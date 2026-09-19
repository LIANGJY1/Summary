# SIR-6224 · 黑夜模式下播放蓝牙音乐的默认封面与UI不一致

- **提交**：`c8e27400` | 2026-08-21 | dufan | BTMusic | bugfix（资源补充 + 顺带空安全清理）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
黑夜模式下播放蓝牙音乐、曲目无专辑封面时，界面显示的默认封面图与 UI 设计稿不一致（用的是白天模式素材）。

## 根因分析
缺陷库根因："未添加对应资源"。`MainActivity` 中封面兜底统一引用 `R.drawable.default_cover`，但工程只有白天模式的该资源，没有 `drawable-night` 变体；黑夜模式下资源查找回落到白天版图片，观感与 UI 稿不符。修复在 `res/drawable-night-mdpi/` 下新增黑夜版 `default_cover.png`（二进制资源新增），代码无需改动即由 Android 资源限定符在夜间模式下自动选用新图。提交同时把 `MainActivity` 里的 `mBinding?.` / `mBinding!!` 混用统一改为 `mBinding.`（视图绑定非空断言清理），属顺带的可读性整改，与缺陷无直接关系。

## 关键代码修改
改动文件：application/BTMusic/src/main/res/drawable-night-mdpi/default_cover.png（新增，二进制资源）、application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（+13/-13 空安全写法统一）
```diff
@@ 资源新增（二进制） @@
+application/BTMusic/src/main/res/drawable-night-mdpi/default_cover.png  (Bin, 120608 bytes)
```
```diff
@@ application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（示例 hunk）
-        mBinding!!.ivPic.setImageResource(R.drawable.default_cover)
+        mBinding.ivPic.setImageResource(R.drawable.default_cover)
```

## 为什么能修复
`drawable-night-mdpi` 限定符让夜间 UI 模式自动命中黑夜版封面资源，白天模式不受影响，无需任何代码分支。隐患：仅补了 `mdpi` 密度变体，高密度设备若匹配不到精确密度目录会回落到该图（小图放大可能模糊），后续可按设计规格补全密度或改用矢量图。

## 复盘与经验
- 白天/黑夜双主题界面，每张运营图/默认图都要问一句"黑夜版在哪"——资源限定符（drawable-night）是最省事的实现，缺资源比错代码更常见。
- 无封面兜底图是媒体类应用的高频视觉缺陷点，验收要专门构造"无封面曲目"场景。
- 修复提交里顺手统一 `mBinding` 空安全写法可以，但要控制在声明式替换，避免扩大 diff。
