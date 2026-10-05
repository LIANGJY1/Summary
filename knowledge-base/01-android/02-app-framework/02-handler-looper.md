# Handler 消息机制与 MessageQueue 实现

> 学习资料（文章模式沉淀）。主线：Handler、Looper、MessageQueue 与 Messenger 的契约和线程边界，以及 Android 17 DeliQueue 的实现和迁移。锁等待与线程稳定性实践见 `08-app-thread-ipc-stability.md`，Binder 语义见 `../01-architecture/03-binder.md`。经典机制与 DeliQueue 分属 Android 通用和 Android 17 语境，逐题标注。Q 序列即结构，供 Atlas 同源直读。

**Q1: Handler、Looper、MessageQueue 和 Message 如何配合把工作交给目标线程？**

每个 Looper 持有一个 MessageQueue，并在所属线程中循环取出到期消息。Handler 绑定某个 Looper，发送 Message 或 Runnable 时把工作入队，队列所属线程取出后再调用对应处理逻辑。Handler 本身不创建线程，也不执行线程切换。执行线程由它绑定的 Looper 决定。

主线程的 Looper 由应用框架初始化并持续运行。延迟消息按计划时间等待，消息队列为空时 Looper 可阻塞等待新工作，不会因“循环”而持续占满 CPU。

**Q2: 为什么普通子线程里直接创建 Handler 会失败，怎样正确准备工作线程消息队列？**

普通子线程默认没有 Looper，因此直接构造绑定当前线程的 Handler 会因没有可用 Looper 而失败。在线程内准备消息队列时，必须先准备 Looper，再创建 Handler，最后进入消息循环：

1. 手动管理时，在线程中调用 `Looper.prepare()`。
2. 在该线程中创建绑定当前 Looper 的 Handler。
3. 调用 `Looper.loop()` 开始处理队列。

需要可复用的带 Looper 工作线程时，可使用 `HandlerThread`。线程启动后取得其 Looper，再创建 Handler。

Looper 使用线程局部存储，一个线程不能重复准备多个 Looper。调用 `quit()` 或 `quitSafely()` 结束队列后，该 Looper 不应再被当作仍可接收任务的工作线程。

**Q3: 非静态内部类 Handler 为什么可能让已销毁的 Activity 继续存活？**

匿名或非静态内部 Handler 隐式持有外部 Activity。如果主线程消息队列中仍有延迟 Message，Message 又持有 Handler，强引用链就可能从长寿命的主线程 Looper 延伸到 Activity，使 Activity 无法回收。真正的条件是队列中的待处理消息延长了引用链，而不是“匿名内部类必然泄漏”。

处理时按工作是否需要跟随页面生命周期选择：

1. 页面销毁时，移除明确归属该页面的回调与消息。
2. 工作不应跟随页面生命周期时，改用合适的生命周期感知组件或独立状态对象。
3. 静态 Handler 加弱引用只能避免一条强引用。仍要清理失效消息，并处理弱引用取值后的空状态。

**Q4: `HandlerThread` 与普通线程池分别适合什么消息处理需求？**

`HandlerThread` 和普通线程池适合不同的执行模型：

1. **HandlerThread：**提供持续存活、串行处理消息并带有 Looper 的线程，适合依赖线程身份、Looper API 或队列顺序的工作。
2. **普通线程池：**适合相互独立、可并发调度的任务。线程池不会自动提供 Looper。
3. **单线程执行器：**多个任务必须严格串行但不需要 Looper 时可使用。

所有者结束时应停止 Looper 或关闭执行器，并取消失效任务。不要把 HandlerThread 当作无限期后台任务容器，也不要仅因为它有消息队列就把耗时任务塞入其中而不评估队列阻塞。

**Q5: Binder 工作线程里用 `Looper.myLooper()` 创建 Handler 会发生什么？**

如果服务端方法正在 Binder 线程池线程上执行，且该线程没有显式准备 Looper，那么 `Looper.myLooper()` 返回 null。将它传给要求非空 Looper 的 Handler 构造函数会抛出异常。Binder 线程不是应用主线程，也不保证自带消息队列。

按目标执行线程选择 Looper：

1. 回调需要投递到主线程时，显式绑定 `Looper.getMainLooper()`。
2. 回调需要串行工作线程时，由服务主动创建并管理带 Looper 的线程。
3. 异常若未在远端服务端处理，跨进程调用方通常会观察到远端异常，不能假定服务方法会继续正常返回。

**Q6: Android 17 的 DeliQueue 优化了消息处理链条的哪一段，为什么它不能让界面绘制或业务回调自动变快？**

DeliQueue 优化的是消息入队和出队时的队列管理。生产者不再与 Looper 争用旧实现中的同一把 Java 监视器锁，而是把消息压入无锁栈，再由 Looper 整理进自己独占的最小堆。它不改变 Handler、Looper 和同步屏障的对外语义，也不会加速 `dispatchMessage()` 之后的布局、绘制、数据库或 Binder 调用。

一轮消息处理分为三个阶段：

