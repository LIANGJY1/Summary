# SIR-6230 · 播放蓝牙音乐时底部状态栏封面与UI不一致

- **提交**：`71d1ef97` | 2026-08-21 | dufan | BTMusic | bugfix（一行资源兜底修复）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
播放蓝牙音乐、曲目无专辑封面时，底部状态栏/通知栏位置的封面显示与 UI 设计不符（空白而非默认封面图）。

## 根因分析
缺陷库根因："未设置对应资源"。`MediaForegroundService.getAlbumArtBitmap()` 负责给常驻媒体通知/状态栏提供封面位图，旧逻辑在 `albumArtUri.isNullOrBlank()`（手机未上报专辑图）时直接 `return null`，下游只能展示空白/系统默认占位，与 UI 稿要求的默认封面不一致。修复改为返回 `BitmapFactory.decodeResource(resources, R.drawable.default_cover)`，用应用默认封面资源作为兜底位图。与 SIR-6224（黑夜模式主界面默认封面）同源：都是"无封面兜底图未落地"，一个在 Activity 层、一个在 Service 通知层。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+1/-1）
```diff
@@ application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt @@
         val albumArtUri = currentAlbumArtUri
         LogUtils.i(tag, "getAlbumArtBitmap IMG uri is $albumArtUri")
         if (albumArtUri.isNullOrBlank()) {
-            return null
+            return BitmapFactory.decodeResource(resources, R.drawable.default_cover)
         }
```

## 为什么能修复
无专辑图路径不再返回 null，状态栏封面始终有 `default_cover` 兜底图，视觉与 UI 稿一致；由于 SIR-6224 已补充 `drawable-night` 变体，黑夜模式下该兜底图也会自动切换为深色版。隐患：每次解码默认图有一次小的资源开销（可缓存为单例 Bitmap）；若通知使用了 RemoteViews 需确认位图尺寸适配。

## 复盘与经验
- 同一"兜底资源缺失"问题会在多个展示层（Activity、通知、状态栏）重复暴露，修一处时要全局搜索同资源引用点一次修全。
- `return null` 表示"放弃渲染"，UI 链路上游若没有处理 null 的兜底，最终就是空白——媒体数据缺失时每层都应有明确的占位策略。
