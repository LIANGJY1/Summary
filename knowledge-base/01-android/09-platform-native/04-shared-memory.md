# 共享内存：ashmem、ION 与 DMA-BUF

> 学习资料（文章模式沉淀，证据等级：二手）。边界：本文回答"ashmem pin/unpin、fd 跨进程传递、ION 到 DMA-BUF heap 迁移与回收路径分工"；图形缓冲在渲染链路的使用归 04-graphics。源码引用以官方文档与社区分析为准。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] ashmem 的 pin/unpin 和普通共享内存有什么本质区别，"unpin 不释放"为什么反而是它的核心价值？**

区别在于 ashmem 把"是否可被系统回收"做成了**独立于映射的状态位**。unpin 只改状态标记、不动已建立的映射；系统真正缺内存时才按 LRU 回收被 unpin 的物理页。因此"unpin 之后数据还在"是设计承诺，不是没释放干净。

ashmem 建立在 Linux 的 tmpfs 共享内存之上，`mmap` 第一次触发缺页时才真正分配物理页：

```c
// drivers/staging/android/ashmem.c 的 mmap 路径（要点）
// 若 asma->file 为空，说明是首个访问者，调 shmem_file_setup() 在 tmpfs 建临时文件
// 挂到 vma->vm_ops->fault 回调：缺页时先查页缓冲区，再查换出页，都没有才真正分配
```

它向内存管理系统注册回收器，靠 LRU 决定回收谁：

```c
// drivers/staging/android/ashmem.c，初始化路径
ashmem_init() {
    ashma_area_cachep = kmem_cache_create("ashmem_area", ...);
    misc_register(&ashmem_miscdev);          // 注册为 /dev/ashmem
    register_shrinker(&ashmem_shrinker);     // 把回收函数交给内存管理
}
```

`ashmem_shrinker` 被调用时，只回收处于 unpin 状态的区域。`/proc/<pid>/maps` 里所有 ashmem 区域都显示成 `/dev/ashmem/<name> (deleted)`——括号里的 `deleted` 就是"目录项已删、inode 仍存活"的表现，不代表内存异常；最后一个 fd 关闭时 inode 与物理内存才真正回收。

用户态接口是五个系统调用（`system/core/include/cutils/ashmem.h`）：创建区域 `ashmem_create_region`、设置保护位 `ashmem_set_prot_region`、锁定 `ashmem_pin_region`、解锁 `ashmem_unpin_region`、取大小 `ashmem_get_size_region`。边界：app 侧的 `MemoryFile` 已基本不可用，官方路径是 `android.os.SharedMemory`。判断规则：读到 `/dev/ashmem/...(deleted)` 不必当成泄漏；反过来，pin 住的 ashmem 区域在低内存时也不会被回收，是低内存杀进程时"看起来还有很多可用内存"的常见成因。

**Q2: [learning] ashmem 内存是怎么跨进程传递的，为什么必须借道 Binder？**

因为 ashmem 区域的句柄是**文件描述符**，而文件描述符不是普通数据，只能由 Binder 的 `BINDER_TYPE_FD` 路径在目标进程重新安装一个 fd。所以共享内存与 Binder 强耦合，脱离 Binder 无法单独传递。

驱动在处理 `BINDER_TYPE_FD` 时，在目标进程里申请一个未占用的 fd，并把同一个 `struct file` 安装进去，然后把 `fp->handle` 改写成这个新 fd：

```c
// drivers/android/binder.c，binder_transaction() 的 flat_binder_object 处理
case BINDER_TYPE_FD: {
    file = fget(fp->handle);
    target_fd = task_get_unused_fd_flags(target_proc, O_CLOEXEC);
    task_fd_install(target_proc, target_fd, file);
    fp->binder = 0;
    fp->handle = target_fd;   // 改写后回传用户态，客户端拿到的是自己进程里的 fd
}
break;
```

`O_CLOEXEC` 保证这个 fd 不会被子进程继承。同一段代码也处理 `BINDER_TYPE_BINDER`（服务端把引用传给自己）和 `BINDER_TYPE_HANDLE`（客户端把 handle 传给对端）——三者都是"指针/句柄跨进程要重新落地"的同类问题。判断规则：把 fd 通过 Bundle 塞给对端时，接收方拿到的**不是同一个 fd 号**而是新分配的同号 fd；因此判断"两个进程是否共享同一块内存"的正确方式是看底层 inode 是否相同，而不是比较 fd 数值。

**Q3: [learning] Android 为什么在传统共享内存之外还需要 ashmem 这类机制，普通 `memfd_create` 不好用吗？**

