# Telephony 与 Connectivity

> 学习资料（文章模式沉淀）。主线：Telephony 的跨进程请求与状态传播模型，Connectivity 的网络注册、验证、排序与回调分发。源文档：android-internals-wiki §1.21《Telephony 服务架构、状态传播与回调》、§1.22《Connectivity 服务、网络选择与回调》；机制按本地 AAOS13 源码（Android 13）核对，与材料 Android 17 语境的差异已标注：RIL 分域 AIDL 与 HIDL 回退、TelephonyRegistry 线性扇出、DataNetwork 状态机在 13 已有，监听数量上限等新约束属 17 语境；Connectivity 属 Mainline 模块、本地 AAOS13 树未含模块源码，相关结论按材料 Android 17 锚点转写；NetworkCallback 回调顺序与默认回调 onLost 语义已与官方文档核对。Q 序列即结构，供 atlas 同源直读。

**Q1: TelephonyManager 这类"同步"查询 API 背后跨了哪些进程？RemoteException 能说明 Modem 坏了吗？**

TelephonyManager 是应用侧客户端入口，不保存完整蜂窝状态：查询与控制经 ITelephony Binder 进入 com.android.phone 进程的 PhoneInterfaceManager，回调注册则走 ITelephonyRegistry 到 system_server 的 TelephonyRegistry；短信（ISms 到 SmsController/IccSmsInterfaceManager）与订阅（ISub 到 SubscriptionManagerService）各有独立 Binder 服务，呼叫界面与 PhoneAccount 由 Telecom 协调——不能画成一条 TelephonyManager → RIL → Modem 的直线。RemoteException 通常表示 Phone 进程正在重启或 Binder 已死亡，与 Modem 无关；Modem 故障表现为 RADIO_NOT_AVAILABLE 之类的 radio 错误。诊断时先定位调用属于哪个 Binder 服务、状态在哪个进程停止传播，再谈"Telephony 慢"。

**Q2: RIL 发出一条 radio 请求后怎么把响应对回原调用？Android 13 连 Radio HAL 用 AIDL 还是 HIDL？**

框架创建 RILRequest 分配 serial，放入 mRequestList 并持有 wakelock，然后调用分域 AIDL IRadio* 的 oneway 方法；Vendor HAL/Modem 异步处理后经对应的 IRadio*Response 携带同一 serial 返回，RIL 用 serial 找回原请求完成 Message——异步配对靠 serial 而不是线程栈。AAOS13 源码核对：RIL 按 data、messaging、modem、network、sim、voice、ims 域维护 RadioServiceProxy 并优先获取分域 AIDL 服务（android.hardware.radio.data.IRadioData 等），AIDL 不可用时依次尝试 HIDL IRadio 1.6 及更低版本，所以"已全面迁移、不再有 HIDL"不成立（分域 AIDL 自 Android 13 起）。Modem 主动上报走 IRadio*Indication，没有等待中的请求与之一一对应；wakelock timeout 不等于取消请求，迟到的响应仍会按 mRequestList 处理。Vendor HAL 到 Modem 的传输由厂商实现决定，不能假设一定有 AT 命令或名为 rild 的进程。

**Q3: 同样是 Telephony 查询 API，为什么 getAllCellInfo 几乎瞬时、requestCellInfoUpdate 慢且限频、部分特权 API 还可能长时间阻塞？**

