# A. Anthropic 官方 Agent Skills 生态调研

> 调研日期：2026-09-05。调研人：官方生态子任务（Agent Skills 全网调研 · A 部分）。
> 所有结论均标注来源 URL；英文关键句保留原文并附中文概括。

## 0. 来源清单与可用性说明

| # | 来源 | URL | 本次抓取方式 |
|---|------|-----|-------------|
| 1 | Agent Skills 官方规范（开放标准站） | https://agentskills.io/specification （Markdown 端点：`/specification.md`） | curl 直连成功 |
| 2 | 官方创作指南（Best practices / Optimizing descriptions / Evaluating skills / Using scripts / Quickstart） | https://agentskills.io/skill-creation/best-practices 等（各页加 `.md`） | curl 直连成功 |
| 3 | anthropics/skills 官方仓库 | https://github.com/anthropics/skills | GitHub API + raw.githubusercontent.com |
| 4 | 工程博客 "Equipping agents for the real world with Agent Skills" | https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills | curl 直连成功 |
| 5 | 发布公告 "Introducing Agent Skills" | https://www.anthropic.com/news/skills | curl 直连成功 |
| 6 | Claude Code skills 文档 | https://code.claude.com/docs/en/skills （Markdown 端点：`/docs/en/skills.md`） | curl 直连成功 |
| 7 | Claude Code plugins 文档 | https://code.claude.com/docs/en/plugins | curl 直连成功 |
| 8 | platform.claude.com 文档（overview / best-practices / quickstart） | https://platform.claude.com/docs/en/agents-and-tools/agent-skills/... | **本网络被地区封锁（307 → app-unavailable-in-region）**，其内容与 agentskills.io 创作指南同源（code.claude.com 的 skills 文档明确把 platform.claude.com 的 best-practices 页列为跨产品写作指南，且 agentskills.io 即官方标准站，README 直接指向它） |

说明：anthropics/skills 仓库的 `spec/agent-skills-spec.md` 现在只有一行指针："The spec is now located at <https://agentskills.io/specification>"（2025-12-18 起 Agent Skills 发布为开放标准）。因此本报告以 agentskills.io 为格式规范的一手权威来源。

---

## 1. 格式规范（agentskills.io/specification）

来源：https://agentskills.io/specification （全文已抓取为纯文本核对）

### 1.1 目录结构（约定）

```
skill-name/
├── SKILL.md      # Required: metadata + instructions
├── scripts/      # Optional: executable code
├── references/   # Optional: documentation
├── assets/       # Optional: templates, resources
└── ...           # Any additional files or directories
```

中文概括：一个 skill 最少就是一个含 SKILL.md 的目录；scripts/references/assets 是"推荐约定"而非强制，允许任意附加文件与目录（canvas-design 就用了自造的 `canvas-fonts/` 目录）。

### 1.2 SKILL.md 格式

原文："The SKILL.md file must contain YAML frontmatter followed by Markdown content."

Frontmatter 字段总表（规范原文约束）：

| 字段 | 必填 | 约束 |
|------|------|------|
| `name` | Yes | Max 64 characters. Lowercase letters, numbers, and hyphens only. Must not start or end with a hyphen. |
| `description` | Yes | Max 1024 characters. Non-empty. Describes what the skill does and when to use it. |
| `license` | No | License name or reference to a bundled license file.（建议简短，如 `Apache-2.0` 或 `Proprietary. LICENSE.txt has complete terms`） |
| `compatibility` | No | Max 500 characters. Indicates environment requirements (intended product, system packages, network access, etc.). |
| `metadata` | No | Arbitrary key-value mapping（字符串键值对 map）；"We recommend making your key names reasonably unique to avoid accidental conflicts" |
| `allowed-tools` | No | Space-separated string of pre-approved tools the skill may use. **(Experimental)** — 例：`allowed-tools : Bash(git:*) Bash(jq:*) Read` |

`name` 字段的完整规则（原文逐条）：
- Must be 1-64 characters
- May only contain unicode lowercase alphanumeric characters (`a-z`, `0-9`) and hyphens (`-`)
- Must not start or end with a hyphen (`-`)
- Must not contain consecutive hyphens (`--`)
- **Must match the parent directory name**（name 必须与所在目录名一致）

非法示例（规范原文）：`PDF-Processing`（大写不允许）、`-pdf`（不能以连字符开头）、`pdf--processing`（连续连字符不允许）。

`description` 字段（原文）：
- Must be 1-1024 characters
- Should describe both **what the skill does and when to use it**
- Should include **specific keywords that help agents identify relevant tasks**

好/坏对照（规范原文）：
```yaml
# 好
description: Extracts text and tables from PDF files, fills PDF forms, and merges multiple PDFs. Use when working with PDF documents or when the user mentions PDFs, forms, or document extraction.
# 坏
description: Helps with PDFs.
```

`compatibility` 示例（规范原文）："Designed for Claude Code (or similar products)" / "Requires git, docker, jq, and access to the internet" / "Requires Python 3.14+ and uv"。并强调："**Most skills do not need the compatibility field.**"

### 1.3 正文（Body content）

原文："The Markdown body after the frontmatter contains the skill instructions. **There are no format restrictions.** Write whatever helps agents perform the task effectively."

推荐章节（原文）：Step-by-step instructions / Examples of inputs and outputs / Common edge cases。

原文提醒："Note that the agent will load this entire file once it's decided to activate a skill. Consider splitting longer SKILL.md content into referenced files."

