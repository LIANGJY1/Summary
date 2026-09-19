# SIR-6245 · 播放蓝牙歌曲时开始时间前几秒数字左右跳动

- **提交**：`c590d008` | 2026-08-23 | dufan | BTMusic | bugfix（一行 gravity 修复）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
播放蓝牙歌曲时，进度条开始时间（如 "00:07"）处的前几秒数字显示位置左右晃动，看起来在"跳动"。

## 根因分析
缺陷库根因："控件设置内容居中，文言变动，位置改动"。`widget_music_play_control.xml` 中时间控件 `tvProgressTime` 固定宽 81dp 且 `android:gravity="center"` 水平居中——播放初期时间文本从 "00:00" → "00:07" → "00:10" 逐秒变化时字符宽度不同（如 "1" 比其他数字窄），水平居中布局下每秒重算居中位置，文本整体左右平移，视觉上就是数字跳动。修复把 gravity 从 `center` 改为 `center_vertical`，仅保留垂直居中、水平左对齐，文本起点固定，宽度变化只向右扩展，不再晃动。

## 关键代码修改
改动文件：application/BTMusic/src/main/res/layout/widget_music_play_control.xml（+1/-1）
```diff
@@ application/BTMusic/src/main/res/layout/widget_music_play_control.xml @@
             android:id="@+id/tvProgressTime"
             android:layout_width="@dimen/dp_81"
             android:layout_height="match_parent"
-            android:gravity="center"
+            android:gravity="center_vertical"
             android:text="00: 00"
```

## 为什么能修复
左对齐使时间文本锚定在固定起点，秒数变化只改变文本末端，不再引起整体位移；垂直居中保留保证了行高上的对齐。无副作用。

## 复盘与经验
- 逐秒刷新的计时/数值文本用水平居中必然"跳舞"（数字宽度不等，尤其含 1），这类文本应固定左（或右）对齐，或使用等宽数字字体特性（tabular figures）。
- UI 视觉抖动类缺陷先看"文本对齐方式与刷新频率"的组合，一行 gravity 常是答案。
