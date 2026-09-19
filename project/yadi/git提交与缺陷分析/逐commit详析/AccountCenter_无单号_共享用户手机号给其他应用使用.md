# 无单号 · 共享用户手机号给其他应用使用

- **提交**：`1461a744` | 2026-07-10 | hedeyuan | AccountCenter | feature（cherry-pick 自 bce5c7c9）
- **关联单**：无（提交说明原文："李思言需要用到手机号"）

## 需求/目标
账号中心登录成功拉取用户信息后，把用户手机号写入全局 Settings（settings_global 表），供车机上其他应用跨进程读取，实现"账号数据一次登录、多应用共享"。

## 实现结构
改动 2 个文件（+5/-1），典型小步快跑：
- `component/CommonTools/.../common/Constants.kt`：`CACHE` 常量组新增 `USER_TELEPHONE = "userTelephone"`（与既有 `USER_ID/ACCESS_TOKEN` 并列）。
- `application/AccountCenter/.../ui/center/CenterActivity.kt`：成员变量 `mobile`，用户信息回调里赋值 `userInfo.mobile`，并 `SettingsUtils.setGSetting(Constants.CACHE.USER_TELEPHONE, mobile ?: "")` 落库；顺带把既有注释中的存放路径说明从 `settings_system.xml` 修正为 `settings_global.xml`。

数据流：登录后请求用户信息 → `CenterActivity` 回调 → `Settings.Global.put` 写入 → 其他应用经 `SettingsUtils.getGSetting` 跨进程读取。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
@@ -142,9 +144,10 @@
         ///存放路径为：data/system/users/0/settings_system.xml，恢复出厂设置后会清除
+        ///存放路径为：data/system/users/0/settings_global.xml，恢复出厂设置后会清除
         SettingsUtils.setGSetting(Constants.CACHE.USER_ID, userId ?: 0L)
         SettingsUtils.setGSetting(Constants.CACHE.CLEAR_DATA_FLAG, "0")
+        SettingsUtils.setGSetting(Constants.CACHE.USER_TELEPHONE, mobile ?: "")
```
实现讲解：复用既有的 `Settings.Global` 共享通道（车机各应用普遍以 `SettingsUtils.getGSetting` 读全局表），手机号只是往这张"跨应用 KV 表"里再加一个键。选择 global 表的动机注释已写明：恢复出厂会随之清除，与账号生命周期天然一致。

## 复盘与要点
- 用 `Settings.Global` 当跨应用 KV 是车机常见手法：零权限、跨进程、系统级持久化；但该表本质是系统设置区，存 PII（手机号）在标准 Android 上任何应用可读，车机封闭环境可接受、开放平台不可照搬。
- 可复用模式：新增共享字段 = 常量组加键 + 写入方一处落库，读取方零改动即可用，扩展成本极低。
- 遗留风险：写入时机仅在用户信息回调，若回调失败或用户重新登录前手机号变更，其他应用读到的是旧值；未登录时读到空串，消费方需自行判空。
