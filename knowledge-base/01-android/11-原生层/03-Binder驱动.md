# Binder 驱动（内核层）

> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答"Binder 驱动的内核实现：mmap/一次拷贝、四组核心对象、binder_write_read、buffer 分配归还、引用计数与进程退出回收、servicemanager"；Framework 侧 Binder 契约归 [../01-architecture/02-Binder.md](../01-architecture/02-Binder.md)。Q1 与 Q3 已按 Android Common Kernel 源码核对。其他题的证据按各题范围说明。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Binder 驱动怎样用接收映射实现一次拷贝？**

普通内核 Binder 事务的数据由驱动从发送进程的用户地址写入接收进程映射的页面。接收方通过该映射读取数据，省掉内核缓冲区到接收方用户缓冲区的第二次复制。

以 Android Common Kernel 的 android14-6.1 分支为例：

1. **建立接收窗口：**进程打开 Binder 设备后调用 mmap。驱动为该进程建立 Binder 接收虚拟地址区，底层页面在分配事务缓冲区时按需安装。建立映射本身不预填整个窗口的物理页。
2. **从目标窗口分配：**binder_transaction 通过 binder_alloc_new_buf 在目标进程的 binder_alloc 中选取一段空闲地址。A 调 B 的请求占用 B 的窗口，B 回复 A 时反向分配。
3. **写入并交接：**驱动通过 binder_alloc_copy_user_to_buffer 把发送方用户地址上的数据分段写入目标缓冲区，并分别处理对象偏移、Binder 引用和文件描述符。接收线程得到 BR_TRANSACTION 时，数据指针指向本进程映射中的地址。
4. **适用范围：**“一次”描述普通载荷的复制路径。编组、反编组、对象转换以及事务描述符交接仍有开销，驱动也可能多次调用分段复制函数。目标窗口无法分配时，本次事务失败，分配与失败条件见 Q2 和 Q3。

依据：Android Common Kernel android14-6.1，drivers/android/binder.c 的 binder_mmap、binder_transaction、binder_thread_read，以及 binder_alloc.c 的缓冲区分配与复制实现。

**Q2: [learning] 一笔事务的 Binder buffer 如何分配与归还？空间不足时的行为是什么？**

驱动把 `data_size`、`offsets_size`、`extra_buffers_size` 三部分分别做指针大小对齐后求和，从目标进程空闲 buffer 红黑树中做最佳适配分配（大块切分、释放时与相邻空闲块合并）。找不到合适空闲块或异步预算不足时立即返回 `-ENOSPC`——不阻塞等待旧 buffer 释放，也不会借 `BR_SPAWN_LOOPER` 扩线程。接收方处理完成后经 `BC_FREE_BUFFER` 归还。同步调用在 Android 17 中发送回复前就释放请求 buffer。

- 映射属于接收方：A 调用 B，请求占 B 的 `binder_alloc`。B 的回复占 A 的。同步与异步共享同一地址池，异步另有半池记账预算。
- oneway 的 buffer 要等服务端处理完才归还，高频 oneway 会拉长占用时间。
- 发送方 Parcel 的内存与接收方 Binder 空间是两个指标。`TransactionTooLargeException` 是 Java 层按失败上下文推测的异常，不是驱动返回的精确字节上限。
- 排查 `-ENOSPC`/`ENOSPC` 扩展错误时，先看接收进程的并发事务、大事务与异步占用，而不是假设“这一笔超过了 1MiB”。

**Q3: [learning] Binder 接收窗口分配失败时，怎样判断是地址区、异步预算还是物理内存问题？**

先区分地址区分配和物理页准备。地址区不足或超出异步预算时返回 ENOSPC，只有已找到所需地址范围后，驱动才会为仍未装入的页面准备物理内存。

1. **地址区不可用：**接收者没有可用映射时，分配无法完成，例如映射已关闭时可返回 ESRCH。设置映射时内核限制单个窗口的大小，libbinder 通常请求更小的映射，见 [Binder 架构](../01-architecture/02-Binder.md) Q2。
2. **空间或预算用尽：**没有足够的连续空闲块，或者本次 oneway 的大小超过剩余异步预算时返回 ENOSPC。查目标进程的在途事务与异步流量，分辨单笔大事务、并发占用或碎片。
3. **物理页准备失败：**地址范围已经选定，分配页面或安装映射仍可能失败。ENOMEM 等错误应结合内存和映射日志判断。这类失败与地址窗口自身的容量上限分别诊断。

