# 渲染管线专题：出图分型与图形 API

> 学习资料（文章模式沉淀）。主线：按「谁生产 buffer、写入哪个 Surface、layer 如何组织、在哪里合成与 present」分型 Android 出图路径，覆盖标准 View、软件/离屏/混合、SurfaceView/TextureView、GLES/EGL/ANGLE、Vulkan 与 SurfaceControl/HardwareBufferRenderer 六类管线的机制与选型边界。源文档：android-internals-wiki §13.1《Android View 渲染管线与分析方法》、§13.2《Android 软件、离屏与混合渲染路径》、§13.3《SurfaceView 与 TextureView 渲染管线》、§13.4《OpenGL ES、EGL 与 ANGLE》、§13.5《Vulkan 原生管线与 HWUI 多队列》、§13.6《SurfaceControl 与 HardwareBufferRenderer》（材料 Android 17 语境）；机制按本地 AAOS13 源码（Android 13）核对，关键差异已标注版本——如 HWUI Vulkan 在 AAOS13 为单 graphics queue、HardwareBufferRenderer 与 SurfaceSyncGroup 为 API 34 新增、NDK setBufferWithRelease 为 API 36 新增（AAOS13 中均不存在），AAOS13 已核对的锚点包括 BLASTBufferQueue、DrawFrameTask、SurfaceView 挖洞、DeferredLayerUpdater、HWComposer presentOrValidate、libEGL swap 分发与 Vulkan WSI 钩子。VSync 调度、SF 合成与 BufferQueue 的机制层已由 [01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md) 与 [02-GPU合成与显示管线.md](../02-rendering/02-GPU合成与显示管线.md) 覆盖，本文专注渲染管线专题与选型视角，不与其重复出题。Q 序列即结构，供 atlas 同源直读。

**Q1: 分析一段渲染 trace 时，为什么应先固定 Producer、输出 Surface、layer 拓扑与合成位置四个坐标，而不是从最长的 trace slice 入手？**

因为同一帧可能由完全不同的组件生产、写入不同目标并在不同位置合成，只看最长 slice 无法确定它属于哪条路径，容易把无关工作当成根因。四个坐标各自要确认：Producer（HWUI RenderThread、GL/Vulkan 线程、Chromium、Flutter raster、Camera HAL、MediaCodec 或其他引擎）、输出目标（App Window 的 Surface、独立 Surface、SurfaceTexture、离屏 HardwareBuffer 或 sideband 流）、layer 拓扑（内容进宿主窗口还是形成独立 child layer）、合成与 present 位置（宿主采样、SurfaceFlinger 的 CLIENT 合成、HWC 的 DEVICE 合成或专用媒体路径）。

机制上，框架名与控件名只是 API 名称：同一个 Flutter 页面可以用 SurfaceView 或 TextureView 作宿主，同一个播放器也可能在普通 BufferQueue、独立 layer 与 tunneled 专用路径之间切换。线程名同样只是线索——trace 中出现 GLThread 不足以证明页面存在独立 layer，看到 SurfaceView 对象也不足以证明本帧被 HWC 作为 overlay 处理，必须用对象标识把线程、swapchain/BufferQueue、SurfaceControl 与目标 layer 对到同一条路径。做法：先用线程 slice、GPU submission、BufferQueue/SurfaceControl 与 layer dump 证据固定四坐标，再进入具体耗时归因。

**Q2: 判定一个页面属于「标准 HWUI 页面」还是混合或独立管线，应该核对哪四个条件？**

判定依据是主体内容的生产与提交路径，四个条件同时成立才是标准页面：主体 Producer 是 ViewRootImpl 加 HWUI RenderThread；输出目标是当前 App Window 的 Surface 与 BLAST BufferQueue；layer 拓扑中主体像素落在 App Window layer；帧节奏能由 Choreographer 的 doFrame 与窗口 SurfaceFrame 解释。

出现任一「切换信号」就应换对应管线分析：主体内容由 Chromium、Flutter raster、Camera HAL、MediaCodec 或游戏引擎主导；出现独立 Surface、SurfaceTexture 输入或另一条 swapchain；主体内容形成 child layer；引擎、解码器或硬件模块维护独立输出节奏。机制上「标准」只描述路径、不评价负载——常规列表页、详情页即使包含复杂布局、大量重组或高耗时 shader 仍是标准页面；页面嵌入视频、地图、相机预览或大型 WebView 后，要按实际占主体的 Producer 重新分类。做法：先分类再选方法，普通 UI 问题走标准 View 管线，独立内容走 Surface 与引擎专项。

**Q3: queueBuffer、BufferTX、latch、present fence 四个信号各证明什么，为什么其中任何一个都不能单独当作「画面已显示」的证据？**

queueBuffer 证明 Producer 把 slot、元数据和 production fence 交回了队列；BufferTX 证明 SurfaceFlinger 侧记录了一笔待处理的 buffer 更新；latch 证明 SF 本轮采纳了该 layer 的新 buffer；present fence 才是显示管线完成本轮 present 的同步反馈。四者依次覆盖「提交、到达 SF、被采纳、到达显示边界」，每一步之后都还可能失败或延迟。

