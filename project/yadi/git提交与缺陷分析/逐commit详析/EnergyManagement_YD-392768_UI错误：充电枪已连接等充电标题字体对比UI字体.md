# YD-392768 · 充电标题字体比 UI 稿略小
- **提交**：`a5097a6a` | 2026-06-29 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：未关联单号（YD 内部单）

## 问题
能量中心"充电枪已连接"等充电状态标题字体，与 UI 设计稿对比偏小。

## 根因分析
充电状态标题使用 `values/themes.xml` 中的样式 `ChargeGunConnectedStatus`，该样式 `android:textSize` 写死为 `38sp`，而 UI 稿要求 `48sp`。属于纯样式常量与设计稿不一致的还原度问题，无逻辑缺陷。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/values/themes.xml`
```diff
--- a/application/EnergyManagement/src/main/res/values/themes.xml
@@ -43,7 +43,7 @@
     <style name="ChargeGunConnectedStatus">
-        <item name="android:textSize">38sp</item>
+        <item name="android:textSize">48sp</item>
         <item name="android:textColor">@color/text_default_default</item>
     </style>
```

## 为什么能修复
将样式字号直接改为设计稿规定的 48sp，所有引用 `ChargeGunConnectedStatus` 样式的充电标题（充电枪已连接等）一并生效，一处修改全局对齐。无副作用。

## 复盘与经验
- 充电类多状态标题共用 style，是正确的收敛方式——修字号只需改一处，不必逐布局排查。
- UI 还原度类问题（字号/颜色/间距）建议走 UI 走查清单逐样式比对，而不是等提测后再发现。
