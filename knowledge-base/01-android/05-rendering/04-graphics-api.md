# 图形 API：EGL、Vulkan 与 NDK 出图接口

> 学习资料（文章模式沉淀）。边界：本文回答"EGL/Vulkan/NDK（ASurfaceControl、HardwareBufferRenderer、WebGPU）各对象与调用语义、Android 平台在其间的行为"；渲染管线与 VSync 调度归 [01-render-pipeline-vsync.md](01-render-pipeline-vsync.md)，GPU 合成归 [02-gpu-composition-display.md](02-gpu-composition-display.md)，测量归因归 15-performance。证据：AAOS13 源码核对与官方文档口径，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: EGL 的 EGLDisplay、EGLContext、EGLSurface 各管什么，「应用在用 GLES」能推断它写到哪个 Surface 吗？**

不能。EGLDisplay 是连接 EGL 实现的句柄，名称中的 Display 不代表某块物理屏幕；EGLContext 保存 GLES 状态与资源命名空间，同一时刻只能 current 到符合规则的线程与 surface 组合；EGLSurface 是 context 的 draw/read 目标，window surface 连接 ANativeWindow，pbuffer 等用于离屏。Android 的 Surface 经 JNI/NDK 转为 ANativeWindow，AOSP 实现把 dequeue/queue 转交给 BufferQueue Producer，EGL window surface 由此取得可写的 GraphicBuffer。

GLES 只描述内容的生产方式，不决定承载组件：同样的 GLES 可以写入 GLSurfaceView 继承自 SurfaceView 的独立 Surface、TextureView 的输入 Surface、pbuffer/FBO/HardwareBuffer 等离屏目标，各自进入不同的显示或消费路径。做法：用 trace 确认拓扑时同时回答「谁发出 GLES 工作」与「EGLSurface 连接到哪个 Consumer」；eglSwapBuffers 只能证明某个 EGL surface 执行了 swap，不能推断 SF 中存在独立 layer。

**Q2: AOSP 平台层在 eglSwapBuffers 里做哪些事，swap 耗时长能直接归因 GPU 慢吗？**

不能。AOSP libEGL 的平台入口只做验证与分发：验证 display 与 surface、处理 surface metadata 与 damage，再把调用交给选中的实现——native 厂商 EGL 或 ANGLE（AAOS13 的 `frameworks/native/opengl/libs/EGL/egl_platform_entries.cpp` 中 eglSwapBuffers 进入 eglSwapBuffersWithDamageKHRImpl，并按 useAngle 分发，可本地核对）；dequeue 时机、GPU flush/submit、swap interval 与 fence 生成都在下游驱动或 ANGLE 内。

因此 swap 的 wall time 可能包含：应用或驱动尚未提交完命令、驱动内部串行或 GPU queue 限制、没有可用 BufferQueue slot、返回旧 buffer 的 release fence 未满足、swap interval 或 pacing 等待、Swappy 与引擎的 pacing、Surface resize 与错误恢复、ANGLE 的状态处理。eglSwapBuffers 返回只说明当前 image 已按 EGL 与驱动规则交给 window system，不证明 GPU 写完、SF latch、HWC present 或面板扫描。做法：把线程状态、子 slice、BufferQueue、fence、GPU stage 与 pacing marker 放同一时间轴归因，再定位是哪一段拖长了 swap。

**Q3: ANGLE 在 Android 上如何分层，为什么不能把每个 GLES 调用一一映射成同名同数量的 Vulkan 命令？**

ANGLE 保持应用可见的 GLES/EGL 接口不变，向下分层：frontend 做 GLES 校验与状态跟踪，shader translator 把 GLSL ES 翻译为 SPIR-V，Vulkan backend 维护 dirty bits 与 pipeline 状态，再经 Android Vulkan loader 与厂商 Vulkan 驱动提交，最后仍走 BufferQueue、SF、HWC 的显示后半段。一个 GLES draw 不一定对应一个 Vulkan draw：frontend 用 dirty bits 把状态变化延迟到 draw、dispatch、clear 等真正执行的命令处同步；特殊 primitive（如 LINE_LOOP 需生成或复用索引数据）、deferred clear、format conversion 与 render-pass 切换都可能插入额外命令。

