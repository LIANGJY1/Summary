# aconfig：声明与构建期代码生成

> 学习资料（文章模式沉淀，证据等级：Android 官方 feature-flagging 文档与 AOSP 源码）。边界：本文回答 `.aconfig` 声明字段、Soong 接入、codegen 模板、release config 与版本核对。运行期存储和 `aflags` 归 [../11-platform-services/05-aconfig-runtime.md](../11-platform-services/05-aconfig-runtime.md)。版本相关结论以目标 Android 分支为准。Q 序列即结构，供 atlas 同源直读。

**Q1: aconfig 是什么，它和以前用 `Build.IS_BOARD_*` 之类的宏有什么本质不同？**

aconfig 是 Android 特性发布流程使用的开关基础设施，贯穿 flag 声明、构建期代码生成、版本配置和运行期取值。它与 `Build.IS_BOARD_*`、`PRODUCT_*` 等构建期配置的差异是：声明为 `READ_WRITE` 的 aconfig 标志可在运行期覆盖，构建期值则随构建确定。不是所有 aconfig 标志都能运行期修改，`READ_ONLY` 标志不可改。

```text
某一 Android 17 源码快照的 aconfig 声明文件统计：接近 500 个。
该数值随分支和统计范围变化，不是平台能力要求。
```

官方将 aconfig 用于隔离尚未发布或仍需验证的代码路径。选择机制时先判断需求：

1. 若要在测试或发布流程中控制新功能开关，评估 aconfig，并按发布阶段选择 `READ_WRITE` 或 `READ_ONLY`。
2. 若值必须在构建系统运行前可用、需要控制依赖/代码体积，或作用于预编译组件，评估 build flag。
3. 若值只表示构建目标或设备配置，且不需要功能发布流程，不要仅因它是布尔值就改成 aconfig。

所有特性发布 flag 默认 `READ_WRITE` 且 `DISABLED`。具体 release config 可以覆盖状态与权限。

**Q2: 声明一个 aconfig 标志需要哪几个必填字段，`container` 与 `package` 分别管什么？**

声明文件以 `package` 和 `container` 设定声明范围，每个 `flag` 提供 `name`、`namespace`、`description` 和 `bug`。`package` 与 `name` 组合成 flag 唯一键。AOSP 贡献规范要求这些字段按文档给出。自建镜像可按官方说明使用自己的 bug 编号或 `<none>`。

```text
有效 container：system、vendor、system_ext、product、name.of.apex、name.of.apk
```

`package` 的作用是标识 flag 并参与生成访问器。Java 侧设为 `foo.bar` 会生成 `foo.bar.Flags` 类。C++ 访问器位于对应的 `foo::bar` 命名空间。多个声明文件可以向同一 package 贡献标志。`container` 定义一起构建和交付的代码集合。是否允许所属 container 之外访问由导出属性等规则控制，不能把 `container` 本身说成完整的可见性规则。

其余字段按用途区分如下：

1. `is_fixed_read_only`：声明标志固定为只读，不允许运行期改值。
2. `is_exported`：允许访问超出其默认 container 边界的 flag 使用场景，仍须满足构建和代码生成规则。
3. `metadata.purpose`：描述功能开关或 bug 修复用途，供评审、策略或测试流程参考，不会自动改变运行期值或权限。
4. `metadata.storage`：描述运行时值后端选择。后端与模板适用条件见 Q4，运行期读取细节见配套运行时文档。
5. `type`：目标分支支持时描述 flag 值类型。Android 17 AOSP 开始出现布尔与整数类型 schema，但这不代表整数访问器链路已完整支持。

`description` 用于说明 flag 控制的功能或变更。`bug` 用于关联代码贡献或本地跟踪事项。对自建镜像而言，关联项关闭是重新评估 flag 生命周期的信号，不等于 flag 会自动删除。

**Q3: 构建期生成了什么代码，Java 侧的入口是什么样的？**

构建期由 `aconfig` 工具按语言生成访问器库。Java 侧通常从生成的 `Flags` 类调用静态 getter，具体实现由 codegen 生成的实现类和当前存储后端承接。

