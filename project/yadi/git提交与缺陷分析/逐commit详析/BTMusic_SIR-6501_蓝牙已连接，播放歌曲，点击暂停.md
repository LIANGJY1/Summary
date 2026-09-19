# SIR-6501 · 蓝牙音乐暂停时进度显示回退约 1 秒

- **提交**：`24db8647` | 2026-08-26 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（蓝牙音乐）

## 问题
蓝牙音乐播放中点击暂停，进度条/时间显示从当前位置倒退约 1 秒。

## 根因分析
`BtMusicModel.updateCurrentProgress()` 对播放与暂停用了两种口径计算进度：`STATE_PLAYING` 时用 `state.position + (SystemClock.elapsedRealtime() - lastPositionUpdateTime) * playbackSpeed` 做**外推**（预测当前播放位置），暂停时直接取 AVRCP 上报的 `state.position` **原始值**。手机端 AVRCP 的 position 上报存在滞后（通常约 1 秒粒度），播放中外推值已领先原始值约 1 秒；点暂停的一瞬间切到原始值口径，UI 从外推值跳回原始值，视觉上进度倒退 1 秒。两种计算方式不一致是根因（缺陷库同述），且数据源滞后是蓝牙协议固有特性，无法从源头消除。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt`

```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ -60,6 +60,8 @@ object BtMusicModel {
     var mIsNoMusicSource = true
     private var mIsBCallInCall = false
+    private var mLastUiProgress = 0L
+    private const val REGRESSION_TOLERANCE_MS = 2000L
 
@@ -219,13 +221,19 @@ object BtMusicModel {
     private fun updateCurrentProgress() {
         val state = mPlaybackState.value ?: return
-        if (state.state == PlaybackState.STATE_PLAYING) {
+        val newProgress = if (state.state == PlaybackState.STATE_PLAYING) {
             val currentTime = SystemClock.elapsedRealtime()
             val elapsedTime = (currentTime - state.lastPositionUpdateTime).toFloat()
-            mMusicProgress.value = state.position + (elapsedTime * state.playbackSpeed).toLong()
+            state.position + (elapsedTime * state.playbackSpeed).toLong()
         } else {
-            mMusicProgress.value = state.position
+            state.position
         }
+        val safeProgress =
+            if (newProgress < mLastUiProgress && mLastUiProgress - newProgress < REGRESSION_TOLERANCE_MS) {
+                mLastUiProgress
+            } else newProgress
+        mLastUiProgress = safeProgress
+        mMusicProgress.value = safeProgress
     }
```

## 为什么能修复
引入 `mLastUiProgress` 记录上次展示的进度：新计算值若**小于**上次值且回退幅度在 `REGRESSION_TOLERANCE_MS`（2 秒）内，则维持上次值不回退——恰好覆盖"暂停时外推值与上报值之间约 1 秒的口径差"；而切歌、手动拖动等真正的位置跳变（回退幅度远超 2 秒）不受拦截，正常回显。顺带修复了日志中 `singerText` 误打 `duration` 的变量错误。隐患：若 AVRCP 上报滞后超过 2 秒（极端卡顿场景）仍可能出现小幅回退，容差值需与实测的协议滞后上限匹配；暂停后不再外推、又拦住了小幅修正，暂停期间的显示停在原值，符合预期。

## 复盘与经验
- 同一进度量在不同播放态下用了不同计算口径（外推 vs 原始值），是显示回退的经典模式；统一口径或加"单调性保护"（进 `Livedata` 前过一道防回退闸）都可，本例用后者成本最低。
- 蓝牙/网络的媒体 position 上报天然滞后，任何"实时进度"都必须外推；外推值与上报值切换时必然存在落差，要设计平滑策略而不是直接赋值。
- 防回退拦截必须带容差（tolerance）并放行大跳变，否则会误伤切歌/拖动进度条等合法回退——容差值的依据（协议滞后上限）应写进常量命名或注释。
