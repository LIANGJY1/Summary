# 无单号 · CommonTools 黑白模式适配（ic_small_un_choose 单图标修正）

- **提交**：`ad350731` | 2026-06-29 | dufan | CommonTools | feature
- **关联单**：无

**类型**：纯资源微调提交（1 个 drawable XML，+5/-6 行）。

## 内容一句话
勾选框未选中态图标 `ic_small_un_choose.xml` 的描边/填充色值微调为语义色，使未选中样式在黑白两模式下与选中态 `ic_small_choose` 视觉一致。

```
component/CommonTools/src/main/res/drawable/ic_small_un_choose.xml | 5 +-
```

属于同日 dufan "黑白模式适配"系列中最小的一片，无独立学习价值，核心手法见 `b226c24f.md`（语义色收敛）。
