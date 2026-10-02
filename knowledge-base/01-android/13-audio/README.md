# AAOS 音频焦点与播放学习路径

以 AAOS 13 为基线，从“点击音和媒体怎么共存”沿应用、焦点服务、车机策略、路由、混音、HAL 到排障逐级深入。每册 Q 序列即学习顺序，适合 atlas 同源读取。下面新增的 06–12 册是连续专题；01–05 册保留原有 AOSP、车载音频、延迟、蓝牙和手机侧知识，不重复搬运正文。

1. [06-音效与音源决策](06-audio-decisions.md)：先分清普通点击、媒体、导航和安全声音；回答“混声还是抢占”的条件。
2. [07-应用焦点契约](07-focus-api.md)：AudioManager/AudioAttributes/AudioFocusRequest 的职责、短音申请示例、失焦和延迟处理。
3. [08-焦点系统调用链](08-focus-flow.md)：应用 → AudioService/MediaFocusControl → 外部 AudioPolicy → CarZonesAudioFocus/CarAudioFocus。
4. [09-AAOS焦点矩阵与音区](09-car-focus.md)：context 矩阵、并发附加条件、多持焦者、多区和延迟焦点。
5. [10-路由配置与音量组](10-routing-volume.md)：usage/context → AudioMix → bus、两份 XML、组增益与单独 duck 的拓扑条件。
6. [11-播放数据与HAL](11-playback-hal.md)：SoundPool/AudioTrack → AudioFlinger → Audio HAL，以及 AudioControl HAL 的车载控制。
7. [12-全链路实验与排障](12-diagnostics.md)：五组对照实验、逐层无声定位、dumpsys 与版本边界。

**先记住的判断**：系统的 `playSoundEffect()` 在 Android 13 路径中不为每次点击申请焦点。自定义 UI 音效若显式申请，AAOS 13 默认矩阵允许 `MUSIC → SYSTEM_SOUND` 并发。实际双持焦还取决于 MAY_DUCK 请求与旧持有者偏好，最终听感还取决于音区、路由和 HAL。

证据优先级：目标车的运行配置与可测输出 → 对应厂商分支源码 → [公开 AAOS 13 CarService](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/) 与 [Android 13 音频服务源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/) → [AAOS 官方音频文档](https://source.android.com/docs/automotive/audio) 和 [Android 开发者音频焦点指南](https://developer.android.com/media/optimize/audio-focus) → 社区文章。社区资料可提供问题线索，涉及版本敏感行为时须回到源码或设备验证。当前工作环境未找到仓库地图中提到的本地 `AAOS13_study`，本轮源码核对使用公开分支，不冒称本地核对。
