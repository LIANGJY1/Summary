# 01 语法基础

> Kotlin 语法学习笔记之一。内容以已有问题为线索，先给结论，再解释必要的语言机制。章节号跨四个文件连续（第 1～12 章）。

## 目录

- 第 1 章 基础语法——Q1～Q3
- 第 2 章 函数与集合——Q4～Q11
- 第 3 章 类型系统——Q12～Q16

## 第 1 章 基础语法

**Q1: Kotlin API 使用 `(Boolean) -> Unit` 作为回调时，Java 为什么需要返回 `Unit.INSTANCE`，什么时候应改用 `fun interface`？**

Kotlin/JVM 会把 `(Boolean) -> Unit` 表示为 `Function1<Boolean, Unit>`。Java 可以传入 lambda，但回调必须返回 `Unit.INSTANCE`，因为这里的 `Unit` 是返回值类型，不是 Java 的 `void`。

```kotlin
// Callbacks.kt
fun observe(onChanged: (Boolean) -> Unit) {
    onChanged(true)
}
```

```java
CallbacksKt.observe(value -> {
    System.out.println(value);
    return kotlin.Unit.INSTANCE;
});
```

如果公开 API 需要被 Java 频繁调用，优先定义 `fun interface`（Kotlin 1.4+）：

```kotlin
fun interface OnChangedListener {
    fun onChanged(value: Boolean)
}

fun observeJava(listener: OnChangedListener) {
    listener.onChanged(true)
}
```

```java
CallbacksKt.observeJava(value -> System.out.println(value));
```

这样 Java lambda 对应的是返回 `void` 的单抽象方法，不需要返回 `Unit.INSTANCE`。选择原则如下：

- 主要由 Kotlin 调用：函数类型更简洁。
- 需要良好的 Java 调用体验，或回调需要稳定名称：使用 `fun interface`。
- 一组相关回调包含多个方法：使用普通 `interface`。

**Q2: 读到 `StringBuilder.() -> Unit` 时应该怎样理解？它与 `(StringBuilder) -> Unit` 有什么区别？**

二者唯一的区别是**对象以什么身份进入 lambda**：`(StringBuilder) -> Unit` 把它作为普通参数，`StringBuilder.() -> Unit` 把它绑定为体内的 `this`。把同一个函数按两种方式各写一遍：

```kotlin
// 写法 A：普通函数类型——对象是参数
fun buildXml(emit: (StringBuilder) -> Unit): String {
    val builder = StringBuilder()
    emit(builder)                 // 调用 lambda：对象作为参数传入
    return builder.toString()
}

buildXml { sb ->                  // 调用方：必须自己声明参数名
    sb.append("<root/>")          // 每次访问都要写参数名前缀
}
```

```kotlin
// 写法 B：带接收者的函数类型——对象是 this
fun buildXml(emit: StringBuilder.() -> Unit): String {
    val builder = StringBuilder()
    builder.emit()                // 调用 lambda：对象绑定为 this（emit(builder) 与其等价）
    return builder.toString()
}

buildXml {                        // 调用方：不用声明参数名
    append("<root/>")             // 裸调用即可，等价于 this.append("<root/>")
}
```

逐项对比：

| | 写法 A：`(StringBuilder) -> Unit` | 写法 B：`StringBuilder.() -> Unit` |
|---|---|---|
| 类型读法 | 接收一个 StringBuilder 参数的函数 | 以 StringBuilder 为接收者的函数 |
| 对象的身份 | 普通参数（`sb`） | 隐式 `this` |
| 调用方写 lambda | `{ sb -> sb.append(...) }`，要声明参数名 | `{ append(...) }`，无参数名 |
| 函数体触发 lambda | `emit(builder)` | `builder.emit()`（或等价的 `emit(builder)`） |
| 裸调用的解析 | lambda 内没有隐式接收者，访问对象必须写 `sb.` | 先查 lambda 自身参数/局部变量，再查 `this` 的成员 |

两种写法的对象都是**函数体在调用 lambda 时传入的**，写 lambda 的人从不提供它——这是最容易忽略的一点：`buildXml { append(...) }` 里那个能 `append` 的对象，来自 `builder.emit()` 这一行。写法 B 中若裸名字与 lambda 自身参数撞名，参数优先，访问接收者成员须显式写 `this.append(...)`；需要区分内外两层 `this` 时用 `this@外层类名`。

