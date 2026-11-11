# Android 13 横幅通知全链路解剖：一条横幅从 notify() 到收回的往返

> 横幅通知（Heads-Up Notification，下称 HUN）是 Android 通知体系中唯一由系统主动把内容推进用户当前视野的形态：用户没有下拉面板、应用不在前台，一张卡片直接压到状态栏下方，驻留几秒后自动收回。本文以本仓 AAOS 13 源码树为唯一取证基础，把一条横幅的完整往返走通——应用端投递、服务端入队与裁决、SystemUI 端判定与上屏、视图与窗口、点击与 Action、超时与收回——随后完整讲清车机形态的第二套横幅实现（CarNotificationLibrary），并给出两套实现的逐项对照。每条断言都落到文件、行号与原文引文；引文一律以本仓文件为准，行号以本仓当前检出版本为准。

---

## 0. 总览与阅读约定

### 0.1 主线：一条横幅的往返路线

```
应用进程                      system_server                          SystemUI 进程
────────                     ─────────────                          ────────────
NotificationManager.notify()
  │ 三跳转发 + fixNotification 体检
  ▼
INotificationManager
.enqueueNotificationWithTag ──Binder──► NMS.enqueueNotificationWithTag (:3704)
                                          │ enqueueNotificationInternal (:6890)
                                          │ 八道拒绝点（同步段）
                                          ▼
                                        NotificationRecord 构造
                                          │ importance 初值 = channel (:387)
                                          ▼
                                        mHandler.post ──► EnqueueNotificationRunnable (:7813)
                                                          │ 入 mEnqueuedNotifications
                                                          ▼
                                                        PostNotificationRunnable (:7931)
                                                          │ extractSignals：11 个提取器 (:8030)
                                                          │ sort + buzzBeepBlink (:8031-8037)
                                                          ▼
                                                        notifyPostedLocked (:8041 → :11751)
                                                          │ 打包裁剪版 SBN + RankingMap
                                                          ▼
                                                       Binder ──► NotificationListener
                                                                    .onNotificationPosted (:150)
                                                                    │
                                                                    ▼
                                                                  GroupCoalescer 攒批 (:135)
                                                                    │
                                                                    ▼
                                                                  NotifCollection.postNotification (:482)
                                                                    │ 入账、发 EntryAddedEvent
                                                                    ▼
                                                                  HeadsUpCoordinator (:403)
                                                                    │ shouldHeadsUp？十一道门槛 (:390)
                                                                    ▼
                                                                  HeadsUpViewBinder 异步绑视图 (:84)
                                                                    │ 绑定完成回调 (:101)
                                                                    ▼
                                                                  HeadsUpManagerPhone
                                                                    .showNotification (:67)
                                                                    │ 入场，5 秒时钟起跳
                                                                    ▼
                                                                  NotificationPanelViewController
                                                                    │ 横幅可见、窗口可触
                                                                    │
                                            ┌───────────────────────┼───────────────────────┐
                                            ▼                       ▼                       ▼
                                      用户点击主体            用户点击 Action           无人理会
                                      contentIntent          actionIntent             超时到期
                                      (:215)                 (:156)                   (:322-337)
                                            │                       │                       │
                                            └───────────────────────┴───────────────────────┘
                                                                    ▼
                                                          removeAlertEntry (:214)
                                                          横幅收回，视图解绑 (:679)
```

主线一句话：**应用把通知交给 NMS，NMS 裁决出 importance 等信号并广播给监听端，SystemUI 收到后独立重判"弹不弹"，判定通过则异步绑定视图、交给 HeadsUpManager 上屏并挂表，到期或事件触发后收回。** 服务端从不决定"弹横幅"这个动作本身，它只生产裁决字段；横幅的生死大权在 SystemUI 进程内。

### 0.2 范围与树况

本文覆盖三部：

- **第一部（1~5 章）**：服务端。从 `NotificationManager.notify()` 到 `onNotificationPosted` 跨进程送达为止，聚焦"哪些裁决字段决定横幅命运"。
- **第二部（6~14 章）**：手机形态 SystemUI。从监听端收帖到横幅上屏、点击、收回的完整闭环，核心是 `NotificationInterruptStateProviderImpl` 的判定门槛与 `HeadsUpManager` 系的时钟机制。
- **第三部（15~22 章）**：车机形态 CarNotificationLibrary。AAOS 在 `packages/apps/Car/Notification` 里另起了一套独立的横幅实现，判定、容器、声音、超时全部与手机侧不同；它也是雅迪车机 SystemUI 定制版的直接上游（对照审计见《yadea通知接入对照与审计》）。

树况约定（详见附录 A）：本仓 SystemUI 树未收录上游 A13 的 `VisualInterruptionDecision` / `VisualInterruptionDecisionProvider` / `VisualInterruptionStageProvider` 系列文件；本树的横幅判定由 `NotificationInterruptStateProviderImpl` 自带完整逻辑、由 coordinator 层的 `HeadsUpCoordinator` 直接消费，链路在树内可全程引证，不存在断点。全文不引用任何不在仓的文件。

### 0.3 与既有文章的关系

- 《通知服务与车机打扰治理》（AAOS13_study 仓 `docs/md/通知服务与车机打扰治理.md`）：以"打扰治理"为主线，覆盖入队管线、importance 权限模型、ZenMode 引擎、排序流水线、buzz-beep-blink 与车机两层打扰闸门。本文第一部与其在第 2~5 章有交集，但视角不同：那篇回答"系统如何治理打扰"，本文回答"这些裁决字段中哪几个决定横幅弹不弹、如何传到 SystemUI"。重叠处本文只写横幅视角的份额，治理细节以交叉引用指向该篇对应章节。
- 《SystemUI 源码分析》（AAOS13_study 仓 `docs/md/SystemUI.md`）：第 3 章有通知管线（NotifCollection → shade list）的概览。本文第二部把其中的横幅支线（判定、绑定、上屏、收回）单独拉直讲透。
- 《yadea通知接入对照与审计》（同目录 `yadea通知接入对照与审计.md`）：本文第三部的下游篇，对雅迪车机 SystemUI 基于 CarNotificationLibrary 的定制实现做机制解剖与接入文档逐条审计。

### 0.4 主线结论预览

1. 横幅门槛是 importance ≥ `IMPORTANCE_HIGH`（手机侧亮屏链第 8 道门槛），而 importance 的初值从 channel 抄来（`NotificationRecord.java:387`），应用对已建 channel 只能降不能升（`PreferencesHelper.java:1061-1075`）——"应用自己把 channel 调高就能弹横幅"在用户动过 channel 之后不成立。
2. 服务端不决定横幅。它把 importance、intercepted（zen 拦截）、suppressedVisualEffects 打进 RankingMap 发给监听端（`NotificationManagerService.java:10556-10565`）；"弹不弹"由 SystemUI 进程内独立重判。
3. A13 本树的判定核心是 `NotificationInterruptStateProviderImpl`：亮屏链十一道门槛、灭屏（AOD pulse）链六道门槛，外加唯一一个 suppressor 实现（`StatusBarNotificationPresenter`）注入的三组条件。
4. 判定的消费者是 `HeadsUpCoordinator`（coordinator 层，而非上游完整版的 VisualInterruption 管线 stage）：判定为真后先异步绑定横幅视图（1000ms 绑定超时），绑定完成回调里才调用全树唯一的 `showNotification` 调用点。
5. 横幅寿命的四个时钟数：自动收回衰减 5000ms、触摸接受延迟 700ms（计入起始）、最短展示 2000ms、snooze 60000ms；AOD pulse 可延长 10000ms；无障碍可再放大。
6. sticky 四态不自动收回：pinned 且展开、远程输入激活中、带 full-screen intent、菜单展开（手机侧附加）。
7. 点击主体发 `contentIntent`（为空时退 `fullScreenIntent`），经 dismissKeyguardThenExecute；点击 Action 走 `RemoteViews.startPendingIntent`，且 Action 按压会触发横幅生命延长。
8. 车机形态是完全独立的第二套实现：判定在 `CarHeadsUpNotificationManager.shouldShowHeadsUp`（五道否决＋三条放行），横幅挂独立 WindowManager 窗口（`TYPE_STATUS_BAR_SUB_PANEL`），超时 8000ms/最短 2000ms，提示音由库内 `Beeper` 自播（automotive 形态下 framework 服务端已关声）。
9. 车机面板管线与横幅管线分离：`PreprocessingManager` 四段流水线只服务通知中心列表，来电（`CATEGORY_CALL`）永不进面板、只以持久横幅呈现。
10. 媒体通知在 A13 手机侧走 `MediaDataManager` 独立链路；横幅判定层没有任何显式的媒体排除分支，媒体通知实际不弹横幅靠的是 importance 达不到 HIGH 这道通用门槛。

---

## 第一部 服务端：这条通知有没有资格成为横幅

### 1. 应用侧：notify() 三跳与交付前体检

#### 1.1 三跳转发

应用侧 API 从 `notify(int, Notification)` 到 Binder 只隔两跳委托。`frameworks/base/core/java/android/app/NotificationManager.java`（611-614 行）：

```java
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }
```

带 tag 变体（同文件 636-639 行）再委托 `notifyAsUser`；终点在 683-695 行，跨 Binder 前通知先过一遍 `fixNotification` 体检：

```java
    public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();

        try {
            if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
            service.enqueueNotificationWithTag(pkg, mContext.getOpPackageName(), tag, id,
                    fixNotification(notification), user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
```

Binder 接口定义在 `frameworks/base/core/java/android/app/INotificationManager.aidl`（55-56 行）：

```java
    void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
            in Notification notification, int userId);
```

注意 A13 的 AIDL 里没有名为 `postNotification` 的方法——旧资料里的方法名在本树不适用（树况说明见附录 A）。

#### 1.2 fixNotification：交付前体检

客户端体检在 `NotificationManager.java:698-720`，四件事：补上下文派生字段、修 legacy 小图标、强校验小图标、裁图并克隆：

```java
    private Notification fixNotification(Notification notification) {
        String pkg = mContext.getPackageName();
        // Fix the notification as best we can.
        Notification.addFieldsFromContext(mContext, notification);
        ...
        fixLegacySmallIcon(notification, pkg);
        if (mContext.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (notification.getSmallIcon() == null) {
                throw new IllegalArgumentException("Invalid notification (no valid small icon): "
                        + notification);
            }
        }
        notification.reduceImageSizes(mContext);
        return Builder.maybeCloneStrippedForDelivery(notification);
    }
```

与横幅直接相关的一条：**没有 smallIcon 的通知在应用进程里就抛异常**（targetSdk > 22 时），根本到不了服务端。`reduceImageSizes` 把大图裁到系统限制内，避免 Binder 传输失败——横幅大图（BigPicture 样式）能否完整显示，第一步就在这里被裁决定了一半。

### 2. NMS 同步段：八道拒绝点与四个记账步骤

Binder 入口在 `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java`（3702-3708 行），直接转内部实现：

```java
        @Override
        // enqueueNotificationWithTag：带标签入队通知入口。
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification, int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, userId);
        }
```

`enqueueNotificationInternal` 有两层：重载（6882-6887 行）补默认参数 `postSilently=false` 后转主体（6890 行起，至 7087 行）。主体是"同步拒绝段＋异步发布段"的分界：同步段里八道拒绝点任何一道失败，通知就地死亡，调用方要么收到异常、要么静默被丢；通过全部拒绝点后，`NotificationRecord` 被 post 到 handler 线程，进入异步发布。以下按执行序走。

#### 拒绝点 1：空参校验（6906-6909 行）

```java
        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }
```

#### 拒绝点 2：用户解析（6911-6913 行）

```java
        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = UserHandle.of(userId);
```

`handleIncomingUser` 校验调用方有没有权力替目标 userId 发通知（跨用户发通知需要 `INTERACT_ACROSS_USERS` 系权限），不合法直接抛 SecurityException。多用户车机上这一道尤其关键：后台用户的应用发不出前台用户可见的通知。

#### 拒绝点 3：防冒名 uid 解析（6915-6922 行）

```java
        // Can throw a SecurityException if the calling uid doesn't have permission to post
        // as "pkg"
        final int notificationUid = resolveNotificationUid(opPkg, pkg, callingUid, userId);

        if (notificationUid == INVALID_UID) {
            throw new SecurityException("Caller " + opPkg + ":" + callingUid
                    + " trying to post for invalid pkg " + pkg + " in user " + incomingUserId);
        }
```

`resolveNotificationUid`（定义在 7287 行）区分两种身份：`opPkg` 是发起调用的包，`pkg` 是通知署名的包。两者不一致时（代投场景，如 `notifyAsPackage`），要求调用方持有 `UPDATE_APP_NOTIFICATION_STATS` 权限，否则只能署自己的名。这道拒绝点保证"横幅上显示的应用名"不可伪造。

#### 拒绝点 4：车机受限 category（6924 行调用，定义 10448-10470 行）

```java
    private void checkRestrictedCategories(final Notification notification) {
        try {
            if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0)) {
                return;
            }
        } catch (RemoteException re) {
            ...
        }
        if (Notification.CATEGORY_CAR_EMERGENCY.equals(notification.category)
                || Notification.CATEGORY_CAR_WARNING.equals(notification.category)
                || Notification.CATEGORY_CAR_INFORMATION.equals(notification.category)) {
            getContext().enforceCallingPermission(
                    android.Manifest.permission.SEND_CATEGORY_CAR_NOTIFICATIONS,
                    String.format("Notification category %s restricted",
                            notification.category));
        }
    }
```

这是 framework 服务端为 automotive 形态保留的少数特化之一：仅在设备声明 `FEATURE_AUTOMOTIVE` 时生效，把三个车机专属 category（紧急/告警/信息）圈进 `SEND_CATEGORY_CAR_NOTIFICATIONS` 权限（signature|privileged 级）。普通第三方应用伪装车控告警，在这一道就被 SecurityException 拦死。它与第三部车机 HUN 判定（17 章）里的 `HunImportance` Z 序、紧急通知置顶构成同一条"车机告警特权"设计线的两端。治理篇第 7.3 节从打扰治理视角讲过这条特权线，本文在 17、18 章讲它在横幅侧的执行端。

#### 拒绝点 5：服务端 fixNotification 与全屏意图剥权（6926-6935 行调用，定义 7107-7129 行）

```java
        // Fix the notification as best we can.
        try {
            fixNotification(notification, pkg, tag, id, userId);
        } catch (Exception e) {
            if (notification.isForegroundService()) {
                throw new SecurityException("Invalid FGS notification", e);
            }
            Slog.e(TAG, "Cannot fix notification", e);
            return;
        }
```

服务端 fix 里与横幅直接相关的是全屏意图剥权（7122-7128 行）：

```java
        if (notification.fullScreenIntent != null && ai.targetSdkVersion >= Build.VERSION_CODES.Q) {
            int fullscreenIntentPermission = mPackageManagerClient.checkPermission(
                    android.Manifest.permission.USE_FULL_SCREEN_INTENT, pkg);
            if (fullscreenIntentPermission != PERMISSION_GRANTED) {
                notification.fullScreenIntent = null;
```

无 `USE_FULL_SCREEN_INTENT` 权限（targetSdk ≥ Q 起）的应用，其 fullScreenIntent 字段被静默摘除。fullScreenIntent 是横幅体系里的特殊成员：它在第二部里既是一道独立判定分支（7.5 节），又让横幅进入 sticky 态不自动收回（9.4 节）、进入 pinned 模式（9.7 节）。这道剥权意味着：**普通应用从 A13 起无法靠 fullScreenIntent 获得"来电式"的持久横幅**，只能拿到普通 HUN 待遇。注意"静默"二字——不抛异常、不给回调，应用侧无感知，这是排查"为什么我的全屏意图没生效"的第一检查点。

另注意异常处理的差别：前台服务（FGS）通知 fix 失败抛 SecurityException 让调用方知道，普通通知 fix 失败静默 return——普通通知死在这一道时无任何声响。

#### 拒绝点 6：FGS 更新策略（6942-6952 行）

```java
        final ServiceNotificationPolicy policy = mAmi.applyForegroundServiceNotification(
                notification, tag, id, pkg, userId);
        if (policy == ServiceNotificationPolicy.UPDATE_ONLY) {
            // Proceed if the notification is already showing/known, otherwise ignore
            if (!isNotificationShownInternal(pkg, tag, id, userId)) {
                reportForegroundServiceUpdate(false, notification, id, pkg, userId);
                return;
            }
        }
```

`ActivityManagerInternal.applyForegroundServiceNotification` 返回 FGS 通知的发布策略。`UPDATE_ONLY` 语义：该 FGS 通知只允许更新已展示的同 key 通知，不许新发——新发被静默吞掉（return，不抛）。这是 A12 起 FGS 通知治理的一环：短命 FGS 不许弹新通知打扰用户。对横幅的含义：FGS 通知的**首次**发布可能根本进不了系统，后续更新才可见；而更新型通知在手机侧横幅判定里另有 old-when 门槛（7.2 节第 10 道）。

#### 记账步骤 A：包装 StatusBarNotification（6956-6958 行）

```java
        final StatusBarNotification n = new StatusBarNotification(
                pkg, opPkg, id, tag, notificationUid, callingPid, notification,
                user, null, System.currentTimeMillis());
```

`StatusBarNotification`（SBN，定义在 `frameworks/base/core/java/android/service/notification/StatusBarNotification.java`，599 行）是通知的跨进程载体：Notification 本体＋身份五元组（pkg/opPkg/id/tag/uid）＋用户＋投递时间戳。注意最后一个参数 `System.currentTimeMillis()` 是 postTime 的初值——第二部 old-when 门槛（7.2 节第 10 道）判断"通知是否太陈旧"用的 when 字段则来自 `Notification.when`，两者不要混淆。

#### 拒绝点 7：channel 必须存在（6961-6992 行）

