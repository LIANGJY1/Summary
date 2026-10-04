# Activity 与窗口生命周期

> 学习资料（文章模式沉淀）。主线：Activity 启动到首帧的完整时序、四个"可观测时点"能承诺什么、setContentView 与 DecorView 的分工、透明主题的尺寸陷阱、Configuration 变更的两条路径与 relaunch 语义、状态保存时机、启动模式与任务栈、切换动画期间的双窗口、首帧与启动度量、可见性与内存回调、车机多用户多显示下的归属差异。AOSP 机制按本地 AAOS13 源码（Android 13）核对（ActivityThread.java、`core/java/android/internal/policy/PhoneWindow.java`、ViewRootImpl.java），版本相关结论按官方文档口径（2026-09 检索）。系统侧 relayout 与 insets 见 [04-window-system.md](04-window-system.md)，启动优化手段见[启动优化](../15-performance/07-app-startup-optimization.md)，ANR 契约见 [ANR](../15-performance/08-anr.md)。Q 序列即结构，供 atlas 同源直读。

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

**Q3: Android 内存紧张时哪些应用进程更容易被系统回收？**

系统依据进程中最重要的活动组件及其与用户交互的关系评估重要性。当前前台交互进程通常最重要，纯缓存后台进程通常最容易在内存压力下被结束。中间等级由可见窗口、正在运行的 Service 等因素决定。

常见重要性大致从高到低如下，具体 LMK/缓存回收顺序与 OEM 实现会变化，不能据此承诺保活时长：

1. **前台进程**：承载用户当前交互的 Activity 或执行重要前台工作，系统通常尽量保留。
2. **可见进程**：Activity 可见但未获得焦点，例如被其他窗口部分遮挡，重要性通常低于当前交互进程。
3. **服务进程**：正在提供用户仍依赖的服务时，可能比纯后台缓存进程更重要。实际等级取决于服务状态和关联组件。
4. **缓存进程**：没有当前可见或活动组件的进程，在需要内存时更容易被回收。

缓存进程可能被直接结束而不回调 `onDestroy()`。应在合适时机保存轻量界面状态。持续后台工作交给系统认可的任务或服务机制，而不要依赖某个进程长时间存活。

**Q4: `startActivity()` 如何在任务选择与 Activity 实例复用之间作出决定？**

系统先选目标任务，再决定目标 Activity 是复用还是新建。这是两个相关但不同的判断：任务已被选中，不代表该任务中的任意 Activity 都会被复用。

沿下面的源码路径分析：

1. **规范化启动请求**：结合 Intent、launch flags、目标 Activity 的 launchMode 与 taskAffinity，确定启动参数。
2. **选择目标 Task**：检查现有任务与目标任务匹配条件。被选中的任务可能被带到前台，但其中目标实例是否存在还要单独判断。
3. **处理目标栈**：结合目标实例在栈中的位置、清栈标记和启动模式，决定保留、清除上层 Activity 或复用目标实例。
4. **创建或复用 Activity**：无可复用实例时创建新实例。复用时交付新 Intent 并可能调用 `onNewIntent()`。把最终分支与实际生命周期回调对应起来。

具体 Android 版本决定内部实现。`startActivityInner()` 等方法名和分支会变化，不是稳定 SDK 契约。

**Q5: `FLAG_ACTIVITY_NEW_TASK`、`SINGLE_TOP` 与 `CLEAR_TOP` 如何改变 Activity 启动？**

这三个 Intent flag 分别影响目标任务选择与目标实例所在栈的处理，效果还要和目标 Activity 的 launchMode、启动方及现有任务一起判断。

1. **`FLAG_ACTIVITY_NEW_TASK`**：要求系统在任务上下文中启动 Activity。系统会尝试选择合适的已有任务。没有可复用任务时才创建新任务。
2. **`FLAG_ACTIVITY_SINGLE_TOP`**：目标实例已位于所选任务栈顶时复用它，并向 `onNewIntent()` 交付新 Intent。目标实例不在栈顶时通常创建新实例。
3. **`FLAG_ACTIVITY_CLEAR_TOP`**：在目标实例所在任务中清除它上方的 Activity。目标实例是否复用、收到 `onNewIntent()` 还是重新创建，还取决于 launchMode 及是否同时使用 `SINGLE_TOP`。

