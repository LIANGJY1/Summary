# Binder 驱动（内核层）

> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答"Binder 驱动的内核实现：mmap/一次拷贝、四组核心对象、binder_write_read、buffer 分配归还、引用计数与进程退出回收、servicemanager"；Framework 侧 Binder 契约归 [../01-architecture/03-binder.md](../01-architecture/03-binder.md)。源码引用以官方文档与社区分析为准，未逐条核对本地 AOSP。Q 序列即结构，供 atlas 同源直读。

**Q1: Framework 侧说的 Binder"一次拷贝"，在内核里是靠哪两个动作共同成立的？**

靠 `mmap` 预映射与"内核在接收者进程的映射区内分配缓冲区"两件事共同成立。发送方只做一次 `copy_from_user`，数据落点既是内核缓冲、又是接收者已映射的地址，因此接收方无需再拷贝即可直接读。

`binder_mmap()` 在 Server 进程打开 `/dev/binder` 后被调用，它在目标进程的虚拟地址空间里映射一批内核分配的物理页：

```c
// drivers/android/binder.c
static int binder_mmap(struct file *filp, struct vm_area_struct *vma)
{
    struct binder_proc *proc = filp->private_data;
    if ((vma->vm_end - vma->vm_start) > SZ_4M)
        vma->vm_end = vma->vm_start + SZ_4M;
    alloc->buffer = (void *)vma->vm_start;
    alloc->buffer_size = vma->vm_end - vma->vm_start;
    for (i = 0; i < alloc->buffer_size / PAGE_SIZE; i++) {
        page = alloc_page(GFP_KERNEL | __GFP_HIGHMEM | __GFP_ZERO);
        vm_insert_page(vma, (unsigned long)alloc->buffer + i * PAGE_SIZE, page);
    }
    return 0;
}
```

发送时的 `binder_transaction()` 调 `binder_alloc_buf(&target_proc->alloc, ...)`——注意传入的是**目标进程**的 `alloc`，返回的 `t->buffer->data` 天然位于接收者的 mmap 区，再 `copy_from_user` 一次把数据填进去：

```c
// drivers/android/binder.c，binder_transaction() 内的数据拷贝段
t->buffer = binder_alloc_buf(&target_proc->alloc, tr->data_size,
        tr->offsets_size, extra_buffers_size,
        !reply && (t->flags & TF_ONE_WAY));
copy_from_user(t->buffer->data,
        (const void __user *)(uintptr_t)tr->data.ptr.buffer,
        tr->data_size);
```

对比传统管道（socketpair）的"发送方→内核缓冲→接收方"两段拷贝，Binder 省掉的正是第二段。判断规则：任何"一次拷贝"的说法都有前提，脱离"接收者预先 mmap 了内核页"这一前提去解释就会失真；反过来，接收方没有 mmap 或映射区耗尽时，缓冲区只能从内核通用池分配，此时接收侧仍需一次 `copy_to_user`，退回两段拷贝。

**Q2: 一笔事务的 Binder buffer 如何分配与归还？空间不足时的行为是什么？**

驱动把 `data_size`、`offsets_size`、`extra_buffers_size` 三部分分别做指针大小对齐后求和，从目标进程空闲 buffer 红黑树中做最佳适配分配（大块切分、释放时与相邻空闲块合并）；找不到合适空闲块或异步预算不足时立即返回 `-ENOSPC`——不阻塞等待旧 buffer 释放，也不会借 `BR_SPAWN_LOOPER` 扩线程。接收方处理完成后经 `BC_FREE_BUFFER` 归还；同步调用在 Android 17 中发送回复前就释放请求 buffer。

- 映射属于接收方：A 调用 B，请求占 B 的 `binder_alloc`；B 的回复占 A 的。同步与异步共享同一地址池，异步另有半池记账预算。
- oneway 的 buffer 要等服务端处理完才归还，高频 oneway 会拉长占用时间。
- 发送方 Parcel 的内存与接收方 Binder 空间是两个指标；`TransactionTooLargeException` 是 Java 层按失败上下文推测的异常，不是驱动返回的精确字节上限。
- 排查 `-ENOSPC`/`ENOSPC` 扩展错误时，先看接收进程的并发事务、大事务与异步占用，而不是假设"这一笔超过了 1MiB"。

