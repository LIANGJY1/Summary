# yadea 通知接入对照与审计：三条链路解剖与 136 条断言逐条核验

> 本文做两件事。前半是机制解剖：把 yadea 车机 SystemUI 的三条通知链路（普通横幅、业务等级、媒体与 CarPlay 触摸转发）按源码走读一遍，每条链路回答"规则是什么、谁设置、谁执行、后果是什么"。后半是逐条审计：把《notification-app-integration 外部应用通知接入文档》（552 行）当作待验证断言清单，穷举出 136 条事实断言，逐条对照 yadea 源码给出判定与证据。
>
> 平台侧（AOSP AAOS13）的横幅全链路走读见姊妹篇 `docs/md/Android13横幅通知全链路解剖.md`；本文只在对照需要时引用其结论，不重复平台侧走读。

---

## 0. 审计对象与口径

### 0.1 两套代码的工程形态反差

先把一个容易被接入文档掩盖的事实放在最前面：**yadea 的 SystemUI 不是 AOSP 平台源码树里的 SystemUI，而是一个独立的 Gradle APK 工程**，位于 yadea_master 树的 application/SystemUI 模块。它借用了 AOSP Car Notification 库的一批类名（`CarNotificationListener`、`PreprocessingManager`、`CarHeadsUpNotificationManager`、`NotificationUtils`），包路径也沿用 `com.android.systemui.*`，但内部机制已经大幅改装：

- 数据载体叫 `AlertEntry`，管线里多了一道 AOSP 没有的"业务等级"（L0/LX/L1/UNKNOWN）体系；
- 横幅判定里 AOSP 的 importance 门槛被整段注释掉，换成了"特权 / category / 业务等级"三通道；
- 前置过滤从 AOSP 的"低重要度特权前台服务通知才过滤"收紧成"全部前台服务通知 + 四个系统包 + 媒体与导航一律丢弃"；
- HUN 容器从 AOSP Car SystemUI 的系统窗口（TYPE_STATUS_BAR_SUB_PANEL）换成默认屏 TYPE_NAVIGATION_BAR_PANEL、副屏（displayId=2）TYPE_SYSTEM_DIALOG 的双容器结构；
- 媒体通知不走 AOSP 的 MediaDataManager 独立链，而是回归通知监听链，由底部导航栏 NavBarFragment 的单槽媒体卡片承接，并叠了一套 CarPlay DOWN/UP 触摸转发机制。

所以审计的基准只有一个：**以 yadea_master 树的实际源码为准**。文档里凡是与 AOSP 行为"想当然对齐"的描述，都要在 yadea 代码里重新找证据。

### 0.2 路径与缩写约定

yadea 树不在本仓库内，受文档路径机械门约束，其完整路径只出现在下面这个代码块与文末附录里；正文与表格一律使用"类文件名:行号"的无路径写法。本仓库（AAOS13）路径可以直接反引号给出。

```text
yadea 树根 ROOT = /home/liang/Project/Reachauto/YaDi/yadea_master

  SYSUI    = ROOT/application/SystemUI/src/main/java/com/android/systemui
  NOTIF    = SYSUI/notification              横幅/等级/预处理主战场
  TEMPLATE = NOTIF/template                  ViewHolder 与 ActionsView
  HEADSUP  = NOTIF/headsup                   HUN 窗口容器
  NAVBAR   = SYSUI/navbar/ui                  NavBarFragment 底栏媒体卡片
  BTMUSIC  = ROOT/application/BTMusic/src/main/java/com/yadea/btmusic
  RES      = ROOT/application/SystemUI/src/main/res
  MANIFEST = ROOT/application/SystemUI/src/main/AndroidManifest.xml

正文类名缩写（未注明者均在 NOTIF 下）：
  Listener = CarNotificationListener.java        491 行
  CHUNM    = CarHeadsUpNotificationManager.java  953 行
  PPM      = PreprocessingManager.java           1073 行
  Utils    = NotificationUtils.java
  Clf      = NotificationLevelClassifier.java    184 行
  Decider  = HeadsUpNotificationDecider.java     52 行
  DispDec  = HeadsUpDisplayDecider.java          25 行
  ReplDec  = HeadsUpReplacementDecider.java      40 行
  DataMgr  = NotificationDataManager.java
  ClickFac = NotificationClickHandlerFactory.java
  NavBar   = NAVBAR/NavBarFragment.java

AAOS13 树（本仓库）：
  AOSP Car 库  = packages/apps/Car/Notification/
  手机 SystemUI = frameworks/base/packages/SystemUI/
```

### 0.3 审计方法与判定档位

审计分三步。第一步通读接入文档全文 552 行，按文档顺序穷举事实断言，得到 136 条（其中 103 条独立断言、33 条是文档第 4/9/10/11 节对前文事实的复述；复述条目按行号另立编号并标注复述来源）。纯主观建议（如"文案尽量短"）、纯示例代码体、纯推荐值不计入断言。第二步逐条在 yadea 源码中定位证据，所有引文从 ROOT 树源文件逐字抄录；涉及平台行为的条目（9 条"双侧"、2 条"AOSP 侧"）同时对照本仓库 AAOS13 源码。第三步给出四档判定：

| 标记 | 档位 | 含义 |
|---|---|---|
| ✅ | 属实 | 断言与当前源码一致 |
| 🟡 | 部分属实 | 断言主体成立，但范围、条件或措辞与实现有出入，按实现修正后才可采信 |
| ❌ | 证伪 | 断言与当前源码矛盾，或引用对象不存在 |
| ⬜ | 无法本机核验 | 断言对象是 CarPlay 应用侧行为，CarPlay APK 不在本工程，只能核验 SystemUI 侧的对应事实 |

### 0.4 总判定预览

136 条断言的判定分布：

| 判定 | 条数 | 占比 |
|---|---|---|
| ✅ 属实 | 112 | 82.4% |
| 🟡 部分属实 | 17 | 12.5% |
| ❌ 证伪 | 2 | 1.5% |
| ⬜ 无法本机核验 | 5 | 3.7% |

总体结论：这份接入文档的骨架是可信的——监听入口、业务等级 extras 键、媒体识别四条件、DOWN/UP 触摸机制、模板映射等核心事实全部与源码对得上。问题集中在三类：**把死代码或半成品写成现有行为**（导航横幅、静音规则、幽灵文件）、**机制描述与实现的松紧不一致**（DOWN/UP 单槽覆盖、子串匹配、位置兜底错位、channelId 参与等级兜底）、**工程事实未同步**（推荐 channel 无系统侧实现、CarPlay 包名不完整）。

十四项重大发现（详见第 6 节；其中 F1 含导航、通话两案）：

1. **导航通知死链**：`CATEGORY_NAVIGATION` 在监听器入口即被丢弃，横幅与列表都不显示；文档 §9.2 的导航接入建议会让导航通知彻底不可见（F1）。
2. **通话类目双杀**：`CATEGORY_CALL` 被面板列表无条件剔除，又不在横幅 category 白名单里，第三方通话通知仅特权或带业务等级时才能以横幅现身（F1）。
3. **startForeground 非媒体通知被丢弃**：一切带 `FLAG_FOREGROUND_SERVICE` 的通知进不了横幅也进不了列表，媒体通知因分流在前而豁免（F2）。
4. **幽灵文件**：文档引用的 `BannerNotificationView.java` 在工程中不存在；配套死布局 `item_banner_notification.xml` 存在但全工程零引用（F3）。
5. **三个推荐 channel 零实现**：high_importance_channel、app_notice_channel、service_channel 在工程内无任何创建或消费点，纯属文档约定；唯一被代码硬编码消费的 channel 是 media_playback_channel（F4）。
6. **importance 门槛注释掉且 javadoc 脱节**：类注释仍写着"Importance >= HIGH"等四条旧准入，方法体实际是"特权/category/业务等级"三通道（F5）。
7. **Play/Pause 的 DOWN/UP 是单槽，后写覆盖先写**：文档要求"Play 和 Pause 的 DOWN/UP 都需提供"，但两组共用一对槽位，同时提供时只有数组中最后一组生效，且不随播放状态切换（F6）。
8. **DOWN/UP 识别比命名规则松**：小写化后任意位置的 contains("down")/contains("up") 子串即命中，不要求后缀（F7）。
9. **标题失配时的位置兜底会错位**：兜底把 actions[0..2] 机械分给上一首/播放/下一首；只传 2 个动作且标题不可识别时，播放键实际发送"下一首"的 intent（F8）。
10. **静音是半成品**：静音判定链完整，但 `toggleMute` 全工程无调用点，当前不存在能把通知置为静音的用户入口；文档把未接线功能写成现有行为与排查项（F9）。
11. **L0 降级保护面比文档暗示的窄**：降级只作用于路由/分区/可清除性；横幅准入用的是不降级的识别等级，第三方伪装 L0 照样弹主屏横幅（F10）。
12. **Previous 按钮是演化残留**：布局恒 gone 且无恢复代码，但识别、点击、兜底接线全套保留，BTMusic 参考实现仍在发送 "Previous" 动作并占用 actions[0]（F11）。
13. **SystemUI 无 CarPlay 特判且包名不符**：工程内 CarPlay 包名常量是 com.ts.carplay.app（仅 Launcher 侧引用），文档写 com.ts.carplay；DOWN/UP 机制是对所有应用开放的通用 title 协议（F12）。
14. **系统四包黑名单**：android、com.android.systemui、com.android.server.telecom、com.android.car.dialer 四个包的通知到达即丢弃，横幅列表全无，文档只字未提（F13）。
15. **channelId 文本参与等级兜底**：channelId（和包名）里的 l0/l1/lx/fault/warning/alarm/error/opponent/peer/duishoujian 子串会直接改变业务等级归属，"其它 channelId 无强制要求"不成立（F14）。

---

## 第一部 机制解剖：三条链路

## 1. 链①：一条普通横幅通知的往返（yadea 版）

本章沿"应用 notify → 监听器 → 预处理 → 横幅判定 → 置换与路由 → 显示 → 时钟 → 点击 → 收回"的顺序走读。与 AOSP Car 库的同名链路对照见第 4 节总表，平台侧（手机 SystemUI）链路见解剖篇第二部。

### 1.1 监听器声明与容器接线

监听器在 MANIFEST 285-293 行声明，权限、directBootAware、exported 三要素齐备：

```xml
<service android:name="com.android.systemui.notification.CarNotificationListener"
    android:label="@string/notification_service_label"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:directBootAware="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService"/>
    </intent-filter>
</service>
```

横幅的两个窗口容器在 `SystemUIApplication.kt` 108-109 行创建——默认屏一个，displayId=2 的副屏一个：

```kotlin
val defaultContainer = CarHeadsUpNotificationAppContainer(applicationContext)
val secondaryContainer = CarHeadsUpNotificationAppContainer(applicationContext, 2)
```

窗口类型的选择在 `CarHeadsUpNotificationAppContainer.java` 38-49 行，注释解释了为什么默认屏不用 TYPE_NAVIGATION_BAR：

```java
public CarHeadsUpNotificationAppContainer(Context context) {
    // 默认屏不用 TYPE_NAVIGATION_BAR(2019)：该 type 每屏唯一，会和 Dock 抢窗口位。
    this(context, Display.DEFAULT_DISPLAY,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL);
}

public CarHeadsUpNotificationAppContainer(Context context, int displayId) {
    this(context, displayId,
            displayId == Display.DEFAULT_DISPLAY
                    ? WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL
                    : WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
}
```

对照 AOSP Car 库：那边的 SystemContainer 用 TYPE_STATUS_BAR_SUB_PANEL 挂在 Car SystemUI 的系统窗口里（见解剖篇 §20.2）；yadea 是独立 APK，没有平台 SystemUI 的窗口体系可依附，于是自建双容器，副屏走 TYPE_SYSTEM_DIALOG。窗口可见性/可触性规则集中在 `HeadsUpWindowStateDecider.java`（36 行小文件）：有可见通知才可见可触，无通知时窗口高度收成 1px（COLLAPSED_WINDOW_HEIGHT_PX = 1，9 行）。

### 1.2 onNotificationPosted：入口六段

Listener 128-169 行是全部通知的唯一入口，六个段落依次是：空值检查（129-132）、当前用户过滤（139-146）、AlertEntry 包装（148）、**媒体分流**（149-159）、**前置过滤丢弃**（160-166）、排名更新与分发（167-168）。后两段是 yadea 相对 AOSP Car 库的关键改装，引文如下（149-168 行）：

```java
        AlertEntry alertEntry = new AlertEntry(sbn);
        if (NotificationUtils.isMediaLikeNotification(alertEntry.getNotification())) {
            // Android's auto-group summary inherits the media channel but has no real media
            // contentIntent/actions. Do not let it overwrite the actual media notification.
            if (!alertEntry.getNotification().isGroupSummary()) {
                mCurrentMediaEntry = alertEntry;
                if (mChangeListener != null) {
                    mChangeListener.onShow(alertEntry);
                }
            }
            return;
        }
        // 前置过滤：前台服务通知、系统通知等无效通知直接丢弃，不进入后续流程
        if (mPreprocessingManager.shouldFilter(alertEntry, rankingMap)) {
            if (DEBUG) {
                Log.d(TAG, "onNotificationPosted: filtered out key=" + alertEntry.getKey());
            }
            return;
        }
        onNotificationRankingUpdate(rankingMap);
        notifyNotificationPosted(alertEntry);
```

三点必须看清：

其一，**媒体分流在过滤之前**。媒体类通知（含自动分组摘要之外的）直接写入单槽 `mCurrentMediaEntry` 并回调 NavBar，然后 return——它们永远不会到达 shouldFilter，这就是 BTMusic 用 `startForeground` 发媒体通知却不被前台服务过滤误伤的原因（顺序豁免，见 F2）。分组摘要被刻意排除，注释写明原因：Android 自动分组摘要会继承媒体 channel，但没有真实的媒体 contentIntent/actions，不能让它覆盖真正的媒体通知。

其二，**shouldFilter 被前移到了监听器入口**。AOSP Car 库里 shouldFilter 只在面板列表管线（PreprocessingManager.process）里生效，横幅链路不经过它；yadea 把它同时变成了"入口丢弃闸"和"横幅复查闸"（CHUNM 189 行，见 1.5），这意味着被 shouldFilter 命中的通知**横幅和列表两头都进不去**。导航通知的死链（F1）就死在这里。

其三，被丢弃的通知连 `mActiveNotifications` 都不进，后续 onNotificationRemoved 对它们也无事发生——丢弃是彻底的。

当前用户过滤（342-347 行）与 AOSP 同款：通知的 user 必须等于 `ActivityManager.getCurrentUser()`，另放行系统自身通知（CarNotification 作为 SystemUI 组件运行时）。

### 1.3 shouldFilter 三把刀

PPM 225-229 行，三把刀任意一把命中即丢弃：

```java
    boolean shouldFilter(AlertEntry alertEntry, RankingMap rankingMap) {
        return isSystemNotification(alertEntry)
                || isForegroundServiceNotification(alertEntry)
                || isMediaOrNavigationNotification(alertEntry);
    }
```

第一把，系统包黑名单（262-277 行）。四个包名硬编码在 PPM 69-72 行：`PKG_ANDROID`（"android"）、`PKG_SYSTEM_UI`（"com.android.systemui"）、`PKG_ANDROID_TELECOM`（"com.android.server.telecom"）、`PKG_ANDROID_CAR_DIALER`（"com.android.car.dialer"），命中即丢：

```java
        String packageName = alertEntry.getStatusBarNotification().getPackageName();
        boolean shouldFilter = PKG_ANDROID.equals(packageName)
                || PKG_SYSTEM_UI.equals(packageName)
                || PKG_ANDROID_TELECOM.equals(packageName)
                || PKG_ANDROID_CAR_DIALER.equals(packageName);
```

注意这里丢的是 telecom 和 car.dialer 的**全部**通知——比后文面板层对 `CATEGORY_CALL` 的剔除更彻底：系统拨号栈的来电通知在入口就没了。yadea 自己的通话应用（BTPhone）若走别的包名和类目则不在此列。

