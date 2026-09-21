# A · 定位与规划 —— AI 时代程序员如何自处、如何做职业规划（调研笔记）

> **调研日期**：2026-09-19
> **核实方式**：用 WebSearch 发现线索；用 web_reader / WebFetch 抓取报告与文章原文逐条核对关键数字（METR、DORA 公告、SO 2025、Octoverse 2024/2025、Stanford 论文页、Harper Reed、swyx、Sourcegraph/Yegge 等均为原文核对）；GitHub 仓库的 star/最近推送时间用 gh api（已认证）逐一查询，12-factor-agents 的因子清单经 raw.githubusercontent.com 直接读取 README 核实。仅个别标注「未逐字核实」的数字来自官方站点的搜索摘要。文中 star 数与 pushed 时间均为 2026-09-19 查询值。
> **证据纪律**：区分【事实】（有数据/原文）与【观点】（署名个人预测）；有争议的结果（METR 变慢 19%、SO 信任度下滑、DORA 稳定性恶化、Canaries 就业下降）如实记录结论与限定条件。

---

## 一、数据线：AI 对软件岗位的实际影响

### 1. GitHub Octoverse 2025
- **URL**：https://github.blog/news-insights/octoverse/octoverse-a-new-developer-joins-github-every-second-as-ai-leads-typescript-to-1/
- **性质**：官方年度报告（GitHub）
- **时间**：2025-10-28 发布
- **关键结论**（原文核实）：
  - 全站开发者达 **1.8 亿**，过去一年新增 **3,600 万**（每秒约 1 名新开发者），新增者约 80% 来自美国以外；
  - **TypeScript 首次成为 GitHub 第一语言**（按月度贡献者数，2025-08 超越 Python 与 JavaScript）。官方解释：开发者用 AI 写的代码变多，而 AI 倾向生成带类型的语言；
  - 过去一年新增 **1.13M 个 AI/agent 相关仓库，同比 +79%**；月均合并 PR 4,320 万（+23%）；全年代码推送 9.86 亿次；
  - Agent 生态爆发：GitHub Copilot CLI +288%、OpenAI Codex SDK +183%、LangChain +148%、Gemini CLI +145%；贡献者最多的开源 AI 项目为 openai-cookbook、Meta Llama、ollama；
  - 原话：「AI is not just making developers more productive — it is fundamentally changing what developers do.」
- **可信度**：高（平台方一手数据）。注意 GitHub 有推广 Copilot 的利益关联，语言榜与仓库数属客观数据，「AI 正在改变开发」的解读则带官方叙事色彩。

### 2. GitHub Octoverse 2024
- **URL**：https://github.blog/news-insights/octoverse/octoverse-2024/
- **性质**：官方年度报告
- **时间**：2024-10-29 发布
- **关键结论**（原文核实）：**Python 十年来首次超越 JavaScript 成为第一语言**（AI/数据科学驱动，Jupyter Notebooks +92%）；AI 项目总数同比 **+98%**，生成式 AI 项目贡献 +59%，2024 年新增 7 万+ 公开生成式 AI 项目；开源调查受访者 **73% 在用 AI 工具**；ollama 成为按贡献者增长最快的 AI 项目（本地小模型需求信号）；印度 +28% 至 1,700 万开发者，预计 2028 年成最大开发者国家。
- **可信度**：高。与 2025 报告连读可见清晰趋势线：2024 年 AI 仓库翻倍 → 2025 年 agent 仓库 +79%、TypeScript 登顶。

