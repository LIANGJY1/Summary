# SIR-1513 · 小计里程重置后平均电耗显示 0.0 而非 "--"

- **提交**：`45a6d668` | 2026-07-07 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
小计里程重置后，平均电耗显示 `0.0 kWh/100km`，UI 规范要求无数据时显示 `-- kWh/100km`。

## 根因分析
`MileageManagementActivity.kt` 的重置与显示逻辑有两处配合失误：
1. **哨兵值与合法值混淆**：`resetSubtotalMileageCache()` 把 `mCachedTripEavCns = 0f`，而 0 本身又是可渲染的合法数值，`String.format("%.1f", 0f)` 得到 "0.0"；旧显示逻辑还写反了语义——`if (mCachedSubtotalTripMileage <= 0f) 显示"0.0"`，把"里程为 0（无数据）"直接翻译成了"电耗 0.0"。
2. **重置后的补偿读取毁掉标记**：重置按钮回调里 `postDelayed(500ms)` 后调用 `vehicleService.readEnergyTripEavCnsOnce(...)` 主动回读 CAN，车端静止时读回 0.0，即使先置成无数据标记也会被覆盖。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt`（+3/-8）
```diff
// --- MileageManagementActivity.kt
     private fun applyTripEavCnsUI() {
-        val avgConsumption = if (mCachedSubtotalTripMileage <= 0f) {
-            getSpannableString("0.0", "kwh")
-        } else {
-            getSpannableString(String.format("%.1f", mCachedTripEavCns), "kwh")
-        }
+        val displayValue = if (mCachedTripEavCns < 0f) "--" else String.format("%.1f", mCachedTripEavCns)
         binding?.lvlSubtotalAvgConsumption?.setRightText(...)
-        mCachedTripEavCns = 0f
+        mCachedTripEavCns = -1f          // -1f 作为"无数据"哨兵
         ...
                     binding!!.getRoot().postDelayed(Runnable {
-                        vehicleService.readEnergyTripEavCnsOnce("MileageManagementActivity.reset")
                         vehicleService.readEnergyTtTimeTripHourOnce("MileageManagementActivity.reset")
```

## 为什么能修复
三步闭环：`-1f` 成为域上不可能出现的"无数据"哨兵（电耗不会为负）；显示层先判哨兵输出 "--"，格式化不再把 0 误当数值；删掉重置后的 TRIPEAVCNS 回读，哨兵不会被 500ms 后的 CAN 旧值（0.0）冲掉，"--"保持到真实行驶数据到来。副作用：哨兵值约定只存在于这一处代码，若其他读取路径（如正常 CAN 回调）不做 `<0f` 判断，仍可能显示异常——目前显示统一走 `applyTripEavCnsUI()`，风险受控。

## 复盘与经验
- **"无数据"必须与"数据为 0"分离**：0 是合法测量值，用它兼当"无数据"必然错显；应选域外哨兵（-1f/NaN）或 Optional 语义。
- **重置类操作不要立刻回读外设**：延迟回读 CAN 拿到的是旧周期数据，会把刚清掉的旧值/零值又灌回 UI；重置后应保持空态直到新数据到达。
- **显示层是最后一道语义防线**：格式化前先判哨兵，而不是依赖上游缓存值恰好正确。
