# SIR-1486 · 停发电量信号后能量柱清空
- **提交**：`f473a9a7` | 2026-07-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量柱显示 30% 电量时停发电量信号（SOC_PERCENT 超时），能量柱直接变为空（0%）。

## 根因分析
`MainActivity.handleSocPercentTimeout`（`application/EnergyManagement/.../view/ui/MainActivity.java`）在 `lost=true`（信号超时丢失）分支里执行了 `binding.energyBarSeekBar.setBatterySoc(0)` 和 `updateEnergyBarBottomGlow(0)`。信号丢失意味着"当前 SOC 未知"，而这段代码把未知直接渲染成了 0%——能量柱清空、底部光效熄灭。超时语义被错误地实现成"清零"，与产品要求"保持上一次值"相悖。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -628,8 +628,7 @@
         mSocPercentTimeoutLost = lost;
         LogUtils.d(TAG, "[SOC_PERCENT_TIMEOUT] status=0x" + Integer.toHexString(status) + ", lost=" + lost);
         if (lost) {
-            binding.energyBarSeekBar.setBatterySoc(0);
-            updateEnergyBarBottomGlow(0);
+            // 信号超时丢失时，保持能量柱之前的值不变，不设置为0
         } else {
             refreshSocFromFramework("socPercentTimeoutRecovered");
         }
```

## 为什么能修复
删除清零调用后，超时分支不再触碰 `energyBarSeekBar` 的 SOC 状态，能量柱保持最后一次收到的真实值（30%）；信号恢复时走 `refreshSocFromFramework` 用框架值刷新。符合"数据丢失时冻结显示"的策略。隐患：若整车长时间停发信号（真实掉电场景），界面会一直显示旧 SOC，可能掩盖真实异常——这是"冻结显示"策略的固有取舍，产品需接受。

## 复盘与经验
- 信号超时 ≠ 数值为 0。"未知"应冻结旧值或显示占位（如 "--"），绝不能用 0 这类有业务含义的值表达未知。
- `setXxx(0)` 这类清零调用常被当作"安全默认"，在显示层恰恰相反：0 是有效数据点。
- 本模块连续多个缺陷（SIR-1486/1487/1490/1491/1493/1494）都围绕"信号超时该怎么显示"展开，说明团队需要一份统一的信号丢失显示策略规范。