判断结果时分别确认所选任务、目标实例在栈中的位置和最终回调。不能只依据单个 flag 名称推断完整返回栈。

**Q6: Launcher Activity 的 Intent Filter 应声明什么？**

应用启动器通过 MAIN action 与 LAUNCHER category 识别可显示的应用入口。该 filter 表达入口发现语义，不会单独保证 Activity 一定能启动。

1. **action**：在 Intent Filter 中声明 `android.intent.action.MAIN`，表示应用主入口。
2. **category**：声明 `android.intent.category.LAUNCHER`，让启动器将组件作为应用图标入口展示。
3. **组件可启动条件**：目标 Activity 必须允许启动器从外部访问。对 targetSdk 31 及以上且声明了 Intent Filter 的组件，必须显式设置 `android:exported`。Launcher 入口通常需设为 `true`，否则其他应用无法启动它。还要满足组件权限等限制。

**Q7: Intent Filter 中的 action、category 与 data 分别匹配什么？**

Intent Filter 按操作、类别和数据三组条件匹配隐式 Intent。三组约束共同决定候选组件，不能彼此替代。

1. **action**：表达请求执行的操作。Intent 指定 action 时，filter 必须包含相同 action 才能匹配。若 Intent 没有 action，filter 也必须没有 action 才通过这一维度。
2. **category**：表达候选组件需要满足的类别。Intent 实际携带的每个 category 都必须在 filter 中声明。filter 中额外声明的 category 不要求 Intent 全部携带。
3. **data**：按 URI 与 MIME type 条件匹配。检查 scheme、host、path 和 MIME type。filter 如何声明这些条件会决定可匹配范围。

通过 `startActivity()` 解析隐式 Intent 时，候选 filter 通常还必须声明 `CATEGORY_DEFAULT`。显式 Intent 或使用其他查询 flags 的场景有不同规则。

**Q8: 隐式 Intent 如何通过 Intent Filter 找到可启动的 Activity？**

系统把隐式 Intent 与已安装组件的 Intent Filter 比较，筛出符合条件的候选，再按解析结果选择启动对象。匹配成功不等于启动必然成功，也不保证只有一个候选。

1. **匹配输入**：解析器检查 Intent 的 action、data（URI 与 MIME type）和 category 与候选 filter 的组合是否符合规则。Intent 携带的每个 category 都必须被 filter 接受。
2. **候选结果**：一个 Activity 可以声明多个 filter，每个 filter 独立表示一组条件。系统可能找到多个候选、没有候选，或显示系统选择器。导出状态、权限和目标平台规则还可能阻止启动。
3. **查询全部候选**：`PackageManager.queryIntentActivities(intent, flags)` 返回符合条件的 Activity。`intent` 是待解析对象，`flags` 控制查询范围。`MATCH_DEFAULT_ONLY` 只保留声明 `CATEGORY_DEFAULT` 的候选。Android 13 及以上 SDK 还提供类型化 flags 重载，具体重载按编译 SDK 确认。包可见性限制也会影响应用能查询到的结果。
4. **解析最佳候选**：`resolveActivity()` 返回系统解析出的结果。若有多个可选项，返回值也可能代表系统 Resolver，而不是唯一的业务目标。

调用方只表达操作意图、无需绑定某个实现类时适合隐式 Intent。对查询结果应结合包可见性、导出状态、权限和实际启动结果判断。

**Q9: 启动页该等 `onWindowFocusChanged()` 还是首帧回调再关闭？**

`onWindowFocusChanged(true)` 只表示窗口获得输入焦点，不承诺窗口内容已经绘制或提交到显示合成链路。需要避免启动遮罩早于内容消失时，应等内容首帧对应的缓冲区提交，再移除遮罩。这仍不等于面板已经完成物理扫描显示。