依据：Android Common Kernel android14-6.1，drivers/android/binder_alloc.c 的 binder_alloc_mmap_handler 与 binder_alloc_new_buf。

**Q4: [learning] Binder 驱动的 binder_proc、binder_thread、binder_node、binder_ref 分别代表什么，谁引用谁？**

`binder_proc` 是一个进程在 Binder 侧的全局上下文，`binder_thread` 是该进程内的一个线程，`binder_node` 是服务端 Binder 对象的内核化身，`binder_ref` 是客户端进程对该 `binder_node` 的引用。引用方向是 `binder_proc` 持有本进程 `binder_thread` 的红黑树，客户端 `binder_proc` 经 `binder_ref` 指向服务端 `binder_node`。

这四者的分工解释了 Binder 的全部行为：

- **进程与线程是两层**。`binder_thread` 记录 `todo` 队列（进程级待处理事务）与 `wait` 等待队列（线程级空闲），因此“唤醒某个线程”与“唤醒某个进程”是两种不同操作。
- **node 与 ref 分离**。`binder_node` 全局唯一（同一服务端对象全系统一个 node），`binder_ref` 进程私有（每个客户端进程各有一个 ref，但 `ref->node` 指向同一个 node）。客户端发事务时给的 `handle` 就是 `binder_ref` 在该进程 `ref_by_id` 表中的下标，驱动据 handle 反查 node 与目标 proc。
- **node 上的引用计数决定生死**。`binder_node->refs` 归零且无强引用时，对象销毁并向已收到该 node 的进程回发 `BR_DEAD_OBJ`。

```c
// drivers/android/binder.c，binder_transaction() 的 handle 反查段
if (tr->target.handle) {
    ref = binder_get_ref_olocked(proc, tr->target.handle, true);
    target_node = ref->node;
    target_proc = target_node->proc;
} else {
    target_node = binder_context_mgr_node;   // handle == 0 才是 ServiceManager
    target_proc = target_node->proc;
}
```

边界：`binder_context_mgr_node` 是唯一的全局 node，对应 `servicemanager`，所有 `addService`/`checkService` 都打到它。判断规则：排查“Binder 通了但拿到的对象不对”时，先确认 handle 落在哪个 node 上——handle 非零走对象、handle 为零走 servicemanager，两条路径的权限与失败表现完全不同。

**Q5: [learning] Framework 的一次 ioctl 到底传了什么，为什么用户态要把“待写数据”和“待读数据”塞进同一个结构体？**

塞进同一个结构体 `binder_write_read` 是为了在**一次系统调用**里同时完成“投递事务”和“收割回复”两件事，避免两次陷入内核。驱动按 `write_size` 与 `read_size` 分别决定是否执行写侧与读侧处理，再把实际消耗量回写给用户态。

```c
// bionic/libbinder/IPCThreadState.cpp
binder_write_read bwr;
const size_t outAvail = (!doReceive || needRead) ? mOut.dataSize() : 0;
bwr.write_size = outAvail;
bwr.write_buffer = (uintptr_t)mOut.data();
if (doReceive && needRead) {
    bwr.read_size = mIn.dataCapacity();
    bwr.read_buffer = (uintptr_t)mIn.data();
}
bwr.write_consumed = 0;
bwr.read_consumed = 0;
do {
    if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0)
        err = NO_ERROR;
    else
        err = -errno;
} while (err == -EINTR);
```

对应的驱动侧按两侧尺寸分派：

```c
// drivers/android/binder.c
static int binder_ioctl_write_read(struct file *filp,
        unsigned int cmd, unsigned long arg, struct binder_thread *thread)
{
    if (copy_from_user(&bwr, ubuf, sizeof(bwr))) { ret = -EFAULT; goto out; }
    if (bwr.write_size > 0)
        ret = binder_thread_write(proc, thread, bwr.write_buffer,
                bwr.write_size, &bwr.write_consumed);
    if (bwr.read_size > 0)
        ret = binder_thread_read(proc, thread, bwr.read_buffer,
                bwr.read_size, &bwr.read_consumed,
                filp->f_flags & O_NONBLOCK);
    if (copy_to_user(ubuf, &bwr, sizeof(bwr))) { ret = -EFAULT; }
    return ret;
}
```