### 3. DORA 报告 2024（《Accelerate State of DevOps》）
- **URL**：https://dora.dev/research/2024/dora-report/ ；官方公告：https://cloud.google.com/blog/products/devops-sre/announcing-the-2024-dora-report
- **性质**：Google Cloud 官方年度研究（DORA 团队）
- **时间**：2024-10-22 发布
- **关键结论**（官方公告核实）：**AI 采用率每提升 25%，交付吞吐量估计下降 1.5%，交付稳定性下降 7.2%**——个体自报生产力提升，但组织级交付结果变差；同时约 **39% 的受访者表示对 AI 生成的代码「几乎不信任或不信任」**（约 76% 受访者已在工作中依赖 AI，见官方公告）。
- **可信度**：高（样本量大、方法学成熟）。这是最早、最著名的「AI 提效叙事」反证之一；注意它是相关性而非因果，且测的是组织交付而非个人编码。

### 4. DORA 报告 2025（《State of AI-assisted Software Development》）
- **URL**：https://dora.dev/research/2025/dora-report/ ；官方公告（本次据此核实）：https://cloud.google.com/blog/products/ai-machine-learning/announcing-the-2025-dora-report
- **性质**：官方年度研究
- **时间**：2025-09-23 发布
- **关键结论**（官方公告核实）：调查近 **5,000** 名从业者；**90% 在工作中使用 AI**（同比 +14%），使用时长中位数 **每天 2 小时**，估计每天省 1.5 小时；80%+ 自报生产力提升，但 **30% 对 AI 生成代码几乎不信任**；2025 年 AI 采用与吞吐量转为**正相关**，但**与交付稳定性仍为负相关**；年度主题：AI 是「**放大器（amplifier）**」——放大组织既有的优势与 dysfunction，其变革潜力「在很大程度上仍未兑现」。
- **可信度**：高。与 2024 连读：个体层面收益真实，系统层面（稳定性）风险持续存在 → 对个人含义：AI 放大你的工程判断力，也放大你的坏习惯。

### 5. Stack Overflow Developer Survey 2025（AI 章节）
- **URL**：https://survey.stackoverflow.co/2025/ai
- **性质**：大型行业调查（49,000+ 开发者）
- **时间**：2025 年调查（2025-07 发布结果）
- **关键结论**（原文核实）：
  - **84% 使用或计划使用 AI**（2024：76%），专业开发者 51% 每天用 → 采用持续上升；
  - **好感度从 2023/2024 的 70%+ 跌至 60%**；**信任坍塌**：33% 信任 vs **46% 不信任** AI 输出准确性，「高度信任」仅 3%；
  - **经验越深越警惕**：10 年以上开发者「高度信任」仅 2.5%、「高度不信任」20.7%；
  - **66% 的最大挫败：「AI 的方案看起来对，但就差一点（almost right, but not quite）」**；45% 认为调试 AI 代码更耗时；45% 认为 AI 不擅长复杂任务；
  - Agent 尚未普及：52% 不用或无计划用 agent，每日使用 agent 的仅 14%。
- **可信度**：高（一手调查）。本条与 Octoverse 的「采用上升」连读即得全貌：**用的人越来越多，信的人越来越少**。

### 6. JetBrains《State of Developer Ecosystem 2025》
- **URL**：https://devecosystem-2025.jetbrains.com （官方博客 2025-10-15 发布）
- **性质**：年度开发者生态调查
- **时间**：2025-10
- **关键结论**：**85% 开发者已在使用 AI 工具；62% 依赖至少一个 AI 助手/agent/AI 编辑器**；最常用 ChatGPT（41%）与 GitHub Copilot（30%）。
- **可信度**：较高（一手调查；以上数字来自官方站点，本次经搜索摘要转述，**未逐字核对原报告**）。作为 SO 2025 的第三方交叉验证：85% ≈ SO 的 84%，口径互相印证。

