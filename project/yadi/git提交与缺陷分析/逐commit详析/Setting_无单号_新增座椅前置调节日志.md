# 无单号 · 新增座椅前置调节日志（含 drivingDisabled 计算调整）

- **提交**：`5b355393` | 2026-09-16 | sgh | Setting | bugfix/可观测性
- **缺陷库**：未关联单号

## 问题
座椅调节偶发不生效，需要更多前置条件日志定位"防夹/过热/自学习故障/车速禁止"等前置状态；同时原 `drivingDisabled` 的判定需要落到真实车速信号上。

## 根因分析
`VehicleControlFragment.getCanState()` 原来只打印 8 个计算后的前置条件字段，且 `drivingDisabled` 的来源不可见，出问题时无法区分"信号没来"还是"判定逻辑错"。本提交做了两件事：1) 在 `getCanState()` 里直接 `readVehicleProperty` 读取 8 路原始 SCU 信号（`SCU_ANTIPLAYSTS`、`SCU_OVERHEATPROTECTIONSTS`、`SCU_SEATMOTORSELFLEARN`、`SCU_SEATMOTORFLT`、`SCU_CUSHIONMOTORSELFLEARN`、`SCU_CUSHIONMOTORFLT`、`SCU_SEATADJUSTSTS`、`SCU_CUSHIONADJUSTSTS`）并整行输出，原始值与计算值并列，可观测性拉满；2) 把 `drivingDisabled` 显式改为 `(speed > 3f) && (speedValid == 0)`，数据源为 `PCU_VEHICLE_SPEED` 与 `PCU_VEHICLE_SPEED_VALID`——注意这是嵌在"日志"提交里的真实逻辑变更（元数据未提，以 diff 为准）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ getCanState()
         val antiplayFaultedRead =
             settingVehicleService.readVehicleProperty(CarPropertyIds.SCU_ANTIPLAYSTS)
         val overheatFaultedRead =
             settingVehicleService.readVehicleProperty(CarPropertyIds.SCU_OVERHEATPROTECTIONSTS)
         ...（共 8 路原始信号读取）
         log(
             "getCanState: antiplayFaultedRead=$antiplayFaultedRead," + ...)
@@ drivingDisabled 改由车速信号直接判定
+        val speed = settingVehicleService.readVehicleProperty(CarPropertyIds.PCU_VEHICLE_SPEED)
+        val speedValid =
+            settingVehicleService.readVehicleProperty(CarPropertyIds.PCU_VEHICLE_SPEED_VALID)
+        drivingDisabled = (speed > 3f) && (speedValid == 0)
+
+        log("getCanState:drivingDisabled=$drivingDisabled,speed=$speed, speedValid=$speedValid")
```

## 为什么能修复
原始信号与派生判定并行打日志后，座椅调节失败时可直接从日志对出"哪一路前置信号异常"，把偶现问题的定位从猜测变为读日志；`drivingDisabled` 的车速阈值（>3km/h 且速度信号无效标志为 0）显式化，判定依据可审计。风险：`speedValid == 0` 的语义（0=有效还是 0=无效）需要与信号矩阵核对，若理解反了会错误禁止/放行座椅调节；该函数在 `onResume` 被调用（见 022cd1a8），每次回到页面会多 10 次 `readVehicleProperty`，开销可接受。

## 复盘与经验
- "打印计算值"不如"打印原始值+计算值"：并排输出才能在事后复盘中验证判定逻辑本身是否正确。
- 警惕日志提交里夹带逻辑变更（本例 `drivingDisabled` 判定重写），review 时对 SIR-XXXX 提交也要逐行看 diff。
- 车速类前置条件要同时记录信号值和 valid 标志，只记值无法判断信号可信度。
