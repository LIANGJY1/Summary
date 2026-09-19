# SIR-7338 · [偶发]蓝牙音乐卡片不显示音乐封面
- **提交**：`811a51dd` | 2026-09-07 | dufan | BTMusic | bugfix（cherry-pick 自 eb626f7a）
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 关闭 · 域 本地多媒体

## 问题
偶发场景下，dock 栏蓝牙音乐卡片不显示当前歌曲封面。

## 根因分析
`MediaForegroundService.getAlbumArtBitmap()` 每次构建前台通知时都从 `currentAlbumArtUri` 现场解码封面：`contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)`。这条路径对时序敏感——媒体元数据更新（`currentAlbumArtUri` 刚被赋新值/临时为空）与通知刷新并发时，`albumArtUri.isNullOrBlank()` 判空直接返回 null，或 `openInputStream` 打开 content 流瞬间失败，解码结果为 null 就被塞进通知的 `MediaMetadataCompat`/notification largeIcon，卡片于是"发送了 null"没有封面（缺陷库定性"加载数据异常发送null"）。由于 dock 卡片刷新依赖该通知链路，一次瞬时失败即表现为封面缺失，且下一次成功与否取决于再次刷新时机，呈现偶发性。修复引入最朴素的 URI→Bitmap 单槽缓存：新增成员 `mLastArtUri`/`mLastArtBitmap`，进入函数先比对 `TextUtils.equals(mLastArtUri, currentAlbumArtUri) && mLastArtBitmap != null` 命中则直接返回缓存，不再重复打开流解码；仅 URI 变化或上次解码失败（缓存位图为 null）时才走真实加载。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
+    private var mLastArtUri: String? = null
+    private var mLastArtBitmap: Bitmap? = null
...
     private fun getAlbumArtBitmap(): Bitmap? {
-        val albumArtUri = currentAlbumArtUri
-        LogUtils.i(tag, "getAlbumArtBitmap IMG uri is $albumArtUri")
-        if (albumArtUri.isNullOrBlank()) {
+        if (mLastArtUri != null && TextUtils.equals(mLastArtUri, currentAlbumArtUri) && mLastArtBitmap != null) {
+            return mLastArtBitmap
+        }
+        mLastArtUri = currentAlbumArtUri
+        if (mLastArtUri.isNullOrBlank()) {
             return null
         }
         return try {
-            val uri = albumArtUri.toUri()
-            when (uri.scheme) {
+            mLastArtBitmap = when (mLastArtUri!!.toUri().scheme) {
                 "content", "file", "android.resource" -> {
                     contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                 }
                 else -> null
             }
+            mLastArtBitmap
         } catch (e: Exception) {
             ...
             null
         }
```

## 为什么能修复
同曲多次通知刷新不再重复走"打开流→解码"的脆弱路径，消除了瞬时打开失败/元数据竞争导致的 null 封面；解码失败时缓存位图为 null、下次自动重试，语义正确。局限与隐患：单槽缓存只记一首歌，切歌即失效（对本场景足够）；解码失败当次仍返回 null，若这是最后一次通知刷新，本次封面依旧缺失——缓存降低而非根除失败概率；另外 bitmap 不随 service 生命周期显式释放，内存占用上限为一张封面图，可忽略。

## 复盘与经验
- "偶现不显示"类媒体资源问题，优先排查"每次展示都重新 IO 加载"的模式：一次瞬时失败即穿透到 UI。按内容 URI 做单槽/LRU 缓存是成本最低的加固。
- 缓存判等要用 `TextUtils.equals` 而非 `==`（String 常量池外不可靠），并保留"上次失败不缓存"的重试语义（判命中时带 `mLastArtBitmap != null`）。
- 通知/卡片这类"最终展示物"建议在数据层持有最近一次成功的结果（last-good value），刷新失败时沿用旧值而不是显式置空。
