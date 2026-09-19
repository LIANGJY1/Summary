# 无单号 · 控制中心增加极致续航状态显示
- **提交**：`21c2ea45` | 2026-09-14 | caohongliang | SystemUI | 功能新增（提交头标为 fixbug 但 diff 为纯新增能力，无缺陷修复语义）
- **缺陷库**：未关联单号（无 defs 记录）

## 问题
控制中心（下拉快捷面板）没有"极致续航"模式的状态显示，用户无法从面板感知当前是否处于极致续航档位。属于功能补齐，不是缺陷。

## 根因分析
链路上缺两段：一是 `VehicleMsgCmdControllerService`（继承 `BaseManager implements CarServiceManager` 的车控信号服务）的 `signals` 订阅列表里没有 `CarPropertyIds.CCU_ECOFUNCSTS`，车辆档位/极致续航信号根本不会上报回调；二是 `QuickSettingFragment.setSingleChooseView()` 只处理单选按钮视图，未对任何"行程模式"做 observe，UI 层无人消费该状态。因此即使信号来了也没有展示出口。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/vehicle/VehicleMsgCmdControllerService.java；application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/vehicle/VehicleMsgCmdControllerService.java
+++ b/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/vehicle/VehicleMsgCmdControllerService.java
@@ -28,10 +28,12 @@
     private List<Integer> signals = Arrays.asList(
             CarPropertyIds.PCU_VEHICLE_SPEED,
-            CarPropertyIds.ENERGY_PCU_ACTUALGEAR
+            CarPropertyIds.ENERGY_PCU_ACTUALGEAR,
+            CarPropertyIds.CCU_ECOFUNCSTS
     );
     public MutableLiveData<Boolean> isPSwitch = new MutableLiveData<>(true);  //NOSONAR
     public MutableLiveData<Float> speed = new MutableLiveData<>(0f);  //NOSONAR
+    public MutableLiveData<Integer> tripMode = new MutableLiveData<>(0);
@@ -81,6 +83,8 @@
             } else if (event.getPropertyId() == CarPropertyIds.PCU_VEHICLE_SPEED) {
                 speed.postValue((Float) event.getValue());
+            } else if (event.getPropertyId() == CarPropertyIds.CCU_ECOFUNCSTS) {
+                tripMode.postValue((int) event.getValue());
             }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
@@ -173,8 +173,20 @@
     private fun setSingleChooseView() {
         LogUtils.d(TAG, "----setSingleChooseView----")
+        vehicleMsgCmdControllerService.tripMode.observe(viewLifecycleOwner) {
+            val isExtremeRangeEnabled = it == CanSignalConstants.SWITCH_ON
+            LogUtils.d(TAG, "Extreme range state changed: $it")
+            qdvTripMode.isSelected = isExtremeRangeEnabled
+            qdvTripMode.setImageDrawable(
+                requireContext().getDrawable(
+                    if (isExtremeRangeEnabled) R.drawable.vector_ultimate_battery_sel
+                    else R.drawable.vector_ultimate_battery_unsel
+                )
+            )
+        }
     }
```

## 为什么能修复（功能落地）
服务端订阅 `CCU_ECOFUNCSTS` 并转成 `tripMode: MutableLiveData<Integer>`，UI 端 `QuickSettingFragment` observe 后按 `CanSignalConstants.SWITCH_ON` 切换 `qdvTripMode` 的 sel/unsel 图标，形成"信号→LiveData→观察者刷图标"的完整链路。隐患：LiveData 默认粘性，面板每次重建会立即收到最近一次值，正好满足状态回显需求；但默认值 0（关）在信号尚未首报时会短暂显示未开启态。

## 复盘与经验
- 车控信号展示类需求的标准三步：`signals` 列表加订阅 ID → 服务内 `onEvent` 分发到对应 LiveData → UI observe 刷视图，缺一环即"信号无声"。
- 这类提交打着 [fixbug] 前缀实为功能新增，分析时以 diff 实际为准（本文件已按功能新增归类）。
- 用 LiveData 承载车况状态天然获得粘性回显，面板重建无需手动同步，但要注意初始默认值与真实状态的短暂不一致窗口。
