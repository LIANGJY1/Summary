# MessageQueue 锁竞争与 Binder 深化

> 学习资料（文章模式沉淀）。主线：从 Android 17 DeliQueue 的无锁队列改造出发，把 Java 锁等待、IPC 选型、Binder 线程池、冻结进程语义与事务缓冲区放进同一条"等待链"诊断主线。源文档：android-internals-wiki §1.8《MessageQueue 与锁竞争：从 DeliQueue 到系统等待链》、§1.9《Android IPC 全景与 Binder 性能》、§1.10《Binder 线程池、异步事务与 Freezer》、§1.11《Binder 事务缓冲区与可观测性》（Android 17 / ACK 6.18 语境）；Android 17 无锁 MessageQueue 的启用条件与迁移要点、冻结进程的同步与异步 Binder 语义已于 2026-09-25 与官方资料核对。Binder 三件组成与"一次拷贝"、AIDL 与 Stable AIDL 的接口契约差异见 [03-Binder.md](./03-Binder.md)。Q 序列即结构，供 atlas 同源直读。


**Q1: Android 17 的 DeliQueue 优化了消息处理链条的哪一段，为什么它不能让界面绘制或业务回调自动变快？**

DeliQueue 只优化"入队"与"出队"两段的队列管理：生产者不再与 Looper 争用同一个 Java 监视器锁，而是把消息压入无锁栈，再由 Looper 整理进自己独占的最小堆。它不改变 `Handler`、`Looper` 与同步屏障的对外语义，也不会加速 `dispatchMessage()` 之后的布局、绘制、数据库或 Binder 调用。

- 一轮消息处理分三段：入队（Handler 投递）、出队（`MessageQueue.next()` 选择下一条到期消息）、分发（`dispatchMessage()` 执行回调）。DeliQueue 直接优化前两段；第三段耗时仍要沿业务调用栈排查。
- 旧实现用一把 `synchronized` 保护按 `when` 排序的单链表：插入最坏 O(N)，生产者与消费者互斥。锁竞争与调度叠加会形成优先级反转——低优先级线程持锁后被中优先级任务抢占 CPU，高优先级 UI 线程反而被它拖住。多线程高频向主线程 `post()`、队列积压大量延时消息、大范围 `removeCallbacksAndMessages()` 等场景最容易暴露旧结构上限。
- 数字边界：官方博客的 `5,000×` 是"多线程向繁忙队列插入消息"的合成高竞争基准最高值；主线程锁竞争耗时降低 15%、掉帧降低 4%、System UI/Launcher 交互掉帧降低 7.7%、启动到首帧 P95 缩短 9.1% 均描述 Google 内部测试样本，不是对每台设备的性能承诺。


**Q2: 应用要满足什么条件才会启用 DeliQueue？怎样在同一台设备上做出可信的 A/B 对照？**

`targetSdkVersion >= 37` 的普通应用在 Android 17 上默认启用 DeliQueue。实现选择发生在进程启动、主 Looper 创建之前，因此 A/B 必须在切换兼容开关后强制停止并冷启动进程；只切开关不重启进程得不到可信对照。

- 兼容性变更在 `android-17.0.0_r1` 中定义为 `@ChangeId @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.BAKLAVA)` 的 `USE_NEW_MESSAGEQUEUE = 421623328L`；BAKLAVA 对应 API 36，默认启用边界放在 API 37。
- 判定路径：PlatformCompat 判定 → ProcessList 启动应用进程时给 Zygote 加 `--use-deliqueue=<boolean>` → `ActivityThread.main()` 在 `Looper.prepareMainLooper()` 之前解析并设置。选择保存为进程级静态状态，同一进程的 MessageQueue 走同一种实现。
- `CombinedDeliMessageQueue/MessageQueue.java` 同时保留 DeliQueue 与 legacy 两条路径，`next()` 按进程级状态分支。所以"Android 17 源码只有 DeliQueue"与"每个 Android 17 应用都在用 DeliQueue"都不准确；系统进程、测试环境与 feature flag 另有平台内部启用入口，普通应用不应视其为稳定 API。
- A/B 做法：`adb shell am compat enable USE_NEW_MESSAGEQUEUE <包名>`（或 disable），每次切换后 `am force-stop` 再启动。关闭后崩溃消失，优先排查反射与测试工具对内部结构的假设；开启后 monitor contention 消失，说明旧队列锁确是原链路一环；两边 `dispatchMessage()` 都很长则继续修业务代码。兼容开关用于开发验证与故障隔离，不应成为应用长期依赖的产品配置。


