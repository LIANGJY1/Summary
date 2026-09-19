# SIR-1766 · 断开蓝牙后多媒体卡片进度条残留
- **提交**：`dc96990e` | 2026-07-02 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
多媒体卡片播放蓝牙音乐时断开蓝牙连接，卡片上的媒体通知/进度条残留不清除。

## 根因分析
`MediaForegroundService.updateNotification`（`application/BTMusic/.../btmusic/service/MediaForegroundService.kt`）无条件调用 `notificationManager.notify(notificationId, buildNotification(...))` 刷新前台媒体通知，从不判断蓝牙连接状态，也没有取消通知的路径。断开蓝牙后元数据虽被置为 `no_playback_hint`（无播放提示），但通知仍以 `ongoing(isPlaying)`、带 `progress(currentProgress)` 的形态存在，`currentProgress` 也没被复位——主交互的多媒体卡片读取该通知/MediaSession 渲染，于是呈现旧进度条残留。缺陷库归因"蓝牙关闭时清空通知"。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt`（`activity_main.xml` 为歌名/歌手 TextView 宽度改 `match_parent` 及 marquee 属性整理，属顺带调整）
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ -321,6 +321,7 @@
             currentTitle = getString(R.string.no_playback_hint)
             currentArtist = getString(R.string.no_playback_hint)
             currentAlbumArtUri = ""
+            currentProgress = 0
@@ -335,9 +337,18 @@
-        notificationManager.notify(
-            notificationId, buildNotification(mPlaying, currentTitle, currentArtist)
-        )
+        if (mBTConnected.value == true && mA2dpIsConnect.value == true) {
+            notificationManager.notify(
+                notificationId, buildNotification(mPlaying, currentTitle, currentArtist)
+            )
+        } else {
+            LogUtils.d(tag, "BT_notification updateNotification clear notification")
+            notificationManager.cancel(notificationId)
+            stopForeground(STOP_FOREGROUND_REMOVE)
+            isForegroundStarted = false
+        }
```
（另一处蓝牙断开回调的元数据清理同样补充 `currentProgress = 0`。）

## 为什么能修复
刷新通知前先校验 `mBTConnected` 与 `mA2dpIsConnect` 两个 LiveData：蓝牙或 A2DP 断开时改为 `notificationManager.cancel(notificationId)` + `stopForeground(STOP_FOREGROUND_REMOVE)` 并复位 `isForegroundStarted`，通知连同其携带的进度条一并从多媒体卡片移除；进度数据源 `currentProgress` 在清元数据时归零，避免下次连接继承旧进度。隐患：`stopForeground(STOP_FOREGROUND_REMOVE)` 会终止前台服务形态，若之后又收到播放回调需确保 `isForegroundStarted` 状态与重新 startForeground 的时序正确，否则可能出现通知重建闪烁。

## 复盘与经验
- 媒体通知的生命周期必须绑定连接状态：notify 前校验连接，断开时 cancel + stopForeground，缺一条就留残留。
- 进度条类 UI 的复位要连同数据源（currentProgress）一起清，只清视图不清数据会在下次会话复现旧值。
- 断连场景常被当作"显示空提示"处理，但通知栏/卡片的残留是独立的清理责任，要显式 cancel。
