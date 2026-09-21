# B 线调研：桌面 LLM 工作台与本地模型底座（Linux 桌面端视角）

- 调研日期：2026-09-19
- 核实方式：开源仓库元数据（stars / pushed_at / archived / license）经 `gh api repos/<o>/<r>` 已认证账号逐项核实；Linux 打包形态逐一读 **releases/latest 资产清单**；README 关键段落走 raw.githubusercontent.com；闭源产品（LM Studio、Msty）与 Ollama 桌面 app 走官网 / 官方博客 + WebSearch 交叉核实。WebSearch 结果为二手信息，已标注。star 数均为查询日快照。
- 与已有调研的关系：D 线《个人知识库AI项目》已核实 AnythingLLM/Cherry Studio/Jan 的知识库与活跃度基线，C 线 §五有本地底座速览——**本篇不重复论证，只补四个增量维度**：① Linux 桌面打包的具体形态与官方程度；② UI 技术栈与观感依据；③ 2026-09 最新状态（版本/新功能）；④ 对"程序员工作流"的适配（MCP、代码块、连接本地模型、编码 agent）。
- 状态标记：✅活跃（近 2 周内有 push/发版）、⚠️放缓/转型/存疑、❌停滞或死亡。

---

## 0. 核心结论速览

1. **Ollama 官方桌面 GUI 至今（2026-09-19）没有 Linux 版**：download 页 Linux 仅 `install.sh`；最新 release v0.34.2（2026-09-15）更新说明两次明写 "desktop app on **macOS and Windows**"。Linux 上想给 Ollama 配 GUI 只能靠第三方客户端（这正是本篇各客户端的机会所在）。
2. **最大新发现：llama.cpp 自带的 WebUI 在 2025–2026 已进化成"底座即工作台"**——v0.4.1（2026-09-14）release notes 实锤 WebUI 持续迭代且**内置 Web UI 本身就是 MCP host**（可挂 MCP server 让纯本地模型调工具）；官方新站 llama.app 主打 `llama serve` + 编码 agent 集成。对 Linux 开发者，"本地底座自带好 UI"这个空位正在被 llama.cpp 而不是 Ollama 填上。
3. **LM Studio 对 Linux 是一等公民**（官方 AppImage + deb、支持应用内更新），闭源免费但有企业级打磨，0.3.17 起支持 MCP，2026 年推出 Bionic agent 与 llmster headless 守护进程。
4. **Jan 已从 Electron 迁到 Tauri**（并布局 iOS/Android），Linux 打包最全（deb + AppImage + 官方 Flathub）。
5. **两个状态变化**：Witsy 仓库已转移至 Kochava-Studios 组织且 v3.5.2（2026-03）后停更；Msty 品牌升级为 msty.ai "Private AI 平台"（Studio/Go/Nexus/Stack 四产品），企业化转型。
6. 新收录：**Askimo**（askimo-ai/askimo，480★，当日活跃，Linux deb，Kotlin/Compose Multiplatform，非 Electron，Chat/RAG/Skills/MCP 全都要）。

---

## 一、本地模型运行底座

### 1. Ollama ✅（底座本身；GUI 无 Linux 版）
- https://github.com/ollama/ollama ｜ **181,261★** ｜ push 2026-09-19（当日）｜ MIT ｜ v0.34.2（2026-09-15）
- **Linux 桌面 app：无。** 三重证据：
  - 官方下载页 Linux 栏目只有 `curl -fsSL https://ollama.com/install.sh | sh`，无 deb/AppImage/Flatpak/GUI（https://ollama.com/download，2026-09-19 核实）；
  - 官方博客《Ollama's new app》（2025-07-30）明写 app "now available for **macOS and Windows**"，功能为模型下载/聊天、文件拖拽（文本+PDF）、多模态（https://ollama.com/blog/new-app）；
  - v0.34.2 release notes（2026-09-15）："Setup completion is shared with the desktop app **on macOS and Windows**"、"`ollama://apps` to open the desktop app's Apps page **directly on macOS and Windows**"（gh api releases/latest）。另有 ollama issue #11609（2025-07 起开放，求跨平台 GUI，社区在等 Linux 版）。