**Q3: DeliQueue 用什么结构替代"一把锁加一条有序链表"？排序成本转移到了哪里？**

生产者通过 CAS 把消息压入共享的 `MessageStack`（Treiber 栈），Looper 用 `heapSweep()` 把新消息整理进自己独占的 `mSyncHeap`（同步消息与屏障）与 `mAsyncHeap`（异步消息）两个最小堆；删除消息先对 `Message.flags` 做 CAS 设置 `FLAG_REMOVED` 完成逻辑删除（墓碑节点进入无锁 freelist），再由 Looper 在 `drainFreelist()` 中物理移除。

- 生产者提交路径 O(1)，不随队列长度增长；Looper 随后付出每次 O(log N) 的堆调整成本。排序成本没有消失，而是从"生产者在带锁链表中线性查找插入位置"转移为"单个消费者集中排序"。
- 最小堆按 `when` 排序，同时间用插入序号 `insertSeq` 保持 FIFO；`sendMessageAtFrontOfQueue()` 用递减负序号让队首消息优先。只有 Looper 线程调整堆结构，堆操作不需要再加 Java 锁。
- `removeMessages()` 要匹配所有符合项，查找仍可能遍历既有消息，整体不能宣称 O(1)；读取线程短暂看到墓碑节点时按删除标记忽略。
- 屏障行为不变：屏障生效时同步消息暂缓、到期异步消息可越过。DeliQueue 没有引入业务优先级或新的线程优先级策略，应用能观察到的排序依据仍是 `when`、同时间插入顺序、同步屏障与异步标记。


**Q4: DeliQueue 为什么禁止在并发路径上复用全局 Message 池？**

因为 Treiber 栈的单指针 CAS 无法自行消除 ABA 问题，而"节点对象被回收后再以新消息身份重新出现"正是经典 ABA 的来源。DeliQueue 把处理方式绑定到对象生命周期：进入并发队列的 Message 可能仍被某次删除遍历引用，因此不入池——DeliQueue 路径的 `obtain()` 直接 `new Message()`，`recycleUnchecked()` 只清引用字段、不回收到共享池。

- ABA 场景：线程读到栈顶为对象 A 后暂停；期间 A 被移除、回收到全局池并被复用为新消息，又重新成为栈顶。只比较对象引用的 CAS 看到的栈顶"仍是 A"，会误判为没有变化。
- 墓碑节点保留生命周期，直到 Looper 物理清理，也是为了避免"已删除节点被当作新节点复用"。
- 代价是比旧路径更多的小对象分配。评估 DeliQueue 收益时应同时观察锁竞争、对象分配速率与 GC，不能只看队列操作时长。


**Q5: 官方把 Android 17 的新队列称为"无锁 MessageQueue"，这意味着 MessageQueue 里没有任何锁、CAS 一定比锁快吗？**

都不是。"无锁"指核心消息的并发提交、检查与移除不再依赖旧的单一全局监视器锁，描述的是进展保证——某个参与线程暂停不阻塞其他线程；Android 17 的组合实现仍保留多把锁，CAS 与锁的快慢也取决于竞争形态。

- 仍有锁的部分：`mIdleHandlersLock` 保护 IdleHandler 集合，`mFileDescriptorRecordsLock` 保护文件描述符监听记录，legacy 路径仍是 `synchronized (this)`，原生轮询、唤醒与退出阶段的协调另有原子状态。把"无锁"理解成"任何操作都不加锁"会与源码冲突。
- CAS 不一定更快：低竞争且临界区很短时，锁可能已经足够；高竞争下 CAS 也会反复重试，还要处理消息计数、插入序号与唤醒协调。DeliQueue 的收益来自针对队列结构的整体重构，不能归结为"把每个 synchronized 换成原子变量"。


**Q6: 把依赖 MessageQueue 内部结构的代码迁移到 Android 17 时要注意什么？**

