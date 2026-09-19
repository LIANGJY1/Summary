# SIR-6210 · 降水提醒点击后无法跳转天气界面直接消失

- **提交**：`35c43c01` | 2026-08-23 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 天气 墨迹天气

## 问题
通知中心（负一屏）点击降水提醒通知后，天气界面没有出现，通知反而直接收走，看起来像"点了没反应"。

## 根因分析
通知点击最终走 `NotificationClickHandlerFactory` 里的 `intent.sendAndReturnResult(...)` 发起跳转。当天气应用已经处于前台时（缺陷库根因"天气已经在前台显示"），这次 PendingIntent 发送对窗口栈没有任何可见效果——目标 Activity 已在栈顶，系统不会重建/置前任何界面；而负一屏（快捷窗口）又只会被"新界面置前"这类事件收起，于是出现了"通知消失（通知被消费置已读）、负一屏仍盖在天气界面之上"的假象。修复思路（提交信息 how："主动收起负一屏"）是不再依赖系统隐式收起，点击通知时显式调用 `ActorController.getInstance().hideShortcutWindow()` 收起负一屏。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/NotificationClickHandlerFactory.java、application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（共 +5 行）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/notification/NotificationClickHandlerFactory.java
@@ 通知点击处理
             int result = ActivityManager.START_ABORTED;
             try {
+                // 通知点击之后主动收起负一屏
+                ActorController.Companion.getInstance().hideShortcutWindow();
                 result = intent.sendAndReturnResult(/* context= */ null, /* code= */ 0,
                         /* intent= */ null, /* onFinished= */ null,
                         /* handler= */ null, /* requiredPermissions= */ null,
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
@@ 媒体点击跳转处
+        // 收起负一屏
+        ActorController.Companion.getInstance().hideShortcutWindow();
         if (musicPending != null) {
             try {
                 musicPending.send();
```

## 为什么能修复
点击通知的第一动作改为先收起负一屏，再做 intent 发送：即使目标应用已在前台、intent 派发无可见效果，用户也能看到负一屏让出屏幕、露出前台的天气界面，交互闭环恢复。`hideShortcutWindow()` 为进程内同步调用，时机在 send 之前，无竞态。顺带在 `NavBarFragment` 的音乐跳转路径补了同样的收起逻辑，属于同类隐患的统一处理。隐患：对所有通知点击都收起负一屏，若未来存在"点击通知需停留在通知中心"的交互（如展开式操作），需要再做条件过滤。

## 复盘与经验
- "跳转无效"不一定是 intent 没送达：目标已在前台时 `sendAndReturnResult` 依旧成功但无可见效果，要结合宿主容器（负一屏/分屏）状态分析。
- 通知点击不应依赖系统隐式行为收起宿主面板，自管理的覆盖层要自己负责收起时机。
- 同类点击路径（通知中心、dock/导航栏媒体卡片）修一处后应横向排查其他入口，本提交即顺手修复了 `NavBarFragment`。
