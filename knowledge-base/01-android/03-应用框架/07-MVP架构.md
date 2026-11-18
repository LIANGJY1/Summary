# Android MVP 架构

> Android 应用架构学习资料，聚焦 MVP 的职责边界、模块拆分、异步协作、生命周期与可测试性。题目覆盖应用层架构选择，不表示所有项目都应采用 MVP。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] Android MVP 中 Model、View 和 Presenter 各自负责什么？**

MVP 按职责拆分界面、业务协调和数据操作：View 展示界面并转发动作，Presenter 协调页面流程，Model 提供业务数据与操作。Presenter 通过 View 接口呈现状态，因此业务流程不必依赖具体 Activity 或 Fragment。

三部分的职责边界如下：

1. **View：**展示界面并转发用户动作，不承载跨页面复用的业务规则。
2. **Presenter：**接收 View 转发的动作，协调业务流程并把结果整理为界面状态。它依赖 View 契约，不直接查找或持有具体控件。
3. **Model：**提供业务数据与数据操作。较大的业务逻辑可再由用例或 Repository 承接，避免 Presenter 成为新的“全能类”。

**Q2: [learning] MVP 中 Presenter 如何通过接口解耦 View 与 Model？**

页面用 View 接口约定渲染能力，用 Presenter 接口约定页面动作。Activity 或 Fragment 实现 View 接口并将用户事件交给 Presenter。Presenter 调用数据层，再通过 View 契约呈现结果。

实现时按调用方向划分接口：

1. **View 契约：**只声明页面需要的读取、渲染和交互结果方法，不复制整套 Activity API。
2. **Presenter 契约：**只暴露页面动作入口，例如提交、刷新或重试。
3. **Presenter 实现：**依赖数据层接口，处理动作并通过 View 接口发布结果。
4. **测试替身：**用假 View 和假数据源实现这些接口，即可单独验证 Presenter 的输入、数据调用和结果分支。

这样，页面仍可使用 Android 控件展示界面，但 Presenter 不需要知道具体 View 类或 Retrofit 实现。

**Q3: [learning] 多个 UI 模块应共用一个 Presenter，还是拆成多个 MVP 组合？**

按共享的业务状态和用例拆分，而不是按控件数量机械拆分。共享同一页面状态且需要紧密协作的控件可以由页面 Presenter 统一协调，生命周期或业务状态独立的区域则适合拆成可单独测试的组件或 Presenter。

常见组织方式有两个判断维度：

1. **状态边界：**多个区域若必须共同更新同一页面状态，可由同一页面 Presenter 协调。独立状态不应为了减少类数量而强行合并。
2. **生命周期边界：**生命周期独立的区域应能独立订阅、取消和测试，不要依赖页面整体的隐式时序。
3. **目录边界：**可以按 feature 或页面组织，将 Contract、Presenter、View 和该功能的数据入口放在相邻位置。统一包结构提高可发现性，但不是所有项目必须采用的固定模板。

**Q4: [learning] 登录模块的 MVP 契约怎样表达输入和结果？**

登录页面的 View 提供读取用户名和密码以及呈现结果的能力。Presenter 接收提交动作、校验输入并调用登录用例，再把结果转换成 View 能显示的状态。

登录契约可以按输入、动作和结果组织：

1. **输入：**View 暴露用户名和密码读取入口。密码不得写入日志或长期保存在 Presenter 字段中。
2. **动作：**Presenter 接收提交请求，完成必要的输入校验，再调用登录用例。
3. **结果：**View 提供加载状态、登录成功、验证失败和网络错误等呈现方法。
4. **依赖：**Presenter 依赖登录用例或数据接口，不直接依赖特定 Retrofit 实现。测试可用假数据源覆盖成功、失败和空输入路径。

**Q5: [learning] MVP 如何在页面销毁时解除 View 引用并收尾异步任务？**

Presenter 应在 View 可用期间接收与发布页面状态，解绑时释放 View 引用与视图拥有的异步任务。若长寿命仓库、单例或未完成任务仍持有 Presenter，未解除的间接引用可能留住整个 Activity 或 Fragment 视图树。

1. **Activity：**在 onDestroy 解除 View 绑定，并取消随 Activity 实例结束的任务。
2. **Fragment：**在 onViewCreated 建立绑定，在 onDestroyView 解除。只服务旧视图的观察与任务随视图一起结束。Fragment 实例拥有的工作可延续至 onDestroy，重建后的视图从当前状态恢复。
3. **数据与任务所有权：**数据层执行 I/O，通过回调、流或挂起函数返回结果。Presenter 只向当前绑定的 View 发布。应跨页面或进程持续的业务操作交给相应 owner，由页面取消观察。
4. **清理方式：**RxJava 可用 CompositeDisposable 管理视图订阅，协程可使用归属明确的 CoroutineScope。显式解绑仍然负责清理回调和 View 引用。弱引用的回收时机不确定，无法表达页面是否仍允许更新。

Fragment 的视图与实例生命周期见 [Fragment 与 ViewModel](11-Fragment与ViewModel.md) Q1、Q2。

**Q6: [learning] MVP 是否更容易单元测试？BaseActivity 和 BasePresenter 有什么取舍？**

当 Presenter 依赖接口而不依赖 Android View 实现时，可以在普通 JVM 测试中注入假数据源和假 View，验证“输入动作—数据调用—状态呈现”链路：用 Mockito 之类框架 mock View 接口、stub 数据层返回值，再验证登录成功路径调用了 showSuccess()、失败路径调用了 showError()。测试性来自清晰的依赖边界，不是来自 MVP 这个名称。

是否抽基类取决于共享规则是否稳定：

1. **适合抽基类：**多个模块确实共享稳定的生命周期或辅助逻辑，且基类不会隐藏页面差异。
2. **不适合抽基类：**重复较少、页面变化快或抽象会增加继承耦合时，基类会使行为难以发现和修改。
3. **组合替代：**变化快或职责窄的共享行为，可组合小型协作者，通常比增加继承层级更容易维护。
