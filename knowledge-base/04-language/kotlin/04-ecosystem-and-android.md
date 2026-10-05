# 04 生态与应用

> Kotlin 学习笔记之四，关注语言特性在 Android 和服务端生态中的应用。章节号跨四个文件连续（第 1～12 章）。

## 目录

- 第 11 章 Android 生态——Q1～Q4

## 第 11 章 Android 生态

**Q1: [learning] Android KTX 的 `SharedPreferences.edit {}`、`lifecycleScope` 和 `viewModelScope` 除了缩短写法，还分别管理什么行为？**

Android KTX 是 Android Jetpack 和其他 Android 库提供的一组 Kotlin 扩展。它们有的只封装常用调用，有的还执行提交策略或生命周期取消，因此要按具体 API 判断，而不能把 KTX 一概称为语法糖。

1. **对象创建封装：**`String.toUri()` 将字符串解析为 `Uri`，`bundleOf()` 以键值参数创建 `Bundle`。它们主要缩短常见对象创建代码，本身不提供生命周期管理。
2. **编辑与提交：**`SharedPreferences.edit {}` 创建 `Editor`，并在带接收者 lambda 中执行修改。`commit` 参数默认是 `false`，所以默认调用 `apply()`。这会立即更新进程内的 `SharedPreferences` 值，并异步写盘。显式传 `commit = true` 会同步写盘并返回写入是否成功，但 KTX lambda 本身返回 `Unit`，不会把这个布尔结果交给调用方。`apply()` 也可能在 Activity 或 Service 生命周期切换时等待未完成写盘，不能把它理解成绝不会阻塞主线程。
3. **生命周期协程：**`lifecycleScope` 中启动的协程会在所属 Lifecycle 销毁时取消。Fragment 页面视图任务使用 `viewLifecycleOwner.lifecycleScope`，避免视图销毁后继续更新旧 View。`viewModelScope` 在对应 ViewModel 被清除时取消，适合由 ViewModel 持有的状态计算。作用域的生命周期所有者不同，不能互换使用。
4. **判断方法：**使用一个 KTX API 前检查它封装的原始 API、默认参数和附加管理行为。是否缩短语法与是否改变提交、线程或取消语义是两个问题。行为依据为 Android KTX 与 Lifecycle 官方文档。

下面的片段展示 `SharedPreferences.edit {}` 的接收者 lambda。原始写法显式创建 Editor、写入值并调用 `apply()`，KTX 写法默认仍是异步提交：

```kotlin
// 原始 SharedPreferences API
val editor = preferences.edit()
editor.putInt(KEY_COUNT, 1)
editor.apply()

// core-ktx，默认 commit = false，执行 apply()
preferences.edit {
    putInt(KEY_COUNT, 1)
}
```

**Q2: [learning] Kotlin 的扩展函数和扩展属性在 Android JVM 项目中怎样暴露给 Java？它们会修改原有 Android 类吗？**

Kotlin 扩展不会向 `String`、`SharedPreferences` 等原有 Java 类注入成员。扩展函数通常编译成带接收者参数的静态方法，扩展属性编译成静态 getter 或 setter。Java 可以调用生成的文件门面方法，但调用形式不如 Kotlin 直接，原有 Android Java API 始终可用。

1. **接收者变成显式参数：**Kotlin 写作 `preferences.edit { ... }`，编译后 Java 侧调用静态方法，并把 `preferences` 作为接收者参数传入。函数具体所在的文件门面类名取决于扩展声明的 Kotlin 文件和包名。
2. **静态解析：**扩展按编译期可见的接收者类型和作用域解析，不是运行时虚方法覆写。即使某个子类声明了同名扩展，也不能据此假定通过基类类型调用时会动态分派到子类版本。
3. **Java 互操作边界：**Java 可见性还受生成方法名、可见性、空值类型映射和默认参数桥接方式影响。不要猜文件门面名称，也不要认为 Kotlin 默认参数会自动成为 Java 重载。需要稳定 Java API 时应显式定义合适的 JVM 声明或 Java 包装方法。
4. **原 API 保留：**扩展为调用点增加 Kotlin 语法入口，不会改变平台类定义、既有 Java 调用路径或对象布局。Java 代码仍可直接调用 `SharedPreferences.edit()` 等原始 API。

**Q3: [learning] `const val SOUND_RES_CLICK = R.raw.pet_sound_click` 为什么会编译失败？R 字段是否总是编译期常量？**

`const val` 的初始化器必须是 Kotlin 编译器认可的编译期常量。`R.raw.pet_sound_click` 是否满足条件取决于当前 Android Gradle Plugin（AGP）生成的 R 字段是否为 `final`：库模块的 R 字段不是编译期常量，AGP 8.0 起应用和测试模块默认也生成非 final 字段，所以不能把 R 资源 ID 一概用于 `const val`。