这套机制与扩展函数相同：`fun StringBuilder.hello()` 体内能省略 `this` 调成员，写法 B 的 lambda 体内同理——相当于把扩展函数的函数体位置开放给调用方填写。

Android KTX 的 `SharedPreferences.edit` 就是写法 B 的代表。读签名的推理路径：`action` 的类型带 `Editor.` 前缀 → lambda 将在某个 `Editor` 上执行 → 函数体里 `action(editor)` 这行把 editor 绑定为接收者 → 调用方的 lambda 才能直接写 `putInt(...)`：

```kotlin
inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    action(editor)                // 绑定发生在这里
    if (commit) editor.commit() else editor.apply()
}

preferences.edit {
    putInt(KEY_COUNT, 1)          // this 是上一步传入的 editor
}
```

在 Kotlin/JVM 上两种类型编译为同一个 `Function1`（`StringBuilder` 版为 `Function1<StringBuilder, Unit>`，`Editor` 版同理），接收者退化为第一个参数。差别不产生新的 JVM 调用能力，只存在于编译期：编译器如何解析 lambda 内的 `this` 与未限定名称。

**Q3: 看到 `return interrupted + if (cond) a() else b()` 这样的写法时，`if/else` 为什么能作为一个值参与 `+` 运算？**

Kotlin 的 `if` 是表达式：带 `else` 的 `if` 整体求值为实际执行的那个分支的值，因此可以出现在任何需要值的位置——`+` 的操作数、`=` 的右侧或 `return` 的结果。这行代码的含义是：按条件从两个调用中选出一个执行，再对它的返回值与 `interrupted` 做 `+` 运算并返回。

理解的基础是表达式与语句的区分：表达式产生值，语句只执行动作。Java 的 `if` 是语句，想在表达式中取值必须借助三元运算符 `cond ? a : b`；Kotlin 没有三元运算符，`if/else` 直接承担这个角色。

```kotlin
fun main() {
    val base = 100
    val bonus = true
    val total = base + if (bonus) 10 else 5
    println(total) // 110：if 表达式的求值结果 10 参与 +
}
```

两个分支的类型必须有公共类型，整个 `if` 表达式的类型就是这个公共类型；上例两个分支都是 `Int`，因此能与 `Int` 的 `base` 相加。作为表达式使用时 `else` 不能省略——条件为假时没有值可取，编译器报错 "'if' must have both main and 'else' branches if used as an expression"；不使用求值结果的 `if`（如只在条件成立时执行副作用）是语句用法，可以没有 `else`。`for` 与 `while` 在 Kotlin 中始终是语句，没有求值结果。

`else` 换到下一行只是排版：`if` 在 `else` 分支出现前尚未完成，语法允许在 then 分支与 `else` 之间换行。按语句展开，它等价于：

```kotlin
val selected = if (cond) a() else b()
return interrupted + selected
```

`+` 的含义由左侧操作数的类型决定：左侧是 `Int` 时是算术加法；触发本问题的真实代码里，`interrupted` 与两个分支都是 `List<PetCommand>`，此时的 `+` 是列表拼接，返回一个新列表而不修改原列表。

## 第 2 章 函数与集合

**Q4: 看到 `private fun vehicleService(): VehicleService = BaseManager.getInstance(VehicleService::class.java)!!` 时，应该怎样拆解语法，`vehicleService` 又是不是通用命名？**

这是一个私有的**表达式函数**：函数体只有一个表达式，因此用 `=` 直接给出返回值，等价于在花括号中 `return`。`vehicleService` 只是项目作者按返回对象起的辅助函数名，不是 Kotlin 或 Java 的命名约定。

```kotlin
private fun vehicleService(): VehicleService =
    BaseManager.getInstance(VehicleService::class.java)!!

// 等价的函数体形式
private fun vehicleService(): VehicleService {
    return BaseManager.getInstance(VehicleService::class.java)!!
}
```

