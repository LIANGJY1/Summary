# Android 系统启动流程

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：从按下开机键到 Launcher 上屏的完整启动链——init、.rc、Zygote、system_server 与应用进程的诞生与恢复机制；2026-10-06 扩充 Q47–Q96：镜像与校验层（boot 镜像/AVB/dm-verity/动态分区）、A/B slot 状态机与 OTA 验证、init 机制层（rc 模型/属性服务/触发阶段）、启动度量与实战杂症（含车机 ACC 唤醒、Car API/VHAL 时序）。配套架构主题见 [01-system-architecture.md](01-system-architecture.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] [tags:系统启动] Android 设备从上电到桌面可交互，启动链经过哪些阶段？**

启动链由 Boot ROM 建立硬件信任根，再把控制权交给 Bootloader 与 Linux 内核。内核启动 init 后，init 按配置建设用户态环境并拉起 Zygote。Zygote 创建 system_server，框架再按当前用户启动实际 HOME Activity。桌面首帧、动画退出、全局 boot 完成和每用户开机广播是不同里程碑，不能把其中一个当成其余事件的证明。

1. **Boot ROM 与 Bootloader**：SoC 上电后从 ROM 执行。ROM/Bootloader 按设备的 verified boot 链校验后续启动镜像，初始化 DRAM 并准备内核启动参数，最后跳入内核。具体镜像布局由设备实现决定。
2. **Linux 内核与 init 第一阶段**：内核初始化调度、内存和驱动，解包初始 ramdisk，并以 `/init` 作为第一个用户态程序。init 第一阶段挂载基础伪文件系统、加载早期模块并按 fstab 挂载启动所需分区。
3. **SELinux 与 init 第二阶段**：init 接力加载 SELinux 策略，再以第二阶段常驻形态启动属性服务、解析各分区 `.rc` 并执行启动动作，拉起原生守护进程和 Zygote。
4. **Zygote 与 system_server**：Zygote 初始化 ART 并预加载共享内容。主 Zygote 直接 fork `system_server`，不经过应用创建用的 socket 请求。`system_server` 分波创建系统服务并推进 BootPhase。
5. **AMS ready 与 HOME 选择**：系统服务进入就绪阶段后，AMS 执行 `systemReady()` 收尾。它依据当前用户和设备用户模式决定是否直接请求 HOME。AAOS headless system user 下，桌面通常由座舱前台用户的启动或切换路径请求，不能假定是 user 0。
6. **Activity 创建与屏幕启用**：ATMS 按用户和显示区域解析 `MAIN + CATEGORY_HOME`，再经 Activity 启动链创建 Activity。进程不存在时才经 Zygote 创建应用进程。首批前台 Activity idle 后，ATMS 推进开机收尾。WMS 还需等待窗口策略和 bootanimation 退出，之后通知 SurfaceFlinger 并启用显示与输入。
7. **全局和每用户完成状态**：动画完成后，AMS 才完成相应收尾、发布 `PHASE_BOOT_COMPLETED` 并设置 `sys.boot_completed=1`。UserController 仍按用户状态处理解锁和升级期 PRE_BOOT 接收者。满足条件后，它才向该用户发送 `BOOT_COMPLETED`。

定位启动故障时，先辨别停在哪一层和哪一个里程碑：进程创建、HOME Activity 启动、首帧、动画退出、全局 boot 属性、用户解锁及用户级广播不能互相替代。此处 Boot ROM/Bootloader/内核依据文档已核对的官方资料。Framework 与 AAOS 行为以 Android 13 `AAOS13_study` 源码锚点 `abec84ef9` 为准。





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



**Q5: [learning] [tags:系统启动] verified boot（AVB）是怎么保证“启动运行的代码没有被篡改”的？**

Android Verified Boot（AVB）用签名元数据和分区摘要建立从 Bootloader 到只读系统分区的验证链。校验失败后是拒绝启动、进入恢复流程还是显示警告，取决于设备锁定状态、验证模式和产品实现。

机制：

1. **签名与摘要分工**：vbmeta 保存经签名的分区描述符，例如预期哈希或 hashtree 根摘要。校验公钥来自设备信任配置，常由 Bootloader/硬件信任链保护。hashtree 本身不等于整张树都存进 vbmeta。
2. **运行期校验**：fstab 里的 avb= 标志让第一阶段挂载时配置 dm-verity。只读分区每读一块就核对树状哈希，盘上内容被篡改会表现为读取错误。
3. **解锁状态**：解锁 Bootloader 会改变设备的验证状态，通常允许启动未通过原厂链验证的镜像并显示警告。这不等于信任根必然被替换。锁定状态下的处理仍取决于验证结果和设备实现。

边界：AVB/dm-verity 保护启动镜像和配置为验证的只读分区，不对可写 `/data` 提供同一种分区完整性校验。FBE 提供文件级静态数据加密，不应描述成 `/data` 的通用完整性保护。

**Q6: [learning] 内核怎样启动第一个用户态进程 /init？为什么这个过程不是 fork？**

`/init` 不是从现有用户态进程 fork 出来的。内核初始化尾声创建的 `kernel_init` 内核线程以 PID 1 的身份调用 `kernel_execve("/init")`，用 init 程序映像替换自己的内核线程映像。

1. **execve 如何接力**：`kernel_execve` 丢弃旧地址空间，装载新 ELF、建立页表与入口栈。返回用户态时，PID 1 执行 init 的入口函数。若找不到 `/init` 或 ELF 损坏，exec 失败会导致内核 panic，设备无法完成启动。
2. **内核如何选择程序**：内核优先使用 `init=` 启动参数指定的路径，其次尝试 ramdisk 上的指定命令，再尝试 `/sbin/init`、`/etc/init`、`/bin/init`。Android 把自己的 init 放在 `/init`，占据第一顺位。
3. **为何 init 能早于系统分区运行**：早期 init 静态链接，自带所需 C 库，不依赖尚未挂载的 system 分区中的动态库。
4. **一个二进制的多种身份**：init 根据启动参数执行第一阶段、第二阶段、`selinux_setup`、ueventd 或 subcontext 执行器等路径。`/system/bin/ueventd` 可链接到同一个 init 二进制。

fork 复制已有进程，而此时没有用户态进程可供复制。execve 让内核线程进入 init 用户态映像。此后 Android 的 Zygote、system_server 和应用等用户态进程才由 init 启动的进程链派生。





**Q7: [learning] [tags:系统启动] 内核把控制权交给 init 的那一刻，系统精确处于什么状态？**

内核已把控制权交给 `/init`，但 init 第一阶段的工作尚未完成。此刻不能把 init 后续挂载、装载的内容算作内核已完成的状态。此时的状态如下：

1. **CPU/内核态**：调度器、内存管理和已注册的内核驱动可运行。设备所需 vendor 模块可能由 init 第一阶段稍后加载。
2. **进程**：当前用户态进程只有 PID 1。它刚由 kernel_init 内核线程 execve `/init` 变身而来。
3. **根文件系统**：刚解包的 ramdisk（内存里），只有 init、fstab、少量工具。
4. `/dev`、`/proc`、`/sys`：尚未由 init 挂载。
5. **系统分区**：system/vendor 分区尚未挂载，AVB 校验流程也未由 init 完成。
6. **SELinux**：策略未装载，强制访问控制尚未按 Android 策略生效。
7. **属性服务**：init 尚未完成第二阶段属性初始化，因此不能说系统属性“全部为零”。启动参数仍可从 `/proc/cmdline` 等内核接口读取。

收束：这份清点就是 init“建设者”职责的完整清单——它要补的每一项空白（窗口、真根、策略、服务、Java 世界）都对应上面一行。





**Q8: [learning] [tags:系统启动] init 为什么要在启动中途 execv 自己两次？三个阶段各自做什么？**

init 是同一个二进制以三个不同进程映像接力：第一阶段在 ramdisk 上搭最小环境，之后 execv 切入 selinux_setup 镜像装载策略，再 execv 切入 second_stage 常驻形态——中间两次 exec 是因为 SELinux 域转换只发生在 exec 时刻，这是把“同一程序不同阶段需要不同信任级别”交由内核保证的做法。

链路（Android 13 批注）：

1. **FirstStageMain**：挂载 `/dev`、`/proc`、`/sys` 与设备节点，调用 LoadKernelModules 加载 vendor 内核模块，再由 DoFirstStageMount 按 fstab 挂载 system/vendor 等只读分区。
2. `execv("selinux_setup")` 与 SetupSelinux：合成并装载 sepolicy，再完成 SELinux 域切换。
3. `execv("second_stage")` 与 SecondStageMain：执行 PropertyInit 与 SelinuxRestoreContext，建立 epoll、signalfd、属性 socket 等事件源，解析 rc 后进入常驻主循环。

三个阶段通过 `execv` 接力，因此全局变量与静态状态不会从前一阶段自动带到后一阶段。跨阶段数据可通过环境变量（如 `INIT_AVB_VERSION`）或文件传递。exec 接力适合需要程序映像或安全域边界的阶段划分。若只是逻辑分层，函数调用更简单，也更容易调试。





**Q9: [learning] /dev、/proc、/sys 三个伪文件系统分别提供什么信息？init 为什么要先挂载它们？**

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





**Q10: [learning] [tags:系统启动] GKI 时代，为什么内核模块要放在 vendor ramdisk 里而不是编进内核？**

GKI（Generic Kernel Image，通用内核镜像）把通用内核与符合 KMI（内核模块接口）的厂商模块分开交付，降低通用内核更新与设备专属驱动之间的耦合。设备驱动并非全部都是模块：部分可以内建，部分可在对应分区挂载后加载。只有挂载关键分区前就必须可用的模块，才需要放在早期可读取的 vendor ramdisk 等位置。

需要早于 vendor/system 挂载的模块放进 vendor ramdisk，是启动顺序的要求，不意味着所有厂商驱动都必须外置到这里。若某个存储驱动正是挂载相应分区的前置条件，就会形成“先挂分区才能取模块、先有模块才能挂分区”的依赖环。init 第一阶段从 ramdisk 加载该类模块来打破这个环。

因此判断模块放置位置应问“它是否是早期挂载的前置依赖”，而不是笼统按“厂商驱动”归类。`LoadKernelModules` 的具体模块集合由设备配置决定。





**Q11: [learning] [tags:系统启动] fstab 是什么？init 第一阶段怎么按它挂载分区？**

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





**Q12: Android 启动时如何装载 SELinux 策略？预编译策略校验失败时怎样回退？**

Treble 设备的 system 与 vendor 可独立更新，但内核最终只装载一份二进制 SELinux 策略。init 的 `SetupSelinux` 汇集 system、system_ext、product、vendor、odm 和 apex 等来源的 CIL 策略，校验后装载并切换到 enforcing。预编译策略与当前分区不匹配时，系统尝试用 `secilc` 现场编译。

1. **预编译策略校验**：AOSP 通过 plat、system_ext、product、apex 等来源的哈希对，确认预编译产物与当前分区一致。哈希应同时存在或同时缺失。只有一侧存在也视为不一致，转入回退流程。
2. **现场编译**：`secilc` 按目标分支要求组合输入文件。vendor 侧必需文件缺失会导致编译失败。product、system_ext、odm、apex 等可选输入缺失时可按分支规则跳过。临时产物放在 `/dev` tmpfs，用完后 unlink，因此现场编译问题主要靠启动日志排查。
3. **非 Treble 设备**：较旧的非 Treble 配置可直接使用 monolithic `/sepolicy`，不走 Treble CIL 汇编路径。
4. **版本兼容**：平台策略 mapping 版本依据 vendor 声明的 `plat_sepolicy_vers.txt`。新平台据此维持旧 vendor 策略所依赖的语义，vendor 无须随每次平台升级都重编策略。
5. **切换 enforcing 前的顺序**：`restorecon /dev/selinux` 必须在切换 enforcing 之前完成。进入 enforcing 后，执行域不一定还拥有修改这些文件标签的权限。
6. **OTA 与 snapuserd**：OTA 场景要按目标分支的五步启动编排读取策略，并在杀死 snapuserd 之前完成读取。否则策略装载后 snapuserd 每次读取 `/system` 都可能触发 AVC 审计，杀死 snapuserd 后又会失去读取 `/system` 的路径。
7. **APEX 与调试覆盖**：APEX 可更新策略用于增量强化。验签或解包失败时回退到 system 自带版本。userdebug 调试策略还要求 `INIT_FORCE_DEBUGGABLE` 环境变量与设备解锁两个条件同时满足，量产锁定设备没有该策略替换路径。

具体哈希文件集合、可选 CIL 输入和 OTA 阶段顺序受 Android 分支影响，排查时应对照设备对应的 init 源码与启动日志。





**Q13: [learning] [tags:系统启动] init 的属性服务是怎么工作的？为什么系统里到处都在用属性？**

属性服务是 init 维护的系统级键值服务。启动时各分区的 prop 文件提供初始值，进程读取共享属性区，写入则经属性 socket 提交给 init，由 init 按 SELinux 策略校验并更新属性，再通知监听方。

1. **属性用途**：`ro.*` 表示启动后只读属性。`persist.*` 属性会持久化到 `/data` 并跨重启保留。`init.svc.<name>` 是服务状态投影。`ctl.*` 是发给 init 的控制命令，不是状态值。
2. **启动编排**：init 可把服务状态写入 `init.svc.*` 供进程读取，也可在属性变化时触发 rc 动作，例如 `on property:xxx=yyy`。组件因此能通过“设置属性—匹配动作”协调启动顺序，而不必彼此直接调用。
3. **完成信号**：Android Framework 在全局启动收尾时设置 `sys.boot_completed=1`。监听方可据此判断全局启动阶段到达，但它不代表每个用户的开机广播均已送达。
4. **权限与容量**：SELinux 控制哪些执行域能写哪些属性前缀。属性有长度和数量上限，不适合承载大块数据。





**Q14: [learning] [tags:系统启动] .rc 文件怎么理解？**

`.rc` 文件是用 Android Init Language 写的声明式配置，相当于 init 的“启动脚本 + 服务注册表”：一个 `service` 块声明一个长驻进程（名字、可执行文件、参数与选项），一个 `on <触发器>` 块声明一组要执行的命令。init 第二阶段解析全部 `.rc` 后，按触发器执行动作、按服务定义 fork/exec 进程并监督。

