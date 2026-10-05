# Activity 启动、任务栈与 Intent 匹配

> 学习资料（文章模式沉淀）。主线：启动请求的任务选择与实例复用两条判断、launchMode 与 Intent flag 的叠加语义、Intent Filter 的 action/category/data 匹配规则与包可见性、taskAffinity 与 allowTaskReparenting、冷热启动与实例复用的区分、非 Activity Context 启动的约束、任务栈组合场景推演。AOSP 机制按本地 AAOS13 源码（Android 13）核对（ActivityStarter、TaskFragment、ContextImpl、ActivityTaskSupervisor），版本相关结论按官方文档口径（2026-09 检索）。生命周期与首帧时序见 [01-activity.md](../03-ui/01-activity.md)，四大组件总览见 [01-four-components.md](01-four-components.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] `startActivity()` 如何在任务选择与 Activity 实例复用之间作出决定？**

系统先选目标任务，再决定目标 Activity 是复用还是新建。这是两个相关但不同的判断：任务已被选中，不代表该任务中的任意 Activity 都会被复用。

沿下面的源码路径分析：

1. **规范化启动请求**：结合 Intent、launch flags、目标 Activity 的 launchMode 与 taskAffinity，确定启动参数。
2. **选择目标 Task**：检查现有任务与目标任务匹配条件。被选中的任务可能被带到前台，但其中目标实例是否存在还要单独判断。
3. **处理目标栈**：结合目标实例在栈中的位置、清栈标记和启动模式，决定保留、清除上层 Activity 或复用目标实例。
4. **创建或复用 Activity**：无可复用实例时创建新实例。复用时交付新 Intent 并可能调用 `onNewIntent()`。把最终分支与实际生命周期回调对应起来。

具体 Android 版本决定内部实现。`startActivityInner()` 等方法名和分支会变化，不是稳定 SDK 契约。

**Q2: [learning] `FLAG_ACTIVITY_NEW_TASK`、`SINGLE_TOP` 与 `CLEAR_TOP` 如何改变 Activity 启动？**

这三个 Intent flag 分别影响目标任务选择与目标实例所在栈的处理，效果还要和目标 Activity 的 launchMode、启动方及现有任务一起判断。

1. `FLAG_ACTIVITY_NEW_TASK`：要求系统在任务上下文中启动 Activity。系统会尝试选择合适的已有任务。没有可复用任务时才创建新任务。
2. `FLAG_ACTIVITY_SINGLE_TOP`：目标实例已位于所选任务栈顶时复用它，并向 `onNewIntent()` 交付新 Intent。目标实例不在栈顶时通常创建新实例。
3. `FLAG_ACTIVITY_CLEAR_TOP`：在目标实例所在任务中清除它上方的 Activity。目标实例是否复用、收到 `onNewIntent()` 还是重新创建，还取决于 launchMode 及是否同时使用 `SINGLE_TOP`。对 `standard` 目标且未加 `SINGLE_TOP` 时，目标实例自身也随上方页面一起出栈销毁并重建新实例；加上 `SINGLE_TOP` 才改为通过 `onNewIntent()` 复用现有实例。

判断结果时分别确认所选任务、目标实例在栈中的位置和最终回调。不能只依据单个 flag 名称推断完整返回栈。

**Q3: [learning] Launcher Activity 的 Intent Filter 应声明什么？**

应用启动器通过 MAIN action 与 LAUNCHER category 识别可显示的应用入口。该 filter 表达入口发现语义，不会单独保证 Activity 一定能启动。

1. **action**：在 Intent Filter 中声明 `android.intent.action.MAIN`，表示应用主入口。
2. **category**：声明 `android.intent.category.LAUNCHER`，让启动器将组件作为应用图标入口展示。
3. **组件可启动条件**：目标 Activity 必须允许启动器从外部访问。对 targetSdk 31 及以上且声明了 Intent Filter 的组件，必须显式设置 `android:exported`。Launcher 入口通常需设为 `true`，否则其他应用无法启动它。还要满足组件权限等限制。

**Q4: [learning] Intent Filter 中的 action、category 与 data 分别匹配什么？**

Intent Filter 按操作、类别和数据三组条件匹配隐式 Intent。三组约束共同决定候选组件，不能彼此替代。

