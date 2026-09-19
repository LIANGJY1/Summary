# 无单号 · BTMusic 黑白模式适配（专辑封面取色背景昼夜适配）

- **提交**：`c8ac775a` | 2026-06-30 | daizhecheng | BTMusic | feature
- **关联单**：无

## 需求/目标
蓝牙音乐主界面整体黑白模式重构：专辑封面模糊取色作为内容区背景时按日夜取不同兜底色，重写主界面布局（activity_main.xml 大改 233 行），补夜间背景 drawable。

## 实现结构
- `MainActivity.kt`（±72 行）：恢复并改进此前注释掉的"封面模糊背景"逻辑——Glide `asBitmap + BlurTransformation(10,3)` 加载封面到 `CustomTarget<Bitmap>`，`Palette.from(bitmap).generate` 取主色，兜底色由 `isNightMode()` 决定白/黑；主色用 `RoundRectShape(顶部四角 36dp 圆角)` + `ShapeDrawable` 设置给 `clContent`，实现"顶部圆角渐变背景"；无封面时回落 `R.drawable.bg_main`（新增 `bg_main_forget.xml` 兜底背景）。
- `activity_main.xml`：233 行布局重构（控件约束/背景引用语义色）；`widget_music_play_control.xml` 同步。

数据流：曲目变化 → Glide 加载封面 → Palette 主色（夜间兜底 WHITE / 白天兜底 BLACK）→ ShapeDrawable 圆角背景 → clContent；模式切换时下次加载即取新兜底色。

## 关键代码
```diff
# application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
+                Glide.with(this)
+                    .asBitmap()
+                    .load(it)
+                    .transform(BlurTransformation(10, 3))
+                    .into(object : CustomTarget<Bitmap>() {
+                        override fun onResourceReady(
+                            bitmap: Bitmap, transition: Transition<in Bitmap>?
+                        ) {
+                            Palette.from(bitmap).generate { palette ->
+                                val colorF = if (isNightMode()) Color.WHITE else Color.BLACK
+                                val dominantColor = palette?.getDominantColor(colorF) ?: colorF
+                                val radiusPx = 36.dpToPx().toFloat()
+                                val outerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
+                                val shape = RoundRectShape(outerRadii, null, null)
+                                val drawable = ShapeDrawable(shape).apply { paint.color = dominantColor }
+                                mBinding!!.clContent.background = drawable
+                            }
+                        }
+                        override fun onLoadCleared(placeholder: Drawable?) {}
+                    })
```

实现讲解：从封面图提取主色做背景时，"主色偏暗/偏亮"在不同主题下观感不同，兜底色按日夜切换（`getDominantColor(night?WHITE:BLACK)`）让 Palette 无主色或主色过近时仍可读。用 `RoundRectShape` 代码化圆角背景而非 xml drawable，是因为颜色运行时才确定，只能代码构造 drawable。

## 复盘与要点
- 可复用手法：Palette 动态取色 + uiMode 兜底色 + 代码构造 ShapeDrawable，是媒体类 App 封面沉浸背景的完整模板；模糊半径从注释版 25 调到 10，兼顾性能与观感。
- `CustomTarget` 记得在曲目切换前 `Glide.with(this).clear()`（本文件已有），否则异步回调可能命中旧视图。
- 遗留风险：模式切换不重建页面时（BTMusic 未说明 configChanges 声明），已生成的背景色不会刷新，需在 onConfigurationChanged 里重触发加载。