### 1.4 三个可选目录的定位

- `scripts/`："Contains executable code that agents can run. Scripts should: Be self-contained or clearly document dependencies; Include helpful error messages; Handle edge cases gracefully."
- `references/`："Contains additional documentation that agents can read when needed"，例：`REFERENCE.md`（技术参考）、`FORMS.md`（表单模板）、按领域拆分（`finance.md`、`legal.md`）。"**Keep individual reference files focused. Agents load these on demand, so smaller files mean less use of context.**"
- `assets/`："Contains static resources: Templates / Images / Data files (lookup tables, schemas)."

### 1.5 渐进式披露（Progressive Disclosure）——三层加载机制

规范原文（核心数字）：
> "Agents load skills progressively, pulling in more detail only as a task calls for it."
> - **Metadata (~100 tokens)**: The name and description fields are loaded at startup for all skills
> - **Instructions (< 5000 tokens recommended)**: The full SKILL.md body is loaded when the skill is activated
> - **Resources (as needed)**: Files (e.g. those in scripts/, references/, or assets/) are loaded only when required
> - "Keep your main SKILL.md **under 500 lines**. Move detailed reference material to separate files."

中文概括：第一层 frontmatter（约 100 token）启动时常驻所有 skill；第二层 SKILL.md 正文在触发时整体载入（建议 <5000 token / <500 行）；第三层附属文件按需读取，"the amount of context that can be bundled into a skill is effectively unbounded"（工程博客语，见 §4）。

注意：本规范文本中没有"嵌套 skill（skill 里再放 skill）"的机制；层级是"SKILL.md → 附属文件"的树。"嵌套/限定名"只出现在 Claude Code 的 monorepo 场景（多个 `.claude/skills/` 目录，见 §5.2），不是格式规范的一部分。

### 1.6 文件引用与校验

- 引用相对路径："use relative paths from the skill root"，例：`See [the reference guide](references/REFERENCE.md) for details.` / `Run the extraction script: scripts/extract.py`
- 原文："**Keep file references one level deep from SKILL.md.** Avoid deeply nested reference chains."（引用链保持一层，避免 A 引 B、B 引 C 的深链）
- 校验工具："Use the skills-ref reference library to validate your skills: `skills-ref validate ./my-skill`"

---

## 2. 官方最佳实践（agentskills.io/skill-creation/*）

来源：
- https://agentskills.io/skill-creation/best-practices
- https://agentskills.io/skill-creation/optimizing-descriptions
- https://agentskills.io/skill-creation/evaluating-skills
- https://agentskills.io/skill-creation/using-scripts
- https://agentskills.io/skill-creation/quickstart

### 2.1 Best practices 全要点

**（1）从真实专长出发（Start from real expertise）**
> 原文："A common pitfall in skill creation is asking an LLM to generate a skill without providing domain-specific context — relying solely on the LLM's general training knowledge. The result is vague, generic procedures ('handle errors appropriately,' 'follow best practices for authentication')..."

两条路径：
- **Extract from a hands-on task**：先与 agent 真实干完一件事，记下"Steps that worked / Corrections you made / Input-output formats / Context you provided"，再提炼成 skill。
- **Synthesize from existing project artifacts**：好素材是"Internal documentation, runbooks, and style guides; API specifications, schemas; Code review comments and issue trackers; Version control history; Real-world failure cases"——"The key is project-specific material, not generic references."

**（2）用真实执行来打磨（Refine with real execution）**
> "Run the skill against real tasks, then feed the results — all of them, not just failures — back into the creation process."
> "Even a single pass of execute-then-revise noticeably improves quality."

诊断指南（原文）："If the agent wastes time on unproductive steps, common causes include instructions that are too vague..., instructions that don't apply to the current task..., or too many options presented without a clear default."

**（3）精打细算地花 context（Spending context wisely）**
- "Add what the agent lacks, omit what it knows." 每条内容自问："**Would the agent get this wrong without this instruction?** If the answer is no, cut it."
  反例：解释"什么是 PDF"；正例：直接说"Use pdfplumber for text extraction. For scanned documents, fall back to pdf2image with pytesseract."
- **Design coherent units**："you want it to encapsulate a coherent unit of work that composes well with other skills"——太窄要加载多个 skill，太宽难以精准触发。
- **Aim for moderate detail**："Concise, stepwise guidance with a working example tends to outperform exhaustive documentation."
- **Structure large skills with progressive disclosure**：重申 <500 行 / <5000 token；关键是写清**何时**加载附属文件：
  > "'Read `references/api-errors.md` if the API returns a non-200 status code' is more useful than a generic 'see references/ for details.'"

**（4）校准控制力度（Calibrating control）**
- **Match specificity to fragility**：多解可容忍的任务给自由（解释 why 比死规定有效："an agent that understands the purpose behind an instruction makes better context-dependent decisions"）；脆弱操作要死命令（"Run exactly this sequence... Do not modify the command or add additional flags."）。"Most skills have a mix. Calibrate each part independently."
- **Provide defaults, not menus**：给默认工具 + 逃生舱，不要罗列等价选项。
- **Favor procedures over declarations**："A skill should teach the agent *how to approach* a class of problems, not *what to produce* for a specific instance."

