# Android 图形

> 图形栈从应用到显示：渲染管线与 VSync、GPU 合成、显示服务、图形 API、相机视频管线与诊断工具。本目录收录 14 篇资料、287 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-渲染管线与VSync.md](01-渲染管线与VSync.md)（38 题）— VSync 与帧调度、Choreographer、渲染管线阶段、jank 口径。
- [02-GPU合成与显示.md](02-GPU合成与显示.md)（41 题）— SurfaceFlinger 合成、HWC DEVICE/CLIENT、client target、GPU 频率与负载。
- [03-显示服务与折叠屏.md](03-显示服务与折叠屏.md)（10 题）— DMS 管理边界、LogicalDisplay、多显示与折叠形态。
- [04-图形API.md](04-图形API.md)（16 题）— EGL 对象分工、GLES/Vulkan 语义与边界。
- [05-图形栈预加载.md](05-图形栈预加载.md)（6 题）— Zygote 图形预热、gralloc mapper、驱动预热。
- [06-相机管线.md](06-相机管线.md)（6 题）— Camera2 多路输出、buffer 归属、metadata 时序、反压。
- [07-视频播放.md](07-视频播放.md)（6 题）— SurfaceView 与 overlay 协商、合成路径确认。
- [08-AndroidXR.md](08-AndroidXR.md)（3 题）— XR 应用出图路径、Jetpack XR、投影眼镜。
- [09-页面渲染实践.md](09-页面渲染实践.md)（20 题）— 四类帧监控入口、queueBuffer 语义、JankStats 口径。
- [10-媒体混合渲染.md](10-媒体混合渲染.md)（33 题）— Vulkan 管线创建成本、混合渲染路径。
- [11-GPU诊断工具.md](11-GPU诊断工具.md)（38 题）— PLT/GOT Hook 机制、GAPID/截帧、GPU 计数器。
- [12-View与Compose实践.md](12-View与Compose实践.md)（26 题）— requestLayout 合并与二次调度、列表刷新实践。
- [13-Compose进阶.md](13-Compose进阶.md)（24 题）— Snapshot 版本选择、状态记录链、并发读语义。
- [14-花屏与显示异常.md](14-花屏与显示异常.md)（20 题）— 花屏分层归因、Kanzi/贴图假设检验、单变量实验。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
