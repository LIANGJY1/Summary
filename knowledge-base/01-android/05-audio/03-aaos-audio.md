# AAOS 车机音频

> 学习资料（文章模式沉淀）。主线：CarAudioService 与 AudioControl HAL 的分工、car_audio_configuration.xml 的 zone/音量组/context 结构与版本、context→bus 动态路由、应用落区判定、设备端口增益、音量键落点、焦点交互矩阵与延迟焦点、ducking/muting、HalAudioFocus、配置校验与排查。Android 13 机制按 AOSP 源码核对。version 3 与 Android 14 新能力按 Android 官方文档核对。AOSP 侧子系统机制见 [01-aosp-audio.md](./01-aosp-audio.md)。应用侧延迟实践见 [04-audio-latency.md](./04-audio-latency.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 车机音频架构比手机多了哪些车载层？AudioControl HAL 和 car_audio_configuration.xml 各负责什么？**

Android 仍由 AudioFlinger 承载播放数据、AudioPolicyService 选择路由。车机再加入车载音区和设备拓扑配置，以及承接车辆控制信号的 AudioControl HAL。具体音区、功放和音源组合因车型而异，不适合写死在通用框架代码中。

1. `car_audio_configuration.xml`：描述音区、音量组、音频 context 与 bus 设备的关系，由 CarAudioService 解析。Android 优先从 `/vendor/etc/car_audio_configuration.xml` 加载，找不到时再回退 `/system/etc/car_audio_configuration.xml`。
2. `audio_policy_configuration.xml`：声明 Audio HAL 提供的端口与路由，包括车机的 BUS 类型设备端点。CarAudioService 引用的 bus 地址必须能在这里找到对应设备。
3. **AudioControl HAL**：承接车辆侧音频控制接口，例如外部焦点请求、设备 duck/mute 通知、设备增益变化回调、fade 与 balance。具体能力取决于接口版本：Android 13 的 HIDL 2.0 封装不支持设备 duck/mute 通知，相关能力需要 AIDL AudioControl HAL。它不代替 AudioPolicyService 选择播放路由。
4. **bus 的边界**：audio bus 是软件可见的虚拟设备端点。HAL/厂商实现负责把 bus 映射到实际功放和声道，具体映射因车型而异。

**Q2: [learning] car_audio_configuration.xml 的结构如何组织？Android 13 能解析哪些版本？**

Android 13 的配置以音区为顶层，再在每个音区内定义音量组和设备/context 归属。其解析器支持 version 1 和 version 2。把 version 3 特有字段写入 Android 13 配置会在解析时失败。

1. **音区**：`zones` 定义 primary 与 passenger audio zones。primary zone 用 `isPrimary="true"` 标记。version 2 可提供 audioZoneId 与 occupantZoneId 的映射。
2. **音量组**：每个 zone 可定义一个或多个 `volumeGroups`，同组设备由 CarAudioService 作为一个音量控制单元管理。
3. **设备与 context**：组内的 `device` 用 bus address 标识输出端点，再用 context 条目声明该设备承载的音频用途分类。
4. **示例边界**：Android emulator 样例展示主区把媒体/公告、导航/语音、电话/振铃、闹钟/系统音分配到不同组。副区 bus100/bus200 的组划分是样例选择，不是 schema 固定要求。
5. **版本边界**：Android 13 CarAudioZonesHelper 接受 version 1/2。version 3 从 Android 14 引入 OEM-defined contexts 与动态音区配置等结构，不能用于 Android 13。

这份文件承担音区、音量组和 context-to-bus 归属配置。新增或调整设备时还要同步检查 audio_policy_configuration.xml 中的 bus 声明。

**Q3: [learning] CarAudioContext 为什么把几十种 usage 归并成 13 个 context？哪些子系统以它为键？**

AudioAttributes 的 usage 有几十种，但车的路由、音量、焦点只需要粗粒度分类：CarAudioContext 把它们归并为 `INVALID`(0) 到 `ANNOUNCEMENT`(12) 共 13 个语境，数值与 AudioControl HAL 的 ContextNumber 对齐（依据 CarAudioContext.java 与 AudioControl HAL ContextNumber 定义）。三个子系统都以 context 为键，Usage 到 context 的换算入口是 `getContextForUsage`：

1. **动态路由**：context 映射到 bus 设备。
2. **音量组**：context 映射到音量组。
3. **焦点矩阵**：持焦 context 与请求 context 组成有序对，再映射到裁决结果。

因此应用选错 usage 的后果在车机上被放大：`USAGE_MEDIA` 与 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 落不同 context，会走不同 bus、受不同音量组控制、在焦点矩阵里有不同裁决——手机上“usage 只影响策略细节”的直觉在车机不成立。

**Q4: [learning] CarAudioService 怎样把 context 路由到 bus？静态 XML 与动态 AudioMix 各负责什么？**

路由分成端点声明和运行时匹配两步。XML 声明哪些 bus 存在，CarAudioService 再为每台 bus 创建 AudioMix 规则。

1. **声明 bus**：audio_policy_configuration.xml 将 bus 声明为 BUS 类型 devicePort，使其成为 AudioPolicy 可选择的端点。car_audio_configuration.xml 再把 context 归属到该 bus。
2. **构造 AudioMix**：CarAudioDynamicRouting 将 bus 承载的各 context 对应 usage 加入 `RULE_MATCH_ATTRIBUTE_USAGE` 规则，设置 `ROUTE_FLAG_RENDER`，并把 AudioMix 注册到 AudioPolicy。
3. **运行时匹配**：AudioPolicyService 根据流的 AudioAttributes 匹配规则，再把流路由到对应 bus。

某用途未走预期 bus 时，分别核对 context-to-bus 配置、bus 是否在 Audio HAL 配置中声明且连接，以及 CarAudioService 实际加载的是哪份配置。路径查找优先 `/vendor`，之后才回退到 `/system`。

**Q5: [learning] 多音区下应用的播放路由和焦点分别怎样归属？落错区怎么查？**

CarAudioService 为各音区分别建立焦点与路由状态。焦点请求的 zoneId 与播放器的实际输出路由相关但不同：为焦点请求指定 zone 不会自动把播放流移到该区。

1. **播放路由默认映射**：系统可按调用方的 display/occupant zone 将 UID 映射到 audio zone。显示区到 occupant zone 的配置错误会令应用声音落到非预期音区。
2. **系统 UID 绑定**：CarAudioService 可按 UID 设置目标 audio zone，内部通过 `mUidToZoneMap` 管理。该绑定会影响应用流的路由归属。
3. **焦点请求 zone**：`AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID` 用于给焦点请求指定目标区。它选择该区的焦点管理器，不等价于设置播放器的输出设备或 UID 路由。
4. **Android 13 焦点隔离**：CarZonesAudioFocus 为各 zone 建立独立焦点管理器。各区使用同一套 context 矩阵逻辑，焦点竞争不跨 zone。
5. **版本差异**：主音区媒体焦点唯一持有者限制是 Android 14 新增行为，Android 13 没有这项限制。

排查落错区时，先确认 display/occupant zone 与 UID-to-zone 映射，再分别检查焦点条目的 zoneId 和播放流最终路由。`dumpsys audio` 可查看焦点请求及其 zoneId。

**Q6: [learning] 音量组增益经过哪条调用链？它与 AudioFlinger 流音量有什么区别？**

CarAudioService 按（音区，音量组）维护当前增益索引，并把索引转换成组内设备端口的 AudioGain 值。这个路径不是直接改 AudioFlinger 的 stream volume。

1. **设置索引**：应用或系统界面调用 `CarAudioManager.setGroupVolume()`，CarAudioService 更新 CarVolumeGroup 的当前 gain index。
2. **换算设备增益**：CarVolumeGroup 根据每台 bus 设备声明的 AudioGain min/max/step 换算 gain 值，单位为毫贝。同组设备按各自声明应用对应增益。
3. **下发设备端口**：AudioManagerHelper 通过音频设备端口配置接口（包括 createAudioPatch 路径）设置设备增益。
4. **外部变化回调**：支持 AudioControl 增益回调的 HAL 可通过 `onAudioDeviceGainsChanged` 通知 CarAudioService 更新车外改变的音量状态。

该接口把组增益设置到音频设备端口，再由 HAL/车型实现决定最终执行位置，常见为 DSP 或功放。不能仅凭 car audio 配置断言必然是纯硬件衰减，也不能推断一定保持 bit-perfect。音量键无效或滑条跳变时，先查设备 AudioGain 范围/步进与 HAL 支持，再查 gain 回调联动。

**Q7: [learning] 物理音量键怎么知道该调哪个区、哪个组？**

CarAudioService 注册 CarAudioPolicyVolumeCallback（继承 `AudioPolicy.AudioPolicyVolumeCallback`），物理音量键经 `onVolumeAdjustment` 回调进来。Android 13 实现固定调主音区，zone id 为 `PRIMARY_AUDIO_ZONE`。系统根据当前主区活动声音的建议 context 找到对应音量组，再按 `ADJUST_RAISE`、`ADJUST_LOWER` 或 mute 动作调整组增益索引。源码线索：`getSuggestedAudioContextForPrimaryZone` 选择建议 context，`getVolumeGroupIdForAudioContext` 查找组 id。

1. **只作用于主区**：副驾、后排的音量不受物理音量键影响。
2. **组随播报状态切换**：主区同时有导航和媒体出声时，键调的是建议 context 对应的组，因此音量键有时调媒体组，有时调导航组。

**Q8: [learning] 车机焦点裁决与手机“栈式”行为差在哪？矩阵怎么工作？**

车机按持焦者与请求者的 context 对查矩阵，而不是维护单一全局焦点栈。FocusInteraction 的 13×13 `sInteractionMatrix[持焦者 context][请求者 context]` 返回三种裁决：`REJECT`、`EXCLUSIVE` 或 `CONCURRENT`。

1. **REJECT**：拒绝新请求。
2. **EXCLUSIVE**：允许新请求，并使既有持焦者失焦。
3. **CONCURRENT**：允许并发持焦，例如默认矩阵中的导航播报与音乐。
4. **可配置例外**：通话期间拒绝导航可由 `KEY_AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL` 控制，CarAudioSettings 按用户保存设置。

手机焦点更接近新请求影响既有持焦者的栈式模型，没有按用途组合配置的并发矩阵。排查车机的焦点意外行为时，先确认矩阵单元格与用户设置，再分析时序竞态。

**Q9: [learning] 焦点请求什么时候返回 DELAYED？应用该怎么处理？**

请求只有在 gain 为 `AUDIOFOCUS_GAIN` 且带 `AUDIOFOCUS_FLAG_DELAY_OK` 时才可能延迟。CarAudioFocus 对当前持焦者逐一裁决，任一持焦者使请求延迟，整个请求会进入 `mDelayedRequest`，并向调用方返回 `AUDIOFOCUS_REQUEST_DELAYED`。持焦者释放后，系统可提升延迟请求并回调焦点变化。

应用需要区分以下结果：

1. **DELAYED 既不是失败也不是 granted**：UI 与播放必须等 granted 回调才出声。
2. **同监听器互斥**：已有 pending 延迟请求时，同监听器再请求其他 usage 会直接失败，避免一个监听器同时登记多项待提升请求。
3. **放弃即清理**：abandon 请求会清掉延迟队列中的对应项。

额外拒绝规则：请求 NOTIFICATION 时，若任一持焦者持有 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`，CarAudioFocus 会直接拒绝请求。这是对齐默认策略引擎的硬编码规则，不由交互矩阵表达。

**Q10: [learning] ducking 由谁执行？被压低的是哪些设备？**

焦点变化后，CarDucking 按音区重新计算 DuckingInfo，再根据 HAL 信号配置决定是否把设备 duck 指令发送给 AudioControl HAL。设备选择与发送过程包含以下边界：

1. **设备列表**：DuckingInfo 为每个 zone 记录需要 duck 的 bus 地址及其承载 context。
2. **发送条件**：`audioUseHalDuckingSignals` 控制 CarService 是否尝试通过 `onDevicesToDuckChange` 通知 AudioControl HAL。Android 13 AOSP 默认值是 `true`，省略 OEM overlay 时采用该默认值。设为 `false` 时不调用此通知。设为 `true` 仍要求 AIDL HAL 实现回调。HIDL 2.0 封装不支持此通知，调用对应方法会抛出 UnsupportedOperationException。
3. **duck 规则**：CarDuckingUtils 按持焦 context 可压制的目标 context 选择设备。`MUSIC` 与 `ANNOUNCEMENT` 持焦时不主动 duck 其他 context。
4. **共享设备**：若同一 bus 上仍有未被 duck 的持焦 context，该设备会从 duck 列表移除，避免连带压低不该被压制的声音。
5. **执行边界**：AudioControl HAL/车型实现负责执行设备 duck。应用无需自行调低播放器音量。应用侧可在收到焦点或状态变化时调整界面呈现。

**Q11: [learning] 车机的 mute 与“音量调零”、AudioFlinger master mute 差在哪？**

车机组 mute 是按区、按音量组计算的静音状态，再经 AIDL AudioControl HAL 的 `onDevicesToMuteChange` 通知 HAL。Android 13 AOSP 默认关闭 `audioUseCarVolumeGroupMuting`，省略 OEM overlay 时使用 master mute。启用组 mute 需将该资源设为 `true`，并由 AIDL HAL 实现通知回调。它与组增益置零及 AudioFlinger master mute 的差别如下：

1. **mute 与组增益置零**：mute 保留增益上下文，取消后可恢复原音量。将组增益设为零会改变当前增益值。
2. **mute 与 master mute**：master mute 是 audioserver 内的全局软静音，不分区不分设备。
3. **走哪条由配置决定**：开启 `audioUseCarVolumeGroupMuting` 时，Android 13 要求 `audioVolumeAdjustmentContextsVersion` 显式解析为 2，否则 CarAudioService 构造时抛出 IllegalArgumentException。AAOS 13 AOSP 该版本资源默认值为 2。OEM overlay 若改成 1，启用组 mute 就会启动失败。关闭组 mute 时使用 `audioPersistMasterMuteState` 控制是否在启动时恢复全局 master mute。AAOS 13 AOSP 默认开启该持久化资源。

排查“某区突然全静”按链路顺序查：组 mute 状态 → HAL mute 指令 → master mute。

**Q12: [learning] 车外系统（雷达、ECU）的声音怎样进入 Android 焦点裁决？**

HalAudioFocus 将车外声音源的焦点请求转换为 Android AudioManager 请求，CarAudioFocus 因而可以用与应用相同的 context 矩阵裁决。车外请求也带 zoneId，所以会受音区隔离约束。

1. **请求入口**：AudioControl HAL 通过 IFocusListener 回调 `requestAudioFocus(usage, zoneId, focusGain)`，表达车外声音的用途、目标 zone 和焦点类型。
2. **Android 侧记账**：CarService 的 HalAudioFocus 按 zone 与 usage 跟踪 HAL 请求，并用 AudioFocusRequest 向 AudioManager 发起请求。
3. **结果回传**：裁决变化经 `onAudioFocusChange(usage, zoneId, status)` 通知 AudioControl HAL。
4. **排查含义**：倒车提示、转向灯音等车外声音可能成为应用失焦的来源。检查请求是否使用正确 zoneId，避免车外音源抢占错误音区。

**Q13: [learning] CarAudioZonesValidator 会拒绝哪些非法配置？多音区无声怎样排查？**

CarAudioZonesValidator 在 CarAudioService 设置音区时校验配置，结构错误会抛异常并阻止该服务正常初始化。服务启动失败先看 validator 报错。服务启动后再按数据面逐层定位无声。

Android 13 校验的主要约束包括：

1. 配置至少包含一个 audio zone。
2. 每个 audio zone 至少包含一个 volume group。
3. device address 在所有音区中全局唯一。
4. primary zone 必须声明输入设备，例如麦克风。

CarAudioService 正常启动后按以下顺序排查：

1. **看 CarAudioService 状态**：`dumpsys car_service --services CarAudioService` 查区/音量组/occupant 映射与 duck/mute 状态。
2. **看策略面**：`dumpsys audio` 查 AudioPolicy 侧 bus 设备连接、mix 命中与焦点条目的 zoneId。
3. **回配置核对一致性**：car_audio_configuration.xml 的 context-to-bus 映射与 audio_policy_configuration.xml 的 bus 声明必须一致。文件位置优先 `/vendor`，再回退 `/system`。改动不生效时先确认服务加载了哪一份配置。

**Q14: [learning] Android 13 的 AudioControl HAL 版本分别提供什么能力？哪些功能受接口版本限制？**

Android 13 的能力边界要按 HIDL 与 AIDL 两条接口演进线区分。HIDL 2.0 增加车外焦点请求，但 Android 13 的 HIDL 2.0 CarService 封装明确不支持设备 duck/mute 通知。AIDL 提供这些通知，AIDL 2.0 再增加播放 metadata 和设备增益回调。

1. **HIDL 1.0**：提供 fade 与 balance 控制。它没有 HIDL 2.0 新增的车外焦点请求接口。
2. **HIDL 2.0**：提供 HAL 发起焦点请求的 IFocusListener 和焦点状态通知。它不提供 Android 13 CarService 可用的设备 duck/mute 通知。AudioControlWrapperV2 对这两个调用抛出 UnsupportedOperationException。
3. **AIDL 1.0**：承接 HIDL 2.0 的焦点接口，并提供 AIDL 形式的设备 duck/mute 通知。要启用车载 duck 或音量组 mute，除资源开关外，AIDL HAL 还必须实现相应回调。
4. **AIDL 2.0（Android 13）**：增加带 PlaybackTrackMetadata 的焦点请求，以及音频设备增益变化回调。未实现新方法的 HAL 不能提供这些新增信息或增益同步能力。

版本号只表明接口契约，不证明厂商实现已经正确接通 DSP、功放或整车控制链路。排查 duck/mute 未生效、HAL 焦点未进入 Android、车外音量变化未同步时，依次核对 CarService 选中的接口封装、资源开关、HAL 声明和实现、车辆侧执行结果。能力演进依据 Android Automotive Audio control HAL 文档及 Android 13 `AudioControlWrapperV2` 实现。

**Q15: [learning] Android 13 的车机音频有哪些“还做不到”？哪些要等 14+？**

Android 13 的 car audio 框架不支持以下 Android 14 能力：

1. **version 3 配置**：Android 14 的 car_audio_configuration.xml version 3 支持 OEM-defined contexts 与非主音区动态配置。
2. **音频镜像**：Android 14 加入 audio mirroring 配置和 API，可按支持的车载路由把一个区域的音频复制到其他设备/区域。
3. **主区媒体焦点唯一持有者**：该限制从 Android 14 起提供。Android 13 可出现多个主区媒体持焦者，不能把这种差异直接判成缺陷。

因此在 Android 13 上镜像不可用或多个应用同时持有主区媒体焦点属于版本能力边界。升级评估时应把这些能力列入需求，并核实目标车型的 HAL 支持。
