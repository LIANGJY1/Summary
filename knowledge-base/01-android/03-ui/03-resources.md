# 资源、主题与多屏适配

> 学习资料（文章模式沉淀）。主线：资源限定符的选择与顺序规则、默认资源兜底与版本限定符、深色模式的三个处理面、动态取色与车机品牌主题的取舍、主题属性作用域与叠加、运行时资源叠加（RRO）与车机定制契约、dp/sp/fontScale 与无障碍字号、资源限定符不等于窗口尺寸、车机多屏下的 Configuration 分歧、多语言与 RTL、多变体布局与 ViewBinding 字段缺失、运行期 Configuration 变更纪律、生效变体的探针方法。规则按官方文档与 AOSP 约定口径（2026-09 检索），车机项目里“定制系统改过资源后兜底不可信”“ViewBinding 跨变体联合字段缺失”两条真实踩坑已在 docs/android-ui.md 就地记录，本文只给机制侧解释。Q 序列即结构，供 atlas 同源直读。

**Q1: 新增资源目录后设备始终命中默认文件，限定符的匹配优先级和书写顺序是什么？**

资源系统按配置限定符的优先顺序筛选最匹配的变体，不是简单地让“限定符越多的目录优先”。多限定符目录必须按下面的完整顺序书写，未使用的限定符直接跳过：

1. **MCC/MNC**。
2. **语言、脚本与地区**。
3. **布局方向**。
4. **最小宽度、可用宽度与可用高度**。
5. **屏幕尺寸、长宽比与圆形屏幕**。
6. **广色域与 HDR**。
7. **屏幕方向**。
8. **UI 模式与夜间模式**，例如桌面模式和暗色界面。
9. **触摸屏类型、键盘可用性与类型、导航可用性与类型**。
10. **屏幕密度**。
11. **平台版本**。

目录顺序写错会令构建工具拒绝该资源目录。例如 drawable-night-hdpi 有效，drawable-hdpi-night 顺序错误。系统先排除与当前配置不匹配的变体，再按限定符优先级逐项比较，因此“匹配限定符数量最多”不是选择规则。所有候选都不匹配时才使用同类型的默认资源。排查未命中时，先核对目录拼写与顺序，再核对设备 Configuration。

**Q2: Android Drawable 表示什么，为什么它不等同于位图？**

Drawable 是可由 Android 绘制到屏幕上的图形资源抽象，可以来自位图文件，也可以由颜色、形状、图层或状态规则构成。View 背景、图标和按钮外观都可使用 Drawable，因此“一个 Drawable 就是一张图片”并不成立。

**Q3: Drawable 的 intrinsic width 和 intrinsic height 表示什么？**

Drawable 的 intrinsic 尺寸是该 Drawable 自身报告的推荐固有尺寸，不是其当前实际绘制边界，也不保证每种 Drawable 都有正值。容器尺寸、布局参数、缩放规则和 Drawable 的实现都会影响最终显示大小。

不能把 intrinsic 尺寸直接当成 View 的测量结果。需要固定界面尺寸时，应由布局或调用方明确设置尺寸与缩放策略。

**Q4: 常见 Drawable 类型各适合表达什么？**

不同 Drawable 把绘制内容或组合规则封装成可复用资源：

1. **BitmapDrawable**：显示位图图像。
2. **NinePatchDrawable**：按可拉伸区域缩放 `.9.png`，适合尺寸随内容变化的边框或气泡背景。
3. **ShapeDrawable**：用颜色、渐变、描边等描述简单几何外观。
4. **LayerDrawable**：按顺序叠放多个子 Drawable，后面的图层绘制在前面图层之上。
5. **ColorDrawable**：用单色填充。
6. **StateListDrawable**：按 pressed、focused 等 View 状态选择子 Drawable。
7. **VectorDrawable**：用矢量路径描述可缩放图形。

可用位图文件或 XML Drawable 资源声明这些图形，也可在代码中组合或实现自定义 Drawable。自定义实现需按 Drawable 契约响应 bounds、状态和 Canvas 绘制。

选择时依据需要表达的是图像内容、可拉伸区域、简单形状还是多层组合。复杂照片仍适合位图资源。

**Q5: [learning] 切回默认语言时因资源缺失崩溃，限定符变体应该怎样提供兜底？**

