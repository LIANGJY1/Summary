# SIR-7016 · 热点关闭时"已连接设备"栏与空提示未隐藏

- **提交**：`225c9e76` | 2026-09-02 | dufan | Setting | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
热点开关处于关闭状态时，弹窗里仍显示"已连接设备"栏（连接数量）与"当前无连接设备"（hot_point_empty）提示，界面状态与实际不符。

## 根因分析
`HotspotDialogFragment` 的 `onApState` 回调与开关监听里，只处理了开启态的 UI（`WIFI_AP_STATE_ENABLED` 分支 `resetCancel()`），`WIFI_AP_STATE_DISABLED` 分支没有隐藏头布局 `item_hotspot_header` 中的设备计数 `tvCount`，各关闭路径（确认弹窗 `CloseHotHintDialog.confirm`、直接关分支）也没有联动隐藏；初始进入时若 `isAPOn=false` 同样未隐藏。控件"该藏的时候没人藏"（缺陷库根因"控件未隐藏"）。修复顺带删除了整个 `item_hotspot_footer.xml`（热点名/密码/频段的重复 footer 及其 `showChangeNameDialog`、`setupNightTabIndicator` 等约 190 行死代码），该 footer 早已 `visibility=GONE` 且无入口维护。

## 关键代码修改
改动文件：MainActivity.kt、HotspotDialogFragment.kt、item_hotspot_footer.xml（删除）、item_hotspot_header.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
             WIFI_AP_STATE_DISABLED -> {
+                mBindingHeader.tvCount.visibility = View.GONE
                 resetCancel(false)
             }
             WIFI_AP_STATE_ENABLED -> {
+                mBindingHeader.tvCount.visibility = View.VISIBLE
                 resetCancel()
             }
```
```diff
// 同文件，初始加载与各关闭路径
         if (mWxApManagerI.isAPOn) {
             scheduleAutoCloseIfIdle()
         } else {
+            mBindingHeader.tvCount.visibility = View.GONE
         }
             } else {
+                    mBindingHeader.tvCount.visibility = View.GONE   // 确认关闭与直接关闭两条路径同步隐藏
```

## 为什么能修复
在 AP 关闭的所有到达路径（初始加载、状态回调、确认弹窗关闭、直接关闭）统一把 `tvCount` 置 GONE，开启态与回调置 VISIBLE，显隐状态机闭合，"关闭仍显示"消失。风险点：显隐逻辑分散在 4 处 if/else，靠人肉保持一致，后续新增关闭路径容易漏；更稳妥是由 `onApState` 单点驱动。删除 footer 属正向清理，但 `HotspotAdapter.removeFooterView` 相关行为需回归确认。

## 复盘与经验
- 控件显隐缺陷的标准排查法：列出该控件所有"应隐藏"的业务路径，逐一确认有没有对应 `visibility=GONE`；漏一条路径就是一个 bug。
- 状态驱动型 UI（开关/连接状态）的显隐最好收敛到单一状态回调（onApState）中统一设置，而不是散落在交互监听里。
- 修 bug 时顺手删除已 GONE 的死布局与死代码（本次 -229 行）能显著降低后续维护噪音，但应在提交信息中显式说明范围。
