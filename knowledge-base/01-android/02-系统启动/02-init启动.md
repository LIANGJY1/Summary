# Android init 启动

> ramdisk、内核到 init、分阶段初始化、早期挂载、SELinux 与启动服务。 各题的平台版本与证据范围以题内说明为准。


**Q1: [done] Android 启动中的 ramdisk、initramfs 和 rootfs 是什么？**

三个名称分别指向：

1. **ramdisk**：Android 启动语境中用于交付早期文件的归档，包含首阶段 init 等内容。它与早期 Linux 的内存块设备 ramdisk 同名，但不是同一个概念。
2. **initramfs**：供内核解包的早期文件归档，通常使用 cpio 格式，也可以压缩。除 init 外，它还可包含设备节点、配置文件、工具和程序所需的共享库。归档本身不是运行中的根文件系统。
3. **rootfs**：内核建立的初始根文件系统。归档解包后文件出现在其中，内核可执行 `/init`。具体根文件系统和后续切换方式取决于内核与设备布局。

使用首阶段 ramdisk 时，启动链路为：

bootloader 加载内核和 ramdisk → 内核解包到 rootfs → 内核执行 `/init`，启动用户态 PID 1 → init 加载模块并挂载系统分区 → 通过 exec 接力后续 init，PID 保持为 1。

ramdisk 提前提供启动文件，使 init 不必依赖尚未挂载的分区。

`/init` 并非所有设备的必然入口：AAOS 13 内核默认尝试该路径，`rdinit=` 可指定其他早期入口。依据：内核源码 `common/init/main.c`。


**Q2: [done] [tags:init] init 进程的入口是什么，是如何拉起的？**

ramdisk 的 `/init` 是可执行文件，不是进程。内核创建 PID 1 的 `kernel_init` 内核线程后，通过 `kernel_execve("/init")` 装载它。执行切到 ELF 入口 `_start` 时，同一个 PID 1 才开始运行用户态 init。Bionic 初始化后再调用 C++ `main()`。这是转换已有任务，不是 `fork` 新进程。

1. **首阶段如何接力：**独立 ramdisk 布局下，`/init` 是静态链接的 `init_first_stage`。程序从 `_start` 经 Bionic 初始化，进入 `first_stage_main.cpp` 的 `main()`。挂载系统分区后，它通过 `execv` 执行 `/system/bin/init`，并传入 `selinux_setup`。

2. **系统 init 如何构建：**`system/core/init/Android.bp` 将 `main.cpp` 编入 `init_second_stage` 模块，并链接 `libinit`。`stem: "init"` 将产物命名为 `init`。若省略，文件名默认取模块名 `init_second_stage`。产物安装到 `/system/bin/init`。启动时，动态链接器先装载依赖，再进入程序的 `_start`，由 Bionic 调用 `main.cpp` 中的 `main()`。

3. **main() 如何分流阶段：**`main()` 收到 `selinux_setup` 时调用 `SetupSelinux()`。SELinux 初始化完成后，再通过 `execv` 传入 `second_stage`，进入 `SecondStageMain()`。`execv` 替换 PID 1 的程序映像，不创建新进程。System-as-root 布局下，`/init` 可指向 `/system/bin/init`，无阶段参数时直接进入 `FirstStageMain()`。


**Q3: [learning] [tags:init] init 进程都做了什么？**

init 是内核启动的第一个用户态进程（PID 1）、所有用户态进程的祖先。它本身不承载业务逻辑，而是“配置驱动的进程管理器 + 系统初始化执行器”。Android 17 中它仍按第一阶段、SELinux 访问控制初始化、第二阶段三步执行。

职责分四块：

1. **分阶段初始化**：第一阶段挂载基础文件系统与早期分区。第二阶段在 SELinux 初始化后完成完整用户态准备。
2. **解析并执行 rc**：init 按 Android Init Language 中的服务与动作声明启动守护进程，包括 Zygote、SurfaceFlinger 等。
3. **属性服务**：init 维护系统属性（`ro.*`、`persist.*` 等），处理属性写入并通知监听方属性变化。
4. **服务监督与子进程回收**：作为 PID 1，init 通过 `waitpid()` 回收子进程，并按 `.rc` 定义决定服务退出后的处理方式。

理解它的用处：所有“谁负责重启某个服务”的答案最终都落在 init 的服务监督上——system_server 崩溃后 Zygote 自杀，再由 init 重启 Zygote、重新 fork system_server，这条恢复链的管理者就是 init。


**Q4: [learning] [tags:init] init 进程 first_stage_init 详解？**

