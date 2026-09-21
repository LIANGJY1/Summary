# D 线调研：GitHub 上的「客户端/本地知识库 + AI」项目深挖

- 调研日期：2026-09-19
- 核实方式：**全部 40 个仓库均经 GitHub API 逐项核实**（`gh api repos/<owner>/<repo>`，已认证账号，取 stars / pushed_at / open_issues / archived / license）；约 15 个关键项目另读 README 全文或关键段落（basic-memory、mem0、obsidian-copilot、smart-connections、smart-composer、anything-llm、cherry-studio、khoj、quivr、private-gpt、gpt4all、reor、kotaemon、karakeep、memos、Folo、MCP servers 目录结构）。star 数均为查询日快照。
- 读者画像前提：提问者已有纯 markdown 个人知识库（PARA/Zettelkasten + AGENTS.md 治理 + agent 读写 + 确认门），已了解 Claude Code memory / Cline Memory Bank / AGENTS.md 标准等。本篇不重复这些，只回答：**「在现有 markdown 库上叠一个什么客户端/AI 层？」**
- 状态标记：✅活跃（近 2 周内有 push）、⚠️放缓/转型、❌停滞或归档。

---

## 结论速览（梯队）

**第一梯队（活跃且真正能用，优先看）**
| 项目 | stars | 一句话 |
|---|---|---|
| basicmachines-co/basic-memory | 3.9k | MCP + 纯 markdown，最贴合「已有 md 库 + 日常 agent」的路线 |
| logancyang/obsidian-copilot | 7.7k | Obsidian 插件，vault RAG + 接入 Claude Code/Codex agent，周更 |
| Mintplex-Labs/anything-llm | 66.2k | 桌面客户端 + 本地 RAG（LanceDB），最省心的「目录喂进去」方案 |
| CherryHQ/cherry-studio | 52.0k | 中文生态最热桌面客户端，内建知识库，MCP 支持好 |
| brianpetro/obsidian-smart-connections | 5.5k | 零配置本地嵌入语义检索/相关笔记，写作时反哺知识库 |
| khoj-ai/khoj | 37.4k | 「AI 第二大脑」最完整开源形态，但 2026 年放缓 |
| infiniflow/ragflow | 91.0k | 要自托管高Parsing质量 RAG 时的引擎首选（个人偏重） |

**避坑（详见文末）**：Reor（已归档）、GPT4All（停更 15 个月+）、Verba（已归档）、Quivr 与 PrivateGPT（均已转型开发者组件）、Smart Composer（官方声明维护模式）。

---

## A. 笔记软件 + AI/RAG 内建

### 1. reorproject/reor ❌已归档
- https://github.com/reorproject/reor ｜ 8,554★ ｜ 最后 push **2025-05-13**（查询日 2026-09-19）｜ **archived=true** ｜ AGPL-3.0
- 定位：本地 AI 个人知识管理应用（Electron），笔记是纯 markdown 文件，内嵌 Ollama + LanceDB 做自动关联/语义 RAG。
- 曾是最贴合「markdown 库 + 本地 RAG」的独立 app，**现已归档只读，团队停止开发**。README 无恢复计划说明。
- 适用性：❌ 不要再投入；其「Ollama+LanceDB 内嵌进笔记应用」思路可借鉴，替代品见路线一/二。

### 2. logancyang/obsidian-copilot ✅活跃（重点）
- https://github.com/logancyang/obsidian-copilot ｜ 7,736★ ｜ push 2026-09-19（当日）｜ AGPL-3.0 ｜ open_issues 97
- 定位已升级为 **"Agents for your Obsidian vault"**：v4.x 周更发版（4.0.5→4.0.9 全部在 2026-09 内）。两条腿：① 自带 vault RAG——本地/云端嵌入模型 + 混合检索，索引留在本机（付费 Copilot Plus 用其本地索引器 Miyo，嵌入数据在设备上）；② 把 Claude Code、Codex CLI、opencode 等 agent 直接跑进 Obsidian，vault 上下文自动带入。
- 支持 BYOK（密钥存 Obsidian Keychain）与本地模型；部分托管功能由 Brevilabs（付费）处理，README 有明确隐私披露。
- **未与 Smart Composer 合并**（Smart Composer 独立存在，见 #4，已进维护模式）。
- 适用性：现有 md 库的最直接叠加层之一，尤其你已在用 Claude Code——它在 Obsidian 内把两者接通了。

