# SIR-3525 · 通知中心天气信息展示（功能开发，误标 bugfix）
- **提交**：`2b73d778` | 2026-08-12 | liujinfeng | SystemUI | **feature（非 bugfix，JSON 标记 mistag=true）**
- **缺陷库**：未关联缺陷（单号为需求单，无 defs 记录）

## 类型说明
提交标题为 `[feat][yadea][SystemUI][SIR-3525]天气信息`，属天气信息展示功能开发，非缺陷修复，不强行剖析。

## 改动概要
61 个文件，+282/-58 行。主要内容：
- `notification/ext/autoweather/bean/WeatherDataBean.kt`：天气数据模型扩展；
- `notification/ui/NotificationCenterFragment.kt`（+254 行为主）：通知中心内天气信息 UI 展示与刷新逻辑；
- `navbar/actor/NavBarActor.kt`：天气入口相关调整；
- `weather/WeatherServiceConnector.kt`：监听接口新增 `onServiceConnectionChanged(connected)` 默认实现，并在 `onServiceDisconnected`/binder died/绑定成功时回调，补齐服务连接状态通知；
- 30 余个天气图标 png（二进制资源新增）与 `notification_center.xml` 布局挂载。

## 备注
该需求单无缺陷库记录，提交信息 `[why]NA [how]NA` 也印证为功能而非修复。
