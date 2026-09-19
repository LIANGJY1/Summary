# SIR-7002 · 姓名+数字组合的联系人，拨号浮窗只显示数字
- **提交**：`406931d4` | 2026-08-31 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
联系人姓名由汉字和数字组成（如"张三138xxx"）时，拨打/来电浮窗只显示数字部分，汉字姓名丢失。

## 根因分析
旧链路把姓名和号码拼成一个字符串再靠启发式反解析：`ViewUtil` 用 `formattedContactName + " " + displayNumber` 拼接后交给 `StringUtil.stringMaxNameOrPhone(callInfo)`，该工具按"优先显示号码"的规则取舍；`SmartEllipsizeTextView.setNameAndNumber(fullText)` 又用"找第一个数字的位置向前找空格"的策略把整串重新拆成 name/number，`numberReplace` 里只要数字段凑满 7 位就认可。对"汉字+数字"姓名，数字切割点落在姓名内部，姓名被截掉，加之 stringMaxNameOrPhone 的号码优先策略，最终只剩数字上屏。本质是"结构化数据（name, number）被压平成字符串再用猜测规则还原"，猜测规则在非典型输入上必然出错；配合自绘省略逻辑（`ellipsizeName`/`onSizeChanged` 重算）复杂度高且难维护。

## 关键代码修改
改动文件：ViewUtil.java、SmartEllipsizeTextView.java
```diff
--- a/.../telecom/utils/ViewUtil.java
             String formattedContactName = StringUtil.formateDisPlayName(rawContactName);
-            String callInfo = TextUtils.isEmpty(displayNumber)
-                    ? formattedContactName
-                    : formattedContactName + " " + displayNumber;
-            tvName.setNameAndNumber(StringUtil.stringMaxNameOrPhone(callInfo));
+            tvName.setNameAndNumber(formattedContactName, displayNumber);
```
```diff
--- a/.../view/SmartEllipsizeTextView.java（-148 行自绘省略逻辑改为原生跑马灯）
     private void init() {
         setMaxLines(1);
         setSingleLine(true);
-        setEllipsize(TextUtils.TruncateAt.END);
+        setEllipsize(TextUtils.TruncateAt.MARQUEE);
+        setMarqueeRepeatLimit(-1);
+        // 使用选中状态触发原生跑马灯，避免多路通话文本争抢焦点
+        setSelected(true);
     }
 
-    public void setNameAndNumber(String fullText) { ...90行解析策略... }
+    public void setNameAndNumber(String name, String number) {
+        CharSequence displayNumber = TextUtils.isEmpty(safeNumber)
+                ? "" : TextUtils.concat("\u2066", safeNumber, "\u2069");
+        String separator = TextUtils.isEmpty(safeName) || TextUtils.isEmpty(safeNumber) ? "" : " ";
+        setText(TextUtils.concat(safeName, separator, displayNumber));
+    }
```

## 为什么能修复
数据从源头就保持结构化：ViewUtil 直接把 `(name, number)` 两个参数传入，删除拼接+反解析+号码优先取舍整条链路，姓名与号码必然同屏；超长溢出改用原生跑马灯（MARQUEE + `setSelected(true)` + 无限重复），无需 Paint 测宽自绘省略；号码用 LTR 隔离符 U+2066/U+2069 包裹，防止混合文本书写方向错乱。风险点：`setSelected(true)` 常驻会让跑马灯一直滚动，视觉上是否可接受需 UI 确认；多个浮窗同时常驻选中态时 marquee 焦点策略（此版已用 selected 规避 focus 争抢）。

## 复盘与经验
- 永远不要把两个语义字段拼成一个字符串再"智能"拆开——传递结构化参数，启发式解析只会制造新 bug；本次净删 148 行解析代码就是最好的证明。
- "优先显示 A"这类取舍规则若无产品明确依据，应默认"都显示+滚动/省略"，而不是丢弃用户数据（姓名）。
- 文本溢出优先用平台原生能力（MARQUEE/ellipsize），自绘测宽截断在多语言、混合书写方向下坑极多。
