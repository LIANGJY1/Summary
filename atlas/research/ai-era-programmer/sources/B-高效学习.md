# B｜AI 时代程序员如何高效学习：GitHub 高质量资源与有据方法论调研

- **调研日期**：2026-09-19
- **读者画像**：中国资深开发者（Android / 车机 / 嵌入式），重度使用 AI 编码代理，已自建 markdown 个人知识库（Zettelkasten 条目 + 费曼输出 + 复盘流），要"能直接照着用"的东西。
- **核实方式**：
  - star 数与最近活跃：shields.io GitHub badge JSON（`/github/stars/*.json`、`/github/last-commit/*.json`，当日匿名 api.github.com 限额已耗尽，shields 直接读 GitHub 实时数据）+ api.github.com search 接口（用于 AI tutor / 闪卡类甄别）。star 为当日快照，"最近提交"为 shields 月份级粒度。
  - 内容质量：逐一抓取仓库页渲染后的 README 正文**亲读**（本机 raw.githubusercontent.com 直连超时，改走 github.com 网页提取与 web_reader 通道）。
  - 官方公告与一手出处：web_reader 直读 openai.com、swyx.io、learninpublic.org；WebFetch 读 teachyourselfcs.com、notes.andymatuschak.org；WebSearch 交叉核对 Study Mode / Learning Mode 的报道。
  - 已亲读 README 的仓库：project-based-learning、build-your-own-x、hello-algo、fucking-algorithm、self-llm、JavaGuide、doocs/advanced-java、CS-Notes、ossu/computer-science、developer-roadmap、free-programming-books、fsrs4anki、obsidian-spaced-repetition、simonw/til、learn-anything、Mr.-Ranedeer-AI-Tutor、raine/anki-llm、AnkiAIUtils、anki、exercism/cli、TeachYourselfCS-CN。
- **入选原则**：活跃维护、口碑与数据可验证、对"AI 时代 + 已有知识库的资深开发者"有直接可用性；宁缺毋滥，共 19 条正式条目 + 避坑清单。

---

## 一、路线图与体系化课程（定知识域）

### 1. developer-roadmap
- **URL**：https://github.com/kamranahmedse/developer-roadmap ｜ https://roadmap.sh
- **数据**：约 368k star，最近提交 1 天前（调研当日仍活跃，全表最活跃之一）。
- **定位**：社区驱动的交互式技术路线图，点开节点即见该主题的讲解与资源。
- **质量评价（亲读）**：README 即全部路线图索引。2026 年的图已明显 AI 化：AI Engineer、AI Product Builder、**Claude Code Roadmap**、**Vibe Coding Roadmap**、Leetcode、Forward Deployed Engineer 等新图在列——它不只是"前端后端"老三样，而是把"AI 时代开发者要学什么"产品化了。
- **适合谁/怎么用**：用某张图（如 AI Engineer 或 Claude Code）做**知识域 gap 自查清单**：逐节点自评"能讲清楚吗"，讲不清的节点进复习队列。别当课程刷，当地图用。

### 2. ossu/computer-science
- **URL**：https://github.com/ossu/computer-science
- **数据**：约 209k star，最近提交 2026-07（维护中，节奏放缓但未弃）。
- **定位**：免费自学 CS 的完整本科课纲（Open Source Society University）。
- **质量评价（亲读）**：README 写明选课硬标准——开放注册、周期开课、教学质量高、对齐 ACM/IEEE-CS 2013 本科课程指南；定位是"完整 CS 教育"而非求职速成，附全球学习者社区。
- **适合谁/怎么用**：资深开发者不必全修，只挑 Core CS 里自己的断层（比如补 OS / 网络）。和 TeachYourselfCS 二选一即可，OSSU 更"课表化"。

