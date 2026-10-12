# PowerMonitor API

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/02-online-diagnostics.md · Q16 -->

**Q1: [learning] API 35 的 PowerMonitor 读数为什么“单次累计值没有场景意义”？AAOS13 上能用吗？**

AAOS13 不可用：`SystemHealthManager` 的 `getSupportedPowerMonitors()` 与 `getPowerMonitorReadings()` 从 API 35 起公开（AAOS13 源码核对不存在这两个方法），Android 13 及更早版本没有这条公开路径。可用平台上也要按正确语义使用：`PowerMonitorReadings` 返回自开机以来的累计能量（单位微瓦秒，包含电池供电与插电阶段），重启后不连续——单次读数没有场景意义，正确用法是同一组 `PowerMonitor` 在场景前后各读一次，按各自 `timestamp`（来自 `SystemClock.elapsedRealtime()`）计算非负增量。跨重启、计数回退或 `ENERGY_UNAVAILABLE` 的样本应丢弃。

三条使用边界：支持列表可能为空，电源监视器的名称与含义依设备而变，名称相同也不代表不同 OEM 的计量范围等价。普通 App 读数可能被缓存并加入与调用 UID 相关的随机噪声，高频读取得不到仪器精度，也不应尝试反推去噪。电源轨是设备级数据，App 只能在受控实验中用 Trace 时间窗做相关分析，不能把电源轨增量直接记为当前 App 的能耗。硬件通道名称可能暴露机型与硬件组合，生产上报要先评估设备指纹风险，并对采样频率和保留期设限。