### 3. brianpetro/obsidian-smart-connections ✅活跃
- https://github.com/brianpetro/obsidian-smart-connections ｜ 5,457★ ｜ push 2026-09-16 ｜ 自定义「source available core」许可（NOASSERTION）｜ open_issues 493
- 定位：写作时的**相关笔记/摘录语义浮现**——内置本地嵌入模型（零配置、无 API key），自动索引 vault，边写边显示语义相关的段落 + Lookup 语义搜索视图；另有 Smart Chat。v4 大重构（2025–2026），单作者驱动，issue 积压偏多。
- 不是全文问答型 RAG，而是「知识库反哺写作/思考」型。
- 适用性：与你 Zettelkasten 式互链习惯最合拍的插件，做检索层而不是问答层。

### 4. glowingjade/obsidian-smart-composer ⚠️维护模式
- https://github.com/glowingjade/obsidian-smart-composer ｜ 2,328★ ｜ push 2026-02-16 ｜ AGPL-3.0
- 原 repo（hyungtaecf）已 404/转移。README 顶部明确 **"not under active development"，单人维护**；功能：vault chat（嵌入检索）、apply-edit、v1.2.x 加了 MCP 支持。
- 适用性：不建议新上车；其「vault 聊天 + 编辑应用」形态已被 obsidian-copilot 覆盖。

### 5. siyuan-note/siyuan ✅活跃
- https://github.com/siyuan-note/siyuan ｜ 46,430★ ｜ push 2026-09-19（当日）｜ AGPL-3.0
- 定位：隐私优先的块级笔记/知识工作空间，最新口号 "humans and AI agents work together"（内置 AI + agent 能力）。数据为 `.sy` JSON 块存储，**不是纯 md 目录**（可导入/导出 markdown）。
- 适用性：功能强但会要求你迁移出纯 md 体系，与现有库冲突，仅作观察对象。

### 6. logseq/logseq ✅活跃
- https://github.com/logseq/logseq ｜ 44,976★ ｜ push 2026-09-19（当日）｜ AGPL-3.0
- 定位：大纲式双链笔记，数据就是本地 markdown/org 文件。内建 AI 能力弱（靠社区插件），DB 版重构推进多年未落定。
- 适用性：md 库友好，但要 AI/RAG 仍需外挂插件或客户端层，本身不解决你的问题。

### 7. toeverything/AFFiNE ✅活跃
- https://github.com/toeverything/AFFiNE ｜ 72,761★ ｜ push 2026-09-18 ｜ 混合许可（NOASSERTION：核心开放 + 附加条款）
- 本地优先 + AI（AFFiNE Cloud 提供智能体），自托管服务端成熟度一般；数据为自有 block 格式非纯 md。
- 适用性：团队/个人一体化工作空间，不适合叠在你现有 md 库上。

### 8. AppFlowy-IO/AppFlowy ✅活跃
- https://github.com/AppFlowy-IO/AppFlowy ｜ 76,833★ ｜ push 2026-09-15 ｜ AGPL-3.0
- 开源 Notion 替代 + AI（支持接本地模型）。数据自有格式。适用性同上：替代而非增强你的 md 库。

## B. 桌面 LLM 客户端带知识库

### 9. CherryHQ/cherry-studio ✅活跃（中文社区热门）
- https://github.com/CherryHQ/cherry-studio ｜ 51,980★ ｜ push 2026-09-19（当日）｜ AGPL-3.0 ｜ open_issues 1,567
- 定位：桌面 AI 工作站——多模型聊天、300+ 预置助手、**内建知识库**（文件/文件夹/网址导入，嵌入模型可接 Ollama 本地模型，向量数据本地存储）、MCP server 支持、WebDAV 备份。发版频繁。
- 适用性：对中文用户是「客户端层」最顺手的选择；文档需导入其知识库管理，非实时监听 md 目录。

### 10. chatboxai/chatbox ✅活跃（但知识库弱）
- https://github.com/chatboxai/chatbox ｜ 41,806★ ｜ push 2026-09-16 ｜ GPL-3.0 ｜ 原 Bin-Huang/chatbox 已更名
- 定位：纯多平台 AI 聊天客户端，月更（v1.23.x）。知识库/RAG 能力远弱于 Cherry Studio/AnythingLLM。
- 适用性：当纯聊天客户端可以，当知识库层不合格。

