# SystemServer

> 学习资料（文章模式沉淀）。主线：system_server 的进程构成、服务就绪与恢复语义，以及它和 WMS、SurfaceFlinger 的边界。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] system_server 进程都包含哪些层级的代码？了解这些有什么用？**

`system_server` 是应用框架服务端的宿主进程。理解它的边界，先区分进程内包含什么，以及系统服务如何暴露调用接口。

1. **进程内代码**：运行 AMS、ATMS、WMS、PMS 等 Java 系统服务、Framework JNI 库、ART 运行时和 Binder 原生库。系统服务按 Bootstrap、Core、Other、Apex 等组装配。
2. **进程外组件**：SurfaceFlinger 是独立原生服务进程。HAL 可能运行于独立进程，也可能作为共享库加载进调用方进程。因此，“应用框架层”不等于某一个进程。
3. **服务调用边界**：跨进程服务经 ServiceManager 发布 Binder 接口。同进程服务可经 LocalServices 暴露 `*Internal` 接口。服务拆到独立进程后，必须改用可跨进程的 IPC 契约，不能继续依赖 LocalServices。

了解这些的用处有三点：

1. **框架单点**：`system_server` 崩溃意味着整个框架重启。Zygote 检测到其死亡后自杀，由 init 重启 Zygote，再重新 fork system_server。许多应用框架 Binder 请求最终由这里的系统服务处理，因此它的卡顿可能影响全局。
2. **慢的归属**：`system_server` 内的排队和锁竞争是系统服务的开销，不要算到应用头上。
3. **进程归属**：定位问题前先确认进程，别把层名当进程名用。

**Q2: [learning] system_server 是怎么被创建并启动到“服务就绪”的？**

创建按以下顺序完成：

1. Zygote 通过 `forkSystemServer()` 创建子进程。子进程继承 Zygote 的预加载类与资源，并在 native 层经 `SpecializeCommon(is_system_server=true)` 完成身份改造，参数 `true` 表示按 system_server 身份分支进行 specialize。改造包括 cgroup 与 task profile、补充组与资源限制、seccomp、`setresgid()/setresuid()`、capabilities 和 SELinux context。
2. `handleSystemServerProcess()` 接管子进程。
3. `ZygoteInit.zygoteInit()` 执行，其中 `nativeZygoteInit()` 启动 Binder 事务线程池。该线程池在进入 `SystemServer.main()` 前已启动。
4. `RuntimeInit.applicationInit()` 找到入口，进入 `SystemServer.main()`。

进入 `SystemServer.main()` 后会调用 `new SystemServer().run()`，启动准备包括以下步骤：

1. 设置系统进程规则，包括 Binder 阻塞告警与最大线程数。
2. 准备主 Looper 与 `SystemServerInitThreadPool`。
3. 加载 `android_servers`、建立 System Context，并初始化进程内 Mainline 模块。
4. 创建 `SystemServiceManager`，依次执行 `startBootstrapServices()`、`startCoreServices()`、`startOtherServices()`、`startApexServices()`（第四组对应 APEX 可更新模块，旧资料常漏），最后主线程进入 `Looper.loop()`。

到达 `main()` 时进程已具备 ART、Framework JNI、预加载类与 Binder 线程池，各服务对象仍要由 `run()` 建立。`Looper.loop()` 只表示主启动控制流进入消息循环，不等于 Framework 全部 ready。Binder 线程池、`SystemServerInitThreadPool` 与主 Looper 是不同的执行机制。判断服务就绪还要结合各服务的 `systemReady()` 与 Boot Phase 事件。

**Q3: system_server 的四波装配顺序为什么改不得？BootPhase 阶段事件解决了什么问题？**

Bootstrap、Core、Other、Apex 四波装配承载强依赖，不能随意换序。弱依赖则由 SystemServiceManager 广播 BootPhase 阶段事件解耦：服务在 `onBootPhase()` 中响应自己关心的阶段，不必直接依赖另一个服务的启动顺序。强依赖按顺序装配，弱依赖按阶段协作。

四波内容（Android 13 批注）：

1. **Bootstrap**：启动 Watchdog、Installer、ATMS、AMS、电源/显示相关服务和 PMS，再由 AMS 设置 system process。PMS 扫描分区 APK，通常是耗时较大的阶段。
2. **Core**：启动 Battery、UsageStats 和 WebView 相关服务。
3. **Other**：启动 WMS、IMS、网络、电话、媒体等约百个服务，末尾调用 `AMS.systemReady()` 并启动 SystemUI。
4. **Apex**：最后装配 APEX 内服务并封板（`sealStartedServices()`）。封板后再注册服务会触发异常。

