# Zygote 与进程启动

> Zygote 创建、预加载、进程派生、USAP 与特殊进程。 各题的平台版本与证据范围以题内说明为准。


**Q1: [learning] [tags:系统启动] Zygote 在 Android 进程模型里扮演什么角色？为什么应用进程要用 fork 而不是各自独立启动？**

Zygote 是带有 ART 运行时和公共预加载内容的模板进程。它通过 fork 派生 `system_server` 与应用进程，使后代复用初始化状态并以写时复制共享尚未修改的物理页。

init 第二阶段解析 `.rc` 后启动 Zygote。Zygote 完成预加载后由主实例直接 fork `system_server`，再进入 socket 循环等待普通进程创建请求。子进程随后 specialize，设置目标 UID/GID、SELinux 域和 seccomp 等安全身份，然后进入 `ActivityThread.main()`。

相对于每个进程独立启动，fork 有三项主要收益：

1. **省时间**：不必每进程重新初始化 ART、加载几千个预加载类。
2. **省内存**：预加载页与未写脏页被所有应用进程共享。
3. **同一起点**：所有进程从一致的运行环境出发。

COW 不等于零成本，后续写入和应用初始化会逐步产生私有页。主 Zygote 在初始化期间调用 `forkSystemServer()` 创建 `system_server`。普通应用由 `system_server` 经 Zygote 请求创建。


**Q2: [learning] [tags:系统启动] Zygote 是怎么被拉起的？启动后依次做什么？**

Zygote 由 init 按服务配置启动，随后在自身启动流程中预加载运行环境、创建 `system_server` 并开始接收应用进程请求。Android 17 的主线如下：

1. `init.zygote64.rc` 声明 Zygote 服务，`zygote-start` 触发器执行 `start zygote`。
2. init fork/exec `/system/bin/app_process64`，并传入 `--zygote`、`--start-system-server` 等参数。具体参数以设备选用的 zygote rc 文件为准。
3. app_process 初始化 ART 并进入 `ZygoteInit.main()`。未启用延迟预加载时，先加载常用类、资源与共享库。
4. Zygote 创建 `ZygoteServer`，使用 init 传入的 Zygote socket 与可选的 USAP 池 socket。
5. 主 Zygote 根据 `--start-system-server` 调用 `forkSystemServer()` 创建 `system_server`。
6. 父 Zygote 进入 `runSelectLoop()`，等待后续应用进程创建请求。

`system_server` 是主 Zygote 启动流程直接 fork 的子进程，不是 init 通过应用请求 socket 创建的服务。init 监督 Zygote 服务。system_server 的崩溃恢复由 Zygote 与 init 的上层恢复链处理。


**Q3: [learning] [tags:系统启动] Zygote 的 preload 到底预加载了哪些东西？为什么所有应用进程能直接共享？**

preload 阶段把“每个应用都需要的公共物”只加载一次：preloaded-classes 清单里的常用框架类、系统资源（drawable/color 资源表）、图形相关初始化与 JCA 安全 Provider。此后所有 fork 出的进程靠写时复制物理共享这些页——读到的都是同一份内存，谁写了那一页才真正复制。

机制：

1. **时机**：主 Zygote 在进入 socket 循环前执行 preload。次 Zygote 用 `--enable-lazy-preload` 跳过大头，只为 32 位应用按需补载。
2. **共享原理**：fork 复制页表而不复制物理页，preload 出来的类元数据与资源位图因此成为全体后代共享的只读页——“省时间”与“省内存”两个收益同源于此。
3. **代价**：清单里的每个类都被全体应用背着——加类开机变慢、删类各应用首载变慢，preloaded-classes 的每次调整都是全局权衡。

收束：排查应用首帧慢时，“目标类不在 preload 清单、首次加载要自己付全部成本”是一个常被忽略的取证点。


**Q4: [learning] [tags:系统启动] Zygote preload 用开机成本换取什么？删减预加载清单或使用 lazy preload 分别要注意什么？**

