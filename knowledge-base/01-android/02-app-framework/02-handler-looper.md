# Handler 消息机制与 MessageQueue 实现

> 学习资料（文章模式沉淀）。边界：本文回答"Handler/Looper/MessageQueue 的契约与线程边界、Android 17 DeliQueue 的实现与迁移"；锁等待与线程稳定性实践见 [应用线程与 IPC 稳定性](08-app-thread-ipc-stability.md)，Binder 语义归 01-architecture/04。经典机制与 DeliQueue 部分分属 Android 通用与 Android 17 语境，逐题标注。Q 序列即结构，供 atlas 同源直读。

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

**Q6: Android 17 的 DeliQueue 优化了消息处理链条的哪一段，为什么它不能让界面绘制或业务回调自动变快？**

DeliQueue 只优化"入队"与"出队"两段的队列管理：生产者不再与 Looper 争用同一个 Java 监视器锁，而是把消息压入无锁栈，再由 Looper 整理进自己独占的最小堆。它不改变 `Handler`、`Looper` 与同步屏障的对外语义，也不会加速 `dispatchMessage()` 之后的布局、绘制、数据库或 Binder 调用。

- 一轮消息处理分三段：入队（Handler 投递）、出队（`MessageQueue.next()` 选择下一条到期消息）、分发（`dispatchMessage()` 执行回调）。DeliQueue 直接优化前两段；第三段耗时仍要沿业务调用栈排查。
- 旧实现用一把 `synchronized` 保护按 `when` 排序的单链表：插入最坏 O(N)，生产者与消费者互斥。锁竞争与调度叠加会形成优先级反转——低优先级线程持锁后被中优先级任务抢占 CPU，高优先级 UI 线程反而被它拖住。多线程高频向主线程 `post()`、队列积压大量延时消息、大范围 `removeCallbacksAndMessages()` 等场景最容易暴露旧结构上限。
- 数字边界：官方博客的 `5,000×` 是"多线程向繁忙队列插入消息"的合成高竞争基准最高值；主线程锁竞争耗时降低 15%、掉帧降低 4%、System UI/Launcher 交互掉帧降低 7.7%、启动到首帧 P95 缩短 9.1% 均描述 Google 内部测试样本，不是对每台设备的性能承诺。

**Q7: 应用要满足什么条件才会启用 DeliQueue？怎样在同一台设备上做出可信的 A/B 对照？**

`targetSdkVersion >= 37` 的普通应用在 Android 17 上默认启用 DeliQueue。实现选择发生在进程启动、主 Looper 创建之前，因此 A/B 必须在切换兼容开关后强制停止并冷启动进程；只切开关不重启进程得不到可信对照。

- 兼容性变更在 `android-17.0.0_r1` 中定义为 `@ChangeId @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.BAKLAVA)` 的 `USE_NEW_MESSAGEQUEUE = 421623328L`；BAKLAVA 对应 API 36，默认启用边界放在 API 37。
- 判定路径：PlatformCompat 判定 → ProcessList 启动应用进程时给 Zygote 加 `--use-deliqueue=<boolean>` → `ActivityThread.main()` 在 `Looper.prepareMainLooper()` 之前解析并设置。选择保存为进程级静态状态，同一进程的 MessageQueue 走同一种实现。
- `CombinedDeliMessageQueue/MessageQueue.java` 同时保留 DeliQueue 与 legacy 两条路径，`next()` 按进程级状态分支。所以"Android 17 源码只有 DeliQueue"与"每个 Android 17 应用都在用 DeliQueue"都不准确；系统进程、测试环境与 feature flag 另有平台内部启用入口，普通应用不应视其为稳定 API。
- A/B 做法：`adb shell am compat enable USE_NEW_MESSAGEQUEUE <包名>`（或 disable），每次切换后 `am force-stop` 再启动。关闭后崩溃消失，优先排查反射与测试工具对内部结构的假设；开启后 monitor contention 消失，说明旧队列锁确是原链路一环；两边 `dispatchMessage()` 都很长则继续修业务代码。兼容开关用于开发验证与故障隔离，不应成为应用长期依赖的产品配置。

**Q8: DeliQueue 用什么结构替代"一把锁加一条有序链表"？排序成本转移到了哪里？**

生产者通过 CAS 把消息压入共享的 `MessageStack`（Treiber 栈），Looper 用 `heapSweep()` 把新消息整理进自己独占的 `mSyncHeap`（同步消息与屏障）与 `mAsyncHeap`（异步消息）两个最小堆；删除消息先对 `Message.flags` 做 CAS 设置 `FLAG_REMOVED` 完成逻辑删除（墓碑节点进入无锁 freelist），再由 Looper 在 `drainFreelist()` 中物理移除。

- 生产者提交路径 O(1)，不随队列长度增长；Looper 随后付出每次 O(log N) 的堆调整成本。排序成本没有消失，而是从"生产者在带锁链表中线性查找插入位置"转移为"单个消费者集中排序"。
- 最小堆按 `when` 排序，同时间用插入序号 `insertSeq` 保持 FIFO；`sendMessageAtFrontOfQueue()` 用递减负序号让队首消息优先。只有 Looper 线程调整堆结构，堆操作不需要再加 Java 锁。
- `removeMessages()` 要匹配所有符合项，查找仍可能遍历既有消息，整体不能宣称 O(1)；读取线程短暂看到墓碑节点时按删除标记忽略。
- 屏障行为不变：屏障生效时同步消息暂缓、到期异步消息可越过。DeliQueue 没有引入业务优先级或新的线程优先级策略，应用能观察到的排序依据仍是 `when`、同时间插入顺序、同步屏障与异步标记。

