# YD-392939 · 蓝牙电话检索页上下间隙太大与 UI 不一致

- **提交**：`a5e2f72f` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392939，defs 无条目）

## 问题
蓝牙电话检索（搜索匹配）区域上下留白过大，与 UI 设计稿不一致。

## 根因分析
`activity_main.xml` 中检索列表容器直接写死了 `paddingTop="50dp"` / `paddingBottom="50dp"`，远超设计稿间距；背景色同时写死 `#F0F3FA`，未引用主题色（顺带与昼夜模式适配问题相关）。`bg_search_match_item.xml` 的背景与描边色也写死了同样的硬编码色值，与布局里的硬编码值重复维护。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/drawable/bg_search_match_item.xml；application/BTPhone/src/main/res/layout/activity_main.xml

```diff
--- application/BTPhone/src/main/res/layout/activity_main.xml
                 android:layout_width="match_parent"
                 android:layout_height="0dp"
                 android:paddingLeft="30dp"
-                android:paddingTop="50dp"
+                android:paddingTop="10dp"
                 android:paddingRight="20dp"
-                android:paddingBottom="50dp"
-                android:background="#F0F3FA"
+                android:paddingBottom="10dp"
+                android:background="@color/bg_application"
```

```diff
--- application/BTPhone/src/main/res/drawable/bg_search_match_item.xml
-            <solid android:color="#F0F3FA" />
+            <solid android:color="@color/bg_application" />
@@
-                android:color="#1A20232B" />
+                android:color="@color/divider_default" />
```

## 为什么能修复
把检索容器的上下 padding 从 50dp 收敛到 10dp，间距与设计稿对齐；背景/描边由硬编码色值改为主题色引用（`bg_application`、`divider_default`），消除多处的重复色值维护点。风险极小，属纯样式对齐；但写死的 `10dp` 是否在所有分辨率/昼夜模式下都正确仍依赖设计验收。

## 复盘与经验
- **布局 padding 写死大数值往往是对设计稿的"目测还原"**，应集中到 `dimen` 资源并命名（如 `search_area_padding`），避免逐处硬编码后各处不一致。
- **颜色硬编码与主题脱节**：`#F0F3FA` 一类写死色值在黑夜模式适配时会成批返工，从一开始就引用语义化主题色（`bg_application`）成本最低。
- **UI 单（YD 系列）虽无缺陷库根因记录，但改动模式高度可复制**：检索"硬编码色值/尺寸→主题资源引用"是本批 BTPhone UI 修复的统一路径。
