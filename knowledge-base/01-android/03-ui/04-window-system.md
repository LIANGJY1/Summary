# 窗口系统与 WindowManagerService

> 学习资料（文章模式沉淀）。主线：一个窗口的三副面孔、addWindow 与 token 契约、relayout 与遍历调度的分工、surface 层级与 z 序、Insets 体系与系统栏控制、Configuration 变更的两条路径、IME 适配、多窗口、PiP 与 TaskSnapshot 的窗口语义、窗口不显示的排查顺序。AOSP 机制按本地 AAOS13 源码（Android 13）核对（`frameworks/base/services/core/java/com/android/server/wm/`、`core/java/android/view/`），行为演进按官方文档口径（2026-09 检索）。合成与帧调度见 [../04-graphics/01-render-pipeline-vsync.md](../04-graphics/01-render-pipeline-vsync.md)，显示服务与多窗口形态见 [../04-graphics/03-display-service-foldable.md](../04-graphics/03-display-service-foldable.md)，应用侧时序见 [01-activity.md](01-activity.md)。Q 序列即结构，供 atlas 同源直读。

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

**Q21: [learning] 屏幕上同时显示两块内容，怎么判断这是不是"多窗口"？**

多窗口的强证据是存在多条独立的应用窗口交付链路：多个顶层 `ViewRootImpl`、WMS 中不同的 `WindowState`/Window token/Task（或 TaskFragment）、各窗口独立的 App Window Surface 与 BLAST 提交链，以及可分属不同 pid、UI tid 或 displayId。满足越多条，越应按多窗口管线分析。

常见误判有两个方向。其一，同一 Activity 内的双栏 View、`SlidingPaneLayout` 或 Compose pane 只有一个 ViewRootImpl 和一条 buffer 链路，不应按多窗口分析；12L 的 Activity Embedding 可让同一 Task 内并排两个 Activity container，视觉双栏仍可能只有一个顶层 Window。其二，系统栏、壁纸、输入法、dim 层和 transition leash 也会增加 SF layer，layer 数量多不证明应用建了多个 Window——要同时核对 WMS 窗口树与 SF layer 树两棵结构。



**Q22: [learning] 同进程多窗口与跨进程窗口，各自共享什么、独立什么？**

按资源归属逐级判断：每个窗口的 buffer 交付链路独立；同进程窗口共享线程；跨进程窗口还会争用目标 Display 的系统资源。共享线程不代表共享 BufferQueue，独立进程也不代表互不影响。线程模型上，`Choreographer` 是 ThreadLocal（`sThreadInstance`），判断单位是 Looper/tid 而非 pid；进程级 HWUI RenderThread 由 `RenderThread::getInstance()` 提供。

1. **永远独立**：每个窗口的 `ViewRootImpl`、Insets 状态、dirty 区域、App Window Surface 与 BLAST/BufferQueue、SF buffer layer 与 release fence——Window A 的 release fence 决定 A 的旧 buffer 何时复用，与 B 无关。
2. **同进程共享**：同一 UI Looper 上的多个 ViewRoot 共享同一个 ThreadLocal Choreographer，到期的 traversal 串行执行，A 的输入、动画、relayout 会推迟 B；所有硬件加速窗口共享进程级 HWUI RenderThread（每窗口一个 CanvasContext），A 的 `DrawFrame`、`dequeueBuffer` 或 fence wait 占用队列时 B 只能排队。
3. **跨进程仍共享**：SF 处理事务与 layer 的时间预算、RenderEngine 的 CLIENT 合成、HWC 的 plane/scaler/带宽、display mode 与 present deadline、GPU/内存/温控。

`DrawFrameTask::run()` 在 `syncFrameState()` 后按 `prepareTextures` 决定是否提前放行 UI 线程，所以主线程回调、RenderThread 任务与 GPU 工作是三段不同时间线，应分别读排队与依赖（A13 源码核对）。窗口挪到另一块屏后，mode、deadline、HWC 能力与 present fence 换了一套，不能用默认屏的 FrameTimeline 解释外屏延迟；同一 layer 经镜像或投屏可出现在多个 Output，要检查目标 Display 的 output layer state 而不是全局 layer 是否存在。


**Q23: [learning] 视频应用进入 PiP 后继续出帧，这条链路与全屏时相比变了什么？`setSeamlessResizeEnabled` 不设置会怎样？**

