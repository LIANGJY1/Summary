# 显示服务与折叠形态

> 学习资料（文章模式沉淀）。主线：DisplayManagerService 的职责边界与启动时序、显示热插拔与 VirtualDisplay、刷新率切换的三方分工、折叠 DeviceState 与 display layout、合盖展开的两阶段行为、SystemUI 折叠动画与帧的关系。多窗口/PiP/Insets/TaskSnapshot 等窗口交互语义已于 2026-10-06 拆至 [../03-ui/04-window-system.md](../03-ui/04-window-system.md)。AOSP 机制按本地 AAOS13 源码（Android 13）核对。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] DisplayManagerService 在显示系统中管什么、不管什么？外接屏黑屏时 dumpsys display 里存在 LogicalDisplay 能证明什么？**

DMS 负责 Display 的发现、身份与逻辑映射、状态与功耗、对外事件分发，不负责应用逐帧绘制，也不决定某个 layer 走 HWC 的 DEVICE 合成还是 RenderEngine 的 CLIENT 合成。`dumpsys display` 里存在 LogicalDisplay 只能证明管理对象已建立，距离画面出现还隔着 WMS 窗口、SF 合成、HWC 与面板 present 三段。

DMS 的管理对象与显示树的关系可分开看：

1. `LogicalDisplayMapper` 把本地或虚拟 `DisplayDevice` 映射为 `LogicalDisplay`，向 framework 暴露 displayId、mode 与 state。
2. DMS 通过 traversal 把 projection、layer stack 与 size 等配置写入 `SurfaceControl.Transaction` 交给 WMS，并把 viewport 交给 InputManager。
3. 物理 hotplug 不会先删再建 SurfaceFlinger 的全局 layer 树。已有 layer 是否镜像、reparent 或留在原屏，由 WMS/Shell 事务与 projection 决定。

判断“为什么没画面”按三层证据依次确认：

1. **DMS 层**：LogicalDisplay 存在、enabled、state 与 mode 正确。
2. **WMS 层**：对应 DisplayContent 上有可见 Task 或 Window，Insets 与焦点正常。
3. **SF/HWC 层**：Output 有可见 layer，validate/present 成功且 present fence 到达。

以上职责与 traversal 交接按 AAOS13 源码核对：`DisplayManagerService` 经 `scheduleTraversalLocked` 置单个 `mPendingTraversal`，WMS 在自己的 traversal 中回调 `performTraversalInternal` 提交显示事务。单条 `onDisplayRemoved()` 回调不能推断所有资源已释放。


**Q2: [learning] 开机卡在等默认显示时，DMS 在哪个启动 phase 等什么条件、超时多久、怎么定位卡点？**

DMS 在 `PHASE_WAIT_FOR_DEFAULT_DISPLAY` 等两个条件同时满足：默认 LogicalDisplay 已创建且 VirtualDisplayAdapter 已创建。默认超时 10000 ms，超时抛 `RuntimeException`，不会无限等待。超时日志（A13 文本为 "Timeout waiting for default display to be initialized. DefaultDisplay=..."）中的两个条件值能直接区分卡在哪一段。

AAOS13 的启动和等待顺序如下：

1. `onStart()` 在 `mSyncRoot` 下加载 `PersistentDataStore` 与稳定显示配置，再投递 `MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS`。
2. Handler 之后才注册 `LocalDisplayAdapter` 与 `VirtualDisplayAdapter`，所以默认适配器不是在 `system_server` 启动调用栈里同步注册。
3. 等待使用 `mSyncRoot.wait()`，该调用释放对象监视器锁，让 DisplayThread 能处理 adapter 事件。
4. 排查时从 SF 是否枚举出 physical display id 与 token 开始，依次确认 `LocalDisplayAdapter` 是否发出 ADDED、`DisplayDeviceRepository` 是否接收、`LogicalDisplayMapper` 是否创建 `DEFAULT_DISPLAY`，最后检查 `VirtualDisplayAdapter` 是否创建。

边界：`PHASE_BOOT_COMPLETED` 只负责通知 DisplayPowerController、DisplayModeDirector 等组件启动完成，不参与首次默认屏发现。版本差异：A13 的 `WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000` 直接返回、不乘 `HW_TIMEOUT_MULTIPLIER`。A17 材料标注乘以该倍率，跨版本对齐超时日志时注意。