预加载把一部分应用启动工作转移到 Zygote 启动阶段，并让 fork 后代通过 COW 共享相应内存。调优时要同时衡量开机成本、共享内存收益和首启延迟。

1. **预加载范围**：主 Zygote 在 fork `system_server` 前加载 Framework 类、资源、app-process HAL 与图形驱动、共享库和字体缓存等。AAOS 13 所用源码的 `frameworks/base/config/preloaded-classes` 约有 1.6 万行。数量随源码分支变化。
2. **扩展清单的代价**：增加预加载项可能减少应用启动期类加载，却会增加 Zygote 启动工作、共享页占用和脏页风险。
3. **删减清单的代价**：移除预加载项可减少启动工作，但使用这些类的应用可能在首次加载时承担额外成本。
4. **评估方法**：在干净开机和多应用场景记录 Zygote 预加载时长、`system_server` ready 时间、Zygote PSS、代表应用 TTID/TTFD，以及低内存设备上的重启与 swap。Boot image profile 调优会同时考虑 boot classpath Profile、system_server Profile 与预加载清单，数据应来自真实 CUJ 并随系统镜像发布。
5. **lazy preload 的边界**：`--enable-lazy-preload` 跳过启动时的 preload，但首次收到 preload 请求时，`ZygoteInit.lazyPreload()` 仍执行同一套完整预加载。因此它改变支付时间，不是增量拆分。AAOS 13 材料中的主 64 位 Zygote 不传该参数，32 位 secondary Zygote 传入。验证时分别记录 primary 的 ZygotePreload、secondary 的 ZygoteInitTiming_lazy，以及首个 32 位进程请求前后的延迟。
6. **应用专属类**：业务应用自己的类通常不属于系统 Zygote 的通用预加载集合，不应通过扩展系统预加载解决单个应用的启动问题。


**Q5: [learning] [tags:系统启动] Zygote 的 fork 模型有哪些硬约束？“zygote 本体没有 Binder”是怎么来的？**

Zygote fork 会复制地址空间和线程状态，因此 Android 必须在 fork 前控制线程创建，并在 fork 周围暂停运行时活动。preload 期间不能随意创建线程，GC 线程会在 preFork 阶段暂停，Binder 线程池则延迟到 fork 后的子进程初始化。`nativeZygoteInit` 只在子进程路径调用。若 Zygote 本体在不受控时启动线程或 Binder 工作，后代可能继承不一致的线程状态。

配套手法：

1. **fd 继承传递**：init 预建监听 socket，fork 后子进程继承 fd。环境变量 `ANDROID_SOCKET_<name>` 传递 fd 编号。无亲缘关系的进程之间传递 fd 则需使用 SCM_RIGHTS。
2. **Runnable 退栈**：fork 后子进程暂时保留 Zygote 的调用栈。Android 将目标 main 包装为 Runnable，并沿调用链逐层 return，退回 fork 分支后才执行目标入口，避免业务入口长期保留孵化调用栈。
3. **请求方身份校验**：应用创建请求依据 socket 对端凭据（SO_PEERCRED）校验调用方，不能只相信请求内容自报的身份。具体权限仍由本地 socket 的访问控制与 Zygote 侧校验共同决定。

边界：“模板进程 + N 个派生进程”的架构才适合 fork 模型，差异大的负载（独立工具进程）fork 反而拖累（继承整个 VM）。Android 13 批注还勘误了一处上游过时注释：现行代码用普通 return 退栈，不是历史上的抛异常方式。


**Q6: [learning] [tags:系统启动] USAP 池与“厂商预启动”是什么关系？为什么 trace 里没看到 fork 不能证明系统预启动了应用？**

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


**Q7: [learning] [tags:系统启动] 主 Zygote 和次 Zygote 怎么分工？preload 与 USAP 池各有什么坑？**

