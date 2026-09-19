# SIR-3401 · 网易云乐评卡片功能开发

- **提交**：`87b0f547` | 2026-09-02 | ljl | Launcher | **feature（非 bugfix，未关联缺陷库）**
- **缺陷库**：未关联单号

## 类型说明
功能开发提交（+769 行），非缺陷修复，不强行按 bug 复盘模板剖析。改动概要：

- **数据链路**：`AndroidManifest.xml` 静态注册 `CommentCalendarReceiver` 接收乐评/日历广播 → `CommentCalendarManager`（单例）暂存 `latestData`，`init` 绑定界面后补显（覆盖"广播先于界面到达"与 Activity 重建场景），展示 8 秒（`AUTO_DISMISS_DELAY = 8000L`）后自动消失，`KEY_LAST_SHOWN_DATE` 控制每日展示。
- **界面**：`CommentCalendarView` + `view_comment_calendar.xml` + `CommentCalendarRootLayout` 挂到 `activity_main.xml`；日/夜两套背景 `img_comment_calendar_bg_light/dark.png`（二进制素材）。
- **跨进程手势联动**：新增 `ICustomGestureService.aidl / ICustomGestureCallback.aidl` 与 `GestureServiceConnector`（211 行），Launcher 侧连接 apf 服务的自定义手势接口；同时更新了 framework 侧同名 aidl（各 +8/+1 行）。
- 常量入 `Constants.java`，`MainActivity` 接入 init/release 生命周期。

## 经验提示
- 接收器静态注册 + 单例暂存 + `init` 补显的时序设计（"广播先于界面到达"）是车载常驻应用处理异步数据的标准套路，值得复用。
- 功能提交同样规范填写了 `[影响等级]` 与 Change-Id，与 bugfix 提交共用一套元数据口径，利于后续统计（但注意缺陷库中 SIR-3401 无 defs 记录，需求类单据不进缺陷分析口径）。
