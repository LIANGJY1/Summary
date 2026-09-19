# SIR-7329 · 离车关机开关回弹（收发信号映射接错）

- **提交**：`26f5286d` | 2026-09-10 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
模式页"离车关机"开关点击后回弹，设置状态无法保持。

## 根因分析
`component/Carlib` 的 `CarPropertyMapping.kt`（object）维护"逻辑信号 → 实际收发 VehiclePropertyId"的映射表。离车关机条目 `CarPropertyIds.MPU_TO_MCU_SHUT_DOWN` 的 wrapper 被配置成了另一组信号：send 用 `VehiclePropertyIds.MPU_TO_MCU_LEAVING_CLOSE`、rec 用 `MCU_TO_MPU_REPLY_LEAVING_CLOSE`。即开关实际写的是"离车近场关闭（LEAVING_CLOSE）"链路，而 MCU 的应答走的是 SHUT_DOWN 信号，写入与回读不闭环：set 打到错误信号、正确应答信号又未注册，真实状态回刷后开关被弹回原位（与缺陷库"修改信号/改回老信号"一致）。本提交同时把 `SceneModeFragment.kickstandDropTimeState` 默认值从 5 改为 3（离车脚撑下放时间的默认档位修正）。

## 关键代码修改
改动文件：component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt（2 文件 +3/-3）
```diff
--- component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
@@ // 离车关机
         CarPropertyIds.MPU_TO_MCU_SHUT_DOWN to CarPropertyIdWrapper(
-            sendId = VehiclePropertyIds.MPU_TO_MCU_LEAVING_CLOSE,
+            sendId = VehiclePropertyIds.MPU_TO_MCU_SHUT_DOWN,
             sendType = Int::class,
-            recId = VehiclePropertyIds.MCU_TO_MPU_REPLY_LEAVING_CLOSE,
+            recId = VehiclePropertyIds.MCU_TO_MPU_SHUT_DOWN,
             recType = Int::class
         ),
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
-    private var kickstandDropTimeState = 5
+    private var kickstandDropTimeState = 3
```

## 为什么能修复
把离车关机的收/发 ID 改回 SHUT_DOWN 这组真实信号后，开关 set 的信号与 MCU 应答/状态广播的信号一致，写入-回读闭环恢复，UI 状态不再被回刷弹回。改动仅是映射表两行 ID 替换，不影响其他条目；隐患是 LEAVING_CLOSE 这组信号若在别处仍被当作离车关机使用，需另行确认其真实语义，避免"换错对象"。

## 复盘与经验
- 车控开关回弹的两大高频根因：信号名/大小写不对（见 944a9a03）与映射表 send/rec 配错组；两者都发生在 Carlib 的"信号契约层"，联调期应逐条核对映射表而非只测 UI。
- send 信号与 rec 应答信号必须成对校验（写 A 读 B 是典型错配），可用"写后回读一致性"用例在台架上提前拦截。
- 默认值/初始档位（如 kickstandDropTimeState）属于需求规格，改动应留单号，本提交混入的无单号默认值修正增加了追溯成本。
