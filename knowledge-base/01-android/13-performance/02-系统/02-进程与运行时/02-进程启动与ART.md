# 进程、启动与 ART

系统服务依赖内核回收、调度与 Native 故障处理；底层机制见 [内存回收与功耗硬件](../../03-底层/02-内存与能耗/02-内存回收与功耗.md) 与 [内核调度与 CPU 频率](../../03-底层/01-调度与频率/01-内核调度与频率.md)。ART profile 产物由[Baseline Profile 工具主题](../../04-工具/03-基准与交付/12-baseline-profile/01-基线Profile验证.md)说明，native heap 采样与内存错误检测见[heapprofd](../../04-工具/02-性能剖析/05-heapprofd/01-原生堆剖析.md)和[内存诊断工具](../../04-工具/02-性能剖析/08-memory-sanitizers/01-原生内存诊断.md)。

**Q1: [learning] 并发 GC 时代 GC 还会拖慢启动吗，HeapTaskDaemon 在 Perfetto 里 Running 就等于在 GC 吗？**

会，路径有三：并发收集器仍有需要挂起 mutator 的短暂停顿，落在主线程关键路径上直接增加 TTID/TTFD。分配失败或为完成分配而触发的 GC（`heap.cc` 用 `kGcCauseForAlloc` 标记，AAOS13 核对该常量存在）会让分配线程等待。`HeapTaskDaemon` 执行标记、扫描、复制时消耗 CPU、内存带宽与 cache，主线程即使没被挂起也可能 Runnable 变长。

观测上不要把 `HeapTaskDaemon` 的 Running 时间都记为 GC——它还执行 collector transition（前后台回收策略切换）、heap trim（归还空闲页）与启动完成清理，这些 slice 不计入 GC 的 CPU 时间。

正确顺序是先在 App 进程找到该线程，区分 concurrent/blocking GC、trim 与其他 heap task slice，再看主线程在同一窗口是 Running、Runnable 还是卡在 GC wait slice，最后对照 TTID/TTFD——首帧之后的 GC 不能解释 TTID 变慢，长时间 Runnable 更支持 CPU 竞争，明确的 GC wait slice 才证明线程在等回收完成。Logcat 的 ART GC 行默认只记录符合条件的显式或慢 GC，没有日志不等于没发生 GC。

堆结构、GC 分类与 lmkd 机制见 [../../07-memory-storage/01-memory-management.md](../../../07-memory-storage/01-memory-management.md)。

**Q2: [learning] 应用进程 fork 后 ART 对启动期堆做了什么，这等于“关闭了两秒 GC”吗？**

不等于。按 AAOS13 源码核对（`art/runtime/gc/heap.cc` 的 `Heap::PostForkChildAction()`，材料描述的 Android 17 行为在 13 已同构存在）：进程 fork 后若非低内存设备，先把 `target_footprint_` 抬到 `growth_limit_` 并重设并发 GC 启动阈值，减少启动期 GC。2 秒（`kPostForkMaxHeapDurationMS = 2000`）后若期间没有发生过 GC 且无 collector 在运行，把目标降到 `max(growth_limit/4, initial_heap_size)`。

若该值仍高于初始堆，再过 8 秒（fork 后约 10 秒）降到 `initial_heap_size`。随后安排 `TriggerPostForkCCGcTask`：目标时间为最后一次调整后再加 8 秒，加上按 UID 生成的 0–19,999 ms 确定性抖动（进程间错峰），执行时若 GC 计数仍与 fork 后相同，才以 `kGcCauseBackground` 请求一次并发 GC 回收启动垃圾。两个关键边界：这期间 collector 并未关闭——启动分配触及阈值、显式 GC 请求、native allocation 压力或进程状态变化仍会触发回收，放宽的只是堆目标。

该策略只对新 fork 进程，温/热启动复用进程不会重新执行，多进程应用的每个新进程有独立的堆与 post-fork 状态。启动完成清理在 AAOS13 由 `Runtime::NotifyStartupCompletedTask`（`VMRuntime.notifyStartupCompleted` 触发）负责释放 app image 元数据，Android 17 对应类名为 `StartupCompletedTask`——都是 runtime 自身触发，不是第三方应用的 GC 开关。