### 7. Anthropic Economic Index（Anthropic 经济指数）
- **URL**：https://www.anthropic.com/economic-index ；底层论文：https://arxiv.org/abs/2503.04761 （《Which Economic Tasks are Performed with AI?》）
- **性质**：官方持续追踪研究（基于数百万条匿名 Claude 真实对话）
- **时间**：2025-02 首发，持续更新（2025-09、2026-01 有重要更新）
- **关键结论**：**「计算机与数学」（软件开发类）是 Claude 使用量最大的职业类别，自 2024-12 起稳定占全部用量的约 37–40%**；软件开发+写作合计约占一半用量（论文）；** augmentation（增强）vs automation（自动化）**：2025-01 为 56% vs 41%，此后自动化占比趋势上行（2026-01《Economic primitives》报告）。
- **可信度**：高（真实使用数据，样本巨大）。注意：它测的是「Claude 用户」而非全体程序员（样本偏向早期/重度采用者）；但「软件任务是 AI 用量第一大类」与「先用 AI 的是较资深/较复杂任务还是简单任务」的持续追踪，对个人定位极有参考价值。

### 8. 斯坦福《Canaries in the Coal Mine?》（煤矿中的金丝雀）
- **URL**：https://digitaleconomy.stanford.edu/publications/canaries-in-the-coal-mine/
- **性质**：学术论文（Brynjolfsson, Chandar, Chen；斯坦福数字经济实验室）
- **时间**：2025-08 首发，**2026-08-12 最新修订版**
- **关键结论**（论文页核实）：基于 ADP 工资单高频数据（覆盖数百万美国就业者，数据至 2026-06）：
  - **没有全经济范围的 AI 大替代**；但 **22–25 岁年轻人在高 AI 暴露职业（软件开发、客服等）中的就业比反事实水平低 19%**（2025-08 初版该数字为 **13%**，修订后扩大）；
  - 机制是**减少招聘**（入口收窄），而非裁员；
  - 关键分化：**AI 替代人类任务的地方就业下降集中；AI 增强人类的地方就业持平或上升**（尤其资深工人受益）；
  - 作者自限：这是「描述性早期指标——金丝雀——而非因果估计」。
- **可信度**：高（作者为顶级劳动/AI 经济学家，数据独特）。是「初级岗位受冲击」论点的最强一手证据；反对者指出 ADP 样本偏差与前置趋势，作者已在论文中列明并部分排除。

---

## 二、争议证据专节：与「AI 大幅提效」叙事相矛盾或需限定的结果

### 9. METR 随机对照试验：AI 让资深开源开发者变慢 19%
- **URL**：https://metr.org/blog/2025-07-10-early-2025-ai-experienced-os-dev-study/
- **性质**：RCT 实证研究（METR，非营利评测机构）
- **时间**：2025-07-10 发布
- **关键结论**（原文全文核实）：16 名资深开源维护者、246 个真实 issue，2025 年 2–6 月，工具为 Cursor Pro + Claude 3.7 Sonnet；组内随机分配「允许/禁止用 AI」。
  - **事实**：用 AI 的任务实际**慢 19%**（95% CI：慢 2% 到慢 39%）；
  - **事实（感知差）**：事前开发者预测 AI 能快 24%，用完后自估快 20%——**主观感受与客观结果严重背离**；
  - **限定条件（原文明示）**：受试者平均在自己仓库有 5 年、1,500+ 提交经验，代码库大而成熟（平均 2.2 万 star、3 万+ 提交）；结论**不能外推**到绿field 项目、不熟悉的代码库或新手；使用的是 2025 年初的工具。
- **可信度**：高（随机对照、预注册、多维度测量）。它证明的不是「AI 没用」，而是三件事：**自我感觉不可靠、成熟大代码库+深度熟悉领域 AI 增益有限、结果高度依赖任务/人群/工具版本**。

