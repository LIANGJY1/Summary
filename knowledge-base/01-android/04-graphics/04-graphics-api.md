# 图形 API：EGL、Vulkan 与 NDK 出图接口

> 学习资料（文章模式沉淀）。边界：本文回答"EGL/Vulkan/NDK（ASurfaceControl、HardwareBufferRenderer、WebGPU）各对象与调用语义、Android 平台在其间的行为"；渲染管线与 VSync 调度归 [01-render-pipeline-vsync.md](./01-render-pipeline-vsync.md)，GPU 合成归 [02-gpu-composition-display.md](./02-gpu-composition-display.md)，测量归因归 12-performance。证据：AAOS13 源码核对与官方文档口径，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] EGL 的 EGLDisplay、EGLContext、EGLSurface 各管什么，「应用在用 GLES」能推断它写到哪个 Surface 吗？**

不能。EGL 对象分工如下：

1. `EGLDisplay` 是连接 EGL 实现的句柄，名称中的 Display 不代表物理屏幕。
2. `EGLContext` 保存 GLES 状态和资源命名空间。同一时刻只能按 EGL 规则 current 到线程与 surface 组合。
3. `EGLSurface` 是 context 的 draw/read 目标。Window surface 连接 `ANativeWindow`，pbuffer 等可用于离屏渲染。
4. Android 的 `Surface` 经 JNI/NDK 转成 `ANativeWindow`。AOSP 实现把 dequeue/queue 操作交给 BufferQueue Producer，EGL window surface 因而取得可写的 `GraphicBuffer`。

GLES 只说明内容如何生产，不说明内容由哪个组件承载。同一 GLES 内容可以写入 `GLSurfaceView` 从 `SurfaceView` 继承的独立 Surface、`TextureView` 的输入 Surface，或 pbuffer/FBO/HardwareBuffer 等离屏目标。这些目标进入不同的显示或消费路径。用 trace 确认拓扑时，要同时确认谁发出 GLES 工作以及 EGLSurface 连接到哪个 consumer。`eglSwapBuffers` 只能证明某个 EGL surface 执行了 swap，不能据此推断 SurfaceFlinger 中存在独立 layer。

**Q2: [learning] AOSP 平台层在 eglSwapBuffers 里做哪些事，swap 耗时长能直接归因 GPU 慢吗？**

不能。Android 13 AOSP libEGL 的平台入口负责参数验证与调用分发：检查 display/surface，处理 surface metadata 和 damage，再把调用交给 native 厂商 EGL 或 ANGLE。dequeue 时机、GPU flush/submit、swap interval 和 fence 生成由下游驱动或 ANGLE 处理。

swap 的 wall time 可能包含以下等待或工作：

1. 应用或驱动尚未提交完命令，或驱动内部串行/GPU queue 受限。
2. BufferQueue 没有可用 slot，或旧 buffer 的 release fence 尚未满足。
3. swap interval、Swappy 或引擎 pacing 等待。
4. Surface resize、错误恢复或 ANGLE 状态处理。

`eglSwapBuffers` 返回只说明当前 image 已按 EGL 和驱动规则交给 window system，不证明 GPU 已写完、SurfaceFlinger 已 latch、HWC 已 present 或面板已扫描。排查时应把线程状态、子 slice、BufferQueue、fence、GPU stage 和 pacing marker 放在同一时间轴，确定是哪一段拖长了 swap。

**Q3: [learning] ANGLE 在 Android 上如何分层，为什么不能把每个 GLES 调用一一映射成同名同数量的 Vulkan 命令？**

ANGLE 保持应用可见的 GLES/EGL 接口不变，再把调用转换到 Vulkan。其主要阶段是：

1. Frontend 执行 GLES 校验并跟踪状态。
2. Shader translator 把 GLSL ES 翻译为 SPIR-V。
3. Vulkan backend 根据 dirty bits 管理 pipeline 状态，并经 Android Vulkan loader 和厂商 Vulkan 驱动提交。
4. 输出仍进入 BufferQueue、SurfaceFlinger、HWC 等 Android 显示路径。

