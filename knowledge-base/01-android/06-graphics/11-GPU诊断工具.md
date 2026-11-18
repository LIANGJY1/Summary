# GPU、相机与窗口图层诊断

> 本册覆盖 GPU 执行与帧捕获、图形内存的统计口径、Camera trace 和 Winscope 窗口图层取证。平台机制以 AAOS 13（Android 13）源码为基准，其他版本差异与独立工具版本见各题。通用追踪配置见 [Perfetto 采集与 Trace Processor 分析](../14-performance/04-工具/01-采集与追踪/01-Perfetto采集与分析.md)，View/Compose 组件树检查见 [Layout Inspector](../14-performance/04-工具/05-平台诊断/09-LayoutInspector.md)。

## 第一章 GPU 时序、帧捕获与数据可用性

**Q1: [learning] 一次 draw API 调用返回后，这一帧还要经过哪些时间节点？怎么用这些节点判断“是不是 GPU 瓶颈”？**

draw API 返回只说明 CPU 已录制或提交图形命令。Producer 可以在 GPU 尚未完成写入时把 buffer 连同 acquire fence 提交给 BufferQueue。消费者必须等待 fence signal 后才能安全读取。之后还要经过 SurfaceFlinger latch、HWC 或 RenderEngine 合成和 display present。判断 GPU 瓶颈要比较 CPU submit、acquire fence signal 与显示交付相对 deadline 的位置，不能把 `queueBuffer` 当成 GPU 已完成。

关键节点存在异步重叠：

1. 应用录制并 submit 命令。
2. Producer 调用 `queueBuffer`，把 buffer 和尚未 signal 的 acquire fence 一起入队。
3. GPU 完成写入后 fence signal。
4. SurfaceFlinger 在满足同步条件后 latch。
5. HWC 或 RenderEngine 完成合成。
6. 显示端 present。

Android 同步框架允许 buffer 指针先交给消费者、再由 fence 表达 GPU 完成依赖。把 CPU、GPU 和 SurfaceFlinger 轨道放进同一时间轴后按证据定位：

- **CPU 录制或 submit 已晚，GPU 随后正常完成**：查 CPU 调度、锁、Binder、命令录制与资源加载。
- **CPU submit 按时，应用 GPU completion fence 越过 deadline**：应用提交的 GPU 工作量偏大，做降分辨率、关 pass 的单变量实验后进帧 profiler。
- **producer 卡在 acquire/dequeue/swap 而 GPU stage 不长**：BufferQueue backpressure、release fence 或 frame pacing 问题。
- **buffer 按时但 SurfaceFlinger 的合成或 present 晚**：查 RenderEngine、HWC 与显示端。

两个常见误判要避开：RenderThread 很短不等于 GPU 慢——RenderThread 可能只负责异步提交，GPU 是否受限要靠 GPU stage、completion fence 与单变量实验共同指向。FrameTimeline 的 `GPU Composition` 标记只说明 SurfaceFlinger 本轮用了 GPU 合成，不说明应用内容是否 GPU 渲染——游戏 Surface 由应用 GPU 绘制后仍可被 HWC 直接扫描输出。另注意队列中可能有多帧 in-flight，抓到的 API 帧很重不代表它就是用户看到的那一帧，要用 frame id、latch 与 present 建立对应。

**Q2: [learning] profileable 和 debuggable 有什么区别？帧捕获工具为什么通常要求 debuggable，性能基线又该怎么取？**

`profileable` 是 API 29 引入的 manifest 元素，允许 shell 侧 profiling 工具分析 release 构建并只暴露平台允许的有限数据，对运行时序的扰动通常更小。`debuggable` 则允许调试器和图形 layer 注入。帧捕获工具（AGI、RenderDoc、Sokatoa，材料口径）通常要求 debuggable 或 root，因为它们要把 Vulkan layer 加载进目标进程记录命令、资源和内存，这是 debuggable 才开放的通道。

由此得到基线取法：用 profileable/release 包录低扰动基线，回答“正常跑多快、改动有没有收益”。用 debuggable 包做短窗口详细诊断，回答“这一帧里哪个 pass、哪条命令、哪个资源有问题”。debuggable 会改变运行时优化与安全检查，捕获 layer 本身也记录命令，两类构建的绝对帧时间不能直接比较。`profileable` 也不保证 GPU 数据源出现——`gpu.counters`、`gpu.renderstages` 或厂商内核事件由系统 producer、驱动与设备配置决定，profileable 包得到空 GPU 轨道时应先查 data-source descriptor 与厂商支持，而不是推断“GPU 没工作”。报告至少记录：工具与版本、设备 build 与 GPU driver、包类型与 Graphics API、分辨率与刷新率、温度与持续运行时间、是否注入 layer 或替换 backend。性能数字来自未注入 layer 的低扰动运行，帧 capture 只用来解释慢帧结构，不当帧率基准。另外 GPU counter 没有跨厂商通用阈值（如“ALU 超 80% 即瓶颈”），counter 名称、分母与采样窗口由厂商定义，结论要绑定同一设备、同画质、相近热状态下的 A/B 对照。

**Q3: [learning] AGI Frame Profiler 是怎么把 GraphicsSpy 注入目标进程的？平台侧的注入条件是什么？**

