# SystemServer

> 学习资料（文章模式沉淀）。主线：system_server 的进程构成与单点语义、WMS 与 SurfaceFlinger 的分工。创建与启动链细节见 [02-system-boot.md](02-system-boot.md)（02-system-boot Q4/02-system-boot Q5/02-system-boot Q14–Q17）。Q 序列即结构，供 atlas 同源直读。

**Q1: `system_server` 进程都包含哪些层级的代码？了解这些有什么用？**

`system_server` 是"应用框架层服务端"的宿主进程：里面运行着几百个 Java 系统服务（AMS/ATMS、WMS、PMS 等，按 Bootstrap/Core/Other/Apex 四组启动）、Framework 的 JNI 库、ART 运行时和 Binder 原生库；它由 Zygote fork 出来，继承预加载的类与资源，接收 Binder 事务的线程池在进入 `SystemServer.main()` 之前就已启动。

它**不包含**的东西同样重要：SurfaceFlinger 是独立原生服务进程，HAL 或是独立进程、或是加载进调用方的共享库——"应用框架"这个层名不等于"某一个进程"。

了解这些的用处有三点：

1. **框架单点**：`system_server` 崩溃意味着整个框架重启——Zygote 检测到其死亡后自杀，由 init 重启 Zygote 再重新 fork；各应用的日常 Binder 调用大量落在这里，它的卡顿是全局性卡顿；
2. **慢的归属**：`system_server` 内的排队和锁竞争是系统服务的开销，不要算到应用头上；
3. **进程归属**：定位问题前先确认进程，别把层名当进程名用。

**Q2: system_server 是怎么被创建并启动到"服务就绪"的？**

创建分四步：

1. `ZygoteInit.main()` 在进入 select 循环前调用 `forkSystemServer()`，子进程（`pid == 0`）在 native 层经 `SpecializeCommon(is_system_server=true)` 完成身份改造——cgroup 与 task profile、补充组与资源限制、seccomp、`setresgid()/setresuid()`、capabilities、SELinux context；
2. `handleSystemServerProcess()` 接管子进程；
3. `ZygoteInit.zygoteInit()` 执行，其中 `nativeZygoteInit()` 启动 Binder 线程池；
4. `RuntimeInit.applicationInit()` 找到入口，进入 `SystemServer.main()`。

启动按四段执行——`main()` 只是 `new SystemServer().run()`：

1. 设置系统进程规则（Binder 阻塞告警、最大线程数等）；
2. 准备主 Looper 与 `SystemServerInitThreadPool`；
3. 加载 `android_servers`、建立 System Context、每进程 Mainline 模块初始化；
4. 创建 `SystemServiceManager`，依次执行 `startBootstrapServices()`、`startCoreServices()`、`startOtherServices()`、`startApexServices()`（第四组对应 APEX 可更新模块，旧资料常漏），最后主线程进入 `Looper.loop()`。

边界：到达 `main()` 时进程已具备 ART、Framework JNI、预加载页面和 Binder 线程池，但各服务对象要由 `run()` 建立；`Looper.loop()` 只表示主启动控制流进入消息循环，不等于"Framework 全部 ready"——Binder 线程池与 InitThreadPool 不服从主 Looper 顺序，判断就绪要看各服务 `systemReady()` 与 Boot Phase 事件。

**Q3: system_server 的四波装配顺序为什么改不得？BootPhase 广播解决了什么问题？**

startBootstrapServices → startCoreServices → startOtherServices → startApexServices 四波的顺序是硬依赖（AMS 依赖 PMS、WMS 依赖 AMS/IMS），改顺序直接启动失败；四波之外的弱依赖靠 SystemServiceManager 的 PHASE_* 阶段广播解耦——服务只声明自己在哪个阶段做什么（onBootPhase），不需要知道彼此的启动顺序。一句话：强依赖用排序表达，弱依赖用阶段事件表达。

四波内容（Android 13 批注）：

1. **Bootstrap**：Watchdog → Installer → ATMS → AMS → 电源/显示 → PMS（最重，扫全部分区 APK）→ AMS.setSystemProcess；
2. **Core**：Battery/UsageStats/WebView；
3. **Other**：WMS/IMS/网络/电话/媒体等约百个服务，末尾调 AMS.systemReady 并启动 SystemUI；
4. **Apex**：APEX 内服务最后装配并封板（sealStartedServices），之后再 startService 直接抛异常。

BootPhase 从 PHASE_WAIT_FOR_DEFAULT_DISPLAY(100) 经 200/480/500/520/550/600 逐级广播到 PHASE_BOOT_COMPLETED(1000)。易错：PMS 构造可能超过 Watchdog 心跳，SystemServer 在调 PMS.main 前显式 pauseWatchingCurrentThread、构造完再恢复——新增长耗时初始化若不照做会被 Watchdog 误杀。

