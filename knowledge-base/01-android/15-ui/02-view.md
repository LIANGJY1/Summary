# View 测量、布局与绘制

> 学习资料（文章模式沉淀）。主线：measure/layout/draw 三趟的职责边界、MeasureSpec 与自定义 View 的尺寸契约、layout 与 onLayout、requestLayout 与 invalidate 的分工、硬件加速下的 display list 模型与"不 invalidate 就不重绘"、绘制顺序与裁剪、掉帧的结构性来源、图层类型与 Surface/TextureView 取舍、属性动画与布局动画的差别。AOSP 机制按本地 AAOS13 源码（Android 13）核对（View.java、ViewRootImpl.java、Choreographer.java、`libs/hwui/`），加速与绘制模型按官方文档口径（2026-09 检索），经验性结论标注社区口径。帧调度见 [../02-rendering/01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md)，实践侧的渲染优化见 [View 与 Compose 渲染实战](../09-app-practice/06-渲染实战-View与Compose基础.md)，掉帧度量方法见[流畅性度量](../07-performance/01-流畅性.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 自定义 View 首次显示时为什么要先 measure、再 layout、最后 draw？**

measure 解决"每个 View 多大"，自顶向下把父容器给出的尺寸规格压进子树，每个 View 把自己的测量结果存起来；layout 解决"每个 View 放在哪"，自顶向下用 measure 的结果定死 `left/top/right/bottom`；draw 解决"怎么画出来"，按最终几何递归执行（View.java onMeasure、resolveSizeAndState、dispatchDraw 核对）。

必须先 measure 的原因是 layout 的输入正是 measure 的输出：父容器要知道子 View 的最终尺寸才能定位它，而子 View 的尺寸又依赖父容器给的规格——所以顺序只能是"先算尺寸，再摆位置"，两次都是自顶向下，第二趟才能拿到确定值。窗口首次显示时这三趟合在一次遍历里（ViewRootImpl.performMeasure → performLayout → performDraw，本地源码核对）。

**Q2: 自定义 View 尺寸为 0 或被截断，MeasureSpec 的 mode 和 size 如何约束结果？**

MeasureSpec 是打包在整数中的"模式 + 尺寸"，三种模式对自定义 View 的约束不同：

1. **EXACTLY**：使用父容器给定的尺寸。
2. **AT_MOST**：内容尺寸不能超过父容器给定上限。
3. **UNSPECIFIED**：父容器不提供尺寸上限，由内容决定尺寸。

resolveSizeAndState 按这三种模式收敛结果（View.java 源码核对）。

childMeasuredState 参数的用途是让子 View 向上汇报"我被迫变小了"这类状态（MEASURED_STATE_TOO_SMALL 一类位），父容器可以据此做收缩处理——这是嵌套权重布局里子 View 反向影响父容器尺寸的正规通道。自定义 View 不调 setMeasuredDimension 时测量结果保持初始值，典型表现就是尺寸为 0 且不报错。

**Q3: 可滚动 ViewGroup 内容很长却自身尺寸异常，父尺寸与子尺寸应怎样分别计算？**

因为父容器的尺寸约束来自自己的上级，而它自己的尺寸不必等于所有子 View 尺寸之和。带滚动的一类容器必须在"内容很大"和"父容器给定上限"之间做夹取：先按无约束测量内容，再按 EXACTLY/AT_MOST 收敛自身，否则会出现内容被压缩或父容器无限撑大的循环。

这也是为什么 onMeasure 里必须先决定自身尺寸、再决定子 View 的测量规格，顺序反了就会得到与父级约束矛盾的尺寸。这条纪律在车机上尤其重要：多屏之间的宽度差异会让同一个页面的自然宽度落在不同约束区间，测量顺序错误的后果会随屏幕而变（资源限定符描述设备配置，窗口尺寸需要在运行时读取）。

**Q4: 自定义 ViewGroup 里的子 View 已创建却不显示，layout 与 onLayout 各负责什么？**

layout() 是 View 的最终方法：它先由框架把 `mLeft/mTop/mRight/mBottom` 写好，再回调 onLayout()（View.java 核对）。onLayout() 只负责把已知尺寸的子 View 定位并写入它们自己的 layout()。父类 View 的 onLayout 是空实现，因为普通 View 没有子节点。

责任边界因此很清楚：框架负责"通知你这个 View 在哪"，ViewGroup 负责"决定子 View 在哪"。自定义 ViewGroup 里忘了调用子 View 的 layout()，子 View 就是"存在但不显示"；反之直接改子 View 的 `left/top` 而不走 layout()，其内部状态与绘制几何会不一致。

**Q5: 内容更新后画面没变或位置错了，该调用 requestLayout 还是 invalidate？**

分工由系统能力决定：requestLayout() 沿父链上溯，标记需要重新走 measure/layout，并最终排一次遍历；invalidate() 只把区域标记为脏，走重绘。按改动性质选：

1. **尺寸或位置可能变**：加/减子 View、间距、文本长度触发重新换行、setPadding 一类影响 measure 的属性 → requestLayout()。
2. **只影响内容不改几何**：颜色、背景、文字内容、位图替换 → invalidate()。
3. **只影响阴影/描边轮廓**：改 elevation 相关阴影或自定义 outline → invalidateOutline()（View.java invalidateOutline 核对），因为绘制缓存与轮廓裁剪按 outline 维护，不重新计算就看不到变化。

只 invalidate() 而尺寸已经变了，绘制会按旧几何进行，画面看起来"没变化"或错位；只 requestLayout() 而内容变了，某些自定义 View 的实现不会重绘（requestLayout 本身不保证 onDraw 被调用），结果是旧画面残留。两者都影响时，优先 requestLayout()，因为遍历本身包含绘制。

**Q6: 硬件加速后修改绘制数据却仍显示旧内容，为什么必须显式 invalidate？**

官方绘制模型分两套：软件绘制下，系统会把与脏区相交的 View 全部重画（这会隐藏 bug——你没改内容的 View 也被重画）；硬件加速下，系统把绘制命令录制成 display list，未被标记为脏的 View 直接重放上一份 display list（官方文档口径）。这带来一条硬规则：自定义 View 修改了绘制结果就必须显式 invalidate()，否则内容停在旧状态，哪怕内存里的数据已经变了。

典型踩坑是"改的是外部数据源而不是 View 属性"：属性 setter 通常自带 invalidate，而把数据放在一个可观察但非 View 属性的对象里就没有人替你标记脏区，需要自己调用。

**Q7: 只 invalidate 一个小 View 却感觉整块区域重绘，脏区实际如何传播？**

invalidate() 会沿父链向上传播，把脏区换算到每层父容器的坐标系，直到 ViewRootImpl.invalidateChild（本地源码核对）汇总出屏幕脏区。软件绘制下系统重画所有与脏区相交的 View，视觉上像是"整块重绘"；硬件加速下系统只需重录被标记 View 的 display list、重放其余 display list，但重录的对象是整个 View 的绘制命令，不是脏区子集。

所以"局部 invalidate 省不了多少"是有依据的（社区经验口径）：它省的是兄弟 View 的重录与重放，不省被标记 View 自身的录制成本。要真正省成本，要么把频繁变化的部分拆成独立的小 View 隔离脏区，要么改用属性动画走合成层的位移而不是重录绘制命令。

**Q8: 动画中的 View 突然盖住兄弟控件，dispatchDraw 的绘制顺序何时会变化？**

正常情况下 dispatchDraw 按子 View 在容器里的顺序先序绘制：父容器先画自身背景，再按子节点次序画子节点（View.java dispatchDraw 核对）。有属性动画在跑时，动画容器会改用 getChildDrawingOrder 提供的次序，让正在动的 View 画在正确的层位上——这是"正在动画的弹窗盖住其他控件"能工作的原因，但也意味着有动画时序相关的叠加问题（动画未结束时插入新 View，次序会突然变化）。

**Q9: 子 View 需要画到父容器外却被裁掉，clipChildren 与 clipToPadding 分别该怎么设？**

这两个属性控制不同的裁剪边界：

1. **clipChildren**：控制子 View 是否可以绘制到父容器范围之外。
2. **clipToPadding**：控制子 View 是否可以绘制到父容器的 padding 区域。

父容器设置了 padding 且内容需要绘制进该区域时，应检查 clipToPadding。

**Q10: 自定义 View 滚动时持续掉帧，onDraw 中分配对象会造成什么开销？**

因为绘制以帧周期运行：60 Hz 下每帧预算只有约 16.7 ms，onDraw 里每次 new Paint()/new Rect() 既要占用分配时间，又会持续推高堆水位——堆涨到阈值触发 GC，即使 ART 的并发 GC 大部分工作与主线程并行，GC 的起始与收尾仍有停顿片段，一次停顿就足以挤掉当帧甚至连续数帧（官方性能指南把"消除 onDraw 内分配"列为回报最高的优化之一，官方文档口径）。诊断：Memory Profiler 的 allocation tracking 或 Perfetto 内存轨，确认分配点与掉帧时间是否重合。

优化按收益排序：**预分配并复用**——Paint、Path、Rect 等在构造器或尺寸变化回调里创建一次，绘制路径只改属性不换对象；**移出无关计算**——与帧率无关的换算、字符串拼接放到数据变更时做，绘制路径只消费现成结果；**静态内容缓存**——不常变的复杂图形缓存成 Bitmap 或用 LAYER_TYPE_HARDWARE 让 GPU 复用。边界提醒：方法内未逃逸的小对象在 ART 上有栈上分配优化，但绘制路径是热路径，不要赌编译器优化，稳定路径不分配仍是纪律。

**Q11: 复杂页面测量耗时高，深层 View 嵌套为什么会放大遍历成本？**

因为 measure 是自顶向下递归，深度与节点数都会放大遍历成本；一旦各层测量结果冲突，同一轮遍历还可能被重复执行。官方建议是保持层级尽量浅，复杂布局考虑自定义 ViewGroup 直接算子节点位置，避免逐层测量（官方文档口径）。这条建议在车机大屏上收益明显：一屏内塞几十层嵌套，在小屏上勉强可用的布局在大屏宽裕空间里往往反而更慢，因为大屏会走多列布局生成更多节点。

**Q12: 旋转、透明度或阴影动画卡顿，View 的三种 Layer 类型该如何取舍？**

NONE 是默认，绘制直接进视图自己的 display list，不额外分配离屏纹理。HARDWARE 把 View 渲染成一张硬件纹理再合成，适合频繁变形或做复杂效果（如 alpha、rotation 叠加、圆角 + 阴影），代价是显存占用与一次纹理上传。SOFTWARE 强制软件绘制，显存少但慢，只在硬件路径无法表达效果时才用（社区经验口径），例如某些路径裁剪操作。

代价要与掉帧归因对齐：先在 trace 里确认掉帧发生在录制、渲染线程还是合成，再决定是否用图层换效果；凭感觉加硬件层常常把问题从 CPU 搬到 GPU 显存与上传带宽。

**Q13: 视频控件旋转后出现黑区或层级异常，SurfaceView 与 TextureView 应该怎么选？**

SurfaceView 有独立 surface，内容不进 View 的 display list，合成时作为单独图层参与，因此可以调与父窗口的相对位置（setZOrderOnTop、setZOrderMediaOverlay 等），也能在 Activity 切换时脱离父视图被单独合成；代价是它不走视图变换（动画、圆角、旋转等受限）、需要与父层打洞协调（requestTransparentRegion）。TextureView 内容作为纹理参与正常绘制，享受完整视图变换与裁剪，代价是每帧要经 GPU 合成进父 surface，功耗与延迟更高（官方文档口径与社区经验口径）。

选择规则：视频与全屏沉浸式内容优先 SurfaceView；需要参与父容器变换、圆角或与界面动画联动的场景用 TextureView。车机多屏场景还要注意 SurfaceView 的独立 surface 与显示分配策略的配合。

**Q14: 只想移动一个 View 却触发复杂动画，ViewPropertyAnimator 与 ObjectAnimator 适用场景是什么？**

属性动画由 AnimationHandler 接收回调后按时间推进计算当前帧值，而回调本身挂在 Choreographer 的帧回调上，所以动画步进与 VSync 对齐；这解释了为什么卡顿动画会"跳帧"而不是"变慢"（帧调度细节见 [渲染管线与 VSync 调度](../02-rendering/01-渲染管线与VSync调度.md)）。ViewPropertyAnimator 直接对 View 的属性开动画，链式调用短、复用同一个动画对象，开销小；ObjectAnimator 面向任意对象与任意属性，需要自己指定插值器与时长，功能更通用。

一个版本相关的注意点：动画时长缩放（animator_duration_scale）在较新版本调整为按应用配置；调试时把动画设为零以确认卡顿是否来自动画，应先核对目标系统版本的生效方式（官方文档口径）。

**Q15: 改变布局参数后界面瞬间跳变，LayoutAnimation 与 TransitionManager 哪个能动画化尺寸变化？**

不是。它们分别是"子 View 出场时的延迟动画"和"布局属性变化时的一帧过渡效果"，两者都以属性动画的形式作用于已存在的 View，并不会让测量和布局重新计算（TransitionManager 会触发一次布局，然后对子 View 做动画）。真正的布局动画是 TransitionManager.beginDelayedTransition 搭配 ChangeBounds 之类，让 requestLayout() 造成的尺寸变化本身被插值。

列表新增项目时若要让高度平滑增长，应使用 beginDelayedTransition 配合 ChangeBounds；android:layoutAnimation 只处理子 View 的进场效果，不能代替布局边界变化动画。

**Q16: 自定义 View 重建后状态没恢复，为什么 onSaveInstanceState 可能根本没被调用？**

框架按 View 的 id 保存和恢复层级状态；没有 id 的自定义 View 不会收到对应的保存调用（View.java 中 mID != NO_ID 的判定核对）。

保存自定义字段时使用 View.BaseSavedState：

1. **保存**：定义 BaseSavedState 子类；在 onSaveInstanceState() 中调用父类实现，再保存自定义字段。
2. **恢复**：在 onRestoreInstanceState(Parcelable) 中读取自定义状态，并将父状态交还父类。

父容器需要透传保存调用，ViewGroup 默认会执行；setSaveEnabled(false) 可以关闭单个 View 的自动保存。确保为需要恢复状态的 View 设置稳定 id。

**Q17: onCreate 中 post 后能读到尺寸，但 doOnPreDraw 时机又不同，二者该怎么选？**

View 尚未 attach 到窗口时，post() 会先把任务存入 HandlerActionQueue，等 attach 后再交给 Handler（View.java 源码核对）。因此在 onCreate 调用 post() 通常不会丢失任务；但它的执行时机依赖 attach 和消息队列，不适合作为“布局已完成”的明确契约。

按需要的时机选择：

1. **布局完成后读取一次尺寸**：使用 doOnPreDraw 或 OneShotPreDrawListener，语义明确且可取消。
2. **只是延后一次任务**：可用 post()，但不要假设它适用于跨帧等待数据。
3. **等待数据到达**：监听数据就绪事件，再更新界面，不要反复 post() 轮询。

**Q18: Android View 动画、帧动画和属性动画分别改变什么？**

View 动画通过变换绘制结果来表现平移、缩放、旋转或透明度变化；帧动画按顺序播放一组 Drawable；属性动画则随时间更新对象的实际属性值。选择取决于目标：播放图像序列用帧动画，只改变绘制外观可用 View 动画，需要属性值真实变化或动画非 View 对象时用属性动画。

旧式 View 动画只改变绘制变换，不一定改变 View 的布局位置或触摸命中区域；属性动画更新对象属性，因而更通用，但每帧更新仍有渲染成本。Android 属性动画框架从 API 11 起提供。

**Q19: Android View 与 ViewGroup 分别表示什么？**

View 是界面树中可参与测量、布局、绘制和输入处理的基础节点；ViewGroup 是 View 的容器子类，除自身视图职责外还负责测量与布局子 View，并可参与事件分发。按钮、文本和自定义绘制控件通常是叶子 View，线性布局等则是 ViewGroup。

具体控件能否滚动、聚焦或响应点击取决于其实现与状态，不能仅由“它是一个 View”推断。
