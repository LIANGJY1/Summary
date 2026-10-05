# Android VPN

> 学习资料（文章模式沉淀）。主线：VpnService 的 tun 数据面、Builder 路由与 DNS 语义、protect 防回环、分应用 VPN、always-on 与 lockdown、平台级 IKEv2 VPN、VPN 与多网卡/多 APN 共存、VPN 应用生命周期与故障。VpnService/Vpn/VpnManager/Ikev2VpnProfile 按本地 AAOS13 源码核对（frameworks/base），行为演进按官方文档与社区经验口径标注（2026-09 检索）。车机多 APN 场景见 [05-multi-apn-veth.md](./05-multi-apn-veth.md)。Q 版本说明：本文档为机制沉淀 V1.0（2026-09-28），项目侧 VPN 版本变更记录由项目文档维护。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] VpnService 的数据面原理是什么？为什么说一个系统同时只有一个活跃 VPN？**

`establish()` 返回的 `ParcelFileDescriptor` 是内核 tun 设备的 fd（VpnService.java 核对）：系统按 Builder 配置改写路由，把命中网段的 IP 包送进 tun，应用从 fd 读原始 IP 包、处理后写回——这是三层（IP 层）转发，没有 TCP/UDP 会话概念，做代理要在应用内自建用户态协议栈（V2EX 流量代理案例口径）。tun 由系统单实例管理：同一时刻只允许一个活跃 VpnService，新 VPN `establish()` 会撤销旧的（旧应用收到 onRevoke），与 Tailscale 类应用天然互斥（社区经验口径）。

**Q2: [learning] Builder 的 addAddress/addRoute/addDnsServer 各决定什么？"DNS 泄漏"是怎么发生的？**

Builder 方法语义（VpnService.java 564–795 行核对）：`addAddress()` 给 tun 配 tun 侧地址；`addRoute()` 决定哪些目的网段进 VPN——**没 addRoute 的网段根本不会进 tun**，`addRoute(0.0.0.0, 0)` 才是全局接管；`addDnsServer()` 设置 VPN 内 DNS；`setMtu()` 定 tun 的 MTU。DNS 泄漏的经典成因：接管了业务网段但 DNS 服务器地址没进 addRoute（或用了系统默认 DNS），解析请求从物理网卡明文发出——既拿不到内网解析也暴露访问意图。配置完整性检查：业务网段、DNS 服务器网段、（IPv6 双栈时）v6 默认路由三样都要进路由。

**Q3: [learning] VPN 应用自己连服务器为什么必须 protect()？漏掉的典型场景是什么？**

`protect(int socket)`（VpnService.java:292 核对，另有 Socket/DatagramSocket 重载）把套接字从 VPN 路由中豁免，走底层物理网络——不加保护，VPN 自身的隧道连接命中自己声明的路由又进 tun，形成回环死循环。两个硬约束：必须在 `connect()` **之前**调用（对已连接 socket 无效）；只对底层 socket 生效。易漏场景（社区经验归纳）：用了连接池/三方网络库（OkHttp 等）时拿不到裸 socket 时机；隧道重建后新 socket 忘了 protect； protect 了主连接但漏了 DNS 查询 socket，导致解析仍进 tun。

**Q4: [learning] 分应用 VPN 怎么配？有哪些互相矛盾与不自洽的坑？**

`Builder.addAllowedApplication()` / `addDisallowedApplication()` 二选一且互斥——同时用会失败（本地 Builder 方法核对）：白名单模式只接管的 app 进 VPN，黑名单模式这些 app 不进；两者都不调用则接管全部应用。三个坑：VPN 应用自身要显式排除（否则自己的隧道流量进 tun 回环）；白名单模式下系统组件（下载管理器、同步框架）不在名单里，其流量不走 VPN，行为与应用内下载不一致；uid 是按包名当前解析的，应用卸载重装后名单语义随系统实现处理，不能假设长期稳定。

**Q5: [learning] always-on 与 lockdown 是什么？对 VPN 应用有什么强制要求？**

always-on：用户在设置里指定开机自动拉起的 VPN 应用，系统在启动时直接启动其 VpnService（无需用户点开应用）；应用要在 meta-data 声明支持（`SERVICE_META_DATA_SUPPORTS_ALWAYS_ON`，本地 Vpn.java:754 核对，声明不支持的包不能设为 always-on）。lockdown（锁定模式）在 always-on 基础上加"VPN 不通就断网"：未建隧道期间禁止流量走底层网络，白名单经 `ALWAYS_ON_VPN_LOCKDOWN_WHITELIST`（Vpn.java:207 核对）管理豁免应用。对应用的要求：服务必须能被系统静默启动、无 UI 也能完成鉴权，`onRevoke()`（:471 核对，"VPN 被系统收走"的回调）里正确关闭 fd 与连接池并按策略重试。

