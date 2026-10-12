# Android 应用包体优化

**Q1: [learning] 体积治理要先固定哪四种口径？为什么“下载少 1 MB，安装空间未必同步少 1 MB”？**

四种口径分别回答不同问题：

1. **上传制品：**APK / AAB 本身的文件大小。
2. **设备交付集合：**固定 ABI、语言、密度与模块后，设备实际拿到哪些 split。
3. **安装占用：**已装 APK、提取的原生库、ART 编译产物与应用数据占用。
4. **运行时映射：**DEX、SO 与资源的文件页、私有脏页和共享页如何进入进程。下载减少不等于安装同步减少：未压缩条目让 APK 文件变大，却可能免去安装期提取并支持直接映射。`.vdex`/`.odex`/`.art` 是安装与运行期产物，占设备存储但不计入商店下载字节。磁盘文件大小也推不出 PSS。

APK 结构上要防误读：`classes*.dex` 保存字节码。`resources.arsc` 与 `res/` 是编译资源，`assets/` 按原始文件接口读取，静态缩减器不能仅凭代码判断 asset 是否仍被使用。`lib/<abi>/` 保存各 ABI 的 ELF。`META-INF/` 有 V1 签名产物不代表只用了 V1——V2/V3 签名位于 APK Signing Block。对比基线必须用同一发布变体、构建工具、签名流程和设备规格，每次只改一类变量，用 APK Analyzer 先按增长目录（DEX、资源还是 lib/）选择后续路径。

**Q2: [learning] “64K 限制”到底限制什么？Defined Methods 与 Referenced Methods 差在哪？**

限制的是单个 DEX 的 `method_ids` 引用表（65,536 项，`field_ids` 同级有 16 位上限）。计数包含应用代码、依赖和该 DEX 引用的 Android/Java 平台方法，不等于源码中声明的方法数。APK Analyzer 里 Defined Methods 只统计本 DEX 定义的方法，Referenced Methods 统计该 DEX 的方法 ID 表——定义在其他 DEX 或平台中的方法仍占当前 DEX 的引用条目，所以决定是否触发上限的是 Referenced。

65,536 解释了为什么需要 multidex，却不能充当体积预算：两个版本的引用数相同，方法体、字符串与调试信息差异仍可能让字节数相差很大。反之引用数下降也可能伴随 DEX 变大。索引区的增长常由依赖、生成代码和宽泛 keep 规则推动——即使方法体很短，一个方法仍会增加 method、type、proto、string 多处数据。

**Q3: [learning] R8 的 keep 规则为什么容易膨胀？怎么找到“是谁保留了这段代码”并安全放宽？**

R8 的三类收益（裁剪不可达代码、优化改写、缩短名称）都受 keep 配置约束：一条裸 `-keep` 同时阻止 shrinking、obfuscation 和 optimization。AGP 8.0 起 R8 Full Mode 已是默认，旧项目里的 `android.enableR8.fullMode=false` 兼容开关要删除才恢复全模式。规则膨胀的典型来源是包级 `-keep class com.example.** { *; }` 止崩后长期保留、`@Keep` 整类标记，以及依赖 AAR 携带的 consumer rules——“应用自己的 proguard-rules.pro 很干净”不能证明 keep 配置健康。

治理方法：规则只保护运行时协议真正需要的部分，先回答反射、JNI、序列化或 WebView bridge 访问的是类、成员、名称、签名还是注解。WebView bridge 已由静态代码创建时用 `-keepclassmembers,allowoptimization` 只保带注解的方法、允许优化方法体，而不是保留整个包。定位工具：`-printconfiguration` 输出合并配置，`-whyareyoukeeping` 追踪意外存活类（只用于本地诊断分支），配合产物中的 configuration.txt、seeds.txt、usage.txt 与 mapping.txt 归因。AGP 9.3 起的 R8 Configuration Analyzer（外部工具链口径）把最终配置量化成 Shrinking/Optimization/Obfuscation 三个分数并给出 Blast Radius 表，可先于完整构建定位宽规则。放宽前必须补动态入口测试、分批发布与线上崩溃监控。mapping.txt 随发布归档，否则线上混淆栈无法还原。

**Q4: [learning] minSdk ≥ 21 的应用还需要 multidex 兼容处理吗？DEX 个数多会拖慢启动吗？**

