# Android 网络框架

> 学习资料（文章模式沉淀）。主线：Connectivity 层的"已连接"语义、默认网络评分与切换、多网络绑定、DNS 解析链与 Private DNS、策略路由、车机以太网、dumpsys 排查。VPN 见 [03-Android-VPN.md](./03-Android-VPN.md)；车机多 APN 见 [02-车机多APN与虚拟网卡.md](./02-车机多APN与虚拟网卡.md)。机制按社区分析与通用 Android/Linux 知识沉淀（2026-09 检索），本地 AAOS13 树含 VPN 与 Telephony data 层源码（已核对部分随题标注），ConnectivityService/netd 不在本地树、未做源码级核对。Q 序列即结构，供 atlas 同源直读。

**Q1: ConnectivityManager 报告"已连接"意味着什么？为什么 isConnected() 被废弃？**

"已连接"是分层语义：物理链路注册（NetworkAgent 上线）→ 能力声明（NET_CAPABILITY_INTERNET 等）→ **验证通过**（NET_CAPABILITY_VALIDATED，系统对生成 URL 探测成功）→ 成为默认网络。`isConnected()` 被废弃是因为它只回答"链路在"，不回答"能上外网"——认证门户、弱网、无出口的 Wi-Fi 都可能是"已连接但未验证"。应用判断应以 `NetworkCallback.onCapabilitiesChanged()` 里的 VALIDATED 位为准，并区分"链路存在"与"可用"两层。

**Q2: 默认网络怎么选出来？为什么连上新 Wi-Fi 会"自己切回去"？**

默认网络由评分驱动：各网络按 transport 类型有基础优先级（以太网/Wi-Fi 高于蜂窝），再叠加验证状态等加权；系统持续评估，把默认网络切给当前最高分。典型坑是切换时序：新 Wi-Fi 刚连上还没通过验证时分数低于旧蜂窝，系统评估后主动切回——这不是 bug（CSDN 配网实战案例口径）。排查切换问题分三步：确认目标网络能力（是否拿到 VALIDATED）→ 观察 NetworkCallback 的 available/capabilities/losing 序列 → 确认应用绑定关系（绑定了 Network 的组件不跟随默认网络切换）。

**Q3: 应用怎么把流量固定到某个网络？有哪些"绑不住"的例外？**

两层绑定：进程级用 `ConnectivityManager.bindProcessToNetwork(network)`，套接字级用 `network.bindSocket(socket)` 或 `socketFactory.createSocket()`——绑定后这些流量走指定网络，不受默认网络切换影响。绑不住的例外（社区经验归纳）：未继承绑定的子进程与 WebView 网络栈仍走默认网络；`bindProcessToNetwork` 与直接 `Network.openConnection` 混用时容易部分流量走错；系统组件（下载管理器等）按自己的网络策略走。验证绑定是否生效用 `dumpsys connectivity` 看默认网络与 per-uid 绑定，配合抓包确认实际出口。

**Q4: Android 的 DNS 解析走哪条链路？Private DNS 是什么？**

应用解析经框架 resolver 进入 netd，netd 按"查询目标网络"选择该网络配置的 DNS 服务器并从对应网卡发出——所以解析结果与发出接口都跟随绑定网络，跨网络解析不会自动发生。Private DNS（Android 9 起）把解析升级为 DNS-over-TLS：设置里的主机名以加密方式查询，失败时按模式（严格/机遇）决定回退。排查 DNS 问题先固定三件事：解析请求实际发往哪台服务器（抓包 UDP/TCP 53 与 853）、从哪个接口发出、返回的是公网还是私网地址——车机多 APN 场景这三件事最容易出错（见 02 册）。

**Q5: Android 的策略路由怎么组织？"一张网卡一个路由表"对应用意味着什么？**

Android 为每个网络维护独立路由表并用 `ip rule` 规则按 fwmark 把流量导到对应表（netd 执行，通用 Android/Linux 机制，官方与社区分析口径）：应用绑定 Network 后其 socket 被打上该网络的 mark，查对应表，从该网卡发出；未绑定的流量查默认表的默认网络。含义：`ip route` 直接看的"主表"不等于应用实际路由，分析车机多网卡路由必须按表+规则一起看；这也是 VPN、多 APN、双网口能共存的基础——各自治各自的路由表。

**Q6: 车机以太网在 Android 里是什么形态？与 Wi-Fi/蜂窝在框架里同权吗？**