四个可观测时点的承诺强度不同：

1. **`onResume()`**：Activity 进入 resumed 状态，可以处理前台生命周期工作。不承诺像素已绘制。
2. **`onPostResume()`**：框架在 `onResume()` 之后回调。仍不承诺首帧已经绘制。
3. **`onWindowFocusChanged(true)`**：窗口取得输入焦点。它不等于首帧已绘制，也不保证用户已经看到新内容。
4. **首帧缓冲区提交**：例如 API 29 起可用的 `registerFrameCommitCallback()` 可观察 ViewRoot 的帧缓冲提交。提交之后 SurfaceFlinger 仍需合成并显示。若要判断实际呈现时刻，应使用帧时间线或 presentation timestamp 等显示侧证据。

因此，焦点变化适合处理输入焦点语义。以新内容替换启动遮罩时，应按所需的可见性保证选择帧提交或实际呈现时点。

**Q10: setContentView 后应用内容为什么不直接成为窗口根 View？**

PhoneWindow 是应用侧 Window 的实现，负责生成并持有 DecorView、解析窗口主题属性并保存窗口参数（`core/java/android/internal/policy/PhoneWindow.java` 本地核对）。DecorView 是窗口根 ViewGroup，内部包含由框架管理的窗口部件，以及承载应用内容的 ContentFrameLayout。

setContentView 把应用布局放进内容容器。需要调整状态栏背景、标题栏等框架部件时，应使用对应主题属性，而不是直接修改 DecorView 内部控件。

**Q11: 在 onCreate 里读取根 View 宽高得到 0，应该等到哪个时机？**

onCreate 执行时根 View 通常还没有完成首次 layout，因此 getWidth() / getHeight() 可能仍为 0。透明或无标题主题本身不会让 View 提前得到尺寸。

读取实际布局尺寸时可选：

1. **布局完成后读取**：在根 View 上注册 `OnLayoutChangeListener`，首次布局回调中读取宽高。只需读取一次时，在回调里移除监听器。
2. **绘制前读取**：使用 `doOnPreDraw` 等一次性预绘制回调，在首帧绘制前读取已经确定的布局尺寸。

**Q12: 旋转屏幕或切换深色模式后，Activity 为什么有时回调、有时重建？**

系统根据 Activity 是否声明自行处理某类配置变化，决定调用 `onConfigurationChanged()` 还是销毁并重建 Activity。未在 `android:configChanges` 中覆盖的变化通常会触发重建。该属性只应列出 Activity 确实能自行适配的配置类型。

1. **声明处理的变化**：例如 `orientation` 表示屏幕方向变化，`screenSize` 表示可用屏幕尺寸变化，`uiMode` 可包含夜间模式变化。当 targetSdk 达到 API 13 时，方向变化通常还需同时处理 `screenSize`。
2. **未声明的变化**：若清单没有声明相应配置，系统通常通过 Activity relaunch 让新实例加载新资源与配置。AAOS 13 源码路径经过 `ActivityThread.handleRelaunchActivity()` 与 `handleRelaunchActivityInner()`。
3. **进程边界**：系统配置 relaunch 和应用调用 `Activity.recreate()` 都会创建新的 Activity 实例，但它们本身不等于重启应用进程。不要依赖 Activity 实例字段保留状态，也不要以异步任务是否存活来区分两种路径。

配置项名称与变化覆盖关系以应用 targetSdk 和目标 Android 版本的 Activity manifest 文档为准。

**Q13: 进程被系统回收后页面状态丢失，onSaveInstanceState 哪些内容需要自己保存？**

系统预计 Activity 可能被销毁并恢复时，会调用 onSaveInstanceState 保存轻量的临时界面状态。用户按返回结束页面或应用调用 finish() 时，系统不保证调用它（Android 官方文档口径）。因此它不适合保存必须持久化的业务数据。

状态丢失常见于：

1. **只依赖 View 自动保存**：自定义字段需要显式写入 Bundle。需要 View 层状态时还要调用父类实现。
2. **异步保存**：回调返回前状态快照可能已经完成，恢复时拿不到尚未写入的数据。
3. **只放在静态字段或单例**：进程死亡后内存状态会一并消失。

