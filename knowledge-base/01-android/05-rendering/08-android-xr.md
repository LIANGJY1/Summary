# Android XR 应用出图、环境资产与帧预算

> 学习资料（文章模式沉淀）。边界：本文区分 Android XR 兼容型 2D 面板、Jetpack XR 空间应用、Unity/OpenXR 应用和投影眼镜应用的出图边界，说明环境资产建议及 XR 应用质量指南中的帧时间口径。平台图形栈的细节归通用渲染主题。Android XR 与 Jetpack XR SDK 独立于 Android 平台 tag 发布，依赖版本需按相应 SDK 发布说明核对。内容按 2026-10-04 可查的 Android 官方文档整理。Q 序列即结构，供 Atlas 同源直读。

**Q1: Android XR 兼容型应用、Jetpack XR、Unity/OpenXR 和投影眼镜应用分别怎样出图？**

出图路径取决于应用形态。兼容型 2D 面板仍从 Android 窗口系统提交内容。空间应用和原生 XR 引擎还要把场景或 swapchain 交给 XR runtime。投影眼镜应用则运行在手机 host，由系统将其投影到眼镜。

1. **兼容型 2D 面板：**应用沿普通 Android 路径生产窗口帧，包括 Choreographer 调度、HWUI 绘制、Surface buffer 以及适用时的 FrameTimeline。普通 Android trace 能解释应用窗口帧如何产生和提交，但无法单独还原 XR runtime 的合成与最终显示过程。
2. **Jetpack XR 空间应用：**应用可以用 Subspace、SpatialPanel 和 SceneCore entity 组织空间内容。嵌入的 Android 面板仍有自己的 Surface 内容，但面板 buffer 交给 XR scene/runtime 后，普通手机窗口分析就到达边界。XR 场景如何合成、重投影和最终 present 应按 XR runtime 的观测能力分析。
3. **Unity/OpenXR 应用：**帧遵循 OpenXR 帧与 swapchain 生命周期，典型次序是 `xrWaitFrame`、`xrBeginFrame`、获取并等待 swapchain image、渲染、释放 image、`xrEndFrame`。swapchain image 由 OpenXR runtime 管理，不能因为 trace 中出现 acquire、wait 或 release 就把它当成 Android BufferQueue buffer。必须结合 API 对象和调用栈判断两者。
4. **投影眼镜应用：**投影 Activity 在手机等 host 设备运行，系统再把其体验投射到眼镜。手机侧出帧只说明 host 端完成了相应渲染工作，不代表眼镜端已经及时接收并显示该帧。眼镜功耗与发热受眼镜自身硬件和投影链路影响，不能仅凭 host GPU 时间作结论。应用从 projected context 访问眼镜硬件时，也要把眼镜设备与 host 手机的硬件上下文区分开。

Android 的公开图形栈资料可以支持普通 2D 面板路径分析。XR compositor 内部的 latch、reprojection 和最终 present 是否可见，取决于设备和 trace instrumentation。公开 trace 中搜不到 XR 关键字，不能据此证明 runtime 没有处理该帧。XR SDK 和库按 Jetpack artifact 独立发布，不能仅凭 AAOS13 等 Android 平台源码树中没有这些库，就推断当前 XR SDK 不存在。

**Q2: Android XR 空间环境的 80 MB、每块 10,000 vertices、200 m 和 1.5 m 分别表示什么？环境资产成本怎样估算？**

这些数字来自 Android XR 环境资产制作指南，属于内容制作与质量建议，并非单帧耗时或内存硬上限。80 MB 建议限制环境 geometry GLB/GLTF 文件大小，10,000 vertices 是单个几何 patch 的建议上限，200 m 是环境视距，约 1.5 m 是几何地面相对用户高度的建议位置。违反建议可能增加资源成本或破坏观感，但不能只凭其中一个数字断定应用必然卡顿。

环境资源的成本应按加载和显示过程拆开估算：

