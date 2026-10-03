# AAOS 车机音频

> 学习资料（文章模式沉淀）。主线：CarAudioService 与 AudioControl HAL 的分工、car_audio_configuration.xml 的 zone/音量组/context 结构与版本、context→bus 动态路由、应用落区判定、硬件增益音量模型、音量键落点、焦点交互矩阵与延迟焦点、ducking/muting、HalAudioFocus、配置校验与排查。机制按本地 AAOS13 源码（Android 13）核对；car_audio_configuration version 3（Android 14 引入 OEM 自定义 context 等）及之后的版本为官方文档结论（2026-09 检索）。AOSP 侧子系统机制见 [01-aosp-audio.md](01-aosp-audio.md)；应用侧延迟实践见 [04-audio-latency.md](04-audio-latency.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 车机音频和手机音频架构差在哪？为什么多了 AudioControl HAL 与一份 car_audio_configuration.xml？**

AudioFlinger/AudioPolicyService 这套下沉机制不变；车机在其上加了两层：声明式拓扑与整车音频 HAL。一辆车有十几路功放通道、主副驾与后排多个听音区，导航/媒体/雷达提示要按用途分设备，这些拓扑因车而异，不能编译进代码。AudioControl HAL 承接整车属性——焦点请求与回调、duck/mute 指令、增益变化回调、前后（fade）与左右（balance）声场（IAudioControl 接口方法核对）。

配置由两份文件分担：

- **`/vendor/etc/car_audio_configuration.xml`**（优先）或 `/system/etc/car_audio_configuration.xml`（回退，路径常量核对）：描述"音区→音量组→bus 设备"拓扑，由 CarAudioService 解析执行。
- **audio_policy_configuration.xml**：声明 bus 设备等 HAL 端点，供策略引擎选择。

audio bus 是 `TYPE_BUS` 虚拟设备，HAL 侧把它映射到真实功放通道。职责划分一句话：AudioPolicy 决定流到哪路 bus，AudioControl 决定这路声音以多大增益出车。

**Q2: car_audio_configuration.xml 的三层结构是什么？Android 13 支持到 version 几？**

三层结构：

- **`zones`**（音频区）：primary zone 用 `isPrimary="true"` 并携带 `occupantZoneId`；副区显式 `audioZoneId`。
- **`volumeGroups`**（音量组）：区内的音量分组，同组设备同步调音量。
- **`device` 与 `context`**：组内每台 bus 地址及其承载的 context 列表。

以 emulator 样例为准：主区四个音量组——媒体+公告、导航+语音命令、电话+振铃、闹钟+系统音等各归其 bus；副区 bus100/bus200 单组承载全部 context（样例文件核对）。Android 13 源码只支持 version 1/2（CarAudioZonesHelper 的 `SUPPORTED_VERSION_1/2` 常量核对）；version 3（Android 14 引入 OEM 自定义 context 等能力）及之后的版本为官方文档结论（2026-09 检索），在 13 上写 version 3 会在解析阶段失败。这份文件是车机音频定制的主入口：context 落位、音量分组、增减音区都在这里改。

**Q3: CarAudioContext 为什么把几十种 usage 归并成 13 个 context？哪些子系统以它为键？**

AudioAttributes 的 usage 有几十种，但车的路由、音量、焦点只需要粗粒度分类：CarAudioContext 把它们归并为 `INVALID`(0) 到 `ANNOUNCEMENT`(12) 共 13 个语境，数值与 AudioControl HAL 的 ContextNumber 对齐（源码常量与注释核对）。三个子系统都以 context 为键，换算入口 `getContextForUsage`：

- **动态路由**：context→bus 设备。
- **音量组**：context→组。
- **焦点交互矩阵**：context 对→裁决值。

因此应用选错 usage 的后果在车机上被放大：`USAGE_MEDIA` 与 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 落不同 context，会走不同 bus、受不同音量组控制、在焦点矩阵里有不同裁决——手机上"usage 只影响策略细节"的直觉在车机不成立。

**Q4: context 怎么被路由到 bus？静态 XML 与动态 AudioMix 各负责什么？**

两段接力：bus 设备先在 audio_policy_configuration.xml 里声明为 BUS 型 devicePort，成为 AudioPolicy 可选端点；CarAudioService 启动时经 CarAudioDynamicRouting 为每台 bus 构造 AudioMix——把该 bus 承载的每个 context 对应的 usage 加成 `RULE_MATCH_ATTRIBUTE_USAGE` 规则、设 `ROUTE_FLAG_RENDER`，加入 `AudioPolicy.Builder`（源码核对）。运行时 AudioPolicyService 命中 mix 规则就把流投给对应 bus。

所以"某用途没走预期 bus"查两层：car_audio_configuration.xml 里这个 context 是否挂在这台 bus 上、audio_policy_configuration.xml 里这台 bus 是否声明且已连接。文件改了不生效先确认加载的是哪一份（/vendor 优先于 /system）。

**Q5: 多音区下应用的声音怎么进入正确音区？落错区怎么查？**

CarAudioService 按音区（audio zone）完全隔离焦点与路由，主驾的焦点竞争不会跨到副驾；一条流进哪个区由三条路径决定，优先级从系统到应用：

- **自动映射**：默认按调用方的 display/occupant zone 映射到 audio zone——映射配置与应用预期不一致是落错区的首因。
- **uid 绑定**：系统侧可按 uid 把某应用的流强制进指定音区（CarAudioService 维护 `mUidToZoneMap`，源码核对），绑定错误同样造成落错区。
- **应用显式指定**：AudioAttributes bundle 里的 `AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID` 显式给出目标区；未映射上时走 fallback，日志 "dispatching audio focus request to zoneId %d" 可确认实际落区。

Android 13 中每个音区一个独立焦点管理器（CarZonesAudioFocus 按 zone 建实例），主区与副区走同一套矩阵裁决，没有额外豁免；"主区媒体焦点唯一持有者"（重复请求报 "already owns the primary audio zone"）是 Android 14 起引入的限制，本地 13 源码无对应逻辑（A14 镜像口径，2026-09 检索）。诊断入口：`dumpsys audio` 看焦点条目携带的 zoneId。

**Q6: 音量组的音量落到哪一层？为什么说车机音量是"硬件增益"而非 AudioFlinger 流音量？**

用户滑条对应音量组增益，音量按（音区, 音量组）二维管理。调用链依次是 `CarAudioManager.setGroupVolume()`、CarVolumeGroup.setCurrentGainIndex、组内每台 bus 设备 `setCurrentGain`、`AudioManagerHelper.setAudioDeviceGain()`（createAudioPatch 写设备端口增益，源码核对），单位毫贝，可用范围与步进来自该设备 AudioGain 声明的 min/max/step；同组多台设备同步取同一增益。AudioControl 支持增益回调时，车外音量变化还会经 `onAudioDeviceGainsChanged` 联动回 CarAudioService（同模块核对）。

这与手机"调 AudioFlinger 流音量"不同：车机把增益交给 DSP/功放执行，软件侧不做衰减，大小音量都不损失混音精度。排查要点随之明确：音量键无效或滑条跳变，先查设备 AudioGain 声明与 HAL gain 支持，再查 gain 回调联动，而不是在应用层或流音量里找。

**Q7: 物理音量键怎么知道该调哪个区、哪个组？**

CarAudioService 注册 CarAudioPolicyVolumeCallback（继承 `AudioPolicy.AudioPolicyVolumeCallback`），物理音量键经 `onVolumeAdjustment` 回调进来；Android 13 实现固定调主音区：区 id 取 `PRIMARY_AUDIO_ZONE`，组取"当前主区活动声音的建议 context"对应的组（`getSuggestedAudioContextForPrimaryZone` → `getVolumeGroupIdForAudioContext`，源码核对），再按 `ADJUST_RAISE`/`ADJUST_LOWER`/mute 动作调组增益索引。两个直接结论：

- **只作用于主区**：副驾、后排的音量不受物理音量键影响。
- **组随播报状态切换**：主区同时有导航和媒体出声时，键调的是"建议 context"的组——这就是"有时音量键调媒体、有时调导航"的实现原因。

**Q8: 车机焦点裁决与手机"栈式"行为差在哪？矩阵怎么工作？**

车机按 context 对裁决而非全局栈：FocusInteraction 维护 13×13 的 `sInteractionMatrix[持焦者 context][请求者 context]`，取三值——`REJECT`（拒绝请求）、`EXCLUSIVE`（请求成功、持焦者丢焦）、`CONCURRENT`（共存同时出声）（常量与矩阵核对）。导航播报与音乐并发是矩阵内建行为；手机框架的焦点近似"新请求压旧请求"的栈模型，没有按用途对的两两共存表。个别单元格可被用户设置改写：通话中拒导航对应 `KEY_AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL`，按用户与配置版本经 CarAudioSettings 读写（源码核对）。排查含义：焦点"意外"先查矩阵值与用户设置改写，再怀疑时序竞态。

**Q9: 焦点请求什么时候返回 DELAYED？应用该怎么处理？**

请求满足两个条件才可能延迟：gain 为 `AUDIOFOCUS_GAIN`（持久请求）且带 `AUDIOFOCUS_FLAG_DELAY_OK` 标志（canReceiveDelayedFocus 核对）。裁决时对每个持焦者逐个求值，任一返回 DELAYED，整个请求挂入 `mDelayedRequest` 并返回 `AUDIOFOCUS_REQUEST_DELAYED`（源码核对）；持焦者释放后系统自动提升延迟请求并回调焦点变化。应用要点三条：

- **DELAYED 既不是失败也不是 granted**：UI 与播放必须等 granted 回调才出声。
- **同监听器互斥**：已有 pending 延迟请求时，同监听器再请求其他 usage 直接失败（源码核对 "cannot request focus for X on same listener"）。
- **放弃即清理**：abandon 请求会清掉延迟队列中的对应项。

另有一条硬规则：请求 NOTIFICATION 时若任一持焦者持有 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`，直接拒绝（源码核对，注释言明这是对齐默认策略引擎的硬编码行为，矩阵本身没有该表达能力）。

**Q10: ducking 由谁执行？被压低的是哪些设备？**

焦点变化后 CarDucking 按音区重新计算 DuckingInfo——每区哪些 bus 地址要 duck、承载什么 context——经 AudioControlWrapper 的 `onDevicesToDuckChange` 交给 AudioControl HAL，由功放/DSP 侧降增益（源码核对调用点）；是否启用 HAL duck 信号由 `audioUseHalDuckingSignals` 配置决定（CarAudioService 字段核对）。设备选择规则在 CarDuckingUtils：持焦 context 压它能压的 context 所在设备，`MUSIC` 与 `ANNOUNCEMENT` 持焦不主动压别人（源码注释核对）；一台设备上若仍有未被压制的 context 持焦，该设备从 duck 列表移除。应用侧不需要也不能自己实现 duck：收到 duck 状态回调后只需调整呈现（如界面提示），音量由系统在硬件侧处理。

**Q11: 车机的 mute 与"音量调零"、AudioFlinger master mute 差在哪？**

车机 mute 是按区按组的设备级静音指令：CarVolumeGroupMuting 计算每区每设备的 mute 状态，经 `onDevicesToMuteChange` 通知 AudioControl HAL 执行（源码核对）。三者区别：

- **mute 与组增益置零**：mute 保留增益上下文，取消即恢复原音量；置零则丢掉原值。
- **mute 与 master mute**：master mute 是 audioserver 内的全局软静音，不分区不分设备。
- **走哪条由配置决定**：`audioUseCarVolumeGroupMuting` 开启时配置必须为 version 2，否则 CarAudioService 构造时直接抛 IllegalArgumentException（源码核对）；关闭时走 master mute 持久化路径。

排查"某区突然全静"按链路顺序查：组 mute 状态 → HAL mute 指令 → master mute。

**Q12: 车外系统（雷达、ECU）的声音怎么进入焦点体系？**

HalAudioFocus 让非 Android 侧的声音走同一套焦点规则：AudioControl HAL 持有反向回调 IFocusListener，车外系统想出声时调 `requestAudioFocus(usage, zoneId, focusGain)`，CarAudioService 内的 HalAudioFocus 按（zone, usage）记账并用 AudioFocusRequest 向 AudioManager 发起请求，裁决结果经 `onAudioFocusChange(usage, zoneId, status)` 回传 HAL（源码核对方法与请求表）。含义：倒车提示、转向灯音与 Android 应用在同一矩阵下竞争焦点，应用"被突然抢焦"的来源可能在 HAL 侧；这些请求同样受音区隔离约束——HAL 必须给出正确 zoneId，配置错区会抢错区里的焦点。

**Q13: 配置非法怎么被拦住？多音区不发声的排查顺序是什么？**

配置由 CarAudioZonesValidator 四步校验：至少定义一个 zone、每 zone 至少一个音量组、device address 全局唯一、primary zone 必须有输入（麦克风）设备（源码核对四步）；违反直接抛 RuntimeException 使 CarAudioService 启动失败——所以"音频服务没起来"先看这份校验异常。服务起来后按层排查：

1. **看 CarAudioService 状态**：`dumpsys car_service --services CarAudioService` 查区/音量组/occupant 映射与 duck/mute 状态。
2. **看策略面**：`dumpsys audio` 查 AudioPolicy 侧 bus 设备连接、mix 命中与焦点条目的 zoneId。
3. **回配置核对一致性**：car_audio_configuration.xml 的 context→bus 与 audio_policy_configuration.xml 的 bus 声明缺一不可；文件位置 /vendor 优先于 /system（路径常量核对），"改了不生效"先确认加载的是哪一份。

**Q14: AudioControl HAL 有哪几个版本？HAL 版本不够时哪些功能会"静默消失"？**

Android 13 的 CarService 内置三个封装，按设备声明的 HAL 版本选择（AudioControlWrapperV1/V2/AIDL 文件核对）：

- **HIDL 1.0（WrapperV1）**：焦点回调 `onAudioFocusChange`、焦点监听注册、前后/左右声场（setFadeTowardFront/setBalanceTowardRight）。
- **HIDL 2.0（WrapperV2）**：增加 duck/mute 通知——`onDevicesToDuckChange`、`onDevicesToMuteChange`（V2 封装源码核对）。
- **AIDL（WrapperAidl，本地 13 已有）**：再加增益回调 `registerGainCallback`/`onAudioDeviceGainsChanged` 与带 metadata 的焦点通知（IAudioControl 方法清单核对）。

关键排查意识：HAL 未实现的能力**不报错但没功能**——只做 V1 的整车，duck/mute 指令和增益回调都收不到，应用侧表现为"duck 不降音量、车外音量变化不同步"。对比两台车的音频行为差异时，先确认 AudioControl 版本与各方法的实现完整度，再查应用与 CarService。

**Q15: Android 13 的车机音频有哪些"还做不到"？哪些要等 14+？**

本地 13 源码核对的边界：car_audio_configuration 只支持 version 1/2；没有音频镜像（audio/ 目录无任何 mirroring 相关类）；没有 OEM 自定义 context；主音区没有"媒体焦点唯一持有者"限制（焦点代码无对应逻辑）。14+ 的能力（官方文档与 A14 镜像口径，2026-09 检索）：version 3 配置引入 OEM 自定义 context 与 mirroring 设备声明、音频镜像 API（把一个音区的输出复制到另一音区）、主区媒体焦点限制为唯一持有者。排查意义：在 13 上"镜像不生效""主区两个应用同时拿媒体焦点"不是缺陷而是版本边界，升级评估时把这三项列入功能清单。
