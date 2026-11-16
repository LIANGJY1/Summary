# 附录A · Perfetto 方法论索引

不同采集的 tid 和相对时间不能混用；查询前需确认样本和 start_ts。

> 与 00 篇的分工：00 篇术语表（§4）讲"是什么"（术语定义一次，本文只引用编号）；本文讲"怎么查"（查询口径、SQL、输出形态）。术语不重复定义。
> 工具：trace_processor_shell-2434129a3af4dc9e（`/home/liang/.local/share/perfetto/prebuilts/`，契约 :64）。SQL 摘录一律逐字复制自 `过程记录/` 存档文件；预期输出形态逐字核对自各批"查询原始输出"txt/csv 归档。
> 三态标注口径：本篇结论标 **已证**／**未证**／**盲区**（契约硬性标准 4）；数值另加复算标记——**[复算]** 表示批17 批内实际执行 trace_processor 并与归档输出逐字命中（执行记录见过程记录 §三），**[机制]** 表示引用既有篇的已证源码结论（行号见各篇），本篇不重新论证。

## 0. 复算对象与样本身份

| 对象 | 路径 | rel 基准（trace_bounds.start_ts） | 口径备注 |
|---|---|---|---|
| 最新样本 | `/home/liang/.codex/visualizations/2026/11/10/01a1c423-8831-7702-b674-030678d29b9b/applist-collector/captures/20261113-151358-vertical-1912967/trace.perfetto`（66,967,010 字节，契约 :62） | 6,073,694,444,196 ns（[复算] 批17 重跑命中） | 本文 §2 的相对时间口径 |
| 旧样本 | `/home/liang/Project/Reachauto/YaDi/yadea_master/.perf-analysis/out/finger.perfetto`（契约 :63） | 1,968,722,243,568 ns | BOOTTIME 口径需换算说明（契约 :78），见 §2.4 |

tid 只适用于各自采集，跨样本不可混用（00 篇 §3.3）。本文 §7 复算包只针对最新样本；旧样本复算入口见 §3f。

## 1. 数据模型：查询表与两条主键链

### 1.1 表关系与两条主键链

本节只讲本系列 94 份存档 SQL 实际用到的表与列（表名与列名按 trace_processor 输出原样引用）。表关系如下（"←"表示引用键）：

```text
process(upid, pid, name)
   ↑ upid
thread(utid, tid, name, upid, is_main_thread)
   ↑ utid                    ↑ utid            ↑ upid
thread_track(id, utid)    thread_state       process_counter_track(id, upid)
   ↑ track_id                                  ↑ track_id
slice(id, ts, dur, name, depth, track_id)     counter(ts, value, track_id)
sched(ts, dur, utid, cpu)                        ↑ track_id
                                              counter_track(id, name)
raw(ts, name, cpu, utid, arg_set_id)          thread_counter_track(id, utid)
trace_bounds(start_ts, end_ts)  stats(name, severity, value)  track(id, name)
__intrinsic_surfaceflinger_transactions(id, ts, vsync_id)
```

两条主键链（本系列全部查询的主干）：

1. **slice 链**：tid → `thread`（查 utid）→ `thread_track`（按 utid）→ `slice`（按 track_id）。线程轨上的 slice 查询全走这条链（§1.2）。
2. **thread_state/sched 链**：tid → `thread`（查 utid）→ `thread_state` 或 `sched`（直接按 utid 过滤，**不经 track**）。§1.3。

### 1.2 走通一次：从 tid=1909 找 deliverInputEvent

录-A 逐字摘自 `过程记录/批08-rvscroll.sql`（批08 归档）：

```sql
-- 批08 补查：RV Scroll slice 与主线程状态序列（rel 基准 start_ts=6073694444196）
select s.ts - 6073694444196 as rel_ts, s.dur, s.name, t.tid, t.utid
from slice s
join thread_track tt on s.track_id = tt.id
join thread t on tt.utid = t.utid
where t.tid = 1909
  and s.ts - 6073694444196 between 6830000000 and 6845000000
order by s.ts;
```

预期输出形态（逐字摘自 `过程记录/批08-trace原始输出.txt`，节选 3 行）：

```text
"rel_ts","dur","name","tid","utid"
6831006342,1734538,"deliverInputEvent src=0x1002 eventTimeNano=6080481854000 id=0x3b3b7f02",1909,74
6831161226,238231,"RV Scroll",1909,74
```

输出第三列 utid=74 即链的第一跳实算值：tid 1909（Launcher 主线程，进程 com.yadea.launcher）对应 utid 74。同链的 tid↔utid 实算对还有：tid 1488→utid 210（InputDispatcher，复验二 §一 waker 查询反查）、tid 1489→InputReader（批04 过程记录 §2）、tid 2079→utid 382（RenderThread，批10 q1 输出）——已证（各批输出归档）。`slice.depth` 列给出嵌套层级（0 为顶层），批06-q1 用它区分 dispatchMotionLocked（depth 0）与其子 slice findTouchedWindowTargetsLocked（depth 1，输出归档 `批06-查询原始输出.txt`）。

### 1.3 thread_state 与 sched：两张表的分工

- `thread_state`（列：ts, dur, state, cpu, blocked_function, io_wait, waker_utid, utid）给出**切出后的状态区间**：state 取 S/R/R+/D/Running（术语 #16，字符串来源 include/trace/events/sched.h:249-267，[机制] 内核详解14 §2.3-2.4）。查状态序列、S/D 窗口、waker、blocked_function（术语 #20）都查这张表，主键过滤只用 utid。
- `sched`（列：ts, dur, utid, cpu）给出 **CPU 占用片段**：每行是一段在某 cpu 上的调度占用。做"某 CPU 在某窗口内谁在跑"的重叠裁剪统计必须用 sched（要按 sched.cpu 分组），做"线程处于什么状态"用 thread_state。同一时间段的 Running 总时长两表一致（批14 Q10 用两表互证 Reader D 窗口内无 sched 行，输出归档 `批14-q10输出.txt`）。
- `thread_state.cpu` 列只在 Running 行记录所在核（S/R/R+ 行为 `[NULL]`，批14-q1 输出原样），批14-q1 用 Running 行定位主线程两个 R 窗口在 CPU2（输出归档 `批14-q1输出.txt`）——已证。

录-B 逐字摘自 `过程记录/批08-threadstate.sql`（批08 归档），thread_state 直查形态：

```sql
-- 批08 补查：Launcher 主线程（tid 1909）状态序列 窗口 6.8307–6.8340
select ts - 6073694444196 as rel_ts, dur, state, io_wait, waker_utid
from thread_state
where utid = (select utid from thread where tid = 1909)
  and ts - 6073694444196 between 6830700000 and 6834000000
order by ts;
```

预期输出形态（逐字摘自 `批08-trace原始输出.txt`）：

```text
"rel_ts","dur","state","io_wait","waker_utid"
6830764842,33923,"R","[NULL]",210
6830798765,1984307,"Running","[NULL]","[NULL]"
6832783072,9106616,"S","[NULL]","[NULL]"
```

NULL 在 trace_processor CSV 输出里显示为 `[NULL]`；S/R 行的 cpu 列为 `[NULL]`（批14-q1 输出，已证）——cpu 只记录运行性状态（Running/R+）所在核。

### 1.4 计数轨的存法：counter 表＋counter_track 表，归属另经 process/thread_counter_track

计数轨数据在 `counter`（ts, value, track_id），轨名在 `counter_track`（id, name）。**查询一律 `counter c JOIN counter_track ct ON c.track_id = ct.id`**（本系列 §3c 全部模式共用此 join）。轨的进程/线程归属不是 counter_track 自带的列，要再 join `process_counter_track`（按 upid）或 `thread_counter_track`（按 utid）。

录-C 逐字摘自 `过程记录/批11-q2-blast_counters.sql`（批11 归档），归属判定的查询形态：

