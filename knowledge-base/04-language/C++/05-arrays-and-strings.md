# C++ 数组与字符串

> 本文说明内建数组、C 风格字符串、流输入和 `std::string` 的基本规则。

**Q1: [done] strcmp 如何使用**

`strcmp` 是 string compare 的缩写，C 标准库的字符串比较函数：比较两个以空字符结尾的字符串内容，相等时返回 `0`，不相等时按字典序返回正值或负值，只保证符号、不保证数值。C 程序经 `<string.h>` 声明，C++ 程序经 `<cstring>` 使用 `std::strcmp`。

1. **返回值读法：**判断内容是否相同用 `== 0`，判断先后用 `< 0` 或 `> 0`。具体数值由实现决定，写成 `== 1` 或 `== -1` 不可移植。
2. **常见误用：**比较内容必须调用 `strcmp`。两个 `char*` 之间写 `==` 比较的是指针地址；内容相同的字符串也可以位于不同地址，此时 `==` 为假而 `strcmp` 为 `0`。
3. **使用前提：**两个参数都必须是空字符结尾的字符串，比较范围由空字符决定；缓冲区未正确终止时会越界读取。
4. **相关函数与替代：**`strncmp(lhs, rhs, n)` 只比较前 `n` 个字符，用于判断前缀；两端都是 `std::string` 时直接用 `==`、`<` 比较内容，不必退回 C 接口。

例如，按返回值符号判断两个启动参数的关系：

```cpp
#include <cstring>

int compare_mode(const char *input, const char *expected) {
    int result = std::strcmp(input, expected);
    if (result == 0) {
        return 0;                 // 内容相同
    }
    return result < 0 ? -1 : 1;   // 只按符号判断先后
}
```

`result == 0` 判内容相等，`result < 0` 判 `input` 字典序在前；这里没有依赖任何具体返回值。

**Q2: [learning] C++ 的复合类型是什么，内建数组如何声明和初始化？**

复合类型由其他类型构成，常见例子包括数组、指针、引用、函数和类类型。内建数组的特点如下：

1. 包含固定数量的同类型元素。
2. 长度属于数组类型的一部分。

```cpp
int main() {
    int values[5]{1, 2};
    return values[0] + values[1];
}
```

此例将前两个元素初始化为 `1` 和 `2`，其余元素值初始化为 `0`。内建数组创建后不能改变长度，也不能直接整体赋值。需要可变长度时通常使用 `std::vector`。



**Q3: [learning] 声明数组时省略长度有什么规则，为什么字符串常这样写？**

若数组由初始化列表初始化，可以省略长度，编译器按初始化项数量推导长度。这样并非不安全，但访问时仍必须保证下标在范围内，内建数组不会自动检查越界。

```cpp
char name[] = "Bob";
```

此数组长度为 `4`，因为字符串字面量包含结尾的空字符。对普通数组省略长度也合法，是否清晰取决于代码是否容易看出元素数量。



**Q4: [learning] 为什么 `long values[] = {25, 92, 3.0};` 会编译失败？**

列表初始化会拒绝窄化转换，原因如下：

1. `3.0` 是浮点值，转换为 `long` 会丢弃小数部分，因此该初始化不合法。
2. 普通赋值形式可能允许转换并截断，容易掩盖精度损失。



**Q5: [learning] 什么是 C 风格字符串，它与普通 `char` 数组有什么区别？**

C 风格字符串是以空字符 `\0` 结尾的字符序列，通常存放在 `char` 数组中。普通 `char` 数组不一定有这个结束标记，因此不能无条件传给要求 C 字符串的函数。

访问或输出缺少 `\0` 的数组时，程序可能越过数组边界继续读取。新代码通常优先使用 `std::string`，避免手动维护结束符和容量。



**Q6: [learning] 为什么 `'S'` 和 `"S"` 不能互换？**

`'S'` 是字符字面量，类型为 `char`。`"S"` 是字符串字面量，类型为含两个 `const char` 元素的数组：字符 `S` 和结尾的 `\0`。

将字符串字面量直接赋给单个 `char` 类型不匹配。字符的数值编码不保证是 ASCII。



