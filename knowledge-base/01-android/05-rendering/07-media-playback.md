# 视频播放与合成路径

> 学习资料（文章模式沉淀）。边界：本文回答"视频帧如何经 SurfaceView/MediaCodec/tunneled 路径上屏、编解码能力与容器格式支持的版本边界"；渲染机制归 01–02 册，应用侧 Media3 与混合渲染实战见 [应用媒体与混合渲染](10-app-media-hybrid-practice.md)。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: 用了 SurfaceView 的视频就一定走硬件 overlay 吗？怎么确认目标帧的实际合成路径？**

不一定。SurfaceView 只为视频保留独立的 SurfaceFlinger layer，让 HWC 有机会把它判为 `DEVICE`；最终 `DEVICE` 还是 `CLIENT` 由 SurfaceFlinger 每帧把完整 layer 栈交给 Composer HAL 协商决定，视频格式、缩放、旋转、HDR、受保护属性、浮层 UI、可用 plane 数量与显示带宽都会改变这一帧的结果。`DEVICE` 只界定该 layer 由设备侧合成，不承诺一层对应一个物理 plane，也不代表整帧没有 GPU 工作——同一帧完全可以视频 `DEVICE`、UI 与特效 `CLIENT` 混合，RenderEngine 先把 CLIENT 层合成进 client target 再交给 HWC；overlay 因此是逐帧决策，上一帧的结论不能沿用。确认路径的最低直接证据是目标视频 layer 在问题区间的 `hwc_composition_type`：`dumpsys SurfaceFlinger` 适合看某一时刻快照，Perfetto 适合看 composition type 随时间变化与 fence 等待；GPU 轨道忙或空都只是旁证，因为 GPU 还在执行 App 渲染或其他 client 合成。接口代际不等于能力：AIDL Composer3 改变的是接口形式，plane 与格式能力仍按设备实测。

**Q2: `MediaCodec.releaseOutputBuffer(index, renderTimestampNs)` 返回后帧就显示了吗？BufferQueue 怎样处理"还没到点"的帧？**

没有。该调用只表示 App 放弃对输出 buffer 的控制并声明期望显示时间（纳秒），其后仍有 `queueBuffer`、SurfaceFlinger latch、HWC 合成与 display present。机制按 AAOS13 源码核对：`BufferQueueProducer::queueBuffer()` 把 requested present timestamp 写入 `BufferItem`；消费方 `BufferQueueConsumer::acquireBuffer(expectedPresent)` 比较队首帧与目标显示时间——距离目标还早时返回 `PRESENT_LATER`，SurfaceFlinger 暂不 acquire；队列中存在更合时的后续帧时，过期的队首帧可被整帧退回丢弃；时间戳明显异常或过远时进入保护分支，避免错误时间戳卡住队列。所以 requested present 只是期望时间，不是显示完成凭据；`releaseOutputBuffer()` 正常返回远不足以解释视频卡顿，要沿 queue、latch、合成与 present fence 把同一帧对齐后再归因。

**Q3: tunneled playback 需要哪些条件？AAOS13 源码里 sideband 是怎样建立的，`SIDEBAND` 与 `DEVICE` 有何不同？**

低层播放要同时满足：video decoder 支持 `FEATURE_TunneledPlayback`、audio 输出能建立 hardware A/V sync、`AudioTrack` 与视频 `MediaCodec` 使用同一 audio session id 且走 `FLAG_HW_AV_SYNC` 路径持续供带 PTS 的音频、输出用 `SurfaceView` 承载，MIME、profile、secure、HDR、分辨率组合也被支持——能力查询通过只代表可以发起请求，稳定播放仍要设备实测。AAOS13 源码核对：媒体层把 audio session 转成 native `audio-hw-sync` key（`CCodecConfig.cpp` 映射，值来自 `AudioSystem.getAudioHwSyncForSession()`）；`CCodec.cpp` 在有输出 Surface、视频 decoder 且 `feature-tunneled-playback` 非零时调用 `configureTunneledVideoPlayback()`，创建 `C2PortTunneledModeTuning::output`（模式 `SIDEBAND`），同步类型有 `audio-hw-sync` 选 `AUDIO_HW_SYNC`、有 `hw-av-sync-id` 选 `HW_AV_SYNC`、否则默认 `REALTIME`；再查询 component 返回的 tunnel handle，经 `native_window_set_sideband_stream()` 绑到输出 Surface；ACodec/OMX 路径仍保留，tunnel 下跳过普通 native window buffer 分配。`SIDEBAND` 与 `DEVICE` 不同：`DEVICE` 仍是逐帧 buffer 更新交硬件合成，`SIDEBAND` 把 buffer 更新与内容同步整体交给设备侧机制，trace 中通常没有密集的 queueBuffer/latch 事件——缺少这些事件可能是正常路径而非黑屏原因。边界：tunnel 视频仍经 SurfaceFlinger 的 sideband layer 进显示链；`PARAMETER_KEY_TUNNEL_PEEK` 未显式设置时行为由 OEM 决定（AAOS13 的 ACodec 保留 legacy unspecified 标记）；静音时也要继续喂带 PTS 的音频，音频时钟停了视频同样停。

