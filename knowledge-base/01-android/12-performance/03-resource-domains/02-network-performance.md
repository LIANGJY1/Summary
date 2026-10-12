# Android 网络性能

**Q1: [learning] 一次 HTTPS 请求应该按哪些分段计时？TTFB 为什么不能单独证明服务端慢？**

非双工请求按阶段观察，并按 attempt 保存事件序列：

1. **调用入队：**用 `dispatcherQueueStart`/`dispatcherQueueEnd` 记录 Dispatcher 排队。
2. **代理与 DNS：**记录代理选择和 `dnsStart`/`dnsEnd`。
3. **路由与建连：**记录路由尝试，以及 `connectStart` 到 `secureConnectStart` 的 TCP 建连阶段。
4. **TLS：**用 `secureConnectStart`/`secureConnectEnd` 记录握手。
5. **请求与响应：**记录请求头/体、响应头等待（请求发送结束至 `responseHeadersStart`）、响应体。
6. **客户端处理：**记录反序列化与界面更新。

缓存命中或连接复用会让 DNS 与 connect 事件缺席；重定向、路由回退与重试会让某些阶段出现多次，不能假定每种事件只出现一次。

TTFB 的起点在不同平台口径不同：

1. 从 `callStart` 起算会包含排队、DNS、建连、握手与上传。
2. 从请求发送结束起算才接近“网络往返加服务端”。

TTFB 高可能来自以下位置，不能只据此归咎服务端：

1. 客户端排队。
2. 边缘节点排队或 CDN cache miss。
3. 源站处理。

结合服务端 trace、`Server-Timing`、CDN 缓存状态与客户端分段计时才能归因。`responseHeadersStart` 表示开始读响应头，不等于业务已拿到可展示数据。OkHttp 的 `callStart` 在 `enqueue()`/`execute()` 后即触发；网络完成到业务可用之间还有解析与 UI 提交。

**Q2: [learning] 主线程发起网络会抛 NetworkOnMainThreadException，但“主线程等待后台网络结果”为什么同样危险？BlockGuard 覆盖哪些入口？**

危险在于结果相同——主线程被网络往返阻塞，造成卡顿甚至 ANR。以下同步等待不是网络调用本身，因此不会触发 NetworkOnMainThreadException：

1. `Future.get()`。
2. `CountDownLatch.await()`。
3. `runBlocking` 中等待网络结果。

Perfetto 里主线程出现 `nativePollOnce` 通常只说明 Looper 在等消息；只有调用栈、线程状态与网络 slice 时间重叠共同指向等待点时，才能判断 UI 在等网络。

直接发起网络的检测由 BlockGuard 提供。AAOS13 源码核对（`libcore/ojluni/src/main/java/java/net/Inet6AddressImpl.java`）：`lookupHostByName()` 在读 DNS 缓存之前就调用 `BlockGuard.getThreadPolicy().onNetwork()`，所以主线程即使命中缓存也会抛异常。`BlockGuardOs`（`libcore/luni/src/main/java/libcore/io/BlockGuardOs.java`）在以下入口也有 onNetwork 守卫：

1. `connect`。
2. 阻塞 `poll`。
3. `recvmsg`。

缺 `INTERNET` 权限时 `getaddrinfo` 返回 EACCES/EPERM，并被转成带提示的 `SecurityException`。JNI 直接 I/O 可能绕过部分 BlockGuard 检测，因此它不是安全边界。

**Q3: [learning] HTTP/2 与 HTTP/3 在同连接并发与丢包处理上的本质区别是什么？“上了 HTTP/3 就一定更快”错在哪？**

HTTP/2 用 HPACK 压缩头部、把多个 stream 复用进一条 TCP 连接，减少了 HTTP/1.1 时代为并发而开多条连接的开销。但 TCP 按字节顺序交付，任一 stream 的数据丢失会让同连接上所有 stream 等待重传（队头阻塞）。HTTP/3 把 HTTP 映射到 QUIC stream（基于 UDP），某个 stream 丢失的数据只阻塞它自己，头部压缩换成 QPACK，且协议带 Connection ID 与路径验证能力，IP 或端口变化后有机会迁移连接。

“一定更快”不成立。HTTP/3 的表现仍受以下因素影响：