回落到同类型资源的默认目录（不含任何配置限定符的那一份），不是“找最接近的一个”。官方文档明确警告：如果一个资源只提供了带限定符的变体而没有默认变体，当设备配置不匹配时会得到 Resources.NotFoundException，典型案例是字符串只放在 values-en 而默认语言目录缺这一条，用户切回默认语言就崩（官方文档口径）。

因此每新增一种限定符，都要确认同一资源类型有完整的默认版本，并检查字符串、布局和 Drawable 等依赖是否也能从默认配置解析。验证时要覆盖最低支持 API 与当前目标系统的代表性 Configuration：前者发现旧系统忽略新限定符后的回退问题，后者验证新变体命中。仅构建两个 minSdk 值不能证明运行时选中了正确资源。

**Q6: 旧版 Android 忽略了新加的资源限定符，如何判断它从哪个 API 级别生效？**

旧版 Android 不认识后来加入的限定符时，会按旧系统可理解的配置选择资源，最终可能回落到默认资源。-night 从 API 8 支持。w<N>dp、h<N>dp 和 sw<N>dp 从 API 13 支持。可用两种方式检查：

1. **静态检查**：Android Studio 资源编辑器会灰显并提示目标平台不支持的限定符。
2. **运行时检查**：为各变体设置易区分的探测文本或颜色，或打印实际命中的资源名称。

默认资源应保持完整可用，新限定符只补充适配，不承担功能开关。

**Q7: [learning] 系统切换深色模式后应用仍是浅色，资源、读取逻辑和主题分别要处理什么？**

三件事各管一段，缺一不可：

1. **资源层**：把颜色、位图、主题属性等在夜间不同的值放到 values-night、drawable-night 等对应资源类型的变体中。-night 自 API 8 支持。
2. **读取层**：用 Configuration.uiMode 与 UI_MODE_NIGHT_MASK 比较 UI_MODE_NIGHT_YES/NO，或使用 Configuration.isNightModeActive()。应用需要覆盖系统模式时，按目标 API 和系统支持情况配置 UiModeManager。
3. **主题层**：主题本身要随模式切换（Theme.Material* 家族已具备），自定义主题则必须显式提供夜间版本，否则夜间只是“文字变白、背景还是白的”。

三者关系是：UI 模式变化是一次 Configuration 变化，默认会触发组件重建，所以运行期切换看起来“整页闪一下”。若在清单里声明了 configChanges 并自行处理，就必须自己重新解析受影响资源并刷新界面（官方文档口径）。

**Q8: [learning] 设备已进入深色模式但应用外观没变，应从资源、系统主题还是厂商定制层排查？**

按发生位置分三层，逐一排除：

1. **应用资源层**：确认夜间变体确实属于当前资源 ID、目录限定符顺序有效且当前 Configuration.uiMode 含夜间位。默认目录是兜底资源，不会抢在匹配成功的夜间变体前被选中。
2. **系统主题层**：车机与定制系统常把应用主题强制替换为厂商主题（组件 overlay 或主题应用），此时应用自己的夜间资源可能根本没被使用。
3. **定制框架层**：厂商可能改写了资源回退逻辑或主题应用顺序，“存在默认资源”不再等于“一定回落到它”。这种情况下不要靠推理，用探针实测：打印当前 Configuration.uiMode、density、fontScale，并在运行时反查某个已知 id 实际落到了哪个资源文件，据此建立事实基础（对应 docs/android-ui.md 里的资源探针条目）。

**Q9: 车机品牌要求固定配色时，为什么通常不跟随壁纸动态取色？**

动态取色是 Android 12（API 31）引入的系统动态配色机制，应用可在受支持的系统与主题方案下使用系统生成的调色板。车机和工业界面若受品牌规范、可读性目标或一致性验收约束，就应明确控制主题来源。壁纸不是稳定的品牌输入时，跟随壁纸会令同一界面在不同设备间改变主色。是否启用应由产品是否允许这种变化、对比度验证和目标系统支持共同决定，不能把所有车机都概括成必须关闭动态取色。

判断规则：品牌色是硬约束时用固定主题，关掉动态取色。只有当车机确实以壁纸配色为卖点、且已验证对比度可达标时才启用。

**Q10: [learning] 自定义主题颜色取错值但能编译，?attr 与 ?android:attr 的作用域差异是什么？**

