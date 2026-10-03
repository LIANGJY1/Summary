# 稳定性治理：线程、协程与 IPC

> 学习资料（文章模式沉淀）。主线包括按 owner 和生命周期证据判断线程与协程泄漏，按传输、服务端和业务结果定位 Binder 故障，以及把 Android 17 Keystore alias 配额当作需要 owner、状态和回滚期的持久资源治理。源文档：android-internals-wiki §20.8《线程与协程泄漏治理》、§20.9《Binder IPC 故障判断与性能诊断》、§20.10《Android 17 Keystore 密钥配额与登录恢复》。本地可核对的机制按 AAOS 13 源码标注版本。工程实践按材料口径转写，不确定处已弱化。Android 17 的 Keystore 配额数值与错误码边界已与官方文档核对。Binder 线程池规模、事务缓冲区与冻结进程的机制层见 `../01-architecture/04-binder.md`。Q 序列即结构，供 Atlas 同源直读。

**Q1: 线程快照里出现大量 WAITING 状态的 `Thread-N` 线程，能据此判定线程泄漏并强杀这些线程吗？**

不能。`WAITING` 是空闲等待的常见状态，`Thread-N` 或 `pool-N-thread-M` 这类名称也只表示归因信息不足。判断泄漏要确认资源是否超过约定生命周期仍然存活，或是否被不应持有它的长生命周期对象强引用。

排查线程归属时先记录三个事实：

1. **创建者：**哪个模块创建了线程或线程池。
2. **任务与所有权：**线程执行哪类工作，由谁取消任务或关闭执行器。
3. **数量预算：**同一个 owner 在一次进程生命周期内允许创建多少线程池和 worker。

不要强杀线程。`Thread.stop()`、`pthread_cancel()` 或反射终止线程时，线程可能正持有 Java monitor、malloc 锁、数据库事务或库内状态，强停可能造成数据损坏或死锁。工程上应使用统一 `ThreadFactory`，按“模块-用途-序号”命名（例如 `img-dec-3`），并记录创建入口。

线程名还受 Native 标识长度限制：

1. Linux 内核 comm 名称缓冲区上限为 16 字节，其中包括结尾 NUL，因此最多容纳 15 个字节。
2. AAOS 13 的 `pthread_setname_np()` 遇到超长名称返回 `ERANGE`，源码位置为 `bionic/libc/bionic/pthread_setname_np.cpp`。
3. Java `Thread.name` 可以比 `/proc/self/task/<tid>/comm` 长。需要 Native 侧识别时，将标识放在前 15 个 ASCII 字节内。
4. 线程名不得包含账号、URL 等用户数据。

**Q2: 按 Android 13 源码，Java 线程退出时 ART 会清空 `Thread` 的 `target` 与 `ThreadLocal` 字段吗？已终止的 `Thread` 被静态集合持有时为什么会滞留对象？**

不会。AAOS 13 的 `Thread.java` 私有 `exit()` 会清空 `target`、`threadLocals`、`inheritableThreadLocals` 和 `blocker` 等字段，但 `getThreadGroup()` 附近的 Android 注释指出，Android runtime 在线程退出时不会调用这个 Java `exit()`。ART 的 `Thread::Destroy()` 在 Native 路径处理未捕获异常、移除 Java peer 并唤醒 `join()` 等待者，不会执行这段 Java 字段清理。

要区分仍运行的线程与已经终止的 Thread 对象：

1. **仍运行的线程：**线程本身是 GC root。它的栈和线程局部引用随之存活，滞留对象要从当前任务持有的引用链判断。
2. **已终止但仍被引用的对象：**线程退出后不再对应 Linux task，但若静态集合或线程注册表仍强引用 Thread 对象，`target`、`ThreadLocalMap` 或线程子类字段仍可能保住关联对象图。
3. **回收条件：**线程终止且外部强引用释放后，Thread 对象及其保留的引用链才可被回收。
4. **诊断方法：**用 heap dominator 分析确认具体强引用链，不从线程状态推断对象是否泄漏。

版本边界：字段清理和 ART 退出行为按 AAOS 13（Android 13）源码核对，android-internals-wiki 中的 Android 17 源码核对结论一致。AAOS 13 文件为 `libcore/ojluni/src/main/java/java/lang/Thread.java`。

**Q3: `/proc/self/status` 的 `Threads`、`/proc/self/task`、Java `Thread` 快照与组件指标各能回答什么？`ThreadGroup.activeCount()` 能当硬限流依据吗？**

四类观测的统计对象不同，不能互相替代。`ThreadGroup.activeCount()` 只能提供估算值，不适合作为硬限流依据，因为计数与枚举之间线程可能启动或退出，`enumerate(Thread[])` 在数组过小时还会静默截断。

各观测面的含义如下：

