# 01 · virtio_input 前端驱动

> AppList 性能全链路解剖 · 第 01 篇（批01 作业单执行稿）
> 源码基准：`/home/liang/Project/AAOS13_kernel/common`（5.15.41，android13-5.15 GKI，HEAD `24d27dff64c4`）。写作前已核验：`git status` 中 drivers/virtio/ 与 drivers/input/ 无任何未提交修改，本文全部行号在该树有效。
> 时间口径：trace 数值一律 Perfetto 相对时间 `00:00:06.xxxxxx`。
> 证据三态：**已证**（源码或 trace 明确支持）／**未证**（有候选无证据）／**盲区**（采集配置或源码缺失）。kernel 树只读，本文是唯一载体。
> 摘抄规则：每个代码块只摘 ≤10 行逐字原文；块内 `... /* 略 ：a-b */` 为省略标记，省略段行号随标。

## 0. 本篇范围与上下游边界

本篇走读 `drivers/virtio/virtio_input.c` 全部 414 行（证据：`wc -l` = 414，见过程记录），外加两个 uapi 头与 `drivers/virtio/virtio_ring.c` 的概述级走读。边界如下：

- **上游边界**：host 后端把一个触摸事件写入 events virtqueue 的 used ring。host 后端的代码不在本基准树内，凡涉及 host 侧行为一律标 **盲区** 或 **未证**。
- **下游边界**：guest 侧事件进入 `input_event()`（`drivers/input/input.c:438`）为止。input core 内部的处置判定与分发留给第 02 篇；evdev 节点创建、缓冲与 `read()` 留给第 03 篇。
- **一条硬结论预告**：本篇将逐行证明——virtio_input.c 这 414 行内不存在任何周期性节流判据（已证），因此本战役 trace 中 event0 节点 1194 个报点间隔的分布形状（均值 16.617 ms、P90 31.416 ms、P95 36.122 ms），其成因归属下界在本驱动之上或更上游，guest 前端驱动这一段不参与塑造该分布。

主线先闭环：第 5 节走读完 `virtinput_recv_events`，"host 报点 → guest 内核 `input_event()`"这条主线即闭合；其后各节展开分支与机制四问。

## 1. 主线问题：event0 的 1194 个间隔从哪里开始

本战役最新样本（`20261113-151358-vertical-1912967`，trace.perfetto 66,967,010 字节）中，与本篇直接相关的 trace 事实（数值引自战役计划第三节核心证据包，逐字一致）：

- 设备输入节点 event0（名 `virtio_input_tp0`）。
- event0 统计：1195 帧/1194 间隔；均值 16.617 ms、中位 16.347、P90 31.416、P95 36.122、P99 40.936、最大 93.711 ms（最大在保留 trace 起点前）；>25 ms 209 个（17.5%）、<10 ms 253 个（21.2%）；接触只见一次 DOWN/TRACKING_ID 无 UP。
- 重点间隔 37.237 ms（00:00:06.750173→00:00:06.787410），之后 2.842 / 9.958 / 16.588 / 16.390 ms。
- event0 坐标变化＋SYN_REPORT：约 00:00:06.787410，与事件 ID `0x3b3b7f02` 的 eventTime 数值吻合（同源单点）；后续位置变化约 00:00:06.800210、00:00:06.816798。
- 由均值做算术换算：1000 ms / 16.617 ms ≈ 60.18 Hz。60.18 Hz 的均值不等于每间隔均匀 16.67 ms——分布有 21.2% 的 <10 ms 间隔和 17.5% 的 >25 ms 间隔，形状是双峰倾向的，不是均匀节拍。

这些间隔是谁决定的？按链路一段一段拆：host 侧产生节奏（盲区）→ guest 前端驱动 virtio_input.c（本篇，下文证明无节流判据）→ input core（02 篇）→ evdev（03 篇）→ 用户态读取（03/04 篇）。本篇的任务是把 guest 前端驱动这一段逐行核查：它做了什么、没做什么、哪些机制点必须在它内部回答。

## 2. 三个结构体与七个配置常量

### 2.1 wire 事件结构 virtio_input_event：没有 time 字段

`drivers/virtio/virtio_input.c:8-9` 只包含两个 uapi 头：`<uapi/linux/virtio_ids.h>` 与 `<uapi/linux/virtio_input.h>`。wire 上传递的事件结构在 `include/uapi/linux/virtio_input.h:70-74`：

```c
struct virtio_input_event {
	__le16 type;
	__le16 code;
	__le32 value;
};
```

走读：type/code/value 三个字段，小端编码，共 8 字节。**没有时间字段**。这个事实是机制四问之二（事件时戳来源）的源头：host 与 guest 之间的 wire 协议不携带时戳，host 想透传它的产生时刻也没有字段可放。该结论已证（结构体定义全文 5 行，无 time 字段）。

### 2.2 guest 内核侧事件结构 input_event：带 time 字段

`include/uapi/linux/input.h:28-47`：

```c
struct input_event {
#if (__BITS_PER_LONG != 32 || !defined(__USE_TIME_BITS64)) && !defined(__KERNEL__)
	struct timeval time;
	... /* 略 ：31-42，两分支的 sec/usec 宏定义 */
#endif
	__u16 type;
	__u16 code;
	__s32 value;
};
```

走读：input_event 在 type/code/value 之外多出 sec/usec 两个时戳字段（`input.h:30-42`，编译分支决定布局）。对比 2.1：wire 结构 8 字节三字段，input_event 至少 16 字节五字段。**两个结构体之间的差集就是"时戳"，而 virtio_input.c 从 wire 结构复制到 `input_event()` 调用的实参里只有 type/code/value 三个数**（见第 5 节 `virtio_input.c:44-47`），时戳由 guest 内核更下层补上——第 8.2 节给完整证据链。

概念定义（全文只定义一次）：本文说 **wire 事件** 指 `struct virtio_input_event`，说 **内核事件** 指 `struct input_event`。

### 2.3 配置空间结构 virtio_input_config 与七个 select 常量

`include/uapi/linux/virtio_input.h:32-40` 定义驱动可向 host 配置空间请求的七类信息：

```c
enum virtio_input_config_select {
	VIRTIO_INPUT_CFG_UNSET      = 0x00,
	VIRTIO_INPUT_CFG_ID_NAME    = 0x01,
	VIRTIO_INPUT_CFG_ID_SERIAL  = 0x02,
	VIRTIO_INPUT_CFG_ID_DEVIDS  = 0x03,
	VIRTIO_INPUT_CFG_PROP_BITS  = 0x10,
	VIRTIO_INPUT_CFG_EV_BITS    = 0x11,
	VIRTIO_INPUT_CFG_ABS_INFO   = 0x12,
};
```

配置空间的返回载体 `include/uapi/linux/virtio_input.h:57-68`：

```c
struct virtio_input_config {
	__u8    select;
	__u8    subsel;
	__u8    size;
	__u8    reserved[5];
	union {
		char string[128];
	... /* 略 ：64-66，bitmap[128] / abs / ids 三分支 */
	} u;
};
```

走读：配置空间是一块由 host 维护的只读内存。驱动按 `select`（大类别）＋`subsel`（类别内索引，如某个 EV_ABS 轴号）发起读取，返回 `size`（有效字节数）与 `u` 联合体内容：name/serial 走 `u.string`（:63），能力位图走 `u.bitmap`（:64），轴参数走 `u.abs`（:65，结构 `virtio_input.h:42-48`，min/max/fuzz/flat/res 五个 `__le32`），设备 ID 走 `u.ids`（:66，结构 `virtio_input.h:50-55`，bustype/vendor/product/version 四个 `__le16`）。本战役设备名 `virtio_input_tp0` 就是 `VIRTIO_INPUT_CFG_ID_NAME` 类别下 `u.string` 里的字节——**该字符串由 host 后端预先放置，guest 只负责搬运**（搬运代码在第 4.4 节；host 如何构造这个字符串＝盲区）。

### 2.4 驱动私有结构 struct virtio_input

`drivers/virtio/virtio_input.c:12-21`（:22 为收尾 `};`）：

```c
struct virtio_input {
	struct virtio_device       *vdev;
	struct input_dev           *idev;
	char                       name[64];
	char                       serial[64];
	char                       phys[64];
	struct virtqueue           *evt, *sts;
	struct virtio_input_event  evts[64];
	spinlock_t                 lock;
	bool                       ready;
```

走读，逐字段：

