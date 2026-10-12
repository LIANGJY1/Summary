# Android 应用 CPU 性能优化

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

**Q6: [learning] 缓存进程因 CPU 使用过量被系统终止的机制是怎么工作的？为什么不能把它当成 Android 17 新增或与 JobScheduler 配额绑定？**

机制在 ActivityManagerService 的过量 CPU 巡检：对进程状态达到 Home（`PROCESS_STATE_HOME`）或更低重要级的进程，用检查窗口内的进程累计 CPU 时间除以窗口墙钟时间换算成百分比，与按“沦为不重要时长”分四档放宽的上限比较，超限先上报统计再终止。按 AAOS13 源码核对：检查间隔默认 5 分钟（`power_check_interval`），四档上限默认 25/25/10/2（`power_check_max_cpu_1..4`），终止时记录 `ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE` 与 `SUBREASON_EXCESSIVE_CPU`。多线程累计 CPU 可以超过单核墙钟时间，所以这个百分比不等同于性能面板里的整机 CPU 占比，检查间隔与档位是可配置常量、厂商可修改。

版本边界要分清：`REASON_EXCESSIVE_RESOURCE_USAGE` 自 API 30 公开，终止机制本身早已存在（AAOS13 已具备）。Android 17 新增的只是 `ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE`，把这条既有路径接入事后性能分析，且 `ProfilingResult` 只公开触发类型、文件路径、应用 tag 与错误码，只能与时间邻近的 `ApplicationExitInfo`、`processStateSummary` 标“可能关联”，不能声称精确映射。这条路径与 JobScheduler/WorkManager 配额没有直接调用关系。后台任务治理应做有界并发、可分批执行、取消信号传到所有子任务、按失败类型延长重试间隔并保存检查点，不要围绕默认阈值持续轮询。

**Q7: [learning] “CPU 占用不高但用户等很久”指向锁竞争时，应该按什么顺序优化？为什么不能假设 synchronized 固定“自旋后休眠”？**

低 CPU 高等待通常先查锁。ART 中 `synchronized` 由 monitor 实现，无竞争处理、竞争策略与运行时状态随实现改变，应用不能依赖“先忙等固定次数再进内核休眠”的固定流程。等锁线程一般不持续吃满一个核，但竞争会增加尾延迟、线程唤醒、上下文切换和优先级反转风险。反过来 CAS 在激烈竞争下反复失败消耗 CPU——`synchronized`、`ReentrantLock`、原子变量之间没有脱离负载的固定胜者。

优化按证据顺序推进：先用 Perfetto 或自加 trace 标记找到等待时间长、调用频繁的临界区。缩短持锁范围，锁内不做 I/O、Binder 调用、回调与重计算。优先用线程封闭、不可变快照或消息传递减少共享可变状态。确认单锁竞争后才评估分段锁等更复杂结构。最后用相同负载比较前后的墙钟、CPU 时间、等待分布与内存成本。把一把锁拆成多把可能增加一致性维护难度，把锁换成 CAS 可能把阻塞等待变成忙碌重试，改动要用 trace 与基准确认。

**Q8: [learning] Dispatchers.Default 与 Dispatchers.IO 是什么关系？limitedParallelism() 视图承诺什么、不承诺什么？**

`Dispatchers.Default` 面向 CPU 计算，`Dispatchers.IO` 面向阻塞 I/O。JVM 上两者共享线程资源，切换 dispatcher 不一定创建新线程。`limitedParallelism(n)` 限制的是“该视图同时执行的协程数量”，不承诺对应固定数量的物理线程。`Dispatchers.IO` 的受限视图有弹性扩容语义——每个视图有自己的并行上限、不受 IO 常规上限的统一约束，但仍与它共享线程资源。

工程含义：把 CPU 任务写成 `suspend` 不会减少指令与 CPU 时间，仍要分批、取消过期任务并控制并发。阻塞并发上限要与数据库连接池、服务端限流、文件描述符和内存预算匹配。不要在主线程用 `runBlocking` 阻塞等待后台结果。结构化并发统一管理子任务的生命周期、取消与错误传播，但不补偿不受控的工作量。这也解释了为什么弹性线程池不能直接登记给 ADPF Session——协程会跨 TID 迁移，系统收到的线程集合会与真实负载分离。

**Q9: [learning] 多线程分别更新相邻计数器导致变慢，怎样判断是不是 false sharing，缓解的优先顺序是什么？**

false sharing 指多个 CPU 并发访问同一 cache line 且至少一个在写，线程操作的是不同字段，却因字段同 line 而产生大量一致性协议通信——“共享”的是硬件维护一致性的 cache line，业务数据本身没有被多线程共同修改。定性不能只凭“字段相邻”：锁竞争、atomic 重试、调度与内存带宽不足会产生相似症状。地址级证据（Arm SPE、`perf c2c`）在量产 Android 设备上经常无法全部满足，得不到时应把结论写成“现象与共享 cache line 竞争一致”。

缓解按代价从低到高执行：

