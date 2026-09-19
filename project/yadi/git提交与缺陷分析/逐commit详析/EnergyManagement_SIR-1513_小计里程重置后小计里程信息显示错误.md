# SIR-1513 · 小计里程重置后显示未立即刷新
- **提交**：`898100f1` | 2026-07-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
点击"重置数据"后，平均电耗立即显示 0kWh/100km，但行驶时长、平均车速等仍显示旧值，要等下一次定时刷新或 CAN 信号回调才更新。

## 根因分析
`MileageManagementActivity`（`application/EnergyManagement/.../view/ui/MileageManagementActivity.kt`）重置按钮只做了两件事：`vehicleService.sendVehicleProperty(CarPropertyIds.ENERGY_RESETODOTRIP, 1)` 发送重置命令，以及 500ms 后逐个 `readXxxOnce` 回读 CAN 信号。问题在于 Activity 内的本地缓存字段（`mCachedSubtotalTripMileage/mCachedSubtotalDurationHour/mCachedSubtotalDurationMin/mCachedSubtotalAvgSpeed/mCachedTripEavCns` 等）完全没有被重置，UI 绑定的是这些缓存——命令发出后车端数据已清零，但本地缓存还是旧值，界面只能等 500ms 回读触发回调才被动更新，期间显示不一致。另外 `applyTripEavCnsUI()` 直接 `String.format("%.1f", mCachedTripEavCns)`，小计里程为 0 时"每百公里电耗"已无意义，缺少兜底分支。

注：缺陷库 sol 描述为"用 -1f 标记无数据、显示 --、移除重置后回读"，与实际 diff 不符；实际实现为清零缓存 + 立即/回读后两次 `refreshUI`，回读逻辑保留，本文以 diff 为准。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt`
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
@@ -262,14 +262,30 @@
     private fun applyTripEavCnsUI() {
+        val avgConsumption = if (mCachedSubtotalTripMileage <= 0f) {
+            getSpannableString("0.0", "kwh")
+        } else {
+            getSpannableString(String.format("%.1f", mCachedTripEavCns), "kwh")
+        }
         binding?.lvlSubtotalAvgConsumption?.setRightText(
-                .append(getSpannableString(String.format("%.1f", mCachedTripEavCns), "kWh"))
+                .append(avgConsumption)
+...
+    private fun resetSubtotalMileageCache() {
+        mCachedSubtotalTripMileage = 0f
+        mCachedSubtotalDurationHour = 0
+        mCachedSubtotalDurationMin = 0
+        mCachedSubtotalAvgSpeed = 0
+        mCachedSubtotalMotorPercent = 0
+        mCachedSubtotalOtherPercent = 0
+        mCachedTripEavCns = 0f
+        refreshUI("resetLocal")
+    }
@@ -421,6 +437,7 @@
                     vehicleService.sendVehicleProperty(CarPropertyIds.ENERGY_RESETODOTRIP, 1)
+                    resetSubtotalMileageCache()
                     binding!!.getRoot().postDelayed(Runnable {
                         ...
+                        refreshUI("resetReadback")
                     }, 500)
```

## 为什么能修复
发命令后立即调用 `resetSubtotalMileageCache()` 把全部缓存清零并 `refreshUI("resetLocal")`，界面第一时间显示 0/--；500ms 回读完成后再次 `refreshUI("resetReadback")` 用车端真实值覆盖。两段式刷新同时解决了"不及时"与"回读失败卡旧值"两个问题。隐患：清零到回读之间约 500ms 显示的是 0 而非"--"，若用户重置时车端未成功清零，存在短暂闪 0 的可能；缓存清零与车端真实清零属于两套状态，仍靠回读对齐。

## 复盘与经验
- 本地缓存 + 异步信号回读的页面，执行"重置"类命令时必须同步重置本地缓存，否则 UI 与车端状态脱节一个刷新周期。
- "立即乐观更新 + 延迟回读校正"是处理车控指令反馈延迟的通用两段式模式，比单等回调体验好。
- 缺陷库记录的方案（-1f 标记显示 --）与最终代码（清零显示 0.0）不一致，说明方案在实现阶段被简化，复盘时应以代码为准并回写缺陷库。
