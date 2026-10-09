# Android 日志调用、丢弃与 logcat 过滤边界

> 学习资料（文章模式沉淀）。边界：本文回答 Android 日志从调用到 logd 的路径、级别过滤能节省的成本、写入丢弃与缓冲区裁剪的区别，以及 logcat 客户端过滤和 logd 读取过滤的边界。R8 构建期删日志与包体积治理归应用 CPU 与体积优化主题。实现细节按 Android 17 语境核对，参考 AOSP `system/logging/liblog`、`system/logging/logd`、`system/logging/logcat` 和 `frameworks/base` 日志实现。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] 一条 Log.d() 从调用到 logd 经过哪些步骤？native 级别过滤能省掉哪些成本？**

`Log.d()` 进入 logd 前经过 Java/native 桥和 liblog。native 级别判断能阻止不需要的日志继续传输，但不能撤销调用参数已经完成的构造工作。

1. **Java 入口：**应用调用 `Log.d(tag, message)` 后，Java 日志实现进入 `println_native`。调用参数在进入方法前已经求值，因此拼接字符串、调用对象的 `toString()` 或 JSON 序列化都已经发生。
2. **JNI 转换：**JNI 取得 tag 和 message，并形成供 native 日志接口写入的 Modified UTF-8 数据。
3. **native 级别判断：**`__android_log_buf_write()` 再按 tag 和 priority 执行 loggability 判断。被过滤的日志不会进入后续 socket 写入和 logd 接收路径，但之前的参数构造和 JNI 调用仍可能已经发生。
4. **liblog 写入：**通过过滤的消息由 liblog 的 `LogdWrite()` 组成日志头和 payload，再用 `writev()` 写入 `/dev/socket/logdw`。
5. **logd 接收：**`/dev/socket/logdw` 是 AF_UNIX 数据报 socket。内核附带发送方 PID、UID 和 GID 凭据，客户端不能通过自己填写日志内容伪造这些凭据。logd 的 LogListener 收到数据后交给 LogBuffer 保存。
6. **调用前保护：**高频路径应在格式化前判断，例如先检查 `BuildConfig.DEBUG` 或 `Log.isLoggable(TAG, Log.DEBUG)`，再构造日志参数。若传入方法前已经构造完整 String，仅在外层包装一个函数并不会省掉字符串构造。
7. **单条消息上限：**`LOGGER_ENTRY_MAX_PAYLOAD` 为 4068 字节，native 写入超过上限时会截断。Java `Log.printlns()` 会按字节预算把长文本或异常堆栈拆成多条记录。因此长堆栈会增加 JNI 和 `writev()` 次数，应把包名、请求 ID 等稳定识别信息放在较前位置。
8. **旁路日志：**EventLog 二进制事件写入 events buffer。StatsD atom 从 Android R 起可经独立的 `/dev/socket/statsdw` 送往 statsd，不经过 logd 的普通文本日志链路。

**Q2: [learning] Android 17 的普通日志风暴会因 logd socket 满而阻塞主线程吗？写入丢弃和缓冲区裁剪有什么区别？**

Android 17 的普通日志 buffer 写入使用非阻塞 socket。logd 接收队列满时，普通日志写入会以 `EAGAIN` 返回并丢弃当前消息，不会等 logd 腾出空间。写入端丢弃与 logd 缓冲区满后裁剪旧记录是两个发生位置和时间都不同的机制。

1. **写入端丢弃：**普通 main、system、radio、events、crash 等 buffer 发生 socket 背压时，liblog 遇到 `EAGAIN` 后放弃当前日志并返回。队列恢复后，liblog 可通过 event tag 1006（`liblog`）报告此前累计丢弃数量。不要对 `EAGAIN` 做无间隔重试，重试会加重拥塞。
2. **安全日志例外：**`security` 是受权限控制的特殊 buffer，liblog 为其使用不同的阻塞 socket。不能把普通 buffer 的非阻塞行为推广到所有 buffer 和所有版本。
3. **服务端裁剪：**Android 17 默认使用 serialized buffer。日志按 chunk 序列化并在封存后使用 Zstd 1 级压缩。buffer 超过目标容量时，logd 会从最旧 chunk 开始回收空间，因而被覆盖的是历史记录，不一定是刚写入的新记录。
4. **旧版 chatty 差异：**压缩方案在 Android S 起取代 chatty 重复日志折叠。旧设备看到 chatty 统计不能证明 Android S 及之后的默认实现仍按该方式去重。
5. **日志风暴的实际代价：**即使 socket 写入不等待，应用仍持续支付参数格式化、JNI 和系统调用成本。大量日志也会遮住关键现场或使旧记录更快被裁剪。logd 接收、压缩、回收和向 reader 分发日志也会增加系统负载。
6. **控制写入：**用 `adb logcat -g` 查看 logd 各 ring buffer 当前容量，不会改变容量。随后降低产生日志的频率、合并重复事件并只保留诊断必需字段，而不是一味增大 buffer。

**Q3: [learning] MyApp:V *:S 和 --regex 在哪里过滤？如何减少 logd 发送给 logcat 的日志量？**

tag/priority filter spec 和 `--regex` 都由 logcat 客户端在收到日志后执行，所以主要减少终端输出和后续文本处理，不会自动缩小 logd 已经发来的数据。要缩小传输范围，应先使用 buffer、PID、时间或序号等 logd reader 条件。

1. **客户端 tag/priority 过滤：**filter spec 的形式是 `<tag>[:priority]`。`MyApp:V` 表示对 `MyApp` 标签显示 Verbose 及以上优先级的日志。`*:S` 表示其他标签静默，因此组合后只打印 `MyApp`。这些规则在 logcat 客户端判断。
2. **客户端正则过滤：**`--regex` 在 logcat 读到并解析消息后对 message 执行 ECMAScript 正则匹配。复杂表达式会增加 logcat 客户端 CPU 开销，不是 logd 服务端匹配正则。
3. **reader 范围过滤：**`-b main` 只订阅 main buffer。`--pid=<pid>` 让 logd reader 只读取指定进程 ID 的日志。`-t` 或 `-T` 可限制读取的尾部条数或起始时间，非阻塞和 wrap 模式则影响 reader 的等待方式。
4. **输出格式：**`-v threadtime` 要求 logcat 客户端以 threadtime 格式打印时间、priority、tag、PID 和 TID。它改变输出格式，不会减少 logd 提供的记录。
5. **组合使用：**先按 buffer 和进程缩小 reader 范围，再用 tag 过滤界面输出：

    ```bash
    adb logcat -b main --pid="$(adb shell pidof -s com.example.app)" \
      -v threadtime 'MyApp:V' '*:S'
    ```

    参数分别承担以下作用：

    1. `-b main` 选择 main buffer。
    2. `adb shell pidof -s com.example.app` 在设备上查询该包的一个 PID，外层 `--pid` 把这个 PID 用作 reader 范围条件。
    3. `-v threadtime` 只改变客户端显示字段。
    4. `'MyApp:V' '*:S'` 是客户端 filter spec，只打印 MyApp 标签的日志。

多开 logcat 会建立独立 reader，增加 logd 解压和数据发送工作。若 reader 的读取位置落到已被裁剪的日志范围之后，服务端会跳过不再可用的记录并记录警告。