机制上每个信号只回答一个阶段：queueBuffer 返回时 GPU 可能仍在写入；BufferTX 增加只说明 SF 记了账，事务还可能因 acquire fence、期望呈现时间或 readiness 检查被推迟，AAOS13 中 `BufferTX - <layerName>` 的计数在 buffer 被 latch 或 drop 后都会下降，仅凭下降不能区分两种结果；latch 之后合成还可能退到 CLIENT 或错过 present。做法：按同一帧（frame number、SurfaceFrame token 或明确的相邻时序）对齐四类证据，才能区分帧开始晚、Producer 完成晚、Consumer 等待、退回 GPU 合成与显示后段延迟。

**Q4: 标准 App Window 里 RenderThread queueBuffer 之后，buffer 是怎样变成 SurfaceFlinger 收到的事务的，为什么这条路径不复制整帧像素？**

标准 App Window 的 BLASTBufferQueue 位于应用进程：queueBuffer 之后触发 onFrameAvailable，acquireNextBufferLocked 取得 BufferItem，调用 Transaction 的 setBuffer 写入 buffer、acquire fence、frame number 与 release callback，需要同步的窗口事务可按 frame number 合并后 apply 提交给 SF（AAOS13 的 `frameworks/native/libs/gui/BLASTBufferQueue.cpp` 可核对这条链路）。SF 收到事务后还要经过 readiness 检查、状态合并与 latch，所以 App 侧 queueBuffer 与 SF 侧 BufferTX 是两个不同时间点。

不复制像素的原因是传递内容只有 slot 索引、buffer 句柄、元数据与同步对象，像素本体留在共享的 GraphicBuffer/dma-buf 内存中，不经 Binder 拷贝。版本边界：BLAST 自 Android 11 起进入平台主线，Android 12/13 的标准窗口均按此模型工作；更早的传统路径由 SF 直接消费 BufferQueue，两套模型不能混用分析。做法：排查「提交了却没显示」时，先确认 BufferItem 是否被 BLAST acquire、事务是否 apply 到 SF，再查 SF 侧 readiness 与 latch，而不是反复检查绘制本身。

**Q5: dequeueBuffer 长时间等待时，为什么不能直接得出「队列深度不够、应该加 buffer」的结论？**

等待的原因不止容量不足：可能没有满足约束的 FREE slot、达到 maxDequeued/maxAcquired 上限、候选 slot 的 release fence 尚未 signal、Consumer 消费偏慢，或 buffer 因尺寸与格式变化正在重分配。Producer 可以选择任何满足约束的 FREE slot，只有所有候选都被状态、上限或 fence 挡住时才会等待，所以「等上一帧那块 buffer」只是特例而非全部。

确认根因要把同队列的可用 slot、Consumer 持有量与上一轮 release 对上：fence 迟到查 Consumer 或 HWC 的归还节奏，触顶查配置与提交节奏，而不是先动队列深度。结果层面，加深队列确实降低 Producer 阻塞概率，但同时增加内存占用与 in-flight 帧数，可能抬高输入到显示延迟——用时延换吞吐并不总是划算；实时交互更关心旧帧是否堆积，离线处理才更关心 Producer 不被抖动打断。做法：只有证据指向容量不足、且业务接受时延代价时才调整 buffer 数量。

**Q6: 「软件渲染」和「离屏渲染」是同一维度的两种选择吗，常见路径如何按「生产方式 × 结果去向」分类？**

不是同一维度：软件渲染回答「谁生成像素」（CPU 还是 GPU），离屏渲染回答「像素先写到哪里」（可见 Surface 还是中间 buffer），两个维度正交、可以独立组合。常见路径分四组：CPU 直写当前窗口或独立 Surface buffer，入口是 drawSoftware 与 lockCanvas；CPU 先写 Bitmap 中间结果再进宿主窗口，入口是 LAYER_TYPE_SOFTWARE 与显式 Bitmap Canvas；HWUI/GPU 写离屏 render target 或 HardwareBuffer，入口是硬件 Canvas 的 saveLayer、RenderEffect 与 HardwareBufferRenderer；App GPU 写 HardwareBuffer 后经 Transaction 的 setBuffer 提交到独立 layer。

前两组是 CPU 软件栅格化，后两组是 GPU 离屏渲染，「软件绘制不等于没有 BufferQueue」——CPU 直写窗口仍走 dequeue/queue 与 fence。边界：中间结果若只交给编码器、算法或缓存，不会自然产生 SF latch、HWC present 与 display present fence。做法：诊断时按 Producer、输出位置、Consumer、最终可见 layer 的顺序确认，再决定沿显示链还是消费链分析。

**Q7: 关闭硬件加速后普通 View 树的一次绘制走什么链路，它与标准 HWUI 路径的本质差异是什么？**

整窗口软件绘制由 ViewRootImpl 的 drawSoftware 完成：锁住窗口 Surface 取得 software Canvas，调用 mView.draw(canvas) 由 CPU 栅格化，再 unlockCanvasAndPost 提交，随后仍经 BufferQueue、BLAST、SF 与 HWC 显示（AAOS13 的 `frameworks/base/core/java/android/view/ViewRootImpl.java` 可核对）。本质差异在像素生产者：没有 RenderNode 录制与 RenderThread/GPU 异步渲染，draw 在当前线程同步完成，UI 线程绘制慢时掉帧更直接。

