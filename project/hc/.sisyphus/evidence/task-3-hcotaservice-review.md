# Task 3: hcotaservice Review

## 内存与稳定性
- `HCOtaService` 只负责桥接，真实风险集中在 `HCOtaManager` / `HCOtaPersistenceManager` / `HCOtaDatabase`。
- `HCOtaManager.init()` 会在服务初始化时读取持久化状态并可能立即恢复 OTA 流程，restart recovery 是核心风险。
- `HCOtaPersistenceManager` 对 SharedPreferences 使用 `commit()`，恢复状态写入是同步的。

## 架构设计
- OTA 逻辑被拆成 manager + process + repository，但入口仍由 IPC service 与恢复逻辑耦合。
- `HCOtaManager` 既是 IPC facade 又承担恢复决策，不宜继续膨胀。

## 性能优化
- `HCOtaDatabase` 使用 `allowMainThreadQueries()`，数据库访问与启动路径耦合。
- `HCOtaPersistenceManager` 的版本/包名拼装是低风险热路径，但要避免在主线程频繁读写。

## IPC 与系统服务
- `HCOtaService` 通过 callback 广播状态；`HCOtaPowerManager` 与 `OtaProgressWindow` 代表系统服务/overlay 风险面。
- `HCOtaPersistenceManager` 的 Room + SharedPreferences 双持久化是恢复语义的关键依赖。

## 严重度
- P0：恢复状态错误会直接影响 OTA 继续/回滚。
- P1：`allowMainThreadQueries()` 与同步持久化。
- P2：默认版本字符串与展示拼装。

## 排除项
- 不在本任务内改 OTA 流程实现。
