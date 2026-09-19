# SRS_ENERGY_004 · [能量中心] 立即充电开关按钮显示

- **提交**：`b5572f3f` | 2026-09-04 | liqingqing | EnergyManagement | feature
- **关联单**：SRS_ENERGY_004（标题引用，无系统单号）

## 需求/目标
预约充电场景的信息展示：当 AC/家用充电枪已连接、当前未在充电、预约充电功能开启、且当前时间在预约时段之外时，能量中心显示"预约充电中"状态、预约开始时间与"立即充电"按钮；点击立即充电立即下发充电指令。

## 实现结构
- `MainActivity.java`（+88）：新增 `updateScheduledChargingUi()` 聚合四个显示条件（`isAcOrHomeChargingGunConnected()` + `chargeState != CHARGING` + `state.mode != 0` + `isOutsideReservationWindow`）；显示时以 30 秒周期重调度自身（`mScheduledChargingUiRunnable`）以轮询"是否进入预约时段"；条件消失时恢复常规充电状态 UI。新增 `isOutsideReservationWindow()`：起止化为分钟数，`start==end` 视为无效窗口，跨零点用 `current>=start || current<end` 判断。新增 `sendImmediateChargingSignal()`：复用 `setAppointmentCharge`，将模式置 0（立即）并携带现有预约时段参数，300ms 后回读刷新。挂接点：onResume、预约状态变化、枪连接变化、充电状态变化四处；onPause/onDestroy 移除回调。
- `VehicleService.java`：新增 `isAcOrHomeChargingGunConnected()` 组合判定。
- 布局与 strings：新增立即充电按钮与两条文案。

数据流：枪状态/充电状态/预约状态任一变化 → updateScheduledChargingUi 重算 → 显示/隐藏 + 30s 自轮询 → 到点后条件翻转回常规 UI。

## 关键代码
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -499,6 +508,57 @@
+    private boolean isOutsideReservationWindow(TboxClientManager.ReservationState state) {
+        int start = state.startHour * 60 + state.startMinute;
+        int end = state.endHour * 60 + state.endMinute;
+        if (start == end) {
+            return false;
+        }
+        Calendar now = Calendar.getInstance();
+        int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
+        boolean inside = start < end
+                ? current >= start && current < end
+                : current >= start || current < end;
+        return !inside;
+    }
```
```diff
@@ -1912,6 +1978,28 @@
+    private void sendImmediateChargingSignal() {
+        ...
+        int mode = state == null ? 1 : state.mode;
+        ...
+        boolean result = vehicleService.setAppointmentCharge(0, mode, startHour, startMinute,
+                endHour, endMinute);
+        if (result) {
+            mMainHandler.postDelayed(() -> refreshChargeSignalsFromFramework("immediateCharging"), 300L);
+        }
+    }
```

实现讲解：核心是"四条件聚合 + 定时重评估"：预约时段外的展示是一个随时间自动失效的状态，实现上用 30 秒 postDelayed 自轮询近似"到点翻转"，避免引入定时对齐复杂度。立即充电未新增协议，而是复用预约充电指令、把首参模式置 0（立即），协议面零扩展。条件消失时通过 `handleChargeStateChanged` 回到既有状态机而非自行拼装 UI，复用度较好。

## 复盘与要点
- 跨零点时间窗判断（start > end 时用或连接）是预约类功能的标准片段，`start==end` 视为无效窗口的防御值得保留。
- 以轮询（30s）实现"时间触发的 UI 翻转"简单可靠但最多有 30 秒延迟；若产品要求准点到点切换，应改为计算距窗口边界的精确 delay。
- `mScheduledChargingUiVisible` 标志记住"本次隐藏前是否显示过"，避免条件翻转时重复整页刷新——多来源驱动同一 UI 时的最小变更思路可复用。