一个 GLES draw 不一定一一对应一个 Vulkan draw。Frontend 可把状态变化延迟到 draw、dispatch、clear 等实际执行命令时再同步。`LINE_LOOP` 等特殊 primitive 可能要生成或复用索引数据，deferred clear、格式转换和 render-pass 切换也可能插入额外命令。因此，看似轻量的 `glDrawArrays` 在 CPU trace 中很长，时间可能包含此前积累的 texture、framebuffer、pipeline 或 shader 准备工作。Shader 翻译发生在编译、link、cache 恢复或 pipeline 准备阶段，不会每次 `glDraw` 都从 GLSL 文本重新开始。

ANGLE 统一的是 frontend 实现，底层仍依赖厂商 Vulkan 编译器和驱动。应用不能通过 ANGLE 直接操作 Vulkan descriptor、render pass 或 queue。需要完整 Vulkan 控制时应直接使用原生 API。归因时按应用 GLES 调用、ANGLE 状态/翻译、Vulkan 驱动执行和显示后半段分层，并用目标设备 A/B 数据验证。Android 13 的 libEGL 已具备 native/ANGLE 分发。翻译层细节以 Android 17 的 external/angle 为基线。

**Q4: [learning] ANGLE 路径上 swap 阶段做哪些工作，为什么 vkQueuePresentKHR 返回仍不能当作上屏证据？**

ANGLE 的 `eglSwapBuffers` 进入 `WindowSurfaceVk::swapImpl` 后可能执行以下工作：

1. 结束或提交当前 render pass，并处理 present layout transition。
2. 等待或取得下一块 swapchain image。
3. 执行 CPU throttle 或 swap interval pacing。
4. 经 `queuePresent` 提交 Vulkan present。

`vkQueuePresentKHR` 返回只说明 present 请求到达 Vulkan WSI 规定的边界，不代表屏幕扫描、SurfaceFlinger 采纳 buffer 或 HWC present 已完成。返回 `OUT_OF_DATE` 或 `SUBOPTIMAL` 时还要按状态重建 swapchain。

无论上游使用厂商 GLES、ANGLE Vulkan 还是原生 Vulkan，显示系统收到的仍是 buffer、dataspace、几何和 acquire fence 等信息。确认 backend 时要组合检查 GL/EGL 字符串、进程已加载库和 `gpu.angle` trace event。单独出现 `vkQueueSubmit` 不能证明使用 ANGLE，因为原生 Vulkan 和 HWUI Vulkan 也会产生 Vulkan 工作。应把 ANGLE swap、Vulkan submit、GPU 完成、SurfaceFlinger latch 与 present fence 分段对齐。

**Q5: [learning] Android 上 Vulkan 的 swapchain image 与 BufferQueue 是什么关系，acquire 与 present 如何与 ANativeWindow 的 fence 交接？**

`VkSurfaceKHR` 通过 `VK_KHR_android_surface` 连接 `ANativeWindow`。Swapchain image 仍对应 Android 的 GraphicBuffer/BufferQueue，present 后也仍经过 SurfaceFlinger、HWC 和 display present。Vulkan 改变的是应用侧控制方式，不是显示后半段。

Android 13 的 `frameworks/native/vulkan/libvulkan/swapchain.cpp` 使用以下集成钩子：

1. Acquire 时，`ANativeWindow::dequeueBuffer()` 返回 buffer 和 dequeue fence fd。Loader 将 fd 副本交给驱动私有入口 `AcquireImageANDROID`，由驱动把依赖连到应用提供的 semaphore 或 fence。
2. Present 时，`QueueSignalReleaseImageANDROID` 把 present waits 转成 producer completion fence，再由 CPU 调用 `queueBuffer()`。BufferQueue consumer 侧把它作为 acquire fence。

应用不应手工 import dequeue 返回的 fd，否则会重复建立 loader 已配置的同步关系。`queueBuffer()` 会接管传入 fence fd 的所有权。两类 fence 方向相反：dequeue fence 表示旧 consumer 何时用完 buffer、producer 何时可写。producer completion fence 表示 producer 何时写完、consumer 何时可读。`vkQueuePresentKHR` 返回时用户通常还没看到画面。`AcquireImageANDROID` 是 loader 与驱动间的集成钩子，应用不会直接调用。应把 acquire、submit、present 的 CPU API、GPU 依赖和 Android queue 分开对齐，不能把 swapchain 当成绕过 Android 显示栈的通道。

