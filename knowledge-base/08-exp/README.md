# 项目经验（exp）

> 收录从真实项目蒸馏、换一个项目仍然成立的非技术判断规则：项目阶段定位、质量投入取舍、度量口径与取数方法、流程治理与制度化兜底、协作联调机制。

## 边界

收：非技术、跨项目仍成立的判断规则——怎么读项目状态、怎么定质量投入、怎么定度量口径、怎么用流程与制度兜底、怎么组织协作联调。

不收（与邻居分工）：

1. 技术根因（某类缺陷的机制与修法）→ 按知识主题归入 [Android 对应主题册](../01-android/README.md)
2. 项目事实档案（架构、模块与职责）→ 仓库根目录 `project/project-architecture/` 或对应项目目录
3. 单次事件的排查过程 → `issue/`
4. 可迁移的技术设计权衡 → [05-design/](../05-design/) 或对应技术主题册

## 全册速览

| 册 | 标题 | 题数 | 主线 |
| --- | --- | ---: | --- |
| [00-project-defect-profile-and-review.md](./00-project-defect-profile-and-review.md) | 项目缺陷画像与复盘方法 | 18 | 85 天 1010 条提交的缺陷权重结构 / A 级两条规律线 / 双源取数以 diff 为准 / 追溯性损耗与流水线卡点 |
| [01-change-governance-and-regression-prevention.md](./01-change-governance-and-regression-prevention.md) | 提交治理与防回归 | 20 | 提交消息、单号与 Change-Id 纪律、重复和错标识别、回退、分支合入及 CI/MR 卡点 |

## 写作规范

文章式内容的结构、表达及来源标注统一遵守 [WRITING-GUIDE.md](../WRITING-GUIDE.md)；项目名称脱敏遵守 [AGENTS.md](../../AGENTS.md)。
