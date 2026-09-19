# 无单号 [SRS_VehSetting_066] 优化补盲影像
- **提交**：`a3142849` | 2026-08-14 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_066

## 需求/目标
补盲影像相关设置的归属调整：把"HUD 辅助驾驶开关"从 HUD 页整体删除，相关 HUD 显示优先级开关（辅助驾驶/补盲）挪到辅助驾驶页管理，并新增 HUD 超时（连接）状态对这些开关的置灰联动。

## 实现结构
- `HudFragment.kt`：删除 `setupHudAssistDriveListener`/`updateHudAssistModeUI` 及 assistiveDrivingSwitch observe、`ssvHudAssistDrive` 相关置灰代码；布局中 `ssv_hud_assist_drive` 移除，`ssv_hud_intersection_zoom`（路口放大图）暂时置 `gone`。
- `AssistedDrivingFragment.kt`：新增 `isHudControlEnabled` 状态与 `l2aHudStatus` observe（state==1 为可用），`updateHudStatusUI` 对 `ssvAssistHudPriority`、`ssvBlindSpotHudPriority` 两个开关做 alpha 0.3 + overlay 置灰。
- `layout_car_control_hud.xml`：对应布局项增删。
- 数据流：L2A HUD 心跳/超时状态 → l2aHudStatus LiveData → 辅助驾驶页 HUD 相关开关可用性；HUD 页不再承载 ADAS 相关开关。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
+        //HUD超时状态监听
+        settingVehicleService.l2aHudStatus.observe(
+            viewLifecycleOwner, Observer { state ->
+                logObserve("Received HUD timeout status change: $state")
+                isHudControlEnabled = (state == 1)
+                updateHudStatusUI(isHudControlEnabled)
+            })
...
+    private fun updateHudStatusUI(enabled: Boolean) {
+        val alpha = if (enabled) 1.0f else 0.3f
+        mBinding.apply {
+            ssvAssistHudPriority.setOptionsAlpha(alpha)
+            ssvBlindSpotHudPriority.setOptionsAlpha(alpha)
+            if (enabled) {
+                ssvAssistHudPriority.disableOverlay()
+                ssvBlindSpotHudPriority.disableOverlay()
+            } else {
+                ssvAssistHudPriority.enableOverlay()
+                ssvBlindSpotHudPriority.enableOverlay()
+            }
+        }
+    }
```
实现上沿用模块统一的置灰模板（setOptionsAlpha + enable/disableOverlay），把"HUD 是否在线"作为辅助驾驶页两个 HUD 优先级开关的第二可用性条件（叠加在既有 P 挡等条件之上）。

## 复盘与要点
- 功能入口按业务域迁移（ADAS 的 HUD 显示设置归入辅助驾驶页）比按输出设备（HUD 页）聚合更符合用户心智，但迁移提交要做干净——本提交同时删监听、删回显、删布局，没有留死代码，值得肯定。
- `l2aHudStatus` 超时联动是"外设可用性决定设置可用性"的通用模式，与此前 ADAS 断连置灰自动远光同构。
- 遗留风险：路口放大图开关被直接 `gone`（标题还是硬编码中文"路口放大图"），疑似等后续需求决定去留，属于半成品状态需跟踪。
