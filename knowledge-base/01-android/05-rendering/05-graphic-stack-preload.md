# 图形栈预加载与驱动选择

> 学习资料（文章模式沉淀）。边界：本文回答"Zygote 预加载覆盖图形栈的哪一段、GPU 驱动/ANGLE 由谁选择、首帧开销中预加载帮不上的部分"；Zygote 与启动链归 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，应用侧首帧优化见 [应用启动优化](../15-performance/11-app-startup-optimization.md)。源文档：android-internals-wiki §1.3（Android 17 语境），官方资料已核对。Q 序列即结构，供 atlas 同源直读。

**Q1: Zygote 预加载碰了图形栈的哪些部分？nativePreloadAppProcessHALs 预加载的是缓冲区分配器吗？**

不是。`ZygoteInit.preload()` 序列里与图形相关的两步是 `nativePreloadAppProcessHALs` 与 `maybePreloadGraphicsDriver`：前者在 Android 17 只调用 `GraphicBufferMapper::preloadHal()`，预加载的是 gralloc 的 mapper HAL，不是负责真实分配的 allocator。

前提：预加载的收益来自"fork 前做好、fork 后写时复制共享"，只能覆盖进程无关的初始化。机制：mapper 负责缓冲区元数据与 map/lock 操作，allocator 负责分配；HAL 连接在 Zygote 建立后由子进程继承，首次 map 免去服务连接与实现初始化成本，但首次 buffer 分配仍走 allocator 的完整路径。结果：诊断"分配慢"不能指望 Zygote 预加载，它只覆盖 mapper 侧。完整 preload 序列与启动链见 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)。

**Q2: Android 17 的 mapper 预加载为什么跳过 Gralloc 2/3？什么情况下直接 FATAL？**

Android 17 的 `GraphicBufferMapper::preloadHal()` 按 `requireMapper4()` 裁剪：设备 API level ≥ 36 且系统要求 gralloc 4 或更新时，只预加载 Gralloc 4/5、跳过 2/3；当 4/5 被要求却拿不到实现时，`LOG_ALWAYS_FATAL` 直接终止——问题暴露在开机而非第一个 app 启动。

前提：预加载的目的是加热"实际会用到"的 HAL，对纯新版本设备加载老版本实现是纯浪费。机制：版本要求来自系统属性与设备 API level 的组合判定，Gralloc 4/5 在该条件下总会被预加载。结果：mapper 预加载阶段的 FATAL 属于配置与实现不匹配的启动期故障，与具体 app 无关。

**Q3: maybePreloadGraphicsDriver 把图形驱动预热到什么程度？ro.zygote.disable_gl_preload 关掉的是什么？**

只预热到最浅层：`zygote_preload_graphics()` 对 SkiaGL 路径调 `eglGetDisplay(EGL_DEFAULT_DISPLAY)`，对 Vulkan 路径调 `vkEnumerateInstanceVersion()`——不创建 EGLContext，也不建 VkInstance 与 VkDevice。

前提：Zygote 里能预加载的只有进程无关初始化，context/instance/device 绑定具体渲染状态，做进 Zygote 反而制造跨 app 污染与常驻内存。机制：`ro.zygote.disable_gl_preload` 只跳过这步驱动预热，`nativePreloadAppProcessHALs` 的 mapper HAL 预加载不受它影响。结果：驱动初始化的一部分被所有 app 通过 COW 共享，但 shader 编译、swapchain 建立等仍在 app 首帧发生，优化预期要按此边界设定。

**Q4: app 实际用的 GPU 驱动由谁决定？GraphicsEnvironment 的选择链优先级是什么？**

由 app 进程侧的 `GraphicsEnvironment.setup()` 决定（setupGpuLayers → setupAngle → chooseDriver → notify），按固定优先级选驱动：UPDATABLE_DRIVER_ALL_APPS 全局开关 > 应用对生产驱动的 opt-out > 预发布驱动 opt-in > 生产驱动 opt-in > denylist > allowlist，命中后经 `ro.gfx.driver.0/1` 指定的可更新驱动包加载实现。

前提：平台支持可更新 GPU 驱动与 ANGLE，驱动可替换意味着 Zygote 预加载的平台驱动未必是 app 最终用的驱动。机制：预加载发生在 Zygote（平台默认驱动），驱动切换发生在 app 进程（GraphicsEnvironment），两者独立。结果：分析"预加载了驱动但首帧仍慢"时，先确认 app 是否被切到可更新驱动或 ANGLE——切换后驱动初始化成本在 app 进程里重新发生。

**Q5: Android 17 上 ANGLE 需要什么条件才能成为设备的默认 GL 实现？**

按源文档口径，Android 17 的 ANGLE manifest opt-in 面向三类设备条件：essential 档位设备、低内存设备、`ro.vendor.api_level < 202604` 的设备；ANGLE 默认化按设备能力门控，不是全局一刀切。

机制上（理解推导）：ANGLE 把 OpenGL ES 调用翻译到 Vulkan 执行，统一驱动实现、降低厂商维护面；按档位与 vendor API level 门控反映"能力足够的设备走原生驱动、受限设备走 ANGLE"的取向。边界：这是设备侧的默认化条件，应用层面的驱动选择仍走 Q4 的 GraphicsEnvironment 选择链。

**Q6: 首帧开销里哪些是 Zygote 预加载帮不上的？HardwareRenderer.preload() 能提前什么？**

分配器初始化、shader 编译、BufferQueue/BLAST 队列建立、fence 与 SurfaceFlinger 合成都依赖 per-app/per-window 状态，首帧才发生，Zygote 预加载覆盖不到；`HardwareRenderer.preload()` 能做的是提前拉起 RenderThread 并初始化渲染管线，把线程冷启动挪到可见首帧之前。

前提：Zygote 预加载只对"进程无关、可 COW 共享"的内容有效。机制：窗口与 surface 一一绑定到具体界面，无法在 Zygote 统一预做，首帧等待常卡在这些 per-app 环节。结果：首帧优化按组合拳做——preload 提前建线程、预编译或缓存 shader、简化首帧布局与绘制，而不是寄望 Zygote 一劳永逸。
