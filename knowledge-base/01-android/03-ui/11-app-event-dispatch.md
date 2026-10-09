# 应用层事件分发：方法链、返回值语义与多点触控

> 学习资料（文章模式沉淀）。主线：一次触摸在 Activity → Window → DecorView → ViewGroup → View 的方法调用链与各方法返回值语义、onTouch/onTouchEvent/onClick/onLongClick 的触发时序与互斥关系、onInterceptTouchEvent 的调用时机、多指场景的 action 编码与 pointer id/index、触摸拆分、滑动冲突的两类拦截策略、scrollTo 滑动模型、ACTION_CANCEL 的清理义务、触摸遮挡过滤、滚轮悬停等通用运动事件、软键盘文本的 InputConnection 路径、返回键默认行为与"点击无效"排查清单。机制按本地 AAOS13 源码（Android 13，`frameworks/base/core/java/android/view/`）核对。输入全链路总览见 [10-input-system.md](./10-input-system.md)；系统侧焦点与触摸目标裁决见 [14-focus-multi-display.md](./14-focus-multi-display.md)，车机按键与旋钮的系统侧链路见 [15-aaos-input.md](./15-aaos-input.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 一次点击在应用内的完整方法调用链是什么？Activity、Window、DecorView、ViewGroup、View 各自负责什么？**

调用链从 `DecorView` 进入：`ViewRootImpl` 把事件交给 `DecorView.dispatchTouchEvent()`，它先转发给 `Window.Callback`（通常就是 Activity，即进入 `Activity.dispatchTouchEvent()`），Activity 再经 `Window.superDispatchTouchEvent()` 交回 DecorView，随后沿 `ViewGroup.dispatchTouchEvent()` 逐层递归到命中的叶子 `View.dispatchTouchEvent()`（按 AAOS13 源码核对，`DecorView.java` 的回调转发是链路第一站）。Activity 在入口做两件事：`ACTION_DOWN` 时回调 `onUserInteraction()`（用于屏保、息屏计时这类"用户活跃"探测），随后把事件交给窗口；窗口返回 false 表示内部无人消费，才回落到 `Activity.onTouchEvent()`。按 AAOS13 源码核对（`app/Activity.java`），这个顺序是固定的：`onUserInteraction` 只在 `DOWN` 调用，窗口消费则 Activity 的兜底不再执行。

各层职责不同：

1. **Activity**：入口与兜底，持有 Window；不参与命中测试。
2. **PhoneWindow/DecorView**：把 View 树接进窗口；`DecorView.dispatchTouchEvent` 先转发给 `Window.Callback`（即 Activity），Activity 经 `superDispatchTouchEvent` 交回后，才进入 DecorView 作为 ViewGroup 的分发实现。
3. **ViewGroup**：命中测试与递归分发——判断事件坐标落在哪个孩子上、维护 TouchTarget 链、执行拦截（`onInterceptTouchEvent`）、按需把多点拆分给不同孩子。
4. **View**：消费决策——按 `onTouchListener`、`onTouchEvent` 的顺序询问自己是否处理。

排查"事件没到自定义 View"时，按这条链从上往下打日志即可定位断在哪一层：Activity 收到了而 ViewGroup 没递归到，通常是命中测试或拦截问题；ViewGroup 收到了而叶子没收到，通常是孩子不可点击、`dispatchTouchEvent` 返回 false 或坐标不在孩子边界内。

**Q2: [learning] onTouchListener 与 onTouchEvent 谁先执行？为什么 View 被 disable 后 onTouch 也收不到事件？**

`View.dispatchTouchEvent()` 的实现顺序是：先查 `ListenerInfo.mOnTouchListener`，同时满足三个条件——监听器非空、View 处于 `ENABLED` 状态、`onTouch()` 返回 true——事件即被消费，`onTouchEvent()` 根本不会被调用；只有 `onTouch()` 返回 false 或条件不满足，才回落到 `onTouchEvent()`。按 AAOS13 源码核对（`View.java` 的 `dispatchTouchEvent`），三个条件缺一不可，是"与"的关系。

disable 后收不到 `onTouch` 是因为 enabled 是监听器短路条件的一部分：源码在调用 `onTouch()` 前显式检查 `(mViewFlags & ENABLED_MASK) == ENABLED`。这是有意设计——禁用的控件不应响应任何触摸；`onTouchEvent()` 内部同样以 enabled 为前提处理按压态。

由此得出两个使用规则：想完全接管一个控件的事件序列（比如自绘按压反馈），在 `onTouch` 里返回 true 即可让默认点击逻辑失效；只想"观察"而不打断默认行为，就让 `onTouch` 返回 false，事件继续走 `onTouchEvent`。用 `onTouch` 返回 true 之后再奇怪"为什么 onClick 不触发了"，就是短路机制的直接结果。

**Q3: [learning] 为什么 ACTION_DOWN 不返回 true，后续的 MOVE/UP 就都收不到了？**

因为整个手势的目标是在 `ACTION_DOWN` 时确定的。ViewGroup 在 `DOWN` 分发时遍历孩子做命中测试，只有孩子的 `dispatchTouchEvent` 对这条 `DOWN` 返回 true，ViewGroup 才把它记为 TouchTarget；`DOWN` 返回 false 的孩子不会进入目标链，后续 `MOVE`/`UP` 只沿着目标链分发，不再询问没入链的孩子。 ViewGroup 自己也一样：如果没有任何孩子消费 `DOWN`，事件由 ViewGroup 的 `onTouchEvent` 处理；一旦结果是"没人消费"，这条手势对整个子树就结束了。

按 AAOS13 源码核对（`ViewGroup.java` 的 `dispatchTouchEvent`），还有一条容易读漏的规则：手势进行中若目标链为空且事件不是 `DOWN`（例如唯一目标被移除后），ViewGroup 不再询问任何人，直接视为拦截、自己消化这条事件——不会把进行中的手势随机交给别的孩子。这条兜底与"`DOWN` 决定目标"是同一份分发实现的两面。

推论有两个：判断一个 View"能不能收到完整手势"，只看它对 `DOWN` 的返回值；想让某个控件在按下瞬间就锁定整条手势（如滑动容器），必须保证 `DOWN` 返回 true，哪怕此刻还不知道用户想不想滑。

**Q4: [learning] onClick 在哪个 action 里触发？onTouch 返回 true 后 onClick 还会执行吗？**

`onClick` 在 `ACTION_UP` 阶段触发：`View.onTouchEvent()` 处理 `UP` 时，若 View 处于按压态且长按没有消费事件，会把一个 `PerformClick` 任务 `post` 到消息队列执行（按 AAOS13 源码核对，`View.java` 的 `UP` 分支 `if (!post(mPerformClick)) performClickInternal()`）。用 `post` 而非同步调用，是为了让整个分发过程先完成；因此 `onClick` 实际执行时机略晚于 `onTouch(UP)` 返回。

`onTouch` 返回 true 后 `onClick` 不会执行，原因是短路：`onTouch` 返回 true 意味着 `onTouchEvent()` 这次没被调用，而点击的判定状态机（按压态、`UP` 分支里的 `PerformClick`）都在 `onTouchEvent()` 里。想让点击仍然生效，标准做法是在 `onTouch` 返回 true 的同时自己维护按压态并手动触发逻辑，或者只在需要观察的 action 返回 false。

还有一个隐含条件：`UP` 到达时手指必须还在"可点击"的位置。`MOVE` 阶段滑出控件边界会移除按压态与长按回调，之后即使手指回到控件内再抬起，也只有一个不触发点击的 `UP`。这就是"按下后稍微拖动再松手，按钮没响应"的机制解释。

**Q5: [learning] 长按的计时与取消由谁驱动？onLongClick 返回 false 时为什么 UP 还能触发 onClick？**

长按用消息队列计时，不占线程：`DOWN` 后 View 先 `postDelayed` 一个 `CheckForTap`（到达点击超时后进入预按压态，平台后备值 100 ms 量级），再 `postDelayed` 一个 `CheckForLongPress`（到达长按超时执行）；超时值运行时从系统读取，长按默认 400 ms 且可被 `Settings.Secure.LONG_PRESS_TIMEOUT` 调整，"固定 400 ms"的说法不成立。`MOVE` 越出 TouchSlop、第二根手指按下、`CANCEL`，都会 `removeCallbacks` 取消计时（按 AAOS13 源码核对，`View.java` 的回调注册与移除路径）。

`CheckForLongPress` 执行时调用 `performLongClick()`，把返回值记入 `mHasPerformedLongPress`：返回 true 表示长按消费了这条手势，后续 `UP` 分支看到该标记就不再 post `PerformClick`；返回 false 表示长按没消费，`UP` 时按压态仍在，click 照常触发——所以"onLongClick 返回 false 会让长按和点击都执行"是设计行为而不是 bug。

要在长按后阻止 click 又不想走返回值语义，可以在 `onLongClick` 里返回 true；反之，如果控件既要有长按菜单、又允许长按后拖动（如桌面图标），需要在长按回调里自行接管后续事件流，因为长按消费后 View 树对 `UP` 的默认处理已经跳过了 click。

**Q6: [learning] 一次手势里 onInterceptTouchEvent 会被调用多少次？什么情况下一次都不会被调用？**

满足"事件是 `ACTION_DOWN` 或已存在触摸目标"且子 View 没有设置 `disallowIntercept` 标志时，每条事件分发前都会调用一次 `onInterceptTouchEvent`，返回 true 即拦截、目标孩子收到 `ACTION_CANCEL`、之后事件交给本容器。按 AAOS13 源码核对（`ViewGroup.java`），判定顺序是：先查 `FLAG_DISALLOW_INTERCEPT`，标志置位时连 `onInterceptTouchEvent` 都不会调用，直接按不拦截处理。

一次都不会被调用的情况有两种：

1. **无目标且非 DOWN**：手势进行中原目标消失后，容器直接按"拦截"处理、自己消化这条事件，绕过询问；
2. **disallow 置位期间**：子 View 调用 `parent.requestDisallowInterceptTouchEvent(true)` 后，直到手势结束或新 `DOWN` 重置标志前，容器失去拦截机会。

由此得出实践结论：拦截判断必须做到"每条事件都能快速给出相同答案"，因为 `MOVE` 阶段它会被反复调用；想在拦截里做耗时计算（如 ML 手势分类）要先在容器侧缓存资格判定。`onInterceptTouchEvent` 的默认实现直接返回 false，所以不重写它时容器从不拦截，这也是普通布局不干扰孩子手势的原因。

**Q7: [learning] TextView 和 Button 都没设 listener，为什么 Button 有按压反馈而 TextView 点了没反应？**

默认可点击性不同：Button 的样式把 `android:clickable` 设为 true，TextView 默认是 false。`View.onTouchEvent()` 只有在 clickable、longClickable、contextClickable 至少一个为 true 时才进入处理并返回 true；不可点击的 TextView 对 `DOWN` 返回 false，目标链里没有它，后续事件也不会来，按压态、点击逻辑全都不会启动。调用 `setOnClickListener()` 时，View 会自动把 clickable 置 true（按 AAOS13 源码核对，`View.setOnClickListener` 内部 `if (!isClickable()) setClickable(true)`），这就是"给 TextView 设了 listener 就能点了"的机制。

按压反馈同样来自 `onTouchEvent`：clickable 的 View 在 `DOWN`/预按压时 `setPressed(true)`，背景 drawable 的 pressed 状态随之切换。所以没有按压反馈通常不是"反馈丢了"，而是事件根本没被消费。

引申到自定义控件：只重写 `onTouchEvent` 处理手势的控件，记得保证自己 clickable（构造器里 `setClickable(true)`），否则 `DOWN` 返回 false 会让整个手势失效；只想监听而不消费时，用 `onTouchListener` 返回 false 的形式，不影响默认行为。

**Q8: [learning] 多指按下时 getAction() 和 getActionMasked() 有什么区别？action 值里的 index 怎么解读？**

`getAction()` 返回原始编码，`getActionMasked()` 把高位掩掉、只留动作类型。多指的按下与抬起（`ACTION_POINTER_DOWN`/`ACTION_POINTER_UP`）用低 8 位存动作、高 8 位存"这条动作涉及的指针在当前指针数组里的下标"，掩码是 `ACTION_POINTER_INDEX_MASK = 0xff00`，移位是 `ACTION_POINTER_INDEX_SHIFT = 8`。所以原始 action 值 `0x00000005` 表示 `ACTION_POINTER_DOWN`（0x5）且 index 为 0，`0x000105` 是 index 为 1 的第二根手指按下。单指动作（`DOWN`/`UP`/`MOVE`/`CANCEL`）不携带 index。

取"这条动作指向的手指"必须用配套 API：`getActionIndex()` 内部就是解这个高位。常见错误是拿 `getAction()` 直接与 `ACTION_POINTER_DOWN` 用 `==` 比较——带 index 的原始值永远不等于裸常量，第二根手指按下时会静默失配；正确写法是先 `getActionMasked()` 再比较。

`MOVE` 事件不带 index：一次移动汇报全部活动指针，逐个用 `findPointerIndex(pointerId)` 换成下标后取坐标。这也是为什么多指跟踪不能依赖 action 携带的信息，而要靠跨手势稳定的 pointer id。

**Q9: [learning] pointer id 和 pointer index 有什么区别？为什么滑动手势必须用 pointer id 跟踪手指？**

pointer index 是指针在当前 `MotionEvent` 指针数组里的位置，会随手指按下、抬起不断前移；pointer id 是系统为每根从按下到抬起之间保持不变的编号。一根手指抬起后，排在它后面的指针 index 全部减一，而 id 不变——用 index 记"这是第一根手指"在松开任意一根手指后就会指错人，用 id 则始终正确。

所以跟踪手势的正确模式是：`DOWN`/`ACTION_POINTER_DOWN` 时用 `getPointerId(actionIndex)` 把 id 记下来；处理 `MOVE` 时先 `findPointerIndex(savedId)` 把 id 换回当前 index，返回负数（`-1`）说明这根手指已经抬起，跳过即可；判断特定手指是否还在手势里，用 `event.findPointerIndex(id) >= 0` 或 id 位集判断。`VelocityTracker`、`GestureDetector` 的取值 API 全部以 id 为参数，理由相同。

按 AAOS13 源码核对，`MotionEvent` 内部用 `PointerProperties`（含 id 与工具类型）和 `PointerCoords` 两个数组平行存储，id 是跨事件的稳定键、index 只是本次事件的数组下标——"index 和 id 混用导致双指手势错乱"是多点触控 bug 的第一大来源。

**Q10: [learning] getX() 与 getRawX() 有什么区别？多点触控时分别怎么取？**

`getX()` 是相对当前 View 左上角的坐标，`getRawX()` 是相对屏幕（严格说是窗口所在屏幕）的坐标。`getX()` 在事件向 View 树逐层分发时被重写：ViewGroup 命中孩子后把坐标减去孩子的 `left`/`top`（再经矩阵变换）再下发，所以每个 View 看到的都是"以自己为原点"的值；`getRawX()` 保持原始屏幕值不变，跨层一致。

多点时两者都有带 index 的重载：`getX(pointerIndex)` 取指定指针的 View 坐标；`getRawX(pointerIndex)` 自 API 29 起可用，旧版本只能拿 `getRawX()`（等价 index 0）。无参调用取的都是 index 0 的指针——处理 `ACTION_POINTER_DOWN` 时如果直接 `getX()`，拿到的不一定是刚按下的那根，应该用 `getX(getActionIndex())`。

使用场景区分：判断"手指落在自己内部"、实现拖动跟手，用 `getX()`；与 Toast/弹窗定位、跨 View 比较轨迹、与 `dumpsys` 或系统日志里的坐标对齐时用 `getRawX()`。两个坐标混用于同一份阈值判断（比如把 View 内坐标和屏幕坐标做减法）是常见的"位移总是不对"的根因。

**Q11: [learning] 两根手指按在同一个 ViewGroup 的不同孩子上，事件怎么分发？什么情况下不拆分？**

默认按指针拆分（split motion events）：ViewGroup 构造时置 `FLAG_SPLIT_MOTION_EVENTS`，`ACTION_POINTER_DOWN` 时只把新指针命中测试到的孩子加入目标链，之后每个孩子只收到属于自己那部分指针的子事件——`MotionEvent` 里只含自己的 pointer id，action 也按子集重算。按 AAOS13 源码核对（`ViewGroup.java`），拆分条件是标志置位且事件来源不是鼠标（`SOURCE_MOUSE` 事件始终整条给一个目标）；标志可用 `setSplitMotionEvents(false)` 关闭。

不拆分时（标志关闭），第二根手指按下后整条事件继续交给已有目标链的第一个目标，`DOWN` 消费者收到所有指针；这保留了"双指手势必须发生在同一个控件"的旧行为，一些自定义双指缩放控件反而依赖它。

子事件边界要特别注意：手指 A 抬起（`ACTION_POINTER_UP`）时，两个目标各自收到自己视角的抬起；而某根手指的移动导致它"划出"原孩子的边界，不会发生目标转移——触摸目标是按 `DOWN`/`POINTER_DOWN` 时刻锁定的，后续只有 `CANCEL` 能终止。这解释了"双指分别按在两个列表上，滑动时只有一个列表在动"的正常现象。

**Q12: [learning] 滑动冲突的经典场景有哪几类？外部拦截和内部拦截分别怎么做？**

场景按"谁想消费同一段位移"分三类：**方向不一致**——外层横向滑动容器套内层纵向列表（或相反），一段位移双方都想响应；**方向一致**——嵌套的同向滚动（外层 RecyclerView 套内层 RecyclerView），要决定"滚内层还是滚外层"；**特殊手势叠加**——下拉刷新、侧滑删除这类上层手势与普通滚动的叠加。三类问题的本质相同：`DOWN` 后目标锁死，必须在 `MOVE` 中重新裁决事件归属。

两种拦截策略的做法：

1. **外部拦截**：父容器重写 `onInterceptTouchEvent()`，从 `DOWN` 起累计位移，超过 TouchSlop 后按方向（比较 `|dx|` 与 `|dy|`，或结合业务规则）决定是否返回 true；返回 true 后子 View 收 `CANCEL`、事件归父容器。实现集中在一处，是默认首选；代价是父容器要理解子容器的意图，同向嵌套时还需按"内容是否到边界"判断。
2. **内部拦截**：父容器声明"除 `DOWN` 外默认不拦截"（`DOWN` 时必须返回 false，且不能用 `DOWN` 做拦截），子 View 在 `dispatchTouchEvent` 里按自己的逻辑调 `parent.requestDisallowInterceptTouchEvent(true)` 申请独占。申请时机必须在 `MOVE` 处理里——`DOWN` 分发前置会把手势状态连同 disallow 标志一起重置，`DOWN` 时的申请活不到下一条事件。适合子控件更懂自身语义的场景（如轮播图要求"横向位移归我"），代价是父子双方都要按约定实现，`DOWN` 处理出错会整个失效。

同向嵌套的现代解法是嵌套滚动协议（`NestedScrollingParent/Child`，`NestedScrollView`/RecyclerView 已内置）：子视图先自己消费、把未消费部分通过 `dispatchNestedScroll` 上交给父级，双方不再抢事件而按消费量协作。能用嵌套滚动表达的层级，优先用它，比手写拦截策略健壮。

**Q13: [learning] 用 scrollTo() 实现跟随手指滑动时，为什么内容往反方向跑？惯性滚动怎么续？**

`mScrollX`/`mScrollY` 的语义是"内容相对视图原点的偏移量"，且符号与直觉相反：`scrollTo(x, y)` 把内容坐标 `(x, y)` 处的内容移到视图原点，所以 `x` 增大内容向左移——跟随手指滑动要写 `scrollTo(scrollX - dx, scrollY - dy)` 而不是加号。按 AAOS13 源码核对，基类 `View.scrollTo` 只做赋值与重绘回调、不做任何边界约束，clamp 都是子类重写的（`ScrollView` 的 `scrollTo` 就带边界钳制）——自定义滑动控件的边界约束要在自己的 `scrollTo` 重写或消费逻辑里做。

惯性滚动的标准结构是三件套：`Scroller` 只做数学（`fling()`/`startScroll()` 算出每时刻的位置），本身不动内容；`invalidate()` 触发下一帧；`computeScroll()` 在每帧绘制前被回调，里面 `computeScrollOffset()` 问 Scroller 当前值，`scrollTo` 消费并再次 `invalidate()`，直到 Scroller 报告结束。漏写 `computeScroll` 里的续帧，表现就是"松手后瞬间停住"；在 `ACTION_UP` 里直接循环 scrollTo 想"一口气滚完"，则是一次性长任务，既掉帧也不可中断。

边界判断放在消费前：先算目标位置、clamp 到 `[0, maxScroll]`，再决定是否 `fling`（未到边界）或回弹（到边界后 overscroll）。`OverScroller` 是 `Scroller` 的超集，带边缘回弹，一般直接用后者。

**Q14: [learning] 收到 ACTION_CANCEL 时，应用必须清理哪些状态？**

`CANCEL` 表示"这条手势到此为止，不会再来 `UP`"，义务是把整条手势的中间状态恢复到 `DOWN` 之前，否则轻则按压态卡住、重则下次手势错乱。清理清单按来源列：

1. **View 自身的交互态**：按压态（`setPressed(false)`，否则 selector 停在 pressed）、长按与点击的延迟回调（框架在默认实现里处理，自绘控件要自己做）；
2. **手势数据结构**：`VelocityTracker` 的 `recycle()`、自己维护的触摸点表、双击检测状态；
3. **动效与视觉**：正在跟随手指的动画/位移回弹到位、嵌套滚动的 `stopNestedScroll()`；
4. **业务状态**：拖拽中的临时标记、"等待抬起才提交"的操作按取消处理（如长按录制、滑动解锁）；

框架默认 `onTouchEvent` 已覆盖第一类，其余三类都是应用自建状态，重写 `dispatchTouchEvent`/自定义容器的人最容易漏。诊断"卡按压态/幽灵滚动"类问题时，先确认每个维持状态的地方都处理了 `CANCEL`——`CANCEL` 不仅来自父容器拦截，系统手势接管、窗口失焦、触摸过期都会产生，不能假设"用户总会正常抬手"。

**Q15: [learning] filterTouchesWhenObscured 防的是什么？开启后什么场景会误伤？**

防的是点击劫持（tapjacking）：另一个窗口悬浮在本窗口之上时，用户以为在点本应用、实际事件被上方窗口遮蔽或接收。开启方式是 `setFilterTouchesWhenObscured(true)` 或布局属性 `android:filterTouchesWhenObscured`；生效点在 `View.dispatchTouchEvent` 的 `onFilterTouchEventForSecurity()`：事件带 `FLAG_WINDOW_IS_OBSCURED` 标志时直接丢弃（按 AAOS13 源码核对，`View.java`）。标志由系统在分发前根据窗口遮挡关系计算，API 29 起另有 `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` 区分部分遮挡。

误伤场景来自"遮挡"的判定不看窗口性质：合法悬浮窗（悬浮球、画中画、无障碍遮罩、车机上的气泡通知）盖到控件上，被过滤的触摸静默失效，表现为"这块区域时好时坏，只在这个悬浮窗出现时坏"。所以该机制适合用在敏感操作（支付确认、密码输入）上按需开启，不适合全应用默认打开。

排查"触摸被吃"时可读事件的 `getFlags()`：运行在遮挡状态下的事件带上述标志，应用侧可据此弹提示或记录。安全要求更高的场景配合 `onFilterTouchEventForSecurity` 之外还应校验可见性（如敏感页退后台即遮罩），因为过滤只保证"被遮时不响应"，不防上方窗口透明读屏的组合手法。

**Q16: [learning] 滚轮、悬停这类"没有按下"的事件，View 层怎么接收？旋钮事件会走到哪里？**

入口都是 `View.dispatchGenericMotionEvent()`，按事件 source 的类别分流：`SOURCE_CLASS_POINTER` 类（鼠标滚轮、悬停）里，hover 动作（`ACTION_HOVER_ENTER/MOVE/EXIT`）走 `dispatchHoverEvent()` 交给指针所在位置的 View（前提是该 View `isHoverable`——enabled 且三类可点击标志至少一个为 true，不可点击的 View 不收 hover）；滚轮 `ACTION_SCROLL` 走 `dispatchGenericPointerEvent()` 同样按位置命中，最终到 `View.onGenericMotionEvent()`，用 `AXIS_VSCROLL`/`AXIS_HSCROLL` 的值换算滚动量，事件上还能用 `getToolType()` 区分手指、触笔与鼠标。非 pointer 类的通用运动事件（如旋钮编码器）没有屏幕坐标，整条交给当前焦点 View 处理（按 AAOS13 源码核对，`View.java` 的 `dispatchGenericMotionEvent` 按 source class 分流）。

Activity 层有同名入口兜底：View 树没人消费时 `Activity.dispatchGenericMotionEvent` / `onGenericMotionEvent` 收到。滚轮处理返回 true 表示已消费，不消费则交给系统做默认滚动（对可滚动容器通常自己处理）。

车机旋钮有两条路径要区分：经 VHAL 上报的旋钮在 CarService 里被转成 `KEYCODE_NAVIGATE_NEXT/PREVIOUS` 或音量键，走按键分发；接成 Linux rotary encoder 设备的旋钮经 `RotaryEncoderInputMapper` 产生带 `AXIS_SCROLL` 的 `MotionEvent`（source 为 `SOURCE_ROTARY_ENCODER`），默认由 AOSP 的 RotaryController（一个无障碍服务）消费并驱动焦点移动。应用直接在 `onGenericMotionEvent` 里收到旋钮事件的前提是 RotaryController 未接管，做应用适配时应以官方旋钮框架为准而不是自己解析 `AXIS_SCROLL`。

**Q17: [learning] 软键盘打出的字为什么不会走 onKeyDown？文本是怎么进入 EditText 的？**

软键盘是独立的输入法应用，默认不产生 `KeyEvent`：IME 进程通过 `InputConnection` 与编辑框所在进程通信，输入的字走 `commitText()`（或 `setComposingText()`/`deleteSurroundingText()` 等编辑方法）直接写入 Editable，路径完全绕开按键分发，所以 `onKeyDown` 收不到。只有少数 IME 在特定模式下显式发送合成按键（带 `FLAG_SOFT_KEYBOARD` 标志），那是 IME 的主动选择而非系统转换。

完整链路是：焦点 View 首次获得可编辑焦点时，系统把它设为"服务目标"并建立 `InputConnection`（`EditText` 经 `onCreateInputConnection(outAttrs)` 提供，`EditorInfo` 在此填写输入类型、`imeOptions` 与初始文本）；IME 侧持有连接代理，软键盘的每次编辑都远程调用它；组合输入（拼音预编辑）用 `setComposingText` 维护下划线区段，提交时 `finishComposingText` 固化。因此拦截文本输入要在 `InputConnection` 层做（包装 `InputConnection`、重写 `commitText`），监听 `onKeyDown` 对软键盘输入无效。

两种命令都不等价于软键盘的文本提交，但构造事件的方式不同：

1. `adb shell input text`：Android 13 使用虚拟键盘的 `KeyCharacterMap` 将可映射字符转换为 `KeyEvent` 序列，不经过 IME 的 `InputConnection`；因此不能覆盖拼音组合、候选选择等 IME 编辑行为。
2. `adb shell input keyevent`：直接注入指定键码，适合测试按键分发。

要验证 IME 编辑行为，应使用真实 IME 或直接测试 `InputConnection`。

**Q18: [learning] 物理返回键在 Activity 层的默认行为是什么？重写 onKeyDown 拦截 BACK 要注意什么？**

未启用 Predictive Back 时，`KEYCODE_BACK` 经按键分发到达 `Activity`：默认实现依次尝试收起 ActionBar、弹 Fragment 回退栈，都不行则结束 Activity；按 AAOS13 源码核对（`app/Activity.java`），旧入口 `onBackPressed()`（已标记 `@Deprecated`）内部走 `onBackInvoked()`，后者通过 `ActivityClient.onBackPressed()` 通知系统侧决定" finish 还是把任务退到后台"（isTaskRoot 时）。应用拦截 BACK 的正确做法是重写 `onBackPressed`（或用 AndroidX 的 `OnBackPressedCallback`），而不是在 `onKeyDown` 里吞 `KEYCODE_BACK`。确需在按键层处理时，框架为 BACK 准备了"跟踪"协议：`onKeyDown` 对 `repeatCount == 0` 的按下调 `event.startTracking()` 并返回 true，`onKeyUp` 里以 `isTracking() && !isCanceled()` 判定才执行——默认 Activity 实现正是这么写的，且预测性返回注册后旧路径被 `mDefaultBackCallback` 门控关闭。跟踪协议的好处是"长按不算返回、抬手才算"，中途手势被抢（收到 CANCEL 标记）也不会误触发。

在 `onKeyDown` 拦截有两个坑：其一，长按返回会先收到 `ACTION_DOWN`、再按 repeat 间隔收到多条重复事件，不检查 `getRepeatCount() == 0` 会重复触发；其二，Predictive Back 启用后 `KEYCODE_BACK` 根本不再下发给应用，基于 `onKeyDown` 拦 BACK 的代码在新系统上整段失效。目标 API 33+ 的应用应迁移到 `OnBackInvokedCallback` 体系，`onKeyDown` 只保留给 BACK 之外的功能键（如相机键、车机自定义键）。

区分两个"返回"：`KEYCODE_BACK` 是按键层概念，走 `onKeyDown`；Predictive Back 的 `OnBackInvokedCallback` 是导航层概念，由手势/按键统一驱动。同一台设备上两者互斥生效，迁移时要整体切换而不是双路径并存。

**Q19: [learning] "按钮点了没反应"，应用侧的系统排查顺序是什么？**

按"事件是否到达、是否被消费、是否命中"三问收窄，顺序如下：

1. **确认事件到达 View**：在 `dispatchTouchEvent` 打日志。没到达，先查父容器——`onInterceptTouchEvent` 返回 true、父容器 `onTouch` 抢先消费、或事件根本停在外层（结合 `getRawX/Y` 与 View 布局边界比对）；
2. **确认返回值**：`onTouch` 是否误返回 true 但没做点击逻辑；`onTouchEvent` 是否因不可点击（没设 listener 且没 `setClickable`）返回 false；
3. **确认状态前提**：View 是否 `enabled`、是否被父布局 `requestDisallowInterceptTouchEvent` 之外的状态影响、`VISIBLE` 与透明度（`INVISIBLE`/`GONE` 不参与命中，alpha=0 仍可命中——"看不见却点得到/看得到却点不到"都源于此）；
4. **确认命中区域**：实际可点击区是布局边界而非视觉外观——`translationX/Y` 属性会让命中区跟着移动，但补间动画不改变布局边界；padding 算入、margin 不算；控件小于 48 dp 时命中困难且无障碍不达标；
5. **确认窗口层**：开了 `filterTouchesWhenObscured` 而恰有悬浮窗遮挡（被过滤的触摸静默丢弃）、或弹出的透明窗口/IME 盖住了目标。

车机场景再加两条：目标 View 在驾驶分心限制下被系统投放的触控锁定遮罩盖住，以及旋钮焦点模型下"看得见但焦点不在此、按键无法激活"——触摸无效与按键无效要分开验证。多数"偶现点不动"最终落在第 3、5 步的状态类原因上，稳定复现的落在第 1、4 步的几何类原因上。

**Q20: [learning] 控件太小点不中，不改布局怎么扩大它的可点击区域？**

用触摸委托（`TouchDelegate`）：父容器把一块更大的矩形映射给目标 View——调用 `setTouchDelegate(new TouchDelegate(bounds, targetView))` 后，落在 `bounds` 内的事件在父容器的事件处理里被转交给委托对象，内部先做坐标反变换（把父容器坐标换成目标 View 坐标系）再喂给目标 View，目标 View 由此"以为"手指点在自己身上（按 AAOS13 源码核对，`View` 持有 `mTouchDelegate` 并在事件处理入口优先询问委托）。

三个使用条件决定成败：

1. **受托 View 可消费：**目标 View 必须 clickable，否则收到事件也可能不消费。
2. **委托方能收到事件：**通常把委托设置在覆盖该区域的直接父容器上；扩大区域必须仍在父容器的可分发边界内。
3. **矩形坐标有效：**`bounds` 使用父容器坐标，布局完成后再计算；View 尺寸或位置变化后重新设置，避免热区留在旧位置。

典型场景是列表项里的勾选框、删除按钮这类小控件：无障碍规范建议可点击目标不小于 48 dp，`TouchDelegate` 是不改视觉尺寸达标的标准手段，且委托信息会进入 `AccessibilityNodeInfo`，对无障碍服务同样生效。排查"委托后还是点不中"按三个条件倒查，最常见是第三条——布局完成后没有重设矩形。

**Q21: [learning] "点击浮窗外部关闭浮窗"是什么机制？为什么拿不到外部点按的位置？**

窗口同时设置 `FLAG_NOT_FOCUSABLE` 与 `FLAG_WATCH_OUTSIDE_TOUCH` 后，可在窗外首个按下时收到 `ACTION_OUTSIDE`。前者表示窗口不获取输入焦点，后者请求系统通知窗外触摸；省略 `FLAG_WATCH_OUTSIDE_TOUCH` 时没有此通知。PopupWindow、Dialog 的点外关闭常用这一机制，外部触摸仍由下层窗口正常处理。

系统只投递一条 `ACTION_OUTSIDE` 通知，不转发后续 `MOVE`/`UP`，坐标为 `(0, 0)`；外部内容的位置与轨迹对浮窗不可见，从而限制其窥探其他界面操作的能力。所以基于它的逻辑只能是"外部发生了一次按下"的布尔判断，做不了轨迹分析。

需要"区分点在外部哪里"的需求（只点空白处关闭、点到悬浮球不关）靠它做不到：要么把"外部"做进自己的 View 树，要么用系统级监视通道（属系统应用能力）。浮窗"点外关闭又立刻弹回"的拉锯，通常是 `ACTION_OUTSIDE` 触发关闭的同时外部点击又触发了浮窗的显示逻辑，加时间窗去抖即可。
