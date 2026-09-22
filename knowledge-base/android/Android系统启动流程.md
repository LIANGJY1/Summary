# Android 分层架构、进程模型与线程协作

> 学习资料（文章模式沉淀）。源文档：android-internals-wiki §1.1《Android 分层架构、进程模型与线程协作》（finalized，机制按 AOSP `android-17.0.0_r1` 与 ACK `android17-6.18-2026-06_r6` 核对，2026-09-07 验证）；本笔记为蒸馏版，Q 复盘题依次排列（无章节标题），供 atlas 同源直读。

**Q1: Android 的五层架构怎么理解？这套分层对排查问题的实际价值是什么？**

从上到下：应用、应用框架、原生库与 ART、HAL、Linux 内核

分层按**职责**划分系统组件；而进程与层是多对多的包含关系：一个应用进程同时装着应用代码、应用框架（Framework）客户端代码、Android 运行时（ART）和原生库；`system_server` 进程既运行 Java 系统服务，也加载 JNI 库；SurfaceFlinger 则是独立的显示合成服务进程。HAL 也一样两头下注——Stable AIDL HAL 是独立 Binder 服务进程，存量直通式（passthrough）HIDL HAL 却以共享库形式加载进调用方进程。

分层的价值是**确定排查方向**，而不是给问题贴层标签：面对卡顿、启动慢或硬件访问延迟，先确定工作发生在哪个进程，再确认它通过 Binder、JNI、系统调用还是 HAL 接口进入下一个模块；架构图里的箭头只表示常见接口，不是所有请求都逐层经过（应用可以经 Bionic 直接发起文件系统调用，SurfaceFlinger 可直接与 Composer HAL 和 DRM 显示子系统交互）。进程、接口和调度状态共同构成证据，单看"属于哪一层"不足以定位问题。

**Q2: system_server 是谁创建的？为什么它不走普通应用冷启动时那套 Zygote socket fork 路径？**

system_server 由 Zygote 在 `forkSystemServer()` 一步直接 fork 创建——`ZygoteInit.main()` 在进入 `runSelectLoop()` 之前显式调用它，因此不走普通应用那套 socket 请求路径。背景：init 按第一阶段、SELinux 初始化、第二阶段执行，第二阶段解析 `.rc` 启动 Zygote、SurfaceFlinger 等服务；`ZygoteInit.main()` 的顺序是未启用延迟预加载时 `preload()` → 创建 `ZygoteServer` → `forkSystemServer()` → 父进程进入 `runSelectLoop()`，而 init 通过 `init.zygote64.rc` 直接管理的是 Zygote 进程（`/system/bin/app_process64 --zygote --start-system-server`），普通应用冷启动才走"`system_server` 经 Zygote/USAP socket 请求 fork"。子进程一侧由 `nativeForkSystemServer()` 在 `pid == 0` 分支调用 `SpecializeCommon(..., is_system_server=true, ...)` 完成身份改造（cgroup 与 task profile、补充组与资源限制、seccomp、`setresgid()/setresuid()`、capabilities、SELinux context），再经 `ZygoteInit.zygoteInit()` → `RuntimeInit.applicationInit()` 进入 `SystemServer.main()`。

**Q3: system_server 接收 Binder 事务的线程池是什么时候启动的？依据是哪条调用链？**

早于 `SystemServer.main()`。调用链是：`handleSystemServerProcess()` → `ZygoteInit.zygoteInit()` → `nativeZygoteInit()` → `app_process/app_main.cpp` 的 `AppRuntime::onZygoteInit()` → `ProcessState::self()->startThreadPool()`。也就是说，到达 `SystemServer.main()` 的 system_server 已经具备 ART、Framework JNI、预加载页面和 Binder IPC 线程池；但 System Context、主 Looper 长期循环、`SystemServiceManager` 以及 AMS/PMS/WMS 等服务对象仍要由 `SystemServer.run()` 组织建立——它既不是空进程，也不是服务全量 ready 的进程。

**Q4: SystemServer.main() 本身做了什么？SystemServer.run() 的骨架按哪四段阅读？**

`SystemServer.main()` 只是 `new SystemServer().run()`；`SystemServer` 是 Java 装配总控，不是 system_server 进程本身，也不是 AMS，更不是向 ServiceManager 发布的 Binder 服务。`run()` 的骨架按执行顺序分四段：

1. 设置系统进程规则（Binder 阻塞告警、SQLite/Parcel 策略、Binder 后台调度与最大线程数）。
2. 准备主线程 Looper 与 `SystemServerInitThreadPool`。
3. 加载 `android_servers`、建立 System Context/System UI Context、执行每进程 Mainline 模块初始化。
4. 创建 `SystemServiceManager`，依次进入 `startBootstrapServices()` → `startCoreServices()` → `startOtherServices()` → `startApexServices()`（第四组对应 APEX 可更新系统模块，旧资料常漏）。

这段调用顺序只证明启动分组，不证明所有服务都在主线程串行初始化——组内可用专门线程或 `SystemServerInitThreadPool` 并行。

**Q5: 为什么 Looper.loop() 开始运行不能当作 Framework 全部 ready 的证据？**

