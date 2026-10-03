# 视频播放与合成路径

> 学习资料（文章模式沉淀）。边界：本文回答"视频帧如何经 SurfaceView/MediaCodec/tunneled 路径上屏、编解码能力与容器格式支持的版本边界"；渲染机制归 01–02 册，应用侧 Media3 与混合渲染实战见 [应用媒体与混合渲染](10-app-media-hybrid-practice.md)。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: 用了 SurfaceView 的视频就一定走硬件 overlay 吗？怎么确认目标帧的实际合成路径？**

不一定。SurfaceView 为视频提供独立的 SurfaceFlinger layer，使 HWC 有机会选择设备侧合成；SurfaceFlinger 每帧仍会把完整 layer 栈交给 Composer HAL 协商，格式、缩放、旋转、HDR、受保护属性、浮层 UI、可用 plane 数量和显示带宽都会影响结果。

1. `DEVICE` 表示该 layer 由设备侧合成，不保证一层对应一个物理 plane，也不表示整帧没有 GPU 工作。同一帧可以是视频 `DEVICE`、UI 和特效 `CLIENT` 的混合结果；RenderEngine 会先把 `CLIENT` 层合成进 client target，再交给 HWC。
2. 合成方式是逐帧决定的，不能沿用上一帧结论。直接证据应检查问题区间目标视频 layer 的 `hwc_composition_type`；`dumpsys SurfaceFlinger` 提供某一时刻快照，Perfetto 可观察 composition type 随时间的变化和 fence 等待。
3. GPU 轨道忙或空只能作为旁证，因为 GPU 还可能在执行 App 渲染或其他 client 合成。AIDL Composer3 改变的是接口形式，plane 与格式能力仍须按设备实际配置判断。

**Q2: `MediaCodec.releaseOutputBuffer(index, renderTimestampNs)` 返回后帧就显示了吗？BufferQueue 怎样处理"还没到点"的帧？**

没有。`MediaCodec.releaseOutputBuffer(index, renderTimestampNs)` 表示 App 放弃对输出 buffer 的控制，并给出以纳秒为单位的期望显示时间；它不是帧已上屏的回执。

1. Android 13 的 `BufferQueueProducer::queueBuffer()` 把 requested present timestamp 写入 `BufferItem`。
2. `BufferQueueConsumer::acquireBuffer(expectedPresent)` 比较队首帧与目标显示时间。若目标时间尚未来到，它返回 `PRESENT_LATER`，SurfaceFlinger 暂不 acquire。
3. 队列中若已有更合时的后续帧，过期队首帧可被整帧退回并丢弃；时间戳异常或过远时也会进入保护分支，避免错误时间戳卡住队列。
4. acquire 之后仍有 SurfaceFlinger latch、HWC 合成和 display present。排查卡顿要把同一帧的 queue、latch、合成和 present fence 对齐，不能把 `releaseOutputBuffer()` 正常返回当成显示完成。

**Q3: tunneled playback 需要哪些条件？AAOS13 源码里 sideband 是怎样建立的，`SIDEBAND` 与 `DEVICE` 有何不同？**

Tunneled playback 需要解码器、音频时钟、音视频同步和输出目标形成受支持的组合；单项 capability 查询通过只代表可以发起请求，稳定播放仍要设备实测。

1. 视频 decoder 必须支持 `FEATURE_TunneledPlayback`，音频输出路径也必须能建立 hardware A/V sync。
2. `AudioTrack` 与视频 `MediaCodec` 使用同一个 audio session id，并走 `FLAG_HW_AV_SYNC` 路径持续提供带 PTS 的音频数据。静音时仍要供给带时间戳的数据，否则音频时钟停止后视频也可能停止。
3. 输出使用 `SurfaceView`。MIME、profile、secure、HDR 和分辨率组合也必须由设备支持。
4. Android 13 的 `CCodecConfig.cpp` 将 audio session 转换为 native `audio-hw-sync` key，值来自 `AudioSystem.getAudioHwSyncForSession()`。`CCodec.cpp` 在存在输出 Surface、组件是视频 decoder 且 `feature-tunneled-playback` 非零时调用 `configureTunneledVideoPlayback()`。
5. CCodec 创建模式为 `SIDEBAND` 的 `C2PortTunneledModeTuning::output`。存在 `audio-hw-sync` 时同步类型为 `AUDIO_HW_SYNC`；否则存在 `hw-av-sync-id` 时为 `HW_AV_SYNC`；两者都没有时为 `REALTIME`。随后它查询 component 返回的 tunnel handle，并通过 `native_window_set_sideband_stream()` 绑定输出 Surface。ACodec/OMX 路径仍保留，tunnel 模式下跳过普通 native window buffer 分配。
6. `DEVICE` 仍逐帧提交 buffer 给硬件合成；`SIDEBAND` 把 buffer 更新与内容同步整体交给设备侧机制。tunnel 视频仍经 SurfaceFlinger 的 sideband layer 进入显示链，但 trace 中通常没有密集的 queueBuffer/latch 事件，因此缺少这些事件可能是正常路径。
7. `PARAMETER_KEY_TUNNEL_PEEK` 未显式设置时的行为由 OEM 决定；Android 13 的 ACodec 保留 legacy unspecified 标记。

