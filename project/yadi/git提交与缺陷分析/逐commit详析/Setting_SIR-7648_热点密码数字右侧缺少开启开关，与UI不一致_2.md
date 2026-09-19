# SIR-7648 · 热点密码行缺少展开箭头（cherry-pick 同步）
- **提交**：`bf4513af` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **类型**：cherry-pick 提交 —— diff 与 `123ac4a9` 完全相同（`cherry picked from commit 123ac4a95fb05f4f46ae26117c9390ccf84d050c`），仅把 `item_hotspot_header.xml` 的 8 行新增同步到另一分支。

改动文件：application/Setting/src/main/res/layout/item_hotspot_header.xml（+8 行）

说明：热点密码行右侧补独立 `iv_arrow` ImageView 并将容器设为垂直居中，详见 123ac4a9.md 的完整剖析。此类多分支并行开发的 UI 修复需要靠 cherry-pick 手工同步，本身无新机制。