以 Zygote 为例（AOSP `android-17.0.0_r1`，节选）：

```rc
# init.zygote64.rc
service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server --socket-name=zygote
    class main
    socket zygote stream 660 root system
    socket usap_pool_primary stream 660 root system
```

`init.rc` 里再由 `zygote-start` 触发器执行 `start zygote` 把它拉起。示例中各项配置与省略后果如下：

1. **service 命令**：`service zygote` 声明服务名，`/system/bin/app_process64` 是执行文件。若服务块或命令缺失，init 就没有这条 Zygote 启动定义。
2. **运行参数**：`-Xzygote` 为运行时选择 Zygote 模式，`/system/bin` 是传给运行时的系统目录参数，`--zygote` 进入 Zygote 启动路径。目标 AOSP 分支若省略其中必要参数，启动行为会改变或失败。
3. **创建 system_server**：`--start-system-server` 要求主 Zygote 在初始化中 fork `system_server`。省略后不能依赖该启动实例创建 `system_server`。
4. **Zygote socket**：`--socket-name=zygote` 指定与 init 创建的 `zygote` socket 相匹配的名称。`socket zygote stream 660 root system` 由 init 创建 Unix stream socket，权限为 `0660`，所有者 `root`，所属组 `system`。省略 socket 声明会让 init 不为服务预建该 fd。省略 socket-name 则由目标 app_process 版本的默认值决定，配置显式匹配可避免两端名称不一致。
5. **USAP socket**：`socket usap_pool_primary stream 660 root system` 为主 Zygote 的 USAP 池另建同权限的 stream socket。若设备不启用该路径，此 socket 可以不配置。启用后需与 Zygote 的池配置一致。
6. **class 分组**：`class main` 把服务归入 main 类，供 `class_start main` 一类命令批量启动。省略 class 时 init 使用默认服务类，不会自动成为 main 类成员。
7. `onrestart` 与其他选项：`onrestart` 用于声明服务退出并准备重启时要执行的动作，例如重启依赖服务。示例没有该项，因此没有额外声明此类动作。`.rc` 的其他服务选项仍按目标 init 版本的默认值处理。

`.rc` 把启动进程、启动参数、socket 和重启动作声明化，init 按触发器和服务配置执行。分析开机耗时与进程拉起顺序时，`.rc` 是第一手材料。





**Q15: init 第二阶段主循环如何处理事件？每轮只执行一条命令有什么作用？**

第二阶段 init 使用单线程事件循环。epoll、signalfd 和属性 socket 等事件源把工作送入主循环，循环每轮只推进一条 `Command`，其间处理 SIGCHLD、属性变化和 ctl 控制消息。单轮限制是为了避免长动作长期占住循环，使关机请求和子进程回收仍有机会及时处理。

1. **启动动作入队**：`LoadBootScripts` 解析 init.rc 与各分区 rc，建立 Service 表和 Action 表，再按 early-init、init、late-init 等触发器入队。rc 动作还会串起 early-fs、post-fs、late-fs、post-fs-data、zygote-start、boot 等阶段。
2. **事件匹配**：ActionManager 用事件队列和 Action 表分离事件到达与命令执行。事件包括命名触发器、属性变化对和内置动作指针。一个事件可命中多个 Action。匹配在锁内完成，执行在锁外进行。
3. **内置动作队列**：`QueueBuiltinAction` 把函数指针同时用作待执行命令和匹配条件。oneshot 动作执行后会从队列和登记表中删除。关机路径的 `ClearQueue` 清空待执行队列，但保留登记表项，使 shutdown 序列仍可运行。
4. **等待属性限制**：全局同时只允许一个 `wait_for_prop` 等待。rc 中连续写两条等待会串行等待，前一条完成后才进入后一条。

该循环适合管理常驻服务和启动时序，不适合承载需要高吞吐的后台任务。





**Q16: [tags:系统启动] ueventd 是怎么把空的 /dev 填满的？**

ueventd 是 init 同一二进制的另一种运行形态，主要职责是监听内核 uevent，并按规则创建和配置大部分设备节点。Android 的 `/dev` 通常由 tmpfs 承载，但并非每个节点都只能由 ueventd 创建，启动脚本和其他系统服务也可建立特定节点或符号链接。

机制：

1. **事件来源**：内核在设备注册或移除时发出 uevent，携带设备路径、主次设备号和子系统等信息。ueventd 通过 netlink socket 接收事件。
2. **冷插拔补齐**：内核早于 ueventd 启动，部分设备事件可能在 ueventd 监听前已经发出。ueventd 启动后遍历 `/sys` 并重放 uevent（coldboot），补齐这些设备的规则处理。
3. **权限规则**：ueventd.rc 规则声明设备路径、属主、组和权限位。例如 `/dev/binder` 可配置为 root 所有、binder 组、权限 `0660`。

创建设备节点的前提是驱动已注册。遇到节点缺失时，应区分驱动未加载或未匹配，与 ueventd 未按规则创建节点这两类原因。





**Q17: [tags:系统启动] init 是怎么把一个服务进程拉起来的？Service::Start 里有哪些容易忽略的细节？**

每个服务由 init fork 出子进程，再由子进程 exec 目标二进制。进程创建、cgroup 设置和 exec 之间有明确顺序：

1. fork 前，init 按服务声明创建 socket。
2. fork 后，子进程继承 socket fd 并等待父进程放行。
3. 父进程先把子进程加入 cgroup，再通过管道发送一个字节。
4. 子进程读到放行信号后才 exec 目标程序。

其中容易忽略的细节与设计如下：

1. **fd 继承传递**：环境变量 `ANDROID_SOCKET_<名字>` 传递 fd 编号，描述符本身通过 fork 继承。Zygote 接收应用创建请求的 socket 也是这样传入。无亲缘关系的进程间传 fd 要使用 `SCM_RIGHTS`。
2. **管道握手**：子进程 exec 前必须已进入 cgroup。父进程写一个字节、子进程读到后才继续，比轮询或延时更可靠地表达顺序约束。
3. **退出状态**：服务状态通过 `init.svc.<名字>` 属性汇报。服务退出后，init 将其交给 Reap 流程裁决。

fd 继承适用于有亲缘关系且 fork 顺序明确的进程树，不能替代无亲缘进程间的显式 fd 传递。





**Q18: [learning] [tags:系统启动] init.rc 的 service 块还有哪些关键选项？class_start/class_stop/class_reset 有什么区别？**

service 选项决定单个进程的退出、重启和分组启动行为。`class_start`、`class_stop`、`class_reset` 则管理整组服务。排查时要分别判断服务自身策略和 class 操作的效果。

1. **critical**：服务在配置的崩溃窗口内反复退出时可触发 fatal 重启。Android 13 对应实现的默认窗口为 4 分钟，累计达到阈值后触发，具体阈值和开机完成前后的行为应以目标分支为准。支持 `window`、`target` 的新语法也需按目标 init 版本核实。
2. **oneshot**：进程退出后不自动重启。省略时按常驻服务的默认重启策略处理。
3. **disabled**：服务不随所属 class 的批量启动命令启动，仍可由显式 `start <name>` 拉起。bootanim 采用 `disabled` 与 `oneshot`，由 SurfaceFlinger 按需启动，窗口管理器在显示就绪后请求退出。
4. **class 命令**：`class_start` 启动该类中可启动的服务。`class_stop` 停止并禁用该类服务。`class_reset` 停止该类服务但保留后续重新启动资格。
5. **版本演进**：`writepid` 已由 init 文档标记为过时，应按目标版本迁移到 `task_profiles`。Android 14 起相关 task profile 可作用于整个进程。`updatable` 允许 APEX 中的同名服务定义覆盖基础定义，并会延迟该服务直到 APEX 激活。
6. **排查入口**：`getprop | grep init.svc` 可查看服务状态属性。调试时可按目标分支支持情况使用 `init.svc_debug.no_fatal.<name>`，不要把调试属性留在量产配置中。





**Q19: [learning] [tags:系统启动] APEX 在启动链的哪一步激活？APEX 损坏时设备表现成什么样？**

在该 Android 13 启动配置中，init 会在 Zygote/system_server 启动前等待 apexd 的激活状态。apexd 扫描内置和数据分区中的候选包，完成校验与挂载后更新状态属性。激活失败时是否回退、重试或阻塞后续启动取决于失败类型与恢复策略，不能概括成所有 APEX 错误都永久卡在同一个状态。

1. **两段激活**：早期 bootstrap 阶段先处理启动关键依赖。`/data` 可用后再处理活动 APEX 并完成后续激活阶段。具体组件与属性状态以目标分支实现为准。
2. **故障表现**：验签或哈希错误可能触发回退、重试或失败状态，影响哪些服务继续启动取决于 rc 对状态属性的等待条件。“卡动画/黑屏”是可能症状，不足以单独证明 APEX 损坏。
3. **排查入口**：`getprop apexd.status`、`logcat -s apexd`、`ls /apex`、`pm list packages --apex`。日志锚点 "Bootstrapping done" / "Marking APEXd as activated/ready"。





**Q20: [learning] [tags:系统启动] Zygote 在 Android 进程模型里扮演什么角色？为什么应用进程要用 fork 而不是各自独立启动？**

Zygote 是带有 ART 运行时和公共预加载内容的模板进程。它通过 fork 派生 `system_server` 与应用进程，使后代复用初始化状态并以写时复制共享尚未修改的物理页。

init 第二阶段解析 `.rc` 后启动 Zygote。Zygote 完成预加载后由主实例直接 fork `system_server`，再进入 socket 循环等待普通进程创建请求。子进程随后 specialize，设置目标 UID/GID、SELinux 域和 seccomp 等安全身份，然后进入 `ActivityThread.main()`。

相对于每个进程独立启动，fork 有三项主要收益：

1. **省时间**：不必每进程重新初始化 ART、加载几千个预加载类。
2. **省内存**：预加载页与未写脏页被所有应用进程共享。
3. **同一起点**：所有进程从一致的运行环境出发。

COW 不等于零成本，后续写入和应用初始化会逐步产生私有页。主 Zygote 在初始化期间调用 `forkSystemServer()` 创建 `system_server`。普通应用由 `system_server` 经 Zygote 请求创建。





**Q21: [learning] [tags:系统启动] Zygote 是怎么被拉起的？启动后依次做什么？**

Zygote 由 init 按服务配置启动，随后在自身启动流程中预加载运行环境、创建 `system_server` 并开始接收应用进程请求。Android 17 的主线如下：

1. `init.zygote64.rc` 声明 Zygote 服务，`zygote-start` 触发器执行 `start zygote`。
2. init fork/exec `/system/bin/app_process64`，并传入 `--zygote`、`--start-system-server` 等参数。具体参数以设备选用的 zygote rc 文件为准。
3. app_process 初始化 ART 并进入 `ZygoteInit.main()`。未启用延迟预加载时，先加载常用类、资源与共享库。
4. Zygote 创建 `ZygoteServer`，使用 init 传入的 Zygote socket 与可选的 USAP 池 socket。
5. 主 Zygote 根据 `--start-system-server` 调用 `forkSystemServer()` 创建 `system_server`。
6. 父 Zygote 进入 `runSelectLoop()`，等待后续应用进程创建请求。

`system_server` 是主 Zygote 启动流程直接 fork 的子进程，不是 init 通过应用请求 socket 创建的服务。init 监督 Zygote 服务。system_server 的崩溃恢复由 Zygote 与 init 的上层恢复链处理。





**Q22: [learning] [tags:系统启动] Zygote 的 preload 到底预加载了哪些东西？为什么所有应用进程能直接共享？**

preload 阶段把“每个应用都需要的公共物”只加载一次：preloaded-classes 清单里的常用框架类、系统资源（drawable/color 资源表）、图形相关初始化与 JCA 安全 Provider。此后所有 fork 出的进程靠写时复制物理共享这些页——读到的都是同一份内存，谁写了那一页才真正复制。

机制：

1. **时机**：主 Zygote 在进入 socket 循环前执行 preload。次 Zygote 用 `--enable-lazy-preload` 跳过大头，只为 32 位应用按需补载。
2. **共享原理**：fork 复制页表而不复制物理页，preload 出来的类元数据与资源位图因此成为全体后代共享的只读页——“省时间”与“省内存”两个收益同源于此。
3. **代价**：清单里的每个类都被全体应用背着——加类开机变慢、删类各应用首载变慢，preloaded-classes 的每次调整都是全局权衡。

收束：排查应用首帧慢时，“目标类不在 preload 清单、首次加载要自己付全部成本”是一个常被忽略的取证点。





**Q23: [learning] [tags:系统启动] Zygote preload 用开机成本换取什么？删减预加载清单或使用 lazy preload 分别要注意什么？**

预加载把一部分应用启动工作转移到 Zygote 启动阶段，并让 fork 后代通过 COW 共享相应内存。调优时要同时衡量开机成本、共享内存收益和首启延迟。

1. **预加载范围**：主 Zygote 在 fork `system_server` 前加载 Framework 类、资源、app-process HAL 与图形驱动、共享库和字体缓存等。AAOS 13 所用源码的 `frameworks/base/config/preloaded-classes` 约有 1.6 万行。数量随源码分支变化。
2. **扩展清单的代价**：增加预加载项可能减少应用启动期类加载，却会增加 Zygote 启动工作、共享页占用和脏页风险。
3. **删减清单的代价**：移除预加载项可减少启动工作，但使用这些类的应用可能在首次加载时承担额外成本。
4. **评估方法**：在干净开机和多应用场景记录 Zygote 预加载时长、`system_server` ready 时间、Zygote PSS、代表应用 TTID/TTFD，以及低内存设备上的重启与 swap。Boot image profile 调优会同时考虑 boot classpath Profile、system_server Profile 与预加载清单，数据应来自真实 CUJ 并随系统镜像发布。
5. **lazy preload 的边界**：`--enable-lazy-preload` 跳过启动时的 preload，但首次收到 preload 请求时，`ZygoteInit.lazyPreload()` 仍执行同一套完整预加载。因此它改变支付时间，不是增量拆分。AAOS 13 材料中的主 64 位 Zygote 不传该参数，32 位 secondary Zygote 传入。验证时分别记录 primary 的 ZygotePreload、secondary 的 ZygoteInitTiming_lazy，以及首个 32 位进程请求前后的延迟。
6. **应用专属类**：业务应用自己的类通常不属于系统 Zygote 的通用预加载集合，不应通过扩展系统预加载解决单个应用的启动问题。





