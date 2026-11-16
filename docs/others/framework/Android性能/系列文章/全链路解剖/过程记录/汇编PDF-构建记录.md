# 汇编 PDF 构建记录

> 任务：用户令"将所有生成文章整理成一份pdf"（2026-11-14 下达）。
> 范围：18 篇文章（00 总纲＋01~16 正文＋附录A，16,810 行）。台账与过程记录为管理文档，不在汇编范围。
> 纪律：原文零改动；只写不提交；不打印设备标识/内部地址；全程留痕（本记录）。
> 产物：`汇编/AppList性能全链路解剖-汇编.pdf`（581 页，4.7 MB）＋`汇编/combined.html`（可复跑源）＋`汇编/build_pdf.py`＋`汇编/book.css`＋`汇编/qa_pdf.py`。

## 一、引擎选型

环境无 pandoc/xelatex/weasyprint/wkhtmltopdf；pip 默认源不可达，经阿里云镜像装 markdown 3.10.3＋WeasyPrint 70.0（pygments 同装未用，代码块取单色浅灰底，守全文色数纪律）。Chrome 152 在场但未选：WeasyPrint 原生支持 target-counter 目录页码、string-set 书眉、标题书签，书籍级分页控制完整。字体：正文 Noto Serif CJK SC 10.5pt/1.75，标题 Noto Sans CJK SC，代码 Noto Sans Mono 8.3pt（CJK 回退 Noto Sans CJK SC）；四态符号 ✅🟡❌⬜ 由 Noto Color Emoji 彩色渲染（WeasyPrint 70 COLRv1，探针实测）。

## 二、管线

markdown（tables/fenced_code/sane_lists/toc，slugify 加篇前缀 a00~a16/aA）→ 链接处理（4 条跨篇链接内化为锚点；8 条外部文件链接〔台账×4、过程记录×4〕去链留文 span.extref；代码围栏内 `](…)` 形 C++ 语法不被 markdown 解析，天然安全）→ colgroup 加权列宽注入 → 单 HTML（封面＋双级目录 257 条＋18 个 section）→ WeasyPrint A4（页边距 20/18/22/18mm）。页码方案：全书连续绝对页码，封面与目录不编号，正文页脚"第 N 页"，目录 target-counter 与书签页码三方一致。元数据：title/author(liang-jy)/subject/keywords/creator 齐全。

## 三、构建期问题与修正（现象/根因/处置）

1. **杂散 h1**：09 篇:546 段首术语编号"#31 performTraversals…"被 python-markdown 宽 ATX 正则（井号后不要求空格，CommonMark 要求）误判为 h1，污染书眉/强制分页/书签。处置：围栏感知预处理，仅对围栏外行首井号后无空格者转义（全 18 篇扫描仅此 1 行；setext 风险 0；正规标题 834 与书签数闭合）。原文零改动。
2. **页码计数器**：counter-reset 方案致目录 target-counter 全 1 且正文页码不重置。处置：移除 reset，改全书连续绝对页码。
3. **宽表溢出右界**（21 页）：auto 布局 min-content（格内长 token）撑破页宽。处置：table-layout:fixed＋colgroup 按列内容显示宽度加权。
4. **li 长箭头链溢出**（p313）：li 未设 overflow-wrap（p 有 li 无）。处置：li 加 overflow-wrap:anywhere。
5. **td 内长哈希 code 溢出**（p450）：WeasyPrint 表格单元格内不兑现 overflow-wrap（引擎限制）。处置：td code/th code 改 word-break:break-all。
6. **宽表短列逐字竖排**（视觉门 p13，三轮修复）：比例权重无最小宽保障→加物理 pt 最小宽（短 token≤12 显示宽保整行×5pt〔实测数字 advance 4.99pt〕、表头至多 4 字横排×9pt、加 padding/边框/余量 13pt；长标识符/路径允许中途折行；最小宽和超 100% 等比兜底）→**根因**：列宽换算误用 559pt 页宽，实际 A4 内容宽＝595.28−2×18mm(51.02pt)＝493.23pt，改 4.9323pt/% 后六项验收全过。
7. **目录标题双重转义**（handler-&amp;gt;）：toc token name 已含 HTML 转义，去二次 escape。

## 四、QA 结果

**机械 QA（qa_pdf.py）**：581 页/4.7 MB/835 书签（一级 19＝目录＋18 篇）/257 目录条目逐条与书签页码核对 **0 不一致**/悬空锚点 0/空白页 0/内部地址 0（设备序列号特征串零出现；四段 IP 样匹配仅章节号 3.1.2.x）/四态符号逐篇与源一致（16 篇＋1 套＝:545 四态图例表头跨页合法重复一次）/关键数值全在（34,738,039、65,629,999、43.597、50.928、16,810、24d27dff64c4）。右界全扫：47 页最大 547.0pt＝内容界 544.3＋字形 bearing 舍入约 2.7pt，>550pt 为零，非布局溢出。

**技能 pdf_qa**：元数据/页尺寸/无空白页/字体全嵌入/填充率/封面全幅/表格居中全过；余 11 WARN 均为行首标点（—/…/，）——WeasyPrint 70 用 Pango 断行、不支持 CSS line-break，— 不在 Pango zh 禁则字符集，引擎级限制，如实记录，不影响内容。

**视觉门（pdf:visual-judge，三轮 9 页）**：封面 p1、目录 p2、篇首 p9、宽表 p13、代码页 p30、li 页 p313、td 页 p450、审计表 p520、四态表 p537——**全 pass**。p13 经三轮修复循环（竖排→右列竖排→数字中断→mm/pt 根因）终轮六项全过。

## 五、复跑与口径

复跑：`python3 汇编/build_pdf.py`（依赖 markdown/weasyprint/pymupdf，book.css 同目录；输出覆盖 汇编/ 下 PDF 与 combined.html）。口径说明：外部文件链接去链留文（台账/过程记录不在 PDF 内）；跨篇链接内化；页码为全书绝对页码；代码块单色无语法高亮；原文 18 篇零改动（预处理仅转换器侧转义 1 行）。

## 六、思考过程留痕与交互修订（2026-11-15 补）

- **思考过程归档**：构建期全部探针/QA 输出/视觉门样张已入仓 `汇编/过程留痕/`（31 件，含 probe 探针、qa_skill 六轮输出、各轮 gate 样张），逐件用途与轮次判定见 `汇编/过程留痕/索引.md`。复核 R7 自验实跑产物入仓 `过程记录/复核-实跑留痕/`（27 件）。两目录地址扫描零命中。
- **交互修订约定**：用户已定型"边看 PDF 边引用原文修改"工作流——引文→grep 定位 markdown 源→修改→按需重建；机制结论类修改先对精树/trace 验证。全文见 `汇编PDF-交互修订约定.md`。PDF 为派生产物，禁止直接改 PDF。