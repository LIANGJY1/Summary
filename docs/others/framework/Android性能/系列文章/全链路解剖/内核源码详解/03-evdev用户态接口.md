# 03 · evdev 用户态接口

> AppList 性能全链路解剖 · 第 03 篇（批03 作业单执行稿）
> 源码基准：kernel 引 `/home/liang/Project/AAOS13_kernel/common`（5.15.41，android13-5.15 GKI，HEAD `24d27dff64c4`），Android 引 `/home/liang/Project/AAOS13` 精树。写作前已实际读取核对全部引文行号；kernel 树只读，本文是唯一载体。
> 时间口径：trace 数值一律 Perfetto 相对时间 `00:00:06.xxxxxx`；touch.txt / event0_帧间隔.csv 中的秒值（如 6080.481854）是 getevent 采集端的设备时钟原始口径（来源与含义见 §6.3），引用时单独标注，不与 trace 相对时间混用。
> 证据三态：**已证**（源码或 trace 明确支持）／**未证**（有候选无证据）／**盲区**（采集配置或源码缺失）。
> 摘抄规则：每个代码块只摘 ≤10 行逐字原文；块内 `... /* 略 ：a-b */` 为省略标记，省略段行号随标。

## 0. 本篇范围与上下游边界

本篇走读 `drivers/input/evdev.c` 全部 1450 行（证据：`wc -l` = 1450，见过程记录），外加 `include/uapi/linux/input.h`（514 行）、`drivers/input/input.c` 的分发与时戳函数段、`kernel/time/timekeeping.c` 的 `ktime_get` 段，以及两个消费端对照段：Android `frameworks/native/services/inputflinger/reader/EventHub.cpp` 的 `openDeviceLocked`/`getEvents` 读事件段与 `system/core/toolbox/getevent.c`。边界如下：

- **上游边界**：input core 的 `input_pass_values`（`drivers/input/input.c:128`）调用 handler 回调为止。input core 的处置判定与攒批语义归第 02 篇；本篇只引用其已核对的行号作为到达路径。
- **下游边界**：`read()` 返回用户态字节流为止。EventHub 把 `read()` 结果组装成 RawEvent 并进入 InputReader 的过程归第 04 篇（本文 §10 给出"见批04"衔接点）。
- **本篇职责（批03 作业单，战役计划与台账.md:100-104）**：evdev_open/client 分配/缓冲大小、evdev_events 写缓冲、evdev_read packet 化、evdev_poll、缓冲满与 SYN_DROPPED 机制、evdev_grab、消费端实际调用的 ioctl 子集、时钟换算；并完成三项 trace 配对——touch.txt 与 trace eventTime 同源吻合的机制根源、1194 个间隔的透传链路、touch.txt 无 SYN_DROPPED 的反证判据。
- **兑现批01 的两个遗留承诺**：批01 §8.2（01-virtio_input前端驱动.md:726-754）把"eventTime 由 evdev 层 `input_get_timestamp`→`ktime_get()` 补齐"标为已证但声明"evdev 侧细节归 03 篇展开"；批01 §9.1（:798）把"event0 序号 0 的分配规则"留给本篇。两处分别在 §6.3 与 §3.2 兑现。

**一条硬结论预告（本篇逐段证明）**：evdev 层对事件流只有三种加工——逐 client 复制写入（§4）、packet 边界判定（§5）、缓冲满时的覆盖丢弃（§6.1）。除缓冲满覆盖外，evdev 不产生、不合并、不延迟、不丢事件，也不改写时戳（§7.1）。因此 event0 节点 1194 个报点间隔的分布形状（均值 16.617 ms、P90 31.416 ms、P95 36.122 ms）在 getevent 采集客户端这一层的归属下界与批01 的驱动层下界衔接：**guest 内核从 virtio 中断到用户态 `read()` 的整段对该分布零塑造**（合并判定见 §7.2）。

主线先闭环：§4 走读完 `evdev_events`，"input core 下发 → 每个 client 缓冲 → 唤醒读者"这条写入侧主线闭合；§5 走读完 `evdev_read`，"client 缓冲 → `read()` 字节流"这条读取侧主线闭合。此后各节展开机制四问与配对。

## 1. 主线问题：同一时刻为什么有两个读数

本战役最新样本（`20261113-151358-vertical-1912967`）中，与本篇直接相关的 trace 与采集事实（数值引自战役计划第三节核心证据包，逐字一致）：

- event0 坐标变化＋SYN_REPORT：约 `00:00:06.787410`，与事件 ID `0x3b3b7f02` 的 eventTime 数值吻合（同源单点）；后续位置变化约 `6.800210`、`6.816798`（战役计划与台账.md:70）。
- event0 统计：1195 帧/1194 间隔；均值 16.617 ms、中位 16.347、P90 31.416、P95 36.122、P99 40.936、最大 93.711 ms（最大在保留 trace 起点前）；>25 ms 209 个（17.5%）、<10 ms 253 个（21.2%）（战役计划与台账.md:76）。
- 重点间隔 37.237 ms（`6.750173→6.787410`），之后 2.842 / 9.958 / 16.588 / 16.390 ms（战役计划与台账.md:76）。
- touch.txt 是 getevent 的输出（00 篇术语表 #33：本样本 touch.txt 的采集端，读取 /dev/input/event0 等全部可读节点）。本批实际打开样本目录核对：touch.txt 首个 event0 报文的 5 行全部携带同一时戳 `6069.677128`（设备秒口径），且全文 `SYN_DROPPED` 计数为 0（见 §7.3）。

主线问题由此展开：**trace 里事件 `0x3b3b7f02` 的 eventTime 数值，和 touch.txt 里同一报点的时戳，为什么是同一个数？** 答案在两层：机制层——两个读数方（getevent 与 EventHub）各持一个独立的 evdev client，evdev 在同一次下发中把同一个时戳值写进每个 client 的缓冲（§4、§7.1）；数据层——该时戳值经两条不同路径到达分析者手中后，在单点上数值吻合（契约 :70 的"同源单点"，批0 已复核）。本篇把机制层的每一步配上行号。

## 2. 三个结构与一个文件操作表

### 2.1 struct evdev：一个 input_dev 对应一个 evdev 对象

`drivers/input/evdev.c:29-39`：

```c
struct evdev {
	int open;
	struct input_handle handle;
	struct evdev_client __rcu *grab;
	struct list_head client_list;
	spinlock_t client_lock; /* protects client_list */
	... /* 略 ：35-37，mutex/dev/cdev 三字段 */
	bool exist;
};
```

走读，逐字段：

- `handle`：input core 的句柄，把本 evdev 对象与 input_dev、input_handler 关联（赋值点 `evdev.c:1373-1376`，见 §3.2）。input core 的事件下发以 handle 为单位（`input.c:95-121`）。
- `grab`：被独占的那个 client 指针（`__rcu` 标注 RCU 保护），机制在 §8。
- `client_list`：本设备全部打开者的 client 链表；`client_lock` 自旋锁保护链表增删（`evdev.c:376-391`）。**每个 `open("/dev/input/eventX")` 得到一个独立 client，事件下发时逐 client 复制一份**（`evdev.c:308-310`，§4）——这是 getevent 与 EventHub 并存互不干扰的结构基础。
- `open`：打开计数（`evdev.c:403` 的 `evdev->open++`），控制底层 `input_open_device` 的首次调用（§3.3）。
- `mutex`：ioctl/open/close 的互斥；`exist`：设备存在标志，disconnect 路径置 false——`evdev_disconnect`（`evdev.c:1406`）→ `evdev_cleanup`（`:1327` 调 `evdev_mark_dead`）→ `evdev_mark_dead`（`evdev.c:1316-1321`，赋值在 `:1319`）。

概念定义（全文只定义一次）：本文说 **evdev 对象** 指 `struct evdev`，说 **client** 指 `struct evdev_client`（一个打开 fd 对应一个），说 **节点** 指 `/dev/input/eventX` 字符设备。

### 2.2 struct evdev_client：环形缓冲与三个游标

`drivers/input/evdev.c:41-55`：

```c
struct evdev_client {
	unsigned int head;
	unsigned int tail;
	unsigned int packet_head; /* [future] position of the first element of next packet */
	spinlock_t buffer_lock; /* protects access to buffer, head and tail */
	wait_queue_head_t wait;
	... /* 略 ：47-52，fasync/evdev/node/clk_type/revoked/evmasks 六字段 */
	unsigned int bufsize;
	struct input_event buffer[];
};
```

走读，逐字段：

- `buffer[]`：柔性数组，`bufsize` 个 `struct input_event` 槽位，容量在 open 时算定（§3.3）。环形缓冲，索引按 `bufsize - 1` 取模。
- `head`：写游标（kernel 侧写入位置）；`tail`：读游标（用户侧消费位置）；`packet_head`：下一 packet 首事件的位置。三者全部在 `buffer_lock` 自旋锁内访问（锁注释 `evdev.c:45`）。packet 的定义在 §5.1 给出。
- `wait`：等待队列头。读进程阻塞在它上面（`evdev.c:602`），epoll 也通过 `poll_wait` 挂在它上面（`evdev.c:620`）。写入侧在 SYN_REPORT 时唤醒它（`evdev.c:287-289`）。
- `clk_type`：本 client 的时戳时钟选择，枚举 `enum input_clock_type`（`include/linux/input.h:39-44`，含 `INPUT_CLK_REAL=0`、`INPUT_CLK_MONO`、`INPUT_CLK_BOOT`）。默认值见 §3.4。
- `evmasks[EV_CNT]`：per-client 事件过滤位图（EVIOCSMASK 设置，`evdev.c:959-992`）；`revoked`：EVIOCREVOKE 后置位（`evdev.c:950`）。

### 2.3 struct input_event：read() 的传输单位

`include/uapi/linux/input.h:28-47`：

```c
struct input_event {
#if (__BITS_PER_LONG != 32 || !defined(__USE_TIME_BITS64)) && !defined(__KERNEL__)
	struct timeval time;
#define input_event_sec time.tv_sec
... /* 略 ：32-43，usec 宏、#else 内核分支的 __sec/__usec 与两个重定义宏及 #endif */
	__u16 type;
	__u16 code;
	__s32 value;
};
```

走读：内核编译走 `#else` 分支（`__KERNEL__` 已定义，`input.h:33`），时戳是两个 `__kernel_ulong_t`；用户态 64 位编译走 `struct timeval` 分支（`input.h:30`）。两种布局在 64 位 LP64 下同为 24 字节（8+8+2+2+4）。内核代码统一用宏 `input_event_sec`/`input_event_usec` 访问（`input.h:31-32/:41-42`），两个分支同源。type/code/value 与批01 §2.1 的 wire 结构字段同名同宽（`include/uapi/linux/virtio_input.h:70-74`）；差集是两个时戳字段——批01 已证 wire 上无时戳，本篇 §6.3 给补齐点的完整走读。

