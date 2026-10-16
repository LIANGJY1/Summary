# 应用 CPU 与并发

**Q1: [learning] 应用层做 CPU 优化时，为什么“CPU 高”不能直接指向改法？墙钟时间与线程 CPU 时间、Runnable 与 Blocked 各回答什么问题？**

“CPU 高”只是现象，动手前先回答三个问题：哪段用户路径受影响（启动、滚动、后台同步还是持续计算）、损失在哪个维度（延迟、CPU 时间、能耗、温升还是后台额度）、证据能否归因到具体任务与调用栈。线程池参数只是其中一个控制点。指标语义各不相同：墙钟时间回答“用户等了多久”，包含运行、排队、锁等待、I/O 与抢占。线程 CPU 时间回答“代码在 CPU 上跑了多久”，不含大多数睡眠与阻塞。Runnable 是线程已具备运行条件但没排到 CPU 的时间，可能来自并发过量、优先级或系统竞争。Blocked/Sleeping 是在等锁、I/O 或条件，此时 CPU 低也可能响应很差。

定位时用两者互查：“墙钟高但 CPU 低”优先查排队、锁、I/O 与调度等待。“CPU 时间高”再查调用栈与任务来源。同样的 CPU 时间在不同频率和核上能耗不同，温升要单独观察。优化通常落在四件事：少做工作、减少同一时刻的并行、减少唤醒与切换、把非紧急工作交给系统调度时机。

**Q2: [learning] 应用进程怎么用公开 API 采样自己的 CPU？“等效占用核数”怎么算？/proc 解析有哪些陷阱？**

Kotlin/Java 层用 `Process.getElapsedCpuTime()`（进程启动以来消耗的 CPU 毫秒）与 `SystemClock.elapsedRealtime()`（含深度睡眠的单调墙钟）做两次采样，CPU 增量除以墙钟增量得到“等效占用核数”：1.0 表示窗口内约用一个核，多线程并行可大于 1.0。再除以逻辑处理器数只能作为容量占比的粗略提示，它不考虑大小核性能差异、频率与调度限制。窗口太短受毫秒精度影响，太长掩盖尖峰，长度由要观察的用户路径决定。告警线来自场景基线与设备分层，不要内置“高 CPU 阈值”。NDK 可用 `times()`：`tms_utime + tms_stime` 是线程组累计 tick，返回值是 elapsed tick、绝对值无业务含义，只比较窗口差值，每秒 tick 数用 `sysconf(_SC_CLK_TCK)` 取，不要由内核 `CONFIG_HZ` 推算。

解析陷阱有三类：`/proc/self/stat` 的进程名 `comm` 在括号内且可能含空格，不能整行按空格切分取固定下标，要先定位右括号再解析（`utime`/`stime` 是其后的第 14、15 字段）。`/proc/stat` 首行的 `guest`/`guest_nice` 已包含在 `user`/`nice` 中，十个字段全加会重复计算虚拟 CPU 时间，通常只累计 `user` 到 `steal` 的前八项。`iowait` 很难可靠计算甚至可能下降，不能当“整机空闲、现在预加载安全”的信号。`/proc/stat` 是整机聚合数据，idle 高也不知道用户是否即将触摸、设备是否处于热约束。这些读取还可能因设备策略或 SELinux 失败，采样必须允许返回“无数据”。同理 `MessageQueue.IdleHandler` 只表示当前 Looper 队列暂时没有到期消息，不代表整机空闲，预加载与重计算应交给受控执行器并能在状态变化时取消，需要延后或带约束的后台工作用 WorkManager/JobScheduler 表达。

**Q3: [learning] ThreadPoolExecutor.execute() 的三段决策顺序是什么？为什么"workers = availableProcessors()"这类公式不能当工程规则？**

`execute()` 的决策顺序决定了队列和线程数的关系：