1. 减少共享写入：每线程 / 每 CPU 累积后批量归并。
2. 避免无条件写入相同值。
3. 将一起读取、一起更新的字段分组。
4. 将高频写字段与高频只读字段分开。
5. 只有热点已证实时，再增加对齐或 padding。NDK 可以用 `alignas(64)` 控制布局并用 `sizeof`/`offsetof` 验证构建结果，但 padding 修复不了 data race，目标硬件一致性粒度更大时还要重新验证。Java/Kotlin 对象布局属于 ART 实现细节，添加若干 `long` 字段或 `@Contended` 都没有公共 SDK 契约，应用层更稳的方案是减少共享可变对象、分片计数、批量提交并用基准测试验证。

**Q10: [learning] C/C++ 数据布局中 AoS（结构体数组）与 SoA（分字段数组）怎么选？**

选择由访问模式决定：只处理少数字段时（如只更新位置）SoA 能避免把颜色、速度等无关数据一并载入 cache。每次处理一个元素的全部字段时 AoS 可能更紧凑。折中方案是 AoSoA，按 SIMD 宽度或 tile 分组，在向量化与单元素访问之间平衡。

量化时注意比例计算：AoS 元素 40 字节、循环只读 `x/y/z` 共 12 字节时，有效数据约占对象流量的 30%（12/40），不是固定套用 cache line 得出的比例，且 cache line 可能跨越两个对象，边界与数组起始地址有关。SoA 的代价是多个数组的构造成本与内存占用。判断依据用测量而不是经验排名：目标循环端到端耗时、bytes processed/item、L1D refill/LLC miss/TLB miss、向量化报告与内存占用，并对照自己的结构运行 `sizeof`/`offsetof` 与布局 dump，不要用想象中的框架类布局论证方案。

**Q11: [learning] 把热循环里的 List<Float> 换成 FloatArray，收益来自哪里，什么时候值得做？**

收益通常同时来自三个方向：减少装箱、减少分配和改善空间局部性，报告改动时必须说明包含哪些因素，不能把全部收益归给 cache。机制：`List<Float>` 每个元素访问都要经过对象引用和装箱对象（pointer chasing），数据密度低于 `FloatArray`。`IntArray` 连续保存 primitive 值，而 `Array<MyObject>` 连续保存的只是引用，对象本体仍分散在堆中，Java 多维数组是“数组的数组”，每一行是独立对象。

边界：这只在性能分析确认是热点的计算环节（图像、音频、统计、几何运算）值得做。不在热点的业务代码可读性与正确性更重要，常见做法是保留清晰的业务对象，只在热点环节把数据转换为批量 buffer。`ByteArray`/`FloatArray` 适合紧凑的批量处理，`Array<Int>`/`List<Int>` 涉及装箱对象。

**Q12: [learning] 线程迁移到另一个 CPU 后会发生什么，cpu-migrations 上升能说明什么？**

迁移后新核心的私有 cache 可能没有该线程最近使用的数据，需要从共享层级或同一硬件一致性域内的其他 cache 获取。原核心的全部 L1/L2 不会因迁移被软件统一失效，硬件一致性协议仍负责维护共享数据的可见性。所以“迁移清空原核心 cache”是错误模型，正确理解是“新核心可能缺数据、代价取决于多个条件”。

`cpu-migrations` 上升只是一条线索，判断迁移是否破坏局部性还要同时比较 CPU time、周期数、cache refill（从低层级重新填入 cache line 的次数）、实际运行的核心和端到端延迟。迁移成本取决于：工作集是否仍在共享 cache、数据能否从同一 cluster 的其他 cache 获取、两个核心是否跨 cluster、迁移间隔与工作集大小、迁移前后核心的微架构与频率、同期内存带宽与其他任务。

**Q13: [learning] 怎么用 Simpleperf 判断一段代码是不是 memory-bound（主要受内存访问限制）？**

先确认设备支持哪些 PMU 事件，再做组计数，最后才做热点采样：`simpleperf list`/`list raw`/`stat --print-hw-counter` 列出内核封装事件、raw 事件与硬件 counter 数量。`stat` 时把 `cpu-cycles,instructions` 与 `cache-references,cache-misses` 分组（同组事件尽量同时调度，可计算 IPC 与 miss ratio，发生 multiplexing 时输出中的 enabled/running 时间和警告必须保留）。确认 cache 事件与慢样本相关后，再用 `record -e cache-misses:u` 配合 `report --sort dso,symbol` 定位符号。

低 IPC 不能单独证明 memory-bound：branch miss、指令 cache/TLB 压力、长依赖链、锁等待附近的短运行片段、前端/后端 stall、不同核心宽度与频率、PMU multiplexing 都会拉低 IPC。判断 memory-bound 至少要观察 cache/TLB refill、backend stall、内存带宽或访问延迟采样中的一部分，并通过改变数据布局或工作集做可控实验，确认延迟或吞吐随之改善。边界：不存在“cache miss 超过 10% 就该优化”的通用阈值。不同 cluster 可能使用不同 PMU，线程迁移会影响结果解释。普通 cache-miss 采样只能提示热点指令附近，不能单独证实 false sharing。