### 3. EbookFoundation/free-programming-books
- **URL**：https://github.com/EbookFoundation/free-programming-books
- **数据**：约 397k star，最近提交为最近一周内。
- **定位**：多语言免费编程书/资源总单，GitHub 最老牌书单。
- **质量评价（亲读）**：由非营利 Free Ebook Foundation 接管运营，按语言（含中文）与主题分组，有检索站点，持续有人工维护。
- **适合谁/怎么用**：查阅式书架，不是学习路径。需要某主题的免费正版书时来查。

### 4. Teach Yourself Computer Science（Oz Nova & Myles Byrne）＋ 中文版仓库
- **URL**：https://teachyourselfcs.com ｜ 中文翻译仓库：https://github.com/izackwu/TeachYourselfCS-CN
- **数据**：英文原版是网站而非仓库（注意：GitHub 上搜到的都是第三方翻译）；CN 仓库约 22k star，2023 年后停更（对应 2020 稳定版，内容并未失效）。
- **定位**：9 个科目、每科 100–200 小时的自学 CS 精读指南，基于教 1000+ 自学工程师的经验。
- **质量评价（亲读官网）**：书单极克制：SICP、CS:APP、Skiena 算法、MIT 6.042 数学、OSTEP、自顶向下网络、Berkeley CS186、Crafting Interpreters、DDIA。官方明确给出降载建议：**时间有限就先啃 CS:APP + DDIA 两本**。
- **适合谁/怎么用**：车机/嵌入式背景的人 CSAPP 优先级天然最高（内存、并发、机器层），可直接照它列的"书+公开课"组合执行；这是少数"少而精"的权威课纲。

## 二、项目驱动学习（怎么练）

### 5. practical-tutorials/project-based-learning
- **URL**：https://github.com/practical-tutorials/project-based-learning
- **数据**：约 284k star，最近提交上周（README 挂有 "Link rot sweep" 的 GitHub Actions 徽章，自动清理死链，维护认真）。
- **定位**：按语言分组的"做项目学 X"教程清单。注意：这是 tuvtran 旧仓库迁移后的现地址（旧链接自动重定向，收藏请用新地址）。
- **质量评价（亲读）**：覆盖语言极广，C/C++ 分区尤其深——写解释器、写 OS、TCP/IP 栈、JIT、光栅化/光追等硬核指南，非常契合嵌入式/系统方向。
- **适合谁/怎么用**：从中挑一个与本职咬合的"自造"项目（如 mini shell、软件渲染器、RAG 引擎），把教程当路书而非答案，坚持先写再对照。

### 6. codecrafters-io/build-your-own-x
- **URL**：https://github.com/codecrafters-io/build-your-own-x
- **数据**：约 548k star（本表最高），最近提交 2026-07。
- **定位**："从零重造你喜欢的技术"分步指南大全，README 开篇即费曼名言 "What I cannot create, I do not understand"。
- **质量评价（亲读）**：30 类技术各附多篇高质量逐步指南；已含 **Build your own AI Model**（Python 从零 LLM、Diffusion、RAG），说明清单随 AI 演进更新。同名 CodeCrafters 平台是商业化实训（按 track 付费，有免费额度），仓库本身完全免费。
- **适合谁/怎么用**："自造 X"是成人版刻意练习的最佳载体（见文末闭环第 1–2 步）。资深开发者建议选比自己日常领域低一层的组件（数据库、网络栈、容器）来造。

## 三、中文社区（中文母语学习线）

### 7. krahets/hello-algo（《Hello 算法》）
- **URL**：https://github.com/krahets/hello-algo
- **数据**：约 130k star，最近提交 2026-07（持续更新，多语言翻译进行中）。
- **定位**：开源免费、动画图解的数据结构与算法入门书。
- **质量评价（亲读）**：全书动画图解 + 12 门语言代码一键运行；README 挂清华大学邓俊辉与李沐的推荐语；强调读者评论区互助。内容工程化、零门槛，是中文算法入门事实标准。
- **适合谁/怎么用**：算法回炉或查数据结构实现细节时用它；每章代码可跑，适合当"检索练习"的题源。

