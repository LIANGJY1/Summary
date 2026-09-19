# 无单号 · 退出登录勾选"清除数据"时删除按用户隔离的偏好配置文件

- **提交**：`8d7ca8c6` | 2026-07-22 | hedeyuan | AccountCenter | bugfix
- **缺陷库**：未关联单号

## 问题
退出登录时勾选"清除数据"，账号相关 token、USER_ID 被清了，但按 userId 隔离保存的用户偏好配置文件（HUD/座椅开关等）仍在磁盘上，换账号或重新登录后旧偏好"复活"。

## 根因分析
`CenterActivity.clearAccountData()` 原来只设置 `Constants.CACHE.CLEAR_DATA_FLAG = "1"` 并调用 `clearAll()`；而 `clearAll()` 里只清理 `SettingsUtils` 下的全局键（USER_ID、ACCESS_TOKEN、REFRESH_TOKEN）。但该应用的偏好设置走的是另一套存储：`ShareConfigUtils.getConfig(key, default, userId)` 按 userId 维护独立配置文件（如 `data/share/user_<uid>_vehicle_config.properties`）。两套存储清理不对称，导致"清除数据"勾选后按用户隔离的偏好文件成为漏网之鱼；同时 `USER_TELEPHONE` 也未清空。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt`
```diff
         LogUtils.d(TAG, "clearAccountData: Clear account data")
         // 退出登录时勾选了清除数据，清除数据标识，1:清除,0:不清除
         SettingsUtils.setGSetting(Constants.CACHE.CLEAR_DATA_FLAG, "1")
+        //删除data/share/目录下的当前用户的偏好设置配置文件，如：data/share/user_4323455642310027023_vehicle_config.properties
+        ShareConfigUtils.clearConfig(userId ?: 0L)
         clearAll()
     }
@@ clearAll()
         // 清除登录状态
         SettingsUtils.setGSetting(Constants.CACHE.USER_ID, 0L)
+        SettingsUtils.setGSetting(Constants.CACHE.USER_TELEPHONE, "")
         SettingsUtils.setGSetting( Constants.CACHE.ACCESS_TOKEN, "")
```

## 为什么能修复
`ShareConfigUtils.clearConfig(userId)` 直接删除当前用户的偏好文件本体，把此前只清全局键、不删用户文件的缺口补上；`USER_TELEPHONE` 一并清空避免下一个登录用户读到前任手机号。副作用：`clearConfig` 传 `userId ?: 0L`，若此时 userId 已被 `clearAll()` 置 0（本提交中 clearAccountData 先于 clearAll 调用，顺序正确）无碍；但若未来有人在 clearAll 之后才调 clearConfig，会误删 key 为 0 的配置或删不到目标文件，调用顺序是隐性约束。

## 复盘与经验
- 同一应用内并存多套持久化（全局 SettingsUtils + 按用户 ShareConfigUtils）时，"清除数据"类功能必须逐套盘点，只清一套等于没清干净。
- 按 userId 隔离的存储，其清理接口应以 userId 为参数显式删除文件，而不是靠遍历键值——文件级删除更原子也更不易漏。
- 清理逻辑里的执行顺序（先取 userId 再清 USER_ID）应写注释固化，本提交的行内注释是好的示范。
