# SIR-7378 · 3D车模续航里程WMTC与充电桩里程不一致，GB一致
- **提交**：`3657f86e` | 2026-09-03 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模充电页展示的续航里程与 Energy（能量中心）充电桩页面数值不一致；当 Energy 切到 WMTC 模式时，3D 车模仍显示 GB 续航。

## 根因分析
Launcher 的 `KanziSignalMapping` 只注册了 `ENERGY_EEM_REMAINING_GB_RANGE`（GB 续航）一个信号回调，单一缓存字段 `mChargingMileage` 只会随 GB 信号刷新，随后通过 `updateChargingMileageToKanzi()` 把该值写进 Kanzi 的 `CHARGING_MILEAGE`。而 Energy 应用端会按 `CRUISE_MILEAGE_DISPLAY_MODE_SETTING`（1=WMTC、2=GB）切换显示口径，因此用户切到 WMTC 模式后，充电桩页面显示 WMTC 里程，3D 车模却永远显示 GB 里程。另外 `KanziDataSourceManager.sendChargingMileageInitStateToKanzi()` 初始化时也只查询 GB 属性，冷启动进入时同样只有 GB 值。本质是"信号订阅面与 UI 显示口径不匹配"：上游有两种续航口径加一个模式开关，下游只消费了其中一种。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java`、`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
-    // 可行驶里程：缓存原始值和超时标志
-    private float mChargingMileage = 0f;
+    private static final int RANGE_MODE_WMTC = 1;  // CRUISE_MILEAGE_DISPLAY_MODE_SETTING: 1=WMTC
+    private static final int RANGE_MODE_GB = 2;    // CRUISE_MILEAGE_DISPLAY_MODE_SETTING: 2=GB
+    private float mChargingMileageGb = 0f;
+    private float mChargingMileageWmtc = 0f;
+    private int mRangeDisplayMode = RANGE_MODE_WMTC;  // 与 Energy 一致：默认 WMTC
```

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
         signalHandlers.put(CarPropertyIds.ENERGY_EEM_REMAINING_GB_RANGE, event -> {
-            mChargingMileage = ((Number) event.getValue()).floatValue();
+            mChargingMileageGb = ((Number) event.getValue()).floatValue();
+            updateChargingMileageToKanzi();
+        });
+        signalHandlers.put(CarPropertyIds.ENERGY_EEM_REMAINING_WMTC_RANGE, event -> {
+            mChargingMileageWmtc = ((Number) event.getValue()).floatValue();
+            updateChargingMileageToKanzi();
+        });
+        // 续航里程显示模式（1=WMTC, 2=GB），非GB值一律回落WMTC（与 Energy 应用逻辑一致）
+        signalHandlers.put(CarPropertyIds.CRUISE_MILEAGE_DISPLAY_MODE_SETTING, event -> {
+            int mode = ((Number) event.getValue()).intValue();
+            mRangeDisplayMode = (mode == RANGE_MODE_GB) ? RANGE_MODE_GB : RANGE_MODE_WMTC;
             updateChargingMileageToKanzi();
         });
```

```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
     private void updateChargingMileageToKanzi() {
-        float kanziValue = (mMileageTimeoutFlag == 1) ? -1f : mChargingMileage;
+        // 按当前续航显示模式选择 GB 或 WMTC 值（超时标志=1 → -1 故障）
+        float rawMileage = (mRangeDisplayMode == RANGE_MODE_GB) ? mChargingMileageGb : mChargingMileageWmtc;
+        float kanziValue = (mMileageTimeoutFlag == 1) ? -1f : rawMileage;
         kanziManager.setValue("", KanziType.CarModel.CHARGING_MILEAGE, Float.valueOf(kanziValue));
     }
```

配套：`VehicleService` 的订阅属性表中新增 `ENERGY_EEM_REMAINING_WMTC_RANGE` 与 `CRUISE_MILEAGE_DISPLAY_MODE_SETTING`；`KanziDataSourceManager.sendChargingMileageInitStateToKanzi()` 初始化时同时查询 GB/WMTC/模式三值并传入 `initChargingMileageState(gbMileage, wmtcMileage, rangeMode, timeoutFlag)`。

## 为什么能修复
修复把"单一 GB 值"升级为"GB + WMTC 双缓存 + 显示模式选择器"，`updateChargingMileageToKanzi()` 按 `mRangeDisplayMode` 选择与 Energy 相同口径的值下发，三个信号（GB、WMTC、模式）任一变化都会重新计算，初始化路径也对齐，两条链路（信号刷新、冷启动初值）均不再漏。模式值做了归一化（非 2 一律按 WMTC），避免非法值导致黑屏口径；潜在隐患是 Launcher 与 Energy 两端对"默认 WMTC、非法值回落"的约定靠注释维系，任何一端单改约定仍会再次不一致。

## 复盘与经验
- 跨应用显示一致性 bug 的典型形态：同一物理量有多个口径（GB/WMTC）+ 一个模式开关，订阅方只接了其中一个口径。排查"两边显示不一致"先对齐双方的信号源清单与模式约定。
- 修复时应同时覆盖"信号刷新"与"初始状态查询"两条数据路径（本例 `sendChargingMileageInitStateToKanzi` 与 `signalHandlers` 同步改造），漏掉初始化路径会出现冷启动首帧错误。
- 对模式类枚举做归一化兜底（非法值回落默认档），防止协议扩展或异常值打穿显示逻辑。
- VehicleService 的属性订阅表是"数据入口总闸"，新增监听信号时必须记得同步登记，否则 handler 写了也收不到事件。