### 8. labuladong/fucking-algorithm（现名"labuladong 的算法笔记"）
- **URL**：https://github.com/labuladong/fucking-algorithm ｜ https://labuladong.online/algo/
- **数据**：约 136k star，最近提交 2026-02。
- **定位**：以 60+ 篇 LeetCode"算法思维框架"文章起家，现长成带可视化面板的学习站。
- **质量评价（亲读）**：README 强调"刷的是思维不是答案"、框架化举一反三；现已配算法可视化面板、Chrome/vscode/JetBrains 刷题插件、**AI 助教随时答疑**——是国内较早把 AI 融进刷题流的。注意：重心已转向 labuladong.online（部分内容付费会员），GitHub 仓库带导流属性，但核心免费文章仍完整。
- **适合谁/怎么用**：刷题思路卡壳时按框架文定位题型；预算有限只用免费部分也够。

### 9. datawhalechina/self-llm（开源大模型食用指南）
- **URL**：https://github.com/datawhalechina/self-llm
- **数据**：约 32k star，最近提交 2026-09（datawhale 系 AI 线里最活跃的之一）。
- **定位**：面向国内学习者的开源 LLM 全流程中文教程：环境配置 → 本地部署 → 应用 → 高效微调。
- **质量评价（亲读）**：README 明确给出学习顺序建议（先配置、再部署、后微调），推荐 Qwen/InternLM/MiniCPM 等国产模型上手；并系统导流姊妹项目 Happy-LLM（原理+从零训练）、Tiny-Universe（不调 API 手写 RAG/Agent/Eval）。体系化程度高、更新跟模型代际走。
- **适合谁/怎么用**：要把"懂 LLM"落到手上跑的人照着顺序做即可。注意同组织的 prompt-engineering-for-developers 与 llm-cookbook（吴恩达课汉化，各约 25k star）**2025 年中后更新放缓**，课程是 2023–24 代际，模型细节以 self-llm 为准。

### 10. Snailclimb/JavaGuide
- **URL**：https://github.com/Snailclimb/JavaGuide
- **数据**：约 159k star，最近提交 2026-09（活跃）。
- **定位**：Java 后端面试知识体系，中文社区最头部之一。
- **质量评价（亲读）**：主站 javaguide.cn 体验更好；README 显示其 AI 化动作很快：新开 **AIGuide**（面向后端的 LLM/Agent/RAG/MCP/Claude Code/Codex 实战与面试指南）和基于 Spring AI 的实战项目。定位仍是面试导向。
- **适合谁/怎么用**：面试准备期用它查漏；不面试时可把"Java 后端面试通关计划"当知识体系自测表。

## 四、学习科学工具化（把方法变成软件）

### 11. ankitects/anki
- **URL**：https://github.com/ankitects/anki
- **数据**：约 31.1k star，最新 release 26.09.2（2026-09-15）、最近提交 2026-09-17，开发极活跃。
- **定位**：间隔重复（SRS）记忆软件的事实标准，开源、全平台。
- **质量评价（亲读）**：README 自述 "a smart spaced repetition flashcard program"；仓库构建配置里直接依赖 **fsrs 6.6.2**，印证新版 Anki 已把 FSRS 作为内建调度算法；仓库甚至维护着 AGENTS.md/CLAUDE.md——用 AI 代理开发的实践者。
- **适合谁/怎么用**：自建知识库的人把"必须刻进长期记忆的最小集"（快捷键、协议字段、API 签名、概念卡）放进 Anki，其余留在 markdown。23.10+ 版本开箱即用 FSRS，无需折腾插件。

