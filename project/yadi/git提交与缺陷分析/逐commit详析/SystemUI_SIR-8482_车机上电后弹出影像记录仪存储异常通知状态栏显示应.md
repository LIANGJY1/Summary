# SIR-8482 · 上电后影像记录仪存储异常通知（LX级）在状态栏显示应用通知图标

- **提交**：`7419db49` | 2026-09-16 | caohongliang | SystemUI | bugfix/UX变更
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车机上电后弹出"影像记录仪存储异常"通知（LX 级别），状态栏出现了应用通知图标，不符合 UX 定义（此单实为 UX 规则变更：LX 级通知要求显示红点）。

## 根因分析
`StatusBarNotificationIconStateResolver.resolve()` 按通知等级决定状态栏图标状态：原逻辑只把 `NotificationLevelType.L0` 判为 `RED_DOT`（红点），而 `LX` 被归入 `L1` 分支记为 `hasGrayDot`（灰点）。UX 变更后 LX 级通知（如行车记录仪存储异常这类需要强提醒的异常通知）应与 L0 一样显示红点。原映射规则滞后于 UX 定义，属规则表修正而非逻辑 bug（元数据 rc/sol 均为"/"）。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/statusbar/StatusBarNotificationIconStateResolver.java
```diff
--- application/SystemUI/src/main/java/com/android/systemui/statusbar/StatusBarNotificationIconStateResolver.java
         boolean hasGrayDot = false;
         for (NotificationLevelType level : levels) {
-            if (level == NotificationLevelType.L0) {
+            if (level == NotificationLevelType.L0 || level == NotificationLevelType.LX) {
                 return StatusBarNotificationIconState.RED_DOT;
             }
-            if (level == NotificationLevelType.L1 || level == NotificationLevelType.LX) {
+            if (level == NotificationLevelType.L1) {
                 hasGrayDot = true;
             }
         }
```

## 为什么能修复
LX 从灰点分支挪入红点分支后，存在 LX 级通知（如记录仪存储异常）时 `resolve` 直接返回 `RED_DOT`，状态栏按 UX 新定义显示红点类图标。该函数是纯映射、提前返回顺序不变（L0/LX 优先于灰点累积），其他等级行为不受影响。隐患：LX 语义从此与 L0 同级，若后续还有"LX 不该红点"的通知类型，需要再细分等级而不是共用 LX。

## 复盘与经验
- 通知等级 → 图标状态这类映射应集中在一处 resolver 并与 UX 规格表逐行对照，规则变更时只改表不改调用方。
- 本单是"UX 变更型 fixbug"：以 bug 单流程落地需求变更，复盘归档时应标注清楚，避免误统计为代码缺陷。
- 提前返回（红点短路）的枚举映射里新增成员时，注意插入位置决定了优先级语义。
