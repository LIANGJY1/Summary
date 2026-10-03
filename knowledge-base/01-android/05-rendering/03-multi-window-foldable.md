# 多窗口、折叠屏与显示服务

> 学习资料（文章模式沉淀）。主线：从 DisplayManagerService 的显示器发现、DeviceState 切换与功耗交接，到多窗口/PiP、Edge-to-Edge 的 WindowInsets、折叠屏显示切换与 TaskSnapshot，把管理状态、窗口几何与最终合成 present 分成三层证据来定位问题。源文档：android-internals-wiki §2.13《DisplayManagerService：显示器发现、拓扑、功耗与渲染交接》、§2.14《多窗口、PiP 与桌面模式渲染管线》、§2.15《Android 17 Edge-to-Edge 渲染与 WindowInsets 处理性能》、§2.16《折叠屏显示切换、窗口连续性与渲染性能》、§2.17《TaskSnapshot 捕获、Overview 缩略图与启动窗口》；机制按本地 AAOS13 源码（Android 13）核对，与材料 Android 17 语境的差异已在答案中标注；材料援引的官方文档口径（如 per-display VSYNC 限制、API 版本边界）因网络不可达未做在线复核，按本地源码与材料标注转写。AAOS 多物理屏与逻辑 Display 的车载全链路见 [../framework/Android显示系统/多屏.md](../../../docs/others/framework/Android显示系统/多屏.md)，逐帧 VSync 的预测与分发见 [../framework/Android显示系统/Vsync流程.md](../../../docs/others/framework/Android显示系统/Vsync流程.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: DisplayManagerService 在显示系统中管什么、不管什么？外接屏黑屏时 `dumpsys display` 里存在 LogicalDisplay 能证明什么？**

DMS 负责 Display 的发现、身份与逻辑映射、状态与功耗、对外事件分发，不负责应用逐帧绘制，也不决定某个 layer 走 HWC 的 DEVICE 合成还是 RenderEngine 的 CLIENT 合成。`dumpsys display` 里存在 LogicalDisplay 只能证明管理对象已建立，距离画面出现还隔着 WMS 窗口、SF 合成、HWC 与面板 present 三段。

DMS 的管理对象与显示树的关系可分开看：

1. `LogicalDisplayMapper` 把本地或虚拟 `DisplayDevice` 映射为 `LogicalDisplay`，向 framework 暴露 displayId、mode 与 state。
2. DMS 通过 traversal 把 projection、layer stack 与 size 等配置写入 `SurfaceControl.Transaction` 交给 WMS，并把 viewport 交给 InputManager。
3. 物理 hotplug 不会先删再建 SurfaceFlinger 的全局 layer 树。已有 layer 是否镜像、reparent 或留在原屏，由 WMS/Shell 事务与 projection 决定。

判断"为什么没画面"按三层证据依次确认：

1. **DMS 层**：LogicalDisplay 存在、enabled、state 与 mode 正确。
2. **WMS 层**：对应 DisplayContent 上有可见 Task 或 Window，Insets 与焦点正常。
3. **SF/HWC 层**：Output 有可见 layer，validate/present 成功且 present fence 到达。

以上职责与 traversal 交接按 AAOS13 源码核对：`DisplayManagerService` 经 `scheduleTraversalLocked` 置单个 `mPendingTraversal`，WMS 在自己的 traversal 中回调 `performTraversalInternal` 提交显示事务；单条 `onDisplayRemoved()` 回调不能推断所有资源已释放。

**Q2: 开机卡在等默认显示时，DMS 在哪个启动 phase 等什么条件、超时多久、怎么定位卡点？**

DMS 在 `PHASE_WAIT_FOR_DEFAULT_DISPLAY` 等两个条件同时满足：默认 LogicalDisplay 已创建且 VirtualDisplayAdapter 已创建；默认超时 10000 ms，超时抛 `RuntimeException`，不会无限等待。超时日志（A13 文本为 "Timeout waiting for default display to be initialized. DefaultDisplay=..."）中的两个条件值能直接区分卡在哪一段。

AAOS13 的启动和等待顺序如下：

1. `onStart()` 在 `mSyncRoot` 下加载 `PersistentDataStore` 与稳定显示配置，再投递 `MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS`。
2. Handler 之后才注册 `LocalDisplayAdapter` 与 `VirtualDisplayAdapter`，所以默认适配器不是在 `system_server` 启动调用栈里同步注册。
3. 等待使用 `mSyncRoot.wait()`，该调用释放对象监视器锁，让 DisplayThread 能处理 adapter 事件。
4. 排查时从 SF 是否枚举出 physical display id 与 token 开始，依次确认 `LocalDisplayAdapter` 是否发出 ADDED、`DisplayDeviceRepository` 是否接收、`LogicalDisplayMapper` 是否创建 `DEFAULT_DISPLAY`，最后检查 `VirtualDisplayAdapter` 是否创建。

边界：`PHASE_BOOT_COMPLETED` 只负责通知 DisplayPowerController、DisplayModeDirector 等组件启动完成，不参与首次默认屏发现。版本差异：A13 的 `WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000` 直接返回、不乘 `HW_TIMEOUT_MULTIPLIER`；A17 材料标注乘以该倍率，跨版本对齐超时日志时注意。

**Q3: 物理屏 hotplug 之后 DMS 这条链上发生什么？与 VirtualDisplay 的 token 创建有何不同？**

物理屏 hotplug 按以下事件链更新管理对象，adapter 回调先移到 DisplayThread，再在锁内统一更新，控制流与数据流由此分离：

1. HWC/vendor 上报 hotplug，SurfaceFlinger 更新物理 Display。
2. `LocalDisplayAdapter` 的 `DisplayEventReceiver` 收到事件，`tryConnectDisplayLocked()` 或 `tryDisconnectDisplayLocked()` 经 `Handler.post()` 生成 ADDED、CHANGED 或 REMOVED。
3. `DisplayDeviceRepository` 在 `mSyncRoot` 下更新设备集合，`LogicalDisplayMapper` 随后更新 LogicalDisplay。
4. DMS 分发回调并请求 WMS traversal。

三类事件更新不同状态：

1. ADDED：校验设备未重复后加入设备仓库并通知 mapper。
2. CHANGED：比较新旧 `DisplayDeviceInfo` 的 mode、state、rotation、color 等变化，再应用 pending info。
3. REMOVED：从仓库删除设备并通知 mapper。

`LocalDisplayAdapter.registerLocked()` 使用 `DisplayControl.getPhysicalDisplayIds()` 枚举物理屏，再从 SurfaceFlinger 查询 token、静态/动态信息与期望模式规格。物理 display token 由 SurfaceFlinger 持有并返回给 framework，DMS 不会为本地物理屏另建 token。

VirtualDisplay 生命周期由调用方发起：

1. 调用方经 `createVirtualDisplay()` 创建。A13 由 `VirtualDisplayAdapter` 调用 `SurfaceControl.createDisplay()` 建 token；A17 材料标注为 `DisplayControl.createVirtualDisplay()`。
2. 调用方提供的 Surface 是输出目标，SurfaceFlinger 把该 virtual display 的合成结果写入 Surface 背后的 BufferQueue。
3. callback 的 Binder 死亡或调用方主动 release 会停止设备、释放 Surface 引用、销毁 token 并发出 REMOVED。
4. resize 或更换 Surface 的 API 返回只表示配置请求被接收，不表示新输出帧已到达 consumer。

**Q4: DMS 为什么用 `mSyncRoot` 一把大锁？哪些慢工作已经被移出锁外？**

`mSyncRoot` 保护整个共享模型——设备集合、LogicalDisplay/DisplayGroup、功耗与亮度索引、回调注册、输入 viewport、pending traversal——因为一次 hotplug 需要原子完成"设备 → 逻辑屏 → group → power → 事件"的关系更新，拆锁会出现设备已删但 LogicalDisplay 仍可见、group 与拓扑不一致等问题。锁序约束是 WMS 可能先持 `mGlobalLock` 再进入 DMS，因此 DMS 持 `mSyncRoot` 时不得做可能回调 WMS 的同步调用。

A13 已把这些慢操作移出锁（源码核对）：

1. **显示事件分发**：`deliverDisplayEvent` 在锁内拷贝回调列表，锁外异步 Binder 通知客户端。
2. **电源操作**：`DisplayDevice.requestDisplayStateLocked` 只返回 `Runnable`，耗时的 `SurfaceControl.setDisplayPowerMode`（可能数百毫秒）在锁外执行。
3. **显示模式切换**：`setDesiredDisplayModeSpecsAsync` 经 Handler 在 DisplayThread 上调 SF。
4. **traversal**：`scheduleTraversalLocked` 只置位并 post，对 WMS 的调用稍后执行。

稳定显示期间，应用 buffer latch 与 HWC validate/present 不经过 `mSyncRoot`，DMS 的观察窗口是开机默认屏发现、插拔、DeviceState 切换、mode/亮度/电源变化和 VirtualDisplay 生命周期。稳定动画每帧卡而 Display 配置没变时，先查 App、SF、HWC 与 present，不先归因于这把锁。版本边界：A17 材料指出 topology 的 `setTopology` 仍在锁内写 XML 持久化，且 A13 没有 `DisplayTopologyCoordinator`（Android 16+ 才引入），分析 A13 不存在该热点。

**Q5: `DisplayManager.DisplayListener` 与 Choreographer 的 VSync 是什么关系？双屏设备能拿到两路独立硬件 VSync 吗？**

两条通道的对象和用途不同：

1. `DisplayListener` 接收 DMS 的 added、removed、changed 管理事件，用于刷新 Display 列表、能力与 mode。`onDisplayChanged()` 不是逐帧信号，也不代表新模式画面已 present。
2. 逐帧 VSync 由 SurfaceFlinger 的 EventThread 经 `DisplayEventReceiver` 投递到应用进程，最终触发 `Choreographer.doFrame()`；DMS 不在这条投递链上。
3. `LocalDisplayAdapter` 虽也使用 `DisplayEventReceiver`，但订阅的是 hotplug、mode change、frame-rate override 等 Display 事件。

per-display VSYNC 在 A13 的源码结构里就不存在：SurfaceFlinger 只调用一次 `createVsyncSchedule`，"app" 与 "appSf" 两个 EventThread 连接共享这一个调度基准（源码核对）；`DisplayEventReceiver.onVsync` 的参数虽带 physicalDisplayId，也不等于每块屏有独立可调度的硬件 VSync 时钟。AOSP 多屏官方文档同样不支持 per-display VSYNC、Display 由主内置屏 VSync 驱动（材料援引，未在线复核）。

边界：不同 Display 可以有不同 active mode 与独立 present 结果，但不能据此推出两路独立时钟。分析双屏或外接屏时按对象分层取证：每块屏的 mode 与渲染帧率、每个 SF Output 与 HWC Display 的 validate/present、每块屏自己的 present fence。

**Q6: 刷新率切换时 DMS、SurfaceFlinger 和应用各做什么？mode 变了 BufferQueue 会重建吗？**

刷新率切换由三个相接但不同的阶段完成：

1. `DisplayModeDirector` 汇总应用 frame-rate vote、系统策略与功耗约束，生成 `DesiredDisplayModeSpecs`。
2. DMS traversal 把 specs 交给 DisplayDevice；`LocalDisplayDevice` 经 `setDesiredDisplayModeSpecsAsync()` 在 DisplayThread 异步调用 `SurfaceControl.setDesiredDisplayModeSpecs()` 提交给 SurfaceFlinger，避免持 `mSyncRoot` 调同步 SurfaceFlinger 接口（AAOS13 源码核对）。
3. SurfaceFlinger 与 HWC 执行实际切换，应用随后按新节奏生产帧。

只切换刷新率时，已有 App Window BufferQueue、SurfaceControl 与 layer 继续使用；变化落在 VSync 预测与 work duration、`Display.Mode` 与 deadline、SF/HWC 的 active mode、FrameTimeline 的 expected/actual present。只有 mode 同时改分辨率时，WMS 才会看到 `DisplayInfo` 与 configuration 变化，应用才可能 relayout、重建尺寸相关 buffer 甚至重启 Activity——buffer 重建来自尺寸与应用响应，不能概括成"切刷新率必然重建 BufferQueue"。

判断切换何时对用户生效：`DISPLAY_DEVICE_EVENT_CHANGED` 只表示 framework 观察到动态显示信息变化；还要对齐 SF active mode、HWC 与驱动的 config applied、新 VSync 周期、目标 Display 的 present fence，以及应用是否已按新节奏出帧。只记录 `onDisplayChanged()` 会把管理通知时间误当成显示时间。

**Q7: 屏幕上同时显示两块内容，怎么判断这是不是"多窗口"？**

多窗口的强证据是存在多条独立的应用窗口交付链路：多个顶层 `ViewRootImpl`、WMS 中不同的 `WindowState`/Window token/Task（或 TaskFragment）、各窗口独立的 App Window Surface 与 BLAST 提交链，以及可分属不同 pid、UI tid 或 displayId。满足越多条，越应按多窗口管线分析。

常见误判有两个方向。其一，同一 Activity 内的双栏 View、`SlidingPaneLayout` 或 Compose pane 只有一个 ViewRootImpl 和一条 buffer 链路，不应按多窗口分析；12L 的 Activity Embedding 可让同一 Task 内并排两个 Activity container，视觉双栏仍可能只有一个顶层 Window。其二，系统栏、壁纸、输入法、dim 层和 transition leash 也会增加 SF layer，layer 数量多不证明应用建了多个 Window——要同时核对 WMS 窗口树与 SF layer 树两棵结构。


**Q8: 同进程多窗口与跨进程窗口，各自共享什么、独立什么？**

按资源归属逐级判断：每个窗口的 buffer 交付链路独立；同进程窗口共享线程；跨进程窗口还会争用目标 Display 的系统资源。共享线程不代表共享 BufferQueue，独立进程也不代表互不影响。线程模型上，`Choreographer` 是 ThreadLocal（`sThreadInstance`），判断单位是 Looper/tid 而非 pid；进程级 HWUI RenderThread 由 `RenderThread::getInstance()` 提供。

1. **永远独立**：每个窗口的 `ViewRootImpl`、Insets 状态、dirty 区域、App Window Surface 与 BLAST/BufferQueue、SF buffer layer 与 release fence——Window A 的 release fence 决定 A 的旧 buffer 何时复用，与 B 无关。
2. **同进程共享**：同一 UI Looper 上的多个 ViewRoot 共享同一个 ThreadLocal Choreographer，到期的 traversal 串行执行，A 的输入、动画、relayout 会推迟 B；所有硬件加速窗口共享进程级 HWUI RenderThread（每窗口一个 CanvasContext），A 的 `DrawFrame`、`dequeueBuffer` 或 fence wait 占用队列时 B 只能排队。
3. **跨进程仍共享**：SF 处理事务与 layer 的时间预算、RenderEngine 的 CLIENT 合成、HWC 的 plane/scaler/带宽、display mode 与 present deadline、GPU/内存/温控。

`DrawFrameTask::run()` 在 `syncFrameState()` 后按 `prepareTextures` 决定是否提前放行 UI 线程，所以主线程回调、RenderThread 任务与 GPU 工作是三段不同时间线，应分别读排队与依赖（A13 源码核对）。窗口挪到另一块屏后，mode、deadline、HWC 能力与 present fence 换了一套，不能用默认屏的 FrameTimeline 解释外屏延迟；同一 layer 经镜像或投屏可出现在多个 Output，要检查目标 Display 的 output layer state 而不是全局 layer 是否存在。

**Q9: 视频应用进入 PiP 后继续出帧，这条链路与全屏时相比变了什么？`setSeamlessResizeEnabled` 不设置会怎样？**

PiP 与 Freeform 仍走常规应用绘制链路，变的是 Task/Window bounds、transition leash、layer 几何与同屏合成策略：WM Shell 取得 Task leash，在动画中持续更新 position、crop、scale 与 alpha，系统可先用旧内容缩放裁剪过渡，不必等应用在每个采样点出新 buffer。视频以 24/30 fps 供帧而屏幕以 60/90/120 Hz present 时，多次复用同一视频 buffer 属正常。SF 缩小 layer 只改合成几何，不会自动降低 Producer 的分辨率与供帧节奏。

`setSeamlessResizeEnabled(true)` 适合视频等可连续缩放内容，复杂 UI 应显式传 `false`，避免把旧 buffer 拉伸到不合适的几何。版本差异（按 AAOS13 源码核对）：A13 未设置时 `isSeamlessResizeEnabled()` 返回 `true`（`mSeamlessResizeEnabled == null ? true : ...`），A17 材料标注 getter 改为返回 `false`；无论哪个版本，`PipTaskOrganizer` 在 resize 结束且该值为 `false` 时会对旧画面截图做 cross-fade。所以不要依赖隐式默认值，并在旋转、折叠、bounds 变化后更新 params。

PiP 常见风险对应明确证据：leash 几何已变而新 buffer 未到（过渡期正常缩放）、圆角/alpha/HDR 组合改变 HWC 策略、旧大尺寸 buffer 未 release 时新尺寸 buffer 已开始分配、进入小窗后 Producer 仍维持高分辨率高帧率（须由应用自查）。

**Q10: PiP 应用何时设置 `setAutoEnterEnabled(true)`，如何避免暂停播放时仍自动进入？**

API 31 起，`setAutoEnterEnabled(true)` 允许系统在适用的离开手势中自动把 Activity 切入 PiP，不再等待应用于 `onUserLeaveHint()` 调用 `enterPictureInPictureMode()`；默认值是 `false`。应用应在播放状态和 PiP 参数已更新时尽早提交 `setPictureInPictureParams()`，并在暂停或不再希望进入 PiP 时把 auto-enter 设为 `false`。启用后 `onPictureInPictureRequested()` 不会被调用，因此不能把该回调作为启用 auto-enter 时的进入通知。

**Q11: PiP 与桌面窗口过渡期间，`WindowContainerTransaction`、`SurfaceControl.Transaction` 和应用 buffer 是三条怎样的输入？**

过渡画面由三条异步输入合成：WCT（`WindowContainerTransaction`）改变 Task bounds、windowing mode、层级与 reparent，由 SystemUI 进程中的 WM Shell 下发；`SurfaceControl.Transaction` 改 layer 的 position、crop、alpha、Z 序与可见性，过渡期常落在 Shell 创建的 leash 上；应用 BLAST 提交新尺寸 buffer。三者在不同时刻到达，"新几何配旧 buffer"是过渡策略的一部分（旧内容被缩放或 letterbox），不一定是缺陷。

同步引擎只等已注册参与者。WMS 内部 `BLASTSyncEngine` 等待已加入同步组的 WindowContainer 的 draw 与 transaction（A13 已有，源码核对）；`SurfaceSyncGroup` 是 API 34+ 公开 API（A13 源码树中不存在——版本边界），面向应用与嵌入 Surface 的协调。未注册的 Camera、codec、SurfaceView Producer 不会因为同屏就被自动等待，它们的下一业务帧与转场没有同步契约。

排查黑边、拉伸或跳变要对齐四条证据：WCT 内容、leash/geometry 事务、应用 relayout 与 traversal、buffer 尺寸与到达时刻。桌面模式的 caption、最大化菜单、拖拽控件由 Shell 的 window decoration 子系统管理，不属于应用 `DecorView`；A17 可复用 `ViewHost` 降低 caption 反复创建成本，A13 源码树尚无该桌面装饰栈（版本边界），caption 卡顿在 A17 语境先查 SystemUI/Shell 线程。

**Q12: 分屏里的副窗口失去了焦点，它进入 `onStop()` 了吗？**

没有。Android 10（API 29）引入 Multi-resume 后，多个可见 Activity 可以同时停留在 `RESUMED`；焦点（focusable）、可见性（visible）与 top resumed 是三套不同信号。分屏副窗口、导航小窗、视频 PiP 都可能"可见但无焦点仍 RESUMED"，不能按后台已停止处理，也不能停掉全部渲染。

按信号分工处理：`onTopResumedActivityChanged(true)` 时恢复高交互频率、尝试获取独占资源；`false` 时只减少非必要重绘并准备处理资源抢占（如相机的 `onDisconnected`），官方允许非 top-resumed 的可见 Activity 继续相机预览（材料援引）；`onStop()` 只在 Activity 离开屏幕时触发，离屏工作在这里停。PiP 单独判断：通常"可见但不可聚焦"，视频仍在播就不是静态后台窗口，内容已暂停则不必每帧完整刷新。

A13 依据：`Activity.onTopResumedActivityChanged` 存在于 A13 源码（API 29 引入）。高频动画、连续 invalidate 与 frame-rate vote 应同时参考 top-resumed、实际可见性与内容是否仍在更新，三选一都不完整。

**Q13: 折叠展开或多窗口 resize 时 Activity 何时重建？`recreateOnConfigChanges` 和 `ViewModel` 各管什么？**

默认情况下，未在 `android:configChanges` 中声明的配置变化（一次折叠可能改变 `screenSize`、`smallestScreenSize`、`screenLayout`、`orientation`、`density` 的某个子集，取决于面板与厂商实现）会销毁并重建 Activity；声明自行处理则收到 `onConfigurationChanged()`，但必须重新读资源、更新布局与 display/density/Insets 等派生状态，不能原样返回。`ViewModel` 与 `ViewModelStore` 跨配置变化保留实例，`SavedStateHandle`/`rememberSaveable` 负责可恢复 UI 状态并覆盖进程被系统回收的情况；导航位置、滚动位置、表单等业务状态应与窗口尺寸和 posture 分离，折叠不应顺带清空它们。

`recreateOnConfigChanges` 的方向与 `configChanges` 相反：它声明"即使系统默认不重建，这类变化也要触发重建"。版本边界（按 AAOS13 源码核对）：A13 中该属性只支持 `mcc|mnc`，材料标注 API 37 才扩展到 touchscreen、keyboard、keyboardHidden、navigation、colorMode。它不能充当折叠屏或窗口尺寸变化的开关——窗口尺寸、方向、screen layout 的高频变化仍要通过 `configChanges`、`onConfigurationChanged()` 与状态保存处理。

大屏规则是另一条版本线：Android 12 起 multi-window 成为大屏标准行为、`resizeableActivity="false"` 不再是绝对开关（A13 已有）；Android 16 起 target 36 应用在 sw >= 600dp 屏上被忽略 `screenOrientation`、宽高比与 resizability 限制并带临时 opt-out，A17 移除 opt-out。A13 上固定方向与不可缩放声明仍有效，分析时按 target SDK 与设备最小宽度分别判断。

**Q14: Edge-to-Edge 强制是哪个版本的行为？它改变渲染管线吗？**

Edge-to-Edge 强制是 Android 15（API 35）起对 target SDK 35+ 应用的行为：内容默认延伸到系统栏与 cutout 后方，`decorFitsSystemWindows` 被置 `false`，状态栏颜色与导航分隔线置透明；Android 15 为 target 35 留过 `windowOptOutEdgeToEdgeEnforcement` 退出项，Android 16 起 target 36+ 禁用，A17 延续。AAOS13 的 `PhoneWindow` 中不存在 `ENFORCE_EDGE_TO_EDGE`（源码核对为 0 处），这些强制行为不适用于 A13 设备，判断时必须同时记录设备系统版本与 target SDK，`compileSdk` 不决定运行行为。

它不改变渲染管线：普通 View 或 Compose 页面仍沿 `ViewRootImpl` → HWUI RenderThread → App Window BLAST → SurfaceFlinger → HWC 出图，系统栏、caption、IME 作为系统 UI 或受控 Surface 参与同一 Display 的合成。改变的是布局责任——避让系统栏从窗口默认留白转给应用布局，需要时用 `statusBars()`、`displayCutout()`、`systemGestures()` 等按 type 处理。

关联版本差异：Android 15 还把 target 35 应用的 `Configuration.screenWidthDp/screenHeightDp` 与系统栏 Insets 解耦，运行时布局几何应改用实际容器、`WindowMetrics` 与 `WindowInsets`；A13 上这些 Configuration 值仍扣除系统栏，两套行为不能混用。

**Q15: 一个 `WindowInsets` 从 WMS 到 View 树经过哪些环节？listener 与 override 谁先执行、消费会挡住兄弟节点吗？**

分发有五个阶段，理解阶段边界有助于把系统状态更新与应用主动重分发区分开：

1. **状态维护**：WMS 保存带类型的 `InsetsState` 与 `InsetsSourceControl`。
2. **跨进程通知**：应用进程的 `InsetsController.onStateChanged()` 接收状态；一次 `WindowInsets` 可同时携带 systemBars、ime、displayCutout、caption、systemGestures 等 type，按 type mask 读取多种 type 不会触发多轮 Binder 分发。
3. **根 View 调度**：`ViewRootImpl.notifyInsetsChanged()` 置 `mApplyInsetsRequested`，并请求 layout 或安排 traversal。
4. **遍历分发**：`performTraversals()` 调用 `dispatchApplyInsets()`，随后由 root 的 `dispatchApplyWindowInsets()` 把值分发到 View/ViewGroup 树。AAOS13 源码中可核对 `mApplyInsetsRequested`、同名 trace section 与 `onStateChanged()`。
5. **应用主动重分发**：调用 `View.requestApplyInsets()` 只是请求根节点重新分发当前状态，不代表收到了新的 WMS 状态。

在单个 View 上，`dispatchApplyWindowInsets()` 先调用 `OnApplyWindowInsetsListener`；没有 listener 时才调用 `onApplyWindowInsets()`。listener 若要保留默认行为，必须显式调用 View 的实现，平台不会再自动调用一次。选 listener 还是 override 取决于组件封装与生命周期；`ViewCompat` 提供兼容层，不是性能优化。

消费规则取决于 API 级别和分发位置：

1. ViewGroup 自身返回 consumed 时，分发不再进入它的子树。
2. Android 11（API 30）起，兄弟节点各自收到原始输入 Insets，前一个 child 消费不会影响后一个。AAOS13 的 `ViewGroup` 源码中，`View.sBrokenInsetsDispatch` 为 false 时走新路径；target SDK 低于 30 的兼容路径保留旧的顺序消费。
3. 消费应放在明确负责避让的容器，并同时检查其后代与兄弟节点。在 decorView 根节点无条件返回 `CONSUMED` 再把 Insets 存进 ViewModel，会切断 Material、ComposeView、WebView 子树的标准分发，也可能让 Dialog、分屏或外接 Display 使用错误缓存。

**Q16: 状态栏透明了，为什么不能直接推断 SurfaceFlinger 换成了 CLIENT composition？**

系统栏背景很可能根本不是新 layer：`DecorView` 的 color view 画进当前 App Window buffer，透明后像素仍由同一个 App Window 交付；SystemUI 图标、手势 handle、IME、caption 是另一批本就存在的窗口。App Window 内部的普通 View 或 color view 不会因为视觉上像一层遮罩就变成 HWC layer，实际 layer 数量要从 SF layer tree 确认。

HWC 按整个 Display 的可见 layer 集合决策，输入包括 layer 的 format、dataspace、blend、transform、crop、protected usage，可用 overlay plane、scaler 与带宽，以及 SystemUI、IME、transition leash、dim、多窗口、视频等同屏内容。透明或半透明 layer 只可能影响选择，不构成结论；也不存在"某类 GPU 固定多 4-8 ms"的跨设备常量。

证明 HWC 回退需要同帧证据（AAOS13 源码核对 `DecorView.updateColorViews` 维护状态栏/导航栏 color view、三键导航可转对比度 scrim）：固定设备、Display mode、导航方式与页面内容；对比变更前后可见 layer tree；对齐同一 DisplayFrame 的 per-layer composition type 或 FrameTimeline 的 GPU Composition；排除 IME、transition、视频、多窗口同时变化的干扰。"透明栏出现""SF 时长变长""功耗上升"任何一项单独都不足以证明。

**Q17: IME 弹出动画期间，`onProgress()` 回调和"逐帧 apply Insets"是一回事吗？**

不是。注册 `WindowInsetsAnimation.Callback` 后，动画帧走 Choreographer 的 `CALLBACK_INSETS_ANIMATION` 阶段（A13 常量顺序 INPUT=0、ANIMATION=1、INSETS_ANIMATION=2、TRAVERSAL=3、COMMIT=4，源码核对），回调在普通 animation 之后、traversal 之前收到插值后的 Insets；生命周期是 `onPrepare()`、`onStart()`、`onProgress()`、`onEnd()`，由 dispatch mode 决定是否继续传给后代。成本取决于回调做了什么：改 `translationY`、alpha 等渲染属性通常不需要 measure，改 padding、margin、约束或列表结构才可能请求 layout；不能由可见 item 数推出固定毫秒。

"同步 Insets 动画"是另一条路径：动画进度放进普通 apply 与 traversal，每帧执行 `dispatchApplyInsets`。版本边界（A13 源码核对为不存在）：`ActivityInfo.ENABLE_SYNCHRONIZED_INSETS_ANIMATION` 是 Android 16+ 的 compat change，还需平台 `synced_insets_animation` flag 与设备满足高性能图形条件。A13 上每帧 `onProgress()` 不等于每帧 apply Insets，判断依据是 trace 中 `dispatchApplyInsets` 是否随动画逐帧出现，仅凭 API 版本无法判断某窗口在走哪条路径。

实践边界：不要硬编码 IME 动画时长与回调次数（"默认 300 ms、60 Hz 必回调 18 次"无依据，实际由系统实现、掉帧与设备状态决定）；用动画起点与终点的 bounds 差值驱动位移，避免在 `onProgress()` 里直接用 IME 底部距离——那会重复叠加原有 padding，并在浮动 IME、分屏或横屏下把内容移到错误位置。

**Q18: 折叠展开瞬间为什么会黑一下？500 ms 超时是什么？**

黑场来自显示框架主动的 display blanking：切换需要改变内屏 enabled 状态或 logical ID 时，`LogicalDisplayMapper` 先把受影响 Display 标记为 in-transition 并让其进入 OFF，用黑屏遮住 resize 过程中可能出现的错误尺寸；所有切换中的 Display 确认 OFF 后才应用目标 layout。500 ms 是这套状态机的兜底超时（A13 `LogicalDisplayMapper` 的 `TIMEOUT_STATE_TRANSITION_MILLIS = 500`，经 `MSG_TRANSITION_TO_PENDING_DEVICE_STATE` 强制推进，源码核对），不是黑场固定时长，也不是折叠动画时长。

完整链路分五层，故障先定位在哪层：

1. vendor 条件产生离散 DeviceState；
2. DMS 选择 layout 并做两阶段切换（标记 → 关屏 → 应用）；
3. WMS 与 Shell 更新 DisplayContent、Task bounds 与 transition leash；
4. 应用处理新 window bounds 与 configuration、提交新尺寸 buffer；
5. SF 与 HWC 为目标 Display 合成并 present。

DMS 几毫秒完成而首帧晚数百毫秒，应继续查 WMS geometry、应用新尺寸 buffer 与目标 Display present，不要在 `mSyncRoot` 上调参。"固定丢 1-3 帧""第一帧慢 30%-50%"没有 AOSP 保证：面板时序、power sequence、Shell transition、应用重绘与 HWC 能力都会改变观测结果。

**Q19: DeviceState 从哪里来？display layout 配置在哪些文件、能配什么？**

DeviceState 由 `DeviceStateProviderImpl` 从 `device_state_configuration.xml` 读取（data 分区优先、vendor 分区回退，A13 源码核对），条件可组合 lid switch 与指定 sensor 的数值范围；系统把全部状态按 ID 升序排序后逐个检查，取第一个条件满足者（`Arrays.sort` + 首个匹配，源码核对）。因此 DeviceState ID 是设备配置值，跨设备脚本不能写死 `STATE_OPEN` 之类的数值，也没有"折叠设备只看 `TYPE_HINGE_ANGLE`"的规定；`SENSOR_DELAY_FASTEST` 只是请求尽快交付，不突破 sensor 实际最小上报间隔与 HAL 行为。

layout 配置（`DeviceStateToLayoutMap`）按 device state 描述每个内屏的物理 `DisplayAddress`、logical display ID、enabled、display group、前后位置、lead display 与亮度/刷新率/热和功耗限流策略 ID。版本差异：A13 只从 `/vendor/etc/displayconfig/display_layout_configuration.xml` 读取（源码核对）；材料标注 A17 另支持 `/data/system/displayconfig/` 路径覆盖。logical display ID 是运行时身份，外接屏拔插后不要按上次 logical ID 关联历史数据。

并发内屏：`config_supportsConcurrentInternalDisplays`（A13 默认 `true`）声明设备能否同时点亮多个内建屏，layout 还要把相应屏配为 enabled 才会真正并发；即便并发，overlay、内存带宽、GPU 与功耗预算仍可能被 SoC 与 vendor 约束，两块屏的性能要分别取证。刷新率策略可随 layout 改变，但没有"展开固定 120 Hz、折叠固定低刷新率"的通用规则，实际 mode 还要经过 `DisplayModeDirector`、内容帧率投票与用户设置。

**Q20: 折叠切换的两阶段具体是什么？为什么"合盖必休眠、展开必亮屏"不是平台保证？**

A13 的 `setDeviceStateLocked` 流程（源码核对）：`resetLayoutLocked` 先把需要切换的 Display 标记为切换中并记录 `mPendingDeviceState`；`updateLogicalDisplaysLocked` 发布过渡状态、相关 Display 转 OFF；当所有 transitioning Display 已 OFF 且 wake/sleep 依赖满足时，才 `transitionToPendingStateLocked` 应用目标 layout 并发布最终状态；`MSG_TRANSITION_TO_PENDING_DEVICE_STATE` 的 500 ms 消息兜底，防止某个 power 或 interactivity 回调缺失后永久停在 pending state。

wake/sleep 是条件行为：满足条件时 mapper 经 `Handler.post()` 调 `PowerManager.wakeUp()`（A13 原因值 `WAKE_REASON_UNFOLD_DEVICE`）或 `goToSleep()`（`GO_TO_SLEEP_REASON_DEVICE_FOLD`），不持锁调用 PowerManager（源码核对）；是否触发由新旧状态、interactive 状态、override 与 boot 状态等条件（`shouldDeviceBeWoken`/`shouldDeviceBePutToSleep`）决定，模拟状态与用户折叠设置同样影响结果。

版本差异：A17 材料描述为"系统启动完成以前 mapper 只保存 `mDeviceStateToBeAppliedAfterBoot`"；A13 的做法是把 `mBootCompleted` 参与 wake/sleep 条件判断，布局应用本身不整体延后到 boot completed。另外 DMS 完成 layout 只代表 LogicalDisplay 配置确定，用户看到新画面还要经过 WMS、App buffer 与 SF/HWC present——黑帧排查要把这几段放到同一条时间线上，不能只用 `setDeviceStateLocked` 到 `applyLayoutLocked` 的时间解释。

**Q21: 应用感知折叠姿态该用 FoldingFeature 还是 hinge angle 传感器？**

普通应用优先使用 Jetpack WindowManager 的 `WindowInfoTracker.windowLayoutInfo()` 流，并按布局判断需要读取的 `FoldingFeature` 属性：

1. `bounds`：特征在应用窗口坐标系内的矩形。
2. `state`：只描述 `FLAT` 或 `HALF_OPENED`，不提供 `CLOSED` 状态或精确角度。
3. `orientation`：表示折叠特征方向；`HORIZONTAL` 且 `HALF_OPENED` 常用于 tabletop 布局判断，`VERTICAL` 对应 book 布局判断。
4. `occlusionType`：回答特征是否遮挡内容，可为 `NONE` 或 `FULL`。
5. `isSeparating`：回答应用是否应把布局分成两侧，不能与遮挡状态互相替代。

`FoldingFeature` 适合做窗口级布局决策，不适合精确动画采样。双面板设备可能 separating 而 bounds 某个维度为零；连续柔性屏平放时可能为 NONE 且不 separating；`state == FLAT` 也不代表整窗不存在铰链约束。切到外屏后，当前应用窗口可能不再包含 feature。

只有确实需要连续角度时才读 raw sensor：

1. 先调用 `getDefaultSensor(TYPE_HINGE_ANGLE)`，处理设备不提供可选传感器而返回 null 的情况。
2. A13 中 `TYPE_HINGE_ANGLE` 的类型值为 36，string type 为 `android.sensor.hinge_angle`；官方传感器定义为 on-change、degree 单位与 wake-up sensor。
3. `SENSOR_DELAY_FASTEST` 表示尽快交付，不是固定 60/120 Hz 采样保证，也不突破传感器最小上报间隔。
4. 如需平滑连续动画，在后台线程保留最新值并按 UI 帧采样，避免每个 sensor event 都触发全树 `requestLayout()`。



**Q22: SystemUI 折叠展开动画如何把传感器角度转成窗口变化，为什么不代表 App 每帧都出帧？**

展开动画由传感器采样、进度映射和图层变换组成，这条系统动画链与应用自身的帧生产链并行运行：

1. `HingeSensorAngleProvider` 使用 `TYPE_HINGE_ANGLE` 与 `SENSOR_DELAY_FASTEST` 收取角度事件；该采样请求不突破传感器硬件与 HAL 的实际上报间隔。
2. `PhysicsBasedUnfoldTransitionProgressProvider` 将角度变化映射为 0 到 1 的 unfold progress。
3. Shell 的 `UnfoldTransitionHandler` 与 `FullscreenUnfoldTaskAnimator` 在进度回调中更新 task leash 的 crop、matrix、圆角与可见性，改变既有 layer 的几何。
4. A13 中 `config_unfoldTransitionEnabled` 默认是 `false`，`config_unfoldTransitionHingeAngle` 提供动画角度配置；配置是否启用取决于设备 overlay。
5. `DeviceStateChanged` trace 表示系统状态提交，不是传感器采样时刻；动画 leash 每帧变化也不证明 App 每帧提交了新 buffer。应用内容仍由自己的 Choreographer 与 BufferQueue 路径生产。

**Q23: TaskSnapshot 里面装的是什么？Overview 卡片、live tile 和启动占位是同一个东西吗？**

TaskSnapshot 是对一个 Task 的 SurfaceControl 子树做 screen capture 得到的 HardwareBuffer 加元数据：top Activity 组件名、orientation 与 rotation、taskSize、contentInsets 与 letterboxInsets、分辨率级别（高/低）、real/主题占位标记、windowing mode、系统栏外观、是否半透明、是否含 IME Surface、ColorSpace 与快照 ID 等（A13 `android.window.TaskSnapshot` 字段核对）。元数据决定消费端如何旋转、裁剪、缩放与判断兼容性；HardwareBuffer 经 Binder 传句柄不复制像素，但接收方仍要等待可用并参与 GPU/HWC 合成，成本不为零。

同一份快照在不同场景落到不同显示对象：

1. **Overview 静态缩略图**：Launcher 把 HardwareBuffer 包装成 hardware Bitmap，画进 Launcher 自己的 App Window（SF 看到的主体通常是 Launcher 窗口 layer，不是每张卡片一个独立 layer）。
2. **live tile**：Recents 动画中真实 Task 的 Surface 经 remote animation leash 显示（`RemoteAnimationTarget`）。
3. **snapshot starting window**：旧快照被设到 Shell 创建的独立 starting surface 上，等待应用首帧。
4. **SF LayerSnapshot**：SurfaceFlinger 普通帧的 layer 可见性/几何状态，与最近任务缩略图无关。

看到 Overview 卡片不能推出屏幕上存在名为 TaskSnapshot 的独立 SF layer；`LayerSnapshotBuilder` 出现在 trace 里也可能只是 SF 为普通显示帧更新状态。这一区分是排查 Overview 卡顿、点击跳变与图形内存的第一步。

**Q24: TaskSnapshot 什么时候被捕获？应用被冻结前会自动截图吗？**

捕获有三条路径：转场（Task 将不可见时、在转场事务生效前记录）、休眠前、特权调用方主动请求。A13 的转场入口是 `TaskSnapshotController.onTransitionStarting()` → `snapshotTasks()` → `snapshotTask()`（源码核对）；A17 重构为 `SnapshotController.onTransactionReady()` 配合 `Transition.ChangeInfo`（版本差异，时机语义相同：用关闭前的 rotation 与 bounds 捕获，避免旧画面配上新几何）。休眠前路径 `snapshotForSleeping()` 遍历对应 Display 的可见 leaf Task，被 Recents 动画控制的 Task 会跳过（A13 的 `inRecentsTransition` 判断核对），安全锁屏进入休眠时默认屏的 home Task 也可能被捕获用于解锁回桌面的 starting window。主动请求用的 `TaskSnapshotManager`/`SnapshotManagerService` 是 A17 才有的系统服务，A13 源码树没有（版本边界）。

Cached App Freezer 不承担触发源：A13 的 `CachedAppOptimizer` 冻结应用时不调用任何 snapshot 入口（源码核对无引用），"每次冻结前先截 Task"的通用 hook 不存在。同理，捕获、`onPause()` 与转场开始之间没有源码承诺的固定顺序，系统只尽量在 Task 关闭前保留可过渡的视觉状态，应用不应假设 `capture → onPause → transition` 的时序。

**Q25: 什么时候会得到主题色卡片而不是真实截图？`setRecentsScreenshotEnabled(false)` 与 `FLAG_SECURE` 有什么区别？**

A13 的 `getSnapshotMode` 逻辑（源码核对）：非 standard/assistant 类型 Task 返回 `SNAPSHOT_MODE_NONE`，不出快照；top Activity `shouldUseAppThemeSnapshot()` 为真时生成 `SNAPSHOT_MODE_APP_THEME`——该条件是 `setRecentsScreenshotEnabled(false)`（`!mEnableRecentsScreenshot`）或 Task 内任一窗口带 `FLAG_SECURE`（`isSecureLocked`）；否则 `SNAPSHOT_MODE_REAL` 捕获真实画面。主题占位由 system_server 用 `TaskDescription` 背景色与窗口背景绘制（`drawAppThemeSnapshot`），不含应用敏感像素，所以主题色卡片可能是预期行为而非截图失败。Recents 与屏保 Activity 不捕获；设备 overlay `config_disableTaskSnapshots = true` 可整体关闭能力（TV/IoT 常用）。

两个隐私 API 范围不同：`Activity.setRecentsScreenshotEnabled(boolean)` 自 API 33 公开（A13 已有），只禁止该 Activity 画面被用作 Overview 表示，系统在其他允许场景仍可截图；`FLAG_SECURE` 更广——阻止窗口进入普通截图并限制在非安全 Display 上显示。处理登录、支付或隐私数据按威胁模型选 `FLAG_SECURE`，不能把 Overview 开关当它的替代品。

**Q26: TaskSnapshot 在运行时如何缓存，磁盘上保存哪些文件？**

运行时缓存：A13 `TaskSnapshotCache` 用 `ArrayMap<taskId, CacheEntry>`（`mRunningCache`）保存运行中任务快照，没有按访问顺序淘汰的 LRU 逻辑；清理靠 top Activity 移除或进程死亡、Task 从 Recents 删除、Task 重新可见完成转场等时机（源码核对）。`onlyCacheLowResTaskSnapshot` 的低分辨率主缓存与高分辨率延迟释放（5000 ms）是 A17 的 flag 路径，A13 没有——分析 A13 图形内存时按高/低分辨率两版并存估算。

磁盘：后台队列（A13 `TaskSnapshotPersister`）写三个文件——`<taskId>.proto`（元数据）、`<taskId>.jpg`（高分辨率图像）、`<taskId>_reduced.jpg`（低分辨率）；JPEG 压缩质量常量 95（A13 名 `QUALITY`，A17 材料改名 `COMPRESS_QUALITY`，值不变）。A13 目录是 `/data/system_ce/<userId>/snapshots/`，A17 材料在其下增加随机化子目录（版本差异，隐私加固）。磁盘命中仍包含文件 I/O、`BitmapFactory.decodeFile()` 解码与 `copy(Config.HARDWARE)` 的硬件位图分配（A13 `TaskSnapshotLoader` 核对），与内存命中不同量级——Overview 空卡要先区分 system_server 缓存、磁盘恢复、Launcher 缓存与主线程 bind 四段，不能统一归因 GPU。

**Q27: 如何估算 TaskSnapshot 的图形内存，像素格式和分辨率由什么决定？**

`width × height × bytesPerPixel` 只能估算像素存储下限；以 1080 × 2400 的 RGBA_8888 为例，约占 9.9 MiB。实际占用还包括 row stride、gralloc 对齐，以及同一任务在高低分辨率缓存、Binder 引用、Launcher hardware Bitmap 和 starting window 中同时存活的副本。

AAOS13 默认像素格式为 RGBA_8888；只有 overlay `config_use16BitTaskSnapshotPixelFormat` 开启、快照格式仍为 `UNKNOWN`、top Activity `fillsParent()`，且主窗口不是“半透明并显示壁纸”的组合时，才选择 RGB_565。高/低分辨率 scale 默认分别为 1.0 和 0.5，可由厂商 overlay 调整。没有 AOSP 规则保证低内存设备固定缓存 3–5 张快照或必用 RGB_565，估算时应以目标设备配置、快照尺寸和实际引用生命周期为准。

**Q28: 点击 Overview 卡片回到应用走哪条路径？旧快照为什么不匹配时会不用？**

两条启动路径：点击当前 running 的 live tile 时，Quickstep 沿 remote target leash 做返回动画；点击静态卡片时 Launcher 发起 `startActivityFromRecents()`，WMS 可能直接等待已有 App Window、创建 snapshot starting window、因快照不兼容改用 splash、或在 Task/Activity 已满足显示条件时不建 starting window——不存在统一的"静态快照 layer 渐变切换成应用实时 Surface"模型，Launcher 静态缩略图、Shell remote leash 与 WMS starting window 是三套对象。

starting window 的兼容检查（A13 `ActivityRecord.isSnapshotCompatible`，源码核对）：top Activity 组件一致、快照 rotation 与目标 rotation 一致、快照 taskSize 宽高比与当前 bounds 宽高比差值 ≤ 0.01（该阈值 A13 与 A17 材料一致；A17 材料将该方法称为 `isSnapshotOrientationCompatible`，语义相同）；折叠、旋转或 resize 后不满足即回退 splash 或不显示快照——排查"折叠后快照拉伸或闪启动屏"从这里入手。尺寸不匹配但可用时，会按 letterbox insets 调整位置并对 X/Y 分别缩放旧 buffer 遮住间隙，它只遮间隙，不修复应用布局，应用仍要提交符合新 bounds 的首帧。

移除时机：应用内容 ready 后由 TaskOrganizer 请求移除 starting window；A13 `TaskSnapshotWindow` 的一般延迟为 100 ms、含 IME 的为 600 ms（源码核对），A17 材料另有 3000 ms 的 fixed-rotation 档（版本差异），三档都是特定移除模式的保护值而非冷启动固定等待。点击后画面跳变的排查要分三张画面分别对齐 task bounds、rotation、density 与 contentInsets/letterboxInsets：Launcher 静态缩略图、WMS/Shell 的 starting window、应用新提交的 buffer，只看录像无法判断跳变发生在哪一段。