```java
        String channelId = notification.getChannelId();
        ...
        final NotificationChannel channel = mPreferencesHelper.getConversationNotificationChannel(
                pkg, notificationUid, channelId, shortcutId,
                true /* parent ok */, false /* includeDeleted */);
        if (channel == null) {
            final String noChannelStr = "No Channel found for " + "pkg=" + pkg ...
            Slog.e(TAG, noChannelStr);
            ...
                doChannelWarningToast(notificationUid,
                        "Developer warning for package \"" + pkg + "\"\n" +
                        "Failed to post notification on channel \"" + channelId + "\"\n" +
                        "See log for more details");
            return;
        }
```

O 之后的铁律：通知必须挂在一个已注册的 channel 上，查不到 channel（含 channel 已删）就拒发。开发期设备上还会弹一个开发者警告 Toast 提示"channel 未注册"。这道拒绝点是横幅链的地基——因为 importance 的唯一合法来源就是 channel（第 3 章），没有 channel 就没有 importance，也就谈不上任何展示形态。`getConversationNotificationChannel` 的 `parent ok` 参数允许会话型通知回落到父 channel。

#### 记账步骤 B：构造 NotificationRecord（6994-7000 行）

```java
        final NotificationRecord r = new NotificationRecord(getContext(), n, channel);
        r.setIsAppImportanceLocked(mPermissionHelper.isPermissionUserSet(pkg, userId));
        r.setPostSilently(postSilently);
        r.setFlagBubbleRemoved(false);
        r.setPkgAllowedAsConvo(mMsgPkgsAllowedAsConvos.contains(pkg));
        boolean isImportanceFixed = mPermissionHelper.isPermissionFixed(pkg, userId);
        r.setImportanceFixed(isImportanceFixed);
```

`NotificationRecord` 是服务端为每条通知开的档案（A13 里旧类 RankingRecord 已并入本类，1823 行）。构造时 channel 被塞进档案，importance 的初值在构造函数里就地算出（3.2 节）。两个权限位同步记账：`isAppImportanceLocked`（用户是否动过该应用的通知设置）与 `isImportanceFixed`（设备策略是否锁死了该应用的 importance，如企业管控）——它们决定后续三层裁决里助手打分是否生效（3.3 节）。

#### 记账步骤 C：FGS 的 importance 系统抬升（7002-7022 行）

```java
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            final boolean fgServiceShown = channel.isFgServiceShown();
            if (((channel.getUserLockedFields() & NotificationChannel.USER_LOCKED_IMPORTANCE) == 0
                        || !fgServiceShown)
                      && (r.getImportance() == IMPORTANCE_MIN
                              || r.getImportance() == IMPORTANCE_NONE)) {
                // Increase the importance of foreground service notifications unless the user had
                // an opinion otherwise (and the channel hasn't yet shown a fg service).
                if (TextUtils.isEmpty(channelId)
                        || NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                    r.setSystemImportance(IMPORTANCE_LOW);
                } else {
                    channel.setImportance(IMPORTANCE_LOW);
                    r.setSystemImportance(IMPORTANCE_LOW);
```

FGS 通知如果 channel importance 低到 MIN/NONE，系统把它抬到 LOW——保证前台服务在通知栏至少可见（用户明确锁过 importance 且该 channel 已展示过 FGS 的除外）。**抬到 LOW 而不是 HIGH**：FGS 通知可见但永不因这段代码获得横幅资格。这是"系统抬升"写入 `setSystemImportance` 的第一现场，三层裁决见 3.3 节。

#### 拒绝点 8：checkDisqualifyingFeatures 终审（7042-7045 行调用，定义 7325 行起）

```java
        if (!checkDisqualifyingFeatures(userId, notificationUid, id, tag, r,
                r.getSbn().getOverrideGroupKey() != null)) {
            return;
        }
```

同步段最后一道是一个复合终审，内含七小项（均在 7325-7456 行区间）：

1. instant app 禁发通知（7343-7351 行）；
2. 入队速率限制（7358-7368 行，`appEnqueueRate > mMaxPackageEnqueueRate` 即拒）；
3. 单应用条数上限（7373-7381 行，`count >= MAX_PACKAGE_NOTIFICATIONS` 即拒）；
4. bubble 与 action 的 PendingIntent 必须可变（7384-7416 行，违反抛 IllegalArgumentException）——这是 S 起 PendingIntent 可变性新规在通知侧的执行点之一；
5. CallStyle 通知必须是 FGS 或带全屏意图（7418-7426 行）；
6. snooze 中的通知拒发（7429-7442 行）——用户在横幅上选过"稍后提醒"的 key，在 snooze 窗口内重发无效；
7. 被关通知权限的应用压制（7444-7456 行）：

```java
        // blocked apps
        boolean isBlocked = !areNotificationsEnabledForPackageInt(pkg, uid);
        synchronized (mNotificationLock) {
            isBlocked |= isRecordBlockedLocked(r);
        }
        if (isBlocked && !(n.isMediaNotification() || isCallNotification(pkg, uid, n))) {
```

注意媒体通知与通话通知对被关权限的应用**豁免压制**（括号里的取反条件）——用户关了某应用的通知，它的媒体播放通知照样活着，这是媒体链特殊地位的又一处体现。权限判定本体（7491-7493 行）只是转问 `mPermissionHelper.hasPermission(uid)`。

#### 记账步骤 D：投递异步段（7078-7086 行）

```java
        // Need escalated privileges to get package importance
        final long token = Binder.clearCallingIdentity();
        boolean isAppForeground;
        try {
            isAppForeground = mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        mHandler.post(new EnqueueNotificationRunnable(userId, r, isAppForeground,
                SystemClock.elapsedRealtime()));
```

同步段到此为止。`EnqueueNotificationRunnable.run`（7813-7909 行）在 handler 线程做入队记账：snooze 到点的通知转 snoozeLocked 重发（7832-7851 行）、档案入 `mEnqueuedNotifications`（7853 行）、同 key 旧档案的排序信息被新档案继承（7858-7862 行 `r.copyRankingInformation(old)`，保证更新型通知不丢既有位次）、组关系处理（7876 行），最后按有无通知助手（NAS）分两路：有 NAS 则先送助手、延迟 `DELAY_FOR_ASSISTANT_TIME` 再发布（7896-7901 行），无 NAS 直接 post `PostNotificationRunnable`（7902-7904 行）。

`PostNotificationRunnable.run`（7931-8068 行）是发布现场：封禁复查（7949、7972-7979 行）、挂起隐藏处理（7981-7986 行）、档案正式入 `mNotificationList`（7996-8017 行），随后三行是本文关心的核心（8030-8037 行）：

```java
                    mRankingHelper.extractSignals(r);
                    mRankingHelper.sort(mNotificationList);
                    final int position = mRankingHelper.indexOf(mNotificationList, r);

                    int buzzBeepBlinkLoggingCode = 0;
                    if (!r.isHidden()) {
                        buzzBeepBlinkLoggingCode = buzzBeepBlinkLocked(r);
                    }
```

入列时还有一次横幅相关的初判（8002-8004 行）：

```java
                        final boolean isInterruptive = isVisuallyInterruptive(null, r);
                        r.setInterruptive(isInterruptive);
                        r.setTextChanged(isInterruptive);
```

`isVisuallyInterruptive` 与 `buzzBeepBlinkLocked` 的分工见 4.5、4.6 节；`extractSignals` 是第 4 章的主角。最后 8041 行 `mListeners.notifyPostedLocked(r, old)` 把消息发给所有监听端——第 5 章展开。

#### 2.1 小结：同步段的四问

- **规则**：八道拒绝点各管一件事——身份（2、3）、车机特权 category（4）、字段合法性与权限剥离（1、5）、FGS 策略（6）、channel 存在性（7）、资格终审（8）。
- **设置者**：channel 由应用注册、importance 由用户/系统/助手三方覆写（第 3 章）、权限位由 `PermissionHelper` 与 `PreferencesHelper` 记账。
- **执行者**：同步段在 Binder 线程执行（拒绝点全部同步生效，调用方能拿到异常）；发布段在 NMS handler 线程执行（拒绝点 8 之后的复查失败都静默）。
- **后果**：任何一道拒绝点失败，通知不存在于系统，横幅无从谈起；其中 4、5、6、8 的失败是静默或半静默的，应用侧排查要靠 logcat 的 `NotificationService` 标签。

### 3. importance 的出生与三层裁决

importance 是横幅的第一命门：手机侧亮屏判定的第 8 道门槛（7.2 节）、灭屏链的第 6 道门槛（7.3 节）、车机侧 AOSP 判定的第 5 道否决（17.1 节）都拿它说话。这一章讲清它从哪来、谁能改、最终值怎么算。

#### 3.1 常量语义：HIGH 的注释里写着 "peeks"

七个常量定义在 `frameworks/base/core/java/android/app/NotificationManager.java`（503-547 行）：

```java
    public static final int IMPORTANCE_UNSPECIFIED = -1000;   // 509 行
    public static final int IMPORTANCE_NONE = 0;              // 514 行
    public static final int IMPORTANCE_MIN = 1;               // 524 行
    public static final int IMPORTANCE_LOW = 2;               // 530 行
    public static final int IMPORTANCE_DEFAULT = 3;           // 536 行
    public static final int IMPORTANCE_HIGH = 4;              // 542 行
    public static final int IMPORTANCE_MAX = 5;               // 547 行
```

每档的官方注释把展示形态直接写死在语义里（行号为注释所在）：

- NONE（511-513 行）："A notification with no importance: does not show in the shade."——完全不展示。
- MIN（516-517 行）："only shows in the shade, below the fold."——只在面板折叠区以下。
- LOW（526-528 行）："Shows in the shade, and potentially in the status bar ... but is not audibly intrusive."——可见、无声。
- DEFAULT（532-534 行）："shows everywhere, makes noise, but does not visually intrude."——有声、**无横幅**。
- HIGH（538-540 行）："shows everywhere, makes noise and peeks. May use full screen intents."——peeks 即横幅；HIGH 起才允许窥视，也才可能用全屏意图。

DEFAULT 与 HIGH 的分界就是"响不响"与"弹不弹"的分界：DEFAULT 可以让手机响，但不会把卡片推到脸上。这条语义在代码里的同构使用：排序比较器第一级以 `>= IMPORTANCE_DEFAULT` 分高低桶（`NotificationComparator.java:64-67`），打扰驻留提取器同样以 `>= IMPORTANCE_DEFAULT` 为门槛（4.3 节），而横幅门槛是更严的 `>= IMPORTANCE_HIGH`。

#### 3.2 出生：初值从 channel 抄来

`NotificationRecord` 构造函数的 257 行执行 `mImportance = calculateInitialImportance();`，其实现在 `frameworks/base/services/core/java/com/android/server/notification/NotificationRecord.java`（385-390 行）：

```java
    private int calculateInitialImportance() {
        final Notification n = getSbn().getNotification();
        int importance = getChannel().getImportance();  // Post-channels notifications use this
        mInitialImportanceExplanationCode = getChannel().hasUserSetImportance()
                ? MetricsEvent.IMPORTANCE_EXPLANATION_USER
                : MetricsEvent.IMPORTANCE_EXPLANATION_APP;
```

387 行是全链路里 importance 的出生地：**通知自己带不来 importance，它只能继承 channel 的当前值**；`Notification.Builder` 上那些 `setPriority` 是 O 之前的遗产，对展示形态不再直接起作用。解释码同时记账这个值来自用户设定（USER）还是应用设定（APP）——后续统计与三层裁决都用得上。

#### 3.3 三层裁决：基线、助手、系统

最终值由 `NotificationRecord.calculateImportance`（915-938 行）合成：

```java
    protected void calculateImportance() {
        ...
        mImportance = calculateInitialImportance();
        mImportanceExplanationCode = mInitialImportanceExplanationCode;

        // Consider Notification Assistant and system overrides to importance. If both, system wins.
        if (!getChannel().hasUserSetImportance()
                && mAssistantImportance != IMPORTANCE_UNSPECIFIED
                && !mImportanceFixed) {
            mImportance = mAssistantImportance;
            mImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_ASST;
        }
        if (mSystemImportance != IMPORTANCE_UNSPECIFIED) {
            mImportance = mSystemImportance;
            mImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM;
        }
    }
```

三层从低到高：

1. **基线层**：channel 当前值（3.2 节）。
2. **助手层**：通知助手（NAS，如设备上的智能分类服务）经 `setAssistantImportance`（885-889 行）提交的打分。生效条件有三个：用户没亲手设过该 channel 的 importance、助手确实给过分、设备策略没锁死（`mImportanceFixed` 为假）。用户意见压过机器意见。
3. **系统层**：`setSystemImportance`（872-877 行，写后立即重算）无条件压过前两层。2 章记账步骤 C 的 FGS 抬升（抬到 LOW）就走这层。

写入口只有这两个 setter，`mImportance` 字段没有公开的直写方法；读口 `getImportance()`（941-943 行）。重算的触发点在排序信号提取阶段：`ImportanceExtractor.process` 的核心就一行（`ImportanceExtractor.java:52`）：

```java
        record.calculateImportance();
```

也就是说，助手打分先记账、到 extractSignals 时才生效合成——这解释了为什么 `EnqueueNotificationRunnable` 里 NAS 路径要延迟发布（2 章记账步骤 D）：等助手把分打完再排序，位次和 importance 才是终值。

#### 3.4 channel 权限模型：应用只能降，用户改动即上锁

importance 的源头是 channel，channel 的写权限模型在 `frameworks/base/services/core/java/com/android/server/notification/PreferencesHelper.java`（3089 行）。核心规则一句话：**应用建 channel 时说了算；用户改过之后，应用只能往低调，不能往高调。**

应用侧更新已存在的 channel（`createNotificationChannel` 入口 1010 行，判定 1061-1075 行）：

```java
                // Apps are allowed to downgrade channel importance if the user has not changed any
                // fields on this channel yet.
                ...
                final int previousExistingImportance = existing.getImportance();
                ...
                if (existing.getUserLockedFields() == 0 &&
                        channel.getImportance() < existing.getImportance()) {
                    existing.setImportance(channel.getImportance());
                    needsPolicyFileChange = true;
                }
```

两个条件同时满足才接受应用的新值：用户从未锁过任何字段（`getUserLockedFields() == 0`）、新值比旧值**更低**。换句话说：应用把消息 channel 从 HIGH 降到 DEFAULT 可以，从 DEFAULT 升回 HIGH 不行——升级请求被静默忽略，channel 维持原值。

新建 channel 时的防线（1115-1142 行）：importance 越界（< NONE 或 > MAX）抛 IllegalArgumentException（1115-1118 行）；应用申报的原始值被单独记账（1142 行 `channel.setOriginalImportance(channel.getImportance());`），供后续对比"应用想要多高 vs 用户压到多低"；用户锁字段被强制清空（1130 行调用，1170-1172 行定义），防止应用在新 channel 上伪造"用户已设定"状态。

用户侧修改走 `updateNotificationChannel(pkg, uid, channel, fromUser)`（1199 行），1217-1222 行按来源分岔：

```java
            if (fromUser) {
                updatedChannel.lockFields(channel.getUserLockedFields());
                lockFieldsForUpdateLocked(channel, updatedChannel);
            } else {
                updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
            }
```

用户改动 importance 即上锁（`lockFieldsForUpdateLocked` 2093 行，判定 2104-2106 行）：

```java
        if (original.getImportance() != update.getImportance()) {
            update.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        }
```

显式解锁只有 `unlockNotificationChannelImportance`（1177-1190 行，1189 行 `channel.unlockFields(USER_LOCKED_IMPORTANCE);`），供系统场景（如用户重置应用偏好）调用。

对横幅的推论：**应用无法靠反复调用 createNotificationChannel 把自己的 importance 刷回 HIGH**。一旦用户在设置里动过这个 channel（包括调低），应用侧的升级通道永久关闭，横幅资格从此由用户掌权；反过来，应用想"改邪归正"降低打扰倒是随时可以。3.3 节助手层的生效条件 `!hasUserSetImportance()` 与本模型同源：用户意志是 importance 体系的最高权威（系统层除外，而系统层只用于 FGS 保底这类基础设施场景）。

#### 3.5 四问小结

- **规则**：importance ∈ [-1000, 5]，横幅门槛 HIGH(4)；初值抄 channel，助手与系统可覆写，系统压一切。
- **设置者**：应用（建 channel 时的初值、事后只降不升）、用户（设置界面，改后即锁）、NAS 助手（打分）、系统（FGS 保底抬升）。
- **执行者**：`NotificationRecord.calculateImportance` 在 `ImportanceExtractor.process` 里被触发重算；结果经 RankingMap 下发（5.2 节）并被 SystemUI 判定层直接消费（7.2 节第 8 道）。
- **后果**：importance < HIGH 的通知在手机侧永不弹横幅（亮屏链）、< DEFAULT 连 AOD pulse 都没有（灭屏链）；在车机侧 AOSP 实现同样 < HIGH 即否决（17.1 节第 5 道）。

### 4. 排序管线：决定横幅命运的三个字段

发布段 8030-8032 行的 `extractSignals → sort → indexOf` 是服务端对通知的最后加工。对横幅而言，这条管线产出三个关键字段：**importance**（第 3 章）、**intercepted**（zen 拦截位）、**suppressedVisualEffects**（视觉抑制位掩码）。后两者由 ZenModeExtractor 写入，最终打进发给 SystemUI 的 Ranking（5.2 节），在手机侧判定层分别对应第 7 道门槛的 DND 抑制与 suppressor 体系（7.2、7.4 节）。排序本身（位次）不影响横幅弹出与否，只影响面板里的位置，治理篇第 5 章已详述，本文只带过结构。

#### 4.1 extractSignals：十一个提取器按配置序执行

`RankingHelper.extractSignals`（`RankingHelper.java:93-110`）是个薄调度：

