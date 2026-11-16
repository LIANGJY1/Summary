# 复核 R6：13/14 virtio_gpu 与 CFS 调度两篇全量复核记录

> 复核口径：精树逐行锚点核对（±2 行窗口）＋机制链对完整函数实现验证＋trace 独立复跑（trace_processor_shell-2434129a3af4dc9e，样本 20261113-151358-vertical-1912967/trace.perfetto，rel base 6073694444196）＋算术 python 闭合＋grep/wc 类断言重跑。kernel 基准树 /home/liang/Project/AAOS13_kernel/common（HEAD 24d27dff64c4，只读）；Android 基准树 /home/liang/Project/AAOS13（只读）。两源码树未改动；trace/采集物只读。三态纪律：已证/未证/盲区维持，未证边界不得当缺陷。

## 一、13 篇 virtio_gpu 与呈现：零错误（189 锚点 0 问题，机制/算术/trace/vg-kit 查证全过）

### 1.1 锚点机械核对
- check_anchors13.py（189 条）精树锚点，修后 0 问题。覆盖 drivers/gpu/drm/virtio/ 全 15 文件（virtgpu_drv/kms/display/plane/vq/fence/ioctl/gem/object/prime/vram/debugfs/trace.h/trace_points/drv.h）＋两 uapi 头（linux/virtio_gpu.h、drm/virtgpu_drm.h）＋DRM core 三件（drm_atomic_uapi.c/drm_atomic.c/drm_atomic_helper.c）。
- 17 文件 wc 与 §0.1 表逐字吻合：virtgpu_drv.c 229/kms.c 327/display.c 355/plane.c 455/vq.c 1306/fence.c 151/ioctl.c 701/gem.c 284/object.c 282/prime.c 146/vram.c 167/debugfs.c 108/trace.h 52/trace_points.c 5/drv.h 462；uapi virtio_gpu.h 444/virtgpu_drm.h 219。15 源文件合计 5030 ✅。无 virtgpu_pci.c/virtgpu_submit.c ✅（ls 实证）。
- 14 处初判 MISMATCH 全为脚本代表词猜测误（范围引文/函数体行/小写函数名），文章引文全部正确：drv.c:96 busid 实 drm_dev_set_unique:97（区域引文）、kms.c:99 names 实 :102、display.c:302 fb_create 实 virtio_gpu_user_framebuffer_create 体内 GEM lookup 行（def :292-293，区域引文合法）、plane.c:188 hw_res_handle 实 bo 提取行 gem_to_virtio_gpu_obj（hw_res_handle 用于 :197/:206，:188 证"fb 背后 GEM 对象"合法）、plane.c:304 cursor 实 bo->dumb 条件、vq.c:626/1148 大写 opcode 实小写函数名 :627/:1149、ioctl.c:453 dma_resv_wait_timeout 实 :456（范围 :453-457）、ioctl.c:631 CREATE_BLOB 实 vram_create 调用（链证见 1.3）、object.c:34/36 实 resource_id_get:34/atomic_inc_return:46、object.c:164 注释实 :168、prime.c:126 import 实 virtgpu_dmabuf_ops 短路、debugfs.c:65 irq-fence 实函数名 virtio_gpu_debugfs_irq_info:66（节点名串在 :96）。修脚本后 0 问题。