边界：这条路径仍由 traversal 与 Choreographer 的 doFrame 驱动、受 VSync 调度，也仍受 BufferQueue slot 与 release fence 约束，所以「缺少 RenderThread slice」不能单独证明当前是软件路径；较强证据是窗口配置关闭硬件加速、调用栈进入 drawSoftware、lockCanvas/draw/unlockCanvasAndPost 出现在主线程 traversal 内，且同一窗口帧没有对应的 HWUI DrawFrame。做法：发现意外软件路径时，检查 Manifest 与 Activity 的 hardwareAccelerated、setLayerType 与实际 Canvas 类型，而不是直接优化绘制指令。

**Q8: Surface.lockCanvas 的 lock、draw、post 三段各有哪些成本，「软件渲染不携带 fence」为什么是错的？**

lock 段可能等待 FREE slot、等待返回 buffer 的 release fence，或因首次映射、page fault 与内存回收使 mmap 变慢；draw 段由 Skia CPU 后端按脏区面积、像素格式、混合、clip、路径与文字复杂度栅格化；post 段包含 gralloc unlock、构造 fence 与 queueBuffer。AAOS13 的 Surface::unlockAndPost 会调用 GraphicBuffer::unlockAsync 取得 fence fd 再 queueBuffer，因此 CPU 路径同样携带同步 fence，下游把它作为 acquire 边界后才安全读取。

三段成本来源不同，归因看线程状态：Sleeping 或 blocked 落在 dequeue 与 fence wait 上指向队列与 Consumer；Running 伴随 page fault、reclaim 或高内存流量指向映射与 copyback；lock 成功后 draw 长时间 Running 才归因 CPU 栅格化。unlockCanvasAndPost 变长也不能写成「CPU 画慢」，要检查 gralloc unlock、queueBuffer 与队列是否因 Consumer 偏慢而阻塞。边界：lockCanvas 没有跨设备有效的固定正常耗时，不能预设阈值。做法：分段测量并对齐同队列的 release fence 与 Consumer 节奏。

**Q9: software Canvas 的小脏区更新为什么可能反而变慢，AAOS13 的 copyback 在什么条件下执行？**

因为 lock 时系统要把上一块已提交 buffer 中需要保留的像素复制回当前 back buffer，小脏区节省的重画量可能小于这笔复制的内存流量。AAOS13 的 Surface::lock 会比较本轮 back buffer 与上一块 mPostedBuffer：尺寸与格式兼容时，计算旧有效区域与本轮新脏区的差集，通过 copyBlt 执行 copyback，再用最终脏区 lockAsync；无法 copyback 时把新脏区扩成整个 buffer 边界（AAOS13 的 `frameworks/native/libs/gui/Surface.cpp` 可核对）。

copyback 的前提是「上一帧内容仍有有效部分且两块 buffer 几何兼容」：resize、format 变化、Surface 重建、buffer discard 或大范围脏区都会削弱甚至消除收益。因此「只重画脏区」既可能减少 CPU 重画，也可能增加旧 buffer 到新 buffer 的内存复制。做法：判断 Dirty Rect 是否有效要同时测量 CPU draw 与 copyback 的内存流量，不能只看脏区面积变小。

**Q10: LAYER_TYPE_SOFTWARE、LAYER_TYPE_HARDWARE 与 Canvas.saveLayer 三者最容易被怎样混淆，「SF 里能看到某个 software View 的独立 layer」为什么是错的？**

三者分属不同入口：LAYER_TYPE_SOFTWARE 只作用于该 View 子树，把它先画成一张 CPU Bitmap（AAOS13 中 View.buildLayer 的 software 分支调用 buildDrawingCache(true)）；LAYER_TYPE_HARDWARE 在硬件加速下建 GPU 中间层，硬件加速关闭时按 software layer 行为处理；saveLayer 由当前 Canvas 类型决定是 CPU 离屏像素存储还是 GPU render target，不能只看 API 名。两个 isHardwareAccelerated 也要分开：Canvas 上的方法回答当前 Canvas 是否硬件加速，View 上的方法只说明所在窗口是否开启。

「SF 看到独立 software View layer」是错的：software layer 的 Bitmap 已在应用侧与其他 View 内容合并进宿主窗口 buffer，宿主硬件帧再对它采样，SurfaceFlinger 只能看到 App Window。该路径同时存在 CPU 栅格化与纹理上传、采样的 GPU 成本，缓存未失效时可复用，频繁 invalidate 或尺寸变化则重复生成。做法：把 LAYER_TYPE_SOFTWARE 当作兼容与滤镜手段而非通用性能开关；证据上用「CPU Bitmap 栅格化加宿主 HWUI 帧」的组合识别，不依赖固定 slice 名。

**Q11: SurfaceView 的「独立」具体独立在哪里，带来哪些收益，又有哪些不保证？**

独立的是主体像素的归属：主体内容由独立 Producer 写入自己的 Surface、BufferQueue 与 SF layer，不经过宿主窗口 buffer；宿主的主线程、RenderThread 与控制条等普通 View 仍照常工作。收益有三项：主体可采用与宿主不同的帧率；不经宿主 RenderThread 纹理采样，对视频、相机等大面积内容可减少 GPU 工作与带宽；SF 能把主体作为独立 layer 交给 HWC 单独评估，设备条件满足时使用 DEVICE composition。