first_stage_init 是第一阶段的执行体：`first_stage_main.cpp` 的 `main()` 直接调用 `FirstStageMain()`（AAOS 13 `system/core/init/first_stage_init.cpp` 本地核对），职责是在“还没有文件系统、设备节点和日志设施”的裸环境里，准备好挂载 system 分区所需的最小运行环境，最后 exec 系统分区的 init 进入第二阶段。它不解析 rc、不启动任何服务、不加载 SELinux 策略——那些都发生在之后。

按 `FirstStageMain()` 的执行顺序：

1. **环境与错误基建**：`umask(0)` 保证后续 `mkdir`/`mknod` 的权限位不被缩小。`clearenv()` 清掉内核传来的环境后只设 `PATH`。此阶段还没有日志设施，`CHECKCALL` 宏把每个失败系统调用的表达式文字与 errno 暂存进 errors 向量，而不是失败即退——等 `/dev/kmsg` 建好、日志初始化完成后再逐条报告并终止启动。
2. **基础文件系统**：把 tmpfs 挂到 `/dev`（`MS_NOSUID`，并建 pts/socket/dm-user 目录），挂 devpts、proc（`hidepid=2` 并把 `/proc/cmdline` chmod 为 0440，防普通进程读内核命令行）、sysfs 与 selinuxfs。随后 `mknod` 创建 kmsg、random、urandom、ptmx、null 等基础设备节点。
3. **辅助挂载与控制台决策**：挂 `/mnt`（含 vendor/product 目录）、`/debug_ramdisk` 等辅助 tmpfs。`FirstStageConsole()` 依编译开关与 cmdline/bootconfig 决定首阶段失败时是否进入控制台（`CONSOLE_ON_FAILURE` 才会 `StartConsole()`）。
4. **加载内核模块**：fstab 指向的块设备可能依赖 vendor 内核模块，`LoadKernelModules()` 先加载驱动，后续 device-mapper 映射与挂载才有目标。模块加载耗时写入环境变量供第二阶段度量。
5. **早期挂载**：`DoFirstStageMount()` 读取 fstab（来自设备树或 cmdline 指定路径），由 fs_mgr 完成 AVB 校验与 dm 映射并早期挂载 system/vendor 等分区——挂载成功后 `/system/bin/init` 才存在。
6. **切根与接力**：`ForceNormalBoot`（如 recovery ramdisk 被要求正常启动）时先 `PrepareSwitchRoot()` 再 `SwitchRoot("/first_stage_ramdisk")`。收尾把首阶段起点计时写入环境（exec 不清环境，第二阶段可读），最后 `execv("/system/bin/init", "selinux_setup")`——同一 PID 1 替换映像进入 `main()` 的阶段分发，接力机制本身在上一题已展开。

两个边界：其一，system-as-root 布局没有独立 ramdisk，`/init` 直接指向 `/system/bin/init`，无阶段参数时同样进入 `FirstStageMain()`，但入口 ELF 不同。其二，这一阶段的失败除控制台模式外都是终态——此刻既没有服务可重启，也没有文件系统可供回退。


**Q5: [learning] 内核怎样启动第一个用户态进程 /init？为什么这个过程不是 fork？**

`/init` 不是从现有用户态进程 fork 出来的。内核初始化尾声创建的 `kernel_init` 内核线程以 PID 1 的身份调用 `kernel_execve("/init")`，用 init 程序映像替换自己的内核线程映像。

1. **execve 如何接力**：`kernel_execve` 丢弃旧地址空间，装载新 ELF、建立页表与入口栈。返回用户态时，PID 1 执行 init 的入口函数。若找不到 `/init` 或 ELF 损坏，exec 失败会导致内核 panic，设备无法完成启动。
2. **内核如何选择程序**：内核优先使用 `init=` 启动参数指定的路径，其次尝试 ramdisk 上的指定命令，再尝试 `/sbin/init`、`/etc/init`、`/bin/init`。Android 把自己的 init 放在 `/init`，占据第一顺位。
3. **为何 init 能早于系统分区运行**：早期 init 静态链接，自带所需 C 库，不依赖尚未挂载的 system 分区中的动态库。
4. **一个二进制的多种身份**：init 根据启动参数执行第一阶段、第二阶段、`selinux_setup`、ueventd 或 subcontext 执行器等路径。`/system/bin/ueventd` 可链接到同一个 init 二进制。

fork 复制已有进程，而此时没有用户态进程可供复制。execve 让内核线程进入 init 用户态映像。此后 Android 的 Zygote、system_server 和应用等用户态进程才由 init 启动的进程链派生。


**Q6: [learning] [tags:系统启动] 内核把控制权交给 init 的那一刻，系统精确处于什么状态？**

