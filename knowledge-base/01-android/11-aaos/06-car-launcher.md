# CarLauncher 实现与任务嵌入

> 学习资料（文章模式沉淀）。边界：本文回答"CarLauncher 与 Launcher3 的职责差异、应用发现与网格构造、点击启动链与 TaskView 嵌入"。HOME 请求的系统侧解析归 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)，任务/launchMode 通用语义归 03-ui/01。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 从职责与架构看，AAOS 的 CarLauncher 与手机上的 AOSP Launcher3 有什么异同？**

两者都提供 Home 入口和应用启动入口，并沿用 Android 的应用与 Activity 模型。AAOS 主要为车内使用情境增加专用体验与平台策略。

两者的主要差异有两个方面：

1. **组件职责**：Android 13 参考实现把 `CarLauncher` 注册为 Home Activity，另用独立的 `AppGridActivity` 展示应用列表。Home 页面还可将地图 Activity 嵌入 `TaskView`，并组合 `HomeCard`。
2. **车载体验**：应用网格需考虑当前用户、目标显示屏、媒体源入口和驾驶中的 UX 限制。手机 AOSP `Launcher3` 则侧重工作区、应用抽屉、文件夹、快捷方式和桌面组件等通用桌面能力。

`CarLauncher` 中的地图卡片和 Home cards 属于 AOSP 参考实现的产品设计，不是所有 AAOS 设备的强制结构。OEM 可以替换或定制 Launcher。面试时应先说明双方共享的 Android 启动机制，再讲车载策略与交互差异，避免把 `Launcher3` 的具体实现说成 AAOS 的必经路径。

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

**Q4: [learning] CarLauncher 的 TaskView 地图出现返回后空白、启动失败或触摸异常时，应检查哪些已知修复点？**

TaskView 嵌入任务的故障要按任务生命周期、宿主可见性、用户状态、输入路由和资源释放分别定位。下面是 AAOS 参考实现与其依赖框架中的已知修复点，排查时还要确认目标分支是否包含对应修复：

1. **任务被 Recents 修剪：**特定修复前，`moveTaskToFront()` 携带 `MOVE_TASK_WITH_HOME` 可能令嵌入任务被 Recents 清理，宿主返回后留下空白。Bug 358682563 的修复方向是让 TaskView 管理的任务不参与该修剪路径。`MOVE_TASK_WITH_HOME` 的定义是将 Home Activity 放在该任务之后，不是“保留 TaskView 子任务”的通用保证。
2. **任务启动失败：**先确认受控 TaskView 是否创建、初始化以及是否真的出现 task。Bug 256832224 的修复为 `ControlledCarTaskView` 增加最多 5 次启动重试，次数上限用于避免无限重启。后续 scalable-ui 的 TaskPanel 使用指数退避自动重启，这是另一实现，不应与 AAOS Launcher 的重试策略混为一谈。原案例用 `am stack` 确认 task 是否存在。该命令是否可用取决于系统版本，必要时用目标版本支持的 `dumpsys activity` 信息交叉确认。不要只凭白屏推断为渲染失败。
3. **PIN 锁定用户崩溃：**Bug 265981088 涉及用户解锁前 task info 为空时的空指针。多用户车机上应覆盖锁屏用户首次进入嵌入页的时序，并在 task info 尚不可用时等待用户/任务状态回调，而不是解引用空对象。
4. **触摸丢失或错区：**TaskView 依赖 input capturing 将输入交给嵌入任务。检查当前输入窗口、捕获区域和宿主窗口焦点，并比较 `dumpsys input` 中的 input window。触摸命中宿主却未送达嵌入任务时，单看任务是否存活不足以定位问题。
5. **局部遮罩未生效：**Bug 382535017 指向 obscure region 更新后未触发 ViewRoot 刷新。更新区域后还要确保 ViewRoot 获得失效/重绘通知，否则内部区域状态改变不保证立即应用到窗口。
6. **TaskView 释放泄漏：**Bug 369995920 涉及 `TaskViewTaskController` 在构造阶段就注册到 transitions。即使界面尚未完成初始化，也要沿对应生命周期移除已注册资源。释放路径须与注册路径配对。

这些 Bug 编号是原案例的追踪标识，不代表任意 AAOS 分支都仍含有同一缺陷。核对修复时以目标分支的 `platform/packages/apps/Car/Launcher`、TaskView/WindowManager Shell 实现及对应提交为准。指数退避的 TaskPanel 案例来自 scalable-ui 项目，不属于 AAOS Launcher 自带实现。
