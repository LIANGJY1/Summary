# SRSENERGY001 · 新增家用充电枪信号

- **提交**：`5afd255f` | 2026-08-28 | liqingqing | EnergyManagement | feature
- **关联单**：SRSENERGY001

## 需求/目标
接入"家用充电枪连接状态"车信号（OBC 家用充电口插枪状态），供能量中心展示家用充电枪连接与否。

## 实现结构
3 个文件、+13/-1，标准"加信号"三件套：`CarPropertyIds.kt` 定义 `ENERGY_EEM_OBC_HOPLUGCONNECTIONSTATUS = 4054`（0=未连接，1=连接，处于 ENERGY 段 4053~4099）；`CarPropertyMapping.kt` 建立 自定义 ID → `VehiclePropertyIds.OBC_HOPLUGCONNECTIONSTATUS`（Int）的收信号映射；能量中心 `VehicleService.java` 把该 ID 追加进监听信号列表。

## 关键代码
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
+        CarPropertyIds.ENERGY_EEM_OBC_HOPLUGCONNECTIONSTATUS to CarPropertyIdWrapper(
+            recId = VehiclePropertyIds.OBC_HOPLUGCONNECTIONSTATUS,
+            recType = Int::class
+        ),//家用充电枪连接状态
```
```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/module/VehicleService.java
-            CarPropertyIds.CRUISE_MILEAGE_DISPLAY_MODE_SETTING
+            CarPropertyIds.CRUISE_MILEAGE_DISPLAY_MODE_SETTING,
+            CarPropertyIds.ENERGY_EEM_OBC_HOPLUGCONNECTIONSTATUS
```
实现讲解：项目把车信号访问统一抽象为 Carlib 的"自定义 ID + Mapping"机制：应用只认 CarPropertyIds 常量，映射层负责换算到 HAL 的 VehiclePropertyIds；因此新增一个信号只需"定义 ID + 注册映射 + 加入监听列表"三步，应用层即可经 VehicleService 收到事件。这是很值得借鉴的信号接入范式。

## 复盘与要点
- 三件套改动高度模板化，未来可由信号清单（CSV/DSL）自动生成，减少手抄 4054 这类 ID 出错。
- 注释里写清了取值语义（0/1），比多数信号定义规范；UI 侧消费逻辑在本提交未体现，应在后续 UI 提交中验证插拔枪刷新。