**Q3: [learning] 系统启动优化怎么定义“启动完成”？为什么 first boot 与 OTA 后首启的样本要单独分组？**

启动完成有多个合法终点：kernel entry（bootloader 交权）、second-stage init、Zygote start、sys.boot_completed=1、Launcher shown、first interactive，指标名必须写明起点与终点（如 kernel_to_boot_completed_ms、boot_completed_to_first_interaction_ms）。sys.boot_completed 由 ActivityManagerService.finishBooting() 在 boot animation 完成后的收尾流程中设置，它不等于桌面已显示，也不等于触摸已有响应。property 置 1 后用户回调、广播与应用进程仍可能占用 CPU 与 I/O。init 会用 ro.boottime.<service> 记录服务首次启动的 CLOCK_BOOTTIME 时间，Zygote 对应 ro.boottime.zygote。

样本分类是因为特殊启动会插入普通启动没有的成本：factory reset 后首启有包扫描与向导，OTA 后首启有 checkpoint、APEX/分区切换与 dexopt，Boot Classpath APEX 变化后首启会触发 ART boot dexopt，userspace reboot 与加密状态变化也各有额外路径。材料的源码口径指出：DexOptHelper.performPackageDexOptUpgradeIfNeeded() 只在 first boot、device upgrade 或 Boot Classpath APEX 变化时调用 ArtManagerLocal.onBoot()，该调用会阻塞且耗时可能超过 30 秒，普通启动直接返回——版本看板不区分启动类型时，P90 很容易被少量升级样本主导。

**Q4: [learning] init 的 Action 队列是串行的，为什么启动期间还能多进程并发？把慢的驱动 probe 或 module 移出关键路径要防什么回退？**

init 队列串行与 service 并发是两个不同层级：

1. **Action 执行：**rc 按解析顺序入队，Action 依次执行，Action 内的 command 也依次执行。
2. **阻塞命令：**`exec`/`exec_start` 会让队列等待目标进程结束；`start`/`class_start` 依次发起服务，但服务启动后的工作彼此并发；`exec_background` 启动后不阻塞后续 command。
3. **表达依赖：**把独立工作放进 service 并发执行，再用 property 表达完成依赖（如 `on property:vendor.prepare_cache.ready=1` 后启动下游），并为失败路径准备超时与降级。
4. **核对触发时机：**`on boot && property:x=y` 只在 boot 事件发生时检查组合条件；boot 已过去、property 后来才满足时，Action 不会补跑。
5. **核对 service 依赖：**把 service 移到更早 class 前，检查分区挂载、SELinux domain、设备节点、APEX 激活与 HAL 依赖。class 只提供分组，不表达依赖图。

kernel 侧把慢 probe 或 module 移出关键路径也有条件：

1. 选择性异步 probe 可让慢速 I2C/SPI 设备及加载 firmware 的设备并行初始化；模块还可通过 `<module>.async_probe=1` 请求异步 probe。官方示例收益 100–500 ms，取决于硬件与驱动，只表示量级。
2. 把非必要 module 从 first-stage ramdisk 移到 second-stage，官方示例可省 500–1000 ms；同样不能当作设备保证值。
3. 异步 probe 后，consumer 发现 supplier（时钟、电源等资源提供方）未就绪时，必须正确返回 `-EPROBE_DEFER`。显示、存储、clock、regulator、thermal 等依赖若表达错误，可能变成更晚的同步等待或功能故障；defer storm 会消耗启动期 CPU。
4. 移动 module 后验证 normal boot、recovery、fastbootd、OTA 与 crash recovery。CPUfreq/devfreq 提前上线前确认 supplier 就绪，并测量频率上升带来的功耗与温升回归。

