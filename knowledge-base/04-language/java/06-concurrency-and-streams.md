# Java 并发与 Stream

> Java 并发与流式处理学习资料。主线：volatile 与原子性边界、双重检查锁定、Thread/Runnable/Callable/ExecutorService 的角色分工、平台线程与虚拟线程、Stream 的惰性与归约、ThreadLocal 与线程状态。结论按 Java 语言与标准库口径（JDK 8+ 为基线，虚拟线程自 JDK 21）；Android 主线程模型与 Handler 归 ../../01-android/02-app-framework/02-handler-looper.md。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] volatile 能保证线程看到最新值吗？它能让 i++ 变成原子操作吗？**

对同一 volatile 变量的写入与后续读取建立可见性和顺序约束，但 `i++` 由读取、计算和写入多个步骤组成，volatile 不会把复合操作变成原子操作。多个线程并发更新计数时应使用锁或原子变量。

volatile 适合用单次读写发布状态或停止标志。若更新依赖旧值或需要复合不变量，单靠 volatile 不够。

**Q2: [learning] 双重检查锁定的单例为什么要求实例字段使用 volatile？**

双重检查锁定要求多个线程安全地观察实例发布过程。volatile 除可见性外还限制相关读写重排序，使其他线程不会在构造尚未完成时观察到已发布引用。同步块负责互斥创建。现代代码应优先使用静态初始化或枚举等更简单的单例实现，除非延迟创建确有需要。

**Q3: [learning] Thread、Runnable、Callable 和 ExecutorService 分别承担什么角色？**

`Thread` 表示执行线程，`Runnable` 表示无返回值任务，`Callable` 表示可返回结果并抛出异常的任务，`ExecutorService` 管理任务提交、排队和线程复用。把任务与线程分开后，任务可由不同执行器调度。

1. **继承 Thread**：把任务实现绑定到线程子类，适合少量简单场景，但限制类继承并耦合任务与执行方式。
2. **Runnable**：将任务交给 Thread 或执行器执行，不直接返回结果。
3. **Callable/Future**：可获得任务结果并观察异常。调用 `get()` 会等待任务完成。
4. **线程池**：复用工作线程并集中管理任务，但仍须配置容量、队列、拒绝和关闭策略。

**Q4: [learning] Java 的平台线程与虚拟线程有什么区别？**

平台线程通常对应操作系统线程，适合需要与原生线程语义协作的工作。虚拟线程由 JVM 调度，可用较低资源成本承载大量以阻塞等待为主的任务。虚拟线程不是更快的 CPU 执行器，也不能提高 CPU 密集任务的并行能力。

虚拟线程自 JDK 21 成为正式特性。是否适用还取决于阻塞点、同步方式、原生调用和实际负载。不要把早期 Java 的平台线程映射结论套用到虚拟线程。

**Q5: [learning] Java Stream 的中间操作为什么不执行遍历？**

Stream 中间操作构造处理流水线并返回新的 Stream，通常在终结操作请求结果时才遍历数据。这样的惰性允许流水线融合处理步骤，但也意味着只声明 `map` 或 `filter` 不会自动产生副作用。常见创建途径：集合调用 stream()（Map 经 entrySet() 间接）、数组用 Arrays.stream()、零散值用 Stream.of()。创建只确定数据源，同样不触发遍历。

Stream 一般只能消费一次。如果需要多个独立处理，应从原数据重新创建流。依赖副作用或修改外部共享状态会使并行执行和结果推理变得困难。

**Q6: [learning] Stream 的 reduce、collect 和 forEach 应如何区分？**

三者按想要的结果形态区分：

1. **reduce**：把元素按结合规则归约为一个值，适合求和、求最值等聚合出单值的场景。
2. **collect**：把元素累积到容器或其他结果，适合分组、拼接或构造集合。
3. **forEach**：对元素执行终结动作，通常不产生聚合结果，只用于副作用。

并行流中的归约函数应满足适合分组合并的约束。不能把依赖顺序的可变累积随意写成并行 `forEach`。

**Q7: [learning] Java ThreadLocal 如何为不同线程保存各自的值？**

`ThreadLocal` 让每个线程分别关联一份变量值。通过同一个 ThreadLocal 实例调用 `set()` 和 `get()` 时，当前线程读写的是自己的副本。它不负责在线程间同步或传递数据。

在线程池中线程会被复用，使用后应在 `finally` 中调用 `remove()`，避免后续任务意外读到旧值，也避免长寿命线程持续持有不再需要的对象。不要将 ThreadLocal 当成跨线程共享的全局状态。

**Q8: [learning] Java 线程的常见状态分别表示什么？**

`Thread.State` 定义 NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING 和 TERMINATED 六种状态。它们描述 Java API 可观测的线程状态，不是一份完整的操作系统调度状态表。例如 RUNNABLE 可能正在运行，也可能正等待操作系统分配 CPU。

判断阻塞原因时还要结合线程栈、锁持有者和等待条件，不能只凭状态名称推断线程正在消耗 CPU。
