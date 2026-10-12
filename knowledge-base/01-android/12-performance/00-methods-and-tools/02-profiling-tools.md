# Android 性能分析工具

**Q1: [learning] 如何用 ADB 查看 Android 设备上某个应用的进程？**

先用 `adb shell pidof <进程名>` 查询正在运行的 PID。需要查看完整进程列表或进程名时，可用 `adb shell ps -A`，再按包名或进程名过滤。应用配置了 `android:process` 时可能有多个进程，主进程名通常与包名相同，私有子进程名通常形如 `包名:后缀`。

没有结果只表示查询时该进程不存在或过滤条件未匹配，不证明应用未安装。先检查设备连接、当前用户和实际 Manifest 进程名，再结合 `dumpsys activity processes` 查看系统记录。

**Q2: [learning] Android Studio 的三种 CPU 采集任务分别适合什么问题？**

按需要的证据选择，三种任务的时间读数不能互换：

1. **System Trace：**记录带时间戳的系统事件、线程状态、调度、渲染与部分系统进程信息。Android 10 及以上采用 Perfetto 格式。先用它区分执行、等待、调度和渲染并圈定窗口；没有显式 tracepoint 的普通方法不会自动展开。
2. **Callstack Sample：**周期性采样运行中的调用栈，给出 CPU 时间分布。已确认时间主要花在 Running 且缺方法标记时，用它找高占比路径；它不记录线程睡眠期间的墙钟等待。
3. **Record Java/Kotlin Methods：**ART 在方法进入和退出时插桩记录调用层级。只有调用顺序仍不清楚时再缩小场景录制；插桩会改变时序，官方建议控制在约 5 秒内，并用无插桩 trace 或基准测试复核绝对耗时。

Android 7.1 及以下的采集量受 File size limit 约束，Android 8.0（API 26）及以上忽略该上限；这不意味着可以无成本长时间录制。

**Q3: [learning] Callstack Sample 的函数占比为什么不能当成单次耗时？**

某函数命中 30% 的样本，表示它在所选采样窗口和事件口径下约占相应 CPU 权重；不说明单次调用持续时间或调用次数。Android Studio 的 Callstack Sample 要求 Android 8.0（API 26）及以上。

解释结果时分开三类限制：

1. **采样缺失：**短于间隔的调用可能没有样本，同一方法的多次调用已被聚合。
2. **墙钟等待：**主线程等待 Binder、锁或 I/O 时不在 CPU 上运行，样本很少也可能是关键路径。用 System Trace 的 Running、Runnable、Sleeping 等状态还原等待。
3. **栈展示：**JNI、ART、`/apex/`、`/system/` 与内核帧可能出现在栈中；隐藏这些帧只改变展示，不改变原始样本。应用 native 采样可由 Simpleperf 后端支持。

**Q4: [learning] 接近发布的性能问题该选 profileable 还是 debuggable 构建？**

优先用接近发布配置的 profileable 变体定位，再在确需 Java 堆细节时缩小问题并制作 debuggable 变体。两者开放的采集能力不同：

1. **profileable：**Android 10（API 29）起，manifest 的 `<profileable>` 允许受控、相对低扰动的 System Trace、Callstack Sample 和 Native allocations。Studio 的低开销 Profile 入口走这一路径。
2. **debuggable：**开放 Java/Kotlin allocations、heap dump 和 Interaction timeline 等完整数据。debug 构建会改变 Java、JNI 与编译优化行为，测到的绝对时间未必代表发布包。
3. **量产现场：**Android 15（API 35）起 ProfilingManager 提供受系统限流和脱敏约束的生产采集入口，适用于开发机不在场的样本。

材料口径的推荐环境为 API 29+、带 Google Play 的测试设备与 AGP 7.3+；采集能力仍须以目标 Studio、设备和构建版本验证。

**Q5: [learning] simpleperf 的 stat、record、report 与 Overhead 分别表示什么？**

simpleperf 的各阶段回答不同问题；Overhead 只是在选定事件上的权重占比，不能一律当墙钟时间：

1. **stat：**汇总事件计数，回答目标累计消耗多少。
2. **record：**按事件触发采样，把程序计数器、线程、映射与可选调用栈写入 `perf.data`，回答消耗发生在哪。
3. **report：**读取 `perf.data`，默认按进程/线程、动态库与符号聚合样本，回答样本归属。

解释 Overhead 前先看采样事件：`task-clock` 表示线程在 CPU 上运行的时间，`cpu-cycles` 还受频率、微架构和迁核影响，`instructions` 是已退休指令数。离散样本占比相近也不证明调用次数接近。采样参数按需求选择：`-f 1000` 表示运行态每秒约 1000 次，线程只运行 200 ms 时约有 200 个样本；`-c 100000` 表示每累积十万个所选事件采样一次；事件的 `:u` 后缀仅统计用户态。

**Q6: [learning] 非 root 设备采集 simpleperf 时怎样判断权限路径？**

先看目标进程是否可采样，再看所用二进制与平台版本；“设备支持事件”不能代替采样授权。常见路径：

1. **profileable 应用：**manifest 声明 `<profileable android:shell="true">` 后，shell 可通过预装工具采样；它不等于 debuggable，也不授予任意进程内存读取权限。
2. **debuggable 应用：**可走 run-as 流程，但构建行为可能偏离发布包。材料按 Android 17 核对的 `app_profiler.py` 默认拒绝此类构建，接受偏差时需显式传入 `--unrepresentative_profile_debug_app`。
3. **root 或 userdebug/eng：**可分析普通发布应用、系统 native 进程或全系统目标。

Android 16 起，材料所引 `environment.cpp` 注释说明侧载 simpleperf 不再拥有所需内核采样权限，优先经 `simpleperf_app_runner` 启动设备内置版本；Android 13 等更早版本优先走 run-as。simpleperf 源码不在本地 AAOS13 树，这条版本差异按材料转写。采集前用 `run_simpleperf_on_device.py list --show-features` 和 `list` 探测能力；`permission denied`、`not supported`、`event not found` 分别指向权限、支持情况和事件名称。

**Q7: [learning] simpleperf 的调用栈模式和丢样该怎样判断？**

默认 `-g` 用 DWARF：内核保存样本寄存器与用户栈，simpleperf 再用 `libunwindstack` 离线展开。它依赖 ELF 的 `.eh_frame`、`.debug_frame`、`.ARM.exidx` 或 `.gnu_debugdata`；材料按 Android 17 核对的单样本栈上限为 65,528 字节，深栈仍可能截断。`--call-graph fp` 则沿帧指针取栈，在保留帧指针的 ARM64 native 代码上开销较低，但 ART Java 栈和 32 位 ARM/Thumb 混合代码可能断栈。模式优劣要比较 `[unknown]` 比例、栈深、热点归属和业务扰动。

出现丢样时依次检查：

1. 降低 `-f`，确认损失是否随采样率下降。
2. 仅在内核丢样时逐步增大 `-m`，即每 CPU 的内核缓冲页数，值须为 2 的幂。
3. 用户缓冲不足或栈被截断时增大 `--user-buffer-size`。
4. 再缩短采集时间、减少目标线程或改用帧指针模式。

每轮只改一个变量，记录 `Samples recorded`、`Samples lost` 与 `truncated stacks`。

**Q8: [learning] simpleperf 的 off-CPU 采样能补齐普通 CPU 采样的什么盲区？**

