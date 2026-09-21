# Linux 桌面端程序员 AI 项目深度调研（2026-09）

> 调研日期：2026-09-19 ｜ 前篇：[../ai-era-programmer/](../ai-era-programmer/AI时代程序员自处与发展-GitHub深度调研.md)（四个问题的理论与知识库选型）；**本篇是续作**，聚焦增量维度：Linux 桌面打包形态（Release 资产/Flathub/AUR 逐项核实）、UI 技术栈与观感、2026-09 最新格局，并把项目映射回四个问题。
> 详细原始笔记见 `sources/`（A 知识库笔记AI客户端 / B 桌面LLM工作台与本地底座 / C AI编码与终端工具）。全部仓库经 GitHub API 实查 star/pushed_at/archived，打包形态经 Release 资产清单实查；star 均为查询日快照；标 \* 处为观感主观评价。

---

## 0. TL;DR

1. **一套可直接抄的 Linux 工作站组合**：知识库 = Obsidian（官方 deb/AppImage）+ obsidian-copilot + basic-memory 同目录；编码 = **Zed + Claude Code（官方 apt/dnf/apk 源）+ Crush**；本地底座 = Ollama 无头 + llama-server 自带 WebUI；问答镜像 = AnythingLLM（AppImage）或新秀 Askimo（deb）；学习 = Anki（FSRS）+ anki-llm。
2. **2026-09 格局：终端 agent 做大脑，GUI 只做壳**。开源第一编码 agent 已是终端系的 opencode（208k★，sst→Anomaly，桌面 App BETA）；Roo Code 关停（2026-05）、Void 归档（2026-06）、Aider/aichat 停更 13–14 个月——GUI 重型一体机时代在 Linux 上落幕。
3. **编辑器与 agent 解耦成标准**：Zed 的 ACP 协议让 Claude Code 连同你的 skills/hooks/MCP 资产**原样住进编辑器**（Rust 原生非 Electron，Linux 一等公民）——这是 Linux 桌面端"好 UI + 不换大脑"的最优解。
4. **两个反直觉核实结论**：① Ollama 官方桌面 GUI **至今没有 Linux 版**（download 页 + v0.34.2 release notes 三重证据）；② 空位被 **llama.cpp 填了**——llama-server 自带 WebUI 已进化为 MCP host（v0.4.1 实锤），"底座即工作台"。
5. **知识库+AI 的 Linux 答案没变但更清晰**：纯 md 库零迁移路线 = Obsidian 生态（唯一 vault 即 md 目录 + 官方 Linux 包 + 插件级 vault RAG + 能把 Claude Code 拉进 GUI）；唯一原生 UI 独苗 = Alpaca（GTK4，但无 RAG）；唯一"目录 RAG + 非 Electron + 官方 deb + 活跃"新候选 = **Askimo**（480★，小，观察仓）。
6. **一年洗牌名单**（详见 §6）：更名/转型一大批——Windsurf→Devin Desktop、Perplexica→Vane（36.9k★）、TriliumNext→Trilium（**砍掉内建 AI**）、Logseq 2.0 DB Beta（告别纯 md）、Msty 企业化、Jan 迁 Tauri；避坑：hollama 桌面已弃、Witsy 停更 5 个月、elia 两年未动。

---

## 一、Linux 桌面 AI 项目全景梯队（2026-09-19 快照）

### 知识库/笔记 × AI（详见 sources/A）

| 项目 | Linux 打包（证据） | UI 栈 | 知识库/RAG | 纯 md 兼容 | 状态 |
|---|---|---|---|---|---|
| **Obsidian** | 官方 AppImage/deb/snap；Flatpak 社区 | Electron（打磨最好\*） | 插件层（Copilot vault RAG） | **vault 即纯 md** | ✅ 1.13.x |
| +obsidian-copilot | 插件 | Obsidian 内 | vault RAG + Claude Code 接入 | vault 即 md | ✅ 4.0.9 周更 |
| +smart-connections | 插件 | Obsidian 内 | 本地嵌入语义浮现 | vault 即 md | ✅ 4.7.2 |
| **basic-memory** | uv 安装（MCP，无 GUI） | 配 Obsidian 同目录 | 图+语义检索，md 为真相源 | **满分** | ✅ v0.23.2 |
| **Cherry Studio** | 官方 deb/rpm/AppImage ×双架构 + CN 版 | Electron | 内建知识库（导入式） | 导入式 | ✅ 52k★ v2.1.0 |
| **AnythingLLM** | 官方仅 AppImage | Electron | LanceDB 全本地 RAG | 导入式 | ✅ 66k★ |
| **Jan** | 官方 deb+AppImage+**Flathub**（最全） | **Tauri**（已迁离 Electron） | 非核心 | 不涉 | ✅ 45k★ |
| **Alpaca** | Flathub 官方 + flatpak 资产 | **GTK4/libadwaita 原生** | 无（Ollama 好壳） | 不涉 | ✅ 1.6k★ 放缓 |
| **Askimo**（新） | 官方 deb ×双架构 | **Kotlin/Compose（非 Electron）** | 本地目录 RAG（BM25+向量） | 只读索引 | ✅ 480★ 日更 |
| Joplin / Zettlr | 官方 AppImage+deb / deb+AppImage+rpm | Electron | 插件级 AI / 无 AI | SQLite / 好 | ✅（不满足 AI 需求） |

