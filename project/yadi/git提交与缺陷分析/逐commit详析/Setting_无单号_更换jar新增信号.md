# SIR-XXXX · 更换jar新增信号
- **提交**：`ca1e6202` | 2026-08-26 | sgh | Setting | feature（信号矩阵迁移/批量重命名）
- **关联单**：SIR-XXXX（占位单号）

## 需求/目标
适配新版本 CarService jar 的信号矩阵：一批信号 ID 更名（氛围灯、迎宾、龙头锁、离车关机等）、占位 ID 替换为正式 ID、废弃信号（驻车娱乐、龙骨/充电口氛围灯）整体下线，收发两端同步切换。

## 实现结构
- `CarPropertyIds.kt`（+61/-61）：重命名 12 个常量并重新分配 ID——`AMBIENT_LIGHT_SWITCH/BRIGHTNESS/MODE` → `MPU_TO_MCU_AMBIENTLIGHTSWITCH(5307)/BRIGHTNESS(5308)/MODE(5309)`（注意数值也变了）；`WELCOME_*` → `MPU_TO_MCU_WELCOME*`；占位龙头锁 `CCU_HANDLELOCKSTATUS/STS(5212/5213)` → 正式 `GANTRY_LOCK_SWITCH(5231)`/`MCU_TO_MPU_STEERINGLOCKFAULT(5230)`；新增 `CCU_ECOFUNCSTS(5122)`（极致续航状态）；删除驻车娱乐/脚撑时间旧 ID。
- `CarPropertyMapping.kt`：映射表同批更新。
- `SettingVehicleService.kt`：订阅列表与 propertyHandlerMap 全量切换到新 ID；`parkingEntertainmentSwitch` 语义更名为 `parkingShutdownSwitch`（离车关机）；`MPU_TO_MCU_LEAVING_CLOSE_COUNT_DOWN` 接管原脚撑下放时间通道（ByteArray 倒计时）。
- 三个 Fragment：发送/读取处 ID 同步替换，LightFragment 删除龙骨/充电口氛围灯发送函数。
- 数据流不变，仅通道地址（propertyId）整体迁移。

## 关键代码
```kotlin
// component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
-    const val AMBIENT_LIGHT_SWITCH: Int = 5307
-    const val KEEL_AMBIENT_LIGHT_SWITCH: Int = 5308
-    const val CHARGING_PORT_AMBIENT_LIGHT_SWITCH: Int = 5309
+    const val MPU_TO_MCU_AMBIENTLIGHTSWITCH: Int = 5307
+    const val AMBIENT_LIGHT_BRIGHTNESS: Int = 5310 → MPU_TO_MCU_AMBIENTLIGHTBRIGHTNESS: Int = 5308
+    const val AMBIENT_LIGHT_MODE: Int = 5311 → MPU_TO_MCU_AMBIENTLIGHTMODE: Int = 5309
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-            CarPropertyIds.CCU_HANDLELOCKSTATUS to { handleLockStatus.postValue(it as? Int) },
-            CarPropertyIds.CCU_HANDLELOCKSTATUS_STS to { handleLockFaultStatus.postValue(it as? Int) },
+            CarPropertyIds.GANTRY_LOCK_SWITCH to { handleLockStatus.postValue(it as? Int) },
+            CarPropertyIds.MCU_TO_MPU_STEERINGLOCKFAULT to { handleLockFaultStatus.postValue(it as? Int) },
```
迁移的最危险点是"名字变了 ID 数值也变"（氛围灯 5310→5308），若只改名字不改数值会出现静默错通道；本提交收发两端同一提交内替换，属于原子迁移的正确做法。

## 复盘与要点
- 信号矩阵升级应在"CarPropertyIds 定义 → 映射表 → 订阅/分发 → 页面收发"全链路一次提交内完成，避免新旧 ID 并存期的静默失联。
- 业务语义随矩阵演进更名（驻车娱乐→离车关机）时同步改 LiveData/函数名，本提交做到了，防止"旧名新义"的误导。
- 风险：批量重命名靠 IDE 替换，若存在反射/字符串拼接 propertyId 的角落将漏改；建议迁移后用"订阅表与 CarPropertyIds 差集"脚本自检。
