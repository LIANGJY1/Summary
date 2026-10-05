# AOSP 音频子系统

> 学习资料（文章模式沉淀）。主线：audioserver 守护进程与服务、AudioPolicyService 路由策略、AudioFlinger 输出线程与混音缓冲、Direct/Offload 接受约束、音量与效果链、AudioTrack/AAudio 数据路径、录音与诊断。平台机制按 Android 13 源码核对。HAL 接口迁移按 Android 官方文档核对。应用侧延迟、AAudio 与 offload 实践见 [04-audio-latency.md](./04-audio-latency.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] audioserver 为什么独立成进程？里面固定跑哪些服务，AAudioService 为什么是"条件启动"？**

audioserver 是独立的 native 守护进程，固定运行 AudioFlinger（混音引擎）与 AudioPolicyService（路由策略），并启动 MediaLogService。AAudioService 只有在系统 MMAP 策略明确允许时才启动（main_audioserver.cpp 核对）。独立进程划分故障域：audioserver 崩溃时，init 可以单独重启音频服务，不必重启编解码、相机等其他媒体进程。

audioserver.rc 中与调度和权限相关的配置有三项：

1. **I/O 优先级**：`ioprio rt 4` 将进程 I/O 调度类别设为实时、优先级设为 4，减少音频相关 I/O 被普通 I/O 挤压的机会。它不保证磁盘或设备 I/O 一定按时完成。
2. **挂起控制权限**：`capabilities BLOCK_SUSPEND` 赋予进程阻止系统挂起所需的 capability。它本身不表示系统始终被锁定，实际是否持有 suspend blocker 取决于运行时路径。
3. **专用用户**：`user audioserver` 让进程以 audioserver 身份运行，配合单独配置的组和 capability 限制其权限。

省略时使用 init 或系统默认行为，不能按当前显式值的反面推断：

1. 省略 `user`：Android init 默认以 root 身份运行服务，因此权限会比显式指定 audioserver 用户更宽。
2. 省略 `capabilities`：服务以非 root 用户运行时不会获得 Linux capabilities。audioserver 显式声明 `BLOCK_SUSPEND` 才具备对应 capability。
3. 省略 `ioprio`：init 不施加该服务级 I/O 优先级设置，实际优先级沿用目标系统的进程与内核默认行为。audioserver 显式设置这些项，是为了减少对宽泛权限和普通 I/O 调度的依赖。

AAudioService 的条件启动在 main_audioserver.cpp：先经 AudioFlinger 查询系统 MMAP 策略，策略为 AUTO 或 ALWAYS 时才 instantiate。这是为了防止客户端在不支持的设备上误用 AAudioService。若服务未启动，可以判断该系统没有开放 AAudio MMAP 服务路径。策略为 AUTO 时，MMAP 不可用可走普通音频框架数据路径。策略为 ALWAYS 时，MMAP 不可用会导致建流失败，不能统一概括成自动回退。

**Q2: [learning] audioserver 或音频 HAL 崩溃后系统怎样恢复？应用侧会观察到什么？**

恢复动作由故障进程对应的 init 服务配置决定。audioserver 重启会使原有服务端音轨失效。单独的 HAL 重启则不等于 audioserver 重启，因此不能假设所有客户端轨道都能无感恢复。

1. **audioserver 崩溃**：init 按 audioserver 服务配置重启进程。Android 13 的 audioserver.rc 在 audioserver 重启时通过 `onrestart` 重启配置中的 Audio HAL 服务，具体服务名由产品配置决定。
2. **请求重启 HAL**：框架或 VTS 将 `sys.audio.restart.hal` 设为 `1` 时，audioserver.rc 按当前分支配置停止并启动 HAL 服务，再把属性重置为 `0`。这是显式重启请求，不应与 audioserver 崩溃恢复流程混为一谈。
3. **应用观察**：audioserver 死亡后，AudioTrack/AudioRecord 原有服务端音轨消失。AudioTrack 在 start、obtainBuffer、getPosition 等路径遇到 `DEAD_OBJECT` 后可调用 `restoreTrack_l()`，尝试在重启后的服务里重建音轨（AudioTrack.cpp 多处恢复分支核对）。恢复成功后播放可继续，但故障间隙的声音不会补播。
4. **HAL 单独重启**：HAL 服务的 init 定义、AudioFlinger 与 HAL 的重连结果以及厂商实现都会影响在播流恢复方式。应用应检查播放/录音 API 的返回状态并准备重建流。若忽略错误，可能表现为"播着播着没声"。