DeliQueue 路径不使用遗留私有字段 `mMessages`，官方迁移文档明确说明该字段会一直是 `null`，任何反射遍历它的监控、测试或调试工具都不再可靠。官方建议的替代是升级测试框架并改用公开或测试专用 API：Espresso 3.7.0 及以上；Robolectric 4.17 及以上，并从 `@LooperMode(LEGACY)` 迁移到 `@LooperMode(PAUSED)`；设备端插桩测试使用 `TestLooperManager`（含 API 36 引入的 `peekWhen()`、`poll()`）。

- 不要改为反射 `MessageStack` 或 `MessageHeap`：它们同样是私有实现，后续版本可以继续变化。
- 需要"队列里还有什么"的断言时，基于 Handler 语义、IdleHandler 或性能轨迹证据重建，而不是寻找新的内部字段。


**Q7: 线程停在 `futex_wait` 就应该去找 Java 持锁者吗？Java 锁等待在 Android 17 上有几条不同路径？**

不应该。Java 锁等待至少有三条路径——ART Monitor（`synchronized`）、原生同步原语（`pthread_mutex`、条件变量、`LockSupport.park()`）与同步 Binder 回复等待。futex 只是让用户态原子状态与内核等待队列协作的底层机制，从 `blocked_function` 里的 `futex_*` 无法还原锁对象与持锁者，必须结合调用栈与轨迹证据。

- ART Monitor：对象头 32 位 LockWord 记录轻量锁状态（`kUnlocked`/`kThinLocked`/`kFatLocked`/`kHashCode`/`kForwardingAddress`）；出现真实竞争、在对象上调用 `wait()` 或对象带身份哈希时膨胀为重量级 Monitor。源码常量 `kLongWaitMs`（release 构建 100ms）只是 ART 长竞争告警的日志阈值，不能推出"小于 100ms 的竞争不重要"——60Hz 一帧约 16.7ms，几毫秒的主线程锁等待已可能掉帧。
- 原生 futex：无竞争时只在用户态做原子操作，竞争时才进内核等待；只有以 `PTHREAD_PRIO_INHERIT` 属性初始化的互斥锁才走 PI-futex/rt-mutex 优先级继承。看到 `futex_wait` 只能证明线程在某个 futex 等待点休眠。
- Binder：同步调用的调用端等的是服务端回复，不是某把"Binder Java 锁"；Binder 有自己独立的事务优先级传播（`binder_transaction_priority()`），与 pthread PI 是两套机制。服务端慢的常见根因在其内部锁、I/O 或嵌套 IPC。
- 诊断入口：Perfetto 标准库 `android.monitor_contention` 给出等待者/持锁者线程、双方方法、锁名与时长（只覆盖 ART Java Monitor，不覆盖 `ReentrantLock` 与原生锁）；futex 候选要从 `thread_state.blocked_function` 回到原生调用栈确认。


**Q8: Android 的 IPC 机制应怎样分层选型？为什么说一条真实链路经常同时使用两三层机制？**

按职责分四层：上层语义与接口（AIDL、Messenger、Intent、ContentProvider）描述"做什么"；控制面 transport（Binder、Unix 域套接字、socket/vsock 上的 Binder RPC）负责控制信息传输；数据面（SharedMemory/mmap、CursorWindow、DMA-BUF、FMQ）承载持续数据；通知与唤醒（eventfd、signal）负责同步。数据形态、方向、频率、生命周期与权限边界决定选择，一条真实链路经常组合使用。

- 典型组合：InputManager 经 Binder 把 `InputChannel` 的文件描述符交给应用，后续输入事件与确认走匿名 `SOCK_SEQPACKET` 套接字；Binder 先传 `SharedMemory` 的 fd，之后双方直接读写同一块映射；HAL 用 AIDL/HIDL 建 FMQ，持续数据走共享内存环形队列。
- 控制信息适合放进 Parcel（方法号、少量参数、令牌、fd）；图像、音频帧、模型权重或大型结果集不应反复写入 Parcel，常见做法是只传句柄或 fd，让接收方据此访问实际数据。
- 错误常出在所有权而非传输：fd 由谁关闭、映射何时解除、DMA-BUF 栅栏完成前谁不能复用缓冲、FMQ 读写方死亡后如何重建，都需要协议明确。
- 不要引用固定的"Binder 0.5ms、Socket 0.1ms"式数字：IPC 延迟至少包含 client 编组、驱动传输、server 排队调度、server 业务与 reply 返回，数据量、CPU、线程池与 SELinux 都会改变结果；比较必须在同一设备、相同负载与统计口径下做。


