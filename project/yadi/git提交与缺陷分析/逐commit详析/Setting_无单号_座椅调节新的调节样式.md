# 无单号 [SRS_VehSetting_010] 座椅调节新的调节样式
- **提交**：`19e1553b` | 2026-08-19 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_010

## 需求/目标
座椅调节交互改版：主/副驾调节入口从"上下两个按钮长按发信号"改为"长按入口 0.5s 弹出动效调节弹窗"——弹窗内是自绘的"虚线轨道 + 蓝色圆光标 + 上下箭头"竖向控件，拖动光标上下即连续调节，松手发停止信号并关闭弹窗。

## 实现结构
- `ui/widget/SeatAdjustTouchView.kt`（新增，398 行）：纯自绘 View。KDoc 完整写了动效规格（弹出 333ms、调节高亮 166ms、11 帧序列 seat_aj_000..010 约 366ms 的光标出现动画）；`onSeatAdjustListener` 四回调（onPullUp/onPullDown/onRelease/onDismiss）把绘图与业务解耦；`idleRunnable` 180ms 静默判定"停住不松手"回落高亮。
- `VehicleControlFragment.kt`：`setSeatAdjustLongPressTrigger` 用 Handler postDelayed(500ms) 实现长按触发；`showSeatAdjustPopup` 用 PopupWindow 承载弹窗并做坐标对齐；核心难点是"长按时手指仍按着"——弹出后手动构造 ACTION_DOWN `dispatchTouchEvent` 把触摸序列交接给弹窗内 View（`dispatchHandoffDown`/`dispatchToSeatTouchView` 坐标转换转发）。
- 旧方案按钮（btnHeightUp/Down、btnAngelFront/Back）及其进度/边界 UI（updateDriverSeatAdjustHeightUI 等）整体注释停用；座椅 CAN 状态置灰 observe 也被注释（待接新 UI）。
- 资源：11 帧光标序列图、seat_main_bg、adjust_up_able/disable、popup_seat_adjust.xml；`selector_common_round_btn` 更名。
- 数据流：长按 → PopupWindow → SeatAdjustTouchView 手势 → onPullUp/Down 发 propertyId=1/2（连续）→ onRelease 发 0 停止。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+        popup.showAsDropDown(anchor, xOff, yOff, Gravity.NO_GRAVITY)
+        // 长按触发时手指仍按着，弹窗弹出后把"按下"事件模拟派发给调节 View，
+        // 使后续 MOVE/UP 由调节 View 接管，避免底层页面滚动、并保证松手正常关闭弹窗
+        longPressHandoff = true
+        dispatchHandoffDown(touchView)
+    }
+
+    private fun dispatchToSeatTouchView(event: MotionEvent) {
+        val touchView = currentSeatTouchView ?: return
+        val loc = IntArray(2).also { touchView.getLocationOnScreen(it) }
+        val forwarded = MotionEvent.obtain(event)
+        forwarded.setLocation(event.rawX - loc[0], event.rawY - loc[1])
+        touchView.dispatchTouchEvent(forwarded)
+        forwarded.recycle()
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/widget/SeatAdjustTouchView.kt
+    // 检测"停止滑动不松手"：MOVE 时重置，超时未动则回落高亮
+    private val idleRunnable = Runnable {
+        if (isAdjusting) {
+            setArrowHighlight(DIRECTION_NONE)
+        }
+    }
```
触摸交接是本提交最有技术含量的部分：PopupWindow 弹出后系统不会把原按住的手指事件转给弹窗，作者用 `MotionEvent.obtain` 伪造 DOWN、后续 MOVE/UP 换算 rawX/rawY 转发，并配合 `requestDisallowInterceptTouchEvent` 阻断 NestedScrollView 抢事件、`isTouchModal=true` 防止弹窗外穿透，三件套保证手势链路完整。

## 复盘与要点
- "长按弹窗 + 触摸序列手动交接"是处理 PopupWindow/Dialog 弹出时手指未松开的标准解法，可复用于任何"长按进入拖动模式"的交互。
- 自绘控件把动效规格直接写进 KDoc（时长/透明度逐项对应设计稿），美术走查与代码对照成本低，值得效仿。
- 遗留风险：大量旧逻辑以注释方式保留（updateDriverSeatAdjustHeightUI、座椅 CAN 置灰 observe），新 UI 尚未接 CAN 状态与回显，属于过渡态，后续提交（0699508d）继续补前置条件。
