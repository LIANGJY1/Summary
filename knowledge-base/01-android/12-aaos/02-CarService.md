# CarService 服务速览

> 范围：CarService 的媒体、蓝牙、遥测、诊断、bugreport、设备策略、存储监控与投影服务。版本敏感的机制以 Android 14 的 packages/services/Car 实现为依据，具体差异以目标设备分支为准。

**Q1: [learning] AAOS CarService 除车辆属性、电源和音频外，还包含哪些服务，怎样按职责建立全景？**

可从 CarFeatureController 建立服务目录：它按 mandatory、optional 和 experimental 等类别组织服务，optional 服务是否实例化受特性开关影响。核心服务以外的服务可按以下职责查找：

1. **输入与驾驶状态**：`CarInputService`（VHAL 硬键/旋钮统一处理）、`CarDrivingStateService`（parked/idling/moving 推导）。
2. **媒体与互联**：`CarMediaService`（管理当前活跃媒体源）、`CarProjectionService`（为投影应用提供宿主能力）。
3. **设备管理面**：`CarBluetoothService`（蓝牙设备和连接策略）、`CarDevicePolicyService`（车机设备策略）、`CarBugreportManagerService`（车机 bugreport）、`CarTelemetryService`（OEM 遥测）、`CarDiagnosticService`（OBD 诊断数据）、`CarStorageMonitoringService`（存储 I/O 监控）。
4. **环境与位置**：`CarLocationService`（关机前存最后位置、开机恢复）、`GarageModeService`（停车期后台维护窗口）、`CarNightService`（日夜切换）。
5. **多屏多用户底座**：`CarOccupantZoneService`（座位↔屏幕↔音频区↔用户映射）、`CarActivityService`（任务栈监控与 setPersistentActivity）。
6. **实验与代理**：`CarExperimentalFeatureServiceController`（绑定 ExperimentalCarService 的实验特性框架）、`CarOemProxyService`（OEM 定制逻辑代理）。

**Q2: [learning] CarMediaService 管什么？“开机恢复上次媒体源”是怎么实现的？媒体应用要注意什么？**

CarMediaService 为当前用户管理活动媒体源，并按 PLAYBACK 和 BROWSE 两种模式记录选择。它决定车机媒体界面当前选中的源，不等同于 MediaSessionManager 的活动 MediaSession 集合。

1. **状态存储：**媒体源状态按播放模式和 userId 保存。乘客之间的媒体状态隔离依赖座位到 occupant zone、再到 userId 的映射。独立播放配置控制同一用户是否能为播放与浏览模式选择不同的媒体源，它不表示为每个座位单独建立播放器。
2. **应用行为的影响**：服务监听活跃 MediaSession 记录播放状态。应用卸载/禁用时回退到上一媒体源。方向盘媒体按键按 seat 找到对应用户后分发给其活跃 session。
3. **开机恢复链：**用户解锁后，服务读取保存的播放源，再按 `config_mediaBootAutoplay` 决定是否恢复播放。AOSP 默认资源值是 2，表示按保存的播放状态恢复，产品 overlay 可覆盖。CarMediaService 的判断函数还接受 0（不播放）、1（播放）和 3（沿用当前内存中的播放状态）。模式 3 主要用于媒体源切换，不能把它解释成开机前上一媒体源的持久状态。服务随后通过媒体连接组件向目标应用传递恢复意图，因此应用需提供可连接的媒体浏览服务。
4. **权限边界：**查询和设置活动源的 CarMediaManager 接口受 `MEDIA_CONTENT_CONTROL` 保护，主要供系统 UI 或媒体中心使用。媒体应用通过正常实现 MediaSession 和 MediaBrowserService 被系统管理，不需要调用这些受保护的管理接口。

**Q3: [learning] CarBluetoothService 管什么？“上车自动连蓝牙”的触发条件是什么？应用还能配优先级吗？**

CarBluetoothService 管理当前用户的蓝牙设备与 profile 连接：为每个用户维护按优先级排序的已知设备列表、profile 抑制（inhibit）管理与默认自动连接策略（策略可通过 resource overlay 换成 OEM 实现）。

