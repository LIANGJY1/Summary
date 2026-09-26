# Aconfig 特性开关：声明、生成代码与运行时存储

> 学习资料（文章模式沉淀，**证据等级：二手**）。主线：Android 14 引入、Android 15 成熟、Android 17 铺开的统一特性开关体系——声明如何用 `.aconfig` 文件与 container 划分归属、构建期如何生成各语言的访问器、运行期值从内存映射存储还是 DeviceConfig 读、以及只读标志如何在 release 构建里被 R8 优化掉。源文档：Android 官方《声明 aconfig 标志》《设置功能发布标志值》文档（`source.android.com/docs/setup/build/feature-flagging/`）、AOSP Internals 第 3 章 Feature Flags (aconfig)、AOSP `build/soong` 的 `aconfig/aconfig_declarations.go` 源码。**本册结论未逐条核对本地 AOSP 源码**，字段名以官方文档与源码为准；版本差异已标注。已有各册未覆盖 aconfig 体系，本册为新增。Q 序列即结构，供 atlas 同源直读。

**Q1: aconfig 是什么，它和以前用 `Build.IS_BOARD_*` 之类的宏有什么本质不同？**

aconfig 是 Android 14（API 34）引入、Android 15（API 35）显著成熟、到 Android 17 全面铺开的统一特性开关基础设施，同时覆盖构建策略、运行期配置、代码生成与测试四层。它与旧宏的本质差别在于**开关的值在运行期仍可改**，而不只是编译期常量。

```text
Android 17 的 aconfig 规模：frameworks、system services、HAL、
Mainline 模块、vendor 分区中接近 500 个 .aconfig 声明文件
```

旧的 `Build.IS_BOARD_*`、`PRODUCT_*` 系列是构建期常量，编进 `Build` 类后运行期不可变；aconfig 的 `READ_WRITE` 标志可以通过 `aflags` 工具在设备上改值，无需重新编译。官方文档给出的默认状态是：所有特性开关默认为 `READ_WRITE` 且 `DISABLED`。判断规则：判断某个开关该用哪种机制，先问"这个值是否需要在不重新编译的前提下按设备/按用户调整"——需要就是 aconfig 的 `READ_WRITE`，不需要就该用构建期常量；把构建期常量当特性开关用会导致灰度只能靠刷机，运营成本极高。

**Q2: 声明一个 aconfig 标志需要哪几个必填字段，`container` 与 `package` 分别管什么？**

`name`、`namespace`、`description`、`bug` 四个是必填；`container` 决定标志归属于哪个一起构建交付的二进制集合，`package` 与标志名组合成唯一键。

```text
合法 container：system、vendor、system_ext、product、name.of.apex、name.of.apk
```

`package` 的作用是生成访问器的命名空间：Java 侧设为 `foo.bar` 会生成 `foo.bar.Flags` 类；C++ 侧访问器方法名会是 `foo::bar::"flagname"`。同一声明文件里的标志属于同一 package，但多个声明文件可以向同一 package 贡献标志——这是官方文档明确支持的扩展方式。`container` 则划定可见性边界：标志默认只能在所属 container 内访问，需要跨 container 才要显式导出。

其余可选字段：

- `is_fixed_read_only`：置真则权限被强制为 `READ_ONLY`，运行期永不可改。
- `is_exported`：置真则标志可在所属 container 之外访问。
- `metadata`：携带 `purpose`（区分"功能开关"与"修 bug 的开关"）与 `storage`（选择运行期后端）。
- `type`：标志值类型，Android 17 新增 `FLAG_TYPE_BOOLEAN` 与 `FLAG_TYPE_INTEGER` 的区分，缺省按布尔处理。

判断规则：`description` 与 `bug` 虽然是元数据，但它们是这套体系可维护性的基础——没有 `bug` 字段就无法回答"这个开关为什么还在"，没有 `description` 就无法在 `aflags list` 输出里判断用途。判断一个标志该不该留，第一步就是看它的 `bug` 指向的问题是否早已关闭。

**Q3: `purpose` 与 `storage` 两个 metadata 字段各自影响什么？**

`purpose` 区分"用开关门控新功能"与"用开关门控正确性修复"，这决定了开关被关闭时代码的期望行为；`storage` 选择运行期的值来源后端，共两种。

两种后端：新的基于 `ACONFIGD` 的内存映射存储，以及遗留的 `DEVICE_CONFIG`（Settings 存储）。新存储是为了解决 DeviceConfig 方案的性能与开机时延问题而引入的。旧路径每次调用都单独 `DeviceConfig.getBoolean()` 读、无缓存；更早还有一版按 namespace 批量 `getProperties()` 读的模板，在 Android 17 被移除。`purpose` 字段的存在意义是让"关掉这个开关是否安全"变成可判定问题——门控新功能时关闭是安全的（回到旧行为），门控正确性修复时关闭可能让已知缺陷重新暴露。判断规则：给一个已有 bug 的修复加开关时要谨慎——用 `purpose` 标记为 bugfix 的开关一旦被误关，缺陷立即复现；这类开关应当额外考虑设 `is_fixed_read_only` 以确保它不会在生产设备上被关掉。

