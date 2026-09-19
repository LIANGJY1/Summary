# SIR-6779 · 应用中心持续滑动仍被判定 10s 无操作退出
- **提交**：`50cf3fe7` | 2026-08-31 | caohongliang | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
手指按住屏幕不抬、持续上下滑动浏览应用中心（applist）时，仍会被判定"10 秒无操作"，自动退回 3D 驻车桌面。

## 根因分析
应用中心的空闲超时检测依赖 `TouchInterceptConstraintLayout.dispatchTouchEvent` 抛出的 `onDownTouch` 回调去重置倒计时。原实现只在 `ev.action == MotionEvent.ACTION_DOWN` 时触发回调——即一次触摸序列里只有第一下按下会重置计时器；之后的 `ACTION_MOVE`（滑动）、`ACTION_UP` 都不重置。只要手指按住不抬地连续滑动，DOWN 后计时器便不再刷新，10 秒一到照样触发超时返回，尽管用户全程"在操作"。根因正是单据所写"滑动时未重置计时器"。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/widget/TouchInterceptConstraintLayout.kt
```diff
--- a/.../launcher/widget/TouchInterceptConstraintLayout.kt
     override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
         // 优先捕获ACTION_DOWN事件
-        if (ev.action == MotionEvent.ACTION_DOWN) {
+        if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
             onDownTouch?.invoke() // 触发回调，重置倒计时
         }
         // 继续传递事件给子View（不影响TabLayout/Fragment交互）
         return super.dispatchTouchEvent(ev)
     }
```

## 为什么能修复
`ACTION_MOVE` 是滑动期间高频派发的事件，把它纳入重置触发后，只要手指仍在屏上移动，倒计时就会不断归零，超时判定只会发生在真正的操作间歇；事件仍走 `super.dispatchTouchEvent` 继续分发，不影响子 View 交互。隐患：MOVE 事件频率很高（每秒数十至上百次），若 `onDownTouch` 回调链路重（如跨线程投递或触发重绘），会有无谓开销——这里仅重置计时器，代价可忽略；如追求极致可加"距上次重置超过 N 秒才回调"的节流。

## 复盘与经验
- "空闲判定"必须覆盖完整触摸序列（DOWN/MOVE/POINTER_DOWN 等），只监听 DOWN 是这类缺陷的头号模式；按住不抬的滑动是最容易绕过 DOWN-only 检测的路径。
- 在容器 `dispatchTouchEvent` 统一埋重置点，比在各子 View 分散监听更完备，本例的自定义容器正是为此而生，只差一个事件类型。
- 高频事件回调要考虑成本，但"重置计时器"这类幂等轻操作可直接执行，不必过度设计节流。