第二把，前台服务通知一律丢弃（258-260 行）：

```java
    private boolean isForegroundServiceNotification(AlertEntry alertEntry) {
        return (alertEntry.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }
```

对照 AOSP Car 库：那边只过滤"importance 低于 DEFAULT **且**来自系统特权应用"的前台服务通知（isLessImportantForegroundNotification），普通应用的前台服务通知照常显示。yadea 收紧成一刀切，直接后果就是接入文档"结论先说"第二条对 startForeground 的承诺在展示层失效（F2）。

第三把，媒体与导航（279-287 行）：

```java
    private boolean isMediaOrNavigationNotification(AlertEntry alertEntry) {
        Notification notification = alertEntry.getNotification();
        boolean mediaOrNav = notification.isMediaNotification()
                || Notification.CATEGORY_NAVIGATION.equals(notification.category);
        ...
        return mediaOrNav;
    }
```

媒体那一半与入口分流有重叠但不等价：分流用的是宽口径 `isMediaLikeNotification`（四条件，见 3.1），这里用的是 AOSP 严口径 `isMediaNotification()`（要求 MediaStyle 且带 MediaSession token）。宽口径媒体在入口已经分流走，到不了这里；能走到这把刀的严口径媒体只剩理论路径。真正每刀见血的是导航那一半：**任何 category 为 `CATEGORY_NAVIGATION` 的通知在此被判死**，除非它先被宽口径媒体分流捞走（导航通知几乎不可能带 MediaStyle）。这就是 F1 的直接证据。

面板管线里还有一段 filter（235-256 行），在 shouldFilter 之外无条件剔除 `CATEGORY_CALL`（248-249 行），注释沿用 AOSP 原文："Call notifications should not be shown in the panel. Since they're shown as persistent HUNs..."。但在 yadea，CALL 已经不在横幅 category 白名单里（见 1.6），"以常驻横幅展示"的前提不成立了——注释是 AOSP 的遗言，剔除却还在执行，于是第三方 CALL 通知列表横幅两头落空（F1 第二案）。

### 1.4 notifyNotificationPosted：面板可见即让路

过了入口的通知进入 Listener 309-340 行：

```java
    private void notifyNotificationPosted(AlertEntry alertEntry) {
        if (LostModeHelper.INSTANCE.isActive(getContext())
                || LostModeHelper.INSTANCE.isLostModeShowing()) {
            Log.d(TAG, "notifyNotificationPosted blocked: lost mode active, key="
                    + alertEntry.getKey());
            return;
        }
        if (isNotificationHigherThanLowImportance(alertEntry)) {
            mNotificationDataManager.addNewMessageNotification(alertEntry);
        } else {
            mNotificationDataManager.untrackUnseenNotification(alertEntry);
        }
        boolean isShowingHeadsUp = false;
        // 通知列表已经展开时，新通知直接进入列表，不再走横幅，避免用户下拉查看时被 HUN 打断。
        if (mIsNotificationPanelVisible) {
            Log.d(TAG, "notifyNotificationPosted: panel visible, skip HUN and post directly, key="
                    + alertEntry.getKey());
        } else {
            isShowingHeadsUp = mHeadsUpManager.maybeShowHeadsUp(alertEntry, getCurrentRanking(),
                    mActiveNotifications);
        }
        ...
        if (!isShowingHeadsUp) {
            updateOverrideGroupKey(alertEntry);
            postNewNotification(alertEntry);
        } else {
            dispatchNotificationIconStateChanged();
        }
    }
```

四段逻辑：

- **LostMode 闸**（310-315）：车辆"迷失模式"（LostModeHelper）激活或正在显示时，一切新通知不入面板不弹横幅，直接 return。这是 yadea 特有的整树熔断，AOSP 无对应物。CHUNM 183-188 行还有同款复查。
- **importance 记账**（316-320）：`isNotificationHigherThanLowImportance`（398-402 行，importance > `IMPORTANCE_LOW`）只决定这条通知是否进入 DataMgr 的 unseen/静音记账，**不参与任何展示判定**。这是 importance 在 yadea 展示链里仅剩的存在感，也是文档 A113"很多逻辑不强依赖 channel importance"的实据。
- **面板可见让路**（322-329）：通知面板已展开时跳过横幅直接入列表，中文注释写明动机。对照 AOSP Car 库：那边横幅与面板列表互斥、横幅结束后补录进面板（解剖篇 §15.1/15.3），yadea 把互斥硬编码在监听器里且展开瞬间全收在飞横幅；对照手机 SystemUI：那边的互斥发生在 HeadsUpManagerPhone 的 pinned/panel 状态机（解剖篇 §10.1）。
- **横幅失败回落列表**（334-339）：`postNewNotification` 经 371-379 行把 AlertEntry 递给 `NotificationViewController.NotificationUpdateHandler`，进通知中心列表。横幅与列表是互斥出口：同一条通知要么弹横幅（并登记状态栏图标），要么进列表。

反向操作也存在：面板展开的瞬间（`setNotificationPanelVisible(true)`，421-426 行）调用 `mHeadsUpManager.releaseAllHeadsUpToShade()`，把在飞的横幅全部收回面板——横幅与面板从不同时占据用户注意力。

### 1.5 maybeShowHeadsUp：横幅判定的实际生效顺序

CHUNM 179-229 行。与 AOSP Car 库相比，yadea 在头部多插了两道闸，尾部的三标志逻辑保持一致：

```java
    public boolean maybeShowHeadsUp(
            AlertEntry alertEntry,
            NotificationListenerService.RankingMap rankingMap,
            Map<String, AlertEntry> activeNotifications) {
        if (LostModeHelper.INSTANCE.isActive(mContext)
                || LostModeHelper.INSTANCE.isLostModeShowing()) {
            Log.d(TAG, "maybeShowHeadsUp blocked: lost mode active, key="
                    + alertEntry.getKey());
            return false;
        }
        if (mPreprocessingManager.shouldFilter(alertEntry, rankingMap)) {
            // check if this is an update to the existing notification and if it should still show
            // as a heads up or not.
            HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                    alertEntry.getKey());
            if (currentActiveHeadsUpNotification == null) {
                ...
                return false;
            }
            if (CarNotificationDiff.sameNotificationKey(currentActiveHeadsUpNotification,
                    alertEntry)
                    && currentActiveHeadsUpNotification.getHandler().hasMessagesOrCallbacks()) {
                dismissHun(alertEntry);
            }
            return false;
        }
        if (!shouldShowHeadsUp(alertEntry, rankingMap)) {
            return false;
        }
        boolean containsKeyFlag = !activeNotifications.containsKey(alertEntry.getKey());
        boolean canUpdateFlag = canUpdate(alertEntry);
        boolean alertAgainFlag = alertAgain(alertEntry.getNotification());
        ...
        if (containsKeyFlag || canUpdateFlag || alertAgainFlag) {
            if (!prepareIncomingHeadsUp(alertEntry)) {
                Log.d(TAG, "maybeShowHeadsUp: fallback to notification list, key="
                        + alertEntry.getKey());
                return false;
            }
            showHeadsUp(mPreprocessingManager.optimizeForDriving(alertEntry),
                    rankingMap);
            return true;
        }
        return false;
    }
```

生效顺序共六道：LostMode 复查（183-188）→ shouldFilter 复查（189-206）→ shouldShowHeadsUp 资格判定（207-209，见 1.6）→ 三标志（210-218）→ 置换决策 prepareIncomingHeadsUp（219-222，见 1.7）→ 显示 showHeadsUp（224，先经 `optimizeForDriving` 做驾驶优化裁剪）。

shouldFilter 复查段有一个容易看漏的细节：如果这条通知是**已在飞的横幅的更新**、且更新后落进了过滤集（比如应用把进行中的普通通知改成了前台服务通知），CHUNM 会主动 `dismissHun` 把它从横幅上撤下来（200-205 行）——过滤规则对存量横幅有追溯力。

三标志与 AOSP 同款语义：`containsKeyFlag`（不是活跃通知，首次弹出）、`canUpdateFlag`（是活跃横幅且仍在 mDuration 时间窗内，338-343 行：`System.currentTimeMillis() - postTime < mDuration`，更新视为再弹）、`alertAgainFlag`（未设 `FLAG_ONLY_ALERT_ONCE`，316-318 行）。三者任一成立才继续。

`optimizeForDriving`（PPM 295-330 行）沿用 AOSP 思路：驾驶状态下裁剪大文本（BIG_TEXT/BIG_PICTURE/INBOX 降级），但 `CATEGORY_MESSAGE` 通知豁免不裁（311-313 行）。

### 1.6 shouldShowHeadsUp：注释掉的门槛与三通道准入

这是全文档审计的核心证据区。CHUNM 830-853 行的 javadoc 仍是 AOSP 原文的四条准入：

```java
     * <p> A notification will be shown as a heads-up if:
     * <ul>
     * <li> Importance >= HIGH
     * <li> it comes from an app signed with the platform key.
     * <li> it comes from a privileged system app.
     * <li> is a car compatible notification.
     * {@link com.android.car.assist.client.CarAssistUtils#isCarCompatibleMessagingNotification}
     * <li> Notification category is one of CATEGORY_CALL or CATEGORY_NAVIGATION
     * </ul>
```

而方法体（854-930 行）实际执行的是另一套。先看被整段注释掉的 importance 门槛（890-899 行，逐字引文）：

```java
        // Do not show if importance < HIGH
//        NotificationListenerService.Ranking ranking = getRanking();
//        if (rankingMap.getRanking(alertEntry.getKey(), ranking)) {
//            if (ranking.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
//                if (DEBUG) {
//                    Log.d(TAG, "Unable to show as HUN: importance is not sufficient");
//                }
//                return false;
//            }
//        }
```

javadoc 与真实方法体已有四处脱节：importance 门槛被注释；car-compatible（CarAssistUtils）检查整体移除；CALL/NAVIGATION 白名单换成了 MESSAGE/NAVIGATION 加业务等级通道（且 NAVIGATION 实际死在入口，见 F1）；新增的等级通道 javadoc 只字未提。文档 A045"横幅当前主要看 category"因此只对了半句——真实准入是三通道（F5）。

方法体的实际序列：

**第一段，基础过滤五条件**（856-889 行）。委托给 `Decider.passesBaseFilters`（Decider 10-33 行，逐字）：

```java
    static boolean passesBaseFilters(boolean isMediaLikeNotification,
            boolean isGroupSummary,
            boolean isNavigationHeadsupEnabled,
            boolean suppressAlertingDueToGrouping,
            boolean isMessageNotificationMuted,
            String category) {
        if (isMediaLikeNotification) {
            return false;
        }
        if (isGroupSummary) {
            return false;
        }
        if (!isNavigationHeadsupEnabled
                && Notification.CATEGORY_NAVIGATION.equals(category)) {
            return false;
        }
        if (suppressAlertingDueToGrouping) {
            return false;
        }
        if (isMessageNotificationMuted) {
            return false;
        }
        return true;
    }
```

五条件即文档第 4 节列出的：媒体类不弹（宽口径 `isMediaLikeNotification`，第二重媒体拦截）、分组摘要不弹、导航在 OEM 未开启 `mEnableNavigationHeadsup` 时不弹（死代码——能走到这里的导航通知不存在，见 1.3）、分组抑制不弹（AOSP `suppressAlertingDueToGrouping()` 原样沿用）、被静音的消息通知不弹（静音规则真实存在于代码，但当前不可达，见 F9）。

**第二段，特权直通**（899-907 行）：`Utils.isSystemPrivilegedOrPlatformKey` 命中即 return true，不再看 category 和等级。实现在 Utils 76-77 与 209-219 行：平台签名（`isSignedWithPlatformKey`）或系统特权应用（`isSystemApp` 加特权判定）。这是 yadea 自家应用（BTPhone、Setting 等）弹横幅的主通道。

**第三段，category/等级双通道**（918-930 行，逐字）：

```java
        NotificationLevelType recognizedLevel = NotificationLevelType.UNKNOWN;
        if (mLevelClassifier != null) {
            recognizedLevel = mLevelClassifier.resolveRecognizedLevel(alertEntry);
        }
        Log.d(TAG, "HUN eligibility check: category=" + notification.category
                + ", recognizedLevel=" + recognizedLevel
                + ", key=" + alertEntry.getKey());

        // 允许明确标记了业务等级的通知进入 HUN，避免第三方通知受限于 category 权限。
        return HeadsUpNotificationDecider.isHeadsUpEligible(
                notification.category,
                recognizedLevel);
```

Decider 45-51 行（逐字）：

```java
    static boolean isHeadsUpEligible(String category, NotificationLevelType levelType) {
        return isCategoryEligible(category) || isLevelEligible(levelType);
    }

    private static boolean isLevelEligible(NotificationLevelType levelType) {
        return levelType != null && levelType != NotificationLevelType.UNKNOWN;
    }
```

其中 `isCategoryEligible`（35-38 行）只认 `CATEGORY_MESSAGE` 与 `CATEGORY_NAVIGATION`。注意两处设计细节：其一，这里刻意用**不兜底的** `resolveRecognizedLevel` 而非兜底成 L1 的 `resolveLevel`——Clf 58-63 行的 javadoc 写明动机："HUN 准入需要区分'明确标注为 L1'与'完全没识别到等级但被兜底成 L1'，否则会把所有普通通知都提升为横幅"。也就是说：一个什么等级信息都不带的普通通知，recognizedLevel 是 UNKNOWN，走不了等级通道，只能靠 category 或特权；而一个明确传了 `notification_level=l1` 的第三方通知，反而拿到了横幅资格。其二，等级通道用的 recognizedLevel **不经过 L0 降级保护**——第三方伪装 L0 在这里畅通无阻（F10，降级只作用于 1.7 的路由与列表侧）。

对第三方应用，准入真值表（假设非特权、无 LostMode、过了基础过滤）：

| category | extras 等级 | 结果 |
|---|---|---|
| MESSAGE | 任意 | 弹 |
| NAVIGATION | 任意 | 到不了这里（入口已丢弃） |
| CALL | 无 | 不弹（且列表也剔除） |
| CALL | L0/LX/L1 | 弹（等级通道） |
| 其它/无 | L0/LX/L1 | 弹（等级通道） |
| 其它/无 | 无 | 不弹 |

### 1.7 置换决策与双屏路由

yadea 在"显示"之前多了一段 AOSP 没有的置换决策。CHUNM 392-420 行（逐字）：

```java
    private boolean prepareIncomingHeadsUp(AlertEntry alertEntry) {
        HeadsUpEntry currentDisplayedHeadsUp = getLatestActiveHeadsUpEntry();
        NotificationLevelType currentLevel = resolveEffectiveLevel(currentDisplayedHeadsUp);
        NotificationLevelType incomingLevel = resolveEffectiveLevel(alertEntry);
        boolean isSameNotification = currentDisplayedHeadsUp != null
                && Objects.equals(currentDisplayedHeadsUp.getKey(), alertEntry.getKey());
        HeadsUpReplacementDecider.Decision decision = HeadsUpReplacementDecider.decide(
                currentLevel, incomingLevel, isSameNotification);
        ...
        if (decision == HeadsUpReplacementDecider.Decision.FALLBACK_TO_LIST) {
            return false;
        }

        // 同一条通知更新时保留当前 HUN，但如果历史上已经叠了其他卡片，
        // 这里仍然要先清理其他旧 HUN，确保屏幕上只剩当前这一张。
        if (decision == HeadsUpReplacementDecider.Decision.REPLACE_CURRENT
                || hasOtherActiveHeadsUpNotifications(alertEntry.getKey())) {
            releaseAllHeadsUpToShadeExcept(alertEntry.getKey());
        }
        return true;
    }
```

规则本体在 ReplDec 21-35 行（逐字）：

```java
    static Decision decide(NotificationLevelType currentLevel,
            NotificationLevelType incomingLevel,
            boolean isSameNotification) {
        if (isSameNotification || currentLevel == null) {
            return Decision.SHOW_NEW;
        }

        if (isTopAlertLevel(currentLevel)) {
            return isTopAlertLevel(incomingLevel)
                    ? Decision.REPLACE_CURRENT
                    : Decision.FALLBACK_TO_LIST;
        }

        return Decision.REPLACE_CURRENT;
    }
```