1. **`/proc/self/status` 的 `Threads`：**当前进程中的 Linux task 总数，不提供线程来源和状态。
2. **`/proc/self/task/<tid>`：**单个 task 的 TID、comm 短名称和调度状态，但看不到已退出且尚未 `join()` 的 pthread。
3. **Java Thread 快照：**只覆盖有 Java peer 的线程并提供调用栈。各线程栈采样时刻不同，因此不是同一瞬间的一致快照。
4. **线程池、协程和 SDK 指标：**能提供 owner、队列等组件语义，但只覆盖已接入监控的组件。

`activeCount()` 统计 Java 线程树，不等于进程 Linux task 数。`Thread.getAllStackTraces()` 也不适合常态计数，因为每次调用都会创建 Map 和每线程栈数组，秒级轮询开销较高。应先用低成本计数发现增长，再按需生成诊断快照。

将证据对齐时还要处理标识差异与采样竞态：

1. Java `threadId()`（API 36 起）和 `getId()` 是 Java 生命周期 ID，不是 Linux TID。要关联 Perfetto、tombstone 和 `/proc`，应在线程内部调用 `Process.myTid()`，或在可靠的线程创建事件中记录 TID。
2. TID 在线程退出后可能复用，跨时间关联必须带采样时间。
3. 枚举 `/proc/self/task` 时，读取期间消失的 TID 是正常竞态。读取失败应记录为“缺测”，不能记成零条线程。

**Q4: raw joinable pthread 退出后，为什么 `/proc` 和线程快照都看不到它，但 Native 内存仍可能增长？监控要覆盖哪些事件？**

joinable pthread 的入口函数返回或调用 `pthread_exit()` 后，Linux task 就消失了，但栈映射等资源要等另一线程调用 `pthread_join()` 才回收。若线程仍存活，也可调用 `pthread_detach()` 释放 join 责任。创建时设为 detached 的线程会在退出时自行回收。因此活线程数回落而 Native RSS 或虚拟地址空间持续增长时，应检查是否有退出后未回收的 joinable 线程资源。

这类资源无法从存活线程列表直接看到。监控应记录完整生命周期事件：

1. 创建线程。
2. 入口函数返回或调用 `pthread_exit()`。
3. 调用 `pthread_join()`。
4. 调用 `pthread_detach()`。
5. 用创建序号作为事件 ID。`pthread_t` 和 TID 都可能复用，不能长期作为唯一主键。

自有 C/C++ 代码可用 RAII 或统一包装层落实资源协议：

1. 无需返回结果的 worker 在创建时设为 detached。
2. 需要返回结果的 joinable worker 由唯一 owner 调用 `join()`。
3. 创建失败时，不把未初始化的 `pthread_t` 写入注册表。

普通 Java `Thread` 是例外边界：AAOS 13 中 ART 的 `Thread::CreateNativeThread()` 使用 `PTHREAD_CREATE_DETACHED` 属性，因此 Java 线程退出后不需要业务代码 `join()` 来回收该 native thread。这里讨论的盲区针对直接调用 `pthread_create()` 且未设 detached 属性的 raw 线程。

**Q5: “每条线程默认占 1 MiB 内存”和“线程与 FD 同步增长说明每条线程占一个 FD”这两个推断错在哪？**

两种推断都把相关指标误当成了固定因果。线程栈预留的虚拟地址不等于常驻物理内存，线程本身也不天然持有一个 FD。

先区分线程栈的地址空间与实际内存：

1. **Native pthread 栈：**AAOS 13 bionic 的默认栈常量是 1 MiB 减去独立信号栈的可用部分，定义在 `bionic/libc/bionic/pthread_internal.h` 的 `PTHREAD_STACK_SIZE_DEFAULT`。
2. **栈映射附属区：**主映射还包含 guard page、static TLS 等，并以 `MAP_NORESERVE` 创建。建立映射时不要求内核预留等量物理页。
3. **Java Thread 栈：**ART 的 `FixStackSize()` 先读取 `-Xss` 默认值，再为 Dalvik 兼容增加 1 MiB 并按页对齐，源码位置为 `art/runtime/thread.cc`。
4. **指标口径：**虚拟地址空间是进程可寻址范围，RSS 只统计当前驻留的物理页。因此不能用“线程数 × 1 MiB”推算任一指标。估算时应在相同 ABI、页大小、设备内存和负载下比较 `/proc/self/maps`、`smaps_rollup`、RSS 与 task 数的共同变化。

线程和 FD 同时增长也不能证明每条线程占一个 FD：

1. `/proc/self/task/<tid>` 是 procfs 视图，不代表进程为每条线程常驻打开一个文件描述符。
2. 两者同步增长通常意味着同一模块同时创建 worker 和 socket、pipe、eventfd 或文件。要按 owner 和时间线建立关联，不能按数量直接推导。
3. `pthread_create()` 抛出 `OutOfMemoryError` 只说明创建路径失败。失败可能来自 ART 分配、bionic 栈或 TLS 映射，也可能来自内核 `clone`。
4. 即使 Java heap 仍有余量，也不能排除系统资源耗尽。应保留原始错误、task 数、`VmSize`、RSS 与进程角色。

