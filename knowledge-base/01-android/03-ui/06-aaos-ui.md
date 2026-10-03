# AAOS 车机 UI 架构与 CarService

> 学习资料（文章模式沉淀）。主线：车机 UI 的分层与定制点、CarService 与 car-lib 的分工、车机 Launcher 与投影共存、Car App Library 模板应用与 AAOS 原生 Activity 两条路线、CarAppService 注册契约与 Car App API level、车机 SystemUI 的独立实现、应用焦点、occupant zone 的座位-显示-用户映射、多用户模型、CarPowerManager 与 power policy 对屏幕的接管、日夜模式、旋钮与自定义输入、仪表通道、调试与特性开关。CarService 实现与 car-lib API 按本地 AAOS13 源码（Android 13）核对（`packages/services/Car/service/src/com/android/car/`、`car-lib/src/android/car/`、`packages/apps/Car/`），Car App Library 与 Power/Car Occupant Zone 文档结论按官方文档口径（2026-09 检索）。交互安全与驾驶分心见 [07-driving-safety.md](07-driving-safety.md)，配置资源适配见 [03-resources.md](03-resources.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 收到“车机页面改不动”的需求后，如何判断应改应用、SystemUI、CarService 还是车辆层？**

按从上到下的可改动性分四层：

1. **应用内容层**：车机应用自己的 Activity 或模板应用，负责呈现具体功能界面。
2. **系统窗口层**：车机 Launcher、SystemUI（系统栏、状态栏、通知、用户切换、音量、车控对话框等），本地实现位于 `packages/apps/Car/SystemUI/src/com/android/systemui/car/`，模块目录包括 systembar、statusbar、notification、userswitcher、volume、systemdialogs、toast、keyguard、cluster、hvac、voicerecognition、window（本地 AAOS13 源码核对）。
3. **系统服务层**：CarService 下的各服务，负责焦点、驾驶状态、UX 限制、投影、电源策略、占用区、输入等，代码在 `packages/services/Car/service/src/com/android/car/`（本地核对）。
4. **车辆与显示层**：VHAL、显示与电源策略。

改车机界面的落点判断只有一句话：**要改的是"这台机器的通用外观"还是"某个应用的界面"**。前者走系统窗口层加资源定制（注意定制点越少越稳定），后者只改应用，不要为了统一外观去改系统窗口层。

**Q2: 应用需要读取车辆状态时，CarService 与 car-lib 分别承担什么角色？**

CarService 是一组运行在 system_server 里的系统服务集合，每个服务各自负责一个领域（音频、电源、occupant zone、UX 限制、投影等），统一入口是 CarServiceImpl 与 ICarImpl（本地 AAOS13 源码核对）。car-lib 则是对外的 API 层：应用通过 android.car.* 下的管理器类拿到服务句柄，car-lib 里既有 Java API 也有跨进程 AIDL（如 CarOccupantZoneManager.aidl、ICar.aidl）。

car-lib 的 README 把这条边界讲得很清楚：API 定义在 car-lib，服务实现放在 service 目录，VHAL 接口在 `hardware/interfaces/automotive/`，并给出两条调试入口——`dumpsys car_service --services <CLASS_NAME>` 看服务内部状态、`adb shell cmd car_service` 走服务自带的 shell 命令（car-lib README 与本地源码核对）。所以改一个车机功能的标准路径是"先在 car-lib 确认 API，再改 service 实现"。

**Q3: 车机桌面需要随驾驶状态和多显示变化，为什么 Launcher 不只是普通桌面应用？**

差别不在视觉，而在**它运行在什么约束下**：车机 Launcher 是当前座位区的前台界面，它必须能在车辆行驶时保持自身可用、能在驾驶状态变化时切换自身内容、还要面对"某个应用突然请求全屏"这种情况下的分心限制（行驶时还要按 UX 限制切换到合规的交互形态）。手机 Launcher 只面对单用户、单显示、无驾驶状态。

因此车机 Launcher 不是一个"桌面应用"，而是系统窗口层的一部分：它与占用区绑定显示、与 UX 限制联动（行驶中被拦截的形态见后文交互安全部分）、与电源策略联动（屏幕开关由策略决定）。评估车机 Launcher 的定制改动时，要按"系统窗口改动"而不是"应用改动"评估影响面。

**Q4: 车载功能用模板应用还是原生 Activity 实现，怎样按功能和合规成本选路线？**

1. **Car App Library 模板应用**：androidx.car.app 提供的一组模板（列表、网格、分栏、消息、搜索、登录、标签、导航等），应用只提供数据与动作，由车机宿主渲染界面。好处是天生符合车机 HMI 规范与分心限制、可同时跑在 Android Auto 与 AAOS 上；代价是界面自由度低，只能用模板给定的组件。
2. **AAOS 原生应用**：普通 Activity，自己控制界面与导航。好处是自由度高、可复用手机端代码；代价是要自己处理分心限制、多显示与多用户、以及车机 HMI 规范。

选型规则：能模板化的功能优先模板化（导航、POI、天气、通讯这类），需要自定义复杂界面的功能走原生路线但必须显式声明自己是"分心优化"应用（distractionOptimized），否则行驶中会被 UX 限制拦截。两者的作用类型不是互斥的，一个应用可以同时提供一个原生界面和一个模板入口。

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

只声明动作不声明类别，宿主无法判断该把它放进哪类应用列表；exported 不为 true 则外部宿主根本绑不上。在 AAOS 上还需要额外的入口组件：清单里放一个 androidx.car.app.activity.CarAppActivity 作为入口（`launchMode="singleTask"`），并声明 distractionOptimized 的 meta-data，以及在 `res/xml/automotive_app_desc.xml` 里声明 `<automotiveApp><uses name="template"/></automotiveApp>`（官方文档口径）。Android Auto 侧还需要 com.google.android.gms.car.application 指向同一描述文件，AAOS 侧则不需要那个 GMS 引用。

**Q6: 同一 Android 版本的车辆支持能力不同，Car App API level 为什么不能用 SDK 版本替代？**

Car App Library 有自己的 API 版本体系（car API level），与 Android API level 独立：宿主声明它支持的 car API level，应用用 androidx.car.app.minCarAppApiLevel 声明自己的最低要求，运行时可查询宿主支持的最高等级，较新的 API 用 @RequiresCarApi 标注（官方文档口径）。

存在的原因是模板宿主的能力由车厂决定而不是由系统版本决定：同一台 Android 版本的车，宿主可能只实现到 car API 4。只按 Android 版本判断"这个特性能不能用"会在老宿主上直接崩，正确写法是用 @RequiresCarApi 让编译器把不兼容路径标出来，并对不支持的场景准备降级实现。

**Q7: 模板应用传入 Action 后按钮没显示，宿主渲染与 Template 契约怎样约束结果？**

界面由宿主（车机 Launcher 或模板宿主应用）渲染，应用只提供模板类型与其中的数据、动作。这一层间接带来两条约束：

1. **动作类型受模板约束**：标准动作有固定集合（返回、应用图标等），应用可自定义动作类型（Action.TYPE_CUSTOM 一类的自定义类型），但某个动作能否出现在某模板里、能否带标题与图标，由模板定义（官方文档口径）。
2. **能力受 car API level 约束**：带 car API 7 标注的新动作（如部分地图与消息类动作）在老宿主上不可用。

工程含义：调试模板应用时"界面不对"往往是数据或动作与模板契约不匹配，而不是渲染 bug——所以先核对宿主支持的 car API level 与该模板允许的动作集合。

**Q8: 副驾屏上的应用拿不到座位信息，occupant zone 如何关联座位、显示和用户？**

CarOccupantZoneManager 提供按座位区查询信息的能力，返回的 OccupantZoneInfo 里包含 zoneId、occupantType（驾驶员、前排乘员、后排乘员）、座位与显示类型（本地 AAOS13 源码 `car-lib/src/android/car/CarOccupantZoneManager.java` 与官方文档核对）。服务实现 CarOccupantZoneService 从 config_occupant_zones 这个 RRO 配置读取声明；配置为空时会**自动为驾驶员创建一个占用区**作为唯一兜底（本地源码核对）。

兜底意味着"只配置一个区"和"没配置"在单屏车机上表现一致，但一旦加装第二块屏却忘了配置，行为就是"新屏幕没有对应占用区"，应用侧表现为该屏上的界面拿不到座位上下文。版本差异要知道：官方文档明确从 Android 14（UPSIDE_DOWN_CAKE_0）起允许没有驾驶员区的系统、且当前用户不再是默认驾驶员，并且要求每个占用区与显示类型至少声明一种输入类型。

**Q9: 后排操作影响了驾驶员的数据，车机多用户下哪些状态必须隔离？**

车机上存在三类用户：当前用户（通常是驾驶员）、后台或无显示的用户（headless）、以及各乘员区的用户。CarUserManager 提供这套模型的操作能力（本地源码核对），系统里还有 `packages/services/Car/service/src/com/android/car/user/` 下的用户管理服务。车机上还存在一个不属于任何座位区的系统用户，承担与驾驶员无关的工作。

写代码时三条纪律：不要假设"只有一个前台用户"（UserHandle 相关的全局状态可能是 headless 用户的）；跨用户数据不共享，持久化要按用户隔离；"当前用户是谁"与"当前驾驶者是谁"是两个概念——夜间有人坐进后排、副驾在后排屏幕上操作，都可能让当前用户与驾驶者不同。

**Q10: 调用 DisplayManager 关闭车机屏幕后状态不一致，屏幕实际由什么电源策略控制？**

因为车机屏幕的开关由车辆电源策略决定，而不是 Android 的显示开关。官方文档描述的结构是：CarPowerManagementService 协调状态机并把电源策略下发给 CarPowerPolicyDaemon 与 VMCU，策略里声明哪些硬件与软件组件（显示、音频、语音交互）应开或关；AAOS 有一个状态机（等待 VHAL、上电、关机准备、等待 VHAL 完成等状态），并在允许的状态下应用新策略（官方文档口径）。

对 UI 的直接影响有两条：屏幕由策略关闭时，应用不会收到普通意义上的"屏幕关闭"广播来判断该不该停渲染，而应监听电源状态/策略变化；应用在关机准备阶段还能收到提前通知，可以用来做提前释放与落盘（本地源码可见 CarPowerManager 的 STATE_PRE_SHUTDOWN_PREPARE 一类状态与 isCompletionAllowed 判定，核对）。

**Q11: 车机夜间模式跟车辆灯光变化不同步，日夜状态由谁提供给应用？**

车机有独立的服务：`packages/services/Car/service/src/com/android/car/CarNightService.java`（本地 AAOS13 源码核对），它管理车机自己的日夜状态。对比手机有两处差异：一是触发源不同——车机可由车辆信号或电源状态驱动，而不只是用户设置与系统时间；二是取值粒度不同——车机可能按显示（不同座位区屏幕）处于不同日夜状态。

应用侧的正确做法是监听配置变化与 Configuration.uiMode 判断，而不是自己算时间（日夜状态可能由车辆信号驱动，应用应读取系统提供的 Configuration），因为重复实现昼夜逻辑会与车辆信号脱节。

**Q12: 应用收不到旋钮或自定义按键事件，车机输入事件经什么系统通道分发？**

车机输入通过 CarInputManager 注册回调获取，输入事件分多种类型，其中既有触摸也有自定义输入事件；旋钮事件与自定义事件各有独立的数据结构（RotaryEvent、CustomInputEvent，本地 AAOS13 源码核对）。系统侧由 CarInputService 与输入采集控制器把 VHAL 事件转成应用可注册的回调。

AOSP 自带一个旋钮控制器应用（`packages/apps/Car/RotaryController`，本地源码核对），它的存在说明旋钮事件需要系统级仲裁——同一个旋钮在不同界面可能被映射成"滚动列表""切换标签""调节音量"，仲裁规则集中实现比每个应用各写一套更可靠。

应用侧纪律：旋钮只能做离散、有明确边界的操作（选中、翻页、调音量），不能替代连续手势；自定义输入事件要先向系统申请再使用，没有申请时事件不会送达。

**Q13: 导航地图无法显示到仪表屏，cluster 服务与普通 Activity 有何区别？**

仪表屏与中控屏是不同显示、不同座位区、有更严格的驾驶限制，Android 为此提供独立的系统服务：ClusterHomeService（仪表主屏）、ClusterNavigationService（导航通道）、InstrumentClusterService（顶层注册），都在 CarService 下（本地 AAOS13 源码核对）。

导航应用要往仪表上画图，走的是这套服务通道而不是"在仪表上起一个 Activity"。这个设计的收益是仪表内容与驾驶限制、用户权限、以及仪表自身的渲染能力统一收口；代价是导航应用要为仪表实现一套受限的内容接口，与中控屏的渲染完全是两套代码路径。

**Q14: Android Auto 投影启动后原生应用不可见或失焦，二者如何共存？**

投影由 CarProjectionService 管理，应用侧 API 是 CarProjectionManager，投影状态用独立的数据类型表示（本地 AAOS13 源码核对）。共存关系是"投影占一层，原生应用占另一层"，而不是互相替换。

投影接入会带来三项工程影响：

1. 投影态下原生应用可能不可见或不可交互，应用需要正确响应可见性与焦点变化。
2. 投影 UI 契约由投影方定义（多为模板化），原生应用不能自行修改。
3. 投影与原生应用同时存在时，音视频焦点与分心限制都要生效；音频焦点机制见 [AAOS 车机音频](../09-audio/03-aaos-audio.md)。

**Q15: 车机调试时 UX 限制被放开，如何用服务状态与特性开关确认测试环境？**

三类入口，用途不同：

1. **服务状态**：`dumpsys car_service --services <CLASS_NAME>` 查看某个 CarService 的内部状态，`adb shell cmd car_service` 调用服务自带的 shell 命令（car-lib README 与本地源码核对）。
2. **调试模式开关**：本地源码里有专用的调试限制控制器应用（`packages/apps/Car/DebuggingRestrictionController`，核对），用于在调试期间调整限制类行为——这类开关必须确认量产关闭，否则会形成安全缺口。
3. **特性开关**：CarService 有特性控制器与实验特性控制器（CarFeatureController、CarExperimentalFeatureServiceController，本地核对），负责按车型/配置裁剪服务与能力；它们与平台层的特性开关体系是两套东西，车机侧由 CarService 自己管。

排查顺序是"先用 dumpsys 确认服务状态，再用开关排除干扰项，最后才改代码"——顺序反了会在一个被人为放开限制的环境里调试出"看起来正常"的结果。

**Q16: 地图 Surface 的生命周期怎么管理？帧预算与可见区域有哪些约束？**

导航、POI 与天气类模板要声明相应模板权限及 androidx.car.app.ACCESS_SURFACE，经 AppManager.setSurfaceCallback() 接收 SurfaceContainer。生命周期规则：每次 onSurfaceAvailable() 都以回调给出的宽、高、DPI 与 Surface 为准，尺寸或 DPI 变化时即使底层 Surface 尚未销毁也可能再次回调；每个收到的 Surface 实例都必须调用 release()，onSurfaceDestroyed() 到达后停止提交帧，并释放自己创建的 VirtualDisplay、Presentation、EGL 关联表面、图形缓冲区与地图引擎引用。投射断开、host 重建、昼夜模式或配置变化都可能触发重建。

可见区域分两层：onVisibleAreaChanged() 给出当前保证无遮挡的 visible area，当前必须可见的重要内容放这里；onStableAreaChanged() 给出考虑动态遮挡后长期稳定的最小区域，不希望随 host 控件显隐移动的持续内容放这里——固定安全边距会在超宽屏、远端屏和不同旋转输入布局上出错。帧预算按设备报告与 trace 为准、不能固定 60 Hz：路线计算、瓦片解码与图标生成不进渲染线程，快速拖动时取消已离开视野的请求、合并重复瓦片、限制解码并发；热压力或 GPU 余量不足时减少非导航覆盖物、阴影与预取，但路线、下一转向与安全提示保持可读。分别记录平均帧率、jank、帧呈现时间与功耗。

**Q17: SystemUI 崩溃重启后车机面板空白、rotary 旋钮失效——CarSystemUI 有哪些值得对照的官方修复与结构要点？**

1. **崩溃后面板空白**：崩溃重启后不会再有 user unlock 事件驱动恢复——官方修复在 rootTask 创建时主动检查用户已解锁并重置 TaskPanel（Bug 394411179）；同族还有 day/night 切换崩溃、车未连接时配置变更 NPE 等修复；
2. **ScalableUI 焦点**：car-scalable-ui 新窗口管理下 TaskPanel 需要独立焦点逻辑（flag `scalable_ui_task_focus` 灰度），否则内嵌应用无法被按键/rotary 操作（Bug 422571603）；
3. **rotary 失效**：keyguard 上"允许 rotary focus"后视图处于 paused 态，必须 resume 才能重新获焦（Bug 263440452）——rotary 问题按 keyguard → HUN → 列表分层排查；
4. **Dagger 替换结构**：CarSystemUI 把 platform SystemUI 编进同一 APK，用 `CarSysUIComponent extends SysUIComponent` 子组件替换依赖图，OEM 只能追加 Binder/Module、不能私造平行 component；仓库自带 daggervis 脚本可导出组件图——MissingBinding 或注入错单例先看新绑定挂在哪个 module（RRO 只改资源、不改绑定）。
