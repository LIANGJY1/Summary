# 无单号 · 修复静态代码扫描（Launcher SonarQube 整改）

- **提交**：`0a41a634` | 2026-07-10 | WangFengQ8 | Launcher | feature（实为质量整改）
- **关联单**：无

## 需求/目标
**提交类型：静态扫描（SonarQube）批量整改**。按 SonarQube 规则对 Launcher 30 个文件整改（+330/-393），覆盖 adapter/control/applist/utils 等包。

## 实现结构
整改内容：
- `NOSONAR` 行级豁免：`queryIntentActivities` / `getPackageInfo` 等被误报的 PM API。
- 单例写法修正：`AppInfoUtils` 的 `private static volatile` 移除（改单检访问方式），`DeviceConnectManager.kt` 115 行重排（去 `!!`、调访问修饰符）。
- 死代码删除：`findOriginalItemPositionInNewList`、`getChangedAppList`、注释掉的 AVM 过滤逻辑等。
- 命名规范：`package_name` → `packageName`（getter/setter 同步 `setPackage_name` → `setPackageName`）。
- 混入的非整改内容：删除 `.idea/compiler.xml`、`.idea/deploymentTargetSelector.xml`，改动 `local.properties` 与 `.gradle/7.5/checksums` 二进制。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/utils/AppInfoUtils.java
-    private static volatile AppInfoUtils mAppInfoUtils = null;
+    private static  AppInfoUtils mAppInfoUtils = null;
@@
-            PackageInfo info = manager.getPackageInfo(packageName, FLAGS);
+            PackageInfo info = manager.getPackageInfo(packageName, FLAGS); //NOSONAR
```
实现讲解：与 `f38ce24d`（SystemUI 同名整改）同一套打法：零风险项直改、误报项 `//NOSONAR` 行级豁免、无引用代码整块删除。`volatile` 的移除伴随单例获取逻辑简化，属于规则 `synchronization`/`volatile` 误用整改。

## 复盘与要点
- 团队已形成可复制的扫描整改套路（两个模块两天内同一格式提交），建议把"NOSONAR 必须带理由注释"与"整改提交禁混 IDE 配置"写进规范——本提交把 `local.properties`、`.gradle` checksums 一并入库属于混入噪音，`local.properties` 通常不该被版本管理。
- 属性重命名（`package_name`→`packageName`）跨类联改一次到位，避免了新旧命名长期并存。
- 遗留风险：删除"用户自定义排序"相关方法若后续需求回归需重写；`volatile` 移除后单例线程安全性依赖新的实现方式，需在多进程车机环境下验证。
