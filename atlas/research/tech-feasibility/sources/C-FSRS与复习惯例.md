# C — FSRS 实现盘点与复习惯例调研

- 日期：2026-09-19（所有 stars / pushed_at 均为当日 `gh api` 实测）
- 对象：Atlas（零模型零网络 Linux 桌面端，Kotlin/Compose Desktop）间隔重复复习模块的技术选型与交互设计
- 核实手段：`gh api` 仓库元数据与源码（已认证）、open-spaced-repetition wiki/README 原文引用、Anki 官方手册（docs.ankiweb.net）、Mochi 官网文档、RemNote 官方帮助中心、Obsidian Spaced Repetition 源码。**SuperMemo 官网（help.supermemo.org 403 反爬、supermemo.guru 超时、Wikipedia 超时）本机不可达，相关条目标注「未核实官方原文」**。

---

## ① FSRS 实现盘点

### 1.1 open-spaced-repetition 组织全景（gh api 实测，按 pushed_at 排序节选）

| 仓库 | 语言 | stars | pushed_at | License | 结论 |
|---|---|---|---|---|---|
| ts-fsrs | TypeScript | 794 | 2026-09-18 | MIT | 社区采用最广，活跃 |
| fsrs4anki | Jupyter/JS | 4072 | 2026-08-14 | MIT | 遗留 addon + 算法文档/wiki 所在地；集成首选已让位 fsrs-rs |
| py-fsrs | Python | 492 | 2026-08-09 | MIT | 参考实现，v6.3.2，活跃，PyPI `fsrs` |
| fsrs-rs | Rust | 429 | 2026-09-18 | BSD-3 | **Anki 内置实现**（py-fsrs README 原文："the official Rust-based Anki implementation, please see fsrs-rs"），含 Optimizer+Scheduler |
| go-fsrs | Go | 145 | 2026-07-25 | MIT | 活跃 |
| swift-fsrs | Swift | 102 | 2026-05-25 | MIT | |
| fsrs-browser | Rust(WASM) | 54 | 2026-06-14 | BSD-3 | 浏览器端 |
| dart-fsrs | Dart | 65 | 2025-06-20 | MIT | |
| **FSRS-Kotlin** | Kotlin | 27 | 2025-08-07 | **无 LICENSE 文件** | 2 个源文件约 337 行的一次性移植，8 个提交，无 release |
| **java-fsrs** | Java | 23 | 2025-07-30 | MIT | **已发 Maven Central**（`io.github.open-spaced-repetition:fsrs`，v1.0.0，2025-07-30），Java 17，90 commits |
| rs-fsrs-java | Rust/Java 绑定 | 20 | 2026-02-03 | MIT | rs-fsrs 的 Java binding（JNI），仓库语言标 Rust |
| rb-fsrs / cljc-fsrs / lisp-fsrs / ex_fsrs | Ruby/Clojure/CL/Elixir | 23/17/32/27 | 2025 | MIT | cljc-fsrs 仅 FSRS v4，过时 |
| rs-fsrs | Rust | 50 | 2026-07-20 | MIT | 纯 Rust 调度器（被 rs-fsrs-java 绑定） |

站外搜索 "fsrs kotlin" 仅多出 `LordDarthArt/fsrs-kotlin`（0★，无描述）；"fsrs java" 无 org 外有量结果。**结论：无 Kotlin 生产级实现；有官方 Java 实现。**

### 1.2 JVM 候选细评

**java-fsrs（推荐）**——README/源码当日核实：
- API 形态正合"给定评分→下次到期"：
  ```java
  Scheduler scheduler = Scheduler.builder().build();
  CardAndReviewLog result = scheduler.reviewCard(card, rating); // 重载支持显式 reviewDatetime / reviewDuration
  Instant due = result.card().getDue();
  double r = scheduler.getCardRetrievability(card);
  ```
- `Scheduler.builder()` 默认值（README 原文 "the following arguments are also the defaults"）：21 参数（FSRS-6）、`desiredRetention(0.9)`、`learningSteps(1m, 10m)`、`relearningSteps(10m)`、`maximumInterval(36500)`、`enableFuzzing(true)`。
- `Card`/`ReviewLog`/`Scheduler` 均有 `toJson/fromJson`（README：`Scheduler newScheduler = Scheduler.fromJson(schedulerJson);`）。
- 限制：README 原文 "**Java-FSRS uses UTC only.**"；"Currently, Java-FSRS does not support parameter optimization"；源码用 Lombok（`State.java` 有 `@Getter`）；最后一次提交 2025-07-30（约 14 个月未动，但 FSRS-6 公式已定稿，属"完成型冻结"而非弃坑）。

