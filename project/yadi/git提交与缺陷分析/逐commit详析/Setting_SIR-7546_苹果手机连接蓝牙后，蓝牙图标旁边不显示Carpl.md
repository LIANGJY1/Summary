# SIR-7546 · 苹果手机连接蓝牙后，蓝牙图标旁不显示CarPlay连接图标
- **提交**：`47d464bc` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 待测试验证 · 域 车控车设

## 问题
iPhone 连上车机蓝牙后，蓝牙设备列表中该设备旁未显示 CarPlay 连接图标（设备实际支持 CarPlay）。

## 根因分析
`DeviceConnectManager.kt` 的 CarPlay 设备列表监听器 `mCarPlayDeviceListListener`（经 `mCarPlayDeviceManager.registerCarPlayDeviceListListener` 注册）在设备状态变化回调里，只对既有分支（蓝牙配对路径，处理后 `BluetoothUtil.SRefreshData.postValue(true)` 通知界面刷新）做了响应；当互联 SDK 上报设备状态为 `CarPlayConstants.DeviceStatus.AVAILABLE`（iPhone 已通过蓝牙接入、CarPlay 能力可用）时，回调落入"未处理"区间，不触发任何刷新。而蓝牙列表界面 `BluetoothFragment` 正是观察 `BluetoothUtil.SRefreshData`（`MutableLiveData<Boolean>`）来重绘设备行、决定是否渲染 CarPlay 图标的——缺了这次刷新，图标要等下一次列表重建才可能出现，表现即"不显示/偶现显示"（缺陷频次标注为偶现，与刷新时机被其他事件掩盖的程度吻合）。修复极简：在回调中补一个 `else if (device?.deviceStatus == CarPlayConstants.DeviceStatus.AVAILABLE)` 分支，同样 `BluetoothUtil.SRefreshData.postValue(true)`。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
                 BluetoothUtil.SRefreshData.postValue(true)
+            } else if (device?.deviceStatus == CarPlayConstants.DeviceStatus.AVAILABLE) {
+                BluetoothUtil.SRefreshData.postValue(true)
             }
         }
```

## 为什么能修复
"设备变为 AVAILABLE"这一状态迁移现在会即时驱动 `SRefreshData`，蓝牙列表观察者立刻重查 CarPlay 设备状态并渲染图标，不再依赖后续偶然的刷新。改动一行分支，风险极低；需留意 `postValue` 在高频状态迁移下可能合并触发，对幂等的列表刷新无碍。

## 复盘与经验
- 设备状态机驱动 UI 的场景，枚举每个 `DeviceStatus` 迁移并核对"哪些状态需要通知 UI"是接入监听器时的固定功课——本例的 `AVAILABLE` 是接入初期最容易被忽略的中间态。
- 用单一 `LiveData` 事件（`SRefreshData`）作为"数据变了"的广播总线，让新增触发源变成一行代码，是这类多源刷新需求的良好基础设施。
- "偶现显示/不显示"类 UI 问题，先画出"状态迁移 → 刷新事件 → UI 渲染"链路，检查每个迁移是否都有事件发出，比反复复现更高效。