**Q6: `activeCount` 小于 `poolSize`、或大量一次性延迟任务被取消后内存仍不降，能得出 worker 泄漏的结论吗？应分别怎么处理？**

不能。这两种现象分别涉及线程池 worker 生命周期和延迟任务队列，不能用同一种“线程泄漏”解释。

判断线程池是否异常时应组合观察：

1. **Worker 数：**`getActiveCount()` 是正在执行任务的 worker 估计数。空闲 worker 等待任务是正常状态，`activeCount < poolSize` 不证明泄漏。
2. **池状态：**一起记录 `corePoolSize`、`maximumPoolSize`、`poolSize`、`largestPoolSize`、队列长度、最老任务等待时间、拒绝次数、pool 实例数与 `isShutdown` 状态。
3. **队列压力：**固定线程池常用无界队列，因此 worker 数稳定时任务和内存仍可能持续堆积。cached pool 的空闲 worker 会在 keep-alive 到期后回落。
4. **重复创建：**若线程名前缀中的 pool 序号持续增长，旧 pool 的 worker 又长期存活，通常要查组件重复初始化或遗漏关闭。隔离明确且能独立关闭的模块可拥有专用 pool，但必须有并发预算和明确 owner。

`ScheduledThreadPoolExecutor` 中取消的延迟任务是另一个问题。一次性延迟任务取消后，默认仍可能留在 delay queue 中，直到原定到期时间。大量长延迟任务会因此滞留对象。

1. 业务允许时，启用 `setRemoveOnCancelPolicy(true)`，让取消的任务从队列移除。
2. owner 保存代表计划任务的 `ScheduledFuture`，在生命周期结束时取消任务，再关闭专用 executor。
3. 该 executor 基于固定数量的 core worker 和无界 delay queue。调大 `maximumPoolSize` 通常不起作用。

因此，“worker 没退出”应查线程池 owner 和关闭路径，“取消任务仍在队列”应查取消策略和队列内容。

**Q7: 排查“协程数量持续上涨”时，如何区分协程泄漏与线程泄漏？`GlobalScope` 的边界是什么？**

同时观察活跃 `Job` 或协程队列数，以及 Linux task 数。协程可以在挂起时不占用专属线程，所以 Job 增长而线程平稳，通常是 scope 或任务生命周期问题。两者同步增长时，再调查自建 dispatcher、executor 或 Native 库。

诊断时比较两类资源：

1. **Job 持续增长、task 平稳：**检查长期未完成的 Job、捕获对象和不响应取消的调用。协程挂起时不占专属线程，`Dispatchers.Default` 和 `Dispatchers.IO` 会复用调度 worker。一万个挂起协程不等于一万条 Linux task，协程恢复时也可能切换到另一个 worker。
2. **Job 与 task 同步增长：**检查是否重复创建线程、dispatcher、executor，或由 Native 库创建线程。

`GlobalScope` 是带 `DelicateCoroutinesApi` 标记的进程级 scope。它启动的任务没有业务 owner 可统一等待或取消的父 `Job`，所以任务及其捕获的 Activity、View 可能超过页面或会话生命周期。但它不会自动为每个协程创建线程，主要风险是任务生命周期失控，而不一定是线程泄漏。

接近线程资源泄漏的协程用法包括：

1. 重复创建 `newSingleThreadContext()` 后不调用 `close()`。
2. 创建 `ExecutorService` 并转换为 dispatcher 后不关闭 executor。
3. 每次进入页面都创建独立 dispatcher，再把它存入长生命周期对象。

长期组件应持有显式创建、显式关闭的 root scope，例如 `SupervisorJob`、dispatcher 和 `AutoCloseable`，并由组件结束回调关闭。一次性请求中的并发子任务应使用 `coroutineScope` 或 `supervisorScope`，不要为每个请求另建 root scope。以上协程语义按 kotlinx.coroutines 1.11.0 说明，库版本独立于 Android 版本。

**Q8: 协程的 `cancel()` 为什么不保证立即停止？`Dispatchers.limitedParallelism(1)` 是跨挂起点的互斥锁吗？**

`cancel()` 是协作式取消，只设置取消状态。协程代码执行取消检查或进入支持取消的挂起点后才会停止。`limitedParallelism(1)` 也不是跨挂起点互斥锁，它只限制同一时刻在该 dispatcher 上执行的代码段数量。

取消能否及时生效，取决于执行路径：

