# SRS_VehSetting_010 · 修改座椅调节逻辑

- **提交**：`df73b3e3` | 2026-08-31 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_010（标题引用，无系统单号）

## 需求/目标
按最新交互定义调整座椅调节的行车禁用规则：从"非P档 && 车速≥5 && 速度无效"简化为"车速>3 && 速度无效"，去掉档位条件；座椅名称编辑入口（编辑笔）跟随总开关/行车状态独立置灰；可调状态判断中移除自学习信号。

## 实现结构
仅改 `VehicleControlFragment.kt`（+34/-15）：
- `refreshSeatDrivingState()`：删除 `pcuActualGear` 因子，`drivingDisabled = (speed > 3f) && (valid == 0)`；对应的 `pcuActualGear.observe` 订阅被注释掉。
- 新增 `refreshSeatEditState()`：`ivEditPosition1~3` 三个编辑图标按 `!isSeatControlEnabled || drivingDisabled` 独立置灰，并在 CAN 状态回调与行车状态刷新两处挂接（呼应 fab40dbe 中从全局 alpha 中摘除编辑图标的伏笔——至此编辑图标有了自己的刷新函数）。
- `refreshSeatAdjustableState()`：height/angle 禁用链移除 `motorSelfLearnFaulted/cushionMotorSelfLearnFaulted`，注释同步更新。
- drivingDisabled 注释从硬编码公式改为业务描述。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -1505,15 +1507,16 @@
     private fun refreshSeatDrivingState() {
-        val gear = settingVehicleService.pcuActualGear.value ?: 0
         val speed = settingVehicleService.vehicleSpeed.value ?: 0f
         val valid = settingVehicleService.vehicleSpeedValid.value ?: 1
-        drivingDisabled = (gear != 0) && (speed >= 5f) && (valid == 0)
+        drivingDisabled = (speed > 3f) && (valid == 0)
         refreshSeatAdjustableState()
         refreshSeatPositionState()
         refreshSeatSaveState()
+        refreshSeatEditState()
     }
```
```diff
+    private fun refreshSeatEditState() {
+        val disabled = !isSeatControlEnabled || drivingDisabled
+        seatAdjustmentBinding?.apply {
+            val alpha = if (disabled) 0.3f else 1.0f
+            ivEditPosition1.alpha = alpha
+            ivEditPosition1.isEnabled = !disabled
+            ...
+        }
+    }
```

实现讲解：本质是"禁用规则的需求变更"：车速阈值 5→3、去掉档位维度、移除自学习禁用源。实现上顺势把编辑入口从公共 alpha 逻辑中独立成 `refreshSeatEditState()`，四类控件（调节/保存/编辑/总开关）各有一个 refresh 函数，规则演进时互不牵连。

## 复盘与要点
- 把禁用阈值（3km/h）与有效性判断写成一行组合式便于评审核对，但阈值仍是魔法数字，建议提为命名常量并与湿滑模式的 3km/h（9a18e629）统一管理。
- 被注释掉的 `pcuActualGear.observe` 是"规则可能回退"的信号，两周内同类注释已开始堆积（fc29efff 也有），宜用 revert 代替注释。
- 座椅模块五天四改（fab40dbe→本提交），说明置灰规则处于快速对齐期；每个 refresh 函数单一职责的拆分让这些变更都限制在小 diff，是本次演进最大的工程收益。
