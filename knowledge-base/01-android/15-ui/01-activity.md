# Activity 与窗口生命周期

> 学习资料（文章模式沉淀）。主线：Activity 启动到首帧的完整时序、四个"可观测时点"能承诺什么、setContentView 与 DecorView 的分工、透明主题的尺寸陷阱、Configuration 变更的两条路径与 relaunch 语义、状态保存时机、启动模式与任务栈、切换动画期间的双窗口、首帧与启动度量、可见性与内存回调、车机多用户多显示下的归属差异。AOSP 机制按本地 AAOS13 源码（Android 13）核对（ActivityThread.java、`core/java/android/internal/policy/PhoneWindow.java`、ViewRootImpl.java），版本相关结论按官方文档口径（2026-09 检索）。系统侧 relayout 与 insets 见 [05-window-system.md](./05-window-system.md)，启动优化手段见[启动优化](../09-app-practice/05-启动优化.md)，ANR 契约见 [ANR](../07-performance/03-ANR.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Activity 执行到 onResume 后界面还没出现，首帧前后经历了哪些阶段？**

以 Android 13 AOSP 冷启动为例，首帧链路分三步：

1. **创建应用与 Activity**：`ActivityThread.performLaunchActivity()` 创建 Activity 并确保 `Application` 已初始化，随后调用 `Activity.attach()` 和 `onCreate()`。`setContentView()` 把应用布局加载并放入窗口的内容容器，创建或接入 View 对象；此时还不代表 View 树已经完成首次遍历。
2. **推进生命周期并加入窗口**：Activity 进入 `onStart()`、`onResume()` 后，`ActivityThread.handleResumeActivity()` 将可见窗口加入 `WindowManager`。`onResume()` 只表示生命周期进入 resumed 状态。
3. **遍历并呈现首帧**：`ViewRootImpl.performTraversals()` 发起 View 树遍历：测量与布局为各 View 确定宽高和位置；绘制则根据这些尺寸生成界面内容。随后框架通过 relayout 与系统协商窗口 frame 和 Surface，绘制结果进入缓冲区，再交由系统合成并呈现。完成 `setContentView()` 只表示内容视图已接入窗口，不表示上述测量、布局、绘制和屏幕呈现已经完成。

因此，`onResume()` 不代表首帧已显示；首帧耗时应以首帧实际呈现为终点，例如 TTID（Time to Initial Display，初始显示耗时），而不是量到 `onResume()`。调用链可在 Android 13 的 `ActivityThread.java`、`Activity.java` 和 `ViewRootImpl.java` 中核对。

**Q2: 启动页该等 onWindowFocusChanged 还是首帧回调再关闭？**

按时点强弱排列，四个时点承诺的东西完全不同：

1. **onResume 之后**：承诺业务对象与窗口关联已建立，视图可以安全操作；不承诺任何像素。
2. **onPostResume**：框架在 onResume 之后调用，表示系统侧已把该 Activity 推进到"前台可交互"的队列；仍不承诺已出图。
3. **onWindowFocusChanged(true)**：承诺本窗口拿到了输入焦点，这是"用户此刻能操作到这里"的可靠信号，也是关闭启动遮罩的常见时机。
4. **首帧绘制完成**：承诺用户已经看到界面。在 onWindowFocusChanged 里弹遮罩仍会看到闪一下，遮罩应当挂在这之后（或更晚）。

工程纪律：需要"用户可见"的地方用焦点，需要"内容已出图"的地方用首帧回调，两者混用是启动闪屏与遮罩失效的主要来源。



**Q3: setContentView 后应用内容为什么不直接成为窗口根 View？**

PhoneWindow 是应用侧 Window 的实现，负责生成并持有 DecorView、解析窗口主题属性并保存窗口参数（`core/java/android/internal/policy/PhoneWindow.java` 本地核对）。DecorView 是窗口根 ViewGroup，内部包含由框架管理的窗口部件，以及承载应用内容的 ContentFrameLayout。

setContentView 把应用布局放进内容容器；需要调整状态栏背景、标题栏等框架部件时，应使用对应主题属性，而不是直接修改 DecorView 内部控件。



**Q4: 在 onCreate 里读取根 View 宽高得到 0，应该等到哪个时机？**

onCreate 执行时根 View 通常还没有完成首次 layout，因此 getWidth() / getHeight() 可能仍为 0；透明或无标题主题本身不会让 View 提前得到尺寸。

读取实际布局尺寸时可选：

1. **布局完成后读取**：在根 View 上注册 OnLayoutChangeListener，首次布局回调中读取宽高。
2. **绘制前读取**：使用 doOnPreDraw 等一次性预绘制回调，在首帧绘制前读取已经确定的布局尺寸。



**Q5: 旋转屏幕或切换深色模式后，Activity 为什么有时回调、有时重建？**

配置变化后的行为先看清单里的 android:configChanges：声明覆盖的变化由系统调用 onConfigurationChanged；未覆盖的变化通常会销毁并重建 Activity（Android 官方文档口径）。本地 AAOS 13 源码中，这条重建路径经过 ActivityThread.handleRelaunchActivity 与 handleRelaunchActivityInner。

系统因配置变化执行的 relaunch 和应用调用 Activity.recreate() 都会创建新的 Activity 实例；它们本身不等于重启应用进程。不要依赖 Activity 实例字段保留状态，也不要把异步任务是否存活当成两者的区分依据。



**Q6: 进程被系统回收后页面状态丢失，onSaveInstanceState 哪些内容需要自己保存？**

系统预计 Activity 可能被销毁并恢复时，会调用 onSaveInstanceState 保存轻量的临时界面状态；用户按返回结束页面或应用调用 finish() 时，系统不保证调用它（Android 官方文档口径）。因此它不适合保存必须持久化的业务数据。

状态丢失常见于：

1. **只依赖 View 自动保存**：自定义字段需要显式写入 Bundle；需要 View 层状态时还要调用父类实现。
2. **异步保存**：回调返回前状态快照可能已经完成，恢复时拿不到尚未写入的数据。
3. **只放在静态字段或单例**：进程死亡后内存状态会一并消失。

把轻量临时状态写入 Bundle，在 onCreate 或 onViewCreated 恢复；用户数据与必须持久化的业务状态应由数据层保存。



**Q7: 详情页返回后直接退出应用，是否由 singleTask 清理了返回栈？**

启动模式决定是否复用 Activity，以及复用时任务栈如何变化：

1. **standard**：通常为每次启动创建新实例。
2. **singleTop**：目标实例已在栈顶时复用，否则创建新实例。
3. **singleTask**：复用任务中的目标实例，并清理它上方的 Activity；按返回可能直接离开该任务。
4. **singleInstance**：把实例放在独立任务中，使用前应确认该任务行为确实符合需求。

Intent flags 也会影响启动结果。若页面需要普通的页面内返回栈，不要只为复用实例而随意设置 singleTask。



**Q8: 复用 Activity 收到新 Intent 后，为什么 getIntent 仍是旧值？**

当 Activity 已存在且被复用（singleTop 命中栈顶、singleTask 命中任务、或被设置为 onNewIntent 模式的复用场景）时，系统不再新建实例，而是把新的 Intent 通过 onNewIntent 交给原实例，同时回调 setIntent 的契约由应用自己完成。

复用实例会收到 onNewIntent，不会重新执行 onCreate；调用回调不会自动替应用更新 getIntent()。因此要把新 Intent 当作新的业务输入处理，并在需要时调用 setIntent(intent)，同步更新页面状态。



**Q9: 跨应用启动后 Activity 跑进了意外的任务栈，taskAffinity 与 reparenting 起什么作用？**

taskAffinity 表达 Activity 倾向加入哪个任务；allowTaskReparenting 允许符合条件的 Activity 在任务切换时转移到 affinity 对应的任务。它们会改变运行时任务归属，因此依赖固定 taskId 或固定返回路径的代码需要覆盖任务重排场景。车机多用户、多显示时还要考虑 Activity 所属用户和显示上下文。



**Q10: Activity 切换时短暂看到前后两个页面，窗口动画期间发生了什么？**

系统对每个窗口配一个过渡动画期间，前台 Activity 的旧实例与新实例会短暂同时可见：旧 Activity 从 onPause 进入不可见但未销毁的窗口，新 Activity 从 onCreate 起绘制，两者由各自的 Surface 参与合成，位置与透明度由动画进度驱动。这就是"交叠"的物理原因。

透明或无背景的新 Activity 可能在过渡期间透出旧界面，表现为闪烁；过渡期间显示的对话框也要结合窗口类型和显示时序检查。



**Q11: 启动指标显示首帧很快但页面仍是骨架，首帧与 reportFullyDrawn 分别表示什么？**

首帧表示界面已开始绘制；reportFullyDrawn() 表示应用认为关键内容已可用，并向系统报告启动完成（Android 官方文档口径）。两者应按各自语义采集：首帧可能只显示占位骨架，不能代替“主要内容已可用”的指标。



**Q12: 应用退到后台后收到 onTrimMemory，哪些状态可以释放？**

收到 TRIM_MEMORY_UI_HIDDEN 表示当前进程的 Activity 都不可见，可释放界面缓存等可重建资源；它不表示进程已经被杀。不要在这里清空仍需恢复的用户状态。必须跨进程死亡保留的数据应写入持久化存储，临时 UI 状态则按实例状态机制保存。



**Q13: 车机多用户、多显示时页面显示在错误屏幕，Activity 归属由什么决定？**

Activity 属于用户与显示两个维度：它在自己的用户空间里运行，被创建时绑定到一个显示。车机除了当前前台用户，还有独立的系统用户与前后排乘员用户，每个 occupant zone（座位区）可以对应不同显示与不同用户，因此同一时刻可能有多个"前台用户"，但只有一个是车机语境里的当前用户（Car Occupant Zone 机制见 [06-aaos-ui.md](./06-aaos-ui.md)）。

因此查询用户与显示相关信息时，应使用当前 Activity 所属用户和显示上下文；不要用默认显示的宽度、密度或资源选择结果推断其他显示上的布局。



**Q14: Activity 生命周期回调里哪些时序操作容易造成白屏、旧界面更新或闪烁？**

1. **onResume 里做重活**：把首帧预算吃掉，表现为白屏或启动指标劣化；正确位置是首帧之后或用后台调度（doOnPreDraw 之后再启动加载）。
2. **postDelayed 期间切后台**：回调在后台仍可能执行并触碰界面，触发已销毁界面的更新或异常；对界面交互要用可取消句柄并在 onStop 取消。
3. **首帧前弹 Toast/Dialog**：额外窗口可能改变层级或 Insets 状态，增加首帧与过渡排查复杂度。
4. **onPause 里提交 UI 变更**：onPause 之后界面不再可见，此时的布局与刷新都白做，还可能干扰动画。
5. **异步回调直接更新已重建的界面**：旧回调可能继续持有旧 Activity 并更新不可见 View；让界面观察生命周期感知的数据，避免回调直接持有 Activity。



**Q15: Fragment 返回后旧 View 仍被观察者更新，视图生命周期该怎么绑定？**

Fragment 的生命周期跟随宿主走一遍，中间插入自己的视图环节：onAttach → onCreate → onCreateView → onViewCreated → onStart → onResume → onPause → onStop → onDestroyView → onDestroy → onDetach（androidx Fragment 库行为，非平台源码，以下均官方文档口径）。**视图生命周期分离**（Fragment 1.2.0 起）指 Fragment 实例生命周期与它的视图树生命周期独立：视图可以被销毁而实例保留（回退栈、ViewPager2 离屏页），再次显示时经 onCreateView 重建新视图。由此 onViewCreated 成为视图相关初始化（绑定视图、注册观察者）的标准位置，且观察者必须绑定 getViewLifecycleOwner() 而不是 Fragment 自身——用 Fragment 做 LifecycleOwner 的话，视图销毁后旧观察者仍持有旧视图引用，重建后新视图收不到更新，是内存泄漏与"界面不刷新"的共同根源；在 onDestroyView 解除视图绑定也是同一原因。



**Q16: 切换 Fragment 页面后状态与内存表现不同，show/hide、replace、ViewPager2 有什么差别？**

三种切换的生命周期代价完全不同：`FragmentTransaction.show()/hide()` 只切换视图可见性，不触发任何生命周期回调——切换最快、状态天然保留，代价是两份视图树常驻内存；replace() 不加回退栈时旧 Fragment 走完整销毁（onDestroyView 到 onDestroy/onDetach），加了回退栈则只销毁视图、实例保留在栈里；ViewPager2 的 FragmentStateAdapter 介于两者之间——离屏页实例保留、视图走 onDestroyView，回滑时重建视图并恢复状态，内存与流畅度平衡最好（adapter 官方行为口径）。选型规则：高频平级切换用 show/hide（注意双份视图内存）或 ViewPager2，销毁语义明确的导航用 replace+回退栈；"切回来界面空白/状态丢了"多半是误用了 replace 而业务需要的是保留视图。



**Q17: 网络回调在 onSaveInstanceState 后提交 Fragment 事务导致崩溃，三个 commit 方法有何边界？**

commit() 把事务投递到主线程队列**异步**执行，可以加入回退栈；commitNow() 在当前调用点**同步**执行完毕，但不允许加入回退栈（同步执行与回退栈的语义冲突）；commitAllowingStateLoss() 与 commit 相同但**不检查状态保存**。崩溃的根源：onSaveInstanceState() 之后系统已为该 Activity 记录了状态快照，此时再 commit 事务，若进程被回收重建，这次事务的状态不会出现在快照里——framework 主动抛 IllegalStateException: Can not perform this action after onSaveInstanceState 防止这种不一致（androidx 行为口径）。工程规则：异步回调/网络返回触发的界面切换要判断生命周期状态（用 lifecycle 已是 RESUMED 再提交），不要用 commitAllowingStateLoss() 掩盖时序问题——丢状态比崩溃更难排查。



**Q18: 共享 ViewModel 后页面重建却不刷新或旧 View 泄漏，作用域与观察者应绑定谁？**

三种作用域：Fragment 自身（this，仅本 Fragment，随 Fragment 销毁）、宿主 Activity（activityViewModels()，跨 Fragment 共享、随 Activity 销毁）、导航图（navigationGraphViewModels()，随导航图回退栈清空）。常见错误是作用域与观察 owner 不配套：用 activityViewModels() 共享数据却把观察绑在 Fragment 自身生命周期上——视图重建（回退栈、ViewPager2 翻页）后收不到后续更新；正确组合是"数据取自所需作用域的 ViewModel，观察绑定 viewLifecycleOwner"（官方架构指南口径）。另一个坑：ViewModel 持有 Fragment 或 View 引用会造成实例级泄漏——ViewModel 活得比单个 Fragment 视图长，引用会把整个已销毁视图树钉在内存里。

**Q19: `startActivity()` 如何在任务选择与 Activity 实例复用之间作出决定？**

系统先结合启动 Intent、launch flags、目标 Activity 的 launchMode 与 taskAffinity，确定目标任务及任务栈操作，再决定复用现有 Activity 还是创建新实例。选择或复用 Task 与复用 Activity 是两个不同判断：目标 Task 已选中，不代表其中任意 Activity 都会被复用；结果还取决于目标实例在栈中的位置、清栈标记与启动模式。

读源码时应以具体 Android 版本为界，沿着“规范化启动参数 → 找目标 Task → 处理目标栈顶与复用 → 创建或交付新 Intent”追踪，并把命中的分支与最终回调（新建生命周期或 `onNewIntent()`）对应起来。`startActivityInner()` 等内部方法名和分支会随版本变化，不能当成稳定 SDK 契约。

**Q20: 隐式 Intent 如何通过 Intent Filter 找到可启动的 Activity？**

隐式 Intent 不写目标组件名，而由系统把它的 action、data（URI 与 MIME type）和 categories 与清单中候选组件的 Intent Filter 比较；符合匹配规则的组件才进入解析结果。它适合调用方只表达“要做什么”而不依赖某个实现类的场景，但可能出现多个候选或没有候选。

匹配时，Intent 的 action 必须满足 filter 声明的 action；Intent 携带的每个 category 都必须在 filter 中声明；URI scheme、host、path 与 MIME type 按 filter 声明的 data 条件约束。隐式启动可能触发系统选择器，也可能因平台导出规则、权限或目标版本限制而失败，不能把“匹配到 filter”等同于“必然成功启动”。

一个 Activity 可以声明多个 Intent Filter，每个 filter 独立描述一组可匹配条件。需要检查可处理某个 Intent 的所有 Activity 时，调用 `PackageManager.queryIntentActivities(intent, flags)`；`intent` 是待解析的 Intent，查询 flags 控制解析范围，使用 `MATCH_DEFAULT_ONLY` 可将结果限制为声明了 DEFAULT category 的候选。Android 新版还提供类型化 flags 重载。只需系统选出的最佳候选时可用 `resolveActivity()`，返回结果仍受已安装组件和权限规则约束。

**Q21: Intent Filter 中的 action、category 与 data 分别匹配什么？**

三类字段描述不同维度：action 表示要执行的操作，category 描述目标组件需满足的附加类别，data 限定数据 URI 或 MIME type。系统按 filter 规则逐项验证，不能用其中一项替代其他项。

Intent 可以包含多个 category；每个实际携带的 category 都必须被 filter 接受。反过来，filter 声明了 category 并不要求 Intent 必须全部携带，因此没有显式加 category 的 Intent 仍可能匹配。通过 `startActivity()` 解析隐式 Intent 时，候选 filter 通常还必须声明 `CATEGORY_DEFAULT`；显式 Intent 或直接使用其他查询 flag 的解析场景规则不同。data 的规则应结合 scheme、host、path 和 MIME type 一起检查。

**Q22: Launcher Activity 的 Intent Filter 应声明什么？**

作为应用启动入口的 Activity 通常声明 `android.intent.action.MAIN` action 与 `android.intent.category.LAUNCHER` category，使启动器把它识别为可显示的应用入口。此 filter 只表达入口发现语义；组件还需满足清单导出、权限与目标平台规则。

**Q23: Activity 从创建到前后台切换时，常见生命周期回调顺序是什么？**

典型首次启动依次调用 `onCreate()`、`onStart()`、`onResume()`；完全遮挡或退到后台时通常先 `onPause()`，不可见后再 `onStop()`；从停止状态返回时通常执行 `onRestart()`、`onStart()`、`onResume()`。同进程从 Activity A 启动 B 时，A 先执行 `onPause()`，随后 B 创建并进入 `onResume()`；A 若完全不可见，再执行 `onStop()`。按返回结束 Activity 时会经过 `onPause()`，若离开可见状态还会经过 `onStop()`，之后调用 `onDestroy()`。

`onStart()` 表示 Activity 对用户可见，`onResume()` 表示它位于前台并可交互；`onPause()` 是失去前台交互的边界，`onStop()` 表示不可见。生命周期可能受多窗口、转场、配置变化及系统版本影响，代码应按回调各自语义组织资源，而不是依赖所有场景都走同一条固定序列。

**Q24: `FLAG_ACTIVITY_NEW_TASK`、`SINGLE_TOP` 与 `CLEAR_TOP` 如何改变 Activity 启动？**

这些 flag 改变任务选择或目标栈处理，不是互相替代的启动模式。`NEW_TASK` 要求系统为启动选择或创建任务；`SINGLE_TOP` 在目标实例已位于栈顶时复用它；`CLEAR_TOP` 命中目标实例时清理其上方页面，目标实例是否复用还会受 launchMode 和 `SINGLE_TOP` 等条件影响。

判断时把启动方、目标任务、目标实例所在位置、Manifest launchMode 与 Intent flags 一起看。不要只依据一个 flag 名称推断返回栈，也不要未经实际任务栈验证就叠加多个清栈 flag。

**Q25: Android 内存紧张时哪些应用进程更容易被系统回收？**

Android 按进程中最重要的活动组件及其与用户交互的关系评估进程重要性；通常当前可交互的前台进程最重要，可见但未获焦点的进程次之，正在提供用户相关服务的进程仍可能比后台进程重要，停止 Activity 所在的 cached 进程最容易在需要内存时被回收。

具体回收顺序、缓存列表策略与 OEM 调整由平台实现和当前内存压力决定，不存在应用可依赖的固定保活时长。缓存进程可能被直接结束而不回调 `onDestroy()`；必须恢复的轻量界面状态应在合适时机保存，持续后台工作应交给系统认可的任务或服务机制。
