# Android 系统启动流程

> 学习资料（文章模式沉淀）。主线：从按下开机键到 Launcher 上屏的完整启动链，以及 init、.rc、Zygote、system_server 与应用进程的诞生与恢复机制。源文档：android-internals-wiki §1.1《Android 分层架构、进程模型与线程协作》的启动章节（init 三阶段、Zygote 与 SystemServer 路径按 AOSP `android-17.0.0_r1` 核对）；Boot ROM/Bootloader/内核阶段与 GKI 概览已于 2026-09-23 与官方资料核对；2026-09-23 并入 AAOS13_study《Android 系统启动全流程 源码分析》的机制细节与 AAOS 挂点（init 接力与调度、Service Reap、SELinux 策略装载、Zygote 约束、SystemServer 看护、CarService/CarSystemUI/CarLauncher；源码锚点 commit `abec84ef9`，Android 13 / Automotive）；2026-09-24 并入地基概念深讲（内核与进程、/init 与 execve 变身、fstab、GKI 与 vendor ramdisk、伪文件系统）；2026-09-25 增补工程实战题（Watchdog 阈值与日志定位、pstore 早期日志、BOOT_COMPLETED 送达条件、Direct Boot 启动视角），关键数字经 AOSP 源码与官方文档核对。配套架构主题见 [01-system-architecture.md](01-system-architecture.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] [tags:系统启动] Android 设备从上电到桌面可交互，启动链经过哪些阶段？**

启动链由 Boot ROM 建立硬件信任根，交给 Bootloader 与 Linux 内核；内核启动 init 后，init 按配置建设用户态环境并拉起 Zygote，Zygote 创建 system_server，框架再按当前用户启动实际 HOME Activity。桌面首帧、动画退出、全局 boot 完成和每用户开机广播是不同里程碑，不能把其中一个当成其余事件的证明。

1. **Boot ROM 与 Bootloader**：SoC 上电后从 ROM 执行；ROM/Bootloader 按设备的 verified boot 链校验后续启动镜像、初始化 DRAM、准备内核启动参数并跳入内核。具体镜像布局由设备实现决定。
2. **Linux 内核与 init 第一阶段**：内核初始化调度、内存和驱动，解包初始 ramdisk（概念见[内核 Q6](../12-platform-native/01-kernel-gki.md)），并以 `/init` 作为第一个用户态程序。init 第一阶段挂载基础伪文件系统、加载早期模块并按 fstab 挂载启动所需分区。
3. **SELinux 与 init 第二阶段**：init 接力加载 SELinux 策略，再以第二阶段常驻形态启动属性服务、解析各分区 `.rc` 并执行启动动作，拉起原生守护进程和 Zygote。
4. **Zygote 与 system_server**：Zygote 初始化 ART 并预加载共享内容；主 Zygote 直接 fork `system_server`，不经过应用创建用的 socket 请求。`system_server` 分波创建系统服务并推进 BootPhase。
5. **AMS ready 与 HOME 选择**：系统服务进入就绪阶段后，AMS 执行 `systemReady()` 收尾；它依据当前用户和设备用户模式决定是否直接请求 HOME。AAOS headless system user 下，桌面通常由座舱前台用户的启动或切换路径请求，不能假定是 user 0。
6. **Activity 创建与屏幕启用**：ATMS 按用户和显示区域解析 `MAIN + CATEGORY_HOME`，再经 Activity 启动链创建 Activity；进程不存在时才经 Zygote 创建应用进程。首批前台 Activity idle 后，ATMS 推进开机收尾；WMS 还需等待窗口策略和 bootanimation 退出，之后通知 SurfaceFlinger 并启用显示与输入。
7. **全局和每用户完成状态**：动画完成后 AMS 才完成相应收尾、发布 `PHASE_BOOT_COMPLETED` 并设置 `sys.boot_completed=1`。UserController 仍按用户状态处理解锁和升级期 PRE_BOOT 接收者，满足条件后才向该用户发送 `BOOT_COMPLETED`。

定位启动故障时，先辨别停在哪一层和哪一个里程碑：进程创建、HOME Activity 启动、首帧、动画退出、全局 boot 属性、用户解锁及用户级广播不能互相替代。此处 Boot ROM/Bootloader/内核依据文档已核对的官方资料；Framework 与 AAOS 行为以 Android 13 `AAOS13_study` 源码锚点 `abec84ef9` 为准。

**Q2: [done] [tags:init,系统启动] init 进程的 main.cpp 是被谁拉起的，如何拉起的？**

内核启动的是 `/init` 可执行文件，不是 `main.cpp` 源码。根据设备布局，`/init` 可能是 ramdisk 中的首阶段程序，也可能指向系统分区中的 `/system/bin/init`；首阶段准备好系统后，通过 `execv` 进入由 `main.cpp` 编译出的 init 程序。

1. **源码如何变成 init：**[Android.bp](</home/liang/Project/MyProject/AAOS13_study/system/core/init/Android.bp:251>) 将 `main.cpp` 编进 `init_second_stage` 模块，并用 `stem: "init"` 将产物命名为 `init`，安装到 `/system/bin/init`。模块还链接 `libinit` 提供实现代码；启动时执行的是二进制文件，不是源码。

2. **ramdisk 布局如何启动：**内核执行 ramdisk 根目录的 `/init`，它通常是由 `first_stage_*.cpp` 构建的 `init_first_stage`，不是 `main.cpp` 编译出的程序。首阶段挂载系统分区后，[调用 `execv`](</home/liang/Project/MyProject/AAOS13_study/system/core/init/first_stage_init.cpp:435>)，以 `selinux_setup` 参数接力启动系统 init。

3. **main.cpp 如何选择阶段：**[main()](</home/liang/Project/MyProject/AAOS13_study/system/core/init/main.cpp:65>) 根据参数路由：`selinux_setup` 进入 SELinux 设置，完成后[再次 `execv`](</home/liang/Project/MyProject/AAOS13_study/system/core/init/selinux.cpp:1098>) 并传入 `second_stage`，进入 `SecondStageMain()`。System-as-root 下，`/init` 可链接到 `/system/bin/init`，首次无阶段参数时进入 `FirstStageMain()`。`execv` 替换程序映像、不创建进程，因此 PID 始终是 1。

**Q3: [tags:系统启动] verified boot（AVB）是怎么保证"启动运行的代码没有被篡改"的？**

Android Verified Boot（AVB）用签名元数据和分区摘要建立从 Bootloader 到只读系统分区的验证链；各级失败后是拒绝启动、进入恢复流程还是显示警告，取决于设备锁定状态、验证模式和产品实现。

机制：

1. **签名与摘要分工**：vbmeta 保存经签名的分区描述符，例如预期哈希或 hashtree 根摘要；校验公钥来自设备信任配置，常由 Bootloader/硬件信任链保护。hashtree 本身不等于整张树都存进 vbmeta；
2. **运行期校验**：fstab 里的 avb= 标志让第一阶段挂载时配置 dm-verity——只读分区每读一块就核对树状哈希，盘上内容被篡改会直接表现为读取错误；
3. **解锁状态**：解锁 Bootloader 会改变设备的验证状态，通常允许启动未通过原厂链验证的镜像并显示警告；这不等于信任根必然被替换。锁定状态下的处理仍取决于验证结果和设备实现。

边界：AVB/dm-verity 保护启动镜像和配置为验证的只读分区，不对可写 `/data` 提供同一种分区完整性校验。FBE 提供文件级静态数据加密，不应描述成 `/data` 的通用完整性保护。

**Q4: [tags:系统启动] 内核是怎么启动第一个用户态进程 /init 的？为什么说它不是 fork 出来的？**

