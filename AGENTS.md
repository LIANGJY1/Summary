# AGENTS.md — Summary 个人 AI 知识库

给 coding agent 的入口说明。本仓库是 private 的个人技术知识库（Android/车载开发为主）：学习笔记、项目资料、经验条目、agent skills。人读入口见 [README.md](./README.md)；本文件回答"agent 进仓后怎么查、怎么写、什么不能碰"。

## 这是什么（30 秒理解）

按记忆类型组织的四层：

- **Semantic（事实知识）**：`knowledge-base/`（统一承载学习资料、语言笔记和可迁移经验条目）
- **Procedural（怎么做）**：`skills/`（agent skills 镜像）+ `tools/`（自用工具与命令行工具）+ `ai/`（工具手册与工作流）
- **Episodic（事件与项目）**：`issue/`（问题复盘）+ `knowledge-base/career/work-project-analysis/`（真实工作项目分析）+ `project/hc` `yadi` `WMS Viewer`（其他项目资料）+ `atlas/`（AI 成长工作站产品项目，根目录）
- **组织与索引**：`path/`（学习路径总纲）+ `research/`（调研归档）+ `juejin-articles-index.md`（文章索引）+ 本文件

## 知识地图（先查这里，再进目录）

| 路径 | 是什么 | agent 何时读 | 怎么写入 |
|---|---|---|---|
| `knowledge-base/` | 跨会话可迁移知识的中心路由入口：普通 `knowledge-entry` 条目与 `language-note` 学习散文两种 profile；内含 `language/` 子目录 | 找某类问题/思想/技巧的现成结论时；检索先读其 `ROUTING.md` | 只经 session-to-knowledge / source-annotator 的共享写入契约；session-to-knowledge 调用即出题——复盘题以 `**Qn:**` 同源格式直接写入知识文档，atlas 同源直读；新内容先按 profile 与路由规则处理 |
| `project/project-architecture/` | 各项目架构解码文档（带 commit 锚点） | 了解某项目架构前，先读对应 `<项目>.md` | 走 project-decoder skill，增量更新 |
| `knowledge-base/career/` | Android 车机求职统一子目录，含 `plans/`、`weekly/`、求职资产和真实工作项目分析 | 求职、源码学习、项目复盘时 | 总路线在 `plans/roadmaps/`，每日练习与作答规则在 `weekly/`；通用知识引用父目录，外部真实项目默认只读 |
| `tools/skills/` | agent skills 镜像（与 `~/.agents/skills` 一致） | 查 skill 定义/规范时 | **绝不手改**——改 `~/.agents/skills` 后手动同步拷贝到此处（暂无自动同步脚本） |
| `knowledge-base/android/`、`knowledge-base/密码/`、`knowledge-base/网络/`、`knowledge-base/设计模式/`、`knowledge-base/文件管理系列文章/` | 学习笔记（人读散文，非条目） | 被点名引用或作为分析素材时 | 无强制流；可迁移结论按 `ROUTING.md` 沉淀为知识条目 |
| `knowledge-base/path/` | 学习路径总纲（Binder/Framework/AMS） | 系统学某领域前，先读总纲定顺序 | 用户手动维护 |
| `excerpts/` | 读书笔记 | 引用书中观点时 | 遵守其自己的 `CONTEXT.md` |
| `issue/` | 问题复盘与交接 | 排查同类问题前先查 | 复盘流待建（暂手动，见 Roadmap） |
| `research/` | 深度调研归档（库级通用主题）：`skill-research/`（Skills 生态调研 + 知识库总体建造方案）。注：atlas 项目相关调研已移至 `atlas/research/` | 需要某主题的调研结论、数据或项目盘点时 | 调研完成后整目录归档于此；新主题先建子目录并在此登记 |
| `/home/liang/Project/MyProject/AAOS13_study` | AAOS 13 源码学习副本：源码批注、实验修改、机制验证和学习记录 | Android Framework/AAOS 源码学习时 | **允许修改**；不得把学习副本改动表述为量产项目成果 |
| `/home/liang/Project/MyProject/AndroidLibs` | 应用层库源码标注学习仓库：glide / retrofit / androidx / corretto-17（JDK 17 java.base），规则见其 `CONTEXT.md` | 求职"源码/实践"轨道、Glide/Retrofit/androidx/JDK 面试主题、找 SDK 设计启示时 | **允许修改**（源码标注学习）；可迁移结论按其词条沉淀 knowledge-base 三文档；面试化表达写回 `career/` |
| `/home/liang/Project/Reachauto/YaDi/yadi_android` `/home/liang/Project/Reachauto/YaDi/yadea_master` | Android/车机实际项目经验源 | 车机求职、项目复盘、源码对照时只读参考 | **严禁修改**源码、配置、构建脚本、生成物和提交历史；产出只写回 Summary 或独立 Demo |
| `ai/` | AI 工具手册与工作流（OpenCode、部署流程）+ Claude Code/Skills 官方文档摘存（`claude-code/`） | 使用/配置 AI 工具时 | 用户手动维护 |
| `project/hc` `yadi` `WMS Viewer` `honda27m-appstore-tools` | 项目资料与设计文档、项目工具链 | 做对应项目任务时 | 项目内沉淀 |
| `atlas/` | AI 成长工作站产品项目（Linux 桌面端知识库+学习闭环应用，代号 atlas，**根目录级**）：单一 `PRD.md` 承载定位/需求/里程碑；`research/` 子目录存放其上游证据调研（ai-era-programmer、linux-desktop-ai-apps）；**MVP 代码在 `app/`**（Kotlin/Compose Desktop，gradle 工程，纳入本仓 git 管理） | 做该产品任何工作（设计/开发/发布）前，先读其 PRD.md | 直接更新 PRD.md，变更记其变更日志；代码改动走 `atlas/app/` 内常规工程流程 |
| `tools/` | 自用工具源码与命令行工具（含 `command/`） | 改工具前 | 工具内自有规则 |
| `juejin-articles-index.md` | 博客文章总索引 | 写作找历史文章/选题时 | 发文后手动登记 |