把轻量临时状态写入 Bundle，在 onCreate 或 onViewCreated 恢复。用户数据与必须持久化的业务状态应由数据层保存。

**Q14: 详情页返回后直接退出应用，是否由 `singleTask` 清理了返回栈？**

`singleTask` 会让目标 Activity 在匹配的任务中以根 Activity 形式复用。系统清除其上方页面后，按返回可能直接离开该任务。先检查目标 launchMode 和任务栈，再判断是否是该行为导致。

常见 launchMode 的差异如下：

1. **`standard`**：默认模式，每次启动通常创建新实例。
2. **`singleTop`**：目标实例已在栈顶时复用，否则创建新实例。
3. **`singleTask`**：在匹配任务中复用根 Activity，并清除其上方页面。
4. **`singleInstance`**：Activity 独占自己的任务，任务栈中不放入其他 Activity。
5. **`singleInstancePerTask`**：API 31 起提供。实例作为任务根页面，一个任务至多一个该模式实例，但可在满足 `NEW_DOCUMENT` 或 `MULTIPLE_TASK` 等条件时存在于不同任务。

Intent flags 也会影响启动结果。若页面需要普通的页面内返回栈，不要只为复用实例而随意设置 `singleTask`。

**Q15: Activity 复用后收到新 Intent，为什么 `getIntent()` 仍是旧值？**

当启动规则复用已有 Activity 实例时，系统通过 `onNewIntent()` 交付新 Intent，但不会自动替应用更新 Activity 保存的 Intent 字段，因此 `getIntent()` 仍可能返回最初启动时的 Intent。

1. **复用条件**：例如 `singleTop` 命中栈顶实例、`singleTask` 复用任务中的目标实例，或 flags 使目标实例被复用。具体是否复用还取决于任务与栈状态。
2. **回调差异**：复用实例时调用 `onNewIntent()`，不会重新调用该实例的 `onCreate()`。若系统创建新实例，则走新的创建生命周期。
3. **同步业务输入**：把回调参数作为新的业务输入处理。需要后续 `getIntent()` 返回新值时，在回调里显式调用 `setIntent(intent)`。否则 Activity 自身仍保存旧 Intent。

**Q16: 跨应用启动后 Activity 跑进了意外的任务栈，`taskAffinity` 与 `allowTaskReparenting` 起什么作用？**

`taskAffinity` 表达 Activity 倾向加入哪个任务，`allowTaskReparenting` 允许系统在相应任务再次到前台时，把 Activity 从启动它的任务迁到 affinity 匹配的任务。两项都不能单独决定完整启动结果。

1. **默认 affinity**：Activity 未设置 `taskAffinity` 时继承 `<application>` 的值。应用也未设置时，默认使用应用 namespace。设为空字符串表示不偏好任何任务。
2. **默认 reparenting**：`allowTaskReparenting` 的默认值为 `false`，此时 Activity 留在启动它的任务。只有属性设置为 `true` 且出现适用任务切换时，才可能迁移。
3. **适用模式**：官方清单语义将重新归属限制在 `standard` 与 `singleTop` 模式。`singleTask` 和 `singleInstance` Activity 作为任务根，不按该方式 reparent。
4. **运行时核对**：检查启动方、`NEW_TASK` 等 flags、目标 launchMode、两侧 affinity 和当前任务栈。记录实际 task 归属，不依赖固定 `taskId` 或唯一返回路径。

**Q17: Activity 切换时短暂看到前后两个页面，窗口动画期间发生了什么？**

某些窗口转场会让旧 Activity 的窗口内容与新 Activity 的首帧在一段时间内都参与显示合成，因此画面可能短暂重叠。它不是两个 Activity 同时处于 resumed 状态的证明。

