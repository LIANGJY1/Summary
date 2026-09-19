# 无单号（SIR-XXX）· 场景界面底部间距显示异常
- **提交**：`d311152f` | 2026-09-08 | sgh | Setting | bugfix（UI 优化类，未关联正式单号）
- **缺陷库**：未关联单号（提交 why 记为"需求遗漏"）

## 问题
场景模式界面底部间距不符合设计（padding 挂错层级导致滚动/留白表现异常）。

## 根因分析
`fragment_scene_mode.xml` 里 `SmartNestedScrollView`（match_parent 高）直接携带 `paddingHorizontal=64dp` 和 `paddingBottom=64dp`。padding 打在滚动容器上时，底部留白属于"可视窗口的内边距"而非"内容的末尾间距"——滚动到底时内容不会进入 padding 区域之外，视觉上底部空白/裁切表现与设计稿不一致。修复就是把这对 padding 从滚动容器下移到内部的内容 LinearLayout 上，让间距跟随内容滚动，属于典型的"padding 挂错层级"布局问题。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/fragment_scene_mode.xml
```diff
--- application/Setting/src/main/res/layout/fragment_scene_mode.xml
@@ -9,13 +9,13 @@
     <com.yadea.setting.ui.widget.SmartNestedScrollView
         android:id="@+id/vc_nestedScrollView"
         android:layout_width="match_parent"
-        android:layout_height="match_parent"
-        android:paddingHorizontal="@dimen/dp_64"
-        android:paddingBottom="@dimen/dp_64">
+        android:layout_height="match_parent">
 
         <LinearLayout
             android:layout_width="match_parent"
             android:layout_height="wrap_content"
+            android:paddingHorizontal="@dimen/dp_64"
+            android:paddingBottom="@dimen/dp_64"
             android:orientation="vertical">
```

## 为什么能修复
padding 移到内容根布局后，64dp 间距成为内容的一部分：滚动全程内容与边缘的左右间距不变，滚到底部时最后一段内容与屏幕底缘之间正好是 64dp，间距表现稳定符合设计。纯布局属性移动，无逻辑风险；唯一提醒是若页面依赖 clipToPadding 之类行为要重新确认，此处未涉及。

## 复盘经验
- 滚动容器的 padding 与内容布局的 padding 视觉效果不同：需要"随内容滚动的留白"必须打在子布局上，需要"固定可视窗口留白"才打在滚动容器上。
- 同一页面层级里 padding 的归属层级是 UI 走查高频差异点，出问题时先比对设计稿的间距语义（内容间距 vs 窗口边距）。
- 此类小修建议与相关页面走查单关联，方便后续用设计稿 diff 工具批量核对同类页面。