`write_buffer` 里的字节不是裸数据，而是一串命令字：写入侧命令以 `BC_`（binder command）开头，如 `BC_TRANSACTION`、`BC_REPLY`。读出侧以 `BR_`（binder reply）开头，如 `BR_TRANSACTION`、`BR_REPLY`。`binder_thread_write()` 的做法是循环从缓冲区取出一个 `cmd`，按命令分派，再推进 `consumed`，直到缓冲区耗尽——所以写入缓冲区的格式是“命令 + 可变长参数”的自描述流，不是结构体数组。判断规则：抓 `BINDER_WRITE_READ` 的 trace 或日志时，`write_size`/`read_size` 表示待处理量、`write_consumed`/`read_consumed` 表示实际处理量，二者不等说明缓冲区里有未解析完的残留命令（常见于版本不匹配的驱动）。

**Q6: [learning] Binder 的引用计数与生命周期是怎样的，为什么会出现“对象已死但句柄还在”？**

`binder_node` 上有引用计数（`node->refs`）与强引用数（`node->strong_refs`）：驱动侧每建立一个 `binder_ref` 记一次 `refs`，业务侧每 acquire 一次记一次 `strong_refs`。`strong_refs` 归零会驱动 `releaseBinder` 类流程。`refs` 归零且无强引用时 node 销毁，向所有曾收到它的进程回发 `BR_DEAD_OBJ`。

客户端持有的 `binder_ref` 只在收到 `BR_DEAD_OBJ` 后才失效，而 `binder_death`（服务端进程死亡）与对象失效是两条路：前者是 `binder_node` 所在的进程没了，后者是对象被 `unlink` 但进程还在。两者都会让对端拿到 `BINDER_ERROR_DEAD_OBJ`，但对业务的处置不同——前者通常需要重新查找服务，后者是调用时序撞上了对象销毁。Java 侧的 `RemoteException` 就是这个错误码的映射。判断规则：把 `RemoteException` 一律当“服务没注册”处理是常见误判。先看死亡通知是 `linkToDeath` 回调触发（进程死了）还是调用当场抛 `DEAD_OBJ`（对象没了），再决定是重试、重查服务还是修正调用时序。

**Q7: [learning] 进程退出时 Binder 的资源如何回收，binder_flush 与 binder_release 各负责什么？**

`binder_release` 负责进程最后一次关闭 `/dev/binder` fd 时的整体清理。`binder_flush` 负责单个 fd 的引用归零时的清理。进程异常退出时，内核按 `files_struct` 逐个 `flush` 打开的 binder fd，无需用户态参与——这是 Binder 在客户端崩溃场景下不泄漏的关键。

```c
// drivers/android/binder.c，文件操作表
static const struct file_operations binder_fops = {
    .owner          = THIS_MODULE,
    .poll           = binder_poll,
    .unlocked_ioctl = binder_ioctl,
    .compat_ioctl   = binder_ioctl,
    .mmap           = binder_mmap,
    .open           = binder_open,
    .flush          = binder_flush,
    .release        = binder_release,
};
```

`binder_open` 在此处创建并挂上 `binder_proc`：

```c
// drivers/android/binder.c
static int binder_open(struct inode *inode, struct file *filp)
{
    struct binder_proc *proc;
    proc = kzalloc(sizeof(*proc), GFP_KERNEL);
    filp->private_data = proc;
    /* 进程所有 binder_thread 以红黑树挂在 proc->threads 下 */
}
```

边界：线程退出不销毁 `binder_thread` 上的待处理事务语义——内核线程池与工作线程要区分，线程池线程退出时其 work 会被重新派发或取消，直接 `clone` 出的线程退出则其 `todo` 队列随线程一起清理。判断规则：客户端进程被 kill 后服务端仍持有服务端对象引用，依赖的是“进程死亡通知 + 引用计数归零”这条路径，不依赖服务端的主动解注册。因此服务端必须对 `DeadObjectException` 幂等，不能假设客户端会正常调用 `unbind`。

**Q8: [learning] servicemanager（ServiceManager）本身是怎么启动和承载的，它和普通服务有什么不同？**

`servicemanager` 是一个在 init 阶段启动的独立原生进程，持有全局唯一的 `binder_context_mgr_node`。普通服务通过 `addService` 把名字与 `binder_node` 的 handle 注册到它。客户端 `checkService` 时 handle 为零，事务就打到这个 node 上。

