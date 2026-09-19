# 无单号 · 公共库版本与项目保持一致

- **提交**：`39e6880c` | 2026-07-13 | daizhecheng | Vlog（实改 component/CarSettingLib） | feature
- **关联单**：无

## 需求/目标
**提交类型：构建配置对齐**。将 `CarSettingLib` 公共库写死的 SDK 版本改为引用项目级 Gradle 全局变量，保证各模块 compileSdk/minSdk/targetSdk 与主工程一致。

## 实现结构
只改 `component/CarSettingLib/build.gradle`（3 行）：
- `compileSdk 35` → `compileSdk project.compileSdkVersion`
- `minSdk 28` → `minSdkVersion project.minSdkVersion`
- `targetSdk 35` → `targetSdkVersion project.targetSdkVersion`

## 关键代码
```diff
--- a/component/CarSettingLib/build.gradle
-    compileSdk 35
+    compileSdk project.compileSdkVersion
     defaultConfig {
-        minSdk 28
-        targetSdk 35
+        minSdkVersion project.minSdkVersion
+        targetSdkVersion project.targetSdkVersion
```
实现讲解：多模块车机工程里，模块各自硬编码 SDK 版本会在主工程升级时产生"部分模块仍编译在旧 SDK"的隐性漂移。收口到 `gradle.properties`/根 `build.gradle` 的 `project.*` 变量后，升级只改一处。

## 复盘与要点
- 可复用规则：所有 `component/*` 库的 SDK 版本一律引用工程级变量，review 时见到硬编码数字即可打回。
- 标题写 Vlog、实际改公共库 CarSettingLib——提交信息的模块标注与改动内容不符，会影响按模块统计/回溯，建议提交模板校验模块前缀。
