# SIR-7349 · 通话记录星期文字显示错误（"星期一"）

- **提交**：`1452dbb6` | 2026-09-03 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C（提交标注 D）· 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
通话记录列表中本周记录的星期文字显示错误/不符 UI：显示为"星期一"式全文，UI 要求"周 x"式短文案；时间格式整体与设计稿三种样式对不上。

## 根因分析
`StringUtil.friendlyTime2` 的时间展示规则与 UI 定义的三种格式不符（[why]"格式化规则未明确"）。旧实现：当天用 `FORMATTYPE5 = "HH:mm"` 24 小时制（UI 要求 `0:00 am` 式 12 小时制）；"昨天"单独一档文案（UI 规则里没有）；2-6 天前用 `dateTime.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)` 拿英文名再 switch 成 `R.string.Monday`（值为"星期一"），且天数差 `ChronoUnit.DAYS.between` 判定周窗与自然周（周一为一周之首）不一致——跨周的 2 天前记录会显示"周几"，而本周内 1 天前却显示"昨天"，规则全乱；一周外 `FORMATTYPE16 = "yyyy/MM/dd"` 也与稿（带空格）不同。另有一个隐藏 bug：`resetDateFormat()` 重建格式化器时漏了 `dateFormatter16`，语言切换后日期格式不刷新。

## 关键代码修改
改动文件：StringUtil.java（-85/+56 主体）、values(-zh)/strings.xml、InCallServiceImpl.java（仅日志）
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/telecom/utils/StringUtil.java
-    private static final String FORMATTYPE5 = "HH:mm";
+    private static final String FORMATTYPE5 = "h:mm a";      // 当天：7:04 PM 式
-    private static final String FORMATTYPE16 = "yyyy/MM/dd";
+    private static final String FORMATTYPE16 = "yyyy / MM / dd";  // 本周外：0000 / 00 / 00 式
（timeFormatter5 改用 Locale.ENGLISH，保证 am/pm 小写英文）
+        if (isToday) {
+            return timeFormatter5.format(dateTime);
+        }
+        if (isInCurrentWeek) {   // 本周内非当天 → R.string.Monday..Sunday
+            switch (dateTime.getDayOfWeek()) { case MONDAY: return ...getString(R.string.Monday); ... }
+        }
+        return dateFormatter16.format(dateTime);   // 本周外及未来 → 完整日期
+
+    static boolean isInCurrentWeek(LocalDate targetDay, LocalDate today) {
+        // 按周一作为每周第一天，且当天由时间格式分支单独处理
+        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1L);
+        return !targetDay.isBefore(startOfWeek) && targetDay.isBefore(today);
+    }
```
```diff
// application/BTPhone/src/main/res/values-zh/strings.xml
-    <string name="Monday">星期一</string>（…Sunday 同）
+    <string name="Monday">周一</string>（…Sunday 同）
```

## 为什么能修复
按 UI 明确的三档规则重写判定：`isToday` → 12 小时制 `h:mm a`；`isInCurrentWeek`（自然周、周一为首、排除当天与未来）→ "周 x"短文案；其余 → `yyyy / MM / dd`。"昨天"档删除、天数差判定改为自然周判定，跨周边界不再错档；strings 改"周一"对齐设计稿；`resetDateFormat()` 补建 `dateFormatter16` 顺带修掉语言切换不生效的隐藏 bug。隐患：12 小时制与 SIR-7308 的 `K` 规则未统一（此处 `h` 0 点显示 12），两界面规则可能仍互相矛盾。

## 复盘与经验
- "规则未明确"就动手实现是这类缺陷的根源；时间展示类需求开发前必须书面确认（当天/本周/更早三档+边界+语言形态）。
- 手写 switch 映射星期既冗长又易错，可用 `getDisplayName(TextStyle.NARROW...)` 或直接改文案资源；本例虽保留 switch 但至少把周窗判定修正为自然周语义。
- 同应用内 12 小时制规则要全局统一（对照 66606118 的 `K` vs 此处 `h`），否则不同界面凌晨显示互相打架。