`Looper.loop()` 只表示主启动控制流进入长期消息循环。system_server 内部至少有三条互不服从的执行路径：主线程按 `SystemServer.run()` 控制总体顺序并在末尾进入 `Looper.loop()`；Binder 线程池接收远端 transaction，不天然服从主 Looper 顺序；`SystemServerInitThreadPool` 和各服务自有线程承载允许并行的工作。判断启动进度应结合 `SystemServerTiming`/trace tag、具体服务的 `StartService ...` 片段、Binder 线程状态和 Boot Phase/`systemReady()` 事件，而不是只看某线程是否活跃。另注意 `SystemServiceManager` 只是 system_server 内部的 Java 管理对象和 `LocalServices` 条目，负责已纳管服务的启动与生命周期分发，不是读取全局依赖图的自动拓扑排序。

**Q6: 如果 system_server 意外退出，系统的重启链路是怎样的？**

Zygote 的 SIGCHLD 处理路径会 `waitpid()` 匹配 `gSystemServerPid`，确认是 system_server 后杀死 Zygote 自身；Zygote 作为 init 管理的服务，由 init 的服务监督链路（`.rc` 里定义的服务可重启）接管重启，再由新的 Zygote 重新 fork system_server。排查时可把进程树、socket 和死亡重启分开取证：`ps -A -o PID,PPID,NAME,ARGS` 确认 system_server 的父进程指向 Zygote；Zygote socket 只解释后续应用进程的 fork 请求，与 system_server 的诞生无关。

**Q7: ProcessState 里的 DEFAULT_MAX_BINDER_THREADS = 15 到底限制什么？哪些线程不计入这个名额？**

它是通过 `BINDER_SET_MAX_THREADS` 写入驱动的**动态增线程请求上限**——驱动在没有等待线程、没有未完成的增线程请求、已请求线程数低于 `max_threads` 且当前线程已进入 Binder 事件循环等条件同时成立时，才通过 `BR_SPAWN_LOOPER` 请求用户态加线程。不计入的有两类：`ProcessState::startThreadPool()` 启动的池中首个线程，以及手工调用 `IPCThreadState::joinThreadPool()` 的线程。线程池启动后该上限不能缩小。这个常量不能直接换算成"应用该用几个 Binder 线程"的建议——线程太少长事务会阻塞后续请求，太多则增加并发、锁竞争和内存成本，调整前要用 Perfetto 确认调用方是否同步等待、目标进程是否没有空闲 Binder 线程、处理线程到底在等什么。

**Q8: Binder 常说的"一次拷贝"指什么？为什么不能由此推出 Binder 调用端到端一定快？**

"一次拷贝"只描述事务数据本身：驱动把数据从发送方用户空间复制到接收方可映射的 Binder 缓冲区（约 1 MB 减两页的映射区），省去了传统管道/套接字的两次拷贝。但一次同步 Binder 的端到端延迟由整条请求决定：文件描述符转换、对象解析（flat/unflat）、权限检查——包括 SELinux `selinux_binder_transaction()` 对 `BINDER__CALL`（凭据不同还有 `BINDER__IMPERSONATE`）的检查——线程排队与调度、目标服务执行和下游依赖都计入总耗时。源码只能证明这些检查存在，没有同设备同策略同负载的测量就不能给它写固定纳秒开销。

**Q9: JNI 调用的成本来自哪里？优化 JNI 的两个主要方向是什么？**

JNI 不是进程切换，也不会因为进入 C/C++ 就自动发生 Binder 或内核态切换，它是同一进程内 ART 托管环境进入原生函数的 ABI 边界。成本来自调用约定、线程状态转换、引用管理、参数转换、数组/字符串的复制或固定，以及原生函数自身的工作。优化方向：① **减少跨边界次数**——循环里的细粒度调用合并为一次批量调用（同时评估额外内存与延迟）；② **减少数据转换**——大块二进制用 direct buffer 或共享表示，避免反复构造 Java 对象。`@FastNative`/`@CriticalNative` 只适用于满足签名和运行时约束的系统级场景，不能按固定倍数估算收益；空 JNI 基准的纳秒数也代表不了含字符串、数组、锁和 I/O 的业务调用。

**Q10: 图形、相机、音频的大量数据为什么不适合反复塞进 Binder Parcel？实际怎么传？排查这类延迟要分别观察什么？**

Binder Parcel 适合控制命令，不适合高吞吐数据。实际做法是"Binder 传控制命令 + 共享缓冲区传数据"：图形走 BufferQueue、DMA-BUF，相机/音频常用共享内存、快速消息队列（FMQ）或硬件缓冲区，同步信息靠 fence 传递。排查时要把三者分开：控制命令的 Binder 往返可能很快，数据通路却在等传感器/ISP/缓冲区同步栅栏；一次显示提交在 SurfaceFlinger 侧可能很短，而 GPU 或硬件合成器（HWC）的完成时间更晚。追踪不能停在控制命令的回复位置，要继续跟缓冲区生命周期、FMQ、DMA-BUF 和驱动事件。

