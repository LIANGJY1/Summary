# 无单号 · 修复静态代码扫描（SystemUI SonarQube 整改）

- **提交**：`f38ce24d` | 2026-07-09 | duanlonglong | SystemUI | feature（实为质量整改）
- **关联单**：无

## 需求/目标
**提交类型：静态扫描（SonarQube）批量整改**。按 "[how]根据SonarQube扫描规则修改" 的口径，对 SystemUI 79 个文件做清零整改（+1318/-3677），覆盖状态栏 actor/icon/fragment、通知、时钟等核心类。

## 实现结构
四类整改手法（全 diff 统计）：
- `NOSONAR` 抑制注解 75 处：对确实需要保留的写法（如 `CarVendorExtensionManager` 车厂扩展 API）加行级豁免；
- 死代码删除：整文件删除 `statusbar/broadcast/UserCenterReceiver.java`（74 行）、`registerLineToolReceiver` 等无引用方法、成片注释代码（屏保设置、旧 bug 52484/52485 的 `updateLanguageAndTheme` 修复残留）；
- 代码坏味修复：`mHandle!!` 双感叹号改为直接访问、lambda 未用参数 `tag: String?` 改 `_: String?`、补充 `== null` 判空（9 处）与 `isEmpty`；
- 顺带植入 `Yadea_Trace` 启动埋点（`SystemUI_Application_start`），与同日 Setting/AccountCenter 的埋点工程对齐。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/statusbar/actor/StatusBarActor.kt
@@ -81,12 +81,10 @@
-        mHandle!!.removeCallbacks(mInitNotification)
-        mHandle!!.postDelayed(mInitNotification, 3000)
+        mHandle.removeCallbacks(mInitNotification)
+        mHandle.postDelayed(mInitNotification, 3000)
@@ -98,7 +96,7 @@
         makeStatusBarPanel()
         FragmentHostManager.get(getDisplayView())
-            .addTagListener(StatusBarFragment.TAG) { tag: String?, fragment: Fragment ->
+            .addTagListener(StatusBarFragment.TAG) { _: String?, fragment: Fragment ->
```
实现讲解：整改遵循"能改则改、改不动则标注"的原则——非空断言、无用参数这类零风险项直接改写；平台 API 误报类用 `//NOSONAR` 精确到行豁免，而不是全局关闭规则；历史注释代码整块删除并由 git 历史兜底。

## 复盘与要点
- 大型扫描整改提交的可复用策略：按规则类型分批改（本提交把"删除类"与"标注类"混在一起，diff 达 5000 行，建议拆分以便 review）。
- `//NOSONAR` 是双刃剑：快速清零报告，但需要在团队约定中注明"豁免必须带注释理由"，否则沦为掩盖告警的工具。
- 顺带删除的 `UserCenterReceiver`、TBox 单项查询广播等属于"上一代车型遗留"，整改前先用引用搜索确认为死代码，避免把静默在用的广播当垃圾清除。
