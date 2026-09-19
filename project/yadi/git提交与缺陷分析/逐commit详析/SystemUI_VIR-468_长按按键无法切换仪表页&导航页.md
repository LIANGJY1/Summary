# VIR-468 · 长按按键无法切换仪表页&导航页

- **提交**：`3f9ec253` | 2026-08-21 | ljl | SystemUI | bugfix（信号通路变更适配）
- **缺陷库**：defs 为空（关联需求单 VIR-468，无缺陷库条目）

## 问题
长按方向盘 L 滚轮按键无法触发仪表页/导航页切换，功能完全失效。

## 根因分析
提交说明明确："由于原信号被拦截，只能使用其他接口，需要适配新接口"。底层 `InputHalService` 订阅了 `ROLLER_PRESS_SWITCH`（0x21400000 系，L 滚轮按压）车辆属性后，该属性不再通过 `CarPropertyManager` 向应用上报，而是被转换为 `KEYCODE_MEDIA_PLAY_PAUSE`(KeyCode=85) 按键事件分发。`DigitalKeyVehicleService` 原来在 `onChangeEvent` 里监听 `ROLLER_PRESS_SWITCH` 的按下(1)/释放(2)并用 Handler 实现 1.5s 长按判定，信号被 HAL 拦截后这条路径永远收不到值，长按判定自然失效。修复改走输入事件通路：通过 `Car.getCarManager(Car.CAR_INPUT_SERVICE)` 获取 `CarInputManager`，`requestInputEventCapture(DISPLAY_TYPE_MAIN, INPUT_TYPE_ALL_INPUTS, CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT, ...)` 捕获按键，在回调里过滤 `KEYCODE_MEDIA_PLAY_PAUSE`，用 `handleRollerPressKeyEvent()` 按 ACTION_DOWN（`repeatCount != 0` 的重复事件跳过以免重置计时）/ACTION_UP 重现同样的 1.5s 长按判定并触发 `StateEventRouter.onHardKeyLongPress()`；销毁时 `releaseInputEventCapture` 释放。原属性监听分支删除并在订阅列表注释说明原因。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt（+88/-28）
```diff
@@ DigitalKeyVehicleService.kt 新增按键捕获 @@
+    private val mCarInputCaptureCallback = object : CarInputManager.CarInputCaptureCallback {
+        override fun onKeyEvents(targetDisplayType: Int, keyEvents: List<KeyEvent>) {
+            keyEvents.forEach { event ->
+                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
+                    handleRollerPressKeyEvent(event)
+                }
+            }
+        }
+    }
...
+        val result = carInputManager.requestInputEventCapture(
+            CarOccupantZoneManager.DISPLAY_TYPE_MAIN,
+            intArrayOf(CarInputManager.INPUT_TYPE_ALL_INPUTS),
+            CarInputManager.CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT,
+            mMainExecutor,
+            mCarInputCaptureCallback
+        )
```
```diff
@@ DigitalKeyVehicleService.kt 长按判定迁移 @@
     private fun handleRollerPressKeyEvent(event: KeyEvent) {
         when (event.action) {
             KeyEvent.ACTION_DOWN -> {
+                // 忽略重复事件（长按期间系统可能持续上报 ACTION_DOWN），避免重置计时器
+                if (event.repeatCount != 0) {
+                    return
+                }
                 mRollerPressStartTime = SystemClock.elapsedRealtime()
                 mIsRollerLongPressTriggered = false
                 mMainHandler.postDelayed(mRollerLongPressRunnable, 1500L)
```

## 为什么能修复
长按检测的数据源从"已断供的车辆属性"切换到"HAL 实际分发按键事件"的通路，判定逻辑本身（1.5s 阈值、release 兜底触发）原样保留，功能恢复。隐患：`INPUT_TYPE_ALL_INPUTS` 全量捕获所有输入事件，可能与其他组件的捕获请求冲突（CarInputManager 的捕获授权策略决定谁拿到事件），需关注并发捕获场景；长按期间键事件被 SystemUI 消费后音乐播放/暂停语义也被接管。

## 复盘与经验
- 车辆属性的"消费权"可能在底层被 HAL 改变（property → keycode 转换），应用层突然收不到信号时先怀疑上游通路被截胡，而不是自己代码回退。
- 适配通路变更时，把业务判定逻辑保持不变、只换数据源（属性回调→按键回调），是改动面最小的迁移方式。
- 用 KeyEvent 实现长按，务必处理 `repeatCount` 重复 DOWN 事件，否则计时器被反复重置、长按永远判定失败。
