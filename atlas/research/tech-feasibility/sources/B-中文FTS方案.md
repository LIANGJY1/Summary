# B. 中文全文检索方案调研（Atlas v1）

- 日期：2026-09-19
- 约束：离线单机 Linux 桌面应用（Kotlin/Compose Desktop + SQLite），**零模型零网络**，约 1 万个 markdown 文件（中文为主、中英混排、含 ANR/Binder/FSRS 等技术术语），要求全文检索 + 命中高亮。
- 结论 TL;DR：**v1 首选 SQLite FTS5 + trigram tokenizer（查询词 ≥3 字符走 MATCH，<3 字符走 LIKE 兜底）**；放弃 unicode61；不做应用侧预分词；Lucene 作为备选而非首选。P2 语义检索以同库向量表叠加，不动 FTS 索引。

---

## 1. SQLite FTS5 内置 tokenizer 对中文的表现

### 1.1 unicode61：中文整段成一个 token，不可用

官方文档（https://sqlite.org/fts5.html §4.3.1，2026-09 抓取原文）：

> "The unicode tokenizer classifies all unicode characters as either "separator" or "token" characters. By default all space and punctuation characters, as defined by Unicode 6.1, are considered separators... all unicode characters assigned to a general category beginning with "L" or "N" (letters and numbers, specifically) or to category "Co"... are considered tokens."
>
> "Each contiguous run of one or more token characters is considered to be a token."

中文汉字属于类别 `L*`（Lo），是 token 字符；中文文本无空格分隔，因此**两个标点之间的整段连续中文 = 一个 token**。文档没有任何 CJK 切分逻辑。

实测（本机 Python SQLite 3.37.2，10k 文档合成语料）：`MATCH '内存管理是操作系统的核心功能之一'`（整句原样）可命中；`MATCH '内存'` → **0 行**。即只能整句回搜，无法做正常检索。**unicode61 直接排除**。

### 1.2 trigram tokenizer（SQLite ≥ 3.34.0）：机制、限制与原文

版本出处：changelog（https://sqlite.org/changes.html）3.34.0（2021-12-01）节内："**Enhanced FTS5 to support trigram indexes.**"

机制原文（fts5.html §4.3.4）：

> "The trigram tokenizer extends FTS5 to support **substring matching** in general, instead of the usual token matching. When using the trigram tokenizer, a query or phrase token may match **any sequence of characters** within a row, not just a complete token."
>
> "Unless the remove_diacritics option is set, FTS5 tables that use the trigram tokenizer also support **indexed GLOB and LIKE pattern matching**."

**关键限制原文（Notes 列表逐字）**：

> "**Substrings consisting of fewer than 3 unicode characters do not match any rows when used with a full-text query.** If a LIKE or GLOB pattern does not contain at least one sequence of non-wildcard unicode characters, FTS5 falls back to a **linear scan of the entire table**."
>
> "If the FTS5 table is created with the detail=none or detail=column option specified, **full-text queries may not contain any tokens longer than 3 unicode characters.**"
>
> "If an FTS5 trigram tokenizer is created with the case_sensitive option set to 1, it may only index GLOB queries, not LIKE."

即：
- **MATCH 查询 <3 字符（如"内存""缺口"）：不报错但 0 行命中**。这是硬性行为，不是可调参数。
- LIKE/GLOB 走 trigram 表时，≥3 字符字面量可用索引；**全通配或无字面量时退化为全表线性扫描**。
- 默认 `case_sensitive 0`：ASCII 大小写不敏感（实测 `MATCH 'anr'` 命中 "ANR"），中文无影响。
- **必须保持默认 `detail=full`**：detail=none/column 会禁止 >3 字符的查询 token，直接废掉 trigram 的子串匹配；且 highlight/snippet 依赖的实例信息在该模式下受限。官方给出的体积参考（1636 MiB 邮件集）：detail=full 743 MiB / column 340 MiB / none 134 MiB——省体积的代价不可接受。

**<3 字符的实测与版本坑**（重要发现）：

| 查询 | plain 表 LIKE | trigram 表 LIKE | trigram 表 MATCH |
|---|---|---|---|
| `'%内存%'`（2字） | 9295 命中 | SQLite **3.37.2：0 行（错）**；**3.51.1：9295（对）** | 0 行（文档行为） |
| `'%内存管理%'` | 9295 | 9295 | 9295，0.7–0.9 ms |

3.37.2 上 trigram 表 2 字词 LIKE 返回**错误的空结果**；在 3.51.1 上复测结果正确。含义：**必须使用应用自带的新版 SQLite（xerial 打包 3.53.4），不能落到系统旧库**；同时兜底 LIKE 建议跑在外部内容表/原文表上而不是 fts 虚表列上，语义更保险。