1. 当前 worker 少于 `corePoolSize` 时，先尝试创建核心 worker。
2. 否则把任务放入 `workQueue`，入队后复查线程池状态。
3. 队列拒绝入队时，再尝试创建非核心 worker；仍失败才执行拒绝策略。由此直接推出队列行为：用大容量队列时任务长期停在第 2 步，`maximumPoolSize` 几乎不参与调度。用 `SynchronousQueue` 时没有存储槽位，提交方更容易触发创建非核心线程——两种队列是“排队还是增加并发”的取舍，没有固定优劣。默认配置下 `keepAliveTime` 只回收超出核心数的 worker，`allowCoreThreadTimeOut(true)` 才让核心线程可超时退出。

线程数没有跨应用公式：`Runtime.availableProcessors()` 只是 JVM 当前可用的逻辑处理器数，不是性能核数量，任务内部还可能调用并行库，设备受前后台、温度与功耗策略影响。做法是从任务模型出发：纯计算任务先以较小的受控并行度做基线，观察吞吐、P95/P99、Runnable 等待与温度。阻塞任务的并发上限取决于下游容量、连接池、文件描述符与超时，不能用一个大固定值代替分析。混合任务拆开计算与阻塞阶段分别受限。队列容量的单位是“任务个数”，要按峰值到达率、可接受排队延迟、每个排队任务持有的内存与任务过期价值共同约束。调参时一次只改一个主要变量，并按 `image_decode` 这类低基数标识记录提交/开始/结束时间、排队与执行时长、拒绝次数和分位数，不要把 URL 或用户 ID 放进指标维度。

**Q4: [learning] 有界计算线程池为什么常配 AbortPolicy？CallerRunsPolicy 在什么条件下危险？线程工厂里 Process.setThreadPriority() 为什么必须写在新线程体内？**

固定大小的有界池让并发量和积压量都可控，`AbortPolicy` 用 `RejectedExecutionException` 明确报告已满，提交点据此合并同类请求、取消过期工作、返回降级结果或转交可跨进程重启的持久化调度器。`CallerRunsPolicy` 会在提交线程里直接 `run()`：如果提交方是主线程、Binder 线程或持锁的回调线程，计算或阻塞工作就转移到那里，只有能证明提交线程允许执行该任务时才能用它做反压。

`setThreadPriority()` 默认修改调用线程，所以要在 `ThreadFactory` 里于新线程启动后调用，否则改的是提交者线程。它表达的是 nice 相关的相对优先级，能力边界要写清：不指定大小核、不把线程移入 cpuset、不设置 uclamp、也绕不开后台与温控策略。提高优先级不减少工作量，设置不当还会挤压 UI、RenderThread、Binder 等关键工作。

**Q5: [learning] scheduleAtFixedRate 与 scheduleWithFixedDelay 语义差在哪？任务抛出未处理异常后周期任务会怎样？Android 16 起补跑行为有什么变化？**

`scheduleAtFixedRate()` 按预定起点维持固定节拍。`scheduleWithFixedDelay()` 从上一次执行结束后再等指定间隔。周期任务一旦抛出未处理异常，后续周期默认不再执行——必须捕获或用包装任务保证异常不外抛。取消后应启用 remove-on-cancel 或明确清理队列，避免积压任务继续占用内存。任务因进程冻结、CPU 挂起或单次执行过长错过周期时，旧实现可能连续补跑：面向 Android 16（API 36）及以上的应用默认启用 `STPE_SKIP_MULTIPLE_MISSED_PERIODIC_TASKS`，回到可执行状态后最多立即补跑一次。AAOS13 的 libcore 实现没有这套时间校正逻辑，仍可能连续补跑，跨版本要分别验证。

平台变化只管 `ScheduledThreadPoolExecutor` 自己的追赶，业务手写补发循环与三方 SDK 定时器要按语义分类：前台且要求相位用 fixed-rate 并在生命周期结束时取消。只要求“每次完成后间隔一段时间”用 fixed-delay。可延期、跨进程重启、带约束交给 WorkManager/JobScheduler。用户可感知的精确时刻用合适的 AlarmManager 接口。升级 targetSdk 时还要检查广告、埋点、APM、IM 等 SDK 内部定时器，不能只测应用自有线程池。采集“冻结 → 恢复 → 首帧完成”区间，确认后台任务没有与主线程、RenderThread 和网络重连同时争用 CPU。

