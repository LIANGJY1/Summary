# C 线调研笔记：Linux 桌面端 AI 编码与终端 AI 工具（2026-09 格局）

- 调研日期：2026-09-19
- 读者画像：中国资深开发者（Android/车机方向），重度使用 Claude Code 类 agent（skills/hooks/MCP 齐备），有纯 markdown 知识库；终端工具算"桌面端"一部分。关注维度：①Linux 支持形态；②UI/UX 质量；③2026-09 最新格局（谁升谁降、新玩家）；④对"用 AI 高效编码+学习"的实际服务能力。
- 核实方式：开源仓库全部经 `gh api repos/<o>/<r>` 实查 stars/pushed_at/archived（2026-09-19），Linux 打包经最新 Release 资产清单实查；闭源产品（Cursor/Windsurf/Trae/Kiro/JetBrains）经官网抓取 + WebSearch（二手来源处已注明）。凡未核实处均明示。
- 与上轮笔记《C-善用AI.md》（../ai-era-programmer/sources/）的关系：那篇覆盖编码代理"方法论"（Claude Code 最佳实践、spec-kit、superpowers、aider 放缓初判等）；本篇专注**工具/载体本身在 Linux 上的形态与 UI/UX**，结论互链不重复。

---

## 〇、2026-09 一页结论（TL;DR）