```sql
-- 批11 q2: 全 trace counter 轨名扫描（找 BLAST 队列计数与 aq:* 类计数）
-- 判据: counter_track.name 含 aq/QueuedBuffer/PendingRelease/BLAST/Consumer 或
--       归属 com.yadea.launcher 进程的全部 counter_track 列名清单
SELECT DISTINCT ct.name AS counter_name, p.name AS process_name, t.tid
FROM counter_track ct
LEFT JOIN process_counter_track pct ON ct.id = pct.id
LEFT JOIN process p ON pct.upid = p.upid
LEFT JOIN thread_counter_track tct ON ct.id = tct.id
LEFT JOIN thread t ON tct.utid = t.utid
WHERE ct.name LIKE '%aq:%'
   OR ct.name LIKE '%QueuedBuffer%'
   OR ct.name LIKE '%PendingRelease%'
   OR ct.name LIKE '%BLAST%'
   OR ct.name LIKE '%Blast%'
   OR (p.name LIKE '%yadea.launcher%')
ORDER BY ct.name LIMIT 200;
```

轨名两种命名域（本 trace 实测，批06/07/11 输出归档）：`iq`/`oq:…`/`wq:…` 为 InputDispatcher 全局名（不带 tid 前缀，输出里的 oq/wq 轨名以连接通道名为后缀，归档输出中呈 40 字符截断形态，如 `oq:1b4998d com.yadea.launcher/com.yadea`）；`aq:pending:…`/`QueuedBuffer - VRI[…]#8BLAST#8` 为应用侧轨名（批11 归档输出）。本样本 `iq` 全程除堆积段外恒 0——34.738 ms 空档不是 mInboundQueue（术语 #29）排队的直接证据之一（已证，批06 断言 E 系列，06 篇 §4.5）。

### 1.5 raw 表与 EXTRACT_ARG：ftrace 原始事件

`raw`（ts, name, cpu, utid, arg_set_id）存 ftrace 原始事件行；各字段（pid/prev_state/target_cpu 等）不在列上，用 `EXTRACT_ARG(arg_set_id, '字段名')` 取。录-D 逐字摘自 `过程记录/批14-q7-switch_raw.sql`（批14 归档）：

```sql
-- 批14 Q7: 主线程 tid 756 在窗口 2 边界（rel 6,812,790,111 前后）的 raw sched_switch 原文
SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu,
       EXTRACT_ARG(raw.arg_set_id, 'prev_comm') AS prev_comm,
       EXTRACT_ARG(raw.arg_set_id, 'prev_pid') AS prev_pid,
       EXTRACT_ARG(raw.arg_set_id, 'prev_state') AS prev_state,
       EXTRACT_ARG(raw.arg_set_id, 'next_comm') AS next_comm,
       EXTRACT_ARG(raw.arg_set_id, 'next_pid') AS next_pid
FROM raw
WHERE raw.name = 'sched_switch'
  AND raw.cpu = 2
  AND raw.ts - 6073694444196 BETWEEN 6812700000 AND 6813000000
ORDER BY raw.ts LIMIT 40;
```

预期输出形态（逐字摘自 `批14-q7输出.txt`，节选）：prev_state 的数值形态（256＝抢占切出记 TASK_REPORT_MAX，0/1＝真实状态位值；映射规则见内核详解14 §2.3）是判 R+/R 双产生点的直接证据（[机制]，内核详解14 §2.6）：

```text
"rel_ts","event","cpu","prev_comm","prev_pid","prev_state","next_comm","next_pid"
6812343034,"sched_switch",2,"main",756,256,"PowerManagerSer",1379
6812790111,"sched_switch",2,"main",756,0,"UsbFfs-worker",26884
```

### 1.6 本系列用过的其余表

| 表 | 本系列用法 | 出处 |
|---|---|---|
| trace_bounds(start_ts, end_ts) | rel 基准；**只有 start_ts/end_ts 两列，没有 ts 列**——标量子查询写 `MAX(ts)` 不报错而返回 NULL，批11-q1 首版踩坑（该文件注释留痕） | §2.1 |
| stats(name, severity, value) | ftrace_setup_errors / traced_buf_bytes_overwritten / tokenizer errors 等解析统计（术语 #21/#22） | 批13-q3 |
| track(id, name) | 按轨名模糊盘点（GPU completion、PrevGpuFrameMissed 等），不区分轨类型 | 批13-q1/q4 |
| thread.is_main_thread | 旧样本批量统计里代替"逐 tid 核对"，取进程主线程 | 批16-q1q6 |
| __intrinsic_surfaceflinger_transactions(id, ts, vsync_id) | SF 事务表（内建表），按 vsync_id 对帧配对 | 批12-q8 |
| process(pid) | 按 pid 定位进程（SF pid 661、composer pid 659） | 批12-q2/q10 |

## 2. 时间口径

### 2.1 rel_ts 定义与换算

全系列 SQL 输出的 `rel_ts = ts − trace_bounds.start_ts`（ns 整数）。最新样本 start_ts = 6,073,694,444,196 ns（[复算] 批17 重跑 `SELECT start_ts FROM trace_bounds` 命中；可解析跨度 end_ts − start_ts = 14,878,661,682 ns，与会话记录.md:126 的 14.878662 s 一致）。

正文的 `00:00:06.xxxxxx` 口径（契约硬性标准 7）由会话记录固化：图 29 环节用户指出 trace 只有约 14 s，"先前用 6080 s 不易定位"，随后用户明确要求"后续直接使用 Perfetto 从 00:00:00 开始的相对刻度"（会话记录.md §"新 trace 的 Perfetto 相对时间换算"与 §"时间口径修正及用户偏好"，:124-131）。即 trace 起点记 `00:00:00.000000`，展示精度为 μs（6 位小数秒）：ns 值 6,776,444,919 展示为 `00:00:06.776445`。μs 展示值与 ns 原值的对应逐条见于批0-证据包复验.md 第一节的两列对照——已证。

换算检查式（批03 已用 CSV 首行核对）：`rel_s = event_time_s − 6073.694444`（event0_帧间隔.csv 的列名 relative_trace_assuming_clock_s 即此口径，内核详解03 §9 后果三引用）。

### 2.2 ns 精度与端点减法

SQL 输出保留 ns 整数；正文允许的近似只有一个：**端点减法允许 ±1 ns 取整差并逐处注明**（批15 数值纪律，15 篇篇头）。跨表数值对照时的精度实例：deliverInputEvent rel 6,831,006,342 对证据包 6.831007（尾数 1 μs 内取整差，批0-证据包复验.md 第一节已判 ✅）；两 doFrame 起点差 65,629,999 ns 逐 ns 闭合（65.630 ms 为其 μs 取整记法，1 ns 记法差）——已证。

### 2.3 BOOTTIME 与 MONOTONIC（批03 结论的引用）

- **[机制]** eventTime 的取时函数是 `input_get_timestamp`→`ktime_get()`；`ktime_get()` 读 CLOCK_MONOTONIC（kernel/time/timekeeping.c:818-835），不是 BOOTTIME。已证（内核详解03 §6.3/§9，引文 timekeeping.c:829）。
- **[机制]** Perfetto trace 主时钟是 BOOTTIME 域；event0（TOUCH 类）的 client 时钟被 EventHub/getevent 都设为 CLOCK_MONOTONIC（EventHub.cpp:446、getevent.c:316，内核详解03 §9）。MONO 与 BOOT 之差＝累计 suspend 时长（input.c:2044-2045 的 TK_OFFS_BOOT 换算，内核详解03 §9）。
- **[机制]** 契约 :70 的单点吻合（event0 约 00:00:06.787410 与 ID 0x3b3b7f02 的 eventTime 数值吻合）约束"该差值在吻合精度内不可分辨"；**全程逐点跨域校验未做＝未证**（00 篇 §3.1）。
- 落到查询层：rel 基准（start_ts）是 BOOTTIME 域读数，eventTime（slice 名内 eventTimeNano）是 MONO 域读数，两者相减（43.597 ms 的算法）依赖上述"差值不可分辨"结论——引用 43.597 ms 时保留该前提标注（内核详解03 B22、批15 §2 账 1）。

### 2.4 旧样本的口径换算

