# SIR-8058 · 偶现 CarPlay 已连接但设备列表显示未连接

- **提交**：`147099cb` | 2026-09-10 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 待测试验证 · 域 手车互联

## 问题
偶现 CarPlay 已连接成功，但设置内设备列表中该设备仍显示"未连接"状态。

## 根因分析
`DeviceConnectManager`（单例）注册的 CarPlay 设备状态回调 `onCarPlayDeviceStatusChanged` 原本只有两个分支：`DeviceStatus.INVALID` 走 `changeSourceDataType(btAddr)`，`DeviceStatus.AVAILABLE` 仅 `BluetoothUtil.SRefreshData.postValue(true)` 触发列表整体刷新，**没有处理 `DeviceStatus.CONNECTED`**，连接成功事件从未分发给 UI 监听器，设备项的已连接标记依赖刷新接口返回的数据，偶发返回不准（缺陷库记"返回数据不对"）时列表就停在未连接态。消费侧 `BluetoothFragment.onDeviceStatusChanged` 的语义也是错的：把第三参数当 `deleteDeviceId`，用 `deviceId` 匹配后直接把设备从 `mAdapter.data`/`mPhonePairedDevices` 里删除，与"状态变更"语义完全不符。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt（2 文件 +23/-13）
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ onCarPlayDeviceStatusChanged
-            if (device?.deviceStatus == CarPlayConstants.DeviceStatus.INVALID) {
-                changeSourceDataType(device.btAddr)
-            } else if (device?.deviceStatus == CarPlayConstants.DeviceStatus.AVAILABLE) {
-                BluetoothUtil.SRefreshData.postValue(true)
+            when (device?.deviceStatus) {
+                CarPlayConstants.DeviceStatus.INVALID -> { changeSourceDataType(device.btAddr) }
+                CarPlayConstants.DeviceStatus.AVAILABLE -> { BluetoothUtil.SRefreshData.postValue(true) }
+                CarPlayConstants.DeviceStatus.CONNECTED -> {
+                    ThreadUtils.runOnUiThread {
+                        synchronized(mListener) {
+                            for (callback in mListener) {
+                                callback.onDeviceStatusChanged(CARPLAY, true, device.btAddr)
+                            }
+                        }
+                    }
+                }
             }
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ onDeviceStatusChanged(deviceType, isConnected, deviceMac)
-        deleteDeviceId?.let {
-            val target = mAdapter.data.firstOrNull { it.bluetoothDevice?.deviceId == deleteDeviceId }
-            target?.let { mAdapter.data.remove(it) }
-            mPhonePairedDevices.removeIf { it.bluetoothDevice?.deviceId == deleteDeviceId }
+        deviceMac?.let {
+            mPhonePairedDevices.firstOrNull{ it.bluetoothDevice?.address == deviceMac }?.apply {
+                bluetoothDevice?.setIsPhoneCarConnect(isConnected)
+            }
+            mAdapter.notifyDataSetChanged()
         } ?: run { BluetoothUtil.handleDeviceList(mPhonePairedDevices) }
```
（接口注释同步将参数 `deleteDeviceId` 更名为 `deviceMac`）

## 为什么能修复
新增 CONNECTED 分支后，连接成功事件在 UI 线程同步给所有监听器；Fragment 侧改为按 `bluetoothDevice.address` 定位设备并直接写 `setIsPhoneCarConnect(isConnected)` 再 `notifyDataSetChanged()`，不再依赖刷新接口的返回值，也不再有"误删设备"的错误语义，偶发的脏数据路径被绕开。隐患：`mAdapter.notifyDataSetChanged()` 是全量刷新，量小可接受；CARPLAY 场景下按 MAC 匹配若设备列表尚未同步完成会匹配失败，仍需依赖 AVAILABLE 分支的整表刷新兜底。

## 复盘与经验
- 状态机回调漏分支是"偶现显示不同步"的经典根因：INVALID/AVAILABLE 都想到了，唯独漏掉 CONNECTED；枚举分支应用 when 全覆盖并处理 else。
- 修复策略"不调用 api，直接修改数据"的实质是缩短数据链路：UI 状态由事件直写本地模型，而不是经一次可能失真的查询；事件直写 + 刷新兜底是较稳的组合。
- 回调参数语义（deleteDeviceId vs deviceMac）错用说明接口注释/命名不清晰时极易埋雷，重命名即是修复的一部分。
