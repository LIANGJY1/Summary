# F. 架构理解方法论调研（为"项目架构解码" skill 提炼）

调研日期：2026-09-06。目的：为用户自写"项目架构解码" skill（教 LLM agent 系统性理解/讲解一个大型代码库）提炼**可执行步骤**。
工具环境备注：本机代理故障，WebFetch/curl 不可用，全部一手来源经 web_reader（MCP）读取正文；WebSearch 用于找线索。所有 URL 均已实际访问核实，未编造。

---

## 1. C4 模型（Simon Brown）——分层的"架构望远镜"

来源（一手，均已读正文）：
- 官网首页：https://c4model.com/
- System context：https://c4model.com/diagrams/system-context
- Container：https://c4model.com/diagrams/container
- Component：https://c4model.com/diagrams/component
- （佐证）Simon Brown 的 InfoQ 文章：https://www.infoq.com/articles/C4-architecture-model/

核心思想：
官网原文："The C4 model is an easy to learn, developer friendly approach to software architecture diagramming"——用一组**层次化抽象**（software systems, containers, components, code）画架构图，像地图一样逐级放大（zoomable hierarchy）。每个官方页面固定四段结构，正好是现成的文档骨架：
- **Scope**：这张图覆盖什么（如 context 图 = 被分析系统 + 用户 + 外部依赖）
- **What it does not show**：刻意排除什么（context 图不画容器/组件内部细节）
- **Audience**：给谁看（context 图 = 所有人包括非技术；container/component 图 = 团队内技术人员）
- **Questions answered**：这层回答什么问题（context：系统是什么、谁在用、依赖谁；container：由哪些部署单元/技术栈构成、如何通信；component：容器内部由哪些构件组成、各管什么、如何协作）

转成 LLM 可执行步骤（输出架构总览时）：
1. **L1 Context**：先写出系统一句话定位 + 用户/角色 + 外部系统依赖（从 README、配置文件、环境变量、API client 代码里找证据）。
2. **L2 Container**：列出部署/运行单元（service、web 前端、DB、消息队列等），标注技术栈和相互通信方式（证据：compose/dockerfile、构建脚本、启动入口、端口配置）。
3. **L3 Component**：对 1-3 个关键容器，列出内部主要模块/包，各配一句话职责 + 依赖方向（证据：目录结构、import/依赖图）。
4. 每层都按官方四段式写：scope / 不包含什么 / 读者 / 回答的问题；并注明证据来源（哪个文件哪行）。
5. 显式声明"L4 Code 层不展开，按需放大"——C4 本来就允许逐级取舍。

对 skill 环节：**结构图（主骨架）**。C4 官方页面的 Scope/Audience/Does-not-show/Questions 四段式可直接当 skill 的输出模板；"每层用什么证据文件"对照表是 LLM 可执行的检索清单。

---

## 2. arc42（Dr. Gernot Starke / Peter Hruschka）——架构文档的 12 章模板与"非功能信息"清单

来源（一手，均已读正文）：
- 12 章总览：https://arc42.org/overview
- §2 约束：https://docs.arc42.org/section-2/
- §3 上下文与范围：https://docs.arc42.org/section-3/
- §9 架构决策：https://docs.arc42.org/section-9/
- §10 质量需求：https://docs.arc42.org/section-10/

核心思想：
arc42 把架构文档固定为 12 章：1 需求与目标 → 2 约束 → 3 上下文与范围 → 4 构建原则（策略）→ 5 系统概览（黑盒）→ 6 运行视图 → 7 部署视图 → 8 横切概念 → 9 架构决策 → 10 质量需求 → 11 风险与技术债 → 12 术语表。对"解码"最有价值的是它对**约束/质量/决策**的处理：
- §2 约束原文定义："Any requirement that constrains software architects in their freedom of design and implementation decisions"——分**组织约束**（流程、预算、团队）、**技术约束**（语言、框架、平台版本）、**隐式/来自环境的约束**。
- §9 架构决策章节内容为"重要、昂贵或巨大的架构相关决策，含动机、备选方案、决定与后果"，practical tips 指向用 ADR 记录更细粒度的决策（与第 5 节衔接）。
- §10 质量需求：用**质量场景**（scenario：具体情境 → 期望响应 → 可度量指标）把"高性能/高可用"这类空话具体化；模板自带 scenario 示例表。
- §3 上下文：分业务上下文（输入/输出/邻居系统）与技术上下文（通道、协议、格式）。

