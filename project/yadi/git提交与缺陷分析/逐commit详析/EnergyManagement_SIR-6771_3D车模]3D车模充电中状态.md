# SIR-6771 · 3D 车模充电中续航里程一直显示 0.0
- **提交**：`4c481545` | 2026-09-02 | liqingqing | EnergyManagement（实际改 Launcher 模块） | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模
- **注**：提交归属 EnergyManagement 批次，但 diff 实际只改 `application/Launcher/.../KanziSignalMapping.java`（3D 车模 Kanzi 信号映射），以 diff 为准。

## 问题
3D 车模处于充电中状态时，续航里程（Charging_Mileage）一直显示 0.0。

## 根因分析
`KanziSignalMapping` 向 3D 车模引擎（Kanzi）下发充电里程时，调用 `kanziManager.setValue("", KanziType.CarModel.CHARGING_MILEAGE, String.valueOf(kanziValue))`——把 float 值转成字符串 `"12.3"` 传入。`KanziType.CarModel.CHARGING_MILEAGE` 这个信号在 Kanzi 侧是 float 类型属性，`setValue` 按信号类型路由后字符串无法正确写入 float 属性（写入失败或被解析为 0），车模端一直渲染默认值 0.0。典型的"同名字段、跨端类型不一致"问题：Java 侧 `mChargingMileage` 是 float，通道却按字符串传递。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`
```diff
--- .../launcher/control/KanziSignalMapping.java
@@ setChargingMileage()（充电里程下发）
-        kanziManager.setValue("", KanziType.CarModel.CHARGING_MILEAGE, String.valueOf(kanziValue));
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_MILEAGE, Float.valueOf(kanziValue));
@@ initChargingMileageState() 充电状态映射
             case 0x2:
+                kanziState = 6;
+                break;
             case 0x3:
                 kanziState = 3;
                 break;
```
（第二处 hunk 将充电状态 0x2 从"落入 0x3 分支映射 kanziState=3"改为独立映射 kanziState=6，修正充电状态机映射。）

## 为什么能修复
`Float.valueOf(kanziValue)` 使信号值以原生 float（装箱为 Float 对象）经 `KanziManager.setValue` 通道下发，与 Kanzi 侧 CHARGING_MILEAGE 的 float 属性类型匹配，写入生效，车模显示真实里程。case 0x2 独立映射修正充电中状态展示。隐患：若其他 CHARGING_* 信号也存在同样的 String/Float 混传，需同类排查；`setValue` 通道本身对类型不匹配不报错（只写失败），建议在映射层加类型断言或日志。

## 复盘与经验
- 跨引擎（Kanzi/Unity 等 3D 渲染端）传值必须核对每个信号的远端类型，`String.valueOf` 兜底式转换只会把失败推迟到渲染端表现为"永远 0.0"。
- "一直显示默认值"类问题，先验证写入通道的类型与路径是否匹配，再怀疑数据源。
- switch 的 case 落空（0x2 与 0x3 共用分支）是映射表常见错误，新增枚举值时每个取值都应有独立断言。