BootPhase 用递增阶段表达服务可以依赖的启动条件。Android 13 基线中的主要阶段如下：

1. `PHASE_WAIT_FOR_DEFAULT_DISPLAY`（100）：等待默认显示设备就绪。
2. `PHASE_WAIT_FOR_SENSOR_SERVICE`（200）：等待 SensorService 可用。
3. `PHASE_LOCK_SETTINGS_READY`（480）：服务可以读取锁屏设置。
4. `PHASE_SYSTEM_SERVICES_READY`（500）：核心系统服务可用，例如 PowerManager 与 PackageManager。
5. `PHASE_DEVICE_SPECIFIC_SERVICES_READY`（520）：设备专属服务可用。
6. `PHASE_ACTIVITY_MANAGER_READY`（550）：可以发送广播。
7. `PHASE_THIRD_PARTY_APPS_CAN_START`（600）：可以启动或绑定第三方应用。
8. `PHASE_BOOT_COMPLETED`（1000）：系统服务应完成启动工作，设备进入可交互阶段。

阶段常量和具体语义会随版本变化，需以目标分支的 `SystemService` 定义为准。

**Q4: 启动一个 Activity 到底是 AMS 还是 ATMS 在负责？目标进程由谁创建？**

现代 Android 中，ATMS 负责 Activity 启动与任务编排，AMS/ProcessList 协调进程管理，Zygote 实际 fork 出目标进程。`Instrumentation.execStartActivity()` 调用 ATMS 的 `startActivity()`，由 `ActivityStarter` 选择 TaskDisplayArea 与 Task。只有目标进程不存在时，ATMS 才经 `startProcessAsync()`、`ActivityManagerInternal.startProcess()`、`ProcessList.startProcessLocked()` 和 `Process.start()` 请求 Zygote 创建进程。“启动 Activity 都由 AMS 完成”是旧版实现的说法。

AMS、ATMS、WMS 的职责和请求路径如下：

1. **AMS**：管理进程、Service、广播、ContentProvider、ANR、崩溃与进程重要性。它经 `IActivityManager` Binder 接口接收跨进程请求，再由 Binder 线程转交持锁代码、Handler 或系统线程处理。名为 ActivityManager 的线程不是 AMS 唯一的处理线程。
2. **ATMS**：管理 Activity 启动、任务选择与前后台切换。任务容器从 `RootWindowContainer` 到 `DisplayContent`、`TaskDisplayArea`、`Task`，再到 `ActivityRecord`。旧资料中的 `TaskStack` 只对应历史实现。
3. **WMS**：管理窗口容器、层级、焦点、可见性与输入窗口状态。

三者都运行在 system_server 进程内。

**Q5: [learning] system_server 把多个系统服务放在同一进程，Watchdog 与 init 怎样降低这种单点故障的影响？**

同进程部署简化了服务间高频调用，但也让系统服务共享崩溃域。Watchdog 监控 system_server 关键线程，超时后可终止整个进程。随后 init 根据服务退出记录和 critical 规则决定是否继续重启设备。

1. **运行期监控**：Watchdog 在 system_server 内运行，在 Bootstrap 阶段启动并检查关键线程的 Handler 与同步监控器。具体线程、阈值和杀进程策略受 Android 版本及设备配置影响。
2. **进程恢复**：Watchdog 终止 system_server 后，Zygote 检测到 system_server 子进程死亡并退出。init 随后重启 Zygote，由新 Zygote 重新创建 system_server。
3. **重复故障裁决**：默认 fatal crash window 为 4 分钟。在开机完成后，critical 服务在该窗口内退出超过 4 次（即第 5 次）会触发 fatal 处理。开机完成前退出也会触发 fatal 处理，不必等到累计第 5 次。服务可用 `critical [window=<分钟>] [target=<目标>]` 配置窗口和 fatal reboot target。省略 `window` 时默认 4 分钟，省略 `target` 时默认重启到 bootloader。产品配置可覆盖这些默认值。
4. **架构取舍**：耦合紧密、交互频繁的服务集中运行可减少 IPC 成本，但需要进程级看护。真正缩小崩溃范围要把低耦合服务放到独立进程。采用 APEX/Mainline 模块化本身只改变交付和更新边界，不保证进程隔离。

调试时反复终止 Zygote 可能触发 critical 规则和设备级重启，不应当作无副作用的重启手段。