转成 LLM 可执行步骤（解读动机时）：
1. 按 §2 三类约束去代码库找证据：组织类（CI 配置、CODEOWNERS、发布脚本）、技术类（锁文件、CI matrix、Dockerfile 基镜像）、隐式类（合规/性能相关代码注释）。
2. 按 §3 画系统上下文：找出所有 inbound/outbound 集成点（网络端口、消息 topic、外部 API client）。
3. 按 §10 的场景三元组改写每条发现的"质量设计动机"：**什么情境下 → 系统应如何响应 → 怎么度量**，逼自己把"它这么设计是为了 X"落到可检验的表述。
4. 按 §11/§12 收尾：列出读代码时发现的风险/技术债线索（TODO、HACK、版本注释），建术语表（项目黑话 → 标准含义）。

对 skill 环节：**动机考据的检查表**。§2/§9/§10 告诉 skill "哪些非功能信息值得写、写成什么样"；12 章可裁剪为解码文档的目录。

---

## 3. CRC 卡（Ward Cunningham & Kent Beck）——类职责卡

来源（一手，全文已读）：
- 原始论文：Kent Beck & Ward Cunningham, "A Laboratory For Teaching Object-Oriented Thinking", OOPSLA'89：https://c2.com/doc/oopsla89/paper.html
- 社区权威页（c2 wiki，页面为 JS 门禁，正文未能抓取，仅作指针）：https://wiki.c2.com/?CrcCard

核心思想：
1989 年 Beck/Cunningham 为教 OOP 发明的"索引卡片法"：**每个类一张卡片**，卡上只写类名、职责（responsibilities）、协作者（collaborators）。原文反复强调"物性"的价值：
- 卡片是给**人**的而非机器的："preference for a paper-doll simulation over computer-based"（倾向纸片模拟而非上机）。
- 一组人**角色扮演程序的运行时**：读一个场景（如"用户按了取消"），轮到某个类的职责被触发时，"扮演"该卡的人描述自己怎么响应、需要请哪个协作者代劳，把控制权"传递"过去。原文："A resolution of the problem is sought... specification is viewed from the user's point of view"（从用户视角出发推演）。
- 卡片小是特性不是缺陷：逼职责写成短语级粒度；画不出卡片的模块说明职责不清。
- 教学上"吝啬步骤说明"："We are sparing in giving detailed procedural instructions to the student"——让推演过程本身当镜子。

转成 LLM 可执行步骤（给关键模块做"类职责卡"）：
1. 选出 5-15 个关键类/模块（入口类、核心服务、被最多引用的抽象）。
2. 每个模块一张卡，严格三栏：**职责**（一句话，动词开头，从公开方法签名/文档注释归纳）＋ **协作者**（它调用了谁，从 import 与方法体里的依赖调用取证据）＋ **证据**（文件:行号）。
3. 挑 1-2 条主链路做"纸上运行"：从一个用户操作/请求入口出发，按卡片顺序模拟控制权传递，写出"卡 A → 卡 B → 卡 C"的剧本，暴露职责缺口与意外耦合。
4. 对推演中"说不清职责"的卡片，标记为架构疑点（进风险清单）。

对 skill 环节：**类职责卡（直接产出物）**。三栏卡格式 + 控制权传递剧本，是 LLM 能逐字执行、且天然防空话（必须落到文件:行号）的输出形式。

---

## 4. Reflexion Models（Gail C. Murphy, David Notkin, Kevin Sullivan）——"假设架构 vs 真实依赖"的对照验证

