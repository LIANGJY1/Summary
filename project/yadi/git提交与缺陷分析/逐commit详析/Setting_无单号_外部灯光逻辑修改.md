# 无单号 [SRS_VehSetting_017] 外部灯光逻辑修改
- **提交**：`d27b53ee` | 2026-08-11 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_017

## 需求/目标
重构外部灯光设置页（LightFragment）的信号通道与交互逻辑：氛围灯信号从"屏幕/龙骨/充电口"三套独立信号收敛为统一的 AMBIENT_LIGHT_* 信号；新增行车中外灯限制逻辑；恢复自动远光灯的二次确认弹窗。

## 实现结构
- `ui/fragment/LightFragment.kt`：删掉约 400 行重复的"屏幕/龙骨/充电口"三套氛围灯 observe/listener/发送代码，收敛为一套 ambientLight* 通道；氛围灯 UI 收进 `includeAmbientLight` 子布局（新增 fragment_ambient_light.xml）。
- `init/SettingVehicleService.kt`：LiveData 通道裁剪（screen/keel/charging 各 4 个 → ambient 共 3 个），订阅表同步改为 AMBIENT_LIGHT_*；新增车速 `PCU_VEHICLE_SPEED` 与车速有效性 `PCU_VEHICLE_SPEED_VALID` 信号订阅。
- `extension/ViewExtension.kt`：新增 SeekBar 的 `setOnStopTrackingTouchListener` / `setOnProgressChangedListener` 扩展，替代 Fragment 内部私有实现。
- `libcar/CarPropertyIds.kt`、`CarPropertyMapping.kt`：删减旧氛围灯信号 ID/映射，新增车速相关 ID。
- 数据流：CAN 信号 → SettingVehicleService 订阅表 → MutableLiveData → Fragment observe 更新 UI；用户操作 → sendVehicleProperty/sendL2A 下发 → pending 态暂存等回显。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
+    /**
+     * 刷新外部灯光副标题显示逻辑
+     * 显示条件：车速有效 (=1) 且 车速 >= 10km/h
+     * 在此条件下，禁用 OFF 档位（位置 0）的可选性
+     */
+    private fun updateExternalLightingSubtitleVisibility(speed: Float) {
+        val vehicleSpeedValid = settingVehicleService.getAnyProperty(CarPropertyIds.PCU_VEHICLE_SPEED_VALID)
+        val shouldShow = (vehicleSpeedValid == 1) && (speed >= 10f)
+        mBinding.rgExternalLighting.setSubTitleVisibility(if (shouldShow) View.VISIBLE else View.GONE)
+        mBinding.rgExternalLighting.setItemSelectable(0, !shouldShow)
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt
+ fun SeekBar.setOnProgressChangedListener(action: (Int) -> Unit) {
+    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
+        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = action(progress)
+        override fun onStartTrackingTouch(seekBar: SeekBar) {}// NOSONAR
+        override fun onStopTrackingTouch(seekBar: SeekBar){}// NOSONAR
+    })
+}
```
车速监听侧是"observe 持续回调 + 读一次性属性"组合：车速 Float 持续变化驱动刷新，车速有效性则用 `getAnyProperty` 即时取值，避免多订阅一路 LiveData。自动远光灯恢复"打开需二次弹窗确认"：关闭直接下发，打开走 showWarningDialog 的 onConfirm 再置位并发 IHC_SWITCH。

## 复盘与要点
- 信号收敛是典型"需求变更引发大删码"案例：前期按三路独立氛围灯实现，后端合并为一路 AMBIENT_LIGHT_* 后 UI/服务层同步大瘦身（-634/+395），说明信号定义应在开发前与 MTU/CAN 侧对齐。
- "车速阈值 + 有效性位"双条件控制行车限制（≥10km/h 禁 OFF 并显示副标题）是可复用的行车安全交互手法。
- 遗留风险：`updateExternalLightingSubtitleVisibility` 只挂车速 observe，若车速有效位变化而车速值未刷新，UI 不会即时响应；且车速有效性订阅代码被注释掉。
