# 无单号 [SRS_VehSetting_040] 湿滑模式和极致续航
- **提交**：`e2ee6731` | 2026-08-13 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_040

## 需求/目标
在驾驶设置页新增"湿滑模式"与"极致续航"两个开关及其信号链路；湿滑模式开启时锁定驾驶模式切换并显示提示副标题；顺带移除驾驶模式中的"推行（PUSH_ASSIST）"档位。

## 实现结构
- `init/SettingVehicleService.kt`：新增 `slipModeSwitchSetting`、`extremeRangeSwitchSetting` 两个 LiveData，订阅 `CCU_SLIPMODE_SET`/`CCU_EXTREMERANGE_SET`，并在 `onPropertyChanged` 分发处补 `TURN_BSD_SW`（转向补盲开关）；删除不再使用的能量回收开关/悬架阻尼 LiveData。
- `ui/fragment/DrivingFragment.kt`：新增两个开关的 listener（sendVehicleProperty 下发 + pending 暂存）、observe 回显（`updateSlipModeUI`/`updateExtremeRangeUI`）、tip 点击弹 SentinelDialogSmall 说明、湿滑模式对驾驶模式的联动置灰 `updateDrivingModeStateBySlipMode`；驾驶模式档位表删 PUSH_ASSIST 并重排索引。
- `fragment_driving.xml` + strings：新增两个 SwitchCardView 布局及中英文文案。
- 数据流：开关点击 → CCU 信号 → 车端回显 → LiveData → UI 校正 + toast；湿滑模式状态同时驱动驾驶模式组的 enable 状态。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
+    /**
+     * 根据湿滑模式状态更新驾驶模式的显示
+     */
+    private fun updateDrivingModeStateBySlipMode(slipModeActive: Boolean) {
+        mBinding.apply {
+            if (slipModeActive) {
+                // 湿滑模式开启置灰驾驶模式
+                updateControlEnabledState(rgDriveMode, false)
+                rgDriveMode.setSubTitle(getString(R.string.slip_mode_enabled_driving_mode_locked))
+            } else {
+                updateControlEnabledState(rgDriveMode, isDrivingModeEnabled)
+                rgDriveMode.setSubTitle("")
+            }
+        }
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
+            // 湿滑模式
+            setupSwitchListener(ssvSlipModeSetting.switchCompat, { isSlipModeEnabled }) { isChecked ->
+                val state = if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF
+                settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_SLIPMODE_SET, state)
+                slipModePendingState = isChecked
+            }
+            ssvSlipModeSetting.setOnTipClickListener {
+                showSentinelDialogSmall(getString(R.string.slip_mode), getString(R.string.slip_mode_tip))
+            }
```
湿滑模式回显时同步调用 `updateDrivingModeStateBySlipMode`，实现"湿滑开 → 驾驶模式置灰 + 副标题'湿滑模式开启时,暂不支持驾驶模式切换'"的联动；`initObserve` 里还有一段 `delay(5000)` 后强制置灰的兜底（测试兼容痕迹）。驾驶模式档位映射从 0..3(含 PUSH_ASSIST) 压缩为 0..2(EOC/COMFORT/SPORT)，UI 与信号双向映射同步改。

## 复盘与要点
- 功能开关的"四件套"模板（LiveData 定义 + 订阅表映射 + listener 下发 + observe 回显）在本仓库高度一致，新增开关照抄即可，是可复用的扩展手法。
- 回显函数里"echo == pending 时 toast '切换开启失败'"的条件写反了：下一个提交 `11d164b8` 已将其修正为 `!isStateMatched` 才弹失败提示，属于复制 pending 模板时的逻辑反转 bug。
- `delay(5000)` 兜底置灰是时序补丁——依赖信号首帧到达时间不确定时的权宜之计，后续应以信号有效性位替代。