`VehicleService::class` 得到 Kotlin 的 `KClass<VehicleService>`；`.java` 将它转换为 Java 的 `Class<VehicleService>`，用于调用接收 `Class` 的 Java 风格 API。末尾的 `!!` 把可空结果断言为非空，值为 `null` 时在该处抛出 `NullPointerException`。

仅凭这段代码无法判断 `BaseManager` 是否缓存、懒创建或始终返回同一实例。

**Q5: 调用最后一个参数为函数类型的函数时，为什么 lambda 可以移到圆括号外，甚至省略圆括号？**

当函数的最后一个参数是函数类型时，可以把对应的 lambda 实参移到圆括号外；如果括号内没有其他参数，圆括号也可以省略。这只是调用语法的变化。

```kotlin
fun observe(initial: Boolean, onChanged: (Boolean) -> Unit) {
    onChanged(initial)
}

observe(true, { value -> println(value) })
observe(true) { value -> println(value) }
```

函数类型描述“接收什么参数、返回什么结果”：

```kotlin
() -> Unit
(Boolean) -> Unit
(Int, String) -> Boolean
```

lambda 写作 `{ 参数 -> 表达式 }`。单参数可以省略名称并使用 `it`；嵌套 lambda 中的 `it` 容易互相遮蔽，此时应显式命名。

判断 `function { ... }` 的含义时，应先查看函数签名：花括号通常是最后一个函数类型参数的实参。

**Q6: 创建可变 Map 时，`mutableMapOf`、`linkedMapOf` 与 `HashMap` 分别是否保证遍历顺序，应该怎样选择？**

在 Kotlin/JVM 中，`mutableMapOf` 当前返回保留插入顺序的 `LinkedHashMap`；直接构造 `HashMap` 则不承诺遍历顺序。业务逻辑不应依赖未声明的顺序；需要稳定遍历顺序时，应把这个意图明确写出来。

```kotlin
val ordered = linkedMapOf<String, Int>()
val unordered = HashMap<String, Int>()
```

如果遍历顺序不重要，`mutableMapOf` 和 `HashMap` 都可以；如果顺序是契约的一部分，优先用 `linkedMapOf` 明确表达。

**Q7: 使用列表大小生成下标区间时，为什么 `0..list.size` 会多遍历一次并导致越界？**

`..` 创建的是**闭区间**，包含左右端点。列表大小为 3 时，`0..list.size` 等于 `0..3`，但合法下标只有 `0..2`，访问最后一个下标时会抛出 `IndexOutOfBoundsException`。

```kotlin
val names = listOf("A", "B", "C")

for (index in 0..names.size) {
    println(names[index]) // index == 3 时越界
}
```

遍历下标时优先使用 `indices`；确实需要左闭右开区间时使用 `until`：

```kotlin
for (index in names.indices) println(names[index])
for (index in 0 until names.size) println(names[index])
```

- `a..b`：闭区间，包含 `a` 和 `b`。
- `a until b`：左闭右开区间，不包含 `b`。
- `a downTo b`：递减闭区间。
- `step n`：指定步长。

如果只需要元素而不需要下标，直接写 `for (name in names)`，可以从根源上避免下标错误。

**Q8: Kotlin 的区间是什么？有什么用？Java 中有对应物吗？**

区间（range）表示“从起点到终点的一段连续取值”，用 `..` 在实现了 `Comparable` 的类型上创建：`0..9` 是包含 0 和 9 的 `IntRange`，`"a".."c"` 是字符区间。区间是一个普通对象，可以判断成员、用于遍历或直接参与范围比较。

主要用途：

- **成员判断**：`in` 检查值是否落在区间内，`if (battery in 0..100)` 等价于 `battery >= 0 && battery <= 100`，端点与方向由区间统一表达，不易写反。
- **循环遍历**：`for (i in 0 until size)` 按左闭右开范围遍历下标；`downTo` 递减、`step n` 改变步长，组合出等差数列。
- **边界比较**：对日期、时间等 `Comparable` 类型直接做范围检查，如 `if (now in start..end)`。

`..` 创建闭区间，永远包含右端点；`until` 创建左闭右开区间，Kotlin 1.9 起可用运算符 `..<` 表示同样含义。用 `0..size` 遍历下标会多出一次越界访问，下标遍历优先使用 `indices`。