翻成白话：屏上没有横幅（或来的就是同一条）→ 直接显示；在飞的是 L0/LX 顶级告警 → 来的也是顶级就替换，否则**回落到通知列表**（低级通知不许打断顶级告警，也不叠加）；在飞的是普通等级 → 无条件替换。注意这里用的是 `resolveEffectiveLevel`（经过 L0 降级的有效等级，见 2.3）——置换对抗里第三方伪装 L0 会被打回 L1 再比。FALLBACK_TO_LIST 返回 false 后，maybeShowHeadsUp 219-222 行记日志回落，通知改走列表出口。这与手机 SystemUI 的"compareTo 择优、胜者上屏"（解剖篇 §9.5）和 AOSP Car 库的 HunImportance Z 序链表（解剖篇 §18.1）是三种不同的多横幅仲裁方案。

路由决策在 CHUNM 374-386 行（逐字）：

```java
    private CarHeadsUpNotificationContainer resolveContainer(AlertEntry alertEntry) {
        if (mLevelClassifier != null && mSecondaryContainer != null) {
            // 横幅和通知中心共用 effective level，避免同一条通知在列表与 HUN 上分级不一致。
            NotificationLevelType level = NotificationEntryLevelResolver.resolveEffectiveLevel(
                    mContext, alertEntry, mLevelClassifier);
            if (HeadsUpDisplayDecider.shouldUseSecondaryDisplay(level)) {
                Log.d(TAG, "Routing HUN to secondary display, effectiveLevel=" + level
                        + ", key=" + alertEntry.getKey());
                return mSecondaryContainer;
            }
        }
        return mDefaultContainer;
    }
```

DispDec 13-15 行：L0 或 LX → 副屏（displayId=2 的 TYPE_SYSTEM_DIALOG 容器），其余 → 默认屏。跨屏迁移也有处理：同一条通知等级变化导致目标容器变化时（`shouldMoveExistingView`，DispDec 17-24），CHUNM 505-510 行释放旧视图、从旧容器摘除、向新容器重新 displayNotification。

### 1.8 显示、模板与视图绑定

showHeadsUp（CHUNM 463 行起）的视图创建段（514-522 行，逐字）：

```java
        if (currentNotification.getNotificationView() == null) {
            shouldBindTouchListener = true;
            currentNotification.setNotificationView(mInflater.inflate(
                    notificationTypeItem.getHeadsUpTemplate(),targetContainer.getHunWindow(),false
            ));
            currentNotification.setViewHolder(
                    notificationTypeItem.getViewHolder(currentNotification.getNotificationView(),
                            mClickHandlerFactory));
            targetContainer.displayNotification(currentNotification.getNotificationView());
        } else {
            targetContainer.prepareForNotificationDisplay("showHeadsUp:reuseExistingView");
        }
```

模板选择在 `CarNotificationTypeItem.java` 40-66 行的枚举表里：每个类目一对布局（headsup 模板 + 列表模板）加一个 ViewHolder 工厂。category → 模板项的映射在 Utils 122-152 行（逐字，含 INBOX 判定与兜底）：

```java
    public static CarNotificationTypeItem getNotificationViewType(AlertEntry alertEntry) {
        String category = alertEntry.getNotification().category;
        ...
        if (category != null) {
            switch (category) {
                case Notification.CATEGORY_CAR_EMERGENCY:
                    return CarNotificationTypeItem.EMERGENCY;
                case Notification.CATEGORY_NAVIGATION:
                    return CarNotificationTypeItem.NAVIGATION;
                case Notification.CATEGORY_CALL:
                    return CarNotificationTypeItem.CALL;
                case Notification.CATEGORY_CAR_WARNING:
                    return CarNotificationTypeItem.WARNING;
                case Notification.CATEGORY_CAR_INFORMATION:
                    return CarNotificationTypeItem.INFORMATION;
                case Notification.CATEGORY_MESSAGE:
                    return CarNotificationTypeItem.MESSAGE;
                default:
                    break;
            }
        }
        Bundle extras = alertEntry.getNotification().extras;
        if (extras.containsKey(Notification.EXTRA_BIG_TEXT)
                && extras.containsKey(Notification.EXTRA_SUMMARY_TEXT)) {
            return CarNotificationTypeItem.INBOX;
        }
        // progress, media, big text, big picture, and basic templates
        return CarNotificationTypeItem.BASIC;
    }
```

文档 §3.2 的七行映射表与这段 switch 逐行吻合（A022-A027 全部属实），但有两个可达性注脚：NAVIGATION 模板因入口死链永远渲染不到（A023 属实但死代码）；CALL 模板只有特权或带等级的通话通知能用到（1.6 真值表）。另外"其它 → Basic"不是无条件的：extras 同时带 `EXTRA_BIG_TEXT` 与 `EXTRA_SUMMARY_TEXT` 时先判 INBOX（A028/A029 部分属实的依据）。

### 1.9 时钟：5000/2000 与 AOSP 的 8000/2000

RES/values/integers.xml 五个时长常量与 AOSP Car 库同名文件逐项对照：

| 常量（同名） | yadea | AOSP Car 库 | 用途 |
|---|---|---|---|
| heads_up_notification_minimum_time | 2000 | 2000 | 最短显示时间（CHUNM 131 行读入） |
| headsup_notification_duration_ms | **5000** | **8000** | 自动收回延时 + canUpdate 更新窗 |
| headsup_total_enter_duration_ms | **500** | 233 | 入场动画总时长 |
| headsup_alpha_enter_duration_ms | **500** | 83 | 入场透明度时长 |
| headsup_exit_duration_ms | **333** | 150 | 退场动画时长 |

主时长在 CHUNM 127 行读入 `mDuration`，两处消费：自动收回（718-725 行，逐字）与更新窗（338-343 行，见 1.5）。自动收回对带 fullScreenIntent 的通知豁免：

```java
    private void setAutoDismissViews(HeadsUpEntry currentNotification, AlertEntry alertEntry) {
        // Should not auto dismiss if HUN has a full screen Intent.
        if (hasFullScreenIntent(alertEntry)) {
            return;
        }
        currentNotification.getHandler().removeCallbacksAndMessages(null);
        currentNotification.getHandler().postDelayed(() -> dismissHun(alertEntry), mDuration);
    }
```

FSI 豁免与 AOSP Car 库同款（解剖篇 §17.3）；对照手机 SystemUI 的 5000+700 补偿与 sticky 四态（解剖篇 §9），yadea 没有 sticky 概念，到点即收。动画时长 yadea 全线放慢（入场 233→500、退场 150→333），配合 5000 的缩短总时长，节奏是"更快收、更缓动"。

### 1.10 声音：Beeper 职责与 AOSP 相反

CHUNM 686-705 行（逐字）：

```java
    private void playSound(AlertEntry alertEntry,
                           NotificationListenerService.RankingMap rankingMap) {
        if (NotificationUtils.isMediaLikeNotification(alertEntry.getNotification())) {
            if (DEBUG) {
                Log.d(TAG, "Skip heads-up sound for media notification: " + alertEntry.getKey());
            }
            return;
        }
        NotificationListenerService.Ranking ranking = getRanking();
        if (rankingMap.getRanking(alertEntry.getKey(), ranking)) {
            NotificationChannel notificationChannel = ranking.getChannel();
            // If sound is not set on the notification channel and default is not chosen it
            // can be null.
            if (notificationChannel.getSound() != null) {
                // make the sound
                mBeeper.beep(alertEntry.getStatusBarNotification().getPackageName(),
                        notificationChannel.getSound());
            }
        }
    }
```

与 AOSP Car 库一致：声音由车机端 Beeper 按 channel 的 sound 播放（职责倒置——平台服务端 automotive 场景不播，见解剖篇 §17.6）；yadea 追加的定制是媒体类通知静音（688-693 行），媒体通知的声音归音频链自己管。

### 1.11 点击：主体与 Action 两条路

主体点击绑定在 `CarNotificationBaseViewHolder.java` 161-175 行（TEMPLATE 下）：`bind` 时给卡片（或分组内层视图）挂 `mClickHandlerFactory.getClickHandler(alertEntry)`。ClickFac 89-126 行（节选，逐字）：

```java
    public View.OnClickListener getClickHandler(AlertEntry alertEntry) {
        return v -> {
            Notification notification = alertEntry.getNotification();
            final PendingIntent intent = notification.contentIntent != null
                    ? notification.contentIntent
                    : notification.fullScreenIntent;
            if (intent == null) {
                return;
            }
            ...
                // 通知点击之后主动收起负一屏
                ActorController.Companion.getInstance().hideShortcutWindow();
                result = intent.sendAndReturnResult(/* context= */ null, /* code= */ 0, ...);
            ...
                mBarService.onNotificationClick(alertEntry.getKey(),
                        notificationVisibility);
                if (shouldAutoCancel(alertEntry)) {
                    clearNotification(alertEntry);
                }
            ...
        };
    }
```

要点三条：contentIntent 缺位时回落 fullScreenIntent，两者皆无则点击静默无反应（文档 A036/A125 的实据，:95-97 的 `return`）；点击后收起负一屏（yadea 定制的 ActorController 联动）；`shouldAutoCancel` 命中则代应用清除通知（auto-cancel 语义由监听端兜底执行）。

Action 点击走 ClickFac 135-165 行 `getActionClickHandler(alertEntry, index)`：同样先收负一屏，然后 `sendPendingIntent(action.actionIntent)`，成功后向 `mBarService.onNotificationActionClick` 上报。绑定处在 `CarNotificationActionsView.java` 198 行；157 行 `Math.min(actions.length, MAX_NUM_ACTIONS)` 执行 3 个上限（MAX_NUM_ACTIONS = 3，57 行）——文档 A034/A037 的实据。

### 1.12 收回：三路出口

横幅的消亡有三条路：

- **超时自动收回**：1.9 的 `dismissHun` 延时任务（CHUNM 724 行触发，737 行实现）。
- **本体移除**：Listener 192-212 行 `onNotificationRemoved` 联动——通知从系统消失时若对应横幅在飞则撤下；媒体通知的移除另有按 key 兜底清理（199-204 行，中文注释："应用退出后删除快照可能丢失媒体标识，当前媒体通知必须按 key 兜底清理"）。
- **面板展开全收**：1.4 的 `releaseAllHeadsUpToShade`（CHUNM 277-278 行，内部走 285-298 行的 Except 变体），横幅让位给面板。

滑动清除由模板层的 dismiss 按钮与触摸处理承接；`shouldDismissOnSwipe`（CHUNM 707-711 行）保留了 AOSP 的例外：带 fullScreenIntent 的 ongoing 通话类横幅不可滑掉。另有旋钮/焦点类交互复位逻辑沿自 AOSP Car 库（解剖篇 §17.3），yadea 车机无旋钮场景，此路基本闲置。

### 1.13 幽灵：文档引用了一个不存在的类

文档 §3.3 给出的点击处理代码路径指向 dropdownbar/notification/widget 下的 BannerNotificationView.java。全工程 find 的结果：**该文件不存在**，dropdownbar 下没有 notification/widget 目录；与之配套的 item_banner_notification.xml 布局文件（RES/layout/ 下）存在，但对它的引用（R.layout.item_banner_notification）在全部 Java/Kotlin 源码中**零命中**——死布局。真实的点击处理在 ClickFac 与各 ViewHolder（1.11）。这是文档唯一的硬性引用错误（A032 证伪，F3），推测是早期原型（"banner"命名的下拉横幅方案）被现方案替换后文档未同步。

### 1.14 链①四问收束

| 问题 | 回答 |
|---|---|
| 横幅准入规则是什么 | 六道顺序闸：LostMode → shouldFilter 复查 → 基础过滤五条件 → 特权直通 / category(MESSAGE) / 业务等级三通道 → 三标志 → 置换决策 |
| 谁设置规则 | yadea SystemUI 团队改装 AOSP Car 库：新增 LostMode/等级/置换/双屏，注释 importance，收紧过滤 |
| 谁执行 | Listener（入口丢弃与分流）→ CHUNM（判定与显示）→ 双容器（默认屏/副屏窗口） |
| 不达标的后果 | shouldFilter 命中：横幅列表全无；资格判定不过或置换 FALLBACK：只进列表；面板可见：直接进列表 |

## 2. 链②：业务等级 L0/LX/L1 的识别、降级与九个消费面

业务等级是 yadea 在 AOSP 之上自建的一套平行分级体系，与平台的 channel importance 并存但互不换算。文档第 5 节对它的描述整体准确（A047-A068 十五条断言十四条属实），本节按"枚举 → 识别 → 降级 → 消费"走读，并精确圈定 L0 降级保护的生效面——这是文档没有说清的部分。

### 2.1 四级枚举与两个解析口

`NotificationLevelType.java` 全文 11 行：

```java
/**
 * 通知业务等级。
 */
public enum NotificationLevelType {
    L0,
    LX,
    L1,
    UNKNOWN
}
```

等级解析有**两个口**，这是理解整套体系的钥匙。Clf 38-63 行（逐字）：

```java
    public NotificationLevelType resolveLevel(AlertEntry entry) {
        NotificationLevelType recognizedLevel = resolveRecognizedLevel(entry);
        if (recognizedLevel != NotificationLevelType.UNKNOWN) {
            return recognizedLevel;
        }
        ...
        // Default unknown notifications to app notifications to avoid over-promoting them.
        return NotificationLevelType.L1;
    }

    /**
     * 返回真正从通知内容中识别出的等级，不做兜底。
     *
     * <p>HUN 准入需要区分“明确标注为 L1”与“完全没识别到等级但被兜底成 L1”，
     * 否则会把所有普通通知都提升为横幅。
     */
    public NotificationLevelType resolveRecognizedLevel(AlertEntry entry) {
```

`resolveLevel` 带 L1 兜底，供列表分区、分组等"总得有个归属"的场合使用；`resolveRecognizedLevel` 不兜底，专供横幅准入——UNKNOWN（没标等级）与 L1（明确标了 L1）在准入语义上是两个世界：前者不进横幅，后者进。

### 2.2 识别流水线：extras 六键 → category → channelId → 包名

`resolveRecognizedLevel`（Clf 64-88 行）的四级瀑布：

```java
        NotificationLevelType levelFromExtras = resolveFromExtras(extras);
        if (levelFromExtras != NotificationLevelType.UNKNOWN) {
            return levelFromExtras;
        }

        NotificationLevelType levelFromCategory = resolveFromCategory(notification.category);
        if (levelFromCategory != NotificationLevelType.UNKNOWN) {
            return levelFromCategory;
        }

        NotificationLevelType levelFromChannel = resolveFromText(notification.getChannelId());
        if (levelFromChannel != NotificationLevelType.UNKNOWN) {
            return levelFromChannel;
        }

        return resolveFromText(entry.getStatusBarNotification().getPackageName());
```

**第一级 extras**（72-75 行调用，90-105 行实现）：按序探测六个键（Clf 21-27 行，逐字）：

```java
    private static final List<String> LEVEL_EXTRA_KEYS = Arrays.asList(
            "notification_level",
            "notificationLevel",
            "level_type",
            "levelType",
            "biz_level",
            "bizLevel");
```

六键与文档 §5.1 完全一致（A051-A056 全部属实）。值的解析在 `parseLevel`（139-174 行）：数字 0→L0、1→L1、10→LX；字符串 "0"/"l0"→L0、"x"/"lx"→LX、"1"/"l1"/"app"/"application"→L1（大小写不敏感，158 行统一小写化）。文档 §5.1 的支持值表（A057-A059）与实现逐项吻合。

**第二级 category**（107-119 行，逐字）：

```java
        switch (category) {
            case Notification.CATEGORY_CAR_EMERGENCY:
            case Notification.CATEGORY_CAR_WARNING:
                return NotificationLevelType.L0;
            default:
                return NotificationLevelType.UNKNOWN;
        }
```

只有两个车规类目直接定级 L0（A064/A065 属实），其余 category 在这一级不产生等级。

**第三、四级 channelId 与包名**共用 `resolveFromText`（121-137 行，逐字）：

