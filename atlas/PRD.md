# Atlas 产品需求文档（PRD）

| | |
|---|---|
| 产品代号 | Atlas（占位名，公开上线前定稿） |
| 版本 | v0.11（2026-09-23，以代码为基准全文校准：导航/题库/复习现状、技术栈偏差、协作协议与存储实态；此前 v0.10 合入技术可行性调研） |
| 文档地位 | 本项目**唯一文档**。产品定位、目标用户、功能需求、非功能需求、里程碑计划、风险全部在此。修改需在附录 C 变更日志登记。 |
| 读者 | 未来参与开发的你自己、开源后的协作者与贡献者。本文假设读者没有读过任何历史讨论，所有结论自包含。 |
| 证据上游 | [AI 时代程序员调研](./research/ai-era-programmer/AI时代程序员自处与发展-GitHub深度调研.md)、[Linux 桌面 AI 项目调研](./research/linux-desktop-ai-apps/Linux桌面端程序员AI项目深度调研.md)。正文引用标注为「调研-A」「调研-B」。 |

---

## 1. 产品概述

### 1.1 一句话定位

**Atlas 是一款 Linux 桌面应用：为程序员与学生打造的 AI 成长工作站，且本身零模型、零网络。三大支柱：① 学习与复习——任何来源的知识（笔记、文章、新主题、面试题）都能变成安排好复习计划的闪卡，由 FSRS 算法保证六个月后还记得；② 知识库——本地 markdown 目录变成可检索、可溯源的系统；③ 内容管理——各种 skill、文档、资料文件统一纳管，可搜索、可预览、可直达。旗舰场景是面试驱动学习（§1.6）与经验流（§1.7）。全部能力用同一组可度量的指标证明你的能力在真实增长。**

### 1.2 我们要解决什么问题

AI 时代"获取答案"的成本趋近于零（调研-A），程序员真正稀缺的是：**把知识记住、把短板补上、把积累用回来**。现状：学了就忘（无复习机制）；知识散落且不可检索提问（md 库只能翻和搜）；短板补没补没有反馈（错过的题不知道何时重测）；成长不可度量（调研-A 引用的 METR 实验证明自我感觉系统性不可靠）。现有工具各解决一角，没有产品把「知识库 + 间隔复习 + 面试题库」串成闭环——Atlas 做这个闭环。

### 1.3 产品哲学与三条设计铁律

**铁律一：markdown 目录是唯一真相源。** 用户笔记永远是普通 `.md` 文件；属于"知识"的数据（卡片、题库）必须是人可用任何编辑器读写修改的 markdown；属于"缓存/过程"的数据（索引、会话、统计）存 SQLite 且可随时删除重建。调研-B 记录的连续死亡案例（Reor、GPT4All、Trilium、Logseq 2.0）共同教训就是数据绑架。违反即否决。

**铁律二：每个里程碑必须可独立演示。** 连续两周拿不出可演示进展 = 范围失控，正确动作是砍范围不是加班。

**铁律三：功能必须服务北极星指标（§4）。** 反例：规划看板的漂亮图表推不动任何指标，复习闭环跑通前不做。

### 1.4 分工总则：Atlas 零模型，agent 承担全部 LLM 能力（本产品的第一架构决策）

**Atlas v1 不配置、不内置、不调用任何模型**——没有云端 API key、没有本地推理、没有内置嵌入模型。整个应用**零网络**（不仅零遥测，代码层就没有网络能力）。用户日常使用的编码代理（ZCode、Codex、Claude Code 等）已经自带模型与密钥，一切 LLM 能力由它们承担：生成题目候选、写摘要、模拟面试批改与追问。

Atlas 与 agent 之间只有两个协议，全部基于纯 markdown 文件：

1. **同源题目文档协议（首期，当前题库主通道）**：题目和答案直接维护在知识库 Markdown 中；识别 `**Qn: 问题**`（`# Qn:` 标题行与裸 `Qn:` 行亦认，代码围栏内不识别），直到下一个 Q、章节标题或文件结尾的内容均为答案。题库范围由设置「题目源文档」控制（默认仅 `knowledge-base/language/kotlin/01-语法基础.md`，支持文件清单与以 `/` 结尾的目录规则），外部修改每 3s 自动重载。Atlas 编辑（新建/编辑/重排）局部写回原文。旧 `questions.md` 通道代码保留但 UI 已不可达（历史兼容）。
2. **发件箱协议（outbox）与上下文包**：Atlas 把"需要 agent 干的活"写出任务文件（如"模拟面试批改""按笔记出闪卡候选"），或把检索结果一键组装为上下文包（问题 + 命中区块 + 路径锚点），用户复制/交给 agent 消费。

*为什么这样设计*：零成本（不付模型费）、零泄露面（应用层无网络）、模型随时换（换 agent 不换工具）、Atlas 保持小而快。注意澄清两点：FSRS 是统计调度算法不是模型，复习调度完全本地；FTS 全文检索是数据库技术不是模型，检索完全本地。

### 1.5 与编码代理的分工边界

Atlas 刻意**不做任何 agent 能力**（编码代理、任务执行、工具调用循环、MCP server）。分工：ZCode/Codex/Claude Code 是**生产与智能端**（经 skill 写库、消费 outbox 任务、执行面试与批改）；Atlas 是**消化与纪律端**（检索、复习调度、题库状态机、确认工作台、内容纳管）。集成协议 = 纯 markdown 文件 + 固定目录约定（§6.3），没有任何私有 API。

### 1.6 旗舰场景：面试驱动学习与查缺补漏

面试准备的本质循环：**刷题暴露短板 → 针对性补漏 → 复现直到稳定**。现有工具没有谁能闭合它（题库在牛客、笔记在库、复习在 Anki，互不相通）。Atlas 的闭合方式：

```
建题库(直接维护问题式 Markdown) → 模拟面试(agent逐题提问,批改结果写回同一源文档)
  → Atlas读取批改:"不通过/勉强"的题保持"未测"(补漏后可重测)
  → 按"参考要点"补漏 → 重测 → "通过"转"待复测"
  → 复测通过 → "已稳定"
  ↘ 关键结论制卡进"面试题"卡组 → 每天 FSRS 复习防遗忘
```

> **现状（2026-09-26 校准）**：上图为目标规格。当前版本题库=同源 Markdown 直读 + **题面自带完成状态标记**（`todo/learning/done`，见 §6.2 题目（Question）），但**仍无批改驱动的状态机**：批改登记与「发起模拟面试」UI 入口已移除（见变更日志 09-21），闪卡复习入口暂时收起；outbox 面试任务生成代码保留、无 UI 入口。恢复面试闭环时按本节与 FR-D5 保留规格实施。

**前沿知识跟进**：v1 不做联网抓取。获取端交给外部工具（RSS 阅读器、浏览器、编码代理总结），你把前沿内容粘贴给 agent 出卡（经 inbox）或由工具写入库，Atlas 保证"看过不等于忘过"。是否内置抓取，P2 评估。

### 1.7 经验流：工作复盘与项目经验

把"日常工作"变成"学习素材"的输入端，两路汇入同一循环：

1. **工作复盘自动回流**：复盘由外部工具生产（如 issue-review 类 skill 写 md 入库）。Atlas **自动扫描识别**复盘与项目经验条目，提取可复习要点**自动提议、确认后**进入卡组。边界：发现与提议自动，写入过确认门。
2. **项目实际经验成为面试弹药**：项目经验条目（架构决策、难点、权衡、个人贡献）可被 agent 扮演面试官深挖追问（FR-D6），答不好留批改记录、薄弱点可复现追问——项目经验进入"学习-复现-稳定"循环而非躺在文件夹里。

两路共同点：**输入可全自动（外部工具写库），落库必须过确认门**。

---

## 2. 目标用户

| 层级 | 人群 | 典型场景 | 对产品的含义 |
|---|---|---|---|
| 种子（P0） | 产品作者本人：资深 Android/车机开发，ZCode/Codex 重度用户，已维护纯 markdown 知识库（Summary，约 1300 篇 md） | 工作×学习双循环、复盘驱动 | dogfooding 硬纪律：M0 起以 Summary 为真实库验收 |
| 核心（P1） | 1–8 年中国程序员，Linux 桌面，编码代理重度用户，"学了就忘、知识散落" | 补强领域、沉淀文章、面试梳理 | 功能按此类人泛化，不绑定作者个人结构 |
| 延伸（P2） | CS 学生与新晋开发者 | 课程→闪卡；无工作复盘源 | 题库来源维度覆盖课程/面试（FR-D5） |

P2 依据：调研-A——就业冲击集中在入口，学生最需要"用 AI 补齐产出差距 + 尽早建立验证能力"；学习闭环是他们的主场景，与 MVP 天然重合。

## 3. 差异化

| 竞品 | 它做到的 | 它没做到的（Atlas 的位置） |
|---|---|---|
| Obsidian + AI 插件 | 最强 md 生态 | 统一复习闭环（FSRS 队列、题库状态机、能力度量） |
| Cherry Studio / AnythingLLM | 桌面 RAG 问答 | 问完就散，无沉淀回路；且需要用户配模型 |
| Anki + anki-llm | FSRS 金标准 | 与知识库/工作流断裂，无上下文 |

一句话：**Atlas 不做更好的聊天窗口，做"可度量的能力增量回路"；而且是唯一零模型、零网络、零配置成本的本地方案**——模型能力全部复用用户已有的编码代理。差异化校验（调研-T/C §4）：「本地 md 库为唯一事实源 + FSRS + 零模型」的组合经搜索确认仍是空位；最近先例 obsidian-spaced-repetition（2557★）无题库状态机、且复习时**不能跳回源笔记**——Atlas 的来源锚点跳转是直接超越点。

## 4. 产品目标与成功标准

### 4.1 北极星：能力增量仪表盘

1. **复习完成率** = 按期完成复习卡 ÷ 到期卡，目标 ≥80%——学过的真的被巩固；
2. **条目复用率** = 条目被检索引用次数（自动）+ 季度"六个月复用测试"通过率（人工抽样）——沉淀真的被用回来。

单一指标皆可刷，两指标互为制衡。任一指标连续两季为零 = 核心承诺失败，先修产品再加功能。数据全部来自本地事件日志（FR-F2），用户只看自己的数据。

### 4.2 v1.0 成功定义

① 作者 dogfooding 连续 8 周三指标有连续数据；② ≥5 名外部用户走通「安装 → 建库 → 首次检索/建卡 → 首次复习」。

## 5. 范围边界

### 5.1 v1.0 包含（P0）

库接入与索引（md 全族 + 技能识别 + 非 md 编目，三档隐私边界）→ FTS 检索（Ctrl+K）与来源预览浮层 → 上下文包/收发件箱（agent 协作）→ 候选确认工作台 → FSRS 间隔复习（入口暂收起）→ 题库（同源 Markdown 直读）与模拟面试任务（当前入口收起）→ 复盘/项目经验识别与提议 → 本地能力仪表盘（已实现未挂载）→ deb 打包（AppImage P2）。

### 5.2 v1.0 明确不做

| 不做的事 | 原因 / 归期 |
|---|---|
| **任何模型配置与调用（云端 API、本地推理、内置嵌入）** | **v1 核心裁决（§1.4）**：LLM 能力全部由外部 agent 承担；向量语义检索随之 P2（引入内置嵌入时启用） |
| 应用内 RAG 生成式回答 | 无模型则无生成；检索+上下文包替代，生成在 agent 侧 |
| agent 能力（内置代理/MCP server） | §1.5，明确不做 |
| 网络能力（任何形式的联网，含抓取 RSS/网页） | 零网络是隐私承诺的代码级保证；抓取 P2 评估 |
| 文件的移动/重命名/删除 | 只读纳管，整理交给用户与其工具 |
| 非 md 文件的正文 RAG（PDF 解析） | P2；v1 只做元数据编目与预览 |
| 笔记编辑器、Anki 导出、自动更新、系统级全局快捷键 | 编辑用用户自己的工具；其余 P1（浅色/深色主题已提前落地，见 FR-F1） |
| 多知识库管理 | P2 |
| 规划看板可视化图表 | 铁律三：复习闭环跑通前是装饰；P2 |

## 6. 整体设计

### 6.1 信息架构：固定三栏主窗口 + 四标签导航（v4，题库独立、工作台精简）

```
┌──────────────────────────────────────────────────────────────────┐
│ Atlas   [工作台●] [题库] [工具]           Ctrl+K 搜索   [⚙]     │
├──────────────────────────────────────────────────────────────────┤
│ 工作台（首页）：                                                  │
│  行动项：      题目 · 待确认候选（inbox>0 时内嵌候选列表）          │
├──────────────────────────────────────────────────────────────────┤
│ 题库 = 同源题目+答案（文件即题库）                                 │
│ 工具 = 本地小工具图形化集成（拖入文件即运行，全部本地执行）         │
│ 学习 = 闪卡复习（入口暂时收起，路由与 FSRS 代码保留）               │
│ 设置 = 复习参数│外观│题库交互│知识库目录│题目源文档│隐私边界│关于   │
├──────────────────────────────────────────────────────────────────┤
│ 按需浮层：Ctrl+K 搜索（即搜·回车打开·复制为上下文包）         │
│           预览浮层（编辑器打开/让 AI 出题/复盘条目就地提议）        │
└──────────────────────────────────────────────────────────────────┘
```

- 顶部导航：**工作台**（打开即处理当前任务；徽标=待确认候选数）、**题库**（题目与答案的唯一管理入口；徽标=同源题目数）、**工具**（本地小工具图形化集成，无徽标）三个标签；设置以顶栏右侧齿轮进入。**学习（闪卡复习）入口暂时收起**：导航模型仅返回工作台/题库/工具，学习路由与 FSRS 代码保留，恢复入口时徽标=到期卡数。
- **题库条目交互**：列表默认只显示题面；点击展开答案与「编辑」（编辑弹窗含「保存并上/下一题」）；另有「调整顺序」拖拽重排（保存后自动重排题号）与「更多」菜单新建（单个可指定题号插入、批量一行一题）；搜索支持当前文件/整个目录树。不提供批改登记弹窗；删除/转闪卡属遗留 questions.md 通道（当前 UI 不可达）。
- **「知识库」不再是导航标签**（v3 裁决）：对 agent 重度用户，应用内检索/上下文包相对"直接问 agent"没有增量，独立工作台是日常噪音。检索收敛到 **Ctrl+K 浮层**（即搜、↑↓、回车打开预览、底部「复制为上下文包」）；预览收敛为**按需浮层**（复习卡「跳回原文」、Ctrl+K 命中打开时出现，带「编辑器打开 / 让 AI 出题」，复盘/项目经验条目就地「让 AI 通读并提议题目」）。索引、三档隐私边界、复盘识别等底座能力不变。
- 「工作台」页承担原「收件箱」职责（FR-E1 确认工作台内嵌其中），只呈现有待处理数量的行动项，不再显示可展开的统计看板；题目从学习页移出，统一进入独立「题库」页；outbox 任务由外部 agent 直接消费，不在工作台展示任务列表或提供路径复制动作。
- 文案纪律：内部机制词（outbox、批写回、trigram、北极星、枚举名）不出现在 UI；每个视图说明文字 ≤1 行。

### 6.2 核心概念

- **库（Library）**：用户选定的一个目录，v1 单库。
- **内容条目（Item）**：库中被纳管的文件，三类：**笔记**（md）、**技能**（含 SKILL.md 的目录；frontmatter name/description 提取未实现，内容按 md 索引）、**文件**（非 md，仅元数据编目，当前无检索/预览入口）。
- **区块（Chunk）**：索引单位。md 按 `##` 小节切块，**`##` 小节是一等知识条目锚点**（对齐本库 knowledge-base 的专题文档模型：一个 `##` 小节 = 一个条目）；区块=小节原文（小节标题并入正文保证可检索），记录 文件路径+小节名；无跨小节前后文窗口。引用锚点格式 `文件.md##小节名`。
- **卡片（Card）**：问答式闪卡，属一个**卡组（Deck）**（学习领域维度，随建随建，可全库混合复习也可限定卡组），由 FSRS（开源间隔重复调度算法，非模型）调度。
- **题目（Question）**：同源 Markdown 中的开放长答面试题（`**Qn:**` 行为题面，Q 块其余内容为答案）。**完成状态编码在 Q 行题面的方括号前缀里**（`**Q1: [done] 问题？**`），三档 `todo`（默认，缺省不写标记）/ `learning` / `done`，随源文档走 git，agent 可直接改；只认已知状态键，题面里 `[1]`、`[注意]` 这类方括号不会被误剥。遗留 `questions.md` 条目带 `未测→待复测→已稳定` 状态机（代码保留，UI 不可达），与同源状态**互不相通**。
- **收件箱/发件箱（Inbox/Outbox）**：与外部 agent 的文件协议。inbox = agent 写入的待确认候选（确认 UI 仅 question 类；旧 card 候选按 front→q、back→answer 兼容转换）；outbox = Atlas 写出的待办任务文件（供 agent 消费）。
- **上下文包（Context Pack）**：检索结果的一键组装物（问题+命中区块全文+路径锚点），供粘贴给 agent 生成回答。
- **确认门**：AI/agent 产出进入用户知识资产前的逐项人工确认。自动化的是发现与提议，写入永远人工。
- **主题定义（Theme Definition）**：一套可选择的完整视觉主题，由名称、浅色模式色板和深色模式色板组成；两套模式色板独立保存，不在运行时互相派生。
- **模式色板（Mode Palette）**：主题在一种明暗模式下的完整语义色集合，覆盖界面表面、正文、交互、状态和 Markdown 阅读语义。
- **语义色（Semantic Color）**：按用途而不是具体色值命名的颜色，例如“强调”“错误”“二级表面”“Markdown 二级标题”。业务界面只消费语义色，不直接依赖 Macchiato 色号。
- **表面角色（Surface Role）**：按视觉层级命名的中性色面，包括页面底、内容面、悬浮/输入面和代码块面；表面角色负责空间层级，不承担成功/警告/错误语义。
- **交互状态（Interaction State）**：组件在默认、悬停、选中、按下、聚焦、禁用等状态下的视觉表达；选中和错误不得只依赖颜色，还要配合指示线、轮廓或文案。
- **编辑器暖灰（Editorial Warm Neutral）**：Atlas 本轮确定的视觉人格：以 Macchiato 中性表面为大面积底色，使用 Peach/Teal/Mauve 分别承载动作、链接和标题，避免蓝色包办所有界面角色。

