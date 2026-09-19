# 无单号 [SRS_Vehicle_Setting_003] 开发解除屏幕锁定
- **提交**：`4d06c6c9` | 2026-08-14 | sgh | Setting | feature
- **关联单**：SRS_Vehicle_Setting_003

## 需求/目标
在场景模式页新增"解除屏幕锁定"开关。该开关纯本地设置（只写 Settings 存储不发车控信号），供其他模块读取决定是否解除屏幕锁定。

## 实现结构
- `Constants.java`：新增 `SETTING_UNLOCK_SCREEN` 常量，注释明确"1开、0关（仅存本地setting，不发信号）"。
- `SceneModeFragment.kt`：`lazyLoadData` 回读存储值初始化开关；listener 直接 `SettingsUtils.setGSetting` 写值，不走 settingVehicleService。
- `fragment_scene_mode.xml`：分隔线 + `SkinSwitchCardView`（复用通用开关卡片，titleT 指向新文案 unlock_screen）。
- 中英 strings 各加一条。
- 顺带：`CommonTools/switch_track_on.xml` 开关轨道选中色从 `bg_switch_on` 改为 `bg_blue_default`（全局视觉统一）。
- 数据流：UI 点击 → SettingsUtils（本地 KV）→ 其他模块按需读取；无 CAN/L2A 环节，也不需要回显。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
+            // 解除屏幕锁定
+            swUnlockScreen.switchCompat.setOnCheckedChangeListener { _, isChecked ->
+                val value =
+                    if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF
+                SettingsUtils.setGSetting(Constants.SETTING_UNLOCK_SCREEN, value)
+                logClick("[Command Send] unlock screen: $isChecked")
+            }
```
```java
// application/Setting/src/main/java/com/yadea/setting/Constants.java
+    /**
+     * 解除屏幕锁定
+     */
+    //1开、0关（仅存本地setting，不发信号）
+    public static final String SETTING_UNLOCK_SCREEN = "setting_unlock_screen";
```
与页面其他开关最大的不同是省掉了整个"pending + 回显"链路——因为不依赖车端确认，点击即生效。复用 `CanSignalConstants.SWITCH_ON/OFF` 作为本地布尔存储值，保持与信号值语义一致便于跨模块判读。

## 复盘与要点
- "本地设置 vs 车控信号"两类开关在同一个页面共存，代码上通过是否走 settingVehicleService 一眼区分，边界清晰。
- 复用 SWITCH_ON/OFF 常量表达本地布尔，减少魔法数字，但也可能让读者误以为有信号交互——常量注释在这里起到了关键澄清作用。
- 遗留风险：跨模块消费方读取该 key 时若 Setting 进程未初始化需有默认值兜底（默认 0=锁定）；onCheckedChange 初始化赋值也会触发一次写回，量小无害但不够严谨。