**Q3: `drivers/android/binder.c` 里 `binder_alloc_buf` 为什么要在接收者的 `alloc` 里找缓冲区，找不到会怎样？**

因为缓冲区必须落在接收者已 mmap 的区域里，才能省掉接收侧拷贝。`binder_alloc_buf` 在 `alloc->free_buffers` 红黑树里找足够大的空闲块，失败时尝试扩展 mmap 区域；异步事务还额外遵守 `free_async_space` 上限（映射区的一半），避免异步请求把同步事务的可用空间吃光。

```c
// drivers/android/binder_alloc.c
struct binder_buffer *binder_alloc_buf(struct binder_alloc *alloc,
        binder_size_t data_size, binder_size_t offsets_size,
        binder_size_t extra_buffers_size, int is_async)
{
    /* 遍历 alloc->free_buffers 红黑树找合适大小的空闲缓冲区，
       或扩展 mmap 区域；返回的 buffer->data 指向目标进程 mmap 区内地址 */
}
```

```c
// drivers/android/binder_alloc.c，binder_alloc_mmap_handler() 建立的不变式
alloc->free_async_space = alloc->buffer_size / 2;
barrier();
alloc->vma = vma;
atomic_inc(&alloc->vma_vm_mm->mm_count);
```

`binder_mmap` 还有两个硬约束：单次映射上限 `SZ_4M`，且只有进程主线程能调用（`proc->tsk != current->group_leader` 直接返回 `-EINVAL`），同时置 `VM_DONTCOPY` 并清 `VM_MAYWRITE` 让映射区不可写不可被 fork 复制。判断规则：`TransactionTooLargeException`、缓冲区耗尽、`ENOMEM` 这几类失败要区分——映射区扩展失败是内存压力，异步空间不足说明同步事务在抢占资源，前者看整机内存，后者看调用模式（是否大量并发 oneway 调用）。

**Q4: Binder 驱动的 `binder_proc`、`binder_thread`、`binder_node`、`binder_ref` 分别代表什么，谁引用谁？**

`binder_proc` 是一个进程在 Binder 侧的全局上下文，`binder_thread` 是该进程内的一个线程，`binder_node` 是服务端 Binder 对象的内核化身，`binder_ref` 是客户端进程对该 `binder_node` 的引用。引用方向是 `binder_proc` 持有本进程 `binder_thread` 的红黑树，客户端 `binder_proc` 经 `binder_ref` 指向服务端 `binder_node`。

这四者的分工解释了 Binder 的全部行为：

- **进程与线程是两层**。`binder_thread` 记录 `todo` 队列（进程级待处理事务）与 `wait` 等待队列（线程级空闲），因此"唤醒某个线程"与"唤醒某个进程"是两种不同操作。
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

边界：`binder_context_mgr_node` 是唯一的全局 node，对应 `servicemanager`，所有 `addService`/`checkService` 都打到它。判断规则：排查"Binder 通了但拿到的对象不对"时，先确认 handle 落在哪个 node 上——handle 非零走对象、handle 为零走 servicemanager，两条路径的权限与失败表现完全不同。

**Q5: Framework 的一次 `ioctl` 到底传了什么，为什么用户态要把"待写数据"和"待读数据"塞进同一个结构体？**

塞进同一个结构体 `binder_write_read` 是为了在**一次系统调用**里同时完成"投递事务"和"收割回复"两件事，避免两次陷入内核。驱动按 `write_size` 与 `read_size` 分别决定是否执行写侧与读侧处理，再把实际消耗量回写给用户态。

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