**Q9: oneway 调用到底保证了什么、没保证什么？`BR_TRANSACTION_COMPLETE` 代表服务端执行完成吗？**

oneway 只保证"调用方不等待业务回复"与"发往同一个 Binder 节点的异步事务按发送顺序逐个分发"；它不保证服务端已执行、不保证跨节点全局有序、也不保证不会失败。`BR_TRANSACTION_COMPLETE` 只表示驱动完成了本次提交——目标进程可能尚未被调度，事务可能仍在 `proc->todo` 或 `node->async_todo` 中排队。

- 排队结构：同一 Binder node 的异步事务串行执行——第一笔在处理时，后续进入该 node 的 `async_todo`，当前 buffer 释放后才取下一笔；不同 node 的异步事务可以并行。
- 优先级：oneway 事务不继承调用方线程优先级，使用目标进程默认优先级；目标 node 自身配置的 `min_priority` 仍参与服务端执行优先级。
- 失败面：提交仍要在目标进程分配 buffer，异步空间不足会得到 `-ENOSPC`/`FAILED_TRANSACTION`；目标进程死亡或冻结也有对应结果。服务端 oneway 方法抛出的异常不会写回调用方，需要业务确认时应设计独立回调并带超时。
- 协议设计：A 必须先于 B 生效时，让 A、B 经过同一个串行执行点，或给消息加序列号与状态校验，不能依赖"都是 oneway"；用 oneway 掩盖服务端过载只会把延迟转移到队列里。


**Q10: 为什么"Binder 单次调用可以安全传接近 1MB"是错误结论？**

因为约 1MB 是一个进程的接收映射区总大小，不是单笔事务的配额：精确值为 `1 MiB − 2×页大小`（4KiB 页设备约 1016KiB，16KiB 页设备约 992KiB），进程内所有在途事务——并发请求、回复、oneway、对象元数据——共享这块空间。`TransactionTooLargeException` 也无法区分是请求没有发出还是回复过大，只能按"操作可能部分完成"处理。

- `BINDER_VM_SIZE = (1×1024×1024) − sysconf(_SC_PAGE_SIZE)×2` 是 libbinder 请求的映射长度；内核驱动另有 mmap 上限 `min(请求长度, 4 MiB)`，那是保护性上限，不代表默认分配 4MB。
- 请求 buffer 分配在目标进程的 `binder_alloc` 中；B 回复 A 时占用的又是 A 的空间。多个中等事务并发也可能共同耗尽缓冲区。
- 内核把一半映射作为异步事务初始预算（`free_async_space = buffer_size/2`），大量 oneway 也会触发分配失败。
- 工程规则：Binder 事务保持小；大数据改用文件描述符、分页或流式传递，不要把"单次不到 1MB"当作安全线。


**Q11: 大数据跨进程传输应该如何设计？`writeBlob` 的 16KiB 分界、`SharedMemory.setProtect()` 和 FMQ 各自的边界是什么？**

原则是 Binder 只传控制信息与句柄，持续数据走共享内存、文件描述符或专用队列。C++ `Parcel::writeBlob()` 以 16KiB 为分界：不超过 16KiB 直接内联写入 Parcel；超过且允许传 fd 时改走兼容 ashmem 区域并用 fd 传递。Java `SharedMemory.setProtect()` 只能移除权限不能加回，应按"写入 → 解除映射 → 降为只读 → 交给对端"的最小权限顺序使用。FMQ 是共享内存上的有界单向队列，单个队列只有一个写入方；双向协议要建两条方向相反的队列。

- `writeBlob` 分流只作用于二进制块路径；普通字节数组与集合仍会被内联编组，"所有大 `byte[]` 自动走共享内存"不成立。
- 共享内存不是端到端零拷贝：生产方写入、缺页与缓存同步、消费方读取复制仍然存在；共享页也没有消息边界与顺序，需要自建协议（版本、长度、状态、校验）。
- FMQ 的 synchronized 队列单读单写、不允许覆盖；unsynchronized 队列可多读但写入可覆盖旧数据、落后的读取方丢数据。它适合固定布局、高频、小单元的数据流，不适合可变长复杂对象或需要逐条权限检查的协议。
- 走 socket/vsock 的 Binder RPC（如 Microdroid pVM 场景）不使用内核 Binder 的接收映射，"1MB 缓冲区"结论对它不适用。


