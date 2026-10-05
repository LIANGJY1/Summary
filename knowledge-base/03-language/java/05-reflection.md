# 反射

> Java 反射学习资料（2026-10-05 重构）：Class 与成员元数据对象、get 与 getDeclared 两族查找范围、深反射的访问检查与模块封装边界。结论按 Java 语言与平台口径（Java 9+ 模块系统相关结论单独标注）。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 反射中的 Class、Field、Method、Constructor 各表示什么？同一个类能有几个 Class 对象？**

反射 API 让程序在运行时读取类型元数据并操作类成员：`Class` 表示运行时类型，`Field`、`Method` 和 `Constructor` 分别描述字段、方法和构造器。取得元数据不代表操作一定有权限，访问检查仍受语言访问控制和模块边界影响。

1. **获取途径**：对象调 `getClass()`、类型字面量 `X.class`、按名字 `Class.forName(...)`。按名称加载时，类加载器与初始化语义结合所用 API 判断——forName 会触发初始化，加载器不等于定义类加载器。
2. **Class 唯一性**：同一个类加载器加载的同一个类，在全进程只有一个 Class 实例，`getClass() == X.class` 对同源类成立；不同类加载器加载的同名类是不同的运行时类型——这也是容器与插件框架里 ClassCastException 的常见来源。
3. **成员对象**：Field/Method/Constructor 是成员的元数据句柄，调用时仍受访问检查；不是“拿到对象就能任意操作”。

**Q2: [learning] getField 与 getDeclaredField 的查找范围差在哪？为什么“查不到”不能断定成员不存在？**

两组 API 的搜索范围不同，不能仅凭返回空值就判断整个继承层次都没有该成员：

1. **`getField` / `getMethod`**：查找符合公共访问规则的成员，并可返回继承的公共成员。
2. **`getDeclaredField` / `getDeclaredMethod`**：只查当前声明类，不限访问级别，也不自动包含父类声明。
3. **重载区分**：方法查询必须提供参数类型才能区分重载；省略参数列表的通用查询没有对应入口。

查询不到会抛 `NoSuchFieldException` 或 `NoSuchMethodException`。处理时应明确需要哪种范围：查继承体系要自己沿类层次逐级 getDeclared 并合并，而非把“未找到”当作“不存在”的唯一解释。

**Q3: [learning] setAccessible(true) 之后反射调用私有成员为什么仍可能失败？Java 9 模块封装改变了什么？**

反射访问受 Java 访问检查与运行时模块封装双重限制。Java 9 引入模块系统后，`setAccessible(true)` 对未向调用模块开放的成员可能抛出 `InaccessibleObjectException`；`trySetAccessible()`（Java 9+）在不允许压制访问检查时返回 `false` 而不抛异常，但同样不绕过模块边界。

1. **模块边界**：目标包未 opens 给调用模块时，深反射被平台拒绝；这是有意的设计而非 bug，绕行手段（加 JVM 参数 --add-opens）只应由部署方配置。
2. **版本边界**：Java 8 及更早没有模块系统，深反射主要受语言访问规则与运行时策略（如曾存在的安全管理器）约束；不能把 Java 9+ 的模块异常描述套到所有版本。
3. **实践**：优先使用公开 API；确需深反射时处理开放与失败路径，并预期未来版本继续收紧（如 JDK 逐渐封闭内部 API）。
