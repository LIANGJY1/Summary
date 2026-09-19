# SIR-7233 · 哨兵模式地图"家"未设置时点空白关弹窗后仍显示选中

- **提交**：`63638ee6` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
哨兵模式下地图页"家"位置未设置时点击"家"，弹出"去设置"提示弹窗；点击弹窗外部空白让弹窗消失后，"家"仍保持选中/高亮状态，与实际（未设置）不符。

## 根因分析
`SafetyMonitorViewModel` 中未设置家/公司时的提示弹窗 `addressDialog` 设置了 `isEnableClickMask = true`（允许点遮罩关闭），但状态复位只挂在 `Callback.confirm`（"去设置"跳转 `naviProtocol.gotoHomeOrCompanyPage`）与取消按钮路径上——点遮罩（mask）关闭走的 `onDismiss` 回调没有任何监听，选中态"家"没有被复位（[why]："dialog关闭没有刷新ui"）。即弹窗有三条退出路径，只有两条接了清理逻辑。

## 关键代码修改
改动文件：SafetyMonitorViewModel.kt（仅 +6 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SafetyMonitorViewModel.kt
                         addressDialog.isEnableClickMask = true
+                        addressDialog.setOnDismissListener(object : DismissListener {
+                            override fun onDismiss() {
+                                onCancel()
+                            }
+                        })
                         addressDialog.setCallback(object : Callback {
                             override fun confirm(content: Any?) {
                                 SdkManager.getInstance().naviProtocol.gotoHomeOrCompanyPage(...)
```

## 为什么能修复
给 `addressDialog` 补挂 `DismissListener`，任何方式关闭（含点空白遮罩）都触发既有的 `onCancel()` 复位逻辑（缺陷库 sol："弹窗关闭重新获取数据刷新"），"家"的选中态随真实状态恢复为未选中。改动复用现有回调，无新增状态；隐患：`onDismiss` 在 confirm 跳转路径同样会触发，`onCancel()` 与 confirm 后续逻辑是否互斥需确认（若 confirm 后弹窗 dismiss 再跑一次 onCancel，可能把"去设置"后的正常选中态也复位——需测试覆盖"点去设置"路径）。

## 复盘与经验
- 弹窗有几种退出方式（确认/取消按钮/返回键/点遮罩），清理逻辑应挂在最通用的 `onDismiss` 上，而不是分散在按钮回调里——漏一条路径就出一个状态残留 bug。
- "选中态与实际状态不符"的通用修法是关闭弹窗后以数据源为准重刷（re-fetch），而非记住"该把哪个控件置回去"。
- 与 `225c9e76`（热点关闭路径漏隐藏）、`740db676`（回弹取消漏一半视图）同构：多路径/多成员场景下的状态一致性是 Setting 模块 bug 的头号模式，review 时先数路径数。