**Q3: [learning] 一条流从 AudioAttributes 到具体设备，路由决策在哪完成？两个策略引擎差在哪？**

决策在 audioserver 内的 AudioPolicyService。它持有 AudioPolicyManager 与策略引擎，把应用的 AudioAttributes（usage、content type）映射到产品策略，再结合设备连接状态为该策略选择输出设备与输出配置档。AudioFlinger 只执行选出的路径，不参与选择。Android 13 提供两个引擎实现，选哪套由构建配置决定：

1. **enginedefault**：策略映射与设备选择编译进代码。
2. **engineconfigurable**：由 audio_policy_engine_configuration.xml 驱动，产品可用 XML 定制策略、设备与音量曲线。配置示例分别在 `frameworks/av/services/audiopolicy/enginedefault/config` 与 `engineconfigurable/config`（目录核对）。

排查意义：要改"某用途走某设备/某配置档"，动的是引擎 XML（configurable 引擎）或 framework 默认逻辑，改 AudioFlinger 改不动选择。

**Q4: [learning] Android 13 的 audio_policy_configuration.xml 四个顶层标签各描述什么？**

这是 HAL 能力声明文件，四个标签各回答一个问题（primary 配置样例核对）：

1. `modules`：声明有哪些音频 HAL 实例，并提供 HAL 版本信息。
2. `mixPort`：声明软件侧音频流端点。

    1. `role="source"` 表示输出混音流，`role="sink"` 表示输入流。
    2. 输出端口的 `flags` 表示配置用途，例如 primary output 声明 `AUDIO_OUTPUT_FLAG_PRIMARY`。

3. `devicePort`：声明物理或虚拟设备端点。

    1. `type` 指明设备类别，例如 `AUDIO_DEVICE_OUT_SPEAKER`。
    2. `address` 区分同类型的多个设备实例。

4. `route`：mix 到 device 的合法连接，如 primary output 连到 Speaker。

引擎可选的设备受两个条件限制：设备端点必须在本文件声明，并且当前处于连接状态。车机的 audio bus 也要先声明为 BUS 类型的 `devicePort`，之后才能被策略选中。

**Q5: [learning] Android 14 引入 AIDL Audio HAL 后，HIDL 与 AIDL 的接口边界怎么理解？**

Android 13 及更早版本的 AOSP Audio HAL 使用 HIDL。Android 14 起 AOSP 提供 Stable AIDL 接口并鼓励厂商迁移，但框架仍支持设备侧提供 HIDL 或 AIDL 实现。Android 14 之后新增的 HAL 功能只加入 AIDL 接口，因此平台版本并不能单独证明某台设备已经迁移。

1. **HIDL Core HAL**：接口位于 `android.hardware.audio@N.M` 包，`IDevicesFactory` 是入口，`IDevice` 描述设备能力。Android 13 的 AOSP HIDL 主线为 7.1。
2. **AIDL Core HAL**：接口位于 `android.hardware.audio.core` 包，`IModule` 描述模块能力并作为核心入口。AIDL 还通过 `IConfig` 提供系统级配置。
3. **配置来源变化**：HIDL 实现依赖厂商提供的 `audio_policy_configuration.xml` 等配置。AIDL 实现将音频策略配置规范转入 HAL，由 Audio Policy Manager 从 HAL 获取。
4. **排查边界**：判断设备实际使用哪一代接口，应核对平台分支、VINTF 声明、服务注册和厂商 HAL 实现。Android 14 的框架兼容 HIDL 与 AIDL，Android 14 之后新增的 HAL 功能只支持 AIDL。

版本依据：Android Open Source Project 的 Audio HAL AIDL 文档与 HIDL HAL 接口文档（2026-09 检索）。

**Q6: [learning] AudioFlinger 按输出 flags 选混音线程，分支是什么？deep_buffer 为什么没有专属线程类？**

openOutput 创建线程时按 flags 顺序判断（AudioFlinger.cpp 分支核对）：

1. `AUDIO_OUTPUT_FLAG_MMAP_NOIRQ`：建 MmapPlaybackThread，客户端直写映射缓冲。
2. `AUDIO_OUTPUT_FLAG_SPATIALIZER`：建 SpatializerThread，空间音频输出。
3. `AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD`：建 OffloadThread，压缩码流交 DSP 解码。
4. `AUDIO_OUTPUT_FLAG_DIRECT`：建 DirectOutputThread，不经软件混音。
5. 其余（含 `PRIMARY`、`DEEP_BUFFER`）：建 MixerThread，通用软件混音。

