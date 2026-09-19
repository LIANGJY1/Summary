# SIR-7808 · 预约充电分钟滚轮滑动调整时间界面卡顿
- **提交**：`08ed385e` | 2026-09-09 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
预约充电设置时间界面，分钟侧滚轮（WheelScrollView）滑动调整时间时界面卡顿、松手瞬间可见跳变。

## 根因分析
自定义滚轮控件 `WheelScrollView`（ViewGroup）有两个叠加问题：① `settleAfterRelease()` 在松手时根据 `dragOffsetY` 是否越过 `itemStepHeight/2` 直接把 `selIndex` 拨一格并立刻 `dragOffsetY = 0`——偏移量瞬间归零，内容位置发生整格跳变，视觉上即"卡顿/闪跳"；② `normalizeDragOffset()` 每次跨档都调用 `updateVisibleItems()` 重绑文字，且循环外还固定调用 `applyTextState()`，拖动过程中重复刷新文字状态，产生无效重绘开销。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/WheelScrollView.java
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/WheelScrollView.java
@@ -165,31 +171,79 @@
     private void settleAfterRelease() {
+        int settledIndex = selIndex;
+        int targetOffsetY = 0;
         if (dragOffsetY >= itemStepHeight / 2) {
-            selIndex = wrapIndex(selIndex - 1);
+            settledIndex = wrapIndex(selIndex - 1);
+            targetOffsetY = itemStepHeight;
         } else if (dragOffsetY <= -itemStepHeight / 2) {
-            selIndex = wrapIndex(selIndex + 1);
+            settledIndex = wrapIndex(selIndex + 1);
+            targetOffsetY = -itemStepHeight;
         }
+        if (dragOffsetY == targetOffsetY) {
+            completeSettle(settledIndex);
+            return;
+        }
+        animateToSettledOffset(targetOffsetY, settledIndex);
+    }
+
+    private void animateToSettledOffset(int targetOffsetY, final int settledIndex) {
+        ValueAnimator animator = ValueAnimator.ofInt(dragOffsetY, targetOffsetY);
+        animator.setDuration(Math.max(80L, Math.min(160L,
+                Math.abs(targetOffsetY - dragOffsetY) * 2L)));
+        animator.setInterpolator(new DecelerateInterpolator());
+        animator.addUpdateListener(animation -> {
+            dragOffsetY = (int) animation.getAnimatedValue();
+            applyChildTranslation();
+        });
```
同时：`normalizeDragOffset()` 改为跨档后仅置位 `selectionChanged` 才调一次 `updateVisibleItems()`，并删除固定的 `applyTextState()`；新增 `completeSettle()` 统一收尾（selIndex/dragOffsetY/updateVisibleItems/applyChildTranslation）、`cancelSettleAnimation()` 在 ACTION_DOWN 取消进行中的吸附动画。

## 为什么能修复
松手吸附从"瞬间归零"改为 80~160ms、按距离自适应时长、DecelerateInterpolator 的 `ValueAnimator` 平滑滚动到档位（目标偏移为 ±itemStepHeight 或 0），动画结束才提交 selIndex 并重绑文字，跳变消失、手感连续；拖动期文字刷新收敛为"真正跨档才刷一次"，减少无效重绘。`cancelSettleAnimation()` 保证快速连续滑动时旧动画被取消、`dragOffsetY` 不会串台（animation end 里还有 `settleAnimator != animation` 防过期回调）。隐患：动画期间 dragOffsetY 处于中间态，若外部在动画中读取选中值可能拿到旧档位，需以动画结束后的 completeSettle 为准。

## 复盘与经验
- 滚轮/选择器类控件"松手直接归零"是典型跳变根因，短距离吸附动画（80~160ms + Decelerate）是标准解法，时长按剩余距离自适应更自然。
- 拖动路径上的高频回调（normalizeDragOffset）要做"状态是否变化"的门控，跨档才刷新 UI，避免逐帧重绑文字。
- 增加动画就要同步考虑打断：ACTION_DOWN 取消动画 + 动画结束回调校验归属（settleAnimator != animation），防止连续手势下状态错乱。
