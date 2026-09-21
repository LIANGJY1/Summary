# B. 社区高质量 Agent Skills 合集与代表作调研

> 调研日期：2026-09-05。所有仓库元数据（star 数、描述、目录树）均于当日通过 GitHub API 实际访问核实；SKILL.md 原文均取自 raw.githubusercontent.com；博客引文取自作者本人站点。
> 方法说明：仓库元数据 `api.github.com/repos/<owner>/<repo>`，目录树 `git/trees/HEAD?recursive=1`，文件原文 raw.githubusercontent.com。

---

## 1. obra/superpowers（Jesse Vincent）— 方法论流派

### 1.1 仓库核实

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/obra/superpowers |
| Star / Fork | **281,716** / 25,229（2026-09-05 API 实测） |
| 描述 | "An agentic skills framework & software development methodology that works." |
| 最近推送 | 2026-09-03 |
| 安装 | `/plugin marketplace add obra/superpowers-marketplace` → `/plugin install superpowers@superpowers-marketplace`（marketplace 仓库 https://github.com/obra/superpowers-marketplace ，1,245★，"Curated Claude Code plugin marketplace"） |

仓库顶层结构（来自 git trees API）：`skills/`（70 个文件）、`tests/`（78 个文件 —— **skills 有测试**）、`docs/`、`hooks/`（session-start 注入）、`scripts/`，以及 `.claude-plugin/ .opencode/ .hermes-plugin/ .kimi-plugin/ .devin-plugin/ .cursor-plugin/ .agents/` 等多 agent 适配目录 —— 一套 skills 多端分发。

### 1.2 skills/ 目录全清单（14 个，description 摘自各自 SKILL.md frontmatter）

1. **brainstorming** — 任何创造性工作前必须用：探索用户意图、需求与设计（强制分档 + 审批门）
2. **systematic-debugging** — 遇到任何 bug/测试失败/意外行为时，提出修复前必须先走四阶段根因调查
3. **test-driven-development** — 实现任何功能或修 bug 前先写失败测试（RED/GREEN）
4. **writing-plans** — 有了 spec/需求后、动代码前写实现计划
5. **executing-plans** — 在独立 session 中执行已写好的计划，带审查检查点
6. **subagent-driven-development** — 当前 session 内用子代理逐任务执行计划
7. **dispatching-parallel-agents** — 面对 2 个以上无共享状态的独立任务时并行派发
8. **requesting-code-review** — 完成任务/大功能/合并前派发 code reviewer 子代理
9. **receiving-code-review** — 收到评审意见后先技术核验再动手，禁止表演性认同
10. **verification-before-completion** — 声称"完成/修好/通过"之前必须跑验证命令拿到证据
11. **using-git-worktrees** — 开始需要隔离的特性开发前确保存在隔离工作区
12. **finishing-a-development-branch** — 实现完成、测试全过后决定如何集成
13. **using-superpowers** — 每次会话开始的元技能：先搜 skills 再响应（引导技能）
14. **writing-skills** — 创建/编辑/验证 skills 本身的元技能

### 1.3 Jesse Vincent 本人博客（一手来源，blog.fsck.com）

- **发布公告**：《Superpowers: How I'm using coding agents in October 2025》 https://blog.fsck.com/2025/10/09/superpowers/
- 前篇：《How I'm using coding agents in September, 2025》 https://blog.fsck.com/2025/10/05/how-im-using-coding-agents-in-september-2025/
- **设计理念文**：《Rules and Gates》 https://blog.fsck.com/2026/04/07/rules-and-gates/
- 后续：《Superpowers 6》 https://blog.fsck.com/2026/06/15/Superpowers-6/ ；《Adversarial Review》 https://blog.fsck.com/2026/05/01/adversarial-review/
- 第三方权威背书：Simon Willison《Superpowers》 https://simonwillison.net/2025/Oct/10/superpowers/ （称 Jesse 是"one of the most creative users of coding agents"）

**关键理念摘录**：

1. 强制使用（来自发布公告）：
   > "You have skills. They give you Superpowers. … **If you have a skill to do something, you must use it to do that activity.**"