```java
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            NotificationSignalExtractor extractor = mSignalExtractors[i];
            try {
                RankingReconsideration recon = extractor.process(r);
                if (recon != null) {
                    mRankingHandler.requestReconsideration(recon);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "NotificationSignalExtractor failed.", t);
            }
        }
```

两点结构信息：单个提取器崩溃不拖垮管线（catch Throwable）；提取器可以返回 `RankingReconsideration`，让系统在延时后重跑裁决（异步信号，如联系人亲和度查询、4.3 节的打扰驻留到期）。

执行顺序不由代码硬编码，而由资源数组决定，`frameworks/base/core/res/res/values/config.xml`（3059-3077 行）：

```xml
    <string-array name="config_notificationSignalExtractors">
        <!-- many of the following extractors depend on the notification channel, so this
        extractor must come first -->
        <item>com.android.server.notification.NotificationChannelExtractor</item>
        <item>com.android.server.notification.NotificationAdjustmentExtractor</item>
        <item>com.android.server.notification.BubbleExtractor</item>
        <!-- depends on AdjustmentExtractor-->
        <item>com.android.server.notification.ValidateNotificationPeople</item>
        <item>com.android.server.notification.PriorityExtractor</item>
        <!-- depends on PriorityExtractor -->
        <item>com.android.server.notification.ZenModeExtractor</item>
        <item>com.android.server.notification.ImportanceExtractor</item>
        <!-- depends on ImportanceExtractor-->
        <item>com.android.server.notification.NotificationIntrusivenessExtractor</item>
        <item>com.android.server.notification.VisibilityExtractor</item>
        <!-- Depends on ZenModeExtractor -->
        <item>com.android.server.notification.BadgeExtractor</item>
        <item>com.android.server.notification.CriticalNotificationExtractor</item>
    </string-array>
```

NMS 在 2417 行读取该数组实例化提取器链。十一个提取器各一行职责（文件均在 `frameworks/base/services/core/java/com/android/server/notification/`，行号为类声明）：

| 序 | 提取器 | 职责 | 与横幅的关系 |
|---|---|---|---|
| 1 | NotificationChannelExtractor（:28） | 把 channel 绑定信息写入 record | importance 的前置 |
| 2 | NotificationAdjustmentExtractor（:27） | 落地 NAS/应用提交的 adjustment 修正 | 助手打分的入口 |
| 3 | BubbleExtractor（:45） | 气泡资格抽取（三层开关＋五项硬资格） | 气泡与横幅互斥（7.2 节第 6 道） |
| 4 | ValidateNotificationPeople（—） | 联系人亲和度异步校验 | 只影响排序 |
| 5 | PriorityExtractor（:26） | DND 旁路（canBypassDnd）标注 | 决定 zen 下能否发声 |
| 6 | ZenModeExtractor（:27） | 把 zen 拦截裁决写入 record | **intercepted/suppressedVisualEffects 的写入者** |
| 7 | ImportanceExtractor（:28） | 触发三层裁决重算 | **importance 终值的执行者** |
| 8 | NotificationIntrusivenessExtractor（:35） | 吵闹且重要的新鲜通知临时置顶 | 排序驻留，非横幅 |
| 9 | VisibilityExtractor（:29） | 锁屏可见性（三道门两级降级） | 锁屏横幅内容打码的上游 |
| 10 | BadgeExtractor（:31） | 角标可见性五级开关取与 | 无关横幅 |
| 11 | CriticalNotificationExtractor（:38） | 车机紧急通知标记（仅系统应用） | 车机侧紧急横幅的标记源 |

顺序注释里写明三处依赖：channel 必须最先（大家依赖它）、ZenMode 依赖 Priority、Intrusiveness 依赖 Importance。这个数组是 OEM 可覆写的配置点——车机产品可以增删提取器，这也是 AAOS 与手机在裁决层产生行为差异的合法通道。

#### 4.2 zen 裁决的写入与消费

`ZenModeExtractor.process`（55-58 行）把勿扰裁决写成两个字段：

```java
        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(
                    mZenModeHelper.getConsolidatedNotificationPolicy().suppressedVisualEffects);
```

裁决本体在 `ZenModeHelper.shouldIntercept`（`ZenModeHelper.java:238-242`）：

```java
    public boolean shouldIntercept(NotificationRecord record) {
        synchronized (mConfig) {
            return mFiltering.shouldIntercept(mZenMode, mConsolidatedPolicy, record);
        }
    }
```

zen 状态变化时对存量通知重刷走 `applyZenModeLocked`（`NotificationManagerService.java:9242-9251`）。消费点有三处与横幅相关：警效裁决里 intercepted 直接静音（`shouldMuteNotificationLocked`，8571 行起，判定 8590-8593 行 `if (record.isIntercepted()) { return true; }`）；`suppressedVisualEffects` 的 `SUPPRESSED_EFFECT_STATUS_BAR` 位控制状态栏图标抑制（8389-8390 行）；两个字段都打进 Ranking 发给监听端（10561-10563 行，见 5.2 节）。SystemUI 侧，DND 对横幅的抑制不直接读 intercepted，而是读 `entry.shouldSuppressPeek()`（7.2 节第 7 道）——该标志的上游正是这里的 suppressedVisualEffects 经 Ranking 传导。zen 规则引擎本身（时段、条件、白名单）见治理篇第 4 章，本文不重复。

#### 4.3 打扰驻留：吵闹的新鲜通知插队十秒

`NotificationIntrusivenessExtractor.java`（109 行）与横幅无直接关系，但它解释了"为什么刚响过的通知排在面板最上面"，且其判据（≥DEFAULT 且有声/振动/全屏）常被误认为横幅判定，故完整走读。核心 56-95 行：

```java
        if (record.getFreshnessMs(System.currentTimeMillis()) < HANG_TIME_MS
                && record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT) {
            if (record.getSound() != null && record.getSound() != Uri.EMPTY) {
                record.setRecentlyIntrusive(true);
            }
            if (record.getVibration() != null) {
                record.setRecentlyIntrusive(true);
            }
            if (record.getNotification().fullScreenIntent != null) {
                record.setRecentlyIntrusive(true);
            }
        }

        if (!record.isRecentlyIntrusive()) {
            return null;
        }

        return new RankingReconsideration(record.getKey(), HANG_TIME_MS) {
            @Override
            public void work() { }
            @Override
            public void applyChangesLocked(NotificationRecord record) {
                if ((System.currentTimeMillis() - record.getLastIntrusive()) >= HANG_TIME_MS) {
                    record.setRecentlyIntrusive(false);
                }
            }
        };
```

`HANG_TIME_MS = 10000`（42 行）。机制：发布后 10 秒内、importance ≥ DEFAULT、且带声音/振动/全屏意图之一的通知被打上 `recentlyIntrusive` 标记；`RankingHelper.sort` 编码 globalSortKey 时该标记参与位次（165-174 行，`intrsv` 位，且要求 `getImportance() > IMPORTANCE_MIN`）；同时返回一个 10 秒后执行的重审任务，到点清除标记、通知落回自然位次。类 Javadoc（28-29 行）一句话概括："notices noisy notifications and marks them to get a temporary ranking bump."——吵闹的新通知插队，但只插队一会儿。

#### 4.4 sort：十三级决胜链（概览）

`RankingHelper.sort`（113-181 行）两段排序：先按 `mPreliminaryComparator` 初排（125 行），选出每组的代理（129-137 行），把组级信息编码进 globalSortKey（140-175 行），再按 `mFinalComparator` 终排（180 行）。比较器 `NotificationComparator.java` 的 compare（57 行起）注释即目录（58-63 行）：

```java
        //【核心流程】通知排序的十三级决胜链，从粗到细逐级比下去：
        // 1.重要度分桶（>=DEFAULT 为高）；2.NAS 助手打分（桶内）；3.彩色化；
        // 4.重要持续型（通话/媒体）；5.即时消息；6.联系人（亲和度近者先）；
        // 7.系统 MAX 桶（android 与 SystemUI）；8.重要度本身；9.亲和度；
        // 10.应用级 priority；11.app 级 priority；12.是否打扰型；13.时间（新者先）。
        // 前一级分出胜负即短路返回。
```

第一级分桶（64-72 行）：

```java
        final int leftImportance = left.getImportance();
        final int rightImportance = right.getImportance();
        final boolean isLeftHighImportance = leftImportance >= IMPORTANCE_DEFAULT;
        final boolean isRightHighImportance = rightImportance >= IMPORTANCE_DEFAULT;

        if (isLeftHighImportance != isRightHighImportance) {
            // by importance bucket, high importance higher than low importance
            return -1 * Boolean.compare(isLeftHighImportance, isRightHighImportance);
        }
```

排序决定面板位次与"哪条先被看到"，不决定横幅；本文到此为止，十三级的完整展开见治理篇第 5 章。

#### 4.5 isVisuallyInterruptive：服务端的"视觉打断"初判不是横幅判定

发布段 8002-8004 行给新通知打 `interruptive` 标记，判定函数 `isVisuallyInterruptive`（8124 行起）的逻辑：组摘要不算（isGroupSummary → false）；新通知（old == null）算；FGS 通知不算（注释原文 "users consider them one 'session'"）；更新型通知比较新旧 EXTRA_TITLE、EXTRA_TEXT、进度完成态，任一变化即算（8170-8205 行区间）。

必须澄清一个常见误解：这个 flag **不是**"要不要弹横幅"的服务端裁决。它的消费点是统计与警效——`hasRecordedInterruption` 去重打点（3342 行）、buzz/beep/blink 里更新型通知的降噪（8617 行 `if (record.isUpdate && !record.isInterruptive() && isBubbleOrOverflowed`）、气泡状态迁移（9043-9054 行）。横幅判定完全在 SystemUI 进程内发生（第二部），服务端只负责把裁决原料（importance、intercepted、suppressedVisualEffects）送过去。

#### 4.6 buzzBeepBlink：警效裁决（概览）

发布段 8034-8037 行调用 `buzzBeepBlinkLocked`（定义 8357 行）决定这条通知响不响、震不震、闪不闪。它与横幅判定是**并行**的两条消费线：同一条 importance ≥ HIGH 的通知，可能响＋弹（正常），也可能在 zen 下不响但仍进入 SystemUI 的横幅判定（是否弹由 SystemUI 的 DND 门槛再拦，7.2 节第 7 道）。静音判据 `shouldMuteNotificationLocked`（8571 行）里 zen 拦截位的使用见 4.2 节。全链展开见治理篇第 6 章。

### 5. 分发：notifyPostedLocked 把什么交给监听端

#### 5.1 两跳到 Binder

`mListeners.notifyPostedLocked(r, old)`（8041 行调用）进入 `NotificationListeners` 内部类（声明 11357 行，字段 688 行，构造注入 2503-2504 行）。入口 11741-11743 行先查锁定模式（lockdown 下直接 return，不发任何监听事件），主体 11751 行起为每个监听器裁剪并打包，最后两跳异步送达（12060 → 12065 行）：

```java
        // notifyPosted：异步投发布。
        private void notifyPosted(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationPosted(sbnHolder, rankingUpdate);
```

#### 5.2 打包物：裁剪版 SBN ＋ RankingMap

每个监听器拿到的东西有两件。第一件是按监听器能力裁剪过的 SBN（11805 行 `trimCache.ForListener(info)`）——不支持额外字段的旧监听端拿到的 Notification 会被剥掉部分 extras。第二件是 `NotificationRankingUpdate`（11785 行 `makeRankingUpdateLocked(info)` 生成），它为每条可见记录装配一个 `Ranking` 对象（10543 行起），装配现场 10556-10565 行：

```java
            final NotificationListenerService.Ranking ranking =
                    new NotificationListenerService.Ranking();
            ranking.populate(
                    key,
                    rankings.size(),
                    !record.isIntercepted(),
                    record.getPackageVisibilityOverride(),
                    record.getSuppressedVisualEffects(),
                    record.getImportance(),
```

第 4 章的三个关键字段在这里完成跨进程装运：`!record.isIntercepted()`（zen 未拦截 → matchesInterruptionFilter 为真）、`getSuppressedVisualEffects()`（视觉抑制位掩码）、`getImportance()`（三层裁决终值）。SystemUI 侧 `entry.getImportance()`、`entry.shouldSuppressPeek()` 等判定原料（7.2 节）全部源于这次装运——**服务端裁决、客户端执行**的分工在数据结构上就是这份 RankingMap。

#### 5.3 ManagedServices：监听端的连接账本

`notifyPostedLocked` 遍历的 `getServices()` 由 `ManagedServices`（`ManagedServices.java`，2169 行，类声明 108 行）维护。它是"系统绑定的应用侧服务"的生命周期基类：审批（用户在设置里授权哪些 NotificationListenerService 可监听）、绑定/重绑、按用户与 profile 过滤。取账本的现场（222-227 行）：

```java
    protected List<ManagedServiceInfo> getServices() {
        synchronized (mMutex) {
            List<ManagedServiceInfo> services = new ArrayList<>(mServices);
            return services;
        }
    }
```

对横幅的意义：SystemUI 的通知监听（第二部第 6 章的 `NotificationListener`）与任何第三方监听（如无障碍工具、手表伴侣）在这套账本里地位相同，都经用户授权、都拿同一份裁剪后数据；SystemUI 没有服务端后门，它拿到的一切信息第三方监听同样能拿到（裁剪级别允许的话）。

#### 5.4 四问小结（第一部收束）

- **规则**：通知必须过八道拒绝点才存在；存在后 importance 由三层裁决合成；zen 拦截与视觉抑制由提取器管线写入；三者随 RankingMap 下发。
- **设置者**：channel/importance 的权限模型见 3.4-3.5 节；intercepted/suppressedVisualEffects 由 zen 策略与用户 DND 设置决定（治理篇第 4 章）。
- **执行者**：NMS handler 线程（发布段）、提取器链（extractSignals）、ListenerHelper（分发）。
- **后果**：SystemUI 收到的每条通知自带裁决字段；横幅判定（第二部）不再回头问服务端任何事——它是纯客户端的、基于 RankingMap 的重判。

---

## 第二部 手机形态：SystemUI 对横幅的判定、展示与收回

### 6. 收帖与入账：A13 的通知事件不经 CommandQueue

#### 6.1 监听端真身：NotificationListener

SystemUI 进程里的通知监听服务是 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/NotificationListener.java`（337 行）：

```java
public class NotificationListener extends NotificationListenerWithPlugins implements PipelineDumpable
```

（64 行。注意包路径：在 `statusbar/` 直下，不在 `statusbar/phone/`。）它注册为全用户监听（`registerAsSystemService`，264-272 行，USER_ALL）。系统回调的 override 都在这个类里，`onNotificationPosted`（150-162 行）：

```java
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn != null && !onPluginNotificationPosted(sbn, rankingMap)) {
            mMainExecutor.execute(() -> {
                processForRemoteInput(sbn.getNotification(), mContext);
                for (NotificationHandler handler : mNotificationHandlers) {
                    handler.onNotificationPosted(sbn, rankingMap);
                }
            });
        }
    }
```

三个要点：先给插件一次拦截机会（`onPluginNotificationPosted` 返回 true 即吞掉，见 6.2 节）；转主线程执行；遍历分发给所有注册过的 `NotificationHandler`（接口定义 318-336 行）。连接建立时（`onListenerConnected`，112-147 行）先取 `getActiveNotifications()` 全量补账（136-140 行逐条当 posted 分发），再发 `onNotificationsInitialized()`（141-143 行）——SystemUI 重启后横幅世界从这次全量补账重建。移除回调（165-180 行）与排序更新回调（183-195 行）同构；排序更新多一层去抖：事件先入 `mRankingMapQueue`，主线程延迟合并，上限 `MAX_RANKING_DELAY_MILLIS = 500L`（68 行，派发 202-233 行）——半秒内的连环排序变化只刷一次。

一个必须钉死的树况事实：**A13 的通知增删事件不经过 CommandQueue**。在 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/CommandQueue.java`（1803 行）里 grep `onNotificationPosted` / `NotificationsCallback` / `notificationVisibilityChanged` 全部零命中；该文件的 `Callbacks` 接口（210 行）与通知相关的只剩面板动画指令（225 行 `animateExpandNotificationPanel` 等）。旧文章里"通知经 CommandQueue 分发"的说法是 A11 之前的架构，本树不适用。

#### 6.2 插件层：NotificationListenerWithPlugins

父类 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationListenerWithPlugins.java`（212 行，这个才在 `phone/` 包）不含任何系统回调 override，只做插件接线：`onPluginConnected()`（93-96 行）置连接标志并把 `NotificationProvider` 交给每个插件；`onPluginNotificationPosted`（104-112 行）轮询插件，`if (plugin.onNotificationPosted(sbn, rankingMap)) { return true; }`——插件返回 true 即拦截；`getProvider()`（174-210 行）给插件一个反向注入口，插件可经 `addNotification`（193-195 行）向 SystemUI 注入伪通知。对横幅的意义：插件体系有能力在收帖第一现场吞掉或伪造通知，是 OEM 定制横幅行为的合法挂载点。

#### 6.3 攒批：GroupCoalescer

`NotificationListener` 的 handler 注册者是 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coalescer/GroupCoalescer.java`（122-124 行 `listenerService.addNotificationHandler(mListener);`）。它的内部 listener 收到 posted 事件后（135-148 行）做三件事：`maybeEmitBatch(sbn)`（先把之前攒的批冲出去）、`applyRanking(rankingMap)`、`handleNotificationPosted(...)` 判断该事件是否可攒（205-233 行：同组、短时间窗内的事件进 `mCoalescedEvents` 队列）。攒批窗口到期或遇到不可攒事件时 `emitBatch`（257-282 行）把整批经 `onNotificationBatchPosted`（281 行，接口 `BatchableNotificationHandler` 定义 352 行）一次性交出。动机：一组十条通知同时到达时，避免管线跑十遍、列表闪十次。

#### 6.4 入账：NotifCollection

