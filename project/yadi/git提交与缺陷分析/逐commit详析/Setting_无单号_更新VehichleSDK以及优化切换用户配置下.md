# 无单号 · 更新 VehicleSDK 及优化用户切换时配置下发

- **提交**：`d12c9296` | 2026-07-08 | sgh | Setting | **SDK 更新 + 配置下发逻辑优化混合提交**
- **缺陷库**：未关联单号（提交消息 [] 单号为空，缺陷库 defs 为空）

## 问题
切换用户后的座椅等个性化配置下发时机不正确，且 VehicleSDK 有版本更新需要集成。

## 根因分析
两部分改动混在一个提交里：
1. **SDK 更新**：`component/commonlibs/VehicleSDK.jar` 二进制替换（84734→85157 字节），`SettingVehicleService.kt` 同步适配（新增订阅 `PCU_HILLHOLDSTS`、`PCU_DOWNHILLDESCENTSTS` 两个坡道状态属性，清理废弃的 screen/brightness/vehicleVin 注释代码与未用 import）。
2. **配置下发优化**：`UserConfigManager.sendSeatConfig()` 中被注释掉的**限速模式守卫被恢复**——只有 `getSDriveStateManager()?.currentDriveState == DriveState.DRIVELIMITED1.ordinal`（车辆处于限速/驻车可用状态）时才 `sendSeatConfig(userSelect)`，否则直接 return。用户切换时若车辆正在行驶，此前会立即下发座椅配置，恢复守卫后避免行车中座椅动作。另将 debug 构建 `minifyEnabled` 改为 false，并给 sendSeatConfig/sendHudConfig 补充 start 日志。

## 关键代码修改
改动文件：`component/commonlibs/VehicleSDK.jar`（二进制更新）、`application/Setting/build.gradle`、`.../init/SettingVehicleService.kt`（-72 行为主，清理+新订阅属性）、`.../utils/UserConfigManager.kt`、`.../ui/fragment/VehicleControlFragment.kt`、`values/strings.xml`（-83 行清理）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt  sendSeatConfig()
-//        val driveState = settingVehicleService.getSDriveStateManager()?.currentDriveState
-//        if (driveState != com.yadea.apf.vehiclebase.Constants.DriveState.DRIVELIMITED1.ordinal) {
-//            return
-//        }
+        val driveState = settingVehicleService.getSDriveStateManager()?.currentDriveState
+        LogUtils.d(TAG, "sendSeatConfig driveState = $driveState")
+        if (driveState != com.yadea.apf.vehiclebase.Constants.DriveState.DRIVELIMITED1.ordinal) {
+            return
+        }
         configSender.sendSeatConfig(userSelect)
```
```diff
// --- SettingVehicleService.kt 订阅清单新增
         CarPropertyIds.CRUISE_MILEAGE_DISPLAY_MODE_SETTING,
+        CarPropertyIds.PCU_HILLHOLDSTS,
+        CarPropertyIds.PCU_DOWNHILLDESCENTSTS
```
```diff
// --- application/Setting/build.gradle（debug 构建）
-            minifyEnabled true
+            minifyEnabled false
```

## 为什么能修复
座椅配置下发加回"限速模式才允许"的状态门控，切用户触发的下发在行车状态下被拦截，消除行车中座椅突动的安全隐患；SDK 换版本后坡道状态信号可订阅，为后续逻辑铺路。副作用与疑点：debug 关闭混淆便于调试但产出物变大、且调试/正式行为差异加大；`DRIVELIMITED1` 语义若与"驻车才能动座椅"不完全等价，守卫过严会让切用户后配置"迟迟不下发"；混合提交无单号，可追溯性差。

## 复盘与经验
- **安全类守卫不该以注释方式"临时关闭"**：恢复的 driveState 检查说明此前为了调试/联调被注释后忘记恢复，安全门控应做成可配置开关并纳入 review 检查项。
- **SDK 二进制更新与业务逻辑改动必须拆分提交**：本提交 jar + 门控恢复 + 混淆开关三件事纠缠，出问题时无法二分定位。
- 切换用户的配置下发是典型的"时机敏感"操作，除状态门外还应考虑去抖与失败重试。