**FSRS-Kotlin（不直接用）**：FSRS-6，README 声称 MIT 但**仓库无 LICENSE 文件**（gh api license 字段为 none）——默认版权保留，法律上不可作为依赖；API 为 `fsrs.calculate(flashCard)` 返回各按钮的 interval 列表；`FSRS.kt` 299 行 + `models.kt` 38 行。价值：证明 FSRS-6 核心可压缩到 ~340 行 Kotlin。

**rs-fsrs-java（不推荐）**：JNI 绑定，需分发平台原生库，违背纯 JVM 单 jar 交付。

### 1.3 推荐路线

1. **首选：直接依赖 java-fsrs**（Maven Central，MIT）。外围用自研 `FsrsEngine` 适配层隔离，序列化层面只把 FSRS 状态行（state/stability/difficulty/due/step 等）写进 cards.md，不落其对象图。
2. **备选：从 py-fsrs 移植到 Kotlin**。工作量证据：核心调度 java-fsrs `Scheduler.java` 661 行（含 fuzz/学习步/JSON），FSRS-Kotlin 压到 337 行。估计 **300–600 行 Kotlin + 1–2 人日**（含对拍测试：以 py-fsrs 或 java-fsrs 输出做 golden file，逐评分/逐状态比对 due 与 stability）。仅在 java-fsrs 出现不可修的阻塞（如 Lombok/模块化冲突）时启动。
3. 参数优化器（fsrs-rs / py-fsrs 的 Optimizer）**不需要**：Atlas 定位零配置，用默认 21 参数即可；将来若做个性化优化再评估。

---

## ② 算法要点速查（给实现者）

来源：py-fsrs/java-fsrs README、awesome-fsrs wiki《The Algorithm》（fsrs4anki wiki 已迁移至此，本日抓取原文）。

### 2.1 状态机与四键映射

- 库内三态（java-fsrs `State.java` / py-fsrs README 原文）：`Learning(1)`（"new card being studied for the first time"，**新卡即 Learning 且立即到期**——"all new cards are 'due' immediately upon creation"）、`Review(2)`（"graduated from the Learning state"）、`Relearning(3)`（"lapsed from the Review state"）。Anki 术语额外有 New 态，库实现用 Learning+立即到期吸收。
- 转移：Learning --Good 走完 steps--> Review；Review --Again(lapse)--> Relearning；若 relearning_steps 置空则 lapse 后**留在 Review**（README 原文："cards in the Review state, when lapsed, will not move to the Relearning state, but instead stay in the Review state"）。
- 四键映射（py-fsrs README 原文，各实现一致）：`Again(1) forgot the card`；`Hard(2) remembered the card with serious difficulty`；`Good(3) remembered the card after a hesitation`；`Easy(4) remembered the card easily`。**与 Atlas 四键语义一一对应，无需改。**

### 2.2 desired retention 与参数

- 默认 **0.9**：py-fsrs/java-fsrs 默认 `desired_retention=0.9`；README 原文："a card will be scheduled at a time in the future when the predicted probability of the user correctly recalling that card falls to 90%"。Anki 手册同："The default is 90%, which offers a good balance of retention and workload."
- 参数个数演进（wiki 原表）：v1:7 → v2:14 → v3:13 → v4/4.5:17 → v5:19 → **FSRS-6:21（w0–w20）**。
- 默认参数（wiki 与 py-fsrs README 完全一致）：`0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542`。来源：wiki 原文 "originates in the DHP model from MaiMemo"，在大规模开源复习日志上预优化；**零配置场景直接用，无需训练**。

### 2.3 核心公式（wiki 原文引用）