不是 fork，是"内核线程 execve 变身"：内核初始化尾声创建的 kernel_init 内核线程（PID 1 此时已存在，但只是没有用户地址空间的内核态任务）在收尾时调用 kernel_execve("/init")——丢弃旧地址空间、装载新 ELF 程序、建立页表与入口栈，回落用户态时执行的就是 init 的 main 函数；exec 失败（找不到 /init、ELF 损坏）则内核 panic，开机失败。

内核按固定顺序寻找第一个用户态程序：`init=` 启动参数指定的路径优先，其次 ramdisk 上的指定命令，再退到 /sbin/init、/etc/init、/bin/init。Android 把自己的 init（源码 system/core/init/）放在 /init 占住第一顺位。它担得起这个位置靠两个细节：静态链接（自带 libc、不依赖分区上的 .so——system 分区挂出来之前动态链接器没有输入）；一个二进制多个身份（按启动参数扮演第一/第二阶段 init、selinux_setup、ueventd、subcontext 执行器，/system/bin/ueventd 就是指向 init 的符号链接）。

收束：fork 是"复制已有进程"，此刻没有任何进程可复制；execve 才是"从无到有进入用户态"的动作。此后 Android 所有进程（zygote、system_server、每个应用）都由 init 一脉 fork/exec 派生——这就是"所有用户态进程的祖先"的由来。

**Q5: [tags:系统启动] 内核把控制权交给 init 的那一刻，系统精确处于什么状态？**

内核已把控制权交给 `/init`，但 init 第一阶段的工作尚未完成；此刻不能把 init 后续挂载、装载的内容算作内核已完成的状态。逐项清点：

1. **CPU/内核态**：调度器、内存管理和已注册的内核驱动可运行；设备所需 vendor 模块可能由 init 第一阶段稍后加载；
2. **进程**：恰好一个——PID 1，刚由 kernel_init 内核线程 execve /init 变身而来；
3. **根文件系统**：刚解包的 ramdisk（内存里），只有 init、fstab、少量工具；
4. **/dev、/proc、/sys**：尚未挂载，三个窗口全关；
5. **真正的系统**：在 system/vendor 分区上，未挂载、未过 AVB 校验；
6. **SELinux**：策略未装载，强制访问控制未生效；
7. **属性服务**：此时 init 尚未完成第二阶段属性初始化，不能说系统属性“全部为零”；启动参数可从 `/proc/cmdline` 等启动接口读取。

收束：这份清点就是 init"建设者"职责的完整清单——它要补的每一项空白（窗口、真根、策略、服务、Java 世界）都对应上面一行。

**Q6: [tags:系统启动] Android init 进程怎么理解？都做了什么？**

init 是内核启动的第一个用户态进程（PID 1）、所有用户态进程的祖先；它本身不承载业务逻辑，而是"配置驱动的进程管理器 + 系统初始化执行器"。Android 17 中它仍按第一阶段、SELinux 访问控制初始化、第二阶段三步执行。

职责分四块：

1. **分阶段初始化**：第一阶段挂载基础文件系统与早期分区，第二阶段完成 SELinux 之后的完整用户态准备；
2. **解析执行 .rc**：按 Android Init Language 声明的服务与动作拉起各守护进程——Zygote、SurfaceFlinger 都由它启动；
3. **属性服务**：维护系统属性（`ro.*`、`persist.*` 等）的设置与变更广播；
4. **服务监督与收尸**：作为 PID 1 `waitpid()` 回收子进程；服务退出后按 `.rc` 定义决定是否重启。

理解它的用处：所有"谁负责重启某个服务"的答案最终都落在 init 的服务监督上——system_server 崩溃后 Zygote 自杀，再由 init 重启 Zygote、重新 fork system_server，这条恢复链的管理者就是 init。

**Q7: [tags:系统启动] init 为什么要在启动中途 execv 自己两次？三个阶段各自做什么？**

init 是同一个二进制以三个不同进程映像接力：第一阶段在 ramdisk 上搭最小环境，之后 execv 切入 selinux_setup 镜像装载策略，再 execv 切入 second_stage 常驻形态——中间两次 exec 是因为 SELinux 域转换只发生在 exec 时刻，这是把"同一程序不同阶段需要不同信任级别"交由内核保证的做法。

链路（Android 13 批注）：

1. **FirstStageMain**：挂载 `/dev`、`/proc`、`/sys` 与设备节点，LoadKernelModules 加载 vendor 内核模块，DoFirstStageMount 按 fstab 挂载 system/vendor 等只读分区；
2. **execv("selinux_setup") → SetupSelinux**：合成并装载 sepolicy、完成域切换；
3. **execv("second_stage") → SecondStageMain**：PropertyInit 与 SelinuxRestoreContext，建立 epoll、signalfd、属性 socket 三路事件源，解析 rc 后进入永不返回的主循环。

边界：三个阶段是三个不同进程映像，第一阶段的全局变量与静态状态不会带到第二阶段，跨阶段传数据只能靠环境变量（如 `INIT_AVB_VERSION`）或文件。设计取舍：阶段间需要硬安全边界时才值得 exec 接力；纯逻辑分阶段用函数调用更简单，exec 反而增加调试成本。

**Q8: [tags:系统启动] /dev、/proc、/sys 这三个伪文件系统怎么理解？init 为什么要先挂载它们？**

三个都是伪文件系统：它们把内核维护的设备、进程状态和设备模型信息呈现为文件接口，内容不按普通磁盘文件方式存放。init 第一阶段先挂载它们，是因为后续初始化需要读取启动状态、发现设备节点并访问内核设备模型。

1. **/dev**：设备节点目录。字符/块设备节点包含主、次设备号，打开后由内核路由到相应驱动；它是用户态访问设备的一种接口，不是所有硬件访问的唯一入口。Android 的 `/dev` 通常由 tmpfs 承载，ueventd 根据内核事件和规则创建大部分设备节点；init 与其他服务也会按需创建特定节点或链接；
2. **/proc**：进程与内核运行状态的窗口——/proc/<pid>/ 每进程一个目录，meminfo/cpuinfo 报告资源，/proc/sys 是 sysctl 可调参数；对 init 特别重要的是 /proc/cmdline（内核启动参数，androidboot.mode=charger 这类启动模式信息），init 第二阶段读它决定走哪条启动线；
3. **/sys**：内核设备模型的拓扑窗口（kobject 目录树）——/sys/devices 是设备本体、/sys/class 按类聚合、/sys/module 列已加载模块、设备目录下的 uevent 文件用于事件重放、/sys/power/state 写入可触发休眠。

时序：第一阶段由 init 统一挂载，/dev 的节点随后由 ueventd 补齐。

**Q9: [tags:系统启动] GKI 时代，为什么内核模块要放在 vendor ramdisk 里而不是编进内核？**

GKI（Generic Kernel Image，通用内核镜像）把通用内核与符合 KMI（内核模块接口）的厂商模块分开交付，降低通用内核更新与设备专属驱动之间的耦合。设备驱动并非全部都是模块：部分可以内建，部分可在对应分区挂载后加载；只有挂载关键分区前就必须可用的模块，才需要放在早期可读取的 vendor ramdisk 等位置。

需要早于 vendor/system 挂载的模块放进 vendor ramdisk，是启动顺序的要求，不意味着所有厂商驱动都必须外置到这里。否则若某个存储驱动正是挂载相应分区的前置条件，就会形成“先挂分区才能取模块、先有模块才能挂分区”的依赖环；init 第一阶段从 ramdisk 加载该类模块来打破这个环。

因此判断模块放置位置应问“它是否是早期挂载的前置依赖”，而不是笼统按“厂商驱动”归类；`LoadKernelModules` 的具体模块集合由设备配置决定。

**Q10: [tags:系统启动] fstab 是什么？init 第一阶段怎么按它挂载分区？**

