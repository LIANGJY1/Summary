# SIR-7846 · 极致续航开启时驾驶模式置灰但缺副标题提示
- **提交**：`512e83b5` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：0831 新增需求）

## 问题
极致续航模式开启时，驾驶模式 Tab 置灰但界面上没有副标题文案说明原因（UI 要求显示"极致续航模式开启，暂不支持切换驾驶模式"）。

## 根因分析
0831 新增需求：极致续航开启时驾驶模式被锁定，UI 需要在置灰同时显示锁定原因副标题。此前 `DrivingFragment.updateDrivingModeGrayState()`（前一提交 ef41464e 刚收敛出的统一入口）只处理了湿滑模式（slipModeActive）一种锁定场景，极致续航开关变化虽有 `extremeRangeSwitchSetting` observe 但只刷新自身 UI，不调用 `updateDrivingModeGrayState()`，也没有对应副标题资源，导致置灰了却无解释。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt；application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/res/values-en/strings.xml
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -300,17 +300,33 @@
     private fun updateDrivingModeGrayState() {
         val slipModeActive = settingVehicleService.slipModeSwitchSetting.value == CanSignalConstants.SWITCH_ON
+        val extremeRangeActive = settingVehicleService.extremeRangeSwitchSetting.value == CanSignalConstants.SWITCH_ON
         mBinding.vcNestedScrollView.runWithScrollRestore {
-            if (slipModeActive) {
-                mBinding.rgDriveMode.setGrayState(false)
-                mBinding.rgDriveMode.setSubTitle(getString(R.string.slip_mode_enabled_driving_mode_locked))
-            } else {
-                mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
-                mBinding.rgDriveMode.setSubTitle("")
+            when {
+                slipModeActive -> {
+                    mBinding.rgDriveMode.setGrayState(false)
+                    mBinding.rgDriveMode.setSubTitle(getString(R.string.slip_mode_enabled_driving_mode_locked))
+                }
+                extremeRangeActive -> {
+                    mBinding.rgDriveMode.setGrayState(false)
+                    mBinding.rgDriveMode.setSubTitle(getString(R.string.extreme_range_enabled_driving_mode_locked))
+                }
+                else -> {
+                    mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
+                    mBinding.rgDriveMode.setSubTitle("")
+                }
             }
         }
     }
```
同时 `extremeRangeSwitchSetting.observe` 回调补调 `updateDrivingModeGrayState()`；新增 strings 资源 `extreme_range_enabled_driving_mode_locked`（"极致续航模式开启，暂不支持切换驾驶模式"，中英文）。注释中还预告了后续要支持的赛道模式、车速>5km/h、电量阈值等锁定规则。

## 为什么能修复
置灰状态机从单条件 if 扩展为 when 优先级链（湿滑 > 极致续航 > 正常），极致续航命中时同时置灰+副标题解释；observe 回调联动刷新保证开关切换即时反映到驾驶模式 Tab。无破坏性副作用，但注意英文资源值当前与中文相同（未翻译），且注释中列的另外几条锁定规则尚未实现，属于后续需求。

## 复盘与经验
- 锁定原因类副标题是"状态→解释"映射，新增一种锁定源就要同步扩 when 分支+新文案资源，适合抽成映射表而非 if 链，规则多后可读性更好。
- "置灰但不说原因"会被 UI 走查必抓：凡是禁止交互的状态，需求评审时都要确认是否配提示文案。
- 上一个提交刚把置灰逻辑收敛到 `updateDrivingModeGrayState()`，本提交立即受益——只改一处即可加新规则，验证了状态单一入口的价值。
