# 类加载、ART 编译与 JNI 链接

> 学习资料（文章模式沉淀）。主线：从类加载三动作出发，串起 Boot Image 的产物与共享、dexopt 过滤器与失效依赖、反优化的触发与回退、JNI 编组成本，以及 Bionic 与动态链接器的隔离、耗时归属。源文档：android-internals-wiki §1.4《类加载与 ART Boot Image》、§1.5《ART 编译、验证与反优化》、§1.6《JNI/NDK 与 Bionic 性能》、§1.7《动态链接器、VNDK 与隔离》（Android 17 / android-17.0.0_r1 语境）；ART Service 三种应用侧过滤器与 pm.dexopt 默认值、@CriticalNative 约束与 API 34 公开、Startup Profile 的 DEX 布局优化（AGP 8.1 起可用、8.3 起默认）、VNDK 自 Android 15 弃用，已于 2026-09-25 与官方资料核对（source.android.com、developer.android.com）。ART 与 JNI 的入门命题见 [05-Art.md](./05-Art.md) 与 [06-JNI.md](./06-JNI.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 类加载的 define、verify、initialize 三步各做什么？loadClass 与 Class.forName 的差别落在哪一步？**

define/link 把字节流变成运行时 Class 结构并完成验证与链接，initialize 执行 `<clinit>`；`ClassLoader.loadClass` 只做到 define/link、不触发初始化，`Class.forName(name)` 默认 initialize=true 会执行 `<clinit>`。

前提：`<clinit>` 有副作用（静态块、静态字段赋值），触发时机影响行为。机制：ART 的类状态沿 loaded→resolved→verified→initialized 推进，验证失败可软回退解释器；要"只加载不初始化"时用 `Class.forName(name, false, loader)`。结果：反射工具选 API 要明确目的——拿 Class 对象用 loadClass，需要静态初始化副作用才用 forName(true)。

**Q2: ART 怎么加速跨 ClassLoader 的类查找？DelegateLastClassLoader 的查找顺序特殊在哪？自定义加载器会失去什么？**

API 37 上 ART 的 `FindClassInBaseDexClassLoader` 对"已知形状"的加载器走原生快路径，直接在 boot classpath 与 dex 数组上查找，省去 Java 层委派递归；但只识别精确类型：PathClassLoader/DexClassLoader、InMemoryDexClassLoader、DelegateLastClassLoader。DelegateLastClassLoader 的特殊点是 boot classpath 仍最先查，其次自身 dex，最后才父委派——child-first 只对非 boot 类生效。

机制：快路径按精确类型匹配，避免对未知加载器结构做错误假设，包装器或自定义加载器回退 Java 路径。结果：自定义 ClassLoader 失去快路径；需要 child-first 语义（类隔离、热修复场景）选 DelegateLastClassLoader，但 boot 类永远不可被覆盖。

**Q3: DEX 文件里如何定位一个类？Startup Profile 的 DEX 布局优化为什么能加快启动、从哪个 AGP 版本默认开启？**

先按类描述符在 TypeLookupTable 里哈希定位 class_def，未命中再从 type_id 起顺序扫描（不是二分查找）。Startup Profile 是 Baseline Profile 的子集，构建期按它重排 DEX 类布局、把启动路径聚拢，官方口径比只用 Baseline Profile 启动再快 15%–30%；DEX 布局优化从 AGP 8.1 起可用（dexLayoutOptimization）、8.3 起默认开启（已与 developer.android.com 核对）。

机制：类查找成本与目标类在 DEX 中的位置强相关，顺序扫描尤其受布局影响；Startup Profile 只能由启动测试生成、库无法贡献，启动代码控制在首个 classes.dex 内收益最大。结果：Baseline Profile 解决"提前 AOT"，Startup Profile 解决"布局聚集"，两者互补（编译侧见 Q6）。

**Q4: Boot Image 的 .art/.oat/.vdex 各存什么？进程间怎么共享、怎么判断共享是否被打破？**

`.art` 存预初始化的堆对象镜像，`.oat` 存 AOT 编译的原生码，`.vdex` 存验证元数据与原始 dex；三者以 MAP_PRIVATE 映射实现写时复制共享，镜像 bitmap 区段映射为 PROT_READ 只读。

前提：boot image 在开机时由 zygote 映射一次，把 BCP 类的编译与初始化成果摊给所有进程。机制：共享部分计入各进程 RSS 但不重复占物理内存，被写的页转入 Private_Dirty；压缩镜像映射后表现为匿名内存。结果：读 smaps 判断健康度——boot image 区域 RSS 高、Private_Dirty 低是常态，Private_Dirty 异常增大说明有进程在写共享镜像。

**Q5: 设备如何选择 boot image 的位置？odrefresh 什么时候触发重建？**

优先用 APEX 数据目录中经 odsign 验证的镜像：`odsign.verification.success=true` 且 `/data/misc/apexdata/com.android.art/dalvik-cache` 下镜像完整时使用；否则回退 `boot_minimal.art` 或 `/system/framework/<isa>/boot.art`，且 odsign 未验证时以 `deny_art_apex_data_files` 拒用 APEX 数据文件。

机制：ART 模块更新或 BCP 构成变化会让现镜像失效，odrefresh 校验当前镜像的组件校验和与依赖（含 dirty-image-objects），不满足就重建；boot image 编译过滤器用 speed-profile（Android 12 起官方配置）。结果：ART 模块更新后的首次开机可能明显变慢，要区分"镜像回退到最小镜像"与"odrefresh 重建进行中"两种慢。

**Q6: 应用侧可用哪几种编译过滤器？speed-profile 没有 profile 时会发生什么？**

ART Service（Android 14 起管理应用 dexopt）只对应用暴露 verify、speed-profile、speed 三种；speed-profile 依赖 profile 指导，没有可用 profile 时实际生效的过滤器回退为 verify——只验证不编译（已与 source.android.com 的 ART Service 文档核对）。

前提：过滤器决定验证与编译的范围，speed-profile 只编 profile 覆盖的方法。机制：新装应用无 profile、baseline profile 未就位时无热点可依，回退 verify 是官方设计而非异常；查询实际生效值用 `adb shell cmd package art dump`。结果：安装后首启走解释与 JIT 不代表配置错误；要首启即有 AOT，必须让安装时就有 profile 可用（Baseline Profile 途径）。

**Q7: pm.dexopt.* 各场景的默认过滤器是什么？"安装后第一次启动慢"的完整链路怎么解释？**

默认值为 first-boot=verify、boot-after-ota=verify、boot-after-mainline-update=verify、bg-dexopt=speed-profile、inactive=verify、cmdline=verify、shared=speed（已与 ART Service 文档核对）；于是安装或 OTA 后首启只有验证过的码可跑、热点靠 JIT 现编，慢是设计使然，后台空闲充电时 bg-dexopt 以 speed-profile 补齐 AOT。

机制：dexopt 生命周期是"安装期验证 → 运行期 JIT 采热 → 后台 speed-profile"；A/B OTA 还支持重启前 dexopt，让更新后首启直接可用。结果：优化首启不要等后台 dexopt——用 Baseline Profile 让安装期就有 profile，speed-profile 才能生效；判断后台优化是否完成看 bg-dexopt 的执行记录。

**Q8: AOT 编译产物何时失效？vdex 记录的验证信息怎么被复用？**

失效依赖是 boot classpath 构成、boot image 校验和与 classpath context（CLC）——任一变化都会让已编译码的假设失效；vdex 保存验证结果与原始 dex，CLC 变化但验证前提未破坏时，dexopt 可复用验证、只重编代码。

前提：AOT 码可能内联 BCP 方法，BCP 变化即内联假设失真。机制：依赖检查按（BCP 指纹、boot image checksum、CLC）比对，通过则直接复用旧产物。结果：ART 模块是 BCP 的一部分，模块更新会触发大面积后台重编；排查设备"突然大量 dexopt"先看是否刚发生过模块更新。

**Q9: ART 的反优化有哪些触发场景？单帧反优化如何执行、怎么判断健康度？**

典型触发有 inline cache 失效（新类型到达单态调用点）、边界检查消除的假设被破坏、CHA 类层次变化、调试介入（kDebugging）、方法句柄类型不匹配等；执行时编译码在守卫点插入 HDeoptimize，命中后把当前帧重建为解释器 ShadowFrame、从该帧继续解释。

前提：优化基于假设，假设破坏要有受控回退通道。机制：单帧反优化只回退当前帧，外层编译帧不动；观测用 Perfetto 的 `Deoptimizing <方法>: <原因>` 切片与 SIGQUIT 里的 deopt 计数，GC 段的 RemoveUnmarkedCode 属于 JIT 缓存清理（kMaxCapacity 64 MB 上限）。结果：反优化是正确性机制而非故障——健康度看"反优化后是否收敛（profile 更新、重编译）"，而不是追求零反优化；另注意 debuggable 构建会额外触发 kDebugging 反优化与解释器插桩，其数据不能与 release 直接对比。

**Q10: @FastNative 与 @CriticalNative 加速什么？各自的硬约束与版本可用性是什么？**

两者都通过推迟 GC 挂起等待与减少过渡开销加速 JNI 调用——@FastNative 让线程保持 runnable；@CriticalNative 更激进，要求方法必须静态、参数与返回值不得含对象（ABI 中没有 JNIEnv*/jclass），官方 2016 年 angler 设备数据为普通 JNI 约 115ns、@FastNative 约 35ns、@CriticalNative 约 25ns。

可用性（已与官方参考文档核对）：Android 8 起平台内部使用；8–11 必须配合 RegisterNatives 动态注册；12 起支持内建动态 JNI 链接查找；14（API 34）起成为公开 API。Android 7 及以下注解被忽略，强行套用会因 ABI 不匹配导致错误编组；非静态或带对象参数的方法会抛 VerifyError。

做法：只用于短小纯计算的高频方法——推迟 GC 挂起意味着临界区内不可做阻塞或回调 Java 的事，否则拖慢整个 GC。

**Q11: native 自建线程里 FindClass 应用类为什么失败？attach 的线程还要注意什么？**

FindClass 沿"当前线程关联的类加载器"查找，native 自建线程 attach 后没有应用加载器上下文，回退只落到系统类加载器，所以应用类返回 null；解法是在 JNI_OnLoad 或有 Java 上下文时把 Class 缓存为全局引用并缓存 method/field ID。

前提：JavaVM 进程级共享、JNIEnv 线程私有。机制：attach 的线程在 detach 前局部引用表持续存活累积，长驻线程要显式 DeleteLocalRef；系统类在任何线程都能找到，失败的只会是应用类。结果：跨线程回调基建（全局引用、ID）统一预建，线程入口只消费缓存。

**Q12: GetStringCritical 返回的指针可以直接保存吗？数组 Elements API 的三种 release 模式怎么选？**

不可以——GetStringCritical 可能返回拷贝：compact strings 把字符以 latin-1 压缩存储，叠加移动 GC，VM 可能选择复制而非暴露原指针；数组 `Get<Type>ArrayElements` 同样是 pin-or-copy 二选一，release 模式 0 回写并释放、JNI_ABORT 不回写只释放、JNI_COMMIT 回写不释放。

前提：Critical 区段禁用 GC，必须短小且成对出现，区内不得调用其他 JNI。做法：不依赖 isCopy 的值写代码——按"可能拷贝"处理；大数组只读检查用 JNI_ABORT 省回写，分批写回用 JNI_COMMIT，常规"改完要生效"用 0。结果：把 Critical 指针存起来跨 GC 使用是悬空崩溃的典型来源。

**Q13: Bionic 的 malloc 最终走到哪个分配器？为什么不同产品行为不同？**

Bionic 的 malloc 是一层 MallocDispatch 分派表，实现按产品配置注入——多数产品默认 Scudo（加固分配器），配置 malloc_low_memory 的低内存产品走 jemalloc；应用无需链接额外库即获得对应行为。

前提：分配器是可替换组件，dispatch 层隔离替换的影响。机制：调优入口是 mallopt——M_PURGE 自 API 28 起主动归还内存，更新的 API 还有 M_PURGE_ALL/M_PURGE_FAST 变体（源文档口径）。结果：排查 native 堆问题先确认产品的分配器：两者的 RSS 行为、碎片与 purge 语义不同，跨产品直接对比 RSS 数值会误导。

**Q14: linker namespace 怎么判定"这个库能不能加载"？报 not accessible 时按什么顺序排查？**

每次 dlopen 从调用方 namespace 出发，检查目标文件路径是否落在可访问集合——按 ld_library_paths_、default_library_paths_、permitted_paths_ 线性查找，目录匹配区分 file_is_in_dir（本目录）与 file_is_under_dir（子树），都不中则拒绝。

前提：namespace 是 Android 隔离平台/vendor/应用库的基本单位，配置由 linkerconfig 生成（Android 11 起在 /linkerconfig/ld.config.txt）。机制：allowed_libs_ 提供跨 namespace 白名单补充。结果：报 "is not accessible for the namespace" 时按序排查——目标库属于哪个分区、调用方 namespace 的 permitted 路径是否覆盖、是否误用了本应经 public 库间接访问的平台私有库；直接改 LD_LIBRARY_PATH 通常破坏隔离而非修复。

**Q15: 为什么说 Bionic 的符号解析全是急切的？一次 dlopen 的耗时应该归到哪些阶段？**

Bionic 不支持 RTLD_LAZY、忽略 DT_BIND_NOW——加载即完成全部重定位，符号问题在 dlopen 时就失败，不会推迟到首次调用；一次 dlopen 的耗时由映射（PT_LOAD）、重定位（link_image，含 RELRO mprotect）与 call_constructors 三段构成，JNI_OnLoad 属于其后的 ART 加载阶段（JavaVMExt::LoadNativeLibrary），不算 dlopen 本身。

机制：do_dlopen 流程为——确定调用方 namespace → BFS 展开 DT_NEEDED 建 LoadTask → 映射 → prelink_image → 全局/局部符号分组 → link_image 完成重定位并保护 PT_GNU_RELRO → 引用计数与构造函数；重定位数据可用 DT_RELR/APS2 打包压缩，trace 上对应 `dlopen: <库名>` 与 `calling constructors: <路径>` 两类切片。结果：优化加载按段下药——减依赖宽度省映射与重定位、用打包重定位省数据量、拆分非必要构造省构造段；构造函数内崩溃会直接杀进程，且没有推迟构造执行的 API。

**Q16: VNDK 弃用后，framework 与 vendor 的原生库隔离靠什么维持？Android 17 的 16 KB 链接兼容开关有哪些取值？**

隔离的运行时基础仍是 linker namespace（linkerconfig 生成配置）；VNDK 自 Android 15 起弃用（已与 source.android.com 核对）——旧 VNDK APEX（v14 及以下）保留，原 VNDK 库改装入 vendor/product 分区，LL-NDK 因稳定 ABI 不属于 VNDK、继续存在。Android 17 的 16 KB 链接兼容属性 `bionic.linker.16kb.app_compat.enabled` 取 true/false/fatal，fatal 让不兼容库立即中止（配合 `pm.16kb.app_compat.disabled true` 全局关闭 backcompat，fatal 模式已与官方核对）。

前提："Self-contained HAL"（厂商自带全部依赖）不是新的链接器模式，只是库组织方式的变化，机制上仍走 namespace 检查。结果：判断 vendor 库加载问题先看 namespace 规则，再看 VNDK 弃用带来的库归属调整；16 KB 升级期的崩溃先查该属性取值与库对齐状态。