fstab（file system table）是文件系统挂载声明表：纯文本，每行一条规则——把哪个块设备、挂到哪个目录、什么文件系统类型、带什么选项；init 的挂载组件 fs_mgr 按 fs_mgr_flags 关键字行事。fstab 就是"挂载分区"这件事的数据化，init 只是执行器。

```text
# 设备                          挂载点    类型  挂载选项        fs_mgr 标志
/dev/block/by-name/system      /system  ext4  ro,barrier=1    wait,avb=vbmeta,first_stage_logical,logical
/dev/block/by-name/vendor      /vendor  ext4  ro,barrier=1    wait,avb=vbmeta,first_stage_logical,logical
/dev/block/by-name/userdata    /data    f2fs  ...             latemount,encrypted=...,fileencryption=...
```

fs_mgr_flags 关键字决定挂载策略：wait 等设备节点出现再挂；avb= 做 verified boot 校验；first_stage_logical 第一阶段就要处理；latemount 可以等到 post-fs-data 再挂；encrypted 涉及加密卷。

表有两份：第一阶段的精简版打进 ramdisk（彼时只能读 ramdisk），完整版在 vendor 分区（/vendor/etc/fstab.<板级名>）。

**Q11: 启动期 SELinux 策略是怎么装载的？预编译产物不可信时怎么回退？**

Treble 下 system 与 vendor 独立更新，而内核只接受单一二进制策略，SetupSelinux 因此把 system/system_ext/product/vendor/odm/apex 六个来源的 CIL 策略合成、校验、装载，再切 enforcing；装载优先信任 vendor 预编译产物，校验失败回退 secilc 现场编译。

机制：

1. **预编译优先**：用四对 sha256（plat/system_ext/product/apex）确认预编译策略仍与当前各分区一致，含"同有同无"的对称检查——哈希一侧存在一侧缺失也判不一致；
2. **现场编译回退**：secilc 按分区凑输入，vendor 侧必需文件缺失即失败，product/system_ext/odm/apex 侧可选文件缺失清空跳过；产物落 /dev tmpfs 且用后 unlink，排查现场编译问题只能靠日志；
3. **非 Treble 老设备**直接用 monolithic `/sepolicy`；
4. **mapping 版本**取 vendor 声明的 `plat_sepolicy_vers.txt`，让新平台策略以旧版本语义兼容旧 vendor，vendor 不必随平台每次升级重编策略。

两条顺序契约决定生死：`restorecon /dev/selinux` 必须在切 enforcing 之前，enforcing 后没有任何域再持有改这些文件标签的权限；OTA 场景 snapuserd 五步编排——读策略必须发生在杀 snapuserd 之前，否则策略加载后 snapuserd 每读 /system 都触发 AVC 审计，而杀掉之后 /system 彻底不可读。

边界：APEX 可更新策略是增量强化，验签或解包失败一律回退 system 自带版本；userdebug 调试策略是双条件门（`INIT_FORCE_DEBUGGABLE` 环境变量与设备解锁缺一不可），量产锁定设备不存在换策略路径。

**Q12: init 的属性服务是怎么工作的？为什么系统里到处都在用属性？**

属性服务是 init 维护的全局键值对仓库：各分区 prop 文件提供初始值，其他进程经属性 socket 向 init 提交写入请求，init 校验请求方的 SELinux 上下文后写入一块进程间共享的内存区并广播变更——读属性是纯内存读取，写属性必须经过 init。

机制：

1. **类别与语义**：ro.* 开机后只读；persist.* 持久化到 /data、重启保留；init.svc.<名字> 是各服务状态的对外投影；ctl.* 是命令不是状态；
2. **双向作用**：向外，init 把服务状态机外化成 init.svc.* 供任何进程免特权读取；向内，属性变化触发 rc 动作（`on property:xxx=yyy`）——组件之间不互相调用、靠"设属性 → 触发动作"编排启动时序；
3. **典型闭环**：system_server 装配完成后置 sys.boot_completed=1，监听该属性的系统组件与测试框架由此得知开机完成。

边界：写权限由 SELinux 精确控制到"哪个域能写哪个前缀"；属性有长度与数量上限，不适合传大块数据。

**Q13: .rc 文件怎么理解？**

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

**Q14: init 第二阶段的主循环怎么运转？为什么每轮只执行一条命令？**

第二阶段的 init 是单线程事件泵——epoll、signalfd、属性 socket 三路事件源汇入一个主循环，每轮只推进一条 Command，间隙处理 SIGCHLD 收割、属性变化与 ctl 控制消息。每轮一条不是性能设计而是活性设计：防止长命令饿死事件响应，让关机请求、崩溃收割的响应延迟有上界。

机制：

1. LoadBootScripts 解析 init.rc 与各分区 rc，形成 Service 表与 Action 表，先入队 early-init → init → late-init 触发序列，rc 内部再级联 early-fs → post-fs → late-fs → post-fs-data → zygote-start → boot；
2. ActionManager 用事件队列加 Action 表解耦"事件到达"与"动作执行"：事件是三形态（命名触发器、属性变化对、内置动作指针），同一事件可命中多个 Action，配对在锁内、执行在锁外；
3. 内置动作（QueueBuiltinAction）的函数指针既当命令又当配对条件，oneshot 执行完从队列与登记表一并删除；关机路径 ClearQueue 清空队列但故意保留登记表项，保证 shutdown 序列能跑完。

边界：`wait_for_prop` 全局同时只允许一个等待，rc 里连续两条是串行等待；这种分片调度适合看护型常驻进程，吞吐型后台任务不适用。

**Q15: ueventd 是怎么把空的 /dev 填满的？**

ueventd 是 init 同一二进制的另一种运行形态，主要职责是监听内核 uevent，并按规则创建和配置大部分设备节点。Android 的 `/dev` 通常由 tmpfs 承载，但并非每个节点都只能由 ueventd 创建，启动脚本和其他系统服务也可建立特定节点或符号链接。

机制：

1. **事件来源**：内核在设备注册或移除时发出 uevent（携带设备路径、主/次设备号、子系统），ueventd 经 netlink socket 接收；
2. **冷插拔**：内核早于 ueventd 启动，启动早期的设备事件已经发完——ueventd 起来后向 /sys 重放一遍事件（coldboot），把错过的事件补齐；
3. **权限规则**：ueventd.rc 每行声明"设备路径 属主 组 权限位"，如 /dev/binder 属 root、组 binder、0660。

边界：节点能建的前提是驱动已注册——遇到"设备节点缺失"先分清是驱动没加载/没匹配，还是 ueventd 没建节点。

**Q16: init 是怎么把一个服务进程拉起来的？Service::Start 里有哪些容易忽略的细节？**

每个服务由 init fork 出子进程再 exec 目标二进制；fork 之前 init 把服务声明的 socket 先创建好、fork 后子进程直接继承 fd；fork 之后父进程建好 cgroup 进程组、经管道写一个字节放行，子进程才 exec。

细节与设计：

1. **fork 前建 socket**：描述符经 fork 继承传递，环境变量 `ANDROID_SOCKET_<名字>` 只传 fd 编号——进程树内的资源交接靠继承，是零拷贝通道；Zygote 接收应用创建请求的 socket 就是这样到手的；
2. **管道握手**：fork 后父子有严格初始化依赖（子进程 exec 前必须已进 cgroup），用"父写一个字节、子读到才继续"表达顺序约束，比轮询或延时可靠；
3. **exec 之后**：服务状态经 init.svc.<名字> 属性汇报，退出后进入 Reap 裁决流程。

边界："继承优于显式传输"只适用于有亲缘关系且 fork 顺序明确的进程树；无亲缘进程间传 fd 要走 SCM_RIGHTS。

