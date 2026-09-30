# 窗口系统与 WindowManagerService

> 学习资料（文章模式沉淀）。主线：一个窗口的三副面孔、addWindow 与 token 契约、relayout 与遍历调度的分工、surface 层级与 z 序、Insets 体系与系统栏控制、Configuration 变更的两条路径、IME 适配、多窗口边界、窗口不显示的排查顺序。AOSP 机制按本地 AAOS13 源码（Android 13）核对（`frameworks/base/services/core/java/com/android/server/wm/`、`core/java/android/view/`），行为演进按官方文档口径（2026-09 检索）。合成与帧调度见 [../02-rendering/01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md)，显示服务与多窗口形态见 [../02-rendering/03-多窗口折叠屏与显示服务.md](../02-rendering/03-多窗口折叠屏与显示服务.md)，应用侧时序见 [01-activity.md](./01-activity.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 界面内容、位置或合成结果异常时，应用窗口分别由哪些层维护？**

一个窗口在三处同时存在，任何一处出问题都可能表现为"界面不对"：

1. **应用侧 ViewRootImpl**：拥有 View 树，负责 measure/layout/draw，并通过 IWindowSession 与系统通信（ViewRootImpl.java 核对）。
2. **系统侧 WindowState**：WindowManagerService 为它维护的位置、大小、可见性、层级、输入通道与 insets 快照（WindowManagerService.java 核对）。
3. **合成侧 Surface**：SurfaceFlinger 按层级合成这些 surface，WMS 只管"放在哪、画多大"，不管"画什么"。

排查必须跨进程，因为三者由不同线程和不同进程持有：View 树在应用主线程，WindowState 在 system_server 的 WMS 锁内，合成在 SurfaceFlinger 与 RenderThread。判断归属的顺序是"内容对不对 → 位置大小对不对 → 有没有被合进去"，分别对应 draw、relayout、surface placement 三段（合成阶段负责把各 layer 组合成最终画面）。

**Q2: WindowManager.addView 抛 BadTokenException，缺失的 token 代表什么？**

返回 ADD_BAD_APP_TOKEN，窗口根本不会建立（WindowManagerService.java addWindow 核对）。token 是窗口的"归属证明"：应用窗口必须挂在 AMS 预先创建的 WindowToken 之下，弹窗要挂在宿主 Activity 的 token 之下，子窗口要挂在父窗口的 token 之下；无特权应用连自己造 token 都不允许（unprivilegedAppCanCreateTokenWith 判定核对）。它防住的是"进程被拉起但界面早于合法宿主出现"这类竞态：token 校验保证窗口的生命周期不会超出它的宿主，因此 addWindow 内部已经按顺序挡掉了子窗口 token 不是窗口、token 指向的父窗口本身是子窗口、TYPE_PRIVATE_PRESENTATION 加在非私密显示上、presentation 窗口加在不合适的显示上等组合。

**Q3: 悬浮窗 addView 返回失败码时，怎样沿 addWindow 校验顺序定位？**

顺序固定为"权限 → 显示 → 重复 → token → 类型 → 多用户 → 建状态"（WindowManagerService.java addWindow 核对），返回码定义在 WindowManagerGlobal，对照定位：

1. ADD_PERMISSION_DENIED：mPolicy.checkAddPermission 按窗口类型判权限没通过，或私密/演示窗口加到了不允许的显示上。
2. ADD_INVALID_DISPLAY：显示不存在，或应用对目标显示无访问权（displayContent.hasAccess）。
3. ADD_DUPLICATE_ADD：同一个 IWindow 已经在 mWindowMap 里。
4. ADD_BAD_SUBWINDOW_TOKEN：子窗口的 token 找不到窗口，或 token 指向的也是子窗口。
5. ADD_BAD_APP_TOKEN / ADD_NOT_APP_TOKEN：应用窗口没有合法 token，或 token 不是应用 token。
6. ADD_INVALID_TYPE：窗口类型非法（PhoneWindowManager.getWindowTypeFromAttributes 解析不出合法 type）。
7. ADD_INVALID_USER：请求的 user 与调用 uid 不符且系统拒绝切换。

用返回码定位比读日志快：先看是权限类、显示类还是 token 类，再回到对应检查点。

**Q4: 窗口 relayout 后界面没有立即重绘，relayoutWindow 返回什么、遍历何时执行？**

它返回的是"新的窗口几何与状态"，不是"画好的结果"：更新后的 Configuration、窗口在显示中的 frame、insets 状态，以及一组结果标志（RELAYOUT_RES_FIRST_TIME、RELAYOUT_RES_SURFACE_CHANGED、RELAYOUT_RES_SURFACE_RESIZED、RELAYOUT_RES_CANCEL_AND_REDRAW、RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS，WindowManagerGlobal 核对）。应用侧收到后只是记下"需要重新测量和绘制"，真正的 performMeasure / performLayout / performDraw 要等下一帧的 traversal 回调（ViewRootImpl.java relayoutWindow 与 scheduleTraversals 核对）。

分工的原因是帧对齐：如果在 relayout 里同步画完，UI 更新时刻就取决于这次 Binder 调用何时到达，而 Binder 返回时刻与 VSync 无关，一帧的预算（时间 + 像素）就被拆散了。排到 Choreographer 的 traversal 回调后，"内容算完"与"合成送显"才有统一的帧边界，卡顿度量也才有单一归因点（遍历由帧调度机制安排，以便与屏幕刷新节奏协调）。RELAYOUT_RES_CANCEL_AND_REDRAW 是这条规则的例外：系统明确要求应用放弃本帧重绘。

**Q5: 修改状态栏或返回键行为时，为什么要找 WindowManagerPolicy 而非 server/wm？**

因为它们不是窗口容器的一部分，而是"这台设备的产品策略"。WindowManagerPolicy 定义按键、显示、导航栏等策略接口，PhoneWindowManager 是手机/车机形态的实现，路径在 `frameworks/base/services/core/java/com/android/server/policy/`（本地 AAOS13 源码核对）。这个拆分决定了改导航栏形态、改返回键行为要动 policy 包，而改窗口层级、insets 计算只动 wm 包；两者共用 WindowManagerService 的锁但职责不重叠，checkAddPermission 也是经 policy 判的（addWindow 核对）。

**Q6: 窗口 token 合法但显示层级或可见性异常，WindowState、WindowToken、DisplayContent 各管什么？**

1. **WindowToken** 是"归属与合法性"的载体，一个宿主（Activity、应用、或由 AMS 创建的抽象 token）持有一个 token，它下面可以挂多个窗口；token 失效时其下所有窗口一起被清理。
2. **WindowState** 是单个窗口在 WMS 里的状态机记录：位置、大小、层级、输入通道、insets、是否可见、是否正在退出。它被放进 DisplayContent 的层级树里排序。
3. **DisplayContent** 是一块显示的汇聚点：它持有该显示上的窗口容器树、insets 计算（InsetsPolicy、InsetsStateController）、输入分发目标与 z 序裁决。

所以 token 回答"这个窗口有没有合法主人"，WindowState 回答"这个窗口现在什么状态"，DisplayContent 回答"这个窗口排在第几、被挡在哪"。同一个 Activity 的窗口换到另一块显示，是 WindowState 换 DisplayContent，token 不变。

**Q7: 普通 View 调整 z 序无效但 SurfaceView 可以置顶，窗口与 View 的层级由谁决定？**

三层输入决定顺序：窗口类型 LayoutParams.type（应用窗口、悬浮窗、系统提示等基类类型）、同类型内的创建与位置次序、以及 subLayer 与 windowLayer 这类层级参数。WMS 侧由 WindowSurfacePlacer.performSurfacePlacement() 统一重排，它与 RootWindowContainer.performSurfacePlacement()（本地 AAOS13 源码核对）分别承担层级树自上而下的遍历与最终计算。

setZOrderOnTop 只对 SurfaceView 有意义，因为 SurfaceView 有独立的 surface，合成顺序由它与父窗口 surface 的相对位置决定；普通 View 的绘制顺序被 dispatchDraw 固定为同层先序遍历，View 本身无法把自己提到兄弟 View 之上（View.java dispatchDraw 核对；子 View 顺序由父容器管理）。要让普通 View 盖住兄弟，只能调整它在父容器里的子 View 次序，或者给父容器加前景层。

**Q8: 系统栏或输入法遮挡内容时，Insets 从哪些来源计算并传给应用？**

按"来源"而不是"位置"分类，典型类型有状态栏、导航栏、刘海/挖孔（displayCutout）、输入法（ime）、强制手势区、可点击手势区、caption 等（WMS 侧类型常量在 InsetsState/InsetsStateController，本地核对）。计算链路是：系统 UI 窗口在自己的 relayout 中声明自己占哪些区域，WMS 汇总成该显示的 insets 状态，再按目标窗口的类型与 flags 过滤（例如悬浮窗可以穿透状态栏、可聚焦与否影响 ime 是否上送），结果随 relayout 回传给应用，应用侧表现为 WindowInsets 对象（WindowManagerService.relayoutWindow 的 insets 出参与 WindowManagerGlobal.RELAYOUT_INSETS_PENDING 核对）。

InsetsStateController 用 WindowContainerInsetsSourceProvider 表按类型存各类来源，ime 有独立的 ImeInsetsSourceProvider（本地源码核对）——输入法动画时每帧都在改几何，这就是输入法弹出容易抖的根因。

**Q9: 沉浸式页面用 setSystemUiVisibility 后行为不稳定，WindowInsetsController 多了什么能力？**

因为旧接口用"视图可见性位"表达意图，系统无法区分"我想让状态栏出现"和"我隐藏了带状态栏背景的视图"，于是只能粗暴地按窗口类型同步一批兄弟窗口。WindowInsetsController 把这件事显式化：它按 API level 分发到平台实现（WindowInsetsController 与 WindowInsetsControllerImpl），提供按类型 show/hide、按类型读取当前可见性、按类型设置外观，以及动画控制（show(Type, Duration)），并把结果广播给所有可见窗口。工程上还有一个直接好处：沉浸式页面在旧接口下要反复对抗系统重新显示状态栏，新接口的语义是"我请求隐藏，系统决定是否可行"，页面状态与实际状态可以一致地读回来。

**Q10: 调用 hide 后内容没有马上扩展，系统栏 Insets 为什么要等后续帧变化？**

因为系统栏的隐藏先落到 InsetsSource 的可见性上，再由 WMS 标记该显示 insets 已变，应用在下一帧 traversal 里读到的仍是上一帧的 insets 快照，只有当系统栏真正开始动画、每帧上报新 insets 时，应用的 padding 才跟着逐帧变化。实践含义是：不要在 onApplyWindowInsets 里做一次性状态赋值就当作最终态，也不要用 insets 值参与不可逆操作（发请求、埋点）；如果确实需要等稳定，用 ViewCompat.setOnApplyWindowInsetsListener 配合一次 post 或动画结束回调收敛。

**Q11: 切换配置后页面有时回调、有时重建，Activity 的分界条件是什么？**

先看应用在清单里声明的 android:configChanges 覆盖了哪些变化位（Activity 文档口径）。声明了的那几项由系统直接回调 onConfigurationChanged，应用自己负责适配；没声明的项走重建路径，应用侧表现为 ActivityThread.handleRelaunchActivity → handleRelaunchActivityInner（本地 AAOS13 源码核对），内部仍然会走销毁与重建，只是复用进程与已创建的组件。因此"有没有调 onConfigurationChanged"不是可靠判据，可靠判据是清单里那一项。

窗口与配置更新不只有"重建 Activity"这一种形态：同一 Activity 实例内的窗口参数刷新与资源重载，与实例销毁重建，是两套路径，详见 [01-activity.md](./01-activity.md)。

**Q12: 分屏或折叠改变窗口大小但没触发 Configuration，应用为何仍会重新布局？**

因为窗口尺寸不是 Configuration 的函数，而是 relayout 的直接返回值。分屏条拖动、折叠屏展开、自由窗口缩放都只改窗口在显示中的 frame，Configuration 可以完全不变；应用侧靠 relayout 的返回值发现 frame 变了，于是重跑 measure/layout（ViewRootImpl.java relayoutWindow 核对）。这解释了一个常见误判：只看 onConfigurationChanged 的应用在多窗口场景下不会收到任何回调，布局却必须正确，所以适配决策应基于"我这次拿到的窗口有多大"，而不是"配置变没变"（资源限定符选择与窗口尺寸属于不同的适配依据）。

**Q13: 输入法弹出后页面缩放卡顿或被平移，adjustResize、adjustPan、adjustNothing 如何影响窗口？**

三者定义输入法出现时窗口的行为：adjustResize 把窗口内容区缩小（内容区被 ime insets 吃掉），adjustPan 保持窗口大小、只平移保证输入框可见，adjustNothing 什么都不做。选 resize 会在 ime 出现/消失时触发整棵 View 树的重新测量，加上软键盘本身高度会随内容变化（中文拼音候选、输入框增高），一次弹出常常是多次重排；这正是"输入框一聚焦就卡"的机制来源。车机与平板这类大屏更适合 resize 但必须保证布局轻量，否则输入过程容易把主线程拖进 ANR 区间（ANR 契约见 [ANR](../07-performance/03-ANR.md)）。

**Q14: 多窗口中的 Activity 看起来小于任务区域，ActivityRecord 边界与 letterbox 分别表示什么？**

ActivityRecord 描述的是"这个 Activity 属于哪个任务、在哪个显示上、期望多大"，是任务管理的边界；真实窗口边界由 WMS 按显示、分屏模式与窗口属性计算。两者的差值在两种情况下不为零：freeform 模式下 Activity 可以小于任务槽位，split-screen 下多个任务共享一块显示（自由窗口与分屏会改变窗口相对显示区域的尺寸）。letterbox 是 Android 12 起系统给"宽度不足以按应用声明的最小尺寸展示"的窗口加的黑边或信箱边，用来保证 UI 不被拉伸变形；它不是应用的布局错误，出现 letterbox 意味着窗口尺寸低于应用声明的最小值，属于分辨率、显示区域或 maxAspectRatio/minAspectRatio 一类约束在起作用（官方文档口径）。

**Q15: 窗口不显示或黑屏时，怎样依次检查窗口记录、frame 与 SurfaceFlinger layer？**

按"系统认不认这个窗口 → 窗口有多大 → 有没有被合进去"三段推进：

1. `dumpsys window windows`：看目标窗口是否在列表里、mViewVisibility、isOnScreen、frame、是否有 mHasSurface。列表里没有 → addWindow 就没成功，去看应用侧 BadTokenException 或 relayout 返回码；列表里有但 isOnScreen 为假 → 可见性或焦点/输入问题。
2. `dumpsys window displays`：看这块显示上 insets、默认显示策略、以及被遮挡的窗口关系，确认不是被别的系统窗口盖住。
3. `dumpsys SurfaceFlinger --list` 与 `dumpsys SurfaceFlinger`：确认对应 layer 是否存在、大小是否为 0、是否被裁剪。View 侧存在但 layer 缺失或尺寸为 0，指向首帧前就被移除或窗口尺寸为 0（透明主题下也可能出现窗口已存在但首帧内容尚未提交的情况）。

**Q16: 窗口添加失败与 Surface 丢失都表现为空白，应用侧症状和恢复责任有什么不同？**

两种症状不同。relayout 失败时应用侧抛的是运行时异常或拿到异常返回码，窗口压根不建立，典型是 BadTokenException（WindowManager.BadTokenException 存在于 WindowManager.java，本地核对），原因是宿主已经销毁而异步添加窗口仍在执行——对策是把添加动作与宿主生命周期绑定，或在宿主销毁时移除。

Surface 丢失（进程被系统回收后恢复、显示热插拔、窗口被回收再恢复）时，应用看到的是窗口还在但内容消失、随后在新 surface 上重新走一遍首帧。此时框架侧的 SurfaceHolder 回调与 relayout 返回值已经把重绘安排好了，应用需要保证的是"重建后状态还在"：渲染状态放在 onSaveInstanceState 之外的数据层，而不是留在一批已经失效的 View 上，否则用户看到的是一个空窗口而不是恢复的界面。

**Q17: 键盘动画结束后页面才跳动，如何用 WindowInsetsAnimation 同步布局？**

insets 变化默认是"动画结束后一次性回调"，应用据此重排就会与键盘动画脱节——键盘自己在动、界面却等它到位才跳变。WindowInsetsAnimation（平台 API 30 起，低版本经 androidx compat）把 insets 变化改成随动画逐帧分发：给 setOnApplyWindowInsetsListener 的监听器附带动画回调，在每帧回调里按动画分数同步平移内容或调整让位高度，实现界面与键盘同一节奏移动（官方文档口径）。更主动的形态是 WindowInsetsAnimationControlListener：应用可以自己接管 insets 的动画进度（例如手势拖拽关闭键盘）。车机场景对应"搜索框聚焦时键盘上移、内容区同步压缩"的体验要求。边界：动画回调只在存在 insets 动画时触发，老系统无动画时退回一次性回调，两套路径都要处理。
