# E. "项目架构分析 / 代码库理解"类 Agent Skills 与工具调研

调研日期：2026-09-06。所有 star 数为当日 GitHub API 实测值；所有 SKILL.md 均通过 raw.githubusercontent.com 实际抓取，标注了抓取路径。目的：为自写一个"架构分析/代码库理解" skill 收集可抄的流程设计与防浅尝辄止手段。

---

## 0. 结论速览（最值得抄的来源）

| 来源 | star | 一句话抄什么 |
|---|---|---|
| ksimback/tech-debt-skill | 590 | "先定向后判断"协议 + file:line 强制引用 + 必填"看着糟但其实没问题"章节，是防浅尝辄止的最佳单文件范本 |
| Egonex-AI/Understand-Anything | 81,574 | 七阶段管线：确定性脚本做脏活（扫描/分批/import 图），LLM 只做语义分析；产物落盘为可复用的 knowledge-graph.json + 新鲜度校验 |
| alexanderop/walkthrough | 130 | 并行 Explore 子代理 + 结构化 NODE 报告模板 + "5-12 节点"硬约束 + 生成前质量 checklist |
| Aider repo map | 34,469※ | tree-sitter 抽符号 → 引用图 → 个性化 PageRank → token 预算内裁剪，"给 LLM 喂项目结构"的祖师爷算法 |
| Graphify-Labs/graphify | 114,980 | 每条边标注 EXTRACTED/INFERRED（读到的 vs 推断的），可解释性设计值得抄进任何分析类 skill |

※Aider-AI/aider star 数为 API 实测，见 §3。

---

## 1. anthropics/skills 官方仓库：确认没有此类 skill

- 仓库：https://github.com/anthropics/skills — **174,448 stars**，"Public repository for Agent Skills"（2026-09-06 API 实测）
- 方法学：`GET /repos/anthropics/skills/git/trees/HEAD?recursive=1` 全量列目录，共 20 个 SKILL.md：
  academy-guide, algorithmic-art, brand-guidelines, canvas-design, claude-api, discernment-nudge, doc-coauthoring, docx, frontend-design, internal-comms, mcp-builder, pdf, pptx, skill-creator, slack-gif-creator, theme-factory, web-artifacts-builder, webapp-testing, xlsx, template
- **结论：没有任何代码库分析/架构/理解类 skill。**
- 最接近的三个：
  - `skill-creator`：写 skill 的元技能（与"分析代码库"正交，但可参考其 SKILL.md 组织方式）
  - `mcp-builder`：从零搭建 MCP server，涉及"理解项目结构后生成代码"，但方向相反
  - `webapp-testing`：对运行中的 webapp 做浏览器测试，是"检查现有产物"而非"理解源码"
- 官方 skill 的通用写法值得整体抄：YAML frontmatter（name/description 触发词导向）+ 简短正文 + 复杂细节放 references/ 辅助文件渐进加载。

**对自写 skill 可抄什么**：官方确认该赛道是空白，自写有价值；SKILL.md 结构遵循官方 frontmatter 惯例即可保证触发与兼容。

---

## 2. 社区 skill（深读）

### 2.1 ksimback/tech-debt-skill — 技术债/架构审计（深读 ★首选范本）

- 仓库：https://github.com/ksimback/tech-debt-skill — **590 stars**（API 实测）
- 抓取：`raw.githubusercontent.com/ksimback/tech-debt-skill/HEAD/SKILL.md`（225 行，单文件）
- 定位：`/tech-debt-audit`，产出 `TECH_DEBT_AUDIT.md`。虽然叫"技术债"，实质就是一次结构化的架构理解 + 评估。

