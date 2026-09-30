# Android MVP 架构

> Android 应用架构学习资料，聚焦 MVP 的职责边界、模块拆分、异步协作、生命周期与可测试性。题目覆盖应用层架构选择，不代表所有项目都应采用 MVP。

**Q1: Android MVP 中 Model、View 和 Presenter 各自负责什么？**

Model 提供业务数据与数据操作，View 展示界面并转发用户动作，Presenter 协调两者并把业务结果整理成界面可呈现的状态。Presenter 通过接口依赖 View，使业务流程不必直接依赖 Activity 或 Fragment。

View 不应承载跨页面复用的业务规则；Presenter 也不应直接查找或持有具体 View 控件。较大的业务逻辑可再由用例或 Repository 承接，避免把 Presenter 变成新的“全能类”。

**Q2: MVP 中 Presenter 如何通过接口解耦 View 与 Model？**

为页面定义 View 契约和 Presenter 契约：View 暴露少量渲染与交互结果方法，Presenter 暴露页面动作入口。Activity/Fragment 实现 View 接口并把用户事件交给 Presenter；Presenter 调用数据层，再通过 View 契约呈现结果。

接口应表达页面需要的能力，而不是复制整套 Activity API。这样可以用测试替身实现 View，单独验证 Presenter 的输入、数据调用和结果分支。

**Q3: 多个 UI 模块应共用一个 Presenter 还是拆成多个 MVP 组合？**

按业务状态与用例是否共享来拆分：一组紧密协作、共享同一页面状态的控件可由一个页面 Presenter 管理；生命周期和业务状态独立的区域应拆成可独立测试的组件或 Presenter。不要仅按 View 控件数量机械地“一控件一个 Presenter”。

常见目录可按 feature/页面组织，把 Contract、Presenter、View 与该功能数据入口放在相邻位置；统一包结构的价值是可发现性，不要求所有项目采用同一固定模板。

**Q4: 登录模块的 MVP 契约怎样表达输入和结果？**

登录 View 可提供用户名、密码输入的读取入口，以及显示加载状态、登录成功、验证失败和网络错误的渲染方法；Presenter 接收提交动作，校验输入并调用登录用例，再把结果转换成 View 能呈现的状态。

密码不应写入日志或长期保存在 Presenter 字段中。Presenter 应依赖接口或用例，不直接依赖某个 Retrofit 实现；这样测试可用假数据源覆盖成功、失败和空输入路径。

**Q5: MVP 中异步请求、页面销毁和内存泄漏应如何协调？**

异步结果应由页面生命周期管理：Presenter 在界面可用时向 View 发布状态，页面解绑或销毁时停止观察并取消确实属于该页面的任务。否则 Presenter 若持有 Activity/Fragment，而网络回调又晚于页面结束，就可能更新旧 View 并延长其存活。

避免在 View 中执行耗时工作；网络请求由数据层异步完成，结果通过明确的回调、响应式流或协程边界返回。协程或 RxJava 只提供执行与取消机制，不能自动替代生命周期所有权设计。

**Q6: MVP 是否更容易单元测试，BaseActivity 和 BasePresenter 有什么取舍？**

Presenter 依赖接口而不依赖 Android View 实现时，可在普通 JVM 测试中注入假数据源与假 View，验证“输入动作—数据调用—状态呈现”链路。测试性来自依赖边界清晰，不是因为采用了 MVP 名称。

基类可集中重复的生命周期或通用辅助逻辑，但过度抽象会隐藏页面差异、增加继承耦合。仅在多个模块确实共享稳定规则时抽基类；重复较少或变化快时，组合小型协作者通常更容易维护。
