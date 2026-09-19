# YD-392696 · 蓝牙音乐封面圆角与 UI 不一致
- **提交**：`8ea38e06` | 2026-06-30 | daizhecheng | BTMusic | bugfix
- **缺陷库**：未关联单号（YD 内部单）

## 问题
播放带封面的蓝牙音乐时，封面图片的圆角大小与 UI 设计稿不一致（圆角偏小）。

## 关键前提
纯 UI 还原度修复，改动仅 2 行，为 Glide 加载链路的调用顺序调整。

## 根因分析
`MainActivity.kt`（`application/BTMusic/.../btmusic/MainActivity.kt`）中封面加载：旧代码先 `.apply(RequestOptions().transform(RoundedCorners(48.dpToPx()))...)`，再在链尾调用 `.override(380.dpToPx(), 380.dpToPx())`。设计意图是"先按 380px 下采样、再施加 48px 圆角变换"，使 48px 半径相对最终 380px 位图计算；当 override 的生效时序晚于 transform 配置时，圆角变换实际作用的位图尺度与预期不符（48px 相对大图被缩小显示），导致最终呈现的圆角与 UI 稿不一致。以 diff 实际为准：本次修复就是把 `override(380, 380)` 从 `.apply(...)` 之后挪到之前，其余参数（`RoundedCorners(48.dpToPx())`、placeholder、error、skipMemoryCache）均未变。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
@@ -185,10 +185,10 @@ open class MainActivity : BaseActivity<ActivityMainBinding, MainViewModel>(),
             if (it != null) {
                 Glide.with(this).clear(mBinding!!.ivPic)
-                Glide.with(this).load(it).apply(
+                Glide.with(this).load(it).override(380.dpToPx(), 380.dpToPx()).apply(
                     RequestOptions().transform(RoundedCorners(48.dpToPx())).placeholder(R.drawable.def_img)
                         .error(R.drawable.def_img)
-                ).override(380.dpToPx(), 380.dpToPx()).skipMemoryCache(false).into(mBinding!!.ivPic)
+                ).skipMemoryCache(false).into(mBinding!!.ivPic)
```

## 为什么能修复
override 前置明确了下采样发生在圆角变换之前，`RoundedCorners(48dp)` 在 380x380 的目标位图上绘制，圆角视觉比例与 UI 稿一致。风险很低；更稳妥的做法是把 `override()` 直接写进 `RequestOptions`，避免依赖链式调用顺序，也便于复用同一份 options。

## 复盘与经验
- Glide 的 `transform()` 圆角是像素值、相对"应用变换时的位图尺寸"，大图不加 override 直接圆角必然与设计稿偏差，圆角+override 要成对出现。
- 链式调用顺序在 Glide 中虽多数场景等价，但 transform/override 的先后意图应显式写清，减少版本差异带来的隐性回归。
