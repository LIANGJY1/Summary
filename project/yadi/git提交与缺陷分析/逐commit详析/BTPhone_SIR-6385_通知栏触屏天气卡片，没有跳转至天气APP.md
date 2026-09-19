# SIR-6385 · 点击通知栏天气卡片不跳转天气应用

- **提交**：`8338fe2f` | 2026-08-26 | liujinfeng | BTPhone/SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 天气 墨迹天气

## 问题
下拉通知栏点击天气卡片，没有任何反应，不跳转到天气应用。

## 根因分析
`NotificationCenterFragment`（SystemUI 通知中心）的天气卡片容器 `llWeather` 从未注册点击监听——卡片上的"权限"入口 `tvPermission`、刷新按钮 `btnRefresh` 都有各自的 `setOnClickListener`，唯独最主体的卡片区域没有绑定任何跳转响应（缺陷库：天气卡片未加点击跳转事件）。属于功能遗漏而非逻辑错误：开发时实现了天气数据展示与工具按钮，漏掉了"点卡片进应用"这一默认交互。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt`

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt
@@ -37,6 +38,7 @@ class NotificationCenterFragment : BaseFragment() {
         private const val TAG = "NotificationCenterFragment"
+        private const val WEATHER_PACKAGE = "com.android.ext.autoweather"
         private const val SWIPE_BACK_DISTANCE = 80f
@@ -87,8 +89,13 @@ class NotificationCenterFragment : BaseFragment() {
         tvPermission.setOnClickListener {
+            hideNotificationCenter()
             WeatherServiceConnector.openPrivacyPage()
         }
+        llWeather.setOnClickListener {
+            hideNotificationCenter()
+            ActivityStarter.goAppByPkg(requireContext(), WEATHER_PACKAGE)
+        }
@@ -184,6 +191,12 @@ public ...
+    private fun hideNotificationCenter() {
+        val statusBarActor =
+            ActorController.getInstance()[ActorController.TYPE_STATUS_BAR] as StatusBarActor
+        statusBarActor.hideQuickSettingView()
+    }
```

## 为什么能修复
给 `llWeather` 绑定点击监听，通过 `ActivityStarter.goAppByPkg` 按包名 `com.android.ext.autoweather` 拉起天气应用；点击后先调 `hideNotificationCenter()`（经 `ActorController` 取 `StatusBarActor.hideQuickSettingView()`）收起通知面板，避免"应用已启动但通知栏还挡在上面"的体验问题，同时给原有的 `tvPermission` 权限入口也补上了同样的收起动作。风险很小；隐患是包名硬编码在 companion 常量里，天气应用包名变更需同步此常量，更宜放入 `SysUIConfig` 之类的配置集中处。

## 复盘与经验
- "可点击的展示型控件"在开发清单里容易被漏（数据展示做了、交互没做），走查时按"每个可见卡片/按钮逐个验证点击行为"能兜住这类遗漏。
- 车机通知面板/OSD 覆盖在应用上层，任何"点卡片跳应用"的逻辑都要先收起面板再启动，顺序错了会出现视觉异常；把"收起面板"沉淀为 `hideNotificationCenter()` 这类小工具方法统一调用。
- 跳转第三方/系统应用优先走集中配置的包名常量，散落硬编码会在应用改包名时变成多个隐雷。