PiP 与 Freeform 仍走常规应用绘制链路，变的是 Task/Window bounds、transition leash、layer 几何与同屏合成策略：WM Shell 取得 Task leash，在动画中持续更新 position、crop、scale 与 alpha，系统可先用旧内容缩放裁剪过渡，不必等应用在每个采样点出新 buffer。视频以 24/30 fps 供帧而屏幕以 60/90/120 Hz present 时，多次复用同一视频 buffer 属正常。SF 缩小 layer 只改合成几何，不会自动降低 Producer 的分辨率与供帧节奏。

`setSeamlessResizeEnabled(true)` 适合视频等可连续缩放内容，复杂 UI 应显式传 `false`，避免把旧 buffer 拉伸到不合适的几何。版本差异（按 AAOS13 源码核对）：A13 未设置时 `isSeamlessResizeEnabled()` 返回 `true`（`mSeamlessResizeEnabled == null ? true : ...`），A17 材料标注 getter 改为返回 `false`；无论哪个版本，`PipTaskOrganizer` 在 resize 结束且该值为 `false` 时会对旧画面截图做 cross-fade。所以不要依赖隐式默认值，并在旋转、折叠、bounds 变化后更新 params。

PiP 常见风险对应明确证据：leash 几何已变而新 buffer 未到（过渡期正常缩放）、圆角/alpha/HDR 组合改变 HWC 策略、旧大尺寸 buffer 未 release 时新尺寸 buffer 已开始分配、进入小窗后 Producer 仍维持高分辨率高帧率（须由应用自查）。


**Q24: [learning] PiP 应用何时设置 `setAutoEnterEnabled(true)`，如何避免暂停播放时仍自动进入？**

API 31 起，`setAutoEnterEnabled(true)` 允许系统在适用的离开手势中自动把 Activity 切入 PiP，不再等待应用于 `onUserLeaveHint()` 调用 `enterPictureInPictureMode()`；默认值是 `false`。应用应在播放状态和 PiP 参数已更新时尽早提交 `setPictureInPictureParams()`，并在暂停或不再希望进入 PiP 时把 auto-enter 设为 `false`。启用后 `onPictureInPictureRequested()` 不会被调用，因此不能把该回调作为启用 auto-enter 时的进入通知。


**Q25: [learning] PiP 与桌面窗口过渡期间，`WindowContainerTransaction`、`SurfaceControl.Transaction` 和应用 buffer 是三条怎样的输入？**

过渡画面由三条异步输入合成：WCT（`WindowContainerTransaction`）改变 Task bounds、windowing mode、层级与 reparent，由 SystemUI 进程中的 WM Shell 下发；`SurfaceControl.Transaction` 改 layer 的 position、crop、alpha、Z 序与可见性，过渡期常落在 Shell 创建的 leash 上；应用 BLAST 提交新尺寸 buffer。三者在不同时刻到达，"新几何配旧 buffer"是过渡策略的一部分（旧内容被缩放或 letterbox），不一定是缺陷。

同步引擎只等已注册参与者。WMS 内部 `BLASTSyncEngine` 等待已加入同步组的 WindowContainer 的 draw 与 transaction（A13 已有，源码核对）；`SurfaceSyncGroup` 是 API 34+ 公开 API（A13 源码树中不存在——版本边界），面向应用与嵌入 Surface 的协调。未注册的 Camera、codec、SurfaceView Producer 不会因为同屏就被自动等待，它们的下一业务帧与转场没有同步契约。

排查黑边、拉伸或跳变要对齐四条证据：WCT 内容、leash/geometry 事务、应用 relayout 与 traversal、buffer 尺寸与到达时刻。桌面模式的 caption、最大化菜单、拖拽控件由 Shell 的 window decoration 子系统管理，不属于应用 `DecorView`；A17 可复用 `ViewHost` 降低 caption 反复创建成本，A13 源码树尚无该桌面装饰栈（版本边界），caption 卡顿在 A17 语境先查 SystemUI/Shell 线程。


**Q26: [learning] 分屏里的副窗口失去了焦点，它进入 `onStop()` 了吗？**

没有。Android 10（API 29）引入 Multi-resume 后，多个可见 Activity 可以同时停留在 `RESUMED`；焦点（focusable）、可见性（visible）与 top resumed 是三套不同信号。分屏副窗口、导航小窗、视频 PiP 都可能"可见但无焦点仍 RESUMED"，不能按后台已停止处理，也不能停掉全部渲染。

