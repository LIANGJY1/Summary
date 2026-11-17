# Android init 配置与服务

> init.rc、属性、服务声明、事件触发、服务恢复与 vendor init。 各题的平台版本与证据范围以题内说明为准。


**Q1: [learning] [tags:系统启动] init 的属性服务是怎么工作的？为什么系统里到处都在用属性？**

属性服务是 init 维护的系统级键值服务。启动时各分区的 prop 文件提供初始值，进程读取共享属性区，写入则经属性 socket 提交给 init，由 init 按 SELinux 策略校验并更新属性，再通知监听方。

1. **属性用途**：`ro.*` 表示启动后只读属性。`persist.*` 属性会持久化到 `/data` 并跨重启保留。`init.svc.<name>` 是服务状态投影。`ctl.*` 是发给 init 的控制命令，不是状态值。
2. **启动编排**：init 可把服务状态写入 `init.svc.*` 供进程读取，也可在属性变化时触发 rc 动作，例如 `on property:xxx=yyy`。组件因此能通过“设置属性—匹配动作”协调启动顺序，而不必彼此直接调用。
3. **完成信号**：Android Framework 在全局启动收尾时设置 `sys.boot_completed=1`。监听方可据此判断全局启动阶段到达，但它不代表每个用户的开机广播均已送达。
4. **权限与容量**：SELinux 控制哪些执行域能写哪些属性前缀。属性有长度和数量上限，不适合承载大块数据。


**Q2: [learning] [tags:系统启动] .rc 文件怎么理解？**

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


**Q3: [learning] [tags:系统启动] init 是怎么把一个服务进程拉起来的？Service::Start 里有哪些容易忽略的细节？**

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


**Q4: [learning] [tags:系统启动] init.rc 的 service 块还有哪些关键选项？class_start/class_stop/class_reset 有什么区别？**

service 选项决定单个进程的退出、重启和分组启动行为。`class_start`、`class_stop`、`class_reset` 则管理整组服务。排查时要分别判断服务自身策略和 class 操作的效果。

1. **critical**：服务在配置的崩溃窗口内反复退出时可触发 fatal 重启。Android 13 对应实现的默认窗口为 4 分钟，累计达到阈值后触发，具体阈值和开机完成前后的行为应以目标分支为准。支持 `window`、`target` 的新语法也需按目标 init 版本核实。
2. **oneshot**：进程退出后不自动重启。省略时按常驻服务的默认重启策略处理。
3. **disabled**：服务不随所属 class 的批量启动命令启动，仍可由显式 `start <name>` 拉起。bootanim 采用 `disabled` 与 `oneshot`，由 SurfaceFlinger 按需启动，窗口管理器在显示就绪后请求退出。
4. **class 命令**：`class_start` 启动该类中可启动的服务。`class_stop` 停止并禁用该类服务。`class_reset` 停止该类服务但保留后续重新启动资格。
5. **版本演进**：`writepid` 已由 init 文档标记为过时，应按目标版本迁移到 `task_profiles`。Android 14 起相关 task profile 可作用于整个进程。`updatable` 允许 APEX 中的同名服务定义覆盖基础定义，并会延迟该服务直到 APEX 激活。
6. **排查入口**：`getprop | grep init.svc` 可查看服务状态属性。调试时可按目标分支支持情况使用 `init.svc_debug.no_fatal.<name>`，不要把调试属性留在量产配置中。


**Q5: [learning] [tags:系统启动] 服务崩溃后 init 的 Reap 裁决按什么顺序处理？哪些情况会放大成整机重启？**

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


**Q6: [learning] [tags:系统启动] init.rc 的解析模型是什么？多个 .rc 文件的加载顺序由什么决定？**

Android Init Language 的核心结构是 action（`on <trigger>` 下的命令序列）与 service（`service <name> <path> <args>` 的进程声明），外加 import。init 按固定顺序加载 rc：先读 `/init.rc` 主文件，再按目录层级加载 `/system/etc/init`、`/vendor/etc/init`、`/odm/etc/init` 等——同一目录内按文件名排序，厂商与 OEM 通过把自己的 rc 放进对应目录插入启动逻辑，而不是改主文件。