**Q6: [learning] Vulkan 的 semaphore、fence、pipeline barrier 各解决什么问题，把 stage mask 一律放宽为 ALL_COMMANDS 会怎样？**

三类同步原语解决不同层次的问题：

1. Semaphore 表达 queue 间或 queue 操作间的执行依赖，例如 acquire、submit 和 present 的衔接。
2. Fence 把 queue 工作完成状态暴露给 CPU，可用于确认本帧 command 和 descriptor 资源何时能安全复用。
3. Pipeline barrier 和 event 在 command stream 中定义执行顺序、内存可见性、image layout 与 queue-family ownership。

Semaphore 只表达执行依赖，不执行 layout transition。barrier 也不能替代 present 对 render-finished semaphore 的等待。把 stage/access mask 一律设为 `ALL_COMMANDS`、频繁查询 queue idle、每个 pass 都用 host fence，或在没有依赖时拆分 submit，都会压缩 GPU 并行空间并增加 GPU bubble 和 CPU 开销。

Swapchain image 的常见 layout 序列是首次使用且明确丢弃旧内容时从 `UNDEFINED` 转到颜色附件 layout，后续 acquire 时通常从 `PRESENT_SRC_KHR` 转回渲染 layout。只有旧内容可完全丢弃时才能将 `oldLayout` 设为 `UNDEFINED`，不能每帧照抄。应根据资源生产者、最早需要资源的 consumer stage 和真实读写 mask 收窄同步，合并无意义的 submit，再用 GPU trace 验证 bubble 是否减少。

**Q7: [learning] Android 的 Vulkan present mode 集合与桌面 Vulkan 有何不同，选 MAILBOX 就一定时延最低吗？**

不同。应用必须使用 `vkGetPhysicalDeviceSurfacePresentModesKHR` 为当前 surface 实际枚举出的模式：

1. FIFO 始终在集合中，这是 Vulkan 规范要求。
2. Android 13 仅在 `min_undequeued_buffers + 1 < max_buffer_count` 时加入 MAILBOX。
3. Android 普通路径不会把 IMMEDIATE 和 FIFO_RELAXED 加入集合，因此不能直接套用桌面 Vulkan 的选型建议。
4. Android 17 起，部分设备可经枚举获得有条件支持的 FIFO_LATEST_READY。Android 13 没有该模式。
5. SHARED_DEMAND_REFRESH 与 SHARED_CONTINUOUS_REFRESH 使用共享 image，生命周期不同于普通 swapchain。

MAILBOX 允许新 image 替换尚未显示的旧 image，但可用 image 更多不等于时延更低。若应用过早采样输入并持续积压工作，即使 presentation engine 丢弃旧帧，CPU 与 GPU 仍为这些帧付出成本。枚举模式后应按业务目标选择，用 surface capabilities 确定合法 image count。surface 重建后重新查询，并在目标设备测量 input-to-present、帧间隔和功耗，不要预设 MAILBOX 最优。

**Q8: [learning] HWUI 的 Vulkan 后端用什么 queue 结构，Android 13 与 Android 17 的描述有何差异，queue 数量能推导硬件并行吗？**

这是版本敏感结论：

1. Android 13 的 `VulkanManager` 只从 graphics queue family 取得一条 `mGraphicsQueue`。RenderThread 和上传用的 Skia context 共用该提交队列。AAOS13 的 `HardwareBitmapUploader` 在 GrallocUploadThread 上提交任务，并等待 GPU 完成后返回。
2. Android 17 形态是在同一 VkDevice 上取两条 graphics queue：queue 0 服务 RenderThread 窗口绘制，queue 1 服务 HardwareBitmapUploader 的 AHardwareBuffer 上传。双 queue 从 Android 14 基线开始存在。