- 可提取性：`R(t,S) = (1 + FACTOR·t/S)^DECAY`；FSRS-4.5 起 `DECAY=-0.5, FACTOR=19/81`；FSRS-6 让 decay 可训练（=w20），且 "factor = 0.9^(-1/w20) - 1 to ensure R(S,S) = 90%"。
- 间隔：`I(r,S) = S/FACTOR · (r^(1/DECAY) - 1)`。
- 成功后稳定性：`S' = S·(e^{w8}(11-D)·S^{-w9}·(e^{w10(1-R)}-1)·[hard/easy penalty] + 1)`，Hard 用 w15、Easy 用 w16。
- 遗忘后稳定性：`S'_f = w11 · D^{-w12} · ((S+1)^{w13} - 1) · e^{w14(1-R)}`。
- **同日（短期）稳定性，FSRS-6 新增**：`S'(S,G) = S · e^{w17·(G-3+w18)} · S^{-w19}`，wiki 原文说明其用意："the S increases faster when it's small and slower when it's large"。
- 难度（1–10，java-fsrs `MIN/MAX_DIFFICULTY=1.0/10.0`）：初始 `D0(G)=w4 - e^{w5(G-1)} + 1`；更新 `ΔD=-w6(G-3)`，线性阻尼 `D'=D+ΔD·(10-D)/9`，均值回归 `D″=w7·D0(4)+(1-w7)·D'`。S 下限 0.001。

### 2.4 同日重复（learning steps）怎么处理

- 库层面：`learning_steps`（默认 1m→10m）在 Learning 态内做同日小步，Good 推进一步、Again 回到第一步；Relearning 态同理（默认 10m）。这是 Anki 惯例的延续，FSRS 库实现照单全收。
- Anki 现状（24.11 release notes 原文）："Let FSRS control short term schedule when no (re)learning steps are set. This is experimental."——即 Anki 正在让 FSRS 的短期稳定性公式接管同日调度。Anki 手册建议："Ensure that all your learning and re-learning steps are shorter than 1d"、"keep the number of learning steps to a minimum"。
- **Atlas 建议**：桌面自定节奏场景可默认空 learning steps（复习节奏由用户掌握），让 FSRS-6 短期公式处理同日重复；或保守用 1m/10m。

### 2.5 纯函数化程度

- `reviewCard(card, rating, reviewDatetime?) → (Card, ReviewLog)`：给定 (状态, 评分, 时间, 参数, 配置) 输出完全确定——**"评分→(state,due) 纯函数"成立**。
- 两个非纯点及对策：① `enable_fuzzing` 默认 true（java-fsrs 有 `DEFAULT_RANDOM_SEED_NUMBER = 42`，固定 seed 即确定；或 Atlas 直接关闭）；② 时间由调用方注入（`Instant reviewDatetime` 重载），测试可固定时钟。注意两库均 **UTC only**，cards.md 的状态行建议存 epoch 毫秒 + due 用时间戳而非"日期"。

---

## ③ 成熟产品复习交互惯例（可借鉴清单）

### 3.1 Anki（docs.ankiweb.net，本日核实）

值得抄：
1. **快捷键四键**：Space 显示答案，答后 1/2/3/4 = Again/Hard/Good/Easy，且 "Space, Enter" 默认 Good；手册建议用户 "keep one finger on 1"。Atlas 照搬：Space/Enter 显示答案并默认 Good。
2. **leech 机制**：lapse 计数达 8（默认）自动 "tags the note as a leech and suspends the card"，之后每 +4 再提醒；手册给的处置是改写（"The most efficient method"）、删除、或隔离干扰项。→ Atlas 可做"问题卡"报告（程序员卡常因一张卡塞太多信息而反复忘）。
3. **零配置默认**：desired retention 90% 即官方默认；FSRS 开关是全局的。Atlas 进一步：不暴露，只留一个"记忆保持率"滑杆（0.8–0.95）。
该避开的坑：SM-2 时代的 "ease hell"（长 learning steps 内反复失败压垮 ease；手册原文："This is not a problem that FSRS suffers from"）——别保留任何 ease 可被用户手工调整的 UI；以及 Anki 式选项海洋，违背 Atlas 零配置定位。

### 3.2 Obsidian Spaced Repetition（st3v3nmw，2557★ MIT；已迁 open-spaced-repetition org 名下 `obsidian-spaced-repetition-recall` 210★，2026-09-19 仍在推送）——与 cards.md+来源锚点设计最像，重点看