```java
// 生成的 Flags.java 是标志检查的主要入口
public static boolean isMyStaticFlagEnabled() { return FlagsImpl.isMyStaticFlagEnabled(); }
```

生成代码和读取 API 会因语言、container、版本及后端而异。不要把某个 Android 版本的 Java 内部类或存储路径写成全部容器通用规则。常见 Soong 模块职责如下：

1. `aconfig_declarations`：通过 `srcs`、`package` 和 `container` 把 `.aconfig` 声明接入构建。
2. `cc_aconfig_library`：生成供 C/C++ 目标链接的 flag 访问器库。
3. `rust_aconfig_library`：生成供 Rust 目标使用的访问器库。
4. Java flag 库：由对应 Soong 模块暴露生成的 `Flags` 类，应用代码调用 accessor，而不是自行解析存储文件。

```text
Android.bp 结构示意，属性值需按模块和目标分区配置：

aconfig_declarations {
    name: "example_flags",
    package: "com.example.flags",
    container: "system",
    srcs: ["flags.aconfig"],
}

cc_aconfig_library {
    name: "libexample_flags",
    aconfig_declarations: "example_flags",
}

cc_library {
    name: "example_service",
    static_libs: ["libexample_flags"],
    shared_libs: ["server_configurable_flags"],
}
```

示例中的每个构建属性承担不同职责：

1. `name`：Soong 模块标识，依赖方使用这个名字引用模块。
2. `package`：声明所属 package，必须与访问器生成所依据的声明一致。
3. `container`：标记这些声明随哪个代码集合构建和交付，示例设为 `system`。
4. `srcs`：输入声明文件路径，示例用 `flags.aconfig`。省略所需输入会导致声明未参与生成。
5. `aconfig_declarations`：访问器库引用的声明模块名，示例连接 `libexample_flags` 与 `example_flags`。
6. `static_libs`：将生成的 C/C++ 访问器库静态链接进使用方。
7. `shared_libs`：示例列出 `server_configurable_flags` 运行时依赖。目标分区及分支需要哪些依赖应按当前 codegen 和 Soong 规则核实。
8. `vendor_available`、`product_available`：当父目标需要对应变体时，生成库也需满足变体可用规则，否则可能构建失败。未显式设置时取决于模块类型默认值及分支规则。

声明写对但构建找不到生成符号时，先核对输入文件是否被 `srcs` 收录、`package` 是否匹配访问器，以及目标库是否声明了生成库和所需运行时依赖。

**Q4: 代码生成有几种模板，分别在什么条件下选用？**

Java codegen 的实现取决于只读 getter 优化、是否导出和存储后端。Android 17 AOSP 的选择逻辑可在 `codegen/java.rs` 中追踪。概念上要区分优化模式与实现模板：

1. 只读 getter 优化：开启 `optimize_read_only_getter` 时，符合条件的 getter 可内联为构建期确定的值。这是代码生成优化模式，不是第四种存储后端。
2. 导出实现：`exported` 标志在 Android 17 源码中使用 `FeatureFlagsImpl.exported.java.template`。生成器对该路径可用的后端有限制，不应将这个版本分支外推到所有版本。
3. 遗留 DeviceConfig 实现：非导出标志可在支持的配置中使用 `FeatureFlagsImpl.legacy_flag.internal.java.template` 读取 `DeviceConfig`。读取频率和缓存行为由具体模板决定，应查看目标分支生成代码后再判断性能。
4. aconfig 存储实现：新存储路径使用 `FeatureFlagsImpl.new_storage.java.template` 及容器读取 API。具体 Java 内部类、文件位置和模板名称随版本可能变化。

Android 17 源码删除了独立的 `FeatureFlagsImpl.deviceConfig.java.template`，将相关 legacy 读取逻辑并入 `legacy_flag.internal` 模板。`build/make/tools/aconfig/aconfig/templates/` 在该源码快照中有 13 个模板文件，这个文件数只代表该目录快照。若某标志读取慢，应先确认后端、生成出的 getter、缓存策略与实际调用频率，再决定改后端还是由调用方合理缓存，不能仅凭 metadata 名称断定每次读取都访问 Settings。

