# AOSP 音频子系统

> 学习资料（文章模式沉淀）。主线：audioserver 守护进程与三个服务、AudioFlinger 输出线程选型、Direct/Offload 接受约束、AudioPolicyService 策略引擎与配置、音量与静音的分层落点、混音管线与 FastMixer/NBAIO、效果链挂点、AudioTrack 传输模式、AAudio 服务端内部、录音路径与 dumpsys/TeeSink 排查。机制按本地 AAOS13 源码（Android 13）核对；HAL 版本演进（HIDL 2.0–7.1、Android 14+ AIDL `IFactory`/`IModule`）为官方文档结论（2026-09 检索），本地源码树未含 `hardware/interfaces/audio`。应用侧延迟、AAudio 与 offload 实践见 [04-audio-latency.md](04-audio-latency.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: audioserver 为什么独立成进程？里面固定跑哪些服务，AAudioService 为什么是"条件启动"？**

audioserver 是独立的 native 守护进程，固定运行 AudioFlinger（混音引擎）与 AudioPolicyService（路由策略），另启动 MediaLogService；AAudioService 只有在系统 MMAP 策略明确允许时才启动（main_audioserver.cpp 核对）。音频单独成进程是为了隔离故障域：混音引擎崩溃重启不会拖垮编解码、相机等其他媒体服务。

进程由 init 按 audioserver.rc 守护，三个关键配置：

- **实时 I/O**：`ioprio rt 4`，混音写入不被普通 I/O 阻塞。
- **阻止挂起**：`capabilities BLOCK_SUSPEND`，播放持锁期间系统不能进入挂起，避免断流。
- **专用用户**：`user audioserver`，按最小权限运行。

AAudioService 的条件启动在 main_audioserver.cpp：先经 `AudioSystem::get_audio_flinger()` 查询系统 MMAP 策略，允许才 instantiate，源码注释言明这是防止客户端在不支持的设备上误用 AAudioService。因此"audioserver 里没有 AAudioService"本身就意味着 MMAP 低延迟路径不可用，AAudio API 只能回退到传统混音路径。

**Q2: audioserver 或音频 HAL 崩溃后系统怎么恢复？应用侧会感知到什么？**

init 负责拉起：audioserver 异常退出后 init 自动重启该服务；audioserver.rc 的 `onrestart restart vendor.audio-hal` 等条目让音频 HAL 一同重启，使服务端两侧状态一致。为避免 audioserver 与 HAL 互相拉起形成重启循环，rc 用 `sys.audio.restart.hal=1` 属性做一次性收敛——先 stop 全部音频 HAL 再 start（源码注释引 b/159966243）；audioserver 停止时也先停 HAL。

应用侧的感知是操作返回 `DEAD_OBJECT`：audioserver 死亡意味着 AudioTrack/AudioRecord 的服务端音轨消失，AudioTrack 在 start、obtainBuffer、getPosition 等路径捕获 `DEAD_OBJECT` 后调 `restoreTrack_l()`，在重启后的服务里重建音轨（AudioTrack.cpp 多处恢复分支核对）。恢复成功播放可继续，但崩溃到重建之间的声音不补播；应用若忽略错误码，表现就是"播着播着没声"。

**Q3: 一条流从 AudioAttributes 到具体设备，路由决策在哪完成？两个策略引擎差在哪？**

决策在 audioserver 内的 AudioPolicyService：它持有 AudioPolicyManager 与策略引擎，把应用的 AudioAttributes（usage、content type）映射到产品策略，再结合设备连接状态为该策略选择输出设备与输出配置档；AudioFlinger 只执行选出的路径，不参与选择。Android 13 提供两个引擎实现，选哪套由构建配置决定：

- **enginedefault**：策略映射与设备选择编译进代码。
- **engineconfigurable**：由 audio_policy_engine_configuration.xml 驱动，产品可用 XML 定制策略、设备与音量曲线；配置示例分别在 `frameworks/av/services/audiopolicy/enginedefault/config` 与 `engineconfigurable/config`（目录核对）。

排查意义：要改"某用途走某设备/某配置档"，动的是引擎 XML（configurable 引擎）或 framework 默认逻辑，改 AudioFlinger 改不动选择。

**Q4: audio_policy_configuration.xml 的四个顶层标签各描述什么？**

这是 HAL 能力声明文件，四个标签各回答一个问题（primary 配置样例核对）：

- **`modules`**：有哪些音频 HAL 实例，各自带 halVersion。
- **`mixPort`**：软件侧流端点——`role="source"` 的输出混音流或 `role="sink"` 的输入流；`flags` 决定输出配置档，如 primary output 带 `AUDIO_OUTPUT_FLAG_PRIMARY`。
- **`devicePort`**：物理或虚拟端点，`type` 如 `AUDIO_DEVICE_OUT_SPEAKER`，`address` 区分同型多实例。
- **`route`**：mix 到 device 的合法连接，如 primary output 连到 Speaker。

引擎能选的设备 = 本文件声明的端点 ∩ 当前已连接集合；车机的 audio bus 同样在这里声明为 BUS 型 devicePort，之后才能被选中。版本背景：HIDL 时代接口为 2.0–7.1 的 `IDevicesFactory`/`IDevice`，7.0 起统一了框架与 HAL 的数据模型；Android 14 起官方改用 AIDL，接口为 `android.hardware.audio.core` 的 `IFactory`/`IModule`（官方文档结论，2026-09 检索）。

**Q5: AudioFlinger 按输出 flags 选混音线程，分支是什么？deep_buffer 为什么没有专属线程类？**

openOutput 创建线程时按 flags 顺序判断（AudioFlinger.cpp 分支核对）：

- **`AUDIO_OUTPUT_FLAG_MMAP_NOIRQ`**：建 MmapPlaybackThread，客户端直写映射缓冲。
- **`AUDIO_OUTPUT_FLAG_SPATIALIZER`**：建 SpatializerThread，空间音频输出。
- **`AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD`**：建 OffloadThread，压缩码流交 DSP 解码。
- **`AUDIO_OUTPUT_FLAG_DIRECT`**：建 DirectOutputThread，不经软件混音。
- **其余（含 `PRIMARY`、`DEEP_BUFFER`）**：建 MixerThread，通用软件混音。

`AUDIO_OUTPUT_FLAG_DEEP_BUFFER` 没有专属分支——它落在 MixerThread，只是选择了缓冲更长的输出配置档，用更高延迟换对混音周期抖动的容忍，软件混音器是同一个实现。由此得到排查规则：flag 决定"用哪个配置档"，线程类型决定"数据怎么被混"，判断混音行为要看线程类型；primary 与 deep-buffer 是两个独立的 openOutput 实例（各自一条线程），互不混音。

**Q6: Direct 与 Offload 输出接受音轨的约束，和传统混音差在哪？**

三种输出的数据路径与接受条件不同：

- **Direct**：不做软件混音也不重采样，建 track 时要求音轨的采样率、格式、声道掩码与输出配置完全一致（Threads.cpp 两处 `sampleRate != mSampleRate || format != mFormat || channelMask != mChannelMask` 比较核对），不一致即失败；适合 bit-perfect 的长流，不适合采样率各异的海量游戏短音效。
- **Offload**：接受压缩码流（MP3/AAC 等），数据绕过软件混音直达 HAL/DSP 解码，线程按 DSP 节奏经 drain 等待对齐；适合低功耗音乐播放。
- **传统 MixerThread**：把任意配置的 PCM 轨重采样、混音成统一输出。

三者的最终选择由策略引擎按 usage 与流属性决定，应用 flag 只是偏好表达。

**Q7: 一条流最终音量由哪几层叠加？铃声静音模式为什么只静一部分流？**

音量逐层相乘，共三层：

- **应用 per-track 音量**：`AudioTrack.setVolume()`，只作用于本音轨。
- **stream 级音量与 mute**：每条输出线程维护 `mStreamTypes[stream]` 的 volume 与 mute（setStreamVolume/setStreamMute 核对）。
- **master mute**：整条输出线程静音。

"铃声随静音模式消失、媒体继续出声"落在 stream 级 mute：判定在 system_server 的 AudioService（Java 层策略），它按 ringer mode 对相应 stream 下发 mute；AudioFlinger 只执行，不理解"静音模式"语义。另有一个独立开关：checkSilentMode_l 读 `ro.audio.silent` 属性，非 0 直接置 master mute（日志 "Silence is golden"），且对 REMOTE_SUBMIX 输出刻意不生效（Threads.cpp 核对）——投屏、录制回环这类走远程子混音的路径不会被该属性静音。

**Q8: 音效链以什么为单位组织？session 效果与设备级效果差在哪？**

效果链按挂点分三类，影响范围不同：

- **session 链**：以（输出线程，audio session id）为单位，同 session 的效果串成链，位置在混音输出之后，只影响本 session 的音轨（Effects.h 注释核对 "EffectChain represents a group of effects associated to one audio session"）。
- **aux 效果**：EffectModule 为它提供独立输入 buffer，各音轨把信号累积进去（Effects.h 核对），是"多轨共享一个效果输入"的特殊形态。
- **设备级效果**：由 DeviceEffectManager 管理，挂在输出设备而非 session 上，影响该设备的全部输出（文件核对）。

使用含义：应用创建 AudioEffect 时指定 session id 即挂 session 链，多音轨共享同一链；想要均衡器这类全局行为要走设备效果路径——挂在单个 session 上做不到全局。

**Q9: AudioTrack 的 MODE_STATIC 与 MODE_STREAM 差在哪？什么场景选 STATIC？**

两种传输模式决定数据怎么进共享内存（AudioTrack.java 常量核对）：

- **`MODE_STREAM`（值 1）**：生命周期内持续 `write()` 把 PCM 推进共享内存环形缓冲，适合时长未知的长流。
- **`MODE_STATIC`（值 0）**：play 前一次性把全部音频写入共享内存，之后只控制回放（循环、定位），不再逐次供数。

STATIC 适合 UI 短音效、按键音这类时长已知、体量小、重复播放的素材，收益是省去供数节奏管理。注意它不是低延迟机制：静态音轨仍走普通混音线程；低延迟要看 FAST 槽位接纳与 MMAP 路径是否成立。

**Q10: 录音路径与播放路径怎么对称？哪些控制不归 AudioFlinger 管？**

数据面结构对称：AudioRecord 经 AudioFlinger 打开输入流进 RecordThread（对应播放侧 MixerThread 的角色），短周期采集有 FastCapture（对应 FastMixer），内存映射录音走 MmapCaptureThread（Threads.h 类清单核对），PCM 同样经共享内存 FIFO 与客户端交换。

策略控制不对称：录音并发限制、隐私指示、按 uid 的授权都在 Java 层（AudioService 与 AppOps）完成；AudioFlinger 只见到已放行的输入请求，拒绝发生在这之前。排查录音问题先分两类：

- **采集路径问题**：RecordThread 状态、输入设备连接。
- **策略拒绝**：权限、并发配额——拒绝原因不会出现在 AudioFlinger 侧。

**Q11: dumpsys media.audio_flinger 与 dumpsys audio 分别回答什么问题？**

两个 dumpsys 各管一面（服务注册名核对）：

- **`dumpsys media.audio_flinger`**（AudioFlinger，数据面）：每个输出/输入线程的当前配置（采样率、格式、flags）、tracks 列表（活动状态、音量、underrun 计数）与效果链——回答"声音现在处于什么数据状态"。
- **`dumpsys audio`**（AudioService，策略面）：设备与路由、各 stream 音量与 mute、焦点请求与 players 登记——回答"系统为什么这么路由与判定"。

联合用法：无声问题先看 AF 对应线程有没有 active track、underrun 是否增长——区分"没送数据"与"送了没出声"；再看 audio 里该流的焦点是否被拒、路由是否落在预期设备。只看一边会把策略问题误判成数据问题，或反之。

**Q12: MixerThread 一个混音周期内部经过哪几块缓冲？prepareTracks_l 在做什么？**

普通混音线程内三块缓冲接力：`mMixerBuffer` → `mEffectBuffer` → `mSinkBuffer`（三个成员在 Threads.cpp 大量使用，核对）。混音由 AudioMixer 的两级 hook 执行——先为每条就绪音轨确定单轨 hook（重采样、音量定点乘法、格式转换），再由总 hook 串联调用；各轨结果叠加后转格式写入 mMixerBuffer，若 session 挂有效果链则经 mEffectBuffer，最后按输出格式复制到 mSinkBuffer 写向 HAL。prepareTracks_l 负责逐轨准备：跳过 fast track（它们由 FastMixer 处理），对数据就绪的 track 设置左右音量、采样率、声道与主/aux 缓冲地址，数据不就绪表现为欠载计数增长。线程无活动音轨超时后进入 standby（mWaitWorkCV 条件变量等待，音轨激活时唤醒；Threads.cpp 核对）。输出线程名为 `AudioOut_%X`、录音为 `AudioIn_%X`（Threads.cpp:2068/7543 核对）——trace 与 dumpsys 里看到的 `AudioOut_13` 就是 0x13 号输出线程。

**Q13: FastMixer 和普通混音线程怎么协作？MonoPipe/NBAIO 是什么？**

FastMixer 是 MixerThread 内部的独立高优先级线程：它把自己 fast track 的混音结果，与普通混音线程经 MonoPipe 送来的子混音结果再做一次混合后输出（源码结构与社区分析一致：Threads.cpp include MonoPipe/MonoPipeReader 并维护 mPipeSink）。NBAIO（Non-Blocking Audio I/O）是共享内存非阻塞管道抽象，MonoPipe 是单读者实现，充当普通混音线程到 FastMixer 的数据桥；两侧通过 StateQueue 传递状态与命令（StateQueue.cpp 存在核对，社区分析口径为 futex 同步）。fast track 槽位中索引 0 留给普通混音线程的子混音，其余供应用 fast track（与 03 册的接纳条件题互补）。收益：普通混音线程可以睡满整个周期、容忍调度抖动，fast 路径以更短周期独立运转，两不相拖。

**Q14: 普通混音线程上音轨采样率与输出不一致会被拒绝吗？"混音器只接受不超过输出 2 倍采样率"的说法对吗？**

不一致不是问题——MixerThread 对普通音轨统一重采样到输出采样率；上限检查是 `sampleRate > mSampleRate * AUDIO_RESAMPLER_DOWN_RATIO_MAX` 时拒绝（Threads.cpp:2525 核对），而该常量定义为 256（`AudioResamplerPublic.h`，另一侧 `AUDIO_RESAMPLER_UP_RATIO_MAX` 为 65536）。"只接受输出 2 倍以内"是社区流传的错误口径，与现行源码不符。真正的硬约束在 Direct 输出：完全一致才收（本册 Direct 条目）。排查变速/杂音时按这条链走：音轨采样率本身 → 是否命中 Direct 约束 → 混音线程的实际重采样路径，而不是怀疑"超过 2 倍被拒"。

**Q15: AAudio MMAP 路径的共享缓冲内部是什么结构？服务端由哪些组件组成？**

数据面是共享内存环形缓冲加读写索引：`FifoBuffer`/`FifoControllerBase`（frameworks/av/media/libaaudio/src/fifo/，核对）维护帧位置的换算，客户端直写、服务端直读，无逐缓冲拷贝与 Binder。服务端在 `frameworks/av/services/oboeservice/`：AAudioService 是 Binder 入口，AAudioEndpointManager 查找与复用 MMAP 端点，AAudioClientTracker 跟踪客户端死亡并清理资源（文件清单核对）。控制面（建流、断连、路由变化）仍走 Binder 与 AudioFlinger/AudioPolicy——MMAP 缩短的只是数据面。这也解释了"设备不支持 MMAP 时 AAudio 回退传统路径"（audioserver 条件启动 AAudioService 的设计）在结构上的对应：回退后数据面从共享 fifo 换回 AudioTrack 共享环形缓冲加混音线程。

**Q16: AudioTrack.getTimestamp() 返回的时间对怎么理解？哪些情况下拿不到？**

AudioTimestamp 是"帧位置 + 系统时间"的对：framePosition 对应的采样已在该 nanoTime 时刻送入 audio sink（javadoc 口径）——它是 sink 入口时间，不是喇叭出声时刻，蓝牙/扬声器还有后级延迟，做 A/V 对齐要另加后级预算。两种拿不到的情况（javadoc 核对）：音频时钟稳定期或路由切换前后**暂时**拿不到（getTimestamp 返回 false，可稍后重试恢复）；路由不支持时间戳时**永久**拿不到，只能用 `getPlaybackHeadPosition()` 取写侧近似位置。排查音画不同步时先确认拿的是时间戳还是 head position——后者偏大（含未播放数据），会系统性误判延迟。

**Q17: 无声问题想确认"系统内部哪一段开始没数据"，AudioFlinger 有什么抓音手段？**

Tee Sink：AudioFlinger 的编译期调试开关（Configuration.h 中 `TEE_SINK` 默认注释，AudioFlinger.cpp 有使用点，核对），开启后在混音链多个位置把最近一段音频落盘存档，复现无声/杂音时对各段对拍，定位数据从哪一段消失（官方调试文档口径）。它是 userdebug 重编才能用的线下手段；线上不可用，替代取证靠 dumpsys 的 underrun 计数与 track 状态、logcat 的 AudioTrack/AudioFlinger 日志。三级证据链建议：应用侧（写入返回值、自己的 buffer 状态）→ AudioFlinger 侧（dumpsys）→ Tee Sink 抓音对拍。
