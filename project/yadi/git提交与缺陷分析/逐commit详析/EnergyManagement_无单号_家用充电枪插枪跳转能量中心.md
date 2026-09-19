# SRS_ENERGY_001 · [能量中心] 家用充电枪插枪跳转能量中心

- **提交**：`61710eb5` | 2026-09-03 | liqingqing | EnergyManagement | feature
- **关联单**：SRS_ENERGY_001（标题引用，无系统单号）

## 需求/目标
把"家用充电枪（OBC 家用插枪）"纳入插枪自动弹窗体系：家用枪插入时同样自动跳转能量中心，并参与"未拔枪+充电中+非P挡"的未拔枪提醒与连接状态广播判定。

## 实现结构
- `ChargeGunMonitorService.java`（独立监控服务）：监控列表加入 `ENERGY_EEM_OBC_HOPLUGCONNECTIONSTATUS`；新增 `mHomeGunStatus`（volatile）+ `handleHomeGunChanged`；基线同步（`syncBaseline`）时一并读取初值；P 挡自动进入条件从"AC或DC连接"扩展为"AC/DC/HOME 任一"，枪类型选择改为 AC > DC > HOME 三级优先；`isHomeConnected` 判定 `status == 0x01`。
- `VehicleService.java`（主信号服务，同样逻辑的第二份实现）：新增 home 枪状态、回调分支、`formatHomeChargingGunStatus`（0x00=disconnected、0x01=connected、2/3=reserved）、`isChargingGunConnected`/`checkChargingGunNotRemoved`/`maybeAutoEnterEnergyApp`/AC/DC 变化时的连接广播判定全部加入 home 维度。
- `CarPropertyMapping.kt`：给该信号注释补上值域定义"0:未连接；1:连接；2、3:reserved"。

数据流：OBC_HOPLUGCONNECTIONSTATUS 回调 → 状态缓存 → `maybeAutoEnter(HOME,...)`（ armed + 档位条件）→ launch 页面；同时影响未拔枪提醒与连接状态 notify。

## 关键代码
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/module/VehicleService.java
@@ -2664,9 +2664,10 @@
         boolean acConnected = isAcChargingGunConnected(mAcChargingGunStatus);
         boolean dcConnected = isDcChargingGunConnected(mDcChargingGunStatus);
-        if (!acConnected && !dcConnected) {
+        boolean homeConnected = isHomeChargingGunConnected(mHomeChargingGunStatus);
+        if (!acConnected && !dcConnected && !homeConnected) {
             LogUtils.d(TAG, "[AUTO_ENTRY] gear→P ignored: no gun connected, ...");
             return;
         }
@@ -2680,8 +2680,8 @@
-        String gunType = acConnected ? "AC" : "DC";
-        int gunStatus = acConnected ? mAcChargingGunStatus : mDcChargingGunStatus;
+        String gunType = acConnected ? "AC" : (dcConnected ? "DC" : "HOME");
+        int gunStatus = acConnected ? mAcChargingGunStatus : (dcConnected ? mDcChargingGunStatus : mHomeGunStatus);
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
@@ -195,7 +195,7 @@
-        ),//家用充电枪连接状态
+        ),//家用充电枪连接状态,0:未连接；1：连接；2、3:reserved
```

实现讲解：接入手法是"复制 AC/DC 枪的完整判定骨架再插入第三路"：状态缓存、基线同步、变化回调、连接判定、自动进入、未拔枪检查、格式化日志七件套逐一补齐，保证新枪与旧两枪行为一致。枪类型优先级 AC > DC > HOME 与各自连接判定值（AC/DC=0x02，HOME=0x01）体现了不同充电口协议值域差异。

## 复盘与要点
- 本提交暴露的结构性问题：同一"插枪自动弹窗"逻辑在 `ChargeGunMonitorService` 与 `VehicleService` 各有一份近乎相同的实现，加第三路枪要改两处、约 90 行——应抽公共 GunMonitor 组件，否则第四路（如 V2L）还会翻倍。
- 信号值域注释直接写进 mapping 表（本提交补的"0未连接/1连接/2、3保留"）是低成本高价值的做法，避免后来者再去翻 CAN 文档。
- home 枪连接值 0x01 与 AC/DC 的 0x02 不同，`isXxxConnected` 独立函数而非统一常量是对的，但可再加值域注释防止误合并。