- `vdev`/`idev`：分别指向 virtio 总线设备与 input core 设备，本驱动的全部工作就是把两者接起来。
- `name[64]`/`serial[64]`/`phys[64]`：三段字符串缓冲。name/serial 来自配置空间（第 4.4 节），phys 由驱动自造（`virtio_input.c:255-256`）。**64 字节上限来自这里的数组尺寸；配置空间实际有 128 字节（`virtio_input_config.u.string[128]`），读取时被 `min(size, sizeof(vi->name))` 截断（`virtio_input.c:250`）**。
- `evt`/`sts`：两个 virtqueue 指针，方向相反（第 5、6 节）。
- `evts[64]`：64 个 wire 事件的预分配池，是 events 队列的 buffer 池，上限来自 `ARRAY_SIZE(vi->evts)`（`virtio_input.c:210`）。全程循环复用，不再分配/释放（第 5.4 节证据）。
- `lock`：自旋锁，保护 `ready` 标志与两个队列操作的互斥。
- `ready`：一布尔开关。probe 尾部置 true（`virtio_input.c:318`），register 失败/remove/freeze 置 false（`virtio_input.c:328/346/364`）。两个回调都先检查它（`virtio_input.c:41/93`）。

## 3. virtio_ring.c 概述：本驱动用到的六个 API

概述级走读，不逐行。先给穷尽清单——grep `virtqueue_` 在 virtio_input.c 的全部调用点（已证，过程记录留有命令输出）：

| API | 调用点 | 用途 |
|---|---|---|
| `virtqueue_add_inbuf` | `virtio_input.c:30` | events 队列挂空闲 buffer |
| `virtqueue_get_buf` | `virtio_input.c:42/114` | 从 used ring 取回 buffer |
| `virtqueue_kick` | `virtio_input.c:51/95/214` | 通知 host |
| `virtqueue_add_outbuf` | `virtio_input.c:94` | status 队列挂待发 buffer |
| `virtqueue_get_vring_size` | `virtio_input.c:209` | 读 vring 槽位数 |
| `virtqueue_detach_unused_buf` | `virtio_input.c:351` | remove 时回收未用 buffer |

整个文件没有 `virtqueue_disable_cb`/`enable_cb`/`enable_cb_delayed` 调用（grep 无匹配）——这个"没有"在第 8.3 节承担关键角色。

### 3.1 add：把 buffer 交给对端

三个 add 函数收敛到 `virtqueue_add()`，按 packed/split 两种 ring 格式分派，`drivers/virtio/virtio_ring.c:1804-1807`：

```c
	return vq->packed_ring ? virtqueue_add_packed(_vq, sgs, total_sg,
					out_sgs, in_sgs, data, ctx, gfp) :
				 virtqueue_add_split(_vq, sgs, total_sg,
					out_sgs, in_sgs, data, ctx, gfp);
```

两个薄封装决定方向语义：`virtqueue_add_inbuf`（`virtio_ring.c:1880-1886`）末行 `return virtqueue_add(vq, &sg, num, 0, 1, data, NULL, gfp);`（:1885）——`out_sgs=0, in_sgs=1`；`virtqueue_add_outbuf`（`virtio_ring.c:1858-1864`）形状相同，末行实参为 `virtqueue_add(vq, &sg, num, 1, 0, ...)`（:1863）。注释原文写明方向：outbuf 是 "readable by other side"（`virtio_ring.c:1848-1851`），inbuf 是 "writable by other side"（`virtio_ring.c:1870-1872`）。

走读：**落到本驱动——events 队列上 guest 只 add_inbuf（`virtio_input.c:30`），即 guest 分配内存、host 有权写入，host→guest 方向；status 队列上 guest 只 add_outbuf（`virtio_input.c:94`），即 guest 写入、host 读取，guest→host 方向**。两队列的方向语义就此定死，这是第 5、6 节全部走读的结构基础。已证。

`data` 实参是 buffer 归属令牌：get_buf 返回它时，驱动凭它认出这是哪个 buffer。本驱动 events 队列的令牌就是池内 `virtio_input_event` 元素地址本身（`virtio_input.c:29-30` 把 `evtbuf` 同时当作内存与令牌传入）。

### 3.2 kick：两步通知

`drivers/virtio/virtio_ring.c:1969-1975`：

```c
bool virtqueue_kick(struct virtqueue *vq)
{
	if (virtqueue_kick_prepare(vq))
		return virtqueue_notify(vq);
	return true;
}
```

`virtqueue_notify`（`virtio_ring.c:1941-1954`）先查 `vq->broken`（:1945-1946），再调 `vq->notify(_vq)`（:1949）。notify 函数由传输层注册：virtio PCI 现代设备创建 vring 时传入二者——`vring_create_virtqueue(index, num, SMP_CACHE_BYTES, &vp_dev->vdev, true, true, ctx, vp_notify, callback, name)`（`drivers/virtio/virtio_pci_modern.c:214-217`）。`vp_notify` 的实现在 `drivers/virtio/virtio_pci_common.c:41-47`：

```c
	/* we write the queue's selector into the notification register to
	 * signal the other end */
	iowrite16(vq->index, (void __iomem *)vq->priv);
	return true;
```

走读：guest 侧的"通知 host"最终是一次 PCI I/O 写——把队列号写进通知寄存器。`virtqueue_kick_prepare`（`virtio_ring.c:1924-1930`）在 notify 之前做抑制判据检查：若 host 声明过"此次无需通知"（avail event idx 机制），通知可以省略。**kick 是 guest→host 方向；中断是 host→guest 方向；两者不是同一件事**。第 8.3 节把这两个方向的先后关系按机制四问拆开。

### 3.3 get_buf：取回已完成的 buffer

`virtqueue_get_buf(_vq, len)`（`virtio_ring.c:2004-2008`，内部转调 `virtqueue_get_buf_ctx`）：从 used ring 取下一个已由对端处理完的 buffer，`len` 返回对端写入的字节数；没有已用 buffer 时返回 NULL；返回值就是 add 时的 `data` 令牌（语义注释 `virtio_ring.c:1977-1992`）。

### 3.4 vring_interrupt：中断何时到 guest

`drivers/virtio/virtio_ring.c:2149-2157`：

```c
	struct vring_virtqueue *vq = to_vvq(_vq);

	if (!more_used(vq)) {
		pr_debug("virtqueue interrupt with no work for %p\n", vq);
		return IRQ_NONE;
	}

	if (unlikely(vq->broken))
		return IRQ_HANDLED;
```

`drivers/virtio/virtio_ring.c:2159-2167`：

```c
	/* Just a hint for performance: so it's ok that this can be racy! */
	if (vq->event)
		vq->event_triggered = true;

	pr_debug("virtqueue callback for %p (%p)\n", vq, vq->vq.callback);
	if (vq->vq.callback)
		vq->vq.callback(&vq->vq);

	return IRQ_HANDLED;
```

走读三个判定步骤：

1. `more_used(vq)`（`virtio_ring.c:2142-2145`，按 split/packed 分派检查 used ring 是否有新条目）不成立 → `IRQ_NONE`。
2. `vq->broken` 成立 → 直接返回 `IRQ_HANDLED`（对端状态已坏，不再干活）。
3. 两个判定都通过 → 调 `vq->vq.callback`。

这个 callback 就是 probe 时注册的回调（第 4.2 节），对 events 队列即 `virtinput_recv_events`。**中断处理没有线程化**：注册路径在 `virtio_pci_common.c` 中是 per-VQ MSI-X 直挂 `request_irq(..., vring_interrupt, 0, ...)`（`virtio_pci_common.c:339-340`），或共享中断 `vp_interrupt → vp_vring_interrupt → vring_interrupt`（`virtio_pci_common.c:82-100`）；request_irq 第三实参 flags=0，无 threaded irq。因此 **`virtinput_recv_events` 及其下游的 `input_event()` 调用运行在硬中断上下文**——已证（链条：`request_irq` 注册 → `vp_interrupt`/`vring_interrupt` → `vq->vq.callback`）。

### 3.5 struct vring_virtqueue 的关键状态

`drivers/virtio/virtio_ring.c:88-118`（节选）：

```c
struct vring_virtqueue {
	struct virtqueue vq;
	... /* 略 ：92-104，packed_ring/use_dma_api/weak_barriers/broken/indirect 标志 */
	/* Host publishes avail event idx */
	bool event;
	... /* 略 ：109-116，free_head/num_added/last_used_idx */
	/* Hint for event idx: already triggered no need to disable. */
	bool event_triggered;
```

走读：本驱动不触碰这些字段（全部经由六个 API 间接操作）。列出只为了两个判定点：`event`（host 支持 avail event idx 抑制，:107）与 `event_triggered`（:118，3.4 节第二道闸的旁路提示）在 virtio_input.c 中从未被设置——驱动对中断抑制机制全程不干预（grep 已证）。