**Q6: [learning] “CPU 占用不高但用户等很久”指向锁竞争时，应该按什么顺序优化？为什么不能假设 synchronized 固定“自旋后休眠”？**

低 CPU 高等待通常先查锁。ART 中 `synchronized` 由 monitor 实现，无竞争处理、竞争策略与运行时状态随实现改变，应用不能依赖“先忙等固定次数再进内核休眠”的固定流程。等锁线程一般不持续吃满一个核，但竞争会增加尾延迟、线程唤醒、上下文切换和优先级反转风险。反过来 CAS 在激烈竞争下反复失败消耗 CPU——`synchronized`、`ReentrantLock`、原子变量之间没有脱离负载的固定胜者。

优化按证据顺序推进：先用 Perfetto 或自加 trace 标记找到等待时间长、调用频繁的临界区。缩短持锁范围，锁内不做 I/O、Binder 调用、回调与重计算。优先用线程封闭、不可变快照或消息传递减少共享可变状态。确认单锁竞争后才评估分段锁等更复杂结构。最后用相同负载比较前后的墙钟、CPU 时间、等待分布与内存成本。把一把锁拆成多把可能增加一致性维护难度，把锁换成 CAS 可能把阻塞等待变成忙碌重试，改动要用 trace 与基准确认。

**Q7: [learning] Dispatchers.Default 与 Dispatchers.IO 是什么关系？limitedParallelism() 视图承诺什么、不承诺什么？**

`Dispatchers.Default` 面向 CPU 计算，`Dispatchers.IO` 面向阻塞 I/O。JVM 上两者共享线程资源，切换 dispatcher 不一定创建新线程。`limitedParallelism(n)` 限制的是“该视图同时执行的协程数量”，不承诺对应固定数量的物理线程。`Dispatchers.IO` 的受限视图有弹性扩容语义——每个视图有自己的并行上限、不受 IO 常规上限的统一约束，但仍与它共享线程资源。

工程含义：把 CPU 任务写成 `suspend` 不会减少指令与 CPU 时间，仍要分批、取消过期任务并控制并发。阻塞并发上限要与数据库连接池、服务端限流、文件描述符和内存预算匹配。不要在主线程用 `runBlocking` 阻塞等待后台结果。结构化并发统一管理子任务的生命周期、取消与错误传播，但不补偿不受控的工作量。这也解释了为什么弹性线程池不能直接登记给 ADPF Session——协程会跨 TID 迁移，系统收到的线程集合会与真实负载分离。

**Q8: [learning] 多线程分别更新相邻计数器导致变慢，怎样判断是不是 false sharing，缓解的优先顺序是什么？**

false sharing 指多个 CPU 并发访问同一 cache line 且至少一个在写，线程操作的是不同字段，却因字段同 line 而产生大量一致性协议通信——“共享”的是硬件维护一致性的 cache line，业务数据本身没有被多线程共同修改。定性不能只凭“字段相邻”：锁竞争、atomic 重试、调度与内存带宽不足会产生相似症状。地址级证据（Arm SPE、`perf c2c`）在量产 Android 设备上经常无法全部满足，得不到时应把结论写成“现象与共享 cache line 竞争一致”。

缓解按代价从低到高执行：

1. 减少共享写入：每线程 / 每 CPU 累积后批量归并。
2. 避免无条件写入相同值。
3. 将一起读取、一起更新的字段分组。
4. 将高频写字段与高频只读字段分开。
5. 只有热点已证实时，再增加对齐或 padding。NDK 可以用 `alignas(64)` 控制布局并用 `sizeof`/`offsetof` 验证构建结果，但 padding 修复不了 data race，目标硬件一致性粒度更大时还要重新验证。Java/Kotlin 对象布局属于 ART 实现细节，添加若干 `long` 字段或 `@Contended` 都没有公共 SDK 契约，应用层更稳的方案是减少共享可变对象、分片计数、批量提交并用基准测试验证。