1. **支持取消的挂起操作：**`delay`、多数 Channel/Flow 操作和支持取消的挂起 API 会检查取消状态。
2. **不检查取消的工作：**没有挂起点的 CPU 循环、吞掉 `CancellationException` 的捕获逻辑（例如用 `runCatching` 捕获全部 `Throwable`）和不可中断阻塞 I/O，在 `cancel()` 后仍可能继续运行。
3. **可中断的 Java 阻塞调用：**使用 `runInterruptible` 包装支持线程中断的 API。
4. **socket 或 stream 等资源：**为资源提供关闭底层对象的取消路径。`withTimeout` 只能取消协程本身，不能自动中断不响应取消的阻塞调用。

`limitedParallelism(1)` 只约束一个 dispatcher 视图上同一时刻执行的代码段。一个协程挂起后，另一个协程可以进入同一段逻辑，因此跨挂起点限制并发应使用 `Semaphore` 或资源池。Semaphore permit 会跨 `withContext` 和挂起点持有。

资源关闭和线程优先级也要与 owner 对齐：

1. `limitedParallelism()` 创建复用原 dispatcher 的视图，不需要单独 `close()`。
2. `ExecutorService.asCoroutineDispatcher()` 创建独立 dispatcher，owner 结束时必须关闭。
3. 协程没有独立的 Linux 调度优先级，内核调度的是线程。在可复用的线程池协程中调用 `Process.setThreadPriority()` 会改变 worker 属性并影响后续无关任务。需要稳定线程属性的组件应使用有界专用 executor，并在 `ThreadFactory` 中设置线程属性。

**Q9: 一次同步 Binder 调用失败时，为什么不能凭一个 Java 异常断言“服务端没有执行”？排查时应分别记录哪三个结果？**

不能只凭客户端异常断定服务端没有执行。同步调用可能在请求序列化、驱动投递、服务端执行或回复序列化与返回阶段失败。回复过大时服务端可能已经执行完，只是客户端没有收到结果。官方因此要求把 `TransactionTooLargeException` 当作部分失败处理。

排查时记录三个彼此独立的结果：

1. **传输：**请求是否到达服务端，回复是否返回。
2. **执行：**服务端是否开始并完成处理。
3. **提交：**业务副作用是否已经提交。

记录后按操作语义选择恢复方式：

1. **纯读取或可重复查询：**重新获取代理对象后，按既定上限重试。
2. **带稳定 request ID 的幂等写：**先查询服务端状态，确认未提交后再重放。
3. **扣款或消费一次性令牌等非幂等操作：**结果未知时只查询提交状态，不能盲目重放。
4. **权限或参数错误：**修正 `SecurityException` 对应的权限或身份，以及参数错误。退避重试不会解决这类问题。
5. **请求序列化失败：**本地运行时异常通常意味着请求未交给服务端，但仍需确认异常发生位置。
6. **`oneway` 调用：**本地返回不代表目标已经处理。要保证投递结果，需另行设计确认和序号协议。

**Q10: 调用 framework 管理类需要到处 `catch (RemoteException)` 吗？服务端 `onTransact()` 抛出的异常如何回到客户端？**

不需要，也不一定可行。`RemoteException` 是受检异常，但 `PackageManager`、`ActivityManager` 等 framework 管理类通常会在内部捕获它，再通过 `rethrowFromSystemServer()` 转换成该 API 约定的运行时异常。只有自有 AIDL 代理对象或签名明确声明 `RemoteException` 的接口，才应在调用处处理这类受检异常。

同步 Binder 调用的异常路径取决于异常类型和调用模式：

1. **可编码的同步异常：**若 Parcel 支持编码，服务端异常会写入回复并在客户端重放。AAOS 13 `Parcel.java` 的 `getExceptionCode()` 支持 `SecurityException`、`BadParcelableException`、`IllegalArgumentException`、`NullPointerException`、`IllegalStateException`、`NetworkOnMainThreadException`、`UnsupportedOperationException`、`ServiceSpecificException` 和 BootClassLoader 中的 Parcelable 异常。
2. **权限与业务错误：**这些异常通常说明请求已到达服务端。`SecurityException` 应修复权限或调用身份，不应自动重试。`ServiceSpecificException` 携带服务自定义 `errorCode`，接口契约应说明每个错误码能否重试。
3. **`oneway`：**没有同步回复通道，服务端异常无法沿调用路径返回。需要业务确认时，应另行设计回调和超时。
4. **客户端解包失败：**`BadParcelableException`、找不到 Parcelable 类加载器或 Stable AIDL 版本不兼容不等同于远端死亡。日志要记录接口版本、transaction code 和错误发生方向。

**Q11: 驱动返回 `FAILED_TRANSACTION` 时 Java 层如何选异常？小 Parcel 调用失败却抛 `DeadObjectException` 合理吗？**

