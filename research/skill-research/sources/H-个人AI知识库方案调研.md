# H. 个人 AI 知识库方案调研（Summary 仓库改造）

> 调研日期：2026-09-06。通道说明：子代理通道持续并发受限，本轮由主会话 WebSearch + web_reader 完成；标注"未核实 star"处为网络受限未能经 API 确认。

## 1. Claude Code 官方记忆体系（web_reader 全文核实：code.claude.com/docs/en/memory）

- **CLAUDE.md**：目标 **<200 行**，写"每会话都要重新解释的事"；支持 `@path` 导入（启动时展开）；HTML 注释会被剥除——**免费的维护者批注层**；`/memory` 编辑。
- **`.claude/rules/*.md` + frontmatter `paths:`**：**按文件路径条件加载**的规则——原生渐进披露，"只在该改那类文件时占上下文"。
- **Auto memory（自动记忆）**：agent 自动积累四类笔记——**user**（用户偏好/角色）、**feedback**（纠正与偏好）、**project**（项目状态与决策）、**reference**（外部资料要点）；`MEMORY.md` 索引 + 按主题拆文件，仅加载索引前 200 行/25KB；**机器本地存储、不进 git**。
- **AGENTS.md 关系**：Claude Code 原生读 CLAUDE.md；跨工具标准做法 = CLAUDE.md 只写一行 `@AGENTS.md`（一次编写，Codex/Cursor/ZCode 通用）。
- 核心原则（与我们调研 C 笔记同源）："write down what you'd otherwise re-explain each session"——只缓存查不到/会重复解释的。

## 2. AGENTS.md 开放标准（agents.md，WebSearch 核实）

"README for agents"，站点自称 60,000+ 开源项目采用；跨工具通用（Codex/Cursor 等）。对 Summary 的意义：**入口文件选 AGENTS.md 而非 CLAUDE.md**，保持工具中立。

## 3. Cline Memory Bank（docs.cline.bot/best-practices/memory-bank + nickbaumann98/cline_docs + cline/prompts，WebSearch 核实；star 未核实）

- 机制：结构化 markdown 文件组——`projectbrief.md`（为什么做）、`productContext.md`、`activeContext.md`（当前焦点）、`systemPatterns.md`、`techContext.md`、`progress.md`（现状/下一步）；会话开始**先读全库再干活**。
- **Lockdown mode**（原版指令集的关键设计）：记忆文件**只在用户显式说 "UPDATE MEMORY BANK" 时更新**——防止 agent 任务中自动写文档、烧 token、把半成品写进知识库。更新发生在"有意义的工作收尾时"。
- 对 Summary 可抄：①"每类知识一个固定文件 + 会话开场读索引"的结构；②**写入显式化**（我们已有等价物：session-to-knowledge 的用户确认门 + project-decoder 的落盘确认）；③activeContext/progress 的"当前状态"层——Summary 目前没有（候选：根或每项目一个 STATUS 文件）。

## 4. 自动捕获流（coleam00/claude-memory-compiler，WebSearch 核实；star 未核实）

hooks 自动捕获会话 → Agent SDK 提炼"决策与教训"→ 写入随代码库演进的 memory。对 Summary 可抄：**阶段 3 的自动化摄入**（hooks 收口时调 session-to-knowledge 候选清单），但我们已有人工确认门的 skill 流，先人工后自动。

## 5. 认知框架（方法论文引用，训练知识 + 原文出处）

- **四类记忆**（alexop.dev/posts/four-types-memory-coding-agents-claude-code）：working / semantic（事实=知识条目）/ procedural（怎么做=skills/工作流）/ episodic（事件=复盘/issue）——**Summary 的目录天然对应**：knowledge-base=semantic、skills+tools+ai=procedural、issue+project=episodic、path/笔记=semantic 组织层。
- **PARA**（Tiago Forte, fortelabs.com）：按**行动性**而非主题分类（Projects/Areas/Resources/Archives）——对本仓库的启示：project/ 与学习笔记的边界按"是否活跃项目"划，与现状一致，无需搬家。
- **Zettelkasten/evergreen notes**（Andy Matuschak, notes.andymatuschak.org）：原子化命题条目 + 链接优先——knowledge-base 的"条目四段+完整命题标题"已是其变体，继续坚持。
- **"markdown files are all you need"**（dev.to/imaginex）：个人规模无需向量库，store/retrieve/use 纯 markdown + 索引即可——支持"保留目录+治理层"决策，不引入新基础设施。

## 6. 综合建议 → Summary 的落地方案

1. **根 AGENTS.md**（本次已建）：知识地图表（路径|是什么|何时读|怎么写入）+ 读写纪律（单一事实源引用子库规则）+ 四场景动线——对应 Claude 官方"CLAUDE.md <200 行 + 指路不复制"与 AGENTS.md 标准。
2. **子库规则下沉**：knowledge-base（CONTEXT.md+ROUTING.md）、excerpts（CONTEXT.md）已具备；skills（镜像纪律在脚本头）已具备——AGENTS.md 只指路。
3. **缺口摄入流**（roadmap 阶段 2）：issue/ 复盘流（现只有孤本 handoff）、写作流（juejin 索引有、成文链路无）、excerpts 读书笔记流（有 CONTEXT 无 skill 流）。
4. **可选增强**（阶段 3）：activeContext/STATUS 层（Cline Memory Bank 思想）；hooks 自动捕获（memory-compiler 思想）。