### 11. Mintplex-Labs/anything-llm ✅活跃（重点）
- https://github.com/Mintplex-Labs/anything-llm ｜ 66,203★ ｜ push 2026-09-19（当日）｜ MIT ｜ open_issues 317
- 定位：**桌面版**（Mac/Win/Linux）+ Docker 双形态的「私有 ChatGPT」：文档导入工作区→默认 LanceDB 本地向量库→RAG 问答；自带 agent、内置 Ollama 可完全离线；向量库可换 PGVector 等，嵌入器有原生默认（全本地）。支持文件夹批量导入（入库后由其内部存储管理，改动需重新同步，并非实时监听目录）。
- 适用性：B 组里「把 md 目录喂进去就出 RAG」完成度最高的桌面方案，MIT 许可，上手成本最低。

### 12. janhq/jan ✅活跃
- https://github.com/janhq/jan ｜ 44,558★ ｜ push 2026-09-19（当日）｜ 自定义许可（原 Apache + 品牌附加条款）
- 定位：100% 离线的本地 LLM 桌面（llama.cpp 系），主打本地推理与扩展应用，知识库/RAG 非核心卖点。
- 适用性：当本地推理底座或备用客户端，不当知识库层。

### 13. nomic-ai/gpt4all ❌停滞（重点避坑）
- https://github.com/nomic-ai/gpt4all ｜ 77,395★ ｜ **最后 push 2025-05-27（停更 15 个月+）** ｜ MIT
- 其 **LocalDocs** 是最早把「指向本地文件夹 → 本地嵌入（Nomic Embed）→ RAG 问答」做进桌面 app 的功能，概念影响深远。但 Nomic 重心早已转移（Atlas/embedding API），无新版本、无修复。
- 适用性：❌ 不再推荐新部署；借鉴 LocalDocs 的产品形态即可。

### 14. lobehub/lobehub（原 lobe-chat）✅活跃
- https://github.com/lobehub/lobehub ｜ 82,622★ ｜ push 2026-09-19（当日）｜ 混合许可（NOASSERTION）
- 原 lobe-chat 的知识库（RAG、pgvector）功能仍在，但 v2 已整体转型为「多 agent 协作平台」（Agents as the unit of work）。自托管需要 Postgres 等一套服务。
- 适用性：个人自托管偏重，且知识库已非宣传重心；中文 UI 友好可作备选。

## C. 自建「第二大脑」/个人 RAG

### 15. khoj-ai/khoj ⚠️2026 明显放缓（重点）
- https://github.com/khoj-ai/khoj ｜ 37,409★ ｜ 最后 push 2026-08-02；release 停在 2026-03 的 2.0.0-beta.28 ｜ AGPL-3.0
- 定位：**最完整的开源「AI 第二大脑」**：Obsidian/markdown/org-mode/PDF/Notion 同步索引，浏览器/Obsidian/桌面/WhatsApp 多端问答，自定义 agent、定时自动化（automations）、深度研究。可自托管亦可云。
- 2026 年节奏明显掉档（半年无 release），商业公司（Khoj Inc）重心存疑，但代码库与云服务仍在线。
- 适用性：md 库→个人 RAG 的「全家桶」路线代表；上车前接受其放缓风险，或观察 2.0 正式版。

### 16. The-Vibe-Company/quivr（原 QuivrHQ/quivr）⚠️已转型
- https://github.com/The-Vibe-Company/quivr ｜ 39,534★ ｜ push 2026-08-31 ｜ 混合许可 ｜ open_issues 34
- **"第二大脑"产品形态已死**：仓库现为 quivr-core——面向开发者的 "opinionated RAG" Python 库（配 megaparse 解析），给产品集成用，不再提供面向个人的自托管问答应用。
- 适用性：想自己写代码时才相关；当现成工具已不适用。