1. **触发与排队**：action 在其 trigger 满足时入队执行，命令按序执行。`on early-init`、`on init`、`on fs`、`on post-fs-data`、`on boot` 是内置的关键触发点。
2. **加载顺序的意义**：同名服务后加载覆盖先加载（用于 OEM 定制），命令的执行顺序则由触发器与 action 定义顺序决定。
3. **口径**：语法细节以 AOSP `system/core/init/README.md` 为准（2026-10 检索）。


**Q7: [learning] [tags:系统启动] service 声明里最常用的选项有哪些？init 的重启策略是怎样的？**

常用选项：`critical`（关键服务）、`oneshot`（一次性，退出不重启）、`disabled`（不随 class 启动，需显式 start）、`user`/`group`（运行身份）、`seclabel`（SELinux 域）、`socket`（由 init 代建 socket 并传 fd）、`onrestart`（本服务重启时执行的命令）、`ioprio`/`oom_score_adj` 等资源属性。

1. **重启策略**：非 oneshot 服务退出后 init 默认自动重启。`onrestart` 允许声明“重启前要做的事”（如 stop 依赖服务）。
2. **关键约束**：critical 服务按“窗口期内的崩溃次数”判定（默认 4 分钟 4 次），超限触发整机重启——这是 boot loop 的机制源头。
3. **实践**：服务“起来又立刻退、反复重启”时，先读 init 日志里的退出码与 onrestart 执行记录，再查它依赖的前置服务是否就绪。


**Q8: [learning] [tags:系统启动] init.rc 中 oneshot 服务退出后会发生什么？**

oneshot 的含义只有一句话：退出后不重启。AAOS13 `system/core/init/README.md` 对它的定义是 "Do not restart the service when it exits"。省略 oneshot 的服务退出后由 init 自动重启，两者的差别全在退出这一刻。

退出时的动作在 Reap 里完成（`service.cpp`）：代码先判断服务带 SVC_ONESHOT 且不处于 SVC_RESTART、SVC_RESET，满足则置 SVC_DISABLED，通知状态变为 stopped，然后直接返回，不进入重启分支。所以 oneshot 服务退出后进入 disabled 状态，后果有两个：

1. **不会被 class_start 拉起：**disabled 服务不随所属 class 的批量启动命令启动，之后只能显式 start 或 enable。
2. **例外是手动重启：**SVC_RESTART 和 SVC_RESET 被排除在置 disabled 的条件外，手动 restart 或 reset 过的服务退出时不置 disabled，因为重启意图是显式发出的。

典型用法是只该跑一次的任务。开机动画 bootanim 用 disabled 加 oneshot：SurfaceFlinger 按需拉起，放完退出即止，不该再被拉起。需要反复运行的服务不要用 oneshot，应配 restart_period 指定重启间隔（默认 5 秒），去掉 oneshot 并不能让它按需重启。

oneshot 标志还能在运行时切换：ctl.oneshot_on 和 ctl.oneshot_off 属性可打开或关闭指定服务的 oneshot，lazy HAL 按需实例化服务用的就是这个机制。


**Q9: [learning] [tags:系统启动] init 对 critical 服务的“4 分钟 4 次”判定是怎么实现的？进入重启前后发生什么？**

init 为每个 critical 服务维护崩溃计数与时间窗：服务意外退出时计数加一，init 会等待一个退避间隔（指数增长）再重启。若计数在窗口内达到阈值（默认 4 分钟内 4 次），init 判定系统进入不可恢复状态，执行有序重启流程——同步属性、通知关键进程，最后触发整机重启（reboot），并在下次开机继续监督。

1. **与 boot loop 的关系**：每次开机同一 critical 服务都崩满 4 次，就会形成“开机几分钟即重启”的循环——这就是 boot loop 判据的 init 侧机制（现象层的分层判断见本册 boot loop 题）。
2. **排障**：init 日志中 "critical process ... exiting" 与 "rebooting because critical process" 是直接证据。崩溃服务自身的 tombstone 说明根因。
3. **边界**：阈值是 init 编译期/属性口径，不随服务声明变化。非 critical 服务崩溃不会触发整机重启，只按策略重启自身。


**Q10: [learning] [tags:系统启动] 属性服务（property service）是怎么实现的？谁能写哪些属性由什么决定？**