**Q4: 构建期生成了什么代码，Java 侧的入口是什么样的？**

构建期由 `aconfig` 工具按语言分别生成访问器库。Java 侧的主入口是生成的 `Flags` 类，它提供静态方法委托给内部的 `FeatureFlags` 实现。

```java
// 生成的 Flags.java 是标志检查的主要入口
public static boolean isMyStaticFlagEnabled() { return FlagsImpl.isMyStaticFlagEnabled(); }
```

平台 container（`system`、`system_ext`、`product`、`vendor`）用 `PlatformAconfigPackageInternal` 实现；非平台 container（APEX 模块）用 `AconfigPackageInternal`，两者都从 `/metadata/aconfig/` 下的内存映射存储文件读值。生成的库通过 Soong 模块接入：`cc_aconfig_library`（C/C++ codegen）、`rust_aconfig_library`（Rust codegen），声明侧用 `aconfig_declarations`。

```text
Android.bp 侧
aconfig_declarations { srcs, package, container }   → 声明
cc_aconfig_library  { name, vendor_available, ... }  → 访问器库
使用：cc_library { static_libs: "<cc_aconfig_library 名>",
                   shared_libs: "server_configurable_flags" }
```

注意一个易漏点：把生成库作为 `static_lib` 引入时，必须同时给宿主库加 `shared_libs: "server_configurable_flags"` 依赖。`vendor_available`、`product_available` 这类属性若父目标设了 `true`，`cc_aconfig_library` 上也要设对应值，否则会出现变体缺失导致的构建中断。判断规则：开关声明写对了但构建报错找不到生成的符号，先查三处——`aconfig_declarations` 的 `srcs` 是否覆盖了声明文件、`package` 是否与访问器引用一致、以 `static_libs` 引入时 `server_configurable_flags` 的 `shared_libs` 依赖是否加了。

**Q5: 代码生成有几种模板，分别在什么条件下选用？**

Java codegen 按"代码生成模式 × 是否导出 × 存储后端"三维度从四个模板里选，Android 17 的选择逻辑在 `codegen/java.rs` 的 `add_feature_flags_impl_template` 里。

```text
1. 优化掉的只读 getter（optimize_read_only_getter）→ 生成体直接返回默认值
2. 导出库（exported）           → FeatureFlagsImpl.exported.java.template
                                  导出 codegen 始终用新存储；生成器断言
                                  exported 标志不使用 DeviceConfig 后端
3. DeviceConfig 后端（非导出）   → FeatureFlagsImpl.legacy_flag.internal.java.template
                                  逐个 DeviceConfig.getBoolean() 读
4. 新的 aconfigd 存储（默认非导出）→ FeatureFlagsImpl.new_storage.java.template
                                  经 PlatformAconfigPackageInternal /
                                  AconfigPackageInternal 读内存映射文件
```

Android 17 的两个变化值得记住：原先独立的 `FeatureFlagsImpl.deviceConfig.java.template` 已被删除，DeviceConfig 运行时读取折进 `legacy_flag.internal` 模板；`build/make/tools/aconfig/aconfig/templates/` 下的完整模板清单为 13 个文件。判断规则：看到"某个 aconfig 标志读取特别慢"且它配置了 `storage` 为 DeviceConfig，那是逐次读取无缓存的必然结果——要么改用默认的 aconfigd 存储后端，要么在业务侧自行加缓存，而不是在框架侧想办法。

**Q6: 只读标志的优化是怎么做到的，为什么它对包体和性能有影响？**

只读标志的 getter 在 release 构建里直接返回编译期确定的默认值，`@AssumeTrueForR8` 注解进一步让 R8 假设方法返回 `true`，从而在 release 构建中把 `isFlagReadOnlyOptimized` 检查整体优化掉。优化彻底时，生成包里可以只保留一个 `Flags` 类——`FeatureFlags`、`FeatureFlagsImpl`、`CustomFeatureFlags`、`FakeFeatureFlagsImpl` 都能被删掉。

```java
// isOptimizationEnabled() 的方法体由模板字面量决定
// {optimize_read_only_getter}：关闭时为 false，开启时为 true
// @AssumeTrueForR8 让 R8 在 release 构建里假设它返回 true，
// 于是对只读标志的那些 isFlagReadOnlyOptimized 检查可被消除
```

