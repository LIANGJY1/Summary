# 无单号 · 非 P 档时 HUD 高度/角度/亮度置灰

- **提交**：`05f72b68` | 2026-07-29 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
车辆不在 P 档时，把车控页 HUD 亮度、高度、角度三个滑动调节项置灰（alpha 0.3）并禁止滑动，满足行车安全交互约束。

## 实现结构
- `component/Carlib/.../CarPropertyIds.kt`：修正 `PCU_ACTUALGEARFEED` 属性 ID 5904 → 6108
- `SettingVehicleService.kt`：订阅属性清单加入 `PCU_ACTUALGEARFEED`，新增 `pcuActualGear: MutableLiveData<Int>`，信号回调 map 中 postValue
- `VehicleControlFragment.kt`：`setHudObserver()` 增加档位监听，`gear==0`（P 档）时调 `updateHudState(true)`；新增 `updateHudState()` 对三个 include 滑条统一设置 alpha 与 `isControlEnabled`
- `GearSwitchViewNew.kt`：新增 `setSlideEnabled()` 公开方法包装 `isControlEnabled`

数据流：CAN 信号 `PCU_ACTUALGEARFEED` → 服务层 LiveData → Fragment Observer → 三个 GearSwitchViewNew 滑条 alpha/开关。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -337,6 +338,14 @@
+        //档位监听
+        settingVehicleService.pcuActualGear.observe(
+            viewLifecycleOwner, Observer { gear ->
+                logObserve("Received gear change: $gear")
+                updateHudState(gear==0)
+            }
+        )
+
+    fun updateHudState(enabled: Boolean) {
+        val alpha = if (enabled) 1.0f else 0.3f
+        mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.apply {
+            this.alpha = alpha
+            isControlEnabled = enabled
+        }
+        mBinding.driverHudLayout.includeSeekbarHudHeight.gearHeight.apply {
+            this.alpha = alpha
+            isControlEnabled = enabled
+        }
+        mBinding.driverHudLayout.includeSeekbarHudCorner.gearCorner.apply {
+            this.alpha = alpha
+            isControlEnabled = enabled
+        }
+    }
```
实现讲解：沿用 Setting 模块"信号订阅表 + LiveData 转发 + Fragment observe"的既有数据通道，只加一个属性就接通链路；置灰用 alpha+isControlEnabled 双管齐下（视觉+交互）。顺带把 `PCU_ACTUALGEARFEED` 从 5904 修正为 6108，属于随功能联调发现的协议号勘误。

## 复盘与要点
- "gear==0 即 P 档"的魔法数字直接内联在 Observer 里，建议后续抽常量（如 GEAR_P=0）提升可读性。
- `setHudObserver()` 开头新增 `if (!hasHudAdjustment)return` 早退，说明无 HUD 配置的车型要跳过整套 HUD 观察，避免空视图操作。
- 可复用手法：`updateHudState()` 统一处理三个 include 控件的"视觉置灰 + 交互禁用"，其他安全联锁（如行车禁调座椅）可直接套用。