**Q11: 服务化（binderized）与直通式（passthrough）HIDL HAL 的区别是什么？排查 HAL 延迟时分别怎么追？**

服务化 HIDL HAL 以独立服务进程运行，通常使用 `/dev/hwbinder`；直通式 HIDL HAL 以共享库加载到调用方进程，调用路径中没有独立 HAL 服务进程。排查路径因此不同：服务化实现沿 Binder 调用进入 HAL 进程，检查服务线程、锁、系统调用与同步栅栏；直通式实现留在调用方进程，检查原生调用栈和共享库内部等待。新 HAL 接口已转向 Stable AIDL（需声明 VINTF 稳定性、以 Binder 服务运行），但 Android 17 设备上仍有为兼容旧厂商镜像保留的 HIDL HAL——不能仅凭系统版本假定全部 HAL 已迁移。背景：Project Treble 从 Android 8.0 起约束框架与厂商实现之间的接口，VINTF 清单与稳定 HAL 接口共同决定 `system`/`vendor` 分区是否兼容；Treble 提供只更新系统框架所需的稳定接口，但不保证任意框架与任意厂商实现都能组合。

**Q12: 16 KB 页大小对应用的影响怎么判断？设备页大小怎么读？Google Play 的时间要求是什么？**

判断看代码构成：只含 Java/Kotlin 代码的应用通常不需要为 ELF 对齐改造；包含 NDK 编译的库或经 SDK 间接引入 `.so` 的，要检查 ELF 的 `LOAD` 段对齐，以及 APK 内未压缩原生库的 ZIP 对齐。设备运行时页大小用 `adb shell getconf PAGE_SIZE` 读取，`4096` 为 4 KB、`16384` 为 16 KB。官方公布的启动/功耗收益来自 Google 初始测试且明确说明因设备而异，只能当测试方向。分发要求：目标版本 Android 15（API 35）及以上的应用须支持 64 位设备上的 16 KB 页，2027 年 2 月 1 日起不支持的应用更新无法发布——这是分发兼容要求，不等于所有 Android 15～17 设备都运行在 16 KB 模式。

**Q13: 为什么 Android 版本号不足以确定 ART、Media、Wi-Fi 等模块的实现？分析 Mainline 相关问题要记录什么？**

Mainline 把一部分系统组件封装为 APEX/APK，可经 Google Play 系统更新或合作伙伴 OTA 独立更新（`SystemServer.run()` 也会执行每进程 Mainline 初始化，并在常规服务组后调用 `startApexServices()`）。因此同版本设备上的模块实现可能不同。分析问题时应同时记录构建指纹（build fingerprint）、相关 APEX/APK 版本和复现时间。模块更新虽能改变实现，但仍受稳定 SDK/System API、稳定 C API 或 Stable AIDL 边界约束。同章边界还有：VNDK 从 Android 15 起废弃（新厂商分区不再声明 `ro.vndk.version`），但动态链接器命名空间仍在——`linker_namespaces.cpp` 的 `android_namespace_t::is_accessible()` 仍检查 `allowed_libs_`、搜索路径和 `permitted_paths_`，"VNDK 废弃=命名空间隔离没了"是错误推论。

**Q14: WindowManagerService 与 SurfaceFlinger 各自负责什么？它们为什么能说明"层"和"进程"不能混为一谈？**

WMS 管"窗口的世界"：窗口容器、层级、焦点、配置与策略，运行在 system_server 进程；SurfaceFlinger 管"像素的合成"：接收图层（layer）状态与缓冲区，借助 CompositionEngine、RenderEngine 和 Composer HAL 产出显示输出，是 init 启动的独立原生服务进程。二者通过明确接口协作，既不在同一进程，也不能一起塞进"应用框架进程"这个标签。Android 17 主刷新路径是 `commit(<vsyncId>)`（提交本轮状态并决定是否合成）→ `composite(<vsyncId>)`（进入各显示设备的合成与 present）→ `postComposition`；历史版本的跟踪入口不同（Android 11～12 搜 `onMessageInvalidate`/`onMessageRefresh`，Android 10 及以前搜 `onMessageReceived`/`handleMessageRefresh`），不能把新版本的跟踪名直接套到旧设备上。

**Q15: Perfetto 里 SurfaceFlinger 的 composite 变长，能直接得出 GPU 过载吗？还要检查什么？**

不能。`composite` 变长只说明合成与显示提交路径整体变长，候选原因还包括 HWC 的合成决策变化（更多图层交给 GPU 合成）、显示提交同步栅栏（present fence）等待、RenderEngine、显示模式切换、图层数量变化和驱动等待。正确动作是沿这些分支逐一取证，再对比正常帧的差异；"composite 长=GPU 慢"和"一定是应用绘制慢"都是跳步结论。

**Q16: 普通应用的进程创建请求为什么走 Zygote/USAP 本地 socket？这条源码事实能推出哪些结论、不能推出哪些？**