### 桌面 LLM 工作台与本地底座（详见 sources/B）

| 项目 | Linux 打包 | UI 栈 | 核心能力 | 状态 |
|---|---|---|---|---|
| **Ollama** | install.sh；**GUI 无 Linux 版**（三重证据） | — | 模型管理事实标准底座 | ✅ 181k★ |
| **llama.cpp** | 官方预编译（CUDA/Vulkan 等）+ 源码 | 自研 WebUI（随 llama-server） | **WebUI 即 MCP host**（v0.4.1），llama.app + Pi 编码 agent | ✅ 129k★ |
| **LM Studio** | 官方 AppImage+deb（应用内更新） | 闭源打磨最好\* | 文档 RAG + MCP（0.3.17+）+ local server | ✅ 0.4.25 |
| open-webui / LibreChat | pip/Docker 自托管 Web | Svelte / Next | 152k★/44k★ 自托管标准答案 | ✅ 日更 |
| Msty Studio | AppImage+deb（官方文档） | Electron 系\* | Knowledge 等；**企业化转型中** | ⚠️ |
| Witsy | deb/rpm/zip | Electron | universal MCP client | ⚠️ 停更 5 个月 |

### AI 编码与终端工具（详见 sources/C）

| 工具 | 形态 | Linux 打包 | 关键点 | 状态 |
|---|---|---|---|---|
| **Claude Code** | 终端 agent | **官方签名 apt/dnf/apk 源**（新增）/brew/npm | 你的方法论资产主体 | ✅ |
| **Zed** | GUI（**Rust 原生非 Electron**） | 官方脚本 + Flathub + tar.gz | **ACP 接 Claude Code/Codex/Gemini CLI/OpenCode**，MCP 可转发 | ✅ 90.5k★ |
| **opencode** | TUI + server + 桌面 BETA | deb/rpm/AppImage + musl | **208k★ 开源第一**，MIT，client/server | ✅ |
| **Crush** | TUI（Charm 系颜值标杆\*） | **deb/rpm/apk/arch/AUR（最全）** | 多会话多模型 + LSP + MCP | ✅ 28k★ |
| Codex CLI / Gemini CLI | 终端 agent | musl 二进制 / npm | 125k★ / 107k★（免费额度） | ✅ |
| **Goose** | CLI + 桌面 App | **deb/rpm/官方 Flatpak** | 通用 agent，Linux 打包最豪华 | ✅ 54k★ |
| Cline / Kilo / Continue | VS Code 插件（+SDK/CLI） | 随 VS Code 系 | 68.7k / 27.4k（承接 Roo）/ 36k 转型 | ✅ |
| claude-code-router / qwen-code | 终端胶水/agent | npm 等 | 国内模型接入刚需 / Qwen 免费额度 | ✅ 37k/28k★ |
| Cursor / Trae / Kiro / Devin Desktop | GUI（闭源） | Cursor AppImage+**deb**；Trae 2026-03 官宣 Linux；Kiro 官网（CLI 支持 musl） | 各有 Linux 但都在"终端化"焦虑 | ✅/⚠️ |
| Neovim：avante / codecompanion / **sidekick**（folke 新作） | 编辑器内 | 随 nvim | sidekick 代表"nvim 绑定外部 CLI agent"路线 | ✅ |

---

## 二、四个问题的"项目映射"（理论见前篇，这里只给工具答案）