**Q12: "Binder 线程池默认 15 个线程"该怎么准确理解？怎样判断服务端真的发生了线程池饥饿？**

15（`DEFAULT_MAX_BINDER_THREADS`）是 libbinder 经 `BINDER_SET_MAX_THREADS` 告诉驱动"最多可按需请求启动的 lazy 线程"上限；`startThreadPool()` 还会主动创建 1 个主线程池线程，显式 `joinThreadPool()` 与服务自设上限再叠加。因此"每个进程固定 15 或 16 条 Binder 线程"都会误导容量分析。饥饿判定要看组合证据，而不是数线程或单看一条日志。

- 驱动在"没有尚未兑现的线程请求、`waiting_threads` 为空、已启动线程数低于 `max_threads`"时返回 `BR_SPAWN_LOOPER`，libbinder 才按需建线程；线程创建后通常存活到进程结束。
- AOSP Android 17 的 system_server 设 `sMaxBinderThreads = 31`（另有 1 个主动线程，总量上界通常可到 32）；应用主线程不默认加入 Binder 池。
- libbinder 的启发式日志：执行中的 Binder 线程数达到配置上限并持续超过 100ms 时输出 `binder thread pool (N threads) starved for M ms`。它只说明"该进程长时间没有空闲工作线程"，不能证明驱动队列有积压或延迟全由 CPU 忙导致；`blockUntilThreadAvailable()` 存在但标准 `transact()` 路径不会调用它。
- 饥饿常见根因：慢接口实现（持锁跨进程调用、无超时 I/O、数据库长事务）、嵌套同步调用占用 worker、oneway 积压。只有当多个请求可安全并行、共享资源有余量、轨迹证明等待空闲 worker 占主导时，增加线程才可能有效；所有 worker 都在等同一把锁时，加线程只会增加等待者。


**Q13: oneway 事务在驱动里如何排队？异步空间紧张时调用方会看到什么？**

oneway 事务仍要在目标进程分配 buffer 并由 Binder 线程执行；同一 Binder node 串行、不同 node 并行。当目标进程剩余异步预算低于总映射的 10%，且当前发送进程占用超过 50 个异步 buffer 或总占用超过总映射的 1/4 时，该笔事务被标记 `oneway_spam_suspect`：发送线程收到 `BR_ONEWAY_SPAM_SUSPECT`，libbinder 打印发送侧调用栈。这是诊断信号，不做限流，事务本身通常仍成功。

- 记账模型：`free_async_space` 初始化为 `buffer_size/2`，同步与异步从同一棵空闲树分配；异步分配前检查并扣减预算，释放后归还。它不是把一半物理划给 oneway 的独立内存池。
- 触发 spam 检测时，目标进程的异步预算已经非常紧张；治理要在协议层完成——合并可覆盖的状态更新（只留最新值）、限频、为事件队列设置容量与丢弃策略，不能等驱动告警才处理。
- oneway 的 buffer 生命周期不一定比同步短：没有回复触发释放，要到服务端处理完成并释放 Parcel 后才归还；高频发送会同时占据目标进程地址池。


**Q14: 向冻结进程发起同步与 oneway Binder 调用分别会发生什么？**

同步事务会被驱动拒绝（`BR_FROZEN_REPLY`），平台的冻结策略还会终止被冻结的接收进程（退出原因 `REASON_FREEZER`），调用方收到 `RemoteException`；oneway 事务允许排入待处理队列，发送方收到 `BR_TRANSACTION_PENDING_FROZEN`，目标解冻后才消费，期间持续占用目标 buffer，缓冲区压力过高时目标同样可能被终止。