```java
        String normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, L0_HINTS)) {
            return NotificationLevelType.L0;
        }
        if (containsAny(normalized, LX_HINTS)) {
            return NotificationLevelType.LX;
        }
        if (normalized.contains("l1")) {
            return NotificationLevelType.L1;
        }
        return NotificationLevelType.UNKNOWN;
```

提示词表在 29-33 行：`L0_HINTS` = l0/fault/warning/alarm/error，`LX_HINTS` = lx/opponent/peer/duishoujian（"对手件"拼音）。匹配是小写化后的**任意位置子串**——这就是 F14：channelId 或包名里偶然带上这些子串（比如叫 "warning_light_channel" 的仪表渠道、包名含 "alarm" 的闹钟应用），等级归属就被改写。文档 A061/A062 把这两级写成中性的"兜底顺序"没错，但 A115"其它 channelId 无强制要求"忽略了 channel 命名本身就是等级输入。

兜底出口：四级全空 → `resolveRecognizedLevel` 返回 UNKNOWN；若走 `resolveLevel` 则再兜成 L1（A063 属实，注意双口差异）。

### 2.3 L0 降级保护：三豁免

外部应用可以随意往 extras 里塞 "l0"，防线在降级层。`NotificationEntryLevelResolver.java` 14-23 行（逐字）：

```java
    static NotificationLevelRules.EntryLevel buildEntryLevel(NotificationLevelType rawLevel,
            boolean isCriticalCarCategory,
            boolean isSystemApp,
            boolean isPlatformSigned) {
        boolean downgradeNonSystemL0 = rawLevel == NotificationLevelType.L0
                && !isCriticalCarCategory
                && !isSystemApp
                && !isPlatformSigned;
        return new NotificationLevelRules.EntryLevel(rawLevel, downgradeNonSystemL0);
    }
```

三个豁免条件由 25-41 行的重载装配：`isCriticalCarCategory` = category 是 `CATEGORY_CAR_EMERGENCY` 或 `CATEGORY_CAR_WARNING`（36-37 行）；`isSystemApp`（38 行）与 `isPlatformSigned`（39-40 行）走 Utils 98-115 行的包信息检查。降级的执行在 `NotificationLevelRules.java` 42-50 行（逐字）：

```java
    static NotificationLevelType resolveEffectiveLevel(EntryLevel entryLevel) {
        if (entryLevel == null || entryLevel.mRawLevel == null) {
            return NotificationLevelType.UNKNOWN;
        }
        if (entryLevel.mRawLevel == NotificationLevelType.L0 && entryLevel.mDowngradeNonSystemL0) {
            return NotificationLevelType.L1;
        }
        return entryLevel.mRawLevel;
    }
```

即：rawLevel 为 L0 且三豁免全不沾 → effectiveLevel 打成 L1。调用侧在 PPM 937-951 行 `buildEntryLevel`（列表管线用），带降级调试日志（946-949 行 "downgrade non-system L0 candidate to L1"）。文档 §5.3"非系统应用/非平台签名应用，很多 L0 会被降级回 L1"（A067）与实现一致，"很多"一词也准确——带车规 category 的 L0 在豁免之列。

### 2.4 降级保护的生效面：横幅准入不在其列

把 2.2 的双口设计与 2.3 的降级叠起来看，第三方伪装 L0 的实际后果需要精确陈述（F10）：

| 消费场合 | 用的等级 | 伪装 L0 的结果 |
|---|---|---|
| 横幅准入（CHUNM 918-930） | recognizedLevel（**不降级**） | L0 ≠ UNKNOWN → **照样获得横幅资格** |
| 双屏路由（CHUNM 374-386） | effectiveLevel（降级后） | 按 L1 走 → 主屏，不进副屏顶级告警屏 |
| 置换决策（CHUNM 392-406） | effectiveLevel（降级后） | 按 L1 参与 → 会被真 L0/LX 挤掉 |
| 列表分区/折叠/可清除性 | effectiveLevel（降级后） | 按 L1 处理 → 普通应用区、可清除 |

所以文档 §5.3 的"保护"保护的是**顶级告警的特权面**（副屏、抗置换、不可清除、置顶分区），不是横幅面：一个传了 `notification_level=l0` 的第三方通知，仍会在主屏弹出横幅，只是弹完以后处处按 L1 对待。想完全拦住伪装 L0 的横幅，得在准入处也改用 effectiveLevel——当前代码没有这么做，且 1.6 的注释表明这是有意设计（准入只区分"标没标等级"）。

### 2.5 等级的九个消费面

| # | 消费面 | 位置 | 用哪个等级 | 效果 |
|---|---|---|---|---|
| 1 | 横幅准入 | CHUNM:918-930 | recognized | ≠UNKNOWN 即可弹 |
| 2 | 双屏路由 | CHUNM:374-386 | effective | L0/LX → 副屏容器 |
| 3 | 置换决策 | CHUNM:392-420 + ReplDec:21-35 | effective | 顶级在飞时低级回落列表 |
| 4 | 列表分区 | PPM:689-693 + Rules:52-59 | effective | TOP_ALERT / APP_NOTIFICATION 两区 |
| 5 | 顶区折叠 | PPM:727-749 + TopAlertFoldRules:9-16 | effective | 顶级告警 >3 条折叠成一组，折叠组等级取最高 |
| 6 | 组级等级 | Rules:18-34 | effective | 组等级取成员最高，L0 短路 |
| 7 | 可清除性 | Rules:36-40 + BaseViewHolder:382 + NotificationGroup:315 | effective | 仅 UNKNOWN/L1 可由用户清除，L0/LX 无清除钮 |
| 8 | 状态栏图标 | Listener:470-490 | 组等级集合 | levels 交给 StatusBarNotificationIconStateResolver 决定图标状态，无等级则 NORMAL |
| 9 | L1 按包重组 | PPM:806-855 | effective | APP 区通知按包名重组为分组 |

其中 5 的规则全文在 `TopAlertFoldRules.java`（49 行小文件）：MAX_VISIBLE_TOP_ALERTS = 3（9 行），shouldFold 判 >3（14-16 行），resolveFoldedLevel 取优先级最高者、L0 短路（18-35 行）。优先级序在 Rules 61-76 行：L0=3 > LX=2 > L1=1 > UNKNOWN=0。

### 2.6 分区重组主流程

PPM 666-711 行 `regroupByBusinessLevel`（process 管线第 160-168 行的第五段，AOSP 四段流水线之后追加）把 AOSP 分组结果整体拆散重排：展平所有组成员 → 按 `resolveDisplaySection` 分入 topAlerts / appNotifications 两桶（689-693 行）→ 两桶各自按业务时间戳降序（697-698 行）→ `buildTopLevelNotificationItems` 重建列表（700 行）。业务时间戳来自 extras 或 postTime（`getBusinessSortTime`，953-955 行起）。计数日志（703-709 行）输出 L0/LX/APP 三档数量，是排查等级问题的第一现场。

### 2.7 链②四问收束

| 问题 | 回答 |
|---|---|
| 等级规则是什么 | extras 六键 → category 两值 → channelId 提示词 → 包名提示词，四级瀑布；解析分兜底/不兜底双口 |
| 谁设置 | 应用侧通过 extras/category/channelId/包名间接设置；降级位由 Resolver 按三豁免装配 |
| 谁执行 | Clf 识别，Resolver+Rules 折算 effective，PPM/CHUNM/模板层九个消费面各取所需 |
| 后果 | 等级决定横幅资格（recognized）、屏幕路由/置换/分区/折叠/清除权（effective）；伪装 L0 弹得了横幅但拿不到特权面 |

## 3. 链③：媒体通知与 CarPlay DOWN/UP 触摸转发

媒体链是三条链里 yadea 定制最重的：AOSP 手机侧的媒体通知走 MediaDataManager 独立管线（解剖篇 §14），AOSP Car 库干脆把媒体从面板过滤掉交给专门的媒体应用；yadea 则让媒体通知**回归通知监听链**，在入口分流进底部导航栏的单槽媒体卡片，并在卡片按钮上叠了一套按 action title 匹配的 DOWN/UP 触摸转发协议——文档 §7.4 称之为 CarPlay 接入要求，实际是对所有应用开放的通用机制。

### 3.1 宽口径媒体识别：四条件

Utils 156-171 行（逐字）：

```java
    public static boolean isMediaLikeNotification(Notification notification) {
        if (notification == null) {
            return false;
        }
        if (notification.isMediaNotification()) {
            return true;
        }

        Bundle extras = notification.extras;
        if (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return true;
        }

        return "android.mediaSession".equals(notification.category)
                || "media_playback_channel".equals(notification.getChannelId());
    }
```

四条件任一命中即媒体（A075-A079 全部属实）：① AOSP 严口径 `isMediaNotification()`（MediaStyle 且带 session token，见 `frameworks/base/core/java/android/app/Notification.java` :7243 起）；② extras 带 `EXTRA_MEDIA_SESSION`；③ category 为 "android.mediaSession"；④ channelId 为 "media_playback_channel"。条件④是全工程唯一被代码硬编码消费的 channel 名（F4）——方法头注释交代了动机："Some migrated media apps do not satisfy isMediaNotification(), but still expose a media session/category/channel that should be handled as media"，即为迁移不彻底的媒体应用留的兼容后门。文档 §8"media_playback_channel 有额外兼容逻辑"（A114）属实。

### 3.2 单槽媒体入口：mCurrentMediaEntry

Listener 只有一个媒体槽位（字段声明略），写入点即 1.2 的入口分流（149-159 行）：非分组摘要的媒体通知直接覆盖 `mCurrentMediaEntry` 并回调 `mChangeListener.onShow`。回调接口在 Listener 404-409 行（逐字）：

```java
    public interface IMediaNotificationListener {
        void onShow(AlertEntry entry);

        void onRemove(AlertEntry entry);

    }
```

注册即补发（411-419 行，逐字）——NavBar 重建时不用等下一条媒体通知：

```java
    public void setNotificationListener(IMediaNotificationListener listener) {
        this.mChangeListener = listener;
        // NavBar 因其他原因重建时，立即恢复当前媒体信息，避免等待下一次通知期间显示暂无播放。
        if (listener != null && mCurrentMediaEntry != null) {
            Log.d(TAG, "setNotificationListener: restore current media key="
                    + mCurrentMediaEntry.getKey());
            listener.onShow(mCurrentMediaEntry);
        }
    }
```

移除侧（192-209 行）双保险：当前媒体的 key 精确匹配清理，加上媒体类通知的宽口径兜底（中文注释："应用退出后删除快照可能丢失媒体标识，当前媒体通知必须按 key 兜底清理"），清理后回调 `onRemove`。

单槽语义的代价：两个媒体应用同时播（如 BTMusic 与 CarPlay），后到者覆盖先到者，卡片只显示最新一条；没有媒体列表、没有优先级仲裁。这与手机 SystemUI 的多媒体条目队列（MediaDataManager 的 filteredMediaData 列表）是两个世界。

### 3.3 NavBar 消费：信息面

NavBar 1219-1231 行实现 `onShow`（逐字）：

```java
    @Override
    public void onShow(AlertEntry entry) {
        Log.d(TAG, "onShow: AlertEntry:" + entry.toString());
        Runnable updateMedia = () -> {
            bindMediaActions(entry);
            updateMediaInfo(entry);
        };
        // 重建后的缓存恢复发生在主线程，直接刷新可避免默认状态被绘制一帧。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateMedia.run();
        } else {
            handler.post(updateMedia);
        }
    }
```

信息面 `updateMediaInfo`（982-1002 行，逐字节选）：

```java
        musicPending = notification.contentIntent;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
        int progress = extras == null ? 0 : extras.getInt(Notification.EXTRA_PROGRESS);
        musicPkg = entry.getStatusBarNotification().getPackageName();
        rememberMediaSource(musicPkg);
        ...
        musicTittle.setText(TextUtils.isEmpty(title) ? getString(R.string.have_not_player) : title);
        updateMediaAvatar(notification);
        mSeekBar.setProgress(progress);
```

标题（EXTRA_TITLE）、副标题（EXTRA_TEXT）、封面（largeIcon，`updateMediaAvatar` 1004 行起）、进度（EXTRA_PROGRESS → 进度条）四项全部直接读 extras。进度条一处细节：yadea 只读 `EXTRA_PROGRESS`，**忽略 `EXTRA_PROGRESS_MAX`**——应用侧必须按百分比语义传 0-100（文档 A081/A117 的"0-100"因此是消费端约定而非平台约定；AOSP 侧 `setProgress(100, progress, false)` 恰好写 max=100，见 `frameworks/base/core/java/android/app/Notification.java` :4500，两者对齐）。空标题/空文本回落"暂无播放"文案。进度条自身不可拖（195 行 `mSeekBar.setOnTouchListener((v, event) -> true)` 吞掉全部触摸）。

卡片点击（llMusicCard，276 行）走 `musicPending`（即媒体通知的 contentIntent）。

### 3.4 控制面：title 识别、单槽位与位置兜底

`bindMediaActions`（876-906 行）先复位四个 DOWN/UP 槽与三个标准槽，然后两段处理（891-892 行）：`resolveActionIndices(actions)` 按 title 识别，`applyFallbackIndices(actions)` 位置兜底；最后按槽位有效性设置按钮可用态（902-904 行）：

```java
        setViewEnabled(mPreviousActionIndex >= 0, previousBtn);
        setViewEnabled(mPlayPauseActionIndex >= 0, playBtn);
        setViewEnabled(mNextActionIndex >= 0, next);
```

识别核心 `resolveActionIndices`（908-945 行，逐字节选）：

```java
            String title = action.title == null ? "" : action.title.toString();
            String normalizedTitle = title.toLowerCase(Locale.ROOT);
            ...
            // 先匹配 CarPlay touch actions（title 含 "down"/"up"）
            if (matchesPlayPause(normalizedTitle) && normalizedTitle.contains("down")) {
                mPlayPauseDownActionIndex = i;
                continue;
            }
            if (matchesPlayPause(normalizedTitle) && normalizedTitle.contains("up")) {
                mPlayPauseUpActionIndex = i;
                continue;
            }
            if (matchesNext(normalizedTitle) && normalizedTitle.contains("down")) {
                mNextDownActionIndex = i;
                continue;
            }
            if (matchesNext(normalizedTitle) && normalizedTitle.contains("up")) {
                mNextUpActionIndex = i;
                continue;
            }

            // 匹配标准媒体 Action
            if (mPreviousActionIndex < 0 && matchesPrevious(normalizedTitle)) {
                mPreviousActionIndex = i;
            } else if (mPlayPauseActionIndex < 0 && matchesPlayPause(normalizedTitle)) {
                mPlayPauseActionIndex = i;
            } else if (mNextActionIndex < 0 && matchesNext(normalizedTitle)) {
                mNextActionIndex = i;
            }
```

三个匹配器（948-956 行，逐字）：

```java
    private static boolean matchesPrevious(String title) {
        return title.contains("previous") || title.contains("prev") || title.contains("上一");
    }

    private static boolean matchesPlayPause(String title) {
        return title.contains("play") || title.contains("pause")
                || title.contains("播放") || title.contains("暂停");
    }

    private static boolean matchesNext(String title) {
        return title.contains("next") || title.contains("下一");
    }
```

全部是小写化后的任意位置 contains——文档 §7.3 的关键词表（A087/A088）属实且实现更宽（"prev" 也算上一首；"playlist" 会误中播放暂停）。DOWN/UP 识别（916-931 行）要求 title **同时**含按钮词与 "down"/"up" 子串，位置不限（F7）。标准槽先到先得（`mPreviousActionIndex < 0` 守卫），DOWN/UP 槽却是**后写覆盖**（无守卫，直接赋值）——这个不对称是 F6 的根源，3.6 详述。

位置兜底 `applyFallbackIndices`（958-968 行，逐字）：

```java
    private void applyFallbackIndices(Notification.Action[] actions) {
        if (mPreviousActionIndex < 0 && actions.length > 0) {
            mPreviousActionIndex = 0;
        }
        if (mPlayPauseActionIndex < 0 && actions.length > 1) {
            mPlayPauseActionIndex = 1;
        }
        if (mNextActionIndex < 0 && actions.length > 2) {
            mNextActionIndex = 2;
        }
    }
```

