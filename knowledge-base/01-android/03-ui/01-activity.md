# Activity 生命周期与首帧

> 学习资料（文章模式沉淀）。主线：Activity 生命周期回调的两对正交维度与分场景顺序、启动到首帧的完整时序、四个"可观测时点"能承诺什么、setContentView 与 DecorView 的分工、Configuration 变更的两条路径与 relaunch 语义、状态保存的时机与恢复链路、切换动画期间的双窗口、首帧与启动度量、生命周期时序缺陷排查。AOSP 机制按本地 AAOS13 源码（Android 13）核对（ActivityThread.java、`core/java/android/internal/policy/PhoneWindow.java`、ViewRootImpl.java），版本相关结论按官方文档口径（2026-09 检索）。系统侧 relayout 与 insets 见 [04-window-system.md](04-window-system.md)，启动优化手段见[启动优化](../15-performance/07-app-startup-optimization.md)，ANR 契约见 [ANR](../15-performance/08-anr.md)；启动模式、任务栈与 Intent 匹配见 [../02-app-framework/10-activity-launch-tasks.md](../02-app-framework/10-activity-launch-tasks.md)，Fragment 与 ViewModel 见 [../02-app-framework/11-fragment-viewmodel.md](../02-app-framework/11-fragment-viewmodel.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Activity 从创建到前后台切换时，常见生命周期回调顺序是什么？**

首次显示、启动另一个 Activity、从后台返回和结束页面的回调序列不同。应按场景理解回调含义，不能把某一条序列当作所有设备状态下的固定承诺。

1. **首次显示**：通常依次调用 `onCreate()`、`onStart()`、`onResume()`。
2. **同进程从 A 启动 B**：A 先调用 `onPause()`，随后 B 创建并进入 `onResume()`。A 完全不可见后才调用 `onStop()`。多窗口和转场可能改变可见状态。
3. **从停止状态返回**：通常依次调用 `onRestart()`、`onStart()`、`onResume()`。
4. **结束 Activity**：按返回结束页面时先调用 `onPause()`。若离开可见状态，再调用 `onStop()`，随后调用 `onDestroy()`。配置变化重建也可能触发销毁，但原因与用户结束页面不同。

`onStart()` 表示 Activity 进入可见状态，`onResume()` 表示它进入前台交互状态，`onPause()` 表示失去前台交互，`onStop()` 表示不再可见。多窗口、窗口转场、配置变化和系统版本会影响具体顺序。按回调语义管理资源，不依赖所有场景都走同一条路径。

**Q2: Activity 执行到 onResume 后界面还没出现，首帧前后经历了哪些阶段？**

以 Android 13 AOSP 冷启动为例，首帧链路分三步：

1. **创建应用与 Activity**：`ActivityThread.performLaunchActivity()` 创建 Activity 并确保 `Application` 已初始化，随后调用 `Activity.attach()` 和 `onCreate()`。`setContentView()` 把应用布局加载并放入窗口的内容容器，创建或接入 View 对象。此时还不代表 View 树已经完成首次遍历。
2. **推进生命周期并加入窗口**：Activity 进入 `onStart()`、`onResume()` 后，`ActivityThread.handleResumeActivity()` 将可见窗口加入 `WindowManager`。`onResume()` 只表示生命周期进入 resumed 状态。
3. **遍历并呈现首帧**：`ViewRootImpl.performTraversals()` 发起 View 树遍历。

    1. **测量与布局**：为各 View 确定宽高和位置。
    2. **绘制**：根据测量与布局结果生成界面内容。

随后框架通过 relayout 与系统协商窗口 frame 和 Surface，绘制结果进入缓冲区，再交由系统合成并呈现。完成 `setContentView()` 只表示内容视图已接入窗口，不表示测量、布局、绘制和屏幕呈现已经完成。

因此，`onResume()` 不代表首帧已显示。首帧耗时应以首帧实际呈现为终点，例如 TTID（Time to Initial Display，初始显示耗时），而不是量到 `onResume()`。调用链可在 Android 13 的 `ActivityThread.java`、`Activity.java` 和 `ViewRootImpl.java` 中核对。

**Q3: 启动页该等 `onWindowFocusChanged()` 还是首帧回调再关闭？**

`onWindowFocusChanged(true)` 只表示窗口获得输入焦点，不承诺窗口内容已经绘制或提交到显示合成链路。需要避免启动遮罩早于内容消失时，应等内容首帧对应的缓冲区提交，再移除遮罩。这仍不等于面板已经完成物理扫描显示。

四个可观测时点的承诺强度不同：

1. **`onResume()`**：Activity 进入 resumed 状态，可以处理前台生命周期工作。不承诺像素已绘制。
2. **`onPostResume()`**：框架在 `onResume()` 之后回调。仍不承诺首帧已经绘制。
3. **`onWindowFocusChanged(true)`**：窗口取得输入焦点。它不等于首帧已绘制，也不保证用户已经看到新内容。
4. **首帧缓冲区提交**：例如 API 29 起可用的 `registerFrameCommitCallback()` 可观察 ViewRoot 的帧缓冲提交。提交之后 SurfaceFlinger 仍需合成并显示。若要判断实际呈现时刻，应使用帧时间线或 presentation timestamp 等显示侧证据。

因此，焦点变化适合处理输入焦点语义。以新内容替换启动遮罩时，应按所需的可见性保证选择帧提交或实际呈现时点。

**Q4: setContentView 后应用内容为什么不直接成为窗口根 View？**

PhoneWindow 是应用侧 Window 的实现，负责生成并持有 DecorView、解析窗口主题属性并保存窗口参数（`core/java/android/internal/policy/PhoneWindow.java` 本地核对）。DecorView 是窗口根 ViewGroup，内部包含由框架管理的窗口部件，以及承载应用内容的 ContentFrameLayout。

setContentView 把应用布局放进内容容器。需要调整状态栏背景、标题栏等框架部件时，应使用对应主题属性，而不是直接修改 DecorView 内部控件。

**Q5: 在 onCreate 里读取根 View 宽高得到 0，应该等到哪个时机？**

onCreate 执行时根 View 通常还没有完成首次 layout，因此 getWidth() / getHeight() 可能仍为 0。透明或无标题主题本身不会让 View 提前得到尺寸。

读取实际布局尺寸时可选：

1. **布局完成后读取**：在根 View 上注册 `OnLayoutChangeListener`，首次布局回调中读取宽高。只需读取一次时，在回调里移除监听器。
2. **绘制前读取**：使用 `doOnPreDraw` 等一次性预绘制回调，在首帧绘制前读取已经确定的布局尺寸。

**Q6: [learning] 旋转屏幕或切换深色模式后，Activity 为什么有时回调、有时重建？**

系统根据 Activity 是否声明自行处理某类配置变化，决定调用 `onConfigurationChanged()` 还是销毁并重建 Activity。未在 `android:configChanges` 中覆盖的变化通常会触发重建。该属性只应列出 Activity 确实能自行适配的配置类型。

1. **声明处理的变化**：例如 `orientation` 表示屏幕方向变化，`screenSize` 表示可用屏幕尺寸变化，`uiMode` 可包含夜间模式变化。当 targetSdk 达到 API 13 时，方向变化通常还需同时处理 `screenSize`。走该路径时 Activity 实例继续存活、不发生重建，因此不触发 `onSaveInstanceState()` 与 `onRestoreInstanceState()`。
2. **未声明的变化**：若清单没有声明相应配置，系统通常通过 Activity relaunch 让新实例加载新资源与配置。AAOS 13 源码路径经过 `ActivityThread.handleRelaunchActivity()` 与 `handleRelaunchActivityInner()`。
3. **进程边界**：系统配置 relaunch 和应用调用 `Activity.recreate()` 都会创建新的 Activity 实例，但它们本身不等于重启应用进程。不要依赖 Activity 实例字段保留状态，也不要以异步任务是否存活来区分两种路径。

配置项名称与变化覆盖关系以应用 targetSdk 和目标 Android 版本的 Activity manifest 文档为准。

**Q7: 进程被系统回收后页面状态丢失，onSaveInstanceState 哪些内容需要自己保存？**

系统预计 Activity 可能被销毁并恢复时，会调用 onSaveInstanceState 保存轻量的临时界面状态。用户按返回结束页面或应用调用 finish() 时，系统不保证调用它（Android 官方文档口径）。因此它不适合保存必须持久化的业务数据。

状态丢失常见于：

1. **只依赖 View 自动保存**：自定义字段需要显式写入 Bundle。需要 View 层状态时还要调用父类实现。
2. **异步保存**：回调返回前状态快照可能已经完成，恢复时拿不到尚未写入的数据。
3. **只放在静态字段或单例**：进程死亡后内存状态会一并消失。

把轻量临时状态写入 Bundle，在 onCreate 或 onViewCreated 恢复。用户数据与必须持久化的业务状态应由数据层保存。

**Q8: Activity 切换时短暂看到前后两个页面，窗口动画期间发生了什么？**

某些窗口转场会让旧 Activity 的窗口内容与新 Activity 的首帧在一段时间内都参与显示合成，因此画面可能短暂重叠。它不是两个 Activity 同时处于 resumed 状态的证明。

1. **内容来源**：旧窗口可能在 Activity 开始退出后仍保留已提交的 Surface 内容，新 Activity 则创建自己的窗口并提交首帧。
2. **画面变化**：窗口转场按动画进度调整位置、透明度或层级，SurfaceFlinger 合成两侧的缓冲区。
3. **透出与闪烁**：新 Activity 使用透明主题或无背景时，动画可能透出旧窗口。如出现闪烁，还要检查窗口背景、Surface 提交时机和过渡动画配置。

对话框若在过渡期间出现，也要结合窗口类型、显示时序和目标显示检查，不能仅从 Activity 回调顺序判断画面归属。

**Q9: 启动指标显示首帧很快但页面仍是骨架，首帧与 reportFullyDrawn 分别表示什么？**

首帧表示界面已开始绘制。reportFullyDrawn() 表示应用认为关键内容已可用，并向系统报告启动完成（Android 官方文档口径）。两者应按各自语义采集：首帧可能只显示占位骨架，不能代替“主要内容已可用”的指标。

**Q10: Activity 生命周期回调里哪些时序操作容易造成白屏、旧界面更新或闪烁？**

这类问题通常由重活占用首帧、生命周期外仍执行回调，或额外窗口干扰转场造成。排查时先把操作绑定到正确时点，并让异步结果服从当前 Activity/视图生命周期。

1. **在 `onResume()` 做重活**：同步 I/O、图片解码或长计算会占用主线程首帧预算。`doOnPreDraw` 在当前帧绘制前执行，在其中启动重活仍可能推迟首帧。把重活放到后台线程，并在首帧之后按需更新 UI。
2. **延迟任务跨入后台**：`postDelayed` 回调在 Activity 停止后仍可能运行并触碰界面。使用可取消句柄，在 `onStop()` 取消不再需要的任务，或通过生命周期感知的调度收敛回调。
3. **首帧前显示 Toast/Dialog**：额外窗口会改变层级或 Insets 条件，使首帧和窗口过渡更难判断。确认它确实需要在首帧前显示，并核对窗口类型和时机。
4. **在 `onPause()` 提交 UI 变化**：Activity 已失去前台交互，此时修改的布局可能很快不可见，也可能与窗口动画竞争。将可见性相关变更放到合适的 resumed 状态。
5. **异步结果写入旧实例**：配置重建或视图销毁后，旧回调仍可能持有旧 Activity/View。让界面观察生命周期感知的数据，并在结果应用前确认当前 owner 有效。

**Q11: [learning] `onStart()`/`onStop()` 与 `onResume()`/`onPause()` 两对回调分别以什么维度划分 Activity 状态，为什么要拆成两对？**

两对回调对应两个正交维度：`onStart()` 与 `onStop()` 以“是否在屏幕上可见”划界，`onResume()` 与 `onPause()` 以“是否位于前台、持有输入焦点可交互”划界。拆成两对是为了让“可见但不可交互”的状态有独立表达，把资源管理代码挂到正确的粒度上。

1. **可见维度**：`onStart()` 之后 Activity 已经出现在屏幕上，`onStop()` 之后完全不可见。需要“看得见就工作”的资源挂在这一对里，例如界面刷新、地图渲染、动画播放。
2. **前台可交互维度**：`onResume()` 之后 Activity 位于前台并持有输入焦点，`onPause()` 表示失去前台交互。需要独占用户注意力的资源挂在这一对里，例如相机取景、高精度传感器监听。
3. **正交组合的实证**：被对话框样式或透明 Activity 覆盖时，以及 Android 7.0 起的多窗口中，非焦点 Activity 处于 paused 但仍可见，只回调 `onPause()` 而不回调 `onStop()`。若把“暂停视频”“停止刷新”写进 `onPause()`，多窗口里仍然可见的页面会被错误冻结。
4. **多显示补充**：多显示与桌面形态下系统还可能让多个 Activity 同时处于 resumed 状态，“位于前台”与“可见”因此更不能互相替代。

实践中按资源归属落位：需要持续展示的内容与可见性回调绑定，需要独占用户注意力的资源与前台回调绑定。

**Q12: [learning] 从 Activity A 启动 B 时，A 的 `onPause()` 与 B 的 `onResume()` 谁先执行，这对 `onPause()` 中的代码有什么约束？**

A 的 `onPause()` 先执行，并且系统要等它执行完才推进 B 的创建与恢复，因此 `onPause()` 里的耗时操作会直接推迟 B 的 `onResume()` 与首帧呈现。

1. **顺序的来源**：切换时系统先向 A 发送暂停事务，等应用上报暂停完成后才恢复下一个 Activity。Android 13 的 `TaskFragment.startPausing()` 注释明确返回时系统正等待客户端上报暂停完成，`completePause()` 收尾后才触发恢复逻辑；B 的 `onCreate()`、`onStart()`、`onResume()` 都排在这个完成点之后。
2. **耗时后果**：`onPause()` 中的同步 I/O、大对象释放或复杂计算占用的是两页切换的关键路径，B 的首帧随之延后，用户感知为切换卡顿。
3. **正确做法**：`onPause()` 只做轻量工作，例如停止动画、保存轻量临时状态。较重的资源释放推迟到 `onStop()`，此时 A 已完全不可见，不再阻塞 B 的显示。

**Q13: [learning] 配置变更或低内存导致 Activity 重建时，`onSaveInstanceState()` 的调用时机随 targetSdk 如何变化，状态应在 `onCreate()` 还是 `onRestoreInstanceState()` 中恢复？**

保存时机以 targetSdk 的 API 28（Android 9.0）为分界：达到 28 时固定在 `onStop()` 之后调用，低于 28 时在 `onStop()` 之前、与 `onPause()` 的先后没有保证。恢复推荐 `onRestoreInstanceState()`，它只在确有状态可恢复时回调，参数 Bundle 必有值。

1. **保存时机的版本分界**：targetSdk 达到 API 28 后，`onSaveInstanceState()` 固定在 `onStop()` 之后，应用可以安全地在 `onStop()` 里提交 Fragment 事务。低于 28 时发生在 `onStop()` 之前，无法保证与 `onPause()` 的先后（`Activity.onSaveInstanceState()` 注释口径，已按 Android 13 本地源码核对）。
2. **`onCreate()` 恢复**：正常启动时传入的 Bundle 为 null，必须判空后才能使用；适合恢复不依赖 View 树的业务数据。
3. **`onRestoreInstanceState()` 恢复**：系统只在携带了保存状态时才回调（`ActivityThread.handleStartActivity()` 中 `r.state` 非空才调用），位于 `onStart()` 之后、`onResume()` 之前。无需判空，适合恢复界面相关状态。

需要区分“从未保存”与“有保存状态”时走 `onCreate()` 判空路径。界面状态统一放 `onRestoreInstanceState()` 可以省掉判空样板。

**Q14: [learning] View 层次结构的状态为什么会随 `onSaveInstanceState()` 自动保存，从 Activity 到单个 View 的保存与恢复链路是怎样的？**

Activity 的默认实现把窗口内 View 的状态沿“Activity → Window → 内容容器 → 逐级子 View”的委托链收集，每个设置了 `android:id` 的 View 以 id 为键存入一个 SparseArray，没有 id 的 View 不会被自动保存。

1. **保存链**：`onSaveInstanceState()` 默认实现调用 `mWindow.saveHierarchyState()` 并把结果存入 Bundle（Android 13 `Activity.java` 本地核对）。`PhoneWindow.saveHierarchyState()` 让内容容器执行 `saveHierarchyState()`，另外记录当前焦点 View 的 id 用于恢复焦点。
2. **逐级分发**：`ViewGroup.dispatchSaveInstanceState()` 先保存自身状态，再递归通知每个子 View。`View.dispatchSaveInstanceState()` 只在设置了有效 id 且未通过 `android:saveEnabled` 关闭保存时回调 `onSaveInstanceState()`，以 id 为键写入 SparseArray，子类覆写时不调用父类实现会抛 `IllegalStateException`。
3. **恢复链**：`onRestoreInstanceState()` 的默认实现调用 `mWindow.restoreHierarchyState()`，按同样顺序逐级分发，各 View 用自身 id 从 SparseArray 取回状态。这也决定了恢复必须发生在 View 树构建完成之后，也就是 `onStart()` 之后。
