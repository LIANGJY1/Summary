# 13 · virtio-gpu 与呈现

> AppList 性能全链路解剖 · 第 13 篇（批13 作业单执行稿）
> 源码基准：kernel 引 `/home/liang/Project/AAOS13_kernel/common`（5.15.41，android13-5.15 GKI，HEAD `24d27dff64c4`），Android 引 `/home/liang/Project/AAOS13` 精树。写作前已实际读取核对全部引文行号；写作前核验 git 状态：该树 drivers/gpu/ 下无未提交修改（改动仅 drivers/char/ 两文件与本篇无关的 untracked 演示文件）。kernel 树只读，本文是唯一载体。
> 时间口径：trace 数值一律 Perfetto 相对时间 `00:00:06.xxxxxx`；vg-kit 输出无任何时戳字段，只作序列引用，不与 trace 时间轴混用。
> 证据三态：**已证**（源码或 trace 明确支持）／**未证**（有候选无证据）／**盲区**（采集配置或源码缺失）。
> 摘抄规则：每个代码块只摘 ≤10 行逐字原文（空行计行）；块内 `... /* 略 ：a-b */` 为省略标记，省略段行号随标；超长函数按连续原文拆为相邻多块，块间无内容增删。
> 术语引用：#23 vg-kit、#24 RESOURCE_FLUSH 的定义见 00 篇 §4 术语表，本篇不重复定义。00 篇全景图节点 [15] 的锚点 `virtgpu_vq.c:584`（VIRTIO_GPU_CMD_RESOURCE_FLUSH 使用点）经本篇实际读取复核命中。

## 0. 本篇范围与上下游边界

本篇走读 `drivers/gpu/drm/virtio/` 全部 15 个源文件（合计 5030 行，逐文件行数与归属判定见 §0.1），外加两个 uapi 头（`include/uapi/linux/virtio_gpu.h` 444 行、`include/uapi/drm/virtgpu_drm.h` 219 行）、DRM core 三个衔接锚点（`drm_atomic_uapi.c`/`drm_atomic.c`/`drm_atomic_helper.c` 定点引用），以及本篇的终判对象 vg-kit（#23）的查证。边界如下：

- **上游边界**：SurfaceFlinger 的 present 段。批12《SurfaceFlinger 合成》负责 SF 侧；本篇只在交接处给出两个锚点——`SurfaceFlinger::composite`（frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp:2186）与 `HWComposer::presentAndGetReleaseFences`（frameworks/native/services/surfaceflinger/DisplayHardware/HWComposer.cpp:508，其内 ：520 调 `mComposer->executeCommands()`）。SF present 之后、到本篇 DRM 提交之间的 HAL 进程与 ioctl 调用者段，**见批12**；本机 HWC HAL 实现在精树 hardware/ 下未定位到（hardware/qcom 的 sde-drm 与 hardware/google 的 Exynos 显示栈均属别的硬件域），该缺口由批12 与本篇共同声明为盲区（§9）。
- **下游边界**：guest 内核把命令写入 control virtqueue、host 发回回执为止。host 渲染进程、host 合成、物理 scanout 节拍（00 篇全景图节点 [16][17]）在 guest 内无任何观测点，本篇终判 vg-kit 计数时把这条边界作为第一约束（§6.3）。
- **本篇终判任务**（契约批13 作业单：战役计划与台账.md:170-174）：vg-kit 计数（Display0 序列 16/39/34/30/31/40/35）与本层命令的对应关系，以及"vg-kit≠已证物理呈现"的源码级划界。结论口径与交接声明一致（§6.5）。

主线先闭环：§1 给出"一次 DRM atomic 提交进入 virtio-gpu 后到 fence 回执"的完整链路；§2~§5 逐机制展开；§6 做终判；§7 机制四问总表；§8 trace 配对。

### 0.1 文件清单与归属判定

`ls drivers/gpu/drm/virtio/` 实测 15 个源文件（.o/.cmd 为构建产物不计）。判定归属如下（行数以 `wc -l` 实测，见过程记录）：

| 文件 | 行数 | 归属判定 | 本篇处理 |
|---|---|---|---|
| virtgpu_drv.c | 229 | 模块入口：virtio 总线驱动注册、probe、drm_driver 结构 | §2 全走读 |
| virtgpu_kms.c | 327 | 设备初始化 virtio_gpu_init：vq/feature/scanout 数/modeset 装配 | §2 全走读 |
| virtgpu_display.c | 355 | modeset_init、output/crtc/connector 装配、crtc helper | §2/§5 全走读 |
| virtgpu_plane.c | 455 | plane helper：primary/cursor update、transfer/flush 触发点 | §4/§5 全走读 |
| virtgpu_vq.c | 1306 | 命令封装与 virtqueue 提交、全部 VIRTIO_GPU_CMD_* 构造、回执 work | §3/§4/§5 全走读 |
| virtgpu_fence.c | 151 | dma_fence 子系统对接：alloc/emit/signal | §3 全走读 |
| virtgpu_ioctl.c | 701 | 用户态 ioctl 表：EXECBUFFER/TRANSFER_TO_HOST 等 10 个 | §3/§4 全走读 |
| virtgpu_gem.c | 284 | dumb buffer 创建、对象数组/锁/fence 辅助 | §4/§5 走读 |
| virtgpu_object.c | 282 | resource id 分配、shmem 对象创建、attach backing | §4 走读 |
| virtgpu_prime.c | 146 | dma-buf 导出/导入 | §4 走读 |
| virtgpu_vram.c | 167 | host visible blob 对象（vram） | 概述（创建分支经 RESOURCE_CREATE_BLOB，virtgpu_ioctl.c:631） |
| virtgpu_debugfs.c | 108 | debugfs 三个节点（features/irq-fence/host-visible-mm） | §3.5 走读 |
| virtgpu_trace.h | 52 | trace 事件定义（TRACE_SYSTEM=virtio_gpu） | §8.3 走读 |
| virtgpu_trace_points.c | 5 | CREATE_TRACE_POINTS | §8.3 |
| virtgpu_drv.h | 462 | 全部结构体定义与函数声明 | 各节引用 |

两份 uapi 头：`include/uapi/linux/virtio_gpu.h`（444 行，wire 命令协议）、`include/uapi/drm/virtgpu_drm.h`（219 行，DRM ioctl 常量与结构）。

**契约作业单点名的两个文件在本树不存在**（已证，`ls` 输出见过程记录）：`virtgpu_pci.c` 不存在——5.15 版本里 PCI 传输层由 drivers/virtio/virtio_pci* 提供，本驱动只注册 virtio 总线驱动（§2.2）；`virtgpu_submit.c` 不存在——该文件是更高版本内核引入的提交路径重构，本树的命令提交实体在 `virtgpu_vq.c:317-441`（§3.2）。以实际文件为准的判定与契约"若 A13 5.15 版本叫别的名字以实际文件为准"一致。引用本文行号时基准即本节清单。

## 1. 主线：一次提交在 virtio-gpu 内的闭环

先把 §2~§5 的结论按数据流排成一条线（每步的行号证据在对应节展开）：

1. 用户态提交：SF present 调用链经 HWC HAL 发起 `DRM_IOCTL_MODE_ATOMIC`，进入 guest 内核 `drm_mode_atomic_ioctl`（drivers/gpu/drm/drm_atomic_uapi.c:1313）→ `drm_atomic_commit`（drivers/gpu/drm/drm_atomic.c:1405）→ `drm_atomic_helper_commit`（drivers/gpu/drm/drm_atomic_helper.c:1824，virtio_gpu 的 `mode_config.atomic_commit` 挂的就是它，virtgpu_display.c:323）。
2. commit tail：非阻塞模式 `queue_work(system_unbound_wq, &state->commit_work)`（drm_atomic_helper.c:1889），commit work 里 `commit_tail`（:1634）→ `drm_atomic_helper_commit_tail`（:1583）→ `drm_atomic_helper_commit_planes`（:2493），逐 plane 调 `funcs->atomic_update`（:2559）——virtio_gpu 即 `virtio_gpu_primary_plane_update`（virtgpu_plane.c:212）。
3. 命令构造：plane update 内按条件发三类 2D 命令——TRANSFER_TO_HOST_2D（dumb bo，:243-245→:174-175）、SET_SCANOUT（fb 变化，:263-278）、RESOURCE_FLUSH（:281-285→:197-198/:206-207）；opcode 赋值点 virtgpu_vq.c:615/:559/:584。
4. virtqueue 提交：三命令共用 control vq，`virtqueue_add_sgs`（virtgpu_vq.c:360）后 `virtio_gpu_notify` kick（:427-441）。
5. host 回执：host 处理完命令把响应头写回 used ring，guest 收中断 `virtio_gpu_ctrl_ack`（:54-60）→ 调度 `virtio_gpu_dequeue_ctrl_func`（:194-252）→ 检查 RESP 类型、按 fence_id 调 `virtio_gpu_fence_event_process`（:243-244）→ `dma_fence_signal_locked`（virtgpu_fence.c:140/:145）。
6. 等待侧：dumb blob 路径的 flush 发送者 `dma_fence_wait_timeout(..., 50ms)`（virtgpu_plane.c:201-202）被唤醒；命令完成。

这条链在 guest 内是完整可证的。链的终点是"host 已处理 flush 命令"这一回执——host 之后做的事（合成、送 scanout、按自己节拍刷新）不在 guest 观测范围（§5.6）。vg-kit 计数的语义判定就压在"第 3~5 步的命令计数"与"物理呈现"之间能不能画等号这个问题上。

## 2. 机制一：probe 与 DRM 设备注册

### 2.1 virtio_gpu_probe：五步装配

`drivers/gpu/drm/virtio/virtgpu_drv.c:100-138`（229 行文件的核心函数）：

```c
static int virtio_gpu_probe(struct virtio_device *vdev)
{
	struct drm_device *dev;
	int ret;

	if (vgacon_text_force() && virtio_gpu_modeset == -1)
		return -EINVAL;

	if (virtio_gpu_modeset == 0)
		return -EINVAL;
```

