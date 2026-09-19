# SIR-5464 · 头盔电话音量进度条最小可调至0与SRS范围不符

- **提交**：`d5b833e1` | 2026-08-03 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
系统设置中头盔电话（通话）音量条能把音量调到 0，违反 SRS 定义的最低音量范围（应为 5）。

## 根因分析
`SoundFragment.setupCallSeekBar()` 的 `onProgressChanged` 里虽然有 `if (progress <= 5) seekBar?.progress = 5` 的钳制，但钳制后没有 `return`，代码继续往下执行：`handleVolumeWithLimit(...)` 的 `applyVolume` 回调中 `mViewModel.setVolumeForUsage(AUDIO_VOLUME_GROUP_CALL, progress)` 用的是拖动原始 `progress`（可以是 0）。也就是说 UI 进度条被视觉上拉回 5，实际下发的音量仍是 0——"下限值设置限制失效"正是这个"只改视图不拦截流程"的半截钳制。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt setupCallSeekBar()
-                if (progress <= 5) seekBar?.progress = 5
+                if (progress <= 5) {
+                    seekBar?.progress = 5
+                    return
+                }
                 val maxGroupValue =
                     mViewModel.getMaxVolumeForUsage(CarAudioManager.AUDIO_VOLUME_GROUP_CALL)
                 hasCallTriggeredLimit = handleVolumeWithLimit(
```
另有两处 `chooseIcon(...)` 参数排版整理，无逻辑变化。

## 为什么能修复
钳制分支补上 `return` 后，progress≤5 时只把进度条视图回弹到 5 并终止本次回调，不再用原始 0 值调用 `setVolumeForUsage` 下发音量；≥6 的路径走 `handleVolumeWithLimit` 原有上限限制，SRS 音量范围 [5, max] 得到完整执行。注意 `setupRingtoneSeekBar` 等其他音量条仍是无 return 的旧写法（下限 1），存在同类隐患，可按需统一。

## 复盘与经验
- "钳制值"必须区分改视图与改流程：`seekBar.progress = X` 只影响显示，后续逻辑仍在用局部变量 `progress`，钳制后应立即 return 或用钳制值替换变量。
- 带下限的滑条标准写法是先做范围归一化（`val safe = progress.coerceIn(min, max)`），再统一走设置逻辑，避免每个分支手工拦截。
- 同一页面多个相似控件（媒体/通话/铃声）常见复制粘贴，修一处要排查同构代码是否同病。
