# SIR-7036 · 无线首次连接耗时超 5 秒
- **提交**：`4cdc4bd8` | 2026-09-01 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
无线首次连接手车互联时整体耗时超过 5 秒，应用列表界面显示过慢。

## 根因分析
`LinkActivity.kt` 在收到 `CarLinkConstants.FusionUiType.APP_LIST`（应用列表型融合界面）时，用 `lifecycleScope.launch { delay(1500.milliseconds); finish() }` 写死了一个 1.5 秒的过场延时才关闭本页。这个固定 delay 是连接总时长的重要组成，叠加底层（无线首次连接）响应慢，导致用户感知超过 5 秒。属于"用固定延时掩盖时序不确定"的经典问题。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt`
```diff
--- .../java/com/yadea/launcher/function/link/LinkActivity.kt
                     CarLinkConstants.FusionUiType.APP_LIST -> {
                         lifecycleScope.launch {
-                            delay(1500.milliseconds)
+                            delay(800.milliseconds)
                             LogUtils.d(TAG, "finish")
                             finish()
                         }
```

## 为什么能修复
把过场 delay 从 1500ms 压到 800ms，直接缩短 700ms 的固定等待，配合前序提交（`764f03a5` 等）的应用列表缓存/回调直通优化，首次连接总时长压回 5 秒内。隐患：该数值仍是拍脑袋延时而非事件驱动——若列表加载事件本身晚于 800ms，页面会提前 finish，需结合缓存策略保证数据已就绪。

## 复盘与经验
- 固定 delay 是车机跨进程时序问题最常见的"止痛药"，短期有效但会被性能指标倒逼反复调整；正确方向是事件驱动（等数据回调再 finish）+ 超时兜底。
- 性能类缺陷（连接耗时）往往是多个固定延时叠加的结果，排查时应把链路上所有 delay 列出来。
- 单行修改也能是 B 级必现缺陷的修复——定位到"哪一段等待是不必要的"比大改更有价值。
