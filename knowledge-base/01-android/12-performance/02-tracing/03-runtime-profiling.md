# Android 应用内运行时诊断与插桩

**Q1: [learning] 字节码插桩能改什么、不能改什么？“无业务手写埋点”为什么仍不等于零侵入？**

字节码插桩在源码编译成 JVM `.class` 之后、生成 DEX 之前自动检查或改写指令，只能处理进入当前 App 构建流程的 class。不能修改 Android framework、ART 或任意第三方库的内部实现——普通 App 的 AGP 插桩改不了设备 boot classpath 上的 `Choreographer`、`ActivityThread`、`Looper`，运行时反射或 ART hook 受隐藏 API、版本差异和完整性策略影响，不应作为常规生产方案。帧与主线程监控应优先 JankStats、`FrameMetrics`、Macrobenchmark 与 Perfetto。

可维护的插桩方案分四层，每层有典型错误：选择规则决定哪些 variant、class、method 需要处理（release 无条件全量插桩、误改生成代码是常见错误）。字节码变换要保证控制流、操作数栈和 frame 合法（只处理正常 `return`、异常路径没有清理）。运行时桥接负责采样、限流、隐私处理并写入 Trace/APM（在热路径分配对象、同步 I/O 或递归调用）。验证发布要校验 class、D8/R8 产物与运行时成本（只看编译成功、不检查优化后产物）。“无手写埋点”仍有侵入：每条注入指令都改变 class、构建时间和运行路径，是否启用、覆盖哪些方法以及容许多少成本，都应由当前工程的测量结果决定。

**Q2: [learning] Transform API 已经怎样了？用 AGP Instrumentation API 注册 AsmClassVisitorFactory 要遵守哪些约束？**

旧的 `Transform` API 在 AGP 7.2 废弃、AGP 8.0 移除，没有覆盖所有旧用法的单一替代。按任务选择入口：

1. **逐 class 变换：**使用 `variant.instrumentation.transformClassesWith()`。
2. **读取或变换整组 class：**使用 `variant.artifacts.forScope()` 与 `ScopedArtifact.CLASSES`。
3. **编译后生成 class 且需要读取已编译 class：**使用 `ScopedArtifact.POST_COMPILATION_CLASSES`；该 API 在 AGP 9.3.1 加入并标 `@Incubating`（构建侧版本按材料核对，本地源码树未包含）。
4. **全程序调用图：**逐 class 信息不足时使用 Scoped Artifacts 独立 task。

ASM 有两种常用 API：Core API 通过 visitor 流式处理，适合入口、出口和调用点替换等局部操作；Tree API 读入 `ClassNode` 便于多次遍历与控制流分析，但内存成本更高。

注册 factory 的硬约束：`isInstrumentable()` 与 `createClassVisitor()` 可能被异步、并发调用，factory 不能用可变全局集合统计结果或读写共享文件。所有配置必须声明为 Gradle `Property` 系输入，回调里临时读取未声明的文件会破坏 up-to-date 判断与 build cache 命中。ASM API 版本应取 `instrumentationContext.apiVersion`，不要把上游最新版（材料口径 2026-08 为 9.10.1）写死进 visitor。范围控制：`InstrumentationScope` 默认 `PROJECT`（自有代码），`ALL` 会处理传递依赖、可能重复插桩已插桩库并扩大兼容面。Android library 只能对本项目 class 注册。给既有方法增加控制流时用 `FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS` 让 AGP 重算 frame，只加注解可用成本更低的 `COPY_FRAMES`，`COMPUTE_FRAMES_FOR_ALL_CLASSES` 触发非增量全类计算应避免。

**Q3: [learning] 给方法插 Trace.beginSection()/endSection() 时，为什么只检查 RETURN 和 ATHROW 两条退出路径不够？section 名称与协程有什么限制？**

`RETURN` 和 `ATHROW` 只覆盖显式出现在当前方法字节码中的退出指令：`repository.load()` 抛出的异常会沿调用栈传播，当前方法没有自己的 `ATHROW`，却在 `INVOKEVIRTUAL` 调用指令处退出——只看退出 opcode 时 `beginSection()` 无法配对，后续同线程的嵌套 section 全部错位。稳妥做法是给原方法体加 catch-all handler，语义等价 `try/finally`：正常返回前调用 `endSection()`，异常路径由 handler 调用后原样重抛。ASM 的 `AdviceAdapter` 已处理“构造方法必须等父类构造完成”的限制。注册了新增控制流后必须选择能重算 frame 的 `FramesComputationMode`，并用包含多 return、显式 throw、被调用方法抛异常、try/catch/finally 的 fixture 验证正常与异常路径 begin/end 数量相等。