三种服务端实现不同。其一，读缓存：targetSdk Q 及以上的 getAllCellInfo 直接返回缓存（AAOS13 源码核对 getCachedCellInfo 分支），新鲜度要看 CellInfo.getTimestampMillis（elapsed realtime 基准、不受校时影响，应与同基准当前值比较），反复调用不会触发更频繁的 radio 扫描。其二，异步刷新：requestCellInfoUpdate 经 ICellInfoCallback 回传，系统限制请求频率且不保证每次都有新数据，应用要处理 ERROR_TIMEOUT、Modem 错误、空列表与旧时间戳。其三，Phone 主 Looper 同步桥接：部分特权 API 进入 PhoneInterfaceManager.sendRequest，Binder 线程把命令投给主 Looper 后 wait 结果，队列长时等待不可控，且明确禁止从主 Looper 自身调用以免线程等自己处理消息而死锁；这里没有覆盖全部 API 的"默认 5–10 秒超时"，Binder 也没有通用事务超时。通用做法：无法确认服务端是否阻塞的调用不放进主线程关键路径，能带 callback 的用 callback，不轮询同步 getter。

**Q4: 一次信号强度变化经 TelephonyRegistry 怎样扇出到应用？同一 callback 反复注册会怎样？**

链路是 Modem → Vendor HAL → IRadioNetworkIndication.currentSignalStrength → RIL registrant → SignalStrengthController（比较 SignalStrength 对象与 subId，变化才继续）→ Phone.notifySignalStrength → DefaultPhoneNotifier → TelephonyRegistry → 遍历 mRecords → oneway IPhoneStateListener.onSignalStrengthsChanged → 应用 Binder Stub → 注册时指定的 Executor。TelephonyRegistry 在 synchronized(mRecords) 内按事件、subId、phoneId 与权限筛选记录（AAOS13 源码核对，ArrayList 线性扫描，成本 O(N)），每条匹配记录收到一份 SignalStrength 新副本；notifyNow=true 的注册会立即回调已有缓存，一次注册可能触发多个初始回调，成本不只是"追加一条记录"。应用侧 callback 不在 system_server 执行，但 Executor 太慢会在应用内积压回调、读到过期状态；framework 只保存 callback 的弱引用，应用须持有强引用并成对注册注销。版本边界：按 PID 限制监听数量、超限抛 IllegalStateException 属 Android 17 语境，13 源码未见此限制，但线性扇出的成本结论在 13 同样成立；未注销重复注册在 13 上旧 stub 可能暂留、初始回调再次触发，应用不应依赖该行为。

**Q5: "蜂窝有信号但应用无网"时，Telephony 与 Connectivity 各管哪一段？DATA_CONNECTED 能说明能上互联网吗？**

不能。DATA_* 状态只是框架对内部 DataNetwork 的概括：DataNetwork 继承 StateMachine（Connecting/Connected/Handover/Disconnecting/Disconnected，AAOS13 源码核对），进入 Connecting 时创建并注册 TelephonyNetworkAgent，把 NetworkCapabilities、LinkProperties 和 score 交给 Connectivity，连接建立后再 markConnected；Connectivity 负责跨 transport 的网络选择与 validation，验证结果经 onValidationStatus 反向通知 Telephony。排查按序确认：ServiceState 已注册到网络 → DataNetwork 处于 connected → NetworkAgent 已向 Connectivity 注册 → NetworkCapabilities 含目标能力 → Connectivity 的 validation、默认网络选择、DNS 与路由完成——任何一段断开都表现成"有信号无网"。默认数据 subId 变化会触发整条链重建，但具体是否走完整的 DISCONNECTED → CONNECTING → CONNECTED 取决于 Modem 并发能力、网络请求与 handover 方式，不是固定序列。

**Q6: 多卡代码里的 physical slot、phoneId、eSIM port、subscriptionId 是一回事吗？DSDS 设备"另一张卡一定周期性掉信号"吗？**

