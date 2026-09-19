# SIR-2603 · 调节头盔多媒体音量进度条无预览音
- **提交**：`49004f1f` | 2026-07-13 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
调节头盔多媒体音量进度条时，喇叭没有发出预览音（其他音量条正常）。

## 根因分析
`SoundViewModel` 的预览音播放原为**单份全局播放状态**：`mCurrentStreamId/mCurrentGroupId/mCurrentUsageType` 只有一组，`playBeepWithUsage` 里 `if (mCurrentStreamId == 0)` 才允许起播。由此两个漏洞：1) 任何一组正在播（streamId!=0），其它组（如头盔组）的播放请求被直接丢弃——头盔条拖动时若恰有别的组在响，永远无声；2) 持续拖动时进度回调密集，前一次的 500ms 延迟停止任务（`mPlayJob`）与新一轮播放交错，`streamId` 复用同一条流被 stop，出现"响了就被掐掉"。缺陷库"预览音播放逻辑处理遗漏场景/修复持续拖动场景"正对应这两点。此外 `stopCurrentPlay` 里 `mCurrentGroupId=-2` 的魔法复位值让状态机更难推理。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt（+72/-42）、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt（+1/-1，log→logClick 仅日志）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt
@@ 播放状态从全局单份改为按音量组分份
+    private data class GroupPlayState(
+        val mutex: Mutex = Mutex(),
+        var streamId: Int = 0,
+        var usageType: Int = 0,
+        var isPlaying: Boolean = false,
+        var hasPending: Boolean = false,
+        var pendingUsageType: Int = 0,
+        var stopJob: Job? = null
+    )
+    private val mGroupStates = mutableMapOf<Int, GroupPlayState>()
@@ playBeepWithUsage：组内互斥，播放中改为排队不丢弃
             val state = mGroupStates.getOrPut(groupId) { GroupPlayState() }
             state.mutex.withLock {
                 if (state.isPlaying) {
                     state.hasPending = true
                     state.pendingUsageType = usageType
                     return@withLock
                 }
                 doPlaySoundLocked(state, usageType, groupId, resId)
             }
@@ 停止任务到期后补播排队请求
                 s.mutex.withLock {
                     stopPlayForGroupLocked(s, groupId)
                     s.isPlaying = false
                     if (s.hasPending) {
                         s.hasPending = false
                         doPlaySoundLocked(s, s.pendingUsageType, groupId, R.raw.test1)
                     }
                 }
@@ setStopPlay 也改为按组精确停播
+            mGroupStates[volumeGroupIdForUsage]?.let { state ->
+                viewModelScope.launch(Dispatchers.IO) { state.mutex.withLock { ... } }
```

## 为什么能修复
播放状态按 `groupId` 分份后，头盔组与其他组的 `streamId` 互不干扰，"别组在响导致头盔请求被丢"不再发生；组内持续拖动由 `hasPending/pendingUsageType` 排队、停止任务到期后补播，声音不再被中途 stop；`Mutex` 消除了 IO 协程并发改状态的数据竞争；`setStopPlay` 焦点停播也精确到组，避免误停他组。隐患：`pendingUsageType` 只有一个槽位，极端密集拖动时只保留最后一次请求（符合预期）；每组建 SoundPool 仍是共享 `mSoundPoolCache`，未按组隔离资源上限。

## 复盘与经验
- **并发资源状态要按"作用域"分份，而不是全局单份**：单份状态天然把无冲突的请求串成互斥，是"某控件偶发无响应"的常见根源。
- **丢弃式节流改排队式**：`if (playing) return` 会静默丢请求；改为记录 pending 并在停止后补播，交互反馈完整。
- **共享可变状态上协程并发必须加锁**：`Dispatchers.IO` 协程读写播放状态，原实现无同步，属于潜伏竞态，本次重构顺带修复。
- **魔法复位值（-2）是状态机坏味道**：用独立布尔（isPlaying）表达状态，比哨兵值清晰得多。
