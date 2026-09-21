# C · 如何写好一个 Agent Skill（SKILL.md）——写法方法论

> 调研日期：2026-09-05。来源分两类：
> - **本地**：ZCode 官方 skill-creator 插件、superpowers 流派 writing-skills（含 4 个附属文件）、Matt Pocock 流派 writing-for-agents（含 SKILL-MECHANICS.md）
> - **网络**：Anthropic 官方文档与 anthropics/skills 仓库、agentskills.io（Agent Skills 开放规范站）、Perplexity 工程博客、Minko Gechev（Angular 团队）指南、Roland Huß、Simon Willison、Han Chung Lee 深度解析
>
> 引用标注格式：`[本地:路径]` 或 `[URL]`。

**来源清单**
- [本地A] /home/liang/.zcode/cli/plugins/cache/zcode-plugins-official/skill-creator/0.1.0/skills/skill-creator/SKILL.md（ZCode 插件版 skill-creator）
- [本地B] /home/liang/.agents/skills/writing-skills/SKILL.md（superpowers 写法，TDD 流派）
- [本地B1] /home/liang/.agents/skills/writing-skills/anthropic-best-practices.md（官方 best-practices 的镜像）
- [本地B2] /home/liang/.agents/skills/writing-skills/testing-skills-with-subagents.md（子代理测试法）
- [本地B3] /home/liang/.agents/skills/writing-skills/persuasion-principles.md（说服原则）
- [本地C] /home/liang/.agents/skills/writing-for-agents/SKILL.md + SKILL-MECHANICS.md（Matt Pocock 面向 agent 写作文）
- [官方文档] https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices
- [官方仓库] https://github.com/anthropics/skills → skills/skill-creator/SKILL.md（比本地A多出完整 eval 与 description 优化闭环）
- [规范站1] https://agentskills.io/skill-creation/best-practices
- [规范站2] https://agentskills.io/skill-creation/optimizing-descriptions
- [Perplexity] https://research.perplexity.ai/articles/designing-refining-and-maintaining-agent-skills-at-perplexity
- [Gechev] https://github.com/mgechev/skills-best-practices（README）
- [Huß] https://ro14nd.de/cc-skill-patterns/
- [Willison] https://simonwillison.net/2025/Oct/16/claude-skills/
- [Lee] https://leehanchung.github.io/blogs/2025/10/26/claude-skills-deep-dive/

---

## ① 格式规范速查

**目录结构**（[本地A][官方文档]）
```
my-skill/
├── SKILL.md          # 必需：YAML frontmatter + Markdown 正文
├── references/       # 可选：按需阅读的参考文档
├── scripts/          # 可选：可执行的辅助脚本
└── assets/           # 可选：模板、fixtures 等产物资产
```

**Frontmatter 硬约束**
| 字段 | 规则 | 来源 |
|---|---|---|
| name | 必填；小写字母/数字/连字符；1–64 字符；不得连续连字符；**必须与目录名完全一致**；避开保留词 `anthropic-helper`、`claude-tools`；name/description 内不用 XML 标签、括号等特殊字符 | [官方文档][Gechev][本地B] |
| description | 必填；**上限 1024 字符**；第三人称；这是唯一的路由/触发信号 | [规范站2][官方文档][本地B] |
| 其余字段 | 可选字段见 agentskills.io 规范；大多数 skill 只需 name + description | [本地A][本地B] |

**长度预算**（"上下文是公共财"）
- SKILL.md 正文 **< 500 行 / 约 5000 tokens**（[规范站1][官方文档]）
- skill 元数据约占 100 tokens，**常驻每次对话**（[Perplexity] 三层成本：Index（元数据，永远付费）→ Load（正文，触发时付费）→ Runtime（附属文件，按需付费）[Lee]）
- superpowers 更狠的预算：常加载的 skill <200 词，其他 <500 词，`wc -w` 验证（[本地B]）
- **>300 行的 reference 文件顶部必须放目录（TOC）**，否则 agent 用 `head -100` 预览会漏信息（[官方仓库]；100 行以上即建议 TOC [官方文档]）