可证的机制链：`system_server` 只决定"要不要目标进程"并整理参数——`ProcessList` 把 UID/GID、运行时标志、targetSdk、SELinux seInfo、ABI、数据目录、包名和入口类 `android.app.ActivityThread` 交给 `Process.start()`，进入 `ZygoteProcess.startViaZygote()`，把参数编码成换行分隔的 Zygote 命令写入 `LocalSocket`。服务端 socket 由 init 在 `init.zygote64.rc` 里声明（`socket zygote stream 660 root system` 和 `usap_pool_primary`），Zygote 的 `runSelectLoop()` 轮询，连接进 `ZygoteConnection.processCommand()`：读 peer credentials、解析参数、做身份策略检查后执行 fork 与 specialize。USAP 池改变的只是"从哪个预备进程完成 specialize"（受支持、已启用、策略允许且命令形态合适才尝试，失败回退主 socket）。**边界**：源码只能证明 AOSP 现状走本地 socket 而非把 Zygote 做成 Binder 服务；不能推出"Binder 原理上不能承载创建命令"，也不能把"启动更早"或"socket 天然更安全"写成唯一官方原因——安全边界来自 init socket 权限、SELinux 策略、peer credentials、Zygote 参数校验和 native specialize 共同作用。

**Q17: 客户端拿到新进程的 PID 之后，能说明什么、不能说明什么？排查应用"出生点"要区分哪三个时间点？**

PID 返回只说明 Zygote/USAP 侧完成了 fork——不等于 `Application` 已绑定、组件已启动或首帧已绘制。三个要分开的时间点：① `ProcessList` 发起进程启动；② Zygote/USAP 返回 PID；③ 应用进程进入 `ActivityThread.main()` 并通过 Binder attach 到 AMS（之后才有 `bindApplication` 和组件调度）。`ps -A -o PID,PPID,NAME,ARGS` 确认父子关系（应用进程父进程是 Zygote），`dumpsys activity processes` 核对 AMS 视角的进程记录。

**Q18: 给应用加 android:process=":remote" 的实际成本有哪些？什么场景才值得拆进程？**

成本至少包括：多一套进程地址空间、ART 运行时状态、Java 与原生堆、线程栈、主线程消息循环和 `Application` 初始化（多进程会多次走 Application onCreate）；原本的进程内调用可能变成 Binder IPC，带来序列化、线程池与状态同步开销。值得拆的场景有明确的故障或内存边界：不可信插件或需隔离权限的服务；崩溃不应拖垮主界面的独立模块；生命周期清楚、结束后靠杀整进程释放大块原生/图形内存的模块；系统明确提供隔离模型的组件。反之，两个模块高频同步调用、共享大量可变状态时，拆进程通常只增加成本。背景（manifest 规则）：默认每个应用用自己的 Linux UID 和进程，`android:process` 可改变边界——冒号开头（如 `:player`）是应用私有进程，实际名带包名前缀；不以冒号开头的全局进程名只在共享 Linux UID 且签名匹配时才可能跨应用共用（`android:sharedUserId` 自 API 29 废弃，新应用不应依赖）。

**Q19: 为什么 adj 数值表不能当成跨设备的"保活等级"表使用？**

因为它只描述 AOSP 的相对顺序，不是稳定契约：可见、previous 和缓存区间在 Android 17 都有更细的分层策略与功能开关；厂商还能调整进程上限、冻结阈值和 `lmkd` 参数；同一应用在不同设备、不同压力状态下算出的 adj 可能不同。应用侧更不能依赖某个数值承诺存活时间——adj 是系统在内存压力下的回收排序输入，不是给应用的契约。数值体系本身：开发者文档把进程概括为前台/可见/服务/缓存四类，AOSP 执行层用更细的 `adj` 分数（数值越小越不容易被杀）——`FOREGROUND_APP_ADJ=0`、`VISIBLE_APP_ADJ=100` 起、`PERCEPTIBLE_APP_ADJ=200` 起、`SERVICE_ADJ=500`、`HOME_APP_ADJ=600`、`PREVIOUS_APP_ADJ=700` 起、`SERVICE_B_ADJ=800`、缓存区间 `CACHED_APP_MIN_ADJ=900`～`CACHED_APP_MAX_ADJ=999`（Android 17 常量已移到 `com.android.server.am.psc.Constants`）。

**Q20: 进程重要性定级遵循哪三条规则？BroadcastReceiver.onReceive() 返回后在普通线程继续干活，进程还保吗？**

定级遵循三条规则：

- **按最重要组件定级**：进程同时有可见 Activity 和 started service 时，按更高的可见级算，不因服务降级。
- **重要性沿依赖传播**：高优先级进程绑定另一进程的 Service 或正在使用其 ContentProvider 时，被依赖进程获得足以完成请求的保护（受绑定 flag、依赖类型和能力传播规则影响）。
- **回调结束即撤销保护**：`onReceive()` 返回后广播接收器不再活跃，此时靠普通线程续命不可靠；需要可靠完成的任务应交给 JobScheduler、WorkManager 等系统能识别和调度的机制。

另外从 Android 13 起，缓存进程在重新进入活跃状态前可能只得到有限执行时间甚至没有——缓存态要当"可立即停止"处理，不是低优先级后台运行模式。