| 问题 | 前篇结论（理论） | 本篇工具映射 |
|---|---|---|
| 如何自处 | 验证能力是第一增值技能；AI 是放大器 | **把验证自动化**：Zed+Claude Code（测试/构建反馈闭环）+ llama-server 本地跑评测；终端 agent 时代"照看 agent"本身就是新岗位 |
| 如何高效学习 | 检索练习+间隔重复+公开输出 | **Anki（FSRS）+ anki-llm 造卡**；Obsidian SR 插件复习队列；**Vane**（原 Perplexica，36.9k★）本机自托管 AI 搜索做研究台；Gemini CLI 免费额度跑学习实验 |
| 如何发展规划 | 季度滚动：每周学习时段+每季工具实测 | 本篇即"季度工具实测"的一次执行；推荐组合里每个部件都有替补（§7），换大脑不换壳（ACP） |
| 如何利用 AI | 四层框架 L1–L4 | L1 日常编码=Zed+CC；L2 复杂任务=spec-kit/superpowers（已有）；L3 学习研究=repomix+simonw/llm+Vane；L4 固化=skills/hooks（已有生态） |
| 知识库+AI | basic-memory 主路线+Obsidian 辅 | §三 专章：Linux 桌面端最优解已验证且更清晰 |

---

## 三、知识库 + AI：Linux 桌面端最优解（重点）

**约束**：保住纯 markdown 库（不迁移数据格式）、要好 UI、与 Claude Code 工作流融合、Linux 官方打包。

**首选（验证过的三层组合）**：
1. **知识层**：basic-memory（MCP）指向现有库目录——agent 侧的图+语义检索，AGENTS.md 治理与确认门原样保留；
2. **UI 层**：**Obsidian 官方 Linux 版**（deb/AppImage）打开同一目录——人侧的阅读/写作 GUI；装 obsidian-copilot（vault RAG + **把 Claude Code 拉进 Obsidian**，v4 周更）与 smart-connections（写作时相关笔记浮现）；再配 obsidian-spaced-repetition + anki-llm 补记忆闭环；
3. **问答镜像（可选）**：AnythingLLM Desktop（AppImage，LanceDB 全本地）或 **Askimo**（deb，本地目录 RAG，非 Electron，480★ 新）——只当只读镜像，不做唯一层。

**理由**：这是唯一同时满足「vault=纯 md 目录（零迁移）+ 官方 Linux 包 + 插件级 RAG + Claude Code 集成」的路线；basic-memory 官方 README 明确 Obsidian 就是指定 GUI（同开目录即标准玩法）。**明确不推荐**：Trilium（内建 AI 已于 v0.102 移除 + SQLite 绑架）、Logseq 2.0（DB Beta 数据风险）、hollama（桌面已弃）、Joplin/Zettlr（AI 插件级或无）。

---

## 四、编码工具：终端做大脑、GUI 做壳

**首选组合：Zed + Claude Code（+ Crush 副驾驶）**
- Zed 是 Linux 上唯一"Rust 原生、GPU 渲染、非 Electron"的现代编辑器（90.5k★，当日推送，Flathub 官方）；**ACP 协议让 Claude Code 直接住进 Agent Panel**——你的 skills/hooks/MCP 资产 100% 复用，认证/模型仍归 Claude Code 管，Zed 自带 agent 可不用。
- Claude Code 在 Linux 已"正品化"：官方签名 apt/dnf/apk 源（车机/内网场景比 npm 脚本可控）。
- Crush（28k★）作为第二 TUI：换模型/并行会话/赏心悦目，打包最全（deb/rpm/apk/arch）。

**替补组合（开源/去依赖路线）**：opencode（208k★，MIT，桌面 App BETA）做大脑 + sidekick.nvim 或 codecompanion.nvim（nvim 主力时）+ claude-code-router（把 DeepSeek/Qwen/Ollama 路由进工作流，国内成本刚需）。Zed 同样能经 ACP 接 opencode——**换大脑不换壳**。

**格局判断**：GUI 重型一体机（Cursor/Trae/Kiro/Devin Desktop）各有 Linux 版但均闭源且在被终端 agent 重新定义；Roo Code 关停与 Void 归档是转折的注脚。

---

## 五、本地模型底座：要 UI 就选 llama.cpp

