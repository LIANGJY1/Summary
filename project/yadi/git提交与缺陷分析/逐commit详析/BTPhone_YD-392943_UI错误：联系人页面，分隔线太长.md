# YD-392943 · 联系人页分隔线太长与右侧字母索引重叠

- **提交**：`5c8c5e2d` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392943，defs 无条目）

## 问题
蓝牙电话联系人列表的分隔线横贯整行，右端压到悬浮的 A-Z 字母索引条上，与 UI 不一致。

## 根因分析
`item_contacts.xml` 中的分隔线 View 宽度为 `match_parent`，左右 margin 各 20dp。联系人页右侧悬浮字母索引条占用了约右缘 40dp 区域，分隔线右边距只留了 20dp，不足以让出索引条宽度，导致线与字母重叠。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/item_contacts.xml

```diff
--- application/BTPhone/src/main/res/layout/item_contacts.xml
         android:layout_width="match_parent"
         android:layout_height="@dimen/dp_1"
         android:layout_marginLeft="@dimen/dp_20"
-        android:layout_marginRight="@dimen/dp_20"
+        android:layout_marginRight="@dimen/dp_40"
         android:layout_marginTop="8dp"
         android:layout_marginBottom="8dp"
         android:background="@color/divider_default"/>
```

## 为什么能修复
右边距从 20dp 加大到 40dp，分隔线右端收在字母索引条左侧，视觉上不再重叠。改动一行、无逻辑风险；但 40dp 仍是"目测对齐"的魔法值，若索引条宽度或位置调整需同步改这里。

## 复盘与经验
- **悬浮控件（字母索引/侧边栏）会侵占列表绘制区**：列表内元素的 `match_parent` + margin 必须"扣除"悬浮层宽度，否则必然出现压叠；理想做法是让 RecyclerView 本身避让（右 padding），而不是每个 item 各自留边。
- **margin 魔法值应语义化**：`dp_40` 这类通用尺寸常量看不出与字母索引条的关联，定义 `dimen/list_divider_end_with_index` 一类命名可避免后续改动失联。
