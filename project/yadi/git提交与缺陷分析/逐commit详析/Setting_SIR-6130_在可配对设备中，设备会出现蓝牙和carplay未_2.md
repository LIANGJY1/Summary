# SIR-6130 · 可配对设备列表出现蓝牙和carplay未连接的图标

- **提交**：`c982673e` | 2026-08-24 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置的可配对设备列表中，设备条目错误地显示蓝牙和 CarPlay 均未连接的图标（实际设备状态并非如此）。

## 根因分析
`DeviceConnectManager` 监听 CarPlay 设备状态回调 `onCarPlayDeviceStatusChanged`：当 `device.deviceStatus == CarPlayConstants.DeviceStatus.INVALID` 时只是 `BluetoothUtil.SRefreshData.postValue(true)` 触发列表刷新，**没有重置缓存的蓝牙设备上的连接类型标记** `CachedBluetoothDevice.phoneCarConnectionType`。缺陷库根因"carplay还未断开时执行了删除操作失败"：CarPlay 断开过程中（尚未完全断开时）执行的连接类型清除没有生效，缓存设备上残留了旧的 `phoneCarConnectionType`（carplay 连接态），列表刷新后按残留标记渲染，出现"蓝牙和 carplay 都未连接"的错误图标。修复在 INVALID 状态时主动遍历 `mWxBtManager.availableDevices` 与 `pairedDevices`，对地址匹配（`device.address == device.btAddr`）的缓存设备把 `phoneCarConnectionType` 归零，再刷新列表。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt（+25）
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ CarPlay 设备状态回调
             if (device?.deviceStatus == CarPlayConstants.DeviceStatus.INVALID) {
+                mWxBtManager.availableDevices.forEach {
+                    if (isSameDevice(it, device.btAddr)) {
+                        return@forEach
+                    }
+                }
+                mWxBtManager.pairedDevices.forEach {
+                    if (isSameDevice(it, device.btAddr)) {
+                        return@forEach
+                    }
+                }
                 BluetoothUtil.SRefreshData.postValue(true)
             }
@@ 新增匹配并重置连接类型
+    fun isSameDevice(device: CachedBluetoothDevice, btAddr: String) : Boolean{
+        if (TextUtils.equals(device.address, btAddr)) {
+            device.phoneCarConnectionType = 0
+            return true
+        }
+        return false
+    }
```
（另新增 `mWxBtManager` 属性获取蓝牙管理器及相应 import。）

## 为什么能修复
CarPlay 状态转 INVALID 时，同步把可配对列表与已配对列表中同地址缓存设备的 `phoneCarConnectionType` 重置为 0，列表刷新后图标按真实状态（仅蓝牙维度）渲染，不再显示残留的 carplay 连接态。隐患：`isSameDevice` 命名只暗示"比较"，实际携带"重置连接类型"的副作用，易被误用；`return@forEach` 写法等价于 continue（并不会提前终止遍历），写法让人误以为有短路语义；状态 INVALID 到达时 CarPlay 链路可能仍未完全断开，若后续还有状态回调覆盖，需要保证时序上本重置是最后写入者。

## 复盘与经验
- 缓存对象上的"连接类型/状态"字段必须有明确的清理时机：状态机每次迁移（尤其 INVALID/断开）都应显式复位相关标记，只刷 UI 不清数据必然渲染出脏状态。
- 一个方法只做一件事：`isSameDevice` 这种"比较+副作用"的命名会让调用点难以审查。
- 断链类修复要点是找到"最后的可靠状态点"（本例的 INVALID 回调）作为清场时机。
