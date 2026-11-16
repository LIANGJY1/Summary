# 批0 证据复验三——CPU3 竞争窗口与 event0 统计（2026-11-13）

前承 批0-证据包复验.md、批0-证据复验二-旧样本与waker.md。SQL 归档 过程记录/cpu3_window.sql。

## 一、CPU3 竞争窗口复算（InputReader R 7.951 ms 窗口，rel [6,787,426,919, 6,795,378,111]）

方法：sched 表对 cpu=3 取与窗口重叠的调度 slice，逐 slice 裁剪（MIN(ts+dur, w1) − MAX(ts, w0)）后按线程求和（重叠裁剪口径，此前会话纠错过的"只筛起点后 SUM 完整 dur"错误不再复用）。

| tid | 线程 | 裁剪后运行时 | 对应旧记录 |
|---|---|---|---|
| 29508 | getevent | 1,716,807 ns = 1.717 ms | ✅ 1.717 ms |
| 29506 | shell svc 29505 | 1,095,693 = 1.096 ms | ✅ 1.096（ADB svc） |
| 852 | slogmodem | 1,087,115 = 1.087 ms | ✅ 1.087 |
| 4867 | dds-recorder | 597,924 = 0.598 ms | ✅ 0.598 |
| 659 | composer@2.4-se | 577,884 = 0.578 ms | ✅ 0.578 |
| 29535 | MP4WtrVidTrkThr | 419,654 = 0.420 ms | ✅ 0.420（录屏） |
| 735/531 | fgecomservice@1（两线程） | 420,307 / 414,807 | ✅ 0.420 |
| 748 | TimerDispatch | 273,231 = 0.273 ms | （旧记录未列名） |
| 742 | vsync | 232,385 = 0.232 ms | （同上） |
| 684 | surfaceflinger | 189,885 = 0.190 ms | （同上） |
| 26884 | UsbFfs-worker | 166,308 = 0.166 ms | （同上） |
| 1789/1791 | gnss@1.1-servic（两线程） | 162,000 / 79,885 | （同上） |
| 26286 | kworker/u16:4 | 142,807 = 0.143 ms | （同上） |
| 2021 | binder:1909_3 | 72,616 = 0.073 ms | （同上，Launcher 侧 binder 线程） |
| 548/1792/1424/8737 | sensors/imu_vsock_send/pullR/kworker/3:0 | 92,423 / 90,192 / 59,846 / 59,423 | （同上） |

判定【已证】：CPU同时间分析.md 列名的七个线程全部 ns 级复现。追加【已证】：top-25 无 swapper（idle）——与"CPU3 该窗口无 idle"一致；InputReader 自身不在列表（该窗口处于 R 态等待，自洽）。因果边界维持既有声明：并发运行为真实观测，单线程致因未证；target_cpu=3 不证明只能跑 CPU3。

## 二、event0 帧间隔统计复核（event0_帧间隔.csv，1196 行=1 表头+1195 数据）

| 指标 | 旧记录 | 本次 awk 复算 | 判定 |
|---|---|---|---|
| 帧数/间隔数 | 1195/1194 | 1195/1194 | ✅ |
| 均值 | 16.617 ms | 16.617 | ✅ |
| 中位 | 16.347 | 16.346 | ✅（μs 级取整差） |
| P90 | 31.416 | 31.394 | ≈✅（百分位定义口径差：nearest-rank vs 插值） |
| P95 | 36.122 | 36.047 | ≈✅（同上） |
| P99 | 40.936 | 40.934 | ✅ |
| 最大 | 93.711 ms | 93.711 | ✅（位于 rel −3.9236，保留 trace 起点之前） |
| >25 ms | 209（17.5%） | 209（17.5%） | ✅ |
| <10 ms | 253（21.2%） | 253（21.2%） | ✅ |

CSV 结构【已证】：列 relative_trace_assuming_clock_s / event_time_s / interval_from_previous_ms / x_if_reported / y_if_reported；首行 interval 为空（无前帧）。空 X/Y 语义=本帧未报告坐标（既有口径维持）。

## 三、vg-kit.txt 复验（2026-11-14 补，批13 素材）

文件 29 行 644 字节，grep 全文：Display[0] avg_fps 序列 **16/39/34/30/31/40/35**（7 个样本，各标 (2s) 窗口），Display[1]/Display[2] 全部 avg_fps=0，末行 `signal_handler capture the signal`（工具由信号终止）。
- 判定【已证】：证据包"vg-kit Display0 序列 16/39/34/30/31/40/35"逐字复现；多屏拓扑中仅 Display0 有计数（另两屏 0）。
- 追加观察【未证】：7×2s=14s 覆盖量与 trace 可解析跨度 14.878662 s 大致同量级，但 vg-kit 无时戳输出，与 trace 时间轴不可对齐——不构成"同域"证明，批13 仅可作背景引用。
- 语义边界维持既有声明：avg_fps 的计数对象（scanout/RESOURCE_FLUSH 关联）已由旧会话逆向关联，≠物理新帧率，≠doFrame 计数。

## 四、未证边界
- P90/P95 的 22/75 μs 差异为百分位算法口径，不改结论；复核时若用其他实现允许同级差。
- CPU3 窗口之外各核的负载未复算（批14 按需扩展）；CPU0 slogmodem 14.728ms 与 CPU5 日志 CACHEABLE 50.608ms 为既有会话数据，本批未复验。
- 采集线程（getevent/shell svc/dds-recorder/MP4WtrVidTrkThr 等）占用的因果边界维持既有声明：按非采集线程分析并保留因果边界，不把采集开销当唯一解释。
