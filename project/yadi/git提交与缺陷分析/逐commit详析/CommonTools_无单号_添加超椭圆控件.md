# 无单号 · 添加超椭圆控件（SquircleImageView）

- **提交**：`2c780978` | 2026-07-01 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
为公共控件库新增"超椭圆（Squircle，iOS 风格连续圆角）"图片控件 `SquircleImageView`：图片按超椭圆曲线裁切填充，支持 `squircleFactor` 调节曲率。

## 实现结构
单文件新增 `component/CommonTools/.../widgets/SquircleImageView.kt`（129 行），继承 `AppCompatImageView`：
- `rebuildPath()`：按超椭圆参数方程 `|x/a|^n + |y/b|^n = 1` 采样 200 步构建 `clipPath`（用 `signum(cosT)*|cosT|^(2/n)` 处理四象限符号），`onSizeChanged` 与 `squircleFactor` setter 时重建。
- `onDraw()`：取 drawable 转 Bitmap（非 BitmapDrawable 先画到临时 Bitmap），按 `scaleType`（CENTER_CROP/FIT_CENTER/CENTER_INSIDE/CENTER 等）计算 `Matrix` 缩放平移，构造 `BitmapShader` 后 `canvas.drawPath(clipPath, shaderPaint)` 以图片为纹理填充路径。
- 不走 `canvas.clipPath + super.onDraw` 而用 shader 填充，避免双重绘制与抗锯齿锯齿。

数据流：尺寸/因子变化 → rebuildPath → onDraw：drawable→Bitmap→BitmapShader(矩阵对齐 scaleType)→drawPath 填充。

## 关键代码
```kotlin
# component/CommonTools/src/main/java/com/yadea/common/widgets/SquircleImageView.kt（新增，节选）
+        val steps = 200
+        clipPath.moveTo(cx + a, cy)
+        for (i in 1..steps) {
+            val t = (2.0 * Math.PI * i / steps).toFloat()
+            val cosT = cos(t); val sinT = sin(t)
+            val px = cx + a * Math.signum(cosT) * abs(cosT).toDouble().pow(2.0 / n).toFloat()
+            val py = cy + b * Math.signum(sinT) * abs(sinT).toDouble().pow(2.0 / n).toFloat()
+            clipPath.lineTo(px, py)
+        }
+        clipPath.close()
...
+        shader.setLocalMatrix(matrix)
+        shaderPaint.shader = shader
+        canvas.drawPath(clipPath, shaderPaint)
```

实现讲解：超椭圆与普通圆角矩形（`RoundRectShape`/`clipPath.addRoundRect`）的区别是曲率连续、过渡平滑，车机 HMI 大量用于卡片/图标。本实现两个关键点：一是参数方程按 `2/n` 次幂采样（n=4 时即经典 squircle），符号函数保证四象限正确；二是 BitmapShader + localMatrix 的"纹理填充"方案，让任意 scaleType 的图片都能精确对齐裁切路径，这是比 `canvas.clipPath()` 更高质量的裁切手法。

## 复盘与要点
- 可复用手法：`BitmapShader + setLocalMatrix(scaleType 矩阵) + drawPath` 是任意形状图片裁切的通用范式，可推广到圆形/圆角/六边形等，不必每形状写一套。
- 遗留风险：200 段 lineTo 的 Path 每次尺寸变化重建，在 RecyclerView 快速滚动时可接受但可缓存；`onDraw` 里为非 BitmapDrawable 每帧 `createBitmap` 有内存抖动，应缓存转换结果； HardwareBitmap/大图场景未做 `Bitmap.Config` 优化。
- `fitXY` 分支留空依赖 shader 默认拉伸，语义成立但无注释说明，可读性欠佳。