kernel 段同理：选择性异步 probe 可让慢速 I2C/SPI 设备、加载 firmware 的设备并行初始化（模块还可经 <module>.async_probe=1），官方示例收益 100–500 ms。把非必要 module 从 first-stage ramdisk 挪到 second-stage 可省 500–1000 ms——两个数字取决于硬件与驱动，只能说明量级。风险是依赖表达错误：异步 probe 后 consumer 发现 supplier（时钟、电源等资源提供方）未就绪必须正确返回 -EPROBE_DEFER，显示、存储、clock、regulator、thermal 依赖表达错会把成本变成更晚的同步等待或功能故障，defer storm（大量 probe 反复 defer 重试）会吃掉启动期 CPU。移动 module 后要同时验证 normal boot、recovery、fastbootd、OTA 与 crash recovery。CPUfreq/devfreq 提前上线前要确认 supplier 就绪，并接受频率上升带来的功耗温升回归。

**Q5: [learning] Android 的 lmkd 基线是怎样工作的？AppFlow 式的“预测哪个应用可杀”在现平台上缺什么？**

lmkd 是用户态低内存终止守护进程，按 AAOS13 源码核对（system/memory/lmkd/lmkd.cpp）与材料一致：默认以 PSI 检测压力，常规设备 psi_partial_stall_ms 默认 70 ms（low-RAM 200 ms，指 1000 ms 窗口内 PSI some 的停顿门槛）、psi_complete_stall_ms 700 ms（full 停顿）、thrashing_limit 默认 100%（low-RAM 30%），且每轮因 thrashing 终止进程后按 10%/50% 下调门槛。收到压力事件后还检查 zone watermark、swap 与 direct reclaim。候选选择由 AMS 经 ProcessList 下发的 oom_score_adj 驱动，数值越高越先被杀，kill_heaviest_task=false 时通常取高 adj 分组队尾，搜索进入 PERCEPTIBLE_APP_ADJ 或更重要范围时会强制选 heaviest 以减少终止次数。

现平台缺的是 AppFlow 需要的三样：跨应用文件访问记录与启动预测（平台只有 UsageStats 等授权接口）、内核 vmscan 里的启动窗口与预读页保护（vendor module 不能替换 core mm 的回收语义）、基于 relaunch 基线（ΔM）与返回概率的净释放排序——oom_score_adj 表达组件此刻的重要性，不是预测。协议能力也有版本差异：AAOS13 的 ProcessList 用 LMK_PROCPRIO 逐进程下发 oom_adj，Android 17 才有 LMK_PROCS_PRIO 批量协议（材料口径每包至多 3 条）。无论哪版都没有“保护将启动应用”的策略接口。全局属性（ro.lmk.lowmem_min_oom、kill_heaviest_task、thrashing 门槛）影响整台设备：调高最低可终止 adj 可能在严重压力下找不到候选，增加 direct reclaim 与 kernel OOM 风险，调低则扩大候选集合。

**Q6: [learning] 想借鉴 AppFlow，普通应用、特权 framework 原型、产品级 OS 三种权限下分别能做什么、不能做什么？**

借鉴 AppFlow 的能力取决于权限层级：

1. **普通应用：**优化自身初始化、生成 Baseline Profile、记录 TTID/TTFD、预读自身文件并控制资源读取；可在 `TRIM_MEMORY_UI_HIDDEN` 释放可重建界面缓存，用 `ApplicationExitInfo` 回看退出原因。不能访问 lmkd 控制 socket、修改其他进程的 `oom_score_adj` 或 vmscan，也不能保护 page cache 中的指定文件。跨包使用历史需要 `PACKAGE_USAGE_STATS`，并由用户在设置中授予 usage access。
2. **特权 framework 原型：**可在 platform service 中采集获授权的应用切换序列、维护带版本的文件访问记录，并在 launch observer 前后调度预读；评估预测准确率、I/O 干扰与预算。kernel 未改时预读页仍可能按常规策略被回收，因此要区分“读取内容最终被使用”和“页面留存到使用时刻”，也不要用随意降低 adj 来模拟后台存活收益。
3. **产品级 OS：**需同时改 framework 与 kernel，建立启动会话 ID，传递文件或 inode/offset 标识，在 classic LRU 与 MGLRU 路径定义短时保护，并处理文件截断、更新、卸载与 memcg 迁移；进程排序仍限制在 AMS/lmkd 已允许终止的候选内。GKI 下 vendor module 不能替换 core mm 的回收语义；修改 core mm 会带来长期维护内核分叉与合并安全更新的成本。

