# SIR-6496 · 通知中心已无通知，状态栏却仍显示应用通知图标

- **提交**：`024b712e` | 2026-08-27 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交信息标注 D，以缺陷库为准）

## 问题
应用的横幅（HUN）通知被移除后，通知中心里已经没有任何该应用通知，但状态栏/通知图标区仍显示该应用的通知图标。

## 根因分析
状态栏图标数据由 `CarNotificationListener` 组装：先拷贝真实活跃通知 `mActiveNotifications` 得到 `notificationsForStatusBar`，再把 `mHeadsUpManager.getActiveHeadsUpNotifications()` 的横幅条目无条件 `put` 进去覆盖。而横幅移除是两段式的：`CarHeadsUpNotificationManager.maybeRemoveHeadsUp` 先把 `HeadsUpEntry.mShouldRemove` 置 true，若展示时长未达 `mMinDisplayDuration` 还要 `postDelayed` 等到最短展示时间才执行 `dismissHun` 并从 `mActiveHeadsUpNotifications` 移除。在这个"已标记删除、尚未真正移除"的窗口期内，应用通知已从 `mActiveNotifications` 消失，但旧的 HUN 条目仍被合并进 `notificationsForStatusBar`，相当于把已删除的通知"复活"给了图标计算，导致图标残留。提交信息概括为"通知数据集合数据错误 → 需要移除的通知需要过滤掉"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/CarHeadsUpNotificationManager.java、application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java
```diff
--- application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java
         Map<String, AlertEntry> notificationsForStatusBar = new HashMap<>(mActiveNotifications);
         if (mHeadsUpManager != null) {
-            mHeadsUpManager.getActiveHeadsUpNotifications().values().forEach(
-                    headsUpEntry -> notificationsForStatusBar.put(headsUpEntry.getKey(),
-                            headsUpEntry));
+            mHeadsUpManager.getActiveHeadsUpNotifications().values().stream()
+                    .filter(headsUpEntry -> !headsUpEntry.mShouldRemove)
+                    .forEach(headsUpEntry -> notificationsForStatusBar.put(
+                            headsUpEntry.getKey(), headsUpEntry));
         }

--- application/SystemUI/src/main/java/com/android/systemui/notification/CarHeadsUpNotificationManager.java
         boolean shouldShowAnimation = !isUpdate(alertEntry);
         HeadsUpEntry currentNotification = addNewHeadsUpEntry(alertEntry);
+        currentNotification.mShouldRemove = false;
```

## 为什么能修复
第一处改动让处于"待删除"状态的 HUN 条目不再参与状态栏图标数据集，图标计算只依据真正存活的通知，残留图标消失。第二处改动处理重发场景：同 key 通知在移除动画/延时期间被应用重新 post 时，`addNewHeadsUpEntry` 复用旧条目并重置 `mShouldRemove=false`，保证新横幅不会被误过滤。副作用是过滤只影响图标数据集，不影响横幅自身展示与移除流程，改动面收敛。

## 复盘与经验
- "合并两个数据源构造视图数据"时，任一数据源里处于中间态（已标记删除/延迟移除）的元素都会污染结果，合并前必须按状态过滤。
- 异步延迟移除（postDelayed + 动画回调）会让集合长期存在"逻辑已删、物理还在"的僵尸条目，需要显式的状态标记（如 `mShouldRemove`）供下游识别。
- 对可复用条目做状态重置与对脏数据做过滤同样重要：只加过滤不加重置，会把"复活"的通知误杀。
