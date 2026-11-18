# Android/AAOS 音频焦点、播放与蓝牙知识目录

本目录以 AAOS 13 为基线，收录 13 册、104 题。蓝牙配对、回连、联系人和通话记录同步见 [蓝牙连接与数据同步](../09-network/11-蓝牙连接与数据同步.md)。

## 音频基础

1. [AOSP 音频](01-AOSP音频.md)（19 题）。应用播放接口、音频服务、混音、策略和 HAL。
2. [手机音频焦点](02-手机音频焦点.md)（7 题）。媒体焦点申请、回调、音量衰减和版本行为。
3. [车机音频](03-车机音频.md)（15 题）。车载音频分区、焦点、音量和路由策略。
4. [音频延迟](04-音频延迟.md)（11 题）。端到端时延、缓冲、低延迟路径和测量。
5. [蓝牙音频](05-蓝牙音频.md)（8 题）。A2DP 媒体传输、编解码、HFP 通话音频及排查。

## 点击音学习路径

1. [音效与音源决策](06-音效与音源决策.md)（6 题）。区分普通点击、媒体、导航和安全声音，判断混音与抢占条件。
2. [焦点申请契约](07-焦点申请契约.md)（4 题）。AudioManager、AudioAttributes 和 AudioFocusRequest 的职责，短音申请、失焦和延迟处理。
3. [焦点调用链](08-焦点调用链.md)（4 题）。从应用经过 AudioService、MediaFocusControl 和外部 AudioPolicy 到车载焦点服务。
4. [车机焦点矩阵](09-车机焦点矩阵.md)（5 题）。context 矩阵、并发条件、多持焦者、音区和延迟焦点。
5. [路由配置与音量组](10-路由与音量组.md)（4 题）。usage、context、AudioMix 和 bus 的映射，组增益及单独 duck 的拓扑条件。
6. [播放数据与 HAL](11-播放数据与HAL.md)（4 题）。SoundPool、AudioTrack、AudioFlinger、Audio HAL 与 AudioControl HAL。
7. [音频全链路排查](12-音频全链路排查.md)（6 题）。五组对照实验、逐层无声定位、dumpsys 与版本边界。

## 蓝牙音频缺陷

1. [车载蓝牙音频、媒体与通话缺陷](13-蓝牙缺陷机制.md)（11 题）。通话音频建链、媒体焦点与显示、播放控制和多路通话状态。

**先记住的判断**：系统的 `playSoundEffect()` 在 Android 13 路径中不为每次点击申请焦点。自定义 UI 音效若显式申请，AAOS 13 默认矩阵允许 `MUSIC → SYSTEM_SOUND` 并发。实际双持焦还取决于 MAY_DUCK 请求与旧持有者偏好，最终听感还取决于音区、路由和 HAL。

证据优先级：目标车的运行配置与可测输出 → 对应厂商分支源码 → [公开 AAOS 13 CarService](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/) 与 [Android 13 音频服务源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/audio/) → [AAOS 官方音频文档](https://source.android.com/docs/automotive/audio) 和 [Android 开发者音频焦点指南](https://developer.android.com/media/optimize/audio-focus) → 社区文章。社区资料可提供问题线索，涉及版本敏感行为时须回到源码或设备验证。