单次 `read()` 的最小单位是 `input_event_size()` 字节（`drivers/input/input-compat.h:54-58` 非 compat 分支返回 `sizeof(struct input_event)`）。`evdev_read` 对不足一个事件长度的请求直接拒绝（`evdev.c:571-572`）。

### 2.4 evdev_fops：用户态看到的全部入口

`drivers/input/evdev.c:1296-1309`：

```c
static const struct file_operations evdev_fops = {
	.owner		= THIS_MODULE,
	.read		= evdev_read,
	.write		= evdev_write,
	.poll		= evdev_poll,
	.open		= evdev_open,
	.release	= evdev_release,
	... /* 略 ：1303-1307，unlocked_ioctl/compat_ioctl/fasync 三入口 */
	.llseek		= no_llseek,
};
```

走读：本篇走读的主线函数——`evdev_open`（§3.3）、`evdev_read`（§5.2）、`evdev_poll`（§5.4）——全部由此表挂到字符设备上；该表在 `evdev_connect` 里经 `cdev_init(&evdev->cdev, &evdev_fops)`（`evdev.c:1388`）注册。`evdev_write`（`evdev.c:506-542`）是事件注入入口（转 `input_inject_event`，`evdev.c:534`），不在本链路数据流上；`evdev_release`（`evdev.c:439-463`）释放 client 并递减打开计数。

## 3. 节点诞生与 client 分配

### 3.1 handler 注册：evdev 与 input core 的对接

`drivers/input/evdev.c:1424-1433`：

```c
static struct input_handler evdev_handler = {
	.event		= evdev_event,
	.events		= evdev_events,
	.connect	= evdev_connect,
	.disconnect	= evdev_disconnect,
	.legacy_minors	= true,
	.minor		= EVDEV_MINOR_BASE,
	.name		= "evdev",
	.id_table	= evdev_ids,
};
```

走读：`evdev_init`（`evdev.c:1435-1438`）在模块加载时调 `input_register_handler(&evdev_handler)`（`drivers/input/input.c:2393-2413`：挂入 `input_handler_list`（:2404），并对 `input_dev_list` 中每个已注册设备执行 `input_attach_handler`，`input.c:2406-2407`）。`evdev_ids` 是 `{ .driver_info = 1 }` 通配（`evdev.c:1417-1420`）——**evdev 对每个注册成功的 input_dev 都建立节点**。批01 probe 尾部的 `input_register_device`（`virtio_input.c:319`）触发 `input_attach_handler` 循环（`input.c:2334-2335`），随即命中 evdev，进入 `evdev_connect`。

### 3.2 evdev_connect：minor 分配与 event%d 命名（兑现批01 遗留承诺一）

`drivers/input/evdev.c:1349-1354`（minor 分配）：

```c
	minor = input_get_new_minor(EVDEV_MINOR_BASE, EVDEV_MINORS, true);
	if (minor < 0) {
		error = minor;
		pr_err("failed to reserve new minor: %d\n", error);
		return error;
	}
```

`drivers/input/evdev.c:1362-1365`（对象初始化）：

```c
	INIT_LIST_HEAD(&evdev->client_list);
	spin_lock_init(&evdev->client_lock);
	mutex_init(&evdev->mutex);
	evdev->exist = true;
```

`drivers/input/evdev.c:1367-1371`（命名归一）：

```c
	dev_no = minor;
	/* Normalize device number if it falls into legacy range */
	if (dev_no < EVDEV_MINOR_BASE + EVDEV_MINORS)
		dev_no -= EVDEV_MINOR_BASE;
	dev_set_name(&evdev->dev, "event%d", dev_no);
```

走读：

- minor 从 `input_get_new_minor(64, 32, true)` 分配（`evdev.c:1349`；实现在 `drivers/input/input.c:2562-2582`，legacy 段走 `ida_simple_get(&input_ida, legacy_base, legacy_base + legacy_num, GFP_KERNEL)`，`input.c:2571-2574`，即在 64..95 内取号）。
- `EVDEV_MINOR_BASE=64`、`EVDEV_MINORS=32` 定义于 `evdev.c:10-11`；设备名 `event%d` 在 `evdev.c:1371` 由 `dev_no` 拼出，legacy 区间内 `dev_no = minor - 64`（`evdev.c:1369-1370`）。
- **event0 序号的分配规则（批01 §9.1 遗留，此处兑现）**：`dev_no` 等于本次 connect 从 ida 分到的 minor 减去基值 64；第一个完成 connect 的 input_dev 拿到 64，得 `event0`。已证（分配算法 `input.c:2562-2581`＋命名 `evdev.c:1367-1371`）。**"本设备上 event0 分给 virtio_input_tp0 而非其他设备"** 是 probe 顺序的结果：connect 的先后由各 input_dev 注册先后决定（`input.c:2334-2335` 的循环序），而 probe 顺序属于虚拟设备列举顺序，host 侧列举顺序不在基准树内——**该先后本身＝盲区**；但"节点名 virtio_input_tp0 与 event0 的对应关系"已由 touch.txt 设备清单直接给出（会话记录.md:110，`add device 7: /dev/input/event0 / name: "virtio_input_tp0"`，本批实际核对 touch.txt:13-14 一致），**结构性已证**。

随后的挂接段（`evdev.c:1373-1390`）：

```c
	evdev->handle.dev = input_get_device(dev);
	evdev->handle.name = dev_name(&evdev->dev);
	evdev->handle.handler = handler;
	evdev->handle.private = evdev;
	... /* 略 ：1378-1382，devt/class/parent/release/device_initialize */
	error = input_register_handle(&evdev->handle);
	if (error)
		goto err_free_evdev;

	cdev_init(&evdev->cdev, &evdev_fops);
```

走读：`handle.private = evdev`（`evdev.c:1376`）是第 4 节 `evdev_events` 里 `handle->private` 取回对象的来源。`input_register_handle`（`input.c:2484` 起）把 handle 挂进 dev 的 `h_list`；`cdev_device_add(&evdev->cdev, &evdev->dev)`（`evdev.c:1390`）创建 `/dev/input/eventX` 字符设备。至此节点存在，尚无数据流——第一个 client 打开前，`evdev->open == 0`，`handle->open` 未置位，input core 对该 handle 不下发（判据 `input.c:144` 的 `handle->open`，见 §4.1）。

### 3.3 evdev_open：client 分配与缓冲大小

`drivers/input/evdev.c:465-472`：

```c
static unsigned int evdev_compute_buffer_size(struct input_dev *dev)
{
	unsigned int n_events =
		max(dev->hint_events_per_packet * EVDEV_BUF_PACKETS,
		    EVDEV_MIN_BUFFER_SIZE);

	return roundup_pow_of_two(n_events);
}
```

走读：容量公式为 `roundup_pow_of_two(max(hint × 8, 64))`。两个常量在 `evdev.c:12-13`：`EVDEV_MIN_BUFFER_SIZE 64U`、`EVDEV_BUF_PACKETS 8`。容量单位是**事件个数**（不是字节）：`evdev.c:481` 的分配是 `kvzalloc(struct_size(client, buffer, bufsize), GFP_KERNEL)`。

`drivers/input/evdev.c:474-483`（分配段）：

```c
static int evdev_open(struct inode *inode, struct file *file)
{
	struct evdev *evdev = container_of(inode->i_cdev, struct evdev, cdev);
	unsigned int bufsize = evdev_compute_buffer_size(evdev->handle.dev);
	struct evdev_client *client;
	int error;

	client = kvzalloc(struct_size(client, buffer, bufsize), GFP_KERNEL);
	if (!client)
		return -ENOMEM;
```

`drivers/input/evdev.c:485-493`（装配段）：

```c
	init_waitqueue_head(&client->wait);
	client->bufsize = bufsize;
	spin_lock_init(&client->buffer_lock);
	client->evdev = evdev;
	evdev_attach_client(evdev, client);

	error = evdev_open_device(evdev);
	if (error)
		goto err_free_client;
```

`drivers/input/evdev.c:495-496`（client 归属 fd）：

```c
	file->private_data = client;
	stream_open(inode, file);
```

走读，按序：

1. `container_of(inode->i_cdev, ...)`（`evdev.c:476`）：从字符设备反取 evdev 对象。
2. client 分配用 **kvzalloc**（`evdev.c:481`）：全零初始化。这个"零"决定两个默认值——`clk_type = 0`（§3.4）与 `evmasks[]` 全 NULL（无过滤，§6.2 的引文 `evdev.c:88-89` 用到）。
3. `evdev_attach_client`（`evdev.c:489`，实现在 `:376-382`）：持 `client_lock` 执行 `list_add_tail_rcu(&client->node, &evdev->client_list)`。从这一刻起本 client 进入下发名单。
4. `evdev_open_device`（`evdev.c:491`）：首次打开时递增 `evdev->open` 并调 `input_open_device`（`evdev.c:393-411`，`if (!evdev->open++) { retval = input_open_device(&evdev->handle); ... }` 在 `:403-404`）。`input_open_device`（`input.c:604` 起）置 `handle->open++`（`input.c:618`），并在设备级首次打开时调 `dev->open` 回调（`input.c:627-628`；virtio 驱动未注册该回调，批02 §4.10 已 grep 证）——**事件下发的前提判据 `handle->open` 由此打开**。（handler 的 `start` 回调不在 open 路径：其调用点是 `input_register_handle` 的 `input.c:2517-2518` 与 grab 释放路径 `__input_release_device` 的 `input.c:573-574`；evdev_handler 未注册 `.start`，`evdev.c:1424-1433` 结构体可证。）
5. `file->private_data = client`（`evdev.c:495`）：此后该 fd 的 read/poll/ioctl 全部以 private_data 找回 client。

**hint_events_per_packet 的来源与本设备的实际容量**：`include/linux/input.h:156` 声明该字段；驱动可经 `input_set_events_per_packet` 设置（`include/linux/input.h:481-483`），virtio_input.c 全文无此调用（grep 计数 0，过程记录留有输出）；注册时 input core 用估算值兜底——`drivers/input/input.c:2291-2296`：

```c
	packet_size = input_estimate_events_per_packet(dev);
	if (dev->hint_events_per_packet < packet_size)
		dev->hint_events_per_packet = packet_size;

	dev->max_vals = dev->hint_events_per_packet + 2;
	dev->vals = kcalloc(dev->max_vals, sizeof(*dev->vals), GFP_KERNEL);
```

