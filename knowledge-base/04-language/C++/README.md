# C++ 学习资料

> 本目录按基础概念、构建工具、系统接口、类型、内存和标准库容器主题组织 C++ 学习笔记。文档结构和题目编号遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。

1. `01-language-fundamentals.md`：词法与对象基础、编程范式、头文件与命名空间、程序模型、入口函数和基础 I/O（28 题）。
2. `02-build-and-link.md`：预处理、编译链接、头文件机制与保护宏、工具链选项和产物检查（11 题）。
3. `03-posix-apis.md`：POSIX 接口与最小 `/init` 示例，含 `pause()` 与 `wait()` 行为解析（血缘、可中断睡眠、唤醒出口）、`mkdir()`、exec 家族、fork 创建子进程与服务进程、exit 与 _exit 区别和僵尸进程（14 题）。
4. `04-functions-and-types.md`：函数、基本类型、初始化、字符、数值与转换（23 题）。
5. `05-arrays-and-strings.md`：数组、C 风格字符串、输入流（含 `std::istringstream` 解析和 `std::atoi` 转换）和 `std::string`（17 题）。
6. `06-user-defined-types.md`：结构体、共同体和枚举：struct 定义解析、类型定义与对象、成员函数与构造函数、struct 与 class 的区别、列表初始化、匿名结构体、自引用与前向声明、位字段、参数传递、标准布局，以及共同体和枚举（18 题）。
7. `07-pointers-and-memory.md`：指针与引用语义、数组关系、存储期、动态分配与资源管理（18 题）。
8. `08-containers.md`：标准库容器 `std::vector`（含与数组的关系、为什么要用它）、存储模型、容量管理、迭代器失效、`push_back` 存入与 `back()` 修改最后一条、erase-remove 惯用法和 `vector<bool>` 特例（12 题）。
9. `09-file-streams.md`：文件流 ifstream 解析：类型结构、打开模式、流状态、格式化与非格式化读取、随机访问、整文件读取和生命周期（12 题）。

`cpp_practice_project/` 保存练习代码，不属于问答文档。
