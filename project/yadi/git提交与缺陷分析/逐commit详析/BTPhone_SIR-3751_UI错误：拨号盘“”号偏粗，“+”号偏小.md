# SIR-3751 · 拨号盘"*"号偏粗、"+"号偏小

- **提交**：`c484f094` | 2026-07-28 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙电话拨号盘 UI 中，"*"号显示偏粗（比设计稿笔画粗）、"+"号偏小，与 UI 设计稿不一致。

## 根因分析
两个独立原因叠加：
1. `DialPadView.NUMBERS` 数组中星号键用的字符是 Unicode "✱"（U+2731 HEAVY ASTERISK，重星号字形），而非普通 "*"。重星号本身字形粗，且各字体渲染差异大——缺陷库"**号由字体决定**"即指此：字符选错，字重由所选字体的该字形决定，无法通过正常字重控制。
2. "+"号挂在 "0" 键的字母位（`LETTERS` 数组下标 10 为 "+"），由 `DialPadButton` 的 `letterText` 用统一 12sp 渲染，作为主功能符号的"+"与数字 26sp 相比明显偏小——缺陷库"+号字体大小设置错误"。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadButton.java；application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadView.java

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadView.java
-    private static final String[] NUMBERS = {"1", "2", ..., "9", "✱", "0", "#"};
+    private static final String[] NUMBERS = {"1", "2", ..., "9", "*", "0", "#"};
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadButton.java
-        boolean isNeedCenter = "✱".equals(number) || "#".equals(number);
+        boolean isNeedCenter = "*".equals(number) || "#".equals(number);
@@
         numberText = new TextView(getContext());
         numberText.setText(number);
-        numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
-        numberText.setTypeface(Typeface.DEFAULT);
+        if (number.equals("*")) {
+            numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
+            numberText.setTypeface(Typeface.create(Typeface.DEFAULT,250,false));
+        } else {
+            numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
+            numberText.setTypeface(Typeface.create(Typeface.DEFAULT, 380, false));
+        }
@@
-            letterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
+            if (number.equals("0")) {
+                letterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
+            } else {
+                letterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
+            }
```

## 为什么能修复
把 "✱" 换成普通 "*" 后，字形粗细改由 `Typeface.create(Typeface.DEFAULT, 250, false)` 的可变字重（250 细体）控制，视觉上"变细"；同时星号键字号单独放大到 60sp 补偿普通星号字形偏小的观感。"0" 键的 "+" 从 12sp 提到 16sp 解决偏小。配套把所有 `"✱".equals(number)` 判断同步改为 `"*"`，避免字符不一致导致居中逻辑失效。注意：`Typeface.create(Typeface, int, boolean)` 三参重载要求 API 28+，本次为此给 `DialPadButton` 构造器和 `init` 补了 `@RequiresApi(api = Build.VERSION_CODES.P)` 注解，说明车机系统镜像为 Android 9+。

## 复盘与经验
- **特殊符号不要用"长得像"的 Unicode 变体**：✱（U+2731）与 * 视觉相近但语义/字重完全不同，UI 还原应优先使用设计指定的标准字符+字重控制，而非特殊码位。
- **同一控件不同符号需要差异化字号/字重配置**：拨号盘的 *、#、+ 是功能符号不是数字，统一 textStyle 无法兼顾，应按 key 单独配置。
- **用可变字体字重（weight 250/380）精细还原设计稿**，比换字体文件更轻量；但要注意 API 级别（API 28+）并显式声明 `@RequiresApi`。
- **改字符常量时全链路同步**：数据源（`NUMBERS`）与所有 `equals` 判断点必须一起改，否则产生"显示一个字符、逻辑比对另一个字符"的隐性分裂。