三类实现都需要退出条件：出现 kernel OOM、SystemUI/Launcher 被杀、导航或音频中断、P99 变差或读放大失控时，停用优化并恢复原生行为。实验固定 cold/hot/warm 口径与分层负载，再用 ablation 分离读取提前、页面保留与进程选择的贡献。

**Q7: [learning] LowMemDetector 和 lmkd 都消费 PSI，它们的分工是什么？**

lmkd 与 LowMemDetector 的职责不同：

1. **lmkd：**在内存压力下选择并终止进程；选择策略与 oom_score_adj 见 [../../07-memory-storage/01-memory-management.md](../../../07-memory-storage/01-memory-management.md)。
2. **LowMemDetector：**system_server 内的状态管理路径，不负责杀进程。native 层注册 PSI monitor，Java 线程经 epoll 等等级变化，并把压力等级提供给 ActivityManager，作为 memory factor 与进程统计调整的输入。

AAOS13 `LowMemDetector.cpp` 的三个 monitor 阈值如下；这些数值与材料按 Android 17 核对的值一致：

1. **LOW：**PSI_SOME，1 秒窗口内 15 ms。
2. **MEDIUM：**PSI_FULL，30 ms。
3. **HIGH：**PSI_FULL，50 ms。

两个消费者各持一个监听 fd 不表示 PSI 被重复计算。这些阈值是 framework 常量，OEM 分支可能不同，不能直接当作所有设备的性能阈值。

**Q8: [learning] 全局内存压力会直接改变前台应用的 GC 行为吗？**

公开机制上不会：ART GC 的频率由应用自身的分配速率、live set、堆目标、收集器与进程状态决定，没有“PSI 升高后前台切换到更重 GC”的规则。可能同时出现的信号按以下分支归因：

1. **GC slice 变密，PSI、vmscan、kswapd 平稳：**更可能是应用 churn。
2. **PSI 与 direct reclaim 上升，GC 不变：**瓶颈更可能在系统回收或 I/O。
3. **两组信号重叠：**继续检查 CPU 竞争与分配线程调用栈。

两个相邻概念也要分开：kswapd、ZRAM、compaction 消耗 CPU 会挤压 GC 并发线程的调度时间。cached process 被 framework compact（压减驻留）或 freeze（暂停调度）属于进程驻留优化，不等同于 ART 的 Java heap GC。GC 与掉帧的重叠判定（暂停、等待、CPU 竞争四类时间）见 [../../07-memory-storage/01-memory-management.md](../../../07-memory-storage/01-memory-management.md)。

**Q9: [learning] 内存抖动、泄漏、缓存增长在曲线上怎么区分？**

在同一脚本下观察曲线形态与释放动作后的回落：

1. **抖动：**分配速率高、GC 后大幅回落且基线稳定；查分配调用栈与 GC/慢帧重叠。
2. **泄漏：**多轮 GC 后存活基线持续上升；查 heap dump、Retained Size 与 GC Root。
3. **缓存或对象池：**曲线上升到容量上限后封顶；核对容量、命中率与逐出记录。
4. **大对象突发：**单次或少数分配拉高曲线；查分配大小、调用栈与业务事件。

判断时遵守两条边界：

1. 锯齿形只是线索；若每次 GC 后基线也在上升，要同时排查存活对象。
2. 分配很高但帧时间、CPU 和 GC 等待没有退化时，优化优先级可以后移。

一次快照不能证明泄漏，一次 GC 也不能证明资源已释放。验证优化要用同一脚本、同设备、同构建复测分配、GC、帧时间与正确性。

**Q10: [learning] 普通应用为什么读不了 /proc/stat 和 /proc/loadavg？“CPU 使用率”的核等价口径与整机占比口径差在哪？**

