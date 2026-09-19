# SIR-7912/7913/7914 · 通知中心时间单位字重与 UI 不一致
- **提交**：`200deae6` | 2026-09-09 | caohongliang | SystemUI | bugfix
- **缺陷库**：SIR-7912 C·必现·主交互（fwk 不支持非 100 整数字重，未自动映射）；SIR-7913 C·必现·主交互（字重错误）；SIR-7914 C·必现·主交互（同 7912）

## 问题
通知中心大号时间及其单位（"分"等）显示字重与 UI 稿不符——设计要求 MiSans Normal（380）级别的中等偏细字重，实际渲染按默认粗细显示。

## 根因分析
缺陷库登记根因：framework 对 `textStyle`/字重设置只支持 100 整数档（normal/bold），且不会自动映射到最近的可变字重，UI 稿要求的 380 这类非整百字重设置后不生效。`notification_center.xml` 中大时间文本（102sp，`fontFamily="MiSans"`）与单位文本（28sp）此前均未声明任何字重属性，走默认渲染，与稿不符。修复在两处 TextView 上补字重声明。（注：缺陷库 solution 写的是"使用 fontVariationSettings 绕过 fwk 字重映射"，但实际 diff 采用的是 `android:textFontWeight` + integer 资源 380——二者均依赖 MiSans 可变字体，以 diff 实际为准。）

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/notification_center.xml；application/SystemUI/src/main/res/values/integers.xml
```diff
--- application/SystemUI/src/main/res/layout/notification_center.xml
@@ -32,6 +32,7 @@
         android:lineSpacingExtra="-3sp"
         android:letterSpacing="0.05"
         android:singleLine="true"
+        android:textFontWeight="@integer/text_weight_normal"
         android:textColor="#20232B"
         android:textSize="102sp" />
@@ -46,6 +47,7 @@
         android:fontFamily="MiSans"
         android:gravity="top"
         android:lineSpacingExtra="-1sp"
+        android:textFontWeight="@integer/text_weight_normal"
         android:textColor="@color/text_default_press"
         android:textSize="28sp" />
```
```diff
--- application/SystemUI/src/main/res/values/integers.xml
@@ -42,4 +42,5 @@
+    <integer name="text_weight_normal">380</integer>
 </resources>
```

## 为什么能修复
`android:textFontWeight`（API 28+）配合 MiSans 可变字体可直接命中字库内 380 字重实例，不再受 fwk 只认 100 整数档的 textStyle 限制，时间与单位字重与 UI 稿一致；字重值抽成 `@integer/text_weight_normal` 统一维护，后续其他界面复用同一语义。前提是设备字体确为含多字重的 MiSans 可变版本，若被替换为静态字库则 380 会回退默认，存在环境依赖风险。

## 复盘与经验
- 车机大屏 UI 大量使用非标准字重（380/450 等）时，`textStyle="bold|normal"` 两档根本不够；API 28+ 用 `textFontWeight`，需要更深控制时才用 `fontVariationSettings`（如 'wght' 380）。
- 字重值不要内联魔法数字，抽 integer/color 风格资源统一命名（text_weight_normal），多界面字重一致性才有保障。
- 自定义字体（MiSans）+ 非整百字重是"设置了不生效"的高发组合，验证时要确认字体文件本身包含对应字重轴。
