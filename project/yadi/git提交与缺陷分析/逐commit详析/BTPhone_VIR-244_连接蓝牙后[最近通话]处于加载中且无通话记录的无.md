# VIR-244 · 最近通话/收藏页空数据时无文字提示
- **提交**：`e1196d40` | 2026-08-13 | liujinfeng | BTPhone | bugfix（UI 变更类：提交信息 what/why/how 均为"UI变更"）
- **缺陷库**：未关联缺陷记录（VIR-244 无 defs 数组条目）

## 问题
连接蓝牙后进入[最近通话]（及[收藏]）页，页面处于"加载中/空数据"状态时没有任何文字提示，用户无从知晓当前状态。

## 根因分析
`CallLogFragment` 与 `FavoritesFragment` 的空态渲染代码里，`tvEmptyMessage` 被显式 `setVisibility(GONE)`（原代码注释即写明"根据新需求，隐藏图片和旧的文本"），且文本分别是 `no_call_history`/`no_contacts` 等页面专作文案；布局 `fragment_call_log.xml`/`fragment_favorites.xml` 中该 TextView 默认 `visibility="gone"`、字号 23sp、颜色 `text_default_press`、marginTop 240dp 且未居中。多因素叠加导致空数据时界面完全无提示。搜索无结果占位（`activity_main.xml` 的 `search_no_match`）也只有一张 `no_agree` 图片、没有文字。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogFragment.java`、`fragment/FavoritesFragment.java`、`res/layout/activity_main.xml`、`res/layout/fragment_call_log.xml`、`res/layout/fragment_favorites.xml`、`res/layout/fragment_contacts.xml`、`res/values*/strings.xml`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/fragment/CallLogFragment.java
-            binding.tvEmptyMessage.setText(R.string.no_call_history);
-            binding.tvEmptyMessage.setVisibility(GONE);
+            binding.tvEmptyMessage.setText(R.string.no_content);
+            binding.tvEmptyMessage.setVisibility(VISIBLE);
```
（`FavoritesFragment.java` 同样由 `no_contacts`+GONE 改为 `no_content`+VISIBLE。）
```diff
--- application/BTPhone/src/main/res/layout/fragment_call_log.xml
-            android:layout_marginTop="240dp"
+            android:layout_marginTop="320dp"
             android:text="@string/no_call_history"
-            android:textColor="@color/text_default_press"
-            android:textSize="23sp"
-            android:visibility="gone"/>
+            android:textColor="@color/text_default_disabled"
+            android:textSize="20sp"
+            android:gravity="center"
+            android:visibility="gone" />
```
```diff
--- application/BTPhone/src/main/res/layout/activity_main.xml (search_no_match)
-            <ImageView
+            <RelativeLayout
                 android:id="@+id/search_no_match"
                 ...
-                android:src="@drawable/no_agree"
-                android:visibility="gone"
+                android:visibility="visible"
                 ...>
+                <ImageView ... android:src="@drawable/no_agree" />
+                <TextView ... android:text="@string/no_content"
+                    android:layout_centerHorizontal="true"
+                    android:layout_marginTop="220dp" />
+            </RelativeLayout>
```
（新增三语文案 `no_content`＝"当前无内容"；`fragment_favorites.xml`/`fragment_contacts.xml` 同步调整字号/颜色/居中。）

## 为什么能修复
空态可见性由代码显式置为 VISIBLE 并统一改用通用文案 `no_content`，布局侧同步把文案规格（20sp/disabled 色/居中/下移 320dp）对齐设计稿，空数据页面出现明确提示；搜索无结果占位补上文字。属纯 UI 变更，无逻辑风险；注意各空态文案被统一为一个字符串，页面差异化提示能力随之丧失，是有意的设计取舍。

## 复盘与经验
- 列表类页面必须定义完整的空态（empty state）规范：图标、文案、可见性、样式，且代码控制点与布局默认值要一致，否则"看不见的提示"极易漏测。
- UI 变更单（what/why/how 都是"UI变更"）混在 bugfix 提交流里，复盘时要按实际 diff 定性，不能只看单号类型。
- 多语言新增文案需 values/values-zh/values-en 三处同步，本提交即是标准做法。