不保证的部分同样明确：独立 layer 只是获得被 HWC 单独评估的机会，不保证 overlay plane、不保证 DEVICE 合成、不保证低功耗或低延迟——整屏条件复杂时反而可能进 CLIENT。主线程卡住时已有视频帧可能继续更新，但布局、裁剪、透明洞与控制条可能停在旧状态。做法：分析时分别回答「内容是否继续生产、宿主几何是否更新、本轮采用何种合成」，不能用组件名推断 overlay 或性能结论。

**Q12: Z-below 的 SurfaceView 为什么要在宿主窗口「挖洞」，mDrawFinished 置位后能证明什么、不能证明什么？**

默认 Z-below 时内容层位于宿主窗口下方，宿主若在同一矩形继续绘制不透明像素会盖住它，因此 HWUI 在宿主 buffer 对应区域保留透明度：gatherTransparentRegion 把 SurfaceView 可见矩形并入窗口透明区，draw 或 dispatchDraw 在 mDrawFinished 且未位于 parent 上方时调用 clearSurfaceViewPort，经 Canvas.punchHole 清出矩形、圆角及 alpha 对应的透明区域（AAOS13 的 `frameworks/base/core/java/android/view/SurfaceView.java` 可核对）。挖洞不创建黑色 View，也不复制视频像素，只是让 SF 按 layer 层级合成宿主与内容。

现代 SurfaceView 由三个 SurfaceControl 组成：mSurfaceControl 是不携带像素的 container，管理位置、裁剪与层级；mBlastSurfaceControl 是承载 Producer buffer 的内容层；mBackgroundControl 是下方纯色背景层——AAOS13 的 updateBackgroundVisibility 仅在内容层位于宿主下方、带 OPAQUE 标志且未被禁用时显示背景层。mDrawFinished 只证明 framework 认为 redraw 回调阶段结束，不证明 Producer 已提交首块 buffer，更不证明 latch 或 present。做法：container 的 position 更新不能证明 content child 用了新 buffer，排查错位时把几何事务与内容 buffer 放同一时间轴；Z-above 无须挖洞，但宿主普通 View 无法盖在内容上。

**Q13: surfaceDestroyed 返回后 Producer 还在写旧 Surface 会怎样，正确的停止协议是什么？**

surfaceDestroyed 返回后渲染线程不得继续访问该 Surface；如果 Producer 在工作线程或远端服务，销毁回调必须同步等到旧连接真正停止使用 Surface，只异步发一条 stop 消息就返回，会留下「回调顺序与生产线程实际状态不一致」的生命周期竞态，可能导致崩溃、黑屏或新 Surface 首帧异常。

前提是认清三个不同状态：Activity 可见、View 已 attach 与底层 Surface 有效是三回事，Producer 只能在 surfaceCreated 到 surfaceDestroyed 之间使用当前 Surface。停止动作因 Producer 而异：EGL/Vulkan 线程要停止对旧 native window 的 swap 与 present，MediaCodec 或 Camera 要撤销旧 output，但「等待到空闲」的责任在调用方。首帧延迟应按「ViewRoot 层就绪、SurfaceControl 与 BLAST 创建、callback、Producer connect、首块 buffer queue、layer 可见并 present」分段测量，不把整段归给 surfaceCreated。做法：在 surfaceDestroyed 中调用同步的 detach-and-wait 式接口，确认旧 Surface 不再被使用后再返回。

**Q14: TextureView 的一次可见更新要经过哪两套队列与哪些环节，「外部 Producer 已 queue，画面就该更新了」为什么不成立？**

TextureView 页面有两套 buffer 周转：外部 Producer 向 SurfaceTexture 的 BufferQueue 提交；应用进程内的 HWUI Consumer 取得最新输入作为纹理，由宿主 RenderThread 采样并生成包含它的 App Window buffer，再经 BLAST、SF 与 HWC 显示。消费链路是：新 buffer 触发 OnFrameAvailableListener，listener 投递到宿主 ViewRoot 所在线程（AAOS13 中绑定 mAttachInfo 的 handler），调用 updateLayer 与 invalidate；宿主 traversal 中 TextureView.draw 执行 applyUpdate，把 native updater 加入 RenderThread 的 pending layer updates；同步阶段由 DeferredLayerUpdater 的 apply 通过 ASurfaceTexture_dequeueBuffer 取得最新 AHardwareBuffer（AAOS13 的 `DeferredLayerUpdater.cpp` 与 `TextureView.java` 可核对）。

不成立的原因是该 dequeue 只保留最新一帧、丢弃尚未消费的旧帧（AAOS13 源码注释明确说明），所以外部 queueBuffer 只证明内容进入应用侧输入队列：回调可能晚于本轮 layer apply，宿主本帧继续使用旧输入，直到下一次 draw 才消费新内容。做法：把 frame-available、宿主 traversal、layer apply 与宿主 draw 四个时间点对齐，才能判断新输入是否赶上目标宿主帧。

**Q15: TextureView 的额外成本是什么形态，什么场景不合适，所有权上有哪些易错入口？**

