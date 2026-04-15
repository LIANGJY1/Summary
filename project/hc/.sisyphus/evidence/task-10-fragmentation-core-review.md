# Task 10: fragmentation_core Review

## 内存与稳定性
- `TransactionDelegate` / `SupportFragmentDelegate` 管理 Fragment 生命周期、队列和 state-saved 场景，风险面广。
- `FragmentationMagician` 通过反射绕过 state-saved 约束，属于高敏感维护点。

## 架构设计
- 这是本地 fork，不是普通业务模块；ordinary feature work should prefer AppStoreApp wrappers first。
- 如果问题能在 wrapper 层解决，不应直接上升到 fork 改造。

## 性能优化
- 主要是事务调度与状态恢复成本，而不是纯算法。

## IPC 与系统服务
- 无直接 IPC；这里只关注 Fragment / AndroidX 兼容性。

## 严重度
- P1：反射绕过 state-saved 与回退栈控制。
- P2：事务队列与动画状态管理复杂。

## 关键词
- `TransactionDelegate`
- `SupportFragmentDelegate`
- `FragmentationMagician`
- `wrapper`