按信号分工处理：`onTopResumedActivityChanged(true)` 时恢复高交互频率、尝试获取独占资源；`false` 时只减少非必要重绘并准备处理资源抢占（如相机的 `onDisconnected`），官方允许非 top-resumed 的可见 Activity 继续相机预览（材料援引）；`onStop()` 只在 Activity 离开屏幕时触发，离屏工作在这里停。PiP 单独判断：通常"可见但不可聚焦"，视频仍在播就不是静态后台窗口，内容已暂停则不必每帧完整刷新。

A13 依据：`Activity.onTopResumedActivityChanged` 存在于 A13 源码（API 29 引入）。高频动画、连续 invalidate 与 frame-rate vote 应同时参考 top-resumed、实际可见性与内容是否仍在更新，三选一都不完整。


**Q27: [learning] 折叠展开或多窗口 resize 时 Activity 何时重建？`recreateOnConfigChanges` 和 `ViewModel` 各管什么？**

默认情况下，未在 `android:configChanges` 中声明的配置变化（一次折叠可能改变 `screenSize`、`smallestScreenSize`、`screenLayout`、`orientation`、`density` 的某个子集，取决于面板与厂商实现）会销毁并重建 Activity；声明自行处理则收到 `onConfigurationChanged()`，但必须重新读资源、更新布局与 display/density/Insets 等派生状态，不能原样返回。`ViewModel` 与 `ViewModelStore` 跨配置变化保留实例，`SavedStateHandle`/`rememberSaveable` 负责可恢复 UI 状态并覆盖进程被系统回收的情况；导航位置、滚动位置、表单等业务状态应与窗口尺寸和 posture 分离，折叠不应顺带清空它们。

`recreateOnConfigChanges` 的方向与 `configChanges` 相反：它声明"即使系统默认不重建，这类变化也要触发重建"。版本边界（按 AAOS13 源码核对）：A13 中该属性只支持 `mcc|mnc`，材料标注 API 37 才扩展到 touchscreen、keyboard、keyboardHidden、navigation、colorMode。它不能充当折叠屏或窗口尺寸变化的开关——窗口尺寸、方向、screen layout 的高频变化仍要通过 `configChanges`、`onConfigurationChanged()` 与状态保存处理。

大屏规则是另一条版本线：Android 12 起 multi-window 成为大屏标准行为、`resizeableActivity="false"` 不再是绝对开关（A13 已有）；Android 16 起 target 36 应用在 sw >= 600dp 屏上被忽略 `screenOrientation`、宽高比与 resizability 限制并带临时 opt-out，A17 移除 opt-out。A13 上固定方向与不可缩放声明仍有效，分析时按 target SDK 与设备最小宽度分别判断。


**Q28: [learning] Edge-to-Edge 强制是哪个版本的行为？它改变渲染管线吗？**

Edge-to-Edge 强制是 Android 15（API 35）起对 target SDK 35+ 应用的行为：内容默认延伸到系统栏与 cutout 后方，`decorFitsSystemWindows` 被置 `false`，状态栏颜色与导航分隔线置透明；Android 15 为 target 35 留过 `windowOptOutEdgeToEdgeEnforcement` 退出项，Android 16 起 target 36+ 禁用，A17 延续。AAOS13 的 `PhoneWindow` 中不存在 `ENFORCE_EDGE_TO_EDGE`（源码核对为 0 处），这些强制行为不适用于 A13 设备，判断时必须同时记录设备系统版本与 target SDK，`compileSdk` 不决定运行行为。

它不改变渲染管线：普通 View 或 Compose 页面仍沿 `ViewRootImpl` → HWUI RenderThread → App Window BLAST → SurfaceFlinger → HWC 出图，系统栏、caption、IME 作为系统 UI 或受控 Surface 参与同一 Display 的合成。改变的是布局责任——避让系统栏从窗口默认留白转给应用布局，需要时用 `statusBars()`、`displayCutout()`、`systemGestures()` 等按 type 处理。

关联版本差异：Android 15 还把 target 35 应用的 `Configuration.screenWidthDp/screenHeightDp` 与系统栏 Insets 解耦，运行时布局几何应改用实际容器、`WindowMetrics` 与 `WindowInsets`；A13 上这些 Configuration 值仍扣除系统栏，两套行为不能混用。


