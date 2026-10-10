# AAOS 车机 UI 架构与 CarService

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：车机 UI 的分层与定制点、CarService 与 car-lib 的分工、车机 Launcher 与投影共存、Car App Library 模板应用与 AAOS 原生 Activity 两条路线、CarAppService 注册契约与 Car App API level、车机 SystemUI 的独立实现、应用焦点、occupant zone 的座位-显示-用户映射、多用户模型、`CarPowerManager` 与 power policy 对屏幕的接管、日夜模式、旋钮与自定义输入、仪表通道、调试与特性开关。交互安全与驾驶分心见 [07-driving-safety.md](07-driving-safety.md)，配置资源适配见 [03-resources.md](03-resources.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 收到“车机页面改不动”的需求后，如何判断应改应用、SystemUI、CarService 还是车辆层？**

按从上到下的可改动性分四层：

1. **应用内容层**：车机应用自己的 Activity 或模板应用，负责呈现具体功能界面。
2. **系统窗口层**：车机 Launcher、SystemUI（系统栏、状态栏、通知、用户切换、音量、车控对话框等），本地实现位于 `packages/apps/Car/SystemUI/src/com/android/systemui/car/`，模块目录包括 systembar、statusbar、notification、userswitcher、volume、systemdialogs、toast、keyguard、cluster、hvac、voicerecognition、window（本地 AAOS13 源码核对）。
3. **系统服务层**：CarService 下的各服务，负责焦点、驾驶状态、UX 限制、投影、电源策略、占用区、输入等，代码在 `packages/services/Car/service/src/com/android/car/`（本地核对）。
4. **车辆与显示层**：VHAL、显示与电源策略。

改动前先分清面向整机的策略和单应用内容，再确认具体拥有者。SystemUI 或 Launcher 的职责不等于所有“车机通用外观”都归 WMS。窗口管理、系统 UI、CarService 与车辆控制是不同改动边界。

**Q2: 应用需要读取车辆状态时，CarService 与 car-lib 分别承担什么角色？**

CarService 在 AAOS 13 中作为高权限 `com.android.car` 进程运行，由 system_server 中的 CarServiceHelperService 协调启动，不是 system_server 进程内部的一组服务。它通过 CarServiceImpl/ICarImpl 向 Car API 暴露多个领域服务。car-lib 则是应用侧 `android.car.*` 管理器、Java API 与 Binder/AIDL 契约所在层。

1. **客户端入口**：应用从 car-lib 管理器取得 API，并经 Binder 调用 CarService。
2. **服务实现**：CarService 服务代码位于 `packages/services/Car/service/`，按音频、电源、occupant zone、UX 限制、投影等职责组织。
3. **车辆接口**：VHAL 接口定义位于 `hardware/interfaces/automotive/`，具体硬件适配由车辆集成方实现。
4. **调试入口**：`dumpsys car_service --services <CLASS_NAME>` 查看指定服务状态。`adb shell cmd car_service` 调用 CarService shell 命令。

修改前先从 car-lib 确认公开契约，再追到对应 service 实现。如果涉及车辆属性，再向下核对 VHAL 属性与车辆端实现。

**Q3: 车机桌面需要随驾驶状态和多显示变化，为什么 Launcher 不只是普通桌面应用？**

车机 Launcher 仍是一个 Activity/应用组件，但通常承担系统 Home 入口职责，并要与车辆用户、目标 Display、驾驶 UX 限制和电源状态协同。不能因为它是 Launcher 就把它等同于 WMS 或 SystemUI。

1. **启动与显示**：Launcher 是系统为 Home 任务选择的入口，AAOS 可在不同用户或 Display 上运行相应的 Home 内容。
2. **驾驶约束**：行驶状态可能改变应用可用交互或前后台行为，应用是否能继续显示由 UX 限制、组件声明及系统策略共同决定。
3. **电源约束**：屏幕电源由车辆电源管理与显示策略协调，Launcher 不应自行假定显示永远开启。
4. **改动边界**：只改 Launcher 页面通常属于应用/系统应用范围。改系统栏、窗口焦点或跨应用策略时才需要进一步追到 SystemUI、WMS 或 CarService。

**Q4: [learning] 车载功能用模板应用还是原生 Activity 实现，怎样按功能和合规成本选路线？**

取舍核心是合规成本对界面自由度：模板应用以呈现受约束换取跨形态兼容与安全合规的默认满足，原生 Activity 反之。

1. **Car App Library 模板应用**：androidx.car.app 提供列表、网格、消息、搜索、导航等模板，应用提交数据与动作，由兼容宿主渲染。宿主据模板契约控制呈现与部分交互规则，跨 Android Auto/AAOS 的复用取决于宿主支持和发行配置，代价是界面定制自由度较低。
2. **AAOS 原生应用**：普通 Activity 自行控制布局与导航，自由度较高，也可复用部分手机代码。应用与系统仍需共同满足驾驶 UX、显示/用户和车机质量要求。

选型时看功能类别是否受 Car App Library 支持，以及需要的界面是否能用模板表达。原生 Activity 若需在行驶时显示，必须满足目标系统对 distractionOptimized 元数据和 UX 限制的要求。单靠开发者声明不保证绕过系统限制。一个应用可同时提供原生入口与模板服务，但两者仍分别遵循宿主与系统契约。

**Q5: Car App 应用已安装却没出现在宿主列表，CarAppService 清单还缺哪些声明？**

CarAppService 是宿主连接应用的入口，它是一个 Service，必须在清单里同时声明动作与类别（官方文档口径）：

```xml
<service android:name=".MyCarAppService" android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService"/>
        <category android:name="androidx.car.app.category.POI"/>
    </intent-filter>
</service>
```

Service 需要可被宿主发现并分类。AAOS 还要声明 CarAppActivity 和 Automotive 能力描述文件，Android Auto 与 AAOS 使用不同的 application 元数据键。

1. **Service 组件**：`android:name` 指向 CarAppService 子类。`android:exported="true"` 允许车机宿主跨进程绑定。targetSdk 31 起有 intent-filter 的组件必须显式设置 `android:exported`，省略会导致合并/构建失败。较旧目标版本的默认值可能按是否有过滤器推导，不应依赖该默认值。
2. **发现动作**：androidx.car.app.CarAppService 是宿主用于发现服务的 action。省略或拼错后，宿主不能用 Car App Library 服务契约找到它。
3. **应用类别**：本例的 androidx.car.app.category.POI 表示兴趣点应用，必须与应用真实支持的 Car App 类别一致。宿主按类别筛选可展示的应用。能提供多个受支持类别时，可在同一过滤器中声明多个 category。
4. **最低 Car API**：在 application 中用 androidx.car.app.minCarApiLevel 声明最低宿主 Car API。基线应用可显式设 `android:value="1"`。使用更高等级能力时声明其最低等级，并对更低宿主准备降级路径。不要省略后依赖未核实的宿主默认值。
5. **AAOS 入口 Activity**：AAOS 需要唯一的 androidx.car.app.activity.CarAppActivity，设 `android:exported="true"`、`android:launchMode="singleTask"`、`android:theme="@android:style/Theme.DeviceDefault.NoActionBar"`，并配置 MAIN/LAUNCHER 过滤器。缺少导出入口或 Launcher 过滤器时，外部 Launcher 无法按预期启动它。singleTask 让 Launcher 能回到已有入口实例。必须设置 meta-data `android:name="distractionOptimized"`、`android:value="true"` 才能按该入口声明驾驶优化。其他 Activity 不应随意照抄该标记。
6. **模板能力文件**：`res/xml/automotive_app_desc.xml` 中的 `<uses name="template"/>` 声明应用使用模板宿主。AAOS 用 `android:name="com.android.automotive"`、`android:resource="@xml/automotive_app_desc"` 引用该文件。Android Auto 使用 `android:name="com.google.android.gms.car.application"` 元数据，引用键不同。两边的引用不可互相替代，AAOS 包不应仅复制 Android Auto 的 GMS 元数据。
7. **平台特性**：模板宿主目标的 AAOS 模块应声明 `android.hardware.type.automotive` 与 `android.software.car.templates_host` 两个 uses-feature。`android:required="true"` 表示缺少对应能力的设备不能安装该模块。uses-feature 省略 required 时默认 true。若应用有非模板宿主的回退实现，可显式设 false 允许安装，但运行时必须检查模板宿主是否存在，不能在无宿主设备上调用模板 API。
8. **其它准入条件**：清单正确仍不保证宿主列出应用。还要确认目标宿主支持所声明类别/API，并检查 CarAppService.createHostValidator() 是否接受该宿主。

**Q6: 同一 Android 版本的车辆支持能力不同，Car App API level 为什么不能用 SDK 版本替代？**

Car App Library 有自己的 API 版本体系（Car App API level），与 Android API level 独立：应用声明最低 Car App API，运行时查询宿主支持的等级，并对高版本模型/方法使用 @RequiresCarApi 标注。该标注提供兼容性元数据，IDE/lint 可据此检查调用。它不会自动在运行时拦截调用，应用仍须主动比较宿主 API 并选择回退实现。

宿主能力由车厂实现和更新节奏决定，而不只由 Android 版本决定。同一 Android 版本的车可能运行不同 Car App API level 的宿主。只按 Android API 判断功能支持会误用宿主没有实现的模板/字段，需结合 API 等级检查和明确的降级路径。

**Q7: [learning] 模板应用传入 Action 后按钮没显示，宿主渲染与 Template 契约怎样约束结果？**

界面由宿主（车机 Launcher 或模板宿主应用）渲染，应用只提供模板类型与其中的数据、动作。这一层间接带来两条约束：

1. **动作类型受模板约束**：标准动作有固定集合（返回、应用图标等），应用可自定义动作类型（Action.TYPE_CUSTOM 一类的自定义类型），但某个动作能否出现在某模板里、能否带标题与图标，由模板定义（官方文档口径）。
2. **能力受 Car App API level 约束**：Action.COMPOSE_MESSAGE 从 Car App API 7 起支持。更低等级宿主无法按该标准动作呈现，应用应提供受支持的替代交互。

工程含义：调试模板应用时“界面不对”往往是数据或动作与模板契约不匹配，而不是渲染 bug——所以先核对宿主支持的 car API level 与该模板允许的动作集合。

**Q8: 副驾屏上的应用拿不到座位信息，occupant zone 如何关联座位、显示和用户？**

Occupant zone 是车辆座位/乘员区域的逻辑对象，可把座位、主显示及附加显示、输入设备和登录用户关联起来。应用通过 CarOccupantZoneManager 查询系统配置。未配置时的兜底行为与版本和车辆资源配置相关，不能将单屏上的默认驾驶员区推断成多屏映射已配置。

1. **查询结果**：AAOS 13 的 OccupantZoneInfo 提供 zoneId、occupantType、座位及显示类型等信息，具体字段以目标 SDK 为准。
2. **系统配置**：CarOccupantZoneService 从系统资源/overlay 配置读取占用区与设备映射。AAOS 13 的 config_occupant_zones 未配置时会建立默认驾驶员区作为单区兜底。
3. **多屏影响**：兜底只解决默认驾驶员区，不会自动为后排或副驾新增屏幕建立完整座位映射。扩展硬件后必须检查 zone、Display 与输入设备配置。
4. **Android 14 边界**：从 Upside Down Cake 起，系统支持不配置驾驶员 occupant zone，且当前用户不再必然是默认驾驶员。`config_occupant_display_mapping` 中每个有关联的 occupant zone/display type 都必须定义至少一种输入类型。没有输入设备的显示应显式使用 `INPUT_TYPE_NONE`。不能把 Android 13 的兜底推断外推到 Android 14。

**Q9: 后排操作影响了驾驶员的数据，车机多用户下哪些状态必须隔离？**

多用户 AAOS 中，Android 当前前台用户、headless system user、以及分配给 occupant zone 的可见用户是不同概念。座位区不是 Android User 本身，只有系统将用户登录/分配到该区后才形成映射。

1. **用户类型**：CarUserManager 管理 Android 用户和车辆用户切换。系统用户可在 headless system user 模式下无显示运行，不能把它当作驾驶员用户。
2. **区域映射**：每个配置好的 occupant zone 可关联登录用户与一组 Display。zone 表示一组显示的抽象，不保证一人一个 zone。是否为每个座位创建独立用户取决于系统配置和并发多用户能力。
3. **数据隔离**：应用按 Android 用户存储的数据由平台用户隔离规则决定。不要自行把不同用户的私有数据放进共享文件或静态进程状态。
4. **驾驶身份**：当前登录/前台用户不必然等于驾驶员身份。判断驾驶相关行为应结合车辆/占用区信息，而不是仅检查当前 UserHandle。

**Q10: 调用 DisplayManager 关闭车机屏幕后状态不一致，屏幕实际由什么电源策略控制？**

AAOS 屏幕电源由车辆电源状态和 power policy 管理，普通 DisplayManager 的屏幕开关调用不能代表车辆电源策略。新版本架构中 CarPowerManagementService 协调电源状态并与 VHAL 通信，CarPowerPolicyDaemon 管理策略并通知订阅方。AAOS 13 的具体服务分工应按该版本源码核对。

1. **状态路径**：VHAL 参与 Wait for VHAL、On、Shutdown Prepare、Wait for VHAL Finish 等电源状态转换。
2. **策略路径**：策略列出 Display、Audio、Input、Voice Interaction 等组件的预期电源状态。当前架构由 CarPowerPolicyDaemon 维护策略，CarPowerManagementService 协调状态机。VHAL 或系统组件可在规定状态请求应用策略。
3. **屏幕行为**：On 状态下 Display 仍由 power policy 控制，不应依赖手机形态的普通 DisplayManager 开关或屏幕广播来决定车机显示是否上电。
4. **应用回调**：`CarPowerManager` 可向有权限的系统客户端提供电源状态/策略通知。普通应用可用哪些接口取决于目标版本和权限，不能假定任意应用都可订阅策略。
5. **关机准备**：应用收到允许的预关机/关机准备通知时，应在时间限制内完成保存状态和释放资源。具体状态常量（如 `STATE_PRE_SHUTDOWN_PREPARE`）及 `isCompletionAllowed` 语义要按 AAOS 13 car-lib 核对。

**Q11: 车机夜间模式跟车辆灯光变化不同步，日夜状态由谁提供给应用？**

CarNightService 位于 `packages/services/Car/service/src/com/android/car/CarNightService.java`，在 AAOS 13 中管理车机日夜模式。切换输入可来自车辆灯光信号或系统策略，不应默认等同于用户设置或当地时间。

1. **读取方式**：应用监听 Configuration 变化并读取 `Configuration.uiMode` 的 night 位，使用系统当前选择的日夜资源。
2. **按显示差异**：车辆可按显示配置不同日夜状态，应用必须从当前 Activity/Display 的 Configuration 读取，不要用进程全局缓存覆盖显示差异。
3. **不要重复计算**：应用不要自行根据时钟推断车机昼夜，否则会与车辆信号和系统策略脱节。

**Q12: 应用收不到旋钮或自定义按键事件，车机输入事件经什么系统通道分发？**

车机专用旋钮和自定义输入不是普通触屏 MotionEvent。系统通过 CarInputManager 的权限控制 API 选择输入类型并回调相应事件对象。触屏仍走常规 Android 输入分发。

1. **数据与服务**：AAOS 13 的 RotaryEvent、CustomInputEvent 等结构表达不同车机输入。CarInputService 和输入采集路径把系统认可的事件交给有权限的客户端。
2. **输入仲裁**：AOSP 的 `packages/apps/Car/RotaryController` 展示系统级焦点与旋钮导航如何配合。同一旋钮可按焦点和系统策略驱动焦点移动、列表滚动或其他动作，应用不能假定自行独占旋钮。
3. **权限与注册**：应用需按目标版本所需的 Car 权限和受支持输入类型注册。未获权限或系统未将该类型路由给应用时，不会收到对应回调。
4. **交互设计**：旋钮适合有焦点、有离散边界的导航与选择，不应伪装成连续触摸手势。自定义输入需由系统配置/授权后才能使用。

**Q13: 导航地图无法显示到仪表屏，cluster 服务与普通 Activity 有何区别？**

AAOS 13 的仪表集群服务提供独立于普通 Home/Activity 启动的导航/主界面注册与内容通道。中控屏 Activity 不会因为目标显示是仪表就自动成为合法的集群内容。

1. **服务职责**：ClusterHomeService 管理仪表 Home 入口，ClusterNavigationService 提供导航内容通道，InstrumentClusterService 负责集群能力注册与访问。
2. **内容提交**：导航应用按集群接口提供受限内容或渲染数据，由车厂仪表端按硬件能力显示。不能假定可在仪表 Display 任意启动 Activity。
3. **边界与代价**：集群输出需遵守驾驶安全、权限和仪表渲染契约。它与中控 Activity 是不同接口路径，需分别维护并验证。

**Q14: [learning] Android Auto 投影启动后原生应用不可见或失焦，二者如何共存？**

AAOS 13 中 CarProjectionService/CarProjectionManager 管理投影相关状态和客户端通知。投影如何呈现、原生任务是否可见或有焦点，由具体 SystemUI、WMS、显示与产品策略共同决定，不能概括成固定的“两层窗口”。

1. **可见性与焦点**：投影进入/退出可能改变原生任务可见性或焦点，应用应正确响应生命周期与窗口焦点变化。
2. **投影 UI 契约**：投影界面由投影栈及系统集成控制，原生应用不能任意修改投影 UI。
3. **并行策略**：同时存在时仍须遵守音频焦点、驾驶 UX 限制和显示策略。音频焦点影响参见 [AAOS 车机音频](../05-audio/03-aaos-audio.md)。

**Q15: [learning] 车机调试时 UX 限制被放开，如何用服务状态与特性开关确认测试环境？**

三类入口，用途不同：

1. **服务状态**：`dumpsys car_service --services <CLASS_NAME>` 查看某个 CarService 的内部状态，`adb shell cmd car_service` 调用服务自带的 shell 命令（car-lib README 与本地源码核对）。
2. **调试模式开关**：本地源码里有专用的调试限制控制器应用（`packages/apps/Car/DebuggingRestrictionController`，核对），用于在调试期间调整限制类行为——这类开关必须确认量产关闭，否则会形成安全缺口。
3. **特性开关**：CarService 有特性控制器与实验特性控制器（CarFeatureController、CarExperimentalFeatureServiceController，本地核对），负责按车型/配置裁剪服务与能力。它们与平台层的特性开关体系是两套东西，车机侧由 CarService 自己管。

排查顺序是“先用 dumpsys 确认服务状态，再用开关排除干扰项，最后才改代码”——顺序反了会在一个被人为放开限制的环境里调试出“看起来正常”的结果。

**Q16: 地图 Surface 的生命周期怎么管理？帧预算与可见区域有哪些约束？**

导航、POI 和天气模板如需自绘 Surface，必须按 Car App API 权限契约注册 SurfaceCallback，并把 Surface、可见区域和帧调度视为宿主提供的临时资源。

1. **模板权限**：NavigationTemplate 需声明 `androidx.car.app.NAVIGATION_TEMPLATES`。MapWithContentTemplate 可按类别声明 NAVIGATION_TEMPLATES 或 MAP_TEMPLATES。旧 MapTemplate、PlaceListNavigationTemplate 和 RoutePreviewNavigationTemplate 使用 NAVIGATION_TEMPLATES。缺少权限时宿主不会授予模板能力。
2. **Surface 权限与获取**：调用 `setSurfaceCallback()` 前声明 `androidx.car.app.ACCESS_SURFACE`，缺少时调用会抛 `SecurityException`。通过 `AppManager.setSurfaceCallback()` 接收 `SurfaceContainer`。每次 `onSurfaceAvailable()` 都读取本次提供的 Surface、宽、高和 DPI。尺寸或 DPI 变化时可能再次回调。
3. **释放 Surface**：Surface 生命周期结束或替换时停止提交帧并调用 release() 释放应用收到的 Surface。同步释放应用自己创建的 VirtualDisplay、Presentation、EGL surface、图形缓冲和地图引擎关联，不能只清理 Java Surface 引用。
4. **重建来源**：投影断开、host 重建、昼夜模式或 Configuration 变化都可能重新触发生命周期回调。渲染器需可销毁后按新尺寸重建。
5. **可见区域**：`onVisibleAreaChanged()` 表示宿主保证当前不会被其他界面遮挡的可见区域，必须持续显示的关键内容应放在该范围。`onStableAreaChanged()` 表示按当前模板遮挡规则计算、始终可见的最小稳定区域，适合位置不应随控件显隐频繁移动的内容。不要写死安全边距，因为超宽屏、远端屏和旋转布局会改变可用区。
6. **帧预算**：按设备报告和 trace 决定目标，不固定假设 60 Hz。路线计算、瓦片解码和图标生成不要占渲染线程。拖动时取消离开视野的请求、合并重复瓦片并限制解码并发。
7. **退化与度量**：温度或 GPU 余量不足时优先减少非导航覆盖物、阴影和预取，同时保持路线、下一转向和安全提示可读。分别记录平均帧率、jank、帧呈现时间和功耗。

**Q17: CarSystemUI 或 TaskPanel 重启后界面空白、焦点或 rotary 失效，应检查哪些恢复与输入边界？**

这类问题通常来自服务重启后的状态恢复或焦点链路，不是单一的旋钮驱动故障。应分别检查 TaskPanel 创建、用户解锁、Keyguard、HUN 与列表焦点。

1. **面板空白**：SystemUI 崩溃重启后不一定再次收到 user unlock 事件，因此 TaskPanel/rootTask 创建时要检查当前用户是否已解锁，并在满足条件时重置面板。AOSP 修复线索包括 Bug 394411179。同类还包括 day/night 切换崩溃和车辆未连接时 Configuration 变化导致的空指针。
2. **ScalableUI 焦点**：新窗口管理下 TaskPanel 需独立焦点处理。AOSP `scalable_ui_task_focus` flag（Bug 422571603）用于灰度验证。缺失时嵌入任务可能无法接收按键或 rotary 焦点。
3. **Keyguard 与 rotary**：Keyguard 放开 rotary focus 后，若目标 View 仍处于 paused 状态，需先恢复 View 状态再请求焦点。Bug 263440452 是对应修复线索。
4. **排查顺序**：先确认目标窗口/TaskPanel 是否已创建，再确认用户解锁和窗口焦点，随后检查 Keyguard、HUN 与列表的焦点迁移，最后检查 CarInputManager/RotaryController 是否把输入路由到当前焦点。

**Q18: CarSystemUI 将依赖注入接到 platform SystemUI 时，CarSysUIComponent 与 OEM Module 的扩展边界是什么？**

AAOS CarSystemUI 在其实现中扩展 platform SystemUI 的依赖图。新增绑定应沿既有子组件和 Module 接入，避免创建互不兼容的并行组件树。

1. **组件关系**：AAOS 13 的 CarSystemUI 使用 `CarSysUIComponent extends SysUIComponent` 将车辆专属绑定接到 platform SystemUI 组件层。
2. **OEM 扩展**：OEM 按项目扩展点添加 Binder 或 Dagger Module，并遵守现有依赖图生命周期。不要假定任何任意自建 component 都能替代宿主依赖图。
3. **排查绑定**：遇到 MissingBinding 或单例作用域错误，沿构造注入关系检查 binding 所属 Module、子组件安装位置和 scope。
4. **图形化工具**：仓库自带 daggervis 脚本可导出组件图，适合检查绑定关系。RRO 只能替换允许覆盖的资源，不能改变 Dagger 绑定。

**Q19: [learning] 车机多用户、多显示时页面显示在错误屏幕，Activity 归属由什么决定？**

Activity 的运行归属同时涉及 Android 用户与 Display：Activity 在所属用户的应用进程和状态空间中运行，并被系统放置到某个显示区域。车机的 occupant zone（座位区）可以把乘员用户与显示器关联，因此同一时刻可能存在多个可见或活动的用户上下文。

1. **用户维度**：确认发起启动的用户、目标用户及目标 Activity 是否允许在该用户下运行。系统用户、当前驾驶员和乘员用户并不是同一个 UID/用户空间。
2. **显示维度**：确认启动请求的目标 Display、Activity 任务当前所在 Display，以及该显示是否映射到预期 occupant zone。不要默认 Activity 一定在默认显示上。
3. **资源与尺寸**：从当前 Activity 的用户和显示上下文获取资源、密度与窗口尺寸。不能用默认显示结果推断其他显示布局。

车机的 occupant zone 策略由 AAOS 配置与平台服务决定。同一时刻有多个可见用户，不代表 Android 全局只有一个当前用户的约束消失。
