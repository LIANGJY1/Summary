# 无单号 · 迎宾座椅/迎宾灯光开关点击未发送车控信号

- **提交**：`d5cdd4ff` | 2026-09-10 | sgh | Setting | bugfix（未关联单号）
- **缺陷库**：未关联单号

## 问题
场景模式页的"迎宾座椅""迎宾灯光"开关点击后不生效（提交注明 [why] 信号遗漏），点击只改了本地临时状态。

## 根因分析
`SceneModeFragment` 中两个开关的点击回调（经 `setClickFastWithRebound` 包裹）原本只做了两件事：记录 `welcomeSeatPendingState`/`welcomeLightPendingState` 和打 `logClick` 日志，**没有任何向车辆写信号的调用**，属于开发遗漏——对比同页"迎宾音效开关"等其他开关均有 `settingVehicleService.sendVehicleProperty(...)` 调用。开关状态自然永远无法同步到 CCU，UI 侧只能靠 pending 机制暂时维持视觉状态。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt（1 文件 +8/-0）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
@@ swWelcomeSeat 点击回调
             swWelcomeSeat.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
                 welcomeSeatPendingState = isChecked
+                val value = if (isChecked) 1 else 0
+                settingVehicleService.sendVehicleProperty(
+                    CarPropertyIds.CCU_WELSEATSWITCH,
+                    value,)
                 logClick("[Command Send] User action: welcome seat $isChecked")
             }
@@ swWelcomeLight 点击回调
             swWelcomeLight.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
                 welcomeLightPendingState = isChecked
+                val value = if (isChecked) 1 else 0
+                settingVehicleService.sendVehicleProperty(
+                    CarPropertyIds.CCU_WELLIGHTSWITCH,
+                    value,)
             }
```

## 为什么能修复
补上缺失的 `sendVehicleProperty` 调用后，开关切换以 1/0 写入 `CCU_WELSEATSWITCH`/`CCU_WELLIGHTSWITCH` 信号，车端状态得以真实变更并回刷 UI，pending 态机制恢复正常闭环。改动是纯增量补调，不影响其他开关；隐患是若这两个信号在映射表中未配置（本提交只改了 Fragment 层），仍需 Carlib 侧配合，建议联调确认回读。

## 复盘与经验
- 同一页面成组的开关应使用统一的"点击→写信号→记日志"模板，本次遗漏恰因两个开关回调体与其他开关结构不一致，代码走查时对"只有日志没有写信号"的回调应视为红旗。
- pending 态（先改 UI 再等回读）机制会掩盖"信号根本没发"这类缺陷，测试必须断言车端信号真实变化，而不能只看 UI 不回弹。
