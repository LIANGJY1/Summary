# AVF 虚拟化、logd 日志、BPF 与端侧 AI 技术栈

> 学习资料（文章模式沉淀）。主线：AVF/pKVM 的隔离边界与性能测量口径、logd 日志链路的真实成本、Android 17 BPF 能力的四道门槛，以及 AI 手机技术栈的交付方与协作边界——每个"支持"都要落到具体组件、版本和权限上判断。源文档：android-internals-wiki §1.26《Android 17 AVF 架构与 pKVM 隔离性能边界》、§1.27《Android logd 日志系统性能与开销》、§1.28《Android 17 / ACK 6.18 BPF 可观测性与可编程边界》、§1.29《Android AI 手机技术栈》（Android 17 / ACK 6.18 语境）；AVF 架构与 pKVM 安全模型、NNAPI 弃用状态、R8 日志删除规则已于 2026-09-25 与 source.android.com、developer.android.com 核对。应用沙箱的 UID/SELinux/seccomp 隔离边界见 [04-Sanbox.md](./04-Sanbox.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 17 的 AVF 实现里，VirtualizationService、virtmgr、crosvm、pKVM、pvmfw、Microdroid 分别运行在哪里、负责什么？**

它们分属不同进程与特权级：API 37 的宿主侧由多个独立进程组成，`VirtualizationService` 相关逻辑并不在 `system_server` 里。

1. **framework-virtualization（应用进程）**：`VirtualMachineManager`/`VirtualMachine` 等 `@SystemApi` 入口，经 Unix 域套接字上的 RpcBinder 与 virtmgr 通信；
2. **virtmgr（Rust 子进程）**：管理 AIDL 生命周期、镜像与文件描述符准备、启动并监控 crosvm；一个 virtmgr 可以管理多台 crosvm 子进程；
3. **VirtualizationServiceInternal（全局 Rust lazy Binder 服务）**：负责虚拟机上下文 ID（CID）、全局资源与统计，按需启动，与 virtmgr 不同进程、也不在 system_server；
4. **crosvm（每个运行中的 VM 一个进程）**：虚拟机监控器（VMM），通过 `/dev/kvm` 的 ioctl 创建并运行 VM，管理 vCPU 线程、virtio 设备和 VM 内存布局；
5. **pKVM（内核 EL2，来自 ACK 的 KVM）**：管理宿主与客户机的 Stage-2 地址转换权限、页面所有权、vCPU 切换和 pVM 保护；
6. **pvmfw（pVM 首段固件）**：验证初始镜像、维护实例身份、派生每台 VM 的机密；
7. **Microdroid（客户机 OS）**：验证启动、SELinux、Bionic、原生 payload 与基于 vsock 的 Binder RPC。

排查 AVF 问题时先确认组件落点：framework API 报错看应用与 virtmgr，VM 启动失败看 crosvm 与 pvmfw，内存隔离问题才到 pKVM 与内核层。

**Q2: Microdroid 是"小号完整 Android"吗？protected VM 里的 Microdroid 能直接用 GPU/NPU 加速通用 AI 推理吗？**

都不是。Microdroid 为原生 payload 提供的是基础设施：Bionic C 库、验证启动、SELinux、APEX 系统组件、日志与崩溃调试，以及基于 vsock 的 Binder RPC；它明确不提供 `system_server` 和 Zygote、图形 UI、HAL，也不提供 `android.*` Java framework API。启用 ART APEX 后能使用 `java.*` 核心 API，但不等于拥有常规 Android 应用运行环境。

推论：以下说法在 API 37 都没有依据——Microdroid 默认含完整 ART、SystemServer 和 Service Manager 服务集；protected 模式的 Microdroid 可以直接使用 virtio-gpu 或 NPU HAL 加速通用 AI 推理；它能承载完整工作资料、Launcher 或 SystemUI。payload 通常是 APK 内嵌的原生共享库，由 Microdroid payload launcher 执行。需要 UI、GPU 或设备直通时，应按具体客户机、crosvm 构建选项和产品安全策略单独评估自定义 VM，不能套用 Microdroid 的能力表。

**Q3: pKVM 靠什么阻止宿主 Android 读取 protected VM 的私有内存？这种保护覆盖什么、不承诺什么？**

pKVM 在宿主上下文中也启用 Stage-2 地址转换：宿主 Stage-2 使用恒等映射，EL2 维护每个物理页的所有者；创建 pVM 时宿主把页面 donate（捐赠）给客户机，这些页随即从宿主 Stage-2 的可访问映射中撤销——即使 crosvm 仍保留着建立 KVM memslot 的虚拟地址区间，宿主 CPU 和受宿主控制的设备也不能再读到这些页。

官方安全模型确认的覆盖范围：

1. **机密性**：pKVM 跟踪页面所有权，页面只有所有者显式 share 后才能被其他 pVM 映射，规则同样约束 CPU 与 DMA 访问；
2. **完整性**：pVM 之间不能未经同意修改对方内存、不能影响对方 CPU 状态；页面归还宿主前会被清理（撤销客户机映射并清写内容）；
3. **通信靠显式共享**：受保护客户机为 virtqueue 预留固定共享内存窗口，客户机在私有页与共享窗口之间做中转复制（bounce copy）——这也是 pVM I/O 额外延迟与尾延迟的来源。

不承诺的是可用性：KVM 有意把调度委托给宿主内核，恶意宿主可以选择永不调度客户机 vCPU，VMM 也能扣留内存和虚拟设备，宿主始终可以终止 crosvm 让整台 VM 停止。已 donate 的页不能被宿主换出或 KSM 合并，回收要靠客户机经 `relinquish` 或 balloon 主动归还。安全设计不能把"数据不被读取"写成"服务不会被中断"。

**Q4: pVM 内的任务变慢，为什么只看客户机内的 Perfetto 不够？vCPU 与 VM exit 的成本结构是什么？**

因为每个 vCPU 只是 crosvm 里的一个 POSIX 线程，客户机看到的慢可能来自三层，而后两层在客户机内完全不可见：客户机内线程没有被客户机调度器选中；对应 vCPU 线程在宿主上处于 runnable 却没获得 CPU；vCPU 因 MMIO、virtio 或中断等事件退出后在 crosvm 或宿主内核等待。

成本结构：

1. vCPU 线程调用 `KVM_RUN` 进入客户机，需要 VMM 处理时返回宿主用户空间；宿主调度器可以抢占它，也能用 CPU affinity、cpuset、uclamp 等常规 QoS 机制控制——客户机不能绕过宿主调度器拿到物理 CPU；
2. 一次 I/O 不等于一次完整 VM exit：virtio 控制面走 MMIO，数据面主要走共享 virtqueue，eventfd/epoll 通知、中断合并和队列深度都会改变退出与唤醒次数；
3. protected VM 的数据面还要叠加共享窗口 bounce copy 与缓存维护，影响吞吐与尾延迟。

做法：把客户机时间线与宿主 crosvm/vCPU 线程调度对齐，统计每单位业务数据的退出与唤醒次数。没有设备型号、频点、负载和分布数据时，"VM exit 5–10 μs"或"比 syscall 慢 3–10 倍"不是平台结论。

**Q5: 一条 Log.d() 从调用到 logd 要经过什么？native 层的级别过滤能省掉哪些成本、省不掉哪些？**

主路径是：`Log.d` → `println_native` → JNI 取得 tag 与 message 的 Modified UTF-8 表示 → `__android_log_buf_write()` 内再次执行级别判断 → liblog 的 `LogdWrite()` 用 `writev()` 一次提交头部与 payload → `/dev/socket/logdw`（AF_UNIX 数据报套接字，内核附带发送方 PID/UID/GID 凭据，客户端无法伪造身份）→ logd `LogListener` → `LogBuffer::Log()`。

native 级别过滤省得掉套接字写入和 logd 端成本，省不掉调用方已经完成的工作——`Log.d()` 的参数在进入 native 方法前已求值，字符串拼接、对象 `toString()`、JSON 序列化照常发生。所以高频路径要在格式化之前判断（`if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG))`），只在调用外包一层普通函数但仍传入已构造的 String 是无效的。

边界：payload 上限 `LOGGER_ENTRY_MAX_PAYLOAD` 为 4068 字节，超长 payload 在 native 侧被截断，Java 层 `Log.printlns()` 会把长文本和堆栈按字节预算拆成多条——一条长异常堆栈对应多次 JNI 与 `writev()`，诊断信息应把稳定标识放在前面。另外两条旁路不要混淆：EventLog 二进制事件走 events buffer，而现代 StatsD atom 自 Android R 起经独立的 `/dev/socket/statsdw` 直达 statsd，不经过 logd。

**Q6: 应用日志风暴会把主线程阻塞在 logd 上导致 ANR 吗？日志的两种丢失分别怎么发生？**

不会阻塞。Android 17 的 liblog 为普通 buffer（main、system、radio、events、stats、crash 等）使用 `SOCK_NONBLOCK`：socket 暂不可用时写入立即返回 `EAGAIN`，liblog 记一次丢弃就返回，不等待 logd；后续恢复时会尝试用 event tag 1006（`liblog`）上报此前丢弃的数量。"socket 满后 write 阻塞主线程导致 ANR"不符合这一实现。

两种丢失机制不同：

1. **写入端丢弃**：如上，logd 来不及接收时 liblog 遇 `EAGAIN` 丢新日志；
2. **服务端裁剪**：Android 17 默认使用 serialized buffer，日志按 chunk 序列化保存、封存后以 Zstd 1 级压缩，超过目标容量时从最老的 chunk 开始裁剪——旧机制 chatty（重复日志折叠）自 Android S 起已被压缩方案取代，旧设备日志里出现 chatty 不代表当前版本还有该行为。

日志风暴的真实风险是：调用方持续做格式化、JNI 转换和系统调用消耗 CPU 与电量；关键现场被噪声覆盖或丢失；logd 忙于接收、压缩、裁剪和读取分发增加系统负载。做法：用 `adb logcat -g` 查目标设备各 buffer 容量；降低写入率、聚合并只保留诊断所需字段；不要对 `EAGAIN` 做无间隔重试。边界：`security` 是受权限控制的特殊 buffer，liblog 为它另设阻塞 socket。

**Q7: logcat 的 tag 过滤（MyApp:V *:S）与 --regex 在哪里执行？为什么它们不一定减少 logd 的传输量？**

都在 logcat 客户端进程执行：tag/priority 由 logcat 的 `android_log_shouldPrintLine()` 判断，`--regex` 由它的 `std::regex_search()` 执行，logd 不知道这些规则，仍会发送全量数据——所以它们主要减少终端输出，不一定减少 logd 到 logcat 的传输。复杂正则消耗的是 logcat 客户端 CPU，不能写成 logd 因正则匹配而 CPU 升高。

服务端能执行的筛选是另一组：log ID mask（`-b`）、`--pid`、起始时间或日志序号、tail 条数、非阻塞与 wrap 等读取模式。长期采集先缩小服务端范围，再加客户端显示过滤：

```bash
adb logcat -b main --pid="$(adb shell pidof -s com.example.app)" \
  -v threadtime 'MyApp:V' '*:S'
```

`-b main` 与 `--pid` 控制 logd 发送的数据范围，后面的 filter spec 只决定打印哪些 tag。边界：每个 reader 有独立读取线程，多开 logcat 会增加服务端解压与发送负担，但没有"每客户端固定开销"的通用阈值；读取位置落到被裁剪数据之后的客户端会被跳过并记录警告。

**Q8: debuggable=false 的 release 包会自动删除 Log.d() 吗？R8 按级别删除日志怎么配置？**

不会自动删除。`debuggable=false` 只让 liblog 按 tag 属性和默认优先级决定是否写入，调用点、参数求值和 JNI 入口仍然发生。要构建期删除，当前 R8 文档提供 `-maximumremovedandroidloglevel`，按级别删除 `Log.*` 与 `Log.isLoggable()` 调用：

```proguard
# INFO 对应数值 4；删除 INFO 及以下级别。
-maximumremovedandroidloglevel 4
```

级别映射为 VERBOSE=2、DEBUG=3、INFO=4、WARNING=5、ERROR=6、ASSERT=7；可选的类规范可以把规则限定到特定类；多条规则命中同一方法时 R8 取最小级别。

边界与做法：该规则近年才进入正式文档，使用前确认项目 R8/AGP 版本支持，规则只进 release 变体，并用反编译或 mapping 输出验证结果；`-assumenosideeffects` 属于强制优化假设，参数带可观察副作用时不保证连带删除，构建期删除应配合调用前的条件判断。隐私上，`READ_LOGS` 自 Android 4.1 起是特权权限，但预装特权组件与 bugreport 仍可能读到日志——令牌、密码、会话密钥应完全不入日志（掩码只适合允许展示部分信息的字段），异常对象也要审查，服务端响应和 URI 查询参数可能经异常文本进入日志。

**Q9: "内核支持 BPF"为什么推不出"这台设备上应用能用"？要依次通过哪四道检查？**

因为 BPF 可用能力是四项条件的交集：内核实现 ∩ 内核配置 ∩ 已加载并附着的程序 ∩ 调用方权限。verifier 通过只是第一道安全检查，仅看到 `CONFIG_BPF_SYSCALL=y`、某个 `.bpf` 文件或 `/sys/fs/bpf` 目录，都不足以得出"可用"的结论。

1. **内核实现**：当前 ACK 源码是否包含对应的 map、program type、helper、kfunc 或 attach 点；
2. **内核配置与 JIT**：设备内核是否编译该能力，当前 CPU 架构的 JIT 是否支持；
3. **装载与附着**：BPF 对象是否随系统镜像安装，loader 是否按内核版本与 feature flag 加载，程序是否已附着到目标事件；
4. **调用方权限**：文件权限、Linux capability 与 SELinux 权限。

补充边界：BPF LSM 被编译进内核也不代表 Android 用 BPF LSM 替换了 SELinux——是否启用、以何种顺序参与安全决策还取决于启动参数、LSM 列表和产品策略；普通应用始终受 Android UID、capability、SELinux 和 bpffs 节点 owner/group/mode 约束。

**Q10: "系统是 Android 17"能推出内核一定是 6.18 吗？内核版本边界怎么确认？**

不能。Android 17 的新 ACK 是 `android17-6.18`，但兼容表同时列出多条可用于 Android 17 的较早 GKI 内核：`android16-6.12`、`android15-6.6`、`android14-6.1`、`android14-5.15`、`android13-5.15` 等（`android12-5.10`、`android13-5.10` 自 Android 17 QPR1 起不再支持）。反方向同样要谨慎：在 `android17-6.18-2026-06_r6` 中确认存在的 Arena 或 `sched_ext`，不能写成所有 Android 17 设备都有。

排查第一步是记录运行内核：

```bash
adb shell uname -r
```

这只给出版本字符串；确认它对应哪个受支持的 GKI 构建还要结合 KMI generation、安全补丁级别和厂商模块。另一个常见错误是把 Linux 6.10 当成 Android 17 的内核分支——它只是上游演进的一个版本阶段，Android 17 的 6.18 ACK 已包含 Arena、`sched_ext`、BPF iterators、BPF LSM 和 `struct_ops`。判断某项特性是否可用应直接检查目标 ACK，不凭上游版本推测回移。

**Q11: 平台 BPF 程序如何装载？"镜像里有 .bpf 文件"等于"设备正在采集"吗？**

不等于。Android 17 用 Rust 实现的 bpfloader，读取显式描述表而不是"扫描 `/system/etc/bpf` 全部加载"，并按 build 类型、feature flag、CPU 架构和 min/max kernel version 筛选；init 挂载 bpffs（`nodev noexec nosuid`）并在 Zygote 启动前触发 `load-bpf-programs`，加载后设置节点 owner/group/mode。

"镜像里有文件"与"正在采集"之间的差距来自装载条件：

1. `auto_attach=true` 的程序加载即附着并 pin link；`auto_attach=false` 只加载并 pin program，附着由后续组件完成；
2. 非 critical 对象加载失败只记错误并继续启动；`skip_on_user` 的测试对象在量产 user build 中不加载；
3. 部分对象受 feature flag 或内核版本限制：wakelock 时长、DMA-BUF iterator、锁竞争程序各需对应 flag，锁竞争还要求内核至少 6.1；`cyclePerUid` 仅 x86_64 且需要专门 flag，不能写成 arm64 手机的通用能力。

另外，网络流量统计与 Tethering 的 BPF 由 `netbpfload` 等组件另行管理，不在上述平台观测对象之列。排查时按"内核支持 → 对象安装 → loader 加载 → attach → 事件触发 → map 更新 → 消费方有权限读取"逐段验证，前一段没有证据时，空数据不能归因给业务或消费方。

**Q12: ACK 6.18 编译了 BPF Arena 和 sched_ext，它们会自动改变设备行为吗？**

都不会。`CONFIG_SCHED_CLASS_EXT=y` 与 Arena 的实现进入内核只说明"框架存在"，两者都需要用户空间主动参与才会生效。

- **Arena**（`BPF_MAP_TYPE_ARENA`）：BPF 程序与用户进程之间的稀疏共享内存，必须设置 `BPF_F_MMAPABLE`、当前架构 JIT 支持，`max_entries × PAGE_SIZE` 最大 4 GiB，用户空间 VMA 不能跨越 32 位地址边界；它不是键值 map——lookup、update、delete 等 map 操作返回不支持，也不会自动附着到任何事件或自动减少内存占用。
- **sched_ext**：初始状态 `disabled`，只有用户空间成功加载并附着一份 `sched_ext_ops` 才进入 `ENABLED` 并切换符合条件的任务（未设 `SCX_OPS_SWITCH_PARTIAL` 时是全部符合条件）；BPF 调度器报错、退出或 watchdog 超时后，内核停用它并把任务交回内置公平调度类（Android 17 为 EEVDF）。

确认 sched_ext 状态可读 `/sys/kernel/sched_ext/state` 等只读节点；`enable_seq` 只增不减，非 0 只证明曾经启用过，不证明当前仍在运行。Android 17 AOSP 没有"ML Scheduler"或 `bpf_runqueue_hook` 这类系统级通用接口——"关键任务响应时间改善 35%"这类说法若缺少可复现实验，不能作为平台结论。

**Q13: 同样叫"AI 手机"，能力可能由哪几层交付？选型时先回答什么？**

先确定每一层由谁交付，因为接口名称相似不代表执行位置、数据边界和可用性相同。五层交付：

1. **芯片与厂商软件**：CPU、GPU、DSP/NPU、驱动、厂商 delegate——不保证同一模型在不同设备上的算子覆盖与性能一致；
2. **AOSP Android 17**：AppFunctions、Binder、权限、Power/Thermal API、Neural Networks HAL——不保证预装大模型或向普通应用开放统一 NPU 指令集；
3. **Google 设备组件**：AICore、Gemini Nano、ML Kit GenAI——不保证所有 Android 17 设备可用或不同 Nano 版本输出一致；
4. **应用推理运行时**：LiteRT、MediaPipe、自研或厂商 SDK——不保证自动得到最优 delegate、质量或功耗；
5. **云端模型**：更大模型与服务端算力——不保证离线可用或数据不出设备。

delegate 是推理运行时连接特定硬件后端的适配器，属于软件，不能与 NPU 等硬件单元并列；即使两台手机都有 NPU，支持的精度、算子与驱动质量也可能不同。

选型时先回答三个问题：能力由谁交付、模型在哪里运行、失败后由谁兜底。用共享端侧模型选 ML Kit GenAI/AICore（应用不打包模型，但要处理可用性与配额）；要自定义模型与离线控制选 LiteRT 自带模型（应用负责模型交付、delegate 覆盖率验证与后端回退）；更大模型与在线知识走云端。同一系统版本下 AI 能力仍可能不同，应用要检测具体 API 与模型可用性并准备回退。

**Q14: Android 17 上 NNAPI 还能用吗？Neural Networks HAL 存在能说明应用模型跑在 NPU 上吗？**

NNAPI NDK API 自 Android 15（API 35）起已弃用，官方迁移说明提醒未来多数设备可能只有 CPU 后端，性能敏感负载应迁移到其他方案（如 LiteRT GPU 运行时），新项目不应再把 `ANeuralNetworks*` 当作 Android 17 的推荐应用接口。Neural Networks HAL 仍受支持（`android-17.0.0_r1` 保留 `hardware/interfaces/neuralnetworks/aidl`），但它面向系统实现者和硬件厂商，普通应用不应直接绑定 HAL AIDL。

HAL 存在也不能证明某个应用模型会完整运行在 NPU 上：不受支持的算子会回退 CPU，计算图被分段到 CPU 与加速器之间时，频繁同步和数据复制甚至可能让混合执行慢于纯 CPU。

做法：应用经 LiteRT、ML Kit 或厂商公开 SDK 使用推理能力；评估 delegate 时按设备验证算子覆盖率（多少算子真正交给目标加速器），并为失败或性能倒退准备后端回退。"AIDL HAL + NNAPI 是 Android 17 应用统一 AI 通路"的说法已经过时。

**Q15: 通过 AICore 使用 ML Kit GenAI（Gemini Nano），应用必须处理哪些运行条件？**

AICore 路线让应用使用设备共享的 Gemini Nano 能力（摘要、校对、改写、图像描述、语音识别、Prompt API），应用无需把基础模型打进 APK，但接入时必须处理：

1. **功能可用性**：不同 API、设备和模型版本支持范围不同，调用前用对应 API 的功能状态检查；
2. **模型准备状态**：新设备或 AICore 重置后模型可能未就绪，应显示可恢复状态而不是卡住等待；
3. **前台限制**：当前只允许最前台、用户正在交互的应用使用 GenAI 推理，前台服务不能绕过；
4. **配额**：短时间请求过多返回 `BUSY`，长期使用还有每应用电量配额错误，重试要退避并允许取消；
5. **模型版本差异**：应用可读取基础模型名称，同一提示在不同版本输出可能不同，质量测试不能只覆盖一台设备；
6. **流式结果**：流式缩短首个可见结果的等待，但不减少总计算量。

边界：AICore 是 Google 在兼容设备上交付的系统组件，不是 CDD 要求的通用服务；Android 17 源码中的 `android.app.ondeviceintelligence`（ODI）系统 API 是平台或厂商的服务边界，不等同于 Google AICore 或 Gemini Nano 模型本身。工程上还应为温度、内存紧张和后台场景设计停止与降级；AICore 自己的每应用请求与电量配额，不会因为应用加大线程池而放宽。

**Q16: AppFunctions 的调用边界是什么？实现 onExecuteFunction 为什么必须把耗时工作切出主线程？**

AppFunctions 是把应用能力暴露给持有相应权限的系统级智能代理的跨应用发现与执行框架，不是任意应用互调的通用 RPC 注册表：提供方用 `AppFunctionService` 或运行时注册实现函数，服务必须声明 `BIND_APP_FUNCTION_SERVICE` 且只有 `system_server` 可以绑定，跨包执行受 `EXECUTE_APP_FUNCTIONS` 或系统级权限约束。

主线程约束是 Android 17 源码明确标注的：`onExecuteFunction` 带 `@MainThread`，回调从主线程进入，耗时 I/O、数据库和模型推理必须切到工作线程，响应 `CancellationSignal`，并通过 `callback` 返回成功或错误——否则智能代理的每次调用都会卡住应用主线程。

边界：Android 17 新增运行时注册，只在相应进程与 `Context` 生命周期内有效，调用者要保存 `AppFunctionRegistration` 并在不再需要时 `unregister()`；另有 Activity 或全局作用域、函数状态观察等能力。AppFunctions 不负责动态模型加载、模型版本回滚或推理调度——函数内部用 AICore、自带模型还是云端，是提供方自己的实现选择。
