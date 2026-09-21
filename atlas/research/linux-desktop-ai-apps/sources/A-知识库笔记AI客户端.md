# A 线调研：Linux 桌面端「知识库/笔记 × AI」客户端深挖

- 调研日期：2026-09-19
- 与 D 线（`ai-era-programmer/sources/D-个人知识库AI项目.md`，40 项目梯队）的关系：**不重复论证基线结论**，只做四个增量维度——① Linux 打包形态落到证据（Release 资产名 / Flathub API / AUR RPC 逐项核实）；② UI 技术栈与观感口碑（标注"观感为主观评价"处均为主观判断）；③ 2026-09 最新状态变化；④ 与用户现有纯 markdown 库（`~/Project/MyProject/Summary`，AGENTS.md 治理）的兼容性。
- 核实方式：`gh api repos/<o>/<r>`（stars/pushed_at/archived）+ `releases/latest` 资产名清单；`obsidian.md/download` 页面原文；Flathub `api/v2/appstream/<id>` 探测（HTTP 200=存在 / 404=未找到）；AUR `rpc?v=5`（包名/版本/票数）；UI 口碑来自 FOSS Force、Reddit（r/selfhosted、r/linux）等 WebSearch 结果。star 数均为查询日快照。
- 状态标记：✅活跃（近 2 周内有 push）、⚠️放缓/转型、❌停滞或归档。

---

## 一、Obsidian 官方 Linux 版 + AI 插件生态（必查 1）

### 1. Obsidian（非开源，事实标准的 md vault）✅
- https://obsidian.md/download ｜ 当前版本 1.13.x ｜ 闭源免费，个人使用无费用
- **Linux 打包（官方页面原文核实）**：官方直接提供 **AppImage（x64 + ARM64）、.deb（amd64）、Snap**；另列 **Flatpak，但明确标注 "Community maintained"**（Flathub `md.obsidian.Obsidian`，探测 HTTP 200）。**无官方 rpm**。注意：GitHub `obsidianmd/obsidian-releases` 的 release 只挂 Android APK，桌面分发全在官网（该 repo 本身 21,687★，push 当日，承载社区插件清单）。
- AUR：`obsidian-bin` 1.13.7-2（5 票，社区）。
- UI：Electron，但打磨度/主题生态在 md 笔记里最好（观感为主观评价）；Linux 上长期是 md 库用户的默认 GUI。
- **md 兼容性：满分。vault 就是纯 md 目录**，与用户的 Summary 库零迁移；AGENTS.md 等非 md 附属文件不受影响（只是不被渲染）。

### 2. logancyang/obsidian-copilot ✅活跃（基线第一梯队，状态更新）
- https://github.com/logancyang/obsidian-copilot ｜ **7,738★**（较 D 线 +2）｜ push 2026-09-19（当日）｜ AGPL-3.0
- 最新版 **4.0.9（2026-09-16）**，v4.x 保持周更（4.0.5→4.0.9 全在 2026-09 内，release 资产核实）。定位 "Agents for your Obsidian vault"：vault RAG（本地/云端嵌入，付费 Copilot Plus 用本地索引器 **Miyo**）+ 把 Claude Code/Codex CLI/opencode 拉进 Obsidian。
- 2026-04 的第三方对比（systemsculpt.com）与 smartconnections.app 官方对比页均把 **Copilot 与 Smart Connections 列为双雄**，无新头部插件取代（2026-06 shadow.do 榜单前五仍是这批：Copilot、Smart Connections、Smart Composer、Text Generator、Local GPT）。
- 分发：Obsidian 社区插件（无需独立安装包）。

### 3. brianpetro/obsidian-smart-connections ✅活跃
- https://github.com/brianpetro/obsidian-smart-connections ｜ **5,460★** ｜ push 2026-09-16 ｜ 自定义 source-available（GitHub 识别 NOASSERTION）
- 最新版 4.7.2（2026-08-06，release 资产核实）。零配置本地嵌入、写作时相关笔记浮现 + Lookup 语义搜索。单作者 v4 大重构，issue 积压仍多（~493）。定位是"检索/反哺写作层"而非问答 RAG。

