# SIR-7648 · 热点密码行右侧缺少展开箭头，与 UI 不一致
- **提交**：`123ac4a9` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **备注**：本提交为原始修复；`bf4513af`（同日）是其 cherry-pick 到另一分支的完全相同改动，机制一致，不重复剖析。

## 问题
热点设置弹窗中"热点密码"一行右侧缺少可展开的箭头图标，与 UI 设计稿不一致。

## 根因分析
`item_hotspot_header.xml` 里的 `ll_hotspot_password` 行内，密码文本 `tv_hotspot_password` 用 `app:drawableEndCompat="@drawable/ic_expand_arrow"` 把箭头画在文本尾部（并且文本是可变长度）。这种做法让箭头紧贴密码文本而不是固定靠右，且布局容器未设置 `android:gravity="center_vertical"`，内容在 76dp 高的行内不垂直居中；当密码为掩码/长短变化时视觉位置也与设计稿不符。属于布局绘制方式选错（文本 drawableEnd 而非独立右对齐的 ImageView），是纯 UI 绘制错误。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/item_hotspot_header.xml（纯新增 8 行）
```diff
--- application/Setting/src/main/res/layout/item_hotspot_header.xml
@@ -50,6 +50,7 @@
         android:id="@+id/ll_hotspot_password"
         android:layout_width="match_parent"
         android:layout_height="@dimen/dp_76"
+        android:gravity="center_vertical"
         android:layout_marginTop="@dimen/dp_10"
         android:orientation="horizontal">
@@ -76,6 +77,13 @@
             android:textSize="@dimen/sp_22"
             app:drawableEndCompat="@drawable/ic_expand_arrow" />
 
+        <ImageView
+            android:id="@+id/iv_arrow"
+            android:layout_width="@dimen/dp_24"
+            android:layout_height="@dimen/dp_24"
+            android:layout_marginStart="@dimen/dp_6"
+            android:src="@drawable/ic_expand_arrow" />
+
     </LinearLayout>
```

## 为什么能修复
容器加了 `gravity="center_vertical"` 后整行内容垂直居中；新增固定 24dp 的 `iv_arrow` ImageView 后，箭头脱离密码文本、由 LinearLayout 水平排布自然靠右，尺寸位置均按设计稿固定，不再随文本长度漂移。隐患：原 TextView 上的 `drawableEndCompat` 若未同步移除，可能出现双箭头（本 diff 未删除该属性，但修复后版本中未见双箭头反馈；建议复核）。

## 复盘经验
- 行尾装饰性图标（箭头/开关）应使用独立 View 固定尺寸，而不是挂在可变长文本的 drawableEnd 上，否则随文案变化错位。
- 固定高度（如 76dp）的列表行要显式设置垂直 gravity 或用约束布局居中，避免内容顶置。
- 同一单号先修一处再 cherry-pick（`bf4513af`）说明多分支并行时 UI 修复要提前同步，避免分支差异引入回归。