**Q24: [learning] [tags:系统启动] Zygote 的 fork 模型有哪些硬约束？“zygote 本体没有 Binder”是怎么来的？**

Zygote fork 会复制地址空间和线程状态，因此 Android 必须在 fork 前控制线程创建，并在 fork 周围暂停运行时活动。preload 期间不能随意创建线程，GC 线程会在 preFork 阶段暂停，Binder 线程池则延迟到 fork 后的子进程初始化。`nativeZygoteInit` 只在子进程路径调用。若 Zygote 本体在不受控时启动线程或 Binder 工作，后代可能继承不一致的线程状态。

配套手法：

1. **fd 继承传递**：init 预建监听 socket，fork 后子进程继承 fd。环境变量 `ANDROID_SOCKET_<name>` 传递 fd 编号。无亲缘关系的进程之间传递 fd 则需使用 SCM_RIGHTS。
2. **Runnable 退栈**：fork 后子进程暂时保留 Zygote 的调用栈。Android 将目标 main 包装为 Runnable，并沿调用链逐层 return，退回 fork 分支后才执行目标入口，避免业务入口长期保留孵化调用栈。
3. **请求方身份校验**：应用创建请求依据 socket 对端凭据（SO_PEERCRED）校验调用方，不能只相信请求内容自报的身份。具体权限仍由本地 socket 的访问控制与 Zygote 侧校验共同决定。

边界：“模板进程 + N 个派生进程”的架构才适合 fork 模型，差异大的负载（独立工具进程）fork 反而拖累（继承整个 VM）。Android 13 批注还勘误了一处上游过时注释：现行代码用普通 return 退栈，不是历史上的抛异常方式。





**Q25: [learning] [tags:系统启动] USAP 池与“厂商预启动”是什么关系？为什么 trace 里没看到 fork 不能证明系统预启动了应用？**

USAP（Unspecialized App Process）池会预先 fork 尚未绑定应用身份的进程。满足启动策略的请求可以复用池成员，再通过 `specializeAppProcess` 设置 UID/GID、SELinux 标签与数据目录。厂商的“预测启动”则是策略名称，不能仅凭该名称推断实际创建了应用进程。

按 AAOS 13 源码，`ZygoteProcess.shouldAttemptUsapLaunch()` 需要同时满足以下条件：

1. 设备支持 USAP 池。
2. 当前配置启用了 USAP 池。该分支默认值为 false。
3. 调用策略选择 USAP 路径。
4. 当前命令类型受 USAP 支持。

该分支只为符合策略的延迟敏感、非 system process 请求尝试 USAP。需要 wrapper 进程、child Zygote 或预加载包的命令会退回普通 Zygote 路径，child Zygote 本身不支持 USAP。

trace 中没有看到 fork 不能单独证明应用被厂商预启动，至少要排查以下来源：

1. 目标进程在观察窗口前已经存在，例如 cached 进程。
2. 请求复用了已预 fork 的 USAP，当前窗口只观察到 specialize。
3. 进程来自 App Zygote。
4. trace 时间窗没有覆盖创建阶段。

核对 PID 创建时间、父进程、`bindApplication`、USAP 状态和 `Zygote:FillUsapPool` 事件。`dalvik.vm.usap_pool_enabled` 与 `runtime_native` DeviceConfig 可用于区分代码支持与设备当前配置，但单个属性不能证明某次请求实际命中 USAP。

厂商预测策略可能触发 ART 编译或 profile 维护、文件页预取、保留 cached 进程、填充 USAP 池、创建私有预热进程，或短时调整调度与 I/O 优先级。要确认策略来源需查产品文档、日志或调用链。trace 用来证明实际动作和耗时效果。对照命中与未命中组时还要固定网络、温度、编译状态和页缓存条件，不能以“点击后很快”推断系统提前创建了目标进程。





**Q26: [learning] [tags:系统启动] 主 Zygote 和次 Zygote 怎么分工？preload 与 USAP 池各有什么坑？**

双 ABI 设备通常由 64 位主 Zygote 服务 64 位应用并创建 `system_server`，32 位次 Zygote 服务 32 位应用。具体实例由设备 ABI 列表和 zygote rc 配置决定，不能仅凭“Android 有主次 Zygote”推断每台设备都运行两个实例。

1. **进程职责**：`--start-system-server` 由主 Zygote 使用。次 Zygote 不创建 `system_server`。次 Zygote 是否采用 `--enable-lazy-preload` 由启动配置决定。
2. **就绪关系**：框架启动时可等待配置要求的次 Zygote 就绪。这是启动依赖关系，不代表两个 Zygote 互为看门狗。
3. **预加载取舍**：扩大预加载集合会增加 Zygote 启动工作，但可让子进程共享更多类。删减会缩短部分启动工作，却可能增加应用首次加载成本。
4. **USAP 取舍**：USAP 预先 fork 待命进程，取用时再 specialize。若启动请求不受支持或池不可用，会回退到普通 Zygote 路径。调试器附加等场景也可能走普通 fork。
5. **注入兼容性**：USAP 改变了 fork 时机，依赖特定 fork 钩子的注入框架可能受影响。Magisk/Zygisk/Riru 等社区案例应按框架版本和设备实现核查，不能概括为必然不兼容。

排查应用创建路径时，先确认设备是否实际运行双 Zygote、USAP 是否配置启用，以及请求是否处于调试或其他回退场景。





**Q27: [learning] [tags:系统启动] USAP 为什么默认关闭？开启前要确认什么？开了以后还能用 PostFork trace 诊断吗？**

USAP 默认关闭是为了让设备显式评估进程创建收益、资源占用和路径兼容性后再启用。启用前要核实属性优先级、池配置、请求覆盖范围和目标 Zygote 类型。开启后仍可用 PostFork trace 分析 specialize 与类加载耗时。

1. **默认值来源**：AAOS 13 源码中的 `ZygoteConfig.USAP_POOL_ENABLED_DEFAULT` 为 false。实际开关依次读取 `persist.device_config.runtime_native.usap_pool_enabled`、`dalvik.vm.usap_pool_enabled`，再回退到内置默认值。具体优先级应以目标分支为准。
2. **启用前检查**：进程创建密集的场景才可能从预 fork 获益。还要核对池大小等参数和目标 Zygote 是否支持。该分支 child Zygote 的 `mUsapPoolSupported` 为 false。
3. **PostFork 诊断**：USAP 被取用后仍会执行 PostFork 相关处理、specialize 和后续类加载，因此 trace 可继续观察这些阶段。应先确认 trace 标记的进程创建来源，避免把填池 fork 与请求命中的 specialize 混为一谈。
4. **收益判断**：USAP 预先支付的是空壳 fork 成本。如果 PostFork 之后的 specialize 或类加载占主要耗时，开启 USAP 对该部分帮助有限。这是由它提前 fork、但仍需 specialize 的机制推导出的判断，需用目标设备数据验证。





**Q28: [learning] [tags:系统启动] fork 派生模型的内核成本省在哪？16 KB 页会改变 COW 的什么？**

省在 COW（写时复制）：fork 时内核 dup_mmap 只复制页表并清除写权限，子进程首次写触发缺页、走 do_wp_page/wp_page_copy 才复制页面——预加载的类、资源与驱动初始化状态因此被全部 app 共享，只有被写的页付复制成本。

16 KB 页改变的是 COW 粒度：单次复制页从 4 KB 变 16 KB，不改变 VMA（虚拟内存区域）数量。机制推导：同样写入模式下，16 KB 设备的页级写放大更大、缺页次数更少，内存账与 4 KB 设备不可直接对比。做法：评估 Zygote 派生收益读 Private_Dirty——共享页 RSS 高而 Private_Dirty 低是健康态。跨页大小比较时分别测量。





**Q29: [learning] [tags:系统启动] App Zygote（ZygotePreload）适合什么场景？它和系统 Zygote 的预加载边界怎么分？**

App Zygote 是应用自己的“应用级 zygote”：API 29 起，manifest 配 `useAppZygote="true"` 并用 `android:zygotePreloadName` 指定实现 ZygotePreload 的类，先孵化一个持有应用公共状态的进程，再由它 fork 出实际服务进程（如 isolated 进程、WebView 渲染进程）。

分界原则：预加载只有被 N 个子进程共享才有收益——全 app 公共内容放系统 Zygote（preload 一次全场共享），应用专属公共内容放 App Zygote（池内共享），单进程独享的放进程自己的启动路径。机制：App Zygote 池内 COW 共享，子进程崩溃可回池再 fork。32 位 WebView 可由 secondary Zygote 按需预载其 provider 代码。





**Q30: [learning] [tags:系统启动] 普通应用进程是怎么诞生的？它和 system_server 的诞生路径差在哪？**

普通应用冷启动路径：

1. 桌面点击，经 Binder 请求 `system_server` 的组件管理服务（ATMS/AMS）。
2. `ProcessList` 收集 UID/GID、targetSdk、SELinux seInfo、ABI、数据目录、入口类 `android.app.ActivityThread` 等参数。
3. `Process.start()` → `ZygoteProcess.startViaZygote()` 把参数编码成 Zygote 命令写入 LocalSocket。
4. Zygote 侧 `runSelectLoop()` 收到连接，`ZygoteConnection.processCommand()` 读取 peer credentials、校验参数。
5. fork 出子进程并 specialize（降权到应用 UID/GID、SELinux 域、seccomp）。
6. 子进程进入 `ActivityThread.main()`：`Looper.prepareMainLooper()` 后 `attach(false, ...)` 经 Binder 回连 `system_server` 完成 `bindApplication`。
7. 父进程把 PID 返回调用方。

与 system_server 的差别：system_server 由主 Zygote 在启动流程里用 `forkSystemServer()` 一步直接创建（参数 `--start-system-server`），不经 socket 请求。普通应用全部走 socket 请求路径。USAP（Unspecialized App Process）池只是把 specialize 提前到“预备进程”，改变不了路径归属，失败时回退主 socket。

排查边界：拿到 PID 只说明 Zygote/USAP 侧创建完成，`bindApplication`、组件生命周期、首帧都是后面的事——发起进程启动、返回 PID、attach 完成三个时间点要分开取证。





**Q31: [learning] [tags:系统启动] Android 13 的 systemReady 回调怎样协调 system_server 服务就绪与当前用户启动？**

`systemReady()` 是 AMS 与 SystemServer 的启动交接点，不等于“回调一结束就由 AMS 给所有设备的 user 0 拉起桌面”。AMS 先打开进程和 Activity 管理的就绪门闩，再运行 `goingCallback` 让 SystemServer 推进后续服务阶段。回调返回后才重新读取当前用户，并按用户模式决定 HOME 启动路径。

1. **回调前准备**：AMS 设置系统就绪状态，通知内部控制器，并完成启动期必要检查。进程启动门槛开始放行。
2. **执行 goingCallback**：AMS 把控制权交还 SystemServer，回调推进 `PHASE_ACTIVITY_MANAGER_READY` 等后置初始化。回调可能触发用户启动或切换，所以不能把回调前读到的 user ID 缓存下来继续使用。
3. **回调后读取用户**：AMS 重新取得当前前台用户，并检查非 system user 启动时 system user 已处于 started 状态。这是多用户启动状态的前置约束。
4. **分流 HOME 请求**：只有当前用户是 system user 且设备不是 headless system user 模式时，AMS 才在这个 `systemReady()` 路径直接调用 `startHomeOnAllDisplays()`。AAOS headless 模式下，user 0 承载系统服务。前台座舱用户启动或切换时，UserController 会经 ATMS 请求目标用户 HOME。

这里的 HOME 请求仍不是指定启动 CarLauncher：ATMS 要针对用户和显示区域解析 HOME 候选，设备配置、默认 HOME 和包状态决定最终组件。`systemReady()` 也不等于桌面首帧或系统 boot 完成。Activity idle、动画退出和每用户广播仍各有独立门槛。





**Q32: AAOS 在标准 Android 启动链的哪三个挂点接入车机服务、SystemUI 与 HOME？**

AAOS 沿用通用 Android 启动链，并在服务绑定、SystemUI 依赖图和 HOME 组件解析三个位置接入车机实现。具体实现取决于产品配置与当前前台用户。

1. **CarService 接入**：AMS 的 system-ready 收尾启动 framework 侧 CarServiceHelperService，由其绑定可更新的 CarService APK。CarService 以 `com.android.car` 进程运行，框架与车机逻辑通过 ICar Binder 接口通信。CarServiceHelperService 的完整实现不在本地源码检出范围内，因此不能从类名推断绑定和重试细节。
2. **SystemUI 接入**：`startSystemUi` 启动 SystemUI 时通过 AppComponentFactory 装配车机依赖图，让车机版本的组件替换根组件实现。
3. **HOME 接入**：AMS/ATMS 发起通用 HOME 请求，PackageManager 按用户解析候选组件，产品配置可能选中 CarLauncher。headless system user 模式下，system-ready 分支不会直接为 user 0 启动座舱 HOME。

这三处是宿主接入点，不代表每个产品必须使用同一组应用或在同一时刻启动座舱用户。





**Q33: [learning] AAOS 的 CarService APK 启动后怎样连接 VHAL、初始化车机服务并注册 Binder 接口？**

Framework 通过 CarServiceHelperService 绑定 CarService APK。CarService 建立 VHAL 客户端和车机子服务后，注册 `car_service` Binder 实例。具体绑定实现受产品版本影响。

