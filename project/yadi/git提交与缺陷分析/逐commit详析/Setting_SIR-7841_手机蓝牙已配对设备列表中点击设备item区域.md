# SIR-7841 · 已配对蓝牙设备点击item无断开/取消二次确认弹窗
- **提交**：`0d0fee58` | 2026-09-08 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：代码屏蔽）

## 问题
手机蓝牙"已配对设备"列表中点击设备 item，没有弹出"断开/取消"二次确认弹窗（设备被直接操作或无反应）。

## 根因分析
`BluetoothFragment` 的列表 `setOnItemClickListener` 中，调用 `BluetoothUtil.showClickDeviceDialog(...)` 的代码整段被注释屏蔽，取而代之的是直接 `device?.apply { operationDevice(this, true) }`——大概率是调试期间绕过弹窗直连后忘记恢复。同时 `BluetoothUtil` 内 `TextDialog` 的弹窗文案参数也是旧的"移除设备"语义（`device_remove_hint`、`disconnect`/`remove` 按钮且 cancel 分支会执行 `disconnect()+unpair()` 解绑），与"断开连接"二次确认的 UI 需求不符。缺陷 = 入口被注释 + 弹窗内容语义错误，两处叠加导致点击 item 无正确弹窗。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt；application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt；application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/res/values-en/strings.xml；component/CommonTools/src/main/java/com/yadea/common/base/BaseFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -151,12 +151,9 @@
             val device: CachedBluetoothDevice? =
                 (adapter.getItem(position) as MultiBluetoothDevice).bluetoothDevice
-//            BluetoothUtil.showClickDeviceDialog(
-//                mPhonePairedDevices, device, lifecycleScope, childFragmentManager
-//            )
-            device?.apply {
-                operationDevice(this, true)
-            }
+            BluetoothUtil.showClickDeviceDialog(
+                mPhonePairedDevices, device, lifecycleScope, childFragmentManager
+            )
```
```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -481,10 +481,10 @@
                 if (it.isConnected) {
                     TextDialog(
-                        it.name,
-                        getString(R.string.device_remove_hint),
-                        getString(R.string.disconnect),
-                        getString(R.string.remove)
+                        getString(R.string.device_disconnect_title),
+                        getString(R.string.device_disconnect_hint) + it.name,
+                        getString(R.string.confirm),
+                        getString(R.string.cancel)
                     ).setCallback(...)
```
弹窗 cancel 分支中原来的 `operationPhoneCar(device, true); it.disconnect(); it.unpair()`（取消即断开并解绑）被注释掉，取消不再执行破坏性操作。另新增 strings 资源 `device_disconnect_title`（"断开连接?"）、`device_disconnect_hint`（"此操作将会断开您与以下设备的连接："）及英文翻译；`BaseFragment.TAG` 由 `javaClass.toString()` 改为 `simpleName`（顺带日志优化）。

## 为什么能修复
恢复被注释的 `showClickDeviceDialog` 调用，点击 item 重新走弹窗链路；弹窗文案改为"断开连接?"+设备名，按钮恢复为确认/取消，取消分支不再执行 disconnect+unpair，语义正确。隐患：cancel 分支代码仅注释未删除，需确认取消后确实无副作用；`operationDevice(true)` 直连路径被移除，若其他入口依赖该行为需另测。

## 复盘与经验
- "代码屏蔽"是本次缺陷库登记的根因——调试用注释/直连代码提交前必须清理或用 debug 开关管理，这是 B 级缺陷的常见来源。
- 弹窗类组件的 confirm/cancel 回调语义要成对检查：本例取消按钮原来竟执行"断开并解绑"，属于回调语义与按钮文案错位的高危写法。
- 弹窗文案新增中英文双语资源要同步（values 与 values-en），否则多语环境回退英文或缺失。