### 1.2 机制链逐环验证（五机制全部成立）
- 机制一 probe/注册：virtio_gpu_probe:100 五步（modeset 闸 :105/drm_dev_alloc:111/pci quirk:116/virtio_gpu_init:122/drm_dev_register:126/fbdev:130）；virtio 总线单 driver 注册（module_virtio_driver:187，全文仅一处 ✅）；virtio_gpu_init 两 vq（virtio_find_vqs:185，names control/cursor:102）＋num_scanouts host 配置空间 min_t 16 封顶 :201；modeset_init 每 scanout 一组 output（循环 :342，仅 index0 初始 enabled :261）；mode_config.atomic_commit=drm_atomic_helper_commit:323；helper_private grep 零命中 ✅（commit_tail 走默认 helper）。
- 机制二 fence/ring：vbuffer 封装（drv.h:144）；queue_ctrl_sgs:317-371（背压 wait_event ack_queue:344、fence_id 入队时定 :348、virtqueue_add_sgs:360、trace_cmd_queue:363、pending_commands:365）；notify:427-441（kick）；fence_alloc:74/emit:96（fence_id 全局单调 :102、trace_dma_fence_emit:107、FLAG_FENCE:109）；回执 ctrl_ack:54→schedule_work→dequeue_ctrl_func:194-252（RESP 检查 :218 DRM_ERROR_RATELIMITED:222 无重试上抛 ✅、wake_up:241、fence_event_process:243）；fence_event_process:113-151（atomic64_set last_fence_id:121、≤当前 id 同 context 全信号 :140/:145 双 dma_fence_signal_locked ✅）。
- 机制三 TRANSFER_TO_HOST_2D：wire 结构 uapi:195；构造 vq.c:594（dma_sync_sgtable_for_device:607、opcode:615、不携像素只 resource_id＋矩形＋偏移）；触发者一 kernel dumb bo（plane.c:155-176，fence NULL:174）；触发者二 TRANSFER_TO_HOST ioctl（ioctl.c:380-439，无 virgl 2D:402/有 virgl 3D:424、guest_blob 拒 :397）；EXECBUFFER 3D 专用（ioctl.c:93 -ENOSYS、cmd_submit SUBMIT_3D vq.c:1096）；外来 DMA-BUF 导入 -ENODEV（prime.c:141-146，object.c:164-169 注释同证）。
- 机制四 SET_SCANOUT/RESOURCE_FLUSH：DRM core 链 drm_mode_atomic_ioctl:1313→drm_atomic_commit:1405→drm_atomic_helper_commit:1824（nonblock queue_work:1889）→commit_tail:1634→commit_tail:1583→commit_planes:2493（atomic_update:2559 先于 atomic_flush:2574 ✅）；primary_plane_update:212-286 五段（nofb:230-238/damage 闸 :240-241/dumb transfer:243-245/fb 变化 scanout:247-278 blob:264·classic:272/无条件 flush:281-285）逐段对完整函数核实 ✅；crtc_atomic_flush:119-135 只设 needs_modeset 恒不发命令 ✅；SET_SCANOUT 四逻辑发送点穷尽（grep 五物理调用 display.c:91/:109＋plane.c:232/:264/:272，fb 变化分支含 blob/classic 两变体 ✅）；fenced flush 50ms 等待弃用返回值（plane.c:201-202）；prepare_fb:288-311（primary 非 guest_blob 返 :301、dumb 换 fb 分配 fence:304-307）；gem.c:84-88 blob 条件。
- 机制五 vg-kit 终判：三层理由（guest 源码级 flush≠scanout 更新／host 域盲区／2s 窗口平均无时戳）逻辑成立；终判口径"逆向关联、非物理呈现"维持，未越界。
- vram→CREATE_BLOB 链实证：ioctl.c:631 virtio_gpu_vram_create→vram.c:155 virtio_gpu_cmd_resource_create_blob→vq.c:1256 VIRTIO_GPU_CMD_RESOURCE_CREATE_BLOB ✅（§0.1 vram 行"创建分支经 RESOURCE_CREATE_BLOB"成立）。