**Q3: [learning] 物理屏 hotplug 之后 DMS 这条链上发生什么？与 VirtualDisplay 的 token 创建有何不同？**

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

1. 调用方经 `createVirtualDisplay()` 创建。A13 由 `VirtualDisplayAdapter` 调用 `SurfaceControl.createDisplay()` 建 token。A17 材料标注为 `DisplayControl.createVirtualDisplay()`。
2. 调用方提供的 Surface 是输出目标，SurfaceFlinger 把该 virtual display 的合成结果写入 Surface 背后的 BufferQueue。
3. callback 的 Binder 死亡或调用方主动 release 会停止设备、释放 Surface 引用、销毁 token 并发出 REMOVED。
4. resize 或更换 Surface 的 API 返回只表示配置请求被接收，不表示新输出帧已到达 consumer。


**Q4: [learning] DMS 为什么用 mSyncRoot 一把大锁？哪些慢工作已经被移出锁外？**

`mSyncRoot` 保护整个共享模型——设备集合、LogicalDisplay/DisplayGroup、功耗与亮度索引、回调注册、输入 viewport、pending traversal——因为一次 hotplug 需要原子完成“设备 → 逻辑屏 → group → power → 事件”的关系更新，拆锁会出现设备已删但 LogicalDisplay 仍可见、group 与拓扑不一致等问题。锁序约束是 WMS 可能先持 `mGlobalLock` 再进入 DMS，因此 DMS 持 `mSyncRoot` 时不得做可能回调 WMS 的同步调用。

A13 已把这些慢操作移出锁（源码核对）：

1. **显示事件分发**：`deliverDisplayEvent` 在锁内拷贝回调列表，锁外异步 Binder 通知客户端。
2. **电源操作**：`DisplayDevice.requestDisplayStateLocked` 只返回 `Runnable`，耗时的 `SurfaceControl.setDisplayPowerMode`（可能数百毫秒）在锁外执行。
3. **显示模式切换**：`setDesiredDisplayModeSpecsAsync` 经 Handler 在 DisplayThread 上调 SF。
4. **traversal**：`scheduleTraversalLocked` 只置位并 post，对 WMS 的调用稍后执行。

稳定显示期间，应用 buffer latch 与 HWC validate/present 不经过 `mSyncRoot`，DMS 的观察窗口是开机默认屏发现、插拔、DeviceState 切换、mode/亮度/电源变化和 VirtualDisplay 生命周期。稳定动画每帧卡而 Display 配置没变时，先查 App、SF、HWC 与 present，不先归因于这把锁。版本边界：A17 材料指出 topology 的 `setTopology` 仍在锁内写 XML 持久化，且 A13 没有 `DisplayTopologyCoordinator`（Android 16+ 才引入），分析 A13 不存在该热点。


**Q5: [learning] DisplayManager.DisplayListener 与 Choreographer 的 VSync 是什么关系？双屏设备能拿到两路独立硬件 VSync 吗？**

两条通道的对象和用途不同：

1. `DisplayListener` 接收 DMS 的 added、removed、changed 管理事件，用于刷新 Display 列表、能力与 mode。`onDisplayChanged()` 不是逐帧信号，也不代表新模式画面已 present。
2. 逐帧 VSync 由 SurfaceFlinger 的 EventThread 经 `DisplayEventReceiver` 投递到应用进程，最终触发 `Choreographer.doFrame()`。DMS 不在这条投递链上。
3. `LocalDisplayAdapter` 虽也使用 `DisplayEventReceiver`，但订阅的是 hotplug、mode change、frame-rate override 等 Display 事件。

per-display VSYNC 在 A13 的源码结构里就不存在：SurfaceFlinger 只调用一次 `createVsyncSchedule`，"app" 与 "appSf" 两个 EventThread 连接共享这一个调度基准（源码核对）。`DisplayEventReceiver.onVsync` 的参数虽带 physicalDisplayId，也不等于每块屏有独立可调度的硬件 VSync 时钟。AOSP 多屏官方文档同样不支持 per-display VSYNC、Display 由主内置屏 VSync 驱动（材料援引，未在线复核）。

边界：不同 Display 可以有不同 active mode 与独立 present 结果，但不能据此推出两路独立时钟。分析双屏或外接屏时按对象分层取证：每块屏的 mode 与渲染帧率、每个 SF Output 与 HWC Display 的 validate/present、每块屏自己的 present fence。


