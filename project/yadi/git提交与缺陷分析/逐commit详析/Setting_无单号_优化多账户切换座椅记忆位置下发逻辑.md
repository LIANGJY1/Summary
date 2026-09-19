# 无单号 · 优化多账户切换座椅记忆位置下发逻辑
- **提交**：`f9f9c990` | 2026-07-20 | shengguanghui | Setting | bugfix（标注"优化/新需求"，cherry-pick 自 580be91a）
- **缺陷库**：未关联单号

## 问题
多账户切换时座椅记忆位置下发逻辑语义不清：切换用户后无条件执行 `sendSeatConfig()`，导致游客态或未保存过位置的新用户也会被下发/回刷座椅记忆位置。

## 根因分析
`UserConfigManager.handleUserSwitch()` 原实现只判断"非游客才继续"，然后一律 `sendSeatConfig()` 并读取 `SeatUserManager.getSelectedPosition()` 打日志，注释掉的重刷代码残留在函数体里，没有按用户迁移场景区分处理。本提交在注释中明确了三条规则：登录用户→游客，保持登录用户状态不处理（游客手动切）；游客→登录用户，若该用户保存过位置则同步下发；登录用户→新用户（没保存过位置），不做处理。另将 `VehicleControlFragment` 中 `saveSelectedPosition(position)` 从 `seatPositionPendingState = null` 之前移到之后，保证先清 pending 超时状态再落盘选中槽位，顺序对齐"回调确认后再保存"的语义。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt`、`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`、`component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
                         CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL,
                         0
                     )
-                        SeatUserManager.saveSelectedPosition(position)
                         seatPositionPendingState = null
+                        SeatUserManager.saveSelectedPosition(position)
```

```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt (handleUserSwitch)
+     * 登录用户->游客，保持登录用户的状态，不做任何处理，游客手动切
+     * 游客->登录用户，登录用户有保存位置，同步下发位置配置
+     * 登录用户->新用户，没有保存过位置，不做任何处理，新用户手动切换位置
         SeatUserManager.addUser(userId)
-        val sendTag = sendSeatConfig()
-        val selectPosition = SeatUserManager.getSelectedPosition()
-        LogUtils.d(TAG, "sendTag selectPosition = $selectPosition")
+        sendSeatConfig()
         sendHudConfig()
```

（`SettingVehicleService.kt` 仅删除未使用的 `BuildConfig` import；`SeatUserManager.kt` 仅注释补充。）

## 为什么能修复
本质是逻辑收敛与语义澄清：明确三种用户切换场景的期望行为，去掉日志与死代码，`sendSeatConfig()` 内部按"是否保存过位置"决定是否下发；`saveSelectedPosition` 移到 pending 清理之后，避免"保存了位置但 pending 状态未清"的中间态。该提交自我标注为优化（影响等级 C，测试范围"新需求"），更像配合 SIR-2860/多账户座椅记忆特性链路的防御性收敛，而非独立缺陷修复。

## 复盘与经验
- 多账户配置下发必须先枚举迁移矩阵（登录↔游客↔新用户），把"不做处理"也显式写成规则，而不是默认全都下发。
- "清 pending 状态 → 再持久化业务数据"的顺序，决定了异常中断时数据与 UI 是否一致。
- cherry-pick 到发布分支的"优化"提交也要带清晰 why，本提交 what/why/how 三段同文，事后无法追溯动机。