**Q21: Android 17 计算进程状态输出哪四组结果？各组负责什么？为什么"oom_score_adj 低"推不出"在 top-app cpuset"？**

实现链路：组件管理模块把变化交给 `ProcessStateController` → 触发局部或全量 OOM adjustment → `OomAdjusterImpl.computeOomAdjLSP()` 从最重要条件开始计算 → 依赖遍历修正服务端/Provider 端及可达进程 → 提交后回调更新并同步 `lmkd`。计算输出四组结果，各自负责：

- `adj`：回收优先级，对应 `/proc/<pid>/oom_score_adj` 与 lmkd 进程表。
- `procState`：更细的运行状态，供后台限制、统计、内存采样等策略使用。
- `schedGroup`：决定进程进入哪类 CPU 调度资源组。
- 能力标志：当前可继承或使用的特定能力，Android 17 冻结策略直接检查 CPU 时间能力。

四组各自独立提交：`oom_score_adj` 低只说明回收保护强，CPU 资源看 `schedGroup` 与任务配置，后台权限看 `procState`、能力标志和 App Standby Bucket——排查时必须同时记录。

**Q22: lmkd 和缓存进程冻结器（Freezer）对缓存进程做的是哪两种不同操作？**

`lmkd` **终止**进程、释放其全部资源；冻结器通过 cgroup v2 的 `cgroup.freeze` **暂停**进程中的任务，进程和内存仍然存在（解冻后原地继续）。Android 17 `CachedAppOptimizer.DEFAULT_USE_FREEZER` 为 true，但设备实际启用还需 DeviceConfig 配置、内核与 `libprocessgroup` 支持；`task_profiles.json` 的 `Frozen`/`Unfrozen` 配置最终写入 FreezerState，由内核 `cgroup/freezer.c` 实现。

**Q23: "adj 达到 900 就一定被冻结"错在哪？Android 17 实际的冻结判定要满足什么？**

冻结阈值不是写死的 900。常规默认来自 `ActivityManagerConstants.DEFAULT_FREEZER_CUTOFF_ADJ`：默认为 `CACHED_APP_MIN_ADJ`，启用实验开关 `prototypeAggressiveFreezing()` 时可前移到 `HOME_APP_ADJ`，产品还可用 `freezer_cutoff_adj` 调整。且达到阈值只是必要条件：`OomAdjuster.getFreezePolicy()` 还检查进程是否持有显式或隐式 CPU 时间能力标志；AMS 在功能启用、达阈值且策略允许时才**异步**安排冻结，中间还有防抖（debounce）、待处理消息、Binder 事务和解冻原因。同步 Binder 调用也不是"自动解冻一切正常"——系统会冻结 Binder 接口并处理待处理事务；应用若靠持续 Binder 事务规避冻结，或冻结状态下异步 Binder 缓冲区耗尽，系统可终止进程（`ApplicationExitInfo.REASON_FREEZER` 指 Binder `ioctl`、同步事务或异步缓冲区问题导致的终止，不是普通冻结事件标签）。

**Q24: Perfetto 里进程存在、线程长时间没有 sched_switch 记录，能确认被冻结吗？正确的确认组合是什么？**

不能，这只是线索——线程也可能在睡眠、等锁、等 Binder 或没有任务。确认组合：`dumpsys activity processes` 的已冻结/待冻结状态、adj、procState；目标进程实际 cgroup 的 `cgroup.freeze`/`cgroup.events`；ActivityManager 冻结跟踪事件与调度轨迹；`dumpsys activity exit-info` 的退出 reason/subreason；Binder 与 lmkd 日志。同理，进程轨迹结束也不能单独证明是 lmkd 所为——崩溃、force-stop、用户停止、升级信号都能结束进程。

**Q25: Android 17 新增的 mmd 与 lmkd 的分工是什么？它改变了什么、没改变什么？**

`lmkd` 在内存压力下**选择并终止**较不重要的进程（Android 10+ 默认 PSI 模式：内核统计资源停顿时间，`lmkd` 订阅内存压力阈值，需内核 `CONFIG_PSI=y`；`ProcessList.setOomAdj()` 发送的 `LMK_PROCPRIO` 只是同步优先级，不触发立即杀）。`mmd`（Memory Management Daemon）集中处理 ZRAM 的配置与维护：重压缩、写回、按进程写回和预取——`ZramMaintenance` 经 JobScheduler 在设备空闲且电量不低时调用 `IMmd.doZramMaintenanceAsync()`；`CachedAppOptimizer` 可在缓存进程压缩后经 pidfd 请求按进程写回，重启时预取减少 major fault。它改变的是缓存进程的内存驻留与再启动代价，没有取消 OOM 调整、冻结器或 lmkd。另注意 lmkd 选目标不只看 RSS：压力、PSI 停顿、swap 余量、页面缓存抖动、refault、策略与厂商配置都参与；低内存退出归因也不完整（可能记为 `REASON_SIGNALED` 而非 `REASON_LOW_MEMORY`）。

**Q26: ActivityThread.main() 的关键顺序是什么？"Binder 回调都在主线程执行"错在哪里？**

