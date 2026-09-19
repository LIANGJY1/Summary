# SIR-6748 · 通知中心时间 00:00 显示不全
- **提交**：`8de1f33d` | 2026-08-31 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车机时间走到 00:00（以及其它较宽时刻）时，通知中心大字号数字时钟显示错误/被截断，其余时刻正常。

## 根因分析
通知中心的数字时钟控件 `com.android.systemui.statusbar.widget.NotificationCenterTextClock`（id `tv_notification_Time`）在 `notification_center.xml` 中宽度写死为 `@dimen/dp_307`，而字号高达 `102sp` 且带 `letterSpacing=0.05`。"00:00" 是数字时钟里最宽的字符串组合（四个 0），按该字号加字距渲染后实际宽度超过 307dp，固定宽度导致内容被裁切，显示缺字/错位；其它较窄时刻（如 "1:23"）恰好放得下，所以呈偶发形态。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/notification_center.xml（另含 NavBarFragment.java 的背景逻辑微调）
```diff
--- a/application/SystemUI/src/main/res/layout/notification_center.xml
@@
     <com.android.systemui.statusbar.widget.NotificationCenterTextClock
         android:id="@+id/tv_notification_Time"
-        android:layout_width="@dimen/dp_307"
+        android:layout_width="wrap_content"
         android:layout_height="@dimen/dp_133"
...
         android:lineSpacingExtra="-3sp"
         android:letterSpacing="0.05"
+        android:singleLine="true"
         android:textColor="#20232B"
         android:textSize="102sp" />
```
```diff
--- a/.../navbar/ui/NavBarFragment.java（随提交顺带的背景逻辑修正）
-            setBackGround(displayState != 0 && displayState != 2 && displayState != 3);
+            if (displayState != 0 && displayState != 2 && displayState != 3) {
+                setBackGround(true);
+            } else {
+                applyDockBackground(currentPosition == 2, false);
+            }
```

## 为什么能修复
`wrap_content` 让时钟宽度由文本实际渲染宽度决定，"00:00" 最宽时刻完整展示；`singleLine="true"` 保证时钟永远单行，避免 wrap_content 与换行组合产生的布局抖动。固定宽度假设"所有时刻等宽"在比例字体+字距下不成立，这是根因。顺带的 NavBarFragment 改动与本缺陷无直接关系（是对前一个提交背景切换逻辑的收尾修正），属同车提交打包。隐患：wrap_content 后时钟在父容器中的对齐位置会随内容宽度微移，需确认 UI 验收可接受。

## 复盘与经验
- 大字号文本控件禁止拍脑袋定宽，尤其数字时钟要按"最宽字符串"（00:00 / 23:59）验证；能自适应就用 wrap_content + singleLine。
- letterSpacing、负 lineSpacingExtra 等排版参数会放大文本实际宽度，估算宽度时必须计入。
- 一个提交里混入无关小修（背景逻辑）会增加回溯成本，理想情况应拆分提交。