### 1.3 trace 独立复验＋算术＋vg-kit 查证（全部吻合）
- §8.2 批13-q1~q5 全复跑：q1 track 名仅 GPU completion(2)/PrevGpuFrameMissed(1)，无 virtio/drm ✅；q2 slice 名 OpsTask onPrepare/onExecute 各 6567/st_flush 3036/st_context_flush 2025/flush surface 1010/flush 1010/flush commands 507，无 virtio_gpu_cmd_queue/response ✅；q4 GPU 两轨 0 slice＋sched 表 424434 行 ✅；q5 raw 事件面 11 项逐字（print 551653/sched_switch 424434/sched_waking 280053/sched_blocked_reason 68074/task_rename 116/task_newtask 116/sched_process_exit 115/cgroup_rmdir·release·mkdir·attach_task 各 7）✅；q3 ftrace_setup_errors notice 39 ✅。
- §8.1 算术 python 闭合：508/14.878662=34.1429≈34.1 ✅；16+39+34+30+31+40+35=225 ✅；225/7=32.1429≈32.1 ✅；差 2.0≈2 ✅。
- §6 vg-kit 查证实物复核：vg-kit.txt 29 行 644 字节 ✅、Display0 序列 16/39/34/30/31/40/35 ✅、Display1/2 全 0 ✅、7 块 ✅、末行 signal_handler capture the signal ✅、vg-kit.exit 0 ✅、vg-kit.err 894 字节（adb_client.cpp:351 格式证实）✅；collect.sh:61 三层嵌套 adb→adb_yocto shell→vg-kit -q 13 -t 2 逐字吻合 ✅；yadea_master find '*vg*kit*' 零命中复跑 ✅。

### 1.4 修正清单
无。13 篇引文/机制/算术/trace/查证全过，零错误（与 06 篇同为干净篇）。

### 1.5 复核口径声明
- 可接受引文（不修）：display.c:302 fb_create（virtio_gpu_user_framebuffer_create 体内 GEM lookup 行，def :292-293，区域引文）；plane.c:188 hw_res_handle（bo 提取行，证"fb 背后 GEM 对象"，字段用于 :197/:206）；ioctl.c:631 CREATE_BLOB（vram_create 分支入口，链证 vram.c:155→vq.c:1256）；fence.c 块头 :139（signal_locked 实在 :140，块头指向前一空行，内容逐字正确）。
- 未证/盲区维持（§9 十一项，不得当缺陷）：本机 feature 位/num_scanouts 值（dmesg 盲区）、flush fence 分支选择、HWC HAL 实现、Android buffer 落型、vg-kit 源码/参数/枚举/计数器定义、7 窗与 trace 对齐、flush 实际频度、host 处理时长、cursor vq 活动、InputReader D 等沿用项。
- 脚本存档：/tmp/review-applist/check_anchors13.py（189）；复跑批13-q1~q5 留痕 SQL。

## 二、14 篇 CFS 调度与线程状态：机制全过；3 组瑕疵已修（4 处定点：2 引文行号＋1 数据表，无机制结论错）

### 2.1 锚点机械核对
- check_anchors14.py（143 条）精树锚点，修后 0 问题。覆盖 include/linux/sched.h、include/linux/sched/signal.h、include/trace/events/sched.h、kernel/sched/core.c/fair.c/sched.h/topology.c/wait.c、kernel/entry/common.c、kernel/signal.c、arch/arm64/kernel/process.c、fs/pstore/pmsg.c、arch/arm64/configs/gki_defconfig，及 Android system/logging/liblog（logger_write.cpp/pmsg_writer.cpp）、system/core/libcutils/trace-dev.inc、system/logging/logd/LogTags.cpp、frameworks/native/cmds/atrace/atrace.cpp、inputflinger/reader/InputReader.cpp。
- 8 处初判 MISMATCH：6 处脚本范围引文猜测误（trace/sched.h:166 sched_waking 实 DEFINE_EVENT:170、fair.c:4243 vruntime-=thresh 实 :4246、fair.c:7155 newidle_balance 实 balance_fair 体内调用 :7161、sched/sched.h:3080 sched_energy_enabled 实 :3086、process.c:543 in_sched_functions 实 :551、process.c:547 count++<16 实 :555、trace-dev.inc:216 WRITE_MSG 实 write(atrace_marker_fd) 行），文章范围引文正确；**2 处真错误已修**（见 2.4 E1/E2）。

