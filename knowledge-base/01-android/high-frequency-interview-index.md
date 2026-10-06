# 面试高频索引

> 编排产物（非蒸馏，无 Q 块）：把本目录的高频面试主题按热度编排成"主题 → 册·Q"速查。热度为通用 Android/AAOS 面试经验判断：★ 必备（几乎每轮会问）、★★ 高频（大厂/平台岗常问）、★★★ 加分项（有区分度）。2026-10-04 学习顺序重排后，册名和题号按本轮映射更新；2026-10-05 QA 题库沉淀的新增题已并入对应条目；2026-10-06 章 12 章重排后路径与册号已同步（输入册为 03-ui/10–16）；检索仍以 atlas 全文为准。AAOS 岗位重点见文末专项段。

## ★ 必备热题

1. **Handler/消息机制与卡顿**：02-handler-looper Q6–Q7；02-handler-looper Q8–Q9；02-handler-looper Q10–Q11；08-app-thread-ipc-stability Q21；03-binder Q7；03-binder Q8（队列/锁/futex）；12-performance/07-app-startup-optimization Q10（IdleHandler）
2. **Binder 架构与"一次拷贝"**：03-binder Q1；Q18；缓冲区与线程池细节见 03-binder Q2；Q10；03-binder Q3；03-binder-driver Q2
3. **ANR 家族与定位**：08-anr 全册（29 题）；入门对照 02-app-framework/08-app-thread-ipc-stability Q14
4. **内存泄漏/OOM/FD**：06-memory-storage/03-app-memory-stability 全册；内存口径见 06-memory-storage/01 Q1–Q2
5. **启动优化与启动指标**：12-performance/07-app-startup-optimization 全册；平台侧见 12-platform-optimization Q2；12-performance/03-performance-tools Q38；12-platform-optimization Q3；02-system-boot Q22
6. **进程被杀/保活**：12-performance/22-app-power-governance Q3–Q5（OEM 差异）、Q26（Freezer 长连接）；06-memory-storage/01 Q15–Q19（lmkd）
7. **渲染卡顿/掉帧**：05-smoothness Q1、Q4；01-render-pipeline-vsync Q32；02-gpu-composition-display Q32–Q33；05-smoothness Q31；01-render-pipeline-vsync Q33；01-render-pipeline-vsync Q34–Q35；04-graphics/01（VSync 调度）
8. **View/Compose 渲染实战**：04-graphics/09–10 与 04-graphics/12–13 按题型查
9. **协程/线程池治理**：02-app-framework/08-app-thread-ipc-stability Q1–Q8；线程优先级与 cpuset 见 12-performance/01 Q2/Q6/Q7
10. **网络与弱网**：11-network-performance；07-network/09-app-network-practice

## ★★ 高频（大厂/平台岗）