内核已把控制权交给 `/init`，但 init 第一阶段的工作尚未完成。此刻不能把 init 后续挂载、装载的内容算作内核已完成的状态。此时的状态如下：

1. **CPU/内核态**：调度器、内存管理和已注册的内核驱动可运行。设备所需 vendor 模块可能由 init 第一阶段稍后加载。
2. **进程**：当前用户态进程只有 PID 1。它刚由 kernel_init 内核线程 execve `/init` 变身而来。
3. **根文件系统**：刚解包的 ramdisk（内存里），只有 init、fstab、少量工具。
4. `/dev`、`/proc`、`/sys`：尚未由 init 挂载。
5. **系统分区**：system/vendor 分区尚未挂载，AVB 校验流程也未由 init 完成。
6. **SELinux**：策略未装载，强制访问控制尚未按 Android 策略生效。
7. **属性服务**：init 尚未完成第二阶段属性初始化，因此不能说系统属性“全部为零”。启动参数仍可从 `/proc/cmdline` 等内核接口读取。

收束：这份清点就是 init“建设者”职责的完整清单——它要补的每一项空白（窗口、真根、策略、服务、Java 世界）都对应上面一行。


**Q7: [learning] [tags:系统启动] init 为什么要在启动中途 execv 自己两次？三个阶段各自做什么？**

init 是同一个二进制以三个不同进程映像接力：第一阶段在 ramdisk 上搭最小环境，之后 execv 切入 selinux_setup 镜像装载策略，再 execv 切入 second_stage 常驻形态——中间两次 exec 是因为 SELinux 域转换只发生在 exec 时刻，这是把“同一程序不同阶段需要不同信任级别”交由内核保证的做法。

链路（Android 13 批注）：

1. **FirstStageMain**：挂载 `/dev`、`/proc`、`/sys` 与设备节点，调用 LoadKernelModules 加载 vendor 内核模块，再由 DoFirstStageMount 按 fstab 挂载 system/vendor 等只读分区。
2. `execv("selinux_setup")` 与 SetupSelinux：合成并装载 sepolicy，再完成 SELinux 域切换。
3. `execv("second_stage")` 与 SecondStageMain：执行 PropertyInit 与 SelinuxRestoreContext，建立 epoll、signalfd、属性 socket 等事件源，解析 rc 后进入常驻主循环。

三个阶段通过 `execv` 接力，因此全局变量与静态状态不会从前一阶段自动带到后一阶段。跨阶段数据可通过环境变量（如 `INIT_AVB_VERSION`）或文件传递。exec 接力适合需要程序映像或安全域边界的阶段划分。若只是逻辑分层，函数调用更简单，也更容易调试。


**Q8: [learning] /dev、/proc、/sys 三个伪文件系统分别提供什么信息？init 为什么要先挂载它们？**

这三个伪文件系统把内核维护的设备、进程和设备模型状态呈现为文件接口，内容不按普通磁盘文件方式保存。init 第一阶段挂载它们，是为了让后续初始化能够读取启动状态、发现设备节点并访问内核设备模型。

1. `/dev`：设备节点。字符设备和块设备节点包含主设备号与次设备号。打开节点后，内核据此路由到相应驱动。Android 的 `/dev` 通常由 tmpfs 承载。
    1. ueventd 根据内核事件和规则创建、配置大部分设备节点。
    2. init 与其他系统服务也可创建特定节点或符号链接，因此不是所有节点都只能由 ueventd 创建。
2. `/proc`：进程和内核运行状态。
    1. `/proc/<pid>/` 为每个进程提供状态目录。
    2. `meminfo`、`cpuinfo` 等文件报告资源信息，`/proc/sys` 提供 sysctl 参数接口。
    3. init 会读取 `/proc/cmdline` 中的内核启动参数，例如 `androidboot.mode=charger`，据此选择启动路径。
3. `/sys`：内核设备模型。
    1. `/sys/devices` 展示设备本体，`/sys/class` 按设备类别聚合，`/sys/module` 列出已加载模块。
    2. 设备目录下的 `uevent` 文件可用于重放设备事件。
    3. 向 `/sys/power/state` 写入支持的值可请求系统休眠。

init 在第一阶段挂载这些文件系统。`/dev` 的大部分设备节点随后由 ueventd 补齐。


**Q9: [learning] [tags:系统启动] GKI 时代，为什么内核模块要放在 vendor ramdisk 里而不是编进内核？**

GKI（Generic Kernel Image，通用内核镜像）把通用内核与符合 KMI（内核模块接口）的厂商模块分开交付，降低通用内核更新与设备专属驱动之间的耦合。设备驱动并非全部都是模块：部分可以内建，部分可在对应分区挂载后加载。只有挂载关键分区前就必须可用的模块，才需要放在早期可读取的 vendor ramdisk 等位置。

