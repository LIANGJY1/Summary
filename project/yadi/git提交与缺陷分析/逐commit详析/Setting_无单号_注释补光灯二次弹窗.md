# 无单号 · 注释补光灯二次弹窗（需求回退冻结）

- **提交**：`9e6a1006` | 2026-08-04 | sgh | Setting | feature（**实为需求回退**）
- **关联单**：无（提交说明：暂时注释待后期需求确认，测试范围"不弹窗"）

## 需求/目标
将 7 月 28 日 `2b326799` 上线的"自动远光灯 + 智能补光灯开启二次弹窗确认"整体注释停用，灯光开关恢复为"直接发 CAN 信号"的旧行为，等待需求方重新确认后再启用。

## 实现结构
单文件 `application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt`：
- `setAutoHighBeamListener()`：恢复点击即发 `IHC_SWITCH` ON/OFF，旧"仅关直通"分支保留为注释
- `setIntelligentLowBeamListener()`：恢复直接发 `sendIntelligentLowBeamCommand(ON/OFF)` + pending 记账
- overlay 注册块（两个 `setOnOverlayClickListener` + `showWarningDialog`）整体注释
- 字符串资源 `smart_light_title/content` 未删除，弹窗代码随时可恢复

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
@@ -310,15 +314,17 @@
-    private fun setIntelligentLowBeamListener() {
+    /**
+     * 补光灯开关监听
+     */
+    private fun setIntelligentLowBeamListener() {
         mBinding.ssvIntelligentLowBeam.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
-            if (!isChecked) {
-                sendIntelligentLowBeamCommand(CanSignalConstants.SWITCH_OFF)
-                intelligentLowBeamPendingState = false
-                log("Send intelligent low beam OFF command")
-            }
+            sendIntelligentLowBeamCommand(if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF)
+            intelligentLowBeamPendingState = isChecked
+//            if (!isChecked) {
+//                sendIntelligentLowBeamCommand(CanSignalConstants.SWITCH_OFF)
+//                ...（旧分支注释保留）
```
实现讲解：采用"注释冻结"而非删除或特性开关：旧直通逻辑恢复为现役代码，二次确认逻辑（含 2b326799 的 overlay 注册）原样留在注释里，需求确认后解除注释即可回切。改动精确反转了 `2b326799` 的行为分支。

## 复盘与要点
- 需求反复时的低成本回退手法：注释冻结 + 文案资源保留，回切成本≈0；但污染 diff 可读性，若反复多次应改用远程配置/feature flag。
- 值得注意的细节：自动远光灯恢复直通后，其关闭链路与 `isAdasControlEnabled` 联动逻辑回到旧语义，与补光灯 pending 状态机的行为差异需回归覆盖。
- 流程教训：`2b326799` 与本提交仅隔一周，"新增→冻结"说明二次确认的 UX 决策未在开发前闭环，需求确认应前置。
