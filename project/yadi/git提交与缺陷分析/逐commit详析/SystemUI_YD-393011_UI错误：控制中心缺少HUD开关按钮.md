# YD-393011 · 控制中心缺少HUD开关按钮

- **提交**：`68f76d45` | 2026-08-04 | liujinfeng | SystemUI | bugfix（需求变更新增）
- **缺陷库**：未关联单号（标题含 YD-393011）

## 问题
控制中心 HUD 区域只有自动亮度开关和亮度条，缺少 HUD 总开关；且原总开关位语义混乱——亮度调到最低时甚至会误关总开关。

## 根因分析
旧实现中 `HUDBrightnessTile` 只有一个 `hud_switch` 按钮，点击写的却是 `SysUIConfig.ID_HUD_AUTO_SWITCH`（自动亮度），总开关属性 `HUD_Switch` 在 `SysUIConfig` 中根本没有定义，控制器也没有对应 LiveData；亮度条拖到最小值时 `performClick()` 落在 `ivSwitch` 上，把"自动亮度开关"当总开关误触发。这是新需求（HUD 开关联动亮度调节，关闭时不可调亮度）叠加旧代码语义错位。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/SystemSettingsControllerService.kt、application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt、application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java、application/SystemUI/src/main/res/drawable/selector_hud_switch.xml、application/SystemUI/src/main/res/drawable/vector_power_switch.xml、application/SystemUI/src/main/res/drawable/vector_power_switch_off.xml、application/SystemUI/src/main/res/drawable/vector_ultimate_battery_sel.xml、application/SystemUI/src/main/res/drawable/vector_ultimate_battery_unsel.xml、application/SystemUI/src/main/res/layout/fragment_quick_setting.xml、application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml、application/SystemUI/src/main/res/values/strings.xml
```diff
// application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java
+    //HUD总开关
+    public static final String ID_HUD_SWITCH = "HUD_Switch";
```
```diff
// HUDBrightnessTile.kt 总开关联动禁用
+    fun updateButtonState() {
+        if (ivSwitch.isSelected) {
+            ivAutoSwitch.isEnabled = true
+            ivAutoSwitch.alpha = 1.0f
+            seekBar.isEnabled = true
+            seekBar.alpha = 1.0f
+        } else {
+            ivAutoSwitch.isEnabled = false
+            ivAutoSwitch.alpha = 0.6f
+            seekBar.isEnabled = false
+            seekBar.alpha = 0.6f
+        }
+    }
```
```diff
// HUDBrightnessTile.kt 最低亮度时不再误关总开关
-                    if (ivSwitch.isSelected) {
-                        ivSwitch.performClick()
+                    if (ivAutoSwitch.isSelected) {
+                        ivAutoSwitch.performClick()
                     }
```
布局：原 60dp `hud_switch` 改名为 `hud_auto_switch`（自动亮度），新增 40dp `hud_switch` 总开关按钮（`selector_hud_switch`：`vector_power_switch`/`vector_power_switch_off`）；`SysUIConfig` 新增 `ID_HUD_SWITCH`，`SystemSettingsControllerService` 新增 `hudSwitchState` LiveData 并拆分 `hudAutoState`；两路开关各自 observe + `ReboundHelper` 回弹校正。

## 为什么能修复
总开关/自动亮度/亮度条三控件与 `HUD_Switch`/`HUD_Auto_Bright_Switch`/`HUD_Bright_Adjust` 三个属性一一对应，状态流分开 observe；总开关关闭时 `updateButtonState()` 将自动亮度与亮度条 `isEnabled=false` 且降透明度，满足"关闭时不能操作 HUD 亮度调节"；最低亮度改为触发自动亮度开关，不再误关总开关。注意 9ff2da17 曾因本提交涉及的同名 id 迁移报编译错，本次布局中 id 引用已自洽。

## 复盘与经验
- 新增硬件开关要"属性 ID 常量 + LiveData 状态流 + 控件 + selector 资源"四件套齐备，缺一个就会出现语义错位（旧代码用自动亮度属性冒充总开关）。
- 主从联动控件（总开关→子功能）在禁用态要同时处理 isEnabled 与视觉（alpha），否则"能点但无效"或"看着能点"都会成为新缺陷。
- 用 `performClick()` 复用点击逻辑时，控件职责变化后要同步改引用，否则会操作到错误的开关。