1. **触发时机**：默认策略在初始化且蓝牙已开启、蓝牙适配器切换为开启状态，以及驾驶员座椅从未占用变为占用时，可以触发自动连接。只有座椅占用事件路径额外要求 CarDrivingStateService 报告 parked，不能把这一约束套到初始化与蓝牙开启路径。parked 是车载驾驶状态，不应直接与挡位 P 画等号。
2. **API 变化**：`CarBluetoothManager` 在 Android 14 的 car-lib 中已移除（Android 13 起废弃优先级设置接口）——应用侧只剩标准蓝牙 API，自动连接由系统策略接管。
3. **多用户**：设备优先级列表按用户持久化——多用户车机上“换了驾驶员蓝牙列表就变了”是设计行为。
4. **边界**：它不实现蓝牙协议栈，只是设备管理 + 连接时机策略层，底层仍用标准各 Profile。

**Q4: [learning] CarTelemetryService 是干什么的？为什么三方应用用不了？**

CarTelemetryService 是 OEM 遥测的收集与处理服务：客户端推送 MetricsConfig（带 name+version 的脚本配置），由独立的 ScriptExecutor APK 执行 Lua 订阅脚本产出报告，客户端经 ReportReadyListener 提醒后拉取。

1. **谁能用**：全部 API 是 `@SystemApi @hide`，需要 signature/privileged 级的 `USE_CAR_TELEMETRY_SERVICE` 权限，manager 文档原话是“唯一的消费者是 OEM 云端应用”——三方应用不在设计范围内。
2. **契约要点**：同 name 高版本覆盖旧版并清空历史。add 最常见的失败是 `SIGNATURE_VERIFICATION_FAILED`（配置签名须与调用应用匹配）。脚本运行错误只体现在返回的 telemetryError 字段。
3. **易混淆**：同仓库的 `cartelemetryd`（C++ 守护进程）是面向 EVS/原生客户端的另一条遥测通道，与 Java 侧服务并存，别当成同一个东西。
4. **版本**：Android 14 已定型为上述形态。脚本结果直传服务端（server-side telemetry）是 Android 15 才引入的。

**Q5: [learning] CarDiagnosticService 暴露什么数据？为什么“实现了也不一定有”？**

它把 VHAL 里的 OBD-II 式诊断数据（live frame 实时帧 / freeze frame 冻结帧）以统一 API 暴露给特权应用——能力完全取决于 VHAL 是否实现了 OBD2_LIVE_FRAME/OBD2_FREEZE_FRAME 等属性，很多参考 VHAL 只给最小实现。

1. **权限分级**：读取（注册监听/取帧）要 `CAR_DIAGNOSTIC_READ_ALL`。清除冻结帧是破坏性操作、单独要 `CAR_DIAGNOSTIC_CLEAR`——普通应用拿不到这两个特权权限。
2. **可选特性**：诊断服务是 `@OptionalFeature`，OEM 可整体关闭——排查“接口不存在”先确认特性开关。
3. **应用侧**：CarDiagnosticManager 为 `@hide`，提供实时帧监听、冻结帧时间戳枚举/读取/清除——OBD2 帧内容是 sensor 索引值对，不通过公开 SDK API 暴露。

**Q6: [learning] 车机版的 bugreport 服务和标准 BugreportManager 有什么区别？**

CarBugreportManagerService 走车机专用的 `carbugreportd`/`cardumpstatez` 通道：无分享确认弹窗、直接写调用方提供的两个输出 fd（主 zip + 额外输出），专为车机可控采集设计。

1. **调用边界**：所核对的 AOSP CarBugreportManagerService 实现要求调用方具有 `DUMP` 权限，且签名与平台匹配。user 构建还要求调用方 UID 包含 `config_car_bugreport_application` 指定的应用包名。userdebug/eng 构建跳过的是这个指定应用检查，并非整个服务的构建限制。目标版本仍需核对同名服务实现。
2. **约束**：同一时刻只允许一个 bugreport（并发返回 IN_PROGRESS）。进度经 onProgress(0–100) 回调、错误按 DUMPSTATE_FAILED/CONNECTION_FAILED 等码区分。
3. **边界**：标准 BugreportManager 走 framework dumpstate 加用户确认 UI——两条通道不要混为一谈。

