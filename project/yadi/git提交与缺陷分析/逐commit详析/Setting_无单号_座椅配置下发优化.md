# 无单号 · 座椅配置下发优化（日志、API 适配、坡道信号 ID 补充）

- **提交**：`c2078aa3` | 2026-07-08 | sgh | Setting(Carlib) | **优化类提交（影响等级 C，无关联单号）**
- **缺陷库**：未关联单号

## 问题
座椅/HUD 配置下发的可观测性不足，且 VehicleSDK 更新（见 d12c9296）后部分调用方式需要适配。

## 根因分析
本提交是 d12c9296"更新 VehicleSDK 及优化切换用户配置下发"的收尾配套，无缺陷根因，改动属打磨性质：
1. `UserConfigManager` 中 `getSDriveStateManager()` 改为属性访问 `driveStateManager`（适配新 SDK 的 API 形态）；
2. 座椅/HUD 低配守卫的条件值先落局部变量并打日志（`seatPersist`/`hudPersist`），下发路径的观测点补齐；同时删除 `saveSeatPosition`、座椅调节指令处两条高频冗余日志；
3. Carlib 补充坡道驻车/陡坡缓降状态信号 ID：`PCU_HILLHOLDSTS = 5116`、`PCU_DOWNHILLDESCENTSTS = 5117`，并在 `CarPropertyMapping` 中以注释形式预留映射、移除未使用的 `CCU_ABS_CONTROL_MODE` 映射。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt`、`.../ui/fragment/VehicleControlFragment.kt`、`component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt`、`.../map/CarPropertyMapping.kt`
```diff
// --- application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt
-        if (SysPropUtils.getSeatPosition() == 1) { return }
+        val seatPersist= SysPropUtils.getSeatPosition()
+        LogUtils.d(TAG, "seatPersist:$seatPersist")
+        if (seatPersist== 1) { return }
         ...
-        val driveState = settingVehicleService.getSDriveStateManager()?.currentDriveState
+        val driveState = settingVehicleService.driveStateManager?.currentDriveState
```
```diff
// --- component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
+    const val PCU_HILLHOLDSTS: Int = 5116        // 坡道驻车工作状态
+    const val PCU_DOWNHILLDESCENTSTS: Int = 5117 // 陡坡缓降工作状态
```

## 为什么能修复
不针对具体 bug：API 适配保证 SDK 升级后编译/运行正确；日志补点让"为什么没下发"（低配/未选用户/非限速态）可从日志直接判定；信号 ID 预埋为后续坡道功能做准备。注意点：新 ID 以注释方式挂在 Mapping 里未真正生效；提交信息无 what/how 细节、无单号，追溯价值低。

## 复盘与经验
- **守卫型逻辑每个 return 都应有日志**：`低配 return`、`未选用户 return`、`非限速态 return` 这类静默出口，没有日志时现场排查只能靠猜——本提交的做法（条件值先打印再判断）值得推广。
- 配套收尾提交应指明所属主提交/需求（如"伴随 d12c9296"），否则历史考古成本高。
- 预留代码以注释形式入库会积累噪音，未启用的映射建议跟随功能提交一起上。