`memfd_create` 提供共享内存但不提供**与内存管理系统的协作接口**：它无法被 `register_shrinker` 按 LRU 回收，也无法按区域 pin/unpin。ashmem 的价值就是补上这两点——让图形缓冲这类"平时要留、紧张时可回收、回收后应能恢复"的内存能被系统统一调度。

具体地，`memfd` 的释放条件只有"最后一个 fd 关闭"，没有中间态；ashaem 提供了"pin 住不被回收 / unpin 允许回收但映射不变"这个三态模型，配合 `register_shrinker` 让 LRU 决定回收谁。这就是它被称为"带回收的共享内存"的原因。边界：ashmem 与 ION 是两代方案并存关系而非替代关系——ashmem 管简单共享与文件型映射，图形缓冲这类需要 cache 与 DMA 语义协调的走 ION/DMA-BUF。判断规则：判断某块内存该用哪个机制，看它是否需要"随内存压力被系统回收再恢复"——需要就属于 ashmem/ION 这类，不需要的普通匿名共享就够了。

**Q4: [learning] 内存回收路径上，ashmem、ION 与 cgroup 各自负责哪一段？**

三段各管一件事：cgroup 管"谁的额度"，页回收算法管"哪些物理页可以搬走或丢弃"，ashmem/ION 管"这块内存声明了什么使用状态"。驱动层只向回收系统贡献信息与回调，不自己决定回收谁。

cgroup 负责额度与归因——`memcg` 记录每个进程组的内存占用与上限，低内存时按用量挑选牺牲者，这是"为什么系统杀了这个应用"的答案来源。kswapd 负责按水位把冷页换出，把可分配内存维持在水位线之上。ashmem 通过 `register_shrinker` 注册的回调把 unpin 的区域交出去供回收；ION 通过 `dma_buf_ops.release` 与 page pool 参与其中。判断规则：低内存问题的归因要分清三问：额度够不够（cgroup / `lmkd` 配置）、有没有可换出的冷页（kswapd / ZRAM）、有内存被声明"不能动"吗（pin 住的 ashmem、映射中的 DMA-BUF）。三问的答案分别对应完全不同的处置手段，混在一起就会出现"加了 ZRAM 还是被杀"这类无效优化。

**Q5: [learning] ION 是什么，它为什么被 DMA-BUF heap 取代？**

ION 是三星为解决 Display 与 Camera 共享内存而实现的一套自定义分配器内核框架，后合入 Linux 3.3 主线；它建立在 DMA-BUF 之上，负责把物理内存页组织成可导出为 `dma_buf` 的缓冲区。取代它的动因有三条，都出自 Android 12 的 GKI 2.0：

- **安全性**：每个 DMA-BUF heap 是独立的字符设备（`/dev/dma_heap/<heap_name>`），可以用 sepolicy 单独控制访问权限；ION 所有堆共用一个 `/dev/ion`，从任一堆分配只需访问这一个节点，无法按堆隔离。
- **ABI 稳定性**：DMA-BUF heap 的 IOCTL 接口在上游 Linux 内核维护，ABI 稳定；ION 允许自定义堆 ID 与堆专用标志，各设备实现行为不一致。
- **标准化**：DMA-BUF heap 提供了明确且统一的 UAPI，通用测试框架可以对任意设备做同一套验证。

```text
ION 堆                              DMA-BUF 堆
所有分配都经 /dev/ion               每堆是 /dev/dma_heap/<heap_name> 独立字符设备
支持堆专用标志                       不支持；每个变体注册成独立堆
需指定堆 ID/掩码 + 标志              用堆名分配
```

API 对应关系是逐项可替换的：注册堆用 `dma_heap_add()` 取代 `ion_device_add_heap()`；内核侧分配用 `dma_heap_buffer_alloc(heap, len, fd_flags, heap_flags)` 取代 `ion_alloc(len, heap_id_mask, flags)`；用户态用 `libdmabufheap` 的 `BufferAllocator::Alloc("system", size)` 取代 `ion_alloc_fd(ionfd, size, 0, ION_HEAP_SYSTEM, ION_FLAG_CACHED, &fd)`。判断规则：判断一块图形内存的分配路径属于哪一代，看它 ioctl 的是 `/dev/ion` 还是 `/dev/dma_heap/*`；排查"权限被拒"时，DMA-BUF 时代的排查方向是 sepolicy 里对应堆的 allow 规则，ION 时代则只能查 `/dev/ion` 一个节点。

**Q6: [learning] ION 分配一块 buffer 的内核流程大致是怎样的？**

`ion_buffer_create()` 分配 `ion_buffer`、调用堆的 `allocate` 与 `map_dma`，再遍历 `sg_table` 里的散列表、建立用户映射，核心三步是"堆分配 → 映射成 sg_table → 供用户态 fault 时映射到 vma"。