**Q17: init.rc 的 service 块还有哪些关键选项？class_start/class_stop/class_reset 有什么区别？**

除 `service`/`socket`/`onrestart` 外，init 还有几个影响"生死语义"的选项；class 三条命令的差别在"停止之后还能不能被再次拉起"。

1. **critical**：默认按 4 分钟崩溃窗口记数，计数超过 4（即第 5 次）才触发整机 fatal 重启；开机完成前同样受该计数门槛约束。新版可写 `critical window=<分钟> target=<目标>`；
2. **oneshot 与 disabled**：oneshot 退出后不重启；disabled 不随 class_start 启动、只能按名 start。bootanim 就是 `disabled + oneshot`，由 SurfaceFlinger 按需拉起，WMS 请求退出；
3. **class 三命令**：`class_start` 启动整类中未运行的服务；`class_stop` 停止且禁用；`class_reset` 只停止不禁用、之后可再次 class_start 拉起；
4. **时代变迁**：`writepid` 已被 init README 标记过时（改用 `task_profiles`，Android 14 起作用于整个进程）；`updatable` 允许被 APEX 内同名服务 override，且该服务在 APEX 激活前启动会被延迟；
5. **排查入口**：`getprop | grep init.svc` 看全部服务状态投影；调试 critical 用 `setprop init.svc_debug.no_fatal.<名> true`。

**Q18: APEX 在启动链的哪一步激活？APEX 损坏时设备表现成什么样？**

在该 Android 13 启动配置中，init 会在 Zygote/system_server 启动前等待 apexd 的激活状态；apexd 扫描内置和数据分区中的候选包，完成校验与挂载后更新状态属性。激活失败时是否回退、重试或阻塞后续启动取决于失败类型与恢复策略，不能概括成所有 APEX 错误都永久卡在同一个状态。

1. **两段激活**：早期 bootstrap 阶段先处理启动关键依赖；`/data` 可用后再处理活动 APEX 并完成后续激活阶段；具体组件与属性状态以目标分支实现为准；
2. **故障表现**：验签或哈希错误可能触发回退、重试或失败状态，影响哪些服务继续启动取决于 rc 对状态属性的等待条件；“卡动画/黑屏”是可能症状，不足以单独证明 APEX 损坏；
3. **排查入口**：`getprop apexd.status`、`logcat -s apexd`、`ls /apex`、`pm list packages --apex`；日志锚点 "Bootstrapping done" / "Marking APEXd as activated/ready"。

**Q19: Zygote 是怎么被拉起的？启动后依次做什么？**

拉起路径：`init.zygote64.rc` 声明服务 → `init.rc` 的 `zygote-start` 触发器执行 `start zygote` → init fork/exec `/system/bin/app_process64 --zygote --start-system-server` → app_process 初始化 ART 运行时 → 进入 `ZygoteInit.main()`。

`ZygoteInit.main()` 的顺序（Android 17）：

1. 未启用延迟预加载时执行 `preload()`：预加载常用类、资源与共享库——这部分内存随后被所有 fork 出的进程共享；
2. 创建 `ZygoteServer`（绑定 init 传入的 zygote socket 与 USAP 池 socket）；
3. 主 Zygote 调用 `forkSystemServer()` 创建 `system_server`；
4. 父进程进入 `runSelectLoop()`，轮询 socket 等待后续应用进程创建请求。

理解要点：`--start-system-server` 参数说明"fork 出 system_server"是主 Zygote 启动流程内的一步，不是后续 socket 请求的结果；init 直接管理的是 Zygote 进程本身，而不是 system_server。

**Q20: Zygote 的 preload 到底预加载了哪些东西？为什么所有应用进程能直接共享？**

preload 阶段把"每个应用都需要的公共物"只加载一次：preloaded-classes 清单里的常用框架类、系统资源（drawable/color 资源表）、图形相关初始化与 JCA 安全 Provider；此后所有 fork 出的进程靠写时复制物理共享这些页——读到的都是同一份内存，谁写了那一页才真正复制。

机制：

1. **时机**：主 Zygote 在进入 socket 循环前执行 preload；次 Zygote 用 `--enable-lazy-preload` 跳过大头，只为 32 位应用按需补载；
2. **共享原理**：fork 复制页表而不复制物理页，preload 出来的类元数据与资源位图因此成为全体后代共享的只读页——"省时间"与"省内存"两个收益同源于此；
3. **代价**：清单里的每个类都被全体应用背着——加类开机变慢、删类各应用首载变慢，preloaded-classes 的每次调整都是全局权衡。

收束：排查应用首帧慢时，"目标类不在 preload 清单、首次加载要自己付全部成本"是一个常被忽略的取证点。

**Q21: Zygote preload 用开机成本换取什么？删减预加载清单或使用 lazy preload 分别要注意什么？**

preload 是把成本从每次应用启动转移到系统启动：主 Zygote 在 fork system_server 前完整预加载 Framework 类（按 AAOS13 源码核对，frameworks/base/config/preloaded-classes 约 1.6 万行）、资源、app-process HAL 与图形驱动、共享库与字体缓存等，fork 出的进程靠写时复制共享这些页。扩清单可能减少应用启动期类加载，却增加 Zygote 启动工作、常驻共享页与脏页风险；删类或扩清单都要在干净开机与多应用场景同时衡量 Zygote 预加载时长、system_server ready 时间、Zygote PSS、多个代表应用的 TTID/TTFD 与低内存设备上的重启和 swap。Boot image profile 文档把 boot classpath Profile、system_server Profile 与 preload 类清单放在同一套设备调优流程，数据应来自真实 CUJ 并随系统镜像发布。

lazy preload 不是新能力也不是增量拆分：--enable-lazy-preload 只跳过启动期 preload，收到首次 preload 请求时 ZygoteInit.lazyPreload() 仍执行同一套完整 preload——它改变的只是支付时间。材料口径与 AAOS13 一致：主 64 位 Zygote 不传该参数，32 位 secondary Zygote 传。回归时要分别记录 primary 的 ZygotePreload、secondary 的 ZygoteInitTiming_lazy 与首个 32 位进程请求前后的延迟。另外业务应用自己的类通常不在系统 Zygote 的通用预加载集合里，不要用扩预加载解决单应用的启动问题。

**Q22: Zygote 在 Android 进程模型里扮演什么角色？为什么应用进程要用 fork 而不是各自独立启动？**

Zygote 是带完整 ART 运行时和预加载类/资源的模板进程，所有应用进程和 `system_server` 都由它 fork 出来，用"写时复制"换取启动速度和内存共享。init 第二阶段解析 `.rc` 后启动 Zygote；Zygote 完成类与资源预加载、直接 fork 出 `system_server` 后，进入 socket 循环等待后续进程创建请求。

fork 之后父子进程共享未修改的物理页，写入时才真正复制（Copy-on-Write），所以新进程并不携带一份完整内存副本；子进程随后完成 specialize——设置到目标应用的 UID/GID、SELinux 域、seccomp 等安全身份——再进入 `ActivityThread.main()`。选择 fork 而非独立启动的原因：

1. **省时间**：不必每进程重新初始化 ART、加载几千个预加载类；
2. **省内存**：预加载页与未写脏页被所有应用进程共享；
3. **同一起点**：所有进程从一致的运行环境出发。

边界：COW 不等于零成本——后续写入和应用初始化会逐步产生私有页。`system_server` 由主 Zygote 在初始化期间直接调用 `forkSystemServer()` 创建；普通应用则由 system_server 通过 Zygote 请求创建。

**Q23: Zygote 的 fork 模型有哪些硬约束？"zygote 本体没有 Binder"是怎么来的？**