需要早于 vendor/system 挂载的模块放进 vendor ramdisk，是启动顺序的要求，不意味着所有厂商驱动都必须外置到这里。若某个存储驱动正是挂载相应分区的前置条件，就会形成“先挂分区才能取模块、先有模块才能挂分区”的依赖环。init 第一阶段从 ramdisk 加载该类模块来打破这个环。

因此判断模块放置位置应问“它是否是早期挂载的前置依赖”，而不是笼统按“厂商驱动”归类。`LoadKernelModules` 的具体模块集合由设备配置决定。


**Q10: [learning] [tags:系统启动] fstab 是什么？init 第一阶段怎么按它挂载分区？**

fstab（file system table）是文件系统挂载声明表。每行说明块设备、挂载点、文件系统类型、挂载选项和 fs_mgr 标志。init 的挂载组件 fs_mgr 依据这些标志执行策略，因此 fstab 是挂载配置，init 是执行器。

fstab 每行由块设备路径、挂载点、文件系统类型、内核挂载选项和 fs_mgr 标志组成。以 Android 设备常见配置为例，`/dev/block/by-name/system /system ext4 ro,barrier=1 wait,avb=vbmeta,first_stage_mount,logical` 表示将 system 逻辑分区以 ext4、只读方式挂载到 `/system`，启用文件系统写屏障选项，等待设备节点，通过对应 vbmeta 做 AVB 校验，并在第一阶段处理。它是说明字段关系的示意行，实际分区路径和标志必须以设备 fstab 为准。

示意行中的每项含义和省略影响如下：

1. **块设备路径**：指定数据来源。换成设备实际分区路径，不能假定每个产品都使用 `/dev/block/by-name/...`。
2. **挂载点与文件系统类型**：分别指定目录和文件系统驱动。改动或省略会导致该条目不能按预期挂载，具体错误取决于解析和设备节点状态。
3. `ro` 与 `barrier=1`：`ro` 请求只读挂载。`barrier=1` 是示例中 ext4 的写屏障挂载选项，具体支持与默认行为由文件系统和内核版本决定。省略它时应核对目标版本默认值，不能推断行为一定相反。
4. `wait`：等待设备节点就绪再尝试挂载。省略时不声明此等待策略。
5. `avb=vbmeta`：要求 fs_mgr 使用指定的 vbmeta 关联配置执行 AVB 验证。省略后，这一行本身不声明该 AVB 校验关系。
6. `first_stage_mount`：将条目标记为第一阶段需要处理的挂载。省略后不能依赖第一阶段挂载它。
7. `logical`：说明这是动态逻辑分区。省略后不能让 fs_mgr 按该标志识别逻辑分区。
8. `first_stage_logical` 兼容写法：部分旧资料或分支配置会出现该标记。它是否受目标 fs_mgr 识别以及与 `first_stage_mount`、`logical` 的组合关系必须查对应分支，不能跨版本机械替换。
9. `latemount` 与文件系统类型：`latemount` 用于将适合延后的条目留到后续挂载阶段处理，常见于 `/data`。`/data` 也常使用 f2fs。它与 `first_stage_mount` 表达不同阶段策略，不能不加判断地同时复制到同一行。
10. **加密标志**：设备 fstab 还可能含 `encrypted`、`fileencryption` 等 fs_mgr 加密标志。它们涉及设备加密方案和文件级加密参数，具体取值必须依据目标设备实现。省略效果取决于设备的加密配置，不能用未知占位值代替真实值。

启动早期使用的 fstab 副本位于 ramdisk 可访问范围，供第一阶段读取。完整版常位于 vendor 等分区配置目录。条目集合、文件名和阶段选择受 Android 版本与设备配置影响。


**Q11: [learning] Android 启动时如何装载 SELinux 策略？预编译策略校验失败时怎样回退？**

Treble 设备的 system 与 vendor 可独立更新，但内核最终只装载一份二进制 SELinux 策略。init 的 `SetupSelinux` 汇集 system、system_ext、product、vendor、odm 和 apex 等来源的 CIL 策略，校验后装载并切换到 enforcing。预编译策略与当前分区不匹配时，系统尝试用 `secilc` 现场编译。