账本是 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/NotifCollection.java`（1146 行）。接线在 `attach(GroupCoalescer)`（206-217 行，215 行 `groupCoalescer.setNotificationHandler(mNotifHandler);`）。`mNotifHandler`（892-936 行）是 `BatchableNotificationHandler` 的匿名实现：单条 posted 走 894-896 行、整批走 899 行、removed 走 904-912 行（两参重载补 `REASON_UNKNOWN`）、排序更新走 915-917 行。

单条入账路径：`onNotificationPosted`（421-427 行）→ `postNotification(sbn, requireRanking(rankingMap, sbn.getKey()))`（424 行）→ `dispatchEventsAndRebuildList("onNotificationPosted")`（426 行）。`postNotification`（482-516 行）是新/改分岔点：新 key 则 487-495 行

```java
        NotificationEntry entry = new NotificationEntry(sbn, ranking, mClock.uptimeMillis());
        ...
        mEventQueue.add(new EntryAddedEvent(entry));
```

已存在则 `entry.setSbn(sbn)` 后发 `EntryUpdatedEvent`（497-515 行）。`dispatchEventsAndRebuildList`（628-644 行）把事件队列逐条派发给所有 `NotifCollectionListener`，最后触发 `mBuildListener.onBuildList(...)` 重建 shade 列表。**横幅支线就是从"事件派发"这一步分出去的**：`HeadsUpCoordinator` 作为 coordinator 监听 EntryAdded/EntryUpdated/EntryRemoved 事件（第 8 章）。

#### 6.5 管线全貌：stage 0-6

接线现场在 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/init/NotifPipelineInitializer.java`（111-116 行）：

```java
        mShadeViewManager = mShadeViewManagerFactory.create(listContainer, stackController);
        mShadeViewManager.attach(mRenderStageManager);
        mRenderStageManager.attach(mListBuilder);
        mListBuilder.attach(mNotifCollection);
        mNotifCollection.attach(mGroupCoalescer);
        mGroupCoalescer.attach(mNotificationService);
```

attach 方向自下而上：数据从监听端流入，逐级向上挂。`dumpPipeline`（133-158 行）列出七个 stage：STAGE 0 SETUP（NotifCoordinatorsImpl，注册全部 coordinator 与分节器）、STAGE 1 LISTEN（NotificationListener）、STAGE 2 BATCH EVENTS（GroupCoalescer）、STAGE 3 COLLECT（NotifCollection）、STAGE 4 BUILD LIST（ShadeListBuilder，过滤/分组/排序）、STAGE 5 DISPATCH RENDER（RenderStageManager）、STAGE 6 UPDATE SHADE（ShadeViewManager）。`NotifPipeline.kt`（261 行）本身只是注册门面（86-90 行注入 collection/listBuilder/renderStageManager；58-74 行 Javadoc 列出 0-13 步钩子顺序）。

关键树况：本树**没有** interruption stage（`VisualInterruptionStageProvider` 全树零命中）。上游完整版 A13 里"横幅判定"是管线中一个独立 stage，产出 `VisualInterruptionDecision`（含 hunBy、bubble、FSI 决策）；本树把判定职责放进了 coordinator 层的 `HeadsUpCoordinator`，判定逻辑本体在 `NotificationInterruptStateProviderImpl`（第 7 章）。功能等价，装配位置不同。

#### 6.6 coordinator 体系与横幅的位置

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coordinator/NotifCoordinators.kt` 注入并注册全部 coordinator（43 行注入 HeadsUpCoordinator，94 行 `mCoordinators.add(headsUpCoordinator)`，101 行把它列进分节序）。分节优先级注释（100 行）：

```
// HeadsUp > FGS > People > Alerting > Silent > Minimized > Unknown/Default
```

横幅分节排第一——列表重建时正在弹横幅的通知永远进最高分节。coordinator 是 A13 管线的"横切面"机制：不改 stage 主链，通过监听 collection 事件＋挂 filter/promoter/sectioner 参与列表构建，横幅、气泡、锁屏、媒体各占一个 coordinator。

### 7. 判定核心：NotificationInterruptStateProviderImpl 的门槛体系

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/interruption/NotificationInterruptStateProviderImpl.java`（613 行）是手机侧横幅判定的唯一权威。文件头注释（59-60 行）自述职责：视觉打断（横幅、AOD pulse、全屏意图）全部经本类裁决，`shouldHeadsUp` 按设备状态分两条判定链。

#### 7.1 入口、分岔与总开关

入口 202-204 行：

```java
    public boolean shouldHeadsUp(NotificationEntry entry) {
        return checkHeadsUp(entry, true);
    }
```

`checkHeadsUp`（207-213 行）按设备状态分岔：

```java
        if (mStatusBarStateController.isDozing()) {
            return shouldHeadsUpWhenDozing(entry, log);
        } else {
            return shouldHeadsUpWhenAwake(entry, log);
        }
```

亮屏链（awake）决定"横幅弹不弹"；灭屏链（dozing）决定"AOD 上 pulse 不 pulse"——两者共享部分门槛但独立成链（7.3 节）。

总开关有两个。编译期常量 `ENABLE_HEADS_UP = true`（68 行）；运行期开关 `mUseHeadsUp` 由 ContentObserver 监听 `Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED` 维护（137-165 行），用户在设置里关掉"浮动通知"即写这个 setting。关闭瞬间的清理在 150 行：

```java
                mHeadsUpManager.releaseAllImmediately();
```

——正在弹的横幅全部立即收回，不等超时。

#### 7.2 亮屏链：十一道门槛逐一过

`shouldHeadsUpWhenAwake`（390-450 行）是一条纯否决链：十一道门槛任何一道命中即 return false（并打对应日志），全部通过才在 448-449 行 `return true`。逐道走读（每道给条件、出处、语义；引文为判定原文）：

**第 1 道：功能开关**（393-396 行）。`if (!mUseHeadsUp) { ... return false; }`——7.1 节的用户设置总闸。

**第 2 道：canAlertCommon**（398-400 行调用，定义 500-517 行）。两个子条件：
- 全部 suppressor 的 `suppressInterruptions(entry)` 轮询（501-509 行），任一为真即否决。suppressor 是外部注入的条件包，本树唯一实现的三组条件见 7.4 节。
- 锁屏隐藏规则（511-514 行）：`if (mKeyguardNotificationVisibilityProvider.shouldHideNotification(entry))` 为真即否决——锁屏上根本不可见的通知（如设置了隐藏敏感内容的 channel）也不许在锁屏上弹横幅。

**第 3 道：canAlertHeadsUpCommon**（402-404 行调用，定义 526-541 行）。两个子条件：
- 分组抑制（530-533 行）：`if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping())`——应用给通知设了 `GROUP_ALERT_CHILDREN` 且它是组成员时，子通知不弹，只弹组摘要。这是 API 层就有的组打扰治理（`Notification.Builder.setGroupAlertBehavior`）。
- 刚发射过全屏意图（535-538 行）：`if (entry.hasJustLaunchedFullScreenIntent())`——全屏 Activity 已经起来了，再叠一张横幅是重复打扰。

**第 4 道：canAlertAwakeCommon**（406-408 行调用，定义 549-561 行）。轮询 suppressor 的 `suppressAwakeInterruptions(entry)`（552-559 行）——"仅亮屏时生效"的抑制条件（本树实现是 VR 模式，7.4 节）。

**第 5 道：包被 snooze**（410-413 行）：

```java
        if (isSnoozedPackage(sbn)) {
```

（563-565 行转 `mHeadsUpManager.isSnoozed(sbn.getPackageName())`。）用户在某条横幅上长按选过"不再提示此应用"后，该包进入 snooze 名单（默认 60000ms，9.6 节），窗口内该包所有通知不弹横幅。注意服务端也有一道 snooze 拒绝点（2 章拒绝点 8 之六），但那是对同 key 重发的拦截；这里是客户端按包名的横幅静默，两层各司其职。

**第 6 道：气泡已接管**（415-419 行）：

```java
        boolean inShade = mStatusBarStateController.getState() == SHADE;
        if (entry.isBubble() && inShade) {
```

已入气泡且在面板态的通知不弹横幅——气泡本身就是它的打断形态，两种打扰不叠加。`entry.isBubble()` 的资格在服务端 BubbleExtractor 抽取（4.1 节第 3 位）、SystemUI 侧 BubblesManager 落实。

**第 7 道：DND 抑制 peek**（421-424 行）：

```java
        if (entry.shouldSuppressPeek()) {
```

`shouldSuppressPeek()` 读的是 entry 上的抑制位，上游是服务端 Ranking 的 `suppressedVisualEffects` 中的 `SUPPRESSED_EFFECT_PEEK` 位（4.2 节装运，5.2 节打包）。勿扰模式的"仅优先"或"完全静音"策略经这条数据通路在客户端执行：服务端裁决、客户端拒弹。

**第 8 道：importance 门槛**（426-429 行）：

```java
        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
```

全文最重要的一行。`entry.getImportance()` 的值来自 RankingMap 装运的服务端三层裁决终值（3.3、5.2 节）；门槛是 `IMPORTANCE_HIGH`，与常量注释 "makes noise and peeks"（3.1 节）严格同构。**应用想弹横幅，channel importance 必须到 HIGH 且用户没把它调低**——这条门槛把第 3 章的权限模型直接变成了横幅准入。

**第 9 道：设备在用**（431-436 行）：

```java
        boolean inUse = mPowerManager.isScreenOn() && !isDreaming();
        if (!inUse) {
            ...
            return false;
        }
```

屏幕亮着且不处于屏保（dream）才算"在用"。灭屏时亮屏链直接否决——灭屏场景由 dozing 链管（AOD pulse，7.3 节），屏保场景则彻底不打扰。

**第 10 道：old-when 抑制**（438-440 行调用，定义 567-610 行）。针对"陈旧 when 时间戳"的防御：通知的 `Notification.when` 比当前时间早超过 `MAX_HUN_WHEN_AGE_MS = 24 * 60 * 60 * 1000`（612 行，24 小时）即视为陈旧，抑制横幅（605-609 行记 `HUN_SUPPRESSED_OLD_WHEN` 并 return true 表示"抑制"）。三个豁免：when ≤ 0（585-593 行，未设时间戳）、带 fullScreenIntent（595-598 行）、是前台服务通知（600-603 行）。整道门槛受 flag `isNoHunForOldWhenEnabled`（568 行）控制。防的是这类事故：应用发了一条 when 写成过去某时刻的通知（如补发昨天的消息提醒），用户眼前突然弹出一张"昨天"的横幅。

**第 11 道：awake HUN 级 suppressor**（442-447 行）：轮询 suppressor 的 `suppressAwakeHeadsUp(entry)`——三组条件里最丰富的一组（锁屏遮挡打码、面板禁用、FSI＋锁屏、无障碍触摸浏览），见 7.4 节。

**通过**（448-449 行）：`if (log) mLogger.logHeadsUp(entry); return true;`。

十一道门槛的权限与数据源分布值得回看：第 1、5、9 道是设备/用户状态，第 2、4、11 道是注入条件包，第 3、6、10 道是通知自身属性，第 7、8 道是服务端裁决字段。**没有一道询问"应用请求了横幅吗"**——横幅从来不是应用的请求权，而是系统基于渠道等级与设备状态的裁量权。

#### 7.3 灭屏链：AOD pulse 的六道门槛

`shouldHeadsUpWhenDozing`（459-491 行）在设备 dozing（AOD 微亮）时接管，六道门槛按序：

1. pulse 开关（460-463 行）：`if (!mAmbientDisplayConfiguration.pulseOnNotificationEnabled(UserHandle.USER_CURRENT))`——设备配置允许"来通知时亮屏脉动"才继续。
2. 省电（465-468 行）：`if (mBatteryController.isAodPowerSave())`——AOD 省电模式下不 pulse。
3. canAlertCommon（470-473 行）：与亮屏链第 2 道同一函数。
4. canAlertHeadsUpCommon（475-478 行）：与亮屏链第 3 道同一函数。
5. ambient 抑制（480-483 行）：`if (entry.shouldSuppressAmbient())`——DND 策略里的 `SUPPRESSED_EFFECT_AMBIENT` 位。
6. importance 门槛（485-488 行）：`if (entry.getImportance() < NotificationManager.IMPORTANCE_DEFAULT)`。

对比亮屏链的两处降格：importance 门槛从 HIGH 降到 DEFAULT（pulse 只是让屏幕闪一下显示图标，打扰远小于横幅上屏）；没有 snooze、气泡、old-when、inUse 诸道（dozing 本身就意味着"不在用"）。灭屏链返回 true 的效果不是横幅，而是 AOD pulse——同判定入口、两种视觉输出，由调用方（HeadsUpCoordinator 与 pulse 机制）分头消费。

#### 7.4 suppressor 体系：唯一实现的三组条件

接口 `NotificationInterruptSuppressor.java`（74 行，接口声明 26 行）定义三个默认返回 false 的钩子加一个署名方法：`suppressInterruptions`（71-73 行，全场景）、`suppressAwakeInterruptions`（58-60 行，仅亮屏）、`suppressAwakeHeadsUp`（45-47 行，仅亮屏横幅）、`getName()`（32-34 行）。注册口在 provider 接口（`NotificationInterruptStateProvider.java:171` `void addSuppressor(NotificationInterruptSuppressor suppressor);`）。

全树唯一实现在 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarNotificationPresenter.java`（369-437 行，匿名类），注册于 185 行 `notificationInterruptStateProvider.addSuppressor(mInterruptSuppressor);`。三组条件：

- `suppressAwakeHeadsUp`（381-423 行）四条：锁屏被遮挡且公共模式需打码时（383-393 行，`mCentralSurfaces.isOccluded()` 且 userPublic 且 needsRedaction——例如锁屏上盖着来电界面，敏感通知不该再弹）；面板被禁用时（395-400 行，`!mCommandQueue.panelsEnabled()`——某些车机/演示模式禁掉整个面板）；带 fullScreenIntent 且锁屏正在显示未被遮挡时（402-413 行，FSI 会在锁屏解锁后自行发射，横幅让路）；无障碍触摸浏览开启时（415-420 行，`mAccessibilityManager.isTouchExplorationEnabled()`——TalkBack 用户靠语音播报获知通知，视觉横幅反而干扰焦点）。
- `suppressAwakeInterruptions`（428-430 行）：`return isDeviceInVrMode();`（317 行）——VR 模式下 2D 横幅无意义。
- `suppressInterruptions`（435-437 行）：`return mCentralSurfaces.areNotificationAlertsDisabled();`——全局警效禁用位（如某些设备状态下的总闸）。

注意 DND 与来电**不在** suppressor 里：DND 走 entry 上的三个 suppress 标志（第 7 道 `shouldSuppressPeek`、灭屏链 `shouldSuppressAmbient`、FSI 的 `shouldSuppressFullScreenIntent`），来电的优先级在 HeadsUpManager 的排序层处理（9.5 节）。

#### 7.5 全屏意图分支：一棵独立决策树

fullScreenIntent 是与横幅平行的另一种视觉打断（直接拉起全屏 Activity），本树只作分叉口记录。入口 `shouldLaunchFullScreenIntentWhenAdded`（219-224 行），核心 `getFullScreenIntentDecision`（244-327 行）按序：无 fullScreenIntent 字段 → NO_FULL_SCREEN_INTENT（246-248 行）；DND 抑制（`entry.shouldSuppressFullScreenIntent()`，255-258 行）；importance < HIGH → 不够格（260-264 行）；分组抑制（266-274 行）；随后三个"发射"条件任一满足即发：屏幕不亮（276-280 行）、屏保中（282-286 行）、锁屏态（288-292 行）；都不满足则问横幅判定（294-300 行 `if (checkHeadsUp(entry, /* log= */ false))` → NO_FSI_EXPECTED_TO_HUN，即"预计会弹横幅，FSI 不抢"）；最后按 flag `fullScreenIntentRequiresKeyguard()` 分两种剩余场景（302-322 行）。要点：**FSI 与 HUN 互斥，灭屏/锁屏归 FSI，亮屏在用归 HUN**；加上服务端剥权（2 章拒绝点 5），普通应用在 A13 实际很难走到 FSI 发射。

#### 7.6 判定核心的四问小结

- **规则**：亮屏链十一道否决、灭屏链六道否决；核心准入是 importance ≥ HIGH（亮屏）/ ≥ DEFAULT（灭屏）；DND、snooze、气泡、分组、陈旧时间戳、设备状态各自独立否决。
- **设置者**：importance 由服务端三层裁决（第 3 章）；suppress 位由 zen 策略（4.2 节）；snooze 由用户在横幅上的操作（9.6 节）；`mUseHeadsUp` 由系统设置；suppressor 由 `StatusBarNotificationPresenter` 注入。
- **执行者**：`HeadsUpCoordinator` 在 EntryAdded/EntryUpdated/RankingApplied 三个时机调用判定（8.1 节）。
- **后果**：判定为真 → 异步绑视图 → showNotification 上屏（8-9 章）；为假 → 通知只进 shade 列表，无任何视觉打断。

### 8. 从判定到上屏：HeadsUpCoordinator 与异步绑定

#### 8.1 判定的三个调用时机

判定核心的消费者全树只有一个：`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coordinator/HeadsUpCoordinator.kt`。三个调用点：

- EntryAdded 时（403 行）：`val shouldHeadsUpEver = mNotificationInterruptStateProvider.shouldHeadsUp(entry)`——新通知入场即问一次，答案存进条目状态（"这条通知**曾经**够格弹横幅吗"）。
- EntryUpdated 时（424 行）：同样的判定重问——更新可能把 importance 或内容改够格，也可能改丢格。
- RankingApplied 时（533-534 行）：`mNotificationInterruptStateProvider.checkHeadsUp(entry, /* log= */ false)`——服务端裁决字段变了（如 zen 切换、importance 重算）而通知本体没变时，靠排序刷新事件补问。

（另有 `BubblesManager.java` 在 293/470/479/502 行调用同一 provider 的 `shouldBubbleUp`——那是气泡判定，与横幅共用裁决器但走不同出口。Dagger 绑定在 `NotificationsModule.java:260-261`。）

#### 8.2 先绑视图，再上屏

判定为真不等于立刻显示。`handlePostedEntry`（340-370 行）对新入场且 `shouldHeadsUpEver` 为真的条目调 `bindForAsyncHeadsUp(posted)`；后者（377-382 行）：

```kotlin
        mHeadsUpViewBinder.bindHeadsUpView(posted.entry, this::onHeadsUpViewBound)
