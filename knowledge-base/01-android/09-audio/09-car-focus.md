# AAOS 焦点矩阵与音区：何时共存、抢占、拒绝或等待

> 以公开 AAOS 13 `android13-release` 默认实现为准。表述中“共存”指同时持焦，不自动保证音频物理叠加或某一方可听。

**Q1: CarAudioContext 与 AudioAttributes.usage 为什么都要有，矩阵又怎样查？**

usage 是应用表达的细用途。CarAudioContext 把多个 usage 归成车机可配置的类别，使焦点、路由、音量与 ducking 对齐。AAOS 13 默认有 `MUSIC`、`NAVIGATION`、`VOICE_COMMAND`、`CALL`、`SYSTEM_SOUND` 等 context。矩阵的行是现有持焦者，列是新请求者，因此反向查可能得到不同结果。

1. `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` → `MUSIC`。
2. `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` → `NAVIGATION`。
3. `USAGE_ASSISTANCE_SONIFICATION` → `SYSTEM_SOUND`。
4. `USAGE_NOTIFICATION` 及部分通知子类型 → `NOTIFICATION`。
5. `USAGE_VOICE_COMMUNICATION` → `CALL`。

这是 Android 13 默认映射。Android 14+ 的 OEM 自定义 context 属于另一个版本机制，不能套到 13。来源：[AAOS 13 CarAudioContext](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioContext.java)、[车机音频配置](https://source.android.com/docs/automotive/audio/audio-policy-configuration)。

**Q2: 默认矩阵说 CONCURRENT，为什么新请求还是可能让旧媒体失焦？**

矩阵值只给出 context 之间允许的基本关系，CarAudioFocus 还要读新请求的 gain hint 和旧持有者的 duck 偏好。AAOS 13 的 FocusInteraction 在 `CONCURRENT` 单元格中，只有新请求 MAY_DUCK、旧持有者未设置“duck 时暂停”、也未要求车机 duck 事件时才保持双持焦。否则把旧持有者列入 losers，后续按新请求类型分发 loss。

1. **新请求 MAY_DUCK，旧媒体允许系统 duck**：双方持焦。媒体可能没有焦点回调，HAL 可单独降低媒体 bus。
2. **新请求 TRANSIENT**：虽然矩阵容许并发，但它不允许旧声音以 duck 形式继续，旧持有者会暂时失焦。
3. **新请求 GAIN**：旧持有者可能永久失焦。长媒体之间相互取代通常需要这种语义。
4. **旧媒体选择 duck 时暂停或收 duck 事件**：它会收到 loss，不应再推断为“同时持焦”。

来源：[AAOS 13 FocusInteraction](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)、[AAOS 焦点并发条件](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q3: 车机同时有音乐、导航、点击音三名请求者，为什么不能只查两两矩阵中的一格？**

CarAudioFocus 对音区内所有当前持焦者逐个评估，也检查暂时失焦而等待恢复的请求。任一比较为拒绝，请求整体失败。可延迟的拒绝使请求进入延迟状态。能授予时，独占关系中的旧持有者收到 loss，其余可保持持焦。于是“点击声和音乐可共存”并不推出“通话中点击声也能播放”。

检查顺序应是：确定请求者 context 与 gain → 确定同一区的所有 holders/losers → 按持有者为行逐项查矩阵 → 叠加 gain/duck 选择 → 看最终返回与回调。AAOS 文档概括为拒绝优先、再独占、最后并发。源码还要处理失焦待恢复者及同 clientId 替换。来源：[AAOS 焦点说明](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 13 CarAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)。

**Q4: 同一辆车主驾听音乐、副驾播视频，为什么副驾请求通常不抢主驾焦点？**

AAOS 将焦点按 audio zone 独立管理，每区有自己的持焦者与音量/路由。副驾请求若正确落入副驾 zone，CarAudioFocus 只和该 zone 的持焦者比较，不应改变主驾的焦点。显示区域与音区有关联，但“画面在副驾屏”本身不能证明声音也在副驾区。

落区要核对请求关联的 user/UID、CarAudioService 对 UID/乘员的绑定，以及多区播放时显式携带的 `AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID`。希望同一应用在多个区同时出声，要逐区申请焦点并为各区建立正确路由，不能用一次主区焦点许可覆盖全车。来源：[AAOS 多区焦点](https://source.android.com/docs/automotive/audio/audio-focus)、[多区路由](https://source.android.com/docs/automotive/audio/audio-multizone-routing)、[AAOS 13 CarZonesAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarZonesAudioFocus.java)。

**Q5: 焦点何时 DELAYED，为什么短点击声不应排队？**

AAOS 13 CarAudioFocus 只对 `AUDIOFOCUS_GAIN` 且设置 `AUDIOFOCUS_FLAG_DELAY_OK` 的请求允许延迟。遇到当前持焦者拒绝但可等待时，返回 `AUDIOFOCUS_REQUEST_DELAYED`，应用不得立即播放。阻塞条件消失后通过监听器通知授予。一个延迟请求后来可能被另一请求替换，所以长期等待必须做好取消与状态核对。

短点击音属于瞬时交互反馈，过时后再响会误导用户。它通常用瞬时焦点请求，失败则跳过这一声。持续媒体内容才考虑延迟，并在真正拿到焦点时复核用户仍想听。来源：[AAOS 13 CarAudioFocus 的 canReceiveDelayedFocus 与延迟请求](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)、[AudioFocusRequest 延迟规则](https://developer.android.com/reference/android/media/AudioFocusRequest)。
