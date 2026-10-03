# 音效与音源决策：先决定声音的语义

> AAOS 13 专题学习资料，按问题递进阅读。此册回答“点击一下要出声，会不会与音乐混声”。“音源”在车机项目中可能指应用、媒体来源、物理输入或音频 bus，排查时先说清所指。源码依据为公开 AOSP `android13-release` 分支，当前工作环境没有仓库地图中所列的本地 AAOS13 学习副本，因此未声称已按本地源码核对。焦点建议仍须以目标车型的产品策略和 AudioControl HAL 实现验收。

**Q1: 用户点击车机按钮播放 100 ms 音效，为什么不能先问“该用哪个播放器”？**

先确定这段声音的语义、目标音区及它对正在播放的声音的影响。播放器只决定怎样提交采样数据。AudioAttributes 决定系统怎样识别声音，焦点决定逻辑播放权，音频策略决定去哪个设备，硬件决定最终如何混合或降音量。同样是 100 ms，普通触摸反馈、导航提醒、告警会走不同规则。

1. **普通 UI 触摸反馈**：用 `USAGE_ASSISTANCE_SONIFICATION`，在 AAOS 13 默认 CarAudioContext 中归为 `SYSTEM_SOUND`。优先考虑系统已有的 `AudioManager.playSoundEffect()` 或 View 的系统反馈能力，尊重用户的音效设置。自定义音效才另选 SoundPool 等播放器。
2. **导航语音或路线提示**：用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，归为 `NAVIGATION`。它要和媒体并发还是让媒体暂停，取决于焦点请求、媒体持有者的选择和车机策略。
3. **媒体内容或音乐**：用 `USAGE_MEDIA`，归为 `MUSIC`，通常为持续播放申请 `AUDIOFOCUS_GAIN`。
4. **安全、紧急声音**：须由有权限的系统/车厂组件按对应 system usage 与产品安全设计接入。不能把普通点击声伪装成高优先级音频来抢占。