finger.perfetto 的 rel 基准 start_ts = 1,968,722,243,568 ns（会话记录.md §"可复现 SQL"、复验二 §二），Perfetto UI 对该样本按 BOOTTIME 口径展示 9 位小数秒（如 `00:00:09.769470630`，契约 :78）。旧样本数值引用规则：先换算到该样本自己的 rel 口径再比较，两个样本的 rel 值不可直接相减（00 篇 §3.8）。换算实例（复验二 §二）：deliverInputEvent slice 名内 eventTimeNano=1,978,514,733,000 → rel = 9,792,489,432 ns；eventTime→deliver = 8,168,467 ns ≈ 8.169 ms——与图09 数值逐字吻合——已证。

## 3. 核心查询模式汇编

七个模式。每个模式给：完整 SQL（逐字摘自存档）＋预期输出形态（逐字摘自归档输出）＋实战出处。SQL 里的窗口数字因查询目标而异，复用时按 §2.1 换算后替换。

### 3a. 模式一：线程状态窗口裁剪统计（重叠筛选 MIN/MAX 裁剪）

问题：统计"窗口 W 内某 CPU/某线程处于各状态的时长"。**错误做法**是 `WHERE ts BETWEEN w0 AND w1` 后直接 `SUM(dur)`——跨界状态行的完整 dur 被整段计入，总和可能大于窗口本身。该错误在会话阶段出现过一次并被纠正：初版 SQL"只选状态起点而对跨界状态未裁剪，8 ms 窗口错误统计 S 12.425 ms，结果总和大于窗口"（会话记录.md §"本次 Perfetto 操作实录"条目 10，图 17-18）。**正确口径是对每行先裁剪再求和**：`MIN(行尾, w1) − MAX(行首, w0)`。

录-1 逐字摘自 `过程记录/cpu3_window.sql`（批0 归档，CPU3 竞争窗口）：

```sql
WITH win AS (
  SELECT (SELECT start_ts FROM trace_bounds) + 6787426919 AS w0,
         (SELECT start_ts FROM trace_bounds) + 6787426919 + 7951192 AS w1)
SELECT 'cpu3_overlap_trimmed' AS tag, th.tid, th.name,
       SUM(MAX(MIN(s.ts + s.dur, w.w1), 0) - MAX(s.ts, w.w0)) AS runtime_ns,
       MIN(MAX(s.ts, w.w0)) AS first_start_rel_minus_offset,
       COUNT(*) AS slices
FROM sched s JOIN thread th USING(utid), win w
WHERE s.cpu = 3 AND s.ts < w.w1 AND s.ts + s.dur > w.w0
GROUP BY th.utid ORDER BY runtime_ns DESC LIMIT 25;
```

SQL 语义注：SQLite 的两参数 `min(a,b)`/`max(a,b)` 是标量函数（取小/取大），与聚合 `MIN(...)`/`SUM(...)` 同名不同形；`MAX(…, 0)` 为防负数兜底（满足 WHERE 的行裁剪差值非负，该兜底不改变求和结果）。该口径的可验证后果：批0 用它复算出 CPU3 在 InputReader R 7,951,192 ns 窗口的七线程裁剪值（getevent 1,716,807 / shell svc 1,095,693 / slogmodem 1,087,115 / dds-recorder 597,924 / composer 577,884 / MP4WtrVidTrkThr 419,654 / fgecomservice 420,307 ns），与会话记录.md §"同时间 CPU 占用调查"的独立统计逐项一致（批0-证据复验三.md 第一节对照表）；批17 [复算] 重跑 top-10 全部命中（过程记录 §三）。已证。

预期输出形态（批17 [复算] 对录-1 原文实跑，节选 3 行；数值与会话记录.md §"同时间 CPU 占用调查"及 `批0-证据复验三-CPU窗口与event0统计.md` 第一节对照表逐项一致）：

```text
"tag","tid","name","runtime_ns","first_start_rel_minus_offset","slices"
"cpu3_overlap_trimmed",29508,"getevent",1716807,6080484756307,2
"cpu3_overlap_trimmed",29506,"shell svc 29505",1095693,6080482291422,4
"cpu3_overlap_trimmed",852,"slogmodem",1087115,6080483118653,1
```

列名复核点（存档原样，复用时注意）：`first_start_rel_minus_offset` 的列名与取值语义不符——`MAX(s.ts, w.w0)` 是绝对 ns，行起点晚于 w0 时该列输出的是 s.ts 绝对值（如 getevent 行 6,080,484,756,307），不是 rel 值；复算取数用 runtime_ns 与 slices 两列。

同模式的第二批变体：批14-q2（主线程 CPU2 双 R 窗口逐线程裁剪，含 window1/window2/totals 三段）、批14-q13（Launcher S 窗口 CPU4 线程构成，**[复算]** 重跑命中 swapper 3,617,928 ns/26 段——"S 窗口 CPU4 有 idle"的直接证据，内核详解14 §3.2）、批16-q12q13（旧样本 51.72 ms 窗口主线程状态裁剪）。三份 SQL 见 §6 索引。

### 3b. 模式二：slice 按线程名/内容模糊定位（命名前缀坑）

问题：按 slice 名找事件。trace 内 slice 名不是裸函数名，前缀与参数随打点方变化；模糊匹配的边界必须先核对命名。

**命名前缀坑（已证，批0-证据包复验.md §三留痕）**：本样本 doFrame slice 的名字是 `Choreographer#doFrame <vsyncId>`（Choreographer.java:749 拼名，术语 #14）。首轮 `s.name LIKE 'doFrame%'` 零行，改 `LIKE '%doFrame%'` 命中——复核代理注意：trace 内不同源 slice 前缀不同，模糊匹配前后缀需逐个核对。本样本实测的命名形态：

| slice 名形态 | 实例（归档输出原样） | 打点方 |
|---|---|---|
| `Choreographer#doFrame 155812` | 批08 输出（`批08-trace原始输出.txt`；批0 首轮同名查询段零行＝前缀坑出处） | Choreographer.java:749 |
| `Choreographer#scheduleVsyncLocked` / `Choreographer#onVsync 155812` | 批08 输出 | 同链 |
| `deliverInputEvent src=0x1002 eventTimeNano=6080481854000 id=0x3b3b7f02` | 批0/批08 输出 | ViewRootImpl.java:9070（批08 §6.2） |
| `notifyMotion(id=0x3b3b7f02)` | 批0 输出 | InputListener.cpp:340-345（术语 #8） |
| `prepareDispatchCycleLocked(inputChannel=…, id=0x3b3b7f02)` | 批0 输出 | InputDispatcher.cpp（批07 §3.3） |
| `RV Scroll`（无前缀无参数） | 批08 输出 | RecyclerView.java:1661（TRACE_SCROLL_TAG :216） |

录-2 逐字摘自 `过程记录/批16-q7q11-finger补充.sql` 的 Q10 块（批16 归档）——从 slice 名字符串里解析 eventTimeNano 的查询形态（substr/instr 取数，cast 后做差分统计）：

```sql
-- B16-Q10 eventTime deltas from deliverInputEvent names
with ev as (
  select cast(replace(substr(s.name, instr(s.name,'eventTimeNano=')+15), ')','') as number) et
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.name like 'deliverInputEvent%'),
 d as (select et, lead(et) over (order by et) - et gap from ev)
select count(*) n, round(avg(gap)/1e6,2) mean_ms, round(sum(gap<=17e6)*100.0/count(*),1) pct_le17, sum(gap is not null) total
from d where gap is not null and gap>0;
```

预期输出形态（批17 [复算] 对 finger.perfetto 实跑，与批16 归档一致）：`"n","mean_ms","pct_le17","total"` 一行四列。此模式同时是 §3f 旧样本链的取数件之一。

### 3c. 模式三：计数轨查询（iq/oq/wq/aq/VSYNC-app/sf）

问题：查队列深度计数与 VSYNC 计数。数据在 `counter`＋`counter_track`（§1.4），值是采样点（事件发生时写入的瞬时值），不是连续曲线；相邻采样点之间保持前值。判读规则：**计数轨只给采样点的值与时刻，两采样点之间的持值时长是推断，不是数据**（批06-q4 输出判读口径）。