AGI 在设备上安装与目标 ABI 匹配的 gapid APK，然后写一组 global settings（`enable_gpu_debug_layers`、`gpu_debug_app`、`gpu_debug_layer_app`、`gpu_debug_layers`）让 Vulkan loader 把 GraphicsSpy layer 加载进目标进程，目标进程内的 gapii 捕获 Vulkan 调用后经 adb forward 送回主机。Android 用 settings 而不是桌面 loader 的 `VK_LAYER_PATH` 环境变量。

平台侧条件按 AAOS13 源码核对：`GraphicsEnvironment` 要求目标 App 可调试，或设备是可 root 的 userdebug 构建，或 targetSdk 不低于 30 的 App 在 manifest 声明 `com.android.graphics.injectLayers.enable=true` 并设置 dumpable，三者其一成立后，还要 `ENABLE_GPU_DEBUG_LAYERS` 设置为 1 且包名与 `GPU_DEBUG_APP` 匹配，才会把 `gpu_debug_layers` 里的 layer 列表交给图形环境（AAOS13 `GraphicsEnvironment.java` 与 `Settings.java` 均可核对到这四个键）。AGI 官方 quickstart 只承诺 `android:debuggable="true"` 这条路径，平台允许的其他入口不代表 AGI 支持对任意生产 App 抓帧。

AGI 工具侧（材料口径，AGI 是独立版本化的仓库，不在 AOSP 平台 tag 内）：gapid APK 同时打包 `libVkLayer_GraphicsSpy.so` 与 `libgapii.so`，前者是被 loader 发现的 wrapper，后者是真正拦截实现。游戏有多进程时 AGI 用私有属性 `debug.agi.procname` 过滤进程名，名字不匹配的进程不建立抓帧连接。排障按链路查：目标 App 是否 debuggable、四个 settings 是否写对且 `gpu_debug_layer_app` 的 ABI 匹配、`debug.agi.procname` 是否为完整进程名、adb forward 与 socket 是否建立。AGI 异常退出后要手动删掉这些 global settings 并清空该属性——settings 跨重启保留，残留会让同包进程继续加载 layer。

**Q4: [learning] AGI 能直接捕获 OpenGL ES 应用吗？选 OpenGL on ANGLE 模式抓到的内容应该怎么理解？**

不能直接抓原生 GLES 调用。AGI Frame Profiler 的 GLES 路径是 OpenGL on ANGLE：用 AGI 提供的 custom ANGLE 把 GLES 命令翻译成 Vulkan，再捕获翻译后的 Vulkan 命令，所以 trace 描述的是 ANGLE 生成的 Vulkan workload，不是设备原生 GLES driver 的调用序列。

这个语义带来三个必须记住的边界：其一，看到的是转换后的 render pass、pipeline、shader 与 GPU 成本，ANGLE 自身的 API 转换、shader translation 和状态管理开销也进入了被测路径。其二，如果问题只在原生 GLES driver 上出现，这份 capture 已经更换了 backend，必须同时保留原生路径的 Perfetto、日志和厂商数据才能对照。其三，Android 15+ 提供按包测试 ANGLE 的入口，更新的版本还允许游戏在 manifest 里表达“优先使用 ANGLE”的请求（材料口径），但那是请求信号，系统是否选择 ANGLE 仍由设备配置与策略决定，不能认定 GLES 应用默认跑在 ANGLE 上。对照实验可用官方设置项把目标包指定到 `angle` 或 native driver，重启进程后核对 EGL vendor/renderer 与进程实际加载的库，测试结束删除这些 global 设置，避免污染后续基线。排查“选了 Vulkan 模式但 GLES 应用抓帧为空”时，先确认 API 模式选错是第一嫌疑。

**Q5: [learning] Adreno、Mali/Xclipse 的架构名称与厂商 NPU 百分比能说明什么？GPU 或内存数据缺失时怎么避免误判？**

架构名称只给出排查方向：Adreno 的 sliced architecture 与 FlexRender 是 Qualcomm 对自家 GPU 的描述，tile-based rendering 不能概括所有 Adreno 负载。Mali/Immortalis 是可配置 IP，同一代 IP 可以有 10–24 个着色器核心等不同规模，看到 Mali-G1 Ultra 仍要确认具体核心数与驱动。Xclipse 960 的 RDNA 代际官方未在产品页给出，要读实机 Vulkan/OpenGL ES 与驱动信息。API 支持由整机驱动决定：产品页列的是 IP 上限，应用能用哪些 Vulkan/OpenGL ES 扩展取决于量产驱动的 feature level。厂商 NPU 百分比用各自的模型、精度、功耗模式与上一代基线，缺少共同分母不能相加排序。端侧推理要看算子与动态形状是否被加速、计算图切成多少子图、哪些算子回退 CPU/GPU（NNAPI NDK 从 Android 15 起弃用，新方案选受支持的运行时与 delegate）。

