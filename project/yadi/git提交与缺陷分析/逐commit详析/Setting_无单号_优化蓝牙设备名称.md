# 无单号 [SRS_BT_LinkSetting_002] 优化蓝牙设备名称
- **提交**：`abe23669` | 2026-08-24 | sgh | Setting | feature
- **关联单**：SRS_BT_LinkSetting_002

## 需求/目标
补齐设备名称与蓝牙名称的同步链路：App 启动时把本地设备名强制同步到蓝牙模块；首次按 VIN 生成默认设备名时，立即同步蓝牙名与热点名，修复"改了设备名但蓝牙/热点还叫旧名"的不一致。

## 实现结构
- `MyApplication.kt`：applicationScope 启动任务里新增 `syncDeviceNameToBluetooth()`——读本地设备名，与 `BluetoothUtil.mWxBtManager.name` 不一致才写回，整体 try/catch 防止蓝牙服务未就绪拖垮启动链。
- `DeviceUtils.kt`：`handleDeviceName` 增加 `setBtName: (String) -> Unit` 回调参数，仅在"首次生成默认名"分支触发，调用方决定同步目标（蓝牙名 + HOTSPOT_NAME 存储）。
- `ConnectFragment.kt`：调用处传回调，同步蓝牙名并写热点名 KV。
- `BluetoothFragment.kt`（1 行）：弹窗头部设备名显示改为完全信任蓝牙模块名（删去 `?: DeviceUtils.getDeviceName()` 兜底），避免显示与实际广播名不一致。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/MyApplication.kt
+    private fun syncDeviceNameToBluetooth() {
+        try {
+            val deviceName = DeviceUtils.getDeviceName()
+            if (deviceName.isNotEmpty() && BluetoothUtil.mWxBtManager.name != deviceName) {
+                BluetoothUtil.mWxBtManager.name = deviceName
+            }
+        } catch (e: Exception) {
+            LogUtils.e(TAG, "syncDeviceNameToBluetooth failed: $e")
+        }
+    }
```
```kotlin
// component/CommonTools/src/main/java/com/yadea/common/utils/DeviceUtils.kt
-    fun handleDeviceName(vin: String) {
+    fun handleDeviceName(vin: String, setBtName: (deviceName: String) -> Unit) {
         ...
             setGSetting(Constants.VehicleConfig.DEVICE_NAME, deviceName)
+            setBtName.invoke(deviceName)
```
启动时机的"全量对账式同步"（不一致才写）让蓝牙名最终收敛到本地 KV 的权威值，兜住"上次改名校验失败/蓝牙模块重启丢名"等历史脏状态；`handleDeviceName` 用回调参数把"生成后做什么"上移到调用方，避免 common 工具层反向依赖 Setting 的 BluetoothUtil。

## 复盘与要点
- 多副本名称（本地 KV、蓝牙、热点）必然漂移，"启动对账 + 变更点即时同步"双管齐下是这类一致性问题的标准解法。
- common 层用回调而非直接依赖上层管理器，保持了工具类的单向依赖，值得效仿。
- 风险：启动同步在 applicationScope.launch 中异步执行，若蓝牙管理器就绪晚于该协程，try/catch 只能保证不崩，名字仍未同步——更稳的做法是挂蓝牙就绪回调。