1. **读取与交付：**文件可来自 APK、应用 asset、URI 或 Play Asset Delivery 等来源。下载、解包和读取会影响资源可用时间，不能把文件已随包交付等同于模型已加载。
2. **解析与对象创建：**GLB/GLTF 解析会创建场景对象与几何数据，模型越大或对象越多，CPU 时间和临时内存压力通常越大。
3. **纹理处理与上传：**纹理转码、mipmap 链及 GPU 上传影响加载时间和显存。官方环境建议使用 KTX2 纹理并包含 mipmap。GLB 压缩文件大小不等于 GPU 常驻大小，实际占用还受解码格式和 mipmap 影响。
4. **每眼渲染目标：**每眼目标分辨率、渲染路径及设备策略影响 GPU 像素工作量。文件大小不能替代对目标分辨率与实际着色量的测量。
5. **场景挂接与首帧：**模型加入场景后还涉及 entity attach、渲染准备和首帧提交。加载完成不代表它已经显示，也不代表呈现时延已经结束。

可见 skybox 与 image-based lighting（IBL）应分开看。GLB/GLTF 环境几何资产可包含用户看到的环境纹理。IBL ZIP 是供物体光照和反射采样的环境数据，可由 HDR EXR 使用 `cmgen` 生成。几何、可见 skybox 与 IBL 的职责不同，拆分后可独立选择内容和分辨率。若只提供几何而没有所需环境光照数据，空间物体可能显得过亮、过暗或反射不符合预期。

格式和资源生命周期也有版本边界。Android XR 当前指南支持 glTF 2.0，通常使用 `.glb`，模型加载 API 和支持扩展要以所用 Jetpack XR artifact 版本为准。SceneCore beta01 阶段的 `GltfModel.create()` 文档限定 binary glTF（`.glb`），不能不加版本条件地推广到所有 API。模型和 IBL 资源若实现 `AutoCloseable`，退出场景时既要解除 entity 与 parent 的关系，也要关闭持有的资源对象。仅从 scene 移除 entity 不等于已释放资源。

**Q3: Android XR 质量指南中的 11.1 ms、13.8 ms 和每眼分辨率怎样解读？启用 SpaceWarp 后是否还要每个显示帧都渲染？**

Android XR differentiated app 质量指南要求应用每帧渲染时间低于 11.1 ms（90 Hz）或 13.8 ms（72 Hz），并将至少 1856 × 2160 每眼列为分辨率指标。这些是 XR 应用的渲染质量目标，不是 Android 主线程的独占预算，也不能用来推算任意设备、任意应用的固定帧率。

1. **帧时间口径：**11.1 ms 和 13.8 ms 分别对应 90 Hz 与 72 Hz 的显示周期。应用的帧生成涉及 CPU simulation、render thread、GPU 和 runtime 提交等环节，任何一个阶段超时都可能影响及时提交。应按引擎、OpenXR runtime 和 Android trace 分段观察，而不是把全部预算当成主线程可用时间。
2. **分辨率口径：**1856 × 2160 是每眼的质量指南指标。它不意味着应用必然执行两个完整、独立的 render pass。multiview、foveation、Vulkan subsampling、实际 swapchain 配置与 compositor 策略会改变实际着色工作量，因此应检查运行时配置和 GPU 指标。
3. **Application SpaceWarp：**Android XR 的 Unity URP Application SpaceWarp 是 OpenXR 优化方案，可合成交替帧。它使用运动向量和深度信息预测像素移动，降低应用每个显示周期都生成全新图像的需求。显示仍可按设备刷新节奏更新，但合成帧不等于应用新渲染帧，必须分开记录 app FPS、runtime 合成 cadence 和 display refresh rate。
4. **适用条件：**SpaceWarp 需要支持运动向量的 shader，并受 Unity、Android XR Extensions、OpenXR runtime 和设备能力约束。启用后应用仍须及时提供合成所需的真实帧及运动/深度数据，因此不能把它理解为应用可以停止逐帧工作。
5. **与其他优化的关系：**Unity Android XR Extensions 中的 Application SpaceWarp 负责合成交替帧，Vulkan subsampling 调整不同区域的采样密度，late latching 尽可能晚地更新姿态以缩短运动到光子的延迟。三者解决的问题不同，但会共同影响帧管线。它们的收益依赖内容、设备和配置，不能简单相加成固定帧时间或延迟数字。

质量目标和优化能力要以 Android XR 质量指南、Jetpack XR/OpenXR 文档及当前 Unity Android XR Extensions 文档的版本为准。兼容型 Android 面板不因运行在 XR 设备上就自动具备 Unity/OpenXR 的 SpaceWarp、subsampling 或 late latching 开关。