数据缺失要防两类误判。GPU：Perfetto 的 gpu.renderstages、gpu.counters 数据依赖厂商 producer 注册，空轨道不能证明 GPU 空闲，采集前应查询设备公布的数据源并精确匹配名称，gpu_mem_total 是内存分配量不是带宽。内存：“支持 LPDDR5X”只是控制器能力范围，5300 与 10667 未注明单位时不能直接比较，应用拿到的是受内存控制器、缓存与互连调度的有效带宽。Perfetto 没有所有设备通用的 DRAM bandwidth 轨道，没有计数器时用受控扰动实验（保持 GPU 场景不变、逐档增加 CPU 内存流量并观察帧时间与功耗）才能接近因果判断，时间相关性只能提出假设。

## 第二章 图形内存统计与资源寿命

**Q6: [learning] 为什么图形内存没有一个统一数字？四类工具各回答什么、为什么不能相加？**

因为同一资源在不同工具里是不同视角，且互相重叠。dumpsys meminfo 给进程的 smaps 分类与 memtrack 补充归因。dumpsys gpu --gpumem 给驱动上报的 GPU 可寻址总量（全局与按 PID）。/proc/PID/fdinfo 与 /sys/kernel/dmabuf/buffers 描述 DMA-BUF 的持有与唯一实体。Perfetto android.gpu.memory 给随时间变化的 counter 序列。一张逻辑图片可能同时存在于软件像素、GPU 纹理缓存或 HARDWARE 位图中。一个 Java 对象可能没有独立底层分配，多个对象也可能共享同一 DMA-BUF。

不能相加的原因：同一 DMA-BUF 被多个进程和 GPU 引用时，各进程 GPU 总量之和可能大于唯一物理容量。memtrack 按 PSS 规则分摊共享并排除类型间重叠，与 gpu_mem_total 的目标不同。排查图形内存增长时，先为数字写清四项限定——采集接口、设备与系统 build、被归因的进程、资源是否共享。离开限定，"Graphics = 120 MB"既说不出物理内存有多少，也证明不了泄漏。

**Q7: [learning] dumpsys meminfo 的 Graphics 摘要由什么组成？Graphics 为 0 说明没有 GPU 内存吗？**

App Summary 的 Graphics 是三项 private memory 相加（AAOS13 Debug.MemoryInfo 的 summary.graphics 口径核对）：Gfx dev（smaps 识别的图形设备映射）、EGL mtrack（memtrack GRAPHICS 中尚未被 smaps 统计的部分）、GL mtrack（memtrack GL 中未统计的部分）。详细表中还可能出现 Other mtrack（来自 memtrack 的 MULTIMEDIA、CAMERA、OTHER 分类），它不属于 App Summary 的 Graphics 项。

memtrack 是厂商 HAL 提供的设备内存记账接口，通过 SMAPS_UNACCOUNTED 标志只报告 smaps 没有计入的部分，避免重复记账。接口契约按材料与官方口径转写（本地 AAOS13 树未包含 memtrack 接口目录）。由此得到判断规则：HAL 缺项或不支持某类型时对应行为 0 或缺失——Graphics 为 0 不能断言进程没有 GPU 资源。Graphics 上升也不能只从 Java heap dump 找 owner，Surface、TextureView、ImageReader、MediaCodec、Camera 与 Vulkan 的 buffer 生命周期各自独立。

**Q8: [learning] dumpsys gpu --gpumem 显示的每个进程 GPU 内存从哪里来？AAOS13 上这条链路的实现是什么样的？**

数据来自 GPU 驱动发出的 `gpu_mem/gpu_mem_total` tracepoint：内核里附着在该 tracepoint 的 BPF 程序把每个 `(gpu_id, pid)` 组合最近上报的总量写进 BPF map，GpuService 的 GpuMem 组件附着程序并以只读方式遍历 map，经 `dumpsys gpu --gpumem` 输出。GpuService 只是读取与展示，不拥有这些内存。

AAOS13 逐项核对（本地源码）：BPF 程序在 `frameworks/native/services/gpuservice/bpfprogs/gpu_mem.c`，map 容量 `GPU_MEM_TOTAL_MAP_SIZE = 1024`，键为 `(gpu_id << 32) | pid` 的 64 位整数，值为该组合当前总字节数，`size` 为 0 时删除对应条目。GpuMem 初始化先 `bpf::waitForProgsLoaded()` 等待系统 BPF 程序加载完成，再取 pinned program 并 `bpf_attach_tracepoint(fd, "gpu_mem", "gpu_mem_total")`，失败每秒重试、累计约 30 秒（`kGpuWaitTimeout = 30`）后放弃，用来覆盖 GPU 驱动晚于 gpuservice 启动的窗口。map 以 `BpfMapRO` 只读打开。pin 路径是 `/sys/fs/bpf/prog_gpu_mem_tracepoint_gpu_mem_gpu_mem_total` 与 `/sys/fs/bpf/map_gpu_mem_gpu_mem_total_map`。版本差异要记牢：材料按 Android 17 核对时该对象已改名 `gpuMem.bpf`，pin 路径前缀随之变为 `gpuMem`——pin 路径由对象名决定、跨版本会变，排查时以 `find /sys/fs/bpf` 实际输出为准，不要写死。`dumpsys gpu` 按 Binder 服务名 `gpu` 找到 GpuService，`--gpumem`、`--gpustats`、`--gpudriverinfo`、`--gpuwork` 分别触发不同模块（AAOS13 四个选项均核对存在）。dump 输出是遍历期间的 best-effort 快照，驱动可能同时还在更新 map，不要把它当成原子一致的状态。

