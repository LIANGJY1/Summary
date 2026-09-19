# 无单号 · 无网络时用户协议页加载失败（网络判定不准且无兜底）

- **提交**：`cb94dcca` | 2026-09-10 | sgh | Setting | bugfix（未关联单号）
- **缺陷库**：未关联单号

## 问题
车机实际无法上网时，用户协议 WebView 页仍尝试加载线上 URL，导致协议加载失败、页面空白/报错。

## 根因分析
`WebFragment.isNetworkAvailable()` 只判了 `NetworkCapabilities.NET_CAPABILITY_INTERNET`——该能力仅表示网络"声明"具备上网能力，车机上常亮的以太网即使不通外网也返回 true，于是 `onViewCreated` 里 `haveNet=true` 走 `webView.loadUrl(url)`，线上协议加载不出（缺陷库记"判断网络的逻辑有问题"）。同时整个链路没有失败兜底：即使误判有网，WebView 主框架加载失败（DNS 解析失败、超时、HTTP 403 等）后也没有回退到本地内置协议 `PAGE_LOCAL_FILE` 的机制。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt（1 文件 +52/-4）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt
@@ isNetworkAvailable()
-        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
+        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
+        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
+        // INTERNET 仅表示网络"声明"具备上网能力（如常亮的以太网），必须同时通过系统的外网连通性校验
+        return hasInternet && validated
@@ WebViewClient 新增主框架失败回调
+            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
+                super.onReceivedError(view, request, error)
+                if (!request.isForMainFrame) return
+                fallbackToLocalHtml()
+            }
+            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
+                super.onReceivedHttpError(view, request, errorResponse)
+                if (!request.isForMainFrame) return
+                fallbackToLocalHtml()
+            }
@@ 新增兜底函数
+    private fun fallbackToLocalHtml() {
+        if (hasFallbackToLocal) return
+        val localFile = arguments?.getString("PAGE_LOCAL_FILE").orEmpty()
+        if (localFile.isEmpty()) return
+        hasFallbackToLocal = true
+        webView.stopLoading()
+        loadLocalHtml(localFile)
+    }
```

## 为什么能修复
两层防御：事前判定加 `NET_CAPABILITY_VALIDATED`（系统连通性探测通过才认为有网），无网时直接走 `loadLocalHtml(localFile)`；事后兜底监听主框架的 `onReceivedError`/`onReceivedHttpError`，即使判定漏网（如已 validated 但协议域名 403/签名过期）也回退本地页，`hasFallbackToLocal` 标志防止子资源错误或重复回调触发循环加载。副作用：`VALIDATED` 依赖系统 captive portal 探测，探测未完成瞬间会误判无网而先显示本地页，对协议场景可接受；无本地文件时仍无兜底（返回空判断直接跳过）。

## 复盘与经验
- 车机"有网卡"不等于"能上网"：判定网络必须用 `NET_CAPABILITY_VALIDATED`，单看 `INTERNET` capability 在以太网常供的座舱环境必然误判。
- WebView 加载外部内容必须有本地兜底（fallback + 主框架错误过滤 isForMainFrame + 防重入标志），三件套缺一不可，否则要么死循环要么兜不干净。
- 协议/隐私政策这类法务页面，"始终可见"优先于"最新版本"，离线内置页是车联网应用的标配。