Zygote 是所有应用进程的模板，fork 会原样复制线程与地址空间，所以"fork 时刻必须单线程"是硬约束：preload 期间禁止创建线程、GC 线程在 preFork 时暂停、Binder 线程池推迟到 fork 之后的子进程里（nativeZygoteInit 只在子进程路径调用）——任何在 Zygote 本体起线程或用 Binder 的改动，都会让所有后代进程带上损坏的线程副本。

配套手法：

1. **fd 继承传递**：init 预建监听 socket，fork 时 fd 直接继承，环境变量 `ANDROID_SOCKET_<name>` 只传编号——父子进程间的资源交接靠继承，不需要额外 IPC；无亲缘关系的进程仍需 SCM_RIGHTS；
2. **Runnable 洗栈**：fork 出的子进程背着 Zygote 的完整调用栈，Android 把目标 main 包成 Runnable 沿调用链逐层 return，栈帧退光后才执行，避免进程一生的异常栈都带着 fork 前的噪音；
3. **信任边界收在对端凭据**：应用创建请求的参数裁决基于 socket 对端凭据（SO_PEERCRED），普通进程不能要求特殊参数——本地 socket 的权限模型建立在内核提供的对端凭据上，不信任请求方自述。

边界："模板进程 + N 个派生进程"的架构才适合 fork 模型，差异大的负载（独立工具进程）fork 反而拖累（继承整个 VM）。Android 13 批注还勘误了一处上游过时注释：现行代码用普通 return 退栈，不是历史上的抛异常方式。

**Q24: USAP 池与"厂商预启动"是什么关系？为什么 trace 里没看到 fork 不能证明系统预启动了应用？**

USAP（Unspecialized App Process）池只负责提前创建进程：池成员是主/次 Zygote 预先 fork、尚未绑定应用身份的进程，启动请求满足条件时经 specializeAppProcess 绑定 UID/GID、SELinux 标签与数据目录。按 AAOS13 源码核对，ZygoteProcess.shouldAttemptUsapLaunch() 要求四项同时成立：mUsapPoolSupported、mUsapPoolEnabled、策略指定 USAP 启动、命令受 USAP 支持；mUsapPoolEnabled 默认为 false，策略只放行延迟敏感、非 system process 的请求，需要 wrapper 进程、child Zygote 或预加载包的命令退回普通 Zygote 路径，child Zygote 不支持 USAP。

没看到 fork 的解释至少有四种：目标进程早已存在（cached）、来自 USAP 专门化、来自 App Zygote，或 trace 窗口漏掉了进程创建。判断要核对 PID 创建时间、父进程、bindApplication 与 USAP 状态；trace 中的 Zygote:FillUsapPool 能证明填池动作，可用 dalvik.vm.usap_pool_enabled 属性与 runtime_native DeviceConfig 区分"源码支持"和"当前已启用"。

"智能预测启动"描述的是决策输入，命中后厂商可能做的动作差异很大：提前 ART 编译或 profile 维护、预取文件页、保留 cached 进程、填 USAP 池、创建私有预热进程或调整短时调度 I/O 优先级。验证要设计命中组与未命中组，固定网络、温度、编译状态与页缓存条件；只有产品文档、系统日志或调用链能说明策略来源，trace 负责证明动作与效果，不能凭"点击后很快"断定系统预创建了进程。

**Q25: 主 Zygote 和次 Zygote 怎么分工？preload 与 USAP 池各有什么坑？**

64 位主 Zygote 负责 fork system_server 与 64 位应用；32 位次 Zygote（`--enable-lazy-preload`）只服务 32 位应用，不 fork system_server，且 system_server 启动前会等次 Zygote 就绪，两者互为看门狗。preload 是双刃剑：加进 preloaded-classes 的类被所有进程共享，但开机时间变长；删类则各应用首次加载变慢，不是纯优化。USAP 池开启时禁止并发多 fork，调试器附加场景会退回普通 fork 路径。

机制：次 Zygote 的判定依据是 `ro.product.cpu.abilist` 与自身 abi-list 不一致；`--start-system-server` 只出现在主 Zygote 命令行上；USAP（Unspecialized App Process）预 fork 待命、取用时只补 specialize，失败回退主 socket 路径；但 USAP 改变了 fork 时机，依赖 fork 路径注入的框架（Magisk/Zygisk/Riru 等，社区案例）开启后可能失效，排查注入类异常先查 USAP 开关。

收束：排查"应用启动走了哪条路"先确认三点——设备是否 64/32 双 Zygote、USAP 是否开启、是否处于调试附加场景。

**Q26: USAP 为什么默认关闭？开启前要确认什么？开了以后还能用 PostFork trace 诊断吗？**

默认关闭：`ZygoteConfig.USAP_POOL_ENABLED_DEFAULT = false`，实际取值按 persist.device_config.runtime_native.usap_pool_enabled → dalvik.vm.usap_pool_enabled → 内置默认的顺序生效；开启前要确认场景收益（进程创建密集）与池参数，且子 zygote 明确不支持 USAP（mUsapPoolSupported = false）。

USAP 路径的 PostFork 处理与普通 fork 路径一致，诊断标准因此可以统一：无论命中 USAP 还是 fork，都看 PostFork 之后的 specialize 与类加载段。结果：先量 PostFork 之后的耗时再决定是否开 USAP——如果大头在 specialize 或类加载，USAP 收益有限（合理推导：USAP 预 fork 空壳省的主要是 fork 本身）。

**Q27: fork 派生模型的内核成本省在哪？16 KB 页会改变 COW 的什么？**

省在 COW（写时复制）：fork 时内核 dup_mmap 只复制页表并清除写权限，子进程首次写触发缺页、走 do_wp_page/wp_page_copy 才复制页面——预加载的类、资源与驱动初始化状态因此被全部 app 共享，只有被写的页付复制成本。

16 KB 页改变的是 COW 粒度：单次复制页从 4 KB 变 16 KB，不改变 VMA（虚拟内存区域）数量。机制推导：同样写入模式下，16 KB 设备的页级写放大更大、缺页次数更少，内存账与 4 KB 设备不可直接对比。做法：评估 Zygote 派生收益读 Private_Dirty——共享页 RSS 高而 Private_Dirty 低是健康态；跨页大小比较时分别测量。

**Q28: App Zygote（ZygotePreload）适合什么场景？它和系统 Zygote 的预加载边界怎么分？**

App Zygote 是应用自己的"应用级 zygote"：API 29 起，manifest 配 `useAppZygote="true"` 并用 `android:zygotePreloadName` 指定实现 ZygotePreload 的类，先孵化一个持有应用公共状态的进程，再由它 fork 出实际服务进程（如 isolated 进程、WebView 渲染进程）。

分界原则：预加载只有被 N 个子进程共享才有收益——全 app 公共内容放系统 Zygote（preload 一次全场共享），应用专属公共内容放 App Zygote（池内共享），单进程独享的放进程自己的启动路径。机制：App Zygote 池内 COW 共享，子进程崩溃可回池再 fork。32 位 WebView 依赖 secondary zygote 懒加载预载的机制见 [02-system-boot.md](02-system-boot.md)。

**Q29: 普通应用进程是怎么诞生的？它和 system_server 的诞生路径差在哪？**

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

**Q30: Android 13 的 systemReady 回调怎样协调 system_server 服务就绪与当前用户启动？**

`systemReady()` 是 AMS 与 SystemServer 的启动交接点，不等于“回调一结束就由 AMS 给所有设备的 user 0 拉起桌面”。AMS 先打开进程和 Activity 管理的就绪门闩，再运行 `goingCallback` 让 SystemServer 推进后续服务阶段；回调返回后才重新读取当前用户，并按用户模式决定 HOME 启动路径。