## 4. virtinput_probe 全走读（:218-337）

probe 是整个驱动的装配段。走读顺序与代码顺序一致。

### 4.1 feature 检查与内存分配（:225-234）

```c
	if (!virtio_has_feature(vdev, VIRTIO_F_VERSION_1))
		return -ENODEV;

	vi = kzalloc(sizeof(*vi), GFP_KERNEL);
	if (!vi)
		return -ENOMEM;

	vdev->priv = vi;
	vi->vdev = vdev;
	spin_lock_init(&vi->lock);
```

走读：`VIRTIO_F_VERSION_1` 是现代 virtio 协议版本标记，不满足则拒绝设备（返回 `-ENODEV`，此设备对 input 子系统不存在）。`vdev->priv = vi` 是 virtio 总线的惯例：之后每个回调（第 5、6 节）都靠 `vq->vdev->priv` 取回这个结构（`virtio_input.c:35/108`）。

**机制四问之四的前半在这里出现：feature 缺失的后果是 probe 返回错误，`struct virtio_input` 与 `input_dev` 都不会创建，/dev/input/ 下不会有对应节点**（节点创建链在第 8.4 节给证据）。

### 4.2 init_vqs：两队列与回调注册（:186-201）

`drivers/virtio/virtio_input.c:188-196`：

```c
	struct virtqueue *vqs[2];
	vq_callback_t *cbs[] = { virtinput_recv_events,
				 virtinput_recv_status };
	static const char * const names[] = { "events", "status" };
	int err;

	err = virtio_find_vqs(vi->vdev, 2, vqs, cbs, names, NULL);
	if (err)
		return err;
```

随后的 `vi->evt = vqs[0]; vi->sts = vqs[1];`（`virtio_input.c:197-198`）按下标对齐收尾：`cbs[0]` 对 `names[0]="events"` 对 `vi->evt`；`cbs[1]` 对 `names[1]="status"` 对 `vi->sts`。

`virtio_find_vqs` 是 `include/linux/virtio_config.h:205-211` 的内联封装，直接转调 `vdev->config->find_vqs`，即传输层（PCI）实现；传输层在创建每个 vring 时把 callback 传给 `vring_create_virtqueue`（`virtio_pci_modern.c:214-217`，见 3.2 节引文），并按第 3.4 节的链条挂上中断。**回调注册与中断注册在 init_vqs 这一次调用里完成**。

调用位置：`virtinput_probe` 在分配 input_dev **之前**调 `virtinput_init_vqs`（`virtio_input.c:236`），失败跳 `err_init_vq`（:237-238），只回收 `vi`。

### 4.3 input device 分配（:240-245）

```c
	vi->idev = input_allocate_device();
	if (!vi->idev) {
		err = -ENOMEM;
		goto err_input_alloc;
	}
	input_set_drvdata(vi->idev, vi);
```

走读：`input_allocate_device()` 由 input core 提供（分配并初始化 `struct input_dev`，细节属第 02 篇）。`input_set_drvdata(idev, vi)` 建立反向指针——第 6 节的 `virtinput_status` 回调靠 `input_get_drvdata(idev)`（`virtio_input.c:122`）取回 `vi`。失败跳 `err_input_alloc`（`virtio_input.c:332-333`），该路径已执行过 init_vqs，所以要 `del_vqs`。

### 4.4 name / serial / phys / devids（:247-273）

`drivers/virtio/virtio_input.c:247-250`：

```c
	size = virtinput_cfg_select(vi, VIRTIO_INPUT_CFG_ID_NAME, 0);
	virtio_cread_bytes(vi->vdev, offsetof(struct virtio_input_config,
					      u.string),
			   vi->name, min(size, sizeof(vi->name)));
```

serial 同型（`virtio_input.c:251-254`，类别换成 `VIRTIO_INPUT_CFG_ID_SERIAL`、目标缓冲 `vi->serial`）。随后：

`drivers/virtio/virtio_input.c:255-259`：

```c
	snprintf(vi->phys, sizeof(vi->phys),
		 "virtio%d/input0", vdev->index);
	vi->idev->name = vi->name;
	vi->idev->phys = vi->phys;
	vi->idev->uniq = vi->serial;
```

走读：

- 读取套路：先 `virtinput_cfg_select` 问 size（见 4.5.1），再 `virtio_cread_bytes` 把 `u.string` 处的字节搬进驱动缓冲，长度取 `min(size, 64)`。
- **`vi->idev->name = vi->name`（`virtio_input.c:257`）是本战役 trace 配对的第一个锚点**：event0 节点名 `virtio_input_tp0` 逐字节来自 host 配置空间 `VIRTIO_INPUT_CFG_ID_NAME` 类别。guest 侧对它没有加工，只有搬运与 64 字节截断。
- `vi->phys = "virtio%d/input0"`（:255-256）：驱动自造，`%d` 为 virtio 总线设备序号；它将出现在 sysfs input 设备的 phys 属性里（02 篇核对 evdev 展开后的最终形态）。
- `uniq = vi->serial`（:259）：host 若未提供 serial，`size=0`，`vi->serial` 保持 kzalloc 后的全零，uniq 为空串。

devids 读取在 `drivers/virtio/virtio_input.c:261-262`：

```c
	size = virtinput_cfg_select(vi, VIRTIO_INPUT_CFG_ID_DEVIDS, 0);
	if (size >= sizeof(struct virtio_input_devids)) {
```

条件成立时逐字段 `virtio_cread_le` 读 bustype/vendor/product/version 进 `vi->idev->id`（:263-270）；不成立时 :271-273 走兜底分支 `vi->idev->id.bustype = BUS_VIRTUAL;`。

走读：host 提供的 devids 不足 8 字节（`sizeof(struct virtio_input_devids)`，四个 `__le16`）时，bustype 兜底为 `BUS_VIRTUAL`，vendor/product/version 保持全零。这是"配置缺失"的第一种后果形态：**不失败，但降级**——ID 四元组影响用户态按 ID 匹配设备的逻辑（.idc 文件等，第 04 篇核对 EventHub 怎么消费）。

### 4.5 能力位图：cfg_select / cfg_bits（:127-170, :275-300）

#### 4.5.1 virtinput_cfg_select

`drivers/virtio/virtio_input.c:127-136`（主体 ：132-136）：

```c
	virtio_cwrite_le(vi->vdev, struct virtio_input_config, select, &select);
	virtio_cwrite_le(vi->vdev, struct virtio_input_config, subsel, &subsel);
	virtio_cread_le(vi->vdev, struct virtio_input_config, size, &size);
	return size;
```

走读：两次配置空间写（选定类别与子项）＋一次配置空间读（取 size）。返回值是 host 声明的该子项有效字节数，0 表示"没有这一项"。

#### 4.5.2 virtinput_cfg_bits

`drivers/virtio/virtio_input.c:143-149`：

```c
	u8 bytes;

	bytes = virtinput_cfg_select(vi, select, subsel);
	if (!bytes)
		return;
	if (bitcount > bytes * 8)
		bitcount = bytes * 8;
```

`drivers/virtio/virtio_input.c:159-169`：

```c
	virtio_cread_bytes(vi->vdev, offsetof(struct virtio_input_config,
					      u.bitmap),
			   virtio_bits, bytes);
	for (bit = 0; bit < bitcount; bit++) {
		if (virtio_bits[bit / 8] & (1 << (bit % 8)))
			__set_bit(bit, bits);
	}
	kfree(virtio_bits);
	... /* 略 ：167 空行 */
	if (select == VIRTIO_INPUT_CFG_EV_BITS)
		__set_bit(subsel, vi->idev->evbit);
```

走读，四个要点：

1. **`if (!bytes) return;`（`virtio_input.c:146-147`）是"配置缺失"的第二种后果形态：该能力位整段静默缺省**。host 说没有，guest 就不设位，input core 之后按位判定事件合法性（第 02 篇的 `is_event_supported`，`drivers/input/input.c:443`）。
2. 位图逐位搬运：`virtio_bits[bit / 8] & (1 << (bit % 8))`（:162-164），首个字节承载 bit 0-7（布局注释 `virtio_input.c:151-155`）。缓冲由 `kzalloc(bytes, GFP_KERNEL)` 临时分配，失败同样静默 return（:156-158）：位图缺省，不报错。
3. 最后一行 `__set_bit(subsel, vi->idev->evbit)`（:168-169）只在 select 为 `VIRTIO_INPUT_CFG_EV_BITS` 时执行：只要 host 声明了某个事件类别的位图，就顺手把该类别本身设进 evbit。
4. 位图来源是 `u.bitmap`（:159-161），即 2.3 节配置联合体。

