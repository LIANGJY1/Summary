# ADB、am 与 dumpsys 诊断命令

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q1 -->

**Q1: [learning] 如何用 ADB 查看 Android 设备上某个应用的进程？**

先用 `adb shell pidof <进程名>` 查询正在运行的 PID。需要查看完整进程列表或进程名时，可用 `adb shell ps -A`，再按包名或进程名过滤。应用配置了 `android:process` 时可能有多个进程，主进程名通常与包名相同，私有子进程名通常形如 `包名:后缀`。

没有结果只表示查询时该进程不存在或过滤条件未匹配，不证明应用未安装。先检查设备连接、当前用户和实际 Manifest 进程名，再结合 `dumpsys activity processes` 查看系统记录。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q12 -->

**Q2: [learning] adb shell am dumpheap 从 shell 到 ART 经过哪些层？为什么 user 构建上对普通发布应用执行会失败？**

链路分四层（AAOS13 源码核对）：

1. `ActivityManagerShellCommand.runDumpHeap()` 解析参数并创建输出文件描述符；输出路径省略时落到 `/data/local/tmp/heapdump-<时间>.prof`。
2. `ActivityManagerService.dumpHeap()` 检查调用方持有 `android.permission.SET_ACTIVITY_WATCHER`，调用 `enforceDebuggable()` 检查目标进程，并临时调用 `enableFreezer(false)` 关闭缓存应用冻结器。
3. 请求经 Binder 到达应用进程的 `ActivityThread.handleDumpHeap()`；在 `-g` 时执行 `System.gc()`、`System.runFinalization()`、`System.gc()` 序列后按分支选择托管堆、native heap 或 malloc info。
4. 最后由 ART 停止托管线程并写文件。

user 构建上失败的原因是 `enforceDebuggable()` 的实现（AAOS13 核对）：`!Build.IS_DEBUGGABLE && !proc.isDebuggable()` 即抛 SecurityException——user 构建只能转储 debuggable 应用，`profileable` 声明不开放 HPROF。userdebug/eng 构建才允许转储其他目标。Android 13 的选项是 `--user`、`-n`（native heap dump）、`-g`、`-m`（malloc info）。

Android 17 材料口径另有 `-b <png|jpg|webp>` 把 Bitmap 像素写进托管堆转储，属版本差异。`-n` 和 `-m` 的产物不是 HPROF，不能交给 HPROF 分析器。应用在自己进程内调用 `Debug.dumpHprofData()` 不经过 AMS 这组跨进程检查。Freezer 临时关闭的目的是防止目标进程被冻结后无法处理 Binder 请求，托管线程的暂停由 ART 负责，两者是两件事。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q16 -->

**Q3: [learning] dumpsys 输出的是哪种证据？为什么 dumpsys cpuinfo 的结果不能叫“瞬时 CPU”？**

`dumpsys` 通过 ServiceManager 找到目标 Binder 服务并调用它的 dump 接口，拿到的是该服务在采集时刻愿意公开的内部状态——或是即时快照，或是某段累计统计窗口，不是时间线。事件先后要靠 Perfetto、Winscope 或日志补足。AAOS13 的 `dumpsys.cpp` 核对：无参数全量模式按服务名排序逐项 dump，单个服务默认等待 10 秒。常用全局参数（写在服务名之前）包括：

- `-t`：超时单位为秒；
- `-T`：超时单位为毫秒，默认 10 秒；
- `-l`：列出注册且可见的服务；
- `--pid`：只打印服务所在进程 PID；
- `--priority`：按 CRITICAL 等优先级筛选。

`--proto` 筛选声明 `DUMP_FLAG_PROTO` 的服务。cpuinfo 正是“累计窗口”的例子：它输出 ActivityManager 后台 `ProcessCpuTracker` 最近两个采样点之间的增量（来自 `/proc/stat` 与 `/proc/<pid>/stat`，AAOS13 存在该类），调用命令的时刻不一定触发新的测量窗口，“瞬时 CPU”的说法高估了时间精度。进程行的 `user`/`kernel` 分列自该进程的两种态 CPU 时间，`iowait`、`irq`、`softirq` 只在 TOTAL 行传入，多线程进程能并行占用多个 CPU、百分比可能超过 100%。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q17 -->

**Q4: [learning] dumpsys activity oom 里的 cur、set、curRaw、setRaw 和 adjType 各代表什么？OOM adjustment 数值高就代表进程会被杀吗？**

OOM adjustment 是系统交给 lmkd 的进程保护分数，数值越大在内存压力下通常越早进入回收候选，但它不表示进程已经发生 OOM。基准值在 AAOS13 的 `ProcessList.java` 核对：`FOREGROUND_APP_ADJ = 0`、`VISIBLE_APP_ADJ = 100`、`PERCEPTIBLE_APP_ADJ = 200`、cached 区间从 900 开始（材料按 Android 17 引用的 `psc/Constants.java` 是新版本文件名，数值一致）。