AAOS13 源码核对两层限制：`system/sepolicy/private/app_neverallows.te` 用 neverallow 禁止所有 untrusted_app 域读取 `proc_stat`、`proc_loadavg` 等节点——neverallow 在策略编译期强制检查，降低 `targetSdk` 也无法恢复，且 `untrusted_app_all.te` 对读取失败做了 `dontaudit`（只抑制审计日志、不授权，应用侧失败时未必有醒目 denial）。first-stage init 以 `hidepid=2,gid=AID_READPROC` 挂载 procfs（AAOS13 `first_stage_init.cpp` 已核对），其他 UID 的进程目录被隐藏。普通应用稳定可依赖的是 `Process.getElapsedCpuTime()`（AAOS13 已核对）加 `/proc/self/stat` 与自身 `task` 节点。

两种百分比口径不要混用：核等价利用率 = Δ进程 CPU / Δwall × 100%，100% 表示约占用一个核、多线程可超 100%，不需要 `/proc/stat`。整机容量占比 = Δ进程 / Δ全机累计 CPU，需要全局计数，普通应用在 AOSP 上算不了。把核等价除以 CPU 数必须先定义分母（物理核、在线核、cpuset 允许的核或按调度容量加权），移动设备有热插拔与大小核，简单除以 `Runtime.availableProcessors()` 只是近似。分母还应区分设备醒着（`uptimeMillis()`，深度睡眠时停走）与完整窗口（`elapsedRealtime()`，含睡眠）。解析 `/proc/self/stat` 有一个必踩的坑：`comm` 字段被括号包围且可含空格和右括号，按空格切整行会错位，必须先找最后一个 `)`：

```kotlin
val line = File("/proc/self/stat").readText()
val tail = line.substring(line.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
// tail[0] 对应字段 3（state）；utime/stime/starttime 分别为 tail[11]、tail[12]、tail[19]
```

计数回退、`starttime` 变化（PID 复用）或读取失败都要丢弃本轮差值，不能拿 -1 继续算。

**Q11: [learning] ProcessCpuTracker 是什么、谁能用？自建采样器从它身上要避开哪些坑？**

`ProcessCpuTracker` 位于 `com.android.internal.os`，是 Android 框架内部的全进程 CPU 采样类（AAOS13 已核对存在），不属于 SDK API。AAOS13 的 `AppProfiler` 在 `system_server` 内持有它：一次 `update()` 会读 `/proc/stat`、用 `Process.getPids("/proc", ...)` 枚举全部可见进程、对每个 PID 读 `/proc/<pid>/stat` 计算增量（含 `rel_minfaults`/`rel_majfaults` 缺页增量），可选扫描线程目录并读取 `/proc/loadavg`。结果用于 ANR 诊断输出、进程 CPU 统计与 BatteryStats 归因。普通应用同时受两层约束：类本身是隐藏 API、反射调用没有兼容性保证。即使调用成功，SELinux 与 procfs 挂载权限也不会随之绕过——第三方 APM 应使用公开 API 采集自身进程，系统级诊断交给 Perfetto、bugreport 或受控平台服务。

自建采样器要避开它已暴露的三个坑：其一，AAOS13 源码中累计回退检查写成 `if (true || ...)`，该分支实际总会接受读数——自建采样器遇到负差值必须丢弃样本，不能照抄这个行为。其二，同一个类内部就存在不同的百分比定义（`getTotalCpuPercent()` 的忙时间不含 iowait 和 softirq，而 proto 输出的总时间包含），使用内部数据时应直接选取需要的增量字段并在指标协议里写明公式。其三，全 PID 扫描是长操作，它每轮仍要枚举 `/proc`，框架源码注释还要求调用方避免在关键锁路径上持有其锁——应用侧常态只采自身进程累计 CPU，异常窗口再临时加线程维度，且不在主线程读取 procfs（StrictMode 会把这类文件 API 归入 disk read）。

**Q12: [learning] JVMTI/ART TI 在 Android 上从哪个版本可用、谁能 attach agent？agent 的生命周期入口有哪些？**