- 2026 新动向：官方博客《Claude Desktop support with Ollama》（2026-08-25）——在 Ollama app 里一键开关，即可把 Claude Desktop 的第三方模型网关配成 Ollama（本地模型 + Ollama Cloud 均可，遥测默认关、宣称 Zero Data Retention）（https://ollama.com/blog/claude-desktop，正文已核实）。**注意：该入口在 Ollama app 里，Linux 用户暂用不上。**
- 对程序员：事实标准底座，OpenAI 兼容 API + 自有 API；几乎每个客户端/agent 框架都内置 Ollama provider。**作为无头服务在 Linux 上无争议；别指望它自带 UI。**

### 2. llama.cpp / llama-server ✅（重点：WebUI 已成"最简底座自带 UI"的事实答案）
- https://github.com/ggml-org/llama.cpp ｜ **128,792★** ｜ push 2026-09-19（当日）｜ MIT ｜ v0.4.1（2026-09-14）
- **WebUI 现状（server README + release notes 核实）**：
  - `llama-server` 启动即在同端口自带 WebUI（`--no-webui` 可关），支持会话管理、对话库（release notes 出现 "export conversations from database"、Chat Messages 渲染性能优化等 UI 专项 PR）；
  - **MCP：内置 WebUI 就是 MCP host**——server 通过 `--tools all` 等暴露内置工具（LLM 可从 WebUI 直接访问本地文件系统），外部 MCP server 的工具以 `<server>_<tool>` 形式"show up in the Web UI and in GET /tools"；v0.4.1 release notes 还在修 "MCP image attachments not displayed in tool blocks"；另有实验性 `--ui-mcp-proxy` 供浏览器直连远程 MCP（https://raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/server/README.md）；
  - 局限：定位是"聊天+工具调用"，**没有向量知识库/RAG**（文件靠投喂与工具读取）；`POST /tools` 端点官方声明内部用、随时可能变。
- **官方新站 llama.app**（2026 上线，"Official home for llama.cpp"）：主打 `llama serve` + **Pi 编码 agent 集成**（装 pi-llama 插件后 agent 自动发现本地模型、零配置），强调无 API key、无遥测（https://llama.app，2026-09-19 核实）。社区口碑：XDA 2026-07 有 "I finally ditched Ollama after using llama.cpp's WebUI" 一类评测（二手，未读原文）。
- 对程序员：单二进制、MIT、性能最激进（并行/batch/量化全可控）；对 Linux 最友好（官方直接发各后端预编译包）。**作为"底座+最简 UI"的一体化选项，2026 年已可当默认答案。**

### 3. LM Studio ✅（闭源免费，Linux 一等公民）
- 官网 https://lmstudio.ai ｜ 当前版本 **0.4.25**（官网下载链接，2026-09-19 核实）｜ License：专有 freeware（企业需付费授权）
- **Linux 打包（官方）**：AppImage + deb 双格式，0.4.22（2026-08-28 changelog）起"Enabled in-app updates for AppImage and .deb installations"；社区 AUR `lmstudio-bin`（装官方 AppImage 到 /opt/lm-studio）（来源：lmstudio.ai changelog 页 + AUR 页，经 WebSearch 二手核实；AUR 非官方）。
- **2026 产品线**：桌面 app（多模型聊天/下载管理）；**Bionic**——"LM Studio's agent for open models"（能建改文档、编码、自动化、控制电脑，官网首页力推，下载链接目前只展示了 Windows 版，Linux 版未见，**未核实**）；**llmster**——headless 守护进程，给服务器/云实例/CI 用（Mac/Linux 走 `install.sh`）。
- **程序员适配**：0.3.17（2025-07，InfoQ 有报道）起支持本地+远程 **MCP server**（编辑 `mcp.json`）；内建 Chat with Documents（文档 RAG）；local server 提供 OpenAI 兼容 API + REST API + 官方 SDK，是"把本地模型喂给任意 agent"的常用本地端点。已知短板：推理服务同一时刻只能"多 LLM 或单 embedding 模型"，不能兼得，做 RAG 后端会别扭（GitHub issue #7713，二手引用）。
- UI 观感（主观）：闭源打磨程度公认最好的一档，窗口/模型库/参数 UI 精致；代价是黑盒与许可条款。