```c
// drivers/staging/android/ion.c
static struct ion_buffer *ion_buffer_create(struct ion_heap *heap,
        struct ion_device *dev, unsigned long len,
        unsigned long align, unsigned long flags)
{
    buffer = kzalloc(sizeof(struct ion_buffer), GFP_KERNEL);
    buffer->heap = heap;
    buffer->flags = flags;
    kref_init(&buffer->ref);
    ret = heap->ops->allocate(heap, buffer, len, align, flags);
    buffer->dev = dev;
    buffer->size = len;
    table = heap->ops->map_dma(heap, buffer);
    /* 后续 ion_buffer_fault_user_mappings 处理用户侧映射策略 */
}
```

两个容易被忽略的分支值得单独记。其一是**页池与 order**：系统堆会按 order 建多个页池（原实现 order 为 0/4/8），大块请求从高 order 池取，机制类似伙伴系统。其二是**cache 属性与 DMA 一致性**：CMA 类堆若请求带 `ION_FLAG_CACHED` 直接返回 `-EINVAL`，因为 CMA 走 `dma_alloc_coherent()` 拿到的是强制同步地址、无 DMA 缓存，再叠加 cache 会破坏一致性；而普通堆默认带 DMA 标志，关闭 cache 反而改为使用页池自身的 cache。

用户态导出走 `ION_IOC_MAP` → `ion_share_dma_buf_fd()` → `dma_buf_export()`，把 `dma_buf_ops`（`map_dma_buf`、`mmap`、`begin_cpu_access`/`end_cpu_access` 等）挂到 `dma_buf` 上，再用 `anon_inode_getfile()` 给它造一个 `file`，最后为该 `file` 分配 fd。判断规则：`begin_cpu_access`/`end_cpu_access` 这对回调的存在说明"CPU 访问与设备 DMA 访问需要显式切换缓存"，在自研图形栈里若漏实现它们，症状是偶发的花屏/数据损坏而不是崩溃。

**Q7: [learning] `dma_buf` 的 exporter / importer 两个角色分别是谁，谁在什么时候切换？**

exporter 是生产 `dma_buf` 的一方（分配并导出缓冲区），importer 是消费图形元的一方。ION 内部同时扮演两者：对上通过 `dma_buf_export` 表现为 exporter，对下作为通用 DMA 缓冲的消费者表现为 importer。

```c
// drivers/staging/android/ion.c，ION 作为 dma_buf 的 exporter
static struct dma_buf_ops dma_buf_ops = {
    .map_dma_buf = ion_map_dma_buf,
    .unmap_dma_buf = ion_unmap_dma_buf,
    .mmap = ion_mmap,
    .release = ion_dma_buf_release,
    .begin_cpu_access = ion_dma_buf_begin_cpu_access,
    .end_cpu_access = ion_dma_buf_end_cpu_access,
    .kmap_atomic = ion_dma_buf_kmap,
    .kunmap_atomic = ion_dma_buf_kunmap,
    .kmap = ion_dma_buf_kmap,
    .kunmap = ion_dma_buf_kunmap,
};
```

同一份物理内存被两个进程各自 mmap 时，进程 A 分配并导出、进程 B 通过 fd 拿到同一个 `dma_buf` 并 mmap，两边走的都是 `dma_buf_ops` 里的同一个 `mmap` 实现——所以"共享"成立的前提是 `dma_buf` 与其 `file` 在两个进程的 `fget` 路径下拿到的是同一个 `struct file`，而不是各建一份。`dma_buf_fd` 做的事就是"为 `dma_buf->file` 在当前进程分配一个可用 fd"，这一步保证 handle 在每个进程里各自有效。判断规则：把图形内存跨进程共享时，共享的是 `dma_buf`（一次分配）而不是两次分配；若两侧各自申请了一块地址恰好相同的内存，那不是共享，缓存一致性问题会以随机花屏形式出现。

**Q8: [learning] GKI 2.0 引入 DMA-BUF heap 的同时关闭了 `CONFIG_ION`，这中间的分界点在哪？**

分界点是 Android 12。GKI 2.0 在 `android12-5.10` 分支已于 2021 年 3 月 1 日停用 `CONFIG_ION`，同时把 `gki_defconfig` 里的 `CONFIG_DMABUF_HEAPS_SYSTEM` 关闭，让它可以成为供应商模块。停用 ION 换来三项收益：可按堆做 sepolicy 隔离、UAPI 稳定、通用 VTS 可验证。

