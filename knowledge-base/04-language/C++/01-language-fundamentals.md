# C++ 语言基础

> 本文介绍 C++ 的词法与对象基础、编程范式、命名空间、程序入口和基础 I/O。

**Q1: [learning] 什么是词法记号（token）？标识符与它是什么关系？**

词法记号（token）是词法分析阶段识别的基本单元，包括关键字、标识符、字面量、运算符和标点符号。标识符是其中一类，用于命名变量、函数和类型等实体。

**Q2: [learning] 字符串字面量在翻译中如何处理？**

双引号括起的内容作为程序数据参与后续翻译。

**Q3: [learning] 注释在翻译中如何处理？**

注释不作为程序语句执行，并在翻译阶段被替换为空格。

1. `//` 注释延续到当前行末。
2. `/* ... */` 注释延续到结束标记；普通块注释不支持嵌套。

**Q4: [learning] int carrots; 是声明还是定义？**

在函数块内，`int carrots;` 定义一个具有自动存储期的 `int` 对象，其生命周期受所在块的执行范围约束。编译器不一定把对象实际放在栈上。

1. **定义：**声明实体，并在需要时为对象提供存储。
2. **声明：**引入名称和类型信息。`extern int carrots;` 通常声明在其他位置定义的对象。

**Q5: [learning] carrots = 25; 做了什么，赋值表达式还有什么结果？**

赋值将右侧值转换为左侧对象的类型并更新该对象。内建赋值表达式的结果是左侧对象的左值，因此 `a = b = 25` 按 `a = (b = 25)` 求值。

**Q6: [learning] 编程范式是什么，C++ 支持哪些范式？**

编程范式是组织程序结构与表达计算过程的一类方法。C++ 支持多种范式，可在同一程序中组合使用。

1. **过程式：**以函数和顺序控制组织操作步骤。
2. **面向对象：**以类封装状态和操作，并通过对象表达程序实体。
3. **泛型：**通过模板编写适用于多种类型的算法和容器，并在编译期检查类型。
4. **函数式风格：**使用函数对象、lambda 表达式和算法组合等方式组织计算。C++ 并非纯函数式语言。

**Q7: [learning] C++ 中的类和对象分别是什么？**

类定义用户自定义类型及其成员；对象是该类型的实体，具有相应的生命周期和状态。静态数据成员属于类，不会为每个对象分别存储一份。

**Q8: [learning] 命名空间是什么，何时使用？**

命名空间是为名称提供独立作用域的语言机制，用于组织相关函数、类型和变量，并避免不同模块中的常见名称冲突。大型工程、库接口或多个组件都定义 `init`、`Device` 等通用名称时，可以用命名空间标明归属。

例如，启动相关函数可放在 `android::init` 命名空间中，通过限定名调用：

```cpp
namespace android {
namespace init {
int first_stage_main() {
    return 0;
}
}
}

int main() {
    return android::init::first_stage_main();
}
```

命名空间用于编译期名称组织，不是访问控制机制；小范围局部实现不必为每个函数单独创建命名空间。

**Q9: [done] 命名空间如何区分同名实体？**

同名实体位于不同命名空间时，用限定名指定目标实体。比如 Android 与厂商模块都定义 `init::start()`，可分别写成 `android::init::start()` 和 `vendor::init::start()`。

```cpp
namespace android { namespace init { int start() { return 1; } } }
namespace vendor { namespace init { int start() { return 2; } } }

int main() {
    return android::init::start() + vendor::init::start();
}
```

`::` 是作用域解析运算符。限定名从全局作用域开始时可写前导 `::`，如 `::android::init::start()`。

**Q10: [learning] 命名空间外的定义如何与声明关联？**

声明和定义必须指向同一命名空间中的同一实体。定义写在命名空间外时，用限定名标明其归属；名称或命名空间不匹配时，声明与定义无法关联，通常会在链接阶段报错。

```cpp
namespace android {
namespace init {
int start();
}
}

int android::init::start() {
    return 0;
}
```

这里声明与定义都指向 `android::init::start`。

