# SRS_ENERGY_001 / YD_CCU · [能量中心] 新增家用充电枪超时判断逻辑

- **提交**：`8342ee5b` | 2026-09-03 | liqingqing | EnergyManagement | feature
- **关联单**：SRS_ENERGY_001（标题引用，无系统单号）

## 需求/目标
为插枪连接状态信号增加"MCU→MPU 超时报告"守护：当 `0C12FF04`（充电连接状态超时标志）置位时，插枪状态视为不可信（UNKNOWN），禁止据此自动弹能量中心；超时恢复后重新同步基线。是对 61710eb5 引入 home 枪后的可靠性补全。

## 实现结构
- `ChargeGunMonitorService.java`：监控列表新增 `ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04`；新增 `mGunTimeoutLost` 标志 + `handleGunConnectionTimeout()`——置位时清空 AC/DC/HOME 三枪状态，恢复时 `syncBaseline()` 重读；三个 `handleXxxGunChanged` 入口加"超时中丢弃并置 UNKNOWN"守卫；基线同步时若已超时同样丢弃初值；`isGearParking()` 增加 `!mGunTimeoutLost` 条件（超时中不允许触发 P 挡自动进入）。
- `VehicleService.java`（同逻辑的第二份实现）：`mChargingGunConnectionTimeoutLost` 标志贯穿 `handleChargingGunConnectionTimeout`（补充 UNKNOWN 入参防御、home 枪纳入超时清位与恢复重读）与 AC/DC/HOME 三个状态回调守卫；基线同步后按超时标志丢弃枪状态。

数据流：`0C12FF04==0x01` → 标志置位 → 三枪状态 UNKNOWN + 事件丢弃 → 恢复（==0）→ 重读三枪真实值 → 恢复正常弹窗判定。

## 关键代码
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/module/ChargeGunMonitorService.java
@@ -198,6 +222,22 @@
+    private void handleGunConnectionTimeout(int timeoutStatus) {
+        if (timeoutStatus == STATUS_UNKNOWN) return;
+        boolean lost = timeoutStatus == 0x01;
+        if (mGunTimeoutLost == lost) return;
+        mGunTimeoutLost = lost;
+        if (lost) {
+            mAcGunStatus = STATUS_UNKNOWN;
+            mDcGunStatus = STATUS_UNKNOWN;
+            mHomeGunStatus = STATUS_UNKNOWN;
+            LogUtils.w(TAG, "[GUN_TIMEOUT] connection status timeout, clearing AC/DC/HOME gun state");
+            return;
+        }
+        syncBaseline();
+        LogUtils.d(TAG, "[GUN_TIMEOUT] connection status recovered, baseline re-synced");
+    }
```
```diff
@@ -169,6 +181,10 @@
     private void handleAcGunChanged(int status) {  //NOSONAR
+        if (mGunTimeoutLost) {
+            mAcGunStatus = STATUS_UNKNOWN;
+            return;
+        }
```

实现讲解：手法是"数据可信度门闩"——不为每个下游判定逐一修改条件，而是在信号入口统一设一道闸：超时期间一切输入按"不可信"处理（状态清 UNKNOWN、事件丢弃），恢复瞬间以一次基线重读拉平状态，天然避免超时窗口内的假插枪弹窗。标志的幂等判断（`== lost` 直接返回）防止重复清位/重复基线同步。

## 复盘与要点
- 车机信号消费必须区分"值"与"值的可信度"，MCU 超时报告就是可信度元数据；本提交的门闩模式可平移到车速、档位等任何有超时报告的信号族。
- 恢复路径选择"整体 syncBaseline 重读"而非逐事件补发，简单且无时序洞，代价是多读几次属性，值得。
- 同一逻辑在两个 Service 中的双份实现经本次又各加约 40 行，重复度继续上升，重构压力在累积。
