# SIR-7499 · 移除 HiCar 已配对设备后图标残留在可配对设备列表
- **提交**：`5190bdbe` | 2026-09-08 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联（rc：判断变量数据没有修改 → 同步更新数据）

## 问题
移除 HiCar 已配对设备后，该设备的 HiCar 图标仍残留在"可配对设备"列表中，分类展示错误。

## 根因分析
蓝牙设备列表按 `CachedBluetoothDevice.phoneCarConnectionType` 字段区分展示形态（0=普通蓝牙，1=手机车机互联如 HiCar/CarPlay/CarLink）。移除 HiCar 设备时，删除流程只做了 `disconnect()/unpair()`，但缓存设备对象上的 `phoneCarConnectionType` **没有被复位为 0**（缺陷库"判断变量数据没有修改"）——unpair 后设备会从已配对列表转入可配对列表，但它仍带着 type=1 的旧分类，于是可配对列表里渲染出 HiCar 图标。此外，`DeviceConnectManager` 里 CarPlay/HiCar/CarLink 三种互联协议的 `INVALID`/断开事件处理各自为政：有的地方只发 `SRefreshData` 刷新、有的地方只回调 `mDeleteDeviceListener`，回调是一次性置空的一次性监听，时序错开就漏掉数据修正。修复引入统一的 `changeSourceDataType(address)`：在收到任一协议的设备 INVALID 事件时，遍历 `availableDevices`/`pairedDevices`，用 `isSameDevice`（并扩展为同时匹配 `address` 与 `deviceId`）找到匹配设备并把其 `phoneCarConnectionType` 置 0，随后触发 `mDeleteDeviceListener` 回调与 `BluetoothUtil.SRefreshData` 列表刷新；删除确认弹窗流程也按 `phoneCarConnectionType` 分流（0 直接断开解绑，1 先 `operationPhoneCar(device, true)` 再等删除回调后断开解绑）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ (HiCar 设备状态回调)
         override fun onHiCarDeviceStatusChanged(device: HiCarDevice?) {
             device?.let {
                 if (it.deviceStatus == HiCarConstants.DeviceStatus.INVALID) {
                     changeSourceDataType(it.btAddr)
                 }
             }
         }
+
+    fun changeSourceDataType(address: String){
+        mWxBtManager.availableDevices.forEach {
+            if (isSameDevice(it, address)) { return@forEach }
+        }
+        mWxBtManager.pairedDevices.forEach {
+            if (isSameDevice(it, address)) { return@forEach }
+        }
+        mDeleteDeviceListener?.onDeleteDevice()
+        mDeleteDeviceListener = null
+        BluetoothUtil.SRefreshData.postValue(true)
+    }
+
     fun isSameDevice(device: CachedBluetoothDevice, btAddr: String) : Boolean{
-        if (TextUtils.equals(device.address, btAddr)) {
+        if (TextUtils.equals(device.address, btAddr) || TextUtils.equals(device.deviceId, btAddr)) {
             device.phoneCarConnectionType = 0
             return true
         }
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
@@ (移除确认弹窗)
                 override fun confirm(content: Any?) {
                     lifecycleScope.launch(ioDispatcher) {
                         if (device.phoneCarConnectionType == 0) {
                             if (device.device != null) {
                                 delay(1000.milliseconds)
                                 device.disconnect()
                                 device.unpair()
                             }
                         } else {
                             operationPhoneCar(device, true)
                             DeviceConnectManager.getInstance()
                                 .setOnDeleteDeviceListener(object : OnDeleteDeviceCallback {
                                     override fun onDeleteDevice() {
                                         com.yadea.hardwarelibs.utils.ThreadUtils.schedule({
                                             device.disconnect()
                                             device.unpair()
                                         }, 100)
                                     }
                                 })
                         }
                     }
                 }
```
（监听接口 `OnDisconnectDeviceListener`/`OnDeleteDeviceListener` 更名为 `...Callback`；CarPlay/CarLink 的 INVALID 分支同样收敛到 `changeSourceDataType`。）

## 为什么能修复
设备被互联协议标记 INVALID 的那一刻，`changeSourceDataType` 立即在缓存里把 `phoneCarConnectionType` 归零并广播刷新——列表 UI 依据的就是这份缓存字段，字段同步后可配对列表自然按普通蓝牙设备渲染，HiCar 图标不再残留；三种协议统一走同一条数据修正路径，消除各自的时序遗漏。隐患：`forEach` 里的 `return@forEach` 只结束当前 lambda 不跳出循环，匹配后仍继续遍历（低效但功能不受影响）；一次性回调（用后置 null）模式在多设备并发删除时仍可能错绑，值得后续改为按地址路由。

## 复盘经验
- 列表分类图标来自缓存对象上的类型字段——"移除/降级"操作必须同步改字段再刷新，只做断连/解绑不改数据是残留类 bug 的典型成因。
- 多协议（CarPlay/HiCar/CarLink）各自实现一套状态回调时，公共收尾逻辑（改数据、发刷新、清回调）应收敛到一个函数，本单的 changeSourceDataType 就是正确方向。
- `return@forEach` 是"继续下一项"而非"跳出循环"，在 forEach 里找第一个匹配要配合 takeIf/firstOrNull，否则代码意图与行为不符。