Android 5.0 / API 21 起 ART 原生加载 APK 内的 `classes2.dex` 等多个 DEX，minSdk ≥ 21 不需要 `androidx.multidex` 安装器，也不需要为类可见性维护旧版主 DEX 类清单——那是 Dalvik 时代为安装器在加载次级 DEX 前保证类可见的方案（minSdk ≤ 20 才需要，且 `MultiDex.install()` 完成前只能可靠访问主 DEX）。这个历史边界不应套到现代应用。

DEX 个数不是启动耗时公式：多 DEX 有文件头、索引与对齐等结构成本，但启动开销取决于启动路径触达的类与方法、它们的布局局部性、Baseline/Startup Profile 是否匹配当前二进制、有无可用 VDEX/OAT/App Image 以及存储速度与缺页。把 `classes3.dex` 合回 `classes2.dex` 不保证启动变快，为减少文件数而加 keep 或打乱布局可能更差。安装后的 `.vdex`/`.odex`/`.art` 占设备存储、不计入商店下载。Startup Profile 由 R8 调整 DEX 布局让启动类集中到首个 DEX（机制与 AGP 版本边界见 cpu-power 邻居文档），验证目标是启动类是否进入 `classes.dex` 与 TTID/TTFD，而不是某个单文件的大小。

**Q5: [learning] Native SO 的 strip 与节区分析怎么做？哪些字节删了会出问题？**

AGP 默认对发布库执行 strip：完整符号表与 DWARF 调试信息（`.debug_*`）移入独立符号制品（`SYMBOL_TABLE` 可还原函数名，`FULL` 还原到源文件与行号），strip 后的 `.so` 进包，两者必须来自同一次链接（重新链接会改变地址布局与 Build ID）。节区语义决定删什么安全：`.text`/`.rodata` 占文件字节且只读映射。`.data` 同时占文件与进程私有脏页。`.bss` 是 `SHT_NOBITS`，占运行内存但不占同量文件字节，`llvm-size` 的大 BSS 值不能当 APK 增长。`.dynsym`/`.dynstr` 与必要重定位是可运行 ELF 的一部分，`--strip-all` 也不能删。`.eh_frame`/`.ARM.exidx` 等栈展开元数据删除前必须用受控 Native 崩溃验证 tombstone、C++ 异常与 profiler 回溯仍工作。

比 strip 更有收益的是让不可达代码消失和缩小导出面：`-ffunction-sections`/`-fdata-sections` 加链接期 `--gc-sections` 删除不可达节区（这是链接期 GC，不是运行时内存回收）。`-fvisibility=hidden` 与 version script 控制符号可见性，JNI 库从 `JNI_OnLoad()` 调 `RegisterNatives()` 后常见公开入口只剩 `JNI_OnLoad`。LTO/ThinLTO 与 ICF 要保存 linker map、节区增量与性能数据评估，ICF 激进模式可能合并地址相同的函数，影响依赖函数地址唯一性的逻辑。多 AAR 同名同路径 `.so` 冲突时 `packaging.jniLibs.pickFirsts` 只选构建系统遇到的第一份——不比较 SHA-256、build ID 与 SONAME，只有在证明候选可互换后才能用。

**Q6: [learning] 安装期系统怎么为一个包选择 ABI？**

按 AAOS13 源码核对，`PackageAbiHelperImpl.derivePackageAbi()` 按包是否 multi-arch、设备支持的 ABI 顺序与是否提取原生库选择分支。需要提取时调用 `NativeLibraryHelper.copyNativeBinariesForSupportedAbi()`，其内部 `findSupportedAbi()` 选择设备 ABI 列表中排名最靠前的匹配项。`extractNativeLibs=false` 时仍选择并校验 ABI 但不复制 `.so`——未压缩且满足对齐的库可直接从 APK 映射，代价是 APK 可能因不压缩而变大，报告要同时记录下载量与安装占用。

**Q7: [learning] Android 15 的 16 KB 页设备上，Native 库要满足哪两层对齐？**

