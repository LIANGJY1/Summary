# 无单号 · BTMusic 添加语音操作

- **提交**：`3ba8ef1d` | 2026-08-25 | dufan | BTMusic | feature
- **关联单**：无

## 需求/目标
让蓝牙音乐响应语音助手的媒体控制指令：通过 MediaSession 的自定义 action 通道接收 `com.aispeech.lyra.assistant` 指令，映射为播放/暂停/上一首/下一首。

## 实现结构
仅改动 `application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt`（+19 行）：在 `MediaSessionCompat.Callback` 中重写 `onCustomAction`，按 action 名识别语音助手通道，再从 extras 的 `key_event` 字符串分发：`play`→播放、`pause`→暂停、`down`→下一首、`up`→上一首；其余 action 走父类。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
+    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
+
+        override fun onCustomAction(action: String?, extras: Bundle?) {
+            LogUtils.d(tag, "onCustomAction: $action")
+            when (action) {
+                "com.aispeech.lyra.assistant" -> {
+                    val keyEvent = extras?.getString("key_event", "down") ?: "down"
+                    when (keyEvent) {
+                        "play" -> mBluetoothPlayerService.mBtMusicModel.setPlayAndPause(true)
+                        "pause" -> mBluetoothPlayerService.mBtMusicModel.setPlayAndPause(false)
+                        "down" -> mBluetoothPlayerService.mBtMusicModel.setNext()
+                        "up" -> mBluetoothPlayerService.mBtMusicModel.setPrevious()
+                    }
```
实现讲解：语音助手不直接调起 Activity，而是通过 MediaSessionController 发送 custom action，实现跨应用解耦控制。值得注意的隐含协议：`key_event` 的取值 `down/up` 在这里被映射为"下一首/上一首"，与字面按键含义无关，说明这是语音端约定好的语义复用；未匹配的 keyEvent 静默忽略，仅留日志。

## 复盘与要点
- 用 MediaSession onCustomAction 承载私有指令协议是车机语音控媒体的常见轻量方案，无需自定义 AIDL；但 action 名/extras 键是 magic string，两端（语音 app 与媒体 app）需以常量表或文档对齐。
- `down`→下一首、`up`→上一首的反直觉映射极易埋坑，建议至少注释说明协议来源。
- 未处理 `onSkipToNext/onSkipToPrevious` 标准回调是否也由语音走标准路径，存在双通道语义重叠的潜在冲突。
