# SIR-1492 · 当前电量等于充电上限时插枪误弹提示
- **提交**：`3a3e47af` | 2026-07-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
当前电量正好等于 SOC 充电上限设置值时，插入充电枪弹出"当前剩余电量已超出设定电量"的提示弹窗，而此时电量并未超出。

## 根因分析
超限检测逻辑在 `MainActivity` 的 SOC 超限检查处（`application/EnergyManagement/.../view/ui/MainActivity.java`，约 1116 行的 `[SOC_EXCEED_LIMIT]` 分支）：`if (currentSoc >= presetSoc)` 就 `ToastUtils.showCenterToast` 弹出 `soc_exceeds_charge_limit` 提示。业务语义是"超出"（strictly greater），`>=` 把"等于上限"也当作超限处理：用户把上限设为当前电量（如电量 80%、上限 80%）后插枪，条件成立，误弹提示。缺陷库归因"当前电量=soc上限设置反馈也弹出弹窗了"与代码完全吻合。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -1116,7 +1116,7 @@
             LogUtils.e(TAG, "[SOC_EXCEED_LIMIT] check failed: invalid values");
             return;
         }
-        if (currentSoc >= presetSoc) {
+        if (currentSoc > presetSoc) {
             ToastUtils.showCenterToast(this, getString(R.string.soc_exceeds_charge_limit),
                     null, null, android.view.Gravity.CENTER, android.view.Gravity.CENTER);
         }
```

## 为什么能修复
比较符从 `>=` 收紧为 `>`，"等于上限"不再命中超限分支，只有真实超出才弹窗，语义与文案"已超出"一致。改动一处边界条件即可，无副作用；仅提示"当前电量恰等于上限时充电不会继续充"这类产品语义需另行确认（本次按 UI 需求以不弹为准）。

## 复盘与经验
- 边界条件（`>=` vs `>`）是弹窗/告警类逻辑最高频的缺陷源，写条件前先明确"相等"属于哪个业务分支。
- 提示文案是"超出"，代码却用 `>=`，文案与逻辑互为对照即可发现此类问题——评审时把字符串和条件放在一起读。
