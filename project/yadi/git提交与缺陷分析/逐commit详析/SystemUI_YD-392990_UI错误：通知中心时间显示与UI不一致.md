# YD-392990 · 通知中心时间显示与 UI 不一致（星期几→周几）
- **提交**：`a1612471` | 2026-07-30 | liqingqing | SystemUI | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
通知中心日期时间显示与 UI 稿不一致：日期尾部显示"星期几"全称（如"星期三"），应为缩写"周几"；12 小时制下上午/下午标记的格式与间距也与设计不符。

## 根因分析
两处格式化与 UI 稿约定不符：
1. `NotificationCenterFragment.kt` 中日期拼接用 `DateTimeFormatter.ofPattern("EEEE", locale)` —— `EEEE` 是星期全称（星期三），UI 稿要求 `E`（周三）。
2. `NotificationCenterTextClock.java`（自定义 TextClock，`init()` 中设置格式）使用 `setFormat12Hour("h:mm a")`，`a` 与时间之间有空格，且中文 locale 下系统把 `a` 渲染为"上午/下午"，均不符合设计稿"AM/PM 紧贴时间"的样式。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt`、`application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/NotificationCenterTextClock.java`
```diff
--- application/SystemUI/.../notification/ui/NotificationCenterFragment.kt
-        val weekFormatter = DateTimeFormatter.ofPattern("EEEE", locale)
+        val weekFormatter = DateTimeFormatter.ofPattern("E", locale)
         tvNotificationDate.text =
-            "${currentDate.format(dateFormatter)} ${currentDate.format(weekFormatter)}"
+            "${currentDate.format(dateFormatter)}  ${currentDate.format(weekFormatter)}"
--- application/SystemUI/.../statusbar/widget/NotificationCenterTextClock.java
-        setFormat12Hour("h:mm a");
+        setFormat12Hour("h:mma");
         setFormat24Hour("HH:mm");
@@ applyStyledText
-            SpannableString styledText = new SpannableString(source);
+            String text = source.toString().replace("上午", "AM").replace("下午", "PM");
+            SpannableString styledText = new SpannableString(text);
...
+            setGravity((Gravity.START) | Gravity.BOTTOM);
```

## 为什么能修复
`E` 模式输出"周三"式缩写；`h:mma` 去掉时间与 AM/PM 间的空格；在 `applyStyledText` 里把中文 locale 系统渲染的"上午/下午"硬替换为"AM/PM"，保证任何语言环境下时段标记样式统一；`setGravity(START|BOTTOM)` 让时间文本按 UI 稿底部对齐。隐患：`replace("上午","AM")` 是硬编码中文匹配，若系统在其他中文变体（如"早上"）下输出则替换不到，属于面向表象的修补而非根治格式化。

## 复盘与经验
- `DateTimeFormatter`/`SimpleDateFormat` 模式字母长度决定输出形态（E vs EEEE），UI 稿评审要把日期格式精确到 pattern 字符串。
- 文本样式类修改（AM/PM）应优先用 locale 无关的格式或 `DateFormat.getBestDateTimePattern`，硬编码字符串替换是脆弱方案，换语言/ROM 即可能失效。
- 自定义 TextClock 的富文本 span 处理中，`setGravity` 这类视觉细节容易被忽略，需对照 UI 标注逐项核对。