1. **回调前准备**：AMS 设置系统就绪状态，通知内部控制器，并完成启动期必要检查；进程启动门槛开始放行。
2. **执行 goingCallback**：AMS 把控制权交还 SystemServer，回调推进 `PHASE_ACTIVITY_MANAGER_READY` 等后置初始化。回调可能触发用户启动或切换，所以不能把回调前读到的 user ID 缓存下来继续使用。
3. **回调后读取用户**：AMS 重新取得当前前台用户，并检查非 system user 启动时 system user 已处于 started 状态。这是多用户启动状态的前置约束。
4. **分流 HOME 请求**：只有当前用户是 system user 且设备不是 headless system user 模式时，AMS 才在这个 `systemReady()` 路径直接调用 `startHomeOnAllDisplays()`。AAOS headless 模式下，user 0 承载系统服务；前台座舱用户启动或切换时，UserController 会经 ATMS 请求目标用户 HOME。

这里的 HOME 请求仍不是指定启动 CarLauncher：ATMS 要针对用户和显示区域解析 HOME 候选，设备配置、默认 HOME 和包状态决定最终组件。`systemReady()` 也不等于桌面首帧或系统 boot 完成；Activity idle、动画退出和每用户广播仍各有独立门槛。

同进程服务调用仍有两类接口：跨进程经 ServiceManager 注册 Binder 服务，进程内经 LocalServices 注册 `*Internal` 接口；服务一旦拆出进程，进程内接口不能继续充当跨进程契约。

**Q31: AAOS 在标准启动链的哪三个挂点接入车机专属层？CarService 是怎么起来的？**

三个挂点把车机层接入通用框架：AMS 的 system-ready 收尾启动框架侧宿主 CarServiceHelperService，由它绑定可更新的 CarService APK；`startSystemUi` 启动 SystemUI 时经 AppComponentFactory 换成车机依赖图；AMS/ATMS 发起通用 HOME 请求，再由 PackageManager 按用户解析目标组件，产品配置可能选中 CarLauncher。headless system user 下 system-ready 分支不会为 user 0 直接启动 HOME。CarService 是可更新 APK（`com.android.car` 进程），框架与车逻辑以 ICar Binder 契约连接；CarServiceHelperService 的具体实现不在本地源码检出范围内。

CarService 起链路（Android 13 批注）：

1. CarServiceImpl.onCreate → VehicleStub.newVehicleStub() 连接 VHAL，按设备实际注册的 HAL 自适应选择 AidlVehicleStub 或 HidlVehicleStub——分析车辆属性流向前先确认当前设备的 VHAL 版本；
2. new ICarImpl：构造约 30 个车机子服务进 mAllServices 表，init() 先 VHAL 再按表序逐个初始化，依赖顺序由表序表达（与 SystemServer 四波装配同构）；
3. ServiceManager.addService("car_service") 并置 `boot.car_service_created=1`，与 CarServiceHelperService 互持 Binder。

设计与边界：车辆数据是一切车机决策的源头，VehicleDeathRecipient 检测到 VHAL 死亡会终止 CarService 进程，由绑定者重新拉起——这是本仓 Android 13 实现的故障策略，不应推广到普通应用服务。AIDL/HIDL 的选择依据设备实际注册的 HAL。SystemServer 中存在按类名启动 CarServiceHelperService 的挂点，但 helper 类实现不在本地源码检出范围内；其绑定与重试细节属推断，不能由类名推定。

**Q32: CarSystemUI 和 CarLauncher 是怎么在不 fork 原生代码的前提下完成车机化的？**

CarSystemUI 走"合并构建 + AppComponentFactory 换依赖图"：不 fork 原生源码，而是在 manifest 声明 CarSystemUIAppComponentFactory，进程创建时把 Dagger 根组件替换为车机版（CarGlobalRootComponent/CarWMComponent），原生 SystemUI 的启动编排（SystemUIService → startServicesIfNeeded → Dagger 展开 CoreStartable）原样复用。CarLauncher 是 ATMS 发起 HOME 请求后可能被 PackageManager 选中的组件；它用 TaskView 把地图 App（另一个进程的受控任务）嵌进桌面，并用 HomeCardModule 装配顶部/底部卡片。

机制与易错：

1. **换图定制点**：Dagger 化的代码用根组件替换当主定制点，比继承或复制源码可维护；前提是目标代码已 Dagger 化、组件边界清晰；
2. **TaskView 行为差异**：地图是另一个进程的 Activity，崩溃与焦点行为和普通 View 不同。`autoRestartOnCrash=false` 表示 TaskView 不按该选项立即自动重启任务；Launcher 后续仍可能在宿主可见、用户解锁、Display 可用或依赖包变化时尝试恢复，不能断言只能手动重进；
3. **多用户边界**：CarSystemUIInitializer 只给 system user 注入 RootTaskDisplayAreaOrganizer（副驾屏等按用户隔离）。CarLauncher 对 headless system user 的地图 TaskView 有单独限制；这不表示前台驾驶员用户的桌面也没有地图卡片；
4. **CarService 断连**：car_service 进程死亡不会按此机制直接杀掉 CarSystemUI/CarLauncher；客户端会经历 Binder 断连并尝试重连，期间依赖 CarService 的 UI 能力可能暂时不可用。调试时应分别观察服务端重启和客户端恢复状态。

**Q33: 误删或禁用了桌面应用，设备开机会怎样？FallbackHome 是干什么的？**

禁用当前用户的真实桌面不必然导致 boot loop，但也不能假定任意设备、任意用户都必定有同一个 fallback。AOSP 可通过 Settings 的 `FallbackHome` 在凭据加密存储尚不可用时提供过渡 HOME；Framework 还会按 system user 设置条件启用 `SystemUserHomeActivity`。实际候选和回退顺序由系统版本、用户类型、包状态及产品配置决定。

1. **FallbackHome**：以低优先级 HOME 候选常驻；解锁前它就是 resolve 结果，`onCreate` 注册 `ACTION_USER_UNLOCKED` 广播，解锁后 `finish()` 让系统重新 resolve 到真桌面；
2. **SystemUserHomeActivity**：Framework 中的占位 HOME 组件，作用范围是 system user；AMS 按 split system user 的 setup 状态或系统属性决定是否启用它。它不是任意前台用户都可用的通用桌面；
3. **实用**：`cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME` 可查询 HOME 候选；禁用桌面前应在目标 Android 版本和目标用户下核实实际解析结果。

**Q34: 开机动画由谁拉起、怎么退出？"开机动画卡死不退出"这个经典回归怎么查？**

bootanim 是 init 声明的 `disabled + oneshot` 服务：SurfaceFlinger 初始化显示后经 init 按需启动它；启屏阶段由 WMS 请求动画退出，并等待 init 确认服务已停止。WMS 随后才通知 SurfaceFlinger `BOOT_FINISHED`，因此启动动画、停止动画和 SurfaceFlinger 的 boot-finished 通知是有先后关系的三个动作。

1. **拉起**：`bootanim.rc` 定义 `class core animation`、`disabled`、`oneshot`；SurfaceFlinger 按显示启动条件通过 init 的 `ctl.start` 请求拉起它。init 的 `init.svc.bootanim` 属性反映服务状态；
2. **退出链**：WMS `performEnableScreen()` 等待开机策略和需显示的系统装饰窗口就绪，再置 `service.bootanim.exit=1`。bootanimation 在帧循环观察属性，并按动画 part 规则收尾；WMS 轮询 init 服务状态，确认进程已停后才发送 SurfaceFlinger `BOOT_FINISHED` 并启用显示/输入。SurfaceFlinger 的 `bootFinished()` 也会置退出属性，但它发生在 WMS 已确认动画服务停止之后；
3. **卡死排查**：`desc.txt` 中 `c` 类 part 会在退出前播完未完成的 `c` part；`p` 类可在退出时中止。若退出属性已置位但进程仍运行，检查当前 part 是否为 `c`、帧循环是否继续，以及 WMS 是否在等系统装饰窗口或策略；
4. **自定义坑**：帧必须 PNG 按序命名、分辨率与 desc.txt 首行一致、zip 用 store 模式（`zip -0qry`）。

