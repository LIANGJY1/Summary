# 14 · CFS 调度与线程状态

> AppList 性能全链路解剖 · 第 14 篇（批14 作业单执行稿，调度横轴篇）
> 源码基准：kernel 引 `/home/liang/Project/AAOS13_kernel/common`（5.15.41，android13-5.15 GKI，HEAD `24d27dff64c4`），Android 引 `/home/liang/Project/AAOS13` 精树。写作前已实际读取核对全部引文行号；kernel 树只读，本文是唯一载体。
> 时间口径：trace 数值一律 Perfetto 相对时间 `00:00:06.xxxxxx`；本篇涉及旧样本处单独标注。
> 证据三态：**已证**（源码或 trace 明确支持）／**未证**（有候选无证据）／**盲区**（采集配置或源码缺失）。
> 摘抄规则：每个代码块只摘 ≤10 行逐字原文；块内 `... /* 略 ：a-b */` 为省略标记，省略段行号随标。
> 本篇无批注任务（kernel 树只读不注，契约战役计划与台账.md:180）。
> 本篇新查询数值的 SQL 与输出归档于 `过程记录/批14-q1~q14*.sql` 与 `批14-qN输出.txt`；引用时标注查询号。

## 0. 本篇范围与上下游边界

00 篇全景图的调度横轴（00-总纲与目录.md:120-127）贯穿节点 [1]-[8] 与 [10]-[15] 的全部用户态线程与 kernel 线程；01~13 篇在各节点给出的状态序列（S/R/R+/D/Running）都由本篇的机制产生。本篇走读以下对象：

- `include/linux/sched.h`：任务状态常量（:85-87）、TASK_REPORT 掩码（:116-119）、proc 侧状态字符表（:1649-1685）。
- `include/trace/events/sched.h`：sched_waking/sched_wakeup/sched_switch/sched_blocked_reason 事件定义。
- `kernel/sched/core.c`：try_to_wake_up 唤醒主路径（:4040-4218）、ttwu_queue 两条投递路（:3757-3868）、__schedule 的切出决策与状态翻译调用点（:6311-6489）。
- `kernel/sched/fair.c`：enqueue_task_fair/dequeue_task_fair（:5637-5826）、pick_next_task_fair/pick_next_entity（:4556-4608、:7388+）、check_preempt_wakeup（:7245-7352）、select_task_rq_fair（:7016-7084）与 wake_affine/select_idle_sibling/find_energy_efficient_cpu（概述级）、task_hot/can_migrate_task（:7813-8008）。
- 旁支：`kernel/sched/wait.c`、`kernel/entry/common.c`、`kernel/signal.c`、`arch/arm64/kernel/process.c`（get_wchan）、`fs/pstore/pmsg.c`、Android `system/logging/liblog` 的 pmsg 写入链。

- **上游边界**：01~13 篇各线程进入内核等待点为止（如 evdev 的 `wake_up_interruptible_poll`、EventHub 的 epoll_wait、logd 的 socket 写）。等待点的业务语义归各篇；本篇只讲这些等待点共同经过的调度机制。
- **下游边界**：状态序列回到 Running 并继续执行为止。各线程执行的代码归其所属篇。
- **本篇职责（批14 作业单，战役计划与台账.md:176-180）**：S/R/R+/D 与 ftrace 状态字符串的精确对应（§2）；唤醒选核 target_cpu=3 的产生点（§4）；waker 字段语义与 maybe interrupt 标记（§4.3）；50.928 ms S 的调度学框架（§3）；Reader R 7.951 ms 与主线程 R 双窗的竞争窗口（§5）；D 3.716 ms write_pmsg 的 blocked_reason 语义与调用链查证（§6）；迁移约束（§7）。

**三条硬结论预告（本篇逐段证明）**：

1. trace 状态列的每一个 S/R/R+/D 都有唯一的内核产生点：S=TASK_INTERRUPTIBLE（sched.h:86）、D=TASK_UNINTERRUPTIBLE（sched.h:87）、R=TASK_RUNNING 且自愿切出（prev_state=0）、R+=TASK_RUNNING 且抢占入口切出（sched_switch 的 preempt 参数，trace/events/sched.h:202-203）。本 trace 用 system_server 主线程的 8 次切出事件把四种形态逐行对上（§2.5）。
2. 50.928 ms S 不是等 CPU：S 窗口内主线程不在任何就绪队列上（deactivate_task，core.c:6436），其所在 CPU4 在窗口内有 26 段 idle（合计 3,617,928 ns，占 7.1%，批14-q13），任何一段 idle 都能立即运行一个可运行任务而主线程未转为 R——它等的是事件，不是 CPU 时间（§3.2/§3.4）。
3. Reader 的 D 3,716,500 ns 与 S 态在 trace 上的结构性差异（D 有 blocked_function、S 没有）由内核决定：sched_blocked_reason 只在唤醒 TASK_UNINTERRUPTIBLE 任务时发出（core.c:4119-4120），S 态任务被唤醒不产生该事件（§6.2）。

主线先闭环：§2 走读完 sched_switch 的翻译链，"thread_state 状态字符串 → ftrace prev_state → __schedule → 任务状态常量"这条主线闭合；§3 走读完 S 的进入与退出，"等待事件"语义闭合；§4 走读完 try_to_wake_up，"事件 → 唤醒 → 选核 → 入队"闭合。此后 §5-§7 展开三个具体窗口与迁移约束。

## 1. 主线问题与本篇的证据清单

本篇承接两个战役问题（00 篇 §0）对调度横轴的待解项：

- 问题 A（Launcher 主线程 tid 1909 的 S 50,928 ms）：批09 已证"S 解除非 vsync 驱动"（09-帧调度与RecyclerView布局.md），批10 已证"S 窗口内 RT 零 slice"；本篇给出 S 的调度学定义、"S 期间 CPU 在做什么"的实测答案、"不是在等什么"的排除项汇总（§3）。
- 问题 B（notifyMotion→prepare 34.738 ms）：批06 已把 34.738 ms 分解为九段并闭合（06-InputDispatcher入队与裁决.md:544-558），其中 S2/S4 两段是 system_server 主线程（tid 756）的可运行窗口 R 9,313,231 ns＋R 17,289,307 ns；批06 明确转办"批14 需对 6,796–6,830 s 的主线程可运行窗口重做同一方法论的裁剪统计"（06-InputDispatcher入队与裁决.md:974）。本篇 §5.5 完成该统计，竞争归因保持未证（批15 终判）。

本篇实际测得的新数值（SQL 与输出在 `过程记录/批14-qN*`，引用时标注查询号）：

| 查询 | 对象 | 关键数值 |
|---|---|---|
| 批14-q1 | system_server 主线程 tid 756 状态序列（6,795–6,832 s，含 cpu 列） | Running 段全部 cpu=2；R/R+ 段 cpu 列为 NULL（thread_state 口径） |
| 批14-q2 | CPU2 与两个 R 窗口的重叠裁剪统计 | 窗口 1 合计 9,313,231/9,313,231；窗口 2 合计 17,289,307/17,289,307；无 swapper |
| 批14-q3 | 窗口 1 各 CPU swapper | cpu0/1/4 各有 29,000/40,307/24,385 ns idle；cpu2 无 |
| 批14-q4/q5 | CPU2 在 6.79–6.84 s 的连续性 | 262 个 slice 合计 50,874,308 ns（窗口 50,000,000 ns，边界 slice 计入完整 dur）；6,798,561,149–6,806,100,495 的 41 个 slice 逐行首尾相接（每行终点=下一行起点，Q5 输出全表） |
| 批14-q6 | raw sched_waking 事件 | Reader 的 waking：rel 6,787,426,919、发射 cpu=4、发射线程 kworker/4:1（tid 26591）、wakee pid=1489、target_cpu=3；主线程第一次醒的 waking：rel 6,796,227,034、发射 cpu=3、发射线程 InputReader（tid 1489）、wakee pid=756、target_cpu=2 |
| 批14-q7 | raw sched_switch（窗口 2 边界 cpu2） | 6,812,717,803 prev_state=1→next=756；6,812,790,111 prev=756 prev_state=0→next=UsbFfs-worker；6,812,884,265 prev=UsbFfs-worker prev_state=256 |
| 批14-q8 | 主线程 6.80–6.83 s 全部切出事件 | 8 次：prev_state 依次 256/0/1/256/256/256/0/256 |
| 批14-q9 | 两次 prev_state=0 切出前后的 waking | 6,805,690,149 有 wakee=1136（tbox_ap）的 waking，发射线程 tid 756 自身；6,812,790,111 前 ±1.5 ms 无 wakee=26884 的 waking |
| 批14-q10 | Reader D 窗口细节 | D 起点 6,801,836,572、dur 3,716,500、blocked_function=`write_pmsg$6fb35a9abf14f1ffebba6021202469c1`；D 前两个 Running 在 cpu=3；D 窗口内 tid 1489 无 sched slice |
| 批14-q11 | 全 trace sched 事件计数 | sched_switch 424,434；sched_waking 280,053；sched_blocked_reason 68,074；sched_wakeup 0 |
| 批14-q12 | Launcher 主线程 S 前后 cpu | S 前 Running（6,779,734,457 起）cpu=4；S 后 Running（6,830,798,765 起）cpu=4 |
| 批14-q13 | CPU4 在 S 窗口的线程构成 | 总覆盖 50,928,000/50,928,000；swapper 3,617,928（26 段）；HeapTaskDaemon 13,228,767；binder:756_2 7,986,539；L-hotwordvad-k 6,413,234；logcat 5,477,768；adbd 4,622,693 等 |

## 2. 状态字符串的产生链（机制①主线闭环）

### 2.1 常量层：任务状态数值

`include/linux/sched.h:85-87`：

```c
/* Used in tsk->state: */
#define TASK_RUNNING			0x0000
#define TASK_INTERRUPTIBLE		0x0001
#define TASK_UNINTERRUPTIBLE		0x0002
```

走读：任务状态是 task_struct 的位掩码数值（00 篇术语表 #16 的"任务状态数值"即此处）。本篇用到三个基态加两个组合态——`include/linux/sched.h:106` 的 `TASK_KILLABLE (TASK_WAKEKILL | TASK_UNINTERRUPTIBLE)` 与 `:110` 的 `TASK_IDLE (TASK_UNINTERRUPTIBLE | TASK_NOLOAD)`。TASK_RUNNING（0x0000）的特殊性：它既表示"正在运行"也表示"可运行未运行"——位掩码为 0 意味着调度器无法从数值本身区分两者，区分由 `on_rq`/`on_cpu` 与切出方式共同完成（§2.2）。