1. **启动与连接**：Android 13 源码路径从 `CarServiceImpl.onCreate()` 进入 `VehicleStub.newVehicleStub()` 并连接设备 VHAL。实现依据设备声明的服务选择 AIDL 或 HIDL backend，不能只按系统版本推断设备实际使用哪一种。
2. **构造与初始化**：`ICarImpl` 构造车机子服务并放入 `mAllServices`。该源码分支约有 30 个服务。`init()` 先初始化 VHAL，再按服务表顺序初始化依赖服务。
3. **发布服务**：CarService 经 ServiceManager 注册 `car_service`，并置 `boot.car_service_created=1`。CarServiceHelperService 持有服务端 Binder 连接，CarService 持有 framework 侧接口，以便双方调用。
4. **VHAL 死亡恢复**：Android 13 实现中的 `VehicleDeathRecipient` 检测到 VHAL Binder 死亡后会终止 CarService 进程，再由绑定路径重建。该故障策略是该源码分支的实现，不应推广为普通应用服务的通用生命周期规则。
5. **版本边界**：服务表内容、AIDL/HIDL backend 选择和 CarServiceHelperService 重试细节可能随分支和产品配置变化。核对设备分支源码与实际 Binder 服务状态。





**Q34: [learning] AAOS 的 CarSystemUI 怎样通过 AppComponentFactory 接入车机组件？**

CarSystemUI 通过合并构建和 `AppComponentFactory` 替换依赖图来复用原生 SystemUI 启动流程，不需要 fork 原生源码。前提是目标 SystemUI 代码已通过 Dagger 暴露可替换的根组件边界。

1. CarSystemUI 在 manifest 声明 `CarSystemUIAppComponentFactory`。
2. 进程创建时，factory 将 Dagger 根组件替换为车机实现，例如 `CarGlobalRootComponent` 与 `CarWMComponent`。
3. 原生 `SystemUIService` 继续执行 `startServicesIfNeeded`，再由 Dagger 展开 `CoreStartable` 服务。
4. 这种方式把定制集中在依赖图边界。若目标代码尚未 Dagger 化或组件边界不稳定，就不能直接假设可用同一方式替换。
5. `CarSystemUIInitializer` 只为 system user 注入 `RootTaskDisplayAreaOrganizer`。此隔离与驾驶员用户的桌面内容不是一回事。
6. `car_service` 进程死亡不会按此机制直接杀死 CarSystemUI。客户端会经历 Binder 断连并尝试重连，依赖 CarService 的 UI 能力可能暂时不可用。排查时分别观察服务端重启和客户端恢复状态。





**Q35: [learning] AAOS 的 CarLauncher 怎样用 TaskView 嵌入地图任务？**

CarLauncher 是 ATMS 发起通用 HOME 请求后可能被 PackageManager 选中的组件。选中后，它可用 TaskView 将地图应用作为受控任务嵌入桌面，并通过 HomeCardModule 装配顶部和底部卡片。是否实际启动仍取决于用户、显示区域和 HOME 解析结果。

1. **任务边界**：地图 Activity 运行在另一个任务和进程中，不是普通 View。其崩溃、焦点与生命周期行为需要按 TaskView/TaskOrganizer 的任务规则处理。
2. **崩溃恢复**：`autoRestartOnCrash=false` 表示 TaskView 不依据该选项立即自动重启任务，不代表永远只能由用户手动重进。宿主可在可见、用户解锁、Display 可用或依赖包变化等条件满足后尝试恢复。
3. **多用户边界**：CarLauncher 对 headless system user 的地图 TaskView 有单独限制。这不表示前台驾驶员用户的桌面也没有地图卡片。
4. **定位方法**：检查目标用户和显示上的 HOME 解析结果、TaskView 宿主状态、地图任务状态及依赖包状态。仅凭 CarLauncher Manifest 声明不能证明它已被选中或地图任务已创建。





**Q36: [learning] [tags:系统启动] 误删或禁用了桌面应用，设备开机会怎样？FallbackHome 是干什么的？**

禁用当前用户的真实桌面不必然导致 boot loop，但也不能假定任意设备、任意用户都必定有同一个 fallback。AOSP 可通过 Settings 的 `FallbackHome` 在凭据加密存储尚不可用时提供过渡 HOME。Framework 还会按 system user 设置条件启用 `SystemUserHomeActivity`。实际候选和回退顺序由系统版本、用户类型、包状态及产品配置决定。

1. **FallbackHome**：以低优先级 HOME 候选常驻。解锁前它就是 resolve 结果，`onCreate` 注册 `ACTION_USER_UNLOCKED` 广播，解锁后 `finish()` 让系统重新 resolve 到真桌面。
2. **SystemUserHomeActivity**：Framework 中的占位 HOME 组件，作用范围是 system user。AMS 按 split system user 的 setup 状态或系统属性决定是否启用它。它不是任意前台用户都可用的通用桌面。
3. **实用**：`cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME` 可查询 HOME 候选。禁用桌面前应在目标 Android 版本和目标用户下核实实际解析结果。





**Q37: [learning] [tags:系统启动] 开机动画由谁拉起、怎么退出？“开机动画卡死不退出”这个经典回归怎么查？**

bootanim 是 init 声明的 `disabled + oneshot` 服务：SurfaceFlinger 初始化显示后经 init 按需启动它。启屏阶段由 WMS 请求动画退出，并等待 init 确认服务已停止。WMS 随后才通知 SurfaceFlinger `BOOT_FINISHED`，因此启动动画、停止动画和 SurfaceFlinger 的 boot-finished 通知是有先后关系的三个动作。

1. **拉起**：`bootanim.rc` 将服务归入 `core` 和 `animation` 两个 class，并声明 `disabled` 与 `oneshot`。SurfaceFlinger 满足显示启动条件后，经 init 的 `ctl.start` 请求拉起它。省略 class 会影响批量启动分组。省略 `disabled` 可能让服务随 class 启动。省略 `oneshot` 会按常驻服务的退出策略处理。具体默认与 rc 配置以目标分支为准。`init.svc.bootanim` 属性反映服务状态。
2. **退出链**：WMS 的 `performEnableScreen()` 等待开机策略和需要显示的系统装饰窗口就绪，再设置 `service.bootanim.exit=1`。bootanimation 在帧循环中观察该属性，并按动画 part 规则收尾。WMS 轮询 init 服务状态，确认进程已停止后才发送 SurfaceFlinger `BOOT_FINISHED` 并启用显示/输入。SurfaceFlinger 的 `bootFinished()` 也会设置退出属性，但在 WMS 已确认动画服务停止之后发生。
3. **卡死排查**：`desc.txt` 中 `c` 类 part 会在退出前播完未完成的 `c` part，`p` 类可在退出时中止。退出属性已设置但进程仍运行时，检查当前 part 类型、帧循环是否继续，以及 WMS 是否仍在等待系统装饰窗口或显示策略。
4. **自定义资源**：动画帧需按序命名为 PNG，分辨率要与 `desc.txt` 首行一致。`zip -0qry` 使用 store 模式打包，避免压缩动画帧。参数具体含义按目标主机的 zip 工具版本确认。





**Q38: [learning] [tags:系统启动] FBE 设备重启后、用户还没输锁屏密码，闹钟类应用怎么才能正常响？LOCKED_BOOT_COMPLETED 和 BOOT_COMPLETED 是什么关系？**

`directBootAware="true"` 的组件在用户解锁前就能被系统拉起并收到 `LOCKED_BOOT_COMPLETED`，但此时只能访问设备加密（DE）存储。用户输完锁屏收到 `ACTION_USER_UNLOCKED` 后，凭据加密（CE）存储才可用——FBE 的 DE/CE 密钥机制见 [../06-memory-storage/06-storage-io.md](../06-memory-storage/06-storage-io.md)。

1. **接收解锁前事件**：在 manifest 为需解锁前运行的组件声明 `directBootAware="true"`。省略该属性时组件默认不参与 Direct Boot 阶段的启动和广播处理。接收器应监听 `LOCKED_BOOT_COMPLETED`，此时只能访问 DE 存储。
2. **区分用户状态**：`UserManager.isUserUnlocked()`（API 24+）用于判断目标用户是否已解锁，不能用设备已开机替代用户解锁状态。
3. **访问 DE 存储**：`createDeviceProtectedStorageContext()` 返回设备保护存储上下文。`moveSharedPreferencesFrom()` 和 `moveDatabaseFrom()` 可用于迁移对应数据。迁移应在目标用户解锁、CE 可访问后按应用数据策略执行。
4. **恢复持久任务**：重启不会保留进程内的 alarm/job 对象。Direct Boot 接收器应在 DE 中保存“重启前存在待恢复任务”的必要信息，之后在 CE 可用时读取所需用户数据并重建任务。
5. **常见故障**：若只在 `BOOT_COMPLETED` 恢复闹钟，用户尚未解锁时不会执行该恢复逻辑。Direct Boot 组件若直接访问 CE 路径，也可能遇到文件不可用或数据库打不开的问题。FBE 下 CE 密钥何时可用取决于用户解锁。





**Q39: [learning] [tags:系统启动] 开机广播 BOOT_COMPLETED 有时收不到、有时收到就 ANR——它的送达条件、超时和限制到底是什么？**

送达问题先区分用户状态、包状态和接收器执行时限。AAOS13_study 的 Android 13 基线中，AMS 前台广播预算为 10 秒、后台为 60 秒，并受 `Build.HW_TIMEOUT_MULTIPLIER` 影响。不要把这一组默认值无条件套到其他 Android 分支或厂商配置。

1. **超时 ANR**：`onReceive()` 未在目标队列的执行预算内返回会触发广播超时 ANR。检查 ANR trace 中的 receiver 进程栈和对应 broadcast queue，不要只依据“收到广播”定位。
2. **stopped state 拦截**：带 `FLAG_EXCLUDE_STOPPED_PACKAGES` 的广播不会启动处于 stopped state 的包。新装未启动或被用户 force-stop 的应用可能处于该状态。省电清理是否造成 stopped state，要看其实际执行的系统动作。
3. **用户级送达条件**：Android 13 的 UserController 按用户推进开机状态。目标用户的 CE key 解锁后，升级场景还需等 `PRE_BOOT_COMPLETED` 接收者处理完成，才发送 `BOOT_COMPLETED`。headless system user 下，system user 与前台座舱用户沿各自状态路径处理。
4. **全局属性不是广播回执**：AMS 设置 `sys.boot_completed=1` 后仍由 UserController 逐用户处理广播。因此属性为 1 不能证明某用户的 `BOOT_COMPLETED` 已送达或接收者已处理完成。
5. **后台启动限制**：应用不能把开机广播后直接拉起界面当作通用入口。应遵守目标版本的后台 Activity 启动限制，通常由接收器安排受约束的后台工作，再通过用户可见入口展示界面。

`onReceive()` 应只做轻量调度。需要异步处理时，按 API 契约调用 `goAsync()` 并及时调用 `PendingResult.finish()`。可延期的持久工作可交给 WorkManager。启动前台服务仍须符合目标版本的服务启动和类型限制。





**Q40: [learning] [tags:系统启动] webview_zygote 是什么？应用声明 isolatedProcess 的服务跑在什么进程里？**

webview_zygote 是供 WebView 渲染进程使用的专用 Zygote。应用声明 `android:isolatedProcess="true"` 的服务则使用隔离 UID 和对应 SELinux 域。二者都涉及隔离进程，但普通 isolated service 不因此变成 WebView renderer，也不必由 webview_zygote 孵化。

1. **webview_zygote**：它按目标 ABI 预载 WebView provider 代码并孵化 renderer 进程。provider 更换时系统会按其更新流程重建相应孵化器。`ps -A | grep zygote` 可检查相关进程，`dumpsys webviewupdate` 可查看 provider 与 zygote 状态。
2. **isolatedProcess**：`android:isolatedProcess="true"` 请求系统把 Service 放入隔离 UID 和相应 SELinux 域，限制其权限与资源访问。省略或设为 false 时，服务不会因该属性获得隔离进程。是否仍因其他组件规则分进程需另行判断。WebView renderer 也受隔离策略约束，但启动用途不同。
3. **进程孵化器选择**：主/次 Zygote 由 `ro.zygote` 与设备 ABI 配置决定。应用可用 `android:useAppZygote="true"` 请求应用专属 Zygote，该属性省略时不请求此路径。不能仅凭 `isolatedProcess="true"` 推断服务来自 webview_zygote。





**Q41: [learning] [tags:系统启动] 服务崩溃后 init 的 Reap 裁决按什么顺序处理？哪些情况会放大成整机重启？**

Reap 负责处理 init 已监督服务的退出，并按顺序决定清理、状态和重启动作：

1. **清理进程资源**：回收服务进程，处理残留进程组和非 persist socket。
2. **检查失败策略**：服务若声明 `reboot_on_failure` 且异常退出，按配置触发重启。
3. **决定服务后继状态**：oneshot 服务正常退出且非手动停止时可能进入 disabled，不能再随 class 自动启动。
4. **评估重启条件**：按服务状态、重启策略和关键服务阈值判断是否重新拉起或进入系统级处理。
5. **准备复活**：执行 rc 声明的 `onrestart` 命令，服务进入 RESTARTING 等待后续主循环重启。

Reap 会触发的系统级后果分三类，不能把“写入故障属性”和“立即重启”混为一谈：

1. **critical 服务**：Android 13 该路径的默认崩溃窗口为 4 分钟，计数超过 4（第 5 次）后可触发 fatal。重启目标由服务或产品配置决定，不一定是 bootloader。
2. **APEX 可更新组件进程**：相应崩溃计数超过门槛后可设置 `sys.init.updatable_crashing`，通知 apexd/update_verifier 处理。这与 critical 服务立即进入 fatal reboot 的路径不同。
3. 显式声明 `reboot_on_failure` 的服务异常退出：按配置直接触发重启。

服务状态由可组合的 SVC_* 位标志表示，而不是互斥枚举，因此 oneshot、disabled、critical 等状态可能并存。oneshot 服务正常退出后会进入 disabled，不会再被 `class_start` 拉起，需要显式 `start`。stop 后立即 start 时的 RESTART 中间态会跳过置 disabled 的步骤，否则服务可能无法重新启动。





**Q42: [learning] [tags:系统启动] 设备反复重启进不了桌面（boot loop）——init 对关键服务反复崩溃的判据是什么？adb 不可用时怎么拿到上一次崩溃的日志？**

