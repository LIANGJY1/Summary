# [SRS_UserCenter_020] · 座椅 HUD 配置逻辑修改（用户切换/开关开启/恢复出厂三场景重构）

- **提交**：`63f02700` | 2026-08-07 | sgh | Setting | feature
- **关联单**：SRS_UserCenter_020

## 需求/目标
重写"账号切换时座椅/HUD 配置同步"的策略：新用户登录与退出到游客**维持物理状态但 UI 不选中**；仅"任何账号切到开了同步开关的老账号"才下发该用户保存的配置；配置开关中途打开时补发；恢复出厂时清除所有用户配置。

## 实现结构
- `utils/UserConfigManager.kt`（+186/-128，主战场）：`handleUserSwitch()` 按场景注释重写（新用户→addUser+refreshNoSelectPosition；登出→refreshNoSelectPosition；老用户→handleSeatConfig/handleHudConfig）；原 `sendSeatConfig/sendHudConfig` 拆为"前置校验层 handleXxx"+"执行层 sendSeatConfig/sendHudConfigs"；HUD 配置 ID 列表提为常量 `HUD_CONFIG_IDS`；新增 `handleSeatConfigByOpen/handleHudConfigByOpen`（开关打开时补下发）
- `common/manager/SeatUserManager.kt`：注释掉 `syncConfigBetweenUsers/syncConfigFromUser`（新用户不再继承来源用户配置），补充"恢复出厂通过 clearAllData() 清除"注释
- `ui/fragment/SystemFragment.kt`：恢复出厂流程增加 `SeatUserManager.clearAllData()`
- `init/SettingVehicleService.kt`：HUD 亮度 postValue 去重（value 相同不发），清理日志

数据流：USER_ID Settings 变更 → Observer → handleUserSwitch 分场景 →（UI 不选中 | 校验低配/开关/槽位/限速模式 → 下发座椅槽位配置 + 逐项 HUD 配置 + 路口放大图）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt
@@ -106,36 +116,49 @@
-    private fun handleUserSwitch(userId: Long) {
+    /**
+     * 处理用户切换同步配置的逻辑
+     * 座椅UI不选中和维持上一个用户座椅物理状态（比如高度是50，切换用户还是维持50）...
+     * 场景1、老账号/游客切换新账号 → 维持上一个用户状态，UI不选中
+     * 场景2、新账号/老账号切换到游客 → 维持上一个用户状态，UI不选中
+     * 场景3、游客/新账号切换老账户（配置关）→ 维持上一个用户状态，座椅UI不选中
+     * 座椅UI切换选中态和调节登录用户的座椅物理状态...场景1、任何账号切换到老账号（配置开）
+     */
         if (!SeatUserManager.userExists(userId)) {
             LogUtils.d(TAG, "handleUserSwitch: new user login")
             SeatUserManager.addUser(userId, oldUserId)
+            //刷新UI不选中
+            configSender.refreshNoSelectPosition()
             return
         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt
@@ -243,12 +243,64 @@
+    /***
+     * 监听到座椅配置开关打开，如果是老用户有座椅配置，下发座椅配置
+     */
+    private fun handleSeatConfigByOpen() {
+        if (userId == USER_CUSTOMER) return
+        sendSeatConfig()
+    }
+
+    private fun sendSeatConfig() {
+        val userSelect = SeatUserManager.getSelectedPosition()
+        if (userSelect == -1) {
+            return
+        }
+        val driveState = settingVehicleService.driveStateManager?.currentDriveState
+        if (driveState != com.yadea.apf.vehiclebase.Constants.DriveState.DRIVELIMITED1.ordinal) {
+            return
+        }
+        configSender.sendSeatConfig(userSelect)
+    }
```
实现讲解：核心变化是把"是否下发"的判定与"怎么下发"的执行拆成两层，四个入口（用户切换、开关打开）复用同一执行层；场景语义全部写进 KDoc（含数值举例），可读性大幅提升。同时 `removeUserConfig` 的游客判断改用 `USER_CUSTOMER` 常量。

## 复盘与要点
- 删除"新用户继承旧用户配置"（syncConfigFromUser 整段注释）是行为级变更：登出→新登录不再把上个账号的座椅槽位/HUD 值带过去，测试矩阵要按 3 场景 + 配置开关四象限重建。
- 可复用手法：配置类同步统一"handle 前置校验（低配/开关/槽位/行车状态）→ send 执行"分层，执行层可被多个触发源复用。
- 遗留风险：`SeatUserManager` 中被注释的 40 余行同步代码长期留存会误导后来者，确认不恢复后应物理删除并靠 git 历史回溯。