```c
	dev = drm_dev_alloc(&driver, &vdev->dev);
	if (IS_ERR(dev))
		return PTR_ERR(dev);
	vdev->priv = dev;

	... /* 略 ：116-120，pci 总线 quirk 分支 */
	ret = virtio_gpu_init(dev);
	if (ret)
		goto err_free;
```

```c
	ret = drm_dev_register(dev, 0);
	if (ret)
		goto err_deinit;

	drm_fbdev_generic_setup(vdev->priv, 32);
	return 0;
```

走读：probe 五步——modeset 模块参数闸门（:43-46 定义，:105-109 检查）→ `drm_dev_alloc(&driver, &vdev->dev)`（:111）分配 drm_device 并挂到 `vdev->priv`（:114）→ PCI 总线场景走 `virtio_gpu_pci_quirk`（:116-120，仅做 framebuffer 冲突让位 ：59-63 与 busid 设置 ：96-97）→ `virtio_gpu_init`（:122）→ `drm_dev_register`（:126）→ `drm_fbdev_generic_setup`（:130）。任何一步失败沿 err_free/err_deinit 回退（:133-137）。

### 2.2 探测入口是 virtio 总线，无独立 PCI 驱动文件

`virtgpu_drv.c:158-161` 与 ：176-187：

```c
static struct virtio_device_id id_table[] = {
	{ VIRTIO_ID_GPU, VIRTIO_DEV_ANY_ID },
	{ 0 },
};

	... /* 略 ：163-175，feature 表 */
static struct virtio_driver virtio_gpu_driver = {
	.feature_table = features,
	.feature_table_size = ARRAY_SIZE(features),
	.driver.name = KBUILD_MODNAME,
```

```c
	.driver.owner = THIS_MODULE,
	.id_table = id_table,
	.probe = virtio_gpu_probe,
	.remove = virtio_gpu_remove,
	.config_changed = virtio_gpu_config_changed
};

module_virtio_driver(virtio_gpu_driver);
```

走读：驱动以 `module_virtio_driver`（:187）注册到 virtio 总线，id_table 只匹配 `VIRTIO_ID_GPU`（:159）。PCI 传输由 drivers/virtio/ 的 virtio_pci 层先把 PCI 设备翻译成 `struct virtio_device`，本文件 ：116 检查 `vdev->dev.parent->bus->name == "pci"` 后才做 PCI 专有的 quirk——这解释了为何没有 `virtgpu_pci.c`：总线类型的分化点在 quirk 分支与 virtio 传输层，不在本驱动。**判定已证**（判定依据：本文件全文 229 行内只有一个 driver 注册语句；`ls drivers/gpu/drm/virtio/` 无该文件，见 §0.1）。

### 2.3 drm_driver 特性位与 ioctl 挂接

`virtgpu_drv.c:198-229` 的 `driver` 结构（节选）：

```c
static const struct drm_driver driver = {
	.driver_features = DRIVER_MODESET | DRIVER_GEM | DRIVER_RENDER | DRIVER_ATOMIC,
	.open = virtio_gpu_driver_open,
	.postclose = virtio_gpu_driver_postclose,

	.dumb_create = virtio_gpu_mode_dumb_create,
	.dumb_map_offset = virtio_gpu_mode_dumb_mmap,
	... /* 略 ：206-215，debugfs/prime/gem_create_object */
	.ioctls = virtio_gpu_ioctls,
	.num_ioctls = DRM_VIRTIO_NUM_IOCTLS,
```

```c
	... /* 略 ：221-227，名字与版本 */
	.release = virtio_gpu_release,
};
```

走读：`DRIVER_ATOMIC`（:199）是本篇主线的资格位——用户态用 atomic 提交接口的前提。ioctl 表 `virtio_gpu_ioctls`（:218）定义在 virtgpu_ioctl.c:665-701，共 10 个 ioctl（uapi 常量 0x01~0x0a，virtgpu_drm.h:40-49；`DRM_VIRTIO_NUM_IOCTLS=11` 是数组长度，virtgpu_drv.h:262——数组 11 项对应索引 0~10，索引 0 未用）。每个 drm_file 打开时 `virtio_gpu_driver_open` 分配 context id（virtgpu_kms.c:282-308，仅 has_virgl_3d 时 ：289-290）。

### 2.4 virtio_gpu_init：两条 virtqueue 与 num_scanouts

`drivers/gpu/drm/virtio/virtgpu_kms.c:97-240` 的关键段（:185-208）：

```c
	ret = virtio_find_vqs(vgdev->vdev, 2, vqs, callbacks, names, NULL);
	if (ret) {
		DRM_ERROR("failed to find virt queues\n");
		goto err_vqs;
	}
	vgdev->ctrlq.vq = vqs[0];
	vgdev->cursorq.vq = vqs[1];
	ret = virtio_gpu_alloc_vbufs(vgdev);
	... /* 略 ：193-196，错误回退 */
```

```c
	/* get display info */
	virtio_cread_le(vgdev->vdev, struct virtio_gpu_config,
			num_scanouts, &num_scanouts);
	vgdev->num_scanouts = min_t(uint32_t, num_scanouts,
				    VIRTIO_GPU_MAX_SCANOUTS);
	if (!vgdev->num_scanouts) {
		DRM_ERROR("num_scanouts is zero\n");
		ret = -EINVAL;
		goto err_scanouts;
	}
```

（下一引文块自 ：208 起）

```c
	DRM_INFO("number of scanouts: %d\n", num_scanouts);
```

走读：guest 向 host 申请 2 个 virtqueue——`control` 与 `cursor`（:99-102 命名，:185-191 挂接），回调用 `virtio_gpu_ctrl_ack`/`virtio_gpu_cursor_ack`（virtgpu_vq.c:54-68）。**scanout 数量由 host 写在 virtio 配置空间**，guest 读取后取与 `VIRTIO_GPU_MAX_SCANOUTS`（16，include/uapi/linux/virtio_gpu.h:225）的较小值（:201-202）；为 0 则 probe 失败（:203-207）。`virtio_gpu_config` 的字段布局在 uapi :347-352（`events_read/events_clear/num_scanouts/num_capsets` 四个 u32）。

feature 位协商在 ：143-158：`VIRTIO_GPU_F_VIRGL`（3D 渲染，:144-146）、`VIRTIO_GPU_F_EDID`、`VIRTIO_GPU_F_RESOURCE_UUID`、`VIRTIO_GPU_F_RESOURCE_BLOB`（:147-158），外加 :159-177 的 host visible 共享内存窗口。这些布尔量（`has_virgl_3d` 等）后续决定 2D/3D 命令走哪条分支（§4.3/§5.5）。**本机协商到了哪些 feature 位：设备端 dmesg 不在任何采集物内——盲区**；由此产生的分支选择差异在 §5.5 单独标三态。

probe 末段（:220-229）把设备置 ready、取 capset/EDID/display_info 并等待响应：

```c
	virtio_device_ready(vgdev->vdev);

	if (num_capsets)
		virtio_gpu_get_capsets(vgdev, num_capsets);
	if (vgdev->has_edid)
		virtio_gpu_cmd_get_edids(vgdev);
	virtio_gpu_cmd_get_display_info(vgdev);
	virtio_gpu_notify(vgdev);
```

```c
	wait_event_timeout(vgdev->resp_wq, !vgdev->display_info_pending,
			   5 * HZ);
	return 0;
```

走读：`virtio_gpu_cmd_get_display_info`（virtgpu_vq.c:763-784）发出的 `VIRTIO_GPU_CMD_GET_DISPLAY_INFO` 的回包带每 scanout 的 pmode 数组，回调 `virtio_gpu_cmd_get_display_info_cb`（:649-676）把 `resp->pmodes[i]` 逐项写入 `vgdev->outputs[i].info`（:657-658），并触发热插拔事件（:674-675）。probe 等待它的完成至多 5 秒（kms.c:228-229）。

### 2.5 modeset 装配：每 scanout 一组 output

`drivers/gpu/drm/virtio/virtgpu_display.c:326-347`：

```c
int virtio_gpu_modeset_init(struct virtio_gpu_device *vgdev)
{
	int i, ret;

	ret = drmm_mode_config_init(vgdev->ddev);
```

（下一引文块自 ：331 起）

```c
	if (ret)
		return ret;

	vgdev->ddev->mode_config.funcs = &virtio_gpu_mode_funcs;

	/* modes will be validated against the framebuffer size */
	vgdev->ddev->mode_config.min_width = XRES_MIN;
```

```c
	vgdev->ddev->mode_config.min_height = YRES_MIN;
	vgdev->ddev->mode_config.max_width = XRES_MAX;
	vgdev->ddev->mode_config.max_height = YRES_MAX;

	for (i = 0 ; i < vgdev->num_scanouts; ++i)
		vgdev_output_init(vgdev, i);

	drm_mode_config_reset(vgdev->ddev);
	return 0;
}
```

走读：循环次数就是 num_scanouts（:342-343）。`vgdev_output_init`（:251-290）为每个 index 装配 primary plane＋cursor plane（:267-272，`virtio_gpu_plane_init` virtgpu_plane.c:419-455）、crtc（:273-275）、connector（:277-281）、encoder（:283-287，`possible_crtcs = 1 << index` :285）。output 结构体数组容量 `outputs[VIRTIO_GPU_MAX_SCANOUTS]`（virtgpu_drv.h:210）。

初始 enabled 状态只在 index 0（:261-264）：

```c
	output->index = index;
	if (index == 0) {
		output->info.enabled = cpu_to_le32(true);
		output->info.r.width = cpu_to_le32(XRES_DEF);
		output->info.r.height = cpu_to_le32(YRES_DEF);
	}
```