Java 语言没有区间语法：没有 `..` 与 `in` 运算符，范围判断要手写两个比较，循环要手写边界与步进（`for (int i = 0; i < n; i++)`）。Java 8 起的 `IntStream.range(0, n)` 提供左闭右开的数值流，第三方库（如 Guava 的 `Range`）提供区间类型；Kotlin 把这一能力内置进语言与标准库。

**Q9: 接收者可能为 `null`，调用结果也可能为 `null` 时，如何用安全调用与 Elvis 运算符统一提供默认值？**

`?.` 在接收者为 `null` 时停止调用，并让整个表达式得到 `null`；`?:` 在左侧为 `null` 时提供默认结果。两者组合可以把多个空值来源统一处理。

```kotlin
val initial: Boolean = store?.readSwitch() ?: true
```

这里有两条空值路径：

1. `store` 本身为 `null`。
2. `readSwitch()` 返回 `null`，例如尚未保存过设置。

无论哪条路径发生，结果都是 `true`，并且表达式最终类型为非空的 `Boolean`。

`?.` 也可能掩盖错误。读取可选数据时，静默跳过通常符合语义；写存储、发指令、释放资源等副作用操作如果被静默跳过，可能造成难以追踪的状态错误。此时应根据契约选择记录日志、提前返回或明确失败。

**Q10: 循环处理多个元素时，为什么不应在每轮都执行与循环变量无关的 `filter`？**

`filter`、`map`、`filterValues` 等操作通常会创建结果集合。如果结果与循环变量无关，应移到循环外，避免重复计算和分配。

```kotlin
val dueTimers = timers.filterValues { it <= now }

for (id in pendingIds) {
    handle(id, dueTimers)
}
```

判断标准不是“集合操作不能写在循环中”，而是：**本轮结果是否依赖循环变量**。依赖就必须逐轮计算；不依赖就只计算一次。

**Q11: 用 `object :` 实现单方法 Java 接口时，为什么 IDE 建议转换为 lambda？转换有什么前提？**

赋值目标或参数类型是只有一个抽象方法的 Java 接口（SAM 接口）时，`object :` 对象表达式可以简写为 `接口名 { ... }`，花括号内直接写唯一方法的实现。IDE 的「Convert to lambda」提示是在建议使用 SAM 转换，不是编译错误。

```kotlin
// Java 侧声明：public interface OnReady { void onReady(); }

val anonymous = object : OnReady {
    override fun onReady() { println("ready") }
}

val sam = OnReady { println("ready") } // SAM 转换：省略方法名与 override
```

使用前提与边界：

- 接口只有**一个抽象方法**时才能转换；新增第二个抽象方法后，lambda 写法编译失败，需改回对象表达式。
- Kotlin 声明的接口不适用这条规则，需显式声明为 `fun interface`（Kotlin 1.4+）才允许 SAM 转换。
- lambda 内的 `this` 指向外围类，而不是对象表达式创建的匿名对象；需要引用匿名对象自身时保留 `object :` 写法。

## 第 3 章 类型系统

**Q12: `listOf` 返回的集合为什么不能调用 `add`？Kotlin 的只读 `List` 是否等于不可变集合？**

Kotlin 把集合接口分为只读接口和可变接口：`List<T>` 只暴露读取操作，`MutableList<T>` 额外暴露 `add`、`remove` 和 `set`。`listOf` 返回 `List<T>`，所以编译器不允许通过这个引用修改集合。

```kotlin
val readOnly: List<Int> = listOf(1, 2, 3)
val mutable: MutableList<Int> = mutableListOf(1, 2, 3)

// readOnly.add(4) // 编译错误
mutable.add(4)
```

**只读不等于不可变**。一个 `List` 引用背后仍可能是可变对象，并被其他持有 `MutableList` 引用的代码修改：

```kotlin
val source = mutableListOf(1, 2)
val view: List<Int> = source

source += 3
println(view) // [1, 2, 3]
```

因此，返回 `List` 只表示“调用方不能通过这个引用修改”，不保证集合内容永远不变。需要独立快照时，可根据场景调用 `toList()`。

