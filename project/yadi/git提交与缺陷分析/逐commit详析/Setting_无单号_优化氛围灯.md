# SRS_VehSetting_052 · 优化氛围灯

- **提交**：`71008d83` | 2026-09-01 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_052（标题引用，无系统单号）

## 需求/目标
氛围灯三路信号（开关/亮度/模式）从"MPU→MCU 内部信号"切换为最新 CAN 规范的 CCU 下发 + LCM 状态回执编码：下发走 `CCU_AL_IP*SET`，回显改收 `LCM_IP*STS`；模式文案改为"常亮/呼吸"；亮度条上限显式设为 5。

## 实现结构
- `CarPropertyIds.kt`：三个常量重命名（值不变 5307/5308/5309），从 `MPU_TO_MCU_AMBIENTLIGHT*` 改为 `CCU_AL_IP*SET`——名实对齐新 CAN 定义。
- `CarPropertyMapping.kt`：三信号的 sendId 同步更名，关键变化在 recId：回执信号从 `MCU_TO_MPU_AMBIENTLIGHT*` 换成灯控模块的 `LCM_IPSWITCHSTS / LCM_IPBRIGHTNESSSTS / LCM_IPMODESET`。
- `SettingVehicleService.kt`：注册表与 propertyHandlerMap 同步换新 ID，LiveData 出口（ambientLightSwitch/Brightness/Mode）不变——上层观察者零改动。
- `LightFragment.kt`：三处下发引用改名；亮度 seekbar `max=5`。
- `DrivingFragment.kt`：极致续航节能下发（9a18e629 引入）同步换新 ID，且 `CanSignalConstants.SWITCH_OFF` 改为字面量 0。
- `strings.xml`：氛围灯模式文案"简洁/详细"→"常亮/呼吸"。

数据流：UI → `CCU_AL_IP*SET` 下发；灯控 LCM → `LCM_IP*STS` 回执 → mapping 表翻译 → SettingVehicleService LiveData → Fragment 回显。

## 关键代码
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
@@ -506,24 +506,24 @@
         // 氛围灯开关
-        CarPropertyIds.MPU_TO_MCU_AMBIENTLIGHTSWITCH to CarPropertyIdWrapper(
-            sendId = VehiclePropertyIds.MPU_TO_MCU_AMBIENTLIGHTSWITCH,
+        CarPropertyIds.CCU_AL_IPSWITCHSET to CarPropertyIdWrapper(
+            sendId = VehiclePropertyIds.CCU_AL_IPSWITCHSET,
             sendType = Int::class,
-            recId = VehiclePropertyIds.MCU_TO_MPU_AMBIENTLIGHTSWITCH,
+            recId = VehiclePropertyIds.LCM_IPSWITCHSTS,
             recType = Int::class
         ),
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
@@ -1704,17 +1704,17 @@
-    const val MPU_TO_MCU_AMBIENTLIGHTSWITCH: Int = 5307
+    const val CCU_AL_IPSWITCHSET: Int = 5307
```

实现讲解：这是一次"协议换轨"：利用 Carlib 的 `CarPropertyIdWrapper`（内部 key → sendId/recId）映射表，把信号更名与回执源切换压缩到组件层一处，Setting 应用层只改常量引用，LiveData 契约保持稳定。亮度条 `max=5` 则是把亮度的物理档位数显式化，避免 seekbar 默认值与 CAN 值域不符。

## 复盘与要点
- 信号"更名不改值"时全仓重命名常量是最安全做法（编译器找出所有引用点），本提交正是靠这招把跨 3 个模块的协议切换做成小 diff。
- 回执源从 MCU 换到 LCM（灯控模块直报状态）意味着回显更真实——之前收 MCU 转发的"请求回声"，现在收执行器状态，这是车机设置页"下发后无反馈/假反馈"类问题的根治路径。
- `DrivingFragment` 极致续航联动下发同步换 ID，提示了常量重命名必须全仓 grep 而非只改功能归属文件。
