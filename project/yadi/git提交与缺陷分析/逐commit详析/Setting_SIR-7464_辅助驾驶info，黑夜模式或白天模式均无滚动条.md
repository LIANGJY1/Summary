# SIR-7464 · 辅助驾驶info弹窗，黑夜/白天模式均无滚动条
- **提交**：`7f4a9e31` | 2026-09-05 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（需求遗漏类）

## 问题
"驾驶-行车辅助-辅助驾驶"的 info 说明弹窗内容超长，但白天/黑夜模式下都没有滚动条，用户无法感知下方还有内容。

## 根因分析
弹窗布局 `application/Setting/src/main/res/layout/dialog_assist_intro.xml` 中的内容容器此前是 `androidx.core.widget.NestedScrollView` 且显式 `android:scrollbars="none"`——滚动条被主动关闭，内容溢出毫无视觉提示。这属于需求遗漏（UI 式样要求有滚动条，实现时沿用了无滚动条模板）。修复把容器换回普通 `ScrollView` 并打开竖向滚动条样式：`scrollbars="vertical"`、`scrollbarSize="4.5dp"`、`scrollbarStyle="insideOverlay"`、`scrollbarThumbVertical="@drawable/scrollbar_thumb"`（此前 SIR-7246 已沉淀的滑块 drawable，`text_default_disabled` 色圆角条）、`scrollbarTrackVertical` 用系统透明色、`overScrollMode="never"` 关闭越界光效；同时加 `layout_marginBottom="@dimen/dp_48"` 给滚动条留出与底部按钮的间距。另新增了 `drawable/scroll_track.xml`（`#3C4558` 30% 透明度的轨道矢量图）——注意该资源在本布局中实际未被引用（track 用的是透明色），应为昼/夜主题适配预留或冗余产物。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/dialog_assist_intro.xml；application/Setting/src/main/res/drawable/scroll_track.xml（新增）
```diff
--- application/Setting/src/main/res/layout/dialog_assist_intro.xml
-            <androidx.core.widget.NestedScrollView
+            <ScrollView
                 android:id="@+id/ns_detail"
                 android:layout_width="match_parent"
                 android:layout_height="match_parent"
-                android:scrollbars="none">
+                android:layout_marginBottom="@dimen/dp_48"
+                android:scrollbars="vertical"
+                android:scrollbarSize="4.5dp"
+                android:scrollbarStyle="insideOverlay"
+                android:scrollbarThumbVertical="@drawable/scrollbar_thumb"
+                android:scrollbarTrackVertical="@android:color/transparent"
+                android:overScrollMode="never">
```

## 为什么能修复
`scrollbars="vertical"` + 自定义 thumb 让内容溢出时出现 4.5dp 竖向滚动条，明暗两套主题下均走同一 `text_default_disabled` 色，无需分主题适配即可生效。风险很小：NestedScrollView 换成 ScrollView 只在与 CoordinatorLayout/NestedScrollingParent 联动的嵌套滚动场景有差异，本弹窗内是普通 LinearLayout 内容，行为等价。

## 复盘与经验
- `scrollbars="none"` 常被当"去美化"默认写法，但会牺牲"内容可滚动"的可用性暗示；车机固定尺寸弹窗中内容长度随多语言/字号变化，滚动条应保留。
- 项目内已有 `scrollbar_thumb` 这类统一滑块资源（SIR-7246 沉淀），新页面直接复用可保证视觉一致——本例正是这样做的；但同一次提交里新增了未被引用的 `scroll_track.xml`，属于冗余资产，应避免入库。
- 同一单号 SIR-7464 被多个提交（7f4a9e31/1bdc8c30/8a4d8a88）分摊修复不同子问题，说明该需求在提测时遗漏面较广。