属性是 init 维护的一块共享内存区域（属性域）加一个 Unix 域 socket 服务：读取方直接 mmap 共享内存零拷贝读取。写入方（任何进程）把写请求发到 init 的属性 socket，由 init 校验后统一写内存并持久化。权限模型由 SELinux 决定——每个属性前缀对应可写的域（`ctl.*` 控制命令、`ro.` 不可改、`persist.*` 落盘、vendor 前缀归 vendor 域），策略不允许时写入被静默拒绝。

1. **实现意义**：属性是“启动期的进程间消息总线”，on property 触发器全部依赖 init 作为唯一写入点。
2. **排障**：“setProperty 不生效”先区分三种失败：SELinux 拒绝（avc 日志）、前缀权限不符、属性根本不存在（`ro.` 误当可写）。
3. **边界**：persist 属性的落盘与开机恢复是独立机制，见本册 persist 题。


**Q11: [learning] [tags:系统启动] on property 触发器如何编排启动时序？late_start 为什么是经典案例？**

init 在属性变化时检查所有以 `on property:<name>=<value>` 声明的 action，满足则把其中的命令入队执行——启动时序因此可以完全用属性当“就绪信号”来编排。经典案例是 `late_start`：加密设备开机时 /data 尚不可用，vold 完成解密后设置属性，init 命中对应触发器执行 `class_start late_start`，一次性拉起所有依赖 /data 的服务（网络、UI 服务群等）。

1. **编排价值**：服务声明成 class，用属性触发批量启动，比在 rc 里硬编码先后关系更可组合。
2. **排障**：某类服务“开机不启动”，先查它所属 class 是否已被 class_start（加密分支漏触发是最常见根因），再看属性链是否走到。
3. **边界**：属性触发是启动编排手段，不是通用 IPC——高频通知应走 Binder 或 socket，而非刷属性。


**Q12: [learning] [tags:系统启动] vendor 分区的 init 脚本与属性是怎么注入启动的？vendor_init 是什么？**

init 会加载 `/vendor/etc/init` 等目录中的厂商脚本，按相同的 rc 模型处理服务与触发器。**vendor_init 是受限的 init 子进程及其 SELinux 域**，用于限制厂商脚本对系统内部资源的访问，并非早期厂商属性加载机制的名称。

1. **命令执行**：来自 `/vendor` 的脚本中，大多数访问文件的内建命令会交给 `vendor_init` 子进程执行。启动或停止服务等影响 init 内部状态的命令仍由主 init 执行，并检查调用来源。
2. **属性与权限**：厂商属性由属性服务管理，读写权限受属性上下文和 SELinux 策略约束。脚本来源和命令类型共同决定权限边界。
3. **排障**：检查 rc 是否加载、命令执行上下文和 SELinux 拒绝。服务启动后还需检查它是否成功注册及 VINTF 声明是否匹配。

服务由哪个触发器启动取决于设备配置，不能假定所有厂商 HAL 都在 Zygote 之前启动。依据：Android Vendor init 文档及 AAOS 13 `system/core/init/subcontext.cpp`。


**Q13: [learning] [tags:系统启动] init 的关键触发阶段（early-init → boot）各对应什么动作？**

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


**Q14: [learning] [tags:系统启动] persist.* 属性是怎么持久化的？开机后什么时候恢复可用？**

`persist.*` 属性可由属性服务持久保存，Android 13 在 `/data` 准备好后加载已保存的值。加载之前，早期代码可能读到默认值、先前设置的值或空值，不能假定持久值已经恢复。

1. **恢复与写入**：Android 13 的 `load_persist_props` 在 `/data` 可用后触发加载。持久属性加载完成后，符合条件的 `persist.*` 更新才会写入持久存储。
2. **读取与触发**：持久值恢复前并不意味着属性一定不存在，`on property` 也不是只有恢复后才能触发。调用方应区分当前值和已恢复的持久值，并按需求等待合适的启动阶段。
3. **排障**：检查 `/data` 和持久属性加载状态、属性默认值，以及 SELinux 读写权限。直启动阶段的配置载体要结合所需时序和存储可用性选择。

依据：AAOS 13 `system/core/rootdir/init.rc` 和 `system/core/init/property_service.cpp`。