title 完全不可识别时按数组位置机械赋值。组合出 F8 的错位案例：应用按文档建议只传 2 个 action（Play/Pause、Next）但 title 用了关键词表外的写法（如 "Pause/Resume" 里的 Resume 不在表内、或纯图标 title）→ previous 槽拿走 actions[0]（按钮不可见，无害），playPause 槽拿走 actions[1]（**实际是 Next 的 intent**），next 槽因 `length > 2` 不成立保持 -1（按钮置灰）→ 用户点播放键，发出的是下一首。

`setViewEnabled`（1084-1087 行）只设 enabled 与 alpha（禁用态 0.3），**不改 visibility**——而 previousBtn 在布局里 `android:visibility="gone"`，全工程无任何恢复 VISIBLE 的代码，所以上一首按钮永不可见，兜底给它的索引纯属空转（F11 的一半；另一半是接线仍活：277 行 `previousBtn.setOnClickListener(this)` 照挂，识别与发送链完整）。

### 3.5 DOWN/UP 触摸转发：机制全貌

四个槽位字段（113-116 行，逐字）：

```java
    private int mPlayPauseDownActionIndex = -1;
    private int mPlayPauseUpActionIndex = -1;
    private int mNextDownActionIndex = -1;
    private int mNextUpActionIndex = -1;
```

触摸监听器（838-873 行，逐字节选）：

```java
    @SuppressLint("ClickableViewAccessibility")
    private final View.OnTouchListener mMediaButtonTouchListener = (v, event) -> {
        if (mDriveTouchLocked) {
            // VIR-1471：消费触摸且不派发媒体动作；仅 DOWN 尝试弹 Toast（展示期由 IC 门闩去重）
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                LogUtils.d(TAG, "Media touch blocked by drive lock, show toast, viewId=" + v.getId());
                showDriveTouchLockedToast();
            }
            return true;
        }
        int viewId = v.getId();
        boolean hasTouchActions = false;
        int downIndex = -1, upIndex = -1;

        if (viewId == R.id.pause_button) {
            downIndex = mPlayPauseDownActionIndex;
            upIndex = mPlayPauseUpActionIndex;
            hasTouchActions = downIndex >= 0 && upIndex >= 0;
        } else if (viewId == R.id.next_button) {
            downIndex = mNextDownActionIndex;
            upIndex = mNextUpActionIndex;
            hasTouchActions = downIndex >= 0 && upIndex >= 0;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (hasTouchActions) {
                sendPendingIntent(downIndex);
                return true;
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (hasTouchActions) {
                sendPendingIntent(upIndex);
                return true;
            }
        }
        return false;
    };
```

机制五要点：

1. **只绑两个按钮**：viewId 分支只认 pause_button 与 next_button，previous 无触摸通道（反正不可见）。
2. **成对必需**：`hasTouchActions` 要求 down/up 索引同时 ≥0，只有其一则整个触摸通道关闭，回落 click（A092 的 SystemUI 侧对应事实：SystemUI 保证要么成对发、要么全不发，不会发出半套按键）。
3. **DOWN 发 down 槽、UP 发 up 槽**（861-871 行），各自 `sendPendingIntent(index)`。
4. **无 DOWN/UP 时 return false**：触摸事件交还 click 监听器，走标准 action 的 `sendMediaAction`（A107 属实）。
5. **行车锁前置**（839-846 行）：`mDriveTouchLocked` 时消费触摸不派发任何动作，仅弹 Toast（VIR-1471 需求）——文档未提及的 yadea 安全闸。

两条发送通道的差别：触摸直发走 `sendPendingIntent`（819-830 行，静默，索引越界/intent 为空直接 return）；click 回落走 `sendMediaAction`（786-814 行，带日志，且**无媒体源时弹中央 Toast 提示**）：

```java
    private void sendMediaAction(int actionIndex) {
        if (mCurrentMediaEntry == null) {
            Log.w(TAG, "sendMediaAction: no active media notification");
            ToastUtils.showCenterToast(requireContext(), requireContext().getString(R.string.no_music_source_hint), ...);
            return;
        }
        Notification.Action[] actions = mCurrentMediaEntry.getNotification().actions;
        if (actions == null || actionIndex < 0 || actionIndex >= actions.length) {
            Log.w(TAG, "sendMediaAction: invalid action index " + actionIndex);
            return;
        }
        ...
        try {
            actionIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "sendMediaAction: action canceled", e);
        }
    }
```

PendingIntent 的构造与内容 SystemUI 一概不问——DOWN/UP 的 intent 里装 KeyEvent 还是广播 extras，由发送方自定（A102/A111 的边界：SystemUI 只负责在正确的时机 `send()`）。

### 3.6 单槽覆盖："Play 与 Pause 都提供"的真实后果

文档 §7.4.2 步骤 3 要求同时提供 Play Down/Up 与 Pause Down/Up（"Play/Pause 按钮的 title 会随播放状态切换"，A101）。实现层面这个要求的兑现方式与文档预期不同（F6）：

- 识别发生在 `bindMediaActions` 时刻（通知每次更新重绑），对 actions 数组**顺序遍历**；
- "Play Down" 与 "Pause Down" 都命中 `matchesPlayPause && contains("down")`，写同一个 `mPlayPauseDownActionIndex`，无先到先得守卫 → **数组中靠后的那组赢**；
- 按文档 §7.5.2 示例的顺序（Pause Down、Pause Up、Play Down、Play Up、Next Down、Next Up），最终生效的恒为 Play Down/Play Up——无论当前按钮显示 Play 还是 Pause 图标；
- 按钮图标确实随状态切换（`updatePlayPauseButton`，970-980 行：按标准 Play/Pause action 的 title 含 "pause"/"暂停" 与否换 icon），但**图标切换不联动 DOWN/UP 槽位重绑**——槽位只在下一次通知更新触发 bindMediaActions 时按新数组重算。

所以"随状态切换"的正确实现方式是应用侧每次更新通知时**只带当前状态的一组 DOWN/UP**（播放中带 Pause Down/Up，暂停中带 Play Down/Up）；两组全带时 SystemUI 不报错，但只有最后一组被使用。A097/A098 的映射表名义成立、实际互相覆盖，判部分属实。Next 无此问题（只有一组，独占槽位，A099 属实）。

### 3.7 BTMusic 参考实现实录

文档 §7.2 给出的两个参考文件真实存在（A084/A085 属实）：BTMUSIC 下 service/MediaForegroundService.kt 与 util/MediaNotification.kt。前者是活的实现，`buildNotification`（443-472 行，逐字节选）：

```kotlin
        val playPauseIcon = if (isPlaying) R.drawable.play_pause else R.drawable.play_stop
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        ...
        notificationBuilder.channelId(channelId).notificationId(notificationId).contentTitle(title)
            .contentText(content).smallIcon(R.drawable.music_icon).largeIcon(getAlbumArtBitmap())
            .mediaSession(mediaSession).playing(isPlaying).progress(currentProgress)
            .priority(NotificationCompat.PRIORITY_LOW).ongoing(isPlaying)
            .visibility(NotificationCompat.VISIBILITY_PUBLIC).addAction(
                R.drawable.play_up,
                "Previous",
                createMediaActionIntent(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            ).addAction(
                playPauseIcon, playPauseTitle, createMediaActionIntent(playPauseAction)
            ).addAction(
                R.drawable.play_next,
                "Next",
                createMediaActionIntent(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            ).compactViewIndices(0, 1, 2).contentIntent(createContentIntent())
```

与文档互相印证的细节：playPauseTitle 随播放态在 "Pause"/"Play" 间切换（447 行，A100 属实）；channelId 就是 "media_playback_channel"（96 行，572 行以 IMPORTANCE_LOW 建渠道）；经 `startForeground` 发布（246/365 行）——它能活着正是因为 1.2 的媒体分流在前台服务过滤之前。与文档相左的细节：BTMusic 仍在发送 "Previous" 动作（461 行）并占据 actions[0]——若某天它的三个 title 都改成识别表外的写法，兜底会把这个 Previous intent 分给不可见的上一首槽，Play/Pause 槽则错拿 "Next"（F8 的现成演练场）。MediaNotification.kt 是 builder 风格的封装工具（channelId 必填校验在 168 行），非发布主体。

### 3.8 CarPlay：无特判、包名不符、协议通用

三点核查结论（F12）：

1. **SystemUI 对 CarPlay 零特判**：SystemUI 模块全部源码中不存在 "com.ts.carplay" 或任何 CarPlay 包名/签名判断；DOWN/UP 协议纯按 title 匹配，任何应用提供合规 title 都能获得触摸转发。文档 §7.4 把它写成 CarPlay 专属接入要求，实为通用机制的单一用例。
2. **包名不符**：工程内 CarPlay 包名常量是 "com.ts.carplay.app"（Launcher 模块 Constants.java 20 行与 arrays.xml 5 行），文档写 "com.ts.carplay"，少了 ".app" 后缀；且该常量只有 Launcher 消费（应用列表/图标类场景），与通知链无关。A090 判部分属实。
3. **CarPlay 应用侧行为不可核验**：CarPlay APK 不在本工程，文档 §7.4.1 的"必须成对、缺 UP 视为按键未释放"（A092/A093）、PendingIntent 内容构造（A102）、BroadcastReceiver 转发 MediaSession（A112）均无法本机核验；SystemUI 侧能确认的对应事实是成对判定（3.5 要点 2）与"send() 内容不问"（3.5 末段）。

### 3.9 链③四问收束

| 问题 | 回答 |
|---|---|
| 媒体识别规则是什么 | 宽口径四条件任一命中（严口径/EXTRA_MEDIA_SESSION/category/channelId 硬编码），入口分流优先于一切过滤 |
| 谁设置 | 应用侧用 MediaStyle+session 或兼容后门（channelId）；DOWN/UP 由应用按 title 协议自带 |
| 谁执行 | Listener 单槽记账 → NavBar 绑定：信息面读 extras，控制面 title 识别 + 位置兜底 + 触摸/点击双通道 |
| 后果 | 媒体通知永不进横幅/列表；识别失配时按钮错位或置灰而非消失；单槽意味着多源互斥，后到覆盖 |

## 4. 三方对照总表：手机 SystemUI / AOSP Car 库 / yadea

把三条实现并排（平台侧细节均见解剖篇对应章节），yadea 的定制点一眼可见：

| 维度 | 手机 SystemUI（A13） | AOSP Car Notification 库 | yadea SystemUI |
|---|---|---|---|
| 工程形态 | 平台 SystemUI（解剖篇 §6） | 平台 Car SystemUI + 共享库（§15-20） | 独立 Gradle APK，借类名改内脏 |
| 数据载体 | NotificationEntry（管线集合） | NotificationEntry（Car 库） | AlertEntry |
| 入口 | NotificationListener + GroupCoalescer 攒批（§6.1/6.3） | CarNotificationListener（§15.2） | Listener 同款类名，入口即分流+丢弃（1.2） |
| 前置过滤 | 无入口丢弃（管线内做抑制） | 面板过滤低重要度特权 FGS、媒体/导航；CALL 剔出面板（§16.2） | 入口丢弃全部 FGS + 四系统包黑名单 + 媒体/导航（1.3，F1/F2） |
| 横幅判定 | 十一道门槛（§7.2） | 五否决三放行（§17.1） | 六闸：LostMode→过滤复查→基础五条件→特权/MESSAGE/等级三通道→三标志→置换（1.5-1.7） |
| importance 门槛 | 亮屏链第 8 道 ≥HIGH（§7.2） | ≥HIGH（§17.1） | **整段注释**（CHUNM:890-899，F5） |
| 业务等级 | 无 | 无 | L0/LX/L1/UNKNOWN 双口解析，九个消费面（§2） |
| 多横幅仲裁 | compareTo 择优单飞（§9.5） | HunImportance Z 序链表（§18.1） | ReplDec 三态：SHOW_NEW/REPLACE/FALLBACK_TO_LIST（1.7） |
| 时长常量 | 5000+700 补偿/2000/60000/10000（§9.2） | 8000/2000（§17.3） | **5000**/2000，入场 500、退场 333（1.9） |
| 窗口 | shade 窗口内嵌视图（§11） | TYPE_STATUS_BAR_SUB_PANEL 系统容器（§20.2） | 默认屏 TYPE_NAVIGATION_BAR_PANEL + 副屏 TYPE_SYSTEM_DIALOG 双容器（1.1） |
| 双屏 | 无 | 无 | L0/LX → displayId=2 副屏路由（1.7） |
| 面板互斥 | pinned/panel 状态机（§10.1） | 横幅/列表互斥＋结束后补录（§15.1/15.3） | 面板可见硬编码跳过横幅 + 展开即全收（1.4） |
| 媒体链 | MediaDataManager 独立管线（§14） | 面板过滤，交给媒体应用 | 回归通知链，NavBar 单槽卡片 + DOWN/UP 触摸协议（§3） |
| 声音 | 服务端 buzz/beep（§4.6） | Beeper 车机端播 channel 声音（§17.6） | 同 Beeper，媒体类静音（1.10） |
| FSI 处理 | 决策树转全屏（§7.5） | 豁免自动收回（§17.3）+ 不可滑掉（§17.5） | 同款豁免（CHUNM:718-722）+ 不可滑掉（:707-711） |
| 特有熔断 | 无 | 驾驶状态 UX 裁剪（§16.3） | LostMode 整树熔断 + 行车触摸锁 + UX 裁剪保留（MESSAGE 豁免） |
| 静音 | 会话级 mute（平台功能） | 消息 mute 按钮接线完整 | 判定链在、接线缺，不可达（F9） |

一句话概括三方关系：手机 SystemUI 的横幅是**平台管线算出来的**，AOSP Car 库的横幅是**配置和类目框出来的**，yadea 的横幅是**业务等级和特权签出来的**——importance 这条平台主轴在 yadea 已经退出展示裁决，接管的是一套应用侧可自报（且部分可伪装）的等级协议。

---

## 第二部 136 条断言逐条审计

## 5. 审计总表

判定标记见 0.3 节。"文档行"指接入文档 552 行版式的行号；复述条目在备注注明首发编号。证据一律为 yadea 树"文件名:行号"（路径映射见 0.2），平台侧证据用本仓库反引号路径。

### 5.1 接入方式与链路总览（A001-A021）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A001 | 7 | ✅ | MANIFEST 285-293 标准监听声明；Listener 继承 `NotificationListenerService`；全工程无面向第三方的自定义广播/AIDL 接入点 |
| A002 | 8 | 🟡 | notify() 路径属实（Listener:128 起接收）；但 startForeground 通知带 `FLAG_FOREGROUND_SERVICE`，被 PPM:258-260 一刀切丢弃（横幅列表全无），仅媒体类因分流在前豁免（Listener:149-159）。"能收到"成立，"能显示"不成立——F2 |
| A003 | 9 | ✅ | category→模板（Utils:122-152）；extras/channelId→等级（Clf:64-88）；MediaSession/channel→媒体分流（Utils:156-171）；actions title→按钮（NavBar:908-956）；contentIntent→点击（ClickFac:91-97） |
| A004 | 17 | ✅ | MANIFEST 285-293；Listener:128 |
| A005 | 19 | ✅ | 文件存在（491 行），承担监听、分流、分发、移除、状态栏图标 |
| A006 | 21 | ✅ | 同 A001；但注意"被接收"不等于"被展示"，还有入口丢弃与准入两道闸（F1/F2/F5） |
| A007 | 43 | ✅ | Listener:149-166：非媒体且过 shouldFilter 者进 notifyNotificationPosted |
| A008 | 45 | ✅ | Listener:128 onNotificationPosted |
| A009 | 46 | ✅ | PPM process:160-168 五段：filter/optimizeForDriving/group/rank/regroupByBusinessLevel |
| A010 | 47 | ✅ | 列表（Listener:334-339→371-379）、横幅（CHUNM:463）、状态栏图标（Listener:470-490） |
| A011 | 49-55 | 🟡 | 消息/普通应用/系统车辆告警（特权直通）属实；**导航入口即全丢**（PPM:279-287，F1）；**通话面板剔除**（PPM:248-249）且不在横幅白名单（Decider:35-38）——第三方通话通知两头不可见 |
| A012 | 59 | ✅ | Listener:149-159 媒体分流后 return，不进面板/横幅管线 |
| A013 | 61 | ✅ | Utils:156-171；调用点 Listener:149 |
| A014 | 62 | ✅ | Listener:153-157 写单槽并回调 onShow → NavBar:1219-1231 更新信息与控制 |
| A015 | 66 | ✅ | 复述 A005 |
| A016 | 67 | ✅ | Utils 存在，承担媒体识别（:156-171）与模板选择（:122-152） |
| A017 | 68 | ✅ | NAVBAR/NavBarFragment.java 存在，onShow:1219-1231 |
| A018 | 72 | ✅ | updateMediaInfo:982-1002：标题/副标题/封面/进度/包名全量刷新底栏 |
| A019 | 86 | ✅ | ClickFac:91-97：contentIntent 优先，fullScreenIntent 兜底 |
| A020 | 107 | ✅ | Utils:122-152 switch(category) |
| A021 | 111 | ✅ | 复述 A016 |