**Q4: 启动一个 Activity 到底是 AMS 还是 ATMS 在负责？目标进程由谁创建？**

现代 Android 中启动 Activity 由 ActivityTaskManagerService（ATMS）负责：`Instrumentation.execStartActivity()` 直接调用 ATMS 的 `startActivity()`，由 `ActivityStarter` 选择 TaskDisplayArea 与 Task；只有目标进程不存在时，才经 `ActivityTaskManagerService.startProcessAsync()` → `ActivityManagerInternal.startProcess()` → `ProcessList.startProcessLocked()` → `Process.start()`/Zygote fork 创建进程。"启动 Activity 都由 AMS 完成"是旧版实现的说法。

- 三者分工：AMS 管进程、Service、广播、ContentProvider、ANR、崩溃与进程重要性；ATMS 管 Activity 启动、任务选择与前后台切换；WMS 管窗口容器、焦点、可见性与输入窗口状态。三者都在 system_server 进程内。
- 任务容器模型：`RootWindowContainer` → `DisplayContent` → `TaskDisplayArea` → `Task`（可嵌套）→ `ActivityRecord`；旧文章中的 `TaskStack` 只用于解释历史实现。
- AMS 经 `IActivityManager` Binder 接口接收跨进程请求，由 Binder 线程接收后在持锁段、Handler 或系统线程继续处理；不要把名为 ActivityManager 的线程当作唯一的 AMS 主线程。

**Q5: system_server 的单点风险靠什么兜底？Watchdog 是怎么工作的？**

单进程装下所有服务换来了服务间进程内直调的简单，也把崩溃域合并成一个；兜底是双层——Watchdog 监控各关键线程心跳、超时杀掉 system_server 进程，之后接 init 侧的 critical 崩溃计数兜底（默认 4 分钟窗口内第 5 次退出触发 fatal，开机完成前同样计数）。

机制与取舍：Watchdog 在 Bootstrap 波最先启动，各关键线程定期喂狗；system_server 被杀后走恢复链（Zygote 自杀 → init 重启 Zygote → 重新 fork）。取舍是"集中式的风险要配自动化的看护"：服务交互极频繁时拆分成本高于崩溃成本，集中加看护更划算；低耦合服务则应拆出去——Android 把部分服务移入 APEX/Mainline 正是反向操作。

边界：调试时反复 kill Zygote 会因 critical 规则把设备直接带回 bootloader，不是 bug。

**Q6: 设备正常使用中突然黑屏软重启（不会回 bootloader），日志出现 "WATCHDOG KILLING SYSTEM PROCESS"——Watchdog 的超时阈值是多少？怎么定位是哪条线程卡住的？**

Watchdog 是 `system_server` 内置看门狗：受监控线程必须定期推进 Handler 检查或响应同步监控；超时后生成诊断并按构建与配置决定是否杀掉 system_server。默认 60 秒、debug 构建 10 秒是该源码基线的常见阈值，设备配置会影响实际行为。

1. **监控对象**：foreground、main、ui、io、display、animation、surface animation 七个关键线程的 HandlerChecker，外加所有 Binder 线程的存活监控；
2. **两级动作**：超过一半时限（约 30 秒）先 dump 全进程栈写入 dropbox（tag `pre_watchdog`）；满 60 秒打印 kill 日志后杀进程；
3. **定位手法**：日志 "Blocked in handler on <线程名> (...)" 或 "Blocked in monitor <类名> ..." 指明卡住的检查项；紧随的 WatchdogDiagnostics 打印阻塞线程的 Java 栈，"waiting to lock <0x...> held by thread N" 直接给出锁归属；
4. **工具**：`dumpsys watchdog` 看当前注册与阻塞状态；dropbox 按 `watchdog`/`pre_watchdog` 检索历史；
5. **防循环**：非 user 构建可配 `framework_watchdog.fatal_count`/`fatal_window.second`，窗口内反复超时进入专门的循环处理；挂载 debugger 期间不杀。

**Q7: system_server 崩溃后，系统靠什么恢复？**

恢复链分三步：

1. Zygote 的 SIGCHLD 处理路径 `waitpid()` 匹配到 system_server 的 pid（`gSystemServerPid`），确认后杀死 Zygote 自身；
2. Zygote 是 init 管理的服务，退出后由 init 的服务监督链路重启；
3. 新 Zygote 重新预加载并 `forkSystemServer()`，整个 Java 框架重建。

