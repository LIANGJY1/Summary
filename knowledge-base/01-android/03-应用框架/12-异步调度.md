# 应用异步调度与数据流

> 学习资料（文章模式沉淀）。本文解释协程调度、结构化并发与 Flow 背压的执行语义，以及这些机制对应用交互和后台工作的影响。Q 序列即结构。

**Q1: [learning] “协程切换”包含哪些不同事件，怎样量化调度成本？**

“协程切换”不是一种固定耗时：挂起、dispatch 与线程上下文切换发生在不同层，先拆开才有可比较的测量。三种事件分别是：

1. **挂起和恢复：**continuation 的执行状态变化，不一定更换 Dispatcher。
2. **dispatch：**Dispatcher 决定直接执行，或把 Runnable 放入目标队列；continuation 在队列中等待时，对应 OS 线程未必是 Runnable。
3. **内核上下文切换：**CPU 从一条线程切到另一条线程，可用 `sched_switch` 观察。Perfetto 的 Runnable 指线程已就绪却未获得 CPU，不等于协程还在 Dispatcher 队列中。

量化一次业务操作时，用 release/profileable 构建并固定温度和电源状态，分别记录四个量：

1. 操作 wall time 的 P50/P95/P99。
2. 线程实际 Running 时间。
3. 线程处于 Runnable 的等待时间。
4. 操作被切成的 Running 片段数。

对照组保留相同业务工作，只移除待验证的 Dispatcher 边界，并分别测空操作、短任务和真实任务。“协程几十纳秒、线程几微秒”没有指定硬件、ART、协程版本和队列状态，不能当通用结论。若一次往返恰好耗时 0.20 ms，串行 10 次的这轮样本约占 2 ms；单次操作的 P95 不能乘 10 推出整条链的 P95，须直接测量链路分布。

**Q2: [learning] 四种标准 Dispatcher 怎样选，IO.limitedParallelism(n) 限制什么？**

先按工作是否必须触及 UI、是否占用 CPU、是否阻塞线程选择 Dispatcher：

1. **Main：**主 Looper 的执行上下文，适合 UI 状态读取与很短的主线程工作。suspend 函数仍可能阻塞；Android 17 的 MessageQueue 改动不消除长消息，AAOS13 仍为经典单 monitor 实现。
2. **Default：**面向 CPU 任务，共享 worker 与 work stealing；阻塞 I/O 会占住计算并行度。
3. **IO：**面向阻塞 I/O 的弹性视图，默认并行上限为 64 与处理器数的较大值（kotlinx.coroutines 文档契约，库可独立升级），与 Default 共享底层线程。
4. **Unconfined：**在调用栈内启动，恢复线程由挂起函数决定，只适合框架或测试等特殊场景。

`IO.limitedParallelism(n)` 限制该视图同时执行的任务数，不固定执行 TID，也不限制进程其他 IO 视图的并发。并行度为 1 时任务顺序执行且有 happens-before 保证，但可落到不同 worker；两个分别限制为 100 和 60 的视图峰值仍可能叠加。依赖 ThreadLocal、固定 TID、JNI 线程附着或 Looper 的组件应使用有 owner 的专用线程，并在 owner 结束时关闭其 Dispatcher。

main-safe 的 suspend API 由实现保证：Repository 内分别用 `withContext(IO)` 包裹阻塞读取、用 `withContext(Default)` 包裹 CPU 解码，并注入 Dispatcher 便于测试。取消 `withContext(IO)` 不保证中断已进入的阻塞调用；支持中断的调用可评估 `runInterruptible`。

**Q3: [learning] 结构化并发怎样传播失败，生命周期 scope 各保护什么？**

普通父子 Job 会等待子任务完成，子任务非取消异常会取消父任务并进一步影响兄弟任务；SupervisorJob 只隔离直接子任务的失败。先确认父子边界，再决定异常处理：

1. **普通 Job/coroutineScope：**父取消会取消子任务；父进入 completing 后等待全部子任务。普通子任务失败会取消父任务和兄弟任务。
2. **SupervisorJob/supervisorScope：**隔离直接子任务失败。Android 的 viewModelScope 与 lifecycleScope 使用 SupervisorJob 风格的根作用域，但其中再进入普通 coroutineScope 后，内部仍按普通规则传播。
3. **生命周期收集：**lifecycleScope 到 DESTROYED 才取消，STOPPED 页面若直接收集热流仍可继续工作。`repeatOnLifecycle(STARTED)` 在 STOPPED 取消内部任务，回 STARTED 重建；两个长期 collect 需分别放在子 launch。Fragment 更新 View 时用 viewLifecycleOwner。

取消是协作式的，长 CPU 循环需主动 `ensureActive()` 或 `yield()`。结构化并发也不限制任务数，仍需用 limitedParallelism、Semaphore 或 Channel 设置上限。GlobalScope、未关闭的手工 scope、给 `withContext` 传新 Job，以及用 `suspendCoroutine` 包装却不注销回调，都会破坏预期生命周期；可取消回调用 `suspendCancellableCoroutine`。

**Q4: [learning] Flow 的 buffer、conflate、collectLatest 与 flowOn 怎样改变慢消费者路径？**

默认冷 Flow 的上游、操作符和 collector 在同一协程顺序执行；collector 没处理完时上游 emit 挂起，因而不丢值但吞吐受最慢阶段限制。引入并发边界后按数据语义选择：

1. **buffer(n)：**上游在独立协程运行，队列满后挂起，适合必须全保留的事件。省略容量时采用 `Channel.BUFFERED`，其实际容量受库配置影响，不能假定为 n。
2. **conflate()：**保留较新值、可跳过中间态，适合 UI 快照；等价于容量 0 且 DROP_OLDEST 的缓冲。StateFlow 已按 `Any.equals` 合并相同值，再 conflate 没有效果。
3. **collectLatest：**新值到来时取消前一个 action，适合搜索或预览；旧 action 必须协作取消。
4. **flowOn(dispatcher)：**只改变其上游操作符的上下文，不改变下游 collector。跨 Dispatcher 会引入 Channel 和额外协程，因而也改变缓冲及取消边界；数据层已提供 main-safe API 时无需每层叠加 IO。

StateFlow 的更新还会遍历活跃订阅者，成本为 O(N)。热流上游的生命周期由 `stateIn/shareIn` 的 scope 与 SharingStarted 策略决定；`repeatOnLifecycle` 只管理当前 collector，排查后台耗电应检查共享上游的 owner 和停止超时。
