# StrictMode

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q35 -->

**Q1: [learning] StrictMode 的 ThreadPolicy 与 VmPolicy 各管什么范围？在 Application.onCreate() 里 setThreadPolicy() 之后，线程池里的磁盘操作为什么检测不到？**

`ThreadPolicy` 只作用于调用 `StrictMode.setThreadPolicy()` 的那条 OS 线程——它不是“主线程策略”，常见写法覆盖主线程只是因为 `onCreate()` 恰好运行在主线程。线程池、Binder 线程和手工创建的线程不会自动继承这份 Java 线程策略，需要各自安装。`VmPolicy` 由 `setVmPolicy()` 安装到进程级静态状态，进程内各线程触发对应检查点时都受约束。两条机制细节（AAOS13 `StrictMode.java` 核对）：每次 `setThreadPolicy()`/`setVmPolicy()` 都会整体替换现有策略。

Builder 启用了 detector 却未显式配置 penalty 时，`build()` 自动补上 `penaltyLog()`。`setThreadPolicyMask()` 会同时更新 libcore `BlockGuard` 的 Java policy 与 Binder native 层保存的策略 mask。多进程方面，`android:process` 创建的进程会重新运行 `Application.onCreate()`，策略与违规限流状态各进程独立。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q36 -->

**Q2: [learning] StrictMode 的磁盘与网络检测点在哪里？为什么 SharedPreferences 后台加载 XML 时主线程卡住、却不报 DiskReadViolation？**

检测点不是拦截每个系统调用，而是 framework 与 libcore 预埋的检查点：`detectDiskReads()`/`detectDiskWrites()` 通过当前线程的 `BlockGuard.Policy` 接收 `onReadFromDisk()`/`onWriteToDisk()` 回调，`detectNetwork()` 走 `onNetwork()`，`detectUnbufferedIo()` 走 `onUnbufferedIO()`（AAOS13 libcore 源码核对），资源只有接入 `CloseGuard` 并调用 `open()`/`close()`/`warnIfOpen()` 才参与泄漏检测。

`SharedPreferences` 是典型盲区：`SharedPreferencesImpl` 在后台线程加载 XML，主线程 getter 只是等待加载完成——磁盘 syscall 不在主线程，主线程 StrictMode 不会产生 `DiskReadViolation`，但界面仍因等待而卡住，此时要看 Perfetto 的线程状态与工作线程 I/O。同类边界还有：直接从 native 代码发起、未经过检查点的 syscall 可能绕过检测。主线程用 `Future.get()` 等待后台 I/O 时检查点在工作线程。检测到磁盘访问不代表该访问一定慢。

`detectExplicitGc()`（显式 `System.gc()` 检查）在 Android 13 是 `@hide`/`@TestApi`（AAOS13 源码与 API 表核对），材料按 Android 17 口径为 API 34 起对 SDK 公开——在 Android 13 上不能直接调用。`noteSlowCall(name)` 只标记调用点、没有耗时阈值，要知道耗时用 trace section 加 Perfetto。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q37 -->

**Q3: [learning] StrictMode 的 penalty 各是什么语义？用 penaltyListener 记录违规时为什么要用独立 Executor？**

penalty 决定违规被检出后的处理：

- `penaltyLog()`：通过 logger 输出违规，相同调用栈指纹会限流。
- `penaltyDeath()`：ThreadPolicy 下在处理末尾抛 `RuntimeException`；VmPolicy 下调用 `killProcess()` 退出。
- `penaltyDeathOnNetwork()`：启用 `detectNetwork()` 后在其他 penalty 之前直接抛 `NetworkOnMainThreadException`，只想记录就不要同时启用。
- `penaltyListener(executor, listener)`：把原始 `Violation` 交给指定 Executor（Executor 参数必填，AAOS13 公开 API 核对）。
- `penaltyDropBox()`：经 ActivityManager 写入系统诊断存储。
- `penaltyDialog()`/`penaltyFlashScreen()`：面向交互式调试。