普通 CPU 采样只在线程运行时发生。线程因锁、I/O 或其他原因离开 CPU 后，等待时间不会出现在这些样本中。`--trace-offcpu` 额外记录 `sched:sched_switch` 与上下文切换时间戳，估算离开 CPU 时停在哪条调用路径；报告可用 `report_html.py --trace-offcpu on-off-cpu` 分开展示两种权重。

使用边界分三项：

1. 整次记录只能选择 `cpu-clock` 或 `task-clock` 中一个事件。
2. off-CPU 权重来自调度时间戳，切换记录或样本丢失会降低精度；先用 `list --show-features` 探测内核支持。
3. 需要唤醒者、Binder 对端、频率或可运行队列的完整时间上下文时，用 Perfetto System Trace 补证，不能从 off-CPU 样本独自推断等待原因。

**Q9: [learning] Arm Topdown 与 SPE 各能定位什么 CPU 微架构问题？**

Topdown（自顶向下微架构分析）把处理器的执行机会按 slot 分成四类，用于判断瓶颈方向：

1. **Retiring：**公式认定已退休的 slot。
2. **Bad Speculation：**错误推测与恢复开销。
3. **Frontend Bound：**前端未及时供给后端的损失。
4. **Backend Bound：**后端资源约束导致的损失。

四类比例不是优化处方；函数热点、调用栈和数据地址仍需采样工具定位。

公式属于具体 CPU 而不属于指令集：slot 宽度（如 Neoverse V1 定义中的 8 slots/cycle）、事件和系数都由该 CPU 的 telemetry specification 定义，不能从 Armv8、Armv9 或 Android API 级别推导。把 Intel TMAM 公式换成 Arm 事件名没有物理含义。材料按 Android 17 核对：simpleperf 可以采集 `raw-stall-slot-backend` 这类原始事件（`simpleperf list raw` 查看），但不计算四类指标。

异构 SoC 上大/小核公式不同、计数不要跨簇相加，事件数超过 PMU 计数器时的分时复用会让比例不可信。SPE（Statistical Profiling Extension，Android 17 simpleperf 新增采集与解码）能按采样指令地址、数据地址和事件位（L1、TLB、分支、LLC 等）定位热点指令，但该版本解码器不输出操作类型、`PERF_SAMPLE_DATA_SRC`、延迟权重、物理地址和可用于逐样本对齐的时间戳，`--spe-cache-miss` 不是 Android 17 的有效命令。

**Q10: [learning] Android Studio Memory Profiler 顶部的内存分类是什么口径？看到曲线上升就宣布泄漏为什么不可靠？**

分类统计来自系统报告的 App 私有已提交内存页，不包含与系统或其他 App 共享的页面，因此不等于 RSS 或 PSS。Graphics 包含图形缓冲区队列、GL surface 和 GL texture 等 CPU/GPU 共享内存，不是独立的 GPU 显存。曲线上升只是候选时段：缓存扩容、延迟 GC、原生分配器保留空闲页、Bitmap 或图形缓冲区的生命周期，以及测试路径没有回到同一状态，都能产生相似曲线。进程 RSS 没有立即下降也不等价于 Java 对象仍存活。泄漏结论需要可重复路径、明确的生命周期预期、多轮 GC 后对象数仍单调增长，以及从 GC Root 追到的引用链。

配套任务上，Java/Kotlin allocation 默认 Full 记录全部分配、可切 Sampled（结果为估计值），Android 7.1 及以下最多保存 65,535 条分配记录而 Android 8.0 及以上没有这项限制（材料口径）。Native allocation 任务按累计分配字节数触发采样（材料标注当前默认 2,048 字节），其 Native Remaining Size 只描述窗口内分配减释放的净变化，不能直接证明窗口结束后仍有泄漏。

**Q11: [learning] LeakCanary 判定一个对象“泄漏”的流程是什么？为什么“等待期和 GC 后仍被保留”不等于“永远无法释放”？**

流程四步（按 LeakCanary 2.14 材料口径）：

1. 生命周期观察器把应结束生命周期的对象交给 `ObjectWatcher`，只保留弱引用。
2. 默认等待五秒并触发 GC；弱引用仍未清除则对象进入保留集合。
3. 保留对象数量达到阈值（应用可见时默认累计 5 个才转储；不可见时等待一个 `retainedDelayMillis`）后调用 Android 堆转储接口。
4. Shark 堆分析器解析 HPROF，从 GC Root 搜索到保留对象的引用路径并按泄漏签名聚类。

它判断的是“对象在等待期和 GC 后仍被保留”，不是“已被证明永远无法释放”——异步任务、动画、消息队列和测试操作尚未结束时对象都可能暂时存活。

默认只跟踪已销毁的 Activity、Fragment、Fragment View、Service 和已执行 `onCleared()` 的 ViewModel，业务对象要在生命周期结束处显式调用 `AppWatcher.objectWatcher.expectWeaklyReachable(instance, reason)`。修复依据是引用链上第一条生命周期不合理的强引用，而不是路径中出现过的熟悉类名。基础依赖应使用 `debugImplementation`，避免把堆转储与分析代码带进 release APK。

**Q12: [learning] adb shell am dumpheap 从 shell 到 ART 经过哪些层？为什么 user 构建上对普通发布应用执行会失败？**

链路分四层（AAOS13 源码核对）：

1. `ActivityManagerShellCommand.runDumpHeap()` 解析参数并创建输出文件描述符；输出路径省略时落到 `/data/local/tmp/heapdump-<时间>.prof`。
2. `ActivityManagerService.dumpHeap()` 检查调用方持有 `android.permission.SET_ACTIVITY_WATCHER`，调用 `enforceDebuggable()` 检查目标进程，并临时调用 `enableFreezer(false)` 关闭缓存应用冻结器。
3. 请求经 Binder 到达应用进程的 `ActivityThread.handleDumpHeap()`；在 `-g` 时执行 `System.gc()`、`System.runFinalization()`、`System.gc()` 序列后按分支选择托管堆、native heap 或 malloc info。
4. 最后由 ART 停止托管线程并写文件。

user 构建上失败的原因是 `enforceDebuggable()` 的实现（AAOS13 核对）：`!Build.IS_DEBUGGABLE && !proc.isDebuggable()` 即抛 SecurityException——user 构建只能转储 debuggable 应用，`profileable` 声明不开放 HPROF。userdebug/eng 构建才允许转储其他目标。Android 13 的选项是 `--user`、`-n`（native heap dump）、`-g`、`-m`（malloc info）。

Android 17 材料口径另有 `-b <png|jpg|webp>` 把 Bitmap 像素写进托管堆转储，属版本差异。`-n` 和 `-m` 的产物不是 HPROF，不能交给 HPROF 分析器。应用在自己进程内调用 `Debug.dumpHprofData()` 不经过 AMS 这组跨进程检查。Freezer 临时关闭的目的是防止目标进程被冻结后无法处理 Binder 请求，托管线程的暂停由 ART 负责，两者是两件事。

**Q13: [learning] ART 生成 HPROF 时为什么整个采集期间都处于停顿？"app/zygote/image"堆空间归类对裁剪文件有什么约束？**

`art/runtime/hprof/hprof.cc` 的 `DumpHeap()` 先进入与 GC 互斥的 `ScopedGCCriticalSection`，再创建 `ScopedSuspendAll(..., true /* long suspend */)` 停止其他托管线程，使对象和引用在遍历期间保持一致。`Hprof::Dump()` 在这个暂停范围内执行两遍——第一遍用计数输出器计算整体大小和最大 record 大小，第二遍写正式数据，停顿覆盖计数遍历、正式遍历和文件输出（AAOS13 源码核对）。