合理。Java Binder JNI 根据 `FAILED_TRANSACTION` 和请求 Parcel 大小启发式选择异常，因此异常名称不是底层失败原因的精确说明。按 AAOS 13 `frameworks/base/core/jni/android_util_Binder.cpp`，请求 Parcel 超过 200 KiB 时抛 `TransactionTooLargeException`，更小的失败优先映射为 `DeadObjectException`。

判断时要补齐缓冲区、回复和设备上限等证据：

1. **共享缓冲区：**约 1 MiB 的接收映射（精确为 1 MiB 减去两倍页大小）由本进程所有在途事务共享。多笔中等大小的并发请求和回复可能共同耗尽它，因此单笔小 Parcel 失败不一定代表该笔过大。
2. **请求与回复：**回复过大时，客户端日志中的请求大小可能很小，服务端也可能已经执行完。
3. **接口预算：**公开建议值为 `IBinder.MAX_IPC_SIZE` 的 64 KiB。AAOS 13 中它是公开常量，`getSuggestedMaxIpcSizeBytes()` 在本地树中仍是隐藏方法，并从 API 34 起公开。该值是建议上界，不是驱动硬限制。自有接口可设置更低预算。
4. **测试解释：**回归测试可检查 `Parcel.dataSize()`，但它不包括并发压力、回复和驱动元数据开销，不能证明线上调用一定成功。

格式错误的 transaction、已关闭的 FD、远端在传输途中死亡和缓冲区空间不足都可能导致 `FAILED_TRANSACTION`。排查时还要结合请求与回复两端日志、并发量和进程状态。

**Q12: `linkToDeath()` 的注册与回调之间存在哪些竞态？死亡回调里应该做什么、不该做什么？**

`linkToDeath()` 可能在目标已经死亡时直接抛 `RemoteException`。`isBinderAlive()` 只反映检查那一刻的状态，返回后目标仍可能立即退出。收到死亡回调后，旧代理对象不可继续使用，重连后必须取得新代理并重新协商状态。

死亡回调应快速标记失效并把恢复工作交给 owner：

1. 递增连接代次（epoch，每次重连变化），并把当前代理标记为不可用。
2. 清理依附于旧进程的会话、回调注册和缓存句柄。
3. 将重新绑定或获取服务的工作提交给应用统一管理的 executor。
4. 不在回调线程做磁盘或网络操作，也不等待新服务。
5. 按操作语义恢复：幂等写先查状态再重放，非幂等且结果未知时只查询提交状态。对 `TransactionTooLargeException` 原样重试无效。

连接代次也用于避免重复计数。一次远端死亡可能让多条并发调用同时抛 `DeadObjectException`，死亡率应按代次或一次死亡通知去重。

**Q13: 给自有 AIDL 客户端包装层计时，耗时包含哪些阶段？为什么普通应用不能指望全局 Binder 拦截点？**

客户端包装层测得的是端到端等待时间，通常包含参数序列化、驱动发送、服务端排队、服务端执行、回复传输和客户端解包。它适合衡量调用方体验，不能直接称为 Binder 传输时间，因为服务端排队和执行可能占主要部分。

记录时应拆开口径并保护业务路径：

1. `oneway` 的外层计时只覆盖序列化和本地提交，不表示远端已执行。
2. 同进程 Binder 可能直接调用 Stub，应与跨进程样本分开统计。
3. 在自有 AIDL 客户端和服务端包装层记录逻辑接口、线程、耗时、是否主线程和失败分类。
4. 标签使用编译期确定的枚举，不包含 URI、用户 ID 或异常消息。
5. recorder 使用固定容量缓冲。缓冲写满时丢弃并计数，监控故障不得遮蔽业务结果。
6. 分位数使用低比例均匀采样。若只上传慢样本，得到的只是慢调用内部的分布。
7. 服务端另记 `server_enter` 和 `server_exit`。两端关联使用业务协议中的 request ID，不依赖可能有时钟偏差的日志时间戳。

普通应用没有可依赖的全局 Binder 拦截点。`Binder.ProxyTransactListener` 和 `BinderInternal.Observer` 属于隐藏或平台内部接口，Native 和 Rust Binder 也不一定经过同一个 Java 入口。反射或 Native Hook 还会改变被测路径的时序。

**Q14: ANR trace 显示主线程停在 `BinderProxy.transactNative`，接下来怎样补全证据链？**

这条客户端堆栈只能证明主线程正在等待一次 IPC，不能说明服务端正在做什么。应使用 Perfetto 对齐时间线，采集 `binder_driver`、`sched` 和相关 atrace 类别，沿 flow 找到服务端线程，确认它在排队、等待锁、执行 I/O 还是发起下游 Binder，再检查回复返回后客户端何时恢复。

诊断需关联客户端、服务端和业务提交三个层面：