**其他格式军规**
- 文件路径一律正斜杠，即使 Windows（[官方文档]）
- MCP 工具用全限定名 `ServerName:tool_name`，否则 "tool not found"（[官方文档]）
- 依赖显式写出：先 `pip install pypdf` 再用，不要假设已安装（[官方文档]）
- 时效性内容收进 "Old patterns" 折叠小节，不在正文写"某年某月之后用新 API"（[官方文档]）
- 不放 README/CHANGELOG/docs 等对 agent 无用的文件（[Gechev]）
- 三种调用发现路径优先级：项目 `.zcode/skills` > 项目 `.agents/skills` > 用户级；同名 skill 高优先路径覆盖低优先路径——新 skill 放 `.agents/skills/`，想覆盖官方 skill 就复制到用户级改（[本地A]）

---

## ② description / 触发词写法（最重要的一段）

**机制真相**（[Lee][官方仓库]）
- 启动时只有所有 skill 的 name+description 进系统提示；Claude **据此决定是否加载正文**。description 是 skill 唯一的"广告位"。
- Claude 只对"它自己不容易完成的任务"才去查 skills——**简单的一步任务可能根本不触发**；多步骤复杂任务触发更可靠。[官方仓库]
- 模型整体倾向 **under-trigger（该触发不触发）**，所以官方建议 description 要写得"略带推销性（a little bit pushy）"。（[本地A][官方仓库]）

**内容规则**
1. **既写 what 也写 when**："description 是主触发信号——skill 做什么、在什么上下文用，都应该写在这里，而不是正文里。"（[本地A]）官方示例格式：`Extract text and tables from PDF files, fill forms, merge documents. Use when working with PDF files or when the user mentions PDFs, forms, or document extraction.`（[官方文档]）
2. **必须含具体触发条件**，以 "Use when..." 开头（Perplexity 变体："Load when ..."），列举具体的症状、场景、用户用语。（[本地B][Perplexity]）
3. **第三人称**。description 会被注入系统提示，人称不一致会破坏发现："✅ Processes Excel files and generates reports；❌ I can help you process Excel files"。（[官方文档][本地B]）
4. **pushy 示范**（[本地A] 原文翻译）：不要写"如何为内部数据构建仪表盘"，要写"如何为内部数据构建**快速**仪表盘。**每当用户提到仪表盘、数据可视化、内部指标，或想展示任何公司数据时使用——即使他们没明说'仪表盘'。**"
5. **关键词覆盖**：放 agent 会搜的词——真实报错文本（"Hook timed out"、"ENOTEMPTY"）、症状词（flaky/hanging/zombie）、同义词（timeout/hang/freeze）、工具与文件类型名。可搜的词要"早放、常放"。（[本地B]）
6. **描述问题而非语言症状**：写"race conditions、时序依赖"而非"setTimeout"；skill 若绑定特定技术，就在触发条件里明说该技术。（[本地B]）
7. **负向触发**：写明"不要用于 X"（"Don't use it for Vue, Svelte ..."），并用 near-miss（似是而非）反例做评测。（[Gechev][Perplexity][官方仓库]）
8. **触发词来自真实用户语言**：把用户实际会说的话写进去。例：监控 PR 的 skill，用户说的是 "babysit / watch CI / make sure this lands"，而不是文档腔"监控 CI 状态"。（[Perplexity]）
9. **一个分支一个触发词**：同义词只是把同一个分支写了两遍；收缩成真正不同的分支。指针（description）里每个词常驻上下文，**前置关键词、狠剪身份信息**。（[本地C]）
10. **不要概括工作流**（superpowers 与 Perplexity 的核心警告）：实测中 description 写"任务间做 code review"，agent 就只照 description 做一次 review，**不再读正文里要求的两阶段 review**。"概括工作流的 description 会制造一条 agent 会走的捷径，正文沦为被跳过的文档。"（[本地B][Perplexity]）
    > ⚠️ 流派分歧标注：官方文档说 description = "what it does + when to use"（[官方文档]）；superpowers/Perplexity 说**只写触发条件、绝不写流程**（[本地B][Perplexity]）。可操作的合成：**功能一句话 + 触发条件具体展开 + 流程细节一个字不提**。
