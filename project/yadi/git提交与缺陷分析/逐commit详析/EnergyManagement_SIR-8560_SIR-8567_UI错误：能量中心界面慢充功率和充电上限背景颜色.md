# SIR-8560/8567 · 能量中心卡片背景色与充电信息字重不符合 UI 式样
- **提交**：`0b94f1a2` | 2026-09-17 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C（两条，SIR-8560/8567） · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心界面"慢充功率/充电上限"卡片背景色与 UI 式样不一致（SIR-8560）；充电信息（如"4h 50min"剩余时间、功率/电流/电压数值与单位）字重与式样不一致（SIR-8567）。

## 根因分析
两个问题各自独立：
1. 背景色：`bg_bottom_card_large.xml`/`bg_bottom_card_small.xml`（含 drawable-night 版本）原来用 `<bitmap android:src="@drawable/big_card">` 位图平铺，切图颜色与式样标注的纯色圆角卡（`#222325` 夜间、`#CCFFFFFF` 白天）存在偏差，且位图拉伸在车机分辨率下易出现色差。
2. 字重：`MainActivity.configureTypefaces()` 中充电信息数值 TextView（`tvMileageDebugChargePowerValue`、`tvMileageDebugChargeCurrentValue`、`tvMileageDebugChargeVoltageValue` 等）统一 `EnergyTypeface.apply(..., regular)`，而式样要求"数值 Medium、单位 Regular"；同时 `formatRemainingTime()` 构造的 SpannableString 只对单位做了 `AbsoluteSizeSpan` 缩小，数值与单位共用同一字重，无法体现字重差。

## 关键代码修改
改动文件：EnergyTypefaceSpan.java（新增）、MainActivity.java、drawable(-night)/bg_bottom_card_large.xml、bg_bottom_card_small.xml
```diff
// application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/EnergyTypefaceSpan.java（新文件）
+public class EnergyTypefaceSpan extends MetricAffectingSpan {
+    private final Typeface mTypeface;
+    public EnergyTypefaceSpan(Typeface typeface) { mTypeface = typeface; }
+    @Override public void updateMeasureState(TextPaint paint) {
+        if (mTypeface != null) { paint.setTypeface(mTypeface); }
+    }
+    @Override public void updateDrawState(TextPaint paint) {
+        if (mTypeface != null) { paint.setTypeface(mTypeface); }
+    }
+}
```
```diff
// application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
-        EnergyTypeface.apply(binding.tvMileageDebugChargePowerValue, regular);
+        // 充电信息字重规范：数值 Medium、单位 Regular
+        EnergyTypeface.apply(binding.tvMileageDebugChargePowerValue, medium);
...
+            // 小时数值 Medium
+            spannable.setSpan(new EnergyTypefaceSpan(medium), numberStart, cursor, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
             // 单位 h 使用小字号
             spannable.setSpan(new AbsoluteSizeSpan(unitSizeSp, true), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
+            spannable.setSpan(new EnergyTypefaceSpan(regular), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
```
```diff
// application/EnergyManagement/src/main/res/drawable-night/bg_bottom_card_small.xml
-<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
-    android:gravity="fill"
-    android:src="@drawable/small_card_night" />
+<vector ... android:width="276dp" android:height="120dp" ...>
+    <group>
+        <clip-path android:pathData="M12 0H264Q276 0 276 12V108Q276 120 264 120H12Q0 120 0 108V12Q0 0 12 0Z" />
+        <path android:fillColor="#222325" android:pathData="..." />
+    </group>
+</vector>
```

## 为什么能修复
背景侧把位图引用替换为式样精确标注的 vector 纯色圆角卡（含 `clip-path` 圆角与 `fillColor`），昼夜两套资源同改，颜色与形状由式样数据直接生成，消除切图色差。字重侧：新建 `EnergyTypefaceSpan`（继承 `MetricAffectingSpan`，注释说明不能用基于字体族名的 `TypefaceSpan`，因为 MiSans 是从 assets 加载的 Typeface 对象），在 `formatRemainingTime()` 里对数值片段 span Medium、单位片段 span Regular，并把相关 TextView 基础字重从 regular 调整为 medium。隐患：`updateMeasureState/updateDrawState` 都设置 Typeface，若与 `AbsoluteSizeSpan` 叠加需注意跨度边界，本实现按"数值段/单位段"分别 setSpan，边界清晰。

## 复盘与经验
- 同一 TextView 内混排"数值+单位"且要求不同字号/字重时，`SpannableString` + 自定义 `MetricAffectingSpan` 是标准解法；assets 加载的 Typeface 必须用 span 对象而非 `TypefaceSpan(familyName)`。
- UI 式样给的是精确颜色值/圆角参数时，用 vector drawable 按标注直写，比让视觉切位图更不易出现偏差，还能自适应分辨率。
- "数值 Medium、单位 Regular"这类排版规范适合抽成工具类/自定义 View 统一实现，散落在各 Activity 的 `EnergyTypeface.apply` 调用极易漏改（本次正是漏改）。