估算函数 `input_estimate_events_per_packet`（`input.c:2128` 起）：MT 设备取 `mt_slots`（`dev->mt->num_slots`，`input.c:2132-2133`），基础值 `events = mt_slots + 1`（`input.c:2143`），再加每个 ABS 轴（MT 轴按槽位数计）与 7 个 KEY/MSC 余量（`input.c:2145-2154`）。本设备的 `mt_slots` 来自 `input_mt_init_slots` 的实参，即 host 在配置空间声明的 `ABS_MT_SLOT` 最大值＋1（批01 §4.6，`virtio_input.c:304-308`）——**host 声明的轴集合与槽位数不可从 guest 验证，故 event0 client 的确切 bufsize 数值＝盲区**；可证的量是下限：任何 client 容量 ≥ 64 个事件（`evdev.c:12` 常量经 `max()` 兜底，`evdev.c:468-469`）。这个下限在 §6.4 的溢出算术里使用。

### 3.4 默认时钟：REAL，不是 MONO

client 全零初始化（§3.3 第 2 步）使 `client->clk_type` 取枚举值 0，而枚举定义是：

`include/linux/input.h:39-44`：

```c
enum input_clock_type {
	INPUT_CLK_REAL = 0,
	INPUT_CLK_MONO,
	INPUT_CLK_BOOT,
	INPUT_CLK_MAX
};
```

走读：**新建 client 的默认时钟是 `INPUT_CLK_REAL`（CLOCK_REALTIME），不是 MONOTONIC**。已证（kvzalloc 全零 `evdev.c:481`＋枚举 `input.h:40`）。时钟切换的唯一入口是 EVIOCSCLOCKID ioctl（`evdev.c:1126-1130` → `evdev_set_clk_type`，`evdev.c:175-213`）。本链路两个消费者都显式设置了时钟：getevent 设 CLOCK_MONOTONIC（`getevent.c:316/:357`），EventHub 也设 CLOCK_MONOTONIC（`EventHub.cpp:440/:446`）——两处在 §9 对照。数据侧一致性：touch.txt 的时戳是设备运行秒量级（如 6068.542723），不是 epoch 量级，排除"未设置、停在默认 REAL"的形态；设备上运行的 getevent 二进制与基准树源码的一致性属 00 篇 §3.6 的未证前提。

## 4. 写入侧主线：evdev_events

### 4.1 到达路径：input core 的两次判据

事件从 input core 到 evdev 的路径（本篇只列判据行号，攒批语义归批02）：`input_handle_event` 在 FLUSH 处置时调 `input_pass_values`（`input.c:402-405`）；`input_pass_values`（`input.c:128-164`）先查设备级 grab（`input.c:139-141`，`rcu_dereference(dev->grab)`，无 grab 时走 `h_list`），再对每个 `handle->open` 成立的 handle 调 `input_to_handler`（`input.c:143-145`）；`input_to_handler`（`input.c:95-121`）先过 handler filter（`input.c:100-109`），然后：

`drivers/input/input.c:114-118`：

```c
	if (handler->events)
		handler->events(handle, vals, count);
	else if (handler->event)
		for (v = vals; v != vals + count; v++)
			handler->event(handle, v->type, v->code, v->value);
```

走读：evdev_handler 两个字段都有赋值（`evdev.c:1425-1426`），`events` 优先，故批量下发走 `evdev_events`；`evdev_event`（`evdev.c:318-324`）只是把单事件包成 `vals[1]` 再转调 `evdev_events` 的兼容入口。整个调用链在 `dev->event_lock` 自旋锁关中断区内执行（`input.c:445` 的 `spin_lock_irqsave`），叠加批01 已证的中断上下文（01 篇 A6）——**`evdev_events` 及其下游运行于关中断的硬中断上下文**。已证（链条：`input.c:445-447` → `input_pass_values` → `evdev_events`）。

### 4.2 evdev_events 本体：一次取时，逐 client 写入

`drivers/input/evdev.c:295-300`（签名与取时）：

```c
static void evdev_events(struct input_handle *handle,
			 const struct input_value *vals, unsigned int count)
{
	struct evdev *evdev = handle->private;
	struct evdev_client *client;
	ktime_t *ev_time = input_get_timestamp(handle->dev);
```

`drivers/input/evdev.c:304-310`（RCU 读侧与 client 分派；`rcu_read_lock`/`rcu_read_unlock` 在 `:302`/`:312`）：

```c
	client = rcu_dereference(evdev->grab);

	if (client)
		evdev_pass_values(client, vals, count, ev_time);
	else
		list_for_each_entry_rcu(client, &evdev->client_list, node)
			evdev_pass_values(client, vals, count, ev_time);
```

走读，四个要点：

1. **`:300` 是 eventTime 的补齐点（兑现批01 遗留承诺二）**：`input_get_timestamp(handle->dev)` 在本次下发的最前端调用一次，返回 `dev->timestamp[]` 三时钟槽数组指针。完整走读在 §6.3；此处先记事实：**所有 client、本批所有事件共享这一次取时读数**。
2. `:302-304` RCU 读侧进入后先查 `evdev->grab`（独占指针，§8）：有 grab 则只写 grab 的 client。
3. `:308-310` 无 grab 时遍历 `client_list`，**每个 client 各得一份独立拷贝**——getevent 的 client 与 EventHub 的 client 同时被写入，互不取走对方的数据。这是"采集端与消费端并存"的机制基础（§7.1 使用）。
4. 下发粒度是 input core 的一次 flush（`vals[0..count)`），即一批 `struct input_value`，末元素是 SYN_REPORT（结构性论证见 §6.2）。

### 4.3 evdev_pass_values：换算时戳、过滤、写缓冲、唤醒

`drivers/input/evdev.c:248-258`（签名、局部量、revoked 判据）：

```c
static void evdev_pass_values(struct evdev_client *client,
			const struct input_value *vals, unsigned int count,
			ktime_t *ev_time)
{
	... /* 略 ：252-255，v/event/ts/wakeup 四个局部声明 */
	if (client->revoked)
		return;
```

`drivers/input/evdev.c:260-265`（时戳换算与加锁）：

```c
	ts = ktime_to_timespec64(ev_time[client->clk_type]);
	event.input_event_sec = ts.tv_sec;
	event.input_event_usec = ts.tv_nsec / NSEC_PER_USEC;

	/* Interrupts are disabled, just acquire the lock. */
	spin_lock(&client->buffer_lock);
```

`drivers/input/evdev.c:267-276`（过滤与空包判据；`:277` 为 if 收尾）：

```c
	for (v = vals; v != vals + count; v++) {
		if (__evdev_is_filtered(client, v->type, v->code))
			continue;

		if (v->type == EV_SYN && v->code == SYN_REPORT) {
			/* drop empty SYN_REPORT */
			if (client->packet_head == client->head)
				continue;

			wakeup = true;
```

`drivers/input/evdev.c:279-282`（写缓冲）与 `:285-289`（解锁与唤醒）：

```c
		event.type = v->type;
		event.code = v->code;
		event.value = v->value;
		__pass_event(client, &event);
```

```c
	spin_unlock(&client->buffer_lock);

	if (wakeup)
		wake_up_interruptible_poll(&client->wait,
			EPOLLIN | EPOLLOUT | EPOLLRDNORM | EPOLLWRNORM);
```

逐行走读：

- `:257-258` revoked 判据：EVIOCREVOKE 后的 client 静默跳过。
- `:260-262` **时戳换算点**：从三时钟槽数组里按下标取本 client 时钟槽，转成 timespec64 的秒/微秒，赋给栈上 `event` 的时戳字段。**同一批 vals 的所有事件共用这一组 sec/usec**——循环体只改 type/code/value（`:279-281`），时戳不再逐事件取。本样本 touch.txt 首个 event0 报文 5 行同一时戳 `6069.677128`（§1），是该机制的直接数据印证。已证。
- `:264-265` 注释原文 "Interrupts are disabled, just acquire the lock."：调用上下文已关中断（§4.1 链条），故普通 `spin_lock` 即可，与 `evdev_fetch_next_event` 的 `spin_lock_irq`（`evdev.c:549`，读侧可能睡眠于进程上下文）形成不对称。
- `:268-269` per-client 过滤：`__evdev_is_filtered`（`evdev.c:76-99`）。全零初始化的 `evmasks[]` 全为 NULL，`:88-89` 与 `:97-98` 都在 `mask &&` 处短路返回 false——**未设置 EVIOCSMASK 的 client 不过滤任何事件**。本链路两个消费者（getevent、EventHub）在源码树内均无 EVIOCSMASK 调用（grep 无匹配），故过滤路径在本链路未启用。已证。
- `:271-277` SYN_REPORT 特判：空 packet（`packet_head == head`）的 SYN_REPORT 直接丢弃，不写不唤醒；非空则置 `wakeup = true`。
- `:282` `__pass_event` 执行写缓冲（见 4.4）。
- `:287-289` **唤醒点**：仅当本批含非空 SYN_REPORT 才 `wake_up_interruptible_poll(&client->wait, EPOLLIN|...)`。阻塞在 `evdev_read` 的进程与挂在 epoll 上的 EventHub 都由此唤醒（读侧机制见 §5.3/§5.4）。**唤醒以 packet 为单位，坐标事件（EV_ABS 等）本身不唤醒**。已证。

### 4.4 __pass_event：写游标推进与溢出覆盖

`drivers/input/evdev.c:215-222`（签名、trace 钩子、写入）：

```c
static void __pass_event(struct evdev_client *client,
			 const struct input_event *event)
{
	trace_android_vh_pass_input_event(client->head, client->tail, client->bufsize,
		event->type, event->code, event->value);

	client->buffer[client->head++] = *event;
	client->head &= client->bufsize - 1;
```

`drivers/input/evdev.c:224-229`（溢出判定与 tail 回退）：

```c
	if (unlikely(client->head == client->tail)) {
		/*
		 * This effectively "drops" all unconsumed events, leaving
		 * EV_SYN/SYN_DROPPED plus the newest event in the queue.
		 */
		client->tail = (client->head - 2) & (client->bufsize - 1);
```

`drivers/input/evdev.c:231-240`（SYN_DROPPED 事件写入与游标归位）：

```c
		client->buffer[client->tail] = (struct input_event) {
			.input_event_sec = event->input_event_sec,
			.input_event_usec = event->input_event_usec,
			.type = EV_SYN,
			.code = SYN_DROPPED,
			.value = 0,
		};

		client->packet_head = client->tail;
	}
```

