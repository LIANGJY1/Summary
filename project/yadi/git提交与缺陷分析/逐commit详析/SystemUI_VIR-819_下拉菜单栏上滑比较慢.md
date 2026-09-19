# VIR-819 · 负一屏（下拉通知栏）上滑退出响应慢
- **提交**：`6cd2b433` | 2026-09-08 | caohongliang | SystemUI | bugfix
- **缺陷库**：未关联单号（元数据 VIR-819，defs 数组为空）

## 问题
下拉菜单栏（负一屏）上滑退出时手感迟钝：面板要等手指抬起（ACTION_UP）后才执行收起，用户感知"上滑比较慢"。

## 根因分析
上滑收起逻辑全部挂在 `MotionEvent.ACTION_UP` 分支：`NotificationCenterFragment.bindGestures()` 中 `downY - event.y > SWIPE_HIDE_DISTANCE` 的判断放在 UP 里，`NoClickOnScrollLayout.onTouchEvent` 也是 MOVE 时仅置位 `mIsScrolled`、UP 时才回调 `mScrollListener?.onScrollEnd()`；`StatusBarPanelView.onTouch` 同样只在 UP/CANCEL 时计算 `dy` 决定是否收起。即"到了阈值但不抬手就不收起"，与下拉展开（到阈值即执行）的交互不对称。另一个结构性问题：`rootView` 原来用 `setOnTouchListener` 挂手势，而空白区域 `onTouchEvent` 返回值不定，子 View/空白区不消费时后续 MOVE 可能不再派发到手势监听，导致滑动跟踪中断、更难达到阈值。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/widget/NoClickOnScrollLayout.kt；application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterLayout.kt（新增）；application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt；application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/StatusBarPanelView.kt；application/SystemUI/src/main/res/layout/notification_center.xml
```diff
--- application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/StatusBarPanelView.kt
@@ -182,7 +192,14 @@
                 MotionEvent.ACTION_MOVE -> {
-                    // 不再跟手平移，仅消费事件
+                    val dy = mViewDownY - motionEvent.y
+                    val elapsed = SystemClock.elapsedRealtime() - mViewDownYTime
+                    // 快速上滑达到原有距离条件后立即收起，不等待手指抬起
+                    if (!mHideTriggered && dy > 5 && elapsed < 300) {
+                        mHideTriggered = true
+                        LogUtils.d(TAG, "onTouch: swipe-up threshold reached, hide panel")
+                        animationHide()
+                    }
                 }
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/widget/NoClickOnScrollLayout.kt
@@ -36,20 +35,28 @@
     override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
         mDispatchTouchEventListener?.onDispatchTouchEvent(ev)
-        return super.dispatchTouchEvent(ev)
+        when (ev.action) {
+            MotionEvent.ACTION_MOVE -> {
+                val swipeUpDistance = mDownY - ev.y
+                // 上滑超过阈值后立即收起面板，不等待手指抬起
+                if (!mIsScrolled && swipeUpDistance > SCROLL_THRESHOLD) {
+                    mIsScrolled = true
+                    mScrollListener?.onScrollEnd()
+                }
+            }
+        }
+        val consumeGesture = mIsScrolled
+        val handled = super.dispatchTouchEvent(ev)
+        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
+            mIsScrolled = false
+        }
+        return consumeGesture || handled
     }
```
同时：`NotificationCenterFragment.bindGestures()` 重写为 `OnPanelGestureListener`，MOVE 阶段达到 `SWIPE_HIDE_DISTANCE` 立即 `hideNotificationCenter()`，并且手势若起始于通知列表内（`isTouchInsideView(notificationListView, event)`）则不处理面板手势，避免列表滚动误收起；新增 `NotificationCenterLayout` 在 `dispatchTouchEvent` 分发阶段跟踪手势、`onTouchEvent` 恒返回 true 认领空白区域保证 MOVE 连续；布局根节点 `RelativeLayout` 换成 `NotificationCenterLayout`。

## 为什么能修复
三处收起入口（快捷设置根布局、通知页根布局、状态栏面板）统一从"抬手判定"改为"MOVE 阈值判定"，手指到位即刻收起，交互与下拉展开对齐，迟滞感消除。`NotificationCenterLayout` 认领空白区域事件解决了 MOVE 断流问题；`gestureStartedInNotificationList` 排除列表内手势、`mHideTriggered` 防重复触发，规避了新时序引入的误收起和双重动画。隐患：快速滑动误触概率上升（阈值 dy>5+300ms 内判定较敏感），需实车验证列表滚动、点击快捷开关不受影响。

## 复盘与经验
- 面板类手势"到阈值即执行、不等待抬手"是车机交互的常见要求；把状态变更挂在 ACTION_UP 会天然带一个抬手延迟。
- `View.OnTouchListener` 挂在根布局上并不可靠：空白区域不消费事件时 MOVE 流会断；在 `dispatchTouchEvent` 分发阶段统一跟踪 + `onTouchEvent` 返回 true 认领是稳态方案。
- 手势升级（阈值提前触发）必须同时处理事件归属（列表内 vs 列表外）与防重触发（mHideTriggered），否则修一个慢、引入两个误触。
