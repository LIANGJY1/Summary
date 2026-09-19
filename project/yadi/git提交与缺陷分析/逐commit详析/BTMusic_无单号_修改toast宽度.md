# 无单号 · 修改 toast 宽度

- **提交**：`794f56a9` | 2026-08-23 | dufan | BTMusic | feature（UI 微调）
- **关联单**：无

## 需求/目标
"无音乐源"提示 toast 去掉自定义 580dp 最小宽度，恢复 ToastUtils 默认宽度（此前宽度设置导致 toast 显示异常/过宽）。

## 实现结构
仅改动 `application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt`（1 增 2 删）：`showCenterToast` 调用点移除 `outMinWidth = com.yadea.common.R.dimen.dp_580` 参数。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/manager/BtMusicModel.kt
                     ToastUtils.showCenterToast(
                         context = it,
-                        message = it.getString(R.string.no_music_source_hint),
-                        outMinWidth = com.yadea.common.R.dimen.dp_580
+                        message = it.getString(R.string.no_music_source_hint)
                     )
```
实现讲解：一行参数回退——toast 宽度特殊值是 UI 验收中临时加的，实测效果不佳后删除，让公共 ToastUtils 用统一默认样式。典型"先加魔数、再回退"的小迭代。

## 复盘与要点
- 公共 toast 组件提供 outMinWidth 覆盖参数容易诱导各应用各调各的，破坏全局一致性；若设计上确需宽 toast，应在 ToastUtils 内统一。
- 影响 D 合理，纯展示层微调。
