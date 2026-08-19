**Q1: 什么是响应式编程？它是在怎样的背景下诞生的？（本章主线）**

- **编程范式的发展脉络**：随着计算机软件行业的发展，诞生了各种各样的编程语言，也产生了多种编程范式——从最初的命令式编程，到后面的面向对象编程及函数式编程，现在响应式编程也流行起来了。
- **响应式编程的特殊之处**：与前几种范式不同，响应式编程并不建立在特定的语言基础上，很多语言（如 Java、Kotlin、Scala 等）都可以进行响应式编程，尤其是在 Web 开发上的应用变得越来越流行。
- **本章的目的**：带读者了解响应式编程的核心特点，并介绍一个适配 Kotlin 且原生支持响应式开发的 Web 框架——Spring 5，最后动手实现一个简单的响应式 Web 应用。
- **"为什么"要重视响应式编程**：Web 场景下大量操作是 IO 密集型的（查询数据库、调用远程接口），传统同步阻塞模型会浪费宝贵的线程资源，而响应式编程以"异步非阻塞 + 数据流"的方式从根本上改变资源利用方式，这是它流行的根本原因。

**Q2: 为什么同步阻塞的写法不适合下单这类业务场景？（13.1 提出的核心痛点）**

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

**Q3: 如何使用 CompletableFuture 实现异步非阻塞？（13.1.1）**

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

**Q4: 什么是 RxKotlin？如何利用它进行响应式编程？（13.1.2）**

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

**Q5: 使用 RxKotlin 进行响应式编程带来了哪些优势？（13.1.2）**

- **将异步编程变得优雅、直观**：不用对每个异步请求都执行一个回调，还可以组合多个异步任务。相比 `CompletableFuture` 合并结果还需要借助 `Stream`、手动强转，RxKotlin 用统一的 `Observable` 接口 + 操作符就把异步组合表达清楚了。
- **无需书写多线程代码**：不需要手动创建线程、管理锁，只需指定相应的调度策略（`Schedulers`）便可使用多线程的功能，把并发细节交给库去处理，开发者只专注于业务逻辑。
- **兼容性好**：Java 6 及以上的版本都可用（RxJava 甚至适用于 SE 8 之前的 Java）。
- **总结**：这些优势让我们在实现需求的同时，又保持了代码的简洁和优雅——这正是响应式编程区别于传统"回调地狱"的核心价值。

**Q6: 什么是数据流处理？为什么说流式调用比回调方式更优雅？（13.1.2）**

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

**Q7: 为什么响应式编程在以前的 Java Web 生态中应用得并不广泛？（13.1.3）**

- **传统 Servlet 容器的限制**：传统的 Servlet 容器（比如 Tomcat）是同步阻塞的模型（Servlet 3.1 Async IO 之前），底层的线程模型决定了很难做到真正的异步非阻塞。
- **主流 Web 框架支持不好**：主流 Java Web 框架（如 Spring MVC、Spring Boot 等）对响应式的支持不是很好；当然也有全面支持响应式的 Web 框架，比如 Vert.x、Play! Framework——如果使用 Play! 进行 Web 开发，自然就会进行响应式编程，因为它就是全面支持响应式编程的。
- **第三方类库的拖累**：一些主流第三方类库的实现是同步阻塞的，比如连接 MySQL 的驱动包（JDBC），即使上层是异步的，数据库访问这一环还是会阻塞，所以很难使整个系统真正做到异步非阻塞。
- **转折点**：随着 Spring 5 的发布，这种局面将会被打破——Spring 5 开始全面拥抱响应式编程，而且适配 Kotlin，为 Java/Kotlin 生态的响应式 Web 开发提供了框架级基础设施。

**Q8: Spring 5 如何支持响应式编程？Spring WebFlux 是什么？（13.2.1）**

- **背景**：Spring 5 是 2017 年 9 月发布的，引入了很多崭新特性，带来的不仅是技术上的改变，更多的是开发思维上的变化。在 Spring 5 版本以前，Spring 并不是原生支持响应式编程的，主要原因还是底层 Web 容器的限制（见 Q7：传统 Servlet 容器的同步阻塞模型，且集成 Netty 等异步容器又相对比较复杂）。
- **容器选择的解放**：Spring 5 发布后，可以轻松选择自己所需的 Web 容器，比如 Tomcat 或 Netty 等，这给 Spring 支持响应式编程提供了底层基础。
- **Spring WebFlux 的引入**：传统的 Spring MVC 并不原生支持响应式编程，所以 Spring 5 引入了一个全新的 Web 框架——**Spring WebFlux**。它主要帮助我们在框架层面实现响应式编程。
- **"是什么"与"为什么"**：WebFlux 不再使用传统基于 Servlet 实现的 `HttpServletRequest` 和 `HttpServletResponse`，而是采用全新的 `ServerRequest` 和 `ServerResponse`（这正是不再依赖 Servlet 容器线程模型的体现）。同时 Spring WebFlux 要求请求的返回数据类型为 `Flux`——一种响应式的数据流类型，类似上一节提到的 `Observable` 类型。

**Q9: 为什么 Spring 5 选择 Reactor 而不是 RxJava 2 作为默认的响应式类库？（13.2.1）**

- **历史包袱问题**：RxJava 库早于 Reactor 库诞生，RxJava 一开始处于响应式编程的探索阶段，当时 Java 并没有提出相应的响应式编程规范，所以 RxJava 2 受限于兼容 RxJava 遗留的历史包袱，有些方面使用起来并不是很方便。
- **Reactor 的设计优势**：Reactor 完全是基于**响应式流规范（Reactive Streams）**设计和实现的类库，同时它的 JDK 最低版本是 JDK 8，所以可以使用 JDK 8 提供的流（Stream）操作。
- **结论**：如果要写更加简洁、更加函数式的代码，Reactor 或许是更好的选择——这符合 Spring 官方对"基于规范、拥抱现代 JDK"的定位，也让 WebFlux 的 API 设计更贴合函数式风格。