**Q29: [learning] 一个 `WindowInsets` 从 WMS 到 View 树经过哪些环节？listener 与 override 谁先执行、消费会挡住兄弟节点吗？**

分发有五个阶段，理解阶段边界有助于把系统状态更新与应用主动重分发区分开：

1. **状态维护**：WMS 保存带类型的 `InsetsState` 与 `InsetsSourceControl`。
2. **跨进程通知**：应用进程的 `InsetsController.onStateChanged()` 接收状态；一次 `WindowInsets` 可同时携带 systemBars、ime、displayCutout、caption、systemGestures 等 type，按 type mask 读取多种 type 不会触发多轮 Binder 分发。
3. **根 View 调度**：`ViewRootImpl.notifyInsetsChanged()` 置 `mApplyInsetsRequested`，并请求 layout 或安排 traversal。
4. **遍历分发**：`performTraversals()` 调用 `dispatchApplyInsets()`，随后由 root 的 `dispatchApplyWindowInsets()` 把值分发到 View/ViewGroup 树。AAOS13 源码中可核对 `mApplyInsetsRequested`、同名 trace section 与 `onStateChanged()`。
5. **应用主动重分发**：调用 `View.requestApplyInsets()` 只是请求根节点重新分发当前状态，不代表收到了新的 WMS 状态。

在单个 View 上，`dispatchApplyWindowInsets()` 先调用 `OnApplyWindowInsetsListener`；没有 listener 时才调用 `onApplyWindowInsets()`。listener 若要保留默认行为，必须显式调用 View 的实现，平台不会再自动调用一次。选 listener 还是 override 取决于组件封装与生命周期；`ViewCompat` 提供兼容层，不是性能优化。

消费规则取决于 API 级别和分发位置：

1. ViewGroup 自身返回 consumed 时，分发不再进入它的子树。
2. Android 11（API 30）起，兄弟节点各自收到原始输入 Insets，前一个 child 消费不会影响后一个。AAOS13 的 `ViewGroup` 源码中，`View.sBrokenInsetsDispatch` 为 false 时走新路径；target SDK 低于 30 的兼容路径保留旧的顺序消费。
3. 消费应放在明确负责避让的容器，并同时检查其后代与兄弟节点。在 decorView 根节点无条件返回 `CONSUMED` 再把 Insets 存进 ViewModel，会切断 Material、ComposeView、WebView 子树的标准分发，也可能让 Dialog、分屏或外接 Display 使用错误缓存。


**Q30: [learning] 状态栏透明了，为什么不能直接推断 SurfaceFlinger 换成了 CLIENT composition？**

系统栏背景很可能根本不是新 layer：`DecorView` 的 color view 画进当前 App Window buffer，透明后像素仍由同一个 App Window 交付；SystemUI 图标、手势 handle、IME、caption 是另一批本就存在的窗口。App Window 内部的普通 View 或 color view 不会因为视觉上像一层遮罩就变成 HWC layer，实际 layer 数量要从 SF layer tree 确认。

HWC 按整个 Display 的可见 layer 集合决策，输入包括 layer 的 format、dataspace、blend、transform、crop、protected usage，可用 overlay plane、scaler 与带宽，以及 SystemUI、IME、transition leash、dim、多窗口、视频等同屏内容。透明或半透明 layer 只可能影响选择，不构成结论；也不存在"某类 GPU 固定多 4-8 ms"的跨设备常量。

证明 HWC 回退需要同帧证据（AAOS13 源码核对 `DecorView.updateColorViews` 维护状态栏/导航栏 color view、三键导航可转对比度 scrim）：固定设备、Display mode、导航方式与页面内容；对比变更前后可见 layer tree；对齐同一 DisplayFrame 的 per-layer composition type 或 FrameTimeline 的 GPU Composition；排除 IME、transition、视频、多窗口同时变化的干扰。"透明栏出现""SF 时长变长""功耗上升"任何一项单独都不足以证明。


**Q31: [learning] IME 弹出动画期间，`onProgress()` 回调和"逐帧 apply Insets"是一回事吗？**

