# SIR-7099 · D档长按P档下电 Toast 取消（需求变更）
- **提交**：`3ed8eb5b` | 2026-09-03 | ljl | SystemUI | bugfix（需求变更）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息
- **注**：缺陷库根因/方案均填"需求变更"，与 diff 一致——非代码逻辑错误，是产品行为变更；本提交同时捎带了后摄故障 Toast（注释待启用）等无关改动。

## 问题
D 档（非 P 档）长按进入 Standby2 下电流程时，车机弹出"为了行车安全，请在P档下锁车关机"Toast。需求变更后要求取消该 Toast。

## 根因分析
非缺陷而是需求演进：`DigitalKeyVehicleService.checkStandby2GearState()` 在 `currentPowerState == 0x03`（Standby2）且 `currentGear != 0x00`（非 P 档）时，通过 `hasToastShownForCurrentState` 标志保证一次状态周期只提示一次，原实现调用 `showToast("为了行车安全，请在P档下锁车关机")`。产品侧决定取消该提示，代码需把 Toast 降级为日志。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt`（核心）、`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`、`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt`、`application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java`、`application/SystemUI/src/main/res/values/strings.xml`、`values-en/strings_en.xml`、`component/Carlib/.../CarPropertyIds.kt`、`CarPropertyMapping.kt`

```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt
         if (currentPowerState == 0x03) { // Standby1 改为 Standby2
             if (currentGear != 0x00) { // 非P档
                 if (!hasToastShownForCurrentState) {
-                    showToast("为了行车安全，请在P档下锁车关机")
+//                    showToast("为了行车安全，请在P档下锁车关机")
+                    LogUtils.i(TAG, "checkStandby2GearState Toast cancel")
                     hasToastShownForCurrentState = true
                 }
```

其余改动与本单无关、属同批捎带：后摄故障 Toast 全套逻辑（`checkRearCameraFault`、`mCameraHpStatusCallback`、`resetRearCameraFaultToast`、`rear_camera_fault_toast` 文案）以注释形式预埋，注明"待新版 NsrCommSdk.aar 到位后取消注释恢复"；`PageStateMachine` 把魔法数字 333/1200 提取为常量 `D_ZONE_CLICK_INTO_S1_DELAY`/`D_GEAR_INTO_S1_DELAY`；Carlib 新增 `SAM_NFC_PAIR_REQ = 6123`（NFC 配卡状态）属性映射。

## 为什么能修复
Toast 调用被注释、降级为 `LogUtils.i`，去重标志 `hasToastShownForCurrentState` 的推进逻辑保留，下电流程其余行为不变，用户在 D 档长按 P 档下电时不再看到提示。以注释而非删除的方式保留原文案，便于需求回退；隐患是注释块越积越多，需依赖"待 aar 到位恢复"的注释纪律跟踪。

## 复盘与经验
- "Toast 还在/需求变更取消提示"类问题，修复要点是只动提示出口、不动状态机推进逻辑（本例保留 `hasToastShownForCurrentState`），避免引入新的时序回归。
- 需求变更类"缺陷"在缺陷库中根因/方案如实标注"需求变更"即可，不必强行编造技术根因；但 commit 里混入大量无关改动（预埋注释功能、常量提取、NFC 属性）会污染该单的追溯性，建议拆分提交。
- 依赖未到位的 SDK（NsrCommSdk.aar）时，用整段注释 + 明确恢复条件注释是可行的暂存手段，但要登记跟踪，否则极易被遗忘。