`drivers/input/evdev.c:242-246`（packet 收口）：

```c
	if (event->type == EV_SYN && event->code == SYN_REPORT) {
		client->packet_head = client->head;
		kill_fasync(&client->fasync, SIGIO, POLL_IN);
	}
}
```

逐行走读：

- `:218-219` 厂商 trace 钩子 `android_vh_pass_input_event`（声明于 `include/trace/hooks/evdev.h:12`）：每次写入上报 head/tail/bufsize 与事件三元组。本战役 trace 是否启用了该钩子事件＝未证（证据包内无对应事件名，批0 未列出；"无记录不等于不存在"）。
- `:221-222` 写入并推进 head：`client->buffer[client->head++] = *event; client->head &= client->bufsize - 1;`——**逐字段拷贝，无改写**。这条是 §7.1"evdev 不改写事件内容"的写入侧证据。
- `:224-240` **溢出分支（SYN_DROPPED 的第一产生点，§6.1）**：写满后 head 追上 tail，执行覆盖——tail 回退到 `head-2`（`:229`），该槽位改写成 `{EV_SYN, SYN_DROPPED, 0}`（`:231-237`，时戳沿用刚写的事件时戳），`packet_head` 同步到新 tail（`:239`）。注释原文说明语义："drops all unconsumed events, leaving EV_SYN/SYN_DROPPED plus the newest event"。**溢出必然在用户流里留下一条可读的 SYN_DROPPED 事件**：写入后可读区间为 `[tail, head)`，SYN_DROPPED 位于 tail 槽位，`packet_head = tail` 使它成为下一 packet 的首事件。已证（本段代码＋判据 `evdev.c:551`）。这条必然性是 §7.3 反证判据的逻辑前提。
- `:242-245` **packet_head 只在 SYN_REPORT 时推进**。同时 `kill_fasync` 给注册了 fasync 的读者发 SIGIO（本链路两个消费者未用 fasync，`evdev_fasync` 仅在 fcntl FASYNC 时挂入）。

## 5. 读取侧主线：evdev_read 与 evdev_poll

### 5.1 packet 的定义与游标关系

概念定义（全文只定义一次）：**packet** 指 client 环形缓冲内以一个 `EV_SYN/SYN_REPORT` 事件结尾的连续事件序列。`packet_head` 字段的注释即此意（`evdev.c:44`："position of the first element of next packet"）。可读判据是 `packet_head != tail`：tail 到 packet_head 之间是**完整的** packet；head 到 packet_head 之间是尚未收口的半包，**读不到**。

### 5.2 evdev_read 全走读

`drivers/input/evdev.c:544-553`：

```c
static int evdev_fetch_next_event(struct evdev_client *client,
				  struct input_event *event)
{
	int have_event;

	spin_lock_irq(&client->buffer_lock);

	have_event = client->packet_head != client->tail;
	if (have_event) {
		*event = client->buffer[client->tail++];
```

走读：单事件出队原语。判据 `:551` 用 `packet_head != tail`——**不是 `head != tail`**：半包（SYN_REPORT 未到）时即便缓冲有数据也返回"无事件"。出队后的 tail 取模收尾在 `:554`，解锁在 `:557`，返回 `have_event` 在 `:559`。读取侧的每一步出队都在 `spin_lock_irq` 内（`:549`），与写侧（关中断上下文＋普通 `spin_lock`，`evdev.c:265`）互斥于同一把 `buffer_lock`。

`drivers/input/evdev.c:562-572`（签名、局部量、入参校验）：

```c
static ssize_t evdev_read(struct file *file, char __user *buffer,
			  size_t count, loff_t *ppos)
{
	struct evdev_client *client = file->private_data;
	... /* 略 ：566-569，evdev/event/read/error 四个局部量 */
	if (count != 0 && count < input_event_size())
		return -EINVAL;
```

`drivers/input/evdev.c:574-580`（主循环、ENODEV、O_NONBLOCK）：

```c
	for (;;) {
		if (!evdev->exist || client->revoked)
			return -ENODEV;

		if (client->packet_head == client->tail &&
		    (file->f_flags & O_NONBLOCK))
			return -EAGAIN;
```

`drivers/input/evdev.c:589-598`（容量受限的拷贝循环）：

```c
		while (read + input_event_size() <= count &&
		       evdev_fetch_next_event(client, &event)) {

			if (input_event_to_user(buffer + read, &event))
				return -EFAULT;

			read += input_event_size();
		}

		if (read)
```

`drivers/input/evdev.c:601-607`（阻塞等待）：

```c
		if (!(file->f_flags & O_NONBLOCK)) {
			error = wait_event_interruptible(client->wait,
					client->packet_head != client->tail ||
					!evdev->exist || client->revoked);
			if (error)
				return error;
		}
```

逐段走读，对照四个机制问题：

1. **入参校验**（`:571-572`）：count 非 0 时必须 ≥ 一个事件长度（24 字节，§2.3），否则 `-EINVAL`。
2. **非阻塞语义**（`:578-580`）：`packet_head == tail`（无完整 packet）且 fd 带 O_NONBLOCK 时返回 `-EAGAIN`。EventHub 打开节点用 `O_RDWR | O_CLOEXEC | O_NONBLOCK`（`EventHub.cpp:1947`），每次 `getEvents` 先经 epoll 判可读再 read，read 返回 EAGAIN 属正常路径（`EventHub.cpp:1687-1690` 只对非 EAGAIN/EINTR 的错误告警）。
3. **count==0 探测**（`:582-587`）：不做 IO，只检查错误条件（ENODEV/revoked）。
4. **拷贝循环**（`:589-596`）：在 `count` 容量内逐事件 `evdev_fetch_next_event` ＋ `input_event_to_user`（复制到用户缓冲），直到填满 count 或缓冲内完整 packet 取尽。**部分读语义**：一次 `read()` 返回的字节数是事件对齐的（`read` 只按 `input_event_size()` 步进），但不保证包含整数个 packet——一个 packet 可以被拆到两次 `read()` 里（例如 EventHub 的 capacity 用尽时 `:1706/:1708-1712` 记录容量归零并回退 epoll 索引，下次调用继续读同一 packet 的剩余事件）。packet 边界在数据流里（SYN_REPORT 事件本身），不在 `read()` 调用边界上。
5. **有读即返回**（`:598-599`）：`if (read) break;`——读完现有完整 packet 就返回，不等下一个 packet。
6. **阻塞等待**（`:601-607`）：无完整 packet 且无 O_NONBLOCK 时，`wait_event_interruptible(client->wait, packet_head != tail || !exist || revoked)` 睡眠，唤醒源就是 §4.3 的 `wake_up_interruptible_poll`（`:287-289`）。**getevent 走的正是这条阻塞路径**：它以无 O_NONBLOCK 的 `O_RDONLY | O_CLOEXEC` 打开（`getevent.c:324`），`poll(ufds, nfds, -1)`（`getevent.c:658`）报 POLLIN 后 `read(ufds[i].fd, &event, sizeof(event))`（`getevent.c:666-667`）一次取一个事件——每次 `read()` 只消耗容量循环的一次迭代，read 以事件为最小单位，packet 的拆分只发生在 count 容量耗尽时（第 4 步），逐事件读取不存在 packet 被拆开的问题。
7. **返回值**（`:610`）：`read` 字节数。

### 5.3 evdev_read 的 packet 化与 input core 侧的对应

写入侧与读取侧的 packet 判据相互咬合：写侧 `packet_head` 只被 SYN_REPORT 推进（`evdev.c:242-243`）；读侧只放行 `packet_head != tail` 区间（`evdev.c:551`）。而到达 evdev 的每一批 vals 自身以 SYN_REPORT 收尾（结构性论证在 §6.2），所以**正常路径上每批 flush 形成一个完整 packet，`read()` 与 `poll` 的可读判定随之以 packet 为粒度**。已证（三处判据行号齐：`evdev.c:242-243/:551/:627-628`）。

### 5.4 evdev_poll

`drivers/input/evdev.c:620-628`（签名 `:614-619`，返回 `:630`）：

```c
	poll_wait(file, &client->wait, wait);

	if (evdev->exist && !client->revoked)
		mask = EPOLLOUT | EPOLLWRNORM;
	else
		mask = EPOLLHUP | EPOLLERR;

	if (client->packet_head != client->tail)
		mask |= EPOLLIN | EPOLLRDNORM;
```

走读：

- `:620` `poll_wait` 把等待者挂上 `client->wait`——与 §4.3 唤醒点、§5.2 阻塞点同一条等待队列。EventHub 的 epoll 实例（`mEpollFd`）通过 epoll 的 poll 表项挂在此队列上；`wake_up_interruptible_poll(&client->wait, EPOLLIN|...)`（`evdev.c:287-289`）同时唤醒阻塞 read 与 epoll 等待者。
- `:627-628` 可读判据与 `evdev_fetch_next_event` 相同（`packet_head != tail`）——**epoll 报 EPOLLIN 与 read 拿到数据的条件一致**，不存在"poll 报可读而 read 无数据"的窗口（同锁保护）。
- `:622-625` exist/revoked 决定 HUP/ERR；未撤销时恒报 EPOLLOUT（写入口 evdev_write 无阻塞点）。

## 6. 机制四问

以下四问各自按"规则是什么／谁设置／谁执行／执行的后果"展开（契约硬性标准 3）。

### 6.1 问一：SYN_DROPPED 何时产生、用户态如何恢复

**规则是什么**：`SYN_DROPPED` 是 EV_SYN 的 code 3（`include/uapi/linux/input-event-codes.h:60`，与 `:57` 的 SYN_REPORT 同表）。evdev 把它作为一条**合成的 `input_event`** 写进 client 缓冲，语义是"本缓冲内此前的未读事件已被丢弃，用户态必须重新同步设备状态"。`type=EV_SYN, code=SYN_DROPPED, value=0`（`evdev.c:152-154`）。

**谁设置（产生点穷尽）**：grep 全文件，写入 `SYN_DROPPED` 的代码只有两处函数，三条路径：

1. **缓冲写满覆盖（主产生点）**：`__pass_event` 的溢出分支，`evdev.c:224-240`（§4.4 已逐行走读）。触发条件＝写入后 `head == tail`，即环形缓冲全满且该事件恰好落在最后一个空槽。
2. **状态读取 ioctl 失败补偿**：`__evdev_queue_syn_dropped`（`evdev.c:144-164`）→ `evdev_queue_syn_dropped`（`evdev.c:166-173`，持 `buffer_lock`）→ 唯一调用点在 `evdev_handle_get_val`（`evdev.c:890-921`）：

