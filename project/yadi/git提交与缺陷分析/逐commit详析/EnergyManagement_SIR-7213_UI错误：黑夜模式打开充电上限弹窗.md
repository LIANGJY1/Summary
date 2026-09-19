# SIR-7213 · 黑夜模式下充电上限弹窗仍显示白天配色

- **提交**：`3d3d7a15` | 2026-09-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C（提交标注 B）· 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
黑夜模式打开"充电上限"说明弹窗（dialog_charge_limit_info），弹窗标题/描述/关闭按钮仍是白天的深色文字浅色样式，与夜间主题不符。

## 根因分析
弹窗相关颜色全部硬编码在布局与样式中：`dialog_charge_limit_info.xml` 里标题写 `android:textColor="#1F222A"`、描述写 `#991F222A`；`style.xml` 的 `ChargeLimitInfoCloseText` 样式写死 `#1F222A`；`drawable/button.xml` 关闭按钮背景写死 `solid #FFFFFF`。硬编码色值不参与资源限定符（values-night）匹配，因此切到黑夜模式后这些颜色不会变化，弹窗整体呈白天观感。缺陷库根因"颜色值不对"，实为"颜色未 token 化"。

## 关键代码修改
改动文件：drawable/button.xml、layout/dialog_charge_limit_info.xml、values-night/colors.xml、values/colors.xml、values/style.xml
```diff
// application/EnergyManagement/src/main/res/layout/dialog_charge_limit_info.xml
-            android:textColor="#1F222A"
+            android:textColor="@color/charge_limit_info_title_text_color"
-            android:textColor="#991F222A"
+            android:textColor="@color/charge_limit_info_desc_text_color"
```
```diff
// application/EnergyManagement/src/main/res/values-night/colors.xml（新增夜间值）
+    <color name="charge_limit_info_title_text_color">#EEEEEE</color>
+    <color name="charge_limit_info_desc_text_color">#99EEEEEE</color>
+    <color name="charge_limit_info_close_text_color">#EEEEEE</color>
+    <color name="charge_limit_info_close_bg_color">#1AFFFFFF</color>
```

## 为什么能修复
把写死的色值抽取为 `charge_limit_info_*` 颜色 token，白天值放 `values/colors.xml`（#1F222A 系）、夜间值放 `values-night/colors.xml`（#EEEEEE 系），系统在 UI 模式切换时自动重载资源，弹窗随主题变色。无逻辑改动、无副作用；隐患仅在于若日/夜 token 后续只改一边会再次不同步。

## 复盘与经验
- 支持夜间模式的项目里，布局/style/drawable 中出现硬编码 `#RRGGBB` 是必然复发的 bug 源，code review 应把"颜色必须引用 token"当作硬性规则。
- 判断某视图是否会跟主题走，快速检查法：全局搜该布局里的 `textColor="#`、`solid android:color="#"`。
- 同一弹窗的颜色应成组命名（title/desc/close 同前缀），避免日夜间值错配。
