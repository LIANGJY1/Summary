# SIR-8494 · 控制中心极致续航打开后无"开启中"文言

- **提交**：`452e2078` | 2026-09-16 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
下拉控制中心中打开"极致续航"开关后，图标切换了选中态，但没有出现预期的"开启中"文字提示。

## 根因分析
`QuickSettingFragment` 收到极致续航状态变化（`CanSignalConstants.SWITCH_ON`）时只做了两件事：`qdvTripMode.isSelected = isExtremeRangeEnabled` 和切换 `vector_ultimate_battery_sel` 图标，从未设置任何副标题文本；而通用控件 `QuickDrawableView` 本身只有主 `textView` 与 `imageView`，没有可承载"开启中"文案的子控件，即使想显示也没有 UI 载体。属于功能开发时遗漏副标题通道的典型"文言遗漏"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt、application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/widget/QuickDrawableView.java、application/SystemUI/src/main/res/layout/layout_drawable_textview.xml
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
             val isExtremeRangeEnabled = it == CanSignalConstants.SWITCH_ON
             LogUtils.d(TAG, "Extreme range state changed: $it")
             qdvTripMode.isSelected = isExtremeRangeEnabled
+            qdvTripMode.setDescription(
+                if (isExtremeRangeEnabled) getString(R.string.connect_on) else ""
+            )
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/widget/QuickDrawableView.java
+    public void setDescription(CharSequence description) {
+        descriptionTextView.setText(description);
+        descriptionTextView.setVisibility(description == null || description.length() == 0
+                ? View.GONE : View.VISIBLE);
+    }
--- application/SystemUI/src/main/res/layout/layout_drawable_textview.xml
+    <TextView
+        android:id="@+id/descriptionTextView"
+        style="@style/BaseSmallTextStyles"
+        android:layout_below="@id/textView"
+        android:layout_marginTop="@dimen/dp_6"
+        android:visibility="gone" />
```

## 为什么能修复
给 `QuickDrawableView` 补齐了通用的副标题能力：布局新增 `descriptionTextView`（默认 gone），控件层新增 `setDescription()`（空串自动隐藏），业务层在极致续航状态回调里按开/关写入 `R.string.connect_on` 或清空。开→显示"开启中"，关→自动隐藏，其余未设置 description 的快捷开关不受影响。隐患：该控件是快捷区通用控件，新增副标题会改变其高度，其他使用方若对高度敏感需回归确认。

## 复盘与经验
- "状态有图标无文字"的 UI 缺陷，先确认通用控件有没有副标题通道——没有就补控件能力，而不是在业务里塞临时 TextView。
- 副标题 setter 设计成"空串即隐藏"可让业务方无需关心可见性，避免状态残留。
- 新增快捷开关时对照 UX 稿逐项核对：选中态、图标、文案三要素，缺一即是本类缺陷。