值得抄：
1. **卡写进笔记正文**：单行 `Question::Answer`、可逆 `Question:::Answer`、多行"separated by `?`"（可逆 `??`）、cloze 用 `==高亮==`/`{{花括号}}`/粗体；`#flashcards` tag 或文件夹分库，子库 `#flashcards/子库`。
2. **调度写回笔记、不污染预览**：卡级 HTML 注释 `<!--SR:!2024-08-16,51,230-->`（下次到期日,间隔天数,ease），文档原文 "Wrapping in a HTML comment makes the scheduling information not visible in the notes preview"；整笔记级调度进 frontmatter（`sr-due/sr-interval/sr-ease`）。Atlas 的 FSRS 状态行同理：机器可读但对 markdown 阅读无害。
3. **上下文与复习范围粒度**：复习时显示 `笔记名 > 标题链` 上下文（UI 偏好可关）；命令分"全部复习 / Review flashcards in this note / cram 本笔记"，cram "algorithm fully ignored"——临时抱佛脚模式是真实需求。
4. （反向教训）**复习弹窗不能跳回源笔记**：`context-section.tsx` 本日读源码核实，上下文仅 `setText()` 渲染成纯文本，无点击处理。Atlas 的来源锚点（卡片→笔记小节）应一键跳转定位到 md 对应行——这是对 obsidian-sr 最直接的超越点。
该避开的坑：评分键位 0/1/2/3 与 Anki 惯例(1-4)冲突（社区两套键位并存，迁移用户不适）；调度注释无版本号，算法升级后旧注释语义漂移——Atlas 状态行应带 `fsrs6` 版本标记。

### 3.3 Mochi（mochi.cards，闭源 SaaS；官网文档本日抓取）

值得抄：
1. **markdown 即导入/导出货币**：`.mochi` 包 = zip + `data.edn 或 data.json` + 媒体文件；md 文件导入时以标题建 deck 层级、文件→卡或分隔符拆卡——"built on top of Markdown"。
2. 复习记录含 date/due/interval 的轻量结构，可导出迁移（.mochi 格式参考文档）。
该避开的坑：md 导入是**单向快照**，卡库绑定其私有存储，没有"笔记内嵌卡+调度写回"的双通道闭环；闭源无法自托管。附注：其复习评分粒度较粗（remembered 与否），低于四键的信息量——此点来源为官网文档抓取，未深入核实，不作强结论。

### 3.4 RemNote（闭源；help.remnote.com 官方文章 6025481，本日核实）

值得抄：
1. **行内 `::` 语法**：原文 "Create a Concept card by typing the name of the concept, :: (two colons), and then its definition."；方向修饰 `:>`（forward-only）/`:<`（reverse-only）；多行=触发符打三遍（`:::`）；cloze `{{...}}`；卡片名后加 `-` 立即禁用（"type - after you've created a flashcard, to immediately disable it"）——禁用而非删除，很适合冻结过时知识。
2. **层级上下文**："it will show all of the ancestors of the bullet the card was generated from"——复习时自动带出大纲祖先链，卡片不用自带背景。Atlas 用 `库 > 文件 > 标题链` 等价实现。
该避开的坑：魔法字符过多（`>>`/`==`/`::`/`:>`/`;:`/`;;`/`{{}}`/`>>>`），与 markdown 原生语义打架、学习成本高。Atlas 分隔符要克制（一个主分隔符 + 一个多行分隔符足矣）且避开 md 常用符号。

### 3.5 SuperMemo 增量阅读（**未核实官方原文**：官网 403/超时；以下为二手证据 + 公认描述）

思想（二手引用）：jdlorimer/incremental-reading README："The idea of working with long-form content within a spaced-repetition program appears to have originated with SuperMemo"；foliole README："Built around Piotr Woźniak's incremental reading ideas, with a native workflow for extracting passages, creating cloze deletions"。公认流水线：阅读 → Extract（摘录）→ Cloze → Item，配优先级队列与 auto-postpone（积压自动顺延）。
值得抄：**不追求一次做出完美卡**——素材先摘录、再改写、卡片是流水线产物。Atlas 的"缺口管理"照此：复习中暴露的缺口 → 回写笔记/生成新卡 → 再复习，形成闭环。
该避开的坑：全家桶复杂度（优先级队列、 postpone 策略叠加）对程序员日常过重；Atlas 只取"摘录成卡 + 积压顺延提示"的最小集。

---

## ④ 差异化校验："本地 markdown 库 + 间隔重复 + 缺口管理 + 零模型"是否已被占位

GitHub 搜索（`gh search repos`，2026-09-19："markdown spaced repetition"、"srs markdown notes"、"incremental reading"、"plain text spaced repetition"、"flashcards markdown"、"flashcards local-first"）候选全清单：