`drivers/input/evdev.c:903-912`（锁序与清队）：

```c
	spin_lock_irq(&dev->event_lock);
	spin_lock(&client->buffer_lock);

	bitmap_copy(mem, bits, maxbit);

	spin_unlock(&dev->event_lock);

	__evdev_flush_queue(client, type);

	spin_unlock_irq(&client->buffer_lock);
```

`drivers/input/evdev.c:914-916`（失败补偿）：

```c
	ret = bits_to_user(mem, maxbit, maxlen, p, compat);
	if (ret < 0)
		evdev_queue_syn_dropped(client);
```

走读：EVIOCGKEY/EVIOCGLED/EVIOCGSND/EVIOCGSW 四个 ioctl 走此函数（`evdev.c:1158-1172`）：先在锁内拷贝当前状态位图并 `__evdev_flush_queue` 清掉缓冲内该 type 的存量事件（防重复，注释 `evdev.c:877-889`），再把位图复制给用户态；**复制失败（EFAULT）时队列一条 SYN_DROPPED**，让用户态知道"状态读取与事件流之间出现了缺口"。`__evdev_flush_queue`（`evdev.c:102-142`）本身不产生 SYN_DROPPED，它只重排缓冲并以 SYN_REPORT 为 packet 收口（`:119` 判据 `is_report = ev->type == EV_SYN && ev->code == SYN_REPORT;`）。
3. **时钟切换清队**：`evdev_set_clk_type`（`evdev.c:195-210`）：切换 `clk_type` 且队列非空时，把三个游标重置到 tail 并 `__evdev_queue_syn_dropped`（`:204-207`）——旧时钟的存量事件被清空，SYN_DROPPED 作为断点标记。

**谁执行**：三处全部在 evdev 内核代码内，无用户态开关。对本链路逐路径核对：路径 2 由 EVIOCGKEY/EVIOCGLED/EVIOCGSND/EVIOCGSW 触发——getevent 启动期的 `print_possible_events` 会调 EVIOCGKEY（`getevent.c:120`）与 EVIOCGSW（`getevent.c:147`），但其成功路径不产生 SYN_DROPPED，只有 `bits_to_user` 失败（EFAULT）才产生；路径 3 只在调用 EVIOCSCLOCKID 且时钟实际变化且队列非空时产生——getevent 与 EventHub 都在 open 后立即设置 MONO（§9），此时 client 刚建立、队列为空（`:204` 判据 `client->head != client->tail` 不成立），不产生。

**执行的后果（用户态恢复）**：SYN_DROPPED 到达用户态后，各消费者的处理：

- **EventHub/InputReader 侧（已证）**：RawEvent 流里的 SYN_DROPPED 由 `InputDevice::process` 处置——`frameworks/native/services/inputflinger/reader/InputDevice.cpp:394-415`：

`frameworks/native/services/inputflinger/reader/InputDevice.cpp:394-403`（丢弃到下一个同步）：

```c
        if (mDropUntilNextSync) {
            if (rawEvent->type == EV_SYN && rawEvent->code == SYN_REPORT) {
                mDropUntilNextSync = false;
                if (DEBUG_RAW_EVENTS) {
                    ALOGD("Recovered from input event buffer overrun.");
                }
            } else {
                if (DEBUG_RAW_EVENTS) {
                    ALOGD("Dropped input event while waiting for next input sync.");
                }
```

`frameworks/native/services/inputflinger/reader/InputDevice.cpp:405-413`（SYN_DROPPED 检出与正常分发）：

```c
        } else if (rawEvent->type == EV_SYN && rawEvent->code == SYN_DROPPED) {
            ALOGI("Detected input event buffer overrun for device %s.", getName().c_str());
            mDropUntilNextSync = true;
            reset(rawEvent->when);
        } else {
            for_each_mapper_in_subdevice(rawEvent->deviceId, [rawEvent](InputMapper& mapper) {
                mapper.process(rawEvent);
            });
        }
```

恢复协议三步：见到 SYN_DROPPED → 置 `mDropUntilNextSync` 并整体 `reset(rawEvent->when)`（`InputDevice.cpp:405-408`）→ 后续事件全部丢弃直到下一个 `EV_SYN/SYN_REPORT`（`:394-404`）→ 从该 SYN_REPORT 起恢复正常分发。**恢复的边界标记恰是 SYN_REPORT**——与 packet 定义（§5.1）同源。InputReader 是否进入过此分支在本样本 trace 中不可见（ALOGI 走 logd，trace 无 logcat 事件，批0 已声明）→ **本样本 EventHub 侧是否发生过 SYN_DROPPED＝未证**。
- **getevent 侧（已证：无恢复逻辑）**：`getevent.c` 的主循环（`:656-689`）对读到的每个事件只做打印（`:676` `print_event`），无任何按 type/code 的状态机；若缓冲溢出，SYN_DROPPED 会作为一行 `EV_SYN SYN_DROPPED` 出现在 touch.txt 里。标签来源：`generate-input.h-labels.py` 从内核 uapi 头收集全部 `SYN_` 开头的 `#define` 生成标签表（`generate-input.h-labels.py:39` 的正则与 `:51` 的 `elif name.startswith("SYN_")` 分支），`SYN_DROPPED`（`input-event-codes.h:60`）在列——即标签模式下它会被打印为可读文本；即便标签缺失，`print_event` 的兜底分支也会打印十六进制 code（`getevent.c:265-268` 的 `printf(" %04x ...", code)`）。**因此"touch.txt 里既无 SYN_DROPPED 文本、也无非 SYN_REPORT 的 EV_SYN 行"构成溢出未发生的完整判据**（§7.3 执行）。

**对本样本的结论**：touch.txt 全文无 SYN_DROPPED（双判据，§7.3）→ getevent client 在采集全程未发生缓冲溢出，也未发生会插入 SYN_DROPPED 的 ioctl 路径 → 采集端事件流完整。已证。EventHub client 侧未证（同上）。

### 6.2 问二：packet 边界为什么是 SYN_REPORT

**规则是什么**：见 §5.1 定义。边界判据的代码落点是三个不等式/推进点：写侧 `evdev.c:242-243`（仅 SYN_REPORT 推进 `packet_head`）、读侧 `evdev.c:551`（`packet_head != tail` 为可读）、poll 侧 `evdev.c:627-628`（同判据报 EPOLLIN）。

**为什么边界事件是 SYN_REPORT 而不是别的**——结构性论证（三层，全部已证）：

1. **input core 只在 SYN_REPORT 时批量下发**：SYN_REPORT 的处置位是 `INPUT_PASS_TO_HANDLERS | INPUT_FLUSH`（`drivers/input/input.c:275-277`）：

```c
		case SYN_REPORT:
			disposition = INPUT_PASS_TO_HANDLERS | INPUT_FLUSH;
			break;
```

FLUSH 使 `input_handle_event` 调 `input_pass_values`（`input.c:402-405`，`if (dev->num_vals >= 2) input_pass_values(dev, dev->vals, dev->num_vals); dev->num_vals = 0;`）。**到达 `evdev_events` 的每一批 vals 都由一次 FLUSH 触发，触发者就是本批末尾的 SYN_REPORT**。
2. **旁路 flush 也以 SYN_REPORT 收尾**：`dev->vals` 容量兜底路径在强制下发前追加 `input_value_sync`——`input.c:48` 定义 `static const struct input_value input_value_sync = { EV_SYN, SYN_REPORT, 1 };`，`input.c:413-415` 在 `dev->num_vals >= dev->max_vals - 2` 时 `dev->vals[dev->num_vals++] = input_value_sync; input_pass_values(...)`；autorepeat 路径同型（`input.c:188-194`）。即**没有任何一条 `input_pass_values` 到达 evdev 的批次缺末尾 SYN_REPORT**。
3. **client 侧过滤剥不掉它**：`__evdev_is_filtered` 对 EV_SYN 恒返回 false——`evdev.c:83-85`：

```c
	/* EV_SYN and unknown codes are never filtered */
	if (type == EV_SYN || type >= EV_CNT)
		return false;
```

加上 `:271-274` 只丢弃**空** packet 的 SYN_REPORT，非空批次的 SYN_REPORT 必然写入缓冲并推进 `packet_head`（`evdev.c:242-243`）。

三层合并：**packet 边界取 SYN_REPORT 的判据直接复用 input core 的下发粒度（FLUSH 语义）**——input core 以 SYN_REPORT 为批量下发的触发点，evdev 以同一事件为 packet 收口点并以此推进可读游标；`packet_head` 的推进条件（`evdev.c:242-243`）与 FLUSH 的触发条件（`input.c:275-277`）判的是同一个事件。已证（`input.c:275-277/:402-405/:48/:413-415`＋`evdev.c:83-85/:242-243`）。

**执行的后果**：读侧（EventHub、getevent）永远以完整 packet 为最小可读单位；半包滞留缓冲（§5.2 第 4 步的部分读语义允许一个 packet 分两次 read 取完）；空 packet 不产生唤醒（§4.3 `:271-274`），不会让读者空转。

### 6.3 问三：eventTime 的时钟来源与 trace 口径

**规则是什么**：evdev 写进 client 缓冲的时戳不是 evdev 自己取的，而是 input core 设备对象上的三时钟槽。三时钟槽定义与换算——`drivers/input/input.c:2040-2046`：

```c
void input_set_timestamp(struct input_dev *dev, ktime_t timestamp)
{
	dev->timestamp[INPUT_CLK_MONO] = timestamp;
	dev->timestamp[INPUT_CLK_REAL] = ktime_mono_to_real(timestamp);
	dev->timestamp[INPUT_CLK_BOOT] = ktime_mono_to_any(timestamp,
						   TK_OFFS_BOOT);
}
```

概念定义（全文只定义一次）：**三时钟槽**指 `dev->timestamp[INPUT_CLK_MONO/REAL/BOOT]` 三个 ktime_t 槽位，同一次取时读数按三种时钟口径各存一份。

**谁设置**：`input_get_timestamp`（`drivers/input/input.c:2055-2063`）——本篇主线的取时函数：

```c
ktime_t *input_get_timestamp(struct input_dev *dev)
{
	const ktime_t invalid_timestamp = ktime_set(0, 0);

	if (!ktime_compare(dev->timestamp[INPUT_CLK_MONO], invalid_timestamp))
		input_set_timestamp(dev, ktime_get());

	return dev->timestamp;
}
```