该优化由构建标志 `RELEASE_ACONFIG_OPTIMIZE_READ_ONLY_JAVA` 控制。判断规则：只读标志适合"这个分支在本次发布中确定要走向"的场景——它换来的是死代码消除与零运行期开销；反之，误把需要运行期可调的标志设成只读，会得到"改了配置但完全没生效"的静默失效，比报错更难查。

**Q7: 运行期存储由谁提供，Android 17 上有什么变化？**

由 `aconfigd-system` 服务在开机阶段初始化存储。存储文件在构建期由 `aconfig create-storage` 生成，共四种二进制文件类型。Android 17 的显著变化是 `aconfigd-system` 成为纯 Rust 二进制（`system/server_configurable_flags/aconfigd/Android.bp` 里的 `rust_binary` Soong 模块），此前用于灰度迁移的 `enable_full_rust_system_aconfigd` 迁移标志已被移除。

值以内存映射文件形式落在 `/metadata/aconfig/` 下。Android 17 的版本 4 格式给 `StoredFlagType` 枚举增加了三个整数对应项——`ReadWriteInt64`、`ReadOnlyInt64`、`FixedReadOnlyInt64`，并新增 `FlagValueType` 枚举（`Boolean`、`Int64`）来分类值的存储方式；这些变体仅在启用 v4 解析器时使用。判断规则：开机阶段读到的标志值不对，先确认存储文件是否已生成且 `/metadata/aconfig/` 是否可读——`aconfigd` 尚未初始化完时读到的是默认值，这与"标志被关掉"的表现完全一致，但根因完全不同。

**Q8: 整数标志是什么，为什么说它目前还是 groundwork？**

Android 17 在声明 schema 里新增了标志类型维度（`FLAG_TYPE_BOOLEAN` 与 `FLAG_TYPE_INTEGER`），使标志能携带整数负载而不只是开关状态。整数值的传递链路已经铺好——值由 `flag_value` 的 `value_int` 字段（field 5）与 `parsed_flag`（field 14）承载，而不是布尔型的 `state`。

但完整链路尚未打通：声明整数标志受构建标志 `RELEASE_ACONFIG_ENABLE_INT_FLAG` 门控，且**整数标志的访问器代码生成尚未接线**。官方文档也说明"此为奠基工作"。判断规则：现阶段不要依赖整数标志做生产功能——底层存储与解析虽已就绪，但访问器缺失意味着 C++/Java 侧拿不到值；要用只能走 `aflags` 命令行或等待版本推进。这与布尔标志"声明即可用"的成熟度有本质差距。

**Q9: `aflags` 是什么，怎么在设备上查看和修改标志值？**

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

**Q10: 版本配置（release config）是什么，`trunk_staging` 与正式发布的区别？**

版本配置是一个目录，包含特定 Android build 的所有标志值文件（启用或禁用哪些特性）。AOSP 自带若干版本配置，位于 `WORKING_DIRECTORY/build/release/aconfig/` 下，例如 `trunk_staging`。

两者的关键差别在权限配置上：`trunk_staging` 是一种**开发**版本配置，Google 在正式发布前用它测试功能，主要使用 `READ_WRITE` 标志，允许在启用/禁用状态下运行期测试代码。正式发布时使用**发布**版本配置，主要使用 `READ_ONLY` 标志，反映该版本已启用的全部代码。

向 `trunk_staging` 添加标志的步骤是固定的：进 `build/release/aconfig/trunk_staging/`，建一个与标志 package 同名的目录，在其中建标志值文件并添加 `flag_value` 块，然后在同目录建 `Android.bp` 声明 `aconfig_values`，最后把该文件加进 `srcs` 列表。

```text
flag_value {
  package: "com.example.android.aconfig.demo.flags"
  name: "my_static_flag"
  state: DISABLED
  Permission: READ_WRITE
}
```

```text
aconfig_values {
  name: "aconfig-values-platform_build_release-trunk-staging-com.android.aconfig.test-all",
  package: "com.android.aconfig.test",
  srcs: [ "*_flag_values.textproto" ],
}
```

判断规则：标志"加了不生效"最常见的原因是只写了声明没写值文件——所有标志默认 `DISABLED`，必须在版本配置里显式给值。另一个常见原因是值文件加了但没加进 `Android.bp` 的 `srcs`；两处都要动。官方还明确说明：Google 不接受用于更新版本配置的特性开关贡献，自建镜像应定义自己的版本配置而非改动 AOSP 的。

**Q11: 声明里 `namespace` 字段的约束是什么，自建镜像该怎么处理？**

`namespace` 用于组织命名空间，必须与指定的 Google 审核人员协作确定；但如果用特性开关来维护自建 AOSP 镜像的稳定性，则可以自由使用命名空间。

