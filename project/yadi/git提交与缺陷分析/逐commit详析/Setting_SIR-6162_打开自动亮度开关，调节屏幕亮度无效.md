# SIR-6162 · 打开自动亮度开关时调节亮度无效（Setting 侧）

- **提交**：`16e6c292` | 2026-08-24 | sgh | Setting | bugfix
- **缺陷库**：SIR-6162 等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互（与 SystemUI 侧 `d57cb555` 同单号协同修复，本提交改 Setting 应用）

## 问题
与 `d57cb555` 同一单号：自动亮度开启时，在设置应用的显示页拖动屏幕亮度档位、以及车控页拖动 HUD 亮度，调节不生效。

## 根因分析
Setting 侧同样的"指令顺序"问题，且两处表现形态不同。`DisplayFragment` 中屏幕亮度档位回调先 `sendL2A(Constants.ID_BACKLIGHT_BRIGHT, gear + 1)` 再对 `tvAuto` 执行 `performClick()` 关自动亮度，后到的"关自动"把先写入的手动亮度覆盖。`VehicleControlFragment` 的 HUD 亮度则是把"关自动亮度"（`tvAuto.setChecked(false)` + `sendL2A(CarPropertyIds.HUD_AUTO_BRIGHT_SWITCH, 0)`）挂在 `onGearChangeStartListener`（拖动开始）里，与实际设置亮度（`HUD_BRIGHT_ADJUST`）分属两个回调，时序不受控——直接点击档位等不经过拖动开始的路径下，关自动根本不会执行，亮度设置被自动策略压住。修复统一为：在 `OnGearChangeListener` 内、发送亮度指令之前判断并关闭自动亮度。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
@@ 屏幕亮度档位回调
         mBinding.sbScreenLight.gearBright.onGearChangeListener =
             GearSwitchViewNew.OnGearChangeListener { gear ->
+                mBinding.sbScreenLight.tvAuto.apply {
+                    if (isSelected) {
+                        performClick()
+                    }
+                }
                 settingVehicleService.sendL2A(
                     Constants.ID_BACKLIGHT_BRIGHT, gear + 1
                 )
-                mBinding.sbScreenLight.tvAuto.apply {
-                    if (isSelected) {
-                        performClick()
-                    }
-                }
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ HUD 亮度档位回调
                 logClick("Set HUD brightness: $gear")
+                if (autoBrightnessState) {
+                    logClick("set HUD_AUTO_BRIGHT_SWITCH 0")
+                    mBinding.driverHudLayout.includeSeekbarHudBrightness.tvAuto.setChecked(false)
+                    settingVehicleService.sendL2A(CarPropertyIds.HUD_AUTO_BRIGHT_SWITCH, 0)
+                }
                 settingVehicleService.sendL2A(CarPropertyIds.HUD_BRIGHT_ADJUST, gear)
@@ 删除独立的拖动开始监听
-        mBinding.driverHudLayout.includeSeekbarHudBrightness.gearBright.onGearChangeStartListener =
-            GearSwitchViewNew.OnGearChangeStartListener {
-                if (autoBrightnessState) {
-                    ... tvAuto.setChecked(false); sendL2A(HUD_AUTO_BRIGHT_SWITCH, 0)
-                }
-            }
```

## 为什么能修复
两处都收敛为"在同一个用户操作回调内，先发 `AUTO_BRIGHT_SWITCH=0`，再发亮度值"，满足 L2A 接口的顺序要求，手动亮度不再被自动策略覆盖；HUD 路径从拖动开始监听迁移到档位变化监听，覆盖了不经过拖动开始的触发方式，且 `autoBrightnessState` 缓存态判断避免了重复发送。隐患：`DisplayFragment` 仍用 `performClick()` 间接触发，链路依赖按钮点击处理器内容不变；两 Fragment 行为靠约定保持一致，缺乏公共封装，后续仍是双维护。

## 复盘与经验
- 同一单号在 SystemUI 与 Setting 两个应用各有一份实现时，修复必须双侧对齐，只修一侧用户仍可从另一入口复现。
- 把模式切换挂在"拖动开始"这类前置回调里，会漏掉点击直达等不经过该回调的路径；与目标写入强绑定的前置动作应放进同一回调。
- 依赖回调先后顺序的协议（L2A 信号）建议在封装层固化发送序列，而不是散落在各 UI 回调里靠约定。