1. **五层架构与进程边界**：01-system-architecture Q1；Q4；01-system-architecture Q11–Q12；01-system-architecture Q13；system_server 内容物见 04-system-server Q1
2. **Zygote 与 fork 模型**：02-system-boot Q19–Q27（Zygote 角色、启动、预加载、fork 与进程模型）
3. **system_server 运行机制**：04-system-server Q2–Q3（创建与装配）；Q5–Q6（Watchdog 看护与恢复）；Q8–Q11（线程纪律、AMS 双锁与 WMS/SurfaceFlinger 边界）；02-system-boot Q30（systemReady）
4. **init/启动链**：02-system-boot Q1–Q2；02-system-boot Q3–Q4；02-system-boot Q5–Q6；02-system-boot Q7–Q8；02-system-boot Q9–Q10；02-system-boot Q11–Q12；02-system-boot Q13–Q14；02-system-boot Q15–Q16；02-system-boot Q17–Q18（启动链与 init 各阶段）；2026-10-06 扩充 Q47–Q96（boot 镜像与 AVB/dm-verity/动态分区 Q47–Q54、A/B slot 状态机与 OTA 回退 Q53/Q55、recovery/BCB/充电模式 Q56–Q57、servicemanager 与启动顺序 Q58–Q59、init 机制层 Q60–Q73、度量与杂症 Q74–Q96）
5. **应用进程诞生**：02-system-boot Q29；应用侧冷启动指标见 12-performance/07-app-startup-optimization Q1–Q2；12-performance/07-app-startup-optimization Q3–Q4
6. **SELinux 拒绝与策略书写**：11-selinux 全册 28 题（基础概念与开发者视角 Q1–Q5；avc 与启动期校验 Q6–Q8；规则、标签与属性配置 Q9–Q14；service_manager 检查和案例 Q15–Q23；ioctl、工作模式与验证 Q24–Q28）；启动期装载见 02-system-boot Q11
7. **多进程/多用户**：09-app-sandbox Q3（UID 公式与跨用户）；12-performance/07-app-startup-optimization Q13；Q16（初始化分流）；02-app-framework/08-app-thread-ipc-stability Q22–Q23（android:process 命名与单进程假设失效）
8. **前台服务与后台限制**：12-performance/22-app-power-governance Q7–Q9、Q27（FGS 类型与 BOOT_COMPLETED 豁免）；02-system-boot Q37
9. **Freezer 冻结机制**：01-system-architecture Q9；01-memory-management Q27；01-memory-management Q28（冻结中 Binder 语义）；12-performance/22-app-power-governance Q26
10. **Native crash 与符号化**：12-performance/13-app-stability Q15–Q20；07-jni Q2；03-binder Q18（RefBase abort）
11. **Watchdog 与软重启**：04-system-server Q5–Q6；启动链死亡层级见 02-system-boot Q42
12. **广播队列与投递**：01-broadcast 全册
13. **线程优先级/实时调度误区**：12-performance/01 Q2/Q6/Q7；01-kernel-gki Q5（vendor hooks 观测）
14. **主线程 Binder ANR 实案**：02-app-framework/08-app-thread-ipc-stability Q14；02-app-framework/09-defect-main-thread-async Q1、Q6–Q7；08-anr Q11、Q17
15. **窗口系统与 relayout**：03-ui/04（窗口三副面孔、addWindow 校验与返回码、relayout 与遍历调度分工、WindowState·Token·DisplayContent、z 序与 surface placement、Insets 体系与 WindowInsetsController）；窗口管理总览另见 04-window-system
16. **Activity 生命周期与首帧**：03-ui/01（生命周期两对正交维度、回调分场景顺序、onPause 时序约束、启动到首帧时序、四个可观测时点、setContentView 与 DecorView、透明主题尺寸陷阱、配置变化与 relaunch、状态保存时机与版本分界、View 层次保存委托链、切换动画双窗口、首帧度量、时序缺陷排查）；启动模式、任务栈与 Intent 匹配见 02-app-framework/10
17. **View 绘制与失效语义**：03-ui/02（MeasureSpec 尺寸契约、layout vs onLayout、requestLayout vs invalidate 分工、display list 与"不 invalidate 就不重绘"、脏区传播、绘制顺序与裁剪、图层类型、SurfaceView vs TextureView、SurfaceView 打洞与 mDrawFinished 边界、TextureView 双队列与 TextureView/GLSurfaceView 帧时序）；渲染实践见 04-graphics/09–10 与 04-graphics/12–13
18. **资源限定符与多屏适配**：03-ui/03（限定符优先级与顺序、默认资源兜底、版本限定符、深色模式三件事、dp·sp·fontScale、限定符≠窗口尺寸、RTL、ViewBinding 联合字段缺失、Drawable 本质与 BitmapDrawable/shape 属性）；组件运行期适配坑见 ../../android-ui.md
19. **事件分发与输入系统**：03-ui/10–16 输入七册——11（Activity→View 方法链与返回值语义、onTouch/onClick/onLongClick 时序与互斥、onInterceptTouchEvent 调用时机、pointer id vs index、getX/getRawX、滑动冲突两策略与嵌套滚动、ACTION_CANCEL 清理、TouchDelegate 热区、ACTION_OUTSIDE）、13（扫描码/键码/字符三层、HOME 为什么拦不到、组合键与 framework 连击、downTime/eventTime 与 repeat、fallback 合成键）、14（窗口命中与 touchableRegion、每屏焦点、touch mode、监视窗口）、10（输入 ANR 计时与 iq·oq·wq、输入过期丢弃）、16（dumpsys input 字段、注入工具对比）
20. **序列化与 Parcelable**：02-app-framework/03-parcel（选型与读写契约、Serializable 反射机制与 serialVersionUID、transient 与 Externalizable、Parcel 本质与 native 路径）；事务缓冲区限制见 03-binder Q2–Q3
21. **MVP 架构与解耦**：02-app-framework/07-mvp-architecture 全册（职责边界、接口解耦、异步与生命周期协调、Presenter 引用管理）
22. **Activity 启动与任务栈**：02-app-framework/10（任务选择与实例复用两条判断、NEW_TASK/SINGLE_TOP/CLEAR_TOP、launchMode 与 flag 叠加语义、Intent Filter 的 action/category/data 匹配与 queryIntentActivities、taskAffinity 与 allowTaskReparenting、冷热启动与实例复用区分、非 Activity Context 启动、任务栈组合场景推演）
23. **Fragment 与 ViewModel**：02-app-framework/11（实例与视图两段式生命周期、viewLifecycleOwner 观察契约、show/hide/replace/ViewPager2 取舍、commit/commitNow/commitAllowingStateLoss 边界、ViewModelStoreOwner 作用域）