### 2.2 关键发现
- core.c preempt_schedule_irq 的 __schedule(SM_PREEMPT) 实在 :6883（:6879 是 exception_enter 与 do 之间的空行）；文章 §2.2/§2.6 两处误引 :6879。preempt_schedule_common 的 :6655 本就正确。
- process.c get_wchan 的 start_backtrace 取帧起点实在 :546（:547 是空行）；文章 §6.3 prose 误引 :547。函数体 :534-560＋EXPORT:561，块内容逐字正确，task_is_running 排除 :539-540 正确。
- §3.2 CPU4 S 窗口构成表"其余 12 线程"组漏列 L-wake-k（tid 4956，614,730 ns，1 slice），且上限"各自 ≤373,346 ns"错——L-wake-k 614,730＞373,346。该组 remainder 合计 3,684,420 ns/39 slice 本身正确（top10 合计 47,243,580，50,928,000−47,243,580=3,684,420 ✅），仅命名列表与上限误。对照 §5.5 窗 2"另有 8 线程各 ≤339,192 ns"上限正确（OMXCallbackDisp 339,192 为该组最大）。

### 2.3 机制链逐环验证（九机制全部成立）
- ①状态字符串翻译：sched.h:85-87 三基态（RUNNING 0/INTERRUPTIBLE 1/UNINTERRUPTIBLE 2）、TASK_REPORT:116=0x7F、TASK_REPORT_IDLE:1649=0x80/MAX:1650=0x100；__schedule:6360（SM_NONE/SM_PREEMPT:6311、prev_state 决策 :6413、signal_pending_state:6414、deactivate_task:6436、trace_sched_switch:6480 首参=sched_mode&SM_MASK_PREEMPT）；__trace_sched_switch_state:190（preempt→TASK_REPORT_MAX:202-203）；TP_printk:249（低 8 位 __print_flags S/D/T/t/X/Z/P/I、无命中 R、:264 追加"+"）；proc task_index_to_char:1673 "RSDTtXZPI" 与 ftrace 口径分离 ✅。
- ②R+ 产生：仅抢占入口（preempt_schedule_common:6637/:6655、preempt_schedule_irq:6871/:6883[修]）；check_preempt_wakeup:7245 resched_curr:7337 置 TIF_NEED_RESCHED；SM_PREEMPT 下 :6413 短路不出队 ✅。
- ③S 语义：set_current_state 等待模式（sched.h:172）；autoremove_wake_function:418→default_wake_function:6891→try_to_wake_up；S 退出两路（事件唤醒/信号 :6415 清态不出队）；S=出队等事件非等 CPU ✅。
- ④唤醒路径：try_to_wake_up:4040（p==current 快路径、trace_sched_waking:4091 早于选核、ttwu_runnable:4116、TASK_UNINTERRUPTIBLE→blocked_reason:4119-4120、TASK_WAKING:4154、select_task_rq:4192、set_task_cpu:4201、ttwu_queue:4207）；ttwu_queue:3856 两路（wakelist IPI:3828/sched_ttwu_pending:3708、慢路锁 rq:3866）；ttwu_do_activate:3637（activate_task:3660、ttwu_do_wakeup:3601 check_preempt_curr:3604/TASK_RUNNING:3605/trace_sched_wakeup:3606）；sched_waking:170 vs sched_wakeup:178 语义区别（waking 必在唤醒者上下文、wakeup 不一定）✅。
- ⑤waker 归属：ftrace 模板字段仅 comm/pid/prio/target_cpu（:147-163），无 waker 字段；Perfetto 按发射上下文关联，maybe interrupt 不证代码调用者 ✅。
- ⑥选核三因素：select_task_rq:3471（nr_cpus_allowed:3475/is_cpu_allowed:3490）；select_task_rq_fair:7016（vendor hook 短路 :7028→能量 find_energy_efficient_cpu:6843→affine wake_affine:6039/wake_affine_idle:5969→idle select_idle_sibling:6440）；sched_energy_enabled:3086（CONFIG_ENERGY_MODEL＋SCHEDUTIL＋sched_energy_present 静态键）；target_cpu=3 是唤醒前 task_cpu（:4091 早于 :4192），选核分支未证 ✅。
- ⑦R 双产生点：唤醒入队未 pick（enqueue_task_fair:5637→enqueue_entity vruntime 红黑树、place_entity:4222 睡眠折扣 :4246）＋自愿调度点切出未睡（:6413 假）；pick_next_task_fair:7388/pick_next_entity:4556 最左优先＋buddy 覆盖；wakeup_preempt_entity:7199 gran=wakeup_gran:7165（默认 1ms fair.c:87 ✅）；sysctl_sched_latency=6ms（fair.c:42，/2=3ms ✅）、migration_cost=500μs（fair.c:90 ✅）三默认值核实。
- ⑧D 与 write_pmsg：signal_pending_state:389（纯 D 不命中 TASK_INTERRUPTIBLE|TASK_WAKEKILL）；signal_wake_up_state:763 只附加 TASK_INTERRUPTIBLE；blocked_reason 只对 D（:4119）；get_wchan:534 回溯 16 帧（:555）跳 in_sched_functions:551 取第一非调度 PC（start_backtrace:546[修]）%pS 符号化；write_pmsg:15（pmsg_lock:12 mutex_lock:31/write_user:32 两睡眠点、pmsg_fops.write:40）；用户态 liblog write_to_log:290（LogdWrite:218＋PmsgWrite:219）→PmsgWrite:70（ANDROID_DEBUGGABLE 门槛 :78、GetPmsgFd:94 open/dev/pmsg0:41/:46）；atrace WRITE_MSG 写 trace_marker（trace-dev.inc:188 宏/:216 write）独立于 pmsg ✅；gki_defconfig CONFIG_PSTORE_PMSG=y RAM 后端。
- ⑨迁移约束：can_migrate_task:7919 四道判（vendor hook:7926/cpus_ptr:7944/task_running:7980/cache-hot task_hot:7813 migration_cost 500μs）；"其他核 idle 不证明可迁核"机制成立（idle 只说明本核 pick 选 idle，跨核须负载均衡拉取受三重约束）✅。

