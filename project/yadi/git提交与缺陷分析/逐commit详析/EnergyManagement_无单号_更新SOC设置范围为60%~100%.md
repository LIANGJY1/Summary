# SRS_ENERGY_006 · 更新SOC设置范围为60%~100%

- **提交**：`057eff9f` | 2026-09-07 | liqingqing | EnergyManagement | feature
- **关联单**：SRS_ENERGY_006（标题引用，无系统单号）

## 需求/目标
充电上限（SOC 阈值）可设置范围从 50%~100% 调整为 60%~100%，涉及信号解析、UI 滑条、刻度尺与提示逻辑的全链路同步。

## 实现结构
- `TboxClientManager.java`：`socLimitValueToPercent()` 从"档位值 0~6 → 70+value*5"的换算改为"直通百分比"，合法域 [60,100]（常量 `SOC_LIMIT_MIN/MAX_PERCENT`）——底层协议已从档位编码改为直接上报百分比。
- `MainActivity.java`：`CHARGE_LIMIT_MIN` 50→60；`sendChargingRestrictionRange()` 下发前新增 `Math.max/min` 钳位，日志与回读校验 `verifyChargeLimitFeedback` 统一使用钳位后的值；相关 KDoc 值域注释更新。
- `ScaleTickView.java`：刻度起点 50→60。
- `activity_main.xml`：SeekBar `max=100/progress=50` → `max=40/progress=40`（UI 值 = SOC−60）。
- 两个 seekbar track drawable：90% 高亮分界点从"50~100 区间的 80%（x=315/325）"重算为"60~100 区间的 75%（x=295/305）"，vector path 数据与注释同步修正。

数据流：Tbox 反馈（百分比直读）→ `socLimitValueToPercent` 校验 → 滑条显示；用户拖动 → UI 值+60 → 钳位 → 下发 → 延时回读验证。

## 关键代码
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/module/TboxClientManager.java
@@ -453,10 +455,10 @@
     private static int socLimitValueToPercent(int value) {
-        if (value < 0 || value > 6) {
+        if (value < SOC_LIMIT_MIN_PERCENT || value > SOC_LIMIT_MAX_PERCENT) {
             return -1;
         }
-        return 70 + value * 5;
+        return value;
     }
```
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -864,10 +864,11 @@
     private void sendChargingRestrictionRange(int value) {
-        LogUtils.d(TAG, "[ChargingLimit] >>> sendChargingRestrictionRange: value=" + value
+        int clampedValue = Math.max(CHARGE_LIMIT_MIN, Math.min(CHARGE_LIMIT_MAX, value));
+        LogUtils.d(TAG, "[ChargingLimit] >>> sendChargingRestrictionRange: value=" + clampedValue
                 + ...
         boolean result = vehicleService.sendVehicleProperty(
-                CarPropertyIds.ENERGY_EEM_CHARGING_RESTRICTION_RANGE_SETTING, value);
+                CarPropertyIds.ENERGY_EEM_CHARGING_RESTRICTION_RANGE_SETTING, clampedValue);
```

实现讲解：值域调整类提交最容易漏的是"UI 坐标几何"：track drawable 是按百分比手算的 vector path，本提交把 90% 分界点在新区间的相对位置重算并连同注释一起改，说明改值域时 drawable 内的硬编码几何也属于"值域的一部分"。下发前补的钳位则把"合法域"从约定升级为代码保证。

## 复盘与要点
- 值域变更检查单：协议换算、下发钳位、回读校验、滑条 min/max、刻度尺起点、drawable 几何、KDoc 注释——本提交七处全改到，可作为 checklist 模板。
- UI 值与物理值偏移（max=40 表示 60~100）节省滑条精度的同时增加心算负担，所有偏移必须集中在读写边界转换，禁止内部散落 ±60。
- `socLimitValueToPercent` 从线性换算改为直通，暗示底层协议同步升级；这类"APP 侧换算函数"是协议变化的第一个哨兵，协议冻结前不要把它写死。