所以代价远不止“扫一遍对象”，耗时随对象数、字段与数组数据量、存储速度和 Bitmap 内容变化，没有一组“每 100 MB 几秒”的数字能跨设备成立。文件头 magic 是 `JAVA PROFILE 1.0.3`，对象 ID 宽度为 4 字节（AAOS13 源码核对，注释明确 hprof-conv 按 4 硬编码）。`HEAP_DUMP_INFO` record 把对象归入 `HPROF_HEAP_APP`（含 app image）、`HPROF_HEAP_ZYGOTE`、`HPROF_HEAP_IMAGE` 三个空间。

zygote 和 boot image 对象通常不是应用泄漏的分配主体，但会出现在 GC Root 路径中、其类与字符串 record 也可能被其他对象引用，直接删除这些二进制区段会得到引用断裂、无法解析的文件——KOOM 一类工具用配套的裁剪器和补全器维护引用关系，手工裁剪前必须理解这一点。

**Q14: [learning] Perfetto 的 android.java_hprof 与完整 ART HPROF 差在哪？它的 fork 管线为什么停顿更短但仍不是零成本？**

两者都来自 ART 托管堆，但产物和停顿边界不同：完整 HPROF（`am dumpheap`、`Debug.dumpHprofData()`）保存类、对象、字段、数组、字符串与 GC Root，能回答“某个字段保存了什么”。`android.java_hprof`（Android 11+ 数据源）只记录类型、对象大小、GC Root 与引用关系，直接写成 protobuf `HeapGraph` 包，不保存实例基本类型字段值、字符串内容、基本类型数组字节和 Bitmap 像素，因此文件更小、隐私面也更小，且能与同一 trace 里的 GC、调度事件做时间对齐。

管线（AAOS13 源码核对关键点）：Perfetto 的 `JavaHprofProducer` 找到目标并授权后发送 `__SIGRTMIN + 6` 信号（`art/perfetto_hprof/perfetto_hprof.cc` 中 `kJavaHeapprofdSignal`），ART 插件的信号处理器只向 pipe 写通知，专用监听线程再进入 GC 临界区、执行 fork——父进程随后尽快恢复托管线程，派生出的子进程遍历 fork 时刻的堆副本并输出 `HeapGraph`。fork 采用写时复制：采集仍有 fork、页表、页面复制、子进程内存与序列化开销，不能描述成零成本。

数据里的 `native_size` 只覆盖 `NativeAllocationRegistry` 为部分 Java 对象登记的原生内存，不能替代 heapprofd 的 native 分配采样。能否采集由 Perfetto 侧的 `CanProfile()` 按 build type、UID 与 manifest 属性判定，与 `am dumpheap` 的权限模型不是同一套。

**Q15: [learning] 排查 native 内存安全错误时，malloc debug、HWASan 与 MTE 应该怎么选？各自的前提是什么？**

按检出目标与运行前提选择：

1. **malloc debug（API 24+）：**在分配器前加入 shim，用 `guard`/`front_guard`/`rear_guard` 检测越界写、`free_track` 提高 use-after-free 检出机会、`backtrace` 记录分配栈，适合错误在分配器检查点才显现的场景；它会改变被测进程的时序与内存行为，不能开在生产或性能基准里。
2. **HWASan：**由编译器插桩并用影子内存保存地址标记，要求 ARM64、重编译目标 native 代码，且使用 libc++ 时必须选 `c++_shared`。Android 10 至 Android 13 上应用运行依赖 HWASan 系统镜像，Android 14 起普通系统才能用 `wrap.sh`（`LD_HWASAN=1`）启动 debuggable 应用（材料口径，AAOS13 属于前一档）。
3. **MTE：**由 Arm CPU 比较指针标记与内存标记，需要 SoC、内核与系统支持，应用通过 `android:memtagMode`（API 31+）请求；`sync` 在出错指令处触发信号、精度高，`async` 精度低。

三者都是测试变体工具。只是想找“哪些调用栈在持续分配”，默认选择仍是 heapprofd 而不是它们。

**Q16: [learning] dumpsys 输出的是哪种证据？为什么 dumpsys cpuinfo 的结果不能叫“瞬时 CPU”？**

`dumpsys` 通过 ServiceManager 找到目标 Binder 服务并调用它的 dump 接口，拿到的是该服务在采集时刻愿意公开的内部状态——或是即时快照，或是某段累计统计窗口，不是时间线。事件先后要靠 Perfetto、Winscope 或日志补足。AAOS13 的 `dumpsys.cpp` 核对：无参数全量模式按服务名排序逐项 dump，单个服务默认等待 10 秒。常用全局参数（写在服务名之前）包括：

- `-t`：超时单位为秒；
- `-T`：超时单位为毫秒，默认 10 秒；
- `-l`：列出注册且可见的服务；
- `--pid`：只打印服务所在进程 PID；
- `--priority`：按 CRITICAL 等优先级筛选。

`--proto` 筛选声明 `DUMP_FLAG_PROTO` 的服务。cpuinfo 正是“累计窗口”的例子：它输出 ActivityManager 后台 `ProcessCpuTracker` 最近两个采样点之间的增量（来自 `/proc/stat` 与 `/proc/<pid>/stat`，AAOS13 存在该类），调用命令的时刻不一定触发新的测量窗口，“瞬时 CPU”的说法高估了时间精度。进程行的 `user`/`kernel` 分列自该进程的两种态 CPU 时间，`iowait`、`irq`、`softirq` 只在 TOTAL 行传入，多线程进程能并行占用多个 CPU、百分比可能超过 100%。

**Q17: [learning] dumpsys activity oom 里的 cur、set、curRaw、setRaw 和 adjType 各代表什么？OOM adjustment 数值高就代表进程会被杀吗？**

OOM adjustment 是系统交给 lmkd 的进程保护分数，数值越大在内存压力下通常越早进入回收候选，但它不表示进程已经发生 OOM。基准值在 AAOS13 的 `ProcessList.java` 核对：`FOREGROUND_APP_ADJ = 0`、`VISIBLE_APP_ADJ = 100`、`PERCEPTIBLE_APP_ADJ = 200`、cached 区间从 900 开始（材料按 Android 17 引用的 `psc/Constants.java` 是新版本文件名，数值一致）。

字段要分开读：`curRaw` 是本轮计算中尚未施加部分修正的 raw adj，`setRaw` 是上次写入记录的 raw adj，`cur` 是本轮计算后的目标值，`set` 是已提交给进程/lmkd 路径的值。`adjType`、`adjSource`、`adjTarget` 说明哪个组件关系（绑定服务、ContentProvider、前台服务、显示器可见性等）抬升或降低了保护级别。发现 `set` 与界面状态不符时，应连同这些归因字段一起核对，不能只用一个数字判定 OOM 计算错误。LMK 的最终归因还要对照 lmkd 日志、PSI 与 `exit-info` 退出原因。

可见进程还可能受 laddering 影响、被分配到 100–199 之间的细分值（材料口径，具体行为受设备配置控制）。

**Q18: [learning] dumpsys gfxinfo 的 reset、framestats 与聚合统计各承担什么？"Number Slow bitmap uploads" 高就是图片解码慢吗？**

`reset` 清空进程内保存的 HWUI 帧数据以划出可重复的测试窗口。无参数输出 since-reset 的聚合统计。`framestats` 输出环形缓冲区里的逐帧列，解析时读取 `---PROFILEDATA---` 之后的 header 按列名建索引——不同版本会增加字段，按固定列号解析会把后续时间戳整体错位，未填充字段可能用 0、负值或哨兵值，计算差值前要过滤。