关键顺序：`Looper.prepareMainLooper()` → `new ActivityThread()` → `attach(false, startSeq)`（经 Binder 向 system_server 完成应用绑定）→ 取 `sMainThreadHandler` → `Looper.loop()`，loop 正常不返回，主线程持续处理消息直到进程退出。背景：`ActivityThread` 是应用进程的调度中枢，不是另一个 `Thread` 对象；应用组件与 View 体系的主要回调（生命周期、输入分发、measure/layout 与显示列表记录、Choreographer 帧回调、主线程 Handler/Executor/`Dispatchers.Main` 任务、主线程直接发起的同步 Binder）都跑在主线程。"Binder 回调都在主线程"是错的：远程 AIDL 调用默认由进程的 Binder 线程池接收，服务代码是否切回主线程取决于组件与实现（所以 Binder 方法、跨进程 ContentProvider 调用要做线程安全）。反过来，主线程**主动发起**同步 Binder 调用时会阻塞等待远端返回，仍是主线程卡顿的常见来源。

**Q27: Looper 空闲时为什么不消耗 CPU？新消息怎么唤醒它？Binder 线程的等待路径一样吗？**

没有到期消息时，Java `MessageQueue` 进入原生层轮询等待：`system/core/libutils/Looper.cpp` 创建 `epoll` 实例和用于唤醒的 `eventfd`，等待时调 `epoll_wait()`，线程处于阻塞睡眠而不是在 Java 层轮询队列。投递消息的线程在新消息改变下一次到期时间时写入 `eventfd` 唤醒 Looper；通过原生 Looper 注册的其他 fd 也由同一轮 `epoll` 发现。Binder 工作线程走**另一条**等待路径——通过 Binder 驱动的 `ioctl` 等事务，不依赖主线程 Looper 的 epoll；Perfetto 里主线程睡在 `epoll_wait`，推不出 Binder 线程也在同样等待。

**Q28: postDelayed() 到期后消息就会准时执行吗？哪些任务不该用主线程 Handler 延迟承载？**

不会准时。`postDelayed()`/`sendMessageAtTime()` 表达的是"到这个时刻后才有资格执行"，到期后仍可能被继续推迟，推迟因素包括：

- 队列前方正在执行的长消息；
- 同步屏障对同步消息的阻挡；
- 线程处于可运行（Runnable）状态，但仍在等待 CPU；
- 进程冻结、省电策略或系统负载；
- 系统时钟和休眠语义。

需要持久化（进程死后仍要执行）、跨进程存活或约束满足后由系统调度的任务，应交给 `AlarmManager`、`JobScheduler` 或 `WorkManager`，不该让主线程 Handler 攥着一个长延迟任务。顺带两个机制边界：`IdleHandler` 在队列空闲时回调，但仍发生在 Looper 线程且系统不预留保证时间——只适合短小、可中断或一次性的初始化，返回 true 保留、false 移除；创建 Handler 应显式指定 Looper（如 `Handler(Looper.getMainLooper())`），无参/隐式绑定当前线程的构造已废弃。

**Q29: Android 17 的 MessageQueue 实现有什么结构性变化？依赖私有字段的代码风险是什么？**

两层选择：**构建时**由 `frameworks/base/core/java/Android.bp` 排除各实现目录、`messagequeue-gen` 选一套源码生成最终的 `android.os.MessageQueue`（源树里 `LegacyMessageQueue`/`CombinedMessageQueue`/`CombinedDeliMessageQueue` 同名文件并存，但不会作为三个公开类同时装入应用进程）；**运行时** `CombinedMessageQueue`/`CombinedDeliMessageQueue` 按 targetSdk 兼容性变更、平台进程身份与功能开关在 legacy 路径和新路径间切换。以 API 37 为目标的应用启用新的并发 MessageQueue 路径，依赖 `mMessages` 等私有字段的反射可能失效；测试判断"队列已空"应使用 `IdlingResource` 等公开同步机制，不要遍历私有链表。

**Q30: Handler、MessageQueue、Looper 三者怎么分工？回调在哪个线程执行由什么决定？**

`Handler` 负责投递消息/Runnable 并在消息取出时分发回调；`MessageQueue` 保存未处理消息并计算下一次唤醒时间；`Looper` 循环取出到期消息，调用对应 Handler 的 `dispatchMessage()`。Looper 通过 `ThreadLocal` 与线程绑定，它不创建线程、不自动切后台线程——**回调在哪个线程执行只取决于 Handler 绑定的 Looper**，与投递发生在哪个线程无关。

**Q31: 主线程与 RenderThread 在一帧里各负责什么？两者的交接点在哪、同步关系到底是"提交后就自由"还是"等整帧 GPU"？**

