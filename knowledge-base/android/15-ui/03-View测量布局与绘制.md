# View 测量、布局与绘制

> 学习资料（文章模式沉淀）。主线：measure/layout/draw 三趟的职责边界、MeasureSpec 与自定义 View 的尺寸契约、layout 与 onLayout、requestLayout 与 invalidate 的分工、硬件加速下的 display list 模型与"不 invalidate 就不重绘"、绘制顺序与裁剪、掉帧的结构性来源、图层类型与 Surface/TextureView 取舍、属性动画与布局动画的差别。AOSP 机制按本地 AAOS13 源码（Android 13）核对（`View.java`、`ViewRootImpl.java`、`Choreographer.java`、`libs/hwui/`），加速与绘制模型按官方文档口径（2026-09 检索），经验性结论标注社区口径。帧调度见 [../02-rendering/01-渲染管线与VSync调度.md](../02-rendering/01-渲染管线与VSync调度.md)，实践侧的渲染优化见 ../09-app-practice/06-渲染实战-View与Compose基础.md，掉帧度量方法见 ../07-performance/01-流畅性.md。Q 序列即结构，供 atlas 同源直读。

**Q1: measure、layout、draw 三趟各自解决什么？为什么必须先 measure 再 layout？**

measure 解决"每个 View 多大"，自顶向下把父容器给出的尺寸规格压进子树，每个 View 把自己的测量结果存起来；layout 解决"每个 View 放在哪"，自顶向下用 measure 的结果定死 `left/top/right/bottom`；draw 解决"怎么画出来"，按最终几何递归执行（View.java `onMeasure`、`resolveSizeAndState`、`dispatchDraw` 核对）。

必须先 measure 的原因是 layout 的输入正是 measure 的输出：父容器要知道子 View 的最终尺寸才能定位它，而子 View 的尺寸又依赖父容器给的规格——所以顺序只能是"先算尺寸，再摆位置"，两次都是自顶向下，第二趟才能拿到确定值。窗口首次显示时这三趟合在一次遍历里（`ViewRootImpl.performMeasure` → `performLayout` → `performDraw`，本地源码核对）。

**Q2: MeasureSpec 的 mode 与 size 是怎么组合的？resolveSizeAndState 按什么规则收敛？**

MeasureSpec 是一个打包成整数的"模式 + 尺寸"对，模式只有三种：EXACTLY（尺寸是硬要求，必须等于）、AT_MOST（尺寸是上限，可小于）、UNSPECIFIED（没有约束，尺寸由内容决定）。`resolveSizeAndState` 的收敛规则就是模式到尺寸的直接映射（View.java `resolveSizeAndState` 核对）：EXACTLY 取给定值、AT_MOST 取内容尺寸与上限的较小值、UNSPECIFIED 取内容尺寸。

`childMeasuredState` 参数的用途是让子 View 向上汇报"我被迫变小了"这类状态（`MEASURED_STATE_TOO_SMALL` 一类位），父容器可以据此做收缩处理——这是嵌套权重布局里子 View 反向影响父容器尺寸的正规通道。自定义 View 不调 `setMeasuredDimension` 时测量结果保持初始值，典型表现就是尺寸为 0 且不报错。

**Q3: 自定义 ViewGroup 时，为什么"父容器尺寸"和"子 View 尺寸"要分开算？**

因为父容器的尺寸约束来自自己的上级，而它自己的尺寸不必等于所有子 View 尺寸之和。带滚动的一类容器必须在"内容很大"和"父容器给定上限"之间做夹取：先按无约束测量内容，再按 EXACTLY/AT_MOST 收敛自身，否则会出现内容被压缩或父容器无限撑大的循环。

这也是为什么 onMeasure 里必须先决定自身尺寸、再决定子 View 的测量规格，顺序反了就会得到与父级约束矛盾的尺寸。这条纪律在车机上尤其重要：多屏之间的宽度差异会让同一个页面的自然宽度落在不同约束区间，测量顺序错误的后果会随屏幕而变（04 册讲资源与窗口尺寸的适配分歧）。

**Q4: layout() 和 onLayout() 有什么区别？自定义 ViewGroup 的责任边界在哪？**

`layout()` 是 `View` 的最终方法：它先由框架把 `mLeft/mTop/mRight/mBottom` 写好，再回调 `onLayout()`（View.java 核对）。`onLayout()` 只负责把已知尺寸的子 View 定位并写入它们自己的 `layout()`。父类 `View` 的 `onLayout` 是空实现，因为普通 View 没有子节点。