AAOS13 的 HWUI 源码核对：`JankTracker` 使用固定 120 项的环形缓冲（写满覆盖最早记录），`ProfileData` 同时输出 deadline-aware 与 legacy 两组统计（`Janky frames` 与 `Janky frames (legacy)`）及 90/95/99 分位，deadline 口径按 `GpuCompleted` 是否越过 `FrameDeadline` 判定，`FrameInfo.h` 中存在 `FrameDeadline`、`SyncStart`、`IssueDrawCommandsStart`、`GpuCompleted` 等字段。

分类名是 trace 入口不是根因：`Slow bitmap uploads` 是保留的历史输出名（对应 `SyncStart → IssueDrawCommandsStart` 超阈值），不能凭名称认定发生了图片解码。`Slow issue draw commands` 也不区分 CPU、GPU、队列或 fence 等待。方法级归因回到 Perfetto 的 FrameTimeline、UI Thread、RenderThread 与 GPU 证据。

**Q19: [learning] 排查图层缺失或晚呈现时，Android 13 上 dumpsys SurfaceFlinger 的 --list 与 --latency 怎么用？--frontend 为什么是无效参数？**

Android 13 的 dumper 注册了 `--list`（输出 Layer 精确名称）、`--latency` 与 `--latency-clear`（单 Layer 的历史帧统计）、`--displays`、`--frametimeline`、`--static-screen` 等入口（AAOS13 `SurfaceFlinger.cpp` 核对）。

`--frontend`、`--hwclayers`、`--scheduler` 是 Android 15 之后 FrontEnd 架构引入的视角（材料按 Android 17 核对），在 Android 13 设备上不可用——这是本文最容易被旧资料误导的版本差异。`--latency` 的用法：先用 `--list` 复制精确 layer name，输出首行是采集时刻的刷新周期信息，其后每行是一帧的三个纳秒级时间戳——期望呈现时间、实际呈现时间、帧就绪时间（三元组语义按材料口径，各版本字段含义略有差异）。

`actual − desired` 只能标记晚呈现，不能独立归因，晚呈现可能来自生产者、acquire fence、调度、client composition、HWC 或 present fence。解析要过滤 0、负值和未完成哨兵，三列都有效才计算差值。dump 中 CLIENT（GPU/RenderEngine 合成）与 DEVICE（HWC 硬件路径）的合成方式只代表该次采集附近的状态，跨帧结论应使用 Perfetto、Winscope 或稳定复现实验。

**Q20: [learning] “WLAN rail 在 20 秒窗口增加 4.2 J”与“目标 UID 被 BatteryStats 归因 0.3 mAh”为什么不能放进同一列比较？**

因为它们是两种证据：前者是 ODPM（设备端电源轨监控）或 Power Stats HAL 的系统级硬件计量，rail 是给某类硬件供电的电源轨，能量单位 `uWs` 数值上等于微焦耳，测量的是整个设备子系统。后者是 BatteryStats 用功耗模型按 UID、组件和进程状态归因的估算结论，精度依赖 `power_profile.xml` 与活动时长统计。两类数据的单位、时间范围和系统消耗的分配规则都不同，报告必须写明用的是哪种。ODPM 是设备级测量，前台 App、后台进程、系统服务和硬件自身活动都可能贡献读数，不能自动归因给目标应用。Android Studio Hedgehog 起的 Power Profiler 展示 ODPM 数据，官方高精度 rail 范围是 Pixel 6 及后续 Pixel 设备（材料口径），没有 ODPM 的设备可能只有电池容量、电荷或电流读数。整机供电端的高精度结论则要靠外部电源分析仪，它同样不提供线程级归因。

**Q21: [learning] Battery Historian 时间线里的色块、top_app 和电池百分比分别代表什么？哪些解读是错的？**

Battery Historian 把 bugreport 中的 BatteryStats 历史渲染成时间线，色块只表示事件或状态正在活动（CPU running、userspace wakelock、JobScheduler、Mobile radio/Wi-Fi、GPS/sensor 等），无法读出该组件消耗了多少电量。常见误读有三类：把某一行持续变色当成耗电来源——能量结论还要结合归因统计、rail、线程活动或外部测量。把 `top_app` 当成“耗电最多的应用”——它只表示当时处于前台，提供场景上下文。

在短窗口读电池百分比——百分比来自 fuel gauge（电量计量芯片及估算算法）并取整，短测试里可能长时间不变或一次跳变，短时优化应比较 rail 能量或可重复的系统级指标，长时待机测试再把百分比斜率作辅助证据。时间重叠只能建立相关性：WLAN 活动与一次图片下载重叠只说明该操作值得进一步测量，不能排除同时刻其他进程也在使用 WLAN。WakeLock 行的判断边界是：bugreport 生成时仍被持有的锁值得检查，但录音、导航等任务可能合法持锁，要按持锁原因、生命周期和时长是否与用户可见任务一致判断。

**Q22: [learning] 采集一份可比较的 bugreport 给 Battery Historian，命令顺序和状态开关怎么安排？Historian 现在还能用吗？**

顺序是有状态的，按此执行（AAOS13 `BatteryStatsService` 核对选项存在）：`dumpsys batterystats --reset` 清空当前统计与 history（确认不需要保留现场后执行）。`--enable full-wake-history` 临时打开完整 userspace wakelock 时间线（占用历史缓冲区，只覆盖目标窗口）。断开 USB 让 BatteryStats 进入 on-battery 统计口径，执行固定测试场景。重新连接后 `adb bugreport bugreport.zip` 生成报告。

最后 `--disable full-wake-history` 恢复。直接读文本时，`--charged`、`--history`、`--usage` 与 `-c`/`--checkin` 输出粒度不同，且 `--checkin` 在材料按 Android 17 核对时还带“输出并清除旧统计”的语义，自动化脚本不能把 `-c` 与 `--checkin` 当作等价。Android 7.0 及以上使用 zip 格式 bugreport，包含账号、网络、日志等敏感信息，不应上传到不受信任的在线 Historian 服务。

维护状态：Google 的 battery-historian 仓库已于 2022 年 12 月归档转为只读（材料口径），官方文档仍给出 `stable:3.1` 容器命令但旧镜像可能拉取失败。打不开时直接用 `dumpsys batterystats` 文本、Perfetto `android.power` 或 Power Profiler。Historian 适合回看较长窗口的 wakelock、job、alarm、定位与网络活动，不适合短时函数级测量，也不能从时间线算出某段代码的毫瓦数。

**Q23: [learning] Macrobenchmark 与 Microbenchmark 的进程边界和编译控制有什么不同？“Microbenchmark 数字更稳定”能替代 Macrobenchmark 吗？**

不能互相替代，差别在进程边界与测量对象：Macrobenchmark 的测试 APK 运行在目标应用进程之外，驱动启动、滚动、动画等完整用户场景，要求独立的 `com.android.test` 模块、目标 APK 使用接近 release 的非 debuggable 构建并在 manifest 声明 `<profileable>`。Microbenchmark 在基准测试进程内循环调用被测代码，循环会形成预热缓存与稳定代码路径，结果接近热点代码的最佳情况。分工是：启动、进程创建、系统服务与完整渲染必须由 Macrobenchmark 加系统 trace 判断。

JSON 解析、布局 inflate、列表 item 绑定等可隔离热点用 Microbenchmark，并用 `BlackHole.consume()` 防止编译器把没有外部用途的计算整段删除（材料口径：Benchmark 1.3.0-beta01+ 配合 AGP 8.4.0+ 默认对 Microbenchmark APK 做全量 AOT）。典型证据路径是：Macrobenchmark 发现回归并保留 trace，Perfetto 定位到可隔离函数，Microbenchmark 比较候选实现，再回到 Macrobenchmark 确认端到端改善。

