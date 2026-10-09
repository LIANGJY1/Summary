# Language — 编程语言知识域（第二级分发）

> ROUTING.md 主判据链第 1 条的落点：凡「编程语言本身」的知识先进本目录，再按本文件分发。本文件是二级分发与新语言准入的单一事实源；维护者 session-to-knowledge。语言子目录变动只登记本文件「目录」节，不回填 ROUTING.md（路由表只到 language/ 粒度）。

## 边界

**收**：

- 某门语言的语法、类型系统、集合、并发模型、泛型/反射/元编程等语言特性与惯用法
- 该语言标准库与官方工具链行为（编译器、构建对语言特性的处理）
- 以「学会这门语言」为骨架的学习体系笔记，可含生态应用章（如 `kotlin/04-ecosystem-and-android.md`）；练习/实验项目（如 `C++/cpp_practice_project/`）

**不收**（退回 ROUTING.md 判据链 2–6）：

- 把语言名换掉结论仍成立的知识——通用设计思想 → design-principles.md；第三方框架/SDK 设计 → sdk-design.md；Android UI 坑 → android-ui.md
- 业务逻辑与工程模式，**即使示例代码是本语言写的**：判定看知识内核，不看代码语言——如「订阅监听后必须对账初值」换任何语言都成立，属工程模式不属 Kotlin 语法，归宿是项目文档或 design-principles.md，不是本域
- 同一主题两边都够格时各写各的角度、互相不复制（例：Kotlin 协程的挂起机制 → `kotlin/`；跨库并发设计思想条目 → sdk-design.md）

## 分发策略（三步判定，按序执行）

1. **替换语言测试**（再验入口）：把知识中的语言名换成任意另一门语言，结论仍成立 → 不是语言知识，退回 ROUTING.md；只在特定语言下成立 → 进第 2 步。
2. **复习场景定主语言**：六个月后要用这条知识时，我大概率在写哪门语言，就分发到哪门语言的目录。
   - 互操作/迁移类按消费语言归档：Kotlin 调 Java 的坑 → `kotlin/`（谁在写代码归谁）。
   - 对称的语言对比 → 归结论主要服务的语言；确实无主 → 问用户，或经用户确认建 `跨语言/` 子目录并在「目录」节登记。
3. **落点判定**（动态）：
   - 语言子目录已存在 → 进入并按「文件归并」落文件。
   - 不存在 → 新语言准入：用户确认开启该语言学习线，或手头已有 ≥2 条该语言知识 → 建目录（命名用官方惯用写法：`java/` `kotlin/`；带符号习用名保留：`C++/`）→ 在「目录」节登记一行。
   - 孤例（单条知识、不成学习线）→ 不强建目录，问用户去处。

## 写作规范

文件命名、Q 编号、结构、表达和质量验收统一遵守 [WRITING-GUIDE.md](../WRITING-GUIDE.md)。本 README 只规定语言知识的边界、分发和目录登记。

## 目录（第二级动态注册表）

| 子目录 | 语言 | 现状 |
|---|---|---|
| `C++/` | C++ | 编号主题笔记见 `C++/README.md`：语言基础、编译链接、POSIX、函数与类型、数组与字符串、自定义类型、指针与内存；另含练习项目 |
| `java/` | Java | 编号主题笔记（2026-10-05 按主题边界重构）：01-oop-basics / 02-strings-and-collections / 03-annotations / 04-generics / 05-reflection / 06-concurrency-and-streams / 07-exceptions-and-io / 08-serialization / 09-jvm-runtime |
| `kotlin/` | Kotlin | 四篇主题笔记：01-syntax-basics / 02-objects-and-types / 03-functional-and-concurrency / 04-ecosystem-and-android；另保留 [Kotlin 学习笔记完整版](./kotlin/kotlin-learning-notes-complete.md)（300 题）。原 `kotlin_study_notes.md` 与该完整版重复，已去重，题目以完整版为唯一保留副本 |