### 2.4 trace 独立复验＋算术＋grep（全部吻合）
- 批14-q11 sched 计数：sched_switch 424434/sched_waking 280053/sched_blocked_reason 68074/sched_wakeup 0（查询无该行）✅。
- q8 主线程 756 八次切出 prev_state 256/0/1/256/256/256/0/256 逐行吻合，next_comm 全对（rcu_preempt/tbox_ap/ackgroundThread/PowerManagerSer×3/UsbFfs-worker/InputDispatcher）；§2.5 表 8 行起点/状态/时长全一致 ✅。
- q10 Reader D：6801836572 dur 3716500 blocked_function=write_pmsg$6fb35a9abf14f1ffebba6021202469c1 逐字 ✅；D 前 Running 177731/120884 均 cpu3 ✅；D 内 tid1489 零 sched slice ✅；D 后 R 1602116 ✅；状态序列 R848846→Running177731→R+461962→Running120884→D3716500→R1602116 ✅。
- q13 CPU4 S 窗口：top10 逐行（HeapTaskDaemon 13228767/21、binder:756_2 7986539/20、L-hotwordvad-k 6413234/14、logcat 5477768/26、adbd 4622693/21、swapper 3617928/26、logcat 2570346/10、ncent.wecarnavi 1731231/5、binder:1909_7 979499/3、kworker/4:1 615575/19）＋合计 204 slice/50928000 ns ✅；idle 7.1%（3617928/50928000）✅；L-wake-k 614730/1 暴露 §3.2 表误（已修）。
- q6 两 waking：Reader rel 6787426919 cpu4 发射 kworker/4:1(26591) wakee1489 target3 ✅；主线程 rel 6796227034 cpu3 发射 InputReader(1489) wakee756 target2 ✅；窗 2 起点周边无 waking ✅。
- q2 CPU2 双窗：窗 1 12 线程 49 slice 合计 9313231（surfaceflinger 2666615/6 等逐行）✅；窗 2 20 线程 106 slice 合计 17289307（UsbFfs-worker 2659077/14 等前 12 行＋另 8 线程各 ≤339192）✅；两窗无 swapper ✅。
- q12 Launcher S：6779836842 dur 50928000 S，S 前 Running 6779734457 cpu4，S 后 R 33923→Running 6830798765 cpu4 ✅。
- q14 九行 S blocked_function 全 NULL（含 Launcher 50928000/Dispatcher 50445654/Reader 3852692/8639231）✅。
- q9 抢占 wake：6805690149 sched_waking cpu2 发射 tid756 自身 wakee1136(tbox_ap) target2 ✅；窗 2 边界无 wakee26884 waking ✅。
- q7 边界三切换：6812717803 PowerManagerSer prev_state1→756；6812790111 756 prev_state0→UsbFfs-worker；6812884265 UsbFfs-worker prev_state256→surfaceflinger；166μs（6812884265−6812717803=166462）✅。
- 算术 python 闭合：19231（6805709380−6805690149）/72308（6812790111−6812717803）/50894077（50928000−33923）/S 窗 50928000/R2 17289307/R1 9313231/Reader R 7951192/D 3716500——全 OK。
- grep 重跑：TouchInputMapper.cpp ALOG=79 ✅；inputflinger 目录 pmsg 零命中 ✅；atrace.cpp:132-135 sched_switch/sched_wakeup=REQ、sched_waking/sched_blocked_reason=OPT ✅；GetPmsgFd open("/dev/pmsg0",O_WRONLY|O_CLOEXEC):41/:46 ✅；InputReader ALOG :117/:159/:185/:195 ✅。