---

## 二、桌面 LLM 工作台 / 客户端

### 4. AnythingLLM Desktop ✅
- https://github.com/Mintplex-Labs/anything-llm ｜ **66,210★** ｜ push 2026-09-19（当日）｜ MIT ｜ v1.16.1（2026-08-27）
- **Linux 打包（官方，release 资产核实）**：`AnythingLLMDesktop.AppImage` + `AnythingLLMDesktop-Arm64.AppImage`（x64/arm64 双架构）+ `installer.sh`。**无 deb/rpm/Flatpak**；Arch 走 AUR（社区）。AppImage 是官方主推形态。
- UI 技术栈：Electron + React。观感为"工作区式文档问答台"，重功能轻装饰（README 截图口径；主观）。
- 能力（D 线基线维持）：LanceDB 全本地向量库、嵌入器可全离线、内置 Ollama 可完全离线；**MCP 兼容**有专门文档（https://docs.anythingllm.com/mcp-compatibility/overview，README 链接核实）；agent + agent skills。
- 对程序员：把 md/代码文档目录批量喂进去即得本地 RAG，是"省心档"首选；不足同 D 线——入库后由内部存储管理，非实时监听目录。

### 5. Cherry Studio ✅（中文生态最热）
- https://github.com/CherryHQ/cherry-studio ｜ **51,986★** ｜ push 2026-09-19（当日）｜ AGPL-3.0 ｜ **v2.1.0**（2026-09-18，大版本已进入 2.x）
- **Linux 打包（官方，release 资产核实，全家桶式最全）**：AppImage / **deb** / **rpm** × x64/arm64；另有 **CN 版**（Cherry-Studio-CN-*）全平台单独发行——对国内网络做了分发优化，这点在同类里独一份。
- UI 技术栈：Electron（electron-vite 工程）。中文用户口碑最好（D 线基线；主观：界面信息密度高、预设助手/翻译/贴纸等本土化细节多）。
- 能力（基线维持 + 本篇核实）：内建知识库（文件/目录/网址导入，嵌入可接 Ollama 本地模型，向量本地存储）、**MCP server 支持**、300+ 预置助手、WebDAV 备份。发版极频繁。
- 对程序员：接云端 API 为主 + 本地 Ollama 为辅的"混血"工作台；知识库体验优于纯聊天客户端，MCP 配置较顺。注意 2.x 属大版本迁移，插件/主题生态有阵痛（未深核）。

### 6. Jan ✅（Linux 打包最完整的开源桌面）
- https://github.com/janhq/jan ｜ **44,560★** ｜ push 2026-09-19（当日）｜ 自定义许可（Apache + 品牌附加条款，NOASSERTION）｜ v0.8.4（2026-07-23）
- **Linux 打包（官方，release 资产 + README 核实）**：`Jan_0.8.4_amd64.deb` + `Jan_0.8.4_amd64.AppImage` + **官方 Flathub（ai.jan.Jan）**；README 还有 Microsoft Store（Win）。三条官方渠道，开源项目里最齐全。
- **UI 技术栈：已从 Electron 迁到 Tauri**（package.json 为 `jan-app` monorepo：`src-tauri`、`yarn tauri build`，且含 `tauri ios/android` target——2026-07 前后完成迁移并布局移动端；这是本次核实到的重大架构变化）。观感：仿 ChatGPT 桌面版布局，轻量、启动快（Tauri 红利；主观）。
- 0.8.4 要点（release notes 核实）：设置与密钥迁移到后端托管存储，secret 进 OS keyring；多语言补全。
- 能力：100% 离线卖点，内置 llama.cpp 引擎；README 功能列表有 "Model Context Protocol: MCP integration for agentic capabilities" 与自定义助手。**知识库/RAG 仍非核心卖点**（基线维持，README 功能清单亦未列 RAG）。
- 对程序员：当"纯本地推理的桌面壳"或备用客户端很稳；文档问答要靠 MCP/外部方案。