`write_buffer` 里的字节不是裸数据，而是一串命令字：写入侧命令以 `BC_`（binder command）开头，如 `BC_TRANSACTION`、`BC_REPLY`；读出侧以 `BR_`（binder reply）开头，如 `BR_TRANSACTION`、`BR_REPLY`。`binder_thread_write()` 的做法是循环从缓冲区取出一个 `cmd`，按命令分派，再推进 `consumed`，直到缓冲区耗尽——所以写入缓冲区的格式是"命令 + 可变长参数"的自描述流，不是结构体数组。判断规则：抓 `BINDER_WRITE_READ` 的 trace 或日志时，`write_size`/`read_size` 表示待处理量、`write_consumed`/`read_consumed` 表示实际处理量，二者不等说明缓冲区里有未解析完的残留命令（常见于版本不匹配的驱动）。

**Q6: Binder 的引用计数与生命周期是怎样的，为什么会出现"对象已死但句柄还在"？**

`binder_node` 上有引用计数（`node->refs`）与强引用数（`node->strong_refs`）：驱动侧每建立一个 `binder_ref` 记一次 `refs`，业务侧每 acquire 一次记一次 `strong_refs`。`strong_refs` 归零会驱动 `releaseBinder` 类流程；`refs` 归零且无强引用时 node 销毁，向所有曾收到它的进程回发 `BR_DEAD_OBJ`。

客户端持有的 `binder_ref` 只在收到 `BR_DEAD_OBJ` 后才失效，而 `binder_death`（服务端进程死亡）与对象失效是两条路：前者是 `binder_node` 所在的进程没了，后者是对象被 `unlink` 但进程还在。两者都会让对端拿到 `BINDER_ERROR_DEAD_OBJ`，但对业务的处置不同——前者通常需要重新查找服务，后者是调用时序撞上了对象销毁。Java 侧的 `RemoteException` 就是这个错误码的映射。判断规则：把 `RemoteException` 一律当"服务没注册"处理是常见误判；先看死亡通知是 `linkToDeath` 回调触发（进程死了）还是调用当场抛 `DEAD_OBJ`（对象没了），再决定是重试、重查服务还是修正调用时序。

**Q7: 进程退出时 Binder 的资源如何回收，`binder_flush` 与 `binder_release` 各负责什么？**

`binder_release` 负责进程最后一次关闭 `/dev/binder` fd 时的整体清理；`binder_flush` 负责单个 fd 的引用归零时的清理。进程异常退出时，内核按 `files_struct` 逐个 `flush` 打开的 binder fd，无需用户态参与——这是 Binder 在客户端崩溃场景下不泄漏的关键。

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

边界：线程退出不销毁 `binder_thread` 上的待处理事务语义——内核线程池与工作线程要区分，线程池线程退出时其 work 会被重新派发或取消，直接 `clone` 出的线程退出则其 `todo` 队列随线程一起清理。判断规则：客户端进程被 kill 后服务端仍持有服务端对象引用，依赖的是"进程死亡通知 + 引用计数归零"这条路径，不依赖服务端的主动解注册；因此服务端必须对 `DeadObjectException` 幂等，不能假设客户端会正常调用 `unbind`。

**Q8: `servicemanager`（`ServiceManager`）本身是怎么启动和承载的，它和普通服务有什么不同？**

`servicemanager` 是一个在 init 阶段启动的独立原生进程，持有全局唯一的 `binder_context_mgr_node`。普通服务通过 `addService` 把名字与 `binder_node` 的 handle 注册到它；客户端 `checkService` 时 handle 为零，事务就打到这个 node 上。

它的特殊之处在于**它是所有服务的寻址入口**：没有它，客户端拿不到任何远端 handle。启动上由 `init.rc` 的 `service servicemanager` 段拉起，进程内 `ServiceManager` 通过 `binder_driver` 的 `ioctl(BINDER_SET_CONTEXT_MGR)` 把自己标记为 context manager：

```c
// drivers/android/binder.c，binder_ioctl_set_ctx_mgr 要点
/* 校验调用方；创建 binder_node 并保存到 context->binder_context_mgr_node */
```