1. **action**：表达请求执行的操作。Intent 指定 action 时，filter 必须包含相同 action 才能匹配，比较是精确字符串匹配、区分大小写。Intent 未指定 action 时框架跳过 action 维度比对（Android 13 的 `IntentFilter.match` 对 action 为 null 不做 action 测试），能否命中由 category 与 data 维度决定。
2. **category**：表达候选组件需要满足的类别。Intent 实际携带的每个 category 都必须在 filter 中声明。filter 中额外声明的 category 不要求 Intent 全部携带。
3. **data**：按 URI 与 MIME type 条件匹配。检查 scheme、host、path 和 MIME type。filter 如何声明这些条件会决定可匹配范围。

通过 `startActivity()` 解析隐式 Intent 时，候选 filter 通常还必须声明 `CATEGORY_DEFAULT`。显式 Intent 或使用其他查询 flags 的场景有不同规则。

**Q5: [learning] 隐式 Intent 如何通过 Intent Filter 找到可启动的 Activity？**

系统把隐式 Intent 与已安装组件的 Intent Filter 比较，筛出符合条件的候选，再按解析结果选择启动对象。匹配成功不等于启动必然成功，也不保证只有一个候选。

1. **匹配输入**：解析器检查 Intent 的 action、data（URI 与 MIME type）和 category 与候选 filter 的组合是否符合规则。Intent 携带的每个 category 都必须被 filter 接受。
2. **候选结果**：一个 Activity 可以声明多个 filter，每个 filter 独立表示一组条件。系统可能找到多个候选、没有候选，或显示系统选择器。导出状态、权限和目标平台规则还可能阻止启动。
3. **查询全部候选**：`PackageManager.queryIntentActivities(intent, flags)` 返回符合条件的 Activity。`intent` 是待解析对象，`flags` 控制查询范围。`MATCH_DEFAULT_ONLY` 只保留声明 `CATEGORY_DEFAULT` 的候选。Android 13 及以上 SDK 还提供类型化 flags 重载，具体重载按编译 SDK 确认。包可见性限制也会影响应用能查询到的结果。
4. **解析最佳候选**：`resolveActivity()` 返回系统解析出的结果。若有多个可选项，返回值也可能代表系统 Resolver，而不是唯一的业务目标。

调用方只表达操作意图、无需绑定某个实现类时适合隐式 Intent。对查询结果应结合包可见性、导出状态、权限和实际启动结果判断。

**Q6: [learning] 详情页返回后直接退出应用，是否由 `singleTask` 清理了返回栈？**

`singleTask` 会让目标 Activity 在匹配的任务中以根 Activity 形式复用。系统清除其上方页面后，按返回可能直接离开该任务。先检查目标 launchMode 和任务栈，再判断是否是该行为导致。

常见 launchMode 的差异如下：

1. `standard`：默认模式，每次启动通常创建新实例。
2. `singleTop`：目标实例已在栈顶时复用，否则创建新实例。
3. `singleTask`：在匹配任务中复用根 Activity，并清除其上方页面。
4. `singleInstance`：Activity 独占自己的任务，任务栈中不放入其他 Activity。
5. `singleInstancePerTask`：API 31 起提供。实例作为任务根页面，一个任务至多一个该模式实例，但可在满足 `NEW_DOCUMENT` 或 `MULTIPLE_TASK` 等条件时存在于不同任务。

Intent flags 也会影响启动结果。若页面需要普通的页面内返回栈，不要只为复用实例而随意设置 `singleTask`。

**Q7: [learning] Activity 复用后收到新 Intent，为什么 `getIntent()` 仍是旧值？**

当启动规则复用已有 Activity 实例时，系统通过 `onNewIntent()` 交付新 Intent，但不会自动替应用更新 Activity 保存的 Intent 字段，因此 `getIntent()` 仍可能返回最初启动时的 Intent。

1. **复用条件**：例如 `singleTop` 命中栈顶实例、`singleTask` 复用任务中的目标实例，或 flags 使目标实例被复用。具体是否复用还取决于任务与栈状态。
2. **回调差异**：复用实例时调用 `onNewIntent()`，不会重新调用该实例的 `onCreate()`。若系统创建新实例，则走新的创建生命周期。
3. **同步业务输入**：把回调参数作为新的业务输入处理。需要后续 `getIntent()` 返回新值时，在回调里显式调用 `setIntent(intent)`。否则 Activity 自身仍保存旧 Intent。

