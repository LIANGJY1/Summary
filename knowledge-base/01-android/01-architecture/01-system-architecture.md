# Android 系统架构

> 学习资料（文章模式沉淀）。主线：分层架构与进程边界、跨层接口、HAL 与 Treble、近年架构边界、进程与线程的架构分工。源文档：android-internals-wiki §1.1《Android 分层架构、进程模型与线程协作》（机制按 AOSP `android-17.0.0_r1` 与 ACK `android17-6.18-2026-06_r6` 核对）；16 KB 分发时间线、应用沙箱与 ART 编译策略已于 2026-09-23 与官方资料核对。启动全链路（init、.rc、Zygote、system_server、应用进程诞生）见 [02-system-boot.md](02-system-boot.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 的五层架构怎么理解？各层中的典型对象都有什么？**

五层按**职责**划分 Android 组件，不表示进程或编程语言也严格分成五份。理解时先看组件承担的职责，再用代表性对象定位：

1. **应用层**：承载单个应用的业务逻辑与界面。典型对象有 Activity、Compose/View、应用业务线程和 RenderThread，通常运行在应用自己的进程。
2. **应用框架层**：提供应用可调用的系统 API 与系统服务。Activity、View 等 SDK 客户端代码位于应用进程。ActivityTaskManagerService、WindowManagerService、PackageManagerService 等服务端通常位于 `system_server`。
3. **原生库与 ART 层**：提供运行时和通用原生能力。ART 执行 dex 并管理 GC。Bionic、Skia/HWUI、SQLite、libbinder 等是原生库。SurfaceFlinger、AudioFlinger 等是独立原生服务进程。
4. **HAL 层**：以稳定接口封装硬件相关能力。典型组件包括显示、相机、音频和蓝牙 HAL，实现可以是独立服务，也可能采用直通式加载。
5. **Linux 内核层**：负责调度、内存、电源、网络、文件系统、Binder 驱动和设备驱动等内核职责。

这里的“层”是职责归类：同一进程可以承载多个层的代码，同一层的组件也可能分布在多个进程。HAL 接口采用 Stable AIDL 或存量 HIDL 的情况，以及各层之间如何调用，分别由后续问题说明。

**Q2: [learning] Android 五层架构之间是通过什么方式通信的，可以跨层通信吗？**

层与层之间通过各边界上的明确定义接口通信：同进程的跨界是进程内函数调用或 JNI，跨进程的跨界必须走显式 IPC（Binder 等）。五层是职责划分，不是调用管线，上层可以跳过中间层直达下层。

各边界常用的接口如下：

1. **应用 → 应用框架**：同进程内经 SDK API 调用客户端代码。跨进程时通常经 Binder 进入 `system_server`。
2. **应用框架 → 原生库与 ART**：框架的 Java 代码可通过 JNI 调用同进程原生库。原生框架服务之间可通过 C++ Binder 跨进程通信。
3. **框架 → HAL**：新 HAL 可使用 Stable AIDL，存量 HIDL HAL 使用 hwbinder。大数据可辅以 FMQ 或共享缓冲区传输。
4. **原生库/HAL → 内核**：通过系统调用、`ioctl`、`mmap` 或设备节点访问内核能力。

跳过中间层的直达是常态：应用可以经 Bionic 直接发起文件系统调用，直达内核而不经过框架服务和 HAL。SurfaceFlinger 可以直接对接 Composer HAL 和 DRM 显示子系统。

以 SurfaceFlinger 为例，应用将渲染缓冲区经 BufferQueue 交给它。SurfaceFlinger 按 vsync 周期把缓冲区句柄、几何、混合模式和 Z 序等图层信息经 Binder 交给 Composer HAL。厂商 HAL 再通过 DRM/KMS ioctl 把图层提交给显示控制器的硬件 plane。需要 GPU 合成的图层则由 SurfaceFlinger 的 RenderEngine 经 DRM 渲染节点处理。此原生路径不要求每一步都经过 Java 框架服务，“跨层直达”描述的是组件直接跨越相应接口边界。

**Q3: [learning] Android 实际工作场景中都有哪些架构问题？如何定位架构问题？**

真实架构问题的共同形态是“现象在应用、根因可能在任何一层”：典型场景如主线程同步 Binder 调用过长、SurfaceFlinger 合成变长、Camera 请求返回慢。定位的标准动作是沿“进程 → 跨层接口 → 线程状态 → 内核等待对象”逐边界取证，每多跨一个边界就多保存一份证据。

三个典型场景的取证路径：

1. **主线程同步 Binder 很长**：先取调用方 Binder 时间片、目标进程与线程，向下追目标线程的调度、锁、I/O 与下游 Binder。不要直接得出“Binder 驱动慢”。
2. `surfaceflinger` 的 composite 变长：先取 SF 主线程、CompositionEngine、HWC/RenderEngine 事件，向下追合成类型、同步栅栏、GPU/HWC 与图层变化。不要直接得出“一定是应用绘制慢”。
3. **Camera 请求返回慢**：先取应用框架、CameraService 与 HAL 间的事务及请求 ID，向下追 HAL 线程、FMQ/缓冲区、同步栅栏、驱动与传感器。不要直接得出“HAL 只是接口，不会延迟”。

通用定位流程：

1. 确定工作发生在哪个进程（进程树/`ps`）。
2. 确认调用跨过哪些接口——同步 Binder 调用可沿 Perfetto 的流向关联（flow）找到目标进程的处理线程。
3. 看目标线程状态，按状态判读等待原因。
4. 涉及图形、相机、音频等大数据时，把控制命令、缓冲区生命周期、同步栅栏分开看。
5. 回到源码确认时间片段对应的执行边界。

线程状态（第 3 步）按含义与排查方向判读：

1. **Running**：正在 CPU 上执行，看执行内容判断在算什么。
2. **Runnable**：在等 CPU，结合优先级、频率、温控。
3. **Sleeping**：通常在等事件——Binder 回复、futex 锁、epoll、I/O。
4. **Uninterruptible**：不可中断等待，看 `wchan` 与驱动事件。

收尾判断规则：调用 API 的进程不是根因的唯一候选，单看一张 `dumpsys` 快照没有时间线，定不下因果。

**Q4: [learning] Android 架构层级与进程是什么关系？一个应用进程都包含哪些层级的代码？了解这些有什么用？**

一个普通应用进程里同时运行着应用自身代码、Framework 客户端类、ART 运行时和原生库，它们处于同一个进程地址空间并共享该进程的生命周期：

1. **应用代码**：dex 字节码、业务线程、应用自带的 `.so`。
2. **Framework 客户端类**：Activity、View 和系统服务客户端等框架 API 类由设备上的 boot classpath 提供。编译应用时使用的 `android.jar` 是 API stub，不是运行时装载的实现。
3. **ART**：dex 的 AOT/JIT 混合编译（安装/运行期把热点代码编译成本地代码）、GC、线程管理。
4. **原生库**：Bionic libc、libbinder、图形（Skia/HWUI）等，随系统镜像提供。

层与进程是多对多关系，一个进程承载多层的代码，一个层也散布在多个进程：

1. 一个应用进程同时装着应用代码、Framework 客户端代码、ART 和原生库。
2. `system_server` 混合 Java 服务与 JNI 库。
3. SurfaceFlinger 是独立原生服务进程。
4. HAL 可能是独立 Binder 服务进程，也可能以共享库形式加载进调用方进程。

了解这些的用处有三点：

1. **崩溃归属**：任何一层的缺陷都表现成“这个进程的问题”——进程崩溃或被杀带走全部四套代码，不能因为“我的业务代码没问题”就排除 Framework、ART 或原生库的原因。
2. **性能归属**：耗时在业务逻辑、Framework 分发、ART（GC/JIT）还是原生库/渲染线程，优化动作完全不同。
3. **安全边界**：进程边界就是沙箱边界，每个应用默认独占一个 Linux UID 和一个进程，跨进程访问必须走 Binder 等显式 IPC。

**Q5: [learning] Android 进程与线程有什么区别，同一应用为什么会使用多个进程？**

进程拥有独立的虚拟地址空间和运行时状态。线程是进程内执行流，共享进程内存与资源，但各自有调用栈和调度状态。同一应用的组件默认运行在应用主进程，需要时可通过 Manifest 的 `android:process` 将组件放到另一进程。

使用多进程会改变状态共享和资源成本：

1. **隔离收益**：特定服务可拥有独立崩溃和内存回收边界，也可满足组件必须在单独进程运行的约束。
2. **状态边界**：静态字段、单例和对象缓存不跨进程共享，组件间需要通过 IPC 交换数据。
3. **资源开销**：每个进程要维护自己的地址空间和运行时状态，通常增加内存占用。进程隔离不会自动把主线程工作变成后台任务。

以冒号开头的进程名（如 `:worker`）表示应用私有的进程名后缀，实际名称会以应用包名为前缀。显式完整名称可能允许不同应用在共享 UID 等条件下请求同一命名进程，具体可用性还受组件与平台权限规则约束。不要仅凭进程名推断线程、隔离权限或执行优先级。

**Q6: [learning] 16 KB 页大小、Mainline 模块化、VNDK 废弃——这三个近年架构边界分别改变了什么？**

三者分别影响原生二进制兼容、系统模块分发与 system/vendor 原生库依赖。它们不能互相替代：16 KB 页要求原生代码适配运行时页大小，Mainline 模块可独立于整机 OTA 更新，VNDK 则从 Android 15 起废弃并调整厂商可用库的分发方式。

1. **16 KB 页大小**（Android 15 起支持）：只含 Java/Kotlin 代码的应用通常无需为 ELF 对齐重新构建。含 NDK 库或经 SDK 间接引入 `.so` 的应用要检查 ELF `LOAD` 段对齐和 APK 内未压缩原生库的 ZIP 对齐。设备当前页大小可用 `adb shell getconf PAGE_SIZE` 读取。Google Play 要求目标为 Android 15（API 35）及以上的应用支持 64 位设备上的 16 KB 页。自 2027-02-01 起，不支持 16 KB 的应用更新不能发布。这是分发要求，不表示所有设备都运行在 16 KB 模式。
2. **Mainline**：部分系统组件以 APEX 或 APK 模块分发，可经 Google Play 系统更新独立于整机 OTA 更新，因此同一平台版本的设备可能安装不同模块版本。分析问题时应同时记录 build fingerprint 和相关模块版本。模块升级仍须遵守相应模块接口与兼容契约，不能仅根据平台版本推断模块实现。
3. **VNDK 废弃**（Android 15 起）：针对 Android 15 构建的 vendor/product 分区不再声明 `ro.vndk.version` 等 VNDK 版本属性，原 VNDK 库改按 vendor-available 库安装到 vendor 或 product 镜像。VNDK 废弃不等于动态链接器命名空间隔离取消，加载前的可访问性仍受命名空间规则约束。

版本依据：Android Developers《Support 16 KB page sizes》。Android Open Source Project《Vendor Native Development Kit (VNDK) overview》。

**Q7: [learning] 判断一台 Android 17 设备的运行时行为，为什么要同时核对五个版本维度，而不是只看系统版本号？**

系统版本号只覆盖平台构建一个维度。targetSdk 兼容行为、Mainline 模块版本、vendor 分区与 HAL 版本、内核 GKI/KMI 代际各自独立演进，任何一维不同都可能让“同一 Android 17”表现出不同行为。

五个维度与各自的判断落点：

1. **平台构建**：SDK/API level 与系统镜像决定框架代码基线。
2. **应用 targetSdk**：新平台的收紧行为按 targetSdk 门控，同设备上不同 targetSdk 的应用表现可以不同。
3. **Mainline 模块版本**：ART 等组件以 APEX 独立于 OTA 更新（ART 模块自 Android 12 引入，见 [06-art-runtime.md](06-art-runtime.md)），排障要记录模块版本。
4. **vendor 分区与 HAL**：Treble 解耦后框架对 vendor 的依赖由 VINTF 兼容矩阵约束。
5. **内核分支与 KMI**：GKI 让内核与平台 release 解绑，先确认设备实际内核再看内核行为。

做法：采集 build fingerprint、各应用 targetSdk、模块版本、VINTF manifest 与内核版本/KMI 字符串，五项齐了再下结论。

**Q8: [learning] Treble 的“框架与 vendor 解耦”靠什么机制保证？GSI 为什么能当验证载体？**

Treble 用 VINTF（vendor interface）约束框架与 vendor 之间的接口版本。设备在 vendor manifest 中声明已实现的 HAL 版本，框架兼容矩阵（FCM）声明所需接口，启动或兼容性验证时据此检查匹配关系。HIDL 已弃用，新 HAL 应使用稳定 AIDL。

GSI 尽量使用通用框架实现，并通过声明的 vendor 接口与设备侧实现协作，因此可作为兼容性测试载体。刷入 GSI 可发现部分框架与 vendor 集成问题，但不能单独证明设备满足全部接口、兼容性和认证要求，也不覆盖厂商扩展功能与产品性能。判断完整兼容性还需结合 VINTF 检查、VTS/CTS 和产品测试。

**Q9: [learning] Android 的进程回收架构由哪些角色组成？杀、冻、压分别是谁在做什么？**

Android 17 的进程回收和压缩由不同角色协作。OomAdjuster 给出进程重要性，lmkd 负责按内存压力选择终止目标，Freezer 暂停符合策略的缓存进程，mmd 维护 ZRAM 压缩。杀、冻、压是三种不同操作。

1. **OomAdjuster**：在 `system_server` 中根据组件活跃状态及绑定、Provider 等依赖关系计算进程重要性，并将 adj、procState、schedGroup 和能力标志同步给 lmkd。adj 数值越小通常越受保护。
2. **lmkd**：在用户态监控内存压力，根据 PSI、swap 剩余和页面回收后的再访问（refault）等信号选择合格目标并发送 SIGKILL。它不是简单地杀 RSS 最大的进程。
3. **Freezer**：通过 cgroup 暂停缓存进程的线程调度，进程仍存在。是否冻结取决于缓存状态、阈值与策略。冻结期间发生不允许的同步 Binder 事务时，系统可能终止该进程。
4. **mmd**：负责 ZRAM 重压缩、写回和预取，改变缓存进程的内存驻留与回切代价，但不取代 lmkd 的终止决策。

排查按症状走三条证据链：

1. **进程没了**：查退出原因（`dumpsys activity exit-info`）与 lmkd 日志。
2. **进程在但不跑**：查冻结状态（`dumpsys activity processes`、cgroup.freeze 文件）。
3. **回切慢**：查 ZRAM 写回与预取记录。

**Q10: [learning] 一个 Android 应用进程内部有哪几类固定线程角色？一次点击到上屏经过哪些线程？**

常见应用进程包含主线程、硬件加速下的 RenderThread、接收远程 Binder 请求的线程，以及应用自行创建的后台执行器。从输入事件已被系统路由到目标窗口开始，一次点击通常经过"应用主线程接收与分发 → 业务逻辑 →（可能跨进程 Binder 调用）→ 主线程遍历 View 并记录绘制内容 → RenderThread 准备并提交渲染工作 → SurfaceFlinger 合成"。这是常见路径，动画、无效化合并和缓存可能改变具体帧的工作量。

1. **主线程**：由 `ActivityThread.main()` 启动 Looper 消息循环，承载生命周期回调、输入分发、测量/布局和显示列表记录。它上面的长消息是掉帧的最直接原因。
2. **RenderThread**：硬件加速窗口才有，消费主线程记录的显示列表、准备并提交 GPU 工作。与主线程在帧同步点交接——既不是“主线程提交后立即自由”，也不是“等 GPU 画完整帧”。
3. **Binder 线程池**：接收跨进程调用。远程 AIDL 回调默认落在这里而不是主线程，实现必须自己保证线程安全。主线程主动发起同步 Binder 调用时同样会被拖住。
4. **后台执行器**：协程、线程池、HandlerThread 等。选择依据是生命周期绑定、持久化、串行/并发需求——HandlerThread 只在任务需要 Looper 亲和时用，需在进程重启后仍存活、由约束触发的持久任务交给 WorkManager。

边界：软件渲染窗口不走硬件渲染路径。协程改变的是任务结构不是速度，`Dispatchers.Main` 上的协程仍然占用主线程。

**Q11: [learning] 应用的所有请求都需要经过 Android 的五层架构吗？可以跨层通信吗？**

不需要。五层是职责划分，不是一条所有请求都必须流经的调用管线：一次请求只穿过它实际跨越的层，很多高频请求完全绕开“应用框架服务”这一层，数据面请求也常常不经 HAL。

按请求类型分开看：

1. **管理类、跨应用类**：通常经 Binder 进入 `system_server`——安装/查询应用（PMS）、启动组件（AMS/ATMS）、窗口操作（WMS），之后才可能继续向下到原生库、HAL 与内核。
2. **进程内数据面**：文件读写和绝大多数计算在应用进程内完成——Java API 只是进程内库函数（libcore/Bionic），直达系统调用进入内核，不经过框架服务进程。
3. **图形**：应用经 Skia/HWUI 提交到 GPU 驱动（内核），合成在独立的 SurfaceFlinger 进程对接 Composer HAL 与 DRM 显示子系统。
4. **相机、音频**：Binder 只传控制命令，数据经共享缓冲区/FMQ 直达 HAL 与驱动，控制路径和数据路径不同。

判断方法：把一次操作拆成“控制命令走哪、数据走哪”，数它跨过的进程和边界，就知道该去哪些进程取证。“应用发起的请求”不等于“会逐层经过五层”。

**Q12: [learning] Android 说的“原生库”指什么？“原生”（native）在这里是什么意思？**

原生库指用 C/C++ 编译成本机机器码、以 .so 形式分发、由 CPU 直接执行的库。“原生”是 native 的中译，取“CPU 本机指令集”之义，对立面是需要在虚拟机里翻译执行的字节码，而不是“系统原装”的意思。

"native"的地基：CPU 只执行自己指令集的机器码，native code 即编译成这套指令、无中间翻译层直接执行的代码（NDK 的 N 就是 Native）。它的对立面是 dex 字节码——不面向具体 CPU，由 ART 在运行时翻译，对象活在 GC 管理的堆上。一段代码是不是“原生世界”的成员，看它的进程里有没有 ART、内存由谁管理。

“库”的形态对照：

1. **原生库**：动态库 .so，装载进调用方进程的地址空间，进程内直接调函数——Bionic libc、Skia、SQLite、libbinder，以及应用经 NDK 打包的 libxxx.so。
2. **独立原生服务**：ELF 可执行程序，由 init 拉起为无界面守护进程——SurfaceFlinger、AudioFlinger。“原生库与 ART 层”这个名字把两种形态都框进同一层，“库”字不读死。

边界与纠偏：

1. WMS 也是系统自带，但它是 Java 类、活在 ART 里，不是 native——“原生”不等于“原装、出厂自带”。
2. ART 自己就是 native 库（libart.so），是 native 世界派去管理 dex 世界的运行时，这也是这一层叫“原生库与 ART 层”的原因。
3. 应用侧的直观体感：NDK 写 C/C++ 编出 .so 塞进 APK，System.loadLibrary() 加载后经 JNI 调用。

**Q13: [learning] C/C++ 编写的组件都属于“原生库与 ART 层”吗？**

不属于。“C/C++ 编写”只说明组件是 native 代码（实现形态），层归属是架构角色，由“随谁分发、服务谁、在哪个边界”决定。C/C++ 代码实际横跨五层中的四层。

四层对照：

1. **应用层**：游戏引擎与 App 的 NDK 模块（如 libunity.so）——随 APK 分发、跑在应用进程、服务单个应用的业务。
2. **原生库与 ART 层**：Skia、SQLite、libbinder、SurfaceFlinger——随系统镜像分发、为全系统提供公共能力。
3. **HAL 层**：厂商的相机、音频、显示 HAL 实现——C/C++ 写成，贴硬件、躲在稳定接口后面。
4. **内核层**：Linux 内核与驱动本身就是 C。

归属三问的判断顺序：先问随谁分发（系统镜像还是 APK），再问服务谁（全系统公共能力还是单应用业务），最后问在哪个边界（贴内核驱动属 HAL/内核，管 dex 的是 ART）。语言只是入场券，席位由归属决定——应用自带的 C++ 是“运行在应用层的原生代码”，内核的 C 是内核层，厂商的 C++ HAL 是 HAL 层。

**Q14: [learning] HAL 的实现代码是 Java 还是 C/C++？框架与 HAL 之间为什么用 Binder 而不是 JNI？**

现代 Android 的 vendor HAL 服务通常使用 C++ 或 Rust 等 native 实现，但不能概括成“HAL 只能用 C/C++”。AIDL 可生成 Java、C++、NDK 和 Rust 等后端。vendor 分区能否使用某个后端还受稳定性、服务注册和分区 API 规则限制。Java 也可作为框架侧客户端，例如 CarService 通过 Binder 调用 VHAL。

实现形态由接口、进程边界和硬件数据路径共同决定：

1. **硬件访问**：HAL 常需对设备节点执行 ioctl、映射缓冲区、与内核驱动交换结构化数据，因此 native 代码和对应系统库较常见。JNI 本身既不是 ioctl 接口，也不能替代跨进程通信。
2. **大数据通路**：相机图像、显示缓冲区和音频数据可使用共享内存、DMA-BUF、FMQ 或同步栅栏等机制，避免把大块数据序列化进普通 Binder Parcel。控制面与数据面因此可能采用不同通路。
3. **既有实现**：厂商常把既有 C/C++ 库、驱动接口与固件控制封装在 HAL 后面，迁移时需维持稳定接口和设备兼容性。
4. **接口演进**：早期 legacy HAL 常以共享库形式加载。Treble 引入 binderized HAL，存量 HIDL 使用 hwbinder。Stable AIDL HAL 使用普通 Binder 并通过 VINTF 管理接口稳定性。直通 HAL 与新旧版本并存，不能据此断言所有 HAL 都在独立进程。

JNI 用于同一进程地址空间内的 Java/native 互调。进程隔离时必须通过 IPC，Android HAL 场景常见 Binder。Treble 推动框架与 vendor 之间使用受版本约束的接口，以减少升级耦合并形成故障与权限边界，但实际隔离程度仍取决于 HAL 类型和产品实现。

依据：AOSP《AIDL for HALs》与《AIDL backends》说明了 HAL 接口后端、稳定性和分区限制。具体设备的接口形式应以其 VINTF manifest 与实现为准。

**Q15: [learning] 框架服务之间所说的“原生 Binder”是什么，和 Java Binder、hwbinder 有何区别？**

指框架家族的原生服务守护进程（SurfaceFlinger、AudioFlinger、CameraService、installd 等）互相调用、以及 system_server 内原生代码调用它们时，用的是 libbinder 的 C++ 接口——BBinder、BpBinder、C++ 版 Parcel 与 AIDL 的 C++/NDK 后端，编解码全程在 C++ 里完成，没有 JVM 参与。

地基：Binder 只有一套底座——内核一个驱动加用户态一个 libbinder，其上有两个语言门面：Java 门面（android.os.Binder、Java 版 Parcel、AIDL 的 Java 后端，本身经 JNI 包着 libbinder）和 native 门面（C++ API）。“原生 Binder”不是第二套 IPC，而是同一条管道的 C++ 门面。必须用它没有选择余地：原生服务进程里没有 ART，android.os.Binder 这个类在那些进程里不存在。

三个具体调用：

1. **图层事务**：system_server 中的 WMS 经 JNI 进入 libgui 的 SurfaceComposerClient，再用 C++ Binder 调用 surfaceflinger 进程的 ISurfaceComposer 接口。
2. **音频通路**：AudioTrack 的 native 半截与 audioserver 进程的 AudioFlinger 之间，经 IAudioFlinger、IAudioTrack 这些 C++ Binder 接口传控制命令与 PCM 数据。
3. **installd**：system_server 的原生部分经 IInstalld（AIDL 的 C++ 后端）跨进程调 installd 守护进程做 dexopt 与目录操作。

两个易混点：

1. **别与 hwbinder 混淆**：hwbinder 是 Treble 时代给 HIDL HAL 划的专用通道。框架原生服务之间走普通 Binder。
2. **跨语言调用是常态**：system_server 的 Java 服务调 cameraserver 的 C++ 服务，就是 Java 代理对 C++ 实现，同一个驱动承载——这恰好证明 Java 与 native 门面是同一套 IPC。另外 system_server 内部 Java 服务互调（如 AMS 调 PMS）虽是 Binder 语义，但两端同进程时走本地路径直接执行，不进内核。

**Q16: [learning] Android 平台组件主要用哪些语言实现，AIDL 和 HIDL 又是什么？**

Android 平台代码主要使用 Java、Kotlin、C、C++ 和 Rust。AIDL、HIDL 是接口描述语言，不是与它们并列的实现语言。选型取决于代码运行在 ART、native 进程还是内核，以及需要跨越什么接口边界。应用也可以随 APK 携带 Dart、JavaScript 等自己的运行时，这不改变平台组件所用的语言。

1. **Java 与 Kotlin**：Java 仍广泛用于 Framework 和 `system_server`。Kotlin 与 Java 可在 Android/JVM 工具链中互操作，常用于应用和部分平台模块。使用 Kotlin 不会替换 ART，也不意味着框架主体已整体迁移。
2. **C 与 C++**：用于 native 库、系统服务和大量 HAL 实现。Linux 内核传统上以 C 为主，近年也支持在受限范围内增量引入 Rust。不能把“内核代码”简单描述为只能写 C。AOSP 各模块对 C++ 异常和 RTTI 的构建约束需按模块配置判断。
3. **Rust**：用于适合其安全和系统编程特性的新增平台组件。借用检查能在编译期阻止一类内存错误，但 `unsafe` 和 FFI 边界仍需审查。Android 的采用重点是新代码，不等于承诺重写全部既有 C/C++。
4. **AIDL 与 HIDL**：描述 IPC 接口及数据契约，再由构建工具生成后端绑定。稳定 AIDL 支持 Java、C++、NDK 和 Rust 等后端，vendor 分区可用的后端受稳定性与 API 规则约束。存量 HIDL HAL 仍需按平台版本识别。

Google 在 2024 年公布的 Android 数据显示，内存安全漏洞占比从 2019 年的 76% 降至 2024 年的 24%。Google 在 Android 13 的资料称，当时约 21% 的新增 native 代码使用 Rust。它们是对应年份的平台统计，不代表漏洞全部来自 C/C++，也不能作为当前代码占比。Rust 可减少新引入的内存安全问题，但不能消除所有漏洞类别。

版本依据：Google Online Security Blog《Eliminating Memory Safety Vulnerabilities at the Source》（2024）和《Memory Safe Languages in Android 13》（2022）。AOSP《Stable AIDL》与《AIDL backends》。

**Q17: [learning] system_server 里有 C++ 代码吗？“system_server 的原生部分”指什么？**

有。system_server 不是纯 Java 进程，而是一个宿主：ART 运行时加几百个 Java 服务，再加 JNI 胶水、成建制的 C++ 库甚至完整的 C++ 服务。“原生部分”就指这些 C++ 代码。

地基是“进程不是语言单元”：同一个地址空间可以同时装两类代码——Java 部分由 ART 执行字节码、内存归 GC 管，C++ 部分是 .so 装载后由 CPU 直接执行。两部分共享线程与内存，靠 JNI 互调。system_server 是这一事实最集中的样本。

system_server 里的 C++ 分三类：

1. **JNI 胶水**：libandroid_runtime（Binder、Parcel、MessageQueue 的 native 实现）、libandroid_servers（各系统服务的 JNI 总库）。
2. **混合服务的 native 半边**：不少“Java 服务”只有决策逻辑在 Java，执行管道在 C++——窗口的 SurfaceControl 经 JNI 落到 libgui，音频的 AudioService 经 JNI 落到 libaudioclient，dexopt 落到 installd 的客户端封装。
3. **整建制 C++ 服务**：输入子系统的 InputReader 与 InputDispatcher 是纯 C++，跑在 system_server 自己的 native 线程上，Java 的 InputManagerService 只是壳。SensorService 也是完整的 C++ Binder 服务，经 JNI 在 system_server 内实例化，外部进程察觉不到它与 Java 服务同住。

为什么这么设计：输入分发延迟直接影响触控跟手度，InputReader 要用 epoll 直读内核 input 设备节点。贴内核的接口 Java 做不了。libgui、inputflinger 这些库本就是为多进程共享写的，直接装载即可。

代价与归属边界：

1. **同进程即同崩溃域**：C++ 部分崩溃照样带走整个 system_server、触发框架重启——InputDispatcher 崩溃等于框架崩溃。
2. **层级归属不变**：这些 C++ 仍是原生层的代码，不因住进 system_server 变成框架层——层与进程是多对多关系，system_server 同时承载应用框架层与原生层的代码。

**Q18: [learning] system_server 引入了哪些 so 库？如何拿到权威清单？**

没有跨版本固定的 system_server 原生库清单。库可能由 SystemServer 显式加载、服务类按需加载，或作为 ELF 依赖被动态链接器拉入，而且具体集合随版本和产品变化。因此设备进程的实际映射是运行时核对依据，源码清单只用于解释来源。

三种途径对应三类库：

1. **SystemServer 显式加载**：libandroid_servers（frameworks/base/services 的 JNI 总库，收输入、电源、灯光、闹钟、USB、Vibrator 等服务的 JNI 与 SensorService 的启动入口。AAOS 13 源码中其链接依赖含 libinputflinger、libinputservice、libaudioclient、libpowermanager、libhardware、libhidlbase、libbinder_ndk 等）。另有触发式加载的 libfdtrack——FD 数量越过阈值才装载的文件描述符泄漏追踪库。
2. **框架必用、自 Zygote 继承的 JNI**：libandroid_runtime（android.os.Binder、Parcel、MessageQueue 的 native 实现）、libhwui（渲染管线，SurfaceControl 与 Surface 的 JNI 也在其中）、libmedia_jni（音频与媒体的框架绑定）、Wi-Fi 栈的 libwifi-service。
3. **依赖拉入的实现库**：libbinder 与基础设施（libcutils、libutils、liblog、libbase）、libgui（SurfaceComposerClient，经 libhwui 的依赖进入）、libaudioclient、libhardware（hw_get_module 加载旧式直通 HAL 的入口）、libhidlbase（存量 HIDL，逐步退场）、libEGL 与 libGLESv2（system_server 自绘界面时生效）、Bionic（libc、libm、libdl）与 C++ 运行时。

权威清单的取法：

```bash
adb shell su -c 'cat /proc/$(pidof system_server)/maps' | grep '\.so' | awk '{print $6}' | sort -u
```

命令各段的作用如下：

1. `adb shell` 在设备端执行后续命令。
2. `su -c` 请求以设备允许的 root 权限执行引号内命令。若设备未授权 root，读取其他进程的 maps 会失败。userdebug、eng 或车机开发版本是否允许仍由设备配置决定。
3. `pidof system_server` 查询当前 `system_server` PID，`$(...)` 将结果代入 `/proc/<pid>/maps`。进程不存在或 PID 查询失败时路径无效。
4. `cat` 输出该进程当前的内存映射，`grep '\\.so'` 保留路径含 `.so` 的行，`awk '{print $6}'` 取 maps 行中的路径字段，`sort -u` 排序并去重。

该命令是设备上的即时采样，不是完整、永久的依赖清单：按需加载库可能尚未出现，匿名映射也不会被 `.so` 过滤器列出。路径中包含空格时按固定字段取值也可能失准。源码侧可用 `frameworks/base/services/core/jni/` 查 libandroid_servers 的源文件，并全局搜索 `loadLibrary` 查显式加载点。需要解释某库为何出现时，应结合采样时机、进程映射和该设备源码追踪。

`libhwbinder` 自 Android 11 起并入 `libbinder`，新版本中通常看不到独立的同名库。Mainline 模块化会把部分能力移出 `system_server`，车机产品也可能增加 CarService 相关依赖，因此网上的库清单只能视为特定版本快照。核对设备时结合 maps 采样与对应源码。

**Q19: 只有 Linux 内核能构成 Android 或桌面 Linux 吗？**

不能。Linux 内核负责进程、内存、调度和驱动等底层资源管理，不包含启动后提供日常功能所需的完整用户空间。

1. **Android**：还需要 init、Bionic、系统服务、Android Runtime、Framework 和应用等用户空间组件。
2. **桌面 Linux**：还需要 shell、常用命令、运行库和桌面图形环境。
3. `ls` 通常是用户空间程序，`cd` 通常由 shell 内建。只保留内核不会自动提供这些命令或环境。