trace 与 Proc 文件的翻译掩码不含 TASK_DEAD：`include/linux/sched.h:116-119`：

```c
#define TASK_REPORT			(TASK_RUNNING | TASK_INTERRUPTIBLE | \
					 TASK_UNINTERRUPTIBLE | __TASK_STOPPED | \
					 __TASK_TRACED | EXIT_DEAD | EXIT_ZOMBIE | \
					 TASK_PARKED)
```

走读：TASK_REPORT＝0x7F，是 ftrace 与 /proc 对外报告的状态位全集。两个派生常量在 `include/linux/sched.h:1649-1650`：

```c
#define TASK_REPORT_IDLE	(TASK_REPORT + 1)
#define TASK_REPORT_MAX		(TASK_REPORT_IDLE << 1)
```

走读：TASK_REPORT_IDLE=0x80（128），TASK_REPORT_MAX=0x100（256）。这两个数在本 trace 的 raw sched_switch 里直接出现：主线程被抢占的切出 prev_state=256（批14-q8），UsbFfs-worker 被抢占的切出 prev_state=256（批14-q7 第三行）——256 就是"抢占"标记值（§2.3）。

### 2.2 切出决策层：__schedule

`kernel/sched/core.c:6311-6319`：

```c
#define SM_NONE			0x0
#define SM_PREEMPT		0x1
#define SM_RTLOCK_WAIT		0x2

#ifndef CONFIG_PREEMPT_RT
# define SM_MASK_PREEMPT	(~0U)
#else
# define SM_MASK_PREEMPT	SM_PREEMPT
#endif
```

走读：__schedule（定义 `kernel/sched/core.c:6360`）的 sched_mode 参数区分本次切出是主动睡眠/调度（SM_NONE）还是抢占（SM_PREEMPT）。本树非 PREEMPT_RT，SM_MASK_PREEMPT 为全 1，`sched_mode & SM_MASK_PREEMPT` 即 sched_mode 本身。

__schedule 读出 prev 的状态并决定切出形态——`kernel/sched/core.c:6412-6443`：

```c
	prev_state = READ_ONCE(prev->__state);
	if (!(sched_mode & SM_MASK_PREEMPT) && prev_state) {
		if (signal_pending_state(prev_state, prev) && __task_can_run(prev)) {
			WRITE_ONCE(prev->__state, TASK_RUNNING);
		} else {
			... /* 略 ：6417-6435，sched_contributes_to_load 与注释 */
			deactivate_task(rq, prev, DEQUEUE_SLEEP | DEQUEUE_NOCLOCK);
		}
		switch_count = &prev->nvcsw;
	}
```

走读，机制四问逐项：

- **规则是什么**：切出时若 sched_mode 为 SM_NONE（主动 schedule）且 prev_state 非 0（任务已用 set_current_state 把自己置入 S/D），则两条分支——有信号挂起且可运行信号能唤醒该状态时，状态清回 TASK_RUNNING 并继续留在就绪队列（不出队）；否则 `deactivate_task(DEQUEUE_SLEEP)` 把任务移出就绪队列（:6436）——这是 S/D 态在 thread_state 里表现为"不占 CPU、无 cpu 列"的直接来源。
- **谁设置**：prev_state 由睡眠线程自己用 set_current_state 置位（include/linux/sched.h:172-203 注释中的标准等待模式），sched_mode 由调用方（schedule() 传 SM_NONE；preempt_schedule/preempt_schedule_irq 传 SM_PREEMPT，:6655/:6883）。
- **谁执行**：该 CPU 上的调度入口——用户态返回路径的 `exit_to_user_mode_loop`（kernel/entry/common.c:160-163，`if (ti_work & _TIF_NEED_RESCHED) schedule();`）、内核抢占入口 `preempt_schedule_common`（core.c:6637-6664）与中断返回抢占 `preempt_schedule_irq`（core.c:6871-6890）。
- **执行的后果**：prev_state 非 0 且无信号→出队（S/D）；prev_state==0（任务仍可运行）或 SM_PREEMPT→不出队（留在就绪队列，产生 R/R+）。

切出时调度器发出 sched_switch 事件——`kernel/sched/core.c:6480`：

```c
		trace_sched_switch(sched_mode & SM_MASK_PREEMPT, prev, next);
```

走读：第一个参数就是 ftrace 里 "+" 的来源：SM_NONE 传 false，SM_PREEMPT 传 true。pick_next_task 在 :6446 完成"下一个运行谁"的选择（CFS 侧实现在 fair.c:7388，§5.2 用到）。

### 2.3 翻译层：sched_switch 的 TP_printk

`include/trace/events/sched.h:190-214`（CREATE_TRACE_POINTS 段）：

```c
static inline long __trace_sched_switch_state(bool preempt, struct task_struct *p)
	... /* 略 ：191-197，函数体头、变量与断言 */
	/*
	 * Preemption ignores task state, therefore preempted tasks are always
	 * RUNNING (we will not have dequeued if state != RUNNING).
	 */
	if (preempt)
		return TASK_REPORT_MAX;
	... /* 略 ：205-212，注释与空行 */
	state = task_state_index(p);
	return state ? (1 << (state - 1)) : state;
}
```

走读：preempt 为真时 prev_state 直接记 TASK_REPORT_MAX=256，不看真实状态——注释的含义是抢占切出时任务必然仍为 RUNNING（已置睡态的任务此前已在主动 schedule 路径经 :6436 出队，轮不到抢占入口）；preempt 为假时用 task_state_index 把真实状态映射成位值。

`include/trace/events/sched.h:249-265`（TP_printk）：

```c
		(__entry->prev_state & (TASK_REPORT_MAX - 1)) ?
		  __print_flags(__entry->prev_state & (TASK_REPORT_MAX - 1), "|",
				{ TASK_INTERRUPTIBLE, "S" },
				{ TASK_UNINTERRUPTIBLE, "D" },
				{ __TASK_STOPPED, "T" },
				{ __TASK_TRACED, "t" },
				... /* 略 ：259-261，EXIT_DEAD/X、EXIT_ZOMBIE/Z、TASK_PARKED/P */
				{ TASK_DEAD, "I" }) :
		  "R",
```

```c
		__entry->prev_state & TASK_REPORT_MAX ? "+" : "", /* 略 ：265 起 next 三字段续行 */
```

走读：这就是 trace 状态字符串的唯一产生点。三步——①prev_state 低 8 位（TASK_REPORT 掩码域）命中位则用 __print_flags 翻出 S/D/T/t/X/Z/P/I，位可组合但常见基态各为单一位（TASK_KILLABLE=0x102 的睡眠经 task_state_index 折算后同样翻出 "D"）；②低 8 位无命中则打印 "R"（prev_state==0，任务可运行）；③prev_state & 0x100（TASK_REPORT_MAX 位）命中则追加 "+"。于是：S 态切出→"S"；D 态切出→"D"；可运行自愿切出→"R"；抢占切出→256→低 8 位全 0 落到 "R"、256 位命中→"+"，合成 "R+"。

proc 侧的字符表是另一套（常被混为一谈，此处分离）：`include/linux/sched.h:1673-1685`：

```c
static inline char task_index_to_char(unsigned int state)
{
	static const char state_char[] = "RSDTtXZPI";

	BUILD_BUG_ON(1 + ilog2(TASK_REPORT_MAX) != sizeof(state_char) - 1);
	return state_char[state];
} /* 略 ：1682-1685，task_state_to_char 即两函数串联 */
```

走读：数组 state_char="RSDTtXZPI"（9 字符，下标 0-8）是 /proc 的单字符口径（task_state_index 返回 fls 值 0-8，sched.h:1652-1671）。ftrace 的 sched_switch 不经此函数，走 §2.3 的 __print_flags；两者对 S/D/R 一致，对 TASK_IDLE 有差异（proc 侧 "I"、ftrace 侧 __print_flags 不含 TASK_IDLE 组合位）。本 trace 的 thread_state 字符串沿用的是 ftrace 口径（S/R/R+/D 与批0 全序列一致）。

### 2.4 Perfetto thread_state 层

Perfetto 把 sched_switch 翻成 thread_state 行：next 任务从上一状态转 Running（起点=switch 时刻，附 cpu），prev 任务按 prev_state 翻 S/R/R+/D/R（00 篇术语表 #16、批04 的口径结论："R 是可运行未运行，Running 是实际处理窗口"，04-EventHub与InputReader读取段.md:18/:973-981）。本篇不引入新定义，只验证本 trace 数据与内核产生点的逐行对应。

### 2.5 本 trace 实证：主线程 8 次切出逐行对上

批14-q8 查询 tid 756（system_server 主线程）在 rel 6,805–6,832 s 的全部 sched_switch 切出事件，prev_state 数值与 thread_state 行（批14-q1、批06-q6）逐行对照：

| rel_ts | prev_state | ftrace 字符串 | thread_state 行（Q1） | 产生点 |
|---|---|---|---|---|
| 6,805,619,611 | 256 | R+ | R+ 31,692（6,805,619,611） | 抢占入口（SM_PREEMPT→TASK_REPORT_MAX） |
| 6,805,709,380 | 0 | R | R 189,346（6,805,709,380） | 用户返回 schedule()（SM_NONE＋prev_state==0，不出队） |
| 6,806,100,495 | 1 | S | S 1,149,039（6,806,100,495） | TASK_INTERRUPTIBLE 出队 |
| 6,810,360,534 | 256 | R+ | R+ 1,893,923（6,810,360,534） | 抢占入口 |
| 6,812,343,034 | 256 | R+ | R+ 118,115（6,812,343,034） | 抢占入口 |
| 6,812,569,265 | 256 | R+ | R+ 148,538（6,812,569,265） | 抢占入口 |
| 6,812,790,111 | 0 | R | R 17,289,307（6,812,790,111） | 用户返回 schedule()（R 双窗第二窗起点） |
| 6,830,366,111 | 256 | R+ | R+ 1,009,154（6,830,366,111） | 抢占入口；起点与时长同批06 S8 段分发线程 Running 1,009,154 重合——让位后由 Dispatcher 运行 |

