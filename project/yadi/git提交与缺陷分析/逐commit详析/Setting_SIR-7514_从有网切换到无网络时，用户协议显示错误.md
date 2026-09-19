# SIR-7514 · 从有网切换到无网络时，用户协议显示错误
- **提交**：`00983d7e` | 2026-09-07 | sgh | Setting | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（注：缺陷库 rc 沿用了同单号另一提交的"按钮选中xml冲突"定性，与本次 diff 不符，以 diff 实际为准：无网络时未加载本地协议文件）

## 问题
"系统-用户协议"此前配置为远程 URL，从有网环境切到无网络后打开用户协议，WebView 显示错误（空白/加载失败）。

## 根因分析
`WebFragment`（协议弹窗容器）原加载逻辑只有两分支：`if (url.startsWith("http")) webView.loadUrl(url) else loadLocalHtml(url)`——URL 是 http 就直接联网加载，完全没有网络可用性判断，也没有远程地址到本地文件的降级路径。断网后 `loadUrl` 加载远程协议页自然失败。`SystemFragment` 侧原本只有 `PAGE_URL`（当 `SettingsUtils.getGSetting("user_service_agreement")` 为空时才给本地 `user_service_agreement.html`），一旦后台下发了远程 URL，本地兜底就彻底退场。修复形成三级降级链：① URL 非 http → 直接 `loadLocalHtml(url)`（本地 asset）；② URL 是 http 且 `isNetworkAvailable()`（新增方法，经 `ConnectivityManager.activeNetwork` + `NetworkCapabilities.NET_CAPABILITY_INTERNET` 判定）→ 正常 `loadUrl`；③ 有 http URL 但断网 → 取新增参数 `PAGE_LOCAL_FILE`，非空则 `loadLocalHtml(localFile)` 回落到本地协议。`SystemFragment` 打开弹窗时固定补传 `bundle.putString("PAGE_LOCAL_FILE", "user_service_agreement.html")`（该文件真实存在于 `application/Setting/src/main/assets/`）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt
```diff
--- application/Setting/.../diologfragment/WebFragment.kt
             val url = it.getString("PAGE_URL") ?: ""
-            if (url.startsWith("http")) {
-                webView.loadUrl(url)
+            if (!url.startsWith("http")) {
+                loadLocalHtml(url)
+            } else if (isNetworkAvailable()) {
+                webView.loadUrl(url)
             } else {
-                loadLocalHtml(url)
+                val localFile = it.getString("PAGE_LOCAL_FILE").orEmpty()
+                if (localFile.isNotEmpty()) {
+                    loadLocalHtml(localFile)
+                }
             }
+
+    /** 判断当前网络是否可用 */
+    private fun isNetworkAvailable(): Boolean {
+        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
+            ?: return false
+        val network = cm.activeNetwork ?: return false
+        val capabilities = cm.getNetworkCapabilities(network) ?: return false
+        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
+    }
--- application/Setting/.../fragment/SystemFragment.kt
             bundle.putString("PAGE_URL",if (TextUtils.isEmpty(user)) "user_service_agreement.html" else user)
+            bundle.putString("PAGE_LOCAL_FILE", "user_service_agreement.html")
```

## 为什么能修复
断网场景不再硬吃远程加载失败：`isNetworkAvailable()` 先行探测，失败即回落 assets 内置的 `user_service_agreement.html`，协议内容始终可读；本地 asset 版本若与云端最新版存在时差，展示的是最近一次打包的协议，属可接受的降级语义。隐患：`NET_CAPABILITY_INTERNET` 只代表网络声明具备联网能力，弱网/ captive portal 下仍可能 `loadUrl` 失败且无二次降级；`PAGE_LOCAL_FILE` 为空且断网时弹窗内容为空白，调用方需保证传值。

## 复盘与经验
- 依赖远程内容的入口必须内置"网络探测 → 本地资产降级"链路，车机场景网络状态多变（地库、行驶切换），本地兜底不是可选项而是必需品。
- `ConnectivityManager` + `NetworkCapabilities.NET_CAPABILITY_INTERNET` 是当前判断"真可上网"的推荐组合，比旧的 `activeNetworkInfo.isConnected` 更准确。
- 用 `PAGE_URL`/`PAGE_LOCAL_FILE` 两个独立参数区分"首选源"与"降级源"，比在 URL 字符串里做约定清晰，不易被调用方漏传。