### 17. karakeep-app/karakeep（原 hoarder）✅活跃
- https://github.com/karakeep-app/karakeep ｜ 29,149★ ｜ push 2026-09-16 ｜ AGPL-3.0
- 定位：自托管「书签一切」（链接/笔记/图片/整页归档）+ **LLM 自动打标与摘要（支持 Ollama 本地模型）** + Meilisearch 全文与语义搜索。多端扩展/App 齐全。是采集/摘录侧工具，**不是文档 RAG 问答**。
- 适用性：与你的 md 库互补——管「外部信息摄入」，高质量条目再沉淀进 md 库。

### 18. usememos/memos ✅活跃（无 AI）
- https://github.com/usememos/memos ｜ 63,166★ ｜ push 2026-09-18 ｜ MIT
- 定位：markdown 时间线速记（flomo 式），轻量自托管，**当前版本无内建 AI/RAG**（早期 OpenAI 集成已不在）。适用性：速记入口可参考，解决不了 AI 层。

### 19. onyx-dot-app/onyx（原 Danswer）✅活跃（个人过重）
- https://github.com/onyx-dot-app/onyx ｜ 32,161★ ｜ push 2026-09-19（当日）｜ 混合许可（NOASSERTION，2025 年调整过，个人自托管自用无碍）
- 定位：企业 AI 搜索/问答平台（40+ 连接器接 Slack/Google Drive/Confluence…），Docker 全家桶依赖重。适用性：个人使用杀鸡用牛刀，团队知识中台才考虑。

### 20. Cinnamon/kotaemon ⚠️放缓
- https://github.com/Cinnamon/kotaemon ｜ 25,775★ ｜ push 2026-07-14 ｜ Apache-2.0
- 定位：与文档聊天的 RAG UI（含 GraphRAG 可视化、引用溯源），自托管友好。2025 年下半年起提交明显稀疏，社区已转向（Open WebUI/AnythingLLM/RAGFlow 吸走了用户）。
- 适用性：想抄「引用可视化/GraphRAG」的 UI 交互时值得看源码，不建议当长期依赖。

### 21. weaviate/Verba ❌已归档（避坑）
- https://github.com/weaviate/Verba ｜ 7,706★ ｜ 最后 push 2026-06-08 ｜ **archived=true** ｜ BSD-3-Clause
- Weaviate 官方 RAG demo 应用，已归档只读。其价值只剩「Weaviate + Ollama 本地 RAG」参考代码。❌ 不要再用。

### 22. zylon-ai/private-gpt ⚠️已转型
- https://github.com/zylon-ai/private-gpt ｜ 57,520★ ｜ push 2026-09-17 ｜ Apache-2.0 ｜ open_issues 仅 13（维护极干净）
- 转型为「本地模型的 **API 层**」：RAG/工具/MCP/text-to-SQL 等 building blocks，按 Claude API 模式暴露，为商业版 Zylon 供血。不再是开箱即用的个人问答应用。
- 适用性：自研本地 AI 服务时的高质量底座，不是直接给个人用的客户端。

## D. 平台级自托管（简要定位——对个人普遍过重）

### 23. langgenius/dify ✅活跃
- https://github.com/langgenius/dify ｜ 156,387★ ｜ push 当日 ｜ 混合许可（Apache + 多租户附加条款）
- 工作流编排 + RAG + agent 的一体化平台。个人当知识库问答用太重；想做自动化管线才值得。

### 24. infiniflow/ragflow ✅活跃（自托管 RAG 引擎首选）
- https://github.com/infiniflow/ragflow ｜ 90,974★ ｜ push 2026-09-18 ｜ Apache-2.0
- 强项在深度文档解析（DeepDoc 版面/表格理解）+ 模板化 RAG + agent。对「PDF/扫描件重的文档问答」质量口碑最好；Docker 部署较重，个人可用但吃资源。

### 25. labring/FastGPT ✅活跃（中文生态）
- https://github.com/labring/FastGPT ｜ 29,691★ ｜ push 2026-09-18 ｜ 自定义开源许可（附加条款）
- 知识库问答 + 可视化工作流，国内文档/生态完善，适合中文 QA 场景。个人自托管中等偏重。

### 26. 1Panel-dev/MaxKB ✅活跃（中文企业向）
- https://github.com/1Panel-dev/MaxKB ｜ 22,839★ ｜ push 2026-09-18 ｜ GPL-3.0
- 企业级智能体/知识库平台，UI 友好。对个人偏重、定制空间一般。

