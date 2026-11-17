# Collie

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q23 -->

**Q1: [learning] Collie 的 FPS、"ANR"、启动与泄漏监控里有哪些口径陷阱？为什么这些实现只能当反例教学？**

Collie 用很少的代码拼出第一批信号，但它的信号是 SDK 自定规则推断的近似值，不是系统定义的指标，反例集中在口径命名与实现方式两类：

- **FPS 按 60 Hz 假设**：把 dispatch 耗时按 16 ms 分桶、用 `cost / 16 - 1` 推掉帧数并把平均 FPS 封顶 60——在 90/120 Hz 与动态刷新率设备上系统性误差。
- **5 秒 dispatch 预警命名成 ANR**：Looper dispatch 超过 5 秒只是“单次主线程停顿”预警，系统 ANR 有 input、Broadcast、Service、Provider 等多类型且超时不统一，事件应命名 `main_dispatch_stall`。
- **启动与页面可见**：用透明 View 的 `onDraw()` 与 window focus 估算——`onDraw()` 只证明该 View 进入绘制，window focus 受启动窗口、弹窗与多窗口影响，都不能命名成系统 TTID/TTFD（TTFD 需要应用调用 `reportFullyDrawn()`）。
- **流量只有 RX**：只读 `TrafficStats.getUidRxBytes()`，没有 TX、URL、请求阶段与错误类型，同 UID 多进程共用计数。
- **泄漏检测主动制造内存压力**：Activity 销毁后放入 `WeakHashMap`，退后台时两次分配约 4 MiB 数组并请求 GC——GC 后仍存活只是“值得检查”的候选信号，主动分配与 GC 还会扰动被测应用。
- **无界队列**：部分事件经无容量上限的 `LinkedBlockingQueue` 转交，消费跟不上生产时把流量高峰转成内存增长——没有处理背压。

可借鉴的是“少量公开入口快速验证信号”的思路。采集器本身应按系统语义重写与命名。Collie 为第三方 SDK，未本地核对。
