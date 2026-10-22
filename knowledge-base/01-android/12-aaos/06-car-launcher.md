# CarLauncher 实现与任务嵌入

> 范围：CarLauncher 与 Launcher3 的职责差异、应用发现、点击启动链和 TaskView 嵌入。HOME 请求的系统侧解析见 [系统启动](../01-architecture/02-system-boot.md)。

**Q1: [learning] 从职责与架构看，AAOS 的 CarLauncher 与手机上的 AOSP Launcher3 有什么异同？**

两者都提供 Home 入口和应用启动入口，并沿用 Android 的应用与 Activity 模型。AAOS 主要为车内使用情境增加专用体验与平台策略。

两者的主要差异有两个方面：

1. **组件职责**：Android 13 参考实现把 `CarLauncher` 注册为 Home Activity，另用独立的 `AppGridActivity` 展示应用列表。Home 页面还可将地图 Activity 嵌入 `TaskView`，并组合 `HomeCard`。
2. **车载体验**：应用网格需考虑当前用户、目标显示屏、媒体源入口和驾驶中的 UX 限制。手机 AOSP `Launcher3` 则侧重工作区、应用抽屉、文件夹、快捷方式和桌面组件等通用桌面能力。

`CarLauncher` 中的地图卡片和 Home cards 属于 AOSP 参考实现的产品设计。OEM 可以替换或定制 Launcher，因此 Launcher3 的具体组件不是 AAOS 的必经路径。

**Q2: [learning] AAOS CarLauncher 如何构造应用网格条目？**

Android 13 参考实现根据当前用户可启动的组件和车载策略构造应用网格。因此网格既不等于设备上全部已安装的包，也不只包含普通 `CATEGORY_LAUNCHER` Activity。

构造过程有三个关键分支：

1. **普通 Activity**：`LauncherApps.getActivityList(null, Process.myUserHandle())` 的第一个参数为 null，表示不把查询限定到某一个包。第二个参数取当前进程所属用户。返回该用户可由 Launcher 启动的 Activity，再依据隐藏包、应用类型等配置筛选。不同 Android user 因安装状态或配置不同，可能看到不同条目。
2. **媒体源服务**：单独查询实现 `MediaBrowserService` 的服务。AAOS 媒体应用通常通过媒体服务接入，不要求同时提供普通的 `CATEGORY_LAUNCHER` Activity。Launcher 可生成带有 `CAR_INTENT_ACTION_MEDIA_TEMPLATE` action 的 Intent，或调用媒体源切换逻辑。因此点击媒体条目表达的是打开媒体体验或选择媒体源，不一定是启动该包里的 Activity。
3. **驾驶态限制**：参考实现通过 `CarPackageManager` 检查目标 Activity 是否声明为 distraction-optimized，并根据当前 UX restriction 禁用不符合驾驶态要求的条目。置灰和提示是界面反馈，平台启动校验仍是最终约束。应用不能只靠按钮置灰保证驾驶安全。

**Q3: [learning] 在 AAOS 13 中，点击普通应用图标后，启动请求怎样到达目标 Activity？**

点击图标后，`CarLauncher` 构造带目标组件和当前显示屏选项的启动请求，经 `Instrumentation` 和 Binder 交给 system_server。随后由 `ActivityTaskManager` 决定 Activity 与任务如何启动或复用，目标应用进程再执行 Activity 生命周期。

具体链路按以下阶段展开：

1. **点击分发**：`AppItemViewHolder` 调用 `AppMetaData` 中的 launch callback，进入 `AppLauncherUtils.launchApp()`。
2. **构造请求**：`ACTION_MAIN` 表示主入口动作，`CATEGORY_LAUNCHER` 表示 Launcher 可展示的入口类别，显式 `ComponentName` 把请求指向用户点击的 Activity。代码添加 `FLAG_ACTIVITY_NEW_TASK`，允许系统按任务匹配规则创建或复用目标任务。`ActivityOptions.setLaunchDisplayId(context.getDisplayId())` 指定 Launcher 当前所在的显示屏，避免只依赖系统默认显示屏选择。最后调用 `Context.startActivity()` 提交请求。
3. **跨进程交接**：`Activity.startActivity()` 进入 `Instrumentation.execStartActivity()`，随后通过 Binder 调用 `IActivityTaskManager.startActivity()` 到达 system_server 中的 `ActivityTaskManagerService`。Binder 交接的是启动请求，不代表目标 Activity 已经开始运行。
4. **系统启动裁决**：`ActivityStarter` 解析目标 Activity，并依据调用权限、目标用户、任务栈、启动模式、Intent 标志和显示屏等条件检查请求，决定创建还是复用 `ActivityRecord` 与任务。显式组件只确定请求目标，不绕过这些系统检查。
5. **目标进程执行**：`ActivityTaskSupervisor` 复用仍存活的目标进程，或请求异步启动进程。目标进程中的 `ActivityThread` 收到启动事务后创建并绑定 Activity，继续执行生命周期回调。

掘金对照文章展示的是 Android 14 `Launcher3` 的 `ItemClickHandler`、`startActivitySafely()` 等调用。“Launcher 准备请求、system_server 负责启动裁决”的边界也适用于 AAOS。但 AAOS 13 的点击入口是 `AppItemViewHolder` 与 `AppLauncherUtils`，并设置当前显示屏，不能把 Launcher3 的类名或启动参数直接套到车机代码上。

**Q4: [learning] TaskView 地图返回后空白、启动失败或触摸异常时，怎样区分故障环节？**

先确认嵌入任务是否存在，再分辨任务生命周期、用户状态、输入路由和宿主窗口问题。白屏并不能单独证明地图渲染失败。排查以目标分支的 CarLauncher 和 WindowManager Shell 实现为准。

1. **任务不存在：**检查受控 TaskView 的初始化、启动结果和重试边界，再用目标版本支持的 `dumpsys activity` 核对 task。任务启动失败不能靠界面重绘修复。
2. **任务曾存在后消失：**对齐 Home 返回、Recents 修剪和 TaskView 释放事件。`MOVE_TASK_WITH_HOME` 控制 Home 与任务的排列，不保证嵌入任务一直存活。
3. **用户尚未解锁：**多用户设备需确认目标用户、任务信息和解锁状态。task info 暂不可用时等待回调，不能直接解引用。
4. **任务存在但无法触摸：**用 `dumpsys input` 检查输入窗口、捕获区域与宿主焦点。局部遮罩变化后，还要检查窗口是否收到更新通知。
5. **界面销毁后异常：**核对 TaskView 初始化期间注册的 transition、监听器与其他资源是否在对应释放路径注销。
