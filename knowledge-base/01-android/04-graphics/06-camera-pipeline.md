# 相机管线：缓冲、栅栏与时间戳

> 学习资料（文章模式沉淀）。边界：本文回答"Camera 输出流的 BufferQueue、fence 交接、ZSL 与时间戳基准"；应用侧 CameraX 与媒体实战见 [应用媒体与混合渲染](./10-app-media-hybrid-practice.md)，性能归因归 12-performance。证据：AAOS13 源码核对，逐题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Camera2 一次 capture request 的 preview、record、analysis 多路输出共用同一块 buffer 吗？收到 metadata 能证明图像输出就绪吗？**

不共用，也不能据此证明输出图像已就绪。同一 request 可产生多路输出，但每路 stream 有自己的格式、尺寸、buffer pool 和 consumer，各自归还所属 stream 的 buffer；HAL 可让同一次曝光及 ISP 中间结果服务多路输出，并不要求同一块 GraphicBuffer 在多个 consumer 间传递。

1. 请求按 frame number 进入 HAL；同一帧的 partial metadata、final metadata 与各路输出 buffer 可以在不同时间返回。收到 metadata 不能证明所有图像输出已就绪。
2. 某个 consumer 长时间占用 buffer 时，可能通过共享的 ISP stage、缩放器、内存带宽或 buffer 预算反压上游，导致其他输出变慢。因此预览正常不能证明分析流或录像流正常。
3. 排查时按 stream、buffer id、format 和 consumer 分别追踪，并用 frame number、各 stream result 返回时间和 buffer 归还记录验证反压路径。

**Q2: [learning] Camera 输出流的 BufferQueue 容量由什么决定？加大 maxBuffers 能解决消费慢吗？**

容量由 consumer 和 HAL 的缓冲要求共同决定，不是固定值。Android 13 的 `Camera3OutputStream::configureConsumerQueueLocked()` 按以下规则配置：

1. `NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS` 查询 consumer 必须保留的最小缓冲数，结果记为 `maxConsumerBuffers`。
2. HAL 在 `configureStreams` 阶段为每条 stream 返回 `camera_stream::max_buffers`。`Camera3Stream` 构造时该值为 0，配置完成后才有 HAL 提供的值。
3. 基础总量为 `mTotalBufferCount = maxConsumerBuffers + camera_stream::max_buffers`。Choreographer 同步预览流还增加 `kDisplaySyncExtraBuffer`；PreviewFrameSpacer 路径则由 camera service 另行缓存帧。
4. HAL 同时取出的 buffer 达到 `max_buffers` 上限时，`getBuffer()` 等待已取出的 buffer 归还，等待时间记录在 “wait on max_buffers” 延迟直方图中。

加大 maxBuffers 可能暂时减少流水线阻塞，但会增加该流可驻留的图像内存，无法修复 consumer 长期吞吐不足；错误配置还会破坏 framework 与 HAL 的流控约定。`ImageReader.maxImages` 限制应用可同时持有的 Image 数，它会影响可用 buffer，但不等于 HAL 的 `max_buffers`。

**Q3: [learning] Camera 输出 buffer 从 HAL 写入到消费方读取，fence 按什么顺序交接？HAL 没等 acquire fence 就出错返回时怎么办？**

fence 随 buffer 所有权交接，用来表达“前一方何时停止访问”和“后一方何时可以开始访问”。Android 13 的流程是：

1. CameraService 调用 `ANativeWindow::dequeueBuffer()` 取得 buffer 和 acquire fence。该 fence 表示上一 consumer 何时完成使用，CameraService 将它放入 `camera_stream_buffer.acquire_fence` 并交给 HAL。
2. HAL 写 buffer 前必须等待 acquire fence，避免覆盖 GPU、显示或编码器仍在读取的内容。
3. HAL 完成访问后，通过 `processCaptureResult()` 返回 buffer 和 release fence；release fence 表示 HAL 何时停止访问该 buffer。
4. `Camera3OutputStream::returnBufferCheckedLocked()` 在状态正常且时间戳有效时调用 `queueBuffer()`，在状态错误、流正在丢帧或时间戳为 0 时调用 `cancelBuffer()`。两条路径都会带上 release fence。
5. 正常入队后，同一同步依赖在 consumer 一侧称为 acquire fence；这只是按所有权方向命名，不表示相机又创建了一条 fence。
6. 若 HAL 返回错误且没有等待 framework 交付的 acquire fence，必须将该 fence 作为 release fence 退回，使 framework 在复用 buffer 前仍等待正确的完成时刻。若 HAL 已等待完成，则不需要再用该 acquire fence 表示未完成访问。

判断 fence 方向时，应同时说明当前谁准备读写 buffer、前一方是否已结束访问。

**Q4: [learning] `Camera3BufferManager` 与 HAL buffer management 是同一个机制吗？`requestStreamBuffers()` 返回的 buffer 预先绑定某个 request 吗？**

两者属于不同层次的 buffer 管理：

