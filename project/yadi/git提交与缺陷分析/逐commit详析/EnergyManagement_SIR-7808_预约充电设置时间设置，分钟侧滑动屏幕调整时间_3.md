# SIR-7808 · 预约充电分钟侧滑动调时间界面卡顿

- **提交**：`87640578` | 2026-09-16 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心
- **类型**：二进制更新（aar）

## 问题
预约充电设置时间界面，分钟滚轮滑动调整时间时界面卡顿。提交说明指出根因：松手时直接把偏移量归零，且拖动过程中重复刷新文字状态，造成可见跳变；修法：短距离平滑吸附到十分钟档位，并减少无效刷新。

## 根因分析
本提交 diff 为纯二进制：`application/Launcher/libs/kanzi-release.aar`（400668356 -> 400895959 字节）。能量中心（YD_CCU）的 3D 界面由 Kanzi 渲染实现，滚轮控件的吸附与刷新逻辑在 Kanzi 工程内，本仓库只见产物更新，无源码 diff 可剖析。按元数据：卡顿/跳变的机制是"松手时 offset 直接归零"（无插值，视觉突跳）叠加"拖动过程中重复刷新文字状态"（高频无效重绘）。

## 关键代码修改
改动文件：application/Launcher/libs/kanzi-release.aar
```diff
Binary files a/application/Launcher/libs/kanzi-release.aar and b/application/Launcher/libs/kanzi-release.aar differ
```
（二进制更新，源码变更在 Kanzi 工程：松手平滑吸附至十分钟档位 + 减少拖动中的重复文字刷新。）

## 为什么能修复
吸附改为短距离平滑插值后，松手不再是瞬间归零的跳变；拖动过程减少无效的文字状态刷新后，渲染压力下降，卡顿随之缓解。Java 侧无行为变化，风险集中在 aar 版本兼容性（需与 Kanzi 服务端/资源版本匹配）。

## 复盘与经验
- 3D/HMI 类控件（Kanzi）的交互问题在本仓库只表现为 aar 变更，复盘时要依赖提交说明的 why/how 字段补齐因果链。
- 滚轮/刻度类控件的通用法则：松手必须吸附到档位且带插值动画，拖动中避免全量刷新文本，只更新可见项。
