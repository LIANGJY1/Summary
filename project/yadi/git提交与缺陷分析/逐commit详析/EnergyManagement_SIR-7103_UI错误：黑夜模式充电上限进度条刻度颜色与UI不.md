# SIR-7103 · 黑夜模式充电上限进度条刻度颜色与 UI 不一致
- **提交**：`1e0c3fb3` | 2026-09-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
黑夜模式下，充电上限进度条（自定义 `ScaleTickView`）的刻度颜色仍沿用白天配色的深色系，与 UI 设计稿不一致。

## 根因分析
`ScaleTickView` 构造函数中对小刻度 `minorPaint` 硬编码了白天颜色 `#991F222A` 并再叠 `setAlpha(0.4)`，完全没有感知日夜模式。该 View 是纯代码绘制（无 drawable/夜间资源目录），切到黑夜模式后深色刻度在深色背景上不可见/观感错误。值得注意的是顺带修了一个隐患：`build.gradle` 同时存在 `libs.lifecycle.common.java8` 与缺依赖问题，补充了 `libs.androidx.lifecycle.common`。

## 关键代码修改
改动文件：`.../view/custom/ScaleTickView.java`、`application/EnergyManagement/build.gradle`
```diff
--- .../energymanagement/view/custom/ScaleTickView.java
@@ public ScaleTickView(Context context, AttributeSet attrs)
-        // 小刻度：UI 稿 = #991F222A 圆点 + alpha 0.4 → 视觉 alpha 24%
-        minorPaint.setColor(Color.parseColor("#991F222A"));
-        minorPaint.setAlpha((int) (255 * 0.4f));   // 61, 相当于 #3D1F222A
+        // 小刻度：白天保持 #991F222A；黑夜使用 #99EEEEEE。
+        boolean isNightMode = (getResources().getConfiguration().uiMode
+                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
+        if (isNightMode) {
+            minorPaint.setColor(Color.parseColor("#99EEEEEE"));
+        } else {
+            minorPaint.setColor(Color.parseColor("#991F222A"));
+            minorPaint.setAlpha((int) (255 * 0.4f));
+        }
```

## 为什么能修复
构造时读取 `Configuration.uiMode` 判定 `UI_MODE_NIGHT_YES`，黑夜下小刻度改用浅灰半透明 `#99EEEEEE`，白天保持原色，两种模式下都与设计稿对齐。隐患：颜色判定只在构造时执行一次，若运行时日夜模式热切换而不重建 View，颜色不会刷新——车机通常切换模式会重建界面，风险可接受。

## 复盘与经验
- 自绘 View 里硬编码颜色等于放弃资源系统的 day/night 能力；若必须代码取色，应引用 `?attr` 主题属性或至少按 uiMode 分支。
- 绘制参数在构造函数一次性初始化的 View，要考虑配置变化（夜间模式、density）时是否需要 `onConfigurationChanged` 重算。
- bugfix 提交里夹带依赖修复（build.gradle）时应说明，避免审查者误判改动范围。
