# 通知服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"NotificationManager 的进程边界、限流、渠道与权限语义"；SystemUI 渲染归 03-ui，应用通知使用与排障见相应应用实践题。源文档：android-internals-wiki §1.23（Android 17 语境），POST_NOTIFICATIONS 与渠道行为已与官方文档核对。Q 序列即结构，供 atlas 同源直读。

**Q1: 调用 NotificationManager.notify() 返回后，系统已经完成了哪些工作、哪些还没有发生？**

notify() 返回只说明同步提交阶段结束：客户端完成构建与序列化，system_server 中的 NotificationManagerService（NMS）完成同步校验；此时通知可能尚未正式发布，SystemUI 更是一定还没有画出界面。通知从应用到屏幕要穿过三个进程边界——应用组装数据、NMS 校验入队排序并分发、SystemUI 把快照转成界面列表项。

同步 Binder 调用（`INotificationManager.enqueueNotificationWithTag()`）返回前，NMS 已经可能完成：

1. 验证调用 UID 能否代表目标包发布，应用前台服务（FGS）通知策略与 flag 修正；
2. 读取 `ApplicationInfo`，校验 full-screen intent 等权限；
3. 检查自定义 `RemoteViews` 的估算内存；
4. 按渠道 ID 从 `PreferencesHelper` 读取渠道，渠道不存在时直接拒绝发布；
5. 创建 `NotificationRecord`，执行速率、数量、snooze 等淘汰检查，并给通知内的 PendingIntent 设置临时 allowlist。

返回之后才发生：`EnqueueNotificationRunnable` 入队（继承旧记录 ranking、交给通知助手）、`PostNotificationRunnable` 正式发布（写入列表、提取信号、排序、决定提醒效果）、监听器分发和 SystemUI 的视图 inflate。启用通知助手时发布还会被 `DELAY_FOR_ASSISTANT_TIME` 延后一段，这是设计行为而非线程阻塞。因此大图解码、`Notification.Builder.build()` 和 `notify()` 都放在主线程会先卡住应用自身；"已调用 notify()"不能作为业务成功确认，关键状态应另存。

**Q2: 对同一通知高频刷新进度（如 41% 变 42%）时，更新为什么会静默丢失？两级限流如何豁免状态变化？**

Android 17 对高频进度更新设置了两级限流，触发时更新被直接丢弃且应用收不到异常或回调，所以持续刷新百分比经常不显示；豁免依据是进度状态变化，不是进度值本身。

1. **客户端限流**：`NotificationManager` 实例对同一 `(user, package, tag, id)` 且 `getProgressState()` 未变化的高频更新，执行以 5 次/秒为目标的客户端限流。状态只有 `NONE`、`ONGOING`、`COMPLETE` 三种，41% 变 42% 仍是 `ONGOING`，会被限流；从 `ONGOING` 变为 `COMPLETE` 的关键更新可以通过"状态改变"条件。
2. **服务端限流**：NMS 的 `checkDisqualifyingFeatures()` 用 `NotificationUsageStats.getAppEnqueueRate(pkg)` 做包级速率估算，阈值来自 `Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE`（AOSP 默认 5 次/秒），对已有同 key 已发布或待发布记录、进度状态相同且非系统自动分组的更新拒绝。

做法：按通知 key 合并状态，只发送用户能看见的变化；完成、失败、暂停等状态切换立即发送，中间进度按时间窗口采样。业务层不能把每次 `notify()` 当成可靠消息投递。

**Q3: 重复调用 createNotificationChannel() 或删除后重建同 ID 渠道，能绕过用户对渠道的设置吗？**

不能。渠道配置的最终决定权在用户：重复创建只能更新 name、description 等少数字段，应用不能提高现有渠道的 importance，也不能覆盖用户在设置中做出的修改；删除渠道只是留下 tombstone 标记（记录 `isDeleted` 和删除时间），同 ID 重建会恢复原有设置而不是得到全新配置。

机制上，sound、vibration 等多数行为在首次创建后由系统保存，后续用同一 ID 创建不会重置；用户从未修改过的渠道允许应用降低 importance。"用户不能提高 importance"是错误说法——用户在设置中可以自由提高或降低。tombstone 设计正是为了防止应用用"删掉再创建"绕过用户选择。需要全新的声音、重要性或用途时，做法是设计新的稳定 channel ID 并向用户解释迁移原因；渠道 ID 是持久身份，不要把版本号、语言或临时业务状态编码进去。

**Q4: POST_NOTIFICATIONS 运行时权限被拒绝后，notify() 还能调用吗？通知会进入 NotificationManagerService 吗？**