`binder_context_mgr_node` 没有对应的真实进程实体 node，驱动为它维护一份全局引用，任何进程拿到的引用都会被计入它的计数。边界：Android 8 之后 Google 提供了可替换的 servicer 实现（`servicemanager` / `native service manager` / `droidFW`），不是所有设备都用 AOSP 的 C++ `ServiceManager`。判断规则：设备上"服务查不到"时先确认 `servicemanager` 进程活着、且它与客户端的 Binder 驱动版本匹配——servicer 被替换后，客户端必须使用与之匹配的获取接口，直接按 AOSP 语义调 `checkService` 可能返回 null 而服务其实已注册。

**Q9: 从"一次拷贝"的角度看，Parcel 里传大对象时的拷贝次数会怎么变？**

取决于对象是否被序列化成 Parcel 数据块。若超过 Binder 缓冲区上限（单次约 1 MB 量级），Framework 侧会走多次传输或改用共享内存 fd，两条路径的拷贝次数完全不同。

普通小对象：调用方对象序列化进 `Parcel` → 用户态缓冲区 → 一次 `copy_from_user` 到接收者 mmap 区 → 接收方直接从自己地址空间读。**1 次拷贝。**

大对象：Java 侧不会把大块字节数组内联进事务，而是传递 `ParcelFileDescriptor`（内存映射文件），此时传递的是 fd 本身（`BINDER_TYPE_FD`），数据不走 Binder 缓冲区。**0 次数据拷贝**，代价是一次 fd 传递与双方各自 `mmap`。判断规则：Binder 传大数据的正确姿势是传 fd 而不是传字节——传字节会撞 `TransactionTooLargeException`，并且即使没撞也白白多一次拷贝；这正是 `SharedMemory`、`Surface`、`GraphicBuffer` 都在传 fd 的原因。

**Q10: 诊断 Binder 问题时，官方工具与内核侧观测点各能回答什么问题？**

内核侧观测点回答"事务在哪个阶段停住"，用户态工具回答"调用方是谁"。两者要配合用——只看其中一个会得出错误归因。

内核侧可看：`/sys/kernel/debug/binder`（`binder_debugfs_dir_entry_root` 随 `binder_init` 创建）下的 `proc/<pid>`、`thread/<tid>`、`stats`，以及 `binder_trace.h` 定义的 trace 点（`trace_binder_transaction` 系列，含 `trace_binder_transaction_fd`）。它们能回答：目标 node 是谁、缓冲区落在哪个进程、fd 是否被正确翻译、事务在哪一环被拒绝。用户态侧回答"这个调用是谁发起的"——`dumpsys` 各服务的 binder 接口统计、`BinderProxy` 关联关系。判断规则：遇到"调用不返回"类问题，先用 `binder_watchdog`/trace 确认事务是否进了目标进程的 `todo` 队列——进了说明是目标侧处理慢，没进说明卡在发送方或驱动；这一步能把搜索范围从整个系统缩到两个进程。

**Q11: Binder 驱动收到事务后，怎样把工作交给服务端线程？**

驱动负责把事务工作排入目标线程或目标进程的队列并唤醒等待者。用户态 libbinder 读出事务后，再调用 Stub 分派服务方法。驱动不直接执行服务代码。

1. **定位目标**：驱动根据 Binder 引用找到目标 node、进程及可处理事务的线程，并为目标进程分配接收缓冲区。
2. **安排工作**：事务进入目标线程队列，或在没有合适线程时进入进程级队列。驱动唤醒等待中的服务线程。线程数未达配置上限且需要扩容时，驱动可向用户态请求创建 Binder 线程。
3. **用户态分派**：线程通过读侧 `BINDER_WRITE_READ` 取出 `BR_TRANSACTION`。libbinder 解码事务，再由 AIDL Stub 按事务码调用服务实现。

同步与 `oneway` 事务在排队、等待回复和缓冲区释放上的行为不同。具体线程选择和唤醒细节随目标内核实现变化。
