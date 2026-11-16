# 批08 补记——ViewRootImpl drawSoftware 空行漂移的发现与修复（2026-11-14）

协调者独立验收时用"仅剥离 CJK 行"口径（与写作代理的"剥离中文/注释/空行"口径不同）复核批08 涉及的 4 个 study 文件，发现：

- `frameworks/base/core/java/android/view/ViewRootImpl.java`：精树 4815（drawSoftware 签名后的空行）在 study 对应位置缺失——study 5313 签名行后直接接 `// 【核心流程】` 批注。该漂移非批08 引入（【核心流程】标签属历史批次风格，批08 新增块用【调用关系】等标签），系历史批注插入时删除了空行；批08 自检因同时剥离空行未暴露。
- 处置：协调者在 study 签名行与【核心流程】批注之间补回空行（Edit 仅新增一行，未触碰任何代码与批注文本）。
- 复验：修复后 ViewRootImpl.java 剥离 CJK 行 diff 精树，非 insert 操作块 0——全文件回到"精树＋纯插入"状态。
- 其余 3 文件（InputEventReceiver.java/RecyclerView.java/VelocityTracker.java）同口径复查非 insert 操作块 0，无漂移。
- 教训入批17 复核指南：批注完整性校验必须用"仅剥离 CJK 行"口径（保留空行与英文行），任何 delete/replace 型 opcode 都是漂移；写作代理自检口径（剥离注释与空行）会漏掉空行删除型漂移。
