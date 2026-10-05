# 路由配置与音量组：声音走向哪只扬声器

> AAOS 13 动态路由的配置面。不同车型的 bus 地址、扬声器布局和 AudioControl HAL 实现须以设备文件与实际测量为准。本册不提供未经目标车验证的配置样例。

**Q1: [learning] 焦点获准之后，是什么把 `USAGE_ASSISTANCE_SONIFICATION` 送到某个 bus？**

焦点只裁决能否开始逻辑播放。实际路由由 AudioPolicyService 执行：CarAudioService 解析 `car_audio_configuration.xml`，把每个 zone 中 context 对应的 usage 与 bus 关联起来，再注册动态 AudioPolicy/AudioMix。底层 `audio_policy_configuration.xml` 声明 bus 设备及可打开的输出路径。两份配置必须对上，Android 才能把音效落到可用的输出设备。

1. **AudioAttributes**：播放器给出 usage，例如 `USAGE_ASSISTANCE_SONIFICATION`。
2. **CarAudioContext**：AAOS 把该 usage 归为 `SYSTEM_SOUND`，确定本区配置里对应的 bus 地址。
3. **动态 AudioMix**：CarAudioDynamicRouting 生成按 usage 匹配的 render mix。AudioPolicyService 根据 mix、UID/user affinity 和已连接设备选输出。
4. **Audio HAL**：接到设备/流的打开与写入请求，把 bus 对应到车上功放或 DSP 通路。

若焦点显示 `SYSTEM_SOUND` 却从媒体 bus 出声，先比较“申请焦点用的 attributes”和“SoundPool/AudioTrack 实际播放用的 attributes”，再查两份配置的对应关系。来源：[AAOS 13 CarAudioDynamicRouting](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarAudioDynamicRouting.java)、[AAOS 车机音频配置](https://source.android.com/docs/automotive/audio/audio-policy-configuration)、[AAOS 多音区路由](https://source.android.com/docs/automotive/audio/audio-multizone-routing)。

**Q2: [learning] `car_audio_configuration.xml` 与 `audio_policy_configuration.xml` 为什么不能互相代替？**

前者描述车载产品语义，后者描述 Android 音频设备及连接能力。CarAudioService 不能凭一个虚构 bus 地址让 HAL 出声，AudioPolicyManager 也不会从设备端口自行推断“哪个 bus 是导航”。

1. **车载配置**：定义 zone、音量组、每个 bus 所承载的 context。AAOS 13 常见版本 2 格式。系统优先查 `vendor/etc/car_audio_configuration.xml`，再查 `system/etc/`。每区应覆盖需要路由的 context。
2. **平台策略配置**：声明 `TYPE_BUS` 输出设备、设备地址、mix port、route、采样率/格式/声道能力，并使 HAL 能打开对应输出。
3. **一致性检查**：车载配置引用的 bus 地址必须在平台策略配置里存在，相关 HAL 也须实现。只改一份 XML 往往表现为 CarService 初始化失败、流落错设备或无声。

Android 14+ 配置 version 3 和动态 zone configuration 有额外能力，不应把新版 XML 字段直接放进 AAOS 13 设备。来源：[车机音频配置与版本](https://source.android.com/docs/automotive/audio/audio-policy-configuration)、[多音区路由版本说明](https://source.android.com/docs/automotive/audio/audio-multizone-routing)。

**Q3: [learning] 车机的“媒体音量”到底调什么，为什么同一只扬声器上的两种声不一定能单独 duck？**

AAOS 的音量组由 zone 中一组 bus 设备组成，组音量最终配置为这些设备的 gain，由车辆功放/DSP 执行。焦点是逻辑许可，音量组是用户控制的增益维度，duck 是并发播放期间的临时相对衰减。三者不能混用。

1. **组增益**：CarAudioService 根据目标 zone/group 设置相关设备 AudioGain，并保存用户音量索引。物理音量键通常先选当前应调的 context 对应组。
2. **硬件 duck**：CarDucking 根据持焦 usage 算应降低的 bus 地址，AudioControl HAL 决定实际衰减。若音乐和导航已在 Android 内混成同一 bus，HAL 无法仅凭这一路 PCM 把音乐降低而保留导航原幅度。
3. **同 bus 保护**：AAOS 13 的 CarDuckingUtils 在 bus 上还有未被 duck 的持焦 context 时，从待 duck 地址列表移除该 bus，避免把需要清晰的声音一起压低。
4. **路由设计**：若产品要求导航提示时媒体降低而提示不降低，应给两类声音可分别控制的 bus 或采用明确的上游混音控制方案，并在整机测量最终听感。

来源：[AAOS 音量组与 duck](https://source.android.com/docs/automotive/audio/volume-management)、[AAOS 13 CarDuckingUtils](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/service/src/com/android/car/audio/CarDuckingUtils.java)、[车机音频配置](https://source.android.com/docs/automotive/audio/audio-policy-configuration)。

**Q4: [learning] “音源在副驾屏，却从主驾扬声器出声”应按什么顺序查？**

先验证流到底进了哪个音区和 bus，再查物理扬声器映射。显示屏位置只是线索，不是路由证据。

1. **应用层**：记录播放器 AudioAttributes、调用进程 UID/user、焦点请求 zone、播放器创建与开始时间。
2. **CarService**：确认 occupant zone 到 audio zone 的绑定、UID/user affinity、该区的 context→bus 与音量组。
3. **AudioPolicy**：确认活动 playback 的 usage、命中的 AudioMix、选中的 bus 地址。
4. **AudioFlinger/HAL**：确认对应输出线程在写数据、HAL 打开的 bus 与实际功放通道一致。
5. **实车**：按车上声道逐个试听或测量。错误可能是功放通道线束/映射，而非 Android 焦点。

来源：[AAOS 多音区路由](https://source.android.com/docs/automotive/audio/audio-multizone-routing)、[车机音频配置](https://source.android.com/docs/automotive/audio/audio-policy-configuration)。