平台契约（AAOS13 已核对）：`android.os.Trace` 的同步 section 必须在同一线程成对并正确嵌套，名称上限 127 个 UTF-16 code unit（`MAX_SECTION_NAME_LEN = 127`。AAOS13 的 Java Trace 走 libcutils trace_marker 单路径，Android 15+ 才有 TrackEvent 路径可选）。tag 应在构建期生成固定值、替换竖线/换行/空字符并处理截断时的 UTF-16 代理对，且不包含业务参数、URL 或账号。`Trace.isEnabled()` 从 API 29 提供：固定字符串的 `beginSection()` 可以直接调用（平台内部会检查 tracing 状态），只有运行时构造临时对象或格式化字符串时才值得先检查。Kotlin 协程是结构性例外：suspend 函数的主要执行逻辑位于生成的 `ContinuationImpl.invokeSuspend()` 状态机，一次源码调用可能跨线程恢复多次，同步 section 无法表达跨线程生命周期——需要异步 trace 或支持关联信息传播的 API。Compose 则用官方 `runtime-tracing` 而不是基于生成方法名的 ASM 规则。

**Q4: [learning] 插桩产物进入 R8 之后，为什么必须在 minified release 上验证 trace？“注入直接调用”和“反射桥接”对 keep 规则的要求差在哪？**

AGP Instrumentation 产生的 class 会继续进入 D8/R8，R8 的内联、合并、删除和 outlining（把重复指令抽成共享方法的优化）都会改变发布产物中的执行形态，只检查未优化 class 不能证明线上 trace 正确。keep 要求分三类：注入的是直接方法调用时，R8 能从调用图找到桥接类，通常无需为整包加 `-keep`。桥接类通过反射或 JNI 按名称查找时，R8 无法从调用图推导目标，需要精确 keep 规则。监控要求与源码方法一一对应时，必须接受发布产物形态变化并直接在 minified release 上验证。不要为保留 trace 结构对整包禁用优化，正确顺序是缩小插桩范围、确认关键方法在 release 中的形态、再为少量边界设规则。

完整验证链有四步：class 级 fixture 测试（`CheckClassAdapter` 不报 verifier 问题、隔离 ClassLoader 可加载执行、原返回值与异常 cause/suppressed 不变、重复经过插件不二次注入——`ClassWriter.COMPUTE_FRAMES` 能修 frame 但证明不了业务语义）。Gradle 集成测试（clean/incremental、configuration cache、build cache、多 variant 多模块）。minified release 产物检查（APK/DEX 大小、R8 `mapping.txt`/seeds/usage 归档、目标调用存在、排除类未被修改）。代表设备上采集 Perfetto 检查 section 嵌套与 R8 后名称。若 trace tag 保存插桩前类名会暴露内部命名，可改用稳定 method ID 加受控映射，与崩溃栈还原用的 `mapping.txt` 分开保存授权。成本门禁用本工程启用/禁用插桩的 A/B 实测建立，不用文章固定百分比。

**Q5: [learning] 在量产 user build 上，谁有权对一个应用做 native heap 采样？普通 App 想在线上采自己的堆 profile 应走哪条路？**

权限判断同时看会话发起者与目标应用属性（材料按 Android 17 的 `CanProfileAndroid()` 核对。heapprofd 自 Android 10 引入，本地 AAOS13 树无 `external/perfetto`，bionic 侧已核对）：

1. **Shell 或未指定发起者：**目标应用须声明 `profileable_from_shell`（`<profileable android:shell="true" />`）或 `debuggable`。
2. **Trusted-system 发起者：**由 `traced` 赋予受信系统会话身份，普通 App 写入自己的配置也不会获得；目标只需 `profileable` 或 `debuggable`。
3. **平台 UID：**user build 上小于 `AID_APP_START` 的 UID 只允许 trusted-system 发起；isolated process 的判定更严格。
4. **Installer 过滤：**`target_installed_by` 只会缩小目标集合，不会授予权限。AAOS13 已核对 bionic 的 `malloc_heapprofd.cpp`：安装是多步流程，且已有不兼容 malloc hook（如 GWP-ASan/malloc debug 占用 dispatch）时 heapprofd 会拒绝安装。

