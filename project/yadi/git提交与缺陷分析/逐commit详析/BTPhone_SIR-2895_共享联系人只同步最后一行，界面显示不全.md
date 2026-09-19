# SIR-2895 · 搜索结果列表显示区域错误（ConstraintLayout 中 RecyclerView 高度误用 match_parent）之二

- **提交**：`133b0726` | 2026-07-21 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
同步联系人后进行号码搜索，搜索结果列表显示区域错误、界面显示不全（表现为只露出最后一行）。与 `bc62d8bc` 同单：前者修状态机，本提交修布局。

## 根因分析
`activity_main.xml` 中搜索结果列表 `recyclerViewMatches` 位于 ConstraintLayout 内，却写了 `android:layout_height="match_parent"`，并用 `layout_marginTop/Bottom=50dp` 留白。ConstraintLayout 子 View 的高度语义与线性布局不同：`match_parent` 不受约束链控制，实际渲染高度按父容器尺寸再叠加 margin 计算，导致列表越出 `tabLayout` 以下、父容器以上的预期区域，内容区域被压缩/错位，视觉上"只显示最后一行"。同时 `MainActivity.onSearch()` 里"空关键字直接 return"的逻辑被注释掉，空串也走搜索流程，结果列表与无结果提示（`searchNoMatch`）的显隐不受控。本提交的 [why]"搜索结果列表高度问题"与缺陷库 rc"搜索结果列表显示区域错误"一致。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/activity_main.xml；application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
```diff
--- a/application/BTPhone/src/main/res/layout/activity_main.xml
             <androidx.recyclerview.widget.RecyclerView
                 android:id="@+id/recyclerViewMatches"
                 android:layout_width="match_parent"
-                android:layout_height="match_parent"
+                android:layout_height="0dp"
                 android:paddingLeft="30dp"
-                android:layout_marginTop="50dp"
+                android:paddingTop="50dp"
                 android:paddingRight="20dp"
-                android:layout_marginBottom="50dp"
+                android:paddingBottom="50dp"
                 android:background="#F0F3FA"
-                android:visibility="gone"
+                android:visibility="visible"
                 app:layout_constraintBottom_toBottomOf="parent"
                 app:layout_constraintStart_toStartOf="parent"
                 app:layout_constraintTop_toBottomOf="@id/tabLayout" />
--- a/application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
     public void onSearch(int currentTab, String numberStr) {
-        /*if(TextUtils.isEmpty(numberStr)){
+        if(TextUtils.isEmpty(numberStr)){
             LogUtils.d(TAG, "onSearch: numberStr is empty");
+            LogUtils.d(TAG, "recyclerViewMatches hide");
+            binding.recyclerViewMatches.setVisibility(View.GONE);
+            binding.searchNoMatch.setVisibility(View.GONE);
             return;
-        }*/
+        }
```

## 为什么能修复
高度改为 `0dp`（match_constraints）后，列表尺寸完全由 `constraintTop_toBottomOf=@id/tabLayout` 与 `constraintBottom_toBottomOf=parent` 的约束链决定，50dp 间距从 margin 改为 padding 保留视觉留白且不再参与越界计算，显示区域与预期一致，"只显示最后一行"消失；空关键字时主动隐藏结果列表与无结果提示，避免残留上一次的列表内容。副作用：列表默认 `visibility` 从 gone 改为 visible，依赖 `onSearch` 空串分支在其不该出现时隐藏——两个改动是配套的，单独回退任何一个都会露出列表残留。

## 复盘与经验
- ConstraintLayout 子 View 撑满剩余空间用 `0dp + 上下约束`，`match_parent` 在此不受约束链管辖，是"列表区域错位"的常见来源。
- 用 margin 定位的列表越界时，把间距改成 padding 是保持视觉不变、修正几何的常用等价变换。
- 同一单号出现多个提交时（本单有 `bc62d8bc` 状态机 + 本提交布局），复盘要合并看：用户报的"显示不全"往往由多层问题叠加。
