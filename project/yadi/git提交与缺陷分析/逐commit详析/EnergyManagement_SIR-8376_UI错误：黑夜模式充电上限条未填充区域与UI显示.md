# SIR-8376 · 黑夜模式充电上限条未填充区域颜色与 UI 不符
- **提交**：`843ff5a0` | 2026-09-14 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
黑夜模式下，充电上限 SeekBar 的"未填充区域"（轨道背景）颜色与 UI 设计稿不一致——深浅模式切换后轨道颜色不随主题变化。

## 根因分析
轨道 drawable `seekbar_charge_limit_track.xml` / `seekbar_charge_limit_track_disabled.xml` 中，vector `<path android:fillColor="#261F222A">`（及 disabled 版 `#0F1F222A`）把颜色**硬编码在 drawable 内**。硬编码色值不参与资源限定符匹配，切到黑夜模式后 `values-night/colors.xml` 里的任何夜间色板都影响不到它，轨道永远显示白天版本的深色底（#26 透明度的 1F222A），在夜间背景上与设计稿的浅色半透明底（#0FFFFFFF）不符。另外布局里 `seekbar_range_mode2` 未挂任何 style，进度条外观参数没有统一入口。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/seekbar_charge_limit_track.xml；.../drawable/seekbar_charge_limit_track_disabled.xml；.../res/layout/activity_main.xml；.../res/values-night/colors.xml；.../res/values/colors.xml；.../res/values/style.xml
```diff
--- a/application/EnergyManagement/src/main/res/drawable/seekbar_charge_limit_track.xml
+++ b/application/EnergyManagement/src/main/res/drawable/seekbar_charge_limit_track.xml
@@ -8,10 +8,10 @@
             <path
-                android:fillColor="#261F222A"
+                android:fillColor="@color/charge_limit_track_background_color"
                 android:pathData="M12,0 H295 L299,4 V20 L295,24 H12 A12,12 0,0 1,12,0 Z" />
             <path
-                android:fillColor="#261F222A"
+                android:fillColor="@color/charge_limit_track_background_color"
                 android:pathData="M305,0 H388 A12,12 0,0 1,388,24 H305 L301,20 V4 Z" />
```
```diff
--- a/application/EnergyManagement/src/main/res/values-night/colors.xml
+++ b/application/EnergyManagement/src/main/res/values-night/colors.xml
@@ -13,6 +13,8 @@
+    <color name="charge_limit_track_background_color">#0FFFFFFF</color>
+    <color name="charge_limit_track_background_disabled_color">@color/charge_limit_track_background_color</color>
--- a/application/EnergyManagement/src/main/res/values/colors.xml
+++ b/application/EnergyManagement/src/main/res/values/colors.xml
@@ -20,6 +20,8 @@
+    <color name="charge_limit_track_background_color">#261F222A</color>
+    <color name="charge_limit_track_background_disabled_color">#0F1F222A</color>
```
（另：activity_main.xml 为 `seekbar_range_mode2` 挂上新建的空 style `@style/ChargeLimitSeekBar`，作为后续统一调节点。）

## 为什么能修复
把 vector fillColor 从字面量改为 `@color/` 引用后，白天取 `values/colors.xml` 的 #261F222A（外观不变），黑夜自动命中 `values-night/colors.xml` 的 #0FFFFFFF（白色 6% 透明度），未填充区域随主题正确切换；disabled 态夜间同引用日间色，保证置灰一致性。无逻辑改动、无副作用，属标准的"硬编码色值资源化"修法。

## 复盘与经验
- drawable（vector/shape）内的 fillColor/strokeColor 必须引用 color 资源，一旦硬编码就绕过了 day/night 限定符体系，是暗色模式 UI 不符的高频根因。
- UI 走查"白天正常、黑夜不对"时，第一件事就是全局搜十六进制字面量色值。
- 同步给控件挂空 style 作为统一入口，为后续同类问题收敛修改点。
