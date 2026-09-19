# 无单号 · 新增氛围灯开关/模式信号 id（3D 车模）
- **提交**：`e4bcd0ba` | 2026-08-31 | liqingqing | Launcher | 信号接入修正（无关联单号）
- **缺陷库**：未关联单号（无缺陷记录）

## 问题
3D 车模需要读取氛围灯开关/模式信号，此前订阅的是 `android.car.VehiclePropertyIds.LCM_IPSWITCHSTS/LCM_IPMODESET` 等标准属性，实际车控协议使用的是 3D 车模专用属性段（0x… 自定义 ID），导致氛围灯状态在 Kanzi 上不刷新/初始值取不到。

## 根因分析
属于信号源对接修正而非传统代码 bug：`KanziSignalMapping.signalHandlers` 的注册 key、`KanziDataSourceManager.sendAmbientLightInitStateToKanzi()` 的初始查询、`VehicleService` 的订阅列表三处都指向旧的 `CarPropertyIds.SCREEN_AMBIENT_LIGHT_SWITCH/MODE`（本批次前一提交 52d7fb85 中刚临时换成 `VehiclePropertyIds.LCM_IP*`），与底软实际发布的属性不匹配。本提交在 `component/Carlib` 的 `CarPropertyIds.kt` 中新增专用常量 `THREE_D_MODEL_LCM_IPSWITCHSTS=6227`、`THREE_D_MODEL_LCM_IPMODESET=6228`，并在 `CarPropertyMapping.kt` 中建立"应用属性 → 底层 VehiclePropertyIds"的映射，三处调用统一切换。

## 关键代码修改
改动文件：CarPropertyIds.kt、CarPropertyMapping.kt、KanziSignalMapping.java、KanziDataSourceManager.java、VehicleService.java
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
+    const val THREE_D_MODEL_LCM_IPSWITCHSTS : Int = 6227  // 氛围灯开关信号
+    const val THREE_D_MODEL_LCM_IPMODESET : Int = 6228    // 氛围灯模式信号
```
```diff
--- a/.../launcher/control/KanziSignalMapping.java
-        signalHandlers.put(VehiclePropertyIds.LCM_IPSWITCHSTS, event -> { ... });
-        signalHandlers.put(VehiclePropertyIds.LCM_IPMODESET, event -> { ... });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_IPSWITCHSTS, event -> { ... });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_IPMODESET, event -> { ... });
```
`VehicleService` 订阅数组与 `KanziDataSourceManager` 初始查询同步替换为新常量。

## 为什么能修复（说明）
订阅 key、初始查询、映射表三者一致指向车模专用属性后，MCU 发出的氛围灯状态才能被回调接收并透传给 Kanzi。此类修复无逻辑风险，风险点在于属性 ID 必须与通信矩阵严格一致——紧随其后的 4a5f26df 就修正了本提交映射表里的一个笔误（见该文件），说明"新增信号 id"极易发生复制粘贴错位。

## 复盘与经验
- 车机信号接入是"四点一致"工程：常量定义、订阅注册、初始查询、映射表，漏改或错改任意一点都表现为"信号收不到"。
- 信号 id 从通信矩阵生成时建议用脚本或单一映射表维护，手抄常量（6227/6228）是笔误高发区。
- 该提交无单号、影响等级标 B，实际是底软协议对齐类改动，建议此类对接修正也开单跟踪以便回归。
