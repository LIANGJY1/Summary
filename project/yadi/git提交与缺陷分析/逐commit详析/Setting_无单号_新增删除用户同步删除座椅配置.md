# 无单号 · 删除用户时同步删除座椅配置

- **提交**：`b2d3a6e2` | 2026-07-29 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
与账号中心 `794e0267` 的失效用户清理联动：Setting 模块通过 ContentObserver 监听 `INVALID_USER_ID` 全局设置变化，一旦账号中心标记了失效用户，自动删除该用户的座椅配置文件，避免残留脏配置。

## 实现结构
单文件改动 `application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt`（+18/-5）：
- 新增 `userLogoutContentObserver`：onChange 时读取 `CACHE.INVALID_USER_ID`，非 0 则 `removeUserConfig(outUserId)`
- `initObserver` 中向 ContentResolver 注册该 Observer（监听 `Settings.Global INVALID_USER_ID` uri）
- `cleanup()` 合并三个 Observer 的反注册为单个 try/catch

数据流：AccountCenter 写 `SettingsUtils.setGSetting(INVALID_USER_ID, id)` → Settings.Global 变更 → ContentObserver 回调 → 删除该用户座椅配置。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/UserConfigManager.kt
@@ -70,6 +70,18 @@
+    // 监听用户退出
+    private val userLogoutContentObserver = object : ContentObserver(mHandler) {
+        override fun onChange(selfChange: Boolean) {
+            super.onChange(selfChange)
+            val outUserId = SettingsUtils.getGSettingLong(CACHE.INVALID_USER_ID)
+            LogUtils.d(TAG, "userLogoutContentObserver $outUserId")
+            if (outUserId != 0L) {
+                removeUserConfig(outUserId)
+            }
+        }
+    }
```
实现讲解：复用该类既有的"Settings.Global + ContentObserver"跨进程通知模式（同文件已有 `userIdContentObserver`、`userIdClearContentObserver`），新增一路监听即完成联动，无需引入新的 IPC 通道。跨模块通信契约就是上一提交里定义的 `Constants.CACHE.INVALID_USER_ID` 键。

## 复盘与要点
- 模块间解耦手法：AccountCenter 不直接调 Setting，而是通过 Settings.Global 键值 + ContentObserver 广播式通知，两模块只共享常量定义，耦合极低。
- 遗留风险：`onChange` 未清零 `INVALID_USER_ID`，同一次失效事件可能在 Settings 再次变更时重复触发删除（removeUserConfig 需幂等）；0 值作为"无事件"哨兵依赖写入方约定。
- cleanup() 把三个 unregister 合并进一个 try/catch，若第一个抛异常后两个不会被反注册，健壮性反而略降（原代码是每个单独 catch）。