双 ABI 设备通常由 64 位主 Zygote 服务 64 位应用并创建 `system_server`，32 位次 Zygote 服务 32 位应用。具体实例由设备 ABI 列表和 zygote rc 配置决定，不能仅凭“Android 有主次 Zygote”推断每台设备都运行两个实例。

1. **进程职责**：`--start-system-server` 由主 Zygote 使用。次 Zygote 不创建 `system_server`。次 Zygote 是否采用 `--enable-lazy-preload` 由启动配置决定。
2. **就绪关系**：框架启动时可等待配置要求的次 Zygote 就绪。这是启动依赖关系，不代表两个 Zygote 互为看门狗。
3. **预加载取舍**：扩大预加载集合会增加 Zygote 启动工作，但可让子进程共享更多类。删减会缩短部分启动工作，却可能增加应用首次加载成本。
4. **USAP 取舍**：USAP 预先 fork 待命进程，取用时再 specialize。若启动请求不受支持或池不可用，会回退到普通 Zygote 路径。调试器附加等场景也可能走普通 fork。
5. **注入兼容性**：USAP 改变了 fork 时机，依赖特定 fork 钩子的注入框架可能受影响。Magisk/Zygisk/Riru 等社区案例应按框架版本和设备实现核查，不能概括为必然不兼容。

排查应用创建路径时，先确认设备是否实际运行双 Zygote、USAP 是否配置启用，以及请求是否处于调试或其他回退场景。


**Q8: [learning] [tags:系统启动] USAP 为什么默认关闭？开启前要确认什么？开了以后还能用 PostFork trace 诊断吗？**

USAP 默认关闭是为了让设备显式评估进程创建收益、资源占用和路径兼容性后再启用。启用前要核实属性优先级、池配置、请求覆盖范围和目标 Zygote 类型。开启后仍可用 PostFork trace 分析 specialize 与类加载耗时。

1. **默认值来源**：AAOS 13 源码中的 `ZygoteConfig.USAP_POOL_ENABLED_DEFAULT` 为 false。实际开关依次读取 `persist.device_config.runtime_native.usap_pool_enabled`、`dalvik.vm.usap_pool_enabled`，再回退到内置默认值。具体优先级应以目标分支为准。
2. **启用前检查**：进程创建密集的场景才可能从预 fork 获益。还要核对池大小等参数和目标 Zygote 是否支持。该分支 child Zygote 的 `mUsapPoolSupported` 为 false。
3. **PostFork 诊断**：USAP 被取用后仍会执行 PostFork 相关处理、specialize 和后续类加载，因此 trace 可继续观察这些阶段。应先确认 trace 标记的进程创建来源，避免把填池 fork 与请求命中的 specialize 混为一谈。
4. **收益判断**：USAP 预先支付的是空壳 fork 成本。如果 PostFork 之后的 specialize 或类加载占主要耗时，开启 USAP 对该部分帮助有限。这是由它提前 fork、但仍需 specialize 的机制推导出的判断，需用目标设备数据验证。


**Q9: [learning] [tags:系统启动] fork 派生模型的内核成本省在哪？16 KB 页会改变 COW 的什么？**

省在 COW（写时复制）：fork 时内核 dup_mmap 只复制页表并清除写权限，子进程首次写触发缺页、走 do_wp_page/wp_page_copy 才复制页面——预加载的类、资源与驱动初始化状态因此被全部 app 共享，只有被写的页付复制成本。

16 KB 页改变的是 COW 粒度：单次复制页从 4 KB 变 16 KB，不改变 VMA（虚拟内存区域）数量。机制推导：同样写入模式下，16 KB 设备的页级写放大更大、缺页次数更少，内存账与 4 KB 设备不可直接对比。做法：评估 Zygote 派生收益读 Private_Dirty——共享页 RSS 高而 Private_Dirty 低是健康态。跨页大小比较时分别测量。


**Q10: [learning] [tags:系统启动] App Zygote（ZygotePreload）适合什么场景？它和系统 Zygote 的预加载边界怎么分？**