ART TI（ART 对 JVMTI 的部分实现）从 Android 8.0/API 26 开始提供，公共的 `Debug.attachJvmtiAgent()` 从 Android 9/API 28 开始。运行中附加只允许 `android:debuggable="true"` 的应用——`profileable` 只向受支持的分析工具开放有限能力，不能让发布构建获得 JVMTI attach 权限，不可调试进程会收到 `SecurityException`。AAOS13 源码核对：`art/openjdkjvmti` 存在、`Debug.attachJvmtiAgent()` 已有。attach 由 ActivityManager 与 ART 权限检查把关，agent 经按需加载的 `libopenjdkjvmti` plugin 以 `jvmtiEnv` 调用、以回调接收事件。CTS 会检查可调试与不可调试应用的 attach 边界及 agent 二进制接口稳定性。

ART TI 的三个生命周期入口（AAOS13 `agent.cc` 已核对符号名）对应不同阶段：

1. **`Agent_OnLoad`：**独立 ART 进程通过 `-Xplugin` + `-agentpath` 启动参数加载，用于 ART 自测；应用进程由 Zygote 派生，不走这条路径。
2. **`Agent_OnAttach`：**运行中的 debuggable 进程接受附加，应用调试常用 `adb shell cmd activity attach-agent ...` 或调用 `attachJvmtiAgent()`。
3. **`Agent_OnUnload`：**只在 ART 运行时关闭时调用。没有与 attach 对称的运行中 detach，源码明确不执行 `dlclose()`；需要无 agent 的干净基线时重启进程。agent 与应用共享同一地址空间、没有进程隔离：越界访问、死锁、ABI 不匹配或回调阻塞都会直接影响目标进程。“标准接口”只约束 JVMTI 调用语义，隔离不了 agent 自身的 native 缺陷。

**Q13: [learning] 在 ART 上启用 JVMTI 事件和类重定义会改变什么？“任意 agent 都会让全进程永久解释执行”对吗？**

不对。AAOS13 源码核对（`openjdkjvmti/events.cc`）：事件启用所需去优化分四级——breakpoint、exception、method entry/exit 等是 `kLimited`（不请求全量去优化，但走 ART Instrumentation 的事件路径）。exception catch 是 `kFull`（所有方法与线程进入全量去优化）。field access/modification、single step 等指定线程时 `kThread`、未指定时 `kFull`。thread/class、GC、monitor 等事件是 `kNone`。`kNone` 只表示启用事件本身不进去优化流程，回调频率与数据采集仍有成本。去优化按请求计数，多个 agent 可同时持有，全量去优化可撤销但要等所有请求移除。最小 agent 的正确顺序是：`GetPotentialCapabilities()` 查询并 `AddCapabilities()` 申请、`SetEventCallbacks()` 登记回调、`SetEventNotificationMode()` 启用——GC 事件回调只报告 stop-the-world 暂停对，且发生在 VM 仍暂停期间，多数 JNI/JVMTI 调用不可用，回调里只做原子计数等轻量工作。

类重定义是另一个边界：标准 `RedefineClasses()` 只能替换方法体、拒绝改变 schema（增删方法/字段、改修饰符或继承关系会报错），正在栈上执行的旧方法成为 obsolete method、后续调用才用新定义。ART 扩展 `com.android.art.class.structurally_redefine_classes`（AAOS13 的 `ti_extension.cc` 已核对存在）只支持增量添加方法或字段、不允许删除成员，实现会暂停类加载与分配并使 JIT 代码失效——适合 IDE 受控操作，不能当线上热修复协议。method tracing 的路径还有版本差异：AAOS13 的 `Trace::Start()` 传 `needs_interpreter=!runtime->IsJavaDebuggable()`（非调试运行时走 stub 而非解释执行），Android 17 改为显式传 `false` 且枚举 `kInstrumentWithInstrumentationStubs` 更名 `kInstrumentWithEntryExitHooks`。AAOS13 的 arm64 汇编同时保留 `art_quick_instrumentation_entry` 与 `art_quick_method_entry_hook`，Android 17 已移除前者——按旧版本符号表适配新版本不可行，每个版本都要重核入口协议。

**Q14: [learning] Java 未捕获异常、系统 ANR、Native 致命信号、进程已退出四类现场，为什么不能共用一套采集代码？**

