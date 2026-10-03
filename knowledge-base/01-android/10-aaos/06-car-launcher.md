# CarLauncher 实现与任务嵌入

> 学习资料（文章模式沉淀）。边界：本文回答"CarLauncher 与 Launcher3 的职责差异、应用发现与网格构造、点击启动链与 TaskView 嵌入"；HOME 请求的系统侧解析归 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，任务/launchMode 通用语义归 03-ui/01。Q 序列即结构，供 atlas 同源直读。

**Q1: 从职责与架构看，AAOS 的 `CarLauncher` 与手机上的 AOSP `Launcher3` 有什么异同？**

两者都提供 Home 入口和应用启动入口，并沿用 Android 的应用与 Activity 模型；AAOS 主要为车内使用情境增加专用体验与平台策略。

两者的主要差异有两个方面：

1. **组件职责**：Android 13 参考实现把 `CarLauncher` 注册为 Home Activity，另用独立的 `AppGridActivity` 展示应用列表；Home 页面还可将地图 Activity 嵌入 `TaskView`，并组合 `HomeCard`。
2. **车载体验**：应用网格需考虑当前用户、目标显示屏、媒体源入口和驾驶中的 UX 限制。手机 AOSP `Launcher3` 则侧重工作区、应用抽屉、文件夹、快捷方式和桌面组件等通用桌面能力。

`CarLauncher` 中的地图卡片和 Home cards 属于 AOSP 参考实现的产品设计，不是所有 AAOS 设备的强制结构；OEM 可以替换或定制 Launcher。面试时应先说明双方共享的 Android 启动机制，再讲车载策略与交互差异，避免把 `Launcher3` 的具体实现说成 AAOS 的必经路径。

**Q2: AAOS `CarLauncher` 如何构造应用网格条目？**

Android 13 参考实现根据当前用户可启动的组件和车载策略构造应用网格；因此网格既不等于设备上全部已安装的包，也不只包含普通 `CATEGORY_LAUNCHER` Activity。

构造过程有三个关键分支：

1. **普通 Activity**：通过 `LauncherApps.getActivityList(null, Process.myUserHandle())` 获取当前用户的启动 Activity，并依据隐藏包、应用类型等配置筛选。不同 Android user 因安装状态或配置不同，可能看到不同条目。
2. **媒体源服务**：单独查询 `MediaBrowserService`。AAOS 媒体应用通常通过媒体服务接入，而不是提供普通的 `CATEGORY_LAUNCHER` Activity；Launcher 可生成 `CAR_INTENT_ACTION_MEDIA_TEMPLATE` Intent 或切换当前媒体源。因此点击媒体条目表达的是打开媒体体验或选择媒体源，不一定是启动该包里的 Activity。
3. **驾驶态限制**：参考实现通过 `CarPackageManager` 检查 Activity 是否为 distraction-optimized，并根据当前 UX restriction 状态禁用不符合条件的条目。置灰和提示属于界面反馈；平台仍负责在受限状态下阻止不允许启动的 Activity。

**Q3: 在 AAOS 13 中，点击普通应用图标后，启动请求怎样到达目标 Activity？**

点击图标后，`CarLauncher` 构造带目标组件和当前显示屏选项的启动请求，经 `Instrumentation` 和 Binder 交给 system_server；随后由 `ActivityTaskManager` 决定 Activity 与任务如何启动或复用，目标应用进程再执行 Activity 生命周期。

具体链路按以下阶段展开：

1. **点击分发**：`AppItemViewHolder` 调用 `AppMetaData` 中的 launch callback，进入 `AppLauncherUtils.launchApp()`。
2. **构造请求**：普通应用使用 `ACTION_MAIN`、`CATEGORY_LAUNCHER` 和显式 `ComponentName`，添加 `FLAG_ACTIVITY_NEW_TASK`；`ActivityOptions.setLaunchDisplayId(context.getDisplayId())` 指定 Launcher 当前所在的显示屏，然后调用 `Context.startActivity()`。
3. **跨进程交接**：`Activity.startActivity()` 进入 `Instrumentation.execStartActivity()`，再通过 Binder 调用 `IActivityTaskManager.startActivity()`，到达 system_server 中的 `ActivityTaskManagerService`。
4. **系统启动裁决**：`ActivityStarter` 解析目标 Activity，并根据调用权限、用户、任务栈、启动模式、Intent 标志和显示屏等条件检查请求，决定创建还是复用 `ActivityRecord` 与任务。
5. **目标进程执行**：`ActivityTaskSupervisor` 复用仍存活的目标进程，或请求异步启动进程；目标进程中的 `ActivityThread` 收到启动事务后创建并绑定 Activity，继续执行生命周期回调。

掘金对照文章展示的是 Android 14 `Launcher3` 的 `ItemClickHandler`、`startActivitySafely()` 等调用；“Launcher 准备请求、system_server 负责启动裁决”的边界也适用于 AAOS。但 AAOS 13 的点击入口是 `AppItemViewHolder` 与 `AppLauncherUtils`，并设置当前显示屏，不能把 Launcher3 的类名或启动参数直接套到车机代码上。

**Q4: CarLauncher 里嵌入的地图"按返回后空白/打不开"——TaskView 嵌入任务有哪些官方修复案例？**

TaskView 嵌入任务的常见故障大多有对应的 AOSP 修复提交，排查时按现象对号入座，再确认目标版本的框架是否已含修复：

1. **被 Recents 修剪**：`moveTaskToFront` 带 `MOVE_TASK_WITH_HOME` 时嵌入任务会被 Recents 修剪，返回即空白——修复是把 TaskView 内嵌任务标记为 non-trimmable（Bug 358682563）；
2. **白屏未启动**：任务根本没起来（`am stack` 确认无 task）——官方给 ControlledCarTaskView 加了最多 5 次的启动重试（Bug 256832224），scalable-ui 的 TaskPanel 进一步做成指数退避的自动重启；
3. **PIN 锁屏用户崩溃**：解锁前 task info 为空的空指针（Bug 265981088）——多用户车机上"锁屏用户首次进入嵌入页"是高频复现场景；
4. **输入捕获异常**：内嵌应用触摸丢失/错区——官方修过 TaskView 的 input capturing；用 `dumpsys input` 对比修复前后的 input window；
5. **obscure region 不生效**：设置局部遮罩后必须 invalidate 才会应用到 ViewRoot（Bug 382535017）；
6. **释放泄漏**：TaskViewTaskController 在构造时就注册进 transitions，未初始化的实例也必须走 removeTask 清理（Bug 369995920）。
