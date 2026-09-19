# 无单号 · 优化座椅调节置灰逻辑

- **提交**：`fab40dbe` | 2026-08-26 | sgh | Setting | feature
- **关联单**：无（标题引用 SRS_VehSetting_010）

## 需求/目标
优化车辆控制页座椅调节区域的置灰（禁用）逻辑：修正自学习/调节状态信号的极性语义，并把"座椅总开关禁用"纳入高低调节的禁用条件。

## 实现结构
- `SettingVehicleService.kt`：信号注册表新增 `SCU_CUSHIONMOTORFLT`（靠背电机故障），否则腰靠故障信号根本收不到回调。
- `VehicleControlFragment.kt`：把 `seatAdjustmentFaulted/cushionAdjustmentFaulted` 两个变量重命名为 `seatAdjustmentSts/cushionAdjustmentSts`——该 CAN 信号是"调节进行中状态"而非"故障"，语义纠正后布尔参与逻辑随之取反；`refreshSeatPositionState/refreshSeatSaveState` 中自学习条件由 `!seatSelfLearnActive` 改为 `seatSelfLearnActive`（自学习进行中才禁用）；`refreshSeatAdjustableState` 的 height/angle 禁用链补上 `!isSeatControlEnabled`；座椅总开关状态回调里追加 `refreshSeatAdjustableState()` 联动刷新；删除全局 alpha 批量设置里对主/副驾 AD 图标的误伤（改由独立状态控制），并清掉两处调试日志大杂烩。

数据流：CAN 信号 → SettingVehicleService 分发（按 propertyId switch）→ Fragment 内 8 个布尔状态位 → 三个 refresh* 方法聚合计算 → 控件 alpha/isEnabled。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -1532,9 +1528,9 @@
     private fun refreshSeatPositionState() {
         val disabled = !isSeatControlEnabled
                 || drivingDisabled
-                || !seatSelfLearnActive || !cushionSelfLearnActive
+                || seatSelfLearnActive || cushionSelfLearnActive
                 || antiplayFaulted
-                || seatAdjustmentFaulted || cushionAdjustmentFaulted
+                || seatAdjustmentSts || cushionAdjustmentSts
                 || overheatFaulted
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -1602,6 +1602,7 @@
+     * 座椅条件前置条件判断
      * 刷新座椅可调节状态
      */
     private fun refreshSeatAdjustableState() {
         val heightDisabled =
-            drivingDisabled || seatAdjustmentFaulted || antiplayFaulted || overheatFaulted || motorSelfLearnFaulted || motorFaultFaulted
+            !isSeatControlEnabled || drivingDisabled || seatAdjustmentSts || antiplayFaulted || overheatFaulted || motorSelfLearnFaulted || motorFaultFaulted
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ -261,6 +261,7 @@
         CarPropertyIds.SCU_SEATMOTORSELFLEARN,
         CarPropertyIds.SCU_CUSHIONMOTORSELFLEARN,
         CarPropertyIds.SCU_SEATMOTORFLT,
+        CarPropertyIds.SCU_CUSHIONMOTORFLT,
         CarPropertyIds.SCU_SEATADJUSTSTS,
```

实现讲解：核心手法是"先纠正变量语义再修逻辑"——旧代码把 STS（状态）当 Fault（故障）用，导致禁用条件极性全反；重命名后 `|| seatAdjustmentSts`（调节中→禁用）才与 CAN 定义一致。同时补齐信号注册这一"链路缺失"问题，并让总开关 `isSeatControlEnabled` 变化时主动触发一次可调状态重算，形成完整联动。

## 复盘与要点
- 信号命名必须忠实于 CAN 规范语义：把"状态"命名成"故障"会迫使后续逻辑写反极性，这种错只能靠重命名+取反一起修，diff 才可读。
- 置灰类需求本质是"多个布尔条件的聚合函数"，建议把禁用条件集中在一个纯函数里（本提交三个 refresh 各自聚合，仍有重复），新增禁用源时只改一处。
- 遗留风险：调试日志被整体删除而非降级，后续排查置灰问题少了现场证据；车辆信号回调里手动串联多个 refresh 的写法容易漏调，可考虑统一 invalidate。
