# D. 本地 Agent Skills 全量盘点（2026-09-05）

- 范围 A：`/home/liang/.agents/skills/` —— **58 个** skill 子目录，均含 SKILL.md（实测无一缺失）。
- 范围 B：`/home/liang/.zcode/cli/plugins/cache/zcode-plugins-official/` —— 10 个官方插件，含历史版本共 **27 个** SKILL.md 文件；按最新版本去重后 **18 个** skill。
- 方法：find/awk 提取全部 frontmatter 与行数统计；逐篇 Read 深读 9 个代表性 skill（任务要求 8 个）。

---

## 一、完整清单（目录 A，58 个）

> 流派判定依据：`superpowers:` 前缀互引、`docs/superpowers/` 路径、"your human partner" 措辞 → superpowers（obra/superpowers）；`ask-matt` 路由器点名的 repo 成员、`setup-matt-pocock-skills` / CONTEXT.md / ADR / issue-tracker 体系 → Matt Pocock；frontmatter `metadata.author: Google LLC` → Google 官方；中文描述、本机绝对路径 → 用户自写。

### 1. superpowers 流派（15 个，平均约 226 行）

| 名称 | 一句话用途 | 领域分类 |
|---|---|---|
| using-superpowers | 元技能：会话开始时建立"先查 skill 再回答"的纪律，含各平台工具参考 | 元技能/入口 |
| brainstorming | 任何创造性工作前的需求探索：spike/bounded/architectural 三路径分类 + 硬性审批门 | 开发流程-需求设计 |
| writing-plans | 把 spec 写成"零上下文工程师可执行"的逐任务实现计划（bite-sized 步骤+禁占位符） | 开发流程-规划 |
| executing-plans | 在新会话中按计划批量执行并在检查点停下等复核 | 开发流程-执行 |
| subagent-driven-development | 每任务派发全新子代理 + 两阶段评审的执行编排（568 行，本地第二长） | 开发流程-执行编排 |
| test-driven-development | 任何功能/修 bug 前先写失败测试的 TDD 全流程（320 行） | 开发流程-测试 |
| systematic-debugging | 四阶段调试纪律：先查根因再动手，3 次修复失败即质疑架构 | 调试 |
| using-git-worktrees | 执行计划前确保隔离工作区（原生工具或 git worktree 兜底） | Git/版本管理 |
| dispatching-parallel-agents | 2 个以上无共享状态任务时的并行子代理派发 | 执行编排 |
| requesting-code-review | 完成任务/合并前按清单请求代码评审 | 评审与质量 |
| receiving-code-review | 接收评审意见时要求技术严谨核实，禁止表演性同意 | 评审与质量 |
| verification-before-completion | 声称"完成/修好/通过"之前必须先跑验证命令拿证据 | 评审与质量 |
| finishing-a-development-branch | 实现完成、测试通过后决定集成方式（合并/PR/丢弃） | Git/版本管理 |
| resolving-merge-conflicts | 处理进行中的 merge/rebase 冲突（仅 14 行短流程） | Git/版本管理 |
| writing-skills | 创建/编辑/验证 skill 的方法论（679 行，本地最长，含 examples/） | 元技能/skill 工程 |

### 2. Matt Pocock 流派（33 个，平均约 70 行）