```

带 1000ms 绑定超时（`BIND_TIMEOUT = 1000L`，729 行）。绑定完成回调 `onHeadsUpViewBound`（101-104 行）里才是全树唯一的 `mHeadsUpManager.showNotification(entry)` 调用点。顺序设计的动机：横幅视图是 RemoteViews 异步充气的，先把视图准备好再上屏，避免"横幅弹出瞬间内容还没渲染"的白帧；绑定超时则防止一条充不出视图的坏通知永远占着横幅位。

`HeadsUpViewBinder.java`（`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/interruption/HeadsUpViewBinder.java`，144 行）的 `bindHeadsUpView`（84-106 行）：重要会话类通知且面板非全折叠时设置加高标志（86-90 行 `setUseIncreasedHeadsUpHeight`）；91 行 `params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);` 声明横幅内容视图需求；92-102 行经 `mStage.requestRebind` 触发异步重绑，完成后回调 `callback.onBindFinished(en)`。解绑 `unbindHeadsUpView`（127-143 行）对称：140 行 `params.markContentViewsFreeable(FLAG_CONTENT_VIEW_HEADS_UP);` 释放视图持有，再 requestRebind。中途取消走 `abortBindCallback`（114-120 行，CancellationSignal）。presenter 注入在 `NotificationsControllerImpl.kt` 115 行。

FSI 发射分支在同一 coordinator：判定"该发全屏意图"时（391-394 行）走 `mLaunchFullScreenIntentProvider.launchFullScreenIntent(entry)`，provider 实现在 `LaunchFullScreenIntentProvider.kt`（52-59 行），转发给注册的 `onFullScreenIntentRequested` 监听者。

### 9. HeadsUpManager：横幅的账本与时钟

#### 9.1 基类结构：AlertingNotificationManager

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/policy/HeadsUpManager.java`（570 行，51 行 `extends AlertingNotificationManager`）的基类 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/AlertingNotificationManager.java` 持有横幅账本（45 行）：

```java
    protected final ArrayMap<String, AlertEntry> mAlertEntries;
```

以通知 key 为键的 map——多条横幅可同时在账（比较取顶，9.5 节），不是单槽。入场 `showNotification`（67-72 行）：

```java
    public void showNotification(NotificationEntry entry) {
        addAlertEntry(entry);
        updateNotification(entry.getKey(), true);
        entry.setInterruption();
    }
```

`addAlertEntry`（193-200 行）从对象池取 `AlertEntry`、setEntry、put 进 map、回调 `onAlertEntryAdded`、置 `entry.setIsAlerting(true)`。移除 `removeNotification(key, releaseImmediately)`（83-96 行）：

```java
        if (releaseImmediately || canRemoveImmediately(key)) {
            removeAlertEntry(key);
        } else {
            alertEntry.removeAsSoonAsPossible();
            return false;
        }
```

`canRemoveImmediately`（256-260 行）＝ `wasShownLongEnough() || isRowDismissed()`——没展示够最短时长且不是被用户划掉的，先进"尽快移除"挂起态（393-399 行），到点再真移除。`removeAlertEntry`（214-230 行）在展开动画进行中会跳过（222-224 行），防止动画撕裂。

#### 9.2 时钟常数：五个资源值

`HeadsUpManager` 构造时（103-111 行）从资源读五个时钟数，值在 `frameworks/base/packages/SystemUI/res/values/config.xml`：

| 资源名 | 值 | 行号 | 用途 |
|---|---|---|---|
| heads_up_notification_decay | 5000ms | :111 | 自动收回衰减（mAutoDismissNotificationDecay） |
| heads_up_notification_minimum_time | 2000ms | :121 | 最短展示（mMinimumDisplayTime） |
| touch_acceptance_delay | 700ms | :127 | 触摸接受延迟（mTouchAcceptanceDelay） |
| heads_up_default_snooze_length_ms | 60000ms | :118 | snooze 默认时长 |
| ambient_notification_extension_time | 10000ms | :130 | AOD pulse 延长（HeadsUpManagerPhone 用） |

另有 `auto_heads_up_notification_decay = 3000`（:114，"自动消失"类通知的短衰减）与设置键 `"heads_up_snooze_length_ms"`（HeadsUpManager 53 行，用户可改 snooze 时长，读取 110-111 行）。

#### 9.3 定时公式

入场/更新时 `updateEntry`（AlertingNotificationManager 322-337 行）挂表：

```java
        mEarliestRemovaltime = currentTime + mMinimumDisplayTime;
        if (updatePostTime) {
            mPostTime = Math.max(mPostTime, currentTime);
        }
        removeAutoRemovalCallbacks();
        if (!isSticky()) {
            long finishTime = calculateFinishTime();
            long removeDelay = Math.max(finishTime - currentTime, mMinimumDisplayTime);
            mHandler.postDelayed(mRemoveAlertRunnable, removeDelay);
        }
```

到期动作在 301-303 行注册：`setEntry(entry, () -> removeAlertEntry(entry.getKey()))`。两个覆写决定 finishTime：`calculateFinishTime()` 基类版（417-419 行）＝ `mPostTime + mAutoDismissNotificationDecay`；HeadsUpManager 版（551-553 行）把衰减量换成 `getRecommendedHeadsUpTimeoutMs(mAutoDismissNotificationDecay)`（562-568 行，经 AccessibilityManager 询问无障碍服务是否要求更长超时，可放大）。`calculatePostTime()`（543-546 行）在基类 postTime 上加 `mTouchAcceptanceDelay`：

```java
    protected long calculatePostTime() {
        return super.calculatePostTime() + mTouchAcceptanceDelay;
    }
```

合起来，一条普通横幅的寿命公式：**removeDelay = max(postTime + 700 + 5000（可被无障碍放大）− now, 2000)**。700ms 触摸接受延迟的用途在 HeadsUpManagerPhone 的防误触（10.3 节）：横幅刚弹出 700ms 内的点击不算数——防止用户正要点屏幕上原有内容时被突然弹出的横幅"吃掉"点击。

#### 9.4 sticky 四态：不挂表的横幅

`updateEntry` 里 `if (!isSticky())` 的反面。`HeadsUpEntry.isSticky()`（482-485 行）：

```java
        public boolean isSticky() {
            return (mEntry.isRowPinned() && expanded) || remoteInputActive || hasFullScreenIntent(mEntry);
        }
```

手机侧再叠一条（HeadsUpManagerPhone 488-490 行）：`return super.isSticky() || mMenuShownPinned;`。四种 sticky 态：pinned 且展开、远程输入激活中、带全屏意图、长按菜单展开中。sticky 期间不挂自动收回表——横幅停多久由用户行为决定（收起远程输入、关掉菜单等事件触发重挂表，如 `setRemoteInputActive` 293-304 行在激活时 299 行 `removeAutoRemovalCallbacks()`）。

#### 9.5 多条横幅的优先序

`getTopEntry`/`getTopHeadsUpEntry`（298-317 行）遍历账本按 `compareTo` 取最小者上屏。排序链（490-523 行）从硬到软：pinned 优先 → 带 fullScreenIntent 优先 → 关键来电优先 → 远程输入激活优先 → postTime 晚者优先。"关键来电"的判定 `isCriticalCallNotif`（461-467 行）：CallStyle 且 CALL_TYPE_INCOMING，或 ongoing 且 CATEGORY_CALL。新横幅比旧横幅优先级高时顶替上屏，否则在账本里等——这就是"来电横幅压过消息横幅"的实现位。

#### 9.6 snooze：按包名的横幅静默

`snooze()`（268-276 行）把当前顶横幅的包名写入 `mSnoozedPackages`，到期时间 now + mSnoozeLengthMs（默认 60000ms，可被设置键覆写）；`isSnoozed`（249-261 行）供判定层第 5 道门槛查询。入口是横幅上的长按菜单（Snooze 选项），与 9.4 节的 menu sticky 态衔接：选完 snooze、菜单收起、横幅移除，窗口内该包新通知不再弹（但仍在 shade 列表里）。

#### 9.7 状态广播：pinned 与 listeners

`onAlertEntryAdded`（203-211 行）：

```java
        entry.setHeadsUp(true);
        setEntryPinned(entry, shouldHeadsUpBecomePinned(entry));
        EventLogTags.writeSysuiHeadsUpStatus(entry.getKey(), 1);
```

随后（208-210 行）向所有 `OnHeadsUpChangedListener` 广播 `onHeadsUpStateChanged(entry, true)`；移除对称（216-225 行，`entry.setHeadsUp(false)` 后广播 false）。pinned 语义是"横幅钉住面板"：基类判定 `shouldHeadsUpBecomePinned = hasFullScreenIntent(entry)`（160-168 行），手机侧覆写加两条（10.1 节）。`setEntryPinned`（172-192 行）在状态变化时广播 `onHeadsUpPinned`/`UnPinned`（184-190 行）；`updatePinnedMode`（229-242 行）广播 `onHeadsUpPinnedModeChanged`——第 11 章的窗口与面板联动全部挂在这三个回调上。

### 10. HeadsUpManagerPhone：手机形态的附加层

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/HeadsUpManagerPhone.java`（637 行，61-62 行 `extends HeadsUpManager implements Dumpable, OnHeadsUpChangedListener`）在基类上叠加手机特有的窗口/锁屏/面板交互。

#### 10.1 pinned 判定覆写

417-424 行：

```java
    protected boolean shouldHeadsUpBecomePinned(NotificationEntry entry) {
        boolean pin = mStatusBarState == StatusBarState.SHADE && !mIsExpanded;
        if (mBypassController.getBypassEnabled()) {
            pin |= mStatusBarState == StatusBarState.KEYGUARD;
        }
        return pin || super.shouldHeadsUpBecomePinned(entry);
    }
```

pinned 的三条来源：面板收起态弹横幅（SHADE 且未展开——最常见的"横幅钉住"场景，横幅此时是面板之外唯一的浮层）；锁屏且开启了锁屏快捷绕过（bypass，锁屏上横幅可直接操作不必解锁）；带全屏意图（基类）。pinned 态驱动第 11 章的窗口可触与面板联动。

#### 10.2 设备状态监听

`mStatusBarStateListener`（603-636 行）：`onStateChanged`（607-622 行）处理锁屏→解锁迁移，bypass 场景下移除非 sticky 的气泡横幅；`onDozingChanged`（627-635 行）：

```java
        if (!isDozing) {
            // Let's make sure all huns we got while dozing time out within the normal timeout
            for (AlertEntry entry : mAlertEntries.values()) {
                entry.updateEntry(true /* updatePostTime */);
            }
        }
```

出 doze 时给账本里全部横幅重置时钟——AOD 期间攒下的横幅在亮屏后按正常超时重新计时，不会亮屏瞬间集体消失。面板展开联动 `onShadeExpansionFullyChanged`（255-262 行，注册于 150 行）：面板全展开时置 `mIsExpanded=true`、清 `mHeadsUpGoingAway`——横幅此时已融入面板列表，不再是浮层。

#### 10.3 防误触与滑出旁路

`shouldSwallowClick`（221-224 行）：

```java
    public boolean shouldSwallowClick(@NonNull View row) {
        NotificationEntry entry = mHeadsUpEntryMap.get(row).getEntry();
        return entry != null && mClock.currentTimeMillis() < entry.mPostTime;
    }
```

mPostTime 已含 700ms 触摸接受延迟（9.3 节），弹出后 700ms 内落在横幅行上的点击被吞掉。滑出旁路 `canRemoveImmediately` 覆写（457-468 行）：

```java
        if (mSwipedOutKeys.contains(key)) {
            mSwipedOutKeys.remove(key);
            return true;
        }
```

被用户侧滑掉的 key 记入 `mSwipedOutKeys`（写入点见 13.3 节），移除时绕过"最短展示 2000ms"的挂起等待，立即收——用户明确表达过的收回意图不再被时钟拖延。

#### 10.4 移除状态机

`HeadsUpEntryPhone.setEntry` 里的 removeHeadsUpRunnable（494-511 行）是收回的调度中枢：列表正在重排且不允许动时，进 `mEntriesToRemoveWhenReorderingAllowed` 挂起（496-502 行）；用户正拖着横幅（`mTrackingHeadsUp`）时，进 `mEntriesToRemoveAfterExpand` 挂起（503-504 行）；否则直接 `removeAlertEntry`（506 行）。配套：`onExpandingFinished`（228-241 行）在面板动画结束后处理 `mReleaseOnExpandFinish`（releaseAllImmediately）；`snooze()` 覆写（346-349 行）置 `mReleaseOnExpandFinish=true`；`setTrackingHeadsUp`（249-251 行）与 `setHeadsUpGoingAway`（270-277 行）维护拖拽/退场动画状态；`removeNotification(key, releaseImmediately, animate)` 三参重载（359-369 行）在不动画时临时禁用 going-away 动画。

#### 10.5 pulse 延长与可触区域

AOD pulse 延长：`mExtensionTime` 读 `ambient_notification_extension_time`（127 行，值 10000ms），`extendPulse`（569-581 行）置 extended 标志后 `calculateFinishTime()` 在基类结果上加 mExtensionTime——灭屏 pulse 的横幅比亮屏多活 10 秒。可触区域 `getTouchableRegion()`（185-210 行）：无 pinned 横幅或无顶条目时返回 null（窗口不可触，事件穿透）；否则按顶横幅行的屏幕位置算矩形（207 行 `mTouchableRegion.set(minX, 0, maxX, mHeadsUpInset + height);`，`mHeadsUpInset` = 状态栏高 + padding，161-165 行）。这个矩形交给窗口层决定横幅区域外的触摸是否穿透给底下的应用（11.3 节）。另有对象池（87-107 行 `Pools.Pool<HeadsUpEntryPhone>`）复用条目对象。

### 11. 视图与窗口：横幅怎么被看见、怎么被摸到

#### 11.1 面板控制器：NotificationPanelViewController 的横幅面

`frameworks/base/packages/SystemUI/src/com/android/systemui/shade/NotificationPanelViewController.java`（6425 行，注意在 `shade/` 包）持有横幅联动的最大一组状态。关键字段：`mHeadsUpManager`（380 行，`setHeadsUpManager` 4065-4071 行注入）、`mTrackedHeadsUpNotification`（526 行，当前跟踪的横幅条目，`setTrackedHeadsUp` 4073-4080 行）、`mHeadsUpPinnedMode`（563 行）、`mHeadsUpAnimatingAway`（500 行）、`mHeadsUpInset`（562 行）、`mListenForHeadsUp`（495 行）。关键方法：面板是否因横幅而可见 `isPanelVisibleBecauseOfHeadsUp`（4163-4166 行）：

```java
    protected boolean isPanelVisibleBecauseOfHeadsUp() {
        return (mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpAnimatingAway)
                && mBarState == StatusBarState.SHADE;
    }
```

`shouldPanelBeVisible`（4060-4063 行）里 `boolean headsUpVisible = mHeadsUpAnimatingAway || mHeadsUpPinnedMode;`——pinned 横幅把整个 shade 窗口拉成"可见"，横幅退场动画期间维持可见防止闪断。内部类 `ShadeHeadsUpChangedListener`（5488-5526 行）挂 9.7 节的三个广播：`onHeadsUpPinnedModeChanged`（5490-5503 行）进入 pinned 即执行 `mHeadsUpExistenceChangedRunnable`（713-716 行）刷新面板可见性，退出 pinned 先 `setHeadsUpAnimatingAway(true)`（4048 行）播退场动画再刷新；`onHeadsUpPinned`（5506-5512 行）触发 `generateHeadsUpAnimation(..., true)` 入场动画；`onHeadsUpUnPinned`（5514-5525 行）对称。触摸事件里横幅行的处理入口在 6155 行（`mHeadsUpTouchHelper.onTouchEvent`），可触区域计算联动在 1472 行。横幅到面板的展开联动（用户下拉横幅进面板）：`transitionFromHeadsUp`（3930 行）、`updateMaxHeadsUpTranslation`（3875 行）、`mExpandingFromHeadsUp`（497 行）。

#### 11.2 窗口层：FLAG_NOT_TOUCH_MODAL 的加与撤

`frameworks/base/packages/SystemUI/src/com/android/systemui/shade/NotificationShadeWindowControllerImpl.java` 把"横幅在显示"翻译成窗口参数。`setHeadsUpShowing`（689-692 行）：

```java
    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.mHeadsUpShowing = showing;
        apply(mCurrentState);
    }
```

`applyModalFlag`（543-549 行）：

```java
        if (state.mHeadsUpShowing) {
            mLpChanged.flags |= LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            mLpChanged.flags &= ~LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
```

`FLAG_NOT_TOUCH_MODAL` 的含义：shade 窗口可见期间，落在窗口内容区之外的触摸**穿透给底下的应用**。没有它，横幅一弹整个屏幕的触摸都被 shade 窗口吃掉，用户无法继续操作原应用；有了它，横幅只接管自己矩形内的触摸（配合 11.3 节的 touchable region）。`isExpanded` 判定（428-435 行）把 `state.mHeadsUpShowing` 计入——横幅显示时窗口按"已展开"规格配置高度与焦点。