额外成本是宿主 RenderThread 对输入纹理的 GPU 采样与第二次 buffer 生产——image import、fence 等待、过滤缩放旋转、颜色转换与宿主 render pass——通常不是 CPU 逐像素 memcpy，也不是固定增加一个刷新周期：回调足够早并赶上当前宿主帧时，额外等待可以小于一个周期；错过 layer apply 截止点则至少等下一次宿主 draw。选型上它适合需要普通 View 级旋转、裁剪、滤镜与父子透明的场景；不适合要求每一输入帧都被显示或处理的任务——最新帧选择会丢弃中间帧，逐帧消费应使用 ImageReader、MediaCodec buffer 模式等有明确 acquire 语义的接口。

边界：TextureView 只能在硬件加速窗口显示内容，外部 Producer 继续 queue 不能排除黑屏；SF 只看到最终宿主 layer，无法把 TextureView 矩形分离成独立 overlay。所有权易错点：onSurfaceTextureDestroyed 返回 true 由 TextureView release、返回 false 由调用方接管并负责释放；同一时刻只能连接一个 Producer；setSurfaceTexture 会立即 release 旧对象且不回调 onSurfaceTextureDestroyed，调用前必须先停止旧 Producer 并从 GL context detach。做法：需要 View 级混合选 TextureView，需要独立节奏、protected 内容或避免宿主采样选 SurfaceView，最终以目标设备数据验证。

**Q16: EGL 的 EGLDisplay、EGLContext、EGLSurface 各管什么，「应用在用 GLES」能推断它写到哪个 Surface 吗？**

不能。EGLDisplay 是连接 EGL 实现的句柄，名称中的 Display 不代表某块物理屏幕；EGLContext 保存 GLES 状态与资源命名空间，同一时刻只能 current 到符合规则的线程与 surface 组合；EGLSurface 是 context 的 draw/read 目标，window surface 连接 ANativeWindow，pbuffer 等用于离屏。Android 的 Surface 经 JNI/NDK 转为 ANativeWindow，AOSP 实现把 dequeue/queue 转交给 BufferQueue Producer，EGL window surface 由此取得可写的 GraphicBuffer。

GLES 只描述内容的生产方式，不决定承载组件：同样的 GLES 可以写入 GLSurfaceView 继承自 SurfaceView 的独立 Surface、TextureView 的输入 Surface、pbuffer/FBO/HardwareBuffer 等离屏目标，各自进入不同的显示或消费路径。做法：用 trace 确认拓扑时同时回答「谁发出 GLES 工作」与「EGLSurface 连接到哪个 Consumer」；eglSwapBuffers 只能证明某个 EGL surface 执行了 swap，不能推断 SF 中存在独立 layer。

**Q17: AOSP 平台层在 eglSwapBuffers 里做哪些事，swap 耗时长能直接归因 GPU 慢吗？**

不能。AOSP libEGL 的平台入口只做验证与分发：验证 display 与 surface、处理 surface metadata 与 damage，再把调用交给选中的实现——native 厂商 EGL 或 ANGLE（AAOS13 的 `frameworks/native/opengl/libs/EGL/egl_platform_entries.cpp` 中 eglSwapBuffers 进入 eglSwapBuffersWithDamageKHRImpl，并按 useAngle 分发，可本地核对）；dequeue 时机、GPU flush/submit、swap interval 与 fence 生成都在下游驱动或 ANGLE 内。

因此 swap 的 wall time 可能包含：应用或驱动尚未提交完命令、驱动内部串行或 GPU queue 限制、没有可用 BufferQueue slot、返回旧 buffer 的 release fence 未满足、swap interval 或 pacing 等待、Swappy 与引擎的 pacing、Surface resize 与错误恢复、ANGLE 的状态处理。eglSwapBuffers 返回只说明当前 image 已按 EGL 与驱动规则交给 window system，不证明 GPU 写完、SF latch、HWC present 或面板扫描。做法：把线程状态、子 slice、BufferQueue、fence、GPU stage 与 pacing marker 放同一时间轴归因，再定位是哪一段拖长了 swap。

**Q18: GLSurfaceView 的两种 render mode 节奏由什么决定，GLThread 独立后哪些责任没有被隔离？**

RENDERMODE_CONTINUOUSLY 下 GLThread 在 Surface 与 context 就绪后持续 draw/swap，节奏受 swap interval、BufferQueue 背压、驱动、Swappy 或业务调度约束，默认不逐帧跟随 Choreographer；RENDERMODE_WHEN_DIRTY 下只在 requestRender 置位后绘制，漏发请求会停在旧帧，过晚请求会错过目标周期。

GLThread 只隔离了执行队列，三类责任没有被隔离：Surface 生命周期——SurfaceHolder 创建销毁、onPause/onResume 仍会让 GLThread 停止或重建；UI 状态——渲染回调读到的业务状态要靠主线程或显式同步交接，queueEvent 不解决时序；显示资源——EGL_CONTEXT_LOST 时要销毁重建 context 与 surface，Renderer 需在 onSurfaceCreated 重建 GL 资源。多线程共享 context 加载资源时，还要显式同步 GL 资源可见性，Java 线程的先后不能保证 GPU 资源已可用。做法：静态图表与事件驱动更新用 WHEN_DIRTY 并保证请求来源可靠；持续动画用 CONTINUOUSLY 时自建 pacing；跨线程状态一律经主线程或显式同步。