流程设计（三阶段）：
1. **Phase 1 Orient（强制定向，"Do not skip this"）**：读 README/manifest/adr → 目录结构映射 → `git log --oneline -200` 和 `git log --stat --since="6 months ago"` 看churn → 识别入口点/热路径/冷角落 → **"最大的 20 个文件 × 近 6 个月改动最频繁的 20 个文件，交集就是债务藏身处"** → 动笔前必须写 1-2 段架构心智模型；若与 README 矛盾，本身就是 finding。
2. **Phase 2 按九个维度审计**：架构衰变/一致性腐蚀/类型契约/测试债/依赖配置/性能/错误处理可观测/安全卫生/文档漂移。每个维度给出具体信号（如 god files >500 LOC）。
3. **Phase 3 交付物**：固定结构——10 条以内执行摘要、架构心智模型、findings 表（ID|类别|file:line|严重度|工作量|描述|建议，目标 30-80 条）、Top 5 "只修一个就修这个"、快速赢、**必填的"看着糟但其实没问题"**、给维护者的开放问题。

防浅尝辄止的手段（作者在 Philosophy 节自己解释了为什么）：
- **"Forced orientation before judgment"**：没有心智模型前的任何 finding 都只是模式匹配。
- **file:line 引用是最大质量杠杆**："没有引用的 finding 是氛围（vibe），氛围修不了。"
- **必填"looks bad but is actually fine"章节**：逼模型列出"考虑过要标记但决定不标"的判断及理由，"该节为空说明你没看够"。
- 明令禁止：推荐重写、注水凑数、谄媚式"总体良好"开场白。
- 大仓库 >50k LOC：按模块并行派子代理，每个子代理带维度清单 + 引用要求 + 200 条 finding 上限，主代理合并去重排序。
- **Repeat-run 模式**：已存在审计文件则先读，标 RESOLVED/NEW，使文档成为随时间追踪的活文档。
- 工具建议按技术栈并行跑（knip/madge/ruff/mypy/cargo machete…），工具缺失记录后继续，不阻塞。

**对自写 skill 可抄什么**：几乎整个骨架——"Orient 阶段不可跳过 + git churn 交集定位热点 + file:line 强制引用 + 必填反例章节 + 禁止重写建议/注水 + 大仓库子代理分治 + 活文档增量模式"。这是把"理解项目"变成可验收流程的最佳单文件参考。

### 2.2 Egonex-AI/Understand-Anything — 代码库知识图插件（深读）

- 仓库：https://github.com/Egonex-AI/Understand-Anything — **81,574 stars**（API 实测；README 有中文版 READMEs/README.zh-CN.md）。简介："Graphs that teach > graphs that impress."
- 结构：`understand-anything-plugin/skills/` 下 9 个子 skill（understand / understand-domain / understand-explain / understand-knowledge / understand-dashboard / understand-diff / understand-chat / understand-figma / understand-onboard），`agents/` 下 10 个专职子代理（project-scanner、file-analyzer、architecture-analyzer、domain-analyzer、tour-builder、graph-reviewer 等）。
- 抓取并深读了三个文件：

**(a) skills/understand/SKILL.md（904 行，主 skill）**
- 七阶段管线：Phase 0 预检 → 0.5 ignore 配置（生成 `.understandignore`，**等用户确认才继续**）→ 1 SCAN（子代理扫描文件/语言/框架/import 图；**>100 文件要用户确认**）→ 1.5 BATCH（确定性脚本按 import 依赖算语义分批）→ 2 ANALYZE（每批一个子代理写 summary/tags/复杂度）→ 3 组装校验 → 4 ARCHITECTURE（分层）→ 5 TOUR（导览路径）→ 6 LLM graph-reviewer 复审 → 7 SAVE。
- 产物：`.ua/knowledge-graph.json`（13 种节点类型、26 种边类型、权重约定），含 `gitCommitHash` 元数据。
- 亮点：
  - **确定性/LLM 分工**：node 脚本（compute-batches.mjs、extract-import-map.mjs、prepare-incremental.mjs）干所有确定性的脏活，LLM 只做语义概括——"cheap Python preprocessing → expensive LLM gets a clean, small input → better results for less cost"。
  - **增量更新门控**：用 gitCommitHash + 结构指纹 diff 决定 SKIP / PARTIAL_UPDATE / ARCHITECTURE_UPDATE / FULL_UPDATE，纯注释改动零 LLM token。
  - **新鲜度校验**：所有下游 skill 使用图前先 `git diff $GRAPH_COMMIT HEAD -- .` 判断图是否过期，过期就警告建议重建（monorepo 用 `-- .` pathspec 防误判）。
  - 全程进度播报（`[Phase 2/7] Analyzing batch X/N...`）。
  - 防注入：把 README/manifest 当不可信数据，只用于推断事实。