11. 长度经验值：< 500 字符（[本地B]）；≤ 50 词（[Perplexity]）。远低于 1024 上限即好。

**触发优化的工程闭环**（[官方仓库][Perplexity]——官方 skill-creator 的方法）
- 写 **约 20 条评测 query**：8–10 条应触发 + **8–10 条不应触发的 near-miss**（相邻技能、主题相近但实际不适用）；query 要像真人输入一样具体详细（具体路径、列名、口语甚至错别字）。（[官方仓库][本地A]）
- 每条 query 跑 **3 次**（LLM 触发有随机性，单次样本会撒谎）；统计触发率，设阈值（Perplexity 用 0.5）。
- **60/40 切分训练/验证集**，最多迭代 5 轮改写 description，按**验证集**得分选最优版本（防止过拟合）。（[官方仓库]）
- 词级改动影响巨大且有"外溢"：改一个词可能让原本正常的邻居 skill 被误触发——每次改 description 要回归测全部相关 skill。（[Perplexity]）
- 纪律型 skill 还应把"**即将违规**的症状"写进 description（"Use when ... or when manually testing seems faster"）。（[本地B2]）

---

## ③ 正文结构模式

**推荐骨架**（[本地B] 模板 + 各源综合）
```
# Skill Name
## Overview            # 1-2 句核心原则
## When to Use         # 症状/场景清单 + When NOT to use；决策不显然时配小型流程图
## Core Pattern        # Before/After 代码对照（技术型）
## Quick Reference     # 可扫读的表格
## Implementation      # 简单模式内联代码；重引用链接到文件
## Common Mistakes     # 常见错误 + 修复
## Real-World Impact   # （可选）具体效果
```

**写作规则**
- **信息阶梯**：文档由 steps（有序动作）与 reference（按需查阅的规则/事实）构成；每个内容件放在阶梯上"agent 多急需用"的那一级：文内步骤 → 文内参考 → 指针后的披露参考。（[本地C]）
- **完成判据**：每个步骤以完成判据收尾，最强的判据"既可检查又要求穷尽"——"每个被改动的模型都被覆盖"逼出彻底工作，"产出一份变更列表"则不然；判据模糊会诱发**提前完成**（premature completion）。（[本地C]）
- **共置（co-location）**：同一概念的定义、规则、注意事项放同一标题下；"文档读起来应该像为 agent 写的文档"。（[本地C]）
- **分支即披露测试**：所有分支都要用的内容内联，只有部分分支够得着的内容推到指针后。（[本地C]）
- **自由度校准**（"窄桥与开阔地"类比，[官方文档][规范站1]）：
  - 高自由（文字指引）：多解、看上下文（代码 review）
  - 中自由（带参伪代码/模板）：有偏好模式、允许变化（报告生成）
  - 低自由（精确脚本、零参数）：操作脆弱、一致性攸关（数据库迁移："Run exactly this script. Do not modify the command."）
