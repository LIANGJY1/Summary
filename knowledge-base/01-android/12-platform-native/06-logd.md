# logd 日志链路

> 学习资料（文章模式沉淀）。边界：本文回答"一条日志从调用到 logd 的路径、级别过滤省什么、丢弃与裁剪机制、logcat 过滤执行位置"；R8 构建期删日志与包体积治理见 [应用 CPU 与体积优化](../14-cpu-power/04-app-cpu-size-optimization.md)。源文档：android-internals-wiki §1.27（Android 17 语境）。Q 序列即结构，供 atlas 同源直读。

**Q1: 一条 Log.d() 从调用到 logd 要经过什么？native 层的级别过滤能省掉哪些成本、省不掉哪些？**

主路径是：`Log.d` → `println_native` → JNI 取得 tag 与 message 的 Modified UTF-8 表示 → `__android_log_buf_write()` 内再次执行级别判断 → liblog 的 `LogdWrite()` 用 `writev()` 一次提交头部与 payload → `/dev/socket/logdw`（AF_UNIX 数据报套接字，内核附带发送方 PID/UID/GID 凭据，客户端无法伪造身份）→ logd `LogListener` → `LogBuffer::Log()`。

native 级别过滤省得掉套接字写入和 logd 端成本，省不掉调用方已经完成的工作——`Log.d()` 的参数在进入 native 方法前已求值，字符串拼接、对象 `toString()`、JSON 序列化照常发生。所以高频路径要在格式化之前判断（`if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG))`），只在调用外包一层普通函数但仍传入已构造的 String 是无效的。

边界：payload 上限 `LOGGER_ENTRY_MAX_PAYLOAD` 为 4068 字节，超长 payload 在 native 侧被截断，Java 层 `Log.printlns()` 会把长文本和堆栈按字节预算拆成多条——一条长异常堆栈对应多次 JNI 与 `writev()`，诊断信息应把稳定标识放在前面。另外两条旁路不要混淆：EventLog 二进制事件走 events buffer，而现代 StatsD atom 自 Android R 起经独立的 `/dev/socket/statsdw` 直达 statsd，不经过 logd。

**Q2: 应用日志风暴会把主线程阻塞在 logd 上导致 ANR 吗？日志的两种丢失分别怎么发生？**

不会阻塞。Android 17 的 liblog 为普通 buffer（main、system、radio、events、stats、crash 等）使用 `SOCK_NONBLOCK`：socket 暂不可用时写入立即返回 `EAGAIN`，liblog 记一次丢弃就返回，不等待 logd；后续恢复时会尝试用 event tag 1006（`liblog`）上报此前丢弃的数量。"socket 满后 write 阻塞主线程导致 ANR"不符合这一实现。

两种丢失机制不同：

1. **写入端丢弃**：如上，logd 来不及接收时 liblog 遇 `EAGAIN` 丢新日志；
2. **服务端裁剪**：Android 17 默认使用 serialized buffer，日志按 chunk 序列化保存、封存后以 Zstd 1 级压缩，超过目标容量时从最老的 chunk 开始裁剪——旧机制 chatty（重复日志折叠）自 Android S 起已被压缩方案取代，旧设备日志里出现 chatty 不代表当前版本还有该行为。

日志风暴的真实风险是：调用方持续做格式化、JNI 转换和系统调用消耗 CPU 与电量；关键现场被噪声覆盖或丢失；logd 忙于接收、压缩、裁剪和读取分发增加系统负载。做法：用 `adb logcat -g` 查目标设备各 buffer 容量；降低写入率、聚合并只保留诊断所需字段；不要对 `EAGAIN` 做无间隔重试。边界：`security` 是受权限控制的特殊 buffer，liblog 为它另设阻塞 socket。

**Q3: logcat 的 tag 过滤（MyApp:V *:S）与 --regex 在哪里执行？为什么它们不一定减少 logd 的传输量？**

都在 logcat 客户端进程执行：tag/priority 由 logcat 的 `android_log_shouldPrintLine()` 判断，`--regex` 由它的 `std::regex_search()` 执行，logd 不知道这些规则，仍会发送全量数据——所以它们主要减少终端输出，不一定减少 logd 到 logcat 的传输。复杂正则消耗的是 logcat 客户端 CPU，不能写成 logd 因正则匹配而 CPU 升高。

服务端能执行的筛选是另一组：log ID mask（`-b`）、`--pid`、起始时间或日志序号、tail 条数、非阻塞与 wrap 等读取模式。长期采集先缩小服务端范围，再加客户端显示过滤：

```bash
adb logcat -b main --pid="$(adb shell pidof -s com.example.app)" \
  -v threadtime 'MyApp:V' '*:S'
```

`-b main` 与 `--pid` 控制 logd 发送的数据范围，后面的 filter spec 只决定打印哪些 tag。边界：每个 reader 有独立读取线程，多开 logcat 会增加服务端解压与发送负担，但没有"每客户端固定开销"的通用阈值；读取位置落到被裁剪数据之后的客户端会被跳过并记录警告。
