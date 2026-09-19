# SIR-4569 · 3D车模退出充电中状态后不显示"充电枪已连接"

- **提交**：`b577bfbe` | 2026-07-29 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模处于充电中状态时拔插流程结束后（OBC 充电状态变 0x0 但充电枪仍插着），车模不显示"充电枪已连接"状态，充电页面被错误关闭。

## 根因分析
`KanziSignalMapping` 中充电状态与充电枪状态是两个独立信号，原代码在两处把它们**无条件互相覆盖**：
1. `updateChargingStateToKanzi()`（原 751-754 行附近）：OBC 充电状态映射为 0（关闭）时，无条件执行 `setValue(CHARGING_GUN_STATE, 0)` 清零充电枪状态。但充电结束≠拔枪——此时缓存的 `mAcChargingGunStatus` 仍为 2（已连接），Kanzi 收到 `Charging_Gun_State=0` 后直接关闭充电页面，"枪已连接"状态丢失。
2. `updateChargingGunStateToKanzi()`（原 620 行附近）：枪状态变化时无条件把枪状态写入 `CHARGING_STATE`。若 OBC 正在充电（Charging_State=2 充电中），一次枪信号抖动会把充电中降级为 1（仅枪连接），页面状态回退。

本质是**两个状态机共享写对方的状态位且无条件覆盖**，任何一个信号变化都会把另一个真实状态冲掉。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ updateChargingStateToKanzi()
+        if (kanziState == 0) {
+            boolean acConnected = mAcChargingGunStatus >= 2 && mAcChargingGunStatus <= 5;
+            boolean dcConnected = mDcChargingGunStatus == 2;
+            if (acConnected || dcConnected) {
+                kanziState = 1;
+            } else {
+                kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, 0);
+            }
+        }
         LogUtils.d(TAG, "Charging state -> Kanzi: ...");
         kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
-        // 充电结束(0x0)时同步清零充电枪连接状态，避免枪连接信号超时后 Kanzi 仍显示已连接
-        if (kanziState == 0) {
-            kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, 0);
-        }
```

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ updateChargingGunStateToKanzi()
         kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, kanziState);
-        // 充电枪连接/断开时同步更新 Charging_State，确保充电页面状态刷新
-        kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
+        if (mObcChargeState == 0) {
+            kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
+        }
```

## 为什么能修复
两处都从"无条件覆盖"改为"有条件仲裁"：
- 充电结束时先查缓存的枪状态（AC 2~5 / DC 2 为已连接）：枪仍在 → `Charging_State` 置 1（枪已连接）且**不再清零** `Charging_Gun_State`，Kanzi 充电页面保留"枪已连接"展示；枪确实断开 → 才清零枪状态。
- 枪状态变化时，只有 OBC 未充电（`mObcChargeState == 0`）才回写 `CHARGING_STATE`，充电中（=2）不再被枪信号降级为 1。
副作用：`Charging_State` 与 `Charging_Gun_State` 的联动规则变复杂（状态仲裁逻辑分散在两个方法里），后续新增充电状态值时需同时审视两处条件；枪状态缓存 `mAcChargingGunStatus` 若长时间未刷新，仲裁基于的可能是旧值（另有 `refreshChargingGunStateFromVehicle()` 从 VehicleService 重读兜底）。

## 复盘与经验
- **两个互相关联的状态机绝不能互相无条件覆写**：A 变化时直接写 B 的状态位，等于假设两者总是同步的；充电结束≠拔枪这类业务上的"部分解耦"必须在仲裁逻辑里显式建模。
- **复合状态要先查从属信号再下结论**：结束充电时先读枪连接缓存再决定目标状态（0 或 1），比"先清零等下一个信号纠正"的写法少一次错误中间态。
- **写方向要有门槛（guard condition）**：`if (mObcChargeState == 0)` 这种"只在低优先级时允许回写"的门，防止高优先级状态（充电中）被低优先级信号（枪抖动）降级。
- **信号缓存值要考虑新鲜度**：基于缓存仲裁时，配套重读机制（`refreshChargingGunStateFromVehicle`）是必要兜底。