### 4. Obsidian 其他 AI 插件速览（gh api 核实）
- **nhaouari/obsidian-textgenerator-plugin** ｜ 1,986★ ｜ push 2026-08-06 ✅（模板化 AI 生成，老牌）
- **pfrankov/obsidian-local-gpt** ｜ 679★ ｜ push 2026-05-02 ⚠️放缓（Ollama 本地接 入 Obsidian，轻量）
- **SystemSculpt/obsidian-systemsculpt-ai** ｜ 194★ ｜ push 2026-09-18 ✅（活跃但规模小，非头部）
- **glowingjade/obsidian-smart-composer** ｜ 2,328★ ｜ push 2026-02-16 ⚠️维护模式（与 D 线结论一致，无变化）

---

## 二、独立桌面客户端（必查 2–7 + 新发现）

### 5. fmaclen/hollama ❌桌面端已停（对任务书假设的纠正）
- https://github.com/fmaclen/hollama ｜ 1,186★ ｜ **最后 push 2025-10-12（≈11 个月无动静）** ｜ MIT
- **纠正**：它早已不是 Tauri。0.35 时代是 **SvelteKit + Electron**（electron-builder 产物），2025-10 起整体转向**纯浏览器 PWA**（README/package.json 首句 "runs entirely in your browser"，`hollama.fernando.is` 有在线 demo）。
- **打包证据**：release 0.35.4 / 0.35.3 / 0.35.2（2025-10）**资产为空，无任何桌面包**；最后带桌面包的是 0.35.1（2025-07-20，Linux 仅 `tar.gz`，无 deb/AppImage）。AUR `hollama-bin` 停在 0.35.1。
- 知识库能力：有 "knowledge" 知识条目/上下文功能（README 截图矩阵含 knowledge 页），但是**手动粘贴级**，非目录 RAG。
- 结论：❌ 不建议投入；曾以极简 UI 获好评（观感为主观评价），现桌面路径已放弃。

### 6. Jeffser/Alpaca ✅活跃（Linux 原生 UI 独苗）
- https://github.com/Jeffser/Alpaca ｜ 1,638★ ｜ push 2026-08-03 ｜ GPL-3.0
- **UI：GTK4 + libadwaita，GNOME 原生观感**——本次调研范围内唯一原生技术栈的 AI 客户端（其余全是 Electron/Tauri/浏览器）。README 明确接受 GNOME CoC，且**明文拒绝 AI 生成的 issue/PR**。
- **打包**：Flathub `com.jeffser.Alpaca`（HTTP 200，官方主渠道，README 挂 Flathub 徽章）；GitHub release 9.2.5（2026-08-03）附官方 `com.jeffser.Alpaca.flatpak` 资产；AUR `alpaca-ai` 9.2.5-1（13 票，版本与上游同步）。无官方 deb/rpm。
- 知识库/RAG 真实能力：**无**。功能为多模型同对话、图像识别、**纯文本文档识别**、YouTube/网页转写问答、Web 搜索、接 OpenAI 兼容云 API——是"Ollama 的好壳"，不是知识库层。
- 活跃度：✅但节奏放缓——release 间隔从 2026-02–03 的密集变为 9.2.3（04-02）→9.2.4（06-26）→9.2.5（08-03），约 1–2 月一发，单维护者。
- 口碑：FOSS Force《Alpaca Makes Running Ollama on Linux a Breeze》(2025-08-28)；Fedora/Zorin 论坛用户好评"让 Ollama 有 GUI 变得非常简单"；TUXEDO OS 官方博客收录（观感为主观评价：GNOME 用户视角观感一流，功能深度不如 Cherry Studio/AnythingLLM）。

### 7. laurent22/joplin ✅活跃（基线未深挖，本线补全）
- https://github.com/laurent22/joplin ｜ **56,439★** ｜ push 2026-09-19（当日）｜ License：GitHub 检测为 Other/NOASSERTION（历来 AGPL-3.0，LICENSE 原文本次未逐字核验）
- 最新版 v3.7.18（2026-09-11），周/双周发版。
- **AI 现状（WebSearch 核实）**：**无内建全套 AI 助手**。官方仓库有 `joplin/plugin-ai-summarisation`（笔记摘要）；插件市场有 AI Note Assistant（io.chatonnotes.joplin，2026-03，笔记问答/摘要）、Jarvis 等；官方论坛仍在讨论原生 AI 方向。Joplin Cloud 付费档含 AI 能力。**AI 生态 = 插件级，弱于 Obsidian。**
- **打包**：官方 release 资产含 **`.AppImage` + `.deb`**（v3.7.18 核实，x64；ARM64 仅有 mac/win）；Flathub `net.cozic.joplin_desktop`（HTTP 200，社区）；AUR `joplin-desktop`（**288 票**，本调研 AUR 票数最高者）；另有 snap。
- 数据：自有 SQLite 存储（+ md 导入导出），**不是纯 md 目录**——对用户的库是"导入/导出"关系。
- UI：Electron，三栏经典布局；2026-06 usevoicy《Best Linux Note-Taking Apps》评其为"对多数人是最佳 Linux 笔记应用"（观感为主观评价：稳重但朴素）。