硬件加速窗口的绘制不是全在主线程：主线程执行动画与 View 回调、measure/layout、遍历 View 树记录显示列表、把帧状态同步给渲染管线；RenderThread 消费渲染节点与显示列表、批处理并提交 GPU 工作、管理 HWUI 渲染上下文、执行部分可脱离主线程的属性动画——`RenderThread::getInstance()` 按需创建，`threadLoop()` 把 nice 值调到 `PRIORITY_DISPLAY`，普通应用的 RenderThread 不是 `SCHED_FIFO` 实时线程。交接链：主线程经 `ThreadedRenderer` → `HardwareRenderer` → `RenderProxy` 调 `DrawFrameTask::drawFrame()`，后者把任务投给 RenderThread 并 `postAndWait()` 等待。RenderThread 执行 `run()` 时先同步帧状态，**满足条件即可在提交绘制前解除主线程等待**；若纹理准备等工作要求继续同步，则稍后解除。所以两个极端说法都不对：不是"主线程提交后立即自由"，也不是"主线程要等 GPU 画完整帧"。Perfetto 里对应三种典型：主线程长（输入/业务/布局/显示列表记录是瓶颈）、RenderThread 长（渲染准备、驱动或 GPU 压力）、主线程在同步点等 RenderThread（继续看 RenderThread 当时在运行、等 CPU、等锁还是等图形资源）。软件渲染窗口不走这条路径，但同进程其他硬件加速窗口仍可能创建 RenderThread，"进程里一定没有 RenderThread"不成立。

**Q32: Perfetto 排查一帧卡顿的推进顺序是什么？**

推进顺序：

1. 从 Perfetto 帧时间线（FrameTimeline）或对应帧事件确认错过的是应用期限还是显示合成期限。
2. 同时查看主线程和 RenderThread，不要只盯 `doFrame`。
3. 对长区间展开线程状态，区分正在使用 CPU（on-CPU）、等待 CPU 还是阻塞。
4. 可运行状态持续很久时，查看 CPU 是否被更高优先级或大量线程占用。
5. 阻塞时沿唤醒事件（wakeup）、futex、Binder、I/O 或锁持有者寻找真正负责唤醒它的线程或事件。
6. 回到源码确认时间片段对应的执行边界，再决定优化业务、并行度还是跨线程协议。

主线程睡在 Looper 的轮询等待里，通常只说明"当前没有到期消息"，不是卡顿证据；关键消息已到期却被前一条消息占用才是。

**Q33: 如何为后台任务选择执行机制？给出典型需求到工具的对应与关键边界。**

选型先判断三个问题：任务是否需要立即完成、是否必须持久化、是否要求串行与线程亲和；先定性质，再对号入座：

| 需求 | 首选工具 | 关键边界 |
|---|---|---|
| 很短的 UI 更新 | 主线程 Handler / 主线程 Executor / `Dispatchers.Main` | 不做阻塞 I/O 与长计算 |
| 与生命周期绑定的异步 | 协程 + `lifecycleScope`/`viewModelScope` | 父任务取消时，子任务也应随之取消 |
| CPU 密集型并行计算 | `Dispatchers.Default` 或有界 Executor | 控制并行度，避免超过设备承受能力 |
| 阻塞式磁盘或网络调用 | `Dispatchers.IO` 或专用有界 Executor | 线程池不消除底层阻塞，只是移出主线程 |
| 带 Looper 的专用线程串行 | `HandlerThread` | 明确所有权并安全退出：`quitSafely()` 处理完到期消息再退出，`quit()` 直接终止；不要在自身线程上 `join()` |
| 约束满足后可靠执行的持久任务 | WorkManager | 调度时刻不精确，普通 Worker 有运行时长限制 |
| 立即运行且用户可感知的长任务 | 前台服务及相应任务 API | 遵守后台启动和通知限制 |

`AsyncTask` 与 `IntentService` 已在 API 30 废弃——前者易泄漏 Context、回调错位、取消语义不全，后者受后台执行限制可能中断工作；替代不是固定搭配，按上表需求轴选。

**Q34: WorkManager 的 Worker 为什么必须设计成幂等？它保证什么、不保证什么？**

保证：应用退出、进程重建后任务仍会被**安排**，并在约束满足后尽力执行。不保证：精确的启动时间、业务操作只生效一次——Worker 可能因约束变化、进程终止或重试策略**重复运行**。所以上传、扣减、写远端等操作要设计成重复执行不改最终结果（幂等），或由服务端提供去重键。普通 Worker 还有单次运行时长限制，长任务要按 WorkManager 长任务/前台服务规则设计，不能无限阻塞。立即发生、只需随当前页面存活的任务也别交给它——既增加调度开销，又让"谁管理取消"变模糊。

**Q35: "用了协程代码就自动变快"错在哪？Dispatchers 的选择原则和哪些坑要记住？**

协程用较少线程表达大量可挂起任务，但**阻塞调用仍占用承载它的线程**——`Dispatchers.IO` 上调阻塞式接口照样占住 IO worker；`Dispatchers.Main` 上的协程仍在主线程跑。调度器要与工作类型匹配：Main 短 UI、Default CPU 密集、IO 阻塞 I/O、专用调度器做线程亲和/资源隔离/严格并发上限。坑：不要依赖 Default/IO 的具体线程数（随版本与运行环境调整）；协程挂起后可能换工作线程续跑，普通 `ThreadLocal` 不能跨挂起点保留上下文，需要 `ThreadLocal.asContextElement()`；结构化并发要求每个任务有明确 scope（页面/ViewModel/服务持有），生命周期结束取消才从父传到子——只看任务在哪条线程，判断不了谁负责管理和取消它。