录-3 逐字摘自 `过程记录/批07-q1-oqwq_tracks.sql`（批07 归档）——计数轨存在性盘点：

```sql
-- 批07 Q1：oq/wq 计数轨存在性（最新样本 20261113-151358-vertical-1912967）
-- 目标：InputDispatcher.cpp:6163-6177 的 traceOutboundQueueLength("oq:%s")/traceWaitQueueLength("wq:%s")
--       在本 trace 中是否有对应 counter_track。
select ct.id, ct.name, count(c.id) as samples
from counter_track ct
left join counter c on c.track_id = ct.id
where ct.name like 'oq%' or ct.name like 'wq%' or ct.name like '%iq%'
group by ct.id, ct.name
order by ct.name;
```

预期输出形态：`"id","name","samples"` 多行（本 trace 六条 oq/wq 轨＋iq 轨全部存在，批07 查询原始输出归档；批07 以此把批06 的 oq/wq 未证项升级为已证）。

录-4 逐字摘自 `过程记录/批06-q4-iq_counter.sql` 第一块（批06 归档）——窗口内采样值序列：

```sql
-- Q4: iq counter 样本（6.77–6.84s 窗口）与 utid 52 解析
SELECT ct.name, c.ts - 6073694444196 AS rel_ts, CAST(c.value AS INT) AS iq
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'iq' AND c.ts - 6073694444196 BETWEEN 6770000000 AND 6840000000
ORDER BY c.ts;
```

预期输出形态（逐字摘自 `批06-查询原始输出.txt` Q4 段）：

```text
"name","rel_ts","iq"
"iq",6771783765,0
"iq",6830307188,1
"iq",6830457303,0
```

本批 [复算] 扩窗重跑（§7 f02 第 2 段）另命中 `oq:…`/`wq:…` 轨的采样值序列（如 rel 6,830,811,995 `wq:1b4998d …` =1；rel 6,838,299,572 `wq:1b4998d …` =0——waitQueue（术语 #30）擦除采样点，批07 §4.4 配对）。VSYNC 类计数轨查询用同一 join，轨名 `VSYNC-sf`/`VSYNC-app`/`HW_VSYNC_0`（批12-q7，本批 [复算] 命中 HW_VSYNC_0 rel 6,707,532,572 值 1；三轨可见性把 00 篇 #13 的未证项升级为已证，批12 §2.2）。aq/QueuedBuffer 类见录-C（§1.4）。

### 3d. 模式四：waker 直连（thread_state.waker_utid join thread）

问题：查"谁唤醒了该线程"。`thread_state.waker_utid` 是 trace 数据直接记录的唤醒者 utid（来源 sched_waking/sched_wakeup 事件，术语 #17/#18）；UI 层的"maybe interrupt"是展示标记，SQL 层直连即得精确值——不需要依赖"可能打断"推断（批0-证据复验二.md §一的口径区分）。

录-5 逐字摘自 `过程记录/批06-q6-main_states.sql` 第一块（批06 归档）：

```sql
-- Q6: 主线程 tid=756 与 binder:756_2 tid=824 的 thread_state（6.795–6.832s，主线程含 waker）
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.waker_utid, wt.name AS waker_thread, wt.tid AS waker_tid
FROM thread_state ts LEFT JOIN thread wt ON ts.waker_utid = wt.utid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=756)
  AND ts.ts - 6073694444196 BETWEEN 6795000000 AND 6832500000
ORDER BY ts.ts LIMIT 30;
```

注意 WHERE 里的 tid=756 是 system_server 主线程（InputFilter 回注执行线程，批06 §4.7），不是 Launcher tid 1909——同一模式换 utid 子查询即可复用。

预期输出形态（批17 [复算] 对 tid 1909 执行，等价改法见 §7 步骤 3；与 `批0-证据复验二.md` §一数值一致）：

```text
"rel_ts","dur","state","waker_utid","waker_tid","waker_thread","waker_proc"
6830764842,33923,"R",210,1488,"InputDispatcher","system_server"
```

即 Launcher 主线程 50.928 ms S 结束时刻（rel 6,830,764,842）的 waker＝InputDispatcher tid 1488（utid 210）——已证（批0 复验二 §一，台账批0 行"Launcher S waker=InputDispatcher tid1488 直证"）。另一方向（谁被唤醒）用批14-q6：raw 表 `sched_waking` 的 EXTRACT_ARG(pid) 过滤，输出形态见 `批14-q6输出.txt`（kworker/4:1 唤醒 pid 1489 target_cpu=3；tid 1489（InputReader）唤醒主线程（pid 756）target_cpu=2——已证）。

### 3e. 模式五：事件 ID 全链追踪（LIKE '%0x…%' 跨线程）

问题：把同一输入事件在各线程的 slice 串起来。**事件 id 是 32 位标识（2 位来源段＋30 位随机段，批04 §1 概念 1），slice 名是它唯一的 trace 载体**；SQL 按 `s.name LIKE '%0x3b3b7f02%'` 跨线程匹配。三态边界：**ID 文字匹配是证据；时间相近不自动证明因果**（会话记录.md 图 20-21 条目的口径，全系列沿用）。

录-6 逐字摘自 `过程记录/verify_evidence_pack.sql` 第四块（批0 归档）：

```sql
SELECT 'input_slices_window' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name, th.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread th ON th.utid = tt.utid
WHERE th.tid IN (1909, 1489, 1488)
  AND (s.name LIKE '%0x3b3b7f02%' OR s.name LIKE '%otifyMotion%' OR s.name LIKE '%prepare%' OR s.name LIKE '%eliverInputEvent%')
  AND s.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6700000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY s.ts;
```

预期输出形态（逐字摘自 `批0-证据复验-原始输出.txt` input_slices_window 段，节选 3 行——一条事件在 tid 1489/1488/1909 三线程轨上的排列）：

```text
"tag","rel_ts","dur","name","tid"
"input_slices_window",6795838649,6577,"notifyMotion(id=0x3b3b7f02)",1489
"input_slices_window",6830576688,5846,"prepareDispatchCycleLocked(inputChannel=1b4998d com.yadea.launcher/com.yadea.launcher.function.applist.AppListActivity (server), id=0x3b3b7f02)",1488
"input_slices_window",6831006342,1734538,"deliverInputEvent src=0x1002 eventTimeNano=6080481854000 id=0x3b3b7f02",1909
```

批17 [复算] 重跑同窗命中 33 行（0x3b3b7f02 全链＋同窗其他 ID），与归档逐点一致。同 ID 三条 notifyMotion 的机制解释（监听器链上三个 QueuedInputListener 实例各打一次点）见批04 §7.3——[机制] 引用，本篇不复证。deliverInputEvent 名内 eventTimeNano=6080481854000 减 start_ts 得 6,787,409,804＝证据包"约 00:00:06.787410"的 ns 实值（批0-证据包复验.md 第一节）——已证。

### 3f. 模式六：旧样本对照查询（tid 发现→S 窗口→slice）

问题：在 finger.perfetto 等旧样本里复算。旧样本 tid 不能从新样本带过去（00 篇 §3.3），**发现顺序固定为三步**（批0-证据复验二.md §二的操作顺序）：

1. **tid 发现**：进程名 → 主线程 tid。录-7 逐字摘自 `过程记录/批10-q5-old_threads.sql`（批10 归档，旧样本 RenderThread tid 发现）：

```sql
-- 批10 q5：旧样本 finger.perfetto 的 DrawFrames 与 doFrame 配对（图03/04 数值复验）
-- ① Launcher 进程线程（找 RenderThread tid）
SELECT t.utid, t.tid, t.name AS thread_name FROM thread t
JOIN process p USING (upid) WHERE p.name LIKE '%yadea.launcher%' ORDER BY t.tid;
```