**Q6: [learning] 刷新率切换时 DMS、SurfaceFlinger 和应用各做什么？mode 变了 BufferQueue 会重建吗？**

刷新率切换由三个相接但不同的阶段完成：

1. `DisplayModeDirector` 汇总应用 frame-rate vote、系统策略与功耗约束，生成 `DesiredDisplayModeSpecs`。
2. DMS traversal 把 specs 交给 DisplayDevice。`LocalDisplayDevice` 经 `setDesiredDisplayModeSpecsAsync()` 在 DisplayThread 异步调用 `SurfaceControl.setDesiredDisplayModeSpecs()` 提交给 SurfaceFlinger，避免持 `mSyncRoot` 调同步 SurfaceFlinger 接口（AAOS13 源码核对）。
3. SurfaceFlinger 与 HWC 执行实际切换，应用随后按新节奏生产帧。

只切换刷新率时，已有 App Window BufferQueue、SurfaceControl 与 layer 继续使用。变化落在 VSync 预测与 work duration、`Display.Mode` 与 deadline、SF/HWC 的 active mode、FrameTimeline 的 expected/actual present。只有 mode 同时改分辨率时，WMS 才会看到 `DisplayInfo` 与 configuration 变化，应用才可能 relayout、重建尺寸相关 buffer 甚至重启 Activity——buffer 重建来自尺寸与应用响应，不能概括成“切刷新率必然重建 BufferQueue”。

判断切换何时对用户生效：`DISPLAY_DEVICE_EVENT_CHANGED` 只表示 framework 观察到动态显示信息变化。还要对齐 SF active mode、HWC 与驱动的 config applied、新 VSync 周期、目标 Display 的 present fence，以及应用是否已按新节奏出帧。只记录 `onDisplayChanged()` 会把管理通知时间误当成显示时间。


**Q7: [learning] 折叠展开瞬间为什么会黑一下？500 ms 超时是什么？**

黑场来自显示框架主动的 display blanking：切换需要改变内屏 enabled 状态或 logical ID 时，`LogicalDisplayMapper` 先把受影响 Display 标记为 in-transition 并让其进入 OFF，用黑屏遮住 resize 过程中可能出现的错误尺寸。所有切换中的 Display 确认 OFF 后才应用目标 layout。500 ms 是这套状态机的兜底超时（A13 `LogicalDisplayMapper` 的 `TIMEOUT_STATE_TRANSITION_MILLIS = 500`，经 `MSG_TRANSITION_TO_PENDING_DEVICE_STATE` 强制推进，源码核对），不是黑场固定时长，也不是折叠动画时长。

完整链路分五层，故障先定位在哪层：

1. vendor 条件产生离散 DeviceState。
2. DMS 选择 layout 并做两阶段切换（标记 → 关屏 → 应用）。
3. WMS 与 Shell 更新 DisplayContent、Task bounds 与 transition leash。
4. 应用处理新 window bounds 与 configuration、提交新尺寸 buffer。
5. SF 与 HWC 为目标 Display 合成并 present。

DMS 几毫秒完成而首帧晚数百毫秒，应继续查 WMS geometry、应用新尺寸 buffer 与目标 Display present，不要在 `mSyncRoot` 上调参。“固定丢 1-3 帧”“第一帧慢 30%-50%”没有 AOSP 保证：面板时序、power sequence、Shell transition、应用重绘与 HWC 能力都会改变观测结果。


**Q8: [learning] DeviceState 从哪里来？display layout 配置在哪些文件、能配什么？**

DeviceState 由 `DeviceStateProviderImpl` 从 `device_state_configuration.xml` 读取（data 分区优先、vendor 分区回退，A13 源码核对），条件可组合 lid switch 与指定 sensor 的数值范围。系统把全部状态按 ID 升序排序后逐个检查，取第一个条件满足者（`Arrays.sort` + 首个匹配，源码核对）。因此 DeviceState ID 是设备配置值，跨设备脚本不能写死 `STATE_OPEN` 之类的数值，也没有"折叠设备只看 `TYPE_HINGE_ANGLE`"的规定。`SENSOR_DELAY_FASTEST` 只是请求尽快交付，不突破 sensor 实际最小上报间隔与 HAL 行为。