**Q36: Process.setThreadPriority() 调整的是什么？数值方向如何？"优先级设得越高越快"错在哪？**

它调整 Linux 的 nice 值（普通线程参与 CPU 调度的优先级输入），比 `Thread.setPriority()` 更贴近 Android/Linux 调度语义。常见常量：`THREAD_PRIORITY_DEFAULT=0`、`BACKGROUND=10`、`FOREGROUND=-2`、`DISPLAY=-4`、`URGENT_DISPLAY=-8`、`AUDIO=-16`——**数值越小优先级越高**。但 nice 只是调度输入之一：内核公平调度类（ACK 的 `kernel/sched/fair.c` 用 EEVDF，按调度资格与虚拟截止时间选任务）不把 CPU 切成固定份额；系统还有任务配置、cgroup、cpuset、uclamp、温控和实时负载在起作用。滥用高优先级会让其他关键线程变慢——它是表达相对重要性的手段，不是提速开关。

**Q37: 为什么实时调度（SCHED_FIFO/SCHED_RR）和手动绑核不能当常规优化手段？**

`SCHED_FIFO`/`SCHED_RR` 绕过公平调度，配置不当可能让主线程、系统服务甚至关键内核工作长期得不到 CPU；设置实时策略通常还需要系统权限和受约束的系统组件，不是第三方应用的通用开关。手动把 RenderThread 或业务线程绑到"大核"也不可移植——SoC 拓扑、能效模型、温控状态和厂商策略各不相同，固定亲和性可能更慢更耗电。正确路径：缩短关键路径、控制并行度，经系统调度器和 ADPF 等公开机制表达性能需求。同理"前台组必跑大核"也不跨设备成立：`task_profiles.json` 的 `SCHED_SP_*` 只是平台默认名，厂商可覆盖，实际 cgroup 层级由内核版本、init 配置与产品配置共同决定。

**Q38: 线程越多吞吐越高吗？增加线程在什么条件下才有效，代价是什么？**

只有任务可并行、资源不是瓶颈且调度成本可接受时加线程才可能提高吞吐。加线程的代价按来源分：

- **内存与元数据**：每线程的原生层元数据与栈地址空间开销。
- **调度**：更多上下文切换，CPU 缓存中的工作集更频繁被替换；更多可运行线程争抢有限 CPU。
- **竞争**：锁与队列竞争，以及高优先级线程等待低优先级线程的优先级反转。
- **下游容量**：不受控的并行 I/O 挤垮存储与服务端。

CPU 密集任务用有界并行度，以目标设备的吞吐、尾延迟、功耗和温度为准；I/O 密集池可大于核数但仍要限并发（fd、连接池、数据库、远端容量）。线程栈的物理占用随运行时与实际访问页面变化，没有"每线程固定 X MB"的通用数。

**Q39: Perfetto 里看到一个很长的线程 slice，下一步怎么做？四种线程状态分别指向什么？**

长 slice 只说明起止间隔久，先展开线程状态分流，四种状态各指向不同问题：

- **Running**：线程正在 CPU 上执行——还要看 slice/调用栈确认在执行什么。
- **Runnable**：可运行但等 CPU——查优先级、CPU 占用、频率与温控判断调度延迟。
- **Sleeping/可中断睡眠**：等消息、Binder、futex、I/O 或条件变量——沿唤醒链找持有者。
- **Uninterruptible Sleep**：不可中断的内核等待——查 `wchan`、I/O 与驱动事件。

定位顺序参考第 10 章的卡顿推进链；每次跨边界（进程/线程/内核）都保存事务 ID、线程 ID、时间范围或符号，避免只凭相邻事件建立因果。

**Q40: Android 17 的虚拟线程处于什么状态？应用该怎么对待它？**

`libcore` 已出现第一版虚拟线程接口（`Thread.ofVirtual()`、`Thread.startVirtualThread()`、`Thread.isVirtual()`、`Executors.newVirtualThreadPerTaskExecutor()`），由 `com.android.libcore.virtual_thread_api_v1` 等发布开关决定是否对外。所以两个极端都不成立："Android 永远没有虚拟线程"不符合 Android 17 源码，"所有 Android 17 设备都能无条件用"同样没有依据。应用以实际 SDK 公开接口、构建开关和目标设备行为为准并准备兼容路径；虚拟线程适合大量阻塞式并发，不会让 CPU 密集突破核数上限，也不替代主线程、Looper、生命周期 scope 和 WorkManager 的持久调度语义——面向多版本，协程和有界 Executor 仍是更稳的基础。

**待补主题**

- Binder 缓冲区容量与事务失败（wiki §1.11）、Binder 调度与冻结事务语义（§1.10）
- AMS 组件调度、adj 计算细节与 system_server 锁模型（§1.12）；cgroup v1/v2 与进程隔离落点（§1.13）
- MessageQueue 锁竞争与并发路径数据结构（§1.8）