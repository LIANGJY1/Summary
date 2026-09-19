# SIR-6281 · 蓝牙连接后手机打开网易云，底部媒体信息与蓝牙音乐信息不一致

- **提交**：`66dbc7d3` | 2026-08-24 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙连接成功后，手机端打开网易云音乐，车机底部状态的媒体信息（标题/歌手）与蓝牙音乐模块内的媒体信息不一致。

## 根因分析
`MediaForegroundService`（前台媒体服务，负责维护 MediaSession 与底部状态栏媒体数据）在 `PlayerCallback.callBackPlaybackState(playbackState)` 回调里只更新了 `currentPlaybackState` 并调用 `updateMediaSessionState(playbackState)`，没有从 `BtMusicModel` 的 LiveData（`mMusicTitle`、`mMusicArtist`）重新取最新的标题/歌手字段。缺陷库根因"字段数据未更新"：蓝牙元数据变化由 `BtMusicModel` 承接，而 `MediaForegroundService` 持有的是旧的 `currentTitle`/`currentArtist` 成员缓存，播放状态回调触发 MediaSession 元数据重组时仍写入旧字段，两处数据源出现时间差，表现为底部信息与蓝牙音乐信息不一致。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+7）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ PlayerCallback.callBackPlaybackState
         mPlayerControl?.addCallback(object : PlayerCallback {
             override fun callBackPlaybackState(playbackState: PlaybackState?) {
+                currentTitle = mBtMusicModel.mMusicTitle.value
+                currentArtist = mBtMusicModel.mMusicArtist.value
                 currentPlaybackState = playbackState
                 updateMediaSessionState(playbackState)
                 val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
```
（新增 `val mBtMusicModel: BtMusicModel by lazy { BtMusicModel }` 引用。）

## 为什么能修复
每次播放状态回调时先从 `BtMusicModel` LiveData 拉取最新标题/歌手再刷新 MediaSession，底部状态与蓝牙音乐模型重新对齐到同一数据源，消除缓存字段的时间差。隐患：只对齐了"播放状态回调"这一入口，若元数据更新与状态回调的到达顺序仍颠倒（先状态后元数据），单次刷新可能仍短暂显示旧值；更彻底的做法是直接观察 `mMusicTitle/mMusicArtist` LiveData 或以 `onMetadataChanged` 为同一刷新入口。

## 复盘与经验
- 同一份数据被多个组件各自缓存成员变量持有时，必然出现不一致；能观察 LiveData 就不要手动拷贝字段。
- "字段数据未更新"类 bug 的通用修法是找齐所有消费点，让消费侧每次使用前从单一数据源取值。
- 回调参数只给了一半上下文（本例只有 playbackState）时，应在回调内主动补齐另一半（metadata），而不是依赖外部顺序。
