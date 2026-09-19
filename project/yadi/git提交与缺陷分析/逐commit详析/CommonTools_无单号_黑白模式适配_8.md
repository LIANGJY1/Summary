# 无单号 · CommonTools 黑白模式适配（背景 shape 语义色替换）

- **提交**：`98f59cee` | 2026-06-30 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
公共库两个高频背景 drawable 去硬编码色：面板顶部圆角背景 `bg_main_round_top.xml` 由"selector 包 shape + 写死 #F0F3FA"简化为纯 shape 引用 `@color/bg_application`；顶部拖拽条 `bg_view_top_line.xml` 由写死 `#2B2E334D` 改引用 `@color/icon_default_disabled`。

## 实现结构
仅 2 个 XML 文件（+5/-9 行），无代码改动。`bg_application`、`icon_default_disabled` 均为 CommonUI 语义色，values/values-night 双定义，uiMode 切换自动生效。

## 关键代码
```diff
# component/CommonTools/src/main/res/drawable/bg_main_round_top.xml
-<selector xmlns:android="http://schemas.android.com/apk/res/android">
-    <item>
-        <shape>
-            <corners android:topLeftRadius="@dimen/dp_36" android:topRightRadius="@dimen/dp_36" />
-            <solid android:color="#F0F3FA" />
-        </shape>
-    </item>
-</selector>
+<shape xmlns:android="http://schemas.android.com/apk/res/android">
+    <corners android:topLeftRadius="@dimen/dp_36" android:topRightRadius="@dimen/dp_36" />
+    <solid android:color="@color/bg_application" />
+</shape>
```
```diff
# component/CommonTools/src/main/res/drawable/bg_view_top_line.xml
-    <solid android:color="#2B2E334D" />
+    <solid android:color="@color/icon_default_disabled" />
```

实现讲解：单状态背景套一层 `<selector>` 是无意义的包装，简化后可读性更好；这两个 drawable 被 BTMusic/Launcher 的 `onConfigurationChanged` 手动重设（见 b58a35fc），语义色化后重设时才能取到正确的夜间色。

## 复盘与要点
- 可复用手法：公共背景 drawable 只要是"单状态 + 圆角/纯色"，一律 shape + 语义色，selector 仅留给按压/选中等多状态场景。
- 这类 1-2 文件的微提交当天有 8 个，说明黑白模式适配是"引用面扫描 → 分片提交"的推进方式；缺点是标题信息量为零，需靠提交时间窗与文件路径推断归属。
