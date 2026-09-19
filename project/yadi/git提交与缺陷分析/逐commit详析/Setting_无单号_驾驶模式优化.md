# 无单号 [SRS_DRVSetting_001] 驾驶模式优化
- **提交**：`c6c5e902` | 2026-08-14 | sgh | Setting | feature（diff 实为回显逻辑重构 + 误弹 toast 修复）
- **关联单**：SRS_DRVSetting_001

## 需求/目标
统一驾驶页/行车安全页 6 个开关（驾驶模式、能量回收、湿滑模式、极致续航、坡道驻车、陡坡缓降、TCS、ABS）的"pending 回显比对"代码结构，并修复页面重新可见时 LiveData 重派发旧值导致误弹"切换失败"toast 的问题。

## 实现结构
- `DrivingFragment.kt`：新增 `onStart()` 清空全部 pending 状态；`updateDrivingModeUI/updateEnergyRecoveryLevelUI/updateSlipModeUI/updateExtremeRangeUI` 全部改为统一的 `if (回显==pending) cancelRebound() else { pending 有效则弹失败 toast }` 单出口结构，pending 在函数尾部统一置为无效值。
- `DrivingSafetyFragment.kt`：同样的结构统一（坡道驻车/陡坡缓降/TCS/ABS），并把字段重命名统一为 `xxxPendingStateTemp` 风格。
- `dialog_sentinel_small.xml` + strings：小弹窗布局微调与文案更新。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
+    override fun onStart() {
+        super.onStart()
+        // 界面重新可见时清空待确认状态，避免LiveData重新派发旧值导致误弹toast
+        drivingModeStateTemp = INVALID_STATE_VALUE
+        energyRecoveryLevelTemp = INVALID_STATE_VALUE
+        slipModePendingState = null
+        extremeRangePendingState = null
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
     fun updateSlipModeUI(state: Boolean) {
         mBinding.apply {
-            val isStateMatched = (state == slipModePendingState)
-            if (isStateMatched) {
+            if (state == slipModePendingState) {
                 SwitchHelper.cancelRebound(ssvSlipModeSetting.switchCompat)
             } else {
                 when (slipModePendingState) {
                     true -> showToast(getString(R.string.slip_mode_switch_open_fail_tip))
                     false -> showToast(getString(R.string.slip_mode_switch_close_fail_tip))
                     null -> {}
                 }
             }
             ssvSlipModeSetting.isChecked = state
         }
         slipModePendingState = null
     }
```
旧实现把"成功取消回弹"与"失败弹 toast"拆成两段独立 if，中间夹着 UI 赋值，末尾还散落 pending 重置，容易漏判；新结构把三条路径收敛到 if/else + when 的单表达式中，任何回显到达都会消费并清空 pending，语义闭环。`onStart` 清空是针对"离开页面再回来，LiveData 重发最后一次信号"这一 Android LiveData 特性的防御。

## 复盘与要点
- "pending 必须在单次回显中被消费"是这类车控回显机制的隐藏不变量：不消费或晚消费都会造成误报，本提交用结构统一 + 生命周期清空双保险落实。
- 跨 Fragment 统一同一套回显模板（同一命名、同一 if/else 结构）显著降低维护成本，是可复用的重构手法。
- 遗留风险：`onStart` 清空 pending 也意味着"切走页面瞬间发出的指令"的回显不再比对（静默接受车端值），属于用体验换正确性的取舍。