1. **预编译策略校验**：AOSP 通过 plat、system_ext、product、apex 等来源的哈希对，确认预编译产物与当前分区一致。哈希应同时存在或同时缺失。只有一侧存在也视为不一致，转入回退流程。
2. **现场编译**：`secilc` 按目标分支要求组合输入文件。vendor 侧必需文件缺失会导致编译失败。product、system_ext、odm、apex 等可选输入缺失时可按分支规则跳过。临时产物放在 `/dev` tmpfs，用完后 unlink，因此现场编译问题主要靠启动日志排查。
3. **非 Treble 设备**：较旧的非 Treble 配置可直接使用 monolithic `/sepolicy`，不走 Treble CIL 汇编路径。
4. **版本兼容**：平台策略 mapping 版本依据 vendor 声明的 `plat_sepolicy_vers.txt`。新平台据此维持旧 vendor 策略所依赖的语义，vendor 无须随每次平台升级都重编策略。
5. **切换 enforcing 前的顺序**：`restorecon /dev/selinux` 必须在切换 enforcing 之前完成。进入 enforcing 后，执行域不一定还拥有修改这些文件标签的权限。
6. **OTA 与 snapuserd**：OTA 场景要按目标分支的五步启动编排读取策略，并在杀死 snapuserd 之前完成读取。否则策略装载后 snapuserd 每次读取 `/system` 都可能触发 AVC 审计，杀死 snapuserd 后又会失去读取 `/system` 的路径。
7. **APEX 与调试覆盖**：APEX 可更新策略用于增量强化。验签或解包失败时回退到 system 自带版本。userdebug 调试策略还要求 `INIT_FORCE_DEBUGGABLE` 环境变量与设备解锁两个条件同时满足，量产锁定设备没有该策略替换路径。

具体哈希文件集合、可选 CIL 输入和 OTA 阶段顺序受 Android 分支影响，排查时应对照设备对应的 init 源码与启动日志。


**Q12: [learning] init 第二阶段主循环如何处理事件？每轮只执行一条命令有什么作用？**

第二阶段 init 使用单线程事件循环。epoll、signalfd 和属性 socket 等事件源把工作送入主循环，循环每轮只推进一条 `Command`，其间处理 SIGCHLD、属性变化和 ctl 控制消息。单轮限制是为了避免长动作长期占住循环，使关机请求和子进程回收仍有机会及时处理。

1. **启动动作入队**：`LoadBootScripts` 解析 init.rc 与各分区 rc，建立 Service 表和 Action 表，再按 early-init、init、late-init 等触发器入队。rc 动作还会串起 early-fs、post-fs、late-fs、post-fs-data、zygote-start、boot 等阶段。
2. **事件匹配**：ActionManager 用事件队列和 Action 表分离事件到达与命令执行。事件包括命名触发器、属性变化对和内置动作指针。一个事件可命中多个 Action。匹配在锁内完成，执行在锁外进行。
3. **内置动作队列**：`QueueBuiltinAction` 把函数指针同时用作待执行命令和匹配条件。oneshot 动作执行后会从队列和登记表中删除。关机路径的 `ClearQueue` 清空待执行队列，但保留登记表项，使 shutdown 序列仍可运行。
4. **等待属性限制**：全局同时只允许一个 `wait_for_prop` 等待。rc 中连续写两条等待会串行等待，前一条完成后才进入后一条。

该循环适合管理常驻服务和启动时序，不适合承载需要高吞吐的后台任务。


**Q13: [learning] [tags:系统启动] ueventd 是怎么把空的 /dev 填满的？**

ueventd 是 init 同一二进制的另一种运行形态，主要职责是监听内核 uevent，并按规则创建和配置大部分设备节点。Android 的 `/dev` 通常由 tmpfs 承载，但并非每个节点都只能由 ueventd 创建，启动脚本和其他系统服务也可建立特定节点或符号链接。

机制：

1. **事件来源**：内核在设备注册或移除时发出 uevent，携带设备路径、主次设备号和子系统等信息。ueventd 通过 netlink socket 接收事件。
2. **冷插拔补齐**：内核早于 ueventd 启动，部分设备事件可能在 ueventd 监听前已经发出。ueventd 启动后遍历 `/sys` 并重放 uevent（coldboot），补齐这些设备的规则处理。
3. **权限规则**：ueventd.rc 规则声明设备路径、属主、组和权限位。例如 `/dev/binder` 可配置为 root 所有、binder 组、权限 `0660`。

创建设备节点的前提是驱动已注册。遇到节点缺失时，应区分驱动未加载或未匹配，与 ueventd 未按规则创建节点这两类原因。


**Q14: [learning] [tags:系统启动] APEX 在启动链的哪一步激活？APEX 损坏时设备表现成什么样？**

在该 Android 13 启动配置中，init 会在 Zygote/system_server 启动前等待 apexd 的激活状态。apexd 扫描内置和数据分区中的候选包，完成校验与挂载后更新状态属性。激活失败时是否回退、重试或阻塞后续启动取决于失败类型与恢复策略，不能概括成所有 APEX 错误都永久卡在同一个状态。

