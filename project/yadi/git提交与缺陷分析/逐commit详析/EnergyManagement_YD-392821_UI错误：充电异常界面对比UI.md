# YD-392821 · 充电异常界面多显示一段"充电电量"文言

- **提交**：`ce73174e` | 2026-07-06 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：未关联单号（提交带 YD-392821 单号，缺陷库 defs 为空）

## 问题
充电异常界面与 UI 稿对比，充电异常标题下方多出一段文言——本应只在结束/完成态显示的"充电电量 xx kWh"（`R.string.charge_energy_info`）在异常态也显示了。

## 根因分析
`EnergyManagement/view/ui/MainActivity.java` 的充电状态 UI 刷新方法里，判断条件把"异常态"和"结束/完成态"合并成了一个分支：`if (isStopped || isCompleted || isUserStoppedCompleted || isAbnormal)`。该分支内除了隐藏"停止充电"按钮外，还顺带执行了 `tvChargeEnergyInfo` 的 VISIBLE + `setText(getString(R.string.charge_energy_info, mChargeEnergyKwh))`。**"隐藏停止按钮"与"显示充电电量"是两个语义，却被复用同一个条件**，导致异常态（isAbnormal）也命中了电量文案的显示逻辑。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`（+5/-2）
```diff
// --- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
-        if (isStopped || isCompleted || isUserStoppedCompleted || isAbnormal) {
+        if (isStopped || isCompleted || isUserStoppedCompleted) {
             binding.btnStopCharging.setVisibility(View.GONE);
             binding.btnStopCharging.setEnabled(false);
-            binding.tvChargeEnergyInfo.setVisibility(View.VISIBLE);
-            binding.tvChargeEnergyInfo.setText(getString(R.string.charge_energy_info, mChargeEnergyKwh));
         } else {
             binding.btnStopCharging.setVisibility(isCharging ? View.VISIBLE : View.GONE);
             binding.btnStopCharging.setEnabled(isCharging);
+        }
+        if (isStopped || isCompleted || isUserStoppedCompleted) {
+            binding.tvChargeEnergyInfo.setVisibility(View.VISIBLE);
+            binding.tvChargeEnergyInfo.setText(getString(R.string.charge_energy_info, mChargeEnergyKwh));
+        } else {
             binding.tvChargeEnergyInfo.setVisibility(View.GONE);
         }
```

## 为什么能修复
把原来合并的分支拆成两个独立条件：按钮显隐继续覆盖异常态，而电量文案只在 停止/完成/用户主动停止 完成态显示，异常态落入 else 分支 `View.GONE`。纯条件拆分，无状态副作用；属于典型的小步修复。

## 复盘与经验
- **一个 if 分支只做一件事**：把多个 UI 元素的显隐塞进同一个条件分支，一旦某元素的条件集合不同（本例：异常态要隐藏按钮但不显示电量），就会出错。条件应按"元素×状态矩阵"独立表达。
- **UI 稿逐态走查**：充电页有 充电中/异常/停止/完成 多个形态，视觉比对类缺陷（YD 单据）多靠逐态对比 UI 稿发现，测试用例应覆盖全状态矩阵。
