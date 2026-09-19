# SIR-2865 · AppList 界面点 DOCK home 键未返回 home 界面
- **提交**：`91c091fc` | 2026-07-16 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车控车设界面显示时进入 APPList 界面，再点击 DOCK 栏 home 按键，未进入 home 界面。

## 根因分析
缺陷库根因为"未添加跳转逻辑"。代码层面：`AppListExitRequestRouter` 通过弱引用回调 `RequestExitTarget.requestExitToLauncher()` 通知 AppList 退出，但 `AppListActivity` 收到退出请求后只执行 `playExitAnimationThenNavigate()` 播放退场动画并 `finish()`，原本的 `navigateToLauncher()` 调用被注释掉（diff 中可见 `// navigateToLauncher()`）。因此 AppList 只是关闭了自己，没有任何跳转回 `MainActivity` 的动作，表现就是"点了 home 键没反应"。此外自动关闭定时器、Fragment 内 `goToLauncher()` 等多条退出路径共用同一无参签名，无法区分"仅关闭"与"关闭并回 home"两种语义。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt`、`AppListFragment.kt`、`application/Launcher/src/main/java/com/yadea/launcher/services/AppListExitRequestRouter.kt`

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
-    override fun requestExitToLauncher() {
+    override fun requestExitToLauncher(isNeedGoHome: Boolean) {
         if (isExitAnimating || isFinishing || isDestroyed) {
             return
         }
-        playExitAnimationThenNavigate()
+        playExitAnimationThenNavigate(isNeedGoHome)
     }
@@ onAnimationEnd
-//                    navigateToLauncher()
+                    if (isNeedGoHome) {
+                        navigateToLauncher()
+                    }
                     finish()
```

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/services/AppListExitRequestRouter.kt
     fun interface RequestExitTarget {
-        fun requestExitToLauncher()
+        fun requestExitToLauncher(isNeedGoHome: Boolean)
     }
     fun requestExitIfAvailable(): Boolean {
         val target = boundTarget.get() ?: return false
-        target.requestExitToLauncher()
+        target.requestExitToLauncher(true)
         return true
     }
```

## 为什么能修复
DOCK 栏 home 按键走 `AppListExitRequestRouter.requestExitIfAvailable()` 路径，现在传入 `isNeedGoHome=true`，退场动画结束后会显式 `startActivity` 拉起 `com.yadea.launcher.function.main.view.MainActivity`；而自动关闭定时器和 Fragment 内部退出传 `false`，保持"仅关闭"语义。用布尔参数区分两类退出意图，最小改动恢复了缺失的跳转逻辑。隐患：`navigateToLauncher()` 中组件名硬编码（setClassName 字符串），若 MainActivity 包名重构会静默抛异常，虽然 catch 后重置了 `isExitAnimating` 防止动画状态卡死。

## 复盘与经验
- 被注释掉的跳转调用（`// navigateToLauncher()`）是典型的"功能被阉割后忘记补回路"——删除代码时留下的缺口要有单测或回归用例兜底。
- 同一个"退出"动作在不同入口有不同语义（关闭自身 vs 回 home）时，接口签名应尽早携带意图参数，而不是事后用布尔 flag 补丁扩散到 3 个文件。
- 路由类（Router）通过弱引用持有 Activity 目标，跨层回调链路排查时要沿着接口定义把所有调用方都列出来再改签名，否则会漏改编译报错。