connector 的在线判定读 `output->info.enabled`（:223-234，`virtio_gpu_conn_detect`）。即：**guest 侧哪些屏"在线"由 host 的 display_info 回包决定**，probe 时只有 scanout 0 先按默认 1024x768 置位，随后被回包覆盖。

`mode_config.funcs`（:334 赋值，:320-324 定义）是 atomic 提交的入口契约：`fb_create`（:321）/`atomic_check = drm_atomic_helper_check`（:322）/`atomic_commit = drm_atomic_helper_commit`（:323）。注意：**本驱动没有设置 `mode_config.helper_private`**（grep drivers/gpu/drm/virtio/ 无 helper_private 命中，见过程记录），因此 commit_tail 走默认 helper `drm_atomic_helper_commit_tail`（drm_atomic_helper.c:1671-1674 的 else 分支）——这一点在 §5.1 用于确定调用链。

### 2.6 机制四问（机制一）

- **规则是什么**：virtio 总线匹配 VIRTIO_ID_GPU 后按 probe 五步装配出 drm_device；scanout 数、feature 位、每屏分辨率全部来自 host 写入的配置空间与 GET_DISPLAY_INFO 回包。
- **谁设置**：host 设置配置空间与回包内容；guest 只能在上限内接受（num_scanouts 封顶 16，:201-202）。
- **谁执行**：guest 的 `virtio_gpu_probe`/`virtio_gpu_init`/`virtio_gpu_modeset_init` 执行装配；DRM core 在 `drm_dev_register` 后向用户态暴露 /dev/dri/renderD* 与 card*。
- **执行的后果（或违反的后果）**：num_scanouts 为 0 时 probe 失败（:203-207），整个 DRM 设备不出现；Display1/2 在 vg-kit 输出中恒 0 的 guest 内核侧对应物是"host 声明的 scanout 中只有 scanout 0 的 pmode enabled"（§2.5 已证机制；本机 pmode 具体值未证——设备端日志盲区）。**反推不成立**：vg-kit 的 Display1/2=0 不能反证 host 只声明了 1 个 scanout（vg-kit 内部按什么枚举，源码盲区，§6.1）。

## 3. 机制二：fence 与 ring——命令的发送与回执

### 3.1 命令封装：vbuffer

所有 guest→host 命令共用一个封装结构 `virtio_gpu_vbuffer`（virtgpu_drv.h:144-158：命令本体指针、可选 data 缓冲、响应缓冲、resp_cb、objs 引用、链表节点）。分配来自 `kmem_cache`（virtgpu_vq.c:70-79），单块尺寸 `VBUFFER_SIZE = vbuffer + 96 字节内联命令 + 24 字节内联响应`（:37-41）。`virtio_gpu_get_vbuf`（:87-109）以 `GFP_KERNEL | __GFP_NOFAIL` 分配（:94）。

### 3.2 入队与 kick：queue_ctrl_sgs / virtio_gpu_notify

`drivers/gpu/drm/virtio/virtgpu_vq.c:317-371`（`virtio_gpu_queue_ctrl_sgs`，核心段）：

```c
again:
	spin_lock(&vgdev->ctrlq.qlock);

	if (vq->num_free < elemcnt) {
		spin_unlock(&vgdev->ctrlq.qlock);
		virtio_gpu_notify(vgdev);
		wait_event(vgdev->ctrlq.ack_queue, vq->num_free >= elemcnt);
		goto again;
	}
```

（下一引文块自 ：347 起）

```c
	/* now that the position of the vbuf in the virtqueue is known, we can
	 * finally set the fence id
	 */
```

```c
	if (fence) {
		virtio_gpu_fence_emit(vgdev, virtio_gpu_vbuf_ctrl_hdr(vbuf),
				      fence);
		if (vbuf->objs) {
			virtio_gpu_array_add_fence(vbuf->objs, &fence->f);
			virtio_gpu_array_unlock_resv(vbuf->objs);
		}
	}
```

（下一引文块自 ：359 起）

```c
	ret = virtqueue_add_sgs(vq, sgs, outcnt, incnt, vbuf, GFP_ATOMIC);
	WARN_ON(ret);
```

```c
	trace_virtio_gpu_cmd_queue(vq, virtio_gpu_vbuf_ctrl_hdr(vbuf));

	atomic_inc(&vgdev->pending_commands);
```

走读：队列满时先 kick 一次再在 `ack_queue` 上睡等（:341-346）——这是 guest 侧唯一的"背压等待点"，等待条件是 vq 有空位（回执 work 会 `wake_up(&vgdev->ctrlq.ack_queue)`，:241）。fence 的 id 在此时才确定（:348-350 注释明示），fence 同时挂到 objs 的 dma_resv 上（:354-356，`virtio_gpu_array_add_fence` virtgpu_gem.c:238-245 加为 exclusive fence）。入队后打 `trace_virtio_gpu_cmd_queue`（:363）并累加 `pending_commands`（:365）。

`virtio_gpu_notify`（:427-441）：

```c
void virtio_gpu_notify(struct virtio_gpu_device *vgdev)
{
	bool notify;

	if (!atomic_read(&vgdev->pending_commands))
		return;

	spin_lock(&vgdev->ctrlq.qlock);
	atomic_set(&vgdev->pending_commands, 0);
	notify = virtqueue_kick_prepare(vgdev->ctrlq.vq);
```

```c
	spin_unlock(&vgdev->ctrlq.qlock);

	if (notify)
		virtqueue_notify(vgdev->ctrlq.vq);
}
```

走读：`virtqueue_notify` 即对 control vq 的 kick（virtio 语义：通知 host available ring 有新数据）。多条命令可以先入队后一次 notify（`virtio_gpu_cmd_resource_flush` 等构造函数不 notify，调用方在发完后统一 `virtio_gpu_notify`——对比 virtgpu_plane.c:199/:208 与 virtgpu_ioctl.c:187/:431 的成对出现）。

### 3.3 fence 生命周期：alloc 与 emit

`drivers/gpu/drm/virtio/virtgpu_fence.c:74-111`：

```c
struct virtio_gpu_fence *virtio_gpu_fence_alloc(struct virtio_gpu_device *vgdev)
{
	... /* 略 ：76-83 */
	/* This only partially initializes the fence because the seqno is
	 * unknown yet.  The fence must not be used outside of the driver
	 * until virtio_gpu_fence_emit is called.
	 */
```

（下一引文块自 ：88 起）

```c
	dma_fence_init(&fence->f, &virtio_gpu_fence_ops, &drv->lock, drv->context,
		       0);

	return fence;
}
```

```c
void virtio_gpu_fence_emit(struct virtio_gpu_device *vgdev,
			  struct virtio_gpu_ctrl_hdr *cmd_hdr,
			  struct virtio_gpu_fence *fence)
{
	... /* 略 ：98-100 */
	spin_lock_irqsave(&drv->lock, irq_flags);
	fence->fence_id = fence->f.seqno = ++drv->current_fence_id;
	dma_fence_get(&fence->f);
```

```c
	list_add_tail(&fence->node, &drv->fences);
	spin_unlock_irqrestore(&drv->lock, irq_flags);

	trace_dma_fence_emit(&fence->f);

	cmd_hdr->flags |= cpu_to_le32(VIRTIO_GPU_FLAG_FENCE);
	cmd_hdr->fence_id = cpu_to_le64(fence->fence_id);
}
```

走读：整个设备一个 dma_fence context（kms.c:131 `dma_fence_context_alloc(1)`），fence_id 全局单调递增（:102）。emit 做四件事：占号、挂入 `drv->fences` 链表、打 `trace_dma_fence_emit`（dma_fence 通用事件，:107；include 语句在 virtgpu_fence.c:26）、把 `VIRTIO_GPU_FLAG_FENCE` 标志与 fence_id 写进命令头（:109-110；标志常量与 `virtio_gpu_ctrl_hdr` 布局在 uapi linux/virtio_gpu.h:125-133）。fence 的 timeline 名是 `"controlq"`（:38-41）。`signaled` 回调恒 false（:43-50），即未回执前所有查询都视为未完成。

### 3.4 回执：中断→work→fence 信号

`virtgpu_vq.c:54-60` 与 ：194-252（回执主函数，分两段摘）：

```c
void virtio_gpu_ctrl_ack(struct virtqueue *vq)
{
	struct drm_device *dev = vq->vdev->priv;
	struct virtio_gpu_device *vgdev = dev->dev_private;

	schedule_work(&vgdev->ctrlq.dequeue_work);
}
	... /* 略 ：62-193，cursor_ack/get_vbuf/reclaim_vbufs */
void virtio_gpu_dequeue_ctrl_func(struct work_struct *work)
{
```

```c
	... /* 略 ：196-213 */
	list_for_each_entry(entry, &reclaim_list, list) {
		resp = (struct virtio_gpu_ctrl_hdr *)entry->resp_buf;

		trace_virtio_gpu_cmd_response(vgdev->ctrlq.vq, resp);

		if (resp->type != cpu_to_le32(VIRTIO_GPU_RESP_OK_NODATA)) {
			... /* 略 ：219-226，错误分级打印 */
```

```c
		}
		if (resp->flags & cpu_to_le32(VIRTIO_GPU_FLAG_FENCE)) {
			u64 f = le64_to_cpu(resp->fence_id);
			... /* 略 ：231-236，fence_id 单调校验 */
		}
		if (entry->resp_cb)
			entry->resp_cb(vgdev, entry);
	}
	wake_up(&vgdev->ctrlq.ack_queue);
```

```c
	if (fence_id)
		virtio_gpu_fence_event_process(vgdev, fence_id);
```

走读：host 回执产生 virtqueue 中断，中断上下文不处理事件本身，只 `schedule_work` 把回执处理排入 kworker（:54-60；与批01 的 virtio_input 在 hardirq 内直接调 `input_event()` 的形态不同）。work 里 `virtqueue_get_buf` 收割 used ring（`reclaim_vbufs` :180-192），对每个回执打 `trace_virtio_gpu_cmd_response`（:216），RESP 非 OK 时只打错误日志（:218-227，`DRM_ERROR_RATELIMITED` :222，**无重试、无上抛**——已证），带 fence 标志的回执取其 fence_id，最后 `virtio_gpu_fence_event_process`（:243-244）。