**Q15: [learning] [tags:系统启动] init 代建的 socket 是怎么用的？为什么很多守护进程的通信入口由 init 创建？**

Android 13 的 service 声明可以让 init 在启动服务时创建 `/dev/socket/` 下的 Unix 域 socket，并把描述符传给服务。这样 socket 的名称和权限可由 init 配置，服务无须自行创建同一个入口。

1. **创建与传递**：init 按 service 的 socket 声明创建入口并设置权限，通过继承的文件描述符和 `ANDROID_SOCKET_` 环境变量让服务取得它。native 服务可用 `android_get_control_socket` 获取描述符。
2. **监听条件**：`stream` 或 `seqpacket` 需要监听。声明带 `+listen` 时 init 同步执行监听，否则不能假定 init 已替服务监听，服务仍需按自身协议处理。
3. **排查时序**：socket 在服务启动时创建，并不保证任意更早的连接都会成功。连接失败或排队取决于入口是否创建、是否监听及服务状态，应结合实际 rc 声明与日志判断。

依据：AAOS 13 `system/core/init/README.md` 中的 service socket 选项。


**Q16: [learning] [tags:Android.bp] system/core/init/Android.bp 中的 defaults、soong_config_module_type 和构建变量如何改变模块配置？**

defaults 模块集中保存多个模块共用的编译属性与依赖。`soong_config_module_type` 则把指定类型的属性开放给 Soong 配置变量。`init_defaults` 让 init 相关模块共享安全编译选项、库依赖和按构建变体调整的宏定义。

下面摘出原文件中的配置类型、构建变体和 Soong 配置变量。为突出属性关系，默认编译参数和部分宏列表已省略：

```bp
soong_config_module_type {
    name: "libinit_cc_defaults",
    module_type: "cc_defaults",
    config_namespace: "ANDROID",
    bool_variables: ["PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT"],
    properties: ["cflags"],
}

libinit_cc_defaults {
    name: "init_defaults",
    // sanitize、依赖、visibility 和 uml 分支等属性省略
    cflags: ["-DINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=0"],
    product_variables: {
        debuggable: {
            cppflags: [
                "-UALLOW_FIRST_STAGE_CONSOLE",
                "-DALLOW_FIRST_STAGE_CONSOLE=1",
                // 其他调试宏省略
            ],
        },
        eng: {
            cppflags: ["-USHUTDOWN_ZERO_TIMEOUT", "-DSHUTDOWN_ZERO_TIMEOUT=1"],
        },
    },
    soong_config_variables: {
        PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT: {
            cflags: [
                "-UINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT",
                "-DINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=1",
            ],
        },
    },
    bootstrap: true,
}
```

1. **定义可配置 defaults 类型**：`libinit_cc_defaults` 包装 `cc_defaults`，将配置命名空间设为 `ANDROID`，允许布尔变量 `PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT` 修改 `cflags`。实例 `init_defaults` 列出警告选项、`-Werror`、线程安全检查、若干安全相关宏及 signed-integer-overflow 检查。它把 `static_libs` 与 `shared_libs` 分开声明，例如静态依赖含 `libavb`、`libxml2` 和 sysprop 库，共享依赖含 `libbase`、`libcutils`、`liblog` 和 `libselinux`。`bootstrap: true` 允许该模块使用非 APEX 版本的库，适用于 APEX 激活前启动的程序。defaults 本身仅对子包可见。
2. **应用产品构建变量**：`debuggable` 变体通过先 `-U` 再 `-D` 的编译参数重新定义宏，启用首阶段控制台、本地属性覆盖、宽松 SELinux、panic 后重启 bootloader、可写 kmsg 等调试行为。`eng` 变体启用零超时关机宏。`uml` 变体定义 `USER_MODE_LINUX`。这些是编译期选择，不是设备运行时动态开关。
3. **应用 Soong 配置变量**：当 `PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT` 为真时，`cflags` 先取消默认的 `INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=0`，再定义为 `1`。该属性能否生效还取决于产品配置是否为 `ANDROID` 命名空间提供了对应值。
4. **控制首阶段安装**：`init_first_stage_cc_defaults` 包装另一个 `cc_defaults` 类型，只开放 `installable`。当 `BOARD_BUILD_SYSTEM_ROOT_IMAGE` 或 `BOARD_USES_RECOVERY_AS_BOOT` 为真时，将 `init_first_stage_defaults` 的 `installable` 设为 `false`。源码注释说明这是为了避免 system-as-root 时覆盖已有符号链接。
5. **通过 defaults 复用**：模块在 `defaults` 中引用相应 defaults 实例后，才继承其中允许配置的属性。具体模块仍可在自己的属性或 `target` 分支中添加变体专属配置。阅读时应分别追踪 defaults 定义、实例覆盖条件和消费模块，不能把 defaults 名称误当成可执行模块。

