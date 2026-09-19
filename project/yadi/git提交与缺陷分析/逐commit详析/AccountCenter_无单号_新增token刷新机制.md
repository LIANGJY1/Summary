# 无单号 · 新增token刷新机制

- **提交**：`cab71b0f` | 2026-07-10 | hedeyuan | AccountCenter | feature（cherry-pick 自 01e56e85）
- **关联单**：无

## 需求/目标
为账号中心建立后台 Token 续期机制：开机自启前台服务，监听网络（WiFi）可用时机用 `refreshToken` 静默换取新 token 并落盘，保证车机长期运行下账号凭证不过期。

## 实现结构
改动 10 个文件（+249/-146），核心三件套：
- 新增 `service/BootReceiver.kt`：监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，`startForegroundService` 拉起服务。
- 新增 `service/BootService.kt`（179 行）：`onCreate` 启动低优先级常驻通知（`foregroundServiceType="connectedDevice"`）+ `registerDefaultNetworkCallback`；WiFi 可用或能力变化时触发 `refreshTokenOnWifi()`。
- `common/Constants.kt → Commons.kt` 重命名（避免与 `com.yadea.common.Constants` 冲突），SN/VIN 设备标识常量收口其中，`RetrofitManager`/`ApiService` 同步改引用。

删除项：废弃的 `QrCodeLoginActivity` 及其 layout（79 行，扫码登录被手机号登录取代）。

数据流：开机 → BootService → 网络回调(WiFi) → 读缓存 `REFRESH_TOKEN` → 60 秒节流 → `SignUtil.generateSign(sn,vin,timestamp)` → `POST API_REFRESH_TOKEN` → 成功后 `ACCESS_TOKEN/REFRESH_TOKEN` 写回 SettingsUtils。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/service/BootService.kt（新增，节选）
+            override fun onAvailable(network: Network) {
+                super.onAvailable(network)
+                val capabilities = connectivityManager.getNetworkCapabilities(network)
+                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
+                    LogUtils.d(TAG, "WiFi network available, attempting to refresh token")
+                    refreshTokenOnWifi()
+                }
+            }
@@
+        // 避免短时间内重复刷新（60秒内只允许一次）
+        val now = System.currentTimeMillis()
+        if (now - lastRefreshTime < MIN_REFRESH_INTERVAL) {
+            LogUtils.d(TAG, "Refresh too frequent, skip. Last refresh: ${now - lastRefreshTime}ms ago")
+            return
+        }
+        lastRefreshTime = now
```
实现讲解：三个工程要点——前台服务保活满足 Android 12+ `connectedDevice` 类型约束；`NetworkCallback` 只挑 WiFi 触发（车机流量金贵，且 WiFi 才代表"在线且空闲"）；`lastRefreshTime` 时间戳节流兜住 `onCapabilitiesChanged` 高频回调导致的重复请求。签名采用 `sn+vin+timestamp` 经 `SignUtil` 生成，带时间戳防重放。

## 复盘与要点
- "开机前台服务 + 网络回调触发 + 节流"是车机端凭证续期的标准骨架，可直接移植到其他需要长连接凭证的应用。
- 遗留风险明确且作者已自知：`Commons.SN/VIN` 是写死的测试值（"898915121312356"/"test260526a"，注释标注"后续对接正式获取方式"），上线前必须接真实 VIN 读取，否则签名校验会被服务端拒绝。
- 小瑕疵：`refreshTokenOnWifi` 在网络回调线程直接发起 Retrofit 订阅 OK，但 `lastRefreshTime` 无同步保护；另外 `onCapabilitiesChanged` 每次带宽变化都触发（仅靠 60s 节流压制），可改为只在 `VALIDATED` 能力时刷新。
