# Android 网络与车载网络

> 网络框架、蜂窝无线、传输协议、网络约束、多 APN、VPN、车载网络与排查。本目录收录 10 篇资料、178 道题。文档结构和编号统一遵守 [../WRITING-GUIDE.md](../../WRITING-GUIDE.md)Q 序列即结构，供 atlas 同源直读。

## 册

- [01-网络框架.md](01-网络框架.md)（24 题）— ConnectivityManager 语义、默认网络选择、DNS 链路、NetworkAgent、策略路由。
- [02-蜂窝与无线.md](02-蜂窝与无线.md)（26 题）— APN 配置、蜂窝栈重写、热点共享、Wi-Fi 编程面、双卡与 5G 判定、RIL 链路。
- [03-传输协议.md](03-传输协议.md)（16 题）— TCP 定界、Nagle、keepalive、证书链事故、拥塞控制、选型与 UDP 坑。
- [04-应用网络约束.md](04-应用网络约束.md)（17 题）— Doze/App Standby、WorkManager 时机、IPv6 双栈、重试幂等、0-RTT、ECH/CT。
- [05-多APN与虚拟网卡.md](05-多APN与虚拟网卡.md)（19 题）— 双形态上网、veth 隔离拓扑、SNAT、DNS 串流、CLAT、打洞。
- [06-VPN.md](06-VPN.md)（10 题）— VpnService 数据面、Builder 语义、分应用、always-on、多网共存、MTU 黑洞。
- [07-车载网络.md](07-车载网络.md)（14 题）— E/E 网络域、TSN/gPTP、时间同步、STA+AP、bufferbloat。
- [08-车机网络安全.md](08-车机网络安全.md)（8 题）— 攻击面盘点、传输安全基线、信任锚管理、调试口治理。
- [09-应用网络实践.md](09-应用网络实践.md)（33 题）— HTTPDNS 接入、OkHttp 链路、切网度量、卫星约束、配额语义。
- [10-网络排查.md](10-网络排查.md)（11 题）— 抓包权限、tcpdump/ip/ss、Perfetto 网络轨迹、弱网台架、DNS 证据。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
