# 无单号 · 根据最新CAN统一修改外发信号值

- **提交**：`fc29efff` | 2026-08-27 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
CAN 规范更新后，把 Setting 各页面对外下发/回显的信号取值统一为新定义：坡道驻车/陡坡缓降改为 2=开、1=关；迎宾类开关改为 1=开；座椅加热档位值域从 0~3 迁移到 1~4；迎宾模式由三档选择器降级为普通开关；座椅记忆结束信号改由 MCU 统一下发。

## 实现结构
- `DrivingSafetyFragment.kt`：坡道驻车、陡坡缓降的下发值与回显判断由 `CanSignalConstants.SWITCH_ON/OFF` 换成字面量 2/1。
- `SceneModeFragment.kt`：迎宾模式 UI 从 `ImageTextRadioGroup`（三档）换成 `SkinSwitchCardView` 开关，新增 `welcomeModePendingState` 乐观回弹；迎宾座椅/灯光/音效回显判断统一为 `state == 1`；迎宾模式下发 1=开/2=关。
- `VehicleControlFragment.kt`：座椅加热图标映射与循环切换值域整体 +1（1=关、2/3/4=档位）；注释掉两处 `CCU_SEATPOSITIONMEMORY*` 结束信号下发（交由 MCU 发送）。
- `fragment_scene_mode.xml`：迎宾模式控件类型与间距调整。
- `CarPropertyIds.kt`：仅注释更新（迎宾模式档位）。

数据流：UI 点击 → 映射新值 → sendVehicleProperty/sendL2A 下发；CAN 回报 → LiveData observe → `state==N` 判断 → 刷 UI + `SwitchHelper.cancelRebound` 回弹确认。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingSafetyFragment.kt
@@ -91,7 +91,7 @@
             setupSwitchListener(ssvHillholdSetting.switchCompat, { isHillHoldTcsEnabled }) { isChecked ->
-                val state = if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF
+                val state = if (isChecked) 2 else 1
                 logClick("[Command Send] Set hill hold: $state")
                 settingVehicleService.sendVehicleProperty(CarPropertyIds.CCU_HILLHOLDSET, state)
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -319,10 +319,11 @@
             val currentState = getTemp().takeIf { it >= 0 } ?: previousState
             val value = when (currentState) {
-                0 -> 3
+                1 -> 4
+                4 -> 3
                 3 -> 2
                 2 -> 1
-                else -> 0
+                else -> -1
             }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
@@ -188,14 +188,14 @@
-    fun updateWelcomeModeGearUI(gear: Int) {
-        mBinding.trgWelcomeMode.setSelectedIndex(gear)
-        welcomeModeGearState = gear
+    fun updateWelcomeModeGearUI(state: Boolean) {
         mBinding.apply {
-            swWelcomeSeat.setVisible(gear != 0)
-            swWelcomeLight.setVisible(gear != 0)
-            swWelcomeSound.setVisible(gear != 0)
+            if (welcomeModePendingState == state) {
+                SwitchHelper.cancelRebound(swWelcomeMode.switchCompat)
+            }
+            swWelcomeMode.isChecked = state
         }
+        welcomeModePendingState = null
     }
```

实现讲解：这是典型的"协议版本对齐"提交——同一批开关的 ON/OFF 编码在不同信号上并不一致（坡道类 2/1、迎宾类 1/2），因此无法再用统一的 SWITCH_ON 常量，只能按信号逐个写字面量。加热档位采用整段值域平移（+1），并把 else 分支改为 -1 表示异常态。迎宾模式改开关后复用了既有"pending 状态 + cancelRebound"的乐观回弹模式。

## 复盘与要点
- 用通用常量（SWITCH_ON/OFF）抽象不同 CAN 信号的开/关编码，在协议改版时反而成阻碍；对外发信号更应显式写出该信号的枚举值并注释含义。
- 值域整体平移（0~3→1~4）是高危改动：下发、回显、图标映射、循环切换四处必须同步，宜抽取一个 level<->icon 映射表单点维护。
- 座椅记忆结束信号移交 MCU 下发（APP 注释掉下发代码）属于职责边界调整，注释保留原文便于追溯，但被注释的死代码应尽快删除并写进联调记录。
