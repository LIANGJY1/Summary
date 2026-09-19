# SIR-6290 · 蓝牙音乐暂停时播网易云，底部状态来回切换

- **提交**：`dcc28319` | 2026-08-27 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙连接成功且蓝牙音乐处于暂停态时，播放网易云音乐，Dock 栏底部音乐状态在"网易云音乐"与"蓝牙歌曲暂停态"之间来回切换。

## 根因分析
`BtMusicModel` 通过 `MediaController.Callback.onPlaybackStateChanged` 监听蓝牙媒体会话状态。当音频焦点被 `com.arcvideo.car.ncm.music`（网易云音乐）持有时，`isCanChangeInfo()` 返回 false，原代码直接 `return@post`，**但没有停掉已经启动的进度刷新任务**。`updateRunnable` 以 500ms 周期经 `scheduleNextUpdate()` 自我 `postDelayed` 持续执行 `updateCurrentProgress()`，反复写 `mMusicProgress` LiveData；`MediaForegroundService` 中 `mMusicProgress.observeForever` 会把每次变化 emit 到 `mMusicProgressFlow` 并触发前台媒体状态（通知/更新广播）刷新。于是蓝牙侧虽被焦点规则挡住"信息变更"，却仍通过进度更新通道不断向外推送"蓝牙音乐仍在"的信号，Dock 侧把它与网易云的真实状态交替渲染，形成来回切换。提交信息概括为"发送了更新广播 → 不发送更新广播"。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
@@ if (state != null && !mIsBCallInCall) {
-                    if (!isCanChangeInfo()) return@post
+                    if (!isCanChangeInfo()) {
+                        stopProgressUpdate()
+                        return@post
+                    }
```

## 为什么能修复
`stopProgressUpdate()` 内部执行 `mHandler.removeCallbacks(updateRunnable)`，在焦点被其他音源占有时立即终结周期性进度刷新，`mMusicProgress` 不再变化，`MediaForegroundService` 也就不再发出任何媒体状态更新，Dock 停止在两个音源间摆动。副作用很小：当蓝牙音乐重新拿到焦点后，下一次 `onPlaybackStateChanged`（STATE_PLAYING 会走 `startProgressUpdate()`，其内部先 `stopProgressUpdate()` 再重启）可自然恢复进度刷新，不存在永久停摆的隐患。

## 复盘与经验
- 提前 `return` 的守卫分支要检查"是否还有定时任务/循环在跑"：只拦数据更新、不拦驱动源，等于没拦住。
- 一个对外可观察的状态（LiveData/Flow）往往有多个写入方，修"状态乱跳"类 bug 要梳理全部写入路径，而不只是改判断条件。
- "媒体信息受焦点管控、进度刷新不受管控"是典型的双通道不一致，媒体源切换类 UI 抖动常源于此。