**Q4: Media3 的 ABR 会因为解码掉帧或视频 layer 不是 `DEVICE` 而降码率吗？1.10.1 的选档逻辑看什么？**

不会。Media3 1.10.1 的选档分两层，且没有把 HWC 合成结果或 decoder 掉帧作为默认 ABR 信号：

1. `DefaultTrackSelector` 根据 renderer/codec 能力、viewport、用户约束、MIME、语言和 HDR 等条件筛出候选 track。
2. `AdaptiveTrackSelection` 再按带宽预算、播放速度、下一个 chunk 时长、buffer 水位与 live edge 选择档位。它不读取 HWC composition type，也不会因单帧错过 display deadline 或 decoder 掉帧自动降码率；应用要把解码表现纳入策略，需限制候选分辨率/码率、排除 track 或实现自定义策略。
3. `DefaultBandwidthMeter` 用传输样本的加权中位数更新带宽估计；尚无样本时按网络类型取初始估计。`AdaptiveTrackSelection` 的默认点播升档门槛约为 10 秒 buffer，buffer 达到 25 秒时可推迟降档，带宽估计乘以 `0.7` 安全系数后参与选档。TTFB 只有在 `BandwidthMeter` 提供估计时才计入预算。

这些阈值属于 Media3 库实现，不是 Android 平台常量。该版本源码没有“亚 100 ms 主动预测缓存”的主路径；若讨论实验组件，应注明对应版本和启用条件。Media3 与 Android 版本彼此独立，升级 Media3 不会更换设备 codec 或显示链。分析 rebuffer 时，应将传输完成、带宽估计更新、选档和 buffer 消耗放在同一时间轴。

**Q5: 设备运行 Android 17 就能播放 VVC 吗？"平台支持"要拆成哪三层理解？**

不一定。判断“平台支持”时要分别核对公开 API、AOSP 参考组件和设备实际 codec 能力：

1. **公开 API 与容器**：Android 17 提供 VVC MIME、MediaCodec/Codec2 profile 接口和 MP4 parser/extractor 支持。
2. **AOSP 参考实现**：AOSP 不提供 VVC 软件 decoder，也不提供 encoder。
3. **设备产品能力**：设备必须注册 vendor Codec2 VVC decoder，且暴露到 `MediaCodecList`，才能通过 Android media API 解码。运行 API 37 与本机能否播放 VVC 是两个不同结论。

排查时查询支持该 MIME 的 decoder，记录 codec name、profile 和 level，再执行 `configure()` 与实际解码。能力查询通过不代表 secure、HDR、低延迟和 tunnel 等组合能力能在同一 codec 上同时成立。这三层口径也适用于评估 APV 等新格式。

**Q6: APV 是什么定位？"Android 支持 APV"包含哪几层含义？422-10 码流能保证解码输出还是 P210 吗？**

APV（Advanced Professional Video）面向专业录制、剪辑和素材交换。帧内编码允许每帧独立解码，并支持 tile 并行、多种色度采样、位深和 HDR 元数据；它偏向编辑效率和多代画质，不以高压缩分发为目标。面向分享和广泛播放的成片通常仍导出 AVC、HEVC 或 AV1。

1. **公开 API**：Android 16 引入 APV；完整 SDK 版本 36.1 增加 `MediaRecorder.VideoEncoder.APV`。Android 17 增加 `MediaRecorder.setVideoEncodingQuality()`，必须在 `prepare()` 前调用；只有所选 encoder 支持 `BITRATE_MODE_CQ` 时才生效。应用不应同时设置 quality 和 bitrate，因为其行为未定义。
2. **AOSP 参考组件**：AOSP 的 frameworks/av 包含 Codec2 APV 软件 encoder/decoder。Android 17 软件 decoder 源码的分辨率上限为 4096×4096；encoder 的 bitrate 参数上限为 240 Mbps。组件是否构建、启用及可发现还取决于设备的 media 配置和软件 codec flag，不能把源码存在等同于设备运行时可用。
3. **设备产品能力**：vendor 硬件 codec、Camera 组合、持续码率和温控能力仍需按设备查询和实测。

APV 422-10 描述码流 profile，不保证输出 buffer 一定是 P210。AOSP 软件 decoder 会按配置的 output format 转换输出，支持路径包括 YV12/YUV420、P010 和受平台 pixel format 能力影响的 P210。剪辑链路应检查 `configure()` 后的 output format 和实际输出 buffer，确认色度采样与位深没有损失。