1. **客户端：**记录调用接口、线程、request ID、开始等待与返回时间，以及失败类别。
2. **服务端：**检查 request ID 是否到达、副作用是否完成、`onTransact()` 与业务函数耗时、Binder 线程状态、锁持有情况和进程崩溃、冻结或重启时间。
3. **嵌套调用：**检查服务端是否等待锁、磁盘、硬件或下游 Binder。A 调 B、B 又同步回调 A 可能形成跨进程循环等待。
4. **`oneway` 调用：**还要检查队列策略、丢弃或合并数量和消费序号缺口。
5. **工具限制：**`dumpsys binder <pid>` 并非所有量产设备都提供。`/sys/kernel/debug/binder` 或 binderfs 统计节点受构建类型和 SELinux 限制。`FAILED BINDER TRANSACTION`、`oneway spamming` 等日志标签随构建变化，只能作为辅助证据。

修复方向取决于根因。服务端 `onTransact()` 应快速校验并复制参数，再把耗时工作交给有容量上限的业务 executor，以便尽快释放 Binder 线程。不要持有应用锁发起外部同步 Binder 调用。确实无法避免时，应约定统一的跨进程加锁顺序并设计超时。

**Q15: Android 17 的 Keystore 密钥配额规则是什么？Android 13 设备上有这条配额吗？达到上限后旧密钥还能用吗？**

Android 17 起按应用 UID 限制 Keystore 密钥条目数。达到限制后新的密钥生成和导入会失败，但已存在的密钥仍可使用，系统也不会主动删除旧密钥。配额只在密钥创建路径检查。

配额与版本边界如下（Android 17 keystore2 源码 `KeystoreSecurityLevel.check_key_counts()` 与官方文档已核对）：

1. **target API 37 及以上的非系统应用：**上限为 50,000 个条目。
2. **其他应用和系统应用：**上限为 200,000 个条目。
3. **target API 37 及以上达到上限：**返回 API 37 新增的 `ERROR_TOO_MANY_KEYS`，常量值为 18。
4. **较低 target API 达到上限：**返回 `ERROR_INCORRECT_USAGE`，异常消息包含密钥数量限制信息。
5. **检查位置：**`generate_key()`、`import_key()`、`import_wrapped_key()` 在进入 KeyMint 前检查条目数。现有密钥的 `Cipher.init()` 和 `Signature.initSign()` 不经过该配额检查。
6. **旧密钥使用：**升级 target SDK 不会仅因配额使旧密钥失效。不过，同名 alias 的 rebind 也要先创建新密钥，满额时会在替换前被拒绝。
7. **UID 范围：**配额属于 UID，不属于进程或业务模块。同一安装包在不同 Android 用户或工作资料下的 UID 分别计数，TEE 和 StrongBox 密钥计入同一 UID 总数。
8. **Android 13：**Android 16 及更早版本不执行这条配额，因此 Android 13 设备没有此限制。AAOS 13 本地树未包含 `system/security`（keystore2）目录，机制细节按 Android 17 材料及官方文档核对。

**Q16: 哪些设计会让 Keystore alias 数量持续增长？passkey 是否计入本应用的配额？**

alias 持续增长通常是因为把一次性或不断变化的标识写进 alias，却没有定义回收周期。治理时应让每类用途占用固定槽位和少量版本，并区分应用自己创建的密钥与凭据提供方管理的 passkey。

常见增长来源及控制方法如下：

1. **设备绑定：**在 alias 中加入时间戳，或每次绑定生成新随机 ID。改为按稳定业务域、用途和版本组织固定槽位，例如 `auth.<account-slot>.device-sign.v3`。`account-slot` 使用首次绑定时生成的随机标识，不含敏感信息。
2. **账号和设备生命周期：**账号退出、注销或设备解绑后不回收密钥。完成业务授权后，按生命周期回收不再使用的版本。
3. **一次性数据：**每笔订单或每次挑战都创建密钥。一次性挑战状态不应写入 Keystore。
4. **数据加密：**每个文件或数据库表都创建 Keystore 密钥。可改用少量 Keystore 密钥包装 DEK（数据加密密钥）。
5. **算法轮换与测试：**轮换只新增不删除，或 QA 测试前缀长期累积。确认新版本可用后回收旧版本，并清理测试 alias。
6. **敏感信息：**alias 不应包含账号 ID、手机号、订单号、token 或可猜哈希。除了占用配额，这些内容还会带来数据关联与猜测风险。

passkey 是否计入本应用配额，取决于私钥由谁管理：

1. **普通依赖方应用：**通过 Credential Manager 请求 passkey 时，私钥由用户选择的凭据提供方（例如密码管理器）保存，不计入本应用 Keystore alias。
2. **应用自建凭据提供方：**应用自己调用 Keystore 生成密钥，或作为凭据提供方管理私钥时，这些条目才计入该 UID 的盘点。
3. **核对方法：**查看本应用 `AndroidKeyStore` 可见 alias 和密钥创建调用栈，不按用户的 passkey 数量推算配额。