另一路 tid 发现：由 0x37ee8b11 slice 名的 tid 反查 Reader 1472/Dispatcher 1471（复验二 §二"由 0x37ee8b11 slice 的 tid 反查，与图01/21 标注一致"）——已证。批量统计路径用 `t.is_main_thread=1`（录-2 的 WHERE 形态）。

2. **S 窗口**：thread_state 按该 tid 查 >25 ms 的 S/R+ 长窗（复验二 §二"31.009 ms S 定位"），窗口端点 rel 9,769,470,630→9,800,479,861。
3. **slice 链**：按事件 ID（0x37ee8b11）与 dur 精确区间双条件定位配对件。录-8 逐字摘自 `过程记录/批10-q5d-old_drawframes_wide.sql`（批10 归档，DrawFrames 2,280,038 ns 的精确复验）：

```sql
-- 批10 q5d：旧样本精确 dur=2280038 的 DrawFrames（放宽窗口 2280038±5000）
SELECT s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2107 AND s.name LIKE 'DrawFrames %'
  AND s.dur BETWEEN 2275038 AND 2285038
ORDER BY s.ts LIMIT 10;
```

预期输出形态：`"rel_ts","dur","name"` 行，dur 值落在 ±5,000 ns 区间（批10-查询原始输出归档；2,280,038 与图04 数值逐字命中——已证）。旧样本查询的完整 SQL 集见 §6 批16 组（批16-q0~q17，八个文件对 11 份旧 trace 28 次执行）；批17 [复算] 对 finger.perfetto 重跑 `批16-q1q6-finger主查询.sql` 命中归档值（主线程 S 15,452.0 ms／doFrame 732 帧 mean_gap 24.56 ms／notifyMotion 3,252 条，过程记录 §三）。

### 3g. 模式七：raw ftrace 事件面盘点（"未采到≠没发生"判据）

问题：判定"某类事件在本 trace 里有没有数据"。两级查询：`raw` 表按 name 分组（ftrace 原始层），`slice`/`counter_track` 按名分组（上层对象层）。

录-9 逐字摘自 `过程记录/批13-q5-raw事件面.sql`（批13 归档）：

```sql
-- 批13 q5: raw 表全部 ftrace 事件名分布（若该版本 trace_processor 支持），用于刻画采集配置面
SELECT name, COUNT(*) AS n FROM raw GROUP BY name ORDER BY n DESC LIMIT 60;
```

录-10 逐字摘自 `过程记录/批14-q11-sched_event_counts.sql`（批14 归档）：

```sql
-- 批14 Q11: 全 trace sched 相关 raw 事件计数（采集事件面）
SELECT raw.name AS event, COUNT(*) AS n
FROM raw
WHERE raw.name IN ('sched_waking','sched_wakeup','sched_switch','sched_blocked_reason','sched_wake_idle_without_ipi','sched_wakeup_new')
GROUP BY raw.name
ORDER BY n DESC LIMIT 15;
```

预期输出形态（逐字摘自 `批14-q11输出.txt` 全文）：

```text
"event","n"
"sched_switch",424434
"sched_waking",280053
"sched_blocked_reason",68074
```

**判据（本系列统一口径）**：raw 表按 name 分组无某事件名 → 该事件类在本次采集配置下未采到（采集面事实）；**不能推及"设备上未发生"**——会话记录.md §"原始 trace 输入和系统调用数据可用性"的原话："该结果不等于设备没有事件"；批13 对 drm/virtio 事件穷尽 11 种 raw 事件名后判"已证未采"而非"未发生"（内核详解13 §3.5 可观测面；台账批13 行）。syscall/irq 在本 trace 无数据（00 篇 §3.7 盲区清单的依据）即由此判据得出——盲区声明，不是否定声明。

## 4. trace_processor 操作要点

1. **调用形态**：`trace_processor_shell <trace文件> -q <SQL文件路径>`（契约 :64）。`-q` 接的是**文件路径**，不是 SQL 字符串；stdin 交互模式不用于本系列（全部查询走文件，便于留痕）。
2. **加载耗时**（批17 [复算] 实测，最新样本 66.97 MB）：`Trace loaded: 66.97 MB in 0.83s (80.9 MB/s)`，单文件查询合计 47 ms 量级（f01 五段实测）；stderr 输出加载与查询耗时，stdout 只有 CSV 结果——重定向时 `2>/dev/null` 不丢数据。
3. **多语句文件**：一个 .sql 文件可含多个 SELECT，按序执行、各自输出一段表头＋数据；块间无分隔行。本系列的分块标记做法是在两条 SELECT 之间插一行 `SELECT '---xxx---' AS marker;`（批06-q2/q3、批14-q2 的输出里显示为 `"marker"` 表头＋一行标记值，归档原样）。
4. **输出即 CSV**：stdout 为标准 CSV（首行列名、双引号包裹、逗号分隔；NULL 显示 `[NULL]`）。批09/批11 的大结果集用 `> 文件.csv` 落盘（`批09-q1-doFrame全序列输出.csv` 508 行数据、`批11-q1-blast_slices输出.csv` 8,126 行——归档即此产物）。
5. **trace_bounds 坑（已证，批11-q1 注释留痕）**：该表只有 start_ts/end_ts，没有 ts 列；标量子查询写成 `(SELECT MAX(ts) FROM trace_bounds)` 不报错、静默返回 NULL，整列 rel_ts 变 NULL——输出"只有 1 行 NULL"的表象。正确写法 `(SELECT start_ts FROM trace_bounds)`。
6. **LIMIT 纪律**：全表扫描类查询必须带 LIMIT（批11-q1 的 `LIMIT 20000`、批12-q2 全量输出 107,847 字节归档）。无 LIMIT 的 `GROUP BY name` 类统计（批13-q5 `LIMIT 60`）同样截断输出——截断行数写进归档文件名或注释。
7. **CAST 与数值形态**：counter 的 value 列以浮点形式输出（`1.000000`），计数判读时 `CAST(c.value AS INT)`（录-4 的写法）；窗口函数 `lead() over (order by …)` 用于相邻间隔统计（录-2、批16-q2/q3）。
8. **常用表清单**：§1 数据模型图中列出的查询表——即本系列 94 份存档 SQL 触碰过的全部表集合；未列出的表本系列未验证存在性与列义。

## 5. Perfetto UI 操作要点（穿插式教学汇总索引）

UI 操作在既有会话中逐图教学（截图索引见 `会话持续记录/截图索引.md`，操作实录全文在 `会话持续记录/会话记录.md` §"本次 Perfetto 操作实录"，条目 1-24 对应图 01-24）。本节每项一句话＋"详见哪篇哪节"；本篇不重复机制论证。

| UI 操作/读法 | 一句话口径 | 详见 |
|---|---|---|
| 展开进程找线程轨 | 同一线程有两行轨：上为线程状态条（thread_state 渲染），下为 slice 轨；不是两个主线程 | 会话记录条目 1（图01）；内核详解14 §2.4 |
| 线程状态条色块 | S/R/R+/D/Running 色块宽度即状态区间时长；选中 slice 后下方面板显示该 slice 期间的状态构成（Running/Sleeping/Runnable 分列） | 会话记录条目 3（图03）；术语 #16；内核详解14 §2 |
| waker 箭头 | 状态条上的唤醒关联箭头标 maybe interrupt——waker 当时在运行，可能打断了睡眠线程；标记不证明代码调用者也不证明因果 | 术语 #17/#18；内核详解14 §4.3；会话记录条目 8/13（图10-12/22-23） |
| SQL 直证 waker | UI 箭头的精确化：thread_state.waker_utid 直连 thread 表（§3d） | 批0-证据复验二.md §一 |
| slice 选中面板 Duration | Duration 是单任务墙钟时长，不是触摸频率、不是相邻事件间隔、不等于 CPU 时间 | 会话记录条目 2/4（图02/04）；00 篇 §5"切片 30 ms"条 |
| 双线程重叠不可相加 | doFrame（主线程）与 DrawFrames（RenderThread）时间重叠，两个 Duration 不可相加为端到端耗时 | 批10 §5；会话记录条目 4（图04） |
| Expected/Actual Timeline | 帧时间线面板；A13 输入链的实际实体是时刻五元组＋GraphicsTimeline 两字段，"Expected/Actual"是 A12 之前 FrameTimeline 的表述 | 批07 §7.1-7.2；术语 #28；会话记录条目 5（图05-06） |
| FrameTimeline jank 标签 | Jank type 位掩码转出的字符串（Prediction Error、App Deadline Missed 等），同一帧可持多个标签，不可相加；单凭黄色/标签不能断言物理卡顿 | 术语 #27/#28；批12 §3.2a；会话记录条目 5（图05-06） |
| doFrame token 读法 | doFrame slice 名尾部数字是 vsyncId，不是连续帧序号；起点差 51.716115 ms（约三个 60 Hz 周期）不直接证明物理丢两帧 | 术语 #15；批09 §2.1；会话记录条目 6（图07-08） |
| 同 ID 三条 notifyMotion 的排列读法 | 三条 slice 在同一高度先后排列（同层），非嵌套；此前"嵌套"推测被截图推翻，三次调用的机制见批04 §7.3 | 会话记录条目 14（图24）；批04 §7.3；批06 §4.9 |
| filterInputEvent 与 ID 的关系 | 单凭 slice 名不证明与某 ID 匹配（该 slice 名不带 ID） | 会话记录条目 9/13（图13-16）；批06 §4.5 |
| UI 目测不能替代 SQL 裁剪 | 窗口统计必须重叠裁剪（§3a），UI 上目测色块长度不构成统计 | 会话记录条目 10（图17-18 教训） |