这解释了一个常见现象：某个看似轻量的 glDrawArrays 在 CPU trace 里很长，时间未必来自 draw 本身，可能包含此前积累的 texture、framebuffer、pipeline 或 shader 准备工作。shader 翻译发生在编译、link、cache 恢复或 pipeline 准备阶段，不会每次 glDraw 都从 GLSL 文本重来。边界：ANGLE 统一的是 frontend 实现，底层仍依赖厂商 Vulkan 编译器与驱动；经 ANGLE 编程时应用不能直接操作 Vulkan descriptor、render pass 或 queue，需要完整 Vulkan 控制应直接用原生 API。做法：按应用 GLES 调用、ANGLE 状态与翻译、Vulkan 驱动执行、显示后半段四段归因，用目标设备 A/B 数据下结论（AAOS13 的 libEGL 已具备 native/ANGLE 分发；翻译层细节以 Android 17 语境的 external/angle 为基线）。

**Q4: ANGLE 路径上 swap 阶段做哪些工作，为什么 vkQueuePresentKHR 返回仍不能当作上屏证据？**

ANGLE 的 eglSwapBuffers 进入 WindowSurfaceVk 的 swapImpl 后，可能执行：结束或提交当前 render pass、处理 present layout transition、等待或取得下一块 swapchain image、做 CPU throttle 或 swap interval pacing，最后经 queuePresent 提交 Vulkan present。vkQueuePresentKHR 返回只说明 present 请求处理到了 Vulkan WSI 规定的边界：屏幕扫描、SF 对该 buffer 的采纳与 HWC present 都在其后，所以返回值不能当作上屏证据；返回 OUT_OF_DATE 或 SUBOPTIMAL 还要求应用重建 swapchain。

对显示系统而言，上游用厂商 GLES、ANGLE Vulkan 还是原生 Vulkan，交给 SF 的输入形式（buffer、dataspace、几何、acquire fence）不变。边界：确认 backend 要把 GL/EGL 字符串、进程已加载库与 gpu.angle trace event 组合使用——单独出现 vkQueueSubmit 不能证明 ANGLE，因为原生 Vulkan 与 HWUI Vulkan 也会产生 Vulkan 工作。做法：把 ANGLE swap 内部、Vulkan submit、GPU 完成、SF latch 与 present fence 分段对齐，逐段找证据。

**Q5: Android 上 Vulkan 的 swapchain image 与 BufferQueue 是什么关系，acquire 与 present 如何与 ANativeWindow 的 fence 交接？**

VkSurfaceKHR 经 VK_KHR_android_surface 连到 ANativeWindow，swapchain image 仍映射到 Android 的 GraphicBuffer/BufferQueue，present 后照样经过 SF、HWC 与显示 present——Vulkan 改变的是应用侧的控制方式，不是显示后半段。AAOS13 的 `frameworks/native/vulkan/libvulkan/swapchain.cpp` 可核对两个集成钩子：acquire 时 ANativeWindow 的 dequeueBuffer 返回 buffer 与 dequeue fence fd，loader 把 fd 副本交给驱动私有入口 AcquireImageANDROID，由驱动把依赖连到应用提供的 semaphore 或 fence；present 时 QueueSignalReleaseImageANDROID 把 present waits 转成 producer completion fence，再由 CPU 调 queueBuffer，BufferQueue 消费侧把它作为 acquire fence。

应用不应手工 import dequeue 返回的这条 fd，否则与 loader 已建立的同步关系重复；queueBuffer 会接管 fence fd 所有权。两个 fence 方向相反：dequeue fence 表示旧 Consumer 何时用完这块 buffer、Producer 何时可写，producer completion fence 表示 Producer 何时写完、Consumer 何时可读。边界：vkQueuePresentKHR 返回时用户通常还没看到画面；AcquireImageANDROID 是 loader 与驱动之间的集成钩子，应用不会直接调用。做法：把 acquire、submit、present 的 CPU API、GPU 依赖与 Android queue 三条线分开对齐，不把 swapchain 当作绕过 Android 显示栈的通道。

**Q6: Vulkan 的 semaphore、fence、pipeline barrier 各解决什么问题，把 stage mask 一律放宽为 ALL_COMMANDS 会怎样？**

semaphore 连接 queue 级执行依赖（acquire、submit、present 之间）；fence 把某次 queue 工作的完成状态暴露给 CPU，用于安全复用该帧的 command 与 descriptor 资源；pipeline barrier 与 event 在 command stream 内定义执行顺序、内存可见性、image layout 与 queue-family ownership。三者不能互相替代：semaphore 只表达执行依赖、不做 layout transition，barrier 也不能替代 present 对 render-finished semaphore 的等待。

