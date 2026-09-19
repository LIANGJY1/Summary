# SIR-7990 · 能量回收等级置灰逻辑与 SRS 不符（极致续航联动遗漏且写反）

- **提交**：`8a6bdf60` | 2026-09-10 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
能量回收等级切换的置灰行为与 SRS 定义不符：极致续航开启时能量回收应置灰并提示，实际却出现相反表现（极致续航开启反而把开关放开），也没有锁定原因提示。

## 根因分析
`DrivingFragment` 中能量回收置灰状态被两条互相矛盾的路径分散管理：① CAN 状态回调里 `if (isEmergyRecoveryEnabled && extremeRangeSwitchSetting.value == SWITCH_ON) setGrayState(false)`——在极致续航开启时**反而调 setGrayState(false) 解除置灰**，与 SRS 恰好写反；② `applyEnergyRecoveryGrayByExtremeRange(extremeRangeOn)` 里 `enabled = if (extremeRangeOn) false else isEmergyRecoveryEnabled` 是正确方向，但没有设置副标题提示，且两处先后执行互相覆盖，最终表现取决于回调时序（缺陷库记"需求遗漏/加上逻辑"）。此外 `updateExtremeRangeUI` 用 `when(state){true->...;false->...}` 把公共刷新逻辑复制了两份。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt、values/strings.xml、values-en/strings.xml（3 文件 +26/-26）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ CAN 状态回调
-                mBinding.vcNestedScrollView.runWithScrollRestore {
-                    mBinding.rgEnergyRecoveryMode.setGrayState(isEmergyRecoveryEnabled)
-                    if (isEmergyRecoveryEnabled && settingVehicleService.extremeRangeSwitchSetting.value == CanSignalConstants.SWITCH_ON) {
-                        mBinding.rgEnergyRecoveryMode.setGrayState(false)
-                    }
-                }
+                updateEnergyRecoveryGrayState()
@@ 删除 applyEnergyRecoveryGrayByExtremeRange，新增统一函数
+    private fun updateEnergyRecoveryGrayState() {
+        val extremeRangeActive = settingVehicleService.extremeRangeSwitchSetting.value == CanSignalConstants.SWITCH_ON
+        mBinding.vcNestedScrollView.runWithScrollRestore {
+            if (extremeRangeActive) {
+                mBinding.rgEnergyRecoveryMode.setGrayState(false)
+                mBinding.rgEnergyRecoveryMode.setSubTitle(getString(R.string.extreme_range_enabled_energy_recovery_locked))
+            } else {
+                mBinding.rgEnergyRecoveryMode.setGrayState(isEmergyRecoveryEnabled)
+                mBinding.rgEnergyRecoveryMode.setSubTitle("")
+            }
+        }
+    }
--- values/strings.xml
+    <string name="extreme_range_enabled_energy_recovery_locked">极致续航开启时，暂不支持能量回收切换</string>
```

## 为什么能修复
置灰判定收敛为单一函数、单一数据源：以 `extremeRangeSwitchSetting` 实时值为准，极致续航开启时置灰（setGrayState(false)）并显示锁定提示副标题，关闭时按 CAN 状态决定，不再有两处互相覆盖；CAN 回调与极致续航开关回调（updateExtremeRangeUI 简化为 if(state) 后统一调 updateEnergyRecoveryGrayState）都走同一入口，时序不再影响结果。副作用极小；注意 setSubTitle 与相邻"驾驶模式锁定"提示共用控件时需确认互斥，避免文案互相顶替。

## 复盘与经验
- 同一 UI 态出现两段方向相反的赋值（一处置灰一处放开）时，表现随时序"抽签"——修复方向永远是合并为单一状态函数（单写者原则）。
- 需求评审要覆盖"功能互斥联动"（极致续航 ↔ 能量回收/驾驶模式），本例与驾驶模式锁定提示是同族逻辑，应一次性抽成通用"锁定+提示"模式。
- 置灰必须配副标题说明原因（"极致续航开启时，暂不支持能量回收切换"），否则用户会当成故障投诉。