1. **两段激活**：早期 bootstrap 阶段先处理启动关键依赖。`/data` 可用后再处理活动 APEX 并完成后续激活阶段。具体组件与属性状态以目标分支实现为准。
2. **故障表现**：验签或哈希错误可能触发回退、重试或失败状态，影响哪些服务继续启动取决于 rc 对状态属性的等待条件。“卡动画/黑屏”是可能症状，不足以单独证明 APEX 损坏。
3. **排查入口**：`getprop apexd.status`、`logcat -s apexd`、`ls /apex`、`pm list packages --apex`。日志锚点 "Bootstrapping done" / "Marking APEXd as activated/ready"。


**Q15: [learning] [tags:系统启动] servicemanager 是什么时候启动的？它为什么必须在大多数服务之前？**

servicemanager（Binder 的上下文管理器）由 init 在很早的阶段以 core 类启动，排在绝大多数 Binder 服务之前：每个 Binder 服务注册时要把“名字→句柄”写进 servicemanager，客户端按名字查询也必须问它——没有它，vold、surfaceflinger、zygote 之间的服务发现无法开始。

1. **特权差异**：servicemanager 自身运行在受限 SELinux 域，谁能注册、谁能查询哪个名字由 sepolicy 精确控制，这是服务面安全的第一道闸。
2. **层次**：原生层有 servicemanager。HIDL 时代另有 hwservicemanager 管理硬件服务，两者并存但职责分域。
3. **排障**：服务注册失败先看 servicemanager 是否存活，再看 sepolicy 是否允许该域的 add——“服务没起来”与“服务起来了但注册被拒”是两类问题。


**Q16: [learning] [tags:系统启动] init 的 selinux_setup 阶段做了什么？为什么第二阶段前要单独走这一步？**

`execv("/system/bin/init", "selinux_setup")` 之后，init 以该参数进入 SetupSelinux()：加载 SELinux 策略（system/vendor 策略合成后装载进内核）、以正确标签挂载 `/sys/fs/selinux` 相关接口、对早期挂载的目录做初始 restorecon，随后再以 `second_stage` 参数 exec 进入 SecondStageMain()。

1. **为什么独立成步**：策略装载后，init 此后的所有动作（启动服务、建 socket、写文件）才受强制访问控制约束——独立阶段保证“策略之后的启动全程受控”，不给策略装载前的窗口启动任何服务。
2. **排障**：卡住或失败的典型表现是停在加载策略前后的日志。策略本身的问题（neverallow、语法）归 SELinux 专题册。


**Q17: [learning] [tags:系统启动] init 第二阶段（SecondStageMain）按什么顺序做哪些事？之后的运行时骨架是什么？**

SecondStageMain 是“真正的 init 常驻体”的起点，初始化顺序大致为：初始化属性域与日志、装载 SELinux 后的策略上下文、建立 epoll 与信号处理（SIGCHLD 由 init 统一 wait 回收）、复位所有 dead 服务状态、依次执行 early-init → init → late-init 触发器的动作队列（rc 解析产物），其中完成 /data 相关挂载、启动 core 类服务，最后进入无限循环：epoll 等待属性设置、子进程退出、文件事件，驱动 action 队列与服务重启。

1. **运行时骨架**：此后 init 不再“顺序执行”，而是事件驱动——属性写入、服务退出、定时器各自入队，由主循环消费。
2. **排查意义**：开机卡住时看 init 日志里最后一条 "processing action (...)" / "processing service (...)"，即可定位卡在哪个动作或服务的启动上。
3. **边界**：init 常驻期的服务监督细节（崩溃、重启次数）见 init 重启策略题与 boot loop 题。


**Q18: [learning] [tags:系统启动] vold 在启动链里处于什么位置？加密设备的解密流程怎么走？**

vold（volume daemon）由 init 在 core 阶段启动，负责 /data 的挂载、FBE 密钥管理与存储事件。加密设备的开机分支：非凭据部分（DE，device-encrypted 存储）在 init 阶段即可挂载，框架以“直接启动”（directBootAware）模式运行有限服务。用户输锁屏凭据后 vold 用凭据派生密钥解开 CE（credential-encrypted）存储，解密完成后 vold 设置状态属性，init 命中 late_start 触发器启动完整框架，随后发出 LOCKED_BOOT_COMPLETED 与 BOOT_COMPLETED 两级广播。