把 stage 或 access mask 一律写成 ALL_COMMANDS、频繁查询 queue idle、每个 pass 都用 host fence、或没有依赖也拆成多个 submit，都会压缩 GPU 可并行执行的空间，制造 GPU bubble 与额外 CPU 开销。swapchain image 的 layout 有常规序列：首次使用且明确丢弃旧内容时可从 UNDEFINED 转入颜色附件 layout，后续 acquire 通常从 PRESENT_SRC_KHR 转回渲染 layout；oldLayout 设 UNDEFINED 必须以旧内容可完全丢弃为前提，不能每帧照抄。做法：按「资源的生产者、最早需要它的 Consumer stage、真实读写 mask」收窄同步，合并无意义的 submit，再用 GPU trace 验证 bubble 是否减少。

**Q7: Android 的 Vulkan present mode 集合与桌面 Vulkan 有何不同，选 MAILBOX 就一定时延最低吗？**

不同。应用只能使用 vkGetPhysicalDeviceSurfacePresentModesKHR 为当前 surface 实际枚举出的模式：FIFO 始终在集合中（Vulkan 规范强制）；MAILBOX 仅在 min_undequeued_buffers + 1 < max_buffer_count 时加入（AAOS13 的 swapchain.cpp 可核对）；Android 普通路径不会把 IMMEDIATE 与 FIFO_RELAXED 加入集合，因此桌面常见的选型建议不能直接移植。版本边界：Android 17 起部分设备可经枚举获得有条件支持的 FIFO_LATEST_READY，AAOS13 没有该模式。

MAILBOX 允许新 image 替换尚未显示的旧 image，但「可用 image 更多」不等于时延更低：若应用过早采样输入并持续积压工作，即使 presentation engine 丢弃旧帧，CPU 与 GPU 仍为这些帧付出成本。shared 系列模式（SHARED_DEMAND_REFRESH 与 SHARED_CONTINUOUS_REFRESH）使用共享 image，生命周期不同于普通 swapchain。做法：枚举后按业务目标选择，用 surface capabilities 定合法 image count，surface 重建后重新查询，并在目标设备测量 input-to-present、帧间隔与功耗，不预设 MAILBOX 最优。

**Q8: HWUI 的 Vulkan 后端用什么 queue 结构，Android 13 与 Android 17 的描述有何差异，queue 数量能推导硬件并行吗？**

这是版本敏感结论：材料描述的 Android 17 形态是同一 VkDevice 上两条 graphics queue——queue 0 服务 RenderThread 的窗口绘制，queue 1 服务 HardwareBitmapUploader 的 AHardwareBuffer 上传，且双 queue 自 Android 14 基线已存在；AAOS13 源码中 VulkanManager 只从 graphics queue family 取一条 mGraphicsQueue，RenderThread 与上传两个 Skia context 都绑定它，硬件位图上传与窗口绘制共用同一条提交队列。

无论单双 queue，queue 结构只说明「可独立提交的接口能力」，GPU 是否并行执行由驱动与硬件决定：两条 queue 仍可能争用内存带宽、cache 或同一图形引擎，驱动可以交错甚至串行执行。上传路径也是同步的——AAOS13 的 HardwareBitmapUploader 在 GrallocUploadThread 上执行 submit 并等 GPU 完成后才返回，主线程同步创建大图仍会依次等待解码、分配、上传与 GPU 完成；HARDWARE Bitmap 首次创建会把 CPU 源像素复制进 gralloc 分配的 AHardwareBuffer，不是 zero-copy。做法：分析上传与绘制的相互影响时，先按目标版本源码确认 queue 拓扑，再用 trace 观察争用，不把 queue 数量当成并行度保证。

**Q9: ASurfaceControl_createFromWindow 创建的是什么，它与 parent window 的 BufferQueue 是什么关系？**

它在指定节点下创建一个新 Layer：从 ANativeWindow 取得已有 SurfaceControl handle 作为父节点，新节点不复用原 window 的 BufferQueue，而是作为可被 transaction 修改的新子节点（AAOS13 头文件中该 API 与 setBuffer 同为 API 29 引入）。四类对象分工不同：ASurfaceControl 是 Layer 节点句柄，不提供绘制命令；ASurfaceTransaction 收集一批状态、apply 后异步提交；AHardwareBuffer 携带可跨 CPU、GPU、编解码器与合成器共享的图形内存，自身不含时序语义；fence fd 才描述读写顺序。

两个易错边界：NDK 创建的节点不能套用 Java SurfaceControl.Builder 的 layer 类型三分法，公开创建函数没有 layer type 选项；ASurfaceControl_release 只释放调用方的本地引用，只要父节点仍在显示树中，子树仍可能显示在屏幕上——移除子树要先 hide 或 reparent 到 NULL，再释放句柄。做法：直接向 SurfaceView 的 BufferQueue 渲染，用其 ANativeWindow 的 EGL/Vulkan/MediaCodec Producer 路径；只有要在宿主节点下组织额外 Layer 时才用 createFromWindow，两者是不同操作。

