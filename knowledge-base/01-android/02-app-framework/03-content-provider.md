# ContentProvider 服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"Provider 初始化时序、CursorWindow 传输、超时契约与批量操作"；四组件入门契约归 [01-four-components.md](01-four-components.md)。源文档：android-internals-wiki §1.15（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: 进程启动时 ContentProvider 为什么早于 `Application.onCreate()` 初始化？`initOrder` 能保证什么？**

`ActivityThread.handleBindApplication()` 的顺序是：`makeApplicationInner()` → `Application.attach()`/`attachBaseContext()` → `installContentProviders()`（逐个 `installProvider()` 触发 `ContentProvider.attachInfo()`/`onCreate()`）→ `publishContentProviders()` 统一发布给 system_server → `Instrumentation.callApplicationOnCreate()` 执行 `Application.onCreate()`。因此任意 Provider 的 `onCreate()` 做磁盘扫描、数据库迁移或同步网络等待，都会推迟同进程其他 Provider 与 `Application.onCreate()`，远程调用方还可能正在等待这个进程发布 Provider。`initOrder` 只保证数值不同的 Provider 之间降序安装；相同值时源码比较器返回 0，实际顺序无承诺。

- Provider 的 `onCreate()` 在主线程执行；远程调用方等待的是 Provider 发布，而不是 `Application.onCreate()` 完成。
- Provider 发布后，远程 Binder 请求可能与提供方主线程的 `Application.onCreate()` 并发执行；二者争用同一数据库锁或 I/O 时，第一次 `query()` 仍会被间接拖慢。
- 存在初始化依赖时应显式声明依赖或由应用统一组织，不能依赖两个库碰巧选择了不同的 `initOrder`。

**Q2: 跨进程查询如何返回 Cursor？为什么 `moveToNext()` 仍可能走 Binder？大结果集该怎么分页？**

提供方用 `CursorWindow` 分批携带行数据：Binder 传递的是窗口文件描述符与控制元数据，不是整个结果集。AOSP Android 17 的 `config_cursorWindowSize` 默认 2048 KiB，产品可覆盖，公开构造函数 `CursorWindow(String, long)` 也可指定容量。窗口内读取列值通常不需要远程取数；但窗口失效、越界（`BulkCursorToCursorAdaptor` 调远端 `getWindow(newPosition)` 取新窗口）或 Provider 要求接收所有移动事件（远端 `onMove()`）时仍会发生 Binder 往返。框架不会自动把 SQL 改写成 keyset 分页，深位置访问可能遍历大量前序结果；需要翻页的接口应由 Provider 提供分页条件（如 `WHERE _id > ? ORDER BY _id LIMIT ?`）并配索引。

- 窗口减少大结果集复制，但列值填充、窗口构建与共享区域初始化仍有成本，"所有 Provider 结果零拷贝"不成立。
- CursorWindow 行数据不直接占用 Binder 事务缓冲区，但 URI、projection、selection、`ContentValues`、`applyBatch` 的操作数组等仍走 Parcel，受进程级共享缓冲区约束。
- 调大窗口不能替代分页与索引：分页优化的是数据库查询计划，窗口只优化传输批次。

**Q3: ContentProvider 的 10 秒、20 秒、3 秒分别计什么？"所有 Provider 操作都有 10 秒超时"对吗？**

不对，ContentProvider 没有覆盖所有操作的统一超时。三个数字对应三个等待点（名义值，会乘 `HW_TIMEOUT_MULTIPLIER`）：已 attach 进程发布 Provider 的窗口 10 秒（`CONTENT_PROVIDER_PUBLISH_TIMEOUT_MILLIS`，超时后 system_server 以初始化失败移除提供方进程）；调用方等待新进程发布 Provider 20 秒（`CONTENT_PROVIDER_READY_TIMEOUT_MILLIS`，等待结束返回失败）；已取得 Provider 后 `getTypeAsync()` 等少量异步回调 3 秒（私有 `CONTENT_PROVIDER_TIMEOUT_MILLIS`）。普通 `query()`/`insert()`/`update()`/`delete()` 是同步 Binder 调用，没有统一 Provider 计时器。

- `setDetectNotResponding()` 是 `@SystemApi`/`@hide` 且要求 `REMOVE_TASKS` 权限的系统接口；Android 17 另有受功能开关保护的取消无响应监测（`setDetectNotRespondingOnCancel()`/`setCallNotCancelledTimeout()`）。这些机制都不会向普通应用自动抛 `TimeoutException`。
- 应用侧做法：同步 ContentResolver API 放工作线程；为业务请求设自己的超时；向支持的 `query()` 传入 `CancellationSignal` 并在截止时间调用 `cancel()`，超时后丢弃迟到结果。取消是协作式的——Provider 与数据库执行路径主动检查信号才会及时停止。
- ContentProvider 通常在主线程初始化；即使最终报告的是输入、广播或 Service ANR，根因也可能是某个 Provider 在启动阶段做了迁移或同步 I/O。

**Q4: `applyBatch()` 天然是事务吗？Provider 的 Binder 线程池怎样被拖垮？**

不是。Android 17 中 `ContentProvider.applyBatch()` 的默认实现只是逐个调用 `ContentProviderOperation.apply()`，不自动开启 SQLite 事务、也不保证失败时回滚；要"全部成功或全部回滚"必须在 Provider 的存储层用 `beginTransaction()`/`setTransactionSuccessful()`/`endTransaction()` 包住整批操作。跨进程 CRUD 默认在提供方 Binder 线程执行，多个调用方并发进入、线程都阻塞在数据库写锁或 I/O 上时，可用线程逐渐耗尽、新请求排队，调用方同步等待进而触发所处场景的 ANR。

- 批次不是越大越好：减少往返的同时会增大单笔 Parcel，可能触发 `TransactionTooLargeException`；批次大小应按真实数据分布测试确定。
- 典型耗尽过程：并发进入 → 线程等同一把锁或同一 I/O → 可执行线程用尽 → 新请求排队 → 调用方 ANR。锁是根因时，把 Provider 移到独立进程不会消除问题，只会改变隔离范围。
- `android:process=":provider"` 的收益是独立堆/GC、独立 Binder 线程池与崩溃隔离；代价是首次访问完整冷启动、重复初始化未区分进程的 SDK、全部调用变跨进程。它适合需要故障或内存隔离的数据组件，不是常规数据库优化选项。
