# 无单号 · 修改手车互联共享开关关闭同步

- **提交**：`70302dd8` | 2026-07-20 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
修复蓝牙手车互联页"流量共享"开关关闭时状态不落盘的问题：关闭开关除了下发关闭指令，还要把共享状态写入 GSetting，保证重进页面/跨进程读取到已关闭状态。

## 实现结构
单文件 2+/1-：`ui/fragment/diologfragment/BluetoothFragment.kt`
- 开关 off 分支补 `SettingsUtils.setGSetting(CURRENT_DEVICE_SHARE_NETWORK_STATE, 2)`（2=关闭），与既有 `changeShareNetworkState(false)` 并列；
- `onDeviceStatusChanged` 中 `mAdapter.notifyDataSetChanged()` 改为 `setAdapterData()`（改为重设数据集，配合 DiffUtil/刷新头部数据）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
             } else {
+                SettingsUtils.setGSetting(CURRENT_DEVICE_SHARE_NETWORK_STATE, 2)
                 DeviceConnectManager.getInstance().changeShareNetworkState(false)
             }
```
```diff
         setTrafficSharingView()
         log("onDeviceStatusChanged: $deviceType--$isConnected--$deleteDeviceId ，$mPhonePairedDevices")
-        mAdapter.notifyDataSetChanged()
+        setAdapterData()
```
实现讲解：共享开关的"开"路径此前已经会把状态写入 GSetting（由 DeviceConnectManager 内部处理），而"关"路径只调了业务方法没有落盘，造成开关状态回读不对称。此提交补齐对称性。`notifyDataSetChanged` → `setAdapterData` 属于刷新方式优化，避免整表盲刷。

## 复盘与要点
- 状态双写（业务动作 + 持久化）最容易在"关闭"分支遗漏——因为关闭常常走默认分支；review 时应把开/关两条路径对照检查。
- `CURRENT_DEVICE_SHARE_NETWORK_STATE` 魔法值 2 无常量语义注释，后续 `13ba9d1d` 会把它迁移到 DeviceConnectManager 单独管理，可见此处的临时性。