16 KB 是两层独立对齐：ELF 内每个 `PT_LOAD` 的 `p_align` 要支持 16 KB，且未压缩 `.so` 在 APK ZIP 中的起始偏移按 16 KB 对齐。NDK r28+ 默认生成对齐 ELF，r27 及以下要显式传 `-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384`，AGP 8.5.1+ 处理未压缩库的 ZIP 对齐。Google Play 要求面向 Android 15/API 35 及更高版本的 64 位应用支持 16 KB，2027-02-01 起不满足的应用更新无法发布（已与官方文档核对）。对齐可能增大文件（段间空隙与 ZIP 填充），增量由布局决定、没有固定百分比。

**Q8: [learning] Android 17 的 Safer Native DCL 对动态加载 .so 增加了什么要求？**

版本边界：Android 17 的 Safer Native DCL 要求目标 API 37 应用用 `System.load()` 加载前把文件设为只读、否则抛 `UnsatisfiedLinkError`（AAOS13 无此要求）。Android 17 还提供 fatal 兼容测试属性让不兼容立即中止，兼容路径只用于迁移、不能替代 Play 要求。

**Q9: [learning] 资源体积优化中，“删”与“换”分别要守住哪些边界？**

删的边界在引用图与限定符回退。资源缩减依赖代码缩减（只有被删除代码引用的资源才会被删）：safe mode 会从字符串常量推测动态引用而保守保留，strict mode 只信显式引用与 `tools:keep`，迁移要先记录 safe mode 额外保留的原因再逐个消除。`Resources.getIdentifier()`、拼接资源名、通知、App Widget、清单元数据与反射 R 字段都在静态引用图之外，`tools:discard` 强删运行时仍会访问的资源会导致 `Resources.NotFoundException`。限定符是运行时选择规则不是冗余：语言、密度、夜间、最小宽度与版本限定符删除某候选后系统会回退并可能产生缩放、布局或语言错误，裁剪要在对应配置设备上验证。语言过滤用 AGP 8.8 起的 `androidResources.localeFilters`（旧 `resourceConfigurations` 已弃用），它过滤依赖翻译但不会生成缺失译文，关闭语言拆分会增加首装下载、换来离线切换可靠。

换的边界在格式版本与解码成本：WebP 各模式 Android 8 / API 26 起支持。AVIF 自 Android 12 / API 31，minSdk 26 应用要保留 WebP/PNG 回退资源（如 `drawable-v31` 目录），且回退会增加 AAB 上传总量。VectorDrawable 适合图标与路径图形，不适合照片（pathData 膨胀与 inflate 成本）。JPEG/WebP/AVIF/MP3 等已压缩格式再套通用压缩收益有限，`noCompress` 换取随机访问与直接映射但下载字节增加。硬规则：已签名 APK 不能再重压或用第三方资源混淆工具改写条目，任何转换必须放在签名前的受支持构建阶段。9-patch 的拉伸区元数据不做普通格式替换。

**Q10: [learning] AAB 上传后设备实际收到什么？bundletool get-size total 两条命令为什么不能相减？**

AAB 是发布格式不是安装包，设备不会安装 `.aab`。Google Play 按 AAB 生成并签名 base APK、按 ABI/语言/密度拆分的 configuration APK、feature APK，以及可能的 install-time 资源 split。本地用 `bundletool build-apks` + `get-size total --device-spec` 估算某设备的压缩传输大小：不带 `--modules` 统计首次下载时安装的所有模块。带 `--modules` 指定集合时会把所选模块的依赖一并计入。两者口径不同（首装全集 vs 指定集合含依赖），不能相减推导单个 feature 自身大小。未提供设备规格时结果可能是设备维度的最小-最大值，不能当单机基线。

三种尺寸要分开记录：AAB 上传大小（检查发布制品）、设备交付大小（由 SDK 版本、ABI、语言、密度、模块集合与交付时机决定）、安装后占用（受原生库提取、dexopt、资产展开与应用数据影响）。`build-apks` 本地签名可用 debug key，仅适合测试。渠道发布仍要用 Play 测试轨道或渠道环境验证。

**Q11: [learning] on-demand 动态特性模块的工程边界有哪些？为什么 base 不能引用 feature 的实现类？**

