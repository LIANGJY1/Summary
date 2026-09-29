# Activity 与窗口生命周期

> 学习资料（文章模式沉淀）。主线：Activity 启动到首帧的完整时序、四个"可观测时点"能承诺什么、setContentView 与 DecorView 的分工、透明主题的尺寸陷阱、Configuration 变更的两条路径与 relaunch 语义、状态保存时机、启动模式与任务栈、切换动画期间的双窗口、首帧与启动度量、可见性与内存回调、车机多用户多显示下的归属差异。AOSP 机制按本地 AAOS13 源码（Android 13）核对（`ActivityThread.java`、`core/java/android/internal/policy/PhoneWindow.java`、`ViewRootImpl.java`），版本相关结论按官方文档口径（2026-09 检索）。系统侧 relayout 与 insets 见 [01-窗口系统与WMS.md](./01-窗口系统与WMS.md)，启动优化手段见 ../09-app-practice/05-启动优化.md，ANR 契约见 ../07-performance/03-ANR.md。Q 序列即结构，供 atlas 同源直读。

**Q1: Activity 从进程创建到首帧呈现经过哪些阶段？为什么 onResume 返回不等于界面已经可见？**

应用侧顺序是 `Application` 初始化 → `Activity.attach`（拿到 Context、窗口回调）→ `onCreate` → `onStart` → `onResume` → `setContentView` 之后的首个遍历完成 measure/layout/draw → 系统合成上屏（Activity.java 与 ActivityThread.java 核对）。`onResume` 只是生命周期回调，表示"业务对象已可用"，此时 View 树可能还没测量过一次，更没有像素交给合成器。

差别的根因是窗口几何来自服务端而不是应用自己：`setContentView` 只是把内容挂进 `PhoneWindow` 的 DecorView，真正知道"这块窗口多大"要等 relayout 的返回值回来，首帧才能算出来（01 册 Q4）。所以"首帧耗时"应从进程创建量到第一次绘制完成，而不是量到 `onResume`。

**Q2: onResume、onPostResume、onWindowFocusChanged、首帧这四个时点各自能承诺什么？**

按时点强弱排列，四个时点承诺的东西完全不同：

- **`onResume` 之后**：承诺业务对象与窗口关联已建立，视图可以安全操作；不承诺任何像素。
- **`onPostResume`**：框架在 `onResume` 之后调用，表示系统侧已把该 Activity 推进到"前台可交互"的队列；仍不承诺已出图。
- **`onWindowFocusChanged(true)`**：承诺本窗口拿到了输入焦点，这是"用户此刻能操作到这里"的可靠信号，也是关闭启动遮罩的常见时机。
- **首帧绘制完成**：承诺用户已经看到界面。在 `onWindowFocusChanged` 里弹遮罩仍会看到闪一下，遮罩应当挂在这之后（或更晚）。

工程纪律：需要"用户可见"的地方用焦点，需要"内容已出图"的地方用首帧回调，两者混用是启动闪屏与遮罩失效的主要来源。

**Q3: setContentView 之后，PhoneWindow、DecorView、ContentFrameLayout 各承担什么？**

`PhoneWindow` 是应用侧 Window 的实现，负责生成并持有 DecorView、解析窗口主题属性、保存窗口参数（`core/java/android/internal/policy/PhoneWindow.java` 本地核对）。DecorView 是窗口的根 ViewGroup，内部由框架生成"系统窗口部件"（状态栏背景、标题栏容器等，具体形态由主题决定）与一个放置应用内容的容器，官方内容容器是 `ContentFrameLayout`（在 `com.android.internal.R.layout` 中）。应用 `setContentView` 的 View 树最终挂在这个内容容器里。

由此推出两条常见结论：一是 `getWindow().getDecorView()` 拿到的是包含系统部件的整棵窗口树，`findViewById` 在它上面能拿到标题栏等框架控件，但这些控件由框架管理，改它们等于跟主题约定打架；二是想真正改变窗口的系统部件外观，正确做法是改主题属性，而不是去 DecorView 里找控件改。

**Q4: 透明或无标题主题下，为什么 onCreate 里 getWidth()/getHeight() 得到 0，该怎么改？**

因为窗口还没有完成第一次 relayout，应用的 View 树没有被测量过，尺寸只能来自 layout 后的 `getWidth()`，此时自然是 0；这与主题无关，只与"你问得太早"有关。

