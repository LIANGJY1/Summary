# 无单号 · 修改CarLink删除逻辑

- **提交**：`86505312` | 2026-07-08 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
重构 CarLink（手机互联）设备的"断开即删除"链路：用前一提交引入的 `deviceId` 精确删除设备，并把删除事件通过连接状态回调推到 UI 做定点移除；同时修正两处连接状态判断。

## 实现结构
改动 3 个文件：
- `init/DeviceConnectManager.kt`：回调签名 `onDeviceStatusChanged` 第三参由 `statusMsg` 语义改为 `deleteDeviceId`；`onCarLinkDeviceChanged` 收到 `DeviceState.INVALID`（设备失效/被删）时向所有监听者广播 `(CARLINK, false, deviceId)`；CarLink 断开分支触发挂起的 `mDeleteDeviceListener` 并置空；关共享时写入 `CURRENT_DEVICE_SHARE_NETWORK_STATE=2`；另修正 CarPlay 会话判断的反向条件。
- `ui/fragment/diologfragment/BluetoothFragment.kt`：`onDeviceStatusChanged` 收到 `deleteDeviceId` 时直接从适配器数据与 `mPhonePairedDevices` 中按 `deviceId` 定点移除，否则回退到全量重建 `handleDeviceList`。
- `utils/BluetoothUtil.kt`：CarLink 连接/断开由 `address` 改用 `deviceId`；`isNeedDelete` 时注册一次性删除监听（等真正断开后 `deleteCarLinkDevice`）或直接删除；`handleDeviceList` 合并列表时同步 `deviceId`，并保证"当前已连接的互联设备"置顶插入。

数据流：UI 删除请求 → `BluetoothUtil.disconnectCarLink(deviceId)` → 车端会话断开/设备 INVALID → `DeviceConnectManager` 广播 `deleteDeviceId` → `BluetoothFragment` 定点移除条目并刷新。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -669,6 +667,17 @@
         override fun onCarLinkDeviceChanged(device: CarLinkDevice?) {
             super.onCarLinkDeviceChanged(device)
             LogUtils.d(TAG, "onCarLinkDeviceChanged:$device")
+            device?.let {
+                if (it.deviceState == CarLinkConstants.DeviceState.INVALID) {
+                    ThreadUtils.runOnUiThread {
+                        synchronized(mListener) {
+                            for (callback in mListener) {
+                                callback.onDeviceStatusChanged(CARLINK, false, device.deviceId)
+                            }
+                        }
+                    }
+                }
+            }
         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ -114,14 +114,28 @@
                 3 -> {
                     if (device.isPhoneCarConnect) {
                         DeviceConnectManager.getInstance().getCarLinkDeviceListManager()?.apply {
-                            disconnectCarLink(device.address)
+                            disconnectCarLink(device.deviceId)
+                            if (isNeedDelete) {
+                                device.phoneCarConnectionType = 0
+                                DeviceConnectManager.getInstance()
+                                    .setOnDeleteDeviceListener(object :
+                                        DeviceConnectManager.OnDeleteDeviceListener {
+                                        override fun onDeleteDevice() {
+                                            deleteCarLinkDevice(device.deviceId)
+                                        }
+                                    })
+                            }
                         }
```
实现讲解：核心手法是"回调参数语义化 + 一次性监听器"。把通用连接回调的第三个参数从无用的 `statusMsg` 改造为 `deleteDeviceId`，空值表示普通状态刷新、非空表示设备被删，UI 据此走定点删除而非全量重算；删除动作则通过 `OnDeleteDeviceListener` 延迟到"连接确实断开"的时机执行，避免删除一个仍在会话中的设备。另外顺带修正 CarPlay 分支 `status == ACTIVATED` → `status != ACTIVATED` 才置 `mShareNetworkSupported=false` 的反向判断，并对 `disconnect/unpair` 前补 `device.device != null` 空保护。

## 复盘与要点
- "通用回调加参数并改语义"是低成本扩展手段，但 `deleteDeviceId == null` 双义（既是"无删除"又是"普通刷新"）靠约定区分，建议后续拆成独立事件回调。
- 用 `deviceId` 替代 MAC `address` 定位 CarLink 设备，依赖前序提交 `7b92870b` 的字段扩展，删除精度更高；但列表合并时两处 `deviceId` 拷贝必须齐全，漏拷一处即匹配失败。
- 遗留风险：`mDeleteDeviceListener` 是单槽全局位，连续删除两台设备时前一个监听会被覆盖；CarLink 删除与共享网络状态写入（Settings 置 2）耦合在同一链路，时序上依赖断开回调必然到达。