以太网是 Connectivity 层的一等公民网络：以太网服务把它包装成带 `TRANSPORT_ETHERNET` 的 NetworkAgent 参与评分，车机常见"以太网优先于蜂窝"就是靠评分与能力配置实现（社区分析口径：Android 13 起以太网框架模块化重构并引入 Metric 评分体系，本地树无该模块源码）。对应用透明——它就是一个普通 Network，可用 NetworkRequest 按 TRANSPORT 请求。车载差异化在配置侧：静态 IP/DHCP、VLAN 与多口策略由 OEM 的以太网服务配置提供。

**Q7: 网络切换瞬间，旧网络上的套接字会怎样？应用该怎么写重连？**

默认网络切换不会通知应用关闭旧 socket：旧连接发往已失去路由的网络会静默失败（写超时/连接重置），应用侧表现为"请求卡住很久才报错"。健壮写法：监听 `onLost()` 主动关闭旧网络上的连接池；请求层设短连接超时与快速失败；关键长连接用系统 Keepalive（`SocketKeepalive`， Cellular/Wi-Fi 均支持）或应用层心跳；重连目标用 `getAllNetworks()`/NetworkRequest 重新评估而不是死等默认网络。

**Q8: dumpsys connectivity 排查网络问题先看什么？**

三段式读法（社区经验归纳）：先看默认网络与各网络清单（netId、transport、capabilities、验证状态），确认"系统认为谁可用"；再看 per-uid/per-pid 绑定与 NetworkRequest 登记，确认"应用实际挂在谁上"；最后对齐 netd 侧路由与 DNS 配置。配合 `dumpsys telephony.registry`（蜂窝数据状态）与抓包定位"框架认为通、实际不通"的分裂——这类分裂在车机多 APN 环境是高发问题。

**Q9: VALIDATED 探测是怎么做的？私网-only 环境永远"未验证"，应用该怎么判断可用性？**

验证由 NetworkStack 对固定探测地址发起 HTTP 请求，期望 204/重定向响应：成功打上 `NET_CAPABILITY_VALIDATED`，命中登录页判定为 captive portal（认证门户）并提示用户认证。推论（车机场景高发）：只接私网 APN、无法到达公网探测地址的环境**永远拿不到 VALIDATED**，但业务本身完全可用——应用绝不能拿 VALIDATED 当业务可用性判据，正确做法是按业务目标做自己的探活（对 TSP 端点发心跳），框架验证位只用于"公网可达性"语义。反过来，认证门户场景下系统会自动弹出登录引导，应用监听 `onCapabilitiesChanged` 里 VALIDATED 的翻转即可感知认证完成。

**Q10: 一条底层链路要怎么才能变成"框架可见的网络"？NetworkAgent 扮演什么角色？**

框架网络由 NetworkAgent 注册产生：链路提供方（Wi-Fi 服务、蜂窝 data 栈、以太网服务、VPN）创建 NetworkAgent，声明 capabilities、评分与底层 LinkProperties（地址/路由/DNS），系统据此把链路纳入评分与分发体系，并回调两侧的连通性验证结果（机制为官方与社区分析口径，ConnectivityService 不在本地树）。它解释了 02 册的两套绑定机制：Wi-Fi/蜂窝/以太网有 NetworkAgent 所以能用 `bindProcessToNetwork`，而裸 veth 没有——要让 veth 成为"框架网络"，正确路径是写一个 NetworkAgent 注册它（OEM 常这么做来统一管理车机以太网口），而不是让应用各自写套接字选项。

**Q11: 应用发出 NetworkRequest 之后，"网络"是怎么被拉起来的？**

请求并不直接驱动硬件：NetworkRequest 进入 ConnectivityService 后按 capabilities 匹配已注册的网络；若无人满足，系统把需求通知给对应的 NetworkFactory（Wi-Fi、蜂窝、以太网各持一个工厂），由工厂评估后创建 NetworkAgent 拉起链路（机制为官方与社区分析口径，源码不在本地树）。两个推论：蜂窝的 PDN 激活是"有请求才拉、无请求可放"的按需模型——车机私网 APN 没人请求就可能不激活，表现为"没流量时私网网卡消失"；factory 的评估逻辑在 OEM/模块实现里，"请求没回调"要先看有没有 factory 认领这类 capability，再查网络本身。

**Q12: netd 在网络管理面还管什么？带宽控制怎么实现？**