**(b) agents/architecture-analyzer.md（481 行，分层分析子代理）**
- 任务：给每个文件分配唯一架构层（3-10 层）。**两步走**：第一步让 agent 自己写并执行一个脚本，从 import 图和路径确定性计算 8 类结构信号（目录分组、fan-in/fan-out、组间 import 频率矩阵、组内密度、跨类型依赖矩阵、目录名→架构模式对照表、部署拓扑检测、数据管道检测）；第二步 LLM 基于这些数字做语义分层。
- 这个"先跑脚本算结构统计、再让 LLM 解读"的模式，是整个项目防幻觉的底座。

**(c) skills/understand-domain/SKILL.md（160 行）+ skills/understand-explain/SKILL.md（73 行）**
- domain：有图则派生（便宜），无图则轻量扫描（`extract-domain-context.py` 产出文件树+入口点+签名），再派 domain-analyzer 子代理，产物 domain-graph.json，校验失败"log warnings but save what's valid"（容错保存）。
- explain：**"用 Grep 在 JSON 里搜相关条目，别整图读入上下文"**；找目标节点 → 找出入边 → 读邻接节点 → 定位所属层 → 最后才读源文件 → 按"架构角色/内部结构/外部连接/数据流"四段讲解。

**对自写 skill 可抄什么**：① 确定性脚本 + LLM 语义层的两层分工；② 产物落盘成结构化 JSON/MD + gitCommitHash 新鲜度门控，让"理解"可增量、可复用；③ 多 skill 家族（先建图 → domain/explain/dashboard 各取所需）而不是一个巨型 skill；④ 分层 agent 提示词里"先脚本统计再解读"的写法；⑤ >100 文件先确认规模、进度播报、容错保存。

### 2.3 alexanderop/walkthrough — 交互式架构导览 HTML（深读）

- 仓库：https://github.com/alexanderop/walkthrough — **130 stars**（API 实测）。简介：生成带可点击 Mermaid 图的自包含 HTML walkthrough，解释功能/流程/架构/表结构。灵感来自 Amp 的 Shareable Walkthroughs。
- 抓取：`skills/walkthrough/skill.md`（273 行）+ `references/html-patterns.md` 存在（渐进加载的辅助文件范式）。
- 流程：
  1. 定范围（功能流/数据流/架构总览/请求生命周期/DB schema），模糊则只问一个澄清问题。
  2. **自己最多做 1-2 次 Glob/Grep 圈定范围，然后并行派 2-4 个 Explore 子代理**，主上下文保持干净留给 HTML 生成。给子代理固定格式的 NODE 报告模板（label/file/purpose/connects_to/key_snippet+lang），**强制每个节点带 1-5 行真实代码片段**——"Always read real source files before generating. Never fabricate code paths."
  3. 子代理返回后**禁止再读文件**，直接综合成节点表+边表+2-4 个分组。
  4. 硬约束：**5-12 个节点（最少 5 条是 strict），节点是概念不是函数**，超了就合并；边标签用普通动词（triggers/feeds into/produces）不用 API 名。
  5. 产出 `walkthrough-{topic}.html`（TL;DR 卡片 + 可点击 Mermaid + 节点详情面板 + 图例），生成前过一遍 **Quality Checklist**（每节点映射真实文件、代码片段非编造、路径正确、图准确反映真实代码流）。

