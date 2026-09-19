# 无单号 · 多账户切换逻辑修改（游客/新老账号配置同步）

- **提交**：`f9f95112` | 2026-07-23 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
重写多账户切换语义：用户切游客时把原用户配置同步给游客（维持体验）；游客/老用户切新账号时把当前配置带给新账号；新老账号互切时按已存配置下发座椅/HUD；并增加路口放大图、座椅名字、位置不选中三个界面刷新事件。

## 实现结构
4 个文件（+263/-96）：
- `utils/UserConfigManager.kt`：`cacheUserId` 改为 `oldUserId`（初始即读 GSetting），`userIdContentObserver` 增加"userId 未变化直接 return"去抖；`handleUserSwitch(userId)` 重写为三分支——退出（同步 old→游客后 return）、新用户（`addUser(userId, oldUserId)` 带源同步）、老用户（sendSeatConfig+sendHudConfig）；游客 HUD 个性化默认开启；`sendSeatConfig` 增加低配（`seatPersist==1`）拦截、未开通同步时若历史有选中则发 `refreshNoSelectPosition`；`IConfigSender` 接口新增 `notifyRoadMapEnlargeChanged/refreshSeatPositionName/refreshNoSelectPosition`；删除 `userLogoutEvent`；
- `common/manager/SeatUserManager.kt`：`addUser` 增加 `sourceUserId` 参数并在新增时调 `syncConfigFromUser`（同步 selectPosition、对应槽位 savedStatus、hudConfig 深拷贝）；新增 `syncConfigBetweenUsers`（退出登录 old→游客）、`getUserSelectedPosition`（同步来的绝对槽位按 `(x-1)%3+1` 折算相对位置）、`userExists`、`resetSeatPositionNames`；
- `init/SettingVehicleService.kt`：`userConfigManager` 改 public 并删 `getUserConfigManager()`；新增三个刷新 MutableLiveData，`IConfigSender` 实现里 postValue；`debugSimulateResponse` 恢复（上一次临时注释）；
- `ui/fragment/VehicleControlFragment.kt`：合并 `subscribeSignalChangeEvent/observeUserLogoutEvent` 为直接 observe 三个刷新事件；`selectSeatPosition` 改用 `getUserSelectedPosition`；`updateSeatPositionButtonsUI` 支持传入 -1 时三个按钮全部置为未选中底图。

数据流：账号中心改 GSetting USER_ID → ContentObserver → handleUserSwitch（同步/下发/刷新）→ SeatUserManager JSON 配置迁移 + configSender 回调 → SettingVehicleService LiveData → Fragment 刷新 UI。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt
+    private fun handleUserSwitch(userId: Long) {
+        /**
+         * 用户退出：用户登录->游客模式，维持上一个用户状态，用户的配置同步给游客
+         */
+        if (userId == USER_CUSTOMER && oldUserId != USER_CUSTOMER) {
+            SeatUserManager.syncConfigBetweenUsers(oldUserId, USER_CUSTOMER)
+            return
+        }
+        /**
+         * 新用户登录：游客/老用户-->新用户，把游客或者老用户的配置同步给新用户
+         */
+        if (!SeatUserManager.userExists(userId)) {
+            SeatUserManager.addUser(userId, oldUserId)
+            return
+        }
+        LogUtils.d(TAG, "handleUserSwitch: old user login")
+        sendSeatConfig()
+        sendHudConfig()
+    }
```
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
+    fun getUserSelectedPosition(position: Int): Int {
+        val userId = SettingsUtils.getGSettingLong(CACHE.USER_ID)
+        val start = getUserStartPosition(userId) 
+        if (start == -1) return -1
+        //先获取是否有其他用户同步给该用户的selectPosition
+        val selectPosition = getSelectedPosition()
+        if (selectPosition != -1) {
+            // selectPosition是绝对槽位号，通过取模算它在分组中是第几个位置（1/2/3）
+            val relativePos = ((selectPosition - 1) % 3) + 1
+            if (position == relativePos) {
+                return selectPosition
+            }
+        }
+        return start.plus(position - 1)
+    }
```
实现讲解：核心是把"切换"建模为配置迁移问题：每个用户在 JSON 缓存里有独立槽位区间，切换时把 selectPosition/savedStatus/HUD 配置在用户对象间拷贝，UI 用三个事件（名字刷新、放大图刷新、取消选中）做精确刷新，替代原来的 userLogoutEvent 粗粒度广播。`getUserSelectedPosition` 用取模把"同步来的绝对槽位"翻译成本用户分组的相对按钮，实现"换账号后高亮位置跟随"。

## 复盘与要点
- `oldUserId` 与 `userId` 双状态 + "未变化 return"去抖，是 ContentObserver 高频误触发下的标准防护；旧实现游客态把 0 归一化为 USER_CUSTOMER，新实现直接以 0 判断，分支更少。
- 可复用手法：配置迁移收敛在 SeatUserManager 的 `syncConfigFromUser` 单函数，退出/登录两条路径复用，避免双份拷贝逻辑漂移。
- 遗留风险：同步时 savedStatus 只保留 selectPosition 对应槽位、其余清 false，语义是"只带走当前坐姿"，但 hudConfig 是全量深拷贝，两类配置的同步粒度不一致，产品上需确认是否有意；`getUserSelectedPosition` 中 `start` 变量在分支未命中时才使用，读到 -1 之外的语义依赖上游保证。
