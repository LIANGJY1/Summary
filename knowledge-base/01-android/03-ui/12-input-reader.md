# 设备接入与 InputReader：内核 input 事件、设备分类与触摸适配

> 学习资料（文章模式沉淀）。主线：内核 input 子系统与 evdev、`input_event` 的事件语义、多点触控协议 Type A/B、EventHub 的设备发现与热插拔、能力位推断设备分类、mapper 族分派、触摸屏与显示器的绑定、触摸坐标的校准与旋转变换、虚拟按键、旋钮编码器接入、内核重复与 Android 重复的关系、SYN_DROPPED 缓冲溢出、鬼触摸与断触的成因对策、触控 IC 调试入口与热力图管道。机制按本地 AAOS13 源码（Android 13，`frameworks/native/services/inputflinger/reader/`、`bionic/libc/kernel/uapi/linux/`）核对；多点触控协议与内核驱动（gpio-keys、rotary-encoder、adc-keys）为 kernel.org 官方文档口径（2026-09 检索，本地树未含内核源码）；触控 IC 实战一节为厂商驱动与社区资料结论，证据等级低于其余条目。2026-10-04 复核：重申 Type A/Type B 的内核协议边界，统一有序支持点格式。按键映射文件见 [13-key-mapping.md](./13-key-mapping.md)；分发链路总览见 [10-input-system.md](./10-input-system.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 一个触摸/按键事件从硬件到 InputReader 经过内核哪些环节？**

路径是：触控 IC/按键控制器驱动 → 内核 input core → evdev handler → `/dev/input/eventX` 字符设备 → 用户态读取。驱动用 `input_allocate_device()`/`input_register_device()` 注册一个 input 设备并上报事件；input core 负责设备与 handler 的匹配连接；evdev 为每个打开的文件句柄维护独立的环形事件缓冲（量级为数十个事件包），用户态消费不过来时溢出，内核以 `SYN_DROPPED` 标记重同步。Android 侧的读取者是 InputFlinger 进程内的 EventHub 线程，它把裸事件包装成 `RawEvent` 交给 InputReader。

`input_event` 结构是四字段的定长记录：`time`（时间戳）、`type`、`code`、`value`，按本地 UAPI 头核对（`bionic/libc/kernel/uapi/linux/input.h`），64 位平台上该结构 24 字节（时间被拆成秒/微秒两个字段），这决定了用 `hexdump` 直接解析 `/dev/input/eventX` 时的字节布局。

理解这条链的排查意义在于分段：`getevent` 有输出说明驱动与内核段通了；InputReader 没消费通常是设备分类或配置问题；而"事件有了但坐标乱"则进入校准与绑定环节——每一层有各自独立的失效方式。

**Q2: [learning] `input_event` 的 type/code/value 各自什么语义？一帧触摸由哪些事件组成？**

`type` 是事件大类：`EV_SYN`（同步，0x00）、`EV_KEY`（按键/触点状态，0x01）、`EV_REL`（相对位移，0x02）、`EV_ABS`（绝对坐标，0x03）、`EV_SW`（开关，0x05）、`EV_LED`、`EV_FF`（力反馈）等（码值按本地 `input-event-codes.h` 核对）。`code` 是大类内的具体项，`value` 的语义随 type 变化：`EV_KEY` 的 value 0/1/2 表示释放/按下/自动重复；`EV_REL` 是增量；`EV_ABS` 是当前绝对值（轴的 min/max/fuzz/flat/resolution 由 `input_absinfo` 描述）；`EV_SW` 是开关状态。

一次触摸"帧"以 `EV_SYN/SYN_REPORT` 收尾：驱动先发若干坐标与状态事件（`ABS_MT_POSITION_X/Y`、`BTN_TOUCH` 等），最后一条 `SYN_REPORT` 表示"此刻的快照完整"。多点协议里还有 `SYN_MT_REPORT` 作为单触点的分隔符（Type A 专用）。接收端看到 `SYN_REPORT` 才处理积累的事件，之前的事件只是"草稿"。

`getevent -l` 输出里的每个三元组就是一条 `input_event`：例如 `0003 0035 000003d4` 是 `EV_ABS`、`ABS_MT_POSITION_X(0x35)`、值 980。读懂三元组是触摸驱动联调的基本功——上游固件改报点协议时，第一验证点就是这层输出变了什么。

**Q3: [learning] 多点触控协议 Type A 与 Type B 有什么区别？Android 怎么判断驱动用的是哪种？**

Type A 无硬件追踪能力，每帧重发全部触点，触点间用 `SYN_MT_REPORT` 分隔、帧尾 `SYN_REPORT` 收尾，接收端只能按"位置相似"猜测哪些样本属于同一根手指；Type B 硬件维护触点身份，用 `ABS_MT_SLOT` 选择槽位、增量更新，触点抬起时把该槽的 `ABS_MT_TRACKING_ID` 置 -1，接收端按槽位稳定跟踪。按 kernel.org 官方文档结论，Type A 已废弃、内核驱动已全部迁移到 Type B。两者的触点身份标识也不同：Type A 的 `BLOB_ID` 仅用于分组，不能当追踪 ID 用。

Android 侧的判定在 `MultiTouchInputMapper`：设备同时具备 `ABS_MT_TRACKING_ID` 与 `ABS_MT_SLOT` 能力位就用 Protocol B，否则按 Protocol A 处理；槽位上限为 32，驱动声明更多会被裁剪（按 AAOS13 源码核对）。Protocol B 的累加器还有一个启动细节：configure 时先向驱动查询当前槽号作为初始槽，否则 evdev 缓冲里残留的旧事件会被并进错误的槽、两根手指的数据串在一起——源码注释明确说宁可首帧跳一下也不要卡住一根手指。

驱动联调含义：Type B 驱动必须正确维护 tracking id 的分配与 -1 释放，漏发释放会导致 Android 认为手指一直按着（"幽灵触点"）；报点数超过声明槽数会触发裁剪丢点。

**Q4: [learning] EventHub 怎么发现和增删设备？热插拔依赖什么机制？**

EventHub 用一路 epoll 同时监听三类文件描述符：全部已打开的 evdev 设备 fd、inotify fd、唤醒管道，且都带 `EPOLLWAKEUP`，保证休眠路径上事件不丢（按 AAOS13 源码核对，`EventHub.cpp`）。热插拔靠 inotify 监听 `/dev/input` 与 `/dev` 目录的创建/删除事件：新 evdev 节点出现即打开、读能力位、加载配置并通知 InputReader 添加设备；节点删除则走移除流程。读取本身是对 evdev fd 的裸 `read()`，读到缓冲后转成 `RawEvent`。

这套机制的效果是：蓝牙键盘连接、USB 触控板插入、虚拟设备（uinput）注册，都在下一轮 epoll 唤醒中被捕获，无需 Android 侧轮询。设备添加时机也解释了一个现象：开机瞬间显示器信息可能尚未就绪，触摸设备的视口解析会失败，InputReader 此时把设备置为禁用、等信息齐了再自动复活，这不是故障。

排查热插拔问题时，`dumpsys input` 的 EventHub 段列出当前全部设备与身份信息（bus/vendor/product/version/name），对照 `getevent -il` 可以确认"系统看到的设备"与"内核暴露的设备"是否一致——不一致通常是 SELinux 或权限导致 EventHub 打不开节点。

**Q5: [learning] Android 怎么判断一个 `/dev/input` 设备是触摸屏、键盘还是旋钮？**

全部靠能力位推断，不依赖驱动自报类型（按 AAOS13 源码核对，`EventHub.cpp` 用 `EVIOCGBIT` 系列读事件能力位、`EVIOCGPROP` 读设备属性位）。主要判定规则：

1. **键盘类**：按键位命中 0 至 `BTN_MISC` 或 `BTN_WHEEL` 至 `KEY_MAX` 区间视为键盘；`BTN_MISC..BTN_MOUSE`、`BTN_JOYSTICK..BTN_DIGI` 归为手柄/鼠标键；
2. **触摸屏三岔**：有 `ABS_MT_POSITION_X/Y` 且带 `BTN_TOUCH`（或无游戏手柄键）判为多点触摸屏；只有 `BTN_TOUCH + ABS_X + ABS_Y` 判为旧式单点触摸屏；只有压力/`BTN_TOUCH` 而无坐标判为外部触笔，且会从该设备上摘掉键盘分类（按键位留给触笔融合用）；
3. **旋钮类**：事件位判断不了，唯一的依据是 `.idc` 里 `device.type = rotaryEncoder`；
4. **其他**：有力反馈位判为振动器，有开关位判为 Switch 设备，带加速度计属性位判为传感器；加载了虚拟按键定义的触摸屏会被追加键盘类（虚拟键要以按键事件交付）。

分类错了后续全错：旋钮没配 `.idc` 就不会产生旋钮事件；触控固件升级后能力位变化可能导致设备被重新分类。排查设备识别问题的入口就是 `dumpsys input` 的 `Events`/`Input props` 段——它打印的就是这套能力位。

**Q6: [learning] 设备分类之后由谁来处理？mapper 族是怎么对应的？**

InputReader 为每个设备按分类挂一组 `InputMapper`，各管一类事件（对应关系按 AAOS13 源码核对，`InputDevice.cpp` 的创建逻辑）：多点触摸屏由 `MultiTouchInputMapper`、单点触摸屏由 `SingleTouchInputMapper` 处理（两者共享 `TouchInputMapper` 的坐标变换与视口逻辑）；`EV_KEY` 按键设备由 `KeyboardInputMapper` 处理；相对位移设备（鼠标/滚轮）由 `CursorInputMapper` 处理；开关设备、力反馈设备、传感器设备、外接触笔、摇杆分别有专用 mapper；旋钮编码器由 `RotaryEncoderInputMapper` 处理。

mapper 的产物是统一的 `NotifyArgs`（`NotifyKeyArgs`、`NotifyMotionArgs` 等），经监听链（Android 13 为 `InputReader → UnwantedInteractionBlocker → InputClassifier → InputDispatcher`）交给分发器（InputDispatcher）。同一设备可以挂多个 mapper（如带滚轮的键盘既是 Keyboard 又是 Cursor），事件按能力分流；游戏手柄是"一设备多 mapper"的典型——按钮走 `KeyboardInputMapper`（合成 `KEYCODE_BUTTON_*`）、摇杆与扳机走 `JoystickInputMapper`（轴值归一化加死区）、震动效果经 `VibratorInputMapper` 写回设备，三路事件并行产生。

这个分层意味着：触摸问题的责任面是 `TouchInputMapper` 及其上游能力位，按键问题的责任面是 `KeyboardInputMapper` 加 `.kl` 映射——"触摸坏了改键盘配置"这类无效操作源于没分清 mapper 边界。

**Q7: [learning] 触摸屏怎么绑定到具体哪块屏幕？车机仪表屏/副驾屏的触控为什么不能接反？**

绑定发生在 InputReader 的视口解析，有三个来源（按 AAOS13 源码核对，`TouchInputMapper.cpp`）：设备分类内建判定（触摸屏/指针设备且 `orientationAware`）、`.idc` 的 `touch.displayId` 显式指定显示唯一标识、设备节点的物理位置（location/port）命中显示端口关联表——第三条是车机多屏的关键，仪表屏与中控屏的触摸设备靠端口名绑到各自视口。查找视口时按四级优先：设备已关联的视口 → 指针设备用 WMS 建议的屏 → idc 唯一标识精确查 → 内建/外置类型查，命中即用。

绑错的直接后果是"在这块屏上按，那块屏响应"：InputReader 把坐标映射到错误视口后，事件带着错误 displayId 走完全相同的分发链路，没有任何一层会纠正。接反的根因通常在配置：显示端口的顺序依赖、`touch.displayId` 写死成另一块屏的 uniqueId。

还有一个容错机制要知道：视口解析失败（如开机时显示信息未就绪）时设备被置为 DISABLED，等下一轮 configure 自动恢复，不算永久故障；但按唯一标识查屏失败不会回落到类型匹配，会持续禁用——所以 idc 里的 uniqueId 写错比不写更糟，症状是"这块屏永远没触摸"且重启无效。

**Q8: [learning] 触摸坐标从驱动原始值到应用屏幕坐标经过哪些变换？**

变换链固定为两步：先做厂商仿射校准，再做屏幕旋转变换（按 AAOS13 源码核对，`TouchInputMapper.cpp`，顺序由源码注释明确——反过来会把旋转变形混进校准矩阵，畸变参数再也调不准）。仿射校准的六参数（`x_scale/x_ymix/x_offset/y_xmix/y_scale/y_offset`）由框架策略按设备描述符与当前朝向查询，Java 侧入口是 `InputManagerService.setTouchAffineTransformation`，`dumpsys input` 的 Affine Transformation 段可直接看到当前矩阵。

旋转变换随显示方向走：90° 时新 X 由旧 Y 换算、新 Y 由旧 X 翻转换算（180°/270° 类推），保证应用收到的坐标恒在当前显示坐标系。设备自身贴装方向与显示不一致时用 `.idc` 的 `touch.orientation = ORIENTATION_90/180/270` 先声明设备朝向；`touch.orientationAware` 决定屏幕旋转时输入是否跟随旋转（触摸屏默认开启）——车机仪表屏"竖屏面板横屏用"就是靠这两个配置组合适配。

排查"触摸偏了/转屏后触摸错位"时按链定位：`getevent` 原始值正确而应用坐标错，问题在变换链——先检查屏幕旋转状态下的表现，再看仿射矩阵是否为出厂校准值，最后怀疑面板装反（用 `touch.orientation` 修正而不是改驱动坐标）。

**Q9: [learning] 虚拟按键（virtual keys）是什么？怎么定义与排查？**

虚拟按键是触控面板上划出的固定区域，按下去不产生触摸事件而产生标准按键事件——无实体按键的设备用它实现返回/Home/菜单条。定义放在 sysfs 文件 `/sys/board_properties/virtualkeys.<设备名>`，每行格式为 `0x01:扫描码:中心X:中心Y:宽:高`（类型 0x01 固定表示按键），由 EventHub 读取后注册（按 AAOS13 源码核对，`EventHub.cpp` 与 `libs/input/VirtualKeyMap.cpp`）。

两个容易踩的实现细节：坐标是显示坐标而非驱动原始坐标，屏幕旋转后虚拟键矩形需要按方向重算，否则出现"旋转后按键位置不对"；虚拟键的扫描码同样要经 `.kl` 映射成按键码，映射缺失时按了没反应。加载了虚拟键的设备会被追加键盘分类，因此虚拟键问题既涉及触摸配置又涉及按键映射。

排查顺序：`getevent` 确认按虚拟键区域时有 `EV_KEY` 事件（说明 sysfs 定义生效且驱动转发正常）→ `dumpsys input` 看设备是否带虚拟键与最终按键码 → 应用层确认键码处理。区域偏移（按 A 出 B）改 sysfs 定义或旋转重算，而不是改应用逻辑。

**Q10: [learning] 旋钮作为 Linux 输入设备怎么接入？与 VHAL 旋钮是同一条链路吗？**

不是同一条链路，两条独立路径并存。物理旋钮接成 Linux rotary encoder 设备后，`RotaryEncoderInputMapper` 把每个同步帧的 `REL_WHEEL` 值乘以 `.idc` 的 `device.scalingFactor`，生成带 `AXIS_SCROLL` 轴的 `ACTION_SCROLL` MotionEvent，source 为 `SOURCE_ROTARY_ENCODER`；不关联任何显示屏（displayId 为 NONE），外部旋钮可带唤醒标志。屏幕旋转 180° 时 scroll 值取反（修正物理装反方向），90°/270° 不处理——旋钮是相对量，没有"上下颠倒"的概念（按 AAOS13 源码核对，`RotaryEncoderInputMapper.cpp`）。

VHAL 路线则是旋钮信号经车控写进 `HW_ROTARY_INPUT` 属性，由 CarService 转成 `RotaryEvent` 或按键。两条链路的事件类型、source、消费方都不同：Linux 设备链的 `AXIS_SCROLL` MotionEvent 走标准焦点窗口分发，AOSP RotaryController（无障碍服务）与声明了 rotary scroll 的可滚动容器消费它；VHAL 链的 `RotaryEvent` 走 CarService 捕获仲裁。调试时必须先确认手上的旋钮走哪条链——`getevent` 有输出走设备链，没有输出而 `dumpsys car_service` 有 VHAL 上报则走车控链。

驱动侧接入 rotary encoder 的标准做法是内核 `rotary-encoder` 驱动（官方文档口径）：设备树声明步数、相对轴模式等参数，驱动产出 `REL_WHEEL`/`REL_X` 事件。板级差异（部分旋钮按下是独立 GPIO）在设备树组合解决。

**Q11: [learning] 内核的按键重复与 Android 的 key repeat 是什么关系？为什么会"双份重复"？**

Android 有意关掉了内核重复：EventHub 打开键盘类设备时下发 `EVIOCSREP` 把内核的重复延迟/间隔清零，长按重复改由 InputDispatcher 在框架侧合成（首条延迟取长按超时、间隔约 50 ms）。原因是框架要按系统设置统一管理重复节奏，内核级的重复节奏无法被系统设置与策略控制（按 AAOS13 源码核对，`EventHub.cpp` 打开路径与 `KeyboardInputMapper.cpp` 的例外处理）。

例外是 `.idc` 的 `keyboard.handlesKeyRepeat = 1`：声明后保留内核重复、框架不再合成。这个配置给特定设备（如自带重复节奏的专用键盘）保留内核行为，但多数设备不应使用——框架侧的 `repeatCount`、`FLAG_LONG_PRESS` 语义建立在框架合成之上，用内核重复会破坏应用对这些字段的预期。

"双份重复"（按住一个键出现密集连发）的典型成因就是配置漂移：设备配了 `handlesKeyRepeat`，同时应用的逻辑又对 `repeatCount` 做了累加处理，或注入端自己循环发送。排查时先看 `dumpsys input` 里该设备的配置，再看事件流的 `repeatCount` 是否连续——内核重复的序列不带框架语义。

**Q12: [learning] `SYN_DROPPED` 是什么？出现时系统会怎样、该查什么？**

`SYN_DROPPED` 是内核 evdev 在缓冲溢出时插入的特殊同步事件：用户态消费太慢、缓冲写满，内核丢弃积压事件并发出这个标记，要求接收端放弃积累的状态、用 `EVIOCGKEY`/`EVIOCGSW`/`EVIOCGABS` 全量重同步（官方内核文档结论；引入于内核 2.6.39）。Android 侧 EventHub 只透传原始事件、不做重同步，靠 mapper 的 reset 机制兜底——表现为当前手势状态被丢弃重建，极端时应用收到取消。

`getevent` 输出里看到 `0000 0003 ...`（`EV_SYN`、`SYN_DROPPED`）就是发生了溢出。常见诱因：报点率 × 触点数过高（高刷多点场景）、系统负载导致 InputReader 线程饥饿、驱动一次上报的批量过大。对策优先级：降低无效报点（固件侧过滤）、确认 InputReader 线程调度没有被挤占、最后才是加大缓冲（涉及内核侧调整）。

与它症状相似的是"丢点但无 SYN_DROPPED"——那通常是 Type B 驱动的槽位/跟踪 ID 管理错误（漏发释放导致幽灵触点）或槽位数超出上限被裁剪，两者的区分就在 `getevent` 里有没有 `SYN_DROPPED` 标记。

**Q13: [learning] 充电或特定充电器插入时触摸乱跳（鬼触摸），原因与对策链是什么？**

典型成因是电气噪声：充电器的共模噪声经 Y 电容耦合进触控面板的电容检测回路，信噪比骤降后 IC 把噪声判为触点；劣质充电器纹波更大、非隔离 DC-DC 更明显。其他来源包括显示屏（尤其高刷新率模组）与 TP 之间的耦合、地弹、ESD、结构压应力（边框挤压使静态电容漂移）。这些是触控行业的通用结论（厂商应用笔记口径，本地树无内核驱动源码，证据等级为二手）。

对策按层递进：IC 侧开启跳频（在多个发射频率间自动避开干扰频点）、充电检测联动（插入充电器时切高阈值/低灵敏度档）、噪声阈值自适应；硬件侧改进屏蔽与接地、TP 与显示间加屏蔽层。部分厂商驱动会暴露 sysfs/proc 节点动态切换这些模式。

排查口径有规律可循：只在充电时乱跳优先查充电器与跳频配置（换原装充电器复测是第一步）；固定区域乱触怀疑结构压应力；完全随机乱点怀疑 ESD 或地。复现路径要固定（同一充电器、同一亮度、同一界面），否则驱动参数调了也验证不了。

**Q14: [learning] 边缘滑动断触、干手指没反应，是同一类问题吗？怎么分？**

不是同一类，信号幅值与算法抑制是两个不同环节。边缘断触的典型原因是触控面板边缘电场畸变（ITO 走线区信号弱），IC 的边缘抑制算法把这些区域的低质量触点滤掉——表现为手势滑到屏幕边缘丢失；对策是调整边缘补偿参数或放宽边缘区的判定阈值（厂商驱动参数，行业通用结论）。干手指（或手套模式未开）则是触点电容变化量低于阈值，IC 判为未接触；对策是开启手套/高灵敏模式或降低阈值，代价是鬼触摸风险上升。

区分方法看报点流的形态：边缘断触的 `getevent` 序列里坐标沿边缘推进然后整帧消失（触点被算法丢弃）；干触是按压瞬间就没有触点产生（阈值未达）。前者调边缘参数，后者调阈值或模式，改错了方向参数怎么调都无效。

车机场景加两条：贴膜（尤其厚钢化膜）会系统性降低信号幅值，量产前要用目标膜材测；低温环境电容特性变化，低温手套场景要在高低温箱里验证阈值余量，常温调好的参数冬天可能不够。

**Q15: [learning] 触控 IC 与驱动联调有哪些标准入口？**

以主流方案为例（厂商驱动资料口径，本地树未含驱动源码）：读版本与身份——Goodix 部分 GT9xx 型号上电后从对应寄存器读 product id，FocalTech FT 系列有对应的固件版本寄存器，驱动 probe 日志（dmesg 里的 probe 成功/失败、product id、fw version）是第一证据；改配置——该项目所用 GT9xx 型号的配置是一段 186 字节的寄存器数组（灵敏度、跳频、噪声阈值等都在其中），一个常见坑是配置尾部的版本号必须大于 IC 内已存版本否则新配置不生效（"改了参数没效果"先查版本号递增）；调试节点——vendor 驱动普遍暴露 sysfs/proc 节点（fw_version、glove、charger、gesture 等模式开关），量产前这些节点要按安全要求收敛。

通用流程性入口与厂商无关：dmesg 的 input core 注册行（`input: xxx as /devices/.../input/inputN`）确认驱动注册成功；`/proc/bus/input/devices` 的位图与 `getevent -p` 等价；I2C 通信失败（probe 阶段 NACK）先查上电时序与从地址（GT9xx 的中断脚电平还兼从地址选择）。

车机特有的收敛点：触控固件升级走厂商定义的时序握手进 bootloader，失败态要有恢复路径；调试节点与固件升级接口在量产版本要按安全基线关闭或加权限。

**Q16: [learning] 想看到触控 IC 的原始电容数据（热力图），有什么现成管道？**

部分触控 IC 会额外暴露一个带 `V4L2_CAP_TOUCH` 能力的视频节点，输出原始电容矩阵帧。AOSP InputReader 里有配套的 `TouchVideoDevice`：以非阻塞方式打开该节点、`VIDIOC_QUERYCAP` 校验能力、mmap 多缓冲采集热力图帧，帧数据（宽高与原始值）以 `TouchVideoFrame` 结构保存（按 AAOS13 源码核对，`reader/TouchVideoDevice.cpp`）。这是研究 IC 原始感知（触点形状、噪声分布、水膜与压应力特征）的现成管道，不需要在驱动里加自定义接口。

用途定位是研究与疑难分析：驱动工程师定位"IC 看到了什么"（区分是感知问题还是算法问题），算法侧采集数据训练防误触模型。常规功能排查用不到它——`getevent` 的报点流已覆盖绝大多数场景；而且热力图数据量大，开启采集对性能有影响，只在受控调试环境使用。

配套关系要清楚：热力图是驱动的原始感知层，报点流是 IC 算法处理后的结果层。两者对照可以回答"IC 没报点是因为没感知到，还是感知到了但被算法抑制"——这正是鬼触摸/断触定性（硬件问题还是参数问题）的分水岭。

**Q17: [learning] 翻盖、皮套、耳机插拔这类开关（Switch）输入走哪条路？车机为什么不该用它传整车信号？**

开关类输入由内核以 `EV_SW` 事件上报（`SW_LID` 翻盖、`SW_HEADPHONE_INSERT` 有线耳机等），EventHub 归类出 Switch 设备后由 `SwitchInputMapper` 处理：它把开关位图打包成 `NotifySwitchArgs`，经监听链到达分发器的 `notifySwitch`——但这条事件**不进入任何输入队列、不做按键/触摸分发**，而是经 JNI 策略回调直接通知 Java 侧（`InputManagerService.notifySwitch` → WMS → `PhoneWindowManager`）。按 AAOS13 源码核对，这是它与其他输入类型的本质区别：Switch 是"状态通知"不是"交互事件"，没有目标窗口、没有 ANR、不经过焦点。

消费分推、拉两路：推路是 `notifySwitch` 回调，`PhoneWindowManager` 用它维护翻盖状态并按配置联动休眠（合盖睡眠是策略决定，不是驱动行为）；拉路是 `getSwitchState()` 查询，`WiredAccessoryManager` 初始化时就用 `getSwitchState(-1, SOURCE_ANY, SW_HEADPHONE_INSERT)` 拉一次耳机状态再靠回调增量更新。调试入口与设备链一致：`getevent -S` 列出全部开关当前值、`-s <位>` 查单个开关，`dumpsys input` 可见 Switch 设备与状态。

车机边界要划清：门锁、挡位、ACC 等整车状态应建模为 VHAL 属性，再由 CarService/车辆应用按车辆语义消费；不要把它们伪装成 `EV_SW`。Switch 通道是 Linux 输入状态通知，没有显示路由、焦点捕获或车载信号语义；真实物理开关（如翻盖状态）则适合走 Switch 通道，系统已有状态查询与策略联动路径。