| 项目 | stars | 状态 | 差什么 |
|---|---|---|---|
| st3v3nmw/obsidian-spaced-repetition（+OSR org 的 -recall） | 2557 / 210 | 活跃 | **最近先例 #1**：卡内嵌笔记+调度写回。寄生于 Obsidian；无缺口管理；复习不可跳回源笔记；调度注释无版本化 |
| campfirium/foliole | 125 | 活跃（2026-09-19 push，Apache-2.0，TS，桌面 macOS/Win/Linux） | **最近先例 #2**：桌面增量阅读应用，内置 FSRS、extract/cloze 流水线。但 README 原文 "Uses a SQLite database and provides a Markdown mirror"——**SQLite 主存储，md 只是镜像**；以"阅读素材→卡"为中心，不是"已有 md 知识库 + 缺口管理" |
| jdlorimer/incremental-reading | 228 | 2022 停更 | Anki 2.1 插件（extract 快捷键 x/h/z 等），需 Anki |
| bjsi/incremental-everything | 74 | 活跃 | RemNote 插件，寄主闭源 |
| mochar/logseq-incremental-blocks | 54 | 维护 | Logseq 插件 |
| ebAobS/roaming-mode-incremental-reading | 51 | 活跃 | 思源笔记插件 |
| vascoferreira25/org-mode-incremental-reading / adham-omran/ir | 64 / 11 | 2022/小 | org-mode + Anki / Emacs |
| reorproject/reor | 8553 | 维护 | 本地 md 知识库 + **本地 AI**（AGPL），**无 SRS**——重模型路线，与 Atlas 反向 |
| lukesmurray/markdown-anki-decks / anki-panky / better-markdown-anki | 143 / 51 / 31 | 维护 | md→Anki **转换器**：证明"md 写卡"需求真实，但都要装 Anki，非独立闭环 |
| DreamThemeGH/nextrepetition、shbernal/leitner 等 | 0 | 长尾 | Nextcloud 文件型 md SRS、TUI，无生态 |
| Mochi / RemNote / SuperMemo | — | 闭源 | 不可自托管、不可审计 |

**结论：四要素组合（本地 md 知识库为唯一事实源 + FSRS 四键复习 + 缺口管理闭环 + 零模型零网络单 jar 交付）仍是空位。**
- 最接近先例是 obsidian-spaced-repetition：差在寄生于 Obsidian、md 之外还需其插件运行时、无缺口管理、来源上下文不可跳转。
- 其次是 foliole：差在 SQLite-first（md 仅镜像）、定位是"阅读素材流水线"而非"已有知识库的缺口管理"。
- Atlas 可辩护差异：① md 是数据库本身（cards.md 状态行 + 任意笔记皆可为卡源）；② 来源锚点一键跳回（obsidian-sr 未做、已核实）；③ 缺口管理闭环（无先例做了）；④ 零依赖零模型（vs Reor/Mochi/RemNote）。

---

## 附：核实记录

- `gh api orgs/open-spaced-repetition/repos`、`repos/open-spaced-repetition/{py-fsrs,ts-fsrs,fsrs-rs,java-fsrs,FSRS-Kotlin,rs-fsrs-java,cljc-fsrs,...}` 及各 README/Scheduler.java/State.java：2026-09-19。
- awesome-fsrs wiki《The Algorithm》（fsrs4anki/wiki/The-Algorithm 已迁移至此）：2026-09-19 抓取，公式/参数表为原文引用。
- py-fsrs README（v6.3.x 对应内容）、java-fsrs README 与 releases（v1.0.0 @ 2025-07-30，Maven Central badge）：2026-09-19。
- docs.ankiweb.net：`studying.html`（快捷键）、`leeches.html`（阈值 8）、`deck-options.html`（90% 默认、steps 建议）原文引用：2026-09-19。
- st3v3nmw/obsidian-spaced-repetition：`docs/docs/en/data-storage.md`、`reviewing.md`、`src/.../context-section.tsx`：2026-09-19。
- mochi.cards/docs（.mochi 格式 = zip+data.edn/json+媒体；导入分文件级/分隔符级）、mochi-cards/open-source（118★，"A collection of open-source integrations with Mochi"）：2026-09-19。
- help.remnote.com/en/articles/6025481-creating-flashcards（`::`/`:>`/`:<`/`;;`/`{{}}`/`>>>`/`-` 禁用 原文）：2026-09-19。
- ankitects/anki releases（24.11 body 中 FSRS 短期调度实验性原文）：2026-09-19。
- 未核实：help.supermemo.org（403）、supermemo.guru（超时）、en.wikipedia.org（超时）；Mochi 评分粒度细节。3.5 节 SuperMemo 内容为二手来源，落地设计前建议再以可联网环境复核 supermemo.com 帮助页。
