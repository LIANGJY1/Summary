# 反射

> Java 反射学习资料（2026-10-05 重构；2026-10-06 按 OpenJDK JEP 416/193 与 Oracle 官方文档口径扩充 MethodHandle 与性能两题）：Class 与成员元数据对象、get 与 getDeclared 两族查找范围、深反射的访问检查与模块封装边界。结论按 Java 语言与平台口径（Java 9+ 模块系统相关结论单独标注）。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 反射中的 Class、Field、Method、Constructor 各表示什么？同一个类能有几个 Class 对象？**

反射 API 让程序在运行时读取类型元数据并操作类成员：`Class` 表示运行时类型，`Field`、`Method` 和 `Constructor` 分别描述字段、方法和构造器。取得元数据不代表操作一定有权限，访问检查仍受语言访问控制和模块边界影响。

1. **获取途径**：对象调 `getClass()`、类型字面量 `X.class`、按名字 `Class.forName(...)`。按名称加载时，类加载器与初始化语义结合所用 API 判断——forName 会触发初始化，加载器不等于定义类加载器。
2. **Class 唯一性**：同一个类加载器加载的同一个类，在全进程只有一个 Class 实例，`getClass() == X.class` 对同源类成立；不同类加载器加载的同名类是不同的运行时类型——这也是容器与插件框架里 ClassCastException 的常见来源。
3. **成员对象**：Field/Method/Constructor 是成员的元数据句柄，调用时仍受访问检查；不是“拿到对象就能任意操作”。

**Q2: [learning] getField 与 getDeclaredField 的查找范围差在哪？为什么“查不到”不能断定成员不存在？**

两组 API 的搜索范围不同，不能仅凭返回空值就判断整个继承层次都没有该成员：

1. `getField` / `getMethod`：查找符合公共访问规则的成员，并可返回继承的公共成员。
2. `getDeclaredField` / `getDeclaredMethod`：只查当前声明类，不限访问级别，也不自动包含父类声明。
3. **重载区分**：方法查询必须提供参数类型才能区分重载；省略参数列表的通用查询没有对应入口。

查询不到会抛 `NoSuchFieldException` 或 `NoSuchMethodException`。处理时应明确需要哪种范围：查继承体系要自己沿类层次逐级 getDeclared 并合并，而非把“未找到”当作“不存在”的唯一解释。

**Q3: [learning] setAccessible(true) 之后反射调用私有成员为什么仍可能失败？Java 9 模块封装改变了什么？**

反射访问受 Java 访问检查与运行时模块封装双重限制。Java 9 引入模块系统后，`setAccessible(true)` 对未向调用模块开放的成员可能抛出 `InaccessibleObjectException`；`trySetAccessible()`（Java 9+）在不允许压制访问检查时返回 `false` 而不抛异常，但同样不绕过模块边界。

1. **模块边界**：目标包未 opens 给调用模块时，深反射被平台拒绝；这是有意的设计而非 bug，绕行手段（加 JVM 参数 --add-opens）只应由部署方配置。
2. **版本边界**：Java 8 及更早没有模块系统，深反射主要受语言访问规则与运行时策略（如曾存在的安全管理器）约束；不能把 Java 9+ 的模块异常描述套到所有版本。
3. **实践**：优先使用公开 API；确需深反射时处理开放与失败路径，并预期未来版本继续收紧（如 JDK 逐渐封闭内部 API）。

**Q4: [learning] MethodHandle 与核心反射的差别是什么？为什么说前者的访问检查时机更有利？取舍在哪？**

`MethodHandle` 是把方法、构造器、字段访问统一抽象为"可签名检查后直接调用"的低层句柄：访问检查在 `Lookup` 查找时一次性完成，之后 `invokeExact()` 按精确签名调用，JIT 可把它当普通调用点优化（内联）；核心反射的 `Method.invoke()` 则把可变参数装箱成 `Object[]`，且可访问性按调用路径处理，优化空间更窄。

1. **检查时机**：反射每次 `invoke` 都可能走访问检查与装箱拆箱；句柄在查找期检查一次，调用期免查。
2. **签名安全**：`invokeExact` 在调用点的静态类型必须与句柄类型完全一致，不匹配直接抛异常——类型错误前移到编译期/first-use，而不是运行期 `ClassCastException`。
3. **取舍与版本事实**：JDK 18 的 JEP 416 已把 HotSpot 的核心反射在 `MethodHandle` 之上重新实现；但官方 JEP 同时注明，句柄调用涉及多次 Java 层调用，个别场景资源消耗可能高于旧反射实现——"句柄总是更快"不成立，基准随场景互有胜负（Oracle 官方博客与 OpenJDK JEP 416，2026-10 检索）。字段访问的对应物是 `VarHandle`（JEP 193），提供带访问模式的字段读写与内存序控制。
4. **选择**：框架需要在运行期组装可复用的调用点、或需要字段内存序语义时用句柄；一次性、低频的动态调用用反射足够，代码更直观。

**Q5: [learning] 热点路径上反复做反射调用，性能特征是怎样的？HotSpot 的"膨胀"机制是怎么回事？**

反射调用的成本由三部分构成：参数与返回值的装箱/`Object[]` 组装、每次调用的访问检查，以及调用点本身的优化空间。HotSpot 对 `Method.invoke` 还有一个实现层机制——膨胀（inflation）：默认前 15 次（`sun.reflect.inflationThreshold`，实现细节随版本可变）走原生方法快速路径，超过阈值后动态生成一个专用的字节码访问类，把后续调用转成常规 Java 调用以获得 JIT 优化；这也是"反射前几次慢、之后变快"现象的来源。

1. **热点路径建议**：能缓存 `Method`/`Field` 对象就缓存（避免重复查找）；调用次数巨大的热路径改 `MethodHandle`/`VarHandle` 或接口抽象，让调用点进入 JIT 的正常优化管线。
2. **边界**：膨胀阈值、原生快速路径是否启用都是 HotSpot 实现细节，不同 JDK 与 vendor 构建可能不同；以实际应用的 profiler 数据为准，不把教程常数当作通用参数。