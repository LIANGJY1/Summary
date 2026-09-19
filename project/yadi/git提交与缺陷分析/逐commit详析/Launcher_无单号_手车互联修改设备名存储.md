# 无单号 · 手车互联修改设备名存储（SP 迁移至 Global Settings）

- **提交**：`92a3fb02` | 2026-07-22 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
把当前互联设备名 `productName` 的存储从 Launcher 私有 SharedPreferences（`SPUtils`）迁移到跨进程可读的 Global Settings（`SettingsUtils.setGSetting`，新键 `CONNECT_DEVICE_NAME`），使设备名在其他进程/模块也能读取。

## 实现结构
3 个文件（+10/-7）：
- `launcher/Constants.java`：新增 `CONNECT_DEVICE_NAME` 常量；
- `launcher/control/DeviceConnectManager.kt`：CarLink 获取设备名 JSON、CarPlay/HiCar/CarLink 连接状态处理共 4 处 `SPUtils.setParam("productName", ...)` 全部替换为 `SettingsUtils.setGSetting(CONNECT_DEVICE_NAME, ...)`；
- `function/applist/AppListActivity.kt`：读取处 `SPUtils.getParam("productName","")` 替换为 `SettingsUtils.getGSetting(CONNECT_DEVICE_NAME)`。

数据流：互联 SDK 回调设备名 → DeviceConnectManager 写 Global Settings → AppListActivity/其他模块按需读取。存储语义不变，仅更换存储介质。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
                 try {
                     val jsonObject = JSONObject(s)
                     mProductName = jsonObject.getString("productName")
-                    SPUtils.setParam("productName", mProductName)
+                    SettingsUtils.setGSetting(CONNECT_DEVICE_NAME, mProductName)
                     updateCarLinkAppList()
```
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
             2, 3 -> updateCarConnectStatus(
                 true,
-                SPUtils.getParam("productName", "") as String
+                SettingsUtils.getGSetting(CONNECT_DEVICE_NAME)
             )
```
实现讲解：车机上 Settings.Global 相当于系统级共享 KV 表，跨应用可见；私有 SP 只有 Launcher 自己能读。迁移让"当前连接的设备名"成为系统级状态，与前面共享开关状态的存储选型保持一致。

## 复盘与要点
- 选型口径：模块私有数据用 SP，需要跨进程共享的车机状态统一走 GSetting；本次迁移是该口径落地的又一例（与 `13ba9d1d`、`fb2950c8` 同一套 `SettingsUtils`）。
- 遗留风险：老用户 SP 里已有的 productName 不会自动迁移，切换后首次读取为空，UI 会短暂丢失设备名；如需无缝应做一次启动搬运。
- HiCar 分支固定写 `"HUAWEI HiCar"`、CarLink 分支在断开时把空串写入——读方需容忍空值，`getGSetting` 默认值处理很关键。