来源（一手/权威转述，均已核实）：
- 论文页（含摘要）：http://www.cs.ubc.ca/~murphy/papers/rm/fse95.html （FSE/SIGSOFT 1995）
- ACM DL：https://dl.acm.org/doi/10.1145/222132.222136 ——摘要原句："This paper introduces the notion of a software reflexion model, which allows an engineer to compare a high-level model with the source code of a system"
- 作者实验室项目页（UBC SPL）：https://www.cs.ubc.ca/labs/spl/projects/reflexion.html ——原句："The software reflexion model technique enables a software developer to summarize interactions in a large body of source code in terms of a selected high-level model"
- 案例研究（Computer'97，Trillium 电话交换系统，VUB 课程材料镜像 PDF）：ftp://prog.vub.ac.be/education/EMOOSE/EMOOSE_OOSA_00_01/Extra%20Course%20Material/Session%205%20-%20LightweightArchitecturalTools/paper_rm_case_study_computer97_murphy.pdf ——原句："the next steps in our technique are to extract a model of the source, define a map, and, through a set of computation tools, compare the two models"
- TSE 2001 期刊版（"iteratively performing five basic steps"）：https://www.computer.org/csdl/journal/ts/2001/04/e0364/13rRUIIVleg

核心思想：
工程师先写一个**假设的高层模型**（框 + 命名的交互边），再从源码**机械地抽取**实体与依赖（当时用 grep 脚本即可），写**映射规则**把源码实体归到高层框上，工具对比两套模型产出 reflexion model：每条交互标为 **convergent**（假设中有、源码印证）/ **divergent**（假设与源码冲突）/ **absent**（源码里有、假设里没有）。便宜、抗"文档腐烂"，因为它承认模型是假设、用源码当裁判。

转成 LLM 可执行步骤（验证防编造核心流程）：
1. **提假设**：读完入口/文档后，先写出假设架构图（模块框 + 命名依赖边，如"ui→core、core→storage"），明确标注"这是猜测"。
2. **抽真实依赖**：用可机械化手段取证——grep/ripgrep import、目录级依赖统计、依赖图工具（如 dependency-cruiser/jdeps），生成真实交互清单。
3. **写映射**：每条真实依赖归到假设图的某条边或"图外"。
4. **出三色报告**：逐边标 convergent（互相印证，写进最终文档）/ divergent（假设错了，修正文档并记下为什么）/ absent（文档漏掉的真实依赖，通常是隐藏耦合，重点讲解）。
5. 迭代一轮后输出，每条结论都带"证据：来自哪个文件的哪些 import/调用"。

对 skill 环节：**验证防编造（最关键的一环）**。它把"AI 幻觉架构图"问题变成可判定流程：先假设、后取证、三色标注、缺席项显式列出。

---

## 5. ADR（Michael Nygard；adr.github.io）——设计动机的载体与考据入口

来源（一手，全文已读）：
- Michael Nygard 原文（2011）：https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions
- adr.github.io：https://adr.github.io/
- Joel Parker Henderson 的 ADR 参考仓库：https://github.com/joelparkerhenderson/architecture-decision-record

核心思想：
Nygard 原文要点：敏捷项目的架构决策是**陆续**做出的，"Not all decisions will be made at once"；每条决策记录成一个**短文本文件**，编号顺序存放（如 `doc/arch/dec-001`），**不可修改、只能被后续 ADR 取代**（"ADRs will be numbered sequentially and will not be altered"，新决策 supersede 旧决策）。固定六段结构：**Title / Date / Status / Context / Decision / Consequences**。adr.github.io 的定义："An Architectural Decision (AD) is a justified design choice that addresses a functional or non-functional requirement that is architecturally significant"——关键词是 **justified（有理由）**。JPH 仓库给出标准文件名样例 `doc/adr/0001-record-architecture-decisions.md`。

对"README 里没有的动机去哪找"：
1. 仓库内顺序检查：`docs/adr|decisions|rfcs|design/` 目录、DESIGN.md/ARCHITECTURE.md、CHANGELOG、`docs/` 下的 RFC；再查 PR 描述与 issue 讨论（`git log --merges` 的 PR 号 → GH API）、邮件列表/论坛链接（commit message 里的 See #n / 链接）。
2. 没有 ADR 时，**用 git 考据（见第 7 节）逆向补写 ADR**：以 Nygard 六段格式记录，Context 标注"重建自 git 历史，可能不完整"。

