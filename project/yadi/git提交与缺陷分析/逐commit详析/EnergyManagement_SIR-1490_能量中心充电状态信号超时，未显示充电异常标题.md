# SIR-1490 · 充电状态信号超时未显示"充电异常"
- **提交**：`6c426db1` | 2026-07-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心充电中停发充电状态信号后，标题显示"充电枪已连接"而非"充电异常"，异常告警未呈现。

## 根因分析
`MainActivity.handleChargingPowerTimeout`（`application/EnergyManagement/.../view/ui/MainActivity.java`）处理充电功率/状态信号超时（`lost=true`）分支中，调用六参方法 `updateChargeStateUi(boolean isCharging, boolean isStopped, boolean isCompleted, boolean isUserStoppedCompleted, boolean isAbnormal, String source)` 时第 5 个参数 `isAbnormal` 传了 `false`：`updateChargeStateUi(false, false, false, false, false, "chargingStateTimeoutLost")`。于是方法内部所有"正常态"标志都为 false、异常标志也为 false，落入 else 分支按默认态渲染标题 `charge_gun_connected_status`（"充电枪已连接"）。信号超时本质上是异常工况，却在调用点被声明为"非异常"，属于状态机入参语义传错。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -476,7 +476,7 @@
             binding.btnStopCharging.setEnabled(false);
             mIsChargingState = false;
-            updateChargeStateUi(false, false, false, false, false, "chargingStateTimeoutLost");
+            updateChargeStateUi(false, false, false, false, true, "chargingStateTimeoutLost");
             updateEnergyConvergenceAnimation("chargingStateTimeoutLost");
```

## 为什么能修复
`isAbnormal` 改为 `true` 后，`updateChargeStateUi` 走异常分支，标题显示 `charge_state_abnormal`（"充电异常"）并展示告警图标，与"信号丢失即异常"的产品语义一致。单参数修改无副作用；注意该方法以 5 个 boolean 表达互斥状态，调用点多时仍容易传错位，属于接口设计隐患。

## 复盘与经验
- 连续布尔参数（boolean列表）可读性差、极易传错位，状态互斥时改用枚举/密封类参数更安全。
- "信号超时"的语义应统一映射为异常态，在各超时分支保持一致，避免有的超时显示异常、有的显示默认态。
- 签名确认是排查这类 bug 的第一步：看到 `updateChargeStateUi(false,false,false,false,false,...)` 应立即对参数名核对语义。
