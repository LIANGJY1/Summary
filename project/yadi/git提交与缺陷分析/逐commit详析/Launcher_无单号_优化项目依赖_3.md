# 无单号 · 优化项目依赖（Launcher 砍掉 30+ 冗余依赖并接入公共组件）

- **提交**：`d9baedc4` | 2026-07-03 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
对 Launcher 模块的构建依赖做一次大规模瘦身：删除数十个未使用/重复的 maven 依赖，改为依赖自研 component 工程，并把遗留 neusoft 工具类调用切到公共库；顺带精简互联状态栏的颜色设置代码。

## 实现结构
- 修改 `application/Launcher/build.gradle`（核心，-152 行级）：
  - 依赖侧删除 rxbinding、rxlifecycle、material-dialogs、bindingcollectionadapter、navigation、live-event-bus、fastjson、constraintlayout/material 直接引入等约 30 项；
  - 新增 `compileOnly project(':component:AdaptApi')`、`implementation project(':component:Carlib'/'CommonTools'/'Hardwarelibs')`，以及 ts-carlink/ts-hicar psdk、framework_bluetooth.jar 等定向引入；
  - `signingConfigs` 块位置上移规整，`buildFeatures` 合并补 `aidl/viewBinding/dataBinding`。
- 修改 `function/applist/CarConnectFragment.kt`：`com.neusoft.applib.utils.LogUtils` → `com.yadea.common.utils.LogUtils`；互联状态文字颜色从大段 `ContextCompat.getColor` 改为 `ResourceUtils.getColor` / `"#6083EC".toColorInt()`（-75 行）。
- 修改 `Myapplication.kt`、`ViewPagerAdapter.java`、`NotificationUtils.java`、`SpUtils.kt`：同类 import/工具切换的小改动。

数据流不变，属于构建与工具层治理；互联状态"已连接高亮/未连接灰字"的视觉逻辑保持，只是写法收敛。

## 关键代码
```diff
--- a/application/Launcher/build.gradle
@@ -129,109 +115,33 @@ dependencies {
-    api "io.reactivex.rxjava2:rxjava:2.2.3"
-    api "io.reactivex.rxjava2:rxandroid:2.1.0"
-    api("com.trello.rxlifecycle2:rxlifecycle:2.2.2") { exclude group: 'com.android.support' }
-    ...（rxbinding / material-dialogs / bindingcollectionadapter / navigation 等约 30 项删除）
+    compileOnly project(':component:AdaptApi')
+    implementation project(':component:Carlib')
+    implementation project(':component:CommonTools')
+    implementation project(':component:Hardwarelibs')
+    compileOnly files('../../component/frameworkLibs/libs/framework_bluetooth.jar')
```

```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
@@ -214,6 +214,7 @@
         mBinding.tvCarlinkStatus.setText(R.string.status_disconnected)
+        mBinding.tvCarplayStatus.setTextColor(ResourceUtils.getColor(R.color.text_default_press))
         when (deviceType) {
             DeviceConnectManager.CARPLAY -> {
                 ...
-                    mBinding.tvCarplayStatus.setTextColor(
-                        ContextCompat.getColor(requireContext(), R.color.car_connect_color))
+                    mBinding.tvCarplayStatus.setTextColor("#6083EC".toColorInt())
```

实现讲解：这是同日"优化项目依赖"系列里最大的一刀。旧依赖多为 `api` 传递（会把依赖泄漏给使用方），且大量来自 Launcher 前身工程的历史包袱；切到 component 工程依赖后版本由仓库统一收敛。删除依赖的前提是代码先完成 import 迁移（neusoft LogUtils → common LogUtils），构建与代码改动在同一个提交里保证可编译。

## 复盘与要点
- 依赖治理的标准节奏：先扫 import 找实际使用 → 无引用的直接删 → 有引用的切到公共实现 → 最后断依赖。`api` 转 `implementation`/`compileOnly` 能显著降低模块间编译耦合。
- `"#6083EC".toColorInt()` 硬编码色值不如放 color 资源，深浅模式下无法切换，是个小的遗留债。
- `compileOnly framework_bluetooth.jar` 的手法值得注意：系统框架隐藏 API 只参与编译、运行时由车机系统提供，避免打进 APK。