- **Ollama 无 Linux GUI**（download 页仅 install.sh；官方博客明写 app 限 macOS/Windows；v0.34.2 release notes 两处再确认）。它仍是无头底座的事实标准（181k★，所有客户端内置 provider）。
- **llama.cpp（129k★）填上了空位**：`llama-server` 自带 WebUI（会话库/工具调用/**WebUI 即 MCP host**，v0.4.1 实锤），官方新站 llama.app 主打 `llama serve` + Pi 编码 agent 零配置集成；单二进制 MIT 无遥测。局限：无向量 RAG（文件靠投喂与工具读取）。
- **LM Studio**：闭源里对 Linux 最上心（官方 AppImage+deb、应用内更新、MCP、文档 RAG、local server），0.4.25 持续发版；黑盒与许可条款是代价。
- 三种需求的答案：全离线 → llama-server（+AnythingLLM 叠 RAG）；云端 API+中文 → Cherry Studio v2.1.0（deb/rpm/AppImage 双架构+CN 版，Linux 打包最全）；底座+最简 UI → Ollama 无头 + open-webui 容器，或直接 llama-server。

---

## 六、一年洗牌名单（2025-09 → 2026-09，全部 API/官网核实）

**死亡/退场**：Roo Code（2026-05-15 关停，团队转云端 Roomote）、Void（归档）、elia（两年未动）、hollama 桌面版（转纯浏览器后停滞 11 个月）、GPT4All（Nomic 官网已彻底抹除，转行 Agent API）。

**更名/转型**：Windsurf→**Devin Desktop**（Cognition，品牌动荡）；Perplexica→**Vane**（36.9k★ 本地 AI 搜索引擎）；TriliumNext→**Trilium Notes**（37.9k★，但**内建 LLM 已移除**）；Logseq **2.0 DB 版 Beta**（告别纯 md）；Msty→msty.ai 企业化四产品；Jan **迁 Tauri**（Linux 打包最全）；opencode→Anomaly 公司化（208k★）；Goose→aaif-goose org（官方 Flatpak）；Aider/aichat 停更 13–14 个月（理念已普惠，勿作主力）。

**新面孔**：Askimo（480★，Kotlin/Compose，目录 RAG+deb）、sidekick.nvim（folke 新作）、Kilo Code（承接 Roo 用户）、qwen-code、claude-code-router（37k★）。

---

## 七、落地：一套 Linux 桌面 AI 工作站（针对本库主人）

前提：已有 Summary 纯 md 知识库 + Claude Code skills/hooks/MCP 资产。

**一晚上**：
- [ ] Zed：`curl -f https://zed.dev/install.sh | sh`，装 ACP Registry 里的 Claude Code 适配器，验证 skills/hooks 在 Agent Panel 生效
- [ ] Crush：apt 直装，配一个免费/廉价模型当副驾驶
- [ ] llama-server：拉一个本地小模型，体验自带 WebUI 的 MCP 工具调用

**一周**：
- [ ] Obsidian 官方 deb 打开 Summary 目录 → 装 copilot + smart-connections → 验证 vault RAG 与 Claude Code 集成
- [ ] basic-memory project 指向 Summary（若尚未），在 Zed/Claude Code 侧验证图+语义检索
- [ ] AnythingLLM AppImage 或 Askimo deb 装一个当只读问答镜像

**一个月**：
- [ ] Vane 本机 Docker 自托管当研究台，研究成果按 ROUTING 写回 knowledge-base
- [ ] anki-llm → Anki（FSRS）学习闭环跑通第一个主题
- [ ] 季度复盘：按前篇方法对比计时基线，决定是否把大脑换/双持 opencode

---

## 附：来源索引

| 笔记 | 内容 | 核实方式 |
|---|---|---|
| [sources/A-知识库笔记AI客户端.md](./sources/A-知识库笔记AI客户端.md) | 18 条目：Obsidian 官方 Linux + 插件生态、Alpaca、Joplin、Trilium、Logseq、Askimo 等 + 对比矩阵 + 状态变化表 | Release 资产名/Flathub API/AUR RPC 逐项核实 |
| [sources/B-桌面LLM工作台与本地底座.md](./sources/B-桌面LLM工作台与本地底座.md) | Ollama 无 Linux GUI 三重证据、llama.cpp WebUI=MCP host、LM Studio/Jan/Msty/Witsy/Askimo + 三需求推荐 | gh api + releases/latest + 官网/官方博客 |
| [sources/C-AI编码与终端工具.md](./sources/C-AI编码与终端工具.md) | Zed/ACP、opencode 208k★、Roo 关停/Void 归档、Crush/Goose/Codex/Gemini CLI、claude-code-router + 对比矩阵 + 组合推荐 | gh api + releases/latest + 官网，闭源项标注二手来源 |
