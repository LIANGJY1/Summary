# SIR-6209 · 播放网易云时点击dock多媒体按键却进入蓝牙音乐

- **提交**：`fe316d8b` | 2026-08-23 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车机正在播放网易云音乐，点击 dock 栏多媒体按键，打开的却是蓝牙音乐应用而不是网易云音乐。

## 根因分析
`NavBarFragment` 中 dock 音乐入口、媒体导航选中态判断、卡片重置路径统一引用 `SysUIConfig.MUSIC_PACKAGE_NAME`，而该常量定义就是 `"com.yadea.btmusic"`（蓝牙音乐）。也就是说"默认音源"被硬编码为蓝牙音乐，无论当前实际音源是什么，点击多媒体按键永远 `goAppByPkg` 到蓝牙音乐。缺陷库根因"默认音源设置的是蓝牙音乐"属实。修复在 `SysUIConfig` 新增 `DOCK_MUSIC_PACKAGE_NAME = "com.arcvideo.car.ncm.music"`（网易云音乐），并把 `NavBarFragment` 中 4 处 dock 相关引用从 `MUSIC_PACKAGE_NAME` 切到新常量。顺带在 `pause_button` 点击处理中增加 `ViewUtilsKt.isInvalidClick(view)` 防抖。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（4 处引用切换 + 1 处防抖）、application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java（新增常量）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/util/SysUIConfig.java
     public final static String MUSIC_PACKAGE_NAME = "com.yadea.btmusic";
+    public final static String DOCK_MUSIC_PACKAGE_NAME = "com.arcvideo.car.ncm.music";
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
@@ 点击音乐入口
-                navigateIfNot(1, ctx -> ActivityStarter.INSTANCE.goAppByPkg(ctx, SysUIConfig.MUSIC_PACKAGE_NAME));
+                navigateIfNot(1, ctx -> ActivityStarter.INSTANCE.goAppByPkg(ctx, SysUIConfig.DOCK_MUSIC_PACKAGE_NAME));
@@ 暂停键防抖
             case R.id.pause_button:
-                sendMediaAction(mPlayPauseActionIndex);
+                if (!ViewUtilsKt.isInvalidClick(view)) {
+                    sendMediaAction(mPlayPauseActionIndex);
+                }
```
（另有 `musicPkg` 字段初始化、卡片重置、`pkg.equals(...)` 导航选中判断共 3 处同步切换为 `DOCK_MUSIC_PACKAGE_NAME`。）

## 为什么能修复
dock 音乐入口的目标包名从蓝牙音乐改为网易云音乐，点击行为与用户预期（当前音源为网易云）一致；导航栏选中态判断同步切换，避免入口与高亮判断使用两个不同包名造成选中态错乱。隐患：这仍是"写死一个默认音源"，并没有实现"跟随当前焦点音源动态跳转"——若默认音源再次变更（如换成其他音乐应用），需要再改常量；`MUSIC_PACKAGE_NAME` 仍被蓝牙音乐自身逻辑使用，两常量并存需注意语义区分。

## 复盘与经验
- "默认值"也是需求：默认音源这类用户可感知的缺省行为应做成配置项并随需求评审确认，而不是开发期随手写死的常量。
- 同一语义（dock 音乐入口）在多处引用同一常量时，改需求要一次改全——本提交 4 处引用逐一替换，容易遗漏，值得收敛为单一出口。
- 按键防抖（`isInvalidClick`）与功能修复出现在同一提交，建议拆分提交以便复盘归因。