**Q7: [learning] `sizeof` 和 `std::strlen` 计算字符串时有什么区别？**

两者衡量的内容不同：

1. `sizeof` 查询对象占用的字节数。
2. `std::strlen` 计算以 `\0` 结尾的 C 字符串在结束符之前有多少个字节。

```cpp
#include <cstring>

int main() {
    char name[20] = "Bob";
    return static_cast<int>(sizeof(name) - std::strlen(name));
}
```

这里 `sizeof(name)` 为整个数组的大小，`std::strlen(name)` 为 `3`。若 `name` 已退化为指针，`sizeof(name)` 得到的是指针大小。`strlen` 要求输入确实指向有效的空字符结尾字符串。



**Q8: [learning] `std::cin.getline()` 和 `std::cin.get()` 读取一行时有何区别？**

两种成员函数都可以读取字符数组，但遇到分隔符后的处理不同。

1. `getline(buffer, size)`：读到换行或容量上限，若遇到换行会将其从输入流中取走，并在缓冲区中写入 `\0`。
2. `get(buffer, size)`：读到换行或容量上限，但会把换行留在输入流中，后续读取需自行处理。
3. 两者容量不足时都要检查流状态，并处理未读完的行，避免截断输入被误认为完整输入。



**Q9: [learning] 为什么保留 `std::cin.get()`，它适合用来检查什么？**

`get()` 会把换行留在流中。读取后可按以下方式判断：

1. `peek()` 得到 `\n`：本行已读完，换行符仍留在流中。
2. 后续不是 `\n`：结合流状态判断是否因缓冲区容量不足而截断，并处理剩余输入。

这项检查避免把截断内容当作完整输入。`get()` 本身不是安全输入的保证。

读取字符数组时应检查容量和流状态。现代代码也可使用 `std::string` 版本的 `std::getline`，再按业务规则限制输入长度。



**Q10: [learning] 为什么先用 `std::cin >> year` 再用 `getline`，会读到空行？**

执行过程分两步：

1. 格式化提取 `std::cin >> year` 读取数字后会把换行留在输入流中。
2. 紧接着的 `getline` 遇到这个换行便结束，得到空行。

若要丢弃本行剩余输入，再读取下一行，可显式忽略到换行：

```cpp
#include <iostream>
#include <limits>
#include <string>

int main() {
    int year{};
    std::string name;

    std::cin >> year;
    std::cin.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    std::getline(std::cin, name);
}
```

这会丢弃数字后直到行末的内容。仅调用一次 `get()` 只会丢弃一个字符，不适用于用户可能输入额外空格或字符的情况。



**Q11: [learning] 为什么通常优先使用 `std::string` 而不是 C 风格字符串？**

`std::string` 管理字符存储和长度，支持赋值、比较及拼接，降低固定缓冲区溢出和手动内存管理的风险。

C 风格字符串仍用于兼容 C API 或明确要求空字符结尾缓冲区的接口。使用它们时必须同时保证容量、终止符和边界条件。



**Q12: [learning] `std::string` 如何支持赋值和拼接？**

`std::string` 重载了赋值与拼接运算符，使其可以按字符串值进行操作。实现会管理所需容量，但具体何时分配内存不是程序可依赖的接口保证。

```cpp
#include <string>

int main() {
    std::string first{"A"};
    std::string second{"B"};
    std::string third{"C"};
    std::string combined = first + second;
    combined += third;
}
```

`operator+` 会生成拼接结果。连续大量拼接时，可用 `append` 或 `+=` 逐步追加，避免不必要的临时结果。



**Q13: [learning] C++ 原始字符串的 `R"(...)"` 和自定义定界符有什么作用？**

原始字符串不把内容中的反斜杠序列当作普通转义序列，适合写路径、正则表达式等内容。其结束标记默认是 `)"`。

```cpp
int main() {
    auto path = R"(C:\new\text.txt)";
    auto text = R"tag(text containing )" here)tag";
}
```

自定义定界符用于避免内容提前包含结束标记。原始字符串解决转义问题，`L`、`u8`、`u`、`U` 等编码前缀则决定字符类型或编码，两者可以组合但含义不同。