## ★★★ 加分项（区分度）

1. **GKI/动态分区/AVB**：01-kernel-gki Q1；Q8；01-partitions Q2–Q3；02-system-boot Q3；Q10
2. **MessageQueue 无锁化（DeliQueue，Android 17）**：02-handler-looper Q6–Q7；02-handler-looper Q8–Q9；02-handler-looper Q10–Q11
3. **Binder 缓冲区/可观测性深水区**：03-binder Q3；03-binder-driver Q2；03-binder Q14–Q15；03-binder Q16
4. **16 KB 页大小**：01-system-architecture Q6；06-memory-storage/01 Q24
5. **Mainline/模块化影响**：01-system-architecture Q6；06-art-runtime Q1（ART 模块）
6. **Rust 平台化**：08-rust-native Q1–Q2；08-rust-native Q3
7. **AVF/虚拟化与 pKVM**：06-avf-virtualization
8. **MTE/内存安全**：02-reclaim-compression Q26；06-memory-storage/02
9. **CarWatchdog I/O 治理**：02-car-services Q10
10. **CarPower 电源状态机**：04-car-power-users Q1
11. **APEX/odsign 与启动**：02-system-boot Q18；06-art-runtime Q6（odrefresh 重建）
12. **分心限制 UXR（AAOS）**：07-driving-safety Q16–Q17；07-driving-safety Q18；机制与验证方法见 03-ui/07
13. **FGS 类型与超时**：12-performance/22-app-power-governance Q8、Q27；08-anr Q7
14. **Compose 运行期与稳定性**：03-ui/05（组合期写状态、remember 与 rememberSaveable 边界、derivedStateOf、稳定性推断与 strong skipping、延迟读取、列表键、ComposeView 组合策略、自定义宿主帧时钟、重组归因）

## AAOS 岗位专项（结合求职方向必看）

