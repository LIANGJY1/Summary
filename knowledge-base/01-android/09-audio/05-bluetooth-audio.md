# 蓝牙音频

> 学习资料（文章模式沉淀）。主线：蓝牙音频路径的延迟口径与实测、LE Audio 的 LC3 延迟预算、单播/广播模式取舍、编码 offload 判定与功耗含义、应用侧控制边界、ASHA 与 HAP 的兼容关系、A2DP/SCO 切换与绝对音量排查。Q1 转自 [../03-ui/04-window-system.md](../03-ui/04-window-system.md)（原「显示窗口与音频链路」册的音频部分，2026-09-28 迁入）；Q2–Q6 转自 [../14-cpu-power/02-energy-efficiency.md](../14-cpu-power/02-energy-efficiency.md) 的 LE Audio 部分。证据边界：Q2–Q6 的蓝牙结论转写自源材料（Android 13 起系统级支持的语境与本地 AAOS13 一致），本地 AAOS13 树不含蓝牙模块源码，未做源码级核对；Android 17 专属接口已随题标注。Q 序列即结构，供 atlas 同源直读。

**Q1: "LDAC 固定 30–50 ms、aptX 固定 50–80 ms"这类结论为什么不可用？必须经蓝牙出声的产品该怎么做？**

蓝牙路径的延迟由编码、分包、无线调度、抖动缓冲、耳机解码与本地 DSP 共同决定，同一编解码器在不同缓冲配置、耳机固件、链路质量与模式下差异明显，且音质模式常被混写成低延迟模式，所以任何"编解码器名 → 固定毫秒数"既不代表 Android 平台，也不代表具体链路。可靠做法：固定手机、耳机、编解码器与协议配置，记录实际协商路由，用高速摄像、外部采集或声学回环测量端到端延迟，分开报告中位数、尾部延迟与断续率。对节奏游戏、虚拟乐器等交互场景，内置扬声器或有线/USB 路径更容易获得稳定延迟；必须支持蓝牙时，产品应做延迟校准或按实测提供补偿——手机侧 FastMixer/MMAP 配置成功并不能消除无线传输与耳机端的缓冲。

**Q2: LE Audio 的 LC3 帧时长 7.5 ms，为什么不能推出端到端延迟 20–40 ms？**

LC3 帧时长只是 Codec 参数，不等于算法延迟或端到端延迟。一次稳态单向延迟的预算是：`应用/AudioTrack 缓冲 + AudioFlinger/HAL 缓冲 + 编码与组帧 + ISO 发送窗口 + Presentation Delay + 耳机解码/后处理/渲染`，每一项都可能跨一个或多个音频周期，Presentation Delay 还可能为多设备同步和接收端缓冲而增大；用帧长直接推导总延迟会漏掉手机和耳机两端的缓冲，也忽略 QoS 与射频重传。

讨论延迟前先选定指标：冷启动延迟（用户操作到首次出声）、已连接启动延迟（建流开销）、稳态单向延迟（视频同步、游戏反馈）、双向往返延迟（VoIP）、路由切换间隙、抖动与 glitch，各指标的起点终点不同，测量对象不同。稳态单向延迟建议用已知波形（脉冲或 MLS）加外部声卡互相关测量，报告中位数与 P95/P99；logcat 的"stream started"位于控制面，之后还要经过 Presentation Delay、耳机缓冲与声学渲染，得不出声学端到端延迟。

**Q3: LE Audio 单播（CIS/CIG）与广播（BIS/BIG）在可靠性和扩展性上有什么本质区别？**

单播的 CIS 与 ACL 基础连接关联，接收端可以确认链路层传输，Controller 可在已分配的子事件时隙内安排重传，可靠性可按 RTN/FT 配置；代价是增加设备或音频方向时通常增加 CIS、子事件与控制开销，且 ACL 丢失时关联的 CIS 也终止。广播的 BIS 没有面向每个接收端的确认，发送端只能用重复、交织或预传输提高抗干扰能力，无法针对某个接收端单独重发；好处是发送端空口计划不随收听人数线性增加，但接收端还要执行扫描、周期广播同步和 BIG 同步，这些阶段也有成本。

因此"广播接收一定比单播省电"没有通用依据，"丢一包就必然出现一次可闻断音"也不成立——丢包后的听感取决于 LC3 丢包隐藏、耳机缓冲和连续丢包长度。这两个模式的取舍要按具体场景测量：广播适合一对多分发，单播适合需要确认与重传的双向或高质量场景。

**Q4: 设备有音频 DSP，LE Audio 编码就一定 offload 了吗，这对功耗意味着什么？**

不一定。Android 17 的 `SessionType.aidl` 区分单播/广播的软件编码、硬件 offload 编码以及广播解码、peripheral offload 等数据路径：软件路径中 Bluetooth 栈负责编解码，offload session 主要承载控制、数据与 Codec 工作由平台硬件实现负责。AOSP `codec_manager.cc` 区分 `CodecLocation::HOST` 与 `CodecLocation::ADSP`，并在检查设备属性和 HAL 能力后才启用 offload，源码还保留"offload 不支持某项配置时如何切换"的待完善点，所以"设备有 DSP"不能推出"当前音频流已经 offload"。