### 10. METR 2026-02 后续：晚 2025 工具反转为「快约 18%」，但证据很弱
- **URL**：https://metr.org/blog/2026-02-24-uplift-update
- **性质**：同一实验的后续数据与方法论修订
- **时间**：2026-02-24 发布
- **关键结论**（原文核实）：换用晚 2025 工具后，原实验回归的 10 名开发者估计**提速 18%**（95% CI：快 38% 到慢 9%）；新招募开发者仅提速 4%（CI：快 15% 到慢 9%）。METR 自己称之为 **「very weak evidence」**：招募出现严重**选择效应**——越来越多开发者拒绝参加无 AI 组（哪怕付 50 美元/小时），30–50% 的人拒交必须手写的任务；因此上述数字「很可能是真实收益的**下限**」。METR 同时宣布重新设计实验。
- **可信度**：高（机构自我批判诚实）。两点并读：**工具在快速变好**（19% 变慢 → 约 18% 变快），但「AI 已普遍大幅提效」的社交媒体结论跑在了证据前面。

### 11. 【观点·预测核查】Dario Amodei：「AI 3–6 个月内写 90% 的代码」
- **URL**：Davos 2025-01 发言（广泛报道，如 Business Insider 2025-10 回顾）；事实核查讨论：https://www.lesswrong.com （2025-10-22《Is 90% of code at Anthropic being written by AIs?》）
- **性质**：企业 CEO 预测（非数据）
- **时间**：2025-01（Davos）与 2025-03（CFR 演讲《Machines of Loving Grace》）重申
- **关键结论**：Anthropic CEO Dario Amodei 预测「3–6 个月内 AI 将写 90% 的代码，12 个月后几乎全部」。**后续核查（2025-10）表明该预测未按期兑现**：AI 辅助编码占比确实很高且在涨，但「90% 由 AI 写」未达到，Amodei 本人后来也做了修正性表述。
- **可信度**：作为**观点/利益相关方预测**引用。用途：警示「不要按 CEO 演讲做规划」，按 METR/DORA/SO 这类测量数据做规划。

**争议证据小结**：四组互相矛盾的证据其实是同一事实的不同切面——（a）采用与工具能力在陡增（Octoverse、JetBrains、METR-2026）；（b）个体自报提效但主观感知系统性偏乐观（METR、DORA）；（c）组织级稳定性在恶化、信任在下降（DORA 2024/2025、SO 2025）；（d）就业冲击集中在入口而非整体（Canaries）。规划应同时吃下这四个面。

---

## 三、方法线：AI 时代的工作方式（高 star 仓库与一手长文）

### 12. Anthropic《Building Effective Agents》
- **URL**：https://www.anthropic.com/engineering/building-effective-agents
- **性质**：官方工程博客（作者 Erik Schluntz、Barry Zhang；本次核实页面与作者署名）
- **时间**：2024-12-19
- **关键内容**：区分 **workflow（预定义代码路径编排 LLM）与 agent（LLM 动态自主决定流程）**；给出五种可组合模式：prompt chaining、routing、parallelization、orchestrator-workers、evaluator-optimizer；核心忠告：**从简单方案开始，仅在确有必要时才增加复杂度；不用框架、直接调 API 往往更透明**；强调 evals 与「把成功标准写进循环」。
- **可信度**：高（一线模型厂商的实践方法论）。对个人：这也是「用 agent 干活」的心智模型——大多数日常任务该用 workflow 思维而非放养 agent。

### 13. Anthropic《Claude Code: Best Practices for Agentic Coding》（现为官方持续维护的文档）
- **URL**：https://www.anthropic.com/engineering/claude-code-best-practices （持续维护版：https://code.claude.com/docs/en/best-practices）
- **性质**：官方工程博客/文档
- **时间**：2025-04 首发至今持续更新
- **关键内容**（现文档版核实）：**explore → plan → code → commit** 工作流；用 **CLAUDE.md** 把项目约定、命令、风格固化给 agent；官方明确建议配对 TDD（先写测试再让 agent 实现）；**「验证循环」是核心——If you can't verify it, don't ship it**（无法验证就不要上线）；headless 模式做 CI/批量重构；multi-Claude（一个写、一个审）；用 /clear、子 agent 管理上下文。
- **可信度**：高。与 SO 2025「信任坍塌」连读：官方的第一原则不是「更快」，而是「**可验证**」。

