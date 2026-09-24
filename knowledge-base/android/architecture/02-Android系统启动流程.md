# Android 系统启动流程

> 学习资料（文章模式沉淀）。主线：从按下开机键到 Launcher 上屏的完整启动链，以及 init、.rc、Zygote、system_server 与应用进程的诞生与恢复机制。源文档：android-internals-wiki §1.1《Android 分层架构、进程模型与线程协作》的启动章节（init 三阶段、Zygote 与 SystemServer 路径按 AOSP `android-17.0.0_r1` 核对）；Boot ROM/Bootloader/内核阶段与 GKI 概览已于 2026-09-23 与官方资料核对；2026-09-23 并入 AAOS13_study《Android 系统启动全流程 源码分析》的机制细节与 AAOS 挂点（init 接力与调度、Service Reap、SELinux 策略装载、Zygote 约束、SystemServer 看护、CarService/CarSystemUI/CarLauncher；源码锚点 commit `abec84ef9`，Android 13 / Automotive）；2026-09-24 并入地基概念深讲（内核与进程、ramdisk、/init 与 execve 变身、fstab、GKI 与 vendor ramdisk、伪文件系统）。配套架构主题见 [01-Android系统架构.md](./01-Android系统架构.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 系统的启动流程？（从点击开机键开始 ）**

完整链路是：Boot ROM → Bootloader → Linux 内核 → init（第一阶段）→ SELinux 初始化 → init（第二阶段）→ 守护进程与 Zygote → system_server → 服务就绪 → Launcher 上屏。逐步看：

1. **Boot ROM**：点击电源键上电复位后，CPU 从芯片内固化的 Boot ROM 开始执行，校验并加载 Bootloader——这是安全启动链（verified boot）的起点；
2. **Bootloader**：初始化内存等最小硬件环境，把内核与 ramdisk 加载进内存，跳转内核入口；
3. **Linux 内核**：初始化调度器、内存管理、驱动，挂载初始 ramdisk，启动第一个用户态进程 **init（PID 1）**——内核世界的终点、Android 世界的起点；
4. **init 第一阶段**：挂载 `/dev`、`/proc`、`/sys` 等基础文件系统，按 fstab 挂载早期分区，然后切向第二阶段；
5. **SELinux 初始化**：加载安全策略，之后系统进入强制访问控制（第一阶段与第二阶段之间）；
6. **init 第二阶段**：启动属性服务，解析全部 `.rc` 配置，按服务定义拉起 servicemanager、vold、surfaceflinger、Zygote 等守护进程；
7. **Zygote**：预加载常用类与资源 → 直接 fork 出 `system_server` → 进入 socket 循环等待应用进程创建请求；
8. **system_server**：进入 `SystemServer.main()`/`run()`，按 Bootstrap/Core/Other/Apex 四组启动 Java 系统服务（AMS/ATMS、PMS、WMS 等），各服务依次 `systemReady()`；
9. **Launcher 启动**：AMS 就绪后经 Zygote socket 创建桌面进程，桌面第一帧上屏即视为开机完成。

理解要点：init 之前的阶段属于"芯片与内核世界"，排查开机问题先分清卡在哪一侧；`system_server` 的诞生是启动链内的一步（不经 socket 请求），后续应用进程才全部走 socket 请求路径。



**Q2: 内核有“进程”这个概念吗？内核为什么能运行？**

进程是内核管理的对象，不是内核存在的前提。 在 Linux 里，所谓进程，本质是内核里的一块数据结构（task_struct：记录 PID、地址空间、打开的文件、调度信息……）加上一份地址空间。内核创建进程，就是在内存里建这样一个结构；销毁进程，就是释放它。

那内核自己是什么？它不是任何进程，它就是被 bootloader 装进内存的一段特权代码 + 它管理的数据结构的总和。 CPU 在特权模式（ARM 上的 EL1/EL2）下直接执行它的指令——不需要“进程”这个载体。开机时连调度器都没有，谈不上“谁在运行内核”：就是 CPU 一条条顺序执行内核指令。

初始化顺序：

1. Bootloader 把内核镜像载入内存，把 CPU 的程序计数器（PC）设到内核入口，跳过去；
2. 内核入口是一小段汇编：建立临时页表、打开 MMU（虚拟内存）、清空 BSS 段、设好栈——把自己变成"可以跑 C 代码"的环境；
3. 进入 C 函数 `start_kernel()`：初始化内存管理、调度器、中断、驱动模型……每初始化完一个子系统，就多一块可用能力；
4. 初始化尾声创建最早的两个特殊内核线程：kthreadd（PID 2，之后所有内核线程的祖先）和 kernel_init（PID 1）；
5. 调度器接管，这些线程才开始被调度运行。

内核线程与用户进程的边界：内核线程只有内核态身份（task_struct 里的地址空间指针为空），永不回落用户态，只执行内核代码（ksoftirqd 软中断处理、kworker 工作队列）——"任务"这个概念在内核里先于"用户进程"存在。

**Q3: Android init 进程怎么理解？**

init 是内核启动的第一个用户态进程（PID 1）、所有用户态进程的祖先；它本身不承载业务逻辑，而是"配置驱动的进程管理器 + 系统初始化执行器"。Android 17 中它仍按第一阶段、SELinux 访问控制初始化、第二阶段三步执行。

职责分四块：

1. **分阶段初始化**：第一阶段挂载基础文件系统与早期分区，第二阶段完成 SELinux 之后的完整用户态准备；
2. **解析执行 .rc**：按 Android Init Language 声明的服务与动作拉起各守护进程——Zygote、SurfaceFlinger 都由它启动；
3. **属性服务**：维护系统属性（`ro.*`、`persist.*` 等）的设置与变更广播；
4. **服务监督与收尸**：作为 PID 1 `waitpid()` 回收子进程；服务退出后按 `.rc` 定义决定是否重启。

理解它的用处：所有"谁负责重启某个服务"的答案最终都落在 init 的服务监督上——system_server 崩溃后 Zygote 自杀，再由 init 重启 Zygote、重新 fork system_server，这条恢复链的管理者就是 init。



**Q4: .rc 文件怎么理解？**

`.rc` 文件是用 Android Init Language 写的声明式配置，相当于 init 的"启动脚本 + 服务注册表"：一个 `service` 块声明一个长驻进程（名字、可执行文件、参数与选项），一个 `on <触发器>` 块声明一组要执行的命令。init 第二阶段解析全部 `.rc` 后，按触发器执行动作、按服务定义 fork/exec 进程并监督。

以 Zygote 为例（AOSP `android-17.0.0_r1`，节选）：

```rc
# init.zygote64.rc
service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server --socket-name=zygote
    class main
    socket zygote stream 660 root system
    socket usap_pool_primary stream 660 root system
```

`init.rc` 里再由 `zygote-start` 触发器执行 `start zygote` 把它拉起。三个关键机制：

1. **socket 选项**：由 init 代为创建 Unix domain socket，服务启动时继承 fd——Zygote 接收应用创建请求的 socket 就这么来的，权限（660 root system）也在这里定义；
2. **onrestart**：声明本服务重启时要执行的命令（如连带重启依赖服务）；
3. **class 分组**：`class main` 之类的分组让 init 能整组启停。

理解要点：`.rc` 把"启动哪些进程、怎么启动、崩了怎么办"全部声明化，init 只是执行器；分析开机耗时与进程拉起顺序时，`.rc` 是第一手材料。



**Q5: Zygote 是怎么被拉起的？启动后依次做什么？**

拉起路径：`init.zygote64.rc` 声明服务 → `init.rc` 的 `zygote-start` 触发器执行 `start zygote` → init fork/exec `/system/bin/app_process64 --zygote --start-system-server` → app_process 初始化 ART 运行时 → 进入 `ZygoteInit.main()`。

`ZygoteInit.main()` 的顺序（Android 17）：

1. 未启用延迟预加载时执行 `preload()`：预加载常用类、资源与共享库——这部分内存随后被所有 fork 出的进程共享；
2. 创建 `ZygoteServer`（绑定 init 传入的 zygote socket 与 USAP 池 socket）；
3. 主 Zygote 调用 `forkSystemServer()` 创建 `system_server`；
4. 父进程进入 `runSelectLoop()`，轮询 socket 等待后续应用进程创建请求。

理解要点：`--start-system-server` 参数说明"fork 出 system_server"是主 Zygote 启动流程内的一步，不是后续 socket 请求的结果；init 直接管理的是 Zygote 进程本身，而不是 system_server。



**Q6: system_server 是怎么被创建并启动到"服务就绪"的？**

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



**Q7: system_server 崩溃后，系统靠什么恢复？**

恢复链分三步：

1. Zygote 的 SIGCHLD 处理路径 `waitpid()` 匹配到 system_server 的 pid（`gSystemServerPid`），确认后杀死 Zygote 自身；
2. Zygote 是 init 管理的服务，退出后由 init 的服务监督链路重启；
3. 新 Zygote 重新预加载并 `forkSystemServer()`，整个 Java 框架重建。

用户感知是"界面闪一下回到桌面"，代价是全部 Java 系统服务的运行状态丢失，所有应用进程被连带终止——每个由 Zygote fork 出的子进程都设置了父进程死亡信号（PDEATHSIG），Zygote 一死即收到 SIGKILL。

排查要点：先确认"是谁死了"——`ps -A -o PID,PPID,NAME` 看 system_server 的父进程是否指向 Zygote、Zygote 是否换了新 pid；Zygote socket 只解释应用进程的创建请求，与 system_server 的崩溃恢复无关。



**Q8: 普通应用进程是怎么诞生的？它和 system_server 的诞生路径差在哪？**

普通应用冷启动路径：

1. 桌面点击，经 Binder 请求 `system_server` 的组件管理服务（ATMS/AMS）；
2. `ProcessList` 收集 UID/GID、targetSdk、SELinux seInfo、ABI、数据目录、入口类 `android.app.ActivityThread` 等参数；
3. `Process.start()` → `ZygoteProcess.startViaZygote()` 把参数编码成 Zygote 命令写入 LocalSocket；
4. Zygote 侧 `runSelectLoop()` 收到连接，`ZygoteConnection.processCommand()` 读取 peer credentials、校验参数；
5. fork 出子进程并 specialize（降权到应用 UID/GID、SELinux 域、seccomp）；
6. 子进程进入 `ActivityThread.main()`：`Looper.prepareMainLooper()` 后 `attach(false, ...)` 经 Binder 回连 `system_server` 完成 `bindApplication`；
7. 父进程把 PID 返回调用方。

与 system_server 的差别：system_server 由主 Zygote 在启动流程里用 `forkSystemServer()` 一步直接创建（参数 `--start-system-server`），不经 socket 请求；普通应用全部走 socket 请求路径。USAP（Unspecialized App Process）池只是把 specialize 提前到"预备进程"，改变不了路径归属，失败时回退主 socket。

排查边界：拿到 PID 只说明 Zygote/USAP 侧创建完成，`bindApplication`、组件生命周期、首帧都是后面的事——发起进程启动、返回 PID、attach 完成三个时间点要分开取证。



**Q9: init 为什么要在启动中途 execv 自己两次？三个阶段各自做什么？**

init 是同一个二进制以三个不同进程映像接力：第一阶段在 ramdisk 上搭最小环境，之后 execv 切入 selinux_setup 镜像装载策略，再 execv 切入 second_stage 常驻形态——中间两次 exec 是因为 SELinux 域转换只发生在 exec 时刻，这是把"同一程序不同阶段需要不同信任级别"交由内核保证的做法。

链路（Android 13 批注）：

1. **FirstStageMain**：挂载 `/dev`、`/proc`、`/sys` 与设备节点，LoadKernelModules 加载 vendor 内核模块，DoFirstStageMount 按 fstab 挂载 system/vendor 等只读分区；
2. **execv("selinux_setup") → SetupSelinux**：合成并装载 sepolicy、完成域切换；
3. **execv("second_stage") → SecondStageMain**：PropertyInit 与 SelinuxRestoreContext，建立 epoll、signalfd、属性 socket 三路事件源，解析 rc 后进入永不返回的主循环。

边界：三个阶段是三个不同进程映像，第一阶段的全局变量与静态状态不会带到第二阶段，跨阶段传数据只能靠环境变量（如 `INIT_AVB_VERSION`）或文件。设计取舍：阶段间需要硬安全边界时才值得 exec 接力；纯逻辑分阶段用函数调用更简单，exec 反而增加调试成本。



**Q10: init 第二阶段的主循环怎么运转？为什么每轮只执行一条命令？**

第二阶段的 init 是单线程事件泵——epoll、signalfd、属性 socket 三路事件源汇入一个主循环，每轮只推进一条 Command，间隙处理 SIGCHLD 收割、属性变化与 ctl 控制消息。每轮一条不是性能设计而是活性设计：防止长命令饿死事件响应，让关机请求、崩溃收割的响应延迟有上界。

机制：

1. LoadBootScripts 解析 init.rc 与各分区 rc，形成 Service 表与 Action 表，先入队 early-init → init → late-init 触发序列，rc 内部再级联 early-fs → post-fs → late-fs → post-fs-data → zygote-start → boot；
2. ActionManager 用事件队列加 Action 表解耦"事件到达"与"动作执行"：事件是三形态（命名触发器、属性变化对、内置动作指针），同一事件可命中多个 Action，配对在锁内、执行在锁外；
3. 内置动作（QueueBuiltinAction）的函数指针既当命令又当配对条件，oneshot 执行完从队列与登记表一并删除；关机路径 ClearQueue 清空队列但故意保留登记表项，保证 shutdown 序列能跑完。

边界：`wait_for_prop` 全局同时只允许一个等待，rc 里连续两条是串行等待；这种分片调度适合看护型常驻进程，吞吐型后台任务不适用。



**Q11: 服务崩溃后 init 的 Reap 裁决按什么顺序处理？哪些情况会放大成整机重启？**

Reap 是服务死亡后的唯一裁决点，五步顺序即语义：收尸（杀残留进程组、清理非 persist 的 socket）→ 违约检查（声明 `reboot_on_failure` 的服务异常退出直接触发重启）→ 后继态裁决（oneshot 且非手动重启置 disabled）→ 重启裁决 → 复活准备（执行 rc 声明的 onrestart 命令、进入 RESTARTING 等主循环重启）。

会放大成整机动作的只有两类：

1. **critical 服务与可更新组件进程**：在 4 分钟崩溃窗口内累计超过 4 次、或开机完成前崩溃——前者 LOG(FATAL) 整机重启进 bootloader，后者置 `sys.init.updatable_crashing` 属性通知 apexd 与 update_verifier；
2. **显式声明 reboot_on_failure** 的服务异常退出。

边界与易错：服务状态用 SVC_* 位标志而非枚举表达（oneshot、disabled、critical 可并存，退出后继态取决于位组合）；oneshot 服务正常退出进 disabled，不会再被 class_start 拉起，须显式 start；stop 后 start 的 RESTART 中间态会跳过置 disabled，否则 start 拉不起来。



**Q12: 启动期 SELinux 策略是怎么装载的？预编译产物不可信时怎么回退？**

Treble 下 system 与 vendor 独立更新，而内核只接受单一二进制策略，SetupSelinux 因此把 system/system_ext/product/vendor/odm/apex 六个来源的 CIL 策略合成、校验、装载，再切 enforcing；装载优先信任 vendor 预编译产物，校验失败回退 secilc 现场编译。

机制：

1. **预编译优先**：用四对 sha256（plat/system_ext/product/apex）确认预编译策略仍与当前各分区一致，含"同有同无"的对称检查——哈希一侧存在一侧缺失也判不一致；
2. **现场编译回退**：secilc 按分区凑输入，vendor 侧必需文件缺失即失败，product/system_ext/odm/apex 侧可选文件缺失清空跳过；产物落 /dev tmpfs 且用后 unlink，排查现场编译问题只能靠日志；
3. **非 Treble 老设备**直接用 monolithic `/sepolicy`；
4. **mapping 版本**取 vendor 声明的 `plat_sepolicy_vers.txt`，让新平台策略以旧版本语义兼容旧 vendor，vendor 不必随平台每次升级重编策略。

两条顺序契约决定生死：`restorecon /dev/selinux` 必须在切 enforcing 之前，enforcing 后没有任何域再持有改这些文件标签的权限；OTA 场景 snapuserd 五步编排——读策略必须发生在杀 snapuserd 之前，否则策略加载后 snapuserd 每读 /system 都触发 AVC 审计，而杀掉之后 /system 彻底不可读。

边界：APEX 可更新策略是增量强化，验签或解包失败一律回退 system 自带版本；userdebug 调试策略是双条件门（`INIT_FORCE_DEBUGGABLE` 环境变量与设备解锁缺一不可），量产锁定设备不存在换策略路径。



**Q13: Zygote 的 fork 模型有哪些硬约束？"zygote 本体没有 Binder"是怎么来的？**

Zygote 是所有应用进程的模板，fork 会原样复制线程与地址空间，所以"fork 时刻必须单线程"是硬约束：preload 期间禁止创建线程、GC 线程在 preFork 时暂停、Binder 线程池推迟到 fork 之后的子进程里（nativeZygoteInit 只在子进程路径调用）——任何在 Zygote 本体起线程或用 Binder 的改动，都会让所有后代进程带上损坏的线程副本。

配套手法：

1. **fd 继承传递**：init 预建监听 socket，fork 时 fd 直接继承，环境变量 `ANDROID_SOCKET_<name>` 只传编号——父子进程间的资源交接靠继承，不需要额外 IPC；无亲缘关系的进程仍需 SCM_RIGHTS；
2. **Runnable 洗栈**：fork 出的子进程背着 Zygote 的完整调用栈，Android 把目标 main 包成 Runnable 沿调用链逐层 return，栈帧退光后才执行，避免进程一生的异常栈都带着 fork 前的噪音；
3. **信任边界收在对端凭据**：应用创建请求的参数裁决基于 socket 对端凭据（SO_PEERCRED），普通进程不能要求特殊参数——本地 socket 的权限模型建立在内核提供的对端凭据上，不信任请求方自述。

边界："模板进程 + N 个派生进程"的架构才适合 fork 模型，差异大的负载（独立工具进程）fork 反而拖累（继承整个 VM）。Android 13 批注还勘误了一处上游过时注释：现行代码用普通 return 退栈，不是历史上的抛异常方式。



**Q14: 主 Zygote 和次 Zygote 怎么分工？preload 与 USAP 池各有什么坑？**

64 位主 Zygote 负责 fork system_server 与 64 位应用；32 位次 Zygote（`--enable-lazy-preload`）只服务 32 位应用，不 fork system_server，且 system_server 启动前会等次 Zygote 就绪，两者互为看门狗。preload 是双刃剑：加进 preloaded-classes 的类被所有进程共享，但开机时间变长；删类则各应用首次加载变慢，不是纯优化。USAP 池开启时禁止并发多 fork，调试器附加场景会退回普通 fork 路径。

机制：次 Zygote 的判定依据是 `ro.product.cpu.abilist` 与自身 abi-list 不一致；`--start-system-server` 只出现在主 Zygote 命令行上；USAP（Unspecialized App Process）预 fork 待命、取用时只补 specialize，失败回退主 socket 路径。

收束：排查"应用启动走了哪条路"先确认三点——设备是否 64/32 双 Zygote、USAP 是否开启、是否处于调试附加场景。



**Q15: system_server 的四波装配顺序为什么改不得？BootPhase 广播解决了什么问题？**

startBootstrapServices → startCoreServices → startOtherServices → startApexServices 四波的顺序是硬依赖（AMS 依赖 PMS、WMS 依赖 AMS/IMS），改顺序直接启动失败；四波之外的弱依赖靠 SystemServiceManager 的 PHASE_* 阶段广播解耦——服务只声明自己在哪个阶段做什么（onBootPhase），不需要知道彼此的启动顺序。一句话：强依赖用排序表达，弱依赖用阶段事件表达。

四波内容（Android 13 批注）：

1. **Bootstrap**：Watchdog → Installer → ATMS → AMS → 电源/显示 → PMS（最重，扫全部分区 APK）→ AMS.setSystemProcess；
2. **Core**：Battery/UsageStats/WebView；
3. **Other**：WMS/IMS/网络/电话/媒体等约百个服务，末尾调 AMS.systemReady 并启动 SystemUI；
4. **Apex**：APEX 内服务最后装配并封板（sealStartedServices），之后再 startService 直接抛异常。

BootPhase 从 PHASE_WAIT_FOR_DEFAULT_DISPLAY(100) 经 200/480/500/520/550/600 逐级广播到 PHASE_BOOT_COMPLETED(1000)。易错：PMS 构造可能超过 Watchdog 心跳，SystemServer 在调 PMS.main 前显式 pauseWatchingCurrentThread、构造完再恢复——新增长耗时初始化若不照做会被 Watchdog 误杀。



**Q16: system_server 的单点风险靠什么兜底？Watchdog 是怎么工作的？**

单进程装下所有服务换来了服务间进程内直调的简单，也把崩溃域合并成一个；兜底是双层——Watchdog 监控各关键线程心跳、超时杀掉 system_server 进程，之后接 init 侧的 critical 崩溃计数兜底（窗口内超 4 次或开机完成前崩溃，整机重启进 bootloader）。

机制与取舍：Watchdog 在 Bootstrap 波最先启动，各关键线程定期喂狗；system_server 被杀后走恢复链（Zygote 自杀 → init 重启 Zygote → 重新 fork）。取舍是"集中式的风险要配自动化的看护"：服务交互极频繁时拆分成本高于崩溃成本，集中加看护更划算；低耦合服务则应拆出去——Android 把部分服务移入 APEX/Mainline 正是反向操作。

边界：调试时反复 kill Zygote 会因 critical 规则把设备直接带回 bootloader，不是 bug。



**Q17: system_server 内部服务之间怎么互相调用？systemReady 回调解决了什么时序问题？**

同进程服务间调用走两条总线：跨进程消费经 ServiceManager.addService 注册 Binder 句柄，进程内消费经 LocalServices.addService 注册 `*Internal` 接口（进程内视图可以加宽方法、减少校验）；对外契约是 Manager 或 `*Internal` 接口而非实现类，其他服务不 import 实现。启动收尾的时序用 systemReady 回调收束——SystemServer 把剩余装配逻辑打包成 Runnable 交给 AMS.systemReady，在"所有服务已就绪"的时点回放：回调内可以安全使用任何服务，AMS 又不需要知道回调里有什么。

systemReady 内部（Android 13 批注）：置 mSystemReady/mProcessesReady → 清理系统更新残留进程 → 执行传入回调 → startPersistentApps → 置 mBooting → startHomeOnAllDisplays 拉起桌面 → 发 USER_STARTED 系列广播；开机完成时 init 收到 `sys.boot_completed=1` 属性触发，闭环回 init 的启动时间线。

边界：同一能力按消费方进程边界提供两套视图的做法只适用于单体进程内的模块化——服务一旦拆进程（APEX 化），就要收敛到 Binder 契约。



**Q18: 在 system_server 里写代码和读代码各有哪些纪律？**

主线程纪律：system_server 主线程跑全部服务的消息，任何阻塞调用（同步 Binder、磁盘 IO）都会放大成整机卡顿，护栏是 `Binder.setWarnOnBlocking(true)` 与 100/200ms 慢消息阈值。读码分流：任务与生命周期逻辑在 ATMS（wm 包），AMS 是进程/内存/Binder 门面——别在 AMS 里找 Activity 启动细节。

运行期重启判定：`sys.boot_completed` 已置位后的 system_server 重启视为 runtime restart（soft reboot），很多服务走精简初始化路径；缺陷是"开机完成前崩溃过一次再重启"时 mRuntimeRestart 仍为 false（源码 TODO 亦承认），据此做条件初始化会踩坑。

收束：判断一段 system_server 代码的行为是否合法，先问三个问题——它跑在哪个线程、是否假设冷启动、该逻辑属于 AMS 还是 ATMS。



**Q19: AAOS 在标准启动链的哪三个挂点接入车机专属层？CarService 是怎么起来的？**

三个挂点：AMS.systemReady 回调里启动框架侧宿主 CarServiceHelperService，由它绑定可更新的 CarService APK；startSystemUi 启动 SystemUI 时经 AppComponentFactory 换成车机依赖图；startHomeOnAllDisplays 把 HOME intent 解析到 CarLauncher。核心取舍是"可更新"——CarService 是普通 APK（com.android.car 进程），可脱离整机 OTA 单独更新，框架与车逻辑的边界落在 ICar Binder 契约上；框架只保留不可变的时序骨架（宿主），演进频繁的领域逻辑装进可更新容器。

CarService 起链路（Android 13 批注）：

1. CarServiceImpl.onCreate → VehicleStub.newVehicleStub() 连接 VHAL，按设备实际注册的 HAL 自适应选择 AidlVehicleStub 或 HidlVehicleStub——分析车辆属性流向前先确认当前设备的 VHAL 版本；
2. new ICarImpl：构造约 30 个车机子服务进 mAllServices 表，init() 先 VHAL 再按表序逐个初始化，依赖顺序由表序表达（与 SystemServer 四波装配同构）；
3. ServiceManager.addService("car_service") 并置 `boot.car_service_created=1`，与 CarServiceHelperService 互持 Binder。

设计与边界：车辆数据是一切车机决策的源头，VehicleDeathRecipient 检测到 VHAL 死亡就 kill 整个 CarService 进程、靠绑定者重新拉起——数据完整性优先于可用性，普通应用服务不宜如此激进。框架侧宿主 CarServiceHelperService（com.android.internal.car 包）不在标注仓库内，其启动时序与绑定重试行为属推断，深入需另查完整源码树。



**Q20: CarSystemUI 和 CarLauncher 是怎么在不 fork 原生代码的前提下完成车机化的？**

CarSystemUI 走"合并构建 + AppComponentFactory 换依赖图"：不 fork 原生源码，而是在 manifest 声明 CarSystemUIAppComponentFactory，进程创建时把 Dagger 根组件替换为车机版（CarGlobalRootComponent/CarWMComponent），原生 SystemUI 的启动编排（SystemUIService → startServicesIfNeeded → Dagger 展开 CoreStartable）原样复用。CarLauncher 是 AMS.startHomeOnAllDisplays 的 HOME 解析落点，用 TaskView 把地图 App（另一个进程的受控任务）嵌进桌面，配 HomeCardModule 装顶部/底部卡片。

机制与易错：

1. **换图定制点**：Dagger 化的代码用根组件替换当主定制点，比继承或复制源码可维护；前提是目标代码已 Dagger 化、组件边界清晰；
2. **TaskView 行为差异**：地图是另一个进程的 Activity，崩溃与焦点行为和普通 View 完全不同，`autoRestartOnCrash=false` 意味着地图崩溃后卡片留白、需用户手动重进；
3. **多用户边界**：CarSystemUIInitializer 只给 system user 注入 RootTaskDisplayAreaOrganizer（副驾屏等按用户隔离）；headless system user 0 的设备上 CarLauncher 不显示地图卡片；
4. **崩溃连锁**：car_service 进程被杀会连带 CarSystemUI/CarLauncher 的依赖（它们经 CarServiceProvider 等待重连），调试时 kill CarService 进程应预期 UI 层短暂异常。

**Q21: ramdisk 是什么？明明有真分区，开机为什么还要一块内存里的临时根文件系统？**

ramdisk 是打包进内存的临时根文件系统：构建系统把 init 二进制、fstab 和少量基础工具打成 cpio 归档塞进 boot 镜像，内核启动时解包到一块内存文件系统（ramfs/tmpfs）上作为最初的根。Linux 里这个最初的根有个专名叫 rootfs，是所有进程根挂载点的原型、不可卸载；它完全活在内存里，重启即消失。

为什么要多此一举——鸡生蛋问题：挂载真正的分区需要挂载程序、fstab 和驱动，这些代码与数据本身得先有个地方住；内核只管机制、不带这些内容，所以必须有一块"随内核一起交付的种子文件系统"。

GKI 时代的布局：boot.img = GKI 内核 + 通用 ramdisk（init、通用 fstab 片段）；vendor_boot.img = vendor ramdisk（vendor 的内核模块、vendor fstab 与 rc 片段）。

**Q22: 内核是怎么启动第一个用户态进程 /init 的？为什么说它不是 fork 出来的？**

不是 fork，是"内核线程 execve 变身"：内核初始化尾声创建的 kernel_init 内核线程（PID 1 此时已存在，但只是没有用户地址空间的内核态任务）在收尾时调用 kernel_execve("/init")——丢弃旧地址空间、装载新 ELF 程序、建立页表与入口栈，回落用户态时执行的就是 init 的 main 函数；exec 失败（找不到 /init、ELF 损坏）则内核 panic，开机失败。

内核按固定顺序寻找第一个用户态程序：`init=` 启动参数指定的路径优先，其次 ramdisk 上的指定命令，再退到 /sbin/init、/etc/init、/bin/init。Android 把自己的 init（源码 system/core/init/）放在 /init 占住第一顺位。它担得起这个位置靠两个细节：静态链接（自带 libc、不依赖分区上的 .so——system 分区挂出来之前动态链接器没有输入）；一个二进制多个身份（按启动参数扮演第一/第二阶段 init、selinux_setup、ueventd、subcontext 执行器，/system/bin/ueventd 就是指向 init 的符号链接）。

收束：fork 是"复制已有进程"，此刻没有任何进程可复制；execve 才是"从无到有进入用户态"的动作。此后 Android 所有进程（zygote、system_server、每个应用）都由 init 一脉 fork/exec 派生——这就是"所有用户态进程的祖先"的由来。

**Q23: fstab 是什么？init 第一阶段怎么按它挂载分区？**

fstab（file system table）是文件系统挂载声明表：纯文本，每行一条规则——把哪个块设备、挂到哪个目录、什么文件系统类型、带什么选项；init 的挂载组件 fs_mgr 按 fs_mgr_flags 关键字行事。fstab 就是"挂载分区"这件事的数据化，init 只是执行器。

```text
# 设备                          挂载点    类型  挂载选项        fs_mgr 标志
/dev/block/by-name/system      /system  ext4  ro,barrier=1    wait,avb=vbmeta,first_stage_logical,logical
/dev/block/by-name/vendor      /vendor  ext4  ro,barrier=1    wait,avb=vbmeta,first_stage_logical,logical
/dev/block/by-name/userdata    /data    f2fs  ...             latemount,encrypted=...,fileencryption=...
```

fs_mgr_flags 关键字决定挂载策略：wait 等设备节点出现再挂；avb= 做 verified boot 校验；first_stage_logical 第一阶段就要处理；latemount 可以等到 post-fs-data 再挂；encrypted 涉及加密卷。

表有两份：第一阶段的精简版打进 ramdisk（彼时只能读 ramdisk），完整版在 vendor 分区（/vendor/etc/fstab.<板级名>）。

**Q24: GKI 时代，为什么内核模块要放在 vendor ramdisk 里而不是编进内核？**

GKI（Generic Kernel Image，通用内核镜像）把内核切成两半：Google 基于 ACK（Android Common Kernel）统一构建的核心内核（不含 SoC 私有驱动）+ 厂商提供的 .ko 可加载模块，两边靠稳定的 KMI（内核模块接口）解耦——核心内核可独立打补丁升级而厂商模块不动；量产落点是 Android 12 起新设备按 GKI 2.0 出货（核心内核 5.10 起）。

模块放进 vendor ramdisk（vendor_boot 分区里厂商附加的第二份 ramdisk）而不是编进内核，是 GKI 哲学的延伸：硬件代码全部外置、厂商自持，核心保持通用。而模块不能等 vendor 分区挂载后再加载，又是一层鸡生蛋：挂载 vendor 分区本身就需要 vendor 的存储/加密驱动——驱动必须住在比分区更早可用的地方。所以第一阶段的 LoadKernelModules 在挂分区之前从 ramdisk 加载这些 .ko，分区才挂得出来。

收束：GKI 内核（无硬件驱动）+ vendor ramdisk 里的模块（硬件驱动）= 一颗能操作这台设备真硬件的内核，拼装发生在 init 第一阶段、任何分区挂载之前。

**Q25: /dev、/proc、/sys 这三个伪文件系统怎么理解？init 为什么要先挂载它们？**

三个都是伪文件系统：目录里的"文件"不占磁盘，是内核数据结构的文件化视图，读一个"文件"等于触发内核现场生成内容；它们分别是设备、进程状态、设备模型拓扑三个窗口。init 第一阶段先挂载它们，是因为后续每一步——找设备节点、读启动参数、控制电源——都依赖窗口先打开。

1. **/dev**：设备节点目录。节点用 mknod 创建、本质是一对主/次设备号，打开它就是把读写路由给对应内核驱动——用户态操作硬件的唯一门牌（/dev/null、/dev/console、/dev/block/by-name/system 都在这）；Android 的 /dev 是 tmpfs、开机全空，由 ueventd 监听内核 uevent 补建全部节点（冷插拔扫描）；
2. **/proc**：进程与内核运行状态的窗口——/proc/<pid>/ 每进程一个目录，meminfo/cpuinfo 报告资源，/proc/sys 是 sysctl 可调参数；对 init 特别重要的是 /proc/cmdline（内核启动参数，androidboot.mode=charger 这类启动模式信息），init 第二阶段读它决定走哪条启动线；
3. **/sys**：内核设备模型的拓扑窗口（kobject 目录树）——/sys/devices 是设备本体、/sys/class 按类聚合、/sys/module 列已加载模块、设备目录下的 uevent 文件用于事件重放、/sys/power/state 写入可触发休眠。

时序：第一阶段由 init 统一挂载，/dev 的节点随后由 ueventd 补齐。

**Q26: 内核把控制权交给 init 的那一刻，系统精确处于什么状态？**

内核态完整可用、用户态只有一个进程——此刻"Android"还不存在，只存在 Linux。逐项清点：

1. **CPU/内核态**：完整可用——调度器、内存管理、驱动模型就绪，GKI 时代 vendor 模块已加载；
2. **进程**：恰好一个——PID 1，刚由 kernel_init 内核线程 execve /init 变身而来；
3. **根文件系统**：刚解包的 ramdisk（内存里），只有 init、fstab、少量工具；
4. **/dev、/proc、/sys**：尚未挂载，三个窗口全关；
5. **真正的系统**：在 system/vendor 分区上，未挂载、未过 AVB 校验；
6. **SELinux**：策略未装载，强制访问控制未生效；
7. **系统属性与配置状态**：全部为零，唯一可读的是 /proc/cmdline 里的启动参数。

收束：这份清点就是 init"建设者"职责的完整清单——它要补的每一项空白（窗口、真根、策略、服务、Java 世界）都对应上面一行。

**Q27: 按下电源键到 Linux 内核开始执行之间发生了什么？Boot ROM 和 Bootloader 各做什么？**

上电复位后 CPU 从芯片内固化的 Boot ROM 开始执行——它初始化最基础的执行环境、从存储加载 Bootloader 并校验其签名；Bootloader 再初始化内存（DRAM）等最小硬件环境、把内核镜像与 ramdisk 载入内存、完成启动镜像校验，最后把 PC 跳到内核入口。此后 CPU 离开芯片厂商代码、进入 Android 世界的第一段代码。

分工：

1. **Boot ROM**：掩膜在 SoC 里、出厂即存在且不可改的只读代码——整个安全启动链的信任根；它只负责认出并加载下一级（Bootloader）；
2. **Bootloader**：厂商实现（U-Boot、ABL 等），职责是"为内核准备一个可运行的内存环境"——初始化 DRAM、从存储读出 boot/vendor_boot 镜像、校验签名（vbmeta）、写好启动参数（cmdline）、跳转内核入口。

边界：Bootloader 不在 AOSP 源码树内、属芯片/厂商私有实现，各芯片 Boot ROM 行为有差异，但"ROM 校验 Bootloader"是 verified boot 链条公认的起点。

**Q28: verified boot（AVB）是怎么保证"启动运行的代码没有被篡改"的？**

AVB 靠一条逐级签名的信任链：芯片 ROM 的内置公钥校验 Bootloader → Bootloader 用 vbmeta 分区里被签名的元数据校验 boot/vendor_boot 等启动镜像的哈希 → 系统起来后 dm-verity 对 system/vendor 等只读分区做运行期逐块校验。任何一级失败都会阻断启动或进入告警状态。

机制：

1. **签名与哈希分离**：vbmeta 分区存放被签名的描述符（各分区的哈希表），签名公钥的根固化在 ROM 或熔丝里，设备出厂即带；
2. **运行期校验**：fstab 里的 avb= 标志让第一阶段挂载时配置 dm-verity——只读分区每读一块就核对树状哈希，盘上内容被篡改会直接表现为读取错误；
3. **解锁状态**：用户解锁 bootloader 后信任链根被替换，设备显示警告并允许刷入未签名镜像——量产锁定设备不存在这条路径。

收束：AVB 的校验对象是静态镜像与只读分区，可写的 /data 不在其内（由 FBE 文件级加密保护）。

**Q29: init 的属性服务是怎么工作的？为什么系统里到处都在用属性？**

属性服务是 init 维护的全局键值对仓库：各分区 prop 文件提供初始值，其他进程经属性 socket 向 init 提交写入请求，init 校验请求方的 SELinux 上下文后写入一块进程间共享的内存区并广播变更——读属性是纯内存读取，写属性必须经过 init。

机制：

1. **类别与语义**：ro.* 开机后只读；persist.* 持久化到 /data、重启保留；init.svc.<名字> 是各服务状态的对外投影；ctl.* 是命令不是状态；
2. **双向作用**：向外，init 把服务状态机外化成 init.svc.* 供任何进程免特权读取；向内，属性变化触发 rc 动作（`on property:xxx=yyy`）——组件之间不互相调用、靠"设属性 → 触发动作"编排启动时序；
3. **典型闭环**：system_server 装配完成后置 sys.boot_completed=1，监听该属性的系统组件与测试框架由此得知开机完成。

边界：写权限由 SELinux 精确控制到"哪个域能写哪个前缀"；属性有长度与数量上限，不适合传大块数据。

**Q30: init 是怎么把一个服务进程拉起来的？Service::Start 里有哪些容易忽略的细节？**

每个服务由 init fork 出子进程再 exec 目标二进制；fork 之前 init 把服务声明的 socket 先创建好、fork 后子进程直接继承 fd；fork 之后父进程建好 cgroup 进程组、经管道写一个字节放行，子进程才 exec。

细节与设计：

1. **fork 前建 socket**：描述符经 fork 继承传递，环境变量 `ANDROID_SOCKET_<名字>` 只传 fd 编号——进程树内的资源交接靠继承，是零拷贝通道；Zygote 接收应用创建请求的 socket 就是这样到手的；
2. **管道握手**：fork 后父子有严格初始化依赖（子进程 exec 前必须已进 cgroup），用"父写一个字节、子读到才继续"表达顺序约束，比轮询或延时可靠；
3. **exec 之后**：服务状态经 init.svc.<名字> 属性汇报，退出后进入 Reap 裁决流程。

边界："继承优于显式传输"只适用于有亲缘关系且 fork 顺序明确的进程树；无亲缘进程间传 fd 要走 SCM_RIGHTS。

**Q31: Zygote 的 preload 到底预加载了哪些东西？为什么所有应用进程能直接共享？**

preload 阶段把"每个应用都需要的公共物"只加载一次：preloaded-classes 清单里的常用框架类、系统资源（drawable/color 资源表）、图形相关初始化与 JCA 安全 Provider；此后所有 fork 出的进程靠写时复制物理共享这些页——读到的都是同一份内存，谁写了那一页才真正复制。

机制：

1. **时机**：主 Zygote 在进入 socket 循环前执行 preload；次 Zygote 用 `--enable-lazy-preload` 跳过大头，只为 32 位应用按需补载；
2. **共享原理**：fork 复制页表而不复制物理页，preload 出来的类元数据与资源位图因此成为全体后代共享的只读页——"省时间"与"省内存"两个收益同源于此；
3. **代价**：清单里的每个类都被全体应用背着——加类开机变慢、删类各应用首载变慢，preloaded-classes 的每次调整都是全局权衡。

收束：排查应用首帧慢时，"目标类不在 preload 清单、首次加载要自己付全部成本"是一个常被忽略的取证点。

**Q32: ueventd 是怎么把空的 /dev 填满的？**

ueventd 是 init 同一二进制的另一个形态，职责只有一个：监听内核的 uevent 设备事件，按 /dev/ueventd.rc 及各分区 rc 声明的规则创建设备节点并设置属主与权限——/dev 是 tmpfs、每次开机全空，所有节点都是它补建出来的。

机制：

1. **事件来源**：内核在设备注册或移除时发出 uevent（携带设备路径、主/次设备号、子系统），ueventd 经 netlink socket 接收；
2. **冷插拔**：内核早于 ueventd 启动，启动早期的设备事件已经发完——ueventd 起来后向 /sys 重放一遍事件（coldboot），把错过的事件补齐；
3. **权限规则**：ueventd.rc 每行声明"设备路径 属主 组 权限位"，如 /dev/binder 属 root、组 binder、0660。

边界：节点能建的前提是驱动已注册——遇到"设备节点缺失"先分清是驱动没加载/没匹配，还是 ueventd 没建节点。

**Q33: Zygote 在 Android 进程模型里扮演什么角色？为什么应用进程要用 fork 而不是各自独立启动？**

Zygote 是带完整 ART 运行时和预加载类/资源的模板进程，所有应用进程和 `system_server` 都由它 fork 出来，用"写时复制"换取启动速度和内存共享。init 第二阶段解析 `.rc` 后启动 Zygote；Zygote 完成类与资源预加载、直接 fork 出 `system_server` 后，进入 socket 循环等待后续进程创建请求。

fork 之后父子进程共享未修改的物理页，写入时才真正复制（Copy-on-Write），所以新进程并不携带一份完整内存副本；子进程随后完成 specialize——设置到目标应用的 UID/GID、SELinux 域、seccomp 等安全身份——再进入 `ActivityThread.main()`。选择 fork 而非独立启动的原因：

1. **省时间**：不必每进程重新初始化 ART、加载几千个预加载类；
2. **省内存**：预加载页与未写脏页被所有应用进程共享；
3. **同一起点**：所有进程从一致的运行环境出发。

边界：COW 不等于零成本——后续写入和应用初始化会逐步产生私有页；普通应用的创建请求由 `system_server` 经 Zygote/USAP 本地 socket 发起，而 `system_server` 自己是 Zygote 在进入 socket 循环前一步直接 fork 的，两条路径不同（深挖见 [02-Android系统启动流程.md](./02-Android系统启动流程.md)）。