转成 LLM 可执行步骤：
1. 读全部现存 ADR/RFC，按 Status 串成决策链（哪些被取代，最新结论是什么）。
2. 对每条关键决策提取：决策内容、Context 里的压力因素、被否决的备选方案（这是讲解设计时最有信息量的部分）。
3. 对"无文档的重要结构"，按六段格式逆向补写 ADR，逐段标注证据等级（直接引用 / git 推断 / 纯推测——推测必须显式标出）。
4. 输出"决策时间线"：把关键决策与其日期、对应提交关联，展示架构是怎么演化成现在的样子。

对 skill 环节：**动机考据（主框架）**。Nygard 六段格式是"逆向补写 ADR"的现成模板；"Status superseded 链"教 agent 别引用过时结论。

---

## 6. 工程师上手大型代码库的经验文章（4 篇 + 1 个学术框架，均核实）

### 6.1 Amber Wilson（前端工程师）"How to approach a new codebase"
URL：https://amberwilson.co.uk/blog/how-to-approach-a-new-codebase/
要点：先拿**高层概览**再下代码；学会用编辑器的导航能力（跳转定义、找引用、全局搜索）代替线性阅读；**借具体任务学习**（挑一个 bug/小需求，把它当作穿透代码库的透镜——"learning by example"）。对 skill：产出"第一周可执行任务清单"（跑起来 → 修一个小 bug → 顺藤摸瓜记笔记）。

### 6.2 Codecov "Learning a Codebase Using Tests"
URL：https://about.codecov.io/blog/learning-a-codebase-using-tests/
要点：测试套件是**最好的上手入口**——"Tests that are run in a CI environment represent actual code paths"（CI 里跑的测试代表真实代码路径）；测试展示了作者**想保证什么**、API 的预期用法与边界条件。对 skill：把"读测试归纳行为规范"设为固定步骤（挑核心模块的测试文件，用 Given/When/Then 列出行为表）。

### 6.3 Diomidis Spinellis《Code Reading: The Open Source Perspective》
URL（作者官方页）：https://www.spinellis.gr/sw/codeReading/
要点：读代码本身是一项需要工具支撑的工程活动（作者页原话：reading code is an important software engineering activity）。方法论：自顶向下（从需求/入口猜结构）与自底向上（从叶子函数归纳）交替；用 grep、编译器告警、调试器当导航仪；边读边画图记笔记。对 skill：为"阅读策略"章节背书，避免 skill 只教一种读法。

### 6.4 Increment（Stripe 出品）Onboarding 专题（Issue 07, 2018）
URL：https://increment.com/onboarding/
要点：大型团队视角的 onboarding 方法论合集（本次核实到专题目录页，文章正文未逐篇抓取，作延伸阅读指针）。对 skill：佐证"先跑起来、先交付一个小改动"是行业共识做法。

### 6.5 （理论框架）Peter Naur "Programming as Theory Building"（1985）
URL（UW 镜像 PDF）：https://pages.cs.wisc.edu/~remzi/Naur.pdf ；另一镜像 https://gwern.net/doc/cs/algorithm/1985-naur.pdf
要点：程序的本质不是源码和文档，而是**构建者头脑中的理论（theory）**；文档再全也无法完整传递 theory，所以"脱离原始语境的代码库难以维护"。对 skill 的启示：解码文档要诚实地分层——能从代码重建的（结构/行为）、能从历史重建的（动机/时间线）、**重建不了的（原作者的 theory，只能标注为开放问题**，防 agent 装懂）。

（另：Medium 热文 https://medium.com/@himanshusingour7/how-to-understand-a-large-codebase-without-losing-your-mind-6ca334a643f6 主张"最大的错误是一上来就读代码，先建上下文"，可作大众向佐证，非一手来源。）

---

## 7. git 考据技巧——从 log/blame 归因设计动机