1. **入队：**Handler 将消息或 Runnable 投递到队列。
2. **出队：**`MessageQueue.next()` 选择下一条到期消息。
3. **分发：**`dispatchMessage()` 执行回调。DeliQueue 直接优化前两个阶段。第三阶段耗时仍需沿业务调用栈排查。

旧实现用一把 `synchronized` 保护按 `when` 排序的单链表，插入最坏 O(N)，生产者与消费者互斥。锁竞争与调度叠加可能形成优先级反转：低优先级线程持锁后被中优先级任务抢占 CPU，高优先级 UI 线程反而被拖住。多线程高频向主线程 `post()`、大量延时消息积压或大范围调用 `removeCallbacksAndMessages()` 时，更容易暴露旧结构的上限。

官方博客报告的性能数字有明确范围：

1. `5,000×` 是多线程向繁忙队列插入消息的合成高竞争基准最高值。
2. 主线程锁竞争耗时降低 15%、掉帧降低 4%、System UI 和 Launcher 交互掉帧降低 7.7%、启动到首帧 P95 缩短 9.1%，都来自 Google 内部测试样本。
3. 这些数据不能当作每台设备的性能承诺。

**Q7: 应用要满足什么条件才会启用 DeliQueue？怎样在同一台设备上做出可信的 A/B 对照？**

普通应用在 Android 17 上是否默认启用 DeliQueue，取决于 target SDK 和平台兼容性变更。对 target SDK 37 及以上的应用，默认启用。实现选择发生在进程启动、主 Looper 创建之前，因此切换开关后必须强制停止并冷启动进程，才能做有效 A/B。

启用路径和版本边界如下：

1. Android 17 `android-17.0.0_r1` 将兼容性变更定义为 `USE_NEW_MESSAGEQUEUE = 421623328L`，并以 `@EnabledAfter(targetSdkVersion = Build.VERSION_CODES.BAKLAVA)` 标记。BAKLAVA 对应 API 36，因此默认启用边界是 API 37。
2. PlatformCompat 先判定变更状态。ProcessList 启动应用进程时将 `--use-deliqueue=<boolean>` 传给 Zygote。`ActivityThread.main()` 在 `Looper.prepareMainLooper()` 前解析该参数并设置状态。
3. 该选择保存在进程级静态状态，同一进程内的 MessageQueue 使用同一种实现。`CombinedDeliMessageQueue/MessageQueue.java` 保留 DeliQueue 与 legacy 两条路径，由 `next()` 按该状态分支。
4. 因此，Android 17 源码并非只有 DeliQueue，Android 17 上也并非每个应用都必然使用 DeliQueue。系统进程、测试环境和 feature flag 另有平台内部入口，普通应用不应把它们视为稳定 API。
5. 按 AAOS 13 源码核对，本地 `frameworks/base/core/java/android/os/` 只有旧版 `MessageQueue.java`，没有 `CombinedMessageQueue` 目录或 `USE_NEW_MESSAGEQUEUE`。DeliQueue 属于 Android 17 行为。

A/B 验证使用兼容性命令切换变更，并在每次切换后重启应用进程：

1. 启用：`adb shell am compat enable USE_NEW_MESSAGEQUEUE <包名>`。
2. 禁用：`adb shell am compat disable USE_NEW_MESSAGEQUEUE <包名>`。
3. 强制停止并重新启动目标应用，确保新进程在 Looper 创建前读取到变更状态。
4. 若关闭后崩溃消失，优先排查反射和测试工具对内部结构的假设。若开启后 monitor contention 消失，旧队列锁可能是原链路的一环。若两种模式下 `dispatchMessage()` 都很长，则继续修复业务回调。

兼容性 A/B 应在 debuggable 构建上执行，并同时观察 monitor contention、消息排队时间、帧表现和启动端到端指标。若锁等待下降而消息排队时间上升，瓶颈可能已转移到 producer 数量或 Looper 端 drain 压力。lock-free 降低的是入队争用，不保证队列不会积压。

兼容性开关适合开发验证与故障隔离，不应成为应用长期依赖的产品配置。

**Q8: DeliQueue 用什么结构替代"一把锁加一条有序链表"？排序成本转移到了哪里？**

生产者通过 VarHandle CAS 把消息压入共享的 `MessageStack`（Treiber 栈）。Looper 用 `heapSweep()` 将新消息整理到自己独占的两个最小堆：`mSyncHeap` 保存同步消息与屏障，`mAsyncHeap` 保存异步消息。删除消息时，代码先对 `Message.flags` 做 CAS 并设置 `FLAG_REMOVED`，将消息逻辑删除。墓碑节点进入无锁 freelist，再由 Looper 在 `drainFreelist()` 中物理移除。

新结构把工作从多生产者路径转移到 Looper 消费路径：

