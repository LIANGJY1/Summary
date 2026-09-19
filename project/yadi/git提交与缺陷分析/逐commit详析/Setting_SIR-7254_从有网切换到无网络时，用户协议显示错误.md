# SIR-7254 · 从有网切到无网时用户协议页面显示错误
- **提交**：`d555ef72` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：判断网络有无连接逻辑有问题）

## 问题
从有网切换到无网络（尤其是直接关闭 WiFi 开关）后打开用户协议 WebView 页，页面加载错误内容而不是本地兜底协议。

## 根因分析
`WebFragment` 的加载分支依赖 `isNetworkAvailable()`：URL 是 http(s) 且"有网"就走 `webView.loadUrl(url)`，否则回落 `PAGE_LOCAL_FILE` 本地协议。原判断只用 `ConnectivityManager.activeNetwork + NetworkCapabilities` 检测，车机场景下 WiFi 开关被关闭后的短暂时间窗内，ConnectivityManager 仍可能返回残留的 activeNetwork（状态未及时收敛），导致判定"有网"、去加载在线 URL，结果 WebView 显示加载失败页而非本地协议——"判断有无网络"与真实可达性脱节。修复在 `isNetworkAvailable()` 前置一道 WiFi 开关状态检查：`WifiManager.wifiState != WIFI_STATE_ENABLED` 时直接返回 false（即"WiFi 开关关闭也是无网络"），并顺带把判定结果提到分支前计算、加了调试日志。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt
@@ -51,12 +52,16 @@
             val url = it.getString("PAGE_URL") ?: ""
+            val haveNet = isNetworkAvailable()
             if (!url.startsWith("http")) {
                 loadLocalHtml(url)
-            } else if (isNetworkAvailable()) {
+            } else if (haveNet) {
                 webView.loadUrl(url)
             } else {
                 val localFile = it.getString("PAGE_LOCAL_FILE").orEmpty()
                 if (localFile.isNotEmpty()) {
                     loadLocalHtml(localFile)
                 }
@@ -186,10 +191,19 @@
     private fun isNetworkAvailable(): Boolean {
-        val cm = requireContext()
-            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
+        val context = requireContext()
+        // WiFi 开关已关闭时直接判定无网
+        val wifiManager = context.applicationContext
+            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
+        if (wifiManager != null && wifiManager.wifiState != WifiManager.WIFI_STATE_ENABLED) {
+            Log.i(TAG, "wifi disabled")
+            return false
+        }
+        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
             ?: return false
         val network = cm.activeNetwork ?: return false
         val capabilities = cm.getNetworkCapabilities(network) ?: return false
```

## 为什么能修复
引入"开关态"这一更强的先验信号：WiFi 开关关闭时无需等待 ConnectivityManager 状态收敛，立即走本地协议分支，消除切换瞬间的误判窗口。隐患：本判断对车机其他上网通路（如 4G/以太网）不友好——WiFi 关着但蜂窝可用时也会被误判为无网，走本地兜底；在当前车机以 WiFi 为主的网络形态下可接受，但属于有偏的判定。另外 WebView 只在进入页面时判定一次（haveNet 提前计算），页面停留期间网络变化不会重新分流，需要外层广播触发重建。

## 复盘经验
- "网络是否可用"有两层：开关/连接状态与真实可达性；UI 分流时优先用开关态等确定性信号， ConnectivityManager 判定存在状态滞后。
- 判定函数要注明覆盖的网络形态（仅 WiFi？含蜂窝？），否则换网络形态就翻车。
- 网络切换是异步过程，WebView 类页面最好监听网络广播重判，而不是只在进入时判定一次。
