# 无单号 · 修正氛围灯模式信号映射笔误
- **提交**：`4a5f26df` | 2026-08-31 | liqingqing | Launcher | bugfix（一行修正，无关联单号）
- **缺陷库**：未关联单号（无缺陷记录）

## 问题
前一提交 `e4bcd0ba` 在 `CarPropertyMapping.kt` 中新增氛围灯映射时，"模式信号"的底层 recId 误写成了"开关信号"的值，导致氛围灯模式（常亮/呼吸/闪烁）状态在 3D 车模上与开关状态串线。

## 根因分析
`CarPropertyMapping.kt` 的映射表条目为 `CarPropertyIds.THREE_D_MODEL_LCM_IPMODESET to CarPropertyIdWrapper(recId = ...)`：应用侧 key 用的是模式信号常量（6228），但 `recId` 却填了 `VehiclePropertyIds.LCM_IPSWITCHSTS`（开关），复制上一行（开关信号条目）后只改了 key 忘了改 recId。结果是：上层订阅/查询模式信号时，底层实际读写的是开关属性，模式永远取到开关的值。

## 关键代码修改
改动文件：component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt
```diff
--- a/.../map/CarPropertyMapping.kt
         CarPropertyIds.THREE_D_MODEL_LCM_IPMODESET to CarPropertyIdWrapper(
-            recId = VehiclePropertyIds.LCM_IPSWITCHSTS,
+            recId = VehiclePropertyIds.LCM_IPMODESET,
             recType = Int::class
         ),//氛围灯模式信号
```

## 为什么能修复
recId 与 key 对齐后，模式信号的应用属性（6228）正确路由到底层 `LCM_IPMODESET`，读写均落在真实模式属性上，串线消除。修复本身零风险；值得警惕的是它出现在 e4bcd0ba 之后仅 10 分钟，属于"上一提交的自纠错"，若无该紧跟提交，氛围灯模式将整体失效。

## 复盘与经验
- 成对/成组常量（开关 vs 模式、左 vs 右）在映射表中紧邻排列，是"复制上一行改一半"笔误的温床；review 时应对映射表做逐行 key-recId 语义核对。
- 修复型提交紧跟原提交自纠，说明提交前缺少最小验证（真机上氛围灯模式一动就露馅）；信号类改动至少做一次端到端联调再合入。