**Q4: Media3 的 ABR 会因为解码掉帧或视频 layer 不是 `DEVICE` 而降码率吗？1.10.1 的选档逻辑看什么？**

不会。Media3 1.10.1 的 ABR 分两层：`DefaultTrackSelector` 按 renderer/codec 能力、viewport、用户约束、MIME、语言、HDR 等得到可选 track；`AdaptiveTrackSelection` 再按带宽预算、播放速度、下一个 chunk 时长、buffer 水位与 live edge 决定当前档位（外部库实现，按材料口径转写）。第二层不读取 HWC composition type，也不会因某帧错过 display deadline 或 decoder 掉帧自动降码率——要让解码表现影响选档，需应用限制候选分辨率/码率、排除 track 或实现自定义策略。带宽估计与门槛是库常量而非平台常量：`DefaultBandwidthMeter` 按传输样本以加权中位数更新估计、无样本时按网络类型取初值；`AdaptiveTrackSelection` 默认点播升档约需 10 秒 buffered duration、buffer 仍有 25 秒时推迟降档、对估计带宽保留 0.7 安全余量，TTFB 只有在 `BandwidthMeter` 提供估计时才计入预算。源码中没有所谓"亚 100 ms 主动预测缓存"的主路径，引用实验组件必须写清版本与启用条件。Media3 版本与 Android 版本彼此独立，升级 Media3 不会更换设备 codec 与显示链；rebuffer 分析要把传输完成、估计更新、选档与 buffer 消耗放到同一时间轴。

**Q5: 设备运行 Android 17 就能播放 VVC 吗？"平台支持"要拆成哪三层理解？**

不一定。"平台支持"至少包含三层，排查时分别记录：一是标准与公开 API，即框架提供 MIME、MediaCodec/Codec2 接口与容器支持；二是 AOSP 参考实现，即仓库里可用的软件编解码组件；三是设备产品能力，即 `MediaCodecList` 实际列出的 vendor/platform codec 及其硬件加速、组合能力与稳定性。对 VVC/H.266：Android 17 增加的是 framework MIME、MediaCodec/Codec2 API 与 MP4 extractor 支持，AOSP 不提供 VVC 软件解码器也不提供编码器，只有 SoC 厂商注册了 vendor Codec2 VVC decoder 的设备才能解码——"运行 API 37"与"本机能否播 VVC"是两个独立字段。做法：按平台 MIME 查询解码器并记录 codec name、profile/level；能力查询通过后仍要 `configure()` 与实际解码验证，因为组合能力（secure、HDR、低延迟、tunnel）可能分别成立却不成立于同一 codec。这条三层口径同样适用于 APV 等新格式的评估。

**Q6: APV 是什么定位？"Android 支持 APV"包含哪几层含义？422-10 码流能保证解码输出还是 P210 吗？**

APV（Advanced Professional Video）面向专业录制、剪辑与素材交换：帧内编码让每帧可独立解码，支持 tile 并行与多种色度采样、位深及 HDR 元数据，目标是编辑效率与多代画质而非高压缩分发，更像录制母版或剪辑中间格式；分享与广泛播放仍应导出 AVC、HEVC 或 AV1。"平台支持"按 Q5 的三层拆解：公开 API（`video/apv` MIME、APV 422-10 profile、P210、MediaRecorder APV、MP4 muxing）、AOSP 参考实现（frameworks/av 的 C2 软编解码组件）、设备产品能力（vendor 硬件 codec、Camera 组合、持续码率与温控）。版本线：APV 随 Android 16 引入，Android 16.1（完整 SDK 版本 36.1）增加 `MediaRecorder.VideoEncoder.APV`，Android 17 增加 `setVideoEncodingQuality()` 的 CQ 录制质量参数——必须在 `prepare()` 前调用、仅编码器支持 `BITRATE_MODE_CQ` 时生效（AAOS13 树中无这些 API，按材料口径转写）。边界：AOSP 软件组件受固定 flag 控制且媒体 XML 默认关闭、规格上限远窄于 APV 标准（材料口径为 1920x1920、240 Mbps），不能作为高规格后备；422-10 码流不保证解码输出仍是 P210——软解码器默认输出 YUV420，剪辑链路要核对 configure 后的 output format，防止输出阶段的色度降采样或位深损失。