不是一回事，四层不可互换：physical slot index 是设备上的物理卡槽；logical slot（phoneId）是 framework 当前管理的逻辑 phone 实例，随多 SIM 配置变化；eSIM port index 是 eUICC 上可启用 profile 的逻辑端口；subscriptionId 是 subscription 记录的 framework ID，SIM 更换、eSIM profile 切换或记录重建都可能改变，不应持久化当作永久身份（公开 API 返回 SubscriptionInfo，Phone 进程内部由 SubscriptionManagerService 与 SubscriptionDatabaseManager 管理）。DSDS 与 DSDA 描述的是 radio 并发能力档位：DSDS 允许多卡待机，但并发通话与射频资源仍受 Modem 能力和运营商配置约束；DSDA 并发更强，但"通话时另一卡能否保持数据"仍要读设备公开能力——Android 13 提供 PhoneCapability 与 active modem count，应读取这些能力而不是按卡槽数量或名字推断。subId、phoneId 与 slot 的映射在热插拔和 eSIM 切换后会更新，异步任务执行前要重新校验。

**Q7: Connectivity 属于 Mainline 模块，ConnectivityService 跑在哪个进程？网络验证卡住时是 system_server 在发 HTTP 探测吗？**

模块归属与进程归属是两回事：Connectivity 代码可随 Mainline 模块独立更新，但 ConnectivityService 仍运行在 system_server；NetworkMonitor 属于 NetworkStack 模块，运行在独立的 com.android.networkstack.process 进程，ConnectivityService 经稳定 AIDL INetworkStackConnector 为每个网络创建监视器——所以验证探测卡住不能推断 system_server 的线程在执行 HTTP 请求。netd 是另一个边界：原生守护进程执行网络创建、路由、UID 权限与防火墙等内核配置。版本说明：Connectivity 模块化与上述进程分工在 Android 13 已成立，但本地 AAOS13 树未包含 packages/modules/Connectivity 源码，模块内部细节按材料 Android 17 锚点转写。排障时"代码归谁更新"和"代码跑在哪、故障影响哪个进程"要分开回答。

**Q8: Wi-Fi 已连接且 NetworkCapabilities 里有 NET_CAPABILITY_INTERNET，为什么应用还是上不了网？一个网络能同时 available 又 blocked 吗？**

能。INTERNET 是网络提供者对"设计上可访问互联网"的声明，VALIDATED 是 NetworkMonitor 探测后系统给出的结果：已关联却无法出网的 Wi-Fi 可以带着 INTERNET 而没有 VALIDATED（官方文档核对两能力分别描述 setup 与实际连通）；连接建立之前又不可能要求 VALIDATED，因为它在连接后才产生且会变化。注册与可用也是多层状态：agent 注册只让服务认识这个网络，native network 创建（netId 与路由规则）后才有内核数据通路，markConnected 后才能满足请求并出现在公开查询中。blocked 是正交的另一个维度：Data Saver、Doze、应用待机、lockdown VPN 等策略限制某 UID 在该网络上的访问，onAvailable 表示网络满足请求、onBlockedStatusChanged 表示该 UID 能否在上面正常发包——同一网络可以已经 available 同时对该 UID blocked；默认网络回调的 onLost 也只表示失去默认网络状态，不等于物理断开（官方文档核对）。

**Q9: NetworkMonitor 验证失败会宣布 Wi-Fi 断开吗？PARTIAL_CONNECTIVITY 是普通应用能用的常量吗？**

不会。NetworkMonitor 是事件驱动的状态机（评估、探测、门户、Private DNS、已验证等状态），验证对象是互联网质量与门户状态，不是物理链路：探测失败按逐渐延长的退避策略重试，失败不产生"链路断开"的结论，物理断开由提供者与 Connectivity 管理，NetworkMonitor 中没有对应断网的 LOST 状态；探测 URL、并行策略与超时受资源配置、DeviceConfig 和模块版本影响，不能写成"验证固定 30 秒"。结果语义：VALIDATED 表示验证成功（部分不要求互联网验证的网络按规则跳过）；CAPTIVE_PORTAL 表示需先登录或确认；PARTIAL_CONNECTIVITY 表示组合探测得到有限连通。后者在 Android 17 仍是隐藏 SystemApi，普通 SDK 应用不能引用；稳妥做法是用 INTERNET + VALIDATED 判断通用可用性、可公开检查 CAPTIVE_PORTAL、其余未验证状态依赖业务请求结果并保留连接与读写超时——系统验证通过也不等于你的服务端健康。

