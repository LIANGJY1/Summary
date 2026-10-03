# 车机交互安全与驾驶分心

> 学习资料（文章模式沉淀）。主线：驾驶分心约束的来源与建模、驾驶状态判定、UX 限制映射表与自动提升规则、restriction mode 与持久化、应用侧消费限制的正确姿势、模板应用的"分心优化"声明、限制读取失败的全限制兜底、设置类界面的行驶态放行、乘员屏触控锁定、限制变化时的界面处理、视频与长文本等通用限制、语音优先与物理控件定位、分心场景的验证方法、车机 UI 的其他安全约束。CarService 实现按本地 AAOS13 源码（Android 13）核对（CarUxRestrictionsManagerService.java、CarDrivingStateService.java、CarUxRestrictionsConfigurationXmlParser.java、`car-lib/src/android/car/`），规则与配置格式按官方文档口径（2026-09 检索）。架构上下文见 [06-aaos-ui.md](06-aaos-ui.md)，UI 定制与防护见 [08-ui-debugging.md](08-ui-debugging.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 车速刚超过零就锁死所有界面，为什么应用不能自行硬编码驾驶限制？**

因为约束不是技术选择，而是法规与市场差异的映射。不同市场的分心法规不同（例如对视频、复杂交互的容忍度不同），同一套 Android 版本在不同地区、同一地区不同车型可能适用不同要求。官方文档因此要求"应用监听 UX 限制，而不是监听驾驶状态"，正是为了让平台把市场差异收在一个地方，应用只消费结果（source.android.com 文档口径）。

自己按车速硬编码会同时错两次：一是把合规责任从平台转移到每个应用，二是市场要求变化时应用侧无处可改。正确纪律是：驾驶状态只用于判断"要不要进入受限体验"这种展示性提示，行为裁剪一律听 UX 限制。

**Q2: 车辆从驻车进入行驶后 UI 未切换，驾驶状态是怎样从车辆信号推导出来的？**

由车辆属性（主要是车速等信号）按配置推导。官方文档描述的链路是：CarDrivingStateManager 按速度区间推出当前驾驶状态，服务把规则保存在内存里，再把驾驶状态映射为 UX 限制并广播给全系统（source.android.com 文档口径）。本地源码对应 `packages/services/Car/service/src/com/android/car/CarDrivingStateService.java`（核对）。

配置里最常见的三档状态是驻车、怠速、行驶，示例配置中行驶态从给定最低速度起生效（官方示例口径）。这意味着"低速但未驻车"的中间态存在，UI 状态机必须把"未知/过渡态"也当作受限态处理，否则会出现"刚松刹车的一瞬间弹出可交互界面"的事故窗口。

**Q3: 同一车机在不同车型限制不同，UX Restrictions 映射表如何表达差异？**

UX 限制（UX Restrictions）是一组声明式约束，表达"在当前驾驶状态下允许多少交互"。官方以 car_ux_restrictions_map.xml 描述：按驾驶状态给出 requiresDistractionOptimization 与 uxr 两个属性，官方示例中驻车态为不要求分心优化、限制为 baseline，怠速态要求分心优化且限制为 no_video|no_config，行驶态为 fully_restricted（source.android.com 文档口径）。

另有多显示的配置方式：用 RestrictionMapping 指定物理端口，为该显示单独给一套限制（默认附加显示不施加限制）。这条规则决定了"后排屏幕能不能看视频"是配置问题而不是代码问题——需要产品与法规一起定，而不是让开发猜。

**Q4: 配置设了 no_video 却仍要求分心优化，uxr 与 baseline 的关系是什么？**

因为除 baseline 外的每一档限制都意味着"某些内容必须受限"，而"分心优化"正是声明"我的界面已按受限形态设计"的标记；两者语义不一致时，配置本身无效。官方在映射表文件里直接写明了这条自动提升规则（source.android.com 文档口径）。

工程含义：配置里写 `uxr="no_video"` 却漏写 `requiresDistractionOptimization="true"`，实际生效的是"true"而不是你以为的 false。反过来若想要"不要求分心优化"，只能用 baseline——这也是为什么 baseline 是唯一"无限制"档。

**Q5: 车型切换后限制配置未生效，restriction mode 为什么需要独立于驾驶状态？**

因为"限制的模式"和"限制的具体内容"要分开：同一驾驶状态下，不同模式可以对应不同的限制集合，切换模式时不重启即可换一套限制。官方 API 提供 setRestrictionMode/getRestrictionMode，默认值是 UX_RESTRICTION_MODE_BASELINE，配置可持久化给下一次启动使用，且需要权限（Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION）（官方文档与本地 CarUxRestrictionsManagerService 的 mRestrictionMode、saveUxRestrictionsConfigurationForNextBoot 核对）。

典型用途是"乘客模式"这类场景：车辆静止时用一套限制，行驶时用另一套。把模式做成可运行时切换的而不是重启生效，是为了避免切换伴随重启这类体验断点。

**Q6: 页面只在 onCreate 读取 UX 限制，行驶后仍可操作，应用应监听什么？**

标准做法是向 CarUxRestrictionsManager 注册 OnUxRestrictionsChangedListener，在回调与 getCurrentUxRestrictions() 里判断两件事：当前是否要求分心优化（isRequiresDistractionOptimization()），以及当前有哪些具体限制（官方文档口径）。官方文档明确建议应用把驾驶状态留给"与界面体验无关"的判断，把与界面相关的判断全部交给 UX 限制。

只读一次限制是最常见的实现错误，表现为"从驻车驶出后界面没有降级"或"熄火后界面还锁着"，因为限制变化是持续广播的、且与窗口可见性无关限制会持续变化，应用需要在回调中更新界面，而不能只在页面创建时读取一次。

**Q7: Car App 在行驶中无法启动，清单里的 distractionOptimized 声明有什么作用？**

会被限制。官方文档明确写出：承载这类界面的 Activity 必须按分心优化要求标记，否则会被阻止运行（source.android.com 文档口径）。清单里对应的标记就是 distractionOptimized 的 meta-data（AAOS 模板应用文档口径）。

这意味着"应用在行驶中打不开"有时不是崩溃也不是权限问题，而是声明缺失或类别不符。排查顺序是：先看清单声明、再看应用类别与宿主支持，最后才看日志。

**Q8: 升级后车机进入全限制状态，配置读取失败时系统为何采用安全兜底？**

官方文档说明：读取已保存配置失败时（例如读取设置项失败），服务回落到硬编码的、全部限制的默认配置（source.android.com 文档口径）。这是安全优先的设计——不确定时按最严处理，避免"配置丢失 = 保护失效"。

对升级与发布的含义有两条：升级后必须验证限制配置是否被正确迁移，否则车机可能整段时间都处于"行驶中什么都看不了"的状态；反过来，"全限制"作为一个故障现象就足以证明配置读取链路出了问题，不必再往应用侧找原因。

**Q9: 行驶中需要开放语音设置入口，哪些界面可以通过配置豁免 UX 限制？**

官方做法是配置一份"行驶态可进入的设置路径"：把允许在行驶中打开的深层设置加入 config_ignore_ux_restrictions（source.android.com 文档口径，例如示例中为语音合成输出设置放行）。这样只有被列出的路径豁免，其余仍然受限。

纪律是这份名单要短且可审计——每加一条都要有产品与法规依据，因为豁免路径本身就是事故面；名单应随发布走评审而不是随开发便利增长。

**Q10: 副驾触屏被锁或后排屏输入异常，显示触控设置与输入类型声明如何配合？**

锁定通过系统设置按显示生效：CarSettings 里的显示触控锁定项保存的是一串显示唯一标识，命中即锁定该显示的触控输入（官方文档与本地源码核对）。另外从 Android 14 起，官方要求 config_occupant_display_mapping 中关联的每个占用区与显示类型都必须至少声明一种输入类型（可用触摸、自定义输入事件或无输入；本地 CarOccupantZoneManager 注释核对）。

这条新要求解决的是一类实际漏洞：不声明输入类型的屏幕，系统无法区分"这块屏不支持触摸"与"忘了配"，默认行为容易让不可控的触摸入口存在。声明为"无输入"的屏幕由系统按配置锁定，代价是它上的应用必须走旋钮或语音交互。

**Q11: 车辆行驶状态变化后页面仍保留旧控件，应用应怎样处理限制更新？**

收到新限制后，应用应遵守以下处理纪律：

1. 立即更新内容形态，不要等到下次进入页面。
2. 把降级形态建模为明确、可测试的状态。
3. 只调整与驾驶安全相关的内容，保留无关状态。
4. 分别处理限制变化与窗口可见性变化，不要用其中一个事件代替另一个。

常见错误包括：

1. 只在 onCreate 读取限制，导致行驶状态变化后页面不更新。
2. 只在 Activity 可见时更新，漏掉后台期间的限制变化。
3. 把"要求分心优化"误解为"完全不可交互"；该标记表示界面已经按受限形态设计。
4. 限制变化时直接调用 finish()，导致用户在行驶中反复退出页面。

**Q12: 驾驶时视频、长文本或复杂交互被禁用，限制依据是什么、界面应如何降级？**

因为它们的共同风险是"视线离开路面"：视频与长列表会诱发持续注视，长文本阅读时间长且易被声音干扰分心，复杂交互要求多次精确点击，单次操作时长随手指移动而放大。官方映射表给出的档位正是 no_video、no_config、fully_restricted 这类内容级限制（source.android.com 文档口径）。

设计上的推论是"受限形态要短、要有终点"：一屏一个决定、最多两级导航、结论式展示而不是信息流。这比逐条功能开关更有效——限制的是交互形态本身，而不是让开发逐个功能自己判断。

**Q13: 驾驶员需要操作高频功能却不应盯屏，语音和物理控件各解决什么风险？**

因为驾驶场景下"手离开方向盘"与"视线离开路面"两类风险都要控制，语音是无需分心的输入通道，旋钮与按键则是不依赖视觉定位的操作面。本地 AOSP 自带旋钮控制器应用做仲裁，也说明这类输入是系统级基础设施而非应用私有功能。

设计含义：高频与安全相关的操作（音量、空调、导航目的地）必须有不依赖触控的路径；只做触控的实现即使功能正确，在行驶场景下也不合格。

**Q14: UX 限制功能通过了驻车测试却在路测失效，怎样按配置到交互构造验证？**

按"配置 → 系统 → 应用 → 交互"四层构造用例并留证据：

1. **配置层**：列出所有驾驶状态与每个限制模式下的预期行为，检查映射表是否覆盖了全部状态与显示（含附加显示的端口映射）。
2. **系统层**：实车或模拟环境制造状态转移（驻车、怠速、起步、停车），抓服务状态确认限制广播值与配置一致，并单独验证配置读取失败时的全限制兜底。
3. **应用层**：确认声明完整、监听持续有效、进入受限形态与退出受限形态都做了断言（截图或状态日志）。
4. **交互层**：受限形态下走一遍全部可交互元素，确认没有残留的视频播放、可滚动长列表或多级入口。

留证据的原则是"每次判定都对应一条可复查的记录"，否则这类合规性功能在回归时最先失效。

**Q15: 倒车影像、车速显示和音量限制同时影响页面时，如何纳入 UI 安全设计？**

设计阶段应纳入三类常见约束：

1. **覆盖型安全界面**：倒车影像、驻车辅助通常需要临时获得全屏与更高显示优先级；它们与分心限制并行，安全界面应优先呈现。
2. **车速相关的显示限制**：车辆信号达到阈值时隐藏特定信息或提示。
3. **音量与提示音约束**：音量上限会影响 UI 提示音设计，音量焦点与 duck/mute 机制见 [AAOS 车机音频](../09-audio/03-aaos-audio.md)。

把这些约束统一到一处管理（配置加服务加应用三层各司其职）是比逐页面打补丁更可持续的做法，因为它们都属于"安全要求而非功能需求"，最容易在功能迭代中被挤掉。

**Q16: 驾驶分心限制（UXR）到底能禁什么？FULLY_RESTRICTED 的位标志清单是什么？**

应用按 `isRequiresDistractionOptimization()` 加各限制位裁剪 UI，而不是按车速自行判断；`FULLY_RESTRICTED` 是九个限制位的按位或——键盘、视频、拨号盘、设置、长文本全部被禁。

1. **限制位清单**：NO_DIALPAD、NO_FILTERING、NO_KEYBOARD、NO_VIDEO（大于 1fps 的动画即算视频）、NO_SETUP、NO_TEXT_MESSAGE、NO_VOICE_TRANSCRIPTION、LIMIT_STRING_LENGTH（默认 120 字符）、LIMIT_CONTENT（单任务默认 21 条、层级默认 3）；
2. **取配额**：长文本用 `getMaxRestrictedStringLength()` 拿实际允许长度，不要硬编码 120；
3. **执行层**：UXR 框架是分心治理的最终执行层（检测链见 Q18）。

**Q17: OEM 怎么定制分心限制规则？为什么改了 car_ux_restrictions_map.xml 没立即生效？**

OEM 用 RRO overlay 覆盖 `car_ux_restrictions_map.xml`；服务端配置有三级加载优先级——已保存的生产配置 > R.xml 资源 > 硬编码默认，且保存的配置要等合适的驾驶状态才晋升替换，所以改动常常"下次启动才生效"。

1. **映射结构**：按 DrivingState（parked/idling/moving 加速度分段）映射限制集；可加 `mode` 定义多套限制集（副驾屏解锁视频的正规路径）；显示定位支持 physicalPort 或 occupantZoneId+displayType，都不填默认主驾屏；
2. **兜底语义（高危）**：配置缺失或畸形时按"完全限制"兜底——requiresDistractionOptimization 默认 true、uxr 默认 fully_restricted；只要 uxr 不等于 baseline，即使声明 false 也会被提升为 true——写坏 xml 的表现就是全车 UI 被锁死；
3. **诊断**：`dumpsys car_service --services CarUxRestrictionsManagerService` 看 transition log（驾驶状态/速度/mode）；`cmd overlay list` 确认 RRO 生效；上线前覆盖全部驾驶状态分段并实车验证。

**Q18: 行驶中打开应用被全屏遮罩挡住——系统怎么判定和阻断？"IDENTIFY_DISTRACTION 权限"是真的吗？**

行驶中非 DO（distraction optimized）应用会被 ActivityBlockingActivity 顶住——其宿主就是 CarSystemUI，判定依据是应用是否声明了 `distractionOptimized` meta-data。重要勘误：公开代码里不存在 `IDENTIFY_DISTRACTION` 权限（frameworks 的 AndroidManifest 全文无此词），Android 14/15 的驾驶员分心检测走的是 VHAL 属性链。

1. **阻断判定**：CarPackageManagerService 按 `isActivityDistractionOptimized` 判定，阻断 UI 的组件串在 config 里可被 OEM 整体替换；应用侧合规声明是 `<meta-data android:name="distractionOptimized" android:value="true"/>`——被阻断先核对包名与 activity 是否匹配声明；
2. **分心检测的真实机制**：VHAL 属性链 `DRIVER_DISTRACTION_SYSTEM_ENABLED/STATE/WARNING_ENABLED/WARNING`，car-lib 侧对应 DriverDistractionState/DriverDistractionWarning（FlaggedApi）与 experimental 的 CarDriverDistractionManager；UXR 框架仍是最终执行层；
3. **边界**：凡资料里出现 "IDENTIFY_DISTRACTION" 一律按讹传处理；实车核验用 dumpsys 看 VHAL 属性订阅。