### 8. Trilium Notes（TriliumNext/Trilium）✅活跃（两处重大状态变化）
- https://github.com/TriliumNext/Trilium ｜ **37,901★** ｜ push 2026-09-19（当日）｜ AGPL-3.0
- **变化一（身份）**：zadam/Trilium 停更后的社区续作已从 `TriliumNext/Notes`（2,926★，**archived=true**，2025-06-24 归档）**迁移并更名 Trilium Notes**，规模暴涨至 37.9k★，主力 repo 今日仍在 push。
- **变化二（AI）**：**内建 LLM 集成已在 v0.102.0 移除**（2026-02-27 官方 issue #8797 公告，感谢贡献者 perfectra1n；当前最新 v0.105.0，2026-08-19）。即：**如今 Trilium 没有任何内建 AI 能力**，官方文档的 AI 页转向外部方案。
- **打包（本调研最全）**：官方 release 资产含 **AppImage / .deb / .rpm / .flatpak / .zip（x64 + arm64 全套）** + 独立 Server tar.xz + Docker（`triliumnext/trilium`）；AUR `trilium-next` 0.105.0。
- 数据：**SQLite，非纯 md**（md 导入导出）——引入即绑架现有库。
- UI 口碑：两极。r/selfhosted："IMO the best truly open note taking software"、"arguably the most feature packed"；另一侧 r/linux、r/Trilium："UI isn't that polished"、"rough around the edges"、"overly complicated"（观感为主观评价：功能怪兽，颜值中等，学习曲线陡）。
- 适用性：对"要好 UI + 保 md 库"的用户是**负匹配**：数据绑架 + AI 刚被砍。

### 9. silverbulletmd/silverbullet ✅活跃（自托管，纯 md 存储）
- https://github.com/silverbulletmd/silverbullet ｜ **6,097★** ｜ push 2026-09-18 ｜ MIT
- 定位：自托管"个人生产力平台"，**空间（space）就是纯 markdown 文件目录**，Lua 脚本扩展；形态是 Deno 单二进制/Docker 的 **Web 服务 + PWA**（可装到桌面当 app 用），不是传统桌面程序。
- AI：官方无内建；社区插 **justyns/silverbullet-ai**（105★，push 2026-09-15 ✅，LLM 聊天/打标/补全，接 OpenAI 兼容 API）。插件级、规模小。
- UI：自研、键盘驱动、极客向，与 Obsidian 观感差异大（观感为主观评价：爱者极爱，需适应）。
- md 兼容性：存储层满分（纯 md 文件夹），但"自托管服务"的日常成本高于本地 AppImage。

### 10. Zettlr/Zettlr ✅活跃（无 AI）
- https://github.com/Zettlr/Zettlr ｜ 13,527★ ｜ push 2026-09-18 ｜ GPL-3.0
- 最新版 v4.8.0（**2026-09-18 当日发版**，release 资产核实）。
- **AI 现状：无内建 AI 功能**，停留在社区需求讨论（forum.zettlr.com 2024-12 "AI Writing Tools for Zettlr?" 帖，无 shipped 特性）——作者刻意保持学术写作工作台的克制。
- **打包**：官方 release 资产含 **.deb / .AppImage / .rpm（x64 + arm64 全套）**；AUR 仅有 `zettlr-git`（3 票，版本停在 3.1.0-beta，维护陈旧）。
- 数据：纯 md 文件夹 + 项文件，兼容性好；但无 AI 层，定位（学术出版写作）与"知识库 + AI"需求错位。

### 11. logseq/logseq ✅活跃（重大状态变化：DB 版落地为 Beta）
- https://github.com/logseq/logseq ｜ 44,979★ ｜ push 2026-09-19（当日）｜ AGPL-3.0
- **状态变化（vs D 线"DB 版推进多年未落定"）**：**Logseq 2.0（DB 版）Beta 已于 2026-07-13 发布（tag 2.0.1）**，官方 README 声明 DB 版处于 beta、新移动端与 RTC 处于 alpha，"数据丢失是可能的，建议自动备份"（WebSearch 多源汇总，含 discuss.logseq.com 时间线佐证）。
- 关键含义：**DB 版不再是纯 markdown 文件存储**——Logseq 对"保住纯 md 库"的用户从"兼容但要外挂 AI"变为"格式绑架"，且内建 AI 仍弱（插件生态）。继续观察即可，不上车。