queue 数量只表示接口可独立提交，不保证硬件并行执行。多条 queue 仍可能争用内存带宽、cache 或同一图形引擎，驱动也可能交错或串行执行。AAOS13 主线程同步创建大图仍会依次等待解码、分配、上传和 GPU 完成。HARDWARE Bitmap 首次创建会把 CPU 源像素复制到 gralloc 分配的 AHardwareBuffer，不是 zero-copy。分析上传与绘制的相互影响时，先按目标版本源码确认 queue 拓扑，再用 trace 观察争用，不把 queue 数量当作并行度保证。

**Q9: [learning] ASurfaceControl_createFromWindow 创建的是什么，它与 parent window 的 BufferQueue 是什么关系？**

`ASurfaceControl_createFromWindow()` 在指定节点下创建新 layer。它从 `ANativeWindow` 取得已有 SurfaceControl handle 作为父节点。新节点不复用原 window 的 BufferQueue，而是可通过 transaction 修改的子节点。Android 13 头文件中该 API 和 `setBuffer()` 均为 API 29 引入。

相关对象职责如下：

1. `ASurfaceControl` 是 layer 节点句柄，不提供绘制命令。
2. `ASurfaceTransaction` 收集一批状态，调用 `apply()` 后异步提交。
3. `AHardwareBuffer` 承载可供 CPU、GPU、编解码器和合成器共享的图形内存，本身不表达时序语义。
4. fence fd 表达 buffer 读写顺序。

NDK 创建的节点不能套用 Java `SurfaceControl.Builder` 的 layer 类型分类。公开创建函数没有 layer type 选项。`ASurfaceControl_release()` 只释放调用方的本地引用。只要父节点仍在显示树中，子树仍可能显示。移除子树时先 hide 或 reparent 到 NULL，再释放句柄。直接向 SurfaceView 的 BufferQueue 渲染时，使用其 ANativeWindow 的 EGL/Vulkan/MediaCodec producer 路径。只有要在宿主节点下组织额外 layer 时才用 `createFromWindow()`。

**Q10: [learning] ASurfaceTransaction 的原子性保证覆盖到哪里，OnCommit、OnComplete 与 API 36 的 OnBufferRelease 各能证明什么？**

事务原子性表示其中状态一起生效，SurfaceFlinger 不会只应用一部分。同一线程调用 `apply()` 的多个事务按提交顺序应用。但这不保证 `apply()` 返回时 buffer 已 latch、事务赶上下一个 VSync 或 acquire fence 已 signal，也不识别多个独立 producer 的 buffer 是否属于同一业务帧。

NDK 回调能证明的状态不同：

1. `ASurfaceTransaction_setOnCommit()` 从 API 31 提供 OnCommit：事务已应用且更新可供呈现。它不证明 buffer 已释放。stats 仅在回调期间有效，present fence 与 previous release fence 在此回调中不可查询。
2. `ASurfaceTransaction_setOnComplete()` 从 API 29 提供 OnComplete：包含更新的帧已呈现。回调 stats 中取得的 present fence fd 和 previous release fence fd 归调用方所有，调用方负责关闭。设备不支持 present fence 时会返回 `-1`。
3. API 36 起，`ASurfaceTransaction_setBufferWithRelease()` 可把 OnBufferRelease 回调绑定到特定 buffer。回调给出 `-1` 表示 buffer 已释放。非负 fd 表示仍需等待该 release fence signal。该回调可在任意线程触发。启用它后，不要再用 OnComplete 对同一 buffer 释放引用。

`ASurfaceControl_createFromWindow()` 创建的 layer 默认允许新提交覆盖尚未展示的旧 buffer。需要保留提交帧序时可调用 `ASurfaceTransaction_setEnableBackPressure(true)`，但服务端可能暂存更多提交，增加排队与内存。需要 pacing 反馈时用 OnCommit。buffer 回收则按 API 等级使用 OnBufferRelease 或 OnComplete 中的 previous release fence，不要在 OnCommit 中查询 fence。

**Q11: [learning] setBuffer 传入 acquire fence fd 后所有权归谁，API 29–35 与 API 36–37 的「buffer 可复用依据」为何不同？**

framework 接管 `setBuffer()` 传入的 acquire fence fd。调用后，调用方不能再次关闭或复用同一 fd。要保留诊断副本时应先 `dup`。fd 所有权与 AHardwareBuffer 引用计数相互独立，持有对象引用不代表同步条件已经满足。

