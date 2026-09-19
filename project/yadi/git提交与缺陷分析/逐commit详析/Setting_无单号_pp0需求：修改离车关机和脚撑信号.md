# 无单号 · pp0需求：修改离车关机和脚撑信号

- **提交**：`a7c77690` | 2026-09-03 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
按最新 CAN 规范更正离车关机、离车脚撑时间两个信号的命名与回执：常量由 `LEAVING_CLOSE/LEAVING_CLOSE_COUNT_DOWN` 更名为 `SHUT_DOWN/KICKSTAND_TIME`，并给脚撑时间信号补上此前缺失的回执 ID。

## 实现结构
- `CarPropertyIds.kt`：5505/5506 常量重命名（值不变），注释保留"内部信号"。
- `CarPropertyMapping.kt`：send/rec 包装条目更名；关键增补——脚撑时间 `recId` 从整段注释（历史上甚至误注释为 `PCU_VEHICLESPEEDVALID`）改为真实的 `MCU_TO_MPU_REPLY_KICKSTAND_TIME`，收发链路闭合。
- `SettingVehicleService.kt`：注册表与 handlerMap 同步换名，LiveData 出口（`parkingShutdownSwitch/kickstandDropTime`）不变。
- `SceneModeFragment.kt`：离车关机开关下发与脚撑倒计时下发两处引用换名。

数据流不变：开关下发 Int、脚撑时间下发单字节数组（`buildSingleByteParameter`），MCU 回执经 mapping 翻译进 LiveData。

## 关键代码
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
@@ -471,18 +471,18 @@
         // 离车脚撑时间
-        CarPropertyIds.MPU_TO_MCU_LEAVING_CLOSE_COUNT_DOWN to CarPropertyIdWrapper(
-            sendId = VehiclePropertyIds.MPU_TO_MCU_LEAVING_CLOSE_COUNT_DOWN,
+        CarPropertyIds.MPU_TO_MCU_KICKSTAND_TIME to CarPropertyIdWrapper(
+            sendId = VehiclePropertyIds.MPU_TO_MCU_KICKSTAND_TIME,
             sendType = Int::class,
-//            recId = VehiclePropertyIds.PCU_VEHICLESPEEDVALID,
-//            recType = Int::class
+            recId = VehiclePropertyIds.MCU_TO_MPU_REPLY_KICKSTAND_TIME,
+            recType = Int::class
         ),
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
@@ -1788,12 +1788,12 @@
-    const val MPU_TO_MCU_LEAVING_CLOSE: Int = 5505
+    const val MPU_TO_MCU_SHUT_DOWN: Int = 5505
-    const val MPU_TO_MCU_LEAVING_CLOSE_COUNT_DOWN: Int = 5506
+    const val MPU_TO_MCU_KICKSTAND_TIME: Int = 5506
```

实现讲解：纯协议换轨提交（更名 + 补回执），UI 与数据流零改动——又一次受益于 Carlib 收口式设计。原 mapping 中被注释的 recId（误指向车速有效信号）说明该信号此前从未真正接收过回执，本提交才让"脚撑时间下发确认"闭环。

## 复盘与要点
- 信号命名向 CAN 数据库词汇靠拢（SHUT_DOWN/KICKSTAND_TIME）比业务化命名（LEAVING_CLOSE_COUNT_DOWN）更抗需求变化——"倒计时"其实是脚撑展开时间，名字误导实现。
- "recId 注释掉 + 占位错信号"是早期接信号的常见遗留，pp0 联调期应集中清一遍 mapping 表，把所有注释占位补成真实回执。
- 值 5505/5506 未变时重命名零风险；若值也变则必须配合 instrumented 回归，两件事不要混在一个提交。
