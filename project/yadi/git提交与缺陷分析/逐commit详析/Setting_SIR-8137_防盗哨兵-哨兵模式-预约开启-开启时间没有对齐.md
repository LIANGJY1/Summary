# SIR-8137 · 防盗哨兵预约开启时间没有对齐

- **提交**：`6dd4cdab` | 2026-09-11 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
防盗哨兵-哨兵模式-预约开启界面中，"开启时间"标题与其时间值显示不对齐：当预约时间为空时占位 TextView 仍占据空间，且标题文字基线偏高，视觉上没有对齐。

## 根因分析
问题出在 `SafetyMonitorViewModel` 拼接时间文本与布局两端。`SafetyMonitorViewModel` 中拼接预约时间的方法把各段数字 `append` 到 `content` 后直接 `tv.text = content.toString()`，从不设置 visibility；当 `content` 为空（未设置预约时间）时，`tv_time` 对应的值 TextView 仍以 VISIBLE 状态占位，把布局撑出空白，导致标题与值行错位。布局侧 `fragment_safety_monitor.xml` 中 `tv_time`（style 为 `setting_title_black_style`）没有设置 `gravity` 与 `includeFontPadding`，Android TextView 默认字体上下 padding 使文字在行内偏上，与右侧数值行视觉上不对齐。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SafetyMonitorViewModel.kt`、`application/Setting/src/main/res/layout/fragment_safety_monitor.xml`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SafetyMonitorViewModel.kt
         tv.text = content.toString()
+        tv.visibility = if (content.isEmpty()) View.GONE else View.VISIBLE
```
```diff
// application/Setting/src/main/res/layout/fragment_safety_monitor.xml
                     <TextView
                         android:id="@+id/tv_time"
                         style="@style/setting_title_black_style"
+                        android:gravity="center_vertical"
+                        android:includeFontPadding="false"
                         android:text="@string/open_time" />
```

## 为什么能修复
空内容时把值 TextView 置为 `GONE`，让它不参与布局占位，消除空白错位；标题加 `gravity="center_vertical"` 并关闭字体 padding，使文字在行框内垂直居中，与数值行对齐。副作用很小：`GONE` 后该行不再保留占位高度，若产品期望"空值仍占位"需另行确认，但就本缺陷而言 GONE 正是期望行为。

## 复盘与经验
- UI"对齐"类缺陷常有两个叠加来源：数据为空时的占位问题 + TextView 默认 fontPadding/重力问题，修复要双管齐下。
- 在 ViewModel/数据绑定层顺手维护"内容为空即隐藏"的不变式，比在每个布局里调 margin 更可靠。
- `includeFontPadding="false"` + `gravity="center_vertical"` 是车机多行文本对齐的常用组合。