**Q10: Mono 与 Flux 是什么？与传统的返回值类型有什么区别？（13.2.1）**

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

**Q11: Spring 5 为什么全面适配 Kotlin？这对 Kotlin Web 开发有什么意义？（13.2.2）**

- **背景痛点**：Kotlin 虽然在安卓开发中被广泛采用，但在 Web 开发中却少见身影，一个很重要的原因就是**没有一个好的 Web 框架适配它**。虽然在 Spring 5 之前已经有 Ktor、Javalin 等框架支持 Kotlin，但由于相对比较小众，并没有被广泛应用。
- **Spring 5 带来的机会**：Spring 5 全面适配 Kotlin，将会是 Kotlin 在 Web 开发中大展拳脚的好机会。利用 Spring 完善的生态以及 Kotlin 全面兼容 Java 等特性，可以让很多 Java 开发人员转移到 Kotlin 阵营。
- **"为什么"契合**：流处理在响应式编程中占据着很重要的角色，而 Kotlin 无疑是一个非常好的选择——它原生提供的各种流（集合/序列）操作，结合 Reactor 库来开发响应式应用将会非常便捷。同时 Kotlin 在 Java 的基础上拥抱了很多函数式语言特性（高阶函数、Data Classes 等），可以让开发效率更高，代码简洁而不失优雅。基于 Spring 5 和 Kotlin 编写响应式 Web 应用未来可能是一个趋势。

**Q12: 什么是 Kotlin DSL？如何用它来配置 Bean？（13.2.2）**

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

**Q13: 什么是函数式路由？它解决了注解路由的什么问题？（13.2.3）**

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

**Q14: 为什么要使用异步数据库驱动？JDBC 驱动有什么局限？（13.2.4）**

- **全链路异步的原则**：如果一个请求在执行过程中有一部分是同步阻塞的，那么整个应用就不能算异步非阻塞。在实际业务场景中与数据库打交道是无法避免的，所以要想实现整个系统异步非阻塞的架构，**数据库操作也必须是异步非阻塞的**，程序与数据库通信的驱动需要支持异步非阻塞。
- **现状与局限**：Spring 已经支持 MongoDB、Redis 等异步非阻塞的数据源，但很多场景用的是 MySQL。由于我们使用的 JDBC 驱动是同步阻塞的，所以将无法达到全异步非阻塞的架构——这正呼应了 13.1.3 中"第三方类库同步阻塞导致响应式编程推广受阻"的论断。
- **解决思路**：如果既需要使用 MySQL，又希望保证整个系统是异步非阻塞的架构，就需要一个支持异步非阻塞操作的数据库驱动。这正是 jasync-sql 这类驱动存在的意义。

**Q15: postgresql-async 与 jasync-sql 是什么？为什么 jasync-sql 可以在 Kotlin 中使用？（13.2.4）**

- **postgresql-async**：Scala 社区已经有一个全异步的数据库驱动 postgresql-async，它基于 Netty 实现，同时支持 MySQL 和 PostgreSQL，一些开源项目和公司（如 Quill）已经在实际中使用它。
- **无法在 Java/Kotlin 中使用的原因**：这个项目实现中使用了大量 Scala 才有的数据类型，比如 `Future`（与 Java 中的 `Future` 不一样），所以无法在 Java 以及 Kotlin 环境中使用它。更不幸的是，该项目的作者声明已不再维护。
- **jasync-sql 的诞生**：有个 Kotlin 社区人员将这个项目用 Kotlin 重写了一遍，项目叫作 jasync-sql。它基于 Java 8 的 `CompletableFuture`，完全适配 Java 及 Kotlin，与 Spring 5 最新的 WebFlux 也可以结合得很好。当然这只是一个小众项目，没有经历过大量测试及实践的考验，仅供学习，不推荐在大型项目中使用。

**Q16: 如何使用 jasync-sql 与 Spring WebFlux 相结合？（13.2.4）**

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

**Q17: 实战：如何实现一个简化的股票行情实时推送功能？为什么选择 Server Sent Event？（13.3 概览）**

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

**Q18: 实战：如何定义数据模型与配置异步数据库连接池？（13.3 model 与 DB）**

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

**Q19: 实战：如何以响应式编程的方式获取数据库数据？（13.3 StockService）**

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

**Q20: 实战：如何配置函数式 Router 并通过 SSE 推送股票行情？（13.3 Routes）**

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

**Q21: 实战：前端如何使用 Server Sent Event 接收实时推送？（13.3 前端）**

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

**Q22: 本章小结：这一章包含了哪几个核心知识点？（13.4）**

- **（1）响应式编程**：了解什么是响应式编程的关键，响应式编程相对于传统编程范式的优势，同时如何利用一些第三方类库来帮助我们在程序中进行响应式开发。
- **（2）Spring 5 支持响应式编程**：简单了解 Spring 5 支持响应式编程的背景，同时介绍了它的一些新特性，比如函数式路由以及适配 Kotlin 等。
- **（3）异步非阻塞 MySQL 数据库驱动**：介绍了一个基于 Netty 且用 Kotlin 实现的全异步非阻塞的 MySQL 数据库驱动——jasync-sql，以及如何在 Spring WebFlux 中使用它。
- **（4）Spring WebFlux + Kotlin 示例**：了解如何用 Kotlin 使用 Spring WebFlux 进行响应式 Web 应用开发。亲手写一个 Demo，能帮助更好地理解相关知识点，加深印象——知识只有串起来并动手实践，才能真正内化。
