# YD-392945 · tab 栏文案未水平对齐，切换时上下跳动

- **提交**：`ca772bbb` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392945，defs 无条目）

## 问题
蓝牙电话主页三个 tab（联系人/收藏/最近通话）文案不在同一水平线上，切换 tab 时文案上下跳动。

## 根因分析
tab 结构是"竖排 LinearLayout：文案 TextView + 下方指示条 View（5dp 高 + 6dp marginTop）"。`MainActivity` 切换 tab 时对未选中指示条调用 `setVisibility(View.GONE)`——`GONE` 会把指示条从布局中**完全移除**，其高度和 margin 全部塌陷，该 tab 的 LinearLayout 内容变矮，文案整体位置随之改变；三个 tab 横向并排，选中的那个带指示条（高），未选中的塌陷（矮），造成文案基线不齐、切换时跳动。缺陷库根因"隐藏控件的方法不对"即指 `GONE`/`INVISIBLE` 用错。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
             binding.indicatorContacts.setVisibility(View.VISIBLE);
-            binding.indicatorFavorites.setVisibility(View.GONE);
-            binding.indicatorCallLog.setVisibility(View.GONE);
+            binding.indicatorFavorites.setVisibility(View.INVISIBLE);
+            binding.indicatorCallLog.setVisibility(View.INVISIBLE);
@@
-            binding.indicatorContacts.setVisibility(View.GONE);
+            binding.indicatorContacts.setVisibility(View.INVISIBLE);
             binding.indicatorFavorites.setVisibility(View.VISIBLE);
-            binding.indicatorCallLog.setVisibility(View.GONE);
+            binding.indicatorCallLog.setVisibility(View.INVISIBLE);
@@
-            binding.indicatorContacts.setVisibility(View.GONE);
-            binding.indicatorFavorites.setVisibility(View.GONE);
+            binding.indicatorContacts.setVisibility(View.INVISIBLE);
+            binding.indicatorFavorites.setVisibility(View.INVISIBLE);
             binding.indicatorCallLog.setVisibility(View.VISIBLE);
```

## 为什么能修复
`INVISIBLE` 只是不绘制、**仍占位**，指示条的高度和 marginTop 继续参与布局，三个 tab 的文案在任何选中态下位置恒定，基线自然对齐、切换不再跳动。副作用：每个未选中指示条仍占约 11dp 的布局空间（设计上本就预留了该空间，无实际影响）。这是 GONE/INVISIBLE 语义差异的最典型案例。

## 复盘与经验
- **GONE 改变布局、INVISIBLE 只隐藏绘制**：横排并排的成组控件互斥显隐时，若各分组高度依赖其中子元素，用 GONE 会导致文案/图标位置漂移，应默认用 INVISIBLE 保持占位。
- **"切换时跳动"类 UI bug 先查可见性 API**：现象是布局重算，根因几乎总是 GONE/移除/尺寸变化，从 `setVisibility(GONE)` 入手一行定位。
- **并排元素的基线对齐依赖结构对称**：三个 tab 的 LinearLayout 内部结构必须一致（子元素数量、占位相同），任何一侧塌陷都会破坏整体对齐。
