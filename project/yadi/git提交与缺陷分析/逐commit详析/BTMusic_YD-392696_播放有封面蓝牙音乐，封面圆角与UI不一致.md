# YD-392696 · 播放有封面蓝牙音乐，封面圆角与UI不一致

- **提交**：`7de8f8c7` | 2026-06-29 | daizhecheng | BTMusic | bugfix（单位换算修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
蓝牙音乐播放带封面的歌曲时，封面图圆角明显小于设计稿，观感"几乎直角"，与 UI 不符。

## 根因分析
`BTMusic/MainActivity.kt` 用 Glide 加载媒体封面：`RequestOptions().transform(RoundedCorners(16))`。问题出在**单位**——Glide 的 `RoundedCorners(int radius)` 参数单位是**像素（px）**，而设计稿标注的 16 是 **dp**。该机器是高密度车机屏（后续 `.override(380.dpToPx(), 380.dpToPx())` 佐证项目有 dpToPx 换算习惯），16px 折算回 dp 大约只有 4~5dp，圆角自然远小于设计稿的 16dp。这是"单位未换算"型 bug 的标准样本：代码编译运行全部正常，只在真机密度下暴露。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt

```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
@@ RoundedCorners 参数由 px 字面量改为 dp 换算
                 Glide.with(this).load(it).apply(
-                    RequestOptions().transform(RoundedCorners(16)).placeholder(R.drawable.def_img)
+                    RequestOptions().transform(RoundedCorners(48.dpToPx())).placeholder(R.drawable.def_img)
                         .error(R.drawable.def_img)
                 ).override(380.dpToPx(), 380.dpToPx()).skipMemoryCache(false).into(mBinding!!.ivPic)
```

## 为什么能修复
`RoundedCorners(48.dpToPx())` 先把设计值换算成该屏幕的实际像素再交给 Glide，圆角视觉即与 48dp（本页设计稿最终采用的值，大于提交标题暗示的 16——以 diff 实际数值为准，说明设计稿也同步改大了圆角）一致。修复方式正确（在调用点做 dp→px），但仍有隐患：换算散落在调用处，若其他页面也用 `RoundedCorners(16)` 这类裸数字，同款 bug 还会复现；更优做法是封装 `RoundedCorners.dp(radius)` 扩展或统一图片样式常量。

## 复盘与经验
- **Android API 的长度参数默认 px**：`RoundedCorners`、`_translationY(float)`、Canvas 绘制等，凡带数值必先问单位；`dpToPx()` 应成为肌肉记忆。
- **单位 bug 在模拟器/低密度屏上可能"看起来正常"**：验收必须覆盖目标车机真实密度。
- **魔法数（16/48/380）应提为设计令牌**：同一封面样式在多页面复用时，集中定义才能保证圆角、尺寸全局一致。
