# Android 13 窗口透明与表面合成全链路解剖
## 从主题属性到屏幕像素

> 本文以本仓 Android 13 源码和车机状态栏改造为例，分析主题声明如何影响 Activity 可见性、窗口绘制与表面合成。代码位置以本仓版本为准；设备配置、厂商修改和合成路径仍需实机核对。

---

## 0. 核心问题与全链路地图

以打开设置并遮挡桌面为例，三条处理链在不同进程协同：

```text
应用进程：主题与 View 绘制 → 应用窗口 buffer
                  ↓ 窗口属性、布局和可见性请求
system_server：Activity 遮挡判断 → 转场 → 窗口 Surface 显隐
                  ↓ Surface 事务
SurfaceFlinger：图层过滤 → GPU / 硬件合成 → 显示输出
```

### 0.1 三个进程如何接力

**屏幕上看到的一帧，不是某个进程调用一次函数后立即得到的结果。** 应用进程负责产生窗口内容，`system_server` 决定窗口如何管理，SurfaceFlinger 则使用已经提交的图层状态与缓冲区生成显示画面。它们之间既有跨进程调用，也有通过 BufferQueue 和 Surface 事务传递的状态；各进程在各自的线程与调度时机处理这些工作。

图形缓冲区（buffer）保存窗口绘制出的图像数据；它与决定图层位置、透明度和显隐的图层状态是两类信息。缓冲区的数量、交接和内存估算见 §7.1。

以**点击 Launcher 桌面图标、打开全屏设置页**为例，先假设设备走 legacy 转场，设置 Activity 是非浮动、非壁纸窗口。下列四步说明各组件的职责；实际执行会交错，不代表四步恰好落在四个连续显示帧中。

1. **设置应用准备窗口内容。** 如果是冷启动，系统需要启动设置进程并创建 Activity；应用的 `PhoneWindow` 随后装配窗口根 View——`DecorView`。ViewRootImpl 向 WMS 发起 relayout，取得窗口的 frame，再组织 View 树测量、布局和绘制。背景、文字和按钮的绘制结果进入设置窗口的图形缓冲区，等待合成（§3.1～§3.3、§4.1）。

   **例子：**屏幕为 1280 × 720 时，若 WMS 算出的设置窗口 frame 覆盖整屏，窗口顶部也属于设置窗口；即使按钮从状态栏下方开始排列，也要看该窗口顶部实际画了什么。若设置已在后台运行，系统可能复用已有进程或 Activity，不一定重复完整的冷启动过程。

2. **WMS 判断桌面是否继续可见，并准备转场。** 启动过程中，WMS 根据 Setting 的 ActivityRecord 遮挡状态和容器层级重新计算可见性。若本例中 `windowIsTranslucent=false`，且设置页被判定完全遮挡 Launcher，Launcher 会被请求变为不可见；转场同时记录需要打开或关闭的容器。legacy 转场会检查参与者是否就绪，条件可能由真实窗口绘制完成或 StartingWindow 已显示等情况满足（§2.1～§2.3、§6.1～§6.2）。

   **例子：**WMS 已将 Launcher 的 `mVisibleRequested` 设为 false 时，设置窗口的首帧可能仍在准备；Launcher 若参加退出动画，也可能暂时继续显示。因此，这个字段表明目标可见状态，不能单独证明桌面图层已经隐藏。

3. **SurfaceFlinger 合成当前可见的窗口。** 应用提交设置窗口的 buffer；WMS 则通过 Surface 事务提交窗口的显示、隐藏和动画变换。SurfaceFlinger 使用已经收到的 buffer 与图层状态，计算各层在当前显示输出中的可见区域，再交由 GPU 或硬件合成器处理（§5.1～§5.3）。

   **例子：**设置窗口刚从屏幕底部滑入、尚未覆盖顶部时，若 Launcher 图层仍可见，未覆盖区域可能显示桌面；同时，SystemUI 状态栏窗口可以显示自己的图标。若 Launcher 已隐藏，未覆盖区域就不能再使用其画面，最终颜色需看剩余图层。屏幕上的结果是这些图层合成后的画面，不代表桌面像素已经写入设置窗口的 buffer。

4. **动画结束后，各组件按条件收尾。** 退出动画结束会触发 WMS 的动画完成处理；旧窗口的隐藏、Launcher 的 `onStop` 和后续 Surface 资源处理分别经过各自条件和调度。相关事务被 SurfaceFlinger 接收并处理后，才会反映到后续合成画面（§2.4～§2.5、§6.5）。

   **例子：**设置页稳定显示后，Launcher 可能已经停止、其图层也已隐藏。此时若状态栏仍要显示与桌面顶部相同的背景，就需要由仍可见的内容提供该图像；不能再依赖已经隐藏的 Launcher。是否已经停止、隐藏或销毁，需要分别查看生命周期、窗口和图层状态。

这四步不是每帧严格串行的“流水线”：应用可能尚未提交新缓冲区，WMS 已经改变期望可见状态；SurfaceFlinger 也可能在处理新事务前继续使用已提交的旧缓冲区。legacy 转场还可能以已显示的 StartingWindow 满足部分就绪条件，而不是等待应用主窗口绘制完成（§6.2）。具体先后取决于启动和转场路径。

**对问题定位的直接影响**：`onPause`、`visibleRequested=false`、窗口 Surface 被 hide、图层不再参与合成，是不同层次的事件。看到其中任意一项，都不能替代其余几项的观测。例如，桌面 Activity 已被请求隐藏时，退出动画仍可能让其窗口暂时显示；因此必须把生命周期日志、窗口状态和图层 trace 按时间对齐，才能解释动画期间出现了什么。

### 0.2 为什么修改透明属性不能同时完成两个目标

本案例有两个独立目标：**停止依赖 Launcher 画面**，减少桌面在设置页下方持续绘制或合成的机会；**状态栏区域仍显示与桌面顶部相同的背景图像**。第二个目标需要有某个可见图层实际提供该图像。

如果只把设置 Activity 的 `windowIsTranslucent` 改为 `false`，而设置窗口的状态栏区域仍未绘制背景，过程是：

1. 在本案例所讨论的普通全屏、非浮动且不显示壁纸的条件下，WMS 可以把设置 Activity 记录为遮挡下层；可见性扫描和转场处理后，Launcher 窗口可能被隐藏。
2. 设置窗口的透明区域没有画出桌面图像。SurfaceFlinger 如果已经不再将 Launcher 图层作为可见内容合成，就无法从该图层取得桌面画面。
3. 于是该区域显示的将是其他仍可见图层或输出背景的结果；若表现为黑色，必须继续检查该帧的合成路径，不能仅凭主题配置断定黑色来源。

**因此，想隐藏 Launcher 又保留原来的视觉效果，必须由仍然显示的内容提供桌面顶部的图像。** 这份图像可以画在设置窗口中，但会随设置窗口的转场一起移动；本案例的乙′方案选择将预先准备的图像画在独立的 SystemUI 状态栏窗口中。它提供的是图像副本：若桌面内容会动态变化，静态图像不会自动跟随更新，必须另行设计同步方式。实现细节和实机效果应分别核查。

### 0.3 `windowIsTranslucent` 影响的对象与范围

先区分四个对象，才能准确理解“遮挡”：

- **Activity** 是页面及其生命周期的管理单位；`system_server` 为其维护 `ActivityRecord`。本节的“Activity 是否遮挡下层”指 WMS 在计算任务及容器内下层 Activity 的可见性时使用的遮挡状态；容器之间还会结合边界及其自身透明性向下传播遮挡，不是对每个屏幕像素做检测。
- **PhoneWindow 和 DecorView** 属于应用端窗口与 View 体系。PhoneWindow 读取主题、创建 DecorView；DecorView 是窗口的根 View，绘制窗口背景、承载应用内容及系统栏色块（§4.1～§4.2）。
- **WindowState** 是 WMS 中对一个窗口的管理对象；它关联窗口属性、几何和 Surface 管理状态。一个 Activity 可有关联的主窗口及其他窗口，不能把 Activity、DecorView 和任意一个窗口当作同一对象。
- **SurfaceFlinger 图层**承载窗口缓冲区及合成属性，决定如何与其他图层组合。Activity 的遮挡判断与图层的 opaque 标志相关，但不是同一个字段（§1.3、§5）。

同一个 `windowIsTranslucent` 主题属性分别进入两条处理路径：

1. **服务端的 Activity 遮挡路径**：创建 ActivityRecord 时，WMS 单独读取主题。对于上述非浮动、非壁纸的普通场景，`windowIsTranslucent=false` 使初始 `mOccludesParent` 为 true；可见性扫描随后可能认定其下 Activity 被遮挡（`ActivityRecord.java:2066-2074`，§1.2、§2.1）。`windowShowWallpaper`、浮动窗口、finishing 和运行时转换等分支会改变具体结果。
2. **应用端的窗口绘制路径**：PhoneWindow 将属性读入 `mIsTranslucent`，DecorView 还使用它处理调整窗口大小时的背景、窗口背景模糊及拖拽阴影（§1.1）。但这次赋值**不会直接把 DecorView 背景改为透明，也不会直接设置窗口格式或系统栏透明标志**。窗口实际画出什么，取决于背景 Drawable、应用内容和绘制过程；默认窗口格式还会受到背景 opacity 影响。

运行时调用半透明转换时，这两类状态也不是互不往来：服务端的 `setOccludesParent` 会更新 Activity 的遮挡记录、触发可见性重算，并尝试更新主窗口 Surface 的 opaque 属性；客户端还会调整渲染器的 opaque 属性（§1.3）。所以准确的结论是：**Activity 遮挡、窗口绘制和图层不透明属性各自有不同职责，部分运行时路径会同步更新它们；不能只凭一个字段推断其余状态。**

### 0.4 可见性变化、动画与状态栏背景的位置

**为什么桌面被判为不可见后还能短暂出现在屏幕上？** 在 legacy 转场中，可见性扫描可先把桌面 Activity 的 `mVisibleRequested` 设为 false，表示转场完成后的目标状态；这不等于同一时刻就对其 Surface 执行 hide。若桌面作为关闭方参加退出动画，WMS 会保留动画所需的显示状态；动画结束并满足隐藏条件后，再通过 Surface 事务通知 SurfaceFlinger。`onStop` 还经过独立的待停止队列及相关条件检查（§2.2～§2.4、§6.5）。

这解释了一个重要的观测差异：**设置页稳定显示时桌面已隐藏，不代表设置页滑入期间桌面也已隐藏。** 若进入动画尚未覆盖全屏，而退出方过早隐藏，未覆盖区域将显示剩余图层的内容，可能出现露底；判断原因必须比较每一帧的窗口位置、图层可见性与实际画面。

**leash 是什么？** leash 是窗口动画期间创建的代理 Surface。WMS 将窗口原有 Surface 重新挂到它下面，再对代理层设置位置、变换矩阵、裁剪和透明度；子层会继承这些变换。动画结束或取消时，再恢复原来的层级并清理代理层（`SurfaceAnimator.java:508-533`、`WindowAnimationSpec.java:133-155`，§6.4）。

因此，若把状态栏背景图像画进设置窗口自身的 buffer，当设置窗口的代理层从屏幕底部向上移动时，**背景图像会和窗口内容一起移动**。若该图像由独立的 SystemUI 状态栏窗口绘制，它不属于设置窗口的 Surface 子树，因而不会仅因为设置窗口的代理层移动而移动；但仍需确认系统栏窗口或其父层没有参加其他动画。这是选择绘制位置时必须验证的区别。

性能方面，隐藏桌面只说明它不再为后续画面提供可见内容；是否减少 GPU 绘制、硬件平面占用或内存，以及减少多少，需要分别测量（§7～§8）。

对本案例，可把主题配置与目标画面放在一起判断：

| Activity 声明 | 状态栏区域画出的内容 | 下层桌面 | 结果 |
|---|---|---|---|
| 半透明 | 透明 | 若桌面仍可见 | 可以透出桌面，但桌面可能继续参与绘制或合成 |
| 不透明 | 透明 | 若桌面已隐藏 | 看不到桌面；区域显示什么取决于其他图层和输出 |
| 不透明 | 在应用窗口画桌面顶部背景图像 | 若桌面已隐藏 | 稳态可显示背景图像；应用窗口转场时背景图像随窗口移动 |
| 不透明 | 在独立系统栏窗口画桌面顶部背景图像 | 若桌面已隐藏 | 可同时保留静止的状态栏背景图像并避免依赖桌面层；实际转场和成本需验证 |

表中“不透明”指 Activity 遮挡声明；应用窗口是否实际填满像素仍需另行配置。这也是本方案需要分别处理 Activity 主题、应用窗口背景和系统栏背景图像的原因。

### 0.5 从点击桌面图标到设置页稳定显示：一次完整串联

下面以**Launcher 已经显示、用户点击图标启动全屏 Setting、设备使用 legacy AppTransition** 为例。假设 Setting 是非浮动窗口、主题不显示壁纸，且没有其他窗口遮挡；这些假设用于解释主路径，不代表所有设备都满足。冷启动是否创建 StartingWindow、动画采用什么资源，以及 Launcher 何时停止，必须以设备实际记录为准。

#### 第一步：桌面发起启动，系统建立 Activity 状态

Launcher 调用启动接口后，Activity 启动请求进入 `system_server`。系统解析目标、确定任务及 Activity 的启动或复用方式，并准备相应转场；若进程或 Activity 已存在，实际路径可以与冷启动不同。本文分析的启动路径在 `ActivityStarter.java:2036-2040` 触发 Activity 可见性更新并执行转场，详见 §6.1。

此时 WMS 为 Setting 持有的 `ActivityRecord` 包含主题推导出的遮挡状态。对于本例：

- `windowIsTranslucent=true` 时，不能仅因为 Setting 已处于上层，就按不透明 Activity 推断它会遮挡下层桌面。
- `windowIsTranslucent=false` 时，在本例限定条件下，初始遮挡状态可以为 true；后续扫描可将被其覆盖的 Launcher 判为不再需要可见（`ActivityRecord.java:2066-2074`，§1.2）。

这是 **Activity 状态判断**，不需要先读取 Setting 的 buffer，因而即使 Setting 的实际背景包含透明区域，也不会自动改变这个初始声明。

#### 第二步：系统计算目标可见性，但保留转场所需状态

`ensureActivitiesVisible` 从上到下检查 Activity 与容器的遮挡状态。若本例的 Setting 被判为遮挡下层，Launcher 会进入 `makeInvisible` 路径；其中 `setVisibility(false)` 更新目标可见性，而不是立即证明桌面 Surface 已从显示输出消失（`EnsureActivitiesVisibleHelper.java:160-254`、`ActivityRecord.java:6210-6253`）。

同时，启动新 Activity 可能先暂停 Launcher。**暂停、目标不可见、窗口实际隐藏和 `onStop` 分别由不同路径推进**：退出动画或转场可能暂时保留 Launcher 的显示；待停止队列还会检查转场和暂停状态（§2.2～§2.4）。因此，抓到一条 `onPause` 日志，不能据此判断桌面已经退出合成。

#### 第三步：Setting 创建窗口，WMS 算出窗口范围

Setting 进程创建 Activity 和 PhoneWindow，并由 PhoneWindow 装配 DecorView。DecorView 作为窗口根 View 承载窗口背景和应用内容；状态栏颜色对应的色块也在应用窗口中处理（§4.1～§4.2）。

ViewRootImpl 发起 relayout，向 WMS 提交窗口属性和请求尺寸；WMS 根据任务边界、窗口参数及 insets 计算 frame，再将结果返回客户端（`ViewRootImpl.java:9084-9098`、`DisplayPolicy.java:1590-1613`，§3.1）。对于本案例的常规非浮动配置，窗口可以延伸到状态栏下方；内容是否避开状态栏还取决于 insets 如何转换为 padding 或 margin（§3.2、§3.4）。

所以，“内容从状态栏下方开始”不等于“窗口的 frame 从状态栏下方开始”。状态栏区域即使没有业务控件，仍可能属于 Setting 窗口，需要检查窗口背景和实际绘制。

#### 第四步：应用绘制第一帧，缓冲区交给图形系统

ViewRootImpl 组织 View 树的测量、布局和绘制；硬件渲染路径将窗口内容写入从 BufferQueue 获取的 buffer。生产端提交后，BLASTBufferQueue 取得 buffer，并通过 `setBuffer` 将它及同步信息放进 Surface 事务，交给 SurfaceFlinger。buffer 里是这次绘制出的像素，窗口位置、透明度和隐藏状态等则由图层状态描述（本节开头与 §3.3、§5.1）。

需要上报绘制完成的路径中，ViewRootImpl 经窗口会话发送 `finishDrawing`（`ViewRootImpl.java:4810-4816` → `WindowState.java:6400`）。**报告绘制完成、事务被处理和显示器真正显示不是同一个事件**；buffer 还要经过同步、合成和显示提交。

如果 Setting 背景在状态栏区域是不透明颜色，buffer 中相应区域会由该背景或后续内容覆盖；若背景和该处实际绘制都保留透明，最终显示还要结合剩余图层判断，不能仅凭 `statusBarColor` 推断整块区域的像素（§4.2）。

#### 第五步：转场满足就绪条件，播放进入和退出动画

在本树 legacy 路径中，`AppTransitionController` 检查转场是否就绪：对于 opening Activity，已绘制、StartingWindow 已显示或起始窗口已转移，可满足其中一项检查；还存在其他转场、壁纸等条件及超时路径（`AppTransitionController.java:1319-1400`，§6.2）。因此，转场开始不必然意味着 Setting 主窗口已经显示其真实首帧。

动画装配时，WMS 选择动画参数并为参与者创建动画。对于由 leash 驱动的窗口动画，leash 是临时代理 Surface：目标 Surface 被挂到它下面，动画逐帧修改代理层的矩阵、透明度和裁剪，SurfaceFlinger 在合成时应用这些变换（`SurfaceAnimator.java:508-533`、`WindowAnimationSpec.java:133-155`，§6.3～§6.4）。

此时若 Launcher 仍在显示，其画面可能出现在 Setting 尚未覆盖的区域；若 Launcher 已被隐藏，则这些区域由其他可见图层提供内容。**进入动画是否覆盖了该区域，以及退出窗口何时隐藏，必须放在同一帧中判断。**

#### 第六步：SurfaceFlinger 选择图层并生成显示画面

SurfaceFlinger 对当前显示输出处理已接收的事务及 buffer，按图层顺序计算可见区域：隐藏或自身不可见的层被排除；被上方不透明区域完全覆盖的部分无需贡献画面；其余内容根据合成策略交由 GPU 或硬件合成器处理（`Output.cpp:494-737`，§5.1～§5.4）。

把本案例的状态栏区域单独取出来看：

1. Launcher 图层仍在该区域可见、Setting 对应像素允许下层参与：合成结果可能包含 Launcher 的桌面画面。
2. Launcher 已隐藏，Setting 又没有在此处画出桌面背景：合成器不能再从 Launcher 图层获得那部分画面；此时需要查看 SystemUI 背景、其他层及输出，才能解释最终颜色。
3. SystemUI 状态栏窗口自己绘制了预先准备的桌面顶部背景图像：即使 Launcher 隐藏，该窗口仍可为状态栏区域提供图像；图标等其他内容再按其层级绘制。

窗口层是否仍在层树、是否对当前输出可见、是由 GPU 还是硬件合成，是三个不同问题。`dumpsys SurfaceFlinger --list` 只能列层名，不能单独回答后两个问题（§5.2、§5.6）。

#### Surface 与 buffer 如何到达 SurfaceFlinger

下面以设置主窗口的**普通硬件加速、BLAST 路径**为例。Surface 控制链负责创建和调整图层；buffer 链负责把设置窗口画出的像素交给该图层。图中省略了部分 JNI 和 Binder 包装，箭头表示调用或数据交接，不表示所有步骤都在同一线程或同一帧完成。

```text
一、创建窗口 Surface：为设置窗口建立可控制的图层

设置进程：ViewRootImpl.performTraversals()
    ↓ 需要窗口布局或 Surface
ViewRootImpl.relayoutWindow()
    ↓ IWindowSession.relayout：跨进程请求 WMS
system_server：WindowManagerService.relayoutWindow()
    ↓ 需要创建窗口 Surface 时
WindowManagerService.createSurfaceControl()
    ↓
WindowStateAnimator.createSurfaceLocked()
    ↓ 创建 WindowSurfaceController / SurfaceControl
    ↓ 向 SurfaceFlinger 创建并关联窗口内容层
    ↓ SurfaceControl 通过 relayout 结果返回设置进程
ViewRootImpl.updateBlastSurfaceIfNeeded()
    ↓ 用 SurfaceControl、尺寸和格式创建或更新 BLASTBufferQueue
BLASTBufferQueue.createSurface()
    ↓
ViewRootImpl.mSurface：应用渲染器使用的绘制入口

二、绘制并提交 buffer：把设置窗口的像素交给图层

设置进程 UI 线程：DecorView / View 树准备绘制内容
    ↓
ViewRootImpl.performDraw() → ThreadedRenderer.draw()
    ↓ 同步绘制数据并请求 RenderThread 绘制
RenderThread：DrawFrameTask → CanvasContext.draw()
    ↓ 经窗口 Surface 从 BufferQueue 获取可用 buffer（dequeue）
    ↓ 渲染窗口背景、文字、按钮等内容
    ↓ 提交绘制结果：queueBuffer
BLASTBufferQueue 的消费者取得 buffer（acquireBuffer）
    ↓ Transaction.setBuffer(SurfaceControl, buffer, fence, ...)
    ↓ 提交 Surface 事务
SurfaceFlinger 接收事务，将 buffer 与对应 Layer 关联

三、与此同时：WMS 可以通过事务调整同一图层的状态

WMS / 动画执行器
    ↓ SurfaceControl.Transaction
显示或隐藏、位置、裁剪、整体透明度、动画变换
    ↓
SurfaceFlinger 接收图层状态变化

四、汇合：SurfaceFlinger 生成当前显示画面

已接收的图层状态 + 可用于合成的窗口 buffer
    ↓ SurfaceFlinger.commit()：处理事务并锁存可用内容
    ↓ SurfaceFlinger.composite() → CompositionEngine.present()
    ↓ 按当前图层状态计算可见区域，协商合成方式
    ├─ client composition：GPU 合成，结果写入 client target
    └─ device composition：由硬件合成器处理相应图层
    ↓ 提交显示
屏幕画面：设置内容 + 当帧可见的 Launcher、SystemUI 等内容

五、buffer 用完后归还，供以后复用

SurfaceFlinger / 合成系统完成对 buffer 的使用
    ↓ release 回调与 release fence
BLASTBufferQueue 将 buffer 归还 BufferQueue
    ↓
应用以后可以再次获取并绘制
```

三个关键区别：

1. **`SurfaceControl` 与 `Surface` 职责不同。** 前者用于通过事务控制关联图层；后者是应用渲染端写入窗口内容的入口。它们与 SurfaceFlinger 的 Layer 相关，但不是同一个 Java 对象。
2. **图层状态不等于图像像素。** WMS 可以改变窗口的显隐和动画位置，而不必替应用重画按钮；应用提交新 buffer 也不代表窗口马上会被显示。
3. **提交不等于已经显示。** buffer 和事务要经过接收、同步及合成；fence 用于协调何时可以读取和复用。Launcher、Setting 和 SystemUI 各自提供内容，最终显示取决于该帧哪些图层可见。

主要源码入口：`ViewRootImpl.java:2358-2382、4050、4914`、`WindowManagerService.java:2579、2818-2832`、`WindowSurfaceController.java:90-115`、`ThreadedRenderer.java:928-949`、`DrawFrameTask.cpp:138-150`、`CanvasContext.cpp:519-591`、`BufferQueueProducer.cpp:410、847`、`BLASTBufferQueue.cpp:527-554、609-613`、`SurfaceFlinger.cpp:2025、2226、4733-4736`。窗口尺寸及多 buffer 的细节见 §3.3、§7.1。

#### 第七步：动画结束，旧窗口与资源按条件收尾

退出动画完成后，WMS 清理代理层并执行窗口动画结束处理；待停止的 Launcher 随后可能满足停止条件，收到 `onStop`。窗口 Surface 的隐藏、销毁和 buffer 的归还由各自路径推进，不保证在同一回调或同一帧全部完成（§2.4～§2.5、§6.5）。

到 Setting 稳定显示时，应分别回答四个问题：**Launcher Activity 是否停止、Launcher Surface 是否隐藏或销毁、该图层是否仍有可见区域、状态栏背景由哪个窗口提供**。只有同时确认这些状态，才能判断方案是否既保留目标画面，又让桌面退出后续合成；性能收益还需在相同场景下比较实际帧和资源数据。

---

## 1. 声明层：一个主题属性如何进入系统

### 1.1 主题属性、窗口格式与系统栏标志

**核心结论：** `windowIsTranslucent` 不直接设置 `LayoutParams.format` 或 `FLAG_TRANSLUCENT_STATUS`。前者在默认路径中受窗口背景的 opacity 影响，后者来自另一个主题属性；只修改其中一个，不能推断另外两个会随之改变。

在本仓 Android 13 源码中，`PhoneWindow.generateLayout` 读取 `windowIsTranslucent`，并将结果保存到 `mIsTranslucent`：

```java
// frameworks/base/core/java/com/android/internal/policy/PhoneWindow.java:2748
mIsTranslucent = a.getBoolean(R.styleable.Window_windowIsTranslucent, false);
```

这次赋值本身不设置窗口格式或系统栏标志。`DecorView` 使用该值处理调整窗口大小时的背景、背景模糊和拖拽阴影；服务端另行读取主题，判断 Activity 是否遮挡下层（见 §1.2）。

**调整窗口大小时的背景。** 拖拽改变多窗口尺寸时，新的窗口范围可能先于应用的新内容出现。`mResizingBackgroundDrawable` 是这段调整期间使用的背景，目的是填充应用尚未绘制的区域。它不是通常显示时的另一份应用内容，而是专门交给调整期渲染器的 Drawable。

这条处理链要分清三个时机：

1. **准备背景**：窗口背景更新时，`setWindowBackground` 根据当前 Drawable 和窗口属性生成 `mResizingBackgroundDrawable`。
2. **开始拖拽**：`onWindowDragResizeStart` 在存在 `ThreadedRenderer` 且 `!CAPTION_ON_SHELL` 时加载背景，并将其交给 `BackdropFrameRenderer`（`DecorView.java:2735-2753`）。不满足条件时，不能假定一定创建了该渲染器。
3. **绘制调整期背景**：渲染器将背景的 bounds 设为当前调整范围，再将 Drawable 画入 `mFrameAndBackdropNode`，最后调用 `drawRenderNode`（`BackdropFrameRenderer.java:352-376`）。它补画的是调整期背景；这不等于应用内容已经按新尺寸完成绘制。

窗口背景发生变化时，`setWindowBackground` 同时更新调整期背景，并将“窗口半透明或显示壁纸”作为处理条件传入（`DecorView.java:1120-1127`）：

```java
if (drawable != null) {
    mResizingBackgroundDrawable = enforceNonTranslucentBackground(drawable,
            mWindow.isTranslucent() || mWindow.isShowingWallpaper());
} else {
    mResizingBackgroundDrawable = getResizingBackgroundDrawable(
            mWindow.mBackgroundDrawable, mWindow.mBackgroundFallbackDrawable,
            mWindow.isTranslucent() || mWindow.isShowingWallpaper());
}
```

处理逻辑位于 `enforceNonTranslucentBackground`（`DecorView.java:2685-2699`）：

```java
private static Drawable enforceNonTranslucentBackground(Drawable drawable,
        boolean windowTranslucent) {
    if (!windowTranslucent && drawable instanceof ColorDrawable) {
        ColorDrawable colorDrawable = (ColorDrawable) drawable;
        int color = colorDrawable.getColor();
        if (Color.alpha(color) != 255) {
            ColorDrawable copy = (ColorDrawable) colorDrawable.getConstantState().newDrawable()
                    .mutate();
            copy.setColor(
                    Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)));
            return copy;
        }
    }
    return drawable;
}
```

这段判断有三个关键条件和一个所有权边界：

- `windowTranslucent` 是 `isTranslucent() || isShowingWallpaper()`。前者返回主题读取的 `mIsTranslucent`；后者检查 `FLAG_SHOW_WALLPAPER`（`PhoneWindow.java:616-627`）。任一为 true，就直接保留传入背景。
- 只有 `windowTranslucent == false` 且背景是 `ColorDrawable`，才检查颜色的 alpha；图片等其他 Drawable 不在这里被转换。
- 纯色的 alpha 已经是 255 时，方法直接返回原对象。alpha 小于 255 时，才创建并 `mutate` 一个副本，将副本的 alpha 改为 255。
- 被修改的是调整期使用的副本，不是传入的原 Drawable。因而，不能从这段代码推断普通显示时的窗口背景也被改成不透明。

