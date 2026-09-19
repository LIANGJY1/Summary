# SIR-6370 · 拨打阿拉伯文联系人时浮窗号码显示为RTL排列

- **提交**：`d4008915` | 2026-08-25 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
联系人名称含阿拉伯文时，通话浮窗里"姓名 + 号码"整串文本按 RTL 排列，导致电话号码也被反向显示（数字顺序错乱），与预期不符。

## 根因分析
`SmartEllipsizeTextView` 的 `showNameAndNumber`（smart 显示"姓名+号码"逻辑）中，此前直接用字符串拼接 `fullName + " " + phoneNumber` 再 `setText`。Android 的 TextView 双向文本（Bidi）算法遇到阿拉伯文等 RTL 字符后，会以基方向判定整段布局；阿拉伯文姓名与号码同处一个 span 文本流中时，紧随其后的号码也被当作 RTL 段的一部分参与重排，于是号码数字顺序呈现为反向（RTL）。关键点：号码本身是 LTR 内容，但被嵌进了 RTL 上下文却没有做方向隔离（isolate），这不是数据错误而是渲染方向问题。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/view/SmartEllipsizeTextView.java`

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/view/SmartEllipsizeTextView.java
@@ -131,17 +131,18 @@ public class SmartEllipsizeTextView extends AppCompatTextView {
         // 计算号码需要的宽度
         float numberWidth = paint.measureText(" " + phoneNumber);
+        CharSequence displayNumber = TextUtils.concat("\u2066", phoneNumber, "\u2069");
 
         // 如果总宽度足够，直接显示
         float fullWidth = paint.measureText(fullName + " " + phoneNumber);
         if (fullWidth <= availableWidth) {
-            setText(fullName + " " + phoneNumber);
+            setText(TextUtils.concat(fullName, " ", displayNumber));
             return;
         }
 
         // 需要对姓名进行省略
         String ellipsizedName = ellipsizeName(paint, availableWidth - numberWidth);
-        setText(ellipsizedName + " " + phoneNumber);
+        setText(TextUtils.concat(ellipsizedName, " ", displayNumber));
```

## 为什么能修复
`\u2066` 是 Unicode LEFT-TO-RIGHT ISOLATE（LRI），`\u2069` 是 POP DIRECTIONAL ISOLATE（PDI）。用 LRI/PDI 把号码包成一个方向隔离区，Bidi 算法在该区段内强制按 LTR 布局，且隔离边界切断它与前面阿拉伯文姓名之间的方向"传染"，姓名仍按原上下文 RTL 显示、号码固定 LTR。同时用 `TextUtils.concat` 生成 `CharSequence`，不影响原有的宽度测量与省略号逻辑。副作用极小：这两个控制字符是零宽不可见字符，`paint.measureText` 对其计宽基本为零，对 ellipsize 计算无可见影响。

## 复盘与经验
- 中东/多语言车机上，"RTL 字符 + 数字/号码"混排是高频 UI 坑：只要同一文本流中出现强 RTL 字符，后续 LTR 内容也会被重排，必须在拼串时就用 LRI/PDI（或 `BidiFormatter.unicodeWrap`）做方向隔离，而不是等测试发现。
- 隔离字符要加在"逻辑单元"边界上（这里是一个完整电话号码），而不是整条文本外层，才能既保住姓名的 RTL 正确显示、又固定号码方向。
- 该类自定义 View 自己做拼串 `setText`，绕过了 XML 里 `textDirection`/`textAlignment` 的常规配置路径，属性配置救不了拼串问题——凡是代码拼多语言文本处都要审视 Bidi 行为。