字段要分开读：`curRaw` 是本轮计算中尚未施加部分修正的 raw adj，`setRaw` 是上次写入记录的 raw adj，`cur` 是本轮计算后的目标值，`set` 是已提交给进程/lmkd 路径的值。`adjType`、`adjSource`、`adjTarget` 说明哪个组件关系（绑定服务、ContentProvider、前台服务、显示器可见性等）抬升或降低了保护级别。发现 `set` 与界面状态不符时，应连同这些归因字段一起核对，不能只用一个数字判定 OOM 计算错误。LMK 的最终归因还要对照 lmkd 日志、PSI 与 `exit-info` 退出原因。

可见进程还可能受 laddering 影响、被分配到 100–199 之间的细分值（材料口径，具体行为受设备配置控制）。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q18 -->

**Q5: [learning] dumpsys gfxinfo 的 reset、framestats 与聚合统计各承担什么？"Number Slow bitmap uploads" 高就是图片解码慢吗？**

`reset` 清空进程内保存的 HWUI 帧数据以划出可重复的测试窗口。无参数输出 since-reset 的聚合统计。`framestats` 输出环形缓冲区里的逐帧列，解析时读取 `---PROFILEDATA---` 之后的 header 按列名建索引——不同版本会增加字段，按固定列号解析会把后续时间戳整体错位，未填充字段可能用 0、负值或哨兵值，计算差值前要过滤。

AAOS13 的 HWUI 源码核对：`JankTracker` 使用固定 120 项的环形缓冲（写满覆盖最早记录），`ProfileData` 同时输出 deadline-aware 与 legacy 两组统计（`Janky frames` 与 `Janky frames (legacy)`）及 90/95/99 分位，deadline 口径按 `GpuCompleted` 是否越过 `FrameDeadline` 判定，`FrameInfo.h` 中存在 `FrameDeadline`、`SyncStart`、`IssueDrawCommandsStart`、`GpuCompleted` 等字段。

分类名是 trace 入口不是根因：`Slow bitmap uploads` 是保留的历史输出名（对应 `SyncStart → IssueDrawCommandsStart` 超阈值），不能凭名称认定发生了图片解码。`Slow issue draw commands` 也不区分 CPU、GPU、队列或 fence 等待。方法级归因回到 Perfetto 的 FrameTimeline、UI Thread、RenderThread 与 GPU 证据。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q19 -->

**Q6: [learning] 排查图层缺失或晚呈现时，Android 13 上 dumpsys SurfaceFlinger 的 --list 与 --latency 怎么用？--frontend 为什么是无效参数？**

Android 13 的 dumper 注册了 `--list`（输出 Layer 精确名称）、`--latency` 与 `--latency-clear`（单 Layer 的历史帧统计）、`--displays`、`--frametimeline`、`--static-screen` 等入口（AAOS13 `SurfaceFlinger.cpp` 核对）。

`--frontend`、`--hwclayers`、`--scheduler` 是 Android 15 之后 FrontEnd 架构引入的视角（材料按 Android 17 核对），在 Android 13 设备上不可用——这是本文最容易被旧资料误导的版本差异。`--latency` 的用法：先用 `--list` 复制精确 layer name，输出首行是采集时刻的刷新周期信息，其后每行是一帧的三个纳秒级时间戳——期望呈现时间、实际呈现时间、帧就绪时间（三元组语义按材料口径，各版本字段含义略有差异）。

`actual − desired` 只能标记晚呈现，不能独立归因，晚呈现可能来自生产者、acquire fence、调度、client composition、HWC 或 present fence。解析要过滤 0、负值和未完成哨兵，三列都有效才计算差值。dump 中 CLIENT（GPU/RenderEngine 合成）与 DEVICE（HWC 硬件路径）的合成方式只代表该次采集附近的状态，跨帧结论应使用 Perfetto、Winscope 或稳定复现实验。


<!-- 来源：00-methods-and-tools/02-profiling-tools.md · Q34 -->

**Q7: [learning] 一次 ANR 排查里，statsd、Perfetto、logcat、dumpsys 各能提供什么？为什么缺一类证据结论就会偏窄？**

四类证据回答不同问题：statsd 的 `ANROccurred` 报告回答哪个 UID/进程发生 ANR、reason 字段是什么、处理各阶段耗时是否异常。Perfetto 还原 ANR 前主线程处于 runnable、blocked 还是 Binder 等待、谁持有锁。logcat 还原 ActivityManager 的 ANR 判定路径。

