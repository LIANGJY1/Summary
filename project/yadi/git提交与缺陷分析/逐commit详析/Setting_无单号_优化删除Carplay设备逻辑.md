# 无单号 · 优化删除 CarPlay 设备逻辑

- **提交**：`f741f664` | 2026-08-24 | dufan | Setting | feature（实质为缺陷修复）
- **关联单**：无

## 问题（diff 定性）
删除 CarPlay 设备后设备连接类型标记未清零、且断开+取消配对前仅等 500ms 不够，导致删除后设备残留旧连接类型状态、或配对记录未及断开就被 unpair 引发异常表现。

## 根因分析
1. `onDeleteDevice` 回调只调 `deleteCarPlayDevice(address)`，未把 `device.phoneCarConnectionType` 复位，UI 层仍按 CarPlay 设备渲染/判断；
2. `operationPhoneCar(device, true)` 强制断开互联是异步流程，固定 delay 500ms 后立即 `disconnect + unpair`，弱环境下断开尚未完成，取消配对会失败或产生半连接状态。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                                     .setOnDeleteDeviceListener(object :
                                         DeviceConnectManager.OnDeleteDeviceListener {
                                         override fun onDeleteDevice() {
+                                            device.phoneCarConnectionType = 0
                                             deleteCarPlayDevice(device.address)
                                         }
                                     })
@@ lifecycleScope.launch(Dispatchers.IO) {
                         operationPhoneCar(device, true)
                         if (device.device != null) {
-                            delay(500.milliseconds)
+                            delay(1000.milliseconds)
                             device.disconnect()
                             device.unpair()
                         }
```
## 为什么能修复
删除时先把连接类型归零，保证删除动作后 UI/逻辑视图一致；断开等待从 500ms 翻倍到 1000ms，给异步强制断开留足时间窗，降低"未断开就 unpair"的失败概率（时序兜底式修复，非事件驱动）。

## 复盘与经验
- 与 `802cab95`（设备切换）同一思路的两端：切换已改为断开回调驱动，删除仍是固定 delay 兜底，理想做法是统一复用 OnDisconnectDeviceListener 事件化等待。
- `phoneCarConnectionType` 这类设备缓存字段在增删场景必须成对复位，建议把"清除"收敛进 deleteCarPlayDevice 内部，避免调用点各写各的。
