# SIR-3261 · 已配对设备列表需多次切换才能连接成功

- **提交**：`37f742e2` | 2026-07-27 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
在手机蓝牙"已配对设备列表"中切换连接目标设备时，需要反复多次切换才能连接成功。

## 根因分析
`BluetoothUtil.operationPhoneCar()` 在连接新设备 `device` 前，若发现 `mWxBtManager.currentConnectDevice` 已有别的设备在线，旧逻辑是 `currentConnectDevice.disconnect()` 后 `delay(160.milliseconds)` 再 `device.connect(true)`。问题在于断开是异步过程，160ms 是拍脑袋定的固定等待：蓝牙协议栈还没完成断链时就发起 connect，底层会因为已有连接占用而拒绝，用户只能反复切换碰运气。缺陷库根因"未等其他设备断开连接去连接"与此一致。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                                 if (mWxBtManager.currentConnectDevice != null && mWxBtManager.currentConnectDevice != device) {
+                                    BluetoothFragment.SConnectCallback = object : BluetoothFragment.OnConnectListener {
+                                        override fun onConnectDevice() {
+                                            log("same onConnectDevice $device")
+                                            device.connect(true)
+                                        }
+                                    }
                                     mWxBtManager.currentConnectDevice.disconnect()
-                                    delay(160.milliseconds)
+                                } else {
+                                    device.connect(true)
                                 }
-                                device.connect(true)
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
             ManagerConstants.STATE_DISCONNECTED -> {
+                SConnectCallback?.onConnectDevice()
+                SConnectCallback = null
                 if (!cachedDevice.isConnected) {
                     handleBluetoothData()
                 }
```
并在 `BluetoothFragment` companion 中新增静态 `SConnectCallback: OnConnectListener?` 与 `interface OnConnectListener { fun onConnectDevice() }`；`initView` 与 `onDestroyView` 中将 `SConnectCallback` 置空。

## 为什么能修复
把"固定延时 160ms 后重连"改为"事件驱动"：连接动作被挂到 `SConnectCallback`，等收到 `STATE_DISCONNECTED` 状态（断开真正完成）才执行 `device.connect(true)`，从机制上消除了时序竞态。副作用：回调挂在静态字段上，若断开广播一直不来，连接会被搁置——所以配套在 `initView`/`onDestroyView` 清空回调，避免跨页面残留触发错误设备连接。

## 复盘与经验
- 用固定 sleep/delay 等待异步蓝牙状态变更不可靠，应订阅真实状态广播（STATE_DISCONNECTED）后再发起下一步动作。
- 静态回调需要成对的"注册-消费-清理"生命周期管理，否则容易泄漏或触发过期连接。
- "多次重试才成功"类 bug，大概率是时序竞态而非权限/参数问题，优先查异步完成事件与后续动作的衔接方式。
