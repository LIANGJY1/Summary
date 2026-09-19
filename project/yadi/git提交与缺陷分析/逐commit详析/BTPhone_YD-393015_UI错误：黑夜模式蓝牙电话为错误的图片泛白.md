# YD-393015 · 黑夜模式蓝牙电话"错误"图片泛白
- **提交**：`31f860ce` | 2026-07-31 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下蓝牙电话"错误/失败"状态页的提示图片泛白，与 UI 稿不符。

## 根因分析
与同日 `38211575`（YD-393016 空态图 empty.png）完全同模式：错误态图片 `sync_error.png` 只有 `res/drawable/` 白天版本，缺 `drawable-night` 变体，黑夜模式回退加载白天浅色图导致泛白。蓝牙电话的空态图（empty）与错误态图（sync_error）分两个单号两天内先后修复，说明 UI 交付时夜间图是按界面零散补交的。

## 关键代码修改
改动文件：新增 `application/BTPhone/src/main/res/drawable-night/sync_error.png`（二进制图片资源，唯一改动）
```diff
+++ application/BTPhone/src/main/res/drawable-night/sync_error.png  (二进制更新)
```

## 为什么能修复
补齐 `sync_error.png` 的夜间限定符版本后，黑夜模式自动加载深色错误图，泛白消失；零代码改动、无逻辑风险。需保证夜间图内容语义与白天一致仅配色差异。

## 复盘经验
- 同一状态页的多张图（empty/sync_error/loading）应作为一组资源同步交付与检查，零散补交就会出现"修一张漏一张"的连续单。
- 建议把"资源文件级昼夜成对校验"固化进构建脚本：对 `drawable/` 与 `drawable-night/` 做差集输出清单，低成本拦截此类问题。