- 最常见的应用错误是解绑后继续使用旧代理：`unbindService()` 后服务端退到缓存态被冻结，客户端仍持旧 `IBinder` 发同步调用。修复是让 Binder 引用生命周期与绑定关系一致，解绑后立即丢弃代理。
- oneway 不是万能解法：解冻后一次性处理大量过期回调会造成 CPU 突增与业务状态倒退。事件语义应分层——瞬时采样可丢弃、当前状态只保留最新、不可丢业务记录要有上限地排队并设计补偿。API 36 起可用 `IBinder.addFrozenStateChangeCallback()` 观察远端冻结状态（状态可能合并，不能用回调次数统计冻结次数）；`RemoteCallbackList` 提供 `FROZEN_CALLEE_POLICY_DROP`/`ENQUEUE_MOST_RECENT`/`ENQUEUE_ALL`（默认上限 1000 条）三种策略。
- `BINDER_GET_FROZEN_INFO` 返回的 `sync_recv`/`async_recv` 是位标志（"冻结期间是否收到过"），不是事务计数。


**Q15: Android 17 依据什么决定一个缓存进程能否被冻结？为什么写入 `cgroup.freeze=1` 不代表已经冻结？**

Android 17 用 CPU 执行资格（capability）模型决策：OomAdjuster 计算进程是否持有 `PROCESS_CAPABILITY_CPU_TIME`（顶部、可见工作、FGS、正在执行服务回调或接收广播等明确工作）或 `PROCESS_CAPABILITY_IMPLICIT_CPU_TIME`（adj 低于阈值时的兼容行为）；`getFreezePolicy()` 对两者都缺失的进程允许冻结。执行顺序是 Binder freeze 先于 cgroup freeze——先阻止新同步事务并等待在途事务排空，再暂停线程；顺序颠倒会让调用端等待一个永远不会执行的服务端。

- 隐式资格阈值 `DEFAULT_FREEZER_CUTOFF_ADJ` 在激进冻结实验开启时取 `HOME_APP_ADJ`（600），常规配置取 `CACHED_APP_MIN_ADJ`（900）；`maxAdj` 更低的约束也可能让进程保留隐式资格，所以"`curAdj >= 900` 就冻结"不成立。
- 进入缓存态后默认还有 10 秒防抖（`config_defaultFreezerDebounceTimeout = 10000`），避免打断状态收尾与反复冻结解冻。
- cgroup v2 中写 `cgroup.freeze=1` 只是请求冻结：`CGRP_FREEZE` 表示已请求，`CGRP_FROZEN`（`cgroup.events` 的 `frozen=1`）才表示任务真正停住；早期实现中的 `shouldNotFreeze()`/`isFreezeExempt()` 已不是 Android 17 主路径。
- 冻结不是内存回收：地址空间、Java 堆、fd 与 Binder 引用仍在，RSS/PSS 不会因冻结归零；GC、内存压缩、ZRAM 回写是冻结前后另外的动作。


**Q16: Binder 相关的约 1MiB、4MiB、600KiB、300KiB 这几个数字分别约束什么？**

它们属于四个不同对象：`1 MiB − 2×页大小` 是 AOSP libbinder 为每个进程请求的接收映射长度；`min(请求长度, 4 MiB)` 是内核驱动接受的单个 mmap 保护上限；600 KiB 是 RPC Binder 单条命令/回复包的协议上限（Android 15/V 及以前为 100 KB，Android 16/Baklava 起提高到 600 KiB）；300 KiB（`kLogTransactionsOverBytes`）只是大事务告警线，不是硬上限。

- 4MiB 不等于默认 4MB：AOSP 进程只请求约 1MiB，实际 `buffer_size` 仍是 1016KiB（4KiB 页）或 992KiB（16KiB 页）。
- 600KiB 只作用于 RPC Binder 路径（socket/vsock 传输），Parcel 可用空间还要再扣除协议头与对象表；普通应用调用系统服务走内核 Binder，不受此值影响，也不会因此获得更大内核 Binder 空间。
- 300KiB 告警分别来自 `BpBinder` 的 `Large outgoing transaction` 与 `BBinder` 的 `Large data transaction`/`Large reply transaction`；出现说明事务在挤压进程并发空间，应检查接口是否把大块数据、无界列表或图片直接塞进 Parcel。
- 另有 `BBinder` 1000ms 慢事务日志：测量的是服务端 `onTransact()` 执行区间，不含到达前的排队，不是端到端耗时。


