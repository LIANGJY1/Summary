# 无单号 · 优化 L2A 重复值回调频繁刷新问题

- **提交**：`b24f27ca` | 2026-09-16 | sgh | Setting | bugfix/优化
- **缺陷库**：未关联单号

## 问题
车辆信号（L2A/CommState）回调频繁上送相同值，导致 `SettingVehicleService` 下游 LiveData 反复 postValue，界面无意义刷新。

## 根因分析
`SettingVehicleService` 的内部类 `SICommStateCallback.onCommState()` 原逻辑把去重条件写在 if 判断里：`if (id != null && "dsi_lux" != id && lastCommStateValues[id] != value)`，if 体内只更新缓存 `lastCommStateValues[id] = value` 并打日志。关键缺陷在于 if 块之后还有一段 `when (id)` 分发（HUD_SWITCH、HUD_BRIGHT_ADJUST 等逐个 `postValue`）：当值与缓存相同时，if 条件整体为 false，控制流直接落到 `when(id)`，相同值照样分发到下游——去重只挡住了"缓存写入+日志"，没挡住"业务分发"，这就是频繁刷新的来源。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@         override fun onCommState(id: String?, value: Int) {
-            if (id != null && "dsi_lux" != id && lastCommStateValues[id] != value) {
-                lastCommStateValues[id] = value
-                LogUtils.d(TAG, "onCommState->id: $id, value: $value")
+            if (id != null && "dsi_lux" != id) {
+                if (lastCommStateValues[id] == value) {
+                    return
+                }
+                lastCommStateValues[id] = value
+                LogUtils.d(TAG, "onCommState->id: $id, value: $value")
             }
             when (id) {
                 CarPropertyIds.HUD_SWITCH -> { hudSwitchSetting.postValue(value) }
                 ...
```

## 为什么能修复
去重从"守卫缓存"升级为"守卫整个回调"：相同值直接 `return`，不再进入后续 `when(id)` 分发，HUD 相关 LiveData 不再收到重复 post，下游订阅者（各设置页开关）刷新次数与真实状态变化次数对齐。副作用：`dsi_lux` 仍被排除在去重外（沿用原设计，亮度类信号可能需要每次透传）；若下游有依赖"每次回调都触发"的心跳类逻辑（目前看没有），会被影响。

## 复盘与经验
- 去重判断的位置决定其效力：只包住"记缓存"而放在"分发"之前 if 外，等于没去重——条件必须覆盖到所有会消费值的路径。
- 回调去重是信号量型车机应用的标配（CAN 信号周期性重发是常态），建议在 BaseManager/回调入口统一做，而不是每个业务点各自判断。
- 注意 `postValue` 会合并但不会去重，相同值 post 依然触发 observer——不能指望 LiveData 兜底。