1. **重心已从 GUI 编辑器转向终端 agent**：2026-07 XDA 评论直言"终端 AI agent 正在取代 GUI 编码工具"，Zed 是 Linux 上最接近 Cursor 的 GUI 替代（[morphllm 2026-09 评测](https://www.morphllm.com)、XDA 2026-07，均为二手来源）。star 数据佐证：openai/codex 125k、google-gemini/gemini-cli 107k、opencode 208k（下详）。
2. **大洗牌**：**Roo Code 2026-05-15 关停**（仓库归档、扩展下架，团队转向云端产品 Roomote）；**Void（开源 Cursor 替代）已归档**（2026-06 停更）；Aider 已 13 个月无 release；elia 两年未动。而 **sst/opencode → anomalyco/opencode 冲到 208k★** 成为开源第一编码 agent。
3. **Linux 一等公民化**：Cursor 新增原生 .deb；Trae 2026-03 官宣 Linux 原生版；Claude Code 官方签名 apt/dnf/apk 仓库；opencode 出桌面 App（deb/rpm/AppImage）；Goose 官方 Flatpak。
4. **编辑器与 agent 解耦成标准**：Zed 的 **ACP（Agent Client Protocol）** 让 Claude Code、Codex、Gemini CLI、OpenCode 等"外部 agent"直接住进编辑器面板——"自带 agent 的编辑器 + 自选终端 agent"成为 Linux 桌面最优解。

---

## 一、编辑器 / IDE 类

### 1. Zed（zed-industries/zed）— Linux 原生 GUI 首选 ✅
- URL：https://github.com/zed-industries/zed ；官网 https://zed.dev
- stars：**90,549**（2026-09-19）；pushed：**2026-09-19**（当日活跃）；最新版 v1.20.2（2026-09-17）
- License：自定义（GitHub API "Other"，主体 GPL/AGPL 系）
- Linux 打包（已核实）：官方 tar.gz（x86_64/aarch64，Release 资产含 zed-remote-server）；官方安装脚本 `curl -f https://zed.dev/install.sh | sh`（[docs](https://zed.dev/docs/installation)）；**Flathub 官方上架 dev.zed.Zed**（Flathub API 实查 stable v1.20.2）；AUR 社区包（未逐项核实）
- UI/UX：**Rust + GPUI 原生渲染，非 Electron**，GPU 加速，Linux（Wayland/X11）从"二等"升为一等维护；性能口碑在 2026 年评测中普遍第一（主观，依据：技术栈 + morphllm/XDA 评测）。
- AI/agent 能力（本次已核实 [外部 agent 文档](https://zed.dev/docs/ai/external-agents)）：**ACP 生态**——外部 agent 以独立进程接入 Agent Panel：**Claude（Agent）、Codex、Gemini CLI、OpenCode、Copilot、Cursor、Pi Coding Agent、Poolside**（经 ACP Registry 安装，列表"curated not exhaustive"）；协议仓库 agentclientprotocol/agent-client-protocol（4,281★，活跃）；Claude 接入适配器 agentclientprotocol/claude-agent-acp（2,547★，"Use Claude Agent SDK from any ACP client"，原 zed-industries/claude-code-acp）。Zed 自带 agent 与订阅另存（Ollama/LM Studio 支持在文档中，本次未逐条核实）。
- 对你的意义：**现有 Claude Code 的 skills/hooks/MCP 资产可原样复用**——agent 归 Claude Code 管，Zed 只做壳；Zed 配置的 MCP server "may be forwarded to External Agents over ACP"（官方文档原话）。 billing/认证仍在 agent 侧（Claude 自己管 auth）。

### 2. Cursor — Linux 终于体面 ✅（闭源）
- 官网 https://cursor.com （下载页含 Linux）
- Linux 打包：**AppImage（x64/arm64）+ 原生 .deb**（2025 底起新增，[2026-01 教程](https://itecsonline.com)实测 APT 安装；AppImage 在 Ubuntu 24.04 需 libfuse2，[askubuntu 老问题](https://askubuntu.com)）
- UI/UX：Electron（VS Code fork），与 mac 同步但 Linux 版偶有滞后/bug 的论坛反馈（主观，[Cursor Forum](https://forum.cursor.com)）
- 状态：仍是闭源 AI 编辑器头部；口碑分歧主要在定价与"终端 agent 冲击"。

### 3. Windsurf → Devin Desktop（Cognition）⚠️（闭源，品牌动荡）
- 2025-07 被 Cognition 收购；**2026-08 报道称整合更名为 "Devin Desktop"**（[The Rundown](https://www.therundown.ai/tools/codeium-windsurf)、[Devin Desktop changelog](https://docs.devin.ai/desktop/changelog)；此 rebrand 为二手来源，**未在官网逐字核实**）
- Linux 打包：**.deb/.rpm + source tarball**（[windsurf.com/editor/update-linux](https://windsurf.com/editor/update-linux) 原文"Available today on Mac, Windows, and Linux"）；[devin.ai/download](https://devin.ai/download) 提供 Mac/Win/Linux + JetBrains 插件 + CLI
- 评价：产品没死但品牌/路线动荡，Linux 用户押注需谨慎。

### 4. Void（voideditor/void）— 已死 ❌
- URL：https://github.com/voideditor/void
- stars：28,801；**archived: true（GitHub API 实查），最后 push 2026-06-02**
- 结论：曾经的"开源 Cursor 替代"明星（28.8k★）停止维护，勿再投入。

### 5. Trae（ByteDance）— 2026-03 补上 Linux ✅（闭源）
- 官网 https://www.trae.ai
- **2026-03-18 官宣原生支持 Linux**（[官方 X](https://x.com/Trae_ai/status/2034479243191886330)："full compatibility across macOS, Windows and Linux… natively built for Linux"）；2025-09 前后官方下载页对 Linux 态度暧昧、靠 AUR 社区包 trae-bin 过渡；2026-03-31 另加入 WSL 支持（二手来源）
- 评价：国内生态亲和（字节出品、模型接入对国内友好），Linux 版新、生态成熟度待观察；闭源、数据出境顾虑自评。

### 6. Kiro（AWS）— Linux 官方支持 ✅（闭源）
- 官网 https://kiro.dev （官方 Installation 文档含 Linux 下载）
- **Kiro CLI 在 Linux 支持 glibc 2.34+ / musl（Alpine 可用）**（2026-08 [bitdoze 评测](https://bitdoze.com)，二手）；IDE 自动后台更新
- 安全提示：2026-01 披露 CVE-2026-0830（GitLab 集成命令注入），需升到已修版本
- 定位：spec-driven 开发 IDE（spec→design→tasks），与你已关注的 spec-kit 方法论同构，可作对照观察。

### 7. JetBrains（AI Assistant + Junie）— 一句话（闭源）
- 随 IDE 全平台含 Linux；订阅制（2025 底起有免费档），Junie 支持多模型/推理力度调节；credits 计价在用户论坛有持续抱怨（[intellij-support 2025-11](https://intellij-support.jetbrains.com)）。闭源，不展开。

---

## 二、VS Code 插件类

### 1. Cline（cline/cline）— 独立 agent 路线赢家 ✅
- URL：https://github.com/cline/cline
- stars：**68,743**；pushed：2026-09-19（活跃）；Apache-2.0；TypeScript
- 官方描述（实查）："Autonomous coding agent as an SDK, IDE extension, or CLI assistant"——**2026 年已从纯 VS Code 插件扩展为 SDK + CLI 三形态**，部分对冲了"插件被终端 agent 替代"的趋势。
- Linux：VS Code/VSCodium/Ospot 等 VS Code 系均可用（插件无平台问题）。
- 事件：**官方宣布 Roo Code 并回 Cline**（[Cline 官宣](https://x.com/cline/status/2046645935762198953)）。

### 2. Roo Code（RooCodeInc/Roo-Code）— 已关停 ❌
- stars：24,301；**archived: true（实查），2026-05-15 仓库归档**
- 事实（已核实多源）：创始人 2026-04 下旬宣布停运，**5 月 15 日扩展、Roo Code Cloud、Router 全部下线**，付费余额退款；约 23k★ / 300 万安装时停摆；团队全力转向云端远程 agent **Roomote**（roomote.dev，"IDE 不是 AI 编码的未来"论，[The New Stack](https://thenewstack.io/roo-code-cloud-ides-ai-coding)）；社区批评"归档而非交棒开源"（r/LocalLLaMA）。
- 结论：曾是最活跃的 Cline fork，**官方建议迁移目标：Cline（官方并回）或 Kilo Code**。

### 3. Kilo Code（Kilo-Org/kilocode）— Roo 空出的承接者 ✅
- stars：**27,358**；pushed：2026-09-19（活跃）；MIT（morphllm 评测口径）
- 定位（实查官方描述）："the all-in-one agentic engineering platform… the most popular open source coding agent"；Cline/Roo 系融合产物，Roo 关停后主要迁徙地，2026 年涨势明显。

### 4. Continue（continuedev/continue）— 转型"开源编码 agent" ✅
- stars：**35,953**；pushed：2026-09-19（活跃）；Apache-2.0
- 官方描述（实查）已从"VS Code/JetBrains 补全助手"改为 **"open-source coding agent"**——与 Cline 同样的"去插件化"转型；老本行（本地模型/自定义 provider 补全）仍是最灵活的之一。

> 格局小结：VS Code 插件三强 2026-09 实况——**Cline 68.7k（活）> Continue 36.0k（活，转型）> Kilo 27.4k（活，上升）**；Roo 已出局。用户画像（重度终端 agent）下，插件类只作"在 VS Code 系里的备胎"。

---

## 三、Neovim 类（一句话级）

| 项目 | stars | pushed | 状态 | 定位一句话 |
|---|---|---|---|---|
| [avante-corp/avante.nvim](https://github.com/avante-corp/avante.nvim)（原 yetone） | 18,168 | 2026-09-18 | ✅ 活跃 | "在 nvim 里用 Cursor"：聊天流式改码 + diff 应用，观感最接近 IDE 交互 |
| [olimorris/codecompanion.nvim](https://github.com/olimorris/codecompanion.nvim) | 6,864 | 2026-09-19 | ✅ 活跃 | Vim 哲学优先：多 provider（含 Ollama/Anthropic/Copilot）、可装配性最强的 nvim AI 框架 |
| [folke/sidekick.nvim](https://github.com/folke/sidekick.nvim) | 2,768 | 2026-09-08 | ✅ 活跃（新） | folke（lazy.nvim 作者）2025 底新作 "Your Neovim AI sidekick"：next-edit 预测 + 把 Claude Code/Gemini CLI 等 CLI agent 嵌进 nvim 终端并联动——**2026 新玩家的代表，代表"nvim 绑定外部 CLI agent"路线** |

- 三者均为 Lua、随 nvim 天然 Linux 原生；UI 美观度依赖你的 nvim 配色（主观）。重度 Claude Code 用户若以 nvim 为主力，**sidekick 的"绑定外部 agent"路线与你现有资产兼容性最好**。

---

## 四、终端工具类（重点）

### 1. Claude Code 本体 — Linux 安装形态已"全平台正品化" ✅
- 文档：https://code.claude.com/docs/en/setup （2026-09-19 经 web_reader 抓取核实）
- Linux 安装（官方文档口径）：**native 安装脚本 + Homebrew + 官方签名 apt / dnf / apk 软件源（新增！）+ npm**；系统要求 Ubuntu 20.04+/Debian 10+/RHEL9/Alpine 3.19+（glibc 2.27+）
- 另有 Desktop app（文档提及；**Linux 是否提供桌面版未核实**）与 VS Code/JetBrains 扩展
- 对你：现有 skills/hooks/MCP 资产的主体，无需赘述；新签名仓库让 Linux 上告别 npm 全局安装的洁癖问题。

### 2. opencode（anomalyco/opencode，原 sst/opencode）— 开源第一编码 agent，爆发 ✅
- URL：https://github.com/anomalyco/opencode ；官网 https://opencode.ai
- stars：**208,530**（2026-09-19 实查，现象级）；pushed：2026-09-19；MIT；TypeScript；最新 v1.18.31（2026-09-14）
- 组织：sst 团队公司化 → **Anomaly**（anoma.ly）
- Linux 打包（Release 资产实查）：TUI 二进制（tar.gz，含 musl）+ **桌面 App（BETA）：.deb/.rpm/AppImage**（x86_64/aarch64；README "Desktop App (BETA)" 原文核实）
- 能力：TUI + **client/server 架构**（agent 内核与 UI 分离，可接桌面端/编辑器）；模型无关（任意 provider）；支持 share/协作等
- 顺带辟谣一条线：旧的 Go 版 opencode（opencode-ai/opencode，13.7k★）**2025-09 已 archived**——与 sst 系 TS 版是两码事，搜"opencode"时注意别混淆
- 对你：与你 `ai/` 目录已有的 OpenCode 手册直接呼应；208k★ + MIT + 桌面 BETA，是"想从 Claude Code 平移/双持到全开源栈"的第一候选。

### 3. OpenAI Codex CLI（openai/codex）— 终端双雄之二 ✅
- stars：**125,253**；pushed：2026-09-19；Rust；Apache-2.0；v0.155.1（2026-09-18，发版极快）
- Linux：npm / brew / **静态 musl 二进制（tar.gz）**/ 甚至 pip wheel（Release 资产实查）；`codex-app-server` 提供编辑器集成接口（Zed 的 Codex 接入即走 ACP/app-server）
- 能力：ChatGPT 订阅或 API key；MCP 支持；沙箱（Linux 用 bwrap/landlock，Release 里有 bwrap 资产）
- 口碑：2026 年与 Claude Code 并列"终端双雄"（社区共识，主观）。

### 4. Gemini CLI（google-gemini/gemini-cli）— 免费额度玩家 ✅
- stars：**107,078**；pushed：2026-09-19；TypeScript；Apache-2.0；v0.60.0（2026-09-15）
- Linux：**npm 安装（无原生包管理器产物，Release 仅 bundle）**
- 亮点：个人 Google 账号免费额度高（社区流传 60 req/min、1000 req/day，本次未核实，标注）；Zed ACP 一等公民
- 对你：便宜大碗的"第二 agent"，跑批量杂活/学习实验不心疼额度。

### 5. Crush（charmbracelet/crush）— 颜值 TUI 代表 ✅
- URL：https://github.com/charmbracelet/crush
- stars：**28,186**；pushed：2026-09-19；Go；v0.95.0（2026-09-16，发版勤）
- Linux 打包（Release 实查，**本清单最全**）：**.deb/.rpm/Alpine apk/Arch pkg.tar.zst**/Homebrew/裸二进制，甚至 Android/armv7；AUR 另有
- UI/UX：Charm 系（Bubble Tea/Lip Gloss），自称 "Glamourous agentic coding"，TUI 美学标杆（README 自述 + Charm 生态口碑，主观）
- 能力：模型无关（Catwalk 目录，OpenAI-compatible/Anthropic/Gemini/本地），MCP 支持，LSP 集成；License：GitHub API "Other/NOASSERTION"（项目曾以 Fair Source 口径发布，**具体条款未逐字核实**）
- 对你：不替代 Claude Code 的方法论资产，但作为"多模型、多会话、好看"的副驾驶 TUI 合格。

### 6. Goose（aaif-goose/goose，原 block/goose）— Linux 桌面打包最豪华 ✅
- stars：**54,458**；pushed：2026-09-19；Rust；Apache-2.0；v1.51.0（2026-09-17）
- 组织变动：Block 孵化 → org 现为 **aaif-goose**（"The goose ai agent platform"，docs：goose-docs.ai）
- Linux 打包（Release 实查）：**.deb/.rpm/官方 Flatpak（io.github.block.Goose）**/tar.gz（gnu/musl）+ **桌面 App 与 CLI 双形态**，vulkan 变体（本地模型加速野心）
- 能力：任意 LLM（含 Ollama）、MCP、扩展生态；定位"超越代码补干的通用 agent"
- 评价：若你想要"Linux 原生包管理器直接装的 AI agent 桌面应用"，Goose 是官方 Flatpak 路线的唯一正经选项。

### 7. Aider（Aider-AI/aider）— 放缓实锤，确认上轮判断 ⚠️
- stars：49,050；pushed：2026-05-22；**最后 release v0.86.0 = 2025-08-09（至 2026-09-19 已 13 个月无 release，实查）**
- 结论：未死但节奏明显停滞，勿作日常主力；repo map 等理念贡献已普惠（详见上轮笔记）。pip 安装仍可用。

### 8. aichat（sigoden/aichat）— 全能但同步放缓 ⚠️
- stars：10,455；pushed：2026-02-23；Rust；**最后 release v0.30.0 = 2025-07-06（14 个月无 release，实查）**
- 功能面仍最全（Shell 助手/REPL/RAG/tools+agents/MCP，官方描述实查）；作为瑞士军刀仍能用，作为快速演进的编码 agent 已掉队。cargo/二进制安装。

### 9. elia（darrenburns/elia）— 已死 ❌
- stars：2,479；**pushed：2024-10-10（两年未动，实查）**。Textual TUI 聊天窗口，当年惊艳，已弃维护。找 TUI 聊天请用 aichat REPL 或 crush。

### 10. gptme（gptme/gptme）— 小众但活跃 ✅
- stars：4,418；pushed：2026-09-19；Python；MIT
- 定位：本地工具型 agent（写码/终端/浏览器），可自建持久自主 agent；适合"想理解 agent 内部构造"的学习对象，生产主力不推荐（体量所限，主观）。

### 11. 补充：两个国内相关的高星终端件
- **claude-code-router**（[musistudio/claude-code-router](https://github.com/musistudio/claude-code-router)）：37,324★，pushed 2026-09-18 活跃。给 Claude Code 做模型路由（DeepSeek/Qwen/Ollama 等接进 Claude Code 工作流）——**国内模型接入刚需件**，与你的 skills/hooks 资产零冲突。
- **qwen-code**（[QwenLM/qwen-code](https://github.com/QwenLM/qwen-code)）：27,986★，pushed 2026-09-19 活跃。阿里基于 gemini-cli fork 的终端编码 agent，Qwen 免费额度；gemini-cli 系语法。

---

## 五、对比矩阵（2026-09-19 快照）

| 工具 | 类别/UI 形态 | Linux 打包 | Agent 能力 | MCP | 模型接入 | 活跃度 |
|---|---|---|---|---|---|---|
| **Zed** | GUI（Rust 原生，非 Electron） | tar.gz+官方脚本+Flathub（+AUR 社区） | 自带 agent + **ACP 外部 agent**（Claude/Codex/Gemini CLI/OpenCode/Copilot/Cursor 等） | 有（可转发给外部 agent） | 自带订阅 + Ollama/LM Studio/OpenAI 兼容 | ✅ 当日推送 |
| **Cursor** | GUI（Electron/VS Code fork，闭源） | AppImage + .deb（新） | 自带（Composer/agent） | 有 | 订阅制 | ✅（闭源，按发版） |
| **Windsurf/Devin Desktop** | GUI（Electron，闭源） | .deb/.rpm/tarball | 自带 + Devin 整合 | 有 | 订阅制 | ⚠️ 品牌动荡 |
| **Trae** | GUI（Electron，闭源） | 官方 Linux 版（2026-03 起，AUR 有社区包） | 自带 agent/自定义 agent | 有 | 国内模型亲和 | ✅ |
| **Kiro** | GUI（闭源） | 官网下载（CLI 支持 musl） | spec-driven agent | 有 | 订阅制（AWS） | ✅ |
| **Cline** | VS Code 插件 + SDK + CLI | 随 VS Code 系 | 自主 agent（SDK 化） | 有 | 任意（BYOK） | ✅ 当日推送 |
| **Kilo Code** | VS Code 插件 | 随 VS Code 系 | 多 agent | 有 | 任意 | ✅ 当日推送 |
| **Continue** | 插件（转型 agent） | 随 IDE | agent 化 | 有 | 最灵活（本地优先） | ✅ 当日推送 |
| **Claude Code** | 终端 agent（+IDE 扩展） | 脚本/brew/**apt/dnf/apk 官方源**/npm | 最强方法论生态（skills/hooks/subagents） | 有（生态最大） | Claude 订阅/API | ✅ 当日推送 |
| **opencode** | 终端 TUI + server + 桌面 BETA | tar.gz/musl + **deb/rpm/AppImage（桌面）** | client/server，可换 UI | 有 | 任意 | ✅ 当日推送（208k★） |
| **Codex CLI** | 终端 agent（Rust） | npm/brew/musl 二进制/pip | app-server 可被编辑器集成 | 有 | ChatGPT 订阅/API | ✅ 当日推送 |
| **Gemini CLI** | 终端 agent | npm（仅） | agent + ACP 一等公民 | 有 | Google 账号（免费额度） | ✅ 当日推送 |
| **Crush** | 终端 TUI（Go，颜值标杆） | **deb/rpm/apk/arch/AUR/brew/二进制（最全）** | 多会话 agent + LSP | 有 | 任意（Catwalk） | ✅ 当日推送 |
| **Goose** | CLI + 桌面 App | **deb/rpm/Flatpak（官方）/tar.gz** | 通用 agent + 扩展 | 有 | 任意（含 Ollama） | ✅ 当日推送 |
| **Aider** | 终端结对 | pip/brew | repo map 方法论鼻祖 | 无（弱） | 任意 | ⚠️ 13 个月无 release |
| **aichat** | CLI/REPL/TUI 全能 | cargo/brew/二进制 | 轻量 agent | 有 | 任意 | ⚠️ 14 个月无 release |
| **elia** | TUI 聊天 | pip | 无 | 无 | 多 | ❌ 两年未动 |
| **gptme** | 终端 agent（Python） | pip | 可自建持久 agent | 弱 | 任意 | ✅ 当日推送 |
| **Void** | GUI（开源 Cursor 替代） | — | — | — | — | ❌ 已归档（2026-06） |
| **Roo Code** | VS Code 插件 | — | — | — | — | ❌ 2026-05-15 关停 |

---

## 六、推荐（针对：Linux 桌面 + 重度 agent 工作流 + 想要好 UI）

### 首选组合：Zed + Claude Code（+ Crush 作 TUI 副驾驶）
- **Zed 作壳**：Linux 上唯一"原生 Rust、非 Electron、GPU 渲染"的现代编辑器，性能与颜值都够；关键是 **ACP 让你的 Claude Code 直接住进 Zed 的 Agent Panel**（claude-agent-acp 适配器 + ACP Registry 一键装），**现有 skills/hooks/MCP 资产 100% 复用**，agent 的 auth/模型/配置仍归 Claude Code 管。Zed 自带的订阅 agent 可不用或当备胎。
- **Claude Code 仍是大脑**：方法论资产（上轮笔记 L1-L4 框架）都长在它上面；Linux 安装已正版化（官方签名 apt/dnf/apk 源），车机/内网场景 apt 源比 npm 脚本可控。
- **Crush 作第二 TUI**：想换模型/开并行会话/单纯赏心悦目时用；打包最全，apt 直装，与 Claude Code 配置互不干扰。
- 何时调整：若某天需要纯开源订阅无关栈 → 把大脑换成 opencode（见下），Zed 照样能通过 ACP 接它。

### 替补组合：opencode（大脑）+ codecompanion.nvim 或 sidekick.nvim（若 nvim 主力）+ claude-code-router（模型路由）
- **opencode**：MIT 全开源、client/server 架构、208k★、桌面 App 已有 BETA（deb/AppImage）——"去 Anthropic 依赖"路线的完整平替，且与你已有 OpenCode 手册呼应。
- **nvim 用户**：sidekick.nvim（folke）代表"nvim 绑定外部 CLI agent"的新路线，兼容性最好；要纯 nvim 内生体验选 codecompanion.nvim。
- **claude-code-router**：把 DeepSeek/Qwen/Ollama 路由进 Claude Code 工作流，国内网络/成本现实下的刚需胶水。
- 何时选替补：模型自主权/成本/离线优先级高于"生态最厚"时。

### 一句话总评
2026-09 的 Linux 端答案已收敛为：**终端 agent 做大脑（Claude Code 或 opencode/Codex），GUI 只做壳（Zed 或 VS Code 系+Cline），TUI 副驾驶看颜值（Crush）**；GUI 重型一体机（Cursor/Windsurf/Trae/Kiro）各有 Linux 版但都在为"终端化"焦虑，Roo Code 与 Void 的死亡是这个转折的注脚。

---

### 附：本篇新增来源（按条目内已标注，集中列关键项）
- 仓库实查：`gh api repos/<o>/<r>`（stars/pushed_at/archived，2026-09-19）与各 repo `releases/latest` 资产清单（Zed v1.20.2 / Crush v0.95.0 / opencode v1.18.31 / Codex rust-v0.155.1 / Goose v1.51.0 / Gemini CLI v0.60.0 / Aider v0.86.0 / aichat v0.30.0）
- https://zed.dev/docs/installation ；https://zed.dev/docs/ai/external-agents ；Flathub API（dev.zed.Zed）
- https://code.claude.com/docs/en/setup （web_reader 抓取）
- Roo Code 关停：GitHub archived 状态 + https://thenewstack.io/roo-code-cloud-ides-ai-coding + https://x.com/cline/status/2046645935762198953
- Windsurf/Devin：https://windsurf.com/editor/update-linux + https://docs.devin.ai/desktop/changelog + https://www.therundown.ai/tools/codeium-windsurf（二手，rebrand 未官网核实）
- Trae：https://x.com/Trae_ai/status/2034479243191886330 ；Kiro：https://kiro.dev
- 格局趋势：https://www.morphllm.com （2026-09 Cursor 替代评测，二手）＋ XDA 2026-07（二手）
