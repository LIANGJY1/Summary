# SIR-1493 · 慢充电流 0.1kW 被误判为信号超时显示 2kW
- **提交**：`700b65f5` | 2026-07-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
发送 `CCU_SlowChargCapSet` 设置慢充电流 0.1kW 后，能量中心慢充电流不显示 0.1kW，而是显示默认值 2kW。

## 根因分析
`MainActivity`（`application/EnergyManagement/.../view/ui/MainActivity.java`）里为慢充功率反馈信号 `OBC_SLOWCHARGCAPSETFEED` 自建了一套"超时检测"：定义 `OBC_SLOW_CHARG_CAP_SET_FEED_TIMEOUT_LOST = 0x01`，在 `handleObcSlowChargCapSetFeedChanged` 中用 `slowChargCapSetFeed == 0x01` 判定信号"超时丢失"。但该信号是数值型反馈（单位 0.1kW），反馈 0.1kW 时 CAN 值恰好就是 1，与代码自造的超时哨兵值撞车——合法数据 0.1kW 被当成超时，走进 `lost` 分支：把 `seekbarRangeMode` 重置为 `SLOW_CHARGE_POWER_DEFAULT_TENTHS`（即 2.0kW 对应的十分值）并刷新 UI，于是界面永远显示 2kW。`verifySlowChargePowerFeedback` 里也有同样的 `== OBC_SLOW_CHARG_CAP_SET_FEED_TIMEOUT_LOST` 分支。本质是把"内部哨兵值"和"协议真实取值域"混为一谈。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -150,9 +150,6 @@
-    private static final int OBC_SLOW_CHARG_CAP_SET_FEED_TIMEOUT_LOST = 0x01;
-    private boolean mObcSlowChargCapSetFeedTimeoutLost = false;
@@ -491,18 +488,7 @@
     private void handleObcSlowChargCapSetFeedChanged(int slowChargCapSetFeed, String source) {
-        boolean lost = (slowChargCapSetFeed == OBC_SLOW_CHARG_CAP_SET_FEED_TIMEOUT_LOST);
-        if (mObcSlowChargCapSetFeedTimeoutLost != lost) {
-            mObcSlowChargCapSetFeedTimeoutLost = lost;
-            LogUtils.d(TAG, "[OBC_SLOWCHARGCAPSETFEED] timeout status changed: value=" + ...);
-            updateSlowChargeSeekBarEnabledState();
-        }
-        if (lost) {
-            binding.seekbarRangeMode.setProgress(SLOW_CHARGE_POWER_DEFAULT_TENTHS - SLOW_CHARGE_POWER_MIN_TENTHS);
-            updateSlowChargePowerUi(binding.seekbarRangeMode);
-            return;
-        }
+        LogUtils.d(TAG, "[OBC_SLOWCHARGCAPSETFEED] value=" + slowChargCapSetFeed + ", source=" + source);
```
（`verifySlowChargePowerFeedback` 中同款哨兵判断分支一并删除。）

## 为什么能修复
删除整套"值==1 即超时"的误判逻辑后，反馈值 1（0.1kW）作为正常数据处理，UI 按真实值刷新，不再被重置为 2.0kW 默认值。隐患：该信号真超时时的行为改由 `isSlowChargeSeekBarEnabledBySignals()` 等其它信号状态统一兜底，超时感知能力弱化，需要确认其它超时监听（如充电功率超时 listener）已覆盖该场景。

## 复盘与经验
- 永远不要拿协议值域内的数（如 0x01）当"超时/丢失"哨兵；超时应由信号管理层的 lost 回调表达，而不是在业务值里猜。
- 单位换算型信号（0.1kW 粒度）小值极易与常见哨兵（0、1、-1）撞车，设计协议解析时先列出完整值域再定哨兵。
- 为每个信号复制粘贴一套超时检测导致三处同款错误分支；超时处理应收敛到框架层统一实现。
