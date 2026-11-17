# 通知服务链路

> 学习资料（文章模式沉淀）。边界：本文回答 NotificationManager 到 NMS 的提交与发布时序、限流、通知渠道与权限、RemoteViews 约束和 Live Update 提升条件。本文还收录通知行绑定与布局的 SystemUI 机制；应用侧产品实践归对应应用实践文档。源文档：android-internals-wiki §1.23。权限和 Live Update 行为对照 Android Developers，Android 17 实现细节对照 frameworks/base 的 NotificationManager、NotificationManagerService、NotificationChannel 和 Notification。

**Q1: [learning] 调用 NotificationManager.notify() 返回后，系统已经完成哪些工作？通知何时才算发布或显示？**

`notify()` 返回表示应用到 NMS 的同步提交调用结束，不等于通知已经显示。NMS 会在 Binder 调用期间做身份与参数校验、构造记录并把后续处理交给服务端工作队列，但服务端任务与调用返回之间存在并发竞态，不能把“返回后”当成所有异步处理的严格分界。

一次通知提交可以分成三个阶段：

1. **应用构建与提交：**应用构造 `Notification` 并调用 `notify()`。大图解码、样式构建和 `Notification.Builder.build()` 若在主线程执行，可能先卡住应用自身。
2. **NMS 接收与异步发布：**Binder 进入 `NotificationManagerService` 后，系统会验证调用身份、包名、渠道及通知策略，修正适用的 FGS 标志，检查 full-screen intent 等权限和自定义 `RemoteViews` 估算大小，并构造 `NotificationRecord`。系统还可能为通知中的 `PendingIntent` 设置临时 allowlist。随后工作线程继续处理限流、数量上限、snooze、通知助手、正式入列、排名和监听器分发。启用通知助手时，发布可能被 `DELAY_FOR_ASSISTANT_TIME` 延迟一段时间，这是异步策略延迟，不是应用线程被同步阻塞。不同检查处于同步入口还是后续处理阶段，应以对应 Android 分支实现为准。
3. **SystemUI 呈现：**SystemUI 收到服务端分发后，才把通知数据转换为状态栏、通知抽屉等界面内容。主题、渠道、权限、勿扰和设备策略都可能使通知不产生预期的提醒或视图。

因此，`notify()` 返回不是 UI 显示确认，也不是关键业务数据的可靠存储确认。需要保证的业务状态应保存在应用自己的持久化存储中。

AAOS13 的 `NotificationManager.notifyAsUser()` 在发起同步 Binder 前还执行 `fixNotification()`，其中包含图像尺寸处理；客户端主线程构建大图可能先消耗时间。NMS 把 `EnqueueNotificationRunnable` 投递到内部 Handler 后 Binder 才返回，后续排序、listener 与 SystemUI 消费不属于本次同步等待。

**Q2: [learning] 高频更新同一条通知时，为什么进度变化可能不显示？客户端和服务端限流如何判定？**

Android 17 AOSP 同时有客户端与 NMS 侧的更新限流。限流会丢弃部分更新，应用不能把每次 `notify()` 都当作可靠消息，也不能只依据进度数字变化判断系统一定接收了更新。

1. **客户端限流：**`NotificationManager` 对同一通知 key（user、package、tag、id）跟踪 `getProgressState()`。默认目标速率为每秒 5 次。状态值为 `NONE`、`ONGOING`、`COMPLETE`，所以 41% 到 42% 仍是 `ONGOING`，可能被压制。`ONGOING` 到 `COMPLETE` 的状态切换可按状态变化规则通过客户端限流。
2. **NMS 侧限流：**`checkDisqualifyingFeatures()` 使用 `NotificationUsageStats.getAppEnqueueRate(pkg)` 估算包级更新速率，并读取 `Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE`。AOSP 默认值为每秒 5 次，设置单位是 Hz，且该项针对更新速率。更新还需满足其他拒绝条件，例如 key 已有发布中或待处理记录、进度状态相同、不是系统自动分组等。
3. **更新策略：**按通知 key 合并当前状态，只在用户可见的信息发生有意义变化时提交。中间进度按时间窗口采样，完成、失败、暂停等状态切换及时提交，并将关键业务事实保存在可靠存储中。

AAOS13 的服务端默认速率 `DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5f` 可由 `Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE` 覆盖，判定使用 `RateEstimator` 的 EWMA 到达率（`RATE_ALPHA = 0.8`），不是简单统计最近一秒调用次数。普通应用还受未清理通知数量上限 `MAX_PACKAGE_NOTIFICATIONS = 50` 约束；超限更新可能只在 NMS 内记日志，应用没有逐条 posted 失败回调。

