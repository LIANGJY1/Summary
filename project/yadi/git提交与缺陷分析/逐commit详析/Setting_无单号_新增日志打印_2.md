# 无单号 · Setting 车控服务 L2A 发送日志拆分
- **提交**：`9fb9b59a` | 2026-09-15 | sgh | Setting | 日志增强（无关联单号）
- **缺陷库**：未关联单号

## 问题
无行为缺陷。`SettingVehicleService.sendL2A` 的日志把连接状态与指令内容混在一条里，排查 L2A（车控指令下发）问题时过滤和比对不便。

## 根因分析（改动说明）
纯日志变更：`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt` 中，原一条 `LogUtils.d` 同时输出 `l2aConStatus`、`mL2AIsReady`、`id`、`value` 四个字段；现拆成两条——第一条只保留链路状态（连接状态/就绪标志），第二条单独输出指令 `id` 与 `value`。使"按指令 id 过滤"与"按连接状态过滤"两条排查线互不干扰，也避免长日志被截断。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ -659,8 +659,9 @@
     fun sendL2A(id: String, value: Int) {
         LogUtils.d(
             TAG,
-            "sendL2A->l2aConStatus: $l2aConStatus, mL2AIsReady: $mL2AIsReady, id: $id, value: $value"
+            "sendL2A->l2aConStatus: $l2aConStatus, mL2AIsReady: $mL2AIsReady"
         )
+        LogUtils.d(TAG, "sendL2A->id: $id, value: $value")
         if (mL2AIsReady && l2aConStatus == L2A_STATUS_CONNECTED) {
             IviCommManager.getInstance()?.sendCommValue(id, "Set", value)
         }
```

## 为什么能修复（价值）
日志行语义单一化：指令维度日志（id/value）可与对端/协议日志按内容对齐，状态维度日志可统计连接健康度，日志量略增但信息密度提升。无逻辑影响，发送条件 `mL2AIsReady && l2aConStatus == L2A_STATUS_CONNECTED` 保持不变。

## 复盘与经验
- 高频控制指令的日志按"链路状态"与"业务内容"拆行，分别支持不同排查维度，比一条大而全日志更可检索。
- 日志格式调整类提交也应关联单号或注明动机（此处无单号），否则难以追溯是为哪次问题加的观测。