**Q35: FBE 设备重启后、用户还没输锁屏密码，闹钟类应用怎么才能正常响？LOCKED_BOOT_COMPLETED 和 BOOT_COMPLETED 是什么关系？**

`directBootAware="true"` 的组件在用户解锁前就能被系统拉起并收到 `LOCKED_BOOT_COMPLETED`，但此时只能访问设备加密（DE）存储；用户输完锁屏收到 `ACTION_USER_UNLOCKED` 后，凭据加密（CE）存储才可用——FBE 的 DE/CE 密钥机制见 [../06-storage/02-storage-io.md](../06-storage/02-storage-io.md)。

1. **状态判断**：`UserManager.isUserUnlocked()`（API 24+）区分解锁前后两个阶段；
2. **DE 存储用法**：`createDeviceProtectedStorageContext()` 拿 DE 上下文，可用 `moveSharedPreferencesFrom()/moveDatabaseFrom()` 在解锁后把数据迁到 CE；
3. **任务重建**：CE 侧的 alarm/job 重启即丢——directBootAware 接收器要用 DE 存储持久化"重启前有任务"的标记，解锁后重建；
4. **典型 bug**：只把恢复闹钟注册在 `BOOT_COMPLETED`（FBE 设备上它要等解锁后才发，用户不解锁就永远不发）；directBootAware 组件里直接打开 CE 路径抛 `FileNotFoundException` 或 SQLite "cannot open file"。

**Q36: 开机广播 BOOT_COMPLETED 有时收不到、有时收到就 ANR——它的送达条件、超时和限制到底是什么？**

送达问题先区分用户状态、包状态和接收器执行时限。AAOS13_study 的 Android 13 基线中，AMS 前台广播预算为 10 秒、后台为 60 秒，并受 `Build.HW_TIMEOUT_MULTIPLIER` 影响；不要把这一组默认值无条件套到其他 Android 分支或厂商配置。

1. **超时 ANR**：`onReceive()` 未在预算内返回会触发广播超时 ANR；检查 ANR trace 中的 receiver 进程栈以及对应 broadcast queue，而不要只依据“收到了广播”定位；
2. **stopped state 拦截**：带 `FLAG_EXCLUDE_STOPPED_PACKAGES` 的广播不会启动处于 stopped state 的包。新装未启动或被用户 force-stop 的应用可能处于该状态；省电清理是否造成 stopped state 要看它实际执行的系统动作；
3. **用户级送达条件**：Android 13 的 UserController 按用户推进开机状态；`BOOT_COMPLETED` 在目标用户 CE key 解锁、且升级场景的 `PRE_BOOT_COMPLETED` 接收者处理完成后发送。headless system user 下，system user 与前台座舱用户沿各自状态路径处理；
4. **全局属性不是广播回执**：AMS 先设置 `sys.boot_completed=1`，再调用 UserController 逐用户处理广播。因此属性为 1 不能证明某用户的 `BOOT_COMPLETED` 已送达或接收者处理完成；
5. **后台启动限制**：应用不能把“开机广播后直接拉起界面”当作通用入口；遵守目标版本的后台 Activity 启动限制，通常应安排受约束的后台工作，由用户可见入口展示界面。

正确姿势：`onReceive()` 做轻量调度；需要异步处理时按 API 契约调用 `goAsync()` 并及时 `PendingResult.finish()`，可延期的持久工作交给 WorkManager。前台服务还要符合目标版本的启动和服务类型限制。

**Q37: webview_zygote 是什么？应用声明 isolatedProcess 的服务跑在什么进程里？**

webview_zygote 是供 WebView 渲染进程使用的专用 Zygote；`android:isolatedProcess="true"` 服务则使用隔离 UID 和对应 SELinux 域。二者都涉及隔离进程，但普通 isolated service 不因此变成 WebView renderer，也不必由 webview_zygote 孵化。

1. **webview_zygote**：WebView provider 更新时旧 zygote 被杀重建（换 provider 必重启 webview zygote），并按目标 ABI 预载 provider 代码；`ps -A | grep zygote` 可同时看到 32/64 位主/辅 zygote 与 webview_zygote，`dumpsys webviewupdate` 看 provider 与 zygote 状态；
2. **isolatedProcess**：系统为服务分配隔离 UID，并按 seapp_contexts 选择 `isolated_app` 等 SELinux 域；组件权限与可访问资源受隔离策略限制。WebView renderer 自身也运行在隔离边界内，但它与应用声明的 isolated service 是不同启动用途；
3. **进程孵化器选择**：主/次 Zygote 由 `ro.zygote` 与设备 ABI 配置决定，`webview_zygote` 是 WebView 的专用孵化器；应用还可通过 `android:useAppZygote="true"` 请求应用专属 Zygote。不能仅凭 `isolatedProcess="true"` 推断进程来自 webview_zygote。

**Q38: 服务崩溃后 init 的 Reap 裁决按什么顺序处理？哪些情况会放大成整机重启？**

Reap 是服务死亡后的唯一裁决点，五步顺序即语义：收尸（杀残留进程组、清理非 persist 的 socket）→ 违约检查（声明 `reboot_on_failure` 的服务异常退出直接触发重启）→ 后继态裁决（oneshot 且非手动重启置 disabled）→ 重启裁决 → 复活准备（执行 rc 声明的 onrestart 命令、进入 RESTARTING 等主循环重启）。

Reap 会触发的系统级后果分三类，不能把“写入故障属性”和“立即重启”混为一谈：

1. **critical 服务**：默认 4 分钟窗口内崩溃计数超过 4（第 5 次），或尚未 boot complete 时累计超过该门槛，init 以 LOG(FATAL) 进入配置的 fatal reboot target；target 未必对所有产品都是 bootloader；
2. **APEX 可更新组件进程**：相应崩溃计数超过门槛后设置 `sys.init.updatable_crashing`，通知 apexd/update_verifier 处理；它不是同一行代码立即执行的 critical fatal reboot；
3. **显式声明 `reboot_on_failure`** 的服务异常退出：按配置直接触发重启。

边界与易错：服务状态用 SVC_* 位标志而非枚举表达（oneshot、disabled、critical 可并存，退出后继态取决于位组合）；oneshot 服务正常退出进 disabled，不会再被 class_start 拉起，须显式 start；stop 后 start 的 RESTART 中间态会跳过置 disabled，否则 start 拉不起来。

**Q39: 设备反复重启进不了桌面（boot loop）——init 对关键服务反复崩溃的判据是什么？adb 不可用时怎么拿到上一次崩溃的日志？**

init 对 `critical` 服务的崩溃计数有明确门槛：默认 4 分钟窗口内计数超过 4，也就是第 5 次崩溃时触发 fatal；开机完成前崩溃同样进入计数逻辑。触发后的重启目标由服务配置决定，不能概括为所有设备都进 bootloader。

1. **早期日志**：`/sys/fs/pstore/console-ramoops` 可保存上一次开机的内核日志，需设备启用 pstore/ramoops；老内核可能提供 `/proc/last_kmsg`。用户态写入 `/dev/pmsg0` 的日志若被 ramoops 保留，重启后应查 `/sys/fs/pstore/pmsg-ramoops-*`；`/dev/pmsg0` 本身是写入端点，不是上次启动日志文件；更早的 Bootloader 阶段通常需要串口或厂商工具；
2. **排查动作**：日志里 grep `avc: denied`、`critical process`、`Fatal signal`、`service exited`；pstore 不可读时用 recovery 模式拉日志；
3. **调试逃生口**：在设备构建与调用权限允许时，`setprop init.svc_debug.no_fatal.<服务名> true` 可临时关闭该服务的 critical fatal 处理，以便收集日志；这不是量产设备上的通用恢复方案；
4. **边界**：Verified Boot 镜像校验失败发生在用户态日志可用之前，应查 Bootloader/串口/recovery 证据；它与 init 运行后的服务崩溃循环属于不同阶段。