普通 App 的线上路径是 Android 15+ 的 `ProfilingManager` 请求自己的 heap profile：系统核对 Binder 调用 UID 与包名、配置目标固定为调用方包名、受单应用与全系统限流，请求被接受只表示开始尝试、不保证生成结果文件（`ERROR_FAILED_RATE_LIMIT_PROCESS`/`_SYSTEM`/`_INVALID_REQUEST` 等错误码要分别记录）。`:remote` 等次要进程不会自动加入。AAOS13（Android 13）没有 `ProfilingManager`。QA 复现走另一条路：release APK 声明 `android:shell="true"` 后在 user build 上由 shell 侧 Perfetto/`tools/heap_profile` 采集——它依赖本地调试通道，不代表 App 能在用户手机上自行启动系统采集。

**Q6: [learning] 用 heapprofd 判断 native 泄漏，为什么第一个 dump 不能当基线？哪些“看起来在涨”其实不是 malloc 泄漏？**

heapprofd 只记录采集窗口内经过受支持分配器（默认 heap 为 `libc.malloc`，覆盖 Bionic 的 malloc/free 与 C++ new/delete 路径）抽样到的分配与释放，不扫描完整堆快照——attach 之前已存在的分配没有历史事件，第一个 dump 无法代表完整 native heap。判断泄漏至少需要：两个以上连续 dump（`continuous_dump_config` 周期输出）、可重复的操作序列，以及场景结束后观察是否回落的稳定窗口。用 `heap_profile_allocation` 表按 callsite 汇总正样本（分配）与负样本（释放）得到净存活字节，相同调用栈跨 dump 增长、场景退出后不回落且多轮复现一致，才构成较强线索。

三类常见误判：一是分配器缓存仍保留页面——应用已 free 但分配器保留 arena/page，存活分配估算已回落而 RSS 不变。二是增长来自 mmap 或图形内存——heapprofd 默认不看 `mmap`/`munmap` 直接建立的映射，DMA-BUF 与 GPU 内存也没有分配器调用栈语义，PSS 上升而 heapprofd 平稳时应转查匿名映射、memtrack、`dmabuf_dump`。三是 Java 引用保住 native 对象——heap profile 不提供引用关系，需要 Java heap dump 补充。参数边界：`sampling_interval_bytes` 是平均每 N 字节取一个样本（1 表示逐次），增大降低开销但漏掉小分配。`block_client` 在 buffer 满时让目标进程等待，线上测量通常关闭以免阻塞高频分配线程。daemon 的 memory/CPU guardrail 每 30 秒检查一次、命中后直接关闭对应 data source，且 `max_heapprofd_memory_kb` 限制的是 heapprofd 自身而非目标 App。

**Q7: [learning] 普通应用为什么读不了 /proc/stat 和 /proc/loadavg？“CPU 使用率”的核等价口径与整机占比口径差在哪？**

AAOS13 源码核对两层限制：`system/sepolicy/private/app_neverallows.te` 用 neverallow 禁止所有 untrusted_app 域读取 `proc_stat`、`proc_loadavg` 等节点——neverallow 在策略编译期强制检查，降低 `targetSdk` 也无法恢复，且 `untrusted_app_all.te` 对读取失败做了 `dontaudit`（只抑制审计日志、不授权，应用侧失败时未必有醒目 denial）。first-stage init 以 `hidepid=2,gid=AID_READPROC` 挂载 procfs（AAOS13 `first_stage_init.cpp` 已核对），其他 UID 的进程目录被隐藏。普通应用稳定可依赖的是 `Process.getElapsedCpuTime()`（AAOS13 已核对）加 `/proc/self/stat` 与自身 `task` 节点。