迭代次数没有统一的"10"：先在固定设备上观察分布与热状态，再选择能稳定区分目标回归幅度的数值。

**Q24: [learning] Microbenchmark 的测量协议怎样避免循环、准备工作和编译状态污染结果？**

Microbenchmark 应由 Benchmark 库控制重复执行，循环体只包含被测工作及防止结果消除所需的消费操作。

三种写法测的都不是目标代码：手工循环把被测循环与 `BenchmarkRule` 自己的 warmup 和测量循环叠在一起，样本混合了多套循环节奏。循环内打日志引入 I/O、锁与 Logcat 成本。返回值无人消费时，编译器或 R8 可能把计算连同结果一起删除——需要用 `BlackHole.consume()` 防止消除（stable 1.4.1 中它仍标 experimental 需 opt-in，1.5.0-rc01 才稳定化）。Microbenchmark 的协议要点由此确定：`measureRepeated {}` 的 block 表示一次操作，循环由库管理。固定 seed 的 fixture 在 `@Before` 准备、循环内只放被测工作与消费操作。可变状态在 `runWithMeasurementDisabled()`（旧名 `runWithTimingDisabled` 已弃用，暂停的是全部测量指标不只计时）内恢复且恢复代码保持轻量。UI 场景用 `measureRepeatedOnMainThread()` 避免长测量触发 ANR。

编译口径决定结果可解释性：使用 AGP 8.4.0+ 并应用 `androidx.benchmark` Gradle 插件时，Microbenchmark APK 默认 full AOT 编译，目标是压掉 JIT 稳定期的波动。`androidx.benchmark.forceaotcompilation=false` 退出该默认、结果更接近 warmup 后的 JIT 状态。full AOT 更稳定但不代表用户设备的常态编译状态——依赖 JIT 行为的局部优化要补一组关闭强制 AOT 的实验。两组结果只在编译配置一致时才可比较，报告必须记录 Benchmark、AGP、Kotlin、R8 与该开关。

**Q25: [learning] Macrobenchmark 模块怎样配置目标构建与每轮测量流程？**

Macrobenchmark 由独立 test APK 在目标应用进程外驱动；目标 APK 保持接近 release 的优化设置，测试 APK 与目标 APK 的 debuggable 状态分别判断。

Macrobenchmark 由单独安装的 test APK 从外部进程驱动目标应用（UiAutomator 负责启动、手势与等待），所以目标应用要保持 non-debuggable 并声明 `<profileable android:shell="true"/>`——允许 adb shell 启动的受控工具读取详细 trace，又不会把应用变成 debuggable。工程结构随之固定：`:app` 的 `benchmark` build type 用 `initWith(release)` 继承 R8 与资源压缩、只换成 debug 签名，`matchingFallbacks` 指向 release 让依赖模块正确匹配。`:macrobenchmark` 模块用 `com.android.test` 插件并声明 `targetProjectPath`，test APK 的 benchmark variant 可以 debuggable（与目标应用的 non-debuggable 不要混淆）。`:app` 还要依赖 ProfileInstaller（1.3+），它负责把 Baseline Profile 交给系统，也提供清 shader cache、重置 profile 的命令通道。

每轮的控制流是：先按 `CompilationMode` 重置或编译目标应用（编译状态重置在平台侧依赖 `pm compile`——AAOS13 的 `PackageManagerShellCommand` 提供 compile 子命令并支持 `speed-profile` 与 `--check-prof`），然后执行 `setupBlock` 与 `measureBlock`。`setupBlock` 不计入指标但决定每轮起点是否一致（按 home 键、deep link 载入固定数据、等待就绪标记）。`measureBlock` 执行被测交互，其中的任何等待都会进入 trace 的测量窗口，某项指标是否计入这段等待由该指标的定义决定。每个 measured iteration 生成一份 Perfetto trace，是解释数字变化的直接证据。

**Q26: [learning] 为什么 Espresso 不能用来驱动 Macrobenchmark 的测量区间？UI Automator、Monkey 与 Android CLI 在性能链路里各承担什么？**

Macrobenchmark 的测试 APK 运行在目标应用进程之外，而 Espresso 是白盒工具——它面向目标应用的 instrumentation 环境，`onView()` 前会等待消息队列与 `IdlingResource` 空闲，把它的 matcher 和同步放进 `measureBlock` 会把框架同步、断言与测试代码的开销计入测量。完整性能场景的驱动是 UI Automator：它在测试进程中通过系统级 UiAutomation 连接读取 accessibility node、注入手势，不要求目标应用链接测试代码，因此能驱动保留 release 优化的 APK 并处理权限弹窗。

页面准备放进 `setupBlock`，`measureBlock` 只留固定输入。Espresso 保留在提交验证阶段做功能断言。Monkey 是另一类：生成伪随机事件做稳定性压测（`-p` 限包、`-s` 固定 seed 可重放事件序列、`--throttle` 限速、`--monitor-native-crashes` 监视 native 崩溃），它不控制编译和设备状态，不能用于启动耗时或帧时间回归判定。

Android CLI（材料口径：2026 年 5 月发布 1.0）是运行在开发机/CI 上的主机编排工具：`android run` 只安装并启动 APK、不执行构建，`layout`/`screen` 返回 UI 树与截图，`studio` 子命令做符号与静态检查查询——它不生成帧级测量数据，性能结论仍来自 Macrobenchmark、Perfetto 等采集端。Journey 用自然语言执行路径、存在执行差异，不适合控制计时区间。

**Q27: [learning] 性能 CI 的五个阶段各固定什么？为什么模拟器跑出的基准数字不能进物理设备基线？**

性能 CI 按产物流向固定五个阶段：

1. **构建：**生成接近 release 的 `benchmark` 变体，关闭 debuggable，保留 R8 和资源压缩，只更换 CI 签名。
2. **设备准备：**固定型号、build fingerprint、电量、充电方式、亮度、刷新率与账号后台。
3. **执行：**PR 冒烟以 `dryRunMode` 或少量场景验证脚本；夜间趋势在固定物理设备跑完整迭代，发布候选增加机型。
4. **产物收集：**保存指标 JSON、每轮系统 trace，并在同一次记录中绑定 Git commit、APK 哈希和设备指纹。
5. **回归判定：**只比较同名测试和指标、同一启动与编译模式、同类设备；阈值来自历史方差和业务预算。

AndroidX Benchmark 会把模拟器判为配置错误。即使通过 `suppressErrors` 继续运行，结果也只能用于诊断，不能进入物理设备基线。同一 invocation 的迭代还共享温度、缓存和后台状态，并非独立样本。设备刷机、Benchmark 升级或 Baseline Profile 更新时应建立新基线。

Benchmark 的输出是连续数值，受设备、温度、编译状态与系统负载影响，单次运行越界可能只是噪声——门禁必须先量化噪声再定阈值，判定规则是“差值同时超过业务绝对预算与历史噪声上界才判回归”。

落地步骤按依赖顺序：

1. 固定一小组物理设备，为每台设备建立历史序列。
2. 对 main 分支定时运行，估计每个 metric 的自然波动带（MAD、置信区间或团队已有统计模型），不要在没有历史数据时随意写“慢 8% 就失败”。
3. PR 在同型号、同系统 fingerprint 的设备上运行 baseline 与 candidate。
4. 判定用 `candidateMedian - baselineMedian > max(业务预算, 历史噪声上界)`。
5. 首次越界在同一设备自动重跑，两轮方向一致才阻断。
6. 归档 raw JSON 与每轮 Perfetto trace，供人工复核起点、样本数与异常负载。

