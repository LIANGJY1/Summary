# Android XR 出图与预算

> 学习资料（文章模式沉淀）。边界：本文回答"XR 形态应用如何出图、环境资产与每帧预算的口径"；通用渲染机制归 01–02 册。证据：XR 官方文档口径（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: Android XR 上不同形态的应用分别怎样出图？普通 Android 窗口的分析链路到哪里要停？**

按形态分：compatible 2D panel 仍按普通 Android 路径生产——Choreographer、HWUI、Surface buffer 与 FrameTimeline 都可复用；Jetpack XR differentiated 应用用 Subspace、SpatialPanel、SceneCore entity 组织空间内容，2D 面板 buffer 到达 XR scene/runtime 的接收边界后就要停止套用标准手机链路；Unity/OpenXR 应用走 `xrWaitFrame → xrBeginFrame → acquire/wait swapchain image → render → release image → xrEndFrame` 的 runtime 协议，swapchain 由 runtime 管理，不是 Android BufferQueue API，trace 中即使都出现 acquire/wait/release 也要按对象类型区分；Projected 眼镜场景 Activity 运行在 host 手机，帧经传输到 glasses 显示，host 出帧不等于眼镜按时 present，眼镜发热也不能直接归因 host GPU（外部框架，未本地核对）。公开 AOSP tag 能验证的只有 2D panel 的公共图形栈：XR compositor 内部的 latch、reprojection 与最终 present 没有公开 slice 名，trace 搜不到 XR 关键字也不能证明 runtime 没有工作。版本边界：Android XR 与 Jetpack XR SDK（XR Compose alpha、SceneCore beta 等）独立于 Android 平台 tag 发布，AAOS13 树中没有这些外部框架。

**Q2: XR 环境资产的 80 MB、10,000 vertices、200 m 是硬限制吗？环境资产的成本怎样构成？**

不是硬限制，是官方制作建议：环境 glb 建议 80 MB 以内、每个 geometry patch 不超过 10,000 vertices、可见距离写为 200 m、用户高度约 1.5 m；超限增加解析、内存、GPU 与功耗成本，但不能仅凭文件大小判定某帧一定卡顿（外部框架，未本地核对）。成本要拆四段：文件读取与交付（APK/asset/URI、Play Asset Delivery）、解析与对象创建、纹理转码与 GPU 上传（KTX2/Basis 压缩纹理、mip chain、每眼目标）、scene attach 与首帧。可见 skybox 与 IBL 光照要拆开：geometry 资产承载用户看到的远景纹理，IBL ZIP 由 HDR EXR 经 `cmgen` 生成、只服务光照与反射，拆分后两者可独立选分辨率，避免为光照计算读取过大的可见纹理。边界：SceneCore beta01 的 `GltfModel.create()` 只支持 binary glTF（.glb）；.glb/KTX2 文件大小不等于 GPU 常驻大小，转码格式、mip chain 与每眼目标都会改变实际占用；模型与 IBL 资源实现 `AutoCloseable`，离开场景要解除 entity parent 并 `close()`，仅从场景移除不等于资源已释放。

**Q3: Android XR 的"每帧 11.1 ms"是主线程预算吗？开了 spacewarp 之后 App 就不用每个显示帧都渲染了吗？**

11.1 ms（90 Hz）或 13.8 ms（72 Hz）是 Android XR differentiated 质量目标里整个应用 render budget 的量级，不是主线程独占预算——CPU simulation、render thread、GPU、swapchain 等待与 runtime 提交共同消耗它；"每眼至少 1856×2160"也不等于两个完整 render pass，multiview、foveation、subsampling 与设备 compositor 都会改变实际着色像素（外部框架，未本地核对）。spacewarp 用 motion vector 与 depth 合成交替帧：显示可以按面板刷新率继续刷新，App 无须为每个 display refresh 生成全新 render frame，所以引擎 FPS、App FrameTimeline、runtime 合成 cadence 与 display refresh rate 必须分开记录。Unity Android XR Extensions 的 Application Spacewarp、Vulkan subsampling 与 late latching 是三类不同优化，可能作用于同一段时序并改变彼此前提，理论收益不能相加成固定延迟数值；这些能力属于 Unity/OpenXR 引擎路径，不是普通 Compose panel 的开关。