两种百分比口径不要混用：核等价利用率 = Δ进程 CPU / Δwall × 100%，100% 表示约占用一个核、多线程可超 100%，不需要 `/proc/stat`。整机容量占比 = Δ进程 / Δ全机累计 CPU，需要全局计数，普通应用在 AOSP 上算不了。把核等价除以 CPU 数必须先定义分母（物理核、在线核、cpuset 允许的核或按调度容量加权），移动设备有热插拔与大小核，简单除以 `Runtime.availableProcessors()` 只是近似。分母还应区分设备醒着（`uptimeMillis()`，深度睡眠时停走）与完整窗口（`elapsedRealtime()`，含睡眠）。解析 `/proc/self/stat` 有一个必踩的坑：`comm` 字段被括号包围且可含空格和右括号，按空格切整行会错位，必须先找最后一个 `)`：

```kotlin
val line = File("/proc/self/stat").readText()
val tail = line.substring(line.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
// tail[0] 对应字段 3（state）；utime/stime/starttime 分别为 tail[11]、tail[12]、tail[19]
```

计数回退、`starttime` 变化（PID 复用）或读取失败都要丢弃本轮差值，不能拿 -1 继续算。

**Q8: [learning] ProcessCpuTracker 是什么、谁能用？自建采样器从它身上要避开哪些坑？**

`ProcessCpuTracker` 位于 `com.android.internal.os`，是 Android 框架内部的全进程 CPU 采样类（AAOS13 已核对存在），不属于 SDK API。AAOS13 的 `AppProfiler` 在 `system_server` 内持有它：一次 `update()` 会读 `/proc/stat`、用 `Process.getPids("/proc", ...)` 枚举全部可见进程、对每个 PID 读 `/proc/<pid>/stat` 计算增量（含 `rel_minfaults`/`rel_majfaults` 缺页增量），可选扫描线程目录并读取 `/proc/loadavg`。结果用于 ANR 诊断输出、进程 CPU 统计与 BatteryStats 归因。普通应用同时受两层约束：类本身是隐藏 API、反射调用没有兼容性保证。即使调用成功，SELinux 与 procfs 挂载权限也不会随之绕过——第三方 APM 应使用公开 API 采集自身进程，系统级诊断交给 Perfetto、bugreport 或受控平台服务。

自建采样器要避开它已暴露的三个坑：其一，AAOS13 源码中累计回退检查写成 `if (true || ...)`，该分支实际总会接受读数——自建采样器遇到负差值必须丢弃样本，不能照抄这个行为。其二，同一个类内部就存在不同的百分比定义（`getTotalCpuPercent()` 的忙时间不含 iowait 和 softirq，而 proto 输出的总时间包含），使用内部数据时应直接选取需要的增量字段并在指标协议里写明公式。其三，全 PID 扫描是长操作，它每轮仍要枚举 `/proc`，框架源码注释还要求调用方避免在关键锁路径上持有其锁——应用侧常态只采自身进程累计 CPU，异常窗口再临时加线程维度，且不在主线程读取 procfs（StrictMode 会把这类文件 API 归入 disk read）。

**Q9: [learning] minor fault 和 major fault 的计数边界在哪？为什么不能用 Δfaults × 4 KB 估算分配字节？**

minor fault 指内核成功完成、最终不带 `VM_FAULT_MAJOR` 且处理过程中没有 retry 的缺页，常见来源是匿名页按需分配、写时复制（COW）和“页缓存已有数据、只需建立 PTE”。major fault 指成功完成但带 `VM_FAULT_MAJOR` 或发生过 retry 的缺页，对应文件页读入与 zRAM/后备存储的 swap-in。计数时机有三处差异（材料按 Android 17 与 6.18 内核核对，本地 AAOS13 树无内核目录）：Simpleperf 的 `page-faults` 在 arm64 校验 VMA 之前计数，包含后来失败的访问。`min_flt`/`maj_flt` 在 `handle_mm_fault()` 成功返回后才记账，`VM_FAULT_ERROR`（最终 SIGSEGV/SIGBUS 的失败访问）不进入任何 fault 计数——所以 `page-faults` 与 minor+major 之和出现差值是有源码依据的，排查时同时检查失败访问、统计区间和工具权限。

