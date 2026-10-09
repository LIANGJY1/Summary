# 项目经验（exp）

> 非技术项目经验目录（学习资料目录，不参与 `ROUTING.md` 大类路由，见知识库 CONTEXT「学习资料目录」）。收录从真实项目蒸馏、换一个项目仍然成立的**非技术**判断规则：项目阶段定位、质量投入取舍、度量口径与取数方法、流程治理与制度化兜底、协作联调机制。题目供 atlas 同源直读。首册于 2026-09-30 自 `01-android/11-defects/00` 迁入；2026-10-03 原 `01-android/11-defects/08-提交治理与防回归` 整册迁入为 [01-change-governance-and-regression-prevention.md](./01-change-governance-and-regression-prevention.md)（20 题，非技术治理）。维护者：session-to-knowledge。

## 边界

收：非技术、跨项目仍成立的判断规则——怎么读项目状态、怎么定质量投入、怎么定度量口径、怎么用流程与制度兜底、怎么组织协作联调。

不收（与邻居分工）：

1. 技术根因（某类缺陷的机制与修法）→ 按知识主题归入 [Android 对应主题册](../01-android/README.md)
2. 项目事实档案（架构、模块、个人职责、面试表达）→ [career/work-project-analysis/](../career/work-project-analysis)（2026-10-03 已建立，首册 Launcher 项目车辆服务案例）
3. 单次事件的排查过程 → `issue/`
4. 可迁移的**技术**设计权衡 → [design-principles.md](../../docs/design-principles.md) 等 ROUTING 大类

## 全册速览

| 册 | 标题 | 题数 | 主线 |
| --- | --- | ---: | --- |
| [00-project-defect-profile-and-review.md](./00-project-defect-profile-and-review.md) | 项目缺陷画像与复盘方法 | 18 | 85 天 1010 条提交的缺陷权重结构 / A 级两条规律线 / 双源取数以 diff 为准 / 追溯性损耗与流水线卡点 |
| [01-change-governance-and-regression-prevention.md](./01-change-governance-and-regression-prevention.md) | 提交治理与防回归 | 20 | 提交消息、单号与 Change-Id 纪律、重复和错标识别、回退、分支合入及 CI/MR 卡点 |

## 规划中（有源材料才立册，不预建空册）

1. 度量口径与取数方法 — 非 merge 口径、互斥归类、去重口径、双源取数（源：yadi 主报告口径章节 + `逐commit详析/`）
2. 流程治理与制度化兜底 — 评审固定检查项、资源规范先行、根因填充制度（与 [01-change-governance-and-regression-prevention.md](./01-change-governance-and-regression-prevention.md) 互为技术/制度两视角）
3. 协作与联调机制 — 多系统联调、提测矩阵、偶现缺陷走机制分析专项的组织路径

## 写作规范

文章式内容的结构、表达及来源标注统一遵守 [WRITING-GUIDE.md](../WRITING-GUIDE.md)；项目名称脱敏遵守 [AGENTS.md](../../AGENTS.md)。