**Q9: [learning] Perfetto 里 android.gpu.memory 数据源和 ftrace 的 gpu_mem/gpu_mem_total 事件各提供什么？只开其中一个会缺什么？**

`android.gpu.memory` 是 GpuMemTracer 注册的 Perfetto data source，在采集开始时遍历一次 BPF map，为已有条目各写一个初始 `GpuMemTotalEvent`，之后不再轮询。ftrace 的 `gpu_mem/gpu_mem_total` 事件则由驱动在 GPU 可寻址内存发生 allocate、free、import、unimport 后发出，提供会话期间的后续变化。要得到从起点开始的完整时间序列，两者必须同时启用。

只开 data source 的后果：trace 里只有启动时刻的全量快照，之后内存涨落全部缺失。只开 ftrace 的后果：首次驱动事件之前没有基线，起点前的占用水平无从知晓。事件字段只有 `gpu_id`、`pid`、`size` 三个（AAOS13 的 GpuMemTracer 与内核 tracepoint 头文件口径一致）：`pid == 0` 表示该 GPU 的全局总量，正 pid 表示进程总量。`size` 是更新后的总字节数，不是一次分配的增量。最小配置是把 `android.gpu.memory` 数据源与 `linux.ftrace` 的 `gpu_mem/gpu_mem_total` 事件放进同一 buffer：

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

两个边界：这条链路由事件触发，驱动不发 tracepoint 时轨道不变——program 加载成功而 map 一直为空，常见原因是驱动没有实现或没有上报该 tracepoint。`GpuMemTotalEvent` 由 GpuService 生成、且只表达总量，不能替代厂商 GPU counter，也推不出带宽、shader 吞吐或某条命令的内存归属。

dumpsys gpu --gpumem 读取同一个 map 的即时快照，适合确认增长集中在哪个 PID。Perfetto 补充会话起点和后续变化。驱动上报的是 GPU 可寻址内存总量。缺少驱动事件、BPF 程序或 map 初始化时，空轨道与零值都不足以证明没有分配。两种输出均缺少分配标识、格式和调用栈，定位创建点还需应用资源注册表、AGI 或厂商工具。

**Q10: [learning] dumpsys gpu --gpustats 里的统计和 GpuMem 是什么关系？这条链路在 Android 13 和 Android 17 之间有什么实现差异？**

GpuStats 记录的是 GL、Vulkan、ANGLE 三类图形驱动的加载次数、失败次数、加载耗时和按应用聚合的图形功能使用信息，完全不记录 GPU 内存字节数。它经 `dumpsys gpu --gpustats` 与 statsd pull atom 两个出口暴露，排查内存增长时不要到 GpuStats 找答案。

AAOS13 实现核对（本地源码）：每个应用的 GL/Vulkan/ANGLE 加载耗时样本上限由 `MAX_NUM_LOADING_TIMES` 控制，AAOS13 中为 50。应用记录达到 `MAX_NUM_APP_RECORDS = 100` 条时，`purgeOldDriverStats()` 按 `lastAccessTime` 删除最旧的 10 条（`APP_RECORD_HEADROOM = 10`）。GpuStats 首次收到统计时才向 statsd 注册 pull callback，且每次 pull 成功返回后相应累计值会被清空——一个 atom 表达的是相邻两次 pull 之间的增量，不是开机以来的累计值，这一点决定了它的数值语义。

版本差异有两处值得标注：其一，材料按 Android 17 核对的同名常量是 16，即每驱动每应用的加载耗时样本从 50 收窄到 16，跨版本比较加载耗时分布时样本量不同。其二，AAOS13 判定应用是否使用 ANGLE 只看 `driverPackageName == "angle"`，材料口径的 Android 17 增加了按 driver 枚举判定的分支。另外 `dumpsys gpu --gpustats` 追加 `--clear` 会清空所选统计并改变后续 dumpsys 与 statsd pull 的结果，采集证据前不要使用。

**Q11: [learning] dumpsys gpu --gpumem 显示某进程 GPU 内存持续增长，下一步怎么定位？GpuMem、memtrack、DMA-BUF 几个数字能互相换算吗？**

下一步是按“进程总量 → buffer 身份 → layer 关联”逐层收窄：先用 `dmabuf_dump` 拿到该进程引用的 DMA-BUF inode 与 exporter，再用 `dumpsys SurfaceFlinger` 关联当前 layer。但 GpuMem、memtrack 与 DMA-BUF 统计的数字不能互相换算，它们覆盖的对象和共享内存分摊规则不同，只能要求“多口径同时增长/同步回落”这类定性一致。

定位步骤：