**（5）五个可复用的写作模式（Patterns for effective instructions）**
1. **Gotchas sections**——"The highest-value content in many skills is a list of gotchas — environment-specific facts that defy reasonable assumptions."例：users 表软删除要加 `WHERE deleted_at IS NULL`。迭代诀窍："When an agent makes a mistake you have to correct, add the correction to the gotchas section."Gotchas 放在 SKILL.md 正文里（不是引用文件），因为"the agent may not recognize the trigger"。
2. **Templates for output format**——"agents pattern-match well against concrete structures"；短模板内联，长模板放 `assets/`。
3. **Checklists for multi-step workflows**——显式 `- [ ]` 进度清单防跳步。
4. **Validation loops**——干完活跑校验器，失败就修，过了才继续（含示例：edit → `python scripts/validate.py output/` → fail 则修 → 再跑）。
5. **Plan-validate-execute**——批量/破坏性操作先产出中间计划（如 `field_values.json`），用脚本对照事实源校验（"Errors ... give the agent enough information to self-correct"），再执行。
6. **Bundling reusable scripts**——"If you notice the agent independently reinventing the same logic each run ... that's a signal to write a tested script once and bundle it in scripts/."

### 2.2 Optimizing descriptions（触发优化）

来源：https://agentskills.io/skill-creation/optimizing-descriptions

机制（原文）："At startup, they load only the `name` and `description` of each available skill... **the description carries the entire burden of triggering.**"

重要细节（原文）："agents typically only consult skills for tasks that require knowledge or capabilities beyond what they can handle alone. A simple, one-step request like 'read this PDF' may not trigger a PDF skill even if the description matches perfectly."——简单任务即使匹配也不触发。

四条写作原则（原文）：
- "**Use imperative phrasing.** Frame the description as an instruction to the agent: 'Use this skill when...' rather than 'This skill does...'"
- "**Focus on user intent, not implementation.**"
- "**Err on the side of being pushy.** Explicitly list contexts where the skill applies, including cases where the user doesn't name the domain directly: 'even if they don't explicitly mention CSV or analysis.'"
- "**Keep it concise.**" 硬上限 1024 字符。

评测方法：约 20 条查询（8-10 应触发 + 8-10 不应触发），负例要用"**near-misses**"（关键词重叠但需求不同）；每条跑 3 次算 **trigger rate**（阈值 0.5）；**train/validation 60/40 分割**防过拟合；迭代优化时"Avoid adding specific keywords from failed queries — that's overfitting"；"Five iterations is usually enough"。文档明确：`skill-creator` skill 自动化了这个闭环。

### 2.3 Evaluating skills（输出质量评测）

来源：https://agentskills.io/skill-creation/evaluating-skills

核心方法：每个用例跑两次（**with_skill vs without_skill** 基线对比），测试用例存 `evals/evals.json`（prompt / expected_output / files / assertions）；断言要"programmatically verifiable"（"The bar chart has labeled axes" 好于 "The output is good"，也避免过于脆短的精确措辞断言）；grading 要"**Require concrete evidence for a PASS**"；汇总到 `benchmark.json`，看 `delta`（"A skill that doubles token usage for a 2-point improvement might not be worth it"）；迭代四原则：Generalize from feedback / Keep the skill lean / **Explain the why**（"Reasoning-based instructions ... work better than rigid directives"）/ Bundle repeated work。

### 2.4 Using scripts（脚本规范）

来源：https://agentskills.io/skill-creation/using-scripts

- 一次性命令可直接引用生态工具并**固定版本**（`uvx ruff@0.8.0`、`npx eslint@9`、`pipx run`、`bunx`、`deno run`、`go run`）；"State prerequisites in your SKILL.md rather than assuming"。
- 引用捆绑脚本用**相对 skill 根的路径**；"List available scripts in your SKILL.md so the agent knows they exist"。
- 自包含脚本用内联依赖声明：Python **PEP 723**（`# /// script` + `uv run`）等。
- 为 agent 设计脚本（全部是硬性建议）：
  - "**Avoid interactive prompts** — This is a hard requirement... A script that blocks on interactive input will hang indefinitely."（无 TTY）
  - "**Document usage with `--help`**"; "Keep it concise — the output enters the agent's context window"
  - "**Write helpful error messages**"："say what went wrong, what was expected, and what to try"
  - "**Use structured output**"（JSON/CSV/TSV）；"send structured data to stdout and progress messages... to stderr"
  - 其他：幂等（"Create if not exists is safer"）、拒绝歧义输入、`--dry-run`、有意义且文档化的 exit codes、安全默认（`--confirm`）、**可控输出体量**（"Many agent harnesses automatically truncate tool output beyond a threshold (e.g., 10-30K characters)... support flags like `--offset`"）

---

## 3. anthropics/skills 官方仓库

来源：https://github.com/anthropics/skills （元数据经 GitHub API，文件经 raw.githubusercontent.com，分支 main，抓取于 2026-09-05）

### 3.1 仓库元数据与顶层结构