1. **内容来源**：旧窗口可能在 Activity 开始退出后仍保留已提交的 Surface 内容，新 Activity 则创建自己的窗口并提交首帧。
2. **画面变化**：窗口转场按动画进度调整位置、透明度或层级，SurfaceFlinger 合成两侧的缓冲区。
3. **透出与闪烁**：新 Activity 使用透明主题或无背景时，动画可能透出旧窗口。如出现闪烁，还要检查窗口背景、Surface 提交时机和过渡动画配置。

对话框若在过渡期间出现，也要结合窗口类型、显示时序和目标显示检查，不能仅从 Activity 回调顺序判断画面归属。

**Q18: 启动指标显示首帧很快但页面仍是骨架，首帧与 reportFullyDrawn 分别表示什么？**

首帧表示界面已开始绘制。reportFullyDrawn() 表示应用认为关键内容已可用，并向系统报告启动完成（Android 官方文档口径）。两者应按各自语义采集：首帧可能只显示占位骨架，不能代替“主要内容已可用”的指标。

**Q19: 应用退到后台后收到 `onTrimMemory()`，哪些状态可以释放？**

收到 `TRIM_MEMORY_UI_HIDDEN` 表示应用 UI 已不再可见，可释放只服务于当前 UI 且能重建的资源，例如位图或动画缓存。它不表示进程已经被杀，也不应清除必须恢复的用户状态。

1. **可释放资源**：释放可以从磁盘、网络缓存或模型数据重建的 UI 缓存。后台仍在执行的用户任务可降低缓存占用，但不能破坏任务状态。
2. **必须保留的数据**：跨进程死亡仍需保留的业务数据写入持久化存储。轻量临时 UI 状态用实例状态机制保存。
3. **版本边界**：Android 14 起系统不再发送多个旧的运行期与内存压力 trim 等级，Android 15 起这些常量已弃用。`TRIM_MEMORY_UI_HIDDEN` 与 `TRIM_MEMORY_BACKGROUND` 仍是当前文档强调的两类信号。应按实际系统版本处理回调，不能期待完整旧等级序列。

**Q20: 车机多用户、多显示时页面显示在错误屏幕，Activity 归属由什么决定？**

Activity 的运行归属同时涉及 Android 用户与 Display：Activity 在所属用户的应用进程和状态空间中运行，并被系统放置到某个显示区域。车机的 occupant zone（座位区）可以把乘员用户与显示器关联，因此同一时刻可能存在多个可见或活动的用户上下文。

1. **用户维度**：确认发起启动的用户、目标用户及目标 Activity 是否允许在该用户下运行。系统用户、当前驾驶员和乘员用户并不是同一个 UID/用户空间。
2. **显示维度**：确认启动请求的目标 Display、Activity 任务当前所在 Display，以及该显示是否映射到预期 occupant zone。不要默认 Activity 一定在默认显示上。
3. **资源与尺寸**：从当前 Activity 的用户和显示上下文获取资源、密度与窗口尺寸。不能用默认显示结果推断其他显示布局。

车机的 occupant zone 策略由 AAOS 配置与平台服务决定。同一时刻有多个可见用户，不代表 Android 全局只有一个当前用户的约束消失。

**Q21: Activity 生命周期回调里哪些时序操作容易造成白屏、旧界面更新或闪烁？**

这类问题通常由重活占用首帧、生命周期外仍执行回调，或额外窗口干扰转场造成。排查时先把操作绑定到正确时点，并让异步结果服从当前 Activity/视图生命周期。

1. **在 `onResume()` 做重活**：同步 I/O、图片解码或长计算会占用主线程首帧预算。`doOnPreDraw` 在当前帧绘制前执行，在其中启动重活仍可能推迟首帧。把重活放到后台线程，并在首帧之后按需更新 UI。
2. **延迟任务跨入后台**：`postDelayed` 回调在 Activity 停止后仍可能运行并触碰界面。使用可取消句柄，在 `onStop()` 取消不再需要的任务，或通过生命周期感知的调度收敛回调。
3. **首帧前显示 Toast/Dialog**：额外窗口会改变层级或 Insets 条件，使首帧和窗口过渡更难判断。确认它确实需要在首帧前显示，并核对窗口类型和时机。
4. **在 `onPause()` 提交 UI 变化**：Activity 已失去前台交互，此时修改的布局可能很快不可见，也可能与窗口动画竞争。将可见性相关变更放到合适的 resumed 状态。
5. **异步结果写入旧实例**：配置重建或视图销毁后，旧回调仍可能持有旧 Activity/View。让界面观察生命周期感知的数据，并在结果应用前确认当前 owner 有效。