**Q9: [learning] C/C++ 数据布局中 AoS（结构体数组）与 SoA（分字段数组）怎么选？**

选择由访问模式决定：只处理少数字段时（如只更新位置）SoA 能避免把颜色、速度等无关数据一并载入 cache。每次处理一个元素的全部字段时 AoS 可能更紧凑。折中方案是 AoSoA，按 SIMD 宽度或 tile 分组，在向量化与单元素访问之间平衡。

量化时注意比例计算：AoS 元素 40 字节、循环只读 `x/y/z` 共 12 字节时，有效数据约占对象流量的 30%（12/40），不是固定套用 cache line 得出的比例，且 cache line 可能跨越两个对象，边界与数组起始地址有关。SoA 的代价是多个数组的构造成本与内存占用。判断依据用测量而不是经验排名：目标循环端到端耗时、bytes processed/item、L1D refill/LLC miss/TLB miss、向量化报告与内存占用，并对照自己的结构运行 `sizeof`/`offsetof` 与布局 dump，不要用想象中的框架类布局论证方案。

**Q10: [learning] 把热循环里的 List<Float> 换成 FloatArray，收益来自哪里，什么时候值得做？**

收益通常同时来自三个方向：减少装箱、减少分配和改善空间局部性，报告改动时必须说明包含哪些因素，不能把全部收益归给 cache。机制：`List<Float>` 每个元素访问都要经过对象引用和装箱对象（pointer chasing），数据密度低于 `FloatArray`。`IntArray` 连续保存 primitive 值，而 `Array<MyObject>` 连续保存的只是引用，对象本体仍分散在堆中，Java 多维数组是“数组的数组”，每一行是独立对象。

边界：这只在性能分析确认是热点的计算环节（图像、音频、统计、几何运算）值得做。不在热点的业务代码可读性与正确性更重要，常见做法是保留清晰的业务对象，只在热点环节把数据转换为批量 buffer。`ByteArray`/`FloatArray` 适合紧凑的批量处理，`Array<Int>`/`List<Int>` 涉及装箱对象。

**Q11: [learning] 用 Jetpack DevicePerformance 读 MPC 有什么兼容性坑？业务分级为什么不能用 mpc >= 34 这类比较？**

兼容风险有两项，受 Jetpack 版本与读取时序影响：

1. **首次读取时序：**`PlayServicesDevicePerformance` 会读取 DataStore 中此前保存的 Play services 结果，并与平台值取较大者；`mediaPerformanceClass` 为 lazy 值。如果首次访问发生在异步更新完成前，本次进程可能继续使用旧值。官方建议在 `Application.onCreate()` 创建一次对象，新值通常供后续进程使用。
2. **低等级识别：**本文核对的 `androidx-main` 实现中，`DefaultDevicePerformance.isPerformanceClassValid()` 仍要求值至少为 `Build.VERSION_CODES.R`（30），可能把 CDD 17 的 1、10、20 视为无效并返回 0；若 Play services 已把低等级写入 DataStore，取较大值的路径又可能保留该等级。该实现随库版本变化，不能直接套到项目依赖。

接入前对项目锁定的 Jetpack 版本做测试，并同时记录平台原始值 `raw_build_mpc` 与库最终值 `resolved_mpc`。本文未将 androidx-main 的行为外推为所有已发布版本。

业务分级不能只用 `>=`，因为等级集合和条款不构成单一、稳定的性能排序；功能要求应按 Android 17 CDD 对应条款显式识别：

1. **HDR 显示：**候选等级为 34、35、37。
2. **后置 RAW：**候选等级为 31、33、34、35、37；31 是首次要求 RAW 的等级。
3. **JPEG_R：**候选等级为 35、37。

