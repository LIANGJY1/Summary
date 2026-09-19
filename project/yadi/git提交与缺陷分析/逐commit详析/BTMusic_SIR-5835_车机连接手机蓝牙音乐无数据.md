# SIR-5835 · 蓝牙音乐无数据时点击多媒体卡片BTN无提示

- **提交**：`ce0681c9` | 2026-08-19 | dufan | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车机连接手机蓝牙音乐后无曲目数据，点击多媒体卡片上的 BTN 按键，既不播放也无"无音源"提示，用户无任何反馈。

## 根因分析
缺陷库标注根因为"未添加对应逻辑"。代码层面有两处缺口：其一，`BtMusicModel.kt` 中判定"无音源"的 `mIsNoMusicSource` 只认手机上报 title 为 `"MUSIC_SOURCE_BT"` 且描述为空的场景，而部分手机（如问题机型）在无数据时上报的 title 是 `"Not Provided"`，走不进无音源分支，上层依赖该标志弹 toast 的逻辑自然不触发；其二，`MediaForegroundService.onStartCommand` 原来直接交给 `MediaButtonReceiver.handleIntent(mediaSession, intent)` 分发媒体按键，该路径依赖 mediaSession 回调处于可用状态，无音源/会话异常时按键事件被吞。本次改为在 Service 内直接解析 `Intent.ACTION_MEDIA_BUTTON` 的 `EXTRA_KEY_EVENT`，按 keyCode 87（下一曲）、126（播放）、127（暂停）直接调用 `BtMusicModel.setNext()/setPlayAndPause()`。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt、service/MediaForegroundService.kt（2 文件，+22/-3）
```diff
@@ application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt @@
-                    TextUtils.equals(title, "MUSIC_SOURCE_BT") && TextUtils.isEmpty(descriptionChar)
+                    (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided")) && TextUtils.isEmpty(descriptionChar)
```
```diff
@@ application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt @@
-        MediaButtonReceiver.handleIntent(mediaSession, intent)
+        intent?.let {
+            if ((Intent.ACTION_MEDIA_BUTTON == it.action
+                        && it.hasExtra(Intent.EXTRA_KEY_EVENT))) {
+                val keyEvent = it.getParcelableExtra<Parcelable>(Intent.EXTRA_KEY_EVENT) as? KeyEvent
+                when (keyEvent?.keyCode) {
+                    87 -> {
+                        LogUtils.d(tag, "onStartCommand onSkipToNext")
+                        mBluetoothPlayerService.mBtMusicModel.setNext()
+                    }
+                    126 -> {
+                        LogUtils.d(tag, "onStartCommand onPlay")
+                        mBluetoothPlayerService.mBtMusicModel.setPlayAndPause(true)
+                    }
+                    127 -> {
+                        LogUtils.d(tag, "onStartCommand onPause")
+                        mBluetoothPlayerService.mBtMusicModel.setPlayAndPause(false)
+                    }
+                }
+            }
+        }
```

## 为什么能修复
把 `"Not Provided"` 纳入无音源判定后，`mIsNoMusicSource` 在问题机型上正确置位，卡片点击路径上的"无音源弹 toast"逻辑得以执行；按键改为直连 `BtMusicModel` 绕过了失效的 mediaSession 分发，保证无数据时按键仍有响应/提示。隐患：手写 keyCode 数字（87/126/127）可读性差；`getParcelableExtra` 在高版本 API 已废弃，且绕过 `MediaButtonReceiver` 后其它按键码（如上一曲）不再走会话回调，若后续需要需补全。

## 复盘与经验
- 对接第三方（手机蓝牙 AVRCP）数据时，"无数据"的标识值不是协议规定而是各家手机自由实现，判定条件要按实测机型枚举扩充（"MUSIC_SOURCE_BT" vs "Not Provided"）。
- 空反馈是最伤体验的失败模式：任何按键路径都应有兜底提示，缺陷库"未添加对应逻辑"即指这条兜底分支缺失。
- `MediaButtonReceiver.handleIntent` 依赖会话可用性，关键按键路径上值得像本提交一样做一层直连解析。