```text
name        仅含小写字母、下划线和数字
namespace   贡献的命名空间；维护 AOSP 镜像稳定性需与 Google 审核人协作，
            自建镜像则可自由使用
description 被标记的特性或变更的简要说明
bug         与新贡献关联的 bug 编号；维护 AOSP 镜像可用自己的跟踪号
```

判断规则：`name` 的字符集限制（小写字母、下划线、数字）意味着不能用驼峰或连字符命名标志——这会直接影响生成的访问器方法名，写错会在编译期才暴露。`bug` 字段在 AOSP 上是必填且需真实存在，但自建镜像可以用自己的跟踪号，这意味着**不能把 `bug` 当作强制清理开关的依据**——自建镜像里指向已关闭跟踪项的开关同样需要定期清理。

**Q12: 这套体系跨版本会变吗，迁移时必须重新核对什么？**

会，而且变化点集中且明确。Android 14 引入、15 显著成熟、17 有多处结构性变化。

```text
Android 14 (API 34)  aconfig 体系引入
Android 15 (API 35)  体系显著成熟
Android 17 (API 37)  近 500 个 .aconfig 声明文件铺开
                    type 字段（FLAG_TYPE_BOOLEAN / FLAG_TYPE_INTEGER）加入声明 schema
                    values 走 flag_value.value_int（field 5）/ parsed_flag（field 14）
                    aconfigd-system 改为纯 Rust 二进制，迁移标志已移除
                    FeatureFlagsImpl.deviceConfig.java.template 被删除
                    存储格式 v4 增加三个整数变体与 FlagValueType 枚举
                    受控于 RELEASE_ACONFIG_ENABLE_INT_FLAG、
                    RELEASE_ACONFIG_OPTIMIZE_READ_ONLY_JAVA
```

Android 17 之前每个标志都隐式是布尔的——这意味着在 16 及更早版本上，`type` 字段根本不存在，整数标志无从声明。判断规则：跨版本迁移这套开关时，先确认目标版本是否支持所用的字段与构建标志，再确认生成模板是否还是同一套；沿用 16 的认知去改 17 的 `.aconfig`（比如以为可以用整数负载、或以为 DeviceConfig 模板独立存在）会直接踩空。

**Q13: 一个标志从声明到生效，完整链路经过哪几步，哪一步最容易断？**

链路是"声明 → 声明模块纳入构建 → 值文件进版本配置 → 生成访问器库 → 宿主链接该库 → 存储文件生成 → `aconfigd` 开机初始化 → 访问器读到值"。七步里最容易断的是第 3 步和第 4 步，因为它们都不产生运行时错误。

```text
① .aconfig 声明文件（name/namespace/description/bug 必填，container 划边界）
② Android.bp 里 aconfig_declarations { srcs, package, container }
③ 值文件进 build/release/aconfig/<config>/<package>/ 并被 aconfig_values 的 srcs 收录
④ codegen 生成 Flags.java / cc_aconfig_library / rust_aconfig_library
⑤ 宿主库引用：Java 直接调 Flags；C/C++ 走 static_libs + shared_libs
⑥ 构建期 aconfig create-storage 生成 /metadata/aconfig/ 下的存储文件
⑦ 开机 aconfigd-system 初始化存储，访问器经 AconfigPackageInternal 读到值
```

第 3 步断裂的表现是"标志永远是默认值"且无任何报错——因为默认值就是 `DISABLED`，而没写值文件与写了 `DISABLED` 行为完全一致。第 4 步断裂的表现是编译期符号找不到，相对容易发现。判断规则：按链路自底向上排查——先用 `aflags list` 确认值是否真的被设进去了（区分第 3 步与后面的问题），再确认访问器能否读到（区分第 7 步与第 4 步）；不要从业务逻辑开始怀疑，因为链路前两段失败时业务逻辑根本不会被执行到。

**Q14: 在自建镜像上维护这样一套开关，主要的维护负担是什么？**

主要负担是"开关的清理"和"运行期可调带来的现场不确定性"，这两项都不是技术问题而是流程问题。

清理负担来自两处：其一，`bug` 字段指向的跟踪项在自建镜像上用自己的编号，不会因为上游关闭而失效，所以过期开关不会自动暴露；其二，`READ_WRITE` 标志可以在生产设备上被改值，于是"代码里写了默认值"不再等于"设备上就是这个值"，现场问题复现时必须先记录当时的 `aflags list` 输出。代价还有一条：`setClickFastWithRebound` 这类带副作用的标志在 `thunk_staging` 上可被反复开关，等于把"代码在开关两侧都正确"变成了硬性要求。判断规则：给自建镜像定一条规矩——每个 `READ_WRITE` 标志都要有明确的清理责任人与触发条件（通常是上游合入或目标特性全量后），并把它挂进需求闭环清单；否则开关数量只会单调增长，维护成本持续上升而收益递减。
