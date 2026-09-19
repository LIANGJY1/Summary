# SIR-8532 · APPList 界面应用图标位置与 UI 稿有差异

- **提交**：`f36046ef` | 2026-09-16 | caohongliang | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
应用列表（AppList）界面所有应用图标的摆放位置与 UI 稿存在偏差（整体边距不对）。

## 根因分析
图标网格的位置由两层参数共同决定：`fragment_app_list.xml` 中 `recyclerView` 的左右 `paddingStart/paddingEnd`（原为 `dimen108dp`/`dimen102dp`），以及 `AppListFragment` 里 `AppListGridSpacingItemDecoration(5, 24dp, 70dp, 48dp, true)` 的网格间距参数（5 列、间距 24、上下 70、水平 48）。两处数值与 UI 标注不一致，导致 5 列网格整体偏移。修复按 UI 稿校准：列表 padding 调为 `dimen118dp`/新增 `dimen119dp`（118px/119px），Decoration 的水平间距 48dp 收窄为 44dp。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListFragment.kt、application/Launcher/src/main/res/layout/fragment_app_list.xml、application/Launcher/src/main/res/values/dimens.xml
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListFragment.kt
             AppListGridSpacingItemDecoration(
-                5, 24.dpToPx(), 70.dpToPx(), 48.dpToPx(), true
+                5, 24.dpToPx(), 70.dpToPx(), 44.dpToPx(), true
             )
--- application/Launcher/src/main/res/layout/fragment_app_list.xml
             android:id="@+id/recyclerView"
-            android:paddingStart="@dimen/dimen108dp"
-            android:paddingEnd="@dimen/dimen102dp"
+            android:paddingStart="@dimen/dimen118dp"
+            android:paddingEnd="@dimen/dimen119dp"
--- application/Launcher/src/main/res/values/dimens.xml
+    <dimen name="dimen119dp">119px</dimen>
+    <dimen name="dimen143dp">143px</dimen>
```

## 为什么能修复
容器 padding 与 ItemDecoration 间距同步按 UI 标注重新取值，网格起点与列间距回到设计值，图标位置差异消除。纯数值修改、无逻辑变化，回归点仅是网格在极端行数下的对齐。注意 `dimens.xml` 中 `dimen119dp` 实际定义为 119px、`dimen120px` 定义为 120dp——"dp 后缀名存 px 值"的命名陷阱在这个仓库里普遍存在，改值时必须核对单位。

## 复盘与经验
- RecyclerView 网格位置 = 容器 padding + ItemDecoration 间距，两处必须按同一份 UI 标注联调，只改一处会出现"整体对但局部错"。
- `dimenXXXdp = XXXpx` 这类命名与单位不符的 dimens 是 UI 偏差的温床，新加条目时先确认目标单位。
- D 级 UI 走查问题修复成本极低，但应推动在开发阶段用 UI 标注工具（如蓝湖/PxD 校准）前置拦截。