### 6.3 数据与存储设计

| 数据 | 存放 | 格式 | 说明 |
|---|---|---|---|
| 用户知识库 | 用户选定目录 | md 为主 + 各类纳管文件 | 用户资产，应用**只读** |
| 卡片 | `<库根>/atlas/cards.md` | md 小节：卡面/卡背/deck/来源/FSRS 状态行 | 人可手改，文件为准 |
| 题库 | 知识库中的问题式 Markdown | `**Qn: 问题**`（`# Qn:`/裸 `Qn:` 亦认）+ 后续 Markdown 答案；范围由设置「题目源文档」控制（默认仅 `knowledge-base/language/kotlin/01-语法基础.md`，支持目录规则） | 人或 Atlas 直接编辑，文件为准 |
| 收件箱候选 | `<库根>/atlas/inbox/*.md` | 约定候选格式（kind: question、q、answer） | agent 写入，Atlas 确认后转入 questions.md 并删除候选 |
| 发件箱任务 | `<库根>/atlas/outbox/*.md` | 约定任务格式（type: interview / cardgen） | Atlas 写出，agent 消费后可标记完成 |
| 索引缓存、检索缓存、事件日志 | 应用数据目录 | SQLite（FTS5） | 可随时重建 |
| 配置 | 应用数据目录（`~/.local/share/atlas/settings.properties`） | 无密钥（零模型） | — |

原则：**知识落 md 人可改；缓存落 SQLite 可重建；与 agent 的一切交互走文件协议（同源知识文档 + atlas/inbox+outbox）。**

### 6.4 视觉主题与 Markdown 阅读系统（目标设计，待实现）

Atlas 只保留一套内置主题，界面名称为 **Atlas**。其唯一色彩来源是 Catppuccin Macchiato；不再保留 Latte、Frappe、Mocha、Nord、Ayu、One Dark、Gruvbox 等并列内置预设。Atlas 不是第三方主题画廊，而是具有稳定视觉身份的知识工作台。

#### 6.4.1 主题结构

- 每个主题定义同时包含独立调校的浅色、深色两套模式色板；禁止运行时用 `lighten()` / `darken()` 从一套原色机械生成另一套。
- 内置 Atlas 只读，是始终可恢复的视觉基线。用户可“基于 Atlas 新建”自定义主题，但不能覆盖内置主题。
- 自定义主题同样保存独立的浅色、深色色板；编辑器以“浅色 / 深色”切换当前模式，再编辑“界面配色 / Markdown 配色”。
- 每套模式色板固定为 20 个字段：12 个界面语义色（强调、成功、警告、错误、次要文字、代码块底、页面底、内容面、悬浮/输入面、正文、交互轮廓、装饰分隔）和 8 个 Markdown 语义色（H1、H2、H3、粗体、链接、引用、行内代码字、行内代码底）。代码块正文复用正文色。
- 业务组件继续只读取语义 token；主题模型变化不得迫使工作台、题库、设置、弹窗等页面直接引用具体色号。
- 表面采用低对比三级结构：页面底 → 内容面 → 悬浮或输入面。深色模式逐级变亮，浅色模式以白色内容面落在轻微着色的页面底上；层次同时使用克制边框，不依赖大面积彩色填充。
- 状态色仅用于状态点、图标、文字和低透明背景，并始终配合文案或图标；颜色不是唯一的信息载体。

#### 6.4.2 Atlas 基准色板

深色模式直接使用 Macchiato 色阶；浅色模式只允许由同一色源混白或降低明度后人工校准，不引入 Catppuccin Latte 或其他主题。

| 语义 | 深色 | 浅色 | 用途 |
|---|---:|---:|---|
| 页面底 | `#181926` | `#F7F2EE` | 应用主背景 |
| 内容面 | `#1E2030` | `#FFFCFA` | 卡片、对话框、主要容器 |
| 悬浮/输入面 | `#24273A` | `#F0E8E6` | 输入框、悬浮态、次级容器 |
| 正文 | `#CAD3F5` | `#3B3440` | 主文本 |
| 次要文字 | `#A5ADCB` | `#6D6676` | 说明、占位、弱信息 |
| 交互轮廓 | `#6E738D` | `#877E8D` | 焦点、控件边界等承载信息的轮廓 |
| 装饰分隔 | `#494D64` | `#D8CFD6` | 不承担状态含义的弱分隔 |
| 主强调薰衣草 | `#B7BDF8` | `#5969A8` | 主操作、焦点、导航指示；大面积选中不填充 |
| 成功绿 | `#A6DA95` | `#3C7848` | 成功、完成 |
| 警告黄 | `#EED49F` | `#8C6C2C` | 警告、未提交改动 |
| 错误红 | `#ED8796` | `#A13D52` | 错误、删除 |

#### 6.4.3 Markdown 阅读配色

Markdown 只保留一套“阅读优化”样式，删除“经典样式”及对应设置。颜色负责辅助结构，不替代字号、字重和留白；本轮不增加语法高亮。

| Markdown 语义 | 深色 | 浅色 | 表现 |
|---|---:|---:|---|
| 一级标题 | `#C6A0F6` | `#704B8F` | 淡紫；文章级锚点 |
| 二级标题 | `#8AADF4` | `#4969B2` | 蓝色；主要章节导航 |
| 三级标题 | `#CAD3F5` | `#303344` | 回归正文色，以字号和字重分层 |
| 粗体 | `#CAD3F5` | `#303344` | 只增强字重，不伪装成链接或状态 |
| 链接 | `#8BD5CA` | `#2F716E` | 青绿；唯一稳定的链接语义 |
| 引用 | `#B8C0E0` | `#6D6676` | 灰紫，并配左侧结构线 |
| 行内代码字 | `#F5A97F` | `#9A4E2D` | 桃色 |
| 行内代码底 | `#363A4F` | `#F2E8E2` | 低对比底色，不抢正文层级 |
| 代码块正文 | `#CAD3F5` | `#303344` | 等宽正文，无语法高亮 |
| 代码块底 | `#181926` | `#F0E9E7` | 与正文容器形成一层清晰区隔 |

表格表头、列表标记、引用结构线、代码语言标签和 Mermaid 节点必须从上述语义继续派生，不另建无约束色值。标题、粗体和链接不得再次回退为同一个主动作色。

#### 6.4.4 设置交互与兼容迁移

- 配色二级页取消 11 套内置主题画廊，改为 Atlas 浅色/深色双预览，下方保留“我的主题”管理。
- 旧设置若选择了已删除的内置主题，读取时自动切换到 Atlas，不保留隐藏兼容主题。
- 旧自定义主题首次读取时，用旧版派生规则各生成一次浅色和深色模式色板，随即写成 `v2|名称|l1|…|l20|d1|…|d20`；此后两套色板独立编辑，不再动态派生。
- 旧的 `markdownStyle=classic` 读取后统一迁移为阅读优化样式；删除设置入口后，不再写出该选择。
- 成功迁移时保留自定义主题名称与选择状态；单条旧记录非法时跳过该记录，若被选中的记录无法迁移则回退到内置 Atlas。任何迁移失败都不得阻止应用启动。

#### 6.4.5 质量闸门

- 普通正文、次要文字、链接、引用和代码文字在页面底与内容面上的对比度均须达到 4.5:1。
- 大号标题、图标、交互轮廓和非文本交互状态须达到 3:1；纯装饰分隔线不承担信息含义，不受此下限约束。黑白对比度函数自校验必须为 21:1。
- 自动测试覆盖：两套内置模式色板、所有语义色对比度、旧内置主题回退、旧自定义主题迁移、新格式往返、同名自定义主题优先级和非法色值保护。
- 视觉验收至少覆盖：工作台、题库、设置、主题编辑器、Markdown 预览和通用弹窗，并分别检查浅色、深色模式。

#### 6.4.6 UI 颜色深化裁决（2026-09-27）

截图复核确认：上一轮虽然替换了部分主题值，但页面仍呈现为一整块冷蓝紫灰，原因是背景、内容面、选中态、边框和正文没有按角色拉开，且多个页面继续直接消费 Material 默认表面色。本轮裁决如下：

- **色源不变**：继续只使用 Catppuccin Macchiato，不引入新的品牌色或第二套主题来源。
- **视觉人格**：采用“编辑器暖灰”，深色模式使用清晰但不过度的三级中性表面层级；浅色模式同步转为暖纸张，而不是冷白反转。
- **语义分工**：Lavender/Periwinkle 用于主动作、焦点与导航指示，Peach 只用于 Markdown 行内代码与警告，Teal 用于链接与 Markdown 链接，Mauve 用于文章级标题，Green/Yellow/Red 只用于状态。
- **Markdown 原则**：结构优先，标题主要依靠字号、字重和留白，颜色只做轻提示；引用、代码、链接保留明确但克制的语义色。
- **交互表达**：选中使用中性内容面 + 细色边/指示线，悬停使用轻微表面变化，焦点使用 2px 轮廓，禁用降低对比度；任何状态不得只依赖颜色。
- **面积控制**：大面积只使用中性色；彩色限于动作、状态、指示线和 Markdown 语义，悬停/选中表面透明度约 3–7%，按压约 13%。
- **覆盖范围**：本轮覆盖窗口壳、导航、输入框、按钮、题库卡片、目录树、弹窗、工具页、设置页和 Markdown 渲染；不改变业务交互、文件协议或主题 V2 持久化结构。

## 7. 功能需求（P0）

### A. 库、索引与内容管理

**FR-A1 建库向导**
- 动机：激活漏斗第一步，零文档可用。
- 描述：首次启动单屏建库：选 md 目录（文件选择器）→「打开这个库」后台执行首次索引（进度在设置页可见）。无任何模型配置步骤。三档隐私边界在建库后于设置页「隐私边界」管理（仅本地/额外忽略逐行编辑，保存触发全量重建）。
- 三档边界（v1 核心设计，来自库审计的安全发现）：`完全忽略`（默认忽略 .git/.idea/.gradle/.vscode/node_modules/__pycache__/build/dist/out/.venv/venv、.qoder/.sisyphus/.trae 等 agent 工具目录与 `atlas/` 协作目录；>1MB 的 md 只编目不索引）/ `仅本地编目`（标记目录：只进条目编目、不进全文索引，**结构性不可能出现在上下文包中**——用于工作敏感仓库）/ `全索引`（默认档）。"疑似工作仓库建议仅本地"提示为规划项，未实现。
- 验收：新用户选目录一步完成建库；"仅本地"目录内容不出现在上下文包中；索引幂等可全量重建。

**FR-A2 索引（全量+增量）**
- 动机：检索与一切联动的基础。
- 描述：按标题层级切块，`##` 小节为一等锚点（区块=小节原文，小节标题并入正文）；SQLite FTS5 全文索引（中文场景用 trigram 分词，spike 裁决）；运行期每 3s 轮询 mtime 自动重载知识文件（同源题目、闪卡、inbox 候选，第三方改动 ≤5s 在题库/工作台可见）。**FTS 索引更新当前为手动/事件触发**（建库、设置页「重新扫描」、修改隐私边界时全量重建）——"保存文件 ≤5s 自动可检索"为规划目标，增量索引未接线。**第三方进程（编码代理/skill/编辑器）写入 md 是受支持的一等内容**——这是与 ZCode/Codex 的官方接口。
- 细节与边界：题库页重命名文档会同步映射题目源文档路径；索引层重命名/移动按"旧路径删除+新路径重建"处理（无元数据映射）；空目录不产生条目；索引幂等可重建。
- 验收：重新扫描后新增/修改/删除文件的检索结果正确；重建后结果一致；外部改 md ≤5s 同源题目与候选自动重载。

**FR-A3 检索（FTS）与质量基线**
- 动机：零模型下检索质量是产品生命线；必须被测量。
- 描述：SQLite FTS5 **trigram tokenizer** 的全文检索（方案经调研-T/B 裁决：unicode61 对中文不可用、JDBC 注册自定义 tokenizer 不可行、预分词方案有 License 与高亮映射坑），供 Ctrl+K 与上下文包共用。评测集（建库时自动抽 ≥30 条"问题→应命中区块"、分关键词型/语义型标注）**未实现，为规划项**。
- 细节与边界：① trigram 官方限制——**<3 字符查询 MATCH 不命中**（如"内存"），这类查询自动走原文表 LIKE 兜底（1 万文档实测 ~20ms，可接受）；② 必须使用新版内置 SQLite（xerial sqlite-jdbc ≥3.53，旧版 3.37 对 trigram 表 2 字 LIKE 有缺陷）；③ detail 保持 full；④ 高亮用 snippet()/手工标记（【】）实现，当前以纯文本渲染（AnnotatedString 映射未做）；⑤ v1 无向量语义检索（P2），语义型查询命中率为已知短板，如实记录基线。
- 验收：**关键词型 top-3 命中率 ≥80% 方放行 M1（评测集未实现，暂以人工抽查代替）**；双字词查询走 LIKE 兜底且结果正确；语义型基线报告存档；P2 引入向量后重测至 ≥80%。
- 性能事实（调研-T/B 实测参考）：1 万文档建索引 ~1.2s，单查询 0.7–1.4ms。

**FR-A4 笔记预览（浮层）**：markdown 只读渲染——内置渲染器支持 GFM 表格、代码块、行内粗体/行内代码/链接（**无语法高亮、无 `文件##小节` 内链跳转**，均为规划项）；以按需浮层呈现（复习卡来源锚点「跳回原文」、Ctrl+K 命中打开时出现），旁有"编辑器打开"与"让 AI 出题"。验收：万文件库不卡；渲染正确。

**FR-A5 多类型内容纳管**：① md 全族进全文索引；② 非 md 文件仅元数据编目（文件名/路径/大小/mtime），**当前不进全文索引、无检索与预览入口**（按文件名可搜为规划项）；③ 预览统一按 UTF-8 文本渲染（无图片/PDF 内建预览，二进制大文件整读为已知风险点）；「编辑器打开」调用系统默认程序。验收：非 md 文件入库被编目不崩溃；正文搜不到属预期（P2）。

**FR-A6 技能识别**：含 SKILL.md 的目录识别为"技能"类型（frontmatter name/description 提取未实现，技能内容按 md 全文索引）。验收：拷入标准 skill 目录正确识别；SKILL.md 更新后重新扫描可见。

**FR-A8 复盘与项目经验识别（自动提议）**
- 动机：复盘与项目经验是复习卡的最高价值原料；识别让它们活起来，同时严守确认门——**自动化的是发现与提议，不是写入**。
- 描述：① 识别：frontmatter `type: retrospective / project-experience`，缺失时按目录名兜底（`issue/`、`retros/`、`retrospective/`、`project-experience[/s]`，可配置 retroDirs）；② 提议：预览浮层对识别条目就地提供「让 AI 通读并提议题目」→ 写 outbox cardgen 任务 → agent 产出候选进 inbox → 确认后落库（确认门）；③ "标记为复盘/项目经验"写 frontmatter 动作未实现。
- 验收：含复盘 frontmatter 或位于 issue/ 的文档在预览浮层显示 chip 与提议入口；提议任务可被 agent 消费并经 inbox 确认；全程无未确认写入。

### B. 检索与 agent 协作

**FR-B1 全局搜索（Ctrl+K 浮层，v3 合并原"检索工作台"）**
- 动机：零模型下"问答"的正确形态 = 检索 + 上下文外包，而不是应用内生成；检索入口按需弹出而非独立页面（对 agent 重度用户，应用内检索的价值是"就地转出卡"，不是替代问 agent）。
- 描述：Ctrl+K 居中浮层：输入即搜 → 命中区块列表（文件+小节+高亮片段）→ 回车/点击打开预览浮层、底部「复制为上下文包」（FR-B2）。
- 细节与边界：Atlas 不生成任何自然语言回答（零模型）；检索无命中时明确提示（实际文案"库中没有找到相关内容"）。
- 验收：有命中时可打开预览；无命中时明确提示；上下文包动作可用。

**FR-B2 上下文包**
- 动机：把"本地检索"与"agent 智能"焊接起来的最小协议。
- 描述：一键把当前问题 + 命中区块全文 + `文件##小节` 锚点组装为结构化 markdown（或写为 outbox 任务文件），用户粘贴/交给任意 agent；agent 的回答若写回库内约定位置则被纳管。
- 验收：上下文包含问题原文、区块全文、路径锚点三要素；从复制到 agent 产出回答到回库，全程无 Atlas 网络行为。

**FR-B3 Ctrl+K 全局搜索**：与 FR-B1 同一浮层。输入即搜（FTS），键盘直达（↑↓ 选择、回车打开预览浮层）。验收：万文件首屏 ≤200ms；全程键盘可达。

