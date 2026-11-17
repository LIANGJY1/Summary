# ContentProvider 服务链路

> 学习资料（文章模式沉淀）。边界：本文回答 Provider 初始化时序、CursorWindow 的跨进程传输、框架等待超时、批量操作的事务和线程池边界，以及客户端 stable/unstable 引用契约与包可见性过滤。四组件入门见 `01-四大组件.md`。依据：AOSP Android 17 源码与 android-internals-wiki §1.15。Q5、Q6 的客户端引用与可见性事实按 Android 13 本地源码核对。Q 序列即结构，供 Atlas 同源直读。

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

**Q3: [learning] ContentProvider 的 10 秒、20 秒和 3 秒分别计什么？所有 Provider 操作都有 10 秒超时吗？**

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

**Q5: [learning] acquireContentProviderClient 与 acquireUnstableContentProviderClient 的契约差在哪里？provider 进程死亡时各发生什么？**

stable 客户端让平台把调用方进程与 provider 生命周期绑定：provider 进程退出时，AMS 会清理依赖它的客户端进程。unstable 客户端显式关闭这套机制，调用方要自己捕获 DeadObjectException、释放旧客户端并重新获取。两个方法在 provider 不存在时都返回 null。

两条路径的差异由客户端进程内的引用计数与死亡通知机制支撑：

1. **引用计数：**`ActivityThread` 按 authority 维护 `ProviderRefCount` 双计数（stableCount/unstableCount）。`installProvider` 按 stable 标志初始化为 stable(1,0) 或 unstable(0,1)。系统自有且 `noReleaseNeeded` 的 provider 用 1000/1000 的大计数占位，正常使用不会把它减到零。
2. **stable 释放的延迟回收：**释放最后一根 stable 引用时，`releaseProvider` 先把它转换为 unstable 引用（向 AMS 调用 `refContentProvider(connection, -1, 1)`），再延迟 `CONTENT_PROVIDER_RETAIN_TIME` 投递移除消息。移除前再次 acquire 会撤销待移除状态，因此短间隔重复获取不产生跨进程往返。
3. **死亡路径：**本地缓存命中但 Binder 已死时，`acquireExistingProvider` 调用 `handleUnstableProviderDiedLocked` 清理引用账目并通知 `AMS.unstableProviderDied`。`ContentResolver.query()` 在远端进程死亡时也走 `unstableProviderDied` 再重新获取引用的恢复路径。检测 provider 线程无响应另有 `ContentProviderClient.setDetectNotResponding`，触发 `appNotRespondingViaProvider` 通知 AMS。

选型按 provider 的可信程度决定：

1. **系统内置、稳定的 provider**：使用 stable 客户端，直接获得生命周期保障。
2. **第三方或不稳定的 provider**：使用 unstable 客户端，避免因 provider 死亡被连坐清理，同时自行处理失败重试与状态重建。

依据：Android 13 的 ActivityThread.acquireProvider/installProvider/releaseProvider 与 ProviderRefCount、ContentResolver.acquireUnstableContentProviderClient javadoc 及 query 死亡恢复路径。

**Q6: [learning] Android 11 的包可见性如何影响解析其他应用的 ContentProvider authority？**

包可见性（package visibility，Android 11/API 30 起）让 authority 解析经过可见性过滤：AMS 取 provider 前先用 PackageManager 解析 authority，解析端对目标组件执行可见性判断，调用方不可见目标应用时直接按“不存在该 provider”返回 null，而不是报告权限错误。因此调用方表现为 `acquireContentProviderClient` 拿到 null 或按 authority 查询失败，容易误判为目标 provider 未实现。

1. **过滤位置：**`ContentProviderHelper.getContentProviderImpl` 在 provider 已运行与冷启动两条分支上都先 `resolveContentProvider(name, 0, userId)` 探测，返回 null 即放弃，注释写明目的是不在普通应用与 instant 应用间暴露 provider。PMS 侧 `ComputerEngine.resolveContentProvider` 在返回前对目标组件调用 `shouldFilterApplication`，命中过滤即返回 null。
2. **可见性规则的来源：**调用方 manifest 的 `<queries>` 提供三类声明：`<intent>`（对 provider 场景按 content scheme 加通配 authority 匹配）、`<package>`（按包名）、`<provider>`（按 authorities 列表，多个以分号分隔）。此外持有 `QUERY_ALL_PACKAGES` 权限、目标应用 forceQueryable、可持久化的 uri 授权等任一规则命中即可见。
3. **豁免边界：**系统 uid 与同一应用（同 uid）之间不过滤。instant 应用之间的暴露规则单独处理。
4. **排查衔接：**权限检查通过但解析不到 authority 时，先核对调用方是否声明了 `<queries>`、目标 authorities 拼写是否一致，再区分“被可见性过滤”与“目标确实未发布 provider”。

依据：Android 13 的 ContentProviderHelper.getContentProviderImpl、ComputerEngine.resolveContentProvider 与 shouldFilterApplication、ParsingPackageUtils.parseQueries。
