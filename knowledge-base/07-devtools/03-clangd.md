# clangd 语言服务器

> 通用开发工具学习资料。主线：clangd 的定位与链路、编译flags 从哪来、能力边界与同类工具取舍。维护者：session-to-knowledge。

**Q1: [done] clangd 是什么，它靠什么知道用哪套编译参数解析代码？**

clangd 是面向 C/C++/Objective-C 的语言服务器（language server，LLVM 项目组件，名字取 clang 加守护进程后缀 d）：它以独立进程运行，通过 LSP（Language Server Protocol，语言服务器协议）与编辑器插件通信，提供补全、跳转定义、实时诊断、悬停和重命名。它只用 clang 前端解析和分析代码，不编译、不产出目标文件；其判断准确性的前提是拿到每个文件真实的编译参数。

1. **在链路中的位置：**编辑器插件（VSCode 的 clangd 扩展、Neovim 的 LSP 客户端等）是客户端，负责界面呈现；clangd 进程是服务端，负责解析与回答。两者用 LSP 消息通信，因此换编辑器不用换分析引擎，一个 clangd 也能服务多种编辑器。
2. **编译参数的来源，按优先级：**

    1. **compile_commands.json：**编译数据库，逐源文件记录一条完整编译命令（编译器、include 路径、宏、标准）。CMake 用 CMAKE_EXPORT_COMPILE_COMMANDS 生成，非 CMake 项目可用 bear 或 compdb 从真实构建中捕获。
    2. **.clangd 项目配置：**YAML 文件，用 CompileFlags 段补充或覆盖参数。Mini_Android 的实样指定 Compiler 为 /usr/bin/g++、Add 为 -std=c++17，让 clangd 按 g++ 的系统头文件路径和 C++17 解析；配合编辑器侧 --query-driver=/usr/bin/g++ 允许它向该编译器查询系统包含路径。
    3. **fallback 内置参数：**两者都缺时按内置默认参数解析，准确度下降，典型表现是成片报头文件找不到或标准不匹配的错误。

3. **做什么与不做什么：**它给出诊断、补全、跳转、重命名和悬停信息，不编译、不产产物、不改代码。它的诊断是“给定参数下 clang 的编译看法”，不是构建结果——参数错则结论错，构建能过而 clangd 报错、或反之，都先查参数来源。
4. **对照与边界：**微软 C/C++ 扩展的 IntelliSense（cpptools）是另一套自有引擎，与 clangd 同在 VSCode 时会双重诊断，需关掉其中一方（Mini_Android 关掉旧适配器的诊断与补全）；同类语言服务器还有 ccls。交叉编译项目（NDK、AOSP 树）必须提供对应 sysroot 与参数的编译数据库或 CompileFlags，否则 clangd 按宿主默认分析，会大面积误报。

依据：LLVM clangd 项目文档，Mini_Android 的 .clangd 配置与 VSCode 设置实测。
