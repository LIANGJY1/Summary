# 相机管线：缓冲、栅栏与时间戳

> 学习资料（文章模式沉淀）。边界：本文回答"Camera 输出流的 BufferQueue、fence 交接、ZSL 与时间戳基准"；应用侧 CameraX 与媒体实战见 [应用媒体与混合渲染](10-app-media-hybrid-practice.md)，性能归因归 15-performance。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: Camera2 一次 capture request 的 preview、record、analysis 多路输出共用同一块 buffer 吗？收到 metadata 能证明图像输出就绪吗？**

都不共用、也不证明。同一 request 可以同时产生多路输出，但每路 stream 有自己的格式、尺寸、buffer pool 与 consumer，各自独立归还所属 stream 的 buffer，HAL 让同一次曝光与 ISP 中间结果服务多路输出，并不依赖同一块 GraphicBuffer 在多个 consumer 间顺序传递。请求按 frame number 进入 HAL，同一 frame 的 partial metadata（分批返回的阶段性结果）、final metadata 与各路输出 buffer 可以在不同时间返回，收到某份 metadata 不能证明该帧所有图像输出已就绪。机制上，某个 consumer 长时间占用 buffer 会通过共享的 ISP stage、缩放器、内存带宽或 buffer 预算反压上游，让其他输出也变慢，所以预览正常不能证明分析或录像正常。做法：按 stream、buffer id、format 与 consumer 分别追踪，用 frame number、各 stream result 返回时间与 buffer 归还记录证明反压路径，而不是凭预览现象下结论。

**Q2: Camera 输出流的 BufferQueue 容量由什么决定？加大 maxBuffers 能解决消费慢吗？**

容量是计算出来的，不是固定值。AAOS13 的 `Camera3OutputStream::configureConsumerQueueLocked()` 先用 `NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS` 查询消费方要求保留的最小缓冲数（`maxConsumerBuffers`），再按 `mTotalBufferCount = maxConsumerBuffers + camera_stream::max_buffers` 计算基础队列容量；choreographer 同步的预览流还会追加 `kDisplaySyncExtraBuffer`，PreviewFrameSpacer 路径由 camera service 另持缓存。`max_buffers` 也不是常量：`Camera3Stream` 构造时为 0，由 HAL 在 configure 阶段逐流返回；HAL 已取走的 buffer 达到上限时 `getBuffer()` 等待归还，等待时长记录在"wait on max_buffers"延迟直方图中。加大 maxBuffers 只能让流水线暂时不阻塞，同时增加该流可驻留的图像内存，不能修复消费方长期吞吐不足，错误数值还会破坏 framework 与 HAL 的流控约定。边界：`ImageReader.maxImages` 限制应用可同时持有的 Image 数，与 HAL 的 maxBuffers 相互影响但不是同一个参数。

**Q3: Camera 输出 buffer 从 HAL 写入到消费方读取，fence 按什么顺序交接？HAL 没等 acquire fence 就出错返回时怎么办？**

顺序是：CameraService 经 `ANativeWindow::dequeueBuffer()` 取得 buffer 与表示"上一消费方何时用完"的 fence fd，填入 `camera_stream_buffer.acquire_fence` 交给 HAL；HAL 必须先等 acquire fence 再写入，避免覆盖 GPU、显示或编码器仍在读取的旧内容；完成后经 `processCaptureResult()` 带着表示"HAL 何时停止访问"的 release fence 返回；AAOS13 的 `Camera3OutputStream::returnBufferCheckedLocked()` 按结果选择 `queueBuffer()`（状态正常且时间戳有效）或 `cancelBuffer()`（错误、流正在丢帧或时间戳为 0），两条路径都携带该 release fence。acquire/release 只是同一次所有权交接两端的命名：正常入队后，这条栅栏在消费方视角又叫 acquire fence，并没有生成另一条相机栅栏。错误路径仍有 fence 语义：HAL 填充失败把状态设为 `ERROR`，若从未等待框架给出的 acquire fence，必须把它原样作为 release fence 退回，让框架在复用前仍能等到正确的完成时刻；已等待完成才可返回空 release fence。判断方向时要同时写明"谁准备读写"与"谁已结束访问"。

**Q4: `Camera3BufferManager` 与 HAL buffer management 是同一个机制吗？`requestStreamBuffers()` 返回的 buffer 预先绑定某个 request 吗？**