netd 是框架到内核的网络命令通道与守护：路由/规则下发、防火墙与带宽控制、网络共享的转发规则都经它落内核。带宽面按 per-uid 管理——黑白名单（限哪些应用可用计费网络）、配额触发后的整网限制、以及针对后台的 idle 归类（机制为官方与社区分析口径，netd 不在本地树；流量统计的 eBPF 见 05 册）。排查"某应用被限速/断网"的顺序：Connectivity 侧的 NetworkPolicy 状态（04 册 Q 分层）→ netd 的带宽规则 → 应用自身配置；三者中第二层在 `dumpsys netd` 类输出里核对，量产 user 版可能受限，要以可观测的替代（抓包看是否被丢）兜底。

**Q13: 按流量计费的网络，应用该怎么适配？"连着 Wi-Fi"为什么不可信？**

判定用 `ConnectivityManager.isActiveNetworkMetered()` 与 capabilities 里的计费语义（官方 API 口径）：蜂窝默认计费；Wi-Fi 默认不计费，但**热点下游会继承上游的计费属性**——车机连了计费蜂窝开热点、或桥到计费上游时，"连着 Wi-Fi"同样按流量花钱。适配规则：大文件下载、自动更新、预加载只在非计费网络做（配合 04 册 Q14 的下载策略）；计费网络下推迟同步、压缩传输、降低预取深度；用户显式发起的动作不受限——被限的是"应用自作主张"。实现上优先用 NetworkRequest 的 `NET_CAPABILITY_NOT_METERED` 约束把任务路由到正确网络，而不是"先跑再检测计费后取消"。车机双卡场景（04 册 DDS 题）里计费属性还随默认数据卡切换而变，长任务要监听 capabilities 变化重估。

**Q14: Android 的系统代理体系怎么工作？为什么"设了代理抓不到包"？**

系统代理有两级：全局静态代理（每网络配置里的 host:port，Wi-Fi 详情页/以太网配置可设）与 PAC 自动代理（脚本按 URL 决定走哪个代理，由系统的 PacProxyService 托管执行——本地源码文件核对）。代理随**网络**走：换默认网络代理就换了，绑定到指定 Network 的流量用那张网络的代理配置。抓不到包的两大原因：走自定义 `Socket`/自有协议的应用不读系统代理（代理只在应用主动使用 `ProxySelector` 默认值时生效，OkHttp/HttpURLConnection 默认读，裸 socket 与很多 SDK 不读）；HTTPS 需要在代理上装信任证书否则只能看到 CONNECT 隧道。PAC 的坑：脚本执行失败或超时会退回直连，"代理时好时坏"先查 PAC 脚本可达性；TUN 型抓包（VPN 应用）比代理覆盖面完整，因为它在 IP 层截获（06 册取证口径）。

**Q15: 验证探测地址本身不可达会怎样？这个地址能改吗？**

能，而且是国内设备的常见定制点：AOSP 默认探测 `connectivitycheck.gstatic.com/generate_204`，该地址在国内不可达，后果是**所有网络都被标为"未验证"**（Q9 机制）——信号是"Wi-Fi 有感叹号/被评分为劣于蜂窝"，但网络实际可用。覆盖方式：Settings.Global 的 `captive_portal_http_url`/`captive_portal_https_url` 指向可达的 204 地址（社区 adb 实践口径，国内可用源如各厂商 generate_204 端点），国内 OEM ROM 出厂通常已预置自家探测源。车机交付注意：私有网/海外环境的整批车辆若出现成片"未验证"告警，先核对探测地址在目标网络内是否可达，再排查网络本身；探测地址属于系统级配置，应用不要基于 VALIDATED 位做业务判据（Q9 结论）。

**Q16: 网络相关的系统权限阶梯是什么？OEM 应用为什么"改不动"网络设置？**

网络权限按能力分四级阶梯（官方权限保护级别口径）：`INTERNET`/`ACCESS_NETWORK_STATE`（normal，发包与查状态，Q10）；`CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`（normal，能发起切换/开关的请求，但系统可拒绝）；`NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD`/`NETWORK_AIRPLANE_MODE`（signature 或 privileged 级，改网络配置、开关飞行模式这类系统行为）；`NETWORK_STACK`（signature，网络栈组件自身）。OEM 应用"改不动"的根因几乎都在阶梯位置：预装到 `priv-app` 且带平台签名才能拿第三级；普通应用反复申请也不授。开发路径相应明确：改系统网络行为的功能做成 priv-app 系统应用，或经系统提供的受控入口（Settings 面板、建议 API）间接实现。
