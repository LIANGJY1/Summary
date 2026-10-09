# ContentProvider 服务链路

> 学习资料（文章模式沉淀）。边界：本文回答 Provider 初始化时序、CursorWindow 的跨进程传输、框架等待超时与批量操作的事务和线程池边界。四组件入门见 `01-four-components.md`。依据：AOSP Android 17 源码与 android-internals-wiki §1.15。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] 进程启动时 ContentProvider 为什么早于 Application.onCreate() 初始化？initOrder 能保证什么？**

应用进程绑定时，Android 会先创建并附加 `Application`，再安装、发布清单中的 ContentProvider，最后调用 `Application.onCreate()`。因此 Provider 的 `onCreate()` 会阻塞同进程后续 Provider 安装和 `Application.onCreate()`。若它做磁盘扫描、数据库迁移或同步网络等待，远程调用方也可能同时等待该进程发布 Provider。

AOSP `ActivityThread.handleBindApplication()` 的关键顺序如下：

1. `makeApplicationInner()` 创建应用对象，并通过 `Application.attach()` 执行 `attachBaseContext()`。
2. `installContentProviders()` 逐个调用 `installProvider()`，进而调用 `ContentProvider.attachInfo()` 和 Provider 的 `onCreate()`。
3. `publishContentProviders()` 将已创建的 Provider 发布给 `system_server`。
4. `Instrumentation.callApplicationOnCreate()` 调用 `Application.onCreate()`。

`initOrder` 只为同一进程中数值不同的 Provider 提供降序安装顺序。AOSP 比较器对相同值返回相等，因此同值 Provider 的相对顺序没有保证。

Provider 发布和应用初始化也不是一个原子阶段：

1. Provider 的 `onCreate()` 在应用主线程执行。
2. Provider 发布后，远程 Binder 请求可能与提供方主线程执行 `Application.onCreate()` 并发。
3. 两条路径若争用同一数据库锁或 I/O，首次 `query()` 仍可能被间接拖慢。
4. 有初始化依赖时应显式声明依赖或由应用统一组织，不能依赖多个库碰巧设置了不同的 `initOrder`。

**Q2: [learning] 跨进程查询如何返回 Cursor？为什么 moveToNext() 仍可能走 Binder？大结果集该怎样分页？**

跨进程 Cursor 通过 `CursorWindow` 分批提供行数据。Binder 传递窗口文件描述符和控制元数据，而不是一次性传输整个结果集。客户端访问当前有效窗口中的列值通常不需要远程取数，但换窗或需要 Provider 响应移动事件时仍会发生 Binder 往返。

理解数据路径时要区分窗口、游标和数据库分页：

1. **窗口大小：**AOSP Android 17 的 `config_cursorWindowSize` 默认值为 2048 KiB，产品可覆盖。公开构造函数 `CursorWindow(String, long)` 也允许指定容量。
2. **窗口内读取：**当前窗口覆盖目标行时，读取列值通常在本地完成。
3. **窗口外移动：**窗口失效或访问越界位置时，`BulkCursorToCursorAdaptor` 可调用远端 `getWindow(newPosition)` 获取新窗口。若 Provider 要求接收每次移动事件，还会调用远端 `onMove()`。
4. **分页责任：**框架不会自动把 SQL 改写成 keyset 分页。直接访问很深的结果位置仍可能遍历大量前序结果。需要分页的 Provider 应自行提供分页条件，例如 `WHERE _id > ? ORDER BY _id LIMIT ?`，并为查询条件和排序设计索引。

窗口减少的是大结果集的单次复制，不意味着结果零成本或整个结果零拷贝：

1. 列值填充、窗口构建和共享区域初始化仍有成本。
2. CursorWindow 的行数据不直接占用 Binder 事务缓冲区，但 URI、projection、selection、`ContentValues` 和 `applyBatch()` 的操作数组等参数仍通过 Parcel 传输，受进程级共享缓冲区约束。
3. 调大窗口不能替代 SQL 分页和索引。分页优化数据库查询计划，窗口优化结果传输批次。