首阶段的安装条件由另一个自定义 defaults 类型表达，源码关键部分如下：

```bp
soong_config_module_type {
    name: "init_first_stage_cc_defaults",
    module_type: "cc_defaults",
    config_namespace: "ANDROID",
    bool_variables: ["BOARD_BUILD_SYSTEM_ROOT_IMAGE", "BOARD_USES_RECOVERY_AS_BOOT"],
    properties: ["installable"],
}

init_first_stage_cc_defaults {
    name: "init_first_stage_defaults",
    soong_config_variables: {
        BOARD_BUILD_SYSTEM_ROOT_IMAGE: { installable: false, },
        BOARD_USES_RECOVERY_AS_BOOT: { installable: false, },
    },
}
```

**Q17: [learning] init_first_stage 与 init_second_stage 在 system/core/init/Android.bp 中如何区分源码、链接方式和安装目标？**

两个模块都生成名为 `init` 的不同构建变体产物，但承担不同启动阶段：`init_first_stage` 是放入 ramdisk 根目录的静态可执行文件，`init_second_stage` 则以 `main.cpp` 和 `libinit` 组成，并按 platform 或 recovery 目标选择附属文件与依赖。

下面是首阶段链接与安装属性，以及第二阶段 recovery 分支的源码摘录。源码列表、静态库清单和其他属性为突出差异而省略：

```bp
cc_binary {
    name: "init_first_stage",
    stem: "init",
    defaults: ["init_first_stage_defaults"],
    static_executable: true,
    system_shared_libs: [],
    required: ["adb_debug.prop"],
    ramdisk: true,
    install_in_root: true,
}

cc_binary {
    name: "init_second_stage",
    recovery_available: true,
    stem: "init",
    static_libs: ["libinit"],
    srcs: ["main.cpp"],
    target: {
        recovery: {
            cflags: ["-DRECOVERY"],
            exclude_static_libs: ["libxml2"],
            exclude_shared_libs: ["libbinder", "libutils"],
            required: [
                "init_recovery.rc",
                "ueventd.rc.recovery",
                "e2fsdroid.recovery",
                "make_f2fs.recovery",
                "mke2fs.recovery",
                "sload_f2fs.recovery",
            ],
        },
    },
}
```

1. **首阶段源码与链接**：`init_first_stage` 使用首阶段挂载、设备发现、SELinux 标签和切换根目录等源码。将多项底层库列入 `static_libs`，设置 `static_executable: true`，并将 `system_shared_libs` 设为空列表，以构建不自动链接系统默认共享库集合的静态可执行文件。
2. **首阶段安装位置**：模块设置 `ramdisk: true` 和 `install_in_root: true`，将产物放在 ramdisk 根目录。`stem: "init"` 使文件名为 `init`。它还将 `adb_debug.prop` 设为必需模块，并在调试 ramdisk 注释所述场景中提供 adb root 支持。
3. **首阶段变体配置**：`debuggable` 与 `eng` 分支按前述宏配置改变编译行为。`sanitize` 启用 signed-integer-overflow 检查并关闭 hwaddress 检查，源码注释给出的原因是首阶段运行环境可能没有标准输出、标准错误或 `/proc`。
4. **第二阶段及 recovery**：`init_second_stage` 声明 `recovery_available: true`。platform 目标要求 `init.rc`、`ueventd.rc`、`e2fsdroid`、`extra_free_kbytes.sh`、`make_f2fs`、`mke2fs` 和 `sload_f2fs`。recovery 目标增加 `-DRECOVERY`，排除 `libxml2`、`libbinder` 和 `libutils` 等指定依赖，并改用 `init_recovery.rc`、`ueventd.rc.recovery` 及带 `.recovery` 后缀的文件系统工具。`libinit` 自身也提供 recovery 变体，并在该变体排除 `libxml2`、`apex-info-list`、`libbinder` 和 `libutils`。
5. **文件名与模块名**：Soong 模块名分别是 `init_first_stage` 和 `init_second_stage`，两者的 `stem` 都是 `init`。模块名用于构建图和依赖引用，`stem` 用于产物文件名。不能仅凭产物文件名判断它属于哪个阶段。

