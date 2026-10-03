# 焦点系统调用链：AudioManager 到 CarAudioFocus

> Android 13/AAOS 13 控制面。焦点处理与 PCM 数据传输是两条链，本册只追前者。实现依据为公开 `android13-release` 源码。车型可能改写 CarService 或策略配置。

**Q1: 应用调用 requestAudioFocus 后，谁真正管理焦点？为什么同时出现 AudioManager、AudioService 和 CarAudioService？**

AudioManager 是应用侧代理。system_server 的 AudioService/MediaFocusControl 是通用焦点入口与回调枢纽。启用 AAOS 动态音频策略后，CarAudioService 注册外部 AudioPolicy 焦点监听器，由 CarZonesAudioFocus 和各区 CarAudioFocus 执行车机矩阵裁决。AudioFlinger 不裁决焦点，它负责拿到可播流后的音频处理。

1. 应用创建 AudioFocusRequest，AudioManager 通过 IAudioService Binder 调用 AudioService。
2. AudioService 把请求交给 MediaFocusControl。若注册了车机外部焦点策略，它把 AudioFocusInfo 通知该策略并等待结果，而不是直接用普通手机焦点栈裁决。
3. CarZonesAudioFocus 根据请求者的 user/UID 或指定 zone 找到目标 CarAudioFocus。
4. CarAudioFocus 按音区内持焦者、失焦待恢复者及矩阵决定结果，经 AudioManager 的策略接口把 `GRANTED`、`FAILED` 或 `DELAYED` 送回原请求，并向旧持有者分发 loss。
5. 应用收到结果后决定是否启动播放器。之后的路由、混音和 bus 增益另由音频策略、AudioFlinger 与 HAL 执行。

若 AAOS 动态路由/焦点策略未启用或注册失败，不能套用上述车机矩阵结论，应先确认实际加载的是哪种策略。来源：[AAOS 13 CarAudioService 注册策略](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioService.java)、[AAOS 13 MediaFocusControl 外部策略分支](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/MediaFocusControl.java)、[AAOS 13 CarZonesAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarZonesAudioFocus.java)。

**Q2: 为什么不能把手机焦点栈与 AAOS 焦点矩阵混为一谈？**

普通 Android 的 MediaFocusControl 使用焦点栈处理默认策略。AAOS 13 在动态车机音频模式中通过外部 AudioPolicy 把裁决交给 CarAudioFocus。二者共用 AudioManager 申请 API 和 AudioService 通道，仲裁规则却不同。

1. **手机默认策略**：新请求通常改变栈顶持有者状态，旧持有者收到相应 loss 或被系统 duck。
2. **AAOS 车机策略**：按音区维护持焦者集合，可同时有多名持有者。矩阵对“当前 context × 新 context”给出拒绝、独占或并发，再受 gain hint 与持有者设置影响。
3. **调试判据**：先查 CarAudioService 是否成功注册 `setIsAudioFocusPolicy(true)` 的 AudioPolicy，再查当前音区的 CarAudioFocus dump。仅看到 MediaFocusControl 的通用栈字段不足以解释车机并发。

来源：[AAOS 音频焦点](https://source.android.com/docs/automotive/audio/audio-focus)、[AAOS 13 CarAudioService](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioService.java)、[Android 13 MediaFocusControl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/MediaFocusControl.java)。

**Q3: 焦点请求的 clientId、监听器、UID、package 和 AudioAttributes 在跨进程过程中各有什么用？**

它们服务于不同判断：clientId 标识一项焦点请求，监听器接收变化，UID/user 决定应用身份及默认音区，package 用于归因，AudioAttributes 决定音频语义。不能用“应用包名相同”推断两次请求能共享一个监听器。

1. **clientId/监听器**：CarAudioFocus 用 clientId 识别同一请求。相同 clientId 与相同 context 的新请求可替换旧请求。换成不同 context 通常被拒，因为后续回调无法说明对应哪段声音。
2. **UID 与 user**：CarZonesAudioFocus 用身份信息确定目标 zone。共享 UID、多用户和乘员区绑定会改变落区，不能只看 Activity 所在显示屏。
3. **package**：用于日志、归因和焦点观察。不等同于唯一的音源识别键，尤其在共享 UID 的车机环境中。
4. **AudioAttributes**：CarAudioContext 从 usage 得到 context。焦点请求与实际播放不一致时，系统可出现“焦点显示系统音、流却走媒体 bus”的错位。

来源：[AAOS 13 CarAudioFocus 请求替换逻辑](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)、[AAOS 13 CarZonesAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarZonesAudioFocus.java)、[AAOS 多音区焦点](https://source.android.com/docs/automotive/audio/audio-focus)。

**Q4: 一次短音效先返回 GRANTED，随后媒体没有停，是谁“没处理焦点”？**

先确认这是否恰好是期望的 AAOS 并发，而不是把“音乐没停”当故障。默认矩阵中 `MUSIC → SYSTEM_SOUND` 可并发。当新请求为 MAY_DUCK 且旧媒体未要求暂停/duck 回调时，CarAudioFocus 可让双方持焦，媒体不会收到 loss。此时是否降低媒体声音取决于 CarDucking 与 HAL，而非媒体应用自己必须暂停。

如果新请求为 TRANSIENT、旧媒体要求 duck 时暂停，或旧媒体要求接收 duck 事件，CarAudioFocus 会把旧媒体移入失焦者并发 loss。如果没有看到 loss，应核对请求类型、音区、旧媒体是否真的持焦、外部策略是否注册。来源：[AAOS 13 FocusInteraction.evaluateRequest](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/FocusInteraction.java)、[AAOS 13 CarAudioFocus](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioFocus.java)。
