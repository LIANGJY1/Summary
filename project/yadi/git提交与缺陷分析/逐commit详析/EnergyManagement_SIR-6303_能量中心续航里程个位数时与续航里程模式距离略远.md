# SIR-6303 · 续航里程个位数时与续航模式文字距离过远

- **提交**：`aef32596` | 2026-08-24 | liqingqing | EnergyManagement | bugfix（提交头误标 [feature]；mistag 已核对）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心续航里程显示个位数（如 9）时，数字与右侧"续航里程模式"（WMTC/km 组合）之间的间距过大，与 UI 设计不符。

## 根因分析
`MainActivity.updateRangeUnitGroupSpacing(int range)` 按续航数字的位数动态调整 `llRangeUnitGroup` 的 `MarginStart`：`range >= 100` 时 8dp、`>= 10` 时 13dp、个位数 21dp——这是"上一版 UI 根据位数不同显示间距不同"的实现。该策略与设计稿冲突：设计要求数字右边缘与 WMTC/km 组合左边缘**固定 8dp**。数字变窄时固定宽度/居中布局叠加 21dp 边距，个位数场景视觉间距明显偏大。缺陷库根因"间距过大"。修复即删除按位数分档的逻辑，固定 8dp。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java（+2/-9）
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ updateRangeUnitGroupSpacing()
     private void updateRangeUnitGroupSpacing(int range) {
-        int marginDp;
-        if (range >= 100) {
-            marginDp = 8;
-        } else if (range >= 10) {
-            marginDp = 13;
-        } else {
-            marginDp = 21;
-        }
+        // 续航数字右边缘与 WMTC/km 组合左边缘固定保持 8dp。
         ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) binding.llRangeUnitGroup.getLayoutParams();
-        int marginPx = dpToPx(marginDp);
+        int marginPx = dpToPx(8);
         if (params.getMarginStart() == marginPx) {
             return;
         }
```

## 为什么能修复
删除位数分档，边距恒为 8dp，任何位数下数字与模式文字的间距一致且与设计稿吻合；`getMarginStart() == marginPx` 的短路判断保留，避免每帧重复 layout。隐患：方法名 `updateRangeUnitGroupSpacing` 仍暗示动态调整，实际已退化为固定值，参数 `range` 不再参与计算（保留签名减少调用方改动），语义上已成冗余调用点，后续可清理；若设计后续又要求"长数字压缩间距"，需要重新引入动态逻辑。

## 复盘与经验
- "按内容长度动态调间距"是设计对齐的常见误实现：除非设计稿明确要求，间距应由布局约束（chain/baseline）固定，而不是代码按位数算 margin。
- 视觉规格应尽量落在 XML/约束里，用 Java 代码调 margin 属于易错且难审查的实现方式。
- 动态逻辑退化为常量后，及时清理死参数与过时方法名，防止下一个维护者误以为分档逻辑仍在生效。
