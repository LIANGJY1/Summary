# SIR-6812 · 车控车设与控制中心各弹出一个 WiFi 弹窗
- **提交**：`6ed48b0e` | 2026-09-01 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
先在车控车设（设置内 ConnectFragment）唤出连接 WiFi 弹窗，再从控制中心入口唤出同一 WiFi 弹窗，屏幕上出现两个 WiFi 弹窗叠加。

## 根因分析
设置应用内有两个互不感知的弹窗宿主：`ConnectFragment`（车控车设页面内，用 `childFragmentManager` 弹出 `WlanDialogFragment`/`HotspotDialogFragment`/`BluetoothDialogFragment`）和 `ConnectChildDialogActivity`（控制中心入口，用自身 `FragmentManager` 弹同名 Fragment）。`ConnectChildDialogActivity.showWifiDialog()` 只检查自己内部的 `mWlanDialogFragment == null || !isVisible`，无法看到 ConnectFragment 里的实例，因此两个入口各弹一份。两个入口各自的防重逻辑只覆盖自身，跨宿主状态无共享。

## 关键代码修改
改动文件：`application/Setting/.../ui/activity/ConnectChildDialogActivity.kt`、`.../ui/fragment/ConnectFragment.kt`
```diff
--- .../ui/activity/ConnectChildDialogActivity.kt
     private fun showWifiDialog() {
+        // 车控WiFi弹窗正在显示时，控制中心入口不再弹出，避免两个WiFi弹窗
+        if (ConnectFragment.isWifiDialogShowing) {
+            finish()
+            return
+        }
         if (mWlanDialogFragment == null || !mWlanDialogFragment!!.isVisible) {
             mWlanDialogFragment = WlanDialogFragment()
             ...
--- .../ui/fragment/ConnectFragment.kt
+    companion object {
+        @JvmStatic
+        var isWifiDialogShowing = false
+        @JvmStatic
+        var isBluetoothDialogShowing = false
+        @JvmStatic
+        var isHotspotDialogShowing = false
+    }
@@ 三处弹窗点击（以 WiFi 为例）
                 mWlanDialogFragment = WlanDialogFragment()
+                mWlanDialogFragment?.setOnDismissListener(object : DismissListener {
+                    override fun onDismiss() { isWifiDialogShowing = false }
+                })
+                isWifiDialogShowing = true
                 mWlanDialogFragment?.show(childFragmentManager, "WlanDialogFragment")
@@ onDestroyView()
+        isWifiDialogShowing = false
+        isBluetoothDialogShowing = false
+        isHotspotDialogShowing = false
```
（热点、蓝牙弹窗同样处理；`ConnectChildDialogActivity` 的 showHotspotDialog/showBluetoothDialog 增加对称判断）

## 为什么能修复
用 `ConnectFragment` 的静态伴生字段作为跨宿主的全局弹窗状态：车控侧弹窗弹出置 true、DismissListener 与 `onDestroyView` 复位；控制中心入口在弹出前检查该标志，若车控弹窗在显示则直接 `finish()` 不再弹。三类弹窗（WiFi/热点/蓝牙）全部覆盖。隐患：静态布尔是进程内弱约定，若后续再加第三入口或弹窗在崩溃后标志未复位，可能出现"点不动"的假死；`onDestroyView` 复位可兜底大部分异常路径。

## 复盘与经验
- 同一功能存在多个入口宿主（页面内弹窗 + 独立 DialogActivity）时，防重逻辑必须建立在跨宿主共享状态上，仅查自身 isVisible 不够。
- 全局弹窗标志要同时挂"弹出置位 / onDismiss 复位 / 宿主销毁复位"三个钩子，缺一个都会造成状态漂移。
- 控制中心这类系统级入口调用设置弹窗前，应把"是否已有同语义弹窗"当作前置校验，而不是各自为政。
