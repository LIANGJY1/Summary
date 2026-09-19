# SIR-3401 · 主交互增加进入网易云歌词页面功能

- **提交**：`68a835c7` | 2026-08-19 | ljl | SystemUI | 功能新增（标题即[feature]，非bugfix；缺陷库已标 mistag）
- **缺陷库**：关联单号 SIR-3401 但 defs 为空（功能需求单，非缺陷）

## 问题
需求类改动：主交互新增"右下角音乐卡片上滑进入网易云歌词页"手势能力，此前该手势（gestureType 8）因歌词页未集成而注释禁用。

## 根因分析
不适用（非缺陷修复）。实现要点：`GestureServiceConnector.kt` 启用 `8 -> Event.RightDownMusicSwipeUp` 映射；`GestureGuard.shouldBlock()` 新增前置校验——当前媒体焦点不是网易云（`CarAudioVolumeController.isCurrentFocusNeteaseMusic()`）时拦截该手势；`CarAudioVolumeController` 新增 `currentFocusPackageName` 跟踪：在 `carFocusCallback.onCarFocusChanged` 中仅当焦点映射到 `GROUP_MEDIA` 才更新包名（语音助理 USAGE_ASSISTANT 瞬时抢占不算切源），焦点切到非网易云（包名非 `com.arcvideo.car.ncm.music`）时延迟 800ms（`EXIT_LYRIC_CONFIRM_DELAY_MS`，过滤蓝牙连接时 ncm↔bluetooth 百毫秒级抖动）经 `PageStateMachine.handleEvent(Event.LyricSwipeDown)` 退出 S5 歌词页。另含一处顺带修改：`DigitalKeyVehicleService.checkStandby1GearState()` 移除 Standby2（0x02）触发"请在P档下锁车关机"toast 的条件，与歌词页功能无关。

## 关键代码修改
改动文件：CarAudioVolumeController.kt、GestureGuard.kt、GestureServiceConnector.kt、DigitalKeyVehicleService.kt（4 文件，+125/-7）
```diff
@@ application/SystemUI/.../volume/CarAudioVolumeController.kt @@
+    private fun onMediaFocusPackageChanged(newPackageName: String?) {
+        if (newPackageName == currentFocusPackageName) return
+        currentFocusPackageName = newPackageName
+        focusExitHandler.removeCallbacks(exitLyricRunnable)
+        if (newPackageName == NETEASE_PACKAGE_NAME) return
+        focusExitHandler.postDelayed(exitLyricRunnable, EXIT_LYRIC_CONFIRM_DELAY_MS)
+    }
```
```diff
@@ application/SystemUI/.../common/GestureGuard.kt @@
+        if (event == Event.RightDownMusicSwipeUp && !isNeteaseMusicFocus()) {
+            LogUtils.d(TAG, "Current audio focus is not netease music, block event=${event.name}")
+            return true
+        }
```

## 为什么能修复
功能闭环：手势入口开启 + 非网易云焦点拦截 + 焦点切走自动退出歌词页，三段配合保证歌词页只在网易云音源下可达、可见。设计上值得肯定的点：延迟确认防抖、语音助理焦点不触发退出、异常时按拦截兜底。

## 复盘与经验
- 音源类功能要以"媒体焦点包名"为准绳，且只跟踪映射到媒体音量组的焦点，否则语音/提示音抢占会误伤状态。
- 车机上蓝牙连接等场景媒体焦点会百毫秒级抖动，任何"焦点变化即动作"的逻辑都要加确认延迟。
- 提交里混入无关改动（Standby1/Standby2 toast 条件）会污染回溯定位，复盘时需人工剥离。