1. `dumpsys gpu --gpumem` 场景前、中、后多次采样，锁定增长的 `(gpu_id, pid)`。GpuMem 只有进程总量，没有 DMA-BUF inode 或分配调用点，无法直接指出哪块 buffer。
2. `dumpsys meminfo <pid>` 看 Framework 核算中 GL/Graphics 分类（memtrack HAL 口径，含未计入 smaps 的 GPU private 分配）。
3. userdebug/eng 设备上 `adb root` 后 `dmabuf_dump <pid>` 看进程持有的 DMA-BUF，`dmabuf_dump -b` 按 exporter/device 汇总（AAOS13 的 `system/memory/libmeminfo/libdmabufinfo/tools/dmabuf_dump.cpp` 核对存在）。`adb root` 会重启 adbd，之后要重新取 PID。
4. `dumpsys SurfaceFlinger --list` 与完整 dump 关联 layer 与合成状态。SF 的 dump 没有 per-buffer 引用跟踪，只提供上下文。

口径差异的原因：GpuMem 是驱动上报的 `(gpu_id, pid)` 总量。memtrack 按类型核算且要求同一块内存不重复计入两个类型、共享 DMA-BUF 按引用者分摊 PSS。DMA-BUF 统计按 buffer 对象逐个列出，而 GPU private 分配可能根本没有 DMA-BUF 身份。进程退出后场景内存未立即归零也不自动构成泄漏——驱动缓存、延迟销毁和异步 fence 都会暂留，判断要靠多轮重复与稳定窗口。同时注意旧 PID entry 是否随进程退出删除，避免 PID 复用误判。

**Q12: [learning] 图形内存“泄漏”为什么先要排除三类延迟释放？**

图形资源从逻辑释放到物理回收常有三段延迟：GPU command 未完成时，driver 要等 fence signal 后才回收 backing memory。BufferQueue 的 producer、consumer 或 SurfaceFlinger 仍持有 slot。图片库、HWUI 或图形 API 保留高水位缓存，复用后不再增长。所以判据是形态：预热后数值稳定不涨，通常符合有上限缓存。每轮相同操作后基线持续上移，更像持有者引用或资源销毁不完整。退出页面后短时不降，要把 fence 与 queue drain 的时间放进采样窗口。一次 System.gc() 不能证明图形资源应立即归零。

资源关闭责任按 API 界定：ImageReader.acquire 得到的每个 Image 要及时 close（未关闭会占用 maxImages 配额和对应 buffer）。Surface、SurfaceTexture、MediaCodec、camera session 按生命周期关闭，Surface 销毁后 buffer 仍可能由 consumer 或 fence 短暂持有。OpenGL ES 资源要在属于相应 share group 的 context 中调用匹配的 delete API，删除后 driver 仍可等引用它的命令完成再释放。Vulkan 要区分 object 与 bound memory——销毁 VkImage 不会隐式替代 vkFreeMemory，suballocation 要交还应用 allocator，不要用 vkDeviceWaitIdle 当生产修复。

可通过 [DMA-BUF 的持有与系统统计](../11-原生层/04-共享内存.md) 区分进程引用与系统唯一缓冲区。

**Q13: [learning] SurfaceView 和 TextureView 谁更省内存？BufferQueue 的 buffer 数是固定的吗？**

两者都不能给固定排序。SurfaceView 由独立 Surface layer 提供内容，系统有机会直接交给 Hardware Composer 合成，但自己也有 BufferQueue。TextureView 把 SurfaceTexture 内容作为 View 层级的一部分由 HWUI 采样，支持普通 View 的 alpha、旋转与裁剪，但增加采样与合成工作，所在应用窗口也要 window buffers。总量由分辨率、格式、queue 深度、overlay 资格与内容路径共同决定，相机与视频页应同机实测进程 GPU counter、DMA-BUF inode 与帧时间再选。

BufferQueue 也没有通用“三缓冲”结论：可用 slot 与同时分配的 buffer 数由 max dequeued（生产者最多取出的槽位）、min undequeued（必须留在队列的最少槽位）、max acquired（消费者最多持有的槽位）、异步模式与 producer 行为共同决定。每个 buffer 的 payload 可用 width × height × bytesPerPixel 粗估，真实 allocation 还要看 stride、plane、格式、压缩与对齐——16 KB 内核页会提高小分配尾部向上取整的最大额外字节，但不能推导统一的“图形内存增加百分比”，4 KB 与 16 KB 的差异要用同 workload 实测。

**Q14: [learning] 像 renderD128 映射增长这类 GPU 驱动侧 OOM，排查方法有什么可迁移的？**

可迁移的是方法而非结论。第一步按设备型号、SoC、GPU、OS、驱动与 ABI 聚类，确认问题只出现在少数组合——聚类结果本身就是证据。第二步监控 /proc/self/maps 或 smaps 中对应设备节点的映射，区分虚拟地址空间耗尽与物理驻留增长（32 位进程更容易被地址空间卡死）。第三步用稳定触发实验锁定入口：该案例中常规 mmap/mremap hook 无记录，反汇编发现 vendor 用户态库绕过 libc 直接调用 syscall（mmap2），只拦截 vendor 库与 gralloc 的 syscall 才抓到映射。调用栈指向 buffer pool 长期保留——阈值 50 时映射峰值约 1.25 GB，调到 20 约 530 MB、10 约 269 MB，修复交给厂商后受影响机型 OOM 崩溃率下降近 50%。

