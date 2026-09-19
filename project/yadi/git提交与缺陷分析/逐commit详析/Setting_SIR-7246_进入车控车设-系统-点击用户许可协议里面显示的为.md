# SIR-7246 · 【偶发】用户许可协议页面显示的是代码
- **提交**：`4c636710` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **注**：缺陷库根因写的是"未使用颜色token/使用颜色token"，与 diff 实际内容（协议 URL 过期、超时重下载）不符，本文以 diff 实际为准；提交说明亦证实为"协议url过期→超时重新下载"。

## 问题
进入车控车设-系统-点击用户许可协议，偶发情况下 WebView 页面显示的不是协议正文，而是源代码/异常内容。

## 根因分析
协议正文由 SystemUI 的 `HttpClient` 从服务端接口拉取，返回的 `contentUrl` 通过 `SettingsUtils.setGSetting` 持久化到 Global Settings（键 `personal_infomation_agreement` / `user_service_agreement`），Setting 侧 `WebFragment` 再读取该 URL 加载。问题在于这个 URL 是有时效的（接口告知不超过 3 天），但原逻辑只在下载时写一次、永不刷新——车机长期不联网或跨天使用后 URL 过期，WebView 加载到的内容错误，表现为"显示的是代码"。此外整个链路没有任何下载时间戳记录，Setting 侧无从判断 URL 是否还有效。跨应用数据（SystemUI 下载、Setting 消费）缺乏有效期协同是根因核心。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt`、`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt`、`application/SystemUI/src/main/java/com/android/systemui/util/HttpClient.kt`、`component/CommonTools/src/main/java/com/yadea/common/Constants.kt`

```diff
--- application/SystemUI/src/main/java/com/android/systemui/util/HttpClient.kt
                             val contentUrl = data.getString("contentUrl")
-                            SettingsUtils.setGSetting(if (api.contains("AGREEMENT_PRIVACY")) "personal_infomation_agreement" else "user_service_agreement", contentUrl)
+                            val isPrivacy = api.contains("AGREEMENT_PRIVACY")
+                            SettingsUtils.setGSetting(if (isPrivacy) "personal_infomation_agreement" else "user_service_agreement", contentUrl)
+                            // 记录协议下载时间（有效期3天）
+                            SettingsUtils.setGSetting(
+                                if (isPrivacy) "personal_infomation_agreement_time" else "user_service_agreement_time",
+                                System.currentTimeMillis()
+                            )
```

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
+        /** 协议 URL 有效期（接口告知不超过 3 天） */
+        private const val AGREEMENT_URL_EXPIRE_TIME = 3 * 24 * 60 * 60 * 1000L
+
+    /**
+     * 协议URL有效期不能超过3天，超过重新下载
+     */
+    private fun checkAndDownloadAgreementUrl() {
+        val now = System.currentTimeMillis()
+        val privacyLastTime = SettingsUtils.getGSettingLong(KEY_PRIVACY_AGREEMENT_TIME)
+        val userLastTime = SettingsUtils.getGSettingLong(KEY_USER_AGREEMENT_TIME)
+        val privacyExpired = privacyLastTime == 0L || now - privacyLastTime >= AGREEMENT_URL_EXPIRE_TIME
+        val userExpired = userLastTime == 0L || now - userLastTime >= AGREEMENT_URL_EXPIRE_TIME
+        if (privacyExpired || userExpired) {
+            requireActivity().sendBroadcast(Intent(com.yadea.common.Constants.Action.ACTION_DOWN_URL))
+        }
+    }
```

```diff
--- application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
+    private val broadcastReceiver = object : BroadcastReceiver() {
+        override fun onReceive(context: Context, intent: Intent) {
+            when (intent.action) {
+                com.yadea.common.Constants.Action.ACTION_DOWN_URL -> {
+                    CoroutineScope(Dispatchers.IO).launch {
+                        HttpClient.getAgreementFromHttpApi()
+                    }
+                }
+            }
+        }
+    }
```

（`Constants.kt` 新增 `Action.ACTION_DOWN_URL = "com.yadea.action.DOWN_URL"`；`SystemFragment.lazyLoadData()` 与 `onHiddenChanged(false)` 两处触发检查；`WebFragment` 增加 html 内容日志便于定位。）

## 为什么能修复
下载成功时在 `HttpClient` 记录时间戳，Setting 侧每次进入系统页/页面可见时按 3 天有效期校验，过期即发 `ACTION_DOWN_URL` 广播，SystemUI 的 `SystemSettingsControllerService` 收到后在 IO 线程重新拉取协议 URL 并刷新 Global Settings——形成"过期感知→触发重下→覆盖新 URL"的闭环，WebView 不再加载过期 URL。隐患：广播是异步刷新，极端情况下用户点进协议时新 URL 尚未下载完成，本次仍读到旧值，需等下次进入；时间戳与 URL 分两个键存储，理论上存在半更新窗口。

## 复盘与经验
- 带时效的云端下发的资源（URL、token、签名串）持久化时必须同时持久化获取时间，消费方按有效期校验并具备主动刷新通道，"一次下载永久使用"是偶发疑难杂症的常见源头。
- 跨应用共享数据（SystemUI 写 Global Settings、Setting 读）要设计失效与再同步机制，广播是车机内轻量可行的跨进程触发手段，但要考虑异步时序。
- 缺陷库元数据（本单"颜色token"）与实际修复偏差较大时，复盘应以 diff 和提交说明为准，同时暴露了缺陷单填写/关联流程的准确性问题。
