# 无单号 · Setting 黑夜模式图标替换

- **提交**：`ab09f686` | 2026-06-29 | shengguanghui | Setting | feature
- **关联单**：无

## 需求/目标
Setting 模块黑夜模式下图标/颜色错误的批量修正：自绘控件 `LinearProgressView` 的硬编码色值改为语义色资源，30+ 个 drawable XML 的 `fillColor` 从写死色值替换为 `@color/xxx` 语义色引用。

## 实现结构
- `ui/widget/LinearProgressView.kt`：删除 `circlePaint/trianglePaint/progressPaint/bgPaint` 四个不再使用的画笔及约 60 行注释掉的旧绘制代码；虚线与滑块颜色从 `Color.parseColor("#CCCCCC")`、`#20232B` 改为 `ResourcesCompat.getColor(resources, R.color.text_default_disabled/text_default_default)`。
- `res/drawable/heat_hand_*、heat_seat_*、ic_sound_*、icon_tab_*` 等 30 余个 selector/vector：`fillColor` 由 `#FF......`、`#......` 字面量批量替换为 `icon_default_default`、`text_default_default` 等语义色（这些色在 values 与 values-night 各有一份定义，夜间自动切换）。

数据流：uiMode 切换 → 系统资源重载 → 语义色在 night 配置下取夜间值 → 自绘 Paint 与 vector fillColor 同步变色。

## 关键代码
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/widget/LinearProgressView.kt
-    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
-    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG)
-    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
-    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
...
-        paint.color = Color.parseColor("#CCCCCC")
+        paint.color =  ResourcesCompat.getColor(resources, R.color.text_default_disabled, null)
...
-        thumbPaint.color =  Color.parseColor("#20232B")
+        thumbPaint.color =  ResourcesCompat.getColor(resources, R.color.text_default_default, null)
```
```diff
# application/Setting/src/main/res/drawable/heat_seat_close.xml（同类改动覆盖 30+ 个 drawable）
-      android:fillColor="#FFFFFF"
+      android:fillColor="@color/icon_default_default"
```

实现讲解：自绘 View 无法享受资源系统自动换肤，必须把 Paint.color 的取值改为运行时读语义色资源（`ResourcesCompat.getColor`），这是自定义 View 适配昼夜模式的标准做法；同时把死代码画笔一并清掉，降低后续维护噪音。

## 复盘与要点
- 可复用手法："自绘控件颜色 = 语义色资源"应作为团队规约，禁止 `Color.parseColor` 出现在控件初始化里。
- 大量小 drawable 的色值替换适合脚本化（正则替换 fillColor），人工逐个改容易漏（本批次后续 d6c6dd54"漏提图标"即是补漏提交）。
- 遗留风险：`ResourcesCompat.getColor` 在 View 构造期读取的是当时的 uiMode，若页面不重建则不刷新，需配合 `onConfigurationChanged` 重设 Paint 颜色。
