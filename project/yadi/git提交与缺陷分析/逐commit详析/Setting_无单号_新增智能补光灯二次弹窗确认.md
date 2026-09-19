# 无单号 · 新增智能补光灯二次弹窗确认

- **提交**：`2b326799` | 2026-07-28 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
灯光设置页的"智能补光灯"开关在开启时增加安全提示二次弹窗确认（与已有"自动远光灯"开启确认保持一致交互），关闭时仍直接下发 CAN 信号。

## 实现结构
- `LightFragment.kt`：核心改动文件
  - `setIntelligentLowBeamListener()`：开关直接点击只处理"关"分支（发 OFF 信号 + 置 pending=false），"开"分支交给 overlay 拦截
  - overlay 注册处新增 `ssvIntelligentLowBeam.setOnOverlayClickListener`，弹 `showWarningDialog` 确认框，确认后置位开关、发 `SWITCH_ON`、启动回弹动画
  - `updateIntelligentLowBeamUI()`：信号状态回流后取消回弹、按开/关状态 enable/disable overlay
- `values/strings.xml`、`values-en/strings.xml`：新增 `smart_light_title`（开启智能补光灯）与 `smart_light_content`（夜间/暗光低速行驶提示文案）中英文资源

数据流：用户点开 → overlay 拦截弹窗 → 确认 → 本地先置 UI 状态 + 发 CAN 信号 + pending 记账 → 信号回流 `updateIntelligentLowBeamUI` 校验 pending 后停止回弹并清空。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
@@ -314,8 +312,11 @@ private fun setIntelligentLowBeamListener() {
         mBinding.ssvIntelligentLowBeam.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
-            sendIntelligentLowBeamCommand(if (isChecked) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF)
-            intelligentLowBeamPendingState = isChecked
+            if (!isChecked) {
+                sendIntelligentLowBeamCommand(CanSignalConstants.SWITCH_OFF)
+                intelligentLowBeamPendingState = false
+                log("Send intelligent low beam OFF command")
+            }
         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
@@ -456,6 +456,21 @@
+            ssvIntelligentLowBeam.setOnOverlayClickListener {
+                showWarningDialog(
+                    title = getString(R.string.smart_light_title),
+                    content = getString(R.string.smart_light_content),
+                    confirmText = getString(R.string.sure),
+                    cancelText = getString(R.string.cancel),
+                    onConfirm = {
+                        intelligentLowBeamPendingState = true
+                        mBinding.ssvIntelligentLowBeam.isChecked = true
+                        sendIntelligentLowBeamCommand(CanSignalConstants.SWITCH_ON)
+                        startReboundForSwitch(mBinding.ssvIntelligentLowBeam.switchCompat)
+                    }
+                )
+            }
```
实现讲解：复用既有 `SwitchSmartView` 的 overlay 机制与 `showWarningDialog`，把"开"动作从直接监听器迁移到 overlay 回调，实现"关直通、开需确认"的非对称交互；`updateIntelligentLowBeamUI` 中把 pending 判空提前（`intelligentLowBeamPendingState != null &&`），避免误清回弹状态，并按状态动态开关 overlay。

## 复盘与要点
- 安全类灯光开关统一"开启二次确认、关闭直通"模式，可直接复制自动远光灯的实现骨架（overlay + WarningDialog + pending + rebound）。
- pending 状态与真实信号回流做对账（cancel rebound + 置 null）是车机 CAN 异步通信下防 UI 抖动的关键手法。
- 遗留风险：提交注释里删掉了原有说明性注释，且 8 天后 `9e6a1006` 又将此弹窗注释掉，说明该需求后续被回退。
