# SIR-8676 · 点击前向碰撞预警开关，显示开启却未显示设置项
- **提交**：`71a7f39f` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 待测试验证 · 域 车控车设

## 问题
点击前向碰撞预警（FCW）开关后，开关显示开启状态，下方的设置项功能区域未显示，与开关状态脱节。

## 根因分析
`AssistedDrivingFragment` 中 FCW 开关与前述 RCW（`b0bfa163`）共用 `setupAdasSwitch`/`startReboundForSwitch` 回弹框架，`b0bfa163` 修复时只给 RCW 接了回弹联动回调，FCW 的两条回弹路径——`setupAdasSwitch(ssvForwardCollisionWarning...)` 正向回弹与关闭流程 `startReboundForSwitch` 回弹——仍未接线：回弹后开关视觉翻回，但 `updateForwardCollisionWarningUI` 不被调用，设置项显隐与开关状态脱节。缺陷库 rc"频繁切换回弹刷新逻辑问题"与提交 `[why] 频繁切换按钮回弹未刷新可见区域` 一致。

## 关键代码修改
改动文件：AssistedDrivingFragment.kt（+6/-2）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
             // 前向碰撞预警开关
             setupAdasSwitch(ssvForwardCollisionWarning.switchCompat, "FCW", {
                 setForwardCollisionWarningState(true)
+            }) {
+                updateForwardCollisionWarningUI(STATE_OFF)
             }
...
             forwardCollisionWarningPendingState = STATE_OFF
             mBinding.ssvForwardCollisionWarning.isChecked = STATE_OFF
             settingVehicleService.sendL2A(CarPropertyIds.FCW_SWITCH, SWITCH_VALUE_OFF)
-            startReboundForSwitch(mBinding.ssvForwardCollisionWarning.switchCompat)
+            startReboundForSwitch(mBinding.ssvForwardCollisionWarning.switchCompat) {
+                updateForwardCollisionWarningUI(STATE_ON)
             }
```

## 为什么能修复
沿用 `b0bfa163` 扩展好的回调参数：FCW 正向点击超时回弹时调 `updateForwardCollisionWarningUI(STATE_OFF)` 隐藏设置项，关闭流程回弹（翻回开启）时调 `updateForwardCollisionWarningUI(STATE_ON)` 显示设置项，两条路径的联动全部补齐。框架层无需再改动，纯调用侧接线，风险极小。至此 FCW/RCW/HUD 三个开关联动全部覆盖，同类模式（LDW、交通提醒开关若有内容区联动）仍需按同样规范检查。

## 复盘与经验
- 修复"回弹联动"这类模式化缺陷时，应一次性排查同框架下所有调用点（本例 RCW 修复次日即发现 FCW 同病），逐个打补丁会拉长缺陷存活期。
- `setupAdasSwitch` 回调参数在 `b0bfa163` 已就位，本提交只加 6 行调用侧代码——良好的框架扩展让后续修复成本趋近于零。
- 反复点击/超时回弹场景应进入设置页开关类功能的固定测试清单。
