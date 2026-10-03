# 手机侧音频焦点与路由

> 学习资料（文章模式沉淀）。主线：手机（非车机）侧的音频焦点仲裁与丢失处理、音量滑条与 AudioAttributes 的关系、拔插设备切换、通信路由、并发录音与麦克风隐私。焦点栈机制按本地 AAOS13 frameworks/base 源码核对；官方规则与社区经验口径随题标注（2026-09 检索）。车机侧焦点矩阵见 [03-aaos-audio.md](03-aaos-audio.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 调用 requestAudioFocus() 之后，系统里发生了什么？焦点栈是怎么仲裁的？**

调用链是 `AudioManager.requestAudioFocus()` 经 Binder 进 system_server 的 AudioService，再转交 MediaFocusControl 仲裁；MediaFocusControl 维护 `mFocusStack`（栈结构，pop/iterator 操作，类头注释核对——"音频焦点的裁判所：mFocusStack 维护「谁能出声」的栈"）。新请求按 gain 类型与在栈者两两比较：成功则压栈，被压者按请求类型收到 `AUDIOFOCUS_LOSS`（永久）或 `AUDIOFOCUS_LOSS_TRANSIENT`（暂时）回调；收永久 loss 的条目出栈。`abandonAudioFocus()` 与请求对称：出栈自己的条目，并可能让被压者收到恢复回调。含义：焦点裁决发生在 system_server，应用间不直接协商；回调是唯一的状态来源。

**Q2: 收到 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 该怎么表现？焦点丢失后能"自己恢复"吗？**

按 loss 类型决定表现，这是官方指南的固定契约：

- **`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`**：短时让位且对方只压低不停止——本方降低音量或暂停，不停播、不释放解码器（导航播报压媒体是典型场景）。
- **`AUDIOFOCUS_LOSS_TRANSIENT`**：短时独占——暂停播放，保留状态等恢复。
- **`AUDIOFOCUS_LOSS`**：永久让位——停止播放并释放资源，不假设会回来。

不能自己恢复：恢复播放的唯一依据是再次收到 `AUDIOFOCUS_GAIN` 回调，轮询音量或定时重播都是错误做法；若请求时系统判定可延迟，恢复同样以 granted 回调为准。多线程下回调线程与应用状态要加同步，避免"回调说停、UI 说在播"的僵尸状态。

**Q3: 音量滑条调的到底是什么？AudioAttributes 与旧 stream type 什么关系？**

AudioService 的音量体系按 stream type 组织：每个滑条对应一个 stream（媒体、铃声、闹钟等），各自有音量曲线与持久化值。AudioAttributes 是新的流描述入口，但内部仍映射回 legacy stream type（`AudioAttributes.toLegacyStreamType()`，AudioAttributes.java 核对），映射关系由 usage 决定——`USAGE_MEDIA` 与 `USAGE_GAME` 通常落媒体滑条，`USAGE_ALARM` 落闹钟滑条。因此"音量键/滑条不动"类问题的第一步是确认流的 usage 映射到了哪个滑条；直接按 stream type 构造 AudioTrack 的旧写法被框架视为等价于对应 usage 的新写法，新代码一律用 AudioAttributes。

**Q4: ACTION_AUDIO_BECOMING_NOISY 什么时候广播？该怎么用？**

`android.media.AUDIO_BECOMING_NOISY`（AudioManager.java 常量核对）在输出设备即将变成"外放"时广播，典型触发是有线耳机拔出——不暂停就会外放尴尬。用法：播放期间注册 BroadcastReceiver，收到后暂停播放；这是一次性事件（非 sticky），注册前发生的广播收不到，所以广播只兜"拔出瞬间"，应用状态恢复仍靠路由监听与自身生命周期。蓝牙断开默认不触发该广播（A2DP 断开走设备连接变化路径，官方文档口径），蓝牙场景的暂停策略要单独处理。

**Q5: 通话/语音类应用怎么选路由？setCommunicationDevice 与老的 setSpeakerphoneOn 什么关系？**

`AudioManager.setCommunicationDevice()`（AudioManager.java:7710 核对，API 31 起）是通信路由的统一入口：`getAvailableCommunicationDevices()`（:7762 核对）列出可选设备（听筒、扬声器、蓝牙 SCO、有线），选定后系统把通信流路由过去，结束用 `clearCommunicationDevice()` 还原。旧的 `setSpeakerphoneOn()`、`startBluetoothSco()` 语义窄且互相踩：扬声器开关与 SCO 建链争同一条路由，表现为"通话没声/该外放却走耳机"（社区经验口径）。迁移要点：通信场景一律用 communication device API，设备断开变 `TYPE_UNKNOWN` 时的回退行为 API 31 起由系统处理，低版本要自己记录并恢复（社区口径，随版本实机核验）。

**Q6: 两个应用同时录音，谁拿到声音？**

Android 10 起麦克风支持多应用并发访问，但默认只有一个拿到真实音频，其余收到静音（官方文档口径）。优先级规则：

- **有前台 UI 的应用**优先于无 UI 的。
- 都无 UI 时，**最近开始捕获**的应用优先。
- 普通（隐私敏感）应用会让位给电话、助手类特权应用。

因此"第二个开始录音后第一个录到静音"是策略行为不是 bug；想真正并发需要特权身份或修改音频策略（CSDN 实测口径）。排查录音"录到全零"先确认是否被并发策略压制，再看权限与设备连接。

**Q7: 麦克风隐私指示与后台录音限制是怎么叠加的？**

三层叠加：Android 12 起状态栏/指示器在麦克风被使用时显示（官方文档口径），让"谁在听"可见；AppOps 记录每次麦克风访问，`dumpsys appops` 可核对哪些包在何时拿了麦克风；Android 14 起后台麦克风采集要求 `microphone` 类型前台服务且服务在应用可见时或经用户操作启动（while-in-use，官方文档口径），后台硬启动拿不到音频。排查"后台录音无声"按层走：指示器/AppOps 确认系统是否认为你在录——FGS 类型与启动时机——录音路径本身。
