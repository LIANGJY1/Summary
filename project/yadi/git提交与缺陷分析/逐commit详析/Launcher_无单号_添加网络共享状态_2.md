# [SRS_BT_LinkSetting_012] · 添加网络共享状态（Launcher 侧联动）

- **提交**：`ea4e972f` | 2026-08-10 | dufan | Launcher | feature
- **关联单**：SRS_BT_LinkSetting_012

## 需求/目标
Launcher 侧消费/维护网络共享状态：互联设备连接时按设备 MAC 恢复其共享开关状态，断连或 CarPlay 状态变化时清零，保证全局 `DEVICE_SHARE_NETWORK_IS_OPEN` 与实际连接情况一致。

## 实现结构
单文件 `application/Launcher/.../control/DeviceConnectManager.kt`（+49/-1）：
- `setDeviceConnectStatus()` 三个分支补状态维护：CarPlay 更新时置 0；HiCar/CarLink 连接时遍历设备列表找 CONNECTED 设备，用 `getShareNetworkState(btAddr/deviceId)` 回填该设备明细中的状态；断开时置 0
- 新增 `getShareNetworkState(mac)`：从 `DEVICE_SHARE_NETWORK_STATE` JSON 明细中按 MAC 取值，缺省 2（默认关）
- 新增 `loadMapFromSettings()`：解析 JSON 明细为 `LinkedHashMap<mac, state>`，空/异常返回空表
- companion 中补两个键常量（与 Setting 侧 `c1236da7` 字面量一致）

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
@@ -880,9 +881,19 @@
                     mCurrentConnectType = 2
+                    mHiCarDeviceListManager?.apply {
+                        hiCarDeviceList.forEach {
+                            if (it.deviceStatus == HiCarConstants.DeviceStatus.CONNECTED) {
+                                SettingsUtils.setGSetting(DEVICE_SHARE_NETWORK_IS_OPEN, getShareNetworkState(it.btAddr))
+                                return@forEach
+                            }
+                        }
+                    }
                 } else {
                     mProductName = ""
                     mCurrentConnectType = 0
+                    SettingsUtils.setGSetting(DEVICE_SHARE_NETWORK_IS_OPEN, 0)
                 }
@@ -1157,11 +1178,38 @@
+    fun getShareNetworkState(mac:String): Int {
+        val sharedNetworkState = loadMapFromSettings()
+        return sharedNetworkState[mac] ?: 2
+    }
+
+    fun loadMapFromSettings(): MutableMap<String, Int> {
+        val map: MutableMap<String, Int> = LinkedHashMap()
+        val jsonStr = SettingsUtils.getGSetting(DEVICE_SHARE_NETWORK_STATE)
+        if (TextUtils.isEmpty(jsonStr)) {
+            return map
+        }
+        try { ... JSONObject 解析 ... } catch (e: JSONException) { e.printStackTrace() }
+        return map
+    }
```
实现讲解：把"互联连接状态变化"作为网络共享开关的联动触发源：连接→从按设备明细恢复总开关，断开/切到 CarPlay→清零。读取容错（空串、坏 JSON 返回空 map）保证解析失败不影响连接主流程。

## 复盘与要点
- Launcher 侧复刻了 Setting 侧的 `loadMapFromSettings` 解析逻辑（两份同名实现），JSON 明细格式变更时需两处同步改，应抽到公共组件。
- 缺省值 `2`（约定为"关"）是隐式契约，建议常量化并注明与 Setting 侧写入值的对应关系。
- "连接即恢复共享状态"的策略依赖明细数据先于连接存在（用户需先在 Setting 打开过该设备共享），首次连接设备默认走 2，符合预期但需产品确认。