### 7. Msty / Msty Studio ⚠️（闭源 freeware，企业化转型）
- https://msty.app → **301 跳转 https://msty.ai**（已核实）；运营主体变为 **CloudStack, LLC**（官网落款核实）
- **2026 产品结构（官网核实）**：四产品——**Studio**（私有 AI 工作台：多模型聊天、知识组织、提示复用）、**Go**（有界任务 agent，桌面/手机可跑、执行可审查）、**Nexus**（中央模型网关：runtimes/providers/keys/策略）、**Stack**（版本化 Knowledge Stacks，标注 Coming soon）。官网通篇强调 SSO/审计日志/自控存储/零遥测，SOC 2 与 ISO 27001 "not yet complete"——**明显的企业化/合规化转型**。
- **Linux 打包**：Msty Studio Desktop 官方支持 Win/Mac/**Linux**，官方文档提供 **AppImage + deb** 安装说明（docs.msty.ai Quick Start + docs.msty.app "Install Msty on Linux"，经 WebSearch 二手核实）；AUR `msty-studio-bin`（社区，2026-05 仍在更新）。传统 Msty 桌面版与 Studio 的边界已模糊，官网已不设独立"legacy Msty"入口（未深核）。
- 能力：内置 Ollama/llama.cpp 便于全本地；Knowledge（知识栈）可用；**MCP：官网未提**，历史版本支持 MCP（2025 口碑），2026 现状未核实。
- 风险：闭源 freeware + 转向企业市场——个人桌面端的持续投入优先级存疑（⚠️ 依据：官网产品叙事全部指向 enterprise deployment）。UI 观感口碑一向好（Electron 系精致派；主观）。

### 8. Witsy ⚠️（停更 + 仓库易主）
- **https://github.com/Kochava-Studios/witsy**（原 nbonamy/witsy，经 GitHub API 核实为原始仓库转移：created 2024-04-25 未变，2,025★，最后 push **2026-04-23**）｜ Apache-2.0 ｜ 最新版 **v3.5.2（2026-03-04）**
- 作者在 2026-04-14 另建了新的空壳 `nbonamy/witsy`（8★，仅一日 push）——本人账号下已非主仓库（两仓库元数据对比核实）。转移原因（是否被 Kochava 收购/雇佣）未核实。
- **Linux 打包（release 资产核实）**：`witsy_3.5.2_amd64.deb` + `witsy-3.5.2-1.x86_64.rpm` + linux-x64 zip。UI 技术栈 Electron。卖点是 BYOK + **"universal MCP client"**（repo 描述原话）+ 写作助手/语音/桌面自动化。
- 状态判定：**5 个月无 push、3 个月无 release，⚠️ 停更边缘**。不建议新上车；其"universal MCP client"定位已被 Cherry Studio/Askimo 等覆盖。

### 9. Askimo ✅（本次新发现，小而活跃）
- https://github.com/askimo-ai/askimo ｜ **480★** ｜ push 2026-09-19（当日）｜ AGPL-3.0 ｜ v1.5.1（2026-09-17）
- 定位（README 原话核实）："One app. Every AI model. Your files stay local."——Chat / 搜索本地文件与网页 / 运行脚本 / 多步 AI 工作流 / **agent skills**，"all offline-capable"。Provider 列表：Anthropic/OpenAI/Gemini/Grok/**Ollama/LM Studio/vLLM**/OpenRouter/NVIDIA NIM/任意 OpenAI 兼容端点；另有 RAG、MCP 工具、agents（repo 描述原话）。
- **Linux 打包（release 资产核实）**：`Askimo-Desktop-linux-x64.deb` + arm64 deb + 裸 jar。
- **UI 技术栈：Kotlin / Compose Multiplatform（JVM）——本篇唯一非 Electron/非 Web 套壳的桌面客户端**，观感与资源占用与 Electron 系不同（未实机验证，主观留白）。
- 对程序员：脚本执行 + skills + MCP 的组合拳适合自动化党；缺点：体量小（480★）、文档与生态薄弱，作观察仓或轻量备胎。