layout 配置（`DeviceStateToLayoutMap`）按 device state 描述每个内屏的物理 `DisplayAddress`、logical display ID、enabled、display group、前后位置、lead display 与亮度/刷新率/热和功耗限流策略 ID。版本差异：A13 只从 `/vendor/etc/displayconfig/display_layout_configuration.xml` 读取（源码核对）。材料标注 A17 另支持 `/data/system/displayconfig/` 路径覆盖。logical display ID 是运行时身份，外接屏拔插后不要按上次 logical ID 关联历史数据。

并发内屏：`config_supportsConcurrentInternalDisplays`（A13 默认 `true`）声明设备能否同时点亮多个内建屏，layout 还要把相应屏配为 enabled 才会真正并发。即便并发，overlay、内存带宽、GPU 与功耗预算仍可能被 SoC 与 vendor 约束，两块屏的性能要分别取证。刷新率策略可随 layout 改变，但没有“展开固定 120 Hz、折叠固定低刷新率”的通用规则，实际 mode 还要经过 `DisplayModeDirector`、内容帧率投票与用户设置。


**Q9: [learning] 折叠切换的两阶段具体是什么？为什么“合盖必休眠、展开必亮屏”不是平台保证？**

A13 的 `setDeviceStateLocked` 流程（源码核对）：`resetLayoutLocked` 先把需要切换的 Display 标记为切换中并记录 `mPendingDeviceState`。`updateLogicalDisplaysLocked` 发布过渡状态、相关 Display 转 OFF。当所有 transitioning Display 已 OFF 且 wake/sleep 依赖满足时，才 `transitionToPendingStateLocked` 应用目标 layout 并发布最终状态。`MSG_TRANSITION_TO_PENDING_DEVICE_STATE` 的 500 ms 消息兜底，防止某个 power 或 interactivity 回调缺失后永久停在 pending state。

wake/sleep 是条件行为：满足条件时 mapper 经 `Handler.post()` 调 `PowerManager.wakeUp()`（A13 原因值 `WAKE_REASON_UNFOLD_DEVICE`）或 `goToSleep()`（`GO_TO_SLEEP_REASON_DEVICE_FOLD`），不持锁调用 PowerManager（源码核对）。是否触发由新旧状态、interactive 状态、override 与 boot 状态等条件（`shouldDeviceBeWoken`/`shouldDeviceBePutToSleep`）决定，模拟状态与用户折叠设置同样影响结果。

版本差异：A17 材料描述为"系统启动完成以前 mapper 只保存 `mDeviceStateToBeAppliedAfterBoot`"。A13 的做法是把 `mBootCompleted` 参与 wake/sleep 条件判断，布局应用本身不整体延后到 boot completed。另外 DMS 完成 layout 只代表 LogicalDisplay 配置确定，用户看到新画面还要经过 WMS、App buffer 与 SF/HWC present——黑帧排查要把这几段放到同一条时间线上，不能只用 `setDeviceStateLocked` 到 `applyLayoutLocked` 的时间解释。


**Q10: [learning] SystemUI 折叠展开动画如何把传感器角度转成窗口变化，为什么不代表 App 每帧都出帧？**

展开动画由传感器采样、进度映射和图层变换组成，这条系统动画链与应用自身的帧生产链并行运行：

1. `HingeSensorAngleProvider` 使用 `TYPE_HINGE_ANGLE` 与 `SENSOR_DELAY_FASTEST` 收取角度事件。该采样请求不突破传感器硬件与 HAL 的实际上报间隔。
2. `PhysicsBasedUnfoldTransitionProgressProvider` 将角度变化映射为 0 到 1 的 unfold progress。
3. Shell 的 `UnfoldTransitionHandler` 与 `FullscreenUnfoldTaskAnimator` 在进度回调中更新 task leash 的 crop、matrix、圆角与可见性，改变既有 layer 的几何。
4. A13 中 `config_unfoldTransitionEnabled` 默认是 `false`，`config_unfoldTransitionHingeAngle` 提供动画角度配置。配置是否启用取决于设备 overlay。
5. `DeviceStateChanged` trace 表示系统状态提交，不是传感器采样时刻。动画 leash 每帧变化也不证明 App 每帧提交了新 buffer。应用内容仍由自己的 Choreographer 与 BufferQueue 路径生产。