分析这类属性时要区分“模块可在哪种变体构建”“编译成什么形态”和“安装到哪个镜像位置”：`recovery_available`、`target.recovery`、`static_executable` 与 `ramdisk`/`install_in_root` 分别回答不同问题。

**Q18: [learning] system/core/init/Android.bp 如何构建 init 测试、主机校验工具和生成文件？**

该文件把设备测试、基准测试、测试辅助库、主机校验程序与生成规则声明为不同模块。它们复用部分 init 源码或 defaults，但目标平台、依赖和用途不同，不能把它们都当作设备启动程序。

先看测试模块和生成规则的实际声明。以下代码保留关键字段，测试源码清单以及主机 defaults 的其他属性与依赖项有所省略：

```bp
cc_test {
    name: "CtsInitTestCases",
    defaults: ["init_defaults"],
    require_root: true,
    compile_multilib: "both",
    multilib: {
        lib32: { suffix: "32", },
        lib64: { suffix: "64", },
    },
    static_libs: ["libinit"],
    test_suites: ["cts", "device-tests"],
}

genrule {
    name: "generated_stub_builtin_function_map",
    tool_files: ["host_builtin_map.py"],
    out: ["generated_stub_builtin_function_map.h"],
    srcs: ["builtins.cpp", "check_builtins.cpp"],
    cmd: "$(location host_builtin_map.py) --builtins $(location builtins.cpp) --check_builtins $(location check_builtins.cpp) > $(out)",
}
```

主机侧模块共享的配置和消费者如下：

```bp
cc_defaults {
    name: "init_host_defaults",
    host_supported: true,
    generated_headers: [
        "generated_stub_builtin_function_map",
        "generated_android_ids",
    ],
    target: {
        android: { enabled: false, },
        darwin: { enabled: false, },
    },
}

cc_binary {
    name: "host_init_verifier",
    defaults: ["init_host_defaults"],
    srcs: init_common_sources + init_host_sources,
}
```

1. **设备测试**：`CtsInitTestCases` 是 `cc_test`，继承 `init_defaults`，声明 `require_root: true`，并用 `compile_multilib: "both"` 和 `multilib` 为 32 位、64 位变体设置后缀。它编译多项 init 单元测试，静态依赖 `libinit`，并登记到 `cts` 与 `device-tests` 测试套件。
2. **基准测试与辅助库**：`init_benchmarks` 是静态依赖 `libinit` 的 `cc_benchmark`。`libinit_test_utils_libraries_defaults` 集中测试辅助库的共享依赖。`libinit_test_utils` 复用通用源码和测试服务工具源码，整合 `libcap`，导出 `test_utils/include`，供测试代码使用。
3. **生成头文件**：`generated_stub_builtin_function_map` 是 `genrule`，以 `host_builtin_map.py` 为工具、以 `builtins.cpp` 和 `check_builtins.cpp` 为输入，运行命令生成 `generated_stub_builtin_function_map.h`。命令中的 `$(location ...)` 引用已声明输入/工具路径，`$(out)` 指向声明的输出。主机 defaults 再通过 `generated_headers` 消费生成头文件。
4. **主机 defaults 与工具**：`init_host_defaults` 设置 `host_supported: true`，集中指定主机编译选项、依赖、Lite Proto 配置和生成头文件，并在 `target.android`、`target.darwin` 中禁用对应目标。`host_init_verifier` 复用通用源码与主机校验器源码。`libinit_host` 则构建可复用的主机静态库、导出当前目录头文件，并将 Proto 头文件导出给可见消费者。
5. **可见性与脚本模块**：`libinit_host` 的 `visibility` 限定其可被 `system/apex/tools` 子树引用。`init_second_stage` 的可见性另行允许 Virtualization 的 microdroid 子树使用。`extra_free_kbytes.sh` 通过 `sh_binary` 声明为 Shell 脚本模块，其 `src` 指向源码脚本。

