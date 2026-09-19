# SIR-7647 · 蓝牙设备移除确认弹窗式样与 UI 不一致
- **提交**：`c3a11792` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（注意：JSON 中 ids 复用了 SIR-7647，与热点单同号，实为蓝牙弹窗式样问题）

## 问题
蓝牙设备列表中，点击已配对设备的确认弹窗式样与 UI 稿不一致：按钮文案为"继续移除"而非"移除"，且点击设备条目时弹出的中间确认对话框流程也不符合新式样。

## 根因分析
`BluetoothFragment.setOnItemClickListener` 里点击设备走 `BluetoothUtil.showClickDeviceDialog(mPhonePairedDevices, device, ...)`，该流程会先弹一个设备操作对话框，再进入删除确认 `TextDialog`。删除确认弹窗的按钮文案用的是 `R.string.proceed_to_remove`（"继续移除"），按钮也未挂红色危险样式。UI 新式样要求：点击设备直接执行连接操作（不再有中间"点击设备"对话框），删除确认按钮文案为"移除"且为红色警示按钮。旧代码在需求（式样）迭代后未同步，属于绘制/流程与设计稿不一致的 UI 缺陷。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、BluetoothAnwFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt、values/strings.xml、values-en/strings.xml
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -153,9 +153,12 @@
             val device: CachedBluetoothDevice? =
                 (adapter.getItem(position) as MultiBluetoothDevice).bluetoothDevice
-            BluetoothUtil.showClickDeviceDialog(
-                mPhonePairedDevices, device, lifecycleScope, childFragmentManager
-            )
+//            BluetoothUtil.showClickDeviceDialog(
+//                mPhonePairedDevices, device, lifecycleScope, childFragmentManager
+//            )
+            device?.apply {
+                BluetoothUtil.connectOperationDevice(childFragmentManager, this, true)
+            }
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -521,8 +521,9 @@
             TextDialog(
                 device.name,
                 getString(R.string.device_remove_hint),
-                getString(R.string.proceed_to_remove),
-                getString(R.string.cancel)
+                getString(R.string.remove),
+                getString(R.string.cancel),
+                R.drawable.selector_common_red_btn
             )
```
（另在 BluetoothAnwFragment.kt 将同款弹窗文案 `proceed_to_remove` 改为 `remove`；values/values-en 的 strings.xml 删除废弃的 `proceed_to_remove` 条目。）

## 为什么能修复
点击行为从"showClickDeviceDialog 二次确认"改为直接 `connectOperationDevice(childFragmentManager, device, true)`，砍掉了与新式样不符的中间对话框；删除确认弹窗按钮换成 `remove` 文案并挂 `selector_common_red_btn` 红色按钮，两个入口（BluetoothFragment/BluetoothAnwFragment）同步统一；废弃字符串清理避免后续误用。隐患：`showClickDeviceDialog` 相关代码只是注释而非删除，且直接连接跳过了原先可能的"断开提示/防误触"环节，误触概率可能上升，需要测试覆盖。

## 复盘经验
- 弹窗按钮文案（"继续移除" vs "移除"）和危险色样式是 UI 走查高频命中点，确认类弹窗建议封装统一工厂方法收敛文案与样式。
- 注释掉旧调用而不是删除，会在代码里留下"僵尸流程"，后续维护者难以判断哪个是真实路径；确定废弃应删码留史。
- 同一个确认弹窗在多个 Fragment 里各写一份 TextDialog，文案改动就要多处同步，本单两处就是证据——应抽公共方法。