三种可用时机，按可靠度排序：在根 View 上挂 `OnLayoutChangeListener` 或 `addOnLayoutChangeListener`，在第一次 layout 后读尺寸；用 `doOnPreDraw` 之类的"首次绘制前"回调；对必须立刻拿到尺寸的场景（例如对话窗宽度计算），用 `WindowManager.LayoutParams` 显式给出宽度并重新请求 layout，而不是读 `getWidth()`。

**Q5: Configuration 变化时，应用收到 onConfigurationChanged 还是被重建？relaunch 与 recreate 有什么不同？**

判定分两层。第一层看清单里的 `android:configChanges`：声明覆盖的项由系统直接回调 `onConfigurationChanged`，未覆盖的项走重建路径（Activity 文档口径）。第二层看系统内部实现：重建路径在应用侧是 `ActivityThread.handleRelaunchActivity` → `handleRelaunchActivityInner`（本地 AAOS13 源码核对）。

relaunch 与 recreate 的区别在于前者尽量保留进程与组件：窗口参数、任务栈、已持有的单例对象都能复用，只重建 Activity 实例并重新走创建流程；后者是进程级重建。实践上两者的差别体现在静态状态与异步任务上——relaunch 后它们仍然活着，容易写出"以为会重来所以重新初始化"的重复逻辑 bug。

**Q6: onSaveInstanceState 什么时候被调用？为什么经常出现"状态没保存"？**

它在 Activity 即将被销毁且可能恢复时被调用，典型时机是用户按返回、或进入后台被回收前。三种常见丢失原因：一是页面里没有需要保存的状态却以为框架会帮你保存——框架只保存 View 层自动保存机制覆盖的内容，自定义字段必须显式写入；二是保存回调里做了异步操作，恢复时数据尚未就绪；三是过早把状态写入静态或单例，进程被杀后静态值仍在但语义已失效（系统会先杀进程再回收记录）。

可靠的模式是：把需要跨进程死亡保留的状态收敛到一处，在 `onSaveInstanceState` 只写快照，在 `onCreate`/`onViewCreated` 只读快照，其余状态从数据层重新推导。

**Q7: launchMode 的四种模式差别是什么？singleTask 会带来什么返回栈副作用？**

`standard` 每次启动都新建实例；`singleTop` 仅当目标已在栈顶时复用，否则新建；`singleTask` 复用已有实例并把它所在任务带到前台；`singleInstance` 早期用于独占一个任务，废弃路径多，现代车机不建议使用（官方文档口径）。`flagSingleTop` 与 `FLAG_ACTIVITY_NEW_TASK` 等 Intent 标志会改变判定。

`singleTask` 的副作用是它会清空目标实例之上的所有 Activity：用户按返回时不会回到原页面，而是直接退出应用。页面间互跳多的应用用 `singleTask` 会出现"点进详情再返回就回到桌面"的观感，处置方式是改用 `singleTop` 加显式导航，或改用 ViewModel 与导航组件管理返回栈。

**Q8: onNewIntent 什么时候会被调用？它和重启 Activity 有什么区别？**

当 Activity 已存在且被复用（`singleTop` 命中栈顶、`singleTask` 命中任务、或被设置为 `onNewIntent` 模式的复用场景）时，系统不再新建实例，而是把新的 Intent 通过 `onNewIntent` 交给原实例，同时回调 `setIntent` 的契约由应用自己完成。

与重启的区别有三点：实例与 View 状态全部保留（新 Intent 不走 `onCreate`）、`getIntent()` 不会自动更新、已经发起但未完成的异步结果不重放。因此复用型 Activity 必须把 `onNewIntent` 当作"新的业务输入"处理，显式更新界面状态，并 `setIntent(intent)` 保持 `getIntent()` 一致。

**Q9: taskAffinity 与 allowTaskReparenting 解决什么问题？**

`taskAffinity` 指定 Activity 偏好的任务容器，用于让不同应用的不同 Activity 能共用一个任务（例如启动器与桌面），也是隐式 Intent 决定"放进哪个任务"的依据之一。`allowTaskReparenting` 允许一个 Activity 在目标显示或目标任务出现后被重新挂到另一个任务上，典型场景是横竖屏或多显示切换。