1. 共享拥塞控制窗口、设备 CPU 与服务器调度仍是连接级共享资源。
2. UDP 路径丢包时，HTTP/3 不保证快于 HTTP/2。
3. 连接迁移取决于 provider 配置、服务器支持、NAT、VPN 与中间网络；Wi-Fi 与蜂窝切换时请求仍可能失败。

发布 HTTP/3 时按协商后的协议、首次连接与会话恢复、网络与地区分组观察 P95/P99、失败率与回退率；服务端要正确发布能力并允许 UDP 路径，客户端保留 HTTP/2 回退。

**Q4: [learning] TLS 1.3 的 0-RTT early data 为什么只能承载能容忍重放的操作？QUIC 连接迁移是无条件的吗？**

0-RTT 让客户端在恢复连接的握手中提前发送 early data，省一次往返；代价是数据可能被截获后原样重发，安全属性弱于新握手后的数据。RFC 8446 要求应用协议自行评估重复执行的后果：

1. 只有重复执行也不会产生不可接受后果的请求才可发送 early data。
2. 不能按 GET/HEAD 方法名直接判安全；GET 也可能消费一次性令牌或触发计数。
3. 带幂等键的 POST 可能可容忍重试；支付、发帖、创建订单等操作应等握手完成，或依赖服务端幂等键与去重记录。

0-RTT 还需要会话状态、客户端允许与服务端接受同时成立；服务端可以拒绝，正确性不能依赖它一定成功。

连接迁移同样有条件：QUIC Connection ID 允许 IP/端口变化后经新路径继续，但迁移仍受以下条件影响：

1. provider 配置与服务器支持。
2. 新路径验证以及 NAT、VPN 和中间网络行为。

切网时请求仍可能失败。业务层保留取消、重试与幂等处理，不能把迁移理解成无感知切换。平台边界如下：

1. Android 10 起平台 TLS 1.3 默认启用，但基于 socket 的平台 TLS API 不支持 0-RTT（按官方文档口径）。
2. OkHttp 5.3.0 的稳定配置没有 HTTP/3 与 early data 开关。
3. Cronet 用 `QuicOptions.Builder.enableTlsZeroRtt()` 与 `addAllowedQuicHost()` 控制（按材料转写）。

**Q5: [learning] 为什么整个进程要共享一个 OkHttpClient？连接池默认的“5 和 5 分钟”是什么口径，一次连接复用要满足什么条件？**

每个 OkHttpClient 拥有独立的连接池与线程资源；每次请求新建 client 会丢失连接复用机会并留下空闲资源。按需求选择：

1. 不同超时或拦截器需求，用 `newBuilder()` 从共享 client 派生；派生 client 共享连接池和线程。
2. 代理、信任管理器、证书固定或 DNS 相互冲突时，才创建真正独立的 client。

“5 和 5 分钟”是 OkHttp 5.3.0 默认最多保留 5 条空闲连接、每条空闲连接 keep-alive 5 分钟——它是空闲连接上限，不是连接总数、域名数或并发上限。正在承载 HTTP/2 stream 的连接不算空闲。调参前先统计新建连接率、空闲回收与服务端 keep-alive 策略，不要凭经验数字扩大连接池。

一次复用需要完整 `Address` 条件一致：

1. scheme 与端口。
2. 代理与 DNS 结果。
3. socket/TLS 配置。
4. hostname verifier 与 certificate pinner。

HTTP/2 跨主机 connection coalescing 还要求现有连接指向同一 IP/端口、证书覆盖新主机且校验与 pin 兼容；两个域名不保证共享连接。OkHttp 5 默认启用 fast fallback 并行尝试多路由以降低 IPv6/IPv4 等待，一次 Call 会出现多组 connect 事件，监控按 attempt 分别保存。“预热”是一笔真实成本：发送 HEAD 或空 GET 会产生 DNS、建连、TLS、流量与电量开销；后续请求还可能因网络切换、authority 不同或空闲回收而无法复用。DNS 预解析只省 resolver 阶段，不能完成 TCP/TLS 握手。

**Q6: [learning] OkHttp 的 callTimeout、connectTimeout、readTimeout、writeTimeout 分别约束哪一段？弱网下把超时一律调大或调小各有什么问题？**

四者是不同层级的期限。OkHttp 5.3.0 默认 connect/read/write 各 10 秒，默认没有覆盖整个 Call 的总期限：