### 12. siyuan-note/siyuan ✅活跃（一句话）
- 46k★ 级，push 当日；内建 AI（写作助手/AI 编排）；v3.8.4（2026-09-17）官方 release 含 **AppImage/.deb/.rpm/.tar.gz（x64+arm64）**，AUR `siyuan-bin`（21 票）；数据 `.sy` JSON 块库非纯 md，引入即绑架库——仅作观察（D 线结论不变）。

### 13. CherryHQ/cherry-studio ✅活跃（仅补 Linux 打包证据）
- 51,986★，push 当日，AGPL-3.0。v2.1.0（2026-09-18）官方 release 资产：**AppImage/.deb/.rpm（x64 + arm64 全套，国际版与 CN 版双轨）**——国内分发非常省心。Flathub：探测 `com.cherry_studio.cherry-studio` 与 `com.cherry-studio.cherry-studio` 均 HTTP 404，**未检索到 Flathub 包**。AUR `cherry-studio-bin`（21 票）。UI：Electron，功能密度极高，中文体验第一梯队（观感为主观评价：偏"工作台"，信息密度大）。

### 14. Mintplex-Labs/anything-llm ✅活跃（仅补 Linux 打包证据）
- 66,210★，push 当日，MIT。v1.16.1（2026-08-27）官方 release 资产：Linux **仅 `AnythingLLMDesktop.AppImage`**（+ Arm64.AppImage 与 installer.sh），**无官方 deb/rpm**；AUR `anythingllm-appimage` 1.12.1（2 票，落后上游 4 个小版本）。UI：Electron，简洁克制（观感为主观评价：三者中最"干净"，但功能少于 Cherry Studio）。

---

## 三、知识层 × UI 的接法（必查 8）

### 15. basicmachines-co/basic-memory ✅活跃（配 UI 的最佳方式）
- 3,994★，push 2026-09-16，v0.23.2（2026-08-25），AGPL-3.0。能力面见 D 线 #29，不重复。
- **配 UI 的官方答案（README 客户端表核实）**：
  - **本地免费形态没有自己的 GUI**——官方明确 **[Obsidian] 直接读写同一批 Markdown**（transport 一栏为"—"，即零集成成本）：把 basic-memory 的 project 指向你的知识库目录，Obsidian 打开同一目录，人写 md、agent 走 MCP 写 md，双向同步。第三方教程（samuellawrentz.com "Basic Memory MCP + Obsidian"）验证此为标准玩法。
  - 付费 Cloud（basicmemory.com）才有官方 web/移动端与跨设备同步（可选，非必需）。
  - **未发现任何成熟的第三方独立 GUI**（WebSearch 仅命中教程与 mcp.so 等目录页）——"未核实到"即为结论：现阶段 GUI = Obsidian（或任意 md 编辑器）。
- 对本用户：与 Obsidian 同开目录 = 「知识层（图+语义检索，给 agent）+ UI（阅读/写作，给人）」的最优组合，且保住纯 md。

---

## 四、2025–2026 新发现（必查 9–10）

### 16. askimo-ai/askimo ✅活跃（新发现，小而快）
- https://github.com/askimo-ai/askimo ｜ 480★ ｜ **push 2026-09-19（当日）** ｜ AGPL-3.0 ｜ 最新 v1.5.1（2026-09-17）
- 定位：桌面 AI 客户端——多 LLM 聊天（Anthropic/OpenAI/Ollama/Gemini…）+ **本地 RAG（索引本地文件夹/文件/URL，BM25+向量混合检索，全本地）** + MCP（stdio/HTTP）+ skills/agent CLI。
- **UI 技术栈：Compose Multiplatform（Kotlin，非 Electron）**——桌面 AI 客户端里的稀有技术选择；Linux 打包：官方 release 资产 **`.deb`（x64 + arm64，jpackage）**，无 AppImage/rpm。
- 口碑/观感：规模尚小（480★），无成型社区口碑，UI 观感未核实（本次未亲测截图）；Java 系字体渲染在部分发行版上有历史怨言（主观预期，未核实）。
- 价值：**"本地文件夹 RAG + 好 UI + Linux deb + 当日活跃"** 四项同时成立的最年轻候选；与 Cherry Studio 的差异是**按目录建索引**而非导文件入库。风险：单一小团队、生态未成型。

