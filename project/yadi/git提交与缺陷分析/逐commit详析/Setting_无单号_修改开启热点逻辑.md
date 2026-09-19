# [SRS_WIFI_LinkSetting_003] · 修改开启热点逻辑（投屏专用热点：固定 SSID/5G 信道/静态 IP）

- **提交**：`f293c432` | 2026-08-06 | dufan | Setting | feature
- **关联单**：SRS_WIFI_LinkSetting_003

## 需求/目标
开启热点由原"硬件 AP 管理器 openAp(自定义配置)"改为"投屏专用热点"：固定 SSID `Yadea`、WPA2 密码、5GHz 信道 149、静态 IPv4 地址对，以满足手机互联（投屏）对频段与网段的固定要求。

## 实现结构
单文件 `application/Setting/.../diologfragment/HotspotDialogFragment.kt`（+85/-1）：
- 开热点入口从 `mWxApManagerI.openAp(createHotspotConfig())` 换成 `openProjectionHotspot()`
- `openProjectionHotspot()`：先 `closeProjectionHotspot()`（closeAp + stopTethering），延迟 800ms 后设配置再启动
- `setProjectionSoftApConfig()`：`WifiManager.setSoftApConfiguration(SoftApConfiguration.Builder().setSsid/setPassphrase(WPA2)/setChannel(149, BAND_5GHZ))`
- `startWifiTetheringWithStaticIp()`：`TetheringManager.startTethering(TetheringRequest + setStaticIpv4Addresses(local, client))`，回调经主线程 Executor
- `createLinkAddress()`：反射调用 `LinkAddress(String)` 公开构造

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
@@ -397,10 +405,86 @@
+    private fun startWifiTetheringWithStaticIp() {
+        val localAddress = createLinkAddress(AP_LOCAL_ADDR)
+        val clientAddress = createLinkAddress(AP_CLIENT_ADDR)
+        val request = TetheringManager.TetheringRequest.Builder(
+            TetheringManager.TETHERING_WIFI
+        )
+            .setShouldShowEntitlementUi(false)
+            .setStaticIpv4Addresses(localAddress, clientAddress)
+            .build()
+        mTetheringManager?.startTethering(request, Executor { ThreadUtils.runOnUiThread(it) },
+            object : TetheringManager.StartTetheringCallback { ... })
+    }
+
+    private fun createLinkAddress(address: String): LinkAddress {
+        val constructor = LinkAddress::class.java.getDeclaredConstructor(String::class.java)
+        constructor.isAccessible = true
+        return constructor.newInstance(address)
+    }
+
+        private const val SSID = "Yadea"
+        private const val PASSWORD = "12345678"
+        private const val CHANNEL = 149
+        private const val AP_LOCAL_ADDR = "192.168.111.236/24"
+        private const val AP_CLIENT_ADDR = "192.168.111.2/24"
```
实现讲解：关键点有三：一是从硬件层 AP 切到框架层 `WifiManager+TetheringManager` 双 API 组合；二是 `setStaticIpv4Addresses` 需要的 `LinkAddress` 在目标 SDK 上无公开构造，用反射 `getDeclaredConstructor(String)` 解决；三是"先关再延迟 800ms 开"规避 AP 状态切换竞态。

## 复盘与要点
- 车机投屏热点常见诉求"固定 5G 信道 + 固定网段"（CarPlay/HiCar 发现协议依赖），本提交给出可直接复用的 SoftApConfiguration + 静态 IP Tethering 模板。
- 反射 `LinkAddress` 构造依赖平台实现细节，跨 Android 版本升级需回归；SSID/密码/网段硬编码在 companion，应下沉到配置或资源。
- 原硬件 AP 路径 `openAp(createHotspotConfig())` 被注释保留而非删除，用户自定热点与投屏热点的产品切换关系待明确。
