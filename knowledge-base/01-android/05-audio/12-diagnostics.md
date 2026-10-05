# 全链路实验与排障：从点击到扬声器逐层取证

> 以 AAOS 13 可调试设备为对象。命令输出格式随分支与厂商修改而变。观察字段要结合设备实现解释。抓取日志前按仓库规则清理凭据、内部地址和敏感包名。测试安全提示音时遵守车辆和台架操作规范。

**Q1: [learning] 要证明“点击音与媒体如何共存”，最少应设计哪些对照实验？**

单次“听起来像混声”无法区分应用没申请焦点、矩阵并发、Android 软件混音及 HAL 硬件 duck。固定音量与设备后，改变一个变量并同步记录焦点、播放器、路由和物理输出。

1. **基线**：只播媒体，确认 `USAGE_MEDIA`、目标 zone、bus、音量组和实际喇叭。
2. **系统点击声**：媒体不断播，调用平台 UI 点击音效。观察有无新焦点请求、音效实际 usage、音乐是否被 duck、最终延迟。
3. **自定义短音**：以 `USAGE_ASSISTANCE_SONIFICATION` 和 MAY_DUCK 申请，先获得焦点再播放，结束后放弃。与使用 TRANSIENT 请求的同一音频作对照，看媒体是否收到 loss。
4. **持有者偏好**：令媒体播放器启用 `setWillPauseWhenDucked(true)`，重复第 3 项，验证并发矩阵中的“旧持有者选择暂停”分支。
5. **多区/高优先级条件**：分别在副驾区、通话占焦和电源策略限制状态下重复。每次记录完整持焦者集合，不能只写“媒体 + 点击”两者。