调用者是 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarHeadsUpChangeListener.java`（36 行 implements OnHeadsUpChangedListener），`onHeadsUpPinnedModeChanged`（69-110 行）：进入 pinned（71 行）`setHeadsUpShowing(true)` ＋ `setForceStatusBarVisible(true)`，并有一帧的 `setForceWindowCollapsed`(true/false) 防误触序列；退出 pinned 时先播 going-away 动画，动画结束（103-108 行）才 `setHeadsUpShowing(false)`（96/107 行）——窗口可触状态与横幅视觉严格同步收回，不提前漏事件。

#### 11.3 触摸区域管理

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarTouchableRegionManager.java` 的 `calculateTouchableRegion()`（158-171 行）：有横幅时用 `mHeadsUpManager.getTouchableRegion()`（10.5 节的矩形），无横幅时退化为状态栏条带＋notch 区域；监听 `onHeadsUpPinnedModeChanged` 与 `onHeadsUpGoingAwayStateChanged`（104-118 行）触发 `updateTouchableRegion()`。三者合力构成横幅的窗口行为闭环：**pinned 决定窗口可不可见，touchable region 决定窗口哪里可摸，NOT_TOUCH_MODAL 决定窗外事件归谁**。

### 12. 点击与 Action：横幅上的两种 PendingIntent

#### 12.1 点击监听器的注册与分发

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/NotificationClicker.java`（41 行 `public final class NotificationClicker implements View.OnClickListener`）在行视图绑定时注册（131-141 行）：

```java
    public void register(@NonNull ExpandableNotificationRow row, @NonNull StatusBarNotification sbn) {
        ...
        Notification notification = sbn.getNotification();
        if (notification.contentIntent != null || notification.fullScreenIntent != null
                || row.getEntry().isBubble()) {
            row.setOnClickListener(this);
            row.setOnDragSuccessListener(mOnDragSuccessListener);
        }
    }
```

注意条件：**contentIntent 与 fullScreenIntent 都为空且非气泡时，行上根本不设点击监听**——"只发 action 不发 contentIntent，点主体没反应"在 A13 手机侧是字面事实（连监听器都没有）。装配点：`NotificationsControllerImpl.kt:107` 把 clicker 交给行绑定器，`NotificationRowBinderImpl.java:206` 逐行调用 register。

`onClick`（74-118 行）的顺序：先唤醒（81-83 行 `centralSurfaces.wakeUpIfDozing(...)`）；菜单可见/子项展开/guts 展开三种状态下点击只是收起这些浮层，直接 return（90-107 行）；气泡栈收起（113-115 行）；最后 117 行 `mNotificationActivityStarter.onNotificationClicked(entry, row);`（接口 `NotificationActivityStarter.java` 33 行）。

#### 12.2 主体点击：contentIntent 优先，锁屏先解锁

`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarNotificationActivityStarter.java` 204 行的 `onNotificationClicked`：远程输入激活时先收起输入框并吞掉本次点击（207-212 行）；intent 选择（215-217 行）：

```java
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
```

contentIntent 优先，缺失时全屏意图兜底当点击目标用。随后经 `handleNotificationClickAfterKeyguardDismissed`（242 行调用，定义 266 行）：锁屏上点击时 257-259 行 `mActivityStarter.dismissKeyguardThenExecute(postKeyguardAction, null, willLaunchResolverActivity);`——先走解锁流程（需要认证的通知会先要求生物识别/PIN），解锁成功才真正 `intent.send()`。拖拽成功路径 `onDragSuccess`（387 行）：`shouldAutoCancel` 或通知因远程输入历史被保留时（393-396 行）`mOnUserInteractionCallback.registerFutureDismissal(entry, REASON_CLICK)` 预约移除。

对横幅的具体含义：锁屏上弹的横幅（pinned bypass 场景，10.1 节）点击后不是立即跳转，而是"解锁→跳转"两段式；`FLAG_AUTO_CANCEL` 的通知点击后由系统以 REASON_CLICK 走 13.2 节的移除链收回横幅。

#### 12.3 Action 点击：RemoteViews.startPendingIntent 与生命延长

Action 按钮的点击不走 NotificationClicker，走 `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/NotificationRemoteInputManager.java` 的 `InteractionHandler.onInteraction(view, pendingIntent, response)`（119-160 行）：先试远程输入（134-137 行 `if (handleRemoteInput(view, pendingIntent)) { return true; }`——带 RemoteInput 的 action 点击是展开输入框，不是发 intent）；普通 action 则 151 行 `Notification.Action action = getActionFromView(view, entry, pendingIntent);` 反查 action 对象（164-191 行，按视图 tag `notification_action_index_tag` 取索引，186-189 行校验 actionIntent 一致性，不匹配则 return null 并警告），152-158 行经回调执行：

```java
            boolean started = RemoteViews.startPendingIntent(view, pendingIntent, options);
```

（156 行；成功则 157 行释放因远程输入历史被保留的通知。）`RemoteViews.startPendingIntent` 是框架提供的带宿主上下文的发送口，内部处理锁屏绕过与动画选项。

与横幅联动的一环：`addActionPressListener`（376-378 行）注册的监听在 action 发送成功后被回调（618-628 行 `releaseNotificationIfKeptForRemoteInputHistory` 内 625-627 行遍历 `mActionPressListeners`），`HeadsUpCoordinator.kt:98` 在此注册——收到按压后按 `mHeadsUpManager.getEarliestRemovalTime(entry.key)` 延时结束生命延长（620-625 行）。翻译成人话：**用户在横幅上点了 Action，横幅不会在点击瞬间被自动收回打脸**，生命延长机制（13.4 节）保证操作完成前横幅驻留。

### 13. 收回与死亡：横幅的四种退场

#### 13.1 超时自动收回

9.3 节挂的 `mRemoveAlertRunnable` 到期执行 `removeAlertEntry(entry.getKey())`（AlertingNotificationManager 301-303 行注册的回调）；手机版的 runnable 带调度（HeadsUpManagerPhone 494-511 行）：列表重排中或用户拖拽中先挂起，条件解除再收。收回入口 `removeAlertEntry`（214-230 行）→ `onAlertEntryRemoved`（HeadsUpManager 216-225 行）：

```java
        entry.setHeadsUp(false);
        ...
        listener.onHeadsUpStateChanged(entry, false);
```

广播触发 11.1 节的 `ShadeHeadsUpChangedListener`（退场动画＋面板刷新）与 11.2 节的 `StatusBarHeadsUpChangeListener`（窗口可触状态收回）——视图、窗口、状态三线同步退场。

#### 13.2 通知本体被移除：全链传导

应用 cancel、用户清除、auto-cancel 点击等导致通知本体消失时，移除事件沿入账原路反向传导：

`NotificationListener.onNotificationRemoved`（165-175 行）→ `GroupCoalescer`（152-166 行）→ `NotifCollection.mNotifHandler`（904-912 行）→ `onNotificationRemoved`（440-459 行，455 行 `entry.mCancellationReason = reason;`、456 行 `tryRemoveNotification(entry);`）→ `tryRemoveNotification`（524-554 行：被生命延长则 540 行 `updateLifetimeExtension` 暂留；否则 545-548 行移出账本、发 `EntryRemovedEvent` 与 `CleanUpEntryEvent`）→ 事件派发（628-644 行）→ `HeadsUpCoordinator.onEntryRemoved`（465-477 行）：

```kotlin
        if (mHeadsUpManager.isAlerting(entryKey)) {
            val removeImmediatelyForRemoteInput = (mRemoteInputManager.isSpinning(entryKey) &&
                    !NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY)
            mHeadsUpManager.removeNotification(entry.key, removeImmediatelyForRemoteInput)
        }
```

正在弹的横幅随通知本体一起收；远程输入正在转圈（发送回复中）时立即移除避免僵尸输入框。之后进 `removeNotification`（AlertingNotificationManager 83-96 行）→ 不够最短展示时长则挂"尽快移除"（9.1 节）→ `removeAlertEntry` → 广播 → `HeadsUpCoordinator.mOnHeadsUpChangedListener`（679-687 行）：682-683 行 `mNotifPromoter.invalidateList(...)` ＋ `mHeadsUpViewBinder.unbindHeadsUpView(entry)`（视图解绑），684 行结束生命延长。

移除原因的枚举消费：`REASON_CLICK` / `REASON_CANCEL` 被视为"可立即移除"（NotifCollection 811-812 行）；用户点横幅主体触发的 auto-cancel 就以 REASON_CLICK 回流本链。统计侧 `OnUserInteractionCallbackImpl.java:75-78`：

```java
        if (mHeadsUpManager.isAlerting(entry.getKey())) {
            dismissalSurface = NotificationStats.DISMISSAL_PEEK;
        }
```

——从横幅上划掉的通知记 DISMISSAL_PEEK，这是"用户讨厌这条横幅"信号的数据源（喂给 NAS 与统计）。

#### 13.3 用户侧滑：绕过最短展示

侧滑横幅行走 `NotificationStackScrollLayoutController.java:524-535` 的 `handleChildViewDismissed`：

```java
        if (row.isHeadsUp()) {
            mHeadsUpManager.addSwipedOutNotification(row.getEntry().getSbn().getKey());
        }
        row.performDismiss(...);
```

（531-535 行。）被滑掉的 key 进 `mSwipedOutKeys`，配合 HeadsUpManagerPhone 的 `canRemoveImmediately` 覆写（10.3 节）实现"用户划掉的横幅立即消失，不等 2000ms 最短展示"。主动清除（点 X 或清除全部）走 `NotifCollection.dismissNotification`（341-344 行）与 `registerFutureDismissal`（997 行）。

#### 13.4 生命延长：不让横幅在操作中途死掉

`HeadsUpCoordinator.kt` 的 `mLifetimeExtender.maybeExtendLifetime`（627-655 行）在 EntryRemoved 事件到达时介入：`canRemoveImmediately` 为真（展示够久或被划掉）则不延长（635 行）；sticky 态则延时执行 `removeNotification(key, true)`（639-642 行）；否则立即 `removeNotification(key, false)`（644-647 行）。延长器由远程输入（12.3 节）与 Action 按压等场景注册，覆盖"通知本体已 cancel 但用户正在横幅上打字"的窗口期。

### 14. 媒体对照节：A13 手机侧媒体通知不在横幅链里

A13 把媒体通知从通知列表里整体拆迁进了独立的媒体播放器（QS Media Player），链路核心是 `frameworks/base/packages/SystemUI/src/com/android/systemui/media/controls/pipeline/MediaDataManager.kt`（1475 行）。识别口径（133-135 行）：

```kotlin
    fun isMediaNotification(sbn: StatusBarNotification) = sbn.notification.isMediaNotification()
```

其中 `Notification.isMediaNotification()`（`frameworks/base/core/java/android/app/Notification.java` 7243-7252 行）：

```java
    public boolean isMediaNotification() {
        Class<? extends Style> style = getNotificationStyle();
        boolean isMediaStyle = (MediaStyle.class.equals(style)
                || DecoratedMediaCustomViewStyle.class.equals(style));

        boolean hasMediaSession = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token.class) != null;

        return isMediaStyle && hasMediaSession;
    }