`virtgpu_fence.c:113-151` 的信号逻辑（核心段）：

```c
	list_for_each_entry_safe(curr, tmp, &drv->fences, node) {
		if (fence_id != curr->fence_id)
			continue;

		signaled = curr;
```

（下一引文块自 ：128 起）

```c
		/*
		 * Signal any fences with a strictly smaller sequence number
		 * than the current signaled fence.
		 */
		list_for_each_entry_safe(curr, tmp, &drv->fences, node) {
```

```c
			/* dma-fence contexts must match */
			if (signaled->f.context != curr->f.context)
				continue;

			if (!dma_fence_is_later(&signaled->f, &curr->f))
				continue;
```

（下一引文块自 ：139 起）

```c
			dma_fence_signal_locked(&curr->f);
			list_del(&curr->node);
			dma_fence_put(&curr->f);
		}
```

```c
		dma_fence_signal_locked(&signaled->f);
```

走读：host 回执带 fence_id，guest 把该 id 及之前同 context 的全部 fence 一次性信号（:128-147）——fence_id 单调递增使"≤ 当前 id 即已完成"成立。同时 `last_fence_id` 被 `atomic64_set` 记录（:121）。

### 3.5 可观测面：debugfs 与两个 trace 事件

`virtgpu_debugfs.c:94-98` 注册三个节点，其中 `virtio-gpu-irq-fence`（:65-75）输出：

```c
	seq_printf(m, "fence %llu %lld\n",
		   (u64)atomic64_read(&vgdev->fence_drv.last_fence_id),
		   vgdev->fence_drv.current_fence_id);
```

走读：这是 guest 内"已回执 fence 数 / 已发出 fence 数"的静态读数点。动态事件有两个——`virtio_gpu_cmd_queue`（入队）与 `virtio_gpu_cmd_response`（回执），定义在 `virtgpu_trace.h:38-46`，TRACE_SYSTEM 为 `virtio_gpu`（:7-9），打印格式（:32-35）：

```c
	TP_printk("vdev=%d vq=%u name=%s type=0x%x flags=0x%x fence_id=%llu ctx_id=%u",
		  __entry->dev, __entry->vq, __entry->name,
		  __entry->type, __entry->flags, __entry->fence_id,
		  __entry->ctx_id)
```

**判定**：事件定义已证（源码）；本战役 trace 是否采到——§8.2 查证为未采（盲区→采集配置边界）。`type` 字段就是 §5 的 opcode（如 0x0104=RESOURCE_FLUSH），若采到即可逐命令计数，这正是 vg-kit 计数语义验证所需的采集形态（§8.3）。

### 3.6 机制四问（机制二）

- **规则是什么**：每条可带 fence 的命令在入队时取得全局单调 fence_id 并写进命令头；host 处理完后把该 id 放进回执头；guest 按回执信号 ≤ 该 id 的全部 fence。
- **谁设置**：fence 由 `virtio_gpu_queue_ctrl_sgs` 的调用方传入（flush 的 dumb blob 路径 §5.5、3D 提交 §3.7、resource create 等）；fence_id 由 guest 驱动分配（:102），host 只回显。
- **谁执行**：guest 的 virtqueue 基础设施（virtio_ring）与 host 后端共同执行交付；回执处理在 guest 的 kworker（dequeue_work）。
- **执行的后果（或违反的后果）**：fence 未信号时，挂在 dma_resv 上的等待者全部睡眠（`virtio_gpu_wait_ioctl` 的 `dma_resv_wait_timeout`，virtgpu_ioctl.c:453-457；flush 发送者的 50ms 上限等待 §5.5）；vq 满时提交者睡在 ack_queue（:344）。host 若不回执，`last_fence_id` 停滞、等待方到 timeout——guest 内没有第二条恢复路径（已证：回执处理函数全文无定时器）。

### 3.7 3D 命令提交：cmd_submit 与 EXECBUFFER 的关系

`virtgpu_vq.c:1080-1101`（`virtio_gpu_cmd_submit`）：

```c
void virtio_gpu_cmd_submit(struct virtio_gpu_device *vgdev,
			   void *data, uint32_t data_size,
			   uint32_t ctx_id,
			   struct virtio_gpu_object_array *objs,
			   struct virtio_gpu_fence *fence)
{
	... /* 略 ：1086-1094，vbuf 装配 */
	cmd_p->hdr.type = cpu_to_le32(VIRTIO_GPU_CMD_SUBMIT_3D);
	cmd_p->hdr.ctx_id = cpu_to_le32(ctx_id);
```

```c
	cmd_p->size = cpu_to_le32(data_size);

	virtio_gpu_queue_fenced_ctrl_buffer(vgdev, vbuf, fence);
}
```

用户态入口 `VIRTGPU_EXECBUFFER`（virtgpu_ioctl.c:77-204）：无 virgl 直接 `-ENOSYS`（:93-94），命令体从用户态 `vmemdup_user` 复制（:154-158），可选 in-fence（:102-121）与 out-fence fd（:123-127/:172-182），提交走 `virtio_gpu_cmd_submit`（:184-185）后立即 notify（:187）。opcode `VIRTIO_GPU_CMD_SUBMIT_3D`（uapi linux/virtio_gpu.h:90，0x0206 系列）。**该通道只运 3D 命令流**，与 2D 呈现命令（§4/§5）不共用 ioctl——这一判定是 §4.4 的前提。

## 4. 机制三：TRANSFER_TO_HOST_2D——内容上传

### 4.1 wire 命令与驱动构造

`include/uapi/linux/virtio_gpu.h:195-202`：

```c
/* VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D: simple transfer to_host */
struct virtio_gpu_transfer_to_host_2d {
	struct virtio_gpu_ctrl_hdr hdr;
	struct virtio_gpu_rect r;
	__le64 offset;
	__le32 resource_id;
	__le32 padding;
};
```

opcode 定义在枚举 ：66-80（2D 命令段）：`VIRTIO_GPU_CMD_GET_DISPLAY_INFO = 0x0100` 起，`RESOURCE_CREATE_2D`/`RESOURCE_UNREF`/`SET_SCANOUT`/`RESOURCE_FLUSH`/`TRANSFER_TO_HOST_2D` 顺次递增（:68-72）——即 TRANSFER_TO_HOST_2D=0x0105、RESOURCE_FLUSH=0x0104。驱动构造函数 `virtio_gpu_cmd_transfer_to_host_2d`（virtgpu_vq.c:594-624）：

```c
	bool use_dma_api = !virtio_has_dma_quirk(vgdev->vdev);
	struct virtio_gpu_object_shmem *shmem = to_virtio_gpu_shmem(bo);

	if (use_dma_api)
		dma_sync_sgtable_for_device(vgdev->vdev->dev.parent,
					    shmem->pages, DMA_TO_DEVICE);

	cmd_p = virtio_gpu_alloc_cmd(vgdev, &vbuf, sizeof(*cmd_p));
	memset(cmd_p, 0, sizeof(*cmd_p));
	vbuf->objs = objs;
```

```c
	cmd_p->hdr.type = cpu_to_le32(VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D);
	cmd_p->resource_id = cpu_to_le32(bo->hw_res_handle);
	cmd_p->offset = cpu_to_le64(offset);
	... /* 略 ：618-621，r 赋值 */
	virtio_gpu_queue_fenced_ctrl_buffer(vgdev, vbuf, fence);
```

走读：命令只携带 resource_id＋矩形＋偏移，**不携带像素数据**；host 从 backing 存储读内容。backing 的 guest 物理地址清单由 `VIRTIO_GPU_CMD_RESOURCE_ATTACH_BACKING` 在对象创建时提交（:626-647；mem_entry 数组来自 `virtio_gpu_object_shmem_init`，virtgpu_object.c:150-209，`dma_map_sgtable` DMA_TO_DEVICE :176-181）。`dma_sync_sgtable_for_device`（:607-609）在每次 transfer 前做缓存同步——guest 侧 CPU 写入的帧内容对 host 可见的机制保证点。

### 4.2 触发者一：kernel 内 dumb bo 的 plane 更新

`drivers/gpu/drm/virtio/virtgpu_plane.c:155-176`：

```c
static void virtio_gpu_update_dumb_bo(struct virtio_gpu_device *vgdev,
				      struct drm_plane_state *state,
				      struct drm_rect *rect)
{
	... /* 略 ：159-167，bo/w/h/x/y/off 计算 */
	objs = virtio_gpu_array_alloc(1);
	if (!objs)
		return;
	virtio_gpu_array_add_obj(objs, &bo->base.base);
```

```c
	virtio_gpu_cmd_transfer_to_host_2d(vgdev, off, w, h, x, y,
					   objs, NULL);
}
```

调用点在 `virtio_gpu_primary_plane_update`（:212-286）内：

```c
	bo = gem_to_virtio_gpu_obj(plane->state->fb->obj[0]);
	if (bo->dumb)
		virtio_gpu_update_dumb_bo(vgdev, plane->state, &rect);
```

走读：**只有 dumb buffer（fbcon/无 KMS 能力的用户态用的简单缓冲）才在 kernel 内触发 transfer**，fence 实参为 NULL（:174-175）。Android 图形栈的 buffer 不是 dumb buffer（GEM blob/shmem 对象经 prime/gralloc 路径），所以本触发者在 Android 场景通常不走——本机是否出现未证（无逐命令观测，§8.2）。

### 4.3 触发者二：VIRTGPU_TRANSFER_TO_HOST ioctl

`drivers/gpu/drm/virtio/virtgpu_ioctl.c:380-439`（核心分支）：