这些都是特定设备、驱动与复现工程的数据，不能外推。错误 hook 私有函数可能破坏正在使用的 GPU 资源。推进 64 位可缓解地址空间耗尽但不减少物理内存成本。材料按 Android 17 核对的 HWUI 规则（alpha 位于 (0,1) 且节点报告重叠绘制时提升为独立 layer）能解释 alpha 触发路径，但不能证明该 pool 行为延续到新版本。

## 第三章 Camera 帧、时钟与缓冲区诊断

**Q15: [learning] Camera 的预览卡顿、拍照慢、录像丢帧、内存上涨四类问题分别要观测什么？HAL3 的 request/result 模型是什么形状？**

四类问题的观测点不同：预览卡顿看 camera frame ready 到预览载体、SF latch 与 display present 的链路。拍照慢看触发到 request、shutter、image buffer 到编码保存的分段。录像丢帧看 record stream 到 encoder、muxer 与存储。内存上涨看 stream buffer、HAL cache、metadata 与业务队列。它们的公共底座是 HAL3 把相机建模为多笔 request 在途的异步流水线。

HAL3 模型的形状：App 通过 `CaptureRequest` 携带控制参数与目标 `Surface`，经 Binder 进入 `cameraserver`。`Camera3Device` 维护 request、in-flight 状态与 stream，`RequestThread` 准备 buffer 和 metadata 后调 HAL 的 `processCaptureRequest` 路径。HAL 经 sensor 和 ISP 生成结果，再通过 `processCaptureResult()` 分批返回 metadata 和 output buffer——同一 frame number 的 partial metadata、final metadata 与各路 buffer 可以在不同时刻到达，callback 顺序不能当作采集顺序。接口传输上，Android 13 起 Camera HAL 新增特性只通过 AIDL 提供、HIDL 仍被支持（AAOS13 的 `libcameraservice/device3/aidl/AidlCamera3Device.cpp` 核对了这条双轨），线程名和 transport 要从目标设备确认。每个 output 映射到独立的 Camera3 stream，preview、record、analysis、still 的消费速度可以不同：预览稳定不证明录像或分析流稳定，某一路 consumer 过慢也可能通过共享的 ISP 阶段、有限 buffer 或内存带宽影响其他输出。给Camera 问题定位时，先把现象归入四类之一，再沿对应链路选 Perfetto slice、BufferQueue/fence 或内存证据，不要用单一“帧间隔阈值”跨类判断。

**Q16: [learning] Perfetto 里 Camera 相关的关键 slice 有哪些？frame capture 和 Stream N: first full buffer 分别能证明什么、不能证明什么？**

AOSP camera 类目下的关键线索包括 `connectHelper`（CameraService 接入与 client 初始化）、`configureStreams`/`beginConfigure`/`endConfigure`（stream 配置）、`sendRequestsBatch`（RequestThread 调入 HAL 提交一批 request）、`frame capture` 与 `still capture`（以 frame number 为 cookie 的异步 request 区间）和 `Stream N: first full buffer`。AAOS13 源码逐字核对：`Camera3Device.cpp` 中 `ATRACE_ASYNC_BEGIN("frame capture", frame_number)`、`Camera3OutputStream.cpp` 中 `"Stream %d: first full buffer\n"`、`Camera3Device.cpp:3214` 的 `sendRequestsBatch()` 均存在。

边界按 slice 语义分：`frame capture` 从 `sendRequestsBatch()` 前开始，到 framework 判断该 request 的 result metadata、shutter 与全部 buffer 条件满足后结束，度量的是 request-to-result 分布——它包含 HAL/sensor/ISP 与 buffer 返回等待，不包含 consumer 后续处理，更不表示预览画面已经显示，所以“frame capture 的频率”不等于预览显示 fps。`Stream N: first full buffer` 由一个很短的 `ATRACE_NAME` 产生，时间戳标记首个有效 output buffer 进入 consumer 路径的时刻有价值，slice 时长没有阶段耗时含义，也不代表 SF latch 或 display present。`still capture` 对应 still intent 的 request 区间，不含 JPEG 保存完成。厂商 slice（高通 CamX/CHI、MTK P1/P2、ISP、JPEG 等）不是 Android 公共 ABI，只能作为目标设备线索——先用 frame number、sensor timestamp、request id、stream id、buffer id 把 AOSP slice 对齐，再解释 vendor node。另外 `dumpsys media.camera` 的 `ProcessCaptureRequest latency histogram` 统计的是同步提交调用范围（AAOS13 的 `mRequestLatency`），不含 sensor/ISP 运行时间。

**Q17: [learning] 用 SQL 量化 Camera 预览帧间隔时有哪些陷阱？怎么保证算出来的 fps 是可信的？**

最大的陷阱是事件没选对：同名 slice 可以来自不同进程、线程或异步 track，必须先列出 Camera 相关 slice 所在的 track，选出“每个目标预览帧恰好出现一次”的事件（典型是目标 preview stream 的 `queueBuffer`），再计算间隔。直接对 `frame capture` 求频率得到的是 request 完成节奏，不是预览交付节奏。

可信的计算流程：