**Q7: [learning] CarDevicePolicyService 管什么？“驾驶中限制拨出电话”是它做的吗？**

CarDevicePolicyService 是 DevicePolicyManager 在车机上的受限子集（创建/移除用户走 CarUserService 且强制 caller restrictions），外加车机特有的“新用户设备管理免责声明”转发——每个新用户要看一次通知。

1. **驾驶中限拨的责任边界：**拨号限制不能笼统归到 CarDevicePolicyService。车辆行驶状态对应的 UX 限制可让拨号界面禁用受限操作，CarInputService 根据产品配置处理 CALL 键，拨号应用还需遵守车载 UX 策略。实际行为由平台配置和应用实现共同决定。
2. **权限**：用户管理接口要 `MANAGE_USERS` 或 `INTERACT_ACROSS_USERS`——同样是特权面。
3. **边界**：guest/ephemeral 等用户限制逻辑实际在 CarUserService，admin 包只是门面。

**Q8: [learning] CarStorageMonitoringService 与 CarWatchdog 的磁盘观测职责有什么不同？**

CarStorageMonitoringService 是可选的 I/O 统计观察服务。CarWatchdog 负责按配置跟踪资源超限并采取治理动作。目标设备可能关闭前者，但这不表示所有旧接口都已移除。新代码应先核对目标版本与设备启用的能力，再决定使用统计接口或 CarWatchdog 的超限通知。

1. **它做什么**：从 `/proc/uid_io/stats` 周期采样 per-UID 读写统计，滑动窗口保留样本、维护开机以来累计 I/O，监听者收到 IoStats 快照——消费方主要是调试/性能工具。
2. **能力取舍**：统计快照与超限治理回答不同问题。设备可关闭此可选 feature，并使用 CarWatchdog 的 I/O 过载观测和治理能力，但不能从“可关闭”推断出设备为何关闭。
3. **排查提示**：接口不存在先查特性开关，再确认设备是否启用了这条旧通道。

**Q9: [learning] CarProjectionService 是干什么的？手机互联（CarPlay/CarLife 类）方案和它什么关系？**

CarProjectionService 是投影类应用的宿主：给投影应用提升进程优先级（bind 保活）、转发语音/电话硬键、按需建立 Wi-Fi AP 供手机连接——AAOS 只提供平台侧 API，不含任何具体互联协议实现。

1. **硬键事件**：投影应用用 `addKeyEventHandler` 显式声明要接的事件集（语音键/呼叫键的按下、短按、长按），声明了才收得到。旧常量已废弃。
2. **AP 建立**：`startProjectionAccessPoint` 底层走系统 tethering/SoftAP——失败码含 TETHERING_DISALLOWED（政策不允许）等，受系统 tethering 政策约束。
3. **权限与形态**：`CarProjectionManager` 为 `@hide`，全部入口要 signature|privileged 级的 `CAR_PROJECTION` 权限——三方互联方案须与 OEM 合作预置，不能运行时自装。
4. **语音入口**：与 CarInputService 配合，投影应用可在语音会话显示回调中抢占语音入口。

**Q10: [learning] CarWatchdog 怎么管第三方应用的磁盘写入？地图与媒体应用最容易超限的模式是什么？**

AAOS CarWatchdog 通过内核 /proc/uid_io/stats 的 per-UID I/O 统计跟踪应用与服务的磁盘写入（AAOS13 树 packages/services/Car 的 watchdog 服务端已实现该链路），写入量从每个 UTC 自然日开始累计、跨同一天内的多次车辆启动保留。第三方应用反复超过配置阈值时可被设为 COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED，即停用到用户再次启动或手动启用。CarWatchdogManager 的 getResourceOveruseStats(resourceOveruseFlag, maxStatsPeriod) 按资源类型和统计周期查询数据。Android 13 的 car-lib 提供当前日、过去 3、7、15 或 30 天的周期常量。addResourceOveruseListener(executor, resourceOveruseFlag, listener) 注册异步回调。设备在写入达到其配置阈值的约 80% 或 100% 时可通知监听器，实际阈值由系统配置决定。地图与媒体类别可有更高的独立阈值，具体数值与处理动作由系统和厂商配置共同决定。