责任边界因此很清楚：框架负责"通知你这个 View 在哪"，`ViewGroup` 负责"决定子 View 在哪"。自定义 ViewGroup 里忘了调用子 View 的 `layout()`，子 View 就是"存在但不显示"；反之直接改子 View 的 `left/top` 而不走 `layout()`，其内部状态与绘制几何会不一致。

**Q5: requestLayout 和 invalidate 分别在什么时候必须调用？为什么改尺寸不能只 invalidate？**

分工由系统能力决定：`requestLayout()` 沿父链上溯，标记需要重新走 measure/layout，并最终排一次遍历；`invalidate()` 只把区域标记为脏，走重绘。按改动性质选：

- **尺寸或位置可能变**：加/减子 View、间距、文本长度触发重新换行、`setPadding` 一类影响 measure 的属性 → `requestLayout()`。
- **只影响内容不改几何**：颜色、背景、文字内容、位图替换 → `invalidate()`。
- **只影响阴影/描边轮廓**：改 `elevation` 相关阴影或自定义 outline → `invalidateOutline()`（View.java `invalidateOutline` 核对），因为绘制缓存与轮廓裁剪按 outline 维护，不重新计算就看不到变化。

只 `invalidate()` 而尺寸已经变了，绘制会按旧几何进行，画面看起来"没变化"或错位；只 `requestLayout()` 而内容变了，某些自定义 View 的实现不会重绘（`requestLayout` 本身不保证 `onDraw` 被调用），结果是旧画面残留。两者都影响时，优先 `requestLayout()`，因为遍历本身包含绘制。

**Q6: 硬件加速下"没调 invalidate 就不重绘"具体意味着什么？**

官方绘制模型分两套：软件绘制下，系统会把与脏区相交的 View 全部重画（这会隐藏 bug——你没改内容的 View 也被重画）；硬件加速下，系统把绘制命令录制成 display list，未被标记为脏的 View 直接重放上一份 display list（官方文档口径）。这带来一条硬规则：自定义 View 修改了绘制结果就必须显式 `invalidate()`，否则内容停在旧状态，哪怕内存里的数据已经变了。

典型踩坑是"改的是外部数据源而不是 View 属性"：属性 setter 通常自带 invalidate，而把数据放在一个可观察但非 View 属性的对象里就没有人替你标记脏区，需要自己调用。

**Q7: 脏区是怎么向上传播的？为什么"局部 invalidate"经常表现为整块重绘？**

`invalidate()` 会沿父链向上传播，把脏区换算到每层父容器的坐标系，直到 `ViewRootImpl.invalidateChild`（本地源码核对）汇总出屏幕脏区。软件绘制下系统重画所有与脏区相交的 View，视觉上像是"整块重绘"；硬件加速下系统只需重录被标记 View 的 display list、重放其余 display list，但重录的对象是整个 View 的绘制命令，不是脏区子集。

所以"局部 invalidate 省不了多少"是有依据的（社区经验口径）：它省的是兄弟 View 的重录与重放，不省被标记 View 自身的录制成本。要真正省成本，要么把频繁变化的部分拆成独立的小 View 隔离脏区，要么改用属性动画走合成层的位移而不是重录绘制命令。

**Q8: dispatchDraw 决定子 View 的绘制顺序，动画期间这个顺序怎么变？**

正常情况下 `dispatchDraw` 按子 View 在容器里的顺序先序绘制：父容器先画自身背景，再按子节点次序画子节点（View.java `dispatchDraw` 核对）。有属性动画在跑时，动画容器会改用 `getChildDrawingOrder` 提供的次序，让正在动的 View 画在正确的层位上——这是"正在动画的弹窗盖住其他控件"能工作的原因，但也意味着有动画时序相关的叠加问题（动画未结束时插入新 View，次序会突然变化）。

**Q9: clipChildren 和 clipToPadding 分别控制什么？**

`clipChildren` 控制子 View 是否可以被裁剪到父容器的边界内（false 时子 View 可以画出父容器范围，常用于圆角卡片、外发光、下拉刷新头部的溢出绘制）；`clipToPadding` 控制子 View 是否被裁到 padding 区域内——设了 padding 又希望内容画进 padding 时才需要它为 false。两者都是布局阶段就能确定的裁剪范围，改动它们必须触发重新布局，不是重绘。

**Q10: onDraw 里分配对象为什么会掉帧？**

