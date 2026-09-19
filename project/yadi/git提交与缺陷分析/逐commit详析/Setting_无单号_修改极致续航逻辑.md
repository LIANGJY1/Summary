# SRS_VehSetting_065 · 修改极致续航逻辑

- **提交**：`9a18e629` | 2026-08-31 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_065（标题引用，无系统单号）

## 需求/目标
补全极致续航（省电模式）的业务闭环：开启成功后由 APP 主动下发关闭座椅加热/把手加热/氛围灯三路节能信号，并联动置灰能量回收档位；同时新增"湿滑模式车速>3km/h 置灰"约束；更新极致续航提示文案。

## 实现结构
仅改 `DrivingFragment.kt`（+58）与 strings.xml（提示文案改为"将大量关闭除驾驶和安全相关的耗电功能"）：
- 新增 `setSlipModeSpeedObserver()`：观察 `vehicleSpeed` + `vehicleSpeedValid`，`valid==1 && speed>3f` 时湿滑模式置灰。
- `updateExtremeRangeUI()` 的确认回弹分支（`state == extremeRangePendingState`）中，开启成功 → `sendExtremeRangeEnergySavingCommands()` 下发 `CCU_SETSEATHEATSWREQ=0`、`CCU_SETHANDLEHEATSWREQ=0`、`MPU_TO_MCU_AMBIENTLIGHTSWITCH=SWITCH_OFF`，并调用 `applyEnergyRecoveryGrayByExtremeRange(true)` 强制能量回收置灰；关闭成功则恢复 `isEmergyRecoveryEnabled` 的原值。
- CAN 状态回调中补充联动：能耗回收可用且极致续航开关为 1 时，重新按极致续航压制置灰。

数据流：极致续航反馈信号 → 确认分支 → 三路节能下发 + 能量回收置灰；车速信号 → 湿滑模式置灰。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -380,11 +402,28 @@
             if (state == extremeRangePendingState) {
                 SwitchHelper.cancelRebound(ssvExtremeRangeSetting.switchCompat)
+                when (extremeRangePendingState) {
+                    true -> {
+                        sendExtremeRangeEnergySavingCommands()
+                        applyEnergyRecoveryGrayByExtremeRange(true)
+                    }
+                    false -> {
+                        applyEnergyRecoveryGrayByExtremeRange(false)
+                    }
+                    null -> {}
+                }
```
```diff
+    private fun sendExtremeRangeEnergySavingCommands() {
+        log("[Command Send] Extreme range ON -> energy saving: close seat heater / handle heater / ambient light")
+        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETSEATHEATSWREQ, 0)
+        settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, 0)
+        settingVehicleService.sendVehicleProperty(
+            CarPropertyIds.MPU_TO_MCU_AMBIENTLIGHTSWITCH,
+            CanSignalConstants.SWITCH_OFF
+        )
+    }
+
+    private fun applyEnergyRecoveryGrayByExtremeRange(extremeRangeOn: Boolean) {
+        val enabled = if (extremeRangeOn) false else isEmergyRecoveryEnabled
+        mBinding.rgEnergyRecoveryMode.setGrayState(enabled)
+    }
```
```diff
+    private fun updateSlipModeGrayBySpeed() {
+        val speed = settingVehicleService.vehicleSpeed.value ?: 0f
+        val valid = settingVehicleService.vehicleSpeedValid.value ?: 0
+        val limited = (valid == 1) && (speed > 3f)
+        mBinding.ssvSlipModeSetting.setGrayState(!limited)
+    }
```

实现讲解：核心手法是"以确认回弹点为事务提交点"——只有当 CAN 反馈与 pending 状态一致（切换真正成功）时才触发联动下发与置灰，失败分支走 toast，避免误联动。置灰统一走 `applyEnergyRecoveryGrayByExtremeRange` 收口：极致续航是更高优先级的禁用源，关闭时回退到能耗回收自身状态 `isEmergyRecoveryEnabled`，两个禁用源不互相覆盖。

## 复盘与要点
- "开关 A 成功后批量下发 B/C/D"这种业务联动，挂在信号确认点而非点击点是正确的——但这把业务编排逻辑放进了 UI Fragment，功能多了之后应上移到 ViewModel/领域层。
- 多禁用源叠加时"关闭后恢复谁的值"容易写错，这里用 `if (extremeRangeOn) false else isEmergyRecoveryEnabled` 显式回退，可复用。
- 细节瑕疵：`sendExtremeRangeEnergySavingCommands` 里氛围灯用 `CanSignalConstants.SWITCH_OFF` 而加热用字面量 0，同函数两种风格（且 fc29efff 已证明该常量并不可靠）；文案里"安安全"为笔误。
