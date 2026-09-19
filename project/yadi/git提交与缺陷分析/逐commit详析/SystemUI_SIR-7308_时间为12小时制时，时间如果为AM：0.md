# SIR-7308 · 12 小时制下 0:00-1:00 显示为 12:00-1:00

- **提交**：`66606118` | 2026-09-03 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B（提交标注 D）· 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
12 小时制时，凌晨 0:00-1:00 区间状态栏右上角时间显示为 12:00-1:00，与产品要求的 0 点起算显示不符。

## 根因分析
时间格式化用的是 `DateFormat` 的 12 小时制符号 `h`（1-12 起算，即标准 12 小时制：0 点显示 12）。而本项目自定义的 12 小时制规则是"0:00-11:00"（[how] 原话），两套规则差 12 小时取模，导致午夜时段显示"12:xx"而期望"0:xx"（缺陷库根因即"标准12小时制式显示的时间是1:00-12:00"）。受影响点两处：`NotificationCenterTextClock.init()` 里的 `setFormat12Hour("h:mma")`，以及状态栏布局 `fragment_status_bar.xml` 的 `android:format12Hour="h:mm"`。

## 关键代码修改
改动文件：NotificationCenterTextClock.java、layout/fragment_status_bar.xml（各 1 行）
```diff
// application/SystemUI/src/main/java/com/android/systemui/statusbar/widget/NotificationCenterTextClock.java
     private void init() {
-        setFormat12Hour("h:mma");
+        setFormat12Hour("K:mma");
         setFormat24Hour("HH:mm");
     }
```
```diff
// application/SystemUI/src/main/res/layout/fragment_status_bar.xml
-        android:format12Hour="h:mm"
+        android:format12Hour="K:mm"
```

## 为什么能修复
`K` 是 DateFormat 中"0-11"起算的小时符号（hour 0-11），恰好把午夜 0 点显示为 0 而非 12，无需任何换算代码就把显示规则切换为产品定义的 0:00-11:00；24 小时制 `HH:mm` 不变。风险：`K` 属于较少用的符号，需要确认测试覆盖 0:00-1:00 与 11:00-12:00 两个边界；另外全应用还有别处以 `h` 显示时间（如 BTPhone 通话记录），各界面规则是否统一需专项核对。

## 复盘与经验
- 产品说"12 小时制"时必须确认 0 点的表达：标准 `h`（12/1-11）还是 0 起算 `K`（0-11），需求歧义就是这个 bug 的源头（[why]"格式化规则未明确"在同类单 1452dbb6 也出现）。
- `TextClock` 的 format12Hour 常在代码与布局两处定义，改动要全局 grep `format12Hour|h:mma`，漏一处就是"状态栏和通知中心时间不一样"。
- 时间显示类缺陷的测试用例必须含午夜边界与正午边界。