### 2.5 修正清单（3 组 4 处定点）
1. §2.2（line 126）":6655/:6879"→":6655/:6883"（preempt_schedule_irq 的 __schedule(SM_PREEMPT) 实 :6883）。
2. §2.6（line 218）":6879 `__schedule(SM_PREEMPT)`"→":6883 `__schedule(SM_PREEMPT)`"（同上，第二处）。
3. §6.3（line 751）prose ":547 取帧起点"→":546 取帧起点"（start_backtrace 实 :546，:547 空行）。
4. §3.2（line 275）"其余 12 线程"组补列 L-wake-k 614,730＋上限"各自 ≤373,346 ns"→"各自 ≤614,730 ns"（L-wake-k 为该组最大，原漏列致上限错；合计 3,684,420/39 slice 不变）。
性质：E1/E2 引文行号（空行误引），E3 数据表命名遗漏＋上限误。**无机制结论错误**——九机制＋三硬结论（状态字符串唯一产生点/S 非等 CPU/D 有 blocked_function S 无）全部维持，代码块逐字内容本就正确。

### 2.6 复核口径声明
- 可接受引文（不修）：§6.3 get_wchan 块省略标记"略 ：535-547"/"略 ：554-561"为区域近似（块内容 :548-555 逐字正确，省略边界含相邻 shown 行属约定容差）；§4.4 sched_energy_enabled 引用范围 :3080-3096（含 #if/静态键/函数/#else）合法；§7.1 balance_fair/newidle_balance 引用 :7155-7162（balance_fair 函数含 newidle_balance 调用 :7161）合法。
- 未证/盲区维持（§10 十一项，不得当缺陷）：S 具体等待条件（syscall/irq 盲区）、sched_wakeup 计数 0 的采集配置原因、target_cpu=3 选核分支、EAS 运行态激活、R 双窗竞争归因（批15 终判）、两次 prev_state=0 的 NEED_RESCHED 来源、R2 终点 pick 触发、D 构成（pmsg_lock vs write_user）、触发 pmsg 的具体 ALOG 语句、负载均衡尝试、CPU4 其他线程运行为构成性观测非 S 成因。
- 本次复核未改动任何源码树；trace/采集文件只读；文章改动 14×3 组（4 处定点）。
- 脚本与数据存档：/tmp/review-applist/check_anchors14.py（143）；复跑批14-q2/q6/q7/q8/q9/q10/q11/q12/q13/q14 留痕 SQL。