- **默认值而非菜单**：给一个默认方案 + 逃生舱（"扫描件 OCR 改用 pdf2image+pytesseract"），不要罗列 5 个可选库。（[官方文档][规范站1]）
- **写步骤不写宣言**：可执行的动作序列（checklist 可让 agent 复制进回复逐项勾选）胜过抽象原则。（[规范站1][官方文档]）
- **示例胜过规则**："Examples beat rules." 要结构化输出就放字面示例；commit message skill 给 2–3 组输入/输出对照。（[本地A][官方文档]）
- **一个卓越示例 > 多个平庸示例**，不要 5 种语言各来一份；示例要完整可运行、注释解释 why、来自真实场景。（[本地B]）
- **术语全程一致**：选定"field"就别混用 box/element/control。（[官方文档]）
- **正向措辞**："别想大象——大象就是全部"；禁令会把被禁行为拉进上下文。先写目标行为（"write one-line comments"），禁令只留给无法正面表述的硬护栏，且成对给出正向目标。（[本地C]）
- **解释 why 而非加大写 MUST**："现代模型在有理由时更守规矩。如果你发现自己在写全大写 ALWAYS/NEVER，那通常说明这条规则需要更好的解释，而不是更响的执行。"（[本地A][官方仓库]）
- **leading word（引导词）**：用模型预训练里已有的紧凑概念（_tracer bullets_、_red_、_tight_）反复作为 token 而非句子出现，一个词锚定一片行为；自造词不招募先验，要用现成词。（[本地C]）
- **格式匹配失败类型**（[本地B]，写作前先分类基线失败）：
  | 基线失败 | 正确形式 | 错误形式 |
  |---|---|---|
  | 压力下违反规则 | 禁令 + 合理化表 + red flags | 软指引（"prefer..."） |
  | 服从但输出形状错 | 正面配方：说清输出**是什么**（部件+顺序） | 禁令清单 |
  | 漏掉应产出的元素 | 模板里设 REQUIRED 槽位 | 模板旁的散文提醒 |
  | 行为应依赖条件 | 挂在可观察谓词上的条件句 | 无条件规则+豁免条款 |
- **流程图只用于**非显然决策点、可能提前停的循环、"A 还是 B"；参考材料用表格、线性指令用编号列表、代码用代码块，标签必须有语义。（[本地B]）
- **纪律型 skill 的防合理化加固**：显式封堵每个具体借口（"删掉重来。无例外：不要留作参考、不要边写测试边改、删就是删"）；早置基石原则"违反规则的字面就是违反规则的精神"；建合理化对照表；建 Red Flags 自检清单。（[本地B][本地B3]——权威/承诺/稀缺/社会证明对 LLM 有效，N=28000 实证，合规率 33%→72%；但 Liking/Reciprocity 反而有害）

---

## ④ scripts / references 拆分原则

**什么时候用 scripts 而不是 prose**（[官方文档][规范站1]）
- **确定性操作写成脚本执行**：脚本比 agent 现场生成的代码更可靠、省 token（只付输出不付源码）、省时间、跨次一致。"Prefer scripts for deterministic operations."
- **重复出现的 helper 固化**：测试中每次都重新发明同一段脚本/同样多步流程 → 写一次放进 `scripts/`，skill 指向它。（[本地A][官方仓库]）
- **solve, don't punt**：脚本要处理错误条件（FileNotFoundError → 建默认文件），而不是抛给 agent；常量要有注释依据（"HTTP 请求通常 30s 内完成，超时放宽到 60s"），不留 voodoo constants（`TIMEOUT = 47 # Why 47?`）。（[官方文档]）
- **写明执行还是阅读**："Run `analyze_form.py` to extract fields"（执行，最常用）vs "See `analyze_form.py` for the algorithm"（当参考读）。绝大多数应执行。（[官方文档]）
- **验证环**：run validator → fix errors → repeat，是"极大提升输出质量"的通用模式。（[官方文档]）
- **plan-validate-execute**：批量/高危/复杂校验操作先产出结构化计划文件（changes.json），脚本验证通过再执行；验证脚本报错要啰嗦到能指导修复（"Field 'signature_date' not found. Available fields: ..."）。（[官方文档][规范站1]）
- **可视化辅助**：能转图片的输入（PDF 页面）转给 agent 看，利用视觉能力。（[官方文档]）