性能实测（本机，热缓存，10k 文档 38.3 MB 语料，合成语料为高频复用句池，命中数偏大、耗时量级可信；未找到 trigram 中文场景的公开基准，此为自测）：

- 建索引：unicode61 0.56s / trigram 1.20s（external content `rebuild`）；WAL。
- `MATCH '"内存管理"'`：0.7–0.9 ms；`MATCH 'binder'`：1.4 ms。
- trigram 列 `LIKE '%内存管理%'`：22 ms；plain 表 `LIKE '%内存%'`：18–20 ms（≈38 MB 全扫的热缓存值，冷盘按 SSD 带宽估算再慢 2–5 倍，1 万文档仍可接受）。
- 索引体积（10k 文档，正文 23.8 MB，独立库）：unicode61 55.7 MB；**trigram(detail=full) 58.3 MB（≈2.4×正文）**；detail=none 38.1 MB / column 42.6 MB（因上述限制不推荐）。

**社区绕法（2 字词问题）**：
- 官方**没有内置 bigram**。3 字以下只能：(a) LIKE 兜底（推荐）；(b) 自定义 C tokenizer。
- 自定义 tokenizer 是 C API（见 §2），JVM 侧做不到；社区项目 [streetwriters/sqlite-better-trigram](https://github.com/streetwriters/sqlite-better-trigram)（45 stars，2026-04 有 push，**无 License 字段**，TypeScript 仓库构建 C loadable 扩展）把 CJK 每字切成独立 token + ASCII 词做 trigram。其 README 自述："in CJK a single Unicode character can be a whole word. `better-trigram` fixes this by treating each CJK character as its own token"；代价："**better-trigram doesn't support `LIKE` & `GLOB` patterns**... Using `LIKE` & `GLOB` will fallback to full table scans"。引入一个无 License 的第三方 native 二进制到"零依赖"应用里，风险大于收益，仅作参考。

### 1.3 辅助函数（高亮）与 trigram 配合

原文（fts5.html §5）：

> "Auxiliary functions... may only be used within full-text queries (**those that use the MATCH operator, or LIKE/GLOB with the trigram tokenizer**) on an FTS5 table."

> "The highlight() function returns a copy of the text from a specified column of the current row **with extra markup text inserted to mark the start and end of phrase matches**."

实测（3.37.2）：trigram 表上 `highlight(fts,0,'[',']')` 在 MATCH 与 `LIKE '%FSRS%'` 两种查询下都正确返回带标记的**原文**（trigram 索引的就是原文，这是它对高亮的决定性优势）；`snippet()` 正常。
注意：**FTS5 没有 `offsets()`**（那是 FTS3/4 函数，实测报 `unable to use function offsets in the requested context`）；SQL 层拿不到逐命中偏移，只能拿 highlight/snippet 标记后的文本。逐命中字节偏移只有 C API（xInstCount/xInst）。

---

## 2. JVM 侧可行性：xerial sqlite-jdbc

仓库数据（gh api，2026-09-19）：xerial/sqlite-jdbc — 3,289 stars，pushed 2026-09-15，Apache-2.0；最新 release **3.53.4.0（2026-08-26），内置 SQLite 3.53.4** ≥ 3.34，trigram 可用。

- **FTS5 已启用**：Makefile 编译参数含 `-DSQLITE_ENABLE_FTS5`（还有 `-DSQLITE_ENABLE_FTS3`、`-DSQLITE_ENABLE_RTREE` 等）；README："The bundled native library compiles official SQLite extensions such as FTS5, R*Tree, and JSON in." trigram 无独立开关，随 FTS5 + 版本 ≥3.34 生效。
- **自定义 tokenizer：JDBC 通道做不到**。官方注册方式是纯 C：先 `SELECT fts5(?1)` + `sqlite3_bind_pointer()` 拿 `fts5_api`，再调 `xCreateTokenizer()`（fts5.html §7 原文："Adding new tokenizers, **also implemented in C**"）。xerial 未暴露这些入口，issue [#449 "How to create a custom tokenizer"](https://github.com/xerial/sqlite-jdbc/issues/449)（open）中维护者 gotson 明确回复：
  > "**This is not possible at the moment. It would require additional java and JNI code to support this.**"
  推论：bigram/任意自定义分词 = 自己维护 xerial fork + JNI，违背 v1 约束，排除。
- **loadable extension 通道存在**（对 P2 有用）：USAGE.md："Loadable extensions | `SQLITE_ENABLE_LOAD_EXTENSION` | Compile-time support only; **still off at runtime until you enable it**"，开启方式 `config.enableLoadExtension(true)` 或连接串 `?enable_load_extension=true`，然后 `SELECT load_extension('路径')`。理论上可加载 better-trigram 之类的 .so，但见 §1.2 风险评估。

---

## 3. 方案 A：应用侧分词（jieba/HanLP 等）+ FTS5 默认 tokenizer

思路：入库前分词、空格连接后入 unicode61 表；查询串同样分词。候选库核实（gh api / Maven Central，2026-09-19）：

| 库 | stars | 最后 push | License | 词库/体积 | 备注 |
|---|---|---|---|---|---|
| huaban/jieba-analysis | 2,713 | **2024-07-11** | Apache-2.0 | 仓库 dict.txt 4.95MB；`com.huaban:jieba-analysis:1.0.2` jar 2.19MB | 低维护（两年多无 push），社区靠 fork 续命 |
| hankcs/HanLP | 36,491 | 2026-09-15 | **代码 Apache-2.0** | hanlp-portable-1.8.6.jar 7.6MB | 见下方 License 坑 |
| NLPchina/ansj_seg | 6,508 | **2023-11-19** | Apache-2.0 | — | 停滞 |
| lionsoul2014/jcseg | 920 | **2023-09-18** | Apache-2.0 | — | 停滞 |
| jieba-kmp（ErolC） | 3 | 2026-01 | — | — | Kotlin 多平台 jieba 移植，极早期 |
| SmartChinese（Lucene） | 见 §4 | — | Apache-2.0 | 3.4MB（内含词典） | 词典+HMM，词典小且更新慢；质量未做定量对比，**未找到公开基准** |
| "Zhin" | — | — | — | — | **未能找到**名为 Zhin 的主流 JVM 中文分词库（GitHub 搜索/网络检索均无），疑记忆有误 |

**HanLP 的 License 坑（README 原文，2026-09-19 抓取）**：
> "HanLP源代码的授权协议为 **Apache License 2.0**，可免费用做商业用途。"
> "机器学习模型的授权...多语种模型授权沿用 CC BY-NC-SA 4.0，**中文模型授权为仅供研究与教学使用**。"

分词词典/模型类数据的授权边界需要法务级确认，对分发型桌面应用是实质风险。

**方案 A 的结构性缺陷**：
1. **高亮回填是硬坑**：FTS5 索引的列是"分词后加空格的文本"，`highlight()`/`snippet()` 返回的是这份变形文本，不是原文。要回填到原文高亮，必须额外存每个 token 在原文中的偏移（自建 `(doc_id, token_start, token_len)` 映射表）或做"变形文本 ↔ 原文"坐标换算——两条路都是易错的胶水代码。
2. **召回受制于分词质量**：技术术语（ANR/Binder/FSRS 无碍，但"内存泄漏""零拷贝"这类组合词、自造词、错切）召回不确定；需维护用户自定义词典。检索语义从"子串匹配"退化为"词匹配"，用户搜子串时可能落空。
3. 对比 trigram：多一个分词依赖 + 一套偏移映射，换来的是"更小的索引（unicode61 55.7MB vs trigram 58.3MB，本语料几乎无差）"——收益不成比例。

**结论：不推荐。** 1 万文档量级下 trigram 的"原文即索引"特性在简单性和高亮正确性上全面占优。

---

## 4. 方案 B：嵌入式 Lucene（lucene-core + 中文 Analyzer）

仓库数据（gh api，2026-09-19）：apache/lucene — 3,562 stars，pushed 2026-09-18（活跃），Apache-2.0，最新 **10.5.1（2026-08-12）**。

- 构件（Maven Central 实测字节数）：lucene-core 4.5MB + analysis-common 1.7MB + **analysis-smartcn 3.4MB** + highlighter 0.3MB + queryparser 0.4MB ≈ **10.3MB**。纯 Java 零 native，与 Kotlin/JVM 天然契合。
- 能力：`SmartChineseAnalyzer`（词典+HMM）；`org.apache.lucene.search.highlight.Highlighter` 现成，直接产出原文带标记片段——**高亮体验优于 FTS5**（可配颜色/片段策略，天然映射 AnnotatedString）。
- 与 FTS5 方案对比：
  - **双引擎**：SQLite 仍是 source of truth，Lucene 索引是旁路派生物，需要自己写索引生命周期（MMapDirectory 开关、提交、损坏重建、版本升级）。Lucene 索引格式**跨大版本不兼容**，升级 Lucene 通常要全量重建（1 万文档重建在秒级，可接受但要有重建脚本）。
  - 中文质量：分词召回优于"整段子串"，但对技术术语同样需要自定义词典；SmartChinese 词典陈旧是社区共识，HanLP Analyzer 则再次引入 §3 的数据授权问题。**未找到二者在技术笔记语料上的公开质量基准。**
  - 性能：1 万文档对 Lucene 是极小负载（亚毫秒~毫秒级），无争议，**无需基准即可下此结论**（官方与生态普遍认知）。
  - 排序：真正的 BM25 词级排序、高亮片段策略、前缀/模糊查询都比 trigram 精细。
- 定位：功能上限更高，但 v1 要为此多养一个"第二引擎"和 10MB 依赖。**作为 P1.x/P2 备选保留**。

---

## 5. 反模式排查

- **Meilisearch**（59,339 stars，活跃）：Rust 独立 server，REST API 交互，必须常驻独立进程 → 与"单应用零依赖"冲突，**排除**。
- **Quickwit**（quickwit-oss/quickwit，11,657 stars）：自述 "Cloud-native OSS search engine"，面向对象存储/分布式的独立服务 → **排除**。
- **Tantivy**（quickwit-oss/tantivy，16,118 stars）：Rust **库**，本身不违背零依赖，但 JVM 侧绑定现状为 indextables/tantivy4java（**2 stars**，JNI），生态几乎为零；等同自维护 native 层 → **排除**。
- 同理排除：Typesense/Sphinx/Xapian（server 或 native）。
- **LIKE '%x%' 全表扫**：无索引可用（前导通配符无法走 B 树），代价 ∝ 语料总量。实测 38.3MB / 1 万文档热缓存 18–22ms/次，冷盘估计 <200ms——**在该数据量级是可用兜底，而不是反模式**；真正的反模式是拿它当唯一方案（随库增长线性劣化、无相关性排序、无高亮基础设施）。

---

## 6. 方案对比表

| 方案 | 中文匹配质量 | 高亮 | 集成复杂度 | 性能（1 万文档） | 额外体积 | 主要风险 |
|---|---|---|---|---|---|---|
| FTS5 unicode61 | ✗ 整段一 token（官方机制+实测） | — | 极低 | — | 0 | **不可用** |
| **FTS5 trigram + <3 字 LIKE 兜底（推荐）** | 良好：子串语义，技术词零切分误差；2 字词走 LIKE | ✅ highlight/snippet 直接作用于原文；SQL 层无逐命中偏移 | 低：仅 DDL + 触发器 + 查询路由 | MATCH 0.7–1.4ms；LIKE 18–22ms；建索引 1.2s | 0（索引 +2.4×正文 ≈ 58MB） | 2 字词需路由兜底；旧版 SQLite 有 2 字 LIKE 错误结果（用 xerial 3.53.4 规避） |
| 应用侧分词 + FTS5（jieba/HanLP） | 良好但受分词词典限制 | ✗ 需自建偏移映射回原文 | 中：分词依赖 + 映射表 + 双向管道 | 与 trigram 同级 | jieba 2.2MB / HanLP 7.6MB | HanLP 数据授权（中文模型仅研究教学）；jieba 低维护 |
| 嵌入 Lucene + SmartChinese/HanLP | 良好，排序最精细 | ✅✅ Highlighter 成熟 | 中高：第二引擎 + 索引生命周期 | 毫秒级，富余 | ~10.3MB jars | 索引跨大版本重建；双引擎一致性 |
| Meilisearch/Quickwit/Tantivy(-4java) | 优 | ✅ | ✗ 违背单应用约束 | — | server 进程 | 排除 |
| 全量 LIKE '%x%' | 子串精确 | 自己算偏移 | 极低 | 18–22ms（当前量级），随库线性劣化 | 0 | 无排序；规模化后不可用 |

---

## 7. 最终推荐与实现要点（v1）

**首选：SQLite FTS5 trigram（xerial sqlite-jdbc 3.53.4.0 自带），<3 字符查询词 LIKE 兜底。** 理由：唯一同时满足"零新增依赖、零 native 自维护、原文直出高亮、技术术语零切分误差、1 万文档毫秒级"的方案；中文质量短板（2 字词）已被实测证明可用 LIKE 以 ~20ms 兜住。

### 7.1 索引列与 schema

```sql
CREATE TABLE notes(id INTEGER PRIMARY KEY, path TEXT UNIQUE, title TEXT, body TEXT, updated_at INTEGER);
CREATE VIRTUAL TABLE notes_fts USING fts5(
  title, body,
  content='notes', content_rowid='id',   -- external content：正文不重复存储
  tokenize='trigram'                      -- 默认 case_sensitive=0：'anr' 命中 'ANR'；必须保持默认 detail=full
);
-- 官方 external content 同步模式（fts5.html §4.4）：AI/AD/AU 三个触发器
CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
  INSERT INTO notes_fts(rowid, title, body) VALUES(new.id, new.title, new.body); END;
CREATE TRIGGER notes_ad AFTER DELETE ON notes BEGIN
  INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.id, old.title, old.body); END;
CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
  INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.id, old.title, old.body);
  INSERT INTO notes_fts(rowid, title, body) VALUES(new.id, new.title, new.body); END;
```

- 只索引 `title`、`body`；markdown 语法符号和 front-matter 在入库前剥离（降低索引噪声与体积）。`path` 不索引。
- 列权重：`ORDER BY bm25(notes_fts, 5.0, 1.0)`（标题命中加权）。
- 全量重建：`INSERT INTO notes_fts(notes_fts) VALUES('rebuild')`（实测 1 万文档 1.2s）；日常写入走触发器增量。

### 7.2 查询处理（Kotlin 侧路由）

1. 查询串按空白/标点切段，转义 `"` 后按 **Unicode code point 数**分组：≥3 → MATCH 短语；<3 → 短词集合。
2. 无短词：`SELECT ... FROM notes_fts WHERE notes_fts MATCH ? ORDER BY rank LIMIT 200`。
3. 含短词（如"内存""缺口"）：长词部分照走 MATCH；短词走 `SELECT id FROM notes WHERE title||body LIKE '%短词%'`，Kotlin 内求交集（实测兜底单次 ~20ms）。纯 2 字查询就是一次全表 LIKE，直接可接受。
4. 永远用双引号短语包裹每个 MATCH 词，防止用户输入被当 FTS5 语法解析。

### 7.3 高亮

- 列表预览：`snippet(notes_fts, -1, '«', '»', '…', 24)`（-1 自动选列）。
- 正文：`highlight(notes_fts, 1, '«', '»')` 返回**原文**带标记文本；用两个不常见控制字符做标记，解析标记位置直接构造 Compose `AnnotatedString` 区间。已知边界：FTS5 SQL 层**没有**逐命中偏移（offsets() 是 FTS3/4 的；逐实例偏移只有 C API xInst，JDBC 不可达），标记文本解析是唯一路线——trigram 方案里这条路线是通顺的，这正是选 trigram 而非预分词的决定性理由。

### 7.4 升级路径（P2 语义检索叠加）

- FTS 索引完全不动。向量表加在同一 SQLite：`CREATE TABLE note_vec(id INTEGER PRIMARY KEY, v BLOB)`；1 万 × 1024 维 fp32 ≈ 40MB，**Kotlin 暴力余弦 TopK 在此量级绰绰有余**；需要更省时再评估 sqlite-vec（xerial 已开 `enable_load_extension` 通道，`config.enableLoadExtension(true)` 后 `SELECT load_extension(...)`）。
- 混合检索：FTS5 命中集 + 向量命中集做 RRF 融合，查询管道与高亮全部复用。

### 7.5 诚实声明

- 本地基准为合成语料（句池复用，命中数偏高）、Python SQLite 3.37.2/3.51.1、热缓存，非 xerial 原生二进制；耗时量级可信，绝对值需在真实库复测。trigram 中文场景**未找到公开基准**。
- jieba/HanLP/SmartChinese 的分词质量未做定量评测；"Zhin"未找到对应项目。
- SQLite changelog 中 trigram 仅一条记录（"Enhanced FTS5 to support trigram indexes."，3.34.0/2021-12-01 节内），后续无公开的 trigram 专项修复条目；3.37.2 的 2 字 LIKE 空结果问题在 3.51.1 实测已不存在的具体修复版本未考证。

## 参考来源

- SQLite FTS5 官方文档（原文引用均出自此页，2026-09-19 抓取）：https://sqlite.org/fts5.html
- SQLite changelog：https://sqlite.org/changes.html
- xerial/sqlite-jdbc：仓库元数据/Makefile/USAGE.md、issue #449：https://github.com/xerial/sqlite-jdbc
- streetwriters/sqlite-better-trigram：https://github.com/streetwriters/sqlite-better-trigram
- huaban/jieba-analysis、hankcs/HanLP（README License 节）、apache/lucene、NLPchina/ansj_seg、lionsoul2014/jcseg、meilisearch、quickwit-oss/tantivy、quickwit-oss/quickwit、indextables/tantivy4java：GitHub API（gh api，2026-09-19）
- Maven Central：hanlp-portable-1.8.6.jar、lucene 10.5.1 构件、com.huaban:jieba-analysis:1.0.2（HEAD/Range 请求实测字节数）