### 5.2 模板、点击与 Action（A022-A037）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A022 | 115 | ✅ | Utils:127-128 → CarNotificationTypeItem.EMERGENCY |
| A023 | 116 | ✅ | Utils:129-130 → NAVIGATION；注：导航通知入口已丢弃（F1），此模板现网不可达，映射仅存于代码 |
| A024 | 117 | ✅ | Utils:131-132 → CALL；注：仅特权或带等级的通话通知可达（1.6 真值表） |
| A025 | 118 | ✅ | Utils:133-134 → WARNING |
| A026 | 119 | ✅ | Utils:135-136 → INFORMATION |
| A027 | 120 | ✅ | Utils:137-138 → MESSAGE |
| A028 | 121 | 🟡 | Utils:143-152：落 BASIC 前还有一道 INBOX 判定（extras 同带 EXTRA_BIG_TEXT 与 EXTRA_SUMMARY_TEXT）——"其它→Basic"漏了 INBOX 分支 |
| A029 | 129 | 🟡 | 同 A028：不设 category 的通知带那两个 extras 键时按 INBOX 模板展示 |
| A030 | 135 | ✅ | ClickFac:89-126；绑定点 BaseViewHolder:161-175 |
| A031 | 136 | ✅ | ClickFac:135-165 getActionClickHandler；绑定点 ActionsView:198 |
| A032 | 140 | ❌ | BannerNotificationView.java **不存在**（全工程 find 零命中，dropdownbar 下无 notification/widget 目录）；配套 item_banner_notification.xml 为死布局（源码零引用）。实际点击处理在 ClickFac 与各 ViewHolder——F3 |
| A033 | 141 | ✅ | TEMPLATE/CarNotificationActionsView.java 存在：上限 :57、截断 :157、绑定 :198 |
| A034 | 145 | ✅ | ActionsView:57 `MAX_NUM_ACTIONS = 3`；:157 `Math.min(actions.length, MAX_NUM_ACTIONS)` |
| A035 | 146 | ✅ | 媒体链 NavBar:799-802（actionIntent 空则 return）；横幅/列表链 ClickFac:146-150（sendPendingIntent 对 null 返回 START_ABORTED）。无 PendingIntent 的 action 按钮点了也没反应 |
| A036 | 147 | ✅ | ClickFac:92-97：contentIntent 与 fullScreenIntent 双空直接 return，主体点击无任何效果 |
| A037 | 152 | ✅ | 复述 A034 |

### 5.3 横幅触发规则（A038-A046）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A038 | 159 | ✅ | 定制汇总即本文第一部：入口丢弃、等级通道、importance 注释、置换决策、双屏路由均为 yadea 独有 |
| A039 | 163 | ✅ | CHUNM 存在（953 行），maybeShowHeadsUp:179-229 |
| A040 | 168 | ✅ | Decider:35-38 MESSAGE 在白名单；前提是过 1.5-1.6 全部闸（非媒体、非组摘要、非组抑制、非静音、非 LostMode、三标志、置换放行） |
| A041 | 169 | ❌ | NAVIGATION 虽在 Decider:37 白名单，但该类目通知在 Listener:160-166 入口即被 shouldFilter 丢弃（PPM:279-287），CHUNM:189-206 复查同拦，**永远到不了横幅判定**。文档 §9.2 建议按此接入的导航通知将完全不可见——F1 |
| A042 | 170 | ✅ | CHUNM:899-907 特权直通 return true；判定实现 Utils:76-77/209-219（平台签名或系统特权） |
| A043 | 171 | ✅ | Decider:26-28 suppressAlertingDueToGrouping 否决；AOSP 平台语义原样沿用 |
| A044 | 172 | 🟡 | 判定链属实（Decider:29-31；DataMgr:173-179 读静音表）；但唯一写入口 toggleMute（DataMgr:185-196）全工程无调用点，MuteStatusSetter（ClickFac:175）仅接口无实现接线——**当前没有任何途径把通知置为静音**，该规则不可达——F9 |
| A045 | 173 | 🟡 | 前半属实：CHUNM:890-899 importance≥HIGH 判定整段注释（逐字引文见 1.6）。后半"主要看 category"不完整：真实准入是特权直通 + category(MESSAGE) + 业务等级三通道（CHUNM:899-930；Decider:45-51），等级通道是文档自己在 §5 介绍、却没在 §4 关联的——F5 |
| A046 | 179 | ✅ | importance 在 yadea 展示链零消费（仅 Listener:316-320/398-402 的 unseen 记账）；调高 channel importance 既不过准入闸，也不影响排序展示 |

### 5.4 业务等级（A047-A068）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A047 | 198-203 | ✅ | NotificationLevelType.java 全文：L0/LX/L1/UNKNOWN 四级 |
| A048 | 207 | ✅ | Clf 存在（184 行） |
| A049 | 208 | ✅ | NotificationLevelType.java 存在（11 行） |
| A050 | 212 | ✅ | Clf:72-75：resolveRecognizedLevel 第一步即 extras |
| A051 | 214 | ✅ | Clf:22 "notification_level" |
| A052 | 215 | ✅ | Clf:23 "notificationLevel" |
| A053 | 216 | ✅ | Clf:24 "level_type" |
| A054 | 217 | ✅ | Clf:25 "levelType" |
| A055 | 218 | ✅ | Clf:26 "biz_level" |
| A056 | 219 | ✅ | Clf:27 "bizLevel" |
| A057 | 223 | ✅ | Clf:146-148（Number 0）、:160-162（"0"/"l0"） |
| A058 | 224 | ✅ | Clf:149-150（Number 1）、:166-170（"1"/"l1"/"app"/"application"） |
| A059 | 225 | ✅ | Clf:151-152（Number 10）、:163-165（"x"/"lx"） |
| A060 | 245 | ✅ | Clf:77-80 → resolveFromCategory:107-119（extras 之后第一位） |
| A061 | 246 | ✅ | Clf:82-85 → resolveFromText(channelId)；注：这是 channel 命名对等级的实质影响入口（F14） |
| A062 | 247 | ✅ | Clf:87 → resolveFromText(包名) |
| A063 | 248 | ✅ | Clf:38-56：resolveLevel 兜底 return L1（:55）；注：横幅准入走不兜底口 resolveRecognizedLevel（2.1 双口设计） |
| A064 | 252 | ✅ | Clf:112-115：CATEGORY_CAR_EMERGENCY → L0 |
| A065 | 252 | ✅ | Clf:112-115：CATEGORY_CAR_WARNING → L0（同一 switch） |
| A066 | 253 | ✅ | Clf:55：无等级信息的外部应用通知兜底 L1 |
| A067 | 257-259 | ✅ | Resolver:14-23 三豁免装配（CAR_EMERGENCY/CAR_WARNING 类目、系统应用、平台签名）；Rules:42-50 执行 L0→L1；PPM:937-951 调用。注：降级只作用于特权面，横幅准入不受其约束——保护面精确圈定见 2.4（F10） |
| A068 | 263 | ✅ | PPM 存在且承担调用侧职责（buildEntryLevel:937-951）；判定在 Resolver、执行在 Rules |

### 5.5 消息与媒体（A069-A089）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A069 | 276 | ✅ | 复述 A027（Utils:137-138） |
| A070 | 278 | ✅ | TEMPLATE/MessageNotificationViewHolder.java:300 读 EXTRA_TITLE（会话标题） |
| A071 | 279 | ✅ | MessageNotificationViewHolder.java:257 读 EXTRA_TEXT（消息文本） |
| A072 | 280 | ✅ | 双侧：MessagingStyle 是 AOSP 会话消息标准载体；yadea 消息模板按 messaging 数据渲染（A070/A071 的读取点即其数据面） |
| A073 | 284 | ✅ | TEMPLATE/MessageNotificationViewHolder.java 存在 |
| A074 | 306 | ✅ | 复述 A014 |
| A075 | 310 | ✅ | Utils:156-171 四条件任一命中即媒体 |
| A076 | 312 | ✅ | Utils:160-162；平台实现见 `frameworks/base/core/java/android/app/Notification.java` :7243 起（MediaStyle 且带 session token） |
| A077 | 313 | ✅ | Utils:164-167 判 extras 含 EXTRA_MEDIA_SESSION |
| A078 | 314 | ✅ | Utils:169-170 判 category=="android.mediaSession" |
| A079 | 315 | ✅ | Utils:170 判 channelId=="media_playback_channel"——全工程唯一被硬编码消费的 channel 名（F4） |
| A080 | 319 | ✅ | 复述 A016 |
| A081 | 331 | ✅ | 双侧：消费端 NavBar:988 把 EXTRA_PROGRESS 直接当 0-100 百分比（**忽略 EXTRA_PROGRESS_MAX**）；AOSP setProgress 写同键（Notification.java:4500）。按 max=100 传值时两端口径一致 |
| A082 | 331 | ✅ | NavBar:998 mSeekBar.setProgress(progress)；进度条只读不可拖（:195 吞触摸） |
| A083 | 332-333 | ✅ | 可见按钮恰为两个：previousBtn 布局恒 gone 且全工程无恢复代码；setViewEnabled 只改 enabled/alpha（NavBar:1084-1087），不改 visibility |
| A084 | 339 | ✅ | BTMUSIC/service/MediaForegroundService.kt 存在（buildNotification:443-472，startForeground:246/365） |
| A085 | 340 | ✅ | BTMUSIC/util/MediaNotification.kt 存在（builder 封装，channelId 必填校验 :168） |
| A086 | 344 | ✅ | NavBar resolveActionIndices:908-945 + 三个匹配器 :948-956 |
| A087 | 346 | ✅ | matchesPlayPause:949-952：play/pause/播放/暂停；实现为小写化任意位置子串，比文档枚举更宽（"playlist" 也命中） |
| A088 | 347 | ✅ | matchesNext:954-956：next/下一 |
| A089 | 349 | ✅ | 可见层面成立：previousBtn 布局 gone、无 setVisibility(VISIBLE) 代码。补注：识别/点击/兜底接线全套保留（NavBar:277/:902/:948-950/:958-960），BTMusic 仍发送 "Previous" 并占据 actions[0]（MediaForegroundService.kt:461）——传了会被不可见槽位吃掉（F11） |

### 5.6 CarPlay 与 DOWN/UP（A090-A112）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A090 | 360 | 🟡 | 工程内实际包名常量为 "com.ts.carplay.app"（Launcher Constants.java:20、arrays.xml:5），文档少 ".app" 后缀；且 SystemUI 通知链对该包零引用（F12） |
| A091 | 360 | ⬜ | CarPlay APK 不在本工程。SystemUI 侧对应事实：媒体分流不看包名，任何应用满足四条件即更新媒体卡片（Listener:149-159） |
| A092 | 364 | ⬜ | CarPlay 端要求不可核验。SystemUI 侧对应事实：hasTouchActions 要求 down/up 索引成对（NavBar:852-858），只发成对或全不发，不会出现半套按键 |
| A093 | 366 | ⬜ | 同上，CarPlay 端"按键未释放"行为不可核验 |
| A094 | 370 | ✅ | Utils:156-171 识别条件不含包名；SystemUI 无 CarPlay 特判，其通知与任意应用同权（F12） |
| A095 | 371 | ✅ | NavBar:916-931 DOWN/UP 专槽识别；:861-871 触摸时机分发 |
| A096 | 372 | 🟡 | 实现比"按钮名+Down/Up"松：小写化后**同时含按钮词与 "down"/"up" 子串**即命中，位置任意（NavBar:916-931），不要求后缀形态（F7） |
| A097 | 373 | 🟡 | 映射名义成立，但 Play/Pause 的 DOWN/UP 共用单槽、遍历无守卫后写覆盖——两组同时提供时数组中最后一组恒生效（NavBar:917-922，F6） |
| A098 | 374 | 🟡 | 同 A097：Pause Down/Up 与 Play Down/Up 竞争同一对槽位 |
| A099 | 375 | ✅ | Next 独享槽位无竞争（NavBar:925-931），映射完整成立 |
| A100 | 377 | ✅ | MediaForegroundService.kt:447 `playPauseTitle = if (isPlaying) "Pause" else "Play"`；NavBar 侧图标随标准 action title 切换（updatePlayPauseButton:970-980） |
| A101 | 377 | 🟡 | "都需提供"不会报错，但单槽覆盖使恒只有最后一组生效、且不随播放状态切换重绑——文档预期的"随 title 状态切换"语义在实现中不存在；正确做法是每次更新只带当前状态的一组（3.6，F6） |
| A102 | 378 | ⬜ | CarPlay 侧 PendingIntent 构造不可核验；SystemUI 只调 send() 不问内容（NavBar:819-830） |
| A103 | 384 | ✅ | mMediaButtonTouchListener（NavBar:838-873），viewId 分支只认 pause_button/next_button（:851/:855） |
| A104 | 385 | ✅ | title 匹配在绑定时刻完成（:916-931），触摸时刻校验索引成对（:852-858）——时序与"touch 时按 title 匹配"微异，结果等价 |
| A105 | 387 | ✅ | NavBar:861-865：ACTION_DOWN → sendPendingIntent(downIndex) → actionIntent.send()（:826） |
| A106 | 388 | ✅ | NavBar:866-870：ACTION_UP → sendPendingIntent(upIndex) |
| A107 | 390 | ✅ | NavBar:872 return false → click 链 sendMediaAction（:786-814）发标准 action 的 PendingIntent |
| A108 | 394 | ✅ | NavBar 含 mMediaButtonTouchListener（:838）与 sendMediaAction（:786） |
| A109 | 395 | ✅ | 复述 A016 |
| A110 | 450 | ✅ | NavBar:916-931；识别为 contains 子串（松紧细节见 A096） |
| A111 | 452 | ✅ | AOSP：PendingIntent 对内容透明，intent extras 可装 KeyEvent 或任意自定义数据（`frameworks/base/core/java/android/app/PendingIntent.java`） |
| A112 | 452 | ⬜ | CarPlay 侧 BroadcastReceiver → MediaSession 转发不可核验 |

### 5.7 channel、排查项与模板（A113-A136）

