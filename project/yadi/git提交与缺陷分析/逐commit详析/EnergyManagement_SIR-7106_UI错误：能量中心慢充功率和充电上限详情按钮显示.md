# SIR-7106 · 慢充功率/充电上限"详情"按钮图标与 UI 稿不一致
- **提交**：`52e4fc7d` | 2026-08-31 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心"慢充功率""充电上限"旁的详情（info）按钮图标与 UI 稿不一致（昼夜两个模式都不对）。

## 根因分析
详情按钮的昼夜资源经主题位图代理分发：`drawable/slow_charge_info_theme.xml`（白天）与 `drawable-night/slow_charge_info_theme.xml`（黑夜）都指向 `@drawable/slow_charge_info2`，而 UI 稿要求的是新图标 `slow_charge_info3`。旧图 `slow_charge_info2` 是历史版本切图，与最新设计稿存在形状/透明度差异；代码层引用（主题 xml）无需改动，属于"资源版本未随设计更新"的典型 UI 对齐缺陷。

## 关键代码修改
改动文件：drawable/slow_charge_info_theme.xml、drawable-night/slow_charge_info_theme.xml、新增两张 png（二进制资源）
```diff
--- a/application/EnergyManagement/src/main/res/drawable/slow_charge_info_theme.xml
 <bitmap xmlns:android="http://schemas.android.com/apk/res/android"
     android:gravity="fill"
-    android:src="@drawable/slow_charge_info2" />
+    android:src="@drawable/slow_charge_info3" />
--- a/application/EnergyManagement/src/main/res/drawable-night/slow_charge_info_theme.xml
（黑夜代理同样改为 slow_charge_info3）
```
同时新增 `drawable-mdpi/slow_charge_info3.png`（755B）与 `drawable-night/slow_charge_info3.png`（671B，夜间专用暗色调），二进制图片资源更新。

## 为什么能修复
主题 xml 只改一行指向新切图，昼夜两个 qualifier 各配一张新 png，白天/黑夜的详情按钮即刻与 UI 稿一致；位图代理（`<bitmap android:src>`）机制不变，所有引用 `slow_charge_info_theme` 的位置一并生效。无逻辑风险，唯一要求是 png 必须放进 `drawable-mdpi`（与车机屏幕密度匹配）且昼夜图尺寸一致，否则会被缩放错位。

## 复盘与经验
- UI 对齐类缺陷的修复入口常在资源引用层而非代码层：先确认"代码用的哪个资源名→该资源当前内容是什么→UI 稿要哪版"，再决定改引用还是换图。
- 昼夜成对的位图资源务必同步更新两个 qualifier 目录，只改白天图会导致夜间仍显旧图（本提交两张图同时新增，做法正确）。
- 切图版本命名（info2/info3）配合主题代理 xml，比直接覆盖同名 png 更可追溯，值得沿用。
