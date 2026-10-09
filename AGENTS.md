# AGENTS.md — Summary 个人知识库

本仓库维护 Android/车载开发相关的学习资料、可迁移经验、项目档案和工具。人读入口见 [README.md](README.md)；本文说明 agent 的查阅、写入和保护规则。本仓库知识内容生成与修订的写作规范唯一来源是 [`knowledge-base/WRITING-GUIDE.md`](knowledge-base/WRITING-GUIDE.md)；其他文档可以定义主题边界、路由、术语和事实索引，不维护独立写作规则。

## 知识地图

| 路径 | 用途与查阅入口 | 写入规则 |
|---|---|---|
| `knowledge-base/` | 跨会话知识入口，含 `knowledge-entry` 与 `language-note` 两种 profile；检索先读 `ROUTING.md` | 内容生成和修订完整遵守 `WRITING-GUIDE.md`；写入路由按 profile 与 `ROUTING.md` 处理 |
| `knowledge-base/01-android/`、`02-os/`、`03-network/`、`04-language/`、`05-design/`、`06-testing/`、`07-devtools/`、`08-exp/` | 学习资料目录（Q&A 或语言散文）；查阅前先读对应 README | 遵守目录边界和 `WRITING-GUIDE.md`；可迁移结论按 `ROUTING.md` 归档 |
| `knowledge-base/01-android/` | Android 与车载平台机制、实现和实践 | Android 实现归此；通用操作系统和网络协议分别归 `knowledge-base/02-os/`、`knowledge-base/03-network/` |
| `knowledge-base/02-os/`、`03-network/`、`04-language/` | 通用操作系统、网络协议、编程语言知识 | 按各目录 README 与 `WRITING-GUIDE.md` 维护；平台专属实现归 `knowledge-base/01-android/` |
| `knowledge-base/06-testing/`、`07-devtools/` | 测试基础、策略、用例、自动化，以及 Git 等通用开发工具 | 按目录 README 组织；Android 测试机制归 `knowledge-base/01-android/`，项目治理经验归 `knowledge-base/08-exp/` |
| `knowledge-base/08-exp/` | 跨项目非技术经验：阶段定位、质量投入、度量、流程和协作；Q 序列供 atlas 直读 | 技术根因归 `knowledge-base/01-android/` 等主题目录；项目事实归 `career/work-project-analysis/`；单次事件归 `issue/`。项目名按“某车机项目”惯例脱敏 |
| `knowledge-base/career/` | Android/车载求职资料、学习计划、周练及真实项目分析 | 路线见 `plans/roadmaps/`，周练规则见 `weekly/`；通用知识引用父目录，外部真实项目默认只读 |
| `knowledge-base/career/work-project-analysis/` | 真实工作项目分析 | 只记录可公开沉淀的项目经验，遵守脱敏要求 |
| `knowledge-base/atlas/` | Atlas 配置协作目录，内容不入索引 | `config/settings.properties` 可手动修改并重启生效；本机配置和 PIN 位于 `~/.local/share/atlas/`；不得写入凭据 |
| `knowledge-base/03-network/` | 平台无关的分层、DNS、TLS、TCP、NAT 等网络知识 | Android/车载网络实现归 `01-android/07-network/` |
| `project/project-architecture/` | 带 commit 锚点的项目架构解码 | 解读前阅读对应项目文档；使用 project-decoder 增量更新 |
| `issue/` | 问题复盘与交接 | 排查前查找同类问题；解决后归档复盘 |
| `research/` | 仓库级通用主题调研归档，如 `skill-research/` | 新主题建立子目录并登记；Atlas 调研归 `atlas/research/` |
| `atlas/` | Atlas 产品项目；先读 `PRD.md`。产品调研在 `research/`，桌面端代码在 `app/`，Android 端在 `app-android/` | 需求与变更日志写入 `PRD.md`；代码按工程流程维护；共享代码的平台差异收敛到 `atlas.platform` |
| `skills/`、`tools/`、`ai/` | Skills 镜像、自用工具、AI 工具手册与工作流 | `tools/skills/` **禁止手改**；修改 `~/.agents/skills` 后同步镜像。其他内容遵守各自规则 |
| `path/`、`excerpts/`、`juejin-articles-index.md` | 学习路径、读书摘录、文章索引 | `path/` 和索引由用户维护；`excerpts/` 遵守其 `CONTEXT.md` |
| `project/hc`、`yadi`、`WMS Viewer`、`honda27m-appstore-tools` | 项目资料、设计文档与工具链 | 按项目内规则维护 |
| `/home/liang/Project/MyProject/AAOS13_study` | AAOS 13 源码学习副本 | **允许修改**；不得将学习副本改动描述为量产项目成果 |
| `/home/liang/Project/MyProject/AndroidLibs` | Glide、Retrofit、AndroidX、Corretto 17 源码学习仓库 | **允许修改**；遵守其 `CONTEXT.md`；可迁移结论沉淀到 knowledge-base，面试表达写入 `career/` |
| `/home/liang/Project/Reachauto/YaDi/yadi_android`、`yadea_master` | Android/车载实际项目经验源 | **只读**；禁止修改源码、配置、构建脚本、生成物和提交历史。成果写回 Summary 或独立 Demo |

## 写入与保护规则

1. **按边界路由**：知识按 `knowledge-base/ROUTING.md` 分流；真实项目分析写入 `career/work-project-analysis/`；架构解读写入 `project-architecture/`；skill 先改 `~/.agents/skills` 再同步。路由不明确时先确认，不猜测。
2. **保护文件**：不得修改 `tools/skills/`、`.gitignore` 排除的本地状态或生成物、`LICENSE`，以及明确标为只读的外部项目。
3. **脱敏**：不得写入密钥、凭据或内网地址原文；项目名称按目录惯例脱敏。
4. **单一事实源**：不复制子目录规则；需要细则时先读对应 `CONTEXT.md`、`ROUTING.md` 或 README。

## 常见工作路径

- **学习与源码研读**：`path/` 定顺序 → 查 `knowledge-base/` → 使用 source-annotator → 按路由沉淀结论。
- **写作**：查 `juejin-articles-index.md`，再从笔记和知识库整理素材。
- **排查与复盘**：先查 `knowledge-base/` 和 `issue/`；解决后沉淀复盘。
- **项目工作**：先查 `ai/` 与对应 `project/` 资料，再按项目规则维护代码和文档。

## 维护

新子库启用前先登记于本文件；目录职责变化时同步更新。保持本文少于 200 行，优先精简重复说明。