init 对 `critical` 服务的崩溃计数有明确门槛：默认 4 分钟窗口内计数超过 4，也就是第 5 次崩溃时触发 fatal。开机完成前崩溃同样进入计数逻辑。触发后的重启目标由服务配置决定，不能概括为所有设备都进 bootloader。

1. **早期日志**：设备启用 pstore/ramoops 时，`/sys/fs/pstore/console-ramoops` 可保存上一次启动的内核日志。旧内核可能提供 `/proc/last_kmsg`。若 ramoops 保留了用户态写入 `/dev/pmsg0` 的日志，重启后查 `/sys/fs/pstore/pmsg-ramoops-*`。`/dev/pmsg0` 是写入端点，不是上次启动日志文件。更早的 Bootloader 阶段通常需要串口或厂商工具。
2. **排查动作**：在保留的日志中搜索 `avc: denied`、`critical process`、`Fatal signal` 和 `service exited`。pstore 不可读时，尝试从 recovery 获取日志。
3. **调试逃生口**：在设备构建和调用权限允许时，`setprop init.svc_debug.no_fatal.<service> true` 可临时关闭指定服务的 critical fatal 处理，以便收集日志。这不是量产设备上的通用恢复方案。
4. **边界**：Verified Boot 镜像校验失败发生在用户态日志可用之前，应查 Bootloader、串口或 recovery 证据。它与 init 运行后的服务崩溃循环属于不同阶段。





**Q43: [learning] [tags:系统启动] 设备“突然重启/黑屏”，怎么从日志快速判断死在哪一层——内核、init、Zygote 还是 system_server？**

四层故障的日志指纹不同，先看设备是否发生内核重启，再定位用户态服务退出或 system_server 看门狗动作。不同设备的 fatal reboot target 和日志保留方式可能不同，不能只凭黑屏外观判断。

1. **内核 panic**：pstore/console-ramoops 中出现 `Kernel panic - not syncing: ...` 是内核崩溃证据。若随后发生设备重启，通常看不到同一次启动继续产生的 Android 日志。
2. **init 监督或 fatal 策略**：`Attempted to kill init!` 表示有进程尝试终止 PID 1。`critical process ... exited ...` 表示关键服务崩溃计数进入 init fatal 路径。最终重启目标受服务与产品配置控制。
3. **Zygote 服务退出**：若 logcat 中 init 报告 `Service 'zygote' ... received signal`，观察 init 是否重启 Zygote，以及 `system_server` PID 是否随新 Zygote 改变。Zygote 服务的 `onrestart` 配置可能连带重启其他 native 服务。
4. **system_server Watchdog：`* WATCHDOG KILLING SYSTEM PROCESS` 和 `Blocked in ...` 是 Watchdog 证据。结合 `pre_watchdog`/`watchdog` DropBox 记录及线程栈找阻塞点。它通常表现为 Framework 重启，不等于内核重启。
5. **区分内核重启与 Framework 重启**：对比 `/proc/sys/kernel/random/boot_id`、进程 PID、`sys.boot_completed` 和 pstore。boot_id 改变说明经历内核启动。PID 或 Framework 状态变化但 boot_id 未变时，应优先查用户态恢复链。`BOOT_COMPLETED` 是否再次出现不能单独作为判据。





**Q44: [learning] [tags:系统启动] AAOS 13 的 headless system user 模式下，AMS 为什么不从 systemReady 直接启动 user 0 的桌面？**

headless system user 模式把 user 0 作为系统服务用户，不把它当作座舱 HOME 的显示用户。因此 AMS 在 `systemReady()` 中跳过 system user 的直接 HOME 启动。座舱用户进入前台时，UserController 再让 ATMS 为该用户启动 HOME。

1. `goingCallback` 执行完后，AMS 重新读取当前用户。回调期间 SystemServer 的后续服务可能已启动或切换用户。
2. 当前用户为 system user 且不是 headless 模式时，AMS 可直接调用 `startHomeOnAllDisplays(currentUserId, "systemReady")`。
3. headless 模式下，AMS 的源码注释说明 system user 此时已由前置流程启动并解锁，部分用户启动工作已完成，因此不从该分支重复启动它的 HOME。驾驶员用户由产品用户策略启动或切到前台。
4. UserController 的 `moveUserToForeground()` 切换任务栈，并经 ATMS `startHomeActivity(newUserId, ...)` 将 HOME 请求绑定到新前台用户。

Framework 源码锚点是 `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` 的 `systemReady()` 和 `frameworks/base/services/core/java/com/android/server/am/UserController.java` 的前台切换路径。具体何时创建驾驶员用户、何时切换前台用户由产品的 CarUserService/用户策略决定。不能仅凭 headless 模式断言每台设备都在同一时刻创建 CarLauncher。





**Q45: [learning] [tags:系统启动] AAOS 13 启动 HOME 时，PackageManager 怎样决定是否运行 CarLauncher，进程又怎样进入 Activity？**

AMS/ATMS 发出的是带用户和显示上下文的 HOME 请求，不是对 CarLauncher 类名的硬编码启动。ATMS 解析出目标组件后，才走通用 Activity 启动链。若目标进程不存在，AMS 才请求 Zygote 创建进程，之后通过 ActivityThread 创建 Activity。

1. RootWindowContainer 为目标 user/display 组装 `ACTION_MAIN + CATEGORY_HOME`。次显示区在满足策略时可使用 `CATEGORY_SECONDARY_HOME`。
2. PackageManager 按目标用户解析当前启用且匹配的组件。CarLauncher Manifest 声明 HOME 候选，但只有默认 HOME、包安装/启用状态与产品配置匹配时才会被选中。
3. ActivityStartController/ActivityStarter 准备启动任务和 ActivityRecord，并经 AMS 的进程管理路径确保应用进程存在。
4. 目标进程不存在时，ProcessList 计算 UID/GID、存储策略和 SELinux `seInfo` 等参数，经 ZygoteProcess 请求 Zygote fork。若进程已存在则复用，HOME 请求不必然创建新进程。
5. 子进程进入 ActivityThread 并 attach 到 system_server 后，客户端事务才创建目标 Activity。只有解析结果为 CarLauncher，控制流才会进入它的 `onCreate()`。

定位“Launcher 没起来”时，先用目标用户查询实际 HOME 解析结果，再查 ActivityTaskManager 启动记录和进程状态。Manifest 声明只能证明组件有资格成为候选，不能证明 PackageManager 最终选中了它。源码锚点包括 `RootWindowContainer.java`、`ActivityTaskManagerService.java`、`ActivityStartController.java`、`ProcessList.java`、`ActivityThread.java` 和 CarLauncher 的 `AndroidManifest.xml`，均按 Android 13 checkout `abec84ef9` 核对。





**Q46: [learning] [tags:系统启动] HOME Activity 已启动后，Android 13 还要满足哪些条件才设置 sys.boot_completed？**

HOME Activity 进入启动链不是 boot complete。ATMS 在前台 Activity idle（或超时兜底）后安排收尾。WMS 等屏幕策略与动画退出，AMS 再用动画完成门闩协调 `finishBooting()`，完成后才推进全局 boot phase 并设置 `sys.boot_completed=1`。

1. ActivityTaskSupervisor 在首批 resumed Activity idle（或相应超时兜底）后调用 `postFinishBooting()`，把启动收尾投递到 ATMS handler。特定应用崩溃、ANR 或 StrictMode 对话框路径也会调用 AMS `ensureBootCompleted()` 兜底。两者都表示框架开始收尾，不表示动画或显示已经完成。
2. WMS `performEnableScreen()` 等待开机窗口策略与需绘制的系统装饰窗口，再请求 bootanimation 退出并轮询 init 的服务状态。
3. bootanimation 停止后，WMS 通知 SurfaceFlinger `BOOT_FINISHED`，启用显示和输入，再回调 AMS `bootAnimationComplete()`。
4. AMS 的 `finishBooting()` 与动画回调可能先后到达：先到的一方记录状态，另一方到达后续跑收尾，避免竞态。随后 AMS 发布 `PHASE_BOOT_COMPLETED` 并设置 `sys.boot_completed=1`。
5. `sys.boot_completed` 是全局系统属性，不是每用户广播的完成回执。UserController 后续仍按用户处理存储解锁、升级时的 `PRE_BOOT_COMPLETED` 和各用户的 `BOOT_COMPLETED`。

因此应分别记录 Activity idle、bootanimation 停止、显示/输入启用、`PHASE_BOOT_COMPLETED`、全局属性和用户广播状态。不能用桌面可见或单个属性替代整条启动完成判定。源码锚点为 `ActivityTaskSupervisor.java`、`WindowManagerService.java`、`ActivityManagerService.java` 与 `UserController.java`，对应 `AAOS13_study` Android 13 commit `abec84ef9`。

**Q47: [learning] [tags:系统启动] 从上电到内核运行，Boot ROM → BootLoader → 内核这条链各自做什么？bootloader 锁定状态影响什么？**

这是一条逐级加载、逐级校验的信任链：Boot ROM 加载并校验 bootloader，bootloader 加载并校验内核，内核接管后才进入系统初始化。锁定状态决定每一级允许加载哪些内容。

1. **Boot ROM**：芯片固化代码，上电后从启动介质（eMMC/UFS/SPI）固定偏移加载 bootloader 镜像，校验其签名后执行——ROM 是整条信任链的根。
2. **BootLoader**（如 AOSP 的 aboot/U-Boot）：初始化 DRAM、时钟与启动介质，读取 boot 分区（boot/vendor_boot），校验 AVB 签名，把内核与 ramdisk 装入内存，组装 cmdline 后跳转内核。同时负责充电模式、recovery/fastboot 进入与 A/B slot 决策。
3. **内核**：解压（若压缩）、初始化子系统与内建驱动，挂载 rootfs 后启动 PID 1（即 init）。

bootloader 锁定状态决定 AVB 校验失败的处置：锁定设备校验失败直接拒绝启动。解锁设备进入警告状态但仍可启动（警告屏见后续题）。链路取证入口是 bootloader 阶段的串口日志与内核 dmesg 的最前段。

**Q48: [learning] [tags:系统启动] boot 镜像由哪些部分组成？GKI 之后 vendor_boot 为什么被拆出来？**

经典 boot 镜像 = 头部（magic、页大小、各段偏移）+ 内核 + ramdisk +（可选）第二阶段。Android 12 起头部还承载 bootconfig。GKI（Generic Kernel Image）拆分后，通用内核（boot）与设备相关的内核模块、DTB、vendor ramdisk 分离到 vendor_boot：boot 里的 GKI 内核由 Google 维护，厂商把自研驱动模块放进 vendor_boot 的 vendor_ramdisk，首阶段 init 挂载 vendor 分区后由 modprobe 加载。

1. **为什么拆**：同一 GKI 内核要跑在不同厂商设备上，内核与硬件描述（DTB）、驱动模块必须解耦，否则每次内核更新都要厂商重编。
2. **启动期配合**：first_stage_init 的 `LoadKernelModules()` 加载的就是 vendor_boot/vendor 分区里的模块，fstab 指向的块设备驱动就绪后才能挂载。
3. **边界**：GKI 镜像结构与模块版本校验细节归 GKI 专题册，本册只讲它在启动链中的位置。

**Q49: [learning] [tags:系统启动] androidboot.* 参数是怎么生成并传到用户态的？bootconfig 改变了什么？**

bootloader 把设备信息（硬件、槽位、解锁状态、启动原因等）以 `androidboot.xxx=yyy` 形式追加到内核命令行。内核把 cmdline 暴露在 `/proc/cmdline`，first_stage_init 读入后通过环境变量转发，第二阶段 init 把每个 `androidboot.xxx` 转成 `ro.boot.xxx` 系统属性——这就是 `ro.boot.bootreason`、`ro.boot.slot_suffix` 等属性的来源。

1. **bootconfig（Android 12+）**：参数量增大后 cmdline 空间不足，bootconfig 作为独立段随 boot 镜像传递，由内核暴露在 `/proc/bootconfig`，init 同样读入并转换，格式更结构化。
2. **排查意义**：属性层的 `ro.boot.*` 与 `/proc/cmdline`（或 bootconfig）两侧对得上，才能区分“bootloader 没传”还是“init 没转”。
3. **边界**：cmdline 中 `quiet`、`loglevel`、`earlycon` 等内核自身参数不经 androidboot 转换，直接作用于内核行为。

**Q50: [learning] [tags:系统启动] AVB 的 vbmeta 链与 rollback protection 是怎么防篡改和防降级的？**

AVB 用签名描述符逐级覆盖分区：vbmeta 分区持有根公钥与各分区描述符（hash/hashtree/链式描述符），bootloader 校验 vbmeta 签名后按描述符继续校验 boot、system/vendor 等分区。被链式描述符指向的分区（如 vendor 的 vbmeta_vendor）再校验其下级——形成从 ROM 根公钥到每个字节的信任链。

1. **rollback protection**：每个分区描述符带 rollback index，设备端 fuse/RPMB 保存已见过的最大值。镜像 index 低于存储值即拒绝启动，阻止“刷旧版本利用已修补漏洞”。
2. **降级窗口**：回退到旧 slot 也受索引约束——旧 slot 的索引低于 fuse 值同样无法启动，这是 A/B 回退的隐含前提。
3. **口径**：本册讲链路与回滚在启动中的作用。AVB 算法与失败处置状态机以 source.android.com《Verified Boot》为准（2026-10 检索）。

**Q51: [learning] [tags:系统启动] dm-verity 在启动期何时生效？它与 AVB 是什么分工？**

dm-verity 是内核的块设备层校验 target：为只读分区建立 hash tree，每次块读取都经树校验，不一致则返回 I/O 错误（或按策略处置）。分工上，AVB（bootloader 侧）负责“启动前”的整分区签名校验，dm-verity（内核侧）负责“运行中”的持续校验——AVB 校验 hash tree 根摘要后，把 dm-verity 参数（根摘要、salt、表大小）经 cmdline 传给内核构建 dm 设备。

