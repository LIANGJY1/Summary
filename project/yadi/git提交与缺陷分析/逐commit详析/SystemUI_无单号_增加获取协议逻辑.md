# 无单号 · 增加获取协议逻辑（SystemUI 侧）

- **提交**：`319843b1` | 2026-07-17 | dufan | SystemUI | feature
- **关联单**：无（与 Setting 侧 `fb2950c8` 成对提交）

## 需求/目标
让隐私协议、用户服务协议的展示地址不再写死本地 HTML，而是车机联网后从云端网关拉取最新协议 URL 并写入全局配置，供协议页面加载。

## 实现结构
4 个文件：
- `util/HttpTimeClient.kt` 重命名为 `util/HttpClient.kt`（+91 行改动）：在原"多时间源校时"客户端基础上新增 `getAgreementFromHttpApi()`，复用已有的 OkHttp 单例（5s 超时）、`testNetworkConnectivity()`、`fetchDataFromApi()` 骨架；
- `cmdcontroller/systemsetting/SystemSettingsControllerService.java` 整体改写为同名 `.kt`（935 行 Java → 1005 行 Kotlin）：在其 `mNetworkChangeReceiver` 监听 `CONNECTIVITY_CHANGE`、网络可用时在 IO 协程里调用 `HttpClient.getAgreementFromHttpApi()`；
- `HUDBrightnessTile.kt`：两处可空观察值补 `!!`（Kotlin 迁移的编译适配）。

数据流：网络恢复 → 广播接收器 → 协程拉取网关接口（`agreement/api/v1/versions/latest?categoryCode=...`）→ 解析 `data.contentUrl` → `SettingsUtils.setGSetting` 写入 `personal_infomation_agreement` / `user_service_agreement` 两个 key。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/util/HttpClient.kt（新文件，由 HttpTimeClient.kt 改名扩展）
+    suspend fun getAgreementFromHttpApi() {
+        if (!testNetworkConnectivity()) { ... return }
+        val agreementApis = listOf(
+            "https://vehicle-gateway-dev.galaxymoto.com.cn/agreement/api/v1/versions/latest?categoryCode=AGREEMENT_PRIVACY_2026",
+            "https://vehicle-gateway-dev.galaxymoto.com.cn/agreement/api/v1/versions/latest?categoryCode=yinsi2",
+        )
+        for (api in agreementApis) {
+            ...
+                    if (result.success) {
+                        val jsonObject = JSONObject(result.content)
+                        if (jsonObject.getString("statusCode") == "0") {
+                            val data = jsonObject.getJSONObject("data")
+                            val contentUrl = data.getString("contentUrl")
+                            SettingsUtils.setGSetting(if (api.contains("AGREEMENT_PRIVACY")) "personal_infomation_agreement" else "user_service_agreement", contentUrl)
+                        }
+                        return@repeat
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt（Java→Kotlin 改写后新增）
     private val mNetworkChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
         override fun onReceive(context: Context, intent: Intent) {
             if ("android.net.conn.CONNECTIVITY_CHANGE" == intent.getAction()) {
                 val networkInfo = cm.getActiveNetworkInfo()
                 if (networkInfo != null && networkInfo.isConnected()) {
                     CoroutineScope(Dispatchers.IO).launch {
                         HttpClient.getAgreementFromHttpApi()
                     }
                 }
             }
         }
     }
```
实现讲解：借一次 Java→Kotlin 迁移顺带挂载新能力，复用校时客户端的网络探测与重试骨架，改动集中在两个类。以 `api.contains("AGREEMENT_PRIVACY")` 区分两类协议并落盘到 GSetting，实现"云端 URL 覆盖本地兜底"。

## 复盘与要点
- "网络恢复即拉取"是车机上典型的弱网补偿模式：协议 URL 不需要实时性，只在联网时机更新一次即可。
- 可复用手法的反面教材：`if (api.contains("AGREEMENT_PRIVACY")) ... else ...` 用 URL 子串决定存储 key，接口调整或 categoryCode 改名会静默写错 key，映射关系应显式成对配置。
- 同一批域名是 dev 环境（`vehicle-gateway-dev`），量产前需随环境切换，属配置外置缺失的风险点。