**Q5: 只读标志的优化是怎么做到的，为什么它对包体和性能有影响？**

对满足条件的只读标志，codegen 可生成基于构建期值的 getter。配合 `@AssumeTrueForR8` 和 release 构建中的 R8 优化，未采用的分支可能被移除，减少运行期开销和保留代码。优化充分时，`FeatureFlags`、`FeatureFlagsImpl`、`CustomFeatureFlags` 或 `FakeFeatureFlagsImpl` 等实现类可能不再保留。实际类集合取决于目标模板、优化配置和消费者，不能保证所有构建最终只留下一个 `Flags` 类。

```java
// isOptimizationEnabled() 的方法体由模板字面量决定
// {optimize_read_only_getter}：关闭时为 false，开启时为 true
// @AssumeTrueForR8 让 R8 在 release 构建里假设它返回 true，
// 于是对只读标志的那些 isFlagReadOnlyOptimized 检查可被消除
```

该优化由构建标志 `RELEASE_ACONFIG_OPTIMIZE_READ_ONLY_JAVA` 控制。需要检查其目标分支的声明、默认值和作用范围，不能假设所有产品都开启。只读适合发布版本中已确定的路径。需要运行期切换的标志若被设为只读，运行期修改命令不会改变它的值。

**Q6: 版本配置（release config）是什么，`trunk_staging` 与正式发布的区别？**

版本配置是一个目录，包含特定 Android build 的所有标志值文件（启用或禁用哪些特性）。AOSP 自带若干版本配置，位于 `WORKING_DIRECTORY/build/release/aconfig/` 下，例如 `trunk_staging`。

两者的差别主要在标志值和权限配置上：`trunk_staging` 是开发版本配置，主要使用 `READ_WRITE` 标志，让功能可在启用与禁用状态下测试。正式发布配置主要使用 `READ_ONLY` 标志，反映该发布版本采用的功能状态。

官方示例中的 `flag_value` 用 package 和 flag 名定位声明，再用 state 和 permission 指定发布值与运行期权限：

```text
flag_value {
  package: "com.example.android.aconfig.demo.flags"
  name: "my_static_flag"
  state: DISABLED
  permission: READ_WRITE
}
```

1. `package`：必须与声明文件中的 package 一致。
2. `name`：必须与声明中的 flag 名一致。
3. `state`：示例值 `DISABLED` 表示此 release config 默认关闭该 flag，可按目标配置设为 `ENABLED`。
4. `permission`：示例值 `READ_WRITE` 表示运行期允许覆盖，或按发布阶段配置为 `READ_ONLY`。

向 `trunk_staging` 加值时，还要把 package 对应的值文件接入 `Android.bp` 中的 `aconfig_values` 模块。下面结构里的 `name` 是 Soong 模块名，`package` 选择收集哪个 package 的值，`srcs` 选择输入 textproto 文件。模块名和属性写法需符合目标分支的 Soong 定义。

```text
aconfig_values {
  name: "aconfig_values_example",
  package: "com.example.android.aconfig.demo.flags",
  srcs: [ "*_flag_values.textproto" ],
}
```

示例中 `srcs` 的通配符匹配当前目录下的 flag value textproto 文件。若值文件未纳入 `srcs`，构建不会把它作为该模块的输入。

未提供覆盖值时，特性发布 flag 默认是 `DISABLED` 和 `READ_WRITE`。调试“声明后没有启用”时，分别检查是否有值文件、该文件是否在模块 `srcs` 中，以及目标构建实际选择了哪个 release config。AOSP 内置配置由 Google 维护，官方不接受通过贡献修改其特性 flag 值。自建镜像应定义自己的 release config。

**Q7: 声明里 `namespace` 字段的约束是什么，自建镜像该怎么处理？**

`namespace` 用于组织 AOSP 贡献的审核命名空间。上游贡献者需与指定的 Google reviewer 协作确定它。维护自建 AOSP 镜像时可自行选择 namespace。

字段约束按官方声明规则理解：