### 17. Witsy（Kochava-Studios/witsy）⚠️放缓（新发现，谨慎）
- https://github.com/Kochava-Studios/witsy ｜ 2,025★ ｜ **最后 push 2026-04-23（≈5 个月）** ｜ 最新 v3.5.2（2026-03-04）
- 桌面 AI 助手 / 通用 MCP 客户端（Electron），原 nbonamy/witsy 迁移而来（旧 repo 已重置，8★）。曾以内置 RAG/knowledge 宣传（该细节未逐项核实）。
- **打包**：官方 release 资产含 **`.deb`、`.rpm`、`.zip`（linux-x64）**；AUR `witsy` 3.5.2。
- 状态：⚠️ 半年无 release；发现价值有限，仅作记录。

### 18. Perplexica → **Vane**（ItzCrazyKns/Vane）✅活跃（必查 10，一句话定位）
- https://github.com/ItzCrazyKns/Vane ｜ **36,877★** ｜ push 2026-09-01 ｜ MIT
- **已由 Perplexica 更名 Vane**（旧名链接重定向）："privacy-focused AI answering engine"——本地硬件可跑的 AI 搜索/研究引擎（Ollama 或云端模型 + SearxNG 检索 + 引用来源 + 图片/视频搜索），Docker 自托管 Web（`itzcrazykns1337/vane`）。**不是笔记/知识库工具**：作为本机"AI 研究台"与 md 库配合使用（研究成果写回 md）。

---

## 五、对比矩阵

| 项目 | Linux 打包（证据） | UI 栈 | 知识库/RAG 真实能力 | 纯 md 库兼容 | 活跃度（2026-09-19） |
|---|---|---|---|---|---|
| Obsidian（官方） | AppImage/deb/snap 官方；Flatpak 社区；无 rpm | Electron（打磨好*） | 插件层：Copilot vault RAG / SC 语义浮现 | **vault 即纯 md，满分** | ✅ 1.13.x 持续发版 |
| obsidian-copilot | 社区插件，无独立包 | Obsidian 内 | vault RAG + Claude Code 接入 | vault 即 md | ✅ 4.0.9（09-16），周更 |
| smart-connections | 社区插件 | Obsidian 内 | 本地嵌入语义检索（非问答 RAG） | vault 即 md | ✅ 4.7.2（08-06） |
| Cherry Studio | AppImage/deb/rpm 官方（双轨）；AUR；Flathub 未检出 | Electron | 内建知识库（导入入库，非实时目录） | 导入式，弱分叉 | ✅ 2.1.0（09-18） |
| AnythingLLM | 官方仅 AppImage；AUR 陈旧 | Electron | LanceDB 本地 RAG（目录批量导入） | 导入式，弱分叉 | ✅ 1.16.1（08-27） |
| basic-memory | pip/uv（MCP+CLI），无 GUI | （配 Obsidian 同目录） | 图+语义混合检索，md 为真相源 | **满分（不改文件）** | ✅ v0.23.2（08-25） |
| Alpaca | Flathub 官方 + flatpak 资产；AUR alpaca-ai；无 deb/rpm | **GTK4/libadwaita 原生** | 无（仅纯文本文档识别） | 不涉（不管理笔记库） | ✅ 9.2.5（08-03），节奏放缓 |
| Joplin | 官方 AppImage+deb；Flathub/AUR 社区 | Electron | 插件级 AI（无内建全套） | SQLite 存储，导出 md | ✅ 3.7.18（09-11），周更 |
| Trilium Notes | **AppImage/deb/rpm/flatpak 全套（x64+arm64）+Docker** | Electron | **内建 LLM 已于 v0.102 移除** | SQLite，绑架库 | ✅ 0.105.0（08-19） |
| SilverBullet | 自托管（Deno/Docker）+PWA | 自研 Web（键盘驱动*） | 社区插 silverbullet-ai（小） | **存储即纯 md** | ✅ 09-18 push |
| Zettlr | 官方 deb/AppImage/rpm 全套；AUR 陈旧 | Electron | 无 AI（讨论阶段） | 好（纯 md 文件夹） | ✅ 4.8.0（09-18） |
| Logseq | 官网下载（本次未逐项核包名） | Electron | AI 弱（插件） | **DB 版 Beta 后绑架库** | ✅ 但 2.0 Beta 数据风险 |
| 思源 siyuan | AppImage/deb/rpm/tar.gz 官方；AUR | 自研（非 Electron） | 内建 AI | `.sy` 块库，绑架 | ✅ 3.8.4（09-17） |
| hollama | ❌ 0.35.2 起 release 无资产；最后桌面包 0.35.1 tar.gz | SvelteKit+Electron→**转纯浏览器** | 手动 knowledge 条目 | 不涉 | ❌ 停滞 11 个月 |
| Askimo（新） | 官方 deb（x64+arm64）；无 AppImage | **Compose Multiplatform（Kotlin）** | 本地目录 RAG（BM25+向量） | 只读索引，不迁移 | ✅ v1.5.1（09-17） |
| Witsy（新） | 官方 deb/rpm/zip | Electron | 内置 RAG（细节未核实） | 不涉 | ⚠️ 停滞 5 个月 |
| Vane（原 Perplexica） | Docker（Web 自托管） | Web | 联网问答引擎（非笔记 RAG） | 产出可写回 md | ✅ 09-01 push |