**什么时候拆 references**（[官方文档][本地B][本地A]）
- 渐进披露三模式：①高层指南+引用文件（Quick start 内联，进阶链接出去）②按领域拆（bigquery 的 finance/sales/product/marketing 各一个 md，问到销售只加载 sales.md）③条件细节（基础正文 + "For tracked changes: See REDLINING.md"）。（[官方文档]）
- **引用只一层深**：SKILL.md → advanced.md 就到头；嵌套引用会让 agent 部分阅读、信息不完整。（[官方文档]）
- 内联保留：原则概念、<50 行代码；拆出：100+ 行重引用、可复用工具/模板。（[本地B]）
- 拆分的两把刀（[本地C]）：**按序列**（后续步骤诱使 agent 赶进度时，把 post-completion 步骤藏出视野）与**按调用**（见 SKILL-MECHANICS）。
- **模型调用 vs 用户调用**：模型调用的 skill 要付 description 常驻上下文的税，换来 agent 自主触发与其他 skill 可达；只靠手敲调用的设 `disable-model-invocation: true`，零上下文税但人必须记得它。多个 skill 共享的参考，若两者都是用户调用（互不可达），就推到 skill 体系外的普通文件。用户调用的 skill 多到记不住时，做一个 **router skill** 汇总索引。（[本地C SKILL-MECHANICS]）

---

## ⑤ 什么该做成 skill，什么不该

**该做**（三条测试，全部命中才做）
1. **基线会失败**："如果 agent 没有这条指令就会做错"（"Would the agent get this wrong without this instruction?"）。（[规范站1][Perplexity]）
2. **不是模型默认就会的**：模型已知的通用知识做成 skill 平均零收益（Perplexity 引述研究原话："self-generated skills provide no benefit on average"）。（[Perplexity][规范站1]）
3. **可复用且跨场景**：不是一次性的、跨项目再次用到、他人也受益。（[本地B]）

典型值得做：训练数据里没有的内部知识（表 schema、企业流程、过滤规则如"永远排除测试账号"）、需要**一致性**输出的任务、品味与领域经验、复杂多步工作流。（[Perplexity][官方文档]）

**不该做 / 移走**（[本地B][Perplexity][Huß]）
- 一次性方案；别处已有良好文档的标准实践。
- **项目特定约定** → 放 CLAUDE.md/AGENTS.md 等指令文件，不做成 skill。
- **能用 regex/校验器强制执行的机械约束 → 自动化（hooks），文档只留给判断题**："Skills are suggestions, not instructions"——skill 管不住的纪律，用 hook 挡。（[本地B][Huß]）
- 系统提示已覆盖的内容（避免与系统提示"争抢"，改系统提示前先检查 skill 间竞争）。（[Perplexity]）
- 快变的信息（易过期的端点、版本 API）。（[Perplexity][官方文档]）
- 环境 自己能查到的（package.json scripts、`--help`、目录结构）——文档复述环境就是在造一份必然过期的缓存；**只缓存查不到的**：不成文的约定、决策背后的理由、配置文件不会招认的坑。（[本地C]）

**每句话的税测**（[Perplexity]）："Every skill is a tax"——逐句问"没有这句 agent 会做错吗？"不会，就删。

---

## ⑥ 反模式清单

**description 反模式**
1. ❌ 概括工作流 → agent 走捷径不读正文（实测案例：两阶段 review 被做成一次）。（[本地B][Perplexity]）
2. ❌ 第一/第二人称："I can help you..."。（[官方文档]）
3. ❌ 空洞："Helps with documents" / "Processes data" / "Does stuff with files"。（[官方文档]）
4. ❌ 太抽象没有触发条件："For async testing"。（[本地B]）
5. ❌ 提及技术但 skill 并非绑定该技术："Use when tests use setTimeout/sleep"。（[本地B]）
6. ❌ 同义词堆成多个分支，浪费常驻 token。（[本地C]）