`?android:attr/...` 取的是平台定义的属性，`?attr/...` 取的是当前应用或组件库自己定义的属性。前者在使用平台控件、引用平台内置主题属性时必要，后者用于组件库主题。当属性名在两处同名但含义不同（例如颜色与维度同时自定义），写错前缀会得到“能编译但取到别的值”的结果，且不易察觉。

配套纪律是主题继承：一个应用主题应继承一个基础主题再覆盖差异项，而不是复制粘贴整套属性。属性一旦成为对外契约（被 overlay 覆盖、被第三方应用引用），改名等于破坏兼容，这与 SDK 设计是同一类问题（docs/sdk-design.md 讨论这类契约）。

**Q11: [learning] 车厂希望不重编应用就替换资源，RRO 与编译期 overlay 的边界是什么？**

两者都能不重编应用替换资源，边界在生效时机与作用对象：编译期 overlay 改的是 APK 产物本身，RRO 在运行期按目标包叠加。

1. **编译期 overlay**：在构建系统镜像时把 overlay 目录的资源合并进目标包，产物是固定的镜像，适合系统与供应商在同一份源码树上协同。
2. **运行时 RRO**：目标包与 overlay 包在设备上分别安装，由资源管理子系统在运行时替换目标包中允许覆盖的资源。Android 10 起，目标包需通过 overlayable 声明暴露资源并指定可接受的策略，overlay 声明目标包与 targetName。Android 11 起可用资源映射 XML 指定映射。设备 Configuration 用于在 overlay 自身的同名资源变体中选择适配项，但这不等于 RRO 可任意按屏幕状态过滤。

车机 Car UI 库的官方做法是把“哪些资源可被定制”当作对外 API 来管理，生成定制的资源契约，并对定制失败提供专门的排障指引（source.android.com 文档口径）。工程含义是：定制点要少而明确，每个可覆盖资源都要考虑“被覆盖后应用是否仍能正常渲染”，因为覆盖发生在运行时、不会在编译期暴露错误。

**Q12: [learning] 用户调大字体后按钮文字溢出，dp、sp 与 fontScale 应如何配合？**

布局尺寸通常用 dp，文字字号用 sp。不要把 px 当作可跨密度、字号设置稳定的布局单位。sp 会按系统字体缩放，dp 不随字体缩放。Android 14 起系统支持最高 200% 字体缩放与非线性缩放，因此不要自行用 fontScale 乘字号模拟转换。应以最大字号验证文本、按钮和滚动区域。

正确做法是让容器高度由内容决定（用 wrap 语义加最小高度），而不是给文本容器写死高度。在需要严格适配的场景提供可选的大字号布局变体，并把“最大字号下不破版”作为验收项——这是可访问性的硬要求，不是体验优化。

**Q13: 分屏窗口变窄但资源布局没切换，为什么资源限定符不等同于窗口尺寸？**

资源变体的选择依据是 Configuration 中的宽高阈值等离散属性，不是对窗口像素边界的连续查询。在多窗口模式下，可用宽度和高度会反映当前应用窗口并随窗口变化，因此 w<N>dp、h<N>dp 等资源变体可能重新选择。但阈值只能表达离散布局档位，不能替代对连续窗口尺寸的适配。sw<N>dp 表示可用最小宽度，不能简单当成当前窗口宽度。

适配依据分两类：

1. **有限的布局档位**：可按资源限定符表达 Configuration 的阈值变化，并保证每种匹配配置都有默认资源兜底。
2. **连续窗口尺寸与折叠状态**：用当前窗口的 WindowMetrics 或窗口尺寸类在运行时选择布局，不能把资源限定符当作精确窗口边界。

**Q14: [learning] 车机副驾屏布局、字号或密度与主屏不一致，应用应从哪个显示上下文取配置？**

车机上同一应用可能被创建到多个显示（不同座位区的屏幕），这些显示在分辨率、密度、尺寸类别上可以完全不同。Activity 的 Configuration 与显示上下文相关。常见后果有三个：

1. 只提供一套横屏布局的应用在窄屏座位区显示上被拉伸或截断。
2. 用 DisplayMetrics 里的全局默认值而非当前显示值计算尺寸，导致位置偏移。
3. 深浅色与字号按“系统设置”统一取值，无法按屏区分。

