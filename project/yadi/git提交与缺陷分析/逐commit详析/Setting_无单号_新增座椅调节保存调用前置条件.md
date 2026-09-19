# 无单号 [SRS_VehSetting_010] 新增座椅调节保存调用前置条件
- **提交**：`0699508d` | 2026-08-24 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_010

## 需求/目标
座椅调节/位置记忆/保存功能接入车端 8 个状态信号（防玩、热保护、双电机自学习、双电机故障、座椅/腰靠调节中）+ 行车禁用条件（非 P 挡 && 车速≥5 && 速度无效位），任一不满足即置灰对应按钮组；恢复此前被注释的座椅 CAN 状态置灰链路。

## 实现结构
- `SettingVehicleService.kt`：新增 8 个 SCU_* 状态 LiveData（防玩/热保护/自学习×2/故障×2/调节中×2）并接入订阅表；`PCU_VEHICLE_SPEED_VALID` 订阅取消注释正式启用；清理旧氛围灯注释块；SICommStateCallback 恢复日志并去掉 HUD 亮度去重过滤。
- `VehicleControlFragment.kt`（+539 行核心）：新增 11 个前置条件布尔成员；每个状态信号 observe → `updateSeatFault(signalIndex, isFaulted)` 统一更新并联动刷新；`refreshSeatDrivingState`（档位+车速+有效位三条件合成 drivingDisabled）；`refreshSeatPositionState`/`refreshSeatSaveState` 按"任一条件即置灰"聚合，KDoc 逐条列出条件，结尾打印全部状态值便于排查；19e1553b 遗留的旧按钮边界逻辑彻底删除/注释。
- `CarPropertyIds.kt`/`CarPropertyMapping.kt`：新增 SCU_* 信号 ID 与映射。
- 数据流：SCU 状态信号 → LiveData → updateSeatFault(缓存布尔) → refreshXxxState 聚合 → alpha/isEnabled + 全量日志。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+    private fun refreshSeatPositionState() {
+        val disabled = !isSeatControlEnabled
+                || drivingDisabled
+                || !seatSelfLearnActive || !cushionSelfLearnActive
+                || antiplayFaulted
+                || seatAdjustmentFaulted || cushionAdjustmentFaulted
+                || overheatFaulted
+        seatAdjustmentBinding?.apply {
+            val alpha = if (disabled) 0.3f else 1.0f
+            btnPosition1.alpha = alpha
+            btnPosition1.isEnabled = !disabled
+            ...
+        }
+        log("refreshSeatPositionState: " + "isSeatControlEnabled=$isSeatControlEnabled, ...")
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+    private fun refreshSeatDrivingState() {
+        val gear = settingVehicleService.pcuActualGear.value ?: 0
+        val speed = settingVehicleService.vehicleSpeed.value ?: 0f
+        val valid = settingVehicleService.vehicleSpeedValid.value ?: 1
+        drivingDisabled = (gear != 0) && (speed >= 5f) && (valid == 0)
+        refreshSeatAdjustableState()
+        refreshSeatPositionState()
+        refreshSeatSaveState()
+    }
```
8 个状态信号全部经 `updateSeatFault(signalIndex, isFaulted)` 归一为布尔缓存再统一聚合，避免每个 observe 里重复写置灰代码；行车禁用复用了 d27b53ee 引入的速度有效位信号（valid==0 表示无效时按行车处理），体现信号语义在模块内的复用。

## 复盘与要点
- "多故障源聚合置灰"的标准结构：每个信号只维护一个布尔 + 单点聚合函数 + 全量状态日志，排查车端状态问题时一屏日志定位所有输入。
- 行车禁用三条件（档位、车速阈值、有效位）是比外灯页更严格的组合，注意 valid 语义在不同需求里方向相反（此处 valid==0 视为行车），复用信号时必须核对枚举表。
- 遗留风险：saveSeatPosition/re recall 里仍保留 `debugSimulateResponse` 400ms 模拟对手件回复的 TODO 测试代码，量产前必须移除；置灰条件 KDoc 与代码顺序一致性靠人工维护。