```c
	objs = virtio_gpu_array_from_handles(file, &args->bo_handle, 1);
	if (objs == NULL)
		return -ENOENT;

	bo = gem_to_virtio_gpu_obj(objs->objs[0]);
	if (bo->guest_blob && !bo->host3d_blob) {
		ret = -EINVAL;
		goto err_put_free;
	}
```

```c
	if (!vgdev->has_virgl_3d) {
		virtio_gpu_cmd_transfer_to_host_2d
			(vgdev, offset,
			 args->box.w, args->box.h, args->box.x, args->box.y,
			 objs, NULL);
	} else {
		... /* 略 ：408-430，3D 分支：ctx attach + transfer_to_host_3d */
	}
	virtio_gpu_notify(vgdev);
```

走读：同一 ioctl 在无 virgl 时发 2D 命令（:402-406）、有 virgl 时发 `TRANSFER_TO_HOST_3D`（:424-428）。guest_blob（纯 guest 内存 blob）拒绝 transfer ioctl（:397-400）。**判定已证**：TRANSFER_TO_HOST_2D 的用户态触发者是这个 ioctl，不是 EXECBUFFER。

### 4.4 EXECBUFFER 不是 2D 通道

契约走读清单问"EXECBUFFER 内嵌 2D 命令 vs 独立 ioctl"。本树的答案（已证）：EXECBUFFER 是 3D 命令流通道，2D 转移走独立 ioctl 或 kernel 内路径。证据链：

1. `virtio_gpu_execbuffer_ioctl` 首检 `has_virgl_3d == false → -ENOSYS`（virtgpu_ioctl.c:93-94）——2D-only 系统（无 virgl）根本没有 EXECBUFFER 可用。
2. EXECBUFFER 的提交体 `virtio_gpu_cmd_submit` 发 `VIRTIO_GPU_CMD_SUBMIT_3D`（:184-185；virtgpu_vq.c:1096），命令流由 host 侧 3D 渲染器（virgl/capset 定义域）解释，guest 驱动不解析其内容（:154-158 原样复制）。
3. uapi `virtgpu_drm.h:40-49` 的 10 个 ioctl 常量中没有任何 RESOURCE_FLUSH/SET_SCANOUT 对应项——**呈现命令对用户态不可直达**（与 §5.2 呼应）。

### 4.5 DMA-BUF 导入语义：本树不支持外来导入

契约问"DMA-BUF 导入后的 transfer 语义"。本树的事实（已证）：

```c
struct drm_gem_object *virtgpu_gem_prime_import_sg_table(
	struct drm_device *dev, struct dma_buf_attachment *attach,
	struct sg_table *table)
{
	return ERR_PTR(-ENODEV);
}
```

（virtgpu_prime.c:141-146。）走读：drm_prime 框架对外来 dma-buf 的导入最终调 `gem_prime_import_sg_table`，virtio-gpu 直接返回 `-ENODEV`——**外来设备 dma-buf 无法导入为 virtio-gpu 资源**，因此"导入后的 transfer"这一形态在本树不存在。对象创建侧的注释同证（virtgpu_object.c:164-169："virtio_gpu doesn't support dma-buf import from other devices"）。本驱动 dma-buf 的实际用途是**导出方向**：`virtgpu_gem_prime_export`（prime.c:80-119）经 `virtio_dma_buf_export`（:111）导出并按需向 host 索取跨设备 uuid（:92-97，`VIRTIO_GPU_CMD_RESOURCE_ASSIGN_UUID`，virtgpu_vq.c:1148-1177）；同设备重复导入走短路（:126-136）。Android 侧跨层 buffer 流（应用→SF→HWC）在本机如何落到 virtio 资源——HWC HAL 不在精树（§0 上游边界），**整段未证/盲区**。

### 4.6 机制四问（机制三）

- **规则是什么**：TRANSFER_TO_HOST_2D 把 guest 侧 resource 的指定矩形内容标记为"待 host 读取"，像素不经命令传递、经 backing 存储映射传递，transfer 前做 dma_sync。
- **谁设置**：kernel 内 dumb bo 更新（:174-175）或用户态 TRANSFER_TO_HOST ioctl（:402-406）设置矩形与 resource_id。
- **谁执行**：guest 构造命令入 control vq，host 后端按 available ring 顺序消费并读 backing。
- **执行的后果（或违反的后果）**：先 transfer 后 flush 的次序保证 host 读到新内容（§5.4 单 commit 内的同 vq 顺序）；若只 flush 不 transfer（如外来内容不可达），host flush 的是它上次持有的旧内容——本树通过"导入即拒绝"（:141-146）堵住了内容来源不一致的主要入口，代价是该驱动不能直接合成外来 dma-buf（`virtio_gpu_primary_plane_update` 处理的 fb 只能是本驱动资源，virtgpu_display.c:302 的 fb_create 也只认本驱动 GEM 对象）。

## 5. 机制四：SET_SCANOUT 与 RESOURCE_FLUSH——呈现命令

### 5.1 DRM core 提交链（guest 内核侧，锚点级）

virtio_gpu 的 atomic 提交入口链（三个 core 锚点，均为定点引用）：

```c
/* drivers/gpu/drm/drm_atomic_uapi.c:1313 */
int drm_mode_atomic_ioctl(struct drm_device *dev,
			  void *data, struct drm_file *file)
```

```c
/* drivers/gpu/drm/drm_atomic.c:1405 */
int drm_atomic_commit(struct drm_atomic_state *state)
```

`drm_atomic_helper_commit`（drivers/gpu/drm/drm_atomic_helper.c:1824，被 virtgpu_display.c:323 挂到 `mode_config.atomic_commit`）的收尾段：

```c
	drm_atomic_state_get(state);
	if (nonblock)
		queue_work(system_unbound_wq, &state->commit_work);
	else
		commit_tail(state);

	return 0;
```

（drm_atomic_helper.c:1887-1893；commit_work 定义 ：1687-1693，其内调 `commit_tail`。）`commit_tail`（:1634）先等 fences/依赖（:1658/:1660），再因 virtio_gpu 无 `helper_private`（§2.5 已证）走默认 `drm_atomic_helper_commit_tail`（:1583-1600）：

```c
void drm_atomic_helper_commit_tail(struct drm_atomic_state *old_state)
{
	struct drm_device *dev = old_state->dev;

	drm_atomic_helper_commit_modeset_disables(dev, old_state);

	drm_atomic_helper_commit_planes(dev, old_state, 0);
```

```c
	drm_atomic_helper_commit_modeset_enables(dev, old_state);
	... /* 略 ：1595-1599，fake_vblank/hw_done/wait_for_vblanks/cleanup */
}
```

`drm_atomic_helper_commit_planes`（:2493）逐 plane 调 helper（:2559 `funcs->atomic_update(plane, old_state)`），随后逐 crtc 调 atomic_flush（:2574 `funcs->atomic_flush(crtc, old_state)`）。**plane 的 atomic_update 在 crtc 的 atomic_flush 之前**（:2559 vs :2574）——这个顺序是 §5.3 解读 virtio_gpu crtc_flush 行为的依据。prepare 阶段的 `funcs->prepare_fb` 调用点在 `drm_atomic_helper_prepare_planes`（:2389，prepare_fb 调用 ：2412-2413）。

### 5.2 primary plane update：五段走读，三类命令

`drivers/gpu/drm/virtio/virtgpu_plane.c:212-286`（`virtio_gpu_primary_plane_update`，全文 75 行按五段走读）：

第一段，无 fb 或 crtc 未激活——发"熄灭" SET_SCANOUT 后返回（:230-238）：

```c
	if (!plane->state->fb || !output->crtc.state->active) {
		DRM_DEBUG("nofb\n");
		virtio_gpu_cmd_set_scanout(vgdev, output->index, 0,
					   plane->state->src_w >> 16,
					   plane->state->src_h >> 16,
					   0, 0);
		virtio_gpu_notify(vgdev);
		return;
	}
```

第二段，damage 合并为空则整个 commit 对该 plane 静默跳过（:240-241）：

```c
	if (!drm_atomic_helper_damage_merged(old_state, plane->state, &rect))
		return;
```

**判定已证：无 damage 的提交不产生任何 transfer/scanout/flush 命令**。第三段，dumb bo 的 transfer（:243-245，§4.2 已引）。第四段，fb/几何变化或 modeset 时重挂 scanout（:247-252 条件，:263-278 命令选择）：

```c
	if (plane->state->fb != old_state->fb ||
	    plane->state->src_w != old_state->src_w ||
	    plane->state->src_h != old_state->src_h ||
	    plane->state->src_x != old_state->src_x ||
	    plane->state->src_y != old_state->src_y ||
	    output->needs_modeset) {
		output->needs_modeset = false;
		... /* 略 ：254-261，DRM_DEBUG 打印 */
```

（下一引文块自 ：263 起）

```c
		if (bo->host3d_blob || bo->guest_blob) {
			virtio_gpu_cmd_set_scanout_blob
					(vgdev, output->index, bo,
					 plane->state->fb,
					 plane->state->src_w >> 16,
					 plane->state->src_h >> 16,
					 plane->state->src_x >> 16,
					 plane->state->src_y >> 16);
		}
		... /* 略 ：271-278，else 分支经典 set_scanout 同参形态 */
```

第五段，无条件 RESOURCE_FLUSH（:281-285）：

```c
	virtio_gpu_resource_flush(plane,
				  rect.x1,
				  rect.y1,
				  rect.x2 - rect.x1,
				  rect.y2 - rect.y1);
```

走读汇总：同一 fb 连续重复提交（Android 合成器每帧 present 同一 buffer 的常见形态）只产生 RESOURCE_FLUSH（＋dumb bo 的 transfer），不产生 SET_SCANOUT；scanout 重挂只发生在换 buffer/换几何/modeset 时。`virtio_gpu_cmd_set_scanout`（virtgpu_vq.c:548-568）与 `virtio_gpu_cmd_set_scanout_blob`（:1272-1306）的 opcode 赋值点分别是 ：559 与 ：1287。

