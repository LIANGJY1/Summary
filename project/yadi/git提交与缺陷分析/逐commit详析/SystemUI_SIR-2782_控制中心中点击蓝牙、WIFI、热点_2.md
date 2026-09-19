# SIR-2782 · 控制中心点击蓝牙/WIFI/热点改为直接弹窗（SystemUI 侧）
- **提交**：`9e8db0a7` | 2026-07-16 | dufan | SystemUI | bugfix（需求变更落地）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
需求变更：控制中心点击蓝牙、WIFI、热点，原行为是"跳转车控车设主界面"，变更后要求直接拉起设置侧对应弹窗。

## 根因分析
这不是传统缺陷而是需求变更落地（缺陷库 rc"需求变更"、sol"只弹窗显示"）。原实现 `ActivityStarter.goSystemSetting()` 用 `packageManager.getLaunchIntentForPackage(PACKAGE_NAME_SETTINGS)` 拉起设置应用**启动 Activity**（主页面），再靠 extra 传 type；要弹窗就必须让 Intent 直达设置侧的弹窗宿主 Activity。修改点：改用显式 `ComponentName` 指向 `com.yadea.setting.ui.activity.ConnectChildDialogActivity`，并新增常量 `SysUIConfig.SETTING_CLASS_MAIN`。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt（+11/-14）、application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java（+3）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/util/ActivityStarter.kt
     fun goSystemSetting(context: Context, type: String) {
         try {
-            val launchIntentForPackage =
-                context.packageManager.getLaunchIntentForPackage(SysUIConfig.PACKAGE_NAME_SETTINGS)
-            launchIntentForPackage?.addFlags(Intent.FLAG_DIRECT_BOOT_AUTO)
-            launchIntentForPackage?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
-            launchIntentForPackage?.putExtra("key", "system_setting")
-            launchIntentForPackage?.putExtra("type", type)
-            val options = ActivityOptionsCompat.makeCustomAnimation(context, 0, R.anim.scale_up)
-            context.startActivity(launchIntentForPackage, options.toBundle())
+            val launchIntentForPackage = Intent().setComponent(
+                ComponentName(SysUIConfig.PACKAGE_NAME_SETTINGS, SETTING_CLASS_MAIN))
+            launchIntentForPackage.addFlags(Intent.FLAG_DIRECT_BOOT_AUTO)
+            launchIntentForPackage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+            launchIntentForPackage.putExtra("key", "system_setting")
+            launchIntentForPackage.putExtra("type", type)
+            context.startActivity(launchIntentForPackage)
         } catch (ex: Exception) { ... }
--- application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java
+    public static final String SETTING_CLASS_MAIN =
+            "com.yadea.setting.ui.activity.ConnectChildDialogActivity";
```

## 为什么能修复
显式 ComponentName 让 `FLAG_ACTIVITY_NEW_TASK + launchMode` 复用既有弹窗 Activity（配合 Setting 侧 `onNewIntent` 处理，见姊妹提交 `7d78eecc`），点击即弹窗、不再路过设置主页。风险：硬编码类名字符串，设置侧重构该类会静默断链（好在 try-catch 只打日志不崩溃，表现为"点了没反应"——恰是要防的静默失败）；去掉了启动动画 options，跳转动画风格变化属预期。

## 复盘与经验
- **跨应用导航尽量显式 ComponentName + 单一配置点**：常量集中在 `SysUIConfig`，改目标页只动一处；但要对目标类做存在性校验或降级路径。
- **静默 catch 会把断链变成"无响应"**：`catch { log }` 模式下失败对用户不可见，联调时这类问题最耗时间，关键跳转可加 toast 兜底。
- **需求变更也要走提交剖析流程**：这类提交在缺陷库中以"需求变更"结单，复盘时应与真缺陷分开统计，避免污染缺陷率。