例如，一个普通窗口的纯色背景为 `#80000000`，调整期副本会变成 `#FF000000`；若窗口声明半透明或显示壁纸，同一个背景会原样返回。这样处理会改变调整期背景是否能够透出下层，但实际画面仍取决于图层内容及渲染器是否创建。

**窗口背景模糊。** `DecorView` 仅在窗口半透明且跨窗口模糊已启用时采用请求的模糊半径，否则将半径设为零（`DecorView.java:1513-1521`）：

```java
private void updateBackgroundBlurRadius() {
    if (getViewRootImpl() == null) return;

    mBackgroundBlurRadius = mCrossWindowBlurEnabled && mWindow.isTranslucent()
            ? mOriginalBackgroundBlurRadius : 0;
    if (mBackgroundBlurDrawable == null && mBackgroundBlurRadius > 0) {
        mBackgroundBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
        updateBackgroundDrawable();
    }
```

其中，`mOriginalBackgroundBlurRadius` 保存应用请求的半径；`mCrossWindowBlurEnabled` 由跨窗口模糊开关的监听回调更新（`DecorView.java:1529-1546`）。

- **两个条件都成立**：采用请求半径；半径大于零且模糊 Drawable 尚不存在时，向 ViewRoot 创建它并更新背景。
- **任一条件不成立**：有效半径为零，已有模糊 Drawable 也会收到零半径；请求值本身仍保存在 `mOriginalBackgroundBlurRadius`，开关恢复后可以重新计算。
- **ViewRoot 尚未建立**：方法直接返回，当前调用不会更新半径。

这是窗口背景模糊的条件，不应与窗口后方 dim 层的 `FLAG_BLUR_BEHIND` 混为一谈；后者的路径及系统开关见 §7.3。

**调整期备用背景。** 尚未准备调整期背景时，`loadBackgroundDrawablesIfNeeded` 会重新选择（`DecorView.java:2541-2546`）：

```java
if (mResizingBackgroundDrawable == null) {
    mResizingBackgroundDrawable = getResizingBackgroundDrawable(mWindow.mBackgroundDrawable,
            mWindow.mBackgroundFallbackDrawable, mWindow.isTranslucent()
            || mWindow.isShowingWallpaper());
```

`getResizingBackgroundDrawable` 依次选择窗口背景、主题的备用背景和黑色背景；前两者还会经过上述不透明化处理（`DecorView.java:2665-2675`）：

```java
public static Drawable getResizingBackgroundDrawable(@Nullable Drawable backgroundDrawable,
        @Nullable Drawable fallbackDrawable, boolean windowTranslucent) {
    if (backgroundDrawable != null) {
        return enforceNonTranslucentBackground(backgroundDrawable, windowTranslucent);
    }

    if (fallbackDrawable != null) {
        return enforceNonTranslucentBackground(fallbackDrawable, windowTranslucent);
    }
    return new ColorDrawable(Color.BLACK);
}
```

因此，备用背景仅在主背景为空时参与选择；两者都为空时才新建黑色 `ColorDrawable`。选择的是调整期背景，并不意味着窗口的 `windowBackground` 主题属性被改写。

**分屏调整阴影。** 以下条件不满足时，方法直接返回，不绘制阴影（`DecorView.java:2828-2834`）：

```java
private void drawResizingShadowIfNeeded(RecordingCanvas canvas) {
    if (mResizeMode != RESIZE_MODE_DOCKED_DIVIDER || mWindow.mIsFloating
            || mWindow.isTranslucent()
            || mWindow.isShowingWallpaper()) {
        return;
    }
```

也就是说，只有拖动分屏分隔条、且窗口不是浮动窗、半透明窗或壁纸窗时，才会沿窗口右缘和下缘绘制渐变阴影。

这里改变的是拖拽期间的装饰绘制：条件不满足时跳过这两条阴影，并不直接改变窗口的像素格式、Activity 遮挡状态或下层生命周期。

上述处理不会直接设置 `LayoutParams.format`。在默认格式路径中，`DecorView.drawableChanged` 会读取背景 Drawable 的 opacity，并将其交给窗口：

```java
// frameworks/base/core/java/com/android/internal/policy/DecorView.java:1928 起 drawableChanged()
// :1962
opacity = bg.getOpacity();
// :1996-1998
mDefaultOpacity = opacity;
if (mFeatureId < 0) {
    mWindow.setDefaultWindowFormat(opacity);
```

```java
// frameworks/base/core/java/android/view/Window.java:2232-2238
protected void setDefaultWindowFormat(int format) {
    mDefaultWindowFormat = format;
    if (!mHaveWindowFormat) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.format = format;
```

因此，在使用默认窗口格式的路径上，背景 Drawable 的 opacity 参与决定 format；显式设置窗口格式等情况需要另行核对。这与 `windowIsTranslucent` 的遮挡状态判定是不同的路径。

还要排除一个常见混淆：`FLAG_TRANSLUCENT_STATUS` 来自**另一个属性** `windowTranslucentStatus`（`PhoneWindow.java:2695-2705`）：

```java
if (a.getBoolean(R.styleable.Window_windowTranslucentStatus,
        false)) {
    setFlags(FLAG_TRANSLUCENT_STATUS, FLAG_TRANSLUCENT_STATUS
            & (~getForcedWindowFlags()));
}

if (a.getBoolean(R.styleable.Window_windowTranslucentNavigation,
        false)) {
    setFlags(FLAG_TRANSLUCENT_NAVIGATION, FLAG_TRANSLUCENT_NAVIGATION
            & (~getForcedWindowFlags()));
}
```

该标志参与系统栏颜色计算（见 §4.2），与 `windowIsTranslucent` 是不同的主题属性。

### 1.2 主题属性如何影响 Activity 遮挡

**核心结论：** 对非浮动、非壁纸的普通 Activity，主题声明不透明会使 WMS 初始记录其遮挡父容器；即使该窗口实际没有画满，WMS 也不会因此自动保留下层。壁纸标志和 finishing 等分支必须另算。

创建 `ActivityRecord` 时，WMS 通过 `AttributeCache` 读取目标 Activity 的主题，并根据窗口是否半透明或浮动设置初始遮挡状态：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:2066-2074
final AttributeCache.Entry ent = AttributeCache.instance().get(packageName,
        realTheme, com.android.internal.R.styleable.Window, mUserId);