### 5.3 crtc atomic_flush：只设标志，不发命令

`drivers/gpu/drm/virtio/virtgpu_display.c:119-135`：

```c
static void virtio_gpu_crtc_atomic_flush(struct drm_crtc *crtc,
					 struct drm_atomic_state *state)
{
	struct drm_crtc_state *crtc_state = drm_atomic_get_new_crtc_state(state,
									  crtc);
	struct virtio_gpu_output *output = drm_crtc_to_virtio_gpu_output(crtc);
```

（下一引文块自 ：126 起）

```c
	/*
	 * virtio-gpu can't do modeset and plane update operations
	 * independent from each other.  So the actual modeset happens
	 * in the plane update callback, and here we just check
```

```c
	 * whenever we must force the modeset.
	 */
	if (drm_atomic_crtc_needs_modeset(crtc_state)) {
		output->needs_modeset = true;
	}
}
```

走读：**RESOURCE_FLUSH 的真正触发者是 plane 的 atomic_update（§5.2 第五段），不是 crtc 的 atomic_flush**——后者只把 modeset 请求记到 `output->needs_modeset`（:132-134），供下一轮 plane update 的条件判断（plane.c:252）消费。注释（:126-131）直证这是有意设计：modeset 与 plane update 在本驱动合并在 plane 回调里执行。契约问"flush 的触发者（drm atomic commit→virtio_gpu_crtc atomic_flush？以代码为证）"——以代码为证的答案是 plane update 回调；crtc atomic_flush 函数全文（:119-135）无任何 virtio_gpu_cmd_* 调用。

### 5.4 SET_SCANOUT 的全部发送点

grep virtio 目录 `virtio_gpu_cmd_set_scanout`（含 blob 变体）的全部调用点（见过程记录命令留痕）：

1. `virtio_gpu_crtc_mode_set_nofb`（virtgpu_display.c:85-95，resource_id=0 的重置式 scanout＋notify）；
2. `virtio_gpu_crtc_atomic_disable`（:102-111，resource_id=0 关屏）；
3. `virtio_gpu_primary_plane_update` 的 nofb 分支（plane.c:232-236）与 fb/几何变化分支（:263-278）。

四个发送点已穷尽（已证）。SET_SCANOUT 的语义是"把 resource_id 绑定到 scanout_id 并声明矩形"；`VIRTIO_GPU_CMD_SET_SCANOUT` 的 wire 结构在 uapi linux/virtio_gpu.h:179-185。

### 5.5 RESOURCE_FLUSH 的发送点与 50ms fence 等待

RESOURCE_FLUSH 的构造函数 `virtio_gpu_cmd_resource_flush`（virtgpu_vq.c:570-592）：

```c
	cmd_p = virtio_gpu_alloc_cmd(vgdev, &vbuf, sizeof(*cmd_p));
	memset(cmd_p, 0, sizeof(*cmd_p));
	vbuf->objs = objs;

	cmd_p->hdr.type = cpu_to_le32(VIRTIO_GPU_CMD_RESOURCE_FLUSH);
	cmd_p->resource_id = cpu_to_le32(resource_id);
	cmd_p->r.width = cpu_to_le32(width);
	cmd_p->r.height = cpu_to_le32(height);
	cmd_p->r.x = cpu_to_le32(x);
	cmd_p->r.y = cpu_to_le32(y);
```

```c
	virtio_gpu_queue_fenced_ctrl_buffer(vgdev, vbuf, fence);
```

（opcode 赋值点 ：584——00 篇全景图节点 [15] 的锚点，复核命中。wire 结构 `virtio_gpu_resource_flush`：uapi :187-193。）

唯一调用者 `virtio_gpu_resource_flush`（plane.c:178-210），fence 分支与无 fence 分支（分段摘录）：

```c
	vgfb = to_virtio_gpu_framebuffer(plane->state->fb);
	bo = gem_to_virtio_gpu_obj(vgfb->base.obj[0]);
	if (vgfb->fence) {
		struct virtio_gpu_object_array *objs;

		objs = virtio_gpu_array_alloc(1);
		if (!objs)
			return;
		virtio_gpu_array_add_obj(objs, vgfb->base.obj[0]);
		virtio_gpu_array_lock_resv(objs);
```

```c
		virtio_gpu_cmd_resource_flush(vgdev, bo->hw_res_handle, x, y,
					      width, height, objs, vgfb->fence);
		virtio_gpu_notify(vgdev);

		dma_fence_wait_timeout(&vgfb->fence->f, true,
				       msecs_to_jiffies(50));
		dma_fence_put(&vgfb->fence->f);
		vgfb->fence = NULL;
```

（下一引文块自 ：205 起）

```c
	} else {
		virtio_gpu_cmd_resource_flush(vgdev, bo->hw_res_handle, x, y,
					      width, height, NULL, NULL);
		virtio_gpu_notify(vgdev);
	}
```

走读：带 fence 时 flush 入队＋notify 后**同步等待 fence 至多 50ms，等待返回值弃用**（:201-202）——超时也继续走，不重试不报错。该 fence 只在 `virtio_gpu_plane_prepare_fb` 为"dumb 且换 fb"的场景分配（:288-311：primary plane 无 guest_blob 时 ：301-302 直接返回不分配；cursor plane dumb 场景 ：304-307 分配并在 cursor update 内等待，:355-372）。dumb buffer 何时以 blob 形态创建由 `virtio_gpu_mode_dumb_create` 的条件决定（virtgpu_gem.c:84-88）：

```c
	if (vgdev->has_resource_blob && !vgdev->has_virgl_3d) {
		params.blob_mem = VIRTGPU_BLOB_MEM_GUEST;
		params.blob_flags = VIRTGPU_BLOB_FLAG_USE_SHAREABLE;
		params.blob = true;
	}
```

**三态判定**：fenced flush 分支的存在已证（源码）；**本机运行时走 fence 分支还是无 fence 分支未证**——它取决于 host 协商出的 feature 位组合（has_resource_blob/has_virgl_3d，§2.4 盲区）与用户态用不用 dumb buffer。两种分支里 flush 的入队与 notify 行为一致，差异只在发送者是否等待 50ms。

### 5.6 host 回执与 guest 可见边界

RESOURCE_FLUSH 的回执处理与其它命令同路（§3.4）：`VIRTIO_GPU_RESP_OK_NODATA`（uapi linux/virtio_gpu.h:99，0x1100）或错误码。**回执的语义上界是"host 已处理该命令"**；host 收到 RESOURCE_FLUSH 后做什么——把 resource 内容贴到 host 侧 scanout 表面、何时合成、何时推给物理显示——没有 wire 消息、没有共享内存标记、没有回执字段向 guest 报告。guest 内不存在"scanout 内容已更新到面板"的任何可读状态（已证：uapi 全部响应类型枚举 ：98-113 无此类消息；本节结论与 00 篇节点 [16] 盲区声明一致）。

### 5.7 机制四问（机制四）

- **规则是什么**：DRM atomic commit 的 plane update 段发 RESOURCE_FLUSH（damage 非空时），fb 变化时前置 SET_SCANOUT；crtc atomic_flush 只登记 modeset 需求。
- **谁设置**：flush 的矩形来自 `drm_atomic_helper_damage_merged` 的合并结果（plane.c:240）；resource_id 是 fb 背后 GEM 对象的 `hw_res_handle`（:188，分配见 virtgpu_object.c:34-55，原子递增不复用的兼容 hack :36-47）。
- **谁执行**：commit_tail 的执行线程（非阻塞模式是 system_unbound_wq 的 commit work，drm_atomic_helper.c:1889）执行到 plane 回调；命令由 host 后端消费。
- **执行的后果（或违反的后果）**：flush 完成（回执）只证明 host 已处理；50ms 等待上限意味着慢回执不会卡死 commit（:201-202 弃用返回值）；damage 为空的 commit 完全静默（:240-241）——命令计数因此可以少于 commit 计数。

## 6. 机制五：vg-kit 计数语义终判

### 6.1 查证过程：vg-kit 源码不在任何可达范围

按契约要求对 `/home/liang/Project/Reachauto/YaDi/yadea_master` 做了只读查证（命令与输出见过程记录）：

1. `find ... -iname '*vg*kit*'`：**零命中**——yadea_master 内无 vg-kit 源码文件或二进制。
2. `grep -ril 'vg-kit'`（md/txt/sh/c/cpp/py）：5 个命中，全部是 `.perf-analysis/out/` 下的分析文档（round2_结论.md、真手指_上下差异定案.md、完整因果分析_capstone.md、逐层推导_重构版.md、真手指_CPU饱和定案.md）——它们是"谈论 vg-kit 的文档"，不是 vg-kit 的实现。
3. 扩展查证：`/home/liang/Project/Reachauto` 下 4 层深度内 `vg-kit*/vgkit*` 零命中；采集脚本 `collect.sh:61` 是唯一可执行的调用面。

**判定已证（查证行为本身）**：vg-kit 的实现不在本战役可达的任何树内；工具内部读什么节点、按什么口径计数，属于**盲区**（源码缺失）。

### 6.2 采集脚本中的调用域与参数

`collect.sh:61`（逐字摘录）：

```sh
(set +e; timeout --signal=TERM --kill-after=3 "$((seconds+12))s" adb shell "timeout -k 2 $((seconds+9))s adb_yocto shell 'timeout -k 2 $((seconds+2))s vg-kit -q 13 -t 2'" > "$out/vg-kit.txt" 2> "$out/vg-kit.err"; rc=$?; printf '%s\n' "$rc" > "$out/vg-kit.exit") & pids+=("$!")
```

