# SIR-4568 · 黑夜模式下隐私政策/用户协议网页弹窗框内底色是白色

- **提交**：`475e691b` | 2026-07-29 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
切到黑夜模式后，设置系统中"个人信息保护政策""用户许可协议"网页弹窗的框内底色仍是白色，刺眼且与暗色主题不符。

## 根因分析
两层原因：
1. **WebView 层**：`WebFragment` 加载本地 HTML 时 `webView.setBackgroundColor(Color.TRANSPARENT)`，WebView 自身不参与主题；既没有按 uiMode 设置底色，也未开启 WebView 的算法变暗/跟随系统配色能力，HTML 默认白底直接透出。
2. **HTML 层**：`personal_infomation_agreement.html` / `user_service_agreement.html` 全部样式为写死的亮色值（`body background #f5f5f5`、`.container background #fff` 等），无任何 `prefers-color-scheme` 声明，浏览器侧无从切换。缺陷库"未适配白天黑夜模式"即此。

## 关键代码修改
改动文件：application/Setting/build.gradle；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt；application/Setting/src/main/assets/personal_infomation_agreement.html；application/Setting/src/main/assets/user_service_agreement.html

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WebFragment.kt
-        webView.setBackgroundColor(Color.TRANSPARENT)
         webView.setLayerType(WebView.LAYER_TYPE_NONE, null)
@@
+        applyWebViewTheme()
@@
+    private fun applyWebViewTheme() {
+        val isNightMode =
+            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
+                Configuration.UI_MODE_NIGHT_YES
+        webView.setBackgroundColor(
+            if (isNightMode) Color.rgb(21, 23, 26) else Color.WHITE
+        )
+        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
+            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
+        }
+    }
+
+    override fun onConfigurationChanged(newConfig: Configuration) {
+        super.onConfigurationChanged(newConfig)
+        if (::webView.isInitialized) {
+            applyWebViewTheme()
+            webView.reload()
+        }
+    }
```

```diff
--- application/Setting/src/main/assets/personal_infomation_agreement.html
+    <meta name="color-scheme" content="light dark">
+        :root { color-scheme: light dark;
+            --page-background: #f5f5f5; --content-background: #ffffff; ... }
+        @media (prefers-color-scheme: dark) {
+            :root {
+                --page-background: #15171A; --content-background: #1F2226;
+                --primary-text: #F2F2F2; ... } }
         body {
-            background-color: #f5f5f5;
-            color: #333;
+            background-color: var(--page-background);
+            color: var(--primary-text);
```

（`build.gradle` 新增 `androidx.webkit:webkit:1.6.1` 依赖以使用 `WebSettingsCompat`。）

## 为什么能修复
WebView 底色由透明改为随 uiMode 取色（夜间 rgb(21,23,26) 与 HTML 的 `--page-background: #15171A` 完全一致，加载瞬间不闪白）；HTML 侧声明 `color-scheme: light dark` 并用 CSS 变量 + `prefers-color-scheme: dark` 定义整套暗色调色板，WebView 检测到系统暗色后按媒体查询渲染暗版内容；`ALGORITHMIC_DARKENING` 兜底处理未覆盖的亮色内容；`onConfigurationChanged` 中重取主题并 `reload()`，运行中切换模式也生效。隐患：`reload()` 会丢失网页滚动位置；`ALGORITHMIC_DARKENING` 与手写暗色样式叠加时可能对个别元素产生二次变暗（需验收确认）。

## 复盘与经验
- **WebView 内容是主题适配的盲区**：App 层 values-night 管不到 HTML，本地协议/协议类页面必须在 HTML 内做 `prefers-color-scheme` 适配，并把 WebView 背景色与 HTML 底色对齐防止加载闪白。
- **CSS 变量 + 双主题调色板是网页暗色适配的标准解**：一次重构（硬编码→var()）后所有颜色集中管控，新增页面直接复用变量。
- **运行中切模式要 reload 或重渲染 WebView**，且注意 reload 的副作用（滚动位置、表单状态）；静态协议页可接受。
- **宿主背景色与网页底色保持同一数值**（15171A）是细节但决定观感，两处独立维护时应注释互相引用关系。