### C. 学习与复习

**FR-C1 题目与复习卡（统一确认门）**
- 动机：题目是面试复习闭环的入口；用户的知识来自四面八方。
- 描述：
  - **同源通道（主）**：agent（session-to-knowledge 出题等）把题目以 `**Qn:**` 直接写入知识库文档，Atlas 同源直读并 ≤5s 自动重载——文档即题库，无确认门。
  - **inbox 通道（备用）**：agent 产出题目候选写入 `atlas/inbox/`（每块只含 `kind: question`、`q`、`answer`；旧 front/back 闪卡候选按 front→q、back→answer 兼容转换）→ 工作台逐条确认/编辑/整批忽略 → 落入 questions.md，status=未测。
  - **手动通道**：题库页「更多」菜单新建单个题目（可指定题号插入）/批量新建（一行一题），直接写同源文档。
- 细节与边界：候选不入正式文件前不算题；Atlas 自身不调用任何生成（零模型）。
- 验收：inbox 写 8 张候选 → 确认 5、编辑 1、忽略 2 → questions.md 恰好 6 题且来源正确；同源文档与手动新建的题即时可见；断网全流程可用。

**FR-C2 卡库存储**：`<库根>/atlas/cards.md` 单文件，每卡一小节（卡面/卡背/deck/来源/FSRS 状态行）；人可手改、文件为准、原子替换防冲突。验收：外部改题面/due 即生效；删除小节=删卡；文件误删重建并显著警告。

**FR-C3 复习队列（入口暂时收起）**：学习（闪卡复习）路由与 FSRS 全链路代码保留，顶部导航入口当前隐藏（恢复时：工作台显示到期+新卡；默认全库混合，可限定卡组；到期用应用内徽标）。当前可达：FSRS 参数经设置页查看/切换/重置（desired retention 默认 0.9，预设 85/90/95/97%）；卡片浏览支持搜索/编辑/暂停/删除。恢复后的评分规格：正面→翻面→四键评分即时调度，**键位采用 Anki 惯例**（Space 显示答案，1–4 评分——评分键 UI 接线当前已移除、评分/撤销代码保留）；"重来"当日重现。调度实现采用官方 **java-fsrs**（`io.github.open-spaced-repetition:fsrs` 1.0.0，MIT，经适配层隔离）；**fuzz 未显式关闭（库默认开启），"关 fuzz 即确定"未达成**。借鉴 Obsidian-SR 的"调度状态写回笔记文件"模式且超越之：**复习时来源锚点可点击跳回原文**（card.source 形如 `文件##小节` 时显示；该插件做不到，调研-T/C 已读源码证实）。验收：评分即写回 cards.md `- fsrs:` 行；AGAIN 当日重现；完成态明确。

**FR-C4 学习统计**：review/review_due 评分与到期事件入本地 SQLite 事件日志；已实现 30 天口径的复习完成率（分母=按卡去重的到期卡）与近 30 天每日复习量；**仪表盘区块已实现但尚未挂载到任何页面**；日/周/月多口径为规划项。验收（挂载后）：指标可见；清日志显示为空而非报错。

### D. 面试与经验流

**FR-D5 题库与模拟面试（面试驱动学习，规则对齐既有周练实践）**
- 动机：面试准备 = 刷题暴露短板 → 补漏 → 复现到稳定（§1.6）。用户在 `career/weekly/` 已有一套手写规则（闭卷作答、批改追加不改原答案、未答题延迟揭晓），本 FR 将其产品化。
- 现状（2026-09-23 校准）：
  - **题库=同源 Markdown 直读（文件即题库）**：左侧递归映射知识库文档树，右侧题目卡片点击展开看答案；编辑弹窗维护题面与答案（含「保存并上/下一题」）；「调整顺序」拖拽重排自动重编号；新建支持单个指定题号插入与批量一行一题；搜索覆盖当前文件/整个目录树。题目范围由设置「题目源文档」控制（默认仅 `knowledge-base/language/kotlin/01-语法基础.md`，支持文件清单与目录规则）。**同源题目带轻量完成状态**（`todo/learning/done`，写在 Q 行的 `[key]` 前缀里，缺省 todo），卡片上以「圆点 + 文案」（`todo/learning/done`）显示状态、**默认态不渲染**（一屏几十题里绝大多数是未完成，渲染出来全是噪音），编辑弹窗可切换并写回源文档（09-26）；但**仍无批改驱动的状态机**：批改登记弹窗与「发起模拟面试」入口已移除（09-21）；`未测→待复测→已稳定` 状态机与"参考要点"属遗留 questions.md 条目（代码保留、UI 不可达）。
  - **模拟面试（无 UI 入口，代码保留）**：写 outbox interview 任务的代码在，但当前无任何界面触发。
- 保留规格（恢复面试闭环时启用）：选题清单 → 写 outbox interview 任务 → agent 逐题提问（可追问 1–2 层）、按参考要点批改写回（追加式）→ Atlas 读批改驱动状态机："不通过/勉强"保持"未测"，"通过"转"待复测"，复测通过转"已稳定"；未答题延迟揭晓、只记录处理计划；批改默认 通过/勉强/不通过，可切"只点评不打分"（该设置项未实现）。
- 验收（现状部分）：含 40 题的同源文档即开即读、可编辑/重排/搜索；外部修改 ≤5s 自动重载；题目局部写回不破坏文档其余内容。

**FR-D6 项目经验模拟追问（未实现，规划项）**：目标规格=选 type=project-experience 条目 →「发起项目追问」outbox 任务 → agent 扮演面试官就架构决策/难点/贡献深挖（≥5 问）→ 作答与点评写回条目附件，薄弱点在点评中标注、可再次发起追问。当前前置已具备：项目经验识别（FR-A8）与「让 AI 通读并提议题目」cardgen 任务；追问专用任务类型与 UI 入口未实现。

### E. 收发件箱与上下文（agent 协作组）

**FR-E1 确认工作台（工作台待确认区）**：「工作台」页内嵌题目候选区，聚合 inbox 候选（kind: question），逐条保留/编辑/丢弃/整批忽略；旧 card 候选按 front→q、back→answer 兼容转换。处理后候选文件归档移除。验收：徽标计数准确；处理后的候选不再出现；未确认候选不进正式文件。

**FR-E2 上下文包**：见 FR-B2。验收同。

**FR-E3 outbox 任务**：任务文件格式约定（type: **interview / cardgen** + 指针数据；feynman/interpret/project-quiz 已随功能下线删除）；agent 完成后在任务文件标记 done（或直接写结果回正式文件），Atlas 每 3s 检测 done。outbox 无任务列表 UI，由外部 agent 直接消费。验收：两类任务各走通一次。

### F. 设置与本地度量

**FR-F1 设置**：实际分区——复习（FSRS 参数入口）、外观（浅色/深色主题即点即生效并持久化 + 全局字号）、题库交互（点击答案打开编辑弹窗开关）、知识库目录（更换直接重新打开并重索引，**无确认对话框**；另有「重新扫描」）、题目源文档（题目范围多行编辑）、隐私边界（仅本地/额外忽略清单，保存触发全量重建）、屏幕录制（保存目录/默认帧率/x264 码率，即改即存并导出 `~/.local/share/atlas/screen-recorder.json` 供 `tools/screen_recorder` 录屏脚本读取；卡片内一键打开录屏界面）、应用数据（只读展示）。外观目标设计见 §6.4：内置主题收敛为 Atlas，配色二级页展示双模式预览与“我的主题”，Markdown 固定为阅读优化样式、不再提供经典样式选择。忽略规则管理仅「额外忽略目录」一项；atlas/ 协作目录约定无设置项（由建库提示与默认忽略承载）。验收：全默认值零配置可用；含“仅本地”清单管理；旧主题设置可无损或安全降级迁移。

**FR-F2 能力增量仪表盘**：事件日志（review/review_due/citation）本地可重算；指标=复习完成率（30 天、分母为按卡去重到期卡）+ 笔记被引用（citation 计数）+ 当前到期卡 + 近 30 天每日复习量。**仪表盘区块已实现但尚未挂载到任何页面**。验收（挂载后）：指标可见；清日志显示为空而非报错。

**FR-F3 零网络**：应用不包含任何网络代码路径——不联网、不遥测、不检查更新。验收：发布说明明示；代码审计无网络依赖（打包产物无网络权限）。

### G. 打包

**FR-G1 deb 打包（P0、唯一承诺格式）**；**FR-G2 AppImage（降级 P2）**——CMP 的 AppImage 打包存在未修复缺陷 CMP-7101（调研-T/A），不承诺。验收：Ubuntu 22.04/24.04 全新机安装即用；卸载不删用户库与 atlas/ 协作目录；自动更新 P1。

## 8. 关键用户旅程走查（验收基准）

> 现状注记（2026-09-23）：学习/复习与面试入口当前收起，J2 的评分环节与 J5 的批改环节暂不可达；其余步骤可达。

**J1 建库与首次检索**：安装 → 建库页选 Summary 目录 → 打开建库（后台索引）→ Ctrl+K 搜一个词 → 命中区块回车打开预览 → 复制为上下文包丢给 ZCode → 得到基于库内内容的回答。全程无任何模型配置。

**J2 学习与复习**：在 ZCode 读一篇 Kafka 文章让 agent 出卡 → 候选进收件箱 → Atlas 保留 5/编辑 1/丢弃 2，卡组"消息队列" → 数日后徽标到期 → 逐张四键评分 → 完成态 → 完成率更新。考前限定"面试题"卡组突击。

**J4 找回技能与文件**：skills 目录纳入库 → 月底记不清某个 skill → Ctrl+K 搜 → 命中技能及 description → 预览 → 打开目录；PDF 按文件名搜到直接预览。

**J5 面试驱动学习**：粘贴 15 题建库（inbox 确认）→ 发起模拟面试（outbox）→ ZCode 逐题问答批改写回 → 补漏后重测、复测全过转"已稳定"，关键结论制卡进"面试题"卡组。前沿文章粘贴给 agent 出卡进"前沿"卡组。

**J6 经验回流**：周五 ZCode 复盘 skill 写入本周 ANR 排查复盘 → Atlas 预览浮层识别并「让 AI 通读并提议题目」→ 候选进收件箱确认落库。周日晚项目经验追问一轮，薄弱点在点评中标注（追问入口未实现，规划项）。

## 9. 非功能需求

| # | 类别 | 要求 |
|---|---|---|
| NFR-1 | 性能 | 启动 ≤3s（AppCDS 为规划项——当前 jpackage 未配置 CDS 参数；实测验收）；1 万文件首索引 ≤10min（调研-T/B 实测 ~1.2s，预算宽裕）；增量 ≤5s；检索首屏 ≤200ms；稳态内存 ≤500MB |
| NFR-2 | 规模 | 1 万文件 / 500MB 文本不劣化；单文件 >1MB 走忽略/编目规则 |
| NFR-3 | 隐私 | **零网络（代码级）**：无任何网络依赖与调用，遥测/更新检查/模型请求一律不存在；三档边界（FR-A1）保证"仅本地"内容不进上下文包 |
| NFR-4 | 平台与许可 | Linux 桌面唯一验收平台：**X11 + Wayland（经 XWayland）**——CMP 无原生 Wayland 支持（skiko 无 Wayland 代码，调研-T/A），多屏 HiDPI 列为已知风险；打包 **deb 为承诺格式**（AppImage P2）；**AGPL-3.0** |
| NFR-5 | i18n | 简中优先，字符串外置 |
| NFR-6 | 技术栈（调研-T 落定 + MVP 偏差已落地） | Compose Multiplatform **1.12.0**（Desktop JVM，Kotlin 2.2.20，Material3 1.9.0 配套）；markdown 渲染 **内置轻量渲染器**（ADR：替代 mikepenz 库零依赖——GFM 表格/代码块/行内标记，无语法高亮）；存储 **xerial sqlite-jdbc 3.53.4.0 直连**（ADR：替代 SQLDelight；FTS5+trigram）；复习调度 **java-fsrs 1.0.0**（官方 MIT，适配层隔离）；jpackage 打包（**AppCDS 未启用**）；**无任何网络/模型依赖库**（依赖审计通过）。GraalVM native image 官方不支持 CMP，不纳入。spike 仍可推翻选型，推翻记 ADR |
| NFR-7 | 质量 | 检索评测集（规划项，未实现）；FSRS/索引/模型层 TDD；主题普通文本对比度 ≥4.5:1、非文本与大字 ≥3:1；GitHub Actions 出 deb 产物（**未建**）；**IME 冒烟验收**（fcitx5/ibus 中文输入——历史有数月不可用窗口，见调研-T/A） |

## 10. 里程碑计划

| 里程碑 | 范围（FR） | 演示 Gate |
|---|---|---|
| M0 骨架（1 周） | A1、A4、B3、F1 | **以 Summary 真实库（1300+ md）建库**：浏览、检索、三档边界生效；**IME 冒烟**（fcitx5/ibus 中文输入正常，调研-T/A） |
| M1 检索与来源（2 周） | A2、A3、B1、B2、E2 | Ctrl+K 检索+来源预览浮层+上下文包；关键词型 eval ≥80% 放行 |
| M2 学习闭环（2–3 周） | C1–C4、E1 | agent 出卡→收件箱确认→FSRS 复习→统计全通 |
| M3 面试·经验流（2–3 周） | D5–D6、A8、F2 | 一轮模拟面试闭环（批改写回、通过转待复测、复现转稳定）、复盘提议制卡、仪表盘指标 |
| M4 学习源与打包（2 周） | A5、A6、G1、G2 | 技能识别、非 md 编目；**deb 全新机即用（AppImage P2）**；随后建公开仓库内测 |

## 11. 发布与运营（M4 后）

内测 2 周（作者全量 dogfooding + 调研期访谈对象）→ GitHub 公开（AGPL-3.0）+ Releases 发布 deb → 掘金发布《一个 Android 开发，为什么给 Linux 程序员造了个零模型知识工作站》→ 双周迭代、季度竞品重测、北极星季审。

## 12. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| 烂尾 | 高 | 铁律二时间盒；M0–M4 演示 Gate；2 周无演示即砍范围 |
| 纯 FTS 检索对语义型问题命中率低 | 中高 | 已知取舍：eval 分两类如实记录基线；上下文包+agent 弥补语义盲区；P2 引入内置嵌入后达标 |
| Wayland 下偶发渲染崩溃与多屏 HiDPI 问题（CMP-5100/5101/9429 等） | 中 | 验收以 X11 为主、XWayland 冒烟为辅；崩溃 issue 跟踪纳入季度重测 |
| 数据绑架嫌疑 | 中 | 铁律一 + 协作目录人可读 + 卸载零残留 |
| 自嗨功能 | 中 | 铁律三；每功能写明服务的北极星指标 |
| 用户误解"零模型=功能缺失" | 中 | 发布叙事明确分工：模型在 agent 侧，Atlas 是纪律与消化层；上下文包演示"检索→agent"衔接 |

## 附录 A：开放问题（阶段 3 spike 裁决）

1. **IME 冒烟（M0）**：fcitx5/ibus 下 CMP 文本框中文输入实测（调研-T/A 确认历史 bug 已修，仍需实测验收）；
2. **启动调优**：AppCDS 启用后实测启动时间，≤3s 不达标时的 JVM 参数调优清单；
3. inbox/outbox 候选与任务的具体 md 格式细则（M2 前与常用 agent skill 联调定稿）；
4. P2 预研：内置本地嵌入模型引入后的向量检索与语义问答（届时按同一三档隐私边界约束）。

*已由调研-T 解决并合入正文：中文 FTS 方案（trigram+LIKE 兜底，FR-A3）；FSRS 实现路线（java-fsrs，NFR-6/FR-C3）；AppImage 打包风险（降 P2，FR-G2）。*

## 附录 B：关键决策记录

- 2026-09-27 主题系统裁决：**唯一内置主题 Atlas + 独立浅深双模式色板 + Macchiato 唯一色源**。删除其余 10 套内置预设和运行时明暗派生；保留可迁移的自定义主题。Markdown 只保留阅读优化样式，以独立 H1/H2/H3、链接、引用和代码语义建立层级，不在本轮加入语法高亮。完整目标设计见 §6.4。
- 2026-09-19 第 1 轮拷问：单库+忽略规则；检索+来源面板+Ctrl+K；闪卡确认门+集中 md 卡库；缺口三入口+费曼强制清结；固定三栏；本地度量仪表盘 P0；AGPL-3.0、M4 后公开、名字公开前定。
- 2026-09-19 裁决：**不做 agent**（ZCode/Codex 等承担生产与智能，§1.5）。
- 2026-09-19 裁决：**学习复习为第一支柱**——四种制卡来源、卡组维度（FR-C1）。
- 2026-09-19 裁决：**内容管理为第三支柱**——多类型纳管+技能识别（FR-A5/A6）。
- 2026-09-19 裁决：**旗舰场景面试驱动学习**——题库/模拟面试/复现闭环（FR-D5）；前沿知识走外部输入。
- 2026-09-19 裁决：**经验流**——复盘/项目经验识别（FR-A8）、项目追问（FR-D6）、本地 Git 学习源（FR-A7）。
- **2026-09-19 重大裁决（推翻此前 BYOK 方案）：v1 零模型零网络**——不配置任何模型（云端/本地/嵌入均无）；LLM 能力全部由外部编码代理承担；Atlas 与 agent 以 inbox/outbox 文件协议 + 上下文包协作；NFR-3 升级为零网络。裁决理由：用户已有 agent 生态、零成本、零泄露面、架构更简。
- 2026-09-19 合入 Summary 库审计结论：三档隐私边界（工作仓库"仅本地"）、`##` 小节一等锚点、D5 对齐既有周练规则（追加式批改/未答题延迟揭晓/可选不打分）、M0 以 Summary 为 dogfooding 库。
- 2026-09-19 合入技术可行性调研（调研-T，见 `research/tech-feasibility/`）：CMP 路线**有条件可行**（Wayland 仅 XWayland、AppImage 降 P2、deb 唯一承诺格式）；中文检索方案落定 **FTS5 trigram + <3 字 LIKE 兜底**；FSRS 落定 **java-fsrs**（官方 MIT）；技术栈版本全部落定（NFR-6）；新增 IME 冒烟与 AppCDS 实测验收。

