# SIR-5436 · ABS 信号不通
- **提交**：`947f159c` | 2026-07-31 | sgh | Setting/Carlib | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **注意**：缺陷库中该单的根因为"已解决"、方案为"修改颜色"，与 diff 实际内容（补信号映射表）明显不符，疑为缺陷库填写错乱，本文以 diff 为准。

## 问题
车控设置中 ABS 开关的信号链路不通：下发/接收 ABS 控制信号无响应。

## 根因分析
提交消息自述根因"代码遗漏"、方式"还原代码"。核心在 `component/Carlib` 的 `CarPropertyMapping.kt`：这是车控信号"发送 ID ↔ 接收 ID"的总映射表，表中 TCS（`CCU_MTC_CONTROL_MODE`）等相邻条目都在，唯独 ABS 条目缺失——发送 `VehiclePropertyIds.CCU_ABS_CONTROL_MODE`、接收 `VehiclePropertyIds.ABS_CONTROL_MODE_RSP` 的 `CarPropertyIdWrapper` 映射没有被注册。映射表缺行，上层 `VehicleControlFragment` 触发 ABS 控制时查不到对应的收发 ID，信号自然"不通"。另有两处调试代码清理：`VehicleControlFragment` 中 `pcuActualGear` 观察者里的 `updateHudState(gear==0)` 与加热超时回滚的 `showToast(toastContent)` 被注释，属测试辅助代码下线。

## 关键代码修改
改动文件：`component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt`
```diff
--- component/Carlib/.../map/CarPropertyMapping.kt
+        //abs
+        CarPropertyIds.CCU_ABS_CONTROL_MODE to CarPropertyIdWrapper(
+            sendId = VehiclePropertyIds.CCU_ABS_CONTROL_MODE,
+            sendType = Int::class,
+            recId = VehiclePropertyIds.ABS_CONTROL_MODE_RSP,
+            recType = Int::class
+        ),
         //TCS
         CarPropertyIds.CCU_MTC_CONTROL_MODE to CarPropertyIdWrapper(
--- application/Setting/.../fragment/VehicleControlFragment.kt
-                updateHudState(gear==0)
+//                updateHudState(gear==0)
...
-                    showToast(toastContent)
+//                    showToast(toastContent)
```

## 为什么能修复
映射表补上 ABS 条目后，信号收发框架能按 `sendId=CCU_ABS_CONTROL_MODE` 下发、按 `recId=ABS_CONTROL_MODE_RSP` 接收应答，链路打通；与相邻 TCS 条目结构完全对称，风险低。隐患：注释掉的 `updateHudState`/`showToast` 若是正式需求（HUD 档位联动、超时提示）而非调试代码，则属于功能被误伤，需与需求确认；同样以注释而非删除保留，留有死代码。

## 复盘经验
- 车控信号"不通"类问题先查映射表注册：收发 ID 映射是信号链路的总开关，漏一行就是整条信号瘫痪，且编译期完全无感。
- 新增车控功能时按信号清单（CCU 发送/接收对照表）逐条核对映射表，可用单元测试遍历 `CarPropertyMapping` 与整车信号 DB 的差集。
- 缺陷库的根因/方案字段（本单填"修改颜色"）与代码事实脱节时，复盘必须回到 diff 取证，否则统计结论全歪。
