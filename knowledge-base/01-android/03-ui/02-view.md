# View 测量、布局与绘制

> 学习资料（文章模式沉淀）。主线：measure/layout/draw 三趟的职责边界、MeasureSpec 与自定义 View 的尺寸契约、layout 与 onLayout、requestLayout 与 invalidate 的分工、硬件加速下的 display list 模型与"不 invalidate 就不重绘"、绘制顺序与裁剪、掉帧的结构性来源、图层类型与 Surface/TextureView 取舍、属性动画与布局动画的差别。AOSP 机制按本地 AAOS13 源码（Android 13）核对（View.java、ViewRootImpl.java、Choreographer.java、`libs/hwui/`），加速与绘制模型按官方文档口径（2026-09 检索），经验性结论标注社区口径。帧调度见 [../04-graphics/01-render-pipeline-vsync.md](../04-graphics/01-render-pipeline-vsync.md)，实践侧的渲染优化见 [View 与 Compose 渲染实战](../04-graphics/12-app-view-compose-practice.md)，掉帧度量方法见[流畅性度量](../12-performance/05-smoothness.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: Android View 与 ViewGroup 分别表示什么？**

View 是界面树中可参与测量、布局、绘制和输入处理的基础节点。ViewGroup 是 View 的容器子类，除自身视图职责外还负责组织子 View 的测量、布局和事件分发。

1. **View**：按钮、文本和自定义绘制控件通常是叶节点，但是否可滚动、聚焦或响应点击，取决于具体实现与状态。
2. **ViewGroup**：线性布局等容器持有子节点，并决定它们的测量约束、位置和输入事件如何向下分发。ViewGroup 也可以绘制自身内容。

因此，不能只凭“它是一个 View”推断控件支持某种交互或容器行为。

**Q2: 自定义 View 首次显示时为什么要先 measure、再 layout、最后 draw？**

三趟遍历分别解决尺寸、位置和像素内容。layout 依赖 measure 的结果，draw 又依赖已经确定的几何信息，因此正常首次呈现按 measure → layout → draw 进行。

1. **measure（测量）**：父容器把 `MeasureSpec` 约束逐层传给子 View。每个 View 计算并保存自己的测量宽高，ViewGroup 也据子 View 结果确定自身尺寸。
2. **layout（布局）**：父容器根据测量结果确定子 View 的 `left`、`top`、`right`、`bottom`，递归安排整棵树的位置。
3. **draw（绘制）**：框架根据最终尺寸和位置生成 View 内容，并递归绘制子树。

窗口首次遍历时，ViewRootImpl 依次执行 performMeasure、performLayout 和 performDraw。measure 还可能因父子尺寸协商而重复，但布局必须使用最终测量结果。具体实现可在 View.java 与 ViewRootImpl.java 核对。

**Q3: Android View 动画、帧动画和属性动画分别改变什么？**

三类动画的区别在于被驱动的对象不同：帧动画切换图像资源，View 动画改变绘制变换，属性动画按时间更新对象属性。属性动画框架从 API 11 起提供。

1. **View 动画**：对 View 的绘制结果做平移、缩放、旋转或透明度变换。它不一定改变布局位置或触摸命中区域。
2. **帧动画**：按顺序播放一组 Drawable，适合表现预先制作好的图像序列。
3. **属性动画**：逐帧计算属性值并写回目标对象，适用于需要真实属性变化或非 View 对象的场景。它更通用，但每帧属性更新仍有渲染成本。

只改外观可用 View 动画，需要图像序列用帧动画，需要属性值随时间变化用属性动画。

**Q4: [learning] 自定义 View 尺寸为 0 或被截断，MeasureSpec 的 mode 和 size 如何约束结果？**

`MeasureSpec` 把模式和尺寸打包在一个整数中，父容器用它约束子 View 的尺寸。自定义 View 要按 mode 计算期望大小，并最终调用 `setMeasuredDimension()`。

1. `EXACTLY`：父容器要求使用给定尺寸，子 View 应按该尺寸测量。
2. `AT_MOST`：给定尺寸是上限，子 View 可按内容需要取更小尺寸，但不能超出上限。
3. `UNSPECIFIED`：父容器不提供该轴上的尺寸上限，子 View 根据内容决定期望尺寸。
4. **收敛结果**：`resolveSizeAndState()` 按模式把期望尺寸收敛到合法结果。`childMeasuredState` 可把 `MEASURED_STATE_TOO_SMALL` 等状态向父容器汇报，让父容器知道子 View 被约束压缩。

如果自定义 View 不调用 `setMeasuredDimension()`，测量结果仍是初始值，常见表现是尺寸为 0 而没有异常。View.java 中可核对 `resolveSizeAndState()` 与测量状态传播。

**Q5: 可滚动 ViewGroup 内容很长却自身尺寸异常，父尺寸与子尺寸应怎样分别计算？**

ViewGroup 自身尺寸受父容器约束，滚动内容尺寸则由子 View 的测量结果决定，两者不能简单设为同一个值。自定义滚动容器必须分别处理滚动轴和交叉轴的测量规则。

1. **读取父约束**：先根据父容器给 ViewGroup 的 `EXACTLY`、`AT_MOST` 或 `UNSPECIFIED` 约束计算自身可视区域尺寸。
2. **测量滚动内容**：在滚动轴上，容器可按内容高度或宽度测量子 View，再把自身视口尺寸限制在父容器允许的范围内。具体子级 MeasureSpec 取决于容器实现，不能假设所有可滚动 ViewGroup 都使用同一种无界测量。
3. **保持轴间约束**：交叉轴通常仍受父级上限约束，避免内容宽度或高度把视口无限撑大。

在 `onMeasure()` 中应先明确自身视口尺寸和内容尺寸各自的规则，再为子 View 构造 MeasureSpec。多屏之间宽度差异可能让相同内容落入不同约束区间。测量顺序或轴选择错误会使问题只在部分屏幕出现。

**Q6: 自定义 ViewGroup 里的子 View 已创建却不显示，`layout()` 与 `onLayout()` 各负责什么？**

框架调用父 View 的 `layout()` 写入它自己的边界，随后回调 `onLayout()`。自定义 ViewGroup 在 `onLayout()` 中计算并调用每个子 View 的 `layout()`，使子节点取得最终位置。

1. **框架负责父级边界**：`View.layout()` 更新当前 View 的 `mLeft`、`mTop`、`mRight`、`mBottom`，再把布局工作交给 `onLayout()`。
2. **ViewGroup 负责子级位置**：在 `onLayout()` 中依据子 View 的测量尺寸和容器规则，调用子节点的 `layout(left, top, right, bottom)`。
3. **普通 View 的实现**：View 没有子节点，因此基类 `onLayout()` 为空实现。

自定义 ViewGroup 忘记对子 View 调用 `layout()` 时，子 View 虽然已创建并测量，却没有可显示的布局位置。直接改 `left/top` 而不走 `layout()` 也会使内部几何状态与绘制位置不一致。View.java 可核对调用链。

**Q7: 内容更新后画面没变或位置错了，该调用 `requestLayout()` 还是 `invalidate()`？**

布局几何可能变化时请求重新布局，绘制内容变化时标记需要重绘。两种变化同时发生时，应根据自定义 View 的实现分别发出所需请求。

1. **尺寸或位置变化**：增删子 View、间距变化、文本换行、`setPadding()` 等可能改变测量或布局结果的变化调用 `requestLayout()`。
2. **只改绘制内容**：颜色、背景、文字数据或位图替换而不改变几何时调用 `invalidate()`。
3. **只改 Outline**：阴影或裁剪轮廓变化调用 `invalidateOutline()`。Outline 是单独维护的几何状态，普通内容 `invalidate()` 不保证让系统重新计算它。
4. **两类都变**：同时确保重新测量/布局和内容重绘。`requestLayout()` 会排队请求遍历，但不保证每一种自定义实现都因而调用 `onDraw()`。

只 `invalidate()` 而尺寸已变，可能仍按旧几何绘制。只 `requestLayout()` 而绘制数据变了，也可能留下旧内容。View.java 可核对这三个入口的失效路径。

**Q8: 硬件加速后修改绘制数据却仍显示旧内容，为什么必须显式 `invalidate()`？**

硬件加速下，系统把 View 的绘制命令记录为 display list。未被标记为脏的 View 可重用旧 display list，因此修改外部数据本身不会通知框架重新录制。

1. **软件绘制**：系统会重画与脏区相交的 View。即使某些 View 没显式标脏，也可能因为区域重叠而被重新绘制，掩盖遗漏失效通知的问题。
2. **硬件加速**：未失效 View 可重放之前记录的 display list。自定义 View 的绘制数据变化时，若没有 `invalidate()`，屏幕内容可能保持旧状态。
3. **属性 setter 与外部数据**：View 自带属性 setter 通常会主动请求失效。若 View 绘制一个外部可观察对象而该变化没有触发 View 失效，调用方必须通知 View 重绘。

这解释了为什么对象数据已更新、界面却仍显示旧内容。该模型与软件/硬件绘制差异可对照 Android 官方硬件加速文档。

**Q9: 只 `invalidate()` 一个小 View 却感觉整块区域重绘，脏区实际如何传播？**

脏区先沿 View 父链转换到祖先坐标系，最后由 ViewRootImpl 汇总到窗口。实际重绘范围取决于软件绘制或硬件加速路径，不应把“局部 invalidate”理解为只重画脏矩形内的几条像素。

1. **脏区传播**：`invalidate()` 把失效区域向父容器传播，并逐层转换坐标，最终进入 `ViewRootImpl.invalidateChild()` 的窗口遍历。
2. **软件绘制**：系统会重画与脏区相交的 View，因此视觉上可能像一整块区域都重绘。
3. **硬件加速**：系统可以只重录被标记 View 的 display list，并重放其他已记录内容。被标记 View 的绘制命令仍按整个 View 录制，不是只生成脏矩形内的命令。
4. **性能含义**：局部失效可避免不相交兄弟 View 的重录或重放，但不消除被标记 View 自身的录制成本。频繁变化的大 View 可考虑拆分独立子 View，或在适合时用合成变换代替重复重绘。

“局部 invalidate 省不了多少”是经验判断而非普遍结论，需通过 trace 确认具体路径与瓶颈。

**Q10: 动画中的 View 突然盖住兄弟控件，`dispatchDraw()` 的绘制顺序何时会变化？**

子 View 的绘制顺序由 ViewGroup 的子节点顺序、Z 值和自定义绘制规则共同决定，动画本身不会普遍自动改变 `dispatchDraw()` 的遍历顺序。遇到遮挡变化时应先查层级和 Z，而不是只归因于动画。

1. **常规顺序**：父 View 先绘制自身背景，再按 ViewGroup 的子节点绘制顺序绘制子节点。
2. **自定义顺序**：调用 `setChildrenDrawingOrderEnabled(true)` 启用自定义顺序，并实现 `getChildDrawingOrder()` 返回每次绘制应使用的子节点索引。未启用时使用 ViewGroup 的常规子节点顺序。
3. **Z 顺序**：`elevation`、`translationZ` 等影响子 View 的前后层级。动画改变这些属性时，视觉遮挡也会变化。
4. **定位动态变化**：检查动画是否修改 Z/translationZ、父容器是否启用自定义顺序，以及运行期间是否新增或移除了子 View。`dispatchDraw()` 的行为需按具体父容器和 API 实现核对。

**Q11: 子 View 需要画到父容器外却被裁掉，`clipChildren` 与 `clipToPadding` 分别该怎么设？**

`clipChildren` 控制子 View 是否可绘制到父容器边界之外，`clipToPadding` 控制绘制是否被父容器 padding 区域裁剪。两者解决的是不同边界。

1. **越过父容器边界**：将相关父容器的 `clipChildren` 设为 `false`。若祖先 ViewGroup 仍裁剪子区域，需逐层检查祖先设置。
2. **绘制进 padding 区域**：将对应父容器的 `clipToPadding` 设为 `false`。
3. **默认与代价**：两者通常默认为 `true`。关闭裁剪会扩大需要绘制的区域，也可能影响滚动边缘效果和绘制开销。

只按实际需要关闭对应边界，不能把 `clipChildren` 与 `clipToPadding` 当成同一个开关。

**Q12: 自定义 View 滚动时持续掉帧，`onDraw()` 中分配对象会造成什么开销？**

`onDraw()` 是高频路径，反复创建绘制对象和临时数据会增加分配成本与堆水位。堆压力上升可能触发 GC。ART 并发 GC 的主要工作可与应用并行。启动和收尾仍可能出现停顿，足以挤占当帧预算。60 Hz 显示下单帧总预算约 16.7 ms，实际留给应用绘制的时间更少。

1. **确认相关性**：用 Memory Profiler allocation tracking 或 Perfetto 内存轨检查分配热点，再和掉帧时间对齐。同时确认主线程绘制耗时与 GC 停顿是否重合。
2. **预分配复用**：在构造或尺寸变化回调中创建 `Paint`、`Path`、`Rect` 等对象，在绘制中更新对象属性而非每帧替换。
3. **移出无关计算**：把与帧率无关的换算和字符串拼接移到数据变化时，只让绘制路径消费准备好的结果。
4. **缓存静态内容**：不常变化的复杂图形可按实测考虑 Bitmap 缓存或硬件图层，核算显存、上传和失效重建成本。

ART 可能优化某些未逃逸小对象，但不能假设热绘制路径的分配一定被消除。Android 性能指南也建议减少绘制回调中的分配。

**Q13: 复杂页面测量耗时高，深层 View 嵌套为什么会放大遍历成本？**

View 树越深、节点越多，递归测量和布局访问的节点越多。父子尺寸协商不稳定时，同一遍历还可能重复 measure/layout。复杂度来自整棵树的结构与重复工作，不只来自某一个深层节点。

1. **树深与节点数**：每层容器都要访问或测量子节点，深度和节点数都会增加遍历成本。
2. **重复测量**：权重、尺寸依赖或自定义布局策略可能让容器先测量再修正，造成额外遍历。
3. **优化方向**：保持层级浅，复杂布局可由自定义 ViewGroup 直接计算子节点位置，避免只为包装而增加容器层。
4. **多列页面边界**：车机大屏可能根据宽度生成更多列与节点，因此不能仅凭单一小屏的结果判断大屏性能。应对比实际节点数量和 measure/layout trace。

Android 布局性能文档建议减少不必要嵌套。是否值得自定义容器，应由测量数据证明。

**Q14: [learning] 旋转、透明度或阴影动画卡顿，View 的三种 Layer 类型该如何取舍？**

Layer 类型决定 View 绘制结果是否进入离屏缓存，选择依据是动画是否能复用该内容以及额外纹理成本是否低于重复录制成本。默认值是 `LAYER_TYPE_NONE`。

1. `LAYER_TYPE_NONE`：默认值。不为该 View 单独建立离屏 layer，绘制命令进入窗口绘制流程，适合内容变化频繁或没有合成复用收益的 View。
2. `LAYER_TYPE_HARDWARE`：在硬件加速下把 View 内容缓存为 GPU layer，适合内容基本不变而 alpha、rotation、圆角与阴影等效果频繁变化的场景。View 内容失效或尺寸变化时仍需重建，且会占用 GPU 内存与上传带宽。
3. `LAYER_TYPE_SOFTWARE`：以软件方式绘制到离屏内容，适用于硬件路径无法正确表达的效果或兼容场景，例如某些路径裁剪需按目标 API 验证。CPU 绘制可能变慢，且硬件加速窗口仍要把结果合成到最终帧。

先从 trace 确认瓶颈在 display list 录制、RenderThread、GPU 还是合成，再决定是否建 layer。凭感觉加硬件层可能把 CPU 开销转成显存和上传开销。

**Q15: 视频控件旋转后出现黑区或层级异常，`SurfaceView` 与 `TextureView` 应该怎么选？**

`SurfaceView` 把内容交给独立 Surface 与 SurfaceFlinger layer，`TextureView` 把 SurfaceTexture 内容作为纹理绘制进宿主窗口。需要独立合成与大面积视频时通常优先评估 SurfaceView。需要普通 View 变换、裁剪和混合时评估 TextureView。

1. **SurfaceView 的层级控制**：`setZOrderOnTop(true)` 把 Surface 放在应用窗口上方，普通 View 通常不能再盖住它。`setZOrderMediaOverlay(true)` 把 Surface 标为媒体 overlay，以控制多个 SurfaceView 之间的相对顺序。两个布尔值默认都为 `false`，省略时按宿主窗口和 SurfaceView 的常规层级工作。
2. **SurfaceView 的代价**：内容不进入父 View 的 display list，普通 View 的旋转、圆角等变换不会自动作用于它。Z-below 与宿主窗口合成时需要透明区域协调，`requestTransparentRegion()` 请求系统考虑该区域透明。
3. **TextureView 的能力与代价**：内容作为纹理进入正常 View 绘制，可使用父容器变换与裁剪。每次宿主绘制需要采样并合成输入纹理，增加 GPU 工作、功耗或延迟的可能性。
4. **选择前核对**：视频或全屏内容优先评估 SurfaceView。需要圆角、旋转或与 UI 动画联动时评估 TextureView。车机多屏还要核对 Surface 的目标显示与显示分配策略。

两种 View 的性能取舍受设备、格式、合成器与具体内容影响，应以目标设备 trace 和功耗数据验证。

**Q16: [learning] 只想移动一个 View 却触发复杂动画，`ViewPropertyAnimator` 与 `ObjectAnimator` 适用场景是什么？**

两者都属于属性动画。`ViewPropertyAnimator` 面向单个 View 的常见属性，调用更简洁。`ObjectAnimator` 可对任意对象的属性执行动画，控制能力更通用。

1. `ViewPropertyAnimator`：通过 `View.animate()` 对 View 的 alpha、translation、rotation 等属性开动画，适合简洁调用和同时驱动多个 View 属性。它从 API 12 起提供。多个属性一起动画时可合并失效请求，减少重复 invalidation。
2. `ObjectAnimator`：指定目标对象和属性，通过属性读取/写入驱动值变化，适合非 View 对象或需要对通用属性建动画的场景。属性动画框架从 API 11 起提供。复杂动画还要配置时长、插值器及必要的 evaluator。
3. **帧调度**：AnimationHandler 根据帧回调推进当前值，Android 实现将其接入 Choreographer 帧节奏。若应用错过帧截止时间，动画会跳过显示帧，不会自动延长该帧的显示时间。帧调度细节见 [渲染管线与 VSync 调度](../04-graphics/01-render-pipeline-vsync.md)。
4. **动画时长缩放**：`Settings.Global.ANIMATOR_DURATION_SCALE` 是 API 17 起提供的 Animator 动画时长倍率，会同时影响 start delay 与 duration。默认值为 `1.0`，`0.0` 会让动画立即结束。它是系统全局设置，不是逐 Activity 默认值。调试时确认目标系统当前设置，不能把动画被立即结束误判为绘制性能问题。

**Q17: [learning] 改变布局参数后界面瞬间跳变，`LayoutAnimation` 与 `TransitionManager` 哪个能动画化尺寸变化？**

要动画化布局边界变化，使用 `TransitionManager.beginDelayedTransition()` 配合 `ChangeBounds` 等 Transition。`android:layoutAnimation` 用于对子 View 的进场施加动画，不负责插值已有 View 的尺寸变化。

1. `LayoutAnimation`：对子 View 按容器配置的动画和延迟播放进场效果，适合列表或容器中子项出现时的视觉过渡。
2. `TransitionManager`：先记录起始状态，代码修改布局属性并触发布局后，再捕获结束状态，由 Transition 对差异做动画。
3. `ChangeBounds`：根据 View 的起止 bounds 过渡位置与尺寸。测量和布局会先计算新状态，动画阶段展示其间的过渡，不是每帧重新测量整棵树。

列表新增项目需要平滑改变容器高度时，使用包含 `ChangeBounds` 的 delayed transition。不要用 `layoutAnimation` 代替布局边界过渡。

**Q18: 自定义 View 重建后状态没恢复，为什么 `onSaveInstanceState()` 可能根本没被调用？**

View 层级状态按 View ID 保存。没有稳定 ID 的 View 通常不会进入层级状态保存/恢复映射，因此自定义状态保存回调可能不被调用。

1. **启用层级保存**：为需要恢复状态的 View 设置稳定且唯一的资源 ID，并确保没有关闭自动保存。
2. **保存自定义字段**：定义 `View.BaseSavedState` 子类，在 `onSaveInstanceState()` 中先调用父类实现，再把自定义字段写入保存对象。
3. **恢复自定义字段**：在 `onRestoreInstanceState(Parcelable)` 中识别自定义状态并恢复字段。普通父状态交还父类处理。
4. **关闭行为**：`setSaveEnabled(false)` 会关闭该 View 的自动状态保存。父容器需透传保存/恢复调用，ViewGroup 默认会递归处理子节点。

View.java 的 `mID != NO_ID` 判断说明无 ID View 不进入相应状态保存流程。

**Q19: [learning] `onCreate()` 中 `post()` 后能读到尺寸，但 `doOnPreDraw` 时机又不同，二者该怎么选？**

`post()` 只是把任务安排到 View 关联的消息队列，不承诺 View 已完成 layout。`doOnPreDraw` 在一次绘制前回调，适合读取已经计算好的布局尺寸，但也不代表像素已显示。

1. View 尚未 attach 时调用 `post()`：View 会先把任务保存在 `HandlerActionQueue`，attach 后再交给 Handler，因此通常不会丢失任务。
2. **读取首次布局尺寸**：用 `doOnPreDraw` 或 `OneShotPreDrawListener` 等待 layout 结束后的绘制前时点。回调可移除或只执行一次，避免每帧重复读取。
3. **只是延后任务**：可用 `post()`，但不要把它当作“layout 已完成”或“跨过一帧”的稳定契约。
4. **等待异步数据**：监听数据就绪事件，再更新界面。不要用反复 `post()` 轮询替代数据状态通知。

`View.java` 中 `post()` 与 HandlerActionQueue 的处理可用于核对 attach 前后的排队行为。

**Q20: `View.setTag()` 和 keyed tag 适合存什么，如何避免资源键错误与引用泄漏？**

无键 Tag 适合给一个 View 保存单个关联对象。keyed tag 适合由多个组件各自保存数据。Tag 与 View 生命周期绑定，不能当成无生命周期约束的业务仓库。

1. **无键 Tag**：`setTag(Object)` 给 View 绑定一个简单关联对象，通常只容纳一项约定数据。
2. **keyed tag**：`setTag(int, Object)` 允许用不同键保存多项元数据。键应是项目 `ids.xml` 中声明的资源 ID，避免和 framework 或其他组件的键冲突。
3. **引用生命周期**：只存生命周期不长于 View 的轻量数据。若对象持有 Activity、Context、监听器或大型对象图，而 View 被长期持有，就可能连带泄漏。
4. **避免错误用法**：不要用硬编码整数代替资源 ID，也不要用 `Map<View, …>` 或 Tag 替代职责清晰的数据模型。

**Q21: [learning] `LAYER_TYPE_SOFTWARE`、`LAYER_TYPE_HARDWARE` 与 `Canvas.saveLayer()` 三者最容易被怎样混淆，为什么 SurfaceFlinger 看不到独立 software View layer？**

三者作用于不同对象：View layer 控制 View 子树缓存方式，`Canvas.saveLayer()` 则要求当前 Canvas 创建离屏绘制目标。软件 View layer 生成的像素会合入宿主窗口缓冲区，因此 SurfaceFlinger 不会把它视为独立 layer。

1. `LAYER_TYPE_SOFTWARE`：将该 View 子树先栅格化为应用侧软件 Bitmap。AAOS 13 的 `View.buildLayer()` software 分支会调用 `buildDrawingCache(true)`。
2. `LAYER_TYPE_HARDWARE`：在硬件加速下为 View 子树建立 GPU 中间层。硬件加速关闭时会退化为 software layer 行为。
3. `Canvas.saveLayer()`：按当前 Canvas 创建离屏绘制区域。软件 Canvas 使用 CPU 像素存储，硬件 Canvas 使用 GPU render target，因此不能只凭 API 名推断实现路径。
4. **硬件加速查询**：Canvas 的 `isHardwareAccelerated()` 回答当前 Canvas 是否硬件加速。View 的同名方法回答所在窗口是否启用硬件加速。窗口启用硬件加速不保证任意 Canvas 都是硬件 Canvas。
5. **SurfaceFlinger 可见边界**：software layer Bitmap 与其他 View 内容合入宿主窗口 buffer，宿主 HWUI 帧再采样它。SurfaceFlinger 看到的是 App Window，不会看到一个独立 software View layer。
6. **成本与用途**：software layer 涉及 CPU 栅格化以及上传/采样成本。缓存可复用，频繁 invalidate 或尺寸变化则可能重复生成。把它当兼容或滤镜手段，不要当通用性能开关。trace 中应识别 CPU Bitmap 栅格化与宿主 HWUI 帧的组合，不依赖固定 slice 名。

**Q22: `SurfaceView` 的“独立”具体独立在哪里，带来哪些收益，又有哪些不保证？**

`SurfaceView` 独立的是主体像素生产与合成 layer：Producer 写入自己的 Surface、BufferQueue 与 SurfaceFlinger layer，不经宿主窗口 buffer。Activity 窗口转场期间，Surface layer 也可能与宿主窗口分别参与合成，但这不保证内容在 Activity 切换后继续存活。宿主主线程、RenderThread 和控制条等普通 View 仍按常规窗口链路工作。

1. **节奏独立**：内容层可以采用不同于宿主窗口的帧率或生产节奏。
2. **减少宿主采样**：视频或相机等大面积内容可不经宿主 RenderThread 采样，因设备与格式而可能减少 GPU 工作和带宽。
3. **单独评估合成**：SurfaceFlinger 可把内容 layer 交给 HWC 单独评估，满足设备条件时使用 DEVICE composition。
4. **不保证 overlay**：独立 layer 只意味着获得 HWC 单独评估机会，不保证 overlay plane、DEVICE 合成、低功耗或低延迟。全屏条件复杂时也可能使用 CLIENT composition。
5. **宿主卡顿边界**：主线程卡住时，已有 Surface 视频帧可能继续更新，但布局、裁剪、透明洞与控制条可能停在旧状态。

排查时分别确认内容是否继续生产、宿主几何是否更新、本轮采用何种合成，不能仅凭组件名称推断 overlay 或性能结论。

**Q23: [learning] Z-below 的 `SurfaceView` 为什么要在宿主窗口“挖洞”，`mDrawFinished` 置位后能证明什么、不能证明什么？**

Z-below 的 SurfaceView 位于宿主窗口下方。宿主窗口若在相同区域继续画不透明像素会遮住内容，因此 framework 让宿主 buffer 对应区域透明以便 SurfaceFlinger 合成下方 layer。`mDrawFinished` 只表示 framework 的 redraw 阶段完成，不证明 Producer 已提交或显示 buffer。

1. **打洞条件**：`gatherTransparentRegion()` 把 SurfaceView 可见矩形并入窗口透明区。绘制阶段在满足条件时通过 `Canvas.punchHole()` 清出矩形、圆角和 alpha 对应区域。AAOS 13 的 SurfaceView.java 可核对相关路径。
2. **打洞结果**：打洞不创建黑色 View，也不复制视频像素，只让 SurfaceFlinger 按 layer 层级合成宿主与内容。
3. **三个 SurfaceControl**：`mSurfaceControl` 是不承载像素的容器层，管理位置、裁剪和层级。`mBlastSurfaceControl` 承载 Producer buffer。`mBackgroundControl` 是下方纯色背景层。
4. **背景显示条件**：AAOS 13 的 `updateBackgroundVisibility()` 只在内容层位于宿主下方、Producer 声明内容不透明的 `OPAQUE` 标志且背景未被禁用时显示背景层。
5. `mDrawFinished` 边界：它表示 framework 认为 redraw 回调阶段结束，不证明 Producer 提交首块 buffer，更不证明 SurfaceFlinger latch 或 display present 已完成。
6. **排查错位**：container 的 position 更新不能证明 content child 已采用新 buffer。把几何事务与内容 buffer 放在同一时间轴检查。Z-above 不需要挖洞，但宿主普通 View 不能盖在内容层上方。

**Q24: `surfaceDestroyed()` 返回后 Producer 还在写旧 Surface 会怎样，正确的停止协议是什么？**

`surfaceDestroyed()` 返回后，渲染线程不得继续访问对应 Surface。若 Producer 在工作线程或远端服务，回调必须等待旧连接真正停止使用 Surface。只异步发送 stop 消息就返回，会造成回调顺序与生产线程状态不一致，可能引发崩溃、黑屏或新 Surface 首帧异常。

1. **区分生命周期状态**：Activity 可见、View 已 attach、底层 Surface 有效是三种不同状态。Producer 只能在 `surfaceCreated()` 到对应 `surfaceDestroyed()` 之间使用该 Surface。
2. **停止 Producer**：EGL/Vulkan 线程停止对旧 native window 的 swap/present。MediaCodec 或 Camera 撤销旧 output。由调用方负责确认这些 Producer 不再使用旧 Surface。
3. **同步完成再返回**：在 `surfaceDestroyed()` 中使用能等待 detach 完成的协议，确认旧 Surface 不再被使用后再返回。
4. **拆分首帧延迟**：依次测量 ViewRoot 就绪、SurfaceControl/BLAST 创建、callback、Producer connect、首个 buffer queue、layer 可见与 present。不要把整段时间都归给 `surfaceCreated()`。

新 Surface 的创建与旧 Producer 完全停止之间要有明确同步边界。

**Q25: TextureView 的一次可见更新要经过哪两套队列与哪些环节，为什么 Producer 已 queue 不代表画面已更新？**

TextureView 更新要经过输入 SurfaceTexture 的 BufferQueue 和宿主应用窗口的输出缓冲区。Producer 提交输入 buffer 只是第一步。还要等 ViewRoot traversal 与 RenderThread 消费它，再由宿主窗口进入 SurfaceFlinger/HWC 显示链路。

1. **输入队列**：外部 Producer 向 SurfaceTexture 对应的 BufferQueue 提交 buffer。
2. **通知宿主**：新 buffer 触发 `OnFrameAvailableListener`。AAOS 13 中 listener 绑定到 `mAttachInfo` 的 Handler，向 ViewRoot 所在线程投递更新并请求 invalidate。
3. **准备 layer 更新**：宿主 traversal 调用 `TextureView.draw()` 的 `applyUpdate()`，把 native updater 加入 RenderThread 的 pending layer updates。
4. **获取最新输入**：同步阶段 `DeferredLayerUpdater.apply()` 通过 `ASurfaceTexture_dequeueBuffer` 获取最新 AHardwareBuffer。实现可在 AAOS 13 的 DeferredLayerUpdater.cpp 与 TextureView.java 核对。
5. **生成并显示宿主帧**：RenderThread 把输入纹理绘制进新的 App Window buffer，再经 BLAST、SurfaceFlinger 与 HWC 显示。
6. **解释延迟**：消费时会选取最新帧并丢弃尚未消费的旧帧。frame-available 回调若晚于本轮 layer apply，宿主本帧仍可能使用旧输入，直到下一次 draw 才消费新内容。

把 frame-available、宿主 traversal、layer apply 与宿主 draw 对齐，才能判断新输入是否赶上目标宿主帧。

**Q26: TextureView 的额外成本是什么形态，什么场景不合适，所有权上有哪些易错入口？**

TextureView 的主要额外成本是宿主 RenderThread 采样输入纹理并再次生成 App Window buffer。它通常不是 CPU 逐像素 memcpy，也不固定增加一个刷新周期。回调赶上当前宿主帧时，额外等待可能小于一个周期，错过 layer apply 截止点时才需等后续宿主 draw。

1. **GPU 路径成本**：可能包括 image import、fence 等待、过滤缩放/旋转、颜色转换和宿主 render pass。是否形成瓶颈取决于格式、尺寸、设备与帧时序。
2. **适用场景**：需要普通 View 级旋转、裁剪、滤镜或父子透明混合时，可评估 TextureView。
3. **不适用场景**：要求每个输入帧都被处理或显示时，不应依赖只保留最新帧的路径。可选择 ImageReader 或 MediaCodec buffer 模式等有明确 acquire 语义的接口。
4. **硬件加速与合成边界**：TextureView 只能在硬件加速窗口显示。Producer 继续 queue 不能排除黑屏。SurfaceFlinger 看到最终宿主 layer，不能把 TextureView 矩形独立成 overlay。
5. **SurfaceTexture 所有权**：`onSurfaceTextureDestroyed()` 返回 `true` 时由 TextureView release。返回 `false` 时调用方接管并负责 release。
6. **连接与替换**：同一时刻只能连接一个 Producer。`setSurfaceTexture()` 会释放旧对象且不回调 `onSurfaceTextureDestroyed()`。调用前停止旧 Producer，并从 GL context detach 旧对象。

需要普通 View 混合时评估 TextureView，需要独立节奏、protected 内容或避免宿主采样时评估 SurfaceView。最终以目标设备数据验证。

**Q27: [learning] `GLSurfaceView` 的两种 render mode 节奏由什么决定，GLThread 独立后哪些责任没有被隔离？**

`RENDERMODE_CONTINUOUSLY` 持续触发绘制，`RENDERMODE_WHEN_DIRTY` 只在收到绘制请求后更新。独立 GLThread 隔离的是渲染执行队列，不会自动解决 Surface 生命周期、业务状态同步或 EGL/GPU 资源同步。

1. `RENDERMODE_CONTINUOUSLY`：Surface 与 EGL context 就绪后，GLThread 持续 draw/swap。帧节奏还受 swap interval、BufferQueue 背压、驱动、Swappy 或业务调度约束，默认不逐帧跟随 Choreographer。
2. `RENDERMODE_WHEN_DIRTY`：只在 `requestRender()` 置位后绘制，适合静态图表和事件驱动内容。漏发请求会停在旧帧，过晚请求会错过目标周期。若未显式设置，模式默认为 CONTINUOUSLY。
3. **Surface 生命周期**：`SurfaceHolder` 创建/销毁及 `onPause()` / `onResume()` 仍会要求 GLThread 停止或重建 EGL 资源。
4. **UI 状态同步**：Renderer 回调读取业务状态时需通过主线程交接或显式同步。`queueEvent()` 只把任务排进 GLThread，不会自动解决状态何时更新。
5. **显示资源恢复**：发生 `EGL_CONTEXT_LOST` 时要重建 context 与 surface，并在 `Renderer.onSurfaceCreated()` 中重建 GL 资源。
6. **共享 context**：多线程共享 context 加载资源时需显式同步 GL 资源可见性。Java 线程的先后不保证 GPU 已完成资源写入。

`WHEN_DIRTY` 要确保每次内容变化都调用 `requestRender()`。持续动画使用 `CONTINUOUSLY` 时仍需管理 pacing。跨线程状态经主线程或明确同步交接。