样本数决定统计口径：启动跑 10 次适合看 median 与逐次 trace，P95 接近极值——CI 要用 P95 必须增加样本并从 JSON 的 `runs` 计算。帧指标一轮就有大量样本，可看 p90/p95/p99 但要同时看 frame count。两个边界：`androidx.benchmark.suppressErrors` 会把 debuggable、模拟器、低电量等配置错误降级为警告，只可用于验证脚本能否跑通的 smoke job，绝不能出现在性能门禁。模拟器可跑 smoke test，性能门禁使用物理设备。

**Q28: [learning] ProfilingManager 解决什么问题？它是什么时候引入的，Android 13 上能用吗？**

ProfilingManager 补足量产设备上“问题发生时没有开启分析工具”的取证空档：应用可以在运行中请求 system trace、Java heap dump、heap profile 和 stack sampling，采集完成后通过回调拿到结果文件路径，不需要连接开发机。

版本边界必须点明：平台类 `android.os.ProfilingManager` 自 Android 15（API 35）引入，AndroidX Core 1.15.0 起提供 `androidx.core.os.Profiling` 与各 request builder 封装，系统触发（trigger）能力再分 API 36、36.1、API 37 三档（材料口径）。

Android 13 上不存在该 API——本地 AAOS13 树的 frameworks/base 与 packages/modules 下均无 ProfilingManager 对应类（本地源码核对），Android 13 及更早的替代是连接开发机对 debuggable/profileable 构建采集，或降级使用 Android vitals、FrameMetrics/JankStats、`ApplicationExitInfo` 等指标与记录。它属于 Mainline 模块、可经 Google Play 系统更新独立更新，因此设备实际行为可能与固定 AOSP tag 的源码有差异。

**Q29: [learning] 发起一次 ProfilingManager 显式请求要注意什么？“提交成功”等于“采集成功”吗？**

不等于。请求经 Binder 进入系统服务后还要依次通过并发状态、限流与参数检查，任一阶段失败都映射到错误码，只有 `errorCode` 为 `ERROR_NONE` 且 `getResultFilePath()` 非 null 才有文件。限流按 profiling 类型的资源成本在小时/天/周窗口以及应用 UID 与整机预算上分别检查，不是固定“每小时几次”，返回 `ERROR_FAILED_RATE_LIMIT_*` 时应退避而不是循环重试。

显式请求的要素：按问题选 builder——SystemTraceRequestBuilder 看线程时间线与调度交叠、JavaHeapDumpRequestBuilder 看对象存活与 GC Root 引用链、HeapProfileRequestBuilder 看持续分配调用栈、StackSamplingRequestBuilder 要低开销热点线索。`executor` 与 request-specific listener 必须成对传入，两者都为 null 仅在已注册全局 listener 时有意义，没有任何 listener 的请求会被丢弃。

`tag` 只取前 20 个字母、数字和连字符并转小写进入文件名，不能承载用户数据。heap dump 是某一时刻的快照、没有时长参数。`BufferFillPolicy` 只属于 system trace 请求，`RING_BUFFER` 保留停止点前的时间窗、`DISCARD` 保留采集开头的早期证据（材料口径）。结果文件只能通过返回的 `resultFilePath` 访问，成功结果用它去重。`ProfilingResult` 没有唯一结果 ID，失败时路径为 null。

**Q30: [learning] system-triggered profiling 为什么“注册了 ANR trigger 却常常拿不到文件”？APP_FULLY_DRAWN 与 COLD_START 两个 trigger 的窗口差在哪？**

注册 trigger 只是允许系统在条件满足时采样，不保证每次事件都有产物：系统会周期性、带随机性地启动后台 trace 并用 ring buffer 保存最近数据，事件发生时只有后台 trace 正在运行且配额充足，才会生成当前 trace 的快照——后台 trace 未运行、限流命中都会导致“事件发生了但没有文件”。两个启动 trigger 的窗口完全不同（材料按 API 36/37 口径）：`APP_FULLY_DRAWN` 保存触发点之前 ring buffer 里已在运行的后台 trace，适合看启动尾段与触发前的系统活动。

`COLD_START` 为这次冷启动新开一份 system trace 并同时做 stack sampling，使用 DISCARD 保留采集开头，在应用调用 `reportFullyDrawn()` 时停止、未调用时默认约 5 秒停止。两者的起点、停止点和产物都不同，样本必须按 trigger type 分组统计，不能混进同一个分位数。trigger 结果只送到全局 listener，进程重启后可再次投递。

版本上 API 36 提供 APP_FULLY_DRAWN 与 ANR，36.1 增加用户强停、最近任务移除等 kill 类 trigger 与 `requestRunningSystemTrace()`（判断用 `SDK_INT_FULL`），API 37 增加 OOM、COLD_START 等——其中 `TRIGGER_TYPE_OOM` 只覆盖 Java OOM，不含 lmkd 或 native OOM，应用自定义 `UncaughtExceptionHandler` 时必须续调默认 handler。

实验设备可用 `profiling_testing` 的 DeviceConfig 开关关闭限流或指定测试包，测试后必须恢复。

**Q31: [learning] statsd 在系统观测里处在什么位置？pushed atom 与 pulled atom 有什么区别？**

statsd 是平台侧的指标守护进程：接收 atom（用 protobuf schema 描述的结构化事件或状态记录），按 `StatsdConfig` 配置聚合成 metric 报告。它不录制逐线程、逐调度事件的时序——“某类事件是否发生、发生多少次、按 UID 或包名切分后的分布”归 statsd，“这段时间内事件按什么顺序发生”归 Perfetto。Statsd 模块自 Android 11 起以 `com.android.os.statsd` APEX 分发，Android 12 起源码迁移到 `packages/modules/StatsD`（材料口径。

本地 AAOS13 树不含该模块源码，仅 `system/core/libstats` 有日志 socket 接入层，属部分核对）。atom 分两类：pushed atom 由系统服务在事件发生时经 `libstatssocket` 写入（如 `ANROccurred`、`LmkKillOccurred`、`AppStartOccurred`、`WakelockStateChanged`、`ScheduledJobStateChanged`）。

pulled atom 由 statsd 按配置或命令向系统数据源拉取当前状态（如 `KernelWakelock`、`CpuTimePerUid`、`ProcessMemoryState`），pulled 区间的 field number（同时是 atom ID）从 10000 开始（材料口径，本地无 `atoms.proto` 无法直接核对）。statsd 过载时发送端可能收到 `EAGAIN`、写入丢失但不阻塞并维护 drop 计数——所以报告缺条目也不能单独证明事件未发生。

**Q32: [learning] statsd 配置里的 AtomMatcher、Predicate 与各类 metric 各管什么？报告里缺条目按什么顺序排查？**

配置层不关心事件怎么产生，只关心“哪些 atom 算命中、怎么聚合”：

- `AtomMatcher`：按 atom ID 和字段条件匹配事件。
- `Predicate`：用 start/stop atom 表示条件区间（如只统计前台期间）。
- `EventMetric`：保留命中事件明细。
- `CountMetric`：按时间 bucket 计数。
- `DurationMetric`：统计状态持续时长；`SUM` 累加区间，`MAX_SPARSE` 保留已结束区间的最大值。
- `GaugeMetric`：对 pulled atom 或触发事件做周期采样。
- `ValueMetric`/`KllMetric`：聚合数值字段，KLL 用分位数摘要估计分布。

