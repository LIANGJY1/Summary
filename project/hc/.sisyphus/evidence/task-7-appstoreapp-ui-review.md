# Task 7: AppStoreApp UI/Rendering Review

## 内存与稳定性
- `RecommendationFragment` 在滚动监听、全局布局监听、snap helper 切换里有多处 UI 生命周期敏感点。

## 架构设计
- `SplitScreenHelper` 把屏幕类型逻辑集中在 Java 层，和大量 `layout-w*dp` XML 一起决定布局分支。

## 性能优化
- `RecommendationFragment` 的 `onScrolled()` / `onScrollStateChanged()` 里有较多临时对象与位置计算。
- banner/indicator 逻辑在分屏状态下频繁切换布局与监听器。
- `DownloadButtonHelper` 与列表刷新路径可能涉及字符串拼接和状态重绘热路径。

## IPC 与系统服务
- 这里主要是渲染/WMS 风险，不是 service IPC。

## 严重度
- P1：分屏与滚动状态切换可能引发 UI 抖动。
- P2：局部对象分配与重复计算。

## 关键词
- `RecommendationFragment`
- `SplitScreenHelper`
- `layout-w`
- `onDraw`
- `对象分配`
- `字符串拼接`
- `WMS`
- `渲染`
