# 无单号 · 能量回收toast修改

- **提交**：`45a5d268` | 2026-07-08 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
细化能量回收功能切换失败时的 toast 文案分支：区分"总开关开启失败 / 总开关关闭失败 / 等级切换失败"三种情形，并规范提示语（去掉感叹号、统一措辞）。

## 实现结构
改动 3 个文件：
- `ui/fragment/DrivingFragment.kt`：失败提示的 `when` 判断由"按尝试值 `energyRecoveryLevelTemp` 匹配"改为"按条件匹配"——实际值 `energyRecoveryLevel == OFF` 时提示开启失败，尝试值 `== OFF` 时提示关闭失败，其余为等级切换失败。
- `res/values/strings.xml` 与 `res/values-en/strings.xml`：新增 `energy_recovery_open_tip`（能量回收功能总开关开启失败），改写 `energy_recovery_close_fail_tip` 文案，中英文同步。

数据流：车控信号返回后，`energyRecoveryLevel`（实际生效值）与 `energyRecoveryLevelTemp`（下发尝试值）比对，不一致即切换失败，按上述条件选择文案弹 toast。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -430,9 +430,9 @@
         // 检查是否切换失败并显示对应提示
         if (energyRecoveryLevelTemp != INVALID_STATE_VALUE && energyRecoveryLevel != energyRecoveryLevelTemp) {
-            val messageResId = when (energyRecoveryLevelTemp) {
-                ENERGY_RECOVERY_OFF -> R.string.energy_recovery_close_fail_tip
-                ENERGY_RECOVERY_LOW, ENERGY_RECOVERY_HIGH -> R.string.energy_recovery_switch_failed
+            val messageResId = when {
+                energyRecoveryLevel == ENERGY_RECOVERY_OFF -> R.string.energy_recovery_open_tip
+                energyRecoveryLevelTemp == ENERGY_RECOVERY_OFF -> R.string.energy_recovery_close_fail_tip
                 else -> R.string.energy_recovery_switch_failed
             }
             showToast(getString(messageResId))
```
实现讲解：旧逻辑只看"尝试关/尝试调级"，无法表达"用户想开总开关但没开成功"的场景。新逻辑先看实际值：实际仍是 OFF 说明开启失败；尝试值是 OFF 而实际没关掉说明关闭失败；否则按等级切换失败提示。判断依据从"发了什么命令"改为"落在什么结果"，语义更准确。

## 复盘与要点
- 车控类开关的失败提示应以"实际状态"为锚而非"下发命令"为锚，这种 `实际值优先、尝试值兜底` 的 when 条件排序可直接复用到 TCS/ABS 等同页面开关。
- 中英资源同步改是加分项：新增 string 键时两份 values 同时落键，避免英文环境回退到 key 名。
- 遗留点：`ENERGY_RECOVERY_LOW/HIGH` 合并进了 `else`，若未来新增中间档位枚举，`else` 分支仍能兜底，无需扩展——这是比穷举更稳的写法。