### 27. open-webui/open-webui ✅活跃
- https://github.com/open-webui/open-webui ｜ 152,515★ ｜ push 当日 ｜ 自定义许可（v0.6 起加品牌条款，自用无碍）
- 自托管 LLM WebUI 事实标准；Knowledge 集合提供上传文档的混合检索（关键词 + 嵌入），配 Ollama 全本地。文档仍以「上传入库」为主，不是持续监听本地目录。作为通用自托管聊天+轻知识库合格。

### 28. danny-avila/LibreChat ✅活跃（补充）
- https://github.com/danny-avila/LibreChat ｜ 44,348★ ｜ push 2026-09-18 ｜ MIT
- 多模型聊天前端 + RAG（rag api + pgvector）+ agents/MCP。自托管一套起，个人视需求选型，优先级低于 AnythingLLM/open-webui。

## E. Agent 记忆/知识层（对你尤其相关）

### 29. basicmachines-co/basic-memory ✅活跃（本次调研最贴合）
- https://github.com/basicmachines-co/basic-memory ｜ 3,991★ ｜ push 2026-09-16 ｜ AGPL-3.0 ｜ open_issues 58 ｜ Python（uv 安装，`uv tool install basic-memory --prerelease=allow`）
- **实际工作方式（经 README 核实）**：
  - 知识的 source of truth 就是**本地纯 markdown 文件**；MCP server 让 Claude/Codex/Cursor/ChatGPT 等「会说话 MCP 的客户端」直接读写同一批文件，人（Obsidian 等任意编辑器）与 AI 双向同步，"AI 与人类写同一批文件"。
  - 在文件之上建索引：observation + wikilink 构成**知识图谱**（SQLite 索引），支持语义搜索，可选 cross-encoder 重排（混合检索）；向量可存 Milvus/Postgres（可选 extra）。
  - 项目（project）= 指向你磁盘上的某个目录（如 `~/basic-memory` 或你自己的 vault 目录）；Obsidian 只需打开同一目录即可并排工作。
  - 本地版免费（AGPL）；云端同步/Teams 收费（$15/mo），非必需。官方还发 Claude Code 插件/skills。
- 与你体系的关系：**它不替代你的 AGENTS.md 治理，而是给 agent 提供对 md 库的结构化读写 + 检索原语**（read/write/search/canvas 类工具），与"Claude Code memory 是会话记忆、basic-memory 是跨项目知识库"的分工互补。
- 适用性：你已确认门机制 + agent 读写知识库——basic-memory 几乎是同一哲学的成熟开源实现，值得一试的第一候选。

### 30. mem0ai/mem0 ✅活跃（重点深挖）
- https://github.com/mem0ai/mem0 ｜ 65,619★ ｜ push 当日 ｜ Apache-2.0 ｜ open_issues 757
- **实际工作方式（经 README 核实）**：「AI 记忆层」——LLM 从对话/文本中**抽取记忆条目**，去重合并后存入向量库（默认 Qdrant，可图存储），检索时注入。**不是文件系统、不管理 markdown 库**。2026-04 新算法：实体链接 + 语义/BM25/实体多信号融合检索 + 时间推理。形态：pip/npm 库、自托管 server（自带鉴权）、云平台、OpenMemory（本地 MCP 记忆，多客户端共享）。
- 适用性：适合「让 agent 自动积累用户偏好/项目事实」的运行时记忆；与你的「人治 md 知识库」是两个物种，**不要用 mem0 替代知识库**，但 OpenMemory MCP 可作 agent 个人记忆层备选。

### 31. letta-ai/letta（原 MemGPT）✅活跃（重心转云）
- https://github.com/letta-ai/letta ｜ 24,793★ ｜ push 2026-09-10 ｜ Apache-2.0
- 有状态 agent 平台：memory blocks + 归档记忆（数据库存储），核心思想（分层记忆/自编辑记忆）值得借鉴；已转向 Letta Cloud/平台服务，自托管开源版仍在维护但 release 节奏放缓（0.16.8，2026-05）。

### 32. topoteretes/cognee ✅活跃
- https://github.com/topoteretes/cognee ｜ 30,827★ ｜ push 2026-09-18 ｜ Apache-2.0
- 自托管「AI 记忆/知识图谱引擎」：ECL 管道（Extract→Cognify→Load）把文本变成图+向量双索引，Python 库形态。适合自研知识图谱记忆，非现成客户端。