换算陷阱有四个：Android 15 起存在 16 KB 页设备，页大小必须运行时查询（`Os.sysconf(OsConstants._SC_PAGE_SIZE)` 或原生 `getpagesize()`），4 KB 只是旧默认。minor fault 还包含 COW 与页缓存映射，同一虚拟区域被回收后再次访问会再 fault。分配器复用已驻留的堆页时，发生分配却没有新增 fault。readahead 和大 folio 让一次 major fault 与一页 I/O 失去一一对应。fault 计数是同步异常的次数，不等于阻塞时间。major 增长只有与关键线程等待存储在同一时间段出现，才支持“I/O 影响关键路径”的判断。改进手段按原因选：冷启动文件页查 Baseline Profile 覆盖与编译产物布局，匿名页/COW 查初始化时机与 Zygote 继承页写入，swap-in 查内存压力与 zRAM 配置。`madvise(MADV_WILLNEED)` 只是把等待前移的提示，不保证读入或保留页面。

**Q10: [learning] 从系统调用层重建 Binder 调用语义为什么这么难？BINDER_WRITE_READ 的 ioctl 耗时能当 Binder RPC 耗时吗？**

Binder 驱动只处理 transaction code、flags、目标 handle、数据缓冲区和对象 offsets，方法名与 Java/Kotlin 参数类型不在 Binder 内核 ABI 中——要还原“哪个方法被调用”需要四层证据逐级拼接：syscall 层（ioctl 参数与返回值）、Binder UAPI 层（`BINDER_WRITE_READ` 命令字、transaction code、flags、数据长度）、Parcel 层（interface token、字节序列、对象 offsets）、签名表层（descriptor + code 到方法与参数类型的映射）。签名表必须与 `Build.FINGERPRINT`、AIDL 接口版本和采集器版本绑定，同一个 transaction code 跨系统版本不保证对应同一方法。复杂 Parcelable、vendor 接口和动态注册接口都没有系统性覆盖。

ioctl 耗时不能当单次 RPC 耗时：一次 ioctl 的 write buffer 可以包含多条命令，也可能进入读取路径等待工作，oneway 事务不同步等待回复。端到端时延应分段计算：客户端应用 slice + `binder_transaction`/`binder_transaction_received` tracepoint（给出事务调试编号、目标进程/线程、reply 标记）+ 服务端 slice。tracepoint 是元数据，不含完整 Parcel 内容。研究方案（WOOTdroid 的 WDBind）在驱动 `copy_from_user()` 之前读用户地址，存在 TOCTOU、短读和 arm64 地址标签（TBI/MTE）风险，且 v1 不处理 reply——实验只在已 root 的 Android 16 Pixel 上做过，不能外推到量产 user build。权限边界：自定义 eBPF 不是应用公开 API，平台 BPF 程序随系统镜像由 loader 加载（加载链与普通应用的权限边界已在工具文档按 AAOS13 核对）。普通应用的系统侧证据走 Perfetto 与 `ProfilingManager`。

**Q11: [learning] Profilo 的 ATrace provider 到底捕到了什么？“它采集的 trace 与 Perfetto system trace 一样”错在哪？**

Profilo（Meta 开源，上游 2023-02 归档、README 声明 API 不稳定）在 API 27+ 的实现是：`dlsym()` 查找 libcutils 私有变量 `atrace_enabled_tags` 与 `atrace_marker_fd` 的地址，对 `libcutils.so` 的 `__write_chk` 安装 PLT hook，启用时把 tag mask 原子交换为全 1。之后命中 hook 的 ATrace 写入只解析 `B`/`E`（同线程同步 slice 开始/结束）转写进 Profilo 缓冲区并直接返回成功——异步事件 `S`/`F`/`G`/`H`、instant `I`/`N` 和 counter `C` 被静默丢弃，调用方无法从返回值发现丢失。AAOS13 源码核对：`trace-dev.cpp` 先打开 `/sys/kernel/tracing/trace_marker`（失败回退 debug 路径），`atrace_enabled_tags` 是普通 `uint64_t`——Profilo 把 `dlsym` 地址当 `std::atomic<uint64_t>*` 操作属于私有 ABI 假设。

“与 system trace 一样”错在两点：可见范围上它只改本进程的重定向槽，能看到的是当前进程内 framework/ART/HWUI 执行到的 ATrace 点，触不到 `system_server`、SurfaceFlinger，也没有 sched、Binder、块 I/O 等内核事件——它只是一份“当前进程同步 slice 的应用内副本”。完整性上，启用期间命中 hook 的写入不再进内核 trace_marker，与 Perfetto system trace 并发采集会缺本进程的 ATrace 事件，必须禁止重叠或把缺失写进采集协议。继续维护 fork 时应做能力协商（安装失败上报并跳过该 provider）、事件语义测试（嵌套、多线程、缓冲区回绕、启停后 tag mask 恢复）与目标构建的开销实测。其 Java 栈采样依赖的 ART 内部布局版本表只列到 Android 9，不能宣称 Android 17 支持。

