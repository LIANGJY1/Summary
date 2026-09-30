# CPU 与体积优化

> 学习资料（文章模式沉淀）。主线：应用侧 CPU 适配（线程池、进程采样、周期任务、过量 CPU 终止）、ADPF 能效验证、热节流降载与包体积治理（DEX、Native SO、资源、App Bundle 分发）的工程方法与版本边界。源文档：android-internals-wiki §25.7《应用层 CPU 优化实战指南》、§25.8《PerformanceHintManager 与 ADPF 能效验证》、§25.9《热节流适配与性能退化治理》、§25.10《应用体积分析与优化：DEX、Native SO 与资源》、§25.11《App Bundle 与按需分发》；可本地核对的机制按 AAOS13 源码（Android 13）核对并标注版本差异（`setThreads`/`setPreferPowerEfficiency`/PowerMonitor 为 API 34/35 才有，AAOS13 上不存在；`ScheduledThreadPoolExecutor` 的错过周期最多补跑一次为 Android 16 起；`ThermalManagerService` 在 AAOS13 位于旧路径 `power/` 下），工程实践按材料口径转写、不确定处已弱化；16 KB 页对齐的 Google Play 要求（面向 API 35+ 的 64 位应用、2027-02-01 起强制、NDK r28 默认对齐）已与官方文档核对。调度器、DVFS 与 ADPF 机制层见 [../cpu-power/01-调度与功耗框架.md](../08-cpu-power/01-调度与功耗框架.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 应用层做 CPU 优化时，为什么"CPU 高"不能直接指向改法？墙钟时间与线程 CPU 时间、Runnable 与 Blocked 各回答什么问题？**

"CPU 高"只是现象，动手前先回答三个问题：哪段用户路径受影响（启动、滚动、后台同步还是持续计算）、损失在哪个维度（延迟、CPU 时间、能耗、温升还是后台额度）、证据能否归因到具体任务与调用栈；线程池参数只是其中一个控制点。指标语义各不相同：墙钟时间回答"用户等了多久"，包含运行、排队、锁等待、I/O 与抢占；线程 CPU 时间回答"代码在 CPU 上跑了多久"，不含大多数睡眠与阻塞；Runnable 是线程已具备运行条件但没排到 CPU 的时间，可能来自并发过量、优先级或系统竞争；Blocked/Sleeping 是在等锁、I/O 或条件，此时 CPU 低也可能响应很差。

定位时用两者互查："墙钟高但 CPU 低"优先查排队、锁、I/O 与调度等待；"CPU 时间高"再查调用栈与任务来源。同样的 CPU 时间在不同频率和核上能耗不同，温升要单独观察。优化通常落在四件事：少做工作、减少同一时刻的并行、减少唤醒与切换、把非紧急工作交给系统调度时机。

**Q2: ThreadPoolExecutor.execute() 的三段决策顺序是什么？为什么"workers = availableProcessors()"这类公式不能当工程规则？**

`execute()` 的决策顺序是：当前 worker 少于 `corePoolSize` 时先尝试创建核心 worker；否则把任务放入 `workQueue`（入队后复查池状态）；队列拒绝入队时再尝试创建非核心 worker，仍失败才执行拒绝策略。由此直接推出队列行为：用大容量队列时任务长期停在第 2 步，`maximumPoolSize` 几乎不参与调度；用 `SynchronousQueue` 时没有存储槽位，提交方更容易触发创建非核心线程——两种队列是"排队还是增加并发"的取舍，没有固定优劣。默认配置下 `keepAliveTime` 只回收超出核心数的 worker，`allowCoreThreadTimeOut(true)` 才让核心线程可超时退出。

线程数没有跨应用公式：`Runtime.availableProcessors()` 只是 JVM 当前可用的逻辑处理器数，不是性能核数量，任务内部还可能调用并行库，设备受前后台、温度与功耗策略影响。做法是从任务模型出发：纯计算任务先以较小的受控并行度做基线，观察吞吐、P95/P99、Runnable 等待与温度；阻塞任务的并发上限取决于下游容量、连接池、文件描述符与超时，不能用一个大固定值代替分析；混合任务拆开计算与阻塞阶段分别受限。队列容量的单位是"任务个数"，要按峰值到达率、可接受排队延迟、每个排队任务持有的内存与任务过期价值共同约束。调参时一次只改一个主要变量，并按 `image_decode` 这类低基数标识记录提交/开始/结束时间、排队与执行时长、拒绝次数和分位数，不要把 URL 或用户 ID 放进指标维度。

**Q3: 有界计算线程池为什么常配 AbortPolicy？CallerRunsPolicy 在什么条件下危险？线程工厂里 `Process.setThreadPriority()` 为什么必须写在新线程体内？**

固定大小的有界池让并发量和积压量都可控，`AbortPolicy` 用 `RejectedExecutionException` 明确报告已满，提交点据此合并同类请求、取消过期工作、返回降级结果或转交可跨进程重启的持久化调度器。`CallerRunsPolicy` 会在提交线程里直接 `run()`：如果提交方是主线程、Binder 线程或持锁的回调线程，计算或阻塞工作就转移到那里，只有能证明提交线程允许执行该任务时才能用它做反压。

`setThreadPriority()` 默认修改调用线程，所以要在 `ThreadFactory` 里于新线程启动后调用，否则改的是提交者线程。它表达的是 nice 相关的相对优先级，能力边界要写清：不指定大小核、不把线程移入 cpuset、不设置 uclamp、也绕不开后台与温控策略；提高优先级不减少工作量，设置不当还会挤压 UI、RenderThread、Binder 等关键工作。

**Q4: 应用进程怎么用公开 API 采样自己的 CPU？"等效占用核数"怎么算？/proc 解析有哪些陷阱？**

Kotlin/Java 层用 `Process.getElapsedCpuTime()`（进程启动以来消耗的 CPU 毫秒）与 `SystemClock.elapsedRealtime()`（含深度睡眠的单调墙钟）做两次采样，CPU 增量除以墙钟增量得到"等效占用核数"：1.0 表示窗口内约用一个核，多线程并行可大于 1.0；再除以逻辑处理器数只能作为容量占比的粗略提示，它不考虑大小核性能差异、频率与调度限制。窗口太短受毫秒精度影响，太长掩盖尖峰，长度由要观察的用户路径决定；告警线来自场景基线与设备分层，不要内置"高 CPU 阈值"。NDK 可用 `times()`：`tms_utime + tms_stime` 是线程组累计 tick，返回值是 elapsed tick、绝对值无业务含义，只比较窗口差值，每秒 tick 数用 `sysconf(_SC_CLK_TCK)` 取，不要由内核 `CONFIG_HZ` 推算。

解析陷阱有三类：`/proc/self/stat` 的进程名 `comm` 在括号内且可能含空格，不能整行按空格切分取固定下标，要先定位右括号再解析（`utime`/`stime` 是其后的第 14、15 字段）；`/proc/stat` 首行的 `guest`/`guest_nice` 已包含在 `user`/`nice` 中，十个字段全加会重复计算虚拟 CPU 时间，通常只累计 `user` 到 `steal` 的前八项；`iowait` 很难可靠计算甚至可能下降，不能当"整机空闲、现在预加载安全"的信号。`/proc/stat` 是整机聚合数据，idle 高也不知道用户是否即将触摸、设备是否处于热约束；这些读取还可能因设备策略或 SELinux 失败，采样必须允许返回"无数据"。同理 `MessageQueue.IdleHandler` 只表示当前 Looper 队列暂时没有到期消息，不代表整机空闲，预加载与重计算应交给受控执行器并能在状态变化时取消，需要延后或带约束的后台工作用 WorkManager/JobScheduler 表达。

**Q5: scheduleAtFixedRate 与 scheduleWithFixedDelay 语义差在哪？任务抛出未处理异常后周期任务会怎样？Android 16 起补跑行为有什么变化？**

`scheduleAtFixedRate()` 按预定起点维持固定节拍；`scheduleWithFixedDelay()` 从上一次执行结束后再等指定间隔。周期任务一旦抛出未处理异常，后续周期默认不再执行——必须捕获或用包装任务保证异常不外抛；取消后应启用 remove-on-cancel 或明确清理队列，避免积压任务继续占用内存。任务因进程冻结、CPU 挂起或单次执行过长错过周期时，旧实现可能连续补跑：面向 Android 16（API 36）及以上的应用默认启用 `STPE_SKIP_MULTIPLE_MISSED_PERIODIC_TASKS`，回到可执行状态后最多立即补跑一次；AAOS13 的 libcore 实现没有这套时间校正逻辑，仍可能连续补跑，跨版本要分别验证。

平台变化只管 `ScheduledThreadPoolExecutor` 自己的追赶，业务手写补发循环与三方 SDK 定时器要按语义分类：前台且要求相位用 fixed-rate 并在生命周期结束时取消；只要求"每次完成后间隔一段时间"用 fixed-delay；可延期、跨进程重启、带约束交给 WorkManager/JobScheduler；用户可感知的精确时刻用合适的 AlarmManager 接口。升级 targetSdk 时还要检查广告、埋点、APM、IM 等 SDK 内部定时器，不能只测应用自有线程池；采集"冻结 → 恢复 → 首帧完成"区间，确认后台任务没有与主线程、RenderThread 和网络重连同时争用 CPU。

**Q6: 缓存进程因 CPU 使用过量被系统终止的机制是怎么工作的？为什么不能把它当成 Android 17 新增或与 JobScheduler 配额绑定？**

机制在 ActivityManagerService 的过量 CPU 巡检：对进程状态达到 Home（`PROCESS_STATE_HOME`）或更低重要级的进程，用检查窗口内的进程累计 CPU 时间除以窗口墙钟时间换算成百分比，与按"沦为不重要时长"分四档放宽的上限比较，超限先上报统计再终止。按 AAOS13 源码核对：检查间隔默认 5 分钟（`power_check_interval`），四档上限默认 25/25/10/2（`power_check_max_cpu_1..4`），终止时记录 `ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE` 与 `SUBREASON_EXCESSIVE_CPU`；多线程累计 CPU 可以超过单核墙钟时间，所以这个百分比不等同于性能面板里的整机 CPU 占比，检查间隔与档位是可配置常量、厂商可修改。

版本边界要分清：`REASON_EXCESSIVE_RESOURCE_USAGE` 自 API 30 公开，终止机制本身早已存在（AAOS13 已具备）；Android 17 新增的只是 `ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE`，把这条既有路径接入事后性能分析，且 `ProfilingResult` 只公开触发类型、文件路径、应用 tag 与错误码，只能与时间邻近的 `ApplicationExitInfo`、`processStateSummary` 标"可能关联"，不能声称精确映射。这条路径与 JobScheduler/WorkManager 配额没有直接调用关系；后台任务治理应做有界并发、可分批执行、取消信号传到所有子任务、按失败类型延长重试间隔并保存检查点，不要围绕默认阈值持续轮询。

**Q7: "CPU 占用不高但用户等很久"指向锁竞争时，应该按什么顺序优化？为什么不能假设 synchronized 固定"自旋后休眠"？**

低 CPU 高等待通常先查锁。ART 中 `synchronized` 由 monitor 实现，无竞争处理、竞争策略与运行时状态随实现改变，应用不能依赖"先忙等固定次数再进内核休眠"的固定流程；等锁线程一般不持续吃满一个核，但竞争会增加尾延迟、线程唤醒、上下文切换和优先级反转风险；反过来 CAS 在激烈竞争下反复失败消耗 CPU——`synchronized`、`ReentrantLock`、原子变量之间没有脱离负载的固定胜者。

优化按证据顺序推进：先用 Perfetto 或自加 trace 标记找到等待时间长、调用频繁的临界区；缩短持锁范围，锁内不做 I/O、Binder 调用、回调与重计算；优先用线程封闭、不可变快照或消息传递减少共享可变状态；确认单锁竞争后才评估分段锁等更复杂结构；最后用相同负载比较前后的墙钟、CPU 时间、等待分布与内存成本。把一把锁拆成多把可能增加一致性维护难度，把锁换成 CAS 可能把阻塞等待变成忙碌重试，改动要用 trace 与基准确认。

**Q8: Dispatchers.Default 与 Dispatchers.IO 是什么关系？limitedParallelism() 视图承诺什么、不承诺什么？**

`Dispatchers.Default` 面向 CPU 计算，`Dispatchers.IO` 面向阻塞 I/O；JVM 上两者共享线程资源，切换 dispatcher 不一定创建新线程。`limitedParallelism(n)` 限制的是"该视图同时执行的协程数量"，不承诺对应固定数量的物理线程；`Dispatchers.IO` 的受限视图有弹性扩容语义——每个视图有自己的并行上限、不受 IO 常规上限的统一约束，但仍与它共享线程资源。

工程含义：把 CPU 任务写成 `suspend` 不会减少指令与 CPU 时间，仍要分批、取消过期任务并控制并发；阻塞并发上限要与数据库连接池、服务端限流、文件描述符和内存预算匹配；不要在主线程用 `runBlocking` 阻塞等待后台结果；结构化并发统一管理子任务的生命周期、取消与错误传播，但不补偿不受控的工作量。这也解释了为什么弹性线程池不能直接登记给 ADPF Session——协程会跨 TID 迁移，系统收到的线程集合会与真实负载分离。

**Q9: 把一组周期性工作线程标记为"能效优先"（setPreferPowerEfficiency）适合什么场景？它承诺省电吗？**

它表达的是"这些线程可以安全地偏向能效而不是性能"的调度偏好，前提是工作有稳定周期、deadline 有余量、线程集合稳定，系统才有机会选择能耗更低的核或频率。后台批量压缩、日志归档、离线索引适合；本地模型批处理、批量转码先小流量验证；游戏主循环、相机预览、低延迟音频要谨慎（deadline 紧）；输入响应、点击后首屏、滚动帧提交与偶发 JSON 解析不适合。

它不承诺省电：没有 PowerMonitor、Perfetto 电源轨或 BatteryStats 数据就不能写收益，结论必须限定为"启用后，在某设备、某场景、某测试窗口内，单位任务能耗下降"。业务没有余量时，平均耗时可能看似稳定而 P95/P99 变差；发布按"耗时不退化，再看能耗"执行，尾部耗时或失败率退化先关闭提示，并支持按机型单独关闭。版本边界：该方法自 API 35 提供，AAOS13（API 33）上不存在，且它只是偏好——不承诺具体频点、大小核选择或能耗下降比例。

**Q10: PerformanceHintManager.Session 的生命周期与线程归属要注意什么？createHintSession 为什么可能返回 null？**

Session 表示一组共同完成同类工作的线程：`tids` 是线程 ID 数组且必须属于当前进程，目标耗时必须为正；设备不支持 hint session 时创建返回 `null`（AAOS13 源码中 `nativeSessionPtr == 0` 即返回 `null`；"线程不属于本应用"等错误参数行为按 Android 17 材料口径：非法参数抛 `IllegalArgumentException`）。会话线程应长期存活，`close()` 是释放不是暂停，下一轮长期工作要重建；`updateTargetWorkDuration()` 只在刷新率、批次大小或业务 deadline 变化时调用；`reportActualWorkDuration()` 在每个周期结束后上报实际耗时，按 `SystemClock.uptimeNanos()` 理解。

Session 不是线程安全对象，创建、`setThreads()`、上报与关闭应由同一管理者串行执行；`setThreads()`（API 34）替换完整线程列表而不是追加，且不是 oneway 调用，不能放在高频关键路径。协程会跨 TID 迁移：`Dispatchers.Default`、`Dispatchers.IO` 与 `limitedParallelism()` 都不承诺挂起恢复后回到同一 TID，Session 绑定的是 Linux TID（`Process.myTid()`），不是 Java 线程逻辑 ID。适合 ADPF 的负载应由单线程或小规模固定 Executor 承载、线程启动后采集 TID、一个周期内不切换 dispatcher；线程池重建时 API 31–33 关闭旧会话重建，API 34+ 可在生命周期边界整体替换线程数组但不能每周期更新。

**Q11: Android 13（API 33）上能用 PerformanceHintManager 的哪些能力？API 34/35/36 各加了什么？**

按 AAOS13 源码核对，API 33 已有 Java 基础能力：`createHintSession(int[], long)`、`updateTargetWorkDuration()`、单值 `reportActualWorkDuration()`、`close()`；NDK 基础 manager/session API 也在 API 33 可用（材料口径）。之后的能力按版本递增：API 34 加 `setThreads()`（Java 与 `APerformanceHint_setThreads()`）；API 35 加 `setPreferPowerEfficiency()` 与 `WorkDuration` 分项时长；API 36 加 NDK 创建配置、能力探测 `APerformanceHint_isFeatureSupported()`、图形管线模式、`APerformanceHint_borrowSessionFromJava()` 借用 Java 会话，以及一次性负载通知（`notifyWorkloadIncrease/Reset/Spike`），同版本废弃 `getPreferredUpdateRateNanos()`——只是探测能力时应改用 `isFeatureSupported()`。

系统侧服务在 AAOS13 已存在（`frameworks/base/services/core/java/com/android/server/power/hint/HintManagerService.java`），调用路径是 framework JNI → 原生客户端 → HintManagerService → Power HAL：高频的目标/实际耗时更新在设备支持时走 FMQ，失败回退 Binder，所以不能笼统写"每帧必定一次 Binder"；系统还会按 UID 的进程状态暂停或恢复 Session，应用调用成功不代表 HAL 每条都采纳。

**Q12: PowerMonitor / SystemHealthManager 的能耗读数怎么用？为什么相邻两次读数可能出现零差值？**

API 35 起（AAOS13 上不存在），`SystemHealthManager.getSupportedPowerMonitors()` 返回监测项列表：`POWER_MONITOR_TYPE_MEASUREMENT` 是直接测量的电源轨（ODPM），`POWER_MONITOR_TYPE_CONSUMER` 是建模能耗消费者（可能由多轨组合或共享轨拆分，如 Wi-Fi 与 Bluetooth 共用芯片）；`getPowerMonitorReadings()` 异步返回读数，监测项不受支持时 `onError()` 返回 `IllegalArgumentException`。`getConsumedEnergy()` 返回本次开机以来的累计能耗，单位 μWs（数值等于 μJ），不跨重启、含电池与插电状态；找不到对应项返回 `ENERGY_UNAVAILABLE = -1`。差值必须配对 `getTimestampMillis()`（elapsedRealtime 基准）解释：两次时间戳相同，差值就不代表业务窗口。

零差值与波动的来源是 Android 17 r1 的实现细节：普通调用方有 20 秒读数缓存（持有隐藏系统权限的精细路径是 250 ms），公开读数还要经过 `IntervalRandomNoiseGenerator` 在"不低于上次原始读数减 10 J"的下界与当前值之间返回按 UID 稳定的随机值——调用频率高于刷新阈值只会反复命中缓存与扰动。这些常量不是公开 API 对所有版本的保证。电源轨名称与覆盖由厂商定义，同名不等于同一硬件范围，监测项索引不保证跨重启稳定、不能持久化；短任务优先用 Perfetto 电源轨或外部功耗仪，公开读数适合较长窗口的重复对照。

**Q13: 怎么验证能效提示真的有效？没有 PowerMonitor 的设备怎么降级？**

按"固定设备、温度起点、输入数据与负载，同时看耗时与能耗"做 A/B：A 组固定线程 + 周期上报，B 组再加 `setPreferPowerEfficiency(true)`；指标看 P50/P90/P99 周期耗时、超 deadline 比例、单位任务能耗（ΔμWs / 完成任务数，按监测项分列）与热状态；通过条件是 P90/P99 不超业务阈值、能耗降幅超过测量噪声、不更早进入 thermal throttling，结果按机型、SoC、系统版本、刷新率与充电状态分别统计，不把一台设备上的电源轨收益外推。

Perfetto 用 `android.power` 数据源并打开 `collect_power_rails`，同时采集 cpu_frequency、sched 与应用 trace section，把提示窗口、线程运行、频率与电源轨放到同一时间线；开发阶段执行 `adb shell dumpsys performance_hint` 核对 Session 的 PID/UID/TID、目标耗时与 PowerEfficient 状态。降级按设备能力三档：PowerMonitor 与电源轨都有时可写单位任务能耗但限定设备场景；只有部分电源轨时写实验室证据、不写 API 差值结论；两者都缺时只用 BatteryStats、Power Profiler、CPU 时间与业务耗时写趋势和风险。线上小流量只把提示当实验变量、采轻量业务指标，不要把 PowerMonitor 当高频采样接口。

**Q14: 应用侧热治理的公开入口在哪？为什么查不到"系统当前启用了哪些热缓解动作"？**

Android SDK 没有公开的 `ThermalManager` Java 类，应用入口在 `PowerManager`：`getCurrentThermalStatus()` 与 `addThermalStatusListener()` 自 API 29，`getThermalHeadroom(forecastSeconds)` 自 API 30（AAOS13 源码即有，允许范围 0–60 秒）；`getThermalHeadroomThresholds()` 为 API 35、`addThermalHeadroomListener()` 为 API 36，AAOS13 上没有。NDK 侧另有 `AThermalManager` 句柄与 `AThermal_*` 函数，两者命名不同。系统内部服务是 `ThermalManagerService`——AAOS13 位于 `services/core/java/com/android/server/power/ThermalManagerService.java`，Android 17 移到 `power/thermal/` 子目录，旧资料路径不能直接套用。

应用能读全局热状态与 thermal headroom，但查不到缓解动作列表：原始温度、冷却设备（CoolingDevice）档位与特权 Binder 方法需要 `DEVICE_POWER` 之类特权权限；限制 CPU、GPU、充电或屏幕的动作发生在内核、固件与厂商策略层，不需要等应用回调。应用能调整的只有自己的工作量：目标帧率、渲染分辨率、画质、并发度、预取与非紧急后台工作。

**Q15: 全局热状态是怎么聚合出来的？为什么不能从它反推某个 CPU 集群的频率？**

`ThermalManagerService` 遍历缓存温度，只取 `TYPE_SKIN` 的传感器并采用最高限制等级作为全局热状态（AAOS13 源码 `onTemperatureMapChangedLocked()` 即如此）。推论：CPU 传感器很热但表面温度限制等级未升时，全局状态可能仍低；多路 SKIN 传感器同时上报时取较高一路；上报来源可能包含电池功率约束、充电与厂商虚拟模型，公开回调没有原因字段。

热状态 0–6 对应 NONE 到 SHUTDOWN 七档，应用按档位分层响应：LIGHT 停止高成本预取与投机工作；MODERATE 降低非必要画质、采样率与后台并发；SEVERE 切到可持续帧率、码率、模型或并发档位；CRITICAL 保留主要功能、减少热源；EMERGENCY 停止可选管线并尽快持久化状态；SHUTDOWN 不能依赖应用仍有执行机会——任何传感器到 `SHUTDOWN` 时系统会以 thermal-state 原因请求关机，状态保存不能拖到这一档才开始。档位只定义响应强度：游戏从 120 帧降到 90/60/30 还是更低、视频降多少分辨率，都要由目标设备的帧时间与温升实验决定，不能把档位换算成固定频率或画质。

**Q16: 怎么证明一次性能退化是热节流造成的，而不是普通 DVFS 或负载变化？**

需要按时间顺序凑齐五步证据：固定输入形成持续负载；热状态、headroom、温度阈值事件或冷却档位发生可重复变化；CPU/GPU 频率上限、可用容量或完成时间显示资源受约束；工作量稳定时线程执行时间、Runnable 时长、GPU 完成时间或帧时间随后恶化；设备降温或去掉单一负载变量后，限制与退化按预测回落。缺少第 2、3 项时，观测到的低频可能只是 DVFS 按普通负载做出的选择；缺少稳定工作量与对照组时无法排除业务负载自身变化；不回落还要检查省电模式、刷新率切换、后台争用与厂商短时提频策略。

Perfetto 里把 `ThermalManagerService.status` 计数器、热状态与冷却设备事件、`cpu_frequency_limits`、调度器、FrameTimeline 与渲染线程放同一时间窗，单条异常帧或一次温度读数不能完成归因。测试可用 `adb shell cmd thermalservice override-status 3` 模拟 SEVERE 回调验证应用档位切换——它只覆盖 Framework 全局状态、不会真的升温或降频，测试结束必须执行 `reset`，即使中途失败也要恢复。

**Q17: 热状态升高时的降载状态机要满足什么条件才不会反复振荡？**

三个条件：进入快、退出慢、档位内聚。热压力升高时快速减少工作量；恢复要等 headroom 低于退出阈值并持续一段冷却驻留时间，再逐级增加——进入与退出采用不同阈值（滞回）避免在边界抖动，余量与驻留时间都来自产品实验，不是平台常量。每个档位对应一组不可拆分的配置，渲染、相机、推理、网络与后台在同一次切换采用同一档，避免渲染已降载而相机或推理仍高负载；限速时要同步限制生产速率并丢弃过期输入，否则积压的帧、相机输入与推理请求继续消耗资源，降载无效。

实时管线（实时通话、导航、录制）不能只按能耗目标降级，要满足业务 SLA。后台任务侧，Android 17 的 `ThermalStatusRestriction` 随档位收缩 JobScheduler 可运行范围（LIGHT 开始限制 MIN priority，SEVERE 及以上限制所有非 TOP_APP job；AAOS13 树没有这个独立类，行为按材料口径转写），任务实现都要支持停止、保存进度和幂等重试。Doze、App Standby、Battery Saver 与热限制是独立状态机，可以同时生效，平台没有面向应用的"叠加系数"，排查延迟任务时要同时看待执行/停止原因与各状态。

**Q18: 体积治理要先固定哪四种口径？为什么"下载少 1 MB，安装空间未必同步少 1 MB"？**

四种口径分别回答不同问题：上传制品（APK/AAB 本身多大）、设备交付集合（固定 ABI、语言、密度与模块后设备拿到哪些 split）、安装占用（已装 APK、提取的原生库、ART 编译产物与应用数据）、运行时映射（DEX、SO、资源的文件页、私有脏页与共享页如何进进程）。下载减少不等于安装同步减少：未压缩条目让 APK 文件变大，却可能免去安装期提取并支持直接映射；`.vdex`/`.odex`/`.art` 是安装与运行期产物，占设备存储但不计入商店下载字节；磁盘文件大小也推不出 PSS。

APK 结构上要防误读：`classes*.dex` 保存字节码；`resources.arsc` 与 `res/` 是编译资源，`assets/` 按原始文件接口读取，静态缩减器不能仅凭代码判断 asset 是否仍被使用；`lib/<abi>/` 保存各 ABI 的 ELF；`META-INF/` 有 V1 签名产物不代表只用了 V1——V2/V3 签名位于 APK Signing Block。对比基线必须用同一发布变体、构建工具、签名流程和设备规格，每次只改一类变量，用 APK Analyzer 先按增长目录（DEX、资源还是 lib/）选择后续路径。

**Q19: "64K 限制"到底限制什么？Defined Methods 与 Referenced Methods 差在哪？**

限制的是单个 DEX 的 `method_ids` 引用表（65,536 项，`field_ids` 同级有 16 位上限）；计数包含应用代码、依赖和该 DEX 引用的 Android/Java 平台方法，不等于源码中声明的方法数。APK Analyzer 里 Defined Methods 只统计本 DEX 定义的方法，Referenced Methods 统计该 DEX 的方法 ID 表——定义在其他 DEX 或平台中的方法仍占当前 DEX 的引用条目，所以决定是否触发上限的是 Referenced。

65,536 解释了为什么需要 multidex，却不能充当体积预算：两个版本的引用数相同，方法体、字符串与调试信息差异仍可能让字节数相差很大；反之引用数下降也可能伴随 DEX 变大。索引区的增长常由依赖、生成代码和宽泛 keep 规则推动——即使方法体很短，一个方法仍会增加 method、type、proto、string 多处数据。

**Q20: R8 的 keep 规则为什么容易膨胀？怎么找到"是谁保留了这段代码"并安全放宽？**

R8 的三类收益（裁剪不可达代码、优化改写、缩短名称）都受 keep 配置约束：一条裸 `-keep` 同时阻止 shrinking、obfuscation 和 optimization；AGP 8.0 起 R8 Full Mode 已是默认，旧项目里的 `android.enableR8.fullMode=false` 兼容开关要删除才恢复全模式。规则膨胀的典型来源是包级 `-keep class com.example.** { *; }` 止崩后长期保留、`@Keep` 整类标记，以及依赖 AAR 携带的 consumer rules——"应用自己的 proguard-rules.pro 很干净"不能证明 keep 配置健康。

治理方法：规则只保护运行时协议真正需要的部分，先回答反射、JNI、序列化或 WebView bridge 访问的是类、成员、名称、签名还是注解；WebView bridge 已由静态代码创建时用 `-keepclassmembers,allowoptimization` 只保带注解的方法、允许优化方法体，而不是保留整个包。定位工具：`-printconfiguration` 输出合并配置，`-whyareyoukeeping` 追踪意外存活类（只用于本地诊断分支），配合产物中的 configuration.txt、seeds.txt、usage.txt 与 mapping.txt 归因；AGP 9.3 起的 R8 Configuration Analyzer（外部工具链口径）把最终配置量化成 Shrinking/Optimization/Obfuscation 三个分数并给出 Blast Radius 表，可先于完整构建定位宽规则。放宽前必须补动态入口测试、分批发布与线上崩溃监控；mapping.txt 随发布归档，否则线上混淆栈无法还原。

**Q21: minSdk ≥ 21 的应用还需要 multidex 兼容处理吗？DEX 个数多会拖慢启动吗？**

Android 5.0 / API 21 起 ART 原生加载 APK 内的 `classes2.dex` 等多个 DEX，minSdk ≥ 21 不需要 `androidx.multidex` 安装器，也不需要为类可见性维护旧版主 DEX 类清单——那是 Dalvik 时代为安装器在加载次级 DEX 前保证类可见的方案（minSdk ≤ 20 才需要，且 `MultiDex.install()` 完成前只能可靠访问主 DEX）。这个历史边界不应套到现代应用。

DEX 个数不是启动耗时公式：多 DEX 有文件头、索引与对齐等结构成本，但启动开销取决于启动路径触达的类与方法、它们的布局局部性、Baseline/Startup Profile 是否匹配当前二进制、有无可用 VDEX/OAT/App Image 以及存储速度与缺页；把 `classes3.dex` 合回 `classes2.dex` 不保证启动变快，为减少文件数而加 keep 或打乱布局可能更差。安装后的 `.vdex`/`.odex`/`.art` 占设备存储、不计入商店下载。Startup Profile 由 R8 调整 DEX 布局让启动类集中到首个 DEX（机制与 AGP 版本边界见 cpu-power 邻居文档），验证目标是启动类是否进入 `classes.dex` 与 TTID/TTFD，而不是某个单文件的大小。

**Q22: Native SO 的 strip 与节区分析怎么做？哪些字节删了会出问题？**

AGP 默认对发布库执行 strip：完整符号表与 DWARF 调试信息（`.debug_*`）移入独立符号制品（`SYMBOL_TABLE` 可还原函数名，`FULL` 还原到源文件与行号），strip 后的 `.so` 进包，两者必须来自同一次链接（重新链接会改变地址布局与 Build ID）。节区语义决定删什么安全：`.text`/`.rodata` 占文件字节且只读映射；`.data` 同时占文件与进程私有脏页；`.bss` 是 `SHT_NOBITS`，占运行内存但不占同量文件字节，`llvm-size` 的大 BSS 值不能当 APK 增长；`.dynsym`/`.dynstr` 与必要重定位是可运行 ELF 的一部分，`--strip-all` 也不能删；`.eh_frame`/`.ARM.exidx` 等栈展开元数据删除前必须用受控 Native 崩溃验证 tombstone、C++ 异常与 profiler 回溯仍工作。

比 strip 更有收益的是让不可达代码消失和缩小导出面：`-ffunction-sections`/`-fdata-sections` 加链接期 `--gc-sections` 删除不可达节区（这是链接期 GC，不是运行时内存回收）；`-fvisibility=hidden` 与 version script 控制符号可见性，JNI 库从 `JNI_OnLoad()` 调 `RegisterNatives()` 后常见公开入口只剩 `JNI_OnLoad`；LTO/ThinLTO 与 ICF 要保存 linker map、节区增量与性能数据评估，ICF 激进模式可能合并地址相同的函数，影响依赖函数地址唯一性的逻辑。多 AAR 同名同路径 `.so` 冲突时 `packaging.jniLibs.pickFirsts` 只选构建系统遇到的第一份——不比较 SHA-256、build ID 与 SONAME，只有在证明候选可互换后才能用。

**Q23: 安装期系统怎么为一个包选择 ABI？16 KB 页对齐要满足哪两层？动态加载 .so 有什么新要求？**

按 AAOS13 源码核对，`PackageAbiHelperImpl.derivePackageAbi()` 按包是否 multi-arch、设备支持的 ABI 顺序与是否提取原生库选择分支；需要提取时调用 `NativeLibraryHelper.copyNativeBinariesForSupportedAbi()`，其内部 `findSupportedAbi()` 选择设备 ABI 列表中排名最靠前的匹配项；`extractNativeLibs=false` 时仍选择并校验 ABI 但不复制 `.so`——未压缩且满足对齐的库可直接从 APK 映射，代价是 APK 可能因不压缩而变大，报告要同时记录下载量与安装占用。

16 KB 是两层独立对齐：ELF 内每个 `PT_LOAD` 的 `p_align` 要支持 16 KB，且未压缩 `.so` 在 APK ZIP 中的起始偏移按 16 KB 对齐。NDK r28+ 默认生成对齐 ELF，r27 及以下要显式传 `-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384`，AGP 8.5.1+ 处理未压缩库的 ZIP 对齐；Google Play 要求面向 Android 15/API 35 及更高版本的 64 位应用支持 16 KB，2027-02-01 起不满足的应用更新无法发布（已与官方文档核对）。对齐可能增大文件（段间空隙与 ZIP 填充），增量由布局决定、没有固定百分比。版本边界：Android 17 的 Safer Native DCL 要求目标 API 37 应用用 `System.load()` 加载前把文件设为只读、否则抛 `UnsatisfiedLinkError`（AAOS13 无此要求）；Android 17 还提供 fatal 兼容测试属性让不兼容立即中止，兼容路径只用于迁移、不能替代 Play 要求。

**Q24: 资源体积优化中，"删"与"换"分别要守住哪些边界？**

删的边界在引用图与限定符回退。资源缩减依赖代码缩减（只有被删除代码引用的资源才会被删）：safe mode 会从字符串常量推测动态引用而保守保留，strict mode 只信显式引用与 `tools:keep`，迁移要先记录 safe mode 额外保留的原因再逐个消除；`Resources.getIdentifier()`、拼接资源名、通知、App Widget、清单元数据与反射 R 字段都在静态引用图之外，`tools:discard` 强删运行时仍会访问的资源会导致 `Resources.NotFoundException`。限定符是运行时选择规则不是冗余：语言、密度、夜间、最小宽度与版本限定符删除某候选后系统会回退并可能产生缩放、布局或语言错误，裁剪要在对应配置设备上验证；语言过滤用 AGP 8.8 起的 `androidResources.localeFilters`（旧 `resourceConfigurations` 已弃用），它过滤依赖翻译但不会生成缺失译文，关闭语言拆分会增加首装下载、换来离线切换可靠。

换的边界在格式版本与解码成本：WebP 各模式 Android 8 / API 26 起支持；AVIF 自 Android 12 / API 31，minSdk 26 应用要保留 WebP/PNG 回退资源（如 `drawable-v31` 目录），且回退会增加 AAB 上传总量；VectorDrawable 适合图标与路径图形，不适合照片（pathData 膨胀与 inflate 成本）；JPEG/WebP/AVIF/MP3 等已压缩格式再套通用压缩收益有限，`noCompress` 换取随机访问与直接映射但下载字节增加。硬规则：已签名 APK 不能再重压或用第三方资源混淆工具改写条目，任何转换必须放在签名前的受支持构建阶段；9-patch 的拉伸区元数据不做普通格式替换。

**Q25: AAB 上传后设备实际收到什么？bundletool get-size total 两条命令为什么不能相减？**

AAB 是发布格式不是安装包，设备不会安装 `.aab`；Google Play 按 AAB 生成并签名 base APK、按 ABI/语言/密度拆分的 configuration APK、feature APK，以及可能的 install-time 资源 split。本地用 `bundletool build-apks` + `get-size total --device-spec` 估算某设备的压缩传输大小：不带 `--modules` 统计首次下载时安装的所有模块；带 `--modules` 指定集合时会把所选模块的依赖一并计入。两者口径不同（首装全集 vs 指定集合含依赖），不能相减推导单个 feature 自身大小；未提供设备规格时结果可能是设备维度的最小-最大值，不能当单机基线。

三种尺寸要分开记录：AAB 上传大小（检查发布制品）、设备交付大小（由 SDK 版本、ABI、语言、密度、模块集合与交付时机决定）、安装后占用（受原生库提取、dexopt、资产展开与应用数据影响）。`build-apks` 本地签名可用 debug key，仅适合测试；渠道发布仍要用 Play 测试轨道或渠道环境验证。

**Q26: on-demand 动态特性模块的工程边界有哪些？为什么 base 不能引用 feature 的实现类？**

交付方式按行为分：install-time（默认、随安装、不减首装）、conditional（按设备特性、国家或最低 API 条件交付）、on-demand（运行时触发下载）、deferred install（后台尽力预取、无法跟踪进度）与 deferred uninstall（请求返回不代表文件已消失）。on-demand 流程经 `SplitInstallManager`：`startInstall()` 成功回调只返回会话 ID，必须等监听器报 `INSTALLED` 才能进入功能；`REQUIRES_USER_CONFIRMATION` 要走确认对话框；`FAILED`/`CANCELED` 保留基础功能并提供重试；监听器按页面或进程生命周期注销，进程重建后用 `installedModules` 与会话状态恢复，不能只依赖内存变量。

依赖方向是硬边界：feature 可以访问 base 的公共 API，base 在编译期不能引用 feature 实现，共享接口、错误类型与路由协议放 base 或独立 API 模块；feature 不重复声明签名、`versionCode` 与 `minifyEnabled`，附加 keep 规则构建时全量合并。`dist:title` 字符串放 base（模块下载前系统也要读取）；`dist:fusing=true` 决定模块能否进入需要融合的 universal APK。刚装完的一段时间内，平台可能无法应用 feature 新增的 manifest 组件、通知等系统界面也可能访问不到 feature 资源，通知图标与故障页应放 base；已安装 feature 随应用更新由 Play 管理，不要自建独立版本协议。

**Q27: 非 Play 渠道怎么替代 AAB 能力？为什么"运行时下载 DEX/SO"不能当体积方案？**

按渠道能力逐项降级：支持 AAB 的渠道按其文档上传验证，不能假设分包、签名或动态交付与 Google Play 相同；只收单 APK 的渠道构建 universal APK 或 per-ABI APK——`bundletool --mode=universal` 只融合 manifest 中 `dist:fusing=true` 的模块，多 APK 的 `versionCode`、签名与升级兼容要自管；受控安装器必须一次提交匹配设备的 base 与全部必需 split 并处理失败回滚。平台侧校验是硬约束：安装会话要求每个 split name 唯一、包名/版本/签名一致，缺必需 split 返回 `INSTALL_FAILED_MISSING_SPLIT`（机制按 AAOS13 的 `PackageInstallerSession` 口径核对），缺 split 的侧载在 Android 10 及以上设备会失败。

大资源走自建下载或 PAD：资源清单含版本、长度、哈希、签名与最低应用版本，先验证再原子发布；PAD 的 asset pack 不允许包含可执行代码，fast-follow/on-demand 以归档展开、路径可能跨会话移动、内容视为只读（补丁依赖完整性）。Google Play 的 Device and Network Abuse 政策禁止从 Play 之外下载可执行代码（WebView 或解释器中运行、仅间接访问 API 的 JS 除外），所以动态 DEX/JAR/`.so` 下载不是合规的体积方案——减少首次下载的正确路径是按需模块、按 ABI 拆分或删除代码。