1. **顺序要点**：无加密设备跳过解密直接 late_start。FBE 设备的“直接启动期”决定了哪些服务必须声明 directBootAware。
2. **排障**：卡在解密阶段看 vold 日志与凭据校验路径。DE/CE 混淆导致的数据“不可见”是常见误判。
3. **边界**：FBE 两级广播对应用的影响见本册 FBE 题。密钥体系细节归存储与安全专题。


**Q19: [learning] [tags:系统启动] apexd 在启动序列的什么位置？APEX 挂载失败或需要回滚时发生什么？**

apexd 由 init 早期启动（main 类、先于 zygote）：它在 /data 的 APEX 会话目录与预置 APEX 之间决定本此开机激活哪一套，把选定的 APEX 以 bind/dm-verity 方式挂载到 `/apex/<name>`，激活完成后置状态属性，之后的 zygote、system_server 与应用看到的运行库（如 Conscrypt、ART 相关模块）就是 APEX 版本——BOOTCLASSPATH 与 linker 配置也依赖激活结果（linkerconfig 据此生成 ld.config）。

1. **回滚**：APEX 升级采用会话化（staged session）：新会话在下次开机激活，若激活或验证失败，apexd 回滚到上一激活集，配合模块可控性测试。
2. **排障**：运行库版本“没生效/突然回退”，先看 `/apex` 挂载清单与 apexd 日志里的会话决策，再查是否处于回滚。
3. **边界**：APEX 的打包与交付归构建与 Mainline 专题，本册只讲它在启动链的位置与决策。


**Q20: [learning] [tags:系统启动] 启动期 SELinux 域经历了哪几次转换？每个阶段 init 跑在什么域里？**

init 的一组进程映像在不同阶段运行在不同 SELinux 域：first stage 的 init 运行在 `init` 域。exec 进入 selinux_setup 后按策略切换到 `selinux_setup` 域——该域只被授权做策略装载与最小文件操作。策略装载完成、exec 第二阶段时再转换到 `u:r:init:s0` 的常规 init 域，此后由它启动的各服务按 rc 里的 seclabel 进入各自域。

1. **设计意图**：把“装载策略”这一最高特权动作限制在专用短命域，装载完成后即放弃，降低 init 常驻域被滥用的影响面。
2. **验证方式**：启动早期 dump 内核日志或用 `ls -Z /proc/1` 观察域标签变化。
3. **边界**：各服务的域规则书写与 avc 处置归 SELinux 专题册，本册只关心“域随启动阶段流转”这一骨架。


**Q21: [learning] Android 启动时谁把 super 里的逻辑分区映射并挂载？bootloader 会挂载 super 或 /data 吗？**

bootloader 负责加载启动所需的物理镜像并启动内核，不负责解析 Android 的 `super` 元数据或挂载 `/data`。启动后由 first-stage init 根据 ramdisk 中的 fstab、动态分区元数据和设备功能创建逻辑 block device 并挂载指定系统分区。`/data` 则在后续 init 阶段完成密钥准备后挂载。

1. **bootloader 阶段：**bootloader 根据设备启动配置读取 boot chain 所需物理分区，例如 boot、vendor_boot、init_boot、dtbo 和 vbmeta，并将内核与 ramdisk 交给内核启动。具体组合取决于 Android 发布版本、GKI、A/B 与 recovery 布局。
2. **first-stage init：**init 从 ramdisk 启动并读取设备 fstab。对标记为 first-stage mount 的条目，它解析 `super` 元数据、创建逻辑分区映射设备，再挂载 system、vendor 等早期所需分区。fstab 中的分区类型和 first-stage 标记决定实际挂载集合，不能假设所有动态分区都会在同一时刻挂载。
3. **Virtual A/B 特例：**启用压缩快照的设备可能需要 first-stage init 在挂载系统分区前启动 ramdisk 中的 `snapuserd`，让逻辑设备读取经过 snapshot 映射的数据。切换到系统分区并加载 SELinux policy 时，init 还需按版本定义的时序重新启动或切换 snapuserd 上下文，避免 snapshot I/O 中断。
4. `/data` 阶段：init 在挂载 `/data` 前要按设备配置完成 metadata encryption 的密钥准备。常见 fstab/init 流程会在 late-fs 阶段等待 KeyMint/Keymaster 等依赖，再通过 `mount_all` 处理 `/data` 条目。阶段名称、等待服务和 fstab 标记随设备实现变化，因此具体顺序要检查产品 init rc 与 fstab。
5. **文件系统安全层：**只读系统分区通常可在 block device 上叠加 dm-verity 校验。`/data` 可配置 dm-default-key 等 metadata encryption 机制。Android 9 的 system-as-root 布局把 root 文件系统并入 `system.img`，由内核将 `system` 挂载为根文件系统。Android 10 起，逻辑 `system` 分区不能再由内核直接挂载，系统分区映射和早期挂载由 ramdisk 中的 first-stage init 处理。升级设备会保留其原有启动布局，不能只按运行的 Android 版本推断分区形态。