对功耗的含义：Host 软件编码会周期性唤醒 AP，offload 可让 AP 保持更长 idle，但控制、缓冲与数据搬运成本仍在。AP 的唤醒节奏取决于缓冲与实现，不能按 10 ms 帧长推出"AP 固定以 100 Hz 唤醒"——Controller 有独立的定时与射频调度能力，不要求 AP 在每个 CIS/BIS event 都运行。判断 LE Audio 是否让 AP 退出深 idle，应查看 trace 中的调度、wakeup source、AudioFlinger 周期与电源轨数据，而不是从帧长推算。

**Q5: 应用侧能控制 LE Audio 到哪一层？**

媒体应用通常继续使用 `AudioTrack`、Media3 等媒体 API；通信应用用 `AudioManager.setCommunicationDevice()` 选择系统已提供的通信设备，结束后调用 `clearCommunicationDevice()`；不能用已弃用的 SCO 开关控制 LE Audio 路由，SCO 属于 HFP/Classic 语音路径，`setBluetoothScoOn()` 也不用于 Auracast 广播。能力检查用状态码而不是布尔值：

```kotlin
val adapter = getSystemService(BluetoothManager::class.java).adapter
val unicastSupported =
    adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED
```

`isLeAudioSupported()` 与 `isLeAudioBroadcastSourceSupported()` 返回状态码，蓝牙关闭时还可能返回错误码；手机报告支持也不代表当前耳机、通信双方的 Profile、区域配置和产品 UI 已满足目标场景，应用仍要处理功能不可用与路由变化。`BluetoothLeAudio` 是 Profile proxy，公开接口只有查询已连接设备、连接状态、组 ID 与 lead device 等，`getActiveDevices()`、`connect()`、`disconnect()` 与 Codec 偏好带 `@Hide`、`@SystemApi` 或 `BLUETOOTH_PRIVILEGED` 限制；A2DP 与 LE Audio 同时可用时的活跃路由由设备能力、用户选择、音频策略与 Profile 状态共同决定，没有"总是优先 LE Audio"的通用规则。

**Q6: ASHA 与 HAP 有什么区别，为什么不能说"HAP 是用 BLE 替代 Classic ASHA"？**

因为 ASHA 本身就运行在 BLE 上。ASHA（Audio Streaming for Hearing Aids）早于标准 LE Audio，使用 GATT 控制、LE L2CAP CoC 传输音频：CoC 的弹性缓冲能抵抗部分丢包但增加延迟，且 ASHA 没有从助听器返回手机的音频 backlink，通话上行使用手机麦克风。HAP（Hearing Access Profile）属于标准 LE Audio/GAF，使用 LE Audio 的能力协商、控制与 ISO 音频机制，可以支持更完整的通话与 VoIP 场景。

Android 需要同时兼容旧有 ASHA 设备与基于 LE Audio 的 HAP 设备，两者使用各自的数据路径与麦克风假设。边界：Android 13 加入系统级 LE Audio 支持不代表所有 Android 13 设备都具备 HAP、广播或双向高采样率能力；助听场景对声学延迟、左右同步、丢包与电池寿命更敏感，固定的延迟或省电比例要在目标产品上验证。

**Q7: 来电时 A2DP 音乐切 SCO 通话，路由上最容易出什么问题？**

一次切换是三件事串行：断开/挂起 A2DP、建立 SCO 链路、把通信流路由到耳机，任何一步慢或失败都表现为"通话没声""音乐停了但声音还在外放"（社区经验口径）。常见坑有三类：

- **扬声器与 SCO 争路由**：`setSpeakerphoneOn()` 与 SCO 建链互踩，该走耳机走了外放——通信路由统一用 communication device API 管理（见 05 册通信路由题）。
- **SCO 采样率骤降**：SCO 语音链路带宽窄（传统 8 kHz，mSBC 16 kHz），媒体与提示音在 SCO 上音质骤降是正常现象，不要按 A2DP 预期调优。
- **切换窗口的音频丢失**：切换瞬间两条路径交替，短提示音可能落在空窗里；产品上把关键提示音避开切换窗口或改走远端。

路由行为随 Android 版本变化明显（通信设备 API、自动回退等），跨版本方案要实机核验。

**Q8: 蓝牙耳机音量"手机调了没反应/两边对不上"怎么查？**

先确认走的是哪种音量机制：绝对音量（AVRCP absolute volume）下手机把音量范围映射给耳机，耳机按键与手机滑条共享一份状态；不支持时手机对音频流做数字增益、耳机侧独立调音量。排查顺序（社区经验口径）：

1. `dumpsys audio` 看绝对音量支持位（`mAvrcpAbsVolSupported` 类字段）是否为 true；
2. 对照手机音量级（如 0–15）到耳机音量级的映射是否单调同步，不对齐多为耳机固件对映射的处理问题；
3. 用开发者选项关闭绝对音量做 A/B——关掉后音量立即正常则问题在映射同步，而不是音频路径；
4. 重连时机导致的状态不同步（断开前音量未持久化）按耳机侧重连恢复逻辑处理。

音量不同步是映射层问题，与编解码、延迟无关，不要往音频路径方向排查。
