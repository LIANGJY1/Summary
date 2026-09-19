# SIR-5982 · HUD亮度拖动过程中不实时变化，松手才生效

- **提交**：`76087170` | 2026-08-19 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置页手动调节 HUD 亮度时，滑到目标档位松手后亮度才变化；而控制中心的同款调节是拖动过程中实时变化，两处交互不一致。

## 根因分析
问题核心在自定义滑杆控件 `GearSwitchViewNew` 的 XML 属性配置：`seekbar_setting_hud_brightness_new.xml` 中 `app:realTimeCallback="false"`，导致 `onGearChangeListener` 只在停止拖动（松手）时回调一次，`setupHudBrightnessListener()` 里的 `settingVehicleService.sendL2A(CarPropertyIds.HUD_BRIGHT_ADJUST, gear)` 自然只在松手时才下发信号。控制中心未关闭该属性所以体验实时。同时本次把原来的"回弹 Job"（拖动后若车辆未确认则回滚 UI 的超时机制）从 Fragment 内散落的 `hudBrightnessReboundJob` 手工管理，重构为按属性 ID 键控的公共工具 `ReboundHelper`（start/cancel/hasTask），避免实时回调多次触发后旧回弹任务误伤新状态。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/seekbar_setting_hud_brightness_new.xml、application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt（2 文件，+22/-21）
```diff
@@ application/Setting/src/main/res/layout/seekbar_setting_hud_brightness_new.xml @@
-            app:realTimeCallback="false"
+            app:realTimeCallback="true"
```
```diff
@@ VehicleControlFragment.kt 回弹任务改用 ReboundHelper 统一管理 @@
-                startReboundJob(
-                    getJob = { hudBrightnessReboundJob },
-                    setJob = { newJob -> hudBrightnessReboundJob = newJob },
-                    action = {
-                        if (hudBrightnessState != hudBrightnessTemp) {
-                            logObserve("...")
-                            updateHudBrightnessUI(previousBrightness)
-                        }
-                        hudBrightnessTemp = -1
-                    })
+                ReboundHelper.start(
+                    CarPropertyIds.HUD_BRIGHT_ADJUST,
+                    viewLifecycleOwner.lifecycleScope
+                ) {
+                    if (hudBrightnessState != hudBrightnessTemp) {
+                        logObserve("HUD brightness timeout rollback to $hudBrightnessState")
+                        updateHudBrightnessUI(hudBrightnessState)
+                    }
+                    hudBrightnessTemp = -1
+                }
```
```diff
@@ VehicleControlFragment.kt 车辆确认到达时取消回弹 @@
-            hudBrightnessReboundJob?.cancel()
-            hudBrightnessReboundJob = null
-            mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.setCurrentGear(brightness, true)
             hudBrightnessState = brightness
+            if (hudBrightnessTemp == brightness) {
+                ReboundHelper.cancel(CarPropertyIds.HUD_BRIGHT_ADJUST)
+            }
+            if (!ReboundHelper.hasTask(CarPropertyIds.HUD_BRIGHT_ADJUST)) {
+                mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.setCurrentGear(brightness, true)
+            }
```

## 为什么能修复
`realTimeCallback="true"` 让滑杆在拖动过程中持续回调，每次档位变化立即 `sendL2A` 下发，交互对齐控制中心。配套的 `ReboundHelper` 按属性 ID 管理超时回滚：车辆回执（`updateHudBrightnessUI`）与临时值一致时取消回弹，超时未确认仍回滚到 `hudBrightnessState`，防止实时下发后信号丢失导致 UI 与实车状态不一致。隐患：拖动过程高频下发车控信号，依赖下游做节流/去重。

## 复盘与经验
- 自定义控件的可配置行为（如 realTimeCallback）散落在各布局 XML 里，同一控件多处使用时交互不一致的高发点，应拉齐默认值或统一封装。
- "实时下发 + 超时回滚"是车控滑杆的标配模式，回弹任务必须与业务键（属性 ID）绑定而不是散落的成员变量，否则多个滑杆复制粘贴后极易出错。
- 交互一致性 bug 的排查入口：对比两处"应该一样"的功能实现差异，往往一行配置即是答案。
