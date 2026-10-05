# 03 函数式与并发

> Kotlin 语法学习笔记之三，关注高阶函数、控制流和并发模型。章节号跨四个文件连续（第 1～12 章）。

## 目录

- [第 8 章 函数式编程](#第-8-章-函数式编程)
- [待补主题](#待补主题)

## 第 8 章 函数式编程

**Q1: [learning] 高频高阶函数或 `reified` 类型参数为什么需要 `inline`？它会带来哪些代码体积和 API 兼容性代价？**

`inline` 让 Kotlin 编译器在调用点展开函数体和可内联 lambda，使某些高频高阶调用避免函数对象与间接调用成本，也让 `reified` 类型参数和非局部返回成为可能。它不是“保证零开销”的开关，调用点代码增长与公开 API 兼容性都要纳入取舍。

1. **展开 lambda：**普通高阶函数接收 lambda 时，调用通常以函数对象和调用分发实现。内联后，编译器可以将 lambda 逻辑放入调用点，从而避免部分对象与间接调用成本。后端仍可调整生成代码，不能保证所有情况下都更快。
2. **允许非局部返回：**内联 lambda 的裸 `return` 可以退出包围调用点的具名函数。它适合表达明确的早退逻辑，但可能使控制流不再像普通 lambda 一样局部。
3. **保留类型实参：**`reified` 只能标记 inline 函数的类型参数。编译器在调用点把具体类型代入函数体，因而能在运行时执行 `is T` 或 `T::class` 等操作。它不会普遍取消 JVM 泛型擦除，嵌套的非 reified 类型实参仍受运行时类型信息限制。
4. **约束 lambda 参数：**默认可内联的 lambda 不能被存储或作为普通值逃逸。`noinline` 保留函数值语义，可存储或传给非 inline 函数，但它失去内联和非局部返回能力。`crossinline` 保持调用点内联，却禁止非局部返回，适用于 lambda 被对象或其他执行上下文捕获调用的情形。
5. **代码体积与公开 API：**内联会把函数体复制到调用方，函数体大或调用点多时可能增加产物体积。public 或 protected inline 函数的实现会进入外部模块的调用方字节码，调用方未重新编译时，库升级可能留下旧实现。这类函数也不能直接引用 private/internal 实现，除非内部声明按规则使用 `@PublishedApi`。
6. **使用条件：**优先在有可内联 lambda 或 `reified` 需求，并且性能分析或 API 语义能够说明收益时使用。没有内联 lambda，也不需要 `reified` 或非局部返回的函数通常不应仅为“更快”而添加 `inline`。

下面的最小代码展示调用点传入 lambda 的形式。`repeatAction` 的循环仍然执行三次，内联只改变编译期生成代码的组织方式：

```kotlin
inline fun repeatAction(times: Int, action: (Int) -> Unit) {
    for (index in 0 until times) {
        action(index)
    }
}

repeatAction(3) { index -> println(index) }
```

**Q2: [learning] 在 inline 函数的 `forEach` lambda 中，裸 `return` 为什么会退出外层函数？只想跳过当前元素时该怎样写？**

标准库 `forEach` 是 inline 函数，因此 lambda 中未加标签的 `return` 可以返回最近的外层具名函数。只结束当前 lambda 调用时使用 `return@forEach`。需要普通循环中的 `break` 或复杂多层跳转时，用显式循环通常更容易读懂。

```kotlin
fun printUntilZero(values: List<Int>) {
    values.forEach { value ->
        if (value == 0) return // 退出 printUntilZero
        println(value)
    }
}
```

1. 裸 `return`：它是非局部返回，退出 `printUntilZero`，而不是只结束当前 `forEach` lambda。因为 `forEach` 是 inline，lambda 的控制流可并入调用它的函数。
2. **带标签返回：**`return@forEach` 只结束当前 lambda 调用，随后 `forEach` 继续处理下一项，语义接近循环体中的 `continue`。
3. **普通循环：**若需要 `break`、跨多层循环的退出或复杂分支，显式 `for` 循环可直接表达目标控制结构，不依赖 lambda 标签。
4. **inline API 的限制：**非 inline 函数的 lambda 不能用裸 `return` 退出调用方。inline 函数的 `noinline` lambda 同样没有非局部返回能力。`crossinline` lambda 仍可内联，但显式禁止非局部返回，常用于 lambda 会被转交给另一个执行上下文的情况。

只跳过零值的写法如下：

```kotlin
fun printNonZero(values: List<Int>) {
    values.forEach { value ->
        if (value == 0) return@forEach
        println(value)
    }
}
```

若要在发现零值时退出整个函数，裸 `return` 才符合目标：

```kotlin
fun printBeforeZero(values: List<Int>) {
    for (value in values) {
        if (value == 0) break
        println(value)
    }
}
```

**Q3: [learning] `runCatching { ... }.onFailure { ... }` 把异常存在哪里？为什么它不等于 `try/catch` 已经处理异常？**

`runCatching` 捕获代码块抛出的 `Throwable` 并将其装进失败的 `Result`。`onFailure` 只对失败结果执行观察逻辑并返回原 `Result`，不恢复结果，也不会自动重新抛出异常。后续代码怎样消费 `Result` 才决定失败是否继续传播。

```kotlin
val raw = "x"
val result: Result<Int> = runCatching { raw.toInt() }
    .onFailure { error -> println("failed: ${error.message}") }

println(result.getOrNull()) // 打印 null
```

1. **成功路径：**代码块正常返回 `Int` 时，`runCatching` 返回成功的 `Result<Int>`，`onFailure` 不执行。
2. **失败路径：**`raw.toInt()` 抛出异常时，异常保存在失败的 `Result` 中。`onFailure` 可用于记录日志或诊断，它返回同一个失败结果，不等于把失败转换为成功。
3. **消费结果：**`getOrNull()` 将失败映射为 `null`，`getOrThrow()` 将原异常重新抛出。若 `onFailure` 的处理 lambda 自己抛异常，新异常直接向外传播。
4. **捕获范围：**该函数捕获 `Throwable`，范围比多数业务代码需要处理的可恢复异常更广。协程取消使用 `CancellationException` 表达，若将其留在失败 `Result` 中而不重新抛出，协程取消信号可能被业务代码吞掉。只想处理特定异常或必须保留取消语义时，使用显式 `try/catch` 并区分异常类型。

所以，异常没有因为 `onFailure` 消失。它仍在 `Result` 中，除非调用方显式转换或重新抛出。

**Q4: [learning] `ids.forEach { id -> service.get(id)?.let { value -> handle(value) } }` 中两个 lambda 各在什么时候执行？`?.let` 和 `let` 的返回值是什么？**

外层 `forEach` 为每个 id 调用一次 lambda。`service.get(id)` 返回非空值时，安全调用才会执行 `let` 的 lambda，并把该值作为 `value` 传给它。返回 `null` 时本轮跳过 `handle`。`let` 的结果是 lambda 最后一条表达式的值，外层 `forEach` 本身返回 `Unit`。

1. **外层循环：**`forEach` 按集合迭代顺序逐项提供 `id`。每一项都会调用一次 `service.get(id)`。
2. **安全调用：**若 `service.get(id)` 为 `null`，`?.` 不调用 `let`，本轮直接结束。若为非空对象，`let` 才运行，lambda 内的接收值已被智能视为非空。
3. **内层参数：**`value` 是 `let` 收到的非空值，和外层 lambda 的 `id` 不是同一个变量。嵌套 lambda 显式命名有助于避免两个隐式 `it` 混淆。
4. **返回值：**`let` 返回 lambda 最后一条表达式。安全调用链在接收对象为 null 时返回 null。原表达式没有接收整个调用结果，所以这里只利用“非空才处理”的条件效果。

下面的例子用 Map 查找代替 `service.get(id)`，说明缺少映射时内层 lambda 不运行：

```kotlin
val namesById: Map<Int, String> = mapOf(1 to "Ada")

listOf(1, 2).forEach { id ->
    namesById[id]?.let { name ->
        println(name)
    }
}
// 只打印 Ada。id == 2 时查找结果为 null，内层 lambda 不执行。
```

## 待补主题

- 第 8 章：纯函数、副作用、不可变数据、`fold` 与 `reduce`。
- 第 9 章：协程、结构化并发、取消、超时与 Flow。
- 第 10 章：注解、KSP、反射与 `kotlin-reflect`。
