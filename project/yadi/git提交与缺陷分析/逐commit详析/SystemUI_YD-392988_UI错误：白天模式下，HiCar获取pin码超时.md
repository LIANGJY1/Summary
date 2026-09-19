# YD-392988 · 白天模式下 HiCar 获取 pin 码超时提示时 dock 为黑色（SystemUI 侧）

- **提交**：`46c73e01` | 2026-07-30 | dufan | SystemUI | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392988，defs 无条目）

## 问题
白天模式下，HiCar 获取 pin 码超时提示出现时（前台为 Launcher 的 LinkActivity 连接页），底部 dock 栏显示为黑色，与 UI 设计不符。

## 根因分析
`NavBarFragment.handleTaskMovedToFront()` 按"前台任务归属"驱动 dock 状态：`pkg.equals(SysUIConfig.LAUNCHER_PACKAGE_NAME)` 即走 `handleLauncherTask(className)`，而该方法只处理主屏 `LAUNCHER_HOME_CLASS_NAME`（position 2，主页 dock **透明化**）与应用列表两种页面。HiCar 连接页 `com.yadea.launcher.function.link.LinkActivity` 也属于 launcher 包：pin 码提示把它顶到前台时，`handleLauncherTask` 两个分支都不命中、**选择器保持原状**——若 dock 此前处于"主页透明"状态（背景 alpha 被动画到 0），在 LinkActivity 这类非壁纸全屏页上透明 dock 便露出深色底层，白天模式下呈现为一条黑色 dock。缺陷库"界面切换判断错误"即此：判断粒度只到包名，未到 Activity 级。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java；application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java

```diff
--- application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java
     public final static String LAUNCHER_APP_CLASS_NAME = "com.yadea.launcher.function.applist.AppListActivity";
+    public final static String LAUNCHER_LINK_CLASS_NAME = "com.yadea.launcher.function.link.LinkActivity";
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
-        if (pkg.equals(SysUIConfig.LAUNCHER_PACKAGE_NAME)) {
+        if (pkg.equals(SysUIConfig.LAUNCHER_PACKAGE_NAME)  && !className.equals(SysUIConfig.LAUNCHER_LINK_CLASS_NAME)) {
             handleLauncherTask(className);
         } else if (...) {
```

## 为什么能修复
把 LinkActivity 从"launcher 分支"排除后，pin 码页到前台时落入末尾 `else → changeNavSelector(-1)`：`currentPosition` 置 -1，`applyDockBackground(currentPosition == 2 → transparent=false)` 走恢复路径，dock 背景 alpha 动画回不透明（255），显示常规白天 dock 底色，黑色透明带消失。与 4 分钟后同单的 Launcher 侧提交 `6207d507`（LinkActivity 独立 task）配套，构成"SystemUI 按 Activity 判断 + Launcher 按 task 隔离"的完整修复。隐患：`changeNavSelector(-1)` 会把 dock 图标选择态全部清掉（`currentPosition=-1`），从 Link 页返回主页时依赖后续 `onTaskMovedToFront` 事件恢复选中态，事件丢失期间 dock 图标无高亮。

## 复盘与经验
- **按包名判断页面状态在包内有多种页面时必然漏**：dock 透明与否是"页面级"属性，判断条件必须细化到 Activity（className），新增全屏 Activity 时同步登记白名单/黑名单。
- **透明 dock 的状态残留会跨页面污染**："主页透明化"是全局单例状态，进入任何非壁纸页面都必须显式恢复不透明，不能假设"别的页面不管它"。
- **跨应用 UI 联动问题常需两端同时修**（本单 SystemUI + Launcher 各一笔）：复盘时把同单多笔提交放在一起看才能还原完整方案。