| 编号 | 文档行 | 判定 | 证据与备注 |
|---|---|---|---|
| A113 | 458 | ✅ | importance 在展示链零消费：仅 Listener:316-320/:398-402 的 unseen 记账；CHUNM 门槛注释（:890-899）；排序走业务时间戳（PPM:697-698）而非 importance |
| A114 | 469 | ✅ | 复述 A079（Utils:170） |
| A115 | 470 | 🟡 | "无强制格式要求"属实，但 channelId 文本进入等级兜底提示词匹配（Clf:82-85/:121-137）：命名含 l0/l1/lx/fault/warning/alarm/error/opponent/peer/duishoujian 子串即改变等级归属——"命名要稳定"应升级为"命名须避开（或刻意利用）提示词"（F14） |
| A116 | 484 | ✅ | 复述 A051/A058；`extras.putString("notification_level","l1")` 命中 Clf:22 + :166-170 |
| A117 | 504 | ✅ | 双侧：setProgress 写 EXTRA_PROGRESS（Notification.java:4500），extras 直传同键；yadea 只读该键（NavBar:988），两路等价（前提 max=100，见 A081） |
| A118 | 504 | ✅ | 复述 A081 |
| A119 | 504 | ✅ | 复述 A082 |
| A120 | 505 | ✅ | 复述 A095/A105-A107 |
| A121 | 515 | 🟡 | 复述 A040/A041：MESSAGE 半边成立；NAVIGATION 半边相反——不用它不影响横幅，**用了它反而全程消失**（F1） |
| A122 | 516 | ✅ | 复述 A043（Decider:26-28） |
| A123 | 517 | 🟡 | 复述 A044：排查项当前无意义——静音状态无法被置位（toggleMute 无调用点，F9），不存在"被用户静音"的通知 |
| A124 | 518 | ✅ | 媒体分流在最前（Listener:149-159）+ passesBaseFilters 首条再拦（Decider:16-18），媒体通知绝无横幅 |
| A125 | 524 | ✅ | 复述 A036（ClickFac:95-97） |
| A126 | 526 | ✅ | AOSP：`frameworks/base/core/java/android/app/PendingIntent.java` :406-414——targetSdk S+ 必须显式二选一：同设 FLAG_IMMUTABLE 与 FLAG_MUTABLE 抛异常，都不设同样抛异常（:412-414 报错原文 "requires that one of FLAG_IMMUTABLE or FLAG_MUTABLE"） |
| A127 | 532 | ✅ | 四条件反推：无 MediaStyle 且其余三条不沾 → 不识别为媒体 → 底栏不更新（Utils:156-171）。"可致"措辞准确（channelId 后门可救） |
| A128 | 533 | ✅ | 同 A127：无 session → 严口径与 EXTRA_MEDIA_SESSION 两条件皆失；category/channelId 命中仍可识别，"可致"措辞准确 |
| A129 | 534 | ✅ | 复述 A077 |
| A130 | 535 | ✅ | 复述 A079 |
| A131 | 536 | 🟡 | 复述 A086 但后果描述失准：媒体**信息**展示不依赖 action title（updateMediaInfo 不读 actions）；按钮也不是"不显示"而是**错位/置灰**——title 失配触发位置兜底 actions[0..2]（NavBar:958-968），只传 2 个 action 时播放键实发 actions[1]（实为 Next），下一首键 -1 置灰（alpha 0.3）。真实风险是误操作而非不可见（F8） |
| A132 | 542 | ✅ | 复述 A094：不满足四条件 → 不进媒体链 → mCurrentMediaEntry 空 → sendMediaAction 弹"无音乐源"Toast 返回（NavBar:787-791） |
| A133 | 543 | 🟡 | 复述 A110 但"无响应"过强：缺 DOWN/UP 时 hasTouchActions=false → return false 回落 click，标准 action 照常发送（NavBar:861-872）；仅当标准 action 也缺失或不可识别才真正无响应。对 CarPlay 而言按键协议退化属实 |
| A134 | 544 | 🟡 | 复述 A096：识别比命名规则**松**——不严格按"按钮名+Down/Up"的 title（如 "Next up next"）也可能命中；失配仅发生在缺按钮词或缺 down/up 子串时（F7） |
| A135 | 551 | ✅ | 复述 A001 |
| A136 | 551 | ✅ | 复述 A120 |

### 5.8 判定统计与分布观察

136 条断言判定分布复核（与 0.4 节一致）：

| 判定 | 条数 | 编号 |
|---|---|---|
| ❌ 证伪 | 2 | A032、A041 |
| 🟡 部分属实 | 17 | A002、A011、A028、A029、A044、A045、A090、A096、A097、A098、A101、A115、A121、A123、A131、A133、A134 |
| ⬜ 无法本机核验 | 5 | A091、A092、A093、A102、A112 |
| ✅ 属实 | 112 | 其余全部 |

三条分布观察：

- **证伪与部分属实高度聚集在文档第 4、7.4、10 节**——横幅规则（A041/A045）、CarPlay DOWN/UP（A096-A101/A133/A134）、排查清单（A121/A123/A131）。机制主干章节（§1/§2/§3/§5/§6）几乎全绿。写文档的人对"是什么"掌握准确，对"边界与例外"掌握滞后。
- **复述条目放大了错误**：第 10 节排查清单复述的前文断言里，A121/A123/A131/A133/A134 五条全部降档——错误结论每复述一次就多一处需要修订的地方。
- **五条无法核验全部指向 CarPlay 应用侧**（A091-A093/A102/A112）：SystemUI 侧能确认的对应事实（成对判定、send 内容不问）均已注在表内，CarPlay 端"缺 UP 视为按键未释放"等协议细节需要拿到 CarPlay APK 或其实测日志才能闭环。

## 6. 十四项重大发现

每项按"发现 → 证据 → 影响 → 建议"展开。证据行号均可在第一部对应章节找到逐字引文。

### F1 类目双杀：导航死链、通话两头落空

**发现**：`CATEGORY_NAVIGATION` 与 `CATEGORY_CALL` 在 yadea 当前代码里都是"死类目"。导航通知在监听器入口即被 shouldFilter 丢弃（横幅、列表、状态栏全无）；通话通知被面板管线无条件剔除，又不存在于横幅 category 白名单，只有平台签名/系统特权或带业务等级 extras 的通话通知才能以横幅现身。

**证据**：PPM:279-287（isMediaOrNavigationNotification 把 NAVIGATION 并入丢弃集）、Listener:160-166（入口丢弃）、CHUNM:189-206（横幅复查同拦）、PPM:248-249（面板剔除 CALL）、Decider:35-38（白名单仅 MESSAGE/NAVIGATION，无 CALL）。对照 AOSP Car 库：那边 shouldFilter 只作用于面板管线，横幅链路不经过它，NAVIGATION 在 config 开启时是合法横幅类目，CALL 是持久横幅的主力类目（解剖篇 §16.2/§17.1）——yadea 把过滤前移到入口，顺手杀死了这两个类目的横幅路径。Decider 里的 NAVIGATION 白名单与 `mEnableNavigationHeadsup` 开关（CHUNM:860-861）因此成为死代码。

**影响**：文档 §4"CATEGORY_NAVIGATION 更容易触发横幅"与 §9.2 的导航接入建议完全失效——按文档接入的导航应用通知会彻底不可见（A041 证伪、A121 降档的直接原因）。第三方通话应用同样受影响。

**建议**：短期修订文档（删除 §9.2 或标注"当前版本不生效"）；长期由 yadea 团队决策——要么在 shouldFilter 里给 NAVIGATION 开豁免并把入口丢弃改回面板过滤（恢复 AOSP 语义），要么把 Decider 的 NAVIGATION 分支与配置开关一并删除，别留半套死代码误导后来者。

### F2 startForeground 非媒体通知被一刀切丢弃

**发现**：一切带 `FLAG_FOREGROUND_SERVICE` 的通知（即 `startForeground()` 挂出的通知）进不了横幅也进不了列表，唯一豁免是媒体类通知——因为媒体分流在过滤之前执行。

**证据**：PPM:258-260（标志位判定）、Listener:149-166（分流先于过滤的顺序）、BTMusic 的 startForeground 存活实证（MediaForegroundService.kt:246/365）。对照 AOSP Car 库：只过滤"importance 低于 DEFAULT 且来自系统特权应用"的前台服务通知（解剖篇 §16.2），普通应用的 FGS 通知照常显示。

**影响**：文档"结论先说"第二条（A002）对 startForeground 的承诺在展示层失效。大量以常驻服务形态工作的应用（运动记录、下载器、车联服务）按文档接入后会"通知发了但什么都没有"，且日志只有一行 DEBUG 级 "filtered out"，排查成本高。

**建议**：文档明确"startForeground 通知仅媒体类可展示"；若业务确需常驻通知可见，用 `NotificationManager.notify()` 发独立通知而非依赖 FGS 通知。yadea 团队可考虑对齐 AOSP 语义（按 importance 分级过滤而非一刀切）。

### F3 幽灵文件与死布局

**发现**：文档 §3.3 引用的 `BannerNotificationView.java`（dropdownbar/notification/widget 下）不存在；工程里的 `item_banner_notification.xml` 布局全源码零引用。

**证据**：全工程 find/grep 零命中（1.13）。真实的点击处理链是 ClickFac（主体 :89-126、action :135-165）+ 各模板 ViewHolder（BaseViewHolder:161-175 绑定）。

**影响**：接入方按图索骥找不到代码；也暗示文档可能基于一个已被替换的早期"下拉横幅"原型写成，其余章节的时效性需要打折审视——本次审计逐条重验正是为此。

**建议**：文档路径修正为 `NotificationClickHandlerFactory.java` 与 `notification/template/` 下的 ViewHolder 家族；删除死布局文件。

### F4 三个推荐 channel 零实现

**发现**：文档 §8 推荐的 high_importance_channel、app_notice_channel、service_channel 在全工程（Java/Kotlin/XML）无任何创建点或消费点；唯一被代码硬编码消费的 channel 是 media_playback_channel（媒体识别第四条件）。

**证据**：全工程 grep 零命中（media_playback_channel 的消费点 Utils:170；创建点 BTMusic MediaForegroundService.kt:96/572，且以 IMPORTANCE_LOW 创建——名字带 high 与否完全无关）。

**影响**：这三个 channel 名对系统侧没有任何魔法：不建 channel 直接发通知会被平台层拒绝（AOSP 强制 channel，见 `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` 的 channel 门槛，解剖篇 §2 拒绝点 7），建了也不比任意其它命名多一分权重。文档把它们写成"推荐"容易让接入方误以为存在约定收益。

**建议**：文档注明"channel 命名除 media_playback_channel 外均无系统侧语义，推荐名仅为团队规范"；同时提醒 channelId 命名避开等级提示词（F14）。

### F5 importance 门槛注释掉，javadoc 四处脱节

**发现**：CHUNM 类注释仍宣称四条 AOSP 准入（Importance≥HIGH、平台签名、特权应用、car-compatible、CALL/NAVIGATION 类目），方法体实际执行的是另一套：importance 判定整段注释，car-compatible 检查移除，CALL 移出白名单，新增业务等级通道。

**证据**：CHUNM:830-853（javadoc）对照 :854-930（方法体），注释块 :890-899 逐字引文见 1.6；等级通道的引入注释 :929（"允许明确标记了业务等级的通知进入 HUN"）。

**影响**：读注释会得到与运行时相反的结论——文档 A045 的"主要看 category"半句错，很可能就是被这段 javadoc 带偏的。对 yadea 团队自身，这也是维护陷阱。

**建议**：javadoc 重写为真实三通道；若 importance 门槛是临时实验性关闭，应在注释块旁标注原因与恢复条件，而非裸留注释代码。

### F6 Play/Pause 的 DOWN/UP 单槽覆盖

**发现**：Play Down/Up 与 Pause Down/Up 写入同一对槽位且遍历无先到先得守卫，同时提供时数组中最后一组恒生效，绑定不随播放状态切换。

**证据**：NavBar:113-116（四个单槽字段）、:917-922（无守卫直接赋值）、:970-980（图标切换不联动槽位）；标准槽对照 :937-943（有 `< 0` 守卫，先到先得）——同一个循环里两套槽位两种策略，疑似疏忽而非设计。

**影响**：文档 §7.4.2 步骤 3"Play 和 Pause 的 DOWN/UP 都需要提供"（A101）照做后，CarPlay 若期望按键语义随状态切换会拿到错误的 PendingIntent（恒为最后注册那组）。

**建议**：应用侧改为每次更新通知只携带当前状态的一组 DOWN/UP；或 SystemUI 侧给 DOWN/UP 槽也加先到先得守卫、并在 updatePlayPauseButton 切态时重选槽位。文档按前者修订最稳妥。

### F7 DOWN/UP 识别比命名规则松

**发现**：匹配条件是小写化后"含按钮词 + 含 down/up 子串"，位置与形态不限。

**证据**：NavBar:916-931（contains 判定）+ :948-956（按钮词表）。

**影响**：宽松是双刃剑——"play back down"这类非常规命名也能命中（容错），而 title 里意外带 "up"/"down" 子串的按钮词动作（如 "Play - Volume Up"）会被误绑成触摸槽、从标准槽里抢走（风险）。A096/A134 的"命名规则"实际是"充分不必要条件"。

**建议**：文档把命名规则改述为"必须同时包含按钮关键词与 down/up 子串；建议严格用 'Play Down' 形态以避免误匹配"；SystemUI 侧若要收紧，可改为 equalsIgnoreCase 或后缀匹配。

### F8 位置兜底错位

**发现**：title 全部不可识别时，兜底把 actions[0..2] 机械赋给上一首/播放/下一首；只传 2 个 action 的常见形态下，播放键会绑定 actions[1]（实际是 Next 的 intent），下一首键置灰。

**证据**：NavBar:958-968（applyFallbackIndices）、:1084-1087（setViewEnabled 置灰而非隐藏）。

**影响**：用户点"播放"发出"下一首"，比按钮消失更糟——静默的错误行为。触发条件（中文标题不在关键词表、图标 title 为空、自造英文标题）在第三方接入中很常见。

**建议**：文档把"title 使用推荐关键词"从建议升级为强约束并给出反例；SystemUI 侧更稳的做法是兜底仅对 3+ action 生效，或识别失败时整体禁用控制钮而非错位绑定。

### F9 静音是未接线的半成品

**发现**：消息静音的判定链完整（存储、读取、横幅否决都在），但唯一状态写入口 `toggleMute` 全工程无调用点，`MuteStatusSetter` 接口无实现注册——静音表永远为空。

**证据**：DataMgr:173-179（读）、:185-196（写，无调用者）、ClickFac:175（接口声明）、Decider:29-31（消费点）；全工程 grep 仅这四处命中。对照 AOSP Car 库：mute 判定是五道否决之一、按钮接线在消息模板里闭环（解剖篇 §17.1）。

**影响**：文档 §4"被用户静音的消息通知不会弹横幅"（A044）与 §10.1 排查项"是否消息通知被用户静音"（A123）描述了一个当前用户无法到达的状态。

**建议**：文档删除或标注该排查项；yadea 团队要么补上 mute 按钮接线（模板里预留位仍在），要么移除判定链避免误导。

### F10 L0 降级保护面比文档暗示的窄

**发现**：L0 降级只作用于 effectiveLevel 消费面（副屏路由、置换、分区、折叠、可清除性）；横幅准入用不降级的 recognizedLevel——第三方伪装 L0 照样弹主屏横幅。

**证据**：CHUNM:918-924（准入用 resolveRecognizedLevel）对照 :374-386（路由用 resolveEffectiveLevel）；降级三豁免 Resolver:14-23；生效面矩阵见 2.4。

**影响**：文档 §5.3"很多 L0 会被降级回 L1"给接入方（和审计方）的安全感超出了实际保护范围：伪装 L0 拿不到特权面，但拿得到横幅打扰面——而横幅恰恰是行车安全最敏感的通道。

**建议**：文档明确"extras 传 L0 不会带来副屏/置顶特权，但仍会触发横幅"；若产品意图是拦截伪装，准入处应改用 effectiveLevel（一行改动，但会把"第三方明确标 L1 也弹横幅"的现行为一并收紧，需产品决策）。

### F11 Previous 按钮的演化残留

**发现**：上一首按钮布局恒 gone 且无恢复代码，但识别（matchesPrevious）、点击（setOnClickListener）、兜底（actions[0]→previous 槽）接线全套保留；工程内唯一的媒体参考实现 BTMusic 仍在发送 "Previous" 动作占据 actions[0]。

**证据**：NavBar:277（点击接线）、:902（enabled 计算）、:948-950（识别）、:958-960（兜底）；MediaForegroundService.kt:459-462（Previous 动作）；布局 gone 且全工程无 setVisibility 恢复（1.13 同款核查方法）。