App Zygote 是应用自己的“应用级 zygote”：API 29 起，manifest 配 `useAppZygote="true"` 并用 `android:zygotePreloadName` 指定实现 ZygotePreload 的类，先孵化一个持有应用公共状态的进程，再由它 fork 出实际服务进程（如 isolated 进程、WebView 渲染进程）。

分界原则：预加载只有被 N 个子进程共享才有收益——全 app 公共内容放系统 Zygote（preload 一次全场共享），应用专属公共内容放 App Zygote（池内共享），单进程独享的放进程自己的启动路径。机制：App Zygote 池内 COW 共享，子进程崩溃可回池再 fork。32 位 WebView 可由 secondary Zygote 按需预载其 provider 代码。


**Q11: [learning] [tags:系统启动] 普通应用进程是怎么诞生的？它和 system_server 的诞生路径差在哪？**

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


**Q12: [learning] [tags:系统启动] webview_zygote 是什么？应用声明 isolatedProcess 的服务跑在什么进程里？**

webview_zygote 是供 WebView 渲染进程使用的专用 Zygote。应用声明 `android:isolatedProcess="true"` 的服务则使用隔离 UID 和对应 SELinux 域。二者都涉及隔离进程，但普通 isolated service 不因此变成 WebView renderer，也不必由 webview_zygote 孵化。

1. **webview_zygote**：它按目标 ABI 预载 WebView provider 代码并孵化 renderer 进程。provider 更换时系统会按其更新流程重建相应孵化器。`ps -A | grep zygote` 可检查相关进程，`dumpsys webviewupdate` 可查看 provider 与 zygote 状态。
2. **isolatedProcess**：`android:isolatedProcess="true"` 请求系统把 Service 放入隔离 UID 和相应 SELinux 域，限制其权限与资源访问。省略或设为 false 时，服务不会因该属性获得隔离进程。是否仍因其他组件规则分进程需另行判断。WebView renderer 也受隔离策略约束，但启动用途不同。
3. **进程孵化器选择**：主/次 Zygote 由 `ro.zygote` 与设备 ABI 配置决定。应用可用 `android:useAppZygote="true"` 请求应用专属 Zygote，该属性省略时不请求此路径。不能仅凭 `isolatedProcess="true"` 推断服务来自 webview_zygote。


**Q13: [learning] Boot Image 的 .art/.oat/.vdex 各存什么？进程间怎么共享、怎么判断共享是否被打破？**

`.art` 存预初始化的堆对象镜像，`.oat` 存 AOT 编译的原生码，`.vdex` 存验证元数据与原始 dex。三者以 MAP_PRIVATE 映射实现写时复制共享，镜像 bitmap 区段映射为 PROT_READ 只读。

前提：boot image 在开机时由 zygote 映射一次，把 BCP 类的编译与初始化成果摊给所有进程。机制：共享部分计入各进程 RSS 但不重复占物理内存，被写的页转入 Private_Dirty。压缩镜像映射后表现为匿名内存。结果：读 smaps 判断健康度——boot image 区域 RSS 高、Private_Dirty 低是常态，Private_Dirty 异常增大说明有进程在写共享镜像。

**Q14: [learning] 设备如何选择 boot image 的位置？odrefresh 什么时候触发重建？**

优先用 APEX 数据目录中经 odsign 验证的镜像：`odsign.verification.success=true` 且 `/data/misc/apexdata/com.android.art/dalvik-cache` 下镜像完整时使用。否则回退 `boot_minimal.art` 或 `/system/framework/<isa>/boot.art`，且 odsign 未验证时以 `deny_art_apex_data_files` 拒用 APEX 数据文件。

机制：ART 模块更新或 BCP 构成变化会让现镜像失效，odrefresh 校验当前镜像的组件校验和与依赖（含 dirty-image-objects），不满足就重建。boot image 编译过滤器用 speed-profile（Android 12 起官方配置）。结果：ART 模块更新后的首次开机可能明显变慢，要区分“镜像回退到最小镜像”与“odrefresh 重建进行中”两种慢。

