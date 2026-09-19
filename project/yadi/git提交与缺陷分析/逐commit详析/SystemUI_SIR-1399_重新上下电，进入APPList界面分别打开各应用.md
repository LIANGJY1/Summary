# SIR-1399 · 重新上下电后经 AppList 打开应用出现上个应用画面残留

- **提交**：`66dad9b4` | 2026-08-25 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
重新上下电后进入 AppList（更多应用）界面，再打开各应用时，会出现上一个应用的画面残留（残影/闪现旧应用）。

## 根因分析
SystemUI 的 `ActivityStarter.goMoreApp()` 负责从 SystemUI 拉起 Launcher 的 AppList（`SysUIConfig.LAUNCHER_PACKAGE_NAME` + `LAUNCHER_APP_CLASS_NAME`），此前只加了 `FLAG_ACTIVITY_NEW_TASK`。缺陷库说明 AppList 页面加了透明动效——即 AppList 是以透明/半透明窗口叠加方式呈现的，能透出其下层（Z 轴下方）的任务画面。重新上下电后任务栈状态未清理，之前打开的应用仍在前台任务序列里；进入 AppList 时它只是叠在最上面，"上一个应用"并没有真正退后台。于是透过 AppList 的透明动效或打开新应用的转场瞬间，旧应用 surface 依然可见，表现为画面残留。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt`

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt
@@ -199,7 +199,8 @@ object ActivityStarter {
                 SysUIConfig.LAUNCHER_PACKAGE_NAME,
                 SysUIConfig.LAUNCHER_APP_CLASS_NAME
             )
-            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
+                    Intent.FLAG_ACTIVITY_TASK_ON_HOME)
             context.startActivity(intent, options.toBundle())
         } catch (e: Exception) {
```

## 为什么能修复
`FLAG_ACTIVITY_TASK_ON_HOME` 会把新启动的 AppList 任务放置在 Home 任务之上，相当于"进 AppList 时把桌面垫在它下面"，原先位于前台的其他应用任务被整体压到 Home 之后（退后台）。这样 AppList 的透明动效期间及新应用启动转场时，透出的下层是桌面而不是旧应用画面，残留消失。副作用基本可控：该 flag 还意味着从 AppList 任务返回键会直接回桌面（而非回到上一个应用），符合车机交互预期；对正常从桌面进入 AppList 的路径无感知差异。

## 复盘与经验
- 透明/带透明动效的窗口"看得见"它 Z 轴下方的所有任务，前台管理必须显式整理任务栈，不能假设"我盖在上面旧的就不算前台"。
- `FLAG_ACTIVITY_TASK_ON_HOME` 是车机/桌面类系统里把某任务"锚定在 Home 上"的标准手段，配合 `NEW_TASK` 使用可同时解决残影与返回键归属两个问题。
- 车机"上下电"是特殊的生命周期扰动源：重新上电后任务栈与应用可见性与普通退出不同，涉及前台窗口的 bug 要专门设计上下电+开应用的组合用例（本单从 1399 老编号看是被压了很久的顽疾）。
