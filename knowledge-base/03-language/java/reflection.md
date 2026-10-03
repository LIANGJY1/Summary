# Java 反射

> Java 反射学习资料。Q 序列即结构。

**Q1: Java 反射中的 Class、Field、Method 和 Constructor 分别表示什么？**

反射 API 让程序在运行时读取类型元数据并操作类成员：`Class` 表示运行时类型，`Field`、`Method` 和 `Constructor` 分别描述字段、方法和构造器。取得元数据不代表操作一定有权限，访问检查仍受语言访问控制和模块边界影响。

可由对象调用 `getClass()`，也可由类型字面量或类名取得 `Class`；按名称加载类时，类加载器与初始化语义要结合所用 API 判断。

**Q2: getField 与 getDeclaredField、getMethod 与 getDeclaredMethod 有什么区别？**

两组 API 的搜索范围不同，不能仅凭返回空值就判断整个继承层次都没有该成员：

1. **`getField` / `getMethod`：**查找符合公共访问规则的成员，并可返回继承的公共成员。
2. **`getDeclaredField` / `getDeclaredMethod`：**只查当前声明类，不限于公共访问级别，也不自动包含父类声明。

方法查询还必须提供参数类型才能区分重载。

查询不到成员会抛出 `NoSuchFieldException` 或 `NoSuchMethodException`；处理时应明确是否需要沿继承层次查找，而不是把“未找到”当作字段不存在的唯一原因。

**Q3: 反射调用私有成员为什么可能失败？**

反射访问仍受 Java 访问检查与运行时模块封装限制。Java 9 引入模块系统后，`setAccessible(true)` 对未向调用模块开放的成员可能抛出 `InaccessibleObjectException`；安全管理器等运行环境限制也可能拒绝该操作。`trySetAccessible()`（Java 9+）可在不允许压制访问检查时返回 `false`，但不会绕过模块边界。

优先使用公开 API；只有在确有需要且运行环境允许时才使用深反射，并处理模块开放和访问失败。Java 8 等没有模块系统的环境仍受语言访问规则、运行时策略和具体实现约束，不能把 Java 9+ 的模块异常描述套用到所有版本。