## 附录 B.1：主题系统重构实施计划

# Atlas Unified Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 11 套运行时派生主题重构为唯一内置 Atlas 双模式主题，并为全应用和 Markdown 建立一致、可迁移、可验证的语义配色。

**Architecture:** `ThemeSpec` 表示一种明暗模式下的 20 个完整语义色，`ThemeDefinition` 聚合独立的 `light` / `dark` 两个 `ThemeSpec`。业务组件继续只消费 `Theme.*` 和 Material 语义色；旧格式只在迁移边界解析一次，不进入运行时主题选择流程。

**Tech Stack:** Kotlin 2.2.20、Compose Multiplatform 1.12.0、Material 3 1.9.0、JUnit 5、Gradle 8.14.3。

**Spec:** `atlas/PRD.md` §6.4。

### Global Constraints

- 内置主题只有 `Atlas`，Catppuccin Macchiato 是唯一色彩来源。
- 每个主题保存独立浅色和深色色板，运行时禁止明暗机械派生。
- 每套模式色板固定 20 个字段；普通文本对比度不低于 4.5:1，交互轮廓和非文本状态不低于 3:1。
- Markdown 只有阅读优化样式，不新增语法高亮。
- 旧内置主题安全回退，合法旧自定义主题必须迁移，非法单条记录不得阻止启动。
- 不新增依赖，不引入网络能力，不修改知识库内容协议。

---

### Task 1: 建立双模式主题领域模型与 Atlas 基准色板

**Files:**

- Modify: `atlas/app/src/main/kotlin/atlas/ui/Theme.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Replace: `atlas/app/src/test/kotlin/atlas/ThemeDeriveTest.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/ThemeContrastTest.kt`

**Interfaces:**

- Produces: `ThemeSpec`, `ThemeDefinition(name: String, light: ThemeSpec, dark: ThemeSpec)`, `ThemeDefinition.spec(dark: Boolean): ThemeSpec`、`AtlasThemes.ATLAS`。
- Preserves: `AtlasTheme(dark, fontScale, spec, content)`、`Theme.Accent` 等业务消费入口。

- [ ] **Step 1: 先写唯一内置主题与精确色值的失败测试**

```kotlin
@Test
fun `内置主题只有 Atlas 且两套色板为人工定值`() {
    assertEquals(listOf("Atlas"), AtlasThemes.ALL.map { it.name })
    assertEquals("#FF181926", hexOf(AtlasThemes.ATLAS.dark.background))
    assertEquals("#FFF6F6F9", hexOf(AtlasThemes.ATLAS.light.background))
    assertEquals("#FFC6A0F6", hexOf(AtlasThemes.ATLAS.dark.md().h1))
    assertEquals("#FF4969B2", hexOf(AtlasThemes.ATLAS.light.md().h2))
}
```

- [ ] **Step 2: 运行测试并确认因 `ATLAS` / `ThemeDefinition` 尚不存在而失败**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeDeriveTest'`

Expected: `compileTestKotlin` 失败，指出新接口尚不存在。

- [ ] **Step 3: 用完整模式色板替换原始主题列表和派生模型**

```kotlin
data class ThemeDefinition(
    val name: String,
    val light: ThemeSpec,
    val dark: ThemeSpec,
) {
    fun spec(dark: Boolean): ThemeSpec = if (dark) this.dark else light
}

object AtlasThemes {
    const val NAME = "Atlas"
    val ATLAS = ThemeDefinition(NAME, light = atlasLight(), dark = atlasDark())
    val ALL = listOf(ATLAS)
    val DEFAULT = ATLAS
    fun specOf(name: String, dark: Boolean): ThemeSpec = ATLAS.spec(dark)
}
```

删除运行时 `lighten()` / `darken()`、11 套 `RAW` 和自动 `readable()` 修色；`ThemeSpec` 改为 12 个界面字段与 8 个 Markdown 字段，具体色值逐项采用 §6.4.2–§6.4.3。

- [ ] **Step 4: 扩充对比度测试，分别检查页面底、内容面和交互轮廓**

```kotlin
AtlasThemes.ALL.flatMap { listOf(it.light to "浅", it.dark to "深") }.forEach { (s, mode) ->
    need(mode, "正文/页面", hexOf(s.onSurface), hexOf(s.background), 4.5)
    need(mode, "正文/内容面", hexOf(s.onSurface), hexOf(s.surface), 4.5)
    need(mode, "交互轮廓/页面", hexOf(s.outline), hexOf(s.background), 3.0)
    need(mode, "链接/内容面", hexOf(s.md().link), hexOf(s.surface), 4.5)
}
```

- [ ] **Step 5: 运行主题模型与对比度测试并确认通过**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeDeriveTest' --tests 'atlas.ThemeContrastTest'`

Expected: 两个测试类全部通过，无对比度失败列表。

- [ ] **Step 6: 提交主题模型**

```bash
git add atlas/app/src/main/kotlin/atlas/ui/Theme.kt atlas/app/src/main/kotlin/atlas/ui/Common.kt atlas/app/src/test/kotlin/atlas/ThemeDeriveTest.kt atlas/app/src/test/kotlin/atlas/ThemeContrastTest.kt
git commit -m "refactor(atlas): establish paired Atlas palettes"
```

### Task 2: 版本化自定义主题并迁移旧配置

**Files:**

- Modify: `atlas/app/src/main/kotlin/atlas/ui/Theme.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/Main.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/core/Settings.kt`
- Replace: `atlas/app/src/test/kotlin/atlas/ThemeMdTest.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/ThemeSwitchTest.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/SettingsTest.kt`

**Interfaces:**

- Consumes: `ThemeDefinition` 和 20 字段 `ThemeSpec`。
- Produces: `CustomTheme(name, light, dark)`、`CustomTheme.encode()`、`CustomTheme.decode(raw)`、`migrateThemeSettings(AppSettings): AppSettings`。

- [ ] **Step 1: 写新格式往返、旧 18 色迁移和非法记录隔离的失败测试**

```kotlin
@Test
fun `v2 自定义主题完整往返双模式`() {
    val original = CustomTheme("夜航", AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)
    val encoded = original.encode()
    assertTrue(encoded.startsWith("v2|夜航|"))
    assertEquals(original, CustomTheme.decode(encoded))
}

@Test
fun `旧内置选择与旧自定义主题一次迁移`() {
    val old = "markdown|" + legacyEighteenHexes.joinToString("|")
    val migrated = migrateThemeSettings(AppSettings(themeName = "Nord", customThemes = listOf(old, "broken")))
    assertEquals("Atlas", migrated.themeName)
    assertEquals(1, migrated.customThemes.size)
    assertTrue(migrated.customThemes.single().startsWith("v2|markdown|"))
}
```

- [ ] **Step 2: 运行测试并确认新构造器、新格式和迁移函数缺失**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeMdTest' --tests 'atlas.ThemeSwitchTest' --tests 'atlas.SettingsTest'`

Expected: 编译或断言失败，原因对应尚未实现的 V2 接口。

- [ ] **Step 3: 实现 V2 编解码与只用于迁移的旧格式转换**

```kotlin
data class CustomTheme(val name: String, val light: ThemeSpec, val dark: ThemeSpec) {
    fun spec(dark: Boolean) = if (dark) this.dark else light
    fun encode(): String = (listOf("v2", safeName(name)) + light.hexList() + dark.hexList()).joinToString("|")

    companion object {
        fun decode(raw: String): CustomTheme? = when {
            raw.startsWith("v2|") -> decodeV2(raw)
            else -> decodeLegacy(raw)
        }
    }
}
```

旧 12/18 色只在 `decodeLegacy` 中映射成新版字段，再分别执行冻结的旧版浅色、深色转换一次；V2 记录必须严格校验 42 段和全部必填字段。

- [ ] **Step 4: 实现启动前迁移和解析优先级**

```kotlin
internal fun migrateThemeSettings(s: AppSettings): AppSettings {
    val migrated = s.customThemes.mapNotNull(CustomTheme::decode).map(CustomTheme::encode)
    val customNames = migrated.map(CustomTheme::nameOf).toSet()
    val selected = if (s.themeName in customNames || s.themeName == AtlasThemes.NAME) s.themeName else AtlasThemes.NAME
    return s.copy(themeName = selected, customThemes = migrated)
}
```

在 Compose `application {}` 启动前调用一次；只有返回值变化时写回设置。`resolveTheme` 保持“同名自定义优先”，否则只返回 Atlas 对应模式。

- [ ] **Step 5: 删除 `AppSettings.markdownStyle`，读取旧键时自然忽略且保存时不再写出**

测试先把旧 `markdownStyle=classic` 写入 properties，再保存加载后的设置，断言输出文件不含 `markdownStyle=`。

- [ ] **Step 6: 运行迁移与设置测试并确认通过**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeMdTest' --tests 'atlas.ThemeSwitchTest' --tests 'atlas.SettingsTest'`

Expected: 三个测试类全部通过，合法旧主题转为 V2，非法记录被隔离。

- [ ] **Step 7: 提交迁移层**

```bash
git add atlas/app/src/main/kotlin/atlas/ui/Theme.kt atlas/app/src/main/kotlin/atlas/Main.kt atlas/app/src/main/kotlin/atlas/core/Settings.kt atlas/app/src/test/kotlin/atlas/ThemeMdTest.kt atlas/app/src/test/kotlin/atlas/ThemeSwitchTest.kt atlas/app/src/test/kotlin/atlas/SettingsTest.kt
git commit -m "feat(atlas): migrate themes to paired palettes"
```

### Task 3: 把配色页重构为 Atlas 主题工作区

**Files:**

- Modify: `atlas/app/src/main/kotlin/atlas/ui/ColorSettingsView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/ThemeSwitchTest.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/SettingsTest.kt`

**Interfaces:**

- Consumes: `CustomTheme.spec(dark)`、`AtlasThemes.ATLAS.light/dark`、V2 编解码。
- Produces: `createCustomTheme(name, base): CustomTheme`、按模式编辑并原子保存双模式主题的 UI 流程。

- [ ] **Step 1: 写“基于 Atlas 创建时复制两套模式、编辑一侧不改变另一侧”的失败测试**

```kotlin
@Test
fun `基于 Atlas 新建会复制独立双模式`() {
    val created = createCustomTheme("我的主题", AtlasThemes.ATLAS)
    val changed = created.copy(dark = created.dark.copy(accent = parseHexColor("#FF112233")!!))
    assertEquals(AtlasThemes.ATLAS.light.hexList(), changed.light.hexList())
    assertNotEquals(created.dark.hexList(), changed.dark.hexList())
}
```

- [ ] **Step 2: 运行测试并确认创建 helper 尚不存在**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeSwitchTest' --tests 'atlas.SettingsTest'`

Expected: `createCustomTheme` 未定义导致失败。

- [ ] **Step 3: 重构配色二级页**

删除“内置 N 套”三列画廊，顶部显示 Atlas 的浅色、深色双预览和当前模式说明；“我的主题”保留选择、编辑、删除；创建动作固定为“基于 Atlas 新建”。内置 Atlas 不显示编辑或删除入口。

- [ ] **Step 4: 重构主题编辑器**

一级切换“浅色 / 深色”，二级分组“界面配色 / Markdown 配色”；每次编辑只替换目标模式的 `ThemeSpec`，随后把完整 `CustomTheme` V2 记录原子写回。保留色块取色、格式校验和错误反馈。

- [ ] **Step 5: 运行主题与设置测试并确认通过**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.Theme*' --tests 'atlas.SettingsTest'`

Expected: 所有主题与设置测试通过。

- [ ] **Step 6: 提交主题设置界面**

```bash
git add atlas/app/src/main/kotlin/atlas/ui/ColorSettingsView.kt atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt atlas/app/src/test/kotlin/atlas/ThemeSwitchTest.kt atlas/app/src/test/kotlin/atlas/SettingsTest.kt
git commit -m "refactor(atlas): turn color settings into theme workspace"
```

### Task 4: 收敛 Markdown 渲染并应用分级语义色

**Files:**

- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/LearnView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/LearningView.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/PreviewDialog.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/ThemeMdTest.kt`
- Modify: `atlas/app/src/test/kotlin/atlas/ui/MarkdownTableTest.kt`

**Interfaces:**

- Consumes: `Theme.MdH1`、`Theme.MdH2`、`Theme.MdH3`、`Theme.MdBold`、`Theme.MdLink`、`Theme.MdQuote`、`Theme.MdInlineCode`、`Theme.MdInlineCodeBg`。
- Produces: `MarkdownText(md, modifier, dirtyLines)` 单一阅读渲染入口。

- [ ] **Step 1: 写 Markdown 分级语义色与单一入口的失败测试**

```kotlin
@Test
fun `Atlas markdown 语义具有稳定层级`() {
    val md = AtlasThemes.ATLAS.dark.md()
    assertEquals("#FFC6A0F6", hexOf(md.h1))
    assertEquals("#FF8AADF4", hexOf(md.h2))
    assertEquals("#FFCAD3F5", hexOf(md.h3))
    assertEquals(hexOf(md.h3), hexOf(md.bold))
    assertNotEquals(hexOf(md.h2), hexOf(md.link))
}
```

- [ ] **Step 2: 运行测试并确认旧 `MdSpec.heading` 无法满足新接口**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeMdTest' --tests 'atlas.ui.MarkdownTableTest'`

Expected: 新的 `h1` / `h2` / `h3` 属性尚不存在或断言失败。

- [ ] **Step 3: 删除 Classic 渲染分支和所有 `markdownStyle` 调用**

```kotlin
@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    dirtyLines: Set<Int> = emptySet(),
) = ReaderMarkdownText(md, modifier, dirtyLines)
```

设置首页删除“Markdown 展示”选择器；题库答案、预览和卡片背面统一调用单一入口，不再按 classic 改答案标题色或 `LocalContentColor`。

- [ ] **Step 4: 将 H1/H2/H3、粗体、链接、引用、列表、表格与代码映射到 §6.4.3**

H1 使用 `Theme.MdH1`，H2 使用 `Theme.MdH2`，H3 使用 `Theme.MdH3`；粗体保持正文语义；链接使用青绿色；引用结构线使用 H1 的克制透明色；列表标记与表头使用 H2；代码块正文显式使用 `onSurface`。

- [ ] **Step 5: 运行 Markdown 和主题测试并确认通过**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeMdTest' --tests 'atlas.ui.MarkdownTableTest' --tests 'atlas.SettingsTest'`

Expected: 三个测试类全部通过，代码中 `rg -n 'markdownStyle|ClassicMarkdown' atlas/app/src` 无结果。

- [ ] **Step 6: 提交 Markdown 收敛**

```bash
git add atlas/app/src/main/kotlin/atlas/ui/Common.kt atlas/app/src/main/kotlin/atlas/ui/LearnView.kt atlas/app/src/main/kotlin/atlas/ui/LearningView.kt atlas/app/src/main/kotlin/atlas/ui/PreviewDialog.kt atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt atlas/app/src/test/kotlin/atlas/ThemeMdTest.kt atlas/app/src/test/kotlin/atlas/ui/MarkdownTableTest.kt
git commit -m "refactor(atlas): unify markdown reading colors"
```

### Task 5: 审计全应用颜色并完成视觉与回归验收

**Files:**

- Modify: `atlas/app/src/main/kotlin/atlas/ui/Theme.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/Common.kt`
- Modify: `atlas/app/src/main/kotlin/atlas/ui/TreeIcons.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/Main.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/CardsBrowse.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/Dashboard.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/InboxView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/LearnView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/LearningView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/MermaidChart.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/SettingsView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/TodayView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/ToolsView.kt`
- Inspect and modify only when the literal is a product color rather than an algorithm color: `atlas/app/src/main/kotlin/atlas/ui/WindowChrome.kt`
- Preserve algorithm literals in `atlas/app/src/main/kotlin/atlas/ui/ColorPicker.kt` unless they escape the picker preview.
- Modify: `atlas/PRD.md`

**Interfaces:**

- Consumes: 完整 Atlas 语义 token 和 Material `ColorScheme`。
- Produces: 不依赖黑白硬编码的全应用配色、最终验证记录。

- [ ] **Step 1: 先增加 Material 映射和装饰/交互轮廓分离的失败断言**

```kotlin
@Test
fun `Material 颜色映射保持三级表面与双轮廓`() {
    val spec = AtlasThemes.ATLAS.dark
    val scheme = spec.toMaterialScheme(dark = true)
    assertEquals(hexOf(spec.background), hexOf(scheme.background))
    assertEquals(hexOf(spec.surfaceVariant), hexOf(scheme.surfaceVariant))
    assertEquals(hexOf(spec.outline), hexOf(scheme.outline))
    assertEquals(hexOf(spec.outlineVariant), hexOf(scheme.outlineVariant))
}
```