不是。注册 `WindowInsetsAnimation.Callback` 后，动画帧走 Choreographer 的 `CALLBACK_INSETS_ANIMATION` 阶段（A13 常量顺序 INPUT=0、ANIMATION=1、INSETS_ANIMATION=2、TRAVERSAL=3、COMMIT=4，源码核对），回调在普通 animation 之后、traversal 之前收到插值后的 Insets；生命周期是 `onPrepare()`、`onStart()`、`onProgress()`、`onEnd()`，由 dispatch mode 决定是否继续传给后代。成本取决于回调做了什么：改 `translationY`、alpha 等渲染属性通常不需要 measure，改 padding、margin、约束或列表结构才可能请求 layout；不能由可见 item 数推出固定毫秒。

"同步 Insets 动画"是另一条路径：动画进度放进普通 apply 与 traversal，每帧执行 `dispatchApplyInsets`。版本边界（A13 源码核对为不存在）：`ActivityInfo.ENABLE_SYNCHRONIZED_INSETS_ANIMATION` 是 Android 16+ 的 compat change，还需平台 `synced_insets_animation` flag 与设备满足高性能图形条件。A13 上每帧 `onProgress()` 不等于每帧 apply Insets，判断依据是 trace 中 `dispatchApplyInsets` 是否随动画逐帧出现，仅凭 API 版本无法判断某窗口在走哪条路径。

实践边界：不要硬编码 IME 动画时长与回调次数（"默认 300 ms、60 Hz 必回调 18 次"无依据，实际由系统实现、掉帧与设备状态决定）；用动画起点与终点的 bounds 差值驱动位移，避免在 `onProgress()` 里直接用 IME 底部距离——那会重复叠加原有 padding，并在浮动 IME、分屏或横屏下把内容移到错误位置。


**Q32: [learning] 应用感知折叠姿态该用 FoldingFeature 还是 hinge angle 传感器？**

普通应用优先使用 Jetpack WindowManager 的 `WindowInfoTracker.windowLayoutInfo()` 流，并按布局判断需要读取的 `FoldingFeature` 属性：

1. `bounds`：特征在应用窗口坐标系内的矩形。
2. `state`：只描述 `FLAT` 或 `HALF_OPENED`，不提供 `CLOSED` 状态或精确角度。
3. `orientation`：表示折叠特征方向；`HORIZONTAL` 且 `HALF_OPENED` 常用于 tabletop 布局判断，`VERTICAL` 对应 book 布局判断。
4. `occlusionType`：回答特征是否遮挡内容，可为 `NONE` 或 `FULL`。
5. `isSeparating`：回答应用是否应把布局分成两侧，不能与遮挡状态互相替代。

`FoldingFeature` 适合做窗口级布局决策，不适合精确动画采样。双面板设备可能 separating 而 bounds 某个维度为零；连续柔性屏平放时可能为 NONE 且不 separating；`state == FLAT` 也不代表整窗不存在铰链约束。切到外屏后，当前应用窗口可能不再包含 feature。

只有确实需要连续角度时才读 raw sensor：

1. 先调用 `getDefaultSensor(TYPE_HINGE_ANGLE)`，处理设备不提供可选传感器而返回 null 的情况。
2. A13 中 `TYPE_HINGE_ANGLE` 的类型值为 36，string type 为 `android.sensor.hinge_angle`；官方传感器定义为 on-change、degree 单位与 wake-up sensor。
3. `SENSOR_DELAY_FASTEST` 表示尽快交付，不是固定 60/120 Hz 采样保证，也不突破传感器最小上报间隔。
4. 如需平滑连续动画，在后台线程保留最新值并按 UI 帧采样，避免每个 sensor event 都触发全树 `requestLayout()`。




**Q33: [learning] TaskSnapshot 里面装的是什么？Overview 卡片、live tile 和启动占位是同一个东西吗？**

TaskSnapshot 是对一个 Task 的 SurfaceControl 子树做 screen capture 得到的 HardwareBuffer 加元数据：top Activity 组件名、orientation 与 rotation、taskSize、contentInsets 与 letterboxInsets、分辨率级别（高/低）、real/主题占位标记、windowing mode、系统栏外观、是否半透明、是否含 IME Surface、ColorSpace 与快照 ID 等（A13 `android.window.TaskSnapshot` 字段核对）。元数据决定消费端如何旋转、裁剪、缩放与判断兼容性；HardwareBuffer 经 Binder 传句柄不复制像素，但接收方仍要等待可用并参与 GPU/HWC 合成，成本不为零。

同一份快照在不同场景落到不同显示对象：