1. **生效时点**：first_stage_init 按 fstab 的 avb 标记挂载分区时由 fs_mgr 激活对应 dm-verity 设备，system/vendor 挂载即运行在校验视图上。
2. **失败表现**：运行期块损坏以 I/O 错误浮出——应用表现为读文件 `EIO`，而不是崩溃在 AVB。“启动成功但随机 IO 错误”要想到 verity 层。
3. **边界**：`adb disable-verity` 会关闭校验并把分区转为可写，属于开发行为。

**Q52: [learning] [tags:系统启动] 动态分区（super 分区）在启动期是怎么映射出来的？**

动态分区把 system、vendor、product 从独立物理分区改为 super 分区内的逻辑分区：启动期由用户态构造映射——first_stage_init 的 `DoFirstStageMount()` 读取 fstab 中带 logical 标记的条目，通过 dm-linear 把 super 内的各段线性拼接成 mapper 下的 system/vendor 块设备，再按普通分区挂载。

1. **与 OTA 的关系**：逻辑分区大小可在 OTA 时调整（缩 vendor 扩 system），不再受物理分区表限制——这是动态分区的核心动机。
2. **排障入口**：映射失败时启动停在 first stage。dmctl 列表、super metadata 版本与 slot 后缀（system_a/system_b）是取证点。
3. **边界**：super 布局的制作与 lpmake 细节归构建与镜像专题，本册只关心启动期的映射路径。

**Q53: [learning] [tags:系统启动] OTA 后的首次开机要经过哪些验证？失败怎么回退？**

A/B 设备 OTA 写入后台槽，重启切到新槽后有一段“待验证”期：update_verifier 服务在启动早期对新槽的关键分区做 dm-verity 校验，同时系统跑完 `sys.boot_completed`（及产品定义的成功标记）之前，bootloader 侧的重试计数一直在倒数。验证通过后调用 bootctl HAL 的 markBootSuccessful，清除计数并把槽标记为 successful。验证失败或计数耗尽，bootloader 将该槽标记 unbootable 并回退旧槽。

1. **关键时点**：markBootSuccessful 之前“能启动”不等于“启动成功”——此刻断电或崩溃会消耗一次重试，而不是留在新槽。
2. **排障**：OTA 后反复回到旧槽，按“update_verifier 是否运行 → verity 校验是否通过 → boot_completed 是否达成 → bootctl 标记是否写入”四步取证据（AOSP《Implement A/B updates》与 update_verifier.cpp，2026-10 检索）。

**Q54: [learning] [tags:系统启动] 内核 panic 后设备走什么路径重启？上一次崩溃的现场从哪里取证？**

内核 panic 的处置由 cmdline 的 `panic=` 秒数等产品配置决定：典型配置为打印寄存器与调用栈后立即硬重启，重启原因经 bootloader 记录为 kernel panic 类 boot reason，供下次开机读 `ro.boot.bootreason`。现场取证依赖“重启也不丢”的存储：

1. **pstore/ramoops**：把 console 日志与 panic 信息映射到 RAM 保留区，重启后由 pstore 驱动导出到 `/sys/fs/pstore/`（console-ramoops、dmesg-ramoops 等）——这是“上一次开机的内核日志”的标准来源。
2. **配套**：部分平台的 bootloader 也保留上一次日志缓冲（last_kmsg 类接口），两处互相印证。
3. **边界**：pstore 只覆盖内核侧。用户态（system_server/init）的最后日志走 tombstone 与 logcat 持久缓冲，与本机制互补。

**Q55: [learning] [tags:系统启动] A/B 设备的 slot 状态机有哪些状态？重试计数怎么流转？**

每个 slot 由三个属性描述：bootable（可尝试）、successful（成功过）、retry_count（剩余尝试次数），由 bootctl HAL 持久保存。OTA 后目标槽是 bootable+非 successful+retry_count=N。每次尝试启动时 bootloader 递减计数。直到系统调用 markBootSuccessful 才置 successful 并停止倒数。计数耗尽仍未成功，槽被标 unbootable，bootloader 回退另一个 successful 槽。

1. **流转要点**：只有当前活动槽会被尝试。两个槽都非 successful 时的行为由 bootloader 实现决定（典型为停留在可尝试槽并重试）。
2. **手工干预**：bootctl 调试接口与 fastboot 的 set_active 可重置标记——OTA 反复回退时先查这组属性，再怀疑系统。
3. **口径**：状态定义以 AOSP《Implement A/B updates》为准（2026-10 检索）。重试次数默认值随 bootloader 配置，不写死。

**Q56: [learning] [tags:系统启动] recovery 是怎么进入的？BCB（misc 分区）在其中扮演什么角色？**

进入 recovery 有两条路：其一，bootloader 直接挂载 recovery 分区（独立内核+ramdisk）启动。其二，Android 运行中把 BCB（bootloader control message，写在 misc 分区，含 command 字段）写入后重启，bootloader 读 BCB 决定进 recovery 而非正常系统——OTA 安装、恢复出厂、fastbootd 都走这条消息通道。

1. **BCB 的字段**：command（boot-recovery、boot-fastboot 等）+ recovery 正文（如 `--update_package=...` 参数），recovery 的 init 解析后执行对应动作。
2. **fastbootd 与 bootloader fastboot**：同名不同层——bootloader fastboot 是引导级。fastbootd 是跑在 recovery ramdisk 里的用户态实现，支持动态分区操作。
3. **排障**：recovery 循环或指令不生效，先 dump misc 内容，确认 BCB 是否被正确写入与清除。

**Q57: [learning] [tags:系统启动] 插线“充电开机”和正常开机走的是同一条链吗？**

不是。关机充电时 bootloader 读取启动原因（按键/BCB/充电事件）后以 charger 模式启动：cmdline 携带 `androidboot.mode=charger`，init 据此不启动 zygote 与 Android 框架，只运行 charger 服务（独立 UI 显示电量动画）。用户短按电源等事件触发正常启动时，init 再拉起 main 类服务进入完整链路。

1. **差异本质**：charger 模式是一个“init + 少量服务”的极简用户态，没有 Java 世界。
2. **排障意义**：插线不亮充电画面 → bootloader 未进 charger 模式或 charger 服务失败。能充电但不能正常开机 → 问题在正常启动链。两类问题的证据要分开取。
3. **边界**：部分产品在关机充电 UI 中加入车机定制的电量策略，实现仍在 charger 服务内，不涉及框架。

**Q58: [learning] [tags:系统启动] servicemanager 是什么时候启动的？它为什么必须在大多数服务之前？**

servicemanager（Binder 的上下文管理器）由 init 在很早的阶段以 core 类启动，排在绝大多数 Binder 服务之前：每个 Binder 服务注册时要把“名字→句柄”写进 servicemanager，客户端按名字查询也必须问它——没有它，vold、surfaceflinger、zygote 之间的服务发现无法开始。

1. **特权差异**：servicemanager 自身运行在受限 SELinux 域，谁能注册、谁能查询哪个名字由 sepolicy 精确控制，这是服务面安全的第一道闸。
2. **层次**：原生层有 servicemanager。HIDL 时代另有 hwservicemanager 管理硬件服务，两者并存但职责分域。
3. **排障**：服务注册失败先看 servicemanager 是否存活，再看 sepolicy 是否允许该域的 add——“服务没起来”与“服务起来了但注册被拒”是两类问题。

**Q59: [learning] [tags:系统启动] 从 init 跑起来到桌面出现，核心服务的典型启动顺序是什么？**

典型顺序按 AOSP init.rc 的类与触发器组织，具体产品会调整：

1. **core 早期**：servicemanager、logd、ueventd 冷插拔、vold、debuggerd 等原生守护进程。
2. **挂载与安全**：post-fs 与 late-fs 阶段完成 /data 相关挂载与加密状态分支，apexd 完成 APEX 激活。
3. **main 类**：zygote 启动并 fork system_server。system_server 内部再依次拉起 AMS、PMS、WMS 等框架服务（内部顺序归 system_server 专题册）。
4. **late_start**：加密解锁或无加密直入后 `class_start late_start`，启动网络、UI 相关服务与完整依赖群。
5. **收尾**：Home 首帧与 `sys.boot_completed` 置位。

理解顺序的依据是依赖：servicemanager 先于一切 Binder 服务，vold 先于依赖 /data 的服务，zygote 先于一切 Java 服务。

**Q60: [learning] [tags:系统启动] init 的 selinux_setup 阶段做了什么？为什么第二阶段前要单独走这一步？**

`execv("/system/bin/init", "selinux_setup")` 之后，init 以该参数进入 SetupSelinux()：加载 SELinux 策略（system/vendor 策略合成后装载进内核）、以正确标签挂载 `/sys/fs/selinux` 相关接口、对早期挂载的目录做初始 restorecon，随后再以 `second_stage` 参数 exec 进入 SecondStageMain()。

1. **为什么独立成步**：策略装载后，init 此后的所有动作（启动服务、建 socket、写文件）才受强制访问控制约束——独立阶段保证“策略之后的启动全程受控”，不给策略装载前的窗口启动任何服务。
2. **排障**：卡住或失败的典型表现是停在加载策略前后的日志。策略本身的问题（neverallow、语法）归 SELinux 专题册。

**Q61: [learning] [tags:系统启动] init 第二阶段（SecondStageMain）按什么顺序做哪些事？之后的运行时骨架是什么？**

SecondStageMain 是“真正的 init 常驻体”的起点，初始化顺序大致为：初始化属性域与日志、装载 SELinux 后的策略上下文、建立 epoll 与信号处理（SIGCHLD 由 init 统一 wait 回收）、复位所有 dead 服务状态、依次执行 early-init → init → late-init 触发器的动作队列（rc 解析产物），其中完成 /data 相关挂载、启动 core 类服务，最后进入无限循环：epoll 等待属性设置、子进程退出、文件事件，驱动 action 队列与服务重启。

1. **运行时骨架**：此后 init 不再“顺序执行”，而是事件驱动——属性写入、服务退出、定时器各自入队，由主循环消费。
2. **排查意义**：开机卡住时看 init 日志里最后一条 "processing action (...)" / "processing service (...)"，即可定位卡在哪个动作或服务的启动上。
3. **边界**：init 常驻期的服务监督细节（崩溃、重启次数）见 init 重启策略题与 boot loop 题。

**Q62: [learning] [tags:系统启动] init.rc 的解析模型是什么？多个 .rc 文件的加载顺序由什么决定？**

Android Init Language 的核心结构是 action（`on <trigger>` 下的命令序列）与 service（`service <name> <path> <args>` 的进程声明），外加 import。init 按固定顺序加载 rc：先读 `/init.rc` 主文件，再按目录层级加载 `/system/etc/init`、`/vendor/etc/init`、`/odm/etc/init` 等——同一目录内按文件名排序，厂商与 OEM 通过把自己的 rc 放进对应目录插入启动逻辑，而不是改主文件。

1. **触发与排队**：action 在其 trigger 满足时入队执行，命令按序执行。`on early-init`、`on init`、`on fs`、`on post-fs-data`、`on boot` 是内置的关键触发点。
2. **加载顺序的意义**：同名服务后加载覆盖先加载（用于 OEM 定制），命令的执行顺序则由触发器与 action 定义顺序决定。
3. **口径**：语法细节以 AOSP `system/core/init/README.md` 为准（2026-10 检索）。

**Q63: [learning] [tags:系统启动] service 声明里最常用的选项有哪些？init 的重启策略是怎样的？**

常用选项：`critical`（关键服务）、`oneshot`（一次性，退出不重启）、`disabled`（不随 class 启动，需显式 start）、`user`/`group`（运行身份）、`seclabel`（SELinux 域）、`socket`（由 init 代建 socket 并传 fd）、`onrestart`（本服务重启时执行的命令）、`ioprio`/`oom_score_adj` 等资源属性。

1. **重启策略**：非 oneshot 服务退出后 init 默认自动重启。`onrestart` 允许声明“重启前要做的事”（如 stop 依赖服务）。
2. **关键约束**：critical 服务按“窗口期内的崩溃次数”判定（默认 4 分钟 4 次），超限触发整机重启——这是 boot loop 的机制源头。
3. **实践**：服务“起来又立刻退、反复重启”时，先读 init 日志里的退出码与 onrestart 执行记录，再查它依赖的前置服务是否就绪。

**Q64: [learning] [tags:系统启动] init 对 critical 服务的“4 分钟 4 次”判定是怎么实现的？进入重启前后发生什么？**

init 为每个 critical 服务维护崩溃计数与时间窗：服务意外退出时计数加一，init 会等待一个退避间隔（指数增长）再重启。若计数在窗口内达到阈值（默认 4 分钟内 4 次），init 判定系统进入不可恢复状态，执行有序重启流程——同步属性、通知关键进程，最后触发整机重启（reboot），并在下次开机继续监督。

1. **与 boot loop 的关系**：每次开机同一 critical 服务都崩满 4 次，就会形成“开机几分钟即重启”的循环——这就是 boot loop 判据的 init 侧机制（现象层的分层判断见本册 boot loop 题）。
2. **排障**：init 日志中 "critical process ... exiting" 与 "rebooting because critical process" 是直接证据。崩溃服务自身的 tombstone 说明根因。
3. **边界**：阈值是 init 编译期/属性口径，不随服务声明变化。非 critical 服务崩溃不会触发整机重启，只按策略重启自身。

**Q65: [learning] [tags:系统启动] 属性服务（property service）是怎么实现的？谁能写哪些属性由什么决定？**

属性是 init 维护的一块共享内存区域（属性域）加一个 Unix 域 socket 服务：读取方直接 mmap 共享内存零拷贝读取。写入方（任何进程）把写请求发到 init 的属性 socket，由 init 校验后统一写内存并持久化。权限模型由 SELinux 决定——每个属性前缀对应可写的域（`ctl.*` 控制命令、`ro.` 不可改、`persist.*` 落盘、vendor 前缀归 vendor 域），策略不允许时写入被静默拒绝。

1. **实现意义**：属性是“启动期的进程间消息总线”，on property 触发器全部依赖 init 作为唯一写入点。
2. **排障**：“setProperty 不生效”先区分三种失败：SELinux 拒绝（avc 日志）、前缀权限不符、属性根本不存在（`ro.` 误当可写）。
3. **边界**：persist 属性的落盘与开机恢复是独立机制，见本册 persist 题。

