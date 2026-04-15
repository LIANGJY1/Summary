# Task 11: Cross-Module Roadmap

## P0
- `AppStoreService` 广播注销 / 生命周期缺口
- `hcotaservice` 恢复语义与持久化错误

## P1
- `AppStoreService` 的 `ServiceRegistry` / `DownloadManager` 集中化
- `AppStoreSDK` / `hcotasdk` 硬编码 target 与契约漂移
- `AppStoreApp` 全局状态与 service startup 耦合
- `fragmentation_core` 反射 state-saved 绕过

## P2
- `AppStoreBase` / `IpcWrapper` / 工具类边界与分配优化
- `AppStoreApp` UI 热路径的对象分配与字符串拼接

## 展示顺序
1. `AppStoreApp`
2. `AppStoreService`

## 执行依赖顺序
1. 服务与契约层
2. App 层
3. fork 层

## 明确不建议立即推进
- UI 改版
- 导航框架替换
- 数据库迁移实施

## 结论
这是 review → recommendation → remediation roadmap，不是实施清单。
