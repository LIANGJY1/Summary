# SIR-8489 · 3D车模充电中止状态文言显示"充电电量0.0kWh"

- **提交**：`5b5443cd` | 2026-09-16 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模充电中状态一段时间后切到"充电中止"，界面文言显示"充电电量0.0kWh"，实际充电电量并非 0。

## 根因分析
充电电量属性 `KanziType.CarModel.CHARGING_ALREADY_POWER` 在 Kanzi 端定义为 **float** 类型，但 Java 侧两处下发都用 `String.valueOf(...)` 包装成字符串：信号变化路径 `signalHandlers.put(CarPropertyIds.ENERGY_CHARGEEGY, ...)` 和初始态查询路径 `KanziDataSourceManager` 的 `getFloatProperty` 回读。类型不匹配导致 Kanzi 解析失败，属性一直保持默认值 0.0，渲染端叠加文言即显示"充电电量0.0kWh"。初始值路径的问题尤其致命：连接建立时先把 0.0 写进去，之后信号变化路径同样发字符串，永远无法纠正。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java、application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
         signalHandlers.put(CarPropertyIds.ENERGY_CHARGEEGY, event -> {
             float alreadyPower = ((Number) event.getValue()).floatValue();
             LogUtils.d(TAG, "Charging already power changed: " + alreadyPower);
-            kanziManager.setValue("", KanziType.CarModel.CHARGING_ALREADY_POWER, String.valueOf(alreadyPower));
+            // [bugfix] 充电电量在 Kanzi 端属性定义为 float 类型，
+            // 原代码用 String.valueOf(float) 下发会被当成字符串，
+            // Kanzi 解析失败 → 一直显示 0.0kWh。改为 Float.valueOf()，
+            // 与下方 CHARGING_MILEAGE (line 824) 写法保持一致。
+            kanziManager.setValue("", KanziType.CarModel.CHARGING_ALREADY_POWER, Float.valueOf(alreadyPower));
         });
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
             kanziManager.setValue("", KanziType.CarModel.CHARGING_ALREADY_POWER,
-                    String.valueOf(alreadyPower != null ? alreadyPower : 0f));
+                    Float.valueOf(alreadyPower != null ? alreadyPower : 0f));
```

## 为什么能修复
两处下发统一改为 `Float.valueOf(...)`，与 Kanzi 端 float 属性类型对齐后解析成功，初始值与变化值都能正确写入，文言中的电量数值恢复正常。同文件 `CHARGING_MILEAGE`（里程）本就如此写，本次是把漏网点对齐。风险极低；教训点是：`setValue(key, Object)` 这类宽接口对类型错误不报编译错，Kanzi 侧静默失败为默认值，必须靠联调发现。

## 复盘与经验
- 跨进程/跨引擎（Java→Kanzi）的 setValue 宽接口是类型安全盲区，封装层应按属性类型提供 setValueFloat/setValueString 强类型入口。
- "初始态查询 + 运行时回调"两条下发路径必须同步修：只修回调，重连/进页面时仍会被旧初始值污染（本提交两处都改，示范正确做法）。
- 渲染端解析失败通常静默回落默认值（0.0），排查"数值恒为 0"类问题先查下发类型而非信号源。