### 33. getzep/graphiti ✅活跃
- https://github.com/getzep/graphiti ｜ 30,998★ ｜ push 2026-09-17 ｜ Apache-2.0
- Zep 的底座：实时**时序知识图谱**（实体/关系带时间版本），面向 agent 记忆。与 cognee 同类，选一研究即可；工程化程度高，个人直接用成本大。

### 34. modelcontextprotocol/servers — memory server ✅仍在但极简
- https://github.com/modelcontextprotocol/servers ｜ 90,455★ ｜ push 2026-09-03 ｜ `src/memory` 参考实现仍在该仓库（未归档，经目录列表核实）
- 官方 memory server：单 JSON 文件的 knowledge graph（实体/关系），无嵌入检索。价值在作为「最小可用」模式参考；要真用，basic-memory 是它的满血版。

## F. 模式参考

### 35. simonw/til ✅活跃
- https://github.com/simonw/til ｜ 1,457★ ｜ push 2026-09-11 ｜ Apache-2.0
- Simon Willison 的公开 TIL 仓库：小步 markdown + 自动建站 + 多数 TIL 由 Claude Code 辅助完成——「AI 代理边干边沉淀知识库」的最佳公开样板，可与你的 session-to-knowledge 流程互参。

### 36. RSSNext/Folo ✅活跃
- https://github.com/RSSNext/Folo ｜ 38,974★ ｜ push 当日 ｜ AGPL-3.0
- AI RSS 阅读器（原 Follow）：订阅→AI 时间线/摘要，全平台。定位是「输入侧」工具：用 AI 提高摄入效率，产出再进你的 md 库。不是知识库层。

### 37. st3v3nmw/obsidian-spaced-repetition ✅活跃
- https://github.com/st3v3nmw/obsidian-spaced-repetition ｜ 2,556★ ｜ push 2026-09-01 ｜ MIT
- Obsidian 内复习闪卡与整篇笔记（SM-2 系调度），**不内建 AI 生成**。知识库 × 间隔重复的标准件。

### 38–40. 用 LLM 从笔记生成闪卡（小项目，均经 API 核实元数据）
- **ctrlaltwill/LearnKit**（173★，push 2026-09-14）：Obsidian 原生学习系统，笔记→AI 闪卡，活跃度最好的新项目。✅
- **pieralukasz/true-recall**（63★，push 2026-09-15）：AI 闪卡 + FSRS v6 调度。✅（小而新）
- **ECuiDev/obsidian-quiz-generator**（175★，最后 push 2024-11）：OpenAI/Google 模型生成闪卡测验。⚠️停更两年内无维护，仅作参考。
- 说明：该细分方向尚无「霸主级」项目，LearnKit/true-recall 皆百星级；若你重度用闪卡，基于现有 SR 插件 + 自己的 agent 生成 Q/A 到卡片语法里，可能比引第三方插件更稳。

---

## 避坑清单（已死 / 更名 / 停滞 / 转型）

| 项目 | 状态 | 证据 | 去向/替代 |
|---|---|---|---|
| **Reor** | ❌ 仓库已归档 | archived=true，最后 push 2025-05-13 | Obsidian 插件（Copilot/Smart Connections）或 AnythingLLM |
| **GPT4All（含 LocalDocs）** | ❌ 停更 | 最后 push 2025-05-27，无新版 | AnythingLLM / open-webui / Obsidian Copilot |
| **Verba（Weaviate）** | ❌ 已归档 | archived=true，push 2026-06-08 | RAGFlow / AnythingLLM |
| **Omnivore** | ❌ 关停（2024 末） | 服务已下线（背景知识，未再核） | karakeep、Readwise/Instapaper |
| **Quivr** | ⚠️ 转型开发者 RAG 库 | repo 迁至 The-Vibe-Company，README = quivr-core | 个人 second brain 需求 → khoj/AnythingLLM |
| **PrivateGPT** | ⚠️ 转型 API 层（Zylon） | README 明示 | 个人客户端需求 → AnythingLLM/Cherry Studio |
| **Smart Composer** | ⚠️ 官方声明维护模式 | README "not under active development"；原 repo 已转移至 glowingjade | obsidian-copilot（未合并，但功能覆盖） |
| **kotaemon** | ⚠️ 明显放缓 | push 2026-07-14，提交稀疏 | RAGFlow / Open WebUI |
| **Khoj** | ⚠️ 2026 放缓（仍可用） | release 停在 2026-03，push 2026-08 | 观望 2.0；替代同上 |
| **obsidian-quiz-generator** | ⚠️ 停更 | push 2024-11 | LearnKit / true-recall / 自研生成 |
| 更名记录 | — | — | Danswer→**Onyx**；hoarder→**karakeep**；lobe-chat→**lobehub**；Bin-Huang/chatbox→**chatboxai/chatbox**；QuivrHQ→**The-Vibe-Company**；GPT4All 未更名但属 Nomic 弃更 |
| 易误解项 | — | — | **memos** 当前无 AI/RAG；**logseq/AFFiNE/AppFlowy/思源** 数据均非纯 md 目录，会绑架你的库 |

