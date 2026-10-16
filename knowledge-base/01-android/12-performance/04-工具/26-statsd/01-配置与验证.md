# statsd 配置与验证

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q31 -->

**Q1: [learning] statsd 在系统观测里处在什么位置？pushed atom 与 pulled atom 有什么区别？**

statsd 是平台侧的指标守护进程：接收 atom（用 protobuf schema 描述的结构化事件或状态记录），按 `StatsdConfig` 配置聚合成 metric 报告。它不录制逐线程、逐调度事件的时序——“某类事件是否发生、发生多少次、按 UID 或包名切分后的分布”归 statsd，“这段时间内事件按什么顺序发生”归 Perfetto。Statsd 模块自 Android 11 起以 `com.android.os.statsd` APEX 分发，Android 12 起源码迁移到 `packages/modules/StatsD`（材料口径。

本地 AAOS13 树不含该模块源码，仅 `system/core/libstats` 有日志 socket 接入层，属部分核对）。atom 分两类：pushed atom 由系统服务在事件发生时经 `libstatssocket` 写入（如 `ANROccurred`、`LmkKillOccurred`、`AppStartOccurred`、`WakelockStateChanged`、`ScheduledJobStateChanged`）。

pulled atom 由 statsd 按配置或命令向系统数据源拉取当前状态（如 `KernelWakelock`、`CpuTimePerUid`、`ProcessMemoryState`），pulled 区间的 field number（同时是 atom ID）从 10000 开始（材料口径，本地无 `atoms.proto` 无法直接核对）。statsd 过载时发送端可能收到 `EAGAIN`、写入丢失但不阻塞并维护 drop 计数——所以报告缺条目也不能单独证明事件未发生。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q32 -->

**Q2: [learning] statsd 配置里的 AtomMatcher、Predicate 与各类 metric 各管什么？报告里缺条目按什么顺序排查？**

配置层不关心事件怎么产生，只关心“哪些 atom 算命中、怎么聚合”：

- `AtomMatcher`：按 atom ID 和字段条件匹配事件。
- `Predicate`：用 start/stop atom 表示条件区间（如只统计前台期间）。
- `EventMetric`：保留命中事件明细。
- `CountMetric`：按时间 bucket 计数。
- `DurationMetric`：统计状态持续时长；`SUM` 累加区间，`MAX_SPARSE` 保留已结束区间的最大值。
- `GaugeMetric`：对 pulled atom 或触发事件做周期采样。
- `ValueMetric`/`KllMetric`：聚合数值字段，KLL 用分位数摘要估计分布。

报告缺条目时按数据流顺序排查：

1. `allowed_log_source` 是否放行了写入来源。
2. matcher/predicate 是否真的命中。
3. metric activation 是否已启用。
4. 当前未结束的 bucket 是否被包含。
5. 报告是否已被导出并清除。
6. pull 是否超时。
7. socket 或队列是否发生丢失。

atom 与 metric 要分开看：报告里有条目只证明对应 atom 到达 statsd、命中配置并进入报告，它不能替代 ANR trace、tombstone、Perfetto trace 或 dumpsys 现场。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q33 -->

**Q3: [learning] 本地用 cmd stats 验证一份 statsd 配置的流程是什么？为什么这套命令不能进量产自动化？**

最小流程按配置生命周期执行（材料按 Android 17 源码口径转写，本地树无 statsd 模块源码）：

1. **编码：**用与目标版本匹配的 `statsd_config.proto` 将 textproto 编成 wire-encoded protobuf；`protoc --encode=android.os.statsd.StatsdConfig` 选择消息类型，省略 `--encode` 不会输出供 statsd 使用的二进制配置。
2. **下发：**`adb shell cmd stats config update 123456 < config.pb` 从 stdin 读取二进制配置并以 int64 ID `123456` 登记；省略 UID 时使用调用方 UID，跨 UID 操作只对 eng/userdebug 开放。此处 ID 是本次调试选择的示例值，应与后续命令一致。
3. **验证：**触发场景后用 `adb shell cmd stats dump-report 123456 --keep_data --include_current_bucket --proto` 拉取报告。`--keep_data` 避免默认导出即清除，`--include_current_bucket` 包含未结束 bucket，`--proto` 保留 protobuf 格式；省略这些选项会分别改变保存状态、可见时间窗与输出格式。
4. **清理：**`cmd stats config remove 123456` 移除本次配置；这里不能省略 ID，否则可能操作比预期更广的配置集合。

辅助命令：`print-uid-map` 查 UID 到包名映射，`pull-source <id>` 验证某个 puller 是否工作（它不创建 metric、不证明周期采集成功），`print-stats` 打印 statsd 自身统计，`print-logs` 需要 root。不能进量产的原因在权限模型：`cmd stats` 的 shell 入口只允许 root/shell UID。

`StatsManager` 是 `@SystemApi`，`addConfig()`/`getReports()` 要求 `DUMP` + `PACKAGE_USAGE_STATS` 特权，量产环境的配置与上报由系统镜像、GMS 或厂商机制控制，普通 App 无法下发平台级配置。