| 名称 | 一句话用途 | 领域分类 |
|---|---|---|
| ask-matt | 路由器：按处境推荐本 repo 的 skill/流水线（idea→ship 主流程图） | 元技能/入口 |
| setup-matt-pocock-skills | 一次性配置 repo 的 issue tracker、triage 标签、领域文档布局 | 工程配置 |
| grilling | 原语：对计划/决策/想法进行无情追问 | 需求/思维磨砺 |
| grill-me | 7 行壳：转发调用 grilling（无工作区时用） | 需求/思维磨砺 |
| grill-with-docs | grilling + 过程中沉淀 ADR 与术语表（有工作区时首选） | 需求/思维磨砺 |
| loop-me | 针对"想构建的工作流"在本工作区内盘问出 spec | 需求/思维磨砺 |
| wait-what | "刚才那句话没立住"——要求重新陈述 | 沟通/思维磨砺 |
| to-spec | 把当前会话综合成 spec（含 XML 模板）发布到 issue tracker，不再盘问 | 需求/Issue 管理 |
| to-tickets | 把计划/spec/会话拆成带阻塞边的 tracer-bullet 工单并发布 | 需求/Issue 管理 |
| to-questionnaire | 把自己答不了的决策做成问卷给别人填 | 协作 |
| triage | 把 issue/外部 PR 推过 triage 状态机并产出 agent-ready 简报 | 需求/Issue 管理 |
| wayfinder | 超单会话的大工程：在 tracker 上画"决策工单地图"逐张解决直到路线清晰（128 行） | 规划/超大任务 |
| implement | 按工单实现一块工作（内部驱动 tdd，收尾跑 code-review） | 开发流程-执行 |
| implement-spec | 把一份 spec 实现为代码 | 开发流程-执行 |
| tdd | red→green 循环参考：好测试的标准、反模式（38 行精简版，与 superpowers 版重叠） | 开发流程-测试 |
| code-review | 双轴评审（Standards+Spec）并行子代理审查 diff | 评审与质量 |
| diagnosing-bugs | 疑难 bug/性能回归的诊断循环：先建紧密反馈回路再理论化（与 superpowers 调试技能重叠） | 调试 |
| codebase-design | deep module 设计词汇表：接口深化、接缝选择 | 架构/词汇层 |
| domain-modeling | 构建项目领域模型，维护 CONTEXT.md 与 ADR | 架构/词汇层 |
| improve-codebase-architecture | 扫描深化机会→HTML 报告→盘问选定项 | 架构 |
| prototype | 造一次性原型回答设计问题（状态模型/UI 手感） | 探索 |
| research | 对高可信一手来源做调研并落成 repo 内 Markdown | 调研/学习 |
| teach | 在工作区内教用户一项新技能（含 MISSION/GLOSSARY 等格式文件） | 学习 |
| handoff | 把会话压缩成交接文档给下一个 agent | 会话/协作 |
| claude-handoff | handoff 变体：直接 `claude --bg` 启动后台代理接着干（用户改写痕迹） | 会话/协作 |
| wizard | 生成交互式 bash 向导，引导人类完成只有人能做的步骤（配 template.sh） | 运维/一次性流程 |
| migrate-to-shoehorn | 测试文件从 `as` 断言迁移到 @total-typescript/shoehorn | TS/重构 |
| scaffold-exercises | 搭建练习目录骨架（sections/problems/solutions，过 lint） | 教学/课程制作 |
| setup-pre-commit | 装 Husky+lint-staged+类型检查+测试的 pre-commit | 工程配置 |
| setup-ts-deep-modules | dependency-cruiser 接入 TS repo 实现深模块封装 | 工程配置 |
| writing-for-agents | 给 agent 写文档（改 skill/AGENTS.md/CLAUDE.md 时用） | 写作/元技能 |
| writing-fragments | 写作-探索：挖掘原始碎片，不设结构 | 写作 |
| writing-shape | 写作-利用：逐段把素材塑成文章 | 写作 |
| writing-beats | 写作-利用：把素材组装成节拍旅程，用前先解释术语 | 写作 |

### 3. Google 官方 Android（3 个，metadata.author: Google LLC）

| 名称 | 一句话用途 | 领域分类 |
|---|---|---|
| android-cli | `android` CLI 安装与使用：建项目、跑应用、管 SDK/AVD、截图、查官方文档 | Android 专项 |
| android-intent-security | AndroidManifest 组件与 Intent 处理代码的安全审计最佳实践（530 行） | Android 专项/安全 |
| r8-analyzer | 分析构建文件与 R8 keep 规则，找冗余/过宽规则（references/ 8 文件） | Android 专项/体积优化 |

### 4. 用户自写/中文（7 个）