### 10. GPT4All 后继 ❌
- Nomic 官网（https://nomic.ai，2026-09-19 核实）**已彻底不见 Atlas 与 GPT4All**：现仅 "Nomic Platform"（面向 AEC 建筑工程行业的 agentic 平台）与 "Agent API"（搜索/文档理解/图纸解析模型）。一句话：**GPT4All 无后继，Nomic 已转行**。

---

## 三、自托管 Web（跑在本机 ≈ 桌面端选项，只更新变化）

- **open-webui** ✅：152,529★，push 当日，最新 v0.11.3（2026-08-31，gh api 核实）。自托管 LLM WebUI 事实标准；Linux 本机 `pip install open-webui` 或 Docker 即得——对不开桌面 app 的开发者，这就是"桌面客户端"。Knowledge 集合（混合检索）能力维持基线描述。
- **LibreChat** ✅：44,364★，push 当日，MIT，tag v0.8.8-rc3（gh api 核实）。多模型前端 + RAG API + agents/MCP 维持基线；无 GitHub releases（用 tag/rc 流程），个人优先级仍低于 open-webui。

---

## 四、对比矩阵（2026-09-19 快照）

| 项目 | Linux 打包（官方程度） | UI 技术栈 | 知识库/RAG | MCP | 本地模型 | 活跃度 |
|---|---|---|---|---|---|---|
| Ollama（底座） | install.sh（官方）；**桌面 GUI 无 Linux 版** | —（无 GUI） | — | —（API 层） | ✅ 原生 | ✅ 181k★ 日更 |
| llama.cpp（底座+UI） | 官方预编译（CUDA/Vulkan/SYCL 等）+ 源码；WebUI 随二进制 | 自研 WebUI（随 llama-server 分发，非 Electron） | ⚠️ 文件投喂+工具读取，无向量 RAG | ✅ WebUI 即 MCP host（v0.4.x 实锤） | ✅ 原生 | ✅ 129k★ 日更 |
| LM Studio | **AppImage + deb（官方，应用内更新）**；AUR 社区 | 闭源 Electron 系（社区口径，未官方声明） | ✅ Chat with Documents | ✅ 0.3.17 起（本地+远程） | ✅ 内置 llama.cpp/MLX | ✅ 0.4.25 持续发版 |
| AnythingLLM | AppImage x64/arm64 + installer.sh（官方；无 deb） | Electron | ✅ LanceDB 全本地 | ✅ | ✅ 内置 Ollama | ✅ 66k★ 日更 |
| Cherry Studio | **AppImage + deb + rpm × x64/arm64 + CN 版（官方）** | Electron | ✅ 内建知识库 | ✅ | ✅ Ollama/LM Studio provider | ✅ 52k★ 日更 v2.1.0 |
| Jan | **deb + AppImage + 官方 Flathub（官方，最全）** | **Tauri**（已迁离 Electron，布局移动端） | ⚠️ 非核心 | ✅ | ✅ 内置 llama.cpp | ✅ 45k★ 日更 v0.8.4 |
| Msty Studio | AppImage + deb（官方文档）；AUR 社区 | Electron 系（社区口径） | ✅ Knowledge（Stack 版 coming soon） | ⚠️ 官网未提，历史支持 | ✅ 内置 Ollama/llama.cpp | ⚠️ 企业化转型中 |
| Witsy | deb + rpm + zip（v3.5.2，2026-03） | Electron | ✅ 知识库 | ✅ universal MCP client | ✅ Ollama 等 | ⚠️ 停更边缘（5 个月无 push，仓库易主 Kochava-Studios） |
| Askimo | deb x64/arm64 + jar（官方） | **Kotlin/Compose Multiplatform** | ✅ 文件/网页检索 | ✅ | ✅ Ollama/LM Studio/vLLM | ✅ 日更（480★，小） |
| open-webui | pip / Docker（自托管 Web） | Svelte Web | ✅ Knowledge | ✅ | ✅ Ollama 等 | ✅ 152k★ v0.11.3 |
| LibreChat | Docker（自托管 Web） | React/Next Web | ✅ RAG API + pgvector | ✅ agents/MCP | ✅ | ✅ 44k★ 日更 |