`AUDIO_OUTPUT_FLAG_DEEP_BUFFER` 没有专属分支。它落在 MixerThread，只是选择了缓冲更长的输出配置档，用更高延迟换取对混音周期抖动的容忍，软件混音器仍是同一个实现。排查时先区分配置档与线程类型：flag 决定使用哪个输出配置，线程类型决定数据如何混合。primary 与 deep-buffer 是两个独立的 openOutput 实例，各自有一条线程，因此不会互相混音。

**Q7: [learning] Direct 与 Offload 输出接受音轨的约束，和传统混音差在哪？**

三种输出的数据路径与接受条件不同：

1. **Direct**：不做软件混音也不重采样，建 track 时要求音轨的采样率、格式、声道掩码与输出配置完全一致（Threads.cpp 两处 `sampleRate != mSampleRate || format != mFormat || channelMask != mChannelMask` 比较核对）。不一致即失败。它适合需要绕过软件混音的长流，但 Direct 本身不能保证后续 HAL、DSP 和硬件处理保持 bit-perfect。
2. **Offload**：接受压缩码流（MP3/AAC 等），数据绕过软件混音直达 HAL/DSP 解码。线程按 DSP 节奏经 drain 等待对齐，适合低功耗音乐播放。
3. **传统 MixerThread**：把任意配置的 PCM 轨重采样、混音成统一输出。

三者的最终选择由策略引擎按 usage 与流属性决定，应用 flag 只是偏好表达。

**Q8: [learning] 一条流最终音量由哪几层叠加？铃声静音模式为什么只静一部分流？**

音量逐层相乘，共三层：

1. **应用 per-track 音量**：`AudioTrack.setVolume()`，只作用于本音轨。
2. **stream 级音量与 mute**：每条输出线程维护 `mStreamTypes[stream]` 的 volume 与 mute（setStreamVolume/setStreamMute 核对）。
3. **master mute**：整条输出线程静音。

"铃声随静音模式消失、媒体继续出声"落在 stream 级 mute。system_server 的 AudioService 按 ringer mode 对相应 stream 下发 mute，AudioFlinger 只执行，不理解"静音模式"语义。

另有一个独立开关。checkSilentMode_l 读取 `ro.audio.silent`，非 0 时直接置 master mute（日志为 "Silence is golden"）。该属性对 REMOTE_SUBMIX 输出刻意不生效（Threads.cpp 核对），因此投屏、录制回环等远程子混音路径不会被此属性静音。

**Q9: [learning] 音效链以什么为单位组织？session 效果与设备级效果差在哪？**

效果链按挂点分三类，影响范围不同：

1. **session 链**：以（输出线程，audio session id）为单位，同 session 的效果串成链，位置在混音输出之后，只影响本 session 的音轨（Effects.h 注释核对 "EffectChain represents a group of effects associated to one audio session"）。
2. **aux 效果**：EffectModule 为它提供独立输入 buffer，各音轨把信号累积进去（Effects.h 核对）。这是"多轨共享一个效果输入"的特殊形态。
3. **设备级效果**：由 DeviceEffectManager 管理，挂在输出设备而非 session 上，影响该设备的全部输出（文件核对）。

使用含义：应用创建 AudioEffect 时指定 session id 即挂 session 链，多音轨共享同一链。均衡器等全局行为要走设备效果路径，挂在单个 session 上无法覆盖全局输出。

**Q10: [learning] AudioTrack 的 MODE_STATIC 与 MODE_STREAM 差在哪？什么场景选 STATIC？**

两种传输模式决定数据怎么进共享内存（AudioTrack.java 常量核对）：

1. `MODE_STREAM`（值 1）：生命周期内持续 `write()` 把 PCM 推进共享内存环形缓冲，适合时长未知的长流。
2. `MODE_STATIC`（值 0）：play 前一次性把全部音频写入共享内存，之后只控制回放（循环、定位），不再逐次供数。

STATIC 适合 UI 短音效、按键音这类时长已知、体量小、重复播放的素材，收益是省去持续供数的管理。它不是低延迟机制，静态音轨仍可能走普通混音线程。低延迟要看 FAST 槽位是否接纳，以及 MMAP 路径是否可用。

**Q11: [learning] 录音路径与播放路径怎么对称？哪些控制不归 AudioFlinger 管？**

