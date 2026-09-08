# AGENTS.md — Summary 个人 AI 知识库

给 coding agent 的入口说明。本仓库是 private 的个人技术知识库（Android/车载开发为主）：学习笔记、项目资料、经验条目、agent skills。人读入口见 [README.md](./README.md)；本文件回答"agent 进仓后怎么查、怎么写、什么不能碰"。

## 这是什么（30 秒理解）

按记忆类型组织的四层：

- **Semantic（事实知识）**：`android/` `网络/` `设计模式/` `密码/`（学习笔记）+ `project/knowledge-base/`（经验条目库，内含 `language/` 语言学习笔记）
- **Procedural（怎么做）**：`skills/`（agent skills 镜像）+ `tools/`（自用工具）+ `ai/`（工具手册与工作流）+ `command/`
- **Episodic（事件与项目）**：`issue/`（问题复盘）+ `project/hc` `yadi` `WMS Viewer`（项目资料）
- **组织与索引**：`path/`（学习路径总纲）+ `juejin-articles-index.md`（文章索引）+ 本文件

## 知识地图（先查这里，再进目录）

| 路径 | 是什么 | agent 何时读 | 怎么写入 |
|---|---|---|---|
| `project/knowledge-base/` | 跨会话可迁移知识的中心路由入口：普通 `knowledge-entry` 条目与 `language-note` 学习散文两种 profile；内含 `language/` 子目录 | 找某类问题/思想/技巧的现成结论时；检索先读其 `ROUTING.md` | 只经 session-to-knowledge / source-annotator 的共享写入契约；新内容先按 profile 与路由规则处理 |
| `project/project-architecture/` | 各项目架构解码文档（带 commit 锚点） | 了解某项目架构前，先读对应 `<项目>.md` | 走 project-decoder skill，增量更新 |
| `skills/` | agent skills 镜像（与 `~/.agents/skills` 一致） | 查 skill 定义/规范时 | **绝不手改**——改 `~/.agents/skills` 后跑 `skills/sync-from-agents.sh` |
| `path/` | 学习路径总纲（Binder/Framework/AMS） | 系统学某领域前，先读总纲定顺序 | 用户手动维护 |
| `android/` `网络/` `设计模式/` `密码/` | 学习笔记（人读散文，非条目） | 被点名引用或作为分析素材时 | 无强制流；可沉淀的可迁移结论走 knowledge-base |
| `excerpts/` | 读书笔记 | 引用书中观点时 | 遵守其自己的 `CONTEXT.md` |
| `issue/` | 问题复盘与交接 | 排查同类问题前先查 | 复盘流待建（暂手动，见 Roadmap） |
| `ai/` | AI 工具手册与工作流（OpenCode、部署流程） | 使用/配置 AI 工具时 | 用户手动维护 |
| `project/hc` `yadi` `WMS Viewer` | 项目资料与设计文档 | 做对应项目任务时 | 项目内沉淀 |
| `tools/` | 自用工具源码 | 改工具前 | 工具内自有规则 |
| `juejin-articles-index.md` | 博客文章总索引 | 写作找历史文章/选题时 | 发文后手动登记 |

## 读写纪律

1. **单一事实源**：各子库的边界、规则、格式以其自己的 `CONTEXT.md` / `ROUTING.md` / README 为准——本文件只指路，不复制规则。
2. **写入分流**：可迁移经验 → knowledge-base（按其 ROUTING 路由）；架构理解 → project-architecture（走 project-decoder）；skill 改动 → 改 `~/.agents/skills` 再同步。分流不确定 → 问用户，不猜。
3. **不碰**：`skills/`（镜像，手改会被覆盖）、`.gitignore` 排除物（编译产物、AI 工具本地状态）、`LICENSE`。
4. **脱敏底线**：仓库虽 private，密钥/凭据/内网地址原文仍不入库（沿用既有约定）。

## 四场景动线（agent 接到任务后怎么走）

- **学习与源码研读**：`path/` 总纲定顺序 → 笔记库查已有理解 → source-annotator 精读标注 → 可迁移结论按 `knowledge-base/ROUTING.md` 沉淀
- **写作与发布**：`juejin-articles-index.md` 查已有 → 笔记与 knowledge-base 找素材 → 成文（成文流待建）
- **问题排查与复盘**：knowledge-base 与 `issue/` 先查同类 → 解决后复盘结论入库（复盘流待建）
- **工作流与项目上下文**：`ai/` 工具手册 → `project/` 对应项目资料 → `tools/` 源码

## 维护

本文件由用户与 agent 共同维护：新子库成立必须**先在此登记再用**；目录职责变化时同步更新地图；本文件目标 <200 行，超了先删后加。