## 6. 本系列 SQL 索引总表

`过程记录/` 下全部 .sql 存档（R7 全量复核盘点共 94 个：既有 90 个＋批17 复算包 4 个）。"关键表"列给该文件触碰的主表。批0 的 waker.sql／old_finger.sql／old_finger2.sql 三个文件在批0-证据复验二.md:3 声明归档；批17 盘点时目录无此三文件（缺失事实当时已证）；协调者已于 2026-11-14 补拷入库（复验二补记／台账批17 行"漏项补齐"，git 未跟踪），R7 全量复核实跑复现复验二 §一/§二 结果（waker utid210→tid1488；旧样本 0x37ee8b11 notify→prepare 5,167,308／prepare→deliver 585,192／eventTime→deliver 8,168,467 ns）——缺失事实已解除，见 §8.1。批04 的查询内联于 `批04-过程记录.md` §2（无独立 .sql 文件）。

| 文件 | 批 | 目的一句话 | 关键表 |
|---|---|---|---|
| verify_evidence_pack.sql | 0 | 证据包六段复验（S/doFrame/事件链/Reader/Dispatcher 状态） | thread_state, slice, thread |
| doframe.sql | 0 | doFrame 与 vsync slice 的宽松匹配（前缀坑出处） | slice |
| cpu3_window.sql | 0 | CPU3 R 窗口重叠裁剪统计（录-1） | sched, thread |
| waker.sql | 0 | Launcher tid1909 醒点 waker 直连（复验二 §一；R7 恢复归档） | thread_state, thread |
| old_finger.sql | 0 | 旧样本 bounds＋launcher 主线程发现（复验二 §二；R7 恢复归档） | trace_bounds, thread, process |
| old_finger2.sql | 0 | 旧样本 utid62 长 S＋0x37ee8b11 全链 slice（复验二 §二；R7 恢复归档） | thread_state, slice, thread_track |
| 批06-q1-dispatcher_slices.sql | 06 | tid1488 窗口 slice 序列（含 depth） | slice |
| 批06-q2-reader_slices.sql | 06 | tid1489 窗口 slice＋全 trace filter/inject 匹配 | slice |
| 批06-q3-ss_window.sql | 06 | system_server Running 排名＋Dispatcher waker＋iq 轨存在性 | thread_state, counter_track |
| 批06-q4-iq_counter.sql | 06 | iq 采样序列＋utid52 解析＋非零样本（录-4） | counter, counter_track |
| 批06-q5-main_binder.sql | 06 | tid756/824 窗口 slice（回注执行者） | slice |
| 批06-q6-main_states.sql | 06 | tid756/824 状态序列含 waker（录-5） | thread_state, thread |
| 批06-q7-old_sample.sql | 06 | 旧样本 iq 采样（9.79-9.82s） | counter, counter_track |
| 批06-q8-iq_drain.sql | 06 | iq 6.83-6.95s 完整序列（堆积排空） | counter |
| 批07-q1-oqwq_tracks.sql | 07 | oq/wq 轨存在性（录-3） | counter_track, counter |
| 批07-q2-oqwq_samples.sql | 07 | oq/wq 分发窗口采样值 | counter |
| 批07-q3-drain_slices.sql | 07 | 排空段 prepare 序列 | slice |
| 批07-q4_dispatcher_states.sql | 07 | tid1488 状态序列（wq 擦点归属） | thread_state |
| 批07-q5_publish_slices.sql | 07 | 发布窗口全 slice（publish 嵌套查证） | slice |
| 批07-q6_finish_slices.sql | 07 | 回执窗口全 slice | slice |
| 批07-q6b_narrow.sql | 07 | 窄窗复验（6,838,0xx μs） | slice |
| 批07-q6c_ss_all.sql | 07 | system_server 全线程窄窗 slice | slice |
| 批08-rvscroll.sql | 08 | tid1909 窗口 slice＋RV Scroll（录-A） | slice, thread_track |
| 批08-threadstate.sql | 08 | tid1909 状态序列含 io_wait（录-B） | thread_state |
| q1.sql | 09 | doFrame 全序列（508 帧 CSV 产物） | slice |
| q2.sql | 09 | doFrame 155812 窗口全子 slice | slice |
| q3.sql | 09 | measure/layout/draw 等 slice 名计数 | slice |
| q4.sql | 09 | RV Scroll/scheduleVsyncLocked/onVsync 计数 | slice |
| q5.sql | 09 | 五关键帧窗口子 slice | slice |
| q6.sql | 09 | onVsync/input/animation/commit 子查询计数 | slice |
| q7.sql | 09 | doFrame 155803 止→S 起窗口 slice | slice |
| 批10-q1-launcher_threads.sql | 10 | Launcher 线程表（RenderThread tid 发现） | thread, process |
| 批10-q2-drawframes.sql | 10 | DrawFrames 前 60 条（ns 端点） | slice |
| 批10-q3-drawframes155812.sql | 10 | tid2079 窗口全 slice（RT 序列） | slice |
| 批10-q4-counts.sql | 10 | DrawFrames/doFrame/syncFrameState/flush 计数对照 | slice |
| 批10-q5-old_threads.sql | 10 | 旧样本线程发现（录-7） | thread, process |
| 批10-q5b-old_drawframes.sql | 10 | 旧样本 DrawFrames dur 区间定位 | slice |
| 批10-q5c-old_doframe.sql | 10 | 旧样本 doFrame dur≈531,807 定位 | slice |
| 批10-q5d-old_drawframes_wide.sql | 10 | dur=2,280,038±5,000 精确复验（录-8） | slice |
| 批10-q5e-old_doframe340845.sql | 10 | vsyncId 340845 doFrame 精确端点 | slice |
| 批10-q6-swindow_rt.sql | 10 | S 窗口内 tid2079 全 slice（RT 零 slice 判定） | slice |
| 批10-q7-postS_rt.sql | 10 | S 结束→DrawFrames 155812 之间 tid2079 slice | slice |
| 批11-q1-blast_slices.sql | 11 | Launcher 全量 BLAST/transaction/buffer slice 扫描 | slice（trace_bounds 坑注释） |
| 批11-q2-blast_counters.sql | 11 | 计数轨归属扫描（录-C） | counter_track＋process/thread_counter_track |
| 批11-q3-rt_context.sql | 11 | tid2079 父链＋全 trace buffer 操作计数 | slice |
| 批11-q4-rt_key_window.sql | 11 | 关键窗口 buffer 操作与 doFrame/postAndWait 配对 | slice |
| 批11-q5-counter_values.sql | 11 | 四条计数轨关键窗口取值序列 | counter |
| 批12-q1-sf线程表.sql | 12 | SF/composer 进程线程表 | thread, process |
| 批12-q2-sf窗口slice.sql | 12 | pid661 窗口全 slice（6.7-6.9s） | slice |
| 批12-q3-合成树.sql | 12 | tid661/685/746 合成段 slice 树 | slice |
| 批12-q4-binder事务.sql | 12 | SF binder 线程事务 slice | slice |
| 批12-q5-计数轨.sql | 12 | VSYNC/VRI/QueuedBuffer 轨样本 | counter |
| 批12-q6-sf节奏.sql | 12 | tid661 commit/composite/onComposerHalVsync 序列 | slice |
| 批12-q7-vsync计数.sql | 12 | VSYNC-sf/app/HW_VSYNC_0 三轨窗口值 | counter |
| 批12-q8-事务表.sql | 12 | __intrinsic_surfaceflinger_transactions 按 vsync_id | 内建事务表 |
| 批12-q9-sf线程slice统计.sql | 12 | upid=50 全线程 slice 计数 | slice |
| 批12-q9b-tid684.sql | 12 | tid684 slice 明细（TransactionTracing 判定） | slice |
| 批12-q10-hwc线程.sql | 12 | upid=49（composer）线程计数 | slice |
| 批12-q10b-hwc窗口.sql | 12 | composer 窗口 slice（CommitFrame） | slice |
| 批12-q11-窗口线程统计.sql | 12 | SF 窗口内逐线程计数 | slice |
| 批12-q12-类名计数.sql | 12 | commit*/composite* 类名归并计数 | slice |
| 批12-q13-155810骨架.sql | 12 | frame 155810 期 SF 骨架（depth≤4） | slice |
| 批12-q14-155818骨架.sql | 12 | frame 155818 期 SF 骨架 | slice |
| 批13-q1-drmvirtio轨.sql | 13 | drm/virtio/gpu 类轨名盘点 | track |
| 批13-q2-slice名.sql | 13 | virtio/drm/flush/scanout 类 slice 名盘点 | slice |
| 批13-q3-ftrace错误与事件面.sql | 13 | stats 错误计数＋sched/counter/track 规模 | stats, track |
| 批13-q4-gpu完成轨明细.sql | 13 | GPU completion/PrevGpuFrameMissed 明细＋sched 规模 | slice, track, sched |
| 批13-q5-raw事件面.sql | 13 | raw 全事件名分布（录-9） | raw |
| 批14-q1-main_thread_cpu.sql | 14 | tid756 状态序列含 cpu 列 | thread_state |
| 批14-q2-main_windows_overlap.sql | 14 | CPU2 双 R 窗口逐线程裁剪（模式一变体） | sched |
| 批14-q3-window1_allcpu.sql | 14 | 窗口1 全 CPU swapper＋CPU2 明细 | sched |
| 批14-q4-cpu2_gapcheck.sql | 14 | CPU2 事件空洞检查 | sched |
| 批14-q5-cpu2_late_window.sql | 14 | CPU2 后半窗口逐 slice | sched |
| 批14-q6-waking_raw.sql | 14 | sched_waking/wakeup 复核（谁唤醒谁） | raw |
| 批14-q7-switch_raw.sql | 14 | sched_switch 原文（prev_state，录-D） | raw |
| 批14-q8-prev756_switches.sql | 14 | tid756 全部切出的 prev_state 形态 | raw |
| 批14-q9-preempt_wake.sql | 14 | 两次抢占切出前后 1ms 唤醒事件 | raw |
| 批14-q10-reader_D_cpu.sql | 14 | Reader D 窗口状态与 sched 互证 | thread_state, sched |
| 批14-q11-sched_event_counts.sql | 14 | sched raw 事件计数（录-10） | raw |
| 批14-q12-launcher_s_cpu.sql | 14 | tid1909 S 窗口所在 CPU | thread_state |
| 批14-q13-cpu4_s_window.sql | 14 | S 窗口 CPU4 线程构成（swapper 3,617,928） | sched |
| 批14-q14-s_rows_blocked_function.sql | 14 | S 行 blocked_function 全 NULL 验证 | thread_state |
| 批16-q0-进程清单.sql | 16 | yadea 系进程清单（is_main_thread 计数） | thread, process |
| 批16-q1q6-finger主查询.sql | 16 | 旧样本六段主查询（状态/doFrame 间隔/deliver 间隔/slice 计数/notifyMotion/Reader 状态） | thread_state, slice |
| 批16-q7q11-finger补充.sql | 16 | D 函数分解/RV Scroll 间隔/eventTime 差分/跨度（含录-2） | thread_state, slice |
| 批16-q12q13-微观窗口.sql | 16 | finger 51.72ms 窗口裁剪＋finger_h top deliver gap | thread_state, slice |
| 批16-q14q15-cpu表.sql | 16 | 全线程 Running 排名＋总 Running 容量 | thread_state |
| 批16-q16-每帧工作量.sql | 16 | traversal/draw/animation 时长统计 | slice |
| 批16-q17-doframe时长.sql | 16 | doFrame 时长分布＋超 16.67ms 计数 | slice |
| 批16-cont与r2逐trace.sql | 16 | cont_*/r2_* 逐 trace 五段（同构复用） | thread_state, slice |
| 批17-复算包-f01-核心证据包.sql | 17 | 证据包 #1-#8 复算（bounds／双 S／Reader 序列／doFrame＋事件链五段） | trace_bounds, thread_state, slice |
| 批17-复算包-f02-waker与计数轨.sql | 17 | 证据包 #9-#11 复算（waker／iq+oq+wq／CPU3 裁剪三段） | thread_state, counter, sched |
| 批17-复算包-f03-cpu与vsync.sql | 17 | #11 前段（S 所在 CPU／CPU4 裁剪／VSYNC 三轨） | thread_state, sched, counter |
| 批17-复算包-f04-cpu4-idle.sql | 17 | #11 的 swapper 行核验（f03 扩行版） | sched |