| 名称 | 一句话用途 | 领域分类 |
|---|---|---|
| source-annotator | 源码标注解释：中文注释写进源码（16 标签体系）+ 宏观结论沉淀库级文档 + 跨库知识库（332 行，v1.22） | 学习/源码研读 |
| session-to-knowledge | 会话知识沉淀进固定知识库（三步：要主旨→候选清单→确认写入，含脱敏与冲突裁决） | 学习/知识管理 |
| session-to-skill | 把会话蒸馏成可复用 SKILL.md 装进 .agents/skills/（中英混合触发词） | 元技能/skill 工程 |
| excel-srs-skill | 自然语言需求→填好的 Excel 软件需求规格说明书（shared/ 规则束+CLI 校验） | 文档生成/需求 |
| markdown-typography-expert | Markdown 排版规范（name 字段为"Markdown Typography Expert"，关键词触发式描述） | 文档生成 |
| git-guardrails-claude-code | 配置 Claude Code PreToolUse hook 拦截危险 git 命令 | Git/安全护栏 |
| claude-handoff | （已列 Pocock 段）handoff 的 `claude --bg` 后台代理改写版 | 会话/协作 |

### 目录 B：官方插件 skills（按最新版本，18 个）

| 插件@版本 | skill | 行数 | 附属结构 |
|---|---|---|---|
| document-skills@0.1.4 | pdf | 984 | references/scripts/configs/briefs/env_setup/typesetting 共 40 文件 |
| document-skills@0.1.4 | pptx | 645 | 单文件（仅 LICENSE） |
| document-skills@0.1.4 | xlsx | 291 | engines/scenes/templates/quality/env_setup + xlsx.py + setup.sh 共 26 文件 |
| document-skills@0.1.4 | docx | 251 | references/routes/scenes/scripts/env_setup 共 41 文件 |
| browser-use@0.4.2 | control-browser | 183 | 单文件 |
| browser-use@0.4.2 | web-gui-tester | 157 | 单文件 |
| skill-creator@0.1.0 | skill-creator | 159 | 单文件 |
| zcode-guide@0.1.0 | zcode-configuration-guide | 99 | 单文件 |
| zcode-guide@0.1.0 | diagnosing-mcp / -hooks / -commands / -skills / -plugins | 97/66/66/61/59 | 全单文件 |
| computer-use@0.5.14 | computer-use | — | 与 zcode-cua@0.5.12 的 zcode-computer-use 描述几乎相同（双插件冗余） |
| android-emulator@0.1.0 | android-dev | — | 单文件 |
| ios-simulator@0.1.0 | ios-dev | — | 单文件 |
| restore-legacy-sessions@0.1.0 | restore-legacy-sessions | — | 单文件 |

---

## 二、结构统计（真实数字）

### 2.1 SKILL.md 行数分布（目录 A，n=58）

- 总行数 7342；**最短 7 行 / 最长 679 行 / 中位数 90 行 / 均值 126.6 行**。
- **Top 10**：writing-skills 679｜subagent-driven-development 568｜android-intent-security 530｜source-annotator 332｜test-driven-development 320｜android-cli 284｜systematic-debugging 283｜brainstorming 250｜finishing-a-development-branch 225｜receiving-code-review 205。
- **Bottom 5**：grill-me 7｜grill-with-docs 7｜wait-what 7｜research 12｜resolving-merge-conflicts 14。
- 分流派均值：superpowers ≈ **226 行**/篇；Matt Pocock ≈ **70 行**/篇；Google Android ≈ 294 行；用户自写 ≈ 111 行。两流派相差 3 倍以上。

### 2.2 frontmatter 字段使用频次（目录 A）

| 字段 | 出现次数 | 说明 |
|---|---|---|
| name | 58 | 全覆盖；仅 markdown-typography-expert 用大写带空格名（与目录名不一致） |
| description | 58 | 全覆盖 |
| disable-model-invocation | 22 | 几乎全是 Pocock 系"斜杠命令式"skill（只许人显式调用） |
| argument-hint | 6 | claude-handoff、handoff、loop-me、teach、excel-srs-skill、source-annotator |
| metadata | 4 | Google 3 个 + source-annotator（version/sources） |
| license | 3 | Google 3 个 |
| allowed-tools | 2 | excel-srs-skill、source-annotator |

无一个 skill 使用 `model`/`context`/`agent` 等其他字段；目录 B 的 document-skills 用 `metadata.author/version`。