```

**MediaStyle 与 MediaSession token 必须同时具备**——只挂 MediaStyle 不塞 session token 的通知不算媒体通知。数据流：`NotificationMediaManager.java`（214-232 行 `setupNotifPipeline`）把一个 NotifCollectionListener 挂进管线，EntryAdded/Updated 转 `mMediaDataManager.onNotificationAdded`（219-228 行），Removed 转 303-304 行。`MediaDataManager.onNotificationAdded`（361-381 行）：媒体特性开启且是媒体通知才建占位并 `loadMediaData`（362 行起，后台加载 698 行，完成回调 1219 行），否则 379 行按移除处理。媒体通知同时被 `MediaCoordinator.java` 的 pre-group filter 从 shade 列表滤除（56-90 行：61 行判特性与媒体身份，88 行 return true 滤除；155-156 行挂进管线；120-124 行注释明说 "they aren't displayed in the shade"）。

与横幅的关系需要一个精确结论：本树的横幅判定层（第 7 章全部代码）与 `HeadsUpCoordinator` 中**没有任何显式的媒体排除分支**。媒体通知理论上仍会进 `shouldHeadsUp` 评估；它实际不弹横幅，靠的是第 8 道通用门槛——媒体通知的 channel importance 惯例是 LOW（低打扰持续型通知），到不了 HIGH。旁证两处：`HighPriorityProvider.java:109-114` 把 `isMediaNotification()` 列为高优先特征（用于 AOD 与分节，不用于 HUN）；`KeyguardCoordinator.kt:190-195` 对媒体通知豁免 unseen 检查。这个"不设专门闸门、靠通用门槛兜住"的结构正是第三部车机实现的反面——车机侧媒体被显式排除（雅迪定制版甚至把媒体分流提前到监听器入口），对照见 yadea 篇链③。

#### 第二部四问小结

- **规则**：横幅 = 判定十一道门槛全过 ＋ 视图绑定成功；寿命 = max(postTime+700+5000−now, 2000)，sticky 四态豁免；收回 = 超时/本体移除/侧滑/点击 auto-cancel 四路。
- **设置者**：判定原料全部来自服务端 RankingMap 与设备状态；时钟常数来自 SystemUI 资源（OEM 可覆写）；snooze 与静音由用户操作写入。
- **执行者**：HeadsUpCoordinator（判定与绑定调度）、HeadsUpManagerPhone（账本与时钟）、NotificationPanelViewController（面板与动画）、NotificationShadeWindowControllerImpl 与 StatusBarTouchableRegionManager（窗口与触摸）。
- **后果**：判定为假的通知静默入列表；判定为真的通知获得最多约 5.7 秒（5000+700）的强制曝光，点击直达应用，Action 免解锁可用（远程输入场景），用户划掉立即消失并被记入 DISMISSAL_PEEK 统计。

---

## 第三部 车机形态：CarNotificationLibrary 的另一套横幅

AAOS 在手机 SystemUI 之外另有一套车机通知实现，主体是 `packages/apps/Car/Notification`（CarNotificationLibrary，下称 Car 库），宿主是 `packages/apps/Car/SystemUI`。它不是对手机侧 HUN 的参数化定制，而是从监听到展示的完整平行实现：判定规则、窗口类型、声音职责、超时值、与面板的关系全部不同。雅迪车机 SystemUI 正是在这套库的同名类上魔改出来的（对照审计见 yadea 篇），所以本部的每一处机制都是读懂那套定制的基线。

### 15. CarNotificationListener：同一基类，另一个世界

#### 15.1 结构

`packages/apps/Car/Notification/src/com/android/car/notification/CarNotificationListener.java`（317 行）声明（52-53 行）：

```java
public class CarNotificationListener extends NotificationListenerService implements
        CarHeadsUpNotificationManager.OnHeadsUpNotificationStateChange {
```

与手机侧 `NotificationListener` 同一个框架基类（都经 NMS 的 ManagedServices 授权与分发，第 5 章的服务端对它一视同仁），但下游完全不同：没有 NotifCollection/管线/coordinator，事件经 Handler 消息分发（三种事件常量 57-59 行：`NOTIFY_NOTIFICATION_POSTED`/`REMOVED`/`RANKING_UPDATED`；`setHandler` 247-249 行；`sendNotificationEventToHandler` 302-310 行）。库里注册为系统服务的入口 `registerAsSystemService(Context, CarUxRestrictionManagerWrapper, CarHeadsUpNotificationManager)`（86-101 行），95 行把自己注册进 HUN 管理器的状态回调——这个回调是"横幅结束后通知去哪"的关键（15.4 节）。

非横幅的活跃通知表在 75 行 `private Map<String, AlertEntry> mActiveNotifications = new HashMap<>();`，注释（69-73 行）明确：**正在弹横幅的通知不进这张表**——横幅与列表是互斥的两个世界，一条通知同一时刻只属于其一。

#### 15.2 收帖与分流

`onNotificationPosted`（127-149 行）：判空 → `isNotificationForCurrentUser(sbn)` 过滤（138 行，多用户车机只处理当前前台用户的通知）→ 包装 `AlertEntry`（146 行，Car 库对 SBN 的轻量包装，也是 `HeadsUpEntry` 的父类）→ 同步排序（147 行）→ `notifyNotificationPosted(alertEntry)`（148 行）。

分流现场 `notifyNotificationPosted`（251-267 行）：

```java
    private void notifyNotificationPosted(AlertEntry alertEntry) {
        if (isNotificationHigherThanLowImportance(alertEntry)) {
            mNotificationDataManager.addNewMessageNotification(alertEntry);
        } else {
            mNotificationDataManager.untrackUnseenNotification(alertEntry);
        }

        boolean isShowingHeadsUp = mHeadsUpManager.maybeShowHeadsUp(alertEntry, getCurrentRanking(),
                mActiveNotifications);
        ...
        if (!isShowingHeadsUp) {
            updateOverrideGroupKey(alertEntry);
            postNewNotification(alertEntry);
        }
    }
```

三步：importance > LOW 的通知记入未读管理（`isNotificationHigherThanLowImportance`，312-316 行：`return ranking.getImportance() > NotificationManager.IMPORTANCE_LOW;`——车机侧第一处 importance 消费点，管未读角标不管横幅）；然后**先试横幅**（`maybeShowHeadsUp`，第 17 章）；没弹成横幅才落入面板列表（`postNewNotification`）。横幅优先、面板兜底——与手机侧"横幅是列表之外的事件"结构相反，车机侧横幅是通知的第一去向。

移除（`onNotificationRemoved`，151-173 行）对称处理：从 `mActiveNotifications` 取出移除（162-165 行）；注释（167-168 行）点明"HUN notifications are not tracked in mActiveNotifications but still need to be removed"——正在弹横幅的通知被移除时要走 `mHeadsUpManager.maybeRemoveHeadsUp(alertEntry)`（`removeNotification` 内 298 行）。

#### 15.3 横幅结束后的去向

库实现的 `OnHeadsUpNotificationStateChange.onStateChange`（277-284 行）：

```java
    public void onStateChange(AlertEntry alertEntry, boolean isHeadsUp) {
        // No more a HUN
        if (!isHeadsUp) {
            updateOverrideGroupKey(alertEntry);
            postNewNotification(alertEntry);
        }
    }
```

横幅超时或被驳回后（非应用撤回），同一条通知**补入面板列表**。手机侧没有这个动作（横幅收回就收回了，通知本来就在 shade 列表里）；车机侧因为"横幅与列表互斥"（15.1 节），必须显式补录，否则用户拉下面板会找不到刚才闪过的通知。

版本缝隙备注：Car SystemUI 的 `NotificationPanelViewController.java:380` 调用了 `mCarNotificationListener.setNotificationsShown(...)`，而本仓 317 行版本的 CarNotificationListener 没有这个方法——树内两个目录来自略微不同的版本快照，引用时以各自文件实况为准。

### 16. PreprocessingManager：面板侧的四段流水线

`packages/apps/Car/Notification/src/com/android/car/notification/PreprocessingManager.java`（819 行）的类 Javadoc（50-55 行）先划清边界：

```java
/**
 * Manager that filters, groups and ranks the notifications in the notification center.
 *
 * <p> Note that heads-up notifications have a different filtering mechanism and is managed by
 * {@link CarHeadsUpNotificationManager}.
 */
```

它只管通知中心列表；横幅有自己的过滤机制（第 17 章）。这个分工与手机侧"coordinator 与 ShadeListBuilder 各管一段"同构，但车机把两段做成了两个几乎不共享代码的类。

#### 16.1 四段流水线

`process()`（160-168 行）：

```java
    public List<NotificationGroup> process(boolean showLessImportantNotifications,
            Map<String, AlertEntry> notifications, RankingMap rankingMap) {
        return new ArrayList<>(
                rank(group(optimizeForDriving(
                                filter(showLessImportantNotifications,
                                        new ArrayList<>(notifications.values()),
                                        rankingMap))),
                        rankingMap));
    }
```

filter → optimizeForDriving → group → rank，输出 `List<NotificationGroup>`（Car 库自有类）。单例（124-134 行 `getInstance`/`refreshInstance`）。

#### 16.2 filter：车机的丢弃口径

`shouldFilter`（221-224 行）：

```java
    boolean shouldFilter(AlertEntry alertEntry, RankingMap rankingMap) {
        return isLessImportantForegroundNotification(alertEntry, rankingMap)
                || isMediaOrNavigationNotification(alertEntry);
    }
```

两类被滤：低重要度的前台服务通知（289-290 行，判定 `importance < NotificationManager.IMPORTANCE_DEFAULT && NotificationUtils.isSystemPrivilegedOrPlatformKey(mContext, alertEntry)`——只有系统特权/平台签名应用的低重要 FGS 通知被滤，第三方应用的 FGS 通知保留）；媒体或导航通知（面板不收，各有专属 UI）。另有硬规则（239-244 行）：

```java
        notifications.removeIf(alertEntry -> Notification.CATEGORY_CALL.equals(alertEntry.getNotification().category));
```

来电永不进面板——注释写明来电以持久横幅呈现（17.5 节），进面板反而会造成"横幅还在、列表也有"的双份打扰。

#### 16.3 optimizeForDriving：行车文本裁剪

`optimizeForDriving(AlertEntry)`（327-346 行）：`CATEGORY_MESSAGE` 豁免（328-330 行，消息内容不裁）；其余通知把 EXTRA_TITLE/EXTRA_TEXT/EXTRA_TITLE_BIG/EXTRA_SUMMARY_TEXT 逐个 `extras.putCharSequence(key, trimText(value));`（340 行）。`trimText`（353-359 行）按 `mMaxStringLength` 截断加省略后缀，上限来自驾驶分心法规（749-750 行 `manager.getCurrentCarUxRestrictions().getMaxRestrictedStringLength()`）——**车机上通知文本长度是法规参数**，随 UX 限制状态动态变化。手机侧没有对应物。

#### 16.4 group：六遍分组

`group(List<AlertEntry>)`（385-569 行）六遍扫描：一遍按 groupKey 聚合（389-411 行；来电特殊处理，396 行 `groupKey = UUID.randomUUID().toString();` 配注释 "DO NOT group CATEGORY_CALL"——每条来电自成一组，防止多个来电被合并成一条）；二遍删无子项的自动摘要（420-429 行）；三遍按已读/未读拆组（442-471 行，车机面板的未读分区）；四遍把不足 `mMinimumGroupingThreshold` 的组还原为单体（477-518 行，两三条通知不值得折叠）；五遍删空组（533-534 行）；六遍组时间戳取子项最大值（542-563 行，组按最新子项排位）。

增量路径 `updateNotifications`（177-201 行）：REMOVED 事件触发全量 `process`，POSTED 事件走增量 `additionalGroupAndRank`（196 行，实现 579-672 行）——新通知插进既有组结构，`insertRankedNotification`（690-712 行）按 Ranking 位次插到"下一个更高排名之前"（704 行 `if (newRanking.getRank() < ranking.getRank())`）。排序 `rank()`（723-737 行）用 `NotificationComparator(rankingMap)`（Car 库自有同名类，非服务端那个）＋组内 `InGroupComparator`（761-786 行，按 sortKey 或全局 rank）。

通话状态联动（95-107 行）：监听 `TelephonyManager.ACTION_PHONE_STATE_CHANGED` 广播回调 `CallStateListener`（接口 64-69 行），通话状态变化时列表整表刷新（消费端见 19 章 ViewAdapter）。

### 17. CarHeadsUpNotificationManager：五道否决与三条放行

`packages/apps/Car/Notification/src/com/android/car/notification/CarHeadsUpNotificationManager.java`（673 行）是车机横幅的判定与调度中枢。

#### 17.1 shouldShowHeadsUp：判定全文

方法 Javadoc（547-562 行）先列规则，实现（568-645 行）分"五道否决＋三条放行"。否决段（568-616 行）：

```java
        if (mKeyguardManager.isKeyguardLocked()) { ... return false; }          // 576 行：锁屏
        Notification notification = alertEntry.getNotification();
        // Navigation notification configured by OEM
        if (!mEnableNavigationHeadsup && Notification.CATEGORY_NAVIGATION.equals(
                notification.category)) { ... return false; }                    // 585 行：OEM 关导航横幅
        // Group alert behavior
        if (notification.suppressAlertingDueToGrouping()) { ... return false; }  // 593 行：分组抑制
        // Messaging notification muted by user.
        if (mNotificationDataManager.isMessageNotificationMuted(alertEntry)) {   // 600 行：用户静音
            ... return false; }
        // Do not show if importance < HIGH
        NotificationListenerService.Ranking ranking = getRanking();
        if (rankingMap.getRanking(alertEntry.getKey(), ranking)) {
            if (ranking.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                ... return false; } }                                            // 610 行：importance 门槛
```

放行段（618-645 行）：

```java
        if (NotificationUtils.isSystemPrivilegedOrPlatformKey(mContext, alertEntry)) {
            ... return true; }                                                   // 618 行：平台签名/特权应用
        // Allow car messaging type.
        if (isCarCompatibleMessagingNotification(alertEntry.getStatusBarNotification())) {
            ... return true; }                                                   // 627 行：Car Assist 消息
        ...
        // Allow for Call, and nav TBT categories.
        return Notification.CATEGORY_CALL.equals(notification.category)
                || Notification.CATEGORY_NAVIGATION.equals(notification.category); // 644-645 行
```

与手机侧十一道门槛的对照要点：

- **importance ≥ HIGH 门槛两边同款**（610 行 vs 手机侧第 8 道）——车机没有降低准入，雅迪定制版注释掉的正是这一道（yadea 篇 1.4 节）。
- 锁屏否决（576 行）对应手机侧的 suppressor/状态门槛，但车机直接一刀切：锁屏期间不弹任何横幅。
- 分组抑制（593 行）与手机侧第 3 道同源同 API（`suppressAlertingDueToGrouping`）。
- 用户静音（600 行）是车机特有的按消息会话静音机制（`NotificationDataManager` 维护）。
- 导航横幅有 OEM 开关 `mEnableNavigationHeadsup`（585 行）——TBT 导航提示要不要打扰驾驶员是产品决策。
- 放行段的三条全是车机语境：平台签名/特权应用（系统级通知必弹）、Car Assist 兼容消息（`com.android.car.assist.client.CarAssistUtils` 认证的消息应用）、CALL/NAVIGATION 两个 category（来电与转向提示是驾驶安全信息）。
- 手机侧独有的 snooze、气泡、old-when、DND peek 位、inUse 诸道在车机判定里不存在；zen 的抑制走服务端 Ranking 传导，车机判定层未显式消费。
- 本类判定不含 `config_earlyStartupServices` 之类白名单配置（全 Car 树 grep 零命中）——早期资料中"开机白名单弹横幅"的说法在本树无对应物。

#### 17.2 入口与去重：三标志

`maybeShowHeadsUp`（146-184 行）：判定不通过时，若是同 key 更新且横幅仍有定时回调在跑，先 `dismissHun(alertEntry);`（161-165 行）——更新把通知改得不够格了，正在弹的也要收。判定通过后按三个标志决定弹不弹（168-176 行）：

```java
        boolean containsKeyFlag = !activeNotifications.containsKey(alertEntry.getKey());
        boolean canUpdateFlag = canUpdate(alertEntry);
        boolean alertAgainFlag = alertAgain(alertEntry.getNotification());
        ...
        if (containsKeyFlag || canUpdateFlag || alertAgainFlag) {
            showHeadsUp(mPreprocessingManager.optimizeForDriving(alertEntry), rankingMap);
            return true; }
```

新 key（不在面板列表里）可弹；同 key 横幅还在最短驻留期内可更新（`canUpdate`，267-272 行：已存在同 key 横幅且 `System.currentTimeMillis() - getPostTime() < mDuration`）；未设 `FLAG_ONLY_ALERT_ONCE` 的通知可再次响铃（`alertAgain`，245-247 行：`(newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0`）。同 key 复用条目、alertAgain 时刷新 postTime（`addNewHeadsUpEntry`，278-301 行，298 行 `updatePostTime()`）。没有排队结构——多条横幅并存于 89 行 `Map<String, HeadsUpEntry> mActiveHeadsUpNotifications`，视觉 Z 序由容器的重要度链表决定（18.1 节）。注意 `showHeadsUp` 的参数先过 `optimizeForDriving`——**横幅文本同样受行车裁剪约束**（16.3 节）。

#### 17.3 时钟：8000/2000 与两个豁免

超时常量（109-113 行）读自 `packages/apps/Car/Notification/res/values/integers.xml`（22、25 行）：

```xml
    <integer name="heads_up_notification_minimum_time">2000</integer>
    <integer name="headsup_notification_duration_ms">8000</integer>
```

横幅驻留 8000ms（手机侧 5000+700）、最短展示 2000ms（与手机同值）。自动消失挂表（470-477 行）：

```java
    private void setAutoDismissViews(HeadsUpEntry currentNotification, AlertEntry alertEntry) {
        // Should not auto dismiss if HUN has a full screen Intent.
        if (hasFullScreenIntent(alertEntry)) { return; }
        currentNotification.getHandler().removeCallbacksAndMessages(null);
        currentNotification.getHandler().postDelayed(() -> dismissHun(alertEntry), mDuration);
    }
```

两个车机特色：full-screen intent 横幅不自动消失（来电横幅常驻直到用户处理，与 17.5 节联动）；**旋钮焦点变化重置计时**（383-385 行，注释 "Reset the auto dismiss timeout for each rotary event."）——车机有旋钮交互，用户正在用旋钮操作横幅时它不该消失，每次焦点变化重新挂 8 秒表。每条横幅有独立 Handler（18.4 节 HeadsUpEntry），定时互不干扰。新横幅才播声与挂表（323-324 行 `if (currentNotification.mIsNewHeadsUp) { playSound(...); setAutoDismissViews(...); }`）。移除补偿 `maybeRemoveHeadsUp`（189-212 行）：展示不足 `mMinDisplayDuration` 时补挂延时（210-211 行）——与手机侧 `canRemoveImmediately` 的挂起态同一目的、不同实现。

#### 17.4 dismiss 链与回面板

`dismissHun`（489-526 行）：`mIsDismissing` 防重入（500-503 行）→ 清 Handler 回调（504 行）→ 退场动画（508 行 `mAnimationHelper.getAnimateOutAnimator`）→ 动画结束回调里三连：移除视图（513 行 `mHunContainer.removeNotification(view);`）、出账（517 行）、**非应用撤回时把通知放归面板**（520-522 行 `handleHeadsUpNotificationStateChanged(alertEntry, /* isHeadsUp= */ false);` → 15.3 节的 onStateChange → postNewNotification）。滑动驳回走 `resetView`（532-542 行，无退场动画直接移除）。这条"横幅→面板"的迁移链是车机形态的标志性差异：手机侧横幅与列表项同体（一个视图两种呈现），车机侧两者是不同窗口的不同视图，需要显式迁移。

#### 17.5 来电横幅的特殊地位

不可滑动关闭的判定（459-463 行）：

```java
    private boolean shouldDismissOnSwipe(AlertEntry alertEntry) {
        return !(hasFullScreenIntent(alertEntry)
                && Objects.equals(alertEntry.getNotification().category, Notification.CATEGORY_CALL)
                && alertEntry.getStatusBarNotification().isOngoing());
    }
```

带全屏意图、CALL 类、ongoing 三条件齐备的来电横幅**禁止滑动关闭**——行车中来电是必须响应的信息，用户只能接听/挂断（横幅上的 action）或等对方挂断（通知本体消失走 17.4 的移除链）。配合 16.2 节"来电永不进面板"与 16.4 节"来电不分组"，CALL 类通知在车机获得三处特判，构成完整的"来电优先"设计线。

UX 限制联动（653-662 行 `onUxRestrictionsChanged`）：行车限制位 `UX_RESTRICTIONS_NO_TEXT_MESSAGE` 变化时更新 `mShouldRestrictMessagePreview`，消费点 346-353 行——限制中且 MESSAGE 类横幅走 `bindRestricted(...)` 隐藏消息预览正文（只显示"收到一条消息"级别的信息），停车后恢复。倒车/档位联动在本类无直接代码（车厂如需，在上层容器或 UX 配置实现）。

#### 17.6 声音职责倒置：Beeper

`packages/apps/Car/Notification/src/com/android/car/notification/Beeper.java` 的存在理由写在注释里（44-46 行）："For Feature_automotive the sounds for notification will be disabled at the server level and notification center will handle playing all the sounds using this class."——**automotive 形态下 framework 服务端的通知发声被关闭，声音职责整体下放给车机通知库**。手机侧"服务端 buzz/beep/blink 决定响不响"（4.6 节）在车机变成"横幅弹出时库内自播"。

调用点唯一：`CarHeadsUpNotificationManager.playSound`（444-457 行，仅 `notificationChannel.getSound() != null` 时播，且仅新横幅触发，323-324 行）。实现要点：同包 1 秒防轰炸（54 行 `ALLOWED_ALERT_INTERVAL = 1000`，`canAlert` 123-129 行）；`beep()`（102-118 行）通话中改播 `config_inCallNotificationSound`（112-113 行）；内部类 `BeepRecord`（140-290 行）用 MediaPlayer 先请求 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 成功才播（209-221 行，压低音乐音量播提示音），失败回退 RingtoneManager（283-289 行），跨用户场景取前台用户 Context（250-257 行）。声音通道与 channel 的 sound 字段挂钩——channel 无声则横幅无声，这是车机侧 channel 影响横幅行为的少数残留之一。

### 18. 容器、视图与动画：独立窗口的横幅

#### 18.1 CarHeadsUpNotificationContainer：窗口即容器

抽象基类 `packages/apps/Car/Notification/src/com/android/car/notification/headsup/CarHeadsUpNotificationContainer.java`（194 行，38 行声明）。构造（45-54 行）按 `R.bool.config_showHeadsUpNotificationOnBottom`（46-47 行，默认 false）选顶部或底部布局，53 行 `windowManager.addView(mHunWindow, getWindowManagerLayoutParams());`——横幅容器是一个**独立 WindowManager 窗口**，与手机侧"横幅活在 shade 窗口内"根本不同。显示 `displayNotification`（66-75 行）；`shouldShowHunPanel()`（101-103 行）默认 `return !isVisible();`，子类可加成多门（20.2 节）；移除 `removeNotification`（108-120 行）。

Z 序机制（173-193 行）是车机特有设计：

```java
    public enum HunImportance { OTHER, CALL, NAVIGATION, WARNING, EMERGENCY }
```

（序值 0-4。）`displayNotificationInner`（77-96 行）按重要度把新横幅视图插进容器视图层级的对应位置，EMERGENCY 恒置顶（78 行）。手机侧多条横幅靠 `compareTo` 选顶（9.5 节），车机侧靠视图链表分层叠放——车机把"哪类横幅压哪类"做成了显式枚举，与第 2 章拒绝点 4 的受限 category、第 11 号提取器 CriticalNotificationExtractor 构成同一条车机告警特权线。

#### 18.2 两种宿主窗口

应用侧宿主 `CarHeadsUpNotificationAppContainer.java`（51 行）：窗口类型 `TYPE_SYSTEM_ERROR`（45 行，注释 "This type allows covering status bar and receiving touch input"）＋ `FLAG_NOT_FOCUSABLE`（47 行），gravity 随顶/底配置（49 行）。系统侧宿主在 Car SystemUI（20.2 节，`TYPE_STATUS_BAR_SUB_PANEL`）。两者共同点：不抢焦点（旋钮/输入法焦点留给当前应用）、压在应用之上。

#### 18.3 HeadsUpContainerView：旋钮焦点委派

内容容器 `HeadsUpContainerView.java`（152 行）是 FrameLayout 子类，核心职责是把旋钮焦点派给横幅：80 行 `setContentDescription(ROTARY_FOCUS_DELEGATING_CONTAINER);`；非触摸模式请求焦点时 `focusTopmostChild()`（85-90 行）；addView 后延迟入场动画时长抢焦点给最顶横幅（93-101 行）；removeViewAt 后延迟退场时长重派焦点（104-112 行）；`focusTopmostChild`（128-146 行）把默认焦点设到 `R.id.action_1`（第一个 action 按钮）再发 `ACTION_FOCUS` 无障碍动作——**横幅弹出时旋钮焦点自动落到"第一个可操作按钮"上**，驾驶员不看屏不看手就能旋钮确认。手机侧无对应机制。

#### 18.4 HeadsUpEntry：每条横幅一个 Handler

`HeadsUpEntry.java`（76 行，33 行 `extends AlertEntry`）：35/44-47 行每条横幅持独立 `Handler`（注释 49-53 行 "each notification will have its own post time, handler"——多条横幅并存时各自的 8 秒表互不干扰，17.3 节）；36-37 行持视图与 ViewHolder；39-42 行四个状态位 `mIsAlertAgain / mIsNewHeadsUp / mIsDismissing / mShouldRemove`。

#### 18.5 滑动关闭手势

`HeadsUpNotificationOnTouchListener.java`（199 行，在主包非 headsup/ 子包）：三个常量（35 行 `MINIMUM_FLING_VELOCITY = 2000`、40 行 `TOUCH_SLOP = 20`、45 行 `THRESHOLD = 0.3f`）；判定（103-114 行）水平位移超横幅宽度 30% 或甩动速度达标且方向一致才 dismiss；驳回动画（115-126 行）`translationX(±viewWidth).alpha(0)` 后回调 `mCallbacks.onDismiss()`（即 Manager 的 `resetView`，17.4 节）；不达阈值回弹（127-129 行 `animateToCenter()`）。车机横幅是横滑关闭（手机侧是上滑进面板/侧滑删除两套手势）。

#### 18.6 动画：可反射插拔

接口 `HeadsUpNotificationAnimationHelper.java`（animationhelper 目录，37 行）：`getAnimateInAnimator`（30 行）、`getAnimateOutAnimator`（33 行）、`resetHUNPosition`（36 行）。三个内置实现：TopAnimationHelper（62 行，顶部滑入，36-37 行加载 `R.animator.heads_up_notification_transition_in`，55-57 行初始位 `-1 * view.getHeight()`）；BottomAnimationHelper（64 行，同构底部）；RightAnimationHelper（90 行，纯代码 ObjectAnimator，位移＋透明度双插值，36-57 行）。时长常量（integers.xml:28,31,34）：

```xml
    <integer name="headsup_total_enter_duration_ms">233</integer>
    <integer name="headsup_alpha_enter_duration_ms">83</integer>
    <integer name="headsup_exit_duration_ms">150</integer>
```

加载机制是反射（Manager 129-139 行 `getAnimationHelper()` 按 `R.string.config_headsUpNotificationAnimationHelper` 配置的类名实例化，默认值在 config.xml:60-61 = `CarHeadsUpNotificationTopAnimationHelper`）——车厂不改库代码即可换整套横幅动画。进场触发在 Manager 367-381 行的 `OnGlobalLayoutListener` 内（先 `resetHUNPosition(view)` 再 `getAnimateInAnimator(...).start()`），且仅新横幅播进场（321 行 `shouldShowAnimation = !isUpdate(alertEntry)`）；退场在 dismissHun 内（508-525 行，17.4 节）。

### 19. 模板：CarNotificationViewAdapter 的 category 分发

`packages/apps/Car/Notification/src/com/android/car/notification/CarNotificationViewAdapter.java`（731 行，57-58 行 `extends ContentLimitingAdapter<RecyclerView.ViewHolder> implements PreprocessingManager.CallStateListener`）服务通知中心列表。category→模板映射在 `getItemViewTypeImpl`（245-264 行）：

```java
        String category = notification.category;
        if (category != null) {
            switch (category) {
                case Notification.CATEGORY_CALL:          return NotificationViewType.CALL;
                case Notification.CATEGORY_CAR_EMERGENCY: return NotificationViewType.CAR_EMERGENCY;
                case Notification.CATEGORY_CAR_WARNING:   return NotificationViewType.CAR_WARNING;
                case Notification.CATEGORY_CAR_INFORMATION: ... CAR_INFORMATION(_IN_GROUP);
                case Notification.CATEGORY_MESSAGE:       ... MESSAGE(_IN_GROUP);
```

无 category 时按 extras 依次判 PROGRESS（267-278 行）、INBOX（281-286 行）、GROUP_SUMMARY（289-292 行）；BigText/BigPicture 样式在车机**回退 BASIC 模板**（294-307 行，注释原文 "the big text and big picture styles are fallen back to basic template in car / i.e. setting the big text and big picture does not have an effect"）——车机不支持大文本/大图样式，这是接入车机通知的常见困惑源。视图类型的模板表在 `CarNotificationTypeItem`（适配器 156-160 行 `CarNotificationTypeItem.of(viewType)` 取 `getNotificationCenterTemplate()` 充气）。绑定（166-207 行）：组摘要 `setHideDismissButton(true)`（192 行）；行车限制下 MESSAGE 类走 `bindRestricted`（200-206 行，判定 637-640 行 `shouldRestrictMessagePreview()` = `UX_RESTRICTIONS_NO_TEXT_MESSAGE` 位——与 17.5 节横幅侧同一限制位）。列表刷新用 DiffUtil（443-493 行，482-486 行 `CarNotificationDiff`）；recents/older 双段装配（495-551 行）；组展开状态表（391-425 行）；可见即已读（677-697 行 `setVisibleNotificationsAsSeen(start, end)`）；通话状态变化整表刷新（616-622 行 `onCallStateChanged`，接口来自 16.4 节的广播监听）。

横幅侧的模板选择走同一张 `CarNotificationTypeItem` 表的 `getHeadsUpTemplate()`（yadea 定制版在 Manager 514-522 行 inflate；AOSP 库同构）——列表模板与横幅模板同源同类、两套布局。

### 20. Car SystemUI 接线

#### 20.1 目录与装配

`packages/apps/Car/SystemUI/src/com/android/systemui/car/notification/` 共 9 文件：BottomNotificationPanelViewMediator.java(74)、CarHeadsUpNotificationSystemContainer.java(93)、CarNotificationModule.java(96)、NotificationPanelViewController.java(703)、NotificationPanelViewMediator.java(237)、NotificationShadeWindowControllerImpl.java(54)、NotificationVisibilityLogger.java(172)、PowerManagerHelper.java(103)、TopNotificationPanelViewMediator.java(74)。

DI 装配在 `CarNotificationModule.java`：50-55 行 Provides `NotificationClickHandlerFactory`（点击处理，依赖 IStatusBarService 回报统计）；57-62 行 Provides `NotificationDataManager` 单例；64-68 行 Provides `CarUxRestrictionManagerWrapper`；70-81 行 Provides `CarNotificationListener` 且**注入即注册**：

```java
        CarNotificationListener listener = new CarNotificationListener();
        listener.registerAsSystemService(context, carUxRestrictionManagerWrapper,
                carHeadsUpNotificationManager);
        return listener;
```

83-91 行 Provides `CarHeadsUpNotificationManager`（注入 clickHandlerFactory 与 headsUpNotificationDisplay）；93-95 行 Binds 容器接口到系统实现：

```java
    @Binds
    abstract CarHeadsUpNotificationContainer bindsCarHeadsUpNotificationContainer(
            CarHeadsUpNotificationSystemContainer carHeadsUpNotificationSystemContainer);
```

#### 20.2 系统容器：窗口类型与双门

`CarHeadsUpNotificationSystemContainer.java`（37 行 `@SysUISingleton`，50 行继承基类）的窗口参数（66-84 行）：

```java
        // Use TYPE_STATUS_BAR_SUB_PANEL window type since we need to find a window that is above
        // status bar but below navigation bar.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = getShowHunOnBottom() ? Gravity.BOTTOM : Gravity.TOP;
        lp.setTitle(WINDOW_TITLE);   // "HeadsUpNotification" (:51)
```

`TYPE_STATUS_BAR_SUB_PANEL`：压在状态栏之上、导航栏之下——车机横幅不遮系统栏。显示双门（86-92 行，覆写 18.1 节的 `shouldShowHunPanel`）：

```java
        return mCarDeviceProvisionedController.isCurrentUserFullySetup()
                && mOverlayViewGlobalStateController.shouldShowHUN();
```

用户未完成开机设置（provisioning）不弹；OverlayView 全局状态不允许（如全屏地图/倒车影像占屏）不弹。第二道门的输入来自面板控制器（20.3 节）。

#### 20.3 面板控制器与 Mediator

Car SystemUI 的 `NotificationPanelViewController.java`（703 行，91-92 行 `extends OverlayPanelViewController implements CommandQueue.Callbacks`）：读库侧配置 `config_enableHeadsUpNotificationWhenNotificationPanelOpen`（108、196-198 行；值 true，`packages/apps/Car/SystemUI/res/values/config.xml` 90 行），并把它喂给 OverlayView 全局状态（300-303 行）：

```java
    @Override
    protected boolean shouldShowHUN() {
        return mEnableHeadsUpNotificationWhenNotificationPanelOpen;
    }
```

——"通知面板打开时还弹不弹横幅"是一个产品配置项（20.2 节双门的第二门）；手机侧的对应决策硬编码在 HeadsUpManagerPhone 的 pinned 逻辑里（10.1 节），车机把它开放给 RRO 覆写。`onNotificationViewInflated`（347-409 行）：注册点击收起面板回调（359-366 行）；未读数回调（368-385 行，376 行 `getNonLowUnseenNotificationCount`——未读角标只数 importance > LOW 的，与 15.2 节同源）；创建 `NotificationViewController`（388-392 行，注入 PreprocessingManager 单例与 CarNotificationListener——**面板经此消费 16 章流水线的输出与 15 章 Handler 事件**）；Car 服务连接后启用（394-408 行，407 行 `mNotificationViewController.enable();`）。开机上电清空全部通知（529-536 行 `onCarPowerStateOn`——车机上一次行程的通知不过夜）。面板开合指令来自 CommandQueue（220-234 行，`animateExpandNotificationPanel`/`animateCollapsePanels` 幂等 toggle）——车机侧 CommandQueue 只管面板动画指令，与手机侧 A13 的分工一致（6.1 节）。

接线层 `NotificationPanelViewMediator.java`（237 行，58 行 implements OverlayViewMediator 等）：`registerListeners()`（114-142 行，含顶栏/底栏触摸监听注册 123-124 行、电源状态广播 141 行）；未读数接 systembar 角标（146-152 行）；四边拖拽监听（209-225 行，可覆写）。Top/Bottom 两个子类（各 74 行）只差方向：`setOverlayDirection`(OVERLAY_FROM_TOP_BAR/OVERLAY_FROM_BOTTOM_BAR)（各自 64-65 行）与拖拽监听覆写（68-73 行）；Bottom 版 Javadoc（31-32 行）误写 "from the top navigation bar"，系复制漏改——车机通知面板可从顶栏或底栏拉出，是 AAOS 的屏幕形态适配点。

### 21. 对照章：两套横幅实现的逐项差异

| 维度 | 手机形态 SystemUI | Car 库车机形态 |
|---|---|---|
| 判定位置 | SystemUI 进程内 interruption 包（`NotificationInterruptStateProviderImpl`），coordinator 消费 | Car 库 `CarHeadsUpNotificationManager.shouldShowHeadsUp`，监听回调直接驱动（15.2 节） |
| 判定依据 | 十一道门槛（importance≥HIGH、snooze、气泡、old-when、DND 位、suppressor…） | 五否决三放行（importance≥HIGH、锁屏、导航开关、分组、静音 ／ 平台签名、Car Assist、CALL+NAVIGATION） |
| 横幅与列表关系 | 同一条目两种呈现（shade 列表项 pinned 上屏） | 互斥两世界：先试横幅，落选/结束后才入面板（15.2、15.3 节） |
| 窗口 | shade 窗口内（FLAG_NOT_TOUCH_MODAL＋touchable region，11.2/11.3 节） | 独立 WM 窗口（TYPE_STATUS_BAR_SUB_PANEL／TYPE_SYSTEM_ERROR，18.2/20.2 节） |
| 多条横幅 | 账本比较取顶（compareTo 五级，9.5 节） | 视图 Z 序链表（HunImportance 五档，18.1 节） |
| 超时 | 5000ms 衰减＋700ms 触摸延迟，最短 2000ms，AOD 延长 10s，无障碍可放大 | 8000ms，最短 2000ms，FSI 豁免，旋钮焦点重置（17.3 节） |
| 声音 | 服务端 buzz/beep/blink 裁决（4.6 节） | 服务端关声，库内 Beeper 自播（17.6 节） |
| 来电特判 | 排序层 criticalCall 优先（9.5 节） | 三处特判：永不进面板、不分组、FSI+ongoing 禁滑动（16.2/16.4/17.5 节） |
| 驾驶分心联动 | 无 | UX 限制裁文本（16.3 节）、隐藏消息预览（17.5 节） |
| 焦点交互 | 触摸为主，无障碍 touch exploration 抑制横幅（7.4 节） | 旋钮焦点自动委派到第一个 action（18.3 节） |
| 面板互斥 | pinned 逻辑硬编码（10.1 节） | `config_enableHeadsUpNotificationWhenNotificationPanelOpen` 可配（20.3 节） |
| 通知生命周期 | 跨会话保留（shade 常驻） | 上电清空（20.3 节 onCarPowerStateOn） |

两套实现共享的只有三样：框架基类 `NotificationListenerService` 与服务端裁决（第 5 章对两者一视同仁）、importance ≥ HIGH 的准入门槛、`suppressAlertingDueToGrouping` 分组抑制 API。其余全部各自为政——这不是"一份代码两种配置"，而是两套平行实现。理解这一点是读懂雅迪定制版的前提：yadea SystemUI 拿 Car 库的类名与骨架，替换了判定内容（importance 门槛注释掉、业务等级体系接入）、窗口宿主（TYPE_NAVIGATION_BAR_PANEL）、时钟（8000→5000）与整个媒体链路（NavBar 底栏媒体卡片），逐项对照与接入文档审计见《yadea通知接入对照与审计》。

---

## 附录 A：树况与版本说明

本文全部引文以本仓当前检出为准。以下树况事实直接影响引用与检索，集中说明：

1. **VisualInterruption 系列不在仓**：上游 A13 的 `VisualInterruptionDecision` / `VisualInterruptionDecisionProvider` / `VisualInterruptionStageProvider`（把横幅/pulse/FSI 三种打断汇总为一个 decision 对象并作为管线 stage 执行）在本树 SystemUI 内零命中。本树的等价链路是 `HeadsUpCoordinator` 直接消费 `NotificationInterruptStateProvider`（判定→异步绑定→showNotification，8.1/8.2 节），判定逻辑本体完整在 `NotificationInterruptStateProviderImpl`（第 7 章）。本文所有判定引文均出自后者，不依赖缺失文件。
2. **RankingRecord 已并入 NotificationRecord**：A13 上游演进如此，非本仓缺文件；旧资料的 `RankingRecord.getImportance()` 对应本仓 `NotificationRecord.getImportance()`（941-943 行）。
3. **NMS 无 postNotification 方法**：Binder 入口是 `enqueueNotificationWithTag`（3704 行）；`PostNotificationRunnable`（7931 行）是内部 runnable，不是公开方法名。
4. **NotificationListenerService 与 StatusBarNotification 的路径**：在 `frameworks/base/core/java/android/service/notification/` 下，不在 `core/java/android/app/`。
5. **A13 通知事件不经 CommandQueue**：SystemUI 侧 grep 证实（6.1 节）；CommandQueue 只余面板动画指令。
6. **Car 库无 NotificationController.java**：A13 已拆分为 `CarNotificationItemController` 等（在仓）；旧文章引用该类名需换算。
7. **Car 树内版本缝隙**：Car SystemUI 的 `NotificationPanelViewController.java:380` 调用 `setNotificationsShown(...)`，而本仓 CarNotificationListener（317 行版）无此方法——两目录快照版本略异，本文各自按实况引用（15.3 节）。
8. **手机侧横幅超时的资源名**：本仓 SystemUI 用 `heads_up_notification_decay`（5000）体系；上游部分版本/产品的 `SHORT_AUTO_DISMISS_TIMEOUT`/`LONG_AUTO_DISMISS_TIMEOUT` 命名不在本树，未引用。

与横幅判定直接相关的 A12→A13 可证变化（仅本树可证者）：判定与管线的装配位置在 A13 收敛到 coordinator 体系（`NotifCoordinators.kt` 分节序注释把 HeadsUp 列第一，6.6 节）；`NotificationListener` 与插件层拆分为父子两类（6.1/6.2 节）；FSI 判定独立成决策树并引入 `fullScreenIntentRequiresKeyguard` flag 分支（7.5 节）。更早版本的横幅逻辑（如 StatusBar 类承担 CentralSurfaces 职责）在本树已更名为 `CentralSurfaces`/`CentralSurfacesImpl`，引用旧类名的资料需换算。

## 附录 B：关联阅读

- 《通知服务与车机打扰治理》（AAOS13_study 仓 `docs/md/通知服务与车机打扰治理.md`）：第 2 章入队管线、第 3 章 importance 权限模型、第 4 章 ZenMode 引擎、第 5 章排序流水线、第 6 章 buzz-beep-blink、第 7 章车机两层打扰闸门——本文第一部各章的治理视角展开。
- 《SystemUI 源码分析》（AAOS13_study 仓 `docs/md/SystemUI.md`）：第 3 章通知管线（NotifCollection → shade list）、第 5 章下拉阴影窗口机制——本文第二部 6/11 章的宿主框架。
- 《yadea通知接入对照与审计》（同目录 `yadea通知接入对照与审计.md`）：本文第三部的下游篇——雅迪车机 SystemUI 对 Car 库的定制解剖与接入文档 136 条断言逐条审计。