**Q15: [learning] Zygote 预加载碰了图形栈的哪些部分？nativePreloadAppProcessHALs 预加载的是缓冲区分配器吗？**

不是。Android 17 的 `ZygoteInit.preload()` 中有两类相关预热：

1. `nativePreloadAppProcessHALs()` 调用 `GraphicBufferMapper::preloadHal()`，预热 gralloc mapper HAL。
2. `maybePreloadGraphicsDriver()` 预热 EGL 或 Vulkan 驱动。

mapper 负责 buffer 元数据查询、导入和映射，allocator 才负责创建/分配 buffer，因此 mapper 预热不等于 allocator 预热。

这种预热发生在 fork 前，使子进程可继承已加载的映射代码和部分初始化状态。它不创建应用所需的具体图形 buffer，也不执行 allocator 的分配请求。因此，首个 buffer 分配慢仍要分别检查 allocator、buffer usage 和驱动路径，不能只凭 mapper 已预热就认定分配器也已就绪。

**Q16: [learning] Android 17 的 mapper 预加载为什么跳过 Gralloc 2/3？什么情况下直接 FATAL？**

Android 17 的 `GraphicBufferMapper::preloadHal()` 按 `requireMapper4()` 裁剪：当设备 API level ≥ 36 且 `require_gralloc4_or_newer` 平台 flag 生效时，跳过 Gralloc 2/3，只预热 Gralloc 4/5。随后 mapper 选择也不再回退到 Gralloc 2/3。若 4/5 都不可用，构造 mapper 时会以 `LOG_ALWAYS_FATAL` 终止进程。

API level 和 flag 共同限定兼容性门槛，避免新设备继续依赖旧 mapper 接口。若必需 mapper 缺失，故障发生在请求构造 mapper 的进程中。是否表现为开机故障取决于哪个系统进程最先触发它，不能笼统说一定发生在开机阶段或与 app 无关。

**Q17: [learning] maybePreloadGraphicsDriver 把图形驱动预热到什么程度？ro.zygote.disable_gl_preload 关掉的是什么？**

Android 17 的 `zygote_preload_graphics()` 按 HWUI 后端做轻量调用：

1. SkiaGL 路径调用 `eglGetDisplay(EGL_DEFAULT_DISPLAY)`。
2. Vulkan 路径调用 `vkEnumerateInstanceVersion()`。

这两步都不创建 EGLContext、VkInstance 或 VkDevice。

`ro.zygote.disable_gl_preload` 为 `true` 时跳过这步图形驱动预热。省略时默认 `false`，即执行预热。该属性只控制图形驱动预热，不关闭 `nativePreloadAppProcessHALs()` 的 mapper HAL 预热。Zygote 预热因此只让一部分驱动加载成本可由 fork 子进程共享，应用上下文、设备对象、shader 和窗口 swapchain 等初始化仍属于应用/窗口阶段。

**Q18: [learning] 首帧开销里哪些是 Zygote 预加载帮不上的？HardwareRenderer.preload() 能提前什么？**

Zygote 无法创建依赖具体应用窗口和上下文的 buffer、BufferQueue/BLAST 队列及 SurfaceFlinger 合成状态，也无法替应用完成 shader 编译。Android 17 的 `HardwareRenderer.preload()` 在应用进程中提前执行以下工作：

1. 启动 RenderThread。
2. 按当前 HWUI 后端预热 EGL 或 Vulkan context。
3. 初始化 HardwareBitmapUploader。

它把线程和渲染上下文的部分冷启动成本提前到 Activity 可见之前，但不建立具体窗口的 swapchain 或完成整帧绘制。

`HardwareRenderer.preload()` 是进程内的提前预热，和 Zygote 的跨进程共享预热作用不同。它可以减少首次创建 RenderThread/context 的临界路径工作，但不能代替具体窗口创建、buffer 分配、shader 首次编译或 SurfaceFlinger 合成。
