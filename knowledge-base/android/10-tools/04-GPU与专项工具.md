# GPU 与专项工具

> 学习资料（文章模式沉淀）。主线：GPU、Camera、窗口与布局、构建产物和内核观测各有独立的证据通道——先按问题选证据形态（帧捕获、GPU counter、Camera trace、窗口状态、BPF 记账、keep 规则报告），再按 Android 版本核对平台侧实现与工具边界。源文档：android-internals-wiki §15.10《三方性能库、Hook 与可观测性基础设施》、§15.11《GPU 调试与 AGI 单帧分析》、§15.12《GPU Counter、内存与 GpuService 可观测性》、§15.13《Android Performance Analyzer 与 GAPS：性能追踪与目标可达性》、§15.14《Camera 性能分析工具：Perfetto、SQL 与 GFXReconstruct》、§15.15《Winscope、Layout Inspector 与 UI 状态调试》、§15.16《Android eBPF 架构与性能观测》、§15.17《R8 Configuration Analyzer 与 keep 规则体积归因》；材料按 Android 17（API 37）撰写，系统侧机制按本地 AAOS13 源码（Android 13）核对并标注版本差异，AGI、GFXReconstruct、APA、GAPS、Layout Inspector、R8 等外部工具不在本地树、按材料口径转写，GPU counter 协议细节按材料标注的 AOSP `external/perfetto` 口径转写，无法支撑的断言已弱化。Perfetto 采集、轨道与 SQL 基础见 [./01-Perfetto-采集与SQL分析.md](./01-Perfetto-采集与SQL分析.md)；Profiler、Simpleperf、dumpsys、statsd 等通用工具见 [./03-性能分析工具.md](./03-性能分析工具.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 想统计自有应用进程内 `libc.so` 的 `malloc` 被谁调用，PLT/GOT Hook 改写的是什么位置？为什么它注定覆盖不了所有 `malloc` 调用？**

PLT/GOT Hook 改写的是调用方共享库（DSO）自己的 GOT 重定位槽，把解析出的目标函数地址换成代理函数地址；它没有修改 `libc.so` 里的 `malloc` 实现，因此凡是绕过这个槽的调用都不会命中，"全量监控"不成立。

机制是：一个 DSO 调用另一个 DSO 导出的函数时，编译器通常生成经 PLT（过程链接表）跳转的调用；动态链接器处理 `R_*_JUMP_SLOT` 类重定位后，把解析出的函数地址写进调用方的 GOT 槽，PLT 代码再从该槽读目标地址。Hook 只需改槽内容，调用方下一次经 PLT 的调用就会进入代理。所以另一个 DSO 有自己独立的槽，要单独处理。

覆盖边界来自调用路径：同一 ELF 内部的直接调用不经过 PLT；`dlsym()` 返回的地址被直接调用时也不经过；编译器内联、静态链接和未列入 Hook 集合的符号同样绕过。Android 侧还有一个关键事实：AAOS13 的 `bionic/linker/linker.cpp` 在处理 `DT_PLTGOT` 时明确注释 `RTLD_LAZY is not supported`——链接器在装载期就完成跳转槽重定位，随后 `protect_relro()` 把 GNU RELRO 区域设为只读（本地源码核对），没有 glibc 风格"首次调用再解析"的窗口。因此 Hook 不必赶在 `dlopen()` 返回前完成，但必须先对 GOT 所在页调用 `mprotect()` 取得写权限，且要考虑 RELRO 只读和晚加载 DSO 两种情况。判断一个符号能否用 PLT Hook 采集，先确认目标调用确实经过动态重定位槽，再用已知次数的测试路径验证命中。

**Q2: PLT Hook 命中不了目标时改用 Inline Hook，它的原理和三项实现难点是什么？ARM64 上为什么经常需要 branch island？**

Inline Hook 在目标函数入口写入跳转指令，把所有到达该地址的调用转向代理函数，被覆盖的原始指令被搬进 trampoline（跳板区）执行后再跳回原函数；难点集中在指令长度、PC-relative 重编码和缓存/并发三点。

三项难点依次是：入口被覆盖的指令长度必须容纳跳转且不能把一条指令截断；被搬走的 PC-relative（相对当前 PC 寻址）指令要按 trampoline 的新地址重新编码，否则寻址失效；写入完成后要刷新指令缓存（框架一般调 `__builtin___clear_cache`），并处理并发线程可能正在执行入口指令的情况。

ARM64 指令固定 4 字节，`B`/`BL` 直接跳转范围约 ±128 MiB；代理函数往往放不了这么近，框架会先在目标附近分配 branch island（分支跳板岛）完成一段短跳，再从 island 做绝对跳转，这样入口只需改写一条相对跳转，也缩小了多指令分步写入的窗口。AArch32 还有 ARM/Thumb 混合编码，函数地址最低位可表示 Thumb 状态，而 AArch64 没有 Thumb 模式——一个 ABI 上验证过的 trampoline 不能推导另一个 ABI 也安全。相比 PLT Hook，Inline Hook 覆盖所有到达入口的调用，但要管理可执行内存与指令重定位，架构与内核加固（BTI/PAC、CFI）都会影响成功率；上层工具如 ShadowHook 的具体行为按其发布版本材料核对，不在本地 AOSP 树内。

**Q3: "普通应用在 Android 上被禁止拥有 RWX 内存，所以 Inline Hook 在线上必然失败"——这个说法有依据吗？**

没有。AAOS13 的 `system/sepolicy/private/app.te` 第 192 行仍写着 `allow appdomain self:process execmem;`（本地源码核对），材料按 Android 17 核对的结果同样是 appdomain 保留 `execmem` 许可，源码注释给出的用途包括 WebView 和应用自带的 JIT 编译器；"普通应用禁止所有 RWX"的统一规则并不存在。

要把边界说准，需要区分几层：`execmem` 管理匿名可执行内存的映射（Inline Hook 分配 trampoline 页走的就是它），`execmod` 管理文件映射改写后继续执行，两者是不同权限；`mprotect()` 只能改变当前进程有权操作的映射；APEX 只读文件系统阻止的是磁盘文件改写，进程内已映射页能否临时修改还要看 VMA 属性与策略；量产环境还可能启用 CFI、BTI/PAC 或 MTE，SELinux 通过只是前提之一。所以正确结论是：execmem 许可让 Inline Hook 在普通应用里"可行"，但不保证每一次 `mmap`/`mprotect` 成功——文件类型、厂商 SELinux 策略和加固机制都可能拒绝。工程做法是记录框架返回的每个错误码，Hook 安装失败时降级关闭该项观测并继续运行业务，而不是把 Hook 成功当作启动条件。

**Q4: Matrix、KOOM、Booster、LeakCanary 这类三方库应该按什么维度选型？"README 写支持某 API 级别"能证明什么？**

选型维度是采集位置：它在哪个层面拿到信号，决定了能看到什么、风险在哪里；"支持 API 37"这类声明只证明框架维护者覆盖了其公开测试范围，不能推导出上层工具的某个具体能力在目标 ROM 上可用。

按采集位置分四类：

- **进程内探针**：Matrix、KOOM、LeakCanary、btrace 在 App 进程内拿方法、Looper、堆、线程、I/O 线索，看不到系统全局因果；
- **构建期改写**：Booster 与 Matrix 的 Gradle 插件在编译期检查或改写字节码，回答"代码里存在什么调用"，回答不了"线上执行了多少次、多慢"；
- **研发侧工具箱**：DoKit、BlockCanary 一类适合开发测试现场，不做生产采样与聚合；
- **可观测性平台**：Firebase Performance、Measure 负责上传、聚合与会话关联，不替代本地源码级定位。

版本声明要拆开读。Matrix 的 Gradle 插件 README 仍声明支持 AGP 3.5/4.0/4.1，而旧 Transform API 自 AGP 8.0 起已移除（材料口径），用 AGP 8/9 的项目要选已迁移的 fork 或自行迁移到 Instrumentation/Scoped Artifacts API；KOOM 的 Java 模块声明 API 21+，Native/Thread 模块限 API 24+ 与 arm64-v8a，其 fork dump（暂停 VM、fork 子进程 dump 后恢复父进程）把冻结缩到 20 ms 量级是项目测量结果而非设备保证；KOOM Native 用"分配元数据 + 保守扫描"找不可达分配块，漏报由形似指针的值造成，它不能替代检测越界和 use-after-free 的 ASan/HWASan。组合使用还要防四件事：开销叠加（探针、Hook、unwind、dump 各自耗资源）、Hook 链冲突（两个库改写同一符号时链顺序与递归保护互相破坏）、数据缺共同主键（session 与时间源不统一就无法关联）、监控不可关闭。接入前把远程开关、采样率和失败降级写进清单，用目标低端机实测开销，不引用上游百分比。

**Q5: 一次 draw API 调用返回后，这一帧还要经过哪些时间节点？怎么用这些节点判断"是不是 GPU 瓶颈"？**

draw API 返回只说明 CPU 执行到提交点，这一帧此后还要走 GPU 执行、producer 完成后 `queueBuffer`、SurfaceFlinger latch、HWC 或 RenderEngine 合成、display present 几个节点；判断 GPU 瓶颈就是看 CPU submit、GPU 完成与显示交付哪一段越过了 deadline，而不是只看 GPU 时间长短。

一次完整帧的关键节点按序是：应用录制命令并 submit；GPU queue 上的工作开始与完成（completion fence）；producer 向 BufferQueue 提交 buffer（acquire fence signal）；SurfaceFlinger latch 选中本帧 buffer；HWC 硬件合成或 RenderEngine client composition；显示端 present。把 CPU/GPU/SF 各轨道放进同一时间轴后按证据定位方向：

- **CPU 录制或 submit 已晚，GPU 随后正常完成**：查 CPU 调度、锁、Binder、命令录制与资源加载；
- **CPU submit 按时，应用 GPU completion fence 越过 deadline**：应用提交的 GPU 工作量偏大，做降分辨率、关 pass 的单变量实验后进帧 profiler；
- **producer 卡在 acquire/dequeue/swap 而 GPU stage 不长**：BufferQueue backpressure、release fence 或 frame pacing 问题；
- **buffer 按时但 SurfaceFlinger 的合成或 present 晚**：查 RenderEngine、HWC 与显示端。

两个常见误判要避开：RenderThread 很短不等于 GPU 慢——RenderThread 可能只负责异步提交，GPU 是否受限要靠 GPU stage、completion fence 与单变量实验共同指向；FrameTimeline 的 `GPU Composition` 标记只说明 SurfaceFlinger 本轮用了 GPU 合成，不说明应用内容是否 GPU 渲染——游戏 Surface 由应用 GPU 绘制后仍可被 HWC 直接扫描输出。另注意队列中可能有多帧 in-flight，抓到的 API 帧很重不代表它就是用户看到的那一帧，要用 frame id、latch 与 present 建立对应。

**Q6: AGI Frame Profiler 是怎么把 GraphicsSpy 注入目标进程的？平台侧的注入条件是什么？**

AGI 在设备上安装与目标 ABI 匹配的 gapid APK，然后写一组 global settings（`enable_gpu_debug_layers`、`gpu_debug_app`、`gpu_debug_layer_app`、`gpu_debug_layers`）让 Vulkan loader 把 GraphicsSpy layer 加载进目标进程，目标进程内的 gapii 捕获 Vulkan 调用后经 adb forward 送回主机；Android 用 settings 而不是桌面 loader 的 `VK_LAYER_PATH` 环境变量。

平台侧条件按 AAOS13 源码核对：`GraphicsEnvironment` 要求目标 App 可调试，或设备是可 root 的 userdebug 构建，或 targetSdk 不低于 30 的 App 在 manifest 声明 `com.android.graphics.injectLayers.enable=true` 并设置 dumpable，三者其一成立后，还要 `ENABLE_GPU_DEBUG_LAYERS` 设置为 1 且包名与 `GPU_DEBUG_APP` 匹配，才会把 `gpu_debug_layers` 里的 layer 列表交给图形环境（AAOS13 `GraphicsEnvironment.java` 与 `Settings.java` 均可核对到这四个键）。AGI 官方 quickstart 只承诺 `android:debuggable="true"` 这条路径，平台允许的其他入口不代表 AGI 支持对任意生产 App 抓帧。

AGI 工具侧（材料口径，AGI 是独立版本化的仓库，不在 AOSP 平台 tag 内）：gapid APK 同时打包 `libVkLayer_GraphicsSpy.so` 与 `libgapii.so`，前者是被 loader 发现的 wrapper，后者是真正拦截实现；游戏有多进程时 AGI 用私有属性 `debug.agi.procname` 过滤进程名，名字不匹配的进程不建立抓帧连接。排障按链路查：目标 App 是否 debuggable、四个 settings 是否写对且 `gpu_debug_layer_app` 的 ABI 匹配、`debug.agi.procname` 是否为完整进程名、adb forward 与 socket 是否建立。AGI 异常退出后要手动删掉这些 global settings 并清空该属性——settings 跨重启保留，残留会让同包进程继续加载 layer。

**Q7: AGI 能直接捕获 OpenGL ES 应用吗？选 OpenGL on ANGLE 模式抓到的内容应该怎么理解？**

不能直接抓原生 GLES 调用。AGI Frame Profiler 的 GLES 路径是 OpenGL on ANGLE：用 AGI 提供的 custom ANGLE 把 GLES 命令翻译成 Vulkan，再捕获翻译后的 Vulkan 命令，所以 trace 描述的是 ANGLE 生成的 Vulkan workload，不是设备原生 GLES driver 的调用序列。

这个语义带来三个必须记住的边界：其一，看到的是转换后的 render pass、pipeline、shader 与 GPU 成本，ANGLE 自身的 API 转换、shader translation 和状态管理开销也进入了被测路径；其二，如果问题只在原生 GLES driver 上出现，这份 capture 已经更换了 backend，必须同时保留原生路径的 Perfetto、日志和厂商数据才能对照；其三，Android 15+ 提供按包测试 ANGLE 的入口，更新的版本还允许游戏在 manifest 里表达"优先使用 ANGLE"的请求（材料口径），但那是请求信号，系统是否选择 ANGLE 仍由设备配置与策略决定，不能认定 GLES 应用默认跑在 ANGLE 上。对照实验可用官方设置项把目标包指定到 `angle` 或 native driver，重启进程后核对 EGL vendor/renderer 与进程实际加载的库，测试结束删除这些 global 设置，避免污染后续基线。排查"选了 Vulkan 模式但 GLES 应用抓帧为空"时，先确认 API 模式选错是第一嫌疑。

**Q8: `profileable` 和 `debuggable` 有什么区别？帧捕获工具为什么通常要求 debuggable，性能基线又该怎么取？**

`profileable` 是 API 29 引入的 manifest 元素，允许 shell 侧 profiling 工具分析 release 构建并只暴露平台允许的有限数据，对运行时序的扰动通常更小；`debuggable` 则允许调试器和图形 layer 注入。帧捕获工具（AGI、RenderDoc、Sokatoa，材料口径）通常要求 debuggable 或 root，因为它们要把 Vulkan layer 加载进目标进程记录命令、资源和内存，这是 debuggable 才开放的通道。

由此得到基线取法：用 profileable/release 包录低扰动基线，回答"正常跑多快、改动有没有收益"；用 debuggable 包做短窗口详细诊断，回答"这一帧里哪个 pass、哪条命令、哪个资源有问题"。debuggable 会改变运行时优化与安全检查，捕获 layer 本身也记录命令，两类构建的绝对帧时间不能直接比较。`profileable` 也不保证 GPU 数据源出现——`gpu.counters`、`gpu.renderstages` 或厂商内核事件由系统 producer、驱动与设备配置决定，profileable 包得到空 GPU 轨道时应先查 data-source descriptor 与厂商支持，而不是推断"GPU 没工作"。报告至少记录：工具与版本、设备 build 与 GPU driver、包类型与 Graphics API、分辨率与刷新率、温度与持续运行时间、是否注入 layer 或替换 backend；性能数字来自未注入 layer 的低扰动运行，帧 capture 只用来解释慢帧结构，不当帧率基准。另外 GPU counter 没有跨厂商通用阈值（如"ALU 超 80% 即瓶颈"），counter 名称、分母与采样窗口由厂商定义，结论要绑定同一设备、同画质、相近热状态下的 A/B 对照。

**Q9: `dumpsys gpu --gpumem` 显示的每个进程 GPU 内存从哪里来？AAOS13 上这条链路的实现是什么样的？**

数据来自 GPU 驱动发出的 `gpu_mem/gpu_mem_total` tracepoint：内核里附着在该 tracepoint 的 BPF 程序把每个 `(gpu_id, pid)` 组合最近上报的总量写进 BPF map，GpuService 的 GpuMem 组件附着程序并以只读方式遍历 map，经 `dumpsys gpu --gpumem` 输出。GpuService 只是读取与展示，不拥有这些内存。

AAOS13 逐项核对（本地源码）：BPF 程序在 `frameworks/native/services/gpuservice/bpfprogs/gpu_mem.c`，map 容量 `GPU_MEM_TOTAL_MAP_SIZE = 1024`，键为 `(gpu_id << 32) | pid` 的 64 位整数，值为该组合当前总字节数，`size` 为 0 时删除对应条目；GpuMem 初始化先 `bpf::waitForProgsLoaded()` 等待系统 BPF 程序加载完成，再取 pinned program 并 `bpf_attach_tracepoint(fd, "gpu_mem", "gpu_mem_total")`，失败每秒重试、累计约 30 秒（`kGpuWaitTimeout = 30`）后放弃，用来覆盖 GPU 驱动晚于 gpuservice 启动的窗口；map 以 `BpfMapRO` 只读打开。pin 路径是 `/sys/fs/bpf/prog_gpu_mem_tracepoint_gpu_mem_gpu_mem_total` 与 `/sys/fs/bpf/map_gpu_mem_gpu_mem_total_map`。版本差异要记牢：材料按 Android 17 核对时该对象已改名 `gpuMem.bpf`，pin 路径前缀随之变为 `gpuMem`——pin 路径由对象名决定、跨版本会变，排查时以 `find /sys/fs/bpf` 实际输出为准，不要写死。`dumpsys gpu` 按 Binder 服务名 `gpu` 找到 GpuService，`--gpumem`、`--gpustats`、`--gpudriverinfo`、`--gpuwork` 分别触发不同模块（AAOS13 四个选项均核对存在）。dump 输出是遍历期间的 best-effort 快照，驱动可能同时还在更新 map，不要把它当成原子一致的状态。

**Q10: Perfetto 里 `android.gpu.memory` 数据源和 ftrace 的 `gpu_mem/gpu_mem_total` 事件各提供什么？只开其中一个会缺什么？**

`android.gpu.memory` 是 GpuMemTracer 注册的 Perfetto data source，在采集开始时遍历一次 BPF map，为已有条目各写一个初始 `GpuMemTotalEvent`，之后不再轮询；ftrace 的 `gpu_mem/gpu_mem_total` 事件则由驱动在 GPU 可寻址内存发生 allocate、free、import、unimport 后发出，提供会话期间的后续变化。要得到从起点开始的完整时间序列，两者必须同时启用。

只开 data source 的后果：trace 里只有启动时刻的全量快照，之后内存涨落全部缺失；只开 ftrace 的后果：首次驱动事件之前没有基线，起点前的占用水平无从知晓。事件字段只有 `gpu_id`、`pid`、`size` 三个（AAOS13 的 GpuMemTracer 与内核 tracepoint 头文件口径一致）：`pid == 0` 表示该 GPU 的全局总量，正 pid 表示进程总量；`size` 是更新后的总字节数，不是一次分配的增量。最小配置是把 `android.gpu.memory` 数据源与 `linux.ftrace` 的 `gpu_mem/gpu_mem_total` 事件放进同一 buffer：

```textproto
data_sources {
  config {
    name: "android.gpu.memory"
  }
}
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "gpu_mem/gpu_mem_total"
    }
  }
}
```

两个边界：这条链路由事件触发，驱动不发 tracepoint 时轨道不变——program 加载成功而 map 一直为空，常见原因是驱动没有实现或没有上报该 tracepoint；`GpuMemTotalEvent` 由 GpuService 生成、且只表达总量，不能替代厂商 GPU counter，也推不出带宽、shader 吞吐或某条命令的内存归属。

**Q11: `dumpsys gpu --gpustats` 里的统计和 GpuMem 是什么关系？这条链路在 Android 13 和 Android 17 之间有什么实现差异？**

GpuStats 记录的是 GL、Vulkan、ANGLE 三类图形驱动的加载次数、失败次数、加载耗时和按应用聚合的图形功能使用信息，完全不记录 GPU 内存字节数；它经 `dumpsys gpu --gpustats` 与 statsd pull atom 两个出口暴露，排查内存增长时不要到 GpuStats 找答案。

AAOS13 实现核对（本地源码）：每个应用的 GL/Vulkan/ANGLE 加载耗时样本上限由 `MAX_NUM_LOADING_TIMES` 控制，AAOS13 中为 50；应用记录达到 `MAX_NUM_APP_RECORDS = 100` 条时，`purgeOldDriverStats()` 按 `lastAccessTime` 删除最旧的 10 条（`APP_RECORD_HEADROOM = 10`）。GpuStats 首次收到统计时才向 statsd 注册 pull callback，且每次 pull 成功返回后相应累计值会被清空——一个 atom 表达的是相邻两次 pull 之间的增量，不是开机以来的累计值，这一点决定了它的数值语义。

版本差异有两处值得标注：其一，材料按 Android 17 核对的同名常量是 16，即每驱动每应用的加载耗时样本从 50 收窄到 16，跨版本比较加载耗时分布时样本量不同；其二，AAOS13 判定应用是否使用 ANGLE 只看 `driverPackageName == "angle"`，材料口径的 Android 17 增加了按 driver 枚举判定的分支。另外 `dumpsys gpu --gpustats` 追加 `--clear` 会清空所选统计并改变后续 dumpsys 与 statsd pull 的结果，采集证据前不要使用。

**Q12: `dumpsys gpu --gpumem` 显示某进程 GPU 内存持续增长，下一步怎么定位？GpuMem、memtrack、DMA-BUF 几个数字能互相换算吗？**

下一步是按"进程总量 → buffer 身份 → layer 关联"逐层收窄：先用 `dmabuf_dump` 拿到该进程引用的 DMA-BUF inode 与 exporter，再用 `dumpsys SurfaceFlinger` 关联当前 layer；但 GpuMem、memtrack 与 DMA-BUF 统计的数字不能互相换算，它们覆盖的对象和共享内存分摊规则不同，只能要求"多口径同时增长/同步回落"这类定性一致。

定位步骤：

1. `dumpsys gpu --gpumem` 场景前、中、后多次采样，锁定增长的 `(gpu_id, pid)`；GpuMem 只有进程总量，没有 DMA-BUF inode 或分配调用点，无法直接指出哪块 buffer；
2. `dumpsys meminfo <pid>` 看 Framework 核算中 GL/Graphics 分类（memtrack HAL 口径，含未计入 smaps 的 GPU private 分配）；
3. userdebug/eng 设备上 `adb root` 后 `dmabuf_dump <pid>` 看进程持有的 DMA-BUF，`dmabuf_dump -b` 按 exporter/device 汇总（AAOS13 的 `system/memory/libmeminfo/libdmabufinfo/tools/dmabuf_dump.cpp` 核对存在）；`adb root` 会重启 adbd，之后要重新取 PID；
4. `dumpsys SurfaceFlinger --list` 与完整 dump 关联 layer 与合成状态；SF 的 dump 没有 per-buffer 引用跟踪，只提供上下文。

口径差异的原因：GpuMem 是驱动上报的 `(gpu_id, pid)` 总量；memtrack 按类型核算且要求同一块内存不重复计入两个类型、共享 DMA-BUF 按引用者分摊 PSS；DMA-BUF 统计按 buffer 对象逐个列出，而 GPU private 分配可能根本没有 DMA-BUF 身份。进程退出后场景内存未立即归零也不自动构成泄漏——驱动缓存、延迟销毁和异步 fence 都会暂留，判断要靠多轮重复与稳定窗口；同时注意旧 PID entry 是否随进程退出删除，避免 PID 复用误判。

**Q13: Android Performance Analyzer（APA）在工具链里处于什么位置？它和 Perfetto CLI、AGI 的分工边界是什么？**

APA 是 Google 提供的独立桌面性能分析应用（材料口径：2026 年 5 月以 open beta 发布 System Profiler，8 月起下载页不再标注 Beta），以 Perfetto 为系统追踪基础，覆盖 CPU、GPU、内存与功耗，支持 Project 管理、交互录制、Trace View 与 PerfettoSQL 查询；它要求受支持的 Android 12+ 设备，发布周期独立于 Android 平台，不是"某版本 Android 新增的 framework API"。

分工按证据需求选：需要脚本化、固定配置、批量采集与 SQL 回归时用 Perfetto CLI 与 Trace Processor；需要 Vulkan 单帧的命令、pipeline、shader、纹理级证据时用 AGI Frame Profiler；需要人机交互的录制管理、轨道整理、GPU counter 浏览和 A/B 对照时用 APA——AGI 文档也把 APA 列为 system profiling 的推荐工具。三者都受设备 GPU producer 与驱动数据限制，空轨道先查设备支持与录制配置。APA 不提供线上采集 API，真实用户设备的脱敏 profile 采集走 `ProfilingManager` 一类系统接口。 APA 展示的 `sched_switch`、FrameTimeline 等数据语义由设备侧决定，时间上相邻的两个事件只是相关线索，写成因果还要线程状态、fence、调用栈或可重复实验支撑。

**Q14: 用 APA 录一份可信的系统 trace，录制配置和构建类型要注意什么？它注入的 Vulkan layer 会不会扰动结果？**

录制前固定设备、build fingerprint、GPU driver、刷新率、温度与 App 构建配置；launch mode 与 trigger 按问题选（启动问题用 On Startup 覆盖进程创建和首帧，稳态场景用 Manual 进目标窗口）；构建类型上，Java/Kotlin 性能测量建议 release-like 且 `debuggable=false`，Vulkan 应用要采 Vulkan 专属数据时才建议 `debuggable=true`——一个混合 App 很难单次录制兼顾两种目标，应保留两份实验，且两次 trace 的绝对时间不能互换结论。

APA 可在录制时注入三类 Vulkan layer（材料口径）：CPU Timing 把 Vulkan API 调用耗时显示为线程上的 slice，`vkCmdDraw` 一类高频函数被有意排除以免开销扭曲结果，它代表 CPU 提交侧耗时、不代表 GPU 执行时间；Render Pass Debug Names 把代码设置的调试标注带进 Trace View；Screenshots 依赖标准 `VK_KHR_swapchain`，用其他 present 机制时拿不到截图，且截图不能证明画面已交给显示端。启用 layer 本身改变运行环境，对外报告的性能数字必须另录一份未启用 layer 的基线。两个容易踩的采集坑：USB 连接会让 Battery 轨道持续充电，能量结论要转功耗测试或仪器；自定义 `TraceConfig` 增删数据源前先确认其在目标设备上注册、buffer 策略能覆盖录制时长，不要为画面丰富而全开数据源。APA 的 PerfettoSQL 与人工视觉对齐适合复盘，定量 A/B 比较仍用相同 SQL 或 benchmark 指标。

**Q15: GAPS 这类工具判定"目标方法可达"分哪两个阶段？报告里的静态命中率能当执行率用吗？触达了目标方法能证明性能问题吗？**

分静态路径重建与动态目标触达两个阶段：静态阶段从 APK/DEX 反向构建调用图，生成从 Android 入口到目标方法的路径与交互指令；动态阶段在设备上执行这些指令，用日志插桩或 Frida Hook 观察目标方法是否真的被调用。静态命中不等于设备上会执行，动态触达也只回答"方法被执行过"，不回答"它造成性能问题"。

数字要按语义读（材料论文 v3 口径）：在 56 个开源应用、每应用 50 个目标方法的基准里，GAPS 静态阶段对 88.24% 的目标至少生成一条路径——这是"路径生成占比"，不是路径精确率，更不是执行率；动态触达率从关闭 PHIL 时的 23.24% 提升到启用后的 56.93%，PHIL 是确定性步骤失败时介入的受限 LLM 组件（处理权限弹窗、动态布局等 UI 障碍），引入非确定性，模型与预算必须随实验记录。能力边界同样承重：目标只有约三分之一位于 Activity 中，浅层页面遍历覆盖不了；当前不支持 Jetpack Compose（事件经 lambda 挂接、节点随重组变化）；账号、支付、服务端配置等运行期状态会让有静态路径的执行停住；真实应用上 62.03% 的静态生成率与 54.80% 的动态触达率之间的差值主要来自这些未构造出的状态，不能叫"静态误报率"。

把 GAPS 接进性能调查时保持证据分工：GAPS 负责把目标路径执行起来，Frida/`Trace.beginSection()` 埋点提供时间锚点，Perfetto 与 simpleperf 负责同一时间窗内的线程、帧、调度与热点证据；只在 `REACHED` 样本内做性能归因，`FAILED` 样本只用于评估自动化可靠性。Frida Hook 本身有开销，可疑场景要移除探针复测。

**Q16: Camera 的预览卡顿、拍照慢、录像丢帧、内存上涨四类问题分别要观测什么？HAL3 的 request/result 模型是什么形状？**

四类问题的观测点不同：预览卡顿看 camera frame ready 到预览载体、SF latch 与 display present 的链路；拍照慢看触发到 request、shutter、image buffer 到编码保存的分段；录像丢帧看 record stream 到 encoder、muxer 与存储；内存上涨看 stream buffer、HAL cache、metadata 与业务队列。它们的公共底座是 HAL3 把相机建模为多笔 request 在途的异步流水线。

HAL3 模型的形状：App 通过 `CaptureRequest` 携带控制参数与目标 `Surface`，经 Binder 进入 `cameraserver`；`Camera3Device` 维护 request、in-flight 状态与 stream，`RequestThread` 准备 buffer 和 metadata 后调 HAL 的 `processCaptureRequest` 路径；HAL 经 sensor 和 ISP 生成结果，再通过 `processCaptureResult()` 分批返回 metadata 和 output buffer——同一 frame number 的 partial metadata、final metadata 与各路 buffer 可以在不同时刻到达，callback 顺序不能当作采集顺序。接口传输上，Android 13 起 Camera HAL 新增特性只通过 AIDL 提供、HIDL 仍被支持（AAOS13 的 `libcameraservice/device3/aidl/AidlCamera3Device.cpp` 核对了这条双轨），线程名和 transport 要从目标设备确认。每个 output 映射到独立的 Camera3 stream，preview、record、analysis、still 的消费速度可以不同：预览稳定不证明录像或分析流稳定，某一路 consumer 过慢也可能通过共享的 ISP 阶段、有限 buffer 或内存带宽影响其他输出。给Camera 问题定位时，先把现象归入四类之一，再沿对应链路选 Perfetto slice、BufferQueue/fence 或内存证据，不要用单一"帧间隔阈值"跨类判断。

**Q17: Perfetto 里 Camera 相关的关键 slice 有哪些？`frame capture` 和 `Stream N: first full buffer` 分别能证明什么、不能证明什么？**

AOSP camera 类目下的关键线索包括 `connectHelper`（CameraService 接入与 client 初始化）、`configureStreams`/`beginConfigure`/`endConfigure`（stream 配置）、`sendRequestsBatch`（RequestThread 调入 HAL 提交一批 request）、`frame capture` 与 `still capture`（以 frame number 为 cookie 的异步 request 区间）和 `Stream N: first full buffer`。AAOS13 源码逐字核对：`Camera3Device.cpp` 中 `ATRACE_ASYNC_BEGIN("frame capture", frame_number)`、`Camera3OutputStream.cpp` 中 `"Stream %d: first full buffer\n"`、`Camera3Device.cpp:3214` 的 `sendRequestsBatch()` 均存在。

边界按 slice 语义分：`frame capture` 从 `sendRequestsBatch()` 前开始，到 framework 判断该 request 的 result metadata、shutter 与全部 buffer 条件满足后结束，度量的是 request-to-result 分布——它包含 HAL/sensor/ISP 与 buffer 返回等待，不包含 consumer 后续处理，更不表示预览画面已经显示，所以"frame capture 的频率"不等于预览显示 fps；`Stream N: first full buffer` 由一个很短的 `ATRACE_NAME` 产生，时间戳标记首个有效 output buffer 进入 consumer 路径的时刻有价值，slice 时长没有阶段耗时含义，也不代表 SF latch 或 display present；`still capture` 对应 still intent 的 request 区间，不含 JPEG 保存完成。厂商 slice（高通 CamX/CHI、MTK P1/P2、ISP、JPEG 等）不是 Android 公共 ABI，只能作为目标设备线索——先用 frame number、sensor timestamp、request id、stream id、buffer id 把 AOSP slice 对齐，再解释 vendor node。另外 `dumpsys media.camera` 的 `ProcessCaptureRequest latency histogram` 统计的是同步提交调用范围（AAOS13 的 `mRequestLatency`），不含 sensor/ISP 运行时间。

**Q18: 用 SQL 量化 Camera 预览帧间隔时有哪些陷阱？怎么保证算出来的 fps 是可信的？**

最大的陷阱是事件没选对：同名 slice 可以来自不同进程、线程或异步 track，必须先列出 Camera 相关 slice 所在的 track，选出"每个目标预览帧恰好出现一次"的事件（典型是目标 preview stream 的 `queueBuffer`），再计算间隔；直接对 `frame capture` 求频率得到的是 request 完成节奏，不是预览交付节奏。

可信的计算流程：

1. 先按名称聚合列出候选 track，确认每个事件对应同一路 preview buffer，同 track 混有其他 `queueBuffer` 时加更具体的名称过滤；
2. 用窗口函数算相邻间隔并输出分布而不是只给均值——平均值会掩盖长间隔与随后的补帧，报告保留 gap 分布与原始时间窗；
3. 防御空样本：若 track 上一条匹配事件都没有，`COUNT(*) + 1` 仍会显示 `frame_count = 1`，统计前先确认样本数；
4. 关联归属时用 Perfetto 的 `upid`/`utid` 而不是原始 PID/TID（系统会复用进程线程号），同步 slice 落 thread track、`frame capture` 这类异步事件落 process track，一条查询为空时换另一条。

一个可复用的间隔骨架（track id 先经第一步确认）：

```sql
WITH ordered AS (
  SELECT ts, ts - LAG(ts) OVER (ORDER BY ts) AS gap_ns
  FROM slice
  WHERE track_id = 1234 AND name GLOB '*queueBuffer*'
)
SELECT COUNT(*) + 1 AS frame_count,
       1e9 / NULLIF(AVG(gap_ns), 0) AS average_fps,
       MAX(gap_ns) / 1e6 AS max_gap_ms
FROM ordered
WHERE gap_ns IS NOT NULL;
```

`average_fps` 只在所选事件与目标 buffer 一一对应时成立；某个间隔偏长还要看下一帧是否补回、显示端是否重复上一帧，30 fps 只给出约 33.33 ms 的名义周期，没有跨设备通用的"超 N ms 即故障"阈值。

**Q19: Camera 预览链路上 consumer 持有 buffer 不还，会造成什么后果？`maxBuffers`、`maxImages` 和"固定三缓冲"分别是什么关系？**

consumer 处理慢于产帧速度时，未归还的 buffer 向上游传导形成回压（backpressure）：缓冲被占满后 producer 取不到 buffer，表现为 `dequeueBuffer`、stream buffer request 或 fence wait 变长，严重时整条 session 被拖慢。这是"谁持有 buffer"的问题，不是"buffer 太少"的问题，单纯增大队列深度只会推迟卡顿并抬高内存。

buffer 数量没有固定值，三个概念要分开：HAL 对某流"同时持有且未归还"的上限由 `HalStream.maxBuffers` 在 stream 配置结果中逐流给出，不是通用常量；HAL buffer management（Android 10 起）还允许 request 先进 HAL、写入前再经 `requestStreamBuffers()` 申请 buffer，framework-managed 与 HAL-managed 模式的等待点不同；`ImageReader.maxImages` 是应用同时 acquire 且尚未 close 的图像上限，与 HAL 的 `maxBuffers` 无关。CameraX 侧 `ImageAnalysis` 的 `STRATEGY_KEEP_ONLY_LATEST` 非阻塞丢旧帧，`STRATEGY_BLOCK_PRODUCER` 在队列满时阻塞 camera device 范围内其他 use case，归还接口是 `ImageProxy.close()`。常见证据组合按持有者归类：`ImageReader`/`ImageAnalysis` acquire 后长时间不 close 是应用持有；encoder 变慢查 MediaCodec 与 muxer/storage；SurfaceView layer release fence 延迟是显示 consumer 仍在读取；多路同时恶化则查共享 ISP、带宽或 thermal。修复针对当前持有者：缩短 Image 持有、改 latest-only、稳定 session 配置、降低某一路分辨率或帧率。

**Q20: Camera 拍照延迟怎么分段度量？`SENSOR_TIMESTAMP` 能直接和 `elapsedRealtimeNanos()` 相减吗？内存侧 `CameraMetadataNative` 为什么要单独看？**

拍照报告至少分四段：触发到 request 提交、request 到 shutter/sensor timestamp、shutter 到 still buffer 可读、still buffer 到编码回调或文件落盘——它们分别对应控制、sensor/ISP、consumer 和 I/O，混成一个总耗时会把不同层的等待搅在一起。推荐三个量：`source_age = shutter_event_time - sensor_timestamp`（图像来自按键前多少毫秒，ZSL 命中时为正）、`callback_latency = callback_time - shutter_event_time`（含重处理与编码）、`save_latency = file_complete_time - callback_time`（应用侧文件 I/O）。

时钟域是硬前提：只有 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` 的设备才能把 `SENSOR_TIMESTAMP` 直接与 `elapsedRealtimeNanos()` 比较；timestamp source 为 `UNKNOWN` 时两者可能不属于同一时钟域，必须先在设备上完成校准，否则 `source_age` 的数值没有意义。`CONTROL_ENABLE_ZSL` 允许设备用历史帧生成 still result 但不保证每次命中，自管 ZSL 需要 reprocessable session 与 input stream，CameraX 的 ZSL 封装与 fallback 条件受库版本、flash、Extensions 与设备能力影响。

内存侧：AAOS13 的 `CameraMetadataNative` 持有 native 指针 `mMetadataPtr`，构造与拷贝时经 `updateNativeAllocation()` 向运行时登记这块 native allocation（本地源码核对），Java 对象的可达性决定 native metadata 何时具备清理条件，而 `finalize()` 的执行时机不确定，不能当及时释放保证。大量长期保留的 `TotalCaptureResult`/`CaptureResult` 或带着 result 的业务消息，会让对应 native metadata 一起存活；处理时把需要的字段提取到轻量对象。内存结论还要结合 Java heap、native heap、dma-buf 与 vendor pool，不能把 RSS 增长全部归给 metadata。

**Q21: Winscope 是什么工具？在 Android 13 上它的采集形态和 Android 15+ 有什么本质区别？**

Winscope 是 AOSP 的系统状态录制与回放分析工具，把 WindowManager 层级、SurfaceFlinger layer 状态、transaction、Shell transition、Input、IME、ProtoLog 和 ViewCapture 放到同一时间轴，回答"窗口/layer 状态何时偏离预期"这类对象级问题。本质区别在采集通道：Android 15 起 Winscope 各 trace 接入 Perfetto，每种 trace 是独立 data source（`android.windowmanager`、`android.surfaceflinger.layers`、`android.surfaceflinger.transactions`、`com.android.wm.shell.transition` 等，材料口径）；Android 13 仍是文件式采集——AAOS13 源码核对到 WMS 的 `WindowTracing.java` 写 `/data/misc/wmtrace/wm_trace.winscope`（`WINSCOPE_EXT = ".winscope"`），SurfaceFlinger 的 `LayerTracing` 与 `TransactionTracing` 经内存 ring buffer 分别写 `/data/misc/wmtrace/layers_trace.winscope` 与 `/data/misc/wmtrace/transactions_trace.winscope`，结束后由 Winscope Web 加载文件，且没有 `WindowTracingPerfetto` 这类 data source 注册。

版本解读的推论：读 AAOS13 现场时按文件式路径取证（调试构建、相应权限下触发录制再取文件），不要去配置 Android 15+ 的 data source 名；反过来读新版本 trace 时也不要用旧文件的字段集合解释。有一个例外值得记住：`android.surfaceflinger.frame`（FrameTracer，帧时间线 counter）在 AAOS13 就已经是 Perfetto data source（本地核对 `kFrameTracerDataSource`），它与 Winscope 的 layer/transaction 文件是两套通道。Winscope 提供状态证据，CPU 调度、Binder、GPU 与 present 时延仍归 Perfetto，两者按"状态错还是迟到"分工。

**Q22: 读 Winscope 的 layer tree 前为什么要先判断出图拓扑？SurfaceView 和 TextureView 在 SurfaceFlinger 里分别长什么样？**

因为同一张画面在 SurfaceFlinger 里可能只有一个宿主 App Window layer，也可能包含多个独立内容 layer，跳过拓扑判断会把"没找到视频 layer"误判成"视频没有提交"。四类拓扑：标准 View/Compose 窗口只有宿主 App Window buffer layer，SF 看不到内部每个 UI 节点；SurfaceView 有独立 layer；TextureView 通常没有独立可见 layer；多窗口/PiP/分屏还要数上 task leash、dim、wallpaper、IME 与系统栏 layer。

SurfaceView 的 AAOS13 形态（本地源码核对到 `BLASTBufferQueue` 与 `applyTransactionOnDraw` 的使用）：View 树里有宿主节点，SF 侧由 container（承载几何与层级）、BLAST buffer child（接收内容 buffer 的子层）和按条件显示的背景 color layer 组成；几何、crop、显隐落在 container，frame number 与内容 buffer 落在 BLAST child，两者的更新节奏彼此独立——container 已移动而 child 沿用旧 buffer 不一定是异常，只选中 container 宣布"有 buffer"则一定会得出错误结论。TextureView 的外部 buffer 先进应用进程内的 SurfaceTexture 队列，由宿主 HWUI 采样后写进 App Window buffer，所以 SF 里找不到独立 TextureView layer 属于正常拓扑，TextureView 黑屏要查 SurfaceTexture、宿主 RenderThread 与外部 producer，在 Winscope 里反复搜"TextureView layer"不会有结果。可见性判断上，WM 中可见只说明窗口管理状态满足条件；SF 中 layer 计算为可见也不保证像素正确——应用可能提交纯黑帧。应用内父子层级用 Layout Inspector，跨窗口与 SF layer 遮挡用 Winscope，两棵树不能一一对应。

**Q23: 用 Winscope 排查黑屏或几何错位时，正确的证据顺序是什么？requested 和 calculated 有什么区别？**

顺序是先锁时间点与 Display，再按 WM 逻辑状态 → SF 对象 → 可见性 → transaction 提交者的层级收窄，每一步都只把问题往下推一层：WM 里确认窗口/Task 状态与焦点；按拓扑找到宿主与独立内容 layer；检查 buffer、frame number、visible region、occlusion、crop、alpha 与 z-order；几何或显隐异常时沿 transaction 追提交者。黑屏的最后一步要区分三种可能：上层遮挡、提交了黑色像素、protected 内容因安全策略无法进录像——第三种不能单凭录像判断 producer 没出帧。

requested 与 calculated 的语义：requested 是该 layer 提交的几何与效果请求值，calculated 是叠加父层继承、坐标变换和裁剪后用于当前合成的值。requested 正确而 calculated 错误时，问题在父链——查 parent、leash、crop、relative Z 与 display 变换；requested 本身就错误时，沿 transaction trace 找提交者，transaction 记录提供 transaction id、PID、UID、layer id，`vsync_id` 属于一次 SF commit 的 entry（同 entry 内多笔 transaction 共用），不表示每笔客户端 transaction 各自的提交时刻。转场问题从 transition id 入手，把 Shell transition 的起止 transaction、目标 leash 与 SF transaction、WM 容器对齐；transition 已 finish 只说明状态机结束，仍要检查目标 layer 显示是否正确。静态"当前谁盖住谁"用 `dumpsys window --proto` 与 `dumpsys SurfaceFlinger --proto` 的 proto dump（AAOS13 两侧的 proto dump 路径均核对存在），dump 没有前后状态，证明不了闪屏或转场顺序。

**Q24: Layout Inspector 能确认什么、不能确认什么？为什么开启 View 属性检查会重启前台 Activity？**

Layout Inspector 查看运行中 debuggable 应用的 View/Compose 结构：节点是否存在、父子关系、bounds 与属性、Compose 的重组与跳过计数。它给的是组件树与当前属性快照——一个节点在 Component Tree 里存在，只证明应用进程内有这个 UI 对象，不保证它有可见像素，也不保证输入事件送达；一帧为何变慢归 Perfetto，窗口与 layer 状态归 Winscope。

重启的机制：Views 属性检查依赖全局设置 `debug_view_attributes`（AAOS13 的 `Settings.java` 核对该键），Layout Inspector 启动时自动开启它，系统会重启当前前台 Activity 使新属性生效；只要 flag 未被手动关闭，后续连接不再因此重启。这对复现的影响是真实的——重启改变冷启动路径、页面状态和只消费一次的事件，抓现场前要把它写进复现步骤；性能测量前应关闭 Inspector 并确认设置与页面状态恢复。排查点击无响应时注意：`enabled`、`clickable`、可见 bounds 都在 Inspector 范围内，但父层拦截、`TouchDelegate`、InputDispatcher 分发与窗口遮挡要转到事件 trace 或 Winscope——视觉上最上方的节点不一定收到事件。Compose 计数的读法是围绕一次受控交互：Reset 清零、执行一次动作、找计数上升的最小子树；recomposition 高表示函数频繁重执行，skipped 高表示本轮被跳过，两者都不能直接当性能评分，成本归因仍要用 Compose tracing 或 Perfetto 验证是否占用目标帧。

**Q25: 普通应用能加载自己的 eBPF 程序吗？Android 平台的 BPF 程序是怎么进入内核的？**

不能。Android 没有向普通应用开放通用 BPF 加载接口：`BPF_PROG_LOAD` 这条 syscall 路径被权限与 SELinux 策略挡住，应用只能读公开 API 已暴露的数据（Perfetto、simpleperf、`ProfilingManager` 等）。平台 BPF 程序随系统镜像构建，由启动期的特权 loader 加载、pin 到 `/sys/fs/bpf` 并按元数据设置权限，再由消费者 attach。

AAOS13 可核对的启动链（本地源码）：`init.rc` 在 `on init` 阶段把 bpffs 挂到 `/sys/fs/bpf`（`mount bpf bpf /sys/fs/bpf`），完成 `post-fs-data` 后 `trigger load_bpf_programs`，加载链末端设置 `bpf.progs_loaded` 属性（sepolicy 中有独立的 `bpf_progs_loaded_prop` 类型）；消费端如 GpuMem、GpuWork 都先调 `bpf::waitForProgsLoaded()` 等待该属性为 1。版本差异：材料按 Android 17 核对的实现是 Rust 平台 loader（`bpfloader.rs` 经 libbpf-rs）加 Connectivity APEX 的 `netbpfload` 多次 `execve()` 链，AAOS13 树未包含 `system/bpf` 源码目录，其 loader 形态按 AOSP 13 上游为 C++ 实现，可本地核对的锚点是上述 init.rc 触发点、属性与消费端等待逻辑。普通应用若需要内核侧信号，路径是使用系统已有的数据源，或以平台组件身份（自研系统镜像/模块）新增 BPF 对象——后者要同时准备构建规则、SELinux 规则、loader 清单与 map 权限，把 `.o` push 到设备并不能加载。

**Q26: Android 的 CPU time-in-state 统计（timeInState）观测什么？它的 attach 点和 map 路径在 Android 13 与 17 之间有什么差异？**

timeInState 回答"某 UID 在各 CPU 频点上累计运行了多久"这类累计记账问题：程序监听调度与调频事件，把每个任务被切出时在当前频点的时间差累计进 UID 维度和整机维度的 map。它保留累计值而不保留事件顺序，所以它不能回答"线程为何晚被调度、runnable 了多久"——后者要用 Perfetto 的 `sched_switch`/`sched_waking` ftrace 时间线。

AAOS13 核对（本地源码）：消费库 `frameworks/native/libs/cputimeinstate/cputimeinstate.cpp` 负责初始化与读取——扫描 cpufreq policy 建立 policy/CPU/频率表写入 map，然后对三个 pinned program 分别执行 attach：`sched/sched_switch`（结算被切出任务的时间）、`power/cpu_frequency`（更新频率索引）、`sched/sched_process_free`（清理退出进程的跟踪 slot），随后经 `bpf_obj_get` 打开 `map_time_in_state_uid_time_in_state_map` 等 map 读取；system_server 侧由 `KernelCpuBpfTracking` 经 JNI 调用。注意"loader 只 pin、消费者 attach"的分工：program 出现在 `/sys/fs/bpf` 不能证明它已在采集，map 长期为空还要查消费者是否执行了 attach、tracepoint 是否存在。路径差异是版本敏感点：AAOS13 的 map 路径形如 `/sys/fs/bpf/map_time_in_state_*`，材料按 Android 17 核对的路径是 `/sys/fs/bpf/cputimeinstate/map_timeInState_*`——pin 路径由对象名与元数据决定、跨版本会变，脚本与文档不要写死某一代的路径。另注意 SDK sandbox 的 UID 会同时记到对应 App 与保留的聚合 UID，framework 计算总量时要处理这份重复。

**Q27: `/sys/fs/bpf` 下能看到某个 BPF program，能说明它正在采集数据吗？怎么区分"加载成功""attach 成功"和"有数据"三层？**

不能。pin 只证明加载阶段完成——内核对象被固定到 bpffs 路径；attach 是把 program 连接到 tracepoint 等触发点的另一个动作；有数据还要求事件真的发生、消费者有权限读取 map。三层证据要分别确认。

区分方法按层走：

1. **加载层**：`getprop bpf.progs_loaded` 应为 1；`logcat -d -s 'bpfloader:*' 'LibBpfLoader:*' 'NetBpfLoad:*'` 查 verifier 拒绝与文件缺失（tag 集合随版本不同）；`find /sys/fs/bpf` 列出 pin 对象；
2. **attach 层**：确认消费者进程已启动并执行 attach——GpuMem 是现成例子，它在 `waitForProgsLoaded()` 后 `bpf_attach_tracepoint()`，驱动尚未注册 tracepoint 时每秒重试约 30 秒后放弃，放弃后 GpuMemTracer 因未初始化而不会注册 data source（AAOS13 源码核对）；userdebug/eng 设备可用 `bpftool prog show`/`map show`（量产 user build 通常没有这个工具）；
3. **数据层**：对 tracepoint 类程序检查 `/sys/kernel/tracing/events/<group>/<name>` 是否存在，再确认驱动是否真的发事件——map 为空可能是没有活跃占用、驱动未实现 tracepoint、attach 失败或消费者没启动，单看空 map 定不了因。

root 可读而 shell 不可读不是加载失败，是 pin 对象的 owner/group/mode 与 SELinux 的正常访问控制。验证一项 BPF 观测是否可信，还应像 Hook 一样做开关 A/B：同一负载下比较开启与关闭探针的目标指标差异，并记录 ring buffer 丢失计数。

**Q28: BPF 程序挂在与 ftrace 相同的 tracepoint 上，Perfetto 会显示 BPF 的聚合结果吗？Android 17 新增的几组 BPF 程序在 Android 13 上能用吗？**

不会。Perfetto 采到的是 ftrace 原始事件流；BPF 程序对事件做的聚合或额外输出需要用户态消费者读取 map 或 ring buffer 后自行写入 trace。平台已有的做法可作参照：GpuMemTracer 读 GpuMem 的 BPF map 在采集开始时写初始快照（后续变化仍靠 ftrace 事件），这是模块自己实现的桥接，不代表任意 BPF 程序都有同样集成。Android 17 的 `external/perfetto` 配置协议里也没有内置的 `ebpf` 数据源（材料口径）；自研程序要进 trace，需自建用户态消费者：读 map/ring buffer、保留内核时间戳与线程标识、经自定义 data source 或 TrackEvent SDK 写 packet，并统计读取失败与 buffer 满造成的丢失。

Android 17 的新程序不能外推到 Android 13：材料口径下 `cyclePerUid`（x86_64 的 per-UID cycle 归因，依赖 RAPL 节点）、`dmabufIter`（DMA-BUF 四字段全局快照 iterator）、`kernelWakelockDuration`（全局 active 时间并集）、`bpfLockContention`（白名单内核锁等待聚合）均为 Android 17 tag 内容，受 aconfig flag、架构与内核版本（部分要求 6.1+）限制；UprobeStats（statsd 控制的受控 uprobe 插桩）按材料版本表属 Android 15+ 的 Mainline 模块。AAOS13 树中这些对象均不存在（本地核对），已有的等价物是：GPU 内存走 `gpu_mem.c`、GPU work period 走 `gpu_work.c` 的 `power/gpu_work_period` tracepoint（AAOS13 核对 `DEFINE_BPF_PROG("tracepoint/power/gpu_work_period", ...)`）、DMA-BUF 快照走 `dmabuf_dump` 工具。同理，内核源码里有 sched_ext（BPF 调度器框架）只证明编译能力，设备是否在用还要看 Kconfig、加载的 scheduler 与运行状态——源码、Kconfig、产品启用与活动状态是四项不同证据。

**Q29: APK Analyzer 显示 `classes.dex` 变大了，为什么还要 R8 Configuration Analyzer？三类分数（shrinking/optimization/obfuscation）到底衡量什么？**

APK Analyzer 回答"结果变大在哪里"（哪个 dex、包、资源、ABI 贡献了体积），回答不了"哪条 keep 规则让 R8 放弃了哪些处理"；Configuration Analyzer 补的是后者——它把最终合并配置映射到类、字段、方法，给出三类分数和规则影响清单。三类分数衡量的是"仍允许 R8 处理的比例"：shrinking score 是仍允许被删除的类/字段/方法占比，optimization score 是仍允许被内联、类合并等改写的占比，obfuscation score 是仍允许被重命名的占比——它们不是已获得的字节收益，也不预测启动耗时，绝对值不能跨 R8 版本比较。

最终配置的来源决定了只读 `proguard-rules.pro` 不可靠：AGP 默认规则、App 自定义规则、AAR 携带的 consumer rules 和工具生成规则都会汇入。keep 规则是加法配置——多条规则的限制叠加，App 再补一条窄规则无法抵消 AAR 已带入的宽规则；`unused`/`identical`/`subsumed` 三类关系只描述当前 variant，不能当所有构建变体的删除清单。修复方向按 source 字段分：App 规则直接改；库的 consumer rules 优先靠升级 SDK 或反馈库作者；AGP 默认规则只识别不改动。一条宽规则的代价同时落在四个面（删、名、优化、class attribute 保留），修正思路是按运行时契约收窄——反射依赖类名才保类名、读泛型才保 `Signature`、JNI upcall 精确到 bridge 方法签名，能用 `allowshrinking`/`allowobfuscation`/`allowoptimization` 放开的面尽量放开，再用 release 包跑反射、序列化、JNI 与动态加载路径的回归。R8 与该工具运行在构建主机，`android-17.0.0_r1` 平台 tag 不包含它（材料口径，外部工具）。

**Q30: R8 Configuration Analyzer 的报告怎么生成、重点看哪些字段？full mode 迁移后它应该放在哪一轮？CI 门禁怎么定？**

生成路径有三条（材料口径，均为构建主机行为）：AGP 9.3.0+ 用独立任务 `./gradlew :app:analyzeReleaseR8Config`，报告写入 `build/reports/r8/` 下且不生成 APK；完整 `assembleRelease` 默认在 `build/outputs/mapping/<variant>/configanalyzer.html` 自动生成；更旧 AGP 需把内置 R8 替换为 9.3.7-dev+ 后用系统属性 `-Dcom.android.tools.r8.dumpkeepradiushtmltodirectory=<dir>` 输出 HTML。报告重点依次看：三类分数中下得分量最大的方向；`impactful rules`（按规则涉及的类/字段/方法数聚合，先确认大范围规则是否对应真实反射、JNI 或动态加载入口）；`subsumed rules`（匹配范围已被另一条规则包含，先判断被包含者或包含者谁过宽）；`unused`/`identical` 配置噪声。排查要配套同一次构建的 `configuration.txt`（最终合并配置）、`mapping.txt`、`seeds.txt`、`usage.txt`，独立分析任务不产出后四者。

放在 full mode 迁移的第二轮：第一轮先让 release 包跑通冒烟测试，迁移期间加的临时宽规则（包级 `-keep class ** { *; }`、全局 `-dontshrink` 等）常是分数低的主因；第二轮用报告把它们逐条映射到运行时契约并收窄。若必须临时关闭 full mode，把它当定位开关并给恢复设截止时间。CI 门禁按"变宽"定义而不是绝对分数红线：新增包级宽规则或全局 `-dont*`、任一分数相对主干基线下降超阈值、DEX 增长与分数下降集中在同一包——这些才算失败；基线与候选必须用相同的 AGP、R8、JDK、Gradle、variant 与依赖图，升级编译器先重建基线，不把分数变化归因给业务规则。官方 `r8-analyzer` skill（材料口径）可读取报告生成摘要与候选清单，但不修改 keep rule——规则背后是运行时契约，删除与否要由工程师确认回归覆盖。

