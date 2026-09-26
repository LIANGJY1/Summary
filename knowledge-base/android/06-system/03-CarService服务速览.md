# CarService 服务速览

> 学习资料（文章模式沉淀）。主线：packages/services/Car 中除核心链路（电源/属性/音频/多用户/UXR，见 [02-OEM与设备差异.md](./02-OEM与设备差异.md)）之外的其余系统服务——媒体源管理、蓝牙策略、遥测、诊断、车机 bugreport、设备策略、存储监控与投影宿主。机制按 LineageOS `lineage-21.0` 镜像（AOSP Android 14）源码逐服务核对（2026-09-25）；CarUxRestrictions 定制与驾驶状态推导的专项题亦在 OEM 册（Q37–Q39）。Q 序列即结构，供 atlas 同源直读。

**Q1: CarService 里除了电源/属性/音频这些核心服务，还有哪些系统服务？怎么快速建立全景？**

全景从 `CarFeatureController` 入手：它把全部服务分为 mandatory/optional/experimental 三类——optional 服务（如诊断、存储监控）由特性开关决定是否实例化，OEM 可整体关闭。核心链路之外的常驻服务按职责记一批名字即可：

1. **输入与驾驶状态**：`CarInputService`（VHAL 硬键/旋钮统一处理）、`CarDrivingStateService`（parked/idling/moving 推导）；
2. **媒体与互联**：`CarMediaService`（活跃媒体源，见 Q2）、`CarProjectionService`（手机投影宿主，见 Q9）；
3. **设备管理面**：`CarBluetoothService`（Q3）、`CarDevicePolicyService`（Q7）、`CarBugreportManagerService`（Q6）、`CarTelemetryService`（Q4）、`CarDiagnosticService`（Q5）、`CarStorageMonitoringService`（Q8）；
4. **环境与位置**：`CarLocationService`（关机前存最后位置、开机恢复）、`GarageModeService`（停车期后台维护窗口）、`CarNightService`（日夜切换）；
5. **多屏多用户底座**：`CarOccupantZoneService`（座位↔屏幕↔音频区↔用户映射）、`CarActivityService`（任务栈监控与 setPersistentActivity）；
6. **实验与代理**：`CarExperimentalFeatureServiceController`（绑定 ExperimentalCarService 的实验特性框架）、`CarOemProxyService`（OEM 定制逻辑代理）。

**Q2: CarMediaService 管什么？"开机恢复上次媒体源"是怎么实现的？媒体应用要注意什么？**

CarMediaService 管理车机的"当前活跃媒体源"——注意车内同一时刻只有一个活跃源，且分 PLAYBACK/BROWSE 两种模式独立记忆；它不是 MediaSessionManager 的活跃 session 列表。

1. **状态存储**：按 mode + userId 双维度持久化；多乘客"独立播放"靠 seat → occupant zone → userId 映射间接实现——`setIndependentPlaybackConfig` 指的是同一用户内两个模式能否各自独立设置，这是最常见的误解点；
2. **应用行为的影响**：服务监听活跃 MediaSession 记录播放状态；应用卸载/禁用时回退到上一媒体源；方向盘媒体按键按 seat 找到对应用户后分发给其活跃 session；
3. **开机恢复链**：用户解锁触发读上次播放源 → 按 `config_mediaBootAutoplay`（NEVER/ALWAYS/按上次状态/沿用上次源）决定是否自动播放 → 绑定目标应用的 **MediaBrowser**（附 AUTOPLAY 参数）——所以媒体应用必须实现 `MediaBrowserService`，否则"恢复播放"接不上；
4. **权限边界**：`CarMediaManager` 的查询/设置接口全部要 `MEDIA_CONTENT_CONTROL`——是给系统 UI/媒体中心用的；三方媒体应用只需正常实现 MediaSession/MediaBrowserService 即可被纳管。

**Q3: CarBluetoothService 管什么？"上车自动连蓝牙"的触发条件是什么？应用还能配优先级吗？**

CarBluetoothService 管理当前用户的蓝牙设备与 profile 连接：为每个用户维护按优先级排序的已知设备列表、profile 抑制（inhibit）管理与默认自动连接策略（策略可通过 resource overlay 换成 OEM 实现）。

1. **触发时机**：蓝牙开启、收到 SEAT_OCCUPANCY 座椅占用事件、init 完成时触发自动连接——且注释明确**只在 parked（P 挡）状态触发**，防止驾驶中操作并过滤行驶中的假占用信号；
2. **API 变化**：`CarBluetoothManager` 在 Android 14 的 car-lib 中已移除（Android 13 起废弃优先级设置接口）——应用侧只剩标准蓝牙 API，自动连接由系统策略接管；
3. **多用户**：设备优先级列表按用户持久化——多用户车机上"换了驾驶员蓝牙列表就变了"是设计行为；
4. **边界**：它不实现蓝牙协议栈，只是设备管理 + 连接时机策略层，底层仍用标准各 Profile。

**Q4: CarTelemetryService 是干什么的？为什么三方应用用不了？**

CarTelemetryService 是 OEM 遥测的收集与处理服务：客户端推送 MetricsConfig（带 name+version 的脚本配置），由独立的 ScriptExecutor APK 执行 Lua 订阅脚本产出报告，客户端经 ReportReadyListener 提醒后拉取。

