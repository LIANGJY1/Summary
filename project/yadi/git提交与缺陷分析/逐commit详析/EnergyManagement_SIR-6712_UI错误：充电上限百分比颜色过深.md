# SIR-6712 · 充电上限百分比文字颜色过深

- **提交**：`99879824` | 2026-08-28 | liqingqing | EnergyManagement | bugfix（UI 单行修改）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心充电上限滑块（thumb）上的百分比文字颜色过深，与 UI 设计稿不符。

## 根因分析
百分比文字的样式定义在 `values/style.xml` 的 `seekbar_thumb_text` 样式，其 `android:textColor` 引用了 `@color/text_default_default`——该色是正文默认色，明度偏低，用在彩色滑块背景上显得过深。设计稿要求使用更高明度的 `icon_default_press` 色。属于典型的"样式引用了语义不匹配的颜色资源"问题。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/values/style.xml
```diff
--- application/EnergyManagement/src/main/res/values/style.xml
     <style name="seekbar_thumb_text">
         <item name="android:textSize">24sp</item>
-        <item name="android:textColor">@color/text_default_default</item>
+        <item name="android:textColor">@color/icon_default_press</item>
     </style>
```

## 为什么能修复
`seekbar_thumb_text` 是滑块文字的唯一样式入口，换色后所有引用该样式的百分比文字同步变浅，与设计稿一致；日间/夜间取值跟随颜色资源本身的限定符，无额外风险。

## 复盘与经验
- 颜色资源应按语义命名并按场景选用（正文色/图标态/强调色），"哪里顺手用哪里"会导致明度层级错乱。
- 文字在非白色背景（滑块、按钮、彩条）上时，颜色要按背景单独定义，不能复用页面正文默认色。