未识别值进入 Unknown，不自动套用相邻等级；每项仍要保留运行时能力查询作为必要条件。产品侧的 MediaTier 分组不能反向解释为 CDD 正式名称。

跨层边界：设备性能等级与系统能力边界见 [设备性能等级与系统能力边界](../../02-系统/03-调度电源与温控.md)。

**Q12: [learning] MPC 与运行时能力查询怎样组合决策？值为 0 或低等级的设备怎么设计体验？**

决策按以下五步执行：

1. 用 `SDK_INT` 判断所需 API 是否存在。
2. 用明确识别的 MPC 选择保守、标准或高规格候选。
3. 用运行时能力查询剔除不支持的项：多路视频检查 `getMaxSupportedInstances()` 与性能点，HDR 检查编码器与屏幕能力，相机检查 `CameraCharacteristics` 与输出流组合，低延迟音频检查 AAudio 能力与实测往返延迟。
4. 根据实测耗时、温度、内存压力与失败率调整默认值。
5. 用可远程关闭的功能开关保留快速回退。

这个顺序避免两类配置错误：高 MPC 设备因单项能力不足而失败，以及 MPC 为 0 的设备被无条件关闭原本可用的功能。

低等级不是失败状态，而是规范定义的下限信息：MPC 1 面向轻量媒体与低内存低 I/O 环境，应严格限制缓冲、位图与并发。MPC 10 可把 720p30 与较低并发作为初始候选再查编解码器与相机能力。MPC 20 有更高内存与 1080p 屏幕下限，可采用中等并发。值为 0 或未识别时的保护策略：视频默认单路 720p/1080p30 起步，相机预览与拍照分别选尺寸优先首帧与成功率，滤镜限制中间缓冲并提供关闭入口，转码上传限制并发，缓存预算结合 isLowRamDevice() 与进程内存回收通知，再按线上耗时与失败率逐步开放——避免维护庞大的机型白名单。发布决策按 MPC 与运行时能力交叉观察，不直接上报机型等高基数字段。

**Q13: [learning] getThermalHeadroom() 返回的数值怎么解读？应用侧降载策略要满足什么条件？**

它是“距离 SEVERE 热限制的相对余量”，不是剩余 CPU 百分比：1.0 表示当前或预测达到 `THERMAL_STATUS_SEVERE`，数值可以大于 1.0 但没有固定 status 映射，0.0 不对应固定温度或 NONE，设备不支持或调用过密时可能返回 NaN。参数是 0–60 秒的预测范围，主要跟踪 SKIN 一类慢变化传感器，采样高于约每秒一次没有收益。AAOS13 已含该 API（`PowerManager.getThermalHeadroom()`，本地源码核对），API 35 的 `getThermalHeadroomThresholds()` 与 API 36 的 CPU/GPU headroom 在 Android 13 上不存在。

应用降载策略要满足三点：可逆（温度回落能逐步恢复）、有迟滞（进入与退出用不同阈值，避免画质在临界值附近反复切换）、按瓶颈选择（GPU 受限减像素与特效，CPU 受限减模拟、脚本或编码复杂度）。还应设置最短驻留时间，避免短暂波动反复切换档位；例如丢帧比例达到较高阈值才降级，只有低于更低的恢复阈值且热状态回落后才恢复，并保持一段最短时间。典型分档是 LIGHT 停非关键后台任务、MODERATE 降渲染分辨率与编码档位、SEVERE 及以上降帧率目标并优先保障交互与数据安全——分档动作是产品策略示例，要经设备实测标定。配合 `OnThermalStatusChangedListener` 响应已发生的状态变化，用低频轮询 headroom 提前准备降载。

跨层边界：Thermal Headroom 系统语义见 [Thermal Headroom 系统语义](../../02-系统/03-调度电源与温控.md)。



**Q14: [learning] ADPF 的 HintSession 是反馈回路还是升频开关？Android 13 上能用到哪些 API？**