**Q6: [learning] 设备正常使用中因 system_server Watchdog 软重启时，默认超时阈值是多少，怎样从日志定位卡住的线程？**

AAOS 13 源码基线中，Watchdog 默认完整超时为 60 秒，调试默认值为 10 秒。设备设置、硬件超时倍率和目标分支都可能改变实际阈值与超时后的动作。Watchdog 将消息投递到受监控线程并执行同步 monitor 检查，检查完成状态用于区分线程卡在消息队列还是 monitor。

1. **监控对象**：源码基线的 HandlerChecker 覆盖 foreground、main、ui、io、display、animation 和 surface animation 等关键线程。Watchdog 还检查 Binder 线程池存活情况。
2. **超时证据**：
    1. 超过一半时限（AAOS 13 默认约 30 秒）时，Watchdog 可 dump 线程栈并写入 DropBox，tag 为 `pre_watchdog`。这一预警阶段会继续等待，不会因此杀掉 system_server。
    2. 达到完整超时时限（常见约 60 秒）时，会打印类似 `WATCHDOG KILLING SYSTEM PROCESS` 的日志，并按构建和配置决定是否终止 system_server。
3. **识别阻塞点**：`Blocked in handler on <线程名>` 表示线程未完成 Handler 检查。`Blocked in monitor <类名>` 表示同步 monitor 检查未完成。WatchdogDiagnostics 随后打印相关 Java 栈。`waiting to lock <0x...> held by thread N` 可帮助找出锁持有线程。
4. **区分启动期长任务**：PMS 构造可能超过 Watchdog 心跳，因此 Android 13 的 SystemServer 在调用 PMS 初始化前暂停当前线程的 Watchdog 监控，构造完成后再恢复。新增同类长耗时初始化时也要处理监控门槛，否则可能被误判为卡死。
5. **查看记录**：`dumpsys watchdog` 显示当前检查器和阻塞状态。DropBox 中的 `watchdog` 与 `pre_watchdog` 条目用于回看历史现场。
6. **防止重启循环**：非 user 构建可用 `framework_watchdog.fatal_count` 配置触发阈值次数，用 `framework_watchdog.fatal_window.second` 配置时间窗秒数。两项都必须设为非零才启用 Watchdog 超时循环保护。省略或取默认值 `0` 时不启用该保护。它与 init 的 critical 服务退出计数是两套独立机制。debugger 已连接时，Watchdog 可跳过杀进程，以便调试。

排查按这个顺序进行：

1. 核对设备实际 Watchdog 阈值和构建类型。
2. 根据 Handler/monitor 日志定位线程，并查看 DropBox 栈与锁等待者。
3. 结合线程栈、锁归属和启动期暂停点追查阻塞原因。

这里的软重启是 system_server/Framework 恢复现象，不等于内核重新启动。

**Q7: system_server 崩溃后，系统靠什么恢复？**

恢复链分三步：

1. Zygote 的 SIGCHLD 处理路径通过 `waitpid()` 匹配到 system_server 的 pid（`gSystemServerPid`），确认后杀死 Zygote 自身。
2. Zygote 是 init 管理的服务，退出后由 init 的服务监督链路重启。
3. 新 Zygote 重新预加载并调用 `forkSystemServer()`，整个 Java 框架重建。

恢复时可观察到的现象与判断依据如下：

1. **进程影响**：system_server 重建会丢失 Java 系统服务运行状态，应用进程也会被连带终止。Zygote fork 出的子进程设置父进程死亡信号（PDEATHSIG），Zygote 退出后子进程会收到 SIGKILL，因此用户可能看到界面闪动后回到桌面。
2. **确认退出链**：先确认是谁退出。用 `ps -A -o PID,PPID,NAME` 检查 system_server 的父进程以及 Zygote 是否换了 PID。Zygote socket 用于应用进程创建请求，与 system_server 的崩溃恢复无关。
3. **运行期重启**：当 `sys.boot_completed` 已置位后 system_server 再启动时，Framework 会按 runtime restart（soft reboot）走部分精简初始化路径。Android 13 基线还存在一个边界：开机完成前 system_server 曾崩溃并再次启动时，`mRuntimeRestart` 仍可能为 false，源码 TODO 也记录了该问题。依赖冷启动/运行期重启标志的代码要以目标分支实现为准。

**Q8: 在 system_server 里写代码和读代码各有哪些纪律？**

读写 system_server 代码时，先确认线程归属与服务职责。system_server 主线程承担 SystemServer 启动控制流及投递到其 Looper 的工作，但系统服务也可拥有独立 HandlerThread、线程池和 Binder 线程。不能假设所有服务消息都跑在主线程。

