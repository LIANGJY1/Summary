# Android 异步与 Flow 测试

> 本文依据 Android Developers 的协程测试与 Flow 测试文档，解释虚拟时间、测试 Dispatcher、热流收集和重连状态机的验证边界。Q 序列即结构。

**Q1: [learning] runTest、TestScope 与 TestCoroutineScheduler 各负责什么，为什么测试中的 Dispatcher 应共享调度器？**

runTest 提供受控的协程测试作用域并在测试结束前等待其管理的任务，TestScope 持有测试协程上下文，TestCoroutineScheduler 则统一管理虚拟时间和待执行任务。被测对象中的 Dispatcher 应通过构造参数等方式注入，并在同一测试里共享一个 TestCoroutineScheduler，否则测试看到的是多条彼此独立的虚拟时间线。

三者的职责边界是：

1. **runTest：**启动测试协程，跳过由共享调度器控制的 delay，并在结束时检查仍未完成的受管任务。它不会自动控制真实线程池上的工作。
2. **TestScope：**提供测试主体及其子协程的结构化生命周期。测试创建的后台无限任务应放入 backgroundScope，使 runTest 能在结束时取消它们。
3. **TestCoroutineScheduler：**保存虚拟时钟和待运行任务队列。StandardTestDispatcher 与 UnconfinedTestDispatcher 可以共享它，使推进时间和排空队列作用于同一批任务。

若生产代码在内部直接使用 Dispatchers.IO、Dispatchers.Default 或自建线程，虚拟时间无法跳过那些线程上的真实等待，执行顺序也脱离测试调度器。正确做法是注入 Dispatcher 或更高层的执行策略，并让测试替换为绑定同一 scheduler 的 TestDispatcher。

**Q2: [learning] StandardTestDispatcher 与 UnconfinedTestDispatcher 怎样选择，为什么立即执行可能掩盖并发缺陷？**

需要验证排队、挂起、取消和事件先后关系时，优先使用 StandardTestDispatcher。UnconfinedTestDispatcher 会让新协程在当前线程上立即进入首个挂起点，适合减少简单测试的调度步骤，但它的执行顺序不同于常见生产 Dispatcher。

选择依据是：

1. **StandardTestDispatcher：**新启动的协程先进入 scheduler 队列，测试必须通过让出控制权或推进 scheduler 才会运行。它能暴露“调用已经返回，但后台更新尚未发生”的真实异步边界。
2. **UnconfinedTestDispatcher：**新协程会立即开始执行，遇到挂起后再由 scheduler 管理。它适合只关心挂起函数最终结果、且不验证中间状态和调度顺序的测试。
3. **混用时：**两者必须绑定同一个 TestCoroutineScheduler。否则一个 Dispatcher 的 advanceUntilIdle 无法排空另一个 Dispatcher 的任务。

立即执行会把本应存在的排队窗口压缩掉。例如启动收集协程后马上发射 SharedFlow，UnconfinedTestDispatcher 可能已完成订阅，而生产环境中的订阅仍在排队。若测试要证明“不丢首个事件”或“取消后不再处理旧回调”，应使用 StandardTestDispatcher 显式建立和推进每个阶段。

**Q3: [learning] runCurrent、advanceTimeBy 与 advanceUntilIdle 分别推进什么，怎样避免用 sleep 制造偶然通过的测试？**

三种操作观察的对象不同。runCurrent 只执行当前虚拟时刻已经可运行的任务，advanceTimeBy 把虚拟时钟向前移动指定时长，advanceUntilIdle 则持续执行并推进时间，直到共享 scheduler 没有待处理任务。

按测试意图选择：

1. **验证当前队列：**调用 runCurrent，检查已入队但尚未执行的启动、回调或状态更新。
2. **验证超时边界：**先 advanceTimeBy 到边界前检查状态，再推进剩余时间并用 runCurrent 执行该时刻变为可运行的任务。不要把“时间到达”与“该时刻任务已经执行”混成一个断言。
3. **只验证最终稳定态：**调用 advanceUntilIdle。若系统包含持续重试、无限收集或周期任务，它可能无法空闲，此时应只推进需要验证的时间窗口，并把长期收集放入 backgroundScope。

Thread.sleep 和基于真实时钟的轮询会把结果交给机器负载与线程调度，既慢又不稳定。只有被测行为本身依赖真实线程、系统时钟或不可注入的外部组件时，才需要集成测试使用真实等待，并给出明确超时。

**Q4: [learning] 冷 Flow、SharedFlow 与 StateFlow 应怎样测试，哪些断言会误读热流语义？**

冷 Flow 通常直接收集有限结果，SharedFlow 与 StateFlow 则需要先建立长期 collector，再驱动输入。StateFlow 是可合并的状态容器，测试应优先断言当前或最终状态，不应默认观察到每一个短暂的中间值。

不同流的验证方式是：

1. **有限冷流：**使用 first、single、take(n).toList 等终止操作，并通过 scheduler 驱动其中的 delay。对不会自行结束的流直接调用 toList 会让测试永久等待。
2. **SharedFlow：**先在 backgroundScope 启动 collector，再用 runCurrent 确认订阅已建立，随后发射事件。是否重放旧事件由 replay 决定，不能把测试启动顺序当成协议保证。
3. **StateFlow：**可以直接断言 value，或在必须验证状态序列时持续收集。快速连续写入可能在 collector 恢复前被合并，遗漏中间值符合 StateFlow 语义，不代表生产代码丢事件。
4. **stateIn 与 shareIn：**测试必须启动至少一个 collector 才能激活 WhileSubscribed 等共享策略。只读取 StateFlow.value 可能不足以启动其上游。

如果业务要求每个输入都被处理，应使用事件流、Channel 或持久化队列表达该契约，并测试完整事件序列。用 StateFlow 承担不可丢事件，会让测试与实现都建立在错误语义上。

**Q5: [learning] 车载服务重连状态机应怎样测试，才能覆盖死亡回调、旧事件和重试竞争？**

重连测试应验证状态机协议，而不是只等待“最后连上”。把连接源、重试 Dispatcher 和时间源注入测试，使用可编程 fake 主动触发连接成功、断开、Binder 死亡、超时和乱序回调，才能证明旧连接不会污染新会话。

最小场景集包括：

1. **首次连接：**从未连接进入 connecting，只有服务实例建立且必要初始化完成后才进入 ready。拿到 Binder 实例不等于服务已经可用。
2. **连接死亡：**死亡回调清理旧实例、监听器和待处理请求，只安排一次重试。重复的死亡或断开通知不得创建并行重连链。
3. **旧回调晚到：**建立第二次连接后再投递第一次连接的成功或数据回调，状态机应通过 generation、token 或实例身份拒绝旧事件。
4. **取消与生命周期：**owner 停止或销毁后推进虚拟时间，确认没有继续重试、重新注册或更新 UI。再次启动时应按协议建立新会话。
5. **退避策略：**逐段推进虚拟时间，检查每个重试窗口、上限和成功后的计数复位。最终调用 advanceUntilIdle 不能替代这些边界断言。

断言至少覆盖当前状态、活跃连接数量、监听器注册数量、重试任务数量和旧请求的完成方式。只断言最终 ready 会漏掉重复注册、并行连接、短暂错误状态和资源泄漏。