---

## 针对该用户的落地路径建议

> 前提约束：保住现有纯 markdown 库（不迁移数据格式）、数据尽量本地、与 Claude Code/agent 日常工作流融合、可随使用持续积累。

### 路线一（推荐主路线）：MCP 层——用 basic-memory 把现有 md 库直接接进所有 agent
- 做法：`uv tool install basic-memory`，把 project 指向你现有知识库目录；Claude Code / Codex / Cursor / ChatGPT 全部通过 MCP 读写同一批 md，获得实体图谱 + 语义检索（可选重排）；你的 AGENTS.md/确认门机制原样保留，basic-memory 只提供工具层。
- 依据：README 核实「AI 与人写同一批 markdown 文件、双向同步、本地优先、Obsidian 免配置并排」；唯一一个以「本地 md 为 source of truth」且仍活跃的 agent 知识层项目（3,991★，push 2026-09-16）。
- 收益：零迁移、知识库直接进入日常 agent 工作流、检索从 grep 升级到图+语义混合。代价：需 Python/uv 与 MCP 配置；AGPL（自用无碍）；语义索引是附属 SQLite/Milvus 数据（仍可随时重建，md 本身不动）；社区规模尚小（4k★）。
- 变体：嫌重可直接用 MCP filesystem server + 你现有检索脚本，但语义检索/图谱要自己造。

### 路线二（推荐辅路线）：Obsidian 插件做「阅读/写作侧」AI 层
- 做法：知识库目录即 vault，装 obsidian-copilot（vault RAG + 把 Claude Code/Codex 拉进 Obsidian）和/或 smart-connections（本地嵌入相关笔记浮现）；配 obsidian-spaced-repetition + LearnKit/自研 agent 生成闪卡，补「知识库→记忆」闭环。
- 依据：copilot v4.x 周更、嵌入索引留在本机；smart-connections 零配置本地嵌入；三者均当日/近期活跃。
- 收益：与现有 md 库完全零摩擦，写作时反哺（相关笔记/闪卡）是其他客户端给不了的。代价：绑 Obsidian；copilot 部分高级功能走 Brevilabs 托管付费（BYOK/本地模型可绕开）；插件 RAG 质量是插件级，不如 RAGFlow 的解析。

### 路线三（按需）：桌面客户端或自托管 RAG——当「问答优先于治理」时
- 轻量档：AnythingLLM Desktop（MIT、LanceDB 全本地、文件夹批量导入）或 Cherry Studio（中文生态、知识库+MCP）。把 md 目录喂进去即可问答。代价：文档要导入其内部库、改动需重同步（非实时监听），与「库的单一真相在 md」有轻微分叉，适合当只读问答镜像。依据：两仓库当日 push，MIT/AGPL。
- 重型档：RAGFlow（或 open-webui Knowledge）自托管，仅当你的库中 PDF/扫描件/表格占比高、要高质量解析问答，或要给家人/团队开服务时才值得。代价：Docker 全家桶 + 资源，个人日常偏重。
- 不推荐起点：Onyx/Dify/MaxKB（企业向）；khoj 虽是 md 库第二大脑鼻祖形态，但 2026 放缓，先观望。

**最小行动建议**：先用一晚上跑通路线一（basic-memory 指向现有库 + Claude Code 验证读写/检索）；同时给 Obsidian 装 smart-connections 感受写作侧反哺；一个月后再决定是否加 AnythingLLM 当全家问答镜像。
