# Layout Inspector

> 本册覆盖运行中 View 与 Compose 组件树、属性和重组计数的检查。

**Q1: [learning] Layout Inspector 能确认什么、不能确认什么？为什么开启 View 属性检查会重启前台 Activity？**

Layout Inspector 查看运行中 debuggable 应用的 View/Compose 结构：节点是否存在、父子关系、bounds 与属性、Compose 的重组与跳过计数。它给的是组件树与当前属性快照——一个节点在 Component Tree 里存在，只证明应用进程内有这个 UI 对象，不保证它有可见像素，也不保证输入事件送达。一帧为何变慢归 Perfetto，窗口与 layer 状态归 Winscope。

重启的机制：Views 属性检查依赖全局设置 `debug_view_attributes`（AAOS13 的 `Settings.java` 核对该键），Layout Inspector 启动时自动开启它，系统会重启当前前台 Activity 使新属性生效。只要 flag 未被手动关闭，后续连接不再因此重启。这对复现的影响是真实的——重启改变冷启动路径、页面状态和只消费一次的事件，抓现场前要把它写进复现步骤。性能测量前应关闭 Inspector 并确认设置与页面状态恢复。排查点击无响应时注意：`enabled`、`clickable`、可见 bounds 都在 Inspector 范围内，但父层拦截、`TouchDelegate`、InputDispatcher 分发与窗口遮挡要转到事件 trace 或 Winscope——视觉上最上方的节点不一定收到事件。Compose 计数的读法是围绕一次受控交互：Reset 清零、执行一次动作、找计数上升的最小子树。recomposition 高表示函数频繁重执行，skipped 高表示本轮被跳过，两者都不能直接当性能评分，成本归因仍要用 Compose tracing 或 Perfetto 验证是否占用目标帧。
