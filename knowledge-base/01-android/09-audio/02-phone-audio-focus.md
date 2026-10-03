# 手机侧音频焦点与路由

> 学习资料（文章模式沉淀）。主线：手机（非车机）侧的音频焦点仲裁与丢失处理、音量滑条与 AudioAttributes 的关系、拔插设备切换、通信路由、并发录音与麦克风隐私。焦点栈机制按本地 AAOS13 frameworks/base 源码核对；官方规则与社区经验口径随题标注（2026-09 检索）。车机侧焦点矩阵见 [03-aaos-audio.md](03-aaos-audio.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 调用 requestAudioFocus() 之后，系统里发生了什么？焦点栈是怎么仲裁的？**

焦点裁决发生在 system_server，而不是应用之间。`AudioManager.requestAudioFocus()` 经过 Binder 到 AudioService，再由 MediaFocusControl 根据 gain 类型与当前 `mFocusStack` 逐项仲裁。

1. **请求结果**：调用返回 `AUDIOFOCUS_REQUEST_GRANTED`、`FAILED` 或 `DELAYED`，应用据此决定能否立即播放或等待。
2. **旧持有者**：成功请求可能让栈中旧持有者收到 `AUDIOFOCUS_LOSS` 或 `AUDIOFOCUS_LOSS_TRANSIENT`。收到永久 loss 的条目会出栈。
3. **放弃与恢复**：调用 `abandonAudioFocus()` 移除自己的条目；若之前的持有者仍有效，系统可向其发送恢复回调。

即时返回值描述本次申请结果，后续焦点变化则通过监听器回调报告；不能用音量变化或播放器状态代替这两类信号。

**Q2: 收到 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 该怎么表现？焦点丢失后能"自己恢复"吗？**

收到 loss 后按持续时间和是否允许 ducking 处理：

1. `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`：短时让位时可降低音量继续播放，也可暂停；保留播放会话，不释放解码器。Android 8.0（API 26）起，符合系统自动 duck 条件时，非语音内容可能被系统压低而不收到该回调。
2. `AUDIOFOCUS_LOSS_TRANSIENT`：短时独占，暂停播放并保留可恢复状态。
3. `AUDIOFOCUS_LOSS`：永久失焦，停止播放并释放不再需要的资源，不应假设系统稍后会恢复焦点。

对暂时失焦的会话，只有收到 `AUDIOFOCUS_GAIN` 才考虑恢复；还要检查用户是否已主动暂停。延迟请求也必须等授予通知，不能轮询音量或定时重播。多线程应用应同步回调与 UI 播放状态，避免界面和播放器状态分叉。

**Q3: 音量滑条调的到底是什么？AudioAttributes 与旧 stream type 什么关系？**

手机系统的传统音量 UI 按 stream type 管理媒体、铃声、闹钟等音量曲线与持久化值。AudioAttributes 用 usage 和相关 flags 描述播放；需要兼容旧 stream 的代码路径会经 `AudioAttributes.toLegacyStreamType()` 映射到 legacy stream，因此排查滑条无响应时应先确认实际 attributes 对应哪类音量。

1. `USAGE_MEDIA` 与 `USAGE_GAME` 通常映射到媒体 stream。
2. `USAGE_ALARM` 通常映射到闹钟 stream。
3. 旧式按 stream type 构造或控制流的 API 仍按对应 legacy stream 工作；新代码应使用 AudioAttributes 表达播放用途，避免把过时 stream 参数当作新的用途模型。

**Q4: ACTION_AUDIO_BECOMING_NOISY 什么时候广播？该怎么用？**

`android.media.AUDIO_BECOMING_NOISY` 是输出路由即将切到可能外放的提示，例如拔掉有线耳机或断开 A2DP sink，系统准备切到扬声器时可能发送。媒体应用可在播放期间动态注册 BroadcastReceiver，收到后暂停或降低音量；该广播不是 sticky，注册前已发生的设备变化不会补发，因此它只处理切换瞬间，不替代播放状态和当前路由管理。具体设备变化是否发送该广播由系统路由行为决定，应用不应把它当作所有蓝牙 profile 断开的通用通知。

**Q5: 通话/语音类应用怎么选路由？setCommunicationDevice 与老的 setSpeakerphoneOn 什么关系？**

通信应用在 API 31 及以上应通过 `AudioManager.setCommunicationDevice()` 选择路由，而不是分别开关扬声器或启动 SCO。选择过程应依据当前可用设备，并观察系统最终选中的 communication device：

1. `getAvailableCommunicationDevices()` 返回此刻可选的通信输出设备，例如听筒、扬声器、蓝牙耳机或有线耳机。
2. 传入其中一个 sink `AudioDeviceInfo` 调用 `setCommunicationDevice()`；设备不再可用或请求无效时返回 `false`。多个应用同时请求时，当前控制 audio mode 的应用优先，因此通话应用应正确维护 `MODE_IN_COMMUNICATION`；`MODE_IN_CALL` 仅主电话应用可设。
3. 用 `getCommunicationDevice()` 或 `addOnCommunicationDeviceChangedListener()` 确认当前路由。设备断开后不要假设仍选中原设备或一定切到某个固定设备；按更新后的可用设备列表重新判断。
4. 通话或语音会话结束、Activity/Service 停止时调用 `clearCommunicationDevice()`，取消应用先前的设备选择。

`setSpeakerphoneOn()` 自 API 34 起弃用，统一改用 communication device API。旧 SCO 控制也应迁移到同一 API，避免扬声器开关和 SCO 建链分别争抢通信路由；旧设备版本需保留对应兼容路径。

**Q6: 两个应用同时录音，谁拿到声音？**

Android 10（API 29）起，系统会按优先级在正在录音的应用之间切换输入；常见情况是低优先级应用继续运行但收到静音，并非任意两个普通应用都能同时拿到麦克风数据。

1. **应用身份**：预装特权应用通常优先于普通应用；助手和无障碍等特定特权场景另有共享规则。
2. **前台状态**：对两个非隐私敏感的普通应用，有可见前台 UI 的应用优先于后台应用；两者都在后台时，最近开始捕获者优先。
3. **隐私敏感音源**：`CAMCORDER`、`VOICE_COMMUNICATION` 等隐私敏感 source 优先于普通 source，即使普通应用界面在前台或较晚开始录音。
4. **并发结果**：普通应用之间通常只有优先级较高的一方收到音频，另一方继续运行但收到静音；少数特权场景可以共享输入。

因此“第二个开始录音后第一个录到静音”可能是系统优先级策略。排查全零录音时，先确认调用方身份、source 隐私级别、前台状态和实际输入设备，再检查权限及 AudioRecord 状态。

**Q7: 麦克风隐私指示与后台录音限制是怎么叠加的？**

排查麦克风状态时要区分系统隐私提示、访问记录和后台执行权限三层：

1. **隐私指示器**：Android 12（API 31）起，系统在麦克风被使用时显示隐私指示，让用户知道传感器正在使用。
2. **AppOps 记录**：`dumpsys appops` 可辅助检查包的麦克风访问记录；它说明系统登记过访问，不单独证明录音 buffer 中有有效样本。
3. **前台服务限制**：面向 Android 14（API 34）及以上的应用需声明 microphone 前台服务类型和相应权限；`RECORD_AUDIO` 受 while-in-use 限制，通常要在 Activity 可见时启动 microphone 前台服务。后台启动受限，除非符合系统列出的例外。
4. **采集数据**：隐私指示和 AppOps 都不能证明 AudioRecord 正在收到非静音数据；仍需检查录音状态、输入设备、并发采集优先级与 buffer 内容。

因此排查后台录音无声时，依次检查系统是否记录麦克风访问、服务声明与启动时机是否合法，最后验证实际输入通路和样本。