## 读写纪律

1. **单一事实源**：各子库的边界、规则、格式以其自己的 `CONTEXT.md` / `ROUTING.md` / README 为准——本文件只指路，不复制规则。
2. **写入分流**：可迁移经验 → knowledge-base（按其 ROUTING 路由）；真实工作项目分析 → `knowledge-base/career/work-project-analysis/`；架构理解 → project-architecture（走 project-decoder）；skill 改动 → 改 `~/.agents/skills` 再同步。分流不确定 → 问用户，不猜。
3. **不碰**：`tools/skills/`（镜像，手改会被覆盖）、`.gitignore` 排除物（编译产物、AI 工具本地状态）、`LICENSE`。
4. **脱敏底线**：仓库虽 private，密钥/凭据/内网地址原文仍不入库（沿用既有约定）。

## 四场景动线（agent 接到任务后怎么走）

- **学习与源码研读**：`knowledge-base/path/` 总纲定顺序 → `knowledge-base/` 查已有理解 → source-annotator 精读标注 → 可迁移结论按 `knowledge-base/ROUTING.md` 沉淀
- **写作与发布**：`juejin-articles-index.md` 查已有 → 笔记与 knowledge-base 找素材 → 成文（成文流待建）
- **问题排查与复盘**：knowledge-base 与 `issue/` 先查同类 → 解决后复盘结论入库（复盘流待建）
- **工作流与项目上下文**：`ai/` 工具手册 → `project/` 对应项目资料 → `tools/` 源码

## 维护

本文件由用户与 agent 共同维护：新子库成立必须**先在此登记再用**；目录职责变化时同步更新地图；本文件目标 <200 行，超了先删后加。