1. `name`：仅含小写字母、下划线和数字，不使用驼峰或连字符。
2. `namespace`：AOSP 贡献使用经审核确定的命名空间。自建镜像可自定。
3. `description`：简要说明被标记的功能或变更。
4. `bug`：关联新代码贡献的 bug。自建镜像可使用自己的跟踪号或 `<none>`。

`name` 会参与生成访问器名称，拼写不符合字符限制会造成声明或生成失败。自建镜像中的 bug 编号是否仍有效需要自行治理，关联事项关闭不会自动清理 flag。

**Q8: 这套体系跨版本会变吗，迁移时必须重新核对什么？**

会。本文核对的版本节点及变更如下，版本号用于定位行为，不表示每个产品分支同时启用了相关能力。

1. Android 14（API 34）：AOSP 开始引入 aconfig 特性 flag 流程。
2. Android 15（API 35）：AOSP 中 aconfig 流程进一步发展。具体成熟度要按分支功能核对，不作为统一能力承诺。
3. Android 17（API 37）源码快照：flag schema 开始出现 `FLAG_TYPE_BOOLEAN` 与 `FLAG_TYPE_INTEGER` 区分，整数值经 `flag_value.value_int`（proto field 5）及解析结构中的 `parsed_flag`（field 14）传递。完整整数访问器仍须按目标分支核实。
4. Android 17 源码快照：存储格式 v4 增加 `ReadWriteInt64`、`ReadOnlyInt64`、`FixedReadOnlyInt64` 三个存储类型变体，并增加 `FlagValueType` 的 `Boolean` 与 `Int64` 枚举。system 侧 `aconfigd` 实现、legacy DeviceConfig 模板也有变更。逐项核查服务实现、模板路径、文件版本及对应构建门控，而不要只依据旧分支结论。
5. `RELEASE_ACONFIG_ENABLE_INT_FLAG` 与 `RELEASE_ACONFIG_OPTIMIZE_READ_ONLY_JAVA`：这是构建配置项。存在于源码不等于产品构建已启用，默认值和生效条件都须在目标分支确认。

迁移时先核对目标分支是否有声明字段、构建门控、codegen 模板和存储格式支持。Android 17 代码中出现整数 schema，不代表旧版本接受该字段，也不代表使用方已有整数访问器。模板文件名或数量也可能变化。

**Q9: 一个标志从声明到生效，完整链路经过哪几步，哪一步最容易断？**

端到端链路可拆成八步。每一步都要在目标构建中成立，后续环节不能补偿前面缺失的声明或配置。

1. `.aconfig` 文件声明 package、container 和 flag 字段。
2. `aconfig_declarations` 模块通过 `srcs` 纳入声明，并绑定对应的 `package` 与 `container`。
3. release config 中的 flag value 文件设定 flag 状态和权限。
4. `aconfig_values` 或目标分支对应的值模块通过 `srcs` 收录这些值文件。
5. codegen 按语言生成 Java、C/C++ 或 Rust 访问器库。
6. 消费方 Soong 模块依照语言、container 和链接方式依赖相应访问器及所需运行时库。
7. 构建系统生成并安装目标设备布局所需的 aconfig 存储产物。实际文件名和目标路径由分区、container 与版本决定。
8. 设备启动时运行时服务装载相应存储，访问器再通过对应读取 API 获取有效值。

在 Android 17 的特定源码路径中，服务名可见 `aconfigd-system`，Java 访问实现可见 `AconfigPackageInternal` 或 `PlatformAconfigPackageInternal`。不要据此断言所有 Android 分支都使用相同进程、实现类或设备路径。

值文件缺失或未纳入模块时，flag 可能保留 `DISABLED` 默认值，通常不会造成编译错误。访问器库未生成或未链接时，常会在构建期暴露符号或依赖错误。排查时应按可观察边界逐层核对：目标 release config、生成访问器、链接依赖、设备实际 flag 状态和运行时存储初始化。`aflags list` 只能显示设备工具当前可见的信息，不能单独证明构建输入、代码生成和业务 getter 全部正确。
