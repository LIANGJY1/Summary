# SIR-5973 · 能量中心 AC 充电功率单位显示错误（30W 显示 3.0kW）
- **提交**：`116c81b0` | 2026-08-18 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心 AC（慢充）充电功率显示错误：信号上报 30W 被界面显示为 3.0kW，数值放大了 100 倍。

## 根因分析
`OBC_SlowChargCapSetFeed` 反馈信号以 **W** 为单位上报，而慢充功率 UI（`MainActivity` 的 seekbar）以 **0.1kW 档位**为刻度（常量 `SLOW_CHARGE_POWER_MIN_TENTHS=0`、`SLOW_CHARGE_POWER_MAX_TENTHS=33`）。原代码把反馈原始值未做任何换算直接送入钳位与 UI：`clampedActual = max(MIN, min(MAX, slowChargCapSetFeed))`——30W 被当作"30 个 0.1kW 档"即 3.0kW 渲染。反馈回调与反馈读取两条路径（`updateSlowChargePowerUi` 相关的两处调用点）都存在同一缺换算问题。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
+        int normalizedActual = wattsToTenthsKw(slowChargCapSetFeed);
         int clampedActual = Math.max(SLOW_CHARGE_POWER_MIN_TENTHS,
-                Math.min(SLOW_CHARGE_POWER_MAX_TENTHS, slowChargCapSetFeed));
+                Math.min(SLOW_CHARGE_POWER_MAX_TENTHS, normalizedActual));
+        LogUtils.d(TAG, "[OBC_SLOWCHARGCAPSETFEED] display conversion: rawValue="
+                + slowChargCapSetFeed + "W, normalizedTenthsKw=" + normalizedActual);
         binding.seekbarRangeMode.setProgress(clampedActual - SLOW_CHARGE_POWER_MIN_TENTHS);
...
+    private int wattsToTenthsKw(int watts) {
+        return Math.round(watts / (float) WATTS_PER_TENTH_KW);
+    }
```
（第二处反馈读取路径同样插入 `wattsToTenthsKw(actualValue)` 后再钳位；`WATTS_PER_TENTH_KW = 100`。）

## 为什么能修复
新增 `wattsToTenthsKw()` 统一做 W→0.1kW 档位换算（除以 100 四舍五入），反馈值先归一化再钳位进 [0,33] 档，30W→0 档显示 0.0kW、100W→1 档显示 0.1kW，与信号真实量纲一致；两条路径共用同一转换函数避免再漂移，并增加 rawValue/normalized 日志便于后续联调核对。风险：用户此前设置的档位若以旧错误量纲写入信号，回读显示会与设置值"不一致"，实为修正后的正确表现。

## 复盘与经验
- 车控信号的单位必须在接口文档/常量层显式化（如 `WATTS_PER_TENTH_KW`），"原始值直接进 UI 刻度"这类缺换算 bug 在多单位体系（W/0.1kW/0.5A）共存的车控域高频出现。
- 钳位（clamp）会掩盖量纲错误：30 被钳进 [0,33] 看起来"合法"，换算缺失被合法性假象遮蔽；钳位前先归一化应是固定顺序。
- 同一转换出现在多个调用点时立即抽函数，并配合"rawValue→normalized"双值日志，联调期即可发现单位错位。
