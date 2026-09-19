# SIR-7506 · 点击 DOCK 栏设置，车辆设置偶现卡在半屏
- **提交**：`69a1beb7` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 主交互（rc：下拉退出逻辑问题）

## 问题
偶现：点击 DOCK 栏打开车辆设置时，界面停在半屏偏移位置（下拉退出的中间态），无法完整显示。

## 根因分析
车辆设置页面用 `LapseTouchLayout`（component/CommonTools）实现"下拉退出"手势，内部 `LapseTouchHelper` 通过 `animator` 驱动 `translationY`，配合 `isTouched/isDragging/isTriggered` 状态机判断退出触发。偶现的卡半屏机制是：用户下拉触发退出、Activity 正在执行退出/切换动画时页面被切到后台，`animator` 未播放完就被中断，`translationY` 残留非 0 的中间偏移；再次从 DOCK 进入时没有任何"重置回原位"的入口，页面就以半屏偏移态显示——即提交注释所写"拖拽/退出动画在后台未结束时 translationY 残留，再次进入未重置"。原实现只在 `onExitCancel()` 等手势收尾路径复位，覆盖不了"后台被打断"这条异常路径。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt、component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt
```diff
--- component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt
@@ -65,6 +65,17 @@
+    /**
+     * 回到初始位置：取消可能残留的动画并清空偏移。
+     * 修复偶现的"Activity 切后台后界面偏移/半屏"问题
+     * （拖拽/退出动画在后台未结束时 translationY 残留，再次进入未重置）。
+     */
+    fun resetPosition() {
+        touchHelper.reset()
+        translationY = 0f
+        currentTranslationY = 0f
+    }
+
+    override fun onAttachedToWindow() {
+        super.onAttachedToWindow()
+        resetPosition()
+    }
+
+    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
+        super.onWindowFocusChanged(hasWindowFocus)
+        if (hasWindowFocus && (translationY != 0f || currentTranslationY != 0f)) {
+            resetPosition()
+        }
+    }
--- component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt
@@ -220,6 +220,19 @@
+    /**
+     * 重置拖动/动画状态，用于视图重新附着或重新获得焦点时回到初始位置
+     */
+    fun reset() {
+        animator?.cancel()
+        animator = null
+        velocityTracker?.recycle()
+        velocityTracker = null
+        isTouched = false
+        isDragging = false
+        isTriggered = false
+    }
```

## 为什么能修复
把"复位"挂到两个必然到达的生命周期点上：重新附着窗口（`onAttachedToWindow`）无条件重置；重新获得窗口焦点（`onWindowFocusChanged(hasWindowFocus=true)`）时只要检测到偏移残留就重置——`reset()` 取消残留动画、回收 VelocityTracker、清三个手势布尔位，`translationY/currentTranslationY` 归零，页面必然回到完整屏位置。由于是残留才重置，正常交互不受影响。隐患：若未来出现"期望进入时保持上次偏移"的场景会与之冲突；另外 `reset()` 里 `animator?.cancel()` 会触发 animator 的 cancel 回调链，需确保取消回调里没有副作用逻辑（本控件内未发现）。

## 复盘经验
- 靠动画驱动 View 属性（translationY/alpha）做手势效果，必须为"动画被打断"设计复位路径；onAttachedToWindow / onWindowFocusChanged 是兜底重置的最佳挂点。
- "偶现"类半屏/错位问题，优先怀疑后台切换时动画中断留下的脏属性，而不是渲染 bug。
- 偏移量恢复要同时清 View 属性（translationY）和逻辑状态（isDragging 等），只清一边下次手势仍会异常。