**Q10: ASurfaceTransaction 的原子性保证覆盖到哪里，OnCommit、OnComplete 与 API 36 的 OnBufferRelease 各能证明什么？**

原子性覆盖「transaction 中的状态一起生效」：SF 不会只应用其中一部分，同一线程调用 apply 的多个事务按提交顺序应用；但它不保证 apply 返回时 buffer 已 latch、事务赶上下一个 VSync、acquire fence 已 signal，更不会识别多个独立 Producer 的哪些 buffer 属于同一业务帧。

三类回调的边界递进：OnCommit（API 31）证明事务已应用、更新进入可展示状态，不能证明 buffer 释放或 present fence 可读，在该回调里查询 present fence 会失败；OnComplete（API 29）证明包含该事务的帧已展示，stats 中的 present 与 previous release fence fd 归调用方所有，需自行等待并关闭；API 36 的 OnBufferRelease 把回收通知直接绑定到本次提交的 buffer，收到非负 fd 仍要等 fence signal 后才能复用。默认关闭 backpressure 时，继续提交的新 buffer 可能覆盖尚未展示的旧 buffer，适合允许丢弃中间帧换低时延的场景；要保留帧序则开启 setEnableBackPressure(true)，代价是服务端可能暂存更多提交、增加排队与内存。做法：pacing 反馈用 OnCommit，buffer 回收按 API 等级选 OnBufferRelease 或 previous release fence，不在 OnCommit 里查 present fence。

**Q11: setBuffer 传入 acquire fence fd 后所有权归谁，API 29–35 与 API 36–37 的「buffer 可复用依据」为何不同？**

framework 接管传入的 acquire fence fd：调用后调用方不能再次关闭或复用同一 fd，需保留诊断时应先 dup；这一所有权转移与 AHardwareBuffer 的引用计数相互独立——持有对象引用不代表同步条件已满足。

可复用依据按 API 等级分两段：API 29–35 用 setBuffer，本次提交 buffer 的 release fence 要等未来某次事务替换它之后，从 OnComplete 的 getPreviousReleaseFenceFd 取得，且该 fd 描述的是「上一块」buffer，同一 AHardwareBuffer 被多次提交时要等所有引用释放；API 36–37 优先 setBufferWithRelease，OnBufferRelease 直接关联当前提交的 buffer（AAOS13 头文件中尚无该接口，属 API 36 新增，跨版本分析时按设备实际 API 等级区分）。两类 fence 方向相反：acquire fence 约束 Consumer 何时可读，release fence 约束 Producer 何时可再写，apply 返回后立即复写 buffer 是撕裂与同步错误的常见来源。usage 与 fence 也不能互换：usage 声明哪些模块可访问内存（直接 setBuffer 要求 COMPOSER_OVERLAY 与 GPU_SAMPLED_IMAGE，AAOS13 的 SurfaceControl javadoc 同样如此要求），fence 只负责访问顺序。做法：提交点记录 buffer id 与 generation，把回收绑定到对应回调与 fence，signal 后再回池。

**Q12: HardwareBufferRenderer 适合什么问题，它与 lockHardwareCanvas 的边界在哪，AAOS13 上能用吗？**

HardwareBufferRenderer（HBR）把一棵 RenderNode 场景树光栅化到调用方拥有的 HardwareBuffer，调用方自行决定该 buffer 交给 SurfaceControl、其他进程、GPU 或媒体 consumer，并管理 presentation 与 release fence 及 buffer 池。它是 API 34（Android 14）新增的 Java API，AAOS13（Android 13）没有这个类——需要类似能力时用 EGL/Vulkan 写 HardwareBuffer，或使用 AndroidX 的兼容封装。

与 lockHardwareCanvas 的边界在目标与所有权：lockHardwareCanvas 已经用 HWUI/GPU（AAOS13 的 Surface.java 中由 RenderNode 加 HardwareRenderer 组成微型管线），但输出目标仍是 Surface 背后的 BufferQueue，且每次必须完整覆盖；HBR 的旧内容保留、是否 clear 由调用方决定，draw 完成回调返回的 RenderResult 携带 presentation fence，必须先检查 status 再由 consumer 等待，不能把 callback 到达当成 GPU 完成；release fence 由后续事务的 setBuffer release callback 提供，两者方向相反、不可互换。成本上 HBR 与普通 UI 共享应用 RenderThread 与 GPU context，首次创建有 GPU context 初始化成本，离屏任务过重会拖累 View 动画；renderer 的 close 不会关闭构造传入的 HardwareBuffer，两者要分别释放。做法：CPU 软件光栅化已是主要耗时、结果本要作为独立 HardwareBuffer 消费、或已有合法 SurfaceControl layer 需要原子提交时实测 HBR；目标只是画到现成 Surface 时，优先 lockHardwareCanvas 或面向 Surface 的 HardwareRenderer。