走读：三层嵌套——宿主 adb → 设备 shell → `adb_yocto shell`（Yocto host 域的入口）→ `vg-kit -q 13 -t 2`。**vg-kit 运行在 Yocto host 域，不在 Android guest 内**（已证，调用链脚本证据）；采集侧日志 `vg-kit.err` 的 adb 客户端日志同样记录了 `service: shell:timeout -k 2 22s vg-kit -q 13 -t 2`（本样本 vg-kit.err，adb_client.cpp:351 行日志，全文 894 字节）。本样本产物：`vg-kit.txt` 29 行 644 字节、`vg-kit.exit` 内容 0。参数 `-q 13`、`-t 2` 的语义无源码可查——**盲区**（"2s"字样与输出行的 `(2s)` 后缀吻合只说明窗口标注，不说明 `-q 13` 的含义）。

### 6.3 输出物全文与计数对象口径

`vg-kit.txt` 全文（29 行，逐字）：7 个样本块，每块三行：

```text
Display[0]: avg_fps=16 (2s)
Display[1]: avg_fps=0 (2s)
Display[2]: avg_fps=0 (2s)

	……（7 块，Display[0] 依次 16/39/34/30/31/40/35）

signal_handler capture the signal
```

Display0 七值序列 16/39/34/30/31/40/35（已证，与契约 ：77 及批0 复验三 §3 逐字一致）；Display[1]/Display[2] 全 0（已证）；无时戳字段（已证）；末行为信号终止记录（已证）。

计数对象口径——本篇终判维持交接定义（00 篇 §4 #23；来源会话记录.md:17）："host/Yocto 域查询显示计数的工具；输出按 Display 划分的约 2 秒平均值，逆向分析关联 scanout 对象计数与至少一条 RESOURCE_FLUSH 增量路径；尚未证明等于物理面板真实新帧率"。本篇在 guest 内核侧能给出的对应物是：

- 与 guest 命令流**可对应**的方向：scanout 0 是唯一默认 enabled 的 scanout（§2.5 已证机制），Display0 的非零计数与"只有 scanout 0 有呈现命令流"一致。
- **不能反向证明**的部分：vg-kit 按 Display 枚举的依据（是 host scanout 表还是它自己的显示拓扑）无源码；"avg_fps" 的分母是时间窗、分子是什么事件（RESOURCE_FLUSH 增量、scanout 对象翻转、还是 host 合成完成）——源码盲区。**判定：关联口径维持未证-级联（逆向关联，非源码级已证）**。

### 6.4 RESOURCE_FLUSH 完成计数≠物理新帧率：三层理由

本篇终判的核心论证，三层独立成立：

**第一层（guest 内核源码级，已证）：flush 命令的完成不等于 scanout 内容更新。**
(a) damage 为空的 commit 不发 flush（plane.c:240-241 已证）——命令计数可少于 commit 计数；(b) 回执只到 RESP_OK_NODATA（§5.6 已证）——guest 无从区分"host 贴了新内容"与"host 只完成了命令簿记"；(c) 同一 fb 重复提交时 flush 照发（:281-285 无条件）——内容未变的帧也计数。三个方向上，flush 计数与"scanout 实际更新的新帧数"都不存在恒等关系。

**第二层（host 域，盲区）：host 合成与 scanout 节拍不可见。**
00 篇节点 [16]：host 渲染进程、host 合成器、物理扫描输出的时刻与频率不在 guest 任何接口可见。host 收到 RESOURCE_FLUSH 后可以在任意时刻呈现，也可以按自己的节拍重扫同一表面（guest 无从知晓）。因此即使第一层的计数等于"host 处理的 flush 数"，它与物理面板新帧率之间还隔着 host 域的整段盲区。

**第三层（观测口径，已证）：vg-kit 输出是 2s 窗口平均且无时戳。**
每行 `(2s)` 后缀与 7 个样本的离散输出（vg-kit.txt）说明它是窗口平均值；无任何时戳使 7 个窗口与 trace 相对时间轴不可对齐（批0 复验三 §3 已判定"不构成同域证明"）。窗口平均抹掉了帧间隔分布（对照：event0 间隔的均值/中位/P90 三值不等，契约 ：76）——平均值相等也不等于节奏相等。

### 6.5 终判陈述

综合 §6.1~§6.4，本篇对 vg-kit 计数语义的终判（与交接口径一致，不越过）：

**vg-kit 的 Display0 序列 16/39/34/30/31/40/35 是 Yocto host 域某个显示计数器的 2 秒窗口平均值；该计数器经旧会话逆向分析关联到 scanout 对象计数与至少一条 RESOURCE_FLUSH 增量路径（未证-级联口径）；它不是、也不能被当作已证的物理呈现（面板新帧）计数；它同样不是 Android doFrame 计数（doFrame 508 为 guest 用户态帧调度计数，两者观测点不同）。**"vg-kit=真实显示 FPS"在 00 篇 §5 已降级表述清单内，本篇不复活。

## 7. 机制四问总表（契约五问逐答）

| 问 | 答案与证据 | 三态 |
|---|---|---|
| ①flush 由谁发起、host 收到后是否回执 | 发起者：DRM atomic commit 的 commit_tail → `drm_atomic_helper_commit_planes`（drm_atomic_helper.c:2493/:2559）→ `virtio_gpu_primary_plane_update` → `virtio_gpu_resource_flush`（virtgpu_plane.c:281-285）。host 回执：是——RESP_OK_NODATA/错误码经 used ring→ctrl_ack 中断→dequeue_ctrl_func（virtgpu_vq.c:194-252），带 fence 时信号 `dma_fence`（virtgpu_fence.c:113-151）；回执语义上界=host 已处理命令 | 已证 |
| ②fence 未信号时 SF/HWC 等什么 | 分层：SF 用户态等 HWC 的 present 完成（`HWComposer::presentAndGetReleaseFences`，HWComposer.cpp:508——批12 域）；HWC HAL 到内核的等待形态本机未证（HAL 盲区）。guest 内核侧的等待点三处：commit_tail 等各 plane 新状态上的 fence（drm_atomic_helper.c:1658，wait_for_fences 定义 ：1438）；fenced flush 等自身 fence 至多 50ms（virtgpu_plane.c:201-202）；vq 满时提交者等 ack_queue（virtgpu_vq.c:344）。fence 的信号源唯一：host 回执（§3.4）。本 trace 无 syscall/irq 事件，实际等待点不可观测（00 篇 §3.7 盲区沿用） | 已证（机制）＋本机实例未证 |
| ③TRANSFER_TO_HOST 与 RESOURCE_FLUSH 的先后依赖 | 同一 atomic commit 内：dumb bo 先 transfer（plane.c:243-245）后 flush（:281-285）；两命令同入 control vq（单 vq FIFO，virtio 语义），host 按 available ring 顺序处理；fence_id 单调使回执有序（§3.3）。无 virgl 时用户态 TRANSFER_TO_HOST ioctl 独立于 flush（ioctl.c:402-406）。语义依赖：flush 呈现的是 host 侧 resource 当前内容，先 transfer 才保证新内容 | 已证 |
| ④guest 侧能观测到呈现完成的最早证据点 | 最早可测点是 RESOURCE_FLUSH 的 fence 信号时刻（host 回执写入 used ring 并被 dequeue 的时刻，§3.4）——它证明"host 已处理 flush"，是 guest 内呈现链最下游的可见事件。更早的观测点是 `virtio_gpu_cmd_queue` trace（入队，仅证明命令已交 guest virtio 层）。观测手段：virtio_gpu_cmd_response trace 事件（virtgpu_trace.h:43-46）/dma_fence trace/debugfs irq-fence 读数（debugfs.c:71-73）——**本 trace 全部未采**（§8.2），故本样本内该点不可见：盲区 | 已证（机制）＋本样本盲区 |
| ⑤虚拟屏多 scanout 与本机 Display1/2 无计数的对应 | guest 侧 scanout 数由 host 配置空间给出、上限 16（kms.c:199-207；uapi:225），每 scanout 一组 output（display.c:342-343）；在线状态由 display_info 回包的 pmodes[i].enabled 决定（vq.c:657-668；display.c:223-234），初始仅 scanout 0 置 enabled（display.c:261-264）。vg-kit 输出固定三行 Display[0..2]（vg-kit.txt），Display1/2=0 与"仅 scanout 0 活跃"方向一致；但 vg-kit 的枚举依据与计数器定义无源码——对应关系不能升级为已证，更不能由 0 计数反推 scanout 数量 | 机制已证＋对应关系未证 |

## 8. trace 配对与查询留痕

### 8.1 vg-kit 数值与 doFrame 计数的算术对照

本篇引用的既有数值（逐字，出处契约第三节与批0 复验三）：

- vg-kit Display0：16/39/34/30/31/40/35（7×2s 窗口；vg-kit.txt 29 行 644 字节）。
- trace 可解析跨度 14.878662 s（契约 :62）。
- Launcher doFrame 508 帧（会话记录.md:118；批09 台账沿用）。

算术对照（本篇新算，仅算术不因果）：508 / 14.878662 ≈ 34.1 帧/s（doFrame 全跨度均值）；vg-kit 七窗均值 (16+39+34+30+31+40+35)/7 = 225/7 ≈ 32.1 帧/s。两者同量级、相差约 2 帧/s。**判定：算术对照成立（已证，两分子两分母均可复算）；"vg-kit 计数≈doFrame 速率"的因果或恒等关系未证**——两个计数器分属 host 域命令侧与 guest 用户态调度侧，且 7 个 2s 窗与 14.878662 s 跨度无时戳对齐（窗口可能跨界、可能不完全覆盖）。

### 8.2 本 trace 中 drm/virtio 事件的可见性查证（新 SQL，过程记录留痕）

四个新查询（批13-q1~q4）跑在本样本 trace.perfetto 上（trace_processor_shell-2434129a3af4dc9e），结论：