走读【已证】：8 次切出的 prev_state 数值与 thread_state 行的起点、时长、状态全部一致；0/1/256 三种 prev_state 数值在本 trace 中各有着落，且 256 与 preempt=true 的代码路径一一对应。这完成了状态字符串产生链从内核到 trace 的整段闭环。Q7 补充了窗口边界的上下文：6,812,717,803 的 sched_switch prev_state=1（前一任务 PowerManagerSer 在 S 中切出）next=system_server——主线程在该时刻进入 Running 72,308 ns，随后在 6,812,790,111 以 prev_state=0 切出（next=UsbFfs-worker），之后 6,812,884,265 UsbFfs-worker 又以 prev_state=256（被抢占）切给 surfaceflinger——CPU2 在这 166 μs 内完成三次切换，与 Q1 的 Running/R 序列逐 ns 吻合。

### 2.6 机制四问：R+ 的精确触发条件

- **规则**：R+ 只在一个条件下产生——切出经由抢占入口（__schedule(SM_PREEMPT)），trace_sched_switch 的 preempt 参数为 true，__trace_sched_switch_state 返回 TASK_REPORT_MAX=256，TP_printk 追加 "+"（include/trace/events/sched.h:202-203、:264）。被抢占任务的 prev_state 与真实状态无关（注释"Preemption ignores task state"，:198-201）。
- **谁设置**：TIF_NEED_RESCHED 标志——唤醒抢占判定 `check_preempt_wakeup`（fair.c:7337 的 resched_curr）或周期 tick 的公平性检查由内核设置；本篇 trace 侧无法区分这两者（§5.4）。
- **谁执行**：两个入口——`preempt_schedule_common`（kernel/sched/core.c:6637-6664，:6655 `__schedule(SM_PREEMPT)`，位于 preempt_enable 点）与 `preempt_schedule_irq`（core.c:6871-6890，:6883 `__schedule(SM_PREEMPT)`，位于中断返回且内核可抢占点）。
- **执行的后果**：任务不出队（SM_PREEMPT 下 :6413 的条件短路），保持在就绪队列；ftrace 记 "R+"；随后由 pick_next_task 决定它何时回来（§5）。

对照面【已证】：prev_state=0 的 "R"（无 +）是另一个产生点——SM_NONE 入口且 prev_state==TASK_RUNNING：任务没睡、只是调度点到了（用户返回路径 exit_to_user_mode_loop 的 schedule()，kernel/entry/common.c:160-163）。§2.5 的两行 prev_state=0 即此形态。R 与 R+ 的区分判据由此可操作化：thread_state 显示 R+ ⇒ 切出经抢占入口；显示 R（无 waker 且无 sched_waking 关联）⇒ 可能是自愿调度点切出（需 raw sched_switch 的 prev_state==0 复核）。

## 3. S 态：等待事件而非等 CPU

### 3.1 S 的置位与清除

S=TASK_INTERRUPTIBLE 的标准使用模式（注释原文，`include/linux/sched.h:172-179`）：

```c
 *   for (;;) {
 *	set_current_state(TASK_UNINTERRUPTIBLE);
 *	if (CONDITION)
 *	   break;
 *
 *	schedule();
 *   }
 *   __set_current_state(TASK_RUNNING);
```

走读：注释给出等待循环三件套——set_current_state 置状态（带屏障）、条件检查、schedule()。TASK_INTERRUPTIBLE 是把第一行换成 S 版本的同一模式。事件生产者一侧的唤醒经 wait queue：以本战役 03 篇走读的 evdev 为例，SYN_REPORT 写完 client 缓冲后 `wake_up_interruptible_poll` 唤醒 epoll 上的读端（03-evdev用户态接口.md 批03 B8，drivers/input/evdev.c:287-289）。wait queue 回调链——`kernel/sched/wait.c:418-428`：

```c
__sched int autoremove_wake_function(struct wait_queue_entry *wq_entry, unsigned int mode,
				     int sync, void *key)
{
	int ret = default_wake_function(wq_entry, mode, sync, key);

	if (ret)
		list_del_init_careful(&wq_entry->entry);

	return ret;
}
```

走读：autoremove_wake_function 调 default_wake_function（`kernel/sched/core.c:6891-6897`，函数体即 `return try_to_wake_up(curr->private, mode, wake_flags);`）——所以任何 wake_up 类调用最终都进入 §4 的 try_to_wake_up。清除侧：S 的退出只有两条路——事件唤醒（try_to_wake_up，§4）或信号（signal_pending_state 放行时 __schedule 在 :6415 把状态清回 TASK_RUNNING 不出队）。S 的语义由此可操作化：**S＝已把自己置为 TASK_INTERRUPTIBLE 并出队的线程，等的是唤醒事件（wait queue 信号、poll/epoll 的 fd 就绪、定时器超时），不是 CPU**。

### 3.2 S 期间 CPU 在做什么（机制四问①前半）

机制：任务在 :6436 出队后，pick_next_task 在同一 CPU 的就绪队列里挑下一个实体（CFS 侧 pick_next_task_fair，fair.c:7388）；队列空则挑 idle 线程。S 线程在此期间一行代码不执行——批04 已对 Reader 的 R 窗口证过"R 期间读取段一行代码未执行"（04-EventHub与InputReader读取段.md:981），S 侧同理且更强：R 只是没被挑中，S 连队列都不在。

本 trace 实测（批14-q12/q13）：Launcher 主线程 tid 1909 在 S 窗口（rel 6,779,836,842–6,830,764,842，50,928,000 ns，契约 :69）前后都运行在 CPU4（S 前 Running 段 6,779,734,457 起 cpu=4；S 后 Running 段 6,830,798,765 起 cpu=4）。CPU4 在 S 窗口内的构成（重叠裁剪求和，批14-q13）：

| 线程 | 裁剪后运行时 | slice 数 |
|---|---|---|
| HeapTaskDaemon（tid 1828） | 13,228,767 ns | 21 |
| binder:756_2（tid 824） | 7,986,539 ns | 20 |
| L-hotwordvad-k（tid 4886） | 6,413,234 ns | 14 |
| logcat（tid 26969） | 5,477,768 ns | 26 |
| adbd（tid 26853） | 4,622,693 ns | 21 |
| swapper（tid 0，idle） | 3,617,928 ns | 26 |
| logcat（tid 27800） | 2,570,346 ns | 10 |
| ncent.wecarnavi（tid 4774） | 1,731,231 ns | 5 |
| binder:1909_7（tid 2837） | 979,499 ns | 3 |
| kworker/4:1（tid 26591） | 615,575 ns | 19 |
| 其余 12 线程（L-wake-k 614,730、Thread-2 373,346、fgemcucomm 361,231、fgecomservice 346,769、binder:1819_5 264,077、cat 177,578、ackgroundThread 166,654、binder:2561_4 140,538、kworker/u16:2 131,077、omx@1.0-service 129,423 等，各自 ≤614,730 ns） | 合计 3,684,420 ns | 39 |
| 合计 | 50,928,000 ns（=窗口时长） | 204 |

走读【已证】：①CPU4 的 sched 覆盖与窗口逐 ns 相等（无事件空洞）；②主线程睡眠期间 CPU4 绝大多数时间在运行其他线程（HeapTaskDaemon 是 system_server 的 GC 守护线程、binder:756_2 是 system_server 的 binder 线程），idle 只占 7.1%（3,617,928/50,928,000）；③**26 段 idle 是"S 不是等 CPU"的直接证明**——CFS 在任一 idle 时刻都会立刻挑走就绪队列里最左的实体（fair.c:7388 的 pick），如果主线程可运行，这些 idle 段不会存在；idle 存在说明主线程不在就绪队列，即不在等 CPU 而在等事件。

### 3.3 唤醒延迟的构成（机制四问①后半）

S 结束时刻的链路已由批0 复验二直证【已证】：Launcher 主线程 S 止 rel 6,830,764,842 的 waker 是 InputDispatcher tid 1488（过程记录/批0-证据复验二-旧样本与waker.md:8-14）；结合同窗口 slice：enqueueDispatchEntriesLocked(AppList 通道) rel 6,830,592,265 → InputChannel 写入 → 主线程醒（6,830,765）——链条闭合。

S 的总时长由此分解为两个可分离的量（本篇的调度学框架，§3.4 收口）：

1. **事件产生延迟**：从主线程进入 S 起到唤醒事件发生。此段由事件生产者决定（本例=InputDispatcher 的分发节奏，批06/07 已走读其内部），调度器不参与——S 线程不在任何队列上，调度器对它无事可做。
2. **调度延迟**：从 sched_waking/wakeup 事件到任务真正上 CPU。上界由 pick_next 的判定决定：唤醒抢占判定（fair.c:7245 check_preempt_wakeup）若判"当前任务该让"（wakeup_preempt_entity 返回 1，:7324）则 resched_curr 置 TIF_NEED_RESCHED，唤醒者在最近的调度点让出；本例主线程醒后 R 33,923 ns（复验二 :11，6,830,764,842 起）→Running，调度延迟实测 33,923 ns。

### 3.4 主线问题 A 的调度学收口

S 50,928 ms 的调度学框架汇总（不引入新归因，排除项均引用已交稿篇）：

| 命题 | 三态 | 依据 |
|---|---|---|
| S=TASK_INTERRUPTIBLE，线程出队等待事件，不占 CPU | 已证 | sched.h:86；core.c:6436；批14-q13 |
| S 窗口内 CPU4 的 idle 段证明主线程非可运行 | 已证 | 批14-q13（swapper 26 段 3,617,928 ns） |
| S 窗口内 RenderThread（tid 2079）零 slice——S 不是等 RT | 已证 | 批10 q6（10-HWUI与RenderThread.md 引用） |
| S 窗口内 SF 主线程静默段 6,814.6→6,858.5（"无新 buffer 不合成不醒"） | 已证 | 批12（12-SurfaceFlinger合成.md 引用） |
| S 结束时刻 waker=InputDispatcher tid 1488 | 已证 | 批0 复验二 :8-14 |
| S 的具体等待条件（哪个 fd/哪个 wait queue） | 盲区 | 本 trace 无 syscall/irq 事件（00 篇 §3.7；契约 :185 维持） |
| "CPU 饱和是唯一主因" | 禁复活 | 00 篇 §5；本篇 §3.2 的 idle 段为其增加一条直接反证 |

走读：框架句为——**S 的 50,928 ms 由"事件产生延迟"与"33,923 ns 调度延迟"两段构成，前者占 50,894,077 ns（50,928,000−33,923），归属由批15 在事件链上归因；调度器对此段的贡献有上界，其值为实测的 33,923 ns**。此句与契约批15 纪律（战役计划与台账.md:186"R 7.951 ms 不外推为 50.928 ms 的原因"）同型：调度横轴给出机制边界，不越界归因。

