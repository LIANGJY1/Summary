# 统一知识沉淀契约：按主题路由、双 profile、共享校验

状态：accepted（2026-09-08）

现有体系按会话类型切维护地盘，导致主题路由与写权限冲突；普通条目与 Language 散文也被迫共用一套质量门。决定：知识按主题由 `ROUTING.md` 唯一路由，维护者只作责任/复核标记；沉淀入口支持 `knowledge-entry` 与 `language-note` 两个 profile；所有直接写入 knowledge-base 的 skill 共用证据、脱敏、冲突和 `check_kb.py` 契约；不设单次条目数量上限，质量由逐条门禁和用户授权保证。

## 取代的决定

- 取代 ADR 0003 中“按会话类型隔离文件地盘”的决定；保留历史正文作为决策演进记录。
- 取代 ADR 0004 中“单次 ≤3 条”的数量限制；default-deny 复习者测试继续有效。

## 结果

`session-to-skill` 只产出可重复流程；陈述性知识进 knowledge-base，难逆且有真实权衡的系统决策另记 ADR。结论反转使用双向 `supersedes` 关系，旧证据保留；修订时间线由 git 承载，不在 timeless 正文强制日期注记。