### 14. Harper Reed《My LLM codegen workflow atm》
- **URL**：https://harper.blog/2025/02/16/my-llm-codegen-workflow-atm/
- **性质**：从业者长文（Harper Reed，资深工程师/前 CTO）
- **时间**：2025-02-16
- **关键内容**（原文核实）：规格驱动流程：头脑风暴 → 生成 `spec.md`（再压缩成 min-spec）→ 拆成 `prompt_plan.md` + `TODO.md` → 为项目生成规则文件（含 git 约定、代码风格、部署命令）→ 逐条执行；**「LLM 是极好的 forcing function」，大设计先行（BDUF）回归**；质量保证全部押在测试上——「我根本不知道代码写得好不好，我只信我的测试」；他把个人 Obsidian 知识库与 slash-commands 结合，把积累变成可复用的 prompt 资产。
- **可信度**：中高（个人经验，但被 Simon Willison 等广泛引用；与 Anthropic 官方建议高度一致）。**对已自建 markdown 知识库的开发者是直接可抄的路径**：知识库 → agent 规则/技能文件。

### 15. humanlayer/12-factor-agents（12 因子 Agent）
- **URL**：https://github.com/humanlayer/12-factor-agents
- **性质**：方法论仓库（HumanLayer / Dex Horthy）
- **时间**：2025 年发布；**26,287 star / 1,977 fork，最近 push 2025-09-21**（2026-09-19 查询）
- **关键内容**（README 原文核实）：12 因子为：自然语言→工具调用 / **Own your prompts** / **Own your context window** / 工具即结构化输出 / 统一执行态与业务态 / 简单 API 启动-暂停-恢复 / 用工具调用联系人类 / **Own your control flow** / 把错误压实进上下文 / 小而专注的 agent / 任意处触发 / agent 即无状态 reducer。著名论断：**agent 就是用确定性代码包起来的 LLM 循环**；警惕框架的「70–80% 陷阱」（快速到 80% 质量，然后被框架绑架，越过 80% 需要逆向工程框架，不如从零自己掌控）。
- **可信度**：高（大量生产实践背书，是 agent 工程领域引用率最高的中文圈外方法论之一）。对个人：「Own your context window」同样适用于个人工作流——自己组装给编码 agent 的上下文，而不是全托付。

### 16. 高 star 资源库组（核实 star 均为 2026-09-19 gh api 查询值）
- **anthropics/skills**（Agent Skills 官方公共仓库）：177,062 star，push 2026-09-10。https://github.com/anthropics/skills ——「把能力写成可复用 skill」已成官方生态。
- **x1xhlol/system-prompts-and-models-of-ai-tools**：143,711 star，push 2026-08-11。https://github.com/x1xhlol/system-prompts-and-models-of-ai-tools —— 各家 AI 编码工具（Cursor、Devin、Claude Code 等）系统提示词汇总；理解「AI 工程师如何造工具」的一手材料。
- **hesreallyhim/awesome-claude-code**：54,290 star，push 2026-09-19（当天仍在活跃更新）。https://github.com/hesreallyhim/awesome-claude-code
- **e2b-dev/awesome-ai-agents**：30,077 star，push 2026-08-21。https://github.com/e2b-dev/awesome-ai-agents
- **donnemartin/system-design-primer**：370,697 star，push 2026-09-15；**ByteByteGoHq/system-design-101**：89,380 star。https://github.com/donnemartin/system-design-primer —— 系统设计能力增值的供给侧佐证（面试与实战双场景热度未衰）。
- **可信度**：中高（社区维护质量参差，但头部仓库 star 与更新频率经 API 核实）。用途：按需取用，不必全读；awesome-claude-code 里「工作流/规则/skill」类条目最能反映社区实战共识。

---

## 四、规划线：定位、技能栈与复利资产

