# SIR-6786 · hicar 断连后设备图标残留在可配对列表
- **提交**：`0159d940` | 2026-08-31 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
HiCar 连接后再断连，"可配对设备"列表中该设备的 HiCar 图标与蓝牙图标残留，设备没有被真正删除。

## 根因分析
删除 HiCar 设备需要"断开会话 + 删除设备记录"两步。原 `BluetoothUtil` 断连处虽然判断了 `isNeedDelete` 并调用 `deleteHiCarDevice(device.address)`，但删除动作立即执行——此时 HiCar 会话尚未完全断开，删除被底层拒绝或被后续状态覆盖，图标残留。同时 `DeviceConnectManager` 的状态回调 switch 中 `HICAR` 分支是空实现：收到 `HiCarConstants.SessionState.DEVICE_DISCONNECT` 断连事件时什么都不做（对比其他分支的 `mDeleteDeviceListener?.onDeleteDevice()` 模式），"等会话真正断开后删"的钩子从未被挂接。两处叠加导致"未执行删除逻辑"。

## 关键代码修改
改动文件：DeviceConnectManager.kt、BluetoothUtil.kt
```diff
--- a/.../setting/init/DeviceConnectManager.kt
             HICAR -> {
+                if (status == HiCarConstants.SessionState.DEVICE_DISCONNECT){
+                    mDeleteDeviceListener?.onDeleteDevice()
+                    mDeleteDeviceListener = null
+                }
             }
```
```diff
--- a/.../setting/utils/BluetoothUtil.kt
                             disconnectHiCar(device.address)
                             if (isNeedDelete) {
-                                deleteHiCarDevice(device.address)
+                                device.phoneCarConnectionType = 0
+                                DeviceConnectManager.getInstance()
+                                    .setOnDeleteDeviceListener(object :
+                                        DeviceConnectManager.OnDeleteDeviceListener {
+                                        override fun onDeleteDevice() {
+                                            deleteHiCarDevice(device.address)
+                                        }
+                                    })
                             }
```

## 为什么能修复
把"立即删除"改为"登记删除回调、等 `DEVICE_DISCONNECT` 事件到达后再删"：会话完全断开后删除才执行，底层不再拒绝；`HICAR` 空分支补上分发逻辑，回调有了触发点，`onDeleteDevice` 执行后置 null 防止重复触发。`phoneCarConnectionType = 0` 同步清连接类型标记。隐患：监听器是单例槽位（`setOnDeleteDeviceListener` 覆盖式），若连续删除两台 HiCar 设备，第一台的回调可能被第二台覆盖造成漏删；异常路径（断连事件不来）会留下悬挂监听器。

## 复盘与经验
- "先断链路、后删记录"是有状态连接类设备管理的固定时序，删除必须订阅断连完成事件而非同步紧跟着调。
- switch 里的空分支（`HICAR -> {}`）是典型的"待实现"遗留，接入新设备类型时应把状态分支全量实现或显式 TODO 跟踪。
- 单例监听器槽位只适合单设备场景，多设备并发操作需改为列表或按设备 address 关联。
