# SIR-6675 · 充电中拔枪，充电动效不消失

- **提交**：`ab58a07b` | 2026-08-27 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
界面处于"充电中"状态时拔出充电枪，扫描动效与粒子动效仍然播放，充电中视觉状态残留。

## 根因分析
`MainActivity.updateChargingGunConnectedUi(boolean connected)` 负责响应充电枪连接变化，拔枪（`connected=false`）分支里只隐藏了"停止充电"按钮、充电信息文字等 View，**没有停 `EnergyBarSeekBar` 上的两个动效开关（`setChargingScanPlaying` / `setParticleEffectPlaying`），也没有复位 `mIsChargingState`**。同时 `handleChargeStateChanged` 判定充电中的条件只看车端状态 `chargeState == EV_CHARGE_STATE_CHARGING`，不校验 `mGunConnected`；拔枪后车辆可能仍上报充电态（状态切换有延迟），或拔枪前缓存的 `mLastKnownChargeState` 在 `gunConnectionChanged` 回调里被重新分发（见 `handleChargeStateChanged(mLastKnownChargeState, "gunConnectionChanged")`），旧充电状态再次把动效拉起。提交信息概括为"新增充电枪连接状态与充电动效联动逻辑"。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ updateChargingGunConnectedUi(boolean connected)
         mGunConnected = connected;
         binding.tvChargeGunConnectedStatus.setVisibility(connected ? View.VISIBLE : View.GONE);
         if (!connected) {
+            mIsChargingState = false;
+            binding.energyBarSeekBar.setChargingScanPlaying(false);
+            binding.energyBarSeekBar.setParticleEffectPlaying(false);
             binding.btnStopCharging.setVisibility(View.GONE);

@@ handleChargeStateChanged
         mLastKnownChargeState = chargeState;
-        boolean isCharging = chargeState == EV_CHARGE_STATE_CHARGING;
+        boolean isCharging = chargeState == EV_CHARGE_STATE_CHARGING && mGunConnected;
```

## 为什么能修复
第一处改动保证拔枪瞬间无条件停止扫描/粒子动效并清掉充电中标志，UI 立即回到非充电态；第二处改动让"车端上报充电中"与"充电枪物理连接"两个条件同时成立才算充电，拔枪后迟到的充电态上报或旧状态重放都无法再触发动效。双保险覆盖了"拔枪事件先到"与"充电态后到"两种时序。隐患是若 `mGunConnected` 初始值与实际枪状态不同步，可能短暂误判，但插枪回调会立即纠正。

## 复盘与经验
- 组合状态（充电中 = 充电态 + 枪连接）的判定要把所有前提条件写进同一个布尔表达式，单独依赖任一信号源都会在异常时序下出错。
- "状态 X 的 UI 资源（动效/定时器/前台服务）"要有与状态源联动的显式关闭路径，只隐藏文字/按钮不是完整的状态清理。
- 缓存最近状态（`mLastKnownChargeState`）再重放的机制，要求重放入口同样过一遍最新约束条件。