**Q6: [learning] 平台级 VPN（Ikev2VpnProfile/VpnManager）与自研 VpnService 怎么选？**

Android 10 起提供 Platform VPN：应用通过 `VpnManager`（本地 13 源码存在核对）提交 `Ikev2VpnProfile`（ extends PlatformVpnProfile，本地核对），由系统内置 IKEv2 客户端建隧道——应用只提供服务器地址、鉴权凭据等配置，不写协议栈。选型对照：协议恰是 IKEv2 且不需要自定义代理逻辑时选平台 VPN（免维护协议栈、与 always-on/lockdown 原生集成）；需要私有协议、混淆、分流代理时才自研 VpnService，代价是 tun 转发、TCP 代理栈、protect 纪律全要自己做。两者共用同一套系统 VPN 槽位，同样受单活跃 VPN 约束。

**Q7: [learning] 车机上 VPN 与多 APN/多网卡怎么共存？谁的路由优先？**

VPN 本身被系统包装成一张"有 VPN transport 的网络"参与统一评分与策略路由（框架机制，01 册口径），与蜂窝/Wi-Fi/多 APN 的 veth 各占独立路由表，靠 mark+rule 分流：进 VPN 路由的流量从 tun 走，未接管的（如私网 APN 网段没进 VPN 路由）继续走原链路。车机典型组合：TSP 私网业务走专网 APN 不进 VPN，公网业务进 VPN 隧道——配置时把私网网段显式排除在 VpnService 路由之外即可天然共存。风险点：VPN 的 addRoute 若含 0.0.0.0/0 会连私网一起接管，车机上表现为私网业务断连；排查时在 02 册五段定位法前面加一段——"包是否被 tun 截走"。

**Q8: [learning] VPN 应用的常见故障怎么按生命周期归位？**

按四个阶段对号：授权——首次运行必须 `prepare()` 弹用户授权，用户拒绝则 establish 失败（静默重试无意义，要引导设置页）；建立——establish 失败多为已被其他 VPN 占用（单活跃约束）或参数越界（地址/路由重叠）；**运行**——隧道断流表现为"应用全部超时"，先查底层网络是否可用（VPN 的 underlying network），再查 protect 是否遗漏（回环死循环的特征是完全无出口流量）；**被收走**——onRevoke 触发（用户撤销、被别的 VPN 抢占、always-on 切换）后必须停栈关 fd，重启策略要区分"用户主动撤销"（不重连）与"系统恢复"（always-on 场景等系统拉起）。线上监控抓 establish 成功率、tun 读写速率与底层网络切换事件三个信号。

**Q9: [learning] VPN 的配置存哪里、诊断看哪里？**

平台侧配置存储由 VpnProfileStore 承担（本地 AAOS13 源码文件核对），应用自研方案通常自己加密持久化 profile——凭据不能明文落盘。诊断三处：`dumpsys connectivity` 看 VPN 网络的 capabilities 与底层网络（underlying）指向；always-on 状态变化系统会发带 `CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED` 的通知事件（Vpn.java:776 核对），可据此做开机自启失败告警；抓包层面确认"包进 tun 没、出物理网卡没"两段（02 册五段定位法的 VPN 版）。车机交付注意：VPN 配置随用户/系统用户走（AAOS 多用户下 headless system user 的 VPN 状态与前台用户隔离，多用户行为参考 OEM 册多用户篇），升级系统后 always-on 包名失效是常见回归点。

**Q10: [learning] VPN 隧道下"小包通、大包不通"——tun MTU 与分片黑洞怎么破？**

根因是双层封装吃掉了 MTU：应用按接口 MTU（常 1500）发包进 tun，VPN 再加隧道头后超出物理网卡 MTU；若原始包 DF 位置位（TLS/QUIC 常见），中间设备不能分片、ICMP"需要分片"又被策略丢弃，就是静默黑洞——症状精确表现为"ping 小包通、大包不通，网页/握手开一半卡死"。对策链：`Builder.setMtu()` 把 tun MTU 调小给隧道头留余量（不设则用系统默认，javadoc 核对：非正数直接 IllegalArgumentException）；隧道内层做 MSS clamp 或用户态转发时按对端 PMTU 切分；确保 ICMP"目的地不可达-需要分片"能回来（PMNUD 依赖它）；排障先用 `ping -M do -s <size>` 阶梯探测找黑洞尺寸，再按"tun MTU→隧道 overhead→物理 MTU"逐层核对。手机热点给车机做上游、或 clat/v6-only 链路（02 册 Q13）叠加时，有效 MTU 更小，这个坑出现频率显著上升。
