# Room

> Room 机制学习资料。边界：本文回答“编译期生成与 SQL 校验、InvalidationTracker 的表级失效通知、suspend DAO 与事务的执行线程、迁移的机制层校验”；迁移设计策略与离线优先事务的实战归 [07-应用存储实践.md](07-应用存储实践.md)。依据以 Android Developers 官方文档与 androidx.room 源码为准。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] @Database 与 @Dao 的实现代码从哪里来？为什么 SQL 写错在编译期就报错？**

Room 的实现类全部由编译期符号处理器生成：KSP（Room 3.0 起，此前为 KAPT）扫描 @Entity、@Dao、@Database 注解，生成 `AppDatabase_Impl` 与每个 DAO 的 `XxxDao_Impl`——SQL 语句、参数绑定、结果列映射的样板代码都在这一步写死，同时把每条 SQL 与实体定义交叉校验。

生成机制与边界：

1. **编译期校验**：DAO 方法里的查询在编译期对着实体字段做合法性检查（列名、类型、参数匹配），写错即编译失败——这是 Room 与裸 SQLiteOpenHelper 的本质差别，错误从运行时提前到构建期。
2. **schema 导出**：`exportSchema = true` 时处理器把数据库结构导出为 JSON，供迁移校验与历史比对使用；不导出则编译期警告，且自动迁移失去依据。
3. **运行期形态**：运行时只有普通 Java/Kotlin 类在跑，没有反射查注解的开销——查询路径的成本等价于手写 SQLite 代码加上少量状态簿记。
4. **处理器演进**：Room 3.0 起完全走 KSP，KAPT 支持移除；构建配置应显式使用 KSP 插件，混用会收到处理器警告。

由此可推出的使用纪律：实体改动后必须重新构建，生成物是编译期快照，不会热更新。排查“改了实体但行为没变”时，先确认构建确实通过 KSP 重新生成了 Impl 类。

依据：Android Developers《Add Room dependencies》与 Jetpack Room 发布说明（KSP 生成、Room 3.0 移除 KAPT）。

**Q2: [learning] 表数据变了，LiveData 或 Flow 收集端是怎么知道的？**

靠 InvalidationTracker 的表级失效通知：Room 在建库时给监控的表安装失效追踪，事务提交后该表被标记失效，收集端（LiveData 或 Flow）收到通知后重新执行查询并把新结果投递出去——观察的是“表有没有变化”，不是“哪一行变了”。

机制与边界：

1. **粒度**：失效按表计算。DAO 查询声明自己读了哪些表，任何对其中一表的写操作都会让该查询失效重跑。同一事务里多次写同一表只触发一次通知。
2. **投递语义**：Flow 的重查在新数据就绪后发射，收集端拿到的是完整新结果集，不是增量 diff——需要增量语义时用 RemoteMediator 或自行 diff。
3. **跨进程边界**：默认失效只覆盖本进程内的写入；多进程共享同一数据库文件时，需要开启多实例失效（workContext 与共享文件配置）才能让其他进程的写入也触发通知，否则收集端会显示过期数据。
4. **代价**：每次失效重跑整条查询。大结果集配合高频写入会造成重复全量查询，应缩小查询范围或在收集端做聚合。

排查“界面数据不刷新”时按此分层：确认查询声明了正确的表、写入走的是同一个 RoomDatabase 实例、多进程场景确认失效配置——三个检查点互不替代。

依据：androidx.room 源码（InvalidationTracker）与 Jetpack 发布说明（多实例失效 API）。

**Q3: [learning] suspend DAO 方法在哪个线程执行？事务应该怎么写？**

suspend DAO 方法由 Room 内部的查询执行器调度，不在调用方线程、也不在主线程。事务用 `withTransaction { }` 挂起块或 @Transaction 注解表达，块内可以安全调用其他 suspend DAO 方法而共享同一个事务。

线程与事务规则：

1. **suspend 与 Flow 的线程**：suspend 查询被派发到 Room 的执行器（可用 `RoomDatabase.Builder.setQueryExecutor`/`setTransactionExecutor` 定制）；返回 Flow 的方法在 Flow 上下文执行查询，失效重查也走同一执行器。普通（非 suspend）DAO 方法不自动切线程，误在主线程调用仍会触发主线程 I/O 问题。
2. **withTransaction**：挂起块内所有 DAO 调用（含 suspend 与阻塞式）进入同一 SQLite 事务，块结束统一提交或回滚；事务线程由 setTransactionExecutor 指定的单线程执行器承载，嵌套调用不产生新事务。
3. **@Transaction**：注解在 DAO 方法上，把该方法体（或 @Query 复合查询）包成事务；对返回组合对象（@Relation 嵌套）的方法 Room 会自动加 @Transaction 语义。
4. **边界**：withTransaction 内不要做网络请求或长时间计算——事务持有数据库锁并延长 WAL 帧写回，跨进程读端会观察到长事务。

依据：Android Developers《Access data using Room DAOs》（suspend/Flow 支持与 withTransaction 语义）。

**Q4: [learning] 迁移在机制层怎么被校验和执行？autoMigration 依赖什么？**

打开数据库时，Room 校验磁盘上的实际结构与编译期导出的 schema 身份（identity hash）是否一致；版本低于当前定义时按注册的 Migration 或 autoMigration 规格逐级升级，任何一级缺失路径都会在 open 阶段抛异常，而不是运行到某条查询才失败。

机制要素：

1. **identity hash**：schema JSON 与生成代码共同维护结构指纹，打开时比对磁盘结构——手工改库（绕过 Room 写库）会破坏一致性并在此处暴露。
2. **Migration 路径**：`addMigrations` 注册的 Migration 按 from→to 版本串联，Room 自动选择从当前版本到目标版本的路径；缺少任何一段即抛 IllegalStateException。
3. **autoMigration**：以编译期导出的相邻版本 schema JSON 为输入，自动推导加列、加表等结构变化；涉及复杂变换（改列类型、拆表）时用 `@AutoMigration(spec = ...)` 的规范类声明删除列的替身或转换逻辑——没有 schema 导出就没有 autoMigration。
4. **测试锚点**：MigrationTestHelper 依据历史 schema JSON 构造旧版本库、跑迁移、再校验新版本结构——迁移测试能在 JVM 上跑通的前提正是 Q1 的 exportSchema 产物被提交进版本库。

与实战册的分工：迁移设计策略（大表回填、破坏性重建的取舍）见 [07-应用存储实践.md](07-应用存储实践.md) 的迁移题；本题只回答机制上的校验与执行路径。

依据：Android Developers《Migrate your Room database》与 autoMigration 文档。
