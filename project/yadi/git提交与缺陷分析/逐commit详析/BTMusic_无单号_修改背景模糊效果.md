# 无单号 · 修改背景模糊效果

- **提交**：`7b459e9c` | 2026-07-14 | daizhecheng | BTMusic | feature
- **关联单**：无

## 需求/目标
重做蓝牙音乐主页的专辑图背景效果：由"取专辑图主色调（Palette）生成纯色圆角背景"改为"专辑图高斯模糊后直接作背景 + 顶部圆角裁剪"，并新增昼夜自适应的前景色资源。

## 实现结构
改动 4 个文件：
- `MainActivity.kt`：Glide 模糊参数从 `BlurTransformation(10, 3)` 调到 `(25, 5)`（更糊更柔和）；`Palette.from(bitmap)` 生成 dominant color 的整段逻辑删除，改为 `setupTopRoundedClip()` + `bitmap.toDrawable(resources)` 直接把解码 bitmap 设为 `clContent` 背景；加载失败/清除回调回退 `R.drawable.bg_main`。
- 新增 `setupTopRoundedClip()`：`clipToOutline = true` + 自定义 `ViewOutlineProvider`，用 `Path.addRoundRect`（仅顶部 36dp 圆角）`setConvexPath` 裁剪 outline，`bgClipSetup` 标志保证只装一次。
- 资源：`values/values.xml` 与 `values-night/values.xml` 各新增 `bg_forgound`（日间 `#99FFFFFF` 半透明白 / 夜间 `#4D000000` 半透明黑）；`bg_main_forget.xml` 底色改引该色。

数据流：专辑图 URL → Glide asBitmap + BlurTransformation(25,5) → bitmap → 直接 toDrawable 设背景 → outline provider 裁顶部圆角。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（节选）
-                            Palette.from(bitmap).generate { palette ->
-                                val colorF = if (isNightMode()) Color.WHITE else Color.BLACK
-                                val dominantColor = palette?.getDominantColor(colorF) ?: colorF
-                                val shape = RoundRectShape(outerRadii, null, null)
-                                val drawable = ShapeDrawable(shape).apply {
-                                    paint.color = dominantColor
-                                }
-                                mBinding!!.clContent.background = drawable
-                            }
+                            setupTopRoundedClip()
+                            val drawable = bitmap.toDrawable(resources)
+                            mBinding!!.clContent.background = drawable
```
实现讲解：两处手法值得记——其一，放弃 Palette 主色，用模糊图本体做背景，视觉上"同源渐变"更自然且省一次调色计算；其二，圆角从 `ShapeDrawable(RoundRectShape)` 挪到 `ViewOutlineProvider.setConvexPath`，裁的是 View 轮廓本身，任意内容（图片、子 View）都会被裁，比逐个 drawable 画圆角普适。

## 复盘与要点
- `clipToOutline + convex path` 是"容器级圆角/异形裁剪"的标准解法，可复用到任何需要"只圆上面两角"的车机卡片；注意 `setConvexPath` 仅支持凸多边形，凹形需 `ViewAnimationUtils` 或双 View 叠加。
- `bgClipSetup` 幂等标志防止重复设置 provider，是好习惯；但 outline 的 path 依赖 `v.width/height`，若首帧尺寸未定需在 layout 后刷新。
- 遗留点：模糊参数 (25,5) 加大后 CPU/GPU 成本上升，低端车机切歌时可感知卡顿的话需降采样（override 已限 380px，尚可）。
