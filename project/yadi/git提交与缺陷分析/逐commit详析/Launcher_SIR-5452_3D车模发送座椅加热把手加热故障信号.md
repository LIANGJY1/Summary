# SIR-5452 · 3D车模座椅加热/把手加热故障信号无响应

- **提交**：`a18a1edc` | 2026-08-05 | liqingqing | Launcher | bugfix（补信号）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
车端发出座椅加热、把手加热故障信号后，3D 车模没有任何故障响应/显示。

## 根因分析
整个信号链路里根本没有这两个故障信号的定义：`CarPropertyIds` 无 `THREE_D_MODEL_SCU_SEATHEATFLT` / `THREE_D_MODEL_SCU_HANDLEHEATFLT` 常量，`CarPropertyMapping` 无到 HAL 属性（`VehiclePropertyIds.SCU_SEATHEATFLT` / `SCU_HANDLEHEATFLT`）的映射，Launcher 既未订阅也未缓存，`KanziSignalMapping` 判故障的依据只有 `mCanSeatStatus != 0`（CAN 报文超时），故障位为 1 时无任何分支——属于"缺信号"型缺陷，需要从属性 ID 定义到渲染推送全链路补齐。

## 关键代码修改
改动文件：component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt、component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt、application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java、application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java、application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java、component/frameworkLibs/libs/android.car.jar（二进制更新，包含新增 VehiclePropertyIds）
```diff
// component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
+    const val THREE_D_MODEL_SCU_SEATHEATFLT: Int = 6222   // 座椅加热故障(0:正常,1:故障)
+    const val THREE_D_MODEL_SCU_HANDLEHEATFLT: Int = 6223 // 把手加热故障(0:正常,1:故障)
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_SCU_HANDLEHEATFLT, event -> {
+            mHandleHeatFaultStatus = ((Number) event.getValue()).intValue();
+            updateHandlebarStateToKanzi();
+        });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_SCU_SEATHEATFLT, event -> {
+            mSeatHeatFaultStatus = ((Number) event.getValue()).intValue();
+            updateSeatHeatToKanzi();
+        });
...
-        if (mCanSeatStatus != 0) {              // 把手加热
+        if (mHandleHeatFaultStatus == 1 || mCanSeatStatus != 0) {
+            // 输出故障(4) 给 kanzi
```
配套：座椅加热判断同样加 `mSeatHeatFaultStatus == 1 ||`；`VehicleService` 订阅清单追加两个属性；`KanziDataSourceManager` 初始化回读两个故障值并扩展 `initHandlebarState(handleHeatLevel, handleHeatFaultStatus, seatHeatLevel, seatHeatFaultStatus, canSeatStatus)` 签名；`CarPropertyMapping` 增加 AppId→`SCU_SEATHEATFLT`/`SCU_HANDLEHEATFLT`（Int）映射。

## 为什么能修复
故障信号完成"ID 定义 → HAL 映射 → 订阅 → handler 缓存 → 故障判定 → Kanzi 推送故障值 4"的闭环后，座椅/把手加热故障位为 1 时 3D 车模立即渲染故障态，CAN 超时兜底逻辑保留（OR 关系）。注意 `android.car.jar` 同步替换（二进制），说明 HAL 侧新增了这两个 `VehiclePropertyIds`，应用与框架 jar 版本必须成对升级。

## 复盘与经验
- "缺信号"类缺陷的修复清单是固定的五件套：ID 常量、映射表、订阅清单、事件 handler、init 回读——任何一环漏掉都会表现为"信号发了没反应"。
- 故障渲染判定建议把各故障源（信号故障位、CAN 超时）汇总成独立谓词函数，新增故障源只需在谓词上加 OR，避免散落 if。
- 引用框架新增 VehiclePropertyIds 时必须同步更新 android.car.jar 并确认编译目标，否则运行时找不到属性定义。
