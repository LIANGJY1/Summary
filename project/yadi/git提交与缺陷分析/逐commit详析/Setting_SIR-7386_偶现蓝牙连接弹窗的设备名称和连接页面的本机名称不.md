# SIR-7386 · 偶现蓝牙连接弹窗设备名称与连接页"本机名称"不一致
- **提交**：`7ff2e26f` | 2026-09-04 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
手机连接车机时弹出的蓝牙连接请求弹窗里显示的设备名，与设置-连接页"本机名称"不一致（偶现）。

## 根因分析
蓝牙协议栈中的本机名（`BluetoothUtil.mWxBtManager.name`）与车设页面展示的本机名（由 VIN 推导/持久化的 `getDeviceName()`）是两份独立数据。`SettingVehicleService.initDeviceName` 原逻辑每次启动都走 `handleDeviceName(vin)` → `syncDeviceNameToBluetooth` 异步同步；当该同步因时序/持久化偶发失败或未完成时，协议栈仍保留旧名，连接弹窗（读取协议栈名）与连接页（读取车设名）便出现不一致——即缺陷库推测的"蓝牙协议栈名称持久化失败"。原逻辑没有启动时的比对兜底，一次失败后不再自愈。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
             override fun doInBackground(): Any? {
                 val vinProperty = settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)
                 LogUtils.d(TAG, "initDeviceName: $vinProperty")
-                handleDeviceName(vinProperty?.value .toString()) { deviceName ->
-                    LogUtils.d(TAG, "initDeviceName: $deviceName")
-                    syncDeviceNameToBluetooth(deviceName)
+                val deviceName = getDeviceName()
+                if (deviceName.isNotEmpty()) {
+                    val btName = BluetoothUtil.mWxBtManager.name
+                    if (!btName.isNullOrEmpty() && btName != deviceName) {
+                        BluetoothUtil.mWxBtManager.name = deviceName
+                    }
+                } else {
+                    handleDeviceName(vinProperty?.value.toString()) { name ->
+                        LogUtils.d(TAG, "initDeviceName: $name")
+                        syncDeviceNameToBluetooth(name)
+                    }
                 }
                 return null
             }
```

## 为什么能修复
启动时优先读取已持久化的 `getDeviceName()`，非空则直接与蓝牙协议栈当前名 `mWxBtManager.name` 比对，不一致立即用 `name = deviceName` 纠正——相当于每次开机做一次"对账"，无论上次同步是否失败都能自愈，弹窗与连接页名称恢复一致；仅在持久化名为空（首次激活/数据异常）时才回退到原 `handleDeviceName(VIN)` 推导链路。隐患：如果用户在协议栈层被其他模块改名，这里会在下次启动强制改回车设名，需保证车设名是唯一权威源。

## 复盘与经验
- "偶现不一致"的两组名称/状态数据，与其排查一次失败的原因，不如加一条启动时比对纠偏的兜底路径——对账逻辑成本低且能自愈所有历史脏数据。
- 同一"设备名"存在多个存储点（蓝牙协议栈、车设持久化、VIN 推导）时必须明确唯一权威源，其余存储点只做缓存并对账。
- 修复时保留原推导链路作为空值回退（`deviceName` 为空才走 `handleDeviceName`），避免影响首次初始化场景。
