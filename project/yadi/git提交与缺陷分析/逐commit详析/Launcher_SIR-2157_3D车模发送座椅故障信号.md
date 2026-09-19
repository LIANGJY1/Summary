# SIR-2157 · 3D 车模座椅/靠背故障信号无变化

- **提交**：`29dd455d` | 2026-08-06 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
车端发送座椅故障信号后，3D 车模上的座椅和靠背模型没有任何故障态变化，UI 不响应。

## 根因分析
纯"缺实现"型缺陷：座椅电机故障（SCU_SEATMOTORFLT）与靠背电机故障（SCU_CUSHIONMOTORFLT）两条 SCU 信号在客户端完全没有接入。具体缺失四层：1) `CarPropertyIds` 中未定义 6224/6225 两个属性 ID；2) `CarPropertyMapping` 中没有到 `VehiclePropertyIds.SCU_SEATMOTORFLT`/`SCU_CUSHIONMOTORFLT` 的映射，SDK 层根本不会订阅；3) `VehicleService.callbackPropertyIds` 列表未包含这两个 ID，回调不会分发；4) `KanziSignalMapping.signalHandlers` 无对应处理器，也没有向 Kanzi 引擎写入 `Seat_Backrest_States`/`Seat_Error_State` 的逻辑。信号链路从底层到 3D 引擎整条断裂，车模自然无变化。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java`、`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`、`component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt`、`component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt`
```diff
// component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
+    const val THREE_D_MODEL_SCU_SEATMOTORFLT : Int = 6224
+    const val THREE_D_MODEL_SCU_CUSHIONMOTORFLT : Int = 6225
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_SCU_CUSHIONMOTORFLT, event -> {
+            int faultStatus = ((Number) event.getValue()).intValue();
+            updateSeatBackrestStateToKanzi(faultStatus);
+        });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_SCU_SEATMOTORFLT, event -> {
+            int faultStatus = ((Number) event.getValue()).intValue();
+            updateSeatErrorStateToKanzi(faultStatus);
+        });
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private void updateSeatBackrestStateToKanzi(int faultStatus) {
+        int kanziState = faultStatus == 1 ? 1 : 0;
+        kanziManager.setValue("", KanziType.CarModel.SEAT_BACKREST_STATES, kanziState);
+    }
+    private void updateSeatErrorStateToKanzi(int faultStatus) {
+        int kanziState = faultStatus == 1 ? 1 : 0;
+        kanziManager.setValue("", KanziType.CarModel.SEAT_ERROR_STATE, kanziState);
+    }
```
配套改动：`VehicleService.callbackPropertyIds` 加入两个 ID 使回调生效；`KanziDataSourceManager` 新增 `sendSeatBackrestFaultInitStateToKanzi`/`sendSeatMotorFaultInitStateToKanzi`，在 Kanzi 连接建立时通过 `getIntProperty` 主动查询初始态下发，避免"连接前已故障、后续无事件"导致的初始状态缺失。

## 为什么能修复
修复把故障信号从 SDK 映射层（CarPropertyMapping）、回调注册层（callbackPropertyIds）、应用处理层（signalHandlers）到 3D 引擎写入层（kanziManager.setValue）四层全部补齐，信号变化即可驱动车模故障态；初始态主动查询又兜住了"进桌面前已处于故障态"的场景。逻辑为简单的 0/1 直传（`faultStatus == 1 ? 1 : 0`），风险低；注意点仅在于非 1 的异常值会被归一为 0（正常），与协议"0 正常 1 故障"一致。

## 复盘与经验
- 车机信号类 bug 排查按"ID 定义 → SDK 映射 → 回调注册 → 信号处理 → UI 写入"五层链路逐层确认，缺哪层补哪层；本例是整条链路都未实现的新功能补齐。
- 任何信号接入都要同时做"变化事件监听 + 初始态主动查询"双路径，否则 Kanzi/3D 引擎冷启动时拿不到当前状态。
- `CarPropertyIds` 与 `CarPropertyMapping`、`callbackPropertyIds` 是三处必须同步登记的位置，建议用封装好的注册函数收敛，避免后续信号再漏挂。
