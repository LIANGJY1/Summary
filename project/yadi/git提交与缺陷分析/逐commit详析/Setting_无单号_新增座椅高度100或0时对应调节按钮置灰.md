# 无单号 · 座椅高度 100/0 时对应调节按钮置灰

- **提交**：`1becea20` | 2026-07-24 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
主驾座椅高度/副驾腰靠角度到达边界值（100 或 0）时，把对应方向的调节按钮置灰禁用（alpha 0.3 + isEnabled=false），并正确处理与"CAN 断连整体禁用"两个禁用来源的叠加与恢复。

## 实现结构
单文件（+82/-24）：`ui/fragment/VehicleControlFragment.kt`
- 删除旧的 `driverSeatHeightAdjustState/passengerSeatAngleAdjustState` 缓存，改为语义化的 `driverHeightDisabledBtn/passengerHeightDisabledBtn: Boolean?`（true=禁用向上/前，false=禁用向下/后，null=中间区间）；
- `updateDriverSeatAdjustHeightUI/updatePassengerSeatAdjustHeightUI`：始终更新缓存值（即使 CAN 断开），但仅当状态变化且 CAN 在线时才动 UI，避免覆盖 CAN 禁用态；
- `updateSeatControlEnabledState`：CAN 恢复（enabled=true）时按缓存边界状态重新对四个按钮施加置灰；
- `setSeatAdjustObserver` 中把 CAN 状态观察者移到高度/角度观察者之前注册，注释说明原因（避免 CAN 恢复回调覆盖边界禁用状态）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
     fun updateDriverSeatAdjustHeightUI(heightState: Int) {
         seatAdjustmentBinding?.linearProgress?.setProgress(heightState.toFloat())
+        // 始终更新缓存值，确保CAN恢复时能正确重建边界状态
+        val disabledBtn: Boolean? = when {
+            heightState >= 100 -> true
+            heightState <= 0 -> false
+            else -> null
+        }
+        if (disabledBtn != driverHeightDisabledBtn) {
+            driverHeightDisabledBtn = disabledBtn
+            // CAN断开时不更新UI，避免覆盖CAN禁用状态
+            if (!isSeatControlEnabled) return
+            seatAdjustmentBinding?.btnHeightUp?.apply {
+                val isDisabled = (disabledBtn == true)
+                alpha = if (isDisabled) 0.3f else 1f
+                isEnabled = !isDisabled
+            }
+            ...
```
```diff
                 btnHeightDown.isEnabled = enabled
                 btnAngelFront.isEnabled = enabled
                 btnAngelBack.isEnabled = enabled
+                // CAN恢复后，重新应用边界禁用状态
+                if (enabled) {
+                    if (driverHeightDisabledBtn == true) {
+                        btnHeightUp.alpha = 0.3f
+                        btnHeightUp.isEnabled = false
+                    } else if (driverHeightDisabledBtn == false) {
+                        btnHeightDown.alpha = 0.3f
+                        btnHeightDown.isEnabled = false
+                    }
+                    ...
```
实现讲解：这是"两个禁用来源叠加"的通用问题——CAN 断连是全局禁用，边界值是方向禁用。解法是把边界禁用做成持久状态缓存，全局禁用恢复时回放；边界刷新在 CAN 断开时只记账不动 UI。观察者注册顺序调整进一步保证恢复回调时序。

## 复盘与要点
- `Boolean?` 三态（null=无边界限制）精准表达"向上禁用/向下禁用/无限制"，比两个 Boolean 或 int 标志更不易出错，可复用于任何双向边界控件。
- "先记账、后渲染"的分层（缓存始终更新、UI 视条件更新）是处理多状态源叠加 UI 的可靠模式；代价是恢复逻辑要显式回放，本提交把它集中在 `updateSeatControlEnabledState` 一处。
- 遗留点：边界判断用 `>=100 / <=0`，若信号出现异常值（如 255 脏数据）会被当作边界，依赖信号层已做过滤。