1. **生产者提交：**压栈路径为 O(1)，不随队列长度线性增长。
2. **Looper 排序：**堆调整每次为 O(log N)。排序成本没有消失，而是从生产者在线性查找插入位置，转移到单个消费者集中整理。
3. **消息顺序：**最小堆按 `when` 排序，同一时间用插入序号 `insertSeq` 保持 FIFO。`sendMessageAtFrontOfQueue()` 使用递减负序号使队首消息优先。
4. **删除成本：**`removeMessages()` 要匹配所有符合项，仍可能遍历既有消息，不能整体宣称为 O(1)。读取线程短暂遇到墓碑节点时会按删除标记忽略。
5. **屏障语义：**屏障生效时同步消息暂缓，到期异步消息可以越过。DeliQueue 不引入业务优先级或新的线程优先级策略。应用可观察的排序仍由 `when`、同时间插入顺序、同步屏障和异步标记决定。

只有 Looper 线程调整堆结构，因此堆操作本身不需要额外 Java 锁。

**Q9: DeliQueue 为什么禁止在并发路径上复用全局 Message 池？**

Treiber 栈的单指针 CAS 无法自行消除 ABA 问题，而节点被回收后再以新消息身份出现正是经典 ABA 的来源。进入并发队列的 Message 可能仍被删除遍历引用，因此 DeliQueue 路径不把它放回共享池。该路径的 `obtain()` 直接 `new Message()`，`recycleUnchecked()` 只清引用字段。

全局复用会带来以下风险和代价：

1. **ABA 风险：**线程读到栈顶对象 A 后暂停。期间 A 被移除、回收到全局池、再被复用为新消息并重新成为栈顶。只比较对象引用的 CAS 会看到栈顶“仍是 A”，误判栈未变化。
2. **墓碑生命周期：**删除标记节点必须保留到 Looper 物理清理，以免已删除节点又被当作新消息复用。
3. **分配代价：**不复用全局池会比旧路径产生更多小对象。评估 DeliQueue 时应同时观察锁竞争、对象分配速率和 GC，不能只看队列操作时长。

**Q10: 官方把 Android 17 的新队列称为"无锁 MessageQueue"，这意味着 MessageQueue 里没有任何锁、CAS 一定比锁快吗？**

都不是。“无锁”指核心消息的并发提交、检查与移除不再依赖旧的单一全局监视器锁。它描述的是进展保证，即某个参与线程暂停时，其他线程仍可继续推进。Android 17 的组合实现仍保留多把锁，CAS 与锁的快慢也取决于竞争形态。

实现中仍有锁和协调状态：

1. `mIdleHandlersLock` 保护 IdleHandler 集合。
2. `mFileDescriptorRecordsLock` 保护文件描述符监听记录。
3. legacy 路径仍使用 `synchronized (this)`。
4. 原生轮询、唤醒和退出阶段使用其他原子状态协调。

CAS 也不保证总是更快。低竞争且临界区很短时，锁可能已经足够。高竞争下 CAS 会反复重试，还要处理消息计数、插入序号与唤醒协调。DeliQueue 的收益来自针对队列结构的整体重构，不能简单归结为把每个 `synchronized` 替换为原子变量。

**Q11: 把依赖 MessageQueue 内部结构的代码迁移到 Android 17 时要注意什么？**

DeliQueue 路径不使用遗留私有字段 `mMessages`。官方迁移说明明确该字段在新路径中会保持 `null`，因此反射遍历它的监控、测试或调试工具不再可靠。迁移重点是移除对私有结构的假设，并把测试改为验证公开行为。

需要检查的依赖包括：

1. 反射读取 `mMessages` 或遍历旧链表的监控和调试工具。
2. 根据 `mMessages` 自制的队列空闲检测。
3. 经 JNI 或 hidden API 直接操作队列内部状态的代码。
4. 依赖反射写入 `static final` 字段的测试工具。
5. 试图改为反射 `MessageStack` 或 `MessageHeap` 的替代实现。这些类型同样是私有结构，后续版本仍可变化。

官方测试工具的迁移基线如下：

1. **Espresso：**升级到 3.7.0 或更高版本。
2. **Robolectric：**升级到 4.17 或更高版本，并从 `@LooperMode(LEGACY)` 迁移到 `@LooperMode(PAUSED)`。
3. **设备端插桩测试：**使用 `TestLooperManager`。API 36 起可用 `peekWhen()` 和 `poll()`。
4. **替代断言：**需要判断队列中是否还有待处理工作时，基于 Handler 语义、IdleHandler 或性能轨迹证据重建，而不是寻找新的内部字段。

**Q12: Android `Messenger` 与 AIDL Binder 适合什么通信需求？**

`Messenger` 把 `Message` 封装为 Binder 消息，并交给服务端关联的 Handler/Looper 处理。AIDL 则生成有类型的方法接口。选择取决于是否需要消息队列语义、接口类型约束和并发控制。

1. **选择 `Messenger`**：消息种类少、无需复杂返回值，且单个 Looper 串行处理足够时使用。它便于复用 Handler 的队列与线程语义。
2. **选择 AIDL**：需要明确的方法和参数契约、同步返回值，或自行管理服务端并发时使用。
3. **共同边界**：跨进程传输仍由 Binder 承载。两种方式都要处理服务死亡、线程切换和调用失败。`Messenger` 的排队不等于消息必定完成。