**Q13: Jetpack WebGPU 是 Android 17 的系统组件吗？它与 WebView 里的 navigator.gpu 是同一条路径吗？**

都不是。Jetpack WebGPU 是应用依赖（`androidx.webgpu`），Kotlin API 经 JNI 进入 AAR 内打包的 Dawn 原生实现，版本随 APK 发布、不随系统 OTA 更新——按材料口径最新公开版为 1.0.0-alpha05（alpha 状态），minSdk 24；WebView 网页的 `navigator.gpu` 由 Chromium/WebView 运行时提供，两条路径不共享 `GPUInstance`/`GPUDevice`、native handle、command buffer、pipeline 缓存与 Surface 生命周期，一侧可用不证明另一侧可用（外部框架，未本地核对）。平台仍负责 Surface、ANativeWindow、BufferQueue、SurfaceFlinger 与 HWC，WebGPU 渲染结果经 `GPUSurface` 进入公共显示路径，不绕过系统合成器；纯计算任务不需要 Surface，执行到 GPU 资源与结果读回即可。版本边界：AAOS13 树中没有 Jetpack WebGPU，该框架整体按材料口径转写；Android 17 也不要求它必须用 Vulkan 1.4，官方只把 Vulkan 1.1+ 列为首选后端，Compatibility 档可覆盖 OpenGLES 路径。

**Q14: WebGPU 的 Core/Compatibility 与 Vulkan/OpenGLES 是同一组分类吗？使用 subgroup 这类能力前要查什么？**

不是同一组。featureLevel（Core/Compatibility）描述应用可依赖的 WebGPU 能力档，backendType（Vulkan/OpenGLES 等）描述 Dawn 最终选择的原生后端，两者不能一一对应：Core 常配 Vulkan，但为扩大设备覆盖也可请求 Compatibility 并命中 OpenGLES，实际结果要读 `GPUAdapterInfo.backendType` 与 adapter info 确认，不能按 GPU 商品名或 Android 大版本推断（外部框架，未本地核对）。使用 subgroup、timestamp query、shader f16、压缩纹理等可选能力前要分层查询：先 `adapter.getFeatures()`/`hasFeature()`，再 `getLimits()`，只把 workload 必需能力写进 `GPUDeviceDescriptor` 的 requiredFeatures/requiredLimits，并处理 `requestDevice()` 失败的降级；subgroup 还要读 subgroupMinSize/subgroupMaxSize。Compatibility 不能概括成"不支持 compute 或 storage texture"，它只是补充少量 vertex/fragment stage 上限字段；`maxImmediateSize` 也不能改写成"Vulkan push constant 支持"，底层寄存器映射属 Dawn 与驱动实现细节。WGSL 是唯一稳定的 shader 输入口，中间表示与编译路径随 Dawn commit 与 backend 变化，不能写成固定管线。

**Q15: WebGPU 的 `surface.present()` 返回表示上屏了吗？`AndroidExternalSurface` 与 `AndroidEmbeddedExternalSurface` 差在哪？**

没有。`present()` 只把当前 surface texture 交给后端呈现路径，其后还有 GPU 完成、BufferQueue 交接、SurfaceFlinger latch、HWC 合成与 display present；每次 `getCurrentTexture()` 都要检查 status——`SuccessOptimal` 正常使用，`SuccessSuboptimal` 本帧可用但应准备重新配置，`Outdated` 更新尺寸后重配，`Lost` 重建与 Android Surface 关联的 GPUSurface，`Timeout` 跳过并控制重试节奏（外部框架，未本地核对）。承载差异：`AndroidExternalSurface` 创建 SurfaceView，得到独立于宿主 App Window 的 Surface 与 layer，WebGPU 内容不进宿主 RenderNode/display list，宿主 Compose 不能对它施加任意裁剪与变换；`AndroidEmbeddedExternalSurface` 走 SurfaceTexture/TextureView 把内容嵌回宿主窗口，增加一次中间纹理与宿主采样。生命周期回调（onSurface/onChanged/onDestroyed）在主线程触发，拿到 Surface 后可切专用渲染线程，收到销毁回调必须停止 acquire、encode、submit 与 present；独立 layer 的 WebGPU producer 未必携带标准 App FrameTimeline token，缺 expected slice 不代表内容没有显示，要按 layer 名、BufferQueue frame number 与 latch/present 证据还原时序。