**Q17: Keystore 故障只看异常消息为什么分类不准？应按什么组合分类？**

异常消息不足以分类 Keystore 故障，因为异常可能多层包装，同一错误码也可能对应多种原因。应同时查看外层异常、cause 链、公开错误码和失败阶段（生成、导入、使用或删除）。

判断时要注意这些 API 和错误类别：

1. **结构化错误信息：**Android 13（API 33）起，`android.security.KeyStoreException` 提供 `getNumericErrorCode()`、`isTransientFailure()`、`getRetryPolicy()`、`isSystemError()` 和 `requiresUserAuthentication()`。
2. **包装异常：**JCA 可能将底层 `KeyStoreException` 包在 `ProviderException` 或 `InvalidKeyException` 的 cause 链中。
3. **独立公开异常：**`UserNotAuthenticatedException`、`KeyPermanentlyInvalidatedException` 和 `StrongBoxUnavailableException` 都需要分别识别。
4. **认证失败与永久失效：**前者应请求设备凭据或生物认证，不应删除密钥。后者应先完成业务身份验证，再替换并回收旧条目。把两者弄反可能导致不必要的删密钥和重新登录。
5. **配额错误：**Android 17 且 target API 37 及以上时，`ERROR_TOO_MANY_KEYS` 可确认配额耗尽。Android 17 上较低 target 的 `ERROR_INCORRECT_USAGE`，只有消息明确提到密钥数量限制时才记为疑似配额，因为该错误码也可能表示算法或参数组合错误。
6. **Keystore 未初始化：**对 `ERROR_KEYSTORE_UNINITIALIZED`，要区分尚未调用 `KeyStore.load()` 与设备未设置锁屏凭据（LSKF）。
7. **瞬态失败：**只有 `isTransientFailure()` 为真时，才按 `getRetryPolicy()` 指定的时机重试，并设置全局次数上限。
8. **权限或参数失败：**`ERROR_PERMISSION_DENIED`，以及没有配额证据的 `ERROR_INCORRECT_USAGE`，应检查调用身份、权限和参数，不能靠重试恢复。

诊断记录保留外层异常类、cause 链中的 `KeyStoreException`、错误码和清理状态。消息文本只能作为辅助证据。

**Q18: 配额耗尽导致登录失败时，为什么“清空 Keystore 后重试”最危险？正确的恢复顺序是什么？**

清空全部 Keystore 可能同时破坏登录、端到端加密、支付、数据库解锁和第三方 SDK 状态，把配额故障扩大成账号和数据安全故障。恢复必须先停止新建，再验证当前密钥，之后按责任边界小批量回收可确认无用的条目。

建议按以下顺序恢复：

1. 写入持久化的“暂停创建”标记，并让同一应用其他进程读取它，阻止故障期间继续生成密钥。
2. 若当前选中的 alias 仍存在且可用，优先用它完成解密、签名或登录恢复。
3. 按生命周期登记表找候选回收项，包括已退出使用的版本、孤立条目、测试前缀和已注销账号的密钥。
4. 若账号注销或设备解绑需要服务端参与，先完成业务授权。
5. 由对应责任模块分批删除，记录每批成功数、失败数和剩余数量。
6. 重新盘点并确认有余量后，再恢复创建。
7. 大规模盘点放在有执行时限的后台任务。启动路径只读取暂停标记和当前 alias，避免主线程长时间阻塞并触发 ANR。

同名 alias 的 rebind 不能靠原样重试解决，因为配额检查发生在创建替换密钥之前。满额时必须先删除可回收 alias 释放额度。不能临时退回明文 token、共享外部存储或缺少认证约束的软件密钥。是否从 StrongBox 改用 TEE 必须由预先审查的安全策略决定。卸载应用通常会清理对应 UID 的 Keystore 数据，但重装同时会清除业务数据和认证状态，也不会修复 alias 持续增长的代码，不能作为线上恢复方案。

**Q19: 为什么不能在 `containsAlias()` 返回 false 后直接 `generateKey()`？多进程应用如何保证 alias 状态一致？**

不能把 `containsAlias()` 的检查与 `generateKey()` 当成一个原子操作。两个进程可能同时读到不存在，再各自创建密钥，造成重复密钥和配额浪费。创建、轮换和删除应由单一 owner 在持久状态机中串行管理。

alias 生命周期可用以下状态迁移表示：

1. `ABSENT → CREATING`：在生成前先持久化 `CREATING`。
2. `CREATING → ACTIVE`：生成成功后读取 characteristics，确认算法、安全级别和认证授权属性符合策略，再登记为 `ACTIVE`。
3. `ACTIVE → RETIRED`：新版本确认可用后，将旧版本退役。同一用途只保留一个 `ACTIVE` 版本，轮换期最多额外保留一个最近确认可用的版本。
4. `RETIRED → DELETING → DELETED`：先登记 `DELETING`，调用 `KeyStore.deleteEntry()`，复查条目已删除后再登记 `DELETED`。
5. **中断恢复：**进程在生成或删除中被杀后，重启时同时查询登记表和 Keystore，再决定继续提交还是回收。

