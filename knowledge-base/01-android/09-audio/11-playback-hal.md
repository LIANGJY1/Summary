# 播放数据与 HAL：PCM 怎样变成车内声音

> 本册把播放器、AudioFlinger、策略服务、Audio HAL 和 AudioControl HAL 放在一条链上。描述以 Android 13/AAOS 13 常见 PCM 播放为主。Direct、Offload、MMAP 是条件路径，不能假设每台车都使用。

**Q1: 点击音与音乐用什么 API 播放，为什么“申请到焦点”仍不等于“播放器准备好了”？**

焦点请求只管理逻辑播放权，播放器还要完成资源加载、解码/缓冲、音轨创建与写入。短 UI 音效优先复用系统音效。自定义短且频繁的 PCM 音效适合预加载 SoundPool。媒体音乐用 Media3/MediaPlayer 等长时播放器，底层通常经 AudioTrack 送样本。每个播放器都要设置与焦点请求一致的 AudioAttributes。

1. **系统 UI 音效**：`AudioManager.playSoundEffect()` 按系统声音类型和用户设置播放，适用于平台已有的点击/导航反馈，不适合任意音频文件。
2. **SoundPool**：为短音样本的低延迟、重叠播放设计。`load()` 是异步加载，须等加载完成再 `play()`。`play()` 返回 stream ID 可用于停止或调单个实例。它没有与每次播放对应的“播放完成”回调，因此自建焦点会话需要额外管理结束时机。
3. **AudioTrack**：应用可显式写 PCM，适合需要精确控制格式、缓冲和时序的场景。`MODE_STATIC` 可预置短 PCM，`MODE_STREAM` 适合持续推流。请求 FAST 并不保证获得快速轨。
4. **长媒体播放器**：负责容器/解码、缓冲与生命周期。播放器实际开始、暂停、结束仍由应用跟随焦点结果和回调控制。

来源：[SoundPool API](https://developer.android.com/reference/android/media/SoundPool)、[AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack)、[AudioManager 音效 API 的 Android 13 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/AudioManager.java)。

**Q2: Audio HAL 与 AudioControl HAL 听起来很像，职责如何划分？**

Audio HAL 承载音频设备与流的数据接口，AudioControl HAL 承载车载专用控制信号。二者通常由车厂供应链协同实现，但不能在架构图里画成一个“焦点管理器”。

1. **Audio HAL**：暴露设备、端口、输出/输入流能力，将 AudioFlinger 写出的音频数据送到硬件。bus 地址和实际通道必须可对上。
2. **AudioControl HAL**：接收车载 duck/mute、gain 变化等控制信息，并允许 Android 外部的车身声音请求焦点。它不替应用决定 AudioFocusRequest 的语义。CarAudioFocus 才执行 Android 侧矩阵裁决。
3. **外部声音**：例如车辆系统在 Android 之外生成提示音，可通过 HAL 焦点回调进入同一车机焦点体系。PCM 本身未必经过 AudioFlinger。排查“音乐突然被压低但找不到 Android 播放器”时要看这条入口。

来源：[AudioControl HAL](https://source.android.com/docs/automotive/audio/audio-control-hal)、[AAOS 音频概览](https://source.android.com/docs/automotive/audio)。

**Q3: 音乐和导航已经“并发持焦”，什么时候在 Android 混合，什么时候在车载 DSP 混合？**

焦点并发不指定混音位置。两路流若被策略路由到同一普通 AudioFlinger 输出，通常先在 Android 混成一路。若去不同 bus，HAL/DSP 能分别处理增益并在下游混合，也可能输出至不同扬声器。Direct/Offload 等路径有额外设备能力与并发约束，不能从应用 API 直接推出最终路径。

1. **需要单独 duck**：优先验证音乐与提示是否有可分别调增益的 bus。否则给 HAL 一个“降低媒体”的目标，可能只能降低混合后的整路声音。
2. **需要精确同步**：记录每路播放器时间戳与输出设备延迟。不同 bus/DSP 通道可能有不同缓冲，焦点同一时刻授予不等于声波同一时刻到达。
3. **需要低延迟**：SoundPool/AudioTrack/AAudio 只是入口。FAST/MMAP 是否可用取决于 AudioPolicy、HAL 配置、采样格式、缓冲和设备负载。必须测量点击到实际出声的端到端延迟。

来源：[AAOS 焦点并发与路由建议](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 音量与 duck](https://source.android.com/docs/automotive/audio/volume-management)、[Android 音频延迟](https://source.android.com/docs/core/audio/latency)。

**Q4: 为什么“duck 指令发了，媒体却没变小”要查 HAL 版本和配置？**

CarDucking 计算的是建议给硬件的设备地址列表，不是直接修改任意应用的播放器音量。AAOS 13 需要启用 `audioUseHalDuckingSignals`，AudioControl HAL 实现对应接口，地址能匹配实际输出，DSP 还要执行衰减。缺任一环节都可能持焦关系正确、播放也正常，却听不到 duck 效果。

检查次序为 CarAudioFocus 双持焦 → CarDucking 目标地址 → CarService 开关 → AudioControl HAL 收到的通知 → 实际 bus/功放增益。HIDL 1.0 与支持 duck/mute 的 2.0/AIDL 能力不同。不要把新文档描述的 AIDL v2 元数据接口倒写成 AAOS 13 的必备接口。来源：[AudioControl HAL 版本与 duck](https://source.android.com/docs/automotive/audio/audio-control-hal)、[AAOS 13 CarDucking](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarDucking.java)。