**Q22: Fragment 返回后旧 View 仍被观察者更新，视图生命周期该怎么绑定？**

Fragment 实例可能比它创建的 View 树活得更久，因此视图观察者必须绑定到 `viewLifecycleOwner`，并在视图销毁时解除直接的 View 引用。将观察者绑定到 Fragment 自身会让旧视图在 `onDestroyView()` 后继续被引用。

1. **生命周期边界**：AndroidX Fragment 1.2.0 起提供独立的视图生命周期。常见显示路径包含 `onAttach()`、`onCreate()`、`onCreateView()`、`onViewCreated()`、`onStart()` 和 `onResume()`。退出时视图先在 `onDestroyView()` 销毁，Fragment 实例可能随后才 `onDestroy()`、`onDetach()`。
2. **视图初始化**：在 `onViewCreated()` 绑定 View、初始化仅属于视图的观察者。观察 LiveData 等生命周期数据时使用 `getViewLifecycleOwner()`。
3. **清理旧引用**：在 `onDestroyView()` 清空 ViewBinding 和其他 View 引用。视图重建后重新绑定新 View，避免旧观察者泄漏并让新视图收不到数据更新。

回退栈和 ViewPager2 等场景可能保留 Fragment 实例而销毁 View。具体回调取决于导航和宿主状态，不应把示例顺序当成每条路径都必须完整经过的固定序列。

**Q23: 切换 Fragment 页面后状态与内存表现不同，`show/hide`、`replace`、ViewPager2 有什么差别？**

三种方式在视图保留、Fragment 实例保留和回退语义上不同，选型应看返回时是否要保留现有 View 树，以及可接受的内存成本。

1. **`show()` / `hide()`**：只改变已添加 Fragment View 的可见性，不触发 Fragment 生命周期回调。页面切换快且状态保留，但隐藏页的 View 树通常仍占内存。
2. **`replace()`**：移除容器中的旧 Fragment 并加入新实例。不加入回退栈时，旧 Fragment 会按移除路径销毁。加入回退栈时，旧视图销毁而实例状态可随 back stack 保留，弹栈时再恢复。
3. **ViewPager2 + `FragmentStateAdapter`**：Adapter 管理 Fragment 实例与保存状态。离当前页面较远的项可被销毁并保存状态，回到该项时再创建 Fragment。近邻页面的保留受 offscreen page limit 和 RecyclerView 回收行为影响，不能断言所有离屏页都立即销毁 View。
4. **选择**：高频平级切换可用 `show/hide`（接受多份 View 常驻）或 ViewPager2。需要明确导航返回语义时使用 `replace` 与 back stack。切回来状态缺失时，检查是否错误销毁了本应保留的视图或业务状态。

**Q24: 网络回调在 `onSaveInstanceState()` 后提交 Fragment 事务导致崩溃，三个 commit 方法有何边界？**

`commit()` 异步排队执行，`commitNow()` 在当前调用点同步执行，`commitAllowingStateLoss()` 允许在状态已保存后提交但可能让界面状态在恢复时丢失。网络回调不应通过 allowing-state-loss 来掩盖生命周期竞态。

1. **`commit()`**：将事务安排到主线程执行，支持加入 back stack。FragmentManager 已保存宿主状态后仍调用，通常抛出 `IllegalStateException`，因为新事务不在已保存快照中。
2. **`commitNow()`**：在当前调用点同步执行事务，方法返回前完成相应 Fragment 生命周期推进。不能对已调用 `addToBackStack()` 的事务使用，因为同步执行无法按异步回退栈事务保存。
3. **`commitAllowingStateLoss()`**：语义接近异步 `commit()`，但允许状态已保存后执行。若 Activity 随后按旧快照恢复，这次 UI 事务可能丢失，因此只适用于丢失该 UI 变化可接受的场景。
4. **回调处理**：网络结果返回时先确认宿主与当前视图仍处于允许事务的生命周期状态，再提交必要变更。使用该方法的理由是避免把异步竞态误当作可忽略的状态差异。