- Stars：**173,993**；Forks：20,630；Open issues：1,208；创建于 2025-09-22；默认分支 main；license 字段为 null（各 skill 目录内有自己的 LICENSE.txt）。
- 顶层：`skills/`（19 个 skill）、`spec/`（仅一行指针指向 agentskills.io）、`template/`（官方 skill 模板）、`.claude-plugin/marketplace.json`（可注册为 Claude Code 插件市场）、`README.md`、`THIRD_PARTY_NOTICES.md`。
- README 关键信息（原文）：
  - "This repository contains skills that demonstrate what's possible with Claude's skills system."
  - 许可分层："**Many skills in this repo are open source (Apache 2.0). We've also included the document creation & editing skills that power Claude's document capabilities under the hood in the skills/docx, skills/pdf, skills/pptx, and skills/xlsx subfolders. These are source-available, not open source**, but we wanted to share these with developers as a reference for more complex skills that are actively used in a production AI application."（四个 document skill 的 frontmatter 写 `license: Proprietary. LICENSE.txt has complete terms`，其余写 `license: Complete terms in LICENSE.txt`）
  - 安装方式：`/plugin marketplace add anthropics/skills` → `/plugin install document-skills@anthropic-agent-skills` 或 `example-skills@anthropic-agent-skills`
  - 免责声明："These skills are provided for demonstration and educational purposes only."
  - Partner skills 区（Notion 等）。

官方模板 `template/SKILL.md`（全文）：
```markdown
---
name: my-skill-name
description: A clear description of what this skill does and when to use it
---

# My Skill Name

[Add your instructions here that Claude will follow when this skill is active]

## Examples
- Example usage 1
- Example usage 2

## Guidelines
- Guideline 1
- Guideline 2
```
README 注："The frontmatter requires only two fields: name ... (lowercase, hyphens for spaces); description ... A complete description of what the skill does and when to use it."

### 3.2 全部 19 个 skill 清单（按 frontmatter description 归纳；行数为 SKILL.md 实测行数）

| Skill | 行数 | 一句话用途（据 description） | 附属结构 |
|-------|------|------------------------------|----------|
| academy-guide | 148 | 回答"Claude 怎么用/怎么学"类问题时，推荐 Claude Academy（academy.claude.com）的匹配课程与教程；与其他 skill 组合使用 | 无 |
| algorithmic-art | 405 | 用 p5.js + 种子随机数创作生成艺术/算法艺术（流场、粒子系统），强调原创避免版权问题 | `templates/generator_template.js`、`templates/viewer.html` |
| brand-guidelines | 74 | 把 Anthropic 官方品牌色与字体规范应用到各类产出物 | 无（纯知识型，最短之一） |
| canvas-design | 129 | 以"设计哲学"方法创作 .png/.pdf 海报与视觉艺术，禁止抄袭在世艺术家风格 | `canvas-fonts/`（80 个 TTF 字体 + OFL 许可文本，自造顶层目录） |
| claude-api | 570 | Claude API/SDK 全栈参考（模型 id、定价、streaming、tool use、caching 等），description 里用 TRIGGER/SKIP 规则精确控制触发，正文含 8 种语言的参考文档树 | `shared/`（26 篇）、`python/ typescript/ go/ java/ ruby/ php/ csharp/ curl/` 各语言子目录 |
| discernment-nudge | 210 | 在给出实质性回答后追加 2-3 个供用户核查的追问；description 详细列举触发与豁免边界 | 无 |
| doc-coauthoring | 376 | 结构化的文档共创工作流（上下文移交→迭代打磨→读者验证） | 无 |
| docx | 92 | 创建/读取/编辑 Word .docx/.dotx（修订、批注、格式保持）；Proprietary | `scripts/`（OOXML 工具 + ISO 29500 XSD schema 全套 + validators） |
| frontend-design | 72 | 前端视觉设计指导：美学方向、排版，避免"模板化默认感" | 无 |
| internal-comms | 33 | 按公司惯用格式写内部通讯（状态报告、简报、FAQ 等） | `examples/`（4 个范例 md） |
| mcp-builder | 237 | 创建高质量 MCP 服务器的四阶段指南（Python FastMCP / TS SDK） | `reference/`（4 篇）、`scripts/`（connections/evaluation） |
| pdf | 315 | PDF 全能处理：读、拼、拆、旋转、水印、建 PDF、填表单、加解密、OCR | `forms.md`、`reference.md`、`scripts/`（9 个表单/校验脚本） |
| pptx | 239 | 任何 .pptx/.potx 输入输出的必用技能：pptxgenjs 新建 / XML 直编辑 / markitdown 读取；Proprietary | `scripts/`（thumbnail、add_slide、clean、office/validate 等 + XSD） |
| skill-creator | 486 | 创建、改进、评测 skill 的元技能：eval 驱动迭代 + 触发描述优化闭环 | `agents/`（grader/comparator/analyzer 子代理指令）、`references/schemas.md`、`scripts/`（7 个）、`eval-viewer/`、`assets/eval_review.html` |
| slack-gif-creator | 255 | 制作 Slack 优化的动图 GIF（约束、校验、动画概念） | `core/`（easing/gif_builder/validators）、`requirements.txt` |
| theme-factory | 60 | 给产出物套主题：10 个预设主题（色板/字体）或即时生成 | `themes/`（10 个主题 md）、`theme-showcase.pdf` |
| web-artifacts-builder | 74 | 构建复杂 claude.ai HTML artifact（React+Tailwind+shadcn/ui 多组件），不适合单文件简单件 | `scripts/`（init-artifact.sh、bundle-artifact.sh、shadcn 组件包 tar.gz） |
| webapp-testing | 96 | 用 Playwright 测试本地 Web 应用：验证前端、截图、看日志 | `scripts/with_server.py`、`examples/`（3 个） |
| xlsx | 100 | 电子表是主输入/输出时的必用技能（.xlsx/.xlsm/.csv/.tsv 读写、清洗、公式、图表）；Proprietary | `scripts/recalc.py`、`scripts/office/`（与 docx/pptx 共享的校验体系） |