### 2.3 附属目录与文件

- **agents/ 目录：36 个**——但内容全部只有一个 `openai.yaml`（interface 显示名 + policy.allow_implicit_invocation），是 Codex/OpenAI 兼容加载的元数据层，不是知识内容；全部属于 Pocock 系 + claude-handoff/git-guardrails。
- **references/：4 个**（android-cli、r8-analyzer、source-annotator、using-superpowers）；**scripts/：5 个**（brainstorming、diagnosing-bugs、git-guardrails-claude-code、source-annotator、subagent-driven-development）；**examples/：1 个**（writing-skills）；**shared/：1 个**（excel-srs-skill，规则束+Python CLI）。
- 多数 skill 的补充知识以**根目录散置 .md**（如 systematic-debugging 的 root-cause-tracing.md、defense-in-depth.md；teach 的 4 个 FORMAT.md；prototype 的 LOGIC.md/UI.md；Pocock 系的 domain.md/issue-tracker-*.md），而非标准 references/ 目录。
- **正文是否链接附属文件**：14 个 skill 正文出现 references/|scripts/|assets/|examples/|templates/ 路径。真实链接良好的典型：source-annotator（8 处 references/、1 处 scripts/，并规定"落笔前必读格式卡"）、subagent-driven-development（8 处 scripts/）、r8-analyzer（16 处 references/）、systematic-debugging（指向同目录 3 个技术文件）。**两处悬空/越界引用**：teach 正文 4 处引用 `./assets/` 但该目录不存在；executing-plans 引用的是兄弟 skill 的 `../using-superpowers/references/`（跨 skill 相对路径，换布局即断）。其余 44 个 skill 完全自包含单文件。

---

## 三、深读分析（9 篇）

### 3.1 brainstorming（superpowers，250 行）

- **触发词/描述**：强制性指令式描述——"You MUST use this before any creative work…"，用 MUST 抢占触发；覆盖 creative work 全场景。
- **正文结构**：HARD-GATE（XML 风格标签）→ 三路径分类（spike/bounded/architectural，要求"说出分类让用户可否决"）→ 反模式表（Red Flags：7 行"念头 vs 现实"对照）→ 分路径 Checklist → **dot 语法的完整流程图** → 详细过程小节 → Visual Companion（just-in-time 提供浏览器可视化，且要求 offer 必须单独成一条消息）。
- **语气**：对"你"（agent）说话，大量 MUST/STOP/NEVER 全大写；称用户为 "your human partner"；甚至预写好要向用户说的话术引文。
- **亮点**：审批门不可跳过（"简单任务恰恰是未审视假设造成浪费的地方"）；路径只升不降的棘轮规则；dot 流程图把状态机写死。
- **短板**：250 行全量进上下文，对小型任务偏重；visual-companion 等依赖（scripts/ 7 个文件）在非 Claude 环境不一定可用。

### 3.2 systematic-debugging（superpowers，283 行）

- **触发词/描述**：极简 "Use when encountering any bug, test failure, or unexpected behavior, before proposing fixes"——时机型触发（出 bug 时、提修复前）。
- **正文结构**：Iron Law（代码块写死 "NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST"）→ 适用/勿跳过场景 → 四阶段（根因调查→模式对比→单假设最小验证→实现修复），每阶段带具体命令示例（bash 多层取证脚本）→ "3 次修复失败即质疑架构"升级规则 → Red Flags 清单 → 用户口头信号表（"Stop guessing" 等）→ Common Rationalizations 借口-现实对照表 → Quick Reference 表 → 同目录 3 个附属技术文件（root-cause-tracing.md 等）。
- **语气**：全大写命令 + 对"你"说话；"违反流程字面即违反调试精神"这类格言式表述。
- **亮点**：把"科学方法"写成可执行步骤；借口表直接对冲模型的走捷径倾向；失败计数器（<3 回炉、≥3 停止并升级）。
- **短板**：与 Pocock 的 diagnosing-bugs 触发场景高度重叠（都是"debug this"），存在触发竞争。

### 3.3 writing-plans（superpowers，171 行）