## 4. 唤醒路径与选核

### 4.1 try_to_wake_up 主路径

`kernel/sched/core.c:4039-4065`（p==current 快路径）：

```c
static int
try_to_wake_up(struct task_struct *p, unsigned int state, int wake_flags)
{
	... /* 略 ：4042-4059，变量、preempt_disable 与 ttwu_state_match 判定 */
	if (p == current) {
		trace_sched_waking(p);
		WRITE_ONCE(p->__state, TASK_RUNNING);
		trace_sched_wakeup(p);
		goto out;
	}
```

走读：唤醒自己（p==current）时直接清状态发事件，不涉及选核。跨 CPU 唤醒的主路径（`kernel/sched/core.c:4091-4120`、:4154、:4192-4207）：

```c
	trace_sched_waking(p);
	... /* 略 ：4093-4115，内存序注释 */
	if (READ_ONCE(p->on_rq) && ttwu_runnable(p, wake_flags))
		goto unlock;

	if (READ_ONCE(p->__state) & TASK_UNINTERRUPTIBLE)
		trace_sched_blocked_reason(p);
```

```c
	WRITE_ONCE(p->__state, TASK_WAKING);
	... /* 略 ：4156-4188，on_cpu 等待与注释 */
	cpu = select_task_rq(p, p->wake_cpu, wake_flags | WF_TTWU);
	if (task_cpu(p) != cpu) {
		... /* 略 ：4194-4197，iowait 记账 */
		wake_flags |= WF_MIGRATED;
		psi_ttwu_dequeue(p);
		set_task_cpu(p, cpu);
	}
	... /* 略 ：4203-4205，!CONFIG_SMP 分支 */
	ttwu_queue(p, cpu, wake_flags);
```

走读，顺序即机制：①`:4091` 发 sched_waking——此时 p->cpu 还是旧值，事件里的 target_cpu=task_cpu(p) 是**唤醒前的 CPU**；②`:4116` 若任务已在就绪队列（没睡成或重复唤醒）走 ttwu_runnable 只清状态；③`:4119-4120` **只有 TASK_UNINTERRUPTIBLE 才发 sched_blocked_reason**（§6.2 的根源）；④`:4154` 置 TASK_WAKING；⑤`:4192` 选核（§4.4）；⑥`:4201` set_task_cpu 迁移记账；⑦`:4207` ttwu_queue 投递到目标 CPU。

### 4.2 ttwu_queue：本地入队与 wakelist IPI

`kernel/sched/core.c:3856-3868`：

```c
static void ttwu_queue(struct task_struct *p, int cpu, int wake_flags)
{
	struct rq *rq = cpu_rq(cpu);
	... /* 略 ：3859-3860，rf 声明与空行 */
	if (ttwu_queue_wakelist(p, cpu, wake_flags))
		return;
	... /* 略 ：3863，空行 */
	rq_lock(rq, &rf);
	update_rq_clock(rq);
	ttwu_do_activate(rq, p, wake_flags, &rf);
	rq_unlock(rq, &rf);
}
```

走读：两条路。快路径 `ttwu_queue_wakelist`（:3828-3845）在 `sched_feat(TTWU_QUEUE) && ttwu_queue_cond(cpu, wake_flags)` 成立时把任务挂到目标 CPU 的 wake_list 并发 IPI（`__ttwu_queue_wakelist` :3757-3765，`__smp_call_single_queue`）；目标 CPU 在 IPI 处理里执行 `sched_ttwu_pending`（:3708-3739）→`ttwu_do_activate`（:3735）。慢路径直接锁目标 rq 并 ttwu_do_activate（:3866）。激活点 `ttwu_do_activate`（:3637-3662）调 `activate_task`（:3660）入队、`ttwu_do_wakeup`（:3661）；`ttwu_do_wakeup`（:3601-3634）先 `check_preempt_curr(rq, p, wake_flags)`（:3604，唤醒抢占判定，CFS 实现=check_preempt_wakeup，fair.c:11829 的 ops 绑定），再 `WRITE_ONCE(p->__state, TASK_RUNNING)`（:3605），再 `trace_sched_wakeup(p)`（:3606）。sched_wakeup 事件的语义注释在 `include/trace/events/sched.h:174-180`：

```c
/*
 * Tracepoint called when the task is actually woken; p->state == TASK_RUNNING.
 * It is not always called from the waking context.
 */
DEFINE_EVENT(sched_wakeup_template, sched_wakeup,
	     TP_PROTO(struct task_struct *p),
	     TP_ARGS(p));
```

走读：**sched_waking 与 sched_wakeup 的区别**由此定界——`sched_waking`（:166-172）"guaranteed to be called from the waking context"（在唤醒者上下文、try_to_wake_up 入口 :4091 发出）；`sched_wakeup`（:174-180）在任务真正入队清态时发出，"not always called from the waking context"——走 wakelist IPI 时它由目标 CPU 的 sched_ttwu_pending 发出，与唤醒者不同 CPU。两者共用模板（:141-164），字段 comm/pid/prio/target_cpu（:147-163），TP_printk `target_cpu=%03d`（:161）。

本 trace 的采集事件面【已证，批14-q11】：sched_switch 424,434 条、sched_waking 280,053 条、sched_blocked_reason 68,074 条、**sched_wakeup 0 条**。atrace 的 sched 类别配置里 sched_wakeup 是 REQ 项（frameworks/native/cmds/atrace/atrace.cpp:132-135，sched_switch/sched_wakeup 为 REQ、sched_waking/sched_blocked_reason 为 OPT），实际生效集合由采集端 ftrace 配置决定，配置未随 trace 保存【盲区】。因此本 trace 的 waker 归属全部基于 sched_waking（§4.3），且 §4.1 的 sched_wakeup 在本 trace 无直接观测点。

### 4.3 waker 字段记录的是谁（机制四问④）

机制：ftrace sched_waking/sched_wakeup 事件**不携带 waker 字段**（模板字段只有 comm/pid/prio/target_cpu，include/trace/events/sched.h:147-163）——"谁唤醒的"在 ftrace 事件层没有直接记录。Perfetto 的 waker_utid 由事件发射上下文关联（00 篇术语表 #17），"maybe interrupt" 标记（#18）即关联到的发射线程当时在运行、不能证明它就是代码里调用 wake_up 的线程。本 trace 的 raw 事件直接给出发射上下文（批14-q6）：

| rel_ts | 事件 | 发射 cpu | 发射线程（raw.utid） | wakee pid（事件字段） | target_cpu |
|---|---|---|---|---|---|
| 6,787,426,919 | sched_waking | 4 | kworker/4:1（tid 26591） | 1489（InputReader） | 3 |
| 6,796,227,034 | sched_waking | 3 | InputReader（tid 1489） | 756（system_server 主线程） | 2 |

走读【已证】：①Reader 唤醒事件的发射上下文是 kworker/4:1 在 cpu4——契约 :71 的"waker 关联 kworker/4:1（maybe interrupt 标记，不证明代码调用者）"复验一致；kworker/4:1 是中断/工作队列上下文的执行者，真正的 wake_up 调用者在内核栈里而 ftrace 不记录栈（调用者身份未证）。②主线程第一次醒的 waking 由 InputReader 在 cpu3 发出——与批06 的"waker=InputReader tid 1489"一致（06-InputDispatcher入队与裁决.md:542），target_cpu=2 与主线程后续 Running 的 cpu=2 一致（批14-q1）。③主线程 R 17,289,307（6,812,790,111 起）无 waker 记录且窗口内无 sched_waking（批14-q6 第二段查询为空）——与 §2.5 的 prev_state=0 切出形态互证：该 R 窗口的起点不是唤醒，而是 §2.6 的自愿调度点切出，Perfetto 无 waking 事件可关联（批06 的"无 waker 记录"由此机制化）。

### 4.4 select_task_rq：三个候选因素与能量模型（机制四问⑤）

选核入口 `kernel/sched/core.c:3471-3494`：

```c
int select_task_rq(struct task_struct *p, int cpu, int wake_flags)
{
	... /* 略 ：3472-3474，函数收口、lockdep 断言与空行 */
	if (p->nr_cpus_allowed > 1 && !is_migration_disabled(p))
		cpu = p->sched_class->select_task_rq(p, cpu, wake_flags);
	else
		cpu = cpumask_any(p->cpus_ptr);
	... /* 略 ：3480-3489，注释 */
	if (unlikely(!is_cpu_allowed(p, cpu)))
		cpu = select_fallback_rq(task_cpu(p), p);
	return cpu;
}
```

走读：单核可跑（nr_cpus_allowed==1）或迁移被禁时直接在 cpus_ptr 里挑；否则按调度类选——CFS 的实现是 select_task_rq_fair（fair.c:11838 的 ops 绑定）。选核的三个候选因素在 fair.c 分层实现：

**因素一 affine（跟唤醒者/上次 CPU 的亲和）**——`kernel/sched/fair.c:7016-7084` select_task_rq_fair 的骨架：

```c
static int
select_task_rq_fair(struct task_struct *p, int prev_cpu, int wake_flags)
{
	... /* 略 ：7019-7027，sync、变量与 sd_flag */
	if (trace_android_rvh_select_task_rq_fair_enabled() &&
	    !(sd_flag & SD_BALANCE_FORK))
		sync_entity_load_avg(&p->se);
	trace_android_rvh_select_task_rq_fair(p, prev_cpu, sd_flag,
			wake_flags, &target_cpu);
	if (target_cpu >= 0)
		return target_cpu;
```

```c
	if (wake_flags & WF_TTWU) {
		record_wakee(p);
		... /* 略 ：7042-7043，空行 */
		if (sched_energy_enabled()) {
			new_cpu = find_energy_efficient_cpu(p, prev_cpu, sync);
			if (new_cpu >= 0)
				return new_cpu;
			new_cpu = prev_cpu;
		}
		... /* 略 ：7049，空行 */
		want_affine = !wake_wide(p) && cpumask_test_cpu(cpu, p->cpus_ptr);
	}
```

```c
	rcu_read_lock();
	for_each_domain(cpu, tmp) {
		... /* 略 ：7055-7073，SD_WAKE_AFFINE 判定与 wake_affine 调用 */
```

