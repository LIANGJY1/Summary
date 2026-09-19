# SIR-7890 · 耳机蓝牙连接界面缺少"默认设备"设置项
- **提交**：`a1a4ad5e` | 2026-09-14 | dufan | Setting | 功能补齐（[why]"未添加对应逻辑"，按需求补实现；cherry-pick 自 60786022）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙（耳机）连接界面只有"移除设备"，缺少"设为默认设备"入口，无法配置耳机优先连接的默认设备（默认设备优先连接，且连接时会把前座耳机自动降为后座耳机）。

## 根因分析
UI 与底层链路双双缺失：`BluetoothAnwFragment.handleRemoveDeviceClick` 只提供移除确认弹窗（confirm→`startPairOrUnpair(false, mac)` 解绑），没有默认设备设置路径；`component/Hardwarelibs` 的 `BtAdapter` 也没有封装 `AnWBT_Set_Default_Master_Device` 这一下层 AIDL 能力，`BtAnwManager` 自然无对应 API。即"未添加对应逻辑"——底层协议栈已有该能力，但 Adapter 封装、Manager 包装、Fragment 交互三层都没有接。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt；application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/res/values-en/strings.xml；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
+++ b/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ +    public void setDefaultMasterDevice(String address){
+        Log.d(TAG, "setDefaultMasterDevice:" + address);
+        mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+        ThreadUtils.runOnUiThread(() -> mBtAdapter.AnWBT_Set_Default_Master_Device(address), 150);
+    }
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java
+++ b/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java
@@ +    public int AnWBT_Set_Default_Master_Device(String address) {
+        if (DBG) Log.d(TAG, "AnWBT_Set_Default_Master_Device");
+        return executeAidl("AnWBT_Set_Default_Master_Device", () -> mService.AnWBT_Set_Default_Master_Device(address));
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@     private fun handleRemoveDeviceClick(device: DeviceBean) {
         TextDialog(
             device.name,
-            getString(R.string.device_remove_hint),
-            getString(R.string.remove),
-            getString(R.string.cancel)
+            "",
+            getString(R.string.set_default),
+            getString(R.string.remove)
         ).setCallback(object : Callback {
             override fun confirm(content: Any?) {
+                TextDialog(
+                    getString(R.string.default_device),
+                    getString(R.string.set_default_device_hint),
+                    getString(R.string.set),
+                    getString(R.string.cancel)
+                ).setCallback(object : Callback {
+                    override fun confirm(content: Any?) {
+                        BtAnwManager.getInstance().setDefaultMasterDevice(device.macAddress)
+                    }
+                    override fun cancel() {}
+                }).show(childFragmentManager, "HintDialog")
+            }
+
+            override fun cancel() {
                 lifecycleScope.launch(ioDispatcher) {
                     BtAnwManager.getInstance().startPairOrUnpair(false, device.macAddress)
                 }
```

## 为什么能修复（实现评价）
三层打通：`BtAdapter` 新增 `AnWBT_Set_Default_Master_Device` AIDL 封装（复用 `executeAidl` 统一异常/线程处理）→ `BtAnwManager.setDefaultMasterDevice` 先打 `AnWBT_Set_Auto_Connect_Interrupt_Flag()` 打断当前自动连接、延迟 150ms 再设默认设备（避免与进行中的自动连接竞态）→ Fragment 弹窗按钮扩展为"设为默认/移除"双入口，确认后经二次说明弹窗下发。注意点：原"移除"语义被挪到 `cancel()` 回调按钮上，属于复用 TextDialog 双按钮的取巧实现，可读性一般但行为正确；values-en 里中文字符串未翻译是遗留瑕疵。

## 复盘与经验
- "界面缺设置项"类问题的排查路径：先确认底层能力是否存在，再按 Adapter→Manager→UI 三层补链路，缺哪层补哪层。
- 改变自动连接相关状态前先打断进行中的自动连接（`Set_Auto_Connect_Interrupt_Flag` + 延迟下发），是蓝牙状态机避免竞态的常见组合拳。
- 复用确认弹窗的双按钮承载"设为默认/移除"两种互斥动作时，按钮语义与回调名（confirm/cancel）会错位，注释与 review 需格外小心。