走读：MONO 槽为 0（无效）时以 `ktime_get()` 现场取时并经 `input_set_timestamp` 灌满三槽；随后返回数组指针。`evdev_events` 在 `:300` 调它，各 client 按 `clk_type` 取各自的槽（`evdev.c:260`）。MONO 槽的清零点在每次 FLUSH 后（`input.c:412`：`dev->timestamp[INPUT_CLK_MONO] = ktime_set(0, 0);`）——**因此每个 packet 的时戳都是"本次 flush 到达 evdev 层时刻"的一次新鲜 `ktime_get()` 读数**。`input_set_timestamp` 的其余调用点穷尽为 autorepeat 定时器（`input.c:193`，与本触摸流无关，批01 §4.5.3 已排除 EV_KEY 重复）——**常规触摸事件路径上时戳的唯一取时点就是 `input_get_timestamp` 内的这一次 `ktime_get()`**。已证（grep 调用点穷尽，过程记录留有输出；与批01 §8.2 的链条 1-4 衔接，本篇补齐第 5 步 evdev 侧细节）。

**`ktime_get()` 是什么时钟——CLOCK_MONOTONIC，不是 BOOTTIME**（本问的裁决点）：

- `kernel/time/timekeeping.c` 的 `ktime_get` 全函数（`:818-835`；`EXPORT_SYMBOL_GPL` 在 `:836`），分两段摘抄。前段（`:818-827`）：

```c
ktime_t ktime_get(void)
{
	struct timekeeper *tk = &tk_core.timekeeper;
	unsigned int seq;
	ktime_t base;
	u64 nsecs;

	WARN_ON(timekeeping_suspended);

	do {
```

`kernel/time/timekeeping.c:828-835`：

```c
		seq = read_seqcount_begin(&tk_core.seq);
		base = tk->tkr_mono.base;
		nsecs = timekeeping_get_ns(&tk->tkr_mono);

	} while (read_seqcount_retry(&tk_core.seq, seq));

	return ktime_add_ns(base, nsecs);
}
```

读数基是 `tk->tkr_mono.base`（`:829`）——timekeeping 的 monotonic 读基（`tkr_mono` 即 `struct tk_read_base` 的 monotonic 实例，`timekeeping.c:118-119` 的维护代码同基）。
- POSIX 时钟映射侧：`kernel/time/posix-timers.c:1318-1321` 的 `clock_monotonic` 定义 `.clock_get_ktime = posix_get_monotonic_ktime`，而 `posix_get_monotonic_ktime`（`posix-timers.c:204-207`）函数体就是 `return ktime_get();`——**CLOCK_MONOTONIC 与 `ktime_get()` 同一读基，已证**。
- BOOTTIME 槽由 MONO 槽加 suspend 偏移换算而来：`input.c:2044-2045` 的 `ktime_mono_to_any(timestamp, TK_OFFS_BOOT)`。两钟之差＝累计 suspend 时长。

**谁执行（本链路的逐点时序）**：virtio 中断 → `virtinput_recv_events` 透传（批01 §5.2）→ `input_event()`（`input.c:438-447`）→ FLUSH → `input_pass_values` → `evdev_events :300` 取时 → 逐 client 写缓冲（`evdev.c:260-262` 换算、`:221` 写入）。**时戳语义＝事件 packet 到达 guest 内核 evdev 层的时刻**，不是 host 产生事件时刻（wire 无时戳字段，批01 §2.1/§8.2），也不是用户态读取时刻。

**后果一：Android 侧的 eventTime 数值来源**。EventHub 把 `read()` 到的 `input_event.time` 原样换算为纳秒——`frameworks/native/services/inputflinger/reader/EventHub.cpp:177-193`（函数头与首段注释 `:177-182` 说明"用事件自带时戳而非当前时刻"）：

`frameworks/native/services/inputflinger/reader/EventHub.cpp:183-192`：

```c
    // The event's timestamp fortuitously uses the same monotonic clock
    // time base as the rest of Android. The kernel event device driver
    // (drivers/input/evdev.c) obtains timestamps using ktime_get_ts().
    // The systemTime(SYSTEM_TIME_MONOTONIC) function we use everywhere
    // calls clock_gettime(CLOCK_MONOTONIC) which is implemented as a
    // system call that also queries ktime_get_ts().

    const nsecs_t inputEventTime = seconds_to_nanoseconds(event.time.tv_sec) +
            microseconds_to_nanoseconds(event.time.tv_usec);
    return inputEventTime;
```

注释自证设计意图："The event's timestamp fortuitously uses the same monotonic clock time base as the rest of Android"——Android 全系统的输入时戳基就是 CLOCK_MONOTONIC（注释里提的 `ktime_get_ts()` 是旧内核入口名，5.15.41 基准树的实际入口是 `ktime_get()`，本篇已按实际树裁决）。该返回值写进 `RawEvent.when`（`EventHub.cpp:1699`，结构定义 `reader/include/EventHub.h:54-59`）。MotionEvent.eventTime 的赋值链归批05；本篇定界到 `RawEvent.when`。**EventHub 的 client 时钟由 `configureFd` 显式设为 MONOTONIC**（函数 `EventHub.cpp:426-448`，注释 `:436-439` 声明"input system assumes they were recorded using the monotonic clock"）：

`frameworks/native/services/inputflinger/reader/EventHub.cpp:440-446`：

```c
    int clockId = CLOCK_MONOTONIC;
    if (classes.test(InputDeviceClass::SENSOR)) {
        // Each new sensor event should use the same time base as
        // SystemClock.elapsedRealtimeNanos().
        clockId = CLOCK_BOOTTIME;
    }
    bool usingClockIoctl = !ioctl(fd, EVIOCSCLOCKID, &clockId);
```

event0 分类为 TOUCH（`absBitmask` 含 `ABS_MT_POSITION_X/Y`，判定 `EventHub.cpp:2090-2096`），非 SENSOR → `clockId = CLOCK_MONOTONIC`。已证。

**后果二：getevent 侧的时戳口径**。getevent 对每个打开的节点执行 `int clkid = CLOCK_MONOTONIC;`（`getevent.c:316`）与 `ioctl(fd, EVIOCSCLOCKID, &clkid)`（`getevent.c:357-360`，失败仅告警"Can't enable monotonic clock reporting"，非致命）——**touch.txt 的时戳是 client 的 MONO 槽值**。打印点：`getevent.c:671-672`：

```c
                    if(get_time) {
                        printf("[%8ld.%06ld] ", event.time.tv_sec, event.time.tv_usec);
                    }
```

（`-t` 开关启用；本样本 touch.txt 每行带 `[  6069.677128]` 前缀，说明采集命令带 `-t`。）注意：**同一 packet 的所有事件共用同一时戳**（§4.3 机制）在 getevent 输出里直接可见——touch.txt:1025-1029 的 5 行（TRACKING_ID/X/Y/BTN_TOUCH/SYN_REPORT）前缀同为 `6069.677128`。已证（数据＋机制双证）。

**后果三：与 trace BOOTTIME 口径的关系（本样本配对成立的条件）**。Perfetto trace 主时钟是 BOOTTIME 域（批0 记录 trace_bounds.start_ts=6073694444196 ns，即设备秒 6073.694444，见 00 篇 §3.1）；event0_帧间隔.csv 的相对列名直接写明假设——`relative_trace_assuming_clock_s`，其值＝`event_time_s − 6073.694444`（本批用 CSV 首行核对：6069.677128 − 6073.694444 = −4.017316，与列值逐字一致）。该减法隐含"getevent 的 MONO 秒可直接减 trace 的 BOOTTIME 起点"。两钟之差＝累计 suspend 偏移（`input.c:2044-2045` 换算所用的 TK_OFFS_BOOT 口径）。**契约 :70 的单点吻合（约 00:00:06.787410 与事件 ID 0x3b3b7f02 的 eventTime 数值吻合）同时约束了这一差值在吻合精度内不可分辨**——若差值非零，CSV 相对列与 trace 相对时间的系统性偏移会使单点吻合不成立。该约束是推论，已证（推论依据：契约 :70 的数据判定＋CSV 列口径本批核对）；约束强度取决于批0 单点判定的精度口径。**全程逐点校验未做＝未证**（00 篇 §3.1 已声明"全程跨域校验未做"，本篇不越界升级）。设备侧 getevent 二进制与基准树 getevent.c 的一致性＝未证（00 篇 §3.6）；数据侧可证的只有量级：touch.txt 时戳为设备运行秒量级，排除默认 REALTIME（epoch 量级），即设备上的 getevent client 时钟确被设为 MONO 或 BOOT 之一；源码口径指向 MONO（`getevent.c:316`）。

### 6.4 问四：client 缓冲满丢帧的触发条件与本样本评估

**规则是什么**：触发条件唯一——`__pass_event` 写入后 `head == tail`（`evdev.c:224`）。即环形缓冲的全部 `bufsize` 个槽位都被未读事件占满，且新事件恰好要写进最后一个槽。后果见 §4.4：除最新事件外全部未读事件被丢弃，用户流中留下 SYN_DROPPED＋最新事件。

**什么场景会满**：写侧速率（input core 每次 FLUSH 一批）不变的前提下，满的条件是读者长时间不出队。出队速率下降的候选：读者进程被调度饿死、读者线程阻塞于非读路径、读者 CPU 竞争。量化：本样本 event0 每个 packet 的事件数可从 touch.txt 逐包数出——DOWN 包 5 个事件（TRACKING_ID/X/Y/BTN_TOUCH/SYN_REPORT，touch.txt:1025-1029），MOVE 包 2-3 个事件（X＋Y＋SYN_REPORT 三事件如 touch.txt:12419-12421，X＋SYN_REPORT 两事件如 :12498/:12501），同批其余设备行交错其间（如 :12499-12500 的 event4/event5 行）。bufsize 下限 64（§3.3）→ 按最小 packet 2 事件计，溢出需要读者连续错过 ≥ 64/2 = 32 个 packet；按样本间隔中位 16.347 ms 计，对应读者 ≥ 约 520 ms 不碰该 fd。样本内可查的读者停顿量级：InputReader 的 D 态 3.716 ms（契约 :74）、R 态 7.951 ms（契约 :71）——均远低于该下界；>25 ms 的间隔 209 个（契约 :76）是个别间隔而非连续错过量。

**谁设置/谁执行**：bufsize 由 open 时的公式定死（`evdev.c:465-472`），运行期不可调；执行者就是写路径的溢出分支本身。

**本样本评估（分侧三态）**：

