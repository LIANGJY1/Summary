# 窗口系统与 WindowManagerService

> 学习资料（文章模式沉淀）。主线：一个窗口的三副面孔、addWindow 与 token 契约、relayout 与遍历调度的分工、surface 层级与 z 序、Insets 体系与系统栏控制、Configuration 变更的两条路径、IME 适配、多窗口边界、窗口不显示的排查顺序。AOSP 机制按本地 AAOS13 源码（Android 13）核对（`frameworks/base/services/core/java/com/android/server/wm/`、`core/java/android/view/`），行为演进按官方文档口径（2026-09 检索）。合成与帧调度见 [../05-rendering/01-render-pipeline-vsync.md](../05-rendering/01-render-pipeline-vsync.md)，显示服务与多窗口形态见 [../05-rendering/03-multi-window-foldable.md](../05-rendering/03-multi-window-foldable.md)，应用侧时序见 [01-activity.md](01-activity.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 界面内容、位置或合成结果异常时，应用窗口分别由哪些层维护？**

一个窗口在三处同时存在，任何一处出问题都可能表现为"界面不对"：

1. **应用侧 ViewRootImpl**：拥有 View 树，负责 measure/layout/draw，并通过 IWindowSession 与系统通信（ViewRootImpl.java 核对）。
2. **系统侧 WindowState**：WindowManagerService 为它维护的位置、大小、可见性、层级、输入通道与 insets 快照（WindowManagerService.java 核对）。
3. **合成侧 Surface**：SurfaceFlinger 按层级合成这些 surface，WMS 只管"放在哪、画多大"，不管"画什么"。

排查必须跨进程，因为三者由不同线程和不同进程持有：View 树在应用主线程，WindowState 在 system_server 的 WMS 锁内，合成在 SurfaceFlinger 与 RenderThread。判断归属的顺序是"内容对不对 → 位置大小对不对 → 有没有被合进去"，分别对应 draw、relayout、surface placement 三段（合成阶段负责把各 layer 组合成最终画面）。

**Q2: WindowManager.addView 抛 BadTokenException，缺失的 token 代表什么？**

窗口 token 是系统验证窗口归属与生命周期的凭据。应用窗口、Activity 弹窗和子窗口分别要关联系统认可的应用 token、宿主 token 和父窗口 token。普通应用不能任意创建应用 token。

1. **应用窗口**：token 必须对应有效的应用窗口容器，否则 addWindow 返回 BAD_APP_TOKEN 一类错误，窗口不会建立。
2. **Activity 弹窗**：应使用宿主 Activity 的窗口上下文或 token，宿主销毁后不能再异步添加依赖该 token 的窗口。
3. **子窗口**：token 必须指向一个有效的父 WindowState，不能指向不存在的窗口或另一个子窗口。
4. **显示类型约束**：私密 Presentation 等窗口还要满足目标 Display 的私密性与访问权限要求。

AAOS 13 的 addWindow 还会拒绝“子窗口 token 不是窗口”“token 所指父窗口本身是子窗口”等组合。无特权应用不能通过 unprivilegedAppCanCreateTokenWith 自行创建应用 token。校验可避免进程先启动、界面却早于合法宿主出现的竞态。

这些检查阻止没有合法宿主的窗口绕过任务与显示生命周期。排查时应先确认 Context/token 来源和宿主生命周期，再看目标 Display 与窗口类型。

**Q3: 悬浮窗 addView 返回失败码时，怎样沿 addWindow 校验顺序定位？**

AAOS 13 的 addWindow 按权限、Display、重复窗口、token、类型与用户等条件检查，返回码能把失败定位到对应阶段：

1. ADD_PERMISSION_DENIED：mPolicy.checkAddPermission 按窗口类型判权限没通过，或私密/演示窗口加到了不允许的显示上。
2. ADD_INVALID_DISPLAY：显示不存在，或应用对目标显示无访问权（displayContent.hasAccess）。
3. ADD_DUPLICATE_ADD：同一个 IWindow 已经在 mWindowMap 里。
4. ADD_BAD_SUBWINDOW_TOKEN：子窗口的 token 找不到窗口，或 token 指向的也是子窗口。
5. ADD_BAD_APP_TOKEN / ADD_NOT_APP_TOKEN：应用窗口没有合法 token，或 token 不是应用 token。
6. ADD_INVALID_TYPE：窗口类型非法（PhoneWindowManager.getWindowTypeFromAttributes 解析不出合法 type）。
7. ADD_INVALID_USER：请求的 user 与调用 uid 不符且系统拒绝切换。

1. **权限或显示策略**：检查窗口类型、悬浮窗权限、目标 Display 是否允许该类型。
2. **显示访问**：确认 Display 存在且调用方有访问权。
3. **重复添加**：确认相同 IWindow 尚未在窗口映射中。
4. **token 关系**：核实应用 token 或父窗口 token 类型正确且仍有效。
5. **类型与用户**：检查窗口类型是否合法，以及请求 user 是否与调用 UID 相符。

错误码定义和分支会随平台实现变化。以目标版本 WindowManagerGlobal 与 WMS.addWindow 的配对分支为准，先按失败阶段缩小范围，再查完整日志。

**Q4: 窗口 relayout 后界面没有立即重绘，relayoutWindow 返回什么、遍历何时执行？**

relayoutWindow 返回窗口几何、Configuration、Insets 和 Surface 状态，不会同步画出应用内容。ViewRootImpl 根据返回结果更新窗口状态，并在需要时将 measure、layout、draw 安排到后续 traversal。

1. **返回信息**：包括窗口 frame、更新的 Configuration/Insets，以及 RELAYOUT_RES_FIRST_TIME、RELAYOUT_RES_SURFACE_CHANGED、RELAYOUT_RES_SURFACE_RESIZED、RELAYOUT_RES_CANCEL_AND_REDRAW、RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS 等结果标志。
2. **绘制时机**：scheduleTraversals 把遍历投递给 Choreographer，真正的测量、布局和绘制在后续帧回调执行。
3. **帧对齐原因**：Binder 返回时刻不保证与 VSync 对齐。把绘制排进帧调度，系统才能协调应用内容更新与合成节奏。
4. **例外标志**：RELAYOUT_RES_CANCEL_AND_REDRAW 要求应用放弃本次原绘制并按新窗口状态重绘。

**Q5: 修改状态栏或返回键行为时，为什么要找 WindowManagerPolicy 而非 server/wm？**

WindowManagerPolicy 是设备窗口行为策略接口，负责按键、系统栏和窗口准入等产品策略。WindowManagerService 则维护窗口容器、层级与布局状态。修改返回键或导航栏策略应追踪 policy 实现，修改窗口 frame、层级或 Insets 计算则应追踪 wm 实现。

AAOS 13 中，PhoneWindowManager 是对应形态的策略实现，位于 frameworks/base/services/core/java/com/android/server/policy。WMS 的 addWindow 会调用策略检查窗口类型权限，因此排查权限失败时也要查看策略层。WMS 与 ATMS 共享 WindowManagerGlobalLock，执行路径有关联但职责不同。

**Q6: 窗口 token 合法但显示层级或可见性异常，WindowState、WindowToken、DisplayContent 各管什么？**

1. **WindowToken**：表达窗口归属和生命周期分组，token 下可有多个窗口。token 移除会触发关联窗口清理。
2. **WindowState**：记录单个窗口在 WMS 中的位置、大小、层级、输入通道、Insets、可见性和退出状态。
3. **DisplayContent**：承载单个 Display 的窗口容器树，并参与 Insets、输入目标与 z 序管理。

所以 token 回答"这个窗口有没有合法主人"，WindowState 回答"这个窗口现在什么状态"，DisplayContent 回答"这个窗口排在第几、被挡在哪"。同一个 Activity 的窗口换到另一块显示，是 WindowState 换 DisplayContent，token 不变。

**Q7: 普通 View 调整 z 序无效但 SurfaceView 可以置顶，窗口与 View 的层级由谁决定？**

窗口层级由 WMS 按窗口类型、容器顺序和层级策略排序。普通 View 的兄弟顺序由父 ViewGroup 绘制，SurfaceView 则有独立 Surface，可由合成层调整相对父窗口的位置。

1. **窗口之间**：LayoutParams.type、同类容器顺序及 subLayer/windowLayer 等层级参数共同影响顺序。AAOS 13 的 WindowSurfacePlacer.performSurfacePlacement 与 RootWindowContainer.performSurfacePlacement 负责遍历和计算。
2. **普通 View 之间**：ViewGroup 的 dispatchDraw 决定子 View 绘制顺序。需要改变时由父容器调整子 View 次序或绘制前景，普通 View 自己不能越过父容器排序盖住兄弟。
3. **SurfaceView 与宿主窗口之间**：setZOrderOnTop 改变独立 Surface 相对于宿主窗口 Surface 的合成层级，不会改变普通 View 的兄弟顺序。

**Q8: 系统栏或输入法遮挡内容时，Insets 从哪些来源计算并传给应用？**

Insets 由系统窗口和显示几何等来源产生，WMS 汇总各来源后按目标窗口的策略筛选，并把结果交给应用。来源分类比边缘位置更重要，因为相同边缘可能同时有状态栏、手势区或输入法等不同 Insets 类型。

1. **系统栏与显示开孔**：状态栏、导航栏和 displayCutout 等类型来自系统窗口或显示几何。
2. **输入法与手势**：ime、强制手势区、可点击手势区等各有独立类型和可见性语义。
3. **计算与传递**：InsetsStateController 汇总后，WMS 按目标窗口类型和 flags 过滤 InsetsState，在 relayout 或 Insets 更新通道中交给应用，应用侧通过 WindowInsets 读取。悬浮窗 flags 和焦点策略会影响系统栏穿透与 IME Insets。
4. **输入法动画**：Android 13 的 InsetsStateController 通过 WindowContainerInsetsSourceProvider 按类型管理来源，IME 使用 ImeInsetsSourceProvider。动画过程中几何会逐帧变化，若应用只按最终 Insets 突然改布局就可能跳动。
5. **窗口过滤**：窗口类型与 flags 会改变可见来源和传递结果，例如非焦点窗口的 IME Insets 处理不同于可聚焦窗口。应同时检查目标窗口策略与 InsetsState。

**Q9: 沉浸式页面用 setSystemUiVisibility 后行为不稳定，WindowInsetsController 多了什么能力？**

旧的 systemUiVisibility 用 View 可见性位间接表达系统栏意图，状态和实际可见性容易分离。WindowInsetsController 把操作改为按 Insets 类型显式请求，并允许读取可见性和控制系统栏外观与动画。

1. **类型化显示控制**：通过 show(types) 与 hide(types) 指定要操作的 Insets 类型。
2. **状态读取**：通过 isVisible(types) 查询系统实际报告的可见性，区分请求状态与当前状态。
3. **外观与动画**：控制系统栏外观并协调 Insets 动画。平台 API 30 起提供该接口，旧系统可使用 AndroidX compat，但具体行为受系统策略约束。旧接口不能区分“请求显示系统栏”和“修改带系统栏背景的 View 可见性”，新接口表达的是请求，系统仍可按策略拒绝或调整。show(types, duration) 一类动画重载同样只是发起请求。
4. **实现边界**：框架按 API level 连接 WindowInsetsController 与平台/compat 实现，读回的可见状态才代表系统当前结果。

**Q10: 调用 hide 后内容没有马上扩展，系统栏 Insets 为什么要等后续帧变化？**

隐藏系统栏是一个状态与动画过程，不保证调用 hide() 后窗口边界立即同步扩大。应用应按收到的 Insets 更新布局，并在动画期间使用 WindowInsetsAnimation 逐帧协调内容移动。

1. **请求与结果**：hide() 表达隐藏请求，实际 Insets 可见性由系统策略和动画决定。
2. **布局更新**：监听器收到新 Insets 后更新受影响的 padding 或边界，不要把一次回调误当成动画最终态。
3. **动画同步**：需要跟随系统栏动画时使用 WindowInsetsAnimation 回调。若只需最终状态，应等待动画结束或下一次稳定 Insets 分发后再执行依赖最终几何的操作。
4. **副作用边界**：不要把可能逐帧变化的 Insets 用于不可逆业务操作，例如重复发请求或重复埋点。只在状态稳定或动画结束时执行一次。
5. **监听入口**：View 系统可通过 ViewCompat.setOnApplyWindowInsetsListener 读取 Insets。需要延后到布局稳定时，可 post 一次性工作或等动画结束回调，不要假设 listener 只回调一次。

**Q11: 切换配置后页面有时回调、有时重建，Activity 的分界条件是什么？**

是否重建取决于该 Activity 实际发生了哪些 Configuration 变化，以及 android:configChanges 是否覆盖这些变化位。已声明的变化由 Activity 接收 onConfigurationChanged。未声明的变化通常触发 Activity 实例销毁与重建。

1. **变化位匹配**：声明只覆盖明确列出的配置项，横竖屏等一次操作可能同时改变多项配置。
2. **热派发责任**：接管后应用必须刷新依赖配置的资源缓存、自定义 View 和窗口相关状态。
3. **重建含义**：Activity 实例会重新创建，但通常不代表应用进程冷启动。应用仍需正确保存和恢复用户状态。
4. **判断证据**：检查清单声明、系统日志或 Perfetto 中的 Activity 生命周期与配置回调，不要仅凭窗口尺寸变化判断是否发生 Configuration 变更。
5. **AAOS 13 路径**：未接管变化的 Activity 在应用侧进入 ActivityThread.handleRelaunchActivity/handleRelaunchActivityInner 一类 relaunch 路径，内部销毁并重建实例，但通常复用进程。

**Q12: 分屏或折叠改变窗口大小但没触发 Configuration，应用为何仍会重新布局？**

窗口 frame 与 Configuration 是不同输入。窗口大小变化会通过 relayout 返回给 ViewRootImpl，进而触发测量和布局。是否同时改变 Configuration 取决于系统及窗口模式。

1. **窗口几何**：分屏拖动、自由窗口缩放和折叠变化会改变窗口 frame，应用必须依据新边界重新布局。
2. **Configuration**：只有系统判定相关配置项改变时才派发配置更新，不能把 onConfigurationChanged 当作所有窗口尺寸变化的通知。
3. **适配依据**：离散资源档位可用资源限定符，连续窗口尺寸适配应使用当前窗口边界或窗口尺寸类。

**Q13: 输入法弹出后页面缩放卡顿或被平移，adjustResize、adjustPan、adjustNothing 如何影响窗口？**

windowSoftInputMode 的 adjustResize、adjustPan 与 adjustNothing 分别控制 IME 出现时窗口可用区域、窗口平移与应用布局责任。省略 adjust 部分时使用 adjustUnspecified，由系统根据窗口内容和主题等条件选择调整方式。

1. **adjustResize**：让应用可用区域反映 IME 遮挡，旧式窗口行为通常表现为内容区缩小。现代系统也通过 IME Insets 通知边界，应用应根据 Insets 更新布局。
2. **adjustPan**：窗口整体尺寸不按 IME 缩小，系统尝试平移窗口内容以保持焦点控件可见。
3. **adjustNothing**：系统不为 IME 调整窗口或自动平移，应用须自行处理遮挡。
4. **性能边界**：IME 动画和内容变化可能造成多次布局。应在目标 API、窗口模式和主题下实测，不能仅凭 adjustResize 就断定每次必然重测整棵 View 树。

**Q14: 多窗口中的 Activity 看起来小于任务区域，ActivityRecord 边界与 letterbox 分别表示什么？**

任务、Activity 和实际窗口有不同边界，不能把 ActivityRecord 的任务组织范围直接当成应用绘制 frame。多窗口会令任务区域、Activity 布局边界和显示区域出现差异。letterbox 则是系统为满足兼容性或窗口策略而在应用绘制区域周围留出的区域。

1. **窗口边界**：应用实际绘制区域由 WMS 按 Display、窗口模式、应用尺寸约束和系统策略计算。
2. **多窗口边界**：自由窗口和分屏改变任务及 Activity 可用区域，应用应检查实际 WindowMetrics 和 Insets。
3. **Letterbox**：系统可为不适配当前屏幕比例或尺寸约束的应用留边。应用的 minAspectRatio、maxAspectRatio 等声明和系统大屏兼容策略可能影响窗口形态。是否出现取决于目标版本，不能仅凭黑边断定布局代码出错或窗口低于某个固定最小值。

**Q15: 窗口不显示或黑屏时，怎样依次检查窗口记录、frame 与 SurfaceFlinger layer？**

按"系统认不认这个窗口 → 窗口有多大 → 有没有被合进去"三段推进：

1. `dumpsys window windows`：看目标窗口是否在列表里、mViewVisibility、isOnScreen、frame、是否有 mHasSurface。列表里没有 → addWindow 就没成功，去看应用侧 BadTokenException 或 relayout 返回码。列表里有但 isOnScreen 为假 → 可见性或焦点/输入问题。
2. `dumpsys window displays`：看这块显示上 insets、默认显示策略、以及被遮挡的窗口关系，确认不是被别的系统窗口盖住。
3. `dumpsys SurfaceFlinger --list` 与 `dumpsys SurfaceFlinger`：确认对应 layer 是否存在、大小是否为 0、是否被裁剪。View 侧存在但 layer 缺失或尺寸为 0，指向首帧前就被移除或窗口尺寸为 0（透明主题下也可能出现窗口已存在但首帧内容尚未提交的情况）。

**Q16: 窗口添加失败与 Surface 丢失都表现为空白，应用侧症状和恢复责任有什么不同？**

添加失败表示窗口没有建立。Surface 重建则表示窗口或宿主仍在，但原有绘制目标失效并需要在新 Surface 上恢复内容。两者的修复点不同。

1. **添加失败**：检查运行时异常或返回码、token 和宿主生命周期。宿主销毁后异步 addView 是典型原因，操作必须与宿主生命周期绑定，并在不再需要时移除。
2. **Surface 重建**：进程恢复、显示热插拔或窗口 Surface 回收后，响应 SurfaceHolder/窗口 Surface 生命周期回调，重新配置渲染器并提交新首帧。
3. **状态恢复**：不要把业务渲染状态只留在已销毁的 View 或旧 Surface。小型 UI 状态可由实例状态保存机制恢复，持续业务状态放在 ViewModel 或持久数据层中。

**Q17: 键盘动画结束后页面才跳动，如何用 WindowInsetsAnimation 同步布局？**

WindowInsetsAnimation 在 API 30 起提供 Insets 动画的逐帧进度回调，应用可据此让内容与系统栏或 IME 同步移动。低版本可通过 AndroidX compat 获取兼容行为。

1. **监听最终 Insets**：WindowInsets 回调提供当前边界和可见状态，负责更新布局基准。
2. **跟随动画进度**：实现 WindowInsetsAnimation.Callback，在 prepare、progress 和结束阶段协调内容位移或尺寸。
3. **主动控制动画**：需要手势驱动等控制权时使用 WindowInsetsAnimationController，并处理控制权未授予或动画取消的情况。
4. **兼容无动画路径**：动画回调只在系统分发动画时出现。仍需让最终 Insets 更新正确工作。WindowInsetsAnimationControlListener/Controller 只有在系统授予动画控制权后才能驱动进度，例如手势交互式关闭 IME。

**Q18: WMS.addWindow 返回成功后窗口就可见了吗？应用收到 BadTokenException 应该先查什么？**

addWindow 成功只说明逻辑窗口通过准入并加入 WMS，不表示窗口已经完成布局或首帧合成。显示还依赖后续 relayout、Surface 建立、应用绘制与合成提交。

1. **addWindow 阶段**：校验权限、Display、token 与类型，创建系统侧窗口状态和输入通道。
2. **relayout 阶段**：确定 frame、Insets 和 Surface 状态。窗口尚未提交首帧时仍可能不可见。
3. **显示阶段**：应用向 Surface 提交缓冲区，SurfaceFlinger 合成后才出现内容。
4. **异常定位**：BadTokenException 指向 addWindow 准入问题，先查窗口类型、Context/token、Activity 生命周期与目标 Display。不要先查 SurfaceFlinger。
5. **WMS 边界**：WMS 维护窗口/任务层级、焦点与可见性，并通过 SurfaceControl.Transaction 更新合成及输入系统状态。它不绘制应用 View，也不逐个消费触摸事件。

**Q19: WMS 的 android.display、android.anim、android.anim.lf 三条线程怎么分工？"线程分开了"为什么仍可能互相拖慢？**

AAOS 13 中三条线程分担显示消息、窗口布局/Surface placement 和 Surface 动画计算，但它们仍访问共享窗口状态，因此线程分开不代表没有锁竞争。

1. **android.display**：DisplayThread 处理 WMS 的 H 消息和一般异步状态任务。
2. **android.anim**：AnimationThread 执行 layout、surface placement 等影响窗口动画时序的任务。
3. **android.anim.lf**：SurfaceAnimationThread 执行 SurfaceAnimationRunner 的逐帧计算，设计目标是不持有 WMS 全局锁。AAOS 13 中三条线程均以 THREAD_PRIORITY_DISPLAY 档位创建。
4. **共享瓶颈**：WMS/ATMS 的窗口树修改仍受 WindowManagerGlobalLock 保护。addWindow、relayoutWindow、容器 reparent、焦点变化等会修改锁内状态，surface placement 也需要访问该窗口树，长时间持锁会令其他线程等待。
5. **排查方向**：用 Perfetto 区分等锁、持锁过久、动画排队、应用绘制和 SurfaceFlinger 合成，单说“WMS 很忙”不能确定根因。

**Q20: ViewRootImpl.performTraversals 是每帧都跨进程调用 WMS 的 relayout 吗？命中哪些条件才会 relayout？**

不是。scheduleTraversals() 在应用进程内向 Choreographer 投递遍历。performTraversals() 只在窗口状态需要同步到 WMS 时调用 relayoutWindow。

1. **首次遍历 mFirst**：首帧需要初始 SurfaceControl、frame 与 Insets。
2. **窗口尺寸 windowShouldResize**：requestLayout 后测量结果改变窗口尺寸时需要 relayout。
3. **可见性 viewVisibilityChanged**：可见性翻转或需要新 Surface 时同步窗口状态。
4. **窗口参数 params != null**：setLayoutParams、system UI visibility 等窗口参数变化可能触发 relayout。
5. **系统强制 mForceNextWindowRelayout**：WMS 经 resized() 等回调要求下一轮 relayout。
6. **纯重绘边界**：invalidate() 仅重绘内容，不必然调用 WMS。requestLayout() 只有导致窗口尺寸或相关窗口状态需更新时才会 relayout。Insets 分发本身不是这段判断的独立布尔条件，但可能间接触发 relayout。
7. **结果同步**：异步 relayout 用于调用方无需立即读取结果的场景。同步路径返回 frame 与 sync id，供 BLAST 同步按帧号对齐，细节依平台版本而异。