#### 4.5.3 probe 的能力装配顺序（:275-300）

`drivers/virtio/virtio_input.c:275-282`：

```c
	virtinput_cfg_bits(vi, VIRTIO_INPUT_CFG_PROP_BITS, 0,
			   vi->idev->propbit, INPUT_PROP_CNT);
	size = virtinput_cfg_select(vi, VIRTIO_INPUT_CFG_EV_BITS, EV_REP);
	if (size)
		__set_bit(EV_REP, vi->idev->evbit);

	vi->idev->dev.parent = &vdev->dev;
	vi->idev->event = virtinput_status;
```

`drivers/virtio/virtio_input.c:284-290`：

```c
	/* device -> kernel */
	virtinput_cfg_bits(vi, VIRTIO_INPUT_CFG_EV_BITS, EV_KEY,
			   vi->idev->keybit, KEY_CNT);
	virtinput_cfg_bits(vi, VIRTIO_INPUT_CFG_EV_BITS, EV_REL,
			   vi->idev->relbit, REL_CNT);
	virtinput_cfg_bits(vi, VIRTIO_INPUT_CFG_EV_BITS, EV_ABS,
			   vi->idev->absbit, ABS_CNT);
```

续 :291-294 同型读取 EV_MSC（mscbit）与 EV_SW（swbit）；:296-300 注释 "kernel -> device" 下同型读取 EV_LED（ledbit）与 EV_SND（sndbit）。

走读：

- 注释把两类方向分开："device -> kernel"（KEY/REL/ABS/MSC/SW，host→guest 的事件类别）与 "kernel -> device"（LED/SND，guest→host 的输出类别）。对应第 5、6 节的两条 virtqueue 通路。
- `vi->idev->event = virtinput_status`（:282）：把第 6 节的回调挂进 input_dev。这个字段在 input core 里的触发条件（`INPUT_PASS_TO_DEVICE` 处置位）属第 02 篇；本篇先记事实：**该赋值是 guest→host 回写通路的注册点**。
- EV_REP 单独处理（:277-279）：host 报了 size 才设 EV_REP。EV_REP 只对 EV_KEY 的重复键有意义（repeat 判定在 `dev->key`/`keybit` 上，`drivers/input/input.c:186-187`）。本战役设备是一次 DOWN/TRACKING_ID 无 UP 的触摸流（trace 事实），EV_KEY 重复键与本流无关——**guest 内核 input core 的 autorepeat 定时器（`drivers/input/input.c:179-202`）不可能周期性生成触摸 MOVE 报点**，此判定已证（repeat 定时器触发条件为 EV_KEY；归 02 篇详述，此处只用于排除候选）。

### 4.6 ABS_INFO 与 MT slots（:172-184, :302-315）

`drivers/virtio/virtio_input.c:176-183`（:184 为收尾 `}`；函数签名 `virtinput_cfg_abs` 在 ：172-175）：

```c
	virtinput_cfg_select(vi, VIRTIO_INPUT_CFG_ABS_INFO, abs);
	... /* 略 ：177-181，u.abs 的 min/max/res/fuzz/flat 五个 cread_le */
	input_set_abs_params(vi->idev, abs, mi, ma, fu, fl);
	input_abs_set_res(vi->idev, abs, re);
```

走读：每个 ABS 轴读 min/max/res/fuzz/flat 五个 `__le32`，交给 `input_set_abs_params`/`input_abs_set_res`。这两个函数只写 `dev->absinfo[]`（第 02 篇核对），不产生事件。

probe 中的调用与 MT 初始化（`drivers/virtio/virtio_input.c:302-314`）：

```c
	if (test_bit(EV_ABS, vi->idev->evbit)) {
		... /* 略 ：303-308，对 absbit 置位轴逐个 virtinput_cfg_abs */
		if (test_bit(ABS_MT_SLOT, vi->idev->absbit)) {
			nslots = input_abs_get_max(vi->idev, ABS_MT_SLOT) + 1;
			err = input_mt_init_slots(vi->idev, nslots, 0);
			if (err)
				goto err_mt_init_slots;
		}
```

走读：host 声明了 EV_ABS 才进入；对 absbit 里每个置位的轴读一次 ABS_INFO。**`ABS_MT_SLOT` 置位是多点触摸协议 B 的标志**：以 host 声明的 slot 最大值 +1 作为槽数调 `input_mt_init_slots`。本战役设备是触摸流（trace 有 TRACKING_ID），该分支必然执行过——但 host 声明的轴集合本身属于配置空间内容，guest 无法验证其真伪（host 侧内容＝盲区）。`input_mt_init_slots` 失败跳 `err_mt_init_slots`（:312-314）。

### 4.7 ready 与注册的次序（:317-324）

```c
	virtio_device_ready(vdev);
	vi->ready = true;
	err = input_register_device(vi->idev);
	if (err)
		goto err_input_register;

	virtinput_fill_evt(vi);
	return 0;
```

走读，次序是本节的全部内容：

1. `virtio_device_ready(vdev)`（`include/linux/virtio_config.h:231-241`）：置 `VIRTIO_CONFIG_S_DRIVER_OK` 状态位，host 自此可以推进队列。
2. `vi->ready = true`（:318）：两个回调的 ready 判据打开。
3. `input_register_device(vi->idev)`（:319）：向 input core 注册。
4. `virtinput_fill_evt(vi)`（:323）：**此后**才把空闲 buffer 挂上 events 队列。

**推论（已证，链条三步）**：host 向 guest 投递事件的必要条件是 events 队列上有挂着的 inbuf（第 3.1 节方向语义：host 只能写进 driver 预挂的 buffer）；而第一个 inbuf 在 `fill_evt`（:323）才挂上，晚于 `input_register_device` 返回（:319）。因此 **`input_event()`（:44）的任何一次执行都发生在 input_register_device 完成之后**。input core 侧配套证据：`dev->vals` 缓冲数组在 `input_register_device` 内部分配（`drivers/input/input.c:2295-2296`），`input_handle_event` 在 `!dev->vals` 时直接返回（`drivers/input/input.c:383-384`）——时序与数据依赖两个方向都闭环。

### 4.8 错误路径（:326-336）

`drivers/virtio/virtio_input.c:326-333`：

```c
err_input_register:
	spin_lock_irqsave(&vi->lock, flags);
	vi->ready = false;
	spin_unlock_irqrestore(&vi->lock, flags);
err_mt_init_slots:
	input_free_device(vi->idev);
err_input_alloc:
	vdev->config->del_vqs(vdev);
```

末段 `err_init_vq: kfree(vi); return err;` 在 ：334-336。

走读：三段式回滚，标签落差即资源释放差集——`err_input_register` 先清 ready 判据再落穿；`err_mt_init_slots` 释放 input_dev；`err_input_alloc` 删队列；`err_init_vq` 释放私有结构。**一个代码事实**：`err_mt_init_slots` 入口不清 `ready`（:330 直接落穿），即此路径上 `ready` 保持 true。后果评估：`fill_evt`（:323）未执行、events 队列上无 inbuf，host 无载体可投递事件（3.1 节方向语义），且随后 `del_vqs`（:333）删除队列——该状态窗口内不存在可达的事件路径。已证（调用点穷尽：`fill_evt` 全文件仅 ：323/:382 两处调用）。

## 5. host→guest 通路：virtinput_recv_events 逐行（:24-54）

### 5.1 buffer 入队原语（:24-31）

```c
static void virtinput_queue_evtbuf(struct virtio_input *vi,
				   struct virtio_input_event *evtbuf)
{
	struct scatterlist sg[1];

	sg_init_one(sg, evtbuf, sizeof(*evtbuf));
	virtqueue_add_inbuf(vi->evt, sg, 1, evtbuf, GFP_ATOMIC);
}
```

走读：单 scatterlist、8 字节 wire 事件、GFP_ATOMIC 分配标记（表示调用点可能在原子上下文，不允许睡眠——与 5.3 节的 hardirq 判定一致）。**返回值未检查**（:30）：`virtqueue_add_inbuf` 失败（ENOMEM/ENOSPC/EIO，注释 `virtio_ring.c:1822`）时该 buffer 静默丢失。可达性分析：本函数的两个调用点都在刚取出一个 used buffer 之后（:42 取出→:49 重挂、:212 批量挂）或队列刚建时（:212），ENOSPC（槽位满）在"取一挂一"的容量核算下不可达；ENOMEM/EIO 属异常路径，触发后果是池内一个 8 字节槽位永久退出循环、吞吐上限从 64 递减。**触发条件在正常路径不可达＝分析推论；失败不检查＝已证的代码事实**。