阅读生成链路时，应从 `genrule` 的输入、工具、命令和输出追到消费它的 `generated_headers`。阅读主机工具时，则继续检查 host defaults、目标禁用分支和 `visibility`，以判断它构建在哪个平台以及哪些模块能依赖它。

**Q19: [learning] 阅读 system/core/init/Android.bp 时，源码变量、libinit、phony 模块和 init_second_stage 如何组成模块依赖图？**

这个文件先定义可复用的源码集合和许可，再由具体 Soong 模块组合成库、可执行文件与别名目标。变量本身不是模块。只有被模块的 `srcs`、`defaults` 等属性引用后，才参与相应模块的构建图。

先看包许可和源码集合怎样进入静态库。下面保留了原文件的关键声明，源码数组中的其他项目用注释标出。模块其余属性也未列出：

```bp
package {
    default_applicable_licenses: ["system_core_init_license"],
}

license {
    name: "system_core_init_license",
    visibility: [":__subpackages__"],
    license_kinds: ["SPDX-license-identifier-Apache-2.0"],
    license_text: ["NOTICE"],
}

init_common_sources = [
    "action.cpp",
    "action_manager.cpp",
    // 其余通用源码项省略
]
init_device_sources = [
    "block_dev_initializer.cpp",
    "bootchart.cpp",
    // 其余设备源码项省略
]

cc_library_static {
    name: "libinit",
    defaults: ["init_defaults", "selinux_policy_version"],
    srcs: init_common_sources + init_device_sources,
    generated_sources: ["apex-info-list"],
    whole_static_libs: [
        "libcap",
        "com.android.sysprop.apex",
        "com.android.sysprop.init",
    ],
}
```

再看入口二进制和 phony 目标的关键字段：

```bp
phony {
    name: "init",
    required: ["init_second_stage"],
}

cc_binary {
    name: "init_second_stage",
    stem: "init",
    static_libs: ["libinit"],
    srcs: ["main.cpp"],
    symlinks: ["ueventd"],
    // recovery_available、defaults、target 和 visibility 等其他属性省略
}
```

1. **包与许可**：`package` 将 `system_core_init_license` 设为默认适用许可。`license` 模块登记 Apache-2.0 类型和 `NOTICE` 文本，并将可见范围限制在子包。
2. **源码集合**：`init_common_sources` 保存解析器、服务、属性、事件和通用工具等共享源码。`init_device_sources` 保存设备启动、挂载、属性服务和设备事件等实现。`init_host_sources` 保存主机侧导入解析与校验器源码。Soong 列表表达式可将这些集合与额外文件拼接。
3. **设备静态库**：`libinit` 是 `cc_library_static`，把通用源码与设备源码编成静态库，并通过 `defaults` 继承 `init_defaults` 和 `selinux_policy_version`。`generated_sources` 加入生成的 `apex-info-list`。`whole_static_libs` 整合 `libcap` 与两个 sysprop 静态库。`header_libs` 引用 `bootimg_headers`。Proto 配置使用 Lite 实现并导出生成头文件。恢复模式有单独的依赖和生成源码排除配置。
4. **可执行模块**：`init_second_stage` 是 `cc_binary`，用 `main.cpp` 作为入口源码并静态依赖 `libinit`。模块名用于 Soong 构建依赖，`stem: "init"` 指定产物文件名。`symlinks: ["ueventd"]` 声明安装时关联的符号链接。
5. **phony 别名**：名为 `init` 的 phony 模块通过 `required` 声明构建目标需关联 `init_second_stage`。`init_system` 也以 `required` 关联它。`required` 是模块构建/安装关联，不是 C++ 链接依赖。phony 自身不编译源码或产生可执行文件。

因此，查看 Soong 依赖时要从具体模块的属性向外追踪：例如请求 `init` 会关联到 `init_second_stage`，后者再依赖 `libinit`，而 `libinit` 的 `srcs` 和 defaults 决定参与编译的源码与公共配置。
