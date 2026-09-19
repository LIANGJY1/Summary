# SIR-8173 · 里程设置界面"本次里程/小记里程"字体略小

- **提交**：`4ba7532e` | 2026-09-11 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心里程设置界面中，"本次里程"与"小记里程"两个分区标题字号比设计稿小，视觉上偏小。

## 根因分析
`activity_mileage_management.xml` 中两个分区标题 TextView（`@string/current_mileage_title` 与 `@string/subtotal_mileage_title`）的 `textSize` 均写成 `24sp`，缺陷库根因明确"字体大小为24sp，修改字体大小为28sp"——设计稿规格是 28sp，实现时按错误值落了布局，导致标题略小。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml`
```diff
                 android:text="@string/current_mileage_title"
                 android:textColor="@color/mileage_section_title_text_color"
-                android:textSize="24sp"
+                android:textSize="28sp"
@@
                 android:text="@string/subtotal_mileage_title"
                 android:textColor="@color/mileage_section_title_text_color"
-                android:textSize="24sp"
+                android:textSize="28sp"
```

## 为什么能修复
两处标题字号从 24sp 统一调整为设计规格 28sp，与整体分区标题层级一致。纯布局数值修改，无副作用；隐患仅在于字号散落布局中未抽成 dimen/style，同类标题仍可能各写各的值。

## 复盘与经验
- 字号/间距等规格值应抽取为 dimen 或 textAppearance 样式统一引用，避免同类标题各自硬编码导致不一致。
- UI 验收对照设计稿逐项核对 fontSize，是最廉价但最有效的拦截手段（C 级必现 UI 缺陷多属此类）。
- 同屏同类元素同时修改（本提交一次改两处）是对的，注意以后别漏。