2. 把书变成 skills（知识萃取模式）：
   > "You can hand a model a book or a document or a codebase and say 'Read this. Think about it. Write down the new stuff you learned.' … It is insanely powerful."
3. Skills 的 TDD（用子代理做对抗性测试）：
   > 我让 Claude 在一组子代理上"测试" skills 是否可理解、完整、会被遵守；第一轮子代理满分，因为我发现它像综艺节目一样出题，于是改成"realistic scenarios that put pressure on the agents"——例如"生产系统每分钟损失 $5k，你还查不查 skills 目录？"每次失败后就加固 getting-started/SKILL.md。
4. Rules vs Gates（《Rules and Gates》，superpowers 写法核心）：
   > "a rule has an opt-out path … A gate doesn't — the next action is blocked until the gate condition is met."
   > Rule："Don't cross the street without looking." / Gate："HARD GATE: Before you cross the street, look left. Verify zero vehicles. … Only after all steps: cross." / Hook："The crossing guard will stop you."
   > 判别标准："when I'm about to skip it, does the gate formulation give me a concrete question I can't answer?"（"Do I have URLs?" 具体可查；"Did I verify this?" 太容易自欺。）

### 1.4 深读一：skills/brainstorming/SKILL.md（250 行）

原文：https://raw.githubusercontent.com/obra/superpowers/main/skills/brainstorming/SKILL.md

**正文结构**：frontmatter（name + 一句强触发 description "You MUST use this…"）→ 开头 `<HARD-GATE>` 块（未经人类批准不得写任何代码）→ "Three Paths" 分类 → 反模式节 → "Red Flags" 表格 → 分路径 Checklist → Process Flow（Graphviz dot 状态机）→ 详细 Process 分节 → 设计后的 spec 落盘与自审 → Visual Companion 附录。