**影响**：对接入方是隐性陷阱——传了 Previous 动作会被不可见槽位吃掉一个数组位，使兜底场景（F8）的错位概率上升；对维护者是"看代码以为有上一首功能"的误导。

**建议**：文档 §7.3 的"不需要传 Previous action"升级为"不要传"（并说明会占用 actions[0]）；yadea 团队择机清理残留接线，或恢复按钮可见性把功能补全。

### F12 SystemUI 无 CarPlay 特判，且包名不符

**发现**：SystemUI 模块对 "com.ts.carplay" 零引用；工程内 CarPlay 包名常量是 "com.ts.carplay.app"，且只有 Launcher 模块消费。DOWN/UP 是按 title 匹配的通用协议，任何应用可用。

**证据**：全工程 grep（3.8）；Launcher Constants.java:20、arrays.xml:5。

**影响**：文档把通用机制写成 CarPlay 专属（§7.4），一方面让其它媒体应用错失触摸转发能力（长按/短按语义），另一方面让接入方误以为存在包名级白名单。包名少写 ".app" 会让按文档做包名判断的下游代码（如白名单、埋点）失配。

**建议**：文档改述为"DOWN/UP 触摸转发是通用协议，当前主要使用方为 CarPlay（com.ts.carplay.app）"；修正包名。

### F13 系统四包黑名单（文档未提及的丢弃面）

**发现**：包名为 android、com.android.systemui、com.android.server.telecom、com.android.car.dialer 的通知在入口即丢弃——涵盖系统自身的杂项通知（如 USB/热点提示）与整个系统电话栈（telecom 的通话样式通知、car.dialer 的未接来电等）。

**证据**：PPM:69-72（常量）、:262-277（判定）、Listener:160-166（执行点）。

**影响**：接入文档完全没有提及这一层，而它恰恰解释了"为什么系统电话的通知在通知中心看不到"这类常见疑问；与 F1 的 CALL 类目剔除叠加后，通话类通知在 yadea 上的可见性完全依赖 yadea 自家 BTPhone 的实现方式。

**建议**：文档在"通知不显示排查"里补充包名黑名单一条；yadea 团队确认 telecom/car.dialer 全量丢弃是否为产品意图（来电横幅由谁负责需要明示）。

### F14 channelId 与包名文本参与等级兜底

**发现**：等级瀑布第三、四级对 channelId 和包名做提示词子串匹配（l0/lx/l1/fault/warning/alarm/error/opponent/peer/duishoujian），命名撞词即改变等级归属，且优先级高于"最终默认 L1"。

**证据**：Clf:29-33（提示词表）、:82-87（channelId/包名两级）、:121-137（子串匹配）。

**影响**：文档 A115"其它 channelId 无强制要求"给出错误安全感。实例：叫 "warning_center_channel" 的运营渠道会被识别成 L0（若非系统应用再被降级成 L1，恰好与不撞词时同值——但**系统应用**用这个名字就直接是真 L0，进副屏、不可清除）；包名含 "alarm" 的时钟应用同理。反过来，这也给接入方留了一个不传 extras 也能声明等级的后门（撞词即声明），是否算特性需要产品确认。

**建议**：文档补充"channelId/包名命名须避开等级提示词，或明确知晓撞词后果"；yadea 团队评估提示词兜底的误伤面，必要时收窄为精确匹配（如 channelId 等于 "l0"/"lx"/"l1"）。

## 7. 无法本机核验清单

五条 ⬜ 全部指向 CarPlay 应用侧（APK 不在本工程，源码不可得）：

| 编号 | 断言 | SystemUI 侧已确认的对应事实 |
|---|---|---|
| A091 | CarPlay 作为三方应用发送自己的媒体通知 | 媒体分流不看包名，任何应用满足四条件即更新单槽媒体卡片（Listener:149-159） |
| A092 | CarPlay 要求每次媒体操作必须含 DOWN 和 UP，缺一不可 | SystemUI 只在 down/up 索引成对时走触摸通道（NavBar:852-858），不会发出半套按键 |
| A093 | 只发 DOWN 无 UP 时 CarPlay 认为按键未释放 | SystemUI 侧无对应行为（成对判定保证要么全发要么全不发）；CarPlay 端逻辑不可见 |
| A102 | DOWN/UP PendingIntent 由 CarPlay 构造并携带按键信息 | SystemUI 只调 `send()` 不检查内容（NavBar:819-830） |
| A112 | CarPlay BroadcastReceiver 接收后转发 MediaSession | 完全在 CarPlay 进程内，不可见 |

闭环办法：实车连接 CarPlay 后抓 logcat——NavBar 的绑定日志（896-901 行，输出全部七个槽位索引与每个 action 的 title/intent）能直接验证 CarPlay 通知的 actions 构成；触摸时的 `sendMediaAction: dispatched`（809-810 行）与 `sendPendingIntent` 路径日志能验证 DOWN/UP 实际发送时序。CarPlay 端"按键未释放"的表现只能靠其实测行为观察。

## 8. 总结论与接入建议修正

### 8.1 对这份接入文档的总评

**骨架可信，边界失真。** 136 条断言 82.4% 属实，机制主干（监听入口、等级 extras 协议、媒体四条件、DOWN/UP 存在性、模板映射、点击链路）全部经得起源码对证；失真的 19 条几乎全部集中在三类边界上——被改动过的 AOSP 遗留语义（F1/F5/F9）、实现比描述更松或更紧的匹配机制（F6/F7/F8/F14）、未同步的工程事实（F3/F4/F12/F13）。文档作者对系统的"设计意图"把握准确，对"当前代码实际执行什么"的核对不足——这在快速改装的车机项目里是典型失真模式。

### 8.2 文档修订清单（按章节）

1. **结论先说第 2 条**：补"startForeground 挂出的通知仅媒体类会被展示，非媒体类被系统统一丢弃"（F2）。
2. **§2.1 适用范围**：删"导航通知"；"通话通知"加限定"仅平台签名/系统特权/带业务等级 extras 时以横幅展示，不进通知中心列表"（F1）。
3. **§3.2 映射表**：NAVIGATION、CALL 两行加可达性注记（F1）。
4. **§3.3 相关代码**：BannerNotificationView.java 替换为 NotificationClickHandlerFactory.java 与 notification/template/ ViewHolder 家族（F3）。
5. **§4 横幅规则**："当前主要看 category"修正为"特权直通 / CATEGORY_MESSAGE / 业务等级 extras 三通道"；删除"导航类使用 CATEGORY_NAVIGATION"建议；"被用户静音"条目标注当前不可达（F5/F1/F9）。
6. **§7.4.2 步骤 3**：DOWN/UP 提供策略改为"每次更新通知只携带当前播放状态的一组 Play 或 Pause DOWN/UP"，并说明两组同带时仅最后一组生效（F6）；命名规则补充"子串匹配、位置不限"的松紧说明（F7）。
7. **§7.3 注**："不需要传 Previous action"升级为"不要传——会被不可见槽位占用 actions[0]，影响兜底分配"（F11）。
8. **§8 channel 建议**：注明除 media_playback_channel 外均为纯命名约定、无系统侧语义（F4）；补充"channelId 与包名须避开等级提示词 l0/lx/l1/fault/warning/alarm/error/opponent/peer/duishoujian"（F14）。
9. **§9.2 导航通知**：整节删除，或改为"当前版本 CATEGORY_NAVIGATION 通知会被系统丢弃，接入前需与 SystemUI 团队确认版本"（F1）。
10. **§10.1 排查清单**：删"是否消息通知被用户静音"；"是否使用了 CATEGORY_MESSAGE 或 CATEGORY_NAVIGATION"改为"是否 CATEGORY_MESSAGE 或携带业务等级 extras"；新增三条：是否 startForeground 发出、包名是否在系统四包黑名单、是否处于 LostMode/行车锁（F9/F1/F2/F13）。
11. **§7.4 措辞**：CarPlay 专属改为"通用 DOWN/UP 触摸转发协议，当前主要使用方 CarPlay"；包名修正为 com.ts.carplay.app（F12）。

### 8.3 对第三方接入方：按当前代码真正有效的建议

浓缩成七条，全部有源码背书：

1. 普通提醒用 `NotificationManager.notify()`，**不要依赖 startForeground 的通知展示**（F2）。
2. 想弹横幅：`CATEGORY_MESSAGE`，或 extras 传 `notification_level`（六键任一，值 l1 即可）——两者取一，后者对任意 category 生效（1.6 真值表）。
3. 想进通知中心列表：避开导航类目、避开四包黑名单、别用 startForeground（F1/F13/F2）。
4. contentIntent 必带，PendingIntent 显式 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`（A036/A126）。
5. 媒体通知：MediaStyle + MediaSession（或兜底 channelId=media_playback_channel），2 个 action、title 严格用 "Play"/"Pause"/"Next" 英文关键词，进度按 0-100 传 EXTRA_PROGRESS（3.1/3.4/3.3）。
6. 不要传 Previous action；action 总数控制在 2-3 个（F11/A034）。
7. channelId 与包名命名避开等级提示词子串（F14）。

### 8.4 对 yadea 团队：代码侧改进清单

按优先级：① 导航/通话类目决策收口（恢复或删除死代码，F1）；② DOWN/UP 槽位加守卫或状态化绑定（F6）；③ 兜底错位防护（识别失败禁用而非错位绑定，F8）；④ 静音接线补全或拆除（F9）；⑤ javadoc 与实现对齐（F5）；⑥ 死布局与幽灵引用清理（F3/F11）；⑦ 等级提示词兜底的误伤评估（F14）；⑧ FGS 过滤对齐 AOSP 分级语义（F2）。

---

## 附录：证据文件行号索引

本文全部 yadea 侧证据的完整路径与关键锚点（ROOT 定义见 0.2 节）：

```text
NOTIF/CarNotificationListener.java（491 行）
  128-169 onNotificationPosted 六段 | 149-159 媒体分流 | 160-166 入口丢弃
  192-212 onNotificationRemoved | 309-340 notifyNotificationPosted
  322-329 面板可见跳过 | 371-379 postNewNotification | 398-402 importance>LOW 记账
  404-409 IMediaNotificationListener | 411-419 注册补发 | 421-426 面板展开全收
  470-490 状态栏图标等级消费
NOTIF/CarHeadsUpNotificationManager.java（953 行）
  127/131 时长常量读入 | 179-229 maybeShowHeadsUp | 183-188 LostMode 复查
  189-206 shouldFilter 复查 | 277-298 releaseAllHeadsUpToShade | 316-318 alertAgain
  338-343 canUpdate | 374-386 resolveContainer 双屏路由 | 392-420 prepareIncomingHeadsUp
  463 showHeadsUp | 505-522 跨屏迁移与视图创建 | 686-705 playSound
  707-711 shouldDismissOnSwipe | 718-725 setAutoDismissViews（FSI 豁免）
  737 dismissHun | 830-853 javadoc 残留 | 854-930 shouldShowHeadsUp
  890-899 importance 注释块 | 899-907 特权直通 | 918-930 等级通道
NOTIF/PreprocessingManager.java（1073 行）
  69-72 四包常量 | 160-168 process 五段 | 225-229 shouldFilter
  235-256 filter（248-249 CALL 剔除） | 258-260 FGS 判定 | 262-277 系统包判定
  279-287 媒体/导航判定 | 295-330 optimizeForDriving | 666-711 regroupByBusinessLevel
  727-749 顶区折叠 | 806-855 L1 按包重组 | 937-951 buildEntryLevel
NOTIF/HeadsUpNotificationDecider.java（52 行）
  10-33 passesBaseFilters | 35-38 isCategoryEligible | 45-51 isHeadsUpEligible
NOTIF/HeadsUpDisplayDecider.java（25 行） 13-15 副屏判定 | 17-24 跨屏迁移判定
NOTIF/HeadsUpReplacementDecider.java（40 行） 21-35 decide 三态 | 37-39 isTopAlertLevel
NOTIF/NotificationLevelClassifier.java（184 行）
  21-27 六键 | 29-33 提示词表 | 38-56 resolveLevel 兜底 | 58-88 resolveRecognizedLevel
  90-105 resolveFromExtras | 107-119 resolveFromCategory | 121-137 resolveFromText
  139-174 parseLevel
NOTIF/NotificationEntryLevelResolver.java（52 行） 14-23 降级装配 | 25-41 三豁免输入 | 43-51 effective 解析
NOTIF/NotificationLevelRules.java（87 行） 18-34 resolveGroupLevel | 36-40 canUserDismiss | 42-50 resolveEffectiveLevel | 52-59 resolveDisplaySection | 61-76 priorityOf
NOTIF/TopAlertFoldRules.java（49 行） 9 MAX=3 | 14-16 shouldFold | 18-35 resolveFoldedLevel
NOTIF/NotificationLevelType.java（11 行） 四级枚举
NOTIF/NotificationDataManager.java 173-179 isMessageNotificationMuted | 185-196 toggleMute（无调用者）
NOTIF/NotificationClickHandlerFactory.java 89-126 主体点击 | 135-165 action 点击 | 175 MuteStatusSetter 接口
NOTIF/NotificationUtils.java
  76-77/209-219 特权判定 | 98-115 isSystemApp/isSignedWithPlatformKey
  122-152 getNotificationViewType | 156-171 isMediaLikeNotification
NOTIF/template/CarNotificationActionsView.java 57 MAX_NUM_ACTIONS | 157 截断 | 198 点击绑定
NOTIF/template/CarNotificationBaseViewHolder.java 161-175 bind | 382 canUserDismiss 消费
NOTIF/template/MessageNotificationViewHolder.java 257 EXTRA_TEXT | 300 EXTRA_TITLE
NOTIF/headsup/CarHeadsUpNotificationAppContainer.java 38-49 窗口类型选择
NOTIF/headsup/HeadsUpWindowStateDecider.java（36 行） 9 收起高度 1px | 14-26 可见/可触/高度
NOTIF/CarNotificationTypeItem.java 40-66 模板枚举表
NOTIF/NotificationGroup.java 315 canUserDismiss 消费
SYSUI/SystemUIApplication.kt 108-109 双容器创建
NAVBAR/NavBarFragment.java
  108 mCurrentMediaEntry | 113-116 DOWN/UP 四槽 | 195 进度条吞触摸 | 276-277 卡片/按钮点击
  786-814 sendMediaAction | 819-830 sendPendingIntent | 838-873 触摸监听（839-846 行车锁）
  876-906 bindMediaActions | 908-945 resolveActionIndices | 948-956 三个匹配器
  958-968 applyFallbackIndices | 970-980 updatePlayPauseButton | 982-1002 updateMediaInfo
  1084-1087 setViewEnabled | 1219-1231 onShow
BTMUSIC/service/MediaForegroundService.kt 96 channelId | 246/365 startForeground | 443-472 buildNotification | 447 playPauseTitle | 459-468 三动作 | 572 建渠道 IMPORTANCE_LOW
BTMUSIC/util/MediaNotification.kt 138 builder | 168 channelId 必填校验
MANIFEST 285-293 监听服务声明
RES/values/integers.xml 22/25/28/31/34 五个时长常量
RES/layout/item_banner_notification.xml 死布局（零引用）
Launcher（ROOT/application/Launcher）Constants.java:20 与 res/values/arrays.xml:5 —— com.ts.carplay.app

AAOS13 树（本仓库）对照锚点：
  frameworks/base/core/java/android/app/Notification.java:7243（isMediaNotification）:4500（setProgress）
  frameworks/base/core/java/android/app/PendingIntent.java:406-414（可变性 flag 强制）
  packages/apps/Car/Notification/res/values/integers.xml:22/25（2000/8000 对照）
```

### 关联阅读

- `docs/md/Android13横幅通知全链路解剖.md` —— 平台侧（手机 SystemUI 与 AOSP Car 库）横幅全链路走读，本文所有"AOSP 对照"结论的出处。
- `docs/md/通知服务与车机打扰治理.md` —— 通知服务的治理视角（免打扰、渠道权限、车规限制），与本文的展示链视角互补。

*审计基准：yadea_master 树 2026-11 工作副本；AAOS13 树为本仓库当前 main。行号以该基准为准，后续提交可能漂移。*