**对自写 skill 可抄什么**：① "主代理只编排、探索外包给并行子代理、拿到报告后禁止再碰源码"的上下文预算纪律；② NODE 结构化报告模板（purpose/connects_to/key_snippet）可直接搬；③ 5-12 概念节点的硬上限强制"压缩成心智模型"而非照抄目录树；④ 产出前 checklist 逐项自查（每条结论对应真实文件）。

### 2.4 Graphify-Labs/graphify — 代码库→知识图 skill（工具+skill 混合）

- 仓库：https://github.com/Graphify-Labs/graphify — **114,980 stars**（API 实测）。YC S26。`/graphify` 一条命令把代码/文档/PDF 映射成可查询知识图。
- 抓取：README.md（939 行）+ 仓库树核实（graphify/skills/agents/references/{extraction-spec,query,update...}.md 为各平台 skill 的引用文件；skill-claude.md 等平台注册文件在根目录）。
- 技术要点（README 自述）：代码用 **tree-sitter AST 确定性解析、零 LLM、全本地**（文档/PDF 才走语义通道）；~40 语言的 calls/imports/inherits 跨文件解析；**每条边打 `EXTRACTED`（源码显式）或 `INFERRED`（推理解析）置信标签**；Leiden 社区检测切子系统并自动命名；产物 graph.html（力导向可点击）+ GRAPH_REPORT.md（关键概念/意外连接/建议提问）+ graph.json（免重读文件即可查询）；`graphify path A B` 追溯任意两概念的连接路径。
- 不是向量索引："a real graph you traverse"。

**对自写 skill 可抄什么**：① **EXTRACTED/INFERRED 二值置信标注**——分析报告里区分"源码里读到的"和"我推断的"，成本极低、可信度大增，这是所有分析类 skill 都该抄的一手；② 产出三件套：可视化 HTML（给人）+ 机器可查 JSON + 一页 REPORT.md（亮点与建议问题）；③ 能确定性解析的绝不劳烦 LLM。

### 2.5 WoJiSama/skill-based-architecture — 把项目规则蒸馏成 skill 目录

- 仓库：https://github.com/WoJiSama/skill-based-architecture — **554 stars**（API 实测）
- 抓取：根目录 SKILL.md（134 行）+ `.cursor/skills/skill-based-architecture/SKILL.md`（34 行，注册入口）。
- 定位：指向任意代码库，把项目的规则/工作流/踩坑教训蒸馏成 `skills/<name>/{SKILL.md,rules/,workflows/,references/,scripts/}` 目录，成为所有 AI agent 的单一事实源。
- 亮点：**"Evidence-Selected Structure"**——单文件/folder-light/full 三档由目标证据内部分档，用户不选；**按抽象层次拆分**：抽象方法论 → `architecture/`，**代码地图 → `references/`**，风格约定 → `conventions/`，模块雷区 → `gotchas/`；SKILL.md 超 ~150 行就该拆；`.cursor` 入口的 description 必须与根 SKILL.md 逐字一致并用脚本防漂移。

**对自写 skill 可抄什么**：如果自写 skill 的产物是"项目文档/知识沉淀"，可抄它"代码地图与结论分离存放（references/ vs 正文）+ 按证据决定结构繁简 + 一致性用脚本守护"的思路；还有用技能目录本身作为理解成果载体的范式。

### 2.6 其他核实过的社区条目（简）