- [ ] **Step 2: 运行断言并确认旧映射把 `outline` 错接到次要文字**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle test --tests 'atlas.ThemeSwitchTest'`

Expected: `scheme.outline` 与新版 `spec.outline` 不一致。

- [ ] **Step 3: 审计并替换业务 UI 中无语义的硬编码颜色**

Run: `rg -n 'Color\.(Black|White)|Color\(0x|copy\(alpha' atlas/app/src/main/kotlin/atlas atlas/app/src/main/kotlin/atlas/ui`

保留颜色选择器自身的 HSV 彩虹和透明棋盘等算法色；图标描边、窗口控件、卡片、输入框、选中态、危险操作全部改用 `Theme.*` 或 `MaterialTheme.colorScheme.*`。透明度只用于弱背景，不降低正文对比度。

- [ ] **Step 4: 运行全量测试和编译**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle clean test compileKotlin`

Expected: Gradle `BUILD SUCCESSFUL`，所有测试零失败。

- [ ] **Step 5: 启动应用并检查六个关键页面的浅深模式**

Run: `cd atlas/app && JAVA_HOME=$HOME/jdk/jdk-17 ~/10-tools/gradle-8.14.3/bin/gradle run`

检查工作台、题库、设置、主题编辑器、Markdown 预览、通用弹窗：三级表面可辨、文字无低对比、状态不只依赖颜色、浅深切换不闪回旧主题、自定义主题重启后仍生效。

- [ ] **Step 6: 将 PRD §6.4 从“待实现”更新为实现状态并记录测试数量**

仅在 Step 4 和 Step 5 均完成后修改；变更日志记录迁移结果、最终测试命令与实际通过数量，不使用预估数字。

- [ ] **Step 7: 提交全应用收口**

```bash
git add atlas/app/src/main atlas/app/src/test atlas/PRD.md
git commit -m "feat(atlas): complete unified visual theme"
```