- **getevent client：未溢出，已证**。判据见 §7.3（文本判据＋标签无关判据双验）。同时该判据也排除路径 2/3 的 ioctl 补偿型 SYN_DROPPED（若发生同样会出现在 touch.txt）。
- **EventHub client：未证**。其缓冲状态无日志、无 trace 事件可查；若溢出，InputReader 会打 "Detected input event buffer overrun"（`InputDevice.cpp:406`）且 trace 无 logcat。按上述算术（32 packet × 中位间隔 16.347 ms），溢出需要 ≥ 约 520 ms 连续不读，而样本窗口内 InputReader 的可见停顿量级为 ms 级——**溢出可能性低，但"未发生"不可证**，按三态纪律记未证。
- **采集端自身压力的定性说明**：getevent 逐事件 `read()`＋逐行 `printf`（stdout 无缓冲，`getevent.c:534` 的 `setbuf(stdout, NULL)`），其消费速度受 stdout 写出影响；touch.txt 2,034,287 字节全程未溢出即其消费速度足以跟上写入（已证数据），"速度裕量多大"未测＝未证。

**缓冲满丢帧与 1194 间隔的关系**：契约 :103 要求"1194 间隔分布在 evdev 缓冲不丢失的前提下透传至用户态的链路"。getevent client 不丢失已证；**该 1194 间隔统计本身取自 touch.txt（getevent client 的输出），因此"分布是否在 evdev 不丢失前提下透传"对统计值自身是重言成立的**——需要论证的是"该分布与 evdev 写入侧的分布一致"，即 §7.2 的透传链路。

## 7. trace 配对

### 7.1 touch.txt 与 trace eventTime 同源吻合的机制根源（契约 :103 配对点一）

**结论（已证）**：touch.txt 的时戳与 trace 内 eventTime 数值同源，机制根源是——**同一次 `input_get_timestamp` 读数，经各自 client 的 MONO 槽换算后，分别写入 getevent client 与 EventHub client 的缓冲，evdev 全程不改写**。

逐点证据链：

1. **一次取时，多 client 共享**：`evdev_events :300` 在进入 client 循环之前调用 `input_get_timestamp`，返回的是设备对象上的三时钟槽数组指针（§6.3）；`:308-310` 对每个 client 调 `evdev_pass_values` 时传入同一个 `ev_time`。两个 client 的 `clk_type` 都是 `INPUT_CLK_MONO`（§9 的两处 EVIOCSCLOCKID 证据）→ 取的是同一个槽 `ev_time[INPUT_CLK_MONO]` → **两 client 写入的 sec/usec 逐字节相同**。已证。
2. **evdev 不改写事件内容**：写侧 `evdev.c:279-281`（type/code/value 逐一来自 input core 的 `input_value`）＋ `:221`（整体拷贝 `client->buffer[client->head++] = *event;`）。时戳只在 `:260-262` 赋一次，循环内不变。已证。
3. **两条到达路径**：getevent 侧 `read()` 单事件后按 `event.time` 打印（`getevent.c:666/:671-672`）；EventHub 侧批量 `read()` 后逐事件 `event->when = processEventTimestamp(iev)`（`EventHub.cpp:1699`，换算 `:190-191`）。两者读的是**各自 client 缓冲里的独立拷贝**（§3.1 的 client_list 机制），拷贝时戳同源（第 1 点）。
4. **数据印证**：trace 内 event0 坐标变化＋SYN_REPORT 约 `00:00:06.787410` 与事件 ID `0x3b3b7f02` 的 eventTime 数值吻合（契约 :70，批0 复核）；CSV 对应行 `6.787410,6080.481854,37.237,727,245`（本批实际读取 event0_帧间隔.csv 核对，行内容与契约 :76 的 37.237 ms 重点间隔一致）。
5. **与批01 的衔接**：批01 已证 wire 无时戳、驱动不改写（01 篇 A1-A3）；本篇证明 evdev 层是补齐点且此后无第二改写点——`input.c:412`（FLUSH 后清 MONO 槽）保证每个 packet 重新取时，取时后至写入 buffer 之间无时钟再换算（MONO 槽原样读出）。

**边界声明**：同源单点吻合＝已证（契约 :70）；**全程逐点同源＝未证**（00 篇 §3.1；本篇 §6.3 后果三）；host 产生事件到 guest 取时之间的延迟不可测＝盲区（批01 A18，wire 无时戳）。

### 7.2 1195 帧/1194 间隔的透传链路（契约 :103 配对点二）

**结论（已证，逐段归属）**：1194 个间隔的分布在 guest 内核内经历的全部环节都不塑造它：

1. **驱动段**（批01 已证，A10）：virtio_input.c 414 行无节流判据，事件逐个透传 `input_event()`。
2. **input core 段**（本篇核对的两点）：攒批按事件计数（`input.c:396-400` 逐值入 `dev->vals`），flush 按 SYN_REPORT 触发（`input.c:402-405`），无定时器参与；容量兜底路径（`input.c:413-417`）只在单包事件数超过 `max_vals` 时把大包拆成两次下发并各附一个合成 SYN_REPORT（`input_value_sync`，`input.c:48`）——它改变的是**包内事件归属**，不延迟、不丢弃事件本身。该兜底路径与 `:402-412` 的 FLUSH 分支互斥（else-if），不清时戳槽：拆分出的两次下发在 evdev 层共用同一次取时读数（第一次下发时 `input_get_timestamp` 灌槽，第二次因槽非零直接复用，`input.c:2059-2060` 的判据）。已证（`input.c:413-417`＋`:2055-2063`）。
3. **evdev 段**（本篇已证）：写侧逐值拷贝（`evdev.c:267-283`），无节流、无合并、无延迟判据；唯一丢弃路径是溢出（getevent client 已排除，§7.3）；过滤未启用（`evmasks` 全 NULL＋消费者无 EVIOCSMASK，§4.3）。
4. **读侧不重塑统计值**：CSV 的间隔来自各 SYN_REPORT 行的 `event.time` 之差（`event_time_s` 列即 `event.time.tv_sec` 口径，由 `getevent.c:671-672` 打印、CSV 提取）——**时戳在 evdev 写入时已定，getevent 何时读到、打印多慢都不改变统计值**；慢读的后果是缓冲积压直至溢出，而溢出已排除。已证（机制）。

合并判定：**guest 内核从 virtio used ring 到 getevent client 缓冲，对 1194 个间隔的分布零塑造（除已排除的溢出）**。分布形状（均值 16.617 ms、双峰倾向、最大 93.711 ms 在 trace 起点前）的成因归属保持批01 §9.3 的结论：host 后端与 host 上游（未证/盲区）。批01 §9.2 末尾把"guest 内核路径整体无节流"标为未证、待 02/03 篇裁决——**本篇依据第 2、3 点的逐行走读，把 evdev 段的裁决完成；input core 段的完整裁决归批02**（本篇引用的 `input.c` 行号仅覆盖判据点，未覆盖 2635 行全文）。三态维持：驱动段＋evdev 段已证；input core 段判据点已证、全文裁决归批02；host 段未证/盲区。

### 7.3 touch.txt 无 SYN_DROPPED 的反证判据（契约作业单指定"标已证并给判据行号"）

**判据 A（文本判据）**：`grep -c "SYN_DROPPED" touch.txt` = 0（本批实际执行，输出见过程记录）。标签机制保证该判据有效：getevent 标签表由 `generate-input.h-labels.py` 从 uapi 头的 `SYN_` 定义生成（`generate-input.h-labels.py:51`），`SYN_DROPPED` 在 uapi 头有定义（`input-event-codes.h:60`）。

**判据 B（标签无关判据）**：不依赖标签表的实际生成内容——`print_event` 对无标签 code 打印十六进制（`getevent.c:265-268`），故任何非 SYN_REPORT 的 EV_SYN 事件都会使 touch.txt 出现一条"EV_SYN 且非 SYN_REPORT"的行。本批执行：

- `grep -c "event0: EV_SYN" touch.txt` = 1195；其中非 SYN_REPORT 行 0 条；
- 全部 8 个节点合计，非 SYN_REPORT 的 EV_SYN 行 0 条；十六进制形式的 EV_SYN 行（`EV_SYN` 后接十六进制 code）0 条。

（三条计数命令与输出摘要见过程记录。）

**判据的逻辑闭合（已证）**：溢出 ⇒ `__pass_event` 必然写一条 SYN_DROPPED 且置 `packet_head = tail` 使其可读（`evdev.c:224-240`，§4.4）；可读 ⇒ getevent 的读循环必然读到并打印（`getevent.c:666-676`，无过滤逻辑）。逆否：**touch.txt 无 SYN_DROPPED ⇒ getevent client 未发生缓冲溢出**。加上判据 B 覆盖标签缺失情形，判据完备。**已证**。

**边界**：本判据只覆盖 getevent client 与全部 8 个节点的 getevent client 集合；EventHub client 的缓冲状态不在判据覆盖内（§6.4 未证项）。

## 8. evdev_grab：独占语义

**规则是什么**：EVIOCGRAB 使一个 client 独占设备——evdev 层只给该 client 下发，input core 层也只给该 handle 下发。

**谁设置**：用户态 ioctl。`evdev.c:1090-1094`：

```c
	case EVIOCGRAB:
		if (p)
			return evdev_grab(evdev, client);
		else
			return evdev_ungrab(evdev, client);
```

`drivers/input/evdev.c:345-358`（签名 `:345-348`）：

```c
	if (evdev->grab)
		return -EBUSY;

	error = input_grab_device(&evdev->handle);
	if (error)
		return error;

	rcu_assign_pointer(evdev->grab, client);

	return 0;
```

走读：两级独占。第一级 evdev 内部：`evdev->grab` 指针（`:349-350` 已被占用返回 `-EBUSY`；`:356` RCU 赋值）——生效点在 `evdev_events :304-307`（有 grab 则跳过 client_list 遍历）。第二级 input core：`input_grab_device`（`drivers/input/input.c:538-557`）把 `dev->grab` 设为本 handle（`:552`），生效点在 `input_pass_values :139-141`（有 grab 时**只**调 grab handle 的 `input_to_handler`，`h_list` 上其他 handler 全部收不到）——即 evdev 独占同时屏蔽 mousedev/joydev 等其他 handler。释放侧 `evdev_ungrab`（`evdev.c:361-374`）先清指针再 `synchronize_rcu` 后 `input_release_device`（`:369-371`）；client 关闭时自动释放（`evdev_release :450`）。

**谁执行/后果**：对本链路——Android 精树内 grep EVIOCGRAB 的调用者只有 `system/teeui/libteeui/src/evdev.cpp:198`（TUI 确认框场景，非本链路）；EventHub 与 getevent 均未调用（grep 无匹配）。数据侧反证：touch.txt 与 trace 同时含 event0 数据，若任一方 grab，另一方按 `:304-307`/`input.c:139-141` 将无数据——**本样本 event0 处于未 grab 状态，已证（结构性推论）**。

