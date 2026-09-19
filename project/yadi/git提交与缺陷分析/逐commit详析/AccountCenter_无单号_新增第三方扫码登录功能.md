# 无单号 · 第三方扫码登录由演示桩改为真实接口闭环

- **提交**：`9f613def` | 2026-07-22 | hedeyuan | AccountCenter | bugfix（实为功能补全）
- **缺陷库**：未关联单号

> 类型说明：提交标题为"新增第三方扫码登录功能"，虽挂 bugfix 前缀，但 diff 实质是把扫码登录从演示桩（写死 URL + 模拟失败）补全为真实接口调用闭环，属功能完成类改动，此处按 diff 实际内容剖析。

## 问题
扫码登录弹窗内二维码内容写死为 `http://www.baidu.com`，且 5 秒后无条件模拟"加载失败"，真实手机扫码后车机端不会登录，也无登录状态轮询。

## 根因分析
改动前 `LoginDialogActivity.showLoginDialog()` 中二维码内容是硬编码的 `val qrContent = "http://www.baidu.com"`，并用 `Handler.postDelayed { showLoadFailState() }` 模拟失败——整条链路没有网络请求。同时该 Activity 没有接入 `LoginViewModel`，`loginLiveData/loginStateLiveData/refreshTokenLiveData/lastAgreementLiveData` 均未观察，扫码成功后的 token 落库、页面跳转逻辑不存在。此外 `CenterActivity` 读 `IS_OPEN_HUD/IS_OPEN_SEAT` 配置时默认值传 `"0"`，首次使用用户开关显示为关，与产品预期默认开不符。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginDialogActivity.kt`、`application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt`
```diff
// application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginDialogActivity.kt（新增网络请求与轮询）
+    fun requestLoginState() {
+        timestamp = System.currentTimeMillis()
+        deviceSign = SignUtil.generateSign(mapOf("sn" to sn, "vin" to vin, "timestamp" to timestamp.toString()))
+        val request = LoginStateRequest(sn, vin, timestamp, deviceSign)
+        viewModel?.checkQrCodeStatus(request)
+    }
```
```diff
// 同文件：真实二维码替换演示内容
-            // 要生成的内容（可替换为任意字符串）
-            val qrContent = "http://www.baidu.com"
-            // 模拟加载失败
-            Handler(mainLooper).postDelayed({
-                showLoadFailState()
-            }, 5000)
+            try {
+                val qrBitmap = QRCodeGenerator.generateQRCode(qrCodeString, qrSize)
+                setQrCodeImage(qrBitmap)
+            } catch (e: WriterException) { ... }
+            qrCodeLoginDialog!!.showNormalState()
+            startRefreshTimer()
+            loginStateRefreshTimer()
```
```diff
// application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
-        binding?.switchHud?.isChecked = ShareConfigUtils.getConfig(Constants.CACHE.IS_OPEN_HUD, "0", userId ?: 0L) == "1"
-        binding?.switchSeat?.isChecked = ShareConfigUtils.getConfig(Constants.CACHE.IS_OPEN_SEAT, "0", userId ?: 0L) == "1"
+        binding?.switchHud?.isChecked = ShareConfigUtils.getConfig(Constants.CACHE.IS_OPEN_HUD, "1", userId ?: 0L) == "1"
+        binding?.switchSeat?.isChecked = ShareConfigUtils.getConfig(Constants.CACHE.IS_OPEN_SEAT, "1", userId ?: 0L) == "1"
```

## 为什么能修复
新增 `requstLoginQRCode()` 真实请求二维码（带 `SignUtil.generateSign` 签名、VIN 校验、网络可用性检查），`loginStaterefreshRunnable` 每秒 `checkQrCodeStatus` 轮询；`handleLoginStateResponse` 收到 token 后写入 `SettingsUtils`（ACCESS_TOKEN/REFRESH_TOKEN）并跳转 `CenterActivity`，登录闭环成立。90 秒 `startRefreshTimer` 负责二维码过期刷新，`onPause/onDestroy` 统一停表防泄漏。隐患：`handleLoginStateResponse` 对成功响应一律 `showMsgToast("登录成功")` 并跳转，未校验 `data` 内是否真的含 token（`response.data ?: return` 之前的 toast 顺序依赖 data 非空，尚可）；但 `onPause` 里直接 `finish()` 的做法把"查看协议跳 WebView"场景也当作退出处理，配合 `isPausedByAgreement` 标记（定义了但此处未见消费）存在误关风险。

## 复盘与经验
- 演示桩代码（写死 URL、模拟失败）合入主干后必须显式跟踪替换，否则会以"功能存在但永远不工作"的形态潜伏到联调阶段。
- 轮询型登录（扫码确认）需要三类定时器配合：二维码有效期刷新、状态轮询、失败态展示；每个定时器都要在 onPause/onDestroy 成对摘除，否则 Activity 销毁后回调仍触 UI。
- 读配置的默认值（如开关默认 "0"/"1"）是隐性产品需求，改动一行即改变所有新用户的初始状态，评审时应与产品确认。