## 附录 C：变更日志

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-27 | — | **UI 颜色深化裁决**：保留 Catppuccin Macchiato 唯一色源，视觉人格确定为“编辑器暖灰”；深色采用清晰三级中性表面，浅色同步转为暖纸张。Peach/Teal/Mauve/Blue/状态色按动作、链接、标题、信息焦点和状态重新分工；选中、悬停、焦点、禁用采用组合状态表达；彩色面积收敛到语义控件和 Markdown 结构。完整决策见 §6.4.6。 |
| 2026-09-27 | — | **主题与 Markdown 视觉系统重构设计获批，尚未实现。** 内置主题将从 11 套收敛为唯一的 Atlas，以 Catppuccin Macchiato 为唯一色源；浅色、深色改为人工校准并独立存储的完整模式色板，移除运行时机械派生。配色页改为 Atlas 双模式预览 + 自定义主题管理；旧内置选择回退 Atlas，旧自定义主题一次性迁移为双模式版本。Markdown 删除经典样式，采用独立标题层级、链接、引用、行内代码和代码块语义色，并以 WCAG 对比度测试与六个关键页面的双模式视觉检查作为放行条件。完整规格见 §6.4。 |
| 2026-09-27 | — | **配色改为二级页 + 主题编辑器支持从色板取色。****① 配色收进二级页。** 此前 11 套主题的画廊（3 列 × 4 行卡片）连同「我的主题」列表、新建/编辑/删除全部平铺在设置首页的「外观与阅读」卡片里，把主页面挤得几乎看不到别的设置项。现抽出独立二级页 `ColorSettingsPage`：主题画廊、自定义主题列表、三个增删改对话框整体搬入，页首提供「‹ 返回」。主页面只留一行入口（色块 + 当前主题名 + 右箭头），点击进入；`深浅色` 快切仍留在主页面——它只占一行且属高频操作，搬走反而多一步。导航状态 `settingsSection` 挂在 Main 的 tab 层，与既有的 `learnSection` 同构，跨页签切换后回到设置仍是首页，不会把用户留在二级页里出不来。**② 主题编辑器可从色板取色。** 此前 18 个色值只能手打 hex——而 hex 要选中文字框、全选、输入 9 位、还要自己判断格式对不对，实际用起来很痛苦。现在每个色块即取色按钮，点开 `ColorPickerDialog`：**SV 面板**（横向白→纯色相、纵向透明→黑两层叠加出标准方块，按下即定位、可拖动微调）、**色相滑杆**（七段彩虹渐变 + 指示环）、**透明度滑杆**（代码块底/代码底色这类需要半透明时用），以及两排现成色板：**「本主题配色」**把当前主题已有的颜色去重后列出，点一下直接借用——这是最常用的路径，且天然保证配色协调；**「标准色板」** 24 色兜底。选中即写回并落盘，走原有 `put()` 校验路径，因此非法值与基色留空仍会被拦并显示红底提示。**③ 颜色数学自带测试。** HSV↔RGB 自己实现而非依赖 Compose 版本 API（不同版本可用性不一），因此配了 5 项测试锁住：8 个主色相的期望 RGB、灰阶饱和度为 0、9 个探针色的双向往返、360°/负色相回绕、alpha 与 RGB 独立保留。顺带修掉 `dragNormalized` 里 `awaitFirstDown()` 被调用两次的真 bug——第二次会等一个永远不会来的按下事件，导致手势永久挂起、该区域完全无响应。全部 179/179 通过。 |
| 2026-09-27 | — | **修两件事：对比度闸门（44 处不可读）+ 清空色值崩溃。****① 派生主题大面积不可读。** 按 WCAG 相对亮度公式逐项核算 11 套主题的浅/深两版，发现 44 处不达标，且几乎全集中在「从深色源派生的浅色版」——如 Gruvbox Dark/浅 强调色 1.65、Ayu Mirage/浅 行内代码 1.42、Catppuccin Mocha/浅 强调色 2.05（需 ≥3.0/4.5）。根因不是配色选错，而是 `lighten()` 只收敛了背景/表面/正文/次要文字/分隔线等中性色，**强调色、语义色、行内代码色原样穿过**：深色主题的淡蓝强调色落到近白底上必然失效。`darken()` 此前只有一个针对 accent 的局部补丁（`ensureBrightEnough`），管不住其余字段。现抽出 `readable()`：按对比度下限把彩色语义色朝背景反方向二分收敛到刚好达标，sRGB lerp 向黑是等比缩放，**色相与饱和度不变**；底色与卡片面亮度不同，两者分别校验。lighten/darken 统一走它，局部补丁删除。11 套 ×2 版 ×10 项全部达标。并把审计固化为 `ThemeContrastTest` 失败闸门（含对比度函数自校验：黑白必须 21.0），以后新增主题会被自动拦下，不再靠肉眼。**② 清空任一基色崩溃。** 现场日志显示 `NullPointerException: null` 于 `fromHexList`——该函数对 12 个基色用 `!!` 强解包，而空串会绕过 `parseHexColor` 直接变 null；用户在编辑弹窗里清空任一基色框即触发，且抛在键盘输入回调里冒泡到 AWT 事件线程，表现为一个**没有文字的 "Unknown error" 弹窗**。逐项复现确认 12/12 全部崩溃，非偶发。现改为逐项判空返回 null（与非法色值同等处理：拒绝保存，不崩），并新增 2 项回归测试（基色清空必被拒 / markdown 色清空仍可留空）。**③ 顺带补可见反馈**：此前非法输入只是静默不落盘，用户无从判断是卡了还是没生效；现于弹窗内显示红底状态行，分别提示「色值格式不对（需 #RRGGBB 或 #AARRGGBB）」、「XX 是必填项，不能留空」、「配色不完整，暂未保存」。全部 174/174 通过。 |
| 2026-09-26 | — | **修自定义主题「编辑/删除静默失效」**。上一条把持久化格式定成 `名称|h1|…|h18`（名字在最前）以避开 hex 内含逗号与列表分隔符撞车，但 `SettingsView` 里 5 处按名字定位记录的代码沿用了旧格式的 `substringAfterLast("|")`——在新格式下它取到的是**最后一个 hex 值**（如 `#FF1E66F5`），永远匹配不上目标名。失效方式很隐蔽：编辑时 `map` 无一命中，原样写回，改动无声丢失（不报错、不刷新）；删除时 `filterNot` 同样删不掉。修复方式不是逐处改分隔符，而是把「名字在首段」这一格式不变量收敛成 `CustomTheme.nameOf(raw)` 单点，5 处调用全部改走它——格式知识只有一处，日后改格式不会再漏。新增回归测试「按名字在列表里定位并改写自定义主题」：覆盖定位、编辑替换、其余记录不受影响、删除剔除；已验证该测试能抓住本 bug（把 `nameOf` 改回旧实现即 FAILED）。注意此前的 `encode/decode` 往返测试**抓不到**这个问题——`decode` 一直正确读取 `parts[0]`，错的只是 UI 侧定位。全部 170/170 通过。 |
| 2026-09-26 | — | **Markdown 语义色并入主题：每套主题自带 markdown 配色，可编辑并永久存储**。此前 markdown 的 6 个语义色是一份**全局** `MdColors` 覆盖，写在 `settings.properties` 的 `mdColor*` 键里——这意味着换主题时它会跨主题泄漏：A 主题下配的标题色会盖到 B 主题上，markdown 观感与所选主题对不上。现把 markdown 配色并入 `ThemeSpec`：`mdHeading`/`mdBold`/`mdLink`/`mdQuote`/`mdInlineCode`/`mdInlineCodeBg` 六项作为**末尾可空字段**（放在末尾且可空，内置 11 套主题的 12 参数构造一行不用改，自定义主题则能存自己的值），`ThemeSpec` 从 12 项扩到 18 项。取值规则：留空 = 由基色推导（标题/加粗/链接取强调色、引用取次要文字、行内代码取行内代码色），填值 = 该主题独有。`lighten()/darken()` 会连带变换这六项，所以深浅两版的 markdown 各自成立。**持久化存生效值而非 null 占位**：主题是整体，存推导结果后即使日后推导规则变化，已存主题的观感也不会漂移。**旧格式兼容**：`fromHexList` 同时接受 18 项与旧版 12 项（12 项补齐为空并走推导），此前已保存的自定义主题不会因格式变更而丢失。设置页随之调整：删掉全局「Markdown 配色」卡片（它就是跨主题泄漏的源头），编辑自定义主题的弹窗改成「界面配色 / Markdown 配色」两个分组页签，共用同一套 hex 行与色块预览；输入过程允许非法中间态（只打了 `#` 之类）而不报错落盘，解析成功才写回设置。`MdColors` 数据类与 `AtlasTheme` 的 `mdColors` 参数、`AppSettings.mdColors` 及其 `mdColor*` 持久化键一并删除。新增 ThemeMdTest 7 项（markdown 六项可持久化往返、未指定时按基色推导、内置主题生效、浅深派生带上 markdown 色、兼容旧 12 色记录、留空固化为推导值、内置 18 项往返稳定）。实测 11 套主题给出 11 种不同标题色，markdown 确实随主题变。全套 169/169 通过。 |
| 2026-09-26 | — | **Markdown 语义色并入主题：每套主题自带 markdown 配色，可编辑、永久存储**。此前 Markdown 配色是一份**全局** `MdColors` 覆盖（`mdColor*` 六个设置键），与主题平行——这意味着换主题后正文 markdown 仍被上一套的覆盖钉死，正是上一条「换主题没反应」的另一半成因。现把六个语义色移入 `ThemeSpec`（字段 12 → 18），删除全局 `MdColors` 及其设置键：**① 每套主题自带**：`mdHeading`/`mdBold`/`mdLink`/`mdQuote`/`mdInlineCode`/`mdInlineCodeBg` 六项随主题走，11 套内置主题的标题色实测 11 种互不相同，引用色随深浅两版各自变化。**② 可编辑**：主题编辑弹窗拆成「界面配色 / Markdown 配色」两页签，Markdown 页可改这 6 项。**③ 永久存储**：与基色一同编入 `名称|h1|…|h18` 落 `settings.properties`，随自定义主题长期保留。**设计取舍**：六项声明为**可空且位于末尾**——内置 11 套主题的 12 参数构造代码一行未改，`null` 表示「由基色推导」（标题/加粗/链接=强调色，引用=次要文字，行内代码=行内代码色），用户不填也能得到协调的观感；同时**落盘时存推导后的生效值而非 null 占位**，这样日后推导规则若调整，已存主题的观感不会漂移。**向后兼容**：`fromHexList` 同时接受 18 项与旧 12 项（旧的补齐为推导值），此前保存的自定义主题不会因格式变更而失效或错位。编辑弹窗另修一处体验缺陷：输入中途只打了 `#` 之类非法值时不再丢弃（`editing` 局部态承载中间输入，解析成功才落盘），此前会被 `fromHexList` 判为非法而"打不进字"。新增 ThemeMdTest 7 项（18 项往返、未指定走推导、内置主题生效、派生带上 markdown 色、旧 12 色兼容、留空固化为推导值、全部内置主题 18 项稳定），全套 169/169 通过。 |
| 2026-09-26 | — | **修「换主题没反应」+ markdown 颜色接入主题**。用户反馈点选任意内置主题界面完全不变。**根因**：`Main.kt` 的 `resolveTheme` 只在 `customThemes`（用户自建主题）里按 `themeName` 查找，找不到就**恒定返回 `AtlasThemes.specOf(DEFAULT.name, …)`**——内置主题表根本没被查。而 `themeName` 确实被写入了设置，所以表现为"选中态变了、界面不变"。现补上内置分支：自定义主题优先 → 同名内置主题其次 → 都没有才回落默认。**markdown 颜色此前不跟随主题**：`MdHeading`/`MdBold`/`MdLink`/`MdQuote` 在用户未显式指定时返回 null，调用点回落到 `Color.Unspecified`（即继承）或硬编码的 `Theme.Muted`，只有行内代码跟着主题走——这正是"换主题时正文 markdown 不变色"的原因。现改为未覆盖时取主题色：标题/加粗/链接 = 主题强调色，引用 = 主题次要文字，行内代码 = 主题行内代码色；用户在「Markdown 配色」里的显式设置仍然优先。调用点随之去掉 `?: Color.Unspecified` 兜底。新增 ThemeSwitchTest 6 项锁死两条契约：① 每套内置主题解析出唯一配色、强调色与深色底确实被采用、同名时自定义优先、深浅两版不同；② markdown 六个语义色在未覆盖时随主题变、被显式覆盖时以覆盖为准。其中一条断言我最初写错（要求 `lighten()` 幂等），改为校验真正需要的"深浅两版不同"。`resolveTheme` 由 `private` 改为 `internal` 以便同包测试。全套 164/164 通过。 |
| 2026-09-26 | — | **配色主题重做：11 套权威配色 + 自定义主题可命名永久保留 + 修「编辑主题」无响应 + UI 层次深化**。四项诉求一次做完：**① 修 bug**：「编辑当前主题」点了没反应——`editingTheme` 赋空串而非 null，`?.let` 只拦 null，空串照样进弹窗，随后 `firstOrNull { it.name == "" }` 找不到目标，在**组合期间**调 `onDismiss()` 导致弹窗瞬开瞬关；且原设计让内置主题也能进编辑流程，而内置主题根本不在 `customThemes` 里，无从编辑。现改为：编辑入口只挂在「我的主题」卡片旁，内置主题一律经「复制」生成可编辑副本。**② 自定义主题**：「新建」弹窗支持自定义命名（重名实时校验并报错）+ 选母版（任意内置或已有自定义），创建后落 `settings.properties` 永久保留；每张自定义主题卡带「编辑 / 删除」，删除有二次确认且会在删除当前主题时回落到默认。**③ 配色换成权威来源**：旧配色是手挑的 4 套，现从各主题官方源抓取解析——Catppuccin 4 变体（`catppuccin/palette`）、Nord + Nord Light（`arcticicestudio/nord`）、Ayu Light/Dark/Mirage（`ayu-theme/ayu-colors`）、One Dark（`atom/one-dark-syntax`，色值是 HSL 需换算）、Gruvbox Dark（`morhetz/gruvbox`，vim 语法需解析 `s:gb.*`）。**每套只存一份源配色**，深浅色由 `ThemeSpec.lighten()/darken()` 派生——手工维护两套 inevitable 会漂移。**④ UI 深化**：`AtlasPalette` 新增 `elevated`/`inputBg` 两个派生表面色并接入设置页卡片、预览块、三个弹窗，卡片比页面底凸出一层形成层次；间距 16→18dp、分区间距 8→10dp。**实现坑（都是测试/探针抓出来的）**：① `converge()` 收敛权重符号错——朝黑收敛时 `goal-cur` 为负、被 `coerceIn(0,1)` 夹成 0，导致「浅色主题派生深色版完全不变」，且因 `towardWhite` 方向恰好同号而只在一半场景暴露；剩余空间必须取「当前色到目标端点」而非 goal。② 强调色**不能**做亮度收敛：把 `#1E66F5` 收到 0.10 亮度会变成近黑的 `#0A317E`，色相被毁；改为 `ensureBrightEnough()`——只在过暗时轻微提亮。③ 新增 ThemeDeriveTest 5 项守住派生结果（可解析/够浅够深/幂等/可区分），并断言每套主题两版的每个色值 `colorSpace` 都能解析（沿用上一条 `Color(ULong)` 崩溃的回归防线）。全套 157/157 通过。 |
| 2026-09-26 | — | **多套配色主题 + 支持自定义主题**：此前只有「浅色/深色」两档，业务色硬编码在 `AtlasPalette` 的两个常量里，用户改不了。本轮新增 `ui/Theme.kt`：`ThemeSpec` 定义 12 个语义色（强调色/成功绿/警告橙/错误红/次要文字/代码块底/行内代码 + 页面底/卡片底/控件底/正文色/分隔线），`AtlasThemes` 内置 4 套——默认靛蓝、GitHub、GitLab、Gruvbox 暖，**每套各带明暗两版**，深浅色切换不再是同一套色的反相。关键设计：表面层必须进主题，因为本应用近 50 处颜色取自 Material3 的 `colorScheme` 而非 `AtlasPalette`，只换强调色会导致所有底色不变；`ThemeSpec.toMaterialScheme(dark)` 用 `lightColorScheme()/darkColorScheme()` 派生后 `copy()` 覆盖，而不是继续用 Material3 默认色板。设置页把原来的明暗 ChipSelector 换成**主题画廊**（每格显示双色底 + 五色横杠的实时预览，内置与「我的主题」分组），并提供「从当前主题复制」新建与逐项 hex 编辑（复用 mdColors 的输入行），内置主题不可直接改。**实现坑**（两处都是探针/测试抓出来的）：① `Color` → hex 必须走 `red/green/blue` 浮点分量，该 Compose 版本把 sRGB 放在 packed 值高 32 位，`value and 0xFFFFFFFF` 会得到错误颜色（与上一条 `Color(ULong)` 崩溃同源）；② 持久化分隔符不能用逗号——12 个 hex 本身含逗号，与 `settings.properties` 列表字段惯用的逗号分隔撞车，一条自定义主题会被拆成 12 条碎片，现改为 `名称|h1|…|h12`、多条以 `;` 分隔。新增 SettingsTest 6 项（持久化往返、Color↔hex 往返、内置主题全量色值可解析、编码解码与 5 类非法输入、多条主题不互相拆散、未知主题名回退），全套 152/152 通过。 |
| 2026-09-26 | — | **设置新增「Markdown 配色」：语义色可调**：`renderInline` 与两套渲染器此前把标题、加粗、链接、引用、行内代码的颜色全部硬编码在 `AtlasPalette` / `Theme` 里，用户只能整体切浅色深色，无法微调——例如想要"蓝色标题与加粗 + 红色行内代码配浅粉底"这种常见排版，只能改源码重编译。现新增 `core/MdColors`（6 个 hex 字段，空串 = 跟随主题默认）挂到 `AppSettings.mdColors`，写入 `settings.properties` 的 `mdColor*` 六个键；`Theme` 新增 `mdOverrides` 状态与 `MdHeading`/`MdBold`/`MdLink`/`MdQuote`/`MdInlineCode`/`MdInlineCodeBg` 访问器，由 `AtlasTheme` 在 SideEffect 里随设置刷新。「外观与阅读」卡片下新增「Markdown 配色」分区：**实时预览**（带左蓝条的标题、加粗、行内代码、链接、引用，与正文同一套 `renderInline`，改色即见）、**4 个预设**（默认 / 清爽蓝 / 暖纸 / 青瓷，清爽蓝即参考图的蓝标题+红代码+粉底）、**6 个 hex 输入行**（色块预览 + 输入即生效，留空回默认）、「全部恢复默认」。hex 解析支持 `#RGB`/`#RRGGBB`/`#AARRGGBB`，非法值一律回退默认色，脏配置不会让正文不可读；输入经 `sanitizeHexInput` 滤字符并限长 8 位。**实现坑**：`SpanStyle.color` 与 `Text(color=)` 不接受 null，"不覆盖"必须用 `Color.Unspecified`；`Color(Long)`/`Color(Int)` 会把 32 位值放进高半部（实测 `Color(0xFF1A56DBL).value == 0xff1a56db00000000`），**首版这里踩了坑并导致设置页崩溃**：初版误以为 `Color(Long)` 会把 32 位值塞进高半部（看了 `.value` 的十六进制就下结论），改成 `Color(ULong)` 才是"正确"写法——实际恰好相反：该 Compose 版本把 sRGB 放在**高** 32 位，`Color(ULong)` 造出的低 32 位颜色在绘制色块时 `getColorSpace` 抛 `ArrayIndexOutOfBoundsException: Index 27 out of bounds for length 20`（`BackgroundNode.drawOutline → drawRoundRect`），整个界面崩掉。`Color(Long)`/`Color(Int)` 才是对的。现 `parseHexColor` 在返回前额外做 `colorSpace` 可解析校验，任何构造错误一律回退默认色而不是崩界面。教训：**不要靠 `.value` 的十六进制推断 Compose Color 的内部布局**，要靠探针实测——同一个 API 我连错两次方向。新增 SettingsTest 4 项，全套 143/143 通过。 |
| 2026-09-26 | — | **行内代码高亮定稿：底色块与下划线全部去掉，只用高对比字色**：前两轮都在调"底色深浅"这个错误维度——先从不透明实色（深色 `0xFF404A5C`）退回 8% 中性底，方向就不对。根因：**这类文档行内代码密度极高**（`20-SELinux.md` 一行 3–5 处），任何底色块连续出现都会连成条码，硬边矩形还容易被读成选中高亮，底色再怎么调都干净不了。定稿方案：**`AtlasPalette` 只保留 `inlineCodeFg` 一个 token，渲染为 `SpanStyle(fontFamily = Monospace, color = InlineCodeFg)`**——无背景、无下划线，识别完全交给字色。取青色是唯一可用的色相：Accent 蓝（链接）、Ok 绿、Warn 橙（git 改动标记）、Bad 红都已占用，黄色会与 Warn 橙混淆，青色与 Accent 的色相差 1.38:1 不易误认。取值深色 `0xFF5FD3E8`（对答案块 **7.76:1**）、浅色 `0xFF0B6E7D`（对卡片 **5.25:1**），均过 WCAG AA 正文门槛。`ReaderMarkdownText`、`ClassicMarkdownText`、表格单元格都走 `renderInline`，一处改动全覆盖。过程教训：中间试过"下划线承担识别、彩色留给链接"的方案（`SpanStyle` 无 `textDecorationColor`，下划线只能跟随字色），最终按需求去掉。**"高亮好不好看"不是对比度问题**——前两轮拿 WCAG 数值当依据是错的，最终用 PIL 按实际取值渲染对比图目视确认。 |
| 2026-09-26 | — | **行内代码高亮返工：底色改中性自适应、识别改由字色承担**：上一条把行内代码底色从 8% 灰提到不透明实色（浅色 `0xFFCAD2E2`、深色 `0xFF404A5C`，对比度拉到 1.35–1.87:1）后被判定为太丑——**方向错了**：问题不是"不够明显"，而是把代码做成了高亮笔。深色底上带蓝调的亮块像选区标记，一屏连续芯片就是一道道荧光笔痕迹。现改为「底色只做中性微调、识别交给字色」：底色用带 alpha 的中性色（深色 8% 白、浅色 8% 黑），在任何底色上自动融进去，只留 1.19–1.27:1 的轻微抬升；字色另给冷调偏移，深色取 `0xFFD6DEEC`（约 10:1，比正文 8.55:1 更亮、正向突出），浅色取 `0xFF333A47`（约 9.3:1，比正文 12.1:1 略重）。`AtlasPalette` 相应拆成 `inlineCodeBg` / `inlineCodeFg` 两个 token。Reader 与 Classic 两套渲染器、表格单元格都走 `renderInline`，一处改动全覆盖。教训：这类"不够明显"先分清是**对比度不足**还是**识别维度选错**，后者靠加对比度只会更糟。 |
| 2026-09-26 | — | **行内代码高亮改为调色板驱动的实底色**：单反引号行内代码原先硬编码 `SpanStyle(background = Color(0x14808080))`——8% 不透明度的固定灰，不随主题走。实测与底色的对比度只有 **1.08–1.10:1**（浅色页面 1.09、浅色卡片 1.08、深色答案块 1.09、深色页面 1.10），等于没有高亮，题库里 `android.permission.*`、`PackageManagerService` 这类行内代码只能靠等宽字体辨认。现给 `AtlasPalette` 增加 `inlineCodeBg`，浅色 `0xFFCAD2E2`、深色 `0xFF404A5C`，对比度提升到 **1.35–1.87:1**（浅色页面 1.52 / 卡片 1.35，深色答案块 1.52 / 页面 1.87），仍是克制的浅底但一眼可辨。颜色未直接复用 `CodeBg`：`CodeBg` 是代码块底（浅色 `0xFFF5F6F8`），用在行内会与浅色卡片底几乎同色，等于没改。Reader 与 Classic 两套渲染器、表格单元格都走 `renderInline`，一处改动全覆盖。 |
| 2026-09-26 | — | **同源题目新增完成状态标签（推翻 09-21「同源题目无状态字段」）**：① 数据——`QuestionStatus` 三档 `todo`/`learning`/`done`，状态编码在 Q 行题面的方括号前缀（`**Q1: [done] 问题？**`），随源文档走 git、agent 可直接改；缺省即 todo 且**不写标记**，故既有文档零迁移、reorder 不产生无谓 diff；`Entry` 新增 `status` 字段但 `id` 仍只由题号+题面决定（状态是展示元数据，改状态不换题目身份，否则 `remove()` 的 id 匹配会失效）。② 协议——只认已知状态键，题面里 `[1]`/`[注意]` 原样保留（否则解析器会吃掉题面里的方括号）；`numberedBlock` 的重编号只替换 `Qn` 不碰前缀，故重排/删除/插入均保留状态。③ 交互——卡片 Q 号后以「圆点 + 文案」标记状态（learning 橙 / done 绿），**默认态不渲染**（首版做成填充圆角药丸且三态全显，实测一屏 26 题里 25 个挂着灰色 todo 药丸，底色在深色卡片上发脏、视觉噪音压过题目本身；改为圆点+文字并隐藏默认态）；编辑弹窗三态选择器同步改为圆点+文案、可点面积加大，「保存到源文档」与「保存并上/下一题」两条路径都写回状态。**UI 文案直接用文件里的英文 key**（`todo/learning/done`），界面上看到的词就是 Q 行里写的词，不必在脑子里维护一层翻译。④ 边界——遗留 `questions.md` 的 `未测→待复测→已稳定` 状态机**仍不可达且与本状态互不相通**，本轮不做批改驱动。⑤ 测试 +15（解析/大小写/防误剥/紧贴与带空格前缀/三种 Q 行格式/代码块内不误判/replace 往返/todo 不写标记/答案与身份不变/重排与删除保留/章节切段），139/139 通过。 |
| 2026-09-26 | — | **题库源文档的「文件即题库」标签改为「复制」按钮（复制绝对路径）**：该标签只说明"这份 md 同时是题库来源"，不提供任何操作；题库页真正缺的是拿绝对路径的手段——相对路径要自己拼库根，而拼接规则（库根名恰为 `knowledge-base` 时要剥掉一层前缀，见 `AppStore.sourceDocumentFile`）并不显然，拼错就是一条打不开的路径。现改为可点击的「复制」按钮（文案两字，toast 回显完整含义），走 `store.sourceQuestionFile().absolutePath`（复用同一套拼接逻辑，不会与实际打开的文件不一致），写入系统剪贴板并 toast 回执。路径文字保留可划词并加 `TextOverflow.Ellipsis` 截断。初版给路径容器加 `Modifier.weight(1f, fill = false)` 想让它按需占位，实测反而把「复制」推离右边缘约 110px：weight 把富余宽度对半分给前一个 `Spacer` 与路径容器，而 `fill = false` 下容器只量出自身内容宽度，Compose 按**实测宽度**摆放下一个子节点，多出的那份就变成路径与「复制」之间的空档。改为整行只保留原有的 `Spacer(Modifier.weight(1f))` 吸收全部富余、路径容器不参与 weight，「复制」成为最后一个子节点，紧贴内容右边缘并与下方题目卡片右对齐。剪贴板用 AWT `Toolkit.getDefaultToolkit().systemClipboard`：Compose 的 `LocalClipboardManager` 已废弃，`LocalClipboard.nativeClipboard` 在 desktop 端的扩展属性需额外 import 且未暴露 `setContents`，AWT 是本应用（desktop-only）唯一无警告的同步路径；`Main.kt` 里既有的 `LocalClipboardManager` 废弃警告未动。 |
| 2026-09-26 | — | **题面橙色标记去碎片化（整份重写不再跳色）**：2026-09-24 那条「未提交改动标记」把题面按**字符级** diff 标橙字，实际用起来在整份文档被重写时失效——`20-SELinux.md` 一次扩写（+267/−67）让 22 道题题面全变，字符级 LCS 退化成单双字交替（Q4「作为framework开发者…」碎成 6 段、Q8 碎成 11 段），用户看到满屏跳色而非「哪几道题动过」，题号旁的「·有改动」标签与正文着色对不上号。现补 `TextDiff.coalesceForHighlight`：区间数 > 3 或变化字符占比 ≥ 60% 时退化为整条题面着色，否则保留行内区间；`annotatedQuestionDiff` 改走该函数。**原始 `questionRanges` 不做退化**——它仍如实反映字符级差异，退化只发生在渲染层，`AppStoreTest` 的区间断言语义不变。答案侧本就是行级整行染色，不受影响。按真实文档复算：26 题中 21 题整条标橙、仅 Q9（两处独立改动的短语，覆盖 56%）保留行内、Q23–Q26 与 HEAD 逐字节相同故无标记。新增 TextDiffTest 6 项，全套 109/109 通过。 |
| 2026-09-26 | — | **输入法候选框固定左下角·根因定位并修复、录屏验证通过（运行时选错，非应用侧缺陷）**：本条推翻了同日早些时候「反射路线判定为死路、应用侧无可用手段」的结论——那个结论是错的，且错在把根因当成了交接文档笔误。**真实根因**：`update.sh` 用 `$HOME/jdk/jdk-17` 构建，jpackage 打进 deb 的是 **OpenJDK 17**；而 X11 下候选框跟随光标依赖 AWT 向输入法设置 XIC 的 `XNSpotLocation`，这段逻辑 **stock OpenJDK 17 根本没有**——`/opt/atlas/lib/runtime/lib/libawt_xawt.so` 中 `spotLocation` 零命中（对照组：系统 `libX11.so.6` 有，检索方法有效），`sun.awt.X11.XInputMethodBase` 全部方法里也没有任何 location/caret/spot 接口。**只有 JetBrains 在自研 JDK 里补上了**：`/opt/android-studio-for-platform/jbr`（`IMPLEMENTOR="JetBrains s.r.o."`）的 `libawt_xawt.so` 含 `spotLocation` 及 `jbNewXimClient_createInputContextOfPreeditPositionStatusNothing` / `obtainSupportedInputStylesBy` / `moveImCandidatesWindow` / `isJbNewXimClientEnabled` 等符号，需配合 `jb.awt.newXimClient.enabled` 开关——反编译 `java.desktop/sun/awt/X11/XInputMethod.class` 确认 `isJbNewXimClientEnabled()` **默认返回 true**（属性未设置即启用），故「默认关闭需手动开启」的说法不准确；JBR 仅有的两个属性就是 `jb.awt.newXimClient.enabled` 与 `jb.awt.newXimClient.preferBelowTheSpot`，但若同时设置 `recreate.x11.input.method`，后者会被忽略并打 WARNING（装机构建未设置该属性）。也就是说前三轮失败的唯一原因是运行时，与开关无关——它们甚至是在 OpenJDK 上打的一个根本不读这两个属性的包。fcitx 官方 FAQ 亦明确：*"Java program that uses swing or awt. There is a historical OpenJDK bug. But jetbrains patches there own JDK with the fix."* 这也解释了「为什么别的应用都好用」——GNOME Terminal/Chrome 等走 GTK/Qt IM module，经 DBus `cursorRect(x,y,w,h)` 显式上报坐标（OpenCode 跑在终端里，IME 是终端而非它自己在处理）；AWT 是 XIM-only，属唯一缺坐标的一类。所以 `ImeCaretFix` 那条反射路线**从一开始就无效**（Java 层 `getTextLocation` 只被 `sun.awt.im` 的 AWT 自绘 composition 窗口消费，与输入法候选框无关），但**前三轮的根因判断（XIC 样式协商 + JBR 开关）其实是对的**，只是代码被打进了未修补的运行时。**修复**：`update.sh` 改为「构建用带 jpackage 的 JDK（jdk-17），安装后把 `/opt/atlas/lib/runtime` 替换为 JBR」——JBR 102MB vs 原 jlink 镜像 73MB。JBR 无法直接打包（被精简掉 `jpackage`/`jmods`；`jimage extract`+`jlink` 重建镜像会因模块哈希丢失而失败，两条死路已记入脚本注释），jpackage 启动器只需 `lib/libjli.so`+`lib/modules`，完整 JBR 目录是 jlink 镜像的超集可直接顶替。`jb.awt.newXimClient.enabled/preferBelowTheSpot` 重新注入（`update.sh` 的 cfg + `Main.kt` 的 `installImeCompatFlags`），`ImeFlagsTest` 恢复。已还原成 jlink 镜像后完整跑通 `update.sh`（exit 0），装机版实测 `java.version=21.0.7`、`java.vendor=JetBrains s.r.o.`、`spotLocation` 就位、界面与字体无回归、启动无异常。**教训**：判断「某能力不存在」时，必须验证的是**被打包进去的那个运行时**，而不是同机的另一个 JDK；`jcmd` 看到属性被设置 ≠ 运行时读取它。另新增启动日志 `输入法能力：… JBR-XIM补丁=有/无/未知 …`（`Main.kt` 的 `logImeRuntimeCapability`，反射列举 `sun.awt.X11.XInputMethod` 是否含 `isJbNewXimClientEnabled`），把这次的诊断成本降到零：以后出候选框问题先看这一行，不必再反编译。两个实现坑一并记下——必须用 `Class.forName(name, false, loader)`（`initialize=false`），否则 `main()` 早期 AWT 未启动、`X11InputMethodBase.initIDs()` 是 native，会抛 `UnsatisfiedLinkError` 而**误报「无补丁」**；检测失败时只报「未知」而不得断言候选框坏了。检测器已跨三个运行时验证：Oracle JDK 17 / Ubuntu OpenJDK 11 = 无，JBR 21.0.7 = 有。**已由用户录屏验证修复生效**（2026-09-26 15:46 录屏，363 帧逐帧核对）：① 搜狗候选框（白底+橙 S 标）**贴在文本框光标处**、显示在应用窗口内，候选 `1.啊川 2.暗刀 3.奥恩`；② 预编辑串 **inline 渲染在插入点**（录屏中「答案」字段末尾直接显示带虚线下划线的「广东」）；③ 全 363 帧扫描确认**屏幕左下角全程无候选框**（该区域全程最亮处仅为右下角搜狗托盘图标）。即 XIM 已协商到 `XIMPreeditPosition`，正是 JBR 新 XIM 客户端的能力，stock OpenJDK 缺失该段代码。验证用的临时 JUL 调试配置与取证守护均已撤除。
| 2026-09-26 | — | **输入法候选框·第三轮（常驻 requests + 修字体缩放回归）**：用户实测第二轮包装器挂上了（日志实证）但候选框仍在左下角，且整页字体变小。定位到真正的鸡生蛋问题：XIM 在**窗口聚焦时**创建输入上下文并协商样式，那一刻 Compose 的 `currentInputMethodRequests` 为 null（文本框未激活）→ 被当作被动客户端 → XIC 建成 root-window 样式（服务端自绘预编辑+候选，永不询问客户端坐标），之后再激活输入框也不重建 IC——这同时解释了四窗口实验中 Swing（requests 常驻非 null → active 样式 → 跟随）与 Atlas 的差异。修复：包装器扩展为 null 时也放入 `AlwaysAliveRequests` 常驻实现（保证 active 协商 + 窗口内兜底坐标），激活后自动换成委托包装器。字体回归：`ImeCaretFix.install()` 从 `main()` 挪到 AppRoot 首帧 `LaunchedEffect`——main 里过早 `Toolkit.getDefaultToolkit()` 干扰 Compose 的 Swing 全局初始化时序导致整页缩放异常；settings.fontScale 未受损。待用户部署实测。 |
| 2026-09-26 | — | **输入法候选框左下角·第二轮修复（ImeCaretFix 兜底）**：首轮 jb.awt.newXimClient 开关经 jcmd 实证已生效但仍无效，遂做四窗口受控实验（同一 JBR+fcitx4/搜狗，纯 Swing JTextArea 分别用 无flag/over-the-spot/newXimClient/newXimClient+recreate 四种参数）——**四窗全部候选框跟随光标**，证明服务端链路正常，问题锁定在 Compose 挂给 AWT 的 `InputMethodRequests`：反编译证实其 `getTextLocation` 在 `focusedRect == null` 时直接返回 null，XIM 拿不到坐标即回退 root-window。新增 `ui/ImeCaretFix`：反射把每个 Compose 场景的 `currentInputMethodRequests` 换成委托包装器（原实现返回 null/(0,0) 时回退到所在窗口内容区的合理 spot，保证服务端永远拿到有效位置）；因 Compose 在文本框焦点切换时会覆盖该字段，用窗口/焦点/鼠标/键盘 AWT 事件 + 800ms 定时器持续重挂。`main()` 在窗口创建前安装。全套测试通过，待用户部署实测。 |
| 2026-09-26 | — | **修复输入法候选框固定在屏幕左下角**：Compose 应用在 X11 上走 AWT XIM，输入法拿不到光标位置时退化为 root-window 模式，候选框画在屏幕左下角（2026-09-26 用户录屏）；09-23 注入打包 cfg 的 `java.awt.im.style=over-the-spot` 实测不够。反编译所用 JBR 发现自带新版 XIM 客户端（`jb.awt.newXimClient.enabled`，默认关闭），开启后有原生 `adjustCandidatesNativeWindowPosition` 按 XIM 协议把候选框移到光标处；Compose 侧 `DesktopTextInputService` 本就实现了 `InputMethodRequests` 上报光标位置，链路因此打通。`main()` 最早处注入 `jb.awt.newXimClient.enabled/preferBelowTheSpot=true`（dev 与打包通用，尊重外部显式设置），update.sh 的 cfg 注入同步补两个 flag 双保险。新增 ImeFlagsTest 2 项（全套 97/97 通过），候选框实际定位依赖输入法服务端支持，待用户部署后实测。 |
| 2026-09-24 | — | **题库记住上次选中的文档**：`selectedSourcePath` 原来硬编码默认 `01-语法基础.md` 且不持久化，每次重启都回到该文档（用户反馈）。现选择时写入 settings（`selectedSourcePath`），启动恢复上次选择；恢复的路径已失效（换库/文档被删）时回退到扫描到的首篇文档。会话内仍可自由选中未映射文档（显示"暂未接入解析"提示），重载不弹回。新增持久化恢复与失效回退测试 2 项（全套 95/95 通过）。 |
| 2026-09-24 | — | **去掉题库页签徽标**：题库页签旁的数字原为「当前选中源文档的题目数」，与左侧映射文档数、全库题目数都不同，易被误解为题目总数，按用户决定直接移除；工作台的收件箱待确认徽标保留。 |
| 2026-09-24 | — | **题库新增「未提交改动」标记（git 比对 + 行内着色）**：逐题把当前内容（题面+答案）与 `git HEAD` 里同文档版本按题号配对比对。有差异的题目：卡片橙色边框、Q 标签追加「·有未提交改动」，并且**变化文字直接变橙色**（无背景高亮）——题面按字符级 diff 把变化字符标橙字（`TextDiff.changedRangesInNew`，AnnotatedString），答案按行级 diff 把变化行整行文字标橙（`MarkdownText` 新增 `dirtyLines` 参数，Reader/Classic 两套渲染器的标题/列表/引用/正文行都生效）。文件从未提交过则全部内容标色。比对由 `computeSourceQuestionGitDiffs`（`git show HEAD:./文件` 取旧文再解析）在 IO 线程异步执行，每次重载自动刷新，带代号防过期结果；HEAD 提交代号纳入文件监听签名（非仓库根缓存探测，避免每 3s 空转子进程），终端 `git commit` 后 ≤3s 橙标自动消失；git 不可用或库不在仓库中时静默不标色。`TextDiff` 为零依赖实现（公共前后缀裁剪 + 小规模 LCS + 超阈值整段回退）。新增 TextDiffTest 6 项、store 级测试 2 项（全套 93/93 通过），待部署后实测。 |
| 2026-09-24 | — | **修复窗口拖动来回振荡与闪烁**：原实现在拖动回调里累加 Compose 本地坐标增量推算窗口位置——窗口被自己移动后，指针不动也会收到坐标系平移带来的合成移动事件，增量方向翻转形成 A/B 两位置来回振荡；高 DPI 缩放屏上增量还带密度倍率误差。改为「OS 指针屏幕坐标闭环」：每个拖动事件读 `MouseInfo` 真实屏幕位置减去按下时记录的抓取偏移得到目标（Compose Desktop `WindowState.position` 数值即屏幕像素，经字节码确认 `componentMoved` 回写 `window.x.dp` 约定），按帧合并写入保留（避免 X11 请求积压）。合成事件、WM 干预均不影响结果。待用户复测确认。 |
| 2026-09-24 | — | **修复保存题目后列表跳回顶部**：文件监听（IO 线程）触发的 `reloadKnowledgeFiles` 对 Compose 状态列表 clear→解析（全库扫描）→addAll，UI 取帧会看到空列表，LazyColumn 滚动位置被钳回 0——每次保存必然跟着这次隐形重载，位置必跳。改为先解析进局部量、再以一个 `Snapshot.withMutableSnapshot` 原子换入（含复习队列重建），读者只见过期态或新态；未改动题目的列表 key（sourcePath#startOffset）不变，滚动锚点与展开状态原样保留。agent 并行改库触发的自动重载同样不再打断浏览。新增回归测试 2 项。 |
| 2026-09-24 | — | **修复工具页拖入路径解析**：XDND（text/uri-list）通道送来的文件是 `file:` URI 字符串（如 `file:/home/x.zip`），直接当路径用会被脚本以工作目录解析成不存在的输入（用户真实拖拽实测暴露）。`Tools.normalizeDroppedPath` 统一规范化——兼容 `file:/`、`file:///`、纯路径三种形态并解码 `%20` 转义，drop 入口统一走它，新增单测 6 项断言；待用户复测确认。 |
| 2026-09-24 | — | **修复工具页文件拖入无效**：上一条的自挂 `java.awt.dnd.DropTarget`（窗口级）实测收不到任何 XDND 事件——根因是框架的 `AwtDragAndDropManager` 已在窗口内容组件上挂了自己的 DropTarget，组件级目标拦截了窗口级拖放。改用 CMP 官方外部拖放链路：拖入区挂 `Modifier.dragAndDropTarget`（foundation 实验 API + `ExperimentalComposeUiApi`），XDND 事件由框架按组件边界分发到 `DragAndDropTarget` 回调——悬停高亮走 onEntered/onExited，文件经 `DragData.FilesList.readFiles()` 读取；`ToolsView` 不再需要 window 参数（Main.kt 恢复原签名，仅保留页签分支）。编译 + 全量测试通过，真实拖拽由用户部署后验证。 |
| 2026-09-24 | — | **修复工具页文件拖入无效**：上一条的自挂 `java.awt.dnd.DropTarget`（窗口级）实测收不到任何 XDND 事件——根因是框架的 `AwtDragAndDropManager` 已在窗口内容组件上挂了自己的 DropTarget，组件级目标拦截了窗口级拖放。改用 CMP 官方外部拖放链路：拖入区挂 `Modifier.dragAndDropTarget`（foundation 实验 API + `ExperimentalComposeUiApi`），XDND 事件由框架按组件边界分发到 `DragAndDropTarget` 回调——悬停高亮走 onEntered/onExited，文件经 `DragData.FilesList.readFiles()` 读取；`ToolsView` 不再需要 window 参数（Main.kt 恢复原签名，仅保留页签分支）。编译 + 全量测试通过，真实拖拽由用户部署后验证。 |
| 2026-09-24 | — | **新增「工具」页：本地小工具图形化集成（首个：27HM 车机日志解密）**：顶部导航新增第三个标签「工具」（§6.1 同步），把日常本地小工具收进 Atlas，全部本地执行、零网络不变。首个卡片「车机日志解密」集成 Summary 仓 `tools/hc/27M/hc_log_auto.py`（27HM V5 解密工具的原生复现，输出与 Windows 工具逐字节一致）。交互：系统文件拖入（AWT DropTarget 挂窗口，仅工具页组合期间挂载、离开即卸载，其余页签不接收拖放）或点击选择文件 → 后台进程运行脚本（运行状态存 AppStore，跨页签保留）→ 卡片内展示状态点/摘要/输入路径/脚本输出尾部 + 「打开输出目录」直达。输入预校验（目录与 zip/7z/tar/rar 扩展名）、运行中拒绝新拖入、图标 Canvas 绘制避免字体缺字。纯逻辑（命令组装/输出解析/预校验）抽至 `core/Tools.kt` 并新增单测 4 项；Main.kt 仅增加 window 传参与页签分支，导航模型测试同步。测试 87/87 通过；Xvfb 虚拟显示完成界面截图与「点击选择→运行→出结果」端到端验证；**真实拖拽（XDND）路径未能模拟验证，待 update.sh 部署后实测**。 |
| 2026-09-24 | — | **屏幕录制集成（FR-F1 新增「屏幕录制」分区）**：录屏工具（GTK 图形界面 `record-gui`：整桌面/单屏幕/框选区域 + 一键全屏脚本 `record-mp4`，均直出 AI 友好 MP4）物理迁入本仓 `tools/screen_recorder/`；Atlas 设置页新增该卡片——保存目录/默认帧率/x264 码率即改即存，导出 `~/.local/share/atlas/screen-recorder.json` 供脚本启动时读取，卡片内一键打开录屏界面。系统快捷键 Ctrl+Alt+G（GUI）/Ctrl+Alt+R（全屏）不变。编译与 Settings/AppStore 单测通过，待 update.sh 部署验证。 |
| 2026-09-23 | — | **顶栏导航页签重设计**：去掉「边框盒 + 填充胶囊 + 下划线」三重激活指示，改为单一胶囊态（激活=强调色填充+Accent 半粗文字，悬停=5% 提亮），页签组间距收紧为 2dp，徽标沿用 `NavBadge`；抽 `NavTab` 组件替代内联循环。窗口拖动区（`appTitleBarDrag`）与页签切换/徽标逻辑不变。已部署验证。 |
| 2026-09-23 | — | **自定义标题栏（undecorated 窗口）**：系统原生标题栏（浅色）与深色主题割裂，窗口改为无装饰模式，顶栏升级为自定义标题栏——整条可拖动移动窗口、双击切换最大化，右侧新增最小化/最大化（还原）/关闭按钮（图标用 Box 绘制避免字体缺字），四边与四角加隐形缩放热区（带方向光标，最小 720×480，最大化时不绘制热区）；建库页配同样的标题栏；顶栏文字与窗口/任务栏标题统一只留「Atlas」。新增 `ui/WindowChrome.kt`；导航与数据协议零改动。 |
| 2026-09-23 | — | **设置页重构（纯布局，无功能变更）**：分区由「标题+VDivider 裸排」改为卡片容器（新增 `SettingsCard`），主题/Markdown 两组单选芯片合并为通用 `ChipSelector` 组件；悬在分隔线之间的孤儿块「Markdown 展示」并入「外观与阅读」分区，「应用数据」并入「关于」；分区重排为 外观与阅读/复习/知识库（目录+题目源文档合并）/隐私边界/关于 五卡；内容限宽 900dp 居中，避免超宽屏下表单拉伸。全部设置项、保存逻辑与提示文案原样保留，测试 83/83 通过。 |
| 2026-09-23 | — | **题库支持移动题目到其他文档**：题目卡片展开后在「编辑/删除」旁新增「移动」入口，弹窗内从题库映射文档列表中选择目标文档，确认后源文档整块移除该题（复用 `SourceQuestions.remove`），目标文档末尾追加并从其最大题号之后续排（复用 `SourceQuestions.append`）；先写目标再写源（中途失败只重复不丢题），目标限定为已纳入题库映射的文档，沿用外部修改守卫；两种搜索范围下均可用。核心逻辑 `AppStore.moveSourceQuestion`，新增 store 级测试 3 项（全套 83/83 通过）。 |
| 2026-09-23 | — | **题库支持删除题目**：同源题目卡片展开后在「编辑」旁新增红色「删除」入口，确认弹窗后把该题（题面+答案整块）从源文档写回移除，剩余题目按文档顺序连续重新编号，章节标题与代码块原样保留；沿用「源文档被外部修改时拒绝覆盖并重新加载」守卫，删除入口在「当前文件/整个目录树」两种搜索范围下均可用（按题目自身 sourcePath 定位文件）。核心逻辑 `SourceQuestions.remove`，新增测试 7 项（全套 80/80 通过）。 |
| 2026-09-23 | v0.11 | **以代码为基准的全文校准**（工作区代码为唯一事实源，两轮探索逐条核对）：① 信息架构——顶部导航实为「工作台/题库」两标签，学习（闪卡复习）入口暂时收起（路由与 FSRS 代码保留）；徽标=待确认候选数/同源题目数；题库交互按同源现状改写（点击展开答案、编辑弹窗、拖拽重排、指定题号新建、双范围搜索）；② 题库——同源协议补全三种 Q 行格式与「题目源文档」设置（默认 Kotlin 语法基础文档，支持目录规则）；FR-D5 拆「现状/保留规格」（批改登记与模拟面试入口已移除、同源题目无状态字段、遗留 questions.md 通道 UI 不可达）；FR-D6 标注未实现；③ 复习与统计——FR-C3 注明评分键位接线已移除、fuzz 未关闭；FR-C4 注明仪表盘已实现未挂载、完成率为 30 天口径；④ 检索与索引——FR-A1 建库改单屏实态；FR-A2 注明 FTS 增量更新为手动重扫；FR-A3 评测集未实现、高亮为纯文本标记；FR-A4/A5/A6 按内置渲染器与纳管实态修正（无语法高亮/内链跳转/非 md 检索/技能 frontmatter 提取）；FR-A8 按识别兜底与 cardgen 提议实态改写；⑤ 协作——FR-C1 主通道改同源直写、inbox 降备用；FR-E3 任务类型收敛为 interview/cardgen；⑥ 技术栈与工程——NFR-6 修正为 sqlite-jdbc 直连 + 内置渲染器（SQLDelight/mikepenz 为原计划值）；AppCDS、GitHub Actions CI 如实标注未启用；⑦ 北极星「三者」→「两指标」；§6.2 概念（三类条目/区块无前后文窗口/题目无状态机）、§6.3 存储表、J1、M1 Gate 措辞同步。 |
| 2026-09-22 | — | **单题新建改为按位置插入**：题号允许与已有题目重复；输入位置后，新题插入该位置，原题及后续题目向后顺延并自动重新编号。 |
| 2026-09-22 | — | **题库排序模式优化**：默认保持阅读交互不变，显式开启「调整顺序」后整张题目卡片可拖动换位；拖动中的卡片悬浮、抬升并跟随指针，其他卡片实时让位；取消独立拖动点，保存后按新顺序自动重排题号并保持答案绑定。 |
| 2026-09-22 | — | **新建单个题目支持配置题号**：弹窗默认填入下一个可用序号，允许手动指定正整数并拦截与源文档已有题号冲突；批量新建继续自动连续编号。 |
| 2026-09-22 | — | **题库题目支持拖动排序**：在当前源 Markdown 且未进行搜索时，通过题目卡片左侧手柄调整题目顺序；释放后保留完整题面与答案并安全写回源文档，搜索结果不提供排序入口。 |
| 2026-09-22 | — | **Atlas UI 深度优化第一阶段**：建立统一 UI tokens（字号、行高、间距、控件高度），调整主导航选中层级与徽标尺寸，降低状态栏视觉权重；题库创建入口收进「更多」菜单，题目卡片增加轻量边界；Markdown 正文统一行高，表格支持横向阅读；不改变题库、Markdown、搜索和复习数据协议。|
| 2026-09-22 | — | **出题主通道切换为知识库同源文档（article-quiz 并入 session-to-knowledge）**：agent 侧 skill 合并——删除 `article-quiz`，`session-to-knowledge` 调用即出题，题目以 `**Qn:**` 格式直接写入 knowledge-base 文档（条目"启示"段/学习资料小节之后），由同源题目机制（SourceQuestions）直接读取，无 inbox、无同步；inbox 收件箱降为备用通道保留（代码与 FR-E1 不动），演示残留文件已清理。atlas 代码零改动；新文档题目进题库仍由设置「支持解析的文档或目录」控制。 |
| 2026-09-21 | — | **题库页主入口调整为生成题库**：移除「发起 AI 模拟面试」作为题库页主操作，改为本地从现有题库生成练习题集；弹窗支持数量、全部/指定标签范围、随机抽取，并直接预览抽取结果，不修改原题库。 |
| 2026-09-21 | — | **移除题库批改登记功能**：题目详情删除「批改」入口与批改弹窗；题面、完整答案、参考要点、标签和状态统一通过「编辑」维护；保留模拟面试、删除与转闪卡流程。 |
| 2026-09-21 | — | **题库独立顶层入口与工作台精简（§6.1 v4）**：① 顶部导航由「今日/学习/设置」调整为「工作台/学习/题库/设置」，题库集中管理题目与答案；② 保留题目点击展开后才出现编辑、删除、批改、转闪卡等操作；③ 学习页收缩为闪卡复习与卡片浏览；④ 原「今日」改名「工作台」，删除统计展开和装饰性任务块，只显示有待处理数量的行动项、候选确认和外部任务；⑤ 新增导航/工作台模型测试，更新 README 与本节信息架构。
| 2026-09-21 | — | **题库中心化：题目+完整答案、点击展开交互、派生复习（FR-D5 增强）**：题库升级为学习闭环的中心信息源。① 数据——`QuestionEntry` 新增 `answer` 字段（完整答案，存 questions.md 小节**正文**：题目=标题、答案=正文，md 直觉；旧条目正文为空照常加载，向后兼容），inbox question 候选新增 `answer` 透传（`ref` 保留为 AI 批改判分点，`answer` 为面向人的完整答案，两者都落库）；② 交互——题目条目改为**点击展开**式：收起态只有勾选框+题面+状态（列表一眼可扫），点击展开详情后才出现操作（批改/转闪卡/编辑/删除），顶部加统计行（共 N · 未测/待复测/已稳定）；编辑对话框新增答案输入；答案与要点沿用"未测/待复测不剧透"门；③ 派生复习——新增「转闪卡」：题面→卡面、答案→卡背（无答案兜底批改要点）、deck=来源，题目一键进 FSRS 复习队列（题库→复习的信息源联动）；④ 测试 10/10（新增 answer roundtrip 与转闪卡用例）；skill `article-quiz` 协议同步 answer 字段 |
| 2026-09-21 | — | **删除缺口（Gap）功能（FR-D1–D4 整体下线，D5/D6/A8 解除联动）**：用户裁决该功能无价值。① 数据与核心——`gaps.md`/GapEntry/loadGaps/saveGaps、OutboxTasks.writeFeynman/writeGapCandidate、Inbox Kind.GAP、AppStore 缺口区与费曼清结门、面试批写回机制（writebackProposals/确认入缺口）整体删除，metrics 去 gapMonth（北极星 2 移除，原 3 改 2），知识文件签名去 gaps.md；② UI——学习页缺口子页与看板、今日页缺口任务行、预览浮层/Ctrl+K「+ 缺口」、收件箱 GAP 候选、题库批改「不通过自动入缺口」联动全部移除（不通过=保持未测可重测）；③ 测试同步（批写回用例删除、inbox/outbox/E2E 用例改写），9/9 通过；④ 式样——FR-D1–D4、§1.6 闭合流程、§1.7/FR-A8 缺口候选、FR-B1 入缺口、§6.2 概念与数据行、UI 三处、J3/J5/J6、M3 同步删除或改写；历史裁决与变更记录保留 |
| 2026-09-21 | — | **知识库降级为基础设施（§6.1 v3，导航 4 标签 → 3 标签）**：动因——对 agent 重度用户，应用内检索/上下文包相对"直接问 agent"没有增量，独立知识库工作台是日常噪音。① 导航取消「知识库」标签（今日/学习/设置）：目录树/类型筛选页删除；检索收敛到 Ctrl+K 浮层（FR-B1 与 FR-B3 合并：即搜、回车打开预览、命中行「+ 缺口」、底部「复制为上下文包」，替代原"转入检索工作台"）；预览收敛为按需浮层（PreviewDialog：复习卡「跳回原文」与 Ctrl+K 命中打开时出现，带"编辑器打开/让 AI 出卡"，复盘/项目经验条目就地「提议制卡/提议缺口」，FR-A4 措辞同步）；② KnowledgeView.kt 删除，AppStore.pendingQuery（转入检索工作台机制）删除；③ 底座不变：FTS 索引、三档隐私边界（仅本地不进上下文包）、复盘/技能识别、经验流提议全部保留。学习闭环无损：跳回原文、就地出卡、检索入缺口三入口均迁移完成。测试 10/10 |
| 2026-09-21 | — | **深色主题（dark theme）**：`AtlasTheme` 接收 dark 开关接 Material3 `darkColorScheme()`；业务语义色（accent/ok/warn/bad/muted/代码块背景）收敛为 `AtlasPalette` 双套色板（深色下提亮），经 snapshot state 驱动组合期自动重组，`renderInline` 等普通函数内的取色无需改造；设置页新增「外观」分区（浅色/深色即点即生效），选择持久化到 settings.properties 的 `theme` 键。测试 10/10 通过 |
| 2026-09-21 | — | **学习动线重构（信息架构 v2，§6.1 重写）**：导航 6 标签 → 4 标签「今日 / 学习 / 知识库 / 设置」，动因：学习闭环被撕进 4 个页签、内部机制术语暴露给学习者、任务发起后无下文（outbox 无任务列表 UI）。① 新增「今日」首页（TodayView）：该学的任务清单（到期卡/缺口/未测题，点击直达学习子页）+ **进行中的 AI 任务**（outbox 任务列表首次有 UI，含复制任务路径与下一步指引；AppStore 新增 outbox 状态列表随 3s 监听刷新）+ 收件箱候选内嵌（InboxView 重构为可复用 CandidateList，FR-E1 确认工作台改挂今日页）+ 仪表盘降级为可展开明细；② 「学习」= 复习/缺口/题目三子页（LearningView 取代 PlanView；LearnView 并入为 ReviewSection，左栏 4 按钮→2，FSRS 参数移入设置，「重建复习队列」删除；「AI 批改结果待确认」从经验流页迁至题目页顶部，批写回状态化 AppStore.writebacks 随文件重载自动刷新）；③ 经验流独立页取消：复盘/项目经验的提议入口迁至知识库预览头（识别为复盘/项目经验的条目就地显示「让 AI 提议制卡/提议缺口」，FR-A8 语义不变）；④ 文案全面人话化：outbox/批写回/trigram/北极星/枚举名等内部术语不再出现在 UI（"发起模拟面试（outbox）"→"发起 AI 模拟面试"、"发起费曼(outbox)"→"让 AI 出题考我"、desired retention→目标记住率等），任务发起 toast 统一带下一步指引，重复入口收敛（暂停卡 2→1、全量重建同屏 2→1、出卡入口统一"让 AI 出卡"）；⑤ 各视图说明文字压至 ≤1 行。**不动**：md 格式、inbox/outbox 协议、FSRS、索引、AppStore 存储逻辑；测试 10/10；article-quiz skill 界面路径文案同步（今日页待确认区 / 学习→题目） |
| 2026-09-21 | — | **删除 Git 源功能（FR-A7 整体下线）**：代码删除 GitView、GitSource、OutboxTasks.writeInterpret、AppStore.startInterpret、AppSettings/SettingsStore 的 `gitRepos`，顶栏页签中「Git 源」移除（同期顶栏重构为 今日/学习/知识库/设置，outbox 徽标随旧页签结构一并取消）；式样同步删除 §1.7 第 3 路「学别人的提交」（三路改两路）、FR-A7、§5.2 风险行、FR-F1「git 学习源管理」、J6 提交学习段、M4 的 A7 与解读闭环、§1.4 提交解读示例。历史裁决与变更记录保留。另：本次同时落地全量文件日志（`~/.local/share/atlas/logs/` 按天轮换，EDT 看门狗定位卡死）与规划页重叠布局修复（Box 内未用 Column 竖排）。测试 10/10 通过 |
| 2026-09-21 | — | **删除工作台外部任务展示**：移除「进行中的外部任务」区块、任务路径复制入口及其工作台摘要项；outbox 文件协议与任务生成能力保留，由外部 agent 直接消费。同步更新 README、工作台模型与 UI。 |
| 2026-09-21 | — | **设置入口移至顶栏右侧**：普通导航保留工作台/学习/题库，设置改用右侧齿轮图标入口，并保留无障碍名称。 |
| 2026-09-21 | — | **待确认候选统一题库化**：article-quiz 与出题任务只生成 `kind: question`、`q`、`answer`；工作台统一显示题目并写入 `questions.md`。旧闪卡候选按 front→q、back→answer 兼容迁移，不再进入 cards.md。 |
| 2026-09-21 | — | **顶栏导航顺序调整**：将「题库」移到「学习」之前，顺序统一为工作台 / 题库 / 学习。 |
| 2026-09-21 | — | **题库同源文档首期框架**：题库左侧递归映射 `knowledge-base` Markdown 文档并支持切换；当前只识别 `knowledge-base/language/kotlin/01-语法基础.md` 的 `**Qn: 问题**` 结构；闪卡/学习入口暂时隐藏，旧 `questions.md` 与 `cards.md` 保留但不参与当前题库链路。 |
| 2026-09-21 | — | **文章出题通道（article-quiz）+ 题库来源维度**：① 题库 `QuestionEntry` 新增 `source` 元数据行（向后兼容，旧文件无此行照常），题库 UI 加来源筛选 chips、发起模拟面试尊重当前筛选；inbox question 候选确认透传 `source`（默认"收件箱"），手动导入题单默认来源"粘贴导入"；顺带修复收件箱"编辑候选"对 question 类型写入 `title` 而确认读 `q` 导致编辑不生效的问题。② 新增 agent 侧 skill `article-quiz`（`~/.agents/skills/article-quiz/`，已同步 Summary `skills/` 镜像）：输入文章（路径/粘贴/URL），按固定规范出题——逐 `##` 小节覆盖、总量 8–15、四层题型（事实回忆/概念理解/应用迁移/批判延伸 ≈3:4:2:1）、关键结论闪卡 3–5 张——以 inbox 候选协议写入 `<库根>/atlas/inbox/article-quiz-<slug>.md`，`source` 固定 `article-quiz: <短题>`、闪卡固定 `文章学习/<主题>` 卡组；闭环=收件箱确认→按来源筛选→模拟面试批改→缺口→复测。测试：gradle test 10/10（含新增 source 链路测试）。零模型零网络铁律不受影响（skill 运行在 agent 侧，符合 §1.4 分工） |
| 2026-09-20 | — | **v1.0 未完成项补齐 + 学习/计划深度升级**（代码 `atlas/app/`，测试 19/19，`./update.sh` 装机+冒烟通过）：① 学习——修复 Anki 键位焦点问题（FocusRequester）、U 撤销上次评分、复习完成态（数量/分布/用时）、卡片浏览（搜索/编辑/删除/暂停，cards.md 新增 `- suspended:` 行向后兼容）、卡组到期/在学/暂停统计、FSRS 参数面板（retention 查看/切换/重置）、**复习时来源锚点跳回原文**（FR-C3 差异化点落地）；② 计划——缺口编辑/确认删除（废弃 `__del__` 哨兵）、来源筛选、题库编辑/删除+**未答题参考要点延迟揭晓**（FR-D5）、**面试批写回自动提议缺口**（解析 log 中「建议入缺口」，确认门）、经验流入口落地（FR-A8：markerByDir 接线 + 复盘候选提议制卡/缺口）、仪表盘重做（真实完成率=完成/到期去重计数、近 30 天每日复习量）；③ 协作——Ctrl+K 全局搜索（FR-B3）、inbox 按文件整批忽略（FR-E1）、outbox 任务统一生成（OutboxTasks）+ 状态面板、第三方改库 ≤3s 自动重载（mtime 监听）；④ 指标——review_due 事件入日志使北极星 1 有真实分母；⑤ 工程——AGPL-3.0 LICENSE 落地、SQL 全参数化、GitSource 管道死锁修复、bootError  surfaced。偏离 PRD 之处：无（新增的撤销/监听/批量忽略均为 FR 既有条款的落实或小幅增强，未引入新依赖，零模型零网络铁律保持） |
| 2026-09-20 | — | **代码库并入 Summary 仓并清理死代码**：代码由 `~/Project/MyProject/atlas` 迁至 `Summary/atlas/app/`（纳入本仓 git 管理，构建产物不入库）；删除零引用死码约 90 行（markerByDir、stateName、dueInstant、BINARY_EXT/DEFAULT_BINARY_EXT、retrospectiveCandidates/proposeGapsFromNote、ScanStats 只写字段、Chunk.ord、tierOf 死参数、review_due 死事件），SmokeTest 同步适配；编译+8/8 测试通过。遗留待决（审查发现，未计入本次变更）：FR-A8 UI 入口缺失、北极星指标 1 缺分母事件、LearnView 焦点问题（Anki 键位）、缺口删除哨兵状态 |
| 2026-09-20 | — | **MVP 代码首版交付**：代码库 `~/Project/MyProject/atlas`（与 Summary 平级的独立仓库，未入库）。compileKotlin ✅、冒烟测试 5/5 ✅、deb 产物 ✅（build/compose/binaries/main-release/deb/）。实现范围：建库向导+三档边界、FTS5 trigram 检索+来源面板+上下文包、收发件箱确认工作台、FSRS 复习（Anki 键位）、缺口费曼清结门、题库+模拟面试状态机、经验流识别、Git 学习源、仪表盘、deb 打包。偏差（ADR 简记）：SQLDelight→sqlite-jdbc 直连；mikepenz 渲染库→内置轻量渲染器；AppImage 待 CMP-7101 修复 |
| 2026-09-19 | v0.10 | **合入技术可行性调研**（新增 `research/tech-feasibility/` 主报告+三份来源）：CMP 路线有条件可行（Wayland=XWayland、AppImage 降 P2）；FR-A3 落定 trigram+LIKE 兜底；FR-C3 落定 java-fsrs+Anki 键位+来源跳转超越点；NFR-1/4/6/7 重写（AppCDS/XWayland/具体库版本/IME 冒烟）；M0/M4 Gate 更新；风险表换 Wayland 渲染风险 |
| 2026-09-19 | v0.9 | **重大裁决：零模型零网络**（推翻 v0.2 BYOK）——删除模型配置/RAG 生成/内置嵌入，新增收发件箱协议、检索工作台、上下文包（§1.4、FR-B1/B2、E 组）；合入库审计四修订（三档边界/##锚点/D5 周练对齐/M0=Summary）；新增 J1–J6 修订版；里程碑重排 |
| 2026-09-19 | v0.8 | 经验流：§1.7、FR-A7 Git 学习源、FR-A8 复盘/项目经验识别、FR-D6 项目追问；J6；M4 扩容 |
| 2026-09-19 | v0.7 | 旗舰场景面试驱动学习：§1.6、FR-D5 题库与模拟面试、J5；前沿知识走外部输入 |
| 2026-09-19 | v0.6 | 第三支柱内容管理：FR-A5 多类型纳管、FR-A6 技能识别、J4 |
| 2026-09-19 | v0.5 | 学习复习升为第一支柱：FR-C1 四来源制卡、卡组维度、J2 重写 |
| 2026-09-19 | v0.4 | 范围裁决：不做 agent；§1.5 分工 |
| 2026-09-19 | v0.3 | 重写为详细版（每 FR 带动机/描述/边界/验收） |
| 2026-09-19 | v0.2 | 合并 Charter/PLAYBOOK/README 为单文档 |
| 2026-09-19 | v0.1 | 初稿 |
