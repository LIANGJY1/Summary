# SIR-6388 · 个人信息保护政策弹窗布局与 UI 式样不一致

- **提交**：`2a9230eb` | 2026-08-25 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
个人信息保护政策弹窗页面的内容区高度与底部渐变背景高度与 UI 设计稿不符，视觉上与式样不一致。

## 根因分析
问题出在 `application/Setting/src/main/res/layout/activity_web.xml`：承载政策正文的 `ScrollView`（`@+id/scroll_view`）高度写死为 `337dp` 且带 `marginBottom=42dp`，而底部渐变遮罩 `View` 高度为 `@dimen/dp_100`。三个尺寸叠加后，滚动区域偏矮、渐变背景过高，内容可视范围和底部过渡带都与 UI 标注不一致。这类"还原度"问题通常是初版按临时标注实现、UI 定稿后未同步更新布局参数所致。注：commit message 写的是"没有加渐变背景/加上渐变背景图"，但 diff 实际是修改内容高度与渐变背景高度，与缺陷库 `sol`（修改内容高度和渐变背景高度）一致，以 diff 为准。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/activity_web.xml`

```diff
--- a/application/Setting/src/main/res/layout/activity_web.xml
@@ -23,20 +23,19 @@
         <ScrollView
             android:id="@+id/scroll_view"
             android:layout_width="@dimen/dp_840"
-            android:layout_height="337dp"
+            android:layout_height="375dp"
             android:layout_below="@id/tv_title"
             android:layout_marginTop="@dimen/dp_24"
             android:layout_marginStart="@dimen/dp_42"
             android:layout_marginEnd="@dimen/dp_12"
             android:scrollbarSize="4.5dp"
-            android:layout_marginBottom="@dimen/dp_42"
             android:scrollbarStyle="insideOverlay"
             android:scrollbarThumbVertical="@drawable/scrollbar_thumb"
             android:scrollbars="vertical" />
 
         <View
             android:layout_width="match_parent"
-            android:layout_height="@dimen/dp_100"
+            android:layout_height="@dimen/dp_48"
             android:layout_alignParentBottom="true"
             android:background="@drawable/bg_bottom_linear_gradient" />
```

## 为什么能修复
`ScrollView` 高度 337dp→375dp 并去掉底部 42dp 的 marginBottom，把内容可视区撑到 UI 标注高度；底部渐变 View 从 100dp 收窄到 48dp，遮罩过渡带比例回到设计稿。三处参数联动调整后弹窗整体视觉与 UI 式样一致。风险极低——纯布局尺寸调整，无逻辑改动；隐患是 `375dp` 仍是硬编码字面量而非 dimen 资源，多分辨率适配时不如引用 dimen 规范。

## 复盘与经验
- UI 还原类缺陷的修复要"量齐"：一次对齐所有相关联的尺寸（内容高度、margin、遮罩高度），只改一处会顾此失彼。
- 布局尺寸尽量统一走 `dimen` 资源而非裸 `dp` 字面量（diff 中 `337dp`/`375dp` 就是裸值），方便评审时与 UI 标注对照、也利于主题/多屏适配。
- commit message 的 why/how 与实际 diff 不符时（本例写"加渐变背景"，实际改高度），会给后续追溯制造噪音——写提交信息应以实际改动为准。