不是同一个机制：`Camera3BufferManager` 是 CameraService 内部组件，让兼容流复用框架管理的 `GraphicBuffer` 再经 `attachBuffer()` 接入 BufferQueue；HAL buffer management 是 Android 10 引入的 HAL3 接口，HAL 通过 `requestStreamBuffers()` 向 CameraService 取缓冲区，把"捕获请求提交"与"输出 buffer 获取"分开。启用该管理的流，请求中只有流占位、buffer 为空，HAL 稍后按需取。`requestStreamBuffers()` 返回的缓冲区不与某个 `CaptureRequest` 预先绑定（AAOS13 的 `AidlCamera3OutputUtils.cpp` 核对）：HAL 可批量请求、选择其一用于某帧、随 `processCaptureResult()` 返回已用的，多取未用的经 `returnStreamBuffers()` 归还，CameraService 会以错误状态与时间戳 0 走 `cancelBuffer()`。CameraService 逐流校验存在性、重复请求、Surface 断开、"未归还数 + 请求数"是否超过 maxBuffers 与是否正在 configureStreams，错误状态包括 `STREAM_DISCONNECTED`、`NO_BUFFER_AVAILABLE`、`MAX_BUFFER_EXCEEDED`、`FAILED_CONFIGURING`。`bufferId` 缓存只在会话内有效：首次携带原生句柄，后续只传 streamId+bufferId，会话关闭或流移除后不能继续用旧映射。

**Q5: `CONTROL_ENABLE_ZSL` 与应用自管 ZSL（ring buffer 加 reprocess）机制差在哪？为什么 ZSL 照片的回调顺序看起来"乱"？**

`CONTROL_ENABLE_ZSL` 是 device-operated 提示：对 `STILL_CAPTURE` intent 的请求设 true 后，camera device 可以用内部保留的历史图像生成结果，不要求 reprocessable session，也不保证每次选中历史帧，候选帧完全由设备管理。应用自管 ZSL 则在预览期把高分辨率候选帧存入 ring buffer，按键时经 `ImageWriter.queueInputImage()` 与 `createReprocessCaptureRequest()` 送回重处理管线，需要设备声明 PRIVATE/YUV reprocessing 并创建带 InputConfiguration 的 session。HAL 侧以请求是否带输入 buffer 区分：AAOS13 的 `Camera3Device` 在提交前从 `Camera3InputStream` 取输入缓冲填入 `camera_capture_request_t.input_buffer`，无输入流时为 NULL——有 input_buffer 即重处理已有图像，不会重新曝光。回调"乱序"的原因是 ZSL still 的图像内容与 metadata 对应更早的 sensor 时刻：对齐必须用 frame number 与 result 的 `SENSOR_TIMESTAMP`，不能按 callback 到达顺序排列。输出 JPEG 的 `SENSOR_TIMESTAMP` 早于按键时间属正常；源帧已存在也不代表回调立即返回，重处理仍要等输入队列、HAL 流水线、JPEG 输出与栅栏。

**Q6: Camera 不同输出流的时间戳可以直接相减吗？Android 13 的 timestamp base 机制是怎样的？**

不能。timestamp base 表示时间戳采用哪套参考时钟，参考时钟不同就必须先做时钟域转换，或改用共同的 frame number 与 trace flow 关联事件。AAOS13 源码核对：`OutputConfiguration` 已提供 `setTimestampBase()` 与 `TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED` 等常量；libcameraservice 在流配置时按 base 生效——显式选 CHOREOGRAPHER_SYNCED，或默认 base 且该流被 HWComposer 消费（SurfaceView）时设置 `mSyncToDisplay` 并追加 `kDisplaySyncExtraBuffer`，让时间戳贴近显示调度节奏；SurfaceTexture 路径走 PreviewFrameSpacer 但 image timestamp 仍保留 sensor 时基；`TIMESTAMP_BASE_REALTIME` 对应系统单调时钟，便于音视频同步。材料按 Android 17 描述"SurfaceView 固定帧率下默认 CHOREOGRAPHER_SYNCED、video-encode 目标默认 MONOTONIC"，与 AAOS13 的 `isConsumedByHWComposer()`、`isVideoStream()` 分支逻辑方向一致。边界：`onCaptureStarted()` 给出曝光开始时刻（支持 readout 的设备另有 `onReadoutStarted()`，属材料 Android 17 口径），`onCaptureCompleted()` 只表示 final metadata 回到框架，都不代表 buffer 已被消费或预览已 present。