**Q66: [learning] [tags:系统启动] on property 触发器如何编排启动时序？late_start 为什么是经典案例？**

init 在属性变化时检查所有以 `on property:<name>=<value>` 声明的 action，满足则把其中的命令入队执行——启动时序因此可以完全用属性当“就绪信号”来编排。经典案例是 `late_start`：加密设备开机时 /data 尚不可用，vold 完成解密后设置属性，init 命中对应触发器执行 `class_start late_start`，一次性拉起所有依赖 /data 的服务（网络、UI 服务群等）。

1. **编排价值**：服务声明成 class，用属性触发批量启动，比在 rc 里硬编码先后关系更可组合。
2. **排障**：某类服务“开机不启动”，先查它所属 class 是否已被 class_start（加密分支漏触发是最常见根因），再看属性链是否走到。
3. **边界**：属性触发是启动编排手段，不是通用 IPC——高频通知应走 Binder 或 socket，而非刷属性。

**Q67: [learning] [tags:系统启动] vold 在启动链里处于什么位置？加密设备的解密流程怎么走？**

vold（volume daemon）由 init 在 core 阶段启动，负责 /data 的挂载、FBE 密钥管理与存储事件。加密设备的开机分支：非凭据部分（DE，device-encrypted 存储）在 init 阶段即可挂载，框架以“直接启动”（directBootAware）模式运行有限服务。用户输锁屏凭据后 vold 用凭据派生密钥解开 CE（credential-encrypted）存储，解密完成后 vold 设置状态属性，init 命中 late_start 触发器启动完整框架，随后发出 LOCKED_BOOT_COMPLETED 与 BOOT_COMPLETED 两级广播。

1. **顺序要点**：无加密设备跳过解密直接 late_start。FBE 设备的“直接启动期”决定了哪些服务必须声明 directBootAware。
2. **排障**：卡在解密阶段看 vold 日志与凭据校验路径。DE/CE 混淆导致的数据“不可见”是常见误判。
3. **边界**：FBE 两级广播对应用的影响见本册 FBE 题。密钥体系细节归存储与安全专题。

**Q68: [learning] [tags:系统启动] apexd 在启动序列的什么位置？APEX 挂载失败或需要回滚时发生什么？**

apexd 由 init 早期启动（main 类、先于 zygote）：它在 /data 的 APEX 会话目录与预置 APEX 之间决定本此开机激活哪一套，把选定的 APEX 以 bind/dm-verity 方式挂载到 `/apex/<name>`，激活完成后置状态属性，之后的 zygote、system_server 与应用看到的运行库（如 Conscrypt、ART 相关模块）就是 APEX 版本——BOOTCLASSPATH 与 linker 配置也依赖激活结果（linkerconfig 据此生成 ld.config）。

1. **回滚**：APEX 升级采用会话化（staged session）：新会话在下次开机激活，若激活或验证失败，apexd 回滚到上一激活集，配合模块可控性测试。
2. **排障**：运行库版本“没生效/突然回退”，先看 `/apex` 挂载清单与 apexd 日志里的会话决策，再查是否处于回滚。
3. **边界**：APEX 的打包与交付归构建与 Mainline 专题，本册只讲它在启动链的位置与决策。

**Q69: [learning] [tags:系统启动] vendor 分区的 init 脚本与属性是怎么注入启动的？vendor_init 是什么？**

init 的 rc 加载目录包含 `/vendor/etc/init`（及 odm 等层级），厂商的服务声明、触发器随分区加载，与 system 的 rc 用同一套模型协作。vendor 域属性（`vendor.`、`ro.vendor.` 等）同样由 init 管理，写入权限按 SELinux 限定在厂商域。vendor_init 这个名字还特指早年的一个机制：vendor 分区在 /data 解密前就能读取，因此厂商属性经由专门的 vendor_default 处理路径在早期可用——现代版本已由 init 统一处理，理解上记住“vendor 属性与 system 属性分域、但同一条 init 管道”即可。

1. **时序价值**：厂商服务（HAL）大多在 zygote 之前由 vendor rc 声明启动，供 CarService 等框架服务连接。
2. **排障**：厂商服务“没起来/起来了但系统找不到”，按 rc 是否被加载（init 日志有解析记录）、SELinux 域是否正确、vintf 是否匹配三条查。
3. **边界**：HAL 的接口与注册细节归平台服务与 SELinux 专题。

**Q70: [learning] [tags:系统启动] init 的关键触发阶段（early-init → boot）各对应什么动作？**

init 的内置触发器构成启动的“主干时间轴”，rc 里所有 action 都挂在这些阶段上：

1. **early-init**：最早的准备，如设置 cgroup、初始化第一部分目录。
2. **init**：基础环境与 core 早期动作。
3. **fs**：按 fstab 挂载分区相关动作（配合 first stage 的早期挂载语义）。
4. **post-fs**：分区就绪后的目录与链接整理。
5. **late-fs**：挂载 /data 的前置阶段（加密设备在此等待密钥准备）。
6. **post-fs-data**：/data 可用后，创建数据目录、装载 persist 属性、启动依赖数据的服务。
7. **zygote-start**：`class_start main` 拉起 zygote（含加密分支的延迟语义）。
8. **boot**：启动收尾的非关键服务与剩余 class（late_start 语义在该阶段补齐）。

把卡点定位到“某个阶段”是开机排障的第一步：读 init 日志的 "processing action (early-init)" 等行即可画出时间轴。

**Q71: [learning] [tags:系统启动] persist.* 属性是怎么持久化的？开机后什么时候恢复可用？**

带 `persist.` 前缀的属性由 init 在 /data 的属性服务目录（`/data/property/persistent_properties`）中持久保存：写入时 init 校验 SELinux 权限后落盘。因为依赖 /data，persist 属性在 /data 挂载完成（post-fs-data 语义）之前不可用——早期服务读 persist 属性会得到空值，这是“开机早期读配置读不到”的经典原因。

1. **恢复时机**：/data 就绪后 init 加载持久化文件重建属性表，此后 on property 触发器才能对 persist 属性生效。
2. **设计推论**：必须在直启动期使用的配置不能用 persist 属性承载，应改用 DE 存储文件或 ro.boot cmdline 参数。
3. **边界**：属性内存布局与 hash 检索是实现细节。跨进程“改了但没生效”优先查 SELinux 与时机两类原因。

**Q72: [learning] [tags:系统启动] 启动期 SELinux 域经历了哪几次转换？每个阶段 init 跑在什么域里？**

init 的一组进程映像在不同阶段运行在不同 SELinux 域：first stage 的 init 运行在 `init` 域。exec 进入 selinux_setup 后按策略切换到 `selinux_setup` 域——该域只被授权做策略装载与最小文件操作。策略装载完成、exec 第二阶段时再转换到 `u:r:init:s0` 的常规 init 域，此后由它启动的各服务按 rc 里的 seclabel 进入各自域。

1. **设计意图**：把“装载策略”这一最高特权动作限制在专用短命域，装载完成后即放弃，降低 init 常驻域被滥用的影响面。
2. **验证方式**：启动早期 dump 内核日志或用 `ls -Z /proc/1` 观察域标签变化。
3. **边界**：各服务的域规则书写与 avc 处置归 SELinux 专题册，本册只关心“域随启动阶段流转”这一骨架。

**Q73: [learning] [tags:系统启动] /data 挂载失败时开机会表现成什么样？按哪三类原因排查？**

/data 挂载失败会使启动停在“依赖数据的阶段”：加密设备表现为等待凭据/解密循环，非加密设备表现为 post-fs-data 之后的服务成批失败、框架反复重启直至 boot loop。原因按层分三类：

1. **加密与密钥**：FBE 密钥不可用（凭据路径异常、密钥损坏），vold 日志有解密失败记录。表现为反复要求输入密码或直接挂载失败。
2. **文件系统**：超级块/元数据损坏、checkpoint 处于未验证状态，fs_mgr 挂载返回错误。伴随大量 EXT4-fs/F2FS 内核报错。
3. **块设备与映射**：动态分区映射失败、dm 设备缺失、存储硬件故障，first stage 或 vold 阶段即失败。

取证顺序：内核日志挂载报错 → vold 日志 → init 卡住的 action。三类原因的修复路径完全不同（重置凭据/修文件系统/查映射），不能混着试。

**Q74: [learning] [tags:系统启动] 安全模式（safemode）是怎么进入和退出的？它对系统做了什么？**

进入方式随产品而异：手机典型为长按电源 → 长按“关机”菜单项确认，或特定按键组合在开机时按住。车机多由工厂菜单或诊断命令触发。进入后包管理器把第三方应用整体禁用（仅系统与预装应用可运行），桌面会显示“安全模式”角标。

1. **用途**：判断“问题来自三方应用”——安全模式下症状消失，嫌疑即在三方。反之指向系统或硬件。
2. **退出**：重启即退出安全模式，回到正常加载。
3. **边界**：安全模式不改变数据、不解密差异。它是诊断态而非修复态，修复动作仍在正常模式验证。

**Q75: [learning] [tags:系统启动] 开机画面其实有三层——bootloader Logo、内核输出、SurfaceFlinger 上的开机动画，时序怎么衔接？**

三层画面由不同主体在不同时刻接管：bootloader 在校验与装载阶段直接刷屏显示静态 Logo。内核接管后若未配置静默，控制台字符可能覆盖画面（所以量产 cmdline 带 quiet）。SurfaceFlinger 起来之后，bootanim 作为 SF 的客户端把自己的图层提交合成，显示动画。框架就绪、Home 首帧可显示时 bootanim 退出，画面自然过渡到桌面。

1. **黑屏分段定位**：BL Logo 出现说明 bootloader 活着。之后黑屏说明卡在内核或 first/second init。动画出现说明 SF 已运行。动画不消失才是框架问题。
2. **排查入口**：每一段的“接管者”不同，日志与截图要按段取，不能凭一张黑屏照片下结论。
3. **边界**：bootanim 的服务退出条件与经典“动画不消失”排查见本册开机动画题。

**Q76: [learning] [tags:系统启动] 怎么系统地度量“开机耗时”？有哪些现成的度量设施？**

度量要按阶段拆，各阶段有对应设施：bootloader 段看其串口日志的内部计时。内核段用 dmesg 时间戳（可开 initcall_debug 看内建驱动初始化耗时）。init 段读 init 日志的 action 处理记录与它导出的首阶段计时环境变量。框架段用 `logcat -b events` 里的 boot 进度事件（boot_progress_start、ams_start 等）与 BootReceiver 记录。整机横断面可用 bootchart 采集进程 CPU/IO 时间线。

1. **口径统一**：所有时间要先换算到同一时基——内核单调时钟与 wall time 的换算点在初始化早期，跨段对比必须对齐。
2. **产出**：一条“上电 → 内核 → init 各阶段 → zygote → system_server → boot_completed → Home 首帧”的时间线，才是可讨论的优化基线。
3. **边界**：优化手段归启动优化专题册，本册只解决“怎么量得准”。
**Q77: [learning] [tags:系统启动] 从上电到桌面，按时间序应当出现哪些关键日志行？**

一条可背的“检查清单”，用于快速判断开机走到了哪一步（各产品措辞略有差异）：

1. 内核日志最前段：bootloader 传参后的内核 banner 与驱动初始化——说明内核已运行。
2. `Run /init as init process`：内核把控制权交给 init。
3. init 的 `processing action (early-init/init/fs/...)` 序列与 `starting service 'xxx'`——反映 init 阶段进度。
4. `Loading SELinux policy` / avc 相关行——selinux_setup 与策略生效。
5. zygote 的 `Preload classes/resources` 与 `System server` 启动行——Java 世界开始。
6. `Enabled bootstep`/sys.boot_completed 置位与 Home 进程启动——接近完成。

反向排查：清单断在哪一行，问题就在那一段。这比“看 logs 大海捞针”快得多。
**Q78: [learning] [tags:系统启动] 重启原因（boot reason）是怎么一级级传到应用的？warm、cold、hard 重启差在哪？**

bootloader 把本次复位的原因（按键、kernel panic、watchdog、OTA、充电插拔等）编码后经 cmdline/bootconfig 传入（`androidboot.bootreason`），init 转成 `ro.boot.bootreason` 属性，框架再映射为 Readable 版本。语义区分：cold 是完全断电再上电。hard 是不保留更多状态的强制复位。warm 是复位但保留部分硬件状态——对启动的影响主要是外设初始化路径与部分 SoC 状态。

1. **用途**：复盘“设备为什么自己重启了”的第一证据就是 boot reason。它决定往内核（panic/watchdog）还是往用户态（framework 请求重启）查。
2. **陷阱**：reason 字符串是 OEM 自由扩充区，跨设备比对要先核对平台定义。kernel panic 类要看 pstore 印证，不能只信字符串。
**Q79: [learning] [tags:车机] [系统启动] 车机的 ACC ON 唤醒（STR）和冷启动有什么区别？它算一次“开机”吗？**

车机常见两种路径：冷启动（cold boot）走完整启动链，从 bootloader 到 Home 全程重建。挂起待机（STR，suspend-to-RAM）下系统整体冻结在内存里，ACC ON 只是唤醒——CPU 恢复现场、屏幕点亮，进程与状态原样保留，不走 init/zygote。

1. **辨析**：判断“这次是唤醒还是开机”看日志——唤醒路径没有 init 的 action 序列，只有内核 resume 打印。用户感知的“秒开”多半是唤醒。
2. **疑难定位**：唤醒后功能异常（服务假死、信号 stale）归电源与休眠专题（Freezer、 suspend 相关），不按开机链排查。但用户报障常把两者混为一谈，先分类再查。
3. **边界**：挂起策略、冻结与功耗权衡归 CPU/功耗章，本册只建立“唤醒不是开机”的边界。
**Q80: [learning] [tags:系统启动] 非正常断电后再开机，系统会多做哪些事？checkpoint 在其中起什么作用？**