可复用依据随 API 等级变化：

1. API 29–35 使用 `ASurfaceTransaction_setBuffer()`。待后续事务替换或移除该 surface 上的 buffer 后，可从 OnComplete 的 `ASurfaceTransactionStats_getPreviousReleaseFenceFd()` 取得上一块 buffer 的 release fence。该 fd 为 `-1` 时已释放。非负时仍须等待 signal。同一 AHardwareBuffer 被多次提交时，各次提交会形成独立引用，必须确保所有引用都释放后才能复用。
2. API 36 起可优先使用 `ASurfaceTransaction_setBufferWithRelease()`。OnBufferRelease 将回收通知绑定到对应提交的 buffer。回调中的非负 fd 由调用方接管并关闭，signal 前不能复写 buffer。`-1` 表示已经释放。Android 13 NDK 没有该接口，跨版本代码应按设备 API 等级使用可用回调。

两类 fence 方向相反：acquire fence 约束 consumer 何时可读，release fence 约束 producer 何时可再写。`ASurfaceTransaction_apply()` 返回后立即复写 buffer 是撕裂和同步错误的常见来源。usage 与 fence 也不能互换：usage 声明哪些模块可访问内存。NDK 的 `ASurfaceTransaction_setBuffer()` 要求 buffer 支持 `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`，因为 SurfaceControl 可能经 GPU 合成，Java `SurfaceControl.Transaction.setBuffer()` 的公开契约则要求 `COMPOSER_OVERLAY` 与 `GPU_SAMPLED_IMAGE`。提交时记录 buffer id 和 generation，并把回收绑定到对应回调与 fence，signal 后再放回 buffer pool。

**Q12: [learning] HardwareBufferRenderer 适合什么问题，它与 lockHardwareCanvas 的边界在哪，AAOS13 上能用吗？**

HardwareBufferRenderer（HBR）把 RenderNode 场景树光栅化到调用方持有的 HardwareBuffer。调用方决定将 buffer 交给 SurfaceControl、其他进程、GPU 或媒体 consumer，并管理 presentation、release fence 和 buffer pool。它是 API 34（Android 14）新增的 Java API，Android 13 没有该类。需要类似能力时可用 EGL/Vulkan 写入 HardwareBuffer，或使用 AndroidX 兼容封装。

与 `lockHardwareCanvas()` 的边界如下：

1. `lockHardwareCanvas()` 已经使用 HWUI/GPU。Android 13 的 `Surface.java` 中由 RenderNode 和 HardwareRenderer 组成微型管线，但输出目标仍是 Surface 背后的 BufferQueue，而且每次必须完整覆盖。
2. HBR 是否保留旧内容、是否 clear 由调用方决定。draw 完成回调返回的 RenderResult 携带 presentation fence。要先检查 status，再由 consumer 等待，不能把 callback 到达当成 GPU 完成。release fence 则由后续 transaction 的 setBuffer release callback 提供，两者方向相反且不能互换。
3. HBR 与普通 UI 共用应用 RenderThread 和 GPU context。首次创建有 GPU context 初始化成本，离屏任务过重会拖累 View 动画。调用 renderer 的 `close()` 不会关闭构造时传入的 HardwareBuffer，两者必须分别释放。

当 CPU 软件光栅化已是主要耗时、结果本来就要作为独立 HardwareBuffer 消费，或已有合法 SurfaceControl layer 需要原子提交时，可实测 HBR。目标只是画到现成 Surface 时，优先考虑 `lockHardwareCanvas()` 或面向 Surface 的 HardwareRenderer。

**Q13: [learning] Jetpack WebGPU 是 Android 17 的系统组件吗？它与 WebView 里的 navigator.gpu 是同一条路径吗？**

都不是。Jetpack WebGPU 是应用依赖 `androidx.webgpu`，不是 Android 17 系统组件。截至 2026 年 10 月，公开发行记录显示最新版本为 alpha05，最低 API 为 24。其 Kotlin API 通过 JNI 使用 AAR 内的 Dawn 原生库，随 APK 发布，不随系统 OTA 更新。

