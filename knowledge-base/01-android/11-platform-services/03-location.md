# 位置服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"LocationManager 的 provider 语义、请求合并、权限改写与回调背压"。功耗策略归 14-cpu-power，应用侧定位实践见相应应用与功耗主题册。源文档：android-internals-wiki §1.25（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: 向 `LocationManager` 的 network provider 提交 `QUALITY_HIGH_ACCURACY` 请求，会自动切换到 GPS 吗？**

不会。显式指定 provider 后，系统把请求交给该 provider 的 `LocationProviderManager`，`LocationRequest.quality` 是该 provider 的精度与功耗提示，不会把请求自动切换到另一个 provider。quality 的实际效果由 provider 实现决定。

几个配置组合的结果如下：

1. 向 `gps` provider 提交 `QUALITY_LOW_POWER` 不会因此改投 `network`。
2. 向 `network` provider 提交 `QUALITY_HIGH_ACCURACY` 也不会因此自动换为 `gps`。
3. 如果希望系统组合多个可用来源，应明确使用 fused provider，而不是把 quality 当作自动选源器。

**Q2: `getLastKnownLocation()`、`getCurrentLocation()` 和 `requestLocationUpdates()` 的成本与保证有什么不同？**

三者分别适用于读取已有缓存、获取一次新鲜位置和持续订阅。它们是否启动 provider、是否可能返回 `null`、以及应用需要维持的注册时长都不同。

1. **`getLastKnownLocation(provider)`**：读指定 provider 的缓存，不启动硬件。判断新鲜度用 `Location.getElapsedRealtimeAgeMillis()`（单调时钟，不受修改系统时间影响），不要用 `System.currentTimeMillis() - location.getTime()` 做唯一依据，并结合 accuracy 判断能否使用。
2. **`getCurrentLocation()`**：Android 17 AOSP 把不超过 10 秒的缓存视为“current”，否则可启动 provider 获取一次新位置。请求 duration 超过 30 秒时会被 `LocationProviderManager` 截到 30 秒。请求超时或系统无法取得有效位置时回调可收到 `null`，缺少位置权限则可能直接抛 `SecurityException`。保留 `CancellationSignal` 并在业务结束时取消，不要让单次等待成为无期限状态。
3. **`requestLocationUpdates()`**：Listener 适合进程存活且生命周期清晰的页面或服务。保存同一实例并在 `onStop()` 用 `removeUpdates(listener)` 取消，避免页面不可见后仍收到更新。`PendingIntent` 适合跨组件交付，但仍受后台位置权限与系统节流约束。取消某个注册会把它从服务端合并请求中移除，是否降低 provider 工作量取决于其他调用者的请求。

**Q3: 同一 `gps` provider 上同时有 1 秒与 30 秒两个注册，底层 GNSS 如何工作？请求怎样合并？**

底层按合并请求工作：同一非 passive provider 上的 active 注册合并为一条 `ProviderRequest`。示例中，1 秒请求会让 `gps` provider 按约 1 秒的最小间隔工作，30 秒请求只在结果分发阶段按自身间隔过滤，不能抵消高频请求带来的共同成本。

合并规则（非 passive provider）：

1. interval、quality、max update delay 分别取最小值。quality 数值越小表示精度要求越高。
2. ADAS bypass 与 ignore-settings 标志做 OR，只要一条注册请求这些能力，合并结果就会保留它。
3. `lowPower` 做 AND，只有所有贡献请求都允许低功耗，合并请求才保持低功耗。
4. `WorkSource` 汇总接近最短 interval、会影响底层工作量的注册，用于功耗归因。

合并只发生在单个 provider 内，不是“全系统挑一个最佳位置源”。若 `maxUpdateDelay / 2 < interval`，framework 会把合并后的 batching delay 置为 0。优化时用 `dumpsys location` 查看服务端接受的合并请求与注册调用者，找出最短 interval 和最强 quality 分别来自哪条请求。

**Q4: 应用只有 coarse（大致位置）权限时，位置请求和回调结果会被系统怎样改写？**

两层改写：`LocationProviderManager` 把请求质量降到低功耗档，并把请求间隔提高到 framework 内部的 10 分钟下限。返回的位置再经 `LocationFudger` 使用随时间变化的偏移和网格化生成 coarse 位置，不是简单截断经纬度小数位。

1. **权限前提：**Android 12 起，用户可以在应用同时请求 fine 与 coarse 权限时选择 approximate location（大致位置）。
2. **调度边界：**“10 分钟”是 `android-17.0.0_r1` framework 内部调度下限，不是公开 API 对所有设备、所有版本的回调承诺。Android 8.0（API 26）及以上对后台应用的位置计算和交付施加系统限制，官方文档描述为通常每小时只能收到少量更新。具体行为仍受系统配置、进程状态和豁免条件影响。
3. **精度边界：**应用不应依赖固定模糊半径或固定小数位数。需要大致位置时，应按权限等级降低功能精度。

**Q5: 位置回调的 oneway Binder 投递为什么仍有背压？Executor 拥塞给系统带来什么额外成本？**

服务端为非 passive 的连续位置更新交付持有一个 partial wakelock，最长等待 30 秒。应用侧 `LocationListenerTransport` 在指定 Executor 上完成回调后，通过 `IRemoteCallback` 通知服务端释放 wakelock。oneway Binder 只表示发送端不等待同步返回，不代表接收端没有队列压力。

1. Executor 拥塞会使回调排队，完成通知随之延后。
2. 服务端在收到完成通知或 wakelock 超时前继续持有唤醒锁，造成额外耗电。
3. 回调应保持轻量，重计算转交工作线程。更长的后台工作应采用系统允许的执行机制，不能把 framework 交付 wakelock 当成业务 wakelock。

**Q6: `LocationManager.FUSED_PROVIDER` 与 Google Play services 的 `FusedLocationProviderClient` 有什么区别？**

两者可能都组合多个定位来源，但属于不同 API 契约。`LocationManager.FUSED_PROVIDER` 是平台 `android.location` provider 名称（API 31 起公开）。`FusedLocationProviderClient` 则是 Google Play services 的客户端 API，不能把一者的回调、可用性或依赖假设套到另一者上。

1. **平台 API：**通过 `LocationManager` 指定 `FUSED_PROVIDER`、`GPS_PROVIDER` 或其他平台 provider。`FUSED_PROVIDER` 若存在，可组合多个平台 provider 的输入。`Criteria` 相关的 `LocationManager` 更新接口也隐式使用 fused provider。`Criteria` 自 API 34 起弃用，平台文档建议直接选择 provider。
2. **Google Play services API：**`FusedLocationProviderClient` 来自 `com.google.android.gms.location`，持续更新常使用 `LocationCallback`，单次读取与持续订阅的 API 契约由 Google Play services 定义。它依赖设备上可用的 Play services 实现。
3. **排障方式：**先确认代码导入的是 `android.location.*` 还是 `com.google.android.gms.location.*`，再选择对应的 provider 状态、日志和版本依赖进行排查。平台 `LocationManager` 没有接收 Google Play services `LocationCallback` 的 `requestLocationUpdates()` 重载。