1. 先按名称聚合列出候选 track，确认每个事件对应同一路 preview buffer，同 track 混有其他 `queueBuffer` 时加更具体的名称过滤。
2. 用窗口函数算相邻间隔并输出分布而不是只给均值——平均值会掩盖长间隔与随后的补帧，报告保留 gap 分布与原始时间窗。
3. 防御空样本：若 track 上一条匹配事件都没有，`COUNT(*) + 1` 仍会显示 `frame_count = 1`，统计前先确认样本数。
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

`average_fps` 只在所选事件与目标 buffer 一一对应时成立。某个间隔偏长还要看下一帧是否补回、显示端是否重复上一帧，30 fps 只给出约 33.33 ms 的名义周期，没有跨设备通用的“超 N ms 即故障”阈值。

**Q18: [learning] Camera 预览链路上 consumer 持有 buffer 不还，会造成什么后果？maxBuffers、maxImages 和“固定三缓冲”分别是什么关系？**

consumer 处理慢于产帧速度时，未归还的 buffer 向上游传导形成回压（backpressure）：缓冲被占满后 producer 取不到 buffer，表现为 `dequeueBuffer`、stream buffer request 或 fence wait 变长，严重时整条 session 被拖慢。这是“谁持有 buffer”的问题，不是“buffer 太少”的问题，单纯增大队列深度只会推迟卡顿并抬高内存。

buffer 数量没有固定值，三个概念要分开：HAL 对某流“同时持有且未归还”的上限由 `HalStream.maxBuffers` 在 stream 配置结果中逐流给出，不是通用常量。HAL buffer management（Android 10 起）还允许 request 先进 HAL、写入前再经 `requestStreamBuffers()` 申请 buffer，framework-managed 与 HAL-managed 模式的等待点不同。`ImageReader.maxImages` 是应用同时 acquire 且尚未 close 的图像上限，与 HAL 的 `maxBuffers` 无关。CameraX 侧 `ImageAnalysis` 的 `STRATEGY_KEEP_ONLY_LATEST` 非阻塞丢旧帧，`STRATEGY_BLOCK_PRODUCER` 在队列满时阻塞 camera device 范围内其他 use case，归还接口是 `ImageProxy.close()`。常见证据组合按持有者归类：`ImageReader`/`ImageAnalysis` acquire 后长时间不 close 是应用持有。encoder 变慢查 MediaCodec 与 muxer/storage。SurfaceView layer release fence 延迟是显示 consumer 仍在读取。多路同时恶化则查共享 ISP、带宽或 thermal。修复针对当前持有者：缩短 Image 持有、改 latest-only、稳定 session 配置、降低某一路分辨率或帧率。

**Q19: [learning] Camera 拍照延迟怎么分段度量？SENSOR_TIMESTAMP 能直接和 elapsedRealtimeNanos() 相减吗？内存侧 CameraMetadataNative 为什么要单独看？**

拍照报告至少分四段：触发到 request 提交、request 到 shutter/sensor timestamp、shutter 到 still buffer 可读、still buffer 到编码回调或文件落盘——它们分别对应控制、sensor/ISP、consumer 和 I/O，混成一个总耗时会把不同层的等待搅在一起。推荐三个量：`source_age = shutter_event_time - sensor_timestamp`（图像来自按键前多少毫秒，ZSL 命中时为正）、`callback_latency = callback_time - shutter_event_time`（含重处理与编码）、`save_latency = file_complete_time - callback_time`（应用侧文件 I/O）。

