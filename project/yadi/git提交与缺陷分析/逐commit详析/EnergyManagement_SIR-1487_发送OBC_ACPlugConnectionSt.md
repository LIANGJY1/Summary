# SIR-1487 · 停发充电枪连接信号后 SOC 上限条被误置灰
- **提交**：`ed39a62b` | 2026-06-30 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
发送 `OBC_ACPlugConnectionStatus` 信号时 SOC 充电上限拖动条高亮可用；一旦停发该信号（信号超时丢失），SOC 上限条被错误置灰不可用。

## 根因分析
`MainActivity`（`application/EnergyManagement/.../view/ui/MainActivity.java`）处理 `CHARGING_GUN_CONNECTION_TIMEOUT` 信号超时的分支里，`lost=true` 时除了隐藏 `tvChargeGunConnectedStatus`、禁用 `btnStopCharging` 外，还调用了 `binding.energyBarSeekBar.setEnabled(false)`；`lost=false` 时再 `setEnabled(true)` 恢复。`energyBarSeekBar` 是 SOC 充电上限拖动条，其可用性本应由 SOC 上限设置信号自身的状态决定，与"充电枪连接信号是否超时"没有业务关系。这是把两个独立信号的 UI 状态错误耦合在一起：枪连接信号一超时，顺带把 SOC 拖动条也禁用了。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -607,14 +607,14 @@
         LogUtils.d(TAG, "[CHARGING_GUN_CONNECTION_TIMEOUT] status=0x" + Integer.toHexString(status) + ", lost=" + lost);
         if (lost) {
             binding.tvChargeGunConnectedStatus.setVisibility(View.GONE);
-            binding.energyBarSeekBar.setEnabled(false);
+//            binding.energyBarSeekBar.setEnabled(false);
             binding.btnStopCharging.setEnabled(false);
         } else {
             if (mGunConnected) {
                 binding.tvChargeGunConnectedStatus.setVisibility(View.VISIBLE);
             }
-            binding.energyBarSeekBar.setEnabled(true);
+//            binding.energyBarSeekBar.setEnabled(true);
             binding.btnStopCharging.setEnabled(true);
```

## 为什么能修复
注释掉两处 `energyBarSeekBar.setEnabled(...)` 后，充电枪连接信号的超时/恢复不再触碰 SOC 拖动条的可用态，拖动条状态只由自己的信号链路驱动，误置灰消失。隐患：只是注释而非删除，说明开发者保留了回退余地；若日后 SOC 拖动条在某些场景确实需要联动（如车未上电禁用），需要由专门的信号分支管理，避免再混入枪连接超时逻辑。

## 复盘与经验
- 一个超时分支顺手多操作了另一个控件的 enable 状态，是典型的"UI 状态与无关信号耦合"，控件的可用性应单一来源管理。
- CAN 信号超时只代表"信号丢失"，不代表功能失效；处理超时时应逐项审视每个 UI 操作是否真属于该信号的职责范围。
