# SIR-6799 · 充电结束/终止文言颜色与 UI 不一致

- **提交**：`24e9eafa` | 2026-08-28 | liqingqing | EnergyManagement | bugfix（UI 单行修改）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模（提交信息无等级标注，以缺陷库为准）

## 问题
能量中心"充电结束/充电终止"状态下的提示文言颜色过深（正文默认色），与 UI 设计稿的浅色要求不一致。

## 根因分析
充电结束/终止文案套用 `values/themes.xml` 中的 `ChargeEnergyInfo` 样式（20sp），其 `android:textColor` 引用的是 `@color/text_default_default`——页面正文默认色，明度低于设计稿要求的状态提示色。与 `99879824`（滑块文字换 `icon_default_press`）同属"状态类文言误用正文默认色"的问题。修复把该样式颜色换为更高明度的 `text_default_press`。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/values/themes.xml
```diff
--- application/EnergyManagement/src/main/res/values/themes.xml
     <style name="ChargeEnergyInfo">
         <item name="android:textSize">20sp</item>
-        <item name="android:textColor">@color/text_default_default</item>
+        <item name="android:textColor">@color/text_default_press</item>
     </style>
```

## 为什么能修复
`ChargeEnergyInfo` 是充电能量信息（含结束/终止提示）文字的唯一样式入口，换色后该状态文言立即与设计稿一致；同文件相邻样式（`StopChargingButtonText` 等）保持原色不受影响。单行样式改动，无逻辑风险。

## 复盘经验
- 状态提示类文言（结束/终止/超时等）与操作类正文应使用不同的颜色层级资源，统称"default"的颜色最容易被滥用。
- 同一模块内 `text_default_press`、`icon_default_press` 被多处 UI 单陆续纠正，说明设计还原时颜色映射最初就没有系统化，宜一次性对表。