1. **track 名**（q1，匹配 drm/virtio/flush/scanout/gpu）：仅 `GPU completion`（2 条 track）与 `PrevGpuFrameMissed`（1 条）。**无任何 virtio/drm 名字的 track**。
2. **track 上的 slice**（q4）：`GPU completion`/`PrevGpuFrameMissed` 两轨 slice 数为 0——**轨存在但无事件**。
3. **slice 名**（q2，匹配 virtio/drm/flush/scanout）：flush 类命中全是 Android 用户态图形 slice——`virtual void skgpu::v1::OpsTask::onPrepare(GrOpFlushState *)`/`onExecute(...)` 各 6567、`st_flush` 3036、`st_context_flush` 2025、`flush surface` 1010、`flush` 1010、`flush commands` 507；**无 `virtio_gpu_cmd_queue`/`virtio_gpu_cmd_response`**。
4. **raw 表事件面**（q5）：本 trace 实际采集的 ftrace 事件名全表——`print` 551653、`sched_switch` 424434、`sched_waking` 280053、`sched_blocked_reason` 68074、`task_rename` 116、`task_newtask` 116、`sched_process_exit` 115、`cgroup_rmdir` 7、`cgroup_release` 7、`cgroup_mkdir` 7、`cgroup_attach_task` 7。sched 表 424434 行。
5. `ftrace_setup_errors=39`（stats 表，severity=notice）复现（契约 ：62）。

**判定**：本 trace 的采集配置不含 virtio_gpu tracepoints 与 drm 类事件（已证——raw 事件面穷尽列出）；因此本样本内 RESOURCE_FLUSH/SET_SCANOUT/TRANSFER_TO_HOST_2D 的逐命令序列、fence 信号时刻均不可观测（**盲区：采集配置边界**）。按硬性标准 4 的表述纪律："未采到"不等于"没发生"——508 个 doFrame 在机制上最终必然经 §5.1 的链到达本层并至少发出 flush 或静默跳过（取决于 damage），两者的比例在本样本内不可判。

### 8.3 若补采：可用的验证形态

把 `drivers/gpu/drm/virtio/virtgpu_trace.h` 定义的两个事件加入采集配置（事件目录 `virtio_gpu`，事件名 `virtio_gpu_cmd_queue`/`virtio_gpu_cmd_response`，打印格式见 §3.5 引文）即可获得：逐命令 opcode（0x0103 SET_SCANOUT/0x0104 RESOURCE_FLUSH/0x0105 TRANSFER_TO_HOST_2D）、fence_id 序列、命令入队与回执的成对时戳。该形态可将 §6.4 第一层的三个不等式在本机升级为已测。此为本篇给出的采集建议，不改变本样本的任何结论。

## 9. 未证边界与盲区清单（本篇新增与沿用）

| # | 事项 | 三态 | 依据 |
|---|---|---|---|
| 1 | 本机协商的 feature 位（virgl/EDID/blob/host_visible）与 num_scanouts 具体值 | 盲区 | 设备端 dmesg（DRM_INFO 行，kms.c:179-183/:208）不在采集物内 |
| 2 | 本机 flush 走 fence 分支还是无 fence 分支 | 未证 | 取决于 #1（gem.c:84-88、plane.c:301-307） |
| 3 | 本机 HWC HAL 实现（SF present→DRM_IOCTL_MODE_ATOMIC 之间的进程与库） | 盲区 | 精树 hardware/ 无 virtio 域 HWC；批12 衔接 |
| 4 | Android 侧 buffer 到 virtio resource 的落型（blob/经典 2D/3D） | 未证 | 依赖 #1/#3 |
| 5 | vg-kit 源码/二进制 | 盲区 | §6.1 find 零命中 |
| 6 | vg-kit 参数 -q 13 语义；Display 枚举依据；计数器精确定义 | 盲区 | §6.2/§6.3 |
| 7 | vg-kit 7 窗与 trace 14.878662 s 的对齐关系 | 未证 | 双方无共同时戳（批0 复验三 §3 维持） |
| 8 | 本 trace 内 flush/scanout 命令的实际发生频度 | 盲区 | §8.2 采集配置边界 |
| 9 | host 收到 RESOURCE_FLUSH 后的处理时长与呈现节拍 | 盲区 | §5.6；00 篇节点 [16] |
| 10 | cursor vq 在本机的活动（Android 硬件指针策略下可能闲置） | 未证 | cursor_ping 调用点在 cursor plane update（plane.c:402）；本机是否发生无观测 |
| 11 | InputReader D 3.716 ms 等既有未证项 | 沿用 | 00 篇 §3.7 全局清单，本篇不重复论证 |

## 10. 交接边界与下一篇

- **向上（批12）**：本篇的 atomic 提交进入点 `drm_mode_atomic_ioctl`（drm_atomic_uapi.c:1313）之上的全部 SF/HWC 段归批12；本篇给出的 `SurfaceFlinger::composite`（SurfaceFlinger.cpp:2186）与 `HWComposer::presentAndGetReleaseFences`（HWComposer.cpp:508）是两侧的对接锚点。
- **横向（批14）**：commit work 运行于 system_unbound_wq（drm_atomic_helper.c:1889）——该 kworker 的调度状态语义归批14；本篇不展开。
- **汇总（批15/16）**：本篇可供引用的已证锚点：§1 六步闭环、§5.2 五段走读与三类命令形态、§5.6 回执语义上界、§6.4 三层理由、§6.5 终判口径。vg-kit 相关结论引用时必须携带"逆向关联、非物理呈现"限定语；"vg-kit=真实显示 FPS"继续禁用（00 篇 §5）。

## 11. 断言与三态汇总

本篇机制性结论的断言编号 V1-V32（详细断言-证据对照见《过程记录/批13-过程记录.md》）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| V1 | 目录 15 文件归属判定；无 virtgpu_pci.c/virtgpu_submit.c | 已证 | ls/wc（§0.1） |
| V2 | probe 五步装配流程 | 已证 | virtgpu_drv.c:100-138 |
| V3 | 探测入口为 virtio 总线，无独立 PCI 驱动文件 | 已证 | virtgpu_drv.c:158-161/:176-187 |
| V4 | DRIVER_ATOMIC 特性位与 10 ioctl 挂接 | 已证 | virtgpu_drv.c:198-229；virtgpu_ioctl.c:665-701 |
| V5 | control/cursor 两条 virtqueue | 已证 | virtgpu_kms.c:99-102/:185-191 |
| V6 | num_scanouts 由 host 配置空间给出、上限 16 | 已证（机制）；本机值盲区 | kms.c:199-207；uapi:225/:347-352 |
| V7 | 每 scanout 一组 output＋primary＋cursor plane | 已证 | display.c:326-347/:251-290；plane.c:419-455 |
| V8 | 仅 scanout 0 初始 enabled；在线状态由 display_info 决定 | 已证 | display.c:261-264/:223-234；vq.c:657-668 |
| V9 | probe 末段等待 display_info 至多 5s | 已证 | kms.c:226-229 |
| V10 | 单 dma_fence context、fence_id 全局单调 | 已证 | kms.c:131；fence.c:102 |
| V11 | 命令入队→trace→pending_commands→kick 链 | 已证 | vq.c:360-365/:427-441 |
| V12 | vq 满时提交者睡 ack_queue（唯一背压等待点） | 已证 | vq.c:341-346 |
| V13 | 回执：中断→work→RESP 检查→fence 信号 | 已证 | vq.c:54-60/:194-252；fence.c:113-151 |
| V14 | 回执错误仅限流打印，无重试上抛 | 已证 | vq.c:218-227 |
| V15 | debugfs 提供 last/current fence 读数 | 已证 | debugfs.c:65-75/:94-98 |
| V16 | virtio_gpu_cmd_queue/response 两 trace 事件定义 | 已证（定义）；本 trace 未采＝盲区 | trace.h:7-9/:38-46 |
| V17 | transfer 触发者一：kernel 内 dumb bo | 已证 | plane.c:155-176/:243-245 |
| V18 | transfer 触发者二：TRANSFER_TO_HOST ioctl（无 virgl 2D 分支） | 已证 | ioctl.c:380-439/:402-407 |
| V19 | EXECBUFFER 为 3D 专用通道（无 virgl -ENOSYS；SUBMIT_3D） | 已证 | ioctl.c:93-94/:184-187；vq.c:1096 |
| V20 | transfer 前 dma_sync_sgtable_for_device | 已证 | vq.c:604-609 |
| V21 | 外来 DMA-BUF 导入 -ENODEV | 已证 | prime.c:141-146；object.c:164-169 |
| V22 | RESOURCE_FLUSH 触发者＝commit_tail→commit_planes→plane update→resource_flush | 已证 | drm_atomic_helper.c:2493/:2559；plane.c:281-285 |
| V23 | crtc atomic_flush 只设 needs_modeset 标志，恒不发命令 | 已证 | display.c:119-135 |
| V24 | SET_SCANOUT 四发送点穷尽 | 已证 | display.c:85-95/:102-111；plane.c:232-236/:263-278 |
| V25 | damage 为空的 commit 不产生任何 2D 命令 | 已证 | plane.c:240-241 |
| V26 | fenced flush 等待至多 50ms 且弃用返回值；分支选择取决于 feature 位 | 已证（机制）；本机分支未证 | plane.c:189-204/:288-311；gem.c:84-88 |
| V27 | flush 回执语义上界＝host 已处理；无 scanout 更新信号 | 已证 | uapi:98-113；vq.c:218；§5.6 |
| V28 | vg-kit 源码/二进制在可达范围零命中 | 已证（查证行为）；工具内部盲区 | §6.1 find/grep 输出 |
| V29 | vg-kit 运行于 Yocto host 域 | 已证 | collect.sh:61；vg-kit.err |
| V30 | vg-kit 计数≠物理新帧率（三层理由） | 已证（源码级论证）＋host 域盲区 | §6.4；plane.c:240-241/:281-285；uapi:98-113 |
| V31 | 34.1 帧/s vs 32.1 帧/s 仅为算术对照 | 已证（算术）；因果未证 | §8.1 |
| V32 | 本 trace 采集面无 virtio_gpu/drm 事件（未采到≠没发生） | 已证（采集配置）；事件发生与否不可判 | §8.2 q1~q5 输出 |

（完）
