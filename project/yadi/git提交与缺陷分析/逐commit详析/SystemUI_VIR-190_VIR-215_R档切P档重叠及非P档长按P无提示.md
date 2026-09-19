# VIR-190/VIR-215 · R 档切 P 档图标重叠及非 P 档长按 P 无提示
- **提交**：`956f8f82` | 2026-08-15 | ljl | SystemUI | bugfix（一提双修）
- **缺陷库**：未关联缺陷记录（VIR-190/VIR-215 无 defs 数组条目）

## 问题
1) R 档切 P 档时，P 档与 Home 键显示重叠；2) 非 P 档长按 P（锁车关机）车机不弹"请在 P 档下锁车关机"提示。

## 根因分析
两个独立缺陷：
1. **R 档转场复用 D 档延迟**：`PageStateMachine` 对 `Event.Gear_R` 原本"不做状态切换"，走的是与 D 档相同的 Launcher 转场延迟路径（`enterS1WithLauncherDelay` 系延迟后才 `setMeterForm(1)` 切仪表）。用户挂 P 回 Launcher 时，R 档遗留的延迟切仪表任务仍在队列中，页面叠加导致 P 档与 Home 键显示重叠。
2. **standby 电源状态不走 Carlib 通道**：`checkStandby1GearState()` 依据 `currentPowerState`（0x02/0x03，源自 Carlib 的 `MCU_REPLY_POWER_STATUS(6107)`）在非 P 档时弹安全 Toast；但 standby1/standby2 场景的电源状态实际由 `CarPowerManagementService` 经框架 `CarPowerManager` 下发，6107 通道收不到事件，`checkStandby1GearState` 根本没被触发，提示缺失。

## 关键代码修改
改动文件：`application/SystemUI/src/main/AndroidManifest.xml`、`digitalkey/DigitalKeyVehicleService.kt`、`digitalkey/mainaction/PageStateMachine.kt`
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
                 LogUtils.d(TAG,"[IGNORE] Plugged-in state, Gear_R suppressed")
                 return
             }
-                //R档不做状态切换，仅隐藏显示状态栏
+                //R档直接通过AL通知切换仪表页面（S1），不做Launcher转场延迟等待（区别于D档延迟）
+                handleGearR()
                 return
```
（新增 `handleGearR()`：仅当 `curState == State.S0_Android_Park` 时执行，按 `navForeground`/`SubMode.Pano_B1` 分支直接 `showLinuxDashboard(ctx.subMode)`/`showAndroidNavPage()` 并 `transitionTo`，不走 D 档的延迟等待。）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
+    private val mCarPowerStateListener = object : CarPowerManager.CarPowerStateListener {
+        override fun onStateChanged(state: Int) {
+            currentPowerState = mapCarPowerState(state)
+            checkStandby1GearState()
+        }
+    }
+    private fun mapCarPowerState(state: Int): Int = when (state) {
+        CarPowerManager.STATE_STANDBY_1 -> 0x02
+        CarPowerManager.STATE_STANDBY_2 -> 0x03
+        else -> -1
+    }
```
（`registerCarPowerStateListener()` 在 Car 就绪后注册 `Car.POWER_SERVICE` 管理器，失败按 500ms 重试最多 3 次；`onDestroy` 中 `clearListener()`；Manifest 增加 `<uses-permission android:name="android.car.permission.CAR_POWER" />`。）

## 为什么能修复
R 档改为即时切仪表并迁移到 S1 状态，消除了"延迟任务跨越换挡事件存活"的时序窗口，挂 P 时状态机处于干净状态，重叠不再出现（`curState` 守卫同时防重复切换）；电源侧补上框架 `CarPowerManager` 监听并把 `STATE_STANDBY_1/2` 映射回 0x02/0x03 值域，`checkStandby1GearState` 的既有逻辑原样生效，非 P 档长按 P 能正常弹提示。隐患：`mapCarPowerState` 对其他状态返回 -1 会覆盖 `currentPowerState`，若 6107 通道与框架状态并存可能互相覆盖，需保证状态源单一。

## 复盘与经验
- 档位切换类转场"哪些档需要等动画、哪些必须立即切"应显式分档实现，复用 D 档延迟逻辑给 R 档（倒车影像优先级更高）是本类重叠/迟滞问题的典型来源。
- 同一业务状态（电源 standby）可能有多个上报通道（MCU CAN 信号 vs 框架 CarPowerManager），场景排查要覆盖全部下发路径，漏一条通道就是"偶发无提示"。
- 监听框架服务要做好获取失败重试与生命周期清理（本例的 3 次重试 + clearListener 是完整范本）。
