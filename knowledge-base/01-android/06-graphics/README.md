# Android 图形

> 本目录覆盖应用出图、帧调度、GPU 合成、显示服务、图形 API、相机视频管线及图形专项诊断，共 13 册、230 题。View 与 Compose 的组件契约见 [Android UI](../05-ui/README.md)，通用性能采集与优化见 [Android 性能](../14-performance/README.md)。

## 册

- [01-渲染管线与VSync.md](01-渲染管线与VSync.md)（38 题）— VSync 与帧调度、Choreographer、渲染管线阶段、jank 口径。
- [02-GPU合成与显示.md](02-GPU合成与显示.md)（41 题）— SurfaceFlinger 合成、HWC DEVICE/CLIENT、client target、GPU 频率与负载。
- [03-显示服务与折叠屏.md](03-显示服务与折叠屏.md)（9 题）— DMS 管理边界、LogicalDisplay、多显示与折叠形态。
- [04-图形API.md](04-图形API.md)（16 题）— EGL 对象分工、GLES/Vulkan 语义与边界。
- [05-图形驱动选择.md](05-图形驱动选择.md)（2 题）— 应用 GPU 驱动与 ANGLE 选择。
- [06-相机管线.md](06-相机管线.md)（6 题）— Camera2 多路输出、buffer 归属、metadata 时序、反压。
- [07-视频播放.md](07-视频播放.md)（9 题）— 解码与显示路径、码流适配、片源体积、播放器缓冲和内存。
- [08-AndroidXR.md](08-AndroidXR.md)（3 题）— XR 应用出图路径、Jetpack XR、投影眼镜。
- [09-页面渲染实践.md](09-页面渲染实践.md)（20 题）— 四类帧监控入口、queueBuffer 语义、JankStats 口径。
- [10-媒体混合渲染.md](10-媒体混合渲染.md)（33 题）— Vulkan 管线创建成本、混合渲染路径。
- [11-GPU诊断工具.md](11-GPU诊断工具.md)（22 题）— GPU 执行与帧捕获、图形内存统计、Camera 追踪和 Winscope 图层取证。
- [12-应用绘制与图形效果.md](12-应用绘制与图形效果.md)（11 题）— 自绘 View 与 Compose Canvas、持续出图、帧率请求、图层、RenderEffect 和 RuntimeShader。
- [14-花屏与显示异常.md](14-花屏与显示异常.md)（20 题）— 花屏分层归因、Kanzi/贴图假设检验、单变量实验。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
