# SIR-3401 · 主交互状态切换新增需求逻辑开发（mistag：需求开发非缺陷）
- **提交**：`6f5e6151` | 2026-08-31 | ljl | SystemUI | feature（JSON 标注 mistag=true，标题为 feature 而非 bugfix）
- **缺陷库**：无缺陷记录（关联单号 SIR-3401 但非缺陷单）

## 问题（需求内容）
SIR-3401 要求主交互状态机（`PageStateMachine`）配合 Linux 仪表侧做一轮行为适配：G1~G8 多个事件（含行驶触屏锁定时序、Dock 收放次序、歌词页手势拦截等）的新逻辑。

## 根因分析（非缺陷，做改动要点归纳）
本提交是需求开发，改动核心三块。其一，`PageStateMachine.onDriveTouchLockChanged` 重排通知次序并加注释说明"先后不构成时序保证"：锁定时先收 Android Dock（`notifyNavBarDriveTouchLock`）再通知仪表，避免快捷入口与档位短暂同屏；toast 仅在 `DriveTouchLockController.isSwitchOn()` 为真时弹。其二，引入临时特性开关 `linuxDockAdaptationEnabled`（存于 `DataContext`，经 `setLinuxDockAdaptationEnabled` 切换），G1/G2/G3/G4/G5/G8 新逻辑可整体回退，G6/G7 不受开关控制。其三，`GestureGuard` 对 `RightDownMusicSwipeUp`（右下角音乐卡片上滑进歌词页）在非网易云焦点时除拦截外补 `showLyricUnsupportedToast()` 提示；并新增调试页 `PageStateTestActivity` 用于驱动状态机事件。

## 关键代码修改
改动文件：PageStateMachine.kt、StateEventRouter.kt、DataContext.kt、GestureGuard.kt、PageStateTestActivity.kt（新增）、NavBarFragment.java、中英 strings（共 8 文件，+255/-37）
```diff
--- a/.../digitalkey/mainaction/common/DataContext.kt
+    var linuxDockAdaptationEnabled: Boolean = false,
+    // Linux 仪表侧适配总开关（临时特性标志，默认关）…对接稳定后删除此标志即为永久启用
```
```diff
--- a/.../digitalkey/mainaction/common/GestureGuard.kt
         if (event == Event.RightDownMusicSwipeUp && !isNeteaseMusicFocus()) {
             LogUtils.d(TAG, "Current audio focus is not netease music, block event=${event.name}")
+            showLyricUnsupportedToast()
             return true
         }
```

## 为什么能修复（达成情况）
特性开关让"仪表侧尚未就绪"阶段新逻辑可安全合入主干，回退成本一行；锁定/解锁通知次序的重排消除了仪表与 Dock 短暂不一致的窗口；歌词拦截补 toast 消除静默失败。风险集中在：临时开关承诺"稳定后删除"，若遗忘将成为长期双路径维护负担。

## 复盘与经验
- 该提交被分入 bugfix 批次属 mistag：feature 提交挂缺陷批次会稀释缺陷统计，分析流水线应按提交消息前缀（[feature]/[bugfix]）先行分流。
- 跨系统（Android/Linux 仪表）行为对齐用特性开关分阶段启用是工程正解，但要给开关写明删除条件（本提交注释做到了）。
- 拦截类手势逻辑（GestureGuard）新增拦截时同步补用户提示，与 178ea894 的教训一致：静默拦截=用户以为坏了。