listener 机制里，回调执行期间 StrictMode 会临时放开回调线程的 ThreadPolicy，避免 listener 内部的日志、入队等操作再次触发自身形成递归，但队列消费与 Executor 生命周期仍由应用负责——回调里不要同步上传网络或做重型符号化，堆栈与明文数据也可能含敏感信息。另一个读数边界：带 Looper 的线程上记录的 `durationMillis` 是从违规时刻到 Looper 完成本轮工作之间的时间，可能包含违规操作后的其他同步代码，不是磁盘 syscall 的精确耗时。同一 Looper 循环的记录数量有上限，日志条数不能当成违规发生次数。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q38 -->

**Q4: [learning] 应用堆栈里出现“发生在 system_server 的 DiskReadViolation”是怎么回事？用 allowThreadDiskReads() 临时放行时要注意什么？**

这是 ThreadPolicy mask 随同步 Binder 调用传播的结果：`setThreadPolicyMask()` 同时更新 BlockGuard 的 Java policy 与 Binder native 层的 mask，发起同步 Binder 调用时 mask 随事务到达服务端 Binder 线程。

服务端触发违规后 `PENALTY_GATHER` 把 `ViolationInfo` 放进 `gatheredViolations` 这个 ThreadLocal，`Parcel.writeNoException()` 把收集到的违规写入 reply——AAOS13 源码核对为最多写前 3 条、每条栈约截断到 20 kB——调用方 `Parcel.readException()` 再经 `readAndHandleBinderCallViolations()` 补上本地调用栈并交给调用方当前策略处理。所以 IPC 间接执行的 I/O 会出现在应用堆栈里。

这套传播只覆盖 ThreadPolicy，远端进程的 VmPolicy 不传播，回传信息也不包含 Binder 调用本身的耗时。临时放行方面：`allowThreadDiskReads()` 只清除 disk-read 检测位，`allowThreadDiskWrites()` 同时清除 disk-write 与 disk-read 位（AAOS13 的 `allowThreadDiskWritesMask()` 实现核对，因为写文件通常伴随读取元数据）。返回值是放行前的完整策略，必须在 `finally` 中恢复，异常、提前返回和嵌套调用都不能让线程永久处于宽松状态。

放行不会让操作更快，注释应写明数据量上限、调用阶段和无法异步化的原因。自动化测试安装策略时，`@Before` 运行在测试线程、直接调用只改变测试线程，要用 `runOnMainSync` 切到应用主线程。依赖 GC 与 finalization 时序的 VmPolicy 违规不适合直接做确定性断言，适合先收集证据。


<!-- 来源：05-observability/02-online-diagnostics.md · Q8 -->

**Q5: [learning] StrictMode 能证明“主线程发生了磁盘操作”吗？能测出这次 I/O 花了多久吗？为什么不能反射替换 CloseGuard 的 reporter 做长期监控？**

StrictMode 能证明触发、不能测时长。AAOS13 源码核对：`BlockGuardOs` 在 `open`、`read`、`write`、`fsync`、`stat`、`rename` 等 libcore OS 操作前调用线程策略的 `onReadFromDisk()`/`onWriteToDisk()`，所以 StrictMode 回答的是“这个受监控线程是否触发了磁盘操作”，它不测量存储设备完成一次请求的时间。调用发生时数据可能已在页缓存，耗时也可能短于一次帧预算，主线程告警要关联 FrameTimeline 和用户操作后再定优先级。两个使用边界：`setThreadPolicy()` 只作用于调用它的线程，只在主线程安装不能声称覆盖全部工作线程。`detectUnbufferedIo()` 从 API 26 提供、`detectLeakedSqlLiteObjects()` 从 API 9 提供（AAOS13 均已核对存在），适合 debug、自动化测试与内测包，生产开启要先评估成本。

`CloseGuard.setReporter()` 在 AAOS13 仍标记为 `@hide`（已核对），普通应用没有稳定的公开调用契约。反射替换 reporter 会同时引入 non-SDK 限制和版本兼容风险。长期监控应使用公开的 StrictMode VM policy（`detectLeakedSqlLiteObjects()`、`detectLeakedClosableObjects()` 等）并限制在测试与受控诊断场景。