来源案例：雅迪车机 Launcher 的 `application/Launcher/src/main/java/com/yadea/launcher/pet/PetConfig.kt` 曾遇到该编译错误。该案例说明项目构建形态会影响结果，不代表所有 AGP 版本的应用模块都必然失败：

```kotlin
object PetConfig {
    // R 字段为非 final 时，初始化器不是编译期常量。
    // const val SOUND_RES_CLICK = R.raw.pet_sound_click

    // 普通 val 可在初始化时读取生成的资源 ID。
    val SOUND_RES_CLICK = R.raw.pet_sound_click
}
```

1. **`const val` 条件：**它要求编译器能在编译期确定初始化值，并会把该值内联到使用处。普通 `val` 不要求初始化器满足这一条件。
2. **R 字段版本：**AGP 8.0 至 8.x 中，`android.nonFinalResIds` 的默认值为 `true`，应用与测试模块的 R 字段默认非 final。显式设为 `false` 时，应用与测试模块生成 final 字段。AGP 9.0 起由 `android.enableAppCompileTimeRClass` 控制应用是否按非 final R 字段编译，默认值从 `false` 改为 `true`。设为 `false` 可保留 AGP 8.13 的编译行为。库模块的 R 字段始终非 final，因为最终资源 ID 要在使用该库的应用或测试打包时确定。旧版 AGP 或覆写默认值时，应用模块可能生成 final 字段，因此同一写法会因模块和构建配置不同而表现不同。相关依据为 Android Gradle Plugin 8.0 和 9.0.1 release notes。
3. **修改方式：**资源引用使用普通 `val`，需要编译期常量的 `const val` 留给字面量或其他合法 const 表达式。AGP 8.x 中 `android.nonFinalResIds=false` 可让应用与测试生成 final R 字段。AGP 9.0 起应核对 `android.enableAppCompileTimeRClass`，其默认 `true` 让应用按非 final R 编译，显式设为 `false` 会保留旧编译行为。库模块仍不能依赖 final R 字段。非 final R 有利于增量编译与资源处理优化，不要仅为消除一个 `const` 编译错误而改变项目构建策略。
4. **检查顺序：**先确认报错模块是应用、测试还是库，再核对 AGP 版本和 `android.nonFinalResIds`。IDE 与命令行表现不一致时，还要确认二者解析的是同一变体和同一生成的 R 类，不能仅凭 IDE 标红或一次构建成功认定字段属性。

**Q4: [learning] `kotlin-compiler-embeddable` 下载缓慢时，手动把 JAR 放进 Gradle 缓存为何不可靠？如何处理依赖缓存和离线构建？**

只把一个 JAR 手动复制到 Gradle 缓存目录，不保证 Gradle 能据此解析依赖。默认的 `GRADLE_USER_HOME` 是用户目录下的 `.gradle`，可由环境或命令行配置改变。Gradle 依赖缓存同时包含制品文件和仓库相关的元数据，并与 Gradle 缓存版本、仓库和依赖坐标有关。正确的离线做法是先由 Gradle 正常解析完整依赖，或按兼容 Gradle 版本复制完整模块缓存。

1. **先核对依赖声明：**确认 `kotlin-compiler-embeddable` 的 group、artifact、版本和仓库配置与当前构建插件及 Kotlin 插件要求一致。版本不匹配的 JAR 不能替代所需制品，私自替换还可能造成编译器与插件不兼容。
2. **排查下载路径：**检查 Maven 仓库是否可达、仓库顺序和凭据配置是否正确。团队长期使用时应在 Gradle 仓库配置中使用可信镜像或内部仓库，而不是依赖成员机器上的手工缓存。
3. **准备离线缓存：**在可联网环境使用同一构建解析完整依赖，再以 `--offline` 执行。离线模式只使用已缓存模块，缺少任何依赖元数据或制品都会导致解析失败。
4. **复制缓存的边界：**Gradle 支持在兼容的 Gradle 版本间复制完整 `caches/modules-<版本>` 缓存结构，但不应只挑一个 JAR，也不要复制其中的 `*.lock` 或 `gc.properties` 文件。Gradle 文档还要求缓存放在目标环境对应的缓存目录结构下。
5. **临时手工处理：**手工放置 JAR 只有在同时满足版本、校验内容和 Gradle 缓存元数据要求时才可能被复用，属于依赖缓存实现细节的临时处理。它不应代替仓库配置或成为团队构建步骤。缓存目录与复制规则依据 Gradle Dependency Caching 文档。

## 待补主题

- 第 11 章：Kotlin 与 Android 生命周期、状态管理及 Java 互操作注解。
- 第 12 章：Ktor、协程与 Flow 在服务端开发中的应用。
