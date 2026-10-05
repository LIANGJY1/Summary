# Java 注解

> Java 注解与元注解学习资料。Q 序列即结构。

**Q1: [learning] Java 注解声明了什么？注解会自动改变程序行为吗？**

注解为声明附加结构化元数据，但单独声明注解不会自动改变程序行为。编译器、注解处理器、运行时框架或工具必须读取并解释这些元数据，行为才会随之变化。

**Q2: [learning] Retention、Target 和 Inherited 分别控制什么？**

元注解是修饰注解类型的注解，写在注解类型的声明上。三个常用元注解控制不同维度：

1. **`@Retention`：**指定注解保留到源码、class 文件还是运行时。若省略，默认按 `CLASS` 保留到 class 文件。
2. **`@Target`：**限制注解可放置的位置，取值为 ElementType 枚举，常用的有 TYPE、FIELD、METHOD、PARAMETER、CONSTRUCTOR、ANNOTATION_TYPE，Java 8 起新增 TYPE_USE 与 TYPE_PARAMETER，Java 9 起新增 MODULE。若省略，注解可用于除类型参数声明外的所有声明位置，但不能标注类型用法。
3. **`@Inherited`：**运行时按类查询时可沿父类找到注解。它不会复制注解，也不会让方法或接口注解自动继承。

应按消费者选择保留策略：只供编译期使用的注解通常不必保留到运行时；反射读取需要 `RUNTIME` 保留。`@Inherited` 只影响运行时类注解查询，与保留策略是两个独立问题。

**Q3: [learning] 注解处理器与运行时反射读取注解有什么区别？**

两种机制可以读取同一注解，但执行阶段和失败时机不同：

1. **注解处理器：**在编译期检查源代码元素，可生成代码或资源，因而能较早报告错误。
2. **运行时反射：**依赖 class 文件将注解保留为 `RUNTIME`，再在程序运行时查询；它可根据实际类型动态工作，但配置或元数据错误可能到运行期才暴露。

**Q4: [learning] 用 @interface 声明注解类型时，语法上有哪些强制规则？**

声明注解类型使用 @interface 关键字，编译后每个注解类型都隐式继承 java.lang.annotation.Annotation 接口，因此不能也不需要再写 extends 或 implements。限定保留级别与适用位置的元注解写在声明上方，元素以无参方法形式声明。最小示例如下：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogTrace {
    String value();
    int level() default 1;
}
```

示例逐项说明：

1. `@Retention(RetentionPolicy.RUNTIME)`：把注解保留到运行时，反射才能读到。省略该元注解时默认按 `CLASS` 保留，注解只进入 class 文件，反射读不到。
2. `@Target(ElementType.METHOD)`：限制 LogTrace 只能标注方法。省略该元注解时，注解可用于除类型参数声明外的所有声明位置。
3. `String value()`：名为 value 的元素，未声明 default，使用处必须显式赋值，否则编译报错。
4. `int level() default 1`：声明了默认值 1，使用处可省略，省略时取该默认值。

除声明写法外还有两条强制约束：

1. 注解类型不能声明为泛型。
2. 注解实例不能直接 new 出来，只能由编译器在使用处生成，或经反射从已标注的程序元素上读取。

**Q5: [learning] 为注解定义元素并传值时，元素类型限制、default 默认值与 value 简写各遵循什么规则？**

注解元素以无参方法形式声明，类型只能是基本类型、String、Class、枚举、注解类型或它们的数组。能否省略取决于有没有 default，而名为 value 的元素在只赋值给它时可进一步省略元素名：

```java
public @interface BusEvent {
    String value();                     // 未写 default，使用处必须赋值
    boolean sticky() default false;     // 有默认值，使用处可省略，省略时为 false
}

@BusEvent("logout")                         // 只给 value 赋值，等价于写 value = "logout"
@BusEvent(value = "logout", sticky = true)  // 还要给 sticky 赋值，此时 value = 必须显式写出
```

规则逐条：

1. **default 决定可否省略：**未写 default 的元素在使用处必须赋值，否则直接编译报错。写了 default 的元素可整项省略，省略时取默认值。
2. **value 简写有前提：**只有当使用处给出的唯一一个元素值对应名为 value 的元素时，才能省略元素名。一旦还要给其他元素赋值，`value =` 也必须显式写出。
3. **元素类型受限：**只能是基本类型、String、Class、枚举、注解类型以及它们的数组。包装类型（如 Integer）与集合、泛型类型（如 List、Map）都不允许。
4. **不接受 null：**元素既不能把 default 设为 null，也不能在使用处传入 null，两者都是编译错误。

**Q6: [learning] 运行时反射读不到自定义注解，通常与 @Retention 的什么设置有关？三个保留级别分别由谁消费？**

反射只能读到 `RUNTIME` 保留的注解。自定义注解供反射使用却漏写 `@Retention(RetentionPolicy.RUNTIME)` 时，注解按默认的 `CLASS` 级别保留到 class 文件。编译期没有任何警告，运行时 isAnnotationPresent 返回 false、getAnnotation 返回 null，失效是静默的。三个保留级别由不同的消费者读取：

1. `SOURCE`：只存在于源码，编译后不进入 class 文件。消费者是编译期工具，注解处理器（APT）生成代码或报告错误，IDE 与 lint 插件做静态检查，JDK 的 @Override 与 Android 的 @IntDef、@DrawableRes 都是这一级别的注解。
2. `CLASS`：默认级别，写入 class 文件但运行时反射不可见。能消费它的只有构建期字节码工具（ASM、Javassist 等），它们直接扫描或改写字节码，不需要把类加载进 JVM。
3. `RUNTIME`：保留到运行时，反射 API 可读取，是依赖注入、序列化、路由等运行时框架读取注解的前提。

选择规则由消费方决定：

1. 只参与编译期检查或代码生成：用 `SOURCE`。
2. 由构建期字节码工具读取：保持默认 `CLASS`，省略 `@Retention` 即可。
3. 要被运行时反射读取：必须显式写 `@Retention(RetentionPolicy.RUNTIME)`。

**Q7: [learning] @Inherited 元注解的确切语义是什么？哪些场景下它不生效？**

@Inherited 标注在注解类型上，表示：反射在某个类上查询该注解而类自身没有直接标注时，会沿父类链向上查找，任一父类标注即可命中。它只作用于“运行时按类查询”这一条路径，边界有三条：

1. **不作用于接口**：类实现接口不会获得接口或其父接口上的注解，getAnnotation 不会到接口里查找。
2. **不作用于方法与字段的覆盖关系**：子类覆盖父类方法时不会继承该方法上的注解；方法注解的查询只看当前类自己声明的方法。
3. **只影响查询路径，不改变保留**：注解仍须保留到 RUNTIME 反射才读得到；@Inherited 与 @Retention 解决的是两个独立问题。

“子类天然携带父类注解”只在“类级注解 + 标注 @Inherited + getAnnotation 查询”这一组合下成立；框架要覆盖接口或方法场景时，必须自行递归扫描父类、接口与方法声明。验证方式是用 getAnnotation 与 getDeclaredAnnotation 对比：前者包含继承查找，后者只查本类直接标注。