### 5.2 回调本体（:33-54）

`drivers/virtio/virtio_input.c:35-41`：

```c
	struct virtio_input *vi = vq->vdev->priv;
	struct virtio_input_event *event;
	unsigned long flags;
	unsigned int len;

	spin_lock_irqsave(&vi->lock, flags);
	if (vi->ready) {
```

`drivers/virtio/virtio_input.c:42-51`：

```c
		while ((event = virtqueue_get_buf(vi->evt, &len)) != NULL) {
			spin_unlock_irqrestore(&vi->lock, flags);
			input_event(vi->idev,
				    le16_to_cpu(event->type),
				    le16_to_cpu(event->code),
				    le32_to_cpu(event->value));
			spin_lock_irqsave(&vi->lock, flags);
			virtinput_queue_evtbuf(vi, event);
		}
		virtqueue_kick(vq);
```

逐行走读：

- :35 `vq->vdev->priv`：取回 probe 时挂上的 `struct virtio_input`（4.1 节）。
- :40 `spin_lock_irqsave(&vi->lock, ...)`：关本 CPU 中断抢锁。与谁互斥？`virtinput_send_status`（:92）、`virtinput_fill_evt`（:208）、`virtinput_remove`（:345）、`virtinput_freeze`（:363）、probe 的错误路径（:327）。
- :41 `if (vi->ready)`：ready 判据。ready=false 时什么都不做，事件滞留 used ring 不取。
- :42 `while ((event = virtqueue_get_buf(vi->evt, &len)) != NULL)`：**把 used ring 取空为止**。`len` 被忽略（host 写入必然是完整 8 字节 wire 事件，长度不作判据）。
- :43-48 解锁窗口：取出的每个事件在**不持锁**状态下转交 `input_event()`，转交完重新加锁。为什么必须解锁——5.3 节单独展开。
- :44-47 转交：实参是 `le16_to_cpu(event->type)/le16_to_cpu(event->code)/le32_to_cpu(event->value)`。**三个数，逐一从小端解码，原样上交。没有时戳实参（`input_event()` 的签名只有四参，`drivers/input/input.c:438-439`），没有过滤、没有改写、没有缓存**。
- :49 `virtinput_queue_evtbuf(vi, event)`：刚消费的 buffer 立刻挂回 avail ring，供 host 写下一个事件。
- :51 `virtqueue_kick(vq)`：while 循环结束后，通知 host"空闲 buffer 已重新挂上"。是否真的发出由 `kick_prepare` 的抑制判据决定（`virtio_ring.c:1969-1974`）。
- :53 解锁恢复中断。

**本段把 host 报点→guest 内核的主线闭环了**：host 写 used ring → 中断（3.4 节）→ 本回调 → while 取空 → 逐个 `input_event()`（进入第 02 篇的 input core）→ buffer 回池 → kick。中间每一跳都是同步的、逐事件的，没有攒批、没有延迟调用、没有工作队列。

### 5.3 解锁窗口：为什么 input_event 必须在不持 vi->lock 时调用

链条如下（已证，每步有行号）：

1. `input_event()` → `input_handle_event()`（`drivers/input/input.c:446`）。
2. `input_handle_event` 里 `if ((disposition & INPUT_PASS_TO_DEVICE) && dev->event) dev->event(dev, type, code, value);`（`drivers/input/input.c:380-381`）——**同步、同上下文**调用设备回写回调。
3. 本驱动把该回调注册为 `virtinput_status`（`virtio_input.c:282`），其实现转调 `virtinput_send_status`（`virtio_input.c:119-125`）。
4. `virtinput_send_status` 第一步就是 `spin_lock_irqsave(&vi->lock, flags)`（`virtio_input.c:92`）。

合并结论：若 `virtinput_recv_events` 持着 `vi->lock` 调 `input_event()`，且该事件处置位含 `INPUT_PASS_TO_DEVICE`，则同一 CPU 会在持锁状态下再次申请 `vi->lock`——自旋死锁。**:43-48 的解锁窗口是该死锁的规避点**。哪些事件类型会触发 `INPUT_PASS_TO_DEVICE`：驱动作者在注释里点过名——`virtio_input.c:68-81` 的注释写明 EV_MSC 被配置为 `INPUT_PASS_TO_ALL`（即 `PASS_TO_HANDLERS|PASS_TO_DEVICE`），LED 类事件同样走设备回写（第 02 篇给 `input_get_disposition` 的完整分支表）。已证。

### 5.4 fill_evt：64 个 buffer 的初次投放（:203-216）

`drivers/virtio/virtio_input.c:208-215`：

```c
	spin_lock_irqsave(&vi->lock, flags);
	size = virtqueue_get_vring_size(vi->evt);
	if (size > ARRAY_SIZE(vi->evts))
		size = ARRAY_SIZE(vi->evts);
	for (i = 0; i < size; i++)
		virtinput_queue_evtbuf(vi, &vi->evts[i]);
	virtqueue_kick(vi->evt);
	spin_unlock_irqrestore(&vi->lock, flags);
```

走读：投放量 = min(vring 槽位数, 64)。64 是 `evts[64]` 池的上限（`virtio_input.c:19`）。之后整个生命周期内，events 队列上的 buffer 恒为这同一批内存（:42 取出→:49 回挂），**没有补充分配代码**（grep 全文件仅 ：24-31 一处 add_inbuf 调用点）。这个恒定池深 64 是一个可测量上限：host 端若在 guest 回调完成前连续投递超过 64 个 wire 事件，必须等 guest 回挂——**但这不是 guest 主动节流**：guest 侧没有任何主动延迟或丢弃判据，纯粹是 buffer 池容量约束。触发该约束所需的事件密度与 host 行为是否出现在本战役 trace 中＝未证（1194 个间隔的分布里无单间隔小于事件处理时长的证据可查）。

## 6. guest→host 通路：send_status / recv_status（:56-125）

### 6.1 回调注释先读（:56-81）

函数顶部注释（`drivers/virtio/virtio_input.c:56-59`）声明错误语义：发送失败时丢失的是状态更新，"typically used for stuff like keyboard leds"，不致命。:68-79 的长注释记录一个已修复的回环放大缺陷：

```c
	 * EV_MSC/MSC_TIMESTAMP is added to each before EV_SYN event.
	 * EV_MSC is configured as INPUT_PASS_TO_ALL.
	 * In case of touch device:
	 *   BE pass EV_MSC/MSC_TIMESTAMP to FE on receiving event from evdev.
	 *   FE pass EV_MSC/MSC_TIMESTAMP back to BE.
	 *   BE writes EV_MSC/MSC_TIMESTAMP to evdev due to INPUT_PASS_TO_ALL.
	 *   BE receives extra EV_MSC/MSC_TIMESTAMP and pass to FE.
	 *   >>> Each new frame becomes larger and larger.
	 * Disable EV_MSC/MSC_TIMESTAMP forwarding for MT.
```

修复手段是 :80-81 的一行短路：

```c
	if (vi->idev->mt && type == EV_MSC && code == MSC_TIMESTAMP)
		return 0;
```

走读：host（BE）把 `EV_MSC/MSC_TIMESTAMP` 发给 guest 前端（FE），FE 因 `INPUT_PASS_TO_ALL` 处置位又经 `dev->event` 回写给 host，host 再写回 evdev，再回传——每帧变长。**这段注释对本文的三个用途**：

1. 证实 guest→host 回写通路真实存在且被实际数据走过（否则不会出现放大）。
2. 证实 `INPUT_PASS_TO_DEVICE` 处置位会同步调用 `dev->event`（5.3 节死锁链条的外部佐证，注释原文 "FE pass EV_MSC/MSC_TIMESTAMP back to BE"）。
3. 证实本驱动对多点触摸设备直接丢弃 MSC_TIMESTAMP（:80-81），guest 侧不转发。本战役 trace 的 eventTime 与 MSC_TIMESTAMP 无关（时戳来源在第 8.2 节裁决）。

### 6.2 send_status 本体（:60-104）

`drivers/virtio/virtio_input.c:83-85` 为 `kzalloc(sizeof(*stsbuf), GFP_ATOMIC)` 与 `!stsbuf → return -ENOMEM` 的分配保护；随后组包与发送：

`drivers/virtio/virtio_input.c:87-95`：

```c
	stsbuf->type  = cpu_to_le16(type);
	stsbuf->code  = cpu_to_le16(code);
	stsbuf->value = cpu_to_le32(value);
	sg_init_one(sg, stsbuf, sizeof(*stsbuf));

	spin_lock_irqsave(&vi->lock, flags);
	if (vi->ready) {
		rc = virtqueue_add_outbuf(vi->sts, sg, 1, stsbuf, GFP_ATOMIC);
		virtqueue_kick(vi->sts);
```

