# SIR-7905 · 关闭 AppList 后未回到原应用而是回到桌面（FLAG_ACTIVITY_TASK_ON_HOME）

- **提交**：`c1428f00` | 2026-09-10 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交头标影响等级 D，与缺陷库 B 不一致，以缺陷库为准）

## 问题
打开任意应用后，再从 dock 打开 AppList，点击退出 AppList 时没有回到之前的前台应用，而是回到了桌面。

## 根因分析
SystemUI 通过 `util/ActivityStarter.goMoreApp(context, view)` 启动 Launcher 的应用列表（`SysUIConfig.LAUNCHER_PACKAGE_NAME`/`LAUNCHER_APP_CLASS_NAME`）。原 intent 同时加了 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_TASK_ON_HOME`：`FLAG_ACTIVITY_TASK_ON_HOME` 会把新任务标记为"位于 Home 之上"，任务结束时返回的是 Home 而不是启动它的任务栈；同时该语义还伴随把 Home 之前的任务移出前台的效果（提交 [why]：applist 打开的时候会退出前台应用）。于是前台应用被退到后台，AppList 一退出，系统自然落回桌面——与用户预期"返回上一个应用"不符。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt（另有 NavBarFragment.java 仅加一条成功日志，+2/-2）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt
@@ goMoreApp()
             intent.setClassName(
                 SysUIConfig.LAUNCHER_PACKAGE_NAME,
                 SysUIConfig.LAUNCHER_APP_CLASS_NAME
             )
-            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
-                    Intent.FLAG_ACTIVITY_TASK_ON_HOME)
+            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
             context.startActivity(intent, options.toBundle())
```

## 为什么能修复
去掉 `FLAG_ACTIVITY_TASK_ON_HOME` 后，AppList 任务按普通 NEW_TASK 方式压在当前应用任务之上，关闭 AppList 时栈顶回退到原前台应用，"退出回应用界面"恢复。副作用：Applist 不再"锚定"在 Home 上，从桌面外的任何界面拉起 AppList 后返回，都会回到拉起它的界面（这正是需求想要的）；但若产品存在"希望关闭 AppList 永远回桌面"的旧场景，需要重新确认。同提交给 `NavBarFragment.handleMusicCardClick` 补了一条成功日志，属顺带调试增强。

## 复盘与经验
- `FLAG_ACTIVITY_TASK_ON_HOME` 的语义是"任务结束时回 Home"，与"悬浮在 Home 上但保留原任务栈"的直觉相反；用系统 flag 前必须验证 back/recents 行为。
- 车机多任务"返回上一层"类 bug，先画任务栈意图图（谁压谁、退出后落到哪），再对照 intent flag 与 launchMode，比盲改启动参数高效。
- 工具类（ActivityStarter）里集中封装启动逻辑是好事：一处 flag 修改即可修复所有经过该入口的调用方。