> GPT4All 已从矩阵剔除（❌ 停更 15 个月+，Nomic 转行，见 §10）。

---

## 五、推荐：按三种需求各给一条首选

### ① 本地优先（全离线）→ 首选 **llama.cpp（llama-server 自带 WebUI）**，知识库刚需再叠 AnythingLLM
- 理由：Linux 上唯一"单二进制 = 底座 + 能打的 UI"组合：MIT、无遥测、官方各 GPU 后端预编译；2026 年的 WebUI 已是 MCP host + 工具调用 + 对话库（v0.4.1 release notes 核实），且官方 llama.app 直接把 `llama serve` 对接编码 agent（Pi 插件零配置）——与用户 Claude Code 类 agent 工作流天然同构。Ollama 在 Linux 恰恰无 GUI，此生态位被 llama.cpp 占住。
- 叠加：要"目录喂进去出 RAG"时加 AnythingLLM AppImage（LanceDB 全本地、MIT），它可直接连 llama-server/Ollama 的 OpenAI 兼容端点。
- 代价：llama-server 无向量知识库；WebUI 功能是"聊天+工具"级，不追求华丽。

### ② 云端 API 优先 + 中文生态 → 首选 **Cherry Studio（v2.1.0）**
- 理由：Linux 打包在中文系客户端里最全（deb/rpm/AppImage × 双架构 + 专门的 CN 发行版）；内建知识库 + MCP + 300+ 预设助手，中文社区最热（52k★、当日 push、发版极频）。对"日常主用云端大模型、偶尔拉本地 Ollama"的使用画像，开箱体验最好。AGPL 对个人自用无碍。
- 备选：轻量纯聊天用 chatbox（D 线基线：知识库弱）；要企业风工作台可试 Msty Studio（闭源风险自担）。

### ③ 底座 + 最简 UI → 首选 **Ollama（无头底座）+ 其生态客户端**；若追求零依赖则 llama-server 一把梭
- 理由：Ollama 在 Linux 无官方 GUI（download 页 + v0.34.2 release notes 三重核实），但它仍是"模型管理事实标准"——181k★、每家客户端内置 provider、2026-08 起官方打通 Claude Desktop 网关（macOS/Win 侧）。Linux 上的正确姿势是：`install.sh` 装底座拉模型，UI 按需选最简一层——想最省事就 `ollama serve` + open-webui 本机容器（152k★ 自托管标准答案）；想零 Docker 就直接用 llama-server 的自带 WebUI，或 Jan（Tauri、Flathub 一键装、接 Ollama）。
- 一句话：**"底座选 Ollama 还是 llama.cpp"在 Linux 上已等价于"要不要 UI"——要官方 GUI 就只剩第三方；要自带 UI 就选 llama.cpp。**

---

## 附：本次未核实/存疑项清单
- LM Studio「Bionic」的 Linux 可用性（官网仅见 Windows 下载入口）；LM Studio UI 栈（Electron）为社区口径。
- Msty Studio 2026 版的 MCP 支持现状、legacy Msty 与 Studio 的并行情況。
- Witsy 仓库转移至 Kochava-Studios 的原因（收购/雇佣/代管）。
- Jan 迁移 Tauri 的具体版本节点（由 package.json 与 0.8.x 发布物推断，未读迁移公告）。
- XDA 等媒体对 llama.cpp WebUI 的口碑评测（仅搜索摘要，未读原文）。