时钟域是硬前提：只有 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` 的设备才能把 `SENSOR_TIMESTAMP` 直接与 `elapsedRealtimeNanos()` 比较。timestamp source 为 `UNKNOWN` 时两者可能不属于同一时钟域，必须先在设备上完成校准，否则 `source_age` 的数值没有意义。`CONTROL_ENABLE_ZSL` 允许设备用历史帧生成 still result 但不保证每次命中，自管 ZSL 需要 reprocessable session 与 input stream，CameraX 的 ZSL 封装与 fallback 条件受库版本、flash、Extensions 与设备能力影响。

内存侧：AAOS13 的 `CameraMetadataNative` 持有 native 指针 `mMetadataPtr`，构造与拷贝时经 `updateNativeAllocation()` 向运行时登记这块 native allocation（本地源码核对），Java 对象的可达性决定 native metadata 何时具备清理条件，而 `finalize()` 的执行时机不确定，不能当及时释放保证。大量长期保留的 `TotalCaptureResult`/`CaptureResult` 或带着 result 的业务消息，会让对应 native metadata 一起存活。处理时把需要的字段提取到轻量对象。内存结论还要结合 Java heap、native heap、dma-buf 与 vendor pool，不能把 RSS 增长全部归给 metadata。

## 第四章 Winscope 窗口与图层取证

**Q20: [learning] Winscope 是什么工具？在 Android 13 上它的采集形态和 Android 15+ 有什么本质区别？**

Winscope 是 AOSP 的系统状态录制与回放分析工具，把 WindowManager 层级、SurfaceFlinger layer 状态、transaction、Shell transition、Input、IME、ProtoLog 和 ViewCapture 放到同一时间轴，回答“窗口/layer 状态何时偏离预期”这类对象级问题。本质区别在采集通道：Android 15 起 Winscope 各 trace 接入 Perfetto，每种 trace 是独立 data source（`android.windowmanager`、`android.surfaceflinger.layers`、`android.surfaceflinger.transactions`、`com.android.wm.shell.transition` 等，材料口径）。Android 13 仍是文件式采集——AAOS13 源码核对到 WMS 的 `WindowTracing.java` 写 `/data/misc/wmtrace/wm_trace.winscope`（`WINSCOPE_EXT = ".winscope"`），SurfaceFlinger 的 `LayerTracing` 与 `TransactionTracing` 经内存 ring buffer 分别写 `/data/misc/wmtrace/layers_trace.winscope` 与 `/data/misc/wmtrace/transactions_trace.winscope`，结束后由 Winscope Web 加载文件，且没有 `WindowTracingPerfetto` 这类 data source 注册。

版本解读的推论：读 AAOS13 现场时按文件式路径取证（调试构建、相应权限下触发录制再取文件），不要去配置 Android 15+ 的 data source 名。反过来读新版本 trace 时也不要用旧文件的字段集合解释。有一个例外值得记住：`android.surfaceflinger.frame`（FrameTracer，帧时间线 counter）在 AAOS13 就已经是 Perfetto data source（本地核对 `kFrameTracerDataSource`），它与 Winscope 的 layer/transaction 文件是两套通道。Winscope 提供状态证据，CPU 调度、Binder、GPU 与 present 时延仍归 Perfetto，两者按“状态错还是迟到”分工。

**Q21: [learning] 读 Winscope 的 layer tree 前为什么要先判断出图拓扑？SurfaceView 和 TextureView 在 SurfaceFlinger 里分别长什么样？**

因为同一张画面在 SurfaceFlinger 里可能只有一个宿主 App Window layer，也可能包含多个独立内容 layer，跳过拓扑判断会把“没找到视频 layer”误判成“视频没有提交”。四类拓扑：标准 View/Compose 窗口只有宿主 App Window buffer layer，SF 看不到内部每个 UI 节点。SurfaceView 有独立 layer。TextureView 通常没有独立可见 layer。多窗口/PiP/分屏还要数上 task leash、dim、wallpaper、IME 与系统栏 layer。

SurfaceView 的 AAOS13 形态（本地源码核对到 `BLASTBufferQueue` 与 `applyTransactionOnDraw` 的使用）：View 树里有宿主节点，SF 侧由 container（承载几何与层级）、BLAST buffer child（接收内容 buffer 的子层）和按条件显示的背景 color layer 组成。几何、crop、显隐落在 container，frame number 与内容 buffer 落在 BLAST child，两者的更新节奏彼此独立——container 已移动而 child 沿用旧 buffer 不一定是异常，只选中 container 宣布“有 buffer”则一定会得出错误结论。TextureView 的外部 buffer 先进应用进程内的 SurfaceTexture 队列，由宿主 HWUI 采样后写进 App Window buffer，所以 SF 里找不到独立 TextureView layer 属于正常拓扑，TextureView 黑屏要查 SurfaceTexture、宿主 RenderThread 与外部 producer，在 Winscope 里反复搜"TextureView layer"不会有结果。可见性判断上，WM 中可见只说明窗口管理状态满足条件。SF 中 layer 计算为可见也不保证像素正确——应用可能提交纯黑帧。应用内父子层级用 Layout Inspector，跨窗口与 SF layer 遮挡用 Winscope，两棵树不能一一对应。

**Q22: [learning] 用 Winscope 排查黑屏或几何错位时，正确的证据顺序是什么？requested 和 calculated 有什么区别？**

顺序是先锁时间点与 Display，再按 WM 逻辑状态 → SF 对象 → 可见性 → transaction 提交者的层级收窄，每一步都只把问题往下推一层：WM 里确认窗口/Task 状态与焦点。按拓扑找到宿主与独立内容 layer。检查 buffer、frame number、visible region、occlusion、crop、alpha 与 z-order。几何或显隐异常时沿 transaction 追提交者。黑屏的最后一步要区分三种可能：上层遮挡、提交了黑色像素、protected 内容因安全策略无法进录像——第三种不能单凭录像判断 producer 没出帧。

requested 与 calculated 的语义：requested 是该 layer 提交的几何与效果请求值，calculated 是叠加父层继承、坐标变换和裁剪后用于当前合成的值。requested 正确而 calculated 错误时，问题在父链——查 parent、leash、crop、relative Z 与 display 变换。requested 本身就错误时，沿 transaction trace 找提交者，transaction 记录提供 transaction id、PID、UID、layer id，`vsync_id` 属于一次 SF commit 的 entry（同 entry 内多笔 transaction 共用），不表示每笔客户端 transaction 各自的提交时刻。转场问题从 transition id 入手，把 Shell transition 的起止 transaction、目标 leash 与 SF transaction、WM 容器对齐。transition 已 finish 只说明状态机结束，仍要检查目标 layer 显示是否正确。静态“当前谁盖住谁”用 `dumpsys window --proto` 与 `dumpsys SurfaceFlinger --proto` 的 proto dump（AAOS13 两侧的 proto dump 路径均核对存在），dump 没有前后状态，证明不了闪屏或转场顺序。