数据面结构部分对称：AudioRecord 经 AudioFlinger 打开输入流进入 RecordThread，对应播放侧的 MixerThread。短周期采集有 FastCapture，对应播放侧 FastMixer。内存映射录音走 MmapCaptureThread（Threads.h 类清单核对），PCM 同样经共享内存 FIFO 与客户端交换。

录音策略控制与播放路径不对称：

1. **策略决策**：录音并发限制、隐私指示和按 uid 授权由 Java 层 AudioService 与 AppOps 等组件处理。
2. **AudioFlinger 输入**：AudioFlinger 处理已获准打开的输入请求。如果请求在策略层被拒绝，AudioFlinger 不会留下对应的活动录音轨。

排查录音问题先分两类：

1. **采集路径问题**：检查 RecordThread 状态和输入设备连接。
2. **策略拒绝**：检查权限和并发配额。被策略拒绝的请求不会形成 AudioFlinger 活动录音轨。

**Q12: [learning] dumpsys media.audio_flinger 与 dumpsys audio 分别回答什么问题？**

两个 dumpsys 各管一面（服务注册名核对）：

1. `dumpsys media.audio_flinger`（AudioFlinger，数据面）：每个输出/输入线程的当前配置（采样率、格式、flags）、tracks 列表（活动状态、音量、underrun 计数）与效果链——回答"声音现在处于什么数据状态"。
2. `dumpsys audio`（AudioService，策略面）：设备与路由、各 stream 音量与 mute、焦点请求与 players 登记——回答"系统为什么这么路由与判定"。

联合用法：无声问题先看 AudioFlinger 对应线程有没有 active track、underrun 是否增长，以区分"没送数据"与"送了没出声"。再看 AudioService 中该流的焦点是否被拒，以及路由是否落在预期设备。只看一边会把策略问题误判成数据问题，或反之。

**Q13: [learning] MixerThread 一个混音周期内部经过哪几块缓冲？**

MixerThread 的混音结果按效果处理情况经过三块缓冲，最终写入 HAL：

1. `mMixerBuffer`：AudioMixer 将各普通音轨的处理结果累加到此缓冲。
2. `mEffectBuffer`：有需要经过 session 效果链时，效果输入和输出会使用效果缓冲。无效果路径不必经过它。
3. `mSinkBuffer`：按输出格式整理最终样本，再交给 HAL 输出流。

AudioMixer 的单轨处理会根据音轨配置选择重采样、音量处理和格式转换等 hook，总混音阶段再把各轨结果累加。具体缓冲是否逐一复制取决于输出线程和效果路径。不能把箭头图理解成每个周期都无条件完整拷贝三次。

**Q14: [learning] MixerThread 的 prepareTracks_l 如何准备每条待混音音轨？**

prepareTracks_l 在每轮混音前检查轨道状态，并为可参与本轮的普通音轨配置 AudioMixer 所需参数。它不负责读取应用策略或替 fast track 混音。

1. **检查轨道**：筛除未就绪或不应在本轮混音的 track。fast track 交由 FastMixer 的独立路径处理。
2. **配置混音输入**：对就绪轨道设置左右声道音量、采样率、声道信息，以及主混音或 aux 效果缓冲地址。
3. **观察欠载**：轨道数据不足时，本轮可混帧数受限，相关 underrun 计数会反映供数不足。
4. **等待下一轮**：无活动轨道达到空闲条件后，线程可进入 standby 并等待条件变量唤醒。音轨重新活动后，线程再恢复工作。

源码线索：AudioFlinger 的 Threads.cpp 中 prepareTracks_l、MixerThread standby 和线程名生成处。输出线程名形如 `AudioOut_%X`，录音线程名形如 `AudioIn_%X`。例如 `AudioOut_13` 中的 13 是十六进制线程标识。

**Q15: [learning] FastMixer 和普通混音线程怎么协作？MonoPipe/NBAIO 是什么？**

FastMixer 是 AudioFlinger 中独立的高优先级输出线程。它把应用 fast track 与普通混音线程送来的子混音合并，再写入输出设备。

1. **MonoPipe**：普通混音线程通过它向 FastMixer 发送子混音。
2. **NBAIO**：Non-Blocking Audio I/O 抽象提供非阻塞音频数据通道，MonoPipe 是其一种实现。
3. **StateQueue**：在线程间传递 FastMixer 状态和命令。
4. **fast track**：由 FastMixer 在较短周期直接处理。可用槽位受线程初始化、输出能力和 fast-track 接纳条件限制。
5. **协作收益**：普通混音线程可以按较长周期工作，FastMixer 仍可按低延迟周期输出，两个周期不必完全一致。