1. **Overview 静态缩略图**：Launcher 把 HardwareBuffer 包装成 hardware Bitmap，画进 Launcher 自己的 App Window（SF 看到的主体通常是 Launcher 窗口 layer，不是每张卡片一个独立 layer）。
2. **live tile**：Recents 动画中真实 Task 的 Surface 经 remote animation leash 显示（`RemoteAnimationTarget`）。
3. **snapshot starting window**：旧快照被设到 Shell 创建的独立 starting surface 上，等待应用首帧。
4. **SF LayerSnapshot**：SurfaceFlinger 普通帧的 layer 可见性/几何状态，与最近任务缩略图无关。

看到 Overview 卡片不能推出屏幕上存在名为 TaskSnapshot 的独立 SF layer；`LayerSnapshotBuilder` 出现在 trace 里也可能只是 SF 为普通显示帧更新状态。这一区分是排查 Overview 卡顿、点击跳变与图形内存的第一步。


**Q34: [learning] TaskSnapshot 什么时候被捕获？应用被冻结前会自动截图吗？**

捕获有三条路径：转场（Task 将不可见时、在转场事务生效前记录）、休眠前、特权调用方主动请求。A13 的转场入口是 `TaskSnapshotController.onTransitionStarting()` → `snapshotTasks()` → `snapshotTask()`（源码核对）；A17 重构为 `SnapshotController.onTransactionReady()` 配合 `Transition.ChangeInfo`（版本差异，时机语义相同：用关闭前的 rotation 与 bounds 捕获，避免旧画面配上新几何）。休眠前路径 `snapshotForSleeping()` 遍历对应 Display 的可见 leaf Task，被 Recents 动画控制的 Task 会跳过（A13 的 `inRecentsTransition` 判断核对），安全锁屏进入休眠时默认屏的 home Task 也可能被捕获用于解锁回桌面的 starting window。主动请求用的 `TaskSnapshotManager`/`SnapshotManagerService` 是 A17 才有的系统服务，A13 源码树没有（版本边界）。

Cached App Freezer 不承担触发源：A13 的 `CachedAppOptimizer` 冻结应用时不调用任何 snapshot 入口（源码核对无引用），"每次冻结前先截 Task"的通用 hook 不存在。同理，捕获、`onPause()` 与转场开始之间没有源码承诺的固定顺序，系统只尽量在 Task 关闭前保留可过渡的视觉状态，应用不应假设 `capture → onPause → transition` 的时序。


**Q35: [learning] 什么时候会得到主题色卡片而不是真实截图？`setRecentsScreenshotEnabled(false)` 与 `FLAG_SECURE` 有什么区别？**

A13 的 `getSnapshotMode` 逻辑（源码核对）：非 standard/assistant 类型 Task 返回 `SNAPSHOT_MODE_NONE`，不出快照；top Activity `shouldUseAppThemeSnapshot()` 为真时生成 `SNAPSHOT_MODE_APP_THEME`——该条件是 `setRecentsScreenshotEnabled(false)`（`!mEnableRecentsScreenshot`）或 Task 内任一窗口带 `FLAG_SECURE`（`isSecureLocked`）；否则 `SNAPSHOT_MODE_REAL` 捕获真实画面。主题占位由 system_server 用 `TaskDescription` 背景色与窗口背景绘制（`drawAppThemeSnapshot`），不含应用敏感像素，所以主题色卡片可能是预期行为而非截图失败。Recents 与屏保 Activity 不捕获；设备 overlay `config_disableTaskSnapshots = true` 可整体关闭能力（TV/IoT 常用）。

两个隐私 API 范围不同：`Activity.setRecentsScreenshotEnabled(boolean)` 自 API 33 公开（A13 已有），只禁止该 Activity 画面被用作 Overview 表示，系统在其他允许场景仍可截图；`FLAG_SECURE` 更广——阻止窗口进入普通截图并限制在非安全 Display 上显示。处理登录、支付或隐私数据按威胁模型选 `FLAG_SECURE`，不能把 Overview 开关当它的替代品。


**Q36: [learning] TaskSnapshot 在运行时如何缓存，磁盘上保存哪些文件？**