通常仍能调用、也仍会进入 NMS。Android 13（API 33）起非豁免通知受 `POST_NOTIFICATIONS` 运行时权限控制，但权限被拒绝不阻止 `notify()` 调用：AOSP 实现里 NMS 会照常创建和检查记录，在 post 阶段才根据包权限与渠道状态抑制显示，普通通知通常也不会因此向 `notify()` 抛异常——"权限拒绝后通知完全不进入 NMS"的说法不准确。

官方文档口径：用户选择不允许后，应用不能再发送通知（除非符合豁免），所有渠道被封锁；前台服务通知不再出现在通知面板，但前台服务任务仍显示在系统的活动应用管理（Task Manager）界面。媒体会话通知和满足条件的 self-managed call（应用自管通话，CallStyle）属于豁免。

边界：参数非法、包身份错误、危险 PendingIntent 或无效 FGS 通知仍可能抛异常，`notify()` 不是永不失败；`areNotificationsEnabled()` 在 Android 17 检查包级 `POST_NOTIFICATIONS` 状态，不能替代渠道级检查。应用应把通知发布设计成尽力而为的界面更新，关键业务状态另存于可靠存储。

**Q5: Android 17 对通知里的自定义 RemoteViews 施加了哪两层内存检查？超限后分别发生什么？**

第一层在 NMS：同步提交阶段用 `RemoteViews.estimateMemoryUsage()` 检查自定义折叠、展开、heads-up 和 public 视图，估算内存大于 2,000,000 字节写 warning，达到 5,000,000 字节直接剥离该 `RemoteViews`。第二层在 SystemUI：`NotificationCustomContentMemoryVerifier` 在 apply 自定义视图后遍历其中的 `ImageView`，`BitmapDrawable` 按实际像素内存 `allocationByteCount` 计入，其他 Drawable 按固有宽高乘 4 估算。

分两层的原因：第一层检查的是传输对象的估算值，覆盖不了 URI 或资源图片在 SystemUI 解码后的真实占用，第二层补上这部分。版本边界：`CHECK_SIZE_OF_INFLATED_CUSTOM_VIEWS` 使用 `@EnabledAfter(BAKLAVA)`，target API 37 起强制拒绝超限视图，target 更低的应用同样超限时只收到迁移警告；两个 AOSP 阈值是 framework resource，可被设备配置覆盖，不能当作应用可用预算。

做法：能用 `BigTextStyle`、`MessagingStyle`、`CallStyle`、`ProgressStyle`、`MetricStyle` 表达的内容优先用系统模板；确需自定义视图时在解码前限制图片尺寸，并为视图被 NMS 剥离或被 SystemUI 拒绝准备可接受的退化展示。

**Q6: Android 17 想让进行中通知获得 Live Update 提升展示（promoted ongoing），只调用 setRequestPromotedOngoing(true) 够吗？**

不够。提升展示要求一组条件同时成立，缺一项系统就不会提升，而且是否最终设置 `FLAG_PROMOTED_ONGOING` 由系统决定。条件包括：

1. manifest 声明非运行时权限 `android.permission.POST_PROMOTED_NOTIFICATIONS`；
2. 调用 `setRequestPromotedOngoing(true)` 请求提升；
3. 通知处于进行中（ongoing）；
4. 提供内容标题（content title）；
5. 不使用自定义内容视图，只能用 Standard Style、`BigTextStyle`、`CallStyle`、`ProgressStyle` 或 `MetricStyle`；
6. 不是 group summary；
7. 不请求 colorized（整张通知使用强调色背景）；
8. 渠道 importance 不是 `IMPORTANCE_MIN`；
9. 普通 `POST_NOTIFICATIONS` 权限等其他通知条件仍然成立。

边界：`Notification.hasPromotableCharacteristics()` 只检查通知对象本身的结构条件，不包含用户是否允许、渠道 importance 或 OEM 附加条件；`NotificationManager.canPostPromotedNotifications()` 用于检查应用当前是否获准发布。用途上，Live Update 面向已开始、由用户发起且对时间敏感的活动（导航、行程、配送、进行中的训练）；促销或没有明确结束点的状态不符合用途，请求提升也不保证系统一定提升。

**Q7: Android 通知渠道解决什么问题，应用能否绕过用户设置强制提高通知重要性？**

面向 Android 8.0（API 26）及以上的应用，通知应先归入通知渠道；渠道把一类通知的名称、用途和重要性暴露给用户，由用户控制提醒方式。应用可在首次使用时创建渠道并为每条通知指定渠道 ID，但渠道创建后的重要性等用户可见设置由系统和用户管理，应用不能靠重复创建同 ID 渠道重置用户选择。

应用仍须在每条通知中提供合理的小图标、内容和点击行为，并按平台版本处理通知运行时权限与前台服务要求。渠道用于分类和用户控制，不是通知必然展示或绕过勿扰、权限及系统策略的保证。