## 7. 复算包：战役核心数值的最小查询集

四个 SQL 文件（§6 末四行，批17 归档于 `过程记录/`），对最新样本依次执行即覆盖契约第三节证据包的全部可 SQL 复算项。执行顺序与预期数值如下（**[复算]**＝批17 实跑命中，执行记录见过程记录 §三）。

```bash
TP=/home/liang/.local/share/perfetto/prebuilts/trace_processor_shell-2434129a3af4dc9e
TRACE=/home/liang/.codex/visualizations/2026/11/10/01a1c423-8831-7702-b674-030678d29b9b/applist-collector/captures/20261113-151358-vertical-1912967/trace.perfetto
$TP $TRACE -q 批17-复算包-f01-核心证据包.sql
$TP $TRACE -q 批17-复算包-f02-waker与计数轨.sql
$TP $TRACE -q 批17-复算包-f03-cpu与vsync.sql
$TP $TRACE -q 批17-复算包-f04-cpu4-idle.sql
```

f01 的五段查询：trace_bounds；Launcher/Dispatcher 大于 40 ms 的状态行；InputReader 状态序列；doFrame 两帧＋同 ID 事件链（§3e 的 LIKE 集＋两帧精确名）。f02 三段：主线程醒点 waker 直连（§3d）；iq/oq/wq 采样（§3c）；CPU3 R 窗口裁剪（§3a）。f03 三段：S 窗口所在 CPU；CPU4 S 窗口裁剪；VSYNC 三轨样本。f04 一段：CPU4 裁剪的 swapper 行核验（f03 的 LIMIT 扩行版）。

### 7.1 证据包 12 项对照表

对照基准：契约第三节核心证据包（战役计划与台账.md:68-78）与台账批0 验收行所列的"证据包 12 项数值 ns 级复现"（战役计划与台账.md:204）。"复算步骤"列给 f01~f04 的段号。