**Q19: ANGLE 在 Android 上如何分层，为什么不能把每个 GLES 调用一一映射成同名同数量的 Vulkan 命令？**

ANGLE 保持应用可见的 GLES/EGL 接口不变，向下分层：frontend 做 GLES 校验与状态跟踪，shader translator 把 GLSL ES 翻译为 SPIR-V，Vulkan backend 维护 dirty bits 与 pipeline 状态，再经 Android Vulkan loader 与厂商 Vulkan 驱动提交，最后仍走 BufferQueue、SF、HWC 的显示后半段。一个 GLES draw 不一定对应一个 Vulkan draw：frontend 用 dirty bits 把状态变化延迟到 draw、dispatch、clear 等真正执行的命令处同步；特殊 primitive（如 LINE_LOOP 需生成或复用索引数据）、deferred clear、format conversion 与 render-pass 切换都可能插入额外命令。

这解释了一个常见现象：某个看似轻量的 glDrawArrays 在 CPU trace 里很长，时间未必来自 draw 本身，可能包含此前积累的 texture、framebuffer、pipeline 或 shader 准备工作。shader 翻译发生在编译、link、cache 恢复或 pipeline 准备阶段，不会每次 glDraw 都从 GLSL 文本重来。边界：ANGLE 统一的是 frontend 实现，底层仍依赖厂商 Vulkan 编译器与驱动；经 ANGLE 编程时应用不能直接操作 Vulkan descriptor、render pass 或 queue，需要完整 Vulkan 控制应直接用原生 API。做法：按应用 GLES 调用、ANGLE 状态与翻译、Vulkan 驱动执行、显示后半段四段归因，用目标设备 A/B 数据下结论（AAOS13 的 libEGL 已具备 native/ANGLE 分发；翻译层细节以 Android 17 语境的 external/angle 为基线）。

**Q20: ANGLE 路径上 swap 阶段做哪些工作，为什么 vkQueuePresentKHR 返回仍不能当作上屏证据？**

ANGLE 的 eglSwapBuffers 进入 WindowSurfaceVk 的 swapImpl 后，可能执行：结束或提交当前 render pass、处理 present layout transition、等待或取得下一块 swapchain image、做 CPU throttle 或 swap interval pacing，最后经 queuePresent 提交 Vulkan present。vkQueuePresentKHR 返回只说明 present 请求处理到了 Vulkan WSI 规定的边界：屏幕扫描、SF 对该 buffer 的采纳与 HWC present 都在其后，所以返回值不能当作上屏证据；返回 OUT_OF_DATE 或 SUBOPTIMAL 还要求应用重建 swapchain。

对显示系统而言，上游用厂商 GLES、ANGLE Vulkan 还是原生 Vulkan，交给 SF 的输入形式（buffer、dataspace、几何、acquire fence）不变。边界：确认 backend 要把 GL/EGL 字符串、进程已加载库与 gpu.angle trace event 组合使用——单独出现 vkQueueSubmit 不能证明 ANGLE，因为原生 Vulkan 与 HWUI Vulkan 也会产生 Vulkan 工作。做法：把 ANGLE swap 内部、Vulkan submit、GPU 完成、SF latch 与 present fence 分段对齐，逐段找证据。

**Q21: Android 上 Vulkan 的 swapchain image 与 BufferQueue 是什么关系，acquire 与 present 如何与 ANativeWindow 的 fence 交接？**

VkSurfaceKHR 经 VK_KHR_android_surface 连到 ANativeWindow，swapchain image 仍映射到 Android 的 GraphicBuffer/BufferQueue，present 后照样经过 SF、HWC 与显示 present——Vulkan 改变的是应用侧的控制方式，不是显示后半段。AAOS13 的 `frameworks/native/vulkan/libvulkan/swapchain.cpp` 可核对两个集成钩子：acquire 时 ANativeWindow 的 dequeueBuffer 返回 buffer 与 dequeue fence fd，loader 把 fd 副本交给驱动私有入口 AcquireImageANDROID，由驱动把依赖连到应用提供的 semaphore 或 fence；present 时 QueueSignalReleaseImageANDROID 把 present waits 转成 producer completion fence，再由 CPU 调 queueBuffer，BufferQueue 消费侧把它作为 acquire fence。

应用不应手工 import dequeue 返回的这条 fd，否则与 loader 已建立的同步关系重复；queueBuffer 会接管 fence fd 所有权。两个 fence 方向相反：dequeue fence 表示旧 Consumer 何时用完这块 buffer、Producer 何时可写，producer completion fence 表示 Producer 何时写完、Consumer 何时可读。边界：vkQueuePresentKHR 返回时用户通常还没看到画面；AcquireImageANDROID 是 loader 与驱动之间的集成钩子，应用不会直接调用。做法：把 acquire、submit、present 的 CPU API、GPU 依赖与 Android queue 三条线分开对齐，不把 swapchain 当作绕过 Android 显示栈的通道。

