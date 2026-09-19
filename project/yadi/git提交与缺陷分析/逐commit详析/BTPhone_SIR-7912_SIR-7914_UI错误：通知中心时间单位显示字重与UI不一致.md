# SIR-7912/7914 · 通知中心时间字重不生效（textFontWeight 不支持非 100 整数倍）

- **提交**：`92f37c07` | 2026-09-11 | caohongliang | BTPhone（提交头标 BTPhone，实际改动在 SystemUI 通知中心资源） | bugfix
- **缺陷库**：SIR-7912 等级 C · 必现-80%~100% · 关闭 · 主交互；SIR-7914 同根因同方案（提交头标影响等级 D，以缺陷库 C 为准）

## 问题
通知中心的大号时间与日期（时间单位）字重与 UI 设计稿不一致——设计要求 380 的中等偏细字重，实际显示为默认字重（设置不生效）。

## 根因分析
`notification_center.xml` 中时间（102sp）与日期（28sp）两个 TextView 用 `android:textFontWeight="@integer/text_weight_normal"` 设置字重。该属性在本车机 fwk 的实现里只支持 **100 的整数倍**字重档（映射到系统预置的字重字体族），`text_weight_normal` 对应的非整百数值（380 档）不在支持列表，fwk 既不生效也不就近映射（缺陷库："fwk 不支持非 100 整数的字重设置，且未自动映射到最近字重，所以字重不生效"）。而 MiSans 是可变字体，其 `wght` 轴本身支持任意 0-1000 数值，能力在字体侧而非 fwk 的 textFontWeight 映射层。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/notification_center.xml（1 文件 +2/-2）
```diff
--- application/SystemUI/src/main/res/layout/notification_center.xml
@@ 时间 TextView（102sp）
-        android:textFontWeight="@integer/text_weight_normal"
+        android:fontVariationSettings="'wght' 380"
@@ 日期 TextView（28sp）
-        android:textFontWeight="@integer/text_weight_normal"
+        android:fontVariationSettings="'wght' 380"
```

## 为什么能修复
`android:fontVariationSettings` 直接向可变字体下发 `wght` 轴值（'wght' 380），绕过 fwk 的 textFontWeight→字重族映射，380 精确生效，两个 TextView 字重与设计稿一致。前提是字体确为可变字体（MiSans VF）且 API 26+（车机平台满足）；隐患：fontVariationSettings 会覆盖 textStyle 的 bold 语义，且若日后替换为非可变字体，该属性静默失效，需要回归验证。

## 复盘与经验
- 平台属性的能力边界（textFontWeight 仅整百档）要在设计系统里前置声明，UI 稿给出任意字重值时先确认落地通道。
- 可变字体场景优先用 `fontVariationSettings` 精确控制 wght 轴；textFontWeight 更适合在"只用 100/400/700 经典档"的场景。
- 两处同族缺陷（7912 时间、7914 日期/单位）合并为一个提交修复是合理做法，但缺陷单要互相关联根因，避免重复回归。