**正文反模式**
7. ❌ 叙事文（"In session 2025-10-03, we found empty projectDir caused..."）——太具体不可复用；skill 是经过验证的技术的参考指南，不是解题故事。（[本地B]）
8. ❌ 多语言示例稀释质量与维护性。（[本地B]）
9. ❌ 流程图里塞代码、step1/helper2 无语义标签。（[本地B]）
10. ❌ 选项菜单不给默认（"You can use pypdf, or pdfplumber, or PyMuPDF, or..."）。（[官方文档]）
11. ❌ 全大写 MUST/NEVER 轰炸——是"该解释 why"的黄旗。（[本地A][官方仓库]）
12. ❌ 禁令加 nuance 豁免（"Don't X unless it matters"重开谈判口子；豁免条款"this limit doesn't apply to code blocks"照样压制代码块）——真例外写成独立条件句。（[本地B]）
13. ❌ 禁令用于"输出形状"问题——实测禁令臂比对照组更糟；用正面配方。（[本地B]）
14. ❌ Windows 反斜杠路径；❌ 深层嵌套引用（advanced.md → details.md → 真内容）。（[官方文档]）
15. ❌ `@路径` 强加载其他 skill——瞬间烧掉 200k 上下文；引用 skill 只写名字+必需性标记（`**REQUIRED SUB-SKILL:** Use xxx`）。（[本地B]）
16. ❌ 混入 README/CHANGELOG 等杂物；❌ 无关文件让 agent 在错误方向上分心。（[Gechev]）
17. ❌ 假设包装/工具已安装；❌ 无谓的 voodoo 常量。（[官方文档]）
18. ❌ sediment（沉积层）：只加不减，陈旧层越积越厚；❌ no-op 句（模型默认就会照做的指令白付 token）——判定是"相对模型默认"而非"相对读者"，办法是跑起来看。（[本地C]）
19. ❌ 重复另一文档已有的含义（违反单一事实源）；注意与 leading word 的刻意重复 token 相区分。（[本地C]）
20. ❌ 多层目录里每层都塞泛化概述——300 个主题时"单文件夹比没有 skill 还糟"。（[Perplexity]）
21. ❌ 批量造 skill 不逐个测试；写完一个必须停下走完部署检查单再写下一个。（[本地B]）
22. ❌ 虚假/有害 skill——官方"不惊讶原则"（Principle of Lack of Surprise），skill 不得含恶意代码或误导。（[官方仓库]）

---

## ⑦ 测试与迭代方法

**总纲：写 skill 就是给流程文档做 TDD**（[本地B]）
> "Writing skills IS Test-Driven Development applied to process documentation." 铁律：**NO SKILL WITHOUT A FAILING TEST FIRST**——新 skill 和改现有 skill 都适用；没先看到失败就写的 skill，删掉重来。

**RED—GREEN—REFACTOR 循环**
- **RED（基线）**：不带 skill 跑压力场景，**逐字**记录 agent 的选择与合理化借口（"I already manually tested it"、"Tests after achieve same goals"）。不知道 agent 自然会怎么错，就不知道 skill 该防什么。（[本地B][本地B2]）
- **GREEN（最小实现）**：只针对基线里真实出现的失败写最小 skill，不给假想情况加内容；带 skill 重跑，应合规。（[本地B]）
- **REFACTOR（堵漏）**：agent 冒出新借口 → 加显式反驳 → 更新合理化表/red flags/description 症状 → 重测，直到无新借口（"bulletproof"标志：agent 在最大压力下选对、能引用 skill 原文、承认诱惑但守规）。（[本地B2]）

