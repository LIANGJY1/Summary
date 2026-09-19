# SIR-5764 · 通知中心打开时再次点击状态栏通知 icon 应保持展开
- **提交**：`9c332937` | 2026-08-11 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
通知中心已打开时，再次点击状态栏通知中心 icon 会把通知中心收起，不符合"再次点击退出/保持"的交互预期（需求要求点击不收起）。

## 根因分析
`PanelPageRouter.getNotificationIconAction()` 在当前页已是 `PAGE_NOTIFICATION` 时返回枚举 `NotificationIconAction.HIDE_PANEL`，即路由层把"重复点击"硬编码为主动收起动作。`StatusBarActor` 收到该枚举后调用 `hideQuickSettingView()`，于是再次点击必然执行收起逻辑。这是路由策略与交互需求不一致：交互变更后路由枚举没有同步更新。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/PanelPageRouter.java`、`statusbar/actor/StatusBarActor.kt`
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/panel/PanelPageRouter.java
     public enum NotificationIconAction {
         OPEN_NOTIFICATION,
         SWITCH_TO_NOTIFICATION,
-        HIDE_PANEL
+        NONE
     }
     ...
         return normalizePage(currentPage) == PAGE_NOTIFICATION
-                ? NotificationIconAction.HIDE_PANEL
+                ? NotificationIconAction.NONE
                 : NotificationIconAction.SWITCH_TO_NOTIFICATION;
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/statusbar/actor/StatusBarActor.kt
-            PanelPageRouter.NotificationIconAction.HIDE_PANEL -> {
-                hideQuickSettingView()
+            PanelPageRouter.NotificationIconAction.NONE -> {
+                LogUtils.d(TAG, "onNotificationIconClick: notification center is visible !")
             }
```

## 为什么能修复
把"通知中心可见时的重复点击"从"执行收起"改为"什么都不做"（NONE 分支仅打日志），路由层不再产生收起指令，`hideQuickSettingView()` 不再被触发。改动是纯枚举语义替换，调用点一一对应，无其他引用风险；代价是失去了"点击 icon 收起"的能力，若后续需要恢复 toggle 交互需重新引入。

## 复盘与经验
- 枚举动作路由（Action 枚举 + when/switch 分发）让交互策略集中、可审计，但也意味着交互需求变更时必须同步改路由策略，否则 UI 行为与产品预期脱节。
- 用 `NONE`/no-op 枚举替代删除分支，能保留所有调用点的完整性编译检查（exhaustive when），比直接删 case 更安全。
- "再次点击收起"是手机端习惯，车机端交互需求可能相反，移植交互时要逐条确认。