1. **谁能用**：全部 API 是 `@SystemApi @hide`，需要 signature/privileged 级的 `USE_CAR_TELEMETRY_SERVICE` 权限，manager 文档原话是"唯一的消费者是 OEM 云端应用"——三方应用不在设计范围内；
2. **契约要点**：同 name 高版本覆盖旧版并清空历史；add 最常见的失败是 `SIGNATURE_VERIFICATION_FAILED`（配置签名须与调用应用匹配）；脚本运行错误只体现在返回的 telemetryError 字段；
3. **易混淆**：同仓库的 `cartelemetryd`（C++ 守护进程）是面向 EVS/原生客户端的另一条遥测通道，与 Java 侧服务并存，别当成同一个东西；
4. **版本**：Android 14 已定型为上述形态；脚本结果直传服务端（server-side telemetry）是 Android 15 才引入的。

**Q5: CarDiagnosticService 暴露什么数据？为什么"实现了也不一定有"？**

它把 VHAL 里的 OBD-II 式诊断数据（live frame 实时帧 / freeze frame 冻结帧）以统一 API 暴露给特权应用——能力完全取决于 VHAL 是否实现了 OBD2_LIVE_FRAME/OBD2_FREEZE_FRAME 等属性，很多参考 VHAL 只给最小实现。

1. **权限分级**：读取（注册监听/取帧）要 `CAR_DIAGNOSTIC_READ_ALL`；清除冻结帧是破坏性操作、单独要 `CAR_DIAGNOSTIC_CLEAR`——普通应用拿不到这两个特权权限；
2. **可选特性**：诊断服务是 `@OptionalFeature`，OEM 可整体关闭——排查"接口不存在"先确认特性开关；
3. **应用侧**：CarDiagnosticManager 为 `@hide`，提供实时帧监听、冻结帧时间戳枚举/读取/清除——OBD2 帧内容是 sensor 索引值对，不通过公开 SDK API 暴露。

**Q6: 车机版的 bugreport 服务和标准 BugreportManager 有什么区别？**

CarBugreportManagerService 走车机专用的 `carbugreportd`/`cardumpstatez` 通道：无分享确认弹窗、直接写调用方提供的两个输出 fd（主 zip + 额外输出），专为车机可控采集设计。

1. **使用前提**：需要 `DUMP` 权限的系统应用触发，且**只在 userdebug/eng 构建可用**（user build 上服务直接不可用）——线上用户版拿不到；
2. **约束**：同一时刻只允许一个 bugreport（并发返回 IN_PROGRESS）；进度经 onProgress(0–100) 回调、错误按 DUMPSTATE_FAILED/CONNECTION_FAILED 等码区分；
3. **边界**：标准 BugreportManager 走 framework dumpstate 加用户确认 UI——两条通道不要混为一谈。

**Q7: CarDevicePolicyService 管什么？"驾驶中限制拨出电话"是它做的吗？**

CarDevicePolicyService 是 DevicePolicyManager 在车机上的受限子集（创建/移除用户走 CarUserService 且强制 caller restrictions），外加车机特有的"新用户设备管理免责声明"转发——每个新用户要看一次通知。

1. **驾驶中限拨的真相（勘误）**：把"resisted outgoing call"归到 CarDevicePolicyService 是错误归因——该术语在源码中不存在；实际由三处协作：UXR 的 DRIVING 限制（拨号盘禁用，见 OEM 册 Q37）、CarInputService 的 CALL 键处理（重拨上次号码/通话中挂断的配置项）、拨号应用自身的 UX 策略；
2. **权限**：用户管理接口要 `MANAGE_USERS` 或 `INTERACT_ACROSS_USERS`——同样是特权面；
3. **边界**：guest/ephemeral 等用户限制逻辑实际在 CarUserService，admin 包只是门面。

**Q8: CarStorageMonitoringService 还能用吗？**

处于边缘化状态：未标 `@Deprecated` 但被标为 `@OptionalFeature`——OEM 可不启用；per-UID 磁盘 I/O 健康监控的职责事实上已由同仓库的 CarWatchdog 承担，新代码不应再依赖它。

1. **它做什么**：从 `/proc/uid_io/stats` 周期采样 per-UID 读写统计，滑动窗口保留样本、维护开机以来累计 I/O，监听者收到 IoStats 快照——消费方主要是调试/性能工具；
2. **现状原因**：持续向用户态广播 IoStats 成本不低，OEM 普遍关闭此 feature 改用 CarWatchdog（其 I/O 过载治理见 OEM 册 Q26）；
3. **排查提示**：接口不存在先查特性开关，再确认设备是否启用了这条旧通道。

**Q9: CarProjectionService 是干什么的？手机互联（CarPlay/CarLife 类）方案和它什么关系？**

CarProjectionService 是投影类应用的宿主：给投影应用提升进程优先级（bind 保活）、转发语音/电话硬键、按需建立 Wi-Fi AP 供手机连接——AAOS 只提供平台侧 API，不含任何具体互联协议实现。

1. **硬键事件**：投影应用用 `addKeyEventHandler` 显式声明要接的事件集（语音键/呼叫键的按下、短按、长按），声明了才收得到；旧常量已废弃；
2. **AP 建立**：`startProjectionAccessPoint` 底层走系统 tethering/SoftAP——失败码含 TETHERING_DISALLOWED（政策不允许）等，受系统 tethering 政策约束；
3. **权限与形态**：`CarProjectionManager` 为 `@hide`，全部入口要 signature|privileged 级的 `CAR_PROJECTION` 权限——三方互联方案须与 OEM 合作预置，不能运行时自装；
4. **语音入口**：与 CarInputService 配合，投影应用可在语音会话显示回调中抢占语音入口。