反馈回路，不是升频开关。应用创建 session 时给出一组线程 TID 与目标工作时长（target work duration），每个周期上报实际时长（actual duration），系统比较两者后尝试调整这组线程的核放置或频率，使后续周期接近目标。公开契约只承诺“尝试调整”，可能维持频率、改 CPU 分配或受热与功耗约束失败。频率没有上升不能证明 session 失效——资源可能已经足够，或瓶颈在 GPU、内存带宽与温控。

按 AAOS13 源码核对，Android 13 的可用面是 `PerformanceHintManager`（API 31 引入）：`createHintSession(tids, targetDurationNanos)`（返回 null 表示设备不支持或 TID 不属于本应用）、`updateTargetWorkDuration()`、`reportActualWorkDuration(long)` 与 `close()`。服务端 `HintManagerService` 位于 `services/core/java/com/android/server/power/hint/`。API 34 的 `setThreads()`、API 35 的 `WorkDuration`（CPU/GPU 分项上报）与 `setPreferPowerEfficiency()`、API 36 的 NDK workload hint 在 Android 13 上不存在——协程漂移导致线程集合失真时，A13 的做法是关闭旧 session 再按当前 TID 重建。

跨层边界：ADPF 性能提示机制见 [ADPF 性能提示机制](../../02-系统/03-调度电源与温控.md)。

**Q15: [learning] target duration 应该填整帧周期吗？事后上报 actual 能修复已经掉的那一帧吗？**

都不。target 只覆盖 session 纳入的线程组：60 Hz 显示周期约 16.67 ms，但这组线程通常只负责其中一段（例如 6 ms 的 CPU 渲染工作），把 16.67 ms 填进去会让系统高估时间余量。帧率、渲染比例或流水线分工变化后应重新测量并调用 `updateTargetWorkDuration()`，不能只用 `1000 / fps` 生成固定值。

actual duration 是事后反馈，影响的是后续周期：上报发生在工作完成之后，对已经超时的那一帧无能为力。可预测的负载突增应提前调整自身策略（降低画质、延后非关键工作）。session 绑定 Linux TID——Java `Thread.getId()`、协程 ID 都不能替代，TID 要在真正执行工作的线程上取得。上报频率按“每帧或每个任务周期一次”，不为小任务反复创建 session。判断接入效果看 actual 相对 target 的 P50/P90/P95 分布、掉帧区间与同机 A/B 对照，而不是瞬时最高频率。

Hint Session 表达周期性工作目标：用属于本进程的长期存活线程组创建 session，给出目标时长（从该线程组负责的一轮工作预算出发，不要机械照搬整帧 16.67 ms），每周期调用 reportActualWorkDuration()，目标变化时 updateTargetWorkDuration()，结束调用 close()。createHintSession() 可能返回 null（设备不支持或线程不属于当前进程），API 34 起线程集合变化可用 setThreads()。它传递的是工作目标，不承诺绑核、固定频率或避免温控降频——资源是否响应要靠设备数据确认。计时用与 uptimeNanos 一致的时钟基准，GPU 完成时间不能混进 CPU 工作时长。评估做只改会话开关的 A/B，观察线程 running/runnable/sleeping、频率、FrameTimeline 与功耗。

thermal headroom 是归一化热压力指标：PowerManager.getThermalHeadroom(forecastSeconds)（API 30 起，参数 0–60 秒）返回值越高表示越接近严重温控阈值，1.0 对应 THERMAL_STATUS_SEVERE、可以大于 1.0，不是摄氏度。设备不支持或调用过快返回 NaN，慢变化传感器约一秒更新一次，更高频轮询没有收益。API 35 的 getThermalHeadroomThresholds() 读取设备为各温控状态定义的阈值映射，API 36 起可能运行时变化并可用 addThermalHeadroomListener() 监听。跨机比较原始 headroom 数字要保留传感器与阈值模型差异。CPU/GPU headroom（SystemHealthManager，API 36 起）是另一套：结果范围 0–100、至少一次同步 Binder 事务可能超过 1 ms，不要在渲染或音频线程等待，最小查询间隔与计算窗口由设备报告。应用纪律：信号只用于渐进调整负载并加滞回与最短保持时间，不写 sysfs、不绑核、不依赖厂商私有性能服务。