观察：**SKILL.md 行数从 33 到 570 不等**（中位数约 130 行），大多数远低于 500 行上限；claude-api（570 行）是唯一超 500 行的，靠 8 个语言子目录把详细内容外移。**没有 references/ 目录的 skill 占多数**——"渐进披露第三层"可用任何自定义目录（themes/、core/、templates/、examples/、canvas-fonts/、agents/、reference/ 单数）实现，规范也说允许任意目录名。

### 3.3 四个代表性 skill 深读

#### 3.3.1 skill-creator（486 行，元技能典范）
来源：https://raw.githubusercontent.com/anthropics/skills/main/skills/skill-creator/SKILL.md

**正文结构**（章节序）：
1. 开篇总览（一段流程 bullet：Decide → Draft → Test prompts → Evaluate qualitatively & quantitatively → Rewrite → Repeat → Expand）+ 一句定位："Your job when using this skill is to figure out where the user is in this process and then jump in"（要求模型先判断用户处于流程哪一步，而非机械从头执行）。
2. `Communicating with the user`——按用户技术水平调节术语使用（"for 'JSON' and 'assertion' you want to see serious cues from the user that they know what those things are"）。
3. `Creating a skill`：Capture Intent（4 个访谈问题）→ Interview and Research → Write the SKILL.md（frontmatter 各字段怎么填）→ Skill Writing Guide（Anatomy / Progressive Disclosure / 安全原则 / Writing Patterns / Writing Style）→ Test Cases。
4. `Running and evaluating test cases`（Step 1-5 精确到脚本命令与目录命名：`<skill-name>-workspace/iteration-N/eval-<ID>/with_skill|without_skill/outputs/`）。
5. `Improving the skill`（4 条改进方法论）→ `The iteration loop`。
6. `Advanced: Blind comparison`、`Description Optimization`（Step 1-4，与 agentskills.io 优化指南同构）、`Package and Present`、`Claude.ai-specific instructions`、`Cowork-Specific Instructions`（按运行环境分支的适配指南）、`Reference files`、结尾复述核心闭环。

**指令风格**：
- 第二人称、对话式祈使句为主（"Cool? Cool."、"just vibe with me"），但在关键动作上转为强命令与全大写强调："**GENERATE THE EVAL VIEWER *BEFORE* evaluating inputs yourself**"、"do NOT use `/skill-test`"。
- 大量具体到可直接执行的命令（`python -m scripts.run_loop --eval-set ... --max-iterations 5`）+ 精确的 JSON schema 示例 + 目录树。
- 决策点写成显式分支（新建 skill 的 baseline = without_skill；改进旧 skill 的 baseline = 快照 old_skill）。

**关键原文句（写 skill 者必读）**：
- 关于 description："This is the primary triggering mechanism - include both what the skill does AND specific contexts for when to use it. **All 'when to use' info goes here, not in the body.** Note: currently Claude has a tendency to 'undertrigger' skills... **please make the skill descriptions a little bit 'pushy'**."
- 渐进披露三层（该 skill 自己的表述）："1. **Metadata** (name + description) - Always in context (~100 words) 2. **SKILL.md body** - In context whenever skill triggers (<500 lines ideal) 3. **Bundled resources** - As needed (unlimited, scripts can execute without loading)"
- 写作风格："**Try to explain to the model why things are important in lieu of heavy-handed musty MUSTs.** Use theory of mind..."
- 改进方法论："**Explain the why.** ... **If you find yourself writing ALWAYS or NEVER in all caps, or using super rigid structures, that's a yellow flag** — if possible, reframe and explain the reasoning"；"Keep the prompt lean. Remove things that aren't pulling their weight."；"**Look for repeated work across test cases.** If all 3 test cases resulted in the subagent writing a `create_docx.py`..., that's a strong signal the skill should bundle that script."
- 引用附属文件的方式：文末集中列出并说明何时读——"The agents/ directory contains instructions for specialized subagents. **Read them when you need to spawn the relevant subagent.**"

#### 3.3.2 pptx（239 行，生产级 document skill，source-available）
来源：https://raw.githubusercontent.com/anthropics/skills/main/skills/pptx/SKILL.md

**正文结构**：开篇一句心智模型（"A `.pptx` is a ZIP archive of XML files. Choose your approach by task:"）→ **三行决策表**（Create→pptxgenjs / Edit→unzip-edit-zip / Read→markitdown + thumbnail.py）→ Scripts 表（每个脚本的用途与关键 flag）→ Creating with pptxgenjs — gotchas（约 20 条 bullet 的坑列表）→ Editing existing decks and templates（命令序列 + 语义坑）→ Design Ideas（配色表、每页视觉元素、字号表、Avoid 列表）→ QA (Required)（内容 QA / 文件 QA / 视觉 QA 三段，各自给出命令与缺陷清单）→ Converting to Images → Dependencies。

