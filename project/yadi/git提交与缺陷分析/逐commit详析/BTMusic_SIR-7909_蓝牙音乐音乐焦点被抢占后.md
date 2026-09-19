# SIR-7909 · 蓝牙音乐焦点被抢占后播放按钮仍显示"播放态"
- **提交**：`13276861` | 2026-09-09 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体（根因：未更新数据）

## 问题
蓝牙音乐焦点被其他音源（如导航 TTS、通话）抢占而暂停后，界面播放按钮仍显示"可播放"状态，未显示为"暂停中"，与 UI 预期不符。

## 根因分析
`BtMusicModel`（object 单例）通过 `MediaController.Callback.onPlaybackStateChanged` 监听媒体会话播放状态。回调里有 `isCanChangeInfo()` 门控——不允许更新信息时（如焦点被抢占场景）提前 `stopProgressUpdate()` 后 `return@post`，把 `stateChange(state.state)` 和 `mPlaybackState.value = state` 整体跳过，导致 UI 侧（播放按钮）拿不到"已暂停"这个最新状态，按钮停留在旧的播放态。缺陷库根因"未更新数据"即指此：门控提前返回时丢弃了状态同步。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ -113,6 +113,9 @@
                 if (state != null) {
                     if (!isCanChangeInfo()) {
                         stopProgressUpdate()
+                        if (state.state == PlaybackState.STATE_PAUSED) {
+                            stateChange(state.state)
+                        }
                         return@post
                     }
                     val stateState = state.state
```

## 为什么能修复
在不允许变更曲目信息的早退分支里，对 `STATE_PAUSED` 单独补发 `stateChange(state.state)`，让播放按钮状态机收到"暂停"事件完成 UI 切换，同时保持早退分支不更新歌名/封面等信息的原语义（只放行暂停态）。副作用：`mPlaybackState.value` 在早退分支仍未更新，依赖完整 `PlaybackState` 对象（进度等）的订阅者拿不到这次暂停事件，只有按钮态（stateChange）同步——若进度条也依赖该状态需另行确认。

## 复盘与经验
- 早退/门控分支不能一刀切丢弃事件：播放器里"暂停态"关系到操作按钮正确性，应与"曲目标题可否刷新"分开评估，按事件类型选择性放行。
- 状态同步类 bug 的典型信号是"界面显示与真实状态脱节且只在特定门控路径下发生"，排查时优先看回调里的提前 return 都跳过了哪些订阅者更新。
- 修这类问题时，最小改动是只放行关键状态（STATE_PAUSED），避免为了一个按钮把整个信息更新门控打开，引入歌名/封面被错误刷新的新问题。