**Q11: [learning] 为什么 <iostream> 与 <unistd.h> 的扩展名不同？**

这是标准来源和命名约定不同造成的。扩展名本身不决定文件是否为头文件。

1. `<iostream>` 是 ISO C++ 标准库头文件，标准名称不带扩展名，其中的标准库名称属于 `std` 命名空间。
2. `<unistd.h>` 是 POSIX 头文件，不属于 ISO C++ 标准库。只有提供相应 POSIX 接口的平台才能使用。

**Q12: [learning] C 头文件在 C++ 中有哪些对应写法？**

C++ 为多数 C 标准库头文件提供了去掉 `.h` 并添加 `c` 前缀的对应形式，例如 `<stdio.h>` 对应 `<cstdio>`，`<math.h>` 对应 `<cmath>`。

使用 C++ 形式时，可通过 `std::` 访问对应标准库名称。旧式 `.h` 头文件在不少实现中仍可用，但其名称是否同时暴露在全局命名空间不能作为可移植保证。POSIX 头文件如 `<unistd.h>` 没有标准 C++ 对应形式。

**Q13: [learning] 为什么包含 <iostream> 后仍须写 std::cout？**

`#include` 使声明可见，但不会移除其所属命名空间。`cout` 属于 `std`，因此应写作 `std::cout` 或通过 using 声明引入。

`using namespace std;` 会使非限定名称查找考虑 `std` 中的声明。它不能代替 `#include`，还可能引入名称歧义。

**Q14: [learning] 为什么不应在头文件中写 using namespace std？**

在头文件或命名空间作用域中使用 `using namespace std;` 会影响后续非限定名称查找，增加名称冲突或歧义的风险。需要少数名称时，应使用限定名或 using 声明。

1. **限定名：**直接写 `std::cout`，作用范围最明确。
2. **using 声明：**`using std::cout;` 只引入 `cout`。
3. **语法边界：**`using-directive`（using 指令）后必须是命名空间名。`cout` 是对象，精确引入应写 `using std::cout;`。

**Q15: [learning] C 程序通常如何生成机器码？**

C 通常由编译器编译为目标平台的机器码，再由操作系统装载执行。生成机器码描述的是编译产物，不意味着程序不依赖运行时支持。

**Q16: [learning] C/C++ 程序运行时通常依赖哪些支持？**

运行时是程序执行所需的支持代码与环境，包括启动代码、语言运行库和操作系统服务。C/C++ 程序可以依赖运行库，但通常不需要托管虚拟机。

**Q17: [learning] C++ 程序的可移植性如何体现，与 Java 有何差异？**

可移植性指程序迁移到不同硬件或操作系统后仍能正确运行的能力。C++ 和 Java 都可编写可移植源代码，但编译产物和运行条件不同。

1. **C++：**通常针对目标平台重新编译为机器码。源代码复用取决于是否使用标准语言特性和可移植库。
2. **Java：**通常编译为字节码，由兼容的 Java 虚拟机（JVM，Java Virtual Machine）执行。
3. **共同限制：**使用平台专属应用程序编程接口（API，Application Programming Interface）、应用二进制接口（ABI，Application Binary Interface）或硬件功能时，两者都需要适配。

**Q18: [learning] int main()、int main(void) 和 void main() 有什么区别？**

在提供托管执行环境的实现（hosted implementation）中，`int main()` 与 `int main(void)` 都表示无参数并返回 `int`。标准规定的程序入口形式返回类型为 `int`。

`void main()` 不符合标准 C++ 对 hosted 程序入口函数的要求。部分编译器可能将其作为扩展接受，不应依赖这种行为。

**Q19: [learning] main() 需要显式写 return 0; 吗？**

不需要。控制流到达 `main()` 函数末尾时，等价于返回 `0`，表示正常终止。

1. 该规则仅适用于 `main()`。
2. 普通非 `void` 函数必须在每条可能正常结束的路径上返回值。

**Q20: [learning] freestanding C++ 程序必须定义 main() 吗？**

桌面和 Android 用户空间程序通常采用 hosted 实现，程序入口由实现规定为 `main()`。freestanding 实现不要求采用 hosted 程序的 `main()` 形式，启动入口由实现定义，因此不能将 `main()` 视为所有嵌入式程序的标准入口。

