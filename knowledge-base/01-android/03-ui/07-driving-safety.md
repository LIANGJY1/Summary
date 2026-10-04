# 车机交互安全与驾驶分心

> 学习资料（文章模式沉淀）。主线：驾驶分心约束的来源与建模、驾驶状态判定、UX 限制映射表与自动提升规则、restriction mode 与持久化、应用侧消费限制的正确姿势、模板应用的"分心优化"声明、限制读取失败的全限制兜底、设置类界面的行驶态放行、乘员屏触控锁定、限制变化时的界面处理、视频与长文本等通用限制、语音优先与物理控件定位、分心场景的验证方法、车机 UI 的其他安全约束。CarService 实现按本地 AAOS13 源码（Android 13）核对（CarUxRestrictionsManagerService.java、CarDrivingStateService.java、CarUxRestrictionsConfigurationXmlParser.java、`car-lib/src/android/car/`），规则与配置格式按官方文档口径（2026-09 检索）。架构上下文见 [06-aaos-ui.md](06-aaos-ui.md)，UI 定制与防护见 [11-ui-debugging.md](11-ui-debugging.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 车速刚超过零就锁死所有界面，为什么应用不能自行硬编码驾驶限制？**

应用不能按车速自行决定界面限制，因为驾驶分心规则由车型与市场策略配置，应用应消费平台提供的 UX Restrictions。

应用与平台的职责边界如下：

1. **车辆状态**：CarDrivingStateManager 根据车辆信号计算 Parked、Idling、Moving 等驾驶状态。
2. **限制策略**：CarUxRestrictionsManagerService 将驾驶状态和当前 restriction mode 映射为 UX 限制。
3. **应用行为**：应用监听 UX 限制并调整交互，不自行复制车速阈值或法规规则。
4. **驾驶状态用途**：只有确实与界面限制无关的功能才直接消费驾驶状态。界面是否要裁剪交互由 UX Restrictions 决定。

**Q2: 车辆从驻车进入行驶后 UI 未切换，驾驶状态是怎样从车辆信号推导出来的？**

驾驶状态由车辆属性推导，再映射为 UX Restrictions。不能把车速阈值直接写入各应用。

1. **输入属性**：系统结合车速、挡位和驻车制动等车辆属性计算驾驶状态。
2. **状态分类**：AAOS 将车辆归为 Parked、Idling 或 Moving。挡位不在 Park 且速度为零时属于 Idling，不是 Parked。
3. **限制映射**：UX Restrictions 服务按当前驾驶状态和配置的映射规则生成限制并通知客户端。
4. **启动边界**：系统收到首次有效驾驶状态前不会执行 UX 限制，并按 Parked 处理。车型集成必须验证车辆信号的可用时机，不能把未知状态描述为平台自动全限制。

**Q3: 同一车机在不同车型限制不同，UX Restrictions 映射表如何表达差异？**

不同车型可用不同 UX Restrictions 配置，把驾驶状态映射到各自的限制集合。应用消费映射结果，不在应用内推测市场规则。

1. **状态规则**：`car_ux_restrictions_map.xml` 可为 Parked、Idling、Moving 分别配置 `requiresDistractionOptimization` 与 `uxr`。Android 文档的默认示例为 Parked 使用 baseline、Idling 禁止视频和设置、Moving 使用 fully_restricted。
2. **显示规则**：可用 `RestrictionMapping` 按物理显示端口配置不同限制。官方默认行为是不对附加显示施加限制，因此产品若要限制副驾或后排屏，必须显式配置并验证。
3. **产品决策**：后排屏能否播放视频取决于法规、车型策略和显示映射配置，不能让应用开发者通过车速判断自行决定。

**Q4: 配置设了 no_video 却仍要求分心优化，uxr 与 baseline 的关系是什么？**

AAOS 13 的限制配置中，只要 `uxr` 不是 `baseline`，系统就要求 `requiresDistractionOptimization` 为 true。设置成 false 也会自动提升。

1. **字段含义**：`uxr` 表示当前限制集合，`requiresDistractionOptimization` 表示前台 Activity 是否必须声明已按受限驾驶体验设计。
2. **一致性规则**：`baseline` 表示没有具体限制位。任一非 baseline 限制都要求 Activity 满足分心优化声明。
3. **配置结果**：需要 false 的状态必须配置 baseline。否则系统自动要求分心优化，避免配置标记与实际限制冲突。

**Q5: 车型切换后限制配置未生效，restriction mode 为什么需要独立于驾驶状态？**

Restriction mode 选择同一驾驶状态下要使用的规则集合，DrivingState 则表示车辆的 Parked、Idling 或 Moving 状态。两者是独立维度。

1. **运行时模式**：`CarUxRestrictionsManager.setRestrictionMode()` 选择当前配置中具名的 restriction mode，例如 passenger mode，使同一驾驶状态可使用另一套限制。
2. **规则配置更新**：`saveUxRestrictionsConfigurationForNextBoot()` 持久化新的配置，不会立即替换正在使用的规则。更新后的配置要等 Car Service 重启且车辆处于 Parked 后加载。
3. **权限与验收**：保存配置需要 `Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION`。分别验证模式切换结果与持久化规则的加载时机，不要把两类 API 当成同一操作。

**Q6: 页面只在 onCreate 读取 UX 限制，行驶后仍可操作，应用应监听什么？**

应用只在 `onCreate()` 读取一次限制会漏掉之后的驾驶状态变化。应注册 `OnUxRestrictionsChangedListener` 并在界面相关事件中更新内容。

1. **订阅更新**：使用 `CarUxRestrictionsManager.registerListener()` 监听新限制，并在初始化时调用 `getCurrentCarUxRestrictions()` 获取当前值。
2. **解释结果**：检查 `isRequiresDistractionOptimization()` 判断 Activity 是否需要分心优化，再检查 `getActiveRestrictions()` 判断应裁剪哪些具体功能。
3. **更新时机**：限制变化时立即更新可见界面，不以 Activity 是否刚创建或是否收到窗口可见性回调作为唯一触发条件。

**Q7: Car App 在行驶中无法启动，清单里的 distractionOptimized 声明有什么作用？**

`distractionOptimized` 元数据让平台知道某个 Activity 声明自己可在受限驾驶状态运行。它不是运行时合规证明，也不代表该 Activity 自动获准启动。

1. **声明位置**：将 `<meta-data android:name="distractionOptimized" android:value="true"/>` 放在对应的 `<activity>` 中。省略时平台按未声明处理，该 Activity 在 UX 限制要求分心优化时可能被阻止。
2. **声明范围**：应用可只将符合要求的 Activity 标为 distraction optimized。其他 Activity 可在驻车等不受限状态提供不同体验。
3. **阻断排查**：先核对目标 Activity 的包名、清单合并结果和元数据位置，再检查当前 UX 限制及系统阻断策略。平台只检查声明，实际界面是否符合要求由应用审查和发行流程负责。

**Q8: 升级后车机进入全限制状态，配置读取失败时系统为何采用安全兜底？**

保存的 UX Restrictions 配置读取失败时，服务会回退到硬编码的 fully restricted 配置。这是读取失败的安全兜底，不是所有全限制状态都代表故障。

1. **触发条件**：配置项读取失败（例如出现 `SettingNotFoundException`）时，服务无法安全恢复车型规则。
2. **兜底行为**：服务使用硬编码配置，将 Idling/Moving 置为 fully restricted，避免错误配置放开交互。
3. **排查方向**：升级后验证已保存配置能否迁移和读取。出现全限制时检查配置读取、当前驾驶状态、restriction mode 和显示映射。车型也可能有意配置全限制，因此单凭结果不能断定配置故障。

**Q9: 行驶中需要开放语音设置入口，哪些界面可以通过配置豁免 UX 限制？**

行驶时要开放设置入口，应按 Car Settings 的 UX 豁免配置逐级放行所需 preference，而不是关闭全局驾驶限制。

1. **配置对象**：`config_ignore_ux_restrictions` 中列出允许行驶时操作的 preference key。每个 key 必须对应实际设置项。
2. **省略行为**：未列入 `config_ignore_ux_restrictions` 的 preference 仍按 Car Settings 默认 UX 限制处理。仅放行深层 preference 本身不一定能到达该页面，进入路径上的父 preference 也要按需要列入配置。
3. **变更审查**：名单应保持最小且可审计，每项都要有产品和合规依据，并在发布配置中验证实际导航路径。

**Q10: 副驾触屏被锁或后排屏输入异常，显示触控设置与输入类型声明如何配合？**

触控锁定配置按显示标识作用于目标屏幕。从 Android 14 起，occupant zone 与显示类型的输入映射还必须显式声明其输入类型。

1. **显示锁定**：CarSettings 保存显示唯一标识，匹配后限制该显示的触摸输入。必须验证标识指向目标屏幕，不能只按座位名称推断。
2. **Android 14 输入映射**：`config_occupant_display_mapping` 中每个关联的 occupant zone/display type 都必须指定至少一种输入类型。无输入设备时使用 `INPUT_TYPE_NONE`。
3. **无输入含义**：`INPUT_TYPE_NONE` 表示该映射不关联输入设备，不等于系统自动锁定显示。产品应为该显示明确设计并验证可用的其他交互通道。

**Q11: 车辆行驶状态变化后页面仍保留旧控件，应用应怎样处理限制更新？**

收到新限制后，应用应遵守以下处理纪律：

1. 立即更新内容形态，不要等到下次进入页面。
2. 把降级形态建模为明确、可测试的状态。
3. 只调整与驾驶安全相关的内容，保留无关状态。
4. 分别处理限制变化与窗口可见性变化，不要用其中一个事件代替另一个。

常见错误包括：

1. 只在 onCreate 读取限制，导致行驶状态变化后页面不更新。
2. 只在 Activity 可见时更新，漏掉后台期间的限制变化。
3. 把"要求分心优化"误解为"完全不可交互"。该标记表示界面已经按受限形态设计。
4. 限制变化时直接调用 finish()，导致用户在行驶中反复退出页面。

**Q12: 驾驶时视频、长文本或复杂交互被禁用，限制依据是什么、界面应如何降级？**

因为它们的共同风险是"视线离开路面"：视频与长列表会诱发持续注视，长文本阅读时间长且易被声音干扰分心，复杂交互要求多次精确点击，单次操作时长随手指移动而放大。官方映射表给出的档位正是 no_video、no_config、fully_restricted 这类内容级限制（source.android.com 文档口径）。

设计上的推论是"受限形态要短、要有终点"：一屏一个决定、最多两级导航、结论式展示而不是信息流。这比逐条功能开关更有效——限制的是交互形态本身，而不是让开发逐个功能自己判断。

**Q13: 驾驶员需要操作高频功能却不应盯屏，语音和物理控件各解决什么风险？**

因为驾驶场景下"手离开方向盘"与"视线离开路面"两类风险都要控制，语音是无需分心的输入通道，旋钮与按键则是不依赖视觉定位的操作面。本地 AOSP 自带旋钮控制器应用做仲裁，也说明这类输入是系统级基础设施而非应用私有功能。

设计含义：高频与安全相关的操作（音量、空调、导航目的地）必须有不依赖触控的路径。只做触控的实现即使功能正确，在行驶场景下也不合格。

**Q14: UX 限制功能通过了驻车测试却在路测失效，怎样按配置到交互构造验证？**

按"配置 → 系统 → 应用 → 交互"四层构造用例并留证据：

1. **配置层**：列出所有驾驶状态与每个限制模式下的预期行为，检查映射表是否覆盖了全部状态与显示（含附加显示的端口映射）。
2. **系统层**：实车或模拟环境制造状态转移（驻车、怠速、起步、停车），抓服务状态确认限制广播值与配置一致，并单独验证配置读取失败时的全限制兜底。
3. **应用层**：确认声明完整、监听持续有效、进入受限形态与退出受限形态都做了断言（截图或状态日志）。
4. **交互层**：受限形态下走一遍全部可交互元素，确认没有残留的视频播放、可滚动长列表或多级入口。

留证据的原则是"每次判定都对应一条可复查的记录"，否则这类合规性功能在回归时最先失效。

**Q15: 倒车影像、车速显示和音量限制同时影响页面时，如何纳入 UI 安全设计？**

设计阶段应纳入三类常见约束：

1. **覆盖型安全界面**：倒车影像、驻车辅助可能需要临时获得全屏与更高显示优先级。它们与 UX 限制并行，最终优先级由系统产品策略确定。
2. **车速相关的显示限制**：车辆信号达到阈值时隐藏特定信息或提示。
3. **音量与提示音约束**：音量上限会影响 UI 提示音设计，音量焦点与 duck/mute 机制见 [AAOS 车机音频](../09-audio/03-aaos-audio.md)。

把这些约束统一到一处管理（配置加服务加应用三层各司其职）是比逐页面打补丁更可持续的做法，因为它们都属于"安全要求而非功能需求"，最容易在功能迭代中被挤掉。

**Q16: 驾驶分心限制（UXR）到底能禁什么？FULLY_RESTRICTED 的位标志清单是什么？**

应用读取 `CarUxRestrictions` 并按 `isRequiresDistractionOptimization()` 与有效限制位调整 UI。`FULLY_RESTRICTED` 表示九个限制位同时生效，不是平台替应用关闭所有界面。

1. **限制位清单**：`NO_DIALPAD` 禁止拨号盘，`NO_FILTERING` 禁止字符过滤，`NO_KEYBOARD` 禁止手动文本输入，`NO_VIDEO` 禁止视频及每秒超过一帧的动画，`NO_SETUP` 禁止设置，`NO_TEXT_MESSAGE` 禁止显示消息，`NO_VOICE_TRANSCRIPTION` 禁止显示语音转录，`LIMIT_STRING_LENGTH` 限制通用字符串长度，`LIMIT_CONTENT` 限制任务中可浏览的内容数与层级。
2. **读取限制参数**：使用 `getMaxRestrictedStringLength()`、`getMaxCumulativeContentItems()` 和 `getMaxContentDepth()` 读取实际限制值，不要假定默认数值。AAOS 13 可配置这些限制，默认值可能被车型配置覆盖。
3. **执行边界**：UX Restrictions 提供应用侧应遵守的限制值。平台还能按 Activity 的 distractionOptimized 声明阻止受限状态下启动界面，但不能检查应用实际界面是否遵守限制。

**Q17: OEM 怎么定制分心限制规则？为什么改了 car_ux_restrictions_map.xml 没立即生效？**

OEM 可通过 RRO 覆盖 `car_ux_restrictions_map.xml` 的资源配置。若保存了 production 配置，新配置不会立即替换当前规则，而是在 Car Service 重启且车辆处于 Parked 后晋升生效。因此只改资源后观察当前运行态，可能看不到结果。

1. **状态与模式**：`state` 选择 Parked、Idling 或 Moving，`mode` 选择规则组。AAOS 13 省略 `mode` 时使用 `baseline`，可显式写 `passenger` 等模式名。
2. **显示范围**：`physicalPort` 指定物理显示端口，或用 `occupantZoneId` 与 `displayType` 指定乘员区显示。AAOS 13 省略显示标识时映射到默认显示，不能据此推断附加显示规则。
3. **限制声明**：`requiresDistractionOptimization` 控制是否要求前台 Activity 声明分心优化，省略时默认为 `true`。`uxr` 指定限制集合，省略时默认为 `fully_restricted`。非 `baseline` 的 `uxr` 会将分心优化要求提升为 `true`。
4. **速度范围**：Moving 状态可按 `minSpeed`、`maxSpeed` 配置速度区间。两者都省略表示该规则覆盖完整速度范围，不能当成零速或空区间。
5. **配置失败与诊断**：AAOS 13 读取保存配置失败时回退到硬编码 fully restricted 配置。先运行 `dumpsys car_service --services CarUxRestrictionsManagerService` 查看实际状态与限制，再运行 `cmd overlay list` 确认 RRO。覆盖所有驾驶状态、速度范围、restriction mode 和目标显示后再交付。

**Q18: 行驶中打开应用被全屏遮罩挡住——系统怎么判定和阻断？"IDENTIFY_DISTRACTION 权限"是真的吗？**

行驶中非 DO（distraction optimized）应用会被 ActivityBlockingActivity 顶住——其宿主就是 CarSystemUI，判定依据是应用是否声明了 `distractionOptimized` meta-data。重要勘误：公开代码里不存在 `IDENTIFY_DISTRACTION` 权限（frameworks 的 AndroidManifest 全文无此词），Android 14/15 的驾驶员分心检测走的是 VHAL 属性链。

1. **阻断判定**：CarPackageManagerService 按 `isActivityDistractionOptimized` 判定 Activity 是否已声明分心优化，阻断 UI 组件可由 OEM 配置替换。应用侧合规声明是 `<meta-data android:name="distractionOptimized" android:value="true"/>`。被阻断先核对包名与 Activity 是否匹配声明。
2. **分心检测的真实机制**：VHAL 属性链 `DRIVER_DISTRACTION_SYSTEM_ENABLED/STATE/WARNING_ENABLED/WARNING`，car-lib 侧对应 DriverDistractionState/DriverDistractionWarning（FlaggedApi）与 experimental 的 CarDriverDistractionManager。UXR 框架仍是最终执行层。
3. **边界**：不要把不存在于该版本公开 API 的 `IDENTIFY_DISTRACTION` 权限当作应用接入方式。UX Restrictions 将限制传给应用，CarPackageManagerService 则按 Activity 的声明阻止不符合条件的界面启动。平台不能代替应用检查 UI 内容。实车核验时分别查看 VHAL 属性和 CarService 状态。
