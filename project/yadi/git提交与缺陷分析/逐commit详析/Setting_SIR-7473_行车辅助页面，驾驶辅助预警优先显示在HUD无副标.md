# SIR-7473 · 行车辅助页面，"驾驶辅助预警优先显示在HUD"无副标题提示
- **提交**：`1c449990` | 2026-09-07 | sgh | Setting | bugfix（需求变更适配）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
行车辅助设置页中，"驾驶辅助预警优先显示在 HUD"设置项没有像其他预警项那样显示 P 档相关副标题提示，与变更后的 UI 式样不符。

## 根因分析
`AssistedDrivingFragment.updatePGearSubtitle(gear)` 统一管理"P 档态副标题"：非 P 档显示各设置项副标题、P 档隐藏。旧实现里 `ssvAssistHudPriority`（HUD 优先项）被包在 `if (hasHudFeature)` 分支中，随挡位变化设置 `assist_hud_priority_p_subtitle` 副标题及可见性；而按变更后的需求，该 HUD 优先项不再需要 P 档副标题提示（其余四项 `ssvForwardCollisionWarning`/`ssvLaneDeviationWarning`/`ssvRearCollisionWarning`/`ssvTrafficWarning` 保持不变）。修复即从 `updatePGearSubtitle` 中删除整个 `if (hasHudFeature) { ssvAssistHudPriority.setSubtitle(...); setSubtitleVisible(visibility) }` 块；`applyAssistHudPGearGray()`（P 档置灰逻辑）保留未动，说明该项目的"P 档禁用"交互仍在，只是不再有文字提示。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
```diff
--- application/Setting/.../fragment/AssistedDrivingFragment.kt
         mBinding.apply {
-            if (hasHudFeature) {
-                ssvAssistHudPriority.setSubtitle(getString(R.string.assist_hud_priority_p_subtitle))
-                ssvAssistHudPriority.setSubtitleVisible(visibility)
-            }
             ssvForwardCollisionWarning.setSubtitle(getString(R.string.forward_collision_warning_p_subtitle))
             ssvForwardCollisionWarning.setSubtitleVisible(visibility)
             ssvLaneDeviationWarning.setSubtitle(getString(R.string.lane_deviation_warning_p_subtitle))
```

## 为什么能修复
删除副标题设置后，HUD 优先项不再随挡位切换显示/隐藏 `assist_hud_priority_p_subtitle`，与新式样一致；P 档置灰逻辑独立保留，功能不受影响。属于典型的减法修复，无技术风险；遗留注意点是 `assist_hud_priority_p_subtitle` 字符串资源可能因此成为无引用资源，可顺手清理。

## 复盘与经验
- 需求变更类"为什么没显示"问题，先定位管理该项显示的统一入口（本例 `updatePGearSubtitle` 集中管理所有副标题），改动集中且不易漏；这也反过来证明"同类设置项的显示逻辑应收拢到一个函数"的价值。
- 删除某项 UI 行为时，检查其专属字符串/drawable 资源是否成为孤儿，避免资源冗余累积。