两者的工程风险是调试困难：任务归属在运行时才会确定，Activity 可能"跑到了别的任务里"，让返回栈和 `getTaskId()` 的假设失效。车机上多显示并存时，Activity 的任务归属还受 occupant zone 与显示分配影响（见 [06-AAOS车机UI架构与CarService.md](./06-AAOS车机UI架构与CarService.md)）。

**Q10: Activity 切换动画期间，界面上同时存在什么？为什么会看到两个界面交叠？**

系统对每个窗口配一个过渡动画期间，前台 Activity 的旧实例与新实例会短暂同时可见：旧 Activity 从 `onPause` 进入不可见但未销毁的窗口，新 Activity 从 `onCreate` 起绘制，两者由各自的 Surface 参与合成，位置与透明度由动画进度驱动。这就是"交叠"的物理原因。

它带来两个实际坑：透明/无背景的新 Activity 在动画里会短暂透出旧界面内容，视觉上像闪烁；动画期间弹出的 Dialog、Toast 落在哪个层级上，取决于它们的窗口类型与动画时序（08 册讲层级错乱的排查）。

**Q11: 首帧和"完全可用"怎么度量，分别用什么 API？**

首帧完成用根 View 的 `OnDrawListener`（`ViewTreeObserver.addOnDrawListener`，仅调试/一次性使用）或 `doOnPreDraw` 前后的回调来观测；"完全可用"用 `Activity.reportFullyDrawn()` 显式上报，系统据此结束启动度量窗口（Activity 文档口径）。`reportFullyDrawnAfterTimeout` 用于已经超时的情况兜底。

注意两者的语义差别：首帧可能是一个空列表或占位骨架，报 `reportFullyDrawn` 意味着"关键数据已到齐"。把两者混淆，会得到"指标很好但用户仍看到骨架"或"指标难看但实际可用"两种相反的误判。

**Q12: onTrimMemory 与 Activity 可见性是什么关系？**

`TRIM_MEMORY_UI_HIDDEN` 在该进程的所有 Activity 都不可见时回调，是"进程可以缓存但仍需保留状态"的信号；更高等级的 level（如 `TRIM_MEMORY_RUNNING_CRITICAL`）提示进程随时可能被杀，此时应放弃可重建的中间态、只留必须持久化的数据（官方文档口径）。

常见误用是在 `UI_HIDDEN` 里就把用户数据清空——进程此时还活着，回来的还是同一个实例；真正需要落盘的是不可重建的业务状态，且应通过生命周期感知组件（`ProcessLifecycleOwner`）而不是单个 Activity 回调来触发。

**Q13: 车机上多用户、多显示并存时，Activity 的归属和可见性有什么特殊之处？**

Activity 属于用户与显示两个维度：它在自己的用户空间里运行，被创建时绑定到一个显示。车机除了当前前台用户，还有独立的系统用户与前后排乘员用户，每个 occupant zone（座位区）可以对应不同显示与不同用户，因此同一时刻可能有多个"前台用户"，但只有一个是车机语境里的当前用户（Car Occupant Zone 机制见 [06-AAOS车机UI架构与CarService.md](./06-AAOS车机UI架构与CarService.md)）。

工程含义有两条：不要用"当前前台用户"当作全局唯一假设（`ActivityManager.getRunningAppProcesses` 这类全局查询在多用户下语义不同）；把显示相关状态（宽度、密度、资源变体）从显示 id 取，而不是从默认显示取——这正是 [04-资源主题与多屏适配.md](./04-资源主题与多屏适配.md) 讨论的资源适配分歧点。

**Q14: 生命周期阶段上有哪些高频时序坑？**

- **onResume 里做重活**：把首帧预算吃掉，表现为白屏或启动指标劣化；正确位置是首帧之后或用后台调度（`doOnPreDraw` 之后再启动加载）。
- **postDelayed 期间切后台**：回调在后台仍可能执行并触碰界面，触发已销毁界面的更新或异常；对界面交互要用可取消句柄并在 `onStop` 取消。
- **首帧前弹 Toast/Dialog**：改变窗口层级与 insets，可能让首帧时间变长、动画被推迟，甚至引发一帧额外重排（输入法场景见 01 册 Q13）。
- **onPause 里提交 UI 变更**：onPause 之后界面不再可见，此时的布局与刷新都白做，还可能干扰动画。
- **异步回调直接更新已重建的界面**：Activity 重建后旧回调仍持有旧实例引用，要么空指针要么写到不可见界面；对策是生命周期感知的数据源（08 册给出同类排查路径）。