**Q13: 可空表达式后面的 `!!` 是什么意思，值为 `null` 时会发生什么？**

`!!` 是 Kotlin 的**非空断言**：它把可空表达式按非空类型使用；运行时值如果是 `null`，就在断言位置抛出 `NullPointerException`。

```kotlin
val name: String? = findName()
val length: Int = name!!.length
// name 为 null 时，第二行抛出 NullPointerException
```

它适用于“这里为 `null` 就违反程序约定”的情况。若 `null` 是正常分支，应使用安全调用 `?.`、Elvis 运算符 `?:` 或显式判空；需要让状态错误携带说明时，可以使用 `checkNotNull(value) { "错误原因" }`。需要区分责任归属时，参数非法用 `requireNotNull`、状态非法用 `checkNotNull`，失败分别抛出 `IllegalArgumentException` 与 `IllegalStateException`。

**Q14: 使用 `Int` 计算百分比时，为什么 `battery / total * 100` 可能得到 `0`，应该怎样保留正确精度？**

两个整数相除仍得到整数，小数部分会**向零截断**：`1 / 2 == 0`，`-1 / 2 == 0`。因此，先除后乘可能在不报错的情况下得到错误结果。

```kotlin
val battery = 1
val total = 2

val wrong = battery / total * 100          // 0
val integerPercent = battery * 100 / total // 50
val precisePercent = battery.toDouble() / total * 100 // 50.0
```

如何选择取决于结果类型：

- 只要整数百分比：先乘后除；数值可能很大时先转为 `Long`，避免乘法溢出。
- 需要小数：除法前把至少一个操作数转为 `Double` 或 `Float`。
- 需要向下取整而不是向零截断：使用 `Math.floorDiv`。

**Q15: 判空或类型检查通过后，为什么有些变量能 smart cast，而成员 `var` 仍不能直接按收窄后的类型使用？**

smart cast 是编译器根据判空或类型检查自动收窄类型。它成立的前提是：编译器能够证明变量在检查与使用之间不会改变。

```kotlin
fun printLength(value: String?) {
    if (value != null) {
        println(value.length) // value 被收窄为 String
    }
}

fun render(view: View) {
    if (view is TextView) {
        view.text = "Hello" // view 被收窄为 TextView
    }
}
```

当前 Kotlin 规则可以概括为：

| 声明形式 | smart cast 条件 |
|---|---|
| 局部 `val` | 通常可以；委托属性除外 |
| 局部 `var` | 检查后未修改，且未被可能修改它的 lambda 捕获 |
| 成员 `val` | 属性不可重写、无自定义 getter，且可见性或模块边界允许编译器证明稳定 |
| 成员 `var` | 不可以，因为其他代码可能随时修改它 |

`object` 只保证单例实例唯一，不保证其中的 `var` 状态不变，因此不会获得例外。

需要稳定读取成员 `var` 时，可以先保存局部快照：

```kotlin
val currentStore = store ?: return
currentStore.readSwitch()
```

这里既完成了空值处理，也避免了对成员属性的重复读取。

**Q16: 分别校验调用参数、对象状态和终止当前逻辑时，应该怎样选择 `require`、`check` 与 `error`？**

选择标准是失败代表哪一类契约被破坏：

```kotlin
fun schedule(delayMs: Long) {
    require(delayMs >= 0) { "delayMs=$delayMs" }
    check(isInitialized) { "initialize() must be called first" }
}
```

- `require(condition)`：调用方传入了非法参数；失败时抛出 `IllegalArgumentException`。
- `check(condition)`：当前对象或程序状态不允许继续；失败时抛出 `IllegalStateException`。
- `error(message)`：直接抛出 `IllegalStateException`，适合明确终止当前逻辑的分支。

`error` 不只用于理论上不可达的代码。对于外部输入或未来可能新增的协议值，应先决定业务策略：拒绝输入、忽略并记录，还是立即失败；不能用“不可达”掩盖尚未定义的行为。

`requireNotNull` 和 `checkNotNull` 分别表达相同的责任归属，并在正常返回后得到非空值。

## 待补主题

- 第 1 章：变量、其他流程控制（`when` 与循环）与字符串。
- 第 2 章：高阶函数、集合变换与序列。
