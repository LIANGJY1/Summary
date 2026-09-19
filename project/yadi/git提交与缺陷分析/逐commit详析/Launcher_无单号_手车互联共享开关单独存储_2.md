# 无单号 · 手车互联共享开关单独存储（Launcher 侧清理）

- **提交**：`72f52731` | 2026-07-21 | dufan | Launcher | feature
- **关联单**：无（与 Setting 侧 `13ba9d1d` 成对提交）

## 需求/目标
配合 Setting 侧共享开关改为按设备 JSON Map 存储后，删除 Launcher 中对旧全局键 `current_device_share_network_state` 的全部直接写入，避免双写冲突。

## 实现结构
2 个文件（-12，纯删除）：
- `launcher/Constants.java`：删除 `CURRENT_DEVICE_SHARE_NETWORK_STATE` 常量（及相邻 3 行同步清理）；
- `control/DeviceConnectManager.kt`：删除 HiCar `IHiCarSharedNetStateListener` 与 CarLink 回调里 6 处 `SettingsUtils.setGSetting(..., 0/1/2)` 写入，回调方法只保留日志。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
             override fun onShareNetworkFailed() {
                 LogUtils.d(TAG, "CarLink onShareNetworkFailed:")
-                SettingsUtils.setGSetting(CURRENT_DEVICE_SHARE_NETWORK_STATE, 0)
             }
             override fun onShareNetworkSuccess() {
                 LogUtils.d(TAG, "CarLink onShareNetworkSuccess:")
-                SettingsUtils.setGSetting(CURRENT_DEVICE_SHARE_NETWORK_STATE, 1)
             }
```
实现讲解：此前 Launcher 与 Setting 两端都会写同一个 GSetting 键，状态以"最后写入者"为准，正是共享开关串台的来源之一。本提交把 Launcher 降级为纯事件方，状态持久化唯一归属 Setting 的 `DeviceConnectManager`。

## 复盘与要点
- "同一个配置只有一个写入方"是跨模块状态管理的基本纪律；本次清理完成后 Launcher 对该状态的感知只能走回调/广播，契约更清晰。
- 风险点：删除写入后需确认 Launcher 内没有遗留读取旧键的代码（本提交未显示读取方），否则将永远读到默认值。
