# D-392951 · 黑夜模式检索页面泛白（第一步：补占位图暗色版）

- **提交**：`3faf14af` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 D-392951，疑为 YD-392951 笔误；defs 无条目）

## 问题
黑夜模式下蓝牙电话检索页泛白：检索无结果占位图为亮色调，与暗色背景冲突。

## 根因分析
检索页（`activity_main.xml` 第 318 行）无结果占位图 `android:src="@drawable/no_agree"` 只有 `drawable/` 下一个白天版本位图，无 `drawable-night` 变体，夜间模式沿用亮色图片导致页面局部泛白。属位图资源缺昼夜双版本问题（与 `b07dca37` 的 icon_no_contact 同一模式）。本提交是该单的**第一步修复**，次日 `367f3f19` 继续修复同单的搜索高亮色问题。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/drawable-night/no_agree.png（**二进制新增**，228366 字节）

```diff
--- /dev/null
+++ b/application/BTPhone/src/main/res/drawable-night/no_agree.png
（二进制文件：新增黑夜模式"无匹配结果"占位插图）
```

## 为什么能修复
`drawable-night/` 限定符让系统在夜间 uiMode 下自动选用暗色调版本插图，白天不变，布局代码零改动。注意：本提交只解决了占位图一处泛白，检索文字高亮色的黑夜适配在同单后续提交 `367f3f19` 中完成——同一个单分两次提交修复，回溯时需两看。

## 复盘与经验
- **"检索页面泛白"往往不止一处**：占位图、命中高亮、背景层次都可能各自泛白，修复时要整页排查而非见一处改一处（本单正是分两次才修全）。
- **位图昼夜适配统一走 drawable-night**，与 `b07dca37`（icon_no_contact）完全同构，说明 BTPhone 早期交付漏掉了整套暗色切图，属于批量性遗漏，应一次性梳理补齐。