四类现场的运行时状态、权限和安全边界不同，统一塞进一个处理器会把可诊断的故障演变成死锁、二次崩溃或残缺报告。选路依据是现场边界，而不是某个函数平时能否调用。

各现场的能力与限制：

1. **Java 未捕获异常**：ART 通常还能执行 Java 代码，但可能正处于 OOM、栈溢出或锁异常。优先保存传入的 `Throwable` 与预存 breadcrumb，资源允许时才补少量目标线程，不要无限制遍历全部线程或同步上传。
2. **系统 ANR 与调试 SIGQUIT**：进程仍存在，由 ART 的 SignalCatcher 在普通线程上下文执行诊断。应用侧用系统 ANR trace 与事前采样的主线程栈互补，不应把自定义 `sigaction(SIGQUIT)` 当稳定公开接口。
3. **Native 致命信号**：Java 堆、线程栈与运行时锁都可能不一致。处理器内不能调 JNI/Java、不能遍历 ART 私有结构、不能用 `malloc/new`、STL 容器、普通 mutex 和数据库网络，把 Java 版“临时文件 + rename”搬进信号处理器是典型错误。
4. **进程已退出**：进程内 Java 状态已不存在，只能合并 `ApplicationExitInfo`、tombstone/ANR trace 与退出前写好的预存记录，并区分“系统证据”“端侧预判”“服务端推断”三种来源。

**Q15: [learning] ART 收到 SIGQUIT 后怎样生成全线程 dump？为什么普通应用拿不到等价的 monitor 持有关系？**

ART 让相关线程屏蔽 `SIGQUIT`，由专属的 SignalCatcher 线程通过 `sigwait()` 同步等待信号，在普通线程控制流里调用 `Runtime::DumpForSigQuit()`，经 `ThreadList::Dump()` 创建 DumpCheckpoint 让各线程在安全位置协作输出（AAOS13 `signal_catcher.cc` 与 `thread_list.cc` 核对）。这条系统路径可以附加 Java monitor 关系——`waiting to lock ... held by thread N`、`locked ...`、`waiting on ...`——但它不是原子快照，checkpoint、栈遍历与输出都可能耗时，故障中也可能缺帧。

普通应用拿不到等价能力的边界：公开的 `Thread.getStackTrace()` 只返回 `StackTraceElement[]`，不含阻塞对象与持锁对象。ART 的 `CreateAnnotatedStackTrace()` 能构造带 `blockedOn` 与 `heldLocks` 的结果，但它是经 `VMStack.getAnnotatedThreadStackTrace()` 暴露的隐藏 API，AAOS13 中只有 `system_server` 侧的 `WatchdogDiagnostics` 这类平台代码使用。Java SE 的 `ThreadMXBean.findDeadlockedThreads()` 不在 Android 公共 API 清单中。应用侧等价做法是在正常 Java 环境里对目标线程采样 `getStackTrace()`，用 `BLOCKED` 状态与连续样本做疑点标记，锁归属的确认留给系统 trace 或 Perfetto。

**Q16: [learning] kswapd 活跃、App 频繁 GC、进程被 lmkd 杀掉，这三者之间能直接推出因果吗？**

不能。kswapd 运行只证明后台内存页回收被唤醒，它不能单独证明：目标 App 正在使用 swap、主线程的 D 状态来自页换入、lmkd 已经或即将杀进程、ART GC 由系统内存压力触发，更不能证明增加物理内存是唯一修复。GC 频率主要与分配速率、堆容量、对象存活和 ART 策略相关，“kswapd 活跃，所以 App 频繁 GC”缺少中间证据，应核对 GC 区间、分配速率、Java/native heap 与对象保留关系。

继续归因要组合证据：PSI 的 memory `some`/`full`、direct reclaim、major fault、zRAM、进程 RSS/swap、lmkd kill 事件与 `oom_score_adj`（AAOS13 的 `system/memory/lmkd/lmkd.cpp` 核对到其读取 PSI 与内存统计选择目标）。判断压力制造者用对照方向：App 的 RSS 或 heap 持续增长，它可能是贡献者。App 内存稳定而 PSI 与其他进程 RSS 同时上升，系统侧资源管理更值得调查。lmkd 的候选选择还受 `oom_score_adj` 与厂商配置影响，不能把一次 kill 简化成“内存最大的进程被杀”。