对策是按“显示”而非“设备”取度量：布局参数从本次窗口的实际尺寸来，密度与 Configuration 从当前 Activity 的上下文取，必要时按显示 id 显式查询并为不同屏幕准备独立资源变体。

**Q15: [learning] 切换到阿拉伯语后控件方向错乱或部分字符串缺失，多语言和 RTL 资源要怎样组织？**

1. 位置语义用 start/end 而不是 left/right，并在应用清单的 application 上设置 supportsRtl="true"。该属性从 API 17 支持，系统随后可按布局方向解析资源并镜像适用的布局属性。
2. 确实需要方向专属的图像或布局时使用 -ldrtl 资源限定符。它表示布局方向，不表示阿拉伯语语言。语言限定符在布局方向限定符前参与匹配，因此不能假定语言名与 ldrtl 可互换。
3. 文案不要硬编码进布局，长文案容器要能换行并考虑截断策略。

只翻译一部分的后果不是“显示英文”，而是某些配置下找不到默认版本、界面直接抛 Resources.NotFoundException。只翻译一半且恰好有默认版本，则会出现“有的页面中文、有的页面回落到英文或直接缺字符串”的混合体验，验收时必须按“每种语言下每个界面都跑一遍”来覆盖。

**Q16: ViewBinding 解决了什么问题，Fragment 中的 Binding 应在什么生命周期边界释放？**

ViewBinding 为每个 XML 布局生成类型化绑定类，提供对布局内 View 的直接引用，减少手写 `findViewById()` 和错误强转。开启模块级配置后，按布局文件名生成对应的 Binding 类型。Activity 的 Binding 通常与 Activity 内容视图同寿命。Fragment 的 View 可以先于 Fragment 实例销毁，因此 Binding 字段必须在 `onDestroyView()` 置空，不能一直保留旧视图树。

若多个资源变体使用同一个布局名，各变体的 View 集合可能不同。只出现在部分变体的字段会成为可空字段，访问前要按实际布局处理。

**Q17: [learning] 某个 ViewBinding 字段在大屏变体上为空，layout 资源之间必须满足什么契约？**

ViewBinding 按“所有变体的字段并集”生成绑定类：某个控件只在部分变体里出现时，它在生成类中对应字段就是可空类型。运行时选中的变体不含该控件，字段就为 null。直接访问会空指针。

处理时要保证代码与布局变体满足同一份契约：

1. **保持相同控件骨架**：各变体都提供相同控件，把差异放在约束和资源中。
2. **允许控件缺席**：访问绑定字段前显式判空，并提供合理的替代行为。

**Q18: 横竖屏或夜间模式切换后页面状态异常，何时让系统重建、何时自行处理 Configuration？**

原则是优先让系统重建并重选资源。只有重建代价明确不可接受且状态保存恢复方案完整时，才由应用接管特定 Configuration 变化。

1. **接管范围**：android:configChanges 只接管清单中声明的变化位，其他未声明变化仍可能触发 Activity 重建。横竖屏切换还可能同时改变 screenSize、screenLayout 等配置，只声明 orientation 不保证避免重建。
2. **回调责任**：接管后，应用要依据新 Configuration 刷新受影响 UI。只调用父类 onConfigurationChanged 不会自动刷新自定义缓存或手工计算的布局数据。
3. **主动改配置**：不要用已弃用的 Resources.updateConfiguration 模拟设备配置。它不能替代系统配置分发，也不能正确模拟窗口几何变化。测试变体应通过设备配置或仅影响应用预览逻辑的显式测试入口完成。

**Q19: 运行时配置看似正确但仍拿到错误图片，如何确认实际命中的资源变体？**

按配置、资源取值、构建产物三层实测：

1. **配置层**：打印 Configuration.uiMode、density、fontScale、screenLayout、当前显示 id 与其 DisplayMetrics，确认输入符合预期。
2. **取值层**：对关键资源用 getString/getDrawable 取值并打印关键属性（颜色值、尺寸值），判断是否拿到了夜间/大屏应有的那份。
3. **产物层**：用 Android Studio 的 APK Analyzer 或构建产物检查工具确认资源 ID、打包变体与 overlay 映射。getResourceName 返回的是资源名称，不是设备上实际文件路径。运行时也没有稳定的公开 API 可把任意资源 ID 还原为原始源文件路径。

