# Handler 消息机制

> 应用线程间投递消息与任务的机制：消息队列、Looper、子线程 Looper 和生命周期边界。Q 序列即结构。

**Q1: Handler、Looper、MessageQueue 和 Message 如何配合把工作交给目标线程？**

每个 Looper 持有一个 MessageQueue，并在所属线程中循环取出到期消息；Handler 绑定某个 Looper，发送 Message 或 Runnable 时把工作入队，队列所属线程取出后再调用对应处理逻辑。Handler 本身不创建线程，也不执行线程切换；执行线程由它绑定的 Looper 决定。

主线程的 Looper 由应用框架初始化并持续运行。延迟消息按计划时间等待，消息队列为空时 Looper 可阻塞等待新工作，不会因“循环”而持续占满 CPU。

**Q2: 为什么普通子线程里直接创建 Handler 会失败，怎样正确准备工作线程消息队列？**

普通子线程默认没有 Looper，因此直接构造绑定当前线程的 Handler 会因没有可用 Looper 而失败。需要在线程内按顺序准备 Looper、创建 Handler、进入消息循环；如果需要可复用的带 Looper 工作线程，可使用 `HandlerThread`，在线程启动后取得其 Looper 再创建 Handler。

Looper 使用线程局部存储，使每个线程最多绑定自己的 Looper；一个线程不能重复准备多个 Looper。调用 `quit()` 或 `quitSafely()` 结束队列后，该 Looper 不应再被当作仍可接收任务的工作线程。

**Q3: 非静态内部类 Handler 为什么可能让已销毁的 Activity 继续存活？**

匿名或非静态内部 Handler 隐式持有外部 Activity；如果主线程消息队列中仍有延迟 Message，它又持有 Handler，强引用链就可能从长寿命的主线程 Looper 延伸到 Activity，使 Activity 无法回收。真正的条件是队列中的待处理消息延长了引用链，而不是“匿名内部类必然泄漏”。

可在页面销毁时移除明确归属该页面的回调与消息；若工作不应跟随页面生命周期，改用合适的生命周期感知组件或独立状态对象。静态 Handler 加弱引用只能避免一条强引用，仍要清理不再有意义的消息，并对弱引用取值后的空状态作处理。

**Q4: `HandlerThread` 与普通线程池分别适合什么消息处理需求？**

`HandlerThread` 提供一个持续存活、串行处理消息并带有 Looper 的线程，适合依赖线程身份、Looper API 或按队列顺序处理的工作；普通线程池适合相互独立、可并发调度的任务。若多个任务必须严格串行，可使用单线程执行器；线程池不会自动提供 Looper。

所有者结束时应停止 Looper 或关闭执行器，并取消失效任务。不要把 HandlerThread 当作无限期后台任务容器，也不要仅因为它有消息队列就把耗时任务塞入其中而不评估队列阻塞。

**Q5: Binder 工作线程里用 `Looper.myLooper()` 创建 Handler 会发生什么？**

如果服务端方法正在 Binder 线程池线程上执行，且该线程没有显式准备 Looper，那么 `Looper.myLooper()` 返回 null；将它传给要求非空 Looper 的 Handler 构造函数会抛出异常。Binder 线程不是应用主线程，也不保证自带消息队列。

若回调确实需要投递到主线程，应显式绑定 `Looper.getMainLooper()`；若需要串行工作线程，则由服务主动创建并管理带 Looper 的线程。异常若未在远端服务端处理，跨进程调用方通常会观察到远端异常，而不是假定服务方法会继续正常返回。
