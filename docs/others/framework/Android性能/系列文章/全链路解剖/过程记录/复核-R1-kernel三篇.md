# 复核 R1——01/02/03 kernel 三篇全量复核记录（2026-11-14）

复核者：战役协调者（用户令"进行全量复核，尤其是源码原理分析讲解，整个链路正确性"）。
方法：全文逐行读＋行号锚点机械核对（python 逐条对精树取行，±2 行窗口）＋初报 MISMATCH 逐条人工裁决（区间引用/脚本猜测错误不算文章错误）＋机制链逐环对完整函数实现＋grep 类论断全量重跑＋采集数据独立复算＋算术 python 闭合。
基准：kernel `/home/liang/Project/AAOS13_kernel/common`（HEAD 24d27dff64c4，5.15.41）；Android `/home/liang/Project/AAOS13` 精树；采集样本 `20261113-151358-vertical-1912967`。
脚本存档：/tmp/review-applist/check_anchors01.py（95 条）、check_anchors02.py（226 条）、check_anchors03.py（152 条）、rv_packets.py（packet 统计复算）。

## 一、01 篇 virtio_input 前端驱动：机制全过；2 处引文瑕疵已修

### 1.1 核心机制链逐环验证（全部已证）
| 环 | 文章论断 | 精树验证 | 判定 |
|---|---|---|---|
| 1 | wire 事件无时戳字段 | include/uapi/linux/virtio_input.h:70-74 struct virtio_input_event 仅 type/code/value | ✅ |
| 2 | 回调运行于硬中断 | virtio_pci_common.c:339-340 request_irq flags=0（无 IRQF_ONESHOT/threaded）；:82-100 vp_interrupt→vring_interrupt；virtio_ring.c:2147-2168 同步调 vq.callback | ✅ |
| 3 | 解锁窗口防自旋死锁 | virtio_input.c:43-48 unlock→input_event→re-lock；INPUT_PASS_TO_DEVICE→dev->event→virtinput_send_status:92 再取 vi->lock | ✅ |
| 4 | input core 无队列/定时/睡眠 | EV_ABS/EV_SYN 路径全同步；唯一定时器 autorepeat 仅 EV_KEY（input.c:179 input_repeat_key） | ✅ |
| 5 | eventTime 由 evdev 现场取时 | input.c:2055-2062 input_get_timestamp→ktime_get()(:2059)；evdev.c:300 调用 | ✅ |
| 6 | 注册打印与设备名 | input.c:2323-2325 pr_info "%s as %s"；virtio_input.c:247-257 name 装配 | ✅ |

### 1.2 锚点与修正
95 条锚点全部通过（初报 MISMATCH 均为脚本猜测行号偏差，文章区间引用正确：:143 函数体、:302-314 ABS 装配段、input.c:179 函数名 input_repeat_key）。
修正 2 处：
1. §4.6 省略标记 `略 ：304-308`→`略 ：303-308`（for 循环头在 :303）。
2. §9.2 第 2 条 grep 描述重写：原"唯一命中:70"不准确（:70 命中的是 time 而非 timer 模式）；改为"大小写敏感零命中，大小写不敏感仅 :357/:403 两处 #ifdef CONFIG_PM_SLEEP 编译守卫"。

## 二、02 篇 input_core 核心层：机制全过；4 处引文瑕疵已修