### 17. swyx《The Rise of the AI Engineer》
- **URL**：https://www.latent.space/p/ai-engineer
- **性质**：从业者长文（swyx / Shawn Wang，Latent.Space）
- **时间**：2023-06-30（该概念的奠基文，影响贯穿 2024–2026）
- **关键内容**（原文核实）：定义 **AI Engineer**：不训练模型，用软件工程能力在基础模型（API/开源权重）之上构建应用；引 Karpathy：「这个角色不需要训练任何模型也能非常成功」；AI Engineer 拥有「**产品专属的数据与 evals**」——评测能力是该角色的核心资产；「**跟上 AI 几乎是一份全职工作（keeping on top of it all is almost a full time job）**，我认真地、字面地看待这句话」；预测 AI Engineer 数量将远超 ML Engineer，十年内「最高需求的工程岗位」。
- **可信度**：中高（观点文，但已被行业实践验证：AI Engineer 成为真实职衔，AI Engineer 大会已成体系）。回看 2025–2026 的 Octoverse 数据，其方向判断成立。

### 18. swyx《Learn In Public》（公开学习）
- **URL**：https://www.swyx.io/learn-in-public
- **性质**：从业者长文
- **时间**：2018-06-19（AI 浪潮前的经典，今天被反复重新引用）
- **关键内容**（原文核实）：把学习过程的「学习排泄物（learning exhaust）」——博客、教程、答案、笔记——持续公开；主要受众是未来的自己，声誉与机会是副产品；「**尽力做对，但别怕做错。反复如此**」；80% 的开发者从不公开输出，公开者自然脱颖而出。
- **可信度**：中（理念文，无数据；但与 GitHub 上可见的实践分布一致：高 star 教程型仓库的作者获得巨大职业杠杆）。

### 19. Steve Yegge《Revenge of the Junior Developer》（初级开发者的复仇）
- **URL**：https://sourcegraph.com/blog/revenge-of-the-junior-developer
- **性质**：从业者长文（Yegge 时任 Sourcegraph Amp 布道者——**有利益关联，须知**）
- **时间**：2025-03-22
- **关键内容**（原文核实，注意为观点）：提出 vibe coding 六波浪潮（传统→补全→聊天→编码 agent→agent 集群→agent 舰队）；断言**新一代会用 AI 的初级开发者将反向压过不改变习惯的资深者**；名言：「**证明 AI 比你强不是 AI 的任务。用 AI 让自己变强才是你的任务（It's not AI's job to prove it's better than you. It's your job to get better using AI.）**」；「软件工程师的新工作将包含很少的直接编码，和大量的 agent 照看（agent babysitting）」；给所有工程师的技能建议：**「学会在新世界里验证与确认（validation and verification）如何做」**、把 taste 变成可指导 agent 的标准。
- **可信度**：中（文风夸张、多处为预测；agent 舰队等判断到 2026 年仅部分兑现）。但其「验证/taste 增值」与 SO 2025、Anthropic 官方立场收敛，可作规划参考；就业结论与 Stanford 数据相抵，需两案并读。
- 相关词条：**Karpathy「vibe coding」**（2025-02 发帖提出，该文引述）——已成行业标准词汇。

---

## 五、中文线：GitHub 中文社区共识文档

### 20. datawhalechina/llm-cookbook（面向开发者的 LLM 入门教程）
- **URL**：https://github.com/datawhalechina/llm-cookbook
- **性质**：中文开源教程（吴恩达大模型系列课程中文版，Datawhale 社区）
- **时间**：**24,719 star，最近 push 2025-06-12**（2026-09-19 查询）
- **关键内容**：prompt engineering、RAG、fine-tuning、agents 等课程的系统中文翻译与讲义，是中文开发者入场的公共知识底座。
- **可信度**：中高（社区协作、更新放缓，适合打基础而非追前沿）。