| 仓库 | star | 说明（均已 API 核实存在） |
|---|---|---|
| zarazhangrui/codebase-to-course | 5,517 | 把代码库变成单页交互 HTML 课程（面向非程序员）；启发：理解产物可以按"课程"叙事组织 |
| bevibing/tutor-skills | 1,131 | 把文档/代码库变成 Obsidian 学习库+测验；启发：以"教会人"为目标组织理解产出 |
| giancarloerra/SocratiCode | 3,286 | 企业级（40m+ LOC）代码智能插件/MCP：语义搜索+多语言依赖图+符号级影响分析；启发：大库要靠索引而非通读 |
| fallow-rs/fallow-skills | 118 | 教 agent 找死代码/循环依赖/复杂度热点/架构漂移；启发："架构漂移检测"是理解类 skill 的好子任务 |
| agustinvillegas/repomap | 8 | opencode skill：本地确定性分析器出 modules/imports/edges JSON，再让 LLM 补 detectedRole/模式标注（SKILL.md 已抓取，326 行）；与 §2.2 同款"确定性先行"模式的最小实现 |
| nandnijaiswal/wtfismyrepo | 16 | CLI+skill：import 图 + PageRank 找枢纽文件 + git churn 脆弱度评分；把 Aider 思想搬进 skill 的小型样板 |
| googlarz/codebase-onboarding | 1 | "join / return / audit" 三模式上手陌生代码库；三模式分档值得借鉴 |
| NVIDIA/skills 的 trtllm-codebase-exploration | — | VoltAgent README（L1588）收录为"Systematic approach to exploring the TensorRT-LLM codebase"；**但该路径在 NVIDIA/skills HEAD 已 404（实测 contents API 无此目录），应已被移动/下架**，未深读 |

---

## 3. Aider repo map — "给 LLM 喂项目结构"的代表作

- 仓库：https://github.com/Aider-AI/aider — **34,469 stars**（2026-09-06 API 实测）
- 文档：https://aider.chat/docs/repomap.html（已抓取全文）
- 源码：`aider/repomap.py`（27,306 字节，已抓取读关键段）

**算法思想**（文档 + 源码印证）：
1. **repo map 是什么**：仓库文件清单 + 每个文件里最重要的类/函数及其签名定义行。LLM 既能据此直接调用其他模块的 API，也能据此决定"还需要看哪个文件"。
2. **符号抽取**：tree-sitter 对每个文件抽取 tags（defines/references 的标识符），带磁盘缓存（`.aider.tags.cache.vN`）。
3. **建图**：有向图，节点是文件；对每个标识符，从"引用它的文件"连边到"定义它的文件"。**边权 = 基础乘数 × sqrt(引用次数)**。乘数规则（源码 L480-506 实测）：在聊天中被用户提及的标识符 ×10；snake/camel/kebab 且长度 ≥8（像"有意义的名字"）×10；下划线开头（私有）×0.1；定义处 >5 个（烂大街名字）×0.1；**已在聊天中的文件作为引用方 ×50**。
4. **排序**：networkx PageRank，且用**个性化 PageRank**（personalization+dangling 设为聊天中文件），让排名向当前任务相关文件倾斜；再把每个文件的 rank 按边权比例分摊到出边，得到"标识符定义"的排名，排名高的定义行才进地图。
5. **预算裁剪**：默认 `--map-tokens` 1k token 预算内从高到低装填；聊天中没有文件时自动扩图（"需要理解整个 repo 的时刻"）。

**对自写 skill 可抄什么**：不用真写 PageRank，但其三个思想极易降维搬进纯提示词 skill——① **"地图只放被引用最多的定义，不放全部"**（用 grep 统计标识符出现频次近似）；② **"用户当前关注点（聊天/任务相关文件）作为排序种子"**；③ **token 预算硬上限**逼出"最关键签名行"级别的压缩输出。另可抄 nandnijaiswal/wtfismyrepo 的近似实现：import 图 + PageRank 找"枢纽文件"作为分析切入点。

---

## 4. 其他值得看的实现（只记录思想）