运行时缓存：A13 `TaskSnapshotCache` 用 `ArrayMap<taskId, CacheEntry>`（`mRunningCache`）保存运行中任务快照，没有按访问顺序淘汰的 LRU 逻辑；清理靠 top Activity 移除或进程死亡、Task 从 Recents 删除、Task 重新可见完成转场等时机（源码核对）。`onlyCacheLowResTaskSnapshot` 的低分辨率主缓存与高分辨率延迟释放（5000 ms）是 A17 的 flag 路径，A13 没有——分析 A13 图形内存时按高/低分辨率两版并存估算。

磁盘：后台队列（A13 `TaskSnapshotPersister`）写三个文件——`<taskId>.proto`（元数据）、`<taskId>.jpg`（高分辨率图像）、`<taskId>_reduced.jpg`（低分辨率）；JPEG 压缩质量常量 95（A13 名 `QUALITY`，A17 材料改名 `COMPRESS_QUALITY`，值不变）。A13 目录是 `/data/system_ce/<userId>/snapshots/`，A17 材料在其下增加随机化子目录（版本差异，隐私加固）。磁盘命中仍包含文件 I/O、`BitmapFactory.decodeFile()` 解码与 `copy(Config.HARDWARE)` 的硬件位图分配（A13 `TaskSnapshotLoader` 核对），与内存命中不同量级——Overview 空卡要先区分 system_server 缓存、磁盘恢复、Launcher 缓存与主线程 bind 四段，不能统一归因 GPU。


**Q37: [learning] 如何估算 TaskSnapshot 的图形内存，像素格式和分辨率由什么决定？**

`width × height × bytesPerPixel` 只能估算像素存储下限；以 1080 × 2400 的 RGBA_8888 为例，约占 9.9 MiB。实际占用还包括 row stride、gralloc 对齐，以及同一任务在高低分辨率缓存、Binder 引用、Launcher hardware Bitmap 和 starting window 中同时存活的副本。

AAOS13 默认像素格式为 RGBA_8888；只有 overlay `config_use16BitTaskSnapshotPixelFormat` 开启、快照格式仍为 `UNKNOWN`、top Activity `fillsParent()`，且主窗口不是“半透明并显示壁纸”的组合时，才选择 RGB_565。高/低分辨率 scale 默认分别为 1.0 和 0.5，可由厂商 overlay 调整。没有 AOSP 规则保证低内存设备固定缓存 3–5 张快照或必用 RGB_565，估算时应以目标设备配置、快照尺寸和实际引用生命周期为准。


**Q38: [learning] 点击 Overview 卡片回到应用走哪条路径？旧快照为什么不匹配时会不用？**

两条启动路径：点击当前 running 的 live tile 时，Quickstep 沿 remote target leash 做返回动画；点击静态卡片时 Launcher 发起 `startActivityFromRecents()`，WMS 可能直接等待已有 App Window、创建 snapshot starting window、因快照不兼容改用 splash、或在 Task/Activity 已满足显示条件时不建 starting window——不存在统一的"静态快照 layer 渐变切换成应用实时 Surface"模型，Launcher 静态缩略图、Shell remote leash 与 WMS starting window 是三套对象。

starting window 的兼容检查（A13 `ActivityRecord.isSnapshotCompatible`，源码核对）：top Activity 组件一致、快照 rotation 与目标 rotation 一致、快照 taskSize 宽高比与当前 bounds 宽高比差值 ≤ 0.01（该阈值 A13 与 A17 材料一致；A17 材料将该方法称为 `isSnapshotOrientationCompatible`，语义相同）；折叠、旋转或 resize 后不满足即回退 splash 或不显示快照——排查"折叠后快照拉伸或闪启动屏"从这里入手。尺寸不匹配但可用时，会按 letterbox insets 调整位置并对 X/Y 分别缩放旧 buffer 遮住间隙，它只遮间隙，不修复应用布局，应用仍要提交符合新 bounds 的首帧。

移除时机：应用内容 ready 后由 TaskOrganizer 请求移除 starting window；A13 `TaskSnapshotWindow` 的一般延迟为 100 ms、含 IME 的为 600 ms（源码核对），A17 材料另有 3000 ms 的 fixed-rotation 档（版本差异），三档都是特定移除模式的保护值而非冷启动固定等待。点击后画面跳变的排查要分三张画面分别对齐 task bounds、rotation、density 与 contentInsets/letterboxInsets：Launcher 静态缩略图、WMS/Shell 的 starting window、应用新提交的 buffer，只看录像无法判断跳变发生在哪一段。