1. **`callTimeout`：**覆盖整个调用，包括 DNS、建连、写入、服务端处理、读取、重定向与内部恢复。
2. **`connectTimeout`：**只约束新 TCP socket 的连接阶段，不约束 DNS 与整个调用。
3. **`readTimeout`：**约束 socket 上单次读取操作之间的间隔，不等于整个响应体期限。
4. **`writeTimeout`：**约束单次写入操作，不等于上传业务的完整截止时间。

按业务约束期限：

1. 交互接口设置明确的总期限。
2. 大文件上传使用分片与断点续传。
3. 长连接与流媒体按心跳或 segment 设计。
4. 后台同步使用 WorkManager 的网络约束与重试调度。

一律调大超时会延长用户等待并占用并发资源（Dispatcher 与连接被慢请求占满）。一律调小会在尾部网络上放大失败与重试。正确做法是按接口 SLO、用户等待预算、请求体大小与可恢复方式分别设定，并记录每次超时发生在哪一段，再调整对应边界。

**Q7: [learning] 判断“失败后能不能自动重试”的依据是什么？“请求体已发出后连接中断”为什么最危险？**

是否重试取决于失败位置与服务端是否可能已经执行：

1. **DNS 或请求发出前的连接失败：**服务端大多没收到业务请求，可让网络库尝试其他地址或路由，但仍受总期限约束。
2. **TLS 证书、主机名或协议校验失败：**属于安全配置或中间网络异常，应停止盲目重试并保留错误分类。
3. **请求体发送后连接中断：**客户端不知道服务端是否已执行；先查询业务状态或依赖幂等键去重，不能直接重发。
4. **HTTP 408、429、503：**服务端可能允许稍后尝试，按接口契约和 `Retry-After` 决定。
5. **响应体中途失败：**支持 Range/ETag 的下载可续传。

HTTP 方法名只是线索：声明为 PUT/DELETE 的接口仍可能有审计或通知副作用，写操作的安全要靠服务端幂等键。

重试节律也要受控：

1. 使用指数退避与随机抖动，并受尝试次数、总期限、前后台状态与用户取消约束。
2. 网络恢复时避免让所有挂起请求同时重发。
3. OkHttp 的 `retryOnConnectionFailure` 默认开启，处理路由与连接层恢复；重定向和认证也会在一个 Call 内产生 follow-up。应用层叠加重试时要分开记录网络库 attempt 与业务 attempt，避免次数相乘。
4. 不要在 Interceptor 内阻塞等待退避；上层或 WorkManager 更适合编排重试。

**Q8: [learning] HTTP 连接复用、TLS 会话恢复与 TLS 1.3 0-RTT 三者有什么区别？客户端持有 session ticket 就一定能恢复吗？**

三者的连接行为不同：

1. **HTTP 连接复用：**不新建传输层连接，也不重新握手；原连接仍可用时直接发送新请求。
2. **TLS 会话恢复：**新建连接并重新握手，但用 PSK 或缓存的 session state 缩短协商。
3. **0-RTT：**在恢复握手中提前发送 early data，还要求网络栈、服务端与业务语义允许（重放边界见上一问）。

复用命中时，DNS、TCP、TLS 阶段都会跳过；这通常比微调加密算法更值得检查。

会话恢复不保证发生，以下情况会让连接退回完整握手：

1. 服务端重启或保护 ticket 的密钥轮换。
2. 负载均衡把请求转到其他节点。
3. ticket 过期。

只看客户端的 `secureConnectStart`/`secureConnectEnd` 无法可靠判断是否恢复，要结合 TLS 库日志或服务端的完整/恢复握手指标。TLS 1.3 完整握手让客户端在首个 ClientHello 携带 key share，一个往返后即可发应用数据（TLS 1.2 通常按两个往返理解）；这是消息路径比较，不能换算成固定毫秒数。

**Q9: [learning] 用 OkHttp EventListener 做网络监控要注意什么？NetworkCallback 的 VALIDATED 能证明业务域名可达吗？**

EventListener 的使用约束如下：