- **Sourcegraph 代码导航**：SCIP（Source Code Intelligence Protocol，github.com/scip-code/scip）——语言无关的代码索引格式，每语言一个 indexer 离线产出"符号定义/引用"紧凑索引，查询时零重分析，支持跨仓库 go-to-definition/find-references（docs.sourcegraph.com/user/code_intelligence；sourcegraph.com/blog/announcing-scip）。**启发**：分析 skill 若要精确调用图，优先复用现成索引器（SCIP/ctags/LSIF）而不是让 LLM 目测。
- **tirth8205/code-review-graph**：**31,198 stars**（搜索 API 实测）。本地持久化代码智能图（MCP+CLI），让 AI 工具"只读相关部分"，宣称显著降低 review/大库工作流的上下文消耗。与 Understand-Anything 同赛道，但以"省 token 读取"而非"教学图"为卖点。
- **OpenAI 官方 skills 仓库**（经 VoltAgent README 收录核实条目）：`openai/security-threat-model`——"Generate repo-specific threat models identifying trust boundaries"。**启发**：信任边界识别是架构理解的一个高质量切面。
- **muratcankoylan/Agent-Skills-for-Context-Engineering**：**17,922 stars**（API 实测）。context-fundamentals 等 skill 讲上下文解剖；理解类 skill 本质是"给 agent 制造好上下文"，其分诊思路可参考。
- **WordPress/wordpress-router**（VoltAgent 收录）："Classifies WordPress repos and routes to the right workflow"——**先给仓库分类、再路由到对应分析流程**，是应对"不同项目形态"的轻量方案。

---

## 5. "SKILL.md + architecture/codebase" 高星合集核实

GitHub `topic:claude-skills` 按 star 排序（API 实测 2026-09-06），成规模合集确实存在，且不止 VoltAgent 一家：

| 合集 | star | 与本主题的关系 |
|---|---|---|
| thedotmack/claude-mem | 93,266 | 会话记忆压缩（非本主题，但"沉淀理解成果"思想相关） |
| **Egonex-AI/Understand-Anything** | 81,574 | 本主题最成体系的 skill 插件（见 §2.2） |
| Graphify-Labs/graphify | 114,980 | 检索词命中最高的代码库图谱 skill（见 §2.4） |
| muratcankoylan/Agent-Skills-for-Context-Engineering | 17,922 | 上下文工程合集 |
| wshobson/agents | 39,443 | 202 个领域 agent（含 architecture 角色），README 已 grep 核实有 architecture 条目，但为 agent 提示词而非 SKILL.md 格式 |
| VoltAgent/awesome-agent-skills | 33,781 | 1000+ skill 索引（README 2052 行已抓取，本报告多条线索来自它） |
| alirezarezvani/claude-skills | 25,567 | 通用 skill 合集 |
| travisvn/awesome-claude-skills | 14,974 | 另一大 awesome 合集 |
| titanwings/distilly | 24,369 | "把同事的思维方式蒸馏成 skill"（原 Colleague Skill），中文社区项目 |

注：GitHub 代码级搜索（q="SKILL.md"+architecture）需登录 token，本次以 `search/repositories` + 两个 awesome 合集 README 交叉核实，结论一致：**"架构分析"没有官方 skill，社区最大实现是知识图路线（Understand-Anything/Graphify），单文件最佳实践是 tech-debt-skill 与 walkthrough。**

---

## 6. 综合建议：自写"架构分析 skill"应抄的 6 个机制

1. **两阶段认知**（tech-debt + Understand-Anything 共有）：先强制"定向"（README+manifest+目录树+git churn 交集→写出心智模型），才允许进入细节分析；"先于理解形成观点"被明确列为第一禁忌。
2. **确定性脚本与 LLM 分工**：import 图、文件清单、频次统计、分批全部用脚本算，LLM 只做语义概括与分层判断（UA 的 architecture-analyzer 甚至让 agent 现场写脚本算结构矩阵）。
3. **证据约束**：每条结论必须带 file:line；区分 EXTRACTED（读到的）与 INFERRED（推断的）；产物 checklist 逐条验证"对应真实文件、非编造"。
4. **压缩输出 + 硬约束**：5-12 个概念节点/30-80 条 findings 的区间约束；必含"看着糟但其实没问题"与"开放问题"两节防装懂；禁注水、禁重写建议、禁谄媚。
5. **产物落盘 + 新鲜度**：输出结构化文件（MD 报告或 JSON 图）+ gitCommitHash，重跑时可增量标 RESOLVED/NEW，让理解成为活文档。
6. **规模分治**：>100 文件先与用户确认范围；大库按模块并行子代理（各带维度清单+引用要求+上限），主代理只合并排序。