### 2.1 锚点机械核对
226 条锚点，202 条直接命中；24 条初报 MISMATCH 人工裁决结果：
- 脚本猜测错误（文章正确）20 条：input.h:37（value 在 :36，__s32）、:172（repeat_key）、:44（enum 收尾）、:341（doc 注释起）、input.c:2291（estimate 调用）、:2302（注释起）、:2328（文章逐字引 mutex_lock_interruptible ✅）、sysrq.c:1015（ids 表起，KEY_LEFTALT 在 :1020）、input.c:2515（文章正确引 list_add_tail_rcu handler 侧）、evdev.c:489（文章正确引 evdev_attach_client）、input.c:618（文章正确引 handle->open++）、:738（going_away 赋值点）、evbug 路径（文章 drivers/input/evbug.c 正确）、input.c:383/:389（区间正确）、:317（见下修正 2）、input-mt.h 路径（文章 include/linux/input/mt.h 正确）、input.c:88（注释起）、evdev.c:465（函数起）、:957/:713（分支区间正确）、virtio_mmio.c:300（if 头，调用 :301-304）、virtio_input.c:188（声明行，区间 :188-194 正确）、gki_defconfig:377（文章正确写 "# CONFIG_VT is not set"）。
- 确认文章错误 4 条（见 2.3）。

