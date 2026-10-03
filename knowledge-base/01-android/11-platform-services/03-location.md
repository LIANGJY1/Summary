# 位置服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"LocationManager 的 provider 语义、请求合并、权限改写与回调背压"；功耗策略归 14-cpu-power，应用实践归 16-app-practice。源文档：android-internals-wiki §1.25（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: 向 LocationManager 的 network provider 提交 QUALITY_HIGH_ACCURACY 请求，会自动启动 GPS 吗？**

不会。平台 API 的规则是 provider 名称决定请求交给哪个 `LocationProviderManager`，`LocationRequest.quality` 只是给该 provider 的质量与功耗提示：向 `gps` 提 `QUALITY_LOW_POWER` 不会迁移到 `network`，向 `network` 提 `QUALITY_HIGH_ACCURACY` 也不会启动 GNSS，quality 的解释由具体 provider 实现决定。旧的 `Criteria` API 也只是调用前从现有 provider 中挑选一个名称，不是一次请求同时驱动多个 provider。

两个易混名称要分清：`LocationManager.FUSED_PROVIDER` 是平台定义的 provider 名称，调用者仍通过 `LocationManager` 使用；`FusedLocationProviderClient` 是 Google Play services 的客户端 API，回调类型是 `LocationCallback`，而平台 `LocationManager` 没有接收 `LocationCallback` 的 `requestLocationUpdates` 重载。排查时先看应用链接的是 `android.location.*` 还是 `com.google.android.gms.location.*`——API 入口不同，进程、日志和版本依赖都不同。做法：明确写出使用哪个平台 API 和哪个 provider，不把 quality 当自动选源器。

**Q2: getLastKnownLocation、getCurrentLocation、requestLocationUpdates 三类读取位置方式的成本与保证有什么不同？**

最近位置只读服务端缓存、不为这次调用启动 provider，可能为 null 或已过时；单次位置先尝试不超过 30 秒的合格缓存、请求 duration 超过 30 秒会被截到 30 秒；连续更新建立长期注册并参与 provider 请求合并，需要自己管理生命周期。

1. **`getLastKnownLocation(provider)`**：读指定 provider 的缓存，不启动硬件；判断新鲜度用 `Location.getElapsedRealtimeAgeMillis()`（单调时钟，不受修改系统时间影响），不要用 `System.currentTimeMillis() - location.getTime()` 做唯一依据，并结合 accuracy 判断能否使用。
2. **`getCurrentLocation()`**：注册激活时缓存不超过 30 秒可立即返回；duration 超 30 秒被 `LocationProviderManager` 截断；权限、位置开关、provider 状态或 AppOps 不满足时可能很快收到 null，超时也返回 null——应保留 `CancellationSignal` 并在业务结束时触发，不把"等待单次结果"写成无期限状态。
3. **`requestLocationUpdates()`**：Listener 适合进程存活且生命周期清晰的页面或服务，保存同一实例并在 `onStop()` 用 `removeUpdates(listener)` 取消——及时取消既断开对回调对象的引用，也把注册从服务端合并请求中移除，避免页面不可见后仍维持高频 provider 请求；`PendingIntent` 适合跨组件交付，但受后台位置权限与系统节流约束。

**Q3: 同一 GPS provider 上同时有 1 秒与 30 秒两个注册，底层 GNSS 按什么工作？请求如何合并？**

底层按合并请求工作：同一 provider 上所有 active 注册合并成一条 `ProviderRequest`——interval 取最小值（1 秒）、quality 取数值最小即最强、max update delay 取最小、`lowPower` 做 AND；GNSS 以 1 秒节奏工作，30 秒注册只是被自己的投递过滤限速，不会降低底层功耗。一条高频请求会抬高同一 provider 上所有注册的共同成本。

合并规则（非 passive provider）：

1. interval、quality、max update delay 各取最小值，即最强要求；
2. ADAS bypass 与 ignore-settings 标志做 OR；
3. `lowPower` 做 AND；
4. `WorkSource` 收集接近最短 interval、会影响底层工作量的注册用于归因。

边界：合并只发生在单个 provider 内，不是"全系统挑一个最佳位置源"；若 `maxUpdateDelay / 2 < interval`，framework 把合并后的 batching delay 置 0。优化时用 `dumpsys location` 查看服务端接受的合并请求与注册调用者，找出最短 interval 和最强 quality 来自哪条请求，而不是只看某个业务模块自己的配置。

**Q4: 应用只有 coarse（大致位置）权限时，位置请求和回调结果会被系统怎样改写？**

两层改写：`LocationProviderManager` 把注册的 quality 改为 `QUALITY_LOW_POWER`，并把 interval 与最小更新间隔提高到 framework 内部的 10 分钟下限；返回的位置再经 `LocationFudger` 用随时间变化的偏移和网格化生成 coarse 位置——不是简单截断经纬度小数位。

前提是 Android 12 起用户可以在应用同时请求 fine 与 coarse 权限时选择 approximate location（大致位置）。边界："10 分钟"是 `android-17.0.0_r1` 的 framework 内部调度下限，不是公开 API 对所有设备、所有版本的回调承诺；后台另有动态 interval 调整，官方文档把普通后台应用描述为每小时只能收到少量位置更新，具体节流值由系统配置、进程状态和豁免条件决定。应用不应依赖固定的模糊半径或固定小数位数，需要大致位置时按能力降级设计。

**Q5: 位置回调的 oneway Binder 投递为什么仍有背压？Executor 拥塞给系统带来什么额外成本？**

因为服务端在交付前会为非 passive 的连续更新持有一个 partial wakelock（30 秒超时），应用侧 `LocationListenerTransport` 在指定 Executor 上执行完回调后，才通过 `IRemoteCallback` 通知服务端释放。oneway 只表示发送方不等同步返回，不表示接收端没有队列压力。

因果链：Executor 队列长时间拥塞 → 处理完成通知延后 → 服务端 wakelock 只能等回调或 30 秒超时 → 系统无谓耗电。所以回调应保持轻量，重计算转交工作线程；比这更长的后台工作应采用符合系统约束的执行与保活机制，不能把 framework 的交付 wakelock 当成业务 wakelock。