预期仅是 AOSP 默认规则的假设，最终以目标车实测为准。来源：[AAOS 焦点矩阵](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 13 FocusInteraction](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)、[音频电源策略](https://source.android.com/docs/automotive/audio/audio-power-management)。

**Q2: [learning] 一次点击无声，怎样按层定位，而不是马上改 SoundPool 或 HAL？**

按声音形成的依赖链查，第一处与预期不符的位置就是下一步重点。每层至少保存时间戳、请求者、usage、zone 和设备地址，避免不同事件串线。

1. **用户事件**：确认按钮回调确实触发、音效开关允许播放，应用没有因生命周期取消请求。
2. **焦点**：确认请求的 gain/AudioAttributes、返回值和随后回调。`FAILED` 或仍是 `DELAYED` 时应用应无声。
3. **播放器**：SoundPool 样本已加载且 `play()` 返回有效 stream ID，或 AudioTrack 已 start/write 且没有 underrun。焦点成功并不能覆盖加载失败。
4. **策略**：确认实际播放的 usage 与焦点请求相同，已路由到目标 zone 和有效 bus，目标音量组不为静音或不可听增益。
5. **数据**：确认 AudioFlinger 活动 track、输出线程及 HAL 写入。若 Android 内已有 PCM 而车内无声，转查 HAL、DSP、功放和扬声器。

来源：[SoundPool API](https://developer.android.com/reference/android/media/SoundPool)、[AAOS 多区路由](https://source.android.com/docs/automotive/audio/audio-multizone-routing)、[AOSP 音频架构](https://source.android.com/docs/core/audio)。

**Q3: [learning] 哪些 dumpsys 能分别回答焦点、路由与混音问题？**

先确认设备允许相应 shell dump。厂商构建可能改变服务名、字段或访问权限。以下命令是只读快照，最好在同一轮实验的播放前、音效中、结束后各保存一次。

```bash
adb shell dumpsys car_service --services CarAudioService
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb shell dumpsys media.audio_flinger
```

各参数的作用及可观察结果：

1. **`adb shell`**：在设备上执行只读服务查询。省略它则命令在本机运行，通常看不到目标车状态。`adb` 默认选择唯一已连接设备，多设备时应加 `-s <serial>` 精确指定。该 serial 是设备标识，采集报告中要脱敏。
2. **`dumpsys car_service --services CarAudioService`**：请求 CarService 的音频服务 dump，重点看 zone、context、持焦者、延迟请求、组和 duck。省略服务过滤可能输出整个 CarService，内容过多且容易漏看关键段。
3. **`dumpsys audio`**：看 AudioService/MediaFocusControl 通道、焦点请求与 AudioManager 状态。省略服务名会 dump 全系统，无法高效定位。外部车机策略启用时不能仅按手机焦点栈解释其结果。
4. **`dumpsys media.audio_policy`**：看策略服务的输出设备、AudioMix 与路由。服务名在不同设备上可能不可用，应先用 `adb shell dumpsys -l` 核对实际服务；`-l` 列出设备注册的 dump service 名称。不能仅凭焦点已授予推断 bus 已正确选中。
5. **`dumpsys media.audio_flinger`**：看活动 track、输出线程、采样与混音状态。它可证明 Android 内部流是否进入输出线程，但无法单独证明车载功放真的发声。

来源：[AOSP 音频架构与调试入口](https://source.android.com/docs/core/audio)、[AAOS 13 CarAudioFocus dump 实现](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)。

**Q4: [learning] 播放器确实 STARTED、焦点 GRANTED，为什么要检查“音效 usage 与焦点 usage 不一致”？**

焦点和实际播放由不同对象携带 AudioAttributes。AAOS 13 所用 Android 13 `SoundPool.Builder` 在未调用 `setAudioAttributes()` 时默认 `USAGE_MEDIA`。若焦点请求用 `USAGE_ASSISTANCE_SONIFICATION`，CarAudioFocus 可能按 `SYSTEM_SOUND` 允许并发，AudioPolicy 却把 SoundPool 的 PCM 当媒体送到 `MUSIC` bus。反过来，播放器是系统音效、请求却按媒体申请，也可能把点击变成媒体抢焦。

核对方法是同时记录 AudioFocusInfo 的 usage 与 active playback configuration 的 AudioAttributes。若不一致，先修应用构造路径，再重新验证路由和 duck。其他播放器的默认 AudioAttributes 仍须分别查目标版本 API 或实机 dump。来源：[Android 音频焦点要求 attributes 一致](https://developer.android.com/media/optimize/audio-focus)、[Android 13 SoundPool.Builder 默认属性源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/SoundPool.java)、[AAOS context 映射](https://source.android.com/docs/automotive/audio/audio-policy-configuration)。

**Q5: [learning] “音乐被压低却没有 Android 播放器请求焦点”还应查哪里？**

车外音源可能在 Android 之外生成声音，但通过 AudioControl HAL 的 IFocusListener 请求焦点，再由 HalAudioFocus 代理进入车机矩阵。车载 DSP 也可能按车辆状态自行调整增益，因此只查 Android 应用 playback 列表会漏掉来源。

1. 检查 CarAudioFocus 当前持焦/失焦条目中是否有 HAL 代理的 usage、zone 和 gain。
2. 检查 AudioControl HAL 的焦点请求、abandon、duck/mute 通知以及对应时间戳。
3. 检查实车外部音源是否走 Android bus、独立 DSP 输入或车身控制器路径，确认其与功放混合位置。
4. 若车外声既未向 Android 申请焦点又直接改变功放，按产品集成协议排查，不能强行归因到 AudioManager。

来源：[AudioControl HAL 的车外焦点请求](https://source.android.com/docs/automotive/audio/audio-control-hal)、[AAOS 13 HalAudioFocus 所在源码目录](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/hal/)。

**Q6: [learning] AAOS 13 文档与网上新版文章对不上时，先核哪些版本边界？**

先以设备 `Build.VERSION.SDK_INT`、CarService 分支、配置文件版本和 AudioControl HAL 接口版本建“版本四元组”，再看文章发布日期。官网当前页面常同时解释 Android 14、15 以后能力，不能把当前页面全部当成 AAOS 13 的行为。

1. **Android 13 焦点**：默认静态 CarAudioContext/FocusInteraction 矩阵，具体策略以设备 CarService 修改为准。
2. **Android 14+ OEM 插件**：可覆盖焦点/duck 等决策，配置 version 3 支持新的 context/zone 机制。AAOS 13 上不能假定已有。
3. **Android 15+ 应用限制**：以 Android 15（API 35）或更高版本为 target 的应用，只有处于顶层或运行前台服务时才能申请焦点，否则返回 `AUDIOFOCUS_REQUEST_FAILED`。这是 target SDK 条件，不能倒套到 AAOS 13。
4. **车型 HAL**：同一 Android 大版本也可因 HIDL 1.0、2.0 或 AIDL 实现不同而表现不同，duck/mute 和外部声音集成需逐车核验。

来源：[AAOS OEM car audio 插件](https://source.android.com/docs/automotive/audio/car-audio-plugin)、[车机音频配置版本](https://source.android.com/docs/automotive/audio/audio-policy-configuration)、[Android 音频焦点版本变化](https://developer.android.com/media/optimize/audio-focus)、[AudioControl HAL](https://source.android.com/docs/automotive/audio/audio-control-hal)。