**Q16: [learning] Performance Hint、CPU/GPU Headroom 与 Thermal 状态三类信号怎么分工？**

三个信号回答不同问题，不能互换：Performance Hint 表达“这组线程希望在多长时间内完成周期性工作”。CPU/GPU Headroom（API 36 的 `SystemHealthManager.getCpuHeadroom()/getGpuHeadroom()`，0–100，Android 13 无）回答“当前计算资源还有多少可授予容量”。Thermal status/headroom 回答“设备已进入什么热限制、接近严重限制的趋势如何”。设备可能热余量尚可但 GPU 已满，也可能 CPU 有余量却因皮肤温度接近阈值需要降级。

使用纪律：headroom 查询是同步 Binder 调用、官方提示可能超过 1 ms，要放在工作线程并遵守最小调用间隔，适合在场景切换或低频调档时读取。HintSession 的 target/actual 每周期上报。CPU 频率未上升时先在同一时间窗对照工作时长、调度、频率与 thermal 数据，再判断是提示未生效还是资源已足够/另有瓶颈。降级决策用三类信号组合触发，并保持滞回与最短保持时间。

**Q17: [learning] 设备分级为什么要把“稳定能力画像、工作负载策略、会话期压力”分成三层，而不是给整机算一个高/中/低总分？**

因为三类信息的含义与生命周期不同：

1. **稳定能力画像：**low-RAM 标记、应用堆上限、Media Performance Class、ABI、明确硬件特性；在安装、系统升级或配置版本变化时才重算。
2. **工作负载策略：**图片缓存预算、预取条数、视频规格；随应用版本和远端策略版本变化。
3. **会话期压力：**温控状态、省电模式、内存压力、丢帧率；在运行期间随时变化。

压成一个总分，会让某项能力很强、另一项较弱的设备收到错误策略——`isLowRamDevice()` 适合影响缓存和预加载，MPC 只覆盖媒体体验，热节流和省电模式只描述此刻条件。一次发热也不应把设备永久记成“低端机”。

策略名称应描述约束而非档次：`memory_constrained`、`media_enhanced`、`render_reduced` 比 `low/middle/high` 在几个月后仍能解释含义。策略数量宜少但“三档”不是平台规则，一项业务只有两个有效配置时就保留两个。同一台设备完全可以同时得到“内存受限”和“媒体能力较强”两个判断，策略解析器按业务维度分别读取，不要先算总分再分配功能。

**Q18: [learning] 设备能力评估应该用哪些公开 API 信号？它们各自的版本边界和常见误读是什么？**

只用公开、可解释的信号，不读隐藏 `SystemProperties`、私有 DeviceConfig 命名空间或内核节点。核心信号与边界：

1. **内存**：`isLowRamDevice()` 是厂商声明的稳定提示，适合收缩高内存功能。`getMemoryClass()` 是当前应用的 Java 堆上限（MB），不表示整机 RAM，也不含 native、图形与共享内存。`MemoryInfo` 各字段语义不同：`totalMem`（API 16）是内核可访问内存，不含基带、TEE 等保留区。`advertisedMem`（API 34）才接近零售标称。`freeMem`（API 37）只统计未使用 RAM、不含可回收页——Linux 会主动用闲置内存做页缓存，单看 freeMem 极易误判压力（AAOS13 源码核对：该版本只有 totalMem/availMem/threshold/lowMemory，后两个字段是后续版本加入）。
2. **CPU/SoC**：`Runtime.availableProcessors()` 允许在同一进程生命周期内变化，只返回调用时刻可用数，不表达大小核性能与频率，并发策略依赖它时应在非关键路径偶尔重读。`Build.SOC_MANUFACTURER`/`SOC_MODEL`（API 31）适合按机型定位异常，不适合单独映射为分档。
3. **媒体**：`Build.VERSION.MEDIA_PERFORMANCE_CLASS`（API 31）返回 0 表示设备未声明等级，官方定义的等级是 30、31、33、34、35（不存在 32）。它覆盖媒体、相机、编解码等 CDD 范围，没有承诺所有工作负载的 CPU/GPU 吞吐，不能当整机跑分，选中高等级后仍要查询目标编解码器或图形特性。
4. **图形/屏幕**：Vulkan 版本与扩展适合做功能门控（接口存在不等于吞吐足够）。120 Hz 面板每帧预算约 8.3 ms，更高分辨率增加像素量与带宽——屏幕规格同时是能力与负载，不能简单加进“高端分”。

