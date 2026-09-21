# Kotlin 学习笔记（完整版）


---

## 第1篇 入门和基础语法 / 流程控制

**Q1: Java 的优势？**

- **多平台与强大的社区支持**：无论是 Web 开发还是移动设备，Java 都是主流编程语言。
- **尊重标准**：拥有严格的语言规范及向后兼容性。非常适合团队协作，即使人员变动，新人也能在相同规范下快速上手。

**Q2: Java 的局限？**

- **范式局限**：随着多核时代与大数据的到来，古老的函数式编程重新变得“时髦”，多范式语言（如 Scala、Clojure）受到关注，对 Java 造成了冲击。
- **规范死板**：Java 过于严格的规范（如冗长的样板代码，JavaBean）也常常引发开发人员的抱怨。

**Q3: Java 8 进行了哪些重要的探索？（如何拥抱函数式编程？）**

- **高阶函数和 Lambda**：首次突破了只有类作为“头等公民”的设计，支持将函数作为参数传递。结合 Lambda 语法，改变了现有的程序设计模式。
- **Stream API**：引入“流”的概念，极大地简化了日常的集合操作，赋予了更强大的业务表达能力并增强了代码可读性。
- **Optional 类**：在类型层面提供了一种解决思路，用于消除 null 引用带来的 `NullPointerException` 问题。
  ```java
  // 传统方式：容易漏判引发 NPE，且嵌套难看
  if (user != null && user.getAddress() != null) { ... }

  // Optional 方式：优雅的链式调用，强制类型层面考虑 null
  Optional.ofNullable(user).map(User::getAddress).orElse(defaultAddress);
  ```

**Q4: Java 未来的发展方向与可能支持的新特性？**

- **数据类 (Data classes)**：用极简的语法表示常见的数据对象类（大幅减少当前 JavaBean 繁琐的代码量）。
  ```kotlin
  // Kotlin 中已经实现的 Data Class，仅需一行代码，替代 Java 中几十行的 getter/setter/toString
  data class User(val firstName: String, val lastName: String, val birthday: DateTime)
  ```
- **值类 (Value classes)**
- **泛型特化**
- **更强大的类型推导**
- **模式匹配**
- *总结*：这些特性旨在进一步解放 Java，提升开发效率与灵活性。值得一提的是，基于 JVM 的 **Scala** 语言在设计之初就集成了面向对象和函数式两大特征，并且已经支持了上述所有新特性。

**Q5: JetBrains 为什么要发明 Kotlin（诞生的根本动因）？**

- **痛点（Java 的滞后）**：JetBrains 团队（IntelliJ IDEA 的开发商）在日常开发中维护着数百万行的 Java 代码。但当时 Java 语法臃肿、发展极其缓慢，严重拉低了他们的开发效率。
- **现成替代品的缺陷**：他们看到了 C# 等现代语言的先进性，也考察过 JVM 平台上的 Scala。但 Scala 过于复杂且**编译速度极慢**，无法满足大型工程的效率要求。
- **历史资产包袱**：他们不可能抛弃公司现有的数百万行 Java 代码库去用其他语言重写。
- **结论与核心目标**：既然市面上没有一门语言能同时满足“现代高效”、“编译快”且“与现有 Java 代码 100% 绝对兼容”，JetBrains 决定自己造一门语言。这就是 Kotlin 的核心使命：**不仅要改良 Java 的痛点，还要完全兼容 Java，成为一门“更好的 Java”（Better Java）**。

**Q6: Kotlin 的设计哲学是什么？与 Scala 有何异同？**

- **相同点**：都源于对 Java 的改良，都在面向对象和函数式之间建立了多范式的桥梁。
- **不同点（实用主义）**：Scala 旨在成为程序员梦想中“包含所有特性”的语言（More than Java），侧重于语言本身的探索；而 Kotlin 则**十分克制和立足现实**。它拒绝了极其复杂的特性（如宏），而是引入能直接改善生产力的特性（数据类、扩展函数、Smart Casts 等）。它的定位非常清晰：在计算机应用领域成为一门**实用且高效**的语言。

**Q7: 为什么说 Kotlin 是“更好的 Java”？（Kotlin 做了哪些具体改良？）**

- **代码极简**：很大程度上实现了类型推导；引入 `object` 关键字直接声明单例（放弃了 `static` 和繁琐的单例模式代码）；引入了数据类（Data Classes）和密封类（Sealed Classes）。
- **向下兼容**：Kotlin 更加兼容 Java 的生态和语法，它可以与 Java 6 一起工作（而新版 Scala 要求 Java 8），这使得 Kotlin 在 Android 上迅速流行。
- **保留习惯（平滑过渡）**：Scala 激进地舍弃了 `return` 关键字（一切皆类型），而 Kotlin 虽支持单行表达式，但依然允许保留 Java 风格的函数定义（大括号 + `return`），Java 程序员极易上手。
- **更直观的语法**：用“扩展函数”替代了 Scala 中强大但晦涩抽象的 `implicit` 特性，满足开发需求的同时保证了代码的可读性（如 Android 的 `android-ktx` 扩展库）。
- **强大的语法糖**：例如 **Smart Casts** 特性。
  ```Kotlin
  if (view is ViewGroup) {
      view.addView(child) // 编译器会自动将其视作 ViewGroup 类型，无需像 Java 那样写出冗余的强转 (ViewGroup) view
  }
  ```

**Q8: 什么是 “Targeting JVM / JavaScript and Native”？（Kotlin 的跨平台生态）**

这句话的意思是 Kotlin 的**编译目标（Targeting）不仅限于 Java 虚拟机，它是一门真正的**多平台语言。一份 Kotlin 代码，可以根据需要编译到不同的平台上运行：

- **Targeting JVM（服务端与 Android 开发）**：Kotlin 代码被编译成 `.class` 字节码，跑在 JVM 上。不仅用于服务器后端开发（如完美支持 Spring Framework 5），也是 Android 开发的官方语言。
- **Targeting JavaScript（前端开发）**：Kotlin 代码被编译成 JS 代码（Kotlin/JS），从而可以直接运行在浏览器或者 Node.js 环境中，甚至用来构建 HTML UI。
- **Targeting Native（原生环境开发）**：通过 Kotlin/Native 技术，Kotlin 可以彻底脱离 JVM 虚拟机，借助 LLVM 直接编译成操作系统能识别的**底层机器码**（二进制文件），用于 iOS、macOS、Linux、Windows 甚至是树莓派等嵌入式系统的开发。

**Q9: 如何用“滑雪”来生动比喻 Java、Scala 和 Kotlin 的关系？**

- **Java（双板滑雪）**：最传统、大家最熟悉的滑雪方式。
- **Scala（单板滑雪）**：姿势优雅、速度快，能进行高级的“深粉雪”滑行（纯函数式编程）。但对于习惯双板的人来说，学习单板就像是一门新运动，容易摔跤（学习曲线极其陡峭）。
- **Kotlin（刻滑板）**：它是双板的一种改良。运动员**完全可以保留原有双板的习惯**（完美兼容 Java 开发者习惯），但同时也能在一定程度上进行“深粉雪”滑行（支持适度的函数式编程）。
- *总结*：Android 这项“世界级赛事”目前未对单板（Scala）开放，但对刻滑板（Kotlin）敞开了怀抱。想寻找更好 Java 体验的开发者，刻滑板是最佳选择！

**Q10: Kotlin 的类型声明**

- **类型后置**：增强代码可读性，也更利于后续省略类型声明（发挥类型推导的作用）。
  ```kotlin
  val a: String = "I am Kotlin"
  ```
- **强大的类型推导**：编译器可自动推导变量类型，极大提高静态类型语言的开发效率。
  ```kotlin
  val str = "I am Kotlin" // 省略了 : String
  val intVar = 1314       // 自动推导为 Int
  ```
  *(注：静态类型语言意味着在**编译阶段**变量的类型就会被严格固定。Kotlin 通过类型推导，让开发者享受了动态语言般的书写极简感，同时又保留了静态语言的绝对安全与高性能。)*

  **注意：类型推导并非万能，以下情况必须或建议显式声明类型：**
  1. **函数的参数**：必须显式声明类型（编译器无法猜测调用者会传什么）。
  2. **非表达式定义的函数（代码块函数体）**：除了返回 `Unit`（类似 Java 的 `void`，表示无有意义的返回值），其他情况必须显式声明返回类型。否则编译器会默认当成返回 `Unit`，若你实际在代码块中 `return` 了一个具体值（如 `Int`），就会产生冲突并报错：
     ```kotlin
     // 错误写法：大括号包围，未声明返回类型，默认期望返回 Unit，但你却 return 了 Int，编译报错
     fun sum(x: Int, y: Int) { return x + y } 

     // 正确写法 1（显式声明返回类型为 Int）：
     fun sum(x: Int, y: Int): Int { return x + y }

     // 正确写法 2（单表达式函数，省略大括号，由编译器自动推导返回类型）：
     fun sum(x: Int, y: Int) = x + y
     ```
  3. **递归函数**：由于 Kotlin 支持子类型和继承，很难做到 Haskell 那样的全局类型推导，因此递归时必须显式声明。
     ```kotlin
     // 报错：类型检查遇到递归问题。必须写成 fun foo(n: Int): Int = ...
     fun foo(n: Int) = if (n == 0) 1 else n * foo(n - 1)
     ```
  4. **公有方法的返回值**：为了更好的代码可读性及输出类型的可控性，建议显式声明。
- **单表达式函数**：可以用 `=` 代替 `{}` 来定义函数，不仅能省略 `return` 关键字，也能利用类型推导直接省略返回值类型。
  ```kotlin
  fun sum(x: Int, y: Int) = x + y // 省略了 {} 和返回值类型 : Int
  ```

**Q11: 深度理解** **`val`** **与** **`var`** **的区别：为什么提倡优先使用** **`val`？**

- **本质区别**：`var` 代表可变变量（variable），`val` 代表只读变量（value，类似于 Java 的 `final`）。
- **延迟初始化限制**：`val` 支持在声明时不立即赋值，但**必须显式声明类型**，且在它的生命周期内**只能被赋值一次**。
  ```kotlin
  val a: Int // 必须声明类型
  a = 1      // 只能被赋值一次
  // a = 2   // 再次赋值将编译报错
  ```
- **引用不可变 vs 对象不可变**：`val` 仅仅保证**引用地址不可变**，并不代表其指向的内存对象不可变。
  ```kotlin
  val x = intArrayOf(1, 2, 3)
  // x = intArrayOf(2, 3, 4) // 编译报错：val cannot be reassigned (引用不可变)
  x[0] = 2                   // 正常运行：数组内部的元素可被修改 (对象状态可变)
  ```
- **防御性编程与消除副作用（核心考量）**：副作用往往由可变数据和共享状态引起（如多线程并发导致状态错乱）。优先使用 `val`（搭配不可变对象与纯函数），能从根本上杜绝外部非法修改带来的状态不可控，使代码更安全、更易推理。
- **`var`** **的正确归宿**：在局部作用域内（如算法循环内累加），变量与外界无交互，使用 `var` 往往比纯函数式方案（如递归或 `fold`）拥有更高的运行效率和更低的内存消耗。

**Q12: 什么是高阶函数？它解决了怎样的工程痛点？（函数作为头等公民）**

- **头等公民的体现**：在 Kotlin 等部分支持函数式特性的语言中，函数不仅能像类一样定义在顶层或嵌套在其他函数内部，更重要的是：**它可以像普通变量一样被传递或返回**。
- **高阶函数的概念**：接收一个或多个函数作为参数，或者以一个函数作为返回值的函数。这是对“过程”这种抽象的进一步升维。
- **解决的痛点（行为抽象与解耦）**：
  在传统面向对象中，方法只能接收“数据”。当业务逻辑频繁变动时（比如一个 CountryApp 需要根据不同的“大洲”、“人口”、“语言”来筛选国家），会导致方法的参数无限膨胀，且内部充斥着高度耦合的 `if/else` 判断。
  而高阶函数允许将**行为（一段业务逻辑过滤过程）**抽象为一个参数（函数引用）传递进去。这使得数据处理骨架与具体的业务条件彻底解耦。
  ```kotlin
  data class Country(val name: String, val continent: String, val population: Int)
  
  // 1. 高阶函数设计：将具体的“过滤条件判断”抽象为参数 test，类型为 (Country) -> Boolean
  fun filterCountries(countries: List<Country>, test: (Country) -> Boolean): List<Country> {
      val res = mutableListOf<Country>()
      for (c in countries) {
          if (test(c)) res.add(c) // 直接调用传入的业务逻辑
      }
      return res
  }
  
  // 2. 外部定义一段具体的行为（判断是否为人口大于1亿的欧洲大国）
  fun isBigEuropeanCountry(country: Country): Boolean {
      return country.continent == "EU" && country.population > 10000
  }
  
  // 3. 将行为（方法引用）作为参数传递，彻底解耦
  // filterCountries(countries, ::isBigEuropeanCountry)
  ```

**Q13: 深入底层：Kotlin 的 Lambda 表达式是如何在 JVM 上运行的？**

- **本质**：Lambda 是简化表达后的匿名函数（一种语法糖），也是 Kotlin 中最常见的**闭包**（能够访问并修改外部环境变量的函数）。
  ```kotlin
  // Lambda 语法糖，it 隐式代表了单个参数
  listOf(1, 2, 3).forEach { print(it) }
  ```
- **JVM 层的转换（FunctionN 接口）**：Java 并没有原生的函数类型。Kotlin 编译器在底层构建了从 `Function0` 到 `Function22` 的接口族（后缀数字代表参数个数）。每个 Lambda 在底层都会被编译成一个实现了对应 `FunctionN` 接口的匿名内部类实例，执行时实际调用的是其中的 `invoke()` 方法。
- **为什么最大是 22？**：沿用了 Scala 设计的业界惯例。对于超过 22 个参数的极端情况，Kotlin 提供了泛型的 `FunctionN` 来兜底解决。

**Q14: 什么是“柯里化”（Currying）？Kotlin 是如何看待它的？**

- **概念**：将接收多个参数的函数，变换成一系列仅接收单一参数的函数的过程。它就像\*\*“击鼓传花”\*\*：第一个人处理第一个参数，然后把剩下的任务（返回一个新函数）传给下一个人，直到最后一个人计算出最终结果。
  ```kotlin
  // 柯里化风格的加法
  fun sum(x: Int) = { y: Int -> { z: Int -> x + y + z } }
  sum(1)(2)(3) // 链式调用
  ```
- **Kotlin 的实用主义取舍**：柯里化源于 Lambda 演算理论的局限（函数只能接收单参数）。在实际工程中，多参数函数是天然支持的，严格的柯里化反而繁琐。Kotlin 提供了一种更实用的\*\*“类柯里化语法糖”\*\*（Trailing Lambda）：当函数的最后一个参数是函数类型时，调用时可将 Lambda 移到括号外部。
  ```kotlin
  fun curryingLike(content: String, block: (String) -> Unit) { block(content) }

  // Lambda 移到括号外部，既实现了类似柯里化的优雅链式表达，又避免了过度抽象
  curryingLike("looks like currying style") { content -> 
      println(content) 
  }
  ```

**Q15: 为什么 Kotlin 极力提倡“面向表达式编程”？（表达式 vs 语句）**

- **核心差异**：语句（Statement）用于控制流程，只执行动作不返回值，其业务意义通常伴随着**副作用**（如修改外部变量的值）；表达式（Expression）则会**计算并产生一个新值**。
- **消除副作用，提升安全性**：在 Kotlin 中，`if`、`when`、`try` 都是表达式。这意味着你可以直接写 `val res = if (flag) A else B`。这种设计强制开发者处理所有分支逻辑（必须有 else），彻底杜绝了“先声明空变量，再在 if 语句中赋值”的危险操作，消除了状态突变的隐患。
  ```kotlin
  // 表达式安全且紧凑，杜绝了变量声明未初始化的隐患
  val a = if (flag) "dive into Kotlin" else ""
  ```
- **更好的组合性**：表达式具备极强的隔离性，能互相嵌套组合，构建出极具表达力的逻辑流。
  ```kotlin
  // try-catch 表达式与 if 表达式组合
  val res: Int? = try {
      if (result.success) decode(result.response) else null
  } catch (e: Exception) {
      null
  }
  ```

**Q16: 为什么要引入** **`Unit`** **类型来取代 Java 的** **`void`** **关键字？**

- **函数式理念的统一**：函数式语言要求一切皆表达式，所有函数都必须有返回值。Java 的 `void` 只是一个修饰符，代表“无返回值”。
- **拯救泛型灾难**：Java 泛型无法使用 `void`（如 `Function<String, void>` 编译报错），只能用包装类 `Void` 代替，且必须丑陋地返回 `null`。这也是 Java 8 迫不得已创造 `Consumer` 等大量冗余接口的原因。
  ```java
  // Java 中面对泛型时，Void 只能返回 null
  Function<String, Void> printFunc = arg -> {
      System.out.println(arg);
      return null; // 极度丑陋
  };
  ```
- **Unit 单例的优雅**：`Unit` 是一种真正的类型，且在全局只有一个实例 `()`。它完美兼容了泛型系统，让高阶函数的 API 设计更加统一，不再需要为无返回值的场景单独开辟后门。
  ```kotlin
  // Kotlin 中，Unit 是一个正常的类型
  val printFunc: (String) -> Unit = { arg -> println(arg) } 
  ```

**Q17: Kotlin 在流程控制、运算符和字符串上还有哪些惊艳的改良？**

- **`when`** **表达式**：超级增强版的 `switch`。无需 `break`，支持多条件、类型检测、区间判断。甚至可以省略参数直接在分支写布尔表达式，代码极为紧凑。
  ```kotlin
  val type = when (x) {
      1 -> "One"
      in 2..10 -> "Range 2 to 10"
      is String -> "It's a string"
      else -> "Other"
  }
  ```
- **Elvis 运算符** **`?:`**：针对可空类型的优雅处理方案。
  ```kotlin
  val a = b ?: 0 // 若 b 为 null，则返回 0
  ```
- **范围与成员检查**：通过 `..`、`downTo`、`until`、`step` 等操作符构建区间，结合 `in` 关键字，让循环控制极具语义化。
  ```kotlin
  for (i in 10 downTo 1 step 2) print(i) // 10 8 6 4 2
  ```
- **中缀表达式（infix）**：允许省略点号和括号进行调用，要求必须是扩展/成员函数且仅有单参数，让代码读起来像自然语言。
  ```kotlin
  infix fun Person.called(name: String) { println("My name is $name") }
  val p = Person()
  p called "Shaw" // 等价于 p.called("Shaw")
  ```
- **字符串的进化**：
  - **原生字符串** `"""..."""`：保留换行与原始格式，极度适合书写 HTML 等代码块。
    ```kotlin
    val html = """
        <html>
            <body>Hello</body>
        </html>
    """
    ```
  - **字符串模板** `"${var}"` 或 `$var`：直接在字符串内植入变量或表达式，彻底告别丑陋的 `+` 号拼接。
    ```kotlin
    val msg = "Hi $name, length is ${name.length}"
    ```
  - **判等分离**：`==` 用于判断结构（内容）是否相等，`===` 用于判断引用（内存地址）是否相等。

**Q23: 如何在 Kotlin 中定义枚举类？为什么有时必须强制加分号？（2.4.4 枚举类和 when 表达式）**

- **枚举是类**：Kotlin 中枚举通过 `enum class` 实现，比 Java 的 `enum` 语法多了 `class` 关键字。正因为它是一种类，所以可以拥有构造参数，以及额外的属性和方法。
  ```kotlin
  enum class Day {
      MON, TUE, WEN, THU, FRI, SAT, SUN
  }

  enum class DayOfWeek(val day: Int) {
      MON(1),
      TUE(2),
      WEN(3),
      THU(4),
      FRI(5),
      SAT(6),
      SUN(7)
      ; // 如果以下有额外的方法或属性定义，则必须强制加上分号
      fun getDayNumber(): Int {
          return day
      }
  }
  ```
- **分号的设计动机**：早期枚举语法需要每个枚举值显式构造（如 `MON: DayOfWeek(1)`），很繁琐。简化成 `MON(1)` 后，编译器难以区分"枚举值"和"类方法"。Kotlin 的解法是用**逗号**分隔每个枚举值、用**一个分号**隔离额外的属性/方法定义，这样既简化了语法，又与 Java 枚举更相似，符合 Kotlin 的设计原则。

**Q24: 为什么用 `when` 代替 `if-else`？如何配合枚举做穷举？（2.4.4）**

- **痛点**：当分支较多时，`if-else` 链会不断嵌套、堆叠语法关键字，代码冗长且可读性差。
  ```kotlin
  fun schedule(day: Day, sunny: Boolean) = {
      if (day == Day.SAT) {
          basketball()
      } else if (day == Day.SUN) {
          fishing()
      } else if (day == Day.FRI) {
          appointment()
      } else {
          if (sunny) {
              library()
          } else {
              study()
          }
      }
  }
  ```
- **`when` 的优势**：去掉了 `else if`、内层 `if` 等大量语法噪音，整个函数"瘦身"很多，可读性明显提升。同时 `when` 是一个表达式，可以直接作为函数体返回值。
  ```kotlin
  fun schedule(sunny: Boolean, day: Day) = when (day) {
      Day.SAT -> basketball()
      Day.SUN -> fishing()
      Day.FRI -> appointment()
      else -> when {
          sunny -> library()
          else -> study()
      }
  }
  ```
- **更优雅的扁平化写法**：`when` 的参数可以省略，直接在分支左侧写布尔条件，把嵌套的 `when` 拍平，逻辑一目了然。
  ```kotlin
  fun schedule(sunny: Boolean, day: Day) = when {
      day == Day.SAT -> basketball()
      day == Day.SUN -> fishing()
      day == Day.FRI -> appointment()
      sunny -> library()
      else -> study()
  }
  ```

**Q25: `when` 表达式的具体语法有哪些要点？（2.4.4）**

- **类似 switch 但更强大**：由 `when` 关键字开始，用花括号包含多个逻辑分支，每个分支由 `->` 连接，自上而下匹配，**不再需要 switch 恼人的 `break`**；全部不匹配时执行 `else` 分支，类似 switch 的 `default`。
- **分支具有返回值**：每个逻辑分支都有返回值，整个 `when` 表达式的类型就是所有分支的相同类型或公共父类型。这也是它能直接作为表达式函数体返回值的原因。
  ```kotlin
  fun foo(a: Int) = when (a) {
      1 -> 1
      2 -> 2
      else -> 0
  }
  >>> foo(1)
  1
  ```
- **参数可以省略**：此时每个分支 `->` 左侧必须返回布尔值作为条件，否则编译报错。
  ```kotlin
  when {
      sunny -> library()
      else -> study()
  }
  // 报错示例：条件必须是 Boolean
  >>> when { 1 -> 1 }
  error: condition must be of type kotlin.Boolean, but is of type kotlin.Int
  ```
- **表达式可组合**：`when` 可以与其它表达式任意嵌套、组合（例如嵌套 `when`、作为表达式函数体），这种长表达式在 Java 中很少见，在 Kotlin 中却很常见。

**Q26: Kotlin 的 `for` 循环相比 Java 有什么不同？（2.4.5 for 循环和范围表达式）**

- **去掉了"初始化; 条件; 更新"三段式**：Java 用分号语句块构建循环，而 Kotlin 只需 `for (i in 1..10)` 一条简洁的表达式，语义化更强。
  ```java
  // Java
  for (int i = 0; i < 10; i++) {
      System.out.println(i);
  }
  ```
  ```kotlin
  // Kotlin：等价表达，一行搞定
  for (i in 1..10) println(i)
  // 带类型声明和花括号也支持
  for (i: Int in 1..10) {
      println(i)
  }
  ```
- **任意可迭代结构都能 for**：任何提供迭代器（iterator）的结构都可以用 `for` 语句迭代，不只是区间。
  ```kotlin
  for (c in array) {
      println(c)
  }
  ```
- **withIndex 解构**：通过 `withIndex()` 方法可以得到带索引的键值元组，配合 `for` 的括号解构直接使用下标和元素。
  ```kotlin
  for ((index, value) in array.withIndex()) {
      println("the element at $index is $value")
  }
  ```

**Q27: 什么是范围表达式（range）？它有哪些限制？（2.4.5）**

- **实现机制**：Range 表达式是通过 `rangeTo` 函数实现的，用 `..` 操作符与某种类型的对象组成。除了整型的基本类型之外，该类型需要实现 `java.lang.Comparable` 接口——因为区间的本质就是"大小之间的连续取值"，比较能力是前提。
- **字符串区间**：`String` 实现了 `Comparable` 接口，字符串值之间可以比较大小（按首字母在字母表中的排序比较，首字母相同则从左往右取下一个字母，以此类推），所以可以创建字符串区间。
  ```kotlin
  "abc".."xyz"
  ```
- **本质**：区间是一种**表达式**，它让"循环范围"和"成员关系判断"都被纳入 Kotlin 面向表达式的设计中，这也是它与 Java 流程控制的根本差异所在。

**Q28: `step`、`downTo`、`until` 操作符如何使用？（2.4.5）**

- **step（步长）**：定义迭代的步长，每隔 n 个取值一次。
  ```kotlin
  >>> for (i in 1..10 step 2) print(i)
  13579
  ```
- **downTo（倒序）**：实现倒序迭代。注意这里必须用 `downTo` 方法，而不是写 `10..1`。
  ```kotlin
  >>> for (i in 10 downTo 1 step 2) print(i) // 通过 downTo，而不是 10..1
  108642
  ```
- **until（半开区间）**：构建一个"包含起点、不包含终点"的半开区间。
  ```kotlin
  >>> for (i in 1 until 10) { print(i) }
  123456789 // 并不包含 10
  ```

**Q29: `in` 关键字除了用于 `for` 循环，还有什么作用？（2.4.5）**

- **检查成员关系**：`in` 可以用来判断一个元素是否是某个区间或集合的成员；前面加感叹号 `!in` 则是相反的判断结果。
  ```kotlin
  >>> "a" in listOf("b", "c")
  false
  >>> "a" !in listOf("b", "c")
  true
  ```
- **结合范围表达式表示"大小落点"**：`in` 与区间结合，可以判断一个值是否落在区间范围内，比写出完整的大小比较链更具语义。
  ```kotlin
  >>> "kot" in "abc".."xyz"
  true
  ```
  `"kot" in "abc".."xyz"` 等价于把 "kot" 与区间两端做大小比较，判断它是否落在 `"abc".."xyz"` 这个区间内。

**Q30: 什么是中缀表达式（infix）？`in`、`step`、`downTo`、`until` 是怎么做到不通过点号调用的？（2.4.6 中缀表达式）**

- **概念**：Kotlin 中一些方法可以不通过点号、而是以"`A 中缀方法 B`"的自然语言形式被调用，这种函数被称为中缀函数。标准库的 `to` 就是典型代表，它通过泛型实现，返回一个 `Pair`。
  ```kotlin
  infix fun <A, B> A.to(that: B): Pair<A, B>
  ```
- **常见的天然中缀**：本章出现的 `in`、`step`、`downTo`、`until` 都是这类中缀方法，所以才能写出 `1..10 step 2`、`10 downTo 1`、`1 until 10` 这样简洁直观的表达式。
- **使用场景**：因为 `to` 返回键值对（`Pair`），它经常与 `map` 结合使用，`mapOf(1 to "one")` 读起来非常自然。
  ```kotlin
  mapOf(
      1 to "one",
      2 to "two",
      3 to "three"
  )
  ```

**Q31: 定义中缀函数需要满足哪些条件？为什么？（2.4.6）**

- **必须满足的四个条件**：
  1. 该中缀函数必须是某个类型的**扩展函数或者成员方法**；
  2. 只能有**一个参数**；
  3. 参数**不能有默认值**；
  4. 参数**不能是可变参数**。
- **原因**：中缀表达式是"`A 中缀方法 B`"的两段式结构，参数必须恰好为一个。若参数有默认值，形式 `B` 会缺失，从而破坏中缀表达式的语义；若参数是可变参数，则无法保证参数数量始终为 1。
- **自定义示例**：给类添加 `infix` 修饰的成员方法，即可获得中缀调用能力。Kotlin 仍然支持用普通方法语法调用中缀函数，但中缀形式更像自然语言，更加优雅。
  ```kotlin
  class Person {
      infix fun called(name: String) {
          println("My name is ${name}.")
      }
  }

  fun main(args: Array<String>) {
      val p = Person()
      p called "Shaw"    // 中缀调用，运行结果：My name is Shaw.
      p.called("Shaw")   // 等价于普通方法的调用方式
  }
  ```
- **顺带了解可变参数（vararg）**：Kotlin 通过 `varargs` 关键字定义可变参数，效果类似 Java 的 `...`。与 Java 不同，Kotlin 的可变参数不一定是最后一个参数；二者都可在函数体内以数组方式使用可变参数变量，也可以用 `*`（星号）把外部数组展开传入。
  ```kotlin
  fun printLetters(vararg letters: String, count: Int): Unit {
      print("${count} letters are ")
      for (letter in letters) print(letter)
  }
  >>> printLetters("a", "b", "c", count = 3)
  3 letters are abc

  val letters = arrayOf("a", "b", "c")
  >>> printLetters(*letters, count = 3)
  3 letters are abc
  ```

---

## 第2篇 函数、Lambda 与集合

**Q18: 什么是匿名函数？为什么要用它？（2.3.5 匿名函数）**

- **概念与语法**：Kotlin 允许在缺省函数名的情况下直接定义一个函数，即匿名函数。它只是去掉了名字，其余语法与普通函数一致（仍需 `fun` 关键字、显式声明参数类型与返回值类型）。
  ```kotlin
  fun(country: Country): Boolean { // 没有函数名
      return country.continent == "EU" && country.population > 10000
  }
  ```
- **动机（临时行为无需复用）**：此前我们需要在 `CountryTest` 类中专门写一个具名的筛选方法，再通过 `countryTest::isBigEuropeanCountry` 方法引用来传递。但 Shaw 的需求很多是临时性的，不值得为每个一次性需求都新建一个类和方法。匿名函数可以直接嵌入调用处，连 `CountryTest` 类都不再需要，代码的简洁性更上一层楼。
  ```kotlin
  countryApp.filterCountries(countries, fun(country: Country): Boolean {
      return country.continent == "EU" && country.population > 10000
  })
  ```

**Q19: 匿名函数与 Lambda 表达式有什么区别？（2.3.5 / 2.3.6）**

- **相同点**：二者都是函数字面量（function literal），即"没有名字、以表达式形式存在的函数"，都能直接作为参数传递。
- **不同点（匿名函数更"完整"，Lambda 更"精简"）**：
  - 匿名函数必须写 `fun` 关键字，参数类型与返回值类型都要显式声明，函数体内用 `return` 返回。
  - Lambda 省略了 `fun`，由于编译器可以推导类型，参数部分只需保留变量名；`return` 关键字也可以省略，最后一个表达式的值就是返回值。
  ```kotlin
  // 匿名函数：完整但啰嗦
  countryApp.filterCountries(countries, fun(country: Country): Boolean {
      return country.continent == "EU" && country.population > 10000
  })
  // Lambda：编译器推导类型，省略 fun 与 return，用 -> 连接参数和返回值
  countryApp.filterCountries(countries, { country ->
      country.continent == "EU" && country.population > 10000
  })
  ```
- **本质关系**：Lambda 可以理解为"简化表达后的匿名函数"，实质上就是一种语法糖。

**Q20: 如何区分 `fun` 声明的函数与 Lambda 表达式？（2.3.7 函数、Lambda 和闭包）**

- **`fun` + 花括号（没有等号）**：最常见的代码块函数体，如果返回非 `Unit` 值必须带 `return`。
  ```kotlin
  fun foo(x: Int) { print(x) }
  fun foo(x: Int, y: Int): Int { return x * y }
  ```
- **`fun` + 等号**：单表达式函数体，可以省略 `return`。
  ```kotlin
  fun foo(x: Int, y: Int) = x + y
  ```
- **等号 + 花括号**：不管是 `val` 还是 `fun`，只要出现 `= { ... }` 的写法，构建的就是一个 Lambda 表达式，Lambda 的参数在花括号内部声明。若左侧是 `fun`，则它是 Lambda 表达式函数体，必须通过 `()` 或 `invoke()` 来调用。
  ```kotlin
  val foo = { x: Int, y: Int -> x + y }   // 调用：foo(1, 2) 或 foo.invoke(1, 2)
  fun foo(x: Int) = { y: Int -> x + y }   // 调用：foo(1)(2) 或 foo(1).invoke(2)
  ```
- **判断诀窍**：是否出现了"等号 + 花括号"结构。有等号且右边是花括号 → Lambda；有等号且右边是表达式 → 表达式函数体；只有花括号 → 代码块函数体。

**Q21: 什么是闭包？为什么说 Kotlin 中 Lambda 就是闭包？（2.3.7）**

- **定义**：匿名函数体、Lambda（以及局部函数、object 表达式）在语法上都存在 `{}`。由这对花括号包裹的代码块，如果访问了外部环境变量，就被称为一个**闭包**。它可以被当作参数传递或直接使用，简单理解就是"访问外部环境变量的函数"。
- **Lambda 即闭包**：Lambda 是 Kotlin 中最常见的闭包形式。前面例子中 `{ country -> ... }` 之所以能读取 `country`，正是因为它是定义它的环境的一部分。
- **底层原理（JVM 层）**：Kotlin 把 Lambda 编译为实现了 `FunctionN` 接口的对象（每个 `Function` 类型都有一个 `invoke` 方法），该对象内部捕获了其定义环境中的外部变量（持有这些变量的引用），因此无论闭包被传递到哪里调用，都能访问到定义它时的环境。
  ```kotlin
  val sum = 0
  listOf(1, 2, 3).forEach { sum + it } // Lambda 捕获了外部变量 sum
  ```

**Q22: Kotlin 的闭包与 Java 有什么不同？什么是"自运行 Lambda"？（2.3.7）**

- **Kotlin 闭包可以修改外部变量**：Java 的 Lambda 只能读取外部（effectively final）变量，而 Kotlin 中的闭包不仅能够访问外部变量，还能够对其进行修改。因为闭包对象持有的是对外部变量的引用，修改会直接作用于原变量。
  ```kotlin
  var sum = 0
  listOf(1, 2, 3).filter { it > 0 }.forEach {
      sum += it // 修改外部变量 sum
  }
  println(sum) // 6
  ```
- **自运行 Lambda**：Kotlin 还支持一种自运行的 Lambda 语法——在 Lambda 后紧跟括号即可立即执行（相当于立即调用 `invoke`），这在某些需要局部作用域的场景下很方便。
  ```kotlin
  { x: Int -> println(x) }(1) // 打印 1
  ```

**Q103: 为什么说 Lambda 是本章的主角？它与集合、内联函数之间是什么关系？**

- **Lambda 贯穿集合 API**：Kotlin 的集合操作库中，Lambda 已经被广泛使用。用 Lambda 操作集合，会让代码变得非常简洁优雅。
- **优雅的代价**：简洁和优雅是有代价的——在 Kotlin 中使用 Lambda 表达式会带来额外的开销（每声明一个 Lambda 都会在字节码中产生一个匿名类）。
- **解决问题的钥匙**：为了消除这种开销，本章最后会介绍内联函数（inline）。因此本章的组织逻辑是：先讲用 Lambda 简化表达 → 再讲大量使用 Lambda 的集合高阶 API → 最后讲解决 Lambda 开销的内联函数。

**Q104: 如何在 Kotlin 中调用 Java 的函数式接口（SAM）？（以 Android 点击事件为例）**

- **背景痛点**：当今大部分类库还是用 Java 实现的，比如 Android 工程中有大量的 Java 接口方法。Kotlin 虽然拥有真正的函数类型，但首先必须解决与 Java 函数式接口打交道的问题。
- **Java 原始写法**：给视图绑定点击事件时，传统 Java 写法是创建匿名类：
  ```java
  view.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) { ... }
  });
  ```
  其中 `OnClickListener` 在 Java 中定义如下，它是一个函数式接口（只含一个抽象方法）：
  ```java
  public interface OnClickListener {
      void onClick(View v);
  }
  ```
- **Kotlin 的 SAM 转换**：Kotlin 允许对 Java 类库做优化——任何接收 Java 的 SAM（单一抽象方法）接口的方法，都可以用 Kotlin 的函数进行替代。上面的例子可以看成在 Kotlin 中定义了如下方法：
  ```kotlin
  fun setOnClickListener(listener: (View) -> Unit)
  ```
  于是可以用 Lambda 语法来简化：
  ```kotlin
  view.setOnClickListener({ ... })
  ```
- **语法糖：括号可省略**：由于 `listener` 是 `setOnClickListener` 的唯一参数，Kotlin 的特殊语法糖允许省略括号：
  ```kotlin
  view.setOnClickListener { ... }
  ```
- **为什么这样设计**：依靠 Kotlin 的 Lambda 语法，可以在很大程度上简化 Android 开发时的代码量，同时提升代码可读性。

**Q105: 什么是"带接收者的 Lambda"（Receiver Lambda）？它有什么用途？**

- **回顾扩展函数**：还记得扩展函数语法吗？在 Kotlin 中，我们还可以定义**带有接收者的函数类型**，即该函数类型本身声明了"哪个类可以作为它的接收者来调用"：
  ```kotlin
  val sum: Int.(Int) -> Int = { other -> plus(other) }
  >>> 2.sum(1)
  3
  ```
  此时可以用一个 `Int` 类型的变量调用 `sum` 方法，传入一个 `Int` 参数，对其执行 `plus` 操作。
- **本质**：`Int.(Int) -> Int` 表示"接收者为 `Int` 类型的函数"，在 Lambda 内部可以直接访问接收者对象的成员（如 `plus`），相当于把这个函数"挂"到了接收者类型上。
- **典型应用：类型安全构造器**：带接收者的 Lambda 非常适合构建类型安全的 HTML 代码。`html` 函数接收一个 `HTML.() -> Unit` 类型的参数，内部创建接收者对象并调用该 Lambda：
  ```kotlin
  class HTML {
      fun body() { ... }
  }

  fun html(init: HTML.() -> Unit): HTML {
      val html = HTML() // 创建了接收者对象
      html.init()       // 把接收者对象传递给 Lambda
      return html
  }

  html {
      body() // 调用接收者对象的 body 方法，无需写 html.body()
  }
  ```

**Q106: with 和 apply 是什么？它们是如何简化代码的？**

- **共同作用**：这两个方法最大的作用就是——在写 Lambda 的时候，省略需要多次书写的对象名，默认用 `this` 关键字来指向它。
- **with 的使用场景**：比如在 Android 开发中给视图控件绑定属性时，利用 `with` 可以让代码可读性更好：
  ```kotlin
  fun bindData(bean: ContentBean) {
      val titleTV = findViewById<TextView>(R.id.iv_title)
      val contentTV = findViewById<TextView>(R.id.iv_content)
      with(bean) {
          titleTV.text = this.title          // this 可以省略
          titleTV.textSize = this.titleFontSize
          contentTV.text = this.content
          contentTV.text = this.contentFontSize
      }
  }
  ```
  如果不使用 `with`，就需要写好多遍 `bean`。
- **with 的源码**：
  ```kotlin
  inline fun <T, R> with(receiver: T, block: T.() -> R): R
  ```
  第 1 个参数为接收者类型，第 2 个参数通过 `T.() -> R` 创建这个类型的 block 方法。因此在 Lambda 中可以直接用 `this` 代表该接收者对象。
- **apply 与 with 的区别**：`apply` 被声明为类型 `T` 的**扩展方法**，且 block 返回 `Unit`；而 `with` 的 block 可以返回任意类型 `R`：
  ```kotlin
  inline fun <T> T.apply(block: T.() -> Unit): T
  ```
- **二者的替代关系**：在很多情况下二者可以互相替代，上面的代码可以翻译成 apply 版本：
  ```kotlin
  fun bindData(bean: ContentBean) {
      val titleTV = findViewById<TextView>(R.id.iv_title)
      val contentTV = findViewById<TextView>(R.id.iv_content)
      bean.apply {
          titleTV.text = this.title          // this 可以省略
          titleTV.textSize = this.titleFontSize
          contentTV.text = this.content
          contentTV.text = this.contentFontSize
      }
  }
  ```
- **本质区别（用什么时候选谁）**：`with` 是"以对象为参数、返回 Lambda 结果"的顶层函数；`apply` 是"扩展方法、返回接收者自身（T）"——这决定了 apply 天然适合链式配置对象（返回的还是该对象），而 with 适合"围绕对象做一段计算并取回结果"的场景。

**Q107: map 是什么？为什么说它"以简驭繁"？**

- **痛点（Java 传统遍历）**：使用集合时，多数情况都需要遍历整个集合。在 Java 8 之前，要让数组每个元素都乘以 2 并返回新数组，需要写循环 + 中间数组：
  ```java
  int list[] = {1, 2, 3, 4, 5, 6};
  int newList[] = new int[list.length];
  for (int i = 0; i < list.length; i++) {
      newList[i] = list[i] * 2;
  }
  ```
- **Kotlin 的一行实现**：只需一行代码：
  ```kotlin
  val list = listOf(1, 2, 3, 4, 5, 6)
  val newList = list.map { it * 2 }
  ```
- **map 的本质**：`map` 是一个高阶函数，接收一个函数作为参数，对集合中每个元素执行该函数，收集返回结果组成新集合。因此 `map` 后会产生一个与原集合大小相同的新集合。也可以写成带参数的匿名函数形式或调用具名函数：
  ```kotlin
  val newList = list.map { el -> el * 2 }

  fun foo(bar: Int) = bar * 2
  val newList = list.map { foo(it) }
  ```
- **源码剖析**：
  ```kotlin
  public inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> {
      return mapTo(ArrayList<R>(collectionSizeOrDefault(10)), transform)
  }

  public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.mapTo(
      destination: C, transform: (T) -> R
  ): C {
      for (item in this)
          destination.add(transform(item))
      return destination
  }
  ```
  实现很简单：遍历集合，把 `transform` 方法产生的结果添加到新集合中并返回。
- **价值**：使用 `map` 免去了 for 语句，也不用定义中间变量，让"整体变换集合"这一高频操作变得极其简洁。

**Q108: 如何对集合进行筛选？filter、filterNot、filterNotNull、count 各有什么特点？**

- **场景引入**：定义一个学生列表，后续围绕它介绍集合 API：
  ```kotlin
  data class Student(val name: String, val age: Int, val sex: String, val score: Int)

  val jilen = Student("Jilen", 30, "m", 85)
  val shaw = Student("Shaw", 18, "m", 90)
  val yison = Student("Yison", 40, "f", 59)
  val jack = Student("Jack", 30, "m", 70)
  val lisa = Student("Lisa", 25, "f", 88)
  val pan = Student("Pan", 36, "f", 55)
  val students = listOf(jilen, shaw, yison, jack, lisa, pan)
  ```
- **filter**：筛选出满足条件的元素，返回新列表。例如获取所有男学生：
  ```kotlin
  val mStudents = students.filter { it.sex == "m" }
  ```
  与 `map` 类似，`filter` 接收一个函数，只是返回值类型必须是 `Boolean`——用于判断每一项是否满足条件，满足就插入新列表。
- **源码剖析**：
  ```kotlin
  public inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T> {
      return filterTo(ArrayList<T>(), predicate)
  }

  public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterTo(
      destination: C, predicate: (T) -> Boolean
  ): C {
      for (element in this) if (predicate(element))
          destination.add(element)
      return destination
  }
  ```
  逻辑很直白：遍历每个元素传入 `predicate`，返回 `true` 就保留，否则丢弃。
- **filter 家族**：
  - `filterNot`：过滤掉满足条件的元素，与 `filter` 作用相反（条件相同时得到相反结果）。
    ```kotlin
    val fStudents = students.filterNot { it.sex == "m" }
    ```
  - `filterNotNull`：过滤掉值为 `null` 的元素。
- **count：只数个数，不建列表**：统计满足条件的元素个数。要统计男女生各自人数：
  ```kotlin
  val countMStudent = students.count { it.sex == "m" }
  val countFStudent = students.count { it.sex == "f" }
  ```
- **为什么推荐 count 而不是 filter + size**：也可以写成 `students.filter { it.sex == "m" }.size`，但这种写法需要先通过 `filter` 得到一个新列表再统计数量，**增加了额外的开销**。`count` 直接遍历计数，不产生中间列表。

**Q109: Kotlin 提供了哪些求和方式？sumBy、sum、fold、reduce 有什么区别？**

- **痛点**：对集合求和十分常见，比如要算学生平均分就得先算总分。传统 for 循环写起来冗长：
  ```kotlin
  var scoreTotal = 0
  for (item in students) {
      scoreTotal = scoreTotal + item.score
  }
  ```
- **sumBy**：对集合每个元素映射出的数值求和，省去多余步骤：
  ```kotlin
  val scoreTotal = students.sumBy { it.score }
  ```
- **sum**：只对数值类型的列表求和，与 sumBy 类似但用法更直接：
  ```kotlin
  val a = listOf(1, 2, 3, 4, 5)
  val b = listOf(1.1, 2.5, 3.0, 4.5)
  val aTotal = a.sum()
  val bTotal = b.sum()
  // 当然也可以写成 a.sumBy { it }
  ```
- **fold：带初始值的折叠（核心是递归思想）**。先看源码：
  ```kotlin
  public inline fun <T, R> Iterable<T>.fold(initial: R, operation: (acc: R, T) -> R): R {
      var accumulator = initial
      for (element in this) accumulator = operation(accumulator, element)
      return accumulator
  }
  ```
  接收两个参数：第 1 个 `initial` 为初始值，第 2 个 `operation` 为函数。每次调用 `operation` 时传入两个参数——上一次调用的结果（第一次调用传初始值 `initial`）和当前遍历到的集合元素，然后把新结果传给下一次调用。简单说就是：**每次都调用 operation，并将结果作为参数提供给下一次调用**。
  ```kotlin
  val scoreTotal = students.fold(0) { accumulator, student ->
      accumulator + student.score
  }
  // 等价于：
  var accumulator = 0
  for (student in students) accumulator = accumulator + student.score
  ```
  还可以实现累乘等任意"折叠"：
  ```kotlin
  >>> val list = listOf(1, 2, 3, 4, 5)
  >>> list.fold(1) { mul, item -> mul * item }
  120
  ```
- **reduce：无初始值的 fold**。源码：
  ```kotlin
  public inline fun <S, T : S> Iterable<T>.reduce(operation: (acc: S, T) -> S): S {
      val iterator = this.iterator()
      if (!iterator.hasNext()) throw UnsupportedOperationException("Empty collection can't be reduced.")
      var accumulator: S = iterator.next()
      while (iterator.hasNext()) {
          accumulator = operation(accumulator, iterator.next())
      }
      return accumulator
  }
  ```
  与 fold 的唯一区别是**没有初始值**，默认以集合第 1 个元素作为初始累加值；因此当集合为空时会抛出异常。
  ```kotlin
  val scoreTotal = students.reduce { accumulator, student ->
      accumulator + student.score
  }
  ```
- **如何选择**：当不需要初始值时用 `reduce`；需要指定初始值（如从 0 或 1 开始，或处理空集合）时用 `fold`。

**Q110: 如何对集合进行分组？groupBy 解决了什么痛点？**

- **痛点（传统分组繁琐易错）**：按性别对学生分组，传统思路要定义多个中间变量并写 if/else 循环：
  ```kotlin
  fun groupBySex(students: List<Student>): Map<String, List<Student>> {
      val mStudents = ArrayList<Student>() // 定义男学生列表
      var fStudents = ArrayList<Student>() // 定义女学生列表
      // 遍历学生列表
      for (student in students) {
          if (student.sex == "m") {
              mStudents.add(student)
          } else if (student.sex == "f") {
              fStudents.add(student)
          }
      }
      return mapOf("m" to mStudents, "f" to fStudents)
  }
  ```
  不仅烦琐，还要定义许多中间变量，且容易出错。
- **groupBy 一行搞定**：Kotlin 提供了 `groupBy` 方法，分组之后的结果是一个 Map：
  ```kotlin
  >>> students.groupBy { it.sex }
  {m=[Student(name=Jilen, ...), Student(name=Shaw, ...), ...],
   f=[Student(name=Yison, ...), ...]}
  ```
  返回类型为 `Map<String, List<Student>>`，其中包含"男"和"女"两个分组。
- **设计价值**：`groupBy` 把"遍历 + 条件分发 + 组装 Map"这一整段过程抽象成一个参数（分组键的提取函数），让分组这一高频需求变得既安全又直观。

**Q111: 如何扁平化处理嵌套集合？flatMap 和 flatten 有什么区别？**

- **场景**：集合的元素也可能是集合，这种嵌套集合在业务中经常碰到。但大多数时候我们希望把嵌套集合中的元素都拿出来，组成一个只有这些元素的集合：
  ```kotlin
  val list = listOf(
      listOf(jilen, shaw, lisa),
      listOf(yison, pan),
      listOf(jack)
  )
  // 目标是得到
  val newList = listOf(jilen, shaw, lisa, yison, pan, jack)
  ```
- **flatten：只扁平化，不加工**。源码很简单：新建一个结果数组，遍历嵌套集合，把每个子集合的元素通过 `addAll` 添加进去：
  ```kotlin
  public fun <T> Iterable<Iterable<T>>.flatten(): List<T> {
      val result = ArrayList<T>()
      for (element in this) {
          result.addAll(element)
      }
      return result
  }
  ```
  使用：
  ```kotlin
  >>> list.flatten()
  [Student(name=Jilen, ...), Student(name=Shaw, ...), ...]
  ```
- **flatMap：先加工再扁平化**。如果希望将子集合中的元素"加工"一下再返回，例如得到一个由姓名组成的列表：
  ```kotlin
  >>> list.flatMap { it.map { it.name } }
  [Jilen, Shaw, Lisa, Yison, Pan, Jack]
  ```
- **为什么 flatMap 可以"代替" flatten + map？** 看下面的等价写法：
  ```kotlin
  >>> list.flatten().map { it.name }
  [Jilen, Shaw, Lisa, Yison, Pan, Jack]
  ```
  这个例子中 flatMap 相当于"先 flatten 再 map"。但换个场景结论就反过来了——给 Student 增加 `hobbies` 属性（爱好是列表）后，要取出所有学生的爱好：
  ```kotlin
  data class Student(val name: String, val age: Int, val sex: String,
                     val score: Int, val hobbies: List<String>)
  val jilen = Student("Jilen", 30, "m", 85, listOf("coding", "reading"))
  ...
  >>> students.map { it.hobbies }.flatten()
  [coding, reading, drinking, fishing, running, game, drawing, writing, dancing]
  >>> students.flatMap { it.hobbies }
  [coding, reading, drinking, fishing, running, game, drawing, writing, dancing]
  ```
  这个例子中 flatMap 又相当于"先 map 再 flatten"，且更简洁。
- **源码剖析**：flatMap 的真正实现是遍历集合，把每个元素经 `transform` 得到的列表整体 `addAll` 到目标列表：
  ```kotlin
  public inline fun <T, R> Iterable<T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
      return flatMapTo(ArrayList<R>(), transform)
  }

  public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.flatMapTo(
      destination: C, transform: (T) -> Iterable<R>
  ): C {
      for (element in this) {
          val list = transform(element)
          destination.addAll(list)
      }
      return destination
  }
  ```
  即：`transform` 接收一个参数（嵌套列表中的某个子列表/元素），返回一个列表，然后整体追加进结果。
- **本质与选择**：`flatMap` 可以看作由 `flatten` 和 `map` 组合而成（组合方式根据具体情况而定）。**只做扁平化用 `flatten`；需要对元素做"加工"再用 `flatMap`**。

**Q112: Kotlin 集合库是如何设计的？集合之间有哪些继承关系？**

- **总体结构（图 6-1）**：`Iterable` 是 Kotlin 集合库的顶层接口。每个集合都分为两种：**带 `Mutable` 前缀的**（可变）和**不带前缀的**（只读）。比如常见的列表就分为 `MutableList` 和 `List`：`List` 实现了 `Collection` 接口，`MutableList` 实现了 `MutableCollection` 和 `List`。
- **基于 Java 的构建**：Kotlin 的集合都是以 Java 集合库为基础构建的，通过扩展函数增强了它。实际上 Kotlin 的集合基本与 Java 的一样，不同之处在于 Kotlin 把集合分成了可变集合与只读集合。
- **List（有序、可重复）**：表示一个有序的、可重复的列表，元素线性存储以保证有序性：
  ```kotlin
  >>> listOf(1, 2, 3, 4, 4, 5, 5)
  [1, 2, 3, 4, 4, 5, 5]
  ```
- **Set（不可重复）**：常用实现有 `HashSet` 和 `TreeSet`。`HashSet` 用 Hash 散列存放数据，不能保证有序性；`TreeSet` 底层是二叉树，能保证元素有序。不指定具体实现时一般认为 Set 是无序的，且元素不能重复：
  ```kotlin
  >>> setOf(1, 2, 3, 4, 4, 5, 5)
  [1, 2, 3, 4, 5]  // 重复元素被过滤掉了
  ```
- **Map（键值对，键不可重复）**：`Map` 与其他集合不同，它没有实现 `Iterable` 或 `Collection`，用来表示键值对元素集合，键不能重复：
  ```kotlin
  >>> mapOf(1 to 1, 2 to 2, 3 to 3)
  {1=1, 2=2, 3=3}
  ```

**Q113: 可变集合与只读集合有什么区别？为什么 Kotlin 的"只读集合"并不总是安全的？**

- **可变集合**：可以改变元素的集合，都带 `Mutable` 前缀，如 `MutableList`：
  ```kotlin
  >>> val list = mutableListOf(1, 2, 3, 4, 5)
  >>> list[0] = 0
  >>> list
  [0, 2, 3, 4, 5]
  ```
- **只读集合**：一般情况下的元素不可修改，如 `listOf(1, 2, 3, 4, 5)`。如果尝试 `list[0] = 0`，会编译报错（`unresolved reference` / `no set method providing array access`）。
- **报错原因**：`list[0] = 0` 实际上是在调用 `set` 方法，而 Kotlin 的只读集合中**没有 `set` 方法**，所以不能修改其中的值。本质区别就是：**Kotlin 把可变集合中的修改、添加、删除等方法移除后，原来的可变集合就变成了只读集合**。
- **设计动机（更安全的代码）**：只读集合只保留"读"的方法（获取大小、遍历等），好处是让代码更容易理解，某种程度上也更安全。例如：
  ```kotlin
  fun merge(a: List<Int>, b: MutableList<Int>) = {
      for (item in a) {
          b.add(item)
      }
  }
  ```
  很容易看出 `merge` 不会修改 `a`（只读），但很可能修改 `b`（可变）。
- **为什么叫"只读"而不叫"不可变"**：Kotlin 中暂时还没有不可变集合，只能称为只读集合——因为**在某些情况下只读集合确实可以被改变**。
- **场景一：只读集合持有可变集合的引用**。`MutableList` 是 `List` 的子类，只读引用与可变引用指向同一对象时，通过可变引用修改后，只读引用"看到"的集合也会变：
  ```kotlin
  >>> val writeList: MutableList<Int> = mutableListOf(1, 2, 3, 4)
  >>> val readList: List<Int> = writeList
  >>> writeList[0] = 0
  >>> readList
  [0, 2, 3, 4]
  ```
- **场景二：与 Java 互操作**。Java 中不区分只读集合与可变集合，Kotlin 集合传给 Java 方法后可能被直接修改：
  ```java
  public static List<Integer> foo(List<Integer> list) {
      for (int i = 0; i < list.size(); i++) {
          list.set(i, list.get(i) * 2);
      }
      return list;
  }
  ```
  ```kotlin
  fun bar(list: List<Int>) {
      println(foo(list))
  }
  >>> val list = listOf(1, 2, 3, 4)
  >>> bar(list)
  [2, 4, 6, 8]
  >>> list
  [2, 4, 6, 8]   // 只读的 list 被 Java 方法改变了！
  ```
- **结论**：只读集合在某些情况下是安全的，但并不总是安全的。当只读集合持有可变集合的引用，或与 Java 互操作时，需要额外注意。

**Q114: 为什么要引入惰性集合（Sequence/序列）？惰性求值解决了什么问题？**

- **痛点（链式操作产生大量中间集合）**：类似 `list.filter { it > 2 }.map { it * 2 }` 的写法很简洁，但当 `list` 元素非常多（比如超过 10 万）时会比较低效。因为 `filter` 和 `map` 都会返回新集合，上面的操作会**产生两个临时集合**，这是不小的开销。
- **序列的出现**：为了解决这一问题，Kotlin 引入了序列（Sequence）：
  ```kotlin
  list.asSequence().filter { it > 2 }.map { it * 2 }.toList()
  ```
  先用 `asSequence()` 把列表转换为序列，在序列上操作，最后用 `toList()` 转回列表。
- **为什么高效**：使用序列时，`filter` 和 `map` 的操作**都不会创建额外的集合**，集合元素数量巨大时能减少大部分开销。
- **惰性求值的定义**：在编程语言理论中，**惰性求值（Lazy Evaluation）** 表示一种在需要时才进行求值的计算方式。表达式不在绑定到变量后就立即求值，而是在该值被取用时才去求值。
- **惰性求值的两大好处**：一是**优化性能**（链式求值时无需像普通集合那样每操作一次就产生一个新集合保存中间数据）；二是**可以构造出无限的数据类型**。

**Q115: 序列是如何工作的？什么是中间操作和末端操作？**

- **两类操作**：序列的操作分为两类。以 `list.asSequence().filter { it > 2 }.map { it * 2 }.toList()` 为例：`filter` 和 `map` 返回的还是序列，称为**中间操作**；`toList()` 把序列转换为 List，返回明确结果，称为**末端操作**。
- **中间操作（惰性求值）**：每次中间操作返回的都是一个新序列，内部知道如何去变换原来序列中的元素，且都采用惰性求值：
  ```kotlin
  list.asSequence().filter {
      println("filter($it)")
      it > 2
  }.map {
      println("map($it)")
      it * 2
  }
  // 结果
  kotlin.sequences.TransformingSequence@7d8abe58
  ```
  可以看到 `println` 根本没有被执行——`filter` 和 `map` 的执行被延迟了，这就是惰性求值的体现。
- **末端操作（触发计算）**：末端操作返回的不能是序列，必须是明确的结果（列表、数字、对象等），一般放在链式操作末尾。执行末端操作时会触发中间操作的延迟计算，也就是把"被需要"这个状态打开：
  ```kotlin
  list.asSequence().filter {
      println("filter($it)")
      it > 2
  }.map {
      println("map($it)")
      it * 2
  }.toList()
  // 结果
  filter(1)
  filter(2)
  filter(3)
  map(3)
  filter(4)
  map(4)
  filter(5)
  map(5)
  [6, 8, 10]
  ```
- **对比：普通集合的执行方式（水平执行）**。同样操作不用序列时：
  ```kotlin
  list.filter {
      println("filter($it)")
      it > 2
  }.map {
      println("map($it)")
      it * 2
  }
  // 结果
  filter(1)
  filter(2)
  filter(3)
  filter(4)
  filter(5)
  map(3)
  map(4)
  map(5)
  [6, 8, 10]
  ```
  普通集合链式操作会先在 `list` 上调用 `filter` 产生结果列表，再在结果列表上执行 `map`。
- **关键差异（垂直执行 vs 水平执行）**：序列执行链式操作时，**会将所有操作应用在同一个元素上**——第 1 个元素执行完所有操作后，第 2 个元素再执行，以此类推。反映到例子上，就是元素 1 执行完 filter 后再执行 map，元素 2 也是同样。
- **实践建议**：序列返回结果还揭示了一个规律——元素 1、2 不满足 `it > 2` 的条件，所以接下来 `map` 操作就不会执行。**因此当 `filter` 和 `map` 的位置可以调换时，应优先使用 `filter`，这样会减少一部分开销。**

**Q116: 序列可以是无限的吗？如何用序列构造自然数数列？**

- **可行性**：惰性求值最大的好处之一就是可以构造出无限的数据类型。数列（如自然数数列）就是一个典型的无限数据类型。
- **为什么列表做不到**：构建列表必须列举出所有元素，而我们无法把自然数全部列举出来。但自然数是有规律的——后一个数永远是前一个数加 1，我们只需要实现一个"描述这种规律"的列表，就相当于实现了无限的数列。
- **generateSequence 实现**：
  ```kotlin
  val naturalNumList = generateSequence(0) { it + 1 }
  ```
  序列是惰性求值的，所以上面创建的序列不会把所有的自然数都列举出来，只有调用末端操作时才去列举我们需要的部分。比如取出前 10 个自然数：
  ```kotlin
  >>> naturalNumList.takeWhile { it <= 9 }.toList()
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
  ```
- **本质理解**：无限数列并不是把无限的数据结构穷举呈现出来，而只是**实现了一种表示无限的状态**，让使用者感觉它就是无限的。

**Q117: 序列与 Java 8 Stream 有何异同？**

- **相同点：Java 也能使用函数式风格 API**。Java 8 之后，Java 也能像 Kotlin 那样操作集合了。比如把性别为男的学生筛选出来：
  ```java
  students.stream().filter(it -> it.sex == "m").collect(toList());
  ```
  但相比 Kotlin，Java 的操作方式有些烦琐——必须先转换成 stream，操作完成后还要再转回 List。这是因为 **Java 8 的流和 Kotlin 的序列一样，也是惰性求值的**，也存在中间操作和末端操作，所以必须经过这一系列转换。这种操作方式与 Kotlin 的序列很相似。
- **不同点一：Stream 是一次性的**。Java 8 的流只能遍历一次，遍历完成后这个流就被"消费"掉了，必须创建新的 Stream 才能再遍历一次，和迭代器很像：
  ```java
  Stream<Student> studentsStream = students.stream();
  studentsStream.filter(it -> it.sex == "m").collect(toList());
  // 你不能再继续在这个 studentsStream 上操作了，需要重新创建
  studentsStream.filter(it -> it.sex == "f").collect(toList());
  ```
  而 Kotlin 的序列可以反复遍历。
- **不同点二：Stream 能够并行处理数据**。Java 8 的流可以在多核架构上并行处理，只需把 `stream` 换成 `parallelStream`：
  ```java
  students.parallelStream().filter(it -> it.sex == "m").collect(toList());
  ```
  并行处理数据这一特性是 **Kotlin 的序列目前还没有实现的地方**，如果需要处理多线程的集合，还需要依赖 Java。

**Q118: 为什么要使用内联函数？它如何优化 Lambda 的开销？（与 invokedynamic 对比）**

- **背景：Lambda 的额外开销**。在 Kotlin 中，每声明一个 Lambda 表达式，就会在字节码中产生一个匿名类，该匿名类包含一个 `invoke` 方法作为 Lambda 的调用方法，每次调用时还会创建一个新对象。Lambda 语法虽然简洁，但额外开销不少。
- **Kotlin 的现实约束（Android / Java 6）**：Kotlin 当下的首要目标是在 Android 平台提供良好的语言特性支持。Android 主要采用 Java 6 开发，Kotlin 要在其中引入 Lambda 语法，必须采用某种方法来优化 Lambda 带来的额外开销——也就是内联函数。
- **Java 的解决方式：invokedynamic**。与 Kotlin 在编译期通过硬编码生成 Lambda 转换类的机制不同，Java 在 SE 7 之后通过 `invokedynamic` 技术在运行期才产生相应的翻译代码。首次调用时触发产生匿名类替换中间码 `invokedynamic`，后续调用直接采用该匿名类的代码。其好处有：
  - 转换实现在运行时产生，字节码中只有固定的 `invokedynamic`，需要静态生成的类数量及字节码大小都显著减少；
  - 翻译策略隐藏在 JDK 库实现中，灵活性高，保证向后兼容的同时后期可不断优化升级；
  - JVM 天然支持 Lambda 的翻译和优化，开发者完全不用关心这个问题。
- **为什么 Kotlin 不用 invokedynamic**：最可能的原因是 **Kotlin 从一开始就需要兼容 Android 最主流的 Java 版本 SE 6**，这导致它无法通过 `invokedynamic` 来解决 Android 平台的 Lambda 开销问题。
- **Kotlin 的方案：内联函数**。作为另一种主流方案（C++、C# 也支持），Kotlin 用 `inline` 关键字修饰函数，使其成为内联函数。**内联函数的函数体在编译期被嵌入每一个被调用的地方**，以减少额外生成的匿名类数量，以及函数执行的时间开销。

**Q119: 内联函数的具体语法是怎样的？它把什么"粘贴"到了调用处？**

- **非内联的代价**：先看一个普通高阶函数：
  ```kotlin
  fun main(args: Array<String>) {
      foo {
          println("dive into Kotlin...")
      }
  }

  fun foo(block: () -> Unit) {
      println("before block")
      block()
      println("end block")
  }
  ```
  反编译成 Java 后，调用 `foo` 会产生一个 `Function0` 类型的 block 类，通过 `invoke` 方法执行，增加了额外的生成类和调用开销。
- **加上 inline 之后**：
  ```kotlin
  inline fun foo(block: () -> Unit) {
      println("before block")
      block()
      println("end block")
  }
  ```
  反编译后的 main 函数中，`foo` 的函数体代码以及被调用的 Lambda 代码都被粘贴到了相应调用的位置（相当于把 Lambda 函数体直接展开）。
- **价值**：如果这是一个工程中的公共方法，或者被嵌套在循环调用的逻辑体中，该方法势必会被调用很多次。通过 `inline` 语法可以彻底消除这种额外调用，节约开销。
- **典型应用：集合 API**。Kotlin 集合的函数式 API（如 `map`、`filter`）都被定义成了内联函数：
  ```kotlin
  inline fun <T, R> Array<out T>.map(
      transform: (T) -> R
  ): List<R>

  inline fun <T> Array<out T>.filter(
      predicate: (T) -> Boolean
  ): List<T>
  ```
  这很容易理解：这些方法都接收 Lambda 作为参数，同时都需要对集合元素进行遍历，把相应实现内联无疑非常适合。
- **内联函数不是万能的（应避免的情况）**：
  - JVM 对普通函数已经能根据实际情况智能判断是否进行内联优化，所以不需要对普通函数使用 `inline` 语法，那只会让字节码变得更复杂；
  - 尽量避免对具有大量函数体的函数进行内联，这会导致过多的字节码数量；
  - 一旦一个函数被定义为内联函数，便不能获取闭包类的私有成员，除非把它们声明为 `internal`。

**Q120: noinline 有什么作用？为什么有时需要让某个参数"不被内联"？**

- **问题**：如果在一个函数开头加上 `inline` 修饰符，那么它的函数体及所有 Lambda 参数都会被内联。但现实情况比较复杂——函数可能接收多个参数，我们只想内联其中部分 Lambda 参数，其他的不内联，该怎么办？
- **解决方案**：Kotlin 在引入 `inline` 的同时，也新增了 `noinline` 关键字。把它加在不想内联的参数开头，该参数便不会具有内联效果：
  ```kotlin
  fun main(args: Array<String>) {
      foo({
          println("I am inlined...")
      }, {
          println("I am not inlined...")
      })
  }

  inline fun foo(block1: () -> Unit, noinline block2: () -> Unit) {
      println("before block")
      block1()
      block2()
      println("end block")
  }
  ```
- **反编译结果**：带 `noinline` 的 `block2` 在调用处并没有被替换成函数体，而是保持原样（仍然是一个 `Function0` 对象，通过 `invoke` 调用）；而 `block1` 的代码被直接内联到了调用处。
- **为什么要这样设计**：某些 Lambda 参数可能需要被当作对象传递、存储或延迟执行（例如传给另一个非内联函数、放入集合等），此时它们不能（也不需要）被内联展开，`noinline` 就提供了这种精确控制。

**Q121: 什么是非局部返回？内联函数是如何支持它的？**

- **局部返回**：普通函数的 `return` 只在该函数的局部生效。例如：
  ```kotlin
  fun main(args: Array<String>) {
      foo()
  }

  fun localReturn() {
      return
  }

  fun foo() {
      println("before local return")
      localReturn()
      println("after local return")  // 依然会执行
      return
  }
  // 运行结果
  before local return
  after local return
  ```
- **Lambda 中不能直接 return**：把函数换成 Lambda 版本后：
  ```kotlin
  fun main(args: Array<String>) {
      foo { return }
  }

  fun foo(returning: () -> Unit) {
      println("before local return")
      returning()
      println("after local return")
      return
  }
  // 运行结果
  Error: 'return' is not allowed here
  ```
  正常情况下，Kotlin 的 Lambda 表达式不允许出现 `return` 关键字。
- **内联使非局部返回成为可能**：把 `foo` 声明为内联函数后：
  ```kotlin
  fun main(args: Array<String>) {
      foo { return }
  }

  inline fun foo(returning: () -> Unit) {
      println("before local return")
      returning()
      println("after local return")
      return
  }
  // 运行结果
  before local return
  ```
  编译通过了，但结果与局部返回不同：Lambda 中的 `return` 执行后**直接让 `foo` 函数退出了执行**（`after local return` 没有被打印）。
- **原因（为什么）**：内联函数的函数体及参数 Lambda 会直接替代具体的调用，所以实际产生的代码中，`return` 相当于是**直接暴露在 main 函数中**，`returning()` 之后的代码自然不会被执——这就是**非局部返回**。
- **另一种等效方式：用标签实现**。可以在不声明 `inline` 的情况下，通过 `@` 标签实现相同的效果：
  ```kotlin
  fun main(args: Array<String>) {
      foo { return@foo }
  }

  fun foo(returning: () -> Unit) {
      println("before local return")
      returning()
      println("after local return")
      return
  }
  // 运行结果
  before local return
  ```
- **典型应用：循环控制**。非局部返回在循环控制中特别有用，比如 Kotlin 的 `forEach` 接收一个 Lambda 参数，由于它也是内联函数，可以直接在 Lambda 中执行 `return` 退出上一层程序：
  ```kotlin
  fun hasZeros(list: List<Int>): Boolean {
      list.forEach {
          if (it == 0) return true // 直接返回函数结果
      }
      return false
  }
  ```

**Q122: crossinline 是做什么的？它如何限制非局部返回？**

- **问题来源**：非局部返回虽然在某些场合下非常有用，但也可能带来危险——因为有时候内联函数所接收的 Lambda 参数常常来自上下文的其他地方。
- **作用**：为了避免带有 `return` 的 Lambda 参数产生破坏，可以使用 `crossinline` 关键字来修饰该参数，从而杜绝此类问题：
  ```kotlin
  fun main(args: Array<String>) {
      foo { return }
  }

  inline fun foo(crossinline returning: () -> Unit) {
      println("before local return")
      returning()
      println("after local return")
      return
  }
  // 运行结果
  Error: 'return' is not allowed here
  ```
- **本质**：`crossinline` 修饰后，Lambda 参数依然会被内联，但其中的 `return` 不允许进行非局部返回（编译报错），只能在 Lambda 内部以局部方式返回。也就是说，它**保留了内联的性能优势，同时封堵了非局部返回可能造成的控制流破坏**。

**Q123: 什么是具体化参数类型？reified 是如何解决泛型类型擦除问题的？**

- **问题背景（类型擦除）**：Kotlin 与 Java 一样，由于运行时的类型擦除，并不能直接获取一个泛型参数的具体类型。
- **reified 的魔法**：由于内联函数会直接在字节码中生成相应的函数体实现，这种情况下反而可以获得参数的具体类型。用 `reified` 修饰符即可实现：
  ```kotlin
  fun main(args: Array<String>) {
      getType<Int>()
  }

  inline fun <reified T> getType() {
      print(T::class)
  }
  // 运行结果
  class kotlin.Int
  ```
- **为什么内联函数可以做到**：普通泛型函数在运行时会丢失类型信息（类型擦除）；而内联函数的函数体在编译期被嵌入调用处，此时 `T` 被替换成了实际类型，所以 `T::class` 能拿到具体类型。
- **Android 实战价值**：这个特性在 Android 开发中格外有用。Java 中调用 `startActivity` 时通常需要把具体的目标 Activity 类作为参数；Kotlin 中可以用 `reified` 简化：
  ```kotlin
  inline fun <reified T : Activity> Activity.startActivity() {
      startActivity(Intent(this, T::class.java))
  }
  ```
  这样进行视图导航就非常容易了：
  ```kotlin
  startActivity<DetailActivity>()
  ```
  无需手动传入 `DetailActivity::class.java`，类型参数直接由编译器确定。

**Q138: 标准库中的 `run` 扩展函数是什么？它有什么典型应用？（7.2.4）**

- **定义**：`run` 是利用扩展实现的通用函数：
  ```kotlin
  public inline fun <T, R> T.run(block: T.() -> R): R = block()
  ```
  简单来说，`run` 是任何类型 T 的通用扩展函数，`run` 中执行了返回类型为 R 的扩展函数 `block`，最终返回该扩展函数的结果。
- **独立作用域（变量屏蔽）**：在 `run` 函数中拥有一个单独的作用域，能够重新定义一个 `nickName` 变量，且它的作用域只存在于 `run` 函数中：
  ```kotlin
  fun testFoo() {
      val nickName = "Prefert"
      run {
          val nickName = "YarenTang"
          println(nickName) // YarenTang
      }
      println(nickName) // Prefert
  }
  ```
- **返回最后一行对象**：这个范围函数本身似乎不是很有用，但相比"作用域"，还有一点不错的是——它返回范围内最后一个对象。例如用户点击领取新人奖励的按钮时，若未登录则弹出 `loginDialog`，若已登录则弹出领取奖励的 `getNewAccountDialog`，可以用以下代码处理：
  ```kotlin
  run {
      if (!islogin) loginDialog else getNewAccountDialog
  }.show()
  ```
  直接对 `run` 的返回值调用 `.show()`，简洁地消除了 if/else 的样板。

**Q139: `let` 扩展函数是什么？与 `apply` 有何区别？（7.2.4）**

- **定义**：`let` 在第 5 章介绍可空类型时接触过，其定义如下：
  ```kotlin
  public inline fun <T, R> T.let(block: (T) -> R): R = block(this)
  ```
- **与 `apply` 的本质区别（返回值不同）**：`let` 和 `apply` 类似，唯一不同的是返回值：`apply` 返回的是原来的对象，而 `let` 返回的是闭包里面的值。这决定了二者的使用场景——`apply` 适合"配置对象后继续使用该对象"，`let` 适合"对对象做计算并取得计算结果"。
- **结合可空类型的典型用法**：第 5 章介绍可空类型时大量使用了 `let` 语法：
  ```kotlin
  data class Student(age: Int)

  class Kot {
      val student: Student? = getStu()

      fun dealStu() {
          val result = student?.let {
              println(it.age)
              it.age
          }
      }
  }
  ```
- **语义**：由于 `let` 函数返回的是闭包的最后一行，当 `student` 不为 null 的时候才会打印并返回它的年龄。与 `run` 一样，它同样限制了变量的作用域；并且配合安全调用 `?.`，天然地规避了空指针问题。

**Q140: `also` 扩展函数是什么？与 `apply`、`let` 相比有何特点？（7.2.4）**

- **定义**：`also` 是 Kotlin 1.1 版本中加入的内容，它像是 `let` 和 `apply` 函数的"加强版"：
  ```kotlin
  public inline fun <T> T.also(block: (T) -> Unit): T {
      block(this)
      return this
  }
  ```
- **与 `apply` 一致的点**：与 `apply` 一致，它的返回值是该函数的接收者。
- **与 `apply` 不同的点（参数 vs 接收者）**：`also` 的 block 接收 `T` 作为普通参数（Lambda 里用 `it` 或自定义名字访问），而 `apply` 的 block 是 `T.() -> Unit`，接收者是 `this`。
- **典型示例（为什么用 also 而不是 apply）**：
  ```kotlin
  class Kot {
      val student: Student? = getStu()
      var age = 0

      fun dealStu() {
          val result = student?.also { stu ->
              this.age += stu.age
              println(this.age)
              println(stu.age)
              this.age
          }
      }
  }
  ```
  将隐式参数指定为 `stu`，假设 `student` 不为空，会发现返回了 `student`，并且总年龄 `age` 增加了。
- **关键差异（外部 this 可达性）**：如果使用 `apply`，由于它内部是一个扩展函数，`this` 将指向 `stu` 而不是 `Kot` 类，此处将无法调用到 `Kot` 下的 `age`。这正是 `also` 存在的意义——当需要在 Lambda 中同时访问"外部对象"和"被操作对象"时，`also` 用命名参数避免了 `this` 的歧义。

**Q141: `takeIf` / `takeUnless` 扩展函数是什么？与 `filter` 有何异同？（7.2.4）**

- **定义**：如果不仅仅想判空，还想加入条件，`let` 可能就显得不足了，这时可以用 `takeIf`：
  ```kotlin
  public inline fun <T> T.takeIf(predicate: (T) -> Boolean): T? = if (predicate(this)) this else null
  ```
  该函数也是在 Kotlin 1.1 中新增的。当接收器满足某些条件时它才会执行（返回接收者自身），否则返回 `null`。
- **典型用法**：如果想对成年的学生操作，可以这样写：
  ```kotlin
  val result = student.takeIf { it.age >= 18 }.let { ... }
  ```
- **与 `filter` 的异同**：这与第 6 章集合中的 `filter` 异曲同工，不过 `takeIf` 只操作单条数据（filter 操作整个集合）。
- **`takeUnless`**：与 `takeIf` 相反的还有 `takeUnless`，即接收器不满足特定条件才会执行。
- **补充**：除了这些函数外，Kotlin 标准库中还有很多方便的扩展函数，由于篇幅限制，剩余的乐趣留给读者自行探索。

---

## 第3篇 类与对象

**Q32: Kotlin 中如何声明一个类？与 Java 相比有哪些设计差异？（面向对象的开始）**

- **对象的两大组成**：任何可以描述的事物都可以看作对象。以鸟为例，**状态**（形状、颜色等静态属性，大小、年龄等动态属性）与**行为**（飞行、进食、鸣叫等动作）共同构成一个完整的对象。
- **类的声明方式**：Kotlin 依然使用熟悉的 `class` 结构体声明类，但把相同的代码反编译成 Java 后能发现显著差异：
  ```kotlin
  class Bird {
      val weight: Double = 500.0
      val color: String = "blue"
      val age: Int = 1
      fun fly() {} // 全局可见
  }
  ```
  反编译后的 Java 代码为 `public final class Bird`，属性变为 `private final`，并自动生成了对应的 `get` 方法。
- **不可变属性成员**：Kotlin 支持用 `val` 声明引用不可变的属性，底层是利用 Java 的 `final` 修饰符实现的；用 `var` 声明的属性则引用可变。
- **属性默认值**：Java 属性自带默认值（`int` 为 0，引用类型为 null），声明时可省略；而 Kotlin 中除非显式声明延迟初始化，否则必须为属性指定默认值。
- **不同的可访问修饰符**：Kotlin 类中的成员默认全局可见（public），而 Java 的默认可见域是包作用域，因此 Java 版本必须用 `public` 修饰才能达到同等效果。

**Q33: Kotlin 的接口相比 Java 有哪些增强？接口属性在底层是如何实现的？（DefaultImpls）**

- **带默认方法的接口**：Java 8 支持接口方法默认实现，Kotlin 同样支持，并且还支持声明抽象属性：
  ```kotlin
  interface Flyer {
      val speed: Int
      fun kind()
      fun fly() {
          println("I can fly")
      }
  }
  ```
- **底层实现原理（DefaultImpls）**：Kotlin 基于 Java 6，而 Java 6 并不支持接口默认方法。编译器通过定义一个静态内部类 `DefaultImpls` 来提供 `fly` 方法的默认实现，同时接口中的属性在 Java 源码中是通过 `get` 方法实现的。
- **接口属性的赋值限制**：接口中的属性不能像 Java 接口那样直接赋值常量，以下写法会报错：
  ```kotlin
  interface Flyer {
      val height = 1000 // error: Property initializers are not allowed in interfaces
  }
  ```
  正确的做法是利用 `getter` 返回常量：
  ```kotlin
  interface Flyer {
      val height
          get() = 1000
  }
  ```
- **设计背景**：Kotlin 接口属性背后其实是方法，若要给属性直接赋值常量，就需要编译器原生支持方法默认实现。但 Kotlin 基于 Java 6、不支持该特性，所以才要求用 `getter` 的写法。
- **普通接口属性**：`val height: Long` 与抽象方法一样，若没有指定默认行为，实现该接口的类中必须对该属性进行初始化。
- **总结**：Kotlin 的类与接口声明整体与 Java 相似，但语法更加简洁。

**Q34: Kotlin 为什么取消了 new 关键字？如何通过默认参数更简洁地构造类的对象？**

- **省略 new**：Kotlin 中没有 `new` 关键字，直接 `val bird = Bird()` 即可创建对象。
- **Java 构造方法重载的痛点**：为了支持不同的参数组合创建对象，Java 需要实现多个构造方法，存在两个缺点：
  - 要支持任意参数组合，构造方法的数量会非常多；
  - 每个构造方法内部存在大量冗余的赋值代码。
  ```java
  public Bird(double weight, int age, String color) { ... }
  public Bird(int age, String color) { ... }
  public Bird(double weight) { ... }
  ```
- **默认参数解决重载问题**：Kotlin 可以给构造方法参数指定默认值，一行代码就等价于 Java 的多个构造方法重载：
  ```kotlin
  class Bird(val weight: Double = 0.00, val age: Int = 0, val color: String = "blue")
  ```
  调用时最好指定参数名，否则必须按参数顺序赋值，否则会报类型不匹配错误：
  ```kotlin
  val bird1 = Bird(color = "black")
  val bird2 = Bird(weight = 1000.00, color = "black")
  ```
- **val/var 构造参数的深层含义**：构造参数名前的 `val`/`var` 不只是可变性声明，带上它们就等价于在类内部声明了同名的属性，可以用 `this` 调用。下面的写法等价于上面的 `Bird` 类：
  ```kotlin
  class Bird(
      weight: Double = 0.00, // 参数名前没有 val
      age: Int = 0,
      color: String = "blue"
  ) {
      val weight: Double
      val age: Int
      val color: String
      init {
          this.weight = weight
          this.age = age
          this.color = color
      }
  }
  ```

**Q35: 什么是 init 语句块？构造方法参数在类内部有哪些使用限制？**

- **init 语句块的概念**：`init` 语句块属于构造方法的一部分，但两者在表现形式上是分离的。构造方法在类的外部，只能对参数进行赋值；而如果需要在初始化时执行其他额外操作，就用 `init` 语句块：
  ```kotlin
  class Bird(weight: Double, age: Int, color: String) {
      init {
          println("do some other things")
          println("the weight is ${weight}")
      }
  }
  ```
- **构造参数的使用范围**：没有 `val`/`var` 的构造参数，可以在 `init` 语句块中直接调用，也可以用于初始化类内部的属性成员，但**不能在其他方法中使用**：
  ```kotlin
  class Bird(weight: Double, age: Int, color: String) {
      fun printWeight() {
          print(weight) // Unresolved reference: weight
      }
  }
  ```
- **多个 init 的顺序与作用**：类可以拥有多个 `init` 语句块，它们会在对象创建时按照类中从上到下的顺序先后执行。这有利于对初始化操作进行职能分离，在复杂的业务开发（如 Android）中特别有用。
- **利用 init 做派生计算**：有些属性不需要出现在构造参数列表中，可以基于已有参数在 `init` 中计算。例如根据颜色区分鸟的性别：
  ```kotlin
  class Bird(val weight: Double, val age: Int, val color: String) {
      val sex: String
      init {
          this.sex = if (this.color == "yellow") "male" else "female"
      }
  }
  ```
- **为什么不能放在普通方法中赋值**：Kotlin 规定类中所有非抽象属性成员都必须在对象创建时被初始化。若改为在 `printSex()` 方法中给 `sex` 赋值，会同时触发两个错误：属性未初始化（`Property must be initialized or be abstract`）以及 `val` 被二次赋值（`Val cannot be reassigned`）。

**Q36: 什么是延迟初始化？by lazy 与 lateinit 有什么区别？（Delegates.notNull）**

- **问题场景**：某些属性不想在对象创建时就有值，也不适合用 `var` 加默认值（可能产生错误语义）或引入可空类型（只是"稍后再赋值"，不希望真的可空），这时需要延迟初始化。
- **by lazy 的特点**：用于 `val` 声明的变量，首次调用时才进行赋值，一旦赋值后续不能再更改：
  ```kotlin
  class Bird(val weight: Double, val age: Int, val color: String) {
      val sex: String by lazy {
          if (color == "yellow") "male" else "female"
      }
  }
  ```
- **by lazy 的底层原理**：`lazy` 是一个接收 lambda 并返回 `Lazy<T>` 实例的函数。第一次访问属性时执行对应的 Lambda 表达式并记录结果，后续访问只是返回记录的结果。系统默认会加上同步锁（`LazyThreadSafetyMode.SYNCHRONIZED`），同一时刻只允许一个线程初始化该属性，因此是线程安全的。若确认无线程安全问题，可传入其他模式消除开销：
  ```kotlin
  val sex: String by lazy(LazyThreadSafetyMode.PUBLICATION) { ... } // 并行模式
  val sex: String by lazy(LazyThreadSafetyMode.NONE) { ... }        // 无任何线程开销与保证
  ```
- **lateinit 的特点**：与 `lazy` 不同，`lateinit` 主要用于 `var` 声明的变量，但不能用于基本数据类型（如 `Int`、`Long`），需要用 `Integer` 等包装类替代：
  ```kotlin
  class Bird(val weight: Double, val age: Int, val color: String) {
      lateinit var sex: String // sex 可以延迟初始化
      fun printSex() {
          this.sex = if (this.color == "yellow") "male" else "female"
          println(this.sex)
      }
  }
  ```
- **基本类型的延迟初始化**：若想让 `var` 声明的**基本数据类型**变量也具有延迟初始化效果，可用 `Delegates.notNull<T>` 委托语法实现：
  ```kotlin
  var test by Delegates.notNull<Int>()
  fun doSomething() {
      test = 1
      println("test value is ${test}")
  }
  ```
- **总结**：Kotlin 并不主张用 Java 的构造方法重载解决多参数组合的调用问题，而是用构造参数默认值以及 `val`/`var` 声明构造参数的语法，更简洁地构造类对象。

**Q37: 什么是主从构造方法？与 Java 的构造方法重载有何区别？**

- **问题场景**：有些时候需要从一个特殊的数据中获取构造参数值。例如已知鸟的生日，希望通过生日得到年龄来创建 `Bird` 对象。如果不想用"在别处定义工厂方法"这种代码分离、不够直观的方案，就需要额外的构造方法。
- **主从构造方法的定义**：通过 `constructor` 关键字定义的新构造方法被称为**从构造方法**，相应地，在类外部定义的构造方法被称为**主构造方法**：
  ```kotlin
  import org.joda.time.DateTime
  class Bird(age: Int) {
      val age: Int
      init {
          this.age = age
      }
      constructor(birth: DateTime) : this(getAgeByBirth(birth)) {
          // 从构造方法的代码块
      }
  }
  ```
- **基本规则**：
  - 每个类最多存在**一个主构造方法**和**多个从构造方法**；若主构造方法存在注解或可见性修饰符，也必须像从构造方法一样加上 `constructor` 关键字；
  - 每个从构造方法由两部分组成：对**其他构造方法的委托** + 花括号包裹的**自身代码块**。执行顺序上先执行委托的方法，再执行自身代码块逻辑。
- **委托链**：通过 `this` 关键字调用要委托的构造方法。如果类存在主构造方法，那么每个从构造方法都要直接或间接地委托给它（例如从构造方法 A 委托给 B，B 再委托给主构造方法）。
- **实际用途：扩展第三方 Java 类库**：从构造方法设计的一大作用是便于基于第三方 Java 库中的类扩展自定义构造方法。典型的例子是定制 Android 业务中特殊的 View 类：
  ```kotlin
  class KotlinView : View {
      constructor(context: Context) : this(context, null)
      constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
      constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
          // ...
      }
  }
  ```

**Q38: Kotlin 的限制修饰符 open/final/abstract 是怎么回事？为什么类默认是 final？**

- **Kotlin 的继承语法差异**：Kotlin 没有采用 Java 的 `extends` 和 `implements` 关键字，而是统一使用 `:` 表示类的继承和接口实现；并且由于 Kotlin 中类和方法的默认不可被继承或重写，必须加上 `open` 修饰符：
  ```kotlin
  open class Bird {
      open fun fly() {
          println("I can fly.")
      }
  }
  class Penguin : Bird() {
      override fun fly() {
          println("I can't fly actually.")
      }
  }
  ```
- **错误设计继承的典型场景**：企鹅虽属鸟类但不会飞，重写父类的 `fly` 方法是一种危险做法。若父类把方法签名改为 `fly(miles: Int)`，子类会报 `'fly' overrides nothing` 错误。因为 `Bird` 类代表的并不是生物学中的鸟类，而是会飞的鸟。
- **里氏替换原则**：子类可以扩展父类的功能，但不能改变父类原有的功能，包含 4 条原则：①子类可以实现父类的抽象方法，但不能覆盖父类的非抽象方法；②子类可以增加自己特有的方法；③子类实现父类方法时，前置条件（形参）要比父类更宽松；④子类实现父类抽象方法时，后置条件（返回值）要比父类更严格。《Effective Java》也提出："要么为继承做好设计并且提供文档，否则就禁止这样做。"
- **类的默认修饰符是 final**：Kotlin 认为类默认开放继承并不是好的选择。把简单的 `class Bird` 反编译成 Java，类、方法及属性前都多了 `final`。Java 中类默认可继承（除非主动加 `final`），Kotlin 恰好相反，默认不可继承（除非主动加 `open`）。因此 Kotlin 提倡重写父类的抽象方法，而不是重写非抽象方法。

**Q39: 类默认 final 真的好吗？Kotlin 官方是怎么辩证看待的？**

- **反对声音（社区批评）**：
  - **与某些框架实现冲突**：如 Spring 会利用注解私下对类进行增强（代理），由于 Kotlin 类默认不能被继承，可能导致框架的某些原始功能出现问题；
  - **第三方库扩展不便**：Kotlin 类库会更倾向于不开放类的继承（人总是偷懒的），默认 final 会阻挠对类库进行继承再扩展。Kotlin 官方论坛甚至为此做过投票，略超半数的人倾向于把 `open` 作为默认。
- **支持理由一（Android 平台定位）**：Kotlin 当前是以 Android 平台为主的开发语言，工程开发中很少频繁继承一个类，默认 final 更安全。若类默认 open 而忘记标记 final 会带来麻烦；反之默认 final 的类需要扩展时，即使没标记 open，编译器也会提醒，不存在隐患。且 Android 不存在类似 Spring 因框架本身产生的冲突。
- **支持理由二（更丰富的扩展手段）**：Kotlin 对类库的扩展手段比 Java 丰富，典型如 `android-ktx`，Google 官方通过**扩展语法**而非继承原始类的方式扩展 Android 标准库。这揭示：以往 Java 中没有扩展语法，常靠继承扩展类库，某些场景不一定合理；Kotlin 因增强了多态性支持，类默认 final 反而督促开发者思考更正确的扩展手段。此外，默认 final 与 Smart Casts 结合还能发挥更大作用。
- **密封类（sealed）**：除了 `final`，Kotlin 还可以用 `sealed` 关键字限制类的继承——子类必须定义在同一个文件中，其他文件中的类无法继承它：
  ```kotlin
  sealed class Bird {
      open fun fly() = "I can fly"
      class Eagle : Bird()
  }
  ```
  反编译后可见它基于抽象类实现（`public abstract class Bird`），所以它不能被直接初始化。密封类使用场景有限，可看作一种功能更强大的枚举，在模式匹配中作用很大。
- **总结**：Kotlin 中 `abstract` 与 Java 完全一样（修饰类表示抽象类、修饰方法表示抽象方法）。限制修饰符的整体差异是：Kotlin 更严格（默认 final），需要辩证看待。

**Q40: Kotlin 的可见性修饰符与 Java 有哪些不同？internal 是什么意思？（模块内可见）**

- **五大差异**：
  1. 默认修饰符不同：Kotlin 是 `public`，Java 是 `default`（包内可见）；
  2. Kotlin 有独特的修饰符 `internal`；
  3. Kotlin 可以在一个文件内单独声明方法及常量，同样支持可见性修饰符；
  4. Java 中除内部类外其他类都不允许用 `private` 修饰，而 Kotlin 可以（作用域为当前 Kotlin 文件）；
  5. `protected` 的访问范围不同：Java 中包、类及子类可访问，Kotlin 只允许类及子类。
- **默认 public 的动机**：Java 中大多数类都希望全局可访问，每次都要加 `public` 感觉是多余的声明。Kotlin 正是考虑到这一点，将默认可见性设为 `public`，无需显式声明。
- **internal 是什么**：`internal` 的作用域是"**模块内访问**"，一个模块可以看作一起编译的 Kotlin 文件组成的集合，包括：一个 Eclipse/IntelliJ IDEA/Maven/Gradle 项目，或一组由一次 Ant 任务执行编译的代码。
- **为什么不用 Java 的包内访问**：Java 包私有存在安全漏洞。假如把包私有的类打包成类库提供给其他项目，开发者只要在自己的工程中创建**与类库相同名字的包**，包下其他类就能直接访问这个类（只需 copy 源代码或伪造同包名）：
  ```java
  // 第三方类库代码
  package com.dripower;
  class TestDefault { ... }
  // 自身工程创建同名包 com.dripower 后即可直接访问
  package com.dripower;
  class Test {
      TestDefault td = new TestDefault(); // 合法！
  }
  ```
- **internal 如何解决**：Kotlin 默认采用模块内可见，开发工程与第三方类库不属于同一个模块，此时若还想使用该类，只能复制源码一种方式。
- **private 类与 protected 的差异**：Kotlin 可以用 `private` 修饰单独的一个类，作用域就是当前 Kotlin 文件；同一包下的其他类不能访问 `protected` 修饰的内容，但子类可以：
  ```kotlin
  package com.dripower.car
  class BMWCar(val name: String) {
      private val bMWEngine = Engine("BMW")
      fun getEngine(): String {
          return bMWEngine.engineType() // error: Cannot access 'engineType'
      }
  }
  private open class Engine(val type: String) {
      protected open fun engineType(): String {
          return "the engine type is $type"
      }
  }
  ```

**Q41: 为什么 Kotlin 默认的可见性修饰符是 public，而不是 internal？**

- **官方说明**：Kotlin 开发人员在官方论坛对此进行了说明。Kotlin 通过分析以往的大众开发代码，发现使用 `public` 修饰的内容远比使用其他修饰符的内容多。
- **设计决策**：为了保持语言的简洁性，Kotlin 考虑多数情况，最终决定将 `public` 当作默认修饰符。这也体现了 Kotlin 一贯的"实用主义"——为最高频的使用场景提供最省心的语法。

**Q42: 什么是"骡子的多继承困惑"（菱形继承问题）？Kotlin 为什么不支持类的多继承？**

- **问题由来**：C++ 的类支持多重继承机制，但存在经典的钻石问题。假设 Java 的类也支持多继承：
  ```java
  abstract class Animal {
      abstract public void run();
  }
  class Horse extends Animal { // 马
      public void run() { System.out.println("I am run very fast"); }
  }
  class Donkey extends Animal { // 驴
      public void run() { System.out.println("I am run very slow"); }
  }
  class Mule extends Horse, Donkey { // 骡子
      // ...
  }
  ```
- **歧义所在**：马和驴都继承了 `Animal` 并实现了 `run()`。骡子是马和驴的杂交产物，`Mule` 用多继承同时继承了 `Horse` 和 `Donkey`。当在 `Mule` 中调用 `run()` 时，到底继承 `Horse` 的还是 `Donkey` 的？这就是经典的**钻石问题**（因继承关系图呈菱形，也叫菱形继承问题）。
- **维护成本**：类的多重继承若使用不当，会在继承关系上产生歧义，且代码耦合度高，各种类之间的关系令人眼花缭乱。
- **Kotlin 的选择**：Kotlin 与 Java 一样只支持类的单继承。那么面对多继承的需求，Kotlin 提供了接口实现、内部类、委托等多种灵活的解决思路。

**Q43: 如何用接口实现多继承？接口默认方法冲突时如何用 super<T> 解决？**

- **用接口模拟多继承**：一个类可以实现多个接口，这是解决多继承问题的第一种方案。Kotlin 接口除了带默认实现的方法，还可以声明抽象属性：
  ```kotlin
  interface Flyer {
      fun fly()
      fun kind() = "flying animals"
  }
  interface Animal {
      val name: String
      fun eat()
      fun kind() = "flying animals"
  }
  class Bird(override val name: String) : Flyer, Animal {
      override fun eat() { println("I can eat") }
      override fun fly() { println("I can fly") }
      override fun kind() = super<Flyer>.kind()
  }
  fun main(args: Array<String>) {
      val bird = Bird("sparrow")
      println(bird.kind()) // 运行结果：flying animals
  }
  ```
- **钻石问题的接口版**：`Flyer` 和 `Animal` 都拥有默认的 `kind()` 方法，同样会引起歧义。Kotlin 用 `super` 关键字解决——用 `super<接口名>.方法名()` 指定继承哪个父接口的方法，也可以主动重写方法覆盖父接口的实现：
  ```kotlin
  override fun kind() = "a flying ${this.name}" // 运行结果：a flying sparrow
  ```
- **实现接口的语法要点**：
  1. 需实现接口中没有默认实现的方法及未初始化的属性；若同时实现多个接口且接口间有相同方法名的默认实现，必须主动指定使用哪个接口的方法或者重写方法；
  2. 默认接口方法可通过 `super<T>` 方式调用，其中 `T` 为拥有该方法的接口名；
  3. 实现接口的属性和方法时，都必须带上 `override` 关键字，不能省略。
- **接口属性实现的多种方式**：可通过主构造方法参数实现（`override val name` 会定义同名属性），也可以在类内部用 `init` 赋值，或用 `getter` 动态计算：
  ```kotlin
  class Bird(chineseName: String) : Flyer, Animal {
      override val name: String
          get() = translate2EnglishName(chineseName)
  }
  ```
- **getter/setter 的小知识**：Kotlin 类不存在字段、只有属性，编译器会自动生成 getter/setter。`val` 声明的属性只有 getter；`var` 声明的属性同时拥有 getter 和 setter；`private` 修饰的属性编译器会省略 getter/setter，因为类外部已无法访问，它们没有存在意义。
- **局限性**：用接口模拟多继承是最常用的方式，但有时在语义上依旧不够明确。

**Q44: Kotlin 中如何用内部类解决多继承问题？内部类与嵌套类有什么区别？**

- **思路**：内部类可以继承一个与外部类无关的类，保证了内部类的独立性，利用这个特性可以模拟多继承的效果。
- **Kotlin 内部类语法（inner）**：在 Java 中，类内部直接定义的类就是内部类。但 Kotlin 默认声明的是**嵌套类**（不是内部类），访问外部类属性会报错：
  ```kotlin
  class OuterKotlin {
      val name = "This is not Kotlin's inner class syntax."
      class ErrorInnerKotlin { // 其实是嵌套类
          fun printName() {
              print("the name is $name") // error: Unresolved reference: name
          }
      }
  }
  ```
  必须在类前面加 `inner` 关键字才是真正的内部类：
  ```kotlin
  class OuterKotlin {
      val name = "This is truely Kotlin's inner class syntax."
      inner class InnerKotlin {
          fun printName() {
              print("the name is $name")
          }
      }
  }
  ```
- **内部类 vs 嵌套类**：Java 是在内部类语法上加 `static` 变成嵌套类；Kotlin 恰好相反——默认是嵌套类，加 `inner` 才是内部类，可以把静态内部类看成嵌套类。两者的本质差别是：**内部类包含对其外部类实例的引用**，因此可以使用外部类的属性；**嵌套类不包含外部类实例引用**，无法调用外部类的属性。
- **用内部类改写骡子例子**：
  ```kotlin
  open class Horse { // 马
      fun runFast() { println("I can run fast") }
  }
  open class Donkey { // 驴
      fun doLongTimeThing() { println("I can do some thing long time") }
  }
  class Mule { // 骡子
      fun runFast() { HorseC().runFast() }
      fun doLongTimeThing() { DonkeyC().doLongTimeThing() }
      private inner class HorseC : Horse()
      private inner class DonkeyC : Donkey()
  }
  ```
- **这个方案的价值**：
  1. 一个类内部可以定义多个内部类，每个内部类的实例都有自己独立的状态，与外部对象的信息相互独立；
  2. 让内部类 `HorseC`、`DonkeyC` 分别继承 `Horse` 和 `Donkey`，就可以在 `Mule` 类中定义它们的实例对象，从而获得两者的不同状态和行为；
  3. 可以用 `private` 修饰内部类，使其他类都不能访问，封装性非常好。

**Q45: 如何使用委托（by）代替多继承？它相比接口多继承和组合有什么优势？**

- **委托的概念**：委托是一种特殊的类型，用于方法事件委托——调用 A 类的 `methodA` 方法，背后其实是 B 类的 `methodA` 在执行。Kotlin 用 `by` 关键字简化了委托语法，之前讲过的 `by lazy` 延迟初始化其实就是利用委托实现的：
  ```kotlin
  val laziness: String by lazy {
      println("I will have a value")
      "I am a lazy-initialized string"
  }
  ```
- **委托代替多继承**：通过 `by` 关键字把接口方法的实现委托给具体的类对象：
  ```kotlin
  interface CanFly {
      fun fly()
  }
  interface CanEat {
      fun eat()
  }
  open class Flyer : CanFly {
      override fun fly() { println("I can fly") }
  }
  open class Animal : CanEat {
      override fun eat() { println("I can eat") }
  }
  class Bird(flyer: Flyer, animal: Animal) : CanFly by flyer, CanEat by animal {}

  fun main(args: Array<String>) {
      val flyer = Flyer()
      val animal = Animal()
      val b = Bird(flyer, animal)
      b.fly() // I can fly
      b.eat() // I can eat
  }
  ```
- **委托的优势（vs 接口多继承与组合）**：
  1. **能力更强**：接口是无状态的，即使提供默认方法实现也很简单，不能实现复杂逻辑，也不推荐在接口中实现复杂方法逻辑。而委托虽然也是接口委托，但它是用**一个具体的类**去实现方法逻辑，能力更强大；
  2. **更直观**：假设需要继承的类是 A、委托对象是 B、C，具体调用时不像组合那样写 `A.B.method`，而是可以直接调用 `A.method`，这更能表达"A 拥有该 method 的能力"（虽然背后也是通过委托对象执行）。
- **其他委托内置行为**：委托还提供可观察属性的行为（类似观察者模式），在 Android 开发中应用很广。

**Q46: 为什么说 JavaBean 很烦琐？（数据类的痛点）**

- **初衷**：有时我们并不想要那么强大的类，只是单纯地使用类来封装数据，类似于 Java 中的 DTO（Data Transfer Object）。
- **JavaBean 的样板代码**：定义数据模型类需要为每个属性定义 getter/setter；要支持对象值比较，还要重写 `hashcode`、`equals`、`toString` 等方法。一个只有 3 个属性的 `Bird` JavaBean 代码量竟然足有 60 多行：
  ```java
  public class Bird {
      private double weight;
      private int age;
      private String color;
      public void fly() { }
      // 构造方法 + getter/setter + equals + hashCode + toString……
  }
  ```
- **问题的本质**：你的初衷无非是想要一个单纯封装数据的类而已，最后却变成了一堆样板式代码。属性越多，JavaBean 的代码量越失控。虽然 IDE 可以自动生成这些代码，但冗长的代码依然令人厌烦。
- **Kotlin 的解法**：引入 `data class` 语法来改善这一状况（这不是 Kotlin 首创，Scala 中就有 case class）。

**Q47: 如何用 data class 创建数据类？编译器在背后自动生成了什么？**

- **一行代码代替 60 行**：用 `data class` 声明数据类，只需一行代码：
  ```kotlin
  data class Bird(var weight: Double, var age: Int, var color: String)
  ```
  这一切无非是添加了一个 `data` 关键字，但反编译后的 Java 代码揭示编译器帮我们做了很多事情：自动生成 getter/setter、构造方法、`equals`、`hashCode`、`toString`，以及 Java 中没有的两个特殊方法 `copy` 和 `componentN`。
- **判等能力**：`equals` 和 `hashCode` 使数据类对象可以像普通类型的实例一样进行判等，甚至能像基本数据类型一样直接用 `==` 判断两个对象相等：
  ```kotlin
  val b1 = Bird(weight = 1000.0, age = 1, color = "blue")
  val b2 = Bird(weight = 1000.0, age = 1, color = "blue")
  b1.equals(b2) // true
  b1 == b2      // true
  ```
- **设计理念**：data class 让你只关心真正的数据，而不是一堆烦琐的模板代码；编译器生成的这些方法使得数据类可以像 Map 一样作为数据结构被广泛运用到业务中，又比 Map 更灵活——它像一个普通类，可以把不同类型的值封装在一处。

**Q48: copy 方法是如何工作的？"浅拷贝"意味着什么？**

- **copy 的作用**：`copy` 方法帮助我们从已有的数据类对象中拷贝一个新的数据类对象，可以传入相应参数生成不同的对象。反编译后可见一个配套的 `copy$default` 方法，其逻辑是：**若未指定具体属性的值，新对象的属性值使用被 copy 对象的属性值**。
- **浅拷贝的本质**：除了基本数据类型的属性，其他属性还是引用同一个对象。例如 Java 中常见的写法会"意外"地相互影响：
  ```java
  Bird b1 = new Bird(20.0, 1, "blue");
  Bird b2 = b1;
  b2.setColor("red");
  System.out.println(b1.getColor()); // red —— 明明改的是 b2，b1 却变了
  ```
  这其实是引用赋值而非真正的拷贝，除了基本类型属性，其他属性仍引用同一个对象，这便是浅拷贝的特点。
- **copy 是语法糖**：假如数据类的属性不可变（用 `val` 声明），只能通过 `copy` 基于原有对象生成新对象：
  ```kotlin
  // 声明的 Bird 属性可变
  val b1 = Bird(20.0, 1, "blue")
  val b2 = b1
  b2.age = 2
  // 声明的 Bird 属性不可变
  val b1 = Bird(20.0, 1, "blue")
  val b2 = b1.copy(age = 2) // 只能通过 copy
  ```
- **使用注意**：`copy` 提供了一种简洁的复制方式，但它是浅拷贝。由于数据类的属性可以被修饰为 `var`，这便不能保证不会出现引用修改问题，所以使用 `copy` 时要注意使用场景。

**Q49: componentN 与解构声明是什么？背后是怎样的编译约定？**

- **componentN 的含义**：`componentN` 可以理解为类属性的值，其中 `N` 代表属性的顺序（`component1` 是第 1 个属性的值，`component3` 是第 3 个属性的值）。
- **解决什么问题**：我们知道怎么把属性绑定到类上，却不熟悉如何把类的属性解绑到相应变量上。解构声明提供了优雅做法：
  ```kotlin
  val b1 = Bird(20.0, 1, "blue")
  // 通常方式
  val weight = b1.weight
  val age = b1.age
  val color = b1.color
  // Kotlin 进阶：解构声明
  val (weight, age, color) = b1
  ```
- **对比 Java 的烦琐**：Java 中把 `"20.0,1,bule"` 这样的字符串拆开赋给变量，需要 `split` 后分多步 `valueOf` 赋值；Kotlin 直接一行解构：
  ```kotlin
  val (weight, age, color) = birdInfo.split(",")
  ```
  其原理也很简单：**解构**，通过编译器的约定实现。
- **数组解构的限制**：数组中默认最多允许赋值 5 个变量，因为变量过多效果会适得其反——到后期都搞不清楚哪个值赋给哪个变量。所以一定要合理使用这一特性。
- **自定义 componentN**：除了编译器自动生成，也可以自己实现对应属性的 `componentN` 方法（注意 `operator` 关键字）：
  ```kotlin
  data class Bird(var weight: Double, var age: Int, var color: String) {
      var sex = 1
      operator fun component4(): Int {
          return this.sex
      }
      constructor(weight: Double, age: Int, color: String, sex: Int) : this(weight, age, color) {
          this.sex = sex
      }
  }
  fun main(args: Array<String>) {
      val b1 = Bird(20.0, 1, "blue", 0)
      val (weight, age, color, sex) = b1
  }
  ```
- **Pair 与 Triple**：Kotlin 提供了两个常用且不必主动声明的数据类。`Pair` 是二元组、`Triple` 是三元组，属性可以是任意类型，可按顺序取值或解构：
  ```kotlin
  public data class Pair<out A, out B>(public val first: A, public val second: B)
  public data class Triple<out A, out B, out C>(public val first: A, public val second: B, public val third: C)
  // 使用
  val (weightP, ageP) = Pair(20.0, 1)
  val (weightT, ageT, colorT) = Triple(20.0, 1, "blue")
  ```
- **注意**：数据类中的解构基于 `componentN` 函数。如果自己不声明，就会默认根据**主构造方法参数**生成具体个数的 `componentN` 函数，与从构造方法中的参数无关。

**Q50: 声明数据类需要满足哪些约定与使用限制？**

- **声明数据类的必要条件**：
  1. 数据类必须拥有一个构造方法，该方法**至少包含一个参数**——一个没有数据的数据类是没有任何用处的；
  2. 与普通类不同，数据类构造方法的参数**强制使用 `var` 或 `val`** 声明；
  3. `data class` 之前**不能用 `abstract`、`open`、`sealed` 或 `inner` 修饰**；
  4. Kotlin 1.1 版本前数据类只允许实现接口，之后的版本既可以实现接口也可以继承类。
- **使用方式**：数据类语法简洁，可以像 Map 一样作为数据结构被广泛运用到业务中。把数据类和 `when` 表达式结合在一起，可以提供更强大的业务组织和表达能力（下一章重点介绍其高级应用）。
- **替代建造者模式**：数据类的另一个典型应用是代替 Java 中的建造者（Builder）模式。建造者模式主要化解 Java 中书写一大串参数的构造方法来初始化对象的场景。而由于 Kotlin 类的构造方法可以指定默认值，依靠数据类的简洁语法，可以更方便地解决这个问题。

**Q51: 什么是伴生对象 companion object？它如何替代 Java 的 static？（工厂方法模式）**

- **Java 中 static 的缺陷**：一个类中既有静态变量/静态方法，也有普通变量/普通方法。虽然静态内容属于类、普通内容属于对象，但它们在代码结构上**混杂在一起，职能区分得不够清晰**：
  ```java
  public class Prize {
      private String name;
      private int count;
      private int type;
      static int TYPE_REDPACK = 0;
      static int TYPE_COUPON = 1;
      static boolean isRedpack(Prize prize) {
          return prize.type == TYPE_REDPACK;
      }
      // 普通方法与静态成员混在一起
  }
  ```
- **伴生对象的概念**："伴生"是相较于一个类而言的，意为**伴随某个类的对象**。它属于这个类所有，全局只有一个单例，需要声明在类内部，在**类被装载时**被初始化。语义上更清晰，用花括号把所有静态属性和方法包裹起来，与类的普通方法和属性清晰区分：
  ```kotlin
  class Prize(val name: String, val count: Int, val type: Int) {
      companion object {
          val TYPE_REDPACK = 0
          val TYPE_COUPON = 1
          fun isRedpack(prize: Prize): Boolean {
              return prize.type == TYPE_REDPACK
          }
      }
  }
  fun main(args: Array<String>) {
      val prize = Prize("红包", 10, Prize.TYPE_REDPACK)
      print(Prize.isRedpack(prize))
  }
  ```
- **实现工厂方法模式**：伴生对象的另一个作用是实现工厂方法模式。相比从构造方法实现工厂方式，它有两大优势：从构造方法方案**语义不够明确**（只能靠参数区分）且**每次获取对象都要重新创建对象**；而伴生对象可以改进这两个问题：
  ```kotlin
  class Prize private constructor(val name: String, val count: Int, val type: Int) {
      companion object {
          val TYPE_COMMON = 1
          val TYPE_REDPACK = 2
          val TYPE_COUPON = 3
          val defaultCommonPrize = Prize("普通奖品", 10, Prize.TYPE_COMMON)
          fun newRedpackPrize(name: String, count: Int) = Prize(name, count, Prize.TYPE_REDPACK)
          fun newCouponPrize(name: String, count: Int) = Prize(name, count, Prize.TYPE_COUPON)
          fun defaultCommonPrize() = defaultCommonPrize // 无须构造新对象
      }
  }
  ```
- **总结**：任何在 Java 类内部用 `static` 定义的内容都可以用伴生对象实现；一个类的伴生对象与静态类一样，全局只能有一个。

**Q52: 如何用 object 创建"天生的单例"？与 Java 单例模式有何对比？**

- **Java 单例模式的痛点**：单例模式最大的特点是系统中只能存在一个实例对象，所以 Java 必须通过**构造方法私有化** + 提供**静态方法创建实例**的方式来实现：
  ```java
  public class DatabaseConfig {
      private static DatabaseConfig databaseConfig = null;
      // 私有构造方法 + 静态 getter
      private DatabaseConfig(String host, int port, String username, String password) { ... }
      static DatabaseConfig getDatabaseConfig() {
          if (databaseConfig != null) {
              return databaseConfig;
          } else {
              return new DatabaseConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_USERNAME, DEFAULT_PASSWORD);
          }
      }
  }
  ```
  它依赖 `static` 关键字，还不得不把构造方法私有化，逻辑繁琐。
- **object 天生的单例**：由于 `object` 全局声明的对象只有一个，它不需要语法上的初始化，甚至不需要构造方法：
  ```kotlin
  object DatabaseConfig {
      var host: String = "127.0.0.1"
      var port: Int = 3306
      var username: String = "root"
      var password: String = ""
  }
  ```
  可以直接修改它的属性：
  ```kotlin
  DatabaseConfig.host = "localhost"
  ```
- **object 的性质**：单例对象与普通类一样可以实现接口和继承类，可以看作一种不需要主动初始化的类，也可以拥有扩展方法。它会在**系统加载时**初始化，全局只有一个。
- **object 不能有构造参数**：`object` 声明**不允许定义主构造函数（带参数）和次构造函数**，下面这种写法是非法的：
  ```kotlin
  object DatabaseConfig(host: String, port: Int) { // ❌ 编译错误
      ...
  }
  ```
  **为什么这么设计？**
  - **没有调用方传入参数**：单例由系统在类加载时自动创建，并不存在一个"由用户主动调用 `XXX()` 创建实例"的入口。既然没有调用方，自然就没有地方传参。
  - **初始化时机太早**：`object` 类似 Java 的 `static`，在首次访问时由 JVM/类加载器初始化。此时应用上下文、配置文件等可能还没准备好，强行要求传参反而会制造时序耦合。
  - **保证"天生单例"的纯粹性**：单例的核心是"全局唯一且无需用户管理生命周期"。一旦允许构造参数，使用者就得协调"谁来传、什么时候传、传错了怎么办"，这就退化成了需要人工编排的 Java 单例模式，违背了 `object` 的设计初衷。
  - **`init` 块可以存在**：虽然没有构造参数，但 `object` 仍然可以有 `init { }` 初始化块，用于执行一次性初始化逻辑。
- **需要"带参数单例"怎么办？** 如果确实需要运行时传入参数再构造单例，推荐用**普通类 + 私有构造 + 伴生对象工厂方法**，把"参数校验 + 唯一性保证"放在工厂方法里：
  ```kotlin
  class DatabaseConfig private constructor(
      var host: String,
      var port: Int,
      var username: String,
      var password: String
  ) {
      companion object {
          @Volatile private var instance: DatabaseConfig? = null
          fun create(host: String, port: Int, username: String, password: String): DatabaseConfig =
              instance ?: synchronized(this) {
                  instance ?: DatabaseConfig(host, port, username, password).also { instance = it }
              }
      }
  }
  ```
  这样既保留了单例语义，又能在首次创建时传入参数，并且通过 `synchronized` 保证线程安全。
- **FAQ：为什么 `instance` 要加 `@Volatile`？** DCL（双重检查锁）的第一次读 `instance` 是**无锁读**（在 `synchronized` 外面），这一步存在两个隐患：
  - **指令重排序**：JVM 执行 `new DatabaseConfig(...)` 时，实际指令是「1)分配内存 → 2)调用构造函数初始化 → 3)把引用赋给 `instance`」。JVM 可能将步骤 2、3 重排为「1 → 3 → 2」，此时 `instance` 已非 null 但对象还没初始化完。另一线程在第一次无锁读时可能拿到这个**半初始化对象**，直接使用会崩。
  - **可见性**：普通 `var` 的写入不保证立即对其他线程可见。A 线程创建好实例后，B 线程的第一次读可能仍然看到旧值 `null`，导致重复加锁。
  
  `@Volatile` 同时解决这两个问题：它建立 **happens-before** 关系，禁止上述重排序，并保证一个线程的写入对其他线程立即可见。**DCL 中 `@Volatile` 不能省，否则单例可能被破坏或返回残缺对象。**
- **FAQ：`create` 方法需要加 `@JvmStatic` 吗？** 取决于调用方：
  - **仅 Kotlin 调用 → 不需要**。Kotlin 编译器已经支持 `DatabaseConfig.create(...)` 这种语法糖，本质调用的是伴生对象实例上的方法，写法和静态调用一致。
  - **会被 Java 调用 → 建议加**。不加 `@JvmStatic` 时，Java 端必须写 `DatabaseConfig.Companion.create(...)`（因为伴生对象在字节码里是一个叫 `Companion` 的内部类实例），啰嗦且不符合 Java 习惯。加上 `@JvmStatic` 后，编译器会额外生成一个真正的 `static` 桥接方法，Java 端就能直接 `DatabaseConfig.create(...)`。
  
  **注意**：`@JvmStatic` 只是多生成一个 static 转发方法，实际逻辑仍在 `Companion` 实例上执行，**不影响单例语义**，也不影响 `synchronized(this)` 中的 `this`（这里 `this` 仍是 `Companion` 实例）。本例如果会被 Java 调用，推荐写法：
  ```kotlin
  companion object {
      @Volatile private var instance: DatabaseConfig? = null
      @JvmStatic
      fun create(host: String, port: Int, username: String, password: String): DatabaseConfig =
          instance ?: synchronized(this) {
              instance ?: DatabaseConfig(host, port, username, password).also { instance = it }
          }
  }
  ```
- **总结**：object 创造的是天生的单例，我们并不需要在 Kotlin 中去构建类似 Java 的单例模式。但它的"天生"也意味着**不支持构造参数**——这是为了保持单例"由系统管理、全局唯一"的纯粹性。如果场景需要运行时参数，应该退回到"类 + 工厂方法"的写法；其中 DCL 实现的 `@Volatile` 不可省，`@JvmStatic` 视 Java 调用需求而定。

**Q53: 什么是 object 表达式？它与匿名内部类、Lambda 表达式该如何选择？**

- **Java 匿名内部类的痛点**：有时明明只有一个方法，却要用一个匿名内部类去实现。方法内掺杂类声明不仅让方法看起来复杂，也不易阅读理解：
  ```java
  List<String> list = Arrays.asList("redpack", "score", "card");
  Collections.sort(list, new Comparator<String>() {
      @Override
      public int compare(String s1, String s2) {
          if (s1 == null) return -1;
          if (s2 == null) return 1;
          return s1.compareTo(s2);
      }
  });
  ```
- **用 object 表达式改善**：
  ```kotlin
  val comparator = object : Comparator<String> {
      override fun compare(s1: String?, s2: String?): Int {
          if (s1 == null) return -1
          else if (s2 == null) return 1
          return s1.compareTo(s2)
      }
  }
  Collections.sort(list, comparator)
  ```
- **object 表达式 vs 匿名内部类**：
  1. object 表达式可以**赋值给一个变量**，在重复使用时能减少很多代码；
  2. 匿名内部类只能继承一个类及实现一个接口，而 object 表达式没有这个限制；
  3. 用于代替匿名内部类的 object 表达式，在运行中**不像单例那样全局只有一个对象**，而是每次运行时都会生成一个新的对象。
- **与 Lambda 的选择原则**：匿名内部类与 object 表达式并非对任何场景都适合。当匿名内部类使用的类接口**只需要实现一个方法时，使用 Lambda 表达式更适合**；当匿名内部类内有**多个方法实现时，使用 object 表达式更合适**。例如上面的比较器用 Lambda 改写后简洁很多：
  ```kotlin
  val comparator = Comparator<String> { s1, s2 ->
      if (s1 == null) return@Comparator -1
      else if (s2 == null) return@Comparator 1
      s1.compareTo(s2)
  }
  Collections.sort(list, comparator)
  ```

---

## 第4篇 代数数据类型与模式匹配

**Q54: 什么是代数数据类型（ADT）？为什么说它是"像代数一样的数据类型"？**

- **ADT 的定义**：代数数据类型（Algebraic Data Type，ADT）是计算机编程中的一种**组合类型**（composite type），即"由其他类型组合而成的类型"。在函数式编程与类型理论中，最常见的两种代数类型是"和"（sum）类型与"积"（product）类型。
- **"像代数一样"的含义**：要理解 ADT，先理解何为代数。代数中的"数"是具体的值（`0, 1, 2, 3, 10, 100 ...`），而代数（algebra）本质上是"能代表数字的符号"，例如解方程 `x + 5 = 6`、`y * 3 = 21` 时，我们通过解方程得知代数 `x` 代表数字 `1`、`y` 代表数字 `7`。
- **从代数到类型的关键类比**：初等代数里有两个核心操作符——加法（`+`）与乘法（`*`），通过它们可以将代数组合成新的代数，如 `x * 1 = z`、`a + 2 = c`。**把方程中的"数"和"代数"替换成编程语言中的"值"和"类型"**，那么"由一些简单类型通过某种操作符组合出的新类型"就叫作代数数据类型（ADT）。
  ```kotlin
  // 简单类型通过"组合"这种操作产生的新类型，就是 ADT
  // 比如把 Boolean 类型与 String 类型组合，产生一个新的组合类型
  ```
- **ADT 的实际价值**：ADT 的应用很广，以业务逻辑为例，可以把一些比较简单的类型通过某种"操作符"抽象成比较复杂而且功能强大的类型；在编程语言中，某些常见的类型本身就是代数类型，比如第2章介绍的枚举类；更重要的是 **ADT 是类型安全的**——它把合法取值圈定在一个"闭环"内，使用它可以为开发免去许多麻烦。
- **抽象的阶梯**：代数是一个庞大的数学分支，从简单的线性、多项式代数到环、域，再到范畴、函子等更加抽象的代数，越往后抽象级别越高、越接近事物的本质，刻画事物的能力也越强（函数式编程的很多语法特性就利用了范畴论思想）。同理，日常开发中如果能合理利用 ADT 对业务进行高度抽象，那么代码在实现诸多功能的前提下还会变得非常简洁。

**Q55: 什么是"计数"？它为什么能帮助理解 ADT？**

- **计数的定义**：每种类型在实例化时都会有对应的若干种取值，将"类型的取值种类数与一个数字关联起来"的方式就叫作计数。比如 `Boolean` 类型存在两种可能的取值 `true` 和 `false`，所以 `Boolean` 对应的数字就是 `2`。
- **Unit 类型的计数**：`Unit` 是 Kotlin 相比 Java 新引入的类型，它表示"只有一个实例"（无实际含义的返回值类型），也就是说它只有一种取值，因此计数结果为 `1`。
  ```kotlin
  // 计数：类型 → 取值种类数
  // Boolean → 2（true / false）
  // Unit    → 1（只有一个实例）
  ```
- **计数的意义**：有了"每种类型对应一个数字"之后，就可以直观地理解 ADT 中的两种基本运算——积类型对应乘法、和类型对应加法。同时，因为可以根据计数判断某种类型或某个类的取值总数，**计数还能用在编译时期对 `when` 之类的语句做分支检查**（比如枚举有 7 种取值，`when` 就必须覆盖全 7 个分支）。

**Q56: 什么是积类型（product type）？为什么说它代表"组合"与"AND"的关系？**

- **与乘法的对应关系**：积类型（product）在 ADT 中对应代数运算中的**乘法**。两个数相乘的结果为"积"，那么两个类型组合出的新类型，其取值总数就是两者取值的**乘积**（combination）。
- **计数验证**：已知 `Boolean` 对应 `2`、`Unit` 对应 `1`，那么它们组合产生的积类型取值总数就是 `2 * 1 = 2`。用代码表达这种组合，就是让一个类同时持有两个类型的参数：
  ```kotlin
  class BooleanProductUnit(a: Boolean, b: Unit){}

  val a = BooleanProductUnit(false, Unit)
  val b = BooleanProductUnit(true, Unit)
  ```
  可以看出，`BooleanProductUnit` 最多只能有两种取值，正好符合 `2 * 1 = 2` 的猜想。
- **本质理解**：当利用类进行组合时，实际上就是在做一次 product 操作。**积类型可以看作"同时持有某些类型"的类型**——比如上面的 `BooleanProductUnit` 就同时持有 `Boolean` 类型和 `Unit` 类型。由于它要求所有组成部分同时存在才能构成一个值，所以积类型体现的是一种 **"AND"（并且）关系**。

**Q57: 什么是和类型（sum type）？为什么说枚举类就是一种和类型？**

- **与加法的对应关系**：和类型（sum）在 ADT 中对应代数运算中的**加法**。枚举类就是最典型的一种和类型，回顾代码清单 4-1 的例子：
  ```kotlin
  enum class Day{SUN, MON, TUE, WED, THU, FRI, SAT}
  ```
- **计数验证**：枚举类中每个常量都是一个对象，比如 `SUN`，它与其他常量一样只能有一种取值，所以记为 `1`。那么枚举类型 `Day` 的所有取值可以表示为：
  ```kotlin
  val a = Day.SUN
  val b = Day.MON
  val c = Day.TUE
  val d = Day.WED
  val e = Day.THU
  val f = Day.FRI
  val g = Day.SAT
  ```
  不难发现，枚举类 `Day` 总共有 7 种可能的取值，即取值总数为 `1+1+1+1+1+1+1 = 7`——这正是"加法"。
- **和类型的两个重要特点**：
  - **类型安全**：和类型是一个"闭环"，枚举类 `Day` 总共只有 7 种可能的取值，不可能出现其他取值，所以使用它时不用担心出现非法情况。
  - **"OR" 的关系**：积类型同时拥有好几种类型（如 `BooleanProductUnit` 同时拥有 `Boolean` 和 `Unit`），体现的是"AND"；而和类型一次只能取其中某一种类型——要么是 `SUN`，要么是 `MON`，不能同时拥有这两种类型，因此它代表的是 **"OR"（或者）关系**。

**Q58: 密封类（sealed class）相比枚举有什么优势？为什么用 when 时可以省略 else？**

- **枚举的局限**：虽然枚举类是一种和类型，但和类型在使用时功能比较单一、扩展性不强（每个枚举常量不能携带不同类型的数据）。我们需要一种在表达上更强大的语法，那就是上一章接触到的密封类。用密封类重新实现上述 Day：
  ```kotlin
  sealed class Day {
      class SUN : Day()
      class MON : Day()
      class TUE : Day()
      ...
      class SAT : Day()
  }
  ```
  同样，该版本中的密封类 `Day` 总共也只有 7 种可能的取值。
- **密封类的限制规则**：在 3.2.1 节中已经了解，密封类通过 `sealed` 修饰符对子类进行限制，**该类的子类只能定义在父类或者与父类同一个文件内**。（注意：Kotlin 1.0 时子类只能定义在父类结构体中，Kotlin 1.1 之后可以不将子类定义在父类中了。）
- **类型安全带来的便利**：使用密封类（或者说和类型）最大的好处是，**使用 `when` 表达式时不用考虑非法情况，可以省略 `else` 分支**——因为和类型是类型安全的，只需将可能的情况列出来即可。而且如果我们遗漏了某种情况，或者多添加了额外的情况，编译器会报错提醒我们。
  ```kotlin
  fun schedule(day: Day): Unit =
      when (day) {
          is Day.SUN -> fishing()
          is Day.MON -> work()
          is Day.TUE -> study()
          is Day.WED -> library()
          is Day.THU -> writing()
          is Day.FRI -> appointment()
          is Day.SAT -> basketball()
      }
  ```
  上面就是一个 ADT 与 `when` 表达式结合的典型例子：不用额外写 `else` 来表示默认选项，因为它是类型安全的。

**Q59: 如何构造一个代数数据类型？以计算图形面积为例**

- **抽象步骤**：要计算圆形（给定半径）、长方形（给定长和宽）、三角形（给定底和高）的面积，首先找到它们的共同点——都是几何图形（Shape），然后利用密封类进行抽象：
  ```kotlin
  sealed class Shape {
      class Circle(val radius: Double) : Shape()
      class Rectangle(val width: Double, val height: Double) : Shape()
      class Triangle(val base: Double, val height: Double) : Shape()
  }
  ```
- **ADT 的结构剖析**：整个 `Shape` 就是一个**和类型**；其中的 `Circle`、`Rectangle`、`Triangle` 则是通过将基本类型 `Double` 构造成类而组合成的**积类型**（每个都同时持有若干个 `Double` 字段）。密封类把"和类型 + 积类型"嵌套在一起，就构成了一个完整的 ADT。
- **放心地使用 when**：使用 ADT 最大的好处就是可以很放心地去使用 `when` 表达式——利用 `when` 定义计算各个图形面积的方法：
  ```kotlin
  fun getArea(shape: Shape): Double = when (shape) {
      is Shape.Circle -> Math.PI * shape.radius * shape.radius
      is Shape.Rectangle -> shape.width * shape.height
      is Shape.Triangle -> shape.base * shape.height / 2.0
  }
  ```
- **与 Java 对比**：通过使用 ADT 和 `when` 表达式，求面积的代码非常简洁。如果用 Java 实现，则需要写一堆 `if-else` 表达式，还要考虑非法的情况，代码的可读性一般。

**Q60: 什么是模式匹配？什么是"模式"？为什么说模式本质上就是表达式？**

- **动机（复杂数据结构的困境）**：开发中会遇到一些复杂的数据结构（比如树），对其操作时经常需要访问内部某个属性。在 Java 中惯常的做法是定义 getter 之类的方法，但大部分情况下复杂的数据结构并没有预先提供那么多方法，有时甚至很难或无法再向其中添加新方法，只能一层一层地访问结构、取出属性再操作——用 Java 编写这类逻辑会觉得复杂且代码容易出错。模式匹配正是为解决这一困境而生。
- **模式的本质**：单词 "Pattern" 会让人联想到 Java 中通过 `java.util.regex` 包下的 `Pattern` 类与 `Matcher` 类实现的正则表达式：
  ```java
  // 创建要匹配的文本
  String text = "Hello World";
  // 实例化 Pattern 对象
  Pattern pattern = Pattern.compile("\\w+");
  // 对正则进行匹配
  boolean isMatch = pattern.matcher(text).matches();
  ```
  模式匹配与正则匹配非常相似，只是模式匹配中匹配的不仅有正则表达式，还可以有其他表达式——**这里的"表达式"就是"模式"**。
- **表达式与模式的关系**：一个数字、一个对象的实例，或者说凡是能够求出特定值的组合，都能称为表达式：
  ```kotlin
  class Pattern(val text: String)
  val a = 1
  val b = 2
  // 5、a + b、Pattern("hello")、a > b 都是表达式
  ```
  上面这些（字面量、运算式、构造表达式、逻辑表达式）都是表达式。**模式本质上就是这些表达式，模式匹配所匹配的内容其实就是表达式**。所以构造模式就是在构造表达式——可以构造简单的数字、逻辑表达式，也可以构造复杂的类或其他嵌套的结构。

**Q61: Kotlin 中常见的模式有哪些？它们分别如何使用？**

- **1. 常量模式**：与熟知的 `if-else` 或 `switch-case` 几乎没有什么不同，就是比较两个常量是否相等：
  ```kotlin
  fun constantPattern(a: Int) = when (a) {
      1 -> "It is 1"
      2 -> "It is 2"
      else -> "It is other number "
  }
  >>> println(constantPattern(1))
  It is 1
  ```
  这与 4.1.4 节中利用 `when` 操作枚举类是一样的，都是匹配常量。
- **2. 类型模式**：类似于 Java 中使用的 `instanceof` 方法，在 `when` 中会把传入的值依次与给定的模式（类型）相比较。比如传入的 shape 类型为 `Shape.Rectangle`，则会有类似 `shape instanceof Shape.Rectangle` 的操作，返回 `true` 就说明是长方形，然后计算面积：
  ```kotlin
  sealed class Shape {
      class Circle(val radius: Double) : Shape()
      class Rectangle(val width: Double, val height: Double) : Shape()
      class Triangle(val base: Double, val height: Double) : Shape()
  }
  fun getArea(shape: Shape): Double = when (shape) {
      is Shape.Circle -> Math.PI * shape.radius * shape.radius
      is Shape.Rectangle -> shape.width * shape.height
      is Shape.Triangle -> shape.base * shape.height / 2.0
  }
  >>> val shape = Shape.Rectangle(10.0, 0.5)
  >>> println(getArea(shape))
  5.0
  ```
- **3. 逻辑表达式模式**：匹配一个条件表达式，注意此时 `when` 关键字后面不带参数：
  ```kotlin
  // 例1：匹配数值是否在某个范围内
  fun logicPattern(a: Int) = when {
      a in 2..11 -> (a.toString() + " is smaller than 10 and bigger than 1")
      else -> "Maybe" + a + "is bigger than 10, or smaller than 1"
  }
  >>> logicPattern(2)
  2 is smaller than 10 and bigger than 1

  // 例2：匹配字符串是否包含另一个字符串
  fun logicPattern(a: String) = when {
      a.contains("Yison") -> "Something is about Yison"
      else -> "It`s none of Yison`s business"
  }
  >>> logicPattern("Yison is a good boy")
  Something is about Yison
  ```
  不带参数的 `when`，其各分支执行的就是类似于 `if` 表达式进行判等的操作。
- **一个疑问**：以上 3 种模式用 `if-else` 或 `switch-case` 都能实现，那么模式匹配的威力到底体现在哪里？答案在于对**嵌套表达式**这类复杂结构的处理上。

**Q62: 用 if-else 处理嵌套表达式为什么会显得无力？（以表达式化简为例）**

- **数据结构**：首先定义一个非常简单的整数表达式数据结构：`Num` 表示某个整数的值；`Operate` 是一个树形结构，用于表示复杂的表达式，其中 `opName` 表示常见的操作符（如 `+`、`-`、`*`、`/`）：
  ```kotlin
  sealed class Expr {
      data class Num(val value: Int): Expr()
      data class Operate(val opName: String, val left: Expr, val right: Expr): Expr()
  }
  ```
- **需求**：实现表达式化简——将 `0 + x` 或者 `x + 0` 化简为 `x`，其他情况返回表达式本身。伪代码为：`if (expr is "0 + x" || expr is "x + 0") x else expr`。
- **用 if-else 的实现**（就像在 Java 中常做的那样）：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = if (expr is Expr.Num) {
      expr
  } else if (expr is Expr.Operate && expr.opName == "+" && expr.left is Expr.Num && expr.left.value == 0) {
      expr.right
  } else if (expr is Expr.Operate && expr.opName == "+" && expr.right is Expr.Num && expr.right.value == 0) {
      expr.left
  } else expr
  ```
  以第 2 个条件为例，它依次做了这些判断：`expr is Expr.Operate`（是否是 Operate 类型）、`expr.opName == "+"`（是否是加法操作）、`expr.left is Expr.Num`（左节点是否是数值）、`expr.left.value == 0`（左节点是否为 0）。可以看到，需要写很多判断类型的代码，非常冗余。
- **Java 的雪上加霜**：在 Java 中还需要写一大堆强制类型转换，因为 Kotlin 支持 Smart Casts（智能转换，下章详细讲），而 Java 不支持。同样的条件在 Java 中会变成这样：
  ```java
  else if (expr instanceof Expr.Operate && ((Expr.Operate)expr).name.equals("+") && ...) {
      return (Expr.Operate)expr.right;
  }
  ```
  假使数据结构更加复杂，每一个条件都会包含更长串的代码，既不方便阅读，也容易出错——这就是 **if-else 语句在处理复杂嵌套表达式时显得无力的原因**。
- **用 when 的直接改写**：虽然可以用 `when` 改写（代码清单 4-2），但本质上与 `if-else` 没有区别，只是因为没有 `else-if` 这种嵌套才看上去相对简洁，其实仍保留了很多判断类型的语句：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = when {
      (expr is Expr.Operate) && (expr.opName == "+") && (expr.left is Expr.Num && expr.left.value == 0) -> expr.right
      (expr is Expr.Operate) && (expr.opName == "+") && (expr.right is Expr.Num && expr.right.value == 0) -> expr.left
      else -> expr
  }
  ```
  那么有没有更优雅的实现方法呢？答案是去支持模式匹配的语言（Scala）中找灵感。

**Q63: 在 Scala 中模式匹配是如何实现的？为什么说模式匹配的核心就是解构？**

- **Scala 的抽象与实现**：与 Kotlin 不同，Scala 支持很多模式匹配的功能特性。首先用 Scala 语法把整数表达式抽象成密封结构（与 Kotlin 语法几乎相近），然后通过 ADT 和模式匹配实现 `simplifyExpr`：
  ```scala
  sealed trait Expr
  case class Num(value: Int) extends Expr
  case class Operate(opName: String, left: Expr, right: Expr) extends Expr

  def simplifyExpr(expr: Expr): Expr = expr match {
    // 0 + x
    case Operate("+", Num(0), x) => x
    // x + 0
    case Operate("+", x, Num(0)) => x
    case _ => expr
  }
  ```
  可以把 `match` 理解为 Kotlin 中的 `when`，`case` 表示某个分支（与 `when` 表达式中 `->` 前面的语句类似）。每个分支中的 `x` 等价于处于那个位置的参数，比如第一个分支中 `x = expr.right`。这样实现比之前简洁许多，并且通过 `case` 后面匹配的内容可以很容易推断出当前分支匹配的结构。
- **case 表达式的本质是反向构造**：仔细看 `case Operate("+", Num(0), x)`，它看上去就像一个 `Expr.Operate` 的实例——我们在实例化时写的是 `val expr = Expr.Operate("+", Expr.Num(0), x)`，而 `case` 后面的表达式就有点像把上面的表达式**反向写**了一遍，类似于：
  ```kotlin
  val Expr.Operate("+", Expr.Num(0), x) = expr
  ```
  然后再用这种反向结构去和传入的值进行比较。
- **与解构声明的关系**：如果把上面的结构再简化一下，变成 `val ("+", Expr.Num(0), x) = expr`，就与第3章介绍过的**解构声明**非常类似了。**其实在 Scala 中，模式匹配的核心就是解构**。
- **解构 = 反向构造**：正向构造就是一般的构造表达式的过程，比如把简单的参数组合成较复杂的表达式：
  ```kotlin
  val expr = Operate("+", Num(0), Operate("-", Num(1), Num(2)))
  ```
  反向构造就是把上面的过程进行回放——将表达式 `expr` 分解成先前的参数。对应到模式匹配中就是：给定一个复杂的数据结构，然后把之前用来构成该复杂结构的参数抽取出来。用 `expr` 去匹配 `case Operate("+", Num(0), x)` 就能得到 `x = Operate("-", Num(1), Num(2))`。
- **总结**：模式匹配中的模式就是表达式，模式匹配要匹配的就是表达式；**模式匹配的核心其实就是解构，也就是反向构造表达式**。看了 Scala 中的模式匹配，就知道应该用 `when` 去匹配什么样的表达式了。

**Q64: 用 when 表达式实现嵌套表达式的模式匹配会遇到什么问题？如何用递归来"力挽狂澜"？**

- **直接照抄 Scala 的写法会报错**：Scala 的 `match-case` 在匹配每个分支时，会先判断该分支的类型（比如先判断传入的 expr 是不是 `Operate` 类型，然后再进行匹配），而 **Kotlin 的 `when` 表达式暂时还做不到这一点**。所以如果照抄 Scala 的写法：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = when (expr) {
      Expr.Operate("+", Expr.Num(0), expr.right) -> expr.right
      Expr.Operate("+", expr.left, Expr.Num(0)) -> expr.left
      else -> expr
  }
  ```
  编译器会报错 `error: unresolved reference: right`——因为在 `when` 匹配分支时不会先做类型判断，`expr` 还是静态类型 `Expr`，访问不了 `Operate` 才有的 `right` 属性。
- **手动加上类型判断**：所以要手动写一个判断类型的操作，先 `is Expr.Operate` 把类型固定下来，再在内层 `when` 中解构：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = when (expr) {
      is Expr.Num -> expr
      is Expr.Operate -> when (expr) {
          Expr.Operate("+", Expr.Num(0), expr.right) -> expr.right
          Expr.Operate("+", expr.left, Expr.Num(0)) -> expr.left
          else -> expr
      }
  }
  ```
  与前面的实现相比简洁了许多，也能清楚地推断出将要匹配的表达式结构。但实现时仍需手动写判断类型的语句，而且**当嵌套层数变多时，单纯使用 `when` 表达式还是显得无力**。
- **递归应对深层嵌套**：比如要匹配下面表达式中最内层的 `Expr.Num(1)`：
  ```kotlin
  val expr = Expr.Operate("+", Expr.Num(0), Expr.Operate("+", Expr.Num(1), Expr.Num(0)))
  ```
  在 Scala 中可以用一条模式直接命中：`case Operate("+", Num(0), Operate("+", left, Num(0))) => left`。而在 Kotlin 中，可以通过**递归**的方式实现——匹配到右子树是"加 0"结构时，对 `expr.right` 递归调用自身，层层下钻：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = when (expr) {
      is Expr.Num -> expr
      is Expr.Operate -> when (expr) {
          Expr.Operate("+", Expr.Num(0), expr.right) -> simplifyExpr(expr.right)
          Expr.Operate("+", expr.left, Expr.Num(0)) -> expr.left
          else -> expr
      }
  }
  ```
- **递归方案的局限**：实际业务中的数据结构可能并不像上面那样对称，有些情况下必须直接访问两层结构，而用递归又实现不了——此时只能退回去写冗长的类型判断与解构。所以 Kotlin 在匹配嵌套表达式时，通过**递归的思路可以在语法上进一步简化表达**；但也要注意，**在嵌套结构很深的情况下，该方案不一定适合**。
- **小结**：Kotlin 还没有完全支持模式匹配，在有嵌套结构的匹配上不那么出色。但 `when` 表达式结合类型判断、解构、Smart Casts 等特性已经能满足大部分工程需求；此外还可以用其他方法来增强 Kotlin 的模式匹配，这正是下一节要介绍的内容。

**Q65: 实现模式匹配有哪 6 种技术？其中哪些在 Kotlin 中可以落地？**

- **论文来源**：Scala 的缔造者 Martin Odersky 以及另外两位模式匹配领域的专家，曾在一篇论文中（Matching Objects With Patterns）介绍了 6 种用来实现模式匹配的技术，分别是：
  - **类型测试/类型转换（Type-Test/Type-Cast）**：先判断值是什么类型，再转换为该类型；
  - **面向对象的分解（Object-Oriented Decomposition）**：在父类中定义测试方法，在子类中分别实现；
  - **访问者设计模式（Visitor）**：把对元素的操作放到类外部；
  - **Typecase**：将类型作为 `case` 分支的条件；
  - **样本类（Case Classes）**：Scala 中自动提供 `equals`、`hashCode`、解构能力的类；
  - **抽取器（Extractor）**：通过 `unapply` 方法把对象反向分解成组成部分。
- **Kotlin 中的取舍**：上面的 6 种方法中，**后面 3 种（Typecase、样本类、抽取器）暂时在 Kotlin 中还不能实现，或者说实现起来还有些困难**；本节将利用**前面 3 种**方法——类型测试/类型转换、面向对象的分解、访问者设计模式——来实现模式匹配。

**Q66: 什么是"类型测试/类型转换"技术？为什么说它在 Kotlin 中基本不能增强模式匹配？**

- **工作原理**：从名称就可以大致知道它如何工作：首先对类型进行测试，也就是判断所给的值是何种类型；然后再进行类型转换。这就是在 Java 程序中常用的一种方式：
  ```java
  // 类型测试：判断 expr 是否为 Expr.Operate
  if (expr instanceof Expr.Operate) {
      // 类型转换：强制转换为 Expr.Operate 后再访问成员
      ((Expr.Operate) expr).name.equals("+");
  }
  ```
- **Kotlin 中的简化（Smart Casts）**：在 Kotlin 中不再需要做强制类型转换，只需要实现类型测试就可以了，因为 Kotlin 本身支持 **Smart Casts（智能转换）**——一旦用 `is` 判断了类型，编译器就自动帮你把变量转换为对应类型：
  ```kotlin
  expr.left is Expr.Num && expr.left.value == 0
  ```
  这里只需要判断 `expr.left` 的类型是否为 `Expr.Num` 即可，Kotlin 会自动将其转换为 `Expr.Num` 类型，从而可以访问 `value` 属性。
- **评价**：在解构对象方面，这种方式最直接，但它存在不少缺点（前面已经提到：需要写大量重复的类型判断代码、代码冗余、深层嵌套时显得无力）。所以**使用这种方式基本上不能增强 Kotlin 的模式匹配**。

**Q67: 什么是"面向对象的分解"？它如何简化模式匹配？代价是什么？**

- **思路**：前面用模式匹配时总需要不断地判断对象是什么类型，再根据特定类型访问内部属性（Java 还要强制转换）。有没有办法不做这些操作就知道某个表达式是数值且值为 0？很容易想到通过调用方法来实现：`expr.isZero()`。**面向对象的分解就是采用这种思路：在父类中定义一系列的测试方法（比如判断是否为数值），然后在子类中分别实现这些方法**，从而在不同子类中做相应操作。
- **实现**：针对 4.2.5 节后半部分那个难以匹配的嵌套表达式（把最内层 `Expr.Num(1)` 取出来），先把多次用到的"是否为 0 + x / x + 0"判断抽象成方法：
  ```kotlin
  sealed class Expr {
      abstract fun isZero(): Boolean
      abstract fun isAddZero(): Boolean

      data class Num(val value: Int) : Expr() {
          override fun isZero(): Boolean = this.value == 0
          override fun isAddZero(): Boolean = false
      }

      data class Operate(val opName: String, val left: Expr, val right: Expr) : Expr() {
          override fun isZero(): Boolean = false
          override fun isAddZero(): Boolean =
              this.opName == "+" && (this.left.isZero() || this.right.isZero())
      }
  }
  ```
  `isAddZero()` 判断某个表达式是否形如 `x + 0` 或 `0 + x`；`isZero()` 判断某个表达式是否为 0。
- **遇到的问题**：即便写了这两个方法，下面的代码编译器仍会报错 `error: unresolved reference: right`：
  ```kotlin
  fun simplifyExpr(expr: Expr): Expr = when {
      expr.isAddZero() && expr.right.isAddZero() && expr.right.right.isZero() -> expr.right.right
      else -> expr
  }
  ```
  因为尽管编写代码的人知晓当 `expr.isAddZero()` 为 `true` 时 `expr` 的类型肯定是 `Expr.Operate`，**但编译器不会自动做这种判断并转换，编译器只知道该类型为 `Expr`**。所以还需要在父类中定义类似于 getter 的方法（`left()` / `right()`），让各子类分别实现：
  ```kotlin
  sealed class Expr {
      abstract fun isZero(): Boolean
      abstract fun isAddZero(): Boolean
      abstract fun left(): Expr
      abstract fun right(): Expr

      data class Num(val value: Int) : Expr() {
          override fun isZero(): Boolean = this.value == 0
          override fun isAddZero(): Boolean = false
          override fun left(): Expr = throw Throwable("no element")
          override fun right(): Expr = throw Throwable("no element")
      }

      data class Operate(val opName: String, val left: Expr, val right: Expr) : Expr() {
          override fun isZero(): Boolean = false
          override fun isAddZero(): Boolean =
              this.opName == "+" && (this.left.isZero() || this.right.isZero())
          override fun left(): Expr = this.left
          override fun right(): Expr = this.right
      }
  }
  ```
  有了这些方法，就能比较简单地实现 `simplifyExpr`：
  ```kotlin
  val expr = Expr.Operate("+", Expr.Num(0), Expr.Operate("+", Expr.Num(1), Expr.Num(0)))
  fun simplifyExpr(expr: Expr): Expr = when {
      expr.isAddZero() && expr.right().isAddZero() && expr.right().left().isZero() -> expr.right().left()
      else -> expr
  }
  >>> simplifyExpr(expr)
  Num(value=1)
  ```
- **代价（类变得臃肿）**：简化 `simplifyExpr` 的代价很明显——**需要在 `sealed class Expr` 中实现许多方法**。业务中需要实现的需求远比上面的例子复杂，这就意味着要在密封类中实现更多、更复杂的方法，导致整个类的结构看上去非常臃肿，几乎全是一些方法的实现。另外，**如果添加一个新的子类，那么针对该子类的测试方法就需要在之前定义的每个子类中再实现一遍**；而且由于之前实现的测试方法并没有考虑新子类的情况，很可能需要被重新实现，这种代价是比较高的。
- **适用场景**：如果业务比较简单，并且后期数据结构不会有太大变化，可以将这种方式与 `when` 表达式结合起来使用，比较方便地简化逻辑。

**Q68: 访问者设计模式如何实现模式匹配？它和面向对象的分解相比解决了什么问题？**

- **动机**：面向对象的分解方式存在一个问题——在类中写了太多方法的实现，使类变得笨重。**访问者设计模式**表示"一个作用于某对象结构中的各元素的操作，它使你可以在不改变各元素类的前提下定义作用于这些元素的新操作"。也就是说，它能把相关方法的实现放到类的外部，让类不再臃肿。
- **基本原理**：在目标类（这里是 `Expr`）的基础上增加一个额外的 `Visitor` 类，起"访问"的作用。`Visitor` 类中定义多个 `visit` 方法，这些方法名称相同但参数类型不同（参数类型为目标类的各个子类）；同时，在目标类的每个子类中定义一个 `accept` 方法，用来把访问者对象注入进来，然后访问者对象就可以对目标类的不同子类进行不同操作：
  ```kotlin
  sealed class Expr {
      abstract fun accept(v: Visitor): Boolean
      class Num(val value: Int) : Expr() {
          override fun accept(v: Visitor): Boolean = v.visit(this)
      }
      class Operate(val opName: String, val left: Expr, val right: Expr) : Expr() {
          override fun accept(v: Visitor): Boolean = v.visit(this)
      }
  }

  class Visitor {
      fun visit(expr: Expr.Num): Boolean = false
      fun visit(expr: Expr.Operate): Boolean = when (expr) {
          Expr.Operate("+", Expr.Num(0), expr.right) -> true
          Expr.Operate("+", expr.left, Expr.Num(0)) -> true
          else -> false
      }
  }
  ```
  两个 `visit` 方法参数类型不同，这是为了对特定子类进行不同操作，也是访问者模式比较关键的一点。`accept` 方法为访问者类提供访问的通道，其实 `visit` 的实现就是前面例子中 `isAddZero` 的实现，只不过把实现代码放在了类的外部。
- **用访问者模式重写前面的例子**：
  ```kotlin
  sealed class Expr {
      abstract fun isZero(v: Visitor): Boolean
      abstract fun isAddZero(v: Visitor): Boolean
      abstract fun simplifyExpr(v: Visitor): Expr

      class Num(val value: Int) : Expr() {
          override fun isZero(v: Visitor): Boolean = v.matchZero(this)
          override fun isAddZero(v: Visitor): Boolean = v.matchAddZero(this)
          override fun simplifyExpr(v: Visitor): Expr = v.doSimplifyExpr(this)
      }

      class Operate(val opName: String, val left: Expr, val right: Expr) : Expr() {
          override fun isZero(v: Visitor): Boolean = v.matchZero(this)
          override fun isAddZero(v: Visitor): Boolean = v.matchAddZero(this)
          override fun simplifyExpr(v: Visitor): Expr = this
      }
  }

  class Visitor {
      fun matchAddZero(expr: Expr.Num): Boolean = false
      fun matchAddZero(expr: Expr.Operate): Boolean = when (expr) {
          Expr.Operate("+", Expr.Num(0), expr.right) -> true
          Expr.Operate("+", expr.left, Expr.Num(0)) -> true
          else -> false
      }
      fun matchZero(expr: Expr.Num): Boolean = expr.value == 0
      fun matchZero(expr: Expr.Operate): Boolean = false
      fun doSimplifyExpr(expr: Expr.Num): Expr = expr
      fun doSimplifyExpr(expr: Expr.Operate, v: Visitor): Expr = when {
          (expr.right is Expr.Num && v.matchAddZero(expr) && v.matchAddZero(expr.right))
                  && (expr.right.left is Expr.Num) && v.matchZero(expr.right.left) -> expr.right.left
          else -> expr
      }
  }
  ```
- **好处与适用场景**：与面向对象的分解相比，`doSimplifyExpr` 方法在实现上并没有太大区别；好处是**把类中方法的实现放到了外部，使得类的结构看上去比较单纯**。当定义的子类特别多、结构比较复杂时，访问者模式可以让我们少写许多判断类型的代码，而且只在特定的子类中进行相关操作，会使逻辑轻巧一些。
- **缺点**：访问者模式也存在很多缺点——**如果给 `Expr` 增加一个子类，就要在访问者类中再为它增加一个操作**，不便于后期维护；如果频繁地增加类型，访问者类就需要被不断修改。另外它与面向对象的分解存在同一个问题：新增加子类之后，之前实现的一些测试方法可能需要被重新实现。所以虽然没在目标类中写方法实现，**访问者模式看上去依然比较笨重，一般情况下不建议使用**。但如果数据结构在后期不会有太大改变、业务逻辑相对复杂，可以将访问者设计模式与 `when` 结合起来使用。

**Q69: 三种增强模式匹配的方式应如何取舍？Kotlin 为什么没有完全支持模式匹配？**

- **三种方式的定位**：本节列举了 Kotlin 中能够实现模式匹配的几种方法——**类型测试/类型转换**（最直接但代码冗余、基本无法增强模式匹配）、**面向对象的分解**（把判断抽象为父类中的方法，但会把类撑得臃肿）、**访问者设计模式**（把操作放到类外部，使类结构单纯，但增加子类时维护成本高）。在**日常开发中，可以将这几种方法与 `when` 表达式进行合理组合**。
- **Kotlin 设计者的考量**：Kotlin 之所以没有完全支持模式匹配，是因为**它的设计者们认为，使用 Kotlin 中现有的 Smart Casts 及解构声明，在处理日常的业务开发时已经足够了**。如果 Kotlin 支持完整的模式匹配，确实会给开发带来许多便捷，也会极大地丰富 Kotlin 的语法，所以我们期待某一天 Kotlin 能够完全地支持模式匹配。
- **延伸阅读**：除了本节提到的几种方式外，Kotlin 中增强模式匹配的方式还可以参考相关文章（如 Kotlin.link 上关于 "Improved Pattern Matching in Kotlin" 的讨论）。

**Q70: 优惠券业务有哪些实际需求？设计时需要注意哪些问题？**

- **需求清单**：我们现在要开发一个与优惠券相关的业务，基本需求如下：
  - 优惠券有多种类型，如**现金券、礼品券及折扣券**；
  - 现金券能够实现"满多少金额减多少金额"，礼品券能够通过券上标明的礼品来兑换相应礼物，折扣券表示用户能够享受多少折扣；
  - 用户可以领取优惠券，领取之后也可以使用优惠券；
  - 优惠券的使用时间可以指定在某一个特定时间段内；
  - 如果优惠券在特定的时间内没有使用的话就会过期。
- **需求分析**：优惠券会有一些基本的属性，比如 `id`、名称、基本信息等；优惠券有不同的类型，通过一个 `type` 来表示；对于不同类型的优惠券，会有不同的属性来对应它们需要实现的功能（比如折扣券会有一个属性来表示折扣）；优惠券能够被领取和使用，只有在特定的时间内才有效，也有可能过期，这就需要有一个专门的方法来判断优惠券的状态。
- **设计要点**：这个需求涉及"多种类型的实体 + 每种类型携带不同字段 + 状态判断"，正是最适合用代数数据类型和模式匹配来抽象的场景。接下来我们从"最容易想到的抽象"出发，逐步优化到"利用 ADT 的高度抽象"。

**Q71: 用"一个大类塞满所有可空字段"的方式来抽象优惠券，为什么是糟糕的设计？**

- **最直接的抽象**：根据分析先抽象出优惠券的基本特征——`id` 和 `type`：
  ```kotlin
  class Coupon(
      val id: Long,
      val type: String
  )
  ```
- **进一层的"膨胀"抽象**：由于优惠券有不同的类型、每种类型有专有的属性，于是进一步得到如下结构——把每种类型可能用到的属性全部塞进一个类里，用可空类型（`?`）来标记"只有特定券类型才用到"：
  ```kotlin
  class Coupon(
      val id: Long,
      val type: String,
      // 券类型为代金券的时候使用，满足leastCost减少reduceCost
      val leastCost: Long?,
      val reduceCost: Long?,
      // 券类型为折扣券的时候使用
      val discount: Int?,
      // 券类型为礼品券的时候使用
      val gift: String?
  ) {
      companion object {
          final val CashType = "CASH"
          final val DiscountType = "DISCOUNT"
          final val GiftType = "GIFT"
      }
  }
  ```
  上面这种抽象方式非常常见，也完全可以实现需求，但仔细分析会发现一些严重问题。
- **问题一：大量冗余的可空字段**。比如在使用"礼品券"时，除了基本属性，只会用到 `gift`，而 `leastCost`、`reduceCost`、`discount` 都为空，因为这 3 个属性与礼品券毫无关系。如果优惠券很复杂（折扣券、代金券还各自含有许多特有属性），冗余属性就会非常多，使代码看上去非常臃肿。
- **问题二：扩展成本高**。后期如果想增加一种优惠券类型，就需要**修改整个 `Coupon` 的结构**，开发成本变得很大。
- **问题三：不安全**。由于 `Coupon` 类有可能在很多个地方被实例化，一旦结构改变，这些地方都需要被修改；而且所有属性都可空，使用时稍不留神就会遇到空值问题。

**Q72: 如何利用 ADT 的思想重新抽象 Coupon？这样做带来了哪些好处？**

- **用密封类把"券类型"变成和类型**：在 4.1.5 节学过如何构建 ADT，现在就用 ADT 的思想重新抽象 `Coupon`——把不同类型的优惠券变成密封类的子类，每种类型的专有属性只存在于自己的子类中：
  ```kotlin
  sealed class Coupon {
      companion object {
          final val CashType = "CASH"
          final val DiscountType = "DISCOUNT"
          final val GiftType = "GIFT"
      }
      class CashCoupon(val id: Long, val type: String, val leastCost: Long, val reduceCost: Long) : Coupon()
      class DiscountCoupon(val id: Long, val type: String, val discount: Int) : Coupon()
      class GiftCoupon(val id: Long, val type: String, val gift: String) : Coupon()
  }
  ```
- **好处一：消除冗余**。通过密封类把优惠券抽象成一个 ADT，**减少了数据的冗余**——每种券只携带自己需要的属性，不再有无关的可空字段。
- **好处二：易于扩展**。需要重新添加一种优惠券时很方便，直接在 `GiftCoupon` 类的后面添加一个类即可，不用修改已有结构。
- **好处三：类型安全**。由于 ADT 是类型安全的，**使用 `when` 表达式时即使遗漏了新类型的逻辑处理，编译器也会提醒我们**，把新增的类型补充上去即可。
- **实现状态判断方法**：有了这个抽象结构，接下来实现需求中"判断优惠券处于何种状态"的方法。先把优惠券的几种状态罗列如下：
  ```kotlin
  companion object {
      final val NotFetched = 1   // 未领取
      final val Fetched = 2      // 已领取但未使用
      final val Used = 3         // 已使用
      final val Expired = 4      // 已过期
      final val UnAvilable = 5   // 已失效
  }
  ```
  然后定义一些方法来判断某张优惠券处于何种状态：
  ```kotlin
  fun fetched(c: Coupon, user: User): Boolean    // 根据用户信息和优惠券信息，是否领取
  fun used(c: Coupon, user: User): Boolean       // 根据用户信息和优惠券信息，是否已被该用户使用
  fun isExpired(c: Coupon): Boolean              // 判断优惠券是否过期
  fun isUnAviable(c: Coupon): Boolean            // 判断优惠券是否已经失效

  fun getCouponStatus(coupon: Coupon, user: User): Int = when {
      isUnAviable(coupon) -> Coupon.UnAvilable   // 无效的优惠券
      isExpired(coupon) -> Coupon.Expired        // 过期的优惠券
      isUsed(coupon, user) -> Coupon.Used        // 被使用的优惠券
      fetched(coupon, user) -> Coupon.Fetched    // 已领取的优惠券但未使用
      else -> Coupon.NotFetched                  // 未领取的优惠券
  }
  ```
- **用状态驱动 UI**：根据 `getCouponStatus` 的返回值即可渲染优惠券详情页中显示的状态：
  ```kotlin
  fun showStatus(coupon: Coupon, user: User) = when (getCouponStatus(coupon, user)) {
      Coupon.UnAvilable -> showUnAvilable()
      Coupon.Expired -> showExpired()
      Coupon.Used -> showUsed()
      Coupon.Fetched -> showFetched()
      else -> showNotFetched()
  }
  ```
- **这套方案的遗留问题**：上面的抽象虽然已经比较优雅，但仍存在两个潜在问题：一是 **`getCouponStatus` 方法被多次调用**（凡是需要状态的逻辑都要调它）；二是**用 `when` 表达式做状态判断时并不是类型安全的**（`Int` 状态码不是封闭类型，必须依赖 `else` 分支兜底，状态一旦增多还容易漏改 `else` 中的逻辑）。

**Q73: 如何实现"更高层次的抽象"：把状态本身抽象成 ADT 并与数据组合？**

- **两个待解决的问题**：上一节的抽象还存在两个潜在问题：① `getCouponStatus` 方法被多次调用；② 用 `when` 表达式做状态判断时非类型安全。要解决第 2 点，有一个办法——**传入 `when` 表达式的参数必须是密封类构建的代数数据类型**。所以可以把优惠券的状态本身抽象成一个密封类（ADT）：
  ```kotlin
  sealed class CouponStatus {
      object StatusNotFetched : CouponStatus()   // 未领取
      object StatusFetched : CouponStatus()      // 已领取但未使用
      object StatusUsed : CouponStatus()         // 已使用
      object StatusExpired : CouponStatus()      // 已过期
      object StatusUnAvilable : CouponStatus()   // 无效优惠券
  }
  ```
  解决第 1 点（`getCouponStatus` 被多次调用），最好只让它被调用一次。我们知道，当使用某个优惠券实例时基本上都需要使用它的状态，**利用 ADT 的思想，可以将优惠券的状态与优惠券的实例进行组合**。于是把状态子类从 `object` 升级为 `data class`，组合进 `Coupon`；又因为"已使用"和"已领取"这两种状态都需要对 `User` 进行操作，所以再把 `User` 也组合进来：
  ```kotlin
  sealed class CouponStatus {
      data class StatusNotFetched(val coupon: Coupon) : CouponStatus()
      data class StatusFetched(val coupon: Coupon, val user: User) : CouponStatus()
      data class StatusUsed(val coupon: Coupon, val user: User) : CouponStatus()
      data class StatusExpired(val coupon: Coupon) : CouponStatus()
      data class StatusUnAvilable(val coupon: Coupon) : CouponStatus()
  }
  ```
- **`getCouponStatus` 只调用一次，且返回值是 ADT**：有了上面的结构，`getCouponStatus` 不再返回 `Int`，而是返回携带数据的 `CouponStatus` 实例，并且它只需要在 `CouponStatus` 被实例化时调用一次：
  ```kotlin
  fun getCouponStatus(coupon: Coupon, user: User): CouponStatus = when {
      isUnAviable(coupon) -> CouponStatus.StatusUnAvilable(coupon)      // 无效
      isExpired(coupon) -> CouponStatus.StatusExpired(coupon)          // 过期的
      isUsed(coupon, user) -> CouponStatus.StatusUsed(coupon, user)    // 被使用的
      fetched(coupon, user) -> CouponStatus.StatusFetched(coupon, user) // 已领取
      else -> CouponStatus.StatusNotFetched(coupon)                    // 未领取的优惠券
  }
  ```
- **模式匹配完全类型安全，不再需要 else**：在根据优惠券状态处理具体逻辑时，只需传入 `CouponStatus` 的实例即可。比如上一节"显示状态"的例子就变成了：
  ```kotlin
  fun showStatus(status: CouponStatus) = when (status) {
      is CouponStatus.StatusUnAvilable -> showUnAvilable()
      is CouponStatus.StatusExpired -> showExpired()
      is CouponStatus.StatusUsed -> showUsed()
      is CouponStatus.StatusFetched -> showFetched()
      is CouponStatus.StatusNotFetched -> showNotFetched()
  }
  ```
  这个 `when` 表达式在模式匹配时**不需要再写 `else` 分支**。如果后期添加一个状态，只需要在 `when` 表达式中添加一个分支即可；如果有遗漏，编译器会提示我们。而且由于 `getCouponStatus` 方法没有被调用多次，维护起来也很方便。
- **高度抽象的本质与收益**：我们通过 ADT 将优惠券的状态进行了抽象，并且把 `Coupon` 和 `User` 也组合到了其中，**好处就是当使用优惠券的状态时，其所需要的数据就不需要再额外去调用其他方法获取了**。这种高度抽象的方式使得数据的具体信息更加简洁、概括能力更强、数据更加完备，使用起来也非常安全。
- **方法论**：要将事物一次性进行高度抽象是比较困难的，这种技能需要靠大量的实战来积累。但实现更高层次抽象时，可以遵循一个可行的套路——**先将所描述的事物利用 ADT 的思想进行一次抽象，再根据业务逻辑进行必要的调整（比如把状态与实体进行组合），最终实现更高层次的抽象**。将模式匹配与 ADT 有机地结合，就能在业务开发中发挥出它们的最大威力。

---

## 第5篇 类型系统（可空性 / 泛型）

**Q74: 为什么说 null 引用是"10 亿美元的错误"？**

- **来源与背景**：null 引用由 Tony Hoare 于 1965 年使用面向对象语言 ALGOL W 设计第一个全面的引用类型系统时发明，当时仅仅因为实现起来非常容易就加入了它。2009 年他在 QCon 发表题为《null 引用：代价 10 亿美元的错误》的演讲，指出这一设计在之后 40 年中可能造成了 10 亿美元的损失。
- **本质认知**：如果说类型系统描述了一系列规则，那么 null 就是类型系统的**一个漏洞**——它是一个"不是值的值"。它在不同语言中有着不同的名字：`NULL`、`nil`、`null`、`None`、`Nothing`、`Nil` 和 `nullptr` 等。
- **为什么不能彻底废弃它**：null 在 1965 年就被创造出来，后来的语言大多沿用了这一设计。若想替换掉 null，一是需要花费大量精力更新以前的工程，二是必须想出更好的一个代号来代表"空"，新的问题随之而来。过重的历史包袱让我们无法立刻摆脱它。

**Q75: null 做了哪些恶？（null 的三个致命缺点）**

- **1. null 存在歧义**：一个值为 null 可能代表"该值从未被初始化""该值不合法""该值不需要""该值不存在"等多种含义。以 Java 的 HashMap 为例，`key` 允许为 null，存入空座位信息后，取出的 null 无法区分"这个座位不存在"与"这个座位上没人"：
  ```java
  HashMap<Long, String> map = new HashMap<>();
  map.put(null, null);
  map.put(1001L, "Yison");
  map.put(1002L, "Jilen");
  map.put(1003L, null); // 空座位
  ```
  在 Java 8 之前这类接口无法精确区分这两种情况，实际业务更复杂，这种歧义很容易造成不易察觉的 bug。
- **2. 难以控制的 NPE**：静态类型语言在编译期能做类型检查，但由于任何引用都可以是 null，调用一个 null 对象的方法就会产生 NullPointerException。以下代码编译顺畅通过，运行时却抛出丑陋的 NPE：
  ```java
  String str = "just haha";
  str = null;
  System.out.println(str.length());
  ```
  null 悄悄越过类型检查，等待运行时释放出一大批错误。
- **3. 冗余的防御式代码**：当类型系统允许万物为 null 时，就不得不写大量判空代码，且人们常把 null 与"字符串为空"混为一谈，违背业务初衷：
  ```java
  if (str == null || str.equals("")) {
      // Todo
  }
  ```
  推荐做法是像 Kotlin 或 Scala 一样用 `str.nonEmpty` 判断，表达上更具语义化。此外，null 还会让代码调试工作变得不容易。

**Q76: 既然 null 问题如此严重，当前 Java 中有哪几种解决 NPE 的方案？**

- **方案 1：函数内对无效值抛异常处理**：更倾向于抛出异常，特别是在 Java 里应使用专门的自定义 Checked Exception。但这种方案对经常出现无效值、有性能需求或代码中经常使用的函数并不适用；对于自身可取空值的类型（如集合类型），通常返回零长度的数组或集合，虽然会多出内存开销。
- **方案 2：采用 @NotNull/@Nullable 标注**：对不可为空的参数用 `@NotNull` 注解，明确参数是否可空，在模块入口就加以控制，避免非法的 null 值进一步传递。
- **方案 3：使用专门的 Optional 对象装箱**：对可能为 null 的变量用 `Optional` 包装，这类对象必须拆箱后才能参与运算，拆箱步骤就提醒使用者必须处理 null 值的情况。

**Q77: Java 8 中的 Optional 是什么？它有哪些不足之处？（引出 Kotlin 可空类型的动机）**

- **Optional 的基本用法**：对不确定是否存在的属性用 `Optional` 封装，比裸 null 更具语义，也更好地处理了 NPE 问题：
  ```java
  public class Seat {
      private Optional<Student> student;
      public Optional<Student> getStudent() { return student; }
  }
  public class Student {
      private Optional<Glasses> glasses;
      public Optional<Glasses> getGlasses() { return glasses; }
  }
  public class Glasses {
      private double degreeOfMyopia; // 近视度数
      public double getDegreeOfMyopia() { return degreeOfMyopia; }
  }
  ```
  注意：眼镜肯定存在度数，所以 `degreeOfMyopia` 声明为 `double`，不需要强行为其套一层 Optional。
- **优雅的数据提取**：如果用 `isPresent()` 层层嵌套判断，反而不加 Optional 更简洁。Optional 提供的 `map`、`flatMap`、`filter` 方法才能真正发挥作用：
  ```java
  public double getDegreeOfMyopia(Optional<Seat> seat) {
      return seat.flatMap(Seat::getStudent)
              .flatMap(Student::getGlasses)
              .map(Glasses::getDegreeOfMyopia)
              .orElse(0.00);
  }
  ```
  它还能在 `flatMap`、`map` 中处理数据，对传统 null 来说少不了嵌套大量 if-else。
- **不推荐 OptionalInt/OptionalLong/OptionalDouble**：它们字面上与 `Optional<Integer>` 类似，但不支持 `map`、`flatMap`、`filter` 方法，序列化时也会出问题，引入它们可能仅仅是为了避免自动装箱。
- **性能劣势（关键缺陷）**：多次测试发现 Optional 的耗时大约是普通判空的**数十倍**。因为 `Optional<T>` 是包含类型 T 引用的泛型类，使用时多创建了一次对象，当数据量非常大时频繁实例化对象会造成性能损失。除非未来 Java 支持值类（value class），这些开销才会消失。这也解释了为什么阅读开源代码时会发现 Optional 并没有被大范围使用。

**Q78: Kotlin 的可空类型是什么？它是如何从根源上消灭 NPE 的？（T? = T or null）**

- **类型层面区分可空与不可空**：与 Java 不同，Kotlin 可区分非空（non-null）和可空（nullable）类型。在 Java 中 `Long x = null` 合法，而 Kotlin 中 `val x: Long = null` 在编译时就报错：
  ```
  Error: Null cannot be a value of a non-null type Long
  ```
  这意味着在 Kotlin 中访问非空类型的变量**永远不会**抛出空指针异常。
- **可空类型语法**：Kotlin 没有 `Optional<T>` 这样的类，而是在任何类型后面加上 `?` 表示可空，如 `Int?` 实际上等同于 `Int? = Int or null`。通过合理使用，不仅能简化很多判空代码，还能有效避免空指针异常。
- **底层包装注意**：由于 null 只能被存储在 Java 的引用类型变量中，Kotlin 中基本数据的可空版本都会使用该类型的包装形式（如 `Int?` 对应 `Integer`）；同样，用基本数据类型作为泛型类的类型参数时，Kotlin 也会使用包装形式。
- **与 Java Optional 的对比**：Kotlin 可空类型实质上只是在 Java 的基础上进行了**语法层面的包装**，性能与 Java 近似一致，所以优于 Java 8 的 Optional 毋庸置疑。其优势可总结为：兼容性更好；性能更好、开销更低；语法简洁（写 `T?` 而不是 `Optional<T>`）。
- **改写示例**：用 Kotlin 把座位-学生-眼镜的例子改写后，可空性信息直接写在类型中：
  ```kotlin
  data class Seat(val student: Student?)
  data class Student(val glasses: Glasses?)
  data class Glasses(val degreeOfMyopia: Double)
  ```

**Q79: Kotlin 如何处理可空值？（安全调用 ?.、Elvis 操作符 ?:、非空断言 !!）**

- **安全调用 ?.**：当 student 存在时，才会调用其下的 `glasses`，链式调用自动短路，避免了 Java 中层层嵌套的 if 判空：
  ```kotlin
  println("该位置上学⽣眼镜度数：${s.student?.glasses?.degreeOfMyopia}")
  ```
- **Elvis 操作符 ?:**（又称合并运算符）：是 Java 三元运算符的类型安全版本，左侧为 null 时返回右侧默认值。假设座位上没戴眼镜时度数为 -1：
  ```java
  double result = student.glasses != null ? student.glasses.degreeOfMyopia : -1;
  ```
  ```kotlin
  val result = student.glasses?.degreeOfMyopia ?: -1
  ```
- **非空断言 !!.**：当确定某个值不为空时，用它强制取值；若实际上为 null，程序就会抛出 NPE 异常。类似于 Java 测试时常用的 Assert，用于测试或兜底场景：
  ```kotlin
  val result = student!!.glasses
  ```
  此外还有 `!is`、`as?` 等运算符。
- **组合使用**：需要让程序抛出异常时，可结合 Elvis 操作符与 throw，将可空值转为显式的异常流程：
  ```kotlin
  seat?.student?.glasses?.degreeOfMyopia ?: throw NullPointerException("some message")
  ```

**Q80: Kotlin 是如何在 JVM 上实现类型的可空性的？（反编译真相）**

- **反编译结果**：用 IDEA 的反编译工具查看 Kotlin 对应的 Java 代码，以 `getDegreeOfMyopiaKt(seat: Seat?)` 为例：
  ```java
  public final double getDegreeOfMyopiaKt(@Nullable Seat seat) {
      double var3;
      if (seat != null) {
          Student var10000 = seat.getStudent();
          if (var10000 != null) {
              Glasses var2 = var10000.getGlasses();
              if (var2 != null) {
                  var3 = var2.getDegreeOfMyopia();
                  return var3;
              }
          }
      }
      var3 = 0.0D;
      return var3;
  }
  ```
  可以看到 Kotlin 在方法参数上标注了 `@Nullable`，实现上依旧采用 if..else 对可空情况做判断。
- **这么做的原因**：兼容 Java 老版本（尤其是兼容 Android）；实现 Java 与 Kotlin 的 100% 互转换；在性能上达到最佳。因此 Kotlin 的可空类型本质上是一种编译期的类型检查机制 + 运行时的空判断，几乎没有额外开销。
- **与其他 NPE 方案的对应**：目前解决 NPE 一般有 try/catch 捕获异常、用 `Optional<T>` 类似类型包装、用 `@NotNull/@Nullable` 注解标注三种方式；Kotlin 的可空类型对应的正是"编译期类型检查 + @Nullable 标注"这一思路的语法级升华。

**Q81: 为什么说光有可空类型还不够？（用 Either 代替可空类型）**

- **可空类型的局限**：以上例子避开了 null 的情况，但在设置默认值的情况下，我们可能无法区分程序是否出错，也就无法获取到异常本身。忽略异常并不总是一种好的做法（可参见《Effective Java》第 77 条：不要忽略异常）。
- **Either 的概念**：如果熟悉 Scala，会自然地想到用 `Either[A, B]` 解决。Either 只有两个子类型 `Left`、`Right`：如果 `Either[A, B]` 对象包含的是 A 的实例，那它就是 Left 实例，否则是 Right 实例。通常 `Left` 代表出错的情况，`Right` 代表成功的情况。
- **用密封类实现**：Kotlin 虽然没有 Either 类，但可以通过密封类便捷地创造出来：
  ```kotlin
  sealed class Either<A, B>() {
      class Left<A, B>(val value: A) : Either<A, B>()
      class Right<A, B>(val value: B) : Either<A, B>()
  }
  ```
  然后将程序改造为返回 `Either<Error, Double>`，出错与成功一目了然：
  ```kotlin
  fun getDegreeOfMyopiaKt(seat: Seat?): Either<Error, Double> {
      return seat?.student?.glasses?.let {
          Either.Right<Error, Double>(it.degreeOfMyopia)
      } ?: Either.Left(Error())
  }
  ```
- **let 函数的概念**：`inline fun <T, R> T.let(block: (T) -> R): R = block(this)`，调用某对象的 let 函数，该对象会作为函数的参数，在函数块内通过 `it` 指代，返回值为函数块的最后一行或指定 return 表达式。
- **为什么这样写值得**：定义 Error 类，把所有步骤中的错误抽象为不同的子类型，便于最终处理以及后期排查错误。如果只是隐藏潜在异常，调用者通常会忽略可能发生的错误，这是很危险的设计。

**Q82: Kotlin 中如何进行类型检查？（is / !is 与 when 结合）**

- **基本语法**：Java 中用 `A instanceof T` 判断 A 是 T 或 T 的子类的实例，Kotlin 中则用 `is` 判断：
  ```kotlin
  if (obj is String) {
      print(obj.length)
  }
  if (obj !is String) { // 等同于 !(obj is String)
      print("Not a String")
  } else {
      print(obj.length)
  }
  ```
- **与 when 表达式结合**：利用上一章介绍的增强版 switch——when 表达式，代码可以变得更优雅：
  ```kotlin
  when (obj) {
      is String -> print(obj.length)
      !is String -> print("Not a String")
  }
  ```
- **关键点**：这里的 `obj` 为 `Any` 类型，虽然做了类型判断，但在没有类型转换的情况下却直接使用了 String 的 `length` 方法——这是 Kotlin 的**智能转换（Smart Casts）** 帮我们省略了一些工作，编译器自动完成了类型收窄。

**Q83: 什么是类型智能转换（Smart Casts）？它的适用条件是什么？**

- **概念**：Smart Casts 可以将一个变量的类型隐式地转变为另一种类型，完全由编译器完成。类型检查通过后，无需像 Java 那样显式强转即可使用子类型成员：
  ```kotlin
  val stu: Any = Student(Glasses(189.00))
  if (stu is Student) println(stu.glasses)
  ```
  在 Java 中则必须先做强转才能调用其属性：
  ```java
  Object stu = new Student(new Glasses(189.00));
  if (stu instanceof Student) System.out.println(((Student) stu).glasses);
  ```
- **对可空类型同样适用**：检查非空之后，编译器自动把 `Student?` 视为 `Student`：
  ```kotlin
  val stu: Student = Student(Glasses(189.00))
  if (stu.glasses != null) println(stu.glasses.degreeOfMyopia)
  ```
- **底层原理**：将第一个例子反编译成 Java，核心代码是 `if (stu instanceof Student) { Glasses var2 = ((Student) stu).getGlasses(); ... }`，与手写的 Java 版本一致——这其实是 Kotlin 编译器帮我们做出的转换。
- **适用条件（重要）**：根据官方文档，**当且仅当 Kotlin 编译器确定在类型检查后该变量不会再改变，才会产生 Smart Casts**。这点能确保多线程应用足够安全。因此对 `var` 声明、且可被外部修改的可空属性，Smart Casts 会失败：
  ```kotlin
  class Kot {
      var stu: Student? = getStu()
      fun dealStu() {
          if (stu != null) {
              print(stu.glasses) // 编译错误：stu 在其他线程中可能被修改
          }
      }
  }
  ```
- **解决方案**：将 `var` 改为 `val` 引用不可变，就能确保程序运行不产生额外副作用；或者用 `let` 函数简化：
  ```kotlin
  class Kot {
      var stu: Student? = getStu()
      fun dealStu() {
          stu?.let { print(it.glasses) }
      }
  }
  ```

**Q84: 当 Smart Casts 不适用时，如何做类型强制转换？（as 与 as? 的安全区别）**

- **as 强制转换**：实际开发中并不总能满足 Smart Casts 的条件，且它有时缺乏语义。当类型需要强制转换时，可用 `as` 操作符。针对上面 Smart Casts 失败的例子，在外部用 `as Student?` 先确定 stu 的类型：
  ```kotlin
  class Kot {
      var stu: Student? = getStu() as Student?
      fun dealStu() {
          if (stu != null) {
              print(stu.glasses)
          }
      }
  }
  ```
- **as 转换的不安全性**：如果 getStu 可能为空，却把转换类型写成 `Student`（非空）：
  ```kotlin
  var stu: Student? = getStu() as Student
  ```
  由于转换目标类型不可空，空值转换会抛出类型转换失败的异常，因此通常称之为"不安全"的类型转换。
- **安全版本 as?**：Kotlin 还提供了操作符 `as?`，转换失败时不会抛出异常，而是返回 null：
  ```kotlin
  var stu: Student? = getStu() as? Student
  ```
- **封装通用转换函数**：配合泛型封装一个更"有效"的类型转换方法。注意此写法在运行时受类型擦除影响，会抛 ClassCastException，IDEA 也会提示 `Warning: Unchecked cast: Any to T`：
  ```kotlin
  fun <T> cast(original: Any): T? = original as? T

  val ans = cast<String>(140163L)
  // Exception: java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.String
  ```
- **用 reified 解决**：Kotlin 的设计者们加入了关键字 `reified`（"具体化"），利用它可以在方法体内访问泛型指定的 JVM 类对象。注意方法前需要加 `inline` 修饰：
  ```kotlin
  inline fun <reified T> cast(original: Any): T? = original as? T
  ```

**Q85: 为什么说 Kotlin 比 Java 更面向对象？（类型结构的整体认知）**

- **纯面向对象的判断标准**：在"纯面向对象"或"完全面向对象"的语言中，程序里所有东西都应视作对象。SmallTalk 就是一门纯面向对象的语言；Java 并不能在真正意义上称为"纯面向对象"语言，因为它的原始类型（如 int）的值与函数等并不能视作对象。
- **Kotlin 的不同**：在 Kotlin 的类型系统中，**并不区分原始类型（基本数据类型）和包装类型**，使用的始终是同一个类型。虽然严格意义上不能说 Kotlin 是纯面向对象语言，但它显然比 Java 有更纯的设计。
- **类型层级概览**：Kotlin 的类型结构以 `Any` 为顶层（所有非空类型的根类型）、以 `Nothing` 为底层（所有类型的子类型），`String`、`Int`、`Double`、`Long` 等类型处于中间层级。

**Q86: 什么是 Any？为什么说它是所有非空类型的根类型？**

- **基本定位**：与 Object 作为 Java 类层级结构顶层类似，`Any` 类型是 Kotlin 中所有非空类型（如 String、Int）的超类。与 Java 不同的是，Kotlin 不区分"原始类型"和其他类型，它们都是同一类型层级结构的一部分。
- **未指定父类型时**：如果定义了一个没有指定父类型的类型，则该类型将是 Any 的直接子类型：
  ```kotlin
  class Animal(val weight: Double)
  ```
- **指定父类型时**：如果指定了父类型，则该父类型是新类型的直接父类型，但新类型的最终根类型仍为 Any：
  ```kotlin
  abstract class Animal(val weight: Double)
  class Bird(weight: Double, val flightSpeed: Double) : Animal(weight)
  class Fish(weight: Double, val swimmingSpeed: Double) : Animal(weight)
  ```
- **多接口情况**：如果类型实现了多个接口，它将具有多个直接的父类型，而 Any 同样是最终的根类型：
  ```kotlin
  interface ICanFly
  interface ICanBuildNest
  class Bird(weight: Double, flightSpeed: Double) : Animal(weight), ICanFly, ICanBuildNest
  ```
- **Type Checker 强制检查父子关系**：可以将子类型值存储到父类型变量中，但反过来不允许——"鸟类是动物，而动物不是鸟类"：
  ```kotlin
  var f: Animal = Bird(weight = 0.1, flightSpeed = 15.0)
  f = Fish(weight = 0.15, swimmingSpeed = 10.0)

  val b = Bird(weight = 0.1, flightSpeed = 15.0)
  val f2: Animal = b
  val b2: Bird = f2
  // Error: Type mismatch: inferred type is Animal but Bird was expected
  ```
- **与 Java 的互操作**：Kotlin 把 Java 方法参数和返回类型中用到的 Object 类型看作 Any（更确切地说是当作"平台类型"）。当在 Kotlin 函数中使用 Any 时，它会被编译成 Java 字节码中的 Object。

**Q87: 什么是平台类型（Platform Type）？为什么说它是 Kotlin 兼容 Java 的权衡设计？**

- **定义**：平台类型本质上是 Kotlin 不知道可空性信息的类型，所有 Java 引用类型在 Kotlin 中都表现为平台类型。当在 Kotlin 中处理平台类型的值时，它既可以被当作可空类型处理，也可以被当作非空类型来操作。
- **设计动机（为什么需要折中）**：试想，如果所有来自 Java 的值都被看成非空，就容易写出比较危险的代码；反之，如果 Java 中的值都强制当作可空，则会导致大量的 null 检查。综合考量，平台类型是一种折中的设计方案。
- **实践意义**：它保证了 Kotlin 与 Java 之间 smooth 的互操作，让开发者自行决定把 Java 传入的值当作可空还是非空来处理，同时把可空性判断的责任交给了调用方。

**Q88: 什么是子类型化（Subtyping）？为什么说 Any? 才是所有类型的根类型？**

- **区分继承与子类型化**：继承强调的是"实现上的复用"，而子类型化是一种类型语义上的替代关系。只懂 Java 的话很容易陷入误区：认为继承关系决定父子类型关系。事实上这是两个完全不同的概念。子类型化可表示为 `S <: T`，意味着在需要 T 类型值的地方，S 类型的值同样适用：
  ```kotlin
  fun printNum(num: Number) {
      println(num)
  }
  >>> val n: Int = 1
  >>> printNum(n)   // Int 是 Number 的子类型
  >>> 1
  >>> printNum("I am a String")
  error: type mismatch: inferred type is String but Number was expected
  ```
- **Any? 是 Any 的父类型**：虽然 Any 与 Any? 看起来没有继承关系，但在需要 `Any?` 类型值的地方，显然可以传入一个类型为 `Any` 的值，编译上不会产生问题。反之则不行——一个参数类型为 `Any` 的函数，传入符合 `Any?` 类型的 null 就会报错：
  ```
  error: null can not be a value of a non-null type Any
  ```
  因此可以大胆地说：**Any? 是 Any 的父类型，而且是所有类型的根类型**（虽然当前 Kotlin 官网文档没有介绍这一点）。
- **Any? 与 Any?? 的等价性**：Kotlin 中的可空类型可以看作所谓的 Union Type，近似于数学中的并集。用类型的并集来表示 `Any?`，可写为 `Any ∪ Null`；相应的 `Any??` 就表示为 `Any ∪ Null ∪ Null`，等价于 `Any ∪ Null`，即 **`Any??` 等价于 `Any?`**。所以说 Any? 是所有类型的根类型是没有问题的。

**Q89: 什么是 Nothing 与 Nothing?？它们位于类型层级的什么位置？**

- **Nothing 的定位**：在 Kotlin 类型层级结构的最底层是 Nothing 类型。顾名思义，Nothing 是没有实例的类型，Nothing 类型的表达式不会产生任何值。需要特别注意的是，**任何返回值为 Nothing 的表达式之后的语句都是无法执行的**。
- **与 return/break 的相似性**：这有点像 return 或 break 的作用。Kotlin 中 return、throw 等（流程控制中与跳转相关的表达式）返回值都为 Nothing。正是因为 Nothing 是所有类型的子类型，所以它可以"适配"任何期望的返回类型。
- **Nothing? 的含义**：与 Nothing 对应的 `Nothing?`，从字面上翻译可以解释为"可空的空"。与 Any、Any? 类似，`Nothing?` 是 Nothing 的父类型，所以 Nothing 处于 Kotlin 类型层级结构的最底层。
- **Nothing? 的本质**：它只能包含一个值——null，本质上与 null 没有区别。所以我们**可以使用 null 作为任何可空类型的值**，这正是类型系统一致性设计的体现。

**Q90: Kotlin 是如何处理自动装箱与拆箱的？（Int 与 Int? 的字节码真相）**

- **Kotlin 没有原始类型**：Kotlin 中并没有 int、float、double、long 这样的原始类型，取而代之的是它们对应的引用类型包装类 Int、Float、Double、Long。此外还有布尔（Boolean）、字符（Char）、字符串（String）及数组（Array），这让 Kotlin 比 Java 更接近"一切皆对象"的纯面向对象设计。
- **Int 底层的真相**：但说"一切皆对象"并不完全严谨。以 Int 为例，它虽然可以像 Integer 一样提供额外的操作函数，但底层实现存在差异。对比三者的字节码：
  ```kotlin
  val x1: Int = 18   // Kotlin
  ```
  ```java
  int x2 = 18;       // Java 基本类型
  Integer x3 = 18;   // Java 包装类型
  ```
  Kotlin 的 `Int` 在 JVM 中实际以 `int` 存储（对应字节码类型为 I，直接 `BIPUSH 18` + `ISTORE 1`，没有装箱）；Java 的 `Integer` 则通过调用静态方法 `Integer.valueOf` 装箱（`invokestatic #2 <java/lang/Integer.valueOf>`）。
- **Int? 才会装箱**：查看可空版本 `Int?` 的字节码，会发现它走了 `Integer.valueOf` 装箱路径：
  ```kotlin
  val x4: Int? = 18
  // 对应字节码
  // BIPUSH 18
  // INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
  // ASTORE 1
  ```
- **结论**：可以简单地认为：**Kotlin 中的 Int 类型等同于 int；Kotlin 中 Int? 等同于 Integer**。Kotlin 让 Int 看起来是引用类型，这只是语法上的一种技巧，目的是让 Kotlin 更接近纯面向对象语言，同时在不产生装箱开销的情况下保持性能。

**Q91: Kotlin 中"新"的数组类型是怎样的？与 Java 数组有何不同？**

- **创建语法**：Kotlin 抛弃了 Java 那种 C/C++ 风格的数组创建写法，使用 `arrayOf` 系列函数：
  ```kotlin
  val funList = arrayOf() // 声明长度为 0 的数组
  val funList = arrayOf(n1, n2, n3..., nt) // 声明并初始化长度为 t 的数组
  val funList = arrayOf<T>(n1, n2, n3..., nt) // 手动指定元素类型
  ```
  由于类型推导，编译器能够隐式推断出 funList 的元素类型；也可以手动指定类型参数。
- **Array 的本质**：Kotlin 中 `Array` 并不是一种原生的数据结构，而是一种 `Array` 类，甚至可以视作集合类的一部分。这一点与 Java 把数组作为语言原生特性完全不同。
- **原始类型数组**：Kotlin 为原始类型额外引入了一些实用类：`IntArray`、`CharArray`、`ShortArray` 等，分别对应 Java 中的 `int[]`、`char[]`、`short[]` 等：
  ```kotlin
  val xArray = intArrayOf(1, 2, 3)
  ```
  注意：`IntArray` 等**并不是 `Array` 的子类**，所以用两者创建的相同值的对象，并不是相同对象。由于 Kotlin 对原始类型有特殊优化（主要体现在避免了自动装箱带来的开销），建议优先使用原始类型数组。
- **数组的固有特性**：数组大小固定，且同一数组只能存放类型一样的数据（基本类型/引用类型）；数组在内存中地址连续，所以性能比较好。正因为数组大小固定，限制了很多使用场景，通常采用可自动扩容的集合（详见第 6 章）。

**Q92: 为什么需要泛型？（泛型：类型安全的利刃）**

- **问题场景**：Java 1.5 引入泛型。在此之前，ArrayList 底层用 Object 类型的数组实现，虽然更通用，但把类型安全检查完全交给了运行时，错误代码在编译期无法被发现：
  ```java
  List stringList = new ArrayList();
  stringList.add(new Double(2.0));
  String str = (String) stringList.get(0);
  // 执行结果：java.lang.ClassCastException: java.lang.Double cannot be cast to java.lang.String
  ```
  理想情况下编译器应该提示错误，但这段代码能编译通过，直到运行时才报错。我们真正需要的是在代码编译的时候就能发现错误，而不是让错误的代码发布到生产环境中——这正是泛型诞生的一个重要原因。
- **引入泛型之后**：编译期就能发现类型错误，防止运行时出现 ClassCastException：
  ```java
  List<String> stringList = new ArrayList<String>();
  stringList.add(new Double(2.0)); // 编译时报错，add(java.lang.String) 无法适配
  ```
- **自动类型转换**：泛型除了能在编译期做类型检查，还能在取值时自动进行类型转换，无需手动强转：
  ```java
  List<String> stringList = new ArrayList<String>();
  stringList.add("test");
  String str = stringList.get(0); // 不需要 (String) 强转
  ```
  同时无须为类型安全的 List 去创建 StringList、DoubleList 等类，只需在声明 List 的同时指定参数类型即可。
- **泛型的四大优势**：类型检查，能在编译时就检查出错误；更加语义化（声明 `List<String>` 便可知里面存储的是 String 对象）；自动类型转换，获取数据时不需要强制转换；能写出更加通用化的代码。

**Q93: 如何在 Kotlin 中使用泛型？（泛型类、泛型扩展函数与类型推导）**

- **基本语法**：Kotlin 和 Java 一样使用尖括号表示泛型，如 `<T>`、`<E>`。定义一个带 `find` 方法的泛型集合类，返回该对象或空值：
  ```kotlin
  class SmartList<T> : ArrayList<T>() {
      fun find(t: T): T? {
          val index = super.indexOf(t)
          return if (index >= 0) super.get(index) else null
      }
  }
  fun main(args: Array<String>) {
      val smartList = SmartList<String>()
      smartList.add("one")
      println(smartList.find("one"))            // 输出 one
      println(smartList.find("two").isNullOrEmpty()) // 输出 true
  }
  ```
  泛型类同样可以继承另一个类，从而使用 ArrayList 中的属性和方法。
- **泛型扩展函数**：除了定义新的泛型集合类，还可以利用扩展函数来实现同样的需求（扩展函数支持泛型）：
  ```kotlin
  fun <T> ArrayList<T>.find(t: T): T? {
      val index = this.indexOf(t)
      return if (index >= 0) this.get(index) else null
  }
  fun main(args: Array<String>) {
      val arrayList = ArrayList<String>()
      arrayList.add("one")
      println(arrayList.find("one"))            // 输出 one
      println(arrayList.find("two").isNullOrEmpty()) // 输出 true
  }
  ```
  当你只是需要对一个集合扩展功能的时候，使用扩展函数非常合适。
- **是否必须显式指定类型？** 在 Kotlin 中 `val arrayList = ArrayList()` 是不被允许的，而 Java 中 `List list = new ArrayList();` 可以。原因在于：泛型是 Java 1.5 版本才引入的，而集合类在 Java 早期版本中就已存在，为了保证兼容老版本代码，Java 允许声明没有具体类型参数的泛型类；Kotlin 基于 Java 6 版本，一开始就有泛型，不存在兼容老版本代码的问题。当然，因为 Kotlin 具有类型推导能力，可以这样写：
  ```kotlin
  val arrayList = arrayListOf("one", "two")
  ```

**Q94: 什么是类型约束（上界约束）？Kotlin 如何为泛型设定类型上界？**

- **问题的提出**：泛型本身就有类型约束的作用（如无法向 String 类型 List 添加 Double 对象），但有时需要更严格的约束。假设有一个盘子 `Plate` 可以放任何东西，现在想归类：一些盘子只能放水果：
  ```kotlin
  open class Fruit(val weight: Double)
  class Apple(weight: Double) : Fruit(weight)
  class Banana(weight: Double) : Fruit(weight)

  class FruitPlate<T : Fruit>(val t: T)
  ```
  这里的语法与 Kotlin 继承语法类似，`T` 只能是 Fruit 类及其子类的类型，其他类型不被允许：
  ```kotlin
  class Noodles(weight: Double) // 面条类
  val applePlate = FruitPlate<Apple>(Apple(100.0)) // 允许
  val applePlate2 = FruitPlate(Apple(100.0))       // 简化写法，也允许
  val noodlesPlate = FruitPlate<Noodles>(Noodles(200.0)) // 不允许
  ```
  这种类型的泛型约束，称之为**上界约束**。
- **与 Java 的对比**：Java 中也有类似的语法，区别在于 Java 使用 `extends` 关键字，而 Kotlin 使用 `:`：
  ```java
  class FruitPlate<T extends Fruit> {
      ...
  }
  ```
- **可空上界**：如果水果盘子不一定都要装水果，有时也可以空着（`FruitPlate(null)`），则需要在上界类型后加 `?`，保持与可空/非空变量声明一致的语法：
  ```kotlin
  class FruitPlate<T : Fruit?>(val t: T)
  ```
- **多个约束条件（where 关键字）**：单个条件只能约束类型上界和是否可空，多个条件时用 `where` 实现。例如一把刀只能用来切长在地上的水果（比如西瓜）：
  ```kotlin
  interface Ground {}
  class Watermelon(weight: Double) : Fruit(weight), Ground

  fun <T> cut(t: T) where T : Fruit, T : Ground {
      print("You can cut me.")
  }
  cut(Watermelon(3.0)) // 允许
  cut(Apple(2.0))      // 不允许
  ```

**Q95: Java 为什么无法声明一个泛型数组？（数组协变与 List 不变的矛盾）**

- **一个奇怪的现象**：假设 Apple 是 Fruit 的子类，思考 Apple[] 和 Fruit[]，以及 List<Apple> 和 List<Fruit> 的关系：
  ```java
  Apple[] appleArray = new Apple[10];
  Fruit[] fruitArray = appleArray;           // 允许
  fruitArray[0] = new Banana(0.5);           // 编译通过，运行报 ArrayStoreException
  List<Apple> appleList = new ArrayList<Apple>();
  List<Fruit> fruitList = appleList;         // 不允许
  ```
  Apple[] 类型的值可以赋值给 Fruit[] 类型，甚至可以把 Banana 对象添加进 fruitArray 编译器也能通过；而 List<Fruit> 类型的值从一开始就被禁止赋值为 List<Apple>。
- **本质原因**：数组是**协变**的，而 List 是**不变**的。简单说，`Object[]` 是所有对象数组的父类，而 `List<Object>` 却不是 `List<T>` 的父类。
- **Java 泛型是类型擦除的（伪泛型）**：无法在程序运行时获取到一个对象的具体类型。对比运行时获取类型：
  ```java
  System.out.println(appleArray.getClass()); // class [Ljavat.Apple;
  System.out.println(appleList.getClass());  // class java.util.ArrayList
  ```
  数组在运行时可以获取自身的类型，而 `List<Apple>` 运行时只知道自己是 List，无法获取泛型参数的类型。
- **结论**：Java 数组是协变的，若 A 是 B 的父类，则 A[] 也是 B[] 的父类。但假如给数组加入泛型后，将无法满足数组协变的原则，因为在运行时无法知道数组的类型。所以 Java 无法声明泛型数组。
- **Kotlin 中的表现**：Kotlin 泛型机制与 Java 一样，通过类型擦除实现，所以同样无法在运行时获取列表的泛型类型；但不同的是，**Kotlin 中的数组支持泛型，且不再协变**：
  ```kotlin
  val appleList = ArrayList<Apple>()
  println(appleList.javaClass) // class java.util.ArrayList

  val appleArray = arrayOfNulls<Apple>(3)
  val anyArray: Array<Any?> = appleArray // 不允许
  ```

**Q96: 为什么 Java 用类型擦除实现泛型？（向后兼容的罪）**

- **Java 的核心承诺**：向后兼容——老版本的 Java 文件编译后可以运行在新版本的 JVM 上。Java 一开始没有泛型，在 1.5 之前程序中有大量这样的代码：
  ```java
  ArrayList list = new ArrayList(); // 没有泛型
  ```
- **支持泛型的两种方式**：一是在没有泛型的语言上全新设计一个集合框架（全新实现现有集合类或创造新的集合类），优点是不需要考虑兼容老代码，写出更符合新标准的代码，缺点是需适应新语法，更严重的是可能无法改造老业务代码；二是在老的集合框架上改造，添加一些特性，在兼容老代码的前提下支持泛型。Java 明显选择了后者。
- **两个历史原因**：① Java 1.5 之前已经有大量的非泛型代码存在，若不兼容它们，会让使用者抗拒升级，因为他要付出大量时间去改造老代码；② Java 曾经有过重新设计集合框架的教训——Java 1.1 到 Java 1.2 过程中 Vector 到 ArrayList、HashTable 到 HashMap 的重构，引起了大量使用者的不满。所以 Java 为了填补自己埋下的坑，只能用类型擦除这种比较别扭的方式实现泛型。
- **类型擦除如何解决兼容问题**：两种声明编译后的字节码完全一样，说明低版本编译的 class 文件在高版本 JVM 上运行不会出问题：
  ```java
  ArrayList list = new ArrayList();                    // (1)
  ArrayList<String> stringList = new ArrayList<String>(); // (2)
  // 对应字节码：两者都是 new #2 java/util/ArrayList + invokespecial "<init>:()V"
  ```
- **泛型特性如何保证**：类型检查是编译器在编译前就帮我们进行的，不受类型擦除影响。类型自动转换则通过强制类型转换实现——查看 ArrayList 的 `get` 方法源码：
  ```java
  @SuppressWarnings("unchecked")
  E elementData(int index) {
      return (E) elementData[index]; // 强制类型转换
  }
  ```
  编译后的字节码也验证了这一点：`invokevirtual get:(I)Ljava/lang/Object;` 之后紧跟 `checkcast #5 class java/lang/String`。可见虽然 Java 受限于向后兼容使用了类型擦除，但它还是通过其他方式保证了泛型的相关特性。

**Q97: 类型擦除的矛盾是什么？（如何在运行时获取泛型参数类型）**

- **矛盾所在**：通常情况下使用泛型并不在意它是否是类型擦除的，但在有些场景（如序列化/反序列化）我们需要知道运行时泛型参数的类型。既然编译后泛型参数类型被擦除，能否主动指定参数类型来达到运行时获取的效果？可以，通过传入 `Class<T>`：
  ```kotlin
  open class Plate<T>(val t: T, val clazz: Class<T>) {
      fun getType() {
          println(clazz)
      }
  }
  val applePlate = Plate(Apple(1.0), Apple::class.java)
  applePlate.getType()
  // 结果：class Apple
  ```
- **该方式的限制**：无法获取一个泛型的类型，因为 `ArrayList<String>::class.java` 这样的写法本身不被允许：
  ```kotlin
  val listType = ArrayList<String>::class.java // 不被允许
  val mapType = Map<String, String>::class.java // 不被允许
  ```
- **匿名内部类方案**：利用匿名内部类可以在运行时知道 List 的具体泛型类型：
  ```kotlin
  val list1 = ArrayList<String>()
  val list2 = object : ArrayList<String>() {} // 匿名内部类
  println(list1.javaClass.genericSuperclass)
  println(list2.javaClass.genericSuperclass)
  // 结果：
  // java.util.AbstractList<E>
  // java.util.ArrayList<java.lang.String>
  ```
- **原理**：泛型类型擦除并不是真的将全部的类型信息都擦除，**还是会将类型信息放在对应 class 的常量池中**。既然还存储着相应的类型信息，就能通过相应方式获取。匿名内部类在初始化的时候就会绑定父类或父接口的相应信息，所以能通过获取父类或父接口的泛型类型信息来实现需求。
- **封装为通用工具类**：设计一个能获取所有类型信息的泛型类：
  ```kotlin
  import java.lang.reflect.ParameterizedType
  import java.lang.reflect.Type
  open class GenericsToken<T> {
      var type: Type = Any::class.java
      init {
          val superClass = this.javaClass.genericSuperclass
          type = (superClass as ParameterizedType).getActualTypeArguments()[0]
      }
  }
  fun main(args: Array<String>) {
      val gt = object : GenericsToken<Map<String, String>>() {} // 用 object 创建
      println(gt.type)
  }
  // 结果：java.util.Map<java.lang.String, ? extends java.lang.String>
  ```
- **Gson 的同类设计**：常用的 Gson 也使用了相同的设计（其 `TypeToken` 就是如此），在 Kotlin 中可以这样进行泛型反序列化：
  ```kotlin
  val json = ...
  val rType = object : TypeToken<List<String>>() {}.type
  val stringList = Gson().fromJson<List<String>>(json, rType)
  ```
  除了这种方式，Kotlin 中还有另一种获取泛型参数类型的方式——内联函数。

**Q98: 如何使用内联函数获取泛型？（reified 关键字的原理与边界）**

- **原理**：Kotlin 的内联函数在编译时，编译器会将相应函数的字节码插入调用处，也就是说参数类型也会被插入字节码中，因此可以获取参数的类型。只需加上 `reified` 关键字即可：
  ```kotlin
  inline fun <reified T> getType() {
      return T::class.java
  }
  ```
  `reified` 相当于在编译时把具体的类型插入相应的字节码中，我们就能在运行时获取到对应参数的类型了。
- **改进 Gson 的使用**：利用 Kotlin 的扩展特性对 Gson 进行功能扩展，在不改变原有类结构的情况下新增方法，使调用更加简洁优雅：
  ```kotlin
  inline fun <reified T : Any> Gson.fromJson(json: String): T {
      return Gson().fromJson(json, T::class.java)
  }
  // 使用
  val json = ...
  val stringList = Gson().fromJson<List<String>>(json)
  ```
- **注意边界（与 Java 的互操作限制）**：Java 并不支持主动指定一个函数是否是内联函数。Kotlin 中声明的普通内联函数可以在 Java 中调用，因为它会被当作一个常规函数；而用 `reified` 实例化参数类型的内联函数**不能在 Java 中调用**，因为它永远是需要内联的。

**Q99: 为什么 List<String> 不能赋值给 List<Object>？（泛型的不变性）**

- **反证法论证**：假设 `List<String>` 能赋值给 `List<Object>`，会怎样？一旦通过 `objList` 加入一个 `Integer`，再通过 `stringList` 取出来当成 String 使用就会出错——类型安全被彻底破坏：
  ```java
  List<String> stringList = new ArrayList<String>();
  List<Object> objList = stringList; // 假设可以，编译报错
  objList.add(Integer(1));
  String str = stringList.get(0);    // 将会出错
  ```
  Java 的设计师明确泛型最基本的条件就是保证类型安全，所以不支持这种行为。
- **但 Kotlin 中出现了"怪现象"**：下面这段代码竟然能编译成功：
  ```kotlin
  val stringList: List<String> = ArrayList<String>()
  val anyList: List<Any> = stringList // 编译成功
  ```
- **关键在于两个 List 不是同一种类型**：Java 与 Kotlin 的 `List` 接口定义不同：
  ```java
  public interface List<E> extends Collection<E> { ... }      // Java
  public interface List<out E> : Collection<E> { ... }        // Kotlin
  ```
  Kotlin 的 List 泛型参数前多了 `out` 关键字。普通方式定义的泛型是**不变**的：不管类型 A 和类型 B 是什么关系，`Generic<A>` 与 `Generic<B>` 都没有任何关系。比如在 Java 中 String 是 Object 的子类型，但 `List<String>` 并不是 `List<Object>` 的子类型；Kotlin 泛型的原理也一样。Kotlin 的 List 之所以允许 `List<String>` 赋值给 `List<Any>`，是因为它做了协变声明。

**Q100: 什么是支持协变的 List？（out 关键字：只读列表）**

- **协变的定义**：如果在定义的泛型类和泛型方法的泛型参数前加上 `out` 关键词，说明该泛型类及泛型方法是**协变**的——类型 A 是类型 B 的子类型，那么 `Generic<A>` 也是 `Generic<B>` 的子类型。比如 Kotlin 中 String 是 Any 的子类型，那么 `List<String>` 也是 `List<Any>` 的子类型，所以 `List<String>` 可以赋值给 `List<Any>`。
- **协变的代价：不能写入**：允许协变会导致类型不安全，Kotlin 通过在 API 层面限制"写"来保证安全。向这个 List 插入对象会编译报错，因为 `out` 声明的泛型参数类型不能作为方法的参数类型（但可以作为返回值类型）：
  ```kotlin
  val stringList: List<String> = ArrayList<String>()
  stringList.add("kotlin") // 编译报错，不允许
  ```
  从 Kotlin `List` 的源码也可以看出，它本来就没有定义 add、remove 及 replace 等方法，也就是说这个 List 一旦创建就不能再被修改——这便是将泛型声明为协变需要付出的代价。`out` 就是"出"的意思，可以理解为 List 是一个只读列表。
- **反证法看限制的必要性**：如果允许向支持协变的 List 插入新对象，就不再是类型安全的了，也就违背了泛型的初衷：
  ```kotlin
  val stringList: List<String> = ArrayList<String>()
  val anyList: List<Any> = stringList
  anyList.add(1)
  val str: String = anyList.get(0) // Int 无法转换为 String
  ```
- **与 Java 的对比**：Java 中也可以用通配符及泛型上界声明协变：`<? extends Object>`，但实现起来非常别扭，这也是 Java 泛型一直被诟病的原因。Kotlin 改进了它，能用简洁的方式对泛型做不同的声明。
- **@UnsafeVariance 注解**：通常情况下，若一个泛型类 `Generic<out T>` 支持协变，其方法的参数类型不能使用 T 类型。但 Kotlin 中可以通过 `@UnsafeVariance` 注解解除这个限制，比如上面 List 中的 `indexOf`、`contains` 等方法。

**Q101: 什么是支持逆变的 Comparator？（in 关键字：消费型）**

- **问题场景**：需要对 `MutableList<Double>` 排序，利用其 `sortWith` 方法传入比较器。但如果还要对 `MutableList<Int>`、`MutableList<Long>` 排序，难道要逐个定义 `intComparator`、`longComparator` 吗？数字类型有一个共同的父类 Number，Number 类型的比较器能否代替它的子类比较器？
  ```kotlin
  val numberComparator = Comparator<Number> {
      n1, n2 -> n1.toDouble().compareTo(n2.toDouble())
  }
  val doubleList = mutableListOf(2.0, 3.0)
  doubleList.sortWith(numberComparator) // 编译通过
  val intList = mutableListOf(1, 2)
  intList.sortWith(numberComparator)    // 编译通过
  ```
- **sortWith 的定义暴露了逆变**：查看 `sortWith` 方法定义，会发现在泛型参数前有一个 `in` 关键字：
  ```kotlin
  public fun <T> MutableList<T>.sortWith(comparator: Comparator<in T>): Unit {
      if (size > 1) java.util.Collections.sort(this, comparator)
  }
  ```
  `in` 和 `out` 一样，赋予了泛型另一个特性——**逆变**。简单来说，若类型 A 是类型 B 的子类型，那么 `Generic<B>` 反过来是 `Generic<A>` 的子类型，所以可以将 `numberComparator` 作为 `doubleComparator` 传入。
- **逆变的使用限制**：用 `out` 声明的泛型参数类型不能作为方法的参数类型，但可以作为方法的返回值类型；而 `in` 刚好相反——不能将泛型参数类型当作方法返回值的类型，但作为方法的参数类型没有任何限制：
  ```kotlin
  interface WriteableList<in T> {
      fun get(index: Int): T    // 错误：Type parameter T is declared as 'in' but occurs in 'out' position
      fun get(index: Int): Any  // 允许
      fun add(t: T): Int        // 允许
  }
  ```
  从 `in` 这个关键词也可以看出，`in` 就是"入"的意思，可以理解为消费内容，所以可以把该列表看作一个可写、可读功能受限的列表，获取的值只能为 Any 类型。在 Java 中使用 `<? super T>` 可以达到相同效果。

**Q102: 什么是协变与逆变？如何在实际中灵活运用？（声明处型变、使用处型变与星投影）**

- **in 与 out 的对立统一**：`in` 和 `out` 是一个对立面，其中 `in` 代表泛型参数类型逆变，`out` 代表泛型参数类型协变。从字面意思理解，`in` 代表着输入，`out` 代表着输出。它们与泛型不变相对立，统称为**型变**（variance），且可以用不同方式使用：声明处型变（如 `public interface List<out E> : Collection<E>`）和使用处型变（如上文 `sortWith` 方法中的 `Comparator<in T>`）。
- **实际应用：copy 函数的泛型化**。假设需要把数据从一个 Double 数组拷贝到另一个 Double 数组。一开始的做法只支持 Double，换成 Int 又要重写：
  ```kotlin
  fun <T> copy(dest: Array<T>, src: Array<T>) {
      if (dest.size < src.size) {
          throw IndexOutOfBoundsException()
      } else {
          src.forEachIndexed { index, value -> dest[index] = src[index] }
      }
  }
  ```
  但这种写法要求必须同一类型，`Array<Double>` 无法拷贝到 `Array<Number>`。此时用泛型变形解决——**in 声明在写入的 dest 上，out 声明在读取的 src 上**：
  ```kotlin
  // in 版本
  fun <T> copyIn(dest: Array<in T>, src: Array<T>) {
      ...
  }
  // out 版本
  fun <T> copyOut(dest: Array<T>, src: Array<out T>) {
      ...
  }
  var dest = arrayOfNulls<Number>(3)
  val src = arrayOf<Double>(1.0, 2.0, 3.0)
  copyIn(dest, src)  // 允许
  copyOut(dest, src) // 允许
  ```
  为什么两种方式都允许？因为 **in 版本中 T 是 Double 类型，dest 可以接收 Double 类型的父类型 Array（如 Array<Number>）；out 版本中 T 是 Number 类型，src 可以接收 Number 类型的子类型 Array（如 Array<Double>）**。in 和 out 的使用非常灵活。
- **星投影（*）**：如果你对泛型参数的类型不感兴趣，可以用类型通配符代替泛型参数。Java 中为 `?`，Kotlin 中用 `*`：
  ```kotlin
  val list: MutableList<*> = mutableListOf(1, "kotlin")
  list.add(2.0) // 出错
  ```
  `MutableList<*>` 与 `MutableList<Any?>` 不是同一种列表：后者可以添加任意元素，而前者只是通配某一种类型，编译器却不知道这是一种什么类型，所以不允许添加元素，因为那样会导致类型不安全。
- **星投影的实质**：通配符只是一种语法糖，背后也是用协变实现的——`MutableList<*>` 本质上就是 `MutableList<out Any?>`，所以使用通配符与协变有着一样的特性。
- **泛型变形在高阶函数中的应用**：Java 8 新增的 Stream 中就有其应用：
  ```java
  <R> Stream<R> map(Function<? super T, ? extends R> mapper);
  ```
  即 `? super T`（逆变）负责消费输入，`? extends R`（协变）负责产出结果。第 10 章中的责任链模式也会涉及高阶函数对泛型变形的应用。

---

## 第6篇 多态与扩展函数

**Q124: 多态（Polymorphism）是什么？Kotlin 中有哪几种多态形式？**

- **多态的概念**：多态是面向对象程序设计（OOP）的一个重要特征，计算机科学中的多态概念于 1967 年由克里斯托弗·斯特雷奇（Christopher Strachey）提出，它在语言使用中发挥了不可或缺的作用。
- **子类型多态（Subtype polymorphism）**：用一个子类继承一个父类，从而可以用子类型替换超类型实例。这是 Java 开发者最熟悉的一种多态形式。
- **参数多态（Parametric polymorphism）**：声明与定义函数、复合类型、变量时不指定具体类型，而是把类型作为参数使用，使该定义对各种具体类型都适用。泛型（第 5 章讨论过）就是其最常见的形式。
- **特设多态（Ad-hoc polymorphism）**：与参数多态相对，是为应对特殊情况所做的特殊处理。一个多态函数有多个不同的实现，依赖于其实参而调用相应版本的函数。它比前两者更加灵活，Kotlin 中的运算符重载、扩展都是它的具体表现。
- **总结**：如果说子类型多态和参数多态是"同一把工具切所有材料"，那么特设多态就是"根据不同的原材料选择不同的工具"，这正是它的灵活之处。

**Q125: 什么是子类型多态？在 Android 中如何体现？（7.1.1）**

- **定义**：当我们用一个子类继承一个父类的时候，就构成了子类型多态。用一个子类型替换超类型实例的行为，就是通常所说的子类型多态。
- **Android 中的例子**：数据持久化操作在任何平台都必不可少，Android 原生支持 SQLite 操作，一般我们会继承 SQLite 操作的相关类：
  ```kotlin
  class CustomerDatabaseHelper(context: Context) : SQLiteOpenHelper(context, ...) {
      override fun onUpgrade(p0: SQLiteDatabase?, p1: Int, p2: Int) {}

      override fun onCreate(db: SQLiteDatabase) {
          val sql = "CREATE TABLE if not exists $tableName ( id integer PRIMARY KEY ...)"
          db.execSQL(sql)
      }
  }
  ```
- **特点**：子类 `CustomerDatabaseHelper` 继承父类 `SQLiteOpenHelper` 后，就可以直接使用父类的所有方法，这种以子类替换超类型并复用其行为的能力，就是子类型多态在实践中的体现。

**Q126: 什么是参数多态？它解决了什么工程痛点？（7.1.2）**

- **痛点（重复代码）**：在完成数据库创建后，要把 `Customer` 存入客户端数据库，通常会写一个持久化方法：
  ```kotlin
  fun persist(customer: Customer) {
      db.save(customer.uniqueKey, customer)
  }
  ```
  但随着需求变动，还要持久化多种类型的数据。如果每种类型都写一个 `persist` 方法，多少有些烦琐。
- **抽象出统一接口**：因为采用键值对方式存储，所以需要获取不同类型对应的 `uniqueKey`，于是抽出一个接口：
  ```kotlin
  interface KeyI {
      val uniqueKey: String
  }

  class ClassA(override val uniqueKey: String) : KeyI { ... }
  class ClassB(override val uniqueKey: String) : KeyI { ... }
  ```
- **泛型改写**：将 `persist` 改写为泛型方法，即可处理不同类型的持久化：
  ```kotlin
  fun <T : KeyI> persist(t: T) {
      db.save(t.uniqueKey, t)
  }
  ```
- **形式化定义**：参数多态在程序设计语言与类型论中，指声明与定义函数、复合类型、变量时不指定其具体类型，而把这部分类型作为参数使用，使该定义对各种具体类型都适用。它建立在运行时的参数基础上，并且所有这些都是在不影响类型安全的前提下进行的。
- **总结**：最常见的参数多态形式就是泛型——它提供了一个"通用工具"，只要一个东西能"切"，就用这个工具来切割它。

**Q127: 当第三方类不可修改时，扩展（Extension）如何解决给它添加方法的问题？（7.1.3）**

- **场景痛点**：当业务类 `ClassA`、`ClassB` 是第三方引入的、不可被修改时，如果我们想给它们扩展一些方法（比如把对象转化为 Json），利用之前的子类型多态或参数多态技术就会比较麻烦。
- **Kotlin 的解法**：Kotlin 支持扩展的语法，利用扩展就能给 `ClassA`、`ClassB` 添加方法或属性，从而换一种思路解决问题：
  ```kotlin
  fun ClassA.toJson(): String {
      ...
  }
  ```
- **关键设计（为什么不污染原类）**：扩展属性和方法的实现运行在 `ClassA` 实例上，但它们的定义操作并不会修改 `ClassA` 类本身。这带来一个很大的好处——被扩展的第三方类免于被污染，从而避免了一些因父类修改而可能导致子类出错的问题。
- **与 Java 的对比**：在 Java 中只能依靠设计模式等办法解决，但相较而言依靠扩展的方案更加方便且合理。
- **本质归属**：这种扩展技术，其实是另一种被称为**特设多态**的技术。

**Q128: 什么是特设多态（Ad-hoc polymorphism）？它与参数多态有何区别？（7.1.4）**

- **引入动机**：想定义一个通用的 `sum` 方法时，也许会在 Kotlin 中这么写：
  ```kotlin
  fun <T> sum(x: T, y: T): T = x + y
  ```
  但编译器会报错——因为某些类型 T 的实例不一定支持加法操作，而且对自定义类我们更希望实现各自定制化的"加法语义上的操作"。
- **形象的比喻**：如果把参数多态做的事情打个比方：它提供了一个工具，只要一个东西能"切"，就用这个工具来切割它。然而现实中不是所有东西都能被切，而且材料也不一定相同。更合理的方案是，根据不同的原材料选择不同的工具来切它。
- **定义**：特设多态可以理解为：一个多态函数有多个不同的实现，依赖于其实参而调用相应版本的函数。相比更通用的参数多态，特设多态提供了"量身定制"的能力。
- **总结**：子类型多态通过继承实现，参数多态通过泛型实现，而特设多态在 Kotlin 中主要通过**扩展**与**运算符重载**实现，它符合面向对象设计基本原则之一——开放封闭原则。

**Q129: Kotlin 的运算符重载如何实现？`operator` 关键字的作用是什么？（7.1.4）**

- **语法结构**：只需声明一个 `operator fun` 函数即可。以给 `Area` 类重载 `+` 为例：
  ```kotlin
  data class Area(val value: Double)

  operator fun Area.plus(that: Area): Area {
      return Area(this.value + that.value)
  }

  fun main(args: Array<String>) {
      println(Area(1.0) + Area(2.0)) // 运行结果：Area(value=3.0)
  }
  ```
- **`operator` 关键字的作用**：将一个函数标记为重载一个操作符或者实现一个约定。这里注意 `plus` 是 Kotlin 规定的函数名，不是随便起的。
- **其它可重载的运算符**：除了加法 `plus`，还可以通过重载减法 `minus`、乘法 `times`、除法 `div`、取余 `mod`（Kotlin 1.1 版本开始被 `rem` 替代）等函数来实现重载运算符。
- **第 2 章语法的背后**：第 2 章遇到过的一些基础语法其实就是利用这种特性实现的：
  ```kotlin
  a in b   // 转换为 b.contains(a)
  f(a)     // 转换为 f.invoke(a)
  ```
- **后续应用**：本书第 9 章将展示如何利用 Kotlin 运算符重载的语法，来简化经典的设计模式。

**Q130: 什么是开放封闭原则（OCP）？为什么说扩展是遵循它的更优方案？（7.2.1）**

- **概念**：开放封闭原则（OCP，Open Closed Principle）是所有面向对象原则的核心：软件实体应该是可扩展而不可修改的——对扩展开放，对修改封闭。软件设计本身追求的目标就是封装变化、降低耦合，而开放封闭原则正是对这一目标的最直接体现；其他设计原则很多时候都是为实现这一目标服务的（例如以替换原则实现最佳的、正确的继承层次，就能保证不会违反开放封闭原则）。
- **现实痛点（滚雪球式修改）**：在进行 Android 开发时，为了某个需求引入了一个第三方库。某一天需求变动、当前库无法满足，且库作者暂时没有升级计划，于是你可能开始尝试修改库源码——这就违背了开放封闭原则。随着需求不断变更，问题就会如滚雪球般增长。
- **Java 的惯常方案及其缺陷**：Java 中的惯常应对方案是让第三方库类继承一个子类再添加新功能。然而，正如第 3 章谈过的，强行的继承可能违背"里氏替换原则"。
- **Kotlin 的更优解**：更合理的方案是依靠扩展这个语言特性。Kotlin 通过扩展一个类的新功能而无须继承该类，在大多数情况下都是一种更好的选择，从而可以合理地遵循软件设计原则。

**Q131: 如何声明扩展函数与扩展属性？扩展函数中的 `this` 指代什么？（7.2.2）**

- **声明语法**：扩展函数的关键字是接收者类型（receiver type，通常是类或接口的名称）作为前缀，语法为 `<Type>.<函数名>(参数)`：
  ```kotlin
  fun MutableList<Int>.exchange(fromIndex: Int, toIndex: Int) {
      val tmp = this[fromIndex]
      this[fromIndex] = this[toIndex]
      this[toIndex] = tmp
  }
  ```
  其中 `MutableList<T>` 是 Kotlin 标准库 Collections 中的 List 容器类，这里作为 receiver type；`exchange` 是扩展函数名；其余和 Kotlin 声明一个普通函数并无区别。
- **`this` 的灵活性**：Kotlin 的 `this` 比 Java 更灵活，扩展函数体里的 `this` 代表的是接收者类型的对象。因此函数体内可以直接通过 `this` 访问接收者对象的成员。
- **可空性注意点**：Kotlin 严格区分接收者是否可空。如果你的函数支持可空接收者，你需要重写一个可空类型的扩展函数（如 `MutableList<Int>?.xxx`）。
- **调用方式**：与调用普通成员方法一样简单：
  ```kotlin
  val list = mutableListOf(1, 2, 3)
  list.exchange(1, 2)
  ```
- **扩展属性**：与扩展函数类似，还能为一个类添加扩展属性。比如给 `MutableList<Int>` 添加一个判断元素和是否为偶数的属性 `sumIsEven`：
  ```kotlin
  val MutableList<Int>.sumIsEven: Boolean
      get() = this.sum() % 2 == 0

  val list = mutableListOf(2, 2, 4)
  list.sumIsEven
  ```

**Q132: 扩展函数的实现机制是什么？它会带来额外的性能消耗吗？（7.2.2）**

- **本质是静态方法**：将 `MutableList<Int>.exchange` 反编译成 Java 代码，可以看到它对应的是 `ExSampleKt` 类中的一个 `public static final void exchange(List $receiver, int fromIndex, int toIndex)` 静态方法。
- **静态方法的特点**：静态方法独立于该类的任何对象，不依赖类的特定实例，被该类的所有实例共享；被 `public` 修饰的静态方法本质上就是全局方法。
- **结论**：结合以上 Java 代码可以看出，扩展函数可以近似理解为静态方法——**扩展函数不会带来额外的性能消耗**。这也是我们可以放心使用它的根本原因。
- **与成员方法的本质差异**：由于扩展函数本质是静态方法、接收者只是编译后方法的一个参数，因此它可以被"重载"（针对不同接收者类型定义多个版本），但**不能被"覆盖"**——这正是成员函数与扩展函数之间最重要的区别（详见 7.4.1 调度方式部分）。

**Q133: 扩展函数的作用域是怎样的？定义在类内部与定义在包内有何区别？（7.2.2）**

- **定义在包内（顶级扩展）**：习惯上我们将扩展函数直接定义在包内：
  ```kotlin
  package com.example.extension

  fun MutableList<Int>.exchange(fromIndex: Int, toIndex: Int) {
      val tmp = this[fromIndex]
      this[fromIndex] = this[toIndex]
      this[toIndex] = tmp
  }
  ```
  在同一包内可以直接调用 `exchange` 方法；如果需要在其他包中调用，只需 `import` 相应的方法即可，这与调用 Java 的全局静态方法类似。
- **定义在类内部（成员扩展）**：实际开发时也可能将扩展函数定义在一个 Class 内部统一管理：
  ```kotlin
  class Extends {
      fun MutableList<Int>.exchange(fromIndex: Int, toIndex: Int) {
          val tmp = this[fromIndex]
          this[fromIndex] = this[toIndex]
          this[toIndex] = tmp
      }
  }
  ```
- **关键区别（为什么类外调用不到）**：当扩展函数定义在 `Extends` 类内部时，之前在类外部的调用位置就无法调用 `exchange` 了。即使加上 `public` 关键字也一样（实际上 Kotlin 中成员方法默认就是 `public` 修饰的）。原因是反编译后，该 `exchange` 方法上**已经没有 `static` 关键字修饰**了。
- **结论**：当扩展方法在一个 Class 内部时，只能在**该类及该类的子类中**进行调用，它不再是一个全局可见的静态方法。

**Q134: 为什么扩展属性不能有初始化器（默认值）？幕后字段是什么？（7.2.2）**

- **错误的写法**：如果给扩展属性添加默认值并写出如下代码：
  ```kotlin
  // 编译错误：扩展属性不能有初始化器
  val MutableList<Int>.sumIsEven: Boolean = false
      get() = this.sum() % 2 == 0
  ```
  这段代码无法编译通过。
- **根本原因（幕后字段）**：与扩展函数一样，扩展属性的本质也是对应 Java 中的静态方法（反编译后可以看到一个 `getSumIsEven` 的静态方法，与扩展函数类似）。由于扩展并没有实际地将成员插入类中，因此对扩展属性来说**幕后字段是无效的**——没有字段可供初始化器去赋值，它们的 `get()`/`set()` 行为只能由显式提供的 getters 和 setters 定义。
- **幕后字段（backing field）概念**：在 Kotlin 中，如果属性中的访问器使用默认实现，那么 Kotlin 会自动提供幕后字段 `field`，它仅可用于自定义 getter 和 setter 中。而扩展属性没有真正的字段，所以无法提供初始化器。

**Q135: 如何定义类似 Java 静态方法的扩展函数？（7.2.3）**

- **语法要求**：在 Kotlin 中，如果需要声明一个静态的扩展函数，必须将其定义在伴生对象（companion object）上。首先定义一个带伴生对象的类：
  ```kotlin
  class Son {
      companion object {
          val age = 10
      }
  }
  ```
- **在伴生对象上扩展**：如果不想在 `Son` 中定义扩展函数，而是在 `Son` 的伴生对象上定义，可以这么写：
  ```kotlin
  fun Son.Companion.foo() {
      println("age = $age")
  }
  ```
- **调用方式**：这样在 `Son` 没有实例对象的情况下也能调用到这个扩展函数，语法类似于 Java 的静态方法：
  ```kotlin
  object Test {
      @JvmStatic
      fun main(args: Array<String>) {
          Son.foo()
      }
  }
  ```
- **局限（第三方类）**：一切看起来都很顺利，但想让第三方类库也支持这样的写法时，我们发现并不是所有第三方类库中的类都存在伴生对象，只能通过它的实例来进行调用，这样会造成很多不必要的麻烦。

**Q136: 当扩展函数与类的成员方法同名时，调用哪一个？（7.2.3）**

- **现象**：已知如下类包含一个成员方法 `foo()`：
  ```kotlin
  class Son {
      fun foo() = println("son called member foo")
  }

  fun Son.foo() = println("son called extention foo")

  object Test {
      @JvmStatic
      fun main(args: Array<String>) {
          Son().foo()
      }
  }
  ```
  预期调用扩展函数 `foo()`，但实际输出为 `son called member foo`。
- **规则**：当扩展函数和现有类的成员方法同时存在时，Kotlin 将会默认使用类的**成员方法**——**同名的类成员方法的优先级总高于扩展函数**。
- **为什么这样设计**：看似不够合理，也很容易引发疑惑（"我定义了新方法，为什么还是调用了旧方法？"）。但换个角度思考：多人在开发时，如果每个人都对 `Son` 扩展了 `foo` 方法，很容易造成混淆；对第三方类库来说甚至是一场灾难——我们把不应该更改的方法改变了。所以使用时必须牢记：同名的类成员方法的优先级总高于扩展函数。

**Q137: 类中的扩展函数如何通过 `this` 和 `this@类名` 区分接收者？（7.2.3）**

- **背景**：Kotlin 的 `this` 比 Java 更灵活。当扩展函数声明在一个 `object`（或类）内部时，函数体里会同时存在两个可访问的实例：扩展接收者（被扩展类的实例）和声明扩展的类实例。
- **示例**：通过 `this@类名` 可以强行指定调用的 `this`：
  ```kotlin
  class Son {
      fun foo() {
          println("foo in Class Son")
      }
  }

  object Parent {
      fun foo() {
          println("foo in Class Parent")
      }

      @JvmStatic
      fun main(args: Array<String>) {
          fun Son.foo2() {
              this.foo()        // 扩展接收者 Son 的 foo
              this@Parent.foo() // 显式指定 Parent 的 foo
          }
          Son().foo2()
      }
  }
  ```
- **调用权限的关键（为什么类外调用不到）**：如果 `Son` 的扩展函数定义在 `Parent` 类内部，我们将无法在类外对其调用。即使设置访问权限为 `public`，它也只能在该类或该类的子类中被访问；如果设置访问权限为 `private`，那么在子类中也不能访问这个扩展函数。原因是第 7.2.2 节已经介绍过的——定义在类内部的扩展函数对应 Java 中没有 `static` 修饰的方法（反编译 `Parent` 可见 `public final void foo2(Son $receiver)`），只能作为成员方法在类的作用域内使用。

**Q142: 如何用扩展函数优化 Snackbar 的使用？（7.3.1）**

- **传统 API 的痛点**：Snackbar 被加入 Android 支持库，取代长期作为用户与应用之间消息传递接口的 Toast。其基本使用方式如下：
  ```kotlin
  Snackbar.make(parentView, message_text, duration)
      .setAction(action_text, click_listener)
      .show()
  ```
  实际使用中该 API 会给代码增加不必要的复杂性：我们不想每次都定义想要显示消息的时间，并且在填充一堆参数后，为什么还要额外调用 `show()`？
- **Anko 的辅助函数**：著名的开源项目 Anko 拥有 Snackbar 的辅助函数，使其更易于使用并使代码更简洁，其中一些参数是可选的：
  ```kotlin
  snackbar(parentView, "message")
  ```
  Anko 中 `snackbar` 的部分源码如下：
  ```kotlin
  inline fun View.snackbar(message: Int, @StringRes actionText: Int, noinline action: (View) -> Unit): Snackbar =
      Snackbar.make(this, message, Snackbar.LENGTH_SHORT)
          .setAction(actionText, action)
          .apply { show() }
  ```
- **进一步消除视图参数**：大多数情况下唯一需要的参数是消息。因为关心的仅仅是在屏幕底部显示消息，所以需要消除视图参数。借助扩展函数，在 Activity 中获取根视图可以通过 `find(android.R.id.content)` 完成，改良后的 Activity 扩展方法如下：
  ```kotlin
  inline fun Activity.snackbar(message: String) = snackbar(find(R.id.content), message)
  ```
- **Fragment 与 View 的版本**：Fragment 中有它所在 Activity 的引用，实现容易得多；而 View 并不一定附加在 Activity 上，需要做防御式判断——在尝试显示 Snackbar 之前必须确保 View 的 context 属性隐藏了一个 Activity 实例：
  ```kotlin
  inline fun Fragment.snackbar(message: String) = snackbar(activity.find(R.id.content), message)

  inline fun View.snackbar(message: String) {
      val activity = context
      if (activity is Activity) snackbar(activity.find(android.R.id.content), message)
      else throw IllegalStateException("视图必须要承载在Activity上.")
  }
  ```
- **小结**：通过为 `Activity`、`Fragment`、`View` 分别定义同名扩展函数，调用方只需 `snackbar("message")` 一行，充分体现了扩展函数按接收者类型分派（特设多态）的能力。

**Q143: 如何用扩展函数封装 Utils 类？（以 Context 的网络判断为例）（7.3.2）**

- **Java 的痛点**：在 Java 中习惯把常用代码放到工具类中，如 `ToastUtils`、`NetworkUtils`、`ImageLoaderUtils` 等。以 `NetworkUtils` 为例，判断手机网络是否可用：
  ```java
  public class NetworkUtils {
      public static boolean isMobileConnected(Context context) {
          if (context != null) {
              ConnectivityManager mConnectivityManager =
                  (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
              NetworkInfo mMobileNetworkInfo =
                  mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
              if (mMobileNetworkInfo != null) {
                  return mMobileNetworkInfo.isAvailable();
              }
          }
          return false;
      }
  }
  ```
  调用时：`Boolean isConnected = NetworkUtils.isMobileConnected(context);`
- **问题**：虽然比不封装优雅很多，但每次都要传入 `context`，造成的烦琐先不计较，重要的是可能会让调用者忽视 `context` 和 `mobileNetwork` 间的强联系。作为代码使用者，更希望在调用时省略 `NetworkUtils` 类名，并且让 `isMobileConnected` 看起来像 `context` 的一个属性或方法。
- **Kotlin 的扩展解法**：`Context` 是 Android SDK 自带的类，无法对其修改。在 Java 中只能通过继承 Context 新增静态成员方法实现，而在 Kotlin 中通过扩展函数就能简单地实现：
  ```kotlin
  fun Context.isMobileConnected(): Boolean {
      val mNetworkInfo = connectivityManager.activeNetworkInfo
      if (mNetworkInfo != null) {
          return mNetworkInfo.isAvailable
      }
      return false
  }
  ```
  只需将以上代码放入对应文件中即可，使用方式为：
  ```kotlin
  val isConnected = context.isMobileConnected()
  ```
- **生命周期提醒**：在 Android 中对 Context 的生命周期需要很好地把控，这里应该使用 `ApplicationContext`，防止出现生命周期不一致导致的内存泄漏或其他问题。
- **封装 Utils 的注意点**：Snackbar 也可以为其创建 `SnackbarUtils`，会提供非常多的便利。但需要注意：不能滥用这个特性（具体原因见 7.4.2）。

**Q144: 如何用扩展函数解决烦人的 `findViewById`？（7.3.3）**

- **痛点（样板代码）**：对视图控件操作前需要通过 `findViewById` 找到对应实例，界面里视图控件数量很多，早期 Android 开发中通常有一大片样板代码：
  ```java
  public class LoginActivity extends AppCompatActivity {
      Button loginButton;
      EditText nameEditText;
      EditText passwordEditText;
      ...
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_login);
          loginButton = findViewById(R.id.btn_login);
          nameEditText = findViewById(R.id.et_name);
          passwordEditText = findViewById(R.id.et_password);
          ...
      }
  }
  ```
  在老版本 SDK 中 `findViewById` 获取到的类型是 `View`，还需要类型强制转换：`loginButton = (Button) findViewById(R.id.btn_login);`
- **泛型扩展函数简化**：利用扩展函数可以简化这个烦琐的过程：
  ```kotlin
  fun <T : View> Activity._view(@IdRes id: Int): T {
      return findViewById(id) as T
  }
  ```
  现在代码可改为：
  ```kotlin
  loginButton = _view(R.id.btn_login)
  nameEditText = _view(R.id.et_name)
  passwordEditText = _view(R.id.et_password)
  ```
- **结合高阶函数更进一步**：极简主义者会想——这些实例似乎只充当了"临时变量"的角色，能否直接对 `R.id.*` 操作呢？可以，利用高阶函数做如下改动（此处以简化 onclick 为例）：
  ```kotlin
  fun Int.onClick(click: () -> Unit) {
      // _view 为我们之前定义的简化版 findViewById
      val tmp = _view<View>(this).apply {
          setOnClickListener {
              click()
          }
      }
  }

  R.id.btn_login.onClick { println("Login…") }
  ```
- **终极方案（kotlin-android-extensions 插件）**：Kotlin 还提供了扩展插件，gradle 默认集成：
  ```gradle
  apply plugin: 'kotlin-android-extensions'
  ```
  回到最初的 `LoginActivity`，只需额外 `import kotlinx.android.synthetic.main.activity_login.*`，即可直接用视图组件的 id 名称来操作视图：
  ```kotlin
  btn_login.setOnClickListener {
      println("MainKotlinActivity onClick Button")
  }
  ```
- **性能疑问的解答（反编译看实现）**：省略了 `R.id.` 几个字符，引入是否会造成性能问题？反编译后对应的 Java 代码显示：第一次使用控件时在 `_$_findViewCache` 缓存集合中查找，有就直接使用，没有就通过 `findViewById` 查找并添加到缓存集合中；还提供了 `_$_clearFindViewByIdCache()` 方法用于清除缓存。也就是说，扩展插件利用**缓存**的方式让我们开发更方便、更快捷，并没有完全离开 `findViewById`。
- **注意**：Fragment 的 `onDestroyView()` 方法中默认调用了 `_$_clearFindViewByIdCache()` 清除缓存，而 Activity 没有。感兴趣还可以了解 Google 推出的 Android 扩展库 Android KTX。

**Q145: 什么是静态调度与动态调度？扩展函数为什么总是静态调度？（7.4.1）**

- **背景概念**：Kotlin 是静态类型语言，创建的每个对象不仅具有运行时类型，还具有编译时类型。在使用扩展函数时，必须清楚地了解静态和动态调度之间的区别。
- **Java 中的动态调度**：声明一个名为 `base` 的变量，它具有编译时类型 `Base` 和运行时类型 `Extended`。调用 `base.fun()` 时将**动态调度**该方法——运行时类型（`Extended`）的方法被调用：
  ```java
  class Base {
      public void fun() { System.out.println("I'm Base foo!"); }
  }

  class Extended extends Base {
      @Override
      public void fun() { System.out.println("I'm Extended foo!"); }
  }

  Base base = new Extended();
  base.fun(); // 输出 I'm Extended foo!
  ```
- **Java 中的静态调度（重载）**：当调用重载方法时，调度变为静态并且仅取决于**编译时类型**：
  ```java
  void foo(Base base) { ... }
  void foo(Extended extended) { ... }

  Base base = new Extended();
  foo(base); // 即使 base 本质上是 Extended 的实例，最终还是会执行 Base 版本的方法
  ```
- **扩展函数始终静态调度**：扩展函数都有一个接收者（receiver），由于接收者实际上只是字节码中编译方法的参数，因此你可以**重载**它，但不能**覆盖**它。这是成员函数和扩展函数之间最重要的区别：前者是动态调度的，后者总是静态调度的。示例：
  ```kotlin
  open class Base
  class Extended : Base()

  fun Base.foo() = "I'm Base.foo!"
  fun Extended.foo() = "I'm Extended.foo!"

  fun main(args: Array<String>) {
      val instance: Base = Extended()
      val instance2 = Extended()
      println(instance.foo())  // 输出：I'm Base.foo!（只看编译时类型 Base）
      println(instance2.foo()) // 输出：I'm Extended.foo!
  }
  ```
  由于只考虑了编译时类型，第 1 个打印调用 `Base.foo()`，第 2 个打印调用 `Extended.foo()`。

**Q146: 类中声明的扩展函数如何被调度？什么是调度接收器与扩展接收器？（7.4.1）**

- **背景**：如果在类的内部声明扩展函数，那么它**不是静态的**。如果该扩展函数加上 `open` 关键字，可以在子类中进行重写（override）。但这是否意味着它将被动态调度？这是一个比较尴尬的问题：当在类内部声明扩展函数时，它同时具有调度接收器和扩展接收器。
- **概念**：
  - **扩展接收器（extension receiver）**：与 Kotlin 扩展密切相关的接收器，表示我们为其定义扩展的对象。
  - **调度接收器（dispatch receiver）**：扩展被声明为成员时存在的一种特殊接收器，它表示声明扩展名的类的实例。
  ```kotlin
  class X {
      fun Y.foo() = "I'm Y.foo"
  }
  ```
  上例中，`X` 是调度接收器而 `Y` 是扩展接收器。如果扩展函数声明为 `open`，则它的**调度接收器只能是动态的**，而**扩展接收器总是在编译时解析**。
- **完整示例**：
  ```kotlin
  open class Base
  class Extended : Base()

  open class X {
      open fun Base.foo() {
          println("I'm Base.foo in X")
      }
      open fun Extended.foo() {
          println("I'm Extended.foo in X")
      }
      fun deal(base: Base) {
          base.foo()
      }
  }

  class Y : X() {
      override fun Base.foo() {
          println("I'm Base.foo in Y")
      }
      override fun Extended.foo() {
          println("I'm Extended.foo in Y")
      }
  }

  X().deal(Base())        // 输出 I'm Base.foo in X
  Y().deal(Base())        // 输出 I'm Base.foo in Y —— dispatch receiver 被动态调度
  X().deal(Extended())    // 输出 I'm Base.foo in X —— extension receiver 被静态调度
  Y().deal(Extended())    // 输出 I'm Base.foo in Y
  ```
- **分析**：`Extended` 的扩展函数始终没有被调用，并且此行为与之前静态调度例子中所看到的一致。决定两个 `Base` 类扩展函数执行哪一个，直接因素是执行 `deal` 方法的类的运行时类型。
- **扩展函数使用总结**：
  - 如果该扩展函数是顶级函数或成员函数，则**不能被覆盖**；
  - 无法访问其接收器的**非公共属性**；
  - **扩展接收器总是被静态调度**。

**Q147: 扩展函数如何被滥用？（以图片加载为例）（7.4.2）**

- **滥用场景**：扩展函数提供了非常多便利，但实际应用中可能会被滥用。上一节提到的 `ImageLoaderUtils`，其中以加载网络图片为例，容易写出如下扩展：
  ```kotlin
  fun Context.loadImage(url: String, imageView: ImageView) {
      GlideApp.with(context)
          .load(url)
          .placeholder(R.mipmap.img_default)
          .error(R.mipmap.ic_error)
          .into(imageView)
  }

  // ImageActivity.kt 中使用
  this.loadImage(url, imgView)
  ```
- **问题分析**：也许在使用时并没有感觉出奇怪的地方，但实际上，我们**并没有以任何方式扩展现有类**——上述代码仅仅为了在函数调用的时候省去参数，这是一种滥用扩展机制的行为。`Context` 作为 "God Object" 已经承担了太多责任，基于 `Context` 扩展还很可能产生 `ImageView` 与传入上下文生命周期不一致导致的很多问题。
- **正确的做法（在 ImageView 上扩展）**：应该在 `ImageView` 上进行扩展：
  ```kotlin
  fun ImageView.loadImage(url: String) {
      GlideApp.with(this.context)
          .load(url)
          .placeholder(R.mipmap.img_default)
          .error(R.mipmap.ic_error)
          .into(this)
  }
  ```
  这样在调用的时候不仅省去了更多的参数，而且 `ImageView` 的生命周期也得到了保证。
- **进一步规范化（二次封装）**：实际项目中还需要考虑网络请求框架替换及维护的问题，一般会对图片请求框架进行二次封装：
  ```kotlin
  object ImageLoader {
      fun with(context: Context, url: String, imageView: ImageView) {
          GlideApp.with(context)
              .load(url)
              .placeholder(R.mipmap.img_default)
              .error(R.mipmap.ic_error)
              .into(imageView)
      }
      ...
  }
  ```
- **结论**：虽然扩展函数能够提供许多便利，我们还是应该注意在恰当的地方使用它。扩展函数实质对应 Java 中的静态方法，使用时应该以 Java 中静态方法的标准来规范自己——**不能为了省参数而滥用扩展，把本不该属于该接收者的方法强行挂到它身上**；在新特性面前也不能过于喜新厌旧，应结合面向对象思想和设计模式来进行规范。

---

## 第7篇 元编程与反射

**Q148: 如何把 data class 转换成 Map？直接手写实现有什么问题？（8.1 开篇引子）**

- **需求背景**：将 data class 转换为 Map 是一个非常常见的数据转换需求，相信大部分程序员都编写过类似的代码。
- **直接实现**：看似简单，对所有类型一个个写 `toMap` 即可：
  ```kotlin
  data class User(val name: String, val age: Int)

  object User {
      fun toMap(a: User): Map<String, Any> = hashMapOf("name" to a.name, "age" to a.age)
  }
  ```
- **存在的问题**（两种坏实践）：
  - **违背 DRY（Don't Repeat Yourself）原则**：每个类型拥有不同属性，对每一个新类型都需要重复实现结构雷同的 `toMap`，在 data class 数量非常多的情况下会产生大量样板代码；
  - **属性名极易写错**：所有属性名都需要人工编写，很难保证 100% 正确，data class 越多问题越严重。
- **引出方向**：需要一种"用一个函数解决所有类型"的手段，这就自然想到了反射。

**Q149: 如何用反射统一实现 toMap？（反射方案与优点）**

- **统一实现**：由于所有类型只需要一个函数，可以定义全局的 `Mapper` 对象来完成需求：
  ```kotlin
  object Mapper {
      fun <A : Any> toMap(a: A) =
          a::class.memberProperties.map { m ->
              val p = m as KProperty
              p.name to p.call(a)
          }.toMap()
  }
  ```
- **核心机制**：`a::class.memberProperties` 获取类型 A 中所有的成员属性，再通过 `KProperty.call(a)` 反射调用属性的 Getter 取得值，从而生成键值对。
- **完美之处**：
  - **适用于所有 data class**：只需调用一个 `Mapper.toMap` 就能把任意类型转化成 Map；
  - **不再需要手工创建 Map**：所有属性名都是自动根据 KClass 对象获取的，不存在写错的可能。

**Q150: 什么是元数据？a::class 到底是什么？（程序和数据）**

- **元数据的概念**：描述数据的数据称之为元数据。如果把 `User` 看成描述现实概念的数据结构，那么在传入参数类型为 `User` 时，`a::class` 就可以看成**描述 User 类型的数据**。
- **a::class 的类型**：`a::class` 的类型是 `KClass`，是 Kotlin 中描述类型的类型，通常被称为 **metaclass（元类）**。
- **与元编程的关联**：将程序看成描述需求的数据，那么描述程序的数据就是程序的元数据；像这样操作元数据的编程，就可以称之为元编程。

**Q151: 什么是元编程？"程序即数据，数据即程序"如何理解？（8.1.1）**

- **一句话概括**：元编程可以用一句话概括——**程序即是数据，数据即是程序**。它不是简单的"反射"（那只是一种实现方式）。
- **两个方向**：
  - **前半句（程序即数据）**：指访问描述程序的数据，如通过反射获取类型信息；
  - **后半句（数据即程序）**：指将这些数据转化成对应的程序，也就是所谓的**代码生成**。
- **代码生成的例子**（来自维基百科）：下面这个 shell 脚本创建了一个名为 program 的文件，并通过 echo 命令将代码写入该文件，把"程序作为程序的输出"：
  ```sh
  #!/bin/sh
  # metaprogram
  echo '#!/bin/sh' > program
  for i in $(seq 992)
  do
      echo "echo $i" >> program
  done
  chmod +x program
  ```

**Q152: 元编程与高阶函数的关系？同像性（homoiconicity）是什么？**

- **更高级的抽象**：元编程就像高阶函数一样，是一种更高阶的抽象——高阶函数将**函数**作为输入或输出，而元编程则是将**程序本身**作为输入或输出。
- **元数据能否直接作为程序**：元数据经过操作之后能否直接作为程序使用（"程序即数据"与"数据即程序"中的"数据"是否同一）？不同语言答案不同：
  - **Kotlin**：显然无法把一个 KClass 修改之后再反过来生成一个新的 class 来使用；
  - **Lisp**：一切都可以视为 LinkedList，而 Lisp 的宏允许直接将这些 LinkedList 作为程序的一部分。
- **同像性（homoiconicity）**：像 Lisp 这样的"程序的结构与其句法相似"的一致性称为同像性。如果一门语言具备同像性，说明该语言的文本表示（通常指源代码）与其抽象语法树（AST）具有相同的结构（AST 和语法是同形的）。该特性允许使用相同的表示语法，将语言中的所有代码当成资料存取和转换，提供了"**代码即数据**"的理论前提。

**Q153: 元编程的优点和缺点分别是什么？工程实践上应遵循什么原则？**

- **优点**：
  - **消除样板代码**：如开篇例子，原本需要对每个类型编写特定的转化代码，现在只需要统一的一个函数即可实现。
- **缺点**：
  - **学习成本高**：在没听说过相关技术之前，程序员们通常会感觉摸不着头脑；
  - **代码不够直接**：编写的代码需要进一步思考才能被理解——如反射代码要求读者对 Kotlin 的 reflection API 有所了解才能阅读，在支持宏的语言中则更难以理解。
- **工程原则（Least Power 原则）**：使用最初级的、最简单的、能满足你需求的技术，而不能单纯为了炫耀而采用某些高级的特性或技术。

**Q154: 常见的元编程技术有哪些？（8.1.2）**

- **运行时通过 API 暴露程序信息**：多次提及的**反射**就是这种实现思路。
- **动态执行代码**：多见于脚本语言，如 JavaScript 就有 `eval` 函数，可以动态地将文本作为代码执行。
- **通过外部程序实现目的**：如**编译器**，在将源文件解析为 AST 之后，可以针对这些 AST 做各种转化。最典型的例子是语法糖——编译器会将这部分代码的 AST 转化为相应等价的 AST，这个过程通常被称为 desugar（解语法糖）。
- **补充概念：什么是 AST**：抽象语法树是源代码语法结构的一种抽象表示，以树状形式表现编程语言的语法结构，树上每个节点都表示源代码中的一种结构。说语法是"抽象"的，是因为这里的语法不会表示出真实语法中出现的每个细节——比如嵌套括号被隐含在树的结构中，并没有以节点的形式呈现；而类似于 if-condition-then 这样的条件跳转语句，可以使用带有两个分支的节点来表示。

**Q155: 反射、宏、模板元编程、路径依赖类型各自的原理和特点？**

- **反射（也称为自反）**：指**元语言（描述程序的数据结构）和要描述的语言是同一种语言**的特性。Kotlin 中的 KClass 就是一个 Kotlin 的类，同时它的实例又能作为描述其他类的元数据——用 Kotlin 描述 Kotlin 自身信息的这种行为就是反射，称为"自反"其实更贴合定义。除了运行时反射，也有许多语言支持**编译期反射**，它通常需要和宏等技术结合使用：编译器将当前程序的信息作为输入传入宏，并将其结果作为程序的一部分。
- **宏**：
  - **C 语言的宏**：本质是编译时简单的文本替换。如下面定义的交换两个整型的宏 SWAP，编译器在编译时直接将其替换为 `int temp = a; a = b; b = temp`：
    ```c
    #define SWAP(a,b) {int _temp = a; a = b; b = _temp}
    int main() {
        int i = 0;
        int j = 1;
        SWAP(i, j);
        return 0;
    }
    ```
  - **C 宏的严重问题**：这种简单粗暴的方式存在非常严重的问题——假如调用宏的代码里已经定义了名为 `_temp` 的变量，就会造成重复定义；
  - **Lisp 和 Scala 的宏**：更加强大，它们会直接在宏展开时暴露抽象语法树（AST），可以在宏定义中直接操作这些 AST，并生成需要的 AST 作为程序返回；
  - **Kotlin 的现状**：Kotlin 目前不支持宏，而且短期看来也没有要支持宏的迹象。
- **模板元编程**：C++ 的招牌特性，甚至《Modern C++ Design》整本书都围绕这一特性展示各种奇技淫巧。C++ 的模板元编程还具备图灵完备性，理论上可以完成所有的编程任务。由于它和 Kotlin 关系不大，书中不再展开。
- **路径依赖类型**：维基百科将此特性归为一种元编程，支持路径依赖类系的语言通常可以在编译的时候从类型层面避免大部分 bug。由于该特性通常只在 Haskell、Scala 等学术型语言中出现，实践中应用并不广泛。

**Q156: Kotlin 反射与 Java 反射的基本数据结构有何对应关系？（8.2.1）**

- **背景**：反射是大部分程序员非常熟悉的技术，很多著名开源框架（如 Spring）都不可避免地使用了反射。反射的引入极大增加了 Java 编程语言的灵活性，使一些以前难以实现的需求得以实现，大幅度减轻了重复编码的工作量。Kotlin 声称 100% 兼容 Java，自然支持所有 Java 支持的反射特性。
- **对比两张数据结构图（图8-1/8-2）可得出三点对应关系**：
  1. **KClass ↔ Class**：可看作同一个含义的类型，并且可以通过 `.java` 和 `.kotlin` 方法在 KClass 和 Class 之间互相转化；
  2. **KCallable ↔ AccessibleObject**：都可理解为"可调用元素"。Java 中构造方法是一个独立的类型（Constructor），而 Kotlin 则统一作为 KFunction 处理；
  3. **KProperty ↔ Field 不太相同**：Kotlin 的 KProperty 通常指相应的 Getter 和 Setter（只有可变属性才有 Setter）的整体作为一个 KProperty（通常情况 Kotlin 并不存在字段的概念），而 Java 的 Field 通常仅仅指字段本身。

**Q157: 为什么 Kotlin 编译器要在字节码中存储额外信息？（kotlin.Metadata）**

- **原因**：Kotlin 反射整体上与 Java 非常接近，但某些情况下（通常是碰到一些 Kotlin 独有的特性时）Kotlin 编译器会在生产的字节码中存储额外信息，这些信息目前是通过 `kotlin.Metadata` 注解实现的——Kotlin 编译器会将 Metadata 标注在这些类上。
- **与 Lambda 实现的关联**：前文提到 Kotlin 的 Lambda 没有采用 `invokeDynamic` 指令实现，这可能也是一个很大的原因——要实现将现有 Metadata 机制适应新的 invokeDynamic 指令，显然有巨大的工作量和兼容性问题，稍有不慎就可能导致 bug 频出。
- **存储形式**：注解信息直接以注解形式存储在字节码文件中，以便运行时反射获取这些数据。这为后文介绍注解（8.3）埋下伏笔。

**Q158: 用反射实现 toMap，Kotlin 相比 Java 好在哪？（可读性与优雅）**

- **等价 Java 实现**：将开篇 Kotlin 的反射实现翻译成等价的 Java 8 代码：
  ```java
  public static <A> Map<String, Object> toMap(A a) {
      Field[] fs = a.getClass().getDeclaredFields();
      Map<String, Object> kvs = new HashMap<>();
      Arrays.stream(fs).forEach((f) -> {
          f.setAccessible(true);
          try {
              kvs.put(f.getName(), f.get(a));
          } catch (IllegalAccessException e) {
              e.printStackTrace();
          }
      });
      return kvs;
  }
  ```
- **可读性差异**（此处可读性指代码容易理解的程度，不是代码长度）：
  - **Kotlin 更直接**：直接反映了函数意图——读取所有属性，并将键值对生成 Map；
  - **Java 多出许多额外元素**：先读取该类所有字段，创建 Map，使用 stream 的 forEach 遍历，将每个字段的键值放到 Map 中，返回这个 Map，同时还需要处理可能的异常；
  - **访问方式更合理**：Java 版本直接强制访问字段键值，需强制设置可访问性（`setAccessible(true)`）；而 Kotlin 版本中 KProperty 的 `call` 函数实际上是直接调用 Getter，这是更合理的方案。
- **结论（优雅）**：从功能上看两者一致，但 Kotlin 用更少的元素表达同样甚至更多的内涵，函数实现直接体现了函数意图，这就是我们说的**优雅**。

**Q159: KClass 有哪些特别的属性或函数？如何利用它们实现 Kotlin 特性的反射？（8.2.2）**

- **特别之处**：KClass 的特别属性/函数主要集中在 Kotlin 独有、Java 没有对应物的特性上，例如**获取 object 实例**等（见表8-1）。
- **自然数编码示例**（用 sealed class 定义皮亚诺自然数）：
  ```kotlin
  import kotlin.reflect.full.*

  sealed class Nat {
      companion object {
          object Zero : Nat()
      }

      val Companion._0: Nat
          get() = Zero

      fun <A : Nat> Succ<A>.preceed(): A = this.prev
  }

  data class Succ<N : Nat>(val prev: N) : Nat()

  fun <A : Nat> Nat.plus(other: A): Nat = when {
      other is Succ<*> -> Succ(this.plus(other.prev)) // a + S(b) = S(a + b)
      else -> this
  }
  ```
- **declaredMemberExtensionFunctions 等函数的真实含义**：这类函数返回的结果指的是**这个类中声明的扩展函数**，而不是在其他位置声明的本类扩展函数。例如上面例子中 `Nat::class.declaredMemberExtensionFunctions` 返回了该类中定义的 `Succ.preceed` 扩展函数，而不会返回定义在类外的 `Nat.plus` 函数。
- **局限（"鸡肋"）**：这一系列方法作用就像"鸡肋"，更多时候我们希望获得的是类外的扩展方法，但遗憾的是目前没有直接方案可以获取某个类的所有扩展函数。
- **根据实例获取 KClass**：除了根据类名获取 KClass 对象以外，Kotlin 还支持根据具体的实例获得 KClass，语法类似，同样用 `::class` 表示，如 `Nat.Companion._1::class`。

**Q160: KCallable 是什么？如何获取一个类的成员？（8.2.3）**

- **KCallable 的概念**：Kotlin 把 Class 中的属性（Property）、函数（Function）甚至构造函数都看作 KCallable，因为它们是**可调用的**，它们都是 Class 的成员。
- **获取方式**：上文的 KClass 提供了 `members` 方法，它的返回值就是一个 `Collection<KCallable<*>>`。
- **API 概览（表8-3）**：浏览 KCallable 的 API 会发现，这些 API 和 Java 中的反射 API 很相似，都是对 KCallable（Class 成员）信息的获取。

**Q161: 如何通过反射执行一个 KCallable？扩展函数有什么注意事项？**

- **call 的本质**：`call` 这个函数就是通过反射执行这个 KCallable 对应的逻辑。套用上面 Nat 的例子：
  ```kotlin
  val _1 = Succ(Nat.Companion.Zero)
  val preceed = _1::class.members.find { it.name == "preceed" }
  println(preceed?.call(_1, _1) == Nat.Companion.Zero)
  ```
- **注意（扩展函数）**：调用 call 就执行了对应逻辑。但要注意，如果 KCallable 代表的是**扩展函数**，那么除了传入对象实例外，还需要额外传入**接收者实例**——上例中 `preceed` 是 `Succ<A>` 的扩展函数，所以 `call(_1, _1)` 传入了两个参数。

**Q162: 如何通过反射修改属性的值？（KMutableProperty 与 setter）**

- **与 Java 的区别**：Java 中可以通过 `Field.set(...)` 完成对字段的更改操作，但在 Kotlin 中并不是所有属性都是可变的，因此只能对那些**可变的属性**进行修改操作。
- **如何识别可变属性**：KMutableProperty 是 KProperty 的一个子类，使用 when 表达式可以轻松区分一个属性是 KMutableProperty 还是 KProperty。假设 `Person` 为 `data class Person(val name: String, val age: Int, var address: String)`，把 address 属性的值改为 "Hefei"：
  ```kotlin
  fun KMutablePropertyShow() {
      val p = Person("极跑科技", 8, "HangZhou")
      val props = p::class.memberProperties
      for (prop in props) {
          when (prop) {
              is KMutableProperty<*> -> prop.setter.call(p, "Hefei")
              else -> prop.call(p)
          }
      }
      println(p.address)
  }
  ```
- **运行结果**：输出 Hefei，已通过反射成功修改了 address 的值。再看 Kotlin 官方关于 KMutableProperty 的 API，发现它只比 KProperty 多了一个 `setter` 函数。

**Q163: Kotlin 把"参数"分为哪三类？分别如何获取？（8.2.4）**

- **分类**：Kotlin 把参数分为 3 个类别——**函数的参数（KParameter）**、**函数的返回值（KType）**及**类型参数（KTypeParameter）**。
- **KParameter**：使用 `KCallable.parameters` 即可获取一个 `List<KParameter>`，它代表的是函数（包括扩展函数）的参数。
- **KType**：每一个 KCallable 都可以使用 `returnType` 获取返回值类型，它的结果类型是一个 KType，代表着 Kotlin 中的类型。
- **KTypeParameter**：在 KClass 和 KCallable 中可以通过 `typeParameters` 获取 class 和 callable 的类型参数，返回的结果集是 `List<KTypeParameter>`，不存在类型参数时就返回一个空的 List。

**Q164: KParameter 有什么特点？"隐藏参数"是什么？（获取参数信息）**

- **API 概览（表8-4）**：KParameter 提供参数的类型（type）、名称等基本信息。
- **打印 Person 所有成员的参数类型**：
  ```kotlin
  fun KParameterShow() {
      val p = Person("极跑科技", 8, "HangZhou")
      for (c in Person::class.members) {
          print("${c.name} -> ")
          for (p in c.parameters) {
              print("${p.type}" + " -- ")
          }
          println()
      }
  }
  ```
- **运行结果**：
  ```
  address -> Person
  name -> Person
  detailAddress -> Person,kotlin.String
  isChild -> Person
  equals -> kotlin.Any,kotlin.Any?
  hashCode -> kotlin.Any
  toString -> kotlin.Any
  ```
- **规律分析（隐藏参数）**：
  - 对于**属性和无参数的函数**，它们都有一个隐藏的参数为类的实例（如 `address -> Person`、`name -> Person`）；
  - 对于**声明了参数的函数**，类的实例作为第 1 个参数，而声明的参数作为后续的参数（如 `detailAddress -> Person, kotlin.String`）；
  - 对于那些**从 Any 继承过来的参数**，Kotlin 默认它们的第 1 个参数为 Any（如 `equals -> kotlin.Any, kotlin.Any?`）。
- **与 Java 的对比**：Java 中尝试获取参数名有可能返回 `arg0`、`arg1`，而不是代码中指定的参数名称；若要获得参数名，可能需要指定 `-parameters` 编译参数。

**Q165: KType 和 classifier API 是什么？（获取返回值类型）**

- **returnType**：每一个 KCallable 都可以使用 `returnType` 获取返回值类型，结果是一个 KType，代表 Kotlin 中的类型（见表8-5）。
- **classifier 的含义**：通过 classifier API 获取的是该参数在**类层面**对应的类型，即去掉泛型参数后的类。给 Person 添加一个返回值为 `List<String>` 的 `friendsName` 方法用于演示：
  ```kotlin
  fun friendsName(): List<String> {
      return listOf("Yison", "Jilen")
  }
  ```
  演示代码：
  ```kotlin
  Person::class.members.forEach {
      println("${it.name} -> ${it.returnType.classifier}")
  }
  ```
- **运行结果分析**：`address -> class kotlin.String`、`age -> class kotlin.Int`、`friendsName -> class kotlin.collections.List`……即 `Int -> class kotlin.Int`，`List<String> -> class kotlin.collections.List`——classifier 获取的就是该类型在类层面（去掉泛型参数）对应的类。

**Q166: KTypeParameter 和 typeParameters 是什么？（获取类型参数）**

- **typeParameters**：对于函数和类来说，还有一个重要的参数——类型参数。在 KClass 和 KCallable 中可以通过 `typeParameters` 获取 class 和 callable 的类型参数，返回 `List<KTypeParameter>`，不存在类型参数时返回空的 List。
- **示例**：给 Person 添加一个带类型参数的方法：
  ```kotlin
  fun <A> get(a: A): A {
      return a
  }
  ```
  然后使用下面的代码获取 get 方法和 List<String> 的类型参数：
  ```kotlin
  fun KTypeParameterShow() {
      for (c in Person::class.members) {
          if (c.name.equals("get")) {
              println(c.typeParameters)
          }
      }
      val list = listOf<String>("How")
      println(list::class.typeParameters)
  }
  ```
- **运行结果**：get 方法的类型参数为 `[A]`，`List<String>` 的类型参数为 `[E]`。

**Q167: 如何创建自定义注解？（8.3 Kotlin 的注解）**

- **背景**：前文提及的注解 `kotlin.Metadata` 是实现 Kotlin 大部分独特特性反射的关键——Kotlin 将这些信息直接以注解形式存储在字节码文件中，以便运行时反射可以获取这些数据。
- **创建方式**：由于 Kotlin 兼容 Java，所有 Java 可以添加注解的地方 Kotlin 都可以；并且 Kotlin 简化了注解创建语法，创建注解就像创建 class 一样简单，只需额外在 class 前增加 `annotation` 关键字即可：
  ```kotlin
  annotation class FooAnnotation(val bar: String)
  ```
- **与 Java 的比较**：和创建其他 Kotlin 的类一样，只要在前面加上 `annotation`，这个类就变成了注解，与等价的 Java 代码相比确实简化了很多。
- **参数类型限制**：和 Java 一样，注解的参数只能是常量，并且仅支持下列类型：
  - 与 Java 对应的基本类型；
  - 字符串；
  - Class 对象（KClass 或者 Java 的 Class）；
  - 其他注解；
  - 上述类型的数组。注意基本类型数组需要指定为对应的 XXXArray，例如 `IntArray`，而不是 `Array<Int>`。

**Q168: Java 的元注解有哪些？Kotlin 与之对应的元注解如何？（Retention/Target）**

- **元注解的概念**：类似 @Target 这样**标注在注解上的注解**我们称之为元注解，它可以指定注解作用的位置等信息。
- **Java 的 5 个元注解**：
  - **@Documented**：文档（通常是 API 文档）中必须出现该注解；
  - **@Inherited**：如果超类标注了该类型，那么其子类型也将自动标注该注解而无须指定；
  - **@Repeatable**：这个注解在同一位置可以出现多次；
  - **@Retention**：表示注解用途（生命周期），有 3 种取值：
    - **SOURCE**：仅在源代码中存在，编译后 class 文件中不包含该注解信息；
    - **CLASS**：class 文件中存在该注解，但不能被反射读取；
    - **RUNTIME**：注解信息同样保存在 class 文件中，并且可以在运行时通过反射获取；
  - **@Target**：表明注解可应用于何处。
- **Kotlin 对应元注解**：Kotlin 也有相应类似的元注解，位于 `kotlin.annotation` 包下（见表8-6）。通过对比可以发现 Kotlin 和 Java 的注解整体上保持一致的，熟悉 Java 注解的读者很容易将这部分知识迁移到 Kotlin；同样 Kotlin 也有 @Target 元注解，和 Java 相似，它控制注解可以作用的位置。
- **注意**：Kotlin 目前**不支持 Inherited**，理论上实现继承没有很大难度，但当前版本还不支持。

**Q169: 注解可以出现在代码的哪些位置？（8.3.1 无处不在的注解）**

- **广泛应用**：和 Java 一样，Kotlin 的注解可以出现在代码的各个位置，例如方法、属性、局部变量、类等。此外注解还能作用于 **Lambda 表达式**、**整个源文件**（@file）。
- **AnnotationTarget 枚举**：Java 注解标注的位置可以通过元注解 @Target 指定，Kotlin 也一样，并且 Kotlin 在 Java 的基础上增加了一些可以标注的位置，这些位置是在 `AnnotationTarget` 枚举中定义的（见表8-7）。观察可知：Kotlin 支持几乎所有 Java 可以标注的位置，并且增加了 Kotlin 独有的位置。
- **简单使用示例（Cache/CacheKey）**：
  ```kotlin
  annotation class Cache(val namespace: String, val expires: Int)
  annotation class CacheKey(val keyName: String, val buckets: IntArray)

  @Cache(namespace = "hero", expires = 3600)
  data class Hero(
      @CacheKey(keyName = "heroName", buckets = intArrayOf(1, 2, 3))
      val name: String,
      val attack: Int,
      val defense: Int,
      val initHp: Int
  )
  ```

**Q170: 为什么要"精确控制注解的位置"？（8.3.2 use-site targets）**

- **问题的由来（多重含义）**：Kotlin 的代码常常会表达多重含义。例如上面的 `name` 除了生成一个不可变的字段之外，实际上还包含了 Getter，同时又是其构造函数的参数。这就带来一个问题：**@CacheKey 注解究竟是作用于何处？**
- **解决办法**：为了解决这个问题，Kotlin 引入了精确的注解控制语法（见表8-8），如 `@property:`、`@field:`、`@get:` 等 use-site target 写法：
  ```kotlin
  @Cache(namespace = "hero", expires = 3600)
  data class Hero(
      @property:CacheKey(keyName = "heroName", buckets = intArrayOf(1, 2, 3))
      val name: String,
      @field:CacheKey(keyName = "atk", buckets = intArrayOf(1, 2, 3))
      val attack: Int,
      @get:CacheKey(keyName = "def", buckets = intArrayOf(1, 2, 3))
      val defense: Int,
      val initHp: Int
  )
  ```
- **效果**：上述 CacheKey 注解分别作用在属性（property）、字段（field）和 Getter 上。

**Q171: 如何通过反射获取注解信息？（8.3.3）**

- **前提（Retention）**：代码标记上注解之后，注解本身也成了代码的一部分。通过反射去获取注解信息有一个前提——这个注解的 Retention 标注为 Runtime，或者没有显式指定（注解默认为 Runtime）。
- **获取方式**：前文已经了解如何通过反射获取类及其成员，获取了这些数据之后，很容易通过 API 获取其注解信息：
  ```kotlin
  annotation class Cache(val namespace: String, val expires: Int)
  annotation class CacheKey(val keyName: String, val buckets: IntArray)

  @Cache(namespace = "hero", expires = 3600)
  data class Hero(
      @CacheKey(keyName = "heroName", buckets = intArrayOf(1, 2, 3))
      val name: String,
      val attack: Int,
      val defense: Int,
      val initHp: Int
  )

  fun main(args: Array<String>) {
      val cacheAnnotation = Hero::class.annotations.find { it is Cache } as Cache?
      println("namespace ${cacheAnnotation?.namespace}")
      println("expires ${cacheAnnotation?.expires}")
  }
  ```
- **性能开销**：通过反射获取注解信息是在运行时发生的，和 Java 一样存在一定的性能开销，但这种开销大部分时候可以忽略不计。
- **位置影响获取**：前面提到的注解标注位置也会影响注解信息的获取——例如 `@file:CacheKey` 这样标注的注解，则无法通过调用 `KProperty.annotations` 获取到该注解信息。

**Q172: 什么是注解处理器（Annotation Processor）？（8.3.3）**

- **背景（JSR269）**：众所周知，JSR269 引入了注解处理器（annotation processors），允许我们在编译过程中挂钩子实现代码生成。得益于此，如 Dagger 之类的框架实现了编译时依赖注入——这是原本只能通过运行时反射支持的特性。
- **编译器的主要工作**：可以把编译器看成一个输入为源代码、输出为目标代码的程序。这个程序的第一步是将源文件解析为 AST（抽象语法树），实现这部分功能的程序通常被称为**解析器（parser）**。解析器解析完毕会将 AST 传给注解处理器。
- **一个澄清**：JSR269 脱胎于 javac，对于 eclipse ecj 之类的编译器通常有自己独立的 AST，它需要额外适配到 JSR269 定义的 AST。
- **只读 API 的限制**：这里本来应该是代码生成的最佳场合，理论上应该可以实现对 AST 进行修改；然而 **JSR269 是只读 API**，这就限制了不能修改任何传入注解处理器的 AST。要实现代码生成，只能非常蹩脚地将代码以字符串形式写入另一个文件，这不得不说是非常大的遗憾。
- **示例（MapperProcessor）**：根据 Mapper 注解获取对应的类信息，并生成一个 XXXMapper 类（里面实现自动转化为 Mapper 的方法）：
  ```kotlin
  import javax.annotation.processing.*
  import javax.lang.model.element.ElementKind
  import javax.lang.model.element.TypeElement
  import javax.tools.JavaFileObject
  import kotlin.reflect.full.memberProperties

  annotation class MapperAnnotation

  class MapperProcessor : AbstractProcessor() {

      private fun genMapperClass(pkg: String, clazzName: String, props: List<String>): String {
          // TODO()
      }

      override fun process(set: MutableSet<out TypeElement>?, env: RoundEnvironment?): Boolean {
          val el = env?.getElementsAnnotatedWith(MapperAnnotation::class.java)?.firstOrNull()
          if (el?.kind == ElementKind.CLASS) {
              val pkg = el.javaClass.`package`.name
              val cls = el.javaClass.simpleName
              val props = el.javaClass.kotlin.memberProperties.map { it.name }
              val mapperClass = genMapperClass(pkg, cls, props)
              val jfo = processingEnv.filer.createSourceFile(cls + "Mapper")
              val writer = jfo.openWriter()
              writer.write(mapperClass)
              writer.close()
          }
          return true
      }
  }
  ```
- **代码生成的蹩脚方式**：就像上面的 genMapperClass 函数，Annotation Processor 没有能力直接修改 AST，只能创建一个文件、将代码以字符串形式写入该文件（这里是 Java 代码）：
  ```kotlin
  private fun genMapperClass(pkg: String, clazzName: String, props: List<String>): String {
      return """
          package $pkg;
          import java.util.*;
          public class ${clazzName}Mapper {
              public Map<String, Object> toMap($clazzName a) {
                  Map<String, Object> m = new HashMap<String, Object>();
                  ${props.map { "m.put(\"${it}\", a.${it});" }}
              }
          }
      """
  }
  ```
- **注解处理器的使用方法**（和 Java 一样）：
  - **添加注解处理器信息**：需要在 classpath 里包含 `META-INF/services/javax.annotation.processing.Processor` 文件，并将注解处理器的包名和类名写入该文件；
  - **使用 kapt 插件**：如果是 gradle 工程，可以通过 `apply plugin: 'kotlin-kapt'` 添加注解处理器支持。kapt 也支持生成 Kotlin 代码——如上述例子中，可以将 genMapperClass 中的代码替换为 Kotlin 代码，并且将其存储在 `processingEnv.options["kapt.kotlin.generated"]` 目录中。
- **结论（同像性）**：annotation processor 虽然允许开发人员访问程序 AST，但没有提供行之有效的代码生成方案，目前仅有的代码生成方案也仅仅是将代码以字符串形式写入新文件，而无法做到直接将生成的 AST 作为程序。这也说明了 **Java 和 Kotlin 目前不具备同像性**。

---

## 第8篇 设计模式

**Q173: 什么是设计模式？Kotlin 如何重新审视 GoF 的 23 种设计模式？（本章主线）**

- **设计模式的定义**：设计模式不是一个类、包或类库，而是软件工程中解决特定问题的一种指南。经典设计模式指 GoF 四人在《设计模式：可复用面向对象软件的基础》中阐述的 23 种设计模式，它们主要采用类和对象的方式，至今仍广泛用于 C++、Java 等面向对象语言。
- **Kotlin 对设计模式的再思考**：Kotlin 是一门多范式语言，前面章节已展示其函数式语言特性带来更多编程可能性。比如 Kotlin 不需要所谓的"单例模式"，因为语言层面已经支持（`object` 关键字）。因此有人说"设计模式无非只是一些编程语言没有支持的特性罢了"。
- **本章的核心思路**：通过 Kotlin 的语言特性重新思考 Java 中常见的设计模式，从而进一步认识 Kotlin 语言特点，并了解如何在实际代码设计中运用它们。论述形式沿用 GoF 的分类方式（创建型、行为型、结构型），基于 Kotlin 崭新特性实现或替代了 Java 中部分典型设计模式。
- **已提前处理的模式**：访问者模式已在第 4 章详细介绍，Kotlin 中可利用模式匹配和 `when` 表达式改良，本章不再重复提及。

**Q174: 创建型模式解决什么问题？本节会探讨哪几种创建型模式？**

- **创建对象的复杂性**：程序设计中做得最多的事情之一就是创建对象。创建对象看似简单，但实际业务可能十分复杂：类可能存在子父类继承关系，或代表系统中各种不同的结构和功能。因此"创建怎样的对象、如何且何时创建它们、如何对类和对象进行配置"都是实际编码中必须考虑的问题。
- **本节探讨的模式**：工厂方法模式、抽象工厂模式以及构建者模式，分别对应书中 9.1.1、9.1.2、9.1.3 三节。

**Q175: 什么是简单工厂模式？Kotlin 中标准的工厂模式实现长什么样？（9.1.1）**

- **简单工厂的核心作用**：通过一个工厂类隐藏对象实例的创建逻辑，不暴露给客户端。典型使用场景是：拥有一个父类与多个子类时，通过该模式来创建子类对象。
- **"是什么"**：把创建实例的逻辑与客户端解耦，当对象创建逻辑发生变化（如构造参数数量变化）时，只需修改工厂 `produce` 方法内部代码，相比直接创建对象的方式更利于维护。
- **代码示例（电脑加工厂生产 PC 和服务器）**：
  ```kotlin
  interface Computer {
      val cpu: String
  }
  class PC(override val cpu: String = "Core") : Computer
  class Server(override val cpu: String = "Xeon") : Computer

  enum class ComputerType {
      PC, Server
  }

  class ComputerFactory {
      fun produce(type: ComputerType): Computer {
          return when (type) {
              ComputerType.PC -> PC()
              ComputerType.Server -> Server()
          }
      }
  }

  // 测试
  val comp = ComputerFactory().produce(ComputerType.PC)
  println(comp.cpu)   // 输出：Core
  ```
- **该实现的不足**：这是 Kotlin 模仿 Java 很标准的工厂模式设计，改善了可维护性，但创建对象时在不同地方都要先创建 `ComputerFactory` 类对象，表达不够简洁。这正是后续用 Kotlin 特性简化的出发点。

**Q176: 如何用 object 单例与运算符重载简化工厂类？（9.1.1 第 1 点）**

- **用单例代替工厂类**：Kotlin 天生用 `object` 支持单例模式，因此可以把 `ComputerFactory` 定义成单例而非普通类，调用时不再需要先实例化工厂对象：
  ```kotlin
  object ComputerFactory {   // 用 object 代替 class
      fun produce(type: ComputerType): Computer {
          return when (type) {
              ComputerType.PC -> PC()
              ComputerType.Server -> Server()
          }
      }
  }

  ComputerFactory.produce(ComputerType.PC)
  ```
- **用 operator invoke 进一步简化**：既然是通过传入 `Computer` 类型来创建不同对象，`produce` 这个名字就显得多余。Kotlin 支持运算符重载，可以用 `operator fun invoke` 来代替 `produce`，让工厂对象"像函数一样被调用"：
  ```kotlin
  object ComputerFactory {
      operator fun invoke(type: ComputerType): Computer {
          return when (type) {
              ComputerType.PC -> PC()
              ComputerType.Server -> Server()
          }
      }
  }

  ComputerFactory(ComputerType.PC)   // 与直接创建具体类实例几乎无差别
  ```
- **"为什么"**：`invoke` 运算符重载让"工厂"在语法上退居幕后，创建对象的表达与直接 `new` 一个类非常接近，大大提升了代码的可读性与简洁性。

**Q177: 伴生对象如何创建静态工厂方法，实现 `Computer(...)` 的直接调用？（9.1.1 第 2 点）**

- **问题动机（Effective Java 第 1 条）**：《Effective Java》第 1 条指导原则是"考虑用静态工厂方法代替构造器"。既然 Kotlin 用伴生对象代替了 Java 中的 `static`，那么在接口中定义伴生对象就能让客户端直接通过类型名创建实例。
- **实现方式**：在 `Computer` 接口中定义伴生对象，并把 `invoke` 放进伴生对象里。不指定伴生对象名字时，可直接通过 `Computer` 调用其伴生对象中的方法：
  ```kotlin
  interface Computer {
      val cpu: String
      companion object {
          operator fun invoke(type: ComputerType): Computer {
              return when (type) {
                  ComputerType.PC -> PC()
                  ComputerType.Server -> Server()
              }
          }
      }
  }

  Computer(ComputerType.PC)   // 输出：Core
  ```
- **给伴生对象命名**：如果觉得 `Factory` 这个名字更好，也可以用 `Factory` 命名伴生对象，调用方式变为 `Computer.Factory(ComputerType.PC)`：
  ```kotlin
  interface Computer {
      val cpu: String
      companion object Factory {
          operator fun invoke(type: ComputerType): Computer {
              return when (type) {
                  ComputerType.PC -> PC()
                  ComputerType.Server -> Server()
              }
          }
      }
  }

  Computer.Factory(ComputerType.PC)
  ```

**Q178: 如何扩展伴生对象方法，让第三方库的工厂也能被改造？（9.1.1 第 3 点）**

- **"是什么"**：扩展函数同样适用于伴生对象。假设 `Computer` 是工程引入的第三方类库中的接口，所有实现细节都被隐藏。若想给它增加"通过 CPU 型号判断电脑类型"的功能，可以扩展其伴生对象：
  ```kotlin
  fun Computer.Companion.fromCPU(cpu: String): ComputerType? = when (cpu) {
      "Core" -> ComputerType.PC
      "Xeon" -> ComputerType.Server
      else -> null
  }
  ```
- **带名字的伴生对象**：如果伴生对象命名为 `Factory`，扩展声明也相应写成 `fun Computer.Factory.fromCPU(...)`。
- **"为什么"**：这种方案比 Java 中的设计更强大——在不修改第三方代码的前提下，通过扩展函数为工厂增加能力，这正是 Kotlin 伴生对象与扩展函数组合带来的灵活性。

**Q179: 什么是抽象工厂模式？传统的抽象工厂实现有何不优雅之处？（9.1.2）**

- **问题升级（多个产品族）**：工厂模式能很好处理一个产品等级结构的问题（如生产服务器、PC 机）。当引入品牌商概念后，出现 Dell、Asus、Acer 多个不同电脑品牌，就有必要再增加工厂类。但我们不希望为每个型号都建立一个工厂，否则代码难以维护，于是需要抽象工厂模式。
- **抽象工厂模式定义**：为创建一组相关或相互依赖的对象提供一个接口，而且无须指定它们的具体类。这"一组相关或相互依赖的对象"称为"产品族"，上文 3 个品牌就是 3 个产品族。
- **传统实现**：
  ```kotlin
  interface Computer
  class Dell : Computer
  class Asus : Computer
  class Acer : Computer

  abstract class AbstractFactory {
      abstract fun produce(): Computer
      companion object {
          operator fun invoke(factory: AbstractFactory): AbstractFactory {
              return factory
          }
      }
  }

  class DellFactory : AbstractFactory() {
      override fun produce() = Dell()
  }
  class AsusFactory : AbstractFactory() {
      override fun produce() = Asus()
  }
  class AcerFactory : AbstractFactory() {
      override fun produce() = Acer()
  }

  fun main(args: Array<String>) {
      val dellFactory = AbstractFactory(DellFactory())
      val dell = dellFactory.produce()
      println(dell)
  }
  ```
- **不优雅之处**：每次创建具体工厂类时都要传入一个具体的工厂类对象作为构造参数，语法上不够优雅——这与"抽象工厂"应该隐藏具体类的初衷相悖。

**Q180: 内联函数 + reified 如何简化抽象工厂的创建语法？（9.1.2）**

- **"是什么"**：第 6 章学过内联函数有一个很大作用——可以具体化参数类型（`reified`）。利用该特性重写 `AbstractFactory` 伴生对象中的 `invoke` 方法，让它根据泛型实参的具体类型返回对应的工厂类对象：
  ```kotlin
  abstract class AbstractFactory {
      abstract fun produce(): Computer
      companion object {
          inline operator fun <reified T : Computer> invoke(): AbstractFactory = when (T::class) {
              Dell::class -> DellFactory()
              Asus::class -> AsusFactory()
              Acer::class -> AcerFactory()
              else -> throw IllegalArgumentException()
          }
      }
  }
  ```
- **代码分析**：1）将 `invoke` 定义为 `inline` 内联函数，从而可以引入 `reified` 关键字使用具体化参数类型语法；2）要具体化的参数类型为 `Computer`，在 `invoke` 中通过判断它的具体类型返回对应工厂类对象。
- **调用效果（像创建泛型类对象一样构建抽象工厂）**：
  ```kotlin
  fun main(args: Array<String>) {
      val dellFactory = AbstractFactory<Dell>()
      val dell = dellFactory.produce()
      println(dell)
  }
  ```
- **"为什么"**：`reified` 使得泛型参数在运行时不再被擦除，`T::class` 能拿到真实类型。这一特性在抽象工厂场景大放光彩——终于可以用类似创建泛型类对象的方式构建抽象工厂具体对象，去掉了手工传参，代码更加简洁优雅。

**Q181: 为什么说 Java 中的 Builder（构建者）模式有痛点？用 Kotlin 实现它是怎样的？（9.1.3）**

- **问题来源（长构造函数）**：在 Java 开发中，常会写出像蛇一样长的构造函数，比如 `new Robot(1, true, true, false, false, false, false, false, false)`——Boolean 参数表示 Robot 是否含有对应固件。刚写完还能看懂，一天后忘记大半，一星期后已不知是什么。面对这样的场景，Java 惯常做法是用 Builder 模式解决。
- **构建者模式定义**：将复杂对象的构建与它的表示分离，使得同样的构建过程可以创建不同的表示。工厂模式和构造函数都存在相同问题——不能很好地扩展到大量的可选参数。
- **重叠构造器（telescoping constructor）的缺陷**：先提供只有必要参数的构造函数，再提供更多含可选属性的构造函数，虽然调用时改进不少，但随着参数数量增加很快失去控制，代码难以维护。
- **Kotlin 实现 Builder 模式（代码清单 9-1）**：
  ```kotlin
  class Robot private constructor(
      val code: String,
      val battery: String?,
      val height: Int?,
      val weight: Int?) {
      class Builder(val code: String) {
          private var battery: String? = null
          private var height: Int? = null
          private var weight: Int? = null
          fun setBattery(battery: String?): Builder {
              this.battery = battery
              return this
          }
          fun setHeight(height: Int): Builder {
              this.height = height
              return this
          }
          fun setWeight(weight: Int): Builder {
              this.weight = weight
              return this
          }
          fun build(): Robot {
              return Robot(code, battery, height, weight)
          }
      }
  }

  val robot = Robot.Builder("007")
      .setBattery("R6")
      .setHeight(100)
      .setWeight(80)
      .build()
  ```
- **设计思路分析**：1）`Robot` 内部定义嵌套类 `Builder` 负责创建对象；2）`Robot` 构造函数用 `private` 修饰，确保使用者无法直接通过 `Robot` 声明实例；3）在 `Builder` 中定义 `set` 方法对可选属性进行设置；4）最终调用 `build()` 返回 `Robot` 对象。这种链式调用近似 2.3.8 节介绍的柯里化语法，且解决了多个可选参数的问题。
- **Builder 模式的不足**：1）业务参数很多时代码依然冗长；2）容易忘记在最后调用 `build()`；3）创建对象前必须先创建构造器，额外增加开销，在注重性能的场景存在问题。

**Q182: Kotlin 为什么可以放弃 Builder 模式？具名可选参数的优势是什么？（9.1.3 第 2 点）**

- **"为什么"（本质剖析）**：《Effective Java》介绍 Builder 时这样描述：本质上 builder 模式模拟了具名的可选参数，就像 Ada 和 Python 中的一样。幸运的是，Kotlin 也正是这样一门拥有具名可选参数的编程语言。因此用 Kotlin 设计程序时，可以在绝大多数情况下避免使用 Builder 模式。
- **具名可选参数的两大表现**：1）在具体化参数取值时，可以通过带上参数名来决定，而不是它在所有参数中的位置；2）由于参数可以设置默认值，允许只给出部分参数的取值，而不必是所有参数。
- **用原生语法重写 Robot 例子**：
  ```kotlin
  class Robot(
      val code: String,
      val battery: String? = null,
      val height: Int? = null,
      val weight: Int? = null
  )

  val robot1 = Robot(code = "007")
  val robot2 = Robot(code = "007", battery = "R6")
  val robot3 = Robot(code = "007", height = 100, weight = 80)
  ```
- **相比 Builder 模式的优点**：1）代码变得十分简单，无论类结构体还是声明对象的语法都更简洁；2）声明对象时每个参数名都可以是显式的，且无须按顺序书写，非常方便灵活；3）`Robot` 的每个属性都用 `val` 声明，相较 Builder 中 `var` 的方案更安全，在多线程并发安全场景更有优势。
- **更简单的替代**：如果类的功能足够简单，更好的思路是用 `data class` 直接声明数据类，数据类同样支持以上所有特性。

**Q183: Builder 的 build 方法参数约束如何用 require 实现？（9.1.3 第 3 点）**

- **需求场景**：Builder 模式的另一作用是可以在 `build` 方法中对参数添加约束。例如机器人重量必须根据电池型号决定，未传入电池型号前不能对 `weight` 赋值，否则抛出异常。Java 版的约束写在 `build` 方法里：
  ```kotlin
  fun build(): Robot {
      if (weight != null && battery == null) {
          throw IllegalArgumentException("Battery should be determined when setting weight.")
      } else {
          return Robot(code, battery, height, weight)
      }
  }
  ```
- **Kotlin 的 require 方案**：具名可选参数方案同样可以在 `init` 方法中增加校验代码，但 Kotlin 在类或函数中还可以使用 `require` 关键字进行函数参数限制。它本质上是一个内联方法，有点像 Java 中的 `assert`：
  ```kotlin
  class Robot(
      val code: String,
      val battery: String? = null,
      val height: Int? = null,
      val weight: Int? = null
  ) {
      init {
          require(weight == null || battery != null) {
              "Battery should be determined when setting weight."
          }
      }
  }

  val robot = Robot(code = "007", weight = 100)
  // 抛出 java.lang.IllegalArgumentException: Battery should be determined when setting weight.
  ```
- **总结**：`require` 让参数约束代码在语义上更加友好。总体而言，在 Kotlin 中应尽量避免使用 Builder 模式，因为 Kotlin 支持具名的可选参数，可以用更简洁且利于维护的代码构造具有多个可选参数的类。

**Q184: 行为型模式解决什么问题？本节涉及哪几种？（9.2 引言）**

- **职责划分与对象交互**：用创建型模式创建出类对象之后，就需要在不同对象之间划分职责、产生交互。用来识别对象之间常用交流模式的模式就是行为型模式。
- **本节的模式清单**：观察者模式、策略模式、模板方法模式、迭代器模式、责任链模式及状态模式，全部用 Kotlin 语法重新思考。

**Q185: 什么是观察者模式？用 Java 标准库 Observable/Observer 如何实现股价动态更新？（9.2.1）**

- **定义与使用场景**：观察者模式定义了"一对多"的依赖关系，让一个或多个观察者对象监听一个主题对象。当被观察者状态改变时，通知相应的观察者，使这些观察者对象自动更新。它是接触最多的设计模式之一——Android 开发的 MVC 架构、RxJava 类库设计都基于它，管理视图变化逻辑响应也离不开它。
- **观察者模式无非做两件事**：1）订阅者（observer）添加或删除对发布者（publisher）的状态监听；2）发布者状态改变时，将事件通知给监听它的所有观察者，然后观察者执行响应逻辑。
- **Java 标准库实现（动态更新股价）**：
  ```kotlin
  import java.util.*

  class StockUpdate : Observable() {
      val observers = mutableSetOf<Observer>()
      fun setStockChanged(price: Int) {
          this.observers.forEach { it.update(this, price) }
      }
  }

  class StockDisplay : Observer {
      override fun update(o: Observable, price: Any) {
          if (o is StockUpdate) {
              println("The latest stock price is ${price}.")
          }
      }
  }

  fun main(args: Array<String>) {
      val su = StockUpdate()
      val sd = StockDisplay()
      su.observers.add(sd)
      su.setStockChanged(100)
  }
  // 运行结果：The latest stock price is 100.
  ```
- **原理分析**：`StockUpdate` 维护监听其变化的观察者集合 `observers`，通过 `add`/`remove` 方法管理。执行 `setStockChanged` 后表示股价已改变，将更新的股价传给观察者执行其 `update` 方法；`StockDisplay` 发现订阅者类型为 `StockUpdate` 时打印最新股价。
- **该方案的局限**：实现 `java.util.Observer` 接口的类只能覆写 `update` 方法编写响应逻辑，若存在多种不同的逻辑响应，必须在同一方法中区分实现，会让订阅者代码臃肿。相当于发布者只提供一个 API 接口，API 调用者承担了更多职能。

**Q186: 如何用委托属性 Delegates.observable 改良观察者模式？（9.2.1 第 1 点）**

- **"是什么"**：Kotlin 标准库额外引入了可被观察的委托属性 `Delegates.observable`，可以利用它实现同样的场景，且支持更细化的上涨/下跌响应：
  ```kotlin
  import kotlin.properties.Delegates

  interface StockUpdateListener {
      fun onRise(price: Int)
      fun onFall(price: Int)
  }

  class StockDisplay : StockUpdateListener {
      override fun onRise(price: Int) {
          println("The latest stock price has risen to ${price}.")
      }
      override fun onFall(price: Int) {
          println("The latest stock price has fell to ${price}.")
      }
  }

  class StockUpdate {
      var listeners = mutableSetOf<StockUpdateListener>()
      var price: Int by Delegates.observable(0) { _, old, new ->
          listeners.forEach {
              if (new > old) it.onRise(price) else it.onFall(price)
          }
      }
  }

  fun main(args: Array<String>) {
      val su = StockUpdate()
      val sd = StockDisplay()
      su.listeners.add(sd)
      su.price = 100
      su.price = 98
  }
  // 运行结果：
  // The latest stock price has risen to 100.
  // The latest stock price has fell to 98.
  ```
- **语法分析**：`Delegates.observable()` 提供 3 个参数，依次代表委托属性的元数据 `KProperty` 对象、旧值以及新值。当 `price` 被赋值时自动触发回调，新旧值比较决定调用 `onRise` 还是 `onFall`。
- **"为什么"更灵活**：通过额外定义 `StockUpdateListener` 接口，把上涨和下跌的不同响应逻辑封装成接口方法，在 `StockDisplay` 中分别实现 `onRise` 和 `onFall`，实现了解耦——发布者事件推送从"单一 API"升级为"按事件类型的多 API"，订阅者代码不再臃肿。

**Q187: Delegates.vetoable 是什么？它如何对属性赋值进行"否决"？（9.2.1 第 2 点）**

- **"是什么"**：有些时候并不希望被监控的值被随心所欲地修改，而是希望对某些改值情况进行否决。Kotlin 标准库除 `observable` 外还提供了 `vetoable`。veto 意为"否决"，`vetoable` 在被赋新值生效之前提前截获，然后判断是否接受它。
- **代码示例**：
  ```kotlin
  import kotlin.properties.Delegates

  var value: Int by Delegates.vetoable(0) { prop, old, new ->
      new > 0
  }

  >>> value = 1
  >>> println(value)   // 1
  >>> value = -1
  >>> println(value)   // 1（拒绝赋负值，仍保留旧值）
  ```
- **"为什么"**：`value` 初始化为 0，委托只接收正整数赋值。当试图把 `value` 改成 -1 时，`vetoable` 的 lambda 返回 false，赋值被否决，打印结果仍为旧值 1。它使"赋值前的条件校验"成为语言层面的声明式语法，适合对数据合法性有强要求的业务场景。

**Q188: 策略模式要解决什么问题？传统的策略模式实现是怎样的？（9.2.2 第 1 点）**

- **动机（开闭原则）**：假设有表示游泳运动员的 `Swimmer` 类，最初只有一个 `swim` 方法。后来 shaw 掌握了蛙泳、仰泳、自由泳多种姿势，如果直接在类里加 3 个方法，则：1）并非所有游泳运动员都掌握 3 种姿势，让每个 `Swimmer` 对象都可调用所有方法显得危险；2）后续难免有新的行为方法加入，通过修改 `Swimmer` 类违背了开放封闭原则。
- **策略模式定义**：定义算法族，分别封装起来，让它们之间可以相互替换，此模式让算法的变化独立于使用算法的客户。本质上是将不同行为策略独立封装，与类在逻辑上解耦，然后根据不同上下文切换选择不同的策略，再用类对象进行调用。
- **传统实现**：
  ```kotlin
  interface SwimStrategy {
      fun swim()
  }
  class Breaststroke : SwimStrategy {
      override fun swim() { println("I am breaststroking...") }
  }
  class Backstroke : SwimStrategy {
      override fun swim() { println("I am backstroke...") }
  }
  class Freestyle : SwimStrategy {
      override fun swim() { println("I am freestyling...") }
  }

  class Swimmer(val strategy: SwimStrategy) {
      fun swim() { strategy.swim() }
  }

  fun main(args: Array<String>) {
      val weekendShaw = Swimmer(Freestyle())
      weekendShaw.swim()      // I am freestyling...
      val weekdaysShaw = Swimmer(Breaststroke())
      weekdaysShaw.swim()     // I am breaststroking...
  }
  ```
- **该方案的不足**：实现了解耦和复用的目的，且很好实现了不同场景切换不同策略，但代码量比之前多很多。因为策略类的目的非常明确，仅仅是对行为算法的一种抽象，用高阶函数来替代是更好的思路。

**Q189: 如何用高阶函数简化策略模式？（9.2.2 第 2 点）**

- **核心思想**：把策略封装成一个函数，然后作为参数传递给 `Swimmer` 类，代码量大幅减少且结构更易阅读：
  ```kotlin
  fun breaststroke() { println("I am breaststroking...") }
  fun backstroke() { println("I am backstroking...") }
  fun freestyle() { println("I am freestyling...") }

  class Swimmer(val swimming: () -> Unit) {
      fun swim() {
          swimming()
      }
  }

  fun main(args: Array<String>) {
      val weekendShaw = Swimmer(::freestyle)
      weekendShaw.swim()
      val weekdaysShaw = Swimmer(::breaststroke)
      weekdaysShaw.swim()
  }
  ```
- **"为什么"**：策略类只是针对行为算法的抽象，Kotlin 的函数类型 `() -> Unit` 天然承担了"算法接口"的角色。初始化 `Swimmer` 对象时可用函数引用语法（`::freestyle`）传递构造参数；也可以把函数用 `val` 声明成 Lambda 表达式，传递参数时更加简洁直观。

**Q190: 什么是模板方法模式？它与策略模式有何异同？（9.2.2 第 3 点）**

- **模板方法模式定义**：定义一个算法中的操作框架，而将一些步骤延迟到子类中，使得子类可以不改变算法的结构即可重定义该算法的某些特定步骤。
- **与策略模式的关系**：两者解决相似的问题，都可以分离通用的算法和具体的上下文。区别在于：策略模式将算法委托（composition），模板方法模式更多基于继承实现，其行为算法具有更明晰的大纲结构——完全相同的步骤在抽象类中实现，可个性化的步骤在子类中定义。
- **经典例子（市民事务中心办事）**：1）排队取号等待；2）按需求办理个性化业务（获取社保清单、申请市民卡、办理房产证）；3）对服务人员态度进行评价。步骤 1 和 3 相同，步骤 2 可个性化。
- **基于继承的模板方法实现**：
  ```kotlin
  abstract class CivicCenterTask {
      fun execute() {
          this.lineUp()
          this.askForHelp()
          this.evaluate()
      }
      private fun lineUp() {
          println("line up to take a number")
      }
      private fun evaluate() {
          println("evaluate service attitude")
      }
      abstract fun askForHelp()
  }

  class PullSocialSecurity : CivicCenterTask {
      override fun askForHelp() {
          println("ask for pulling the social security")
      }
  }
  class ApplyForCitizenCard : CivicCenterTask {
      override fun askForHelp() {
          println("apply for a citizen card")
      }
  }

  val pss = PullSocialSecurity()
  pss.execute()
  // line up to take a number
  // ask for pulling the social security
  // evaluate service attitude
  ```

**Q191: 如何用高阶函数代替继承来实现模板方法模式？（9.2.2 第 3 点）**

- **"是什么"**：继承方案的复用性虽高，但还是要为不同业务场景定义具体子类。利用高阶函数，只需一个 `CivicCenterTask` 类即可代替继承实现相同效果——把可个性化的步骤作为函数参数传入：
  ```kotlin
  class CivicCenterTask {
      fun execute(askForHelp: () -> Unit) {
          this.lineUp()
          askForHelp()
          this.evaluate()
      }
      private fun lineUp() {
          println("line up to take a number")
      }
      private fun evaluate() {
          println("evaluate service attitude")
      }
  }

  fun pullSocialSecurity() {
      println("ask for pulling the social security")
  }
  fun applyForCitizenCard() {
      println("apply for a citizen card")
  }

  val task1 = CivicCenterTask()
  task1.execute(::pullSocialSecurity)
  // line up to take a number
  // ask for pulling the social security
  // evaluate service attitude
  val task2 = CivicCenterTask()
  task2.execute(::applyForCitizenCard)
  // line up to take a number
  // apply for a citizen card
  // evaluate service attitude
  ```
- **"为什么"**：算法大纲（`lineUp`→`askForHelp`→`evaluate`）被固定为 `execute` 函数内的调用序列，而可变步骤以 `() -> Unit` 函数参数注入。相比继承，不再需要为每个业务场景创建子类，代码量显著减少，组合优于继承。

**Q192: 什么是迭代器模式？方案 1：实现 Iterator 接口是怎样的？（9.2.3）**

- **定义与核心作用**：迭代器是 Java 中很熟悉的东西，`List`、`Set` 等数据结构内置迭代器，可用它提供的方法顺序访问聚合对象中的各个元素。迭代器模式的核心作用是把遍历和实现分离开来，在遍历的同时不需要暴露对象的内部表示。
- **实现思路**：通常不需要自己实现一个迭代器，Java 标准库提供 `java.util.Iterator` 接口，容器类实现该接口，再实现需要的迭代器方法。
- **方案 1：实现 Iterator 接口（书架 Bookcase 的例子）**：
  ```kotlin
  data class Book(val name: String)

  class Bookcase(val books: List<Book>) : Iterator<Book> {
      private val iterator: Iterator<Book>
      init {
          this.iterator = books.iterator()
      }
      override fun hasNext() = this.iterator.hasNext()
      override fun next() = this.iterator.next()
  }

  fun main(args: Array<String>) {
      val bookcase = Bookcase(
          listOf(Book("Dive into Kotlin"), Book("Thinking in Java"))
      )
      while (bookcase.hasNext()) {
          println("The book name is ${bookcase.next().name}")
      }
  }
  // The book name is Dive into Kotlin
  // The book name is Thinking in Java
  ```
- **更简洁的遍历方式**：由于 `Bookcase` 对象拥有与 `List<Book>` 实例相同的迭代器，可以直接用 `for` 循环：
  ```kotlin
  for (book in bookcase) {
      println("The book name is ${book.name}")
  }
  ```

**Q193: 方案 2/3：如何用 operator 重载 iterator 方法与扩展函数简化迭代器？（9.2.3）**

- **方案 2：重载 iterator 方法**：Kotlin 利用 `operator` 关键字内置了很多运算符重载功能。重载 `Bookcase` 类的 `iterator` 方法，实现语法更精简的版本——一行代码完成全部效果：
  ```kotlin
  data class Book(val name: String)

  class Bookcase(val books: List<Book>) {
      operator fun iterator(): Iterator<Book> = this.books.iterator()
  }
  ```
- **方案 3：通过扩展函数**：Kotlin 还支持扩展函数，可以给所有对象都内置一个迭代器。假设 `Bookcase` 是引入的类、不能修改其源码，就用扩展语法给 `Bookcase` 对象增加迭代功能：
  ```kotlin
  data class Book(val name: String)
  class Bookcase(val books: List<Book>) {}

  operator fun Bookcase.iterator(): Iterator<Book> = books.iterator()
  ```
- **用 object 表达式获得更多控制权**：如果希望对迭代器逻辑有更多控制权，可结合 object 表达式：
  ```kotlin
  operator fun Bookcase.iterator(): Iterator<Book> = object : Iterator<Book> {
      val iterator = books.iterator()
      override fun hasNext() = iterator.hasNext()
      override fun next() = iterator.next()
  }
  ```
- **"为什么"**：迭代器模式本身不是一种很常用的设计模式，但通过它可以看到 Kotlin 扩展函数的应用以及运算符重载功能的强大之处——不修改原类源码，甚至不用实现接口，就能让任意类拥有 `for` 循环遍历能力。

**Q194: 什么是责任链模式？传统的面向对象实现是怎样的？（9.2.4 引言）**

- **定义与典型场景**：责任链模式让多个对象都有机会处理某种类型的请求，避免请求发送者与接收者之间的耦合关系，把这些对象连成一条链，并沿着这条链传递请求，直到有一个对象处理它为止。典型例子是 Servlet 中的 `Filter` 和 `FilterChain` 接口——收到 Web 请求先进行各种 filter 逻辑操作，filter 都处理完才执行 servlet，不同 filter 代表不同职责，最终形成责任链。
- **具体业务例子（学生会经费审批）**：金额 100 元以内由各分部长审批；超过 100 元需要会长同意；达到 500 元以上需要学院辅导员陈老师批准；经费上限 1000 元，超出则默认打回申请。
- **面向对象实现**：
  ```kotlin
  data class ApplyEvent(val money: Int, val title: String)

  interface ApplyHandler {
      val successor: ApplyHandler?
      fun handleEvent(event: ApplyEvent)
  }

  class GroupLeader(override val successor: ApplyHandler?) : ApplyHandler {
      override fun handleEvent(event: ApplyEvent) {
          when {
              event.money <= 100 -> println("Group Leader handled application: ${event.title}")
              else -> when (successor) {
                  is ApplyHandler -> successor.handleEvent(event)
                  else -> println("Group Leader: This application cannot be handled.")
              }
          }
      }
  }

  class President(override val successor: ApplyHandler?) : ApplyHandler {
      override fun handleEvent(event: ApplyEvent) {
          when {
              event.money <= 500 -> println("President handled application: ${event.title}")
              else -> when (successor) {
                  is ApplyHandler -> successor.handleEvent(event)
                  else -> println("President: This application cannot be handled.")
              }
          }
      }
  }

  class College(override val successor: ApplyHandler?) : ApplyHandler {
      override fun handleEvent(event: ApplyEvent) {
          when {
              event.money > 1000 -> println("College: This application is refused.")
              else -> println("College handled application: ${event.title}.")
          }
      }
  }

  fun main(args: Array<String>) {
      val college = College(null)
      val president = President(college)
      val groupLeader = GroupLeader(president)
      groupLeader.handleEvent(ApplyEvent(10, "buy a pen"))
      groupLeader.handleEvent(ApplyEvent(200, "team building"))
      groupLeader.handleEvent(ApplyEvent(600, "hold a debate match"))
      groupLeader.handleEvent(ApplyEvent(1200, "annual meeting of the college"))
  }
  // 运行结果：
  // Group Leader handled application: buy a pen.
  // President handled application: team building.
  // College handled application: hold a debate match.
  // College: This application is refused.
  ```
- **机理剖析**：接口包含可空的后继者对象 `successor` 及处理申请的方法 `handleEvent`。事件传给 `GroupLeader` 后，它按经费金额判断是否转交给 `successor`（`President`）处理，以此类推形成责任链。整个链条每个处理环节都有对输入参数的校验标准——在编程语言中有一个专门术语描述这种情况，就是"偏函数"。

**Q195: 什么是偏函数？如何用 Kotlin 实现 PartialFunction 类型？（9.2.4 第 1 点）**

- **偏函数定义**：偏函数是数学中的概念，指定义域 X 中可能存在某些值在值域 Y 中没有对应的值。普通函数 `(Int) -> Unit` 可以接收任何 Int 值；而偏函数指定类型的参数并不接收任意该类型的值，例如 `mustGreaterThan5` 只接受大于 5 的值，传 1 就抛异常。
  ```kotlin
  fun mustGreaterThan5(x: Int): Boolean {
      if (x > 5) {
          return true
      } else throw Exception("x must be greater than 5")
  }
  >>> mustGreaterThan5(6)   // true
  >>> mustGreaterThan5(1)   // java.lang.Exception: x must be greater than 5
  ```
- **"为什么"提到偏函数**：在 Scala 等函数式编程语言中有 `PartialFunction` 类型，可用它简化责任链模式的实现。Kotlin 标准库没有原生支持 `PartialFunction`，但语言特性足够灵活强大，一些开源库（如 funKTionale）已实现该功能。
- **自定义 PartialFunction 类型**：声明类对象时接收两个构造参数——`definetAt` 为校验函数，`f` 为处理函数。当对象执行 `invoke` 时，`definetAt` 对输入参数 p1 做有效性校验：通过则执行 `f` 并将 p1 传给它，否则抛出异常。
  ```kotlin
  class PartialFunction<in P1, out R>(
      private val definetAt: (P1) -> Boolean,
      private val f: (P1) -> R) {
      operator fun invoke(p1: P1): R {
          if (definetAt(p1)) {
              return f(p1)
          } else {
              throw IllegalArgumentException("Value: ($p1) isn't supported by this function.")
          }
      }
      fun isDefinedAt(p1: P1) = definetAt(p1)
  }
  ```
- **orElse 扩展函数（构建链式传递）**：`isDefinedAt` 只是拷贝 `definetAt` 的内部方法，供 `orElse` 中调用。`orElse` 用 `infix` 关键字声明为中缀函数，让链式调用语法更直观：
  ```kotlin
  infix fun <P1, R> PartialFunction<P1, R>.orElse(that: PartialFunction<P1, R>): PartialFunction<P1, R> {
      return PartialFunction({ this.isDefinedAt(it) || that.isDefinedAt(it) }) {
          when {
              this.isDefinedAt(it) -> this(it)
              else -> that(it)
          }
      }
  }
  ```
  在 `orElse` 中传入另一个 `PartialFunction` 对象 `that`，它就是责任链中的后继者；当前环节 `isDefinedAt` 为 false 时调用 `that` 继续处理。

**Q196: 如何用 PartialFunction + orElse 中缀表达式构建责任链？（9.2.4 第 2 点）**

- **用自运行 Lambda 构建各环节对象**：借助自运行 Lambda 语法构建 `PartialFunction` 对象，`definetAt` 校验申请金额是否在审批范围，`handler` 处理通过校验后的审批操作：
  ```kotlin
  data class ApplyEvent(val money: Int, val title: String)

  val groupLeader = {
      val definetAt: (ApplyEvent) -> Boolean = { it.money <= 200 }
      val handler: (ApplyEvent) -> Unit = {
          println("Group Leader handled application: ${it.title}")
      }
      PartialFunction(definetAt, handler)
  }()

  val president = {
      val definetAt: (ApplyEvent) -> Boolean = { it.money <= 500 }
      val handler: (ApplyEvent) -> Unit = {
          println("President handled application: ${it.title}")
      }
      PartialFunction(definetAt, handler)
  }()

  val college = {
      val definetAt: (ApplyEvent) -> Boolean = { true }
      val handler: (ApplyEvent) -> Unit = {
          when {
              it.money > 1000 -> println("College: This application is refused.")
              else -> println("College handled application: ${it.title}.")
          }
      }
      PartialFunction(definetAt, handler)
  }()
  ```
- **用 orElse 构建责任链**：
  ```kotlin
  val applyChain = groupLeader orElse president orElse college

  >>> applyChain(ApplyEvent(600, "hold a debate match"))
  // College handled application: hold a debate match.
  ```
- **"为什么"**：借助 `PartialFunction` 类的封装，不仅大幅减少了程序代码量，而且构建责任链时可以用 `orElse` 获得更好的语法表达——中缀表达式把"责任链"从"层层 if-else + successor 指针"变成了直观的函数组合，各环节的校验逻辑与处理逻辑内聚在偏函数内部。

**Q197: 什么是状态模式？为什么说它与策略模式相似又不同？（9.2.5 引言）**

- **状态模式定义**：允许一个对象在其内部状态改变时改变它的行为，对象看起来似乎修改了它的类。具体表现为：1）状态决定行为，对象的行为由它内部的状态决定；2）对象的状态在运行期被改变时，它的行为也因此而改变。从表面看，同一个对象在不同运行时刻行为不一样，就像类被修改了一样。
- **与策略模式的区别**：两者都实现某种算法、业务逻辑的切换，但机制不同——策略模式通过在客户端切换不同的策略实现来改变算法；状态模式中，对象通过修改内部的状态来切换不同的行为方法。
- **"为什么"想到 ADT**：书中第 4 章介绍了 ADT（代数数据类型）以及如何用它与模式匹配抽象业务，ADT 是函数式语言中强大的语言特性。面对饮水机这种有 3 种固定状态（未启动、制冷模式、制热模式）的场景，会很自然地联想到用密封类来封装代表不同状态的 ADT。

**Q198: 如何用密封类（sealed class）ADT 实现饮水机的状态模式？（9.2.5）**

- **用密封类封装状态的 ADT**：`WaterMachineState` 是一个密封类，构造参数为 `WaterMachine` 类对象。类外部分别定义 `Off`、`Heating`、`Cooling` 代表饮水机 3 种工作状态，它们都继承 `WaterMachineState` 的 `machine` 成员属性及 3 个状态切换方法。每个切换状态的方法中，通过改变 `machine` 对象的 `state` 实现状态切换：
  ```kotlin
  sealed class WaterMachineState(open val machine: WaterMachine) {
      fun turnHeating() {
          if (this !is Heating) {
              println("turn heating")
              machine.state = machine.heating
          } else {
              println("The state is already heating mode.")
          }
      }
      fun turnCooling() {
          if (this !is Cooling) {
              println("turn cooling")
              machine.state = machine.cooling
          } else {
              println("The state is already cooling mode.")
          }
      }
      fun turnOff() {
          if (this !is Off) {
              println("turn off")
              machine.state = machine.off
          } else {
              println("The state is already off.")
          }
      }
  }

  class Off(override val machine: WaterMachine) : WaterMachineState(machine)
  class Heating(override val machine: WaterMachine) : WaterMachineState(machine)
  class Cooling(override val machine: WaterMachine) : WaterMachineState(machine)
  ```
- **WaterMachine 类的设计**：内部包含：1）可变的 `WaterMachineState` 对象 `state`，表示当前工作状态；2）表示 3 种不同状态的成员属性 `off`、`heating`、`cooling`（即 `WaterMachineState` 的 3 个子类对象），通过传入 `this` 构造，从而在状态类内部能改变 `WaterMachine` 的 `state` 引用；初始化时 `state` 默认为 `off`；3）3 个直接调用的操作方法，分别执行对应 `state` 对象的 3 种操作，供客户端调用：
  ```kotlin
  class WaterMachine {
      var state: WaterMachineState
      val off = Off(this)
      val heating = Heating(this)
      val cooling = Cooling(this)
      init {
          this.state = off
      }
      fun turnHeating() { this.state.turnHeating() }
      fun turnCooling() { this.state.turnCooling() }
      fun turnOff() { this.state.turnOff() }
  }
  ```

**Q199: 如何用 when 表达式 + 密封类实现状态驱动的业务函数？（9.2.5 应用）**

- **业务场景**：夏天早上同事把饮水机调为制冷模式；Shaw 吃泡面时切为制热，吃完后下一位同事再切回制冷；下班时 Kim 关闭电源。不同角色在不同时刻对饮水机执行不同操作。
- **waterMachineOps 函数**：通过 `when(moment)` 匹配时间段，内部再用 `when(machine.state)` 判断当前状态是否已是目标状态，避免重复切换：
  ```kotlin
  enum class Moment {
      EARLY_MORNING,   // 早上上班
      DRINKING_WATER,  // 日常饮水
      INSTANCE_NOODLES,// Shaw 吃泡面
      AFTER_WORK       // 下班
  }

  fun waterMachineOps(machine: WaterMachine, moment: Moment) {
      when (moment) {
          Moment.EARLY_MORNING,
          Moment.DRINKING_WATER -> when (machine.state) {
              !is Cooling -> machine.turnCooling()
          }
          Moment.INSTANCE_NOODLES -> when (machine.state) {
              !is Heating -> machine.turnHeating()
          }
          Moment.AFTER_WORK -> when (machine.state) {
              !is Off -> machine.turnOff()
          }
          else -> Unit
      }
  }

  fun main(args: Array<String>) {
      val machine = WaterMachine()
      waterMachineOps(machine, Moment.DRINKING_WATER)
      waterMachineOps(machine, Moment.INSTANCE_NOODLES)
      waterMachineOps(machine, Moment.DRINKING_WATER)
      waterMachineOps(machine, Moment.AFTER_WORK)
  }
  // 执行结果：
  // turn cooling
  // turn heating
  // turn cooling
  // turn off
  ```
- **密封类的类型安全优势**：用 `when` 表达式处理枚举类时默认情况必须用 `else` 处理；但由于密封类在类型安全上的额外设计，处理 `machine.state` 时不需要考虑这一细节（`!is` 智能转换已穷尽分支），语言表达上简洁得多。这正体现了 ADT 结合模式匹配实现状态模式的优势。

**Q200: 结构型模式解决什么问题？为什么选择装饰者模式作为重点？（9.3 引言）**

- **关注点**：对象被创建之后，对象的组成及对象之间的依赖关系成为关注焦点，这与程序的可维护性息息相关。
- **本节的差异化思路**：重点介绍装饰者模式。与 Java 传统的设计方法不同，Kotlin 依靠类委托和扩展的语言特性，给开发者提供了更多的选择——这是"用语言特性改写设计模式"的又一典型体现。

**Q201: 什么是装饰者模式？它有什么优点和痛点？（9.3.1）**

- **背景**：在 Java 中给一个类扩展行为，通常有两种选择：1）设计一个继承它的子类；2）使用装饰者模式对该类进行装饰，然后对功能进行扩展。由于并非所有场合都适合继承（第 3 章讨论过"里氏替换原则"），很多时候装饰者模式是更好的思路。
- **装饰者模式定义**：在不必改变原类文件和使用继承的情况下，动态地扩展一个对象的功能。该模式通过创建一个包装对象来包裹真实的对象。
- **装饰者模式做的几件事**：1）创建一个装饰类，包含一个需要被装饰类的实例；2）装饰类重写所有被装饰类的方法；3）在装饰类中对需要增强的功能进行扩展。
- **优点与痛点**：优势在于符合"组合优于继承"的设计原则，规避了某些场景下继承所带来的问题；但它有时也会显得比较啰嗦——因为要重写所有装饰对象的方法，可能存在大量样板代码。

**Q202: 如何用类委托（by 关键字）减少装饰者模式的样板代码？（9.3.1）**

- **"是什么"**：利用 Kotlin 的类委托特性，用 `by` 关键字把装饰类的所有方法委托给被装饰的类对象，然后只需覆写需要装饰的方法即可。以 MacBook Pro 增加内存为例：
  ```kotlin
  interface MacBook {
      fun getCost(): Int
      fun getDesc(): String
      fun getProdDate(): String
  }

  class MacBookPro : MacBook {
      override fun getCost() = 10000
      override fun getDesc() = "Macbook Pro"
      override fun getProdDate() = "Late 2011"
  }

  // 装饰类
  class ProcessorUpgradeMacbookPro(val macbook: MacBook) : MacBook by macbook {
      override fun getCost() = macbook.getCost() + 219
      override fun getDesc() = macbook.getDesc() + ", +1G Memory"
  }

  fun main(args: Array<String>) {
      val macBookPro = MacBookPro()
      val processorUpgradeMacbookPro = ProcessorUpgradeMacbookPro(macBookPro)
      println(processorUpgradeMacbookPro.getCost())   // 10219
      println(processorUpgradeMacbookPro.getDesc())   // Macbook Pro, +1G Memory
  }
  ```
- **原理分析**：`MacBook` 接口的 3 个方法分别表示预算、机型信息、生产年份。`ProcessorUpgradeMacbookPro` 通过 `MacBook by macbook` 把接口所有方法都委托给构造参数对象 `macbook`，因此只需覆写需要变更的 `getCost` 和 `getDesc`；生产年份不会改变，无需重写，装饰类会自动调用被装饰对象的 `getProdDate` 方法。
- **"为什么"**：Kotlin 通过类委托减少了装饰者模式中的样板代码——否则在不继承 `Macbook` 类的前提下，得创建一个装饰类和被装饰类的公共父抽象类。`by` 关键字让"委托所有方法"成为一行声明。

**Q203: 如何用扩展函数代替装饰类？（9.3.2）**

- **"是什么"**：第 7 章介绍过"扩展"是 Kotlin 中强大的语言特性，其灵活应用就是实现特设多态。特设多态可以针对不同的版本实现完全不同的行为，这与装饰者模式不谋而合——后者也是给特定对象添加额外行为。因此某些场景下可以用扩展语法代替装饰类实现类似目的。
- **需求场景**：`Printer` 绘图类有 3 个画图方法（实线、虚线、星号线），新增需求是希望在每次绘图开始和结束后有一段文字说明。对每个绘图方法都做装饰会显得冗余，尤其未来 `Printer` 还可能新增其他绘图方法。
- **用扩展方法代替装饰类**：
  ```kotlin
  class Printer {
      fun drawLine() {
          println("——— ——— ——— ——— ——— ——— ——— ———")
      }
      fun drawDottedLine() {
          println("- - - - -")
      }
      fun drawStars() {
          println("********")
      }
  }

  fun Printer.startDraw(decorated: Printer.() -> Unit) {
      println("+++ start drawing +++")
      this.decorated()
      println("+++ end drawing +++")
  }

  fun main(args: Array<String>) {
      Printer().run {
          startDraw { drawLine() }
          startDraw { drawDottedLine() }
          startDraw { drawStars() }
      }
  }
  // +++ start drawing +++
  // ——— ——— ——— ——— ——— ——— ——— ———
  // +++ end drawing +++
  // +++ start drawing +++
  // - - - - -
  // +++ end drawing +++
  // +++ start drawing +++
  // ********
  // +++ end drawing +++
  ```
- **语法分析**：给 `Printer` 扩展 `startDraw` 方法，它接收一个可执行的 `Printer` 类方法 `decorated`（带接收者的 Lambda `Printer.() -> Unit`），调用 `startDraw` 时在 `decorated` 执行前后分别打印"绘图开始"和"绘图结束"说明。结合 `run` 方法（接收 lambda、以闭包形式返回最后一行值），可以优雅实现需求。
- **"为什么"**：对每个绘图方法做装饰是冗余且难以扩展的；用扩展方法把"开始/结束标记"这个横切关注点集中到 `startDraw` 一处，任何现有或未来的绘图方法都能直接复用，无需修改 `Printer` 源码——扩展代替装饰者在代码量与灵活性上更胜一筹。

---

## 第9篇 函数式编程

**Q204: 什么是"函数式语言之争"？狭义与广义的函数式语言有何区别？**

- **争议的根源**：业界对"什么是函数式编程"没有统一标准。古老的 Haskell、ML、Lisp 是函数式语言的鼻祖，而更现代的 Scala、Clojure、JavaScript、Kotlin 也在某种程度上宣称自己是函数式语言。
- **狭义定义（纯函数式）**：有着非常简单且严格的语⾔标准——只通过**纯函数**进行编程，**不允许有副作用**，所有数据结构都是不可变的。以 Haskell 为代表，纯函数就像数学中的函数，给定同样的输入必然得到相同的输出，程序非常适合推理。
  ```kotlin
  // 狭义的函数式语言中，纯函数像数学函数一样
  // 同样的输入 -> 同样的输出，且无副作用
  fun square(x: Int) = x * x
  ```
- **狭义定义的劣势**：绝对的无副作用与不可变数据结构，使设计一些非常简单的程序也变得麻烦，比如实现一个随机数函数。因此 Scala、Kotlin 等语言允许可变数据的存在，依然可以在代码中拥有"状态"，并继承了 Java 面向对象的特性。
- **广义定义（后函数式）**：Scala 作者马丁认为，函数式语言不应是严格的刻板标准，而应随需求变化而发展。从广义上看，任何"**以函数为中心进行编程**"的语言都可称为函数式编程——可以在任何位置定义函数，也可以将函数作为值传递。
- **广义函数式语言的常见特性**：
  - 函数是头等公民；
  - 方便的闭包语法；
  - 递归式构造列表（list comprehension）；
  - 柯里化的函数；
  - 惰性求值；
  - 模式匹配；
  - 尾递归优化。
  - 若支持静态类型，还可能支持：强大的泛型能力（包括高阶类型）、Typeclass、类型推导。
- **Kotlin 的定位**：Kotlin 支持上述列表中的绝大多数特性，因此可以被称为**广义上的函数式语言**。但由于高度函数式化的编程思维与 Kotlin "更好的 Java" 的设计哲学相悖，它只克制地采纳了部分基础函数式特性（如高阶函数、部分模式匹配能力），并不像 Scala 那样彻底拥抱函数式编程。
- **本章的讨论范围**：现代编程语言中的函数式思想几乎都可追溯到纯函数式语言（Haskell），因此本章主要围绕**狭义上函数式语言的思想**进行讨论，即仅通过纯函数来设计程序。

**Q205: 什么是副作用？为什么说它会让程序变得危险、难以测试？**

- **副作用的通俗理解**：如同药品除了主药效还会产生额外的不良反应，编程中一个带副作用的函数，其"不良反应"会让程序变得危险，也让代码难以测试。
- **带副作用的示例**：用 `unsafeInterpreter` 函数把一组 `Format` 对象格式化为字符串打印出来。
  ```kotlin
  sealed class Format
  data class Print(val text: String): Format()
  object Newline: Format()

  val string = listOf<Format>(Print("Hello"), Newline, Print("Kotlin"))

  fun unsafeInterpreter(str: List<Format>) {
      str.forEach {
          when(it) {
              is Print -> print(it.text)
              is Newline -> println()
          }
      }
  }
  ```
- **问题 1：缺乏可测试性**。如果想测试 `unsafeInterpreter` 的逻辑，打印结果虽然能反映转换正确性，但若内部副作用不是 print 而是写数据库，测试工作会变得异常烦琐。
- **问题 2：难以被组合复用**。函数内部混杂了副作用与字符串格式转化的逻辑，当想复用转化后的结果时就会产生很大问题——一个持久化到数据库的操作，显然不能被当作转化字符串的功能方法来使用。

**Q206: 什么是纯函数？如何用纯函数消除副作用？**

- **纯函数的典型特征**：没有副作用。只要传递给它的参数一致，每次都可以获得相同的返回结果。
- **消除副作用**：使用 `fold` 把格式化结果作为返回值，而不是直接打印，得到纯函数版本：
  ```kotlin
  fun stringInterpreter(str: List<Format>) = str.fold("") { fullText, s ->
      when(s) {
          is Print -> fullText + s.text
          is Newline -> fullText + "\n"
      }
  }
  ```
- **收益**：在消除副作用之后，不管是在测试性还是代码的可复用性上都得到很好的提升——测试时只需断言返回值，复用时可以直接拿结果字符串继续处理。
- **为什么值得做**：函数式编程倡导使用纯函数，因为"避免副作用可以让程序代码变得更加安全可靠，利于测试，同时也易于组合"，这些特点构成了函数式编程的一大优点——近似于数学中的**等式推理**。

**Q207: 什么是引用透明性？它为什么是评判纯函数的基本法则？**

- **定义**：一个表达式在程序中可以被它等价的值替换，而**不影响结果**。对一个具体函数而言，如果它具备引用透明性，只要输入相同，对应的计算结果也相同。
- **"计算结果"的深层含义**：`unsafeInterpreter` 的返回结果值每次都是 `Unit`，也可以看成相同的结果值，但它有副作用，所以"计算结果"**不仅针对返回结果值**。一个函数若具备引用透明性，它内部的行为不会改变外部的状态。
  ```kotlin
  // unsafeInterpreter 中的 print 操作每次执行都会在控制台打印信息，
  // 改变了外部状态，所以具有副作用行为的函数违背了引用透明性原则
  ```
- **意义**：当我们尽量遵循引用透明性原则去编写程序，就具备了函数式编程的基础。避免副作用让代码更安全可靠、利于测试、易于组合，从而获得近似数学的等式推理能力。

**Q208: 纯函数（引用透明性）是否意味着不能使用任何可变变量？**

- **不一定**：引用透明性需要结合上下文来解读。例如下面的 `foo` 函数内部定义了可变的 `var y`，但只要传入相同的 `x`，计算结果依旧相同，所以它完全可以说是引用透明性的，也是一个纯函数。
  ```kotlin
  fun foo(x: Int): Int {
      var y = 0
      y = y + x
      return y
  }
  ```
- **黑盒视角**：`foo` 函数具备局部可变性，但当它被外部执行调用的时候，函数整体会被看成一个黑盒，程序依旧符合引用透明性。
- **辩证看待可变性**：关于副作用需要将话题限定在一定的抽象层次，因为没有绝对的"无副作用"——即使是纯函数，也会使用内存、占用 CPU。局部可变性有时候能让程序设计变得更自然、性能更好，所以函数式编程并不意味着拒绝可变性，合理地结合可变性和不可变性能够发挥更好的作用。

**Q209: 纯函数存在哪些局限性？（随机数函数为什么不是纯函数）**

- **无法表达真实世界的不确定性**：常见的随机数函数 `random` 每次调用都没有参数，但每次输出的随机数都不同，它并不符合"同样的输入必然得到相同输出"的纯函数定义，因此随机数函数不是"纯函数"。
- **死循环也能是"相同的计算结果"**：一个符合引用透明性的程序，它的"相同结果"可能是死循环（见代换模型一节的示例），这暴露了纯函数等式推理的一个尴尬事实。
- **结论**：虽然纯函数在绝大多数场景下利于程序设计，但面对现实世界的随机性、IO 等副作用时，纯函数有其不胜任的时候。这正是后续用 Monad 等结构"把副作用限制在管道容器之内"来组合的动机。

**Q210: 什么是代换模型？应用序与正则序有何区别？（f1(1, f2(2)) 之谜）**

- **问题引出**：看一段符合引用透明性的 Kotlin 代码，`f1(1, f2(2))`。若执行它，`f2` 必然被不断调用，导致 `eval to bottom`，产生死循环：
  ```kotlin
  fun f1(x: Int, y: Int) = x
  fun f2(x: Int): Int = f2(x)

  >>> f1(1, f2(2))  // Kotlin 中会无限递归
  ```
  而 Haskell 程序员将其翻译成等价版本后却成功返回了结果 1。原因在于两种语言采用了不同的**代换模型**（求值策略）。
- **应用序（Applicative Order）**：大部分熟悉的主流语言如 Kotlin、C、Java 都是"应用序"语言。当要执行一个过程时，就**先对过程参数进行求值**。上述 Kotlin 代码中调用 `f1(1, f2(2))` 时，程序会先对 `f2(2)` 求值，从而不断地递归调用 `f2` 导致死循环。
  ```kotlin
  fun f1(x: Int, y: Int) = x
  // 应用序：先求值 f2(2) → 无限递归 → 死循环
  ```
- **正则序（Normal Order）**：Haskell 采用了不同的逻辑，它会**延迟对过程参数的求值**，直到确实需要用到它的时候才进行计算，这就是"正则序"，是一个惰性求值的过程。调用 `f1(1, f2(2))` 时，由于 `f1` 的过程体中根本不需要用到 `y`，所以不会对 `f2(2)` 求值，直接返回 `x` 的值 1。
  ```haskell
  f1 :: Int -> Int -> Int
  f1 x y = x + y     -- 正则序：f2(2) 未被使用则不求值
  f2 :: Int -> Int
  f2 x = x
  ```
- **启示**：这就是为什么同一段逻辑在 Kotlin 中死循环、在 Haskell 中正常返回——差异不在于函数本身，而在于语言的**求值策略**（何时计算）。

**Q211: 什么是惰性求值？Thunk 机制是如何实现它的？**

- **概念**：Haskell 是默认采用惰性求值的语言，在 Kotlin 和其他一些语言（如 Scala、Swift）中，也可以利用 `lazy` 关键字来声明惰性的变量和函数。
- **优点**：惰性求值可以带来很多优势，例如"**无限长的列表结构**"。
- **缺点**：它也会制造麻烦——让程序求值模型变得更加复杂，滥用惰性求值也会导致效率下降。
- **实现机制（Thunk）**：Haskell 中惰性求值主要靠 Thunk 这种机制实现。理解 Thunk 很容易：把"一段代码"包装成"一个未来可以执行的函数"，那么它就变成了惰性的、可替代的。比如针对非纯函数 `println`，可以这样改造让它变得 "lazy"：
  ```kotlin
  fun lazyPrintln(msg: String) = { println(msg) }
  ```
  当程序调用 `lazyPrintln("I am a IO operation.")` 时，它仅仅只是返回一个可以执行 `println` 的函数，打印动作并没有发生，它是惰性的，也是可替代的。这样我们就可以在程序中将这些 IO 操作进行**组合**，最后再统一执行它们——这正是 10.3 节组合业务副作用时使用的思路。

**Q212: 为什么泛型（一阶参数多态）在需要更高阶抽象时会产生代码冗余？**

- **泛型的贡献与局限**：利用泛型多态（一阶参数多态）在很大程度可以减少大量相同的代码；但当需要更高阶的抽象时，泛型也避免不了代码冗余。
- **冗余示例**：标准库中的 `List`、`Set` 都实现了 `Iterable` 接口，它们都有相同的方法如 `filter`、`remove`。尝试通过泛型设计 `Iterable`：
  ```kotlin
  interface Iterable<T> {
      fun filter(p: (T) -> Boolean): Iterable<T>
      fun remove(p: (T) -> Boolean): Iterable<T> = filter { x -> !p(x) }
  }
  ```
  由于 `filter`、`remove` 需要返回**具体的容器类型**，`List` 和 `Set` 都不得不重新实现这些方法：
  ```kotlin
  interface List<T>: Iterable<T> {
      override fun filter(p: (T) -> Boolean): List<T>
      override fun remove(p: (T) -> Boolean): List<T> = filter { x -> !p(x) }
  }

  interface Set<T>: Iterable<T> {
      override fun filter(p: (T) -> Boolean): Set<T>
      override fun remove(p: (T) -> Boolean): Set<T> = filter { x -> !p(x) }
  }
  ```
- **改进思路**：假使类型也能像函数一样支持高阶，也就是**可以通过类型来创造新的类型**，那么多阶类型就可以上升到更高的抽象，从而进一步消除冗余的代码，这便是**高阶类型（higher-order kind）**。

**Q213: 什么是高阶类型？如何用类型构造器构造新类型？**

- **先理解"类型构造器（type constructor）"**：与熟悉的"值构造器（value constructor）"相对。很多情况下值构造器可以是一个函数，给函数传递一个值参数，从而构造出一个新的值：
  ```kotlin
  (x: Int) -> x
  ```
  如果是类型构造器，就可以传递一个类型变量，然后构造出一个新的类型，比如 `List[T]`，当我们传入 `Int` 时，就可以构造出 `List[Int]` 类型。
- **一阶推导**：
  - 一阶值构造器：通过传入一个具体的值，然后构造出另一个具体的值。
  - 一阶类型构造器：通过传入一个具体的类型变量，然后构造出另一个具体的类型。
- **高阶函数突破一阶**：高阶函数可以支持传入一个值构造器，或返回另一个值构造器：
  ```kotlin
  { x: (Int) -> Int -> x(1) }          // 接收一个函数作为参数
  { x: Int -> {y: Int -> x + y} }      // 返回一个函数（柯里化）
  ```
- **高阶类型的定义**：同样的道理，高阶类型可以支持**传入构造器变量**，或构造出另一个**类型构造器**。假设 Kotlin 支持高阶类型的语法，我们可以定义一种类型构造器 `Container`，然后将其作为另一个类型构造器 `Iterable` 的类型变量：
  ```kotlin
  interface Iterable<T, Container<X>> {
      fun filter(p: (T) -> Boolean): Container<T>
      fun remove(p: (T) -> Boolean): Container<T> = filter { x -> !p(x) }
  }

  interface List<T>: Iterable<T, List>
  interface Set<T>: Iterable<T, Set>
  ```
  此时，`List`、`Set` 声明时**只需指明自己就是那个容器**，冗余的 `filter`、`remove` 实现消失了。
- **重要声明**：Kotlin 当前并不支持上述语法，这只是假设。但如果 Kotlin 支持高阶类型，就可以写出更加抽象和强大的代码。这是后续用 Kind + 扩展方法模拟高阶类型、实现 Typeclass 的动机。

**Q214: 什么是 Typeclass？Functor（函子）是什么？**

- **Typeclass 的由来**：在 Haskell 中，高阶类型的特性天然催生了这门语言中一项非常强大的语言特性——Typeclass。可以说 Typeclass 是高阶类型自然延伸出的"对类型进行抽象分类并赋予行为"的机制。
- **背景理论（范畴论）**：函数式编程非常近似数学，其背后理论是一套叫**范畴论**的学科——抽象地处理数学结构以及结构之间联系的数学理论，把这些概念形式化成一组组"物件"及"态射"。本质并不难：在编程中，函数可以看成具体类型之间的映射关系。
- **Functor 的定义**：**函子就是高阶类型之间的映射**——只需将其看成"高阶类型的参数类型之间的映射"。用 Scala 定义的高阶类型 Functor：
  ```scala
  trait Functor[F[_]] {
      def fmap[A, B](fa: F[A], f: A => B): F[B]
  }
  ```
- **实现分析**：
  1. Scala 的 `trait` 近似于 Kotlin 的 `interface`。因为它支持高阶类型，所以 `Functor` 支持传入类型变量 `F`，`F[_]` 中的 `F` 本身也是一个高阶类型（类型构造器）。
  2. `Functor` 中实现了 `fmap` 方法：它接收一个类型为 `F[A]` 的参数 `fa`，以及一个函数 `f: A => B`，通过 `f` 可以把 `fa` 中的元素类型 `A` 映射为 `B`，最终返回 `F[B]`。
- **应用场景**：Functor 的应用非常广泛，例如把一个 `List[Int]` 中的元素都转化为字符串。在 Scala 中通过隐式值让 `List` 集成 Functor 的功能：
  ```scala
  implicit val listFunctor = new Functor[List] {
      def fmap(fa: List[A])(f: A => B) = fa.map(f)
  }
  ```

**Q215: Kotlin 不支持高阶类型，如何用扩展方法模拟并实现 Typeclass？（Kind 包装技巧）**

- **理论基础**：Jeremy Yallop 和 Leo White 在论文《Lightweight higher-kinded polymorphism》中阐述了一种**模拟高阶类型**的方法——用 `Kind` 类型把"类型构造器应用到类型参数"的结果显式表示出来。
- **第一步：定义 Kind 与 Functor 接口**：
  ```kotlin
  interface Kind<out F, out A>

  interface Functor<F> {
      fun <A, B> Kind<F, A>.map(f: (A) -> B): Kind<F, B>
  }
  ```
  `Kind<out F, out A>` 表示类型构造器 `F` 应用类型参数 `A` 产生的类型（`F` 实际上并不能携带类型参数，只是模拟）。
- **第二步：让具体类型实现 Kind**。自定一个 `List` 类型，由 `Nil`（空列表）与 `Cons`（head 与 tail 连接而成的列表）两个状态构成：
  ```kotlin
  sealed class List<out A> : Kind<List.K, A> {
      object K
  }
  object Nil : List<Nothing>()
  data class Cons<A>(val head: A, val tail: List<A>) : List<A>()
  ```
  `List<A>` 实现了 `Kind<List.K, A>`，即 `List<A>` 是"类型构造器 `List.K` 应用类型参数 `A`"得到的类型，由此可以用 `List.K` 代表 `List` 这个高阶类型。
- **第三步：构造 List 的 Functor 实例**：
  ```kotlin
  @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
  inline fun <A> Kind<List.K, A>.unwrap(): List<A> =
      this as List<A>

  object ListFunctor: Functor<List.K> {
      override fun <A, B> Kind<List.K, A>.map(f: (A) -> B): Kind<List.K, B> {
          return when (this) {
              is Cons -> {
                  val t = this.tail.map(f).unwrap()
                  Cons<B>(f(this.head), t)
              }
              else -> Nil
          }
      }
  }
  ```
- **关键一步：如何使用这个实例**。Kotlin 无法将 `object` 内部的扩展方法直接 `import` 进来（`import ListFunctor.*` 不行）。幸运的是，Kotlin 的 **receiver（接收者）机制**可以将 object 中的成员引入作用域，所以只需使用 `run` 函数即可使用这个实例：
  ```kotlin
  ListFunctor.run {
      Cons(1, Nil).map { it + 1 }
  }
  ```

**Q216: 用 Kotlin 实现 Typeclass 的通用做法是什么？**

- **总结三步法**：
  1. 利用**类型的扩展语法**定义通用的 Typeclass 接口（用扩展函数作为接口方法，让目标类型"拥有"该能力）；
  2. 通过 **object 定义具体类型的 Typeclass 实例**；
  3. 在实例 **`run` 函数的闭包**中，目标类型的对象或值随之支持了相应的 Typeclass 功能。
- **为什么是扩展语法 + object + run 的组合**：Kotlin 没有高阶类型，接口方法若写成普通函数则无法针对"容器类型 F"做抽象；用 `F.eq(that: F)` 这类**接收者扩展**，配合 `run { }` 把实例的成员引入作用域，就能在调用点像原生方法一样使用 `a.eq(b)`，达到近似 Typeclass 的效果。
- **设计价值**：Typeclass 这种多态技术很适合函数式编程——Typeclass 之间可以灵活组合（如实现 Show 时引入 Foldable），使得用它们进行程序设计非常灵活且**低耦合**。

**Q217: 如何用 Typeclass 实现 Eq（判等）？**

- **定义 Eq 接口**：只要为一种类型定义一个 `Eq` 的 Typeclass 实例，就可以在实例 `run` 函数中对该类型的对象或值进行判等操作。
  ```kotlin
  interface Eq<F> {
      fun F.eq(that: F): Boolean
  }
  ```
- **为 Int 类型实现实例**：
  ```kotlin
  object IntEq : Eq<Int> {
      override fun Int.eq(that: Int): Boolean {
          return this == that
      }
  }

  IntEq.run {
      val a = 1
      println(a.eq(1))
      println(a.eq(2))
  }
  // 运行结果
  true
  false
  ```
- **为高阶类型（Kind<List.K, A>）实现实例**：`ListEq` 是一个抽象类，接收一个类型为 `Eq<A>` 的构造参数 `a`（即一个 Eq 的实例），并实现 `Eq<Kind<List.K, A>>`，从而在 `eq` 内部调用 `a` 的 `eq` 方法递归地比较元素：
  ```kotlin
  abstract class ListEq<A>(val a: Eq<A>) : Eq<Kind<List.K, A>> {
      override fun Kind<List.K, A>.eq(that: Kind<List.K, A>): Boolean {
          val curr = this
          return if (curr is Cons && that is Cons) {
              val headEq = a.run {
                  curr.head.eq(that.head)
              }
              if (headEq) curr.tail.eq(that.tail) else false
          } else if (curr is Nil && that is Nil) {
              true
          } else false
      }
  }

  object IntListEq : ListEq<Int>(IntEq)

  IntListEq.run {
      val a = Cons(1, Cons(2, Nil))
      println(a.eq(Cons(1, Cons(2, Nil))))
      println(a.eq(Cons(1, Nil)))
  }
  // 运行结果
  true
  false
  ```
- **设计要点**：`ListEq` 比单纯的 `Eq` 前进了一大步——它通过组合 `Eq<A>` 与递归结构，让高阶类型 `Kind<List.K, A>` 也具备了判等能力，展示了 Typeclass 之间"用实例组合实例"的扩展方式。

**Q218: 如何用 Typeclass 实现 Show 和 Foldable？**

- **Show 接口**：类似 Java 中给类实现 `toString` 方法，通过设计一个名为 `Show` 的 Typeclass 实现类似功能：
  ```kotlin
  interface Show<F> {
      fun F.show(): String
  }

  class Book(val name: String)
  object BookShow : Show<Book> {
      override fun Book.show(): String = this.name
  }

  BookShow.run {
      println(Book("Dive Into Kotlin").show())
  }
  // 运行结果
  Dive Into Kotlin
  ```
- **实现 List 的 Show 需要 Foldable**：与 Eq 不同，List 的打印结果需要把元素的打印结果都**拼装**起来，因此需要给 List 增加一个类似 `fold` 的操作。先设计支持高阶类型效果的 `Foldable`：
  ```kotlin
  interface Foldable<F> {
      fun <A, B> Kind<F, A>.fold(init: B): ((B, A) -> B) -> B
  }

  @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
  inline fun <A> Kind<List.K, A>.unwrap(): List<A> =
      this as List<A>

  object ListFoldable: Foldable<List.K> {
      override fun <A, B> Kind<List.K, A>.fold(init: B): ((B, A) -> B) -> B = { f ->
          fun fold0(l: List<A>, v: B): B {
              return when (l) {
                  is Cons -> {
                      fold0(l.tail, f(v, l.head))
                  }
                  else -> v
              }
          }
          fold0(this.unwrap(), init)
      }
  }
  ```
- **组合 Foldable 与 Show 实现 ListShow**：`ListShow` 的设计思路与 `ListEq` 相似，只是需要 Foldable 的额外帮助——先 fold 出所有元素的 `show()` 结果列表，再 `joinToString` 拼装：
  ```kotlin
  abstract class ListShow<A>(val a: Show<A>) : Show<Kind<List.K, A>> {
      override fun Kind<List.K, A>.show(): String {
          val fa = this
          return "[" + ListFoldable.run {
              fa.fold(listOf<String>())({ r, i ->
                  r + a.run { i.show() }
              }).joinToString()
          } + "]"
      }
  }

  object BookListShow : ListShow<Book>(BookShow)

  BookListShow.run {
      println(
          Cons(
              Book("Dive into Kotlin"),
              Cons(Book("Thinking in Java"), Nil)
          ).show()
      )
  }
  // 运行结果
  [Dive into Kotlin, Thinking in Java]
  ```
- **启示**：Typeclass 之间的组合使得用它们进行程序设计非常灵活且低耦合——实现一个能力时可以"借用"其他 Typeclass（这里 `ListShow` 复用了 `Foldable` 与 `Show`），无需修改任何已有的类型定义。

**Q219: 什么是 Monoid？它由哪些部分组成、遵循哪些法则？**

- **Monoid 的双重含义**：一方面它是一个很简单的 Typeclass；另一方面它也被用来描述某一种代数——这类代数遵循 Monoid 法则，即**结合律**和**同一律**。
- **三个组成部分**：
  - 一个抽象类型 `A`；
  - 一个满足结合律的二元操作 `append`，接收任何两个 `A` 类型的参数，返回一个 `A` 类型的结果；
  - 一个单位元 `zero`，它同样是 `A` 类型的一个值。
- **两条数学法则**：
  - **结合律**：`append(a, append(b, c)) == append(append(a, b), c)`，对任何 `A` 类型的值（a、b、c）均成立。
  - **同一律**：`append(a, zero) == a` 或 `append(zero, a) == a`，单位元 `zero` 与任何 `A` 类型的值 `a` 做 append 操作，结果都等于 `a`。
- **用 Kotlin 表达 Monoid**：它是一个新的 Typeclass：
  ```kotlin
  interface Monoid<A> {
      fun zero(): A
      fun A.append(b: A): A
  }
  ```
- **典型实例：字符串拼接**。字符串拼装是典型的符合 Monoid 法则的具体例子：抽象类型 `A` 具体化为 `String`；任何 3 个字符串的拼接满足结合律（`("Dive"+"into")+"Kotlin" == "Dive"+("into"+"Kotlin")`）；单位元 `zero` 为空字符串。
  ```kotlin
  object stringConcatMonoid: Monoid<String> {
      override fun String.append(b: String): String = this + b
      override fun zero(): String = ""
  }
  ```

**Q220: Monoid 和折叠（fold）有什么关系？为什么说 Monoid 天然适合 fold？**

- **通用数据结构的价值**：Monoid 是一种通用的数据结构，这意味着可以利用它来编写通用的代码。它单独看定义非常简单，但**当它与列表结构联系在一起时，就可以发挥很大的作用**。
- **sum 方法示例**：为前文定义的 `List` 类型扩展一个 `sum` 方法，支持使用者指定一种二元操作对列表元素进行操作。这显然是一个典型的 fold 操作：
  ```kotlin
  fun <A> List<A>.sum(ma: Monoid<A>): A {
      val fa = this
      return ListFoldable.run {
          fa.fold(ma.zero())({ s, i ->
              ma.run {
                  s.append(i)
              }
          })
      }
  }
  ```
  `sum` 方法接收一个 `Monoid<A>` 类型的参数 `ma`——Monoid 这种抽象结构**非常适合 fold 这种折叠操作**。
- **与 Kotlin 集合库的对照**：回顾 Kotlin 集合库中 `fold` 相关方法的定义：
  ```kotlin
  inline fun <T, R> Iterable<T>.fold(
      initial: R,
      operation: (acc: R, T) -> R
  ): R
  ```
  `fold` 方法的两个参数 `initial` 和 `operation` 恰好对应了 `Monoid<A>` 中的 **zero 单位元**和 **append 操作**——这正是 Monoid 与 fold 天然契合的本质原因。
- **实际应用**：
  ```kotlin
  println(
      Cons(
          "Dive ",
          Cons(
              "into ",
              Cons("Kotlin", Nil)
          )
      ).sum(stringConcatMonoid)
  )
  // 运行结果
  Dive into Kotlin
  ```
- **可推广性**：除了字符串拼装，还有很多同样适合使用 Monoid 法则的操作，比如加法。事实上，可以用 Monoid 来抽象更加复杂的业务——凡是有"零值"且操作满足结合律的场景，都能用 Monoid 统一折叠。

**Q221: 什么是函子定律（Functor 定律）？Functor 的能力局限在哪里？**

- **Functor 回顾**：Functor 为类型 `Kind<F, A>` 定义了 `map` 操作，返回另一个类型 `Kind<F, B>`。类型参数 `F` 模拟了高阶类型中的类型构造器。`F` 除了是 `List.K`，还可以是 `Option.K`（可空或存在的高阶类型）、`Effect.K`（拥有副作用的高阶类型）、`Parser.K`（解析器的高阶类型）等——它们都是"阉割"版的 `List.K`（容器内只有一个值），都能派生对应的 Functor 实例：
  ```kotlin
  object OptionFunctor: Functor<Option.K> {
      override fun <A, B> Kind<Option.K, A>.map(f: (A) -> B): Kind<Option.K, B> { ... }
  }
  object EffectFunctor: Functor<Effect.K> {
      override fun <A, B> Kind<Effect.K, A>.map(f: (A) -> B): Kind<Effect.K, B> { ... }
  }
  ```
- **函子定律 1：同一律法则**。假设存在一个 `identity` 函数，接收 `A` 类型的参数 `a`，返回结果还是 `a`。那么调用函子实例的 `map` 方法执行 `identity` 函数时，返回的结果还是实例本身：
  ```kotlin
  fun identity<A>(a: A) = a

  ListFunctor.run {
      println(Cons(1, Nil).map { identity(it) })
  }
  // 运行结果
  Cons(1, Nil)
  ```
- **函子定律 2：map 的组合满足结合律**。先对函子实例应用函数 `f` 进行 map，再将结果应用函数 `g` 进行 map，与直接对函子实例应用"两个函数组合出的新函数"进行 map，结果相同：
  ```kotlin
  fun f(a: Int) = a + 1
  fun g(a: Int) = a * 2

  ListFunctor.run {
      val r1 = Cons(1, Nil).map { f(it) }.map { g(it) }
      val r2 = Cons(1, Nil).map { f(g(it)) }
      println(r1 == r2)
  }
  // 运行结果
  true
  ```
- **定律的意义**：函子定律保证了实例本身的容器 `F` 不变，但可以改变容器内部的程序状态，主要通过 `map` 方法实现，并且 `map` 施加的函数可以进行组合。
- **Functor 的局限**：`map` 操作并没有提供足够高的抽象组合能力。假如把高阶类型 `Kind<F, A>` 比作一个管道，Functor 提供了对**管道内状态**进行转化的能力，即 `map(fa, f)`。但真实世界充满了副作用，为了把这些拥有相同容器类型的"管道"组合起来（拼装成新管道而规格不变），需要一个新的 `map2` 函数：
  ```kotlin
  fun <A, B> map(fa: Kind<F, A>, f: (A) -> B): Kind<F, B>
  fun <A, B, C> map2(fa: Kind<F, A>, fb: Kind<F, B>, f: (A, B) -> C): Kind<F, C>
  ```
- **设计思想**：把副作用限制在管道容器之内，将管道视为一个拥有原子性的整体（如贪吃蛇的方块），那么在这个层面它依旧符合引用透明性；于是可以把相同容器内的副作用操作利用函数 `f` 进行组合，**尽量推迟到最后执行**——这是典型的函数式编程思路。

**Q222: 为什么需要 flatMap？如何用 pure（unit）与 flatMap 实现更复杂的组合？**

- **map 组合的困境——嵌套容器**：想实现 `map2` 的效果，直接利用 `map` 会得到一个嵌套容器的结构。对类型 `Kind<F, A>` 进行 `map`，应用一个返回 `Kind<F, B>` 的函数，得到的结果将是 `Kind<F, Kind<F, B>>`。显然需要一个 `flatten` 操作，把嵌套的容器 `F` 提取出来，转化为 `Kind<F, B>`。
- **flatMap 的引入**：Kotlin 中的 `flatMap` 支持 flatten 操作，本质上它可以看成 **map 与 flatten 的结合操作**。因此给高阶类型扩展一个 `flatMap` 方法：
  ```kotlin
  fun <A, B> Kind<F, A>.flatMap(f: (A) -> Kind<F, B>): Kind<F, B>
  ```
  `flatMap` 和 `map` 一样是高阶函数：参数 `f` 接收类型 `A` 的参数，返回另一个 `Kind<F, B>` 的值；`flatMap` 最终返回的结果类型也是 `Kind<F, B>`。
- **用 flatMap 实现 map2**：高阶类型一旦有了 `flatMap`，就可以很容易地实现 `map2`：
  ```kotlin
  fun <A, B, C> map2(fa: Kind<F, A>, fb: Kind<F, B>, f: (A, B) -> C): Kind<F, C> =
      fa.flatMap { a -> fb.map { b -> f(a, b) } }
  ```
- **pure（unit）与最小操作集**：如果再引入 `pure` 方法（有时也叫 `unit`，在 Haskell 中它对应 `return`，`flatMap` 则代表 `bind`），它的核心作用就是将 `A` 类型的参数转化为 `Kind<F, A>` 类型：
  ```kotlin
  fun <A> pure(a: A): Kind<F, A>
  ```
  于是 `map` 方法同样也可以用 `flatMap` 来实现：
  ```kotlin
  fun <A, B> flatMap(fa: Kind<F, A>, f: (A) -> Kind<F, B>): Kind<F, B>
  fun <A, B> map(fa: Kind<F, A>, f: (A) -> B): Kind<F, B> =
      flatMap(fa) { a -> pure(f(a)) }
  ```
- **结论**：`pure` 和 `flatMap` 可作为**最原始的（最小）操作集合**，利用这两个函数的组合可以实现 `map`、`map2` 及更复杂的数据转换操作。如果再定义一种新的 Typeclass，同时包含 `pure` 和 `flatMap` 操作，那么它将是一种**最通用的函数式结构**——这就是 **Monad**。

**Q223: 什么是 Monad？它与 Monad Typeclass 有何区别？**

- **概念区分**：谈论 Monad 时，需要对 **Monad Typeclass** 及 **Monad 概念本身**进行区分。准确地说，**Monad 是满足 Monad 法则的一个最小区（最小操作集）的实现**，可被称为单子。这个实现的组合并不是唯一的——可以用 `pure` + `flatMap` 来满足法则，同样也可以用 `pure` + `compose` 来代替实现。
- **Monad 的通用性**：Monad 是函数式编程中最通用的抽象数据结构。即使没有 Monad，我们依然可以进行某种程度上的函数式编程；但利用 Monad 可以运用组合的思想来抽象绝大部分的事物。菲利普·瓦德勒（Phillip Wadler）有一句著名的解读："**Monad 无非就是个自函子范畴上的幺半群**"。
- **Monad<F> 的 Typeclass 定义**（其中一种版本，必须满足 Monad 法则）：
  ```kotlin
  interface Monad<F> {
      fun <A> pure(a: A): Kind<F, A>
      fun <A, B> Kind<F, A>.flatMap(f: (A) -> Kind<F, B>): Kind<F, B>
  }
  ```
  为 `Monad<F>` 定义了 `pure` 方法，以及利用 Kotlin 语言特性为模拟的高阶类型 `Kind<F, A>` 扩展了 `flatMap` 方法。
- **创建 ListMonad 实例**：
  ```kotlin
  object ListMonad : Monad<List.K> {
      private fun <A> append(fa: Kind<List.K, A>, fb: Kind<List.K, A>): Kind<List.K, A> =
          if (fa is Cons) {
              Cons(fa.head, append(fa.tail, fb).unwrap())
          } else {
              fb
          }

      override fun <A> pure(a: A): Kind<List.K, A> {
          return Cons(a, Nil)
      }

      override fun <A, B> Kind<List.K, A>.flatMap(f: (A) -> Kind<List.K, B>): Kind<List.K, B> {
          val fa = this
          val empty: Kind<List.K, B> = Nil
          return ListFoldable.run {
              fa.fold(empty)({ r, l ->
                  append(r, f(l))
              })
          }
      }
  }
  ```
- **组合的幂力（为什么不用测试）**：当用 `Monoid<A>`、`Monoid<B>` 组合出新的 `Monoid<C>` 时，这个新的 Monoid 依旧遵循 Monoid 法则（同一律与结合律）。这是函数式编程的魅力之一——只要像遵循数学定理一样进行组合，无须关注过程中具体类型的细节，最终推导出的结果依旧遵循正确的法则，省去了测试的工作。Monad 的组合同样满足 Monad 定律。

**Q224: Functor、Applicative、Monad 之间是什么关系？如何用 Applicative 重新定义 Monad？**

- **依赖关系**：数学中的 3 种代数结构存在如下依赖关系：
  ```
  Functor -> Applicative -> Monad
  ```
  也就是说，**所有的 Monad 都是 Applicative，所有的 Applicative 都是 Functor**。在 Haskell 的发展历史中，Monad 跳过了 Applicative 被更早地发现——这容易理解，因为相比 Applicative，Monad 要更加通用一些。
- **Applicative<F> 的定义**：它直接实现了 `Functor<F>`，并在其内部为高阶类型扩展了一个 `ap` 方法。`ap` 方法接收一个高阶类型为 `Kind<F, (A) -> B>` 的参数（即容器内装着一个函数），然后返回 `Kind<F, B>`：
  ```kotlin
  interface Applicative<F> : Functor<F> {
      fun <A> pure(a: A): Kind<F, A>
      fun <A, B> Kind<F, A>.ap(f: Kind<F, (A) -> B>): Kind<F, B>

      override fun <A, B> Kind<F, A>.map(f: (A) -> B): Kind<F, B> {
          return ap(pure(f))
      }
  }
  ```
- **用 Applicative 重新定义 Monad**：有了 `Applicative<F>` 之后，就可以用它来重新定义 `Monad<F>`：
  ```kotlin
  interface Monad<F> : Applicative<F> {
      fun <A, B> Kind<F, A>.flatMap(f: (A) -> Kind<F, B>): Kind<F, B>

      override fun <A, B> Kind<F, A>.ap(f: Kind<F, (A) -> B>): Kind<F, B> {
          return f.flatMap { fn ->
              this.flatMap { a ->
                  pure(fn(a))
              }
          }
      }
  }
  ```
- **结果**：这样 `Monad<F>` 既是 `Functor<F>`，又是 `Applicative<F>`，所以它也同时定义了 `map` 和 `ap` 方法。这套递进关系揭示了 Monad 作为"最通用抽象结构"的位置：它向下兼容了 Functor 与 Applicative 的所有能力，向上又通过 `flatMap` 提供了顺序组合的威力。

**Q225: Monad 如何组合现实中的副作用？（以标准 IO 为例）**

- **Monad 的使命**：Monad 被创造的一个很大使命，就是用来**组合现实中的副作用**，由此发挥函数式编程的优点（引用透明性和等式推理），设计准确、容易测试的程序。
- **创建 StdIO<A> 类型**：代表标准输入输出，实现了 `Kind<StdIO.K, A>`，并定义了 `ReadLine`、`WriteLine`、`Pure` 三种状态：
  ```kotlin
  @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
  inline fun <A> Kind<StdIO.K, A>.unwrap(): StdIO<A> =
      this as StdIO<A>

  sealed class StdIO<A> : Kind<StdIO.K, A> {
      object K
      companion object {
          fun read(): StdIO<String> {
              return ReadLine
          }
          fun write(l: String): StdIO<Unit> {
              return WriteLine(l)
          }
          fun <A> pure(a: A): StdIO<A> {
              return Pure(a)
          }
      }
  }

  object ReadLine : StdIO<String>()
  data class WriteLine(val line: String) : StdIO<Unit>()
  data class Pure<A>(val a: A) : StdIO<A>()
  ```
- **实现 StdIOMonad**：为 `StdIO` 提供可组合的方法。注意 `flatMap` 返回的是一个 `FlatMap` 数据类——它把"要执行的 IO 操作"与"后续函数"记录下来，而**并没有真正执行**：
  ```kotlin
  data class FlatMap<A, B>(val fa: StdIO<A>, val f: (A) -> StdIO<B>) : StdIO<B>()

  object StdIOMonad : Monad<StdIO.K> {
      override fun <A, B> Kind<StdIO.K, A>.flatMap(f: (A) -> Kind<StdIO.K, B>): Kind<StdIO.K, B> {
          return FlatMap<A, B>(this.unwrap(), { a -> f(a).unwrap() })
      }
      override fun <A> pure(a: A): Kind<StdIO.K, A> {
          return Pure(a)
      }
  }
  ```
- **解释器 perform**：`perform` 接收一个 `StdIO<A>` 类型的参数，真正解释并执行其中的副作用操作：
  ```kotlin
  fun <A> perform(stdIO: StdIO<A>): A {
      fun <C, D> runFlatMap(fm: FlatMap<C, D>) {
          perform(fm.f(perform(fm.fa)))
      }
      @Suppress("UNCHECKED_CAST")
      return when (stdIO) {
          is ReadLine -> readLine() as A
          is Pure<A> -> stdIO.a
          is FlatMap<*, A> -> runFlatMap(stdIO) as A
          is WriteLine -> println(stdIO.line) as A
      }
  }
  ```
- **业务示例：读取两个数字求和并输出**。用 `StdIO` 的 `read` 和 `write` 方法分别处理读写操作（返回类型都实现了 `Kind<StdIO.K, A>`，因此都可以调用 `flatMap`）：
  ```kotlin
  val io = StdIOMonad.run {
      StdIO.read().flatMap { a ->
          StdIO.read().flatMap { b ->
              StdIO.write((a.toInt() + b.toInt()).toString())
          }
      }
  }
  perform(io.unwrap())
  ```
- **延迟执行与引用透明性**：通过 `flatMap` 组合 `StdIO` 对象，把所有操作定义为名为 `io` 的变量。**当 io 变量被定义时，业务逻辑也同时被定义，然而读写并没有发生**。至此整个程序依旧符合引用透明的原则。由于组合满足 Monad 定律，只要编译通过、各环节的类型检查无误，就可以相信这段代码是正确的。最终执行 `perform` 方法，整个 io 操作才会被真正触发——这正是 10.1.3 节"惰性求值 + 组合副作用"思路的落地。

**Q226: 为什么要用类型代替异常处理错误？这与函数式编程有什么关系？**

- **Kotlin 对受检异常的态度**：Kotlin 抛弃了 Java 中的受检异常（Checked Exception）。受检异常的优势是编译器强制检查、不会忘记处理、可在编译期提前发现 bug；但若强制在大型软件工程中应用，则会让编码异常烦琐、降低生产力，因此类似 C# 的语言也没有采用受检异常。
- **异常与函数式编程的根本冲突**：抛出异常这种做法本身其实是**一种副作用**，它破坏了"引用透明性"。但这并不意味着函数式编程就要抛弃错误处理——任何健壮的程序都需要对具体错误进行捕捉并给出正确的反馈。
- **函数式的解决思路**：换一种思路——利用高阶类型及 Monad 这种通用的函数式结构，用**更抽象的数据类型来代替异常处理错误**。用类型来处理错误还有一个优点：**类型安全**（编译器可以在类型层面保证错误被处理）。
- **典型代表**：`Option` / `OptionT`、`Either` / `EitherT` 为业务中的错误处理提供了新的思路——抛弃传统的异常处理，基于高阶类型来定义和区分业务中非正常的情况，这种思路依然符合引用透明性。

**Q227: 如何用 Option 与 OptionMonad 处理错误？（读值求和例子）**

- **Option 类型的定义**：Kotlin 的可空类型（5.2 节）某种程度上就是利用类型代替 Checked Exception 来防止 NPE 问题。在学会模拟高阶类型之后，可以自定义一个 `Option` 类型。`Kind<Option.K, A>` 与 `Kind<List.K, A>` 一样模拟了一种高阶类型，用来表示**存在值或空值**两种状态，分别对应数据类 `Some` 和单例对象 `None`：
  ```kotlin
  @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
  inline fun <A> Kind<Option.K, A>.unwrap(): Option<A> =
      this as Option<A>

  sealed class Option<out A> : Kind<Option.K, A> {
      object K
  }
  data class Some<V>(val value: V) : Option<V>()
  object None : Option<Nothing>()
  ```
- **创建 OptionMonad**：给 `Option` 类型扩展 `flatMap`、`pure` 及 `map` 方法，使它具备强大的组合能力：
  ```kotlin
  object OptionMonad : Monad<Option.K> {
      override fun <A, B> Kind<Option.K, A>.flatMap(f: (A) -> Kind<Option.K, B>): Kind<Option.K, B> {
          val oa = this
          return when (oa) {
              is Some -> f(oa.value)
              else -> None
          }
      }
      override fun <A> pure(a: A): Kind<Option.K, A> {
          return Some(a)
      }
  }
  ```
  `flatMap` 的实现非常直观：`Some` 时继续应用 `f`，`None` 时短路直接返回 `None`——错误一路传播。
- **应用场景**：10.3.3 节的读值求和例子在现实中可能出错，因为输入的值可能不是数字，`toInt` 会失败。一种可行的思路是通过 `Option<Int>` 类型表示读取结果：检测到读取值并非数字时当作 `None`；只要用户的输入存在一次 `None`，最终计算结果也为 `None`（非合理的情况）。
  ```kotlin
  fun readInt(): StdIO<Option<Int>> {
      return StdIOMonad.run {
          val r = StdIO.read().map {
              when {
                  it.matches(Regex("[0-9]+")) -> Some(it.toInt())
                  else -> None
              }
          }
          r.unwrap()
      }
  }

  fun addOption(oa: Option<Int>, ob: Option<Int>) = {
      OptionMonad.run {
          oa.flatMap { a ->
              ob.map { b -> a + b }
          }
      }
  }
  ```
  `addOption` 依赖 `OptionMonad` 扩展的 `flatMap` 方法实现两个读值之间的组合，最终返回结果也是 `Option<Int>` 类型。
- **整体错误处理函数**：
  ```kotlin
  fun errorHandleWithOption() {
      StdIOMonad.run {
          readInt().flatMap { oi ->
              readInt().flatMap { oj ->
                  val r = addOption(oi, oj)
                  val display = when (r) {
                      is Some<*> -> r.value.toString()
                      else -> ""
                  }
                  StdIO.write(display)
              }
          }
      }
  }
  ```
- **该方案的不足**：从计算资源利用率角度来说，这个方案没有达到最优——如果第一次读值就出现了错误，最理想是马上返回非正常结果，而当前方案依旧会进行第二次读值。同时，对 `StdIO` 进行组合时，由于内部都是 `Option` 类型，每次都必须先对 `Option` 值进行模式匹配再处理，语法表达上会呈现多层嵌套，不够优雅。**OptionT 正是为此设计的数据类型**。

**Q228: 什么是 OptionT？它是如何解决 Option 组合时的嵌套问题的？**

- **核心思想**：如果能把 `StdIO<Option<T>>` 中的 `StdIO<Option<*>>` 看成一个**整体**，就可以直接对 `T` 进行组合操作，从而进一步提升可读性。OptionT 正是为此设计的数据类型。
- **定义**：`OptionT<F, A>` 的核心参数 `value` 的类型是 `Kind<F, Option<A>>`——即存在一个 `Option` 类型的值，给它套上类型构造器 `F`，再包裹一层 `OptionT`：
  ```kotlin
  data class OptionT<F, A>(val value: Kind<F, Option<A>>) {
      object K
      companion object {
          fun <F, A> pure(AP: Applicative<F>): (A) -> OptionT<F, A> = { v ->
              OptionT(AP.pure(Some(v)))
          }
          fun <F, A> none(AP: Applicative<F>): OptionT<F, A> {
              return OptionT(AP.pure(None))
          }
          fun <F, A> liftF(M: Functor<F>): (Kind<F, A>) -> OptionT<F, A> = { fa ->
              val v = M.run {
                  fa.map { Some(it) }
              }
              OptionT(v)
          }
      }

      fun <B> flatMap(M: Monad<F>, f: (A) -> OptionT<F, B>): OptionT<F, B> {
          val r = M.run {
              value.flatMap { oa ->
                  when (oa) {
                      is Some -> f(oa.value).value
                      else -> M.pure(None)
                  }
              }
          }
          return OptionT(r)
      }
      fun <B> flatMapF(M: Monad<F>, f: (A) -> Kind<F, B>): OptionT<F, B> {
          val ob = M.run {
              value.flatMap {
                  when (it) {
                      is Some -> f(it.value).map { Some(it) }
                      else -> pure(None)
                  }
              }
          }
          return OptionT(ob)
      }
      fun <B> map(F: Functor<F>, f: (A) -> B): OptionT<F, B> {
          val r: Kind<F, Option<B>> = F.run {
              value.map { ov ->
                  OptionMonad.run {
                      ov.map(f).unwrap()
                  }
              }
          }
          return OptionT(r)
      }
      fun getOrElseF(M: Monad<F>, fa: Kind<F, A>): Kind<F, A> {
          return M.run {
              value.flatMap {
                  when (it) {
                      is Some -> M.pure(it.value)
                      else -> fa
                  }
              }
          }
      }
  }
  ```
- **要点解析**：
  - `pure` 和 `none` 接收的参数只需拥有一个 `pure` 方法，所以类型是 **Applicative\<F\> 就足够了**，不必须是 Monad。
  - 最核心的 `flatMap` 方法返回值是一个 lambda（表达式函数体），类型为 `(A) -> OptionT<F, B>) -> OptionT<F, B>`。它的执行流程：
    1. 调用 `flatMap` 的 `OptionT` 实例，内部 `value` 对应的 `Option<A>` 部分的值**不存在（None）**，则直接返回一个 `None` 值转化的 `Monad<F>` 实例，再用 `OptionT` 包裹；
    2. 如果对应的 `Option<A>` 部分的值**存在（Some）**，就用函数 `f` 接收该对象的 `value` 进行处理，最终返回一个处理后的新的 `OptionT` 对象。
- **本质**：如果仔细思考，会发现 OptionT 本质上是在应用 Option 时、特定场景下一种**表达上的简化**。它直接消除了嵌套的层级——用 `OptionT` 类型的对象做组合，产生新的 `OptionT` 对象可以继续做其他的组合，一旦某次组合返回的 `Option<T>` 部分为 `None`，则停止（提前短路，避免无谓的第二次读值）。
- **改写读值求和例子**：
  ```kotlin
  fun errorHandleWithOptionT() {
      fun readInt(): OptionT<StdIO.K, Int> {
          val r = StdIOMonad.run {
              val r = StdIO.read().map {
                  when {
                      it.matches(Regex("[0-9]+")) -> Some(it.toInt())
                      else -> None
                  }
              }
              r.unwrap()
          }
          return OptionT(r)
      }
      val add = readInt().flatMap(StdIOMonad) { i ->
          readInt().flatMapF(StdIOMonad) { j ->
              StdIO.write((i + j).toString())
          }
      }
      add.getOrElseF(StdIOMonad, StdIO.write("input error"))
  }
  ```
- **收益**：运用 OptionT 之后，成功解决了之前的问题（提前短路、避免第二次读值），同时又保证了语法表达上的简洁——不再需要层层模式匹配。抽象的数据类型就像数学中的公式，定义上显得抽象，但只要耐心一步步推演其中的逻辑，就会无比正确。

**Q229: 什么是 Either 与 EitherT？它们相比 Option 有哪些优势？**

- **问题动机**：现实中的业务错误种类常常是多样化的，针对不同的错误最好能提供不同的处理方式。以读值求和为例，如果读取值是非数字，会产生"类型错误"；即使是数字，若位数过长还会产生**整型溢出**的错误——这是另一种错误情况。仅仅用 `Option` 无法对非正常的情况做到很好的区分，因此需要更通用的抽象数据类型。
- **简单的 Either 版本**（5.2.2 节已实现过一个简陋版本）：`Either<A, B>` 表示**非 A 即 B**的值，从这个角度看，`Option` 也可以认为是一种特殊的 `Either`（只代表"是否存在"的关系）。由于 Either 更加通用，它需要接收两个类型变量：
  ```kotlin
  sealed class Either<A,B>() {
      class Left<A,B>(val value: A): Either<A,B>()
      class Right<A,B>(val value: B) : Either<A,B>()
  }
  ```
- **支持高阶类型的新版 Either**：旧版本没有支持高阶类型，因此写一个新版本。由于 Either 的特殊需求，需要定义 `Kind2<F, A, B>`（通过类型别名）：
  ```kotlin
  typealias Kind2<F, A, B> = Kind<Kind<F, A>, B>

  @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
  inline fun <A, B> Kind2<Either.K, A, B>.unwrap(): Either<A, B> =
      this as Either<A, B>

  sealed class Either<out A, out B> : Kind2<Either.K, A, B> {
      object K
  }
  data class Right<B>(val value: B) : Either<Nothing, B>()
  data class Left<A>(val value: A) : Either<A, Nothing>()
  ```
- **EitherMonad**：作为构建函数式通用结构的基本套路，给 `Either` 类型增加一个 `EitherMonad`。它比 `OptionMonad` 多了一个泛型参数 `C`（记录错误类型 `Left`）：
  ```kotlin
  class EitherMonad<C> : Monad<Kind<Either.K, C>> {
      override fun <A, B> Kind<Kind<Either.K, C>, A>.flatMap(f: (A) -> Kind<Kind<Either.K, C>, B>): Kind<Kind<Either.K, C>, B> {
          val eab = this
          return when (eab) {
              is Right -> f(eab.value)
              is Left -> eab
              else -> TODO()
          }
      }
      override fun <A> pure(a: A): Kind<Kind<Either.K, C>, A> {
          return Right(a)
      }
  }
  ```
  `flatMap` 中 `Right` 时继续应用 `f`，`Left` 时原样返回错误并短路。
- **EitherT 定义**：Either 也面临与 Option 一样的窘境——多组合场景时需要类似 OptionT 的版本，即 EitherT。采用与 OptionT 类似的改进思路：
  ```kotlin
  data class EitherT<F, L, A>(val value: Kind<F, Either<L, A>>) {
      companion object {
          fun <F, A, B> pure(AP: Applicative<F>): (B) -> EitherT<F, A, B> = { b ->
              EitherT(AP.pure(Right(b)))
          }
      }
      fun <B> flatMap(M: Monad<F>): ((A) -> EitherT<F, L, B>) -> EitherT<F, L, B> {
          val v = M.run {
              value.flatMap { ela ->
                  when (ela) {
                      is Left -> M.pure(Left(ela.value))
                      is Right -> f(ela.value).value
                  }
              }
          }
          return EitherT(v)
      }
      fun <B> map(F: Functor<F>): (((A) -> B) -> EitherT<F, L, B>) = { f ->
          val felb = F.run {
              value.map { ela ->
                  EitherMonad<L>().run {
                      ela.map(f).unwrap()
                  }
              }
          }
          EitherT(felb)
      }
  }
  ```
- **改写读值求和的例子**：结合之前的读值求和例子，可以很容易地将基于 Either 的例子改写为如下内容——`Left` 携带可读的错误信息（如 `"${it} is not a number"`），`valueOrF` 在出错时输出错误信息：
  ```kotlin
  fun errorHandleWithEitherT() {
      fun readInt(): EitherT<StdIO.K, String, Int> {
          val r = StdIOMonad.run {
              StdIO.read().map {
                  when {
                      it.matches(Regex("[0-9]+")) -> Right(it.toInt())
                      else -> Left("${it} is not a number")
                  }
              }
          }
          return EitherT(r)
      }
      val add = readInt().flatMap(StdIOMonad) { i ->
          readInt().flatMapF(StdIOMonad) { j ->
              StdIO.write((i + j).toString())
          }
      }
      add.valueOrF(StdIOMonad) { err ->
          StdIO.write(err)
      }
  }
  ```
- **收益**：使用 EitherT 之后，代码的可读性得到了极大改善——不仅能区分多种错误（用不同的 `Left` 值携带不同的错误信息），还能像 OptionT 一样消除嵌套、提前短路，同时保持类型安全与引用透明。

---

## 第10篇 异步与并发

**Q230: 同步与阻塞是一回事吗？二者的本质区别是什么？**

- **概念混淆的来源**："同步"与"阻塞"这两个概念经常会被放在一起，非常容易给人一种"同步即阻塞、阻塞即同步"的错觉。其实这两个概念并没有太强的联系。
- **同步描述的是行为（Behavior）**：当执行 IO 操作的时候，在代码层面我们需要主动去等待结果，直到结果返回，这种"主动等待结果返回"的调用方式就是同步调用。
- **阻塞描述的是状态（State）**：当执行 IO 操作的时候，线程处于挂起状态，即该线程没有在执行了，这种"线程被挂起、无事可做"的状态就是阻塞。同步与阻塞不同，同步也可以是非阻塞的，比如我们在执行同步代码块的时候，线程可以不被挂起而是一直在后台运行。
- **关键结论**：同步描述的是行为，阻塞描述的是状态（异步与阻塞、非阻塞的关系也是如此）。"异步"并不意味着"非阻塞"，两者是不同维度的概念，自由组合可以形成同步阻塞、同步非阻塞、异步阻塞、异步非阻塞四种情况。
- **从下单场景看同步阻塞的代码**：在线商城执行"下单减库存"操作时，服务端依次完成查询商品信息、整理订单信息、插入订单快照、减库存等操作。因为每次执行到 IO 操作时程序都要等待结果返回后才能继续，所以这种方式是同步阻塞的：
  ```java
  public void createOrder(String productNo){
      if(productNo == null || "".equals(productNo)){
          return;
      }
      //获取商品信息
      ProductInfo productInfo = getItemInfo(productNo);
      if(productInfo == null){
          return;
      }
      //整理订单信息
      OrderInfo orderInfo = convert2OrderInf(productInfo);
      //插入订单并减库存
      insertRecord(orderInfo);
      reduceStore(productNo);
  }
  ```

**Q231: 同步阻塞会带来怎样的代价？为什么会消耗大量资源？**

- **Tomcat 的线程模型**：Java Web 开发中最常采用的服务器是 Apache Tomcat，它采用的是多线程的工作方式。接收者线程（acceptor thread）接收客户端的 HTTP 请求，然后将这些请求分配给请求处理线程进行处理。当收到多个下单请求连接时，Tomcat 会为每一个连接分配一个线程去执行相应操作。
- **大量时间耗费在等待 IO 上**：解析请求、查询商品信息、插入快照和减库存的操作都是 IO 操作，相对较慢。在线程中每当执行到 IO 操作时，程序就会处于等待状态，同时该线程会处于挂起状态——该线程不能执行其他操作，必须等待相应结果返回之后才能继续执行。于是线程从开始执行到结束，有大部分时间都用在了等待上，极大地消耗了资源。
- **成倍增长的资源消耗**：Tomcat 这种多线程机制下，若每个线程都采用这种"挂起等待"的机制，消耗的资源将会成倍增加。
- **请求数量超过线程池上限的后果**：Tomcat 能分配的线程是有限的。一旦客户端发来的请求数远远大于 Tomcat 所能处理的最大线程数，没有得到处理的请求就会处于阻塞和等待状态，反映到用户层面就是页面迟迟得不到响应；如果等待时间过长，耐心的用户会看到请求超时的错误，而性子急的用户早早地就关掉了页面。
- **木桶原理**：一个系统的性能好坏，往往由最弱的那一环决定。如果在服务端进行阻塞调用时有大部分线程都处于挂起状态，即使程序在代码层面已经优化得非常好，也不能带来质的提升。

**Q232: 如何利用"异步非阻塞"来提高效率？**

- **异步的含义**：异步是区别于同步的。程序执行 IO 操作时，如果是同步代码块，程序会一直处于阻塞状态，必须等待该 IO 操作返回结果才能继续执行；如果采用异步的实现方式，那么当执行 IO 操作的时候程序可以不用等待，还可以继续执行其他代码块，比如执行其他异步的 IO 操作。当有结果返回的时候，程序再回来执行该代码块，这样就节省了许多资源。
- **代码层面的异步改造**：假设程序是多线程的，如果采用同步的实现方式，程序就会在某个线程上等待，并且其他线程也必须等待该线程的完成；采用异步的方式，当程序执行 IO 操作时，可以去执行其他线程的代码，不用在这里一直等着，当有结果返回时程序再回来执行该代码块。
- **服务端层面的非阻塞调用**：异步能解决一些性能上的问题，但并不能解决阻塞调用所带来的瓶颈。真正的突破是把服务端的阻塞调用改为**非阻塞调用**——当执行 IO 操作时，该线程并没有被挂起，仍处于执行状态，它还可以去执行其他代码，不用在这里等待而浪费大量的时间。
- **两层结合**：代码层用异步（不主动等待结果），服务端线程层用非阻塞（线程不挂起），两者结合才能让系统在并发量增大时依然保持高效的吞吐。这也是现代高性能框架（如 Netty、Node.js）的核心思想。

**Q233: 什么是回调地狱？它是如何产生的？**

- **回调的由来**：服务端线程的非阻塞调用已有比较成熟的方案，但在代码层面，我们往往使用**回调**来进行 IO 操作：发起一个异步任务，为其设置一个回调函数，任务完成后执行回调。当处理的逻辑比较复杂时，回调就会一层套着一层，最终出现常见的**回调地狱**。
- **下单逻辑的回调实现**：把下单逻辑改为异步方式，采用回调的写法就是层层嵌套的任务和回调：
  ```java
  public void createOrder(String productNo){
      //创建获取订单信息的任务
      GetOrderInfoTask task = new GetOrderInfoTask(productNo);
      //设置创建订单的回调
      task.setCallBack(new CreateOrderBack() {
          @Override
          public void createOrder(OrderInfo orderInfo) {
              //创建插入订单的任务
              InsertOrderTask insertOrderTask = new InsertOrderTask(orderInfo);
              //设置减库存的回调
              insertOrderTask.setReduceStoreBack(new ReduceStoreBack() {
                  @Override
                  public void reduceStore(String producerNo) {
                      reduceStore(orderInfo.getProducerNo());
                  }
              });
              threadPool.submit(insertOrderTask);
          }
      });
      //执行获取订单信息的任务
      threadPool.submit(task);
  }
  ```
  对应的任务类 `GetOrderInfoTask`、`InsertOrderTask` 分别实现 `Runnable`，在 `run()` 中完成自己的业务后通过 `callBack.createOrder(...)`、`reduceStoreBack.reduceStore(...)` 触发下一层回调。
- **问题所在**：上面的代码只执行了 3 步操作（查询商品信息 → 整理并插入订单 → 减库存），但代码却比同步实现复杂得多，而且不易维护。当嵌套的层级增多时，就会出现回调地狱。
- **普遍性**：在大部分语言中，处理异步的时候都会出现回调地狱。这也是后续 Kotlin 引入协程（Coroutine）来解决的核心痛点——避免在异步编程中使用大量的回调。

**Q234: 多线程一定优于单线程吗？为什么？**

- **多线程的初衷**：为了能在同一个程序（进程）内同时执行多个任务，我们引入了多线程。以商城为例，若某个时间段内有多人同时下单，只用单线程处理时一次只能处理一位用户的请求，后面的人必须等待，效率非常低下；引入多线程后可以同时处理多个用户的请求，从而提高效率。
- **"同时执行"是假象**：多线程在执行的时候只是看上去是同时执行的。线程的执行由 CPU 来调度，CPU 通过在每个线程之间快速切换，使得它们看上去像是同时执行的。实际上 CPU 在某个时间片内只能执行一个线程，当这个线程执行一会儿之后，它就会去执行其他线程。
- **线程切换的开销**：当 CPU 从一个线程切换到另一个线程时会执行许多操作，主要有两个：**保存当前线程的执行上下文**、**载入另外一个线程的执行上下文**。这种切换所产生的开销是不能忽视的。
- **线程阻塞加剧切换**：当线程池中的线程有许多被阻塞住了，CPU 就会将该线程挂起，去执行别的线程，从而产生线程切换。当切换很频繁的时候，就会消耗大量的资源在切换线程的操作上。
- **结论**：采用多线程的实现方式并不一定优于单线程。当线程过多、阻塞频繁导致上下文切换开销过大时，多线程反而可能不如单线程高效。这为协程（更轻量级的"线程"）的登场埋下伏笔。

**Q235: 什么是协程？为什么说它是更轻量级的"线程"？**

- **协程的历史与定义**：协程并不是一个新的概念，早在 1963 年就已被提出。协程是一个**无优先级的子程序调度组件**，允许子程序在特定的地方挂起恢复。线程包含于进程，协程包含于线程。只要内存足够，一个线程中可以有任意多个协程，但某一时刻只能有一个协程在运行，多个协程分享该线程分配到的计算机资源。
- **最简单的协程示例**：通过 `launch` 构造一个协程，其内部调用 `delay` 方法会挂起协程但不会阻塞线程，所以在协程延迟 1 秒的时间段内，主线程继续执行，先输出 "Hello,"，之后才输出 "World!"：
  ```kotlin
  import kotlinx.coroutines.experimental.*
  fun main(args: Array<String>) {
      GlobalScope.launch { // 在后台启动一个协程
          delay(1000L) // 延迟1秒（非阻塞）
          println("World!") // 延迟之后输出
      }
      println("Hello,") // 协程被延迟了1秒，但是主线程继续执行
      Thread.sleep(2000L) // 为了使JVM保活，阻塞主线程2秒钟
  }
  ```
- **轻量级的原因**：线程是由操作系统来进行调度的，操作系统切换线程时会产生一定的消耗（保存/载入上下文）。而协程包含于线程、工作在线程之上，**协程的切换可以由程序自己来控制，不需要操作系统去调度**，这样大大降低了开销。
- **数量级的对比**：启动 10 万个协程执行输出 "Hello" 的操作毫无压力，但若启动 10 万个线程去做同样的事，就可能会出现 `out of memory` 错误：
  ```kotlin
  import kotlinx.coroutines.experimental.*
  fun main(args: Array<String>) = runBlocking {
      repeat(100_000) {
          launch {
              println("Hello")
          }
      }
  }
  ```

**Q236: `launch` 与 `runBlocking` 有什么区别？如何合理地使用协程？**

- **初版示例的不合理之处**：最简单的协程示例中既使用了 `delay` 又使用了 `sleep`，但两者语义完全不同：`delay` 只能在协程内部使用，它用于**挂起协程**，不会阻塞线程；`sleep` 用来**阻塞线程**。混用这两个方法会让我们不容易弄清楚哪个是阻塞式的、哪个是非阻塞式的。
- **`runBlocking` 引入**：为了改良上述写法，用 `runBlocking` 将整个 `main` 函数包裹起来，不再需要使用 `sleep` 方法，全部改用非阻塞式的 `delay`，运行结果与之前一样：
  ```kotlin
  import kotlinx.coroutines.experimental.*
  fun main(args: Array<String>) = runBlocking {
      launch {
          delay(1000L)
          println("World!")
      }
      println("Hello,")
      delay(2000L)
  }
  ```
- **二者的区别**：`launch` 与 `runBlocking` 都会启动一个协程。不同的是，`runBlocking` 是最高级的协程，也就是**主协程**，`launch` 创建的协程能够在 `runBlocking` 中运行（反过来是不行的）。上面的代码可以看作在一个线程中创建了一个主协程，然后在主协程中创建了一个输出 "World!" 的子协程。
- **注意**：`runBlocking` 方法仍旧会**阻塞当前执行的线程**。它适合作为协程与普通代码之间的桥接入口，而不是用来做并发优化的工具。

**Q237: 协程的生命周期如何控制？`join` 与 `suspend` 关键字有什么作用？**

- **让程序保活的问题**：在之前的代码中，我们都在程序最后加上 `Thread.sleep(2000L)` 或 `delay(2000L)` 来让程序不要过早地结束——它们都表示"程序在这段时间内保活"。如果没有这两行代码，主线程没有被阻塞，程序会立即执行完而不会等待协程，只会输出 "Hello,"。
- **固定时间保活的局限**：在协程中执行 IO 操作（比如 `launch { search() }` 查询数据库）时，我们并不知道该操作要执行多久，所以没有办法设定一个合理的时间让程序一直保活。
- **使用 `join` 等待协程结束**：为了在协程执行完毕之前让程序一直保活，可以使用 `join`。`job.join()` 会让程序一直等待，直到我们启动的协程结束。注意这里的等待是**非阻塞式**的等待，不会将当前线程挂起：
  ```kotlin
  import kotlinx.coroutines.experimental.*
  fun main(args: Array<String>) = runBlocking {
      val job = launch {
          search()
      }
      println("Hello,")
      job.join()
  }
  suspend fun search() {
      delay(1000L)
      println("World!")
  }
  ```
- **`suspend` 关键字**：用 `suspend` 修饰的方法在协程内部使用的时候和普通方法没什么区别，不同的是在 `suspend` 修饰的方法内部还可以调用其他 `suspend` 方法。比如上面 `search` 方法中调用的 `delay` 就是一个 `suspend` 修饰的方法，这些方法只能在协程内部或者其他 `suspend` 方法中执行，不能直接在普通方法中执行。
- **超时控制**：有时也需要给程序定时，比如我们不需要某个 IO 操作执行很长时间，超过一定时间之后就报超时的错误，这种情况下就不必使用 `join`，而是使用协程的超时机制。

**Q238: 如何用同步的方式写异步代码？（`async` 与 `await` 的用法）**

- **协程内代码的执行顺序**：先看两段顺序执行的查询代码——两个 suspend 方法在协程内部其实是**顺序执行**的，先执行 `searchItemlOne()` 再执行 `searchItemTwo()`，这有点类似 11.1.1 节中 Java 实现的同步代码：
  ```kotlin
  suspend fun searchItemlOne(): String {
      delay(1000L)
      return "item-one"
  }
  suspend fun searchItemTwo(): String {
      delay(1000L)
      return "item-two"
  }
  fun main(args: Array<String>) = runBlocking<Unit> {
      val one = searchItemlOne()
      val two = searchItemTwo()
      println("The items is ${one} and ${two}")
  }
  ```
- **顺序执行并不合理**：这两个查询操作不会相互依赖，第二个查询操作不需要等第一个完成之后再执行，它们的关系应该是**并行**的。
- **使用 `async` 与 `await` 实现并行**：将两个查询操作用 `async` 包裹起来，类似于前面的 `launch`——使用 `async` 相当于创建了一个子协程，它会和其他子协程一样**并行工作**。与 `launch` 不同的是，`async` 会返回一个 **`Deferred` 对象**：`Deferred` 是一个非阻塞、可取消的 future，它是一个带有结果的 job（`launch` 也会返回一个 job 对象，但是没有返回值）。在输出时用 `await` 方法等待这个将来会返回的值查询到之后，再将其取出来：
  ```kotlin
  fun main(args: Array<String>) = runBlocking<Unit> {
      val one = async { searchItemlOne() }
      val two = async { searchItemTwo() }
      println("The items is ${one.await()} and ${two.await()}")
  }
  ```
- **执行时间的对比（效率提升近一半）**：不使用 `async` 与 `await` 时耗时约 2035 ms；使用后耗时约 1038 ms，执行时间几乎缩短了一半。
  ```kotlin
  val time = measureTimeMillis {
      val one = async { searchItemlOne() }
      val two = async { searchItemTwo() }
      println("The items is ${one.await()} and ${two.await()}")
  }
  println("Cost time is ${time} ms")
  ```
- **用同步风格写异步逻辑的好处**：查询操作都是异步的，必须等两个商品都被查出来之后才能输出信息。按照以前编写异步逻辑的方法还需要使用回调，而现在直接能用**同步风格的代码**来实现异步逻辑，不仅大大节省程序的执行时间，而且提高了代码的可读性与可维护性。
- **协程也不是万能的**：Kotlin 的协程目前还处于实验阶段，在非常重要的业务逻辑中使用它可能会出现一些未知的问题；另外，滥用协程可能会使代码变得更加复杂，不利于后期的维护。

**Q239: 为什么需要对共享资源加锁？Kotlin 中如何实现锁模式？**

- **共享资源与并发问题**：共享资源可以是共享变量，也可以是数据库中的数据等。一段代码块可以由多个线程执行，两个甚至多个线程同时对共享资源进行操作，可能导致共享资源不一致的问题。比如一个商品的库存在抢购活动中由于高并发可能出现**超卖**，所以需要对商品库存这种共享资源加锁，保证同一时刻只有一个线程能对其进行读写。
- **Java 的 `synchronized`**：Java 程序员最熟悉的加锁方式就是 `synchronized` 关键字，它可以作用在方法及代码块上。
- **Kotlin 没有 `synchronized` 关键字**：Kotlin 虽然基于 Java 改良而来，但它没有 `synchronized`，取而代之的是使用 **`@Synchronized` 注解**和 **`synchronized()`** 来实现等同的效果：
  ```kotlin
  class Shop {
      val goods = hashMapOf<Long,Int>()
      init {
          goods.put(1,10)
          goods.put(2,15)
      }
      @Synchronized fun buyGoods(id: Long) {
          val stock = goods.getValue(id)
          goods.put(id, stock - 1)
      }
      fun buyGoods2(id: Long) {
          synchronized(this) {
              val stock = goods.getValue(id)
              goods.put(id, stock - 1)
          }
      }
  }
  ```
- **`volatile` 也变成了注解**：Kotlin 同样支持 Java 中其他的并发原语，如 `volatile` 关键字、`java.util.concurrent.*` 下的并发工具。其中 `volatile` 关键字在 Kotlin 中变成了注解：
  ```kotlin
  @Volatile private var running = false
  ```
- **`synchronized` 的性能定位**：`synchronized` 在并发激烈的情况下不是很好的选择，但在实际开发中要根据具体场景设计方案——如果明知并发量不会很大，却一味追求所谓的高并发，最终只会导致复杂臃肿的设计及众多基本无用的代码。软件设计中有句名言："**过早的优化是万恶之源**"。在竞争不是很激烈的情况下，使用 `synchronized` 相对来说更简单，也更语义化。

**Q240: Kotlin 如何用 `Lock` 加锁？`withLock` 是怎么优化加锁代码的？**

- **用 `Lock` 改造 `buyGoods`**：在 Java 中除了 `synchronized`，还可以用 `Lock` 的方式加锁。在 Kotlin 中同样可以使用 `ReentrantLock`：
  ```kotlin
  var lock: Lock = ReentrantLock()
  fun buyGoods(id: Long) {
      lock.lock()
      try {
          val stock = goods.getValue(id)
          goods.put(id, stock - 1)
      } catch (ex: Exception) {
          println("[Exception] is ${ex}")
      } finally {
          lock.unlock()
      }
  }
  ```
- **这种写法的痛点**：① 若在同一个类内有多个同步方法，将会**竞争同一把锁**；② 加锁之后，编码人员**很容易忘记解锁**操作；③ 存在大量**重复的模板代码**。
- **用高阶函数提高抽象程度**：将"加锁、执行、解锁"的模板逻辑抽成一个高阶函数 `withLock`，它接收一个 `lock` 对象和一个 Lambda 表达式（即加锁后要执行的业务逻辑）。这样调用方就不需要关心加锁细节，只需传入一个 `lock` 对象即可：
  ```kotlin
  fun <T> withLock(lock: Lock, action: () -> T): T {
      lock.lock()
      try{
          return action()
      } catch (ex: Exception) {
          println("[Exception] is ${ex}")
      } finally {
          lock.unlock()
      }
  }

  fun buyGoods(id: Long) {
      val stock = goods.getValue(id)
      goods.put(id, stock - 1)
  }
  var lock: Lock = ReentrantLock()
  withLock(lock, {buyGoods(1)})
  ```
- **Kotlin 标准库已内置**：Kotlin 类库中提供了相应的方法，直接对 `Lock` 调用 `withLock` 即可，比自定义的高阶函数更简洁：
  ```kotlin
  var lock: Lock = ReentrantLock()
  lock.withLock {buyGoods(1)}
  ```

**Q241: 多个商家卖货时，全局一把锁还合理吗？如何实现"锁分离"？**

- **场景升级**：前面是一个商家卖货，现在是多个商家卖货，所有顾客购买时都会调用 `buyGoods` 方法。如果所有商家的库存都共用同一把锁，那从 A 商家购买衣服和从 B 商家购买鞋子就无法同时进行，因为同一时刻只能被一个线程调用，导致锁竞争激烈、线程堵塞直至程序崩溃。
- **核心思路**：不同商家之间的商品库存并不会产生并发冲突。解决问题的核心在于**对并发时最会发生冲突的部分进行加锁**——为具体商家的 `buyGoods` 加锁，实现业务锁分离：
  ```kotlin
  class Shop (private var goods: HashMap<Long, Int>) {
      private val lock: Lock = ReentrantLock()
      fun buyGoods(id: Long) {
          lock.withLock {
              val stock = goods.getValue(id)
              goods.put(id, stock - 1)
          }
      }
  }

  class ShopApi {
      private val A_goods = hashMapOf<Long, Int>()
      private val B_goods = hashMapOf<Long, Int>()
      private var shopA: Shop
      private var shopB: Shop
      init {
          A_goods.put(1, 10)
          A_goods.put(2, 15)
          B_goods.put(1, 20)
          B_goods.put(2, 10)
          shopA = Shop(A_goods)
          shopB = Shop(B_goods)
      }
      fun buyGoods(shopName: String, id: Long) {
          when (shopName) {
              "A" -> shopA.buyGoods(id) //不同商家使用不同的model处理
              "B" -> shopB.buyGoods(id)
              else -> { }
          }
      }
  }

  val shopApi = ShopApi()
  shopApi.buyGoods("A", 1)
  shopApi.buyGoods("B", 2)
  ```
- **该方案的局限**：实现起来花费很大功夫，需要初始化多个 `Shop`；如果要在运行时初始化，还要考虑初始化的线程安全问题；若有成千上万个商家，用 `when` 来匹配可能是一个灾难；而且这种方式无法支持异步。
- **从改善思路中提炼出的模型要求**：① 独立的一个单元，可以有状态，可以处理逻辑（如上文的 `Shop` 类）；② 每个单元有独特的标识，且系统中最多只能有一个实例；③ 每个单元可以顺序地处理逻辑，不会有并发问题（方法同步是一种方案，线程安全的消息队列也是一种方案）；④ 最好能支持异步操作，处理成功后可以有返回值。这些要求正是 **Actor 模型**的雏形。

**Q242: 什么是 Actor？它要解决什么问题？**

- **Actor 的历史**：Actor 概念已经存在很久了，由 Carl Hewitt、Peter Bishop 及 Richard Steiger 在 1973 年的论文中提出，但直到这种概念在 Erlang 中应用后，才逐渐被大家所熟知。现在 Actor 模型已被应用到生产环境中，比如 **Akka**（一个基于 Actor 模型的并发框架）；Scala、Java 包括 Kotlin 也都支持 Actor 模型（Kotlin 内置的 Actor 在协程库中，目前仍是实验版）。
- **Actor 模型要做的事情**：① 用另一种思维来解决并发问题，而不是只有共享内存这一种方式；② 提高锁抽象的程度，尽量不在业务中出现锁，减少因为使用锁出现的问题（比如死锁）；③ 为解决分布式并发问题提供一种更好的思路。
- **邮政系统的比喻**：假定现实中的两个人只知道对方的地址，想要交流、给对方传递信息，但又没有手机、电话、网络之类的途径，所以他们之间只能用信件传递消息——很像现实中的邮政系统：你要寄一封信，只需根据地址把信投寄到相应的信箱中，具体它是如何帮你处理送达的，你就不需要了解了。你也有可能收到收信人的回复，这相当于消息反馈。上述例子中的信件相当于 Actor 中的消息，**Actor 与 Actor 之间只能通过消息通信**。
- **Actor 与并发的关系**：使用 Actor 模式，不同人之间的邮件投递可以并行处理，反映到应用中就是可以利用多核处理器。另外，信件信息是不可变的——你不能在发出这封信后又去修改它的内容；同时接收信件的人是从它的信箱里**有序地**处理信件。这两点就可以保证消息的一致性，不再需要使用共享内存。顺序地处理消息，Actor 内部的状态将不会有线程安全问题。
- **思想总结**：使用 Actor 这种方案并不一定就会比其他方案在并发性能上表现得更加优异，每种场景都有最适合自己的方案。Actor 模型这种思想简单概括就是**分而治之**——把一个大任务分解成一个个独立的小任务，依靠多核处理器以及多线程来达到整体的最优。

**Q243: 如何使用 Akka 框架实现基于 Actor 的购物系统？**

- **为何用 Akka**：Kotlin 内置的 Actor 功能并不是很完善，而且目前只是实验版，没有加入正式的 Kotlin 标准库，所以书中使用成熟的基于 Actor 的框架——**Akka**。Akka 同时支持 Scala 和 Java，Kotlin 百分之百兼容 Java，所以 Akka 也可以在 Kotlin 中使用。
- **引入依赖**：使用 Akka 需要引入相关的依赖包，用 Maven 或 Gradle 都可以，暂时只需要引入核心的 `akka-actor` 包；由于 Akka 是使用 Scala 编写的，所以还需要引入 Scala 的核心包：
  ```gradle
  compile 'com.typesafe.akka:akka-actor_2.12:2.5.14'
  compile 'org.scala-lang:scala-library:2.12.4'
  ```
- **实现 `ShopActor`**：`ShopActor` 内部有两个状态 `stocks` 和 `orderNumber`，分别代表库存和订单号；定义了 `sealed class Action` 来表示用户请求行为；`onReceive` 方法根据用户的不同请求（买商品、查库存）做不同的处理，并通过 `sender.tell` 返回结果：
  ```kotlin
  class ShopActor(val stocks: HashMap<Long, Int>) : UntypedAbstractActor() {
      var orderNumber = 1L
      override fun onReceive(message: Any?) {
          when (message) {
              is Action.Buy -> {
                  val stock = stocks.getValue(message.id)
                  if (stock > message.amount) {
                      stocks.plus(Pair(message.id, stock - message.amount))
                      sender.tell(orderNumber, self)
                      orderNumber++
                  } else {
                      sender.tell("low stocks", self)
                  }
              }
              is Action.GetStock -> {
                  sender.tell(stocks.get(message.id), self)
              }
          }
      }
  }

  sealed class Action {
      data class BuyOrInit(val id: Long, val userId: Long, val amount: Long,
                           val shopName: String, val stocks: Map<Long, Int>) : Action()
      data class Buy(val id: Long, val userId: Long, val amount: Long) : Action()
      data class GetStock(val id: Long) : Action()
      data class GetStockOrInit(val id: Long, val shopName: String,
                                val stocks: Map<Long, Int>) : Action()
  }
  ```
- **实现 `ManageActor` 管理与初始化 `ShopActor`**：
  ```kotlin
  class ManageActor : UntypedAbstractActor() { //管理和初始化ShopActor
      override fun onReceive(message: Any?) {
          when (message) {
              is Action.BuyOrInit -> getOrInit(message.shopName, message.stocks)
              is Action.GetStockOrInit -> getOrInit(message.shopName, message.stocks)
          }
      }
      fun getOrInit(shopName: String, stocks: Map<Long, Int>): ActorRef {
          return context.findChild("shop-actor-${shopName}").orElseGet {
              context.actorOf(Props.create(ShopActor::class.java, stocks), "shop-actor-${shopName}")
          }
      }
  }
  ```
- **使用方案**：初始化 Actor 系统，创建 `ManageActor`，通过 `Patterns.ask` 模拟"读取库存"和"购买商品"两个用户操作，再用 `Await.result` 等待返回结果：
  ```kotlin
  fun main(args: Array<String>) {
      val stocksA = hashMapOf(Pair(1L, 10), Pair(2L, 5), Pair(3L, 20))
      val stocksB = hashMapOf(Pair(1L, 15), Pair(2L, 8), Pair(3L, 30))
      val actorSystem = ActorSystem.apply("shop-system") //初始化Actor系统
      val manageActor = actorSystem.actorOf(Props.create(ManageActor::class.java), "manage-actor")
      val timeout = Timeout(Duration.create(3, "seconds"))
      val resA = Patterns.ask(manageActor, Action.GetStockOrInit(1L, "A", stocksA), timeout)
      val stock = Await.result(resA, timeout.duration())
      println("the stock is ${stock}")

      val resB = Patterns.ask(manageActor, Action.BuyOrInit(2L, 1L, 1, "B", stocksB), timeout)
      val orderNumber = Await.result(resB, timeout.duration())
      println("the orderNumber is ${orderNumber}")
  }
  // result:
  // the stock is 10
  // the orderNumber is 1
  ```
- **设计思想**：将一个个行为分解成合适的单位来进行处理，这就是 Actor 这种设计背后的思想——分而治之。

**Q244: Actor 是如何保证共享资源的正确性的？MailBox 在其中起什么作用？**

- **共享内存设计理念的差异**：Akka 中 Actor 的共享内存设计理念与传统方式不同。Actor 模型提倡的是：**通过通信来实现共享内存，而不是用共享内存来实现通信**。在 Java 中，每个线程操作共享内存中的数据时，都需要不断地获取共享内存的监视器锁，然后将操作后的数据暴露给其他线程访问使用——是用共享内存来实现线程之间的通信；而在 Akka 中，可以将共享可变的变量作为一个 Actor 内部的状态，利用 Actor 模型本身**串行处理消息**的机制来保证变量的一致性。
- **必须满足的两条原则**：
  - **消息的发送必须先于消息的接收**；
  - **同一个 Actor 对一条消息的处理先于对下一条消息的处理**。
  第二条原则很好理解，就是 Actor 内部串行处理消息，因此在 Actor 内部不会出现并发问题。
- **MailBox 的结构**：每个 Actor 都有一个属于自己的 MailBox，可以理解为存放消息的队列。比如一下子向一个 Actor 发送了几十万条消息，Actor 会将消息先存储在 MailBox 中，然后依次进行处理。
- **为什么必须保证"发送先于接收"**：因为这里存在两个操作——向 MailBox 中写入消息、从 MailBox 中读取消息，它们不是一个原子操作。如果一条消息在被写入 MailBox 还没结束的时候就被 Actor 读取走了，就可能出现一些未知的情况。所以消息必须先完整地写入 MailBox 才能被接收处理，这意味着 MailBox 必须是线程安全的。
- **MailBox 的实现方式**：MailBox 是一个存储消息的队列，消息只会添加在队列的尾部，取消息是在队列的头部。可以使用 `LinkedBlockingDeque` 作为 MailBox 的基础结构，它是基于双向链表实现的，也是线程安全的（内部仍使用 Lock 保证线程安全）；`ConcurrentLinkedQueue` 内部则使用 CAS 操作保证线程安全。但 Akka 并没有采用这两种方案，而是自己实现了一个 `AbstractNodeQueue`——一个功能更明确、专门为 Actor 这种需求设计的队列。

**Q245: 为什么说 Actor 方案还缺少"数据持久化"？并发瓶颈为什么最终指向数据库？**

- **内存数据不可靠**：前面例子中用 Map 来存储数据是有问题的，因为它是存储在内存中的，一旦系统宕机或程序崩溃，数据就会丢失，无法在生产环境中使用，所以需要把数据持久化，比如存储到数据库中。
- **回到单表竞争的尴尬**：原本我们在逻辑上已经把业务分解了，如果最后又回归到数据库单个表的竞争，那前面所有的花费都是徒劳。一般情况下，在系统优化得当的时候，并发的瓶颈就在于数据库，主要有两方面：① 数据库的连接和关闭、网络传输需要一定时间；② 一些不恰当的或者需要锁表的事务 SQL，如果大量执行会导致数据库执行变慢，甚至崩溃。
- **CRUD 模式在高并发下的困境**：以往的设计都是将对象的状态实时更新到数据库中，比如商品被购买一件后就修改数据库里相应的库存数量，而且还需要经常去读取库存，这就是通常所说的 **CRUD 模式**。这种模式很好理解，在并发不激烈时不会有什么问题；但并发激烈时，频繁的锁表事务操作不仅会让 SQL 执行变慢、失败，还会影响整个系统的吞吐量，甚至引起系统崩溃。
- **读写分离思路的局限**：面对这种情况，最容易想到的是采用主从数据库这种读写分离方案，但它依然有两个问题：一来避免不了修改库存时候的并发竞争，二来数据同步也需要大量的消耗。于是引入另一种方式——**CQRS（Command Query Responsibility Segregation）架构**。

**Q246: 什么是 CQRS 架构？它与普通的读写分离有何区别？**

- **CQRS 的定义**：CQRS 是一种命令与查询职责分离的设计原则，简单来说也是一种**读写分离**的设计方案。它与普通方式的读写分离有一些区别，主要体现在**写**方面——它不再是对记录进行不断修改，而是一种**事件溯源（Event Sourcing）**的思维方式。
- **与 binlog 的类比**：它跟数据库备份所使用的 binlog 方案很像：数据库会将有修改状态行为的 SQL 执行情况一条一条地记录在 binlog 日志中，利用这些记录便能推导出最终的数据库状态。CQRS 正是采用这种"记录行为、推导状态"的思维方式。
- **它解决的并发问题**：并发最大的困难就在于对共享资源的竞争。前面我们试着将竞争的部分分解到合适的单位（Actor），但某个具体单位的竞争还是可能激烈，所以从业务角度进行优化——将修改和查询分开。通过引入 CQRS 架构、结合领域驱动设计，可以将持久数据和视图数据分开存储：视图数据可以存在内存数据中，提高查询效率；通过保存所有的状态更改事件使内存中的数据是可靠的——比如减少库存数量时，不必查询数据库中的数据，直接使用内存中的数据即可。同时，因为事件是不断被添加而且不能修改的，所以可以选取写效率高的 DB（如 Cassandra）来存储事件。通过这些优化将提升程序的性能。

**Q247: 什么是 Event Sourcing 事件溯源？为什么说记录对象操作轨迹很重要？**

- **原理**：事件溯源就是**根据一系列事件推导出对象的最新状态**。举个简单的例子：假如你购买一件商品，商品的库存应该减一；但你突然又不想买了，进行了退货，这时商品库存又要加一。一来一回商品的库存并没有发生变化，按照普通的方式你会对数据库中的库存进行状态的修改，但这种方式要是不借助其他记录，我们将无法知晓在该对象上发生了什么事。所以比较合理的做法是**记录每次发生在该对象上的状态变更事件**，根据这些事件来推导出对象的最新状态，这便是事件溯源。
- **事件溯源最关键的几点**：
  - **以事件为驱动**：任何的用户行为都是一种事件，比如购买商品、退货等；
  - **存储所有对对象状态会有影响的事件**：这一点至关重要，因为程序恢复或者数据校验的时候都需要它；
  - **用于查询或者显示的视图数据不一定要持久化**：比如我们可以将对象的状态数据存放在内存中。
- **用 Kotlin 的 sealed class 表达事件**：在第 1 点上，前面 Actor 的例子中已经这么做了——将事件行为都声明在 `Action` 类中，通过这种方式就可以将业务行为分成各种事件。比如现在定义一个退货事件：
  ```kotlin
  sealed class Action {
      data class Return(val id: Long, val userId: Long, val amount: Long) : Action()
  }
  ```
- **事件驱动的收益**：利用事件驱动的方式构建业务逻辑，不仅语义上更加清晰，同时还**天然支持异步操作**。使用异步架构可以较为容易地提升程序的吞吐量。
- **与 Actor 的完美结合**：CQRS 以及 Event Sourcing 中最重要的两部分就是事件与聚合的划分：事件可以用 Kotlin 的 **Data class** 来实现，而将**一个 Actor 看成聚合**更是一个完美的应用——每个 Actor 维护自身的状态，既简洁又高效。

**Q248: 什么是聚合与聚合根？为什么事件溯源要依赖它们？**

- **聚合（Aggregate）**：聚合顾名词义，是一系列对象的集合。比如一个商家里面有商品、优惠券等，它们的集合就可以看作一个聚合。
- **聚合根（Aggregate Root）**：聚合根属于这个聚合中**可以被外部访问的元素**。比如商家就是一个聚合根，经过它我们才能查看它其中的商品、优惠券等。
- **为什么理解聚合与聚合根很重要**：要结合 CQRS、事件溯源这些设计，我们就要用一种新的思维模式去设计业务——**只能通过聚合根来操作聚合中其他对象的状态**，比如只能通过商家去修改商品的库存，而不允许直接修改库存。原来你可能直接在数据库中更改一下商品的库存就可以了，而现在你需要向商家发一条修改商品库存的信息，然后它会生成一个库存修改事件，最后才会修改好库存。
- **这种"绕一圈"的做法有什么益处**：以传统方式试想一下——假设商家修改了商品库存，但后来发现修改错了：一来可能忘记了修改的内容而无法回滚；二来即使可以回滚，付出的代价也是极大的，因为它需要回滚所有与商品库存有关的操作（数据回滚在现实中依然存在，比如银行、交易所的业务）。而如果通过聚合根来修改聚合中对象的状态，我们会记录聚合所有的状态更改事件，可以根据这些事件**恢复到任一时刻聚合的状态**，一切将会变得容易。
- **缺点与对策**：因为需要保存每次修改状态的事件，将会占用大量的存储空间；而且在状态恢复时需要回放以前所有的事件，也会有一定的消耗。这个问题可以通过**引入快照（Snapshot）**解决。

**Q249: 什么是 PersistenceActor？如何使用它实现 CQRS 架构？**

- **动机**：前面实现的 Actor 例子应用了各种领域事件（购物事件、查询库存事件），但并没有持久化任何 Actor 状态更改事件。假如程序出错甚至崩溃，我们将无法恢复 Actor 的状态，数据将会出错，因此有必要建立持久化状态更改事件的机制。Akka 为此提供了简单又高效的方式——**PersistenceActor**。顾名思义，PersistenceActor 就是支持持久化的 Actor，它的状态是可靠的。
- **与普通 Actor 的区别**：使用 PersistenceActor 需要继承 `AbstractPersistentActor` 类，必须实现以下几个关键方法：
  ```kotlin
  fun persistenceId()
  fun createReceiveRecover()
  fun createReceive()
  ```
  - `createReceive` 方法与前面例子中 Actor 的 `onReceive` 类似，都是用来接收处理消息的，只是语法上有差别；
  - 与普通 Actor 关键的差别在于多了 `persistenceId` 和 `createReceiveRecover` 方法。
- **`persistenceId` 是聚合标识**：在 CQRS 架构的设计中，划分一个聚合是关键步骤，而在这里每一个 Actor 都是一个聚合，它必须要有一个聚合标识，这便是 `persistenceId` 的用处。每个 Actor 的 `persistenceId` 都要不同，这样才能标识持久化的事件到底属于哪个聚合，对 Actor 的状态恢复起到至关重要的作用。
- **`createReceiveRecover` 用于状态恢复**：Actor 的状态恢复是通过事件回放实现的——`createReceiveRecover` 方法会在每次 Actor 重新启动的时候执行回放事件，恢复 Actor 的内部状态。
- **添加依赖与配置**：使用 PersistenceActor 需要添加相应依赖，并配置 `application.conf` 设置持久化事件的存储方式（这里用 Akka 默认提供的 leveldb，也可以用 Cassandra、Redis、MySQL 等，一般推荐写性能较好的 DB，因为它的基本需求就是写入事件）以及序列化方式（这里用 kryo 序列化来减小存储事件的体积，因为存储的事件将会非常多）：
  ```gradle
  compile group: 'com.typesafe.akka', name: 'akka-persistence_2.12', version: '2.5.14'
  compile group: 'org.iq80.leveldb', name: 'leveldb', version: '0.10'
  compile group: 'com.twitter', name: 'chill-akka_2.12', version: '0.9.2'
  ```
  ```conf
  akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
  akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
  akka.persistence.journal.leveldb.dir = "log/journal"
  akka.persistence.snapshot-store.local.dir = "log/snapshots"
  akka.actor.serializers {
      kryo = "com.twitter.chill.akka.AkkaSerializer"
  }
  akka.actor.serialization-bindings {
      "scala.Product" = kryo
      "akka.persistence.PersistentRepr" = kryo
  }
  ```
- **基于 PersistenceActor 的购物例子**：`ShopStateActor` 继承 `AbstractPersistentActor`，`persistenceId` 返回 `"ShopStateActor-${shopName}"` 作为聚合标识：
  ```kotlin
  class ShopStateActor(val shopName: String, var stocks: HashMap<Long, Int>)
      : AbstractPersistentActor() {
      override fun persistenceId() = "ShopStateActor-${shopName}"
      var orderNumber = 1L

      override fun createReceiveRecover(): Receive = receiveBuilder()
          .match(ShopEvt.ReduceStock::class.java, this::recoverReduceStock)
          .build()

      fun recoverReduceStock(evt: ShopEvt.ReduceStock) {
          val stock = stocks.getValue(evt.id)
          stocks.plus(Pair(evt.id, stock - evt.amount))
          orderNumber = evt.orderNumber
          orderNumber++
          //self.tell(viewData, viewActor) 视图数据发送给viewActor用于查询
      }

      override fun createReceive(): Receive = receiveBuilder()
          .match(Action.Buy::class.java, this::buyProcess)
          .build()

      fun buyProcess(cmd: Action.Buy) {
          val stock = stocks.getValue(cmd.id)
          if (stock > cmd.amount) {
              persist(ShopEvt.ReduceStock(cmd.id, cmd.userId, cmd.amount, orderNumber)) {
                  persistReduceStockAfter(it)
              }
          } else {
              sender.tell("low stocks", self)
          }
      }
      fun persistReduceStockAfter(evt: ShopEvt.ReduceStock) {
          val stock = stocks.getValue(evt.id)
          orderNumber++
          stocks.plus(Pair(evt.id, stock - evt.amount))
          sender.tell(orderNumber, self)
          //self.tell(viewData, viewActor) 视图数据发送给viewActor用于查询
      }
  }

  sealed class ShopEvt {
      object Snapshot : ShopEvt()
      data class SnapshotData(val orderNumber: Long, val stocks: Map<Long, Int>) : ShopEvt()
      data class ReduceStock(val id: Long, val userId: Long, val amount: Long,
                             val orderNumber: Long) : ShopEvt()
      data class AddStock(val id: Long, val amount: Long) : ShopEvt()
  }
  ```
- **两个关键步骤的解读**：
  - **持久化事件**：`buyProcess` 是 Event Sourcing 的关键部分，它存储了改变 Actor 状态的所有事件，比如这里的 `ReduceStock` 事件。`PersistentActor` 中的 `persist` 提供了持久化事件成功后的回调，我们可以在回调中修改 Actor 的状态、向其他 Actor 发送消息，或者存储视图数据等操作。
  - **回放事件恢复状态**：`createReceiveRecover` 在 Actor 重启时回放所有持久化的事件，然后根据这些事件来恢复 Actor 关闭或者出错时的状态。

**Q250: PersistenceActor 如何利用快照加速状态恢复？批量持久化 `persistAll` 有什么用？**

- **事件回放的性能问题**：Actor 恢复的时候需要回放大量的历史事件，导致恢复时间过长。为了解决这个问题，可以引入 **Actor 快照存储**的方式——每隔一段时间发送 `ShopEvt.Snapshot` 消息要求 Actor 进行快照保存：
  ```kotlin
  fun saveSnapshot() {
      saveSnapshot(ShopEvt.SnapshotData(orderNumber, stocks))
  }
  override fun createReceive(): Receive = receiveBuilder()
      .match(Action.Buy::class.java, this::buyProcess)
      .match(ShopEvt.Snapshot::class.java, this::saveSnapshot)
      .build()
  ```
- **用快照恢复状态**：有了快照保存之后，便可以利用快照来恢复 Actor 的状态。Actor 恢复的时候会**优先选用快照恢复**，然后再利用事件恢复，从而大大减少 Actor 重启恢复状态时的消耗：
  ```kotlin
  override fun createReceiveRecover(): Receive = receiveBuilder()
      .match(ShopEvt.SnapshotData::class.java, this::recoverSnapshotData)
      .build()
  fun recoverSnapshotData(evt: ShopEvt.SnapshotData) {
      stocks = evt.stocks
      orderNumber = evt.orderNumber
  }
  ```
- **关于查询部分的实现**：实现查询的方案有很多种，比如将需要的查询数据发送给另一个 Actor，或者将数据存储在读效率高的 DB 中，也可以使用 Akka 自身提供的 `akka-persistence-query`。
- **按场景设计，不要盲目使用**：使用 Event Sourcing 和 CQRS 架构设计系统时，一定要根据具体场景来设计，比如系统是写要求高还是读要求高。假设我们对写入的要求很高，如上例中一次事件执行一次写入，即使真正写入 DB 的时间非常短，但每次网络通信的消耗也非常大，这时就可以利用**批量存储**这种方式来改进。`PersistentActor` 也提供了这种方式——`persistAll`，通过它我们可以批量地持久化事件：
  ```kotlin
  persistAll(listOf(event1, event2, ...), processAfterPersist)
  ```
- **批量持久化的注意点**：使用批量持久化后，逻辑会变得稍微复杂一点，比如在批处理的时候减库存就不能只依靠上面那种方式，因为被减少的库存并没有真正持久化到 DB 中。可以通过引入一个**临时变量**来解决这个问题；因为 Actor 是串行处理的，所以不必担心这个变量会有线程安全问题。
- **回顾整体思路**：从最简单也最熟悉的业务加锁开始——从整个方法加锁到局部加锁，学习了利用 Kotlin 的简洁语法优化加锁代码，并引出 Actor 模型（有状态的并行计算单元），利用 Actor 实现业务上的无锁并发；接着在 Actor 的基础上，介绍了 CQRS 架构以及 Event Sourcing 的思维方式；最后利用 Akka 的 PersistentActor 实现了最终的版本。整个过程中不断地面对问题，然后思考用好的方案去解决它。

---

## 第11篇 基于 Kotlin 的 Android 架构

**Q251: 为什么移动端需要架构？它要解决怎样的工程痛点？**

- **移动端早期的"伪需求"**：在移动端发展早期，我们通常会提及 App 的架构，此时总有些大材小用的感觉，因为移动端并没有复杂的业务处理、高并发等场景，甚至我们需要的只是简单地"将数据展示在屏幕上"。
- **随着移动端飞速发展产生的问题**：
  - 移动端 App 中业务逻辑越来越复杂，用户渴望更好的体验及更新颖的功能；
  - 不断地迭代让项目结构复杂化，维护成本越来越高。
- **架构的核心目的**：我们需要一个良好的架构模式，**拆分视图和数据，解除模块之间的耦合，提高模块内部的聚合度**，让系统更稳健。本章谈论的架构，即是对客户端的**代码组织/职责**进行的划分。
- **本章主线**：以传统的 MVC 及当下流行的 MVP、MVVM 架构为例，展现 Kotlin 在实现这些架构时的魅力；同时介绍一种比较新颖的事物——**基于单向数据流的 Android 架构**，并基于一个名为 ReKotlin 的开源项目来实现一个完整的 Android 架构。

**Q252: 什么是 MVC 架构？三个角色各承担什么职责？（12.1.1）**

- **起源**：Android 架构的鼻祖，自然是经典的 MVC 了。在用户界面比业务逻辑更容易发生变化的时候，客户端和后端开发需要一种分离用户界面功能的方式，这时候，MVC 模式应运而生。MVC 对应 Model、View、Controller。
- **Model（数据层）**：负责管理业务逻辑和处理网络或数据库 API。
- **View（视图层）**：让数据层的数据可视化。在 Android 中对应**用户交互、UI 绘制**等。
- **Controller（逻辑层）**：获得用户行为的通知，并根据需要更新 Model。
- **对 Model 的常见误解**：很多人对于经典 MVC 架构中的 Model 一直存在误解，认为其代表的只是一个实体模型。其实，准确来说它**还应该包含大量的业务逻辑处理**。相对而言，Controller 只是在 View 和 Model 层之间建立一个桥梁而已。
- **三层结构细分**：
  - **Model 层**：数据访问（数据库、文件、网络等）、缓存（图片、文件等）、配置文件（shared preference）等；
  - **View 层**：数据展示与管理、用户交互、UI 组件的绘制、列表 Adapter 等；
  - **Controller 层**：初始化配置（定义全局变量等）、数据加工（加工成 UI 层需要的数据）、数据变化的通知机制等。

**Q253: 在 Android 中 Activity 到底应该归入哪一层？为什么？**

- **历史现状**：当你在 Stack Overflow 中搜索类似"如何在 Android 应用中使用 Activity"的问题时，你会发最高频的答案就是：**一个 Activity 既是 View 又是 Controller**。
- **背后的妥协**：这看起来好像对新手非常不友好，但是当时解决的**重点问题是使 Model 可测试**。这导致很多开发者在项目结构中出现了很多 Free Style 的代码，使得 Activity 中代码量庞大并且难以维护。
- **经验结论**：经过大量时间与项目的验证，我们更加明确：**Activities、Fragments 和 Views 都应该被划分到 MVC 的 View 层中，而不是 Controller 或 Model 中**。也就是说，Activity/Fragment 只负责展示数据与接收用户交互，具体的业务逻辑应该交给独立出来的层去处理。

**Q254: MVC 架构的优势有哪些？**

- **Model 层可单元测试**：Model 类没有对 Android 类的任何引用，因此可以直接进行单元测试。
- **Controller 层可单元测试**：Controller 不会扩展或实现任何 Android 类，并且应该引用 View 的接口类。通过这种方式，也可以对控制器进行单元测试。
- **View 层遵循单一职责原则**：如果 View 遵循单一职责原则，那么它们的角色就是为每个用户事件更新 Controller，只显示 Model 中的数据，而**不实现任何业务逻辑**。在这种理想的作用（理想情形）下，UI 测试应该足以覆盖所有的 View 的功能。
- **总结**：MVC 模式高度支持职责的分离。这种优势不仅增加了代码的可测试性，而且使其更容易扩展，从而可以相当容易地实现新功能。

**Q255: 经典 MVC 容易产生哪些问题？（Android 中 MVC 的痛点）**

- **代码相对冗余**：MVC 模式中 View 对 Model 是有着强依赖的。当 View 非常复杂的时候，为了最小化 View 中的逻辑，Model 应该能够为要显示的每个视图提供可测试的方法——这将增加大量的类和方法。
- **灵活性较低**：由于 View 依赖于 Controller 和 Model，UI 逻辑中的一个更改可能导致需要修改很多类，这降低了灵活性，并且导致 UI 难以测试。
- **可维护性低**：Android 的视图组件中，有着非常明显的生命周期，如 Activity、Fragment 等。对于 MVC 模式，我们有时不得不将处理视图逻辑的代码都写在这些组件中，造成它们十分臃肿。
- **结论**：Android 中最初的 MVC 架构问题显而易见：**过于臃肿的 Controller 层大大降低了工程的可维护性及可测试性**。

**Q256: 什么是 MVP？它相对于 MVC 的核心改进是什么？（12.1.2）**

- **定义**：直到 MVP 架构模式的出现，传统 MVC 架构才从真正意义上得到解脱。MVP 分别对应 Model、View、Presenter。
- **Model（数据层）**：负责管理业务逻辑和处理网络或数据库 API。
- **View（视图层）**：显示数据并将用户操作的信息通知给 Presenter。
- **Presenter（逻辑层）**：从 Model 中检索数据，应用 UI 逻辑并管理 View 的状态，决定显示什么，以及对 View 的事件做出响应。
- **核心改进（为什么引入 Presenter）**：相对于 MVC，MVP 模式设计思路的核心是**提出了 Presenter 层**，它是 View 层与 Model 层沟通的桥梁，对业务逻辑进行处理。这更符合了我们理想中的单一职责原则。
- **数据流方向**：View 不再直接依赖 Model，而是"用户操作 View → View 通知 Presenter → Presenter 从 Model 取数据并处理 → 驱动 View 更新"，形成了一条清晰的职责链。

**Q257: 传统 MVP 中 Model 层是如何设计的？（以 todo-app 获取任务列表为例）**

- **背景**：Android 架构蓝图中的 todo-app 允许用户创建、读取、更新和删除"待办事项"任务，以及对任务列表进行分类显示。
- **双数据源设计**：处理 Model 的时候，一般都会使用**远程和本地数据源**来获取和保存数据。以获取待办事项列表为例：当请求列表数据时，Model 优先尝试从本地获取，如果为空，则查询网络更新本地数据并返回。
  ```kotlin
  fun getTasks(callback: TasksDataSource.LoadTasksCallback) {
      // 如果本地有缓存并且缓存正常，则直接返回缓存
      if (cachedTasks.isNotEmpty() && !cacheIsDirty) {
          callback.onTasksLoaded(ArrayList(cachedTasks.values))
          return
      }
      if (cacheIsDirty) {
          // 如果缓存过期或被污染，则需要从服务端获取最新的数据
          getTasksFromRemoteDataSource(callback)
      } else {
          // 如果本地存在缓存数据则从本地获取，否则从服务端获取
          tasksLocalDataSource.getTasks(object : TasksDataSource.LoadTasksCallback {
              override fun onTasksLoaded(tasks: List<Task>) {
                  refreshCache(tasks)
                  callback.onTasksLoaded(ArrayList(cachedTasks.values))
              }
              override fun onDataNotAvailable() {
                  getTasksFromRemoteDataSource(callback)
              }
          })
      }
  }
  ```
- **为什么容易测试**：它接收通用回调类型 `TasksDataSource.LoadTasksCallback` 作为参数，使其**完全独立于任何 Android 类**，因此易于使用 JUnit 进行单元测试。
- **Mock 测试示例**：例如要模拟本地数据不准确的情况：
  ```kotlin
  private lateinit var tasksRepository: TasksRepository

  @Mock private lateinit var loadTasksCallback: TasksDataSource.LoadTasksCallback
  @Mock private lateinit var tasksRemoteDataSource: TasksDataSource
  @Mock private lateinit var tasksLocalDataSource: TasksDataSource

  private val TASKS = Lists.newArrayList(
      Task(TASK_TITLE_1, TASK_GENERIC_DESCRIPTION),
      Task(TASK_TITLE_2, TASK_GENERIC_DESCRIPTION))

  @Before fun setupTasksRepository() {
      MockitoAnnotations.initMocks(this)
      tasksRepository = TasksRepository.getInstance(tasksRemoteDataSource, tasksLocalDataSource)
  }

  @Test fun getTasksWithLocalDataSourceUnavailable_tasksAreRetrievedFromRemote() {
      tasksRepository.getTasks(loadTasksCallback)
      setTasksNotAvailable(tasksLocalDataSource)
      setTasksAvailable(tasksRemoteDataSource, TASKS)
      verify(loadTasksCallback).onTasksLoaded(TASKS)
  }
  ```

**Q258: 在 Kotlin 中如何实现 MVP 的 View 与 Presenter？**

- **View 的归类**：在界面展示数据的时候，View 通过 Presenter 来发送获取数据的指令。在 MVP 模式中，**Activity、Fragment 和自定义视图都被归为 View**。
- **BaseView 接口**：Todo 项目中，所有 View 都实现了允许设置 Presenter 的 BaseView 接口：
  ```kotlin
  interface BaseView<T> {
      var presenter: T
  }
  ```
- **契约类 TasksContract**：通常把 View 和 Presenter 的接口写在其中，便于管理：
  ```kotlin
  interface TasksContract {
      interface View : BaseView<Presenter> {
          fun showTasks(tasks: List<Task>)
          fun showTaskDetailsUi(taskId: String)
          fun showLoadingTasksError()
          fun showNoTasks()
          ......
      }
      interface Presenter : BasePresenter {
          fun loadTasks(forceUpdate: Boolean)
          ......
      }
  }
  ```
- **Fragment 中延迟初始化的 presenter**：View 模块通常在生命周期函数 `onResume()` 中通知 Presenter"我准备好被更新了，请随时下达指令"。而在 Kotlin 中，通常的做法是在 View 中声明一个**延迟初始化的 presenter**：
  ```kotlin
  class TasksFragment : Fragment(), TasksContract.View {
      override lateinit var presenter: TasksContract.Presenter
      ......
      override fun onResume() {
          super.onResume()
          presenter.start() // 请求加载当前视图初始化需要的数据
      }
  }
  ```
- **Activity 中组装**：在承载视图的 TasksActivity 上，我们初始化视图 TasksFragment 以及 TasksPresenter：
  ```kotlin
  class TasksActivity : AppCompatActivity() {
      ......
      private lateinit var tasksPresenter: TasksPresenter
      override fun onCreate(savedInstanceState: Bundle?) {
          val tasksFragment = supportFragmentManager
              .findFragmentById(R.id.contentFrame) as TasksFragment?
              ?: TasksFragment.newInstance().also {
                  replaceFragmentInActivity(it, R.id.contentFrame)
              }
          // 创建 presenter
          tasksPresenter = TasksPresenter(
              Injection.provideTasksRepository(applicationContext), tasksFragment)
          // 加载历史数据
          ......
      }
  }
  ```
- **init() 中的玄机（绑定技巧）**：看上去上面并没有将 Presenter 和 View 绑定的操作，其实 TasksPresenter 中另有玄机——得益于 `init()`，在 TasksPresenter 初始化的同时，也对 View 中的 presenter 进行赋值，这样就不必每次都写 subscribe 和 unsubscribe 方法了。当然，利用依赖注入（比如 dagger）也能实现这样的需求：
  ```kotlin
  class TasksPresenter(
      val tasksRepository: TasksRepository,
      val tasksView: TasksContract.View
  ) : TasksContract.Presenter {
      init {
          tasksView.presenter = this
      }
      override fun start() {
          loadTasks(false)
      }
      ......
  }
  ```
- **防止内存泄漏**：当页面结束的时候会终止网络请求，我们应该及时释放 Presenter 中的引用，防止内存泄漏。通常使用的是 RxLifeCycle。此外，还能通过结合很多框架（如 dagger、rxKotlin）来让工程更加通透。

**Q259: MVP 模式容易产生哪些问题？**

- **1）接口粒度难以掌控**：MVP 模式将模块职责进行了良好的分离。但在开发小规模 App 或原型时，这似乎增加了开销——对于每个业务场景，我们都要写 Activity-View-Presenter-Contract 这 4 个类。为了缓解这种情况，一些开发者删除了 Contract 接口类和 Presenter 的接口。另外，Presenter 与 View 的交互是通过接口实现的，如果**接口粒度过大，解耦程度就不高**；反之会造成**接口数量暴增**的情况。从工程的严谨角度来说，这或许并不是缺点，只是创造一个良好工程架构带来的额外工作量。
- **2）Presenter 逻辑容易过重**：当我们将 UI 的逻辑移动到 Presenter 中时，Presenter 变成了有数千行代码的类，或许会难以维护。要解决这个问题，我们只可能更多地拆分代码，创建便于单元测试的单一职责的类。
- **3）Presenter 和 View 相互引用**：我们在 Presenter 和 View 中都会保持一份对对方的引用，所以需要用 subscribe 和 unsubscribe 来绑定和解除绑定。在操作 UI 的时候，我们需要判断 UI 生命周期，否则容易造成内存泄露。
- **引出下一步**：当然，以上的"缺点"我们都可以通过良好的编码习惯及严谨的设计来规避。如果我们想要一个**基于事件且 View 会对事件变化做出反应**的架构，该怎么实现呢？这就引出了 MVVM。

**Q260: 什么是 MVVM？它与 MVP 的根本区别是什么？（12.1.3）**

- **维基百科定义**：MVVM 有助于将图形用户界面的开发与业务逻辑或后端逻辑（数据模型）的开发分离开来，这是通过置标语言（标记语言）或 GUI 代码实现的。MVVM 的视图模型是一个**值转换器**，这意味着视图模型负责从模型中暴露（转换）数据对象，以便轻松管理和呈现对象。在这方面，视图模型比视图做得更多，并且处理大部分视图的显示逻辑。视图模型可以实现**中介者模式**，组织对视图所支持的用例集的后端逻辑的访问。
- **主要构成**：MVVM 也被称为 model-view-binder。
  - **Model（数据模型）**：与 ViewModel 配合，可以获取和保存数据；
  - **View（视图）**：即将用户的动作通知给 ViewModel（视图模型）；
  - **ViewModel（视图模型）**：暴露公共属性与 View 相关的数据流，通常为 Model 和 View 的绑定关系。
- **与 MVP 的相似与不同（核心区别）**：作为 MV* 家族的一员，它看起来与 MVP 模式有所相似：它们都擅长抽象视图行为和状态。
  - 如果 MVP 模式意味着 Presenter **直接告诉 View 要显示的内容**；
  - 那么 MVVM 中，ViewModel 会**公开 Views 可以绑定的事件流**。这样，ViewModel 不再需要保持对 View 的引用，但发挥了 Presenter 一样的作用。这也意味着 **MVP 模式所需的所有接口现在都被删除了**——这对介意接口数量过多的开发者来说是个福音。
- **双向数据绑定与多对一关系**：View 还会通知 ViewModel 进行不同的操作。因此，MVVM 模式支持 View 和 ViewModel 之间的**双向数据绑定**，并且 View 和 ViewModel 之间存在**多对一**关系。View 具有对 ViewModel 的引用，但 ViewModel 没有关于 View 的信息。因为数据的使用者应该知道生产者，但生产者 ViewModel 不需要知道、也不关心谁使用数据。

**Q261: MVVM 中 Data Binding 是如何实现双向数据绑定的？（以 addtask_frag.xml 为例）**

- **背景**：光有概念部分读者可能还不能感受到 MVVM 的特点。以官方 todo-app 中的 addTask 模块为例，先看它的布局 addtask_frag.xml：
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <layout xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:app="http://schemas.android.com/apk/res-auto">
      <data>
          <import type="android.view.View"/>
          <variable
              name="viewmodel"
              type="com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskViewModel"/>
      </data>

      <EditText
          android:id="@+id/add_task_title"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:hint="@string/title_hint"
          android:singleLine="true"
          android:text="@={viewmodel.title}"/>

      <EditText
          android:id="@+id/add_task_description"
          android:layout_width="match_parent"
          android:layout_height="350dp"
          android:gravity="top"
          android:hint="@string/description_hint"
          android:text="@={viewmodel.description}"/>
  </layout>
  ```
- **<data> 标签的含义**：之前没有接触过 MVVM 模式的读者，应该会对 `<data>` 标签感到疑惑。这其实是 **Data Binding** 的一种特性：使用 Data Binding 让 xml 绑定数据，我们需要以 `<layout>` 为根布局，并且声明 `<data>`，其中 `type` 对应 Model（需要指定完整类名），`name` 相当于 Model 在当前视图中对应的对象。
- **双向绑定的表现**：我们在 xml 中就可以用 `android:text="@={viewmodel.description}"` 实现绑定。当 `viewmodel.description` 变化的时候，EditText 也会改变；反之，当我们编辑 EditText 的时候，`viewmodel.description` 的值也会相应变化。这正是 MVVM 与 MVP 最直观的差异——**数据驱动 UI，UI 的变更也自动回写到数据**。

**Q262: MVVM 中 ViewModel 是如何实现的？（AddEditTaskViewModel 与 Fragment 的绑定）**

- **ViewModel 的设计**：将大部分操作数据的逻辑都放在这个类中，在维护的时候就能体会到其中的优势。它通过 Observable 字段暴露数据，完全不需要持有 View 引用：
  ```kotlin
  class AddEditTaskViewModel internal constructor(
      context: Context,
      private val mTasksRepository: TasksRepository
  ) : TasksDataSource.GetTaskCallback {
      val title = ObservableField<String>()
      val description = ObservableField<String>()
      val dataLoading = ObservableBoolean(false)
      val snackbarText = ObservableField<String>()

      private val mContext: Context // 避免内存泄漏，我们应该使用Application的Context
      private var mTaskId: String? = null
      private var isNewTask: Boolean = false
      private var mIsDataLoaded = false
      private var mAddEditTaskNavigator: AddEditTaskNavigator? = null

      init {
          mContext = context.applicationContext // 强制使用Application的context
      }

      fun onActivityCreated(navigator: AddEditTaskNavigator) {
          mAddEditTaskNavigator = navigator
      }

      fun onActivityDestroyed() {
          // 释放不需要的引用
          mAddEditTaskNavigator = null
      }

      fun start(taskId: String?) {
          if (dataLoading.get()) {
              return
          }
          mTaskId = taskId
          if (taskId == null) {
              isNewTask = true
              return
          }
          if (mIsDataLoaded) {
              return
          }
          isNewTask = false
          dataLoading.set(true)
          mTasksRepository.getTask(taskId, this)
      }

      override fun onTaskLoaded(task: Task) {
          title.set(task.title)
          description.set(task.description)
          dataLoading.set(false)
          mIsDataLoaded = true
          // 这里我们不需要像MVP模式那样主动改变View，因为我们已经使用了Observable
      }
      ......
  }
  ```
- **Fragment 中绑定 ViewModel 的两步**：
  1. 通过 View 来创建一个 ViewDataBinding 的对象；
  2. 将 mViewModel 赋值到 XML 文件 `<data>` 里声明的 ViewModel 的具体对象当中，从而使 ViewModel 和 XML 文件创建关联：
  ```kotlin
  class AddEditTaskFragment : Fragment() {
      private var mViewModel: AddEditTaskViewModel? = null
      private lateinit var mViewDataBinding: AddtaskFragBinding
      ......
      override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?,
                                savedInstanceState: Bundle?): View? {
          val root = container?.inflate(R.layout.addtask_frag)
          mViewDataBinding = AddtaskFragBinding.bind(root)
          mViewDataBinding.viewmodel = mViewModel
          setHasOptionsMenu(true)
          retainInstance = false
          return mViewDataBinding.root
      }
      ......
  }
  ```
- **与 MVP 的对比**：与 MVP 类似，我们在 Fragment 的各个生命周期中，调用 mViewModel 对应的方法来响应 View 的变化。**不同的是**，在 MVVM 中我们只需要改变 viewModel 中的数据，View 的响应已经自动完成了（比如通过 Data Binding）。这样代码的结构比之前更加通透，我们核心关注的就是**数据的改变**。

**Q263: MVVM 容易造成哪些问题？**

- **1）需要更多精力定位 Bug**：由于双向绑定，视图中的异常排查起来会比较麻烦，你需要检查 View 中的代码，还需要检查 Model 中的代码。另外你可能多处复用了 Model，一个地方导致的异常可能会扩散到其他地方，定位错误源可能并不会太简单。
- **2）通用的 View 需要更好的设计**：当一个 View 要变成通用组件时，该 View 对应的 Model 通常不能复用。在整体架构设计不够完善时，我们很容易创建一些冗余的 Model。
- **解决思路**：如果说双向数据流这种"自动管理状态"的特性会给我们造成困扰，除了在编码上规避，还有其他的解决方案吗？答案是肯定的，这里我们推荐使用**谷歌官方的 Android Architecture Components**。

**Q264: 什么是单向数据流模型？Flux 由哪四部分组成？（12.2）**

- **引出**：既然有双向数据绑定的架构 MVVM，那自然少不了单向数据流。如果你接触过前端，你肯定听说过 **Flux**，它是最经典的单向数据流架构之一。
- **Flux 的 4 个组成部分**：
  - **View（视图）**：显示 UI；
  - **Action（动作）**：用户操作界面时，视图层发出的消息（比如用户点击按钮、输入文字等）；
  - **Dispatcher（分发器）**：用来接收 Actions，执行回调函数；
  - **Store（数据层）**：类似于 MV* 的 Model 层。用来存放应用的状态，一旦发生变动，就提醒 View 更新页面。
- **完整的数据流动过程**：用户通过与 view 交互或者外部产生一个 Action，Dispatcher 接收到 Action 并执行那些已经注册的回调，向所有 Store 分发 Action。通过注册的回调，Store 响应那些与它所保存的状态有关的 Action。然后 Store 会触发一个 change 事件，来提醒对应的 View 数据已经发生了改变。View 监听这些事件并重新从 Store 中获取数据。这些 View 调用它们自己的 `setState()` 方法，重新渲染自身及相关联的组件。
- **更多实例**：除了 Flux，当前 Web 前端比较常用的 **React** 也是比较典型的单向数据流框架，它也是基于 Redux 模型实现的。

**Q265: Redux 是什么？它的三大核心概念是什么？（12.2.1）**

- **定位**：Redux 作为 **Flux 模型一个友好简洁的实现**，它基于一个严格的单向数据流：应用中的所有数据都是通过组件在一个方向上流动。Redux 希望确保应用的视图是根据**确定的状态**来呈现的——即在任何阶段，应用的状态总是确定、有效的，并且可以转换到另一个可预测、有效的状态，视图将根据所处的状态来进行对应的展示。
- **1）Store**：保存应用的状态并提供方法来存取对应的状态，分发状态，并注册监听。
- **2）Actions**：与 Flux 类似。包含要传递给 Store 的信息，表明我们希望怎样改变应用的状态。比如，在 Kotlin 中我们可以定义如下 action：
  ```kotlin
  data class AddTodoAction(val title: String, val content: String)
  ```
  然后由 store 进行分发：
  ```kotlin
  store.dispatch(AddTodoAction("Finish your homeWork", "English And Math"))
  ```
- **3）Reducers**：Store 收到 Action 以后，必须给出一个新的 State，这样 View 才会发生变化。这种 State 的计算过程就叫作 Reducer：
  ```kotlin
  fun reduce(oldState: AppState, action: Action): AppState {
      return when (action) {
          is AddTodoAction -> {
              oldState.copy(todo = ...)
          }
          else -> oldState
      }
  }
  ```

**Q266: Flux 与 Redux 有何异同？**

- **相同点**：Redux 是 Flux 模型的实现，两者都是典型的单向数据流架构：数据只沿"Action → Store → View"一个方向流动，UI 变化不直接反向修改数据。
- **不同点（Redux 的简化）**：对比 Flux，我们可以发现一些不同点——Redux 作为 Flux 一个**友好而简洁**的实现，将"分发 + 存储 + 状态计算"的职责进一步收敛：由单一的 Store 统一负责存取状态、分发状态与注册监听，Actions 只负责用**陈述性**的信息描述期望的状态变更，而状态的计算完全交给 **Reducer 纯函数**完成（给定相同的输入 State 与 Action，永远返回相同的输出 State）。
- **设计目标**：通过这种简化，Redux 确保了"视图根据确定状态呈现"的可预测性：任何阶段应用的状态都是确定、有效、可预测地转换的。

**Q267: 单向数据流最大的优势是什么？为什么它的数据追溯能力更强？（12.2.2）**

- **总述**：单向数据流架构的最大优势在于整个应用中的数据流以**单向流动**的方式，从而使得拥有**更好的可预测性与可控性**，这样可以保证应用各个模块之间的**松耦合性**。
- **对比 MVVM 的"自动同步"困境**：在 MVVM 中，数据变动时由框架自动帮我们实现视图的同步变更，更改一个地方的数据，可能会影响很多地方的状态，并且它是**不可预期**的，很难维护和调试。而单向数据流的架构中，整个应用状态是**可预测**的，我们可以监听到数据变动，从而采取自定义的操作。
- **单一数据入口**：对于一个组件来说，数据入口只有唯一一个。当数据发生改变时，UI 也会发生改变；反之 UI 的变化并不会直接变动数据。这不仅使得程序更直观、更容易理解，而且更有利于应用的可维护性。

**Q268: 为什么单向数据流能带来更简洁的单元测试？**

- **可以"伪造"事件**：因为 Dispatcher 是所有 Action 的处理中心，即使没有对应的事件发生，我们也可以"伪造"一个出来，只需要用 Action 对象向 Dispatcher 描述当前的事件，就可以执行对应的逻辑。
- **Reducer 是纯函数（核心原因）**：在 Redux 中，由于 Reducer 是纯函数而没有内部状态，对于给定的输入状态和操作，它们将始终返回相同的输出状态。因为 State 和 Action 相对是轻量级的，所以我们可以把测试重点放在 Reducer 上。在 Kotlin 中代码可能是这样的：
  ```kotlin
  class TodoReducer {
      fun reduce(state: AppState, action: Action): AppState {
          // todo 逻辑操作
      }
  }

  data class TodoAction(val text: String)

  val todoReducer = TodoReducer()
  val originalState = AppState(/* todo 初始状态 */)
  val todoAction = TodoAction(text = "just haha")
  val newState = todoReducer.reduce(originalState, todoAction)
  // 判断newState与预期是否一致
  assert(newState ...)
  ```
- **多来源数据的测试技巧**：如果数据需要从多个地方获取（比如，本地存储或网络中获取），我们可以改变 Action 的结构：
  ```kotlin
  class TodoAction(val dataOfLocal: String, val dataOfApi: String) {
      companion object {
          fun create(localStore: LocalStore, apiResponse: ApiResponse): TodoAction {
              val dataOfLocal = localStore.targetData
              val dataOfApi = apiResponse.targetData
              return TodoAction(dataOfLocal = dataOfLocal, dataOfApi = dataOfApi)
          }
      }
  }
  ```
  这样测试起来也是很容易：
  ```kotlin
  val todoAction = TodoAction(dataOfLocal = "i'm from sqlite", dataOfApi = "i'm from api")
  val newState = reducer.reduce(originalState, todoAction)
  ......
  ```

**Q269: 单向数据流遇上 Kotlin 后有什么优势？（Kotlin 如何拯救样板代码）**

- **背景**：因为 Redux 是基于 Flux 的思想产生的，所以在 Redux 架构中构造组件，通常也会产生许多样板代码。对于 JavaScript 来说，这可能难以优化。而使用 Kotlin，我们能更加方便地管理样板代码。
- **Java 的套路**：当我们在 Reducer 中匹配不同类型的 Action 时，按照 Java 的套路可能会这样写——要么在 Action 上添加 `type` 字段做 switch 分支：
  ```java
  AppState reduce(Action action, AppState oldState) {
      switch (action.type) {
          case TodoAction.TYPE.ADD_TODO_ITEM:
              return addTodoAction(oldState, action);
          case TodoAction.TYPE.CHANGE_STATE:
              return changeAction(oldState, action);
          default:
              return oldState;
      }
  }
  ```
  要么当 Action 结构相对比较复杂、不想再添加一个 type 字段时，直接判断 Action 属于什么类：
  ```java
  AppState reduce(Action action, AppState oldState) {
      if (action instanceof AddTodoAction) {
          return addTodoAction(oldState, action);
      } else if (action instanceof ChangeTodoAction) {
          return changeAction(oldState, action);
      } else if (...) {
          ......
      }
      return oldState;
  }
  ```
  这个时候，如果 action 非常多，就会给开发者带来巨大的痛苦。
- **Kotlin 的 when 来拯救**：
  ```kotlin
  fun reduce(action: Action, oldState: AppState): AppState {
      return when (action) {
          is AddTodoAction -> reduceAddTodoAction(oldState, action)
          is RemoveTodoAction -> reduceRemoveTodoAction(oldState, action)
          else -> oldState
      }
  }
  ```
- **Smart Casts 的额外好处**：我们还能利用 **Smart Casts**，在数据处理的同时避免不必要的判断。当然，这里只是用 Kotlin 提升 Redux 架构便捷性的冰山一角。
- **总结**：虽然 Redux 起源于 Web 端，但从它的构建中，我们可以看到很多非常好的想法，这都是值得学习并可以尝试引入 Android 的。即使我们的平台、语言和工具可能不同，但在架构层面，我们面对着许多相同的基本问题，比如，尽可能降低 View 和业务逻辑代码的耦合度等。

**Q270: ReKotlin 是什么？它奉行哪些核心设计？（12.3.1）**

- **背景**：如果你是一名 Android 开发者，你应该知道：在国内的项目中，鲜有单向数据流架构的痕迹。甚至一些经验不够丰富的 Android 开发者，可能都不知道"单向数据流"。
- **渊源**：在 iOS 中，有一个著名的单向数据流框架 **ReSwift**，它在 GitHub 上的被关注度还不错。随着 Kotlin 在 Android 中的地位不断提高，利用其优秀的语言特性，也派生出了类似的框架：**ReKotlin**。它的出现，也宣布了 Android 即将"跨入单向数据流时代"。
- **基于经典 Redux 模型，ReKotlin 奉行的设计**：
  - **The Store**：以**单一数据结构**管理整个 App 的状态，状态只能通过 dispatching Actions 来进行修改。每当 Store 中的状态改变了，它就会通知所有的 Observers。
  - **Actions**：通过陈述的形式来描述一次状态变更，**操作中不包含任何代码**，通过 Store 转发给 Reducers。Reducers 会接收这些 Actions，然后进行相应的状态逻辑变更。
  - **Reducers**：基于当前的 Action 和 App 状态，通过**纯函数**来返回一个新的 App 状态。
- **对单向数据流的直观概括**：单向数据流意味着应用程序的 **State 不应该保存在许多不同的地方**。相反，存储组件将所有 State 保持在**中心位置**。View 会对 State 的更改做出反应，而不是在内部处理它。Action 是触发 State 更改的唯一方法，它不会通过它们自己来更改状态，而更像是一些**指令**——表示某些内容将发生变化。这些"指令"是针对使用执行实际状态更改的 Reducers 的 Store 对象发出的。
- **Middleware（中间件）**：由于 Action 的接收方 Reducer 都是纯函数、不能产生副作用，因此引入了中间件，它主要用来处理**副作用**（如网络请求、日志打印、数据库操作等），这会在后面介绍。

**Q271: 如何创建基于 ReKotlin 的项目？（引入依赖与整体结构）（12.3.2）**

- **1. 引入 ReKotlin**：在 Gradle 中集成 ReKotlin，这里以 1.0.0 版本为例，同时加上一些日常需要的框架（版本仅供参考，引入实际项目时可酌情调整）：
  ```gradle
  dependencies {
      implementation 'com.android.support:recyclerview-v7:27.1.1'
      implementation 'com.android.support:cardview-v7:27.1.1'
      implementation 'com.android.support:design:27.1.1'
      // reKotlin
      implementation "org.rekotlin:rekotlin:1.0.0"
      // http
      implementation 'com.squareup.retrofit2:retrofit:2.3.0'
      implementation 'com.squareup.retrofit2:converter-gson:2.3.0'
      // imageLoader
      implementation 'com.squareup.picasso:picasso:2.5.2'
      // json
      implementation 'com.google.code.gson:gson:2.8.2'
      // log
      implementation 'com.jakewharton.timber:timber:4.6.0'
  }
  ```
- **2. 整体结构**：本次的示例主要是做一个电影列表，从开源 API 中获取数据，然后将其显示到 App 中。项目文件清单如下：
  ```
  - actions
    - MovieListActions.kt
  - middlewares
    - MovieMiddleWare.kt
    - NetworkMiddleWare.kt
  - model
    - Movie.kt
  - network
    - Api.kt
    - HttpClient.kt
  - reducers
    - AppReducer.kt
    - MovieListReducer.kt
  - states
    - AppState.kt
    - MovieListState.kt
  - ui
    - BaseActivity.kt
    - MovieDetailActivity.kt
    - MovieListAdapter.kt
    - MovieListFragment.kt
    - MainActivity.kt
  - utils
    - ImageLoder.kt
    - Logger.kt
  - MovieApplication.kt
  ```
- **新增目录的职责（为什么这样分层）**：其中 model、network、ui、utils 文件夹与我们平常的项目结构类似。把目光聚焦在新增加的 actions、middlewares、reducers、states 目录上：
  - **actions**：所有更新 State 的行为我们都可以抽象成 Action，并且根据不同的场景分布在不同文件下；
  - **reducers**：不同 Action 对应的响应中心，会返回一个新的状态（State）；
  - **middlewares**：由于 Action 的接收方 Reducer 都是纯函数，不会也不能产生副作用，如果我们想加入一些额外的操作，例如打印日志、操作 SQLite 数据库等，我们可以将这些操作放到该文件夹中；
  - **states**：所有状态的声明都放在这个目录下。

**Q272: ReKotlin 中 Store 是如何初始化的？（MovieApplication）**

- **为什么在 Application 中初始化**：在 ReKotlin 中，**每个 App 对应只有一个数据管理中心（Store）**。所以，我们可以在 Application 中将其初始化，项目中我们使用的自定义的 Application 名为 MovieApplication：
  ```kotlin
  import android.app.Application
  import dripower.rekotlinsimpleexample.middlewares.movieMiddleWare
  import dripower.rekotlinsimpleexample.middlewares.networkMiddleWare
  import dripower.rekotlinsimpleexample.ruducer.appReducer
  import org.rekotlin.Store

  val store = Store(
      reducer = ::appReducer,
      state = null
  )

  class MovieApplication : Application() {
      ...
  }
  ```
- **顶层 val 的意义**：通过顶层的 `store` 全局单例，任何 View 都可以拿到这唯一的数据源，从而保证"所有 State 保持在中心位置"这一单向数据流的核心理念。

**Q273: ReKotlin 中 View 是如何与 Store 数据流绑定的？（MovieListFragment）**

- **View 部分**：示例采用 Activity + Fragment 的常规组合。先看 BaseActivity 与 MainActivity——MainActivity 通过一个事务扩展函数渲染出 MovieListFragment：
  ```kotlin
  import android.support.v4.app.FragmentTransaction
  import android.support.v7.app.AppCompatActivity

  abstract class BaseActivity : AppCompatActivity() {

      inline fun BaseActivity.transFragment(action: FragmentTransaction.() -> Unit) {
          supportFragmentManager.beginTransaction().apply {
              action()
          }.commit()
      }
  }
  ```
  ```kotlin
  import android.os.Bundle
  import android.support.v4.app.Fragment
  import dripower.rekotlinsimpleexample.R
  import dripower.rekotlinsimpleexample.ui.movieList.MovieListFragment

  class MainActivity : BaseActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_main)
          showFragment(MovieListFragment())
      }
      private fun showFragment(fragment: Fragment) {
          transFragment {
              replace(R.id.container, fragment)
          }
      }
  }
  ```
- **MovieListFragment：View 与 Store 绑定的重点**。通常，在与数据打交道的界面中，我们都会实现 `StoreSubscriber<TState>` 接口（这是 ReKotlin 中实现的，我们可以直接使用）：
  ```kotlin
  class MovieListFragment : Fragment(), StoreSubscriber<MovieListState?> {
      private lateinit var movieListAdapter: MovieListAdapter

      override fun newState(state: MovieListState?) {
          state?.movieObjects?.let {
              initializeAdapter(it)
          }
      }

      override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                savedInstanceState: Bundle?): View? =
          inflater.inflate(R.layout.fragment_movie_list, container, false)

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)
          store.dispatch(LoadTop250MovieList())
      }

      ......

      override fun onStart() {
          super.onStart()
          store.subscribe(this) {
              it.select {
                  it.movieListState
              }.skipRepeats()
          }
      }

      override fun onStop() {
          super.onStop()
          store.unsubscribe(this)
      }
  }
  ```
- **各个生命周期中要做的事情（为什么这样分配）**：
  1. **`override fun newState(state: MovieListState?)`**：这里相当于一个**数据流管道的出口**，当数据 state 变化时，我们可以对其做一些相应的操作，有点类似前端中的 Watch 机制。示例中，每次数据变化我们都更新列表数据，重新渲染；
  2. **onViewCreated**：通常我们在这个生命周期里**发起数据请求**（网络或者本地数据库）。示例中，我们仅做了网络请求；
  3. **onStart**：我们通常在这里进行 **store 的绑定**（subscribe）；
  4. **onStop**：相应地，我们需要在视图不显示的时候，**解除 store 绑定**，防止内存泄漏等问题。
- **列表与详情页**：其余代码为列表适配器绑定以及跳转 MovieDetailActivity。MovieListAdapter 只是常规的 RecyclerView.Adapter（绑定评分、标题、图片，并处理图片点击跳转详情）；MovieDetailActivity 界面很简单：包含一个返回按钮，两个文本框分别显示传入的 movie name 及 id：
  ```kotlin
  class MovieDetailActivity : BaseActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_movie_detail)
          val id = intent.extras?.get("id")
          val title = intent.extras?.get("title")
          tv_movie_id.text = id as String
          tv_movie_title.text = title as String
          btn_back.setOnClickListener { this.finish() }
      }
  }
  ```

**Q274: ReKotlin 中 State、Action、Reducer 是如何定义的？**

- **先从状态（State）开始**：既然称为单向数据流，我们最核心的地方肯定都是围绕数据展开的。对于某个场景来说，我们的数据为该场景的**状态（State）**服务，而状态直接决定了该场景视图显示的内容。所以我们需要先确定好这些状态。对于当前示例，我们需要显示 movieList：
  ```kotlin
  import dripower.rekotlinsimpleexample.model.Subject
  import org.rekotlin.StateType

  data class MovieListState(
      var movieObjects: List<Subject>? = null
  ) : StateType
  ```
- **统一管理多个场景的状态**：一个 App 中会对应很多场景，我们同样需要进行统一管理。当前示例中我们将其放入 AppState：
  ```kotlin
  data class AppState(
      var movieListState: MovieListState? = null
  ) : StateType
  ```
- **定义动作（Action）**：确定最终显示效果之后，我们可以打开数据流的开关，并且控制它们的流向，为当前场景的操作定义不同的动作（Action）：
  ```kotlin
  class InitMovieList(val movieData: List<Subject>) : Action
  class LoadTop250MovieList : Action
  class ShowMovieList(val movieData: List<Subject>) : Action
  ```
  我们将所有对 MovieList 的操作都放入 Action 中，以方便管理。当我们调用 `store.dispatch(Action())` 之后，才能让 Reducer 处理 State 的变化。
- **编写 Reducer**：AppReducer 负责组合分发到各个场景的 reducer；MovieListReducer 针对当前场景，对 ShowMovieList 作出响应并产生新状态：
  ```kotlin
  fun appReducer(action: Action, appState: AppState?): AppState =
      AppState(movieListState = movieListReducer(action, appState?.movieListState))
  ```
  ```kotlin
  fun movieListReducer(action: Action, movieListState: MovieListState?): MovieListState {
      var state = movieListState ?: MovieListState()
      when (action) {
          is ShowMovieList -> {
              state = state.copy(movieObjects = action.movieData)
          }
      }
      return state
  }
  ```
- **完整闭环**：View 派发 Action → Store 交给 Reducer 计算新 State → 状态变化触发订阅者的 `newState()` → 重新渲染列表。这样，一个单向数据流架构的 App 就完成了。

**Q275: ReKotlin 中的 Middleware 中间件起什么作用？如何实现？（处理副作用）**

- **为什么要中间件**：我们知道，Reducer 是一个**没有副作用**的处理，所以如果需要对数据进行中间加工或者打印日志等，都需要放到**中间件 Middleware** 中。
- **如何接入**：如果使用 Middleware，需要在初始化 Store 的时候传入 Middleware 参数。本示例中我们在 MovieApplication 中初始化 Store，需要做如下更改：
  ```kotlin
  val store = Store(
      ......
      middleware = listOf(networkMiddleware, movieMiddleware)
  )
  ```
- **NetworkMiddleware：把网络请求当作副作用**。本示例中，我们将网络请求获取 MovieList 的逻辑放在 networkMiddleware 中，当返回正确的结果时，我们进行渲染 movieList 的操作，否则打印错误日志：
  ```kotlin
  internal val networkMiddleware: Middleware<AppState> = { dispatch, _ ->
      { next ->
          { action ->
              when (action) {
                  is LoadTop250MovieList -> {
                      getTop250MovieList(dispatch)
                  }
              }
              next(action)
          }
      }
  }

  // 这里即获取movie数据的核心逻辑
  private fun getTop250MovieList(dispatch: DispatchFunction) {
      val apiService = HttpClient.client?.create(Api::class.java)
      val call = apiService?.getTop250MovieList()
      call?.enqueue(object : Callback<MovieResponse> {
          override fun onFailure(call: Call<MovieResponse>?, t: Throwable?) {
              Logger.error(t)
          }
          override fun onResponse(call: Call<MovieResponse>?, response: Response<MovieResponse>?) {
              val movieObjects = response?.body()?.subjects
              movieObjects?.let {
                  dispatch(InitMovieList(it))
              }
          }
      })
  }
  ```
- **MovieMiddleware：加工后再次派发**。同时，我们在初始化 MovieList 的时候（即发送 InitMovieListAction），让 Action 进入 Middleware 中，在这里可以对 movieList 做一些有副作用的操作，加工完毕后派发新的 Action（ShowMovieList）回到数据流：
  ```kotlin
  internal val movieMiddleware: Middleware<AppState> = { dispatch, _ ->
      { next ->
          { action ->
              when (action) {
                  is InitMovieList -> {
                      processMovies(action.movieData, dispatch)
                  }
              }
              next(action)
          }
      }
  }

  private fun processMovies(movieObjects: List<Subject>, dispatch: DispatchFunction) {
      // 你可以在这里对movieList进行一些有副作用的操作，例如：打印日志、操作SQLite数据库等
      dispatch(ShowMovieList(movieObjects))
  }
  ```
- **最终联动**：当 MovieListReducer 接收到 ShowMovieList 的 action 时，将会更新 state 中的 movieObjects。还记得我们在 MovieListFragment 中实现了 `StoreSubscriber<MovieListState?>` 接口吗？当 MovieListState 发生变化时，将会触发 `newState(state: MovieListState?)` 方法，这样就会重新渲染 movieList。如果你的代码正确，此时一个完整的列表界面就呈现出来了。
- **局限与期望**：当然，这样还不是最理想的使用方式。在单元测试的时候，可能依旧局限于单个视图下的数据操作，即只能保证数据流的验证（虽然在单向数据流中，这已经足够验证我们的视图正确显示了——除非你的 UI 显示逻辑显示错误）。要是我们能够对视图进行测试，那该多好啊！

**Q276: 传统视图导航存在哪些问题？（12.4.1）**

- **背景**：经过以上介绍，相信你已经能够掌控数据流了。现在要解决另一个问题：如何解耦视图导航。在移动端，我们需要借助视图导航来完成页面切换及数据传递。随着 App 的业务不断复杂化，传统的视图导航存在着许多不便之处。
- **1. 高耦合的 Activity.class**：在传统的 Android 开发中，显示跳转 Activity 我们一般这样写：
  ```kotlin
  val intent = Intent()
  intent.setClass(this, TargetActivity::class.java)
  startActivity(intent)
  ```
  这是绝大多数 Android 开发者的首选做法。所以这看上去非常和谐，不存在任何问题。但是实际上造成了**很高的耦合性**：当前 Activity 如果要跳转到 TargetActivity，就一定要引用 TargetActivity。这衍生出了两个问题：
  - 如果项目中存在多个 Module，底层 Module 中 Activity **不能跳转到**上层的 Activity；
  - 如果 TargetActivity **类名变化**，调用的地方需要相应改动。
- **2. 难以管理的 intent-filter**：在 Android 中，我们通常用 intent-filter 来隐式启动/跳转 Activity：
  ```kotlin
  val intent = Intent()
  intent.action = Intent.ACTION_SENDTO
  intent.data = Uri.parse("smsto:10000")
  context.startActivity(intent)
  ```
  如果项目中存在多个 Module、Activity，需要在各自 Module 的 AndroidManifest.xml 中声明配置，**容易重复，难以统一管理**。
- **3. 不友好的 Hybrid**：在 React Native、Weex、Flutter 大行其道的现实环境下，我们难免会与混合开发打交道。当 H5 页面需要跳转到 Native，并且需要把相关数据传递过去时，通常情况下，我们会采取两种做法：
  - 直接根据目标 Activity 的 Action 中的 Scheme 跳过去；
  - Native 维护一个 `<关键字，Activity>` 的 Map，H5 传过来 Activity 的"关键字"，Native 在 Map 中查到后进行跳转。
  - **第 1 种情况**：Action 命名需要符合 iOS 和 Android 两个平台的规范，如果当前版本的 Native 不支持该 Action，还需要进行跳转失败的处理；
  - **第 2 种情况**：维护 `<关键字，Activity>` 的 Map 比较麻烦。另外，Activity 的存储及生命周期的处理都会存在问题；
  - 并且在两种情况下，我们都可能难以获取到 Context 的引用，这时候需要使用 **Application 的 Context**。

**Q277: rekotlin-router 是什么？如何用它实现声明式路由与导航解耦？（12.4.2）**

- **背景**：以上几种都是传统导航中存在的问题。作为国内开发者，我们应该接触过著名的开源框架 **ARouter**，它能够给以上问题一个很好的解决方案，并且还能解决其他额外的很多问题。但是对于我们来说，这也许有些"重"，我们可以使用与 ReKotlin 配套的 **rekotlin-router**。
- **定位**：ReKotlin 的主要贡献者对 rekotlin-router 是这么阐述的：ReKotlin 的**声明式路由**，允许开发者以 Web 上使用 URL 类似的方式声明路由。
- **引入依赖**：
  ```gradle
  implementation 'org.rekotlinrouter:rekotlin-router:0.1.9'
  ```
- **扩展导航状态**：将原有 AppState 扩展出导航的状态：
  ```kotlin
  import org.rekotlinrouter.HasNavigationState
  import org.rekotlinrouter.NavigationState

  data class AppState(
      ...
      override var navigationState: NavigationState
  ) : StateType, HasNavigationState
  ```
- **创建 Router 实例**：在初始化 AppState 之后，我们需要创建 Router 的实例。需要传入关联的 store 与根 Routable：
  ```kotlin
  router = Router(
      store = mainStore,
      rootRoutable = RootRoutable(context = applicationContext),
      stateTransform = { subscription ->
          subscription.select { stateType ->
              stateType.navigationState
          }
      }
  )
  ```
- **封装跳转 Route 的 Action**：然后我们封装一个跳转 Route 的 Action。它实现了 `StandardActionConvertible`，可以将 Kotlin 的 typed action 与路由框架的 `StandardAction`（类似 URL 的 payload 描述）互相转换：
  ```kotlin
  import org.rekotlin.Action
  import org.rekotlinrouter.Route
  import org.rekotlinrouter.StandardAction
  import org.rekotlinrouter.StandardActionConvertible

  class SetRouteAction(
      private var route: Route,
      private var animated: Boolean = true,
      action: StandardAction? = null
  ) : StandardActionConvertible {
      companion object {
          const val type = "RE_KOTLIN_ROUTER_SET_ROUTE"
      }
      init {
          if (action != null) {
              route = action.payload?.keys?.toTypedArray() as Route
              animated = action.payload!!["animated"] as Boolean
          }
      }
      override fun toStandardAction(): StandardAction {
          val payloadMap: HashMap<String, Any> = HashMap()
          payloadMap.put("route", this.route)
          payloadMap.put("animated", this.animated)
          return StandardAction(
              type = type,
              payload = payloadMap,
              isTypedAction = true
          )
      }
  }

  class SetRouteSpecificData(val route: Route, val data: Any) : Action
  ```
- **调用方式（解耦效果）**：综上，我们就可以这样调用——用声明式的 Route 数组代替对目标 Activity 类的直接引用：
  ```kotlin
  private fun movieListToDetailRoute() {
      val routes = arrayListOf(Routers.mainActivityRoute, Routers.movieDetailActivityRoute)
      val action = SetRouteAction(route = routes)
      store.dispatch(action)
  }
  ```
- **为什么更优雅**：这样是否比之前优雅了很多？就算在复杂的项目中，我们也能很好地管理页面跳转——导航变成了数据流中的一个 Action，路由表集中管理，页面之间不再直接引用彼此的 Class，彻底解耦了视图导航。

**Q278: 本章小结：MV* 家族与单向数据流、ReKotlin 各解决了什么问题？（12.5）**

- **（1）主流的客户端架构**：目前比较主流的客户端架构即 MV* 家族：MVC、MVP、MVVM。其中 **MVC 适合小而简单的 App**，而 **MVP 和 MVVM 的选择需从 App 具体业务场景出发**。从 MVC 到 MVP 的演变**完成了 View 与 Model 的解耦**，改进了职责分配与可测试性。而从 MVP 到 MVVM，添加了 **View 与 ViewModel 之间的数据绑定**，使得 View 完全无状态化。
- **（2）从 MV* 到单向数据流**：单向数据流在前端页面中是一种非常流行的架构方式，在 React 和 Vue 中其优点得到极致的体现。从 MV* 到单向数据流的变迁采用了**消息队列式的数据流驱动**的架构，并且以 **Redux** 为代表的方案将原本 MV* 中**碎片化的状态管理变为统一的状态管理**，保证了状态的**有序性与可回溯性**。
- **（3）ReKotlin**：Kotlin 的崛起，让 Android 能够顺滑地支持 **ReSwift 的思想**。利用 ReKotlin，我们可以在 Android 中更容易地实现单向数据流架构，在强有力的状态的有序性与可回溯性前提下，我们能够提供比 MV* 架构**更详尽的单元测试**。并且在复杂的业务场景下，也不易出现难以排查的问题和冗杂的代码。

---

## 第12篇 开发响应式 Web 应用

**Q279: 什么是响应式编程？它是在怎样的背景下诞生的？（本章主线）**

- **编程范式的发展脉络**：随着计算机软件行业的发展，诞生了各种各样的编程语言，也产生了多种编程范式——从最初的命令式编程，到后面的面向对象编程及函数式编程，现在响应式编程也流行起来了。
- **响应式编程的特殊之处**：与前几种范式不同，响应式编程并不建立在特定的语言基础上，很多语言（如 Java、Kotlin、Scala 等）都可以进行响应式编程，尤其是在 Web 开发上的应用变得越来越流行。
- **本章的目的**：带读者了解响应式编程的核心特点，并介绍一个适配 Kotlin 且原生支持响应式开发的 Web 框架——Spring 5，最后动手实现一个简单的响应式 Web 应用。
- **"为什么"要重视响应式编程**：Web 场景下大量操作是 IO 密集型的（查询数据库、调用远程接口），传统同步阻塞模型会浪费宝贵的线程资源，而响应式编程以"异步非阻塞 + 数据流"的方式从根本上改变资源利用方式，这是它流行的根本原因。

**Q280: 为什么同步阻塞的写法不适合下单这类业务场景？（13.1 提出的核心痛点）**

- **典型场景**：假设一个用户要购物下单，我们需要先获取商品详情和用户的地址，然后根据这些信息进行下单操作。最初的做法是顺序调用，每一步都 `Thread.sleep` 模拟 IO 耗时：
  ```kotlin
  data class Goods(val id: Long, val name: String, val stock: Int)
  data class Address(val userId: Long, val location: String)

  fun getGoodsFromDB(goodsId: Long): Goods {
      Thread.sleep(1000) //模拟IO操作
      return Goods(goodsId, "深入Kotlin", 10)
  }

  fun getAddressFromDB(userId: Long): Address {
      Thread.sleep(1000) //模拟IO操作
      return Address(userId, "杭州")
  }

  fun doOrder(goods: Goods, address: Address): Long {
      Thread.sleep(1000) //模拟IO操作
      return 1L
  }

  fun order(goodsId: Long, userId: Long) {
      val goods = getGoodsFromDB(goodsId)
      val address = getAddressFromDB(userId)
      doOrder(goods, address)
  }
  ```
- **"是什么"**：这是一种同步阻塞的方式，每个调用必须等前一个调用返回才能继续，简单、好理解，但效率低。
- **"为什么"不好**：获取商品信息和获取地址这两个操作**互不依赖**，完全可以设计成并行执行以获得更快的响应速度。假设每次 IO 操作耗时 100ms，上面这段代码的执行时间至少是 300ms，而理论上可以控制在 200ms 左右——多出来的 100ms 就是串行等待的浪费。这正是第 11 章讲过的同步阻塞的劣势在真实业务中的体现。

**Q281: 如何使用 CompletableFuture 实现异步非阻塞？（13.1.1）**

- **核心思路**：实现异步非阻塞在 Kotlin 中有两种方式——利用 Java 标准库的 `CompletableFuture`，或者通过协程实现。本节用 `CompletableFuture` 改进上面的代码，思路是让 IO 操作并行执行且整个过程非阻塞：
  ```kotlin
  fun getGoodsFromDB(goodsId: Long): CompletableFuture<Goods> {
      return CompletableFuture.supplyAsync {
          Thread.sleep(1000) //模拟IO操作
          Goods(goodsId, "深入Kotlin", 10)
      }
  }

  fun getAddressFromDB(userId: Long): CompletableFuture<Address> {
      return CompletableFuture.supplyAsync {
          Thread.sleep(1000) //模拟IO操作
          Address(userId, "杭州")
      }
  }

  fun doOrder(goods: Goods, address: Address): CompletableFuture<Long> {
      return CompletableFuture.supplyAsync {
          Thread.sleep(1000) //模拟IO操作
          1L
      }
  }

  fun main(args: Array<String>) {
      val goodsF = getGoodsFromDB(1)
      val addressF = getAddressFromDB(1)
      CompletableFuture.allOf(goodsF, addressF).thenApply { //保证前两个IO操作都完成
          Stream.of(goodsF, addressF).map { it.join() }.collect(Collectors.toList())
      }.thenApply {
          doOrder(it[0] as Goods, it[1] as Address)
      }.join()
  }
  ```
- **关键点**：`supplyAsync` 把耗时的 IO 操作放进线程池异步执行，函数立即返回一个 `CompletableFuture`，主流程不被阻塞；`allOf` 等待两个 Future 全部完成后再进入下一步，从而让两个无关的 IO 并行执行，把总耗时从 300ms 压缩到约 200ms。
- **"为什么"说它不够直观**：Java 8 之后虽然可以用 `CompletableFuture` 写异步非阻塞代码，但对它的操作却不怎么直观。比如上面的合并操作还需要借助 `Stream` 才能拿到结果，`thenApply` 里还要做类型强转，开发变得烦琐、不容易理解——这为下一节引入 RxKotlin 埋下了动机。

**Q282: 什么是 RxKotlin？如何利用它进行响应式编程？（13.1.2）**

- **"是什么"**：RxKotlin 是 RxJava 的 Kotlin 版本——RxJava 提供了对 Java 的支持，RxKotlin 的实现基于 RxJava，但增加了 Kotlin 独有的特性（如 `subscribeBy` 等具名参数回调）。它同样适用于 SE 8 之前的 Java 版本。
- **核心概念**：Rx 系列类库的一个主要作用就是**提供统一的接口来更方便地处理异步数据流**。它将异步任务抽象成可组合的 `Observable`（可观察对象），通过操作符对数据流进行变换与合并。
- **用 RxKotlin 改造下单场景**：
  ```kotlin
  val threadCount = Runtime.getRuntime().availableProcessors()
  val threadPoolExecutor = Executors.newFixedThreadPool(threadCount) //线程池
  val scheduler = Schedulers.from(threadPoolExecutor)               //调度器

  fun getGoodsFromDB(goodsId: Long): Observable<Goods> {
      return Observable.defer {
          Thread.sleep(1000) //模拟IO操作
          Observable.just(Goods(goodsId, "深入Kotlin", 10))
      }
  }

  fun getAddressFromDB(userId: Long): Observable<Address> {
      return Observable.defer {
          Thread.sleep(1000) //模拟IO操作
          Observable.just(Address(userId, "杭州"))
      }
  }

  fun rxOrder(goodsId: Long, userId: Long) {
      var goods: Goods? = null
      var address: Address? = null

      val goodsF = getGoodsFromDB(1).subscribeOn(scheduler)  //指定在调度器线程池执行
      val addressF = getAddressFromDB(1).subscribeOn(scheduler)

      Observable.merge(goodsF, addressF).subscribeBy(         //合并两个Observable
          onNext = { when (it) {
              is Goods -> goods = it
              is Address -> address = it
          } },
          onComplete = { //全部执行后
              doOrder(goods!!, address!!)
          }
      )
  }
  ```
- **几个关键操作符/API 的"为什么"**：
  - `Observable.defer`：延迟到订阅时才真正执行数据源创建逻辑，保证每次订阅都会重新执行 IO，避免只执行一次；
  - `Observable.just`：把一个普通对象包装成只发射一个元素的数据流，从而进入响应式世界；
  - `subscribeOn(scheduler)`：指定该数据流的订阅（即耗时 IO）运行在哪个调度器上，一行代码即完成多线程调度；
  - `Observable.merge`：合并多个 Observable，任一事件到达都会触发 `onNext`，全部完成后触发 `onComplete`，天然实现了两个 IO 的并行与汇合。

**Q283: 使用 RxKotlin 进行响应式编程带来了哪些优势？（13.1.2）**

- **将异步编程变得优雅、直观**：不用对每个异步请求都执行一个回调，还可以组合多个异步任务。相比 `CompletableFuture` 合并结果还需要借助 `Stream`、手动强转，RxKotlin 用统一的 `Observable` 接口 + 操作符就把异步组合表达清楚了。
- **无需书写多线程代码**：不需要手动创建线程、管理锁，只需指定相应的调度策略（`Schedulers`）便可使用多线程的功能，把并发细节交给库去处理，开发者只专注于业务逻辑。
- **兼容性好**：Java 6 及以上的版本都可用（RxJava 甚至适用于 SE 8 之前的 Java）。
- **总结**：这些优势让我们在实现需求的同时，又保持了代码的简洁和优雅——这正是响应式编程区别于传统"回调地狱"的核心价值。

**Q284: 什么是数据流处理？为什么说流式调用比回调方式更优雅？（13.1.2）**

- **"是什么"**：响应式编程除了异步编程模型这个特点外，还有另一个特点——数据流处理。简单来说，就是将数据处理的过程变得像流水线一样：A=>B=>C=>……，后继者不需要阻塞等待结果，而是由前一个处理者将结果通知它。
- **代码示例**：假设下单之后需要给商家及消费者推送消息，用流处理表示如下：
  ```kotlin
  doOrder()
      .map(doNotifyCustomer)
      .map(doNotifyShop)
      .map(doOther)
      // ...
  ```
- **"为什么"优雅**：可以对原始数据进行处理，生成一个新的数据然后传递给下一个处理者，每个处理过程都是异步非阻塞的。流式调用相对于回调的方式优雅得多——不再需要编写大量的嵌套回调函数，代码更加简洁易懂，也更容易排查数据在链路中的流转路径。

**Q285: 为什么响应式编程在以前的 Java Web 生态中应用得并不广泛？（13.1.3）**

- **传统 Servlet 容器的限制**：传统的 Servlet 容器（比如 Tomcat）是同步阻塞的模型（Servlet 3.1 Async IO 之前），底层的线程模型决定了很难做到真正的异步非阻塞。
- **主流 Web 框架支持不好**：主流 Java Web 框架（如 Spring MVC、Spring Boot 等）对响应式的支持不是很好；当然也有全面支持响应式的 Web 框架，比如 Vert.x、Play! Framework——如果使用 Play! 进行 Web 开发，自然就会进行响应式编程，因为它就是全面支持响应式编程的。
- **第三方类库的拖累**：一些主流第三方类库的实现是同步阻塞的，比如连接 MySQL 的驱动包（JDBC），即使上层是异步的，数据库访问这一环还是会阻塞，所以很难使整个系统真正做到异步非阻塞。
- **转折点**：随着 Spring 5 的发布，这种局面将会被打破——Spring 5 开始全面拥抱响应式编程，而且适配 Kotlin，为 Java/Kotlin 生态的响应式 Web 开发提供了框架级基础设施。

**Q286: Spring 5 如何支持响应式编程？Spring WebFlux 是什么？（13.2.1）**

- **背景**：Spring 5 是 2017 年 9 月发布的，引入了很多崭新特性，带来的不仅是技术上的改变，更多的是开发思维上的变化。在 Spring 5 版本以前，Spring 并不是原生支持响应式编程的，主要原因还是底层 Web 容器的限制：Tomcat 等容器在 Servlet 3.1 支持 Async IO 之前做不到真正的异步非阻塞，而集成一些支持异步非阻塞的容器（比如 Netty）又相对比较复杂。
- **容器选择的解放**：Spring 5 发布后，可以轻松选择自己所需的 Web 容器，比如 Tomcat 或 Netty 等，这给 Spring 支持响应式编程提供了底层基础。
- **Spring WebFlux 的引入**：传统的 Spring MVC 并不原生支持响应式编程，所以 Spring 5 引入了一个全新的 Web 框架——**Spring WebFlux**。它主要帮助我们在框架层面实现响应式编程。
- **"是什么"与"为什么"**：WebFlux 不再使用传统基于 Servlet 实现的 `HttpServletRequest` 和 `HttpServletResponse`，而是采用全新的 `ServerRequest` 和 `ServerResponse`（这正是不再依赖 Servlet 容器线程模型的体现）。同时 Spring WebFlux 要求请求的返回数据类型为 `Flux`——一种响应式的数据流类型，类似上一节提到的 `Observable` 类型。

**Q287: 为什么 Spring 5 选择 Reactor 而不是 RxJava 2 作为默认的响应式类库？（13.2.1）**

- **历史包袱问题**：RxJava 库早于 Reactor 库诞生，RxJava 一开始处于响应式编程的探索阶段，当时 Java 并没有提出相应的响应式编程规范，所以 RxJava 2 受限于兼容 RxJava 遗留的历史包袱，有些方面使用起来并不是很方便。
- **Reactor 的设计优势**：Reactor 完全是基于**响应式流规范（Reactive Streams）**设计和实现的类库，同时它的 JDK 最低版本是 JDK 8，所以可以使用 JDK 8 提供的流（Stream）操作。
- **结论**：如果要写更加简洁、更加函数式的代码，Reactor 或许是更好的选择——这符合 Spring 官方对"基于规范、拥抱现代 JDK"的定位，也让 WebFlux 的 API 设计更贴合函数式风格。

**Q288: Mono 与 Flux 是什么？与传统的返回值类型有什么区别？（13.2.1）**

- **Mono（0~1 个元素）**：在传统的 Spring MVC 里，请求的返回直接是一个对象，比如查询一个用户，返回的是 `User` 对象或者 `null`；而在 Spring WebFlux 中使用 `Mono`，它代表 0~1 个元素。比如返回类型为 `Mono<User>`，代表返回流中只有一个数据或者为空数据。
  ```java
  // 传统 Spring MVC：直接返回对象，可能出现 null
  User getUser(Long id) { ... }

  // Spring WebFlux：Mono 显式地表达"0 或 1 个结果"，杜绝隐式 null
  Mono<User> getUser(Long id) { ... }
  ```
- **Flux（0~N 个元素）**：在业务开发中，除了返回一个简单的对象外，有时还会返回集合对象，比如查询一批用户，返回值是 `List<User>`；而在 Spring WebFlux 中则使用 `Flux`，它代表 0~N 个元素。比如返回类型为 `Flux<User>`，代表返回流中有 0~N 个数据。
  ```java
  // 传统：List<User> 是"一次性拉取全部"的集合
  List<User> listUsers() { ... }

  // 响应式：Flux 是"按需流动"的数据流，可被订阅、可异步推送
  Flux<User> listUsers() { ... }
  ```
- **"为什么"用这两个类型**：`Mono`/`Flux` 把"可能为空"和"可能有多条"这两个语义显式地放进类型系统，调用方必须订阅才能真正取到数据，从而倒逼整条链路的异步化，避免了传统 `null` 检查与同步阻塞的隐患。

**Q289: Spring 5 为什么全面适配 Kotlin？这对 Kotlin Web 开发有什么意义？（13.2.2）**

- **背景痛点**：Kotlin 虽然在安卓开发中被广泛采用，但在 Web 开发中却少见身影，一个很重要的原因就是**没有一个好的 Web 框架适配它**。虽然在 Spring 5 之前已经有 Ktor、Javalin 等框架支持 Kotlin，但由于相对比较小众，并没有被广泛应用。
- **Spring 5 带来的机会**：Spring 5 全面适配 Kotlin，将会是 Kotlin 在 Web 开发中大展拳脚的好机会。利用 Spring 完善的生态以及 Kotlin 全面兼容 Java 等特性，可以让很多 Java 开发人员转移到 Kotlin 阵营。
- **"为什么"契合**：流处理在响应式编程中占据着很重要的角色，而 Kotlin 无疑是一个非常好的选择——它原生提供的各种流（集合/序列）操作，结合 Reactor 库来开发响应式应用将会非常便捷。同时 Kotlin 在 Java 的基础上拥抱了很多函数式语言特性（高阶函数、Data Classes 等），可以让开发效率更高，代码简洁而不失优雅。基于 Spring 5 和 Kotlin 编写响应式 Web 应用未来可能是一个趋势。

**Q290: 什么是 Kotlin DSL？如何用它来配置 Bean？（13.2.2）**

- **"是什么"**：Spring 支持 Kotlin DSL，让开发应用时配置更加灵活。Spring 声明 Bean 的方式经历了变迁：最开始用 XML，后来用注解声明，Spring 5 又提供了 Kotlin DSL 这一更简洁的方式。
- **传统注解声明 Bean**：
  ```kotlin
  @Configuration
  class UserBean {
      @Bean //注解声明一个Bean
      val userDao = UserDao()

      @Bean
      val userService = UserService(userDao)
  }
  ```
- **用 Kotlin DSL 声明 Bean**：
  ```kotlin
  import org.springframework.context.support.beans

  val beans = beans {
      bean<UserDao>()
      bean<UserService>()
  }

  beans().initialize(GenericApplicationContext) //将所有的bean进行初始化
  ```
- **"为什么"更好**：使用 Kotlin DSL 使代码变得非常简洁，格式非常统一，更具语义化，而且便于统一管理。总的来看，Spring 并非只是简单地支持 Kotlin，而是结合 Kotlin 的很多特性，带来不一样的编程体验。

**Q291: 什么是函数式路由？它解决了注解路由的什么问题？（13.2.3）**

- **背景**：路由配置是一个 Web 框架的特色，Spring 从最早的 XML 配置到后来的注解配置，现在也支持了函数式路由。注解配置路由虽然很简单、很直接，但随着微服务及模块化程序开发趋势的发展，**路由分模块化统一管理**成为一个需求，而用传统注解方式却很难做到。
- **注解方式的痛点**：如果 Handler 里面的方法一多，路由信息与业务方法掺杂在一起，会导致整个类变得臃肿，不易维护：
  ```kotlin
  @Component
  class UserHandler {
      @RequestMapping(value = "user/getUser", method = [RequestMethod.GET])
      fun getUser() {}

      @RequestMapping(value = "user/addUser", method = [RequestMethod.POST])
      fun addUser() {}

      @RequestMapping(value = "user/updateUser", method = [RequestMethod.PUT])
      fun updateUser() {}
  }

  @Component
  class CustomerHandler {
      @RequestMapping(value = "customer/getCustomer", method = [RequestMethod.GET])
      fun getCustomer() {}

      @RequestMapping(value = "customer/addCustomer", method = [RequestMethod.POST])
      fun addCustomer() {}

      @RequestMapping(value = "customer/updateCustomer", method = [RequestMethod.PUT])
      fun updateCustomer() {}
  }
  ```
- **函数式路由方案**：Spring 5 支持的函数式路由可以解决这个问题，而且结合 Kotlin DSL 语法非常简洁。核心思想是——Handler 类中不再包含路由信息，只保留业务方法；路由统一收敛到独立的 `@Configuration` 路由类中，按模块管理：
  ```kotlin
  import org.springframework.http.MediaType

  @Component
  class UserHandler { //类中没有路由信息
      fun getUser() {}
      fun addUser() {}
      fun updateUser() {}
  }

  @Component
  class CustomerHandler {
      fun getCustomer() {}
      fun addCustomer() {}
      fun updateCustomer() {}
  }

  @Configuration
  class Routes(userHandler: UserHandler, customerHandler: CustomerHandler) {
      @Bean
      fun userRouter() = router {
          "user".nest { //不同类的路由分开管理
              GET("/getUser").nest { //支持REST请求
                  accept(APPLICATION_JSON, userHandler::getUser)
              }
              POST("/addUser").nest {
                  accept(APPLICATION_JSON, userHandler::addUser)
              }
              PUT("/updateUser").nest {
                  accept(APPLICATION_JSON, userHandler::updateUser)
              }
          }
      }

      @Bean
      fun customerRouter() = router {
          "customer".nest {
              GET("/getCustomer").nest {
                  accept(APPLICATION_JSON, customerHandler::getCustomer)
              }
              POST("/addCustomer").nest {
                  accept(APPLICATION_JSON, customerHandler::addCustomer)
              }
              PUT("/updateCustomer").nest {
                  accept(APPLICATION_JSON, customerHandler::updateCustomer)
              }
          }
      }
  }
  ```
- **"为什么"更合理**：乍一看这种方式似乎并没有简单多少，甚至感觉代码更多了。但仔细思考，这是一个更合理的方式——它帮助我们将**配置与业务逻辑分离**，而且统一管理，功能点上也没有很大缺失（依然支持 REST 请求、指定请求及返回的数据类型）。同时这种方式更符合函数式编程的风格，结合 Kotlin DSL 使代码更加精简优雅，可读性也更好。

**Q292: 为什么要使用异步数据库驱动？JDBC 驱动有什么局限？（13.2.4）**

- **全链路异步的原则**：如果一个请求在执行过程中有一部分是同步阻塞的，那么整个应用就不能算异步非阻塞。在实际业务场景中与数据库打交道是无法避免的，所以要想实现整个系统异步非阻塞的架构，**数据库操作也必须是异步非阻塞的**，程序与数据库通信的驱动需要支持异步非阻塞。
- **现状与局限**：Spring 已经支持 MongoDB、Redis 等异步非阻塞的数据源，但很多场景用的是 MySQL。由于我们使用的 JDBC 驱动是同步阻塞的，所以将无法达到全异步非阻塞的架构——这正呼应了 13.1.3 中"第三方类库同步阻塞导致响应式编程推广受阻"的论断。
- **解决思路**：如果既需要使用 MySQL，又希望保证整个系统是异步非阻塞的架构，就需要一个支持异步非阻塞操作的数据库驱动。这正是 jasync-sql 这类驱动存在的意义。

**Q293: postgresql-async 与 jasync-sql 是什么？为什么 jasync-sql 可以在 Kotlin 中使用？（13.2.4）**

- **postgresql-async**：Scala 社区已经有一个全异步的数据库驱动 postgresql-async，它基于 Netty 实现，同时支持 MySQL 和 PostgreSQL，一些开源项目和公司（如 Quill）已经在实际中使用它。
- **无法在 Java/Kotlin 中使用的原因**：这个项目实现中使用了大量 Scala 才有的数据类型，比如 `Future`（与 Java 中的 `Future` 不一样），所以无法在 Java 以及 Kotlin 环境中使用它。更不幸的是，该项目的作者声明已不再维护。
- **jasync-sql 的诞生**：有个 Kotlin 社区人员将这个项目用 Kotlin 重写了一遍，项目叫作 jasync-sql。它基于 Java 8 的 `CompletableFuture`，完全适配 Java 及 Kotlin，与 Spring 5 最新的 WebFlux 也可以结合得很好。当然这只是一个小众项目，没有经历过大量测试及实践的考验，仅供学习，不推荐在大型项目中使用。

**Q294: 如何使用 jasync-sql 与 Spring WebFlux 相结合？（13.2.4）**

- **整体写法与 JDBC 类似**：书写方式跟以前用 JDBC 写数据库操作很类似，也是先创建连接，然后执行数据库操作：
  ```java
  //创建数据库连接
  Connection connection = new MySQLConnection(
      new Configuration(
          "root",
          "localhost",
          3306,
          "123456",
          "test"
      )
  );
  //执行连接
  CompletableFuture<Connection> connectFuture = connection.connect();
  //执行数据库操作
  CompletableFuture<QueryResult> queryResult = connection.sendPreparedStatement(...);
  ```
- **关键差异：返回值类型**：不同的地方在于返回的数据类型——不是简单的 `QueryResult`，而是一个 `CompletableFuture<T>` 类型。它不同于 `Future`，不仅仅是异步执行的，而且**获取值的时候也是非阻塞的**，这正是保证整个查询过程异步非阻塞的关键。
- **桥接到 WebFlux**：`CompletableFuture<T>` 类型的值转化为 WebFlux 所要求的数据类型很容易，比如使用 `Mono.fromFuture` 就可以将一个 `CompletableFuture` 类型的值转换为 `Mono`（或 `Flux`）：
  ```kotlin
  val result: Mono<QueryResult> = Mono.fromFuture(queryResult)
  ```
- **结论**：通过 `Mono.fromFuture` 这个桥梁，就可以使用 jasync-sql 构建基于 MySQL 和 Spring WebFlux 的响应式应用，打通了"MySQL 驱动"这一曾经最顽固的同步阻塞环节。

**Q295: 实战：如何实现一个简化的股票行情实时推送功能？为什么选择 Server Sent Event？（13.3 概览）**

- **技术选型**：基于 Spring WebFlux + Kotlin + MySQL，实现一个简化的股票行情实时推送功能。实现这种需求有很多方式，比如 Ajax 轮询、长轮询、WebSocket 等，但这个例子中使用的是**Server Sent Event（SSE）**。
- **"为什么"选 SSE**：查看股票行情实时行情，往往只需要服务端向客户端不断推送消息即可。SSE 虽然不能像 WebSocket 一样实现双工通信（只能由服务器不断向客户端发送消息），但它有自己的优势——**基于 Http 协议，会自动断开重连等**，实现简单且契合"单向下推"的场景。
- **项目构建与目录结构**：使用 gradle 构建，并加入 13.2 节所讲的 MySQL 异步数据库驱动（jasync-sql），保证使用 MySQL 作为存储 DB 进行数据库操作时也是异步非阻塞的：
  ```
  main
  |____kotlin
  | |____Application.kt
  | |____handler
  | | |____StockHandler.kt
  | |____JasyncPool.kt
  | |____models
  | | |____StockQuotation.kt
  | |____Routes.kt
  | |____service
  | | |____StockService.kt
  | |____resources
  | | |____application.properties
  | | |____static
  | | | |____index.js
  | | |____templates
  | | | |____index.mustache
  ```
- **build.gradle.kts 关键依赖**：加入 jasync-mysql、webflux 等依赖，指定编译版本为 1.8：
  ```kotlin
  dependencies {
      compile(kotlin("stdlib-jdk8"))
      compile("org.jetbrains.kotlin:kotlin-reflect")
      compile("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
      compile("com.github.jasync-sql:jasync-mysql:0.8.32") //添加jasync-mysql依赖
      compile("com.samskivert:jmustache")
      compile("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
      compile("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")
      compile("org.springframework.boot:spring-boot-starter-thymeleaf:$springBootVersion")
  }
  ```

**Q296: 实战：如何定义数据模型与配置异步数据库连接池？（13.3 model 与 DB）**

- **使用 data class 定义模型**：利用 Kotlin 的 data class 一行声明领域模型，无需编写 getter/setter：
  ```kotlin
  data class StockQuotation(
      val id: Long,
      val stock_id: Long,     //股票代码
      val stock_name: String, //股票名称
      val price: Int,         //股票价格
      val time: String        //当前时间
  )

  data class StockQuotationResult(
      val queryTime: String,        //时间
      val stockQuotation: StockQuotation //当前股票信息
  )
  ```
- **配置 jasync-sql 连接池**：因为需要使用 jasync-sql，所以需要配置相应的数据库连接池。数据库配置与连接池配置分离，`ConnectionPool` 统一管理连接复用，避免每次请求都新建连接：
  ```kotlin
  @Component
  class DB {
      private val configuration = Configuration( //数据库配置
          "test",
          "localhost",
          3306,
          "123456",
          "test"
      )

      private val poolConfiguration = PoolConfiguration( //连接池配置
          maxObjects = 100,
          maxIdle = TimeUnit.MINUTES.toMillis(15),
          maxQueueSize = 10_000,
          validationInterval = TimeUnit.SECONDS.toMillis(30)
      )

      val connectionPool = ConnectionPool(factory = MySQLConnectionFactory(
          configuration, poolConfiguration))
  }
  ```
- **"为什么"要配置连接池**：异步非阻塞不意味着不做连接复用；连接池通过 `maxObjects`、`maxIdle`、`maxQueueSize` 等参数在异步场景下控制资源上限，保证高并发推送时数据库连接不会被耗尽。

**Q297: 实战：如何以响应式编程的方式获取数据库数据？（13.3 StockService）**

- **核心代码**：StockService 是整个项目中比较重要的部分——以响应式编程的方式获取所需数据：
  ```kotlin
  @Component
  class StockService(val db: DB) {
      val repeat = Flux.interval(Duration.ofMillis(1000)) //（1）定时循环的Flux，控制定时查询数据库

      fun getStockQuotation(): Flux<StockQuotationResult> {
          val query = "select * from stock_quotation order by id desc limit 1;"

          fun stockQuotation(time: DateTime) =
              Mono.fromFuture(db.connectionPool.sendPreparedStatement(query)) //（2）异步查询数据库，返回CompletableFuture<QueryResult>
                  .map { it.rows.orEmpty().first() }     //（3）只需要第1列数据
                  .map { transRowDataToStockQuotation(it) } //（4）将RowData手动转换为data class
                  .map { StockQuotationResult(time.toString("YYYY-MM-dd hh:mm:ss"), it) } //（5）Mono.fromFuture将CompletableFuture转换为Mono

          return repeat.flatMap {
              insertStockQuotation()
              stockQuotation(DateTime.now())
          }
      }

      private fun transRowDataToStockQuotation(rowData: RowData): StockQuotation {
          return StockQuotation(
              rowData.get("id").toString().toLong(),
              rowData.get("stock_id").toString().toLong(),
              rowData.get("stock_name").toString(),
              rowData.get("price").toString().toInt(),
              (rowData.get("time") as LocalDateTime).toString("YYYY-MM-dd hh:mm:ss")
          )
      }

      private fun insertStockQuotation() { //（6）模拟定时生成股票价格
          val max = 74000
          val min = 72000
          val price = Random().nextInt(max - min) + min
          val query = "insert into stock_quotation (stock_id, stock_name, price, time) values (...)"
          db.connectionPool.sendPreparedStatement(query)
      }
  }
  ```
- **逐步解读（重点在第 2 步和第 5 步）**：
  1. `Flux.interval` 创建了一个定时循环的 Flux，用来控制模拟定时循环查询数据库（每 1000ms 一次）；
  2. 利用数据库连接池从数据库查询数据，返回的数据类型是 `CompletableFuture<QueryResult>`；
  3. 因为只需要第 1 列数据，所以使用 `it.rows.orEmpty().first()` 获取第 1 列数据；
  4. 将 `RowData` 类型数据转化为自定义的 data class 对象，这里没有使用第三方 ORM 框架，需要自己手动转换；
  5. 使用 `Mono.fromFuture` 将一个 `CompletableFuture<T>` 类型数据转换为 `Mono<T>` 类型；
  6. `insertStockQuotation` 模拟定时生成股票价格并写入数据库。
- **"为什么"这两个步骤是重点**：`CompletableFuture` 相比 `Future` 一个很大的优势就是它获取值的时候不必阻塞等待，这便保证了整个查询过程是异步非阻塞的；同时 Reactor 提供了将 `CompletableFuture` 转化为 `Mono` 的方法，这样就能完全适配 Spring WebFlux 所要求的返回数据类型格式。

**Q298: 实战：如何配置函数式 Router 并通过 SSE 推送股票行情？（13.3 Routes）**

- **使用 Spring 5 最新的函数式 Router**：本例使用 Spring 5 最新的函数式 Router（当然也可以使用传统基于 Spring MVC 的注解方式）：
  ```kotlin
  @Configuration
  class Routes(val userHandler: StockHandler) {
      @Bean
      fun Router() = router {
          accept(MediaType.TEXT_HTML).nest {
              GET("/") { ok().render("index") }
          }
          "/api".nest { //api开头的请求
              GET("/getStockQuotation").nest {
                  accept(TEXT_EVENT_STREAM, userHandler::getStockQuotation)
              }
          }
          resources("/**", ClassPathResource("static/")) //静态文件访问路径
      }.filter { request, next ->
          next.handle(request).flatMap {
              if (it is RenderingResponse) RenderingResponse.from(it).build() else it
          }
      }
  }
  ```
- **关键点**：`/api/getStockQuotation` 路由通过 `accept(TEXT_EVENT_STREAM, ...)` 指定响应类型为 Server Sent Event；Controller/Handler 中通过 `ok().bodyToServerSentEvents(stockService.getStockQuotation())` 把 `Flux` 数据流以 SSE 格式输出给客户端：
  ```kotlin
  ok().bodyToServerSentEvents(stockService.getStockQuotation())
  ```
- **"为什么"这么设计**：用这种方式定义 router 相对传统方式来说，更加语义化也更容易管理，而且 Router 的 `filter` 还支持对 Response 进行不同的统一处理（比如这里对 `RenderingResponse` 的特殊处理），这是注解路由难以做到的。

**Q299: 实战：前端如何使用 Server Sent Event 接收实时推送？（13.3 前端）**

- **模板与 JS 的结构**：虽然目前很多项目多采用前后端分离架构，但为了更方便演示示例、让读者更容易搭建，前端页面渲染采用了 Mustache 模板引擎。前端代码包括模板 `index.mustache` 与 JS 文件 `index.js` 两个部分。
- **模板（index.mustache）**：页面只放一个空的展示容器，由 JS 动态生成列表项：
  ```html
  <!DOCTYPE html>
  <html lang="en">
  <head>
      <meta charset="UTF-8">
      <title>股票行情</title>
      <script src="index.js"></script>
      <style>
          #stockQuotations { margin: 0 auto; text-align: center; }
          #stockQuotations li { list-style-type: none; margin-bottom: 5px; }
      </style>
  </head>
  <body>
      <div id="stockQuotations"></div>
  </body>
  </html>
  ```
- **JS（index.js）**：这里需要使用 `EventSource`（SSE 的客户端 API），而不是常见的 Ajax 方式请求；同时用 `eventSource.onmessage` 来监听返回的数据进行处理：
  ```javascript
  var eventSource = new EventSource("/api/getStockQuotation");
  eventSource.onmessage = function(e) {
      var li = document.createElement("li");
      var data = JSON.parse(e.data);
      li.innerText = "股票代码: " + data.stockQuotation.stock_id +
                     " 股票名称:" + data.stockQuotation.stock_name +
                     " 价格: " + data.stockQuotation.price;
      document.getElementById("stockQuotations").appendChild(li);
  }
  ```
- **"为什么"用 EventSource 而不是 Ajax**：SSE 是服务端单向下推的协议，`EventSource` 会自动建立长连接、自动重连，比 Ajax 轮询更高效也更及时。
- **注意事项与运行**：需要留意浏览器是否支持 Server Sent Event 这种传输格式——当前 IE 及 Edge 的所有版本都不支持，测试最好使用其他浏览器。最后运行程序，通过浏览器打开 http://localhost:8282/ 即可看到实时滚动的股票行情界面。源码已上传到 GitHub（github.com/godpan/reactive-spring-kotlin-app），读者可自行查看。

**Q300: 本章小结：这一章包含了哪几个核心知识点？（13.4）**

- **（1）响应式编程**：了解什么是响应式编程的关键，响应式编程相对于传统编程范式的优势，同时如何利用一些第三方类库来帮助我们在程序中进行响应式开发。
- **（2）Spring 5 支持响应式编程**：简单了解 Spring 5 支持响应式编程的背景，同时介绍了它的一些新特性，比如函数式路由以及适配 Kotlin 等。
- **（3）异步非阻塞 MySQL 数据库驱动**：介绍了一个基于 Netty 且用 Kotlin 实现的全异步非阻塞的 MySQL 数据库驱动——jasync-sql，以及如何在 Spring WebFlux 中使用它。
- **（4）Spring WebFlux + Kotlin 示例**：了解如何用 Kotlin 使用 Spring WebFlux 进行响应式 Web 应用开发。亲手写一个 Demo，能帮助更好地理解相关知识点，加深印象——知识只有串起来并动手实践，才能真正内化。