**Q9: DeliQueue 为什么禁止在并发路径上复用全局 Message 池？**

因为 Treiber 栈的单指针 CAS 无法自行消除 ABA 问题，而"节点对象被回收后再以新消息身份重新出现"正是经典 ABA 的来源。DeliQueue 把处理方式绑定到对象生命周期：进入并发队列的 Message 可能仍被某次删除遍历引用，因此不入池——DeliQueue 路径的 `obtain()` 直接 `new Message()`，`recycleUnchecked()` 只清引用字段、不回收到共享池。

- ABA 场景：线程读到栈顶为对象 A 后暂停；期间 A 被移除、回收到全局池并被复用为新消息，又重新成为栈顶。只比较对象引用的 CAS 看到的栈顶"仍是 A"，会误判为没有变化。
- 墓碑节点保留生命周期，直到 Looper 物理清理，也是为了避免"已删除节点被当作新节点复用"。
- 代价是比旧路径更多的小对象分配。评估 DeliQueue 收益时应同时观察锁竞争、对象分配速率与 GC，不能只看队列操作时长。

**Q10: 官方把 Android 17 的新队列称为"无锁 MessageQueue"，这意味着 MessageQueue 里没有任何锁、CAS 一定比锁快吗？**

都不是。"无锁"指核心消息的并发提交、检查与移除不再依赖旧的单一全局监视器锁，描述的是进展保证——某个参与线程暂停不阻塞其他线程；Android 17 的组合实现仍保留多把锁，CAS 与锁的快慢也取决于竞争形态。

- 仍有锁的部分：`mIdleHandlersLock` 保护 IdleHandler 集合，`mFileDescriptorRecordsLock` 保护文件描述符监听记录，legacy 路径仍是 `synchronized (this)`，原生轮询、唤醒与退出阶段的协调另有原子状态。把"无锁"理解成"任何操作都不加锁"会与源码冲突。
- CAS 不一定更快：低竞争且临界区很短时，锁可能已经足够；高竞争下 CAS 也会反复重试，还要处理消息计数、插入序号与唤醒协调。DeliQueue 的收益来自针对队列结构的整体重构，不能归结为"把每个 synchronized 换成原子变量"。

**Q11: 把依赖 MessageQueue 内部结构的代码迁移到 Android 17 时要注意什么？**

DeliQueue 路径不使用遗留私有字段 `mMessages`，官方迁移文档明确说明该字段会一直是 `null`，任何反射遍历它的监控、测试或调试工具都不再可靠。官方建议的替代是升级测试框架并改用公开或测试专用 API：Espresso 3.7.0 及以上；Robolectric 4.17 及以上，并从 `@LooperMode(LEGACY)` 迁移到 `@LooperMode(PAUSED)`；设备端插桩测试使用 `TestLooperManager`（含 API 36 引入的 `peekWhen()`、`poll()`）。

- 不要改为反射 `MessageStack` 或 `MessageHeap`：它们同样是私有实现，后续版本可以继续变化。
- 需要"队列里还有什么"的断言时，基于 Handler 语义、IdleHandler 或性能轨迹证据重建，而不是寻找新的内部字段。

**Q12: Android 17 的 DeliQueue（无锁 MessageQueue）解决什么问题？Android 13 上的消息队列是什么实现，迁移测试要覆盖哪些风险？**

DeliQueue 针对消息入队争用：旧 MessageQueue 用同一把对象 monitor 保护按 when 排序的单链表，后台线程 post 与 Looper 的 next() 竞争同一把锁，高并发投递时可能出现优先级反转，让主线程卡在队列锁上。Android 17 对 target 37 应用启用 lock-free 新实现：producer 用 VarHandle CAS 把消息压入 Treiber stack，Looper 调用 nextMessage() 时批量 drain 到普通与异步两个有序集合再挑选交付；为兼容保留的 mMessages 字段在新实现下恒为 null，不能反映队列是否为空。按 AAOS13 源码核对，本地树 frameworks/base/core/java/android/os/ 下只有旧版 MessageQueue.java，没有 CombinedMessageQueue 目录也没有 USE_NEW_MESSAGEQUEUE（A17 中为兼容变更 421623328L）——新实现是 Android 17 行为。

迁移风险集中在私有实现依赖：反射读取 mMessages 或遍历旧链表、自制空闲检测、经 JNI 或 hidden API 操作队列内部状态，以及依赖反射写 static final 的测试工具。官方测试基线是 Espresso 3.7.0 及以上、Robolectric 4.17 及以上并迁移到 @LooperMode(PAUSED)。验证时可在 debuggable 构建上用 am compat enable/disable USE_NEW_MESSAGEQUEUE 做同包 A/B，同时观察 monitor_contention、消息排队时间与帧、启动端到端指标；若锁等待下降而排队时间上升，说明瓶颈已转移到 producer 数量或 Looper 端 drain 压力——lock-free 降低的是入队争用，不保证队列不积压。