**Q8: [learning] 跨应用启动后 Activity 跑进了意外的任务栈，`taskAffinity` 与 `allowTaskReparenting` 起什么作用？**

`taskAffinity` 表达 Activity 倾向加入哪个任务，`allowTaskReparenting` 允许系统在相应任务再次到前台时，把 Activity 从启动它的任务迁到 affinity 匹配的任务。两项都不能单独决定完整启动结果。

1. **默认 affinity**：Activity 未设置 `taskAffinity` 时继承 `<application>` 的值。应用也未设置时，默认使用应用 namespace。设为空字符串表示不偏好任何任务。
2. **默认 reparenting**：`allowTaskReparenting` 的默认值为 `false`，此时 Activity 留在启动它的任务。只有属性设置为 `true` 且出现适用任务切换时，才可能迁移。
3. **适用模式**：官方清单语义将重新归属限制在 `standard` 与 `singleTop` 模式。`singleTask` 和 `singleInstance` Activity 作为任务根，不按该方式 reparent。
4. **运行时核对**：检查启动方、`NEW_TASK` 等 flags、目标 launchMode、两侧 affinity 和当前任务栈。记录实际 task 归属，不依赖固定 `taskId` 或唯一返回路径。

**Q9: [learning] AAOS Launcher 设置 `FLAG_ACTIVITY_NEW_TASK` 后，系统是否一定新建任务？**

不一定。该 flag 要求 Activity 在任务上下文中启动，但系统仍会匹配可复用的任务。只有没有合适的已有任务时，才会创建新任务。

判断时按启动过程区分三个层次：

1. **启动语义**：Launcher 从自身任务启动目标 Activity 时设置 `FLAG_ACTIVITY_NEW_TASK`，让系统按任务栈规则处理目标 Activity。
2. **任务匹配**：system_server 结合目标组件、`taskAffinity`、`launchMode`、已有任务和其他 Intent flag 查找可复用任务。找到匹配任务时，系统可能将其带到前台并复用其中的 Activity。
3. **实际结果**：任务复用不代表目标 Activity 实例一定复用。是否创建新实例或通过 `onNewIntent()` 接收请求，还要看任务栈状态、启动模式和具体 flag。

所以 `NEW_TASK` 不能解释为“每次都创建一个全新任务”，也不能单凭它推断应用进程会重启。

**Q10: [learning] 从 Launcher 点击图标时，为什么目标进程已存活仍可能创建新的 Activity？**

冷启动与热启动描述的是目标应用进程是否需要创建。Activity 是否复用则是另一个由任务栈和启动规则决定的问题。进程热启动不保证复用某个 Activity 实例。

分析时分别检查两个状态：

1. **进程状态**：目标进程不存在时，系统需要启动进程并初始化 `Application`。目标进程仍存活时，可以复用该进程。
2. **Activity 状态**：系统依据目标任务、Activity 实例、`launchMode` 和 Intent flag 决定复用现有实例、调用 `onNewIntent()`，或创建新的 Activity 实例。

AAOS 13 的 `ActivityTaskSupervisor.startSpecificActivity()` 体现了进程分支：目标进程可用时调用 `realStartActivityLocked()`。否则走异步进程启动路径。排查启动后“回到旧页面”“新建了 Activity”或“没有看到新进程”时，应把任务复用、Activity 实例复用和进程启动分开判断。

**Q11: [learning] 在 Application 或 Service 等 Context 里调用 `startActivity()` 为什么抛 `AndroidRuntimeException`，应该怎么改？**

非 Activity 上下文不属于任何任务，框架无法决定新 Activity 的落点，因此要求 Intent 显式携带 `FLAG_ACTIVITY_NEW_TASK`，让系统按 taskAffinity 选择目标任务，找不到再新建。

1. **报错条件**：Android 13 的 `ContextImpl.startActivity()` 在 Intent 未带 `FLAG_ACTIVITY_NEW_TASK`、`ActivityOptions` 也未指定目标任务 id 时抛出 `AndroidRuntimeException`，文案即 "Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag"。
2. **版本豁免**：targetSdk 介于 Android N 与 O MR1 之间时不抛出，源码注释说明这是为兼容该区间已存在的历史 bug 而保留；其余 targetSdk 均受此检查约束。
3. **加上 flag 后的行为**：系统按目标 Activity 的 taskAffinity 查找可复用任务，复用规则与从 Activity 发起时相同。但新页面不在调用方的返回栈里，评估按返回的行为时要按任务归属单独判断。
4. **Activity 为何不受限**：Activity 重写了 `startActivity()`，经 `Instrumentation` 发起启动并携带自身所属任务信息，不需要额外 flag。

