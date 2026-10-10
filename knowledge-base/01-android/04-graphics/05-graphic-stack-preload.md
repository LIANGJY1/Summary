# 图形栈预加载与驱动选择

> 学习资料（文章模式沉淀）。边界：本文回答 Zygote 预加载覆盖图形栈的哪一段、GPU 驱动/ANGLE 由谁选择，以及首帧开销中预加载帮不上的部分。Zygote 与启动链见 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，应用侧首帧优化见 [应用启动优化](../12-performance/07-app-startup-optimization.md)。源文档：android-internals-wiki §1.3（Android 17 语境），官方资料已核对。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Zygote 预加载碰了图形栈的哪些部分？nativePreloadAppProcessHALs 预加载的是缓冲区分配器吗？**

不是。Android 17 的 `ZygoteInit.preload()` 中有两类相关预热：

1. `nativePreloadAppProcessHALs()` 调用 `GraphicBufferMapper::preloadHal()`，预热 gralloc mapper HAL。
2. `maybePreloadGraphicsDriver()` 预热 EGL 或 Vulkan 驱动。

mapper 负责 buffer 元数据查询、导入和映射，allocator 才负责创建/分配 buffer，因此 mapper 预热不等于 allocator 预热。

这种预热发生在 fork 前，使子进程可继承已加载的映射代码和部分初始化状态。它不创建应用所需的具体图形 buffer，也不执行 allocator 的分配请求。因此，首个 buffer 分配慢仍要分别检查 allocator、buffer usage 和驱动路径，不能只凭 mapper 已预热就认定分配器也已就绪。

**Q2: [learning] Android 17 的 mapper 预加载为什么跳过 Gralloc 2/3？什么情况下直接 FATAL？**

Android 17 的 `GraphicBufferMapper::preloadHal()` 按 `requireMapper4()` 裁剪：当设备 API level ≥ 36 且 `require_gralloc4_or_newer` 平台 flag 生效时，跳过 Gralloc 2/3，只预热 Gralloc 4/5。随后 mapper 选择也不再回退到 Gralloc 2/3。若 4/5 都不可用，构造 mapper 时会以 `LOG_ALWAYS_FATAL` 终止进程。

API level 和 flag 共同限定兼容性门槛，避免新设备继续依赖旧 mapper 接口。若必需 mapper 缺失，故障发生在请求构造 mapper 的进程中。是否表现为开机故障取决于哪个系统进程最先触发它，不能笼统说一定发生在开机阶段或与 app 无关。

**Q3: [learning] maybePreloadGraphicsDriver 把图形驱动预热到什么程度？ro.zygote.disable_gl_preload 关掉的是什么？**

Android 17 的 `zygote_preload_graphics()` 按 HWUI 后端做轻量调用：

1. SkiaGL 路径调用 `eglGetDisplay(EGL_DEFAULT_DISPLAY)`。
2. Vulkan 路径调用 `vkEnumerateInstanceVersion()`。

这两步都不创建 EGLContext、VkInstance 或 VkDevice。

`ro.zygote.disable_gl_preload` 为 `true` 时跳过这步图形驱动预热。省略时默认 `false`，即执行预热。该属性只控制图形驱动预热，不关闭 `nativePreloadAppProcessHALs()` 的 mapper HAL 预热。Zygote 预热因此只让一部分驱动加载成本可由 fork 子进程共享，应用上下文、设备对象、shader 和窗口 swapchain 等初始化仍属于应用/窗口阶段。

**Q4: [learning] app 实际用的 GPU 驱动由谁决定？GraphicsEnvironment 的选择链优先级是什么？**

由 app 进程侧 `GraphicsEnvironment.setup()` 配置。它先处理 ANGLE，再选择可更新生产/预发布驱动，随后通知图形环境。对符合更新驱动资格的应用，发生冲突时按以下顺序判定：

1. `UPDATABLE_DRIVER_ALL_APPS`：全局选择。
2. 生产驱动 opt-out：应用选择退出生产驱动。
3. 预发布驱动 opt-in：应用选择启用预发布驱动。
4. 生产驱动 opt-in：应用选择启用生产驱动。
5. 生产驱动 denylist：拒绝加载生产驱动。
6. 生产驱动 allowlist：允许加载生产驱动。

实际驱动包由 `ro.gfx.driver.0`（生产）和 `ro.gfx.driver.1`（预发布）等属性提供。

这是可更新驱动的选择顺序，不应与 ANGLE 自身的选择条件混成一条优先级链。Zygote 预热的是设备图形环境可见的驱动状态，而 app 最终可因包配置和平台设置切换到 ANGLE 或更新驱动。排查首帧时应先确认实际加载的 GLES/Vulkan 实现，再判断预热是否覆盖该实现。

**Q5: [learning] Android 17 应用在 manifest 中请求 ANGLE 时，哪些设备会接受该偏好？**

manifest 的 `com.android.graphics.driver.prefer_angle=true` 是应用级偏好，不会把 ANGLE 设成全设备默认驱动。Android 17 仅在设备不是 essential-tier、不是 low-RAM，且 `ro.vendor.api_level >= 202604` 时接受该偏好。

三个条件任一不满足，`GraphicsEnvironment` 会跳过 manifest 偏好并返回默认驱动选择。即使条件满足，设备设置、持久化 EGL 选择和 ANGLE 是否可用仍会影响最后实际加载的实现。ANGLE 将 OpenGL ES 调用转换为 Vulkan 调用，因此运行时应以系统报告的实际驱动为准，不能仅凭 manifest 声明认定 ANGLE 已加载。

**Q6: [learning] 首帧开销里哪些是 Zygote 预加载帮不上的？HardwareRenderer.preload() 能提前什么？**

Zygote 无法创建依赖具体应用窗口和上下文的 buffer、BufferQueue/BLAST 队列及 SurfaceFlinger 合成状态，也无法替应用完成 shader 编译。Android 17 的 `HardwareRenderer.preload()` 在应用进程中提前执行以下工作：

1. 启动 RenderThread。
2. 按当前 HWUI 后端预热 EGL 或 Vulkan context。
3. 初始化 HardwareBitmapUploader。

它把线程和渲染上下文的部分冷启动成本提前到 Activity 可见之前，但不建立具体窗口的 swapchain 或完成整帧绘制。

`HardwareRenderer.preload()` 是进程内的提前预热，和 Zygote 的跨进程共享预热作用不同。它可以减少首次创建 RenderThread/context 的临界路径工作，但不能代替具体窗口创建、buffer 分配、shader 首次编译或 SurfaceFlinger 合成。