### 21. datawhalechina/hello-agents（《从零开始构建智能体》）
- **URL**：https://github.com/datawhalechina/hello-agents
- **性质**：中文开源教程
- **时间**：创建于 2025-09-07；**79,826 star，最近 push 2026-09-18**（调研前一天仍在更新——当前中文社区最活跃的 agent 教程）
- **关键内容**：从原理到实践的智能体系统教程，一年内冲到近 8 万 star，反映中文社区学习重心已从「用 prompt」整体迁移到「构建/编排 agent」。
- **可信度**：中高。与英文线（12-factor-agents、Anthropic 两篇）互相印证：**中英文社区的「下一层能力」共识都是 agent 工程**。

---

## 六、综合洞察（可执行结论）

1. **最增值的单项能力是「验证（verification）」：测试设计、代码评审、上线判断。**
   依据：SO 2025（46% 不信任 AI、66% 被「几乎对但差一点」折磨、45% 说调试 AI 代码更耗时）；Anthropic 官方第一原则「If you can't verify it, don't ship it」；DORA 2024/2025 显示组织稳定性随 AI 采用恶化。行动：把「AI 产出必须过我的验证关」制度化——先定验收标准/测试，再放 agent 干活（Claude Code 官方 TDD 建议、Harper Reed 全流程即此模式）。

2. **不要相信自己对「AI 提效」的体感，用测量校准工作流。**
   依据：METR RCT——开发者自估快 20%，实际慢 19%；METR 2026 后续显示工具确实在变快（约 +18%），但作者自认证据薄弱。行动：对高频任务（如车机模块改动）做简单计时/返工率对比，每季度重测一次；工具版本换代后结论会变，测试也要换代。

3. **AI 是放大器：领域纵深与系统性知识在升值，样板编码在贬值。**
   依据：DORA 2025「amplifier」主题；METR 的限定条件表明 AI 在「深度熟悉的大型成熟代码库」里增益有限——这类场景（车机/嵌入式/AOSP 正是）恰恰依赖人的领域判断；Canaries 显示「AI 增强（augmentation）」场景就业持平或上升、「替代（automation）」场景收缩。行动：把精力从写代码量转向**约束知识**（功耗/时序/安全/构建系统）与**跨模块架构**——这是 AI 最弱、组织最缺的部分。

4. **不同阶段策略必须分化：新人的入口在收窄，资深人的机会在「上移一层」。**
   依据：Canaries（22–25 岁高暴露职业就业低于反事实 19%，机制是减少招聘）；SO 2025（资深者最不信任 AI——他们见过代价）；Yegge（观点）主张新人靠 AI 反超、所有人补验证能力。行动——新人：尽早接触「验证、部署、客户责任」这些 AI 担不了责的环节，用 AI 补齐与资深者的产出差距；资深人：从「实现者」转型「**编排者+评审者**」，把 20 年经验写成 agent 可执行的规则/skill（见第 6 条）。

5. **AI 原生技能栈四支柱：规格化（spec）能力、上下文工程、agent 编排、系统设计。**
   依据：Harper Reed（spec 驱动 + 规则文件）；Anthropic《Building Effective Agents》与 12-factor-agents（上下文所有权、控制流所有权、小而专注）；Octoverse 2025（agent 仓库 +79%、TS 登顶——工具链主战场）；system-design-primer 37 万 star 的持续热度。行动：刻意练习「把模糊需求写成机器可执行的规格」；学一门 agent 编排的公共语言（TypeScript/Python 生态）以读懂工具链，哪怕主业是 Kotlin/Java。

6. **把已有资产复利化：markdown 知识库 → agent 规则/skill；学习过程 → 公开作品。**
   依据：Harper Reed 把 Obsidian 知识库变成 slash-commands；anthropics/skills 17.7 万 star 证明「可复用能力封装」本身成为被引用的资产；swyx《Learn In Public》（80% 开发者从不出声）；hello-agents/llm-cookbook 展示了中文社区以教程换影响力的真实路径。行动：从你的个人知识库中挑出重复出现的流程（如「排查系统崩溃」「OTA 升级验证」）封装成 skill/规则文件并公开沉淀。

