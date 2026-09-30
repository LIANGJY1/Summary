# 与 source-annotator 的沉淀分工按会话类型划分

状态：superseded by ADR 0007（保留本文作为历史决策记录）。

knowledge-base 既有大类均由 source-annotator 维护（源码标注会话的跨库总结），新 skill session-to-knowledge 也写此目录。决定按"谁在会话现场"划分：源码阅读/标注会话的沉淀归 source-annotator；其余会话（调试踩坑、工具/工作流、架构讨论、AI 协作经验）归 session-to-knowledge。两者写同一目录、共用同一套文档骨架。

## Considered Options

- 按内容分流被否：每次写入都要跨 skill 判断内容归属，成本高且易误判。
- 单一写手（source-annotator 也绕道新 skill）被否：其沉淀节奏与源码标注流程耦合，多绕一层只添噪音。

## 解耦机制（2026-09-05 补）

session-to-knowledge 曾以 sdk-design.md 为格式范本、示例条目也写入其中，形成对 source-annotator 文档的耦合，且与本文的分工决定冲突。决定彻底分离：每个大类引言 blockquote 标注维护者，session-to-knowledge 只写自己维护或新建的大类，source-annotator 系文档主题再匹配也不碰（宁可新开大类并在边界里分工）；格式契约由 session-to-knowledge 自带（骨架 + 默认四段），不依赖任何既有大类作范本。两个 skill 共享目录与术语表（CONTEXT.md），文件集不相交。