若设备启用了厂商 overlay，还要结合资源配置与 overlay 状态确认最终取值来源。这样可区分输入配置不对、资源解析结果不对、构建产物或运行时 overlay 改写了资源这几类原因。

**Q20: [learning] Resources、ResourcesImpl、AssetManager 三层各持有什么？“旋转一次就新建一个 ResourcesImpl”为什么不成立？**

这些对象分别位于 API 包装、资源实现和资源表管理层，不能把 Context 数量等同于 ResourcesImpl 或 AssetManager 数量。

1. **Resources**：公开资源 API，向调用方提供字符串、Drawable 等类型化访问。
2. **ResourcesImpl**：持有与一组配置关联的资源解析状态、显示度量和缓存，并通过 AssetManager 查表。
3. **AssetManager**：管理 APK、split、overlay 和共享库提供的资源表。
4. **共享与替换**：多个 Resources 可共享同一 ResourcesImpl。Android 13 的 ResourcesKey 将资源路径、overlay/共享库路径、displayId、覆盖配置、兼容参数和 ResourcesLoader 等作为区分因素。配置变化时资源管理器可更新现有实现，键所代表的资源组合变化时才需要切换实现。
5. **应用侧影响**：createConfigurationContext() 会为覆盖配置创建独立的资源上下文。短期使用后应释放引用。长期缓存此类 Context 可能保留旧 Activity 或增加同时存活的资源实现，但仅看到实现数量增加还不足以证明泄漏。

**Q21: 一次 Configuration 变更后，系统如何决定 Activity 是热派发 onConfigurationChanged 还是销毁重建？relaunch 等于冷启动吗？**

服务端会把进程级配置变化应用到资源，并比较每个 Activity 的配置差异与其接管范围，决定是否重建 Activity 或回调 onConfigurationChanged。重建会重新创建 Activity 实例，但通常复用仍存活的进程，因此它不等于进程冷启动。

1. **资源更新**：ResourcesManager 将新配置应用到资源解析状态，后续资源查找据此选择变体。
2. **Activity 判定**：ActivityTaskManager/ActivityThread 根据该 Activity 实际变化位及清单声明的 configChanges 判断是否 relaunch。未接管的变化可能触发重建。
3. **热派发**：被 Activity 接管的变化通过 onConfigurationChanged 通知应用。应用需要刷新自行缓存的值和界面状态。
4. **重建开销**：Activity 生命周期与视图树重走，保存状态可用于恢复。进程、Application 和进程内缓存通常仍在，因此不能把它称为冷启动。初始化逻辑很重时，单个 Activity 重建仍可能造成明显延迟。
5. **诊断**：用 Perfetto/系统日志观察 Activity relaunch 与配置回调。不同 Android 版本的 trace 名称可能变化，不能把某个固定事件名作为唯一判据。

**Q22: android:configChanges 接管旋转后系统还做什么？Android 17 的 recreateOnConfigChanges 五类扩展为什么不能在 Android 13 上依赖？**

configChanges 不冻结配置。它改变的是 Activity 对选定变化的处理路径。Android 17 对若干输入设备与显示特性的默认重建行为也发生变化，Android 13 上的假设不能直接外推到 Android 17。

1. **配置仍会更新**：系统应用新 Configuration 并调用 onConfigurationChanged。Activity 的资源访问会反映新配置，但应用自有的缓存尺寸、View 状态和嵌入组件仍需刷新。
2. **声明范围有限**：只声明明确接管的变化位，其他变化仍可能重建。默认重建会重新选择 layout-land、values-night 等资源，手动接管则由应用承担相应刷新和状态保持责任。
3. **Android 13 边界**：AAOS 13 对应的 manifest 定义允许 recreateOnConfigChanges 指定 mcc、mnc。省略该属性时，没有通过它额外要求这些变化执行重建，Activity 仍按该版本默认配置变化规则处理。
4. **Android 17 边界**：API 37 对 keyboard、keyboardHidden、navigation、touchscreen、colorMode，以及 UI_MODE_TYPE_DESK 对应的 UI_MODE 变化调整了默认是否重建行为。这些变化默认只回调 onConfigurationChanged。应用若依赖重建来重新加载资源，可在 recreateOnConfigChanges 中列出相应值以要求完整重建。省略该属性时采用 Android 17 的新默认行为。该属性支持的取值随版本变化，不能在 Android 13 使用 Android 17 的语义假设。