**指令风格**：
- 高密度**祈使句 gotchas**，每条都是"命令 + 后果 + 修复"：如 "Set `pres.layout` before adding slides. The default canvas is `LAYOUT_16x9` = **10" × 5.625"**, not 13.3" wide. Coordinates past the edge are written, not clamped"；"Hex colors: never `#`, never 8 digits... **corrupt the file**"。
- **NEVER 句式用于风格红线**："**NEVER use accent lines under titles** — these are a hallmark of AI-generated slides"；"**NEVER add decorative color bars or accent stripes**"。这与 skill-creator 里"ALWAYS/NEVER 是 yellow flag"形成有趣对照：官方实践是——机械性技术坑用 MUST/NEVER 没问题，审美/判断类指导才需要解释 why。
- 强制校验闭环（对应 best-practices 的 Validation loops）："After `writeFile()`, run `python scripts/office/validate.py deck.pptx`"；"Your first render usually has a few real issues... Find and fix those, re-render only the slides you changed, and stop."
- 环境事实前置（"pptxgenjs is preinstalled — do not run `npm install` first"；"bare `soffice` hangs in this sandbox"）——典型的"agent 不知道的环境 gotchas"。

#### 3.3.3 mcp-builder（237 行，分阶段工作流 + 按需引用）
来源：https://raw.githubusercontent.com/anthropics/skills/main/skills/mcp-builder/SKILL.md

**正文结构**：Overview（一句话质量标准："The quality of an MCP server is measured by how well it enables LLMs to accomplish real-world tasks"）→ Process 四阶段：Phase 1 Deep Research and Planning（含推荐技术栈及理由）→ Phase 2 Implementation（Input/Output Schema、Tool Description、Annotations 的清单式要求）→ Phase 3 Review and Test → Phase 4 Create Evaluations（10 个 QA 对的 XML 格式）→ 文末 `📚 Documentation Library` 按加载时机分组列出引用文件（"Core MCP Documentation (Load First)" / "(Load During Phase 1/2)" / "(Load During Phase 4)"）。

**指令风格**：决策树 + checklist 混合；标题带 emoji 做视觉锚点（🚀📋🐍⚡✅）；引用附属文件时**显式写加载时机与条件**（"Load [✅ Evaluation Guide](./reference/evaluation.md) for complete evaluation guidelines"、"Use WebFetch to load https://raw.githubusercontent.com/..."）——这是"tell the agent WHEN to load each file"的最佳示范。对语言选型给出带理由的默认（"Recommended stack: TypeScript... AI models are good at generating TypeScript code"），体现 provide defaults, not menus。

#### 3.3.4 canvas-design（129 行，创意型 skill；附 web-artifacts-builder 对照）
来源：https://raw.githubusercontent.com/anthropics/skills/main/skills/canvas-design/SKILL.md

- 结构：两步流程（先写"设计哲学" md，再据其在画布上表达为 pdf/png）→ 大量全大写强调（"CRITICAL UNDERSTANDING"、"CRITICAL GUIDELINES"、"This is **VERY IMPORTANT**"）→ 内嵌 5 个"哲学示例"做 few-shot → 末段"FINAL STEP"预置了二轮打磨指令（"The user ALREADY said 'It isn't perfect enough...'"——把多轮对话预期直接写进单文件）。
- 有趣点：它要求重复强调"expert craftsmanship"（"**Emphasize craftsmanship REPEATEDLY**: ... repeat phrases like 'meticulously crafted,' ..."）——用提示工程技巧对抗"AI 平庸感"；附属的是 `canvas-fonts/` 80 个字体文件（assets 的变体），正文中指令"Search the `./canvas-fonts` directory"。
- 对照 web-artifacts-builder（74 行）：极简风——5 步 numbered workflow（调两个脚本）+ 一条风格红线（"VERY IMPORTANT: To avoid what is often referred to as 'AI slop', avoid using excessive centered layouts, purple gradients, uniform rounded corners, and Inter font."）+ 末尾 Reference。证明"如果逻辑都在脚本里，SKILL.md 可以非常短"。

---

## 4. 官方设计理念文章

### 4.1 工程博客："Equipping agents for the real world with Agent Skills"
来源：https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills （发表于 Oct 16, 2025；作者 Barry Zhang, Keith Lazuka, Mahesh Murag；文内更新注："Update: We've published Agent Skills as an open standard for cross-platform portability. (December 18, 2025)"）

**为什么用 skills（核心隐喻）**：
> "Building a skill for an agent is like putting together an **onboarding guide for a new hire**. Instead of building fragmented, custom-designed agents for each use case, anyone can now specialize their agents with composable capabilities by capturing and sharing their procedural knowledge."

**与上下文/工具的关系**：
- "Claude already knows a lot about understanding PDFs, but is limited in its ability to manipulate them directly (e.g. to fill out a form). This PDF skill lets us give Claude these new abilities."（skill 补的是"能做"而不仅是"知道"）
- **三层渐进披露**（首次系统阐述）："This metadata is the **first level of progressive disclosure**... The actual body of this file is the **second level**... These additional linked files are the **third level (and beyond)**, which Claude can choose to navigate and discover only as needed."
- 无限上下文："Agents with a filesystem and code execution tools don't need to read the entirety of a skill into their context window... **the amount of context that can be bundled into a skill is effectively unbounded.**"
- "Progressive disclosure is **the core design principle** that makes Agent Skills flexible and scalable. Like a well-organized manual that starts with a table of contents, then specific chapters, and finally a detailed appendix..."

**Skills 与代码执行**：
> "Large language models excel at many tasks, but certain operations are better suited for traditional code execution. For example, sorting a list via token generation is far more expensive than simply running a sorting algorithm. Beyond efficiency concerns, many applications require the **deterministic reliability** that only code can provide."
> "Claude can run this script **without loading either the script or the PDF into context**. And because code is deterministic, this workflow is consistent and repeatable."

