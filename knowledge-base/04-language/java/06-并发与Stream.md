# Java 并发与 Stream

> Java 并发与流式处理知识。并发结论以 Java Memory Model 和 `java.util.concurrent` 契约为边界。JDK 8+ 为基线，虚拟线程自 JDK 21 成为正式特性。Android 主线程模型与 Handler 归 `01-android/02-应用框架/02-Handler消息机制.md`。

**Q1: [learning] volatile 能保证线程看到最新值吗？它能让 i++ 变成原子操作吗？**

对同一 volatile 变量的写入与后续读取建立 happens-before 关系，使读取方能够观察该写入之前的普通写入。`i++` 仍由读取、计算和写入多个步骤组成，volatile 不会把复合操作变成原子操作。

volatile 适合发布独立状态、版本号或停止标志。若更新依赖旧值，或多个字段共同维护一个不变量，应使用锁、原子类的复合操作或把状态收敛为一个不可变对象后原子替换。

**Q2: [learning] 双重检查锁定的单例为什么要求实例字段使用 volatile？**

双重检查锁定既要避免重复创建，也要安全发布构造完成的实例。同步块负责互斥创建，volatile 写入与后续读取建立可见性和顺序关系，防止其他线程观察到已经发布却尚未完成初始化的对象状态。

只有访问同一个 volatile 字段才能获得这条发布关系。构造函数把 `this` 提前交给回调、静态集合或其他线程，仍可能导致对象逸出。没有参数的单例优先使用静态初始化或枚举。只有确实需要延迟创建并传入参数时，才值得承担双重检查锁定的复杂度。

**Q3: [learning] Thread、Runnable、Callable 和 ExecutorService 分别承担什么角色？**

四者分别表示执行载体、无结果任务、有结果任务和任务调度器：

1. **Thread：**表示一条 Java 执行线程。继承 Thread 会把任务与执行方式绑定，通常不利于复用和测试。
2. **Runnable：**表示没有返回值、不能通过签名抛受检异常的任务，可交给 Thread 或执行器。
3. **Callable/Future：**Callable 可以返回结果并抛异常，Future 表示任务的完成结果。`get()` 会等待完成并包装任务异常。
4. **ExecutorService：**接收任务并管理排队、线程复用、关闭和拒绝。它不自动保证任务数量、队列容量和关闭策略合理。

业务代码优先依赖执行器或更高层并发抽象，不直接为每个任务创建线程。线程池的容量、队列与拒绝策略属于负载和背压设计，不能只靠默认构造解决。

**Q4: [learning] Java 的平台线程与虚拟线程有什么区别？**

平台线程通常映射到操作系统线程。虚拟线程由 JVM 调度，可用较低资源成本承载大量以阻塞等待为主、彼此独立的任务。虚拟线程不是更快的 CPU 执行器，也不会增加处理器并行能力。

虚拟线程适合“一个请求一条线程”式阻塞代码，但仍需限制数据库连接、文件描述符、远端配额等稀缺资源。长时间持有监视器执行阻塞操作、依赖固定线程身份或大量 ThreadLocal 数据，都可能削弱收益。Android 运行时是否提供某项新 JDK 并发能力，应按目标 Android 版本和公开 API 核对，不能只看桌面 JDK 版本。

**Q5: [learning] Java ThreadLocal 如何为不同线程保存各自的值？**

ThreadLocal 让每个线程分别关联一份值。通过同一个 ThreadLocal 实例调用 `set()` 和 `get()` 时，当前线程访问的是自己的映射项。它不负责线程间同步，也不把调用方上下文自动传播到另一条线程。

线程池会复用线程，因此任务使用后应在 `finally` 中调用 `remove()`。否则后续任务可能读到前一任务的身份、追踪或事务状态，长寿命线程也会持续持有无用对象。异步链路需要上下文传播时，应显式捕获并恢复所需字段，不能假设 ThreadLocal 会随任务移动。

**Q6: [learning] Java 线程状态能否直接说明线程为什么卡住或是否消耗 CPU？**

不能。Thread.State 的 NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING 和 TERMINATED 是 Java API 的状态分类，不是完整的操作系统调度状态。RUNNABLE 既可能正在 CPU 上执行，也可能已可运行但仍在等调度。

BLOCKED 特指等待进入 Java monitor。WAITING 或 TIMED_WAITING 可能来自 `wait()`、`join()`、`park()` 或其他同步器。判断卡顿要结合线程栈、锁或同步器持有者、等待对象、持续时间和操作系统调度数据，不能只按状态名称归因。

**Q7: [learning] happens-before 解决什么问题？它与代码书写顺序有什么不同？**

happens-before 是 Java Memory Model 用来约束跨线程可见性和顺序的关系。若动作 A happens-before 动作 B，B 必须能观察到 A 按规则应当可见的结果。它不表示两个动作在墙钟时间上紧挨着，也不等于源码中 A 写在 B 前面。

常用关系包括以下几类：

