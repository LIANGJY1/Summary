> 本文以本地 `AAOS13_study`（Android 13，commit `f534cc6fa`）中的 `CarLauncher` 与 framework 源码为主。掘金对照文章基于 Android 14 `Launcher3`，用于说明通用启动入口与系统任务决策；具体车机类名、Intent 标志和显示屏参数以 Android 13 `CarLauncher` 为准。

**Q1: 从职责与架构看，AAOS 的 `CarLauncher` 与手机上的 AOSP `Launcher3` 有什么异同？**

两者都提供 Home 入口和应用启动入口，并沿用 Android 的应用与 Activity 模型；AAOS 主要为车内使用情境增加专用体验与平台策略。

两者的主要差异有两个方面：

1. **组件职责**：Android 13 参考实现把 `CarLauncher` 注册为 Home Activity，另用独立的 `AppGridActivity` 展示应用列表；Home 页面还可将地图 Activity 嵌入 `TaskView`，并组合 `HomeCard`。
2. **车载体验**：应用网格需考虑当前用户、目标显示屏、媒体源入口和驾驶中的 UX 限制。手机 AOSP `Launcher3` 则侧重工作区、应用抽屉、文件夹、快捷方式和桌面组件等通用桌面能力。

`CarLauncher` 中的地图卡片和 Home cards 属于 AOSP 参考实现的产品设计，不是所有 AAOS 设备的强制结构；OEM 可以替换或定制 Launcher。面试时应先说明双方共享的 Android 启动机制，再讲车载策略与交互差异，避免把 `Launcher3` 的具体实现说成 AAOS 的必经路径。



**Q2: CarLauncher是什么时候启动的？被谁拉起的？**



**Q3: AAOS `CarLauncher` 如何构造应用网格条目？**

Android 13 参考实现根据当前用户可启动的组件和车载策略构造应用网格；因此网格既不等于设备上全部已安装的包，也不只包含普通 `CATEGORY_LAUNCHER` Activity。

构造过程有三个关键分支：

1. **普通 Activity**：通过 `LauncherApps.getActivityList(null, Process.myUserHandle())` 获取当前用户的启动 Activity，并依据隐藏包、应用类型等配置筛选。不同 Android user 因安装状态或配置不同，可能看到不同条目。
2. **媒体源服务**：单独查询 `MediaBrowserService`。AAOS 媒体应用通常通过媒体服务接入，而不是提供普通的 `CATEGORY_LAUNCHER` Activity；Launcher 可生成 `CAR_INTENT_ACTION_MEDIA_TEMPLATE` Intent 或切换当前媒体源。因此点击媒体条目表达的是打开媒体体验或选择媒体源，不一定是启动该包里的 Activity。
3. **驾驶态限制**：参考实现通过 `CarPackageManager` 检查 Activity 是否为 distraction-optimized，并根据当前 UX restriction 状态禁用不符合条件的条目。置灰和提示属于界面反馈；平台仍负责在受限状态下阻止不允许启动的 Activity。



**Q4: 在 AAOS 13 中，点击普通应用图标后，启动请求怎样到达目标 Activity？**

点击图标后，`CarLauncher` 构造带目标组件和当前显示屏选项的启动请求，经 `Instrumentation` 和 Binder 交给 system_server；随后由 `ActivityTaskManager` 决定 Activity 与任务如何启动或复用，目标应用进程再执行 Activity 生命周期。

具体链路按以下阶段展开：

1. **点击分发**：`AppItemViewHolder` 调用 `AppMetaData` 中的 launch callback，进入 `AppLauncherUtils.launchApp()`。
2. **构造请求**：普通应用使用 `ACTION_MAIN`、`CATEGORY_LAUNCHER` 和显式 `ComponentName`，添加 `FLAG_ACTIVITY_NEW_TASK`；`ActivityOptions.setLaunchDisplayId(context.getDisplayId())` 指定 Launcher 当前所在的显示屏，然后调用 `Context.startActivity()`。
3. **跨进程交接**：`Activity.startActivity()` 进入 `Instrumentation.execStartActivity()`，再通过 Binder 调用 `IActivityTaskManager.startActivity()`，到达 system_server 中的 `ActivityTaskManagerService`。
4. **系统启动裁决**：`ActivityStarter` 解析目标 Activity，并根据调用权限、用户、任务栈、启动模式、Intent 标志和显示屏等条件检查请求，决定创建还是复用 `ActivityRecord` 与任务。
5. **目标进程执行**：`ActivityTaskSupervisor` 复用仍存活的目标进程，或请求异步启动进程；目标进程中的 `ActivityThread` 收到启动事务后创建并绑定 Activity，继续执行生命周期回调。

掘金对照文章展示的是 Android 14 `Launcher3` 的 `ItemClickHandler`、`startActivitySafely()` 等调用；“Launcher 准备请求、system_server 负责启动裁决”的边界也适用于 AAOS。但 AAOS 13 的点击入口是 `AppItemViewHolder` 与 `AppLauncherUtils`，并设置当前显示屏，不能把 Launcher3 的类名或启动参数直接套到车机代码上。



**Q5: AAOS Launcher 设置 `FLAG_ACTIVITY_NEW_TASK` 后，系统是否一定新建任务？**

不一定。该 flag 要求 Activity 在任务上下文中启动，但系统仍会匹配可复用的任务；只有没有合适的已有任务时，才会创建新任务。

判断时按启动过程区分三个层次：

1. **启动语义**：Launcher 从自身任务启动目标 Activity 时设置 `FLAG_ACTIVITY_NEW_TASK`，让系统按任务栈规则处理目标 Activity。
2. **任务匹配**：system_server 结合目标组件、`taskAffinity`、`launchMode`、已有任务和其他 Intent flag 查找可复用任务。找到匹配任务时，系统可能将其带到前台并复用其中的 Activity。
3. **实际结果**：任务复用不代表目标 Activity 实例一定复用。是否创建新实例或通过 `onNewIntent()` 接收请求，还要看任务栈状态、启动模式和具体 flag。

所以 `NEW_TASK` 不能解释为“每次都创建一个全新任务”，也不能单凭它推断应用进程会重启。



**Q6: 从 Launcher 点击图标时，为什么目标进程已存活仍可能创建新的 Activity？**

冷启动与热启动描述的是目标应用进程是否需要创建；Activity 是否复用则是另一个由任务栈和启动规则决定的问题。进程热启动不保证复用某个 Activity 实例。

分析时分别检查两个状态：

1. **进程状态**：目标进程不存在时，系统需要启动进程并初始化 `Application`；目标进程仍存活时，可以复用该进程。
2. **Activity 状态**：系统依据目标任务、Activity 实例、`launchMode` 和 Intent flag 决定复用现有实例、调用 `onNewIntent()`，或创建新的 Activity 实例。

AAOS 13 的 `ActivityTaskSupervisor.startSpecificActivity()` 体现了进程分支：目标进程可用时调用 `realStartActivityLocked()`；否则走异步进程启动路径。排查启动后“回到旧页面”“新建了 Activity”或“没有看到新进程”时，应把任务复用、Activity 实例复用和进程启动分开判断。
