# SIR-5750 · 打开应用后进入 AppList，再点 dock 栏 AppList 键应回到应用界面
- **提交**：`943299fc` | 2026-08-11 | liujinfeng | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
打开某应用后进入 AppList，再点击 dock 栏 AppList 按键，预期退回之前的应用界面，实际却回到了 Home 页。

## 根因分析
`ExitWithAnimatorReceiver` 收到退出动画广播后调用 `AppListExitRequestRouter.shared.requestExitIfAvailable()`。查看 `AppListExitRequestRouter.kt`（提交时版本）可知其签名是 `requestExitIfAvailable(isNeedGoHome: Boolean = true)`，默认参数为 true——即默认退出 AppList 后要回到 Home。`ExitWithAnimatorReceiver` 走了默认值，导致"退出 AppList"被升级成"退出并回 Home"，先前打开的应用界面被跳过。属交互变更（再次点击只退出 AppList）后调用点未同步参数。另 `Myapplication.kt` 中多屏占位 View 的 `alpha = 0f` 被注释放开，配合占位窗口（背景 `0x00000000`）在切回应用时保持显示内容。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/services/ExitWithAnimatorReceiver.kt`、`Myapplication.kt`
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/services/ExitWithAnimatorReceiver.kt
-        AppListExitRequestRouter.shared.requestExitIfAvailable()
+        AppListExitRequestRouter.shared.requestExitIfAvailable(false)
```
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt
                 val placeholderView = View(this).apply {
-                    alpha = 0f
+//                    alpha = 0f
                     setBackgroundColor(0x00000000)
                 }
                 ...
                 ).apply {
                     gravity = Gravity.FILL
-                    alpha = 0f
+//                    alpha = 0f
                 }
```

## 为什么能修复
显式传 `false` 后 `requestExitToLauncher(isNeedGoHome=false)` 只销毁/退出 AppListActivity 而不触发回 Home，底层栈中先前应用自然重新可见，匹配"再次点击回到应用"的交互。`Myapplication.kt` 放开占位 View alpha 保证返回应用时占位透明窗口正常渲染、画面不黑屏。隐患：其他仍走默认 `true` 的调用点语义不变，但今后新增调用若继续依赖默认值容易重蹈覆辙。

## 复盘与经验
- Kotlin 默认参数是隐形行为契约：`requestExitIfAvailable()` 不带参读起来像"退出"，实际是"退出并回 Home"。跨模块交互语义建议显式传参或用具名常量，避免默认值埋雷。
- "退出面板"与"退出到哪"是两个正交决策，路由 API 设计上应拆分，或至少用具名参数 `isNeedGoHome` 的调用点注释标明交互依据。
- 交互变更类 bug（本单与 SIR-5764 同日同因）说明：改交互时要用全局搜索枚举/默认参数值的方式排查所有触发路径。
