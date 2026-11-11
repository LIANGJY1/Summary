# Android 13 Launcher 启动、LaunchMode 与 Task 源码分析

> 一条从 `systemReady` 走到客户端 `onNewIntent` 的完整调用链精读：HOME 意图如何被解析与发起，`ActivityStarter` 如何裁决 flag 与 Task，`findTask` 如何决定复用还是新建，五种 launchMode 各自的栈形推导，跨进程事务如何把 `onCreate` 送进应用进程——最后用雅迪车机一次真实的「Launcher 双实例」事故日志，把前面所有机制串成一条可逐行验证的证据链。

---

## 目录

- [0. 导读：材料、口径与一条主线](#0-导读材料口径与一条主线)
- [1. 「回桌面」从哪里来：HOME 启动的发起侧](#1-回桌面从哪里来home-启动的发起侧)
- [2. 一次 startActivity 的服务端上半程：从 Binder 入口到 ActivityRecord](#2-一次-startactivity-的服务端上半程从-binder-入口到-activityrecord)
- [3. startActivityInner：flag 裁决与任务的创建、复用](#3-startactivityinnerflag-裁决与任务的创建复用)
- [4. findTask：任务复用匹配器与类型兼容的不对称性](#4-findtask任务复用匹配器与类型兼容的不对称性)
- [5. 五种 launchMode 逐一推导](#5-五种-launchmode-逐一推导)
- [6. 跨进程：从 resume 裁决到客户端 onCreate 与 onNewIntent](#6-跨进程从-resume-裁决到客户端-oncreate-与-onnewintent)
- [7. Task 日志怎么读：stringName 缓存与活字段](#7-task-日志怎么读stringname-缓存与活字段)
- [8. 事故全推演：雅迪车机 Launcher 双实例](#8-事故全推演雅迪车机-launcher-双实例)
- [附录 A：EventLog 标签速查](#附录-aeventlog-标签速查)
- [附录 B：常量与词汇表](#附录-b常量与词汇表)

---

## 0. 导读：材料、口径与一条主线

### 0.1 三棵代码树与一份日志

本文全部结论落在四份材料上，各自的角色与可信度不同，先交代清楚。

**材料一：学习树（主证据源）。** 路径 /home/liang/Project/MyProject/AAOS13_study，HEAD 为 cb9d30f9e。文中所有相对链接（形如 `../../../../AAOS13_study/frameworks/base/...#L 行号`）与不带树名标注的行号，一律指这棵树的 frameworks/base。它是 A13 原生代码加上此前批注战役留下的中文注释，机制与 AOSP android-13 一致。

**材料二：雅迪 framework 树（对照源）。** 路径 /home/liang/Project/Reachauto/YaDi/yadi_android，frameworks/base HEAD 为 8302534c。事故日志出自这台车机的量产构建（内部称 build 476），**该构建与这棵树 HEAD 的严格对应关系未核实**——凡引用此树，文中都标注「雅迪树」并给出行号；凡结论依赖两树一致性，文中都已逐点比对（比对结果见 8.1 节）。

**材料三：雅迪应用源码（旁证源）。** 路径 /home/liang/Project/Reachauto/YaDi/yadea_master，含 Launcher 与 SystemUI 两个应用。本文引用其 AndroidManifest.xml 与两处 Kotlin 源码。

**材料四：事故日志（事实源）。** 文件为 /home/liang/Downloads/log/logcat_2026_11_03_05_43_01_557-103.txt，约 29 万行，覆盖 2026-11-03 05:43:01 起的一次 Monkey 测试。文中引用日志一律给出「行号 + 时间戳 + 原文」。

### 0.2 引文与标注约定

- 源码引文**剔除学习树已有的批注行**（那些以 `//` 开头、如「xxx：……。」与【关键细节】形态的中文注释是此前批注战役的产物，不是 AOSP 原文），只保留 AOSP 代码本体；行号仍按学习树文件计，因此引文行号与链接锚点一致、引文内容却不逐字节等于文件原文——差的就是被剔除的批注行。
- 引文中的 `// …` 表示省略，省略处不含被引用逻辑的分支。
- 雅迪树、应用源码、日志文件在仓外，路径以纯文本写出（不进反引号、不做链接），行号以「文件名:行号」形式标注。
- 外部路径为个人环境绝对路径，换机阅读时以「树名 + 相对路径 + 行号」为准。

### 0.3 事实分级标注

事故章（第 8 章）及全文关键断言使用四级标注：

| 标注 | 含义 |
| --- | --- |
| 【日志事实】 | 日志原文直接可见，给出行号与时间戳 |
| 【源码事实】 | 学习树或雅迪树代码直接可见，给出文件与行号 |
| 【推断】 | 由日志事实与源码事实推导，推导过程在文中给出 |
| 【未核实】 | 有合理怀疑但缺乏直接证据，明确写出缺什么证据 |

### 0.4 一条主线

全文只讲一件事：**一次「让 Launcher 起来」的请求，在系统里走过的每一步**。主线如下，后续章节按此展开：

```text
发起侧（第 1 章）
  AMS.systemReady / UserController / 按键 / SystemUI 自定义
        │  Intent{MAIN + CATEGORY_HOME + NEW_TASK}
        ▼
服务端上半程（第 2 章）
  ATMS.startActivityAsUser → ActivityStarter.execute
        → Request.resolveActivity（组件解析与回写）
        → executeRequest（权限、START 日志、ActivityRecord 构造）
        │  ActivityRecord：launchMode / taskAffinity / activityType 在此定型
        ▼
服务端下半程（第 3、4、5 章）
  startActivityUnchecked → startActivityInner
        → setInitialState / computeLaunchingTaskFlags（flag 裁决）
        → getReusableTask → RootWindowContainer.findTask（复用匹配，第 4 章）
        → 四个出口：新建 Task ／ 复用并清栈 ／ 顶部交付 onNewIntent ／ 普通入栈
        │  五种 launchMode 只是在这条链上走不同分支（第 5 章）
        ▼
跨进程（第 6 章）
  resumeFocusedTasksTopActivities → TaskFragment.resumeTopActivity
        → startPausing（旧顶暂停）→ startSpecificActivity
        → realStartActivityLocked（组装 ClientTransaction）
        → LaunchActivityItem ──Binder──▶ ActivityThread.handleLaunchActivity
        → performLaunchActivity → Activity.attach → onCreate
        （wm_on_create_called 等事件日志在此写出）
```

第 7 章解决读日志的一个大坑（`Task{...}` 打印里哪些字段是活的、哪些是缓存的死值），第 8 章用事故日志把 1-7 章全部机制走一遍。

---

## 1. 「回桌面」从哪里来：HOME 启动的发起侧

Launcher 启动与普通 Activity 启动走的是同一条 `ActivityStarter` 主干，差别只在**发起侧如何组装那个 Intent**、以及**服务端如何把它认定为 HOME 类型**。本章先把发起侧讲完，HOME 类型的认定放在第 2 章（它发生在 `ActivityRecord` 构造时，属于主干的一部分）。

### 1.1 systemReady 的 HOME 分支：开机第一跳

系统开机路径上，HOME 的第一次启动挂在 `ActivityManagerService.systemReady` 的回调里：

[ActivityManagerService.java#L9217](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java#L9217)

```java
boolean isBootingSystemUser = currentUserId == UserHandle.USER_SYSTEM;

// Some systems - like automotive - will explicitly unlock system user then switch
// to a secondary user.
// TODO(b/242195409): this workaround shouldn't be necessary once we move the
// headless-user start logic to UserManager-land.
//
if (isBootingSystemUser && !UserManager.isHeadlessSystemUserMode()) {
    t.traceBegin("startHomeOnAllDisplays");
    mAtmInternal.startHomeOnAllDisplays(currentUserId, "systemReady");
    t.traceEnd();
}
```

逐条拆：

- `isBootingSystemUser`：当前启动用户是不是 user 0（system user）。规则是**只有以 system user 开机时才走这个分支**。
- `UserManager.isHeadlessSystemUserMode()`：AAOS 的关键分叉。headless system user 模式下（车机的典型配置），user 0 只承载系统服务、不承载桌面，开机后会切到一个 secondary user 去显示 UI——所以这里**不**启动 HOME，注释里那句 "Some systems - like automotive - will explicitly unlock system user then switch to a secondary user" 说的就是这个。此时 HOME 由用户启动流程（`UserController` 一侧的 `startUser` 链路）负责触发。
- 这个回调本身由 `SystemServer` 在引导末尾调用（学习树 services/java/com/android/server/SystemServer.java 第 2864 行附近的 `mActivityManagerService.systemReady(...)`）。

**雅迪树在这里动了一刀。** 雅迪树 services/core/java/com/android/server/am/ActivityManagerService.java:8335-8343，把 AOSP 的这段整体注释掉了：

```java
/*
 * Don't call onUserStarting here. UserController will call onUserStarting when start user0.
 * AOSP Code:
if (bootingSystemUser) {
    t.traceBegin("startHomeOnAllDisplays");
    mAtmInternal.startHomeOnAllDisplays(currentUserId, "systemReady");
    t.traceEnd();
}
 */
```

【源码事实·雅迪树】雅迪构建的开机 HOME 不走 `systemReady` 这一跳，改由 `UserController` 启动 user 0 时的 `onUserStarting` 链路触发。这与车机「先起系统用户、再按整车信号决定给谁显示桌面」的启动编排一致。【推断】具体从 `onUserStarting` 到 `startHomeOnDisplay` 的转接在该树的 UserController 一侧完成；本文不展开该链，因为事故日志窗口内（system_server 重启后）**没有任何系统侧 HOME 启动**——第一个 HOME 类请求来自 SystemUI（见 1.7 节与第 8 章），这本身就是【日志事实】。

> **Q：为什么 system_server 单独重启后，系统没有自动补一次 HOME？**
> A：`systemReady` 的 HOME 分支只在开机流程执行一次；`onUserStarting` 挂在用户启动事件上。system_server 崩溃重启走的是 zygote 重启路径，用户并没有重新「start」，所以两条系统侧触发点都不会再响。事故日志正是这个局面：新 system_server 05:44:03 起来后，直到 05:44:10 SystemUI 收到唤醒信号调 `goLauncher`，才出现第一个带 `CATEGORY_HOME` 的 START（日志 122044 行）。这段空窗里屏幕上是什么，取决于重启前的窗口残留与 keyguard 状态，日志未直接打印，【未核实】。

### 1.2 startHomeOnAllDisplays：按显示、按任务区展开

无论哪个触发点，系统侧 HOME 启动最终都汇入 `RootWindowContainer` 的同一条链：

[RootWindowContainer.java#L1495](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1495)

```java
boolean startHomeOnAllDisplays(int userId, String reason) {
    boolean homeStarted = false;
    for (int i = getChildCount() - 1; i >= 0; i--) {
        final int displayId = getChildAt(i).mDisplayId;
        homeStarted |= startHomeOnDisplay(userId, reason, displayId);
    }
    return homeStarted;
}
```

`RootWindowContainer` 的直接子节点是各 `DisplayContent`，倒序遍历即从最上层显示开始。每个显示再展开到它的所有任务显示区（TaskDisplayArea，下称 TDA）：

[RootWindowContainer.java#L1522](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1522)

```java
boolean startHomeOnDisplay(int userId, String reason, int displayId, boolean allowInstrumenting,
        boolean fromHomeKey) {
    // Fallback to top focused display or default display if the displayId is invalid.
    if (displayId == INVALID_DISPLAY) {
        final Task rootTask = getTopDisplayFocusedRootTask();
        displayId = rootTask != null ? rootTask.getDisplayId() : DEFAULT_DISPLAY;
    }

    final DisplayContent display = getDisplayContent(displayId);
    return display.reduceOnAllTaskDisplayAreas((taskDisplayArea, result) ->
                    result | startHomeOnTaskDisplayArea(userId, reason, taskDisplayArea,
                            allowInstrumenting, fromHomeKey),
            false /* initValue */);
}
```

三层结构的含义：**显示（DisplayContent）→ 任务显示区（TDA）→ 根任务（root task）**。HOME 是逐 TDA 启动的，因为每个 TDA 是可以独立承载任务栈的容器；车机一块物理屏可以被拆成多个 TDA（例如主驾区与副驾区各一个），每个区都要有自己的桌面。`reduceOnAllTaskDisplayAreas` 对每个 TDA 调 `startHomeOnTaskDisplayArea`，并按位或收集「是否有区成功起了 HOME」。

### 1.3 startHomeOnTaskDisplayArea：意图组装、解析与发射

这是发起侧的核心方法，做四件事：选意图、解析活动、资格校验、交给启动器。

[RootWindowContainer.java#L1549](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1549)

```java
boolean startHomeOnTaskDisplayArea(int userId, String reason, TaskDisplayArea taskDisplayArea,
        boolean allowInstrumenting, boolean fromHomeKey) {
    // Fallback to top focused display area if the provided one is invalid.
    if (taskDisplayArea == null) {
        final Task rootTask = getTopDisplayFocusedRootTask();
        taskDisplayArea = rootTask != null ? rootTask.getDisplayArea()
                : getDefaultTaskDisplayArea();
    }

    Intent homeIntent = null;
    ActivityInfo aInfo = null;
    if (taskDisplayArea == getDefaultTaskDisplayArea()) {
        homeIntent = mService.getHomeIntent();
        aInfo = resolveHomeActivity(userId, homeIntent);
    } else if (shouldPlaceSecondaryHomeOnDisplayArea(taskDisplayArea)) {
        Pair<ActivityInfo, Intent> info = resolveSecondaryHomeActivity(userId, taskDisplayArea);
        aInfo = info.first;
        homeIntent = info.second;
    }
    if (aInfo == null || homeIntent == null) {
        return false;
    }

    if (!canStartHomeOnDisplayArea(aInfo, taskDisplayArea, allowInstrumenting)) {
        return false;
    }

    // Updates the home component of the intent.
    homeIntent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
    homeIntent.setFlags(homeIntent.getFlags() | FLAG_ACTIVITY_NEW_TASK);
    // Updates the extra information of the intent.
    if (fromHomeKey) {
        homeIntent.putExtra(WindowManagerPolicy.EXTRA_FROM_HOME_KEY, true);
        if (mWindowManager.getRecentsAnimationController() != null) {
            mWindowManager.getRecentsAnimationController().cancelAnimationForHomeStart();
        }
    }
    homeIntent.putExtra(WindowManagerPolicy.EXTRA_START_REASON, reason);

    // Update the reason for ANR debugging to verify if the user activity is the one that
    // actually launched.
    final String myReason = reason + ":" + userId + ":" + UserHandle.getUserId(
            aInfo.applicationInfo.uid) + ":" + taskDisplayArea.getDisplayId();
    mService.getActivityStartController().startHomeActivity(homeIntent, aInfo, myReason,
            taskDisplayArea);
    return true;
}
```

**第一步：选意图。** 默认 TDA 用 `getHomeIntent()`（主桌面），非默认 TDA 且允许放 secondary home 时用 `resolveSecondaryHomeActivity`（副屏桌面，见 1.6 节）。

`getHomeIntent` 本体很短，但每个字段都是后文判定的输入：

[ActivityTaskManagerService.java#L5420](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java#L5420)

```java
Intent getHomeIntent() {
    Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
    intent.setComponent(mTopComponent);
    intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
    if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
        intent.addCategory(Intent.CATEGORY_HOME);
    }
    return intent;
}
```

- `mTopAction`：正常设备上恒为 `Intent.ACTION_MAIN`（只有工厂测试模式会改）。
- `mTopComponent`：正常为 null——**意图不带组件**，靠 PackageManager 按 `MAIN + HOME` 解析出默认桌面。这就是「系统 HOME 意图是隐式意图」的出处。
- `mFactoryTest != FACTORY_TEST_LOW_LEVEL`：非低级工厂测试才加 `CATEGORY_HOME`。低级工厂测试模式下这个意图退化成裸 MAIN，不会命中桌面。
- 注意此处**没有** `FLAG_ACTIVITY_NEW_TASK`；NEW_TASK 是回到 `startHomeOnTaskDisplayArea` 后补的（上面引文 1586 行）。

**第二步：解析活动。** `resolveHomeActivity` 把隐式意图变成具体的 `ActivityInfo`：

[RootWindowContainer.java#L1612](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1612)

```java
ActivityInfo resolveHomeActivity(int userId, Intent homeIntent) {
    final int flags = ActivityManagerService.STOCK_PM_FLAGS;
    final ComponentName comp = homeIntent.getComponent();
    ActivityInfo aInfo = null;
    try {
        if (comp != null) {
            // Factory test.
            aInfo = AppGlobals.getPackageManager().getActivityInfo(comp, flags, userId);
        } else {
            final String resolvedType =
                    homeIntent.resolveTypeIfNeeded(mService.mContext.getContentResolver());
            final ResolveInfo info = AppGlobals.getPackageManager()
                    .resolveIntent(homeIntent, resolvedType, flags, userId);
            if (info != null) {
                aInfo = info.activityInfo;
            }
        }
    } catch (RemoteException e) {
        // ignore
    }

    if (aInfo == null) {
        Slog.wtf(TAG, "No home screen found for " + homeIntent, new Throwable());
        return null;
    }

    aInfo = new ActivityInfo(aInfo);
    aInfo.applicationInfo = mService.getAppInfoForUser(aInfo.applicationInfo, userId);
    return aInfo;
}
```

带组件的分支注释写着 "Factory test."——正常路径永远走 `resolveIntent` 隐式解析。解析失败会打 `Slog.wtf("No home screen found ...")`，这是排查「开机无桌面」时的第一关键字。若解析出多个候选（用户没设默认桌面），PackageManager 会返回 ResolverActivity，后文会看到系统对「解析器不算 HOME」有专门处理。

**第三步：资格校验。** `canStartHomeOnDisplayArea`（[RootWindowContainer.java#L1803](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1803)）拦两类情况：该区不允许放 HOME（如不受信任的虚拟显示），以及仪表（instrumentation）接管期间的特殊限制。校验不过直接返回 false，本区无桌面。

**第四步：回写并发射。** 解析出的组件被 `setComponent` 写回意图，`FLAG_ACTIVITY_NEW_TASK` 被补上。为什么必须补 NEW_TASK？因为发起方是 system_server 自己，不是某个 Activity 的上下文——没有「调用者所在的栈」可以挂靠，只能要求开新任务。这条规则对一切「非 Activity 上下文发起的启动」都成立，第 3 章会看到 `computeLaunchingTaskFlags` 里对普通应用也有同样的强制（那里带一条著名的 `Slog.w` 警告日志）。

随后意图交给 `ActivityStartController.startHomeActivity`，`myReason` 把 user、uid、displayId 拼进原因串，专供 ANR 排查时确认「到底哪一跳真正把桌面拉起来的」。

### 1.4 startHomeActivity：进 ActivityStarter 前的最后一棒

[ActivityStartController.java#L176](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStartController.java#L176)

```java
void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason,
        TaskDisplayArea taskDisplayArea) {
    final ActivityOptions options = ActivityOptions.makeBasic();
    options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
    if (!ActivityRecord.isResolverActivity(aInfo.name)) {
        // The resolver activity shouldn't be put in root home task because when the
        // foreground is standard type activity, the resolver activity should be put on the
        // top of current foreground instead of bring root home task to front.
        options.setLaunchActivityType(ACTIVITY_TYPE_HOME);
    }
    final int displayId = taskDisplayArea.getDisplayId();
    options.setLaunchDisplayId(displayId);
    options.setLaunchTaskDisplayArea(taskDisplayArea.mRemoteToken
            .toWindowContainerToken());

    // The home activity will be started later, defer resuming to avoid unnecessary operations
    // (e.g. start home recursively) when creating root home task.
    mSupervisor.beginDeferResume();
    final Task rootHomeTask;
    try {
        // Make sure root home task exists on display area.
        rootHomeTask = taskDisplayArea.getOrCreateRootHomeTask(ON_TOP);
    } finally {
        mSupervisor.endDeferResume();
    }

    mLastHomeActivityStartResult = obtainStarter(intent, "startHomeActivity: " + reason)
            .setOutActivity(tmpOutRecord)
            .setCallingUid(0)
            .setActivityInfo(aInfo)
            .setActivityOptions(options.toBundle())
            .execute();
    mLastHomeActivityStartRecord = tmpOutRecord[0];
    if (rootHomeTask.mInResumeTopActivity) {
        // If we are in resume section already, home activity will be initialized, but not
        // resumed (to avoid recursive resume) and will stay that way until something pokes it
        // again. We need to schedule another resume.
        mSupervisor.scheduleResumeTopActivities();
    }
}
```

四个细节，每个都对着后文的一个机制：

1. `options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN)`：HOME 永远全屏窗口模式。这决定了第 3 章 `alwaysCreateRootTask` 判定时的 windowingMode 输入。
2. `options.setLaunchActivityType(ACTIVITY_TYPE_HOME)`，但 **ResolverActivity 除外**——注释讲得很直白：桌面上弹出「选择哪个桌面」的解析器时，它应该盖在当前前台之上，而不是把 root home task 顶到前台。这是「意图是 HOME 意图」与「类型是 HOME 类型」分离的第一个例子；第 2 章 `setActivityType` 里还有第二个（`!isResolverOrDelegateActivity()` 条件）。
3. `getOrCreateRootHomeTask(ON_TOP)` 在启动活动**之前**先把 root home task 备好，且用 `beginDeferResume` 与 `endDeferResume` 包住——建根任务本身可能触发 resume 调度，这里明确推迟，防止「起 HOME 的过程递归再起 HOME」。
4. `.setCallingUid(0)`：系统侧 HOME 启动的调用 uid 记为 0（root）。第 2 章会看到 `canLaunchHomeActivity` 对 uid 0 与 SYSTEM_UID 都放行——这是给系统侧留的门。

`getOrCreateRootHomeTask` 本体：

[TaskDisplayArea.java#L1724](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskDisplayArea.java#L1724)

```java
Task getOrCreateRootHomeTask(boolean onTop) {
    Task homeTask = getRootHomeTask();
    // Take into account if this TaskDisplayArea can have a home task before trying to
    // create the root task
    if (homeTask == null && canHostHomeTask()) {
        homeTask = createRootTask(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, onTop);
    }
    return homeTask;
}
```

`canHostHomeTask` 挡掉不受信任的显示区（虚拟屏、投屏），所以外接屏不会有自己的桌面。`createRootTask` 直接走 `Task.Builder`，`ACTIVITY_TYPE_HOME` 在这里就写进根任务——**root home task 是系统里少数几个「出生即带类型」的任务**，这个类型随后成为 HOME 活动挂载点的判定依据（第 3 章），也成为事故日志里 `rootTaskId=1` 的那个「1」。

### 1.5 Task 1 的出生：WMS 初始化时就建好了

上面 1.4 的 `getOrCreateRootHomeTask` 只是「取或建」；实际上默认显示的 root home task 在 WindowManager 初始化阶段就建好了：

[RootWindowContainer.java#L1386](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1386)

```java
void setWindowManager(WindowManagerService wm) {
    mWindowManager = wm;
    mDisplayManager = mService.mContext.getSystemService(DisplayManager.class);
    mDisplayManager.registerDisplayListener(this, mService.mUiHandler);
    mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);

    final Display[] displays = mDisplayManager.getDisplays();
    for (int displayNdx = 0; displayNdx < displays.length; ++displayNdx) {
        final Display display = displays[displayNdx];
        final DisplayContent displayContent = new DisplayContent(display, this);
        addChild(displayContent, POSITION_BOTTOM);
        if (displayContent.mDisplayId == DEFAULT_DISPLAY) {
            mDefaultDisplay = displayContent;
        }
    }

    final TaskDisplayArea defaultTaskDisplayArea = getDefaultTaskDisplayArea();
    defaultTaskDisplayArea.getOrCreateRootHomeTask(ON_TOP);
    positionChildAt(POSITION_TOP, defaultTaskDisplayArea.mDisplayContent,
            false /* includingParents */);
}
```

为每块显示建 `DisplayContent` 之后，立刻对默认 TDA 调 `getOrCreateRootHomeTask(ON_TOP)`。**这就是事故日志里 05:44:03.755 那条 `wm_task_created: [1,-1]` 的出处**【推断，机制见 7.4 节】：新 system_server 起来，WMS 初始化走到这里，root home task（Task 1）诞生——此时它是个空容器，没有任何 Activity，`wm_task_created` 的第二个字段 -1 是构造期打印的固定值（第 7 章展开）。

TDA 侧对 HOME 类型根任务还有一个直达缓存：

[TaskDisplayArea.java#L225](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskDisplayArea.java#L225)

```java
Task getRootTask(int windowingMode, int activityType) {
    if (activityType == ACTIVITY_TYPE_HOME) {
        return mRootHomeTask;
    }
    if (windowingMode == WINDOWING_MODE_PINNED) {
        return mRootPinnedTask;
    }
    return getRootTask(rootTask -> {
        if (activityType == ACTIVITY_TYPE_UNDEFINED
                && windowingMode == rootTask.getWindowingMode()) {
            // Passing in undefined type means we want to match the topmost root task with the
            // windowing mode.
            return true;
        }
        return rootTask.isCompatible(windowingMode, activityType);
    });
}
```

HOME 与 PINNED 各有一个成员变量直取（`mRootHomeTask`、`mRootPinnedTask`），不做遍历——一个 TDA 至多一个 home 根任务、一个 PIP 根任务，这是容器树给两类特殊任务留的专用车位。第 3 章选根任务时会回到这个方法。

### 1.6 多屏车机：SECONDARY_HOME 的解析优先级

非默认 TDA（典型如车机副屏、乘客屏）不直接用主桌面，而是走 secondary home 解析。方法头注释把优先级写得很清楚：

[RootWindowContainer.java#L1537](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1537)

```java
/**
 * This starts home activity on display areas that can have system decorations based on
 * displayId - default display area always uses primary home component.
 * For secondary display areas, the home activity must have category SECONDARY_HOME and then
 * resolves according to the priorities listed below.
 * - If default home is not set, always use the secondary home defined in the config.
 * - Use currently selected primary home activity.
 * - Use the activity in the same package as currently selected primary home activity.
 * If there are multiple activities matched, use first one.
 * - Use the secondary home defined in the config.
 */
```

实现里最关键的一段：

[RootWindowContainer.java#L1649](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L1649)

```java
Pair<ActivityInfo, Intent> resolveSecondaryHomeActivity(int userId,
        @NonNull TaskDisplayArea taskDisplayArea) {
    if (taskDisplayArea == getDefaultTaskDisplayArea()) {
        throw new IllegalArgumentException(
                "resolveSecondaryHomeActivity: Should not be default task container");
    }
    // Resolve activities in the same package as currently selected primary home activity.
    Intent homeIntent = mService.getHomeIntent();
    ActivityInfo aInfo = resolveHomeActivity(userId, homeIntent);
    if (aInfo != null) {
        if (ResolverActivity.class.getName().equals(aInfo.name)) {
            // Always fallback to secondary home component if default home is not set.
            aInfo = null;
        } else {
            // Look for secondary home activities in the currently selected default home
            // package.
            homeIntent = mService.getSecondaryHomeIntent(aInfo.applicationInfo.packageName);
            final List<ResolveInfo> resolutions = resolveActivities(userId, homeIntent);
            ...
        }
    }
    ...
}
```

语义：副屏桌面必须声明 `CATEGORY_SECONDARY_HOME`；解析时优先在**主桌面包内**找 secondary home 活动（主桌面是雅迪 Launcher，副屏就优先用雅迪 Launcher 自己声明的 secondary 入口），找不到再退回 config 里配置的系统 secondary home（`config_secondaryHomePackage`）。主桌面没设默认（解析出 ResolverActivity）时，直接走 config 兜底。对车机开发的实际意义：**一块副屏要不要独立桌面、桌面是谁，由 manifest 的 SECONDARY_HOME 声明与 overlay 配置共同决定**，不是自动复制主屏。

### 1.7 非 AOSP 发起方：雅迪 SystemUI 的 goLauncher

AOSP 的 HOME 发起点（systemReady、HOME 键、keyguard 消失、`startHomeOnEmptyDisplays` 等）之外，车机厂商常有自己的「回桌面」入口。雅迪的入口在 SystemUI 的数字钥匙模块里。

先看意图组装，SystemUI 应用源码 application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt:84-95：

```kotlin
fun goLauncher(context: Context) {
    LogUtils.d(TAG, "goLauncher:::#")

    try {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        LogUtils.e(TAG, "goLauncher::: ${e.message}")
    }
}
```

对照 1.3 节的 `getHomeIntent`：同样是 `MAIN + CATEGORY_HOME + NEW_TASK` 的隐式意图，差别只在没带 `RESET_TASK_IF_NEEDED`，也没走 `resolveHomeActivity` 的预解析——它直接 `context.startActivity`，把隐式意图丢给 Binder 对面的 ATMS，解析发生在服务端（第 2 章 2.2 节）。**从服务端视角，这个请求与系统 HOME 启动几乎不可区分**；唯一的身份差异是调用方 uid（SystemUI 是 uid 1000）与 flag 组合。

再看触发点，application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyManager.kt:241-278：

```kotlin
fun handleWakeUpSignal(wakeType: Int) {
    LogUtils.i(TAG, "handleWakeUpSignal: wakeType=0x${wakeType.toString(16)}")
    ...
    when (wakeType) {
        DigitalKeyConstants.WAKE_UP_SHOW_LOCK -> {
            // 收到手把硬按键唤醒信号，显示密码解锁界面
            showKeyguard()
        }
        DigitalKeyConstants.WAKE_UP_SHOW_LAUNCHER, DigitalKeyConstants.WAKE_UP_SHOW_NULL -> {
            // 正常进入homelauncher，如果当前有锁屏则隐藏
            hideKeyguardIfShowing()
            // 信号切回 Launcher 时，恢复数字钥匙信号拉起前保存的原始锁屏类型
            restoreSettingsLockTypeIfNeeded()
            goLauncher()
        }
        else -> {
            LogUtils.w(TAG, "handleWakeUpSignal: unknown wakeType=$wakeType")
        }
    }
}
```

整车 MCU 通过数字钥匙通道送来唤醒信号：`wakeType=0x1` 弹密码锁屏，`wakeType=0x2`（WAKE_UP_SHOW_LAUNCHER）回桌面。事故日志 122035 行的 `handleWakeUpSignal: wakeType=0x2` 与 122037 行的 `goLauncher:::#` 正是这条链【日志事实】。

这个自定义发起点对全文的意义：**Launcher 的启动请求不止一种「意图形状」**。同一个 MainActivity，可以被人以 HOME 类别拉起（本节），也可以被人以 LAUNCHER 类别拉起（Monkey、桌面图标点击都是这种）——两种拉起在服务端被认定成**不同的 activityType**，而 activityType 直接参与任务复用匹配。这是第 4 章的主定理，也是第 8 章事故的根因；此处先埋下第一颗钉子：`goLauncher` 用的是 HOME 类别。

### 1.8 本章小结与 Q&A

发起侧全景（按本文口径从「最系统」到「最应用」排列）：

| 发起点 | 意图形状 | 触发时机 |
| --- | --- | --- |
| AMS.systemReady（AOSP） | getHomeIntent：MAIN+HOME，NEW_TASK 后补 | 开机，非 headless 且 system user |
| UserController 链（雅迪树实际路径） | 同上 | 用户启动事件 |
| RWC.startHomeOnEmptyDisplays | 同上 | 显示变空（START_HOME_MSG） |
| HOME 键 / keyguard 消失 | 同上，可带 EXTRA_FROM_HOME_KEY | 用户交互 |
| 雅迪 SystemUI goLauncher | MAIN+HOME+NEW_TASK，手写 | 整车唤醒信号 0x2 |
| Monkey / 图标点击 | MAIN+**LAUNCHER**+NEW_TASK\|RESET_TASK_IF_NEEDED | 测试 / 用户 |

> **Q1：HOME 意图为什么总是隐式的？**
> A：桌面可被用户更换（设置默认 HOME 应用），系统侧不能写死组件。`resolveHomeActivity` 每次现解析，解析结果写回意图（1.3 节第四步），保证这一次启动的确定性；下次再按新默认解析。带组件的分支只服务工厂测试。
>
> **Q2：`startHomeOnAllDisplays` 会对每块屏都起一个 Launcher 进程吗？**
> A：不一定。每个 TDA 起的是「一个 HOME Activity 记录」；同包 Activity 默认同进程，所以多屏 HOME 通常共用一个进程、多个 Activity 实例。副屏走 SECONDARY_HOME 时甚至可能是不同组件。
>
> **Q3：为什么 1.4 节要先建 root home task 再启动活动，而不是让 ActivityStarter 顺手建？**
> A：因为 HOME 活动的落位规则是「挂进已存在的 home 根任务」（第 3 章 `alwaysCreateRootTask` 对 HOME 返回 false），ActivityStarter 只找不建这种特殊根任务；先备好容器，启动时才有车位可停。同时 `beginDeferResume` 包住建任务过程，避免容器变化触发一轮无谓的 resume 调度。
>
> **Q4：SystemUI 的 `goLauncher` 与系统 HOME 启动，服务端能区分吗？**
> A：能，但只体现在三处：调用 uid/pid（START 日志的 `from uid` 字段）、flag 组合（有没有 RESET_TASK_IF_NEEDED）、以及 reason 串（系统路径带 `startHomeActivity:` 前缀）。意图形状与解析结果相同，activityType 认定也相同（都满足 HOME 三条件，见 2.5 节）。**对任务复用而言，两者等价**——这是第 8 章推演的前提之一。

下一章进入主干：这个 Intent 过了 Binder 之后，服务端如何一步步把它变成一条 `ActivityRecord`，以及 HOME/STANDARD 类型在哪一行代码上定型。

---

## 2. 一次 startActivity 的服务端上半程：从 Binder 入口到 ActivityRecord

上一章的所有发起点，殊途同归落到同一个 Binder 入口。本章沿 `ATMS.startActivityAsUser → ActivityStarter.execute → executeRequest → ActivityRecord 构造` 走一遍，重点回答三个问题：**组件是何时被解析并写回意图的**、**`START u0 {...}` 日志的每个字段从哪来**、**activityType（HOME/STANDARD）在哪一行定型**。第三个问题是全文最重要的机制点之一。

### 2.1 ATMS.startActivityAsUser 与 obtainStarter 链

[ActivityTaskManagerService.java#L1303](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java#L1303)

```java
private int startActivityAsUser(IApplicationThread caller, String callingPackage,
        @Nullable String callingFeatureId, Intent intent, String resolvedType,
        IBinder resultTo, String resultWho, int requestCode, int startFlags,
        ProfilerInfo profilerInfo, Bundle bOptions, int userId, boolean validateIncomingUser) {

    final SafeActivityOptions opts = SafeActivityOptions.fromBundle(bOptions);
    // A quick path (skip general intent/task resolving) to start recents animation if the
    // recents (or home) activity is available in background.
    if (opts != null && opts.getOriginalOptions().getTransientLaunch()
            && isCallerRecents(Binder.getCallingUid())) {
        ...
    }

    assertPackageMatchesCallingUid(callingPackage);
    enforceNotIsolatedCaller("startActivityAsUser");
    ...
    userId = getActivityStartController().checkTargetUser(userId, validateIncomingUser,
            Binder.getCallingPid(), Binder.getCallingUid(), "startActivityAsUser");

    // TODO: Switch to user app stacks here.
    return getActivityStartController().obtainStarter(intent, "startActivityAsUser")
            .setCaller(caller)
            .setCallingPackage(callingPackage)
            .setCallingFeatureId(callingFeatureId)
            .setResolvedType(resolvedType)
            .setResultTo(resultTo)
            .setResultWho(resultWho)
            .setRequestCode(requestCode)
            .setStartFlags(startFlags)
            .setProfilerInfo(profilerInfo)
            .setActivityOptions(opts)
            .setUserId(userId)
            .execute();
}
```

要点：

- 入口先做三道身份闸：`assertPackageMatchesCallingUid`（声称的包名必须属于调用 uid）、`enforceNotIsolatedCaller`（隔离进程不许启动活动）、`checkTargetUser`（跨用户启动权限）。
- `obtainStarter` 造一个 `ActivityStarter`，把所有参数灌进它的 `Request` 结构，最后 `execute()`。`ActivityStarter` 是一次性的：一次启动一个实例，状态全在 `mRequest` 与成员变量里。
- **注意链上没有 `setComponentSpecified`。** 这个字段与 HOME 类型认定直接相关，2.6 节专门讲它的真实来源。
- recents 的 `TransientLaunch` 快速路径是给「最近任务动画」开的专线，与本文主线无关，略过。

`execute()` 的前半段做两件事——拒绝带文件描述符的意图（防 fd 泄漏），以及**预解析**：

[ActivityStarter.java#L693](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L693)

```java
int execute() {
    try {
        onExecutionStarted();

        // Refuse possible leaked file descriptors
        if (mRequest.intent != null && mRequest.intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        final LaunchingState launchingState;
        synchronized (mService.mGlobalLock) {
            final ActivityRecord caller = ActivityRecord.forTokenLocked(mRequest.resultTo);
            final int callingUid = mRequest.realCallingUid == Request.DEFAULT_REAL_CALLING_UID
                    ?  Binder.getCallingUid() : mRequest.realCallingUid;
            launchingState = mSupervisor.getActivityMetricsLogger().notifyActivityLaunching(
                    mRequest.intent, caller, callingUid);
        }

        // If the caller hasn't already resolved the activity, we're willing
        // to do so here. ...
        if (mRequest.activityInfo == null) {
            mRequest.resolveActivity(mSupervisor);
        }
        ...
```

`notifyActivityLaunching` 是启动耗时统计（`am_activity_launch_time` 一类）的起点。`mRequest.resolveActivity` 只在调用方没预先解析时才做——系统内部路径（如 1.4 节 `startHomeActivity` 已经带着 `setActivityInfo(aInfo)` 进来）会跳过。

### 2.2 组件解析与回写：隐式意图何时长出 cmp=

`Request.resolveActivity`（[ActivityStarter.java#L523](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L523)）补齐调用身份后，转给 supervisor 做真正的解析：

[ActivityTaskSupervisor.java#L698](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L698)

```java
ActivityInfo resolveActivity(Intent intent, ResolveInfo rInfo, int startFlags,
        ProfilerInfo profilerInfo) {
    final ActivityInfo aInfo = rInfo != null ? rInfo.activityInfo : null;
    if (aInfo != null) {
        // Store the found target back into the intent, because now that
        // we have it we never want to do this again.  For example, if the
        // user navigates back to this point in the history, we should
        // always restart the exact same activity.
        intent.setComponent(new ComponentName(
                aInfo.applicationInfo.packageName, aInfo.name));
        ...
```

**解析结果被 `setComponent` 写回原意图。** 注释给出了理由：解析只做一次，历史回放时必须命中同一个活动。这个回写有一个读日志时极易踩的后果：

> **Q：日志里 `START u0 {... cmp=com.yadea.launcher/.function.main.view.MainActivity}` 带 cmp=，能不能说明调用方发的是显式意图？**
> A：**不能。** `START` 日志在解析之后打印（见 2.3 节），隐式意图经回写后同样带 cmp=。事故日志里 SystemUI `goLauncher` 的请求就是隐式意图（Kotlin 源码没设组件），START 行照样打出 cmp=（日志 122044 行）。要区分显式/隐式，得看调用方源码或 `componentSpecified` 字段的行为（2.6 节），不能看这一行日志。

### 2.3 executeRequest：身份归一、START 日志与权限闸

`execute()` 后半段进入 `executeRequest`，这是服务端上半程的主体：

[ActivityStarter.java#L909](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L909)

```java
private int executeRequest(Request request) {
    if (TextUtils.isEmpty(request.reason)) {
        throw new IllegalArgumentException("Need to specify a reason.");
    }
    mLastStartReason = request.reason;
    mLastStartActivityTimeMs = System.currentTimeMillis();
    mLastStartActivityRecord = null;

    final IApplicationThread caller = request.caller;
    Intent intent = request.intent;
    ...
    WindowProcessController callerApp = null;
    if (caller != null) {
        callerApp = mService.getProcessController(caller);
        if (callerApp != null) {
            callingPid = callerApp.getPid();
            callingUid = callerApp.mInfo.uid;
        } else {
            Slog.w(TAG, "Unable to find app for caller " + caller + " (pid=" + callingPid
                    + ") when starting: " + intent.toString());
            err = START_PERMISSION_DENIED;
        }
    }

    final int userId = aInfo != null && aInfo.applicationInfo != null
            ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;
    if (err == ActivityManager.START_SUCCESS) {
        Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                + "} from uid " + callingUid);
    }
    ...
```

这条 `Slog.i` 就是全文反复引用的 **START 日志**。逐字段拆：

- `u0`：目标 userId，取自解析出的 `ActivityInfo.applicationInfo.uid` 的用户段——**是活动归属的用户，不是调用者的用户**。
- `{...}`：`intent.toShortString(true, true, true, false)`，四个 true/false 依次控制打印 action、categories、component、extras。所以这一行里 `act=`、`cat=[...]`、`cmp=`、`flg=` 都是意图字段；`flg=` 是十进制还是十六进制取决于 toShortString 的实现，logcat 里看到的是 `flg=0x10200000` 十六进制形态。
- `from uid`：调用方 uid。经过 `callerApp` 归一：Binder 调用方若能对应到进程记录，用进程的 uid（这就是为什么 SystemUI 的请求打 `from uid 1000`）；系统内部路径（如 systemReady 的 HOME 启动）caller 为 null 时走 `setCallingUid` 预设值。事故日志里 Monkey 的请求打 `from uid 0, pid 13116`——uid 0 是 root，Monkey 以 root 身份跑【日志事实】。
- 事故日志里 START 行末尾的 `, pid 13116` 后缀是**雅迪树版本的日志格式**（yadi 树 ActivityStarter.java:909-910 多拼了 `", pid " + callingPid`），学习树这一行没有 pid 字段——两树日志版本有差异，字段语义一致【源码事实·两树对照】。调用方进程找不到时打 `Unable to find app for caller` 并直接 `START_PERMISSION_DENIED`。

`START` 日志之后是一串资格检查：后台启动限制 `shouldAbortBackgroundActivityStart`（[ActivityStarter.java#L1353](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1353)，Android 10 引入的 BAL 限制主入口，豁免名单含 uid 1000、持有 START_ACTIVITIES_FROM_BACKGROUND 权限者、 Companion 设备等）、挂起包检查、重权限检查等。全部通过后，构造 `ActivityRecord`。

### 2.4 ActivityRecord 的诞生：launchMode 与 taskAffinity 定型

[ActivityStarter.java#L1239](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1239)

```java
final ActivityRecord r = new ActivityRecord.Builder(mService)
        .setCaller(callerApp)
        .setLaunchedFromPid(callingPid)
        .setLaunchedFromUid(callingUid)
        .setLaunchedFromPackage(callingPackage)
        .setLaunchedFromFeature(callingFeatureId)
        .setIntent(intent)
        .setResolvedType(resolvedType)
        .setActivityInfo(aInfo)
        .setConfiguration(mService.getGlobalConfiguration())
        .setResultTo(resultRecord)
        .setResultWho(resultWho)
        .setRequestCode(requestCode)
        .setComponentSpecified(request.componentSpecified)
        .setRootVoiceInteraction(voiceSession != null)
        .setActivityOptions(checkedOptions)
        .setSourceRecord(sourceRecord)
        .build();
```

`ActivityRecord` 是服务端为一个「将要存在的 Activity 实例」建立的档案：token、intent、info、调用方身份、launchMode、taskAffinity、activityType 全在这里定。构造函数里的三步关键赋值：

[ActivityRecord.java#L2134](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2134)

```java
info.taskAffinity = computeTaskAffinity(info.taskAffinity, info.applicationInfo.uid,
        launchMode);
taskAffinity = info.taskAffinity;
```

[ActivityRecord.java#L2228](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2228)

```java
static String computeTaskAffinity(String affinity, int uid, int launchMode) {
    final String uidStr = Integer.toString(uid);
    if (affinity != null && !affinity.startsWith(uidStr)) {
        affinity = uidStr + (launchMode == LAUNCH_SINGLE_INSTANCE ? "-si:" : ":") + affinity;
    }
    return affinity;
}
```

- `affinity` 的默认值是 manifest 的 `taskAffinity` 属性，缺省为包名。雅迪 Launcher 没写 `taskAffinity`，所以缺省是包名 com.yadea.launcher。
- 前缀规则：affinity 不以 uid 开头时，强制加 `uid:` 前缀（singleInstance 是 `uid-si:` 前缀）。**Launcher 的 uid 是 1000**（系统应用），所以算出来的 affinity 是 `1000:com.yadea.launcher`——事故日志里每个 Task 打印的 `A=1000:com.yadea.launcher` 就是这一行的产物【源码事实 + 日志事实互证】。
- uid 前缀的作用是隔离：不同应用即使写了相同的 taskAffinity 字符串，加前缀后也不同，防止跨应用劫持任务。`-si:` 变体让 singleInstance 活动的 affinity 与普通活动天然不同，配合第 5 章的特殊查找路径。

[ActivityRecord.java#L2169](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2169)

```java
launchMode = aInfo.launchMode;

// Don't move below setActivityType since it triggers onConfigurationChange ->
// resolveOverrideConfiguration that requires having mLetterboxUiController initialised.
mLetterboxUiController = new LetterboxUiController(mWmService, this);
...
setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);
```

`launchMode` 直接抄 manifest 解析结果，服务端不改写它。雅迪 Launcher 的 MainActivity 声明 `android:launchMode="singleTask"`（应用源码 application/Launcher/src/main/AndroidManifest.xml:87）【源码事实·应用树】，所以 A、B 两个实例的 `launchMode` 都是 `LAUNCH_SINGLE_TASK`——记住这一点，第 8 章的悖论（singleTask 为什么没保证单实例）全靠它。

### 2.5 setActivityType：HOME 是怎么被认定的

[ActivityRecord.java#L3001](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L3001)

```java
private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent,
        ActivityOptions options, ActivityRecord sourceRecord) {
    int activityType = ACTIVITY_TYPE_UNDEFINED;
    if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord))
            && isHomeIntent(intent) && !isResolverOrDelegateActivity()) {
        // This sure looks like a home activity!
        activityType = ACTIVITY_TYPE_HOME;

        if (info.resizeMode == RESIZE_MODE_FORCE_RESIZEABLE
                || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
            // We only allow home activities to be resizeable if they explicitly requested it.
            info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        }
    } else if (mAtmService.getRecentTasks().isRecentsComponent(mActivityComponent,
            info.applicationInfo.uid)) {
        activityType = ACTIVITY_TYPE_RECENTS;
    } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_ASSISTANT
            && canLaunchAssistActivity(launchedFromPackage)) {
        activityType = ACTIVITY_TYPE_ASSISTANT;
    } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_DREAM
            && mAtmService.canLaunchDreamActivity(launchedFromPackage)
            && DreamActivity.class.getName() == info.name) {
        activityType = ACTIVITY_TYPE_DREAM;
    }
    setActivityType(activityType);
}
```

初始值是 `ACTIVITY_TYPE_UNDEFINED`——**注意这里没有 STANDARD 分支**，四个 else-if 全不命中时，记录带着 UNDEFINED 离开构造函数。HOME 认定要同时过三关：

**第一关：`!componentSpecified || canLaunchHomeActivity(...)`。** 意图没指定组件，直接过；指定了组件，则要求调用方有「拉 HOME 的特权」：

[ActivityRecord.java#L2973](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2973)

```java
boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
    if (uid == SYSTEM_UID || uid == 0) {
        // System process can launch home activity.
        return true;
    }
    // Allow the recents component to launch the home activity.
    final RecentTasks recentTasks = mTaskSupervisor.mService.getRecentTasks();
    if (recentTasks != null && recentTasks.isCallerRecents(uid)) {
        return true;
    }
    // Resolver or system chooser activity can launch home activity.
    return sourceRecord != null && sourceRecord.isResolverOrDelegateActivity();
}
```

放行名单：SYSTEM_UID（1000）、root（0）、recents 组件、解析器/选择器活动。这一关的用途是防「显式指定组件冒充 HOME」——但对 uid 0 与 1000 完全放行，所以 **Monkey（uid 0）与 SystemUI（uid 1000）都过得去**，真正的分叉不在这里。

**第二关：`isHomeIntent(intent)`。** 意图形状必须是「纯 HOME」：

[ActivityRecord.java#L2953](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2953)

```java
private boolean isHomeIntent(Intent intent) {
    return ACTION_MAIN.equals(intent.getAction())
            && (intent.hasCategory(CATEGORY_HOME)
            || intent.hasCategory(CATEGORY_SECONDARY_HOME))
            && intent.getCategories().size() == 1
            && intent.getData() == null
            && intent.getType() == null;
}
```

四个条件缺一不可：MAIN 动作、带 HOME 或 SECONDARY_HOME 类别、**类别集合大小为 1**（HOME 与 LAUNCHER 同时带上就不算）、无 data、无 type。对照两类请求：

- 雅迪 `goLauncher`：MAIN + 仅 CATEGORY_HOME，无 data/type → **是 HOME 意图**。
- Monkey / 图标点击：MAIN + 仅 CATEGORY_LAUNCHER → `hasCategory(CATEGORY_HOME)` 不成立 → **不是 HOME 意图**。旁边还有个 `isMainIntent`（[ActivityRecord.java#L2963](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L2963)）专门描述这种 MAIN+LAUNCHER 形状，但它不参与类型认定。
- 雅迪 Launcher 的 manifest 把 HOME、LAUNCHER、DEFAULT 三个 category 声明在**同一个 intent-filter**（priority=1000）里【源码事实·应用树，AndroidManifest.xml:89-95】——这决定同一个 MainActivity 既能被 HOME 意图解析到、也能被 LAUNCHER 意图解析到。**manifest 的类别声明不改变活动的 launchMode，也不预先决定 activityType；类型完全由「这一次请求的意图形状 + 调用方」在运行时认定。**

**第三关：`!isResolverOrDelegateActivity()`。** 解析器不算 HOME，与 1.4 节 `startHomeActivity` 不给 Resolver 设 HOME options 呼应：HOME 类型意味着「落进 home 根任务、参与桌面语义」，而解析器只是临时盖在前台之上。

三关全过 → `ACTIVITY_TYPE_HOME`，顺带把非显式声明的 resizeable 抹掉（HOME 不许自由缩放）。任何一关不过 → 落入后续 else-if（RECENTS/ASSISTANT/DREAM 各有专门认定），全不沾边则**保持 UNDEFINED**。

### 2.6 componentSpecified 的真相：Binder 路径上它恒为 false

第一关里的 `componentSpecified` 值得单独一节，因为它的行为与直觉相反。全树检索它的写入点，只有四处：

| 写入点 | 位置 | 值 |
| --- | --- | --- |
| `Request.reset` | [ActivityStarter.java#L454](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L454) | false（每次复用 Request 时复位） |
| heavyweight switcher 换道 | [ActivityStarter.java#L856](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L856) | true |
| `startActivities` 多启动 | ActivityStartController.java:435、482 | `intent.getComponent() != null` |
| 任务/活动记录恢复 | ActivityRecord.java:10043、10091，Task.java:5841 | 从 XML 属性读 |

`startActivities` 里的写法（ActivityStartController.java:435-437）值得引用，因为它把「为什么要在解析前取值」写成了注释：

```java
// Get the flag earlier because the intent may be modified in resolveActivity below.
final boolean componentSpecified = intent.getComponent() != null;
// Don't modify the client's object!
intent = new Intent(intent);
```

而 **2.1 节的单次 `startActivityAsUser → obtainStarter` 链上没有 `setComponentSpecified`**，`ActivityStarter.setIntent`（[ActivityStarter.java#L3248](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L3248)）也只赋意图不碰这个字段：

```java
ActivityStarter setIntent(Intent intent) {
    mRequest.intent = intent;
    return this;
}
```

结论【源码事实】：**在 A13 的单次 startActivity 路径上（应用 Binder 调用与系统内部 obtainStarter 调用都算），`componentSpecified` 恒为 false，与意图是否显式无关。** 它只在多活动启动、heavyweight 切换、记录恢复三条旁路上才可能为 true。

推论有两层：

1. 对 Binder 路径，`setActivityType` 第一关的 `!componentSpecified` 恒真、短路成立，`canLaunchHomeActivity` 根本不会被调用。**HOME 认定的实际判据只剩意图形状（isHomeIntent）与解析器排除。** 这意味着 A13 上任何应用发一个 `MAIN+CATEGORY_HOME` 的意图，其记录都会被认定为 HOME 类型（能不能真的启动成功另说，还有 BAL 限制等闸门）——类型认定与启动许可是两套机制。
2. Monkey 的显式意图（cmp= 直指 MainActivity）同样 `componentSpecified=false`；它没成为 HOME 的原因**纯粹是第二关**：类别是 LAUNCHER 不是 HOME。

> **Q：那第一关的 `canLaunchHomeActivity` 岂不是形同虚设？**
> A：在单次启动路径上确实是。它生效的场合是 `startActivities`（多启动会把显式组件如实标记）与恢复路径。读 AOSP 代码时按「字段的所有写入点」检索，而不是按直觉假设它何时被设——这个字段的真实覆盖面比名字暗示的窄得多。

### 2.7 STANDARD 的延迟定型：本文最重要的结论之一

2.5 节说了 HOME 在构造时定型、其余保持 UNDEFINED。那么普通应用的 STANDARD 类型何时出现？答案在任务侧，`Task.onDescendantActivityAdded`：

[Task.java#L1580](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1580)

```java
/** Called when an {@link ActivityRecord} is added as a descendant */
void onDescendantActivityAdded(boolean hadActivity, int activityType, ActivityRecord r) {
    warnForNonLeafTask("onDescendantActivityAdded");

    // Only set this based on the first activity
    if (!hadActivity) {
        if (r.getActivityType() == ACTIVITY_TYPE_UNDEFINED) {
            // Normally non-standard activity type for the activity record will be set when the
            // object is created, however we delay setting the standard application type until
            // this point so that the task can set the type for additional activities added in
            // the else condition below.
            r.setActivityType(ACTIVITY_TYPE_STANDARD);
        }
        setActivityType(r.getActivityType());
        isPersistable = r.isPersistable();
        mCallingUid = r.launchedFromUid;
        mCallingPackage = r.launchedFromPackage;
        mCallingFeatureId = r.launchedFromFeatureId;
        // Clamp to [1, max].
        maxRecents = Math.min(Math.max(r.info.maxRecents, 1),
                ActivityTaskManager.getMaxAppRecentsLimitStatic());
    } else {
        // Otherwise make all added activities match this one.
        r.setActivityType(activityType);
    }
    ...
```

注释把设计意图写透了：**非 STANDARD 的类型（HOME/RECENTS/ASSISTANT/DREAM）在记录创建时就定；STANDARD 故意拖到入栈这一刻才定**。为什么拖？看 else 分支——后续活动入栈时直接抄任务类型（`r.setActivityType(activityType)`），任务类型由第一个活动定。如果 STANDARD 在构造时就写死，「任务给后续活动统一类型」的机制就没有操作空间了。

由此得到一条贯穿全文的时间线规则：

> **【核心结论一】一个新 ActivityRecord 在 `findTask` 匹配期间的 activityType：HOME 请求是 HOME，其余一切请求是 UNDEFINED（不是 STANDARD）。STANDARD 要等它被放进任务时才补写。**

这条规则的直接后果要到第 4 章才展开（UNDEFINED 与所有类型兼容，而 HOME 与 STANDARD 互斥），这里先把它的两个即时影响说清：

- 新建任务的类型跟着第一个活动走：第一个活动是 HOME → 任务 HOME；是 UNDEFINED → 补成 STANDARD → 任务 STANDARD（第 3 章 3.6 节）。
- 日志打印的 `type=` 字段可能是过期缓存（第 7 章）；判断记录的真实类型要回到「构造时的意图形状」，判断任务的真实类型要回到「第一个入栈活动的类型」。

### 2.8 startActivityUnchecked：进入不回退区

`executeRequest` 的终点是把记录交给 `startActivityUnchecked`：

[ActivityStarter.java#L1279](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1279)

```java
mLastStartActivityResult = startActivityUnchecked(r, sourceRecord, voiceSession,
        request.voiceInteractor, startFlags, true /* doResume */, checkedOptions,
        inTask, inTaskFragment, restrictedBgActivity, intentGrants);
```

[ActivityStarter.java#L1720](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1720)

```java
private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        int startFlags, boolean doResume, ActivityOptions options, Task inTask,
        TaskFragment inTaskFragment, boolean restrictedBgActivity,
        NeededUriGrants intentGrants) {
    int result = START_CANCELED;
    final Task startedActivityRootTask;

    // Create a transition now to record the original intent of actions taken within
    // startActivityInner. Otherwise, logic in startActivityInner could start a different
    // transition based on a sub-action.
    // Only do the create here (and defer requestStart) since startActivityInner might abort.
    final TransitionController transitionController = r.mTransitionController;
    Transition newTransition = (!transitionController.isCollecting()
            && transitionController.getTransitionPlayer() != null)
            ? transitionController.createTransition(TRANSIT_OPEN) : null;
    RemoteTransition remoteTransition = r.takeRemoteTransition();
    try {
        mService.deferWindowLayout();
        transitionController.collect(r);
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "startActivityInner");
            result = startActivityInner(r, sourceRecord, voiceSession, voiceInteractor,
                    startFlags, doResume, options, inTask, inTaskFragment, restrictedBgActivity,
                    intentGrants);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            startedActivityRootTask = handleStartResult(r, options, result, newTransition,
                    remoteTransition);
        }
    } finally {
        mService.continueWindowLayout();
    }
    postStartActivityProcessing(r, result, startedActivityRootTask);

    return result;
}
```

这一层的职责是**事务边界**而非业务逻辑：

- `deferWindowLayout() / continueWindowLayout()`：整个 `startActivityInner` 期间冻结窗口布局计算，结束时一次性重排——启动过程中的中间态（任务建了一半、活动挂了一半）不触发布局。
- `transitionController.collect(r)`：Shell 转场（A13 的 Transitions 机制）从此刻开始收集本次启动涉及的容器变化。
- `handleStartResult`（[ActivityStarter.java#L1767](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1767)）：结果不成功时把「半接入」的记录从任务摘除、空任务连带移除——启动失败的现场不留半成品。
- `Trace.traceBegin("startActivityInner")`：systrace 上那条著名的泳道就在这里开闭。

下一章进入 `startActivityInner` 本体：flag 怎么被改写、复用还是新建怎么裁决、任务树怎么长。

---

## 3. startActivityInner：flag 裁决与任务的创建、复用

`startActivityInner` 是任务裁决的总现场：一次启动到底**复用哪个任务、还是新建任务、还是只给栈顶送一个 onNewIntent**，全部在这一个方法里定案。它也是第 8 章事故推演的骨架——A 实例走的是「新建根任务」分支，B 实例走的也是「新建根任务」分支，但两者新建的原因完全不同；Monkey 第 3 次启动走的是「交付给栈顶」分支，却交付到了 B 头上。要读懂那三个分叉，先把本章的每个裁决点拆开。

### 3.1 十步骨架

[ActivityStarter.java#L1893](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L1893)

```java
int startActivityInner(final ActivityRecord r, ActivityRecord sourceRecord,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        int startFlags, boolean doResume, ActivityOptions options, Task inTask,
        TaskFragment inTaskFragment, boolean restrictedBgActivity,
        NeededUriGrants intentGrants) {
    setInitialState(r, options, inTask, inTaskFragment, doResume, startFlags, sourceRecord,
            voiceSession, voiceInteractor, restrictedBgActivity);

    computeLaunchingTaskFlags();
    mIntent.setFlags(mLaunchFlags);
    ...
    // Get top task at beginning because the order may be changed when reusing existing task.
    final Task prevTopRootTask = mPreferredTaskDisplayArea.getFocusedRootTask();
    final Task prevTopTask = prevTopRootTask != null ? prevTopRootTask.getTopLeafTask() : null;
    final Task reusedTask = getReusableTask();
    ...
    // Compute if there is an existing task that should be used for.
    final Task targetTask = reusedTask != null ? reusedTask : computeTargetTask();
    final boolean newTask = targetTask == null;
    mTargetTask = targetTask;

    computeLaunchParams(r, sourceRecord, targetTask);

    // Check if starting activity on given task or on a new task is allowed.
    int startResult = isAllowedToStart(r, newTask, targetTask);
    if (startResult != START_SUCCESS) {
        ...
        return startResult;
    }
    ...
    final ActivityRecord targetTaskTop = newTask
            ? null : targetTask.getTopNonFinishingActivity();
    if (targetTaskTop != null) {
        // Recycle the target task for this launch.
        startResult = recycleTask(targetTask, targetTaskTop, reusedTask, intentGrants);
        if (startResult != START_SUCCESS) {
            return startResult;
        }
    } else {
        mAddingToTask = true;
    }

    // If the activity being launched is the same as the one currently at the top, then
    // we need to check if it should only be launched once.
    final Task topRootTask = mPreferredTaskDisplayArea.getFocusedRootTask();
    if (topRootTask != null) {
        startResult = deliverToCurrentTopIfNeeded(topRootTask, intentGrants);
        if (startResult != START_SUCCESS) {
            return startResult;
        }
    }

    if (mTargetRootTask == null) {
        mTargetRootTask = getOrCreateRootTask(mStartActivity, mLaunchFlags, targetTask,
                mOptions);
    }
    if (newTask) {
        final Task taskToAffiliate = (mLaunchTaskBehind && mSourceRecord != null)
                ? mSourceRecord.getTask() : null;
        setNewTask(taskToAffiliate);
    } else if (mAddingToTask) {
        addOrReparentStartingActivity(targetTask, "adding to task");
    }

    if (!mAvoidMoveToFront && mDoResume) {
        mTargetRootTask.getRootTask().moveToFront("reuseOrNewTask", targetTask);
        ...
    }
    ...
    final Task startedTask = mStartActivity.getTask();
    if (newTask) {
        EventLogTags.writeWmCreateTask(mStartActivity.mUserId, startedTask.mTaskId);
    }
    mStartActivity.logStartActivity(EventLogTags.WM_CREATE_ACTIVITY, startedTask);
    ...
    mTargetRootTask.startActivityLocked(mStartActivity, topRootTask, newTask, isTaskSwitch,
            mOptions, sourceRecord);
    if (mDoResume) {
        ...
        mRootWindowContainer.resumeFocusedTasksTopActivities(
                mTargetRootTask, mStartActivity, mOptions, mTransientLaunch);
    }
    ...
    return START_SUCCESS;
}
```

把主干抽出来，是十步：

1. **`setInitialState`**：把 Request 摊平成成员变量，并对 flag 做第一轮归一（3.2 节）。
2. **`computeLaunchingTaskFlags`**：第二轮 flag 归一，核心是三种「强制 NEW_TASK」（3.3 节）。
3. **`getReusableTask`**：查历史——这个意图/组件是否已有可复用的任务（3.4 节，匹配器本体在第 4 章）。
4. **`computeTargetTask`**：`reusedTask` 为空时的兜底目标（3.5 节）。`targetTask == null` 即 `newTask = true`。
5. **`isAllowedToStart`**：HOME 能不能落这个显示区、任务归属与多屏许可等资格复查。
6. **`recycleTask`**：目标任务非空时先「回收」它——把目标任务调整成本次启动需要的样子（3.7 节）。
7. **`deliverToCurrentTopIfNeeded`**：栈顶短路——要启动的活动恰好是焦点根任务栈顶、且带 singleTop 语义时，不启动新实例，只送 onNewIntent，返回 `START_DELIVERED_TO_TOP`（3.7 节）。
8. **`getOrCreateRootTask` + `setNewTask`/`addOrReparentStartingActivity`**：定案落点——新任务在根任务下建出来，或把记录加进既有任务（3.6 节）。
9. **日志与转场**：`wm_create_task`、`wm_create_activity` 事件，`startActivityLocked` 建窗口、准备 app transition。
10. **`resumeFocusedTasksTopActivities`**：把接力棒交给第 6 章的 resume 机器。

一个容易误读的点：`recycleTask` 与 `deliverToCurrentTopIfNeeded` 都可能提前 return——**方法返回值不只是错误码，还是路由**：`START_DELIVERED_TO_TOP` 意味着「这次启动到此为止，没有新实例」。第 8 章里 Monkey 重复启动走的就是这条路由。

### 3.2 setInitialState：flag 的第一轮归一

[ActivityStarter.java#L2595](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2595)

```java
private void setInitialState(ActivityRecord r, ActivityOptions options, Task inTask,
        TaskFragment inTaskFragment, boolean doResume, int startFlags,
        ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession,
        IVoiceInteractor voiceInteractor, boolean restrictedBgActivity) {
    reset(false /* clearRequest */);

    mStartActivity = r;
    mIntent = r.intent;
    ...
    mLaunchMode = r.launchMode;

    mLaunchFlags = adjustLaunchFlagsToDocumentMode(
            r, LAUNCH_SINGLE_INSTANCE == mLaunchMode,
            LAUNCH_SINGLE_TASK == mLaunchMode, mIntent.getFlags());
    ...
    if (mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK) {
        // Adding NEW_TASK flag for singleInstancePerTask launch mode activity, so that the
        // activity won't be launched in source record's task.
        mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
    }

    sendNewTaskResultRequestIfNeeded();

    if ((mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
        mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
    }

    // If we are actually going to launch in to a new task, there are some cases where
    // we further want to do multiple task.
    if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
        if (mLaunchTaskBehind
                || r.info.documentLaunchMode == DOCUMENT_LAUNCH_ALWAYS) {
            mLaunchFlags |= FLAG_ACTIVITY_MULTIPLE_TASK;
        }
    }
    ...
    mDoResume = doResume;
    if (!doResume || !r.showToCurrentUser() || mLaunchTaskBehind) {
        r.delayedResume = true;
        mDoResume = false;
    }
    ...
```

规则与后果：

- `mLaunchMode` 直接取自记录的 `launchMode`（即 manifest 声明，2.4 节），服务端全程不改它；能被改写的是 **flag**。
- `adjustLaunchFlagsToDocumentMode` 按活动的 `documentLaunchMode`（manifest 属性，缺省 DOCUMENT_LAUNCH_NONE）调和 NEW_DOCUMENT/MULTIPLE_TASK，与车机主线无关，不展开。
- **launchMode 可以合成 flag**：singleInstancePerTask 强制加 NEW_TASK（不让它落进源活动的任务）；NEW_DOCUMENT 且无 resultTo 时也加 NEW_TASK（文档必须独立任务）。这是「launchMode 是静态声明、flag 是本次意图」两套机制的汇合点之一：声明可以被翻译成本次的 flag。
- `mDoResume=false` 的路径（调用方要求不立即 resume、活动对当前用户不可见、launchTaskBehind）会把记录标成 `delayedResume`——第 6 章会看到 `realStartActivityLocked` 对这类记录只发 LaunchActivityItem 不发 ResumeActivityItem，pending 的 newIntents 也因此不立即交付。
- 对照事故日志：Monkey 的 flag 是 0x10200000（NEW_TASK|RESET_TASK_IF_NEEDED），SystemUI 的是 0x10000000（仅 NEW_TASK）【日志事实】——两者都过 NEW_TASK 分支，第一轮归一没有给它们追加任何东西（MainActivity 的 documentLaunchMode 缺省 NONE，launchMode 是 singleTask 不是 singleInstancePerTask）。

### 3.3 computeLaunchingTaskFlags：三种强制 NEW_TASK

[ActivityStarter.java#L2828](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2828)

```java
if (mInTask == null) {
    if (mSourceRecord == null) {
        // This activity is not being started from another...  in this
        // case we -always- start a new task.
        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) == 0 && mInTask == null) {
            Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                    "Intent.FLAG_ACTIVITY_NEW_TASK for: " + mIntent);
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
        }
    } else if (mSourceRecord.launchMode == LAUNCH_SINGLE_INSTANCE) {
        // The original activity who is starting us is running as a single
        // instance...  this new activity it is starting must go on its
        // own task.
        mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
    } else if (isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)) {
        // The activity being started is a single instance...  it always
        // gets launched into its own task.
        mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
    }
}
```

三种情形：

1. **无源记录**（从 Service、BroadcastReceiver、Application context 等非 Activity 上下文启动）：强制 NEW_TASK，并打一条著名的 `Slog.w`——「startActivity called from non-Activity context; forcing Intent.FLAG_ACTIVITY_NEW_TASK」。原因是非 Activity 上下文没有「当前任务」可挂靠，落不进任何既有任务。SystemUI 的 `goLauncher` 用的是注入的 context（日志 122038 行 ContextImpl 的警告栈显示 ActivityStarter.goLauncher:91），但意图本身已带 NEW_TASK，所以不触发这条 Slog.w。
2. **源活动是 singleInstance**：它独占任务，从它启动的任何活动都必须另起任务。
3. **被启动的活动自身是 singleInstance 或 singleTask**：注释说得很直白——「it always gets launched into its own task」。**注意措辞：是「获得 NEW_TASK flag」，不是「获得独占任务」。** NEW_TASK 只保证「不挂进源任务」；能不能找到既有任务复用，是下一步 `getReusableTask` 的事。雅迪 MainActivity 是 singleTask，走这条：即便调用方忘了加 NEW_TASK，服务端也会补上。

> **Q：既然 singleTask 会被强制 NEW_TASK，那 singleTask 与「NEW_TASK 的 standard 活动」在 flag 层面还有区别吗？**
> A：flag 层面趋同，但裁决层面有三个区别：`getReusableTask` 的 `putIntoExistingTask` 条件里 singleTask **不需要 NEW_TASK 也参与复用查找**（3.4 节）；`complyActivityFlags` 里 singleTask 走 CLEAR_TOP 同款清栈分支（3.7 节）；匹配器的类型闸门对两者的记录类型认定不同（第 4 章、第 5 章）。

方法尾部还有一段 LAUNCH_ADJACENT 清理（[ActivityStarter.java#L2850](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2850)）：无源记录或没带 NEW_TASK 时把该 flag 抹掉——分屏「相邻启动」必须有源才有意义。

### 3.4 getReusableTask：要不要查历史

[ActivityStarter.java#L2862](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2862)

```java
private Task getReusableTask() {
    // If a target task is specified, try to reuse that one
    if (mOptions != null && mOptions.getLaunchTaskId() != INVALID_TASK_ID) {
        Task launchTask = mRootWindowContainer.anyTaskForId(mOptions.getLaunchTaskId());
        if (launchTask != null) {
            return launchTask;
        }
        return null;
    }

    // We may want to try to place the new activity in to an existing task.  We always
    // do this if the target activity is singleTask or singleInstance; we will also do
    // this if NEW_TASK has been requested, and there is not an additional qualifier telling
    // us to still place it in a new task: multi task, always doc mode, or being asked to
    // launch this as a new task behind the current one.
    boolean putIntoExistingTask = ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
            (mLaunchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
            || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK);
    // If bring to front is requested, and no result is requested and we have not been given
    // an explicit task to launch in to, and we can find a task that was started with this
    // same component, then instead of launching bring that one to the front.
    putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
    ActivityRecord intentActivity = null;
    if (putIntoExistingTask) {
        if (LAUNCH_SINGLE_INSTANCE == mLaunchMode) {
            // There can be one and only one instance of single instance activity in the
            // history, and it is always in its own task, so we do a special search.
            intentActivity = mRootWindowContainer.findActivity(mIntent, mStartActivity.info,
                   mStartActivity.isActivityTypeHome());
        } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
            ...
        } else {
            // Otherwise find the best task to put the activity in.
            intentActivity =
                    mRootWindowContainer.findTask(mStartActivity, mPreferredTaskDisplayArea);
        }
    }

    if (intentActivity != null && mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK
            && !intentActivity.getTask().getRootActivity().mActivityComponent.equals(
            mStartActivity.mActivityComponent)) {
        // The task could be selected due to same task affinity. Do not reuse the task while
        // starting the singleInstancePerTask activity if it is not the task root activity.
        intentActivity = null;
    }

    if (intentActivity != null
            && (mStartActivity.isActivityTypeHome() || intentActivity.isActivityTypeHome())
            && intentActivity.getDisplayArea() != mPreferredTaskDisplayArea) {
        // Do not reuse home activity on other display areas.
        intentActivity = null;
    }

    return intentActivity != null ? intentActivity.getTask() : null;
}
```

逐条拆：

- **`putIntoExistingTask` 准入**：（NEW_TASK 且非 MULTIPLE_TASK）或 launchMode 是 singleInstance/singleTask。再与「非 AppTask 指定、非 startActivityForResult」相与。MULTIPLE_TASK 的语义就是「别复用，给我新的」，所以它一票否决复用查找。
- **返回值是 `intentActivity.getTask()`——叶子任务**。`findTask` 返回的是**活动记录**不是任务（A13 的签名如此，第 4 章细讲），这里取它所在的任务。
- **singleInstance 走专门查找 `findActivity`**：注释写明「一个 singleInstance 活动在历史中有且仅有一个实例，且永远独占任务」，所以按组件+affinity 全局找记录，而不是找任务。这是五种 launchMode 中唯一不复用 `findTask` 的模式（第 5 章推导）。
- **两道一票否决**：singleInstancePerTask 匹配到的任务若根活动组件不同（说明只是 affinity 撞上），拒绝复用；**HOME 语义跨显示区否决**——只要本次记录或匹配记录任一方是 HOME 类型，且对方不在同一 TaskDisplayArea，就不复用。这是多屏车机的关键规则：**每个屏的桌面各归各的任务区，主屏 HOME 请求永远不会复用副屏的 HOME 任务**（1.6 节的解析优先级管「用哪个 HOME 应用」，这一条管「用哪个 HOME 任务」）。
- 对照事故日志：A（Monkey）与 B（SystemUI）的请求都过准入（NEW_TASK、singleTask）。A 发起时全场只有空的 HOME 根任务 Task 1，`findTask` 无从匹配；B 发起时场上有装着 A 的 Task 2，`findTask` 却因类型闸门视而不见——这就是第 4 章的主题。

### 3.5 computeTargetTask：兜底目标

[ActivityStarter.java#L2078](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2078)

```java
private Task computeTargetTask() {
    if (mStartActivity.resultTo == null && mInTask == null && !mAddingToTask
            && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
        // A new task should be created instead of using existing one.
        return null;
    } else if (mSourceRecord != null) {
        return mSourceRecord.getTask();
    } else if (mInTask != null) {
        // The task is specified from AppTaskImpl, so it may not be attached yet.
        if (!mInTask.isAttached()) {
            // Attach the task to display area. Ignore the returned root task (though usually
            // they are the same) because "target task" should be leaf task.
            getOrCreateRootTask(mStartActivity, mLaunchFlags, mInTask, mOptions);
        }
        return mInTask;
    } else {
        final Task rootTask = getOrCreateRootTask(mStartActivity, mLaunchFlags, null /* task */,
                mOptions);
        final ActivityRecord top = rootTask.getTopNonFinishingActivity();
        if (top != null) {
            return top.getTask();
        } else {
            // Remove the root task if no activity in the root task.
            rootTask.removeIfPossible("computeTargetTask");
        }
    }
    return null;
}
```

第一分支是主线：**带 NEW_TASK、非 startActivityForResult、非 AppTask 指定 → 直接返回 null**，即「不复用就新建」。事故里 A、B 都从这里出去，`newTask=true`。没有 NEW_TASK 的普通跳转走第二分支，落进源活动的任务——这就是「应用内跳转默认压同栈」的实现位置。最后的 else 分支处理无源无 NEW_TASK 的边角（先建根任务再看栈顶），并有一个自我保护：建出来的根任务若是空的就 `removeIfPossible` 拆掉，不留垃圾。

### 3.6 新建路径：根任务从哪来，叶子任务怎么长

`newTask=true` 后，落点分两步：先定**根任务**（`getOrCreateRootTask`），再在根任务下定**叶子任务**（`setNewTask`）。这两层在 A13 里经常是同一个对象——「根任务直接当叶子用」，3.6.3 节讲这个转正机制。

#### 3.6.1 getOrCreateRootTask 与 resolveActivityType

ActivityStarter 侧只是个转发（[ActivityStarter.java#L3222](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L3222)），主体在 RootWindowContainer：

[RootWindowContainer.java#L3011](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L3011)

```java
Task getOrCreateRootTask(@Nullable ActivityRecord r,
        @Nullable ActivityOptions options, @Nullable Task candidateTask,
        @Nullable Task sourceTask, boolean onTop,
        @Nullable LaunchParamsController.LaunchParams launchParams, int launchFlags) {
    // First preference goes to the launch root task set in the activity options.
    if (options != null) {
        final Task candidateRoot = Task.fromWindowContainerToken(options.getLaunchRootTask());
        if (candidateRoot != null && canLaunchOnDisplay(r, candidateRoot)) {
            return candidateRoot;
        }
    }
    // Next preference goes to the task id set in the activity options.
    ...
    // Next preference goes to the TaskDisplayArea candidate from launchParams
    // or activity options.
    TaskDisplayArea taskDisplayArea = null;
    if (launchParams != null && launchParams.mPreferredTaskDisplayArea != null) {
        taskDisplayArea = launchParams.mPreferredTaskDisplayArea;
    } else if (options != null) {
        ...
    }

    final int activityType = resolveActivityType(r, options, candidateTask);
    if (taskDisplayArea != null) {
        if (canLaunchOnDisplay(r, taskDisplayArea.getDisplayId())) {
            return taskDisplayArea.getOrCreateRootTask(r, options, candidateTask,
                    sourceTask, launchParams, launchFlags, activityType, onTop);
        } else {
            taskDisplayArea = null;
        }
    }
    ...
    // Falling back to default task container
    if (taskDisplayArea == null) {
        taskDisplayArea = getDefaultTaskDisplayArea();
    }
    return taskDisplayArea.getOrCreateRootTask(r, options, candidateTask, sourceTask,
            launchParams, launchFlags, activityType, onTop);
}
```

优先级链是：options 指定的根任务 → options 指定的任务 id → 首选 TaskDisplayArea → 候选/源任务所在的根任务（要求窗口模式与活动类型兼容）→ 缺省 TDA。**注意 `resolveActivityType` 在进 TDA 之前算好**，它决定 TDA 里「找现成根任务还是建新根任务」用哪个类型：

[RootWindowContainer.java#L3137](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L3137)

```java
int resolveActivityType(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
        @Nullable Task task) {
    // Preference is given to the activity type for the activity then the task since the type
    // once set shouldn't change.
    int activityType = r != null ? r.getActivityType() : ACTIVITY_TYPE_UNDEFINED;
    if (activityType == ACTIVITY_TYPE_UNDEFINED && task != null) {
        activityType = task.getActivityType();
    }
    if (activityType != ACTIVITY_TYPE_UNDEFINED) {
        return activityType;
    }
    if (options != null) {
        activityType = options.getLaunchActivityType();
    }
    return activityType != ACTIVITY_TYPE_UNDEFINED ? activityType : ACTIVITY_TYPE_STANDARD;
}
```

四问走一遍——**规则**：活动自身类型优先，其次候选任务类型，再次 options，全空则兜底 STANDARD。**设置者**：调用链上的 ActivityStarter（记录在构造时定型，2.5/2.7 节）。**执行**：本方法在每次 `getOrCreateRootTask` 时跑。**后果**：这是 UNDEFINED 记录第一次被「视作 STANDARD」的地方——但注意，**它只影响根任务的查找/创建，不回写记录**；记录本体要等入栈才补 STANDARD（2.7 节）。HOME 请求在这里返回 HOME，于是 TDA 直接命中 `mRootHomeTask` 缓存（1.5 节），根本不新建根任务。

#### 3.6.2 alwaysCreateRootTask：STANDARD 全屏为什么必有根任务

TDA 的 `getOrCreateRootTask`（TaskDisplayArea.java:977）先问一句：这种组合是否「必须自建根任务」？

[DisplayContent.java#L6220](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java#L6220)

```java
static boolean alwaysCreateRootTask(int windowingMode, int activityType) {
    // Always create a root task for fullscreen, freeform, and multi windowing
    // modes so that we can manage visual ordering and return types correctly.
    return activityType == ACTIVITY_TYPE_STANDARD
            && (windowingMode == WINDOWING_MODE_FULLSCREEN
            || windowingMode == WINDOWING_MODE_FREEFORM
            || windowingMode == WINDOWING_MODE_PINNED
            || windowingMode == WINDOWING_MODE_MULTI_WINDOW);
}
```

条件是 **STANDARD 类型 + 全屏/自由窗口/画中画/多窗口模式之一**。命中则不复用现成根任务，直接建一个新的；HOME/RECENTS 等类型不命中（HOME 永远进 `mRootHomeTask`）。这条规则的动机在注释里：每个应用任务自己当根，视觉排序（z 序）与返回类型（回桌面还是回上个应用）才能按根任务粒度管理。对照事故：A 的请求经 `resolveActivityType` 得到 STANDARD（记录此刻 UNDEFINED → 兜底），窗口模式 fullscreen → 命中 → **新建根任务 Task 2**。

#### 3.6.3 setNewTask：reuseOrCreateTask 与「根转正叶」

[ActivityStarter.java#L3073](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L3073)

```java
private void setNewTask(Task taskToAffiliate) {
    final boolean toTop = !mLaunchTaskBehind && !mAvoidMoveToFront;
    final Task task = mTargetRootTask.reuseOrCreateTask(
            mStartActivity.info, mIntent, mVoiceSession,
            mVoiceInteractor, toTop, mStartActivity, mSourceRecord, mOptions);
    task.mTransitionController.collectExistenceChange(task);
    addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask");
    ...
    if (taskToAffiliate != null) {
        mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
    }
}
```

`reuseOrCreateTask`（[Task.java#L6218](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L6218)）在根任务内部做第二次裁决：

```java
Task reuseOrCreateTask(TaskInfo info, Intent intent, IVoiceInteractionSession voiceSession,
        IVoiceInteractor voiceInteractor, boolean toTop, ActivityRecord activity,
        ActivityRecord source, ActivityOptions options) {

    Task task;
    if (canReuseAsLeafTask()) {
        // This root task will only contain one task, so just return itself since all root
        // tasks ara now tasks and all tasks are now root tasks.
        task = reuseAsLeafTask(voiceSession, voiceInteractor, intent, info, activity);
    } else {
        // Create child task since this root task can contain multiple tasks.
        final int taskId = activity != null
                ? mTaskSupervisor.getNextTaskIdForUser(activity.mUserId)
                : mTaskSupervisor.getNextTaskIdForUser();
        task = new Task.Builder(mAtmService)
                .setTaskId(taskId)
                .setActivityInfo(info)
                .setActivityOptions(options)
                .setIntent(intent)
                .setVoiceSession(voiceSession)
                .setVoiceInteractor(voiceInteractor)
                .setOnTop(toTop)
                .setParent(this)
                .build();
    }
    ...
    return task;
}
```

[Task.java#L6264](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L6264)

```java
private boolean canReuseAsLeafTask() {
    // Cannot be reused as leaf task if this task is created by organizer or having child tasks.
    if (mCreatedByOrganizer || !isLeafTask()) {
        return false;
    }

    // Existing Tasks can be reused if a new root task will be created anyway.
    final int windowingMode = getWindowingMode();
    final int activityType = getActivityType();
    return DisplayContent.alwaysCreateRootTask(windowingMode, activityType);
}
```

刚建出来的根任务满足三个条件：非 organizer 创建、是叶子（还没孩子）、`alwaysCreateRootTask` 命中（它就是因此被建出来的）——于是 `reuseAsLeafTask` **原地转正**：

[Task.java#L748](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L748)

```java
Task reuseAsLeafTask(IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
        Intent intent, ActivityInfo info, ActivityRecord activity) {
    voiceSession = _voiceSession;
    voiceInteractor = _voiceInteractor;
    setIntent(activity, intent, info);
    setMinDimensions(info);
    // Before we began to reuse a root task as the leaf task, we used to
    // create a leaf task in this case. Therefore now we won't send out the task created
    // notification when we decide to reuse it here, so we send out the notification below.
    ...
    mAtmService.getTaskChangeNotificationController().notifyTaskCreated(mTaskId, realActivity);
    return this;
}
```

`setIntent(activity, intent, info)` 把意图、realActivity、affinity 灌进这个任务（Task.java:1049，其中第一次设置时连 `rootAffinity` 一起定，第 4 章匹配要用），然后**返回 this**——不新建对象。所以「STANDARD 全屏新任务」在 A13 的真实形态是：**一个既是根又是叶的 Task**，taskId 就是根任务 id。这解释了两个日志现象：

- `wm_task_created [2,-1]`（日志 106655 行）：第二个字段是 rootTaskId，**-1 表示自己就是根**。转正路径上 Task 的构造发生在 `TDA.getOrCreateRootTask` 建根的那一刻。
- Task 2 的打印 `Task{af7f4b9 #2 type=standard ... U=0 visible=true ...}` **没有 rootTaskId= 字段**（日志 122164 行）——雅迪树的 toString 只在「有父任务且父不是自己」时打 rootTaskId（第 7 章）。没有这个字段本身就是「我是根任务」的证据【日志事实 + 源码事实互证】。

对照 HOME 路径：B 的请求经 `resolveActivityType` 得 HOME → TDA 命中 `mRootHomeTask`（Task 1，1.5 节建好的）→ `canReuseAsLeafTask` 里 `alwaysCreateRootTask(fullscreen, HOME)` 为 **false** → 走 else 分支，**在 Task 1 下新建子 Task 3**。所以 `wm_task_created [3,-1]`……等等，日志 122064 行明明是 [3,-1]？——不，回看原始日志：122064 行是 `[3,-1]`，而 141787 行的陈旧打印里 Task 3 带 `rootTaskId=1`。两者矛盾吗？不矛盾：`wm_task_created` 的事件在**构造函数里**发出，雅迪树的构造函数在 attach 之前调用它，此刻还没有父任务，rootTaskId 自然是 -1（第 7 章 7.3 节详证）。**事件日志说 [3,-1]，稳态打印说 rootTaskId=1，都对，时点不同。** 这是本文最重要的一条日志阅读纪律。

#### 3.6.4 addOrReparentStartingActivity：记录入栈

[ActivityStarter.java#L3105](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L3105)

```java
private void addOrReparentStartingActivity(@NonNull Task task, String reason) {
    TaskFragment newParent = task;
    if (mInTaskFragment != null) {
        int embeddingCheckResult = canEmbedActivity(mInTaskFragment, mStartActivity, task);
        if (embeddingCheckResult == EMBEDDING_ALLOWED) {
            newParent = mInTaskFragment;
        } else {
            // Start mStartActivity to task instead if it can't be embedded to mInTaskFragment.
            sendCanNotEmbedActivityError(mInTaskFragment, embeddingCheckResult);
        }
    } else {
        TaskFragment candidateTf = mAddingToTaskFragment != null ? mAddingToTaskFragment : null;
        if (candidateTf == null) {
            final ActivityRecord top = task.topRunningActivity(false /* focusableOnly */,
                    false /* includingEmbeddedTask */);
            if (top != null) {
                candidateTf = top.getTaskFragment();
            }
        }
        if (candidateTf != null && candidateTf.isEmbedded()
                && canEmbedActivity(candidateTf, mStartActivity, task) == EMBEDDING_ALLOWED) {
            // Use the embedded TaskFragment of the top activity as the new parent if the
            // activity can be embedded.
            newParent = candidateTf;
        }
    }
    if (mStartActivity.getTaskFragment() == null
            || mStartActivity.getTaskFragment() == newParent) {
        newParent.addChild(mStartActivity, POSITION_TOP);
    } else {
        mStartActivity.reparent(newParent, newParent.getChildCount() /* top */, reason);
    }
}
```

Activity Embedding（Jetpack WindowManager 的分屏嵌入）会在这里改道：任务内若有嵌入 TaskFragment 且允许嵌入，记录挂进 TaskFragment 而不是 Task 本体。车机 Launcher 场景没有嵌入，`newParent` 就是 task 本身，走 `addChild(mStartActivity, POSITION_TOP)`。**`addChild` 正是触发 `onDescendantActivityAdded` 的入口**——2.7 节那条「UNDEFINED 记录此刻补成 STANDARD、任务类型跟着第一个活动定」的规则，就在这一跳生效。A 入 Task 2 的瞬间，记录与任务同时变成 standard。

### 3.7 复用路径：recycleTask、栈顶短路与 complyActivityFlags

`targetTask != null` 时先 `recycleTask`（[ActivityStarter.java#L2269](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2269)）：跨用户任务只挂靠不清理；`reusedTask` 非空时把 TASK_ON_HOME flag 同步进任务意图（回桌面时任务压 HOME 之上的语义）；然后 `setTargetRootTaskIfNeeded(targetTaskTop)`（[ActivityStarter.java#L2929](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2929)）按源/目标任务关系定 `mTargetRootTask`；最后调 `complyActivityFlags` 按 flag 清理目标任务。

**deliverToCurrentTopIfNeeded——栈顶短路**（[ActivityStarter.java#L2362](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2362)）：

```java
private int deliverToCurrentTopIfNeeded(Task topRootTask, NeededUriGrants intentGrants) {
    final ActivityRecord top = topRootTask.topRunningNonDelayedActivityLocked(mNotTop);
    final boolean dontStart = top != null
            && top.mActivityComponent.equals(mStartActivity.mActivityComponent)
            && top.mUserId == mStartActivity.mUserId
            && top.attachedToProcess()
            && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
            || LAUNCH_SINGLE_TOP == mLaunchMode)
            // This allows home activity to automatically launch on secondary task display area
            // when it was added, if home was the top activity on default task display area,
            // instead of sending new intent to the home activity on default display area.
            && (!top.isActivityTypeHome() || top.getDisplayArea() == mPreferredTaskDisplayArea);
    if (!dontStart) {
        return START_SUCCESS;
    }
    ...
    deliverNewIntent(top, intentGrants);
    ...
    return START_DELIVERED_TO_TOP;
}
```

`dontStart` 五个条件全过才短路：焦点根任务的栈顶（非 delayed）就是同组件、同用户、**已附着进程**、带 singleTop 语义（flag 或 launchMode）、且 HOME 记录不跨显示区。`attachedToProcess()` 这一条很实际：栈顶记录若还没绑上进程（进程还没起来或刚死），短路没有意义——onNewIntent 没人可送，不如老实启动。注意这条短路**与 findTask 复用是两回事**：它不看任务归属，只看「焦点根任务栈顶是不是同一个组件」。事故里 Monkey 第 2、3 次启动都返回了 `wm_new_intent`（第 8 章会证明第 3 次走的其实不是这条短路，而是 recycleTask → complyActivityFlags 的 singleTask 分支——两条路都通向 `deliverNewIntent`，日志形态却一样，这正是日志阅读的陷阱）。

**complyActivityFlags**（[ActivityStarter.java#L2412](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2412)）是复用路径的 flag 执行器，五段裁决按序：

```java
if ((mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
    // The caller has requested to completely replace any existing task with its new
    // activity. Well that should not be too hard...
    ...
    targetTask.performClearTaskForReuse(true /* excludingTaskOverlay*/);
    targetTask.setIntent(mStartActivity);
    mAddingToTask = true;
    mIsTaskCleared = true;
} else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
        || isDocumentLaunchesIntoExisting(mLaunchFlags)
        || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK,
                LAUNCH_SINGLE_INSTANCE_PER_TASK)) {
    // In this situation we want to remove all activities from the task up to the one
    // being started. In most cases this means we are resetting the task to its initial
    // state.
    int[] finishCount = new int[1];
    final ActivityRecord clearTop = targetTask.performClearTop(mStartActivity,
            mLaunchFlags, finishCount);

    if (clearTop != null && !clearTop.finishing) {
        ...
        if (clearTop.isRootOfTask()) {
            // Activity aliases may mean we use different intents for the top activity,
            // so make sure the task now has the identity of the new intent.
            clearTop.getTask().setIntent(mStartActivity);
        }
        deliverNewIntent(clearTop, intentGrants);
    } else {
        // A special case: we need to start the activity because it is not currently
        // running, and the caller has asked to clear the current task to have this
        // activity at the top.
        mAddingToTask = true;
        ...
    }
} else if (...) {
    // REORDER_TO_FRONT：历史中找同组件记录，moveActivityToFront 后 deliverNewIntent
} else if (mStartActivity.mActivityComponent.equals(targetTask.realActivity)) {
    // 同组件复用：SINGLE_TOP 语义且栈顶同组件 → deliverNewIntent(targetTaskTop)
    // isSameIntentFilter 不同 → mAddingToTask = true（新实例压顶）
} else if (!resetTask) {
    // 普通压栈：通知、快捷方式等场景，新活动放任务顶
    mAddingToTask = true;
} else if (!targetTask.rootWasReset) {
    ...
}
```

要点三条：

1. **singleTask 与 CLEAR_TOP 同段处理**——第二段 else-if 把 `LAUNCH_SINGLE_TASK` 与 `FLAG_ACTIVITY_CLEAR_TOP` 并列：找到目标任务里匹配的记录，清掉它之上的所有活动，命中记录 `deliverNewIntent`。**singleTask 的「清栈回到唯一实例」语义，实现上就是 CLEAR_TOP 那一套**（performClearTop 的细节在第 5 章）。
2. 所有「不新建实例」的出口都汇到 `deliverNewIntent`。
3. `mAddingToTask = true` 的出口回到 3.1 骨架第 8 步的 `addOrReparentStartingActivity(targetTask, "adding to task")`——复用任务但压新实例。

**deliverNewIntent 与 wm_new_intent 日志**（[ActivityStarter.java#L3090](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L3090)）：

```java
private void deliverNewIntent(ActivityRecord activity, NeededUriGrants intentGrants) {
    if (mIntentDelivered) {
        return;
    }

    activity.logStartActivity(EventLogTags.WM_NEW_INTENT, activity.getTask());
    activity.deliverNewIntentLocked(mCallingUid, mStartActivity.intent, intentGrants,
            mStartActivity.launchedFromPackage);
    mIntentDelivered = true;
}
```

先打 `wm_new_intent` 事件再投递。`logStartActivity`（[ActivityRecord.java#L4831](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L4831)）的字段构成：

```java
void logStartActivity(int tag, Task task) {
    final Uri data = intent.getData();
    final String strData = data != null ? data.toSafeString() : null;

    EventLog.writeEvent(tag,
            mUserId, System.identityHashCode(this), task.mTaskId,
            shortComponentName, intent.getAction(),
            intent.getType(), strData, intent.getFlags());
}
```

**`System.identityHashCode(this)`——被交付记录的服务端身份哈希。** 这个值随 `wm_create_activity`、`wm_new_intent`、`wm_restart_activity` 一起出现，是跨事件锁定「哪条记录」的唯一钥匙；它还会经 LaunchActivityItem 传给客户端成为 `Activity.mIdent`，出现在客户端的 `wm_on_create_called` 里（第 6 章）。事故日志中 236566844 恒指 A、130421252 恒指 B，两条十六进制身份（e19b93c、7c61204）同时出现在服务端记录打印 `ActivityRecord{e19b93c ...}` 中——十进制与十六进制同值互证【日志事实】。

`deliverNewIntentLocked`（[ActivityRecord.java#L4991](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L4991)）决定「立即送」还是「记账等 resume」：

```java
void deliverNewIntentLocked(int callingUid, Intent intent,
        @Nullable NeededUriGrants intentGrants, String fromPackage) {
    ...
    final boolean callerIsRecents = mTaskSupervisor.mService.getRecentTasks()
            .isCallerRecents(callingUid);
    if (!callerIsRecents && !callerIsSystem) {
        // Give the new intent to the current top activity, ...
        grantUriPermissionUncheckedFromIntent(intentGrants, getUriPermissionsLocked());
    }

    if ((state == State.RESUMED || state == State.PAUSED || mTopWhileSleeping)
            && attachedToProcess()) {
        if (getTaskFragment().scheduleNewIntent(callingUid, this, intent, fromPackage)) {
            ...
            return;
        }
    }
    // Not running -- just add to list of pending intents.
    addNewIntentLocked(intent);
}
```

活动正处于 RESUMED/PAUSED（或睡眠期栈顶）且进程已附着 → 走 ClientTransaction 立即投递；否则 `addNewIntentLocked` 存进 `newIntents` 列表，等 `realStartActivityLocked` 时随 LaunchActivityItem 一起带过去（第 6 章）。**「立即」与「pending」的分水岭是记录状态 + 进程附着**，这也解释了 2.7 节提到的 delayedResume 记录为何收不到即时 onNewIntent。

### 3.8 收尾三连：日志、startActivityLocked、resume

回到 3.1 骨架的末段：

```java
final Task startedTask = mStartActivity.getTask();
if (newTask) {
    EventLogTags.writeWmCreateTask(mStartActivity.mUserId, startedTask.mTaskId);
}
mStartActivity.logStartActivity(EventLogTags.WM_CREATE_ACTIVITY, startedTask);
```

- `wm_create_task` 只在**新建任务**时打，两个字段：userId 与 taskId。事故日志 106753 行 `wm_create_task: [0,2]` 即「用户 0 的任务 2」——**不是**「任务 0 的第 2 条」，字段顺序以 EventLogTags 定义为准（附录 A）。
- `wm_create_activity` 无条件打，九字段中第 2 字段是上面那个 identityHashCode。
- 随后 `mTargetRootTask.startActivityLocked(...)`（[Task.java#L5379](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L5379) 起）为新记录建窗口容器（`ActivityRecord` 的 WindowState、starting window 判定、app transition 准备），`moveToFront("reuseOrNewTask", ...)` 把目标根任务提到显示区顶，最后 `resumeFocusedTasksTopActivities(mTargetRootTask, mStartActivity, mOptions, mTransientLaunch)` 进入第 6 章。

### 3.9 本章小结：两条新建、一条短路

把本章的裁决树压成一张对照表（完整事故推演在第 8 章）：

| 请求 | 记录类型（findTask 时） | getReusableTask 结果 | computeTargetTask | 根任务 | 叶子任务 | 结局 |
| --- | --- | --- | --- | --- | --- | --- |
| Monkey 首启（MAIN+LAUNCHER） | UNDEFINED | null（Task 1 空，无可匹配） | null（NEW_TASK） | 新建 STANDARD 根 Task 2 | 根转正叶 | A 入 Task 2，补成 standard |
| SystemUI goLauncher（MAIN+HOME） | HOME | null（Task 2 被类型闸门挡住，第 4 章） | null（NEW_TASK） | 命中 mRootHomeTask（Task 1） | Task 1 下新建子 Task 3 | B 入 Task 3 |
| Monkey 再启（同 A） | UNDEFINED | Task 2（匹配到 A，第 4 章） | — | — | — | complyActivityFlags singleTask 分支 → deliverNewIntent → wm_new_intent 给 A |

三条路都从 `startActivityInner` 进来，分叉点全在「记录类型 × findTask 匹配 × 根任务选择」这三个变量上。下一章把 `findTask` 这台匹配器整个拆开——包括它为什么会放走 Task 2。

---

## 4. findTask：任务复用匹配器与类型兼容的不对称性

3.4 节的 `getReusableTask` 把「找哪个任务能装下这次启动」委托给了 `RootWindowContainer.findTask`。这台匹配器是全文的技术核心：**雅迪双实例事故的直接原因，就是它的类型闸门放走了装着 A 的 Task 2**。本章把它整个拆开——遍历顺序、五道闸门、两级匹配结果——然后在 4.7 节把事故现场套进去演算一遍。

### 4.1 入口与遍历顺序

[RootWindowContainer.java#L2341](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L2341)

```java
ActivityRecord findTask(ActivityRecord r, TaskDisplayArea preferredTaskDisplayArea) {
    return findTask(r.getActivityType(), r.taskAffinity, r.intent, r.info,
            preferredTaskDisplayArea);
}
```

第一个要钉死的细节：**入参是「本次启动的新记录」`r`，匹配用的类型是 `r.getActivityType()`——即 2.5/2.7 节定型的值：HOME 请求是 HOME，其余一切是 UNDEFINED。** 返回值也是 `ActivityRecord`（A13 的签名），不是 Task；`getReusableTask` 拿到后再 `.getTask()`。

五参重载是主体：

[RootWindowContainer.java#L2348](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L2348)

```java
@Nullable
ActivityRecord findTask(int activityType, String taskAffinity, Intent intent, ActivityInfo info,
        TaskDisplayArea preferredTaskDisplayArea) {
    ProtoLog.d(WM_DEBUG_TASKS, "Looking for task of type=%s, taskAffinity=%s, intent=%s"
                    + ", info=%s, preferredTDA=%s", activityType, taskAffinity, intent, info,
            preferredTaskDisplayArea);
    mTmpFindTaskResult.init(activityType, taskAffinity, intent, info);

    // Looking up task on preferred display area first
    ActivityRecord candidateActivity = null;
    if (preferredTaskDisplayArea != null) {
        mTmpFindTaskResult.process(preferredTaskDisplayArea);
        if (mTmpFindTaskResult.mIdealRecord != null) {
            return mTmpFindTaskResult.mIdealRecord;
        } else if (mTmpFindTaskResult.mCandidateRecord != null) {
            candidateActivity = mTmpFindTaskResult.mCandidateRecord;
        }
    }

    final ActivityRecord idealMatchActivity = getItemFromTaskDisplayAreas(taskDisplayArea -> {
        if (taskDisplayArea == preferredTaskDisplayArea) {
            return null;
        }

        mTmpFindTaskResult.process(taskDisplayArea);
        if (mTmpFindTaskResult.mIdealRecord != null) {
            return mTmpFindTaskResult.mIdealRecord;
        }
        return null;
    });
    if (idealMatchActivity != null) {
        return idealMatchActivity;
    }

    if (WM_DEBUG_TASKS.isEnabled() && candidateActivity == null) {
        ProtoLog.d(WM_DEBUG_TASKS, "No task found");
    }
    return candidateActivity;
}
```

结构是「**先扫首选显示区，再扫其余显示区；理想解立即返回，弱候选先存着**」：

1. `mTmpFindTaskResult` 是复用的单例过滤器（成员变量，`init` 重置状态——匹配器本身无状态，状态全在这一次 init 里）。
2. **首选 TDA 先扫**：命中理想解（`mIdealRecord`）立即返回；只有弱候选（`mCandidateRecord`）则暂存，继续扫其他 TDA——其他区若出理想解，理想解优先于暂存的弱候选。
3. 其余 TDA 用 `getItemFromTaskDisplayAreas` 逐个扫，只认理想解。
4. 全部扫完，返回暂存的弱候选（可能为 null）。

**多屏语义**：跨屏复用是允许的（弱候选不锁死在本屏），只有 3.4 节 `getReusableTask` 的 HOME 跨屏否决和 `isAllowedToStart` 的显示区许可才做最终拦截。

单个 TDA 内的遍历由 `process` 发起：

[RootWindowContainer.java#L354](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L354)

```java
void process(WindowContainer parent) {
    cls = mIntent.getComponent();
    if (mInfo.targetActivity != null) {
        cls = new ComponentName(mInfo.packageName, mInfo.targetActivity);
    }
    userId = UserHandle.getUserId(mInfo.applicationInfo.uid);
    isDocument = mIntent != null & mIntent.isDocument();
    // If documentData is non-null then it must match the existing task data.
    documentData = isDocument ? mIntent.getData() : null;

    ProtoLog.d(WM_DEBUG_TASKS, "Looking for task of %s in %s", mInfo,
            parent);
    parent.forAllLeafTasks(this);
}
```

- `cls`：目标组件。`targetActivity` 是 activity-alias 的转发目标——别名活动按真身匹配。
- `userId`：从 `info.applicationInfo.uid` 取用户段（活动归属用户，与 2.3 节 START 日志的 `u0` 同源）。
- `documentData`：仅文档意图（NEW_DOCUMENT）参与 data 比对。
- `forAllLeafTasks(this)`：`FindTaskResult` 自身实现 `Predicate<Task>`，`test` 就是逐任务的闸门。遍历实现在 [Task.java#L3430](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L3430)：

```java
@Override
boolean forAllLeafTasks(Predicate<Task> callback) {
    boolean isLeafTask = true;
    for (int i = mChildren.size() - 1; i >= 0; --i) {
        final Task child = mChildren.get(i).asTask();
        if (child != null) {
            isLeafTask = false;
            if (child.forAllLeafTasks(callback)) {
                return true;
            }
        }
    }
    if (isLeafTask) {
        return callback.test(this);
    }
    return false;
}
```

**倒序递归**：`mChildren` 尾部是 z 序顶，倒序即**自顶向下**扫；非叶任务先递归孩子，叶任务才 `test` 自己；`test` 返回 true 立即短路整个遍历。所以匹配顺序 = **视觉栈序：最上面的叶任务最先被试**。事故里这条顺序决定了 Monkey 第 3 次启动的 onNewIntent 落在 B 而不是 A（4.7 节）。

### 4.2 test()：任务级三道闸

[RootWindowContainer.java#L371](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L371)

```java
@Override
public boolean test(Task task) {
    if (!ConfigurationContainer.isCompatibleActivityType(mActivityType,
            task.getActivityType())) {
        ProtoLog.d(WM_DEBUG_TASKS, "Skipping task: (mismatch activity/task) %s", task);
        return false;
    }

    if (task.voiceSession != null) {
        // We never match voice sessions; those always run independently.
        ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: voice session", task);
        return false;
    }
    if (task.mUserId != userId) {
        // Looking for a different task.
        ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: different user", task);
        return false;
    }

    if (matchingCandidate(task)) {
        return true;
    }

    // Looking for the embedded tasks (if any)
    return !task.isLeafTaskFragment() && task.forAllLeafTaskFragments(
            this::matchingCandidate);
}
```

三道任务级闸门，全过才进记录级匹配：

1. **类型闸**：`isCompatibleActivityType(请求记录类型, 任务类型)`——4.5 节的主角。
2. **语音会话闸**：voice session 的任务永不复用（语音交互独立生命周期）。
3. **用户闸**：任务用户 ≠ 活动归属用户则跳过。车机多用户（驾驶员/乘客分屏账号）场景这条会真实起作用。

末尾的 `forAllLeafTaskFragments` 是 Activity Embedding 的补充扫描：任务本体不匹配时，再试它内部的嵌入片段。车机 Launcher 场景无嵌入，略过。

### 4.3 matchingCandidate()：记录级闸门与两级匹配

[RootWindowContainer.java#L399](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L399)

```java
boolean matchingCandidate(TaskFragment taskFragment) {
    final Task task = taskFragment.asTask();
    if (task == null) {
        return false;
    }

    // Overlays should not be considered as the task's logical top activity.
    // Activities of the tasks that embedded from this one should not be used.
    final ActivityRecord r = task.getTopNonFinishingActivity(false /* includeOverlays */,
            false /* includingEmbeddedTask */);

    if (r == null || r.finishing || r.mUserId != userId
            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
        ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: mismatch root %s", task, r);
        return false;
    }
    if (!ConfigurationContainer.isCompatibleActivityType(r.getActivityType(),
            mActivityType)) {
        ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: mismatch activity type", task);
        return false;
    }
    ...
```

**记录级闸门四条**，注意主语换成了「任务里最顶上的那条非 finishing 记录」：

- `r == null`：任务空（或只剩 overlay/finishing 记录）——**HOME 根任务 Task 1 在开机后长期就是这个状态，任何 findTask 都匹配不到它**（1.5 节说过它是「等着装桌面的空壳」）。
- `r.finishing`：栈顶正在收尾的记录不算数。第 8 章的 Tasks 4/5（SystemUI 空白自杀活动）就是靠这条被排除，导致第二次启动又开新任务——**与主事故机制不同的另一条「双任务」成因**。
- `r.mUserId != userId`：记录用户再核一遍。
- `r.launchMode == LAUNCH_SINGLE_INSTANCE`：**栈顶是 singleInstance 记录的任务不参与 findTask**——singleInstance 有自己的 `findActivity` 专线（3.4 节），两套机制互不串门。
- **第二道类型闸**：`isCompatibleActivityType(记录类型, 请求类型)`——注意参数顺序与任务级闸相反（这里是「任务侧在前」，`test` 里是「请求侧在前」），但函数对称性只被 ASSISTANT 分支破坏，对 HOME/STANDARD/UNDEFINED 组合两边等价。

闸门全过之后是意图比对，先提取任务侧的文档信息：

[RootWindowContainer.java#L421](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L421)

```java
final Intent taskIntent = task.intent;
final Intent affinityIntent = task.affinityIntent;
final boolean taskIsDocument;
final Uri taskDocumentData;
if (taskIntent != null && taskIntent.isDocument()) {
    taskIsDocument = true;
    taskDocumentData = taskIntent.getData();
} else if (affinityIntent != null && affinityIntent.isDocument()) {
    taskIsDocument = true;
    taskDocumentData = affinityIntent.getData();
} else {
    taskIsDocument = false;
    taskDocumentData = null;
}

ProtoLog.d(WM_DEBUG_TASKS, "Comparing existing cls=%s /aff=%s to new cls=%s /aff=%s",
        r.getTask().rootAffinity, mIntent.getComponent().flattenToShortString(),
        mInfo.taskAffinity, (task.realActivity != null
                ? task.realActivity.flattenToShortString() : ""));
```

顺带一个调试陷阱：这条 ProtoLog 的**格式串与实参错位**——标签说 existing cls 在前，实参却先给 `rootAffinity`、再给**新意图**的组件，最后才是任务的 `realActivity`。开着 WM_DEBUG_TASKS 抓日志对匹配过程时，别被这行的字段顺序骗了【源码事实】。

然后是三分支裁决：

[RootWindowContainer.java#L441](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L441)

```java
// TODO Refactor to remove duplications. Check if logic can be simplified.
if (task.realActivity != null && task.realActivity.compareTo(cls) == 0
        && Objects.equals(documentData, taskDocumentData)) {
    ProtoLog.d(WM_DEBUG_TASKS, "Found matching class!");
    ProtoLog.d(WM_DEBUG_TASKS, "For Intent %s bringing to top: %s", mIntent, r.intent);
    mIdealRecord = r;
    return true;
} else if (affinityIntent != null && affinityIntent.getComponent() != null
        && affinityIntent.getComponent().compareTo(cls) == 0 &&
        Objects.equals(documentData, taskDocumentData)) {
    ProtoLog.d(WM_DEBUG_TASKS, "Found matching class!");
    ProtoLog.d(WM_DEBUG_TASKS, "For Intent %s bringing to top: %s", mIntent, r.intent);
    mIdealRecord = r;
    return true;
} else if (!isDocument && !taskIsDocument
        && mIdealRecord == null && mCandidateRecord == null
        && task.rootAffinity != null) {
    if (task.rootAffinity.equals(mTaskAffinity)) {
        ProtoLog.d(WM_DEBUG_TASKS, "Found matching affinity candidate!");
        // It is possible for multiple tasks to have the same root affinity especially
        // if they are in separate root tasks. We save off this candidate, but keep
        // looking to see if there is a better candidate.
        mCandidateRecord = r;
    }
} else {
    ProtoLog.d(WM_DEBUG_TASKS, "Not a match: %s", task);
}

return false;
```

**两级匹配结果**，这是 findTask 最容易被讲漏的机制：

- **理想解（mIdealRecord）**：任务 `realActivity`（或 affinityIntent 的组件）与请求组件**全等**，文档 data 一致。`return true` → 短路整个遍历 → findTask 立即返回。**组件相同即终局，不再看别的任务。**
- **弱候选（mCandidateRecord）**：组件不同但 `rootAffinity` 相同（且双方都不是文档意图、且还没有任何候选）。`return false` → **遍历继续**，注释写明「同 rootAffinity 的任务可能有多个，先存下这个，继续找更好的」。弱候选的语义是「同 affinity 任务可以装下这个活动」（比如从通知点进应用的非入口活动）。
- 弱候选还有个隐藏的一次性：`mCandidateRecord == null` 条件使**只有第一个弱候选被记住**，后续同 affinity 任务不再顶替——先来后到按栈序。

`rootAffinity` 的来源在 Task.setIntent（[Task.java#L1086](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1086)）：任务第一次被赋予意图时，若 `rootAffinity` 还是 null 就取该意图的 taskAffinity——**根 affinity 一经设定不再随后续 setIntent 改变**（`mNeverRelinquishIdentity` 为 true 的任务连 intent 都不换，回收站里恢复的任务身份不漂移）。

### 4.4 理想解为什么不看 launchMode

注意 4.3 的三分支里**没有任何 launchMode 判断**（除了排除 singleInstance 栈顶那条闸）。findTask 只回答「哪个任务最配装这次启动」；「装进去之后清不清栈、送不送 onNewIntent」是 `complyActivityFlags` 按 launchMode/flag 裁决的（3.7 节）。**匹配与处置分离**——这是理解「standard + NEW_TASK 也会复用任务」的关键：standard 活动带 NEW_TASK 启动时同样过 `putIntoExistingTask` 准入、同样跑 findTask，匹配到同 affinity 任务就复用；launchMode 影响的是准入条件（singleTask/singleInstance 免 NEW_TASK 也查）与后续处置，不是「有没有资格被匹配」。

### 4.5 isCompatibleActivityType：不对称的兼容矩阵

两道类型闸共用同一个函数：

[ConfigurationContainer.java#L711](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ConfigurationContainer.java#L711)

```java
public static boolean isCompatibleActivityType(int currentType, int otherType) {
    if (currentType == otherType) {
        return true;
    }
    if (currentType == ACTIVITY_TYPE_ASSISTANT) {
        // Assistant activities are only compatible with themselves...
        return false;
    }
    // Otherwise we are compatible if us or other is not currently defined.
    return currentType == ACTIVITY_TYPE_UNDEFINED || otherType == ACTIVITY_TYPE_UNDEFINED;
}
```

类型常量（[WindowConfiguration.java#L130](../../../../AAOS13_study/frameworks/base/core/java/android/app/WindowConfiguration.java#L130)）：UNDEFINED=0、STANDARD=1、HOME=2、RECENTS=3、ASSISTANT=4、DREAM=5。把矩阵摊开：

| 请求 \ 任务 | UNDEFINED | STANDARD | HOME | RECENTS | ASSISTANT | DREAM |
| --- | --- | --- | --- | --- | --- | --- |
| **UNDEFINED** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **STANDARD** | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ |
| **HOME** | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ |
| **RECENTS** | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| **ASSISTANT** | ✗* | ✗ | ✗ | ✗ | ✓ | ✗ |
| **DREAM** | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |

\* ASSISTANT 行首格：`currentType==otherType` 先判，UNDEFINED≠ASSISTANT，再进 ASSISTANT 独占分支返回 false——助手请求连 UNDEFINED 任务都不匹配。但反过来（请求 UNDEFINED、任务 ASSISTANT）最后一行兜底返回 true，**函数在 ASSISTANT 上是不对称的**，其余类型对称。

矩阵的要害一眼可见：

> **【核心结论二】UNDEFINED 与一切兼容；HOME 与 STANDARD 互斥。** 结合核心结论一（非 HOME 的新记录在匹配期是 UNDEFINED，装过 STANDARD 活动的任务类型是 STANDARD，HOME 任务的类型是 HOME），得到事故的两条铁律：
> - **HOME 请求看不见 STANDARD 任务**——isCompatibleActivityType(HOME, STANDARD) = false，任务级闸直接 `Skipping task: (mismatch activity/task)`。
> - **普通请求（UNDEFINED）看得见一切任务**——包括 HOME 任务。

这两条的「不对称」正是雅迪事故的机关：桌面请求是排他的（不会误落进普通应用任务），普通请求是泛匹配（会落进桌面任务）——**单向透明玻璃**。

设计动机可以从容器树的角度理解：HOME 任务挂在 home 根任务下、参与「回桌面」语义（1.5 节的 `mRootHomeTask` 缓存、3.4 节的跨屏否决），若允许 HOME 请求复用 STANDARD 任务，桌面就会「住进」普通应用的任务里，back 键语义、最近任务分类、`moveTaskToBack` 全乱。类型隔离保的是**任务树的语义分区**。而 UNDEFINED 的「与一切兼容」不是宽容，是**未决**：类型还没定的东西不该拒绝任何落点，等落点定了类型再补写（2.7 节）。

### 4.6 findTask 全流程串讲

把 4.1–4.5 串成一条判定流水线，供第 5、8 章反复引用：

1. **取请求侧四元组**：记录类型（HOME/UNDEFINED）、taskAffinity（`1000:com.yadea.launcher` 形态）、意图（组件、data）、info（归属用户、targetActivity）。
2. **首选 TDA 自顶向下扫叶任务**，每个任务过三道任务级闸：类型兼容（请求类型 × 任务类型）、非语音会话、同用户。
3. **取任务栈顶非 finishing 记录**，过记录级闸：存在、非 finishing、同用户、非 singleInstance、类型兼容（记录类型 × 请求类型）。
4. **意图比对**：realActivity 或 affinityIntent 组件全等 → 理想解，**短路返回**；否则 rootAffinity 相等且尚无候选 → 记弱候选，**继续扫**。
5. 首选 TDA 无理想解 → 扫其余 TDA（只认理想解）→ 仍无 → 返回弱候选或 null。
6. 返回的**活动记录**经 `getReusableTask` 两道否决（singleInstancePerTask 根组件校验、HOME 跨屏否决）后 `.getTask()`，成为 `startActivityInner` 的 `reusedTask`。

### 4.7 把事故套进匹配器：三次演算

以事故时刻的任务树状态（第 8 章给完整时间线，这里只取必要快照）做三次 findTask 演算。

**演算一：Monkey 首启 A（06.310）**。场上任务：Task 1（HOME 根，空）。
请求侧：类型 UNDEFINED（MAIN+LAUNCHER 不是 HOME 意图）、cls=MainActivity、affinity=1000:com.yadea.launcher。
扫 Task 1：任务级类型闸 UNDEFINED×HOME ✓ 过；记录级 `r == null`（空任务）✗ 拒。
结果 null → `newTask=true` → 3.6 节路径 → 新建 STANDARD 根 Task 2，A 入栈，双双补成 standard。

**演算二：SystemUI goLauncher 启 B（10.750）**。场上任务：Task 1（HOME 根，空）、Task 2（STANDARD，栈顶 A，type=standard 活字段见日志 122164 行）。
请求侧：类型 **HOME**（MAIN+CATEGORY_HOME，2.5 节三关全过）、cls=MainActivity（解析回写后）、affinity 同上。
自顶向下：Task 2 在顶（10.750 时 A 仍是焦点），任务级类型闸 **HOME×STANDARD → false**，`Skipping task: (mismatch activity/task)`【源码事实推演出的必然路径】；Task 1：类型闸 HOME×HOME ✓、voiceSession ✓、userId ✓，记录级 `r == null` ✗ 拒。
结果 null → `newTask=true` → `resolveActivityType` 得 HOME → TDA 命中 `mRootHomeTask`=Task 1 → `alwaysCreateRootTask(fullscreen, HOME)`=false → Task 1 下新建子 **Task 3**，B 入栈，B 与 Task 3 都是 HOME。
**A 还活着、就在一屏之下的 Task 2 里，findTask 却因类型闸视而不见——同一个 MainActivity 的两个实例就此并存。**

**演算三：Monkey 第 3 次启动（11.193，日志 123748 行）**。场上任务：Task 1（HOME 根，子 Task 3 装着 B，已被 moveToFront 提到顶）、Task 2（STANDARD，A 已被 pause）。
请求侧：类型 **UNDEFINED**（还是 MAIN+LAUNCHER）、cls=MainActivity。
自顶向下：先 Task 3——任务级闸 **UNDEFINED×HOME ✓**（核心结论二的下半句）；记录级：栈顶 B 非 finishing ✓、同用户 ✓、非 singleInstance ✓、类型闸 **HOME×UNDEFINED ✓**；意图比对：Task 3 的 realActivity=MainActivity 与 cls 全等 → **理想解，`return true` 短路**——遍历根本没走到 Task 2。
结果返回 **B** → `getReusableTask` 两道否决都不触发（非 singleInstancePerTask；请求类型 UNDEFINED 非 HOME、B 是 HOME 但同显示区）→ `reusedTask=Task 3` → `recycleTask` → `complyActivityFlags` 第二段（singleTask）→ `performClearTop` 命中 B 且其上无活动 → `deliverNewIntent(B)` → **`wm_new_intent [0,130421252,3,...]`，日志 123770 行**【日志事实与源码推演完全吻合】。

> **Q：演算三里，如果 Task 2 恰好压在 Task 3 上面（z 序相反），会发生什么？**
> A：自顶向下先试 Task 2：UNDEFINED×STANDARD ✓，栈顶 A 全过闸，cls 全等 → 理想解是 A，onNewIntent 送给 A。也就是说，**UNDEFINED 请求的落点由 z 序决定**——谁在最上面且类型兼容，谁得理想解。事故时刻 home 根任务刚被提到顶（10.796 行 startPausing Task 2 即为证据），所以 B 赢了。这也说明 Monkey 第 3 次「本意是点 Launcher 图标」却唤到了 B：类型泛匹配 + 栈序优先，两条规则叠加的必然结果，不是 bug 式的偶然。

> **Q：A 和 B 是同组件、同 affinity、同 launchMode 的两条记录，findTask 层面有没有任何机制能发现「已经有两个了」？**
> A：没有。findTask 是「为新请求找落点」的匹配器，不是「全局唯一性」的执法者。singleTask 的全部承诺是：**在本次匹配可见的范围内**清栈复用（3.7 节第二段）。匹配被类型闸截断时，承诺的管辖范围也就截断了。这就是交接文档里那条「singleTask ≠ 无条件全局唯一」的源码级表述。

### 4.8 本章小结

- findTask 自顶向下扫叶任务，任务级三闸 + 记录级五闸 + 意图三分支；理想解短路，弱候选续扫。
- 类型兼容矩阵的不对称是全文枢纽：**HOME 请求排他，UNDEFINED 请求泛匹配**。
- 非 HOME 新记录匹配期恒为 UNDEFINED——不是「还没设置」的疏漏，而是配合 `onDescendantActivityAdded` 延迟定型的**设计**（2.7 节注释原文为证）。
- 三次演算证明：A、B 并存与 Monkey 唤错对象，都是同一套规则的确定性输出。第 5 章把五种 launchMode 逐一放进这套规则里推导，第 8 章给完整时间线。

---

## 5. 五种 launchMode 逐一推导

launchMode 是 manifest 里的静态声明（`ActivityInfo` 常量：LAUNCH_MULTIPLE=0、LAUNCH_SINGLE_TOP=1、LAUNCH_SINGLE_TASK=2、LAUNCH_SINGLE_INSTANCE=3、LAUNCH_SINGLE_INSTANCE_PER_TASK=4，[ActivityInfo.java#L76](../../../../AAOS13_study/frameworks/base/core/java/android/content/pm/ActivityInfo.java#L76)）。第 3、4 章已经把公共主干讲完——**五种模式共享同一条 `startActivityInner` 流水线，分歧只发生在四个裁决点**：

| 裁决点 | 位置 | 模式差异 |
| --- | --- | --- |
| ① flag 归一 | setInitialState / computeLaunchingTaskFlags（3.2/3.3 节） | singleTask、singleInstance、singleInstancePerTask 强制 NEW_TASK |
| ② 复用查找准入与选型 | getReusableTask（3.4 节） | singleTask 等免 NEW_TASK 也查；singleInstance 走 findActivity 专线；其余走 findTask |
| ③ 栈顶短路 | deliverToCurrentTopIfNeeded（3.7 节） | singleTop（或 SINGLE_TOP flag）专属 |
| ④ 复用后处置 | complyActivityFlags（3.7 节） | singleTask/singleInstance/singleInstancePerTask 走清栈分支；同组件 singleTop 送 onNewIntent；standard 压新实例 |

本章不再重复主干，逐个模式把四个裁决点的取值代进去，用栈推演验证行为。栈记法：`T1[A B C]` 表示任务 T1 自底向上 A、B、C，`→` 表示一次启动，`*` 标记收到 onNewIntent 的实例。

### 5.1 standard（LAUNCH_MULTIPLE）：默认值，实例不设限

**四个裁决点取值**：① 不强制 NEW_TASK（仅非 Activity 上下文启动时被迫加，3.3 节）；② `putIntoExistingTask` 只在意图带 NEW_TASK 且非 MULTIPLE_TASK 时为真——**standard 自己没资格发起复用查找，全靠 flag**；③ 不适用（除非同时带 SINGLE_TOP flag）；④ 落进 `complyActivityFlags` 最后的普通压栈分支（`mAddingToTask = true`）。

**推演 1：应用内跳转（无 flag）**。T1[A] 里 A 启动 B（standard）：
`computeTargetTask` 第二分支返回源任务 → targetTask=T1 → 栈顶 A 与 B 不同组件 → 普通压栈。

```text
T1[A]      → startActivity(B)      → T1[A B]
T1[A B]    → startActivity(B)      → T1[A B B]     ← 实例不设限
```

第二次启动 B 照样压新实例——这就是 LAUNCH_MULTIPLE 名字的含义：**允许多实例**。

**推演 2：standard + NEW_TASK**。图标点击（Launcher 发起，带 NEW_TASK|RESET_TASK_IF_NEEDED，即事故日志里 Monkey 的 0x10200000）：
`getReusableTask` 准入通过 → findTask：同组件任务给理想解，同 affinity 任务给弱候选。

```text
（无匹配任务） T2 不存在        → 新建 T2[B]
（弱候选）    T2[X Y]（affinity 同） → B 压进 T2 → T2[X Y B]
（理想解）    T2[B X]           → ④清栈？不——standard 不在清栈分支，
                                  同组件分支要求 SINGLE_TOP 语义才送 onNewIntent，
                                  standard 无此语义 → isSameIntentFilter 判定：
                                  同意图 → reusedActivity 非空 → 什么都不做，任务前置
```

第三行的「什么都不做」值得展开：`complyActivityFlags` 第四分支（[ActivityStarter.java#L2501](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2501)）里，同组件 + 无 singleTop 语义 + `isSameIntentFilter` 相同 + `reusedActivity == null` 不成立时，**四个出口全不命中，`mAddingToTask` 保持 false**——不压新实例、不送 onNewIntent，本次启动退化为「把既有任务搬到前台」（`setTargetRootTaskIfNeeded` 的 moveTaskToFront，返回 `START_TASK_TO_FRONT`）。standard + NEW_TASK 重复点图标的常见体验「任务弹到前面，页面还是原样」就是这条路径。

**推演 3：CLEAR_TOP 打在 standard 上**。T1[A B C]，启动 B 带 CLEAR_TOP：

[Task.java#L1802](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1802)

```java
private ActivityRecord clearTopActivities(ActivityRecord newR, int launchFlags,
        int[] finishCount) {
    final ActivityRecord r = findActivityInHistory(newR.mActivityComponent, newR.mUserId);
    if (r == null) return null;

    final PooledPredicate f = PooledLambda.obtainPredicate(
            (ActivityRecord ar, ActivityRecord boundaryActivity) ->
                    finishActivityAbove(ar, boundaryActivity, finishCount),
            PooledLambda.__(ActivityRecord.class), r);
    forAllActivities(f);
    f.recycle();

    // Finally, if this is a normal launch mode (that is, not expecting onNewIntent()), then we
    // will finish the current instance of the activity so a new fresh one can be started.
    if (r.launchMode == ActivityInfo.LAUNCH_MULTIPLE
            && (launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0
            && !ActivityStarter.isDocumentLaunchesIntoExisting(launchFlags)) {
        if (!r.finishing) {
            r.finishIfPossible("clear-task-top", false /* oomAdj */);
        }
    }

    return r;
}
```

`findActivityInHistory`（[Task.java#L1934](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1934)）按「组件全等 + 同用户 + 非 finishing」找 B，`finishActivityAbove`（[Task.java#L1828](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1828)）逐个终结 B 之上的活动（C），然后**关键分支**：B 是 LAUNCH_MULTIPLE 且没带 SINGLE_TOP → **连 B 自己也 finish**（注释：normal launch mode 不期待 onNewIntent，杀旧启新）。回到 `complyActivityFlags`，`clearTop` 返回的 B 已 finishing → 走 else：`mAddingToTask = true`，压一个全新 B：

```text
T1[A B C]  → startActivity(B, CLEAR_TOP)  → T1[A] + 新B → T1[A B']    ← B 重建，onCreate 重走
T1[A B C]  → startActivity(B, CLEAR_TOP|SINGLE_TOP) → T1[A B*]        ← B 保活，onNewIntent
```

**standard 的 CLEAR_TOP 默认是「清到 B 且换血 B」**；想保 B 必须追加 SINGLE_TOP。这是 flag 组合改变 launchMode 行为的第一个例子。

### 5.2 singleTop（LAUNCH_SINGLE_TOP）：只管栈顶一层

**四个裁决点取值**：① 不强制 NEW_TASK；② **不在 `putIntoExistingTask` 的 launchMode 名单里**——singleTop 自己不触发任务查找，行为完全依附于「它恰好在不在栈顶」；③ **专属裁决点**；④ 同组件分支送 onNewIntent。

**裁决点 ③ 全文**（3.7 节已引）：`dontStart` 要求焦点根任务栈顶同组件、同用户、已附着进程、且 `(SINGLE_TOP flag || launchMode==singleTop)`。满足则 `deliverNewIntent(top)` 并返回 `START_DELIVERED_TO_TOP`——**新记录 mStartActivity 直接被丢弃**（`handleStartResult` 不入任务）。

**推演**：

```text
T1[A C]    → startActivity(C)   C 在栈顶      → T1[A C*]        onNewIntent(C)
T1[A C]    → startActivity(A)   A 不在栈顶    → T1[A C A]       ← 照样压新实例！
T1[A C]（C 未附着进程，冷启动中） → startActivity(C) → attachedToProcess()==false
                                                  → 短路失败 → 正常启动流程
```

第二行是 singleTop 最常被误解的地方：**它不是「任务里只有一个」，是「栈顶只有一个」**。A 埋在 C 下面时，再启 A 照压新实例。第三行是 `attachedToProcess()` 条件的意义：记录存在但进程没绑上时，onNewIntent 无处可送，宁可走完整启动（最终 `realStartActivityLocked` 会把 pending newIntents 一起带过去，第 6 章）。

**裁决点 ④ 的 singleTop 出口**（同组件分支，[ActivityStarter.java#L2507](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java#L2507)）与 ③ 的区别：③ 看的是**焦点根任务**的栈顶（与目标任务无关，纯前台判定），④ 看的是 **findTask/computeTargetTask 选中的目标任务**的栈顶。带 NEW_TASK 的 singleTop 启动走 ②→④ 链，普通跳转走 ③。两条路殊途同归到 `deliverNewIntent`。

singleTop 与 SINGLE_TOP flag 在代码里始终并列出现（`(mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0 || LAUNCH_SINGLE_TOP == mLaunchMode`）——**flag 是「本次请求要 singleTop 行为」，launchMode 是「这个活动永远是 singleTop 行为」**，静态声明等价于每次请求都带上那个 flag。

### 5.3 singleTask（LAUNCH_SINGLE_TASK）：清栈复用，但管辖范围有限

**四个裁决点取值**：① 强制 NEW_TASK（3.3 节第三分支）；② **免 flag 准入**——`isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)` 直接进查找，走 findTask；③ 不适用；④ 清栈分支（与 CLEAR_TOP 同段）。

**裁决点 ④ 的执行链**（3.7 节已引代码）：`performClearTop`（[Task.java#L1773](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1773)）→ `clearTopActivities`：找到组件匹配的记录 r，终结 r 之上所有活动；**r 自己是 SINGLE_TASK 不是 LAUNCH_MULTIPLE，不触发自杀分支** → r 存活 → `deliverNewIntent(r)`。

**推演**：

```text
（无匹配）        → findTask 返回 null → 新建任务 T2[B]（B 是根）
T2[B X Y]  → startActivity(X')   X 是 singleTask，findTask 理想解命中 T2
           → 清掉 X 之上的 Y → T2[B X*]                onNewIntent(X)
T2[B X]    → 从 T2 外启动 X（singleTask）
           → 同上：T2 前置，X 清顶收 onNewIntent
T3[P Q]（affinity 与 B 相同，组件不同） → startActivity(B, singleTask)
           → findTask 弱候选命中 T3 → 清栈分支：findActivityInHistory(B) 在 T3 找不到
           → clearTop==null → mAddingToTask=true → T3[P Q B]   ← B 进了别人的任务！
```

第四行推演揭示 singleTask 的完整语义：**「在自己的任务里清栈唯一」，但「哪个任务算自己的」由 findTask 的 affinity 弱候选决定**——同 affinity 的既有任务会被认作落点，B 作为新实例压进去（此时它并不是该任务里唯一的历史遗留，只是从此以后清栈机制保证它上方不再堆叠同组件实例）。「singleTask 活动一定独占一个任务」是流传最广的错误认知，弱候选路径就是反例。

**管辖范围有限的四个边界**（全部来自第 4 章的闸门，逐条对应）：

1. **类型闸**：HOME 请求看不见 STANDARD 任务，UNDEFINED 请求泛匹配——雅迪事故（4.7 节演算二/三）。
2. **用户闸**：其他用户的同组件任务不参与匹配（车机多用户：驾驶员账号的 Launcher 任务对乘客账号请求不可见）。
3. **finishing/空任务闸**：栈顶正在收尾的任务不算候选——第 8 章 Tasks 4/5 的成因。
4. **跨屏否决**（getReusableTask 层）：HOME 记录不许跨 TDA 复用。

> **Q：交接文档里那句「singleTask ≠ 无条件全局唯一」，最精确的源码表述是什么？**
> A：singleTask 的唯一性由「findTask 可见范围内的清栈复用」实现（裁决点 ②+④），findTask 的可见范围受类型、用户、finishing、显示区四重闸限制。**闸门外存在同组件实例时，singleTask 不感知、不合并、不报错**——它会安静地再建一个。全局唯一性从来不是 launchMode 的承诺，是「匹配器可见性」的副产品。

### 5.4 singleInstance（LAUNCH_SINGLE_INSTANCE）：全局专线，独占任务

**四个裁决点取值**：① 强制 NEW_TASK；② **不走 findTask，走 `findActivity` 全局专线**；③ 不适用；④ 清栈分支（但任务里只有它自己，清栈是空操作）。

**专线查找**（[RootWindowContainer.java#L2946](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L2946)）：

```java
ActivityRecord findActivity(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
    ComponentName cls = intent.getComponent();
    if (info.targetActivity != null) {
        cls = new ComponentName(info.packageName, info.targetActivity);
    }
    final int userId = UserHandle.getUserId(info.applicationInfo.uid);

    final PooledPredicate p = PooledLambda.obtainPredicate(
            RootWindowContainer::matchesActivity, PooledLambda.__(ActivityRecord.class),
            userId, compareIntentFilters, intent, cls);
    final ActivityRecord r = getActivity(p);
    p.recycle();
    return r;
}

private static boolean matchesActivity(ActivityRecord r, int userId,
        boolean compareIntentFilters, Intent intent, ComponentName cls) {
    if (!r.canBeTopRunning() || r.mUserId != userId) return false;

    if (compareIntentFilters) {
        if (r.intent.filterEquals(intent)) {
            return true;
        }
    } else {
        // Compare the target component instead of intent component so we don't miss if the
        // activity uses alias.
        if (r.mActivityComponent.equals(cls)) {
            return true;
        }
    }
    return false;
}
```

与 findTask 的三处本质区别：

- **找记录不找任务**：`getActivity(p)` 全容器树扫记录，按「组件全等（或意图 filterEquals）+ 同用户 + canBeTopRunning」匹配，**没有任务类型闸、没有 affinity 概念**——singleInstance 的匹配维度只剩组件本身。
- `compareIntentFilters` 实参是 `mStartActivity.isActivityTypeHome()`（3.4 节引文）：HOME 型的 singleInstance 按完整意图过滤匹配（桌面意图形状敏感），普通 singleInstance 按组件匹配。
- 匹配即终局：找到唯一记录，其所在任务就是复用目标；`setTargetRootTaskIfNeeded` 把该任务 moveTaskToFront。

**独占性的双向保证**：

1. **它不别人**：findActivity 找到记录后复用的任务里只可能有它（下一条保证），清栈分支自然空转。
2. **别人不它**：两处——`computeLaunchingTaskFlags` 第二分支（3.3 节）：**源活动是 singleInstance 时，它启动的任何活动强制 NEW_TASK**，落不进它的任务；`matchingCandidate` 记录级闸（4.3 节）：**栈顶是 singleInstance 记录的任务被 findTask 直接排除**，别的请求也无法弱候选进来。
3. **affinity 隔离**（2.4 节）：`computeTaskAffinity` 给它加 `-si:` 前缀（`1000-si:com.example` 形态），与同包普通活动的 affinity 字符串天然不同——即便有人想按 affinity 弱候选撞进来也撞不上。

**推演**：

```text
T1[A]  → startActivity(S, singleInstance)  → findActivity 无果 → 新建 T2[S]
T2[S]  → S 里 startActivity(D)（standard） → 源是 singleInstance → 强制 NEW_TASK
                                            → 新建/复用 T3[D]     ← D 不可能进 T2
T3[D]  → 再启动 S                          → findActivity 命中 T2 里的 S
                                            → T2 前置，S 收 onNewIntent → T2[S*]
```

**代价**：singleInstance 任务在「最近任务」里独立成卡片，back 行为、任务切换动画都按独立任务处理；且它启动的活动全被赶到别的任务，返回栈割裂（从 D 返回不会回到 S 所在任务的原位置，而是按任务序切换）。车机上典型用途是来电/语音这类「必须全局唯一、必须立刻可见、不与应用任务纠缠」的界面；滥用会让最近任务列表碎片化。

### 5.5 singleInstancePerTask（LAUNCH_SINGLE_INSTANCE_PER_TASK）：每任务一个，Android 12 新贵

**四个裁决点取值**：① 强制 NEW_TASK（setInitialState 专属分支，注释：「so that the activity won't be launched in source record's task」）；② 走 findTask，**但加一道根组件校验**；③ 不适用；④ 清栈分支（名单里与 singleTask 并列）。

**裁决点 ② 的专属否决**（3.4 节已引）：

```java
if (intentActivity != null && mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK
        && !intentActivity.getTask().getRootActivity().mActivityComponent.equals(
        mStartActivity.mActivityComponent)) {
    // The task could be selected due to same task affinity. Do not reuse the task while
    // starting the singleInstancePerTask activity if it is not the task root activity.
    intentActivity = null;
}
```

翻译：findTask 的匹配结果只有在「**匹配到的记录是任务根、且根组件与本次组件全等**」时才被接受——affinity 弱候选一律否决。名字的准确读法是 **single-instance-per-task：在每个任务内唯一**，跨任务不设限。

**推演**（配合 MULTIPLE_TASK / 文档语义才能看出它与 singleTask 的差别）：

```text
T2[B]（B 是根，singleInstancePerTask） → 再启动 B（无 MULTIPLE_TASK）
    → findTask 理想解命中 T2 根 → 校验通过 → 清栈 + onNewIntent → T2[B*]
T2[B X] → 启动 B 带 MULTIPLE_TASK
    → getReusableTask 准入被 MULTIPLE_TASK 否决 → 新建 T3[B]
    → 此刻 B 有两个实例：T2[B ...] 与 T3[B]      ← 合法！每任务一个
T3[P Q]（affinity 同 B） → 启动 B
    → findTask 弱候选命中 T3 → 根组件校验：T3 根是 P ≠ B → 否决
    → 新建 T4[B]                                 ← singleTask 在这里会压进 T3
```

第三行是与 singleTask 的分水岭：**singleTask 接受 affinity 弱候选（压进别人任务），singleInstancePerTask 拒绝（必须自己当根）**。它的典型客户是多窗口/气泡/文档类场景：同一活动要在多个任务里各有一份（每任务唯一），但绝不允许两份挤在同一任务、也不允许寄生在别人的任务里。车机上的现实存在感很低（雅迪全树没有一个活动用它【源码事实·应用树】），但读 A13 源码绕不开——3.2/3.3/3.4/3.7 四处都有它的专属分支。

### 5.6 五模式对照总表与车机落位

| 维度 | standard | singleTop | singleTask | singleInstance | singleInstancePerTask |
| --- | --- | --- | --- | --- | --- |
| 强制 NEW_TASK | 否（仅无源上下文） | 否 | 是 | 是 | 是 |
| 复用查找 | 仅当带 NEW_TASK，findTask | 不主动查 | findTask（免 flag） | findActivity 全局专线 | findTask + 根组件校验 |
| affinity 弱候选 | 接受 | — | **接受**（可进别人任务） | —（无 affinity 概念） | **否决** |
| 命中后处置 | 压新实例（或任务前置） | 栈顶→onNewIntent | 清栈→onNewIntent | 任务前置→onNewIntent | 清栈→onNewIntent |
| 未命中 | 压源任务/新任务 | 压新实例 | 新任务，自己当根 | 新任务，独占 | 新任务，自己当根 |
| 实例上限 | 无 | 每栈顶一个 | 每「findTask 可见域」一个 | 每用户全局一个 | 每任务一个 |
| 任务里能住别人吗 | 能 | 能 | 能（同 affinity 活动可同任务） | **不能**（双向排斥） | 能（它当根，上面可压别的活动） |

**车机落位**【源码事实·应用树】：雅迪 Launcher 的 MainActivity、AppListActivity、LinkActivity 全部 singleTask（AppList/Link 还各带独立 taskAffinity，com.yadea.launcher.applist / com.yadea.launcher.link——按 5.3 的弱候选规则，它们不会互相串任务）；SystemUI 的 MainActivity 无声明即 standard。AAOS 原生侧，CarLauncher 的 Launcher activity 同样是 singleTask 家族（AOSP packages/apps/Car/Launcher，不在本仓，仅注明）。**车机比手机更依赖 singleTask 的原因**：仪表盘/中控多屏、语音与方控按键随时「回桌面」，桌面必须清栈复用而不是无限堆叠——但这套依赖恰好把「类型闸管辖范围」问题放大成了雅迪事故：手机桌面上很少有第二个入口用不同意图形状启动同一个 HOME 活动，车机上 SystemUI 按键、Monkey 测试、方控信号三方并发是常态。

> **Q：既然 singleInstance 才是「全局唯一」，雅迪为什么不用它做 Launcher？**
> A：因为 singleInstance 的双向排斥（5.4 节）会把从桌面打开的所有应用全赶到别的任务，最近任务与返回栈全乱；而且它解决不了本案问题——事故根源是 **HOME 与 STANDARD 的类型不对称**，与 launchMode 无关：就算 MainActivity 是 standard，HOME 请求（B）照样看不见 A 的 STANDARD 任务（4.7 演算二的闸门在任务类型层面，先于一切 launchMode 逻辑）。真正对症的修法在第 8 章。

---

## 6. 跨进程：从 resume 裁决到客户端 onCreate 与 onNewIntent

第 3 章末尾，`startActivityInner` 把接力棒交给 `resumeFocusedTasksTopActivities`。从这里开始，故事跨出「裁决」进入「执行」：服务端要先暂停旧栈顶、再决定新记录是热启动（进程活着，直接发事务）还是冷启动（先拉进程），最后把 LaunchActivityItem 事务送进应用进程，客户端在主线程上顺序执行 onCreate → onNewIntent（若有 pending）→ onResume。本章走完这条链，并把三个 500ms 级超时讲清楚——事故日志里 A 卡死三秒、B 靠超时强行上位的剧情，全靠这套机制兜底。

**跨树口径提醒**【未核实→结构差异已核实】：本章行号与方法结构以学习树为准。雅迪实机的栈帧（日志 124968 行）显示其构建里 pause/resume 状态机位于 `Task.resumeTopActivityInnerLocked`（Task.java:5047）→ `TaskFragment.resumeTopActivity`（TaskFragment.java:1483），而学习树已把这套逻辑整合进 `TaskFragment.resumeTopActivity` 单方法（无 Inner 拆分）。**方法名与行号跨树不同，控制流语义一致**；引用雅迪栈帧时以日志为准，引用代码时以学习树为准。

### 6.1 resumeFocusedTasksTopActivities：多屏各自恢复

[RootWindowContainer.java#L2424](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java#L2424)

```java
boolean resumeFocusedTasksTopActivities(
        Task targetRootTask, ActivityRecord target, ActivityOptions targetOptions,
        boolean deferPause) {
    if (!mTaskSupervisor.readyToResume()) {
        return false;
    }

    boolean result = false;
    if (targetRootTask != null && (targetRootTask.isTopRootTaskInDisplayArea()
            || getTopDisplayFocusedRootTask() == targetRootTask)) {
        result = targetRootTask.resumeTopActivityUncheckedLocked(target, targetOptions,
                deferPause);
    }

    for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
        final DisplayContent display = getChildAt(displayNdx);
        ...
```

两段式：先恢复**指定目标**（本次启动涉及的根任务，要求它是所在显示区栈顶或全局焦点），再**逐显示**恢复各自焦点根任务的栈顶。第二段是多屏车机的关键：AAOS 允许多个屏各有自己的 resumed activity（multi-resume），主屏桌面与副屏应用可以同时处于 RESUMED——`resumeTopActivity` 里随处可见的 `taskDisplayArea.allResumedActivitiesComplete()` 检查就是为这个环境写的。

### 6.2 resumeTopActivity：恢复必先暂停

`resumeTopActivityUncheckedLocked` 逐层下探到目标 TaskFragment 的 `resumeTopActivity`（[TaskFragment.java#L1265](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java#L1265)）：

```java
final boolean resumeTopActivity(ActivityRecord prev, ActivityOptions options,
        boolean deferPause) {
    ActivityRecord next = topRunningActivity(true /* focusableOnly */);
    if (next == null || !next.canResumeByCompat()) {
        return false;
    }

    next.delayedResume = false;

    // If we are currently pausing an activity, then don't do anything until that is done.
    final boolean allPausedComplete = mRootWindowContainer.allPausedActivitiesComplete();
    if (!allPausedComplete) {
        ProtoLog.v(WM_DEBUG_STATES,
                "resumeTopActivity: Skip resume: some activity pausing.");
        return false;
    }

    // If the top activity is the resumed one, nothing to do.
    if (mResumedActivity == next && next.isState(RESUMED)
            && taskDisplayArea.allResumedActivitiesComplete()) {
        ...
        executeAppTransition(options);
        ...
```

第一道闸 `allPausedActivitiesComplete()`：**全局有任何活动还在 PAUSING，本轮 resume 直接放弃**（`Skip resume: some activity pausing`）。生命周期从不越级——新活动必须等旧活动的 pause 回执。放弃不是丢失：pause 完成时 `completePause` 会再次调度 resume，这是台事件驱动的状态机，不是一次性调用。

往下走，若本 TaskFragment 有正在 resumed 的旧活动，进入 pause 分支（[TaskFragment.java#L1346](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java#L1346)）：

```java
boolean pausing = !deferPause && taskDisplayArea.pauseBackTasks(next);
if (mResumedActivity != null) {
    ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Pausing %s", mResumedActivity);
    pausing |= startPausing(mTaskSupervisor.mUserLeaving, false /* uiSleeping */,
            next, "resumeTopActivity");
}
if (pausing) {
    ProtoLog.v(WM_DEBUG_STATES, "resumeTopActivity: Skip resume: need to"
            + " start pausing");
    // At this point we want to put the upcoming activity's process
    // at the top of the LRU list, since we know we will be needing it
    // very soon and it would be a waste to let it get killed if it
    // happens to be sitting towards the end.
    if (next.attachedToProcess()) {
        next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                true /* activityChange */, false /* updateOomAdj */,
                false /* addPendingTopUid */);
    } else if (!next.isProcessRunning()) {
        // Since the start-process is asynchronous, if we already know the process of next
        // activity isn't running, we can start the process earlier to save the time to wait
        // for the current activity to be paused.
        final boolean isTop = this == taskDisplayArea.getFocusedRootTask();
        mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                isTop ? HostingRecord.HOSTING_TYPE_NEXT_TOP_ACTIVITY
                        : HostingRecord.HOSTING_TYPE_NEXT_ACTIVITY);
    }
    ...
    return true;
}
```

三个细节：

- `pauseBackTasks(next)`：同显示区内被 next 完全遮挡的**后台任务**的栈顶也要暂停（多任务可见性收敛），不只是本片段的前任。
- `startPausing`（[TaskFragment.java#L1695](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java#L1695)）打出事故日志里那条关键打印——`startPausing: taskFrag =%s mResumedActivity=%s`，日志 122164 行正是它把 Task 2 的活字段状态定格进了事故档案（第 7 章）。它内部给旧活动发 PauseActivityItem 并调用 `schedulePauseTimeout()` 上 500ms 保险（6.7 节）。
- **进程预热**：next 的进程若还没起来，`startProcessAsync` 在 pause 等待期就抢先拉进程——冷启动的进程 fork 与旧活动 pause 并行，这是启动耗时优化的经典手法（注释原话：save the time to wait for the current activity to be paused）。

pause 全部完成后再次进入 `resumeTopActivity`，走到终段（[TaskFragment.java#L1626](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java#L1626)）：

```java
} else {
    // Whoops, need to restart this activity!
    if (!next.hasBeenLaunched) {
        next.hasBeenLaunched = true;
    } else {
        if (SHOW_APP_STARTING_PREVIEW) {
            next.showStartingWindow(false /* taskSwich */);
        }
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
    }
    ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Restarting %s", next);
    mTaskSupervisor.startSpecificActivity(next, true, true);
}
```

`Restarting` 不等于「重来」——**首次启动也走这条**（`hasBeenLaunched` 为 false 时只是打标）；第二次及以后才补 starting window。事故日志 124687 行 `resumeTopActivity: Restarting ActivityRecord{7c61204 ...} t3` 就是 B 在这里被推上发射台。

### 6.3 startSpecificActivity：冷热分岔口

[ActivityTaskSupervisor.java#L1086](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L1086)

```java
void startSpecificActivity(ActivityRecord r, boolean andResume, boolean checkConfig) {
    // Is this activity's application already running?
    final WindowProcessController wpc =
            mService.getProcessController(r.processName, r.info.applicationInfo.uid);

    boolean knownToBeDead = false;
    if (wpc != null && wpc.hasThread()) {
        try {
            realStartActivityLocked(r, wpc, andResume, checkConfig);
            return;
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception when starting activity "
                    + r.intent.getComponent().flattenToShortString(), e);
        }

        // If a dead object exception was thrown -- fall through to
        // restart the application.
        knownToBeDead = true;
        // Remove the process record so it won't be considered as alive.
        mService.mProcessNames.remove(wpc.mName, wpc.mUid);
        mService.mProcessMap.remove(wpc.getPid());
    }

    r.notifyUnknownVisibilityLaunchedForKeyguardTransition();

    final boolean isTop = andResume && r.isTopRunningActivity();
    mService.startProcessAsync(r, knownToBeDead, isTop,
            isTop ? HostingRecord.HOSTING_TYPE_TOP_ACTIVITY
                    : HostingRecord.HOSTING_TYPE_ACTIVITY);
}
```

**热路径**：进程记录存在且 ApplicationThread 活着 → 直接 `realStartActivityLocked`。**冷路径**：`startProcessAsync` 异步走 zygote fork；进程起来后 `attachApplication` 回调再找 pending 的活动记录补 `realStartActivityLocked`（该回调链属进程启动域，本文不展开）。`andResume=true` 表示「启动完直接推到 RESUMED」；false 的场景（如 6.2 节预热失败后的补发、可见但暂停的启动）只发 Launch 不发 Resume。

对照事故：A 首启（06.789 wm_restart_activity 之前）走的是**冷路径**——Launcher 进程 13306 当时还没起来，startProcessAsync 拉进程，进程 attach 后才 realStart（客户端 onCreate 08.675，日志 114573 行，进程拉起耗时约 1.9s【日志事实】）。B 启动（11.395）走的是**热路径**——同包同进程，13306 已活着，`realStartActivityLocked` 立即执行。**A、B 共享一个进程，这个事实是第 8 章「一损俱损」剧情的物理基础。**

### 6.4 realStartActivityLocked：组装跨进程事务

[ActivityTaskSupervisor.java#L801](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L801)

```java
boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
        boolean andResume, boolean checkConfig) throws RemoteException {

    if (!mRootWindowContainer.allPausedActivitiesComplete()) {
        // While there are activities pausing we skipping starting any new activities until
        // pauses are complete. NOTE: that we also do this for activities that are starting in
        // the paused state because they will first be resumed then paused on the client side.
        ProtoLog.v(WM_DEBUG_STATES,
                "realStartActivityLocked: Skipping start of r=%s some activities pausing...",
                r);
        return false;
    }
    ...
```

pause 闸门在这里**再查一遍**——从 resumeTopActivity 到这里可能隔着进程启动的几百毫秒，期间状态可能变化。然后是事务组装的主体（[ActivityTaskSupervisor.java#L893](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L893) 起）：

```java
List<ResultInfo> results = null;
List<ReferrerIntent> newIntents = null;
if (andResume) {
    // We don't need to deliver new intents and/or set results if activity is going
    // to pause immediately after launch.
    results = r.results;
    newIntents = r.newIntents;
}
...
EventLogTags.writeWmRestartActivity(r.mUserId, System.identityHashCode(r),
        task.mTaskId, r.shortComponentName);
if (r.isActivityTypeHome()) {
    // Home process is the root process of the task.
    updateHomeProcess(task.getBottomMostActivity().app);
}
...
// Create activity launch transaction.
final ClientTransaction clientTransaction = ClientTransaction.obtain(
        proc.getThread(), r.token);

final boolean isTransitionForward = r.isTransitionForward();
final IBinder fragmentToken = r.getTaskFragment().getFragmentToken();
clientTransaction.addCallback(LaunchActivityItem.obtain(new Intent(r.intent),
        System.identityHashCode(r), r.info,
        mergedConfiguration.getGlobalConfiguration(),
        mergedConfiguration.getOverrideConfiguration(), r.compat,
        r.getFilteredReferrer(r.launchedFromPackage), task.voiceInteractor,
        proc.getReportedProcState(), r.getSavedState(), r.getPersistentSavedState(),
        results, newIntents, r.takeOptions(), isTransitionForward,
        proc.createProfilerInfoIfNeeded(), r.assistToken, activityClientController,
        r.shareableActivityToken, r.getLaunchedFromBubble(), fragmentToken));

// Set desired final state.
final ActivityLifecycleItem lifecycleItem;
if (andResume) {
    lifecycleItem = ResumeActivityItem.obtain(isTransitionForward,
            r.shouldSendCompatFakeFocus());
} else {
    lifecycleItem = PauseActivityItem.obtain();
}
clientTransaction.setLifecycleStateRequest(lifecycleItem);

// Schedule transaction.
mService.getLifecycleManager().scheduleTransaction(clientTransaction);
```

四个要点：

1. **`wm_restart_activity` 事件在此打出**，第二个字段又是 `System.identityHashCode(r)`——事故里用它区分 A（236566844）与 B（130421252）的每一次真启动（日志 107845、124951 行）。
2. **pending newIntents 只在 `andResume` 时随 Launch 事务带过去**。3.7 节 `deliverNewIntentLocked` 存下的「账」（活动没起来时 newIntents 暂存），在这里结算；`andResume=false` 时继续欠着——注释给了理由：马上要 pause 的活动没必要现在送意图。
3. **ClientTransaction = callbacks + 目标状态**：LaunchActivityItem 是 callback（干活），ResumeActivityItem/PauseActivityItem 是 lifecycle state request（声明终态）。客户端事务引擎执行完 callbacks 后，把活动状态机推进到声明的终态——**一次 IPC 携带「创建 + 恢复到顶」两步**，这是 A10 以来 ClientTransaction 机制替代旧式 scheduleLaunchActivity/scheduleResumeActivity 分散调用的核心收益。
4. `LaunchActivityItem.obtain` 的第二个参数 `System.identityHashCode(r)` 就是 **ident**：服务端记录的对象哈希随事务过江，成为客户端 `Activity.mIdent`（6.5 节）——服务端事件日志与客户端事件日志靠它对上号。

失败兜底（[ActivityTaskSupervisor.java#L991](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L991)）：RemoteException 第一次 → `launchFailed=true`、detach、重抛让上层重拉进程；**第二次 → `Second failure launching ..., giving up`，进程按 `2nd-crash` 处理，记录 finish**——启动失败不给第三次机会。

### 6.5 客户端执行：LaunchActivityItem → onCreate

事务到达应用进程后由 ActivityThread 的事务引擎在**主线程**顺序执行。LaunchActivityItem 的 execute（[LaunchActivityItem.java#L100](../../../../AAOS13_study/frameworks/base/core/java/android/app/servertransaction/LaunchActivityItem.java#L100)）：

```java
@Override
public void execute(ClientTransactionHandler client, IBinder token,
        PendingTransactionActions pendingActions) {
    Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
    ActivityClientRecord r = new ActivityClientRecord(token, mIntent, mIdent, mInfo,
            mOverrideConfig, mCompatInfo, mReferrer, mVoiceInteractor, mState, mPersistentState,
            mPendingResults, mPendingNewIntents, mActivityOptions, mIsForward, mProfilerInfo,
            client, mAssistToken, mShareableActivityToken, mLaunchedFromBubble,
            mTaskFragmentToken);
    client.handleLaunchActivity(r, pendingActions, null /* customIntent */);
    Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
}
```

`mIdent`、`mPendingNewIntents` 原样装进客户端的 `ActivityClientRecord`（进程内的活动档案，与服务端 ActivityRecord 一一对应，token 为键）。`handleLaunchActivity` → `performLaunchActivity`（[ActivityThread.java#L3933](../../../../AAOS13_study/frameworks/base/core/java/android/app/ActivityThread.java#L3933)）：

```java
private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    ActivityInfo aInfo = r.activityInfo;
    if (r.packageInfo == null) {
        r.packageInfo = getPackageInfo(aInfo.applicationInfo, r.compatInfo,
                Context.CONTEXT_INCLUDE_CODE);
    }

    ComponentName component = r.intent.getComponent();
    if (component == null) {
        component = r.intent.resolveActivity(
            mInitialApplication.getPackageManager());
        r.intent.setComponent(component);
    }
    ...
    ContextImpl appContext = createBaseContextForActivity(r);
    Activity activity = null;
    try {
        java.lang.ClassLoader cl = appContext.getClassLoader();
        activity = mInstrumentation.newActivity(
                cl, component.getClassName(), r.intent);
        ...
    }
    try {
        Application app = r.packageInfo.makeApplicationInner(false, mInstrumentation);
        ...
        synchronized (mResourcesManager) {
            mActivities.put(r.token, r);
        }

        if (activity != null) {
            ...
            activity.attach(appContext, this, getInstrumentation(), r.token,
                    r.ident, app, r.intent, r.activityInfo, title, r.parent,
                    r.embeddedID, r.lastNonConfigurationInstances, config,
                    r.referrer, r.voiceInteractor, window, r.configCallback,
                    r.assistToken, r.shareableActivityToken);
            ...
            if (r.isPersistable()) {
                mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
            } else {
                mInstrumentation.callActivityOnCreate(activity, r.state);
            }
            ...
```

- `newActivity`：ClassLoader 反射实例化——**Activity 对象诞生的一行**。
- `makeApplicationInner`：Application 未建则此刻建（`Application.onCreate` 先于一切 `Activity.onCreate` 的保证点）；已建则复用。**B 启动时 Launcher 进程和 Application 都已在（A 建的），这一步是纯复用**——双实例并存时两个 Activity 共享同一个 Application 单例，`onCreate` 不会再走第二次。
- `activity.attach(..., r.token, r.ident, ...)`：token 与 **ident** 注入 Activity 本体（[Activity.java#L8893](../../../../AAOS13_study/frameworks/base/core/java/android/app/Activity.java#L8893)，`mIdent = ident`）。
- `callActivityOnCreate` → `performCreate`（[Activity.java#L8977](../../../../AAOS13_study/frameworks/base/core/java/android/app/Activity.java#L8977)）：

```java
final void performCreate(Bundle icicle, PersistableBundle persistentState) {
    ...
    if (persistentState != null) {
        onCreate(icicle, persistentState);
    } else {
        onCreate(icicle);
    }
    EventLogTags.writeWmOnCreateCalled(mIdent, getComponentName().getClassName(),
            "performCreate");
    ...
```

**`wm_on_create_called` 的第一个字段就是 mIdent**——身份链闭环：服务端 `System.identityHashCode(ActivityRecord)` → LaunchActivityItem.mIdent → Activity.mIdent → 客户端事件日志。事故日志 114573 行 `wm_on_create_called: [236566844,...]`（A）与 128585 行 `[130421252,...]`（B）由此可以直接对号入座，不需要任何猜测【日志事实 + 源码事实互证】。顺带：`236566844 = 0xE19B93C`、`130421252 = 0x7C61204`，正是服务端记录打印 `ActivityRecord{e19b93c ...}`、`ActivityRecord{7c61204 ...}` 的十六进制哈希——十进制/十六进制同值互证，两条日志线拧成一股。

### 6.6 onNewIntent 的两条送达路径与顺序保证

**路径一：pending 随 Launch/Resume 结算**（冷启动或延迟交付场景）。resume 事务在客户端落到 `handleResumeActivity`（ActivityThread.java:5346），其内转调 `performResumeActivity`（[ActivityThread.java#L5266](../../../../AAOS13_study/frameworks/base/core/java/android/app/ActivityThread.java#L5266)）：

```java
try {
    r.activity.onStateNotSaved();
    r.activity.mFragments.noteStateNotSaved();
    checkAndBlockForNetworkAccess();
    if (r.pendingIntents != null) {
        deliverNewIntents(r, r.pendingIntents);
        r.pendingIntents = null;
    }
    if (r.pendingResults != null) {
        deliverResults(r, r.pendingResults, reason);
        r.pendingResults = null;
    }
    r.activity.performResume(r.startsNotResumed, reason);
    ...
```

**顺序是硬的：pendingIntents 在 performResume 之前**——应用侧感知为「先 onNewIntent 后 onResume」，onResume 里读到的意图状态一定是最新的。`deliverNewIntents`（[ActivityThread.java#L4250](../../../../AAOS13_study/frameworks/base/core/java/android/app/ActivityThread.java#L4250)）逐个 `callActivityOnNewIntent`。

**路径二：NewIntentItem 即时投递**（活动已在 RESUMED/PAUSED 且进程附着，3.7 节 `scheduleNewIntent` 分支）。客户端 [NewIntentItem.java#L62](../../../../AAOS13_study/frameworks/base/core/java/android/app/servertransaction/NewIntentItem.java#L62)：

```java
@Override
public void execute(ClientTransactionHandler client, ActivityClientRecord r,
        PendingTransactionActions pendingActions) {
    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityNewIntent");
    client.handleNewIntent(r, mIntents);
    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
}
```

`handleNewIntent` → 同一个 `deliverNewIntents`。它的 `getPostExecutionState()` 按 `mResume` 返回 ON_RESUME 或 UNDEFINED——纯送意图不动生命周期时不约束状态机。事故里 Monkey 第 2、3 次启动给 A/B 送的 onNewIntent 都走这条路径（目标活动进程已附着、状态 RESUMED/PAUSED）。

### 6.7 三个 500ms：服务端不被慢客户端拖死

服务端状态机的推进依赖客户端回执（pause 完成、top-resumed 释放），但客户端可能卡死。三道保险：

**PAUSE_TIMEOUT = 500ms**（[ActivityRecord.java#L429](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L429)）：

```java
// How long we wait until giving up on the last activity to pause.  This
// is short because it directly impacts the responsiveness of starting the
// next activity.
private static final int PAUSE_TIMEOUT = 500;
```

`startPausing` 发出 PauseActivityItem 后 `schedulePauseTimeout()`（[ActivityRecord.java#L6543](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L6543)）挂表；超时进 `mPauseTimeoutRunnable`（[ActivityRecord.java#L960](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L960)）：

```java
private final Runnable mPauseTimeoutRunnable = new Runnable() {
    @Override
    public void run() {
        // We don't at this point know if the activity is fullscreen,
        // so we need to be conservative and assume it isn't.
        Slog.w(TAG, "Activity pause timeout for " + ActivityRecord.this);
        synchronized (mAtmService.mGlobalLock) {
            if (!hasProcess()) {
                return;
            }
            mAtmService.logAppTooSlow(app, pauseTime, "pausing " + ActivityRecord.this);
            activityPaused(true);
        }
    }
};
```

`Activity pause timeout for ...` + `logAppTooSlow`（打出 ANR 风格的完整进程状态转储）+ **`activityPaused(true)` 强行替客户端签收 pause**——服务端状态机继续走，客户端欠的账之后自己还（迟到的 activityPaused 回执幂等处理）。事故日志 124681 行（11.298）`Activity pause timeout for ActivityRecord{e19b93c ... t2}` 就是这条：A 的进程主线程卡住没回 pause，500ms 后服务端替它签收，B 才得以在 11.299 进入 Restarting（124687 行）【日志事实与源码逐字对应】。

**TOP_RESUMED_STATE_LOSS_TIMEOUT = 500ms**（[ActivityTaskSupervisor.java#L187](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L187)）：multi-resume 环境里「谁是全局栈顶」是独占资源，旧栈顶要回执「我已释放 top-resumed 状态」；超时进 `TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG`（[ActivityTaskSupervisor.java#L2638](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L2638)）：

```java
case TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG: {
    final ActivityRecord r = (ActivityRecord) msg.obj;
    Slog.w(TAG, "Activity top resumed state loss timeout for " + r);
    if (r.hasProcess()) {
        mService.logAppTooSlow(r.app, r.topResumedStateLossTime,
                "top state loss for " + r);
    }
    handleTopResumedStateReleased(true /* timeout */);
} break;
```

同样套路：告警 + 替客户端签收。事故日志 124614 行（11.283）先于 pause timeout 15ms 打出 `Activity top resumed state loss timeout for ActivityRecord{e19b93c ... t2}`——A 连 top-resumed 释放都没回执，主线程卡死实锤【日志事实】。

**LAUNCH_TIMEOUT = 10s×HW_TIMEOUT_MULTIPLIER**（[ActivityTaskSupervisor.java#L184](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java#L184)）：启动整体超时，超了走 ANR 流程。事故里 B 从事务下发（11.395）到客户端 onCreate（14.167）耗时 2.77s，没碰到这道 10s 线，所以没有 ANR，只有两条 500ms 告警和一份 logAppTooSlow 转储。

三道保险的共同哲学：**服务端状态机永远前进，客户端回执只是加速器不是许可**——慢客户端得到的是告警与代签，不是全局停摆。代价是状态短暂撕裂：11.298–14.105 之间，服务端认为 A 已 paused、B 已 restart，而客户端 A 还自认 resumed——直到主线程解卡，把积压的事务与回调按序清偿（14.105 A top-lost/paused → 14.167 B create → 14.185 B top-gained → 14.188 A stop，日志 128467–128668 行，全部同 pid 13306【日志事实】）。这段「服务端超前、客户端追账」的窗口，就是第 8 章时间线上最诡异的 2.8 秒。

### 6.8 completeResumeLocked：账本核销

B 的 ResumeActivityItem 执行完、客户端回执 activityResumed 后，服务端 `completeResumeLocked`（[ActivityRecord.java#L6456](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java#L6456)）：

```java
void completeResumeLocked() {
    final boolean wasVisible = mVisibleRequested;
    setVisibility(true);
    ...
    idle = false;
    results = null;
    if (newIntents != null && newIntents.size() > 0) {
        mLastNewIntent = newIntents.get(newIntents.size() - 1);
    }
    newIntents = null;
    stopped = false;

    if (isActivityTypeHome()) {
        mTaskSupervisor.updateHomeProcess(task.getBottomMostActivity().app);
    }
    ...
```

`results`/`newIntents` 两个 pending 账本**无条件核销**（置 null）——不管之前送没送出去，resume 完成即视为结清；最后一个 newIntent 留档到 `mLastNewIntent`（dumpsys 可见）。HOME 记录额外触发 `updateHomeProcess`：把桌面进程登记为 home 进程（oom_adj 待遇、`mHomeProcess` 语义），B 是 HOME 类型所以走这行，A 不走——**同一进程因两条记录类型不同，被登记了两次身份**，这是双实例并存在进程管理层面的余波。

### 6.9 本章小结

- 链路：resumeFocusedTasksTopActivities → resumeTopActivity（pause 闸 → startPausing → Restarting）→ startSpecificActivity（冷热分岔）→ realStartActivityLocked（ClientTransaction：LaunchActivityItem + Resume/PauseActivityItem，ident 过江）→ 客户端 performLaunchActivity（newActivity → attach(mIdent) → onCreate → wm_on_create_called）→ performResumeActivity（pendingIntents 先于 performResume）。
- onNewIntent 两条路：pending 随 Launch 结算（andResume 时），或 NewIntentItem 即时投递。
- 三个超时（pause 500ms、top-resumed-loss 500ms、launch 10s）保证服务端状态机独立于客户端推进；事故里 A 卡死三秒的窗口全靠它们撑过去。
- ident 是跨进程、跨日志域追踪同一条记录的钥匙：服务端十六进制、事件日志十进制、客户端 mIdent，同值三面。

---

## 7. Task 日志怎么读：stringName 缓存与活字段

第 8 章要逐行读事故日志，而日志里密度最高的证据就是 `Task{...}` 打印。这类打印**一半是陈年缓存、一半是实时状态**，不分清就会把「type=undefined」当成现行事实——交接文档里最初的误判正源于此。先把两棵树的 toString 实现摆开对照。

### 7.1 学习树：整体缓存，只在 setIntent 时作废

[Task.java#L4001](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L4001)

```java
@Override
public String toString() {
    if (stringName != null) return stringName;
    StringBuilder sb = new StringBuilder(128);
    sb.append("Task{");
    sb.append(Integer.toHexString(System.identityHashCode(this)));
    sb.append(" #");
    sb.append(mTaskId);
    sb.append(" type=" + activityTypeToString(getActivityType()));
    if (affinity != null) {
        sb.append(" A=");
        sb.append(affinity);
    } else if (intent != null && intent.getComponent() != null) {
        sb.append(" I=");
        sb.append(intent.getComponent().flattenToShortString());
    } else if (affinityIntent != null && affinityIntent.getComponent() != null) {
        sb.append(" aI=");
        sb.append(affinityIntent.getComponent().flattenToShortString());
    }
    sb.append('}');
    return stringName = sb.toString();
}
```

- **第一次被打印时整串定格**，之后永远返回缓存。
- 全树唯一的作废点是 `setIntent`（[Task.java#L1094](../../../../AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/Task.java#L1094)，`stringName = null`）——任务换意图（如 CLEAR_TASK 复用、别名切换）时重建。
- **`setActivityType` 不作废缓存**（WindowConfiguration.setActivityType 只改 `mActivityType` 字段，Task 层没有钩子）：任务类型从 UNDEFINED 变成 STANDARD/HOME 之后，**缓存里的 `type=` 保持旧值**。
- 学习树的短格式到 `}` 为止：没有 U=、rootTaskId=、visible=、sz= 这些字段——**拿学习树格式知识去读雅迪日志会缺半边信息**，反之亦然。

### 7.2 雅迪树：缓存前缀 + 活字段后缀

雅迪实机的 toString（yadi 树 Task.java:3725，路径 /home/liang/Project/Reachauto/YaDi/yadi_android/frameworks/base/services/core/java/com/android/server/wm/Task.java）：

```java
@Override
public String toString() {
    StringBuilder sb = new StringBuilder(128);
    if (stringName != null) {
        sb.append(stringName);
        sb.append(" U=");
        sb.append(mUserId);
        final Task rootTask = getRootTask();
        if (rootTask != this) {
            sb.append(" rootTaskId=");
            sb.append(rootTask.mTaskId);
        }
        sb.append(" visible=");
        sb.append(shouldBeVisible(null /* starting */));
        sb.append(" visibleRequested=");
        sb.append(isVisibleRequested());
        sb.append(" mode=");
        sb.append(windowingModeToString(getWindowingMode()));
        sb.append(" translucent=");
        sb.append(isTranslucent(null /* starting */));
        sb.append(" sz=");
        sb.append(getChildCount());
        sb.append('}');
        return sb.toString();
    }
    sb.append("Task{");
    sb.append(Integer.toHexString(System.identityHashCode(this)));
    ...
    stringName = sb.toString();
    return toString();
}
```

结构是**两段拼接**：

| 段 | 内容 | 时效 |
| --- | --- | --- |
| 缓存前缀（stringName） | `Task{对象哈希 #taskId type=活动类型 A=affinity`（或 I=/aI=） | **首次打印时定格**，仅 setIntent 作废（yadi 树 Task.java:1020） |
| 活后缀 | `U=用户 rootTaskId=根任务id（仅当自己不是根） visible= visibleRequested= mode= translucent= sz=直接子节点数}` | **每次打印实时计算** |

由此得到四条铁律，第 8 章逐条要用：

1. **`type=` 与 `A=` 可能是陈旧的**；`U=`、`rootTaskId=`、`visible=`、`sz=` 永远新鲜。
2. **没有 `rootTaskId=` 字段 = 自己就是根任务**（`rootTask != this` 才打印该字段）。
3. `sz=` 是**直接子节点数**：根任务下挂子任务时 sz=子任务数；叶任务 sz=活动数。
4. 对象哈希（`Task{b0651be` 的 b0651be）是 `System.identityHashCode` 十六进制——**跨打印锁定同一个 Task 对象**的钥匙，与 taskId 独立（taskId 会复用给新对象，哈希不会）。

### 7.3 一条自相矛盾的日志：wm_task_created [3,-1] 与 rootTaskId=1

事故日志里 Task 3 有两组「矛盾」证据：

- 122064 行（10.761）：`wm_task_created: [3,-1]`——事件说 Task 3 的根任务 id 是 **-1**（无根）。
- 141787 行（19.055）：`Task{73dd4b3 #3 type=undefined A=1000:com.yadea.launcher U=0 rootTaskId=1 ... sz=1}`——打印说它的根是 **Task 1**。

矛盾吗？不。看雅迪树的事件写入点——**在构造函数里**（yadi 树 Task.java:683）：

```java
EventLogTags.writeWmTaskCreated(mTaskId, isRootTask() ? INVALID_TASK_ID : getRootTaskId());
```

构造时刻任务**还没 attach 到父容器**（Builder.build 里 attach 发生在构造之后，yadi 树 Task.java:6670-6678），`isRootTask()` 为 true（没有父就是根）→ 第二字段恒为 **-1**。所以雅迪树上**每一个** `wm_task_created` 都是 `[N,-1]`，它只可靠地告诉你「任务 N 出生了」，**不携带父子关系**；父子关系要看活后缀的 `rootTaskId=`。对照 1.5 节：Task 1 出生时 `[1,-1]`（102865 行）是真根，语义碰巧一致；Task 3 的 `[3,-1]` 则是构造时序的产物【源码事实·雅迪树，与日志互证】。

### 7.4 type=undefined 的活化石：Task 3 的陈旧前缀

再看 141787 行本身。此刻是 19.055，B（HOME 记录）已在 Task 3 里住了 8 秒多——按 2.7 节，`onDescendantActivityAdded` 在 B 入栈瞬间（10.784）就把 Task 3 的类型设成了 HOME。**为什么打印还是 `type=undefined`？**

时序还原【源码事实推演】：

1. 10.761 Task 3 构造，type=UNDEFINED，affinity 未设。
2. Builder.setIntent / setIntent 装配意图：affinity=`1000:com.yadea.launcher`，同时 `stringName = null`（作废，yadi 树 Task.java:1020）。
3. attach 到 Task 1 之下、moveToFront 等操作触发**第一次 toString**：缓存前缀定格为 `Task{73dd4b3 #3 type=undefined A=1000:com.yadea.launcher`——此刻 B 还没 addChild，类型还是 UNDEFINED。
4. 10.784 B addChild → `onDescendantActivityAdded` → `setActivityType(HOME)`——**不作废 stringName**（7.1 节第三条）。
5. 此后每一次打印：陈旧前缀（type=undefined）+ 活后缀（rootTaskId=1、sz=1）。141787、151003、177539、197060 行的四份「undefined 化石」全部如此。

对照组：Task 2 在 122164 行（10.796）打印 `Task{b0651be #2 type=standard A=1000:com.yadea.launcher U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}`——**type=standard 是新鲜的**，因为 Task 2 的首次 toString 发生在 A 入栈（06.361，类型补成 standard）之后。同一份日志里，一个任务打印真类型、另一个打印出生类型，差别只在**首次被打印的时刻**【源码事实 + 日志事实互证】。

> **Q：怎么从日志判断一行 `type=` 是新是旧？**
> A：单行判不了，要交叉验证：(a) 找该任务更早的打印，若 type= 从未变过且任务里明显住过活动，多半是化石；(b) 用事件日志锚定入栈时刻（wm_create_activity 的 TaskId 字段）与首次 Task 打印时刻的先后；(c) 终极手段 `dumpsys activity activities`——dump 路径不走 toString 缓存，类型字段实时。事故分析里我们对 Task 3 用的就是 (a)+(b)。

### 7.5 ActivityRecord 打印与 ident 对照

`ActivityRecord{7c61204 u0 com.yadea.launcher/.function.main.view.MainActivity t3}` 形态的打印：花括号首段是**对象哈希十六进制**，`u0` 用户，组件，`t3` 所在任务 id。与事件日志的 Token 字段（十进制 `System.identityHashCode`）换算：

| 记录 | 服务端打印（hex） | 事件日志 Token（dec） | 换算 |
| --- | --- | --- | --- |
| A（Monkey 启动的实例） | e19b93c | 236566844 | 0xE19B93C = 236566844 |
| B（SystemUI 启动的实例） | 7c61204 | 130421252 | 0x7C61204 = 130421252 |

客户端 `wm_on_create_called` 等事件的 Token 字段同值（6.5 节 mIdent 链）。**这张换算表是第 8 章所有「谁是谁」判定的基础**：日志里任何一行带 236566844 的事件都属于 A，带 130421252 的都属于 B，无一例外【日志事实】。

### 7.6 事件日志字段速读

以事故里最关键的两行为例（字段定义见附录 A）：

```text
wm_new_intent: [0,130421252,3,com.yadea.launcher/.function.main.view.MainActivity,MAIN,NULL,NULL,270532608]
```

依次是：User=0、Token=130421252（**B**）、TaskId=3、组件、Action=MAIN、MIME=NULL、URI=NULL、Flags=270532608。270532608 十进制 = **0x10200000** = NEW_TASK|RESET_TASK_IF_NEEDED——**Monkey 的意图 flag 出现在了 B 的交付事件里**：这一行同时证明「请求来自 Monkey 形状（flag 是 Monkey 的）」与「收件人是 B（Token/TaskId 是 B 的）」，是 4.7 演算三的直接物证。

```text
wm_create_activity: [0,130421252,3,com.yadea.launcher/.function.main.view.MainActivity,MAIN,NULL,NULL,268435456]
```

268435456 = **0x10000000** = 仅 NEW_TASK——SystemUI `goLauncher` 的 flag 形状（Kotlin 源码 `addFlags(FLAG_ACTIVITY_NEW_TASK)`，1.7 节）。**同一组件的两条创建事件，flag 字段直接暴露了两个不同发起方的意图形状**，无需依赖 START 行。

---

## 8. 事故全推演：雅迪车机 Launcher 双实例

材料齐了，现在把 2026-11-03 凌晨那份 logcat（/home/liang/Downloads/log/logcat_2026_11_03_05_43_01_557-103.txt）从头到尾走一遍。所有行号、时间戳、字段值均直接摘自该文件【日志事实】；每一步的机制解释回指前文章节【源码事实/推演】；无法从材料确认的单独标注【未核实】。

### 8.0 事故画像

**现象**：车机运行中 Launcher 出现两个 MainActivity 实例并存（任务 2 与任务 3 各一个，同一进程 13306），桌面行为异常（Monkey 的点击意图被送进了 SystemUI 拉起的实例）。

**一句话根因**：同一 activityType 敏感匹配器（第 4 章）对「HOME 形状请求」与「LAUNCHER 形状请求」的可见性不对称——**HOME 请求看不见 STANDARD 任务，UNDEFINED 请求看得见一切**。SystemUI 的 HOME 意图（B）与 Monkey 的 LAUNCHER 意图（A）指向同一个 singleTask 活动，却在匹配器眼里属于两个世界。

### 8.1 时间线总表

| 时刻 | 行号 | 事件 | 章节 |
| --- | --- | --- | --- |
| 05:43:48.881 | 83001 | 旧 server（pid 1186）时代 Task 2 打印（type=undefined, rootTaskId=1） | 7.2 |
| 05:43:53.323 | 92984 | init 向 zygote（pid 418）发 signal 9 | 8.2 |
| 05:43:53.636 | 93149/93150 | zygote 收到/转发信号 | 8.2 |
| 05:43:53.659 | 92019 | SurfaceFlinger（pid 671）SIGABRT | 8.2 |
| 05:44:03.755 | 102865 | 新 server（pid 12877）：wm_task_created [1,-1]，HOME 根任务 Task 1 出生 | 1.5 |
| 05:44:06.310 | 106587 | START A：MAIN+LAUNCHER，flg=0x10200000，uid 0 pid 13116（Monkey） | 8.4 |
| 05:44:06.331 | 106655 | wm_task_created [2,-1]：STANDARD 根任务 Task 2 出生 | 3.6 |
| 05:44:06.361 | 106753/4 | wm_create_task [0,2]；wm_create_activity A（Token 236566844，flg 270532608） | 3.8 |
| 05:44:06.789 | 107845 | wm_restart_activity A（冷启动，进程拉起中） | 6.4 |
| 05:44:08.675-08.712 | 114573-114654 | A 客户端 create/start/resume/topResumedGained（pid 13306 出生） | 6.5 |
| 05:44:09.305 | 116823 | Monkey 第 2 次 START（同 A 意图） | 8.5 |
| 05:44:09.324 | 116896 | wm_new_intent → A（Token 236566844，Task 2） | 4.7 |
| 05:44:09.369-09.378 | 117070-117137 | A 客户端 top-lost/paused/resumed（onNewIntent 周期） | 6.6 |
| 05:44:10.746 | 122035 | DigitalKeyManager：handleWakeUpSignal wakeType=0x2 | 1.7 |
| 05:44:10.747 | 122037 | goLauncher:::# 第 1 次 | 1.7 |
| 05:44:10.750 | 122044 | START B：MAIN+HOME，flg=0x10000000，uid 1000 pid 13232 | 8.6 |
| 05:44:10.761 | 122064 | wm_task_created [3,-1]：Task 3 出生于 Task 1 之下 | 3.6/7.3 |
| 05:44:10.784 | 122131/2 | wm_create_task [0,3]；wm_create_activity B（Token 130421252，flg 268435456） | 3.8 |
| 05:44:10.796 | 122164 | startPausing 打印：Task 2 type=standard（活字段，无 rootTaskId → 根） | 7.4 |
| 05:44:10.801 | 122182/3 | wakeType 第 2 次 + goLauncher 第 2 次 | 8.6 |
| 05:44:10.817 | 122238 | START（HOME 形状第 2 发） | 8.6 |
| 05:44:10.823 | 122251 | wm_new_intent → B | 4.3 |
| 05:44:10.833 | 122298/9 | PlatformNotifierImpl goLauncher 第 3 次 | 8.6 |
| 05:44:10.834 | 122304 | START（第 3 发） | 8.6 |
| 05:44:10.837 | 122309 | wm_new_intent → B | 4.3 |
| 05:44:10.968 | 123081 | Window freeze timeout expired（转场窗口冻结超时） | 8.7 |
| 05:44:11.193 | 123748 | Monkey 第 3 次 START（uid 0 pid 13116） | 8.7 |
| 05:44:11.204 | 123770 | **wm_new_intent [0,130421252,3,...,270532608] → B**（Monkey 的 flag，B 的 Token） | 4.7 演算三 |
| 05:44:11.283 | 124614 | top resumed state loss timeout（A，e19b93c t2） | 6.7 |
| 05:44:11.298 | 124681 | Activity pause timeout（A） | 6.7 |
| 05:44:11.299 | 124687 | resumeTopActivity: Restarting B（7c61204 t3） | 6.2 |
| 05:44:11.395 | 124951 | wm_restart_activity B（热启动，进程 13306 已活着） | 6.4 |
| 05:44:11.415 | 124968 | logAppTooSlow 转储内雅迪实机栈帧（Task.resumeTopActivityInnerLocked:5047 等） | 第 6 章开头跨树口径 |
| 05:44:14.105-14.110 | 128467/482 | A 客户端 top-lost/paused（迟到 3.3s 的回执） | 6.7 |
| 05:44:14.167-14.185 | 128585-128655 | B 客户端 create/start/resume/top-gained（pid 13306） | 6.5 |
| 05:44:14.188 | 128668 | A 客户端 wm_on_stop_called（pid 13306）——**双实例并存实锤** | 8.8 |
| 05:44:19.055-19.292 | 141702-142309 | SystemUI MainActivity 第 1 次：Task 4，70ms 后自 finish | 8.9 |
| 05:44:22.136-22.4 | 150880-151283 | SystemUI MainActivity 第 2 次：Task 5，同样自 finish | 8.9 |
| 05:44:23.469 / 26.853 | 152840/163245 | 后续 Monkey START → 均 wm_new_intent → B | 4.7 |

### 8.2 前史：zygote 之死与 system_server 重启（53.323–03.755）

05:43:53.323，init 向 zygote（pid 418）发送 signal 9（92984 行）；53.636 zygote 侧记录收到并转发（93149/93150 行）；53.659，SurfaceFlinger（pid 671）SIGABRT（92019 行）。system_server 是 zygote 的子进程，zygote 死亡即拖死 system_server，init 随即重启二者——新 server pid 12877。

**因果边界必须钉死**【未核实】：

- init 为什么杀 zygote（看门狗？主动重启策略？上游崩溃连锁？）——日志片段内无直接答案。
- zygote 被杀（53.323）**先于** SF 崩溃（53.659）336ms；交接材料中另有一次 SF buffer 失败与 zygote SIGKILL 仅差 18ms 的记录。**时序上不能断言「SF 崩溃导致 zygote 死亡」**，反向（zygote 死导致 SF 失去缓冲供应而 abort）同样说得通。本文只把「双双重启」当作既定前提，不裁决第一因。
- 旧 server 时代的 Task 2（83001 行，`Task{af7f4b9 #2 type=undefined ... rootTaskId=1 sz=1}`）说明：**正常启动的拓扑里，Launcher 任务本来就是 HOME 根任务 Task 1 的子任务**（rootTaskId=1）——这正是 systemReady 走 AOSP HOME 链（1.1 节）的产物：HOME 意图 → HOME 类型 → 子任务挂 Task 1。事故后 A 所在的 Task 2 是**平级 STANDARD 根任务**，拓扑形态已经变了。旧打印里 type=undefined 同样是 7.4 节的化石现象（首次打印早于类型补写），不代表正常态任务真无类型。

### 8.3 新 server 起步：Task 1 出生，但没有系统 HOME 启动（03.755）

102865 行（05:44:03.755，线程 12877）：`wm_task_created: [1,-1]`——WMS 初始化 `RWC.setWindowManager` 建出 HOME 根任务 Task 1（1.5 节）。**此后直到 10.750，日志里没有任何 HOME 启动**：没有 systemReady 的 `START u0 {act=MAIN cat=[HOME] ...}`，没有 wm_create_activity。

原因在雅迪树的 systemReady（1.1 节已引，yadi 树 ActivityManagerService.java:8335-8343）：AOSP 的 `mAtmInternal.startHomeOnAllDisplays` 调用被**注释禁用**，注释原话「Don't call onUserStarting here. UserController will call onUserStarting when start user0」——HOME 启动被改道 UserController 的用户启动链。**而本次只有 system_server 重启、没有用户切换/重启，UserController 链路不触发**【推演，与日志中 HOME 启动缺位互证】。于是出现一个 6 秒的窗口（03.755–10.750）：**Task 1 空着，桌面上没有任何 HOME 活动**，第一个 HOME 请求要等外部发起方。这个窗口是事故的温床：若 AOSP 链路在场，B 会在 Monkey 之前就以 HOME 类型入住 Task 1，后续 Monkey 的 UNDEFINED 请求会理想匹配到它（演算三逻辑），A 根本不会以 STANDARD 根任务形态出生。

### 8.4 Monkey 启动 A：一条 STANDARD 根任务的诞生（06.310–08.712）

106587 行：

```text
START u0 {act=MAIN cat=[LAUNCHER] flg=0x10200000 cmp=com.yadea.launcher/.function.main.view.MainActivity} from uid 0, pid 13116
```

发起方 uid 0（root）pid 13116——Monkey【日志事实；Monkey 进程身份为推演，依据 uid 0 + LAUNCHER 类别 + 重复启动模式】。意图形状 MAIN+LAUNCHER：`isHomeIntent` 第二关不过（2.5 节）→ A 的记录类型 **UNDEFINED**（核心结论一）。

裁决链（4.7 演算一）：findTask 无匹配（Task 1 空）→ `newTask=true` → `resolveActivityType` UNDEFINED→STANDARD 兜底（3.6.1）→ `alwaysCreateRootTask(fullscreen, STANDARD)`=true（3.6.2）→ **新建 STANDARD 根 Task 2** → `canReuseAsLeafTask` 通过 → `reuseAsLeafTask` 根转正叶（3.6.3）→ A addChild → `onDescendantActivityAdded`：A 补成 STANDARD，Task 2 类型=standard（2.7 节）。

日志锚点逐一落位：106655 `wm_task_created [2,-1]`（构造即事件，7.3 节）；106753 `wm_create_task [0,2]`（用户 0 任务 2，3.8 节）；106754 `wm_create_activity [0,236566844,2,...,270532608]`（A 的 Token 首次亮相）；107845 `wm_restart_activity`（06.789，冷启动——Launcher 进程此前不存在，startProcessAsync 拉进程，6.3 节）；114573–114654（08.675–08.712）客户端四连：`wm_on_create_called [236566844,...]`、on_start、on_resume、top_resumed_gained，pid 13306——**进程 13306 出生，A 上车**。从 START 到 onCreate 约 2.37s，其中进程拉起占大头【日志事实】。

### 8.5 Monkey 第 2 次：规矩的复用（09.305–09.378）

116823 行同形状 START；116896 行 `wm_new_intent [0,236566844,2,...]`——findTask 理想解命中 Task 2 的 A（UNDEFINED×STANDARD 兼容，cls 全等，4.6 节流水线）→ `complyActivityFlags` singleTask 清栈分支（A 之上无活动，空转）→ `deliverNewIntent`（3.7 节）。客户端 117070–117137：top-lost → paused → resumed，onNewIntent 周期。**这一轮一切正常**——同形状请求的世界没有类型裂缝。

### 8.6 唤醒信号与 B 的诞生：裂缝张开（10.746–10.837）

122035 行 `handleWakeUpSignal: wakeType=0x2`（DigitalKeyManager.kt:241，WAKE_UP_SHOW_LAUNCHER=0x02 → hideKeyguardIfShowing → goLauncher()，1.7 节）；122037 `goLauncher:::#`；122038 ContextImpl 警告附带调用栈（ActivityStarter.goLauncher:91 ← DigitalKeyManager.goLauncher:541 ← handleWakeUpSignal:271）——**发起方身份三连证**【日志事实】。

122044 行：

```text
START u0 {act=MAIN cat=[HOME] flg=0x10000000 cmp=com.yadea.launcher/.function.main.view.MainActivity} from uid 1000, pid 13232
```

HOME 形状、uid 1000（SystemUI）、隐式意图（cmp= 是解析回写，2.2 节）。裁决链（4.7 演算二）：B 的记录类型 **HOME**（2.5 节三关全过）→ findTask：Task 2 任务级类型闸 **HOME×STANDARD=false** 直接跳过，Task 1 空任务记录级闸拒 → **null** → newTask → `resolveActivityType`=HOME → TDA 命中 `mRootHomeTask`=Task 1 → `alwaysCreateRootTask(fullscreen, HOME)`=false → Task 1 下**新建子 Task 3**（122064/122131/122132 行）→ B addChild，B 与 Task 3 均 HOME。

122164 行 startPausing 打印 Task 2 `type=standard`（新鲜缓存，7.4 节对照组）——A 被暂停，B 上位中。

接着 91ms 内两发重复 HOME 请求：122182/122238/122251（wake 第 2 次 → goLauncher → wm_new_intent **→ B**）与 122298/122304/122309（PlatformNotifierImpl 第 3 次 → wm_new_intent **→ B**）。此时 findTask 的 HOME 请求**能**看见 Task 3（HOME×HOME 兼容、cls 全等、理想解）——**重复 HOME 请求规矩地复用了 B**。发起方三处（DigitalKeyManager 两次、PlatformNotifier 一次）各自为政地「确保桌面在前台」，91ms 三发【日志事实；三方为何并发触发为未核实】。裂缝不在这里——这里一切符合设计；裂缝在下一节。

### 8.7 Monkey 第 3 次：意图送错门 + A 卡死（11.193–11.415）

123748 行 Monkey 第 3 次 START（uid 0 pid 13116，LAUNCHER 形状，flag 0x10200000）。此刻任务树：Task 3（HOME，B，随 Task 1 在顶）压着 Task 2（STANDARD，A，PAUSING 中）。

裁决链（4.7 演算三，全文最关键的一次匹配）：新记录类型 **UNDEFINED** → findTask 自顶向下：Task 3 任务级闸 UNDEFINED×HOME ✓ → 记录级闸（B 非 finishing、同用户、非 singleInstance、HOME×UNDEFINED ✓）→ cls 全等 → **理想解 = B，短路，Task 2 根本没被扫到** → `complyActivityFlags` singleTask 清栈分支 → `deliverNewIntent(B)`。

123770 行物证：`wm_new_intent [0,130421252,3,...,270532608]`——**Token/TaskId 是 B 的，Flags 是 Monkey 的**（7.6 节解码）。Monkey 以为自己在点 Launcher 图标唤醒桌面，实际把带 RESET_TASK_IF_NEEDED 的意图送进了 SystemUI 拉起的 HOME 实例。**这就是「双实例」从拓扑问题升级成行为问题的转折点。**

同一时刻，A 的进程 13306 主线程已经卡死（123081 行 10.968 `Window freeze timeout expired` 是先兆——转场冻结窗口等不到重绘【日志事实；卡死的具体原因未核实，logAppTooSlow 转储在 124968 行附近，含进程线程状态可供深挖】）：

- 11.283（124614）：`Activity top resumed state loss timeout for ActivityRecord{e19b93c ... t2}`——A 连 top-resumed 释放都不回执（6.7 节第一道保险，从 ~10.78 起算 500ms）。
- 11.298（124681）：`Activity pause timeout for ActivityRecord{e19b93c ... t2}`——第二道保险触发，`activityPaused(true)` 代签。
- 11.299（124687）：`resumeTopActivity: Restarting ActivityRecord{7c61204 ...} t3`——pause 闸放行，B 进入发射程序（6.2 节）。
- 11.395（124951）：`wm_restart_activity [0,130421252,3,...]`——热路径（13306 活着，6.3 节），LaunchActivityItem+ResumeActivityItem 事务发往 13306。
- 11.415（124968）：logAppTooSlow 转储打出雅迪实机栈帧 `Task.resumeTopActivityInnerLocked:5047 → TaskFragment.resumeTopActivity:1483 → startSpecificActivity:1043 → realStartActivityLocked:976 → minimalResumeActivityLocked:4795`——**实机构建的方法布局与学习树不同、控制流语义一致**（第 6 章开头跨树口径的实物证据）。

事务发出去了，但 13306 的主线程还在卡着——B 的 LaunchActivityItem 排在积压队列里等。

### 8.8 客户端清账：双实例并存实锤（14.105–14.188）

主线程解卡后 83ms 内按序清偿（全部 pid 13306【日志事实】）：

- 14.105/14.110（128467/128482）：A 客户端 `wm_on_top_resumed_lost_called`、`wm_on_paused_called`——迟到 3.3 秒的回执，服务端早已代签（6.7 节「状态撕裂窗口」闭合）。
- 14.167–14.185（128585/128592/128643/128655）：B 客户端 `wm_on_create_called [130421252,...]` → on_start → on_resume → top_resumed_gained——**第二个 MainActivity 实例在同一个进程里走完 onCreate**（6.5 节：Application 复用，`makeApplicationInner` 不再新建）。
- 14.188（128668）：A `wm_on_stop_called`。

**A 走到 onStop 而不是 onDestroy**——A 实例完整存活在 Task 2 里，B 在 Task 3 前台。两个 Activity 对象、同一进程、同一 Application、同组件、同 launchMode=singleTask。此后每次 Monkey 启动（152840、163245 行等）都按演算三送 wm_new_intent 给 B（z 序 Task 3 在上），A 永远收不到任何意图，成为僵尸前台候选。

**交接文档旧结论的两处修正在此确认**：(1) 「两个客户端 Activity 是否真的并存过」——是，128467–128668 的同 pid 事件序列是直接证据；(2) 「掉帧是否根因」——不是，帧问题发生在 B 记录创建（10.784）之后，是主线程卡死的伴生症状，双实例拓扑在 10.784 已定案。

### 8.9 插曲：Tasks 4/5——另一种「双任务」，机制完全不同

19.055（141702）与 22.136（150880）两次 `START u0 {... cmp=com.android.systemui/.MainActivity} from uid 0, pid 14072`，各建 Task 4、Task 5（141715 `[4,-1]`、150898 `[5,-1]`），看似「SystemUI 也双实例」。真相在后续 70ms：

- 141764/5：`wm_create_activity [0,63378520,4,...]`；141840 restart；142011（19.173）`wm_finish_activity ... app-request`——**应用自己请求 finish**；142071 客户端 create；142177 `wm_destroy_activity ... finish-imm:idle`；142309 客户端 destroy。Task 5 同型（151115/151162/151264/151283）。

SystemUI 的 MainActivity 是 standard（应用源码 SystemUI AndroidManifest.xml:218-222，无 launchMode 声明【源码事实·应用树】）的**空白自杀活动**：create 后约 70ms 自行 finish（用途按行为推测是触发某个前台流程的跳板，具体业务逻辑未核实）。第二次启动时 findTask 的记录级闸 `r.finishing` 拒掉收尾中的候选（4.3 节）→ 又开新任务。**这是「finishing 任务不可复用」的正常行为，与主事故的类型不对称无关**——把它并进根因会污染结论，交接文档特意单列，本文维持。

### 8.10 根因链与反事实推演

**五环因果链**（每环独立成立，缺一环事故形态即变）：

1. **温床**：雅迪禁用 systemReady HOME 链 + 仅 server 重启不触发 UserController 链 → 6 秒 HOME 真空期（8.3）。
2. **抢跑**：真空期内 Monkey 以 LAUNCHER 形状启动桌面 → A 以 STANDARD 根任务形态出生，偏离正常拓扑（正常态是 Task 1 的 HOME 子任务，8.2 对照）（8.4）。
3. **裂缝**：SystemUI 以 HOME 形状启动同一活动 → findTask 类型闸 HOME×STANDARD=false → 看不见 A → B 在 Task 1 下另立门户（8.6）。
4. **错位**：Monkey 再启动（UNDEFINED 形状）→ 泛匹配 + z 序优先 → 理想解命中 B → 意图送错门（8.7）。
5. **固化**：A 进程主线程卡死 → 三道超时代签 → B 上位、A 带伤存活（onStop 不销毁）→ 双实例长期并存（8.7/8.8）。

**反事实推演**（每问只改一个变量，其余同事故现场；结论均为源码推演）：

- **若 Monkey 也用 HOME 意图**（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）：A 出生即 HOME 类型、入住 Task 1 子任务；B 请求 HOME×HOME 兼容 → 理想解命中 A → wm_new_intent 给 A。**无双实例。**
- **若 SystemUI goLauncher 改用 LAUNCHER 类别**（与 Monkey 同形状）：B 的记录类型 UNDEFINED → findTask 看得见 Task 2（UNDEFINED×STANDARD ✓，cls 全等）→ 理想解 A → wm_new_intent 给 A。**无双实例**（但 HOME 语义丢失，不推荐，见 8.11）。
- **若 systemReady HOME 链未被注释**：开机即有 HOME 实例入住 Task 1；server 重启后是否重建取决于重启路径（本例重启原因未核实），即便重建也在 Monkey 之前 → Monkey 的 UNDEFINED 请求理想匹配 HOME 任务 → 单实例。**温床消失。**
- **若 MainActivity 是 standard 而非 singleTask**：第 3 环不变（类型闸先于一切 launchMode 逻辑，4.5/5.6 节）——**照样双实例**，且第 4 环更糟：standard 无清栈分支，重复启动会无限压新实例。**换 launchMode 治不了这个病。**
- **若 A 进程不卡死**：双实例拓扑依旧（第 3 环已定案），但 A 能正常 pause/stop，B 正常上位；A 仍收不到后续意图（演算三的 z 序逻辑不变）。卡死只影响第 5 环的烈度，不影响事故成立。

### 8.11 修复选项与验证清单

按侵入度从小到大：

1. **应用侧自防御（推荐首选）**：MainActivity.onCreate 加单实例守卫——`if (!isTaskRoot()) { finish(); return; }` 的变体不够（B 是 Task 3 的根），应改为**检测自身 activityType/任务类型或既有实例**：例如 onCreate 里通过 ActivityManager 查同组件任务数，发现更老的同类任务则 finish 自己并移交意图。优点：不动框架、不依赖发起方自律；缺点：治标，第 4 环的意图错位仍会短暂发生。
2. **发起方统一意图形状（治本方向之一）**：约定所有「回桌面」入口（SystemUI goLauncher、PlatformNotifier、语音、方控）统一用 HOME 形状（现状已如此），并**约束测试与三方工具**：Monkey 的 LAUNCHER 形状是本次的抢跑者——量产环境无 Monkey，但任何以 LAUNCHER 类别启动桌面的调试脚本/三方 App 都会复现第 2 环。可在 Launcher 内对「非 HOME 形状且任务类型非 HOME」的启动请求做归一（收到后主动 finish 并转发 HOME 意图）。
3. **框架侧恢复系统 HOME 链（治本方向之二）**：评估恢复 systemReady/UserController 在 server 重启场景的 HOME 启动（雅迪注释掉它必有原因——多用户时序，需与 UserController 团队核对【未核实】），消除 HOME 真空期。改动面大，须回归多用户/多屏用例。
4. **不推荐**：改 MainActivity 的 launchMode（8.10 反事实四已证无效）；给 goLauncher 加 component（componentSpecified 在单次启动路径恒 false，2.6 节，加了也不改变类型认定）。

**验证清单**（修复后按序打点）：

- 复现路径：`kill $(pidof zygote)`（或等价的 server 重启手段）→ 静待 6s → 以 LAUNCHER 形状启动桌面 → 以 HOME 形状启动桌面。
- 观测点一：`wm_create_task` 事件数——修复后第二轮 HOME 请求**不应**产生新任务 id。
- 观测点二：`wm_new_intent` 的 Token 字段——HOME 请求应命中第一个实例的 Token（对照 wm_create_activity 的 Token）。
- 观测点三：`dumpsys activity activities`——同组件任务数恒为 1，且类型为 home（dump 不走 toString 缓存，7.4 节）。
- 观测点四：客户端 `wm_on_create_called` 同 ident 只出现一次。

### 8.12 事实分级总账

**【日志事实】**（行号见 8.1 总表）：zygote/SF/system_server 重启时序；Task 1/2/3/4/5 的创建事件与打印；A/B 的 Token 恒定与十进制-十六进制互证；三发 goLauncher 的调用栈；Monkey 第 3 次意图落在 B（123770）；两条 500ms 超时与 Restarting/wm_restart_activity 时序；客户端 14.105–14.188 的同 pid 事件序列（并存实锤）；Tasks 4/5 的 app-request 自 finish；雅迪实机栈帧（124968）。

**【源码事实】**（学习树行号为准，雅迪树/应用树单独注明）：HOME 类型认定三关与 isHomeIntent 形状判据；componentSpecified 在单次启动路径恒 false；STANDARD 延迟定型（onDescendantActivityAdded 注释原文）；isCompatibleActivityType 矩阵与 findTask 双级类型闸；alwaysCreateRootTask/reuseAsLeafTask 的根叶结构；三道超时与代签机制；ident 跨进程链；两棵树 toString 缓存策略差异；雅迪 systemReady HOME 链注释禁用；应用 manifest 的 launchMode 与 intent-filter 声明。

**【推演】**（源码事实代入日志时序的必然结论）：演算一/二/三的全部分叉走向；Task 3 type=undefined 化石的成因时序；HOME 真空期的成因（注释 + 重启路径不触发 UserController）；五环因果链。

**【未核实】**：init 杀 zygote 的第一因；zygote 之死与 SF 崩溃的因果方向；**雅迪源码树（HEAD 8302534c）与事故构建 476 的对应关系**——本文所有「雅迪树」引用以当前检出为准，若构建 476 后有过 wm 相关提交，个别行号可能漂移（机制层结论经日志实物交叉验证，风险集中在行号而非逻辑）；13306 主线程卡死 3.3s 的具体原因（logAppTooSlow 转储可深挖）；三处发起方 91ms 内并发的业务动机；SystemUI 空白 MainActivity 的业务用途。

---

## 附录 A：EventLog 标签速查

字段定义摘自 frameworks/base/services/core/java/com/android/server/wm/EventLogTags.logtags 与 frameworks/base/core/java/android/app/EventLogTags.logtags（学习树）。Token 字段一律是 `System.identityHashCode(ActivityRecord)` 十进制（客户端事件为对值的 Activity.mIdent）。

**服务端（wm 包）**：

| 标签 | id | 字段 |
| --- | --- | --- |
| wm_finish_activity | 30001 | User, Token, Task ID, Component Name, Reason |
| wm_task_to_front | 30002 | User, Task |
| wm_new_intent | 30003 | User, Token, Task ID, Component Name, Action, MIME Type, URI, Flags |
| wm_create_task | 30004 | User, Task ID |
| wm_create_activity | 30005 | User, Token, Task ID, Component Name, Action, MIME Type, URI, Flags |
| wm_restart_activity | 30006 | User, Token, Task ID, Component Name |
| wm_resume_activity | 30007 | User, Token, Task ID, Component Name |
| wm_pause_activity | 30013 | User, Token, Component Name, User Leaving, Reason |
| wm_destroy_activity | 30018 | User, Token, Task ID, Component Name, Reason |
| wm_add_to_stopping | 30066 | User, Token, Component Name, Reason |
| wm_task_created | 31001 | TaskId, RootTaskId（雅迪树恒为 -1，7.3 节） |

**客户端（android.app 包）**：

| 标签 | id | 字段 |
| --- | --- | --- |
| wm_on_paused_called | 30021 | Token, Component Name, Reason |
| wm_on_resume_called | 30022 | Token, Component Name, Reason |
| wm_on_stop_called | 30049 | Token, Component Name, Reason |
| wm_on_create_called | 30057 | Token, Component Name, Reason |
| wm_on_restart_called | 30058 | Token, Component Name, Reason |
| wm_on_start_called | 30059 | Token, Component Name, Reason |
| wm_on_destroy_called | 30060 | Token, Component Name, Reason |
| wm_on_top_resumed_gained_called | 30064 | Token, Component Name, Reason |
| wm_on_top_resumed_lost_called | 30065 | Token, Component Name, Reason |

常见 Reason 值：`app-request`（应用主动 finish，8.9 节）、`finish-imm:idle`（立即销毁，idle 通道）、`clear-task-top`/`clear-task-stack`（清栈，5.1 节）、`resume-exception`（resume 抛异常，6.2 节）、`2nd-crash`（二次启动失败，6.4 节）。

## 附录 B：常量与词汇表

**activityType**（[WindowConfiguration.java#L130](../../../../AAOS13_study/frameworks/base/core/java/android/app/WindowConfiguration.java#L130)）：UNDEFINED=0、STANDARD=1、HOME=2、RECENTS=3、ASSISTANT=4、DREAM=5。

**launchMode**（[ActivityInfo.java#L76](../../../../AAOS13_study/frameworks/base/core/java/android/content/pm/ActivityInfo.java#L76)）：LAUNCH_MULTIPLE=0（standard）、LAUNCH_SINGLE_TOP=1、LAUNCH_SINGLE_TASK=2、LAUNCH_SINGLE_INSTANCE=3、LAUNCH_SINGLE_INSTANCE_PER_TASK=4。

**本文涉及的 Intent flag**：NEW_TASK=0x10000000、SINGLE_TOP=0x20000000、CLEAR_TOP=0x04000000、RESET_TASK_IF_NEEDED=0x00200000、MULTIPLE_TASK=0x08000000、NEW_DOCUMENT=0x00080000、LAUNCH_ADJACENT=0x00001000、TASK_ON_HOME=0x00004000、BROUGHT_TO_FRONT=0x00400000。事故中的 0x10200000 = NEW_TASK|RESET_TASK_IF_NEEDED（Monkey/图标点击的标准组合）；0x10000000 = 仅 NEW_TASK（goLauncher）。

**超时常量**：PAUSE_TIMEOUT=500ms（ActivityRecord.java:429）、TOP_RESUMED_STATE_LOSS_TIMEOUT=500ms（ActivityTaskSupervisor.java:187）、LAUNCH_TIMEOUT=10s×HW_TIMEOUT_MULTIPLIER（ActivityTaskSupervisor.java:184）、LAUNCH_TICK=500ms（ActivityRecord.java:432）。

**词汇表**：

| 术语 | 定义 | 出处 |
| --- | --- | --- |
| 根任务 / 叶任务 | 根任务是 TaskDisplayArea 的直接子 Task；叶任务是没有子 Task 的 Task。STANDARD 全屏任务在 A13 常态是「根叶一体」 | 3.6 节 |
| TDA（TaskDisplayArea） | 显示区内承载任务的容器，每屏缺省一个；HOME 根任务缓存在其 mRootHomeTask | 1.2/1.5 节 |
| 理想解 / 弱候选 | findTask 的两级匹配结果：组件全等即理想解（短路返回）；仅 rootAffinity 相同为弱候选（继续找更好） | 4.3 节 |
| 类型闸 | findTask 任务级与记录级的 isCompatibleActivityType 检查；HOME×STANDARD 互斥、UNDEFINED 泛兼容 | 4.2/4.5 节 |
| 延迟定型 | STANDARD 类型不在 ActivityRecord 构造时设置，拖到入栈（onDescendantActivityAdded）才补写 | 2.7 节 |
| 根转正叶 | alwaysCreateRootTask 新建的根任务经 canReuseAsLeafTask/reuseAsLeafTask 直接充当叶任务 | 3.6.3 节 |
| ident | System.identityHashCode(ActivityRecord)，随 LaunchActivityItem 过江成为客户端 Activity.mIdent，服务端十六进制打印与事件日志十进制同值 | 6.4/6.5 节 |
| 代签 | 客户端超时不回执时，服务端以 activityPaused(true)/handleTopResumedStateReleased(true) 强行推进状态机 | 6.7 节 |
| 化石打印 | Task.toString 缓存前缀中的过期字段（典型：type=undefined），活后缀字段永远新鲜 | 7.2/7.4 节 |
| HOME 真空期 | server 重启后 Task 1 已建而 HOME 活动未启动的窗口期（雅迪树 systemReady 链被禁用所致） | 8.3 节 |

（全文完）