**Q10: "Wi-Fi 60 分 + validated 40 分 = 100 分，比蜂窝 90 分高所以选 Wi-Fi"——这个打分模型对吗？系统实际怎么排序？**

不对。Android 17 源码明确 legacy int 只供测量与日志、不再参与网络排名（材料按 android-17.0.0_r1 核对；本模块源码不在本地 AAOS13 树，按材料锚点转写）。实际选择由 FullScore 的布尔策略位加 NetworkRanker 的有序筛选完成：先排除不满足 capabilities、transport、specifier、UID 要求的网络，再按 invincible offer、已连接 VPN、用户选择并接受未验证、已验证或被用户接受（含让位低质量 Wi-Fi 的兼容策略）、非 EXITING、同 transport 的 primary、Ethernet/Wi-Fi/Bluetooth/Cellular 的 transport 顺序、等价蜂窝中的 VCN、非等待替代状态逐级缩小候选，全部等价时保持当前 satisfier 以减少无意义切换。VPN 的 legacy int 101 只是历史兼容值，排序靠 TRANSPORT_VPN 策略位而非"继承底层分数"；非计费网络也不会直接获得数值加分。业务代码不应复制这套顺序：运营商配置、VPN 与后续版本都会改变结果，且 requestNetwork 之外的打分无公开 API。

**Q11: registerDefaultNetworkCallback、registerNetworkCallback、requestNetwork 三者怎么选？为什么不能在 onAvailable 里同步调 getNetworkCapabilities()？**

按需求选：观察本应用默认网络用 registerDefaultNetworkCallback；被动观察所有匹配网络、不要求系统为此建网用 registerNetworkCallback；只需单个最佳匹配用 registerBestMatchingNetworkCallback（API 31+）；确实需要系统建立或保持额外网络的短期任务才用 requestNetwork——它可能促使扫描或数据网络保持活动，不能当轮询工具，用完必须 unregisterNetworkCallback。回调顺序自 Android 8.0 起有保证：onAvailable 之后按 onCapabilitiesChanged、onLinkPropertiesChanged、onBlockedStatusChanged 到达（官方文档核对）；回调先跨进程进入应用，再由框架串行分发，默认跑在应用专属的 Connectivity 线程、传入 Handler 则跑对应 Looper。在 onAvailable 里同步查询可能拿到与当前回调时序错位的新结果，应使用随后回调携带的对象。约束：注册与注销严格配对、复用进程级 tracker；版本边界：每 UID 未注销请求与回调 100 个上限属 Android 17 材料口径，13 上同样不应无上限注册。

**Q12: Wi-Fi 切到蜂窝后默认网络回调已收到新网络，为什么已有 TCP 连接还是断了？应用该怎么处理？**

默认网络切换只影响"之后"的新 socket 与新域名解析，ConnectivityService 不会迁移已建立的 TCP/QUIC 会话：旧连接仍关联原 netId 与接口，旧网络被拆除或策略关闭 socket 时要按协议语义重连。绑定手段：新 socket 用 Network.bindSocket 或 SocketFactory，单次解析用 Network.getAllByName，bindProcessToNetwork 影响该进程后续全部 socket 与 DNS、作用过大应慎用；VPN 或声明了 underlying networks 的库要在承载网络变化时同步更新。恢复处理先分类：幂等请求可安全重试，写操作要带业务 request ID 或服务端幂等键，WebSocket、HTTP/2、QUIC 的迁移与重连交给网络库。旧网络可能进入 linger 为收尾留时间（材料按 Android 17 记载默认 30 秒、可由系统配置调整，非 SDK 承诺），onLosing 只是提示且可能不出现或几乎与 onLost 同时到达——不能把收到 onAvailable(newNetwork) 当成"业务连接已恢复"而直接重发全部请求。
