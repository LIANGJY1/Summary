# SRS_VehSetting_040 · pp0需求：替换android.car.jar、新增湿滑模式、极致续航信号

- **提交**：`7e29ce90` | 2026-09-02 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_040（标题引用，无系统单号）

## 需求/目标
pp0 阶段联调对齐：替换新版 android.car.jar；湿滑模式/极致续航两信号正式接通收发链路（此前 mapping 表整段注释、收发不通）；开关编码切换为新规范并用带值后缀的常量显式表达；CAN 断连时湿滑/极致续航随驾驶模式一起置灰；引入滚动位置保护。

## 实现结构
- `android.car.jar`：二进制替换（framework 扩展更新）。
- `CarPropertyIds.kt`：`CCU_SLIPMODE_SET(5118)`→`CCU_SLIPPEMODE`；`CCU_EXTREMERANGE_SET(5119)` 并入既有的 `CCU_ECOFUNCSTS`（原 5122 重复定义被删除，消除"一个功能两个 ID"的隐患）。
- `CarPropertyMapping.kt`：两信号的 send/rec 映射从整段注释改为真实接线：`CCU_SLIPPEMODE↔PCU_SLIPPEMODEFEEDBACK`、`CCU_ECOFUNCSTS↔PCU_ECOFUNCSTSFEEDBACK`。
- `CanSignalConstants.kt`：重整开关常量——旧 `SWITCH_OFF_2` 删除，新增 `SWITCH_ON_2=1`/`SWITCH_OFF_1=0` 表示"新的开关信号"编码（0=无动作、1=开/关按用途），湿滑模式与极致续航的下发/回显全部切到新常量。
- `DrivingFragment.kt`：CAN 状态回调把 `isSlipModeEnabled/isExtremeRangeEnabled` 与驾驶模式同源联动；车速置灰增加 `isSlipModeEnabled`（CAN 连接）因子；所有置灰/刷新操作包进新扩展 `runWithScrollRestore {}`，防止 NestedScrollView 因重布局跳滚。
- `LightFragment.kt`：远光辅助开启确认从警告弹窗换成提示弹窗。

数据流：UI → 新开关编码 → `CCU_SLIPPEMODE/CCU_ECOFUNCSTS` 下发；PCU 反馈信号 → mapping 翻译 → LiveData → 回显与置灰联动。

## 关键代码
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
@@ -492,18 +492,18 @@
         // 湿滑模式
-        CarPropertyIds.CCU_SLIPMODE_SET to CarPropertyIdWrapper(
-//            sendId = VehiclePropertyIds.CCU_SL,
-//            sendType = Int::class,
-//            recId = VehiclePropertyIds.PCU_SLIPPEMODEFEEDBACK,
-//            recType = Int::class
+        CarPropertyIds.CCU_SLIPPEMODE to CarPropertyIdWrapper(
+            sendId = VehiclePropertyIds.CCU_SLIPPEMODE,
+            sendType = Int::class,
+            recId = VehiclePropertyIds.PCU_SLIPPEMODEFEEDBACK,
+            recType = Int::class
         ),
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -256,10 +262,16 @@
                 isDrivingModeEnabled = (it == CAN_STATUS_ENABLED)
+                isSlipModeEnabled = (it == CAN_STATUS_ENABLED)
+                isExtremeRangeEnabled = (it == CAN_STATUS_ENABLED)
                 ...
-                mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
+                mBinding.vcNestedScrollView.runWithScrollRestore {
+                    mBinding.rgDriveMode.setGrayState(isDrivingModeEnabled)
+                    updateSlipModeGrayBySpeed()
+                    mBinding.ssvExtremeRangeSetting.setGrayState(isExtremeRangeEnabled)
+                }
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CanSignalConstants.kt
@@ -9,16 +9,24 @@
+    /**
+     * 新的开关信号
+     * 0x02 开
+     * 0x01 关
+     * 0x00 No Action
+     */
+    const val SWITCH_OFF_1 = 0
+    const val SWITCH_ON_2 = 1
```

实现讲解：这个提交把"功能占位"变成"功能可用"：mapping 表的注释取消意味着 send/rec 双向真正注册到 CarService。`runWithScrollRestore` 是典型的 UI 细节打磨——CAN 状态高频回调触发 setGrayState 重布局时保持滚动位置，避免列表跳动。常量 `SWITCH_ON_2/SWITCH_OFF_1` 用数字后缀记录编码值，注释与常量并存但注释（0x02 开/0x01 关）与当前用法（1=开）存在张力，属于规范仍在漂移的中间态。

## 复盘与要点
- 同一功能出现两个 propertyId（5119 与 5122）说明前期各接各的，联调前应先做一遍 ID 冲突扫描；本提交删除重复定义是必要的还债。
- "mapping 表先注释占位、联调时接通"是可取的渐进策略，但要在票面记录接通时点，否则易被误认为已通。
- 置灰类高频回调配合 NestedScrollView 需要 `runWithScrollRestore` 这类包装，值得沉淀为全局扩展（本提交正是这么做的）。
