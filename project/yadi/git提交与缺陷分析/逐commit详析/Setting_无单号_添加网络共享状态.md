# [SRS_BT_LinkSetting_012] · 添加网络共享状态（Setting 侧写入）

- **提交**：`c1236da7` | 2026-08-10 | dufan | Setting | feature
- **关联单**：SRS_BT_LinkSetting_012

## 需求/目标
为"设备网络共享"新增全局可读的开关状态：Setting 侧用户改变共享网络开关时，同步写入 `DEVICE_SHARE_NETWORK_IS_OPEN` 全局设置，供 Launcher/SystemUI 等跨进程消费。

## 实现结构
- `application/Setting/.../Constants.java`：新增键常量 `DEVICE_SHARE_NETWORK_IS_OPEN`
- `application/Setting/.../init/DeviceConnectManager.kt`：`changeShareNetworkState(state)` 中把 state 写入该全局设置（并加日志）

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -897,6 +898,8 @@
     fun changeShareNetworkState(state: Int) {
+        log("changeShareNetworkState:$state")
+        SettingsUtils.setGSetting(DEVICE_SHARE_NETWORK_IS_OPEN, state)
         SCurrentThirdDevice?.apply {
             val sharedNetworkState = loadMapFromSettings()
```
实现讲解：写入点放在既有 `changeShareNetworkState` 入口最前，与原"按设备 MAC 的 JSON 映射存储（DEVICE_SHARE_NETWORK_STATE）"并存——新键是"当前总开关"快照，旧键是"每设备明细"，读取方按需选择。

## 复盘与要点
- "总开关快照键 + 明细 JSON 键"双层存储便于读取方免解析 JSON，但两份数据需保证同步更新，存在一致性维护成本。
- 与同日 Launcher `ea4e972f` 成对：Setting 写、Launcher 读，契约是字符串常量名，建议后续收敛到公共 Constants。