**Q21: [learning] _tmain() 是什么，能否用于标准 C++ 程序？**

`_tmain` 是 Microsoft TCHAR 兼容层提供的入口名称，不属于 ISO C++ 标准。它依据 `_UNICODE` 等字符集配置映射到 `main` 或 Microsoft 扩展 `wmain`。

跨平台程序应使用标准 `main()`。仅在使用相应 Microsoft 工具链和 TCHAR 约定时考虑 `_tmain()`。

**Q22: [learning] Linux hosted 程序如何从 ELF 入口执行到 main？**

在以 ELF 和 glibc 为例的 Linux hosted 程序中，执行从 ELF 启动入口开始，经 C 运行时初始化后调用 `main`。因此 `main` 是运行时约定的入口函数，不是内核直接调用的函数。典型顺序如下：

1. **ELF 装载：**若 ELF 指定了动态装载器，内核先将控制权交给装载器；装载和重定位完成后，装载器跳转到主程序入口。未指定动态装载器时，内核直接跳转到主程序入口。入口通常对应启动文件提供的 `_start`。
2. **运行时初始化：**以 glibc 为例，启动代码调用 `__libc_start_main`，完成必要初始化后调用 `main`。全局对象的动态初始化通常在 `main` 前执行，具体顺序受标准和实现规则限制。
3. **返回与终止：**`main` 返回后，运行时按进程终止约定处理返回状态。调用链和符号取决于平台、C 运行时及链接配置。

在 Linux ELF 环境中，可用 `readelf -h` 查看 ELF 入口地址，并用 `nm` 查看符号表中的 `_start` 和 `main`。命令中的 `build/init-second-stage` 是示例文件路径，应替换为待检查的 ELF 文件；`grep` 用于筛选所需字段和符号：

```bash
readelf -h build/init-second-stage | grep 'Entry point'
nm build/init-second-stage | grep -E ' (_start|main)$'
```

ELF 入口地址通常对应 `_start`，而 `main` 是由启动代码调用的符号。具体结果受架构、链接器和 C 运行时影响。

**Q23: [learning] std::cout 是对象吗？**

是。`std::cout` 是标准库提供的 `std::ostream` 对象，关联标准输出流。标准输出可重定向到文件或管道，因此 `std::cout` 不等同于屏幕。

**Q24: [learning] std::cout 输出字符串和整数时有何区别？**

两者调用不同的插入运算符重载：前者输出字符串字面量，后者按流的格式设置将 `int` 转换为文本。无需手写 `printf` 风格的格式占位符。

**Q25: [learning] 流插入运算符为什么可以连续使用？**

流插入运算符返回流引用，因此表达式按左结合连续作用于同一个流：`((std::cout << "A") << "B") << carrots`。

**Q26: [done] std::cerr 与 std::endl 如何刷新输出？**

`std::cerr` 是标准错误流，默认设置 `unitbuf`，输出操作后会刷新关联的流缓冲区。`std::endl` 写入换行符并显式刷新所作用的流；`<<` 负责将内容插入流。

**Q27: [done] std::cout 输出后程序阻塞，如何确保内容已刷新？**

例如：

```cpp
std::cout << "Mini-Android init";
while (true) {
    pause();
}
```

插入操作可能只把字符写入 std::cout 的缓冲区。`pause()` 等待信号，不会刷新该流，因此日志可能尚未送往终端。刷新方式如下：

1. std::endl：写入换行并刷新。
2. std::flush：只刷新，不写入换行。
3. \n：只写入换行符，不会主动刷新流。

例如：

```cpp
std::cout << "Mini-Android init" << std::endl;
```

**Q28: [learning] std::cin.get() 放在程序末尾有什么作用？**

无参数的 `get()` 从输入流读取一个字符并返回，因此可等待输入。但它可能读取此前已缓冲的字符，不是通用的程序暂停机制。

调试时应使用断点；需要交互时，应明确读取并处理输入，而不要依赖控制台窗口行为。