迁移不是一次切换，而是按堆逐个推进，`libdmabufheap` 专门为此提供"按名字分配、必要时回退到 ION"的过渡抽象。官方建议的步骤是：先在 DMA-BUF 框架里注册与原 ION 堆等价的堆（一个带专用标志的堆通常要注册成两个 DMA-BUF 堆）；再在 `ueventd` 里为新堆改设备节点权限；然后让客户端链接 `libdmabufheap`、在初始化期建立"堆名 → 等价 ION 堆参数"的映射；接着把 `ion_alloc_fd()` 换成 `BufferAllocator::Alloc(堆名, size)`；再补 sepolicy 让客户端能访问新堆；确认运行正常后在内核里停用对应 ION 堆。

```text
分配类型                          libion                              libdmabufheap
系统堆·缓存                       ion_alloc_fd(fd, size, 0,          allocator->Alloc("system", size)
                                  ION_HEAP_SYSTEM,
                                  ION_FLAG_CACHED, &fd)
系统堆·非缓存                     ion_alloc_fd(fd, size, 0,          allocator->Alloc("system-uncached",
                                  ION_HEAP_SYSTEM, 0, &fd)           size)
自定义堆·无特殊标志               ion_alloc_fd(fd, size, 0,          allocator->Alloc("my_heap", size)
                                  ION_HEAP_MY_HEAP, 0, &fd)
自定义堆·带 ICON_FLAG_MY_FLAG     ion_alloc_fd(fd, size, 0,          allocator->Alloc("my_heap_special",
                                  ION_HEAP_MY_HEAP,                  size)
                                  ION_FLAG_MY_FLAG, &fd)
```

边界两条：`BufferAllocator` 持有的设备描述符由它内部管理，客户端**不应**在初始化期自己 `open("/dev/ion")` 再 `ion_open()`；保留的映射到 ION 的兼容接口只服务升级设备（其内核可能只支持 ION），稳定后可移除。判断规则：判断一个 vendor HAL 是否完成了 ION 迁移，最快的检查点是它是否链接了 `libdmabufheap` 并在初始化期调 `MapNameToIonHeap()`；仍直接 `ion_open()` 说明还在 ION 路径上。

**Q9: [learning] 这几层机制的版本差异会让结论失效吗，跨版本迁移时该重新核对什么？**

会，跨版本必须重新核对。DMA-BUF heap 是 Android 12 才成为默认路径，ashmem 在更早的内核上被 ION/DMA-BUF 取代，`binder_mmap` 的单次 4 MB 上限与异步空间上限属实现细节、可能随版本调整，`servicemanager` 在 Android 8 之后可被替换。官方文档给出的时间锚点是：`android12-5.10` 分支于 2021 年 3 月 1 日停用 `CONFIG_ION`。

跨版本最该重新核对的四件事：图形内存分配走的是 ION 还是 DMA-BUF（决定权限排查方向）；`/dev/binder` 存在几个实例与对应的驱动版本；servicemanager 是 AOSP 实现还是可替换实现；kswapd 的水位与 cgroup 版本的 `memory.low`/`memory.high` 语义。判断规则：任何"设备上跑不通"的问题，先确认结论所依据的机制在这台设备的版本里是否存在，再看逻辑——把 ION 时代的 sepolicy 结论套到 DMA-BUF 设备上，或把 AOSP `ServiceManager` 的假设套到换了 servicer 的设备上，都会得到看似合理但完全错误的排查方向。

**Q10: [learning] 排查"图形内存分配失败"时，按什么顺序区分是分配器、权限、还是内存不足？**

按"设备节点是否存在 → sepolicy 是否放行 → 堆是否支持该大小与 flag → 整机内存与映射区余量"四层顺序收敛，每层的失败表现不同。

第一层设备节点：有 ION 的设备必有 `/dev/ion`，有 DMA-BUF heap 的设备必有 `/dev/dma_heap/<name>`；节点缺失说明内核配置或 `ueventd` 没配（迁移期尤其容易漏）。第二层 sepolicy：DMA-BUF 时代每个堆是独立字符设备，缺 allow 规则会得到 EACCES，此时 `libdmabufheap` 会回退到 ION 而不是直接失败，容易掩盖问题。第三层堆能力：CMA 类堆对 `ION_FLAG_CACHED` 直接返回 `-EINVAL`、对 `align > PAGE_SIZE` 也拒绝，这类是参数不匹配而非内存不足。第四层余量：真正的 ENOMEM 来自 `binder_alloc_buf` 扩展 mmap 失败或页分配失败。判断规则：把 EACCES 误当 ENOMEM 是最常见的误判——前者改 sepolicy、后者加内存，加内存对权限问题毫无作用；所以先确认 errno，再决定动作。
