# RecyclerView 列表组件

**Q1: [learning] RecyclerView 如何把数据、ViewHolder 与可见 item 连接起来，为什么创建和绑定不是一一对应？**

RecyclerView 只为当前布局需要的 item 请求 View，并在复用时把已有 ViewHolder 绑定到新数据；Adapter、ViewHolder 和 LayoutManager 分别提供数据绑定、item 视图和摆放策略。列表有很多数据项时，屏幕外的每一项不会因此都变成一棵常驻 View 子树。

1. **Adapter：**提供 item 数量与类型，并在需要时创建或绑定 ViewHolder。`onCreateViewHolder()` 创建 item View 结构，`onBindViewHolder()` 把当前数据完整写入结构；一个 Holder 可多次绑定不同数据，因此绑定次数不等于创建次数。
2. **ViewHolder：**持有一个 item 根 View 及常用子 View 引用，减少重复查找。它代表可复用的视图结构，不代表某条数据的永久对象；绑定时必须覆盖文本、可见性、选中态和异步请求等会随 item 改变的状态，避免旧 item 状态漏到新 item。
3. **LayoutManager：**决定 item 如何测量、摆放、滚动及何时需要可见项。LinearLayoutManager 组织一维列表，GridLayoutManager 组织网格；自定义布局策略也由它承担。
4. **RecyclerView 与回收：**RecyclerView 协调布局、Adapter 和回收器。离开屏幕的 ViewHolder 可能暂时保留、进入共享池，或被丢弃；之后是否能直接复用取决于 viewType 等条件，复用后通常仍要重新绑定数据。

因此，RecyclerView 复用的是 item View 结构，不是业务数据；一次创建的 ViewHolder 可以多次绑定，绑定代码必须完整设置当前 item 的 UI 状态。依据为 Android Developers《Create dynamic lists with RecyclerView》和 RecyclerView API reference。列表滚动、复用和更新的性能优化见 [View 与 Compose 渲染实战](../05-graphics/12-View与Compose实践.md)。
