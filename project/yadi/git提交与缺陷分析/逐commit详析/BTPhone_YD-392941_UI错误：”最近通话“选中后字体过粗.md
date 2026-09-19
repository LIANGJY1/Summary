# YD-392941 · "最近通话"选中后字体过粗、下方横线没有圆角

- **提交**：`5d7ee2e1` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392941，defs 无条目）

## 问题
蓝牙电话主页 tab 栏（联系人/收藏/最近通话）选中项字体过粗，选中指示横线为直角矩形，与 UI 设计稿不符。

## 根因分析
两处叠加：
1. `MainActivity` 的 tab 切换逻辑（约 848-880 行）对选中项调用 `setTypeface(Typeface.DEFAULT_BOLD)`、非选中项 `setTypeface(Typeface.DEFAULT)`。而布局里 tab 文案又声明了 `android:textFontWeight="400"`。`DEFAULT_BOLD` 是系统默认字体的 BOLD 变体，直接覆盖会把字重拉到 700，比设计稿明显偏粗；且 `setTypeface` 与 `textFontWeight` 两套字重机制互相打架。缺陷库描述的"字重问题"即此。
2. 指示条 `indicatorContacts` 等直接 `android:background="@color/text_default_default"`——纯色没有 shape，渲染为直角，无法呈现设计稿的圆角胶囊形。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java；application/BTPhone/src/main/res/drawable/bg_indicator_rounded.xml（新增）；application/BTPhone/src/main/res/layout/activity_main.xml

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
             binding.tvContacts.setTextColor(...R.color.text_default_default);
             binding.tvFavorites.setTextColor(...R.color.text_default_press);
             binding.tvCallLog.setTextColor(...R.color.text_default_press);
-            binding.tvContacts.setTypeface(Typeface.DEFAULT_BOLD);
-            binding.tvFavorites.setTypeface(Typeface.DEFAULT);
-            binding.tvCallLog.setTypeface(Typeface.DEFAULT);
+            //binding.tvContacts.setTypeface(Typeface.DEFAULT_BOLD);
+            //binding.tvFavorites.setTypeface(Typeface.DEFAULT);
+            //binding.tvCallLog.setTypeface(Typeface.DEFAULT);
             binding.indicatorContacts.setVisibility(View.VISIBLE);
```

```diff
--- application/BTPhone/src/main/res/drawable/bg_indicator_rounded.xml（新增）
+<shape xmlns:android="http://schemas.android.com/apk/res/android"
+    android:shape="rectangle">
+    <corners android:radius="60dp" />
+    <solid android:color="@color/text_default_default" />
+</shape>
--- application/BTPhone/src/main/res/layout/activity_main.xml
                         <View
                             android:id="@+id/indicatorContacts"
                             android:layout_width="60dp"
-                            android:layout_height="3dp"
+                            android:layout_height="5dp"
                             android:layout_marginTop="6dp"
-                            android:background="@color/text_default_default"
+                            android:background="@drawable/bg_indicator_rounded"
                             android:visibility="visible" />
```

（`activity_main.xml` 同时删除了三个 tab 文案的 `android:textFontWeight="400"` 属性，收藏 tab 增加 `layout_marginRight="2dp"` 微调间距。）

## 为什么能修复
注释掉 `setTypeface(DEFAULT_BOLD/DEFAULT)` 调用后，字重完全回归布局声明的字体默认渲染，选中态仅靠颜色（`text_default_default` vs `text_default_press`）区分，过粗问题消除；指示条背景换成 60dp 圆角的 `bg_indicator_rounded` shape 并加厚到 5dp，呈现设计稿的圆角胶囊样式。隐患：`setTypeface` 代码以注释而非删除的方式保留，且未同步清理 `textFontWeight` 之外的运行时字重设置入口，后续若恢复注释需重新确认与设计稿一致。

## 复盘与经验
- **同一文本的字重不要两处控制**：XML `textFontWeight` 与代码 `setTypeface` 叠用时，运行时调用会覆盖 XML，"代码里默认加粗+布局里声明 400"必然产生观感冲突。
- **选中态表达优先用颜色/指示器而非字体加粗**：车机大字号场景下加粗观感突兀，本例改为"颜色深浅+圆角指示条"更贴设计稿。
- **纯色 background 无法带圆角**：需要圆角/描边一律用 shape drawable；`<corners>` 半径≥高度一半即为胶囊形。
- **删代码优于注释代码**：注释掉的 `setTypeface` 三处调用属于死代码，会误导后续维护者（本批 UI 系列提交中多处采用注释而非删除，长期应清理）。