- **触发词/描述**："Use when you have a spec or requirements for a multi-step task, before touching code"。
- **正文结构**：Overview（"假设执行者零上下文、品味存疑"）→ 开场白话术（要求宣布"我正在用 writing-plans skill"）→ Scope Check → 文件结构原则 → 任务粒度（每步 2-5 分钟一个动作）→ **计划文档模板**（header 模板 + Task N 完整 Markdown 模板，含 Files/Interfaces/勾选步骤/测试代码/commit 命令）→ No Placeholders 禁止清单（"TBD"“Similar to Task N”都是计划失败）→ Self-Review 三查（覆盖率/占位符/类型一致）→ 执行交接话术（子代理 vs 内联二选一）。
- **语气**：对"你"说话，模板+禁令混合；内置"Announce at start"元指令。
- **亮点**：Interfaces 块解决"任务间只知自己任务"的协调问题；显式写出向用户提供的二选一话术。
- **短板**：强绑 `docs/superpowers/plans/` 路径与 `superpowers:` 前缀技能名，脱离原 repo 需要改。

### 3.4 grill-me（Matt Pocock，7 行）

- **触发词/描述**："A relentless interview to sharpen a plan or design." 一句话定义，无 Use when；`disable-model-invocation: true`（纯斜杠命令）。
- **正文结构**：正文一句话——"Call the Skill tool with 'grilling'." 纯转发壳。
- **语气**：对 agent 的单条祈使句。
- **亮点**：原语/壳分层——grilling 是原语，grill-me（无工作区）、grill-with-docs（有工作区留痕）是场景壳；7 行即完成适配，是"组合优于复制"的极致。
- **短板**：对不理解"skill 互调"的宿主（无 Skill tool 的环境）完全失效；无任何兜底。

### 3.5 to-spec（Matt Pocock，75 行）

- **触发词/描述**："Turn the current conversation into a spec and publish it…：no interview, just synthesis"——明确否定句界定边界（不盘问）。
- **正文结构**：先决条件检查（tracker 未配置则让用户跑 /setup-matt-pocock-skills）→ 3 步流程（探仓库→定测试接缝并与用户确认→按模板写 spec 并打 ready-for-agent 标签）→ `<spec-template>` XML 标签包裹的完整模板（Problem Statement/Solution/超长编号 User Stories/Implementation Decisions/Testing Decisions/Out of Scope/Further Notes），模板内嵌反规则（"不要写文件路径与代码片段，会很快过时"）与原型代码例外。
- **语气**：对"你"说话的简洁祈使；规则写在模板内部就近约束。
- **亮点**：与 issue tracker/triage 标签体系联动形成闭环；"接缝（seam）"决策显式找用户确认。
- **短板**：依赖 setup-matt-pocock-skills 先行配置，冷启动体验差；无 tracker 时的降级路径未写明（wayfinder 有，to-spec 没有）。

### 3.6 wayfinder（Matt Pocock，128 行，加读）

- 正文结构：叙事化开篇（"a loose idea wrapped in fog"）→ 原则节（Plan don't do / Refer by name）→ 地图 issue 模板（Destination/Notes/Decisions so far/Not yet specified/Out of scope）→ 工单四类型（Research-AFK / Prototype / Grilling / Task，HITL-AFK 标注）→ "战争迷雾"晋升规则 → 两种调用模式各 5-6 步。亮点：迷雾-工单判据（"能否现在精确陈述问题"）；HITL 纪律（"代理不得替人类回答盘问"）；每会话只解一张工单的上下文卫生。短板：概念密度极高，是"最需要配合整套体系（tracker/grilling/domain-modeling）才能跑"的技能。

### 3.7 pptx（官方 document-skills，645 行单文件）