**Q17: 一笔事务的 Binder buffer 如何分配与归还？空间不足时的行为是什么？**

驱动把 `data_size`、`offsets_size`、`extra_buffers_size` 三部分分别做指针大小对齐后求和，从目标进程空闲 buffer 红黑树中做最佳适配分配（大块切分、释放时与相邻空闲块合并）；找不到合适空闲块或异步预算不足时立即返回 `-ENOSPC`——不阻塞等待旧 buffer 释放，也不会借 `BR_SPAWN_LOOPER` 扩线程。接收方处理完成后经 `BC_FREE_BUFFER` 归还；同步调用在 Android 17 中发送回复前就释放请求 buffer。

- 映射属于接收方：A 调用 B，请求占 B 的 `binder_alloc`；B 的回复占 A 的。同步与异步共享同一地址池，异步另有半池记账预算。
- oneway 的 buffer 要等服务端处理完才归还，高频 oneway 会拉长占用时间。
- 发送方 Parcel 的内存与接收方 Binder 空间是两个指标；`TransactionTooLargeException` 是 Java 层按失败上下文推测的异常，不是驱动返回的精确字节上限。
- 排查 `-ENOSPC`/`ENOSPC` 扩展错误时，先看接收进程的并发事务、大事务与异步占用，而不是假设"这一笔超过了 1MiB"。


**Q18: 排查 Binder 问题时，冻结状态位、扩展错误、AIDL trace 与 Perfetto 表各自能回答什么、不能回答什么？**

它们是不同观察面，没有统一的"粗到细"层级：binderfs `features` 文件声明驱动能力（如 `oneway_spam_detection`、`extended_error`、`freeze_notification`），不表示运行状态；`BINDER_GET_FROZEN_INFO` 返回 `sync_recv`/`async_recv` 位标志，不提供次数或耗时；`BINDER_GET_EXTENDED_ERROR` 是线程级一次性信息（`id`/`command`/`param`，读取后立即重置），libbinder 只对 `ENOSPC` 给出专门解释；AIDL trace（`ATRACE_TAG_AIDL`）提供 `AIDL::cpp::<接口>::<方法>::server` 形式的方法时间片，不含参数内容；Perfetto `android_binder_txns` 关联两端与同步类型，但没有 `dispatch_dur` 列——服务端开始延迟要用 `server_ts − client_ts` 自行计算并解释。

- `sync_recv == 3` 表示两个状态位都为 1，不是"发生了三次同步事务"；`async_recv` 也不是单调计数。
- AIDL 名称依赖轨迹数据可解析：只采集内核 Binder 事件时 `interface`/`method_name` 可能为空；映射缺失或事务码不在用户方法范围时，切片名退化为 `UNKNOWN_CODE_<n>`。
- 内核 tracepoint 有 `binder_transaction`、`binder_transaction_received`、`binder_transaction_alloc_buf`、`binder_txn_latency_free`；不存在名为 `binder_reply` 或 `binder_freeze` 的 tracepoint，不要用不存在的内核事件名标记区间。
- debugfs/binderfs 状态文件是读取时刻的快照，两次读取之间完成的事务可能完全看不到。


**Q19: `RecordedTransaction` 能做什么？为什么不能当线上常驻监控？**

`RecordedTransaction` 是 libbinder 的事务录制能力，可保存接口名、事务码、flags、返回状态与请求/回复 Parcel 内容，适合受控环境下复现协议问题与离线检查。它同时受三个条件限制：libbinder 编译期定义 `BINDER_ENABLE_RECORDING`、使用内核 Binder、发起录制的调用方 UID 为 root；源码明确标记文件格式仍在开发、不稳定，录制内容可能包含令牌等敏感数据，序列化与写文件还会改变被测路径的时延。

- 时间戳在服务端 `onTransact()` 返回后采集，不能当作事务开始时间；发送路径没有对称的客户端录制入口，"两端各录一次拼出端到端"不成立。
- 端到端时间应由 Perfetto 的 Binder flow 解释；录制文件要按敏感数据管理，分析完成后清理。
- 与 `binder_calls_stats` 区分：后者是 framework 按 UID/接口/方法聚合的统计（带抽样），不是驱动队列监视器，也不是"更细统计模式"开关。
