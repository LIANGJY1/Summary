# 无单号 [SRS_VehSetting_023] 自动远光灯优化
- **提交**：`4b377c7f` | 2026-08-14 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_023

## 需求/目标
灯光页外部灯光回显逻辑改造：信号值与 UI 索引改为 1:1 直映（去掉 0/2/3→0/1/2 的换算 when），并把自动远光灯、伴我回家、智能补光灯的 pending 比对统一成"回显==pending 才取消回弹、函数尾统一清 pending"的结构；远光回弹时同步置灰 overlay。

## 实现结构
仅改 `ui/fragment/LightFragment.kt`（+22/-30）：
- 删除 `externalLightState`/`refreshByState`，`updateExternalLightingUI` 直接 `setSelectedIndex(state)`。
- `updateAutoHighBeamUI/updateIntelligentLowBeamUI` 去掉冗余的 null 判断与重复 cancelRebound 调用（原来同时调了 `cancelReboundForSwitch` 与 `SwitchHelper.cancelRebound`），pending 清空统一移到函数末尾。
- `startReboundForSwitch` 协程回弹（开关打开失败回滚为关）中补 `enableOverlay()`，回弹后恢复遮罩拦截。
- 数据流不变：信号 echo → update 函数 → 与 pending 比对决定取消回弹或接受车端值。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
     fun updateExternalLightingUI(state: Int) {
         mBinding.apply {
-            rgExternalLighting.cancelRebound()
-            refreshByState(state)
-        }
-    }
-    private fun refreshByState(state: Int) {
-        mBinding.apply {
-            when (state) {
-                0 -> rgExternalLighting.setSelectedIndex(0)
-                2 -> rgExternalLighting.setSelectedIndex(1)
-                3 -> rgExternalLighting.setSelectedIndex(2)
-            }
+            if(pendingExternalLightState==state){
+                rgExternalLighting.cancelRebound()
+            }
+            rgExternalLighting.setSelectedIndex(state)
         }
-        externalLightState = state
+        pendingExternalLightState=-1
     }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
                 delay(REBOUND_DELAY_MS)
                 if (isActive && isVisible && view.isAttachedToWindow) {
                     view.isChecked = false
+                    mBinding.ssvAutoHighBeam.enableOverlay()
                 }
```
配合 d27b53ee 中外部灯光选中项已改为直接发 position（0/1/2），此处回显自然收敛为直接索引，换算层被彻底删除——前后两个提交共同完成"UI 索引=信号值"的契约简化。pending 统一在函数尾部清空也与 c6c5e902（同日相邻提交）的模板完全对齐。

## 复盘与要点
- "UI 索引与信号值 1:1"是消除映射 bug 的最有效手段；当 CAN 侧可改定义时，优先改协议而非在两端写换算表。
- 同一天内 c6c5e902、4b377c7f 两个提交把全模块回显模板逐页统一，体现了"先修 bug、再固化模板"的渐进式重构节奏。
- `startReboundForSwitch` 中补 enableOverlay 说明此前"回弹失败回滚为关"后遮罩状态与置灰条件（ADAS 断连或未开启）脱节，属于状态联动遗漏的补丁。