**Q22: Vulkan 的 semaphore、fence、pipeline barrier 各解决什么问题，把 stage mask 一律放宽为 ALL_COMMANDS 会怎样？**

semaphore 连接 queue 级执行依赖（acquire、submit、present 之间）；fence 把某次 queue 工作的完成状态暴露给 CPU，用于安全复用该帧的 command 与 descriptor 资源；pipeline barrier 与 event 在 command stream 内定义执行顺序、内存可见性、image layout 与 queue-family ownership。三者不能互相替代：semaphore 只表达执行依赖、不做 layout transition，barrier 也不能替代 present 对 render-finished semaphore 的等待。

把 stage 或 access mask 一律写成 ALL_COMMANDS、频繁查询 queue idle、每个 pass 都用 host fence、或没有依赖也拆成多个 submit，都会压缩 GPU 可并行执行的空间，制造 GPU bubble 与额外 CPU 开销。swapchain image 的 layout 有常规序列：首次使用且明确丢弃旧内容时可从 UNDEFINED 转入颜色附件 layout，后续 acquire 通常从 PRESENT_SRC_KHR 转回渲染 layout；oldLayout 设 UNDEFINED 必须以旧内容可完全丢弃为前提，不能每帧照抄。做法：按「资源的生产者、最早需要它的 Consumer stage、真实读写 mask」收窄同步，合并无意义的 submit，再用 GPU trace 验证 bubble 是否减少。

**Q23: Android 的 Vulkan present mode 集合与桌面 Vulkan 有何不同，选 MAILBOX 就一定时延最低吗？**

不同。应用只能使用 vkGetPhysicalDeviceSurfacePresentModesKHR 为当前 surface 实际枚举出的模式：FIFO 始终在集合中（Vulkan 规范强制）；MAILBOX 仅在 min_undequeued_buffers + 1 < max_buffer_count 时加入（AAOS13 的 swapchain.cpp 可核对）；Android 普通路径不会把 IMMEDIATE 与 FIFO_RELAXED 加入集合，因此桌面常见的选型建议不能直接移植。版本边界：Android 17 起部分设备可经枚举获得有条件支持的 FIFO_LATEST_READY，AAOS13 没有该模式。

MAILBOX 允许新 image 替换尚未显示的旧 image，但「可用 image 更多」不等于时延更低：若应用过早采样输入并持续积压工作，即使 presentation engine 丢弃旧帧，CPU 与 GPU 仍为这些帧付出成本。shared 系列模式（SHARED_DEMAND_REFRESH 与 SHARED_CONTINUOUS_REFRESH）使用共享 image，生命周期不同于普通 swapchain。做法：枚举后按业务目标选择，用 surface capabilities 定合法 image count，surface 重建后重新查询，并在目标设备测量 input-to-present、帧间隔与功耗，不预设 MAILBOX 最优。

**Q24: HWUI 的 Vulkan 后端用什么 queue 结构，Android 13 与 Android 17 的描述有何差异，queue 数量能推导硬件并行吗？**

这是版本敏感结论：材料描述的 Android 17 形态是同一 VkDevice 上两条 graphics queue——queue 0 服务 RenderThread 的窗口绘制，queue 1 服务 HardwareBitmapUploader 的 AHardwareBuffer 上传，且双 queue 自 Android 14 基线已存在；AAOS13 源码中 VulkanManager 只从 graphics queue family 取一条 mGraphicsQueue，RenderThread 与上传两个 Skia context 都绑定它，硬件位图上传与窗口绘制共用同一条提交队列。

无论单双 queue，queue 结构只说明「可独立提交的接口能力」，GPU 是否并行执行由驱动与硬件决定：两条 queue 仍可能争用内存带宽、cache 或同一图形引擎，驱动可以交错甚至串行执行。上传路径也是同步的——AAOS13 的 HardwareBitmapUploader 在 GrallocUploadThread 上执行 submit 并等 GPU 完成后才返回，主线程同步创建大图仍会依次等待解码、分配、上传与 GPU 完成；HARDWARE Bitmap 首次创建会把 CPU 源像素复制进 gralloc 分配的 AHardwareBuffer，不是 zero-copy。做法：分析上传与绘制的相互影响时，先按目标版本源码确认 queue 拓扑，再用 trace 观察争用，不把 queue 数量当成并行度保证。

**Q25: ASurfaceControl_createFromWindow 创建的是什么，它与 parent window 的 BufferQueue 是什么关系？**

它在指定节点下创建一个新 Layer：从 ANativeWindow 取得已有 SurfaceControl handle 作为父节点，新节点不复用原 window 的 BufferQueue，而是作为可被 transaction 修改的新子节点（AAOS13 头文件中该 API 与 setBuffer 同为 API 29 引入）。四类对象分工不同：ASurfaceControl 是 Layer 节点句柄，不提供绘制命令；ASurfaceTransaction 收集一批状态、apply 后异步提交；AHardwareBuffer 携带可跨 CPU、GPU、编解码器与合成器共享的图形内存，自身不含时序语义；fence fd 才描述读写顺序。