```c
	if (unlikely(sd)) {
		/* Slow path */
		new_cpu = find_idlest_cpu(sd, p, cpu, prev_cpu, sd_flag);
	} else if (wake_flags & WF_TTWU) { /* XXX always ? */
		/* Fast path */
		new_cpu = select_idle_sibling(p, prev_cpu, new_cpu);
	}
	rcu_read_unlock();
	... /* 略 ：7082-7083，空行与 return new_cpu */
```

走读：want_affine（:7050）成立且唤醒者与 prev_cpu 同域（SD_WAKE_AFFINE）时走 `wake_affine`（:6039-6057：WA_IDLE 判 idle、WA_WEIGHT 比负载）；否则慢路径 find_idlest_cpu（:7076）或快路径 `select_idle_sibling`（:7079）。affine 的 idle 判定 `wake_affine_idle`（:5969-5994）：this_cpu idle 且共享缓存→选 this 或 prev；prev_cpu idle→选 prev（:5990-5991）。

**因素二 idle（空闲扫描）**——`kernel/sched/fair.c:6440-6524` select_idle_sibling 的候选序：

```c
static int select_idle_sibling(struct task_struct *p, int prev, int target)
{
	... /* 略 ：6442-6458 */
	if ((available_idle_cpu(target) || sched_idle_cpu(target)) &&
	    asym_fits_capacity(task_util, target))
		return target;
	... /* 略 ：6465-6472，注释 */
	if (prev != target && cpus_share_cache(prev, target) &&
	    (available_idle_cpu(prev) || sched_idle_cpu(prev)) &&
	    asym_fits_capacity(task_util, prev))
		return prev;
	... /* 略 ：6473-6504，per-cpu kthread 与 recent_used_cpu 候选 */
```

走读：依次考虑 target（唤醒者所在 CPU）、prev、recent_used_cpu，判据=available_idle_cpu＋`asym_fits_capacity`；之后在 sd_asym_cpucapacity/sd_llc 域内扫描（:6510-6524 起的 select_idle_capacity/select_idle_core）。

**因素三 容量（capacity/util）**——两个层面：select_idle_sibling 内的 asym_fits_capacity 过滤（:6462/:6470）与能量路径的 `fits_capacity`（fair.c:6918）。能量模型路径概述级：`kernel/sched/sched.h:3080-3096` 定义 `sched_energy_enabled()`＝CONFIG_ENERGY_MODEL＋CONFIG_CPU_FREQ_GOV_SCHEDUTIL＋`sched_energy_present` 静态键；`kernel/sched/topology.c:316-327` 的 sched_energy_set 按条件开/关该键（gki_defconfig 有 `CONFIG_ENERGY_MODEL=y`，arch/arm64/configs/gki_defconfig:74；运行时该键在目标设备是否开启**未证**——trace 无对应观测量）；`find_energy_efficient_cpu`（fair.c:6843-6998）在 root domain 未过载（:6865 `READ_ONCE(rd->overutilized)` 为假）时按 perf domain 算 spare capacity 与 compute_energy（:6961）选能量优 CPU。三因素的顺序【已证】：vendor hook 可整体短路（:7028-7034，`target_cpu >= 0` 直接返回）→能量路径→affine→idle 扫描。

**target_cpu=3 的产生点判定**（契约 :71 的"Reader 唤醒 target_cpu=3"）：trace 只给出 sched_waking 事件的 target_cpu=3 数值（批14-q6 复验一致），该数值是唤醒前 task_cpu(Reader)（:4091 发事件早于 :4192 选核，模板 :158 的 target_cpu=task_cpu(p)）——即 Reader 上次运行的 CPU。它是否等于最终选核结果、选核走了哪条分支（vendor hook/能量/affine/idle 扫描），trace 无 sched_wakeup 事件（批14-q11）且无 vendor hook 数据【未证】。

### 4.5 机制四问汇总（唤醒段）

- **规则**：跨 CPU 唤醒必经 try_to_wake_up：状态匹配→waking 事件→（D 态加发 blocked_reason）→TASK_WAKING→select_task_rq→set_task_cpu→ttwu_queue→activate→wakeup 事件→check_preempt_curr。
- **谁设置**：唤醒状态条件由唤醒者传入（wake_up_state 的 state 参数，core.c:4282-4284 `return try_to_wake_up(p, state, 0);`）；选核判据由 sched_domain 层级/能量模型/vendor hook 提供。
- **谁执行**：唤醒者线程（waking 事件的发射上下文，批14-q6 实测两例）；wakelist 路径下激活由目标 CPU 的 IPI 处理完成（sched_ttwu_pending，core.c:3708-3739）。
- **后果**：任务进目标 CPU 就绪队列、状态转 TASK_RUNNING（core.c:3605）；是否立刻抢占当前任务由 check_preempt_wakeup 决定（§5.3）。

## 5. R 态与 R+：可运行未运行的产生点

### 5.1 R 的两条产生路径

R（可运行未运行）只有两个进入方式，均已在 §2/§4 给出代码点：

1. **唤醒入队后未被 pick**：ttwu_do_activate 入队（core.c:3660）后任务在就绪队列等 pick_next_task 挑中；从入队到上 CPU 的时长即 R 窗口。本战役主例＝InputReader 的 R 7,951,192 ns（rel 6,787,426,919–6,795,378,111，契约 :71；批0 复验 :17 逐 ns 复现）。
2. **运行中在自愿调度点切出且未睡**：__schedule(SM_NONE) 且 prev_state==TASK_RUNNING（core.c:6413 条件为假，不出队）。本战役主例＝system_server 主线程的 R 17,289,307 ns（6,812,790,111 起，§2.5 的 prev_state=0 行）。

R+ 与 R 的区别只在切出入口（SM_PREEMPT vs SM_NONE，§2.6）；两者在就绪队列里的等待方式相同。

### 5.2 就绪队列上的等待：enqueue_task_fair / dequeue_task_fair / pick_next_task_fair

R 窗口的物理位置是 CFS 就绪队列（per-CPU 的 cfs_rq 红黑树）。入队侧——`kernel/sched/fair.c:5637-5643`：

```c
static void
enqueue_task_fair(struct rq *rq, struct task_struct *p, int flags)
{
	struct cfs_rq *cfs_rq;
	struct sched_entity *se = &p->se;
	int idle_h_nr_running = task_has_idle_policy(p);
	int task_new = !(flags & ENQUEUE_WAKEUP);
	int should_iowait_boost;
```

`kernel/sched/fair.c:5652-5670`：

```c
	util_est_enqueue(&rq->cfs, p);
	... /* 略 ：5654-5662，iowait boost 与 vendor hook */
	for_each_sched_entity(se) {
		if (se->on_rq)
			break;
		cfs_rq = cfs_rq_of(se);
		enqueue_entity(cfs_rq, se, flags);
		... /* 略 ：5669-5681，h_nr_running 累加、idle 统计、throttle 与 flags=ENQUEUE_WAKEUP */
	}
```

走读：enqueue_task_fair（fair.c:11824 的 ops 绑定）被 activate_task（core.c:3660）调用，flags 带 ENQUEUE_WAKEUP（唤醒入队）或新任务标志。enqueue_entity 把调度实体按 vruntime 插入红黑树——插入点的 vruntime 修正在 place_entity（fair.c:4222-4252，睡眠折扣，§5.3 引用）；util_est_enqueue（:5652，实现在 :3999-4013）把任务估利用度累加进 cfs_rq，供 cpufreq/EAS 读取。出队侧对称——`kernel/sched/fair.c:5757-5765` 与 :5817/:5824：

```c
static void dequeue_task_fair(struct rq *rq, struct task_struct *p, int flags)
{
	... /* 略 ：5759-5764，变量（task_sleep=flags & DEQUEUE_SLEEP 等） */
	util_est_dequeue(&rq->cfs, p);
	... /* 略 ：5767-5816，dequeue_entity 循环与 throttle */
	sub_nr_running(rq, 1);
	... /* 略 ：5819-5821，sched_idle 均衡提示 */
dequeue_throttle:
	util_est_update(&rq->cfs, p, task_sleep);
	hrtick_update(rq);
}
```

走读：dequeue_task_fair 被 deactivate_task（core.c:6436）以 DEQUEUE_SLEEP 调用——S/D 出队走这里；task_sleep 时还把 `set_next_buddy` 置上（:5789-5790），使睡者的兄弟实体下次优先被挑。挑选侧——`kernel/sched/fair.c:7388-7397`：

```c
pick_next_task_fair(struct rq *rq, struct task_struct *prev, struct rq_flags *rf)
{
	struct cfs_rq *cfs_rq = &rq->cfs;
	... /* 略 ：7390-7395，变量 */
again:
	if (!sched_fair_runnable(rq))
		goto idle;
```

`kernel/sched/fair.c:4556-4573`（实体挑选）：

```c
static struct sched_entity *
pick_next_entity(struct cfs_rq *cfs_rq, struct sched_entity *curr)
{
	struct sched_entity *left = __pick_first_entity(cfs_rq);
	... /* 略 ：4560-4572，se 声明、vendor hook 与 curr/left 比较 */
	se = left; /* ideally we run the leftmost entity */
	... /* 略 ：4575-4604，skip/next/last buddy 三步覆盖 */
```

走读：挑选规则＝vruntime 最左实体优先（`__pick_first_entity`），随后 next buddy（唤醒抢占时 set_next_buddy 置上，fair.c:7330）与 last buddy（被抢占者，:7351）可在不公平超过门槛前插队（:4594-4604 的 wakeup_preempt_entity<1 判定）。**R 窗口的机制含义由此闭合**：任务已入队（enqueue_entity 完成）但尚未成为挑选结果——要么不是最左，要么 buddy 覆盖；窗口时长就是从入队到被 pick 的间隔。check_preempt_wakeup 在唤醒入队时已经做过一次"要不要让当前任务立刻让位"的判定（§5.3）；判"不让"或 NEED_RESCHED 尚未消费时，任务就在队列里等下一次 pick。

### 5.3 唤醒侧的抢占判定：谁把 TIF_NEED_RESCHED 置上

`kernel/sched/fair.c:7245-7337` check_preempt_wakeup（节选）：

```c
static void check_preempt_wakeup(struct rq *rq, struct task_struct *p, int wake_flags)
{
	... /* 略 ：7247-7323，实体、vendor hook、throttle、NEXT_BUDDY 与 idle/batch 策略分支 */
	if (wakeup_preempt_entity(se, pse) == 1) {
		... /* 略 ：7325-7330，注释与 set_next_buddy */
		goto preempt;
	}
	... /* 略 ：7333，空行 */
	return;

preempt:
	resched_curr(rq);
	... /* 略 ：7338-7351，LAST_BUDDY */
```