### 12. open-spaced-repetition/fsrs4anki（FSRS 算法族）
- **URL**：https://github.com/open-spaced-repetition/fsrs4anki ｜ 实现：py-fsrs（约 491 star）、ts-fsrs（约 793 star，2026-08/09 均有提交）
- **定位**：FSRS（Free Spaced Repetition Scheduler）：开源、数据驱动的现代间隔重复调度算法；optimizer 用机器学习从你的复习历史拟合个人记忆参数。
- **质量评价（亲读）**：README 写明算法承自 Maimemo 的两篇公开论文（随机最短路调度、记忆动力学建模），"数据驱动、兼顾可解释与可验证"；调度器 + 优化器两件套，Anki 23.10+ 已原生集成，旧版才有配置成本。
- **适合谁/怎么用**：想给自建系统/知识库接入 SRS 的开发者，直接用 py-fsrs / ts-fsrs 库实现调度，不必碰 Anki。这是"间隔重复方法论"目前最先进且开源的实现。

### 13. st3v3nmw/obsidian-spaced-repetition
- **URL**：https://github.com/st3v3nmw/obsidian-spaced-repetition
- **数据**：约 2.6k star，最近提交 2026-07/08。
- **定位**：在 Obsidian 的 markdown 笔记里直接写闪卡，并用 FSRS 或 SM-2 排期复习。
- **质量评价（亲读）**：语法贴近笔记原生——单行 `Question::Answer`、多行 `?` 分隔、`==高亮==` 挖空，支持代码高亮/LaTeX/图片，还能把整篇笔记设为"到期待复习"。汉化在内多语言齐全。
- **适合谁/怎么用**：与"markdown 个人知识库"工作流零摩擦：在已有 Zettelkasten 条目里就地加闪卡，复习时点开原笔记上下文。是知识库与 SRS 结合成本最低的方案。

### 14. raine/anki-llm
- **URL**：https://github.com/raine/anki-llm
- **数据**：约 301 star，最近提交 2026-09-09（活跃）。
- **定位**：用 LLM 批量生成、批改、增强 Anki 闪卡的可脚本化 CLI + 交互式 TUI。
- **质量评价（亲读）**：当前"LLM→闪卡"工具里工程完成度最高：支持 OpenAI/Gemini/DeepSeek/xAI/OpenRouter/Ollama 及任意 OpenAI 兼容端点；TUI 里并排比较多张候选卡、查重、预览、快照回滚；prompt、模板、配置全部落在可版本化的普通文件里（README 明说可用编码代理编辑模板）；附 TTS 批量填音频。HN/Reddit 反馈积极。
- **适合谁/怎么用**：把每轮学完的主题喂给它批量产卡 → 人工在 TUI 里筛选 → 入 Anki。**人工筛选这一步不可省**（LLM 生成的卡需要质量闸门）。

### 15. thiswillbeyourgithub/AnkiAIUtils
- **URL**：https://github.com/thiswillbeyourgithub/AnkiAIUtils
- **数据**：约 880 star，最近提交 2026-06。
- **定位**：对你**答错**的卡自动增强：补 LLM 解释、插图、助记术。
- **质量评价（亲读）**：作者为医学生出身、在医学院实测（记忆负载最重的场景）；设计尊重用户自建助记（可结合 major system）；文档由 aider 辅助撰写、作者自述发布仓促，属"高可用但有毛边"的个人作品。
- **适合谁/怎么用**：重度 Anki 用户的 AI 增强层：失败触发解释，恰好对应"检索失败后立即反馈"的强化时机。新用户先别上，跑顺基础 SRS 再加。

## 五、AI 辅助学习（重点增量）

### 16. 官方 AI 学习模式：OpenAI ChatGPT Study Mode ＆ Anthropic Claude Learning Mode
- **URL**：https://openai.com/index/chatgpt-study-mode/ ｜ https://www.anthropic.com/news (Claude for Education)
- **数据/事实**：Study Mode 由 OpenAI 于 2025-07-29 发布（官方公告 + CNBC/VentureBeat/Ars Technica 多源交叉核实）：以定制 system prompts 实现苏格拉底式引导——先问你"已经知道什么、卡在哪"，分步引导、知识自检，不直接给答案；Free/Plus/Pro/Team 可用。Anthropic 于 2025-04-02 发布 Claude for Education 的 Learning Mode（同为苏格拉底式反问），2025-08 起延伸到编程教学场景。
- **质量评价**：不是开源仓库，但这是"用 AI 学习"最重要的产品化增量：两大厂商不约而同把默认行为从"给答案"改成"逼检索"。机制上与你已在用的编码代理互补——**写代码用代理，学概念时切到学习模式**。
- **适合谁/怎么用**：自建系统提示词即可复刻：给代理立规矩——"只提问、分步引导、先摸底、不直接给完整答案、最后出 2–3 道自测题"。

