# Activity 与窗口生命周期

> 学习资料（文章模式沉淀）。主线：Activity 启动到首帧的完整时序、四个"可观测时点"能承诺什么、setContentView 与 DecorView 的分工、透明主题的尺寸陷阱、Configuration 变更的两条路径与 relaunch 语义、状态保存时机、启动模式与任务栈、切换动画期间的双窗口、首帧与启动度量、可见性与内存回调、车机多用户多显示下的归属差异。AOSP 机制按本地 AAOS13 源码（Android 13）核对（ActivityThread.java、`core/java/android/internal/policy/PhoneWindow.java`、ViewRootImpl.java），版本相关结论按官方文档口径（2026-09 检索）。系统侧 relayout 与 insets 见 [05-window-system.md](./05-window-system.md)，启动优化手段见[启动优化](../09-app-practice/05-启动优化.md)，ANR 契约见 [ANR](../07-performance/03-ANR.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Activity 执行到 onResume 后界面还没出现，首帧前后经历了哪些阶段？**

冷启动的大致顺序是 Application 初始化 → Activity.attach → onCreate（通常在这里调用 setContentView）→ onStart → onResume → 首次 measure/layout/draw → 系统合成上屏（Activity.java 与 ActivityThread.java 核对）。onResume 只表示 Activity 进入 resumed 状态，不代表 View 树已完成布局或首帧已显示。

窗口大小要等 relayout 等窗口流程提供；应用拿到尺寸并完成首次遍历后，才有可供合成的首帧。因此首帧耗时应量到首帧呈现，而不是量到 onResume。

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
