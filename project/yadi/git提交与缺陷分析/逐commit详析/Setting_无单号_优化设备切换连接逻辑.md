# 无单号 · [SRS_BT_LinkSetting_012] 优化设备切换连接逻辑

- **提交**：`802cab95` | 2026-08-13 | dufan | Setting | feature
- **关联单**：无（SRS_BT_LinkSetting_012）

## 需求/目标
修复手机互联（CarPlay/HiCar/CarLink）与蓝牙设备切换连接的时序问题：原先"先强制断开互联再操作蓝牙"，断开是异步的，导致蓝牙连接与互联断开互相踩踏；改为"互联会话真正断开后（回调通知）再执行蓝牙断开与重连"。

## 实现结构
改动 3 个文件：`DeviceConnectManager.kt`（新增 `OnDisconnectDeviceListener` 接口、单例 listener 字段，在 CarPlay SESSION_STATUS_DEACTIVATED / HiCar DEVICE_DISCONNECT / CarLink DEVICE_DISCONNECTED 三条会话断开路径上统一触发回调并置空）；`BluetoothUtil.kt`（连接提示弹窗的 callback 中调整顺序——非蓝牙操作场景先注册断开监听，再发起 `operationPhoneCar(it, isForceDisconnect = true)`，把蓝牙断开+连接新设备逻辑放进回调）；`item_paired_device.xml`（已配对设备图标尺寸 27/24→30 并加 3dp padding、间距调整，视觉对齐）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ val callback: () -> Unit = {
                     SCurrentThirdDevice?.let {
-                        operationPhoneCar(it, isForceDisconnect = true)
                         if (isOperationBluetooth) {
+                            operationPhoneCar(it, isForceDisconnect = true)
                             ...
                         } else {
-                            if (it.isConnected) {
-                                it.disconnect()
-                            }
-                            operationPhoneCar(device)
+                            DeviceConnectManager.getInstance().setOnDisconnectDeviceListener(object : DeviceConnectManager.OnDisconnectDeviceListener {
+                                override fun onDisconnectDevice() {
+                                    if (it.isConnected) {
+                                        it.disconnect()
+                                    }
+                                    operationPhoneCar(device)
+                                }
+                            })
+                            operationPhoneCar(it, isForceDisconnect = true)
                         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ override fun onSessionStatusChanged(status: Int) { // CarPlay
                 ...
                 CARPLAY,
                 status == CarPlayConstants.SessionStatus.SESSION_STATUS_ACTIVATED
             )
+            if (status == CarPlayConstants.SessionStatus.SESSION_STATUS_DEACTIVATED){
+                mDisconnectDeviceListener?.onDisconnectDevice()
+                mDisconnectDeviceListener = null
             }
```
实现讲解：核心手法是"把异步断开事件化"——DeviceConnectManager 作为三种互联协议会话状态的汇聚点，新增一次性（用后置 null）断开回调；调用方从"按顺序发命令"改为"发命令 + 等事件再善后"，消除了强制断开尚未完成时蓝牙就开始连接的竞态。三条协议路径都触发同一回调，保证 whichever 协议断开都能收到。

## 复盘与要点
- 可复用手法：对异步互斥操作（断 A 再连 B），用"一次性监听器 + 事件触发后置空"串行化，比延时 sleep 可靠。
- 风险：回调只保存一个 listener 且超时/断开失败时不会被清理，若会话永远不回调，蓝牙切换将静默丢失；可考虑加超时兜底。
- 一处"先注册后触发"顺序敏感：`setOnDisconnectDeviceListener` 必须在 `operationPhoneCar(force)` 之前调用，代码里靠书写顺序保证，容易在后续维护中被调换。
