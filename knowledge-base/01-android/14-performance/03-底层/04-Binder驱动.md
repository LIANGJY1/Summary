# Binder 驱动调用边界

这篇只解释 Binder 驱动的系统调用边界；Framework Binder 调用流程见 [系统 ANR 与故障诊断](../02-系统/04-ANR与故障诊断.md)。

**Q1: [learning] 从系统调用层重建 Binder 调用语义为什么这么难？BINDER_WRITE_READ 的 ioctl 耗时能当 Binder RPC 耗时吗？**

Binder 驱动只处理 transaction code、flags、目标 handle、数据缓冲区和对象 offsets，方法名与 Java/Kotlin 参数类型不在 Binder 内核 ABI 中——要还原“哪个方法被调用”需要四层证据逐级拼接：syscall 层（ioctl 参数与返回值）、Binder UAPI 层（`BINDER_WRITE_READ` 命令字、transaction code、flags、数据长度）、Parcel 层（interface token、字节序列、对象 offsets）、签名表层（descriptor + code 到方法与参数类型的映射）。签名表必须与 `Build.FINGERPRINT`、AIDL 接口版本和采集器版本绑定，同一个 transaction code 跨系统版本不保证对应同一方法。复杂 Parcelable、vendor 接口和动态注册接口都没有系统性覆盖。

ioctl 耗时不能当单次 RPC 耗时：一次 ioctl 的 write buffer 可以包含多条命令，也可能进入读取路径等待工作，oneway 事务不同步等待回复。端到端时延应分段计算：客户端应用 slice + `binder_transaction`/`binder_transaction_received` tracepoint（给出事务调试编号、目标进程/线程、reply 标记）+ 服务端 slice。tracepoint 是元数据，不含完整 Parcel 内容。研究方案（WOOTdroid 的 WDBind）在驱动 `copy_from_user()` 之前读用户地址，存在 TOCTOU、短读和 arm64 地址标签（TBI/MTE）风险，且 v1 不处理 reply——实验只在已 root 的 Android 16 Pixel 上做过，不能外推到量产 user build。权限边界：自定义 eBPF 不是应用公开 API，平台 BPF 程序随系统镜像由 loader 加载（加载链与普通应用的权限边界已在工具文档按 AAOS13 核对）。普通应用的系统侧证据走 Perfetto 与 `ProfilingManager`。
