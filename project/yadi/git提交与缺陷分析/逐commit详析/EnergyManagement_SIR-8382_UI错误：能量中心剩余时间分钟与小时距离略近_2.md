# SIR-8382 · 能量中心剩余时间"4h 50min"中小时与分钟间距不对

- **提交**：`7fb557e5` | 2026-09-17 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心剩余时间（如"4h 50min"）中，小时与分钟两部分之间的间距与 UI 稿不符。注意：缺陷标题写"距离略近"、元数据 sol 写"增加空格间距"，但代码注释与 diff 实际方向相反——是把偏大的空格**收窄**（以 diff 实际为准，疑为标题"略近/略远"笔误）。

## 根因分析
`MainActivity` 拼接剩余时间的富文本时，数值用大字号（30sp）、单位 h/min 用 `AbsoluteSizeSpan` 缩到 20sp，但两者之间的**空格没有任何 span**，按整段基础字号 30sp 渲染，空格宽度约为数值字号的 1/3 以上，视觉上 h 与分钟被撑得过开。旧实现还有一处脆弱点：span 区间用 `text.length() - 3`、`hoursStr.length()` 这类位置推算，字符串格式一变就错位。修复引入 `REMAINING_TIME_GAP_TEXT_SIZE_SP = 10`（注释说明 10sp 约为 30sp 空格宽度的 1/3），用 `StringBuilder` + 单一 `cursor` 依次对 h、间隔空格、min 精确打 span。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java、application/EnergyManagement/src/main/res/layout/activity_main.xml、application/EnergyManagement/src/main/res/layout/activity_mileage_management.xml、drawable/main_bg_theme.xml、drawable-night/main_bg_theme.xml（另新增 main_bg_white3.png / main_bg_night3.png，二进制图片资源）
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
+    private static final int REMAINING_TIME_GAP_TEXT_SIZE_SP = 10;
@@ 富文本拼接（cursor 推进式打 span）
         if (hours > 0) {
             cursor += String.valueOf(hours).length();
             // 单位 h 使用小字号
             spannable.setSpan(new AbsoluteSizeSpan(unitSizeSp, true), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
             cursor += 1;
             if (showMinutes) {
                 // 间隔空格单独使用更小字号，避免按数值字号(30sp)渲染导致 h 与分钟数值间距过大
                 spannable.setSpan(new AbsoluteSizeSpan(gapSizeSp, true), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                 cursor += 1;
             }
         }
--- application/EnergyManagement/src/main/res/layout/activity_main.xml（mileage 界面同步）
     android:id="@+id/root_layout"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
+    android:paddingTop="10dp"
```

## 为什么能修复
间隔空格从 30sp 降到 10sp 后，h 与分钟数值的视觉间距按比例收窄到设计值；`cursor` 推进式打 span 消除了 `length()-3` 式脆弱推算，并统一处理了"整小时（只有 h）/不足一小时（只有 min）/混合"三种格式（`showMinutes = hours == 0 || minutes > 0` 保证整点也显示"0min"）。同提交还给两个界面 root 加了 10dp `paddingTop` 并更换背景图（white3/night3），属同一轮 UI 校准的顺带改动，与标题单点问题无关，归档时注意区分。

## 复盘与经验
- `SpannableString` 富文本里，"没打 span 的字符按基础字号渲染"是最容易漏的细节——空格、标点都要纳入字号规划。
- span 区间用游标推进而不是倒推 `length()-N`，格式扩展时不会错位；混合字号拼接一律建议封装成小工具函数。
- 一个 fix 提交混入背景图更换 + 根布局 padding 变更，会让 UI 走查归因困难，尽量按视觉问题拆单提交。