1. **启动链与挂点**：02-system-boot Q18（apexd）、Q31–Q34（CarService、CarSystemUI、CarLauncher）、Q35（FallbackHome）
2. **VHAL 双向**：应用侧 04-car-power-users Q2；服务侧 03-vhal-integration Q1–Q3（校验/崩溃重连/订阅契约）；迁移契约 Q4
3. **音频**：05-audio 前五册——01（AOSP 子系统：audioserver/线程选型/混音管线与 FastMixer/策略引擎/音量分层/AAudio 内部）、02（手机侧焦点栈/丢失处理/音量滑条/通信路由/并发录音与隐私）、03（AAOS 车机：多音区配置/落区判定/硬件增益/焦点矩阵/duck·mute/AudioControl 版本，落错区排查见 Q5）、04（延迟口径与实测/FAST 轨/AAudio 回退与缓冲调优/xrun/Offload 取舍/AudioTrack·SoundPool 语义）、05（蓝牙延迟/LC3/LE Audio/ASHA·HAP/SCO 切换与绝对音量）；06–12 册是点击音从应用焦点到 HAL 的连续学习路径。应用侧背景见 04-car-power-users Q1。
4. **网络（车机向）**：07-network 十册按编号阅读：01 网络框架 → 02 蜂窝与无线 → 03 传输协议 → 04 应用网络约束 → 05 多 APN 与 veth → 06 VPN → 07 车载网络 → 08 车载安全 → 09 应用连接实践 → 10 专项诊断。多网绑定、DNS、弱网与车云通道分别回到对应册核对。
5. **UI 定制与案例**：06-aaos-ui Q18（CarSystemUI 依赖注入结构）；06-aaos-ui Q17、06-car-launcher Q4（CarSystemUI 恢复与焦点修复案例）
6. **输入与旋钮（AAOS 向）**：03-ui/15（VHAL 三输入属性 int32Values 语义、InputHalService 加工与防御、CarInputService 五步分发、语音/通话键车载长按、CustomInputEvent 无人捕获即丢弃、capture 排他栈与音量旋钮不可捕获、旋钮 VHAL 与 Linux 设备双链路、RotaryService 无障碍形态与三模式、FocusArea/FocusParkingView 契约、触摸退出检测与 HUN nudge 劫持、cluster 按键两代路由、车机 IME 与旋钮输入法、inject-key/-rotary 调试、旋钮失灵五层排查）；方向盘 HID/uinput 与 VHAL 路线取舍见 03-ui/13-key-mapping Q8；外设（键鼠/手柄）接入排查见 03-ui/16-input-diagnostics Q16
7. **UI 与分心（AAOS 向）**：03-ui 基础与排查专题——01（Activity 启动到首帧/生命周期与配置变化/状态保存）、02（View 测量布局绘制/MeasureSpec/requestLayout 与 invalidate/脏区和 child drawing order/Layer 与 SurfaceFlinger 可见边界/SurfaceView 打洞及独立合成/TextureView 双 BufferQueue 与 SurfaceTexture 所有权/GLSurfaceView 渲染节奏）、03（资源限定符/深色主题/字号与 RTL/多显示 Configuration）、04（窗口层级与 token/addWindow/relayout 与遍历/Insets/IME/多窗口与窗口排查）、05（Compose 组合与状态/derivedStateOf/稳定性与 strong skipping/Effect/View 互操作）、06（CarService 与 car-lib/occupant zone 与多显示归属/模板与原生应用/多用户隔离/电源策略/旋钮与仪表/投影）、07（驾驶状态与 UX 限制映射/restriction mode/应用声明/乘员屏/受限交互/安全验证）、09（白屏与黑屏/首帧和掉帧归因/诊断工具/重绘与 Insets/输入超时/多显示和电源策略/回归检查）
8. **多用户与乘员**：04-car-power-users Q2–Q3；09-app-sandbox Q3；02-system-boot Q30；02-system-boot Q43（用户启动与 AAOS headless system user）；03-ui/06 Q9
9. **其余服务速览**：02-car-services 全册（媒体源/蓝牙/遥测/诊断/bugreport/投影）
10. **车辆信号链路与五层映射**：01-vehicle-links Q1（33 条链路总表）；网络地基机制见 ../../网络/01-network-fundamentals（分层模型/DNS 层级递归/TLS 握手/TCP 状态机/IP 分片/NAT 与 conntrack/全链路走读/分层校验）