**Q22: [learning] android 中的 system-as-root 怎么理解？**

system-as-root（SAR）指根文件系统就是 system 分区本身：挂载后 system 镜像根部的内容直接出现在 `/` 下。init 二进制在镜像里位于 `system/bin/init`，成为根之后它在设备上的路径就是 `/system/bin/init`。构建时由 rootdir 模块（`system/core/rootdir`）在镜像根部创建指向它的符号链接，于是挂载后 `/init` 指向 `/system/bin/init`。内核启动第一个用户态进程的入口就是根下的 `/init`，保留这条路径使内核、既有工具和脚本都无需改动。该布局 Android 9 引入，Android 10 起成为新发布设备的要求。ramdisk 仍然存在，但只负责 first-stage 启动，不再提供根文件系统。

1. **和旧布局比：**旧布局由 ramdisk 提供初始根文件系统，`/init` 是 ramdisk 内的实体文件，system 作为普通分区挂载到 `/system`。SAR 下 system 同时承担根，init 再把根绑定到 `/system` 路径，按 `/system` 开头的旧访问方式继续可用。
2. **为什么 Android 10 起必须采用：**动态分区下 system 是 `super` 内的逻辑分区，内核只挂载物理块设备，无法把它挂为根。必须由 ramdisk 中的 first-stage init 解析 `super` 元数据、创建设备映射后再挂载。根从 ramdisk 换成 system，与 first-stage init 承担根挂载是同一变化的两面。
3. **怎么确认：**`ls -l /init` 第一列为 `l`，输出形如 `init -> /system/bin/init`——符号链接是一种内容为路径的特殊文件，访问它等于访问目标文件。`findmnt /` 应显示根来自 system 逻辑分区。两者同时成立即可判定设备采用 SAR。
4. **注意：**升级设备保留原有启动布局，非 SAR 设备上 `/init` 是实体文件。recovery 的根布局可能与正常启动不同。判断以设备 fstab 和分区表为准，不按 Android 版本反推。

**Q23: [learning] Android 启动时执行的 /init 是什么，为什么源码里有两个 init？**

`/init` 是 ramdisk 根目录中的 ELF 可执行程序，内核启动用户空间时执行它作为 PID 1。AAOS 13 中，它由 Soong 模块 `init_first_stage` 构建。模块名不是设备上的文件名。

1. **模块变成文件：**`system/core/init/Android.bp` 的 `srcs` 指定首阶段 C++ 源码，Soong 将其编译链接为程序。`stem: "init"` 把产物命名为 `init`。若省略，文件名默认取模块名 `init_first_stage`。Linux 不要求可执行文件带扩展名，内核按 ELF 格式识别程序，因此文件可以直接叫 `init`。构建产物在 `out/soong/.intermediates/system/core/init/init_first_stage/<目标变体>/init`。安装到产品输出树后通常是 `out/target/product/<产品名>/ramdisk/init`。
2. **文件路径变成启动路径：**产品把首阶段产物放进 ramdisk 根目录 `/`。根目录下名为 `init` 的文件，其绝对路径就是 `/init`。所以 `/init` 是文件路径，不是模块名或目录名。
3. **两个程序接力：**首阶段 `init` 静态链接，可在 `/system` 尚未挂载时运行。挂载系统分区后，它以 `selinux_setup` 参数执行 `/system/bin/init`。完成 SELinux 初始化后，后者再以 `second_stage` 参数进入完整服务管理。两次都用 `exec` 替换 PID 1 的程序映像，不创建新进程。
4. **在模拟器中查看：**启动后可用 `adb shell ls -l /init` 检查首阶段文件是否仍在。若存在且权限允许，可用 `adb pull /init` 拉到电脑。部分布局会在启动过程中清理或替换初始 ramdisk，且普通 `user` 镜像可能限制读取。此时从 `ramdisk.img` 或启动镜像提取它。启动完成后 PID 1 运行的是 `/system/bin/init`，可用 `adb pull /system/bin/init` 查看当前阶段的程序。`adb root` 仅适用于支持 root adbd 的 `userdebug` 或 `eng` 镜像。

这里的文件名和安装位置对应 AAOS 13 的 ramdisk 启动布局。具体产品应以实际 ramdisk 和启动配置为准。源码见 `system/core/init/Android.bp`、`first_stage_main.cpp`、`first_stage_init.cpp` 和 `main.cpp`。