用户感知是"界面闪一下回到桌面"，代价是全部 Java 系统服务的运行状态丢失，所有应用进程被连带终止——每个由 Zygote fork 出的子进程都设置了父进程死亡信号（PDEATHSIG），Zygote 一死即收到 SIGKILL。

排查要点：先确认"是谁死了"——`ps -A -o PID,PPID,NAME` 看 system_server 的父进程是否指向 Zygote、Zygote 是否换了新 pid；Zygote socket 只解释应用进程的创建请求，与 system_server 的崩溃恢复无关。

**Q8: 在 system_server 里写代码和读代码各有哪些纪律？**

主线程纪律：system_server 主线程承担 SystemServer 启动控制流及投递到其 Looper 的工作，但系统服务也可拥有独立 HandlerThread、线程池和 Binder 线程；不能说所有服务消息都跑在主线程。主线程上的同步 Binder、磁盘 I/O 或长计算仍会阻塞它依赖的启动/服务路径，因此要检查调用线程、超时和锁依赖。读码时，任务与 Activity 生命周期编排主要看 ATMS（wm 包），进程管理等看 AMS（am 包）。

运行期重启判定：`sys.boot_completed` 已置位后的 system_server 重启视为 runtime restart（soft reboot），很多服务走精简初始化路径；缺陷是"开机完成前崩溃过一次再重启"时 mRuntimeRestart 仍为 false（源码 TODO 亦承认），据此做条件初始化会踩坑。

收束：判断一段 system_server 代码的行为是否合法，先问三个问题——它跑在哪个线程、是否假设冷启动、该逻辑属于 AMS 还是 ATMS。

**Q9: AMS 的双锁模型（`mGlobalLock` 与 `mProcLock`）如何工作？为什么 OOM adj 全量更新仍要同时持两把锁？**

Android 12 起 AMS 持有两把锁：`mGlobalLock`（即 `ActivityManagerService.this`）保护 Service、Provider、Broadcast 等核心组件状态，`mProcLock`（`ENABLE_PROC_LOCK = true` 时为独立的 `ActivityManagerProcLock` 对象）保护逐步迁入的进程管理状态；需要两把锁时获取顺序固定为 `mGlobalLock` → `mProcLock`，反向获取会形成 AB-BA 死锁。OOM adj 全量更新既要读组件关系又要写进程状态，所以 `updateOomAdjLocked()` 在持全局锁后再进入 `mProcLock` 调用 `updateOomAdjLSP()`。

- `@CompositeRWLock({"mService", "mProcLock"})` 表达组合读写契约：读取持任一把锁即可，写入需两把（如 `ProcessList.mLruProcesses`）；它是供静态分析使用的契约，不会在运行时创建新的读写锁对象。
- 方法后缀是速记：LOSP（任一锁）、LSP（两把锁）、Locked（全局锁）、LPr（进程锁）；判断时按"注解优先、后缀辅助、调用点复核"，不要为 LSP 臆造英文全称。
- 双锁扩大了部分读取与独立操作（如时区更新只持 `mProcLock` 遍历 LRU、`CachedAppOptimizer` 的冻结/压缩队列）的并发空间，但没有消除大临界区；`ThreadPriorityBooster` 只把持锁线程的 nice 提升到 `THREAD_PRIORITY_FOREGROUND` 并在退出最外层临界区时恢复，不等于 `SCHED_FIFO` 实时调度。
- 观测：Android 17 定义 `big_locks` 类别的 `ams_lock_acquire/held`、`proc_lock_acquire/held` 事件（受 `perfettoSdkTracingV3()` 特性控制）；通用证据仍是 Perfetto 的 `android.monitor_contention`。

**Q10: WindowManagerService 和 SurfaceFlinger 各自负责什么？为什么说它们是"层≠进程"的典型实例？**

WMS 与 SurfaceFlinger 分管显示链路的"策略世界"和"像素世界"，策略与合成解耦，两个角色互不隶属、不能合并进同一个"应用框架进程"标签：

1. **WMS**：运行在 `system_server`，管窗口容器、层级、焦点、配置，产出图层描述；
2. **SurfaceFlinger**：init 启动的独立原生服务进程，收集各应用的图层与缓冲区，借助 CompositionEngine、RenderEngine 和 Composer HAL 在每个 vsync 周期合成上屏。

对排查的意义：判断"界面没动"时两侧都要查——可能是 WMS 侧没有产生布局/层级变化，也可能是 SF 侧没有合成新帧或提交被栅栏卡住；`system_server` 与 SF 通过明确接口协作，一方的卡顿与崩溃不会自动等同于另一方。这也是显示链路"策略端与合成端"分工的直接例证：二者协作却分属两层（WMS 在应用框架层，SF 在原生库与 ART 层，见 Q1）、两进程、两种语言、两个崩溃域。
