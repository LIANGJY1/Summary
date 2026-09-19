# VIR-994 · 亮度滑块拖出限定范围后丢失触感/跟手
- **提交**：`0a028b8c` | 2026-09-15 | sgh | Setting | bugfix
- **缺陷库**：未关联缺陷库记录（单号 VIR-994，无 defs 详情）

## 问题
屏幕亮度滑块拖动过程中，手指一旦移出控件限定的触摸区域（纵向超过轨道高度 2 倍等），滑块停止跟手、"丢失触感"，需重新按下才能继续拖动。

## 根因分析
`application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt` 的 `handleTouchMove` 中，原逻辑先计算 `isWithinValidTouchArea`（纵向 `trackCenterY ± trackHeight*2`、横向 `trackLeft-thumbWidth .. trackRight+thumbWidth`），并把该条件同时用于两处：进入拖动（`abs(deltaX) > touchSlop && isWithinValidTouchArea`）和拖动中的位置更新（`if (isWithinValidTouchArea) { dragCurrentPosition = ... }`）。Android 触摸事件流中手指按住拖动滑出 View 边界后仍会持续派发 `ACTION_MOVE`，但坐标落在区域外时更新分支被整体跳过，`dragCurrentPosition` 冻结、`invalidate()` 也不执行，滑块视觉停住、触觉反馈中断——"必须在限定范围以内互动"正是对这组条件的复述。触摸命中判定被误用作拖动过程中的持续有效性判定，是根因。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt
@@ -719,22 +719,20 @@
     private fun handleTouchMove(event: MotionEvent): Boolean {
         if (!isDragging) return false
 
-        val isInVerticalRange = event.y in (trackCenterY - trackHeight * 2)..(trackCenterY + trackHeight * 2)
-        val isInHorizontalRange = event.x in (trackLeft - thumbWidth)..(trackRight + thumbWidth)
-        val isWithinValidTouchArea = isInVerticalRange && isInHorizontalRange
         val deltaX = event.x - dragStartX
 
-        if (!hasTriggeredDrag && kotlin.math.abs(deltaX) > touchSlop && isWithinValidTouchArea) {
+        // 手指按下滑块后，纵/横向移出控件范围也应继续跟手拖动（对齐系统 SeekBar 行为），
+        // 因此不再用"有效触摸区域"限制拖动，仅以横向位移是否超过 touchSlop 判断是否进入拖动，
+        // 越界位置由下面的 coerceIn(trackLeft, trackRight) 兜住
+        if (!hasTriggeredDrag && kotlin.math.abs(deltaX) > touchSlop) {
             hasTriggeredDrag = true
             onGearChangeStartListener?.onGearChangeStart()
         }
 
         if (hasTriggeredDrag) {
-            if (isWithinValidTouchArea) {
-                dragCurrentPosition = (dragStartPosition + deltaX).coerceIn(trackLeft, trackRight)
-                if (realTimeCallback) {
-                    updateGearFromPosition(dragCurrentPosition, notifyListener = true)
-                }
+            dragCurrentPosition = (dragStartPosition + deltaX).coerceIn(trackLeft, trackRight)
+            if (realTimeCallback) {
+                updateGearFromPosition(dragCurrentPosition, notifyListener = true)
             }
             invalidate()
         }
```

## 为什么能修复
删除拖动全程的区域限制：只要 `hasTriggeredDrag` 成立就持续用 `deltaX` 更新位置，手指移出控件后滑块仍按横向位移跟手；滑块不会被拖出轨道由既有的 `coerceIn(trackLeft, trackRight)` 钳制兜底。行为对齐系统 SeekBar（拖动期间越界不中断）。副作用：手指大幅纵向偏移时滑块仍横向跟手（此前会冻结），属于向系统控件行为看齐的有意变化；亮度实时回调 `notifyListener=true` 的触发频率不变。

## 复盘与经验
- 自定义拖拽控件要区分"按下时命中判定"与"拖动中有效性判定"：拖动一旦开始，坐标越界不应中断交互，位置由钳制函数兜底（系统 SeekBar/Slider 皆如此）。
- "丢触感/拖一半卡住"类问题优先检查 ACTION_MOVE 处理链里是否有区域条件守卫短路了状态更新。
- 越界钳制（coerceIn）放在"更新位置"处而不是"是否更新"处，职责才正确。
