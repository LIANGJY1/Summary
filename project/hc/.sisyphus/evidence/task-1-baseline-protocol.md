# Task 1: Baseline Evidence Protocol

## 模块拓扑
- `AppStoreApp`
- `AppStoreService`
- `AppStoreSDK` / `AppStoreBase`
- `hcotaservice` / `hcotasdk` / `hcotabase`
- `fragmentation_core`

## 约束与证据
- `AppStoreApp/build.gradle`、`AppStoreService/build.gradle`、`hcotaservice/build.gradle` 均启用 `ignoreFailures = true`。
- Root build 使用 `flatDir` 指向 `../../../out/.../libs`，并依赖预编译 AAR（如 `AppStoreSDK.aar`、`HCOTASDK.aar`）。
- 证据分级统一为 `P0 / P1 / P2`。

## 审查模板
- 内存与稳定性
- 架构设计
- 性能优化
- IPC 与系统服务

## Baseline Commands
- `./gradlew projects`
- `./gradlew :AppStoreApp:testDebugUnitTest`
- `./gradlew :AppStoreService:testDebugUnitTest`
- `./gradlew :hcotaservice:testDebugUnitTest`

## 当前环境
- 本会话里这些命令受 Java 11 阻塞，AGP 要求 Java 17。

## 风险清单
- `SparseArray` / `LongSparseArray` 替代机会需要在性能热路径里单独记录。
- `Binder` 缓存/重连、`WMS`、`overlay`、`listener cleanup` 需要单独标注。

## 结论
这是 review-only 基线协议，不进入实现。
