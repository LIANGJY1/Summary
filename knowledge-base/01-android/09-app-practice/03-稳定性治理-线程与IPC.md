# 稳定性治理：线程、协程与 IPC

> 学习资料（文章模式沉淀）。主线：线程与协程泄漏按 owner 与生命周期证据判定而不是看线程数，Binder 故障按"传输、服务端、业务"三个结果分类定位而不是看一个异常名，Android 17 的 Keystore 密钥配额把 alias 当作有 owner、状态和回滚期的持久资源治理。源文档：android-internals-wiki §20.8《线程与协程泄漏治理》、§20.9《Binder IPC 故障判断与性能诊断》、§20.10《Android 17 Keystore 密钥配额与登录恢复》；可本地核对的机制按 AAOS13 源码（Android 13）核对并标注版本差异，工程实践按材料口径转写、不确定处已弱化；Android 17 的 Keystore 配额数值与错误码边界已与官方文档核对。Binder 线程池规模、事务缓冲区与冻结进程的机制层见 [../architecture/13-MessageQueue锁竞争与Binder深化.md](../01-architecture/13-MessageQueue锁竞争与Binder深化.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 线程快照里出现大量 WAITING 状态的 `Thread-N` 线程，能据此判定线程泄漏并强杀这些线程吗？**

不能。`WAITING` 是空闲等待的常见状态——线程池空闲 worker、Binder 线程、GC 线程和等待消息的 `HandlerThread` 都可能长期休眠；`Thread-N`、`pool-N-thread-M` 这类默认名称只说明归因信息不足，不能单独证明线程不该存活。这里的"泄漏"限定为：资源超过约定生命周期后仍存活，或仍被强引用。判定前先回答三个问题：哪个模块创建了它、它执行哪类任务并由谁取消或关闭、同一 owner 在一次进程生命周期内允许创建多少个 pool 和 worker。

强杀（`Thread.stop()`、`pthread_cancel()` 或反射终止）不在可接受方案内：线程可能持有 Java monitor、malloc 锁、数据库事务或库内状态，强停会把"线程多"变成数据损坏或死锁。工程上的归因手段是统一 `ThreadFactory` 用 `模块-用途-序号` 格式命名（如 `img-dec-3`），并把创建入口记录下来。命名有硬边界：内核 comm 短名称上限 16 字节且含结尾 NUL，AAOS13 的 `pthread_setname_np()` 对超长名称返回 `ERANGE`（按 `bionic/libc/bionic/pthread_setname_np.cpp` 核对），所以较长的 Java `Thread.name` 与 `/proc/self/task/<tid>/comm` 可能不同，Native 标识应放在前 15 个 ASCII 字节内，也不要把账号、URL 等用户数据放进线程名。

**Q2: 按 Android 13 源码，Java 线程退出时 ART 会清空 `Thread` 的 `target` 与 `ThreadLocal` 字段吗？已终止的 `Thread` 被静态集合持有时为什么会滞留对象？**

不会。按 AAOS13 源码核对（`libcore/ojluni/src/main/java/java/lang/Thread.java`）：同文件的私有 `exit()` 会清空 `target`、`threadLocals`、`inheritableThreadLocals`、`blocker` 等字段，但 `getThreadGroup()` 处的 Android-added 注释明确写出"Android runtime does not call exit() when a Thread exits"——ART 的 `Thread::Destroy()` 在 native 路径分发未捕获异常、移除 Java peer 并唤醒 join 等待者，不执行这段 Java 清理。

由此要区分两类对象链。仍存活的线程本身是 GC root，其栈和线程局部引用随之存活，判断滞留要看它执行的任务持有什么。已终止的 `Thread` 不再对应 Linux task，但业务静态集合、线程注册表等长生命周期对象若仍强引用它，`target`、`ThreadLocalMap` 或线程子类字段仍会继续保住背后的对象图；只有线程终止且外部强引用释放后，整条链才随 `Thread` 对象一起回收。排查用 heap dominator 分析确认具体强引用链，不能从线程状态直接推断。版本边界：该行为按 AAOS13（Android 13）核对，材料按 Android 17 源码（`android-17.0.0_r1`）核对结论一致。

**Q3: `/proc/self/status` 的 `Threads`、`/proc/self/task`、Java `Thread` 快照与组件自身指标这四个观测面各能回答什么？`ThreadGroup.activeCount()` 能当硬限流依据吗？**

四个观测面口径不同且不能互相替代：`Threads` 给当前进程的 Linux task 总数但没有来源与状态；`/proc/self/task/<tid>` 给单个 task 的 TID、comm 短名称与调度状态，但看不到已退出未 join 的 pthread；Java `Thread` 快照只覆盖有 Java peer 的线程并提供调用栈，且各条栈的采样时刻不同；pool、协程和 SDK 自身指标给出 owner、队列等组件语义，但只覆盖已接入的组件。`activeCount()` 不能当硬限流依据：它的文档和实现都说明返回值是估计值，线程可能在计数与枚举之间启动或结束，`enumerate(Thread[])` 在数组过小时还会静默截断，而且它统计的是 Java 线程树，不等于进程 Linux task 数。`Thread.getAllStackTraces()` 同理不适合常态计数——每次调用创建 `Map` 和每线程栈数组、秒级轮询开销高，正确用法是先用低成本计数发现增长，再触发式生成诊断快照。

对齐证据时注意两点：Java 的 `threadId()`（API 36 起）/`getId()` 是 Java 生命周期 ID，不是 Linux TID，只有在线程内部调用 `Process.myTid()` 或在可靠创建事件中取得 TID，才能与 Perfetto、tombstone 和 `/proc` 对齐；TID 在线程退出后会复用，跨时间关联必须带采样时间。枚举 `/proc/self/task` 时要把读取期间消失的 TID 当正常竞态、把读取失败记为"缺测"而不是 0 条。

**Q4: raw joinable pthread 退出后，为什么 `/proc` 和所有线程快照都看不到它，但 Native 内存仍可能增长？监控要覆盖哪些事件？**

joinable pthread 的入口函数返回或调用 `pthread_exit()` 后就没有 Linux task 了，但它的栈映射等资源要等另一线程调用 `pthread_join()`（或存活时 `pthread_detach()`）才回收；创建时设为 detached 的线程在退出时自行回收。这类"已退出、未回收"的资源在 `/proc/self/task`、`Threads`、Java 栈快照和 Perfetto 的存活线程表里全部不可见，所以活线程数回落而 Native RSS 或虚拟地址空间持续增长时，应优先怀疑这类资源。

监控必须覆盖成对事件而非只数存活线程：创建、入口返回/`pthread_exit`、`pthread_join`、`pthread_detach`，并以创建序号作事件 ID——`pthread_t` 和 TID 都可能复用，不能长期作主键。自有 C/C++ 代码用 RAII 或统一包装层执行协议：无需返回结果的 worker 创建前设 detached，需要结果的 joinable worker 只由一个 owner join，创建失败不把未初始化的 `pthread_t` 写入注册表。边界：普通 Java `Thread` 由 ART 以 `PTHREAD_CREATE_DETACHED` 属性创建（按 AAOS13 源码核对，`art/runtime/thread.cc` 的 `Thread::CreateNativeThread()`），退出后不需要业务 join，这一盲区只针对直接 `pthread_create()` 且未设 detached 属性的 raw 线程。

**Q5: "每条线程默认占 1 MiB 内存""线程与 FD 同步增长说明每条线程占一个 FD"这两个推断错在哪？**

两处都错。前者混淆了虚拟地址预留与常驻内存，且漏掉线程私有附属结构；后者把共同增长当成了因果。按 AAOS13 源码核对：bionic 对 raw pthread 的默认栈常量是 1 MiB 减去独立信号栈的可用部分（`bionic/libc/bionic/pthread_internal.h` 的 `PTHREAD_STACK_SIZE_DEFAULT`），主映射还包含 guard page、static TLS 等，并以 `MAP_NORESERVE` 创建——创建映射时不要求内核预留等量物理空间；Java `Thread` 还要经 ART 的 `FixStackSize()`（`art/runtime/thread.cc`）先取 `-Xss` 默认值、再为 Dalvik 兼容加 1 MiB 并按页对齐。虚拟地址空间是进程可寻址范围，RSS 只统计当前驻留的物理页，"线程数 × 1 MiB"两种口径都不成立，估算应在目标 ABI、页大小、设备内存和相同负载上比较 `/proc/self/maps`、`smaps_rollup`、RSS 与 task 数的共同变化。

线程不天然持有 FD：`/proc/self/task/<tid>` 是 procfs 视图，不表示进程为每条线程常驻打开文件描述符。线程与 FD 一起增长通常说明同一模块同时创建 worker 与 socket、pipe、eventfd 或文件，关联必须按 owner 和时间线证明，不能按数量推导。同样，`pthread_create` 抛出 `OutOfMemoryError` 只说明创建路径失败，可能发生在 ART 分配、bionic 栈/TLS 映射或内核 `clone`——不能因 Java heap 尚有余量就排除资源耗尽，要保留原始错误、task 数、`VmSize`/RSS 与进程角色。

**Q6: `activeCount` 小于 `poolSize`、或大量一次性延迟任务被取消后内存仍不降，能得出 worker 泄漏的结论吗？应分别怎么处理？**

都不能，这是两类不同的问题。`getActiveCount()` 返回正在执行任务的 worker 估计数，空闲 worker 等待队列是常态，`activeCount < poolSize` 不证明泄漏；线程池的判断要看组合证据——`corePoolSize`/`maximumPoolSize`/`poolSize`/`largestPoolSize`、队列长度与最老任务等待时间、拒绝次数、pool 实例数与 `isShutdown` 状态。固定线程池常用无界队列，worker 数稳定时任务和内存仍会堆积；cached pool 的空闲 worker 在 keep-alive 到期后回落。比"某一条匿名线程"更值得查的是 pool 实例重复创建：线程名前缀里的 pool 序号持续增长且旧 pool worker 长期存活，通常提示组件重复初始化或缺少关闭；隔离明确、能独立关闭的模块可以拥有专用 pool，但必须有并发预算和明确 owner。

取消的 `ScheduledThreadPoolExecutor` 延迟任务滞留队列是另一类问题：一次性延迟任务被取消后默认仍留在 delay queue 中直到原延迟到期，大量长延迟任务会造成对象滞留。业务允许时启用 `setRemoveOnCancelPolicy(true)`，由 owner 保存代表计划任务的 `ScheduledFuture` 并在生命周期结束时取消，再关闭专用 executor。它基于固定数量的 core worker 和无界 delay queue，调大 `maximumPoolSize` 通常没有作用——"worker 没退出"和"取消任务仍在队列"的修复点不同。

**Q7: 排查"协程数量持续上涨"时，如何区分协程泄漏与线程泄漏？`GlobalScope` 的边界是什么？**

先画两条曲线：活跃 `Job`/队列数与 Linux task 数。只有 Job 增长而线程平稳，是 scope 或任务生命周期问题，重点查未完成的 Job、捕获对象和不可取消的调用；两者同步增长，才查自建 dispatcher、executor 或 native 库。原因是协程挂起时不占专属线程：`Dispatchers.Default` 和 `Dispatchers.IO` 复用调度 worker，一万个挂起协程不等于一万条 Linux task，协程恢复时还可能换到另一个 worker。

`GlobalScope` 是带 `DelicateCoroutinesApi` 标记的进程级 scope，启动的任务没有可由业务 owner 统一等待或取消的父 `Job`，容易让任务及其捕获的 Activity、View 超过页面或会话生命周期——但它不会自动为每个协程新建线程，所以它主要造成"任务生命周期泄漏"而不是线程泄漏。更接近线程资源泄漏的协程用法是：重复创建 `newSingleThreadContext()` 后不 `close()`、新建 `ExecutorService` 转 dispatcher 后不关闭、每次进入页面创建独立 dispatcher 并存进长生命周期对象。长期组件应持有显式创建、显式关闭的 root scope（`SupervisorJob` + dispatcher + `AutoCloseable`），关闭入口由组件的结束回调调用；一次性请求内部的并发子任务用 `coroutineScope`/`supervisorScope`，不为每个请求再造 root。版本边界：协程语义按 kotlinx.coroutines 1.11.0 口径转写，库版本独立于 Android 版本。

**Q8: 协程的 `cancel()` 为什么不保证立即停止？`Dispatchers.limitedParallelism(1)` 是跨挂起点的互斥锁吗？**

取消是协作式的：`cancel()` 只是标记，代码需要执行取消检查或进入支持取消的挂起点才会停止。`delay`、多数 Channel/Flow 操作和支持取消的挂起 API 会检查取消；不含挂起点的 CPU 循环、吞掉 `CancellationException` 的捕获逻辑（如 `runCatching` 包住全部 `Throwable`）、不可中断的阻塞 I/O 在 `cancel()` 后仍会运行。对支持线程中断的 Java 阻塞 API 用 `runInterruptible`，对 socket、stream 等资源还要提供能关闭底层对象的取消方法；`withTimeout` 也只能取消协程本身，不能自动中断不响应取消的阻塞调用。

`limitedParallelism(1)` 不是互斥锁：它只保证同一时刻最多一段代码在线程上执行，一个协程挂起后另一个协程可以进入同一段逻辑。跨挂起点限流要用 `Semaphore`（permit 会跨 `withContext` 和挂起点持续持有）或资源池本身。另外，`limitedParallelism` 创建的是复用原 dispatcher 的视图，不需要 `close()`；通过 `ExecutorService.asCoroutineDispatcher()` 创建的独立 dispatcher 则必须由 owner 在结束时关闭。最后，协程没有独立的 Linux 调度优先级——内核调度的是线程，在线程池协程里调 `Process.setThreadPriority()` 会改掉可复用 worker 的属性并影响后续无关任务，需要稳定线程属性的组件应使用有界专用 executor 并在 `ThreadFactory` 中设置。

**Q9: 一次同步 Binder 调用失败时，为什么不能凭一个 Java 异常断言"服务端没有执行"？排查时应分别记录哪三个结果？**

因为同步调用有四个可能失败的位置——请求序列化、驱动投递、服务端执行、回复序列化与返回——而回复阶段的失败在客户端看来同样是调用失败。官方要求把 `TransactionTooLargeException` 当"部分失败"（partial failure）处理：请求可能尚未送达，也可能已经执行、只因回复过大而无法返回；服务端提交副作用后死亡也有同样的不确定性。

排查时应分别记录三个结果：传输是否完成、服务端是否执行、业务是否提交，再按操作语义决定动作。纯读取、可重复查询可在重新获取代理对象后按既定上限重试；带稳定 request ID 的幂等写先查询服务端状态、确认未提交后再重放；扣款、消费一次性令牌等结果未知的操作只能查询提交状态，不能盲目重放；`SecurityException` 和参数错误要修权限或参数，退避重试无效。边界：请求序列化阶段的本地运行时异常通常可以断言服务端未执行，但仍要确认异常发生的位置；`oneway` 调用在本地返回也不代表目标已经处理，可靠投递要另建确认和序号协议。

**Q10: 调用 framework 管理类需要到处 `catch (RemoteException)` 吗？服务端 `onTransact()` 抛出的异常如何回到客户端？**

不需要也不一定可行。`RemoteException` 是受检异常，但 `PackageManager`、`ActivityManager` 等管理类往往已在内部捕获，再经 `rethrowFromSystemServer()` 转换成该 API 约定的运行时异常；只有自有 AIDL 代理对象或签名明确声明 `RemoteException` 的接口，才在调用处处理这组受检异常。"给所有系统 API 加 catch"既可能编不过，也覆盖不了完整边界。

服务端抛出的异常如果 Parcel 能表示，会被编码进同步回复、在客户端原样重放。按 AAOS13 源码核对（`frameworks/base/core/java/android/os/Parcel.java` 的 `getExceptionCode()`），可编码类型包括 `SecurityException`、`BadParcelableException`、`IllegalArgumentException`、`NullPointerException`、`IllegalStateException`、`NetworkOnMainThreadException`、`UnsupportedOperationException`、`ServiceSpecificException` 以及 BootClassLoader 中的 `Parcelable` 异常。看到这类异常说明请求通常已到达服务端：`SecurityException` 是权限或身份问题，不应自动重试；`ServiceSpecificException` 携带服务自定义 `errorCode`，应在接口契约中说明每个错误码能否重试。两个关键边界：`oneway` 没有回复通道，服务端异常无法沿这条路径返回，需要业务确认时另设计回调加超时；客户端解包失败（`BadParcelableException`、类加载器找不到 Parcelable、Stable AIDL 版本不兼容）不等同于远端死亡，记录要含接口版本、transaction code 与出错方向。

**Q11: 驱动返回 `FAILED_TRANSACTION` 时 Java 层如何选异常？小 Parcel 调用失败却抛 `DeadObjectException` 合理吗？**

合理。Java Binder JNI 收到 `FAILED_TRANSACTION` 时按启发式选异常：按 AAOS13 源码核对（`frameworks/base/core/jni/android_util_Binder.cpp`），请求 Parcel 超过 200 KiB 才抛 `TransactionTooLargeException`；更小的失败优先映射为 `DeadObjectException`——源码注释明确说明 `FAILED_TRANSACTION` 还可能来自格式错误的 transaction、已关闭的 FD、远端在传输途中死亡或缓冲区空间不足，异常名不是精确的字节原因。

由此得到两个排障结论。其一，约 1 MiB（精确为 1 MiB − 2×页大小）的接收映射由本进程所有在途事务共享，多笔中等大小的并发请求和回复也可能共同耗尽缓冲区，所以小 Parcel 失败未必是"这一笔太大"，异常也未必叫 `TransactionTooLargeException`（缓冲区机制层见相邻的 Binder 深化文档）。其二，回复过大时客户端日志中的请求大小可能很小，而服务端可能已经执行完。公开建议值是 `IBinder.MAX_IPC_SIZE` 的 64 KiB——AAOS13 中已是公开常量，`getSuggestedMaxIpcSizeBytes()` 在本地树中仍是隐藏方法、API 34 起公开——它是建议上界而非驱动硬限制，自有接口可设更低预算。测试中可测 `Parcel.dataSize()` 为请求写回归断言，但该值不含并发压力、回复与驱动元数据开销，不能证明线上一定成功。

**Q12: `linkToDeath()` 的注册与回调之间存在哪些竞态？死亡回调里应该做什么、不该做什么？**

注册时目标可能已经死亡，此时 `linkToDeath()` 直接抛 `RemoteException`；`isBinderAlive()` 返回真也只代表检查发生的那一刻，返回后目标可能立即退出。所以旧代理对象一旦收到死亡回调就不能再使用，重连后必须获取新代理并重新协商状态。

死亡回调线程只做三件事：递增连接代次（epoch，每次重连变化）并把当前代理标记不可用；清理依附于旧进程的会话、回调注册和缓存句柄；把重新绑定或获取服务的工作交给应用统一管理的 executor。回调线程不做磁盘和网络操作、不等待新服务。重试策略由操作语义决定：幂等写先查状态再重放，非幂等操作结果未知时只查询提交状态，`TransactionTooLargeException` 原样重试没有价值。连接代次还有统计价值：远端一次死亡会让多条并发调用同时抛 `DeadObjectException`，死亡率要按代次或一次死亡通知去重，否则一次事故会被算成多次。

**Q13: 给自有 AIDL 客户端包装层计时，得到的"耗时"包含哪些段？为什么普通应用不能指望全局 Binder 拦截点？**

外层计时得到的是端到端等待：marshal + 驱动发送 + 服务端排队 + 服务端执行 + 回复 + unmarshal。它适合衡量用户体验，但不能命名为"Binder 传输耗时"——排队和服务端执行占比可能远大于传输本身。两个补充口径：`oneway` 的外层计时只覆盖序列化与本地提交，不表示远端已经执行；同进程的本地 Binder 可能直接调用 Stub，应与跨进程样本分开统计。

普通应用没有可依赖的全局拦截点：`Binder.ProxyTransactListener`、`BinderInternal.Observer` 属于隐藏或平台内部接口，native/Rust Binder 也不一定经过同一个 Java 入口，反射或 native Hook 还会改变被测路径的时序。可行做法是在自有 AIDL 契约的客户端和服务端包装层记录：逻辑接口、线程、耗时、是否主线程与失败分类；标签来自编译期确定的枚举，不含 URI、用户 ID 或异常消息；recorder 用固定容量缓冲、写满丢弃并计数，监控故障不得遮蔽业务结果。分位数需要低比例均匀采样，只上传慢样本得到的只是"慢调用内部的分布"；服务端包装层另记 `server_enter`/`server_exit`，两端关联靠业务协议里的 request ID，不靠可能存在时钟偏差的日志时间戳。

**Q14: ANR trace 显示主线程停在 `BinderProxy.transactNative`，接下来怎样补全证据链？**

客户端堆栈到此为止只能证明"主线程正在等待某次 IPC"，不能说明对端在做什么。下一步用 Perfetto 对齐时间线：采集 `binder_driver`、`sched` 和相关 atrace 类别后，沿 flow（连接跨线程事件的箭头）找到服务端线程，确认它在排队、等待锁、执行 I/O 还是发起下游 Binder，再看回复返回后客户端何时恢复。归因常见结论是服务端慢：Binder 线程持锁、磁盘 I/O、等待硬件或调用缓慢的下游服务；嵌套同步调用（A 调 B、B 又同步回调 A）还可能形成跨进程循环等待。

服务端侧的证据包括：对应 request ID 是否到达、副作用是否完成、`onTransact()` 与业务函数耗时、Binder 线程状态与持有的锁、进程崩溃/冻结/重启时间；`oneway` 场景还要看队列策略、丢弃合并数量与消费序号缺口。工具边界要心里有数：`dumpsys binder <pid>` 并非所有量产设备都提供，`/sys/kernel/debug/binder` 或 binderfs 统计节点受构建类型与 SELinux 限制，日志中的 `FAILED BINDER TRANSACTION`、`oneway spamming` 等标签随构建变化、只能作辅助证据。修复方向按根因：服务端 `onTransact()` 快速校验并复制参数后，把耗时任务交给有容量上限的业务 executor 尽快释放 Binder 线程；不要持应用锁发起外部同步 Binder 调用，无法避免时规定统一的跨进程加锁顺序并设计超时。

**Q15: Android 17 的 Keystore 密钥配额规则是什么？Android 13 设备上有这条配额吗？达到上限后旧密钥还能用吗？**

Android 17 起按应用 UID 限制 Keystore 密钥条目数（已与官方文档核对）：非系统应用 target API 37 及以上上限 50,000，其他应用与系统应用上限 200,000；达到上限后新的生成和导入失败，target 37 及以上返回 `ERROR_TOO_MANY_KEYS`（API 37 新增，常量值 18），target 更低返回 `ERROR_INCORRECT_USAGE` 且异常消息含密钥数量限制信息。已存在的密钥仍可继续使用，系统也不会主动删除旧密钥——配额只在创建路径检查。

创建路径与使用路径的分离是关键边界：材料按 Android 17 的 keystore2 源码（`KeystoreSecurityLevel.check_key_counts()`）核对，检查发生在 `generate_key()`、`import_key()`、`import_wrapped_key()` 三条路径进入 KeyMint 之前；现有密钥的 `Cipher.init()`、`Signature.initSign()` 不经过它，所以升级 target SDK 不会仅因配额让旧密钥失效。但配额检查发生在 rebind（用新密钥替换同名 alias）之前，满额时即使用现有 alias 重新生成也会先被拒绝。版本边界：Android 16 及更早不执行这条配额，Android 13 没有此限制；本地 AAOS13 树未包含 `system/security`（keystore2）目录，机制面按材料与官方文档口径转写。配额属于 UID 而非进程或业务模块：同一安装包在不同 Android 用户或工作资料的 UID 分别计数，TEE 与 StrongBox 的密钥计入同一 UID 总数。

**Q16: 哪些设计会让 Keystore alias 数量持续增长？passkey 占不占本应用的配额？**

增长的共同模式是"把一次性或随时间变化的标识写进 alias 且从不回收"：设备绑定 alias 带时间戳或每次绑定的随机 ID、账号退出后不删密钥、每笔订单或每次挑战各建一个密钥、每个文件或表建加密密钥、算法轮换只增不减、QA 测试前缀长期累积。治理方向是给每类用途固定槽位与版本：`auth.<account-slot>.device-sign.v3` 这类命名由稳定业务域、不含敏感信息的账号槽位、用途和版本号构成，`account-slot` 用首次绑定生成的随机标识；数据加密用少量 Keystore 密钥包装 DEK（数据加密密钥），而不是每份数据一个密钥；一次性挑战数据不写入 Keystore；设备换代确认成功后回收旧版本。alias 中不要放账号 ID、手机号、订单号、token 或可猜哈希——除了配额，还会引入数据关联与猜测面。

passkey 要单独判断：普通应用经 Credential Manager 请求 passkey 时，私钥由用户选定的凭据提供方（密码管理器等）保存，不计入本应用的 Keystore alias；只有应用自己调用 Keystore 生成密钥，或自身实现凭据提供方并管理相关密钥时，才把这些条目纳入本 UID 的盘点。排查时看本应用 `AndroidKeyStore` 可见的 alias 和密钥创建调用栈，不能按用户拥有的 passkey 数量推算配额。

**Q17: Keystore 故障只看异常消息为什么分类不准？应按什么组合分类？**

因为异常会多层包装且同一错误码有多种含义。`android.security.KeyStoreException` 从 API 33（Android 13）起提供 `getNumericErrorCode()`、`isTransientFailure()`、`getRetryPolicy()`、`isSystemError()`、`requiresUserAuthentication()`；但 JCA 可能把底层 `KeyStoreException` 包在 `ProviderException`、`InvalidKeyException` 等 cause 链里，`UserNotAuthenticatedException`、`KeyPermanentlyInvalidatedException`、`StrongBoxUnavailableException` 又是独立公开异常。混淆"需要再认证"与"永久失效"的代价是直接的：前者应请求设备凭据或生物认证、不删密钥，后者要完成业务身份验证后替换并回收旧条目——搞反会造成不必要的删密钥和重新登录。

可靠分类要同时看外层异常、cause 链、公开错误码和失败发生的操作阶段（生成、导入、使用还是删除）。分组判断：Android 17 且 target 37+ 的 `ERROR_TOO_MANY_KEYS` 才能确认配额；Android 17 上 target 更低时，`ERROR_INCORRECT_USAGE` 且消息明确提到密钥数量限制只能记"疑似配额"——同一错误码也可表示算法或参数组合错误；`ERROR_KEYSTORE_UNINITIALIZED` 要区分 `KeyStore.load()` 尚未调用与设备未设置锁屏凭据（LSKF）；`isTransientFailure()` 为真才按 `getRetryPolicy()` 指定时机重试并设全局次数上限；`ERROR_PERMISSION_DENIED` 或无配额证据的 `ERROR_INCORRECT_USAGE` 修调用与参数，不靠重试恢复。诊断记录保留外层异常类、cause 链中的 `KeyStoreException`、错误码与清理状态，消息文本只作辅助。

**Q18: 配额耗尽导致登录失败时，为什么"清空 Keystore 后重试"最危险？正确的恢复顺序是什么？**

全量删除可能同时破坏登录、端到端加密、支付、数据库解锁和第三方 SDK 状态，把一次配额故障扩大成账号与数据安全故障。可执行的恢复顺序：先写入持久化的"暂停创建"标记并让同一应用的其他进程读取，阻止失败后继续生成；当前选中的 alias 仍存在且可用时，优先用它完成解密、签名或登录恢复；再按生命周期登记表找出已退出使用的版本、孤立条目、测试前缀和已注销账号的候选集合；账号注销、设备解绑需要服务端参与时先完成业务授权；然后按责任模块小批量删除并记录每批成功、失败和剩余数量；重新盘点确认余量后再恢复创建；大规模盘点交给有执行时限的后台任务，启动路径只读暂停标记和当前 alias，避免主线程长时间阻塞触发 ANR。

另一个常见坑是同名 rebind：配额检查发生在用新密钥替换同名 alias 之前，满额时反复用同一 alias 重试无法解决，必须先删除可回收 alias 释放额度。错误处理的底线是不能临时退回明文 token、共享外部存储或缺少认证约束的软件密钥；是否从 StrongBox 改用 TEE 也必须由预先审查的安全策略决定。卸载应用通常会清理对应 UID 的 Keystore 数据，但"让用户重装"不能设计成线上恢复方案——它同时清除业务数据和认证状态，也不修复 alias 持续增长的代码。

**Q19: 为什么不能在 `containsAlias()` 返回 false 后直接 `generateKey()`？多进程应用如何保证 alias 状态一致？**

因为两个进程可能同时得到 false，然后各自创建，造成重复密钥与配额浪费。创建与删除要用持久状态机保护：`ABSENT → CREATING → ACTIVE → RETIRED → DELETING → DELETED`，由单一密钥管理进程或跨进程文件锁负责转换。关键点：`CREATING` 记录在生成前持久化，进程被杀后重启时同时查询登记表与 Keystore 再决定继续提交还是回收；生成成功后读取密钥 characteristics（算法、安全级别、认证授权属性）确认符合策略再切 `ACTIVE`；同一用途只保留一个 `ACTIVE` 版本，轮换期可额外保留一个最近确认可用的版本；删除先切 `DELETING`，调用 `KeyStore.deleteEntry()` 复查后再写 `DELETED`。

生命周期登记表是应用自维护的数据，不是 Android API：不保存密钥材料，也不能把"数据库有记录"当作密钥存在的证明；每次重要操作都要处理两种不一致——登记表有记录但 Keystore 无条目，以及 Keystore 有条目但登记表无归属。多进程约束收束为：每个 alias 前缀只有一个责任组件能创建、轮换和删除；业务进程只请求"取得或创建某用途密钥"，不自行拼接随机 alias；删除前确认相关进程使用的版本；配额失败后写共享暂停标记；应用版本回滚时登记表结构保留兼容迁移逻辑。第三方 SDK 自行创建密钥时，宿主应要求它声明 alias 前缀、创建频率和删除 API，否则无法安全回收。

**Q20: 应用侧如何盘点自己的 alias 数量？target SDK 从 36 升到 37 前要做什么准备？**

盘点用 `KeyStore.getInstance("AndroidKeyStore")` 加 `aliases()` 遍历，统计总数与受控前缀分布，遥测里只保留总数、已知前缀计数和未匹配数量，不上传完整 alias。两个边界：这个总数是应用视角的估算，不是 keystore2 的内部配额计数，`KeyStore.aliases()`/`size()` 不是配额查询 API；`unknownCount` 只说明有 alias 未匹配已知前缀，不能据此批量删除。全量枚举接近上限的数万条目会产生 Binder 调用、数据库查询和字符串分配开销，不能在主线程、`Application.onCreate()` 首帧前或每次登录时执行——日常由低频后台任务采集趋势，进入预警区间或出现创建失败才做详细盘点，并记录耗时、枚举异常和触发原因。

升级准备的核心是"历史设备不是干净设备"：系统不会自动清理已有密钥，已积累大量条目的非系统应用 UID 升级到 target 37 后，下一次生成或导入会立即失败，发布前应按历史设备的 alias 数量区间分析，不能只测新安装设备。50,000 与 200,000 是系统拒绝新密钥的硬限制，不适合当业务预警阈值——预警线按每日增长速度、清理能力和一次轮换的最大增量设在更低处。容量测试用独立设备或可重置测试用户、固定测试前缀和生成数量硬上限，任务正常结束或中断恢复时都要清理测试密钥。