\* 观感为主观评价。

## 六、状态变化速查（vs 上一轮 D 线基线）

| 项目 | 变化 |
|---|---|
| Trilium 续作 | 迁移更名 **TriliumNext/Trilium（Trilium Notes）**，37.9k★；旧 TriliumNext/Notes 归档 |
| Trilium AI | **内建 LLM 于 v0.102.0 移除**（2026-02 公告）——"Trilium 有 AI"已不成立 |
| Logseq | DB 版 **2.0 Beta 落地（2026-07-13）**，正式告别纯 md 存储，beta 期有数据丢失警告 |
| Perplexica | **更名 Vane**，口号升级为 privacy-focused answering engine |
| hollama | **放弃桌面打包**（0.35.2 起 release 无资产），转纯浏览器 PWA，随后整体停滞 |
| Witsy | 仓库迁至 Kochava-Studios，**5 个月无 push** |
| obsidian-copilot / smart-connections | 无变化，仍为 Obsidian AI 双雄，前者的 Claude Code 集成路线持续强化 |
| Khoj / Reor / GPT4All / Smart Composer | 与 D 线一致，无恢复迹象 |

## 七、推荐（针对：已有纯 md 库 + Claude Code 重度 + 要好 UI + Linux 桌面）

**首选：Obsidian 官方 Linux 版 + obsidian-copilot（+ basic-memory 同目录并开）**
- 理由：唯一同时满足「vault=纯 md 目录（零迁移）、官方 AppImage/deb 直装、插件级 vault RAG + 把 Claude Code 拉进 GUI」的方案；Copilot v4 周更、嵌入索引留本机；Obsidian 同时充当 basic-memory 的人侧 GUI（官方推荐的接法），你的 AGENTS.md 治理原样保留。若要 Linux 原生观感，这一层无法满足（Electron），但换来的是最完整的生态与 md 兼容。
- 操作面：官网 AppImage/deb 二选一 → 打开 Summary 目录为 vault → 装 copilot（vault QA + Claude Code 集成）与 smart-connections（写作反哺）。

**替补一（Linux 原生 UI 党）：Alpaca**
- 理由：GTK4/libadwaita 原生观感在 Linux AI 客户端里独一份，Flathub 官方维护、口碑好；但要清楚它的定位是"Ollama 的好壳"——**没有 RAG/知识库**，只解决"本地模型的好 UI"，与知识库层互补而非替代。

**替补二（问答镜像）：AnythingLLM Desktop 或 Askimo**
- AnythingLLM（MIT，LanceDB 全本地）：最省心的"目录喂进去出 RAG"，注意 Linux 仅 AppImage、md 改动需重同步——只当只读问答镜像，不做唯一层。
- Askimo（480★，新）：唯一"本地目录 RAG（BM25+向量）+ 非 Electron UI + 官方 deb + 当日活跃"的新候选，值得一晚试装；风险是社区小、未经验证。

**明确不推荐**：hollama（桌面已弃）、Trilium（AI 刚移除 + SQLite 绑架）、Logseq 2.0（DB Beta 数据风险 + 绑架）、Witsy（停滞半年）、Zettlr/Joplin（前者无 AI，后者 AI 仅插件级且数据非纯 md）。