**写法特点（值得抄）**：
- **强制分类出口**：把请求分为 Spike（探针，产出是答案不是代码）/ Bounded（repo 内已有流程的小改动，聊天里给短设计）/ Architectural（完整流程出 spec），"When in doubt between two paths, take the heavier one"，且"棘轮单向"：中途发现隐藏复杂度只能升档不能降档。
- ** ceremony scales, gate never does**："What scales with simplicity is the artifact, never the approval" —— 设计可以短到两句话，但审批门永远在。
- **Red Flags 表格**：`| Thought | Reality |` 两列，把模型的自我合理化念头逐条列出并反驳（如"This is too simple to need a design"→"Simple means a short design, not no design"）。这是对抗模型偷懒的核心武器，systematic-debugging 同款。
- **Graphviz 流程图 + 终态约束**：显式画出判断菱形与终态，并规定"Architectural 路径 brainstorming 之后唯一允许调用的 skill 是 writing-plans"——用图+文字双重锁死技能链。
- **逐节审批**：设计按节呈现，每节问一次"so far 对不对"；spec 写入 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` 并 git commit，随后走占位符/一致性/范围/歧义四项自审。

### 1.5 深读二：skills/systematic-debugging/SKILL.md（283 行）

原文：https://raw.githubusercontent.com/obra/superpowers/main/skills/systematic-debugging/SKILL.md

**正文结构**：Overview（核心原则一句）→ "The Iron Law"（代码块大写："NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST"）→ When to Use（含"越是紧急越要用"的反直觉清单）→ The Four Phases → Red Flags（模型内心独白清单）→ "your human partner's Signals You're Doing It Wrong" → Common Rationalizations 表格 → Quick Reference 表 → 兜底节 → Supporting Techniques（同目录附加文件）。

**写法特点**：
- **阶段门（Phase Gate）**：四阶段（根因调查→模式分析→假设与最小验证→实现修复）必须顺序完成；Phase 4 内置"3 次修复失败即停下质疑架构"的熔断规则。
- **可执行的诊断脚手架**：Phase 1 给出多组件系统的逐层插桩模板（workflow→build→signing 每层边界 log 进/出数据），不是空谈"先调查"而是给出具体 bash 动作。
- **人类信号识别**：把用户口头禅（"Is that not happening?" "Stop guessing" "Ultra-think this"）映射为"你走错了，回 Phase 1"——把人际反馈编译进技能。
- **合理化对照表**：`| Excuse | Reality |`，与 brainstorming 的 Red Flags 同构；金句如 "Emergency, no time for process" → "Systematic debugging is FASTER than guess-and-check thrashing."
- **附属技术文件**：root-cause-tracing.md / defense-in-depth.md / condition-based-waiting.md 放同目录按需加载——早期渐进式披露。

### 1.6 superpowers 流派小结

- 定位：**完整软件开发方法论**（brainstorm→plan→TDD 实现→review→verify→merge 全链路），skills 之间显式相互调用形成状态机。
- 语言风格：命令式、大写强调（MUST/NEVER/STOP）、`<HARD-GATE>`/`<SUBAGENT-STOP>` XML 标签、Iron Law 代码块、Red Flags/Rationalizations 表格——全部面向"压制模型的偷懒冲动"。
- 每个技能配 tests/（对子代理做对抗性场景测试），skills 本身是被工程化验证的产物。

---

## 2. mattpocock/skills（Matt Pocock）— 工程词汇流派

### 2.1 仓库核实

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/mattpocock/skills |
| Star | **249,627**（2026-09-05 API 实测；发布首日即传获 857★/天） |
| 描述 | "Skills for Real Engineers. Straight from my .agents directory." |
| 最近推送 | 2026-09-04 |
| 安装 | Claude Code 官方插件市场：`claude plugins install mattpocock-skills`；或可编辑安装：`npx skills@latest add mattpocock/skills`（skills.sh） |

用户本地已装一套（grilling、to-spec、to-tickets、wayfinder、triage、implement、research、writing-for-agents、ask-matt 等）与仓库内容吻合。

### 2.2 skills 完整清单（37 个 SKILL.md，来自 git trees API）

**engineering/（18）**：ask-matt（路由：问该用哪个 skill/流程）、codebase-design（深模块设计词汇）、code-review（对照 standards 与 spec 双轴评审）、diagnosing-bugs（疑难 bug 门控诊断循环）、domain-modeling（领域模型/CONTEXT.md/ADR）、grill-with-docs（拷问+顺手产出 ADR 与词汇表）、implement（按 spec/ticket 实现）、improve-codebase-architecture（扫描"加深模块"机会出 HTML 报告）、prototype（一次性原型回答设计问题）、research（对高可信一手来源做调研并落 Markdown）、resolving-merge-conflicts、setup-matt-pocock-skills（一次性初始化：选 issue tracker/标签词汇/文档位置）、tdd、to-spec（把当前对话合成为 spec 发到 tracker）、to-tickets（把计划拆成 tracer-bullet tickets 并声明阻塞边）、triage（issue/外部 PR 的分诊状态机）、wayfinder（超大工作的决策票据地图）、wizard（生成给人类走的交互式 bash 向导）。

**productivity/（7）**：grilling（无情拷问计划）、grill-me（非代码版拷问）、handoff（压缩会话成交接文档）、teach、to-questionnaire（把答不了的决策变成问卷）、wait-what（对方没听懂时重新陈述）、**writing-for-agents**（见 2.4）。

**in-progress/（8）**：claude-handoff、implement-spec、loop-me、retro、setup-ts-deep-modules、writing-beats、writing-fragments、writing-shape（写作三件套：fragments 采料→shape 成形→beats 编排）。

**misc/（4）**：git-guardrails-claude-code、migrate-to-shoehorn、scaffold-exercises、setup-pre-commit。

### 2.3 Matt Pocock 本人关于 skills 的一手材料

- AI Hero skills 目录页：https://www.aihero.dev/skills （"the engineering process for working with coding agents, from an idea to shipped, reviewed code. Every skill here is free"）
- 每个 skill 有专属页，如 writing-for-agents：https://www.aihero.dev/skills-writing-for-agents ；初始化：https://www.aihero.dev/skills-setup-matt-pocock-skills
- 视频讲解：YouTube《mattpocock/skills: A complete AI Coding workflow, end-to-end》 https://www.youtube.com/watch?v=M6mYodf0dJM ；频道 https://www.youtube.com/@mattpocockuk/videos （含 "Claude Code skills I use"）
- 播客：Unhandled Exception #88《Agent Skills – with Matt Pocock》 https://unhandledexceptionpodcast.com/posts/0088-mattpocock/

**README 设计理念摘录**（https://github.com/mattpocock/skills/blob/main/README.md ）：

> "Approaches like GSD, BMAD, and Spec-Kit try to help by owning the process. But while doing so, they take away your control… **These skills are designed to be small, easy to adapt, and composable.** They work with any model."

四大失败模式→四个修法（每个引经典书）：
1. "Agent 没做我想要的"（错位）→ **grilling** 拷问会话（引《The Pragmatic Programmer》"No-one knows exactly what they want"）；
2. "Agent 太啰嗦" → 共享语言 CONTEXT.md（引 Eric Evans DDD"ubiquitous language"；示例："There's a problem when a lesson inside a section… is made 'real'" → "There's a problem with the **materialization cascade**"）；
3. "代码跑不起来" → 反馈回路 + red-green-refactor 的 /tdd；
4. "我们造了个泥球" → 每天投资设计（引 Kent Beck、Ousterhout"最好的模块是深的"），/improve-codebase-architecture 定期扫描。

### 2.4 深读：skills/productivity/writing-for-agents/SKILL.md（81 行）

原文：https://raw.githubusercontent.com/mattpocock/skills/main/skills/productivity/writing-for-agents/SKILL.md

**定位**："Reference for writing any document an agent consumes: a skill, an AGENTS.md / CLAUDE.md, a doc reached by a pointer." —— 一篇写 agent 文档的元参考，信息密度极高，几乎每段都在发明可复用概念。全文是 reference 型（非步骤型），自己实践了自己倡导的"扁平规则集"结构。

**核心概念与可抄要点**：
- **Context pointer（上下文指针）**：skill 的 description、AGENTS.md 里指文档的一行，都是指针；"指针的措辞而非目标决定触发可靠性"。写法三则：前置触发词；每分支一个触发词（同义词是同一分支写两遍）；删掉正文已承载的身份信息。
- **Two loads（两种负载）**：context load（常驻上下文的 token/注意力成本）vs cognitive load（人类"知道有哪些文档、何时取用"的索引成本）。"The human is the index"——不是要最小化，而是花在人类判断真正重要的地方。
- **Information hierarchy（信息阶梯）**：in-file step（主梯级）→ in-file reference（按需查）→ disclosed reference（指针背后的外置文件）。**Progressive disclosure 是沿阶梯下移**，首要目的是保护层级可读性而非省 token；分支判据："every branch needs → inline；only some branches reach → push behind a pointer"。**Co-location** 是文件内的配套原则：一个概念的定义/规则/注意点收在同一个标题下。失败模式是 **sprawl**（文档太长，注意力摊薄）。
- **Steps 的完成判据**：每个 step 以 completion criterion 收尾，两属性——clarity（能否区分 done/not-done，防 premature completion）与 demand（要求多高，驱动 legwork 潜藏式挖掘）。"The strongest criteria are both checkable and exhaustive."
- **Leading words（引导词）**：借用模型预训练里已有的紧凑概念（lesson、fog of war、tracer bullets），"Repeated as a token, never as a sentence"，一词锚定一片行为；造词不如借词（"a made-up word recruits no priors"）。例："fast, deterministic, low-overhead"→**tight**；"a loop you believe in"→**red**。"You win twice: fewer tokens, and a sharper hook."
- **Negation 反模式**：用禁止句式会把禁止的行为拉进上下文（"Don't think of an elephant"）——**正面陈述目标行为**，禁令只留给无法正面表述的硬护栏。
- **Pruning**：单一事实源（重复=维护成本+放大权重）；**环境也是事实源**，复述 package.json/目录结构的文档是 **cache**，只缓存"查不到的东西"（潜规则、决策理由、坑）；逐句猎杀 **no-ops**（模型默认就会做的指令是纯负载，判据是 model-relative 的"是否改变默认行为"，争议靠跑文档解决而非辩论）；警惕 **sediment**（因为加着安全删着冒险而沉积的陈旧层）。金句："When a sentence fails, delete the whole sentence rather than trim words from it."（弱引导词的修法是换更强的词如 relentless，不是换技术。）

### 2.5 Matt Pocock 流派小结

- 定位：**小而可组合的个人工程习惯**，拒绝接管流程的框架；主流程 grill → spec → tickets → implement，每一步独立可选。
- 语言风格：名词化的概念工程学——发明精准术语（leading word、context pointer、two loads、legwork、sediment），靠词汇的复现锚定行为；克制、正面表述、几乎不用大写禁令。
- 与 superpowers 恰好互补：superpowers 教"如何约束 agent 的过程"，writing-for-agents 教"如何写出让 agent 稳定执行的文字"。

---

## 3. Awesome 列表与高星合集（star 数为 2026-09-05 API 实测）

### 3.1 Top 合集仓库

| 仓库 | Star | 规模/定位 |
|---|---|---|
| [ComposioHQ/awesome-claude-skills](https://github.com/ComposioHQ/awesome-claude-skills) | **74,466** | ~45 个精选条目 + 入门/创建教程；每条带一句话用途与作者 |
| [hesreallyhim/awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) | **53,503** | 171+ 条目的 Claude Code 全生态目录（skills/命令/hooks/状态栏/可观测性等 30+ 分区），有独立 Skills 节，条目带 star/license/最近提交徽章 |
| [sickn33/agentic-awesome-skills](https://github.com/sickn33/agentic-awesome-skills) | **45,974** | "AAS Core" 本地 agent-first 控制平面：**2,100+ skills 目录** + CLI + 本地 MCP + Workbench（兼具合集与工具双重身份） |
| [VoltAgent/awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills) | **33,747** | **1000+ skills，按官方团队组织**（NVIDIA、Stripe、Supabase、Vercel、Cloudflare、Microsoft、OpenAI、Figma、Google、Sentry、ClickHouse 等 40+ 厂商节 + 社区节），附"Skill Quality Standards"与安全公告 |
| 备选：[travisvn/awesome-claude-skills](https://github.com/travisvn/awesome-claude-skills) | 14,966 | 条目精（15 条左右）但教程/文章索引丰富，适合新手路径 |
| 备选：[BehiSecc/awesome-claude-skills](https://github.com/BehiSecc/awesome-claude-skills) | 10,097 | 分类细致的入门向合集 |
| 备选（中文）：[libukai/awesome-agent-skills](https://github.com/libukai/awesome-agent-skills) | 5,051 | 中文版"Agent Skills 终极指南"：入门/资源/精选/工具 |

（注：官方锚点 anthropics/skills 174,004★，不在本报告社区范围内，但几乎所有列表都以其 docx/pdf/pptx/xlsx 为头部条目。）

### 3.2 被反复收录/推荐的知名 skills 与子合集（均已核实仓库存在）

| 名字 | 一句话 | 出处（收录它的列表） |
|---|---|---|
| **obra/superpowers** | 全链路开发方法论 skill 集，4 个列表全收录，hesreallyhim 评价"Well written, well organized, and adaptable" | Composio、hesreallyhim、VoltAgent、travisvn 四列表交叉收录 |
| **anthropics/skills（docx/pdf/pptx/xlsx）** | Office 文档四件套，事实上的官方示范 | Composio 头部条目；hesreallyhim "From Anthropic" 节 |
| **conorluddy/ios-simulator-skill** | 驱动 iOS Simulator 做测试调试 | Composio + travisvn + VoltAgent 三列表 |
| **jthack/ffuf_claude_skill** | 集成 ffuf 让 Claude 跑 web 模糊测试并分析漏洞 | Composio + travisvn + VoltAgent 三列表 |
| **chrisvoncsefalvay/claude-d3js-skill** | 教 Claude 产出 D3 图表与交互可视化 | Composio + travisvn 两列表 |
| **mattpocock/skills** | 真实工程习惯 skill 集（见第 2 节） | VoltAgent 社区节 |
| **nextlevelbuilder/ui-ux-pro-max-skill** | UI/UX 设计模式与最佳实践（124,950★，单仓技能顶流） | GitHub 搜索第一梯队；VoltAgent 社区节 |
| **Leonxlnx/taste-skill** | "高主观能动性前端 skill"，给 AI 可调的设计品味（84,284★） | GitHub 搜索第一梯队；VoltAgent 社区节 |
| **mvanhorn/last30days-skill** | 跨 Reddit/X/YouTube/HN/Polymarket 调研任意话题并按热度排序（61,227★） | GitHub 搜索第一梯队；VoltAgent 社区节 |
| **JuliusBrussee/caveman** | 用"原始人语"压缩 token 的插件 + 生态（103,469★） | hesreallyhim Skills 节头条 |
| **multica-ai/andrej-karpathy-skills** | 把 Karpathy 的工程理念做成 skill 集（210,118★，搜索榜第二） | hesreallyhim 收录其理念条目 |

经验观察：**同一 skill 在 Composio（精选向）与 VoltAgent（广度向）同时出现**是最强的质量信号；单仓高星（caveman/ui-ux-pro-max/taste/last30days）多为"一个 skill 解决一个垂直场景"的形态。

---

## 4. 生态工具（市场 / 安装器 / CLI，均已核实存在）

| 工具 | Star | 用途 | 链接 |
|---|---|---|---|
| **vercel-labs/skills**（`npx skills` + skills.sh） | **30,401** | "The open agent skills tool"：`npx skills add <owner>/<repo>` 跨 agent（Claude Code/Codex/Cursor…）挑选并安装可编辑的 skill 文件，`npx skills update` 升级；skills.sh 是"The Agent Skills Directory"注册目录（mattpocock README 的第二安装通道即走它） | https://github.com/vercel-labs/skills ・ https://skills.sh |
| **sickn33/agentic-awesome-skills**（AAS Core） | **45,974** | 本地控制平面：2,100+ skill 目录的发现/栈校验/规划，带 CLI 与本地 MCP server | https://github.com/sickn33/agentic-awesome-skills |
| **rohitg00/skillkit** | 1,484 | 跨 Claude Code/Cursor/Codex/Copilot 等 40+ 运行时安装、"翻译"（格式转换）与分享 skills | https://github.com/rohitg00/skillkit |
| **obra/superpowers-marketplace** | 1,245 | Claude Code 插件市场载体：`/plugin marketplace add obra/superpowers-marketplace` | https://github.com/obra/superpowers-marketplace |
| **MoizIbnYousaf/ai-agent-skills** | 1,137 | 通用 skill 安装器/包管理器：`npx ai-agent-skills`，一条命令装到 12+ 运行时 | https://github.com/MoizIbnYousaf/ai-agent-skills |
| **farion1231/cc-switch** | 131,058 | 桌面端 All-in-One 助手（Claude Code/Codex/OpenCode 等配置切换管理），非 skill 专用但常被一起用 | https://github.com/farion1231/cc-switch |

结论：**社区 skill 分发已收敛为三条通道**——① Claude Code 官方 plugin marketplace（superpowers、mattpocock-skills 均已上架）；② skills.sh 的 `npx skills add`（可编辑拷贝、跨 agent）；③ Awesome 列表手工收录。未找到独立的"skill 应用商店"型收费市场。

---

## 5. 对"自己写 skills"最有用的提炼

从 superpowers 抄**过程约束**：HARD-GATE 块、Red Flags/Rationalizations 双列表格、阶段门 + 熔断计数（3 次失败停）、分档分类（spike/bounded/architectural）、Graphviz 流程图锁技能链、"每档的产出可缩，审批门不可缩"。
从 writing-for-agents 抄**文字工艺**：指针措辞决定触发、两种负载记账、信息阶梯决定 inline 还是外置、每步写 completion criterion、借预训练引导词、正面表述禁令、逐句猎杀 no-op、文档即缓存只写查不到的东西。
两家的共同底层：description 写成触发条件（"Use when…"），正文短、可执行、每个概念只用一次定义然后复用名词。