### 17. simonw/til
- **URL**：https://github.com/simonw/til ｜ https://til.simonwillison.net
- **数据**：约 1.5k star，最近提交 2026-09（活跃）。
- **定位**：Simon Willison 的 Today-I-Learned 公开学习仓库，582 条微笔记。
- **质量评价（亲读）**：每条 TIL 是一个带日期的小 markdown 文件，GitHub Actions 自动构建成可搜索站点 + Atom feed；自述灵感来自 jbranchaud/til。这就是 learning-in-public 的最小工程化模板：**学到一条 → 写一条 md → push → 自动发布**，与既有 markdown 知识库可共用一套工具链。
- **适合谁/怎么用**：直接 fork 其目录结构与发布 workflow，把自己的"费曼输出"公开化。写不出一条 TIL = 没学懂，这本身就是检验器。

## 六、方法论一手出处（证据链）

### 18. swyx《Learning in Public》
- **URL**：https://learninpublic.org ｜ https://gist.github.com/sw-yx/9ff4d46359b0b8ca9077 ｜ https://swyx.io/learn-in-public
- **定位**：learning in public 的原始提出者（2017 gist，后成其标志性主张）。
- **核实（读官方向导页与文章）**：核心主张是"step 1: learn in public"——把学到的东西写成博客/视频/帖子发布，并主动要求纠正；"You are what you learn"。公开学习同时解决反馈回路、问责与职业资本三个问题。simonw/til 是它的最小实现。
- **用法**：不需要项目，只需要一个公开目录 + 一个发布自动化。

### 19. Andy Matuschak《Evergreen notes》
- **URL**：https://notes.andymatuschak.org/Evergreen_notes
- **定位**：常青笔记的原始定义页（Zettelkasten 的现代学术化版本）。
- **核实（亲读）**：笔记应"随时间演化、贡献并跨项目积累"，目标是更好的思考而非存摘录。五条性质：**原子性**（一条一义）、**概念导向**（按想法而非来源/日期组织）、**密集链接**、**联想本体优于层级目录**、默认为自己而写。
- **用法**：作为既有 Zettelkasten 库的"质检标准"：定期抽查条目是否满足五性质，不满足的改写而非新增。

### 20. 文献证据小节（检索练习 / 间隔重复 / 刻意练习）
不占条目数，但建议引用一手出处而非二手转述：
- **检索练习（测试效应）**：Roediger & Karpicke (2006), *Test-Enhanced Learning*, Psychological Science, doi:10.1111/j.1467-9280.2006.01693.x——检索比重读更能促进长期保持；Karpicke & Blunt (2011), *Science* 331:772–775——检索练习优于概念图精细加工。这是"让 AI 考你而不是讲给你听"的依据。
- **间隔重复**：Cepeda et al. (2006), *Distributed practice in verbal recall tasks*, Psychological Bulletin 132(3):354–380——分布式练习的元分析证据。这是 FSRS/Anki 的方法论地基。
- **刻意练习**：Ericsson, Krampe & Tesch-Römer (1993), *The Role of Deliberate Practice in the Acquisition of Expert Performance*, Psychological Review 100(3):363–406——专家绩效来自在能力边缘进行、有即时反馈的重复训练，而非经验年限。"自造 X + 测试反馈"即此结构。
- **教学法总评**：Dunlosky et al. (2013), *Improving Students' Learning With Effective Learning Techniques*, PSPI 14(1):4–58——十种常用技术中，**练习测试与分布式练习**效用等级最高；精读标注、摘要复述等流行动作效用反而低。

---

