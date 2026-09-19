# SIR-6677 · 车速大于 0 时歌词模式仍可下滑关闭

- **提交**：`4f9a5472` | 2026-08-28 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
行驶触屏锁定生效（车速 > 0）时，歌词模式（S5_Linux_Lyric）本应禁止退出，但仍能通过下滑手势关闭。

## 根因分析
`GestureGuard` 维护一份"锁屏状态下拦截"的手势白名单，其中收录了 `FourFingerSwipe`、`MediaCardSwipeUp`、`RightDownMusicSwipeUp`、`ThreeFingerSwipe` 等，**唯独漏掉了 `Event.LyricSwipeDown`（歌词页下滑）**。因此车速达标、`ctx.isScreenLocked=true` 时，下滑歌词事件未被拦截，仍进入 `PageStateMachine` 的转移表，从 S5 切到 `S1_Linux_Dashboard`。同时 `CarAudioVolumeController` 的音源焦点变化逻辑复用了 `Event.LyricSwipeDown` 作为"系统退出歌词"的载体——但那是非触屏系统事件，本就不应被触屏锁定拦截。一个事件枚举承担"触屏手势"与"系统事件"两种语义，导致无法只拦截其一。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/GestureGuard.kt、application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/enums/Event.kt、application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt、application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt、application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/test/PageStateTestActivity.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/GestureGuard.kt
         return when (event) {
             Event.FourFingerSwipe,
             Event.MediaCardSwipeUp,
             Event.RightDownMusicSwipeUp,
+            Event.LyricSwipeDown,
             Event.ThreeFingerSwipe,
             Event.ThreeFingerSwipeUp,
             Event.ThreeFingerSwipeDown -> true

--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/enums/Event.kt
+    MediaFocusExitLyric,   // 媒体焦点离开网易云，系统退出歌词页（非触屏手势）

--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
-                Event.LyricSwipeDown -> {
-                    State.S1_Linux_Dashboard to { _: DataContext ->
-                        notifier.showLinuxDashboard(c.subMode)
-                        ...
-                    }
+                Event.LyricSwipeDown, Event.MediaFocusExitLyric -> {
+                    State.S1_Linux_Dashboard to ::exitLyricToDashboard
                 }
```
配套：`CarAudioVolumeController` 焦点切走路径改发 `MediaFocusExitLyric`；测试页新增用例 17（锁屏下滑歌词被拦截，S5 不变）与用例 18（锁屏音源切走仍退出，S1）。

## 为什么能修复
`LyricSwipeDown` 加入拦截白名单后，行驶中下滑手势在 `GestureGuard` 就被吞掉，无法触达状态机；音源焦点退出改走新事件 `MediaFocusExitLyric`，不在白名单内，系统级退出在锁屏下依然有效。两者在转移表中合并指向提取出的 `exitLyricToDashboard()`，退出行为（含 Pano_B1 拉起导航）完全一致，语义与拦截策略解耦。测试用例把两条路径固化为回归用例。风险很小，前提是其它发出 `LyricSwipeDown` 的入口均为真实触屏手势。

## 复盘与经验
- 事件枚举要按"来源"分类（触屏手势/系统事件/硬按键），拦截策略挂在来源属性上；一个事件多种来源必然导致拦截粒度失控。
- 拦截类白名单是安全策略，新增退出路径时必须同步评审是否入表——本例是典型的"新增手势后忘了加白名单"。
- 修复同时补自动化/模拟用例（用例 17/18），把"拦截手势但不拦截系统事件"这一微妙语义固化下来。