走读：唤醒入队时（ttwu_do_wakeup→check_preempt_curr，core.c:3604）CFS 用 `wakeup_preempt_entity`（fair.c:7199-7212）比较 curr 与唤醒实体的 vruntime 差：vdiff>gran（gran=wakeup_gran，fair.c:7165-7183，sysctl_sched_wakeup_granularity 默认 1,000,000 ns，fair.c:87）才判抢占并 resched_curr。睡眠折扣在入队点 `place_entity`（fair.c:4222-4252）：非 initial（唤醒入队）时 `vruntime -= thresh`（:4243-4246，GENTLE_FAIR_SLEEPERS 下 thresh=sysctl_sched_latency/2=3 ms，fair.c:42）——睡过的事件处理线程醒来时 vruntime 被减掉一段，通常能立刻排到队首。这两段机制决定了：**多数唤醒的调度延迟在微秒量级；出现长 R 窗口的条件是"唤醒时未判抢占＋后续多次 pick 都没挑到它"**。

### 5.4 trace 实证：主线程 R 17,289,307 窗口的两侧

起点侧【已证，批14-q7/q9】：主线程 Running 72,308 ns（6,812,717,803–6,812,790,111）后以 prev_state=0 切出（next=UsbFfs-worker tid 26884）。切出前 ±1.5 ms 内无 wakee=26884 的 sched_waking（批14-q9 第二段为空）——TIF_NEED_RESCHED 的置位来源不是 UsbFfs 的新唤醒，候选为周期 tick 的公平性检查或更早的唤醒【未证：trace 无 tick 事件与 NEED_RESCHED 事件】。切出机制本身已证：SM_NONE＋prev_state==0→不出队（core.c:6413）→留在就绪队列 17,289,307 ns 未被 pick。

中途侧【已证，批14-q2】：该窗口内 CPU2 持续运行其他线程（见 §5.5 表），主线程保持 R。终点侧【已证，批14-q1】：6,830,079,418 转 Running 286,693——终点无 sched_waking 关联（批14-q6 未查到 wakee=756 的 waking 在 6,812,000,000–6,830,200,000 区间），对应批06 Q6 的"无 waker 记录"；被 pick 回来的方式（tick 后重选/其他 CPU 迁移/负载均衡）未证。

对照组【已证，批14-q9】：主线程第一次 prev_state=0 切出（6,805,709,380，next=tbox_ap tid 1136）有完整因果链：6,805,690,149 主线程自身在 Running 中发出 sched_waking（wakee=1136，target_cpu=2）→ wake_up 的 check_preempt_curr 对当前任务做抢占判定（fair.c:7324 判"该让"则 resched_curr）→ 19,231 ns 后主线程在调度点以 prev_state=0 切出、让 tbox_ap 上 CPU（6,805,709,380−6,805,690,149）。此链完整覆盖"wake_up→resched_curr→切出→R"四步，是 §5.3 机制的 trace 实证。

### 5.5 主线程 R 双窗的 CPU2 竞争裁剪统计（批06 转办项执行）

批06 明确："批00 §3.7 的 CPU3 竞争裁剪统计……不能外推到主线程窗口——批14 需对 6,796–6,830 s 的主线程可运行窗口重做同一方法论的裁剪统计"（06-InputDispatcher入队与裁决.md:974）。本节完成。方法与批0 复验三相同（重叠裁剪求和），窗口端点取批06 Q6 的 R 行（起点/时长），CPU 取主线程 Running 段的实测 cpu=2（批14-q1）。

**窗口 1**：rel 6,796,227,034–6,805,540,265（R 9,313,231，起点 waker=InputReader）。CPU2 全部 sched slice 裁剪求和（批14-q2）：

| tid | 线程 | 裁剪后运行时 | slices |
|---|---|---|---|
| 661 | surfaceflinger | 2,666,615 ns | 6 |
| 26884 | UsbFfs-worker | 1,546,154 ns | 6 |
| 269 | logd.writer | 1,258,614 ns | 11 |
| 1819 | ech.lyra.daemon | 1,125,347 ns | 7 |
| 5010 | reader | 1,014,810 ns | 7 |
| 5008 | AudioIn_46 | 931,845 ns | 5 |
| 554 | spi@1.0-service | 294,153 ns | 2 |
| 1790 | gnss@1.1-servic | 265,616 ns | 1 |
| 29516 | screenrecord | 76,039 ns | 1 |
| 10 | rcu_preempt | 57,885 ns | 1 |
| 10607 | kworker/u16:0 | 45,692 ns | 1 |
| 248 | spi2 | 30,461 ns | 1 |
| 合计 | 49 slice | **9,313,231 ns＝窗口时长** | 49 |

**窗口 2**：rel 6,812,790,111–6,830,079,418（R 17,289,307，无 waker 记录）。CPU2 裁剪求和（批14-q2，前 12 行）：

| tid | 线程 | 裁剪后运行时 | slices |
|---|---|---|---|
| 26884 | UsbFfs-worker | 2,659,077 ns | 14 |
| 4881 | L-hotwordasr-k | 2,286,769 ns | 5 |
| 661 | surfaceflinger | 1,937,192 ns | 1 |
| 4880 | L-MessageProces | 1,380,308 ns | 4 |
| 5010 | reader | 1,362,615 ns | 7 |
| 26976 | logd.reader.per | 1,355,922 ns | 20 |
| 1489 | InputReader | 1,258,654 ns | 5 |
| 441 | logd.reader.per | 1,196,962 ns | 17 |
| 5008 | AudioIn_46 | 854,654 ns | 5 |
| 1790 | gnss@1.1-servic | 571,038 ns | 1 |
| 431 | ylogkat | 531,000 ns | 3 |
| 10607 | kworker/u16:0 | 464,576 ns | 8 |
| （另有 8 线程各 ≤339,192 ns） | … | 合计差额 | … |
| 合计 | 106 slice | **17,289,307 ns＝窗口时长** | 106 |

判定与边界：

- 【已证】两个窗口内 CPU2 均无 idle（裁剪合计恰等于窗口时长；批14-q3 显示同期 idle 只在 cpu0/1/4 出现）；窗口 1 含 49 个 slice、窗口 2 含 106 个，分别由 12 个、20 个线程分享；无 swapper 行——与批0 复验三对 CPU3 窗口的判定同型。
- 【已证】两个窗口内主线程均无 slice（R＝未获 CPU），窗口内 surfaceflinger 分别占 2.667 ms/1.937 ms、logd.writer 占 1.259 ms、adb 通道（UsbFfs-worker）占 1.546/2.659 ms——多线程混合竞争，单个线程的致因贡献未证（同批0 复验三 :27 的因果边界："并发运行为真实观测，单线程致因未证"）。
- 【未证】两窗口与 34.738 ms 空档的因果（批06 表格 C9 列的"未证→批14"至此升级为"竞争构成已测、因果仍未证"，批15 终判）。
- 【已证，边界】窗口 2 内 InputReader 自身运行 1,258,654 ns（5 slice）——Reader 在主线程 R 窗口内占用同一 CPU2，两线程竞争同一资源；此为构成性观测，不构成因果。

### 5.6 Reader R 7,951,192 的框架归位

批0 复验三的 CPU3 七线程裁剪表（getevent 1,716,807/shell svc 1,095,693/slogmodem 1,087,115/dds-recorder 597,924/composer 577,884/MP4WtrVidTrkThr 419,654/fgecomservice 两线程 420,307＋414,807 ns，过程记录/批0-证据复验三-CPU窗口与event0统计.md:9-27）在本篇框架下归位：Reader 的 R 7,951,192 ns 属 §5.1 路径 1（唤醒入队后未被 pick），其唤醒 target_cpu=3（§4.4），落 CPU3 后与上述线程竞争。归因纪律维持契约 :185——"InputReader R 7.951 ms 不外推为 50.928 ms 的原因"；批04 的口径结论（R=调度等待，实际处理窗口是 Running 996,346 ns，04-EventHub与InputReader读取段.md:981）在此框架下就是"路径 1 的 R 窗口＋路径内 Running 段"的组合。

## 6. D 态与 write_pmsg

### 6.1 D 的产生与不可中断性（机制四问③）

D=TASK_UNINTERRUPTIBLE（sched.h:87）。"不可中断"的代码判据在信号路径——`include/linux/sched/signal.h:389-397`：

```c
static inline int signal_pending_state(unsigned int state, struct task_struct *p)
{
	if (!(state & (TASK_INTERRUPTIBLE | TASK_WAKEKILL)))
		return 0;
	if (!signal_pending(p))
		return 0;

	return (state & TASK_INTERRUPTIBLE) || __fatal_signal_pending(p);
}
```

走读：state 不含 TASK_INTERRUPTIBLE(0x1) 或 TASK_WAKEKILL(0x100) 时直接返回 0——纯 TASK_UNINTERRUPTIBLE(0x2) 不命中。该返回值用于 __schedule 的 :6414 分支：返回 0→不走"清状态不出队"分支→deactivate_task 出队（:6436）。发送侧 `kernel/signal.c:763-774`：

```c
void signal_wake_up_state(struct task_struct *t, unsigned int state)
{
	set_tsk_thread_flag(t, TIF_SIGPENDING);
	... /* 略 ：766-770，注释 */
	if (!wake_up_state(t, state | TASK_INTERRUPTIBLE))
		kick_process(t);
}
```

走读：信号唤醒的 state 参数只附加 TASK_INTERRUPTIBLE——try_to_wake_up 的状态匹配（ttwu_state_match，:4058/:4075）对纯 D 任务不成立，信号不触发其唤醒。四问作答：**规则＝纯 D 态不响应任何普通信号；谁设置＝set_current_state(TASK_UNINTERRUPTIBLE) 的等待方（I/O 完成路径等）；谁执行＝事件生产者经 try_to_wake_up 唤醒（如块设备完成回调）；后果＝D 的退出只有事件完成一条路，等待时长由事件生产者决定，调度器既不能提前结束它也没有信号打断路径——混合态 TASK_KILLABLE（sched.h:106）例外，可被致命信号唤醒**。

### 6.2 sched_blocked_reason 只对 D 发出：S 没有 blocked_function 的根源

`kernel/sched/core.c:4119-4120`（§4.1 已引）：

```c
	if (READ_ONCE(p->__state) & TASK_UNINTERRUPTIBLE)
		trace_sched_blocked_reason(p);
```