两个易错边界：NDK 创建的节点不能套用 Java SurfaceControl.Builder 的 layer 类型三分法，公开创建函数没有 layer type 选项；ASurfaceControl_release 只释放调用方的本地引用，只要父节点仍在显示树中，子树仍可能显示在屏幕上——移除子树要先 hide 或 reparent 到 NULL，再释放句柄。做法：直接向 SurfaceView 的 BufferQueue 渲染，用其 ANativeWindow 的 EGL/Vulkan/MediaCodec Producer 路径；只有要在宿主节点下组织额外 Layer 时才用 createFromWindow，两者是不同操作。

**Q26: ASurfaceTransaction 的原子性保证覆盖到哪里，OnCommit、OnComplete 与 API 36 的 OnBufferRelease 各能证明什么？**

原子性覆盖「transaction 中的状态一起生效」：SF 不会只应用其中一部分，同一线程调用 apply 的多个事务按提交顺序应用；但它不保证 apply 返回时 buffer 已 latch、事务赶上下一个 VSync、acquire fence 已 signal，更不会识别多个独立 Producer 的哪些 buffer 属于同一业务帧。

三类回调的边界递进：OnCommit（API 31）证明事务已应用、更新进入可展示状态，不能证明 buffer 释放或 present fence 可读，在该回调里查询 present fence 会失败；OnComplete（API 29）证明包含该事务的帧已展示，stats 中的 present 与 previous release fence fd 归调用方所有，需自行等待并关闭；API 36 的 OnBufferRelease 把回收通知直接绑定到本次提交的 buffer，收到非负 fd 仍要等 fence signal 后才能复用。默认关闭 backpressure 时，继续提交的新 buffer 可能覆盖尚未展示的旧 buffer，适合允许丢弃中间帧换低时延的场景；要保留帧序则开启 setEnableBackPressure(true)，代价是服务端可能暂存更多提交、增加排队与内存。做法：pacing 反馈用 OnCommit，buffer 回收按 API 等级选 OnBufferRelease 或 previous release fence，不在 OnCommit 里查 present fence。

**Q27: setBuffer 传入 acquire fence fd 后所有权归谁，API 29–35 与 API 36–37 的「buffer 可复用依据」为何不同？**

framework 接管传入的 acquire fence fd：调用后调用方不能再次关闭或复用同一 fd，需保留诊断时应先 dup；这一所有权转移与 AHardwareBuffer 的引用计数相互独立——持有对象引用不代表同步条件已满足。

可复用依据按 API 等级分两段：API 29–35 用 setBuffer，本次提交 buffer 的 release fence 要等未来某次事务替换它之后，从 OnComplete 的 getPreviousReleaseFenceFd 取得，且该 fd 描述的是「上一块」buffer，同一 AHardwareBuffer 被多次提交时要等所有引用释放；API 36–37 优先 setBufferWithRelease，OnBufferRelease 直接关联当前提交的 buffer（AAOS13 头文件中尚无该接口，属 API 36 新增，跨版本分析时按设备实际 API 等级区分）。两类 fence 方向相反：acquire fence 约束 Consumer 何时可读，release fence 约束 Producer 何时可再写，apply 返回后立即复写 buffer 是撕裂与同步错误的常见来源。usage 与 fence 也不能互换：usage 声明哪些模块可访问内存（直接 setBuffer 要求 COMPOSER_OVERLAY 与 GPU_SAMPLED_IMAGE，AAOS13 的 SurfaceControl javadoc 同样如此要求），fence 只负责访问顺序。做法：提交点记录 buffer id 与 generation，把回收绑定到对应回调与 fence，signal 后再回池。

**Q28: HardwareBufferRenderer 适合什么问题，它与 lockHardwareCanvas 的边界在哪，AAOS13 上能用吗？**

HardwareBufferRenderer（HBR）把一棵 RenderNode 场景树光栅化到调用方拥有的 HardwareBuffer，调用方自行决定该 buffer 交给 SurfaceControl、其他进程、GPU 或媒体 consumer，并管理 presentation 与 release fence 及 buffer 池。它是 API 34（Android 14）新增的 Java API，AAOS13（Android 13）没有这个类——需要类似能力时用 EGL/Vulkan 写 HardwareBuffer，或使用 AndroidX 的兼容封装。

与 lockHardwareCanvas 的边界在目标与所有权：lockHardwareCanvas 已经用 HWUI/GPU（AAOS13 的 Surface.java 中由 RenderNode 加 HardwareRenderer 组成微型管线），但输出目标仍是 Surface 背后的 BufferQueue，且每次必须完整覆盖；HBR 的旧内容保留、是否 clear 由调用方决定，draw 完成回调返回的 RenderResult 携带 presentation fence，必须先检查 status 再由 consumer 等待，不能把 callback 到达当成 GPU 完成；release fence 由后续事务的 setBuffer release callback 提供，两者方向相反、不可互换。成本上 HBR 与普通 UI 共享应用 RenderThread 与 GPU context，首次创建有 GPU context 初始化成本，离屏任务过重会拖累 View 动画；renderer 的 close 不会关闭构造传入的 HardwareBuffer，两者要分别释放。做法：CPU 软件光栅化已是主要耗时、结果本要作为独立 HardwareBuffer 消费、或已有合法 SurfaceControl layer 需要原子提交时实测 HBR；目标只是画到现成 Surface 时，优先 lockHardwareCanvas 或面向 Surface 的 HardwareRenderer。