**压力场景设计**（[本地B2]）
- **3+ 种压力组合**才有效：时间（上线窗口 5 分钟后关闭）+ 沉没成本（已写 200 行）+ 权威（老板说直接上）+ 疲惫（晚上 6 点半）+ 后果；单压力测不出问题。
- 强制 A/B/C 选择、真实文件路径、让 agent"行动"而非"背书"；"差场景问 What does the skill say?（学术背诵）好场景问 What do you do?（被迫决策）"。
- **Meta-testing**：agent 仍选错时问"这个 skill 怎么写才能让你明确只有 A 可选？"——三种回答对应三种修法（原则不狠/文档缺失/组织不醒目）。
- **词句微测试**（便宜的一线验证，[本地B]）：每次调用用单条新鲜样本；**必带无指导对照组**（对照不失败就没事可修，别写）；每变体 5+ 次；命中项人工逐条读（模板回声会冒充命中）；**方差是指标**——五次五种解释说明措辞没约束力，先收紧形式再加字。

**按 skill 类型选测法**（[本地B]）：纪律型（TDD 类）→ 压力+学术+组合压力；技术型 → 应用/变体/缺信息场景；模式型 → 识别/应用/反例；参考型 → 检索/应用/覆盖缺口（纯参考型可免压力测试）。

**官方的评测驱动开发与 Agent A/B 法**（[官方文档][官方仓库][本地A]）
- **Evals 先行**：先在代表性任务上跑无 skill 的 agent，记录真实缺口 → 建 ≥3 条评测（query+文件+期望行为清单）→ 量基线 → 写刚够过的最小内容 → 迭代对照。官方仓库 skill-creator 内置 evals.json/grading.json/基准聚合（pass_rate、耗时、token 数，均值±标准差），with-skill 与 baseline 同轮并行跑，grader 子代理按断言打分。
- **Agent A / Agent B**：A 帮你起草与改写（让它删掉 agent 本来就懂的解说、优化信息架构），B 是加载 skill 的新实例跑真实任务；观察 B 在哪挣扎，带回具体观察给 A 改——"基于观察到的行为而非假设来迭代"。
- **观察 agent 的导航行为**：意外阅读顺序=结构不直观；没跟上引用=链接不醒目；反复读同一文件=该内容该上浮到正文；从不打开的附属文件=多余或信号不足。
- **多模型测试**：Opus 怕过度解说、Haiku 怕指引不足，跨模型使用就取中间态。（[官方文档]）
- **从反馈泛化**：在几个样例上迭代，但 skill 要服务没见过的输入；顽固问题换框架/隐喻，而不是继续叠规则——"过拟合的小规则和压迫性 MUST 让 skill 随时间变差"。（[本地A][官方仓库]）
- **迭代节奏**（[本地A] 核心循环）：意图 → 草稿 → 2–3 条真实测试 prompt → 与用户同看输出（同时看结果和 trace：忙乱绕圈=过度规定或不清，该删不该加）→ 改进 → 循环直到满意或改进不再生效。
- **维护飞轮**（[Perplexity]）：agent 犯错→追加 gotcha；误加载→收紧 description+加负例；没加载→补关键词+加正例；每次追加新 skill 后回归测邻居 skill（改动会"隔空"打破别人）；改描述前先跑 eval 再动手。

---

## 附：一页速记（写作时按序自检）
1. 这事模型不做就会错、且非一次性？（⑤）
2. name 合法且=目录名；description：第三人称、what+when、"Use when..."具体触发、真实用户词、负向触发、无流程概括、≤1024 字符（②）
3. 正文 <500 行：Overview/When to Use(+NOT)/核心模式/Quick Reference/Common Mistakes（③）
4. 步骤有完成判据；概念共置；分支决定内联还是拆出（③）
5. 确定性操作 → scripts/（solve don't punt）；重引用一层深、>300 行带 TOC（④）
6. 默认值而非菜单；示例优于规则；解释 why 而非吼 MUST；正向措辞（③）
7. RED（基线逐字记录失败）→ GREEN（最小）→ REFACTOR（合理化表堵漏）；20 条触发评测含 near-miss、每条 3 次、60/40 切分（⑦②）