7. **对 Android/车机/嵌入式方向的特别结论：护城河与摩擦是同一件事。**
   依据：METR（AI 在约束多、验证贵的领域增益有限）；SO 2025（45% 认为 AI 不擅长复杂任务）；Anthropic 经济指数（AI 用量集中在软件/网页类任务，车载/固件覆盖弱）。行动：真机、硬件在环、长构建链是你的「验证成本高地」——把其中可自动化的部分（日志解析、CTS/Sanity 用例、构建诊断）做成 agent 工作流，降自己的验证成本；同时你的领域约束知识不会被通用模型快速学会，可放心深耕。

8. **按数据而非按演讲做规划：预测会错，趋势不会等。**
   依据：Dario Amodei「3–6 个月 AI 写 90% 代码」未按期兑现（2025-10 核查），但同期 Octoverse 显示 AI 仓库 +79%、开发者年增 3,600 万——方向不可逆、斜率被高估是常态。行动：以「季度」为单位滚动更新个人规划（新工具→重测→调整技能投资），把 swyx 的「跟上 AI 是全职工作」降级为一个可持续的**每周固定学习时段 + 每季度一次工具实测**。

---

## 附：证据清单速览

| 来源 | 类型 | 时间 | 一句话结论 |
|---|---|---|---|
| Octoverse 2025 | 官方报告 | 2025-10 | 1.13M 新 AI/agent 仓库(+79%)，TypeScript 登顶，开发行为被 AI 重塑 |
| Octoverse 2024 | 官方报告 | 2024-10 | Python 超越 JS；AI 项目 +98%；73% 开发者已用 AI |
| DORA 2024 | 官方研究 | 2024-10 | AI 采用+25% ⇒ 吞吐 -1.5%、稳定性 -7.2% |
| DORA 2025 | 官方研究 | 2025-09 | 90% 采用；AI 是放大器；稳定性仍受损 |
| SO Survey 2025 | 行业调查 | 2025-07 | 84% 采用 vs 46% 不信任；资深者最警惕 |
| JetBrains SoDE 2025 | 行业调查 | 2025-10 | 85% 使用 AI，62% 依赖至少一个 AI 工具 |
| Anthropic Economic Index | 官方数据 | 2025-02 起 | 软件类任务是第一大用量（37–40%）；自动化占比上行 |
| Canaries（Stanford） | 论文 | 2025-08/2026-08 修订 | 年轻人高暴露职业就业 -19%；招聘收窄而非裁员 |
| METR RCT | RCT 研究 | 2025-07 | 资深开源者用 AI 实际慢 19%，自感快 20% |
| METR 2026 更新 | RCT 后续 | 2026-02 | 晚 2025 工具约快 18%，但「证据很弱」、选择效应严重 |
| Building Effective Agents | 官方方法 | 2024-12 | 从简单开始；workflow 优先于 agent；evals 核心 |
| Claude Code Best Practices | 官方方法 | 2025-04 | explore-plan-code-commit；无法验证就不要上线 |
| Harper Reed workflow | 从业者长文 | 2025-02 | 规格驱动 + 测试兜底 + 知识库变 prompt 资产 |
| 12-factor-agents | 仓库 | 2025 | Own your context/control flow；70–80% 框架陷阱 |
| Rise of the AI Engineer | 从业者长文 | 2023-06 | 定义 AI Engineer；evals 是核心资产 |
| Learn In Public | 从业者长文 | 2018-06 | 公开学习是复利资产 |
| Revenge of the Junior Developer | 从业者长文（观点） | 2025-03 | 验证与 taste 是新硬通货（观点，夸张文风） |
| llm-cookbook / hello-agents | 中文仓库 | 2024–2026 | 中文社区重心：LLM 入门 → agent 构建 |