`dumpsys activity exit-info`（`ApplicationExitInfo`，Android 11+）区分 ANR、Java/native crash、low-memory kill、用户请求等退出原因，`lastanr` 保存最近一次 ANR 状态。缺任何一类结论都会偏窄：statsd 只有聚合事实没有时序，Perfetto 不知道平台统计口径，dumpsys 快照只证明当前状态，ANR trace 的线程栈要靠系统保存。LMK 同理——`LmkKillOccurred` 只记录哪个进程被杀，内存压力为什么升高要查 lmkd 日志、PSI、PSS/zRAM 与前后台状态。

工程上的分工原则：线上 APM 负责应用内会话与业务维度，statsd 负责系统侧平台事件维度，后端按时间、版本、设备关联两者。只有系统侧才知道、App 无权限拿到的事件才值得引入 statsd，APM 能稳定采到的不必重复采集。


<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q34 -->

**Q8: [learning] adb shell am start -W 的输出应该怎么读？“冷启动”与“清缓存”为什么不是一回事？**

`-S` 会在启动前 force-stop Intent 匹配的包，`-W` 等待启动结果。Android 13 与 17 的输出都是 Status、LaunchState、TotalTime、WaitTime 四项（AAOS13 的 `ActivityManagerShellCommand` 与 `WaitResult` 源码核对。旧版本的 ThisTime 已不再打印）。读法：

1. **LaunchState**：`WaitResult` 区分 COLD、WARM、HOT、RELAUNCH，按进程是否运行、Activity 是否新建分类。报告必须保存该字段，预期 COLD 却得到其他状态时该轮无效。一个竞品可能通过透明 Activity、deep link 或多进程策略进入首页，TotalTime 覆盖到哪个 Activity 取决于本次实际启动链。
2. **TotalTime**：Activity 启动跟踪开始到目标窗口完成首轮绘制的延迟，接近 TTID 的系统口径，不表示网络数据齐备或页面可完成业务操作。
3. **WaitTime**：shell 在调用前后用 uptime 计算的阻塞时间，包含命令发起与返回路径，适合诊断命令等待，不替代 TotalTime。

“冷”只描述进程在启动前不存在：`-S` 不清空文件页缓存、应用数据或服务端缓存。要求首次安装、首次登录或冷数据的场景必须另设，不能借 `-S` 推断。编译状态也要选一种协议并声明：保留各 App 经商店分发和正常使用形成的编译状态（回答用户现实体验），或对所有包执行同一安装与编译流程（如 `cmd package compile -m speed -f`，回答固定 ART 条件下的差异，但不能称为商店体验）。`speed-profile` 在未核验各 App profile 是否存在且质量相近时，会引入新的不一致。Android 12 起的系统 SplashScreen 早于应用首帧出现，但 TotalTime 的完成点仍是目标 Activity 窗口绘制，不表示数据可用。


<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q30 -->

**Q9: [learning] 性能回归测试的环境标准化有哪些关键约束？为什么 cmd thermalservice override-status 不能用来制造“未降频”基准？**

数值判定用专用真机：模拟器结果受宿主机、虚拟化、GPU 和存储影响，官方不建议用于性能判定，只适合安装、导航、断言与产物产出的冒烟检查。参考设备要固定序列号、build fingerprint、安全补丁、vendor 镜像、ART Mainline 版本、电池健康与实验室散热方式。目标应用应接近 release：`debuggable=false`、启用 `profileable`（允许 shell 在不打开调试时采集）、与发布一致的 R8 配置、包含 ProfileInstaller。eng 构建、debuggable 目标包和 method tracing 都会改变执行路径。显示模式与亮度保持一致或记录每帧实际 deadline，测动画时保留发布配置的 animation scale。`adb shell am kill-all` 只清理符合条件的后台进程，不能当作“设备已经干净”的证明，串行运行并禁止同设备并行测试。

温度用状态门控而不是统一摄氏度：不同设备的 thermal zone、传感器位置和厂商策略不同，`/sys/class/thermal/thermal_zone*/temp` 在量产设备上可能不可读。做法是为参考机建立冷机基线（记录室温与散热配置、从 `dumpsys thermalservice` 与 Perfetto 记录热状态、试运行确定降频前的状态范围），超出门控就暂停冷却并把样本标记为环境无效，保存每轮起止热状态。

`cmd thermalservice override-status` 只覆盖 `ThermalManagerService` 向 Framework 暴露的状态等级，不会关闭 Thermal HAL、内核 cpufreq、GPU 降频或厂商 thermal engine——它适合测试应用的 thermal callback，不能用来伪造“未降频”的测量环境。短路径（启动、短滑动）关心冷机状态。游戏、直播等持续负载要测热稳定态，预热数据单独保存、不混入稳态统计，稳态区间由预先定义的滑动窗口条件判定而不是看曲线后现选。
