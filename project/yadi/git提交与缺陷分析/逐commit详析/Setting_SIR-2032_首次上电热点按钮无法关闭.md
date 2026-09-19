# SIR-2032 · 首次上电热点开关开启失败后按钮卡死无法关闭

- **提交**：`e355657e` | 2026-07-07 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
首次上电后打开热点开关，热点开启失败，但开关停在"打开"状态且无法关闭（按钮无响应）。

## 根因分析
`HotspotDialogFragment` 实现 `IWxApListener`，在 AP 状态回调里根据 `WIFI_AP_STATE_DISABLED/ENABLED` 调用 `switchHotspot.disableOverlay()` 解除交互锁。但 `when` 分支**漏掉了 `WIFI_AP_STATE_FAILED`**：首次上电底层热点开启失败时回调 FAILED，此前点击开关设置的 overlay 防抖锁（disableOverlay 的对立面，点击后先锁住等待状态回执）永远不会被释放，开关被"锁死"在开启态，后续点击全部被 overlay 拦截——即"无法关闭"。rc："开启失败未重置状态"。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt`（+7）、`values/strings.xml`、`values-en/strings.xml`（各 +1 文案）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt  AP 状态回调
         when (state) {
             WIFI_AP_STATE_DISABLED -> mBindingHeader.switchHotspot.disableOverlay()
             WIFI_AP_STATE_ENABLED -> mBindingHeader.switchHotspot.disableOverlay()
+            WIFI_AP_STATE_FAILED -> {
+                mBindingHeader.switchHotspot.isChecked = false
+                mBindingHeader.switchHotspot.disableOverlay()
+                ToastUtils.showMsgToast(requireContext(), getString(R.string.hot_point_open_failed))
+            }
         }
```
```diff
// --- application/Setting/src/main/res/values/strings.xml
+    <string name="hot_point_open_failed">热点开启失败，请稍后重试</string>
```

## 为什么能修复
FAILED 分支补齐后，失败路径完成两件事：`isChecked = false` 把开关视觉/状态回滚到关闭，`disableOverlay()` 释放交互锁使按钮恢复可点，同时 toast 告知失败原因。"请求→锁交互→等状态回执→解锁"的状态机重新闭合，任何回执结果都有出口。遗留风险：如果底层连 FAILED 回调都丢失（超时无回执），锁依然无法释放，缺少超时兜底。

## 复盘与经验
- **"请求-回执"模式必须穷举所有回执态**：DISABLED/ENABLED/FAILED 三态只处理两个，漏掉的恰恰是异常路径——`when` 密封类/常量全集应配合 lint 或 else 分支兜底。
- **异步操作期间的交互锁要有超时保险**：overlay/防抖锁依赖回调释放，回调丢失即永久卡死，建议加超时自动解锁。
- **失败不仅要解锁还要回滚状态**：只解锁不把 `isChecked` 拨回，开关显示与实际热点状态仍不一致，用户会再次困惑。
