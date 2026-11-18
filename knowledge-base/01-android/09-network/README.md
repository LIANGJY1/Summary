# Android 网络与车载网络

本目录收录 11 篇资料、200 道题，覆盖 Android 网络框架、蜂窝无线、应用联网、车载网络、蓝牙连接与网络排查。通用 HTTP 机制见 [HTTP 缓存与传输](../../03-network/02-HTTP.md)，通用套接字接口见 [套接字接口](../../02-os/03-进程与通信/03-套接字接口.md)。

## 册

- [01-网络框架.md](01-网络框架.md)（24 题）— ConnectivityManager 语义、默认网络选择、DNS 链路、NetworkAgent、策略路由。
- [02-蜂窝与无线.md](02-蜂窝与无线.md)（26 题）— APN 配置、蜂窝栈重写、热点共享、Wi-Fi 编程面、双卡与 5G 判定、RIL 链路。
- [03-传输协议.md](03-传输协议.md)（14 题）— 车云帧与版本兼容、保活与会话恢复、丢包和拥塞、车云通道选型及链路排查。
- [04-应用网络约束.md](04-应用网络约束.md)（17 题）— Doze/App Standby、WorkManager 时机、IPv6 双栈、OkHttp 重试与缓存、0-RTT、ECH 与证书透明度。
- [05-多APN与虚拟网卡.md](05-多APN与虚拟网卡.md)（19 题）— 双形态上网、veth 隔离拓扑、SNAT、DNS 串流、CLAT、打洞。
- [06-VPN.md](06-VPN.md)（10 题）— VpnService 数据面、Builder 语义、分应用、always-on、多网共存、MTU 黑洞。
- [07-车载网络.md](07-车载网络.md)（14 题）— E/E 网络域、TSN/gPTP、时间同步、STA+AP、bufferbloat。
- [08-车机网络安全.md](08-车机网络安全.md)（8 题）— 攻击面盘点、传输安全基线、信任锚管理、调试口治理。
- [09-应用网络实践.md](09-应用网络实践.md)（36 题）— HTTPDNS、OkHttp 解压与连接复用、gRPC 调用与重试、迁移与切网度量、卫星和配额适配、BluetoothSocket 与 NFC。
- [10-网络排查.md](10-网络排查.md)（11 题）— 抓包权限、tcpdump/ip/ss、Perfetto 网络轨迹、弱网台架、DNS 证据。
- [11-蓝牙连接与数据同步.md](11-蓝牙连接与数据同步.md)（21 题）— 配对、扫描、回连、多设备连接状态、联系人与通话记录同步。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。
