# VIR-82 · P 挡驻车按座桶键，3D 车模座桶未打开
- **提交**：`058f0cf2` | 2026-07-21 | liqingqing | Launcher | bugfix
- **缺陷库**：未关联单号（VIR-82 无缺陷库记录）

## 问题
P 挡驻车状态下按下座桶（座椅舱盖）按键，3D 车模上座桶没有正常打开。

## 根因分析
提交消息自述"座椅舱盖打开信号为 Seat_Lock_Status"。3D 车模此前根本没有订阅座桶舱盖状态信号——Kanzi 侧的 `SEAT_OPEN_STATE` 没有 Launcher 侧数据源。本提交在 `CarPropertyIds` 新增 `CAN_SEAT_COVER_STATUS = 6219`，在 `CarPropertyMapping` 中把它映射到车端真实信号 `VehiclePropertyIds.SEAT_LOCK_STATUS`（座椅舱盖状态：0=解锁/打开，1=上锁/关闭），并在 `KanziSignalMapping` 注册 handler：舱盖信号 0（解锁）时向 Kanzi 发 `Seat_Open_State=1`（打开），非 0 发 0。即修正了两层偏差：信号没接 + 锁状态与"打开"语义取反。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`、`component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt`、`component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt`、`component/frameworkLibs/libs/android.car.jar`（二进制更新）

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+        signalHandlers.put(CarPropertyIds.CAN_SEAT_COVER_STATUS, event -> {
+            int seatCoverStatus = ((Number) event.getValue()).intValue();
+            int seatOpenState = seatCoverStatus == 0 ? 1 : 0;   // 0=解锁(打开) → Seat_Open_State=1
+            LogUtils.d(TAG, "CAN seat cover status changed: " + seatCoverStatus
+                    + ", Seat_Open_State=" + seatOpenState);
+            kanziManager.setValue("", KanziType.CarModel.SEAT_OPEN_STATE, seatOpenState);
+        });
```

```diff
--- component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
+        // 座椅舱盖状态（0：解锁；1：上锁）
+        CarPropertyIds.CAN_SEAT_COVER_STATUS to CarPropertyIdWrapper(
+            recId = VehiclePropertyIds.SEAT_LOCK_STATUS,
+            recType = Int::class
+        )
```

## 为什么能修复
打通"车端 SEAT_LOCK_STATUS → CAN_SEAT_COVER_STATUS → Kanzi SEAT_OPEN_STATE"的完整信号链，并按"锁状态取反=打开状态"换算，座桶按键动作后车端解锁舱盖、信号回调、3D 车模动画即可联动。`VehicleService` 订阅清单同步注册 6219 保证事件可达。`android.car.jar` 二进制更新应是为暴露 `SEAT_LOCK_STATUS` 常量。隐患：handler 中 `((Number) event.getValue())` 若车端上报异常类型仍是隐患，但外层框架一般有类型包装。

## 复盘与经验
- "3D 车模不动"类问题的排查模板：车端有没有这个信号 → Carlib 映射表有没有这条 → VehicleService 订没订阅 → KanziSignalMapping 有没有 handler，四层任何一层缺失都表现为同一现象。
- 锁类信号（lock/unlock）与直觉语义（open/close）常相反，接入时必须做显式取反并注释原始信号枚举含义，否则下一个人会"修复"掉这个取反。
- 车端信号枚举值含义（0=解锁 1=上锁）要写在映射表旁，注释即文档。