交付方式按行为分：install-time（默认、随安装、不减首装）、conditional（按设备特性、国家或最低 API 条件交付）、on-demand（运行时触发下载）、deferred install（后台尽力预取、无法跟踪进度）与 deferred uninstall（请求返回不代表文件已消失）。on-demand 流程经 `SplitInstallManager`：`startInstall()` 成功回调只返回会话 ID，必须等监听器报 `INSTALLED` 才能进入功能。`REQUIRES_USER_CONFIRMATION` 要走确认对话框。`FAILED`/`CANCELED` 保留基础功能并提供重试。监听器按页面或进程生命周期注销，进程重建后用 `installedModules` 与会话状态恢复，不能只依赖内存变量。

依赖方向是硬边界：feature 可以访问 base 的公共 API，base 在编译期不能引用 feature 实现，共享接口、错误类型与路由协议放 base 或独立 API 模块。feature 不重复声明签名、`versionCode` 与 `minifyEnabled`，附加 keep 规则构建时全量合并。`dist:title` 字符串放 base（模块下载前系统也要读取）。`dist:fusing=true` 决定模块能否进入需要融合的 universal APK。刚装完的一段时间内，平台可能无法应用 feature 新增的 manifest 组件、通知等系统界面也可能访问不到 feature 资源，通知图标与故障页应放 base。已安装 feature 随应用更新由 Play 管理，不要自建独立版本协议。

**Q12: [learning] 非 Play 渠道怎么替代 AAB 能力？为什么“运行时下载 DEX/SO”不能当体积方案？**

按渠道能力逐项降级：支持 AAB 的渠道按其文档上传验证，不能假设分包、签名或动态交付与 Google Play 相同。只收单 APK 的渠道构建 universal APK 或 per-ABI APK——`bundletool --mode=universal` 只融合 manifest 中 `dist:fusing=true` 的模块，多 APK 的 `versionCode`、签名与升级兼容要自管。受控安装器必须一次提交匹配设备的 base 与全部必需 split 并处理失败回滚。平台侧校验是硬约束：安装会话要求每个 split name 唯一、包名/版本/签名一致，缺必需 split 返回 `INSTALL_FAILED_MISSING_SPLIT`（机制按 AAOS13 的 `PackageInstallerSession` 口径核对），缺 split 的侧载在 Android 10 及以上设备会失败。

大资源走自建下载或 PAD：资源清单含版本、长度、哈希、签名与最低应用版本，先验证再原子发布。PAD 的 asset pack 不允许包含可执行代码，fast-follow/on-demand 以归档展开、路径可能跨会话移动、内容视为只读（补丁依赖完整性）。Google Play 的 Device and Network Abuse 政策禁止从 Play 之外下载可执行代码（WebView 或解释器中运行、仅间接访问 API 的 JS 除外），所以动态 DEX/JAR/`.so` 下载不是合规的体积方案——减少首次下载的正确路径是按需模块、按 ABI 拆分或删除代码。

**Q13: [learning] debuggable=false 的 release 包会自动删除 Log.d() 吗？R8 按级别删除日志怎么配置？**

不会自动删除。`debuggable=false` 只让 liblog 按 tag 属性和默认优先级决定是否写入，调用点、参数求值和 JNI 入口仍然发生。要构建期删除，当前 R8 文档提供 `-maximumremovedandroidloglevel`，按级别删除 `Log.*` 与 `Log.isLoggable()` 调用：

```proguard
# INFO 对应数值 4；删除 INFO 及以下级别。
-maximumremovedandroidloglevel 4
```

级别映射为 VERBOSE=2、DEBUG=3、INFO=4、WARNING=5、ERROR=6、ASSERT=7。可选的类规范可以把规则限定到特定类。多条规则命中同一方法时 R8 取最小级别。

边界与做法：该规则近年才进入正式文档，使用前确认项目 R8/AGP 版本支持，规则只进 release 变体，并用反编译或 mapping 输出验证结果。`-assumenosideeffects` 属于强制优化假设，参数带可观察副作用时不保证连带删除，构建期删除应配合调用前的条件判断。隐私上，`READ_LOGS` 自 Android 4.1 起是特权权限，但预装特权组件与 bugreport 仍可能读到日志——令牌、密码、会话密钥应完全不入日志（掩码只适合允许展示部分信息的字段），异常对象也要审查，服务端响应和 URI 查询参数可能经异常文本进入日志。