1. WebView 网页的 `navigator.gpu` 由 Chromium/WebView 运行时提供。Jetpack WebGPU 由应用中的 AndroidX library 提供。
2. 两条路径不共享 GPUInstance/GPUDevice、native handle、command buffer、pipeline cache 或 Surface 生命周期。一侧可用不证明另一侧可用。
3. 平台仍负责 Surface、ANativeWindow、BufferQueue、SurfaceFlinger 与 HWC。WebGPU 图形结果经 GPUSurface 进入公共显示路径，不绕过系统合成器。纯计算任务不需要 Surface，执行到 GPU 资源与结果读回即可。
4. Android 13 AOSP 树中没有 Jetpack WebGPU。Android 17 也不要求它必须使用 Vulkan 1.4。官方文档将 Vulkan 1.1+ 列为首选后端，Compatibility 档可覆盖 OpenGL ES 路径。

**Q14: [learning] WebGPU 的 Core/Compatibility 与 Vulkan/OpenGLES 是同一组分类吗？使用 subgroup 这类能力前要查什么？**

不是同一组。`featureLevel`（Core/Compatibility）描述应用可依赖的 WebGPU 能力档。`backendType`（Vulkan/OpenGLES 等）描述 Dawn 选择的原生后端，两者不能一一对应。Core 常配 Vulkan。为扩大设备覆盖也可请求 Compatibility 并由 OpenGLES backend 实现。实际 backend 应读取 `GPUAdapterInfo.backendType` 和 adapter info，不能按 GPU 商品名或 Android 大版本推断。

使用可选能力前，应按以下顺序查询并处理失败：

1. 对 subgroup、timestamp query、shader f16、压缩纹理等能力，先通过 adapter 的 features/`hasFeature()` 查询支持情况，再读 `getLimits()`。
2. 只把 workload 必需能力写入 `GPUDeviceDescriptor.requiredFeatures` 和 `requiredLimits`，并为 `requestDevice()` 失败准备降级路径。
3. 使用 subgroup 时还要读取 `subgroupMinSize` 与 `subgroupMaxSize`。

Compatibility 不能概括成“不支持 compute 或 storage texture”。它会补充少量 vertex/fragment stage 上限字段。`maxImmediateSize` 也不能直接解释为 Vulkan push constant 支持，底层寄存器映射取决于 Dawn 和驱动实现。WGSL 是稳定的 shader 输入口。中间表示和编译路径随 Dawn commit 与 backend 变化，不能描述成固定管线。

**Q15: [learning] WebGPU 的 surface.present() 返回表示上屏了吗？AndroidExternalSurface 与 AndroidEmbeddedExternalSurface 差在哪？**

没有。`present()` 只把当前 surface texture 交给后端呈现路径。其后仍有 GPU 完成、BufferQueue 交接、SurfaceFlinger latch、HWC 合成和 display present。

每次 `getCurrentTexture()` 都应检查 status：

1. `SuccessOptimal`：正常使用当前 texture。
2. `SuccessSuboptimal`：当前帧可用，但应准备重新配置 surface。
3. `Outdated`：按更新后的尺寸等状态重新配置。
4. `Lost`：重建与 Android Surface 关联的 GPUSurface。
5. `Timeout`：跳过本次获取并控制重试节奏。

两种 Compose surface 的承载路径不同：

1. `AndroidExternalSurface` 创建 SurfaceView，得到独立于宿主 App Window 的 Surface 和 layer。WebGPU 内容不进入宿主 RenderNode/display list，宿主 Compose 不能对它任意裁剪或变换。
2. `AndroidEmbeddedExternalSurface` 通过 SurfaceTexture/TextureView 把内容嵌入宿主窗口，因此会增加中间纹理和宿主采样。

生命周期回调 `onSurface`、`onChanged` 和 `onDestroyed` 在主线程触发。取得 Surface 后可切到专用渲染线程。收到销毁回调必须停止 acquire、encode、submit 和 present。独立 layer 的 WebGPU producer 未必带标准 App FrameTimeline token，因此缺少 expected slice 不代表内容没有显示。应按 layer 名、BufferQueue frame number 以及 latch/present 证据还原时序。
