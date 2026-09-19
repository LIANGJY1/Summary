# SIR-6497 · 连接手车互联后进入 AppList 未默认显示手车互联栏

- **提交**：`ce43f808` | 2026-08-26 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
手机与车机建立手车互联连接后，进入 AppList（应用列表）界面，默认停留在"应用列表"页签，没有按设计要求默认切到"手车互联"栏。

## 根因分析
`AppListActivity` 用 `ViewPager` + `tabLayout` 承载两个页签：index 0 为 `AppListFragment`（应用列表）、index 1 为 `CarConnectFragment`（手车互联）。初始化时按 `DeviceConnectManager.getCurrentConnectType()` 分支处理，但原 `when` 里 `1 ->` 分支只打日志、什么也不做，`2, 3 ->` 分支只调用 `updateCarConnectStatus(...)` 刷新连接状态文本，所有分支都没有 `viewPager.setCurrentItem(...)` 的页签切换——也就是说"连接态决定默认页签"这一交互从未实现（缺陷库：未添加对应逻辑），ViewPager 默认落在 index 0。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt`

```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
@@ -111,11 +111,15 @@ class AppListActivity : BaseActivity<ActivityAppListBinding, AppViewModel>(),
         when (currentConnectType) {
             1 -> {
                 LogUtils.d(TAG, "currentConnectType: $currentConnectType")
+                viewPager?.setCurrentItem(1, false)
+            }
+            2, 3 -> {
+                updateCarConnectStatus(
+                    true,
+                    SettingsUtils.getGSetting(CONNECT_DEVICE_NAME)
+                )
+                viewPager?.setCurrentItem(1, false)
             }
-            2, 3 -> updateCarConnectStatus(
-                true,
-                SettingsUtils.getGSetting(CONNECT_DEVICE_NAME)
-            )
 
             else -> {
                 updateCarConnectStatus(
```

## 为什么能修复
修复在所有"有连接"的分支（type 1、2、3）统一补上 `viewPager?.setCurrentItem(1, false)`：进入 AppList 时若已有手车互联/互联类连接，页签直接定位到手车互联栏；`false` 表示不平滑滚动，进入瞬间即呈现目标页签，无中间动画。未连接（else）分支保持默认应用列表页签不变。逻辑风险低；注意 type 1 分支仍未调用 `updateCarConnectStatus(true, ...)`，若 type 1 也应在互联栏显示设备名，可能仍欠一步（以当前需求范围为准）。

## 复盘与经验
- "根据外部状态决定初始 UI"的分支，每个 case 都要落到底（状态刷新 + 页面定位），只写一半的 when 分支是最常见的功能遗漏形态——本单 `1 ->` 只剩日志就是典型。
- `ViewPager.setCurrentItem(idx, false)` 的第二参数决定是否带滚动动画，"进入即定位"场景应传 false，避免用户看到页签滑动过程。
- 连接类型枚举（1/2/3）分支逻辑分散在多处时容易顾此失彼，建议收敛为一个"连接类型 → UI 状态"的映射函数，新增互联类型时只改一处。