走读【已证】：try_to_wake_up 只在被唤醒任务处于 TASK_UNINTERRUPTIBLE 时发 sched_blocked_reason。于是 trace 形成结构性差异——D 态行带 blocked_function 列（事件里有 caller），S 态行没有。这解释了本战役证据表的形态：Reader 的 D 3,716,500 行有 `write_pmsg$…`（契约 :74），战役窗口内全部 S 行（Launcher 主线程 50,928,000、Dispatcher 50,445,654、Reader 3,852,692/8,639,231 等 9 行，批14-q14 逐行核对）的 blocked_function 均为空——不是采集丢了 S 的信息，是内核就不发。

事件定义 `include/trace/events/sched.h:490-509`：

```c
TRACE_EVENT(sched_blocked_reason,
	... /* 略 ：491-495，TP_PROTO/TP_ARGS */
	TP_STRUCT__entry(
		__field( pid_t,	pid	)
		__field( void*, caller	)
		__field( bool, io_wait	)
	),
```

```c
	TP_fast_assign(
		__entry->pid	= tsk->pid;
		__entry->caller = (void *)get_wchan(tsk);
		__entry->io_wait = tsk->in_iowait;
	),

	TP_printk("pid=%d iowait=%d caller=%pS", __entry->pid, __entry->io_wait, __entry->caller)
);
```

走读：00 篇术语表 #20 的"caller 字段（get_wchan 取得、按 %pS 符号化）"复验一致：caller=get_wchan(tsk)（:504）、io_wait=tsk->in_iowait（:505）、TP_printk 三字段（:508）。Perfetto 把 caller 存入 thread_state 的 blocked_function 列（列名差异沿用 #20 的声明）。

### 6.3 blocked_function 为什么能记录内核函数名（机制四问⑥）

caller 的值来自 get_wchan——`arch/arm64/kernel/process.c:534-561`：

```c
unsigned long get_wchan(struct task_struct *p)
{
	... /* 略 ：535-547，局部变量、current/running 排除、取栈与 start_backtrace 取帧起点 */
	do {
		if (unwind_frame(p, &frame))
			goto out;
		if (!in_sched_functions(frame.pc)) {
			ret = frame.pc;
			goto out;
		}
	} while (count++ < 16);
	... /* 略 ：554-561，循环收口、out 标签、put_task_stack 与 return */
```

走读，四问作答：**规则**＝取睡眠任务保存的栈帧指针，回溯最多 16 帧，跳过调度器自身函数（in_sched_functions），返回第一个内核态 PC。**谁设置**＝任务被切出时 cpu_switch_to 保存的 thread.cpu_context（thread_saved_fp/pc 读取，:546 取帧起点；任务非 current 才可查，:539-540 的 task_is_running 排除）。**谁执行**＝唤醒线程在 try_to_wake_up :4120 内（唤醒者替被唤醒者记录）。**后果**＝PC 经 TP_printk 的 %pS 符号化为内核函数名——即 trace 里 blocked_function 的内容。对 Reader 的 D 窗口，该 PC 符号化为 write_pmsg（§6.4），含义：**Reader 线程进入 D 时最后一个非调度内核函数是 write_pmsg**——它卡在 write_pmsg 内部（函数内的 mutex_lock 或 psinfo->write_user，两者都是睡眠点）。

本 trace 的完整取值（批14-q10，逐字）：`blocked_function = write_pmsg$6fb35a9abf14f1ffebba6021202469c1`。契约 :74 与批0 复验 :19 的"write_pmsg$…"复验一致，后缀串为 trace 记录的符号串原样。

### 6.4 write_pmsg 调用链查证

kernel 侧——`fs/pstore/pmsg.c:12-46`（走读全文 94 行）：

```c
static DEFINE_MUTEX(pmsg_lock);

static ssize_t write_pmsg(struct file *file, const char __user *buf,
			  size_t count, loff_t *ppos)
{
	... /* 略 ：17-29，record 初始化、access_ok */
	mutex_lock(&pmsg_lock);
	ret = psinfo->write_user(&record, buf);
	mutex_unlock(&pmsg_lock);
	return ret ? ret : count;
}
```

`fs/pstore/pmsg.c:37-41`：

```c
static const struct file_operations pmsg_fops = {
	.owner		= THIS_MODULE,
	.llseek		= noop_llseek,
	.write		= write_pmsg,
};
```

走读：/dev/pmsg0 的 write 系统调用落 write_pmsg（.write 赋值 :40）；两个睡眠点——全局互斥量 pmsg_lock（:12/:31，mutex_lock 睡眠为 TASK_UNINTERRUPTIBLE）与 psinfo->write_user（:32，pstore 后端写入）。后端由配置决定：gki_defconfig 有 `CONFIG_PSTORE=y`、`CONFIG_PSTORE_CONSOLE=y`、`CONFIG_PSTORE_PMSG=y`、`CONFIG_PSTORE_RAM=y`（arch/arm64/configs/gki_defconfig:591-594）——RAM 后端。**D 窗口 3,716,500 ns 的具体构成（等 pmsg_lock 还是等 write_user 完成）未证**：trace 无锁事件；get_wchan 只给出函数名（write_pmsg），不区分函数内睡眠点。

用户态侧——谁写 /dev/pmsg0：`system/logging/liblog/logger_write.cpp:189-220`（write_to_log，节选尾部）：

```c
  ret = LogdWrite(log_id, &ts, vec, nr);
  PmsgWrite(log_id, &ts, vec, nr);

  return ret;
}
```

走读：liblog 默认写路径（default logger 为 `__android_log_logd_logger`，logger_write.cpp:160；其函数体 :278-290 组三个 iovec 后调 write_to_log :290）对每条日志同时做两件事——LogdWrite 发 logd socket＋PmsgWrite 写 pmsg（:218-219）。`system/logging/liblog/pmsg_writer.cpp:70-97` 的 PmsgWrite 先经构建期门槛，再取 fd 写入——`system/logging/liblog/pmsg_writer.cpp:70-94`：

```cpp
int PmsgWrite(log_id_t logId, struct timespec* ts, struct iovec* vec, size_t nr) {
  ... /* 略 ：71-77，局部变量 */
  if (!ANDROID_DEBUGGABLE) {
    if (logId != LOG_ID_EVENTS && logId != LOG_ID_SECURITY) {
      return -1;
    }
    ... /* 略 ：82-91，EVENTS 的 SNET 检查 */
  }

  GetPmsgFd();
```

走读：`if (!ANDROID_DEBUGGABLE)` 分支（:78-92）只放行 LOG_ID_EVENTS/LOG_ID_SECURITY；debuggable 构建下各 buffer 均走到 `GetPmsgFd()`（:94，函数体 :34-58，open("/dev/pmsg0", O_WRONLY | O_CLOEXEC) 在 :41、置零重开在 :46）——即 pmsg 写入的进程侧入口。本 trace 的写入已发生（blocked_function 佐证），故该门槛在本设备上已放行；具体是 EVENTS/SECURITY buffer 还是 debuggable 全放行，未证。`__android_log_bwrite`（:418-428，LOG_ID_EVENTS）与 `__android_log_buf_write`（:319，任意 buffer）都汇入 write_to_log。

区分证据【已证】：atrace 打点不走 pmsg——cutils 的 WRITE_MSG 写 trace_marker fd（system/core/libcutils/trace-dev.inc:216），与 liblog 的 pmsg 路径是两条独立路径。因此 trace 内所有 ATRACE slice（notifyMotion 等）不产生 pmsg 写；能触达 /dev/pmsg0 的是 ALOGx 系列日志调用。InputReader 侧的 ALOG 家族存在（frameworks/native/services/inputflinger/reader/InputReader.cpp:117/:159/:185/:195 等；mapper/TouchInputMapper.cpp 内 79 处 ALOG，grep 计数），inputflinger 目录无直接 pmsg 引用（grep 无命中）。

查证结论：**机制链已证**（InputReader 线程的 ALOGx→liblog write_to_log→PmsgWrite→/dev/pmsg0→write_pmsg，两端行号如上）；**具体语句未定位**（D 窗口 6,801,836,572–6,805,553,072 内触发的是哪条日志语句，trace 无 log 内容与 syscall 事件——盲区，留待带 logd 数据的补采）。logd 进程自身另有 pmsg 写（system/logging/logd/LogTags.cpp:499-508，16 K 速率限流写 tag 表），属 logd 进程，与 Reader 线程无关。

### 6.5 D 窗口的段位与因果边界

- 【已证】Reader 在 CPU3 上进入 D（D 前两个 Running 177,731/120,884 ns 均 cpu=3，批14-q10）；D 窗口内 tid 1489 无任何 sched slice（出队，core.c:6436）——D 期间该线程不占任何 CPU。
- 【已证】D 不可被信号打断（§6.1）；3,716,500 ns 全长由事件侧（pmsg_lock 持有者或后端写入完成时刻）决定。
- 【未证】pmsg_lock 的持有者身份；D 窗口与同 ID 关键路径（0x3b3b7f02）的因果——契约 :74 口径维持（"发生在 notifyMotion 之后，与同 ID 关键路径的因果未证"）；批06 已证分发线程与主线程在该空档无 D 态（06-InputDispatcher入队与裁决.md:978），"日志税"旧表述按 00 篇 §5 禁复活处理。
- 【盲区】pmsg 后端写入的物理耗时构成（RAM 后端的具体写路径时长无观测量）。

## 7. 迁移约束：其他核 idle 不证明可迁核

### 7.1 can_migrate_task 与 task_hot

负载均衡拉取任务时的迁移判据链——`kernel/sched/fair.c:7919-8006` can_migrate_task（节选）：

```c
int can_migrate_task(struct task_struct *p, struct lb_env *env)
{
	... /* 略 ：7921-7943，变量、断言、vendor hook、throttle 与 per-cpu kthread 排除 */
	if (!cpumask_test_cpu(env->dst_cpu, p->cpus_ptr)) {
		... /* 略 ：7945-7974，LBF_SOME_PINNED 记账 */
		return 0;
	}
	... /* 略 ：7977-7983，LBF_ALL_PINNED 记账 */
	if (task_running(env->src_rq, p)) {
		schedstat_inc(p->se.statistics.nr_failed_migrations_running);
		return 0;
	}
```