来源（均已核实正文或为官方文档/可信工程博客）：
- John Firebaugh "Code Archaeology with Git"（2012，全文已读）：https://jfire.io/blog/2012/03/07/code-archaeology-with-git/ ——开篇即考古隐喻："Have you ever dug through the commit history of an open source project, peeling away layers, sifting for clues, trying to answer the question..."；文中给出：`git blame -w`（忽略纯空白改动，找真正改逻辑的提交）、pickaxe `git log -S"符号"` / `git log -G"正则"`（定位"这个符号/这段逻辑是哪个提交引入的"）、`git log -L <起>,<止>:<文件>`（盯着某几行的完整演化史）。
- Foojay "Git Archeology: Removing the Sands of Time from Code"：https://foojay.io/today/git-archeology/ ——"逐层铲沙挖土"重建原始意图，实操向教程。
- CloudBees "Git Blame Explained"（blame vs log 分工教程）：https://www.cloudbees.com/blog/git-blame-explained
- 官方手册（flag 语义以文档为准）：https://git-scm.com/docs/git-blame 、https://git-scm.com/docs/git-log
- HN 实践讨论（"git blaming is really misunderstood"）：https://news.ycombinator.com/item?id=43405644

转成 LLM 可执行步骤（动机归因流水线，全部命令可直接执行）：
1. **行级归因**：`git blame -w <file>` 锁定关键行的引入提交；若命中的是无意义小改动，跳到其前一次实质提交再 blame（Firebaugh 法）。
2. **符号溯源**：`git log --oneline -S"<关键符号/字符串>"` 与 `-G<regex>` 找"它是被哪个提交、为了什么引入的"；加 `--reverse` 看首次出现。
3. **函数/行段演化史**：`git log -L /funcName/,/^}:<file>` 或 `-L <start>,<end>:<file>`，读该逻辑块的历次变更说明。
4. **模块级归属与权威**：`git shortlog -sn -- <dir>`（谁最懂这个模块）、`git log --since="..." -- <dir> --oneline`（近期活跃度）；重命名跟踪用 `git log --follow -p -- <path>`。
5. **动机提取**：对命中提交读完整 message（`git show <sha>`），并看它关联的 issue/PR（message 里的 #n、See also）；把结论写成 Nygard 六段 ADR，标注证据等级。
6. 文件诞生即动机：`git log --diff-filter=A -- <path>` 找"这个文件为什么被创建"（初始提交 message 常含设计意图）。

对 skill 环节：**动机考据（执行手册）** + 为"验证防编造"提供第二裁判（结构结论要能与提交历史对上：一个声称"为性能而生"的缓存层，历史里应该能找到性能相关的提交/issue）。

---

## 8. 组装建议：映射到 skill 的四个环节

| skill 环节 | 用什么方法 | 产出物 |
|---|---|---|
| 结构图 | C4 四层 + 官方四段式模板（§1）；arc42 §3/§5 概览章节 | L1/L2/L3 架构图 + scope/读者/不包含什么 |
| 类职责卡 | CRC 三栏卡 + 纸上运行剧本（§3） | 每关键模块一张职责卡 + 主链路控制权传递剧本 |
| 动机考据 | ADR 决策链（§5）+ arc42 §2/§9/§10 清单（§2）+ git 考据六步（§7） | 决策时间线 + 逆向补写的 ADR（含证据等级） |
| 验证防编造 | Reflexion 五步三色报告（§4）；测试作行为规范（§6.2）；Naur"不可重建理论"诚实标注（§6.5） | convergent/divergent/absent 对照表 + "无法重建的开放问题"清单 |

另两条通用纪律（来自 §6 多篇文章共识，供 skill 直接引用）：
- **先跑起来再读**：能构建、能跑测试、能命中一次主链路断点，胜过读十遍代码（Wilson；Increment 专题共识）。
- **借任务穿针引线**：挑一个小 bug/小功能，让一条真实需求把你需要理解的 20% 代码串出来，而不是追求 100% 覆盖（Wilson"learning by example"）。