它的特殊之处在于**它是所有服务的寻址入口**：没有它，客户端拿不到任何远端 handle。启动上由 `init.rc` 的 `service servicemanager` 段拉起，进程内 `ServiceManager` 通过 `binder_driver` 的 `ioctl(BINDER_SET_CONTEXT_MGR)` 把自己标记为 context manager：

```c
// drivers/android/binder.c，binder_ioctl_set_ctx_mgr 要点
/* 校验调用方；创建 binder_node 并保存到 context->binder_context_mgr_node */
```

`binder_context_mgr_node` 没有对应的真实进程实体 node，驱动为它维护一份全局引用，任何进程拿到的引用都会被计入它的计数。边界：Android 8 之后 Google 提供了可替换的 servicer 实现（`servicemanager` / `native service manager` / `droidFW`），不是所有设备都用 AOSP 的 C++ `ServiceManager`。判断规则：设备上“服务查不到”时先确认 `servicemanager` 进程活着、且它与客户端的 Binder 驱动版本匹配——servicer 被替换后，客户端必须使用与之匹配的获取接口，直接按 AOSP 语义调 `checkService` 可能返回 null 而服务其实已注册。

**Q9: [learning] 从“一次拷贝”的角度看，Parcel 里传大对象时的拷贝次数会怎么变？**

取决于对象是否被序列化成 Parcel 数据块。若超过 Binder 缓冲区上限（单次约 1 MB 量级），Framework 侧会走多次传输或改用共享内存 fd，两条路径的拷贝次数完全不同。

普通小对象：调用方对象序列化进 `Parcel` → 用户态缓冲区 → 一次 `copy_from_user` 到接收者 mmap 区 → 接收方直接从自己地址空间读。**1 次拷贝。**

大对象：Java 侧不会把大块字节数组内联进事务，而是传递 `ParcelFileDescriptor`（内存映射文件），此时传递的是 fd 本身（`BINDER_TYPE_FD`），数据不走 Binder 缓冲区。**0 次数据拷贝**，代价是一次 fd 传递与双方各自 `mmap`。判断规则：Binder 传大数据的正确姿势是传 fd 而不是传字节——传字节会撞 `TransactionTooLargeException`，并且即使没撞也白白多一次拷贝。这正是 `SharedMemory`、`Surface`、`GraphicBuffer` 都在传 fd 的原因。

**Q10: [learning] 诊断 Binder 问题时，官方工具与内核侧观测点各能回答什么问题？**

内核侧观测点回答“事务在哪个阶段停住”，用户态工具回答“调用方是谁”。两者要配合用——只看其中一个会得出错误归因。

内核侧可看：`/sys/kernel/debug/binder`（`binder_debugfs_dir_entry_root` 随 `binder_init` 创建）下的 `proc/<pid>`、`thread/<tid>`、`stats`，以及 `binder_trace.h` 定义的 trace 点（`trace_binder_transaction` 系列，含 `trace_binder_transaction_fd`）。它们能回答：目标 node 是谁、缓冲区落在哪个进程、fd 是否被正确翻译、事务在哪一环被拒绝。用户态侧回答“这个调用是谁发起的”——`dumpsys` 各服务的 binder 接口统计、`BinderProxy` 关联关系。判断规则：遇到“调用不返回”类问题，先用 `binder_watchdog`/trace 确认事务是否进了目标进程的 `todo` 队列——进了说明是目标侧处理慢，没进说明卡在发送方或驱动。这一步能把搜索范围从整个系统缩到两个进程。

**Q11: [learning] Binder 驱动收到事务后，怎样把工作交给服务端线程？**

驱动负责把事务工作排入目标线程或目标进程的队列并唤醒等待者。用户态 libbinder 读出事务后，再调用 Stub 分派服务方法。驱动不直接执行服务代码。

1. **定位目标**：驱动根据 Binder 引用找到目标 node、进程及可处理事务的线程，并为目标进程分配接收缓冲区。
2. **安排工作**：事务进入目标线程队列，或在没有合适线程时进入进程级队列。驱动唤醒等待中的服务线程。线程数未达配置上限且需要扩容时，驱动可向用户态请求创建 Binder 线程。
3. **用户态分派**：线程通过读侧 `BINDER_WRITE_READ` 取出 `BR_TRANSACTION`。libbinder 解码事务，再由 AIDL Stub 按事务码调用服务实现。

同步与 `oneway` 事务在排队、等待回复和缓冲区释放上的行为不同。具体线程选择和唤醒细节随目标内核实现变化。
