# APM 专项原理与架构

> 学习资料（文章模式沉淀）。主线：APM 专项工具链的原理与工程边界——实验室测试与设备 Benchmark 的口径纪律，网络、崩溃与 ANR、耗电与发热、混合栈四条捕获链路的能力上限，以及千万级 DAU 下的端侧采集架构。源文档：android-internals-wiki §17.7《实验室测试工具与设备 Benchmark》、§17.8《网络 APM 底层捕获原理》、§17.9《崩溃与 ANR 捕获机制》、§17.10《耗电与发热监控 (Battery & Thermal)》、§17.11《混合栈与跨平台 APM (WebView / Flutter)》、§17.12《千万级 DAU 的 APM 端侧架构》；可本地核对的平台侧机制按 AAOS13 源码（Android 13）核对并标注版本差异（Recoverable GWP-ASan、ApplicationExitInfo.getAnrInfo、Thermal headroom 阈值 API、thermal HAL 的 AIDL 优先连接等均为 Android 14–17 能力，AAOS13 树中不存在或实现不同），第三方 SDK 与工具（PerfDog、SoloPi、OkHttp、Cronet、Flutter 等）与架构实践按材料口径转写、不确定处已弱化；GWP-ASan 默认参数、WakeLock 与 BatteryManager 语义、WebView 渲染进程回调、/data/anr 目录权限等已按 AAOS13 源码核对，官方文档级数值沿用材料注明的官方核对结果、本文未重复上网核对。ANR 超时契约与 trace 诊断见 [../performance/03-ANR.md](../07-performance/03-ANR.md)，BatteryStats 归因管线与功耗优化见 [../performance/05-功耗.md](../07-performance/05-功耗.md)；APM 平台选型与采集 SDK 见 [./06-APM-平台与SDK.md](./06-APM-平台与SDK.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: PerfDog 不要求被测 App 接入 SDK 也不要求设备 root，为什么仍不能用它代替线上 APM 和系统 trace 做根因定位？**

PerfDog 是实验室观测工具：覆盖受控环境中的单台或一组设备，输出帧率、CPU、内存、温度、功耗等外部指标曲线。线上 APM 负责汇总真实用户、真实网络与设备分布下的长期数据；函数、线程或缓存策略级的根因仍要 Perfetto、Android Studio Profiler、simpleperf 或业务埋点提供证据，一条外部曲线证明不了内部实现。

它的适合场景由这个定位推出：QA 在固定设备和固定脚本上做发版回归、快速筛出异常时间段、拿不到源码时观察第三方 App 的外部表现、经 PerfDog Service 接入实验室自动化。字段可用性取决于平台、设备、SoC、驱动与客户端版本，开始前应先查当前设备的可用指标列表，字段为空时不能拿 0 代替"未采集"；GPU 利用率与 Counter 只在部分机型支持，同一设备同一驱动上的版本对比价值较高，跨 SoC 的 GPU 绝对值通常没有可比性。

判断规则：PerfDog 的结论停留在"哪个版本、设备或时间段的外部指标异常"；要回答"哪个函数造成异常"，就把异常区间交给 Perfetto 或源码 trace 继续定位。

**Q2: PerfDog 的 Jank 用固定的 24 FPS 门槛判定，为什么在 120 Hz 屏幕上不能把这套 Jank 计数直接当系统卡顿分类使用？**

PerfDog 的 SmallJank、Jank、BigJank 用"当前帧时长大于前三帧平均值 2 倍，且大于约 41.67、83.33 或 125 ms"的固定门槛，前两档来自 24 FPS 电影帧时长，不随设备 60、90 或 120 Hz 刷新率变化；Android Vitals、JankStats 与 Perfetto FrameTimeline 的 jank 分类与此不同，口径不能互相替换。

问题出在预算错位：120 Hz 每帧预算约 8.33 ms，一帧 30 ms 已经错过多个刷新周期，却没有达到 SmallJank 的 41.67 ms 门槛；反过来 60 Hz 下一帧 40 ms 只错过约 1.5 帧也可能落进同一档。因此高刷新率测试要同时保留 FTime 分布、P95/P99、1% Low 和连续低帧区间，而不是只比 Jank 次数。Smooth（稳帧指数）数值越低越稳定，官方经验值"游戏或视频小于 8、滑动类 App 小于 20"是产品建议区间而非平台标准，门禁值应先用自身机型与场景建立基线。

帧率数字还依赖窗口选择：一个包名可能同时存在 Activity 主窗口、SurfaceView、TextureView 和独立游戏渲染层，选错窗口测到的是系统 UI 或静止层。测试前确认包名、前台 Activity 与 PerfDog 选中的窗口或 Surface 名称；可用 PerfDog Service 的 `getAppWindowsMap` 或 `dumpsys SurfaceFlinger --list` 的人工核对兜底，layer 名称相似不能当归属证据。

**Q3: 用 PerfDog 做功耗回归时为什么必须在 Wi-Fi 模式下拔掉 USB 采集 Battery Power？发现帧率退化后怎样组合证据判断是热降频？**

USB 连接线会给设备充电，USB 下采集的电流或功耗曲线混入充电电流，不能与断线后的结果放进同一组基线；PerfDog 官方把 Android Battery Power 明确定义在 Wi-Fi 模式下采集——先用 USB 建立 Wi-Fi 连接，成功后拔线再开始测试，报告必须写明连接方式。

Battery Power 是整机口径，屏幕、基带、Wi-Fi、后台进程和系统服务都包含在内，不是目标 App 的独占功耗，对比时要固定亮度、音量、网络和后台状态。`FPower` 的口径是 Power 除以 FPS、单位仍是 mW，只用于相近场景和帧率下的归一化比较；FPS 接近 0、场景静止或两组帧率差距很大时失去解释力，它也不是物理意义上的单帧能量。

热降频的判断需要同一时间轴上的证据组合：温度持续上升；CPU/GPU 频率出现台阶式下降；FTime P95/P99、1% Low 或 Stutter 同步恶化；相同操作标签处没有能单独解释退化的负载新峰值。单个温度值不等于系统已经限频，报告应写起止温度与系统 thermal status 而不是只贴峰值。平台侧可用 `adb shell dumpsys thermalservice` 与 `dumpsys powerstats` 交叉核验"系统侧是否有数据、变化时间是否一致"（服务名 `thermalservice` 按 AAOS13 源码核对）；注意版本差异——AAOS13 的 `ThermalManagerService` 位于 `services/core/java/com/android/server/power/` 下并按 HIDL 2.0、1.1、1.0 顺序连接 HAL，材料按 Android 17 核对的实现移到 `power/thermal/` 子包且优先尝试 AIDL。精确能耗实验应使用外置功耗仪，PerfDog 更适合相对变化监控。

**Q4: Geekbench、3DMark、安兔兔、PCMark 的分数在什么条件下才能放进同一组比较？"多核分数高所以多开线程一定快"错在哪里？**

分数只有在 Benchmark 应用版本、应用内测试项目版本、被测系统与驱动版本三个版本号一致，且温度、刷新率、电量等运行条件相同时才可比。大版本变更都改动了测试项或规则：Geekbench 6 到 7、安兔兔 V10 到 V11、PCMark Work 2.0 到 3.0 均不可比，旧序列应冻结在独立字段，厂商没有发布换算公式时不做经验换算。

多核分数的误读来自把代理变量当直接测量：Geekbench 分数由固定子测试按参考系统归一化后加权而来，表示"这套工作负载中的性能"，不表示任意 App 代码同比例变快；多核分数采用 shared-task 模型并只纳入现实任务适合并行的套件，回答不了"开八个线程快多少"——要先检查任务依赖、可并行比例、线程池拥塞与内存带宽，主线程上的串行关键路径不会因多核总分高而自动缩短。同理，Geekbench Compute 跑 Vulkan/OpenCL 计算负载，不覆盖着色、光栅化、合成与显示刷新，分析渲染问题要看 3DMark 图形测试、FrameTimeline 和 App 自身帧数据。3DMark Stress 的 stability（最低轮与最高轮平均帧率之比）必须连同每轮曲线解释：98% 可能来自"每轮都不快"，很高的首轮帧率配 65% 说明峰值强但持续性能弱。

做法上把工具语义写进字段名（`gb7_vulkan_compute`、`3dmark_wild_life_stability` 而不是笼统的 `gpu_score`），结论只引用相关分项加 App 证据；综合分可留在摘要，但"安兔兔总分高所以 SQLite 快"这类推论越过了证据边界。

**Q5: 做机型分层时为什么应保存分项能力向量而不是一个综合档位？Media Performance Class 能当作档位标准吗？**

综合分把不同资源结构压成一个数，会隐藏 App 依赖的那一维：总分相近的两台设备可能一台 CPU 强存储弱、另一台相反，图片解码重的 App 与冷启动读小文件多的 App 在两台设备上排序相反。分层应保存相互独立的能力向量——CPU 单核与多核、GPU graphics 与 compute、存储、RAM、热稳定性——各维度从对应的 Benchmark 子项或线上采集取值；四档（入门、中端、高端、旗舰）的边界从团队已冻结版本的设备样本分布中计算（例如按关键维度的 P20、P50、P80 切分），换 Benchmark 大版本或设备覆盖集后要重新计算。

Media Performance Class 不能当档位标准：它用 CDD 规定媒体、相机、内存、显示等能力下限并由 CTS 验证，只表达"能力下限标签"，表达不了 CPU 排名、启动 P95 或列表慢帧率。AAOS13 源码核对 `Build.VERSION.MEDIA_PERFORMANCE_CLASS` 从设备属性读取声明值、未声明返回 0；官方公开定义的等级是 30、31、33、34、35（没有 32），设备升级系统后仍报告原先满足的等级，不要自行创造"MPC 37"，也不要把 Android 版本号当作设备必然报告的等级。

工程连接方式是版本化设备字典：客户端只上报设备与系统标识，分析平台在服务端关联能力字段，事件里保留 `capability_schema` 一类的规则版本让历史数据可还原；分档用于灰度覆盖、性能预算和告警分组，Benchmark 版本变化只触发字典重算，不直接触发线上性能告警。

**Q6: 网络 APM 说"无侵入"，采集器实际插在哪里？为什么只记一个 attempt 数组的 schema 在 HTTP/2 和重试场景会失真？**

"无侵入"只指业务开发者不必在每个接口旁手写计时代码，采集器仍集中在五类位置之一：HTTP 语义层（OkHttp `Interceptor`、Cronet 回调）、客户端事件层（OkHttp `EventListener`、Cronet metrics）、构建期插桩层（AGP Instrumentation API 加 ASM 改字节码）、Native 动态链接层（PLT/GOT Hook）、系统与内核层（TrafficStats、BPF）。各层观测对象不同，能回答的问题也不同——语义层看得到状态码但分不出 DNS 与 TLS 边界，内核层看得到 UID 字节但看不到 TLS 内的 URL。

失真的根源是"HTTP 请求、DNS 查询、建连尝试、socket"不是一一对应：一次调用可能复用连接、一次解析可能并行尝试多个地址、HTTP/2 与 HTTP/3（QUIC）允许多个 stream 共享连接。样本要拆成四条相互关联的记录：`call`（业务发起的一次调用）、`dns_span`（一次解析区间，可零或多次）、`route_attempt`（对某 IP、端口、代理的一次连接尝试）、`exchange`（一轮实际发送的 request/response，重定向、鉴权挑战和故障恢复都会产生新 exchange）；关联键取自同一客户端实例的事件，底层 socket 数据还原不了 stream 时只保留连接维度关联并写入可信度。`attempt_count` 这类含糊字段应拆成 `route_attempt_count`、`exchange_count`、`retry_decision_count` 与 `follow_up_count`。

**Q7: 用 OkHttp EventListener 采集网络指标时，哪些事件序列例外会破坏"一次 call 一条记录"的假设？注册为什么必须用 eventListenerFactory 而不是 eventListener？**

四类例外都会让线性假设失效：连接复用时代理选择、DNS 和 connect 事件可能全部缺席；retry 与 follow-up 会重复产生事件序列，且 `requestFailed`、`responseFailed` 都不必然终止整个 call；`Expect: 100-continue` 与 duplex body 会让 body 事件与其他事件交错；除取消外事件通常顺序发生，但 `canceled` 可与其他回调并发甚至晚于 `callEnd`。

应对方式是 per-call listener：每个 Call 独占一个监听器实例，按事件顺序维护状态，每次 `requestHeadersStart` 新建一个 exchange，使 `requestFailed` 或 `responseFailed` 之后的恢复不会覆盖上一轮的数据；`connectEnd` 与 `connectFailed` 依靠地址、端口和代理找回对应 route，已有 API 无法唯一配对时保留原始时间线并标记配对可信度低。状态修改用 per-call 私有锁且锁内不做 I/O，回调必须快速返回、不抛异常、不修改参数。注册必须用 `eventListenerFactory(...)` 让每个 Call 获得独立实例；`eventListener(listener)` 会在并发调用间复用同一实例，只适合没有 per-call 可变字段的监听器。语义层（Interceptor）与事件层通过 `Request.tag()` 中不含用户信息的 request id 关联。

版本边界：OkHttp 4.3 以前 `responseHeadersStart` 在客户端准备读取 header 时就过早触发，4.3 起才表示服务端响应 header 开始返回——旧版本不能沿用基于它的 post-send wait 算法。

**Q8: 网络 APM 的 Exchange TTFB 和 post-send wait 应该怎么计算？"connectStart 到 connectEnd 就是 TCP 握手"为什么是错的？**

Exchange TTFB 等于 `responseHeadersStart` 减 `requestHeadersStart`，即从开始写 request header 到首个响应 header 字节的区间；post-send wait 等于 `responseHeadersStart` 减 sendEnd（有 body 取 `requestBodyEnd`，无 body 取 `requestHeadersEnd`），且必须同时满足三个条件才成立：OkHttp 不低于 4.3、`responseHeadersStart` 不早于 sendEnd、request 不是 duplex 也没有 `Expect: 100-continue` 造成事件交错。条件不满足时记 null 并写明重叠原因，负数取绝对值或强制归零会掩盖协议行为。

"connectStart 到 connectEnd 是 TCP 握手"错在 HTTPS 下 `connectEnd` 发生在 `secureConnectEnd` 之后，这段区间包含 TLS；直连时 `connectStart` 到 `secureConnectStart` 才接近 TCP connect，经过 HTTP 代理时还混入 CONNECT 隧道协商，只能叫 pre-TLS transport，不能标成 TCP 再与 TLS 相加。同一 exchange 可能先收到 1xx（100 Continue、103 Early Hints），应单独保存或计数 informational header block——上表的 `responseHeadersStart` 指最终非 1xx response 的起点，101 作为协议升级的终止 response 处理；拿首个 1xx 覆盖最终 response 会同时破坏状态码和 TTFB。

"Server Wait"（post-send wait 的旧称）包含上行尾部、网络 RTT、服务端排队与执行、下行首字节，端侧测到高只能说明这条路径慢，不能单独证明后端慢；需要后端耗时应结合可信的 `Server-Timing`、分布式 trace 或服务端日志。另外 `responseBodyEnd` 减 `responseBodyStart` 是应用读取或关闭 body 的消费窗口，提前 close 时字节数小于总长是有效状态，不是丢数据。

**Q9: 对 HttpURLConnection、Cronet 和纯 Native 网络库，网络 APM 分别能捕获到什么程度？**

没有一种手段是通用兜底，每条通道的能力上限由它的入口决定，选型按覆盖率和风险推进。

`HttpURLConnection` 与 `java.net.URL` 定义在 libcore 的 boot classpath 里（AAOS13 树中 `libcore/ojluni/src/main/java/java/net/` 已核对存在），应用无法修改，只能在构建期用 AGP Instrumentation API 加 `AsmClassVisitorFactory` 改字节码：`openConnection()`、`openConnection(Proxy)`、`openStream()` 三个 method descriptor 必须分别匹配替换成静态桥接（原 receiver 成为静态方法第一个参数），只 Hook 无参重载会漏掉代理重载和 `openStream()`；桥接层所在包必须排除出插桩范围，否则桥接再次调用 `openConnection()` 会递归。它没有 DNS/TLS 回调——`connect()` 返回不等于纯 TCP 完成，`getInputStream()` 可能同时建连、写请求、读响应头，不能从几个 Java 方法时间点伪造完整协议阶段。

Cronet 应优先用官方 `RequestFinishedInfo.Metrics`（DNS、connect、SSL、sending、TTFB 等），用 `addRequestAnnotation()` 放入不含敏感信息的 request id 关联。QUIC 字段语义要显式区分：`connectEnd` 在 TCP 与 SSL 完成之后、QUIC 0-RTT 下甚至可能晚于 `sendingStart`；QUIC 的 `sslStart/sslEnd` 与 `connectStart/connectEnd` 对齐、不能相加；socket reuse 为 true 时 DNS、connect、SSL 为空。redirect 字节数是否累计在文档内部口径不一致，应按实际 provider 做契约测试而不是自行决定。

Native 层优先接库的稳定回调；PLT/GOT Hook 只对经过动态符号入口的调用有效（`getaddrinfo`、`connect`、`send`、`recv`、`SSL_write` 等），而 Cronet 常把 BoringSSL 与网络栈静态链接、自研库可能隐藏符号或直接 syscall，覆盖率可能很低——`SSL_write` 的输入长度不等于链路发送字节，`send`/`recv` 也看不到内核 TCP 重传。eBPF 需要加载与 attach BPF 程序的 capability、SELinux 域和文件权限，量产三方应用通常不具备，可行场景限于 ROM、企业专管设备、root 或工程机诊断、CI 实验室；即使拿到 UID 流量与 TCP RTT，也无法从 TLS 密文恢复 URL、header 或 stream。

**Q10: 网络 APM 的脱敏为什么必须在端侧生成内存样本之前完成？事件回调里哪些操作必须禁止？**

服务端脱敏只能处理端侧已经泄露之后的数据：原始 URL、header 或 body 一旦写入样本结构、日志或崩溃附件，就已经离开设备。所以安全控制必须发生在样本生成前——先用业务路由注册表把 `/user/12345/order/888` 归一成 `/user/:userId/order/:orderId`，未命中的 path 只上报允许的 host、path 段数和模板 hash；query 的 value 默认全部丢弃、key 只保留允许清单中的名称；fragment 不会发给 HTTP 服务端，APM 也不应采集；header 默认允许清单只放 `content-type`、受控的 `content-length` 和专用 trace id，`authorization`、`cookie`、设备 id 直接丢弃；body 默认不采内容、只记录经过校验的长度与 MIME，确需采样要同时满足 endpoint 与 schema 命中、结构化字段级脱敏、小字节上限、采样与授权审计。普通 hash 只是可关联的假名，确需关联用户时使用服务端管理、定期轮换的 HMAC key。

回调侧的纪律来自它在请求关键路径上：只读取时间与计数、写入固定大小结构或有界 ring buffer；禁止序列化 JSON、写文件、查数据库、打印日志或发请求。`System.nanoTime()` 只用于同一进程内计算时长，不能持久化后与 wall clock 比较；URL 归一化避免灾难性回溯的正则和大字符串复制。队列满时丢弃 APM 样本而不阻塞业务请求，上报端排除采集器自己的网络请求避免递归；大量故障同时出现时设置每分钟上限、按 route 或错误类型分组的 reservoir sampling 和丢弃计数，防止错误期进一步放大开销——失败样本诊断价值更高，降级时应先停 body 采集、降低成功样本率，让失败样本保留得更久。

**Q11: APM 代理 Thread.setDefaultUncaughtExceptionHandler 时，为什么必须保存并继续调用 previous handler？多个 SDK 各自接管会出什么问题？**

Android 的默认终止链由 `RuntimeInit.commonInit()` 安装的两层处理器组成：`LoggingHandler` 作为 VM 内部的前置处理器输出 fatal 日志，`KillApplicationHandler` 作为 `Thread` 的默认处理器向 ActivityManager 报告崩溃并终止进程（AAOS13 源码核对，`frameworks/base/core/java/com/android/internal/os/RuntimeInit.java`）。应用的 `setDefaultUncaughtExceptionHandler()` 只替换第二层，APM 安装前必须保存当前 default handler 并在采集结束后调用它——否则系统拿不到崩溃报告、终止链被截断。

崩溃当下堆与锁可能已经损坏，handler 里只写字段受限、有字节上限的 envelope（magic、schema version、时间、线程、异常类型和预存 breadcrumb 的序号），栈序列化限制深度与总字节数；同步等待设很小的硬超时，绝不打开数据库、压缩或发网络请求，冷启动后再校验 checksum、补齐符号化字段并上传。用 CAS 防止采集器重复进入，`previous` 链成环或重复崩溃时立即终止进程。

多 SDK 各自接管的问题：后安装者覆盖先安装者，即使每家都保存 `previous` 仍可能出现 A 到 B 再回 A 的循环链、多个 handler 依次等待 I/O 超过系统容忍、某个 handler 捕获异常后返回截断终止链，或 SDK 延迟初始化在启动检查之后悄悄覆盖。工程上指定统一负责者：由应用的 stability hub 安装唯一代理并保存系统 default handler，自研 sink 只读内存事件或有界存储，第三方 SDK 优先选"不接管 handler"的回调模式，无法关闭接管时固定初始化顺序并在启动后校验 handler 的对象身份。

**Q12: Native 崩溃的 signal handler 里为什么只能做"读取上下文并通知外部进程"这一件事？**

同步致命信号（`SIGSEGV`、`SIGABRT`、`SIGBUS`、`SIGILL`、`SIGFPE`、`SIGTRAP`）由当前线程执行的指令直接触发，到达时堆、锁或栈可能已经损坏，handler 只允许 async-signal-safe 操作：读取 `siginfo_t`（信号原因与 fault address）、`ucontext_t`（寄存器上下文）和当前 tid，把定长结构写入预先打开的 pipe 或 socket，或通知 Crashpad handler。

`malloc/new`、STL 扩容、普通日志与 JSON、`pthread_mutex`、JNI 回调、数据库操作、在未知栈状态下调通用 libunwind、直接上传网络，都可能在已损坏的运行时里二次崩溃。完整 unwind、maps 读取和 minidump 生成应交给外部进程：Crashpad 的设计把客户端与独立 handler 进程分开，客户端预先注册，崩溃时只通知异常上下文的位置，handler 进程读取目标进程写 dump——进程外设计少依赖已损坏的堆和锁，但 handler 进程的启动、注册、权限与生命周期要在正常运行期准备好。客户端最后应恢复或转交系统 signal 处理语义，让 debuggerd 与 tombstoned 继续生成系统证据；自研 handler 吞掉 signal 会同时损失系统 tombstone、Android Vitals 的原因分类和后续 SDK 的处理机会。

共存与符号化边界：与前一个 handler 共存要处理 `SA_SIGINFO` 回调签名、signal mask、`SA_RESETHAND`、备用栈与重入，同步产生的 `SIGSEGV`/`SIGBUS` 被 `SIG_IGN` 忽略后通常会再次触发；符号化依赖每个 `.so` 的 build id——只按 version name 选择符号，遇到热修复或重打包会匹配到错误版本，而符号化错了调用栈看起来仍然完整。

**Q13: 从崩溃采集的视角看，为什么三方 APM 不能轮询 /data/anr、注册 SIGQUIT handler 或 Hook ART SignalCatcher 来抓系统 ANR？**

系统 ANR 的取证入口全部在系统侧。`/data/anr` 目录由 init 以 `0775 system system` 创建（AAOS13 `system/core/rootdir/init.rc` 已核对），内部文件还受 SELinux 强制访问控制、文件属主与系统服务检查共同限制，三方生产应用读不到，轮询、inotify 或反射调用系统服务都不是稳定方案。系统收集 Java 线程 dump 时发出的 `SIGQUIT` 先在所有线程的 signal mask 中被屏蔽，再由 ART 的 `SignalCatcher` 线程通过 `sigwait()` 同步接收（AAOS13 `art/runtime/signal_catcher.cc` 已核对），因此应用再注册的 `sigaction` handler 根本不会被调用——这就是"ANR 取证用 SIGQUIT，应用却收不到"的原因。所谓 Signal Catcher Hook 要改信号掩码、拦截 ART 内部符号或修改 libsigchain，与 Android 版本和 ART 实现强耦合，只适合厂商 ROM、root/userdebug 诊断或可回滚实验。

三方应用能做的只有应用端 main-looper watchdog：定时检查主线程消息循环是否还能响应，在卡顿期间多次采主线程栈，提前发现耗时消息、锁等待和 Binder 阻塞。它没有 system_server 掌握的输入分发、广播、Service、ContentProvider 等超时上下文，所以只能标记 `suspected_anr`；系统 ANR 结论以后续 `ApplicationExitInfo`、Vitals 或系统 trace 为准。ANR 各类超时契约与 trace 解读的机制层见 [../performance/03-ANR.md](../07-performance/03-ANR.md)。

**Q14: 用 ApplicationExitInfo 补采集崩溃与 ANR 证据时有哪些字段语义陷阱？Android 13 与 Android 17 的能力差在哪里？**

API 30 起可以通过 `ActivityManager.getHistoricalProcessExitReasons()` 读取当前 UID/包的历史退出记录（AAOS13 已核对存在），它是下次启动后的补充证据，不能替代进程内 crash handler。读取时的语义陷阱：

- `REASON_ANR` 的 trace 通常是系统 ANR 文本流，且流可能为空；
- API 31 起 `REASON_CRASH_NATIVE` 返回按 `tombstone.proto` 编码的 protobuf（AAOS13 源码已核对），不能按 UTF-8 文本解析；
- trace 位于全局环形存储，容量用完后覆盖较旧记录，其他应用的新记录也会占用这份容量；
- 进程发生 ANR 后恢复、再因其他原因退出时，退出记录仍可能带 ANR trace——trace 存在不等于 `reason == REASON_ANR`；
- PSS/RSS 是系统最近采样值，0 也可能表示来不及采样；
- `description` 是面向人阅读的说明，格式不保证跨设备版本稳定，不能当协议切片解析；
- 不支持 LMK report 的设备把低内存 kill 记为 `REASON_SIGNALED` 加 `SIGKILL`，这个组合只能标"可能 LMK"——`SIGKILL` 也可能来自 force-stop、shell 或其他系统策略。

版本差异：API 37 新增 `getAnrInfo()`（ANR 类型、超时时长等结构化信息），AAOS13 源码中不存在该方法，Android 13 只能用 reason 字段加 trace 文本；更早的 Android 8–10（API 26–29）连 `ApplicationExitInfo` 都没有，系统 ANR 与 LMK 对普通应用没有本地查询 API，只能用 watchdog 标疑似事件、用 heartbeat 与正常退出标记识别"非正常消失"并保留 unknown，系统级证据依赖 Vitals、bugreport 或厂商渠道。

**Q15: GWP-ASan 为什么能以极低开销在线上抓到 heap use-after-free？Android 13 与 Android 17 的默认行为差在哪里？**

GWP-ASan 是 Native 堆内存错误的抽样检测器：随机抽取少量 allocation 放入由 guard page 隔离的区域，释放后访问或越界读写会立即落在保护页上触发 fault，从而以很低的 CPU 开销取得真实错误现场。它不要求重编译第三方 Native 库，只做检测、不提供内存安全防护。

应用用 manifest 的 `android:gwpAsanMode` 控制开关（AAOS13 的 `attrs_manifest.xml` 已核对该属性存在）；AAOS13 源码核对 bionic 默认参数：`SampleRate` 2500（被选中进程平均每 2500 次分配抽取一次）、`ProcessSampling` 128（约每 128 个候选进程选中一个）、`MaxSimultaneousAllocations` 32。这些是实现内部参数不是 SDK 契约，普通应用也不应通过 `libc.debug.gwp_asan.*` 系统属性做线上配置。

关键版本差异在命中后的行为：Android 14 起默认走 Recoverable 模式——命中后生成 tombstone 再允许进程继续运行，且代表 Recoverable fault 的 `SIGSEGV` 不会调用应用自定义 signal handler（材料按 Android 17 与官方文档核对）；AAOS13 的 bionic 中没有 Recoverable 路径（已核对），A13 上默认抽样与 `always` 模式命中即按致命崩溃终止进程。因此 APM 在两个版本上都应保留系统 tombstone、GWP-ASan 错误类型、分配/释放/非法访问调用栈和模块 build id，不能把系统 handler 的内部实现复制进应用自己的 signal handler；"自己判断并放行以保持进程运行"的设计只在 A14+ 的 Recoverable 语义下才被平台明确排除，A13 上命中本来就是致命崩溃。

**Q16: 监控线上 OOM 时为什么先要区分失败的资源？哪些信号必须靠正常运行期预采样而不是崩溃时抓取？**

"OOM"只是结果名的一部分：Java heap（`OutOfMemoryError` 与 GC 频繁回收少）、Native/graphics 内存、FD 表、线程、虚拟地址空间和系统低内存 kill 的失败路径与证据各不相同，先区分失败资源才能选择有效证据，也不能把所有线程创建失败都标成 Java heap OOM。

各资源的可观测信号与误判点：FD 耗尽表现为 `open`/`socket`/`dup` 返回 `EMFILE`，预警阈值应按当前用量与 `RLIMIT_NOFILE` 的比例计算并保留 FD 类型分布，固定写"超过 1024"不适配不同设备；线程耗尽表现为 `pthread_create` 返回 `EAGAIN` 或 Java 建线程失败，要看线程创建速率、池大小与排队深度而不是瞬时总数；VMA 风险在 32 位进程更早暴露（有限地址空间要同时容纳 `.so`、JIT 代码、线程栈与图形映射），64 位进程很大的 `VmSize` 可能只是预留地址范围、不代表物理内存驻留；系统低内存 kill 由用户态 lmkd 结合 PSI 与 `oom_score_adj` 选择目标（AAOS13 树含 `system/memory/lmkd`），且无回调地消失还可能来自 force-stop 或掉电。

预采样是唯一可靠路径：正常运行期按前后台与业务阶段低频、错峰读取 `/proc/self/status` 的 `VmRSS`、`VmSize`、`Threads`，结合 `Debug.MemoryInfo`；统计 `/proc/self/fd` 数量并在诊断抽样中解析类型；记录线程创建速率与队列等待。资源接近限制后要降低采样频率、停止高成本分类——继续频繁扫描 `/proc` 或抓 heap dump 可能成为压垮进程的额外负担；RSS/FD/线程数在崩溃现场应使用崩溃前最近一次采样，而不是在 signal handler 里临时扫描。

**Q17: 耗电 APM 为什么不能把"方法执行了多久"换算成 mAh？应该记录哪三层证据？**

端侧没有把代码与能耗直接关联的测量面：电池百分比经过 fuel gauge 平滑，瞬时电流混合了屏幕、基带、GPU、充放电和其他进程的影响，把一段方法耗时换算成 mAh 只会得到一个看似精确的误差值。可靠方案是记录资源事件——哪个任务申请了 WakeLock、哪个 Alarm 被投递、网络传了多少字节、哪些线程持续耗 CPU——再与系统 Thermal 状态按单调时钟对齐。

证据分三层，字段名应明确区分：业务请求（`acquire()`、`setExact()`、`requestLocationUpdates()`）只说明应用表达了需求；应用可见结果（回调到达、扫描成功、网络字节）说明该次调用成功，但不等于整个硬件活跃窗口；系统或实验室统计（batterystats、Perfetto、Power Profiler、Macrobenchmark `PowerMetric`）才有 UID 与硬件状态口径——普通应用进程拿不到 `BatteryStats` 的同等完整数据，开发期可用 `dumpsys batterystats` 与系统 trace 核对。

由此推出几条"API 窗口不等于硬件窗口"的边界：BLE `startScan()` 返回不代表整个窗口内硬件持续扫描；`TrafficStats` 是 UID 累计字节、看不到 modem tail time 与能量，只能做"蜂窝耗电风险"级别的风险判断；任务运行 30 秒墙钟不代表占用 30 秒 CPU——等待网络、锁或 Binder 时 CPU time 只增加少量，多线程 2 秒墙钟内累计 CPU 时间甚至可能超过 2 秒，判断 CPU 消耗要同时保存 CPU 时间与墙钟时间。BatteryStats 的归因管线与设置页百分比口径见 [../performance/05-功耗.md](../07-performance/05-功耗.md)。

**Q18: 线上监控 WakeLock 时为什么要区分"申请过、当前持有、最终释放"？Android 13 的 WakeLock 对象有哪些必须写进模型的语义？**

WakeLock 的耗电影响取决于实际持有时长而不是调用次数：最常见的 `PARTIAL_WAKE_LOCK` 在屏幕关闭后继续保持 CPU 运行，"申请过、当前持有、最终释放"必须分开建模，否则 timeout 自动释放、引用计数错配都会被误读为业务持有。

AAOS13 源码核对（`frameworks/base/core/java/android/os/PowerManager.java`）的对象语义：构造时创建私有 Binder token `mToken`，`acquire` 经 `IPowerManager.acquireWakeLock` 用 token 注册、`release` 用同一 token 释放；默认启用引用计数（`mRefCounted = true`），同一对象 acquire 两次通常要 release 两次，`setReferenceCounted(false)` 后一次 release 即撤销持有；`acquire(timeout)` 通过框架内部的 `mReleaser`（带 `RELEASE_FLAG_TIMEOUT`）安排释放，这条内部释放不经过业务代码的公开 `release()` 调用点；release 次数超过申请次数会抛 under-locked 异常。

监控做法：插桩挂在四类公开调用成功返回之后（`newWakeLock`、`setReferenceCounted`、`acquire`、`release`），对象注册表用弱引用加 identity 语义，避免 APM 自身延长锁的生命周期；`acquire(timeout)` 之后在 deadline 加短宽限期处用 `isHeld()` 做保守探核——它只是一瞬间的观测，为 true 也可能是又一次 acquire。不要反射 `mToken`、`mRefCounted` 等私有字段：隐藏 API 限制、R8、厂商改动和并发访问都会让路径失效，token 也没有上传价值。告警规则按锁等级、前后台、是否提供 timeout、生命周期归属与同类样本分位数区分，固定"持有超过 30 秒就是泄漏"会大量误报。

**Q19: 追踪 Alarm 的耗电影响时为什么要把设置、接受、取消、投递分成四个事件？精确 Alarm 权限在 Android 12 到 17 之间怎么变化？**

schedule attempt（业务发起设置）、accepted/rejected（系统是否接收）、cancel/replace（计划被撤销或被同身份新计划覆盖）、delivery（回调真正到达）是四个独立事件：设置成功不代表投递，Doze 与配额都会推迟执行，APM 不能用请求时间推算确定的投递时间，也不能把一次获准调用描述成"绕过 Doze"。只有 `RTC_WAKEUP` 与 `ELAPSED_REALTIME_WAKEUP` 属于 wakeup alarm——投递时系统唤醒设备并在回调期间持有 partial WakeLock；非 wakeup 类型不能一概记录成"唤醒设备"。

权限版本线（AlarmManagerService 属 jobscheduler 模块、不在本地 AAOS13 树，以下按材料与其注明的官方文档转写）：Android 12 对精确 Alarm 引入 `SCHEDULE_EXACT_ALARM` special app access（target 31+ 需声明并获授权，豁免应用除外）；Android 13 起 target 33+ 可按合规场景改用 `USE_EXACT_ALARM`——安装即授予、用户不可撤销，但只适用闹钟、计时器、日历等核心场景并受商店政策约束；Android 14 起 target 31+ 未获授权时 `setExact()`、`setExactAndAllowWhileIdle()`、`setAlarmClock()` 抛 `SecurityException` 而不是自动降级为非精确，`OnAlarmListener` 路径不要求该权限，但 Android 17 中调用进程进入 cached 状态后系统可丢弃 listener Alarm。

工程做法：调用前记录 `canScheduleExactAlarms()`、调用后记录成功或异常，收到授权变更广播后重新核查；样本分别保存 `requested_exact`、`accepted`、`delivered`；业务 tag 用低基数稳定 ID（`daily_sync` 之类），把时间戳或用户 ID 放进 tag 会让每条记录都像新任务并泄露隐私；可延迟的同步与上传优先 WorkManager，并把"入队"与"执行"分开记录。

**Q20: BatteryManager 的电流、电荷、能量属性在 Android 13 上语义是什么？为什么不能用它给单个方法计费？**

AAOS13 源码核对（`frameworks/base/core/java/android/os/BatteryManager.java`）：`BATTERY_PROPERTY_CURRENT_NOW` 是瞬时电流（µA，正数表示净电流流入电池、负数表示电池放电）；`BATTERY_PROPERTY_CURRENT_AVERAGE` 是平均电流（µA，平均窗口由 fuel gauge 硬件及配置决定）；`BATTERY_PROPERTY_CHARGE_COUNTER` 是剩余电荷（µAh）；`BATTERY_PROPERTY_ENERGY_COUNTER` 是剩余能量（nWh）。设备不支持某项 long 属性时 `getLongProperty()` 返回 `Long.MIN_VALUE`，采集端要把"不支持"记成独立状态，不能记成 0。

这些值适合两类用途：同一设备、相同充电状态下的实验对照——例如比较某版本在"未充电、屏幕状态相近、网络类型一致"的会话中资源事件与放电电流分布是否一起变化；以及线上低频、低比例采样的上下文字段。不适合的场景：用一次 `CURRENT_NOW × 电压 × 方法耗时` 计算某个方法的能量（瞬时读数与该方法没有因果）；跨机型比较绝对电流（采样周期、滤波、符号与精度都有设备差异）；在充电、电量校准或温控强降频期间推导业务成本；高频轮询后忽略采样本身的开销。

精确功耗评估放到实验室：固定设备、亮度与网络条件，使用 Power Profiler、系统 trace、Macrobenchmark 电源指标或外接功耗仪。

**Q21: Thermal status 与 thermal headroom 分别适合驱动什么决策？Android 13 上这套 API 覆盖到什么程度？**

Thermal status 是系统汇总后的整机热节流等级（`NONE` 到 `SHUTDOWN`），适合转成统一的资源预算——各类任务允许的并发数、执行频率和质量规格上限；thermal headroom 是接近 `THERMAL_STATUS_SEVERE` 阈值的归一化指标，1.0 表示当前或预测会到达 SEVERE 阈值，适合游戏、相机、视频等需要短期预测的业务提前减小负载。值大于 1.0 不对应确定的更高状态，不能自行线性映射成 CRITICAL；返回 `NaN` 表示本次没有可用数值（不支持、样本不足或调用过频），不能参与大小比较。

AAOS13 的覆盖范围（源码核对）：Android 10 的 `getCurrentThermalStatus()` 与 `OnThermalStatusChangedListener`、Android 11 的 `getThermalHeadroom(forecastSeconds)`（预测 0 到 60 秒）都已存在；Android 15 的 `getThermalHeadroomThresholds()` 与 Android 16 的 `OnThermalHeadroomChangedListener` 不在 A13——A13 只能按受控频率主动轮询 headroom 并处理 NaN。服务端注册行为 A13 已核对：`ThermalManagerService.registerThermalStatusListener()` 注册成功后立即把当前 status 投递给新 listener，应用再手工查一次会产生重复事件，直接依赖注册后的初始回调即可。

策略设计：升温时立即收紧预算，降温时延迟分级恢复（迟滞避免状态在阈值附近波动时反复启停下载、视频与动画）；每项策略要幂等，NORMAL 预算必须显式恢复此前改变过的所有参数。status 不提供摄氏度、也不证明热量来自当前 App——业务负载先出现、状态随后升高、减载后下降只是增强因果判断，还需要对照实验与系统 trace。调试可用 `adb shell cmd thermalservice override-status` 注入状态、`reset` 解除（AAOS13 的 ThermalManagerService 已核对该命令存在，仅测试环境使用）。

**Q22: WebView 页面的"加载完成"有哪几个互不相同的口径？为什么不能从 loadEventEnd 推导一个 TTI？**

至少五个口径回答不同的问题：`onPageFinished()` 表示 main frame 文档加载完成，官方语义明确不保证下一帧已包含当时的 DOM；`onPageCommitVisible()` 表示旧导航内容不会再被绘制，下一次 draw 可能只显示 WebView 背景；FCP 记录首个文本或图片开始绘制；LCP 跟踪加载期间最大内容元素候选；业务 `app_ready` 由产品定义，表示数据、路由和关键交互可用。它们必须分开上报，不能压成一个"首屏耗时"。

TTI 的教训：它试图估计页面进入稳定可交互的时刻，但浏览器没有对应的 Performance Entry，Lighthouse 10 已因它对偶发网络请求和 Long Task 过于敏感而移除。线上 WebView 应采用明确的业务 `app_ready`，按 provider 支持情况补充 LCP、Long Tasks 或 INP，不能从 `loadEventEnd` 推导一个名为 TTI 的值。

SPA（same-document 导航）还要另立边界：页面内导航不产生新的 Navigation Timing 也不重置 LCP，所以 H5 路由层要自己发出独立的 `route_id` 与 route start/ready，一次 document load 的 LCP 不能重复算到每个 soft navigation 上。LCP observer 给出的是候选序列，应在首次输入、页面隐藏或 pagehide 时上报末个候选并保留 `finalizationReason`；页面长期可见且无输入时的超时快照只能标 `lcp_candidate`，不能伪装成已结束的 LCP。能力缺失（provider 不支持某 entry type）写 `unsupported`，不能当成 0。

**Q23: WebView 的 performance.now() 与 Flutter FrameTiming 的 raw timestamp 都不能直接和 elapsedRealtime 对齐，正确做法分别是什么？**

三套时钟参考起点不同：Web 指标相对当前文档的 `performance.timeOrigin`；Flutter 的 raw timestamp 只保证同一组 `FrameTiming` 共用同一 epoch，官方不保证它等于 Dart `DateTime` 或 Android `elapsedRealtime`；Native 事件用 `elapsedRealtime`。直接把两套数值配对会制造伪精确时间。

WebView 的校准方法是多次往返采样：Native 在调用 `evaluateJavascript("performance.now()")` 前后记录 `t0`/`t1`，暂用中点估计 JS 执行时刻、往返时间的一半作为保守不确定度，取多次采样中往返最小的一组求 offset（`nativeElapsed ≈ jsTime + offset`）；每次 main-frame 导航重新校准，进程从长时间后台恢复后再查漂移。端到端 FCP 按"映射后的 FCP 时刻减容器开始时刻"计算，不能把可能重叠的阶段时长相加；wall clock 只用于跨设备日志关联并额外记录时钟偏移。

Flutter 的边界：raw timestamp 只用于同组做差、排序和按秒聚合；Native 收到 MethodChannel 批量数据的接收时间只说明"这一批最迟此时到达"（release 约每 1 秒、debug/profile 约每 100 ms 交付，首帧立即），每帧的真实时刻在批次窗口内不确定，误差不能写成对称的 ±1000 ms。要与 Native ANR、Binder 调用或合成帧精确关联时，在同一次测试中核对 Perfetto 的 engine、Choreographer 与 sched 轨迹。通用原则是把 `source_time`/`source_clock` 与 `observed_elapsed` 分开存并记录 `uncertainty`，缺失写 unknown 不写 0。

**Q24: H5 白屏检测为什么要组合生命周期、DOM 和像素三类信号？PixelCopy 失败时样本应该怎么记？**

每类信号都有各自的误判场景，单独使用都不可靠：生命周期事件（`onPageCommitVisible()`、visual-state callback、`onPageFinished()`）成本低，但页面可能只画了背景；DOM 与业务信号（可见节点面积、文本图片、`app_ready`）会被 canvas/WebGL、video 和骨架屏欺骗；像素信号接近用户所见，但分不清合法白色页面、深色主题和遮罩。所以判定用状态机推进：loading、visual_committed、suspected_blank、confirmed_blank、inconclusive，`confirmed_blank` 要求间隔采样仍为空并排除 renderer gone、网络错误、容器隐藏和合法空状态。

PixelCopy 的正确姿势：Android 8（API 26）起可把 Window 指定 Rect 中已合成的像素异步复制到 Bitmap（AAOS13 已核对存在 Window 重载），源区域会缩放到目标 Bitmap，用低分辨率样本即可；调用前要求 WebView 已 attach、可见且尺寸有效，Window 已取得 surface。`postVisualStateCallback()` 表示"调用时的 DOM 状态已为下一次 draw 准备好"而非"这一帧已显示"，回调后还要安排下一次 draw 再做像素采样。结果分三态：成功得 `Classified`（是否白屏与分数）；PixelCopy error、空区域或未 attach 一律记 `Inconclusive`（带原因），不能算"非白屏"；Bitmap 用完立即回收、截图不上传，采样 Rect 要排除 Native toolbar 与骨架层。

后台边界：页面进入后台后 JS timer 与 renderer 都可能被节流或冻结，此时 heartbeat 超时与无像素只能记不可判定；线上按关键超时点采样一到两次而不按帧截图，白屏检测自身的 CPU、Bitmap 字节和执行时长也要进入 APM 自监控。

**Q25: WebView renderer 退出和无响应分别通过什么 API 感知？为什么 renderer 崩溃不会触发 App 的 crash handler？**

Android 8 起 WebView 可把网页代码执行与绘制放在独立的 sandboxed renderer 进程中，renderer 退出时 App 进程未必退出，只接 Java/Kotlin crash handler 会漏掉一类白屏、黑屏与页面重载。直接证据是 `WebViewClient.onRenderProcessGone()`（AAOS13 已核对）：`didCrash()` 为 true 表示 renderer crash，为 false 通常表示系统终止了 renderer；状态信号是 API 29 的 `WebViewRenderProcessClient.onRenderProcessUnresponsive()`（AAOS13 已核对，注释明确两次回调最短间隔 5 秒，恢复时 `onRenderProcessResponsive()` 回调一次）。

处理顺序：`onRenderProcessGone()` 回调后该 WebView 已不可用——把它移出视图树、`destroy()` 并清除 Activity、Fragment、adapter 和缓存中的引用，完成清理并决定后续界面后返回 `true`；返回 `false` 会让 renderer crash 连带 App crash 或被系统结束。一个 renderer 可服务同一 App 的多个 WebView，每个受影响实例都会收到回调。恢复策略按业务幂等性决定，不自动重放 POST、表单和支付导航。unresponsive 回调在 renderer 持续无响应期间可能重复到达，APM 对 repeat 做计数而不是每 5 秒生成一条独立故障；收到后不应默认调用 `terminate()`——它会直接结束关联 renderer，可能影响同 renderer 的其他 WebView。

证据分级：API 26–28 没有平台 unresponsive 回调、provider 也不支持兼容接口时，只能组合业务 ready 超时、Bridge heartbeat、DOM 状态和像素采样，这些信号不能证明 renderer 已卡死（后台节流、长任务、网络失败都会造成相似症状）。样本必须写 `signal_strength = direct | state | suspected` 三档——只有 `onRenderProcessGone()` 能把 renderer 退出写成直接事件，超时推断不能冒充 crash。

**Q26: 监控 JSBridge 时为什么应优先用 addWebMessageListener？addJavascriptInterface 在 Android 13 上有哪些必须写进设计的边界？**

AndroidX WebKit 的 `WebViewCompat.addWebMessageListener()` 按 `allowedOriginRules` 注入对象，回调携带 `sourceOrigin` 与 `isMainFrame`，来源可控可校验，是首选；`addJavascriptInterface()` 无法识别调用 frame 的来源，只应用于 App 完全控制的文档。

AAOS13 源码核对（`frameworks/base/core/java/android/webkit/WebView.java`）的 `addJavascriptInterface()` 边界：注入对象进入页面的所有 frame 包括 iframe；JavaScript 调 Java 是同步的，JavaScript 会等方法返回；被注解的 Java 方法运行在 WebView 的私有后台线程而不是主线程；`removeJavascriptInterface()` 的变化要到下一次页面 reload 才生效；`WebView.getUrl()` 也回答不了调用来自哪个 frame。

监控与安全做法：使用 Web message listener 时 allowlist 写完整 scheme、host 和必要 port、不用 `*`，回调里重复校验 main-frame 与精确 origin（降低单点配置错误风险），限制载荷大小，JSON 解析与落盘移到有容量上限的 worker 队列并记录 queue wait、payload bytes 与 drop count；监控调用拆成 direction、method_key、各阶段耗时与 queue depth。安全边界必须写清：origin 校验挡不住受信任站点自身的 XSS 调用 Bridge，敏感 Native 操作仍要验证消息 schema、业务状态与权限——"来自允许域名"不是完整授权。`evaluateJavascript()` 必须在 UI 线程调用，它虽是异步 API，调用前的排队、renderer 执行与回调派发仍可能很慢。

**Q27: 为什么 Android 侧的 JankStats 和主线程监控看不到 Flutter 页面的卡顿根因？Flutter 版本升级对 APM 的线程模型有什么影响？**

纯 Flutter 内容的 build、layer tree 与 raster 都在 engine 内部完成，JankStats、FrameMetrics 和 Android 主线程消息只反映宿主侧帧与线程症状，区分不了 Dart build 慢、raster 慢还是 pipeline latency（从帧开始到最终完成）过高。逐帧诊断要用 engine 的 `FrameTiming`：`buildDuration`、`rasterDuration` 与 `totalSpan`（从 vsync start 到 raster finish）三个维度分别对应三类原因；流水并行下 build 和 raster 可能各自未超预算但 totalSpan 已超一个刷新周期，判断触控延迟要看 totalSpan。固定阈值 16 ms 只适合约 60 Hz，可变刷新率设备要记录当时的 display mode。

版本决定线程归属：Flutter 3.29 起 Android 主线默认合并 UI 与 Platform 线程（早期版本可用 manifest key 退出），3.38 起发现旧 opt-out key 直接抛异常、3.44 起该 key 被移除。合并后 Dart 的 build/layout/paint 与 Android Platform、插件回调共享宿主主线程——耗时 MethodChannel handler 会挤压 Dart UI 工作，长 Dart build 也会推迟 platform callback；这些因果关系只在合并模型下成立，旧版或定制 engine 中两类任务可能在不同 OS 线程。APK 携带的 engine revision 比 Android API level 更有判别力，样本要记录 framework 版本、engine revision 与线程模型。

两个附带边界：Flutter 通过 VsyncWaiter 使用平台 vsync 信号（3.47.0 走 NDK Choreographer、回退 Java `Choreographer.postFrameCallback()`），"共用 vsync"不等于走完整 Android View 绘制阶段；异常侧至少区分 framework-caught error（可能被 ErrorWidget 或业务恢复，不等于 crash）、root isolate 未捕获错误（保留 handler 的 handled 返回语义）与 engine/plugin 的 Native crash（进 Native crash 管道），不能都记成 Dart exception。

**Q28: 千万级 DAU 的 APM 端侧 SDK 必须守住哪三条硬约束？为什么说"千万级"改变的是发布风险而不是队列大小？**

"千万级 DAU"不会让单台手机的队列自动变大，它改变的是发布风险：一个只在 0.1% 设备出现的采集器回归也覆盖大量用户；没有随机错峰的重试会在同一时刻形成请求峰值；目标范围过宽的远程指令会迅速消耗系统预算和用户流量。端侧要同时控制单进程开销、跨进程一致性和一次配置或版本发布能影响的设备范围。

全链条共享三条硬约束：业务线程不等待编码、文件、压缩、加密和网络；所有内存、文件、重试和重样本都有上限；高优先级故障证据有独立容量、不能被普通指标淹没。"业务线程不等待"不等于每次调用都有严格常数耗时——对象分配、CAS 竞争、缺页都可能造成少量高延迟调用，所以要在低端机实测入口 P50/P95/P99.9 并给主线程路径设自动降级。

执行机制随约束走：SDK 按采集、准入缓冲、编码、本地 spool、上传调度、指令控制、自我保护分层，各层限制各自的风险；断路器在某类操作持续超预算或失败时拒绝对应低优先级工作、冷却后半开探测。内存自保不能只靠 low-memory callback——Android 14 起不再向 App 发送 `TRIM_MEMORY_RUNNING_MODERATE/LOW/CRITICAL`（AAOS13 的 `ComponentCallbacks2` 仍有这些常量，属版本差异），要以自己的字节预算、heap headroom 和固定窗口内队列峰值为主。自监控数字要写清分子分母："SDK CPU 占比 1%"必须说明分母是进程 CPU 还是全部 SDK 线程，否则两个 1% 不可比。

**Q29: 用 Channel.trySend() 做 APM 事件入口时"立即返回"和"无锁"是什么关系？为什么普通 FIFO 队列保护不了崩溃证据？**

`trySend()` 是非挂起发送：尝试立即放入、容量满或 Channel 已关闭时直接失败返回，不等待也不抛异常，适合 APM 事件入口；默认 `SUSPEND` 策略下的普通 `send()` 在有界 buffer 满时会等待，业务线程绝不能用。但"立即返回"不等于 lock-free——lock-free 要求系统整体总有线程能前进、wait-free 还要求每个操作有限步完成，Channel 文档没有承诺这两种进度保证或固定耗时上限；自研 MPSC RingBuffer 要用每个 slot 的 sequence 证明发布顺序并处理 ABA 问题，通过并发测试、soak test 和线性化检查，不能把循环数组直接命名为无锁队列。

入口配套语义：`trySend()` 失败时元素从未进入 Channel，官方契约不会触发 `onUndeliveredElement`，失败分支要自行计数，且"容量已满"与"Channel 已关闭"要分开统计（后者说明 SDK 生命周期或 worker 出错）；失败计数器预先创建、用固定枚举索引，不能在 hot path 拼 key 或建 Map entry。`capacity` 只限制 buffer 中持有的元素数，事件对象可能引用大字符串，队列节点、编码 buffer、压缩工作区都要纳入内存预算。

优先级准入保护崩溃证据：FIFO 满载时普通帧样本会占满所有槽位、随后到达的 crash breadcrumb 无处可放。准入分三级：P0 故障证据（crash/ANR 摘要、renderer gone、存储损坏）用独立保留槽或直接写预分配故障区，P0 也不无限增长——用很小的独立 ring、覆盖最旧记录并记录 dropped/overwritten 计数；P1 异常样本丢弃同类旧样本或限频保留；P2 普通样本按采样率丢弃。

**Q30: APM 本地 spool 为什么要求单写者和"只上传 sealed 文件"？多进程 App 与进程被杀分别怎么处理？**

多个线程或进程并发写同一活动文件会让文件尾部随时处于半条记录状态，恢复器无法判定边界。单写者方案由一个 spool worker 串行执行编码、分片、游标提交和文件轮转，把一致性简化成状态机：`IN_MEMORY → ACTIVE → SEALED → UPLOADING → ACKED → DELETED`（另有 `EXPIRED`/`CORRUPT` 出口）——active 分片仍在写入，sealed 分片已提交并停止修改，上传 worker 只读 sealed 文件。

进程被杀的处理由此推出：App 被杀后下次启动修复 `ACTIVE`、继续上传 `SEALED`、把中断的 `UPLOADING` 恢复成可重试；crash handler 不能负责"清空整个队列"——Java handler 里锁与堆可能已损坏、native signal handler 只有 async-signal-safe 集合、LMK/force-stop/掉电没有清理回调，所以 crash 前的 breadcrumb 要持续写入预分配区域，handler 只追加最小头或设置 crash marker 后交还默认终止链。

多进程隔离：每个进程启动时生成新的 `process_instance_id`（PID 重启后会复用，不能单独当持久标识），使用自己的 active queue 与 spool 目录（`files/apm/spool/<process-name-hash>/<process-instance-id>/`）；上传协调者固定在主进程或专用诊断进程，扫描其他进程的 sealed 文件，其他进程只负责 seal 并发轻量 IPC 通知（通知丢失由下次扫描补上）；多进程提交 WorkManager 用 `RemoteWorkManager`。上传侧用 unique work 加幂等 `batch_id`（服务端对同一批次即使重复提交也只确认一次），重试按响应区分——429/503 尊重 `Retry-After`、schema 类 4xx 隔离不再重试；错峰用每设备在允许窗口内选择、配置周期内保持稳定的随机偏移，避免"每 15 分钟整点"让大量客户端同时请求。

**Q31: 用 mmap 写 APM 日志为什么不能想当然地获得"crash-safe"？force()、SIGBUS 和文件格式各自解决什么问题？**

mmap 只是一种缓冲与访问方式：它把文件页面映射进进程地址空间，已驻留的热页可减少逐条 `write()` syscall，但首次访问仍可能触发缺页，修改后的脏页仍由内核经 file cache 与 writeback 回写存储；没有显式提交点时，进程 crash 后内核仍存活并不构成 Java API 的持久性契约。需要承诺的记录必须有显式提交点。

`MappedByteBuffer.force()` 提供明确的 durability boundary——调用成功前修改已提交到本地设备（AAOS13 libcore 已核对该方法存在）；它可能执行同步 I/O，只能在 spool worker 上按批调用，且 `FileChannel.force()` 不替代 mapped buffer 自己的 `force()`。`ftruncate()` 扩展文件长度时文件系统可能只创建 sparse region，后续写 `MAP_SHARED` 页面若空间耗尽，进程可能收到 `SIGBUS`——Mars 与 Logan 的实现都先写零或填充预分配、映射失败退回普通 buffer；生产实现要在 worker 上检查可用空间、完整预分配小而固定的 active 文件，mapping 存续期间禁止其他线程或进程 truncate、替换底层文件，并对 ENOSPC、只读文件系统、checksum 失败分别计数。

恢复能力来自文件格式而不是 mmap API：A/B 双 superblock（magic、format_version、generation、committed_offset、checksum）加 record（length、sequence、payload、checksum），单写者先写完整 record 与 checksum、再更新下一代 superblock；启动扫描只接受长度合法、checksum 正确的 record，遇到尾部半条记录停在前一个有效 offset。4 字节游标更新不是跨 crash 的事务——文件系统写入粒度与回写顺序都可能影响结果，恢复器要能接受"数据写了但游标没写""游标较新但尾部校验失败"等组合。纯 Java 还要注意 mapping 生命周期：关闭创建 mapping 的 `FileChannel` 不会让 `MappedByteBuffer` 立即失效，公开 API 也没有可依赖的同步 unmap——可控方案是长期复用一块很小的 active journal，把已提交 block 复制成 sealed 文件后再复用。

**Q32: 远程下发"重取证"指令（trace、heap dump）时，普通发布 App 的能力上限在哪里？指令从接收到执行要过哪些校验？**

远程指令不增加 App 权限，也不能保证命令到达时设备满足内存、磁盘和网络预算——它只能把少量目标设备从低成本指标切换到平台本就允许的能力：本进程 `android.os.Trace` marker（只有 trace session 正在采集时才形成材料）、Perfetto SDK in-process（只含 App 自定义 data source，不是带 sched/ftrace 的 system trace）、`ProfilingManager` 的 app-driven 与 system-triggered 采集（API 35 起；AAOS13 源码中没有这套系统代理采集面——Android 13 上这条路径不存在，属于版本差异）、`Debug.dumpHprofData()` 对自身进程（可能触发 GC 且包含敏感数据，不适合静默大范围开启）。读全设备 logcat、其他进程 Hprof、system Perfetto 都在普通 App 能力之外。

指令从接收到执行要过完整校验链：应用层数字签名验签（TLS 只保护传输，配置经 CDN、代理或存储后的完整性由端侧内置公钥验证，支持 key rotation）；schema 校验；generation 防回滚（拒绝 generation 倒退）；TTL——wall clock 可被用户修改，进程内用单调时钟计算剩余 TTL，跨重启以签名的 `expires_at` 加保守最大存活期双重限制；cohort 与 consent 校验；capability 检查；单设备触发次数与 CPU、内存、文件、上传字节预算全部通过后才持久化为 `VERIFIED`。服务端 kill switch 依赖网络，不能替代端侧写死、远端无权放宽的 hard limit；未知字段可能改变安全含义时 fail-closed 拒绝执行。

执行边界：进入 `RUNNING` 前持久化触发次数与预算预留，防止进程在写计数前被杀而反复执行；crash loop（启动后很快崩溃并反复重启）中不能每次启动都执行重指令。"特定 UserID"不应把原始账号写进配置与 APM 文件——用短期、App scoped 的 opaque cohort token 或支持流程中的一次性 case ID。Java heap dump 可能包含 token、会话与密钥材料，只允许有明确数据用途、可审计授权和严格人群范围的方案；能由端侧类计数或摘要回答的问题，不上传原始 heap。