**与 MCP 的区别/互补**（原文两处）："We'll also explore how Skills can complement Model Context Protocol (MCP) servers by **teaching agents more complex workflows that involve external tools and software**."——即 MCP 负责"连接工具"，Skills 负责"教会工作流"。（mcp-builder skill 与官方 MCP 文档的分界与此一致。）

**作者四条实践建议（原文标题级）**：
1. **Start with evaluation**："Identify specific gaps in your agents' capabilities by running them on representative tasks and observing where they struggle... Then build skills incrementally."
2. **Structure for scale**："When the SKILL.md file becomes unwieldy, split its content into separate files and reference them. If certain contexts are mutually exclusive or rarely used together, keeping the paths separate will reduce the token usage. Finally, code can serve as both executable tools and as documentation. It should be clear whether Claude should run scripts directly or read them into context as reference."
3. **Think from Claude's perspective**："Pay special attention to the name and description of your skill. Claude will use these when deciding whether to trigger the skill."
4. **Iterate with Claude**："ask Claude to capture its successful approaches and common mistakes into reusable context and code within a skill... This process will help you discover what context Claude actually needs, instead of trying to anticipate it upfront."

**安全提醒（原文）**："malicious skills may introduce vulnerabilities in the environment where they're used or direct Claude to exfiltrate data and take unintended actions. We recommend installing skills only from trusted sources... paying particular attention to code dependencies and bundled resources like images or scripts."

### 4.2 发布公告："Introducing Agent Skills"
来源：https://www.anthropic.com/news/skills （Oct 16, 2025；更新 Dec 18, 2025：组织级管理、合作伙伴 skill 目录、发布为开放标准）

- 定义："Skills are folders that include instructions, scripts, and resources that Claude can load when needed... **Claude will only access a skill when it's relevant to the task at hand.**"
- 四性质（原文）：**Composable**（"Skills stack together. Claude automatically identifies which skills are needed and coordinates their use"）/ **Portable**（"Build once, use across Claude apps, Claude Code, and API"）/ **Efficient**（"Only loads what's needed, when it's needed"）/ **Powerful**（"Skills can include executable code for tasks where traditional programming is more reliable than token generation"）。
- "Think of Skills as **custom onboarding materials** that let you package expertise."
- API 侧："/v1/skills endpoint gives developers programmatic control over custom skill versioning and management. Skills require the Code Execution Tool beta."
- 官方推荐创作入口："The 'skill-creator' skill provides interactive guidance: Claude asks about your workflow, generates the folder structure, formats the SKILL.md file, and bundles the resources you need."

---

## 5. Claude Code 中的 skills 落地方式

来源：https://code.claude.com/docs/en/skills （全文）、https://code.claude.com/docs/en/plugins

### 5.1 存放位置（四级）

| 级别 | 路径 | 作用范围 |
|------|------|----------|
| Enterprise | managed settings 目录下 `.claude/skills/`（如 Linux `/etc/claude-code/.claude/skills/`） | 组织全体用户 |
| Personal | `~/.claude/skills/<skill-name>/SKILL.md` | 本人所有项目 |
| Project | `.claude/skills/<skill-name>/SKILL.md` | 仅本项目 |
| Plugin | `<plugin>/skills/<skill-name>/SKILL.md` | 插件启用处 |

补充规则（均为文档原文要点）：
- **同名优先级**："enterprise overrides personal, and personal overrides project"；任何级别都能覆盖 bundled skill（如自带 `/code-review`）；plugin skill 用 `plugin-name:skill-name` 命名空间，不冲突；skill 与 `.claude/commands/` 文件同名时"**the skill takes precedence**"；claude.ai 同步 skill（`~/.claude/skills/synced/`，`synced` 是保留目录名）优先级最低。
- **发现范围**：项目 skill 从启动目录及其**向上到仓库根**的每一级 `.claude/skills/` 加载；启动目录**之下**的嵌套 `.claude/skills/`（monorepo 子包）在 Claude 首次读/改该子目录文件时才加载，重名时以 `apps/web:deploy` 这类目录限定名共存；`--add-dir` 附加目录的 skills 也会加载。
- **热更新**："Claude Code watches skill directories for file changes... picks up the change within the current session, without a restart."
- skill 目录可以是**符号链接**；在 skill 文件夹里放 `.claude-plugin/plugin.json` 即可作为名为 `<name>@skills-dir` 的插件加载（可捆绑 agents/hooks/MCP）。
- 分发（文档 §Share skills）："**Project skills**: Commit `.claude/skills/` to version control; **Plugins**: Create a `skills/` directory in your plugin; **Managed**: Deploy organization-wide through managed settings."

### 5.2 自动触发机制与手动调用

- 触发依据就是 description 匹配："Claude uses skills when relevant, or you can invoke one directly with `/skill-name`"；"the `description` helps Claude decide when to load the skill automatically"。
- "Custom commands have been merged into skills."（原文 Note）："A file at `.claude/commands/deploy.md` and a skill at `.claude/skills/deploy/SKILL.md` both create `/deploy` and work the same way."——slash command 与 skill 已统一；skills 额外提供：支持文件目录、frontmatter 控制调用方、自动加载。
- 命令名来源：个人/项目 skill 取**目录名**；plugin skill 的 frontmatter `name` 决定末段（`/my-plugin:fancy`）；`.claude/commands/` 取文件名。
- 两个方向的调用控制：
  - `disable-model-invocation: true`：只有用户能调（"You don't want Claude deciding to deploy because your code looks ready"），且 description 不进上下文；
  - `user-invocable: false`：只有 Claude 能调（背景知识型），菜单里隐藏。