因为分配发生在每帧，且会推高内存压力：分配本身要时间，分配到一定量会触发 GC，GC 期间主线程停顿。优化方向按收益排序是——预分配并复用（`Paint`、`Path`、`Rect` 等）、把与帧率无关的计算移出绘制路径、把静态内容缓存成位图或用硬件层。官方优化指南也把"`onDraw` 中消除分配"列为回报最高的一条（官方文档口径）。

**Q11: 层级嵌套过深为什么测量更贵？官方给的建议是什么？**

因为 measure 是自顶向下递归，深度与节点数都会放大遍历成本；一旦各层测量结果冲突，同一轮遍历还可能被重复执行。官方建议是保持层级尽量浅，复杂布局考虑自定义 ViewGroup 直接算子节点位置，避免逐层测量（官方文档口径）。这条建议在车机大屏上收益明显：一屏内塞几十层嵌套，在小屏上勉强可用的布局在大屏宽裕空间里往往反而更慢，因为大屏会走多列布局生成更多节点。

**Q12: 图层类型 LAYER_TYPE_NONE / HARDWARE / SOFTWARE 的代价与选择依据？**

NONE 是默认，绘制直接进视图自己的 display list，不额外分配离屏纹理。HARDWARE 把 View 渲染成一张硬件纹理再合成，适合频繁变形或做复杂效果（如 `alpha`、`rotation` 叠加、圆角 + 阴影），代价是显存占用与一次纹理上传。SOFTWARE 强制软件绘制，显存少但慢，只在硬件路径无法表达效果时才用（社区经验口径），例如某些路径裁剪操作。

代价要与掉帧归因对齐：先在 trace 里确认掉帧发生在录制、渲染线程还是合成，再决定是否用图层换效果；凭感觉加硬件层常常把问题从 CPU 搬到 GPU 显存与上传带宽。

**Q13: SurfaceView 和 TextureView 怎么选？为什么只有 SurfaceView 能调 z 序？**

`SurfaceView` 有独立 surface，内容不进 View 的 display list，合成时作为单独图层参与，因此可以调与父窗口的相对位置（`setZOrderOnTop`、`setZOrderMediaOverlay` 等），也能在 Activity 切换时脱离父视图被单独合成；代价是它不走视图变换（动画、圆角、旋转等受限）、需要与父层打洞协调（`requestTransparentRegion`）。`TextureView` 内容作为纹理参与正常绘制，享受完整视图变换与裁剪，代价是每帧要经 GPU 合成进父 surface，功耗与延迟更高（官方文档口径与社区经验口径）。

选择规则：视频与全屏沉浸式内容优先 `SurfaceView`；需要参与父容器变换、圆角或与界面动画联动的场景用 `TextureView`。车机多屏场景还要注意 `SurfaceView` 的独立 surface 与显示分配策略的配合。

**Q14: 属性动画靠什么驱动？ViewPropertyAnimator 和 ObjectAnimator 怎么选？**

属性动画由 `AnimationHandler` 接收回调后按时间推进计算当前帧值，而回调本身挂在 Choreographer 的帧回调上，所以动画步进与 VSync 对齐；这解释了为什么卡顿动画会"跳帧"而不是"变慢"（02-rendering/01 册）。`ViewPropertyAnimator` 直接对 View 的属性开动画，链式调用短、复用同一个动画对象，开销小；`ObjectAnimator` 面向任意对象与任意属性，需要自己指定插值器与时长，功能更通用。

一个版本相关的注意点：动画时长缩放（`animator_duration_scale`）在较新版本调整为按应用配置，不再是只面向开发者的全局开关，调试时"把动画调为零来验证是否卡在动画"的做法要按目标版本确认生效路径（官方文档口径）。

**Q15: LayoutAnimation 与 TransitionManager 是真正的布局动画吗？**

不是。它们分别是"子 View 出场时的延迟动画"和"布局属性变化时的一帧过渡效果"，两者都以属性动画的形式作用于已存在的 View，并不会让测量和布局重新计算（TransitionManager 会触发一次布局，然后对子 View 做动画）。真正的布局动画是 `TransitionManager.beginDelayedTransition` 搭配 `ChangeBounds` 之类，让 `requestLayout()` 造成的尺寸变化本身被插值。

这个区分决定优化方向：想让列表新增一项时高度平滑增长，`beginDelayedTransition` 配 `ChangeBounds` 才是正解；`android:layoutAnimation` 只能管"进场"效果，用它去平滑高度变化会得到跳变。