ready 不成立则 `rc = -ENODEV`（:96-98），锁恢复于 ：99；尾部 `if (rc != 0) kfree(stsbuf);`（:101-102）回收发送失败的 buffer。

走读：与 events 通路相反，这里每次发送**新分配**一个 8 字节 buffer（:83，GFP_ATOMIC），add_outbuf 后 kick（:94-95），host 消费完由 `virtinput_recv_status` 回收释放（:114-115 的 `kfree`）。分配失败或 ready=false 时放弃发送，顶层注释（:56-59）明示丢失状态更新不致命。**触摸报点（EV_ABS/EV_SYN）不经过此通路**：它们是 device→kernel 方向事件（4.5.3 节注释分类），处置位不含 `INPUT_PASS_TO_DEVICE` 的路径不会调 `dev->event`（`drivers/input/input.c:380-381`）——所以 event0 的 1195 帧不会反向写入 status 队列。已证（处置位判定属 02 篇完整展开，此处引用其 :380-381 的执行条件）。

### 6.3 recv_status 与 virtinput_status（:106-125）

`virtinput_recv_status`（:106-117）是 status 队列的 vring 回调，主体两行 `while ((stsbuf = virtqueue_get_buf(vi->sts, &len)) != NULL) kfree(stsbuf);`（`drivers/virtio/virtio_input.c:114-115`，位于 :113-116 的锁区间内）。它只做内存回收，不做任何事件处理。

`virtinput_status`（`drivers/virtio/virtio_input.c:119-125`）是 input core 的 `dev->event` 回调入口：

```c
static int virtinput_status(struct input_dev *idev, unsigned int type,
			    unsigned int code, int value)
{
	struct virtio_input *vi = input_get_drvdata(idev);

	return virtinput_send_status(vi, type, code, value);
}
```

走读：取回_drvdata 后转调 `send_status`。它注册于 probe 的 :282，早于 `input_register_device`，但能被触发的最早时点同样是注册完成后（handler 连接发生在 register 内部，`drivers/input/input.c:2334-2335` 的 `input_attach_handler` 循环）。

## 7. remove / freeze / restore（:339-385）

`drivers/virtio/virtio_input.c:345-354`：

```c
	spin_lock_irqsave(&vi->lock, flags);
	vi->ready = false;
	spin_unlock_irqrestore(&vi->lock, flags);

	input_unregister_device(vi->idev);
	vdev->config->reset(vdev);
	while ((buf = virtqueue_detach_unused_buf(vi->sts)) != NULL)
		kfree(buf);
	vdev->config->del_vqs(vdev);
	kfree(vi);
```

（函数头 `virtinput_remove` 在 ：339-344。）

走读：先清 ready 判据（并发回调安全：`recv_events`/`send_status` 都在同一把锁下检查 :41/:93），再注销 input_dev、复位设备、回收 status 队列残留 buffer（`virtqueue_detach_unused_buf`，`virtio_ring.c:2125-2140` 注释明示仅用于关停）、删队列、释放私有结构。**events 队列上没有 detach 循环**：`evts[64]` 池是 `vi` 的内嵌数组，`kfree(vi)` 时随结构一起释放，无需逐个回收——这是两个队列 buffer 管理不对称的根源（events 静态池，status 动态分配）。

挂起/恢复（`drivers/virtio/virtio_input.c:357-385`）。`virtinput_freeze`（:358-369）主体：

```c
	spin_lock_irqsave(&vi->lock, flags);
	vi->ready = false;
	spin_unlock_irqrestore(&vi->lock, flags);

	vdev->config->del_vqs(vdev);
	return 0;
```

（证据：`virtio_input.c:363-368`；函数头与锁声明 ：358-362，收尾 ：369。）`virtinput_restore`（:371-384）主体：

```c
	err = virtinput_init_vqs(vi);
	if (err)
		return err;

	virtio_device_ready(vdev);
	vi->ready = true;
	virtinput_fill_evt(vi);
	return 0;
```

（证据：`virtio_input.c:376-383`。）

走读：freeze 只清 ready 判据＋删队列（不注销 input_dev——用户态的 /dev/input/eventX 节点在冻结期间保留）；restore 按 probe 的同一次序重建：init_vqs → device_ready → ready=true → fill_evt。**冻结期间的事件丢失是设计内的**：ready=false 时 `recv_events` 不取 used ring（:41），host 在 del_vqs 后也无法投递。对本战役无影响判定：trace 采集窗口内无系统挂起事件（过程记录声明：本篇未核对 trace 中 suspend/resume 相关事件，该核对属批15 全时序归因；此处按源码机制陈述）。

驱动装配尾部（`drivers/virtio/virtio_input.c:387-414`）：`features[]` 为空表（:387-389，注释 "none"——驱动不声明任何设备 feature，仅依赖协议强制的 VIRTIO_F_VERSION_1 检查）；id_table 匹配 `VIRTIO_ID_INPUT`（:390-393）；`virtio_driver` 结构挂 probe/remove/freeze/restore（:395-407）；`module_virtio_driver` 注册（:409）；模块元数据至 ：414。

## 8. 机制四问

### 8.1 SYN_REPORT 由谁产生

**规则是什么**：input 协议里，一帧触摸数据以 `EV_SYN/SYN_REPORT` 结尾；input core 收到它才把攒着的坐标批量下传——`drivers/input/input.c:275-277` 给 SYN_REPORT 的处置位是 `INPUT_PASS_TO_HANDLERS | INPUT_FLUSH`：

```c
		case SYN_REPORT:
			disposition = INPUT_PASS_TO_HANDLERS | INPUT_FLUSH;
			break;
```

`drivers/input/input.c:402-405` 在 FLUSH 时调 `input_pass_values`（`if (disposition & INPUT_FLUSH) { if (dev->num_vals >= 2) input_pass_values(dev, dev->vals, dev->num_vals); dev->num_vals = 0;`）。input core 自己不为常规驱动路径合成 SYN_REPORT——它只在两个旁路点合成：autorepeat 定时器（`drivers/input/input.c:188-194`）与设备注销时的按键复位 `input_dev_release_keys`（`drivers/input/input.c:707-719`，:719 处 `input_pass_event(dev, EV_SYN, SYN_REPORT, 1)`），后者只发 EV_KEY 释放＋SYN。

**谁设置**：wire 事件的 type/code 由 host 后端写入 buffer。**谁执行**：`virtinput_recv_events` 透传（`virtio_input.c:44-47`），SYN_REPORT 与坐标一起按 8 字节原样进 `input_event()`。

**本驱动的生成点排查（已证）**：grep `EV_SYN` 在 virtio_input.c 全文仅命中一行注释（`virtio_input.c:70`，讲的是 MSC_TIMESTAMP 补丁背景）；驱动代码没有任何一处构造 `{EV_SYN, SYN_REPORT, ...}`。`virtinput_recv_events` 的 while 循环对 wire 事件零加工。**结论：SYN_REPORT 由 host 后端产生，guest 前端驱动只透传**。已证（驱动侧生成点不存在＋透传路径逐行）；**host 后端何时插入 SYN_REPORT、是否批量打包，host 代码不在基准树＝盲区**。

**后果**：SYN_REPORT 的到达节奏决定 input core 的批量下发节奏（`INPUT_FLUSH`），进而决定 evdev 一次 `read()` 拿到的 packet 边界（03 篇）。trace 中"坐标变化＋SYN_REPORT 约 00:00:06.787410"（证据包数值）的成帧者是 host 后端，guest 无感知加工。

### 8.2 事件时戳的来源与传递

**规则是什么**：用户态最终读到的 `struct input_event` 带 sec/usec（`include/uapi/linux/input.h:28-47`）；wire 上的 `struct virtio_input_event` 不带（`include/uapi/linux/virtio_input.h:70-74`）。

**谁设置（在本驱动路径上）**：没有谁。证据链四步，全部已证：

1. wire 结构无 time 字段（2.1 节）。
2. `virtinput_recv_events` 的转交实参只有 type/code/value 三个解码值（`virtio_input.c:44-47`）。
3. `input_event()` 签名为 `(dev, type, code, value)` 四参，无时戳参数（`drivers/input/input.c:438-439`）。
4. grep virtio_input.c 全文：无 `ktime`/`timeval`/`jiffies`/`do_gettime`（过程记录留有命令输出；唯一含 "time" 的行是 ：70 注释里的 MSC_TIMESTAMP 字样）。

