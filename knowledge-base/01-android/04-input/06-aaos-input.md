# AAOS 车机输入：VHAL 按键链路、CarInputService 与旋钮

> 学习资料（文章模式沉淀）。主线：车机物理按键从 VHAL 到应用的完整上行链路、三个 VHAL 输入属性的语义、InputHalService 的转换与防御、CarInputService 的分发顺序与语音/通话键长按、CustomInputEvent 的 OEM 约定、按键捕获（capture）的栈仲裁、旋钮的两条独立链路、RotaryService 的焦点导航模型与 FocusArea 契约、仪表按键路由、车机输入法、注入调试命令与"旋钮失灵"分层排查。机制按本地 AAOS13 源码（Android 13，`packages/services/Car/`、`packages/apps/Car/RotaryController/`、`hardware/interfaces/automotive/vehicle/`）核对；Rotary 交互模型与 FocusArea 属性按官方文档口径（source.android.com，2026-09 检索）。2026-10-04 复核：重新确认“首次旋钮动作可能只用于进入旋转模式”的交互规则与 OEM 定制输入权限边界。CarService 与 car-lib 总览见 [../03-ui/06-aaos-ui.md](../03-ui/06-aaos-ui.md)；HID/uinput 接入路线与唤醒键见 [04-key-mapping.md](04-key-mapping.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 车机物理按键从按下到应用响应的完整链路是什么？**

标准链路（VHAL 路线）是：物理按键 → MCU/CAN → VHAL 实现写入 `HW_KEY_INPUT` 属性 → CarService 的 `InputHalService` 收到属性变化并转成标准 `KeyEvent` → `CarInputService` 做车载级分发（特殊键处理、显示路由、捕获仲裁）→ 无人拦截时经 `injectInputEvent` 注入系统 → `InputDispatcher` 按目标屏焦点窗口分发 → 应用 `onKeyDown`。每段都有独立观测点，任何一段断链或被截走，表现都是"按键没反应"。

这条链的关键设计是：按键在 CarService 层以**标准 KeyEvent** 的形态注入，后续与真实输入设备的事件走完全相同的系统分发——应用不需要为车机硬键写特殊通道。而按键进入 Android 之前的车载语义（投到哪块屏、长按含义、是否给仪表）全部在 CarService 层裁决，这是它与普通 Android 设备的本质区别。

对应地，旋钮（`HW_ROTARY_INPUT`）与 OEM 自定义键（`HW_CUSTOM_INPUT`）各有专属属性，在 `InputHalService` 里与按键并行转换。排查硬键问题的第一分岔就是确认按键走的是这条 VHAL 链还是 HID/uinput 设备链——前者 `getevent` 无输出，后者有。

**Q2: VHAL 的三个输入属性分别怎么定义？int32Values 各个位置是什么含义？**

三个属性都是 `INT32_VEC`、`ON_CHANGE`、只读，按本地 HAL 接口文件核对（`hardware/interfaces/automotive/vehicle/aidl/.../VehicleProperty.aidl`）：

1. **`HW_KEY_INPUT`（0x0A10）**：`[0]` 动作（`VehicleHwKeyInputAction`：0 按下、1 抬起）、`[1]` 标准 Android 按键码、`[2]` 目标显示（`VehicleDisplay`：MAIN=0、INSTRUMENT_CLUSTER=1）、`[3]` 可选的重复次数（≥1，缺省 1）；
2. **`HW_ROTARY_INPUT`（0x0A20）**：`[0]` 旋钮类型（`RotaryInputType`：SYSTEM_NAVIGATION=0、AUDIO_VOLUME=1）、`[1]` 定位点数（正=顺时针、负=逆时针）、`[2]` 目标显示、`[3..]` 相邻定位点之间的纳秒级时间差（定位点多于 1 时携带）；属性 timestamp 是第一个定位点的时刻。官方文档同时要求：同方向连续定位点必须合并为一个事件上报，不要拆成多条；
3. **`HW_CUSTOM_INPUT`（0x0A30）**：`[0]` 自定义输入码（官方给 `CUSTOM_EVENT_F1..F10`（1001–1010）作便捷命名，OEM 可用任意值）、`[1]` 目标显示、`[2]` 重复计数（0=非重复）。

三个属性都只面向驾驶员的两块屏（MAIN 与 CLUSTER），乘员屏输入不走 VHAL 输入属性（走标准 Android 输入子系统）。对角线 nudge 没有专用键值，官方做法是用水平与垂直事件序列合成。自定义键的码值语义是 OEM 内部约定，必须与 VHAL 实现和消费服务同步维护，改一边不改另一边就是"按了没反应"。

**Q3: `InputHalService` 在转换时做了哪些加工与防御？**

按 AAOS13 源码核对（`packages/services/Car/service/src/com/android/car/hal/InputHalService.java`）：

1. **条件订阅**：维护三个能力标志（key/rotary/custom 各一个），按 VHAL 实际支持的属性决定订阅哪几个；三个都不支持时拒绝注册监听——VHAL 没实现输入属性时 CarService 侧整个按键链路静默不存在，排查第一步就是看这个标志；
2. **按键状态簿**：为每个按键记录最近一次按下的时间戳与已发按下次数，用于合成 `KeyEvent` 的 `repeatCount`；收到没有配对按下的抬起时用事件时间兜底补 `downTime`（防 HAL 实现异常导致崩溃）；
3. **旋钮防御**：定位点数为 0 丢弃；取绝对值前防 `Integer.MIN_VALUE` 溢出；目标显示只接受 MAIN 与 CLUSTER；数组长度必须恰好等于 3+定位点数-1，否则丢弃并打错误日志；
4. **显示类型转换**：`VehicleDisplay` 映射为 CarOccupantZoneManager 的显示类型常量，未知值归为 UNKNOWN。

加工的含义是：VHAL 侧只需要按属性契约上报原始值，重复计数、时间戳语义、合法性全部由这一层统一保证——HAL 实现偷懒（如不带时间差数组）不会崩，但会在这一层被丢弃，现象是"旋钮部分转不动"。

**Q4: `CarInputService` 收到按键后的分发顺序是什么？哪一步可能"吃掉"按键？**

按 AAOS13 源码核对（`CarInputService.java` 的 `onKeyEvent`），顺序固定为五步：

1. **特殊键特判**：`KEYCODE_VOICE_ASSIST` 与 `KEYCODE_CALL` 走车机专用的长按/短按处理，不再往下走；
2. **强制分配显示**：`assignDisplayId` 用 `CarOccupantZoneService.getDisplayIdForDriver()` 把目标显示类型换算成真实 displayId 并**覆盖**事件已有值——即使 VHAL 带了 displayId 也会被重写；
3. **仪表路由**：目标为仪表屏且已注册 cluster 键监听时，交给监听者消费；
4. **捕获仲裁**：`mCaptureController.onKeyEvent` 返回 true 时事件被捕获者吃掉，分发结束；
5. **默认注入**：调用注入实现（默认 `InputManagerHelper.injectInputEvent`）把事件交给系统 InputDispatcher。

会"吃掉"按键的就是第 1、3、4 步：语音/通话键被 CarService 自己消费（应用永远收不到原始事件）；仪表键给 cluster；其余键被任何持有捕获的客户端截走。排查"应用收不到某硬键"时按这五步从上往下查，每步都有对应的 dump 或日志。

**Q5: 语音键和通话键的长按逻辑在哪实现？短按/长按分别做什么？**

在 `CarInputService` 内部实现，不依赖框架的长按机制：自己维护 `KeyPressTimer`，阈值取系统长按超时设置（与 View 长按同源，用户可调）；抬起先于定时器到点判短按，定时器先触发则按长按处理且抬起不再重复响应（按 AAOS13 源码核对）。

两条键的处理逻辑都遵循"投影优先"的优先级链：

1. **语音键**：长按依次尝试投影应用（注册了语音长按回调的）、蓝牙语音识别、默认语音助手；短按先给投影应用，否则拉起默认语音助手；
2. **通话键**：短按在振铃时接听；配置开启且通话中则挂断；都不是则给投影应用，最后拉起拨号盘。长按在振铃/通话场景同样先接听/挂断，否则给投影应用，最后重拨最后一个去电。

工程含义：车载语音键、通话键是整车级资源，AOSP 把语义集中在 CarService 而不是交给前台应用；应用想要定制这两个键的行为，正规途径是 `CarProjectionManager` 的按键事件回调（按事件类型订阅），而不是试图在 `onKeyDown` 里拦截——原始 `VOICE_ASSIST`/`CALL` 事件根本不会被注入系统。

**Q6: `CustomInputEvent` 是什么？为什么 OEM 自定义键"没人接收就直接丢弃"？**

`CustomInputEvent` 是车机为"Android 键值体系之外的按键"预留的通道：OEM 的 HMI 硬键（如自定义的模式切换键）不适合硬塞进标准 KEYCODE（占用系统键码有冲突风险），就通过 `HW_CUSTOM_INPUT` 上报自定义码，CarService 转成 `CustomInputEvent` 分发。官方约定的便捷码值是 F1–F10（1001–1010），实际可用任意整数，语义表由 OEM 自行维护。

"无人接收直接丢弃"是刻意的分发策略：按 AAOS13 源码核对（`CarInputService.onCustomInputEvent`），事件只投给注册了 `INPUT_TYPE_CUSTOM_INPUT_EVENT` 捕获的客户端（OEM 系统服务），没有任何捕获者时打警告日志并丢弃——**不注入系统、不给前台应用兜底**。设计理由是自定义键没有通用语义，与其让事件误导前台应用，不如要求消费方显式声明。

标准做法（官方 `SampleCustomInputService` 示例口径）：OEM 写一个特权系统服务，启动时 `requestInputEventCapture(INPUT_TYPE_CUSTOM_INPUT_EVENT)`，在回调里按码值表执行动作（如发 intent 打开地图）。第三方应用拿不到所需权限（`INJECT_EVENTS` 为签名级/特权级），所以自定义键天然不会泄漏给普通应用——这也是"厂商按键第三方应用收不到"问题的制度性答案。

**Q7: 应用或系统服务怎么"捕获"车机按键？捕获的仲裁规则是什么？**

通过 `CarInputManager.requestInputEventCapture()`：指定目标显示、输入类型数组（`DPAD_KEYS`、`NAVIGATE_KEYS`、`SYSTEM_NAVIGATE_KEYS`、`ROTARY_NAVIGATION`、`CUSTOM_INPUT_EVENT` 或 `INPUT_TYPE_ALL_INPUTS`）与回调，权限要求 `android.car.permission.CAR_MONITOR_INPUT` 或 `android.permission.MONITOR_INPUT`。回调接口有按键、旋钮、自定义事件与捕获状态变化四类通知（按 AAOS13 源码核对，`car-lib/.../CarInputManager.java`）。

仲裁规则是**排他栈**而不是共享订阅（按 AAOS13 源码核对，`InputCaptureClientController.java`）：每块屏维护"全量捕获栈 + 按类型捕获栈"，只有栈顶的客户端收事件；新申请者压栈即拿走授权，被顶掉者经 `onCaptureStateChanged` 收到收窄后的类型集。全量捕获（`TAKE_ALL_EVENTS_FOR_DISPLAY`）活跃期间其他申请得到 DELAYED 暂缓授权；全量捕获本身只授予系统进程（cluster 屏额外放行 cluster Home 包名）。另一个易踩点：同一进程的同一 `CarInputManager` 实例对同屏后注册的回调会覆盖先注册的。

还有一个精确边界：`ROTARY_VOLUME`（音量旋钮类型）**不在合法捕获类型里**——音量旋钮事件不可捕获，永远走"转成音量键注入"的兜底路径。多音区下音量旋钮路由到哪个音区由音频侧的多音区归属决定，与输入捕获无关。排查"某应用打开后按键/旋钮全失效"，第一 suspect 就是它申请了捕获压栈排挤了默认分发。

**Q8: 车机旋钮有哪两条接入链路？事件类型与消费方有什么区别？**

两条链路完全独立，事件类型、source、消费方都不同：

1. **VHAL 链**：旋钮信号经车控写进 `HW_ROTARY_INPUT`，`InputHalService` 转成 `RotaryEvent`（携带旋钮类型、顺逆时针、每个定位点的时间戳），交给 `CarInputService.onRotaryEvent`：先问捕获仲裁，无人捕获时按类型转成标准按键——导航旋钮转 `KEYCODE_NAVIGATE_NEXT/PREVIOUS`、音量旋钮转 `KEYCODE_VOLUME_UP/DOWN`，每个定位点一对按下/抬起——再走按键分发注入。调试命令 `cmd car_service inject-rotary` 注入的就是这条链；
2. **Linux 设备链**：旋钮接成内核 rotary encoder 设备，`RotaryEncoderInputMapper` 产生带 `AXIS_SCROLL` 轴的 `MotionEvent`（source 为 `SOURCE_ROTARY_ENCODER`），走标准焦点窗口分发，由 RotaryController（无障碍服务）或声明了旋钮滚动的可滚动容器消费。用 uinput 造 `REL_WHEEL` 设备可模拟这条链。

判断手上的旋钮走哪条链：`getevent -lt` 有输出是设备链；无输出而 `dumpsys car_service --services InputHalService` 显示 rotary 支持且 VHAL 在上报，是 VHAL 链。两条链的"失灵"排查路径完全不同，混查是常见的时间黑洞。

**Q9: RotaryService 是什么？为什么实现成无障碍服务？**

`RotaryService` 是 AOSP 旋钮方案（`packages/apps/Car/RotaryController`）的核心，继承自 `AccessibilityService`（按 AAOS13 源码核对，manifest 声明无障碍服务绑定与能力配置）。选择无障碍形态的原因有三个：它需要**读取并遍历任意应用的 View 树**来决定焦点去哪（无障碍服务天然有窗口内容与节点操作能力）；它需要**过滤按键**（无障碍的按键过滤能力）来处理 nudge/中心/返回等物理键；它通过 `CarInputManager` 的捕获接口拿旋钮事件（注册于主屏，`CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT` 允许延迟授权）。

CarService 侧的接入方式是配置字符串：`packages/services/Car` 的配置里指定 RotaryService 的组件名，用户切换时 `CarInputService` 自动把它写入系统的无障碍服务启用列表（最多重试 5 次，失败打日志）。不带旋钮的产品用静态 RRO 把该配置覆盖为空串，服务就不会被启用。

理解这个形态对排查至关重要：RotaryService 被禁用/崩溃/被挤出捕获栈，旋钮的焦点导航就整体失效，但 VHAL 链的兜底按键转换仍在——表现为"旋转有反应但焦点乱跳"或"焦点完全不动"，先查无障碍服务状态再查应用布局。

**Q10: 旋钮交互的三种基本操作与直接操作模式分别是什么？**

官方交互模型分三层（source.android.com 旋控器文档口径）：**nudge**（四向微移）负责 FocusArea 之间的粗导航；**旋转**负责 FocusArea 内部可聚焦视图之间的细导航（按遍历顺序）；**中心按钮**等于点击当前聚焦视图；返回键等物理键照常。例外层是**直接操作模式**（DM）：对旋钮/滑块这类控件，进入后旋转不再移动焦点而是直接调节数值。

进入/退出 DM 的两个机制（按 AAOS13 源码核对，`RotaryService.java`）：简单机制——中心按钮按下且聚焦节点声明支持直接操作时进入，按返回键退出；高级机制——应用自己发无障碍焦点事件控制进入/退出（系统窗口只能用简单机制）。应用侧视觉上用 `state_selected` 等自定义状态提示"已进入直接操作"。

另有一个反直觉的设计：触摸屏幕后，第一次旋钮操作只用来"启动旋转模式"（重建焦点），不产生实际动作——从触摸切回旋钮需要一个过渡操作，避免焦点凭空跳走。给旋钮用户做交互说明时要包含这个行为。

**Q11: 旋转事件怎么决定"移动焦点还是滚动内容"？加速策略是什么？**

RotaryService 对每个 `RotaryEvent`（或其按键化形式）先尝试移动焦点：在当前 FocusArea 内按遍历顺序找下一个可聚焦视图，找到就移动焦点、该次旋转被"消耗"；找不到（已到 FocusArea 边界且不循环）时，把剩余的旋转量以 `ACTION_SCROLL` 运动事件注入给最近的可滚动容器——注入而非代替应用执行，让应用自己决定滚动量（按 AAOS13 源码核对，`RotaryService.java` 的"先移动后滚动"路径）。声明了 `rotaryScrollEnabled` 的可滚动容器（配合 `FOCUS_BEFORE_DESCENDANTS` 等要求）则直接消费 `AXIS_SCROLL` 自己滚。

连续快速旋转有加速：相邻定位点平均间隔低于阈值（默认 20 ms）按 3 倍计、低于次阈值（默认 40 ms）按 2 倍计，两个阈值可由 RRO 调整（设为极大值即关闭加速）。VHAL 为每个定位点单独带时间戳时加速计算最准；只有一个总时长时按匀速假设。长列表场景"轻轻一转跳一格、快速转一滑到底"就是这套机制的手感来源。

排查"旋转不滚动"按这个顺序：焦点是否卡在不可滚动的 FocusArea（nudge 出去再试）；容器是否声明 `rotaryScrollEnabled`；加速配置是否被 RRO 改成了极端值；最后确认事件本身到达（`dumpsys` 捕获状态）。

**Q12: 应用适配旋钮要遵守哪些布局契约？**

布局契约集中在三件事：

1. **FocusArea**（car-ui-lib 组件）：把界面划成 nudge 的粗导航单元。常用属性包括 `defaultFocus`（nudge 进入时的默认视图）、`nudgeLeft/Right/Up/Down`（显式指定相邻 FocusArea，几何搜索找不到目标时兜底）、`wrapAround`（旋转到头是否循环）。FocusArea 不允许嵌套；界面不写任何 FocusArea 时根视图成为隐式单元——nudge 在应用内失效、只剩旋转遍历，这是"nudge 失灵但旋转正常"的第一原因；
2. **FocusParkingView**：每个窗口需要一个（通常放在根布局角落），职责有三个——跨窗口移动焦点时先把焦点"停"到它身上（Android 不会自动清除另一窗口的焦点，没有它会出现双窗口同时聚焦）、旋转到头循环时识别"即将绕回"、应用启动时作为焦点的初始落点再转到最佳视图；
3. **可聚焦性**：能被旋钮聚焦的条件是 focusable + enabled + visible + 尺寸非零；想表达"显示为禁用但仍可聚焦"用自定义状态而非 `setEnabled(false)`。

历史缓存的两个行为也要知道：反向 nudge 会回到上一个 FocusArea、并恢复其中上次聚焦的视图（两级缓存，超时与开关由 car-ui-lib 资源配置）。适配验证不要只测"能不能聚焦"，要按 nudge 进出、旋转遍历、边界循环、跨窗口移动四条路径走完。

**Q13: 仪表（cluster）屏能接收按键吗？两条路由分别是什么？**

能，但只接收 VHAL 事件里目标显示为 INSTRUMENT_CLUSTER 的按键，主屏按键不会被转给仪表。两条路由（按 AAOS13 源码核对）：

1. **renderer 回调链（传统）**：`InstrumentClusterService` 在初始化时注册为 cluster 键监听，`CarInputService` 分发到仪表键时回调 `InstrumentClusterRenderingService.onKeyEvent()`——这是 cluster 渲染服务的公开空实现，OEM 的仪表渲染服务覆写后自行处理（比如注入到仪表自己的 Presentation）；
2. **全捕获链（ClusterHome）**：Android 13 的 cluster Home 方案不走 renderer 回调，而是以 cluster 包名申请 `TAKE_ALL_EVENTS_FOR_DISPLAY` 全量捕获仪表屏输入，按键直接进 cluster 应用。

两条链互斥生效：注册了 renderer 监听时按键在 `CarInputService` 第 3 步就被截走，捕获栈里 cluster Home 的全量捕获对"已被 renderer 吃掉"的事件不可见。排查仪表按键问题的第一件事是确认设备用的哪条链（看是否有 cluster renderer 服务实现）。

**Q14: 车机的输入法有什么特殊性？旋钮怎么输入文本？**

AAOS 自带车机定制输入法 `packages/apps/Car/LatinIME`（包名与手机版 LatinIME 相同、服务类不同，按 AAOS13 源码核对 manifest）。旋钮输入文本的方案是**按交互模式切换输入法**：RotaryService 进入旋转模式时把默认输入法切换到配置指定的"旋钮输入法"（用方向键在候选按键间移动焦点、中心按钮确认的 IME），用户一旦触摸屏幕又切回触摸输入法，触摸输入法的选择按用户记忆保存。AOSP 默认的旋钮输入法配置是空串——即默认不切换，OEM 不提供旋钮输入法时用户就无法用旋钮打字（官方文档明说 Carboard 等触摸 IME 不支持旋控）。

驾驶限制与输入法的交叉：分心限制的完全受限组合里包含"禁用键盘"（`UX_RESTRICTIONS_NO_KEYBOARD`），驾驶状态下输入法弹窗本身会被限制逻辑拦下。

`ime list -s` 中 `-s` 表示只列出当前已启用的输入法 service，不能单独证明某个服务是旋钮输入法；还要核对组件实现与 RotaryService 配置。排查“旋钮没法打字”按三层：确认是否存在旋钮输入法；确认 RotaryService 的切换配置是否为空；确认当前限制状态是否禁了键盘。三层的日志特征完全不同，先分层再动手。

**Q15: 车机输入有哪些专用调试命令？**

按 AAOS13 源码核对（`CarShellCommand.java`）与官方 readme：

1. **注入**：`adb shell cmd car_service inject-key [-d 0|1] [-t 延迟ms | -a down|up] <键码>`（缺省按下抬起成对，`-d 1` 投到仪表）；`inject-rotary [-d 显示] [-i 10|11] [-c true] [-dt 毫秒差列表]`（`-i` 10=导航旋钮、11=音量旋钮，`-dt` 要求降序非负）；`inject-custom-input [-d 显示] [-r 重复] F1..F10|整数`。注意这些命令直调 `CarInputService` 的注入入口，**走完整车载分发链（含捕获仲裁）**，与 `adb shell input keyevent`（绕过 CarService 直注入系统）语义不同——测车载行为用前者，测应用层行为用后者；
2. **状态**：`dumpsys car_service --services CarInputService`（长按配置、捕获控制器状态）、`--services InputHalService`（三个能力标志，VHAL 是否支持输入看这里）；
3. **无旋钮模拟旋控**：userdebug 版本开启 `settings put secure android.car.ROTARY_KEY_EVENT_FILTER 1` 后，用键盘模拟——WASD 或方向键 nudge、F 或逗号中心、R 或 Esc 返回、Q/C 逆时针旋转、E/V 顺时针（Shift 按住按 10 格计）；仅 debuggable 构建生效；
4. **模拟器**：`car_x86_64` 镜像的 Extended controls 有 Car rotary 面板。

组合用法：怀疑捕获被抢占时，先用 `dumpsys` 看捕获栈，再分别用 `inject-key`（过 CarService）与 `input keyevent`（不过 CarService）注入同一键码——前者无响应后者有响应，问题定位在 CarService 层的仲裁；两者都无响应才往应用层查。

**Q16: "旋钮/按键没反应"的车机分层排查怎么做？**

按链路从上游到下游五层收敛，每层有明确观测点：

1. **VHAL 层**：`dumpsys car_service --services InputHalService` 看三个能力标志；确认供应商实现真的在上报属性（`cmd car_service` 侧或整车调试工具读 `HW_KEY_INPUT`/`HW_ROTARY_INPUT`）。格式错误的旋钮上报会被这一层静默丢弃（定位点数为 0、数组长度不对），只有错误日志可寻；
2. **CarService 层**：`CarInputService` 初始化时若判定"VHAL 不支持按键输入"，整个监听不注册——先确认服务初始化日志；语音/通话键到此已被消费，属正常；
3. **捕获层**：`dumpsys` 捕获控制器看栈——事件被谁捕获、自己的服务是否被顶到栈下；确认是否有应用申请了 `TAKE_ALL`；
4. **服务存活层**：RotaryService 是无障碍服务，被禁用/崩溃即焦点导航失效；`dumpsys accessibility` 看状态（CarService 会在用户切换时自动重启用，但重试有上限）；
5. **应用层**：布局缺 FocusArea（nudge 失效）、缺 FocusParkingView（跨窗口焦点异常）、控件不可聚焦（focusable、enabled、可见、尺寸非零四要素）、焦点被 HUN（通知横幅）的 nudge 劫持逻辑抢走。

辅助判据：`inject-key`（走 CarService）有效而 `input keyevent`（不走）无效 → 问题在 CarService 之前或捕获层；两者都无效但无障碍状态正常 → 应用布局层；`getevent` 有旋钮输出而 VHAL 无上报 → 供应商 VHAL 实现没接这个设备（设备链与 VHAL 链没打通）。每层都有 dump 与命令，按层走查比整链乱猜快得多。

**Q17: 定制旋控体验前，RotaryService 有哪些内部机制必须知道？**

四个机制直接决定定制效果（按 AAOS13 源码核对，`RotaryService.java`）：

1. **触摸退出检测**：服务持有一个 0×0 的系统级悬浮窗口（`TYPE_APPLICATION_OVERLAY` 配 `FLAG_WATCH_OUTSIDE_TOUCH`），靠 `ACTION_OUTSIDE` 感知真实触摸并退出旋转模式、清掉焦点——即使服务崩溃重启留下状态残留也能被下一次触摸纠正；旋钮输入法键盘上产生的"触摸"按时间窗忽略，避免误退出；
2. **HUN（横幅通知）nudge 劫持**：横幅在屏幕底部时，向下的 nudge 被解释为"聚焦横幅"、向上为"从横幅逃逸"，方向由配置资源决定且必须与车机 SystemUI、通知侧的同名资源一致——三处配置不一致是"nudge 被横幅吃掉"类问题的第一原因；
3. **SurfaceView 特殊处理**：SurfaceView 的内容不在 View 树的普通绘制层，焦点高亮与可聚焦区域要按其内容位置修正（服务内有专用的 SurfaceView 辅助类），地图、视频类应用适配旋钮时重点验证；
4. **FocusArea 历史缓存**：反向 nudge 回上一个 FocusArea 并恢复其中上次聚焦的视图，缓存类型、过期时长与"旋转时是否清历史"由 car-ui-lib 资源控制，产品手感（要不要记住上次位置）调这里。

改这些机制的正确姿势是配置优先：nudge 方向、旋转加速阈值、历史缓存都有资源/RRO 出口，直接改服务代码会给后续 OTA 合入埋冲突。验证手法：userdebug 版本 `settings put secure android.car.ROTARY_KEY_EVENT_FILTER 1` 开启键盘模拟旋控（WASD/方向键 nudge、F 或逗号中心、R 或 Esc 返回、Q/C 逆时针旋转、E/V 顺时针），配合 `dumpsys accessibility` 看服务绑定状态。
