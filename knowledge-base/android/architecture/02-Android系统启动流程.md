# Android 系统启动流程

> 学习资料（文章模式沉淀）。主线：从按下开机键到 Launcher 上屏的完整启动链，以及 init、.rc、Zygote、system_server 与应用进程的诞生与恢复机制。源文档：android-internals-wiki §1.1《Android 分层架构、进程模型与线程协作》的启动章节（init 三阶段、Zygote 与 SystemServer 路径按 AOSP `android-17.0.0_r1` 核对）；Boot ROM/Bootloader/内核阶段与 GKI 概览已于 2026-09-23 与官方资料核对。配套架构主题见 [01-Android系统架构.md](./01-Android系统架构.md)。Q 序列即结构，供 atlas 同源直读。

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

理解要点：init 之前的阶段属于"芯片与内核世界"，排查开机问题先分清卡在哪一侧；`system_server` 的诞生是启动链内的一步（不经 socket 请求），后续应用进程才全部走 socket 请求路径（见 Q7）。

**Q2: Android init 进程怎么理解？**

init 是内核启动的第一个用户态进程（PID 1）、所有用户态进程的祖先；它本身不承载业务逻辑，而是"配置驱动的进程管理器 + 系统初始化执行器"。Android 17 中它仍按第一阶段、SELinux 访问控制初始化、第二阶段三步执行。

职责分四块：

1. **分阶段初始化**：第一阶段挂载基础文件系统与早期分区，第二阶段完成 SELinux 之后的完整用户态准备；
2. **解析执行 .rc**：按 Android Init Language 声明的服务与动作拉起各守护进程——Zygote、SurfaceFlinger 都由它启动（见 Q3）；
3. **属性服务**：维护系统属性（`ro.*`、`persist.*` 等）的设置与变更广播；
4. **服务监督与收尸**：作为 PID 1 `waitpid()` 回收子进程；服务退出后按 `.rc` 定义决定是否重启。

理解它的用处：所有"谁负责重启某个服务"的答案最终都落在 init 的服务监督上——system_server 崩溃后 Zygote 自杀，再由 init 重启 Zygote、重新 fork system_server（见 Q6），这条恢复链的管理者就是 init。

**Q3: .rc 文件怎么理解？**

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

**Q4: Zygote 是怎么被拉起的？启动后依次做什么？**

拉起路径：`init.zygote64.rc` 声明服务 → `init.rc` 的 `zygote-start` 触发器执行 `start zygote` → init fork/exec `/system/bin/app_process64 --zygote --start-system-server` → app_process 初始化 ART 运行时 → 进入 `ZygoteInit.main()`。

`ZygoteInit.main()` 的顺序（Android 17）：

1. 未启用延迟预加载时执行 `preload()`：预加载常用类、资源与共享库——这部分内存随后被所有 fork 出的进程共享；
2. 创建 `ZygoteServer`（绑定 init 传入的 zygote socket 与 USAP 池 socket）；
3. 主 Zygote 调用 `forkSystemServer()` 创建 `system_server`；
4. 父进程进入 `runSelectLoop()`，轮询 socket 等待后续应用进程创建请求。

理解要点：`--start-system-server` 参数说明"fork 出 system_server"是主 Zygote 启动流程内的一步，不是后续 socket 请求的结果；init 直接管理的是 Zygote 进程本身，而不是 system_server。

**Q5: system_server 是怎么被创建并启动到"服务就绪"的？**

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

**Q6: system_server 崩溃后，系统靠什么恢复？**

恢复链分三步：

1. Zygote 的 SIGCHLD 处理路径 `waitpid()` 匹配到 system_server 的 pid（`gSystemServerPid`），确认后杀死 Zygote 自身；
2. Zygote 是 init 管理的服务，退出后由 init 的服务监督链路重启；
3. 新 Zygote 重新预加载并 `forkSystemServer()`，整个 Java 框架重建。

用户感知是"界面闪一下回到桌面"，代价是全部 Java 系统服务的运行状态丢失，所有应用进程被连带终止——每个由 Zygote fork 出的子进程都设置了父进程死亡信号（PDEATHSIG），Zygote 一死即收到 SIGKILL。

排查要点：先确认"是谁死了"——`ps -A -o PID,PPID,NAME` 看 system_server 的父进程是否指向 Zygote、Zygote 是否换了新 pid；Zygote socket 只解释应用进程的创建请求，与 system_server 的崩溃恢复无关。

**Q7: 普通应用进程是怎么诞生的？它和 system_server 的诞生路径差在哪？**

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
