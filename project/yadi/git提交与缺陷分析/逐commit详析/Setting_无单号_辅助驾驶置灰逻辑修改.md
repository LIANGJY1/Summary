# 无单号 [SRS_VehSetting_009] 辅助驾驶置灰逻辑修改
- **提交**：`8040b6ad` | 2026-08-19 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_009

## 需求/目标
辅助驾驶页引入"P 挡才可操作"的门控：前碰/后碰/车道偏离/路况预警 4 个开关 + 灵敏度档位 + "驾驶辅助预警优先显示在HUD"开关，非 P 挡时统一置灰并显示各自副标题提示；同时修正了此前档位副标题显示条件写反的问题。

## 实现结构
- `AssistedDrivingFragment.kt`：新增 `isPGear` 成员；`updateAssistHudPrioritySubtitle`（逻辑反义版）重构为 `updatePGearSubtitle`——正确判定 `isPGear = (gear == GEAR_P)`，对 5 个开关批量 setSubtitle/setSubtitleVisible；点击侧 `setupAdasSwitch`、灵敏度 RadioGroup、HUD 优先级开关回调全部加 `if (!isPGear ...) return` 拦截；`updateAdasStatusUI` 的置灰条件升级为 `enabled && isPGear` 双条件；HUD 开关的置灰拆分给 `applyAssistHudPGearGray`（叠加 HUD 连接状态）。
- `DrivingFragment.kt`：删 7 行冗余（顺势清理）。
- strings：新增 5 条"xxx仅在P挡时可用"副标题文案（中英）。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
+    private fun updatePGearSubtitle(gear: Int) {
+        isPGear = (gear == GEAR_P)
+        val visibility = if (isPGear) View.GONE else View.VISIBLE
+        mBinding.apply {
+            ssvAssistHudPriority.setSubtitle(getString(R.string.assist_hud_priority_p_subtitle))
+            ssvAssistHudPriority.setSubtitleVisible(visibility)
+            ssvForwardCollisionWarning.setSubtitle(getString(R.string.forward_collision_warning_p_subtitle))
+            ssvForwardCollisionWarning.setSubtitleVisible(visibility)
+            ... // 后碰/车道/路况同理
+        }
+        updateAdasStatusUI(isAdasControlEnabled)
+        applyAssistHudPGearGray()
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
     private fun setupAdasSwitch(switch: CustomSwitchCompat, tag: String, action: () -> Unit) {
         switch.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
-            if (!isAdasControlEnabled) return@setClickFastWithRebound
+            if (!isPGear || !isAdasControlEnabled) return@setClickFastWithRebound
```
置灰采用"两层叠加"：ADAS 通用可用性（连接状态 × P 挡）由 `updateAdasStatusUI` 处理 4 个预警开关；HUD 专属开关由 `applyAssistHudPGearGray` 处理（HUD 连接 × P 挡），职责分开避免互相覆盖。副标题在挡位回调中统一设置文本、只切换可见性，减少重复 getString。

## 复盘与要点
- 修复了 e82137df 引入的反义 bug（`isNotPGear = (gear == GEAR_P)`），并把单开关副标题推广为全页门控——一个需求的两次提交间隔 6 天，说明"先小面积接信号、再全页推广"的落地节奏。
- "点击回调 return 拦截 + 回显时置灰 + 副标题说明"三层配合是车控门控的完整闭环：只置灰不拦截点击会有 overlay 失效风险，只拦截不置灰用户不知道原因。
- 遗留风险：5 个开关副标题/置灰逻辑高度重复（后续 8d645edb 还在做置灰收敛），存在继续抽象空间；`isPGear` 默认 true 意味着信号未到前页面全部可操作。
