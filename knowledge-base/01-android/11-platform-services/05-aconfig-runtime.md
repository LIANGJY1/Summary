# aconfig 运行时：存储与 aflags

> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答"标志值运行期从哪读、aconfigd 如何初始化存储、aflags 怎么查看与修改"；声明与构建期代码生成归 [../13-build-system/07-aconfig.md](../13-build-system/07-aconfig.md)。源文档：Android 官方 feature-flagging 文档与 AOSP 源码口径，版本差异已标注。Q 序列即结构，供 atlas 同源直读。

**Q1: `purpose` 与 `storage` 两个 metadata 字段各自影响什么？**

`purpose` 区分"用开关门控新功能"与"用开关门控正确性修复"，这决定了开关被关闭时代码的期望行为；`storage` 选择运行期的值来源后端，共两种。

两种后端：新的基于 `ACONFIGD` 的内存映射存储，以及遗留的 `DEVICE_CONFIG`（Settings 存储）。新存储是为了解决 DeviceConfig 方案的性能与开机时延问题而引入的。旧路径每次调用都单独 `DeviceConfig.getBoolean()` 读、无缓存；更早还有一版按 namespace 批量 `getProperties()` 读的模板，在 Android 17 被移除。`purpose` 字段的存在意义是让"关掉这个开关是否安全"变成可判定问题——门控新功能时关闭是安全的（回到旧行为），门控正确性修复时关闭可能让已知缺陷重新暴露。判断规则：给一个已有 bug 的修复加开关时要谨慎——用 `purpose` 标记为 bugfix 的开关一旦被误关，缺陷立即复现；这类开关应当额外考虑设 `is_fixed_read_only` 以确保它不会在生产设备上被关掉。

**Q2: 运行期存储由谁提供，Android 17 上有什么变化？**

由 `aconfigd-system` 服务在开机阶段初始化存储。存储文件在构建期由 `aconfig create-storage` 生成，共四种二进制文件类型。Android 17 的显著变化是 `aconfigd-system` 成为纯 Rust 二进制（`system/server_configurable_flags/aconfigd/Android.bp` 里的 `rust_binary` Soong 模块），此前用于灰度迁移的 `enable_full_rust_system_aconfigd` 迁移标志已被移除。

值以内存映射文件形式落在 `/metadata/aconfig/` 下。Android 17 的版本 4 格式给 `StoredFlagType` 枚举增加了三个整数对应项——`ReadWriteInt64`、`ReadOnlyInt64`、`FixedReadOnlyInt64`，并新增 `FlagValueType` 枚举（`Boolean`、`Int64`）来分类值的存储方式；这些变体仅在启用 v4 解析器时使用。判断规则：开机阶段读到的标志值不对，先确认存储文件是否已生成且 `/metadata/aconfig/` 是否可读——`aconfigd` 尚未初始化完时读到的是默认值，这与"标志被关掉"的表现完全一致，但根因完全不同。

**Q3: 整数标志是什么，为什么说它目前还是 groundwork？**

Android 17 在声明 schema 里新增了标志类型维度（`FLAG_TYPE_BOOLEAN` 与 `FLAG_TYPE_INTEGER`），使标志能携带整数负载而不只是开关状态。整数值的传递链路已经铺好——值由 `flag_value` 的 `value_int` 字段（field 5）与 `parsed_flag`（field 14）承载，而不是布尔型的 `state`。

但完整链路尚未打通：声明整数标志受构建标志 `RELEASE_ACONFIG_ENABLE_INT_FLAG` 门控，且**整数标志的访问器代码生成尚未接线**。官方文档也说明"此为奠基工作"。判断规则：现阶段不要依赖整数标志做生产功能——底层存储与解析虽已就绪，但访问器缺失意味着 C++/Java 侧拿不到值；要用只能走 `aflags` 命令行或等待版本推进。这与布尔标志"声明即可用"的成熟度有本质差距。

**Q4: `aflags` 是什么，怎么在设备上查看和修改标志值？**

`aflags` 是设备端的标志查看与操作工具。设备上的 `aflags` 是一层薄壳，把实际子命令逻辑委托给 ConfigInfrastructure APEX 中可更新的 `aflags_updatable` 二进制。

```bash
# 源码位置
build/make/tools/aconfig/aflags/src/main.rs            ← 设备端薄壳
packages/modules/ConfigInfrastructure/aflags/src/main.rs ← 实际子命令逻辑

# 基本操作
aflags list
aflags enable <package>.<name>
aflags disable <package>.<name>
aflags unset <package>.<name>
```

`enable`、`disable`、`unset` 三个子命令都接受 `-i`/`--immediate` 参数。Android 17 新增两项列举能力：`aflags list --format proto` 输出 Base64 编码的 `ProtoFlagList`（受 `android.provider.flags.aflags_list_proto` 标志门控）；当 `aflags_list_mainline_beta` 标志置位时，`aflags list` 还会合并从 `device_config` 存储读取的 Mainline Beta 标志。判断规则：现场调试时优先用 `aflags list` 确认标志当前实际值，而不是看代码里的默认值或构建配置——尤其是走 DeviceConfig 后端的标志，它的值可能已被其他组件改动过。

**Q5: 在自建镜像上维护这样一套开关，主要的维护负担是什么？**

主要负担是"开关的清理"和"运行期可调带来的现场不确定性"，这两项都不是技术问题而是流程问题。

清理负担来自两处：其一，`bug` 字段指向的跟踪项在自建镜像上用自己的编号，不会因为上游关闭而失效，所以过期开关不会自动暴露；其二，`READ_WRITE` 标志可以在生产设备上被改值，于是"代码里写了默认值"不再等于"设备上就是这个值"，现场问题复现时必须先记录当时的 `aflags list` 输出。代价还有一条：`setClickFastWithRebound` 这类带副作用的标志在 `thunk_staging` 上可被反复开关，等于把"代码在开关两侧都正确"变成了硬性要求。判断规则：给自建镜像定一条规矩——每个 `READ_WRITE` 标志都要有明确的清理责任人与触发条件（通常是上游合入或目标特性全量后），并把它挂进需求闭环清单；否则开关数量只会单调增长，维护成本持续上升而收益递减。