- 生命周期："the rendered SKILL.md content enters the conversation as a single message and **stays there across later turns**"；auto-compaction 后每个 skill 保留前 5,000 tokens、合计 25,000 tokens 预算——"write guidance that should apply throughout a task as standing instructions rather than one-time steps."
- **skill 列表预算**（对 description 写作的实际约束）："Claude Code loads a listing of skill names and descriptions into context... The budget scales at **1% of the model's context window**"；"the combined `description` and `when_to_use` text is **truncated at 1,536 characters** in the skill listing"；超出预算时"Claude Code drops descriptions starting with the skills you invoke least"——**把关键用例写在 description 开头**（"Put the key use case first"）。

### 5.3 Claude Code 扩展 frontmatter（超出 agentskills.io 规范的部分）

文档明确区分标准字段与扩展（原文表格 "Using skill frontmatter outside Claude Code"）：
- 走规范分发的路径（claude.ai 上传、Skills API、`package_skill.py` 打包）**只允许 6 个规范字段**：`name, description, license, compatibility, metadata, allowed-tools`；多写字段会**硬报错**："Unexpected key(s) in SKILL.md frontmatter: argument-hint. Allowed properties are: allowed-tools, compatibility, description, license, metadata, name"。
- Claude Code 本地额外支持：`when_to_use`、`argument-hint`、`arguments`、`disable-model-invocation`、`user-invocable`、`disallowed-tools`、`model`、`effort`、`context: fork`（在隔离子代理中运行）、`agent`、`background`、`hooks`、`paths`（glob 限定触发文件范围）、`shell`。
- **allowed-tools 在 Claude Code 中**：仅当轮免批准（"The grant clears when you send your next message"），且"Workspace trust doesn't gate this field... review the allowed-tools of skills checked into a repository before you run Claude Code there"（供应链安全提示）。
- 字符串替换：`$ARGUMENTS`/`$N`/`$name`、`${CLAUDE_SKILL_DIR}`、`${CLAUDE_PROJECT_DIR}`、`${CLAUDE_SESSION_ID}`、`${CLAUDE_PLUGIN_ROOT}` 等；`!` 反引号`` !`git diff HEAD` ``动态上下文注入（运行 shell 把输出内联进 prompt）。
- 配套文档还给出 `skillOverrides` 设置（on / name-only / user-invocable-only / off 四态）与权限规则（`Skill(commit)`、`Skill(deploy *)`）。

### 5.4 触发问题的官方排障（原文要点）

- 不触发：加关键词 → "Check the description includes keywords users would naturally say"；frontmatter YAML 坏了时"Claude Code loads the skill body with empty metadata"，用 `claude plugin validate .claude/skills` 体检（v2.1.233+）。
- 过度触发："Make the description more specific"或加 `disable-model-invocation: true`。
- 描述被截断：见 5.2 的 1536 字符 cap 与 1% 预算；用 `/context`、`/doctor` 观测。

---

## 6. 综合：官方生态给 skill 作者的规则清单（按重要性）

1. **description 决定生死**：1-1024 字符、祈使句、写"what + when"、含具体触发关键词、适度 pushy、把最重要的用例放开头（Claude Code 1536 字符截断 + skill 列表只占上下文 1% 预算）。所有"何时使用"信息放 description，不放正文。（§1.2 / §2.2 / §3.3.1 / §5.2）
2. **三层渐进披露是唯一架构原则**：frontmatter ~100 token 常驻 → SKILL.md <500 行 / <5000 token 触发时全量加载 → references/scripts/assets 按需。引用附属文件必须写清"何时读"（条件式指针），引用链保持一层。（§1.5 / §2.1 / §4.1）
3. **name 必须 = 目录名**：1-64 字符、小写字母数字连字符、不连写 `--`、不以 `-` 开头结尾；跨平台分发只允许 6 个 frontmatter 字段，Claude Code 扩展字段带不出去。（§1.2 / §5.3）
4. **只写 agent 不知道的事**：逐条自问 "Would the agent get this wrong without this instruction?"；环境 gotchas 是最高价值内容且放正文；给默认方案而非选项菜单；解释 why 优于 ALL-CAPS 命令（技术性硬红线除外）。（§2.1 / §3.3）
5. **确定性的事交给脚本**：重复出现的逻辑打包进 `scripts/`；脚本要无交互、--help、结构化输出、stdout/stderr 分离、幂等、可控输出体量；PEP 723 内联依赖 + 固定版本。（§2.4 / §4.1）
6. **eval 驱动迭代**：with_skill vs without_skill 基线对比 + 断言打分（要 evidence）+ description 触发率评测（20 条、near-miss 负例、train/validation 60/40、5 轮）；官方 `skill-creator` 全自动化此闭环。（§2.2 / §2.3 / §3.3.1）
7. **格式极简**：最少只需 SKILL.md + 两个 frontmatter 字段（官方 template 即如此）；目录与章节组织全凭任务需要——19 个官方 skill 里 33 行到 570 行并存。（§3.2）
8. **安全**：skill 即代码，来自不可信来源要先审计（依赖、脚本、外联指令）；仓库内 skill 的 `allowed-tools` 也应复查。（§4.1 / §5.3）