生命周期登记表是应用自维护的数据，不是 Android API。它不保存密钥材料，也不能把数据库有记录当成密钥存在的证明。每次重要操作都要处理两类不一致：登记表有记录但 Keystore 没有条目，以及 Keystore 有条目但登记表没有归属。

多进程与 SDK 的责任边界如下：

1. 每个 alias 前缀只有一个责任组件能创建、轮换和删除。
2. 业务进程只请求“取得或创建某用途密钥”，不自行拼接随机 alias。
3. 删除前确认相关进程使用的密钥版本。配额失败后写入共享暂停标记。
4. 登记表结构需保留应用版本回滚时的兼容迁移逻辑。
5. 第三方 SDK 自行创建密钥时，宿主应要求其声明 alias 前缀、创建频率和删除 API，否则无法安全回收。

**Q20: 应用侧如何盘点自己的 alias 数量？target SDK 从 36 升到 37 前要做什么准备？**

应用可用 `KeyStore.getInstance("AndroidKeyStore")` 和 `aliases()` 枚举自己可见的 alias，再按受控前缀盘点。该数量只是应用视角的估算，`KeyStore.aliases()` 和 `size()` 都不是 keystore2 配额查询 API。

盘点和遥测应遵守以下边界：

1. 遥测只保留 alias 总数、已知前缀计数和未匹配数量，不上传完整 alias。
2. `unknownCount` 只表示有 alias 未匹配已知前缀，不能据此批量删除。
3. 接近上限的数万条目全量枚举会产生 Binder 调用、数据库查询和字符串分配开销。
4. 不要在主线程、`Application.onCreate()` 首帧前或每次登录时执行全量枚举。日常用低频后台任务采集趋势，进入预警区间或出现创建失败后再详细盘点，并记录耗时、枚举异常和触发原因。

从 target API 36 升到 37 前要考虑存量设备，而不只是新安装：

1. 系统不会自动清理历史密钥。已积累大量条目的非系统应用 UID 升级到 target 37 后，下一次生成或导入就可能失败。
2. 发布前按历史设备 alias 数量区间评估，不要只测新装设备。
3. 50,000 和 200,000 是拒绝新密钥的硬上限，不适合作为业务预警阈值。预警线应根据每日增长速度、清理能力和一次轮换的最大增量设得更低。
4. 容量测试使用独立设备或可重置测试用户，固定测试前缀并限制生成数量。任务正常结束或中断恢复时都要清理测试密钥。

**Q21: 线程停在 `futex_wait` 就应该去找 Java 持锁者吗？Java 锁等待在 Android 17 上有几条不同路径？**

不应该。Java 锁等待至少有三条路径——ART Monitor（`synchronized`）、原生同步原语（`pthread_mutex`、条件变量、`LockSupport.park()`）与同步 Binder 回复等待。futex 只是让用户态原子状态与内核等待队列协作的底层机制，从 `blocked_function` 里的 `futex_*` 无法还原锁对象与持锁者，必须结合调用栈与轨迹证据。

1. **ART Monitor：**对象头 32 位 LockWord 记录轻量锁状态（`kUnlocked`、`kThinLocked`、`kFatLocked`、`kHashCode`、`kForwardingAddress`）。出现真实竞争、在对象上调用 `wait()` 或对象带身份哈希时，会膨胀为重量级 Monitor。源码常量 `kLongWaitMs`（release 构建 100ms）只是 ART 长竞争告警阈值，不能推出“小于 100ms 的竞争不重要”。60Hz 一帧约 16.7ms，几毫秒的主线程锁等待已可能掉帧。
2. **原生 futex：**无竞争时只在用户态做原子操作，竞争时才进内核等待。只有以 `PTHREAD_PRIO_INHERIT` 属性初始化的互斥锁才走 PI-futex/rt-mutex 优先级继承。看到 `futex_wait` 只能证明线程在某个 futex 等待点休眠。
3. **Binder：**同步调用的调用端等待服务端回复，不是在等某把“Binder Java 锁”。Binder 有自己独立的事务优先级传播（`binder_transaction_priority()`），与 pthread PI 是两套机制。服务端变慢的常见根因是内部锁、I/O 或嵌套 IPC。
4. **诊断入口：**Perfetto 标准库 `android.monitor_contention` 给出等待者和持锁者线程、双方方法、锁名与时长。它只覆盖 ART Java Monitor，不覆盖 `ReentrantLock` 和原生锁。futex 候选应从 `thread_state.blocked_function` 回到原生调用栈确认。
