# SIR-7641 · 座椅位置重命名保存闪退回驻车界面
- **提交**：`daf96773` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设（根因：用户信息无法存入 share）

## 问题
座椅位置重命名后点击"保存"，应用闪退并退回驻车界面。

## 根因分析
座椅用户信息由 `SeatUserManager`（object 单例，位于 CommonTools 组件）持久化到 `FILE_PATH = "/data/share/config/user_config.json"`。`/data/share` 是跨应用共享分区，运行期该目录对应用失去读取/写入权限（提交 [why]："share 目录没有读取权限了"），保存流程读写 `user_config.json` 时抛出 IO/权限异常且未妥善兜底，导致保存点击链路崩溃，界面闪退回驻车页。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
```diff
--- component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
@@ -19,7 +19,13 @@ object SeatUserManager {
     private const val TAG = "SeatUserManager"
-    private const val FILE_PATH = "/data/share/config/user_config.json"
+
+    /**
+     * 存储路径：Setting 应用私有 data 目录。
+     * Setting 与 Launcher 共用 android.uid.system（同一 uid），Launcher 同样可读写该目录，
+     * 保证座椅名称等配置跨应用共享不受影响（替代原来空间易满的 /data/share 分区）。
+     */
+    private const val FILE_PATH = "/data/data/com.yadea.setting/files/user_config.json"
```

## 为什么能修复
存储路径迁到 Setting 应用私有 data 目录 `/data/data/com.yadea.setting/files/`，读写权限由应用自身保证，不再依赖 `/data/share` 分区的挂载与权限策略，保存链路不再因权限异常崩溃。跨应用共享靠 Setting 与 Launcher 共用 `android.uid.system`（同 uid 可互访私有目录）维持。隐患：老用户在 `/data/share` 下的历史 `user_config.json` 不会自动迁移，升级后首次读取可能丢失已有命名（需确认有迁移/默认兜底）；同时该路径写死了 `com.yadea.setting` 包名，包名变更需同步。

## 复盘与经验
- 依赖 `/data/share` 这类共享分区做持久化，权限与容量策略都不在自己手里；应用私有目录 + sharedUserId 是车机多应用共享数据的常见替代。
- 持久化 IO 应有 try-catch 兜底：一个 JSON 读写异常直接把界面打崩，说明保存链路缺少防御，异常兜底与路径修复应同步做。
- 修改存储路径属于数据兼容性变更，必须评估旧数据迁移，否则"闪退修好了"但用户配置静默丢失。
