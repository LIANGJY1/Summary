# Task 6: AppStoreApp Core Review

## 内存与稳定性
- `App` 启动时同步初始化 UI 组件、语音、下载分发、全局对话框管理。
- `DownloadStateDispatcher` 在 `init()` 中直接订阅 SDK 回调，属于全局常驻 fan-out。
- `GlobalDialogManager` 持有 `WeakReference<Activity>`，但仍有 service-startup side effect。

## 架构设计
- `CommonPresenter` 同时处理点击节流、限制、下载、卸载、对话框、登录态与全局 observer。
- `MainActivity` 使用 fragmentation shell，根 fragment 切换逻辑与 task-root 处理绑定。

## 性能优化
- `CommonPresenter` / `DownloadStateDispatcher` 的 UI 转发链路里存在多层日志和分发。

## IPC 与系统服务
- `GlobalDialogManager.startAppStoreService()` 直接启动 exported service，属于 app↔service 耦合点。
- `AppSettingsFragment` 在设置页里直接触发账号、HCC 更新/恢复、全局下载状态联动。

## 严重度
- P1：全局状态与 UI 生命周期耦合。
- P2：日志与分发开销。

## 关键词
- `CommonPresenter`
- `DownloadStateDispatcher`
- `GlobalDialogManager`
- `Activity`
- `Context`
- `service startup`