`onSaveInstanceState()` 之后 FragmentManager 可能已禁止普通事务。具体异常时点与生命周期实现应按 AndroidX 版本核对。

**Q25: 共享 ViewModel 后页面重建却不刷新或旧 View 泄漏，作用域与观察者应绑定谁？**

ViewModel 的 `ViewModelStoreOwner` 决定数据实例存活与共享范围，观察者的 LifecycleOwner 决定何时接收更新。两者应分别选择，不能把数据共享范围误当成 View 生命周期。

1. **Fragment 作用域**：`viewModels()` / `by viewModels()` 以 Fragment 为 owner。实例随该 Fragment 的 ViewModelStore 清理而清除，不会因为单次 `onDestroyView()` 自动清除。
2. **Activity 作用域**：`activityViewModels()` 使用宿主 Activity 作为 owner，适合多个 Fragment 共享。Activity 被永久销毁并清理 ViewModelStore 后，实例才清除。
3. **导航图作用域**：`navGraphViewModels()` 以导航图对应的 back stack entry 为 owner。图对应的回退栈 entry 移除后清理实例。
4. **观察视图状态**：只要观察结果会更新 Fragment 的 View，就绑定 `viewLifecycleOwner`。视图重建后，新 owner 重新观察共享 ViewModel。不要把观察者绑在 Fragment 实例生命周期上。
5. **避免泄漏**：ViewModel 不持有 Fragment、Activity、View 或 ViewBinding 引用。ViewModel 可能比单个视图活得更久，直接引用会把已销毁的视图树留在内存中。

**Q26: AAOS Launcher 设置 `FLAG_ACTIVITY_NEW_TASK` 后，系统是否一定新建任务？**

不一定。该 flag 要求 Activity 在任务上下文中启动，但系统仍会匹配可复用的任务。只有没有合适的已有任务时，才会创建新任务。

判断时按启动过程区分三个层次：

1. **启动语义**：Launcher 从自身任务启动目标 Activity 时设置 `FLAG_ACTIVITY_NEW_TASK`，让系统按任务栈规则处理目标 Activity。
2. **任务匹配**：system_server 结合目标组件、`taskAffinity`、`launchMode`、已有任务和其他 Intent flag 查找可复用任务。找到匹配任务时，系统可能将其带到前台并复用其中的 Activity。
3. **实际结果**：任务复用不代表目标 Activity 实例一定复用。是否创建新实例或通过 `onNewIntent()` 接收请求，还要看任务栈状态、启动模式和具体 flag。

所以 `NEW_TASK` 不能解释为“每次都创建一个全新任务”，也不能单凭它推断应用进程会重启。

**Q27: 从 Launcher 点击图标时，为什么目标进程已存活仍可能创建新的 Activity？**

冷启动与热启动描述的是目标应用进程是否需要创建。Activity 是否复用则是另一个由任务栈和启动规则决定的问题。进程热启动不保证复用某个 Activity 实例。

分析时分别检查两个状态：

1. **进程状态**：目标进程不存在时，系统需要启动进程并初始化 `Application`。目标进程仍存活时，可以复用该进程。
2. **Activity 状态**：系统依据目标任务、Activity 实例、`launchMode` 和 Intent flag 决定复用现有实例、调用 `onNewIntent()`，或创建新的 Activity 实例。

AAOS 13 的 `ActivityTaskSupervisor.startSpecificActivity()` 体现了进程分支：目标进程可用时调用 `realStartActivityLocked()`。否则走异步进程启动路径。排查启动后“回到旧页面”“新建了 Activity”或“没有看到新进程”时，应把任务复用、Activity 实例复用和进程启动分开判断。