## 9. 消费端对照：两个 evdev client 的实际调用面

本节对照 touch.txt 采集端（getevent）与 Android 消费入口（EventHub）各自打开节点后的实际调用面。EventHub 的完整走读（getEvents 主循环、RawEvent 组装、设备扫描）归批04——本节只覆盖作业单指定的"EventHub 实际调用的 ioctl 子集"与 read 形态。

### 9.1 ioctl 子集对照表

| ioctl | evdev 实现 | getevent（`getevent.c`） | EventHub（`EventHub.cpp`） |
|---|---|---|---|
| EVIOCGVERSION | `evdev.c:1050-1051` | `:331` | `:1975` |
| EVIOCGID | `evdev.c:1053-1056` | `:336` | `:1983` |
| EVIOCGNAME | `evdev.c:1174-1175` | `:344` | `:1956` |
| EVIOCGPHYS | `evdev.c:1177-1178` | `:348` | `:1994` |
| EVIOCGUNIQ | `evdev.c:1180-1181` | `:352` | `:2002` |
| EVIOCGBIT(EV_x) | `evdev.c:1203-1206`→`handle_eviocgbit :782-804` | `:109`（print_possible_events） | `:2054-2060`（七类位图） |
| EVIOCGPROP | `evdev.c:1151-1153` | `:72` | `:2061` |
| EVIOCGABS | `evdev.c:1208-1221` | `:195` | `:802/:944`（轴信息） |
| EVIOCGKEY/EVIOCGSW | `evdev.c:1158-1172` | `:120/:147/:643` | `:855/:871/:927`（初始状态读） |
| EVIOCSREP | `evdev.c:1067-1078` | 无 | `:431`（仅 KEYBOARD 类，置 0 关内核重复） |
| EVIOCSCLOCKID | `evdev.c:1126-1130` | `:357`（CLOCK_MONOTONIC） | `:446`（MONO；SENSOR 类 BOOTTIME `:440-445`） |
| EVIOCGMTSLOTS | `evdev.c:1155-1156` | 无 | 无（grep 无匹配） |
| EVIOCGRAB | `evdev.c:1090-1094` | 无 | 无（grep 无匹配） |
| EVIOCSMASK/EVIOCGMASK | `evdev.c:1102-1124` | 无 | 无（grep 无匹配） |

走读：共同子集是"设备身份五件套＋时钟设置"。两处 EVIOCSCLOCKID 都把 event0 类设备设到 CLOCK_MONOTONIC（§6.3），这是"两个 client 时戳同槽"的前提。EventHub 的 EVIOCSREP 只作用于 KEYBOARD 类（`EventHub.cpp:428-434`），event0 是 TOUCH 类不适用。getevent 的 EVIOCGKEY/EVIOCGSW 属 `print_possible_events` 的启动期信息打印，成功路径不产生 SYN_DROPPED（§6.1 路径 2 说明）。

### 9.2 read 形态对照

- **getevent**：`open(device, O_RDONLY | O_CLOEXEC)`（`getevent.c:324`，阻塞模式）→ `poll(ufds, nfds, -1)`（`:658`）→ `read(ufds[i].fd, &event, sizeof(event))`（`:666-667`）单事件读取 → `printf` 打印（`:671-676`）。一次循环一个事件，对应 evdev_read 的"容量恰好一个事件"分支（§5.2 第 4 步）。
- **EventHub**：`open(devicePath.c_str(), O_RDWR | O_CLOEXEC | O_NONBLOCK)`（`EventHub.cpp:1947`）→ epoll 等待（`EventHub.cpp:1760` 的 `epoll_wait`，经由 §5.4 的 poll 挂接）→ `read(device->fd, readBuffer, sizeof(struct input_event) * capacity)`（`:1678-1679`）批量读取 → 逐事件填 RawEvent（`:1697-1707`，`when`/`readTime` 双时戳——`when` 取事件时戳 `:1699`，`readTime` 取 `systemTime(SYSTEM_TIME_MONOTONIC)` `:1700`，两者的差就是"事件时戳到读取时刻"的延迟量，批04 的输入延迟素材）→ capacity 用尽时回退 epoll 索引待下轮续读（`:1708-1712`）。
- **两个 client 并存的机制**：`evdev_open` 每次调用独立分配 client 并挂入 `client_list`（§3.3），`evdev_events` 逐 client 拷贝（§4.2 第 3 点）——getevent 的读取不减少 EventHub 可读的数据，反之亦然。已证。这直接支撑 00 篇 §1.2 节点 [4] 的"两侧时戳同源"注记（批03 论证即本节）。

## 10. 交接边界与下一篇

本篇闭环止于 `read()` 返回用户态与 RawEvent 双时戳的填充点（`EventHub.cpp:1699-1700`）。从这一行起——getEvents 主循环的设备增删分支（`:1546-1618`）、epoll/inotify/wake 三 fd 的分派（`:1622-1654`）、RawEvent 到 InputReader 的传递与 `loopOnce`——全部留给第 04 篇《EventHub 与 InputReader 读取段》（"见批04"）。

本篇已证并可供后篇引用的机制锚点：

1. 每 client 独立缓冲、逐 client 拷贝下发（`evdev.c:308-310`）——getevent 与 EventHub 并存互不干扰。
2. packet 可读判据 `packet_head != tail`（`evdev.c:551/:627-628`）与边界推进点（`:242-243`）——批04 的 getEvents 返回节奏、批05 的 batching 语义都以 packet 为单位。
3. eventTime＝flush 到达 evdev 层时刻的一次 `ktime_get()`（CLOCK_MONOTONIC）读数（`evdev.c:300`＋`input.c:2055-2063`＋`timekeeping.c:818-835`）——批05 的 MotionEvent.eventTime 归属、批15 的 43.597 ms 计时起点定义。
4. SYN_DROPPED 的三产生点与 InputReader 恢复协议（`InputDevice.cpp:394-415`）——批04 读到 SYN_DROPPED 分支时的引用基础。
5. `RawEvent.readTime` 与 `when` 的双时戳（`EventHub.cpp:1699-1700`）——批04 输入延迟分解的素材。
6. getevent client 未溢出（§7.3）——批15 归因时可把"采集端丢帧"从候选表排除（仅限 getevent client 口径）。

## 11. 断言与三态汇总

本篇机制性结论的三态汇总（断言-证据对照的完整表在《过程记录/批03-过程记录.md》，编号一致）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| B1 | evdev 对每个 input_dev 通配建节点 | 已证 | evdev.c:1417-1420/:1435-1438；input.c:2393-2413 |
| B2 | event%d 序号＝minor−64，首个 connect 得 event0 | 已证 | evdev.c:10-11/:1349/:1367-1371；input.c:2562-2582 |
| B3 | 每次 open 得独立 client，缓冲＝roundup_pow2(max(hint×8,64)) | 已证 | evdev.c:465-472/:474-504 |
| B4 | event0 client 确切 bufsize | 盲区 | 依赖 host 声明的轴集合（virtio_input.c:304-308 批01）；下限 64 已证（evdev.c:12） |
| B5 | 默认 client 时钟＝INPUT_CLK_REAL | 已证 | evdev.c:481（kvzalloc）＋input.h:39-44 |
| B6 | 事件下发前提＝handle->open | 已证 | evdev.c:403-404；input.c:144/:618 |
| B7 | evdev_events 一次取时、逐 client 共享同一读数 | 已证 | evdev.c:300/:260-262/:308-310 |
| B8 | 唤醒仅由非空 SYN_REPORT 触发 | 已证 | evdev.c:271-277/:287-289 |
| B9 | evdev 不改写事件内容与时戳 | 已证 | evdev.c:260-262/:279-281/:221 |
| B10 | packet 边界判据三点咬合 | 已证 | evdev.c:242-243/:551/:627-628 |
| B11 | 每批到达 evdev 的 vals 以 SYN_REPORT 收尾 | 已证 | input.c:275-277/:402-405/:48/:413-415；evdev.c:83-85 |
| B12 | SYN_DROPPED 三产生点穷尽 | 已证 | evdev.c:224-240/:914-916/:204-207 |
| B13 | 溢出必然产生可读 SYN_DROPPED | 已证 | evdev.c:224-240（packet_head=tail 使其可读） |
| B14 | InputReader 的 SYN_DROPPED 恢复协议 | 已证 | InputDevice.cpp:394-415 |
| B15 | getevent 无 SYN_DROPPED 恢复逻辑（纯打印） | 已证 | getevent.c:656-689/:676 |
| B16 | ktime_get＝CLOCK_MONOTONIC | 已证 | timekeeping.c:818-835；posix-timers.c:1318-1321/:204-207 |
| B17 | eventTime＝到达 evdev 层时刻的新鲜取时 | 已证 | input.c:2055-2063/:412；evdev.c:300 |
| B18 | getevent 与 EventHub 均设 MONO | 已证 | getevent.c:316/:357；EventHub.cpp:440-446 |
| B19 | touch.txt 与 trace eventTime 同源（机制） | 已证 | B7/B9/B18＋§7.1 |
| B20 | 同源单点数值吻合（6.787410） | 已证 | 契约 :70；CSV 行 6.787410 本批核对 |
| B21 | 全程逐点同源校验 | 未证 | 00 篇 §3.1 声明；本篇不升级 |
| B22 | MONO−BOOT 差（累计 suspend）在单点精度内不可分辨 | 已证（推论） | B20＋CSV 列口径（assuming_clock） |
| B23 | getevent client 全程未溢出 | 已证 | §7.3 判据 A/B（touch.txt 双计数） |
| B24 | EventHub client 未溢出 | 未证 | 无日志/trace 证据；算术下界 §6.4 |
| B25 | guest 内核（驱动＋evdev 段）对 1194 间隔零塑造 | 已证 | 批01 A10＋本篇 §7.2 第 3 点 |
| B26 | input core 段全文无节流的完整裁决 | 未证（本篇） | 判据点已证（input.c:402-417）；全文裁决归批02 |
| B27 | host 写入节奏塑造分布形状 | 未证/盲区 | 批01 A16-A18 维持 |
| B28 | event0 未被 grab，两 client 并存 | 已证 | grep 无 EVIOCGRAB＋双端数据并存推论；evdev.c:304-307 |
| B29 | android_vh_pass_input_event 钩子在本 trace 是否启用 | 未证 | evdev.c:218-219；证据包无该事件名 |
| B30 | evdev_write 注入入口存在但不在本链路 | 已证 | evdev.c:506-542/:534 |

（完）