地图和媒体应用常见的超限诱因是重复写入和无效持久化：

1. **缓存覆盖：**瓦片与路线缓存反复覆盖同一文件，会放大实际写入量。
2. **数据库日志：**SQLite WAL checkpoint 过于频繁或日志长期未收敛，会持续产生写入。
3. **重复生成：**图片转码和临时文件反复生成，会让相同业务内容被多次落盘。
4. **离线包处理：**重复下载或解压离线包，会造成可避免的存储 I/O。
5. **位置持久化：**每次位置更新都写入持久存储，会把高频状态变化转为大量小写入。

优化时应减少写放大（底层实际写入量大于业务数据量），例如合并改写、控制无效淘汰并复用解压结果。记录逻辑下载字节、文件系统写入字节、缓存命中率和淘汰量，才能验证收益，不能只看下载量。

**Q11: [learning] 挂 R 后倒车影像出得慢甚至黑屏——CarEvsService 的触发链有哪两条？延迟差在哪？**

既有 CarEvsService 实现有两种触发链：`EVS_SERVICE_REQUEST` 属性可直接通知 EVS 服务，另一种是通过 `GEAR_SELECTION==REVERSE` 变化间接触发。后者还经过 CarPropertyService、状态机、EvsActivity 和显示链路。路径差异会影响调度环节，但不能仅凭路径长度断定端到端时延。

这是使用 CarEvsService/EVS 的既有实现路径。AOSP 当前已给出迁移到 Camera2 的指导，并计划弃用 EVS 摄像头 API 与 CarEvsService。新平台应先查目标分支是否仍启用该可选服务，不能把下面的状态机当作所有 AAOS 倒车影像的必经路径。

1. **状态机**：UNAVAILABLE → INACTIVE → REQUESTED → ACTIVE。客户端拿到 REQUESTED 后必须在限时内发起视频流，超时回到 INACTIVE。
2. **触发选择**：目标分支启用 `EVS_SERVICE_REQUEST` 时核对它与挡位订阅的选择逻辑。传统挡位链可能根据时间戳丢弃过期事件。比较速度应实测从挂 R 信号到首帧的完整时长。
3. **停帧根因**：EVS 帧必须逐帧归还（doneWithFrame），buffer 不归还是丢帧/停帧的常见原因。
4. **诊断**：`logcat -s CAR.EVS` 中 `-s` 用于只显示 CAR.EVS 标签的日志，将其时间戳与 VHAL 事件对齐以测量“挂 R 到首帧”的时长。`dumpsys car_service --services CarEvsService` 中 `--services` 将输出限制到指定的 CarService 服务，可检查 CarEvsService 状态机停留位置。

**Q12: [learning] 仪表相关接口抛 "Service is not enabled"、cluster UI 停更但不崩——ClusterHomeService 的机制要点是什么？**

ClusterHomeService 是 ClusterOS 与 ClusterHome 渲染端之间的中介：客户端经它上报状态（CLUSTER_REPORT_STATE）与请求显示（CLUSTER_REQUEST_DISPLAY），HAL 侧的 CLUSTER_SWITCH_UI/CLUSTER_DISPLAY_STATE 驱动 UI 切换。导航态走 navstate2 proto 并可回写 NAVIGATION_STATE 属性。

1. **"Service is not enabled"**：cluster 功能未启用时所有接口调用命中此异常——先确认产品配置与 VHAL 四件套（CLUSTER_SWITCH_UI/CLUSTER_REPORT_STATE/CLUSTER_DISPLAY_STATE/NAVIGATION_STATE）是否实现。
2. **静默吞异常**：渲染端的发送接口内部 RemoteException 被忽略——cluster UI 停更但无 crash 日志时，要主动查链路两端而不是等报错。
3. **FixedActivity**：ClusterHome 以 FixedActivity 方式固定在 cluster display 上，启动失败先核对 displayId 与 userId。
4. **OEM 落点**：渲染侧继承 car-lib 的 `InstrumentClusterRenderingService`。诊断入口 `dumpsys car_service --services ClusterHomeService`。