- **触发词/描述**：极短——"Create and edit pptx file via pptxgenjs/python-pptx"，靠工具名关键词触发；frontmatter 带 `metadata: author: Z.AI, version: "1.1"`、license 专有。
- **正文结构**：两大部分。Part 1 幻灯片设计最佳实践（10 小节：配色三分法/版式/图表优先/图文层叠/字体/间距/CJK 字体/"AI 味"避免清单/QA），写作风格是设计教师式的原理解释 + 硬规则（"never below 12pt"“No emoji”）；Part 2 是 pptxgenjs API 深度手册（Setup/文本/形状/图片/表格/图表/母版），大量可复制代码块 + 阴影参数表等速查表 + 常见坑（负 offset 会损坏文件）。
- **语气**：中性技术手册+设计指南混合体；不用对"你"说话的第二人称命令（偶有 "you" 但整体是文档腔）。
- **亮点**：把"审美决策"编码为可执行规则（背景 60-70% 权重、主色 1-2 辅色、强调 5-10%）；预览失真告警（LibreOffice 字体替换导致溢出误判）是实战教训；同插件 pdf/xlsx/docx 则走"重附属目录"路线（references/scripts/templates 各 26-41 文件），单文件 vs 文件树两种打包策略并存。
- **短板**：645 行全量加载成本高（对比 pdf 的 984 行但可按 references/ 分包）；description 太短，触发依赖用户点名文件类型。

### 3.8 source-annotator（用户自写中文，332 行，v1.22）

- **触发词/描述**：中文长描述，`>` 折叠块：一句定位（注释承载微观、沉淀文档承载宏观）+ 触发场景枚举（标注源码/加中文注释/讲解类或调用链/学习库实现）+ 适用范围（语言无关）。frontmatter 最丰富：argument-hint、allowed-tools、metadata.version/sources（列出 8 个方法来源，如 redis-3.0-annotated、Ousterhout）。
- **正文结构**：编号节 + 小数节（§0-§7）：铁律 6 条 → 输入契约与升级提问（调用 grill-with-docs）→ 分析流程 7 步 → 注释规范（16 标签体系表格、六类代码判型表、论断四级分级、行宽自适应）→ 文档沉淀（机制章四小节模板、幂等更新）→ 固定四段输出格式 → **Quality Gates 16 条自检** → **Must Not Do 12 条**。
- **语气**：对 agent 的规约体（铁律/禁令/必读），大量"绝不/必须/先…再"；穿插"真实教训"案例（如 Glide 流行说法与源码相反、覆盖审计单口径误报被用户质疑）作论据。
- **亮点**：本机最精密的工程化 skill——机械校验脚本（scripts/check_annotations.py 九项一键+自测试）与语义自评（删除测试/专名回查/链条检查）双层质量门；`[inferred]` 论断分级防注释幻觉；三个附属 references 文件承担版式细节避免主文件无限膨胀。
- **短板**：332 行 + 必读格式卡，上下文成本全局域最高；强绑本机绝对路径（/home/liang/Project/MyProject/...）；16 条自检执行一遍本身消耗大。

### 3.9 session-to-knowledge（用户自写中文，61 行）

- **触发词/描述**：中文描述内置口语触发短语（"总结本次会话并沉淀""把这次会话学到的写入知识库"）+ **分流规则**（可复用流程走 session-to-skill、交接走 handoff、源码标注走 source-annotator）——描述本身就是路由器；disable-model-invocation: true。
- **正文结构**：定位句（"你不是在写总结报告，而是在维护一份'当前最可信'的知识库"）→ §0 该不该自己干的四条判断 → §1 固定路径与骨架 → §2 三步流程（要主旨→候选清单含状态三档/脱敏→确认后写入，写入规则含并入/修订/冲突裁决/取代标记）→ §3 收尾汇报 → Edge cases 4 条。
- **语气**：对"你"说话的规约体 + 决策判据式（"没有结果也算成功，不要硬凑"）。
- **亮点**：知识库当数据库维护（同名并入、修订注记、冲突不静默裁决而交用户、`取代于：`保留旧条目）；脱敏规则前置；状态三档（已验证/会话推断/待验证）防把推断写成事实。
- **短板**：路径写死本机；与 source-annotator 的知识库共用靠约定而非机制。

---

## 四、总结

### 4.1 已覆盖的场景

