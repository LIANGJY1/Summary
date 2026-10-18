# 图片加载性能

本目录覆盖 Android 应用中图片从交付、解码到显示的性能问题，以及相机分析帧的 YUV 处理。内容遵守 [WRITING-GUIDE.md](../../../../WRITING-GUIDE.md)。

## 阅读顺序与文章索引

1. [图片格式与体积](./01-格式与体积.md)：位图基础、尺寸交付、编码取舍、透明度、矢量与密度资源、宽色域和 HDR、上传编码、栅格化与交付验收（17 题）。
2. [图片解码与内存](./02-解码与内存.md)：内存口径、采样计算、方向、像素配置、分配与复用、区域解码、动图、峰值预算及 OOM 排查（21 题）。
3. [加载链路与加载库](./03-加载链路与加载库.md)：Glide 4 与 Coil 3 的目标尺寸、分层缓存、版本与账号身份、生命周期、预取、回退、本地缩略图和链路验收（15 题）。
4. [图片显示与渲染](./04-显示与渲染.md)：View 与 Compose 布局、缩放、异步加载、纹理上传、图层与动效、可见帧证据及综合面试案例（12 题）。
5. [YUV 图像处理与性能](./05-yuv.md)：采样比例、平面布局、stride 寻址、色彩转换、帧生命周期、CameraX 背压、NV21 输出、Surface 显示、P010 与诊断（14 题）。

共 5 篇、79 题，其中 12 题为 `[done]`，67 题为 `[learning]`。先完成静态图和动图主线，再阅读 YUV。机制示意图说明加载阶段、像素副本重叠与 YUV 布局，示例数字均为理论计算，不是项目测量成果。

## 主题边界

1. **应用优化：**本文档组说明请求尺寸、缓存、内存、显示与图像分析的性能判断。Glide 本身的整体架构与组件扩展见[应用库目录](../../../03-app-libraries/README.md)。
2. **相机与媒体机制：**YUV 只收应用消费帧时需要的布局、颜色、转换和调度边界。相机系统管线见[相机管线](../../../05-graphics/06-camera-pipeline.md)，视频播放见[视频播放与合成路径](../../../05-graphics/07-media-playback.md)。
3. **工具：**文中给出所需证据和验收指标，完整工具操作见[性能工具目录](../../04-工具/README.md)。

## 资料依据

1. **Android Developers：**Bitmap、BitmapFactory.Options、ImageDecoder、BitmapRegionDecoder、ImageFormat、Image.Plane、ImageReader、YuvImage、ComponentCallbacks2 的 API 参考，以及图片内存、慢渲染、Compose 图形和 CameraX 分析文档。
2. **平台源码：**Android 开源项目 `platform/frameworks/base` 的 `android-13.0.0_r1` 标签，用于核对区域解码同步和图像方向处理。已完成题目保留其原有版本依据。
3. **加载库官方资料：**Glide 4 的 Caching、Targets、Resource Reuse、Transformations、Hardware Bitmaps、RecyclerView integration，以及 Coil 3 的 Compose、Image Loaders、Network Images。
4. **格式与协议：**W3C PNG 规范、GIF89a、ITU-R BT.601/BT.709、libyuv 格式说明与 RFC 9111。各题注明直接相关依据，设备可选能力与版本敏感行为在题内限定。
