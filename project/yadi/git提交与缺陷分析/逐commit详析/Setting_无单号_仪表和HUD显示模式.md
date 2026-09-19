# 无单号 [SRS_SYSSetting_006] 仪表和HUD显示模式
- **提交**：`97d49c9b` | 2026-08-14 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_006

## 需求/目标
仪表设置页与 HUD 设置页的"模式/尺寸"选择控件从 ImageTextRadioGroup 单选组改为"大图预览卡片 + 圆形选中圈"的图片卡片样式（专注/全景仪表、标准/小表盘、简洁/详细 HUD、日常/雪地），并配套新增卡片级 1 秒回弹机制。

## 实现结构
- `extension/ViewExtension.kt`：新增 `View.setCardReboundClick(scope, onSelect, onRebound)` 与 `cancelCardRebound`，用 `WeakHashMap<View, Job>` 管理每张卡片的回弹协程——点击立即选中并发信号，1 秒内无回显则回弹到上一状态。
- `DashboardFragment.kt`：删除 rgDialMode/rgDialSize RadioGroup 方案，改为 `bindDialModeClick/bindDialSizeClick`（preview 与 circle 两个 View 绑同一 click）+ `updateDialModeCards/updateDialSizeCards` 集中刷新 isSelected 与选中圈图标。
- `HudFragment.kt`：HUD 模式（原"地图信息档位"改名）、雪地模式同样卡片化；回显函数改为 cancelCardRebound 判定。
- 资源：新增 7 张 mdpi 预览图、`shape_dial_circle_unselected` 未选圈 drawable；`layout_car_control_hud.xml`/`fragment_dashboard.xml` 重排为卡片布局。
- 数据流：点击卡片 → sendL2A → 车端/仪表回显 LiveData → updateXxxCards 校正；超时则 onRebound 回弹。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt
+private val cardReboundJobs = WeakHashMap<View, Job>()
+
+fun View.setCardReboundClick(
+    scope: CoroutineScope,
+    onSelect: () -> Unit,
+    onRebound: () -> Unit
+) {
+    setOnClickListener {
+        if (!isEnabled) { return@setOnClickListener }
+        cardReboundJobs[this]?.cancel()
+        onSelect()
+        val job = scope.launch(Dispatchers.Main) {
+            delay(1000L)
+            if (isAttachedToWindow) { onRebound() }
+            cardReboundJobs.remove(this@setCardReboundClick)
+        }
+        cardReboundJobs[this] = job
+    }
+}
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DashboardFragment.kt
+    private fun bindDialModeClick(preview: View, circle: View, index: Int, showDialSet: Boolean) {
+        val click = {
+            settingVehicleService.sendL2A(Constants.METER_STYLE, index)
+            updateDialModeCards(index)
+            if (showDialSet) mBinding.llDialSet.visibility = View.VISIBLE
+        }
+        preview.setOnClickListener { click() }
+        circle.setOnClickListener { click() }
+    }
```
WeakHashMap 以 View 为 key 自动随视图回收 job，避免静态 map 泄漏；`isAttachedToWindow` 检查防止页面销毁后还回弹。每个选项由 preview+circle 两个可点区域构成，用共享 click 闭包绑定，选中态集中在 updateXxxCards 一处维护。

## 复盘与要点
- 把 RadioGroup 的"1 秒回弹"机制推广为任意 View 可用的扩展函数（setCardReboundClick），是本仓库回弹体系从专用控件走向通用手法的标志。
- "点击即乐观更新 + 超时回弹"的 UI 模式适合慢总线（L2A）场景，WeakHashMap 管理协程生命周期值得复用。
- 遗留风险：回弹 onRebound 里 `previousIndex = if (index == 0) 1 else 0` 是硬编码二选一，若扩展到三选项会回弹到错误项；回显到达时未重置 hudSnowModeStateTemp 的 cancelCardRebound 只对两张 preview 卡生效，circle 卡的 job 仍可能超时触发。