缓存预算应同时受应用堆上限、资源类型和内存回调约束。按整机 `totalMem` 固定取百分比会漏掉进程堆限制与图形内存开销。另外不要在冷启动期间跑微基准探测硬件：它会延长启动、增加发热，并产生受后台负载影响很大的样本。

**Q19: [learning] 远端下发的设备策略为什么必须能离线启动？验证分级收益时要注意哪些陷阱？**

远端配置可以修正规则和控制灰度，但冷启动必须能使用本地配置完成。数据契约按生效顺序检查：

1. **离线默认值：**APK 内置已验证的安全配置，本地保存上一份通过校验的配置；拉取失败继续使用本地版本。
2. **版本与有效期：**配置携带 `schemaVersion`、`policyRevision`、适用应用版本和过期时间；未识别字段按兼容规则忽略，越界值拒绝生效。
3. **降级与切换：**高风险开关有独立 kill switch，同一前台会话维持稳定策略，新配置到下一次会话再切换。
4. **曝光记录：**只有策略实际应用到用户行为的样本才记为实验曝光。

验证收益时分别排除三种误判：

1. **分组偏差：**用符合隐私规则的稳定标识确定性分组，并在能力画像或机型层内随机分配；直接比较高端组与低端组会混入用户和系统差异。
2. **汇总与副作用：**分层检查流量构成，防止辛普森悖论。减少预取可用内存峰值作主指标，同时以滚动卡顿、图片等待、请求数和成功率作护栏，比较 P50–P99 而非只看平均值。
3. **安全越权：**设备等级是可篡改的客户端参数，服务端不能据此授予权限或跳过风控。

**Q20: [learning] 做机型分层时为什么应保存分项能力向量而不是一个综合档位？Media Performance Class 能当作档位标准吗？**

综合分把不同资源结构压成一个数，会隐藏 App 依赖的那一维：总分相近的两台设备可能一台 CPU 强存储弱、另一台相反，图片解码重的 App 与冷启动读小文件多的 App 在两台设备上排序相反。分层应保存相互独立的能力向量——CPU 单核与多核、GPU graphics 与 compute、存储、RAM、热稳定性——各维度从对应的 Benchmark 子项或线上采集取值。四档（入门、中端、高端、旗舰）的边界从团队已冻结版本的设备样本分布中计算（例如按关键维度的 P20、P50、P80 切分），换 Benchmark 大版本或设备覆盖集后要重新计算。

Media Performance Class 不能当档位标准：它用 CDD 规定媒体、相机、内存、显示等能力下限并由 CTS 验证，只表达“能力下限标签”，表达不了 CPU 排名、启动 P95 或列表慢帧率。AAOS13 源码核对 `Build.VERSION.MEDIA_PERFORMANCE_CLASS` 从设备属性读取声明值、未声明返回 0。官方公开定义的等级是 30、31、33、34、35（没有 32），设备升级系统后仍报告原先满足的等级，不要自行创造"MPC 37"，也不要把 Android 版本号当作设备必然报告的等级。

工程连接方式是版本化设备字典：客户端只上报设备与系统标识，分析平台在服务端关联能力字段，事件里保留 `capability_schema` 一类的规则版本让历史数据可还原。分档用于灰度覆盖、性能预算和告警分组，Benchmark 版本变化只触发字典重算，不直接触发线上性能告警。
