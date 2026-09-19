# SIR-7384 · 连接 WiFi 后断开连接二次确认弹窗与 UI 设计不符
- **提交**：`e3f64c5c` | 2026-09-04 | sgh | Setting | bugfix（UI 变更适配）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
对已连接的 WiFi 网络操作时的二次确认弹窗，样式与按钮语义与新版 UI 设计不符。

## 根因分析
`WlanDialogFragment.handleSavedAccessPoint()` 旧实现用 `TextDialog` 弹出，且按钮语义混乱：确认按钮文案是"断开连接"（`disconnect` → 执行 `disconnectWifi()`），取消按钮文案竟是"移除"（`remove` → 执行 `forgetWifi()`）——把"移除网络"藏在了取消键上，与警告弹窗规范（标题"断开连接？"+ 说明文案 + 确认/取消）完全不符。产品 UI 变更后要求改为标准警告弹窗。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt`、`application/Setting/src/main/res/values/strings.xml`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt
     private fun handleSavedAccessPoint(point: AccessPoint) {
         if (mWxWifiManagerI.isWifiConnected && point.isConnected) {
-            TextDialog(
-                point.ssid,
-                getString(R.string.wlan_remove_hint),
-                getString(R.string.disconnect),
-                getString(R.string.remove)
-            ).setCallback(object : Callback {
-                override fun confirm(content: Any?) {
-                    mWxWifiManagerI.disconnectWifi()
-                }
-
-                override fun cancel() {
-                    mWxWifiManagerI.forgetWifi()
-                }
-            }).show(childFragmentManager, "HintDialog")
+            showWarningDialog(
+                title = getString(R.string.hotspot_block_title),
+                content = getString(R.string.wifi_disable_des),
+                confirmText = getString(R.string.wifi_disconnect),
+                cancelText = getString(R.string.cancel),
+                onConfirm = {
+                    lifecycleScope.launch(Dispatchers.IO) {
+                        mWxWifiManagerI.disconnectWifi()
+                    }
+                })
```

```diff
--- application/Setting/src/main/res/values/strings.xml
+    <string name="wifi_disable_des">断开连接后，无法连接无线网络</string>
+    <string name="wifi_disconnect">断开</string>
```

## 为什么能修复
改用统一 `showWarningDialog`：标题复用热点断开的"断开连接？"，新增文案"断开连接后，无法连接无线网络"，确认键"断开"执行 `disconnectWifi()`（移入 IO 线程，原实现直接在 UI 回调里同步调用，也算顺带修正）。副作用：取消按钮不再隐含"移除网络"（`forgetWifi()` 调用被删），若产品仍需"移除网络"入口，需要另行确认——从本单"按新 UI 修改"的结论看属于有意为之的行为变更。

## 复盘与经验
- "取消按钮执行第三个隐秘动作"（取消=移除网络）是典型的反模式：用户对"取消"的心理预期是无操作，把破坏性动作挂在取消键上极易误触；新弹窗删掉该设计值得肯定。
- 同类二次确认弹窗（热点断开 `a4d5f26b` / WiFi 断开本单）应共用标题与警告样式，只替换内容文案，保证全局一致。
- 弹窗确认回调里的耗时操作（断网 IO）应切到后台线程，避免卡 UI 线程。