**Q12: [learning] 为 Activity 指定启动行为时，清单里的 `android:launchMode` 与 Intent flag 各能表达什么，两者同时设置时如何生效？**

两种方式可以并用，系统解析启动请求时把清单的静态声明与本次 Intent 携带的 flag 一起判断：launchMode 描述该 Activity 的固定归属规则，flag 描述这一次启动的动态行为。

1. 清单 `android:launchMode`：取值覆盖 `standard`、`singleTop`、`singleTask`、`singleInstance`，API 31 起增加 `singleInstancePerTask`，作用于该 Activity 的每一次启动。它表达不了 `FLAG_ACTIVITY_CLEAR_TOP` 这类清栈动作，没有对应的取值。
2. **Intent flag**：如 `FLAG_ACTIVITY_NEW_TASK`、`FLAG_ACTIVITY_SINGLE_TOP`、`FLAG_ACTIVITY_CLEAR_TOP`，只在本次启动生效，可以组合。它表达不了 `singleInstance` 这类模式，没有对应的 flag。
3. **同时设置的生效方式**：两者叠加解析而非二选一，清单为 `standard` 的 Activity 加上 `FLAG_ACTIVITY_SINGLE_TOP` 同样获得栈顶复用。“flag 优先级更高”的常见说法应理解为动态行为叠加在静态模式之上，但 flag 改变不了 `singleInstance` 的独占任务语义。
4. **选择**：要求所有入口都遵循同一模式时写清单。只想对特定路径（如通知跳转）改变行为时用 flag。

**Q13: [learning] 前台任务栈为 A、B，后台任务栈为 C、D（C、D 均为 `singleTask` 且声明了与包名不同的同一 `taskAffinity`），从 B 启动已存在的 D 后连续按返回，回退顺序是什么？**

D 所需的任务已经存在，系统把整个后台任务连同 C 一起带到前台，而不是把 D 压入当前任务。合并后的回退顺序是 D → C → B → A，最后回到桌面。

1. **任务整体切换**：D 为 `singleTask`，其 `taskAffinity` 对应的后台任务已存在，启动时系统按 affinity 在全区查找可复用任务（AAOS 13 的 `ActivityStarter` 即此逻辑），把它整体移到前台，C 仍留在 D 下方。
2. **跨任务返回**：按返回先在当前任务内逐级出栈，当前任务清空后才回落到上一个任务，所以 D、C 依次出栈后才回到 B、A。
3. **边界**：若 D 尚不存在，系统按该 affinity 新建任务并以 D 为根，任务里只有 D，返回一次就直接回到 B。

判断规则：`singleTask` 启动的复用粒度是任务——先按 affinity 定位并前置整个任务，再谈实例复用与新 Intent 交付。

**Q14: [learning] 同一应用内 A 为 `standard`，B、C 均为 `singleTask` 且未修改 `taskAffinity`，依次执行 A 启动 B、B 启动 C、C 启动 A、A 再次启动 B，返回栈如何变化，连按两次返回停在哪个界面？**

默认 `taskAffinity` 就是应用包名，B、C 所需的任务与 A 所在任务相同，因此全部进入同一个任务而不新建任务。A 再次启动 B 时复用栈内实例并清除其上方页面，连按两次返回先回到 A，再回到桌面。

1. **A 启动 B**：B 所需任务已存在，B 直接进入当前任务，栈变为 A、B。
2. **B 启动 C**：同理 C 入当前任务，栈变为 A、B、C。
3. **C 启动 A**：A 是 `standard`，进入启动者所在的任务，栈变为 A、B、C、A。
4. **A 再次启动 B**：B 已在栈内，`singleTask` 复用该实例并销毁其上方页面，C 与第二个 A 出栈，B 通过 `onNewIntent()` 收到新 Intent，栈回到 A、B。
5. **两次返回**：第一次出栈 B 回到 A，第二次出栈 A 后任务清空，回到桌面。

该场景的判断规则：`singleTask` 是否新建任务取决于 affinity 对应任务是否已存在，复用栈内实例时默认顺带清除其上方页面。
