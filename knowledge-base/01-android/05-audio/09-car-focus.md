# AAOS 焦点矩阵与音区：何时共存、抢占、拒绝或等待

> 以公开 AAOS 13 `android13-release` 默认实现为准。表述中“共存”指同时持焦，不自动保证音频物理叠加或某一方可听。

**Q1: [learning] CarAudioContext 与 AudioAttributes.usage 为什么都要有，矩阵又怎样查？**

usage 是应用表达的细用途。CarAudioContext 把多个 usage 归成车机可配置的类别，使焦点、路由、音量与 ducking 对齐。AAOS 13 默认有 `MUSIC`、`NAVIGATION`、`VOICE_COMMAND`、`CALL`、`SYSTEM_SOUND` 等 context。矩阵的行是现有持焦者，列是新请求者，因此反向查可能得到不同结果。

1. `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` → `MUSIC`。
2. `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` → `NAVIGATION`。
3. `USAGE_ASSISTANCE_SONIFICATION` → `SYSTEM_SOUND`。
4. `USAGE_NOTIFICATION` 及部分通知子类型 → `NOTIFICATION`。
5. `USAGE_VOICE_COMMUNICATION` → `CALL`。

这是 Android 13 默认映射。Android 14+ 的 OEM 自定义 context 属于另一个版本机制，不能套到 13。来源：[AAOS 13 CarAudioContext](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioContext.java)、[车机音频配置](https://source.android.com/docs/automotive/audio/audio-policy-configuration)。

**Q2: [learning] 默认矩阵说 CONCURRENT，为什么新请求还是可能让旧媒体失焦？**

矩阵值只给出 context 之间允许的基本关系，CarAudioFocus 还要读新请求的 gain hint 和旧持有者的 duck 偏好。AAOS 13 的 FocusInteraction 在 `CONCURRENT` 单元格中，只有新请求 MAY_DUCK、旧持有者未设置“duck 时暂停”、也未要求车机 duck 事件时才保持双持焦。否则把旧持有者列入 losers，后续按新请求类型分发 loss。

1. **新请求 MAY_DUCK，旧媒体允许系统 duck**：双方持焦。媒体可能没有焦点回调，HAL 可单独降低媒体 bus。
2. **新请求 TRANSIENT**：虽然矩阵容许并发，但它不允许旧声音以 duck 形式继续，旧持有者会暂时失焦。
3. **新请求 GAIN**：旧持有者可能永久失焦。长媒体之间相互取代通常需要这种语义。
4. **旧媒体选择 duck 时暂停或收 duck 事件**：它会收到 loss，不应再推断为“同时持焦”。

来源：[AAOS 13 FocusInteraction](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)、[AAOS 焦点并发条件](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q3: [learning] 车机同时有音乐、导航、点击音三名请求者，为什么不能只查两两矩阵中的一格？**

CarAudioFocus 对音区内所有当前持焦者逐个评估，也检查暂时失焦而等待恢复的请求。一个请求要结合所有比较结果裁决：任一比较拒绝时整体失败；允许延迟的拒绝会使请求进入延迟状态；授予时，独占关系中的旧持有者收到 loss，其余可以继续持焦。因此“点击声和音乐可共存”并不推出“通话中点击声也能播放”。

分析多请求场景时按以下顺序检查：

1. 从新请求确定 context 与 gain。
2. 列出同一音区内全部当前持有者与等待恢复的失焦请求。
3. 以每个旧持有者为行，逐项读取矩阵与新请求的交互。
4. 将 gain 和 duck 选项应用到每项交互，再核对整体返回值及各旧持有者收到的回调。

AAOS 文档概括的处理次序是拒绝优先、其次独占、最后并发；A13 源码还处理失焦待恢复者和相同 clientId 的请求替换。来源：[AAOS 焦点说明](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 13 CarAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)。

**Q4: [learning] 同一辆车主驾听音乐、副驾播视频，为什么副驾请求通常不抢主驾焦点？**

AAOS 将焦点按 audio zone 独立管理，每区有自己的持焦者与音量/路由。副驾请求若正确落入副驾 zone，CarAudioFocus 只和该 zone 的持焦者比较，不会改变主驾焦点。显示区域与音区有关联，但“画面在副驾屏”本身不能证明声音也在副驾区。

诊断落区时应核对：

1. 请求关联的 user 与 UID。
2. CarAudioService 对 UID/乘员的音区绑定。
3. 多区播放是否显式携带 `AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID`。

同一应用要在多个区同时出声，需逐区申请焦点并为各区建立正确路由；一次主区焦点许可不会覆盖全车。来源：[AAOS 多区焦点](https://source.android.com/docs/automotive/audio/audio-focus)、[多区路由](https://source.android.com/docs/automotive/audio/audio-multizone-routing)、[AAOS 13 CarZonesAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarZonesAudioFocus.java)。

**Q5: [learning] 焦点何时 DELAYED，为什么短点击声不应排队？**

AAOS 13 CarAudioFocus 只对 `AUDIOFOCUS_GAIN` 且设置 `AUDIOFOCUS_FLAG_DELAY_OK` 的请求允许延迟。当前持焦者拒绝但请求可等待时，系统返回 `AUDIOFOCUS_REQUEST_DELAYED`；应用此时不能播放。阻塞条件消失后，监听器会收到授予通知，但等待中的请求也可能被另一请求替换。

是否接受延迟应按内容有效期决定：

1. 短点击音是瞬时交互反馈，过时后再响可能误导用户；使用瞬时焦点请求，失败时跳过本次声音。
2. 持续媒体可接受延迟，但应在等待期间处理取消，并在真正获得焦点时再次确认用户仍想播放。

来源：[AAOS 13 CarAudioFocus 的 canReceiveDelayedFocus 与延迟请求](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)、[AudioFocusRequest 延迟规则](https://developer.android.com/reference/android/media/AudioFocusRequest)。
