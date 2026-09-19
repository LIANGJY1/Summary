# 无单号 [SRS_WIFI_LinkSetting_004] 热点已连接设备黑名单
- **提交**：`d002b4e7` | 2026-08-24 | sgh | Setting | feature
- **关联单**：SRS_WIFI_LinkSetting_004

## 需求/目标
热点管理弹窗支持"点击已连接设备 → 确认后断开并临时拉黑"，防止目标设备立刻重连；另附带"热点开启 10 分钟无连接自动关闭"的功耗策略与 WLAN 弹窗头部设备名隐藏两个小改动。

## 实现结构
- `HotspotDialogFragment.kt`（+137 行，核心）：
  - 缓存 `mConnectedClients` 列表；列表 item 点击 → `showDisconnectDialog`（TextDialog 二次确认）→ `disconnectClient`。
  - 断开实现：因 `SoftApConfiguration.Builder` 为隐藏 API，用反射构造新配置把目标 MAC 加入 `blockedClientList` 后 `setSoftApConfiguration` 下发（依赖 SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT 特性即时踢除）。
  - 三段式时序：3 秒后复查是否仍在线 → 仍在线则回退为"关热点+重开"方案；成功后 5 秒延迟 `clearBlockedClient` 把 MAC 移出黑名单（允许后续重连）。
  - `scheduleAutoCloseIfIdle`：热点开启时 10 分钟无客户端自动 `closeProjectionHotspot`，有连接即 cancel。
- `WlanDialogFragment.kt`：WLAN 弹窗头部隐藏"设备名称"行；`item_connect_child_header.xml` 补 tv_device_name_label id。
- 数据流：TetheringEventCallback → handleConnectedClients（缓存+渲染）→ 用户点击 → 反射改 softAp 系统配置 → 回调刷新。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
+                //先把目标 MAC 加入黑名单，防止它立刻重连
+                val blocked = ArrayList(current.blockedClientList)
+                if (!blocked.contains(mac)) blocked.add(mac)
+                builderClass.getMethod("setBlockedClientList", List::class.java).invoke(builder, blocked)
+                val newConfig = builderClass.getMethod("build").invoke(builder) as SoftApConfiguration
+                val ret = wifiManager.setSoftApConfiguration(newConfig)
+                // 等待系统强制断开；若目标设备仍在线，则回退为重启热点
+                delay(3000)
+                val stillConnected = mConnectedClients.any {
+                    it.macAddress.toString().equals(item.address, ignoreCase = true)
+                }
```
"踢除 = 拉黑 + 等待生效 + 复查 + 回退重启 + 延迟解黑"是一条完整的工程化防御链：每一层失败都有可观测日志（ret、blocked 列表），回退路径保证最差情况也能断开。

## 复盘与要点
- 对隐藏 API（SoftApConfiguration$Builder）的反射封装集中在 disconnectClient/clearBlockedClient 两个函数内，并注释了依赖的系统特性，便于 API 变更时定位——反射调系统 API 的模板写法。
- "临时黑名单 + 定时解除"巧妙规避了永久黑名单的管理 UI 需求，把"断开某设备"降维成一次性动作，产品与实现双赢。
- 风险：`mConnectedClients` 是弹窗内缓存，3 秒复查依赖 tethering 回调及时刷新；若回调滞后会误判"已断开"而跳过重启回退，且 CLEAR_BLOCK 期间用户重连会被静默拒绝。
