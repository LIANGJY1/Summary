# 全网高质量 Agent Skills 调研与写作指南

> 调研日期：2026-09-05 ｜ 目标：遍历分析全网高质量 Skill（SKILL.md 生态），提炼写法模式，支撑自写 skills 用于工作与学习
> 详细原始笔记见本目录 `sources/`（A 官方生态 / B 社区合集 / C 写法方法论 / D 本地盘点），本报告所有结论均可回溯至笔记中的出处。

---

## 0. TL;DR：给"想自己写 skill 的你"的 10 条军规

1. **先证明需要，再动笔**：没有这条指令 agent 就会做错才值得写。机械约束交给 hooks，项目约定放 AGENTS.md/CLAUDE.md，模型本来就会的知识零收益。
2. **description 是唯一的广告位**：启动时只有 name+description 常驻上下文，模型据此决定是否加载正文。第三人称、≤1024 字符，"功能一句话 + Use when 具体触发条件"，要写得略带推销性（模型天生倾向不触发）。
3. **触发词用真实用户语言**：真实报错文本、症状词、同义词、口语（"babysit CI" 而非 "监控 CI 状态"）；写负向触发（Don't use for...）；绝不概括工作流——description 写了流程摘要，agent 就照摘要走捷径、跳过正文。
4. **正文 <500 行**：Overview → When to Use（含 NOT）→ 核心模式 → Quick Reference → Common Mistakes；每个步骤带可检查的完成判据。
5. **确定性操作写进 scripts/**：脚本执行而非 agent 现场发挥（更可靠、省 token、跨次一致）；引用文件只拆一层深，>300 行顶部加目录。
6. **示例优于规则，默认值优于菜单**：给一个默认方案 + 逃生舱，不罗列 5 个可选库；解释 why 而非全大写吼 MUST。
7. **正向措辞**：先写目标行为；禁令会把被禁行为拉进上下文，只留给无法正面表述的硬护栏。
8. **TDD 铁律**：先跑无 skill 基线、逐字记录失败与 agent 的合理化借口，再写最小 skill；新借口冒出来就显式封堵，直到无新借口。
9. **触发评测工程化**：约 20 条评测 query（一半是 near-miss 反例）、每条跑 3 次、60/40 训练/验证切分、最多 5 轮改写 description。
10. **每句话交税**："Every skill is a tax"——逐句问"没有这句 agent 会错吗？"不会就删。只写环境里查不到的知识与坑。

---

## 1. Skill 是什么：格式规范速查

> 来源：Anthropic 官方文档与 anthropics/skills 仓库、agentskills.io 规范站、本地 skill-creator 插件。详见 `sources/A-官方生态.md`、`sources/C-写法方法论.md`。

### 1.1 目录结构

```
my-skill/
├── SKILL.md          # 必需：YAML frontmatter + Markdown 正文
├── references/       # 可选：按需阅读的参考文档
├── scripts/          # 可选：可执行的辅助脚本
└── assets/           # 可选：模板、fixtures 等产物资产
```

### 1.2 渐进式披露（Progressive Disclosure）三层成本模型

| 层 | 加载时机 | 成本 | 内容 |
|---|---|---|---|
| Index（元数据） | 常驻每次对话 | ~100 tokens/skill，永远付费 | name + description |
| Load（正文） | 模型判断相关时加载 | 按触发付费 | SKILL.md 正文（<500 行） |
| Runtime（附属） | agent 读到引用才加载 | 按需付费 | references/ scripts/ assets/ |

推论：**description 是常驻税，正文是触发税，附属文件是使用税**——越常触发的内容越要精简，越重的内容越要往底层拆。

### 1.3 Frontmatter 硬约束

**跨平台规范只认 6 个字段**（agentskills.io；多写字段在规范分发路径会硬报错）：

| 字段 | 规则 |
|---|---|
| `name` | 必填；小写字母/数字/连字符；1–64 字符；**必须与目录名完全一致**；不以 `-` 开头结尾、无连续 `--` |
| `description` | 必填；**上限 1024 字符**；第三人称祈使句；唯一路由/触发信号；写 what + when |
| `license` | 可选；如 `Apache-2.0` 或 `Proprietary. LICENSE.txt has complete terms` |
| `compatibility` | 可选；≤500 字符；环境要求（大多数 skill 不需要） |
| `metadata` | 可选；任意字符串键值对（键名建议加前缀防冲突） |
| `allowed-tools` | 可选；**实验性**；预批准工具列表 |

**Claude Code / ZCode 本地扩展字段**（不随规范分发带出去）：`when_to_use`、`argument-hint`、`disable-model-invocation`、`user-invocable`、`model`、`context: fork`、`paths`、`hooks` 等。其中 `disable-model-invocation: true` = 只能人显式调用（零上下文税，但 agent 不会自主触发）。

### 1.4 其他格式军规

- 文件路径一律正斜杠（即使 Windows）；MCP 工具用全限定名 `ServerName:tool_name`
- 依赖显式写出（先 `pip install pypdf` 再用），不假设已安装；一次性工具固定版本（`uvx ruff@0.8.0`）；自包含 Python 脚本用 PEP 723 内联依赖
- 不放 README/CHANGELOG 等对 agent 无用的文件
- 引用只一层深：SKILL.md → advanced.md 到头，不再嵌套；每个引用写清"何时读"（条件式指针："Read references/api-errors.md if the API returns non-200"），不写泛泛的"see references/"
- 同名 skill 的发现优先级：项目级 > 用户级；想覆盖官方/他人 skill，就复制到更高优先级路径改
- 用 `skills-ref validate ./my-skill` 或 `claude plugin validate .claude/skills` 做体检

---

## 2. 官方生态全景：开放规范 + 官方仓库 + Claude Code 落地

> 详细出处与原文引用见 `sources/A-官方生态.md`。

### 2.1 Agent Skills 已是开放标准

- 2025-12-18 起，Anthropic 把 Agent Skills 发布为**开放标准**，规范权威源是 [agentskills.io/specification](https://agentskills.io/specification)（anthropics/skills 仓库里的 spec 文件只剩一行指针指向它）。
- 规范极简：一个 skill 最少就是**一个含 SKILL.md 的目录**；scripts/references/assets 是推荐约定而非强制，允许任意自造目录（官方 canvas-design 就用了 `canvas-fonts/` 装 80 个字体）。
- 正文"没有任何格式限制"（规范原文），推荐写：分步指令、输入输出示例、常见边界情况。
- 官方提供校验工具：`skills-ref validate ./my-skill`。

### 2.2 anthropics/skills 官方仓库（173,993 star）

19 个官方 skill，SKILL.md 从 **33 行到 570 行**不等（中位数约 130）——官方自己就没把 500 行上限用满，格式极简是常态。四类：

| 类别 | 代表 | 特点 |
|---|---|---|
| 文档技能 | docx / pdf / pptx / xlsx | source-available（非开源），生产级；高密度 gotchas + 强制 QA 校验闭环 |
| 创意类 | canvas-design / algorithmic-art / theme-factory | 全大写强调 + few-shot 哲学示例；对抗"AI 平庸感" |
| 技术类 | mcp-builder / webapp-testing / claude-api / web-artifacts-builder | 四阶段工作流 + 按需加载引用；claude-api 570 行靠 8 个语言子目录外移细节 |
| 方法类 | **skill-creator**（486 行元技能）/ doc-coauthoring / brand-guidelines | eval 驱动迭代的完整闭环，写 skill 前必读 |

值得记住的两个对照实验：**claude-api（570 行 + 26 篇 shared/）**示范"超重知识如何分包"；**web-artifacts-builder（74 行）**示范"逻辑都在脚本里时 SKILL.md 可以极短"。

### 2.3 官方设计理念（工程博客 + 发布公告）

- 核心隐喻：**"给 agent 写 skill 就像给新员工写入职指南"**（onboarding guide for a new hire）——不是为每个用例造定制 agent，而是沉淀可组合的程序性知识。
- Skill 补的是"能做"而不仅是"知道"：Claude 懂 PDF 但不能直接填表单，脚本补上操作能力。
- **与 MCP 的分工**：MCP 负责"连接工具"，Skills 负责"教会涉及外部工具的复杂工作流"。
- 四性质：Composable（自动识别与组合）/ Portable（一次编写，Claude 应用、Claude Code、API 通用）/ Efficient（按需加载）/ Powerful（可含可执行代码）。
- 作者四条实践建议：① Start with evaluation（先跑代表性任务找缺口）；② Structure for scale（重了就拆文件，写清"运行还是阅读"）；③ Think from Claude's perspective（name/description 决定触发）；④ Iterate with Claude（让 Claude 把成功路径与常见错误沉淀回 skill）。

### 2.4 Claude Code 中的落地机制（写 skill 前必须知道的几条）

- **四级安装位置**：企业 managed settings > 个人 `~/.claude/skills/` > 项目 `.claude/skills/` > 插件（`plugin:skill` 命名空间不冲突）；同名高级覆盖低级，任何级别都能覆盖内置 skill。ZCode 同理采用"项目级 > 用户级"。
- **slash command 已与 skill 合并**：`.claude/commands/deploy.md` 和 `.claude/skills/deploy/SKILL.md` 都产生 `/deploy`，行为一致；skill 额外支持目录、frontmatter 控制与自动触发。
- **调用控制两个字段**：`disable-model-invocation: true`（只有人能调，description 不进上下文）；`user-invocable: false`（只有 Claude 能调的背景知识型）。
- **description 列表截断**：Claude Code 的 skill 列表预算约为模型上下文的 **1%**，单条 `description`（含 `when_to_use`）超 **1536 字符即截断**——**把最关键的用例写在开头**。
- **生命周期**：skill 正文以单条消息进入对话并**跨轮次留存**；自动压缩后每个 skill 保底保留前 5,000 tokens（合计 25,000 预算）——所以"贯穿任务全程的 standing instructions"比"一次性步骤"更值得写。
- **Claude Code 扩展 frontmatter**（跨平台分发时无效，规范只认 6 字段 name/description/license/compatibility/metadata/allowed-tools）：`when_to_use`、`argument-hint`、`disable-model-invocation`、`user-invocable`、`model`、`context: fork`、`paths`、`hooks` 等；ZCode 本地实测还常用 `allowed-tools`、`metadata`、`argument-hint`。
- 排障速查：不触发 → description 加用户会说的关键词；过度触发 → 收窄描述或设 disable-model-invocation；frontmatter YAML 坏了 → body 照样加载但元数据为空，用 `claude plugin validate` 体检。

### 2.5 官方代表 skill 深读要点（抄作业指南）

- **skill-creator（486 行，元技能典范）**：开篇就要求"先判断用户处于流程哪一步再切入"，而非机械从头执行；description 写法原文——"All 'when to use' info goes here, not in the body… Claude 倾向 undertrigger，请把 description 写得 a little bit 'pushy'"；改进方法论："如果你发现自己在写全大写 ALWAYS/NEVER，那是黄旗——改写为解释理由"。
- **pptx（239 行，生产级）**：三行决策表开场（建→pptxgenjs / 改→unzip 编辑 / 读→markitdown）；~20 条"命令+后果+修复"式 gotchas；**MUST/NEVER 只用于机械性技术坑**（"hex 色带 # 会损坏文件"），审美红线才讲理由；强制三段 QA（内容/文件/视觉）+ 校验脚本闭环。
- **mcp-builder（237 行）**：四阶段流程 + 文末"📚 Documentation Library"按加载时机分组（Load First / Load During Phase 1/2 / Phase 4）——**"告诉 agent 何时读每个文件"的最佳示范**。
- **canvas-design（129 行）**：先写"设计哲学"再落画布；预置二轮打磨对话（"用户已经说过不够完美…"）——把多轮预期写进单文件。

---

## 3. 社区生态全景：两大流派、合集地图与分发通道

> star 数均为 2026-09-05 GitHub API 实测；全部仓库已核实存在。详细出处见 `sources/B-社区合集.md`。

### 3.1 两大代表作仓库（用户本地已装这两套）

**obra/superpowers —— 281,716★，「过程压制派」**
- 定位是**完整软件开发方法论**：brainstorm → plan → TDD 实现 → review → verify → merge 全链路 14 个 skill，相互调用形成状态机；仓库自带 `tests/`（78 个文件）对子代理做对抗性场景测试。
- Jesse Vincent 的写法核心是 **Rules vs Gates 之辨**（博客《Rules and Gates》）：rule 有 opt-out 路径，gate 没有——"下一个动作被阻塞，直到门条件满足"。判别标准："当我想跳过它时，gate 表述能否给我一个具体到无法自欺的问题？"（"Do I have URLs?" 可以；"Did I verify this?" 不行）。
- 值得抄的部件：`<HARD-GATE>` XML 块、Red Flags / Rationalizations 双列表格（把模型自我合理化的念头逐条列出反驳）、阶段门 + 熔断计数（3 次修复失败即停下质疑架构）、spike/bounded/architectural 三档分类 + 棘轮规则（只升不降）、"产出可缩水，审批门永不缩水"。

**mattpocock/skills —— 249,627★，「词汇工艺派」**
- 定位是**小而可组合的个人工程习惯**（37 个 skill，engineering/productivity/in-progress/misc 四类），README 明确反对接管流程的框架（GSD/BMAD/Spec-Kit）："These skills are designed to be small, easy to adapt, and composable."
- 理论基石是四个失败模式 → 四个修法（各引一本经典书）：没做想要的 → grilling 拷问；太啰嗦 → CONTEXT.md 共享语言（DDD）；跑不起来 → TDD 反馈回路；泥球架构 → 每天投资设计（Ousterhout 深模块）。
- 元技能 `writing-for-agents`（81 行）是**写 agent 文档的参考手册**，核心概念：context pointer（指针的措辞而非目标决定触发可靠性）、two loads（上下文负载 vs 人类索引负载）、信息阶梯（in-file step → in-file reference → disclosed reference）、completion criterion（既可检查又要求穷尽）、leading words（借预训练已有的紧凑概念，"以 token 而非句子反复出现"）、猎杀 no-op、文档即缓存只写查不到的东西。

**两派关系**：superpowers 教"如何约束 agent 的过程"，Pocock 教"如何写出让 agent 稳定执行的文字"——互补而非竞争。你的 7 个自写中文 skill 已经同时吸收了两派。

### 3.2 高星合集地图（按 star）

| 合集 | Star | 定位 |
|---|---|---|
| anthropics/skills（官方锚点） | 173,993 | 19 个官方 skill，事实标准示范 |
| ComposioHQ/awesome-claude-skills | 74,466 | ~45 条精选 + 入门教程，新手最佳入口 |
| hesreallyhim/awesome-claude-code | 53,503 | 171+ 条 Claude Code 全生态目录 |
| sickn33/agentic-awesome-skills | 45,974 | 2,100+ skills 目录 + CLI + 本地 MCP 控制面 |
| VoltAgent/awesome-agent-skills | 33,747 | 1000+ skills 按厂商组织（NVIDIA/Stripe/Supabase/Vercel…），附质量标准 |
| libukai/awesome-agent-skills（中文） | 5,051 | 中文版终极指南 |

**质量信号经验**：同一 skill 同时出现在 Composio（精选向）与 VoltAgent（广度向）是最强信号。被反复收录的单体代表作：ui-ux-pro-max（125k★，UI/UX 设计模式）、taste-skill（84k★，给 AI 设计品味）、last30days（61k★，跨平台热点调研）、caveman（103k★，token 压缩）、ffuf_claude_skill（安全模糊测试）、ios-simulator-skill。

### 3.3 分发通道已收敛为三条

1. **Claude Code / ZCode 官方 plugin marketplace**（superpowers、mattpocock-skills 均已上架）；
2. **skills.sh 的 `npx skills add <owner>/<repo>`**（vercel-labs/skills，30k★——可编辑拷贝、跨 Claude Code/Codex/Cursor 多端安装）；
3. **Awesome 列表手工收录**。

自写 skill 想被别人用：发布到 GitHub + 提交进 awesome 列表 + 上架 marketplace，三选一即可起步。



---

## 4. 正文怎么写：结构、措辞与拆分

> 全部规则带出处，见 `sources/C-写法方法论.md` ③④ 节。

### 4.1 推荐骨架（写技术型 skill 就按这个来）

```
# Skill Name
## Overview            # 1-2 句核心原则，不是散文
## When to Use         # 症状/场景清单 + When NOT to use；决策不显然才配流程图
## Core Pattern        # Before/After 代码对照
## Quick Reference     # 可扫读的表格
## Implementation      # 简单模式内联代码；重引用链接出去
## Common Mistakes     # ❌ 错误 → ✅ 修复
```

配套两个官方五模式：**gotchas 节**（"每次你不得不纠正 agent，就把纠正加进来"，且放正文而非引用文件——agent 可能认不出触发时机）、**checklist**（显式 `- [ ]` 防跳步）。

### 4.2 自由度校准：「窄桥与开阔地」

| 任务性质 | 控制力度 | 形式 |
|---|---|---|
| 多解、看上下文（code review） | 高自由 | 文字指引 + 解释 why |
| 有偏好模式、允许变化（报告生成） | 中自由 | 带参伪代码 / 模板 |
| 操作脆弱、一致性攸关（数据库迁移） | 零自由 | "Run exactly this script. Do not modify." |

原则：**给默认方案 + 逃生舱，不给菜单**；写步骤不写宣言；"skill 应教 agent *如何应对一类问题*，而非*某个实例要产出什么*"。

### 4.3 措辞规则（易踩坑的六条）

1. **按失败类型选形式**——压力下违规 → 禁令 + 借口对照表；输出形状错 → 正面配方（说清输出是什么）；漏元素 → 模板 REQUIRED 槽位；条件行为 → 挂在可观察谓词上。
2. **示例优于规则**：一个卓越示例 > 多个平庸示例；要结构化输出就放字面示例。
3. **解释 why 而非吼 MUST**：全大写 ALWAYS/NEVER 是黄旗，说明这条规则缺解释。例外（pptx 实践）：机械性技术坑（"hex 带 # 会损坏文件"）直接 NEVER 没问题。
4. **正向措辞**：先写目标行为；"别想大象"式禁令会把被禁行为拉进上下文；真例外写成独立条件句，不加豁免条款。
5. **leading words**：借模型预训练已有的紧凑概念（tracer bullets、red、tight），一词锚定一片行为；自造词不招募先验。
6. **完成判据**：每步以"既可检查又要求穷尽"的判据收尾（"每个被改动的模型都被覆盖"），模糊判据诱发提前完成。

### 4.4 拆分原则：scripts/ 与 references/

**进 scripts/ 的条件**（执行而非阅读）：
- 确定性操作——比 agent 现场写码更可靠、省 token（只付输出不付源码）、跨次一致
- 同一段 helper 被反复重造——写一次打包
- 脚本质量要求：无交互（硬性）、有 `--help`、结构化输出（JSON 到 stdout，进度到 stderr）、报错"说清错在哪、期望什么、试什么"、幂等、支持 `--offset` 控输出体量
- 批量/高危操作用 **plan-validate-execute**：先产出计划文件 → 脚本对照事实源校验（报错要啰嗦到能指导修复）→ 通过再执行

**进 references/ 的条件**：
- 三种拆法：① Quick start 内联 + 进阶外链；② 按领域拆（问销售只加载 sales.md）；③ 条件细节（正文 + "For tracked changes: See REDLINING.md"）
- 内联保留：原则概念、<50 行代码；拆出：100+ 行重引用、可复用模板
- 引用一层深；>300 行顶部加 TOC；每个引用写清加载条件
- **多个 skill 共享的参考**若大家都是用户手调型，就推到 skill 体系外当普通文件

---

## 5. 你的 skill 写作工作流：从想法到可靠发布

> 综合自官方 skill-creator / agentskills.io 评测指南 / superpowers writing-skills / Perplexity 实践，出处见 `sources/C-写法方法论.md`。

### 阶段 0：立项——资格三问（不过就不做）

1. 没有 skill 时 agent 真的会做错吗？（没跑过基线就没资格回答）
2. 这不是模型默认就会的通用知识吧？
3. 可复用、跨场景，不是一次性的吧？

分流：机械约束 → hooks；项目约定 → AGENTS.md；一次性 → 直接做。

### 阶段 1：RED——先拿失败证据

- 跑无 skill 基线（有条件就跑 2–3 次真实任务），**逐字记录** agent 的错误选择和它的合理化借口（"我已经手动测过了""事后测试也能达到同样目标"）。
- 基线不失败 → 没东西可修，取消立项。
- 同时给失败分类：压力下违规？输出形状错？漏元素？条件分支错？——**失败类型决定写法**（禁令+借口表 / 正面配方 / REQUIRED 槽位 / 条件句）。

### 阶段 2：GREEN——最小实现

- 素材从真实专长来：你干完这件事时记下的步骤、纠正、坑（gotchas），或内部文档/评审意见/真实故障案例——**不要让 LLM 凭通用知识编**（官方点名批评的首个陷阱）。
- 按第 4 章骨架写；只针对基线里真实出现的失败写内容，不给假想情况加戏。
- 完成判据贴到每个步骤；默认值替代菜单；示例优于规则。
- 确定性操作沉淀成 `scripts/`（无交互、有 `--help`、结构化输出、stdout/stderr 分离、幂等、可控输出体量）。

### 阶段 3：验证——两种评测都要跑

**输出质量评测**（skill 做得对不对）：
- with_skill vs without_skill 成对跑，断言要"可编程验证 + 要求具体证据"（"图表有标注轴" 好于 "输出不错"）。
- 看 delta 值不值："token 翻倍只换 2 分提升，可能不值"。

**触发评测**（该来的时候来不来）：
- 按附录 C 的 20 条/3 次/60-40/5 轮流程跑；负例用 near-miss。
- 改 description 后**回归测相邻 skill**（词级改动会外溢）。

### 阶段 4：REFACTOR——堵漏至无新借口

- agent 冒出新借口 → 显式封堵（"删掉重来。无例外"）→ 更新借口对照表 / Red Flags / description 症状词 → 重测。
- 观察加载行为比问观点准：意外阅读顺序 = 结构不直观；反复读同一文件 = 内容该上浮；从不打开的附属文件 = 多余或信号不足。
- 顽固问题**换框架/隐喻**，不是继续叠规则——过拟合的小规则和压迫性 MUST 会让 skill 随时间变差。

### 阶段 5：维护飞轮

- agent 犯错 → 追加 gotcha（官方：**"每次你不得不纠正 agent，就把这个纠正加进 gotchas 节"**）。
- 误加载 → 收紧 description + 加负例；没加载 → 补用户真实用语。
- 定期删：陈旧内容、no-op 句、被环境取代的缓存知识——防"沉积层"。
- 写完一个、走完全部检查再写下一个，**不要批量造**。

### 工具化建议（你的环境已具备）

- 依次用现成元技能：`skill-creator`（官方，带 eval 闭环）→ 本地 `writing-skills`（superpowers TDD 流派，更严）→ `session-to-skill`（把刚干完的会话直接蒸馏成 skill——最贴合"从真实专长出发"）。
- 发布前用附录 B 清单逐项过；存进 `~/.agents/skills/`（用户级）或项目 `.agents/skills/`（项目级）。

---

## 附录 A：SKILL.md 起步模板（可直接复制）

```markdown
---
name: <kebab-case-name>          # 必须与目录名一致
description: >                   # 第三人称，<1024 字符，"功能 + Use when 触发条件"
  <一句话说清做什么，含关键名词/工具名>。Use when
  <用户会说的原话/症状/报错关键词/场景>，or when
  <另一个具体触发分支>。Don't use for <near-miss 反例>。
---

# <Skill 名称>

## Overview
一两句核心原则（这不是散文，是给 agent 的锚）。

## When to Use
- 场景/症状清单（具体、可观察）
- **When NOT to use**：负向边界

## 流程
### 1. <步骤名>
动作指令……
**完成判据**：<可检查、要求穷尽的标志>   ← 每步必须有，防"提前完成"

### 2. <步骤名>
……

## Quick Reference
| 情况 | 动作 |
|---|---|

## Common Mistakes
- ❌ <错误做法> → ✅ <正确做法>

## 附属文件（如有）
- 确定性操作：`Run scripts/xxx.py to <目的>`（执行，不是阅读）
- 重引用：`See references/xxx.md`（只一层深，>300 行加目录）
```

## 附录 B：写完一个 skill 后的自检清单（按序过一遍）

1. **资格三问**：① 没有 skill 时 agent 真的会做错吗（跑过基线吗）？② 这不是模型默认就会的通用知识吧？③ 是可复用、跨场景的吗？
   ——任何一问不过：不做，或改放 AGENTS.md / hook。
2. **name**：kebab-case、与目录名一致、无特殊字符。
3. **description**：第三人称；功能一句话 + "Use when..." 具体展开；含真实用户词/报错词/同义词；有负向触发；**没有一个字概括工作流**；≤1024 字符（经验值 <500）。
4. **正文**：<500 行；Overview/When to Use(+NOT)/核心模式/Quick Reference/Common Mistakes 骨架齐；每步有完成判据；概念共置。
5. **自由度校准**：多解任务给文字指引；有偏好给模板/伪代码；脆弱操作给"Run exactly this script"零参数脚本；处处有默认值而非菜单。
6. **措辞**：示例 ≥ 规则；解释 why 而非全大写 MUST；正向措辞优先；术语全程一致。
7. **附属文件**：确定性操作在 scripts/ 且写明"执行"；引用一层深；>300 行 reference 有 TOC；无悬空引用（引用的文件真实存在）。
8. **测试**：无 skill 基线失败逐字记录过；RED→GREEN→REFACTOR 走完；20 条触发评测（一半 near-miss）每条 3 次；改过 description 后回归测了相邻 skill。
9. **每句话税测**：逐句问"没有这句会错吗"，删掉所有 no-op 句。

## 附录 C：触发评测怎么做（官方 skill-creator 的方法）

1. 写 **~20 条评测 query**：8–10 条应触发 + 8–10 条**不应触发的 near-miss**（主题相近但实际不适用）；query 要像真人输入（具体路径、口语、甚至错别字）。
2. 每条 query 跑 **3 次**（触发有随机性，单次样本会撒谎），统计触发率，设阈值（Perplexity 用 0.5）。
3. **60/40 切分**训练/验证集，最多迭代 5 轮改写 description，按**验证集**得分选版本（防过拟合）。
4. 词级改动有"外溢"：改一个词可能让邻居 skill 被误触发——每次改完**回归测全部相关 skill**。

## 附录 D：什么该做成 skill，什么不该

**该做（三条测试全过才做）**：基线会失败 + 非模型默认知识 + 可复用跨场景。
典型：训练数据里没有的内部知识（表 schema、企业流程、过滤规则）、需要一致性输出的任务、品味与领域经验、复杂多步工作流。

**不该做 / 移走**：
- 一次性方案 → 直接做，别沉淀
- 项目特定约定 → AGENTS.md / CLAUDE.md
- 机械约束（能用 regex/校验器强制的）→ hooks；"Skills are suggestions, not instructions"，skill 管不住的纪律用 hook 挡
- 系统提示已覆盖的 → 别与系统提示争抢
- 快变信息（易过期 API）→ 不做
- 环境自己能查到的（package.json scripts、`--help`）→ 只缓存查不到的：不成文约定、决策理由、配置不会招认的坑

---

## 附录 E：本地已装 58+18 个 skill 的盘点结论（你的起点）

> 完整清单与逐篇深读见 `sources/D-本地盘点.md`。

**四大流派分布**：superpowers 15 个（开发流程/调试/评审，平均 ~226 行）｜Matt Pocock 33 个（需求盘问/issue 流水线/写作，平均 ~70 行）｜Google Android 3 个｜自写中文 7 个（source-annotator、session-to-knowledge、session-to-skill、excel-srs-skill 等，工程化程度最高）｜官方插件 18 个（文档四件套/浏览器/诊断指南）。

**结构规律**：行数两极分化（7 行的 grill-me 到 679 行的 writing-skills，中位数 90）；44/58 单文件自包含；frontmatter 只实际用到 7 种字段；官方插件同时存在"645 行巨型单文件（pptx）"和"40 文件文件树（pdf）"两种打包策略。

**可直接借鉴的本地范本**：
- 学"纪律型 skill 怎么堵漏洞" → `systematic-debugging`（铁律 + 四阶段 + 借口对照表）
- 学"流程型 skill 怎么写模板" → `writing-plans`（零上下文假设 + 任务模板 + No Placeholders）
- 学"原语/壳分层与技能组合" → `grilling` → `grill-me`/`grill-with-docs` + `ask-matt` 路由器
- 学"重工程 skill 的质量门" → `source-annotator`（脚本校验 + 语义自评双层门 + 论断分级）
- 学"描述即路由器" → `session-to-knowledge`（描述里写分流规则）

**注意已存在的触发竞争**（写新 skill 前先查重）：tdd vs test-driven-development、systematic-debugging vs diagnosing-bugs、handoff vs claude-handoff、grill-me vs grill-with-docs vs loop-me。

**场景缺口（自写 skill 的优先方向）**：
1. 前端/UI 设计与实现（brainstorming 还引用了未安装的 frontend-design）
2. 测试专项：e2e/Playwright、性能基准、测试数据构造
3. 数据与后端：SQL/迁移、API 契约设计
4. Git 协作链：PR 描述生成、changelog/release
5. 中文写作发布链：技术博客/公众号排版发布、双语技术文档

---

## 附录 F：来源汇总

- 本地精读：ZCode skill-creator 插件、writing-skills（superpowers，含 4 附属文件）、writing-for-agents（Matt Pocock）——路径见 `sources/C-写法方法论.md`
- 官方文档与仓库：见 `sources/A-官方生态.md`
- 社区合集与生态：见 `sources/B-社区合集.md`
- 本地盘点统计：见 `sources/D-本地盘点.md`
