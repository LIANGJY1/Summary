# Fragment 生命周期与 ViewModel 作用域

> 学习资料（文章模式沉淀）。主线：Fragment 实例生命周期与视图生命周期的两段式分离、viewLifecycleOwner 观察契约、show/hide、replace 与 ViewPager2 三种页面切换方式的视图保留与回退语义、FragmentManager 事务的三个 commit 方法边界、ViewModel 的作用域选择与观察者绑定。按 AndroidX Fragment 通用行为描述（视图生命周期自 AndroidX Fragment 1.2.0 起提供），具体异常时点随 AndroidX 版本核对。Activity 生命周期见 [01-activity.md](../03-ui/01-activity.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Fragment 的实例生命周期与视图生命周期为什么分成两段？onCreateView 与 onViewCreated 的职责应怎样划分？**

Fragment 与 Activity 的关键差异是“实例存活”与“视图存在”属于两个独立区间：完整回调序列为 onAttach → onCreate → onCreateView → onViewCreated → onStart → onResume → onPause → onStop → onDestroyView → onDestroy → onDetach，其中视图只在 onCreateView 创建、在 onDestroyView 销毁，而实例可以活得比视图久——返回栈回退、ViewPager 换页、放入 back stack 后被系统回收视图等场景都只走 onDestroyView 而不销毁实例。一切 View 相关工作都要以这个两段式为准：

1. **onCreateView**：只负责构建并返回视图层次。把数据加载、监听注册等逻辑塞进这里，会在“视图刚创建、尚未完成绑定”的时点执行复杂 UI 操作，是空指针与状态不生效的常见来源。
2. **onViewCreated**：视图已完整构建，是 findViewById、绑定点击事件、初始化列表和开始观察数据的正确位置。
3. **onDestroyView**：解除对视图及其内部对象的引用（清空 binding、移除回调）。跨越视图销毁持有旧视图引用，是 Fragment 内存泄漏的主要来源，因为宿主实例可能被容器复用而重建视图。

观察数据的边界随之确定：订阅应挂在视图生命周期的所有者（viewLifecycleOwner）上，而不是实例的生命周期所有者——挂在实例上的观察在视图销毁后仍会回调，更新的是一个已不存在的视图。

**Q2: [learning] Fragment 返回后旧 View 仍被观察者更新，视图生命周期该怎么绑定？**

Fragment 实例可能比它创建的 View 树活得更久，因此视图观察者必须绑定到 `viewLifecycleOwner`，并在视图销毁时解除直接的 View 引用。将观察者绑定到 Fragment 自身会让旧视图在 `onDestroyView()` 后继续被引用。

1. **生命周期边界**：AndroidX Fragment 1.2.0 起提供独立的视图生命周期。常见显示路径包含 `onAttach()`、`onCreate()`、`onCreateView()`、`onViewCreated()`、`onStart()` 和 `onResume()`。退出时视图先在 `onDestroyView()` 销毁，Fragment 实例可能随后才 `onDestroy()`、`onDetach()`。
2. **视图初始化**：在 `onViewCreated()` 绑定 View、初始化仅属于视图的观察者。观察 LiveData 等生命周期数据时使用 `getViewLifecycleOwner()`。
3. **清理旧引用**：在 `onDestroyView()` 清空 ViewBinding 和其他 View 引用。视图重建后重新绑定新 View，避免旧观察者泄漏并让新视图收不到数据更新。

回退栈和 ViewPager2 等场景可能保留 Fragment 实例而销毁 View。具体回调取决于导航和宿主状态，不应把示例顺序当成每条路径都必须完整经过的固定序列。

**Q3: [learning] 切换 Fragment 页面后状态与内存表现不同，show/hide、replace、ViewPager2 有什么差别？**

三种方式在视图保留、Fragment 实例保留和回退语义上不同，选型应看返回时是否要保留现有 View 树，以及可接受的内存成本。

1. `show()` / `hide()`：只改变已添加 Fragment View 的可见性，不触发 Fragment 生命周期回调。页面切换快且状态保留，但隐藏页的 View 树通常仍占内存。
2. `replace()`：移除容器中的旧 Fragment 并加入新实例。不加入回退栈时，旧 Fragment 会按移除路径销毁。加入回退栈时，旧视图销毁而实例状态可随 back stack 保留，弹栈时再恢复。
3. ViewPager2 + `FragmentStateAdapter`：Adapter 管理 Fragment 实例与保存状态。离当前页面较远的项可被销毁并保存状态，回到该项时再创建 Fragment。近邻页面的保留受 offscreen page limit 和 RecyclerView 回收行为影响，不能断言所有离屏页都立即销毁 View。
4. **选择**：高频平级切换可用 `show/hide`（接受多份 View 常驻）或 ViewPager2。需要明确导航返回语义时使用 `replace` 与 back stack。切回来状态缺失时，检查是否错误销毁了本应保留的视图或业务状态。

**Q4: [learning] 网络回调在 onSaveInstanceState() 后提交 Fragment 事务导致崩溃，三个 commit 方法有何边界？**

`commit()` 异步排队执行，`commitNow()` 在当前调用点同步执行，`commitAllowingStateLoss()` 允许在状态已保存后提交但可能让界面状态在恢复时丢失。网络回调不应通过 allowing-state-loss 来掩盖生命周期竞态。

1. `commit()`：将事务安排到主线程执行，支持加入 back stack。FragmentManager 已保存宿主状态后仍调用，通常抛出 `IllegalStateException`，因为新事务不在已保存快照中。
2. `commitNow()`：在当前调用点同步执行事务，方法返回前完成相应 Fragment 生命周期推进。不能对已调用 `addToBackStack()` 的事务使用，因为同步执行无法按异步回退栈事务保存。
3. `commitAllowingStateLoss()`：语义接近异步 `commit()`，但允许状态已保存后执行。若 Activity 随后按旧快照恢复，这次 UI 事务可能丢失，因此只适用于丢失该 UI 变化可接受的场景。
4. **回调处理**：网络结果返回时先确认宿主与当前视图仍处于允许事务的生命周期状态，再提交必要变更。使用该方法的理由是避免把异步竞态误当作可忽略的状态差异。

`onSaveInstanceState()` 之后 FragmentManager 可能已禁止普通事务。具体异常时点与生命周期实现应按 AndroidX 版本核对。

**Q5: [learning] 共享 ViewModel 后页面重建却不刷新或旧 View 泄漏，作用域与观察者应绑定谁？**

ViewModel 的 `ViewModelStoreOwner` 决定数据实例存活与共享范围，观察者的 LifecycleOwner 决定何时接收更新。两者应分别选择，不能把数据共享范围误当成 View 生命周期。

1. **Fragment 作用域**：`viewModels()` / `by viewModels()` 以 Fragment 为 owner。实例随该 Fragment 的 ViewModelStore 清理而清除，不会因为单次 `onDestroyView()` 自动清除。
2. **Activity 作用域**：`activityViewModels()` 使用宿主 Activity 作为 owner，适合多个 Fragment 共享。Activity 被永久销毁并清理 ViewModelStore 后，实例才清除。
3. **导航图作用域**：`navGraphViewModels()` 以导航图对应的 back stack entry 为 owner。图对应的回退栈 entry 移除后清理实例。
4. **观察视图状态**：只要观察结果会更新 Fragment 的 View，就绑定 `viewLifecycleOwner`。视图重建后，新 owner 重新观察共享 ViewModel。不要把观察者绑在 Fragment 实例生命周期上。
5. **避免泄漏**：ViewModel 不持有 Fragment、Activity、View 或 ViewBinding 引用。ViewModel 可能比单个视图活得更久，直接引用会把已销毁的视图树留在内存中。