源码线索：Threads.cpp 中的 MonoPipe、MonoPipeReader 和 mPipeSink 成员，以及 FastMixerState 中的 track 槽位约定。同步实现细节应以对应平台分支的 StateQueue 代码为准，不能仅凭社区文章推广到所有版本。

**Q16: [learning] 普通混音线程上音轨采样率与输出不一致会被拒绝吗？"混音器只接受不超过输出 2 倍采样率"的说法对吗？**

普通 MixerThread 会把普通音轨重采样到输出采样率，因此输入采样率和输出采样率不同本身不会导致拒绝。Android 13 的源码以 `AUDIO_RESAMPLER_DOWN_RATIO_MAX` 限制过高的输入/输出比例，该常量为 256。“只接受输出采样率 2 倍以内”不是这段代码的约束。

排查时先确认实际输出线程。普通混音线程可重采样，Direct 输出则要求音轨格式与输出配置满足该路径的兼容条件。遇到变速或杂音，再核对采样率和重采样配置。比例上限是实现约束，不代表任意设备都能以极端比例保持可接受音质。

**Q17: [learning] AAudio MMAP 路径的共享缓冲内部是什么结构？服务端由哪些组件组成？**

AAudio 的 MMAP 数据面通过共享内存缓冲区和帧索引交换音频数据，控制面仍由 Binder 等服务路径处理。MMAP 减少的是数据传输路径中的拷贝与调度开销，不会消除建流、断连和路由管理所需的控制通信。

1. **共享 FIFO**：FifoBuffer 与 FifoControllerBase 管理共享环形缓冲区中的帧位置和读写索引。客户端与服务端据此交换音频帧。
2. **服务端组件**：AAudioService 接收服务请求，AAudioEndpointManager 查找或复用 MMAP 端点，AAudioClientTracker 跟踪客户端并在客户端死亡时清理资源。
3. **控制面**：建流、断连和路由变化仍需通过 Binder 与 AudioFlinger、AudioPolicy 等组件协调。
4. **路径边界**：是否使用 MMAP 取决于设备策略、HAL 能力和流配置。AAudio 在 MMAP 不可用时的行为还取决于客户端请求和平台实现，不能仅凭 audioserver 是否启动 AAudioService 推断一定会自动回退。

**Q18: [learning] AudioTrack.getTimestamp() 返回的帧位置和时间怎样解释？什么时候拿不到？**

AudioTimestamp 将音轨帧位置与单调时钟时间配对，表示该帧已经播放或已提交播放时的估计时间。它是尽力估计，无法计入系统实现未知的后级延迟，因此不等同于声波到达听者的时刻。

1. **帧位置范围**：AudioTrack 时间戳中的 framePosition 只有低 32 位有效，并按帧回绕，语义类似 getPlaybackHeadPosition。
2. **暂时不可用**：音频时钟尚未稳定，或路由切换期间及切换后，getTimestamp 可能返回 false。可以定期重试，直到帧位置开始推进或确认当前路由不支持时间戳。
3. **路由不支持时间戳**：该路由可能一直无法提供 timestamp。应用可以使用 getPlaybackHeadPosition 获得近似帧位置，但仍须另外估算音频处理管线之后的延迟。
4. **排查音画同步**：先确认读到的帧位置是否持续推进，再核对所用时钟基准与设备、传输路径的额外延迟。

依据：Android AudioTrack 与 AudioTimestamp API 文档。

**Q19: [learning] 无声问题想确认"系统内部哪一段开始没数据"，AudioFlinger 有什么抓音手段？**

Tee Sink 是 AudioFlinger 的调试功能，可保留混音链若干位置的短音频片段，供开发者比较数据在哪个阶段变成静音或失真。它默认关闭，启用需要自定义编译和运行时配置，并且只适合 userdebug/eng 等调试环境。音频文件可能包含敏感内容，分析和分享时必须按调试数据处理。

1. **启用条件**：按 AOSP Audio debugging 文档打开编译期 `TEE_SINK`，在可调试构建上设置运行时属性。仅修改运行时属性不足以启用未编入的代码。
2. **比较片段**：复现问题后提取对应阶段的 PCM 文件，比较应用预期数据与 AudioFlinger 各抓取点，缩小无声或失真的边界。
3. **替代证据**：量产设备无法使用 Tee Sink 时，先结合应用写入结果、AudioFlinger track/underrun 状态和 logcat 定位问题层级。