**Q3: ContentProvider 的 10 秒、20 秒和 3 秒分别计什么？所有 Provider 操作都有 10 秒超时吗？**

没有统一的“所有操作 10 秒超时”。AOSP Android 17 的这三个名义值分别对应 Provider 发布等待、调用方等待新进程就绪和少数异步回调等待，并会乘以 `HW_TIMEOUT_MULTIPLIER`。普通同步 CRUD Binder 调用不受同一个 Provider 计时器覆盖。

三个框架等待点分别是：

1. **10 秒发布窗口：**已 attach 的进程发布 Provider 的等待窗口，对应 `CONTENT_PROVIDER_PUBLISH_TIMEOUT_MILLIS`。超时后 `system_server` 会把提供方进程作为初始化失败移除。
2. **20 秒就绪窗口：**调用方等待新进程发布 Provider 的窗口，对应 `CONTENT_PROVIDER_READY_TIMEOUT_MILLIS`。等待结束仍未就绪时，调用失败。
3. **3 秒异步回调：**取得 Provider 后，`getTypeAsync()` 等少数异步回调的等待窗口，对应私有常量 `CONTENT_PROVIDER_TIMEOUT_MILLIS`。

普通 `query()`、`insert()`、`update()` 和 `delete()` 是同步 Binder 调用，没有统一的 Provider 超时计时器。相关系统机制也不等于普通应用会自动收到 `TimeoutException`：

1. `setDetectNotResponding()` 是 `@SystemApi`/`@hide` 接口，并要求 `REMOVE_TASKS` 权限。
2. Android 17 还提供受功能开关保护的取消无响应监测，包括 `setDetectNotRespondingOnCancel()` 和 `setCallNotCancelledTimeout()`。
3. 应用应把同步 ContentResolver 调用放在工作线程，为业务请求设置自己的截止时间，并在支持的 `query()` 中传递 `CancellationSignal`。截止时间到达后调用 `cancel()` 并丢弃迟到结果。
4. 取消是协作式的。Provider 和数据库执行路径主动检查取消信号时，工作才会及时停止。
5. Provider 通常在应用主线程初始化。因此，即使最终报告为输入、广播或 Service ANR，启动阶段 Provider 执行迁移或同步 I/O 仍可能是根因。

**Q4: [learning] applyBatch() 天然是事务吗？ContentProvider 的 Binder 线程池怎样会被拖垮？**

`applyBatch()` 默认不是事务。AOSP Android 17 的默认实现逐个调用 `ContentProviderOperation.apply()`，不会自动开启 SQLite 事务，也不保证失败时回滚。若接口要求整批“全部成功或全部回滚”，Provider 必须在存储层显式用 `beginTransaction()`、`setTransactionSuccessful()` 和 `endTransaction()` 包住整批操作。

并发 CRUD 可能让提供方 Binder 线程池耗尽：

1. 多个跨进程调用并发进入 Provider 的 Binder 线程。
2. 若这些线程都阻塞在同一数据库写锁或 I/O 上，可用线程逐渐耗尽。
3. 后续请求排队，调用方同步等待，最终可能触发调用场景对应的 ANR。

批量和进程隔离都各有边界：

1. **批次大小：**批量操作减少往返，但会扩大单个 Parcel，可能触发 `TransactionTooLargeException`。应按真实数据分布确定批次大小。
2. **独立进程收益：**`android:process=":provider"` 提供独立堆、GC、Binder 线程池和崩溃隔离。
3. **独立进程代价：**首次访问需要完整冷启动，SDK 可能在该进程重复初始化，所有调用都会变成跨进程调用。
4. **选型边界：**独立进程适合需要故障或内存隔离的数据组件。若根因是共享数据库锁，换进程不会消除锁竞争，只会改变隔离范围，因此它不是常规数据库性能优化手段。
