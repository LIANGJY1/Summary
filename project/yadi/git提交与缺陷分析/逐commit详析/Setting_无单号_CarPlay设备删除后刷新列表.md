# 无单号 · CarPlay 设备删除后刷新列表

- **提交**：`09f97aa0` | 2026-07-23 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
修复 CarPlay 设备在手机侧解除绑定（设备状态变 INVALID）后，车机蓝牙列表仍显示旧条目、不自动刷新的问题。

## 实现结构
单文件 3 行：`init/DeviceConnectManager.kt` 的 `onCarPlayDeviceStatusChanged` 回调中，检测 `device?.deviceStatus == CarPlayConstants.DeviceStatus.INVALID` 时通过既有 `BluetoothUtil.SRefreshData`（MutableLiveData）post true，通知蓝牙列表页刷新数据。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
             LogUtils.d(
                 TAG, "onCarPlayDeviceStatusChanged:$device"
             )
+            if (device?.deviceStatus == CarPlayConstants.DeviceStatus.INVALID) {
+                BluetoothUtil.SRefreshData.postValue(true)
+            }
         }
```
实现讲解：列表刷新走的是模块内已有的 `SRefreshData` LiveData 事件总线（`13ba9d1d` 中已可见其用途），本次只是把"CarPlay 设备失效"这一事件源接入，属于 3 行的补链修复（标题写 feature，按 diff 定性为缺陷修补）。

## 复盘与要点
- 好的一面：复用既有刷新事件，改动最小、无新依赖。
- 通用问题：设备删除只覆盖了 CarPlay 的 INVALID 状态，HiCar/CarLink 的解绑是否有对应事件源需另行核对；状态驱动的 UI 列表应尽量让"每个状态变化源"都接到同一刷新总线。