**Q23: [learning] Resources 相关内存持续增长时，为什么“mResourceImpls 里的键变多”不能直接定性为泄漏？应该怎么取证？**

Android 13 的 mResourceImpls 以 ResourcesKey 映射到 ResourcesImpl 的弱引用。键或弱引用条目仍在映射里，不代表对象仍被强引用或原生资源仍泄漏。诊断要同时观察对象存活、Java 堆和原生内存，而不是只看映射条目数。

1. **确认趋势**：重复相同的配置变化与页面操作，等待 GC 后比较 PSS、Java 堆和原生堆是否持续增长。
2. **追踪 Java 引用**：对可调试进程抓取堆快照，沿 GC Root 检查存活的 Resources、Context 和旧 Activity 引用。静态持有 Activity Context 或长期缓存覆盖配置 Context 是常见风险。
3. **检查原生资源**：AssetManager 与资源表会占用原生内存，Java HPROF 不能覆盖全部原生成本，应结合原生堆分析工具。
4. **解释合理多实例**：多个显示、窗口、覆盖配置或资源加载器组合可以要求不同 ResourcesImpl。只有伴随无法释放的对象或持续内存增长证据，才能进一步判断泄漏。

**Q24: [learning] 把 PNG 直接放进 drawable 目录和用 <bitmap> 标签包装有什么区别？BitmapDrawable 的绘制属性各改变什么效果？**

直接放进资源目录的位图会被包装成默认配置的 BitmapDrawable。要调整抗锯齿、过滤、平铺这些绘制行为，必须用 `<bitmap>` 标签写 XML 显式包装，属性逐项决定绘制效果：

1. **android:src**：要显示的位图资源，缺省则没有可绘制内容。
2. **android:antialias**：对位图边缘做抗锯齿，斜线与曲线边缘更平滑，代价是轻微的清晰度损失。默认关闭。
3. **android:dither**：在颜色深度低于位图的屏幕上用抖动模拟中间色，渐变过渡更自然。默认关闭，面向低色深显示时建议开启。
4. **android:filter**：位图被缩放时启用双线性过滤，放大缩小过渡更平滑。会被拉伸显示的位图建议开启，默认关闭。
5. **android:gravity**：位图尺寸小于容器时的对齐与填充策略（如居中、整体填充）。默认按填充处理。
6. **android:tileMode**：平铺模式，默认 disabled 不平铺。clamp 拉伸边缘像素补满、repeat 重复平铺、mirror 镜像交替平铺。一旦设置平铺模式，gravity 的对齐策略即被取代。

这些属性都是绘制期行为，不改变原始位图数据。同一张位图可以在不同 Drawable 资源里用不同属性组合复用。

**Q25: [learning] 用 <shape> 标签声明的 Drawable 实际是什么类型？它的子标签之间有哪些约束？**

`<shape>` 标签在加载时实际生成的是 GradientDrawable 实例——这个类统一承载纯色、渐变、描边等几何外观，android:shape 属性（rectangle、oval、line、ring）决定几何轮廓。它的子标签不是自由组合，存在几条会改变结果或直接失效的约束：

1. **<solid> 与 <gradient> 互斥**：二者都描述填充方式，GradientDrawable 同一时刻只采用一种填充，同写时以实际生效的一方为准，不要指望叠加。
2. **<corners> 只对 rectangle 生效**：圆角半径描述的是矩形四角，oval 本身无直角，line 与 ring 也不适用，设置后不产生效果。
3. **<size> 只是固有尺寸建议**：它决定 Drawable 报告的 intrinsic 宽高，最终显示大小仍由使用它的 View 尺寸与缩放策略决定，不是强制边界。
4. **<stroke> 与 <padding>**：描边声明宽度与颜色。padding 声明内容留白，影响它作为容器或背景时内容的摆放。

判断规则：需要纯色或线性渐变的简单几何外观用 `<shape>`（即 GradientDrawable）即可。渐变形式之外的复杂效果（多层、状态切换）应改用 layer-list、selector 等组合方式，而不是继续往 shape 上堆属性。
