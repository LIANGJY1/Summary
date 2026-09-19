# SIR-4596 · 屏幕显示泛灰（占位窗口 alpha 未清零）

- **提交**：`249b4cc3` | 2026-08-07 | liujinfeng | Launcher | bugfix（低概率偶现，验证性修复）
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 主交互（rc/sol 均记 "/"，以提交信息为准）

## 问题
屏幕偶发整体泛灰，像蒙了一层半透明灰纱（偶现-低于10%），影响整个主交互屏显示。

## 根因分析
`Myapplication.addTransparentPlaceholderWindow()` 会向 Display 2/Display 4 各添加一个全屏"透明占位窗口"（`TYPE_APPLICATION_OVERLAY` + `PixelFormat.TRANSLUCENT` + `Gravity.FILL`，不可聚焦不可触摸），注释说明用途是"用于图层刷新"。提交归因：怀疑是 display2 的这个占位 View 导致泛灰。机制上，占位窗口虽然 `setBackgroundColor(0x00000000)` 全透明，但 View 的 alpha 与 WindowManager.LayoutParams 的 alpha 默认是 1.0，TRANSLUCENT 像素格式 + SurfaceFlinger 合成路径下，透明窗口仍可能参与混合（尤其与车机多屏图层/仪表合成配合时），一旦合成权重异常就会给整屏叠上一层灰色半透明 veil。缺陷库 rc/sol 留空，说明根因当时未被完全证实，属于"先按最强嫌疑加固"的探索性修复。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt`
```diff
// application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt
                 val placeholderView = View(this).apply {
+                    alpha = 0f
                     setBackgroundColor(0x00000000)
                 }
                 val params = WindowManager.LayoutParams(
                     ...
                     PixelFormat.TRANSLUCENT
                 ).apply {
                     gravity = Gravity.FILL
+                    alpha = 0f
                 }
```
View alpha 与窗口 LayoutParams alpha 双双强制置 0，确保占位窗口在 View 层与 Window 合成层都不参与透明度混合。

## 为什么能修复
alpha=0 让该窗口在 SurfaceFlinger 合成时等效完全不可见（连合成权重都归零），从根上排除"占位窗口被半透明合成"导致整屏泛灰的可能。注意：本提交是压测验证性修复（`[测试范围]压测，看看还能不能复现`），且当前仓库 HEAD 上这两行 `alpha = 0f` 已被注释（后续提交又调整过该实验），说明问题定位经历了反复，最终结论以最新代码为准；阅读该文件历史时不能只看单一提交。

## 复盘与经验
- "占位窗口""保活窗口"等隐性全局 Window 是整屏级显示异常（泛灰、闪烁、变色）的头号嫌疑，排查时应先盘点应用添加的所有 TYPE_APPLICATION_OVERLAY 窗口。
- 透明窗口要彻底不可见，需同时保证背景色、View alpha、LayoutParams alpha 三者归零，仅设背景色 0x00000000 在某些合成路径下并不保险。
- 偶现(低于10%)显示 bug 的正确姿势：先按嫌疑加固 + 高强度压测复现验证，同时保留可回退的实验开关（本例后续又注释回退正说明需要这种迭代空间）。