**Q12: [learning] 新项目要做线上应用内 trace 或系统级追踪，公共选项有哪些？Profilo 的架构还有什么参考价值？**

公共追踪入口按目标选择：

1. **添加业务标记：**Java / Kotlin 使用 `android.os.Trace` 或 AndroidX Tracing（材料口径中的稳定版 1.3.0）；Native 使用 NDK ATrace。它们只发标记，采集端启用对应类别后才出现在 Perfetto system trace 中。
2. **线上应用内采集：**评估 AndroidX Tracing `2.0.0-beta01`（预发布，可插拔 backend / sink，可写 Perfetto trace packet）或 Perfetto Tracing SDK 的 in-process backend（C++17，不连接系统 `traced`，只含应用注册的数据源）。
3. **系统级因果分析：**使用 Android 10+ Perfetto system trace，同轴组合应用标记、sched、Binder、频率、内存与 I/O。
4. **平台托管 profile：**Android 15+ 可用 `ProfilingManager` 请求；它受限流，不保证执行，结果会脱敏。

Profilo 仍有架构参考价值：provider 按需启停的组织方式、trigger 决定是否采集的控制流、固定容量 mmap 环形缓冲区（写满循环复用旧槽位，读线程落后时会覆盖未读数据）与待处理文件管理，都是线上短窗口 trace 的成熟设计。但直接依赖其上游 AAR 快速上线没有维护方保证：仓库已归档、trace schema 无版本承诺、实现依赖私有符号与 PLT relocation，Java unwinder 依赖旧 ART 布局。两种 in-process 方案都只含应用自己的数据源，不会自动加入调度器或系统进程事件。选择应用内采集后，抽样、大小与频率限制、脱敏、加密、上传与保留策略仍要自己实现——Perfetto 格式只解决数据模型与工具兼容，不替应用决定合规边界。

**Q13: [learning] JVMTI/ART TI 在 Android 上从哪个版本可用、谁能 attach agent？agent 的生命周期入口有哪些？**

ART TI（ART 对 JVMTI 的部分实现）从 Android 8.0/API 26 开始提供，公共的 `Debug.attachJvmtiAgent()` 从 Android 9/API 28 开始。运行中附加只允许 `android:debuggable="true"` 的应用——`profileable` 只向受支持的分析工具开放有限能力，不能让发布构建获得 JVMTI attach 权限，不可调试进程会收到 `SecurityException`。AAOS13 源码核对：`art/openjdkjvmti` 存在、`Debug.attachJvmtiAgent()` 已有。attach 由 ActivityManager 与 ART 权限检查把关，agent 经按需加载的 `libopenjdkjvmti` plugin 以 `jvmtiEnv` 调用、以回调接收事件。CTS 会检查可调试与不可调试应用的 attach 边界及 agent 二进制接口稳定性。

ART TI 的三个生命周期入口（AAOS13 `agent.cc` 已核对符号名）对应不同阶段：

1. **`Agent_OnLoad`：**独立 ART 进程通过 `-Xplugin` + `-agentpath` 启动参数加载，用于 ART 自测；应用进程由 Zygote 派生，不走这条路径。
2. **`Agent_OnAttach`：**运行中的 debuggable 进程接受附加，应用调试常用 `adb shell cmd activity attach-agent ...` 或调用 `attachJvmtiAgent()`。
3. **`Agent_OnUnload`：**只在 ART 运行时关闭时调用。没有与 attach 对称的运行中 detach，源码明确不执行 `dlclose()`；需要无 agent 的干净基线时重启进程。agent 与应用共享同一地址空间、没有进程隔离：越界访问、死锁、ABI 不匹配或回调阻塞都会直接影响目标进程。“标准接口”只约束 JVMTI 调用语义，隔离不了 agent 自身的 native 缺陷。

**Q14: [learning] 在 ART 上启用 JVMTI 事件和类重定义会改变什么？“任意 agent 都会让全进程永久解释执行”对吗？**