## 避坑清单（知名但已过时/转型，2026-09 实测）

| 项目 | 数据 | 问题 |
|---|---|---|
| CyC2018/CS-Notes | ~186k star | 实质停更于 2023-07，内容仍好但技术细节有过时风险，别作为唯一依据 |
| JushBJJ/Mr.-Ranedeer-AI-Tutor | ~29.6k star | README 已标 **DISCONTINUED**（提交止于 2025-09-30）。曾是最火 AI 导师提示词，可当提示词工程范例，别当在维护的工具 |
| learn-anything/learn-anything | ~17k star | 已转型为 linsa.io（端到端加密内容存储），README 自述"暂无时间公开开发"，最近提交 2026-01。原来的学习地图项目已名存实亡 |
| mckaywrigley/ai-tutor | — | 仓库已删除（解析报 repo not found）。教训：AI 学习工具代际更替极快，**任何 AI 学习类仓库先看最近提交时间** |
| datawhalechina/prompt-engineering-for-developers、llm-cookbook | 各 ~25k star | 2025 年中后更新放缓，内容为 2023–24 模型代际，新模型细节请用 self-llm |
| tuvtran/project-based-learning（旧地址） | — | 已迁移至 practical-tutorials 组织，旧链接虽自动重定向，收藏请换新地址 |

---

## 综合洞察：AI 时代资深程序员的高效学习闭环

一个可直接照做的五步闭环（每步标注依据）：

**1）选题：以"自造"定目标，以地图定边界。**
从 build-your-own-x / project-based-learning 挑一个与本职咬合的"从零造 X"（车机背景可选：mini 容器运行时、TCP/IP 栈、小型数据库、RAG 引擎），知识断层用 developer-roadmap 对应图 + TeachYourselfCS 的降载建议（CS:APP + DDIA 优先）圈定。自造项目天然是"能力边缘 + 即时反馈"的刻意练习结构（Ericsson 1993；build-your-own-x 的费曼题词）。

**2）练：检索优先，让 AI 当考官而不是讲解员。**
动手前把"让 AI 直接给方案"切换为学习模式式的苏格拉底引导（OpenAI Study Mode / Claude Learning Mode 的机制：先摸底、只提问、分步、结尾出题自测；自建 system prompt 即可复刻）。编码代理负责体力活，**你负责被它考**（Roediger & Karpicke 2006：检索比重读更能形成长期记忆）。

**3）用 AI 造检索材料，而不是造答案。**
每轮学完，用 raine/anki-llm 把概念批量生成候选闪卡，TUI 里人工筛选入 Anki（Anki 23.10+ 原生 FSRS 调度，间隔重复证据见 Cepeda 2006）；答错的卡用 AnkiAIUtils 自动补解释与助记——检索失败即刻反馈，正是测试效应最强的时刻。

**4）验证：费曼输出 + 公开仓库 + AI 复试。**
把学懂的东西压缩成一条条 TIL 短文推到公开仓库（simonw/til 的目录 + Actions 自动建站模板），写不出来即暴露未懂；隔天让 AI 就输出内容反向出题复盘（Karpicke & Blunt 2011：检索优于重读/整理）。公开换来的纠正与讨论是免费的反馈回路（swyx，learning in public）。

**5）沉淀：按常青标准入库，并让库自己排期复习。**
新知识按 Matuschak 五性质（原子、概念导向、密集链接、联想本体、为自己写）改写进 Zettelkasten；在 Obsidian 里用 obsidian-spaced-repetition 对笔记就地挂 FSRS 复习队列，让"沉淀物"自动回流到第 2 步的检索练习——闭环闭合：**选题→AI 考练→AI 造卡→公开费曼→常青入库→到期再检索**。

一句话总结：AI 把"获取解释"的成本降到近零，稀缺的变成**检索、输出与长期记忆**这三种旧功夫；上面五个仓库（build-your-own-x、study mode 式提示、anki-llm+FSRS、simonw/til、evergreen 笔记）恰好各占一个环节。
