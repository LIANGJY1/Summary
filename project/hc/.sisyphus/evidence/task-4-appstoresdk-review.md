# Task 4: AppStoreSDK Review

## 内存与稳定性
- `AppStoreSDK` 是单例 facade，构造时立即 `connect()` 并注册两个 observer 实例。
- `AppStoreClient` 硬编码目标包名与 service 类名，绑定失败会直接影响所有调用。

## 架构设计
- 该模块是 client-contract 层，不是工具库；它与 `AppStoreService` 的方法签名必须严格对齐。
- `UiClientObserverImpl` / `DownloadRestrictionObserverImpl` 通过聚合分发，需关注 detach 语义与生命周期。

## 性能优化
- `CopyOnWrite` 风格的 observer fan-out 适合读多写少，但 attach/detach 频率高时会有额外拷贝。

## IPC 与系统服务
- 这里是 prebuilt AAR 的源头之一，source/runtime skew 是主要风险。
- 需保留 Binder 缓存、重连、兼容性与服务侧对齐检查。

## 严重度
- P1：硬编码目标与契约漂移。
- P2：observer 聚合与集合开销。

## 关键词
- `AppStoreClient`
- `hardcoded`
- `CopyOnWrite`
- `AAR`
- `Binder`
- `缓存`
- `重连`
- `兼容性`