1. **检查阻塞**：主线程上的同步 Binder、磁盘 I/O 或长计算会阻塞依赖该线程的启动和服务路径。核对调用线程、超时设置与锁依赖。
2. **定位职责**：Activity 任务和生命周期编排主要由 ATMS（wm 包）负责，进程管理等由 AMS（am 包）负责。
3. **判断合法性**：分析一段代码前确认它运行在哪个线程、假设冷启动还是运行期重启，以及职责属于 AMS 还是 ATMS。

**Q9: [learning] AMS 的双锁模型（mGlobalLock 与 mProcLock）如何工作？为什么 OOM adj 全量更新仍要同时持两把锁？**

Android 12 起 AMS 持有两把锁。AMS 从 Android 12 起逐步把受保护状态分到两把锁。`mGlobalLock`（即 `ActivityManagerService.this`）保护 Service、Provider、Broadcast 等核心组件状态。`mProcLock`（`ENABLE_PROC_LOCK = true` 时为独立的 `ActivityManagerProcLock` 对象）保护逐步迁入的进程管理状态。需要同时持锁时固定按 `mGlobalLock` → `mProcLock` 的顺序获取，反向获取会形成 AB-BA 死锁。OOM adj 全量更新既要读组件关系又要写进程状态，所以 `updateOomAdjLocked()` 在持全局锁后再进入 `mProcLock` 调用 `updateOomAdjLSP()`。

1. **组合读写契约**：`@CompositeRWLock({"this", "mProcLock"})` 表示读取持任一把锁即可，写入需两把（如 AMS 中受该注解保护的字段）。注解中的 `this` 指向 AMS 全局锁。它供静态分析使用，不会在运行时创建新的读写锁对象。
2. **方法后缀**：LOSP 表示任一锁，LSP 表示两把锁，Locked 表示全局锁，LPr 表示进程锁。判断时先看注解，再用后缀辅助，并复核调用点。不要为 LSP 臆造英文全称。
3. **并发收益**：双锁扩大了部分读取与独立操作的并发空间，例如时区更新可只持 `mProcLock` 遍历 LRU，`CachedAppOptimizer` 也使用冻结/压缩队列。双锁没有消除大临界区，实际临界区仍要按持锁代码审查。

**Q10: [learning] WindowManagerService 与 SurfaceFlinger 如何分工？界面不更新时怎样区分两侧的问题？**

WMS 负责窗口策略，SurfaceFlinger 负责图层合成。它们通过接口协作，但运行在不同进程，排障时要沿各自职责找证据：

1. **WMS**：运行在 `system_server`，管理窗口容器、层级、焦点与配置，并把窗口状态转换为 SurfaceControl 图层操作。
2. **SurfaceFlinger**：init 启动的独立原生服务进程，收集图层与缓冲区，借助 CompositionEngine、RenderEngine 和 Composer HAL 合成帧并提交显示。

排查“界面没动”时分别检查两侧：WMS 可能没有产生布局或层级变化，SurfaceFlinger 可能没有合成新帧，或者提交被栅栏卡住。两者通过接口协作，但属于不同进程与崩溃域。一方卡顿或崩溃不自动意味着另一方也失效。


**Q11: AMS 双锁设计怎样改善并发？锁优先级提升和竞争观测分别意味着什么？**

`mGlobalLock` 与 `mProcLock` 让不共享保护状态的操作有机会并行。锁优先级提升只减少持锁线程被调度延迟，不能消除竞争。观察锁事件和 contention trace 才能确认实际等待位置。

1. **并发边界**：双锁允许只访问进程状态的操作单独持有 `mProcLock`，而仍涉及 AMS 全局关系的操作继续遵循对应锁契约。它扩大并发空间，不代表所有方法都能拆锁，也不保证消除长临界区。
2. **优先级提升**：AAOS 13 的 `ThreadPriorityBooster` 在锁保护区内把持锁线程的 nice 提升到 `THREAD_PRIORITY_FOREGROUND`，并在退出最外层临界区时恢复原优先级。这不是把线程切换到 `SCHED_FIFO` 实时调度，也不会解除锁等待。
3. **锁竞争观测**：Android 17 在 `big_locks` 类别定义 `ams_lock_acquire/held` 与 `proc_lock_acquire/held` 事件。是否可用取决于 `perfettoSdkTracingV3()` 特性。通用锁竞争可从 Perfetto 的 `android.monitor_contention` 轨道检查，具体事件名与采集配置要按目标分支核对。
