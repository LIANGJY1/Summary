# SIR-7180/7273 · 模拟车速＞10km/h 时车外灯 OFF 未置灰

- **提交**：`fa797195` | 2026-09-02 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 挂起 · 域 仪表信息（rc/sol 缺库，本文以 diff 实际为准）

## 问题
模拟车速超过 10km/h 时，控制-灯光-车外灯的 OFF 档未按需求置灰，对应的"车速过高"文言副标题也未出现。

## 根因分析
车速有效性信号 `PCU_VEHICLE_SPEED_VALID` 的协议语义是 **0x0=有效、0x1=无效**（仓库内 `SystemUI/.../DigitalKeyVehicleService.kt` 注释明确写有 "0x0：有效 0x1：无效"，`VehicleControlFragment.getCanState` 也按 `(speed > 3f) && (speedValid == 0)` 判定行驶禁用）。而 `LightFragment.updateExternalLightingSubtitleVisibility` 却写成 `vehicleSpeedValid == 1` 才置灰——把"无效"当"有效"，导致真实车速有效（值 0）时 `shouldShow` 恒为 false：副标题 GONE、`rgExternalLighting.setItemSelectable(0, true)` 让 OFF 仍可选，正是"未置灰"的直接原因。典型的信号枚举语义理解颠倒。

## 关键代码修改
改动文件：LightFragment.kt（仅 1 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
     private fun updateExternalLightingSubtitleVisibility(speed: Float) {
         val vehicleSpeedValid = settingVehicleService.getAnyProperty(CarPropertyIds.PCU_VEHICLE_SPEED_VALID)
-        val shouldShow = (vehicleSpeedValid == 1) && (speed >= 10f)
+        val shouldShow = (vehicleSpeedValid == 0) && (speed >= 10f)
         mBinding.rgExternalLighting.setSubTitleVisibility(if (shouldShow) View.VISIBLE else View.GONE)
         mBinding.rgExternalLighting.setItemSelectable(0, !shouldShow)
```

## 为什么能修复
`== 0` 与信号"0=有效"语义对齐后，车速有效且 ≥10km/h 时 `shouldShow=true`：副标题显示、`setItemSelectable(0, false)` 将 OFF 档置灰，两条现象一次修掉。隐患有二：方法头注释仍写着"车速有效 (=1)"未同步更新，会误导后人；缺陷库中该单状态仍为"挂起"且根因/方案未回填，修复与缺陷管理脱节。另外判空缺失——`getAnyProperty` 返回 null 时 `== 0` 为 false，效果是不置灰，恰好安全但属隐式依赖。

## 复盘与经验
- 同一信号在多个模块消费时语义必须一致：修此类 bug 最快的办法是全局 grep 信号 ID，本例 `DigitalKeyVehicleService`、`VehicleControlFragment` 都有正确示范。
- "0=有效/1=无效"这类反直觉协议值极易写反，建议在 service 层收敛成 `speedValid: Boolean` 再下发，而非让每个 UI 各自比较原始值。
- 一行 diff 也可能承载全部根因；改动极小的提交更要核对方法注释与代码的一致性。
