# Task 2: AppStoreService Review

## 内存与稳定性
- `AppStoreService` 在 `onCreate()` 中立即初始化 `ServiceRegistry`，再启动 `HandlerThread` 和广播接收器，启动面较大。
- `onDestroy()` 里存在广播注销重复/漏项风险：`unregisterUpdateBroadcastReceiver()` 里两次检查同一个 `mUpdateBroadcastReceiver`。
- `DownloadManager` 构造函数里直接触发 reboot 恢复与云端列表同步，构造期开销偏重。

## 架构设计
- `ServiceRegistry` 是 composition root，但现在同时负责注册、实例化、`addInterface()` 和 eager start。
- `DownloadManager` 过大，混合了同步、恢复、队列、限制、云端对账、统计上报。
- `ProcessUtil` 直接操作系统能力和反射 `forceStopPackage()`，边界很重。

## 性能优化
- `DownloadManager.resolveFinalAppListFromCloudAndLocalInternal()` 使用多次 map/list 处理，热路径需关注分配。
- `ProcessUtil.isRunningApk()` 通过 shell `ps` 扫描进程，成本高且脆弱。

## IPC 与系统服务
- 导出 service 依赖 hardcoded 组件与系统广播，属于高爆炸半径 IPC 面。
- `AppStorePowerManager` 注册电源监听并在特定状态触发重初始化，需确认销毁路径完整。

## 严重度
- P0：广播注销/生命周期缺口可能导致重复回调或泄漏。
- P1：`DownloadManager` / `ServiceRegistry` 过度集中。
- P2：局部集合与日志分配。

## 排除项
- 不建议在本任务内做重构实现。
