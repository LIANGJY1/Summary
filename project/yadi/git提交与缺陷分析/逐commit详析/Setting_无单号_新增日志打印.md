# 无单号 · 新增日志打印

- **提交**：`0e64bcfe` | 2026-07-09 | sgh | Setting | feature（实为联调诊断日志）
- **关联单**：无

## 需求/目标
在驾驶设置页与车控页显示时主动读取并打印 CAN 车控信号当前值（驾驶模式/能量回收/TCS/ABS/座椅），用于排查"页面显示状态与车端实际状态不一致"类问题——作者自注"测试专用后期删掉"。

## 实现结构
改动 2 个文件、纯新增 30 行：
- `ui/fragment/DrivingFragment.kt`：新增 `onHiddenChanged(!hidden)` 钩子 + `getCanState()`，读 `CAN_DIVRING_STATUS / CAN_TCS_STATUS / CAN_ABS_STATUS` 三个属性并 log。
- `ui/fragment/VehicleControlFragment.kt`：同模式，读 `CAN_SEAT_STATUS`。

数据流：Fragment 从隐藏变为可见 → `settingVehicleService.readVehicleProperty(CarPropertyIds.*)` 同步读当前信号 → `log()` 输出，无任何 UI/状态写入。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
+    override fun onHiddenChanged(hidden: Boolean) {
+        super.onHiddenChanged(hidden)
+        if (!hidden) {
+            getCanState()
+        }
+    }
+
+    /***
+     *  测试专用后期删掉
+     * 获取驾驶模式，能量回收，坡道驻车，陡坡缓降，TCS，ABS的状态
+     */
+    fun getCanState() {
+        val driveModeState = settingVehicleService.readVehicleProperty(CarPropertyIds.CAN_DIVRING_STATUS)
+        val tcsModeState = settingVehicleService.readVehicleProperty(CarPropertyIds.CAN_TCS_STATUS)
+        val absModeState = settingVehicleService.readVehicleProperty(CarPropertyIds.CAN_ABS_STATUS)
+        log( "getCanState: driveModeState=$driveModeState, tcsModeState=$tcsModeState, absModeState=$absModeState")
+    }
```
实现讲解：选点在 `onHiddenChanged` 而非 `onResume`——车机设置页多用 `show/hide` 复用实例，`onResume` 不随页面切换触发；"变为可见"这一刻读一次信号快照，正好对齐"用户看到什么/车端是什么"的比对诉求。

## 复盘与要点
- "测试专用后期删掉"注释是典型技术债欠条：联调结束无人记得删，建议此类临时代码用统一标记（`// TODO-DEBUG:`）供 CI 扫描清退。
- 可复用手法：调试 CAN 信号不一致问题时，"页面可见时读快照 + 打日志"是最低成本手段，比逐条追 PropertyChange 回调更直观。
- 风险：`readVehicleProperty` 若为阻塞型 binder 调用，在主线程 `onHiddenChanged` 里同步读 3 个属性可能造成掉帧，量大时应切 IO 线程。