不对。AAOS13 源码核对（`openjdkjvmti/events.cc`）：事件启用所需去优化分四级——breakpoint、exception、method entry/exit 等是 `kLimited`（不请求全量去优化，但走 ART Instrumentation 的事件路径）。exception catch 是 `kFull`（所有方法与线程进入全量去优化）。field access/modification、single step 等指定线程时 `kThread`、未指定时 `kFull`。thread/class、GC、monitor 等事件是 `kNone`。`kNone` 只表示启用事件本身不进去优化流程，回调频率与数据采集仍有成本。去优化按请求计数，多个 agent 可同时持有，全量去优化可撤销但要等所有请求移除。最小 agent 的正确顺序是：`GetPotentialCapabilities()` 查询并 `AddCapabilities()` 申请、`SetEventCallbacks()` 登记回调、`SetEventNotificationMode()` 启用——GC 事件回调只报告 stop-the-world 暂停对，且发生在 VM 仍暂停期间，多数 JNI/JVMTI 调用不可用，回调里只做原子计数等轻量工作。

类重定义是另一个边界：标准 `RedefineClasses()` 只能替换方法体、拒绝改变 schema（增删方法/字段、改修饰符或继承关系会报错），正在栈上执行的旧方法成为 obsolete method、后续调用才用新定义。ART 扩展 `com.android.art.class.structurally_redefine_classes`（AAOS13 的 `ti_extension.cc` 已核对存在）只支持增量添加方法或字段、不允许删除成员，实现会暂停类加载与分配并使 JIT 代码失效——适合 IDE 受控操作，不能当线上热修复协议。method tracing 的路径还有版本差异：AAOS13 的 `Trace::Start()` 传 `needs_interpreter=!runtime->IsJavaDebuggable()`（非调试运行时走 stub 而非解释执行），Android 17 改为显式传 `false` 且枚举 `kInstrumentWithInstrumentationStubs` 更名 `kInstrumentWithEntryExitHooks`。AAOS13 的 arm64 汇编同时保留 `art_quick_instrumentation_entry` 与 `art_quick_method_entry_hook`，Android 17 已移除前者——按旧版本符号表适配新版本不可行，每个版本都要重核入口协议。

**Q15: [learning] XTrace 这类“线上动态方法追踪”方案的核心机制是什么？"Non-Invasive"应该怎么理解？**

核心机制是“只改少量目标方法的入口 + 尽量保留原编译执行路径”：把配置指定的方法组成 target set，只更新这些方法的入口（其余方法沿原入口执行，避免全局遍历与改写）。目标方法已有 JIT/AOT 编译代码时选择 ART 的 instrumentation stub、回调后继续执行原机器码，解释态方法走解释器入口（自适应入口）。事件代理注册到方法进入事件，经 JNI 调用 Java 层 interceptor 执行采集动作。论文实验把启动增量压到约 6.5 ms（Frida 对照约 130 ms）、CPU 增量低于 1%，消融显示目标注入主要改善启动与全局 CPU、自适应入口主要改善高频调用的事件成本。1.08 亿 DAU 的 A/B 按 Crash +0.5%、ANR +0.8% 非劣界通过。

"Non-Invasive"是论文中的相对概念：它不改写 DEX 方法体、不做 inline hook，但仍 hook ART 私有函数、定位 `ArtMethod` 并改变方法执行入口——公共 API、Play Integrity 结果、SELinux 策略和厂商 ROM 兼容性都没有随之获得保证。论文把 `UpdateMethodsCode`、`EnableMethodTracing` 称为 "public subset"，这只表示作者选用的 ART 符号子集。按 Android API 定义，这些 C++ 接口仍是平台内部实现（AAOS13 已核对 `InstrumentationListener` 位于 `art/runtime/instrumentation.h`，事件分发前有无监听器的快速检查）。证据边界也要分清：论文报告按 arXiv v1（无源码、无 SDK），兼容范围自报 Android 5.0–15+，性能数字来自单台 Android 15 设备。Android 17 的入口与 tracing 路径已变化，适配远多于替换符号名。普通线上应用没有等价公共 API：目标在 Framework、事件短到采样难以命中且必须记录每次调用时，这类方案才有独特价值，且属于运行时平台工程。其余场景用 `ProfilingManager` 栈采样、编译期插桩或短录制的 `Debug.startMethodTracing()`。