**Q3: [learning] 通知从入库到显示经历哪些阶段，为什么异步绑定没有消除 UI 线程成本？**

阶段是：`NotifInflaterImpl` 把 entry 交给 row binder 创建或复用通知行。内容绑定任务在通知 inflation 工作线程上构建内容并加载图片（RemoteViews 新建走 `applyAsync()`、复用走 `reapplyAsync()`，异步失败会在 UI 回调路径同步重试）。完成后 row 进入 View 树，触发 measure/layout、动画与绘制。异步只把 Builder 恢复、模板生成与部分图片工作移出主线程，UI 线程仍要处理完成回调、把 View 加入界面树、`requestLayout()` 与动画状态——大量通知短时间到达时，常见时序是 worker 队列持续工作、多个异步结果相近时刻完成、UI 线程集中挂接 row、NSSL 与图标容器随后几帧反复更新。版本边界：AAOS13 的实现类是 `NotificationContentInflater.java`（含 `AsyncInflationTask#doInBackground`，已按 13 源码核对），Android 17 迁移为 Kotlin 的 `NotificationRowContentBinderImpl.kt` 但保留历史 trace 名，搜 trace 时要区分“兼容保留的 slice 名称”与“当前是否存在同名类”。"主线程没有 `RemoteViews.apply()`"不能排除通知绑定，因为异步的主要工作本来就在 worker 上。做法：App 侧合并高频进度更新、保持 ID 与模板稳定、控制大图与自定义 RemoteViews 层级。SystemUI 侧修复触发同步 fallback 的原因。

**Q4: [learning] NSSL 的 onMeasure 为什么连 GONE 的子 View 也要测量，“屏幕上可见几条通知”与测量成本为什么不同？**

AAOS13 的 `NotificationStackScrollLayout.java` 在 `onMeasure()` 中遍历全部 child 并明确测量 GONE child，源码注释写明“需要测量所有子 View（包括 GONE 的），以便算法计算可容纳的通知数量”——视觉上被隐藏不代表测量工作归零。一帧中的工作量随以下条件变化：entry 与 row 总数、分组展开/折叠与 heads-up 状态、contracted/expanded/public 等内容变体是否已绑定、OEM 增加的包装层，以及配置变化或字体缩放触发的重测。`updateChildren()` 还会运行堆叠布局算法、应用状态或启动状态动画并处理通知间重叠。因此“减少屏幕上可见的通知”与“减少参与测量的 row”是两个不同目标，NSSL measure 随通知总数增长时应减少参与测量的 row、内容形态与层级，`updateChildren` 变长时检查分组、heads-up 与同一帧中的更新次数。边界：A13 源码中该方法存在但没有 `NSSL#updateChildren` 这个 trace 名（材料按 Android 17 引用），具体 slice 名随版本变化，用 trace 前先在目标 build 上确认。

**Q5: [learning] 通知渠道由谁控制？重复创建或删除后重建同一 ID，能绕过用户设置吗？**

通知渠道是 Android 8.0（API 26）及以上的分类与用户控制边界。应用创建稳定的 channel ID 并为每条通知指定它，用户则可以在系统设置中控制渠道的重要性和提醒方式。重复创建或删除后重建相同 ID 都不能重置用户已作出的选择。

1. **首次创建：**渠道 ID 标识持久的通知类别。应用提供名称、说明、默认重要性和初始提醒设置，用户可以在系统设置中修改允许用户控制的字段。
2. **重复创建：**再次用相同 ID 调用 `createNotificationChannel()` 主要允许更新名称、说明等元数据。声音、震动等提醒方式通常在首次创建后由系统保存，后续用相同 ID 创建不会重置这些设置。应用不能靠重复创建把现有渠道的重要性调高，也不能覆盖已保存的用户设置。若用户尚未锁定相应字段，应用可以降低渠道重要性。用户可以在系统设置中提高或降低重要性。
3. **删除后重建：**系统保留已删除渠道的 tombstone 状态，包括 `isDeleted` 和删除时间等记录。同 ID 重建会恢复此前渠道身份与保留设置，不会创建一个绕过用户选择的全新渠道。
4. **迁移策略：**需要为实质不同的通知用途创建新渠道时，应设计新的稳定 ID，并向用户说明用途变化。不要把版本号、语言或临时业务状态编码进 channel ID 来规避用户配置。
5. **显示边界：**渠道只提供分类和用户控制入口，不保证通知一定显示，也不绕过应用权限、勿扰模式或系统策略。小图标、正文和点击行为仍由应用为每条通知提供。