**Q17: [learning] lmkd kill 之后的性能损失怎么量化？**

cached process 被终止后内存立即可用，但用户再次进入该 App 时，warm/hot resume 变成 cold start：创建进程、加载代码与资源、初始化 Application 与 ContentProvider、恢复业务状态。代价取决于包体、I/O、编译状态和当时的系统压力，没有固定毫秒数可以概括。

评估时关联五项证据：

1. lmkd kill 的时间与原因。
2. 用户返回该 App 的时间间隔。
3. 温启动变冷启动的比例。
4. 冷启动的 CPU、I/O、首帧与状态恢复耗时。
5. 同一进程是否在短时间内反复启动退出；反复终止与重启说明回收没有净收益，反而延长压力窗口。

App 侧可用 ApplicationExitInfo 识别低内存退出（REASON_LOW_MEMORY）；部分设备不支持精确报告，先查 `isLowMemoryKillReportSupported()`。不能只用末条内存曲线反推死因。

**Q18: [learning] 低内存拖慢冷启动的证据链怎么建？**

先分线程状态再谈代码：如果低内存样本与对照样本的主线程 Running 时间接近，而低内存样本的 D 状态明显更长，应调查存储等待与回收事件，不能仅凭总启动耗时把差异归因到主线程代码。这里描述的是判读模式，不代表某台设备或产品的实测数值。

完整证据链按序建立：

1. 在 Perfetto 圈出启动区间，比较主线程 Running、Runnable、Sleeping 与 D 状态占比。
2. 展开 D 状态对应的内核调用栈，确认等待文件页、块设备还是文件系统锁。
3. 同一时间窗核对 kswapd0、回收 tracepoint、meminfo/vmstat、swap/ZRAM 与 PSI。
4. 核对 lmkd 与 ActivityManager 事件，确认进程是否反复被终止又重启。

只有同一时间窗内出现低内存水位、文件页回收活跃、启动缺页等待存储等证据，才支持该因果链；单次 trace 的同核现象不足以证明调度缺陷。原文调高 extra_free_kbytes 的历史建议不能直接迁移到新产品。修复验收以同场景 A/B 的 PSI、direct reclaim、kswapd CPU 与启动耗时为准，没有量化结果的案例不要补写修复收益。

**Q19: [learning] ActivityManagerService 怎样巡检并终止 CPU 使用过量的缓存进程？**

机制在 ActivityManagerService 的过量 CPU 巡检：对进程状态达到 Home（`PROCESS_STATE_HOME`）或更低重要级的进程，用检查窗口内的进程累计 CPU 时间除以窗口墙钟时间换算成百分比，与按“沦为不重要时长”分四档放宽的上限比较，超限先上报统计再终止。按 AAOS13 源码核对：检查间隔默认 5 分钟（`power_check_interval`），四档上限默认 25/25/10/2（`power_check_max_cpu_1..4`），终止时记录 `ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE` 与 `SUBREASON_EXCESSIVE_CPU`。多线程累计 CPU 可以超过单核墙钟时间，所以这个百分比不等同于性能面板里的整机 CPU 占比，检查间隔与档位是可配置常量、厂商可修改。

版本边界要分清：`REASON_EXCESSIVE_RESOURCE_USAGE` 自 API 30 公开，终止机制本身早已存在（AAOS13 已具备）。Android 17 新增的只是 `ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE`，把这条既有路径接入事后性能分析，且 `ProfilingResult` 只公开触发类型、文件路径、应用 tag 与错误码，只能与时间邻近的 `ApplicationExitInfo`、`processStateSummary` 标“可能关联”，不能声称精确映射。这条路径与 JobScheduler/WorkManager 配额没有直接调用关系。后台任务治理应做有界并发、可分批执行、取消信号传到所有子任务、按失败类型延长重试间隔并保存检查点，不要围绕默认阈值持续轮询。
