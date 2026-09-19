# SIR-5734 · 慢充功率下发精度错误（seekbar 值 33 被当 33W 下发）

- **提交**：`67ecc428` | 2026-08-07 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心（rc：下发单位 kw 应为 w / sol：seekbar 值 ×100 转 w）

## 问题
能量中心慢充功率设置中，用户拖动滑条设定功率（如 3.3kW）后，CCU 收到的值精度错误——用户拖到 3.3，底层只收到 33，实际被解释为 33W 而非 3300W。

## 根因分析
UI 上慢充功率显示为 0~3.3kW、精度 0.1kW，而 seekbar 的 value 是整数 0~33（`SLOW_CHARGE_POWER_MAX_TENTHS = 33`，单位是 0.1kW）。`MainActivity.sendChargingPowerAdjustmentRange(int value)` 把这个"0.1kW 精度值"**原样**通过 `vehicleService.sendVehicleProperty(CarPropertyIds.ENERGY_EEM_CHARGING_POWER_ADJUSTMENT_RANGE_SETTING, value)` 下发。但底层与对手件的信号协议单位是**瓦（W）**，于是用户拖到 3.3kW（seekbar 值 33）时下发了 33——单位换算整层缺失，偏差 100 倍。这是 UI 内部单位（0.1kW）与协议单位（W）未做映射的经典单位错误。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
// application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
+    private static final int WATTS_PER_TENTH_KW = 100;
...
-    private void sendChargingPowerAdjustmentRange(int value) {
-        LogUtils.d(TAG, "... value=" + value + " (" + ... "%.1fkW", value / 10f ... ")");
+    private void sendChargingPowerAdjustmentRange(int valueTenthsKw) {
+        int valueWatts = valueTenthsKw * WATTS_PER_TENTH_KW;
+        LogUtils.d(TAG, "... uiValue=" + valueTenthsKw
+                + " (" + ... "%.1fkW", valueTenthsKw / 10f ... ")"
+                + ", sendValue=" + valueWatts + "W" ...);
         boolean result = vehicleService.sendVehicleProperty(
-                CarPropertyIds.ENERGY_EEM_CHARGING_POWER_ADJUSTMENT_RANGE_SETTING, value);
+                CarPropertyIds.ENERGY_EEM_CHARGING_POWER_ADJUSTMENT_RANGE_SETTING, valueWatts);
```
同时 `verifySlowChargePowerFeedback` 参数更名为 `sentValueTenthsKw` 并保留 UI 精度单位用于回读校验，日志同步区分 uiValue 与 sendValue。

## 为什么能修复
下发前统一乘以 `WATTS_PER_TENTH_KW=100` 完成 0.1kW → W 的换算：拖到 0 下发 0、0.1 下发 100、1 下发 1000、3.3 下发 3300，与提交 `[how]` 描述及底层协议完全一致。反馈校验 `verifySlowChargePowerFeedback` 仍以 0.1kW 口径比对 UI 设定，职责清晰。副作用：整型换算无精度损失（0.1kW 步进本身就是 100 的整数倍）；风险点在反馈回读值 `readObcSlowChargCapSetFeedOnce` 的单位口径需与新的下发单位匹配，否则回显校验会误判回弹。

## 复盘与经验
- 单位换算必须显式成常量并命名（`WATTS_PER_TENTH_KW`），禁止在发送点裸写乘数；参数命名带单位后缀（valueTenthsKw/valueWatts）能在编译期防误用。
- "UI 内部精度值"与"协议值"是两个世界，信号收发函数的入参应强制使用协议单位，UI 单位只在视图层存在。
- 日志同时打印 UI 值与下发值（含单位）是联调利器，本修复顺手补齐，后续再出精度问题一眼可定位。
- seekbar 的 value 永远是无量纲整数，从 seekbar 取值到下发信号的链路上，第一步就该写出换算公式。