1. 每个 `Call` 由 `EventListener.Factory` 创建独立 listener 实例，避免并发事件共享可变状态。
2. 回调快速返回，不做磁盘或网络 I/O，也不重新进入同一个 client；事件写入无阻塞队列，由后台消费者批量处理。
3. DNS、connect、请求与响应事件可能因重定向、fast fallback 和重试重复出现，应追加到 attempt 列表；连接复用时 DNS 与 connect 缺席是正常结果。
4. 指标限制基数并保护隐私：记录白名单映射的接口模板、协议、状态码与错误类别，不记录完整 URL、query、header、请求体与 token。
5. 使用单调时钟计时。Perfetto 不会自动展开 OkHttp Call；用 AndroidX Tracing 异步 slice 标记逻辑调用，并用唯一 cookie 区分并发请求。

`VALIDATED` 不能证明业务可达：

1. `INTERNET` 是能力声明，`VALIDATED` 是系统对公网探测点的结论；业务请求还取决于目标域名、路由、证书、CDN 与服务端。
2. `NetworkCapabilities.getLinkDownstreamBandwidthKbps()` 是设备到所接网络的第一跳带宽估计，不是到目标服务的吞吐。
3. `SubscriptionInfo` 的流媒体速率上限（Android 17 新增，按材料核对）返回 `BITRATE_UNKNOWN` 时也不是测速值。

这些平台状态只作为请求策略输入。后台任务若关心“有网或非计费网”，优先使用 WorkManager/JobScheduler 约束；网络切换后不要统一清空连接池或立即重放全部失败请求。

**Q10: [learning] HTTP cache 与业务离线数据各管什么？onlyIfCached() 在缓存缺失时返回什么？**

OkHttp cache 遵守 HTTP 缓存语义：客户端只保存符合 `Cache-Control`、`ETag`、`Last-Modified`、`Vary` 规则的响应，服务端负责正确返回这些头。它不能替代 Room、SQLite 或文件层的业务离线数据——后者承载业务状态与查询能力，前者只缓存 HTTP 响应。

离线时若产品允许使用过期但可接受的 stale 响应，可显式构造只读缓存请求：`CacheControl.Builder().onlyIfCached().maxStale(...)`。cache miss 时返回 504 `Unsatisfiable Request`，不会自动访问网络；UI 要区分以下状态：

1. 没有缓存。
2. 缓存过旧。
3. 请求失败。
4. 已展示旧数据并正在刷新。

弱网降级可选择以下措施，并结合用户设置、metered、漫游、系统估计与近期请求观测：

1. 使用已有缓存。
2. 降低媒体分辨率。
3. 缩小分页。
4. 暂停自动播放。

策略使用滞回（升降级不同阈值）避免画质频繁切换。同一 Wi-Fi 可能拥塞，蜂窝也可能吞吐良好；transport 标签不能直接当质量判据。

**Q11: [learning] 网络性能日志应该记录什么、不应该记录什么？**

性能日志只记录低基数、经过白名单筛选的字段：

1. **可记录：**接口模板、协议、状态码、错误类别、分段耗时、metered/roaming 状态与缓存命中。
2. **不记录：**完整 URL、query、header、请求体、响应体、Cookie、Authorization 与 token。
3. **CDN 对照：**只采用白名单字段，例如协议、IP family、POP/cache 状态与分段耗时；客户端仅凭 TTFB 难以区分边缘排队、cache miss 与源站处理，应结合 CDN 日志及白名单传递的服务端 trace ID。

**Q12: [learning] OkHttp、Cronet 与平台 HttpEngine 应按什么边界选型？**

选择取决于协议需求、运行时交付依赖和目标设备支持，选型后仍要按线上协商结果与分段耗时验证，不能把库名称当作性能结论。

1. **OkHttp：**自管连接池，TCP 经 java.net.Socket，TLS 经平台 JSSE/Conscrypt；材料核对的 OkHttp 5.3.0 稳定协议栈为 HTTP/1.1 与 HTTP/2。
2. **Cronet：**使用 Chromium native 网络栈；需要 HTTP/3 时要评估 Play services provider 的安装、更新与缺失回退，fallback 实现能力较弱，不能预设与 Cronet provider 等价。一个进程通常只建一个 CronetEngine，多个 engine 不能共用 storage directory。
3. **HttpEngine：**API 34 起提供平台实现，具体可用能力受 Android 版本与设备实现约束。
4. **共同要求：**三者都要避免主线程网络与同步等待，并在运行时记录依赖版本、provider、协议协商结果和失败分段。