来源：[AudioAttributes API](https://developer.android.com/reference/android/media/AudioAttributes)、[AAOS 音频配置中的 usage→context 表](https://source.android.com/docs/automotive/audio/audio-policy-configuration)、[AAOS 13 CarAudioContext 源码](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioContext.java)。

**Q2: 点击音效与音乐“混声、压低、暂停、拒绝”分别是哪层决定的？**

四个结果是不同阶段的判断，不能只凭一个 focus 返回值推断最终听感。

1. **焦点裁决**：如果音效申请焦点，CarAudioFocus 在目标音区以当前持焦 context 为行、音效 context 为列查 FocusInteraction 矩阵。它可授予并发、使旧持有者丢焦、拒绝请求或延迟授予。没申请焦点的声音不参加这次裁决。焦点并非阻止 PCM 出声的硬门禁。
2. **应用响应**：旧持有者收到 loss 后应暂停、停止或按规则降音量。`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 仅表达允许对方继续以较低音量播放，不保证车机一定混声。
3. **路由与混音**：两条流若去同一个 Android 输出，由 AudioFlinger 混合成一条输出。若去不同 bus，可由 HAL/DSP 在混音前分别控制增益，也可能分配到不同扬声器。
4. **设备增益**：AAOS 可把待 duck 的 bus 地址交给 AudioControl HAL。HAL 的实际衰减量、渐变和扬声器布局由车型实现决定。只看焦点成功无法证明用户听到了音效。

来源：[AAOS 焦点交互与并发条件](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 音量与 ducking](https://source.android.com/docs/automotive/audio/volume-management)、[AAOS 13 焦点矩阵源码](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)。

**Q3: 媒体正在播放时，一个普通 `SYSTEM_SOUND` 点击音默认会抢焦还是共存？**

在 AAOS 13 默认矩阵中，当前持焦者为 `MUSIC`、新请求为 `SYSTEM_SOUND` 的单元格是 `CONCURRENT`。但要实际维持双持焦，音效新请求还须使用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，且媒体持有者没有要求 duck 时暂停、没有要求接收车机 duck 事件。否则 CarAudioFocus 把媒体列为焦点失去者。若音效改用 `AUDIOFOCUS_GAIN`，旧媒体可能收到永久 loss，显然不适合普通点击声。目标区里还有第三个持焦者时，必须逐个比较，任何拒绝都可让请求失败。

1. **系统自带点击反馈**：Android 13 的 `AudioManager.playSoundEffect()` 经 AudioService 交给 SoundEffectsHelper，以 `USAGE_ASSISTANCE_SONIFICATION` 播放。该路径没有为每次音效显式调用 `requestAudioFocus()`，因此不会因这次点击去查焦点矩阵。它仍受音效设置与实际路由/音量影响。
2. **自定义 UI 点击声**：若产品允许与媒体共存，以 `USAGE_ASSISTANCE_SONIFICATION` 和短时 MAY_DUCK 请求表达意图。短促点击应避免每按一次就令音乐永久失焦。是否每次单独请求、或者由一个连续交互会话持焦，要按车厂策略和实际延迟测试决定。
3. **对媒体的衰减**：只有 `SYSTEM_SOUND` 确实持焦，CarDucking 才会把它纳入计算。AAOS 13 默认规则将其列为可 duck `MUSIC` 的 context，但同一 bus 上若还有未被 duck 的持焦 context，整台设备不会被 duck。普通 `playSoundEffect()` 不因一次点击触发这项焦点驱动的 duck。官方也把触摸交互提示的体验列为车型应特别考虑的例外，不能把默认规则当成最终产品规范。

来源：[AAOS 13 FocusInteraction](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)、[AAOS 13 CarAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)、[Android 13 SoundEffectsHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/SoundEffectsHelper.java)、[AAOS 13 CarDuckingUtils](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarDuckingUtils.java)、[音量与 ducking 建议](https://source.android.com/docs/automotive/audio/volume-management)。

**Q4: 为什么“焦点授予”仍可能无声，“焦点失败”却可能听见声？**

焦点是协作式控制协议，不是音频数据的硬开关。授予表示系统允许逻辑声音开始，但播放器可能未加载、数据未写入、路由落错音区、音量组静音、HAL 未输出。失败后若应用违约继续调用 play，也可能有 PCM 送进混音器。车机应以焦点结果控制应用行为，但安全关键的声音不能仅靠其他应用自觉遵守焦点来保证可听。

验证时分别取证：焦点结果与持有者、播放器状态、策略路由与 bus、设备/组增益、最终扬声器输出。来源：[AAOS 音频焦点文档的非强制边界](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 音频概览](https://source.android.com/docs/automotive/audio)。

**Q5: 车机界面里的“当前音源”与 AudioFocusInfo 的“当前持焦者”为什么不一定相同？**

界面音源通常指用户选中的媒体内容提供者，例如蓝牙音乐、广播、USB 音乐或在线播放应用。焦点持有者指此刻获准主导某段声音的逻辑请求。导航、通话、点击音可临时持焦，却不应改写用户选中的媒体来源。媒体应用还可能已被选中但暂停或因通话未获焦点。

1. **媒体源选择**：由车机媒体体验层及 MediaSession/媒体应用管理“当前浏览/播放哪个来源”，用于媒体界面与媒体按键目标。
2. **音频焦点**：由 AudioManager 请求、CarAudioFocus 裁决“谁可在何时发声”，可能同区多名并发持焦。
3. **活动播放**：由播放器状态与实际 PCM 数据决定。已持焦不等于正在输出。无焦点的违约应用也可能仍有音轨。

排查“音源显示蓝牙，喇叭却在播导航”时应分别记录媒体源、焦点持有者与活动 playback，不要把三种状态压成一个布尔。来源：[AAOS 音频概览](https://source.android.com/docs/automotive/audio)、[AAOS 媒体命令与 CarMediaService](https://source.android.com/docs/automotive/voice/voice_interaction_guide/fulfilling_commands)、[AAOS 音频焦点](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q6: FM 收音机等 Android 外部音源如何参与焦点，是否必须把模拟/数字音频送进 AudioFlinger？**

外部媒体源应由 Android 应用代表，替它申请焦点并处理媒体按键。PCM 是否经过 Android 混音是另一项硬件方案选择。广播调谐器可通过 HwAudioSource/音频 patch 接入 Android 路由，也可在车载硬件下游混合。若选择后者，应用仍要在焦点变化时控制外部源，HAL 也须让车机知道它正在出声，否则媒体和外部音源可能互相盖住。

1. **Android 媒体源**：应用提供媒体会话、管理播放状态与媒体键，并用 `USAGE_MEDIA` 等真实用途请求焦点。
2. **音频 patch 路径**：HwAudioSource 可连接外部输入设备与输出路径，让路由和媒体会话更容易协同。必须有策略配置与 HAL 设备能力支持。
3. **完全外部混音**：样本可能不进入 AudioFlinger，仍可通过应用代理焦点或 AudioControl HAL 外部焦点入口参与车机协作。安全提示音的硬件优先级最终由车辆系统保证。

来源：[AAOS 音频概览的外部媒体源建议](https://source.android.com/docs/automotive/audio)、[AAOS 连接输入设备与 HwAudioSource](https://source.android.com/docs/automotive/audio/optional-player)、[AudioControl HAL 外部焦点](https://source.android.com/docs/automotive/audio/audio-control-hal)。