报告缺条目时按数据流顺序排查：

1. `allowed_log_source` 是否放行了写入来源。
2. matcher/predicate 是否真的命中。
3. metric activation 是否已启用。
4. 当前未结束的 bucket 是否被包含。
5. 报告是否已被导出并清除。
6. pull 是否超时。
7. socket 或队列是否发生丢失。

atom 与 metric 要分开看：报告里有条目只证明对应 atom 到达 statsd、命中配置并进入报告，它不能替代 ANR trace、tombstone、Perfetto trace 或 dumpsys 现场。

**Q33: [learning] 本地用 cmd stats 验证一份 statsd 配置的流程是什么？为什么这套命令不能进量产自动化？**

最小流程按配置生命周期执行（材料按 Android 17 源码口径转写，本地树无 statsd 模块源码）：

1. **编码：**用与目标版本匹配的 `statsd_config.proto` 将 textproto 编成 wire-encoded protobuf；`protoc --encode=android.os.statsd.StatsdConfig` 选择消息类型，省略 `--encode` 不会输出供 statsd 使用的二进制配置。
2. **下发：**`adb shell cmd stats config update 123456 < config.pb` 从 stdin 读取二进制配置并以 int64 ID `123456` 登记；省略 UID 时使用调用方 UID，跨 UID 操作只对 eng/userdebug 开放。此处 ID 是本次调试选择的示例值，应与后续命令一致。
3. **验证：**触发场景后用 `adb shell cmd stats dump-report 123456 --keep_data --include_current_bucket --proto` 拉取报告。`--keep_data` 避免默认导出即清除，`--include_current_bucket` 包含未结束 bucket，`--proto` 保留 protobuf 格式；省略这些选项会分别改变保存状态、可见时间窗与输出格式。
4. **清理：**`cmd stats config remove 123456` 移除本次配置；这里不能省略 ID，否则可能操作比预期更广的配置集合。

辅助命令：`print-uid-map` 查 UID 到包名映射，`pull-source <id>` 验证某个 puller 是否工作（它不创建 metric、不证明周期采集成功），`print-stats` 打印 statsd 自身统计，`print-logs` 需要 root。不能进量产的原因在权限模型：`cmd stats` 的 shell 入口只允许 root/shell UID。

`StatsManager` 是 `@SystemApi`，`addConfig()`/`getReports()` 要求 `DUMP` + `PACKAGE_USAGE_STATS` 特权，量产环境的配置与上报由系统镜像、GMS 或厂商机制控制，普通 App 无法下发平台级配置。

**Q34: [learning] 一次 ANR 排查里，statsd、Perfetto、logcat、dumpsys 各能提供什么？为什么缺一类证据结论就会偏窄？**

四类证据回答不同问题：statsd 的 `ANROccurred` 报告回答哪个 UID/进程发生 ANR、reason 字段是什么、处理各阶段耗时是否异常。Perfetto 还原 ANR 前主线程处于 runnable、blocked 还是 Binder 等待、谁持有锁。logcat 还原 ActivityManager 的 ANR 判定路径。

`dumpsys activity exit-info`（`ApplicationExitInfo`，Android 11+）区分 ANR、Java/native crash、low-memory kill、用户请求等退出原因，`lastanr` 保存最近一次 ANR 状态。缺任何一类结论都会偏窄：statsd 只有聚合事实没有时序，Perfetto 不知道平台统计口径，dumpsys 快照只证明当前状态，ANR trace 的线程栈要靠系统保存。LMK 同理——`LmkKillOccurred` 只记录哪个进程被杀，内存压力为什么升高要查 lmkd 日志、PSI、PSS/zRAM 与前后台状态。

工程上的分工原则：线上 APM 负责应用内会话与业务维度，statsd 负责系统侧平台事件维度，后端按时间、版本、设备关联两者。只有系统侧才知道、App 无权限拿到的事件才值得引入 statsd，APM 能稳定采到的不必重复采集。

**Q35: [learning] StrictMode 的 ThreadPolicy 与 VmPolicy 各管什么范围？在 Application.onCreate() 里 setThreadPolicy() 之后，线程池里的磁盘操作为什么检测不到？**

`ThreadPolicy` 只作用于调用 `StrictMode.setThreadPolicy()` 的那条 OS 线程——它不是“主线程策略”，常见写法覆盖主线程只是因为 `onCreate()` 恰好运行在主线程。线程池、Binder 线程和手工创建的线程不会自动继承这份 Java 线程策略，需要各自安装。`VmPolicy` 由 `setVmPolicy()` 安装到进程级静态状态，进程内各线程触发对应检查点时都受约束。两条机制细节（AAOS13 `StrictMode.java` 核对）：每次 `setThreadPolicy()`/`setVmPolicy()` 都会整体替换现有策略。

Builder 启用了 detector 却未显式配置 penalty 时，`build()` 自动补上 `penaltyLog()`。`setThreadPolicyMask()` 会同时更新 libcore `BlockGuard` 的 Java policy 与 Binder native 层保存的策略 mask。多进程方面，`android:process` 创建的进程会重新运行 `Application.onCreate()`，策略与违规限流状态各进程独立。

**Q36: [learning] StrictMode 的磁盘与网络检测点在哪里？为什么 SharedPreferences 后台加载 XML 时主线程卡住、却不报 DiskReadViolation？**

检测点不是拦截每个系统调用，而是 framework 与 libcore 预埋的检查点：`detectDiskReads()`/`detectDiskWrites()` 通过当前线程的 `BlockGuard.Policy` 接收 `onReadFromDisk()`/`onWriteToDisk()` 回调，`detectNetwork()` 走 `onNetwork()`，`detectUnbufferedIo()` 走 `onUnbufferedIO()`（AAOS13 libcore 源码核对），资源只有接入 `CloseGuard` 并调用 `open()`/`close()`/`warnIfOpen()` 才参与泄漏检测。

`SharedPreferences` 是典型盲区：`SharedPreferencesImpl` 在后台线程加载 XML，主线程 getter 只是等待加载完成——磁盘 syscall 不在主线程，主线程 StrictMode 不会产生 `DiskReadViolation`，但界面仍因等待而卡住，此时要看 Perfetto 的线程状态与工作线程 I/O。同类边界还有：直接从 native 代码发起、未经过检查点的 syscall 可能绕过检测。主线程用 `Future.get()` 等待后台 I/O 时检查点在工作线程。检测到磁盘访问不代表该访问一定慢。

`detectExplicitGc()`（显式 `System.gc()` 检查）在 Android 13 是 `@hide`/`@TestApi`（AAOS13 源码与 API 表核对），材料按 Android 17 口径为 API 34 起对 SDK 公开——在 Android 13 上不能直接调用。`noteSlowCall(name)` 只标记调用点、没有耗时阈值，要知道耗时用 trace section 加 Perfetto。

**Q37: [learning] StrictMode 的 penalty 各是什么语义？用 penaltyListener 记录违规时为什么要用独立 Executor？**

penalty 决定违规被检出后的处理：

- `penaltyLog()`：通过 logger 输出违规，相同调用栈指纹会限流。
- `penaltyDeath()`：ThreadPolicy 下在处理末尾抛 `RuntimeException`；VmPolicy 下调用 `killProcess()` 退出。
- `penaltyDeathOnNetwork()`：启用 `detectNetwork()` 后在其他 penalty 之前直接抛 `NetworkOnMainThreadException`，只想记录就不要同时启用。
- `penaltyListener(executor, listener)`：把原始 `Violation` 交给指定 Executor（Executor 参数必填，AAOS13 公开 API 核对）。
- `penaltyDropBox()`：经 ActivityManager 写入系统诊断存储。
- `penaltyDialog()`/`penaltyFlashScreen()`：面向交互式调试。