异常断电可能让 /data 处于“写了一半”的崩溃一致状态。开机挂载前文件系统层先自检（ext4 走 e2fsck 类检查修复。F2FS 依赖日志式结构在线恢复），耗时与脏数据量成正比——这是“断电后开机变慢”的常见原因。OTA 引入的 checkpoint（用户数据检查点）则更进一步：在“待验证”启动期把 /data 置于检查点会话，若本次开机验证失败可整体回滚到检查点，防止新版本把用户数据写坏。

1. **现象映射**：断电后首次开机慢（fsck）与 OTA 后回滚（checkpoint）是两条不同机制，日志分别落在文件系统驱动与 checkpoint 服务。
2. **排障**：怀疑数据损坏按“挂载前检查日志 → 文件系统类型 → 是否处于 checkpoint 未提交期”三步定位，不要直接恢复出厂。
**Q81: [learning] [tags:系统启动] 出厂首次开机和日常开机有什么差异？为什么第一次开机格外慢？**

首次开机（或恢复出厂后）多出几类一次性工作：

1. /data 首次格式化与目录初始化。
2. provisioning 流程（SetupWizard 引导、系统标记置位）。
3. 全量 dexopt/编译任务（把热点应用编译为机器码）在大规模后台执行。
4. 各应用首次运行建立自己的数据与缓存。

这些工作使首次开机的“开机后体验”明显慢于日常开机，且部分任务持续到开机完成后一段时间。

1. **日常开机不再做的**：格式化与初始化、provisioning 标记。dexopt 转为按需与空闲期维护。
2. **排障口径**：对比“首次 vs 日常”的耗时差异要分开采集——把一次性工作的开销算进日常开机基线会得出错误的优化结论。
3. **边界**：dexopt/odrefresh 的触发与产物细节归 ART 专题册。
**Q82: [tags:系统启动] SetupWizard（开机引导）和 PROVISION 标记在启动链里怎么生效？'

首次开机时系统启动 SetupWizard 引导用户完成语言、网络、账号等设置。完成与否由系统标记记录（user setup complete / device provisioned）。该标记在启动链上有实际约束：未完成 provisioning 前，部分系统行为受到限制（如 Home 与关键交互的放行策略、某些广播与服务的启动策略），以避免用户在未初始化完成时进入不完整环境。

1. **位置**：SetupWizard 通常作为首个大规模交互界面出现在 Home 就绪前后，完成时写入标记。
2. **排障**：设备“卡在引导无法跳过/重复进入引导”，先查 provision 标记是否写入成功，再看 SetupWizard 自身是否崩溃。
3. **边界**：车机的引导体验（如首次启动的免责声明页）多为厂商定制流程，机制同源。
**Q83: [tags:系统启动] 开机后多久能 adb？adbd 的启动与授权时机在哪里？'

adbd 由 init 按 USB 状态触发启动（usb rc 与 `sys.usb.config` 状态机），启动早于框架。能否连上还要过授权关：user 构建上 `ro.adb.secure=1`，新主机第一次连接需要用户在屏幕上确认 RSA 指纹，授权信息保存在 /data 的 adb 目录中。

1. **时机结论**：init 阶段 adbd 即可运行（USB 驱动与配置就绪后），但 FBE 设备在数据解密前功能受限，涉及 /data 的授权与部分命令要等解锁。
2. **排障**：开机后 adb 连不上按“USB 配置状态机 → adbd 是否运行 → 授权弹窗/已授权列表 → /data 是否可用”顺序查。`adb devices` 显示 unauthorized 与 offline 是不同问题。
**Q84: [tags:系统启动] bootloader 解锁设备开机时会看到“警告屏”，这个状态机有哪些级别？'

AVF 校验与锁定状态的组合决定开机提示级别：锁定且校验通过 → 正常无提示。解锁（orange 状态）→ 每次开机显示解锁警告数秒后才继续。校验失败但设备解锁 → 允许继续但仍警告。锁定且校验失败 → 拒绝启动（red 状态，部分平台还有损坏提示的 yellow/epsilon 变体，按平台实现）。

1. **开发影响**：解锁设备是开发与刷机的常态，警告屏等待是固定开销。验证类测试必须回到锁定状态做，否则校验路径根本没被执行。
2. **排障**：“设备突然多了一个警告屏”通常意味着 bootloader 被解锁或校验链损坏——先确认锁定状态再查 flash 历史。
**Q85: [learning] [tags:系统启动] 内核的 initcall 机制是什么？它对开机耗时分析有什么用？**

内核把内建代码的初始化函数按级别（early、core、postcore、arch、subsys、fs、device、late）组织为 initcall，启动时由内核按序执行——各驱动的探测（probe）大多发生在 device 级附近。打开 `initcall_debug=1` 后，每个 initcall 的名字与耗时都会打印，可直接量化“内核初始化阶段哪个驱动最慢”。

1. **分析价值**：内核段开机慢的问题，用它能把“内核总共 2 秒”拆到具体驱动，而不是停在黑盒。
2. **边界**：initcall 只覆盖内建代码。模块的加载在 init/用户态阶段由 modprobe 完成，耗时记在 init 段——两段要分开归因。
**Q86: [learning] [tags:系统启动] 开机最早期日志抓不到怎么办？earlycon 与 loglevel 怎么用？**

内核最早的日志（解压后初始化驱动之前）只有控制台驱动就绪后才能输出，量产 cmdline 常配 `quiet` 与低 `loglevel` 把输出压掉。抓早期日志的两个手段：其一，`earlycon=<驱动>,<地址>` 让内核在极早期就通过串口输出（需要 bootloader 传参与地址映射配合）。其二，临时调高 `loglevel=` 或去掉 quiet 复现问题。

1. **取舍**：earlycon 是诊断设施的“第一批日志”，车机串口日志看不全时先核对这两个参数。
2. **边界**：earlycon 只覆盖串口通道，不进入 pstore。两者配合才能覆盖“最早”与“崩溃时”两类场景。
**Q87: [learning] [tags:系统启动] 各启动阶段的耗时“应该”是多少？这类预算怎么定才合理？**

业界没有统一标准，合理的预算来自产品自身的分解与测量：把“上电到 Home 首帧”切成 bootloader、内核、init（至 zygote 前）、zygote+system_server、Home 首帧五段，以当前实测为基线，再按硬件代际与产品目标设相对预算（例如某段占比异常高于同类设备即为嫌疑）。社区流传的“bootloader 应小于 N 秒”类数字是经验口径而非平台承诺。

1. **用法**：预算用于发现异常段，不用于对外承诺。每代硬件、每次内核大版本升级都应重测基线。
2. **配套**：预算必须与度量设施绑定（本册度量题的各段设施），否则预算只是口号。
**Q88: [learning] [tags:系统启动] SurfaceFlinger 在启动链的什么位置就绪？它就绪前后画面由谁负责？**

SurfaceFlinger 由 init 在框架之前启动（依赖图形驱动与 GPU 用户态就绪）：它就绪后才有“合成”能力，开机动画作为它的客户端把图层提交上来显示。在此之前画面是 bootloader/内核刷的静态内容。框架的窗口（Home 首帧）同样经 SF 合成显示——所以“开机动画消失、桌面出现”的本质是 bootanim 图层退出、Home 窗口图层就位，SF 一直在场。

1. **排障**：动画出来了但桌面永远不出现，SF 已就绪，问题在框架与 Home。连动画都没有，则 SF 或其依赖（GPU、drm）没起来。
2. **边界**：SF 的合成机制与帧调度归图形管线章，本册只关心它作为“画面接管者”的时点。
**Q89: [learning] [tags:系统启动] zygote 反复崩溃的日志有什么特征？系统怎么恢复？**

zygote 属于关键服务：它崩溃后 init 按重启策略拉起，崩溃次数在窗口内超限则触发整机重启——日志特征是重复出现的 zygote 退出与重启记录，随后紧跟 reboot 动作。system_server 由 zygote fork 而来，其崩溃也可能连带 zygote 自杀重启（自恢复设计），日志上表现为成对的退出记录。

1. **与 boot loop 的衔接**：每次开机都在同一处崩溃即形成循环。循环根因通常在 preload 阶段加载的类/资源或 system_server 早期初始化，用 tombstone 与 dropped 日志定位。
2. **取证**：init 日志的重启记录 + zygote 的 stderr + tombstone 三者对时间线，即可区分“zygote 自身问题”与“被 system_server 连累”。
**Q90: [tags:车机][系统启动] 车机应用开机早期调用 Car API 拿不到服务而异常，根因和正确姿势是什么？'

CarService 由 system_server 在启动后期绑定并初始化，完成前应用调用 `Car.createCar()` 后再 `getCarManager()` 会拿到 null 或进入未就绪分支——车控/仪表类应用在 BOOT_COMPLETED 前后抢跑时高发。正确姿势是显式等待就绪：用 `Car.createCar(context, handler, waitTimeout)` 的就绪回调或监听 CarService 就绪状态，就绪后再取 manager 发起车辆操作。

1. **根因链**：应用收到 BOOT_COMPLETED（甚至更早的自启动）→ 框架服务尚未完成 CarService 装配 → 调用失败。这不是 CarService 的 bug，而是启动时序的固有窗口。
2. **实战**：早期只允许“读缓存/显示占位”，一切车辆操作挂起至就绪回调。超时未就绪要有可观测日志而不是静默失败。
**Q91: [learning] [tags:车机] [系统启动] VHAL 什么时候就绪？“开机前几秒读车辆信号失败”该怎么解释与处理？**

信号链是 应用 → CarService（property 服务）→ VHAL HAL → 车辆总线。开机早期 CarService 初始化时会连接 VHAL 并批量读取/订阅属性，这一过程在 system_server 启动后期才完成。在此之前任何车辆属性读取都会失败或返回不可用。因此“开机前几秒读不到信号”是时序现象，不是故障。

1. **应用侧处理**：与 Car API 就绪回调合并处理——就绪前显示默认态或占位，注册监听后以第一次回调刷新真实值。
2. **区分真故障**：长时间（远超启动窗口）读不到、且 CarService 日志显示 VHAL 连接失败或属性超时，才是 VHAL/总线侧问题。两者用时间窗区分。
3. **边界**：VHAL 的属性语义与订阅契约归 VHAL 专题册。
**Q92: [tags:车机][系统启动] 多用户车机开机时，各用户的 BOOT_COMPLETED 按什么顺序发出？'

用户启动由 system_server 的用户管理服务按策略调度：系统用户（user 0 或 headless 模式下的 system user）先完成启动并进入 BOOT 阶段，随后按配置与乘员/显示绑定关系逐个启动其他用户。每个用户走完自身的用户级启动阶段后才收到属于该用户的 BOOT_COMPLETED。因此多用户设备上不同用户空间里的应用收到开机广播的时刻可以相差很远。

1. **应用影响**：把“开机即同步”的假设建立在 user 0 时刻的广播上，在其他用户空间会失效。应依赖各自用户空间的广播与就绪信号。
2. **排障**：某乘客屏应用“没收到开机广播”，先确认其所在用户是否已启动、广播是否已发到该用户，而不是断言广播丢失。
**Q93: [learning] [tags:系统启动] VINTF 兼容性校验在启动期起什么作用？校验失败会怎样？**

VINTF（Vendor Interface）把“framework 期望的 HAL/接口版本”与“设备实际提供的 manifest”做兼容性匹配：启动期系统按 manifest 与 compatibility matrix 校验设备提供的接口集合，不匹配时相关 HAL 无法被框架消费、依赖它的服务启动失败，OTA 升级前也会先行校验以阻止不兼容组合上线。

1. **排障**：厂商服务“编译进来了却没生效”，查 vintf 校验日志与 manifest 是否声明了对应接口/版本。OTA 失败场景核对 matrix 增补（FCM 版本）。
2. **边界**：manifest 的书写与 FCM 生命周期归构建与平台专题，本册只关心它作为“启动期准入检查”的角色。
**Q94: [tags:系统启动] 内核镜像的压缩格式对启动时间有影响吗？Image 与 Image.gz 怎么选？'

有影响但量级可控：压缩镜像（gzip/lz4 等）体积小、从存储加载更快，但要付出解压时间。未压缩 Image 加载慢、免解压。GKI 时代 boot 分区空间与加载速度的平衡由产品决定，选择依据是实测的“加载时间 + 解压时间”总和，而不是“压缩一定快”或“不压缩一定快”的单边结论。

1. **实测方法**：分别量 bootloader 加载镜像的时间（其串口日志）与内核解压/启动的 dmesg 时间差，合并对比。
2. **边界**：内核镜像的构建配置归内核专题。启动分析中只需要知道“解压开销发生在内核 banner 之前”。
**Q95: [tags:系统启动] “开机后第一次打开应用特别慢”，完整的归因链怎么拆？'

首次启动慢是多层一次性成本叠加，按发生位置拆：

1. **编译层**：应用从未运行过，代码要么走解释/JIT，要么等待/触发 dexopt 产物生成——首启与二次启动的执行方式不同。
2. **数据层**：首启建立数据库、缓存与配置，磁盘写入集中爆发。
3. **系统层**：开机后系统整体处于高负载（大量服务与广播并发），CPU/IO 竞争放大首启耗时。

归因方法是分别采集“首次”与“二次”启动的启动耗时与系统状态，差异即一次性成本的位置。优化则分属启动优化（系统竞争）与应用（数据初始化）两个专题，本册提供的是拆解框架。
**Q96: [tags:系统启动] init 代建的 socket 是怎么用的？为什么很多守护进程的通信入口由 init 创建？'

service 声明里的 socket 选项让 init 在服务启动前创建抽象或文件系统的 Unix 域 socket，并把 fd 作为环境变量（`ANDROID_SOCKET_xxx`）注入服务进程：这让“创建、监听、权限（SELinux 标签与文件上下文）”由 init 统一在正确的时机完成，服务只管 accept——避免了服务自己建 socket 的时序竞争与标签错误。

1. **典型使用**：zygote 的 zygote socket、vold 与框架之间的 cryptd 通道、logd 的控制通道都走这套机制。
2. **实践推论**：服务间“谁先起”导致的连接失败，若走的是 init socket（连接会排队/阻塞语义按实现）与自建 socket（直接失败）表现不同。分析时先确认 socket 的创建者。