1. `Camera3BufferManager` 是 CameraService 内部组件，让兼容 stream 复用 framework 管理的 `GraphicBuffer`，再通过 `attachBuffer()` 接入 BufferQueue。
2. HAL buffer management 是 Android 10 引入的 HAL3 接口。HAL 通过 `requestStreamBuffers()` 向 CameraService 取 buffer，将 capture request 提交与输出 buffer 获取分开。启用该机制的 request 只列出 stream，buffer 为空，HAL 后续按需取用。
3. `requestStreamBuffers()` 返回的 buffer 不会预先绑定到某个 `CaptureRequest`。HAL 可批量请求，再选择一个给某帧使用。已用 buffer 随 `processCaptureResult()` 返回；多取但未用的 buffer 由 `returnStreamBuffers()` 归还。
4. CameraService 会逐 stream 检查 stream 是否存在、是否重复请求、Surface 是否断开、未归还数量加本次请求数是否超过 `maxBuffers`，以及当前是否正在执行 `configureStreams`。错误状态包括 `STREAM_DISCONNECTED`、`NO_BUFFER_AVAILABLE`、`MAX_BUFFER_EXCEEDED` 和 `FAILED_CONFIGURING`。归还未用 buffer 时会以错误状态和时间戳 0 走 `cancelBuffer()`。
5. `bufferId` 缓存只在会话内有效。首次传输携带原生句柄，后续只需传 `streamId` 和 `bufferId`；会话关闭或 stream 移除后不得沿用旧映射。

**Q5: [learning] `CONTROL_ENABLE_ZSL` 与应用自管 ZSL（ring buffer 加 reprocess）机制差在哪？为什么 ZSL 照片的回调顺序看起来"乱"？**

`CONTROL_ENABLE_ZSL` 与应用自管 ZSL 的关键区别是历史帧由谁选择和管理：

1. `CONTROL_ENABLE_ZSL=true` 是 device-operated 提示。对 `STILL_CAPTURE` intent 的 request 设置后，camera device 可以从内部保留的历史图像生成结果；它不要求 reprocessable session，也不保证每次都选择历史帧，候选图像完全由设备管理。
2. 应用自管 ZSL 在预览期间把高分辨率候选帧放入 ring buffer。触发拍照后，应用使用 `ImageWriter.queueInputImage()` 和 `createReprocessCaptureRequest()` 将选定帧送入重处理管线；设备必须支持 PRIVATE/YUV reprocessing，session 也必须通过 `InputConfiguration` 配置输入流。
3. Android 13 的 `Camera3Device` 在提交 request 前从 `Camera3InputStream` 取得输入 buffer 并填入 `camera_capture_request_t.input_buffer`；没有输入 stream 时该字段为 `NULL`。存在 `input_buffer` 表示重处理已有图像，不会重新曝光。
4. ZSL 静态图像可能对应早于按键时刻的 sensor 帧，因此回调到达顺序不等于拍摄帧顺序。应使用 frame number 和结果中的 `SENSOR_TIMESTAMP` 对齐，不能按 callback 到达时间排序。
5. 输出 JPEG 的 `SENSOR_TIMESTAMP` 早于按键时刻是正常情况；源帧已存在也不代表 callback 会立即返回，因为重处理仍需等待输入队列、HAL 流水线、JPEG 输出和 fence。

**Q6: [learning] Camera 不同输出流的时间戳可以直接相减吗？Android 13 的 timestamp base 机制是怎样的？**

不能直接相减。timestamp base 表示图像时间戳使用的参考时钟；基准不同就要先转换时钟域，或用共同 frame number 和 trace flow 关联事件。`OutputConfiguration.setTimestampBase()` 用来选择基准，该 API 与相关常量从 API 33 提供。默认值是 `TIMESTAMP_BASE_DEFAULT`，由 camera device 根据输出 surface 决定时间基准。

1. `TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED`：让预览时间戳贴近显示调度节奏。Android 13 的 libcameraservice 在显式选择此值，或默认基准且 stream 由 HWComposer 消费时，设置 `mSyncToDisplay` 并增加 `kDisplaySyncExtraBuffer`。
2. `TIMESTAMP_BASE_SENSOR`：使用 `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE` 指定的传感器时钟。
3. `TIMESTAMP_BASE_MONOTONIC`：时间戳约等于 `SystemClock.uptimeMillis()` 的时基，适合音视频同步；若设备的 sensor timestamp source 是 REALTIME，不能直接与 capture callback 或 sensor result 时间戳比较。
4. `TIMESTAMP_BASE_REALTIME`：时间戳约等于 `SystemClock.elapsedRealtime()`，与 MONOTONIC 不是同一时钟。
5. 默认 SurfaceTexture 路径由 PreviewFrameSpacer 调整输出帧交付节奏以减少取景抖动，但 image timestamp 仍保留 sensor 时基。固定帧率下，SurfaceView 默认使用 CHOREOGRAPHER_SYNCED；video-encode 输出默认使用 MONOTONIC。

`onCaptureStarted()` 给出曝光开始时刻；支持 readout 时间戳的设备还可回调 `onReadoutStarted()`，表示开始读出图像。`onCaptureCompleted()` 表示 final metadata 返回 framework。以上回调均不代表输出 buffer 已被 consumer 消费或预览帧已 present。
