# SIR-1496 · 续航里程显示 1022km 超出量程
- **提交**：`b49c7f3d` | 2026-07-03 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心接收续航里程信号 `PCU_RemainingRange` 值 1022km 时照实显示 1022km，超过该车最大续航 513km。

## 根因分析
`MainActivity.updateRangeText`（`application/EnergyManagement/.../view/ui/MainActivity.java`，约 1328 行）对信号值只做了单边钳制：`int normalizedRange = mRemainingRangeTimeoutLost ? 0 : Math.max(0, range)`。1022 这个值本身有协议含义——CAN 信号常用全 1 位（0x3FF 等）表示"无效/错误值"，折算后恰为量程上限的 2 倍附近（1022 = 2×511+2，对应 10bit 无效值），代码不设上限就把它当真实里程渲染，`binding.someId.setText(String.valueOf(normalizedRange))` 直接显示 1022。最小值有保护、最大值没有，是典型的单边边界校验。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -47,6 +47,7 @@
     private static final int CHARGE_LIMIT_MIN = 50;
     private static final int CHARGE_LIMIT_MAX = 100;
+    private static final int RANGE_MAX = 513;
@@ -1327,7 +1328,7 @@
     private void updateRangeText(int range, String source) {
-        int normalizedRange = mRemainingRangeTimeoutLost ? 0 : Math.max(0, range);
+        int normalizedRange = mRemainingRangeTimeoutLost ? 0 : Math.max(0, Math.min(RANGE_MAX, range));
         binding.someId.setText(String.valueOf(normalizedRange));
         updateRangeValueStart(normalizedRange);
         updateRangeUnitGroupSpacing(normalizedRange);
```

## 为什么能修复
新增常量 `RANGE_MAX = 513` 并用 `Math.max(0, Math.min(RANGE_MAX, range))` 双边钳制，任何超过物理上限的信号值（含 1022 无效值）都被截到 513 显示。隐患：显示 513 仍是在渲染一个"无效值"的截断结果，更严格的做法是把 1022 识别为无效并显示"--"或冻结旧值；`RANGE_MAX` 按当前车型硬编码，不同续航版本的车型共用代码时需要配置化。

## 复盘与经验
- CAN 信号的"无效值/错误值"（全 1、超量程）必须在显示层钳制或识别，照单全收就会把 1022km 画上屏幕。
- 数值钳制要双边（min+max）成对写，单边校验是漏掉另一侧的前奏；上限用命名常量而非魔法数。
- 区分"无效值"与"极大值"：钳制只是兜底，语义上无效时应走无数据显示路径（与 SIR-1513 的 "--" 思路一致）。