**时戳实际在哪里补**：input core 为设备维护 `dev->timestamp[]` 三时钟槽——`input_set_timestamp`（`drivers/input/input.c:2040-2046`）把同一时刻换算写入 `INPUT_CLK_MONO/REAL/BOOT` 三槽（`dev->timestamp[INPUT_CLK_MONO] = timestamp;` 在 ：2042）。常规驱动事件路径**不调用它**（input.c 中 `input_set_timestamp` 的调用点只有 autorepeat 定时器 ：193 与 `input_get_timestamp` 内部 ：2060）；每次 FLUSH 后重置为无效值（`drivers/input/input.c:406-412`，`dev->timestamp[INPUT_CLK_MONO] = ktime_set(0, 0)`）；evdev 层在事件入 client 缓冲时调 `input_get_timestamp`（`drivers/input/evdev.c:300`，函数 `evdev_events`），该函数发现 `INPUT_CLK_MONO` 槽为无效值时以 `ktime_get()` 现场取时——`drivers/input/input.c:2055-2062`：

```c
ktime_t *input_get_timestamp(struct input_dev *dev)
{
	const ktime_t invalid_timestamp = ktime_set(0, 0);

	if (!ktime_compare(dev->timestamp[INPUT_CLK_MONO], invalid_timestamp))
		input_set_timestamp(dev, ktime_get());

	return dev->timestamp;
```

（引文含函数头与空行，:2056-2058/2061-2062 随标；尾行 `EXPORT_SYMBOL` 在 ：2064。）

**因此：eventTime 是 guest 内核在事件到达 input core/evdev 那一刻取的 `ktime_get()`，不是 host 产生事件的时刻，也不是本驱动改写的**。已证（此链路五点均有行号；evdev 侧细节归 03 篇展开）。

**谁执行/后果**：该机制解释了 trace 里"event0 坐标变化＋SYN_REPORT 约 00:00:06.787410 与事件 ID `0x3b3b7f02` 的 eventTime 数值吻合（同源单点）"这一配对现象的机制基础——evdev 的入缓冲时戳与 Android 侧 MotionEvent.eventTime 同源于 guest 内核单次取时，中途无第二处改写点（03 篇在 `evdev_read` 的时钟换算处继续核对）。**"host 产生事件到 guest 中断送达之间的真实延迟"在现有 trace 与源码内无法测量＝盲区**（wire 无时戳字段使两时刻不可比）。

### 8.3 virtqueue kick 与 vring 中断的先后关系

**规则是什么**：kick 是 guest→host 方向的一次写寄存器通知（`virtio_pci_common.c:41-47`）；中断是 host→guest 方向的硬件事件，guest 的处理函数是 `vring_interrupt`（`virtio_ring.c:2147-2168`）。两个方向独立存在，成对但不互为因果。

**谁设置**：中断到 guest 的条件由 host 决定（host 完成 buffer、写入 used ring 后决定是否发中断）；guest 可通过 avail event idx 机制向 host 声明推迟通知，但 **virtio_input.c 从不调用 `virtqueue_disable_cb`/`enable_cb_prepare`/`enable_cb_delayed`**（grep 已证，第 3 节表格），也从不直接设置 event idx——guest 对中断抑制零干预。

**谁执行**：中断到达时 `vring_interrupt` 过三个判定（`more_used` → broken → callback，3.4 节）；guest 发 kick 时 `virtqueue_kick` 过一个判定（`kick_prepare` 的抑制判据，`virtio_ring.c:1969-1974`）。

**执行的后果**：在本驱动的实际时序里，一条触摸事件的主线是——

1. host 写 used ring（盲区：host 是否按事件逐个写、是否攒批）。
2. host 发中断（盲区：host 侧中断合并策略；guest 未声明抑制，host 的默认行为不可从 guest 源码判定）。
3. guest hardirq `vring_interrupt` → `virtinput_recv_events` → while 取空 used ring → 逐个 `input_event()`（已证）。
4. 取空后 `virtqueue_kick(vq)`（`virtio_input.c:51`）——kick 在**取完**之后发生，不是取之前。kick_prepare 判定是否真写寄存器。

**先后关系的精确表述（已证）**：guest 收到中断在前、发出 kick 在后；两次中断之间的间隔由"host 下一次完成 buffer＋发中断"决定，guest 侧唯一参与的量是第 4 步的 kick 与 :49 的 buffer 回挂（不回挂则 host 无载体，中断也不会带来可取的 used buffer——`more_used` 判定不通过）。**"60.18 Hz 均值是否等于 host 以 16.67 ms 周期发中断"＝未证**（无中断时序 trace 佐证；本战役 trace 无 virtio 中断事件源）。

### 8.4 input_dev 注册失败/配置缺失的后果

**规则是什么**：probe 是全有或全无的装配段；任何一步失败都走 :326-336 回滚，设备以"不存在"呈现。**谁设置**：失败分支由三类输入决定——feature 协商结果、host 配置空间内容、input core 的注册结果。**谁执行**：probe 错误路径本身（4.8 节）。

**执行的后果，逐一列举（均已证，行号齐）**：

1. `VIRTIO_F_VERSION_1` 缺失 → `-ENODEV`（:225-226）→ 无 input_dev、无 virtqueue、无节点。
2. `virtinput_init_vqs` 失败 → `err_init_vq`（:236-238）→ 只释放 `vi`。
3. `input_allocate_device` 失败 → `err_input_alloc`（:241-244）→ 释放 input_dev＋del_vqs＋kfree。
4. `input_mt_init_slots` 失败 → `err_mt_init_slots`（:311-314）→ 释放 input_dev＋del_vqs＋kfree；ready 不清零但无可达事件路径（4.8 节已证）。
5. `input_register_device` 失败 → `err_input_register`（:319-321）→ 先置 `ready=false`（:327-329）阻止回调工作，再走同一回滚。注册失败的内部原因可来自 `device_add` 失败或 vals 分配失败（`drivers/input/input.c:2318-2320, 2295-2299`）。
6. 配置缺失（不失败、降级）：devids 不足 8 字节 → bustype=BUS_VIRTUAL（:271-273）；`cfg_bits` 返回 size=0 → 对应能力位整段缺省（:146-147）；EV_ABS 位缺省 → 跳过全部 ABS_INFO 且不进 MT 初始化（:302-315）——设备将不是触摸设备。
7. 成功注册的标志链：`device_add` 成功 → input core 打印 `"%s as %s"`（`drivers/input/input.c:2323-2325`，dev->name 即 `virtio_input_tp0`）→ handler 挂接循环（`drivers/input/input.c:2334-2335`）。**/dev/input/eventX 的创建在 evdev handler 的 connect 里，行号证据归 03 篇**——本文止步于"input core 侧注册动作完成"。

**对本战役的反事实判定**：以上任何一步失败，trace 里都不会有 event0 节点与 1194 个间隔。trace 显示节点存在且有数据流（证据包），故 probe 在本设备上成功完成——已证（trace 事实＋probe 成功后的必然产物链）。

## 9. 与本战役 trace 的配对

### 9.1 event0（名 virtio_input_tp0）就是本驱动注册的 input_dev 的产物

配对链条，逐步给证据：

1. **驱动侧 name 来源**：`vi->idev->name = vi->name`，name 来自配置空间 `VIRTIO_INPUT_CFG_ID_NAME` 的 `u.string` 搬运（`virtio_input.c:247-250, 257`）。已证。
2. **树内唯一性**：`drivers/input/` 目录下无任何 virtio 相关驱动（`ls`/Kconfig grep 无匹配，过程记录留有输出）；内核中 virtio 输入前端仅 `drivers/virtio/virtio_input.c` 一条路径（`id_table` 匹配 `VIRTIO_ID_INPUT`，`virtio_input.c:390-393`）。已证。
3. **name 的暴露**：input core 注册时以 `dev->name` 打印设备（`drivers/input/input.c:2323-2325`）；用户态通过 ioctl 读 name 属 evdev 层（`EVIOCGNAME`，03 篇核对）。trace 与 touch.txt 采到的节点名 `virtio_input_tp0` 与该链路的产物形态一致。
4. **配对结论**：trace 中 event0（名 `virtio_input_tp0`）即本驱动在 probe :319 注册的 input_dev 经 input core/evdev 产生的节点——**结构性已证**（链条 1+2+3）。剩余一环"host 为什么把 name 起成 virtio_input_tp0、以及 event0 序号 0 的分配规则"：前者 host 侧＝盲区，后者 evdev minor 分配＝03 篇证据。

### 9.2 驱动层无周期性节流逻辑（已证判定）