```c
	... /* 略 ：7985-7993，注释与 active balance */
	tsk_cache_hot = migrate_degrades_locality(p, env);
	if (tsk_cache_hot == -1)
		tsk_cache_hot = task_hot(p, env);
	... /* 略 ：7998，空行 */
	if (tsk_cache_hot <= 0 ||
	    env->sd->nr_balance_failed > env->sd->cache_nice_tries) {
		... /* 略 ：8001-8004，schedstat 记账 */
		return 1;
	}
```

走读：四道判据——①vendor hook 可整体拒绝（:7926）；②目标 CPU 不在 p->cpus_ptr 内→拒绝（:7944，亲和性硬约束）；③任务正在源 CPU 运行→拒绝（:7980）；④cache-hot 判定 `task_hot`（:7813-7853）：

```c
static int task_hot(struct task_struct *p, struct lb_env *env)
{
	... /* 略 ：7815-7835，sched_class/idle/SMT/buddy 前置 */
	if (sysctl_sched_migration_cost == -1)
		return 1;
	... /* 略 ：7840-7848，cookie 与 0 特例 */
	delta = rq_clock_task(env->src_rq) - p->se.exec_start;

	return delta < (s64)sysctl_sched_migration_cost;
}
```

走读：任务最近执行距现在 `sysctl_sched_migration_cost`（默认 500,000 ns，fair.c:90）以内判 cache-hot→拒绝迁移。**"其他核 idle 不证明可迁核"的机制依据**由此闭合：某核 idle 只说明它此刻的 pick 会选 idle 线程；它要获得别的核上的任务必须由负载均衡主动拉取（balance_fair/newidle_balance 触发，fair.c:7155-7162），而拉取受 ①cpus_ptr 亲和（sched_setaffinity/cpuset 的生效掩码，判定点 core.c:3475-3478 与 :3490）、②cache-hot 窗口（500 μs）、③均衡时机（周期 tick/newidle 点）三重约束；唤醒侧的选核（§4.4）同理先看 cpus_ptr（core.c:3475/:3490）。【已证：机制】【未证：本战役三窗口内是否有均衡尝试发生——trace 无 lb 事件，采集面未含 sched_balance 类事件】

### 7.2 对本战役三个窗口的含义

- Reader R 窗口（CPU3 无 idle，批0 复验三）与主线程 R 双窗（CPU2 无 idle，批14-q2）：两窗口内"别的核有没有 idle"与"该线程为何没被挑中/为何不去别的核"是两个独立问题——跨核移动只发生在 §7.1 的约束满足时，本 trace 无法证明发生过（无 lb 事件）。
- Launcher 主线程 S 窗口：S 期间 CPU4 的 idle 段（批14-q13）与 CPU0/1 的 idle 段（批14-q3）都不构成"S 线程本可早跑"的推论——S 线程根本不在任何就绪队列，迁移约束无从谈起（等的事件不在，迁移无意义）。

## 8. 机制四问汇总表

| 机制 | 规则是什么 | 谁设置 | 谁执行 | 后果 |
|---|---|---|---|---|
| 状态字符串翻译（§2） | prev_state 按 TASK_REPORT 掩码翻 S/D/…，无命中位为 R，TASK_REPORT_MAX 位加 "+" | 睡眠方 set_current_state；切出方 sched_mode | __schedule→trace_sched_switch→TP_printk | thread_state 的 S/R/R+/D 与内核状态一一对应 |
| R+ 产生（§2.6） | 仅抢占入口（SM_PREEMPT）切出的可运行任务记 R+ | check_preempt_wakeup/tick 置 TIF_NEED_RESCHED | preempt_schedule_common/preempt_schedule_irq | 任务不出队，等下一次 pick |
| S 语义（§3） | S=TASK_INTERRUPTIBLE，出队等待事件 | 睡眠方；事件方 wake_up | wait queue 回调→try_to_wake_up | S 时长=事件产生延迟＋33,923 ns 级调度延迟（本例实测） |
| D 语义（§6.1） | 纯 TASK_UNINTERRUPTIBLE 不响应信号 | I/O 等待方 set_current_state | 事件完成方唤醒 | 退出唯一路径=事件完成；KILLABLE 混合态例外 |
| waker 归属（§4.3） | ftrace 事件不带 waker 字段，Perfetto 按发射上下文关联 | 内核发 sched_waking（try_to_wake_up :4091） | 唤醒者上下文 | maybe interrupt 标记不证明代码调用者（#18） |
| blocked_function（§6.3） | get_wchan 回溯 16 帧取第一个非调度 PC，%pS 符号化 | 睡眠任务的栈 | 唤醒线程在 :4120 替其记录 | D 态有函数名，S 态无此事件（:4119 判定） |

## 9. trace 配对总表（数值逐字口径）

| 数值 | 值 | 出处 |
|---|---|---|
| Launcher 主线程 S | 6,779,836,842–6,830,764,842，50,928,000 ns | 契约 :69；批0 复验 :15；批14-q12 |
| Launcher S 的 waker | InputDispatcher tid 1488 | 批0 复验二 :8-14 |
| 主线程醒后调度延迟 | R 33,923 ns（6,830,764,842 起） | 批0 复验二 :11 |
| InputReader R | 6,787,426,919 起，7,951,192 ns | 契约 :71；批0 复验 :17 |
| InputReader CPU3 Running | 6,795,378 起，996,346 ns | 契约 :71；批0 复验 :45 |
| Reader 状态序列 | R 7,951,192→Running 996,346→S 3,852,692→R 848,846→Running 177,731→R+ 461,962→Running 120,884→D 3,716,500→R 1,602,116→…→S 8,639,231 | 批0 复验 :44-46；批14-q10 覆盖 6,800–6,806 段（cpu=3） |
| InputReader D | 6,801,836,572–6,805,553,072，3,716,500 ns，blocked_function=write_pmsg$6fb35a9abf14f1ffebba6021202469c1 | 契约 :74；批14-q10（全串逐字） |
| InputDispatcher S | 6,779,885,149–6,830,330,803，50,445,654 ns | 契约 :73；批0 复验 :23 |
| Dispatcher 状态序列 | S 50,445,654→R 35,308→Running 1,009,154→S 615,769→R 6,164,154→Running 194,230→R 4,906,116 | 批0 复验 :48-50 |
| system_server 主线程 R 双窗 | R 9,313,231（6,796,227,034 起，waker=InputReader）＋R 17,289,307（6,812,790,111 起，无 waker 记录） | 批06-q6；批14-q1（含 cpu=2） |
| Reader 唤醒 sched_waking | rel 6,787,426,919、cpu=4、发射 kworker/4:1、wakee 1489、target_cpu=3 | 契约 :71；批14-q6 |
| CPU3 竞争窗口七线程 | getevent 1,716,807/shell svc 1,095,693/slogmodem 1,087,115/dds-recorder 597,924/composer 577,884/MP4WtrVidTrkThr 419,654/fgecomservice 420,307＋414,807 ns | 批0 复验三 :9-27 |
| CPU2 双窗裁剪合计 | 9,313,231/9,313,231 与 17,289,307/17,289,307（无 idle） | 批14-q2 |
| CPU4 S 窗口构成 | 覆盖 50,928,000/50,928,000；swapper 3,617,928（26 段）；HeapTaskDaemon 13,228,767；binder:756_2 7,986,539 | 批14-q13 |
| sched 事件计数 | sched_switch 424,434/sched_waking 280,053/sched_blocked_reason 68,074/sched_wakeup 0 | 批14-q11 |

## 10. 未证边界与盲区清单

1. 【盲区】Launcher 主线程 S 50,928 ms 的具体等待条件（哪个 fd/wait queue/超时）：trace 无 syscall/irq 事件（00 篇 §3.7 维持；本篇框架不改变该判定）。
2. 【盲区】本 trace 采集的 ftrace 事件集合为何无 sched_wakeup（atrace.cpp:133 为 REQ 项但计数为 0）：采集端配置未随 trace 保存（批14-q11）。
3. 【未证】Reader 唤醒 target_cpu=3 的选核分支（vendor hook/能量路径/affine/idle 扫描四选一）：trace 只有 target_cpu 数值且无 sched_wakeup 对照（§4.4）。
4. 【未证】EAS（find_energy_efficient_cpu）在本设备运行时的激活状态：CONFIG_ENERGY_MODEL=y 已证（gki_defconfig:74），sched_energy_present 静态键运行态无观测量（§4.4）。
5. 【未证】主线程 R 双窗的竞争归因：窗口构成已测（批14-q2），单线程致因与 34.738 ms 的因果未证——批06 C9 的"未证→批14"在构成立场收口，因果判定批15 终判（§5.5）。
6. 【未证】主线程两次 prev_state=0 切出（6,805,709,380/6,812,790,111）时 TIF_NEED_RESCHED 的置位来源（tick 公平性检查/更早唤醒）：前者有 wake 链旁证（tbox_ap 的 waking 由主线程自身发出，批14-q9），后者无（§5.4）。
7. 【未证】R 17,289,307 终点（6,830,079,418 转 Running）的 pick 触发方式（tick/迁移/均衡）：无对应事件（§5.4）。
8. 【未证】D 3,716,500 ns 的构成（pmsg_lock 竞争 vs psinfo->write_user 执行）：get_wchan 只到函数级（§6.4）。
9. 【盲区】触发 pmsg 写的具体日志语句（InputReader 哪条 ALOG）：机制链已证（§6.4），语句未定位；D 与同 ID 关键路径因果未证（契约 :74）。
10. 【未证】窗口内是否发生负载均衡尝试：采集面无 lb 事件（§7.1）。
11. 【已证边界】CPU4 S 窗口内其他线程的运行（HeapTaskDaemon 13,228,767 ns 等）是构成性观测，不构成 S 成因（§3.2/§3.4）。

## 11. 三态汇总

三态标注贯穿全文：正文逐段标注（已证字样 35 处、未证 30 处、盲区 11 处，含定义句与引用）＋§10 汇总（未证 7 项：清单 3-8、10；盲区 3 项：清单 1-2、9；已证边界 1 项：清单 11）。核心已证项：状态字符串产生链（§2.5 逐行闭合）、S=等待事件（§3.2 的 idle 段直接证明）、唤醒路径与两事件区别（§4.1/4.2）、waker=发射上下文（§4.3 的 raw 实证）、R+/R 双产生点（§2.5/§5.4）、D 不可信号打断（§6.1）、blocked_reason 只对 D（§6.2）、write_pmsg 机制链（§6.4）、迁移四道判据（§7.1）。核心未证/盲区维持既有契约口径，未新增降级表述。