listener 机制里，回调执行期间 StrictMode 会临时放开回调线程的 ThreadPolicy，避免 listener 内部的日志、入队等操作再次触发自身形成递归，但队列消费与 Executor 生命周期仍由应用负责——回调里不要同步上传网络或做重型符号化，堆栈与明文数据也可能含敏感信息。另一个读数边界：带 Looper 的线程上记录的 `durationMillis` 是从违规时刻到 Looper 完成本轮工作之间的时间，可能包含违规操作后的其他同步代码，不是磁盘 syscall 的精确耗时。同一 Looper 循环的记录数量有上限，日志条数不能当成违规发生次数。

**Q38: [learning] 应用堆栈里出现“发生在 system_server 的 DiskReadViolation”是怎么回事？用 allowThreadDiskReads() 临时放行时要注意什么？**

这是 ThreadPolicy mask 随同步 Binder 调用传播的结果：`setThreadPolicyMask()` 同时更新 BlockGuard 的 Java policy 与 Binder native 层的 mask，发起同步 Binder 调用时 mask 随事务到达服务端 Binder 线程。

服务端触发违规后 `PENALTY_GATHER` 把 `ViolationInfo` 放进 `gatheredViolations` 这个 ThreadLocal，`Parcel.writeNoException()` 把收集到的违规写入 reply——AAOS13 源码核对为最多写前 3 条、每条栈约截断到 20 kB——调用方 `Parcel.readException()` 再经 `readAndHandleBinderCallViolations()` 补上本地调用栈并交给调用方当前策略处理。所以 IPC 间接执行的 I/O 会出现在应用堆栈里。

这套传播只覆盖 ThreadPolicy，远端进程的 VmPolicy 不传播，回传信息也不包含 Binder 调用本身的耗时。临时放行方面：`allowThreadDiskReads()` 只清除 disk-read 检测位，`allowThreadDiskWrites()` 同时清除 disk-write 与 disk-read 位（AAOS13 的 `allowThreadDiskWritesMask()` 实现核对，因为写文件通常伴随读取元数据）。返回值是放行前的完整策略，必须在 `finally` 中恢复，异常、提前返回和嵌套调用都不能让线程永久处于宽松状态。

放行不会让操作更快，注释应写明数据量上限、调用阶段和无法异步化的原因。自动化测试安装策略时，`@Before` 运行在测试线程、直接调用只改变测试线程，要用 `runOnMainSync` 切到应用主线程。依赖 GC 与 finalization 时序的 VmPolicy 违规不适合直接做确定性断言，适合先收集证据。

**Q39: [learning] FrameTimingMetric 的三个输出怎么读？TraceSectionMetric 默认 Mode.Sum 的陷阱是什么？**

`FrameTimingMetric` 输出三类样本：

1. **`frameDurationCpuMs`：**UI thread 与 RenderThread 产出一帧的 CPU duration（API 31 前无法计入 `Choreographer#doFrame` 开始前的时间）。
2. **`frameOverrunMs`：**API 31+ 提供，AAOS13 的 `FrameMetrics.DEADLINE` 已支持。它表示相对帧 deadline 的超期或余量——正值超期、负值仍有余量；变刷新率设备上应优先用它判断 deadline 表现。
3. **`frameCount`：**测量窗口内产出的帧数，用来解释“删掉无效帧后分位数反而上升”这类样本变化。

它仍要与 FrameTimeline、主线程、RenderThread 与 GPU 轨道联读，不能凭一个分位数定位根因。

`TraceSectionMetric` 的陷阱在默认聚合方式：stable 1.4.1 中它标注为 `ExperimentalMetricApi`，默认 `Mode.Sum` 会把全部匹配 section 的时长求和并输出次数——遇到递归或重入 section 时，重叠时间会被重复相加。只取第一条要显式选 `Mode.First`，另有 `Min`、`Max`、`Count`、`Average`。它默认只匹配目标包，且忽略未闭合（`dur = -1`）的 slice。用于基准指标的 trace 名称必须稳定，并打开每轮 Perfetto trace 复核选中的区间是否符合预期。

其他指标的门槛：`PowerMetric`（API 29+）的高精度读数依赖设备 power rail，使用前检查 `deviceSupportsHighPrecisionTracking()`。`MemoryUsageMetric` 适合固定操作窗口，不能替代线上 OOM 与 LMKD 证据。

**Q40: [learning] PerfDog 不要求被测 App 接入 SDK 也不要求设备 root，为什么仍不能用它代替线上 APM 和系统 trace 做根因定位？**

PerfDog 是实验室观测工具：覆盖受控环境中的单台或一组设备，输出帧率、CPU、内存、温度、功耗等外部指标曲线。线上 APM 负责汇总真实用户、真实网络与设备分布下的长期数据。函数、线程或缓存策略级的根因仍要 Perfetto、Android Studio Profiler、simpleperf 或业务埋点提供证据，一条外部曲线证明不了内部实现。

它的适合场景由这个定位推出：QA 在固定设备和固定脚本上做发版回归、快速筛出异常时间段、拿不到源码时观察第三方 App 的外部表现、经 PerfDog Service 接入实验室自动化。字段可用性取决于平台、设备、SoC、驱动与客户端版本，开始前应先查当前设备的可用指标列表，字段为空时不能拿 0 代替“未采集”。GPU 利用率与 Counter 只在部分机型支持，同一设备同一驱动上的版本对比价值较高，跨 SoC 的 GPU 绝对值通常没有可比性。

判断规则：PerfDog 的结论停留在“哪个版本、设备或时间段的外部指标异常”。要回答“哪个函数造成异常”，就把异常区间交给 Perfetto 或源码 trace 继续定位。

**Q41: [learning] PerfDog 的 Jank 用固定的 24 FPS 门槛判定，为什么在 120 Hz 屏幕上不能把这套 Jank 计数直接当系统卡顿分类使用？**

PerfDog 的 SmallJank、Jank、BigJank 用“当前帧时长大于前三帧平均值 2 倍，且大于约 41.67、83.33 或 125 ms”的固定门槛，前两档来自 24 FPS 电影帧时长，不随设备 60、90 或 120 Hz 刷新率变化。Android Vitals、JankStats 与 Perfetto FrameTimeline 的 jank 分类与此不同，口径不能互相替换。

问题出在预算错位：120 Hz 每帧预算约 8.33 ms，一帧 30 ms 已经错过多个刷新周期，却没有达到 SmallJank 的 41.67 ms 门槛。反过来 60 Hz 下一帧 40 ms 只错过约 1.5 帧也可能落进同一档。因此高刷新率测试要同时保留 FTime 分布、P95/P99、1% Low 和连续低帧区间，而不是只比 Jank 次数。Smooth（稳帧指数）数值越低越稳定，官方经验值“游戏或视频小于 8、滑动类 App 小于 20”是产品建议区间而非平台标准，门禁值应先用自身机型与场景建立基线。

帧率数字还依赖窗口选择：一个包名可能同时存在 Activity 主窗口、SurfaceView、TextureView 和独立游戏渲染层，选错窗口测到的是系统 UI 或静止层。测试前确认包名、前台 Activity 与 PerfDog 选中的窗口或 Surface 名称。可用 PerfDog Service 的 `getAppWindowsMap` 或 `dumpsys SurfaceFlinger --list` 的人工核对兜底，layer 名称相似不能当归属证据。
