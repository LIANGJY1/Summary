# AppCompat 装配

> androidx.appcompat 装配机制学习资料（1.x 系源码锚点，AndroidLibs/androidx HEAD f38a5e5）。边界：本文回答“AppCompatActivity 的三层装配与 setContentView 全链、XML 控件调包与 tint 管线、夜间模式生效链、Toolbar/menu 体系”；主题资源属性链与 RRO 归 [03-资源与主题.md](03-资源与主题.md)，主题还原缺陷案例归 [08-界面还原与主题缺陷.md](08-界面还原与主题缺陷.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 继承 AppCompatActivity 之后，setContentView 的实际执行路径与裸 Activity 有什么不同？**

AppCompatActivity 把窗口装饰、控件膨胀与着色全部委托给 AppCompatDelegateImpl：setContentView 先由 delegate 装出一层可定制的 subDecor，把框架 decor 里的 android.R.id.content 掉包成 subDecor 自己的内容容器，之后应用布局实际挂在这层容器里——这就是“继承即获得全套装配”的机制含义。

setContentView 全链六步：

1. **主题翻标志**：读取主题属性决定 subDecor 形态（是否带 ActionBar、是否窗口浮层）。
2. **框架 decor 先装**：调用 PhoneWindow 的标准安装流程生成框架 DecorView。
3. **膨胀模板**：按形态膨胀 abc_screen_toolbar 或 abc_screen_simple 等 AppCompat 屏幕模板。
4. **content 掉包**：把框架 decor 的 android.R.id.content 替换为 subDecor 的内容容器——应用 setContentView 的目标 View 树从这一步起挂在 AppCompat 的容器里。
5. **挂窗**：把 subDecor 加入框架 decor。
6. **补边**：处理状态栏与内容边距（applyFixedSizeWindow/updateStatusGuard 路径）。

两条时序边界由此而来：requestWindowFeature 必须在 setContentView 之前调用（subDecor 装好后再改窗口特征直接抛异常）；AppCompatDialog 的面板延迟装配使 setButton/setView 在 show() 后失效，但 setTitle/setMessage 直改 view 仍生效。

依据：AndroidLibs/androidx（appcompat 1.x，HEAD f38a5e5）AppCompatDelegateImpl 类头与沉淀文档 §1。

**Q2: [learning] XML 里写的 TextView，运行时为什么变成 AppCompatTextView？调包在什么时机发生、有没有漏网情况？**

调包发生在 View 膨胀期：AppCompatViewInflater.createView 对高频控件（TextView、ImageView、Button 等）用 switch 硬编码直造对应 AppCompat 壳，长尾控件走反射兜底，并预留 viewInflaterClass 扩展点整体替换——XML 标签名不变，实例类型在 inflate 返回前被替换。

装配细节与例外：

1. **switch 未命中也要自造**：即使没有 AppCompat 壳，inflate 也经由 inflater 统一出口创建，保证 android:theme 指定的子主题不丢失——直接放行会丢主题上下文。
2. **控件壳两段式**：以 AppCompatButton 为样板，构造做三件事——super 构造前用 TintContextWrapper.wrap 包壳、helper 装载兼容属性、着色交 ResourceManagerInternal；“super 先干原活、helper 补兼容”是家族共性。
3. **家族例外**：ToggleButton 不包壳；AppCompatImageView 的 setImageResource 整个劫持不调 super（要经过自己的资源解析路径）。
4. **能力搬运的意义**：壳让老平台获得 tint、矢量图、auto-size、emoji 等新版本能力；反之排查“XML 写了属性没生效”时，先确认实际实例类型是不是 AppCompat 壳——被人手动 new 出来的控件不经过调包，壳能力不存在。

依据：AndroidLibs/androidx 的 AppCompatViewInflater、AppCompatButton 与沉淀文档 §2。

**Q3: [learning] setDefaultNightMode 后界面是怎么更新的？为什么有时原地刷新、有时整 Activity 重建？**

AppCompatDelegate.setDefaultNightMode 经静态注册表广播到每个活实例，各实例走 applyApplicationSpecificConfig 生效链：能原地刷新资源就回调 onConfigurationChanged，接不住才 recreate。是否重建由“重建裁决双账本”对账决定，不是简单的开关。

机制要点：

1. **静态到实例的分发**：sActivityDelegates 是活实例的弱引用注册表，静态设置经它中转——已销毁的实例自动失效，不会泄漏。
2. **双账本裁决**：mEffectiveConfiguration 快照与合成的新配置逐项对账，并结合 manifest 的 configChanges 声明判断：应用声明接得住的变更走 onConfigurationChanged 原地刷资源，接不住的（如 uiMode 未声明）走 recreate。
3. **防循环两道闸**：allowReconfiguration=false 与 locales 冻结——夜间模式与语言设置的联动刷新不会互相触发无限重建。
4. **排查落点**：夜间切换后“有的页面重建、有的原地刷”通常不是 bug，而是各页面 manifest 声明与视图状态差异的对账结果；要统一行为先统一 configChanges 声明，再查是否持有按旧 Configuration 记忆的状态。

依据：AndroidLibs/androidx 的 AppCompatDelegateImpl（applyApplicationSpecificConfig、sActivityDelegates）与沉淀文档 §1。

**Q4: [learning] 着色（tint）是怎么统一收口的？“null”返回值代表没找到吗？**

着色走 ResourceManagerInternal（机制层）加 AppCompatDrawableManager（策略层）两级：机制层负责 drawable 的三级加载回退、着色执行与按主题分桶缓存；策略层是“哪类控件染什么色”的决策表，两者分离使策略表可整体替换。调色查询返回 null 的语义是“不归我管”（交给调用方原样使用），不是“没找到”——混淆这两者会在自定义策略里错误地兜底。

机制与策略分工：

1. **机制层三级回退**：先查缓存，未命中按主题加载原始 drawable，再按控件类型应用 tintList；缓存按主题分桶，日夜两套配置互不污染。
2. **策略层 Hooks**：AppCompatDrawableManager 声明各控件与资源对的 tintList 映射；覆盖默认策略时继承并替换，而不是散落在业务代码里逐个 setColorFilter。
3. **排查落点**：图标颜色异常时按“实际实例是否为壳 → 该控件是否在策略表 → 缓存桶是否对应当前主题”三级查，第三级专门针对夜间切换后残留日间配色的场景。

依据：AndroidLibs/androidx 的 ResourceManagerInternal、AppCompatDrawableManager 与沉淀文档 §2。

**Q5: [learning] Toolbar 与传统 ActionBar 是什么关系？menu 的展示决策在哪里发生？**

Toolbar 是 ActionBar 的布局级替身：自绘容器（导航图标、logo、标题、自定义 view 与菜单按钮全部自己 onMeasure/onLayout），setSupportActionBar 时经 ToolbarWidgetWrapper 换装成 DecorToolbar 接口；窗口 ActionBar 路径（WindowDecorActionBar）与 Toolbar 路径（ToolbarActionBar）互斥装配，一次只有一套生效。

menu 体系三层与决策点：

1. **三层架构**：模型层（MenuBuilder/MenuItemImpl）→ 呈现层（MenuPresenter/MenuView，覆盖按钮、列表、弹出窗、对话框形态）→ 回调层（宿主 delegate 的 preparePanel 先备后开）。checkable 组状态以 MenuBuilder 为唯一裁决点。
2. **showAsAction 的决议本体**：ActionMenuPresenter.flagActionItems 按可用宽度决定按钮配额，ActionMenuView.onMeasure 宽度一变即触发重新分箱——“为什么我的菜单按钮时有时无”要按容器实际宽度查，不是按 item 数量。
3. **溢出形态**：按屏幕最小边 ≥720dp 选择级联或标准弹出。
4. **共存实现**：ToolbarActionBar 用“占位 View 骗过框架 preparePanel”让两套 ActionBar 机制在同一窗口体系下共存。

依据：AndroidLibs/androidx 的 Toolbar、MenuBuilder、ActionMenuPresenter 与沉淀文档 §3、§4。
