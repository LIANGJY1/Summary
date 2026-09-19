# SIR-8634 · 负一屏左滑手势透传，连带退出底下应用
- **提交**：`bbe04a00` | 2026-09-17 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
打开任意应用后再打开通知中心/负一屏，触发左滑关闭手势时，负一屏和底下的应用一起退出。

## 根因分析
负一屏窗口由 `QuickSettingWindowManager` 创建，其 `WindowManager.LayoutParams.flags` 里同时带 `FLAG_NOT_TOUCH_MODAL`、`FLAG_NOT_FOCUSABLE`、`FLAG_LAYOUT_NO_LIMITS`。`FLAG_NOT_FOCUSABLE` 使该窗口永不持有窗口焦点，按键/返回类事件（左滑关闭手势在框架里映射为 back 事件）不会被负一屏消费，而是按焦点链透传给下层 Activity——底层应用把这次手势当作"退出我"处理，于是应用与负一屏同时关闭。缺陷库根因"负一屏不持有窗口焦点，back 事件透传给下面的 activity"与此完全吻合。

## 关键代码修改
改动文件：QuickSettingWindowManager.kt（单行删除）
```diff
// component/SystemUIService/src/main/java/com/android/ext/systemuiservice/dropdownbar/quicksetting/manager/QuickSettingWindowManager.kt
             flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
-                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                     or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                     or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
```

## 为什么能修复
去掉 `FLAG_NOT_FOCUSABLE` 后，负一屏弹出时即成为焦点窗口，左滑手势产生的 back 事件首先投递给负一屏，由其自身拦截并处理为"关闭负一屏"，不再穿透到底层 Activity；`FLAG_NOT_TOUCH_MODAL` 仍保留，保证负一屏以外的区域点击可以继续落到下层应用，触摸穿透行为不受影响。潜在副作用：负一屏持有焦点期间，底层应用的按键事件会被阻断，这恰是弹出式面板的预期语义；另需关注焦点窗口变化对输入法等系统行为的影响。

## 复盘与经验
- 悬浮面板（负一屏/通知中心/对话框型窗口）要明确回答"要不要焦点"：需要拦截 back/按键就必须可聚焦，只想要触摸穿透则用 `FLAG_NOT_TOUCH_MODAL` 而不是 `NOT_FOCUSABLE`。
- "手势退出连带关闭两层界面"类问题，优先检查窗口 flags 与事件分发路径（焦点窗口→按键注入→透传），一行 flag 常是根因。
- SystemUI 窗口调试可用 `adb shell dumpsys window` 查看各窗口 focus 状态，快速验证"谁持有焦点"。