### 2.2 机制链与 grep 类论断全量复验（全部通过）
- input_handle_event 全函数（:367-419）逐行核对：inhibited 门 :373、disposition :376、randomness :377-378、PASS_TO_DEVICE :380-381、vals 空检查 :383-384、SLOT 合成 :389-394、攒批 :396-399、FLUSH :402-405、timestamp 重置 :412、强制 flush :413-417——与文章 §6.2 逐字一致。
- input_get_disposition 全表（:261-365）14 分支行号逐一核对一致；EV_KEY value==2 直通 :288-291、位去重 :293-297 引文逐字正确。
- input_handle_abs_event（:211-259）：SLOT 暂存 :218-227、pold 三分支 :229-241、defuzz :243-245、值去重 :246-249、slot 冲刷 :252-256 全对。
- input_pass_values（:128-164）grab/h_list/open/break/autorepeat 双位判据 :154-156 全对；input_to_handler（:93-121）filter 压缩/events 优先全对。
- handler 注册点 grep 全树＝10 处，与文章 §5 表格行号逐一一致（evdev:1437/sysrq:1039/kbd:1658/kgdboc:94/mac_hid:170/mousedev:1104/joydev:1079/apm-power:109/input-leds:207/evbug:91）。
- .filter 字段 grep＝仅 sysrq.c:1026 与 mac_hid.c:155 两处 ✅；handler->filter 调用点仅 input.c:102（:100/:1266/:2502 是 if 测试非调用，:1266 在 procfs show）✅。
- virtio_input.c grep input_set_events_per_packet/hint/.open/rep[ 全零 ✅。
- input.c timer 类 grep 命中分类：代码 :76/:78/:85/:179/:181/:197/:1920/:2192/:2220/:2222-2223，注释 :1966/:2215/:2303/:2451——与文章 §10.2 逐字一致 ✅。
- defconfig：EVDEV=y:369、MAGIC_SYSRQ=y:679、"# CONFIG_VT is not set":377 ✅；JOYDEV/MOUSEDEV/EVBUG/LEDS/MAC_EMUMOUSEBTN 在 configs/ 全目录（含 fragment）零命中 ✅；vt/Makefile:7-8 obj-$(CONFIG_VT) 含 keyboard.o ✅。
- 匹配链 input_match_device_id :975-1008（ID 四开关 :978-992、十类 bitmap_subset :994-1005）、evdev_ids 通配 :1417-1420、sysrq KEY_LEFTALT :1020 全对。
- input_set_timestamp 调用点穷尽＝:193（repeat）与 :2060（get_timestamp 内部）两处（文章原表述见修正 3）。

### 2.3 采集数据独立复算（rv_packets.py，全部吻合）
1195 packet；1153×2EV_ABS＋41×1（8 单 X/33 单 Y）＋1×4（TRACKING_ID/X/Y/BTN_TOUCH DOWN）；0 空包；2350 EV_ABS＋1 EV_KEY＋1195 EV_SYN 全 SYN_REPORT；ABS_MT_SLOT 0 条；value==2 0 条。
算术：10+1+60+7=78→max_vals 80 ✅；1153×2+41+3=2350 ✅；1153+41+1=1195 ✅。
input-devices.txt:44-52 核对：KEY 仅 BTN_TOOL_FINGER/BTN_TOUCH、ABS 六轴 :45-50 全 fuzz 0、ABS_MT_SLOT max 9、INPUT_PROP_DIRECT ✅。

### 2.4 修正清单（4 处）
1. §5 编译清单：sysrq_init 区间 :1188-1195→:1188-1196；device_initcall :1196→:1197（实际 :1197）。
2. §6.3 EV_REL 行：backtick `value != 0` 为意译，改"value 非零（:317，条件写作 `&& value`）"（引文纯净）。
3. §9.2：":193 是 input_set_timestamp 在 input.c 事件路径上的唯一调用点"不精确（:2060 在 input_get_timestamp 内亦为调用点且被 evdev_events 触达）；改为"事件处理函数内的唯一直接调用点；另一调用点 :2060 位于 input_get_timestamp 辅助函数内部，即第 7.3 节的现场取时"。
4. §13 B33 证据列：input.c:376-378→:377-378（:376 是 disposition 调用行）。
性质：全部为引文行号/表述精度问题，无机制性错误。

## 三、03 篇 evdev 用户态接口：机制主线全过；1 处机制错误＋6 处引文瑕疵已修

### 3.1 锚点机械核对
152 条锚点（kernel＋Android 双树），147 条直接命中；5 条初报 MISMATCH 裁决：
- evdev.c:959（evdev_set_mask 即 EVIOCSMASK 实现，区间 :959-992 正确）、:1102（EVIOCGMASK case，:1102-1124 覆盖两 case 正确）、input.h(uapi):33（#else 行正确）——脚本猜测偏差，文章正确。
- evdev.c:1316（evdev_mark_dead 而非 evdev_disconnect 本体）→归属精度修正。
- input.c:2401（空行；attach 循环实际 :2406-2407）→行号错误修正。

### 3.2 机制链逐环验证（全部已证）
- 写入侧：evdev_events :295-313 一次取时(:300)→grab 判定(:304)→逐 client 拷贝(:308-310)；evdev_pass_values :248-290 时戳换算(:260-262)→buffer_lock(:265)→过滤(:268)→空 SYN 丢弃(:271-274)→__pass_event(:282)→锁外唤醒(:287-289)；__pass_event :215-246 写入(:221)→溢出覆盖(:224-240)→packet_head 仅 SYN_REPORT 推进(:242-245)。全部逐行核对一致。
- 读取侧：evdev_fetch_next_event :544-559 判据 packet_head!=tail(:551)；evdev_read :562-611 入参校验(:571-572)/EAGAIN(:578-580)/count==0(:582-587)/容量循环(:589-596)/有读即返(:598-599)/阻塞等待(:601-607)；evdev_poll :614-630 同判据(:627-628)。全对。
- SYN_DROPPED 产生点穷尽：grep 全文件写入点仅 :153（__evdev_queue_syn_dropped）与 :235（__pass_event）两函数，三路径（溢出/get_val 失败补偿 :914-916/时钟切换 :204-207）✅。
- 时钟链：ktime_get timekeeping.c:818-835 读基 tkr_mono.base(:829)；posix-timers.c:204-207 return ktime_get()、:1318-1321 clock_monotonic.clock_get_ktime 挂接 ✅；input_set_timestamp 三槽 :2040-2046、BOOT=ktime_mono_to_any(TK_OFFS_BOOT):2044-2045 ✅。
- 消费端：EventHub processEventTimestamp :177-193 引文逐字一致；configureFd :426-448（KEYBOARD EVIOCSREP :431、MONO :440、SENSOR BOOTTIME :444、EVIOCSCLOCKID :446）；TOUCH 判定 :2090-2096；read 形态 :1678-1712（when :1699/readTime :1700/capacity 回退 :1708-1712）；InputDevice.cpp:394-415 恢复协议引文逐字一致。
- getevent.c：open :324 O_RDONLY 阻塞、poll :658、read :666 单事件、printf :671-672、print_event :676、hex 兜底 :268、setbuf :534、EVIOCGSW :643 全对；主循环无 SYN_DROPPED 状态机 ✅。
- grep 类：getevent/EventHub 无 EVIOCSMASK/EVIOCGRAB/EVIOCGMTSLOTS（计数 0/0）✅；EVIOCGRAB 全树代码调用者仅 system/teeui/libteeui/src/evdev.cpp（:198 grab/:213 ungrab；sepolicy 命中为宏定义非调用）✅。

### 3.3 采集数据独立复验（全部吻合）
touch.txt 2,034,287 字节 ✅；SYN_DROPPED 计数 0 ✅；event0 EV_SYN 1195 ✅；全节点非 SYN_REPORT 的 EV_SYN 0 ✅；首 packet :1025-1029 五行同时戳 6069.677128 ✅；:13-14 add device 7 event0 virtio_input_tp0 ✅；:12419-12421 三事件 MOVE 包 ✅；:12498-12501 单 X 包＋event4/event5 交错 ✅。
CSV：表头 relative_trace_assuming_clock_s ✅；首行 −4.017316=6069.677128−6073.694444 ✅；行 648 "6.787410,6080.481854,37.237,727,245" ✅。

### 3.4 修正清单（7 问题 9 编辑）
1. **§3.3 第 4 步机制错误**：原文"input_open_device……并调 handler 的 start 回调（input.c:573 判据）"——input_open_device 不调 handler->start；:573 实为 __input_release_device（grab 释放路径）的 start 判据。已改为 dev->open 回调（:627-628）并补注 start 的真实调用点（input_register_handle :2517-2518 与 __input_release_device :573-574；evdev_handler 无 .start）。
2. §3.1：input_register_handler 区间 :2393-2411→:2393-2413；attach 循环 :2401-2403→:2404-2407（list_add_tail :2404、循环 :2406-2407）；B1 证据列同步。
3. §2.1：exist 置 false 归属补全链条 evdev_disconnect:1406→evdev_cleanup:1327→evdev_mark_dead:1316-1321（赋值 :1319）。
4. §2.3：省略标记 略:32-42→略:32-43（:43 #endif 亦在省略段）。
5. §6.1：generate-input.h-labels.py 正则 :40→:39。
6. §6.4：EventHub 溢出评估"≥约 200 ms"与同节算术（32×16.347≈523 ms）不一致→"≥约 520 ms"并补算式。
7. §3.2：input_get_new_minor 实现区间 :2562-2581→:2562-2582；legacy ida_simple_get :2570-2572→:2571-2574；B2 证据列同步。
性质：1 处机制性错误（start 回调归属，已修）＋6 处引文精度问题；主线机制（一次取时/逐 client 拷贝/packet 判据/溢出必然留 SYN_DROPPED/时钟同源）全部通过。

## 四、复核口径声明
- 未证边界维持（不得当缺陷）：host 列举顺序/报点节奏成因、event0 确切 bufsize（host 轴集合盲区）、EventHub client 溢出与否、全程逐点跨时钟域校验、设备二进制与精树一致性、android_vh 钩子启用状态、传输类型 mmio/pci 选择。
- 本次复核未改动任何源码树；trace/采集文件只读；文章改动仅上述 15 处编辑（01×2、02×4、03×9）。
- 三篇跨篇衔接核对：01§8.2 时戳承诺→03§6.3 兑现 ✅；01§9.1 event0 序号承诺→03§3.2 兑现 ✅；02§7.3 evdev 端点引用→03§4 展开 ✅；02§10.2 A19 input core 段裁决→03§7.2 划界一致 ✅。
- 脚本与数据存档：/tmp/review-applist/（check_anchors01/02/03.py、rv_packets.py）。