判定文本：**virtio_input.c 全部 414 行内，不存在任何节流判据——没有周期定时器、没有最小间隔检查、没有丢点逻辑、没有批量攒点逻辑。**

逐行排除证据（每行已实际读取，过程记录附命令输出）：

1. **主线循环无判据**：`virtinput_recv_events` 的 while 循环（:42-50）对每个 used buffer 无条件执行"取出→上交→回挂"。循环体内唯一的分支是 :41 的 ready 判据，无时间/计数/深度条件。上交点 ：44-47 是同步函数调用，无延迟语义。
2. **无定时器实体**：`struct virtio_input`（:12-22）无 timer/hrtimer/work/delayed_work 字段；grep 全文件 `timer|delay|sleep|jiffies|schedule|hrtimer` 大小写敏感零命中，大小写不敏感仅 :357/:403 两处 `#ifdef CONFIG_PM_SLEEP` 编译守卫（挂起/恢复段，非运行时节奏判据）。
3. **无可睡眠等待**：两处内存分配标记为 `GFP_ATOMIC`（:30/:83），代码在 hardirq 上下文运行（3.4 节链条已证），调度器在此上下文不可能介入制造延迟。
4. **无周期性合成**：驱动不产生任何事件（8.1 节已证 SYN_REPORT 无生成点；EV_MSC 的唯一处理是 :80-81 的丢弃分支）；input core 的 autorepeat 定时器（`drivers/input/input.c:179-202`）只对 EV_KEY 生效且要求 host 声明 EV_REP（`virtio_input.c:277-279`），对一次 DOWN/TRACKING_ID 无 UP 的触摸 MOVE 流不适用。
5. **唯一类节流的量是 buffer 池深 64**（4.5 节 `evts[64]`，:19/:210）：这是容量约束不是节奏判据——host 想更快投递时 guest 无法拒绝，只能以池深为上限背压。触发条件在 trace 中是否出现＝未证。

**该判定的推论边界（归属下界）**：

- **已证**：从 used ring 到 `input_event()` 入口的 guest 内核最前端一段，对 1194 个间隔的分布形状零加工——它不产生 16.617 ms 的均值，也不产生 21.2% 的 <10 ms 间隔或 17.5% 的 >25 ms 间隔。
- **已证（guest 内核后续段的部分旁证，完整裁决归 02/03 篇）**：`input_handle_event` 从入口到 `input_pass_values` 无延时逻辑（`drivers/input/input.c:367-419` 全文无定时/睡眠调用）；evdev `evdev_events` 把事件直接写入各 client 缓冲（`drivers/input/evdev.c:296-306` 函数头与取时戳后即遍历 client）。02/03 篇需以同样方法走读全部行数后才能把"guest 内核路径整体无节流"标为已证，本文不越界宣称。
- **未证（host 后端）**：host 把触摸事件写入 used ring 的节奏（是否每帧一批、是否攒批合并、中断合并策略）——候选解释 1194 间隔的双峰倾向，host 代码不在基准树。
- **盲区（host 更上游）**：host 侧的物理输入来源与 host 渲染节拍对报点节奏的影响，guest 内完全不可见。

### 9.3 间隔分布数值的解释边界

把 9.2 的判定落到具体数值上：

- 均值 16.617 ms（≈60.18 Hz）与中位 16.347 ms 接近 16.67 ms（60 Hz 周期），**但不构成"host 按 60 Hz 均匀发点"的证据**——P90 31.416 ms、P95 36.122 ms、>25 ms 209 个（17.5%）与 <10 ms 253 个（21.2%）并存，均匀节拍无法同时满足这些分位数。分布形状的成因归属：guest 驱动段已排除（9.2 已证）；host 后端与 host 上游为候选（未证/盲区）。
- 重点间隔 37.237 ms（00:00:06.750173→00:00:06.787410）与其后的 2.842 / 9.958 / 16.588 / 16.390 ms 四个节点间隔：guest 驱动对它们同样零加工（9.2 判定逐行适用于每个间隔）。这五段数值与"先空档后追赶"的形状是否符合 host 后端攒批-释放模型＝**未证**（需要 host 侧或带 wire 时戳的采集，现有采集配置不含）。
- 接触只见一次 DOWN/TRACKING_ID 无 UP：guest 驱动对 DOWN 与 MOVE 的处理路径相同（同一 while 循环、同一 `input_event()` 调用，:42-50），**无 UP 不是 guest 丢弃所致**——驱动没有丢弃分支（8.1 节的排查同样覆盖 UP：无任何 type 过滤）。已证。host 侧为何不发 UP＝未证/盲区。

## 10. 交接边界与下一篇

本篇闭环止于 `input_event()` 的四参调用（`virtio_input.c:44-47` → `drivers/input/input.c:438`）。从这一行起，事件进入 input core 的 `event_lock` 保护域（`drivers/input/input.c:445`），此后发生的事——disposition 判定（`input_get_disposition`，`drivers/input/input.c:261-365`）、`dev->vals` 攒批与 `INPUT_FLUSH` 下发（`drivers/input/input.c:386-417`）、handler 挂接与 `input_pass_values` 分发——全部留给第 02 篇《input core 核心层》；evdev 的 client 缓冲、packet 化与时钟换算留给第 03 篇。

本篇已证并可供后篇引用的机制锚点：

1. events/status 两 virtqueue 的方向语义（3.1 节）。
2. 回调运行于 hardirq，无线程化（3.4 节链条）。
3. wire 事件无时戳字段；eventTime 由 evdev 层 `input_get_timestamp` 补齐（8.2 节链条，03 篇展开）。
4. SYN_REPORT 由 host 产生、guest 驱动透传（8.1 节）。
5. guest 驱动段对报点间隔分布零加工（9.2 节）。
6. probe 全或无装配与七种失败/降级形态（8.4 节）。

## 11. 断言与三态汇总

本篇机制性结论的三态分布（详细断言-证据对照见《过程记录/批01-过程记录.md》，其编号 A1-A24 与下表 A1-A19 一致并多出记录侧补充项 A20-A24）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| A1 | wire 事件结构无 time 字段 | 已证 | virtio_input.h:70-74 |
| A2 | 驱动转交 input_event 仅 type/code/value，无改写 | 已证 | virtio_input.c:44-47 |
| A3 | 驱动全文无时戳操作点 | 已证 | grep 命令输出＋input.c:438-439 签名 |
| A4 | eventTime 由 evdev 层 ktime_get() 补齐 | 已证 | input.c:2040-2047/2055-2062、evdev.c:300 |
| A5 | SYN_REPORT 由 host 产生，guest 驱动无生成点 | 已证 | grep EV_SYN 仅 :70 注释；:42-50 零加工 |
| A6 | 回调运行于 hardirq | 已证 | virtio_pci_common.c:339-340/82-100、virtio_ring.c:2147-2168 |
| A7 | 解锁窗口防自旋死锁 | 已证 | virtio_input.c:43-48、92；input.c:380-381 |
| A8 | MSC_TIMESTAMP 对 MT 设备丢弃 | 已证 | virtio_input.c:80-81 |
| A9 | 事件最早可达时点晚于 input_register_device 完成 | 已证 | virtio_input.c:317-323；input.c:2295-2296/383-384 |
| A10 | 驱动层无节流判据（414 行逐行排除） | 已证 | :12-22/:30/:42-50/:83 等全文；grep 输出 |
| A11 | event0（virtio_input_tp0）与本驱动注册设备的结构性配对 | 已证 | virtio_input.c:247-257/319/390-393；drivers/input 无 virtio 驱动 |
| A12 | probe 七种失败/降级形态及后果 | 已证 | :225-238/241-244/271-273/302-315/311-321/326-336 |
| A13 | buffer 池深 64 为唯一容量约束，非节奏判据 | 已证（机制）；触发出现与否未证 | :19/:210/:212；:24-31 |
| A14 | add_inbuf 返回值未检查 | 已证（代码事实）；正常路径 ENOSPC 不可达为分析推论 | :30 |
| A15 | err_mt_init_slots 路径 ready 不清零但无可达事件路径 | 已证 | :330-333；fill_evt 调用点穷尽 ：323/:382 |
| A16 | host 写 used ring 的节奏/攒批/中断合并策略 | 未证 | host 代码不在基准树 |
| A17 | host 为何不发 UP、如何构造 name 字符串 | 未证/盲区 | 同上 |
| A18 | host 产生事件→guest 中断送达的真实延迟 | 盲区 | wire 无时戳字段，两时刻不可比 |
| A19 | guest 内核路径（input core＋evdev 全量）无节流 | 未证（本篇） | 旁证 input.c:367-419、evdev.c:296-306；02/03 篇裁决 |

（完）