**Q6: [learning] Android 13 及以上拒绝 POST_NOTIFICATIONS 后，还能调用 notify() 吗？通知会显示在哪里？**

`notify()` 方法本身仍可调用，但 Android 13（API 33）及以上用户拒绝 `POST_NOTIFICATIONS` 后，应用通常不能发布非豁免的用户可见通知。应用应把“调用成功”和“用户看到通知”分开处理，不要依赖内部是否创建过通知记录。

1. **普通通知：**拒绝权限会封锁应用的通知渠道，普通通知不显示在通知抽屉。首次安装 Android 13 及以上设备的应用，通知默认关闭，应用需要按目标 SDK 和产品场景请求权限。
2. **前台服务：**该权限不是启动前台服务的前置条件，服务仍须提交通知。权限被拒绝时，FGS 通知不显示在通知抽屉，但相关运行状态仍可显示在系统 Task Manager 的活动应用界面。
3. **豁免类别：**媒体会话通知属于豁免。自管电话应用在声明 `MANAGE_OWN_CALLS`、实现 `ConnectionService` 并向 Telecom 注册 `PhoneAccount` 后，可发布 `CallStyle` 通知而不要求该权限。
4. **查询边界：**`NotificationManager.areNotificationsEnabled()` 检查应用级通知允许状态，不能替代对具体 channel importance、单个渠道阻止状态或勿扰策略的检查。参数非法、身份校验失败或其他通知约束仍可能导致 `notify()` 抛异常。

**Q7: [learning] Android 17 如何限制通知自定义 RemoteViews 的内存？超过限制会怎样？**

Android 17 在 NMS 提交阶段和 SystemUI 展开阶段分别检查自定义通知视图。第一层基于 `RemoteViews` 序列化对象的估算值，第二层检查 SystemUI 实际应用后可见的 Drawable 占用。它们检查的对象不同，不能用第一层估算替代实际展开检查。

1. **NMS 估算：**`RemoteViews.estimateMemoryUsage()` 检查折叠、展开、heads-up 和 public version 视图。AOSP 默认估算值大于 2,000,000 字节时记录 warning，达到 5,000,000 字节时剥离对应自定义视图。这两个阈值来自 framework resource，设备可以覆盖，不是应用可用预算。
2. **SystemUI 展开后检查：**Android 17 引入 `NotificationCustomContentMemoryVerifier`，在自定义视图应用后遍历 `ImageView`。`BitmapDrawable` 使用 `allocationByteCount` 统计像素内存，其他 Drawable 按固有宽高乘 4 估算。对于 target API 37 及以上应用，`CHECK_SIZE_OF_INFLATED_CUSTOM_VIEWS` 生效后超限的自定义视图不能按原样用于展示。具体兼容 flag 和 OEM 行为需以目标系统镜像验证，较低 target 的兼容处理以迁移警告为主。
3. **适配：**优先用 `BigTextStyle`、`MessagingStyle`、`CallStyle`、`ProgressStyle` 或 `MetricStyle` 等系统模板。确需自定义布局时，应在解码前限制图片尺寸，并保证标准标题与正文足以作为退化内容。

**Q8: [learning] Android 17 想让进行中的通知成为 Live Update（promoted ongoing），只调用 setRequestPromotedOngoing(true) 够吗？**

不够。应用只能请求提升，系统还会结合通知结构、权限、用户设置和渠道条件决定是否设置 `FLAG_PROMOTED_ONGOING`。适格通知需要同时满足以下结构与权限条件：

1. 在 manifest 声明非运行时权限 `android.permission.POST_PROMOTED_NOTIFICATIONS`。
2. 调用 `setRequestPromotedOngoing(true)` 或设置 `EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升。
3. 将通知标记为 ongoing，并设置非空 `contentTitle`。
4. 使用 Standard Style、`BigTextStyle`、`CallStyle`、`ProgressStyle` 或 `MetricStyle`。不得设置自定义 `RemoteViews`。
5. 通知不能是 group summary，且不能请求 colorized 背景。
6. 渠道 importance 不能为 `IMPORTANCE_MIN`，普通 `POST_NOTIFICATIONS` 权限及渠道条件也必须允许发布。

`Notification.hasPromotableCharacteristics()` 检查通知对象结构，不检查用户设置、权限或渠道是否允许提升。`NotificationManager.canPostPromotedNotifications()` 用于查询应用当前是否获准请求提升。这些检查仍不保证系统最终提升。Live Update 面向用户发起、已开始且对时间敏感的活动，例如导航、行程、配送或进行中的训练，不适合促销信息或没有明确结束点的常驻状态。