1. **线程内顺序：**同一线程中，前一个动作 happens-before 后续动作。
2. **监视器：**一次 unlock happens-before 后续对同一 monitor 的 lock。
3. **volatile：**一次 volatile 写 happens-before 后续对同一字段的读。
4. **线程生命周期：**调用 Thread.start() 之前的动作对新线程可见。线程中的动作在线程成功终止检测之后可见，例如 `join()` 返回。
5. **传递性：**A happens-before B 且 B happens-before C，则 A happens-before C。

没有 happens-before 关系的数据竞争代码，即使在某台设备上多次得到预期结果，也不能据此证明正确。编译器、JIT 和处理器可以在内存模型允许范围内重排或缓存访问。

**Q8: [learning] synchronized、ReentrantLock 和原子类应怎样选择？**

选择取决于要保护的不变量与等待能力，而不是“哪一种更快”：

1. **synchronized：**适合围绕一个 monitor 保护短临界区。退出代码块时自动释放，异常路径较安全，并同时提供互斥和可见性。
2. **ReentrantLock：**适合需要可中断获取、超时获取、公平策略或多个 Condition 的场景。它必须在 `finally` 中释放。公平模式降低插队，但通常牺牲吞吐。
3. **原子类：**适合能表示为单变量原子读改写的状态，例如计数、标志或不可变状态引用。多个原子变量不能自动组成一个原子业务不变量。

先写出临界区保护的条件，再选工具。若“余额与流水必须同时变化”，两个 AtomicLong 仍会暴露中间状态。一个锁、事务或单一不可变状态更容易保证整体一致性。

**Q9: [learning] AQS 为什么能支撑锁、Semaphore 和 CountDownLatch？FIFO 队列是否保证公平？**

AQS（AbstractQueuedSynchronizer）把“同步状态”和“等待线程管理”分开。子类用一个原子 int 表示状态，并实现获取或释放状态的判定。AQS 负责入队、阻塞、唤醒，以及独占或共享模式的通用机械过程。

1. **独占模式：**同一时刻通常只有一个线程获取成功，ReentrantLock 是典型使用者。
2. **共享模式：**多个线程可同时获取，Semaphore 和 CountDownLatch 可用状态表示许可或剩余计数。
3. **Condition：**独占同步器可以提供条件队列。等待条件会先释放同步状态，被唤醒后再竞争恢复。
4. **公平边界：**AQS 内部使用 FIFO 等待队列，但默认不自动保证严格 FIFO 获取。新到线程可能在排队线程之前获取，公平策略需要具体同步器在获取逻辑中显式限制插队。

AQS 的队列长度和等待线程查询主要用于监控，不能作为业务正确性的瞬时判断。读取之后队列可能立即变化。

**Q10: [learning] ConcurrentHashMap 的单次操作线程安全，为什么 get 后再 put 仍会丢更新？**

ConcurrentHashMap 保证它提供的单次操作遵守并发契约，但两个安全操作组合后不自动成为一个原子事务。`get(key)` 后计算再 `put(key, value)` 之间，其他线程可以修改同一个键，导致覆盖或重复初始化。

1. **按键复合更新：**使用 `putIfAbsent`、`compute`、`computeIfAbsent`、`merge` 或 `replace` 等原子复合方法表达意图。
2. **计数：**高并发频率表可用 `computeIfAbsent` 创建 LongAdder，再执行 increment。创建函数应短小，避免阻塞或递归修改冲突键。
3. **聚合观察：**并发更新期间，`size()`、`isEmpty()` 和遍历反映的是瞬时或弱一致视图，适合监控估计，不应作为需要稳定快照的业务控制条件。
4. **跨键不变量：**ConcurrentHashMap 不提供整张表锁。若多个键必须原子变化，应在更高层使用锁、不可变快照或事务。

“容器线程安全”只说明容器操作的契约，不证明使用该容器的整段业务逻辑线程安全。

**Q11: [learning] Java Stream 的中间操作为什么不立即遍历数据？**

Stream 中间操作构造处理流水线，通常在终结操作请求结果时才遍历数据。惰性执行允许运行时融合多个步骤、短路处理，但也意味着只声明 `map` 或 `filter` 不会产生结果或可靠副作用。

Stream 一般只能消费一次。如果需要多次独立处理，应从原数据重新创建流。依赖外部可变状态会使顺序流难以推理，并在并行流中引入数据竞争。

**Q12: [learning] Stream 的 reduce、collect 和 forEach 应如何区分？**

按结果形态选择终结操作：

1. **reduce：**按结合规则把元素归约为一个值，适合求和、求最值和不可变聚合。
2. **collect：**通过 supplier、accumulator 和 combiner 构造可变结果容器，适合分组、拼接或生成集合。
3. **forEach：**为每个元素执行动作，主要用于副作用，不产生聚合结果。

并行归约要求操作满足对应的结合和无干扰约束。把依赖顺序的可变累积写进并行 `forEach`，即使偶尔得到正确结果，也不具备稳定语义。