| # | 证据包数值（逐字） | 复算步骤 | 本批复算实值 | 判定 |
|---|---|---|---|---|
| 1 | doFrame 155803 起点 `00:00:06.776445` | f01 第 5 段 | rel 6,776,444,919 dur 1,185,500 | ✅ [复算] |
| 2 | Launcher S `00:00:06.779837–00:00:06.830765` 计 50.928 ms | f01 第 2 段 | rel 6,779,836,842 dur 50,928,000 state=S | ✅ [复算] |
| 3 | event0 约 `00:00:06.787410`（ID 0x3b3b7f02 eventTime 同源） | f01 第 5 段 | deliverInputEvent 名内 eventTimeNano=6,080,481,854,000 − 6,073,694,444,196 = 6,787,409,804 | ✅ [复算] |
| 4 | InputReader `00:00:06.787427` 起 R 7.951 ms；`00:00:06.795378` Running | f01 第 4 段 | R rel 6,787,426,919 dur 7,951,192；Running rel 6,795,378,111 dur 996,346 | ✅ [复算] |
| 5 | notify `00:00:06.795839`／prepare `00:00:06.830577`／deliver `00:00:06.831007`；差 34.738/43.597 ms | f01 第 5 段 | 6,795,838,649 / 6,830,576,688 / 6,831,006,342；差 34,738,039 与 43,596,538 ns（端点减法） | ✅ [复算] |
| 6 | Dispatcher S `00:00:06.779885–00:00:06.830331` 计 50.446 ms；waker system_server | f01 第 3 段（waker 值取批06-q6 归档，见 §7.2） | S rel 6,779,885,149 dur 50,445,654；醒后短 R waker 关联 system_server（批06-q6 归档输出） | ✅ [复算]（waker 行取归档） |
| 7 | Reader D `00:00:06.801837–00:00:06.805553` 计 3.716 ms write_pmsg | f01 第 4 段 | D rel 6,801,836,572 dur 3,716,500 blocked_function=write_pmsg$6fb35a9… | ✅ [复算] |
| 8 | 醒后约 0.241 ms 回调；scheduleVsync `00:00:06.831246`；doFrame 155812 `00:00:06.842075`；两帧差 65.630 ms | f01 第 5 段 | deliver 起 6,831,006,342；scheduleVsyncLocked rel 6,831,246,265；doFrame 155812 rel 6,842,074,918；差 65,629,999 ns（65.630 ms 为 μs 取整记法） | ✅ [复算] |
| 9 | Launcher S waker＝InputDispatcher tid1488 直证 | f02 第 1 段 | waker_utid=210 → tid 1488 InputDispatcher（system_server） | ✅ [复算] |
| 10 | iq 恒 0（空档非排队）＋oq/wq 三通道驻留 | f02 第 2 段 | iq 采样序列与归档一致（6,771,783,765 起）；oq/wq 三轨采样全命中（wq 擦点 6,838,299,572） | ✅ [复算] |
| 11 | CPU3 竞争裁剪七线程；CPU4 S 窗口 idle 3,617,928 ns | f02 第 3 段＋f03 第 2 段＋f04 | getevent 1,716,807 / shell svc 1,095,693 / slogmodem 1,087,115 / dds-recorder 597,924 / composer 577,884 / MP4WtrVidTrkThr 419,654 / fgecomservice 420,307 ns；CPU4 swapper 3,617,928/26 段 | ✅ [复算] |
| 12 | 旧样本对照（0x37ee8b11 全链；notify→prepare 5.2 ms vs 新 34.7 ms） | §3f 三步（批16-q1q6 对 finger.perfetto） | 主线程 S 15,452.0 ms／doFrame 732 帧 mean_gap 24.56 ms／deliver 间隔 pct≤17ms 58.3%／notifyMotion 3,252 条——与批16 归档一致；0x37ee8b11 逐点表见批0-证据复验二.md §二 | ✅ [复算] |

证据包中两项不经 SQL（复算包不含，出处另行）：event0 统计（1195 帧/1194 间隔与百分位）来自样本目录 touch.txt 派生的 event0_帧间隔.csv 的 awk 复算（批0-证据复验三.md 第二节，非 trace_processor 对象）；vg-kit Display0 序列 16/39/34/30/31/40/35 来自 vg-kit.txt 文本（host 域计数，批0-证据复验三.md 第三节，非 trace 对象）。

### 7.2 复算包的边界

- 复算包四文件的窗口数字写死为战役窗口（rel 6.7~6.9 s 等）；换其他样本时按 §2.1 先算新 start_ts 再改参。
- §7.1 #6 行的 Dispatcher waker 值取自批06-q6 归档（本批复算未重跑该 tid 756 版本；同模式 Launcher 版已在 f02 重跑命中）——该行判定以归档为准。
- f02 第 2 段在 6.82–6.86 s 窗口输出 76 行采样（本批实跑计数），扩展窗口行数随窗口增大；复现时以"采样值序列逐点可对照"为准，不以行数为准。

## 8. 未证边界（本篇清单）

1. **批0 三 SQL 文件缺失（已解除）**：批0-证据复验二.md:3 声明"SQL 归档于 过程记录/（waker.sql、old_finger.sql、old_finger2.sql）"，写入当时未实际执行拷贝（留痕纪律执行偏差）；批17 盘点时目录无此三文件（缺失事实当时已证）。协调者已于 2026-11-14 从 /tmp/sql-verify/ 补拷三文件入库（复验二补记 2026-11-14／台账批17 行"漏项补齐"；git 未跟踪，mtime 2026-11-14 04:56）。R7 全量复核实跑复现：waker.sql 对最新样本命中 waker_utid=210→tid1488 InputDispatcher；old_finger.sql 对旧样本命中 bounds start_ts=1,968,722,243,568 与 launcher tid1913/utid62；old_finger2.sql 命中 0x37ee8b11 全链（notify→prepare 5,167,308／prepare→deliver 585,192／eventTime→deliver 8,168,467 ns，与复验二 §二逐字一致）。存档缺口已闭合；三文件 SQL 文本现已可查（§6 索引已补三行）。
2. **批04 查询无独立 SQL 存档**：批04 的 trace 查询内联于批04-过程记录.md §2（1 段 notifyMotion 查询＋输出），未建 .sql 文件——口径事实，已证；不影响复算（§6 索引已注明）。
3. **Dispatcher waker 未重跑**：§7.1 #6 行的 tid 756 waker 数值取自批06-q6 归档输出，批17 未重跑该条（同模式已对 tid 1909 验证）——该单点以归档为准。
4. **旧样本复算范围**：批17 [复算] 对旧样本只重跑了批16-q1q6（finger.perfetto 单份）；批16 其余 10 份旧 trace 的 28 次执行结果未重跑，以批16 归档为准。
5. **MONO−BOOT 全程校验未做**：§2.3 的"差值不可分辨"依赖契约 :70 单点判定，全程逐点跨域校验未做＝未证（承 00 篇 §3.1 与内核详解03 B22，不升级）。
6. **Perfetto UI 面板与 trace_processor 列的渲染对应**（如状态色块与 thread_state 行的映射、waker 箭头的起终点选择逻辑）属 UI 实现层，本系列只对可 SQL 复算的部分（§3d）给直证，UI 渲染细节未查证＝盲区（不影响任何数值结论）。
7. **§6 索引的"目的"列是批17 对存档 SQL 的读法归纳**；各文件完整语义以文件内注释与本篇摘录为准，索引不替代原文。

## 9. 关联阅读

- 00-总纲与目录.md §4 术语表（本文引用 #8/#13/#14/#15/#16/#17/#18/#20/#21/#22/#27/#28/#29/#30 不重定义）、§3.4 关键数值速查、§3.7 全局盲区清单。
- 各机制篇：03（eventTime 时源）、04（事件 id 与监听器链）、06/07（iq/oq/wq）、08（deliverInputEvent 打点）、09（doFrame 链）、10（DrawFrames）、11（BLAST 计数轨）、12（VSYNC 轨与 FrameTimeline）、13（raw 事件面）、14（thread_state/sched/waker/blocked_function）、15（全链归因）、16（旧结论审计）。
- 复核指南：战役计划与台账.md 第六节第 6 条"SQL 复算"以本篇 §7 为入口。