需求与规划（brainstorming/grilling 系/writing-plans/to-spec/to-tickets/wayfinder）、执行与测试（implement/executing-plans/subagent-driven-development/双 TDD）、调试（systematic-debugging/diagnosing-bugs）、评审与验证（code-review 双轴/请求与接收评审/完成前验证）、Git（worktree/收尾/冲突/护栏）、会话与知识（handoff 系/session-to-skill/session-to-knowledge/source-annotator/teach）、写作（fragments/shape/beats）、Office 文档（docx/xlsx/pptx/pdf）、Android 三件套、浏览器与 GUI 测试、TS 工程配置。

### 4.2 明显缺口（"想用 skill 提效的开发者"视角，Top 5）

1. **前端/UI 开发缺失**：无 frontend-design/组件规范/设计系统类 skill——brainstorming 正文还引用了未安装的 frontend-design；web-gui-tester 只做黑盒测试不做实现。
2. **测试专项薄弱**：只有 TDD 方法论两份，无 Playwright/e2e 编写、性能基准、测试数据构造、覆盖率策略类 skill。
3. **数据与后端空白**：无 SQL/数据库迁移/API 契约设计/性能调优专项（diagnosing-bugs 只是泛化调试）。
4. **Git 协作链不完整**：有 worktree/冲突/收尾，缺 PR 描述生成、changelog/release、版本号策略。
5. **中文写作与发布链缺失**：writing-* 三件套是英文写作流派；中文技术博客/公众号排版发布、README 生成、双语技术文档没有覆盖（markdown-typography-expert 仅管排版规则）。

### 4.3 三方风格对比

| 维度 | superpowers（15 篇） | Matt Pocock（33 篇） | 官方插件（18 篇） |
|---|---|---|---|
| 长度 | 平均约 226 行，长文为主 | 平均约 70 行，最短 7 行 | 两极：99-645 行，或超长单文件 |
| 结构 | Overview→铁律→阶段流程→Red Flags 表→借口表→速查表 | 一句话正文/模板（XML 标签）→技能互调组合（ask-matt 路由） | 手册式：设计原则 Part1 + API 手册 Part2，或诊断五部曲 |
| 描述写法 | "Use when + 时机/状态" | 一句话定义 + disable-model-invocation（22 个） | 超长枚举触发场景（zcode-guide 系每个描述 100+ 词） |
| 语气 | 对"你"的全大写命令（MUST/STOP），称用户 "your human partner" | 简洁祈使 + 名词化概念（seam/fog of war/frontier） | 中性文档腔，零元指令 |
| 附属文件 | 同目录散置 .md + scripts/ | agents/openai.yaml 元数据（36 个）+ tracker/格式 .md | 两策略并存：单文件巨型化（pptx）或 references/scripts/templates 文件树（pdf/docx/xlsx） |
| 复用方式 | 技能链 REQUIRED SUB-SKILL 串行 | 原语+壳（grill-me→grilling）+ 斜杠流水线 | 插件内互相独立，agents/judge.md 子代理 |

用户自写中文 skill（7 个）吸收了三方：superpowers 的铁律/自检清单、Pocock 的技能互调与 disable-model-invocation、官方的关键词枚举触发，另加本机绝对路径与"真实教训"叙事，是四路里工程化程度最高的一支（但也是上下文成本最高的一支）。

### 4.4 其他值得注意的发现

- **触发竞争**：test-driven-development vs tdd、systematic-debugging vs diagnosing-bugs、handoff vs claude-handoff、grill-me vs grill-with-docs vs loop-me——同名场景多份并存，命中哪个取决于描述措辞。
- **悬空引用**：teach 引用不存在的 `./assets/`；executing-plans 跨目录相对路径引用 using-superpowers/references/，布局一变即断。
- **命名不一致**：markdown-typography-expert 的 name 是 "Markdown Typography Expert"（大小写+空格），与其余 57 个 kebab-case 不一致，且描述是"用户提到 markdown/md 关键词就执行"的宽触发，易误触发。
- **版本缓存**：目录 B 保留了 browser-use 0.4.0/0.4.1、document-skills 0.1.2、computer-use 0.5.13 旧版本共 9 个冗余 SKILL.md；computer-use 与 zcode-cua 两个插件功能描述几乎重复。