**Q40: 设备"突然重启/黑屏"，怎么从日志快速判断死在哪一层——内核、init、Zygote 还是 system_server？**

四层故障的日志指纹不同，先看设备是否发生内核重启，再定位用户态服务退出或 system_server 看门狗动作。不同设备的 fatal reboot target 和日志保留方式可能不同，不能只凭黑屏外观判断。

1. **内核 panic**：pstore/console-ramoops 里有 "Kernel panic - not syncing: ..."，设备直接黑屏重启、没有任何 Android 日志延续；
2. **init 监督或 fatal 策略**：`Attempted to kill init!` 表示有人尝试终止 PID 1；`critical process ... exited ...` 表示服务崩溃计数触发 init fatal 路径。最终目标受服务和产品配置控制，不保证固定进入某个模式；
3. **Zygote 服务退出**：logcat 中 init 报告 `Service 'zygote' ... received signal`，随后观察 init 是否重启 Zygote，以及 system_server PID 是否随新 Zygote 改变；zygote 服务的 `onrestart` 配置会影响其他 native 服务；
4. **system_server Watchdog**：`*** WATCHDOG KILLING SYSTEM PROCESS` 和 `Blocked in ...` 是 Watchdog 证据；结合 `pre_watchdog`/`watchdog` DropBox 记录及线程栈找阻塞点。通常表现为 Framework 重启，不等于内核重启；
5. **区分内核重启与 Framework 重启**：对比 `/proc/sys/kernel/random/boot_id`、进程 PID、`sys.boot_completed` 和 pstore。内核 boot_id 改变说明经历内核启动；PID/Framework 状态变化但 boot_id 未变更说明应优先查用户态恢复链。BOOT_COMPLETED 是否再次出现不能单独作为判据。

**Q41: AAOS 13 的 headless system user 模式下，AMS 为什么不从 systemReady 直接启动 user 0 的桌面？**

headless system user 模式把 user 0 作为系统服务用户，不把它当作座舱 HOME 的显示用户。因此 AMS 在 `systemReady()` 中跳过 system user 的直接 HOME 启动；座舱用户进入前台时，UserController 再让 ATMS 为该用户启动 HOME。

1. `goingCallback` 执行完后，AMS 重新读取当前用户；回调期间 SystemServer 的后续服务可能已启动或切换用户。
2. 当前用户为 system user 且不是 headless 模式时，AMS 可直接调用 `startHomeOnAllDisplays(currentUserId, "systemReady")`。
3. headless 模式下，AMS 的源码注释说明 system user 此时已由前置流程启动并解锁，部分用户启动工作已完成，因此不从该分支重复启动它的 HOME；驾驶员用户由产品用户策略启动或切到前台。
4. UserController 的 `moveUserToForeground()` 切换任务栈，并经 ATMS `startHomeActivity(newUserId, ...)` 将 HOME 请求绑定到新前台用户。

Framework 源码锚点是 `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` 的 `systemReady()` 和 `frameworks/base/services/core/java/com/android/server/am/UserController.java` 的前台切换路径。具体何时创建驾驶员用户、何时切换前台用户由产品的 CarUserService/用户策略决定；不能仅凭 headless 模式断言每台设备都在同一时刻创建 CarLauncher。

**Q42: AAOS 13 启动 HOME 时，PackageManager 怎样决定是否运行 CarLauncher，进程又怎样进入 Activity？**

AMS/ATMS 发出的是带用户和显示上下文的 HOME 请求，不是对 CarLauncher 类名的硬编码启动。ATMS 解析出目标组件后，才走通用 Activity 启动链；若目标进程不存在，AMS 才请求 Zygote 创建进程，之后通过 ActivityThread 创建 Activity。

1. RootWindowContainer 为目标 user/display 组装 `ACTION_MAIN + CATEGORY_HOME`；次显示区在满足策略时可使用 `CATEGORY_SECONDARY_HOME`。
2. PackageManager 按目标用户解析当前启用且匹配的组件。CarLauncher Manifest 声明 HOME 候选，因此可能被选中；默认 HOME、包是否安装/启用及产品配置决定解析结果。
3. ActivityStartController/ActivityStarter 准备启动任务和 ActivityRecord，并经 AMS 的进程管理路径确保应用进程存在。
4. 进程不存在时，ProcessList 计算 UID/GID、存储策略和 SELinux `seInfo` 等参数，经 ZygoteProcess 请求 Zygote fork；已有进程则复用，不会因为 HOME 请求必然创建新进程。
5. 子进程进入 ActivityThread、attach 到 system_server 后，客户端事务才创建目标 Activity。只有解析结果确实为 CarLauncher，控制流才进入它的 `onCreate()`。

定位“Launcher 没起来”时，先用目标用户查询实际 HOME 解析结果，再查 ActivityTaskManager 启动记录和进程状态；Manifest 声明只能证明组件有资格成为候选，不能证明 PackageManager 最终选中了它。源码锚点包括 `RootWindowContainer.java`、`ActivityTaskManagerService.java`、`ActivityStartController.java`、`ProcessList.java`、`ActivityThread.java` 和 CarLauncher 的 `AndroidManifest.xml`，均按 Android 13 checkout `abec84ef9` 核对。

**Q43: HOME Activity 已启动后，Android 13 还要满足哪些条件才设置 sys.boot_completed？**

HOME Activity 进入启动链不是 boot complete。ATMS 在前台 Activity idle（或超时兜底）后安排收尾；WMS 等屏幕策略与动画退出，AMS 再用动画完成门闩协调 `finishBooting()`，完成后才推进全局 boot phase 并设置 `sys.boot_completed=1`。

1. ActivityTaskSupervisor 在首批 resumed Activity idle（或相应超时兜底）后调用 `postFinishBooting()`，把启动收尾投递到 ATMS handler；特定应用崩溃、ANR 或 StrictMode 对话框路径也会调用 AMS `ensureBootCompleted()` 兜底。两者都表示框架开始收尾，不表示动画或显示已经完成。
2. WMS `performEnableScreen()` 等待开机窗口策略与需绘制的系统装饰窗口，再请求 bootanimation 退出并轮询 init 的服务状态。
3. bootanimation 停止后，WMS 通知 SurfaceFlinger `BOOT_FINISHED`，启用显示和输入，再回调 AMS `bootAnimationComplete()`。
4. AMS 的 `finishBooting()` 与动画回调可能先后到达：先到的一方记录状态，另一方到达后续跑收尾，避免竞态；随后 AMS 发布 `PHASE_BOOT_COMPLETED` 并设置 `sys.boot_completed=1`。
5. `sys.boot_completed` 是全局系统属性，不是每用户广播的完成回执。UserController 后续仍按用户处理存储解锁、升级时的 `PRE_BOOT_COMPLETED` 和各用户的 `BOOT_COMPLETED`。

因此应分别记录 Activity idle、bootanimation 停止、显示/输入启用、`PHASE_BOOT_COMPLETED`、全局属性和用户广播状态；不能用桌面可见或单个属性替代整条启动完成判定。源码锚点为 `ActivityTaskSupervisor.java`、`WindowManagerService.java`、`ActivityManagerService.java` 与 `UserController.java`，对应 `AAOS13_study` Android 13 commit `abec84ef9`。
