# SIR-4307 · 发送充电信息信号3D车充电信息不显示

- **提交**：`4bda748a` | 2026-08-05 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
发送充电信息信号后，3D 车模上充电功率等充电信息不显示。

## 根因分析
充电功率信号订阅与读取用错了属性与类型：`KanziSignalMapping` 把 `ENERGY_OBC_SLOWCHARGCAPSETFEED`（OBC 慢充容量反馈，float 型"容量"信号）当作"充电功率"缓存为 `float mChargingPower` 并推给 Kanzi；而车辆实际上报的充电功率是 `ENERGY_EEM_CHARGING_POWER`（int，单位 W）。信号源错配导致 Kanzi 收到的值语义/量纲全错（容量值冒充功率），3D 车模校验不过不渲染，充电信息整体不显示。同时电流/电压也按 float 读取，与 int 型信号不符（缺陷库：传递的数据类型有误）。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java、application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java、application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
-        signalHandlers.put(CarPropertyIds.ENERGY_OBC_SLOWCHARGCAPSETFEED, event -> {
-            mChargingPower = ((Number) event.getValue()).floatValue();
+        signalHandlers.put(CarPropertyIds.ENERGY_EEM_CHARGING_POWER, event -> {
+            mChargingPowerWatts = ((Number) event.getValue()).intValue();
             updateChargingPowerToKanzi();
         });
...
     private void updateChargingPowerToKanzi() {
-        float kanziValue = (mChargingPowerTimeoutFlag == 1) ? -1f : mChargingPower;
+        // 车辆信号单位为 W，发送 Kanzi 时转换为 kW
+        float kanziValue = (mChargingPowerTimeoutFlag == 1) ? -1f : mChargingPowerWatts / 1000f;
```
```diff
// KanziSignalMapping.java 剩余时间/电流/电压改整型传值
-        float kanziValue = (mChargingTimeoutFlag == 1) ? -1f : mChargingRemainingTime;
-        kanziManager.setValue("", KanziType.CarModel.CHARGING_REMAINING_TIME, String.valueOf(kanziValue));
+        int kanziValue = (mChargingTimeoutFlag == 1) ? -1 : Math.round(mChargingRemainingTime);
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_REMAINING_TIME, kanziValue);
```
配套：`KanziDataSourceManager.sendChargingPowerInitStateToKanzi()` 由 `getFloatProperty(ENERGY_OBC_SLOWCHARGCAPSETFEED)` 改为 `getIntProperty(ENERGY_EEM_CHARGING_POWER)`；`VehicleService` 属性订阅清单同步替换；电流/电压/剩余时间的缓存与 `setValue` 从 float/字符串改为 int 数值直传。

## 为什么能修复
信号源从"慢充容量反馈"换成真正的 `ENERGY_EEM_CHARGING_POWER`（int·W），并做 W→kW 量纲转换后推给 Kanzi，3D 车模收到的功率值语义、类型、单位全部正确，充电信息恢复显示；init 查询与事件订阅两条路径同步替换，冷启动与运行中一致。剩余时间新增 `Math.round` 保留原 0.0~2880 语义到整型区间（0~2000），注意与 Kanzi 侧校验区间一致。

## 复盘与经验
- 车况信号接入要以 DBC/属性表为准核对"属性 ID + 类型 + 单位 + 量纲"四要素，用相近名字的信号顶替（SLOWCHARGCAPSETFEED vs CHARGING_POWER）是典型错配。
- 传给渲染引擎（Kanzi）的值尽量用原生数值类型并带上单位换算，`String.valueOf(float)` 这类隐式字符串化会掩盖类型错误。
- 信号接线通常散落在"订阅清单 + handler 表 + init 回读"三处，改信号源时必须三处同步，漏一处就会出现冷启动数据不显示。