if (ent != null) {
    mOccludesParent = !ActivityInfo.isTranslucentOrFloating(ent.array)
            // This style is propagated to the main window attributes with
            // FLAG_SHOW_WALLPAPER from PhoneWindow#generateLayout.
            || ent.array.getBoolean(R.styleable.Window_windowShowWallpaper, false);
    mStyleFillsParent = mOccludesParent;
```

`isTranslucentOrFloating` 在 A13 只读两个属性（`windowSwipeToDismiss`、`windowBackgroundDimAmount` 是更老版本的口径；`ActivityInfoBase` 是 Android 14 才拆出的类）：

```java
// frameworks/base/core/java/android/content/pm/ActivityInfo.java:1775-1784
public static boolean isTranslucentOrFloating(TypedArray attributes) {
    final boolean isTranslucent =
            attributes.getBoolean(com.android.internal.R.styleable.Window_windowIsTranslucent,
                    false);
    final boolean isFloating =
            attributes.getBoolean(com.android.internal.R.styleable.Window_windowIsFloating,
                    false);

    return isFloating || isTranslucent;
```

`mOccludesParent` 的语义写在方法注释里——"not finishing, is opaque and fills the entire space of this task"：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:3088-3102
/** Returns true if this activity is not finishing, is opaque and fills the entire space of this task. */
boolean occludesParent() {
    return occludesParent(false);
}

boolean occludesParent(boolean includingFinishing) {
    if (!includingFinishing && finishing) {
        return false;
    }
    return mOccludesParent || showWallpaper();
}
```

`windowIsTranslucent` 影响 WMS 对 Activity 是否遮挡下层的初始记录，而不是逐像素描述窗口内容。上层不遮挡时，下层可能保持可见；生命周期、surface 显隐和合成还受其他窗口及转场状态影响，见第 2、5 章。

### 1.3 运行时转换：同步遮挡状态与图层属性

**核心结论：** 运行时转换同时修改 Activity 遮挡状态与渲染相关属性，但 layer 能否标为 opaque 还受窗口 format 限制；“Activity 不遮挡”和“图层像素带 alpha”不能当作同一字段。

Activity 可在运行时通过 `setTranslucent(true)` 转为半透明。客户端一方面通知 WMS 更新状态，另一方面调整本地画布的不透明属性（`Activity.java:8200-8206、8261-8273`）：

```java
// frameworks/base/core/java/android/app/Activity.java:8261-8273（节选）
mChangeCanvasToTranslucent = ActivityClient.getInstance().convertToTranslucent(
        mToken, options == null ? null : options.toBundle());
WindowManagerGlobal.getInstance().changeCanvasOpacity(mToken, false);
```

`changeCanvasOpacity` 将画布的不透明属性传给渲染器；如果根 View 请求透明区域，即使调用方要求不透明，也会传入 false（`ViewRootImpl.java:11212-11217`）：

```java
void changeCanvasOpacity(boolean opaque) {
    Log.d(mTag, "changeCanvasOpacity: opaque=" + opaque);
    opaque = opaque & ((mView.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0);
    if (mAttachInfo.mThreadedRenderer != null) {
        mAttachInfo.mThreadedRenderer.setOpaque(opaque);
    }
}
```

`ActivityClient.java:345-356` 则经 IActivityClientController 跨进程到服务端：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityClientController.java:837-873（convertToTranslucent）
// :860
r.setOccludesParent(false);
```

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:3105-3131
boolean setOccludesParent(boolean occludesParent) {
    final boolean changed = occludesParent != mOccludesParent;
    mOccludesParent = occludesParent;
    setMainWindowOpaque(occludesParent);
    mWmService.mWindowPlacerLocked.requestTraversal();

    if (changed && task != null && !occludesParent) {
        getRootTask().convertActivityToTranslucent(this);
    }
    // Always ensure visibility if this activity doesn't occlude parent, so the
    // {@link #returningOptions} of the activity under this one can be applied in
    // {@link #handleAlreadyVisible()}.
    if (changed || !occludesParent) {
        mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
    }
    return changed;
}

void setMainWindowOpaque(boolean isOpaque) {
    final WindowState win = findMainWindow();
    if (win == null) {
        return;
    }
    isOpaque = isOpaque & !PixelFormat.formatHasAlpha(win.getAttrs().format);
    win.mWinAnimator.setOpaqueLocked(isOpaque);
}
```

这里分别更新 Activity 的遮挡状态和主窗口的不透明属性，并请求窗口遍历、重新计算 Activity 可见性。

这次运行时转换同时触及三处状态，各有不同职责：

1. **应用渲染器**：`changeCanvasOpacity(..., false)` 将 renderer 的 opaque 属性设为 false；它不修改 Activity 主题，也不等同于把所有已有像素清成透明。
2. **Activity 遮挡记录**：服务端调用 `setOccludesParent(false)`，更新 `mOccludesParent`，使后续可见性计算重新评估下层 Activity。
3. **窗口 Surface 属性**：`setMainWindowOpaque` 查找主窗口，再根据请求值与窗口 format 是否带 alpha 计算 opaque，并向 Surface 事务传递。

代码还请求一次窗口遍历；当状态改变或结果为不遮挡时，调用 `ensureActivitiesVisible`。如果找不到主窗口，Surface 属性更新提前返回，但不代表前面的 Activity 状态更新没有发生。调用返回、事务提交和画面最终变化也不是同一个时刻。

主窗口的 opaque 值还受窗口格式约束：调用方要求不透明，且 format 不带 alpha，最终值才为 true。默认 format 可能由窗口背景决定（§1.1）；显式设置格式或运行时转换时应分别检查。

下发链逐跳看。第一跳，WindowStateAnimator 只做转写（`WindowStateAnimator.java:578-583`）：

```java
void setOpaqueLocked(boolean isOpaque) {
    if (mSurfaceController == null) {
        return;
    }
    mSurfaceController.setOpaque(isOpaque);
}
```

第二跳，WindowSurfaceController 把布尔写进全局 SurfaceControl 事务（`WindowSurfaceController.java:225-239`）：

```java
void setOpaque(boolean isOpaque) {
    ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isOpaque=%b: %s", isOpaque, title);

    if (mSurfaceControl == null) {
        return;
    }
    ...
    mService.openSurfaceTransaction();
    try {
        getGlobalTransaction().setOpaque(mSurfaceControl, isOpaque);
    } finally {
        mService.closeSurfaceTransaction("setOpaqueLocked");
    }
}
```

第三、四跳是薄封装：`SurfaceControl.Transaction.setOpaque`（`SurfaceControl.java:3979`）→ native `SurfaceComposerClient.cpp:1320` 打上 `layer_state_t::eLayerOpaque` 掩码。SF 端消费：

```cpp
// frameworks/native/services/surfaceflinger/BufferLayer.cpp:107-117
bool BufferLayer::isOpaque(const Layer::State& s) const {
    ...
    // :116
    return ((s.flags & layer_state_t::eLayerOpaque) != 0) || getOpacityForFormat(getPixelFormat());
}
```

还有一个 A13 的关键细节——**硬件加速窗口建层时 format 一律强制 TRANSLUCENT**：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowStateAnimator.java:366-370
final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;

mSurfaceController = new WindowSurfaceController(attrs.getTitle().toString(), format,
        flags, this, attrs.type);
```

因此，即使硬件加速路径创建层时使用带 alpha 的格式，WMS 仍可通过 `eLayerOpaque` 向 SurfaceFlinger 传达不透明属性。

### 1.4 相关消费点

下表汇总本章涉及的主要处理位置：

| 消费点 | 位置 | 作用 |
|---|---|---|
| WMS 遮挡状态记录 | `ActivityRecord.java:2066-2074` → `EnsureActivitiesVisibleHelper.java:176` | 参与下层 Activity 的可见性判定 |
| Task 透明判定 | `TaskFragment.java:987-1015`（`isTranslucent`/`isOpaqueActivity`） | Task 级遮挡传播 |
| 起始窗主题校验 | `ActivityRecord.java:2384-2398`（`:2397` translucent/floating 直接 return false） | 此校验路径拒绝半透明或浮动主题 |
| 运行时转换 | `Activity.java:8200-8285` + `ActivityClientController.java:802-873` | convertTo/FromTranslucent |
| DecorView 细节 | `DecorView.java:1122、1516、2544、2828` | resize 背景、模糊、阴影 |
| resume 联动 | `TaskFragment.java:1489-1496` | 下层强制可见性刷新 |

**TaskFragment 透明判定。** 以下代码遍历其中的 Activity，结合其遮挡状态判断容器是否透明（`TaskFragment.java:987-1015`）：

```java
boolean isTranslucent(ActivityRecord starting) {
    if (!isAttached() || isForceHidden() || isForceTranslucent()) {
        return true;
    }
    final PooledPredicate p = PooledLambda.obtainPredicate(TaskFragment::isOpaqueActivity,
            PooledLambda.__(ActivityRecord.class), starting);
    final ActivityRecord opaque = getActivity(p);
    p.recycle();
    return opaque == null;
}

private static boolean isOpaqueActivity(ActivityRecord r, ActivityRecord starting) {
    if (r.finishing) {
        // We don't factor in finishing activities when determining translucency since
        // they will be gone soon.
        return false;
    }
    if (!r.visibleIgnoringKeyguard && r != starting) {
        // Also ignore invisible activities that are not the currently starting
        // activity (about to be visible).
        return false;
    }
    if (r.occludesParent()) {
        // Root task isn't translucent if it has at least one fullscreen activity
        // that is visible.
        return true;
    }
```

**禁用起始窗**（`ActivityRecord.java:2384-2399`，validateStartingWindowTheme 内直接重读主题）：

```java
final boolean windowIsTranslucent = ent.array.getBoolean(
        com.android.internal.R.styleable.Window_windowIsTranslucent, false);
...
// If this activity is launched from system surface, ignore windowDisableStarting
if (windowIsTranslucent || windowIsFloating) {
    return false;
}
```

在这条主题校验路径中，半透明或浮动主题会被拒绝。实际启动过程是否出现其他起始内容，仍需结合启动类型和设备观测。

**resume 联动**（`TaskFragment.java:1489-1496`）：

```java
// If the previous activity is translucent, force a visibility update of
// the next activity, so that it's added to WM's opening app list, and
// transition animation can be set up properly.
// For example, pressing Home button with a translucent activity in focus.
// Launcher is already visible in this case. If we don't add it to opening
// apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
// TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
final boolean lastActivityTranslucent = inMultiWindowMode()
        || mLastPausedActivity != null && !mLastPausedActivity.occludesParent();
```

代码注释给出的例子是：从半透明 Activity 返回 Home 时，Launcher 可能已经可见，因此需要补充转场参与信息，才能正确识别壁纸转场。

---

## 2. 状态层：Activity 可见性与生命周期

### 2.1 ensureActivitiesVisible：自顶向下的一次扫描

**核心结论：** 上层 Activity 是否挡住下层，由可见性扫描传播的遮挡状态决定，不由应用背景中的透明像素逐点决定；一个充满容器且声明遮挡的上层可以使下层进入不可见流程。

可见性计算依次经过 `RootWindowContainer.ensureActivitiesVisible`、`TaskFragment.updateActivityVisibilities` 和 `EnsureActivitiesVisibleHelper.process`。`TaskFragment` 在调用前后维护可见性更新的计数（`TaskFragment.java:1253-1260`）：

```java
final void updateActivityVisibilities(@Nullable ActivityRecord starting, int configChanges,
        boolean preserveWindows, boolean notifyClients) {
    mTaskSupervisor.beginActivityVisibilityUpdate();
    try {
        mEnsureActivitiesVisibleHelper.process(
                starting, configChanges, preserveWindows, notifyClients);
    } finally {
        mTaskSupervisor.endActivityVisibilityUpdate();
```

核心循环自顶向下遍历子容器：

```java
// frameworks/base/services/core/java/com/android/server/wm/EnsureActivitiesVisibleHelper.java:90-157（节选）
void process(@Nullable ActivityRecord starting, int configChanges, boolean preserveWindows,
        boolean notifyClients) {
    reset(starting, configChanges, preserveWindows, notifyClients);
    ...
    ArrayList<TaskFragment> adjacentTaskFragments = null;
    for (int i = mTaskFragment.mChildren.size() - 1; i >= 0; --i) {
        final WindowContainer child = mTaskFragment.mChildren.get(i);
        final TaskFragment childTaskFragment = child.asTaskFragment();
        if (childTaskFragment != null
                && childTaskFragment.getTopNonFinishingActivity() != null) {
            childTaskFragment.updateActivityVisibilities(starting, configChanges,
                    preserveWindows, notifyClients);
            // The TaskFragment should fully occlude the activities below if the bounds
            // equals to its parent task, unless it is translucent.
            mBehindFullyOccludedContainer |=
                    (childTaskFragment.getBounds().equals(mTaskFragment.getBounds())
                            && !childTaskFragment.isTranslucent(starting));
            ...
        } else if (child.asActivityRecord() != null) {
            setActivityVisibilityState(child.asActivityRecord(), starting, resumeTopActivity);
        }
    }
}
```

单个 Activity 的可见性由 `setActivityVisibilityState` 根据容器和 Activity 状态判断：

```java
// frameworks/base/services/core/java/com/android/server/wm/EnsureActivitiesVisibleHelper.java:160-254（节选）
private void setActivityVisibilityState(ActivityRecord r, ActivityRecord starting,
        final boolean resumeTopActivity) {
    final boolean isTop = r == mTopRunningActivity;
    if (mAboveTop && !isTop) {
        // Ensure activities above the top-running activity to be invisible ...
        r.makeInvisible();
        return;
    }
    mAboveTop = false;

    r.updateVisibilityIgnoringKeyguard(mBehindFullyOccludedContainer);
    final boolean reallyVisible = r.shouldBeVisibleUnchecked();

    if (r.visibleIgnoringKeyguard) {
        if (r.occludesParent()) {
            // At this point, nothing else needs to be shown in this task.
            mBehindFullyOccludedContainer = true;
        } else {
            mBehindFullyOccludedContainer = false;
        }
    } ...

    if (reallyVisible) {
        ...
        } else if (r.isVisibleRequested()) {
            // If this activity is already visible, then there is nothing to do here.
            r.handleAlreadyVisible();
            ...
        } else {
            r.makeVisibleIfNeeded(mStarting, mNotifyClients);
        }
    } else {
        ...
        r.makeInvisible();
    }
```

`mBehindFullyOccludedContainer` 沿遍历方向记录上方是否已完全遮挡当前容器。代码结合该状态、Activity 的遮挡能力和其他条件，决定调用 `makeVisible` 还是 `makeInvisible`。

这段循环的因果顺序是：

1. **先判断当前 Activity**：用扫描到当前位置时的遮挡状态更新 `visibleIgnoringKeyguard`，再计算 `reallyVisible`。
2. **再更新下层的遮挡状态**：当前 Activity 在忽略锁屏时可见，且 `occludesParent()` 为 true，就将标志置为 true；否则在相应分支中复位。
3. **最后处理当前 Activity**：`reallyVisible` 为 true 时，按当前状态调用 `handleAlreadyVisible` 或 `makeVisibleIfNeeded`；为 false 时调用 `makeInvisible`。

因此，标志描述的是扫描过程中对下层的遮挡判断，不是读取窗口每个像素的结果。嵌套 TaskFragment、分屏以及锁屏等条件会影响结果，不能简单说“上面有一个窗口，下层就必然不可见”。

`updateVisibilityIgnoringKeyguard` 结合遮挡状态、当前用户和 `mLaunchTaskBehind`（`ActivityRecord.java:6157-6160`）：

```java
void updateVisibilityIgnoringKeyguard(boolean behindOccludedContainer) {
    visibleIgnoringKeyguard = (!behindOccludedContainer || mLaunchTaskBehind)
            && showToCurrentUser();
}
```

`mLaunchTaskBehind` 用于需要在其他全屏 Activity 后方保持可见的场景，例如 Recents 动画；此时不能只按普通遮挡结果判断。

上面的公式还要求 `showToCurrentUser()` 为 true。随后 `shouldBeVisibleUnchecked` 继续检查根任务、强制隐藏的 PIP 任务、非可信嵌入上的覆盖窗口，以及休眠显示或锁屏状态（`ActivityRecord.java:6106-6134`）。所以 `visibleIgnoringKeyguard` 为 true 只是中间结果，不等于最终的 `reallyVisible` 必定为 true。

**相邻分屏的合并判断**（`EnsureActivitiesVisibleHelper.java:132-146`）：

```java
if (adjacentTaskFragments != null && adjacentTaskFragments.contains(
        childTaskFragment)) {
    if (!childTaskFragment.isTranslucent(starting)
            && !childTaskFragment.getAdjacentTaskFragment().isTranslucent(
                    starting)) {
        // Everything behind two adjacent TaskFragments are occluded.
        mBehindFullyOccludedContainer = true;
    }
    continue;
}

final TaskFragment adjacentTaskFrag = childTaskFragment.getAdjacentTaskFragment();
if (adjacentTaskFrag != null) {
    if (adjacentTaskFragments == null) {
        adjacentTaskFragments = new ArrayList<>();
    }
    adjacentTaskFragments.add(adjacentTaskFrag);
}
```

对于相邻的两个 TaskFragment，代码在遇到第二个成员时合并判断；两者都不透明时，才将这对容器视为完全遮挡下层。下层最终是否停止，还要经过后续生命周期和转场处理。

因此，全屏不透明 Activity 可以使被遮挡的桌面进入不可见处理；`onStop` 的具体时机见 §2.4。

#### 实例：全屏设置任务如何使 Launcher 进入不可见流程

**先给结论：在所讨论的 legacy 启动路径中，WMS 通常在转场动画播放之前，就根据 Activity 声明和任务容器状态计算 Launcher 的目标可见性；它并不等待设置窗口动画滑到屏幕顶部，再逐像素判断是否盖住 Launcher。** 随后 Activity 的可见性提交、退出窗口 Surface 隐藏和 `onStop` 还要经过各自的转场与生命周期处理。

以下假设设置和 Launcher 在同一显示区域、各自处于不同任务；设置任务位于上方、以全屏模式运行，有运行中的设置 Activity，且没有其他窗口或容器改变判断。设备的实际任务层级和转场路径应通过 trace 核对。

**第一步：从设置主题得到 Activity 的初始遮挡状态。** WMS 创建 `ActivityRecord` 时读取设置主题（`ActivityRecord.java:2066-2074`）：

```java
mOccludesParent = !ActivityInfo.isTranslucentOrFloating(ent.array)
        || ent.array.getBoolean(R.styleable.Window_windowShowWallpaper, false);
```

`isTranslucentOrFloating` 读取 `windowIsTranslucent` 和 `windowIsFloating`（`ActivityInfo.java:1775-1784`）。在本例两个属性均为 false、`windowShowWallpaper` 为 false 时，计算得到 `mOccludesParent=true`。这是 WMS 保存的**遮挡状态**，不是对设置窗口 buffer 做像素检测；默认值、壁纸标志以及运行时转换等情况应按实际代码分支判断。

```text
windowIsTranslucent = false
windowIsFloating    = false
windowShowWallpaper = false
        ↓
isTranslucentOrFloating = false
        ↓
mOccludesParent = true
```

`occludesParent()` 还会处理 finishing 和壁纸状态（`ActivityRecord.java:3088-3102`）。因此，`windowIsTranslucent=false` 是本例推导的输入之一，不是无条件断言“任何情况下 Launcher 都不可见”。

**第二步：从设置 Activity 推导设置任务容器是否透明。** Launcher 与设置通常不在同一个任务中，不能只看单个任务内部的 Activity 扫描。`TaskFragment.isTranslucent(starting)` 查找可见或正在启动且未 finishing 的遮挡型 Activity：找到了，容器返回不透明；找不到，才返回透明（`TaskFragment.java:987-1015`）。

本例中，设置 Activity 满足这些条件且 `occludesParent()` 为 true，因此设置任务容器可被判为不透明。这里的“容器不透明”是 WMS 用于可见性判断的状态，**不保证应用实际在窗口的每个位置都画了不透明像素**。

**第三步：Launcher 的容器从上往下检查其他任务。** 当 WMS 为 Launcher 所在的 TaskFragment 计算可见性时，`getVisibility` 先检查父容器，再按 Z 序从上往下检查同级容器（`TaskFragment.java:1114-1233`）。本例的关键分支是：

```java
if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
    if (isTranslucent(other, starting)) {
        gotTranslucentFullscreen = true;
        continue;
    }
    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
}
```

在本例中，上方设置任务有运行中的 Activity、窗口模式为全屏、容器又被判定不透明，Launcher 容器便返回 `INVISIBLE`。**代码在这里检查的是窗口模式和容器透明性，不是检查设置动画当前的屏幕位置，也不是比较两张 buffer 的像素。** 多窗口、相邻分屏、父容器本身不可见及强制隐藏等情况有各自分支；不能把此处的全屏结论推广到所有布局。

**第四步：容器不可见传给 Launcher Activity。** 可见性更新遍历到 Launcher 的 TaskFragment 时，`EnsureActivitiesVisibleHelper.reset` 用该容器的 `shouldBeVisible()` 初始化遮挡状态（`EnsureActivitiesVisibleHelper.java:60-79`）：

```java
mContainerShouldBeVisible = mTaskFragment.shouldBeVisible(mStarting);
mBehindFullyOccludedContainer = !mContainerShouldBeVisible;
```

在本例中，Launcher 容器不可见，故初始 `mBehindFullyOccludedContainer=true`。接下来 Launcher Activity 的 `visibleIgnoringKeyguard` 由这个值、`mLaunchTaskBehind` 和 `showToCurrentUser()` 共同计算（`ActivityRecord.java:6157-6160`）；`shouldBeVisibleUnchecked()` 还检查锁屏、显示休眠等状态。最终 `reallyVisible=false` 时，才调用 `makeInvisible()`。这条链的中间判断不可省略：容器判为不可见并不等同于立即对 Surface 执行 hide。

**第五步：区分“目标不可见”“Activity 状态提交”和“Surface 隐藏”。** 对已经可见的 Launcher，`makeInvisible()` 调用 `setVisibility(false)`，将 `mVisibleRequested` 改为 false，并按生命周期状态将其加入待停止队列（`ActivityRecord.java:6210-6253`）。当 legacy 转场正在准备且满足延迟提交条件时，它还可能被加入 `closingApps`，暂缓提交可见性（`ActivityRecord.java:5420-5550、5557-5600`）。

转场准备就绪后，`AppTransitionController.handleAppTransitionReady` 先装配动画，再调用 `handleClosingApps`；后者调用 `commitVisibility(false)`，随后才继续 `goodToGo`（`AppTransitionController.java:278-289、1230-1240`）。因此，**Activity 的 `mVisible` 可以在动画开始阶段提交为 false，并不是一律等到动画结束才变化。**

但 `mVisible=false` 仍不等于窗口图层立刻隐藏：`WindowState.isOnScreen()` 在窗口有 Surface、策略允许显示且自身或父容器有转场动画时，可继续返回 true（`WindowState.java:2124-2137`）。退出动画结束后，相应路径的 `onExitAnimationDone` 才对窗口执行 hide 并推进清理（`WindowState.java:5348-5410`）；没有动画或条件不同，隐藏可能更早。`onStop` 则另由待停止队列处理（§2.4）。

关键判断如下：在前面的 Surface 存在、窗口未销毁且策略允许显示等条件满足时，Activity 可见和正在执行转场动画是两个可使窗口继续被视为在屏上的条件（`WindowState.java:2124-2137`）：

```java
final ActivityRecord atoken = mActivityRecord;
if (atoken != null) {
    return ((!isParentWindowHidden() && atoken.isVisible())
            || isAnimating(TRANSITION | PARENTS));
}
```

因此，一次有退出动画的典型过程可以是：WMS 先请求 Launcher 不可见 → 转场就绪时提交 Activity 可见状态 → Launcher 窗口仍因退出动画被视为在屏上 → 退出动画结束后，相应窗口才进入 hide 和清理。若没有可用动画，或窗口状态不满足上述条件，不能套用这个延迟过程。

**与 SurfaceFlinger 的逐帧遮挡不是一回事。** WMS 上述判断回答“Launcher Activity 最终是否需要可见”；SurfaceFlinger 则根据当前图层的实际变换、不透明区域和显隐状态计算每一帧的可见区域（`Output.cpp:625-652`，§5.2）。例如设置窗口从屏幕底部滑入时，WMS 可以已经决定最终隐藏 Launcher，但只要退出动画仍保留 Launcher Surface，且设置层尚未盖住顶部，顶部仍可能显示桌面。

换言之，**“WMS 已决定最终隐藏 Launcher”和“当前帧仍显示未被设置窗口覆盖的 Launcher”可以同时成立**。前者是目标可见性判断，后者是当前帧的实际合成结果。

### 2.2 从可见性请求到实际隐藏

**核心结论：** `makeInvisible` 先改变 `mVisibleRequested`；转场可以使实际可见状态和 Surface 隐藏滞后。要判断动画中是否仍能看到桌面，不能只看期望可见字段。

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:6210-6253（节选）
void makeInvisible() {
    if (!mVisibleRequested) {
        return;
    }
    try {
        final boolean canEnterPictureInPicture = checkEnterPictureInPictureState(
                "makeInvisible", true /* beforeStopping */);
        // Defer telling the client it is hidden if it can enter Pip and isn't current paused,
        // stopped or stopping. This gives it a chance to enter Pip in onPause().
        final boolean deferHidingClient = canEnterPictureInPicture
                && !isState(STARTED, STOPPING, STOPPED, PAUSED);
        setDeferHidingClient(deferHidingClient);
        setVisibility(false);                       // :6228

        switch (getState()) {
            ...
            case RESUMED: case INITIALIZING: case PAUSING: case PAUSED: case STARTED:
                addToStopping(true /* scheduleIdle */,
                        canEnterPictureInPicture /* idleDelayed */, "makeInvisible");  // :6242
                break;
        }
    } ...
}
```

`setVisibility(false)` 最终落到唯一的写点：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:5379-5389
// This is the only place that writes {@link #mVisibleRequested} ...
void setVisibleRequested(boolean visible) {
    if (visible == mVisibleRequested) {
        return;
    }
    mVisibleRequested = visible;
```

`mVisibleRequested` 记录期望可见性，`mVisible` 是 Activity 已提交的可见状态。转场期间两者可以暂时不同；但 `mVisible=false` 也不代表退出动画中的窗口 Surface 已经隐藏，具体时序见 §2.1 的实例：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowContainer.java:1408-1416
/**
 * ... this will reflect the final visibility while isVisible won't change until the
 * transition is finished.
 */
boolean isVisibleRequested() {
```

同一时刻，Activity 还被登记进转场集合，供后续动画使用：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:5585-5590
if (visible) {
    mDisplayContent.mOpeningApps.add(this);
    mEnteringAnimation = true;
} else if (mVisible) {
    mDisplayContent.mClosingApps.add(this);
```

启动流程会触发可见性重算；实际隐藏还可能受转场影响。第 6 章将结合时间线说明这两个时机。

### 2.3 Surface 的显示与隐藏

**核心结论：** Activity 被请求隐藏并不直接等于 Surface 立即 hide；窗口与容器在转场中仍可能因动画状态保持显示，真正的 hide 通过事务传给 SurfaceFlinger。

窗口 surface 的显隐不是一次性事件，而是每帧遍历时重新判定：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowStateAnimator.java:510-535（prepareSurfaceLocked 节选）
if (w.isParentWindowHidden() || !w.isOnScreen()) {
    hide(t, "prepareSurfaceLocked");
    mWallpaperControllerLocked.hideWallpapers(w);
    ...
} else if (mLastAlpha != mShownAlpha || mLastHidden) {
    ...
    boolean prepared = mSurfaceController.prepareToShowInTransaction(t, mShownAlpha);
    if (prepared && mDrawState == HAS_DRAWN) {
        if (mLastHidden) {
            if (showSurfaceRobustlyLocked(t)) {
```

`isOnScreen` 里有一条转场期的例外规则——动画中的窗口即使"不可见"也算在屏上：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowState.java:2124-2131
boolean isOnScreen() {
    ...
    return ((!isParentWindowHidden() && atoken.isVisible())
            || isAnimating(TRANSITION | PARENTS));
```

容器级同样有这条例外判断（A13 中原 applySurfaceChangesTransaction 的显隐职责移到了 prepareSurfaces）：

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:7856-7864
void prepareSurfaces() {
    final boolean show = isVisible() || isAnimating(PARENTS,
            ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_RECENTS);

    if (mSurfaceControl != null) {
        if (show && !mLastSurfaceShowing) {
            getSyncTransaction().show(mSurfaceControl);
        } else if (!show && mLastSurfaceShowing) {
            getSyncTransaction().hide(mSurfaceControl);
        }
```

`hide` 的两跳原文。第一跳带幂等保护（`WindowStateAnimator.java:228-237`）：

```java
void hide(SurfaceControl.Transaction transaction, String reason) {
    if (!mLastHidden) {
        //dump();
        mLastHidden = true;

        if (mSurfaceController != null) {
            mSurfaceController.hide(transaction, reason);
        }
    }
}
```

第二跳落到事务（`WindowSurfaceController.java:126-150`）：

```java
void hide(SurfaceControl.Transaction transaction, String reason) {
    ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE HIDE ( %s ): %s", reason, title);

    if (mSurfaceShown) {
        hideSurface(transaction);
    }
}

private void hideSurface(SurfaceControl.Transaction transaction) {
    if (mSurfaceControl == null) {
        return;
    }
    setShown(false);
    try {
        transaction.hide(mSurfaceControl);
```

`transaction.hide` 最终在 SurfaceFlinger 设置 `eLayerHidden`（见 §5.6）。调用点传入的 reason 可用于在相应日志中追踪隐藏原因；能否观察到日志取决于设备的日志配置。

### 2.4 pause 与 stop 的触发路径

**核心结论：** `onPause` 与失去 resumed 状态有关，`onStop` 还受不可见、转场和暂停完成等条件影响。下层被半透明页面覆盖时可以已暂停却仍可见，不能据 `onPause` 判断其已经停止绘制。

恢复新 Activity 时，系统可能先暂停当前 Activity；暂停与窗口是否仍然可见是两个不同的判断：

```java
// frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java:1346-1350
if (mResumedActivity != null) {
    ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Pausing %s", mResumedActivity);
    pausing |= startPausing(mTaskSupervisor.mUserLeaving, false /* uiSleeping */,
            next, "resumeTopActivity");
```

服务端先将 Activity 置为 PAUSING，再通知应用（`TaskFragment.java:1729-1739`）：

```java
ProtoLog.v(WM_DEBUG_STATES, "Moving to PAUSING: %s", prev);
mPausingActivity = prev;
mLastPausedActivity = prev;
...
prev.setState(PAUSING, "startPausingLocked");
```

**第二跳，投递事务**（`TaskFragment.java:1831-1840`）：

```java
void schedulePauseActivity(ActivityRecord prev, boolean userLeaving,
        boolean pauseImmediately, boolean autoEnteringPip, String reason) {
    ProtoLog.v(WM_DEBUG_STATES, "Enqueueing pending pause: %s", prev);
    try {
        EventLogTags.writeWmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                prev.shortComponentName, "userLeaving=" + userLeaving, reason);

        mAtmService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                prev.token, PauseActivityItem.obtain(prev.finishing, userLeaving,
                        prev.configChangeFlags, pauseImmediately, autoEnteringPip));
```

`EventLogTags.writeWmPauseActivity` 就是 `logcat -b events` 里 wm_pause_activity 事件的出处——命令手册（§8）的每条命令在这里都能找到写入端。

**第三跳，应用进程执行**（`ActivityThread.java:5520-5533`）：

```java
public void handlePauseActivity(ActivityClientRecord r, boolean finished, boolean userLeaving,
        int configChanges, boolean autoEnteringPip, PendingTransactionActions pendingActions,
        String reason) {
    if (userLeaving) {
        performUserLeavingActivity(r);
    }

    r.activity.mConfigChangeFlags |= configChanges;
    if (autoEnteringPip) {
        r.activity.mIsInPictureInPictureMode = true;
    }
    performPauseActivity(r, finished, reason, pendingActions);
```

**第四跳，真正的 onPause() 调用点**（`ActivityThread.java:5628-5643`）——含 mCalled 防漏调 super 检查：

```java
try {
    r.activity.mCalled = false;
    mInstrumentation.callActivityOnPause(r.activity);
    if (!r.activity.mCalled) {
        throw new SuperNotCalledException("Activity " + safeToComponentShortString(r.intent)
                + " did not call through to super.onPause()");
    }
} ...
r.setState(ON_PAUSE);
```

**第五跳，回执置 PAUSED**（`TaskFragment.java:1860-1863`）：

```java
if (prev != null) {
    prev.setWillCloseOrEnterPip(false);
    final boolean wasStopping = prev.isState(STOPPING);
    prev.setState(PAUSED, "completePausedLocked");
```

`wasStopping` 记录"暂停期间已被判不可见"——pause 回执到达时才补进 stopping 流程，两条链在这里汇合。

在这里分析的不可见路径中，`makeInvisible` 通过 `addToStopping` 将 Activity 加入待停止队列，而不是立即调用 `onStop`。队列在 idle 或超时处理中检查（`ActivityRecord.java:6672-6697`）：

```java
void addToStopping(boolean scheduleIdle, boolean idleDelayed, String reason) {
    if (!mTaskSupervisor.mStoppingActivities.contains(this)) {
        EventLogTags.writeWmAddToStopping(mUserId, System.identityHashCode(this),
                shortComponentName, reason);
        mTaskSupervisor.mStoppingActivities.add(this);
    }

    final Task rootTask = getRootTask();
    // If we already have a few activities waiting to stop, then give up on things going idle
    // and start clearing them out. Or if r is the last of activity of the last task the root
    // task will be empty and must be cleared immediately.
    // MAX_STOPPING_TO_FORCE = 3（ActivityRecord.java:418）：
    boolean forceIdle = mTaskSupervisor.mStoppingActivities.size() > MAX_STOPPING_TO_FORCE
            || (isRootOfTask() && rootTask.getChildCount() <= 1);
    if (scheduleIdle || forceIdle) {
        ...
        if (!idleDelayed) {
            mTaskSupervisor.scheduleIdle();
        } else {
            mTaskSupervisor.scheduleIdleTimeout(this);
        }
    } ...
}
```

处理队列时，系统检查转场及其他相关条件（`ActivityTaskSupervisor.java:2022-2062`）：

```java
private void processStoppingAndFinishingActivities(ActivityRecord launchedActivity,
        boolean processPausingActivities, String reason) {
    // Stop any activities that are scheduled to do so but have been waiting for the transition
    // animation to finish.
    ArrayList<ActivityRecord> readyToStopActivities = null;
    for (int i = mStoppingActivities.size() - 1; i >= 0; --i) {
        final ActivityRecord s = mStoppingActivities.get(i);
        final boolean animating = s.isInTransition();
        ...
        if (!animating || mService.mShuttingDown) {
            if (!processPausingActivities && s.isState(PAUSING)) {
                // Defer processing pausing activities in this iteration and reschedule
                // a delayed idle to reprocess it again
                removeIdleTimeoutForActivity(launchedActivity);
                scheduleIdleTimeout(launchedActivity);
                continue;
            }
            ...
            readyToStopActivities.add(s);
            mStoppingActivities.remove(i);
        }
    }
    ...
    for (int i = 0; i < numReadyStops; i++) {
        final ActivityRecord r = readyToStopActivities.get(i);
        if (r.isInHistory()) {
            if (r.finishing) {
                r.destroyIfPossible(reason);
            } else {
                r.stopIfPossible();
            }
        }
    }
```

队列处理包含两个容易混淆的条件：

- **等待转场**：`isInTransition()` 为 true 时，通常保留在 stopping 队列；系统正在关闭时允许越过这项等待。
- **等待 pause 完成**：即使不再等待转场，若本轮不处理 PAUSING Activity 且目标仍处于 PAUSING，系统会重新安排 idle 超时并继续等待。

通过检查后，Activity 才移出队列：仍在历史栈且未 finishing 的调用 `stopIfPossible()`；finishing 的调用 `destroyIfPossible()`。因此，“加入 stopping 队列”“动画结束”和“应用执行 `onStop`”是不同事件，不能假设发生在同一帧。

`isInTransition()` 检查自身或父容器的转场状态（`ActivityRecord.java:8686`）。满足停止条件后，`stopIfPossible` 设置 STOPPING 并投递事务（`ActivityRecord.java:6594-6610`）：

```java
stopped = false;
ProtoLog.v(WM_DEBUG_STATES, "Moving to STOPPING: %s (stop requested)", this);

setState(STOPPING, "stopIfPossible");
...
EventLogTags.writeWmStopActivity(
        mUserId, System.identityHashCode(this), shortComponentName);
mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), token,
        StopActivityItem.obtain(configChangeFlags));

mAtmService.mH.postDelayed(mStopTimeoutRunnable, STOP_TIMEOUT);
```

客户端侧（`ActivityThread.java:5801-5823`）：

```java
public void handleStopActivity(ActivityClientRecord r, int configChanges,
        PendingTransactionActions pendingActions, boolean finalStateRequest, String reason) {
    r.activity.mConfigChangeFlags |= configChanges;

    final StopInfo stopInfo = new StopInfo();
    performStopActivityInner(r, stopInfo, true /* saveState */, finalStateRequest,
            reason);
    ...
    updateVisibility(r, false);
```

`performStopActivityInner` 里先强制补 pause（`:5717-5718`）：

```java
// One must first be paused before stopped...
performPauseActivityIfNeeded(r, reason);
```

`callActivityOnStop` 执行最终回调，注释还说明了 Android P 前后保存实例状态的时机（`ActivityThread.java:5747-5771`）：

```java
private void callActivityOnStop(ActivityClientRecord r, boolean saveState, String reason) {
    // Before P onSaveInstanceState was called before onStop, starting with P it's
    // called after. Before Honeycomb state was always saved before onPause.
    final boolean shouldSaveState = saveState && !r.activity.mFinished && r.state == null
            && !r.isPreHoneycomb();
    final boolean isPreP = r.isPreP();
    if (shouldSaveState && isPreP) {
        callActivityOnSaveInstanceState(r);
    }

    try {
        r.activity.performStop(r.mPreserveWindow, reason);
    } ...
    r.setState(ON_STOP);

    if (shouldSaveState && !isPreP) {
        callActivityOnSaveInstanceState(r);
    }
```

半透明或对话框式 Activity 覆盖时，下层可能仍然可见，但当前 resumed Activity 仍可能被暂停。以下是暂停判断之一（`ActivityRecord.java:6312-6317`）：

```java
boolean shouldPauseActivity(ActivityRecord activeActivity) {
    return shouldMakeActive(activeActivity) && !isFocusable() && !isState(PAUSING, PAUSED)
            // We will only allow pausing if results is null, otherwise it will cause this
            // activity to resume before getting result
            && (results == null);
}
```

这段判断关注是否应暂停当前 Activity；窗口是否仍可见则由可见性计算单独处理。

### 2.5 停止对进程、绘制和 Surface 的影响

**核心结论：** Activity 停止会触发 ViewRoot 停止及资源处理，并影响进程状态；但进程是否回收、Surface 何时销毁分别由其他条件决定，不能把 `onStop` 当成三者同时完成的信号。

**进程状态。** WMS 汇总进程中 Activity 的状态，再由 AMS 计算进程状态和内存回收优先级（`WindowProcessController.java:1183-1218`）。下文的 adj 是内存回收优先级，procState 是进程状态，schedGroup 是 CPU 调度组：

```java
if (r.isVisibleRequested()) {
    ...
    visible = true;
    // continue the loop, in case there are multiple visible activities in
    // this process, we'd find out the one with the minimal layer, thus it'll
    // get a higher adj score.
} else if (!visible && bestInvisibleState != PAUSING) {
    if (r.isState(PAUSING, PAUSED)) {
        bestInvisibleState = PAUSING;
    } else if (r.isState(STOPPING)) {
        bestInvisibleState = STOPPING;
        // Not "finishing" if any of activity isn't finishing.
        allStoppingFinishing &= r.finishing;
    }
}
...
if (visible) {
    stateFlags |= ACTIVITY_STATE_FLAG_IS_VISIBLE;
} else if (bestInvisibleState == PAUSING) {
    stateFlags |= ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED;
} else if (bestInvisibleState == STOPPING) {
    stateFlags |= ACTIVITY_STATE_FLAG_IS_STOPPING;
```

AMS 侧按位分派三个回调（`WindowProcessController.java:1143-1156`）：

```java
public int computeOomAdjFromActivities(ComputeOomAdjCallback callback) {
    final int flags = mActivityStateFlags;
    if ((flags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0) {
        callback.onVisibleActivity();
    } else if ((flags & ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED) != 0) {
        callback.onPausedActivity();
    } else if ((flags & ACTIVITY_STATE_FLAG_IS_STOPPING) != 0) {
        callback.onStoppingActivity(
                (flags & ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING) != 0);
    } else {
        callback.onOtherActivity();
    }
```

OomAdjuster 三档处理：

```java
// frameworks/base/services/core/java/com/android/server/am/OomAdjuster.java:1446-1478（节选）
public void onVisibleActivity() {
    if (adj > ProcessList.VISIBLE_APP_ADJ) {
        adj = ProcessList.VISIBLE_APP_ADJ;
        mState.setAdjType("vis-activity");
    ...
public void onPausedActivity() {
    if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
        adj = ProcessList.PERCEPTIBLE_APP_ADJ;
        mState.setAdjType("pause-activity");
```

`onStoppingActivity` 对停止中的 Activity 使用以下处理（`OomAdjuster.java:1503-1522`）：

```java
public void onStoppingActivity(boolean finishing) {
    if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
        adj = ProcessList.PERCEPTIBLE_APP_ADJ;
        mState.setAdjType("stop-activity");
        ...
    }

    // For the process state, we will at this point consider the process to be cached. It
    // will be cached either as an activity or empty depending on whether the activity is
    // finishing. We do this so that we can treat the process as cached for purposes of
    // memory trimming (determining current memory level, trim command to send to process)
    // since there can be an arbitrary number of stopping processes and they should soon all
    // go into the cached state.
    if (!finishing) {
        if (procState > PROCESS_STATE_LAST_ACTIVITY) {
            procState = PROCESS_STATE_LAST_ACTIVITY;
```

停止中的 Activity 会影响进程状态计算；最终结果还取决于同一进程内其他组件。不能仅凭一个 Activity 的 `onStop` 推断进程一定会被回收。

**停止窗口绘制。** Activity 停止时会通知窗口管理侧更新该 Activity 的 ViewRoot 状态：

```java
// frameworks/base/core/java/android/app/Activity.java:9219-9220（performStop 内）
if (!preserveWindow && mToken != null && mParent == null) {
    WindowManagerGlobal.getInstance().setStoppedState(mToken, true);
```

`WindowManagerGlobal` 按 token 查找关联的 ViewRoot，并在需要时同步切换到目标线程执行（`WindowManagerGlobal.java:736-767`）：

```java
public void setStoppedState(IBinder token, boolean stopped) {
    ArrayList<ViewRootImpl> nonCurrentThreadRoots = null;
    synchronized (mLock) {
        int count = mViews.size();
        for (int i = count - 1; i >= 0; i--) {
            if (token == null || mParams.get(i).token == token) {
                ViewRootImpl root = mRoots.get(i);
                if (root.mThread == Thread.currentThread()) {
                    root.setWindowStopped(stopped);
                } else { ... nonCurrentThreadRoots.add(root); }
                // Recursively forward stopped state to View's attached
                // to this Window rather than the root application token,
                // e.g. PopupWindow's.
                setStoppedState(root.mAttachInfo.mWindowToken, stopped);
            }
        }
    }
    // Update the stopped state synchronously to ensure the surface won't be used after server
    // side has destroyed it. ...
    if (nonCurrentThreadRoots != null) {
        for (...) {
            root.mHandler.runWithScissors(() -> root.setWindowStopped(stopped), 0);
        }
    }
```

`ViewRootImpl` 更新停止状态并释放硬件渲染资源（`ViewRootImpl.java:2244-2262`）：

```java
void setWindowStopped(boolean stopped) {
    checkThread();
    if (mStopped != stopped) {
        mStopped = stopped;
        final ThreadedRenderer renderer = mAttachInfo.mThreadedRenderer;
        if (renderer != null) {
            renderer.setStopped(mStopped);
        }
        if (!mStopped) {
            // Make sure that relayoutWindow will be called to get valid surface because
            // the previous surface may have been released.
            mAppVisibilityChanged = true;
            scheduleTraversals();
        } else {
            if (renderer != null) {
                renderer.destroyHardwareResources(mView);
            }
```

停止时释放硬件资源；恢复时标记可见性变化并安排后续遍历。遍历中还会检查 `mStopped`（`ViewRootImpl.java:3236-3240`）：

```java
boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
```

`mStopped` 阻止该 ViewRoot 的常规遍历继续处理布局和绘制。`onPause` 本身不执行这一步；其他窗口或渲染路径是否继续产生帧，需要分别判断。

**Surface 回收。** WMS 在表面放置过程中处理待销毁的 Surface（`RootWindowContainer.java:965-982`）：

```java
// Destroy the surface of any windows that are no longer visible.
i = mWmService.mDestroySurface.size();
if (i > 0) {
    do {
        i--;
        WindowState win = mWmService.mDestroySurface.get(i);
        win.mDestroying = false;
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.mInputMethodWindow == win) {
            displayContent.setInputMethodWindowLocked(null);
        }
        if (displayContent.mWallpaperController.isWallpaperTarget(win)) {
            displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        }
        win.destroySurfaceUnchecked();
    } while (i > 0);
    mWmService.mDestroySurface.clear();
}
```

退出动画结束是加入待销毁队列的一个时机（见 §6.5）。此外，relayout 对不可见窗口也有释放 SurfaceControl 的处理（`WindowManagerService.java:2616-2635`）：

```java
if (outSurfaceControl != null) {
    if (viewVisibility == View.VISIBLE && winAnimator.hasSurface()) {
        // We already told the client to go invisible, but the message may not be
        // handled yet, or it might want to draw a last frame. If we already have a
        // surface, let the client use that, but don't create new surface at this point.
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: getSurface");
        winAnimator.mSurfaceController.getSurfaceControl(outSurfaceControl);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    } else {
        if (DEBUG_VISIBILITY) Slog.i(TAG_WM, "Releasing surface in: " + win);
        try {
            ...
            outSurfaceControl.release();
        } ...
    }
}
```

Surface 销毁会影响其关联资源的生命周期。buffer 的实际释放时机和 `meminfo` 中的变化还取决于引用与统计口径，应通过实测确认。

因此，半透明上层可能使下层保持可见及相关资源存续。是否持续绘制、参与合成或保留多少内存，取决于下层状态、窗口内容和设备实现，不能仅由主题属性确定。

---

## 3. 几何层：窗口、View 与 buffer 的尺寸

### 3.1 relayout 往返

**核心结论：** 应用请求窗口尺寸，WMS 根据窗口边界和 insets 计算 frame，再回传给应用；分屏时边界通常来自任务而不是整个屏幕，因此不能用物理屏幕尺寸直接代表应用窗口尺寸。

窗口几何由一次跨进程往返确定。客户端发起：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:9084-9098（relayoutWindow 节选）
final int requestedWidth = (int) (measuredWidth * appScale + 0.5f);
...
relayoutResult = mWindowSession.relayout(mWindow, params, requestedWidth, requestedHeight,
        viewVisibility, insetsPending, mFrameSeq, layoutSeq, mTmpFrames,
        mPendingMergedConfiguration, mSurfaceControl, mTempInsets, mTempControls,
        mRelayoutBundle);
```

服务端先记录请求尺寸（`WindowManagerService.java:2410-2413`）：

```java
WindowStateAnimator winAnimator = win.mWinAnimator;
if (viewVisibility != View.GONE) {
    win.setRequestedSize(requestedWidth, requestedHeight);
}
```

随后执行布局，确保向客户端返回更新后的窗口几何（`WindowManagerService.java:2591-2593`）：

```java
// We may be deferring layout passes at the moment, but since the client is interested
// in the new out values right now we need to force a layout.
mWindowPlacerLocked.performSurfacePlacement(true /* force */);
```

其中对每个窗口调 `DisplayPolicy.layoutWindowLw`：

```java
// frameworks/base/services/core/java/com/android/server/wm/DisplayPolicy.java:1590-1613（节选）
public void layoutWindowLw(WindowState win, WindowState attached, DisplayFrames displayFrames) {
    ...
    final boolean trustedSize = attrs == win.mAttrs;
    final int requestedWidth = trustedSize ? win.mRequestedWidth : UNSPECIFIED_LENGTH;
    ...
    mWindowLayout.computeFrames(attrs, win.getInsetsState(), displayFrames.mDisplayCutoutSafe,
            win.getBounds(), win.getWindowingMode(), requestedWidth, requestedHeight,
            compatFrame, displayFrames, win.isVisible(), surfaceInsets, outParentFrame,
            outDisplayFrame, outFrame);
    win.setFrames(sTmpClientFrames, win.mRequestedWidth, win.mRequestedHeight);
```

`win.getBounds()` 提供窗口所在容器的边界；多窗口场景中，该边界可能小于屏幕。计算结果随后写入 `WindowFrames`（`WindowState.java:1412-1425`）：

```java
void setFrames(ClientWindowFrames clientWindowFrames, int requestedWidth, int requestedHeight) {
    final WindowFrames windowFrames = mWindowFrames;
    mTmpRect.set(windowFrames.mParentFrame);

    windowFrames.mDisplayFrame.set(clientWindowFrames.displayFrame);
    windowFrames.mParentFrame.set(clientWindowFrames.parentFrame);
    windowFrames.mFrame.set(clientWindowFrames.frame);

    windowFrames.mCompatFrame.set(windowFrames.mFrame);
    if (mInvGlobalScale != 1f) {
        // Also, the scaled frame that we report to the app needs to be adjusted to be in
        // its coordinate space.
        windowFrames.mCompatFrame.scale(mInvGlobalScale);
    }
```

relayout 返回 `mCompatFrame`（`WindowState.java:4075-4082`）。在尺寸兼容模式下，WMS 使用逆缩放系数将坐标换算到应用使用的坐标系：

```java
void fillClientWindowFramesAndConfiguration(...) {
    outFrames.frame.set(mWindowFrames.mCompatFrame);
    outFrames.displayFrame.set(mWindowFrames.mDisplayFrame);
```

### 3.2 computeFrames：窗口几何的统一计算规则

**核心结论：** 默认非浮动窗口可以延伸到系统栏下方，内容避让随后由 insets 处理；状态栏占用高度不必然从窗口 frame 中扣除。

```java
// frameworks/base/core/java/android/view/WindowLayout.java:89-100（节选）
// Compute bounds restricted by insets
final Insets insets = state.calculateInsets(windowBounds, attrs.getFitInsetsTypes(),
        attrs.isFitInsetsIgnoringVisibility());
final @WindowInsets.Side.InsetsSide int sides = attrs.getFitInsetsSides();
final int left = (sides & WindowInsets.Side.LEFT) != 0 ? insets.left : 0;
final int top = (sides & WindowInsets.Side.TOP) != 0 ? insets.top : 0;
...
outDisplayFrame.set(windowBounds.left + left, windowBounds.top + top,
        windowBounds.right - right, windowBounds.bottom - bottom);

if (attachedWindowFrame == null) {
    outParentFrame.set(outDisplayFrame);
```

```java
// frameworks/base/core/java/android/view/WindowLayout.java:202-248（节选）
int rw = requestedWidth;
int rh = requestedHeight;
...
if (rw == UNSPECIFIED_LENGTH || extendedByCutout) {
    rw = attrs.width >= 0 ? attrs.width : pw;
}
...
if (attrs.width == MATCH_PARENT) {
    w = pw;
} else if (hasCompatScale) {
    w = (int) (rw * compatScale + .5f);
} else {
    w = rw;
}
if (attrs.height == MATCH_PARENT) {
    h = ph;
} ...
```

三段式的第三段原文（`WindowLayout.java:259-285`）——多窗口钳制与两步定位：

```java
if (inMultiWindowMode
        && (attrs.privateFlags & PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME) == 0) {
    // Make sure window fits in parent frame since it is in a non-fullscreen task as
    // required by {@link Gravity#apply} call.
    w = Math.min(w, pw);
    h = Math.min(h, ph);
}
...
final boolean fitToDisplay = !inMultiWindowMode
        || ((attrs.type != TYPE_BASE_APPLICATION) && !noLimits);

// Set mFrame
Gravity.apply(attrs.gravity, w, h, outParentFrame,
        (int) (x + attrs.horizontalMargin * pw),
        (int) (y + attrs.verticalMargin * ph), outFrame);

// Now make sure the window fits in the overall display frame.
if (fitToDisplay) {
    Gravity.applyDisplay(attrs.gravity, outDisplayFrame, outFrame);
}
```

这段代码先按 gravity 和 margin 在父框内定位；满足 `fitToDisplay` 时，再用 `applyDisplay` 将窗口调整到 displayFrame 范围内。对话框是否避开状态栏还取决于其 inset 配置，不能仅由这一步断定。

对于非浮动窗口，`PhoneWindow.generateLayout` 设置布局标志，并清空需要避让的 inset 类型与边：

```java
// frameworks/base/core/java/com/android/internal/policy/PhoneWindow.java:2666-2673（节选）
if (mIsFloating) {
    setLayout(WRAP_CONTENT, WRAP_CONTENT);
    setFlags(0, flagsToUpdate);
} else {
    setFlags(FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_INSET_DECOR, flagsToUpdate);   // :2671
    getAttributes().setFitInsetsSides(0);                                     // :2672
    getAttributes().setFitInsetsTypes(0);                                     // :2673
```

`fitInsetsTypes` 和 `fitInsetsSides` 分别指定需要避让的 inset 类型和边。上述默认配置不会因系统栏 inset 缩小 displayFrame；对于匹配父容器且边界为全屏的窗口，frame 因而可以延伸到系统栏下方。实际结果还受后续参数修改和窗口边界影响。客户端兼容处理也会根据标志调整避让类型：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2759-2776（adjustLayoutParamsForCompatibility 节选）
if (((sysUiVis & SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) != 0
        || (flags & FLAG_LAYOUT_IN_SCREEN) != 0)
        || (flags & FLAG_TRANSLUCENT_STATUS) != 0) {
    types &= ~Type.statusBars();
}
...
inOutParams.setFitInsetsTypes(types);
```

如果状态栏类型仍在避让集合中，相应 inset 可以使 displayFrame 内缩。实际偏移量取决于设备提供的状态栏 inset；本案例为 64 px。

### 3.3 回写、测量、buffer：三处同源

**核心结论：** 常规 `MATCH_PARENT` 窗口的根 View 和 Surface 都从 frame 推导尺寸，但 Surface 在缩放、拖拽调整或附加 surfaceInsets 时可以不同；验证内存应查看实际 buffer，而不是仅量 View。

WMS 裁定的 frame 随 relayout 回包写回客户端：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:9188-9190
setFrame(mTmpFrames.frame);
// :9229-9230
private void setFrame(Rect frame) {
    mWinFrame.set(frame);
```

然后 mWidth/mHeight 取 frame 宽高，并用它对 DecorView 做**强制精确测量**：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:3628-3631
if (mWidth != frame.width() || mHeight != frame.height()) {
    mWidth = frame.width();
    mHeight = frame.height();
}
// :3704-3718（节选）
int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width, lp.privateFlags);
int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height, lp.privateFlags);
...
performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
```

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:4683-4702
private static int getRootMeasureSpec(int windowSize, int measurement, int privateFlags) {
    int measureSpec;
    ...
    switch (rootDimension) {
        case ViewGroup.LayoutParams.MATCH_PARENT:
            // Window can't resize. Force root view to be windowSize.
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.EXACTLY);
            break;
        case ViewGroup.LayoutParams.WRAP_CONTENT:
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.AT_MOST);
            break;
        default:
            measureSpec = MeasureSpec.makeMeasureSpec(rootDimension, MeasureSpec.EXACTLY);
            break;
    }
    return measureSpec;
}
```

在所示测量路径中，`MATCH_PARENT` 使用 frame 尺寸和 `EXACTLY` 模式。Surface 尺寸也从布局结果计算，但存在以下例外：

```java
// frameworks/base/core/java/android/view/WindowLayout.java:327-366（computeSurfaceSize 节选）
if ((attrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0) {
    width = requestedWidth;
    height = requestedHeight;
} else {
    if (dragResizing) {
        width = maxBounds.width();
        height = maxBounds.height();
    } else {
        width = winFrame.width();
        height = winFrame.height();
    }
}
...
// Adjust for surface insets.
width += surfaceInsets.left + surfaceInsets.right;
height += surfaceInsets.top + surfaceInsets.bottom;
outSurfaceSize.set(width, height);
```

`surfaceInsets` 为窗口内容之外的绘制区域提供边距，例如窗口阴影。它会使 Surface 尺寸大于 frame；上面的缩放和拖拽调整分支也可能改变尺寸。

具体比较时应分别看：

1. `MATCH_PARENT` 的根 View 在此测量分支使用 frame 的宽高和 `EXACTLY`；`WRAP_CONTENT` 则是以该宽高为上限的 `AT_MOST`，不能套用相同结论。
2. 常规 Surface 尺寸从 `winFrame` 取值；`FLAG_SCALED` 改用请求尺寸，拖拽调整大小时改用 `maxBounds`。
3. 在上述尺寸基础上再加 `surfaceInsets`。因此即使根 View 与 frame 等宽高，含阴影边距的 Surface 也可以更大。

这些是布局和缓冲尺寸的关系。通过 leash 对图层做位移或缩放属于后续合成变换，不应据屏幕上的投影大小反推 buffer 一定发生了同样的调整。

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2358-2382（updateBlastSurfaceIfNeeded 节选）
if (mBlastBufferQueue != null && mBlastBufferQueue.isSameSurfaceControl(mSurfaceControl)) {
    mBlastBufferQueue.update(mSurfaceControl, mSurfaceSize.x, mSurfaceSize.y,
            mWindowAttributes.format);
    return;
}
...
mBlastBufferQueue = new BLASTBufferQueue(mTag, mSurfaceControl, mSurfaceSize.x, mSurfaceSize.y,
        mWindowAttributes.format);
```

native 端把同一尺寸同时写给生产端和显示端：

```cpp
// frameworks/native/libs/gui/BLASTBufferQueue.cpp:266-279（update 节选）
ui::Size newSize(width, height);
if (mRequestedSize != newSize) {
    mRequestedSize.set(newSize);
    mBufferItemConsumer->setDefaultBufferSize(mRequestedSize.width, mRequestedSize.height);
    ...
    mSize = mRequestedSize;
    if (mUpdateDestinationFrame) {
        t.setDestinationFrame(mSurfaceControl, Rect(newSize));
```

`setDefaultBufferSize` 设置队列的默认 buffer 尺寸；`setDestinationFrame` 在启用相应更新时设置目标矩形。常规路径的尺寸来自同一布局结果，但前面的缩放、拖拽和 surfaceInsets 分支会造成差异。下一轮 relayout 将测量值作为请求尺寸送回 WMS（`ViewRootImpl.java:9084`），供 WRAP_CONTENT 布局使用（`WindowLayout.java:202-217`）。

下面以几类窗口为例，说明布局参数和容器边界如何影响尺寸。

**手机 SystemUI 状态栏（"显式高系统窗"的代表）**（`StatusBarWindowController.java:216-241`）：

```java
private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
    int height = SystemBarUtils.getStatusBarHeightForRotation(mContext, rotation);
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT);
    lp.privateFlags |= PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
    lp.token = new Binder();
    lp.gravity = Gravity.TOP;
    lp.setFitInsetsTypes(0 /* types */);
    lp.setTitle("StatusBar");
```

高度不是常量而是 `getStatusBarHeightForRotation`（`frameworks/base/core/java/com/android/internal/policy/SystemBarUtils.java`:65-88）算出来的：取状态栏 inset 与 `status_bar_height_default` dimen 的较大者。车机 CarSystemUI 顶栏同构（`SystemBarConfigs.java:457-476`），差别在用 `providedInsets` 自报 inset 源、窗口类型是 `TYPE_STATUS_BAR_ADDITIONAL`：

```java
private WindowManager.LayoutParams getLayoutParams() {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            isHorizontalBar(mSide) ? ViewGroup.LayoutParams.MATCH_PARENT : mGirth,
            isHorizontalBar(mSide) ? mGirth : ViewGroup.LayoutParams.MATCH_PARENT,
            mapZOrderToBarType(mZOrder),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT);
    lp.setTitle(BAR_TITLE_MAP.get(mSide));
    lp.providedInsets = new InsetsFrameProvider[] {
            new InsetsFrameProvider(BAR_TYPE_MAP[mBarType]),
            new InsetsFrameProvider(BAR_GESTURE_MAP.get(mSide))
    };
    lp.setFitInsetsTypes(0);
    lp.windowAnimations = 0;
```

`windowAnimations = 0` 表示此处未指定窗口动画样式；其他转场机制仍需单独检查。

**IME**（`InputMethodService.java:1581-1589`）——宽 MATCH、高 WRAP、gravity BOTTOM、只避让状态栏和导航栏：

```java
final WindowManager.LayoutParams lp = window.getAttributes();
lp.setTitle("InputMethod");
lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
lp.width = WindowManager.LayoutParams.MATCH_PARENT;
lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
lp.gravity = Gravity.BOTTOM;
lp.setFitInsetsTypes(statusBars() | navigationBars());
lp.setFitInsetsSides(Side.all() & ~Side.BOTTOM);
```

**Toast**（`ToastPresenter.java:149-156`）——WRAP_CONTENT 的典型：

```java
private WindowManager.LayoutParams createLayoutParams() {
    WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    params.height = WindowManager.LayoutParams.WRAP_CONTENT;
    params.width = WindowManager.LayoutParams.WRAP_CONTENT;
    params.format = PixelFormat.TRANSLUCENT;
    params.windowAnimations = R.style.Animation_Toast;
    params.type = WindowManager.LayoutParams.TYPE_TOAST;
```

**分屏/freeform/PIP**：窗口 lp 不变，变的是 `win.getBounds()` 传进 computeFrames 的 windowBounds——Task 边界经 ConfigurationContainer 读取（`ConfigurationContainer.java:334-337`）：

```java
public Rect getBounds() {
    mReturnBounds.set(getConfiguration().windowConfiguration.getBounds());
    return mReturnBounds;
}
```

bounds 由 WMShell 的 TaskOrganizer 经 `Task.setBounds`/override configuration 写入（`Task.java:840`、`:2040-2066`，退出全屏时 `:2059` 回到 mLastNonFullscreenBounds）。

**letterbox/size-compat**：内容区被压进兼容 bounds、居中，四周黑边由 Letterbox 层补画（`LetterboxUiController.java:370-419`，核心是"填充域"与"内框"的差集布局）：

```java
final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
final Rect spaceToFill = transformedBounds != null
        ? transformedBounds
        : mActivityRecord.inMultiWindowMode()
                ? mActivityRecord.getTask().getBounds()
                : mActivityRecord.getRootTask().getParent().getBounds();
final Rect innerFrame = hasInheritedLetterboxBehavior()
        ? mActivityRecord.getWindowConfiguration().getBounds() : w.getFrame();
mLetterbox.layout(spaceToFill, innerFrame, mTmpPoint);
```

触发点藏在 setFrames 末尾（`WindowState.java:1468-1470`：`mActivityRecord.layoutLetterbox(this)`）——每次布局都会同时检查一次 letterbox（信箱模式黑边）是否需要更新。letterbox 背景默认黑（`LetterboxUiController.java:734-737`）：

```java
private Color getLetterboxBackgroundColor() {
    final WindowState w = mActivityRecord.findMainWindow();
    if (w == null || w.isLetterboxedForDisplayCutout()) {
        return Color.valueOf(Color.BLACK);
    }
```

其他常见情况包括：PopupWindow 使用调用方指定的宽高，子窗口受父窗口约束，拖拽调整期间 Surface 可能使用更大的边界。相关处理分别见 `PopupWindow.java:1704-1734`、`WindowLayout.computeFrames` 和 `WindowLayout.java:340-344`。

### 3.4 fitsSystemWindows：系统栏 inset 如何影响内容布局

**核心结论：** 本例中窗口仍覆盖状态栏区域，`fitsSystemWindows` 让内容通过 padding 避让；内容下移不意味着窗口顶部 64 px 被系统从 frame 中裁掉。

窗口延伸到系统栏下方时，内容仍可通过 insets 避让。先看 `ViewRootImpl` 如何将 insets 分发给 DecorView（`ViewRootImpl.java:2982-2995`）：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java:2982-2995（节选）
public void dispatchApplyInsets(View host) {
    WindowInsets insets = getWindowInsets(true /* forceConstruct */);
    ...
    host.dispatchApplyWindowInsets(insets);
```

DecorView 先处理浮动窗口和系统栏色块，再继续分发 insets（`DecorView.java:1188-1216`）：

```java
public WindowInsets onApplyWindowInsets(WindowInsets insets) {
    final WindowManager.LayoutParams attrs = mWindow.getAttributes();
    mFloatingInsets.setEmpty();
    if ((attrs.flags & FLAG_LAYOUT_IN_SCREEN) == 0) {
        // For dialog windows we want to make sure they don't go over the status bar or nav bar.
        // We consume the system insets and we will reuse them later during the measure phase.
        if (attrs.height == WindowManager.LayoutParams.WRAP_CONTENT) {
            mFloatingInsets.top = insets.getSystemWindowInsetTop();
            mFloatingInsets.bottom = insets.getSystemWindowInsetBottom();
            insets = insets.inset(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
        }
        ...
    }
    mFrameOffsets.set(insets.getSystemWindowInsetsAsRect());
    insets = updateColorViews(insets, true /* animate */);
    insets = updateStatusGuard(insets);
    ...
    return insets;
}
```

接着，ViewGroup 根据分发策略向子 View 传递 insets（`ViewGroup.java:8028-8065`）：

```java
public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
    insets = super.dispatchApplyWindowInsets(insets);
    if (insets.isConsumed()) {
        return insets;
    }
    if (View.sBrokenInsetsDispatch) {
        return brokenDispatchApplyWindowInsets(insets);
    } else {
        return newDispatchApplyWindowInsets(insets);
    }
}

private WindowInsets newDispatchApplyWindowInsets(WindowInsets insets) {
    final int count = getChildCount();
    for (int i = 0; i < count; i++) {
        getChildAt(i).dispatchApplyWindowInsets(insets);
    }
    return insets;
}
```

框架布局 `screen_simple.xml` 的根 LinearLayout 声明了 `fitsSystemWindows`（`screen_simple.xml:27`）：

```java
case com.android.internal.R.styleable.View_fitsSystemWindows:
    if (a.getBoolean(attr, false)) {
        viewFlagValues |= FITS_SYSTEM_WINDOWS;
        viewFlagMasks |= FITS_SYSTEM_WINDOWS;
    }
    break;
```

它没有覆写 onApplyWindowInsets，走 View 默认实现（`View.java:11833-11852`）：

```java
public WindowInsets onApplyWindowInsets(WindowInsets insets) {
    if ((mPrivateFlags4 & PFLAG4_FRAMEWORK_OPTIONAL_FITS_SYSTEM_WINDOWS) != 0
            && (mViewFlags & FITS_SYSTEM_WINDOWS) != 0) {
        return onApplyFrameworkOptionalFitSystemWindows(insets);
    }
    if ((mPrivateFlags3 & PFLAG3_FITTING_SYSTEM_WINDOWS) == 0) {
        // We weren't called from within a direct call to fitSystemWindows,
        // call into it as a fallback in case we're in a class that overrides it
        // and has logic to perform.
        if (fitSystemWindows(insets.getSystemWindowInsetsAsRect())) {
            return insets.consumeSystemWindowInsets();
        }
    } ...
    return insets;
}
```

消费计算在 `computeSystemWindowInsets`（`View.java:12474-12493`）——非 optional 分支把全部系统 insets 取为本地值并消费：

```java
} else {
    outLocalInsets.set(in.getSystemWindowInsetsAsRect());
    return in.consumeSystemWindowInsets().inset(outLocalInsets);
}
```

最后落到：

```java
// frameworks/base/core/java/android/view/View.java:11788-11805
private boolean fitSystemWindowsInt(Rect insets) {
    if ((mViewFlags & FITS_SYSTEM_WINDOWS) == FITS_SYSTEM_WINDOWS) {
        Rect localInsets = sThreadLocal.get();
        boolean res = computeFitSystemWindows(insets, localInsets);
        applyInsets(localInsets);
        return res;
    }
    return false;
}

private void applyInsets(Rect insets) {
    mUserPaddingStart = UNDEFINED_PADDING;
    mUserPaddingEnd = UNDEFINED_PADDING;
    mUserPaddingLeftInitial = insets.left;
    mUserPaddingRightInitial = insets.right;
    internalSetPadding(insets.left, insets.top, insets.right, insets.bottom);   // :11804
}
```

在该布局和设备配置下，顶部 inset 可成为 `mContentRoot` 的 padding；应用内容作为其子级随之下移。窗口 frame 本身不因这一步 padding 改变。

还有一条备用路径用 **margin** 而非 padding：当窗口被强制铺满（force draw bar backgrounds）但内容未声明自己处理系统栏时，updateColorViews 直接把消费掉的 inset 写成 mContentRoot 的 MarginLayoutParams（`DecorView.java:1398-1430`）：

```java
boolean consumingStatusBar = (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) == 0
        && decorFitsSystemWindows
        && (attrs.flags & FLAG_LAYOUT_IN_SCREEN) == 0
        && (attrs.flags & FLAG_LAYOUT_INSET_DECOR) == 0
        && mForceWindowDrawsBarBackgrounds
        && mLastTopInset != 0
        || (mLastShouldAlwaysConsumeSystemBars && fullscreen);

int consumedTop = consumingStatusBar ? mLastTopInset : 0;
...
if (mContentRoot != null
        && mContentRoot.getLayoutParams() instanceof MarginLayoutParams) {
    MarginLayoutParams lp = (MarginLayoutParams) mContentRoot.getLayoutParams();
    if (lp.topMargin != consumedTop || ...) {
        lp.topMargin = consumedTop;
        ...
        mContentRoot.setLayoutParams(lp);
    }
    if (insets != null) {
        insets = insets.inset(consumedLeft, consumedTop, consumedRight, consumedBottom);
    }
}
```

padding 和 margin 是两种不同的内容避让路径。应用还可通过 `setDecorFitsSystemWindows(false)` 调整默认分发行为（`PhoneWindow.java:4410-4430`），并自行处理 insets。

insets 的源头是 SystemUI 栏窗口自己。它被 addView 进 WMS 时按类型登记为 inset 源（`DisplayPolicy.java:1210-1234`）：

```java
void addWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
    switch (attrs.type) {
        ...
        case TYPE_STATUS_BAR:
            mStatusBar = win;
            final TriConsumer<DisplayFrames, WindowContainer, Rect> gestureFrameProvider =
                    (displayFrames, windowContainer, rect) -> {
                        rect.bottom = rect.top + getStatusBarHeight(displayFrames);
                        ...
                    };
            mDisplayContent.setInsetProvider(ITYPE_STATUS_BAR, win, null);
            mDisplayContent.setInsetProvider(
                    ITYPE_TOP_MANDATORY_GESTURES, win, gestureFrameProvider);
            mDisplayContent.setInsetProvider(ITYPE_TOP_TAPPABLE_ELEMENT, win, null);
            mInsetsSourceWindowsExceptIme.add(win);
            break;
```

一个状态栏窗口同时登记三个 inset 源（栏本体、顶部强制手势区、顶部可点元素）——应用能分别拿到 `Type.statusBars()` 与 `Type.systemGestures()` 等多种 inset 的由来就在这。高度读取（`DisplayPolicy.java:1472-1479`）：

```java
private int getStatusBarHeight(DisplayFrames displayFrames) {
    int statusBarHeight;
    if (mStatusBar != null) {
        statusBarHeight = mStatusBar.mAttrs.forRotation(displayFrames.mRotation).height;
    } else {
        statusBarHeight = 0;
    }
    return Math.max(statusBarHeight, displayFrames.mDisplayCutoutSafe.top);
}
```

此处的状态栏高度由窗口配置及显示切口安全区共同决定。车机系统栏还可通过 `providedInsets` 声明其提供的 inset 类型（`SystemBarConfigs.java:468-471`）；案例中的 64 px 应以设备实际配置和输出为准。

---

## 4. 绘制层：DecorView 到底画了什么

### 4.1 结构

**核心结论：** `windowBackground` 是应用窗口 DecorView 的背景，不是独立的系统栏层；应用内容未覆盖的位置，它仍可能决定窗口 buffer 画出的颜色。

`setContentView` 第一次调用触发装配：

```java
// frameworks/base/core/java/com/android/internal/policy/PhoneWindow.java:486-508（节选）
if (mContentParent == null) {
    installDecor();
}
...
mLayoutInflater.inflate(layoutResID, mContentParent);
// :2992-3005
private void installDecor() {
    ...
    mDecor = generateDecor(-1);                    // :2995 → new DecorView(...)（:2644）
    ...
    mContentParent = generateLayout(mDecor);       // :3005
```

`generateLayout`（`:2649-2977`）读主题、定 flags，然后按窗口 feature 位选一个框架布局。feature 位是应用在 setContentView 之前用 `requestWindowFeature()` 申请的窗口特性（FEATURE_NO_TITLE、FEATURE_ACTION_BAR、FEATURE_PROGRESS 等），PhoneWindow 内以位掩码记录、`getLocalFeatures()` 读取（`PhoneWindow.java:2876-2932`，节选主干）：

```java
int layoutResource;
int features = getLocalFeatures();
if ((features & ((1 << FEATURE_LEFT_ICON) | (1 << FEATURE_RIGHT_ICON))) != 0) {
    ...
    layoutResource = R.layout.screen_title_icons;
    ...
} else if ((features & ((1 << FEATURE_PROGRESS) | (1 << FEATURE_INDETERMINATE_PROGRESS))) != 0
        && (features & (1 << FEATURE_ACTION_BAR)) == 0) {
    layoutResource = R.layout.screen_progress;
} else if ((features & (1 << FEATURE_CUSTOM_TITLE)) != 0) {
    ...
    layoutResource = R.layout.screen_custom_title;
    ...
} else if ((features & (1 << FEATURE_NO_TITLE)) == 0) {
    ...
    layoutResource = R.layout.screen_title;
} else if ((features & (1 << FEATURE_ACTION_MODE_OVERLAY)) != 0) {
    layoutResource = R.layout.screen_simple_overlay_action_mode;
} else {
    // Embedded, so no decoration is needed.
    layoutResource = R.layout.screen_simple;
}

mDecor.startChanging();
mDecor.onResourcesLoaded(mLayoutInflater, layoutResource);

ViewGroup contentParent = (ViewGroup)findViewById(ID_ANDROID_CONTENT);
if (contentParent == null) {
    throw new RuntimeException("Window couldn't find content container view");
}
```

以下以 `FEATURE_NO_TITLE` 分支使用的 `screen_simple.xml` 为例（省略版权头）：

```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true"
    android:orientation="vertical">
    <ViewStub android:id="@+id/action_mode_bar_stub"
              android:inflatedId="@+id/action_mode_bar"
              android:layout="@layout/action_mode_bar"
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:theme="?attr/actionBarTheme" />
    <FrameLayout
         android:id="@android:id/content"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
         android:foregroundInsidePadding="false"
         android:foregroundGravity="fill_horizontal|top"
         android:foreground="?android:attr/windowContentOverlay" />
</LinearLayout>
```

该布局的根 LinearLayout 声明了 `fitsSystemWindows`，`@android:id/content` 是其子容器。DecorView 将框架布局加入自身：

```java
// frameworks/base/core/java/com/android/internal/policy/DecorView.java:2521-2535（onResourcesLoaded 节选）
final View root = inflater.inflate(layoutResource, null);
...
// Put it below the color views.
addView(root, 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
mContentRoot = (ViewGroup) root;
```

该分支形成如下 View 层次；其他窗口特性可能使用不同的框架布局：

```
DecorView (FrameLayout, = ViewRootImpl.mView, 背景=windowBackground)
 ├── statusBarBackground 色块 View（Gravity.TOP, 高=状态栏inset）   ← 系统栏颜色在这
 ├── navigationBarBackground 色块 View
 └── mContentRoot = screen_simple 根 LinearLayout (fitsSystemWindows=true, padding.top=64)
      ├── action_mode_bar_stub
      └── @android:id/content (FrameLayout)
           └── 你 setContentView 的布局
```

DecorView 是窗口的根 View，负责承载内容及系统栏色块、标题栏等窗口装饰，并参与 insets 分发。

**窗口背景的设置路径。** PhoneWindow 先读取主题中的背景资源（`PhoneWindow.java:2859-2861`）：

```java
if (a.hasValue(R.styleable.Window_windowBackground)) {
    mBackgroundDrawable = a.getDrawable(R.styleable.Window_windowBackground);
}
```

第二跳，交给 DecorView（`PhoneWindow.java:2950-2952`，generateLayout 尾部）：

```java
// Remaining setup -- of background and title -- that only applies
// to top-level windows.
if (getContainer() == null) {
    mDecor.setWindowBackground(mBackgroundDrawable);
```

第三跳，DecorView 收下并触发格式重估（`DecorView.java:1112-1136`）：

```java
public void setWindowBackground(Drawable drawable) {
    if (mWindow == null) {
        mPendingWindowBackground = drawable;
        return;
    }
    if (mOriginalBackgroundDrawable != drawable) {
        mOriginalBackgroundDrawable = drawable;
        updateBackgroundDrawable();
        if (drawable != null) {
            mResizingBackgroundDrawable = enforceNonTranslucentBackground(drawable,
                    mWindow.isTranslucent() || mWindow.isShowingWallpaper());
        } ...
        if (!View.sBrokenWindowBackground) {
            drawableChanged();
        }
    }
}

@Override
public void setBackgroundDrawable(Drawable background) {
    setWindowBackground(background);
}
```

第四跳，组装成最终背景（`DecorView.java:1450-1470`）——模糊层与 windowBackground 叠成 LayerDrawable，再按背景 insets 包一层 InsetDrawable：

```java
private void updateBackgroundDrawable() {
    if (mBackgroundInsets == null) {
        mBackgroundInsets = Insets.NONE;
    }
    if (mBackgroundInsets.equals(mLastBackgroundInsets)
            && mBackgroundBlurDrawable == mLastBackgroundBlurDrawable
            && mLastOriginalBackgroundDrawable == mOriginalBackgroundDrawable) {
        return;
    }

    Drawable destDrawable = mOriginalBackgroundDrawable;
    if (mBackgroundBlurDrawable != null) {
        destDrawable = new LayerDrawable(new Drawable[] {mBackgroundBlurDrawable,
                                                         mOriginalBackgroundDrawable});
    }

    if (destDrawable != null && !mBackgroundInsets.equals(Insets.NONE)) {
        destDrawable = new InsetDrawable(destDrawable,
                mBackgroundInsets.left, mBackgroundInsets.top,
                mBackgroundInsets.right, mBackgroundInsets.bottom) { ... };
    }
    super.setBackgroundDrawable(destDrawable);
```

`super.setBackgroundDrawable` 将结果设为 View 背景；最终像素还取决于背景、子 View 和后续绘制。背景 opacity 也参与默认窗口格式的计算（§1.1）。

### 4.2 statusBarColor 与应用窗口的色块 View

**核心结论：** 系统栏颜色由 DecorView 中的色块 View 绘制；最终颜色透明时，该色块可以不显示。透明色并不保证状态栏区域显示桌面，还要看背景、应用内容和下层是否可见。

入口两跳。`Window.setStatusBarColor`（`Window.java:2804`，抽象）→ PhoneWindow 实现（`PhoneWindow.java:4253-4263`）：

```java
public void setStatusBarColor(int color) {
    mStatusBarColor = color;
    mForcedStatusBarColor = true;
    if (mDecor != null) {
        mDecor.updateColorViews(null, false /* animate */);
    }
    final WindowControllerCallback callback = getWindowControllerCallback();
    if (callback != null) {
        getWindowControllerCallback().updateStatusBarColor(color);
    }
}
```

注意 `mForcedStatusBarColor`——运行期 set 的值优先于主题值；以及 callback 把颜色同步给 WM 侧（用于 TaskDescription/快照）。`updateColorViews`（`DecorView.java:1269-1440`）先取色再交给建 View 的函数。取色器全文（`DecorView.java:1574-1609`），四分支优先级一次看清：

```java
private int calculateStatusBarColor(@Appearance int appearance) {
    return calculateBarColor(mWindow.getAttributes().flags, FLAG_TRANSLUCENT_STATUS,
            mSemiTransparentBarColor, mWindow.mStatusBarColor,
            appearance, APPEARANCE_LIGHT_STATUS_BARS,
            mWindow.mEnsureStatusBarContrastWhenTransparent);
}

public static int calculateBarColor(int flags, int translucentFlag, int semiTransparentBarColor,
        int barColor, @Appearance int appearance, @Appearance int lightAppearanceFlag,
        boolean scrimTransparent) {
    if ((flags & translucentFlag) != 0) {
        return semiTransparentBarColor;
    } else if ((flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
        return Color.BLACK;
    } else if (scrimTransparent && Color.alpha(barColor) == 0) {
        boolean light = (appearance & lightAppearanceFlag) != 0;
        return light ? SCRIM_LIGHT : semiTransparentBarColor;
    } else {
        return barColor;
    }
}
```

`FLAG_TRANSLUCENT_STATUS`、对比度保护配置和 `APPEARANCE_LIGHT_STATUS_BARS` 分别参与这段颜色选择。浅色状态栏外观表示应使用深色图标；具体分支及颜色见上面的代码。案例中若最终颜色为 `#00000000`，还需经过下方的可见性判断。

四个分支有明确的先后顺序，不能只看应用设置的颜色：

1. 设置了 `FLAG_TRANSLUCENT_STATUS`：返回系统提供的半透明栏色。
2. 没有该标志，且没有 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`：返回黑色。
3. 已声明绘制系统栏背景，但请求透明色且启用了透明栏对比度保护：根据栏外观选择用于对比度保护的颜色。
4. 上述条件都不满足：返回应用或主题提供的 `barColor`。

例如，应用设置 `statusBarColor=#00000000` 不足以证明最终色块颜色一定透明：若前面的标志或对比度分支命中，返回值会不同。应先查最终 `color`，再查下面的 View 显隐条件。

取到的颜色交给 `updateColorViewInt`，它决定色块 View 的创建与显隐（`DecorView.java:1638-1700`，节选）：

```java
private void updateColorViewInt(final ColorViewState state, int color, int dividerColor,
        int size, boolean verticalBar, boolean seascape, int sideMargin, boolean animate,
        boolean force, WindowInsetsController controller) {
    state.present = state.attributes.isPresent(
            (controller.isRequestedVisible(state.attributes.insetsType)
                    || mLastShouldAlwaysConsumeSystemBars),
            mWindow.getAttributes().flags, force);
    boolean show = state.attributes.isVisible(state.present, color,
            mWindow.getAttributes().flags, force);
    boolean showView = show && !isResizing() && !mHasCaption && size > 0;
    ...
    if (view == null) {
        if (showView) {
            state.view = view = new View(mContext);
            setColor(view, color, dividerColor, verticalBar, seascape);
            ...
            view.setId(state.attributes.id);            // com.android.internal.R.id.statusBarBackground
            view.setVisibility(INVISIBLE);
            ...
            LayoutParams lp = new LayoutParams(resolvedWidth, resolvedHeight, resolvedGravity);
            ...
            addView(view, lp);
```

色块就是一个普通 View，加在 DecorView 顶层、贴窗口顶边、高度等于状态栏 inset。其中 `isVisible` 是决定"透明色到底画不画"的判定：

```java
// frameworks/base/core/java/com/android/internal/policy/DecorView.java:3110-3120（ColorViewAttributes）
boolean isPresent(...) {
    return requestedVisible
            && ((windowFlags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0 || force);
}
boolean isVisible(boolean present, int color, int windowFlags, boolean force) {
    return present
            && (color & Color.BLACK) != 0
            && ((windowFlags & translucentFlag) == 0  || force);
}
```

当传给 `isVisible` 的最终颜色为 `#00000000` 时，alpha 检查不通过，色块不会显示。该区域的最终像素还取决于窗口背景、内容绘制及其他图层；仅凭色块不可见不能断定该区域一定透明。

`isPresent` 和 `isVisible` 也分别回答两个问题：前者检查栏是否请求可见，以及窗口是否声明绘制背景或被强制绘制；后者在此基础上检查颜色 alpha 和半透明标志。随后 `showView` 还排除了窗口正在调整大小、有 caption 或尺寸不大于零等情况。只有把这些条件连起来，才能判断色块是否创建或显示；不能将 `statusBarColor` 与 View 的最终状态直接画等号。

应用窗口的色块与 SystemUI 的系统栏窗口是独立的。系统通过窗口外观状态协调栏背景；SystemUI 窗口的具体类型和透明度需按设备实现确认。下面是 WMS 对栏背景的判断：

```java
// frameworks/base/services/core/java/com/android/server/wm/DisplayPolicy.java:2643-2676（节选）
private boolean drawsBarBackground(WindowState win) {
    ...
    final boolean drawsSystemBars =
            (win.getAttrs().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;   // :2648-2649
    ...
int configureStatusBarOpacity(int appearance) {
    ...
    drawBackground &= drawsBarBackground(window);
    ...
    if (!drawBackground) {
        appearance &= ~APPEARANCE_OPAQUE_STATUS_BARS;
```

SystemUI 侧据此切换自己的栏底（`CentralSurfacesImpl.java:2130-2147`，全文）：

```java
private @TransitionMode int barMode(boolean isTransient, int appearance) {
    final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_STATUS_BARS;
    if (mOngoingCallController.hasOngoingCall() && mIsFullscreen) {
        return MODE_SEMI_TRANSPARENT;
    } else if (isTransient) {
        return MODE_SEMI_TRANSPARENT;
    } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
        return MODE_LIGHTS_OUT;
    } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
        return MODE_LIGHTS_OUT_TRANSPARENT;
    } else if ((appearance & APPEARANCE_OPAQUE_STATUS_BARS) != 0) {
        return MODE_OPAQUE;
    } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS) != 0) {
        return MODE_SEMI_TRANSPARENT;
    } else {
        return MODE_TRANSPARENT;
    }
}
```

appearance 里没有 OPAQUE 位（被 WMS 摘掉了）且无其他修饰 → 落到最后的 MODE_TRANSPARENT。BarTransitions 按模式取色：

```java
// frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/BarTransitions.java:336-352（draw 节选）
} else if (mMode == MODE_TRANSPARENT || mMode == MODE_LIGHTS_OUT_TRANSPARENT) {
    targetColor = mTransparent;      // system_bar_background_transparent = #00000000
```

在所示配置下，应用窗口的色块和 SystemUI 栏背景通过外观状态配合。实际栏色还取决于强制绘制、对比度保护及其他窗口状态。

`windowDrawsSystemBarBackgrounds=false` 的效果因此也是双重的：不设 flag → 色块 isPresent=false（无 force 时）+ WMS 保留 OPAQUE 外观位 → SystemUI 自画不透明黑底。**但 A13 有一个默认强制层**：

```java
// frameworks/base/core/java/com/android/internal/policy/PhoneWindow.java:2772-2782（节选）
if (!targetPreL && a.getBoolean(
        R.styleable.Window_windowDrawsSystemBarBackgrounds, false)) {
    setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, ...);          // :2778-2779
}
if (mDecor.mForceWindowDrawsBarBackgrounds) {
    params.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;   // :2781
```

`config_forceWindowDrawsStatusBarBackground` 默认 true（`frameworks/base/core/res/res/values/config.xml`:3745-3748）→ `DecorView.java:325-328` 读入 → 色块判定里的 `|| force` 放行，颜色取 `calculateBarColor` 的强制分支：

```java
// frameworks/base/core/java/com/android/internal/policy/DecorView.java:1595-1609（节选）
} else if ((flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
    return Color.BLACK;
```

在本树默认启用强制绘制且命中该分支时，不声明自绘可能得到黑色栏底；这与通过 insets 调整窗口或内容位置是不同的处理。

---

## 5. 合成层：SurfaceFlinger 的一帧

### 5.1 一帧的生命周期

A13 的 SF 主循环拆成 commit/composite 两段，由调度器按 vsync 驱动：

```cpp
// frameworks/native/services/surfaceflinger/Scheduler/MessageQueue.cpp:55-66
if (!compositor.commit(frameTime, mVsyncId, mExpectedVsyncTime)) {
    return;
}
compositor.composite(frameTime, mVsyncId);
```

commit（`SurfaceFlinger.cpp:2025`）应用事务、latch 各层新 buffer；composite（`:2226`）按 Z 序收集层交给合成引擎：

```cpp
// frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp:2244-2247
mDrawingState.traverseInZOrder([&refreshArgs](Layer* layer) {
    if (auto layerFE = layer->getCompositionEngineLayerFE())
        refreshArgs.layers.push_back(layerFE);
```

随后进入合成引擎。`CompositionEngine::present` 的骨架（`CompositionEngine.cpp:104-131`）：

```cpp
void CompositionEngine::present(CompositionRefreshArgs& args) {
    ATRACE_CALL();
    ALOGV(__FUNCTION__);

    preComposition(args);

    {
        // latchedLayers is used to track the set of front-end layer state that
        // has been latched across all outputs for the prepare step, and is not
        // needed for anything else.
        LayerFESet latchedLayers;

        for (const auto& output : args.outputs) {
            output->prepare(args, latchedLayers);
        }
    }

    updateLayerStateFromFE(args);

    for (const auto& output : args.outputs) {
        output->present(args);
    }
}
```

`preComposition` 检查后续更新需求；`prepare` 为各输出准备图层状态；随后更新前端状态并逐个输出执行 `present`。单个输出的主要步骤如下（`Output.cpp:444-467`）：

```cpp
void Output::present(const compositionengine::CompositionRefreshArgs& refreshArgs) {
    ATRACE_CALL();
    ALOGV(__FUNCTION__);

    updateColorProfile(refreshArgs);
    updateCompositionState(refreshArgs);
    planComposition();
    writeCompositionState(refreshArgs);
    setColorTransform(refreshArgs);
    beginFrame();

    GpuCompositionResult result;
    const bool predictCompositionStrategy = canPredictCompositionStrategy(refreshArgs);
    if (predictCompositionStrategy) {
        result = prepareFrameAsync(refreshArgs);
    } else {
        prepareFrame();
    }

    devOptRepaintFlash(refreshArgs);
    finishFrame(refreshArgs, std::move(result));
    postFramebuffer();
    renderCachedSets(refreshArgs);
}
```

依次计算图层合成状态、写入 HWC、判断是否需要重合成、协商合成方式并提交结果。具体的过滤、混合和 GPU 绘制见 §5.2～§5.7。

### 5.2 ensureOutputLayerIfVisible：进入合成列表的三个过滤条件

**核心结论：** SF 先检查层本身可见，再扣除上方不透明覆盖，最后检查当前输出内是否还有绘制区域；三者任一结果为空，该层就不为本输出生成合成记录。

层栈重建时**自顶向下**遍历（`Output.cpp:494-505`，"Evaluate the layers from front to back"），每层要通过三个过滤条件：

```cpp
// frameworks/native/services/surfaceflinger/CompositionEngine/src/Output.cpp:512-737（节选）
void Output::ensureOutputLayerIfVisible(sp<compositionengine::LayerFE>& layerFE,
                                        compositionengine::Output::CoverageState& coverage) {
    ...
    // handle hidden surfaces by setting the visible region to empty
    if (CC_UNLIKELY(!layerFEState->isVisible)) {
        return;
    }
    ...
    /*
     * visibleRegion: area of a surface that is visible on screen and not fully
     * transparent. This is essentially the layer's footprint minus the opaque
     * regions above it. Areas covered by a translucent surface are considered
     * visible.
     */
    ...
    // compute the opaque region
    const auto layerOrientation = tr.getOrientation();
    if (layerFEState->isOpaque && ((layerOrientation & ui::Transform::ROT_INVALID) == 0)) {
        // If we one of the simple category of transforms (0/90/180/270 rotation
        // + any flip), then the opaque region is the layer's footprint.
        opaqueRegion.set(visibleRect);
    }
    ...
    // subtract the opaque region covered by the layers above us
    visibleRegion.subtractSelf(coverage.aboveOpaqueLayers);       // :652

    if (visibleRegion.isEmpty()) {
        return;
    }
    ...
    // Update accumAboveOpaqueLayers for next (lower) layer
    coverage.aboveOpaqueLayers.orSelf(opaqueRegion);              // :703
    ...
    Region drawRegion(outputState.transform.transform(visibleNonTransparentRegion));
    drawRegion.andSelf(outputState.displaySpace.getBoundsAsRect());
    if (drawRegion.isEmpty()) {
        return;
    }
    ...
    auto result = ensureOutputLayer(prevOutputLayerIndex, layerFE);
    auto& outputLayerState = result->editState();
    outputLayerState.visibleRegion = visibleRegion;
    ...
```

`isVisible` 的生成端：

```cpp
// frameworks/native/services/surfaceflinger/BufferLayer.cpp:123-126
bool BufferLayer::isVisible() const {
    return !isHiddenByPolicy() && getAlpha() > 0.0f &&
            (mBufferInfo.mBuffer != nullptr || mSidebandStream != nullptr);
}
```

alpha 含父级连乘（`Layer.cpp:1928-1933`）。OutputLayer 是层通过三个过滤条件后生成的"本帧合成记录"，携带 visibleRegion、displayFrame、blendMode 等逐帧数据，是写给 HWC 与 GPU 合成请求的直接数据结构——不生成它，这一层在本帧合成里就等于不存在。

`aboveOpaqueLayers` 累积上方不透明区域；下层的可见区域被完全覆盖时，不会生成对应的 OutputLayer。半透明层通常不能以自身区域作同样的不透明遮挡，但下层是否参与合成、参与范围及采用何种合成方式还取决于其他层和输出状态。

三个返回条件分别解决不同问题：

1. **层本身是否可见**：例如 BufferLayer 被策略隐藏、继承后的 alpha 为零，或既没有 buffer 也没有 sideband 内容时，`isVisible()` 为 false。
2. **是否被上方不透明区域完全覆盖**：从该层的 `visibleRegion` 扣除 `coverage.aboveOpaqueLayers`；扣除后为空才在此处返回。只覆盖一部分时，剩余区域仍可能参与合成。
3. **在当前输出上是否有绘制区域**：将区域变换到输出坐标并与显示边界求交，结果为空则跳过当前输出。

通过三项检查才创建或复用 `OutputLayer`。因此，“层仍存在于层树”“层对某个输出可见”和“最终由 GPU 还是 HWC 合成”是三个不同问题，不能用 `--list` 的名称列表代替后两项。

### 5.3 混合模式：opaque 带来的另一半收益

**核心结论：** 图层不透明且整体 alpha 为 1 时可使用 `NONE` 混合；带透明度时需按 alpha 混合。是否能遮挡下层还要检查圆角等条件，不能仅凭 blendMode 推断。

```cpp
// frameworks/native/services/surfaceflinger/Layer.cpp:430-461（prepareBasicGeometryCompositionState 节选）
const auto alpha = static_cast<float>(getAlpha());
const bool opaque = isOpaque(drawingState);
const bool usesRoundedCorners = hasRoundedCorners();

auto blendMode = Hwc2::IComposerClient::BlendMode::NONE;
if (!opaque || alpha != 1.0f) {
    blendMode = mPremultipliedAlpha ? Hwc2::IComposerClient::BlendMode::PREMULTIPLIED
                                    : Hwc2::IComposerClient::BlendMode::COVERAGE;
}
...
compositionState->isVisible = isVisible();
compositionState->isOpaque = opaque && !usesRoundedCorners && alpha == 1.f;
...
compositionState->blendMode = static_cast<Hwc2::IComposerClient::BlendMode>(blendMode);
```

代码根据 opaque 和 alpha 选择混合模式：不透明且整体 alpha 为 1 时可使用 `NONE`，否则按图层的 alpha 表示选择混合方式。圆角等条件也会影响不透明判定。结果随后写入 HWC（`OutputLayer.cpp:477-498`）：

这里有两项相近但不同的结果：`blendMode` 由图层自身 opaque 状态及整体 alpha 选择；用于遮挡计算的 `compositionState->isOpaque` 还要求没有圆角。`PREMULTIPLIED` 表示输入 RGB 已乘逐像素 alpha，`COVERAGE` 则由混合阶段处理该乘法；两者都不同于可直接覆盖的 `NONE`。整体 alpha 还会受父层影响，因此动画改变父层透明度时，子层即使内容未重绘，合成结果也可能变化。

```cpp
void OutputLayer::writeOutputIndependentGeometryStateToHWC(
        HWC2::Layer* hwcLayer, const LayerFECompositionState& outputIndependentState,
        bool skipLayer) {
    // If there is a peekThroughLayer, then this layer has a hole in it. We need to use
    // PREMULTIPLIED so it will peek through.
    const auto& overrideInfo = getState().overrideInfo;
    const auto blendMode = overrideInfo.buffer || overrideInfo.peekThroughLayer
            ? hardware::graphics::composer::hal::BlendMode::PREMULTIPLIED
            : outputIndependentState.blendMode;
    if (auto error = hwcLayer->setBlendMode(blendMode); error != hal::Error::NONE) {
        ALOGE("[%s] Failed to set blend mode %s: %s (%d)", getLayerFE().getDebugName(),
              toString(blendMode).c_str(), to_string(error).data(),
              static_cast<int32_t>(error));
    }

    const float alpha = skipLayer
            ? 0.0f
            : (getState().overrideInfo.buffer ? 1.0f : outputIndependentState.alpha);
    ...
    if (auto error = hwcLayer->setPlaneAlpha(alpha); error != hal::Error::NONE) {
```

setPlaneAlpha 设置的是 **plane alpha**（HWC 术语：作用在整层上的统一透明度，与 buffer 内逐像素的 alpha 相乘）——WMS 动画里 leash 的 setAlpha 最终就落在这里，交接文档观测到的"任务透明度逐步下降"，硬件侧的物理形态就是 plane alpha 变小。

GPU 路径的 source-over 就是 SkPaint 的默认值——`SkiaGLRenderEngine.cpp:1075` 一个普通的 `SkPaint paint;`，全函数对正常层从不改混合模式。A13 没有旧文的 `ui::BlendMode::Skip`，它的替身是客户端合成的 `disableBlending`（→ `SkBlendMode::kSrc`）与 `skipContentDraw`。

### 5.4 GPU 合成：每层一次纹理采样

**核心结论：** 进入 client composition 的可见内容需要交给 RenderEngine 处理；如果该层由 HWC 处理或缓存命中，就不能把它简单记成一次全屏 GPU 读取。

```cpp
// frameworks/native/libs/renderengine/skia/SkiaGLRenderEngine.cpp:916-917
for (const auto& layer : layers) {
    ATRACE_FORMAT("DrawLayer: %s", layer.name.c_str());
// :1076-1094（节选）
if (layer.source.buffer.buffer) {
    ATRACE_NAME("DrawImage");
    ...
    imageTextureRef = std::make_shared<AutoBackendTexture::LocalRef>(
            grContext, item.buffer->getBuffer()->toAHardwareBuffer(), false, mTextureCleanupMgr);
// :1268
canvas->drawRect(bounds.rect(), paint);
```

在这条 GPU 绘制路径中，buffer 被作为纹理参与绘制。采样的实际成本受裁剪、缓存、格式和设备实现影响。满足以下条件的层可以跳过内容绘制：

```cpp
// frameworks/native/libs/renderengine/skia/SkiaGLRenderEngine.cpp:1061-1065
if (layer.skipContentDraw ||
    (layer.alpha == 0 && !requiresLinearEffect && !layer.disableBlending &&
     (!displayColorTransform || displayColorTransform->isAlphaUnchanged()))) {
    continue;
}
```

带 buffer 的可见层可请求 DEVICE composition，最终由 HWC 在 `validateDisplay` 中决定是否接受（`BufferLayer.cpp:307-310`、`Display.cpp:258-301`）。可用硬件平面和限制取决于设备实现。

### 5.5 黑色像素的完整推导

**核心结论：** 被隐藏的 Launcher 不能为透明区域提供像素。所引 GPU 路径先清为透明黑；输出中若没有其他内容补足该区域，可能看到黑色，但具体黑闪来源必须结合实际输出和图层确认。

以下分析限定在所示 GPU client composition 路径：透明区域没有其他图层贡献时，清零值和输出处理如何影响画面。其他硬件合成路径需结合设备验证。

**(1) 该绘制路径将画布清为透明黑**：

```cpp
// frameworks/native/libs/renderengine/skia/SkiaGLRenderEngine.cpp:908-911（drawLayersInternal 内）
AutoSaveRestore surfaceAutoSaveRestore(canvas);
// Clear the entire canvas with a transparent black to prevent ghost images.
canvas->clear(SK_ColorTRANSPARENT);
initCanvas(canvas, display);
```

**(2) 合成结果交给 HWC。** client target 是 GPU 合成结果所在的输出缓冲，`FramebufferSurface.cpp:148` 将它交给 HWC。该调用点本身不说明 alpha 在最终显示中的处理；判断某个透明区域的最终颜色，还需结合其他图层、显示输出和设备实现。

**(3) 层全消失时补一帧黑**：

```cpp
// frameworks/native/services/surfaceflinger/CompositionEngine/src/Output.cpp:999-1013（beginFrame 节选）
// If nothing has changed (!dirty), don't recompose.
// If something changed, but we don't currently have any visible layers,
//   and didn't when we last did a composition, then skip it this time.
// The second rule does two things:
// - When all layers are removed from a display, we'll emit one black
//   frame, then nothing more until we get new layers.
mMustRecompose = dirty && !(empty && wasEmpty);
```

另需区分 blackout 与清除操作：blackout 可为受保护内容生成不透明黑色（`Layer.cpp:671-681`）；HWC 区域对应的 `clearContent` 使用透明内容（`Output.cpp:1453`、`Layer.cpp:696-699`）。

对于设置覆盖桌面的案例，需依次确认：Launcher 是否被隐藏、设置窗口的对应区域实际绘制了什么、该区域还有哪些图层，以及最终采用何种合成路径。透明黑清零是其中一条源码事实，不能单独证明设备上出现黑色的原因。

### 5.6 hide 的传导链

**核心结论：** `hide` 设置的是 layer 的隐藏状态，不等于立刻销毁 layer；所以 `dumpsys SurfaceFlinger --list` 中仍有名称，并不证明该层仍参与合成。

```cpp
// frameworks/native/libs/gui/SurfaceComposerClient.cpp:1259-1262
SurfaceComposerClient::Transaction& SurfaceComposerClient::Transaction::hide(
        const sp<SurfaceControl>& sc) {
    return setFlags(sc, layer_state_t::eLayerHidden, layer_state_t::eLayerHidden);
}
```

第二跳，binder 进 SF 后按掩码分发（`SurfaceFlinger.cpp:4573-4575`，applyTransactionState 内）：

```cpp
if (what & layer_state_t::eFlagsChanged) {
    if (layer->setFlags(s.flags, s.mask)) flags |= eTraversalNeeded;
}
```

第三跳，Layer 写 drawing state（`Layer.cpp:1049-1057`）——flags 是掩码式更新，sequence++ 触发重算：

```cpp
bool Layer::setFlags(uint32_t flags, uint32_t mask) {
    const uint32_t newFlags = (mDrawingState.flags & ~mask) | (flags & mask);
    if (mDrawingState.flags == newFlags) return false;
    mDrawingState.sequence++;
    mDrawingState.flags = newFlags;
    mDrawingState.modified = true;
    setTransactionFlags(eTransactionNeeded);
    return true;
}
```

第四跳，可见性判定读取标志位（`Layer.cpp:1402-1421`）——注意它先递归父级与 zOrderRelativeOf，**任何一层祖先 hidden，整棵子树都不可见**：

```cpp
bool Layer::isHiddenByPolicy() const {
    const State& s(mDrawingState);
    const auto& parent = mDrawingParent.promote();
    if (parent != nullptr && parent->isHiddenByPolicy()) {
        return true;
    }
    if (usingRelativeZ(LayerVector::StateSet::Drawing)) {
        auto zOrderRelativeOf = mDrawingState.zOrderRelativeOf.promote();
        if (zOrderRelativeOf != nullptr) {
            if (zOrderRelativeOf->isHiddenByPolicy()) {
                return true;
            }
        }
    }
    if (CC_UNLIKELY(!isTransformValid())) {
        ALOGW("Hide layer %s because it has invalid transformation.", getDebugName());
        return true;
    }
    return s.flags & layer_state_t::eLayerHidden;
}
```

隐藏的层不会通过该可见性判断，但层对象仍可能留在层树里。`dumpsys SurfaceFlinger --list` 遍历层树（`SurfaceFlinger.cpp:5188-5191`），因此仅凭名称存在与否不能判断该层是否参与合成；应结合层状态和 trace（见第 8 章）。

### 5.7 client composition cache

**核心结论：** 缓存命中时可复用既有 client composition 结果；即使桌面静止，上层动画改变合成请求也可能让缓存失效，使桌面重新参与 GPU 合成。

```cpp
// frameworks/native/services/surfaceflinger/CompositionEngine/src/Output.cpp:1303-1319（composeSurfaces 节选）
if (mClientCompositionRequestCache) {
    if (mClientCompositionRequestCache->exists(tex->getBuffer()->getId(),
                                               clientCompositionDisplay,
                                               clientCompositionLayers)) {
        ATRACE_NAME("ClientCompositionCacheHit");
        outputCompositionState.reusedClientComposition = true;
        ...
        return base::unique_fd(std::move(fd));
    }
    ATRACE_NAME("ClientCompositionCacheMiss");
    mClientCompositionRequestCache->add(...);
}
```

这项缓存检查发生在生成 client composition 请求之后、调用 RenderEngine 绘制之前：

1. **比较什么**：`exists` 同时接收输出 buffer 的 ID、显示参数和本次图层请求。即便画面看似静止，也需要所选输出 buffer 及请求满足缓存的比较条件。
2. **命中做什么**：标记 `reusedClientComposition`，返回该 buffer 对应的 fence，不再执行本次后续的 client composition 绘制。
3. **未命中做什么**：记录 `ClientCompositionCacheMiss`，把本次请求加入缓存，并继续后续绘制。下一帧能否命中仍要看届时的 buffer 和请求。

窗口动画可能改变矩阵、裁剪或 alpha，从而改变合成请求；静止的桌面内容即使没有更新，也可能需要参与未命中的那次合成。相反，不能仅凭“正在动画”断言所有设备和每一帧都必然未命中；应结合 `ClientCompositionCacheHit` 和 `ClientCompositionCacheMiss` trace 观察。

---

## 6. 转场层：从可见性请求到动画结束

以启动设置为例，系统可能先将 Launcher 标记为期望不可见，再等转场完成后处理实际隐藏。本章按 T0～T3 追踪这段过程；具体时序取决于转场类型和动画状态。

### 6.1 T0：startActivity 那一刻

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java:2036-2040（startActivityUnchecked 节选）
mTargetRootTask.ensureActivitiesVisible(null /* starting */,
        0 /* configChanges */, !PRESERVE_WINDOWS);
...
mTargetRootTask.mDisplayContent.executeAppTransition();
// :2490-2492
if (mNoAnimation) {
    act.mDisplayContent.prepareAppTransition(TRANSIT_NONE);
} else {
    act.mDisplayContent.prepareAppTransition(TRANSIT_TO_FRONT);
```

`prepareAppTransition` 准备 legacy AppTransition。本树同时支持 Shell Transitions，两者的选择见 §6.7。legacy 状态包括 IDLE、READY、RUNNING 和 TIMEOUT，并设有超时处理（`AppTransition.java:226-230、244、348`）。新任务的转场请求在 Task 侧登记（`Task.java:5422-5428`）：

```java
if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
    dc.prepareAppTransition(TRANSIT_NONE);
    mTaskSupervisor.mNoAnimActivities.add(r);
} else {
    dc.prepareAppTransition(TRANSIT_OPEN);
    mTaskSupervisor.mNoAnimActivities.remove(r);
}
```

`prepare` 重置部分状态并通知监听者（`AppTransition.java:441-448`）：

```java
private boolean prepare() {
    if (!isRunning()) {
        setAppTransitionState(APP_STATE_IDLE);
        notifyAppTransitionPendingLocked();
        return true;
    }
    return false;
}
```

```java
// frameworks/base/services/core/java/com/android/server/wm/AppTransition.java:1601-1611
boolean prepareAppTransition(@TransitionType int transit, @TransitionFlags int flags) {
    if (mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
        return false;
    }
    mNextAppTransitionRequests.add(transit);
    ...
    return prepare();
```

启动流程中的可见性计算可能将 Launcher 的 `visibleRequested` 设为 false，同时将相关 Activity 登记到转场集合。转场期间的显示与停止仍由 §2.3、§2.4 中的条件控制。

### 6.2 T1：首帧就绪 → goodToGo

**核心结论：** legacy 转场不一定等应用主窗口首帧显示才开始：起始窗口已显示或转移也可满足 opening Activity 的就绪条件；此外还需检查壁纸、Recents 等条件。

应用画完第一帧，跨进程上报。第一跳 binder 入口（`Session.java:306-311`）：

```java
@Override
public void finishDrawing(IWindow window,
        @Nullable SurfaceControl.Transaction postDrawTransaction, int seqId) {
    if (DEBUG) Slog.v(TAG_WM, "IWindow finishDrawing called for " + window);
    mService.finishDrawingWindow(this, window, postDrawTransaction, seqId);
}
```

第二跳落到 WindowState（`WindowState.java:6400-6403`）再转 `WindowStateAnimator.finishDrawingLocked`，推进绘制状态机（`WindowStateAnimator.java:244-268`）：

```java
boolean finishDrawingLocked(SurfaceControl.Transaction postDrawTransaction,
        boolean forceApplyNow) {
    final boolean startingWindow =
            mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
    ...
    boolean layoutNeeded = false;

    if (mDrawState == DRAW_PENDING) {
        ...
        mDrawState = COMMIT_DRAW_PENDING;
        layoutNeeded = true;
    }

    if (postDrawTransaction != null) {
        // If there is no surface, the last draw was for the previous surface. We don't want to
        // wait until the new surface is shown and instead just apply the transaction right away.
        if (mLastHidden && mDrawState != NO_SURFACE && !forceApplyNow) {
            mPostDrawTransaction.merge(postDrawTransaction);
        } else {
            mWin.getSyncTransaction().merge(postDrawTransaction);
        }
        layoutNeeded = true;
    }
    return layoutNeeded;
}
```

窗口绘制状态依次涉及 `NO_SURFACE`、`DRAW_PENDING`、`COMMIT_DRAW_PENDING`、`READY_TO_SHOW` 和 `HAS_DRAWN`（定义见 `WindowStateAnimator.java:118-146`）。应用报告绘制完成后，以下代码将待提交状态推进到可显示状态（`WindowStateAnimator.java:290-300`）：

```java
if (mDrawState != COMMIT_DRAW_PENDING && mDrawState != READY_TO_SHOW) {
    return false;
}
ProtoLog.i(WM_DEBUG_ANIM, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW %s",
        mSurfaceController);
mDrawState = READY_TO_SHOW;
```

随后每帧放置流程调 `performShowLocked`：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowState.java:4938-4958（节选）
boolean performShowLocked() {
    ...
    final int drawState = mWinAnimator.mDrawState;
    if ((drawState == HAS_DRAWN || drawState == READY_TO_SHOW) && mActivityRecord != null) {
        if (mAttrs.type != TYPE_APPLICATION_STARTING) {
            mActivityRecord.onFirstWindowDrawn(this);
        } else {
            mActivityRecord.onStartingWindowDrawn();
        }
    }

    if (mWinAnimator.mDrawState != READY_TO_SHOW || !isReadyForDisplay()) {
        return false;
    }
```

这段代码说明主窗口显示受绘制状态约束；它不能单独证明某次黑闪的来源。应结合 starting window、letterbox 和其他图层的实际状态排查。

首帧信号汇聚到 `executeAppTransition`（`DisplayContent.java:5764-5772`）——setReady 把状态机从 IDLE 推到 READY：

```java
void executeAppTransition() {
    mTransitionController.setReady(this);
    if (mAppTransition.isTransitionSet()) {
        ProtoLog.w(WM_DEBUG_APP_TRANSITIONS,
                "Execute app transition: %s, displayId: %d Callers=%s",
                mAppTransition, mDisplayId, Debug.getCallers(5));
        mAppTransition.setReady();
        mWmService.mWindowPlacerLocked.requestTraversal();
    }
}
```

READY 之后，每帧表面放置流程都会探一次就绪（`RootWindowContainer.java:1043-1047`）：

```java
// If we are ready to perform an app transition, check through all of the app tokens
// to be shown and see if they are ready to go.
if (curDisplay.mAppTransition.isReady()) {
    // handleAppTransitionReady may modify curDisplay.pendingLayoutChanges.
    curDisplay.mAppTransitionController.handleAppTransitionReady();
```

进入 `handleAppTransitionReady`：

```java
// frameworks/base/services/core/java/com/android/server/wm/AppTransitionController.java:189-307（节选）
void handleAppTransitionReady() {
    mTempTransitionReasons.clear();
    if (!transitionGoodToGo(mDisplayContent.mOpeningApps, mTempTransitionReasons)
            || ...) {
        return;
    }
    ...
    ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "**** GOOD TO GO");
    ...
    // Find the layout params of the top-most application window in the tokens, which is
    // what will control the animation theme. ...
    final ActivityRecord animLpActivity = findAnimLayoutParamsToken(transit, activityTypes,
            mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
            mDisplayContent.mChangingContainers);
    ...
    final WindowManager.LayoutParams animLp = getAnimLp(animLpActivity);
    ...
    applyAnimations(mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps, transit,
            animLp, voiceInteraction);                    // :278
    ...
    layoutRedo = appTransition.goodToGo(transit, topOpeningApp);   // :289 → APP_STATE_RUNNING
```

goodToGo 本体（`AppTransition.java:457-465`）——清请求、置 RUNNING、取顶层打开容器的动画适配器：

```java
int goodToGo(@TransitionOldType int transit, ActivityRecord topOpeningApp) {
    mNextAppTransitionFlags = 0;
    mNextAppTransitionRequests.clear();
    setAppTransitionState(APP_STATE_RUNNING);
    final WindowContainer wc =
            topOpeningApp != null ? topOpeningApp.getAnimatingContainer() : null;
    final AnimationAdapter topOpeningAnim = wc != null ? wc.getAnimation() : null;

    int redoLayout = notifyAppTransitionStartingLocked(
            AppTransition.isKeyguardGoingAwayTransitOld(transit),
            AppTransition.isKeyguardOccludeTransitOld(transit),
            topOpeningAnim != null ? topOpeningAnim.getDurationHint() : 0,
            topOpeningAnim != null
                    ? topOpeningAnim.getStatusBarTransitionsStartTime()
                    : SystemClock.uptimeMillis(),
            AnimationAdapter.STATUS_BAR_TRANSITION_DURATION);

    if (mRemoteAnimationController != null) {
        mRemoteAnimationController.goodToGo(transit);
    } else if (...) {
```

`notifyAppTransitionStartingLocked` 使用顶层打开容器动画的 duration hint；存在远程动画控制器时，还会通知该控制器（见 §6.6）。

就绪门槛就在 `transitionGoodToGo` 的逐 opening app 检查（`AppTransitionController.java:1364-1375`）：

```java
ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
        "Check opening app=%s: allDrawn=%b startingDisplayed=%b "
                + "startingMoved=%b isRelaunching()=%b startingWindow=%s",
        activity, activity.allDrawn, activity.isStartingWindowDisplayed(),
        activity.startingMoved, activity.isRelaunching(),
        activity.mStartingWindow);
final boolean allDrawn = activity.allDrawn && !activity.isRelaunching();
if (!allDrawn && !activity.isStartingWindowDisplayed() && !activity.startingMoved) {
    return false;
}
if (allDrawn) {
    outReasons.put(activity, APP_TRANSITION_WINDOWS_DRAWN);
```

三选一放行：全部窗口画完、或起始窗已展示、或起始窗已转移。`allDrawn` 的聚合在 `ActivityRecord.updateDrawnWindowStates`（`:5231-5235`：`mNumDrawnWindows >= numInteresting` 才置位）。

这三个条件解释了为什么**转场开始不一定等于应用主窗口首帧已经显示**：起始窗口已经展示或转移时，转场也可能继续。相反，仅看到 `allDrawn` 字段为 true 也不能忽略重启判断：这里要求 `allDrawn && !isRelaunching()`。

此外，上面的条件只是逐个 opening Activity 的一部分。完整 `transitionGoodToGo` 还会检查 Recents 动画、转场规格是否仍在获取、未知应用可见性是否已解决，以及可见壁纸是否准备好（`AppTransitionController.java:1350-1400`）。排查等待时应先判断卡在哪项，不能把所有延迟都归因于应用首帧。

### 6.3 T2：动画从谁的主题来

**核心结论：** 在所示 legacy 路径中，opening 和 closing 共用选出的 `animLp`；因此新页面的主题可能决定旧页面的 exit 动画，不能只检查旧页面自己的主题。

`findAnimLayoutParamsToken` 按优先级选择提供动画参数的容器：先检查远程动画定义，再检查充满父容器的窗口，最后查找其他有主窗口的容器（`AppTransitionController.java:832-851`）：

```java
private ActivityRecord findAnimLayoutParamsToken(@TransitionOldType int transit,
        ArraySet<Integer> activityTypes, ArraySet<ActivityRecord> openingApps,
        ArraySet<ActivityRecord> closingApps, ArraySet<WindowContainer> changingApps) {
    ActivityRecord result;

    // Remote animations always win, but fullscreen tokens override non-fullscreen tokens.
    result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
            w -> w.getRemoteAnimationDefinition() != null
                    && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
    if (result != null) {
        return result;
    }
    result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
            w -> w.fillsParent() && w.findMainWindow() != null);
    if (result != null) {
        return result;
    }
    return lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
            w -> w.findMainWindow() != null);
}
```

在所分析的设置启动场景中，若选中打开中的 Setting，`getAnimLp` 便取其主窗口属性（`:546-549`）：

```java
private static WindowManager.LayoutParams getAnimLp(ActivityRecord activity) {
    final WindowState mainWindow = activity != null ? activity.findMainWindow() : null;
    return mainWindow != null ? mainWindow.mAttrs : null;
}
```

`applyAnimations` 先收集动画目标再逐容器施加（`:930-949` 与 `:1154-1161`）——**opening 与 closing 共用同一个 animLp**：

```java
private void applyAnimations(ArraySet<WindowContainer> wcs, ArraySet<ActivityRecord> apps,
        @TransitionOldType int transit, boolean visible, LayoutParams animLp,
        boolean voiceInteraction) {
    final int wcsCount = wcs.size();
    for (int i = 0; i < wcsCount; i++) {
        final WindowContainer wc = wcs.valueAt(i);
        ...
        wc.applyAnimation(animLp, transit, visible, voiceInteraction, transitioningDescendants);
    }
}
...
final ArraySet<WindowContainer> openingWcs = getAnimationTargets(
        openingApps, closingApps, true /* visible */);
final ArraySet<WindowContainer> closingWcs = getAnimationTargets(
        openingApps, closingApps, false /* visible */);
applyAnimations(openingWcs, openingApps, transit, true /* visible */, animLp, voiceInteraction);
applyAnimations(closingWcs, closingApps, transit, false /* visible */, animLp, voiceInteraction);
...
for (int i = 0; i < openingApps.size(); ++i) {
    openingApps.valueAtUnchecked(i).mOverrideTaskTransition = false;
}
```

因此，在这个选中 Setting 主窗口参数的路径中，Launcher 的退场动画也从同一组动画参数解析。具体归属仍取决于实际选中的容器和转场类型。

`loadAnimation` 的属性映射：

```java
// frameworks/base/services/core/java/com/android/server/wm/AppTransition.java:883-893（节选）
Animation loadAnimation(LayoutParams lp, int transit, boolean enter, ...) {
    final boolean canCustomizeAppTransition = container.canCustomizeAppTransition();

    if (mNextAppTransitionOverrideRequested) {
        if (canCustomizeAppTransition || mOverrideTaskTransition) {
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_CUSTOM;
        } else {
            ProtoLog.e(WM_DEBUG_APP_TRANSITIONS_ANIM, "applyAnimation: "
                    + " override requested, but it is prohibited by policy.");
        }
    }
// :655-662（mapOpenCloseTransitTypes）
case TRANSIT_OLD_ACTIVITY_OPEN:
case TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN:
    animAttr = enter
            ? WindowAnimation_activityOpenEnterAnimation
            : WindowAnimation_activityOpenExitAnimation;
// :1008-1011
int animAttr = mapOpenCloseTransitTypes(transit, enter);
a = animAttr == 0 ? null : (canCustomizeAppTransition
    ? loadAnimationAttr(lp, animAttr, transit)
    : mTransitionAnimation.loadDefaultAnimationAttr(animAttr, transit));
```

自定义动画是否用于任务级转场还受策略限制：代码检查 `canCustomizeAppTransition` 或 `overrideTaskTransition`。后者是 AOSP 定义的选项，设置时还需满足相应权限检查（`ActivityOptions.java:352-353`、`SafeActivityOptions.java:345-352`）。

属性解析落到主题缓存：

```java
// frameworks/base/core/java/com/android/internal/policy/TransitionAnimation.java:272-288（节选）
public Animation loadAnimationAttr(LayoutParams lp, int animAttr, int transit) {
    int resId = Resources.ID_NULL;
    Context context = mContext;
    if (animAttr >= 0) {
        AttributeCache.Entry ent = getCachedAnimations(lp);
        if (ent != null) {
            context = ent.context;
            resId = ent.array.getResourceId(animAttr, 0);
        }
    }
    resId = updateToTranslucentAnimIfNeeded(resId, transit);
    if (ResourceId.isValid(resId)) {
        return loadAnimationSafely(context, resId, mTag);
    }
    return null;
}
```

默认样式链：`themes.xml:185 windowAnimationStyle=@style/Animation.Activity` → `styles.xml:83-87`。属性到资源的解析走 AttributeCache（`TransitionAnimation.java:357-379`），键是"包名 + lp.windowAnimations"：

```java
private AttributeCache.Entry getCachedAnimations(LayoutParams lp) {
    ...
    if (lp != null && lp.windowAnimations != 0) {
        // If this is a system resource, don't try to load it from the
        // application resources.  It is nice to avoid loading application
        // resources if we can.
        String packageName = lp.packageName != null ? lp.packageName : DEFAULT_PACKAGE;
        int resId = getAnimationStyleResId(lp);
        if ((resId & 0xFF000000) == 0x01000000) {
            packageName = DEFAULT_PACKAGE;
        }
        ...
        return AttributeCache.instance().get(packageName, resId,
                com.android.internal.R.styleable.WindowAnimation);
    }
    return null;
}
```

当选中的 `animLp` 来自 Setting 主窗口时，动画资源按该参数解析。以下是 AOSP 默认动画（省略版权头）：

```xml
<!-- frameworks/base/core/res/res/anim/activity_open_enter.xml -->
<alpha android:fromAlpha="0" android:toAlpha="1.0"
    android:interpolator="@interpolator/linear"
    android:startOffset="50" android:duration="83" />
<translate android:fromXDelta="10%" android:toXDelta="0"
    android:interpolator="@interpolator/fast_out_extra_slow_in"
    android:duration="450" />

<!-- frameworks/base/core/res/res/anim/activity_open_exit.xml -->
<alpha android:fromAlpha="1.0" android:toAlpha="1.0"
    android:interpolator="@interpolator/standard_accelerate"
    android:duration="450" />
<translate android:fromXDelta="0" android:toXDelta="-10%"
    android:interpolator="@interpolator/fast_out_extra_slow_in"
    android:duration="450" />
```

默认 exit 动画的 alpha 为 1→1，并包含位移。动画期间窗口能否延迟隐藏还取决于前述转场状态；工程中也可使用 alpha 1→1 的动画使退出方保持动画状态。本文将后一种用法称为“延迟隐藏动画”。

需要区分动画的视觉效果与它对状态的影响：

1. **默认 exit**：450 ms 内 alpha 保持 1，同时沿 X 轴从 0 移到 -10%。它不是纯粹的“透明度不变动画”，位移可能改变下方区域是否露出。
2. **单独配置 alpha 1→1**：若该动画被当前转场选中并实际启动，它没有透明度变化，但可在其持续期间维持相关动画状态；延迟隐藏是否发生还要检查 §2.3 和 §2.4 的条件。
3. **没有可用动画**：`loadAnimation` 返回 null 时，不会生成对应 adapter（见下节）。这会改变动画状态和隐藏时机，但最终露出的是桌面、任务背景还是黑色，仍取决于当时参与合成的层。

因此，验证方案时既要看动画资源和选中结果，也要看退出方何时隐藏及进入方实际覆盖区域；只比较 XML 的时长不够。

### 6.4 leash：通过代理层执行动画

**核心结论：** 窗口 Surface 被挂在 leash 之下，动画通过事务变换 leash；应用内容包括自绘状态栏背景图像会随之变换，而窗口布局尺寸不必逐帧改变。

**leash** 是动画使用的代理 Surface。动画开始时，系统将目标 Surface 重新挂到 leash 下；动画可对 leash 应用位置、矩阵和透明度，结束后再恢复层级。这样，合成变换可以独立于窗口布局进行。

拿到 Animation 后，`WindowContainer.getAnimationAdapter` 包装：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowContainer.java:3421-3446（节选）
final Animation a = loadAnimation(lp, transit, enter, isVoiceInteraction);

if (a != null) {
    ...
    AnimationAdapter adapter = new LocalAnimationAdapter(
            new WindowAnimationSpec(a, mTmpPoint, mTmpRect, ...),
            getSurfaceAnimationRunner());
    resultAdapters = new Pair<>(adapter, null);
    ...
    mTransit = transit;
} else {
    resultAdapters = new Pair<>(null, null);
}
```

adapter 非 null 才 `startAnimation`（`WindowContainer.java:3529-3531`）：

```java
animationRunnerBuilder.build()
        .startAnimation(getPendingTransaction(), adapter, !isVisible(),
                ANIMATION_TYPE_APP_TRANSITION, thumbnailAdapter);
```

`SurfaceAnimator.startAnimation`（`SurfaceAnimator.java:190-215`）先取消旧动画、再建 leash、最后挂真实动画——注意 `mAnimationStartDelayed` 分支（转场收集期的协同延迟）：

```java
void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
        @AnimationType int type, ...) {
    cancelAnimation(t, true /* restarting */, true /* forwardCancel */);
    mAnimation = anim;
    mAnimationType = type;
    mSurfaceAnimationFinishedCallback = animationFinishedCallback;
    mAnimationCancelledCallback = animationCancelledCallback;
    final SurfaceControl surface = mAnimatable.getSurfaceControl();
    if (surface == null) {
        Slog.w(TAG, "Unable to start animation, surface is null or no children.");
        cancelAnimation();
        return;
    }
    mLeash = freezer != null ? freezer.takeLeashForAnimation() : null;
    if (mLeash == null) {
        mLeash = createAnimationLeash(mAnimatable, surface, t, type,
                mAnimatable.getSurfaceWidth(), mAnimatable.getSurfaceHeight(), 0 /* x */,
                0 /* y */, hidden, mService.mTransactionFactory);
        mAnimatable.onAnimationLeashCreated(t, mLeash);
    }
    mAnimatable.onLeashAnimationStarting(t, mLeash);
    if (mAnimationStartDelayed) {
        ProtoLog.i(WM_DEBUG_ANIM, "Animation start delayed for %s", mAnimatable);
        return;
    }
    mAnimation.startAnimation(mLeash, t, type, mInnerAnimationFinishedCallback);
```

创建 leash 并调整层级（`SurfaceAnimator.java:508-533`）：

```java
// frameworks/base/services/core/java/com/android/server/wm/SurfaceAnimator.java:508-533（createAnimationLeash 节选）
final SurfaceControl.Builder builder = animatable.makeAnimationLeash()
        .setParent(animatable.getAnimationLeashParent())
        .setName(surface + " - animation-leash of " + animationTypeToString(type))
        ...
        .setHidden(hidden)
        .setEffectLayer()
        ...
final SurfaceControl leash = builder.build();
t.setWindowCrop(leash, width, height);
t.setPosition(leash, x, y);
t.show(leash);
t.setAlpha(leash, hidden ? 0 : 1);

t.reparent(surface, leash);
return leash;
```

窗口 surface 被 reparent 到一个新建的 leash 层之下；动画每帧只写 leash：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowAnimationSpec.java:133-155（apply 节选）
t.setMatrix(leash, tmp.transformation.getMatrix(), tmp.floats);
t.setAlpha(leash, tmp.transformation.getAlpha());
...
t.setWindowCrop(leash, clipRect);
```

可以把这条链拆为三步：

- **建立层级**：`createAnimationLeash` 创建代理层、设置初始裁剪和位置，再用 `reparent(surface, leash)` 把原 Surface 挂到代理层下。
- **逐帧变换**：`WindowAnimationSpec.apply` 将动画计算出的矩阵、alpha 和裁剪写入事务。子 Surface 继承父层变换，因此整个窗口随 leash 移动或淡入淡出。
- **结束恢复**：动画结束或取消时由 `SurfaceAnimator` 清理 leash、恢复层级（见 §6.5）。这与 View 内部动画修改内容的方式不同。

由此可推导：将状态栏背景图像画在应用窗口内容中时，若该窗口所在的 leash 被平移，背景图像也会随窗口一起移动；若背景图像在独立系统栏窗口中，则还需检查系统栏自身及其父层是否参与了该次转场。

`WindowAnimator` 通过 Choreographer 安排动画更新（`WindowAnimator.java:108-117`）：

```java
mAnimationFrameCallback = frameTimeNs -> {
    synchronized (mService.mGlobalLock) {
        mAnimationFrameCallbackScheduled = false;
        final long vsyncId = mChoreographer.getVsyncId();
        animate(frameTimeNs, vsyncId);
        if (mNotifyWhenNoAnimation && !mLastRootAnimating) {
            mService.mGlobalLock.notifyAll();
        }
    }
};
```

每次更新安排后续帧，并使用 Surface 事务提交动画变化（`WindowAnimator.java:140-157`）：

```java
private void animate(long frameTimeNs, long vsyncId) {
    if (!mInitialized) {
        return;
    }

    // Schedule next frame already such that back-pressure happens continuously.
    scheduleAnimation();

    final RootWindowContainer root = mService.mRoot;
    mCurrentTime = frameTimeNs / TimeUtils.NANOS_PER_MS;
    mBulkUpdateParams = 0;
    root.mOrientationChangeComplete = true;
    ...
    ProtoLog.i(WM_SHOW_TRANSACTIONS, ">>> OPEN TRANSACTION animate");
    mService.openSurfaceTransaction();
```

事务里每个在播动画走 `SurfaceAnimationRunner.applyTransformation`（`SurfaceAnimationRunner.java:349-351`）——这就是 WindowAnimationSpec.apply 的调用点：

```java
private void applyTransformation(RunningAnimation a, Transaction t, long currentPlayTime) {
    a.mAnimSpec.apply(t, a.mLeash, currentPlayTime);
}
```

这条 leash 动画路径直接更新合成层，不要求每次变换都重新布局或重绘应用。WMS 与 SurfaceFlinger 的遮挡判断使用不同的数据：前者在相关判断中读取窗口状态和 frame（`WindowState.java:2288-2296、2486-2500`）：

```java
private boolean isOpaqueDrawn() {
    // When there is keyguard, wallpaper could be placed over the secure app
    // window but invisible. We need to check wallpaper visibility explicitly
    // to determine if it's occluding apps.
    final boolean isWallpaper = mToken.asWallpaperToken() != null;
    return ((!isWallpaper && mAttrs.format == PixelFormat.OPAQUE)
            || (isWallpaper && mToken.isVisible()))
            && isDrawn() && !isAnimating(TRANSITION | PARENTS);
}

boolean isObscuringDisplay() {
    Task task = getTask();
    if (task != null && task.getRootTask() != null && !task.getRootTask().fillsParent()) {
        return false;
    }
    return isOpaqueDrawn() && fillsDisplay();
}

boolean fillsDisplay() {
    final DisplayInfo displayInfo = getDisplayInfo();
    return mWindowFrames.mFrame.left <= 0 && mWindowFrames.mFrame.top <= 0
            && mWindowFrames.mFrame.right >= displayInfo.appWidth
            && mWindowFrames.mFrame.bottom >= displayInfo.appHeight;
}
```

`DisplayContent` 使用上述窗口判断处理遮挡等状态（`DisplayContent.java:955-972`）；`isOpaqueDrawn` 也会检查动画状态。

**SF 的逐帧合成用变换后 bounds**（`Layer.cpp:359-402 computeBounds`，leash matrix 经 getActiveTransform 卷入）：

```cpp
void Layer::computeBounds(FloatRect parentBounds, ui::Transform parentTransform,
                          float parentShadowRadius) {
    const State& s(getDrawingState());

    // Calculate effective layer transform
    mEffectiveTransform = parentTransform * getActiveTransform(s);
    ...
    // Transform parent bounds to layer space
    parentBounds = getActiveTransform(s).inverse().transform(parentBounds);

    // Calculate source bounds
    mSourceBounds = computeSourceBounds(parentBounds);

    // Calculate bounds by croping diplay frame with layer crop and parent bounds
    FloatRect bounds = mSourceBounds;
    const Rect layerCrop = getCrop(s);
    if (!layerCrop.isEmpty()) {
        bounds = mSourceBounds.intersect(layerCrop.toFloatRect());
    }
    bounds = bounds.intersect(parentBounds);

    mBounds = bounds;
    mScreenBounds = mEffectiveTransform.transform(mBounds);
    ...
    for (const sp<Layer>& child : mDrawingChildren) {
        child->computeBounds(mBounds, mEffectiveTransform, childShadowRadius);
    }
```

SurfaceFlinger 使用变换后的几何计算可见区域和裁剪（`Output.cpp:585、652`）。滑入途中，下层未被覆盖的区域可能仍需参与合成。

转场动画还可能设置任务背景色。以下代码在装配动画时处理该颜色：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowContainer.java:3500-3526（节选）
final @ColorInt int backgroundColorForTransition;
if (adapter.getBackgroundColor() != 0) {
    // ... originates from a call to overridePendingAppTransition.
    backgroundColorForTransition = adapter.getBackgroundColor();
} else {
    ...
    backgroundColorForTransition = ColorUtils.setAlphaComponent(
            parentTask.getTaskDescription().getBackgroundColor(), 255);
}
animationRunnerBuilder.setTaskBackgroundColor(backgroundColorForTransition);
```

```java
// frameworks/base/services/core/java/com/android/server/wm/TaskDisplayArea.java:853-873（节选）
void setBackgroundColor(@ColorInt int colorInt, boolean restore) {
    mBackgroundColor = colorInt;
    Color color = Color.valueOf(colorInt);
    ...
    if (mSurfaceControl != null) {
        getPendingTransaction()
                .setColor(mSurfaceControl,
                        new float[]{color.red(), color.green(), color.blue()});
        scheduleAnimation();
    }
}
```

TaskDescription 可从 Activity 主题的 `android:colorBackground` 取得背景色（`Activity.java:5702-5706`），并由 Task 向上提供（`Task.java:1996-1997`）；动画结束后相应背景可被清除（`TaskDisplayArea.java:876-885`）。它是排查转场露底的一个候选来源，设备实际色值和画面仍需验证。

### 6.5 T3：动画结束后的状态更新

**核心结论：** 退出动画结束会推进 leash 清理、窗口退出处理和待停止队列，但这些操作有各自条件；不能仅根据进入动画结束断定旧窗口已隐藏或 `onStop` 已执行。

动画播完，finish 回调从 SurfaceAnimationRunner 一路传回。SurfaceAnimator 的内层回调先销毁 leash 再分发（`SurfaceAnimator.java:126-146`）：

```java
final Runnable resetAndInvokeFinish = () -> {
    // We need to check again if the animation has been replaced with a new
    // animation because the animatable may defer to finish.
    if (anim != mAnimation) {
        return;
    }
    final OnAnimationFinishedCallback animationFinishCallback =
            mSurfaceAnimationFinishedCallback;
    reset(mAnimatable.getSyncTransaction(), true /* destroyLeash */);
    if (staticAnimationFinishedCallback != null) {
        staticAnimationFinishedCallback.onAnimationFinished(type, anim);
    }
    if (animationFinishCallback != null) {
        animationFinishCallback.onAnimationFinished(type, anim);
    }
};
```

窗口侧的中转（`WindowState.java:5636-5638` → `WindowStateAnimator.java:193-212`）——注释点明了 mAnimatingExit 的解除时机：

```java
protected void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
    super.onAnimationFinished(type, anim);
    mWinAnimator.onAnimationFinished();
}
// WindowStateAnimator:
void onAnimationFinished() {
    // Done animating, clean up.
    ...
    mWin.checkPolicyVisibilityChange();
    final DisplayContent displayContent = mWin.getDisplayContent();
    if ((mAttrType == LayoutParams.TYPE_STATUS_BAR
            || mAttrType == LayoutParams.TYPE_NOTIFICATION_SHADE) && mWin.isVisibleByPolicy()) {
        // Upon completion of a not-visible to visible status bar animation a relayout is
        // required.
        displayContent.setLayoutNeeded();
    }
    mWin.onExitAnimationDone();
    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
```

终点 `onExitAnimationDone`：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowState.java:5348-5418（节选）
void onExitAnimationDone() {
    ...
    if (isAnimating()) {
        return;
    }
    if (!isSelfOrAncestorWindowAnimatingExit()) {
        return;
    }
    ...
    mDestroying = true;
    final boolean hasSurface = mWinAnimator.hasSurface();

    // Use pendingTransaction here so hide is done the same transaction as the other
    // animations when exiting
    mWinAnimator.hide(getPendingTransaction(), "onExitAnimationDone");

    if (mActivityRecord != null) {
        if (mAttrs.type == TYPE_BASE_APPLICATION) {
            mActivityRecord.destroySurfaces();
        } ...
    }
    mAnimatingExit = false;
    ProtoLog.d(WM_DEBUG_ANIM, "Clear animatingExit: reason=exitAnimationDone win=%s", this);
    getDisplayContent().mWallpaperController.hideWallpapers(this);
}
```

容器级 `ActivityRecord.onAnimationFinished` 也处理动画结束后的状态（`ActivityRecord.java:7983-8033`）：

```java
protected void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
    super.onAnimationFinished(type, anim);

    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AR#onAnimationFinished");
    mTransit = TRANSIT_OLD_UNSET;
    mTransitFlags = 0;

    setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM | FINISH_LAYOUT_REDO_WALLPAPER,
            "ActivityRecord");

    clearThumbnail();
    setClientVisible(isVisible() || mVisibleRequested);
    ...
    // WindowState.onExitAnimationDone might modify the children list, so make a copy and then
    // traverse the copy.
    final ArrayList<WindowState> children = new ArrayList<>(mChildren);
    children.forEach(WindowState::onExitAnimationDone);
    ...
    getDisplayContent().mAppTransition.notifyAppTransitionFinishedLocked(token);
    scheduleAnimation();

    // Schedule to handle the stopping and finishing activities which the animation is done
    // because the activities which were animating have not been stopped yet.
    mTaskSupervisor.scheduleProcessStoppingAndFinishingActivitiesIfNeeded();
    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
}
```

回调会更新动画状态，并在相应条件下推进待停止 Activity 的处理。进入动画完成通知还会经过 `notifyAppTransitionFinishedLocked`（`AppTransition.java:587-591`）：

```java
public void notifyAppTransitionFinishedLocked(IBinder token) {
    for (int i = 0; i < mListeners.size(); i++) {
        mListeners.get(i).onAppTransitionFinishedLocked(token);
    }
}
```

监听者是 WMS 自己注册的回调（`WindowManagerService.java:1163-1186`）：

```java
public void onAppTransitionFinishedLocked(IBinder token) {
    final ActivityRecord atoken = mRoot.getActivityRecord(token);
    if (atoken == null) {
        return;
    }
    ...
    } else {
        atoken.updateReportedVisibilityLocked();
        // We should also defer sending the finished callback until the recents animation
        // successfully finishes.
        if (atoken.mEnteringAnimation && !isRecentsAnimationTarget(atoken)) {
            atoken.mEnteringAnimation = false;
            if (atoken.attachedToProcess()) {
                try {
                    atoken.app.getThread().scheduleEnterAnimationComplete(atoken.token);
                } catch (RemoteException e) {
                }
            }
```

应用进程侧两跳收尾（`ActivityThread.java:4462-4467` → `Activity.java:8441-8449`）：

```java
private void handleEnterAnimationComplete(IBinder token) {
    ActivityClientRecord r = mActivities.get(token);
    if (r != null) {
        r.activity.dispatchEnterAnimationComplete();
    }
}
// Activity:
public void dispatchEnterAnimationComplete() {
    mEnterAnimationComplete = true;
    mInstrumentation.onEnterAnimationComplete();
    onEnterAnimationComplete();
    if (getWindow() != null && getWindow().getDecorView() != null) {
        View decorView = getWindow().getDecorView();
        decorView.getViewTreeObserver().dispatchOnEnterAnimationComplete();
    }
}
```

`mEnteringAnimation` 在相关进入转场中置位（`ActivityRecord.java:5587`），用于决定是否发送进入动画完成回调。回调的适用范围应结合实际转场路径判断。

在所分析的 legacy 路径中，可见性请求可能先改变，窗口随后等待就绪并播放动画，结束后再推进隐藏和停止。若退出动画缺失，原有延迟条件可能不再成立；是否露出其他颜色取决于当时仍可见的图层和合成路径。系统还提供异常动画状态的清理逻辑（`WindowState.java:5335-5345`）：

```java
void cleanupAnimatingExitWindow() {
    // TODO(b/205335975): WindowManagerService#tryStartExitingAnimation starts an exit animation
    // and set #mAnimationExit. After the exit animation finishes, #onExitAnimationDone shall
    // be called, but there seems to be a case that #onExitAnimationDone is not triggered, so
    // a windows stuck in the animatingExit status.
    if (mAnimatingExit && shouldFinishAnimatingExit()) {
        ProtoLog.w(WM_DEBUG_APP_TRANSITIONS, "Clear window stuck on animatingExit status: %s",
                this);
        onExitAnimationDone();
    }
}
```

注释说明了退出动画回调未触发时可能留下的状态，清理逻辑用于处理这类情况。

进入和退出动画可配置不同的时长与变换，因此不能仅凭一侧动画结束推断另一侧已经覆盖或隐藏。

### 6.6 窗口转场与内容转场

**Surface 级动画**通过 leash 和 Surface 事务变换窗口或任务图层。远程动画则由其他进程中的 `IRemoteAnimationRunner` 接收参与转场的目标并驱动动画；以下是控制器的就绪入口（`RemoteAnimationController.java:165-213`）：

```java
void goodToGo(@WindowManager.TransitionOldType int transit) {
    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "goodToGo()");
    if (mCanceled) { ... return; }

    // Scale the timeout with the animator scale the controlling app is using.
    mHandler.postDelayed(mTimeoutRunnable,
            (long) (TIMEOUT_MS * mService.getCurrentAnimatorScale()));
    mFinishedCallback = new FinishedCallback(this);

    // Create the app targets
    final RemoteAnimationTarget[] appTargets = createAppAnimations();
    ...
    // Create the remote wallpaper animation targets (if any)
    final RemoteAnimationTarget[] wallpaperTargets = createWallpaperAnimations();

    // Create the remote non app animation targets (if any)
    final RemoteAnimationTarget[] nonAppTargets = createNonAppWindowAnimations(transit);

    mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
        try {
            linkToDeathOfRunner();
            ...
            mRemoteAnimationAdapter.getRunner().onAnimationStart(transit, appTargets,
                    wallpaperTargets, nonAppTargets, mFinishedCallback);
```

IME 弹出的动画同样是 leash 位移（`InsetsSourceProvider.java:90-97`）：

```java
private final Consumer<Transaction> mSetLeashPositionConsumer = t -> {
    if (mControl != null) {
        final SurfaceControl leash = mControl.getLeash();
        if (leash != null) {
            final Point position = mControl.getSurfacePosition();
            t.setPosition(leash, position.x, position.y);
        }
    }
};
```

**内容级动画**由应用侧的 View、RenderNode 或 transition 处理。共享元素转场的发起入口如下（`ActivityOptions.java:980-989`）：

```java
public static ActivityOptions makeSceneTransitionAnimation(Activity activity,
        Pair<View, String>... sharedElements) {
    ActivityOptions opts = new ActivityOptions();
    ExitTransitionCoordinator exit = makeSceneTransitionAnimation(
            new ActivityExitTransitionCallbacks(activity), activity.mExitTransitionListener,
            activity.getWindow(), opts, sharedElements);
    opts.mExitCoordinatorIndex =
            activity.mActivityTransitionState.addExitTransitionCoordinator(exit);
    return opts;
}
```

进入端的启动点（`ActivityTransitionState.java:203-210`）：

```java
public void enterReady(Activity activity) {
    if (mEnterActivityOptions == null || mIsEnterTriggered) {
        return;
    }
    mIsEnterTriggered = true;
    mHasExited = false;
    ArrayList<String> sharedElementNames = mEnterActivityOptions.getSharedElementNames();
    ResultReceiver resultReceiver = mEnterActivityOptions.getResultReceiver();
```

共享元素转场在客户端执行（`ActivityRecord.java:5061-5064`）；`ViewPropertyAnimator` 也可操作 View 或 RenderNode 属性。内容移动后露出的画面取决于窗口背景和下层内容，不能直接断定为黑色。

`makeCustomAnimation` 虽由应用发起，在所示 legacy 路径中仍由 WMS 加载并交给窗口动画机制。首先，ActivityRecord 接收选项（`ActivityRecord.java:5081-5090`）：

```java
switch (animationType) {
    case ANIM_CUSTOM:
        displayContent.mAppTransition.overridePendingAppTransition(
                pendingOptions.getPackageName(),
                pendingOptions.getCustomEnterResId(),
                pendingOptions.getCustomExitResId(),
                pendingOptions.getCustomBackgroundColor(),
                pendingOptions.getAnimationStartedListener(),
                pendingOptions.getAnimationFinishedListener(),
                pendingOptions.getOverrideTaskTransition());
```

第二跳，AppTransition 记下资源 id（`AppTransition.java:1053-1068`）：

```java
void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
        @ColorInt int backgroundColor, IRemoteCallback startedCallback,
        IRemoteCallback endedCallback, boolean overrideTaskTransaction) {
    if (canOverridePendingAppTransition()) {
        clear();
        mNextAppTransitionOverrideRequested = true;
        mNextAppTransitionPackage = packageName;
        mNextAppTransitionEnter = enterAnim;
        mNextAppTransitionExit = exitAnim;
        mNextAppTransitionBackgroundColor = backgroundColor;
        postAnimationCallback();
        mNextAppTransitionCallback = startedCallback;
        mAnimationFinishedCallback = endedCallback;
        mOverrideTaskTransition = overrideTaskTransaction;
    }
}
```

第三跳，loadAnimation 的 CUSTOM 分支在 **WMS 进程内**加载应用 APK 里的 anim 资源（`AppTransition.java:931-932` → `:357-365`）：

```java
} else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM) {
    a = getNextAppRequestedAnimation(enter);
// getNextAppRequestedAnimation:
Animation getNextAppRequestedAnimation(boolean enter) {
    final Animation a = mTransitionAnimation.loadAppTransitionAnimation(
            mNextAppTransitionPackage,
            enter ? mNextAppTransitionEnter : mNextAppTransitionExit);
    if (mNextAppTransitionBackgroundColor != 0 && a != null) {
        a.setBackdropColor(mNextAppTransitionBackgroundColor);
    }
    return a;
}
```

最后，`WindowContainer` 将动画包装为 `LocalAnimationAdapter` 和 `WindowAnimationSpec`。该自定义动画能否应用，还受 §6.3 所述任务级策略约束。

另注意第二跳里的 `setBackdropColor`：makeCustomAnimation 的 backgroundColor 参数从那里进入 `adapter.getBackgroundColor()`，最终刷上 TaskDisplayArea 表面——这是 6.4 末尾"转场底色"链路的第一个入口。

### 6.7 双状态机：legacy 与 Shell Transitions

**核心结论：** 本树配置默认关闭 Shell Transitions，legacy 是默认路径；设备是否实际走该路径必须结合属性与 TransitionPlayer 注册状态确认。

A13 两套并存。server 端权威判定：

```java
// frameworks/base/services/core/java/com/android/server/wm/TransitionController.java:248-250
boolean isShellTransitionsEnabled() {
    return mTransitionPlayer != null;
}
```

player 由 WMShell 注册（`WindowOrganizerController.java:1755-1765`），其启用受以下配置控制：

```java
// frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/transition/Transitions.java:91-92
public static final boolean ENABLE_SHELL_TRANSITIONS =
        SystemProperties.getBoolean("persist.wm.debug.shell_transit", false);
```

**本树默认值为 false，默认走 legacy AppTransition**。设备是否沿用该配置还需检查属性及实际注册状态；本章的 legacy 链路以此为前提。Shell 路径的状态机在 `Transition.java:131-146`，注释即文档：

```java
/** The transition has been created but isn't collecting yet. */
private static final int STATE_PENDING = -1;

/** The transition has been created and is collecting, but hasn't formally started. */
private static final int STATE_COLLECTING = 0;

/**
 * The transition has formally started. It is still collecting but will stop once all
 * participants are ready to animate (finished drawing).
 */
private static final int STATE_STARTED = 1;

/**
 * This transition is currently playing its animation and can no longer collect or be changed.
 */
private static final int STATE_PLAYING = 2;
```

Shell Transitions 也会等待参与者准备就绪，再由相应 handler 执行动画。它与 legacy 的实现和细节不同；排查前应先确认设备启用的路径。

---

## 7. 成本层：内存与带宽的算术

### 7.1 图形缓冲区：图像来源与内存

**设置窗口的 buffer 保存设置窗口画出的像素，不包含其他窗口随后合成进来的内容。** 它的尺寸通常由窗口 Surface 决定，而不是由 Activity 对象决定。SurfaceFlinger 用图层（Layer）管理内容的位置、透明度和层级；图层与存放像素的 buffer 是不同对象。WMS 通过 `SurfaceControl` 事务调整相关状态，WMS 的 `WindowState` 也不是 SurfaceFlinger 的 Layer（`WindowState.java:288`、`Layer.h:110`）。

以 1280 × 720 的设置窗口为例：无缩放或额外边距时，其 buffer 通常也是 1280 × 720，包含顶部状态栏所在的 64 px，但其中未必画了 Home 或 SystemUI 的内容（尺寸例外见 §3.3）。若该区域透明且 Home 图层可见，SurfaceFlinger 合成后可看到 Home 背景和 SystemUI 图标；**Home 像素仍来自 Home 图层，不在设置窗口的 buffer 中**。Home 隐藏后，它就不能再为该区域提供画面。

设置窗口内由 DecorView 绘制的状态栏背景色块属于设置窗口；SystemUI 绘制的时钟和图标属于独立窗口（§4.2）。GPU 合成结果还可能写入单独的输出 buffer，因此窗口 buffer 不是最终屏幕画面。

**一个窗口为什么需要多张 buffer？** 图形系统使用上一帧的 buffer 时，应用可以在另一张上绘制下一帧；用完的 buffer 随后归还复用。实际数量不固定，队列允许的数量也不等于已经分配的数量。Activity 可有其他窗口，`SurfaceView` 也可有独立队列，不能按 Activity 数量推算 buffer 数量（`SurfaceView.java:1312-1363`）。

本树的 BLAST 路径中，渲染端提交 buffer，`BLASTBufferQueue` 取得后通过 Surface 事务交给 SurfaceFlinger；fence 用于协调绘制完成与 buffer 复用（`BufferQueueProducer.cpp:410、847`、`BLASTBufferQueue.cpp:438-554、609-613`）。**提交不等于已经显示。** buffer 是存放图像的内存对象，不等同于操作系统管理内存所用的一个内存页。

**内存估算：**1280 × 720 的 RGBA_8888 图像按每像素 4 字节计算，不计行对齐时：

| buffer 数量 | 理论像素数据量 |
|---|---:|
| 一张 | 3,686,400 B，约 3.69 MB |
| 两张 | 约 7.37 MB |
| 三张 | 约 11.06 MB |

实际占用还受行对齐、分配方式和 buffer 数量影响；三张只是示例，不是每个窗口的固定驻留量（`GraphicBufferAllocator.cpp:126-167`）。

队列上限由多项条件共同决定：BLAST 的最大 dequeue 数初始为 2，生产者可覆盖；BufferQueue 还会考虑最大 acquired 数和运行模式（`BLASTBufferQueue.cpp:177-196`、`BufferQueueCore.cpp:242-247`）：

```cpp
mProducer->setMaxDequeuedBufferCount(2);
...
int maxCount = mMaxAcquiredBufferCount + mMaxDequeuedBufferCount +
        ((asyncMode || dequeueBufferCannotBlock) ? 1 : 0);
maxCount = std::min(maxBufferCount, maxCount);
```

### 7.2 每帧额外开销

**核心结论：** 半透明方案的潜在代价是让原本可隐藏的下层继续绘制或合成；3.7 MB 只是一张全屏 RGBA buffer 的未压缩数据量，不能直接当作每帧实测带宽。

半透明窗口可能使下层继续参与绘制或合成。若下层的全屏 RGBA_8888 buffer 由 GPU 完整采样，单次读取的未压缩数据量可按 1280 × 720 × 4 估算为约 3.7 MB；缓存、可见区域、硬件合成与内存压缩都会改变实际流量。动画是否使 client composition cache 失效，也应结合具体层状态与 trace 判断。

下表只描述全屏、无其他覆盖层且转场结束后的典型情况，不替代实测：

| windowIsTranslucent | windowBackground | 可能的窗口状态与画面 |
|---|---|---|
| true | 透明 | 下层可能保持可见，透明区域可显示下层内容 |
| false | 透明 | 下层可能被隐藏；透明区域的颜色取决于剩余图层和合成路径 |
| true | 不透明 | 下层可能保持可见，但被上层背景遮住 |
| false | 不透明 | 下层可被遮挡；状态栏区域需要由上层或其他窗口提供内容 |

这些组合不直接给出实际带宽或内存。比较方案时，应在相同场景下采集图层状态、合成方式、帧时间和内存数据。

### 7.3 背景模糊的合成成本

**核心结论：** 本树背景模糊不仅执行模糊 shader，还可能使模糊层下方进入 client composition 并生成离屏快照；性能对比应同时测量这些工作，而不是只比较模糊半径。

**dim 层**用于在窗口后方压暗内容，例如对话框后的半透明黑层。A13 也可在该层上设置背景模糊半径。下面是窗口侧的条件判断（`WindowState.java:5791-5815`）：

```java
private void applyDims() {
    if (!mAnimatingExit && mAppDied) {
        mIsDimming = true;
        getDimmer().dimAbove(getSyncTransaction(), this, DEFAULT_DIM_AMOUNT_DEAD_WINDOW);
    } else if (((mAttrs.flags & FLAG_DIM_BEHIND) != 0 || shouldDrawBlurBehind())
               && isVisibleNow() && !mHidden) {
        // Only show the Dimmer when the following is satisfied:
        // 1. The window has the flag FLAG_DIM_BEHIND or blur behind is requested
        // 2. The WindowToken is not hidden so dims aren't shown when the window is exiting.
        // 3. The WS is considered visible according to the isVisible() method
        // 4. The WS is not hidden.
        mIsDimming = true;
        final float dimAmount = (mAttrs.flags & FLAG_DIM_BEHIND) != 0 ? mAttrs.dimAmount : 0;
        final int blurRadius = shouldDrawBlurBehind() ? mAttrs.getBlurBehindRadius() : 0;
        getDimmer().dimBelow(getSyncTransaction(), this, dimAmount, blurRadius);
    }
}

private boolean shouldDrawBlurBehind() {
    return (mAttrs.flags & FLAG_BLUR_BEHIND) != 0
        && mWmService.mBlurController.getBlurEnabled();
}
```

该逻辑检查窗口状态以及 `shouldDrawBlurBehind`。随后 Dimmer 设置层级和模糊半径（`Dimmer.java:255-270`）：

普通的窗口后方处理路径要同时满足以下条件：窗口声明 `FLAG_DIM_BEHIND` 或 `shouldDrawBlurBehind()` 为 true，窗口当前可见，且没有被隐藏。`shouldDrawBlurBehind()` 本身还要求 `FLAG_BLUR_BEHIND` 和全局模糊开关同时为 true。

两个效果可分别请求：只声明 dim 时，半径为零、仍可压暗；只请求 blur 时，dimAmount 可为零、仍可传入模糊半径。源码前面的应用死亡分支另有 `dimAbove` 处理，不应混同为普通窗口后方模糊。

```java
private void dim(SurfaceControl.Transaction t, WindowContainer container, int relativeLayer,
        float alpha, int blurRadius) {
    final DimState d = getDimState(container);

    if (d == null) {
        return;
    }

    // The dim method is called from WindowState.prepareSurfaces(), which is always called
    // in the correct Z from lowest Z to highest. This ensures that the dim layer is always
    // relative to the highest Z layer with a dim.
    t.setRelativeLayer(d.mDimLayer, container.getSurfaceControl(), relativeLayer);
    t.setAlpha(d.mDimLayer, alpha);
    t.setBackgroundBlurRadius(d.mDimLayer, blurRadius);

    d.mDimming = true;
```

SurfaceFlinger 首先识别需要模糊的层，并处理 sideband 和 opaque 等条件（`Output.cpp:869-887`）：

```cpp
compositionengine::OutputLayer* Output::findLayerRequestingBackgroundComposition() const {
    compositionengine::OutputLayer* layerRequestingBgComposition = nullptr;
    for (auto* layer : getOutputLayersOrderedByZ()) {
        auto* compState = layer->getLayerFE().getCompositionState();

        // If any layer has a sideband stream, we will disable blurs. In that case, we don't
        // want to force client composition because of the blur.
        if (compState->sidebandStream != nullptr) {
            return nullptr;
        }
        if (compState->isOpaque) {
            continue;
        }
        if (compState->backgroundBlurRadius > 0 || compState->blurRegions.size() > 0) {
            layerRequestingBgComposition = layer;
        }
    }
    return layerRequestingBgComposition;
}
```

然后在 updateCompositionState 里把它**以下**的所有层标记 forceClientComposition（`Output.cpp:758-770`）：

```cpp
mLayerRequestingBackgroundBlur = findLayerRequestingBackgroundComposition();
bool forceClientComposition = mLayerRequestingBackgroundBlur != nullptr;

for (auto* layer : getOutputLayersOrderedByZ()) {
    layer->updateCompositionState(refreshArgs.updatingGeometryThisFrame,
                                  refreshArgs.devOptForceClientComposition ||
                                          forceClientComposition,
                                  refreshArgs.internalDisplayRotationFlags);

    if (mLayerRequestingBackgroundBlur == layer) {
        forceClientComposition = false;
    }
}
```

在该实现路径中，受模糊影响的下层可能被标记为 client composition。模糊使用已合成内容的离屏快照作为输入（`SkiaGLRenderEngine.cpp:879-929`）：

```cpp
if (requiresCompositionLayer) {
    activeSurface = dstSurface->makeSurface(dstSurface->imageInfo());
    canvas = mCapture->tryOffscreenCapture(activeSurface.get(), &offscreenCaptureState);
    blurCompositionLayer = &layer;
    break;
}
...
// save a snapshot of the activeSurface to use as input to the blur shaders
blurInput = activeSurface->makeImageSnapshot();
```

这一过程的成本来源可以分别分析：

1. **改变合成方式**：找到请求背景模糊的层后，代码在按 Z 序更新输出层时，将其下方受影响层标记为强制 client composition。存在 sideband stream 时，上面的查找函数直接返回 null，不按这条路径强制模糊合成。
2. **生成模糊输入**：RenderEngine 在需要时建立离屏 surface，并对已合成内容生成快照；模糊处理使用这份图像，而不是分别对每个下层 Drawable 调用模糊。
3. **执行模糊 pass**：Kawase 实现对输入缩放并执行有限次数的 pass。窗口数量、输出面积、设备 GPU、缓存和 HWC 支持都会影响实际耗时。

所以，“下层更多”可能增加生成模糊输入的工作，但源码并不能单独给出设备的精确增量，也不能证明模糊 shader 的成本一定可以忽略。

Kawase 模糊的实现对输入降采样，并限制 pass 数量（`KawaseBlurFilter.cpp:62-99`）：

```cpp
sk_sp<SkImage> KawaseBlurFilter::generate(GrRecordingContext* context, const uint32_t blurRadius,
                                          const sk_sp<SkImage> input, const SkRect& blurRect)
    const {
    // Kawase is an approximation of Gaussian, but it behaves differently from it.
    // A radius transformation is required for approximating them, and also to introduce
    // non-integer steps, necessary to smoothly interpolate large radii.
    float tmpRadius = (float)blurRadius / 2.0f;
    float numberOfPasses = std::min(kMaxPasses, (uint32_t)ceil(tmpRadius));
    float radiusByPasses = tmpRadius / (float)numberOfPasses;

    // create blur surface with the bit depth and colorspace of the original surface
    SkImageInfo scaledInfo = input->imageInfo().makeWH(std::ceil(blurRect.width() * kInputScale),
                                                       std::ceil(blurRect.height() * kInputScale));
    ...
    // start by downscaling and doing the first blur pass
    ...
    // And now we'll build our chain of scaled blur stages
    for (auto i = 1; i < numberOfPasses; i++) {
        ...
        tmpBlur = blurBuilder.makeImage(context, nullptr, scaledInfo, false);
    }
    return tmpBlur;
}
```

例如半径 56 时，代码得到 `tmpRadius=28`，pass 数取上限 4。实际性能还取决于快照、合成、分辨率和设备 GPU；不能仅根据 pass 数比较成本。系统还通过 `BlurController` 控制模糊是否可用（`BlurController.java:129-133`）：

```java
final boolean newEnabled = CROSS_WINDOW_BLUR_SUPPORTED && !mBlurDisabledSetting
        && !mInPowerSaveMode && !mTunnelModeEnabled && !mCriticalThermalStatus;
```

---

## 8. 测量层：修正后的命令手册

每条命令注明服务端处理入口与注意事项（✓=本树核实存在）：

```bash
S="-s <SERIAL>"

# 声明层（第1章）
adb $S shell "dumpsys activity activities" | grep -E "occludesParent|state=|visible"
#   occludesParent= 输出点：ActivityRecord.java:1175 ✓；state=/visible= 同文件:1168、Task.java:3985
adb $S shell "dumpsys window windows" | grep -A30 com.yadea.setting
#   frame 输出格式是 "Frames: ... frame="（WindowFrames.java:204-206）✓；grep mFrame 查不到任何内容
#   注意：isOpaque 无文本 dump（仅 ProtoLog，WindowSurfaceController.java:226）✗

# 生命周期（第2章）
adb $S logcat -b events -v time -s wm_on_paused_called wm_on_stop_called wm_on_resume_called
#   事件定义 EventLogTags.logtags:14（30049 wm_on_stop_called）✓
adb $S shell dumpsys gfxinfo com.yadea.launcher | head -20
#   "Total frames rendered" 只计真实完成帧（ProfileData.cpp:117）✓ → 判断"已停止绘制"的依据

# 合成层（第5章）
adb $S shell dumpsys SurfaceFlinger --list | grep -i launcher
#   ⚠ 列的是整棵层树含 hidden 层（SurfaceFlinger.cpp:5188-5191）；
#   名称存在不等于参与合成；结合层状态与 trace 判断：
adb $S shell dumpsys SurfaceFlinger | grep -A12 com.yadea.launcher   # 看 isVisible/visibleRegion

# 转场（第6章）
adb $S shell getprop persist.wm.debug.shell_transit        # false=legacy 路径（本树默认）
adb $S shell "dumpsys window animator" | grep -A8 yadea    # WindowManagerService.java:7273 ✓
#   ✗ dumpsys window transitions 在本树无此分节（WindowManagerService.java:7263-7328）
#   替代：atrace wm 切片（prepareAppTransition/goodToGo）或 winscope proto（APP_TRANSITION_STATE）
adb $S shell cmd window tracing start                      # WindowManagerShellCommand.java:103 ✓
adb $S shell service call SurfaceFlinger 1025 i32 1        # layer tracing（SurfaceFlinger.cpp:5994）
#   ✗ cmd surfaceflinger layer-tracing 不存在；输出 /data/misc/wmtrace/layers_trace.winscope

# Perfetto（第6.2节口径修正）
#   ✗ android.surfaceflinger.layers 是 A14 数据源；A13 用：
#   android.surfaceflinger.frame（FrameTracer.h:72）+ android.surfaceflinger.frametimeline（FrameTimeline.h:492）
#   atrace 类别无 sf/app（SF 用 gfx 标签，SurfaceFlinger.cpp:23；应用侧用 -a 包名）
adb $S shell perfetto -o /data/misc/perfetto-traces/t.pftrace -t 15s \
  sched freq idle am wm gfx view binder_driver hal dalvik input res memory

# 几何/内存/其他
adb $S shell wm size && adb $S shell wm density            # WindowManagerShellCommand.java:93 ✓
adb $S shell dumpsys meminfo com.yadea.launcher | grep -E "Graphics|EGL mtrack|GL mtrack"
adb $S shell cmd uimode night yes                          # 会更改设备夜间模式
adb $S shell cmd overlay lookup android android:color/background_material_dark
#   检查设备主题资源；转场实际取色还需结合 TaskDescription 与 trace
```

---

## 9. 结语：分析时先确认三个条件

1. **区分声明与像素**（第 1、2、5 章）：`windowIsTranslucent` 影响 Activity 的遮挡状态；背景、窗口格式及 layer 属性影响绘制和合成。透明区域最终显示什么，还要检查下层与合成路径。
2. **先确认几何与动画条件**（第 3、6 章）：常规窗口的 frame、View 和 buffer 尺寸由布局关联，也存在缩放和边距等例外。通过 leash 平移应用窗口时，画在该窗口中的背景图像会随之平移；独立系统栏的行为仍需核对实际转场。
3. **区分可见性请求与实际隐藏**（第 2、6 章）：转场期间，期望可见状态可能先改变，实际隐藏和停止可被延后。定位露底问题时，应同时检查转场状态、图层和生命周期事件。

### 附录 A：Android 13 版本差异

- 该版本读取 `windowIsTranslucent` 时不直接设置 format 或系统栏 flags；默认 format 可由 windowBackground 的 opacity 参与决定。
- `ActivityInfoBase` 不存在（A14 才拆分）；`isTranslucentOrFloating` 只读 translucent/floating。
- `hideRobustly` 已删除（事务化 `WindowSurfaceController.hide` 承担）；`showRobustly` 仍在。
- `ui::BlendMode::Skip` 不存在；替身 = HAL `BlendMode::NONE` + `disableBlending`/`skipContentDraw`。
- 硬件加速窗口 layer format 恒 TRANSLUCENT，不透明性全靠 `eLayerOpaque`。
- `applySurfaceChangesTransaction` 的显隐职责移至 `prepareSurfaces`。
- Shell Transitions 默认关（`persist.wm.debug.shell_transit`=false），legacy AppTransition 是默认路径。
- perfetto 无 `android.surfaceflinger.layers` 数据源；`cmd surfaceflinger layer-tracing` 不存在。

### 附录 B：哪些路径需要读取像素

本文的 Activity 遮挡判定不依赖逐像素检查。系统其他路径会读取或处理图像内容，用途包括合成、生成副本和提取壁纸颜色；它们不应被混为同一种操作：

| 回读者 | 入口 | 为什么必须看像素 |
|---|---|---|
| SF 合成（每帧） | SkiaGLRenderEngine.cpp:1076-1268 逐层纹理采样；HWC 扫描输出 | 产出输出本身就是像素；结果不回流任何策略 |
| 截图服务 | `SurfaceFlinger.cpp`:6511/6610 captureDisplay/captureLayers（AIDL :7639-7661） | screencap/screenrecord/SystemUI 截屏的出口 |
| WMS 旋转动画 | `ScreenRotationAnimation.java`:174/199（SurfaceControl.captureDisplay） | 旋转期间旧画面要冻结交叉淡出——"旧屏幕内容"无法用声明表达 |
| WMS 任务快照 | `TaskSnapshotController.java`:440/504（captureLayersExcluding） | recents/快照起始窗需要真实内容做视觉占位；WMS 对结果 buffer 只 opaque 存储转发，从不解释 |
| 模糊离屏快照 | SkiaGLRenderEngine.cpp:879-929 makeImageSnapshot | 模糊的输入就是已合成的下层内容（§7.3） |
| 壁纸取色 | `WallpaperColors.java`:568-607 getPixels+亮度统计（调用点 `WallpaperManagerService.java`:552/597） | "壁纸偏亮还是偏暗"是像素统计性质，没有任何声明者；结果驱动状态栏图标深浅与深色调度 |
| PixelCopy | `frameworks/base/graphics/java/android/view/PixelCopy.java`:195 copySurfaceInto | 应用把 Surface/窗口内容取成 Bitmap（分享、快照），跨进程像素只能经 RenderThread/SF |
| 虚拟屏/投屏 | VirtualDisplaySurface（合成输出写进消费者 buffer 队列） | 连续镜像=连续回读，故 MediaProjection 配用户同意弹窗作隐私授权关卡 |

以下列出三个处理图像内容的例子。旋转动画会截取旋转前画面，供动画期间显示（`ScreenRotationAnimation.java:174-199`）：

```java
final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer;
if (isSizeChanged) {
    ...
    final SurfaceControl.DisplayCaptureArgs captureArgs =
            new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                    .setSourceCrop(new Rect(0, 0, width, height))
                    .setAllowProtected(true)
                    .setCaptureSecureLayers(true)
                    .build();
    screenshotBuffer = SurfaceControl.captureDisplay(captureArgs);
} else {
    SurfaceControl.LayerCaptureArgs captureArgs =
            new SurfaceControl.LayerCaptureArgs.Builder(
                    displayContent.getSurfaceControl())
                    .setCaptureSecureLayers(true)
                    .setAllowProtected(true)
```

**任务快照**（`TaskSnapshotController.java:503-512`）——任务退到后台时抓取整个 Task 层树（排除 IME 或导航栏），供 recents 与快照起始窗使用；WMS 对返回的 HardwareBuffer 只存储转发，从不解读：

```java
final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
        SurfaceControl.captureLayersExcluding(
                task.getSurfaceControl(), mTmpRect, scaleFraction,
                pixelFormat, excludeLayers);
if (outTaskSize != null) {
    outTaskSize.x = mTmpRect.width();
    outTaskSize.y = mTmpRect.height();
}
final HardwareBuffer buffer = screenshotBuffer == null ? null
        : screenshotBuffer.getHardwareBuffer();
```

**壁纸取色**（`WallpaperColors.java:565-607`）是根据像素统计生成提示的一例：getPixels 拉平整幅壁纸，逐像素算亮度、数暗像素，输出 HINT_SUPPORTS_DARK_TEXT 等提示位驱动状态栏图标深浅与深色调度：

```java
int[] pixels = new int[source.getWidth() * source.getHeight()];
double totalLuminance = 0;
final int maxDarkPixels = (int) (pixels.length * MAX_DARK_AREA);
int darkPixels = 0;
source.getPixels(pixels, 0 /* offset */, source.getWidth(), 0 /* x */, 0 /* y */,
        source.getWidth(), source.getHeight());
...
for (int i = 0; i < pixels.length; i++) {
    int pixelColor = pixels[i];
    ColorUtils.colorToHSL(pixelColor, tmpHsl);
    final int alpha = Color.alpha(pixelColor);
    ...
    // Calculate the adjusted luminance of the dimmed wallpaper pixel color.
    double adjustedLuminance = ColorUtils.calculateLuminance(compositeColors);
    ...
    if (!satisfiesTextContrast && alpha != 0) {
        darkPixels++;
    }
    totalLuminance += adjustedLuminance;
}

int hints = 0;
double meanLuminance = totalLuminance / pixels.length;
if (meanLuminance > BRIGHT_IMAGE_MEAN_LUMINANCE && darkPixels < maxDarkPixels) {
    hints |= HINT_SUPPORTS_DARK_TEXT;
}
```

这些调用点展示了设置壁纸时的颜色提取；不能据此推断所有壁纸都是静态的，也不能推广为系统所有决策都不读取像素。

其他机制也可能基于标志或几何信息工作：例如截图对 secure 层的处理、触摸遮挡过滤与屏幕放大镜使用的层变换。它们分别解决不同问题，不宜一概推断为像素回读。

在本文讨论的可见性路径中，WMS 使用窗口和 Activity 状态作判断；截图等其他功能则可通过 SurfaceFlinger 获取内容副本。区分调用目的与具体路径，比笼统地说“系统不读取像素”更准确。

---

## 专题：全屏 Setting 遮挡 Launcher 的判定与转场

**通常是在动画播放前，WMS 就已经判断 Launcher 最终不需要保持可见；但这不意味着 Launcher 窗口在动画开始前就从屏幕上消失。**

这里有两种不同的“遮挡”：

- **WMS 的 Activity 可见性判断**：根据主题声明、任务层级和窗口模式，判断 Launcher 最终是否需要可见。
- **SurfaceFlinger 的逐帧可见区域计算**：根据当前图层位置、透明度和不透明区域，判断这一帧实际需要显示 Launcher 的哪些部分。

前者可以在设置窗口还在屏幕下方、甚至尚未开始播放动画时就完成；后者会反映动画过程中的实际位置。

以下以两个独立任务为例：**全屏 Setting 任务在上，Launcher 任务在下**；Setting 是非浮动窗口、不显示壁纸，且设备走 legacy 转场。

### 1. WMS 先根据主题记录 Setting 的遮挡状态

创建 Setting 的 `ActivityRecord` 时，WMS 读取主题：

```java
mOccludesParent = !ActivityInfo.isTranslucentOrFloating(ent.array)
        || ent.array.getBoolean(R.styleable.Window_windowShowWallpaper, false);
```

`isTranslucentOrFloating()` 读取 `windowIsTranslucent` 和 `windowIsFloating`。

在本例中：

```text
windowIsTranslucent = false
windowIsFloating    = false
windowShowWallpaper = false

→ isTranslucentOrFloating = false
→ mOccludesParent = true
```

也就是说，WMS **记录 Setting Activity 可以遮挡其父容器范围内的下层内容**。这是根据主题得到的状态；WMS 没有检查 Setting 窗口 buffer 中的每个像素。

源码位置：[ActivityRecord.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:2066)、[ActivityInfo.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/core/java/android/content/pm/ActivityInfo.java:1775)。

### 2. WMS 判断上方的 Setting 任务容器是否透明

Launcher 和 Setting 通常属于不同任务，所以不能只在同一个 Activity 列表里判断。WMS 还要判断**上方的任务容器**能否让下方任务保持可见。

`TaskFragment.isTranslucent()` 会查找容器中符合条件的不透明 Activity：

- Activity 正在 finishing：不作为不透明 Activity 计入。
- Activity 不可见，而且也不是正在启动的 Activity：不计入。
- 找到符合条件、且 `occludesParent()` 为 true 的 Activity：容器可被判为不透明。
- 找不到：容器被判为透明。

本例中，Setting 正在启动，并且 `occludesParent()` 为 true，因此上方 Setting 任务容器可以被判为不透明。

这里的“不透明”仍然是 **WMS 的容器状态判断**，不是说 WMS 已经验证了设置窗口实际画出的每个像素。

源码位置：[TaskFragment.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java:987)。

### 3. Launcher 容器检查上方任务

WMS 为 Launcher 所在容器计算可见性时，会检查其父容器及上方同级容器。本例中，关键逻辑可概括为：

```java
if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
    if (isTranslucent(other, starting)) {
        // 上方全屏任务透明，继续检查
        continue;
    }
    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
}
```

代入本例：

```text
Setting 任务在 Launcher 任务上方
        ↓
Setting 任务是全屏模式
        ↓
Setting 任务被判定为不透明
        ↓
Launcher 容器的目标可见性 = INVISIBLE
```

**这里没有等待进入动画结束，也没有检查 Setting 当前滑到了屏幕哪个位置。** 它检查的是任务窗口模式和容器透明状态。

分屏、多窗口、父容器被隐藏等情况还有其他分支，不能直接套用这个全屏示例。

源码位置：[TaskFragment.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/TaskFragment.java:1114)。

### 4. Launcher Activity 被请求变为不可见

Launcher 容器的结果传给 `EnsureActivitiesVisibleHelper`；它继续结合当前用户、锁屏等条件计算 Launcher Activity 的可见性。当最终 `reallyVisible=false` 时，调用：

```text
Launcher.makeInvisible()
    → setVisibility(false)
    → mVisibleRequested = false
```

在符合条件的生命周期状态下，Launcher 还会被加入待停止队列。

到这一步，准确含义是：**WMS 已经决定 Launcher 的目标状态是不需要可见。** 它并不等于 Launcher 的 Surface 此刻已经被 SurfaceFlinger 隐藏。

源码位置：[EnsureActivitiesVisibleHelper.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/EnsureActivitiesVisibleHelper.java:60)、[ActivityRecord.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java:6210)。

### 5. 动画期间，为什么仍可能看到 Launcher？

若 Launcher 作为关闭方参加 legacy 转场，WMS 可以暂缓提交部分可见性变化。转场准备就绪后，`AppTransitionController` 会装配动画，并对关闭方调用 `commitVisibility(false)`。

这里需要特别注意：**Launcher 的 Activity 可见状态 `mVisible` 可以在动画开始阶段就提交为 false，并非必须等动画结束。** 但窗口是否仍显示，还有单独的判断：

```java
return ((!isParentWindowHidden() && atoken.isVisible())
        || isAnimating(TRANSITION | PARENTS));
```

只要满足窗口 Surface 存在、策略允许显示等前提，即使 Activity 的 `isVisible()` 已为 false，**窗口自身或父容器仍在执行转场动画时，`isOnScreen()` 仍可能返回 true**。

因此可能出现：

```text
WMS 已决定最终隐藏 Launcher
        ↓
Launcher 的目标可见性已设为 false
        ↓
退出动画仍在运行，Launcher Surface 暂时保留
        ↓
Setting 从底部滑入，尚未覆盖屏幕顶部
        ↓
顶部仍可能看到 Launcher
```

退出动画完成后，对应的窗口退出路径会执行 hide 和清理；若没有退出动画或条件不同，隐藏也可能更早。`onStop` 又是另一条需要等待条件的路径。

源码位置：[AppTransitionController.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/AppTransitionController.java:278)、[WindowState.java](/home/liang/Project/MyProject/AAOS13_study/frameworks/base/services/core/java/com/android/server/wm/WindowState.java:2124)。

### 6. SurfaceFlinger 再判断“这一帧实际盖住了多少”

动画运行时，SurfaceFlinger 根据图层**当前的变换后位置**及不透明区域计算可见区域。Setting 从下向上滑动时，它尚未覆盖的区域，Launcher 仍可能对该帧有贡献——前提是 Launcher 图层此时仍可见。

所以同一时刻可以同时成立：

> **WMS 已判定 Launcher 最终不需要可见；SurfaceFlinger 当前帧仍在显示 Launcher 尚未被设置窗口覆盖的部分。**

我已将这条跨任务判定、时序和两种遮挡的区别补入[文章第 2.1 节](Android13窗口透明与合成全链路解剖.md)。文档路径和代码围栏检查通过；设备实际转场与任务状态仍需实机 trace 确认。

### 7. `windowIsFloating` 是什么？与 `windowIsTranslucent` 有何关系？

`windowIsFloating` 是 Activity 主题中的**窗口属性**，表示该窗口按浮动窗口的方式配置；对话框样式的 Activity 是常见例子。它影响的是窗口如何布局，**不是任务的窗口模式，也不是“这个窗口的像素必然透明”**。

在本树的 `PhoneWindow.generateLayout()` 中，系统读取该属性后采用不同的初始布局配置（`PhoneWindow.java:2664-2673`）：

```java
mIsFloating = a.getBoolean(R.styleable.Window_windowIsFloating, false);
int flagsToUpdate = (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)
        & (~getForcedWindowFlags());
if (mIsFloating) {
    setLayout(WRAP_CONTENT, WRAP_CONTENT);
    setFlags(0, flagsToUpdate);
} else {
    setFlags(FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR, flagsToUpdate);
    getAttributes().setFitInsetsSides(0);
    getAttributes().setFitInsetsTypes(0);
}
```

两条路径的结果分别是：

- **浮动窗口**：初始宽、高设置为 `WRAP_CONTENT`，并清除上述两项布局标志中允许更新的部分；窗口通常按内容大小布局。后续仍可通过布局参数或其他规则改变尺寸，因此不能断言浮动窗口一定小于屏幕。
- **非浮动窗口**：设置上述布局标志，并清空需要由窗口 frame 避让的 inset 类型和边；窗口可以延伸到系统栏下方。实际 frame 还受任务边界、窗口尺寸和后续参数影响。

`windowIsTranslucent` 是另一个独立的主题属性，用来声明半透明窗口；它不由 `windowIsFloating` 自动赋值。两者的关系体现在服务端的 Activity 遮挡判断中：

```java
public static boolean isTranslucentOrFloating(TypedArray attributes) {
    final boolean isTranslucent = attributes.getBoolean(
            com.android.internal.R.styleable.Window_windowIsTranslucent, false);
    final boolean isFloating = attributes.getBoolean(
            com.android.internal.R.styleable.Window_windowIsFloating, false);
    return isFloating || isTranslucent;
}
```

这是 `ActivityInfo.java:1775-1784` 的原始逻辑：**只要任一属性为 true，`isTranslucentOrFloating()` 就为 true。** 在 `windowShowWallpaper=false` 的前提下，第一节中的初始化公式会得到 `mOccludesParent=false`。因此，一个浮动 Activity 即使将 `windowIsTranslucent` 设为 false，也不能仅凭这个值推断它会遮挡整个父容器。最终 Activity 可见性仍需结合任务容器和其他条件。

例子：一个设置对话框主题为 `windowIsFloating=true`、`windowIsTranslucent=false`，它的背景可以是不透明纯色，但 WMS 仍按浮动窗口参与上述判断；一个普通设置页为两个属性都 false，则在非壁纸等前提下可得到 `mOccludesParent=true`。**窗口是否浮动、绘制出的像素是否透明，是不同问题。**

### 8. `windowShowWallpaper` 又改变了什么？

`windowShowWallpaper` 是主题中的壁纸显示属性。`PhoneWindow.generateLayout()` 读到它为 true 时，会请求设置 `FLAG_SHOW_WALLPAPER`，使窗口参与显示壁纸的相关处理（`PhoneWindow.java:2707-2709`）；窗口中的可见区域是否真的能看到壁纸，还取决于窗口实际绘制和图层遮挡。

在第一节的 `ActivityRecord` 初始化公式中，该属性位于逻辑或的右侧：

```text
mOccludesParent = !(windowIsTranslucent || windowIsFloating)
                  || windowShowWallpaper
```

该表达式按上文三个布尔属性概括 `isTranslucentOrFloating()` 的结果，仅用于解释**创建 ActivityRecord 时的初始值**：

- 普通设置页：三个值均为 false，得到 true。
- 半透明或浮动窗口，且 `windowShowWallpaper=false`：得到 false。
- `windowShowWallpaper=true`：该或条件使初始值为 true，即使前两个属性中有一个为 true。

此外，`occludesParent()` 还会检查 `finishing` 与运行时的 `showWallpaper()`（`ActivityRecord.java:3088-3102`）。所以，主题里的 `windowShowWallpaper`、窗口的 `FLAG_SHOW_WALLPAPER` 和方法中的 `showWallpaper()` **相关但不是同一个字段**；分析实时状态时应查看对应代码与设备状态。

设计上的关键区别是：WMS 不能只把“显示壁纸的窗口”当成普通的半透明 Activity 去推断任务下层可见性。它需要同时处理壁纸和 Activity 容器的关系；而壁纸最终是否露出，仍由窗口实际像素和图层合成决定。

### 9. Setting 任务何时、如何被判定为全屏模式？

**这里的全屏指任务容器的 `WINDOWING_MODE_FULLSCREEN`，不是主题的 `windowIsFloating=false`，也不是 `windowFullscreen` 隐藏状态栏的标志。** 这三者分别描述任务窗口模式、窗口是否浮动，以及窗口是否请求全屏显示；不能互相替代。

任务被创建或复用时，WMS 需要选择目标根任务和窗口模式。`RootWindowContainer.java:3085` 在相应选择路径调用 `TaskDisplayArea.resolveWindowingMode()`；该方法的选择顺序是（`TaskDisplayArea.java:1480-1503`）：

1. 若启动时的 `ActivityOptions` 指定了窗口模式，先使用该请求。
2. 否则依次查看候选任务、ActivityRecord，以及 TaskDisplayArea 自身的窗口模式。
3. 对选出的模式做合法性校验；若结果仍为 `WINDOWING_MODE_UNDEFINED`，返回 `WINDOWING_MODE_FULLSCREEN`。

任务的实际窗口模式保存在合并后的配置中。容器调用 `getWindowingMode()` 时，从 `mFullConfiguration.windowConfiguration` 读取当前生效值（`ConfigurationContainer.java:524-525`）；`setWindowingMode()` 等操作可以请求更新配置，最终值仍受父级配置与系统规则影响。

**本例中何时检查？** 当后续可见性更新计算 Launcher 容器状态时，第三节的 `TaskFragment.getVisibility()` 会读取上方容器的**当前** `getWindowingMode()`；若它为 `WINDOWING_MODE_FULLSCREEN`，并且该容器被判为不透明，就返回不可见。这个检查发生在可见性计算中，而不是等窗口动画播放完后才按屏幕上实际覆盖面积判断。

因此，“Setting 任务是全屏模式”是本例的前提，不能从 `windowIsFloating=false` 单独推出。分屏、自由窗口、任务复用或运行时调整都可能改变模式；在目标设备上应查看任务的实际 `windowingMode`、bounds 和层级，再确定本例分支是否命中。任务文本输出中窗口模式的来源可见 `Task.java:3985-3990`。

### 10. legacy 转场是什么？有什么用？

**legacy AppTransition 是本树中由 WMS 主导的应用窗口转场路径。** 当 Activity 或任务打开、关闭、移到前台时，它协调即将打开与关闭的容器：等待转场条件、选择动画、提交可见性变化并驱动窗口动画。它要解决的问题是：新旧窗口切换时，系统怎样在内容就绪和窗口显示状态之间安排动画。

本树同时提供 **Shell Transitions**：服务端协调参与者，WMShell 的 transition handler 参与执行转场。二者不应混成一条调用链。服务端判断 Shell Transitions 是否启用的依据是是否注册了 transition player（`TransitionController.java:248-250`）；WMShell 在本树读取的属性默认值为 false（`Transitions.java:91-92、201-205`）：

```java
public static final boolean ENABLE_SHELL_TRANSITIONS =
        SystemProperties.getBoolean("persist.wm.debug.shell_transit", false);
```

因此，**本树默认配置是 legacy，但设备实际使用哪条路径还应核对该属性、player 注册及厂商修改**。第五节对 `closingApps`、`handleClosingApps()` 的分析限定在 legacy 路径；不能仅因为设备运行 Android 13 就断言一定命中。

### 11. “退出动画”是谁的？如何设置？

第五节的“退出动画”是指：**打开 Setting 的这次转场中，Launcher 作为关闭方时作用于 Launcher 窗口或其任务容器的动画**。它与 Setting 的进入动画是两条不同的动画：前者影响旧画面如何退出，后者影响新画面如何出现。具体动画目标可能提升为任务等容器，不能只凭 Activity 名称判断实际动画作用在哪一层。

**动画资源从哪里选择？** 在所讨论的 legacy 路径中，`AppTransitionController` 从打开、关闭和变化的参与者中选择用于解析动画的窗口参数 `animLp`，然后将同一组参数传给进入和退出目标（`AppTransitionController.java:832-851、1116-1161`）。因此，若最终选中的是 Setting 的主窗口参数，Launcher 的退出动画也可能从 **Setting 主题对应的窗口动画样式**解析；这不是“必然读取 Launcher 自己的主题”。远程动画、任务级动画和其他覆盖规则可能改变选择。

**普通主题动画如何配置？** 先在 Activity 主题中设置 `android:windowAnimationStyle`，再在动画样式中分别设置进入和退出资源。例如：

```xml
<style name="MyWindowAnimation" parent="@android:style/Animation.Activity">
    <item name="android:activityOpenEnterAnimation">@anim/setting_enter</item>
    <item name="android:activityOpenExitAnimation">@anim/launcher_exit</item>
    <item name="android:taskOpenEnterAnimation">@anim/setting_enter</item>
    <item name="android:taskOpenExitAnimation">@anim/launcher_exit</item>
</style>

<style name="MySettingTheme" parent="@android:style/Theme.Material.Light.NoActionBar">
    <item name="android:windowAnimationStyle">@style/MyWindowAnimation</item>
</style>
```

上面的资源名是说明用示例，需在项目中实际创建动画资源并确保目标 Activity 使用了该主题。**关键是先查转场类型**：`TRANSIT_OLD_ACTIVITY_OPEN` 的关闭方使用 `activityOpenExitAnimation`；`TRANSIT_OLD_TASK_OPEN` 的关闭方使用 `taskOpenExitAnimation`（`AppTransition.java:655-677`）。从桌面打开另一个任务时不能未经确认就只改 Activity 级属性。

例如，若目标是让 Launcher 在退出期间不因该动画改变透明度，可以把退出资源写成以下形式：

```xml
<alpha xmlns:android="http://schemas.android.com/apk/res/android"
    android:fromAlpha="1.0"
    android:toAlpha="1.0"
    android:duration="300" />
```

这只定义了一个 **300 ms 内 alpha 恒为 1 的动画资源**。只有当前转场实际选中了该资源并把它应用给 Launcher 的关闭目标时，它才参与退出；资源存在或主题中有配置，都不等于设备已经执行了它。退出窗口是否因此保留、何时隐藏，还需同时检查第五节所述动画状态和 Surface 显隐。

**启动时也可以请求自定义动画。** 应用或系统组件可使用 `ActivityOptions.makeCustomAnimation(...)` 等接口提供进入与退出资源；但本树 `AppTransition.loadAnimation()` 对任务级自定义动画受 `canCustomizeAppTransition` / `overrideTaskTransition` 条件限制，相关覆盖选项还受权限检查（`AppTransition.java:891-897`、`SafeActivityOptions.java:345-352`）。这不是任意应用都可无条件使用的开关。远程动画或 Shell Transitions 也可能接管实际动画。

**本设备仓的当前证据：**本次读取到 `ActivityStarter.kt:212-218`（yadea 的 SystemUI 模块） 在 `goAppByPkg` 路径调用 `ActivityOptionsCompat.makeCustomAnimation(context, 0, 0)`；仓内虽然存在 `setting_enter.xml` 和 `setting_hold_outgoing.xml`，但该调用并未引用它们。实际使用哪条启动路径、设备运行的是哪个 APK、最终选中了什么动画，必须用设备版本和转场 trace 验证，不能只看资源文件名。
