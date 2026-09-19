# 无单号 [SRS_VehSetting_009] 行车辅助
- **提交**：`e82137df` | 2026-08-13 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_009

## 需求/目标
为辅助驾驶页（AssistedDrivingFragment）新增两项：adasInfo 入口点击弹出"辅助驾驶功能介绍"四 Tab 大弹窗；"驾驶辅助预警优先显示在 HUD"开关根据当前档位（P 挡限制）动态显示提示副标题。

## 实现结构
- `diologfragment/AssistIntroDialog.kt`（新增）：840x480 大弹窗，4 个 TextView Tab（前碰/后碰/车道偏离/路况预警）+ 图文详情区，`selectTab` 统一切换选中态并刷新 `ivDetail`/`tvDetailItem`，文案来自 strings 资源（assist_intro_* string-array 渲染成的四段长文案）。
- `diologfragment/SentinelDialogSmall.kt`（新增）：小尺寸提示弹窗，与 SentinelDialog 参数化风格一致。
- `ui/widget/SmartNestedScrollView.kt`（新增）：重写 `scrollTo/scrollBy`，区分"用户拖动"与"代码/焦点触发的滚动"，支持 `scrollLock + lockedScrollY` 全局锁滚动——解决弹窗/状态刷新导致页面意外滚走的问题。
- `AssistedDrivingFragment.kt`：observe `pcuActualGear` → `updateAssistHudPrioritySubtitle` 控制副标题显隐；adasInfo 点击展示弹窗。
- `CarPropertyIds.kt` 新增 HUD 优先级等相关信号 ID。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/widget/SmartNestedScrollView.kt
+    override fun scrollTo(x: Int, y: Int) {
+        // 情况1：全局锁定，强制停留在锁定位置
+        if (scrollLock) { super.scrollTo(x, lockedScrollY); return }
+        // 情况2：非用户操作触发的滚动，直接拦截
+        if (!userScrollAllowed) { return }
+        // 情况3：用户手动滚动，正常处理
+        super.scrollTo(x, y)
+    }
+    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
+        when (ev.action) {
+            MotionEvent.ACTION_DOWN -> userScrollAllowed = true
+            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userScrollAllowed = false
+        }
+        return super.onInterceptTouchEvent(ev)
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
+    private fun updateAssistHudPrioritySubtitle(gear: Int) {
+        val isNotPGear = (gear == GEAR_P)
+        mBinding.ssvAssistHudPriority.setSubtitle(
+            if (isNotPGear) getString(R.string.assist_hud_priority_subtitle) else ""
+        )
+        mBinding.ssvAssistHudPriority.setSubtitleVisible(if (isNotPGear) View.VISIBLE else View.GONE)
+    }
```
AssistIntroDialog 的 Tab 切换用 `forEachIndexed { i, tv -> tv.isSelected = (i == index) }` 集中刷新，配合 drawable selector 无需手写背景切换，是最简洁的 Tab 实现手法。SmartNestedScrollView 的思路是"只放行带 ACTION_DOWN 的滚动序列"，代码滚动（smoothScrollTo 等）默认被吞，需要显式走 `scrollToProgrammatically` 白名单。

## 复盘与要点
- `isNotPGear = (gear == GEAR_P)` 命名与判断相反（gear==0 是 P 挡却叫 isNotPGear），且文案"仅在P挡时可用"在 P 挡时显示的逻辑是否符合需求需 QA 确认——命名反义是后期维护的隐形地雷。
- SmartNestedScrollView 封装了车机上常见的"焦点/刷新抢滚动"问题，可作为通用控件沉淀到 common 层。
- 辅助驾驶介绍用"一张底图 + 四段富文本 string"而非 ViewPager，实现极简但扩展性有限（tabImageResIds 四项目前是同一张图）。
