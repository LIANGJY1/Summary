# C++ 中的 POSIX 接口

> 本文说明 C++ 程序使用 POSIX 接口的边界，以及 `pause()` 的等待行为。

**Q1: [done] POSIX 接口是什么？C++ 程序何时使用？**

POSIX 函数是 POSIX（Portable Operating System Interface，可移植操作系统接口）定义的操作系统接口，不属于 ISO C++ 标准库。目标平台提供该接口时，C++ 程序可以调用。

1. **需要操作系统接口：**访问文件描述符、进程或信号等 POSIX 能力时，调用目标平台提供的接口。
2. **需要跨平台：**优先使用 C++ 标准库，或将 POSIX 调用封装在平台适配层。`<unistd.h>` 并非所有 C++ 环境都提供。

**Q2: [done] C++ 调用 POSIX 函数需要包含什么头文件、链接什么库？**

头文件提供函数声明，链接器解析函数实现。具体头文件和库依函数及目标平台而定，没有供所有 POSIX 函数统一使用的 `libposix`。

1. **头文件：**包含声明所在的头文件，例如 `pause()` 声明在 `<unistd.h>`。
2. **链接库：**Linux 上 `pause()` 由系统 C 库提供，常规 `g++` 链接流程会自动处理，无需额外指定 `-lposix` 或 `-lc`。
3. **平台差异：**其他函数可能需要额外库、特性宏或链接选项，应查目标平台文档。

**Q3: [done] Linux 中调用 pause() 会怎样等待信号，为什么它不能单独实现完整的 PID 1 init？**

`pause()` 挂起调用进程，直到捕获的信号处理函数返回；随后它返回 `-1`，并将 `errno` 设为 `EINTR`。它只提供等待机制，不负责信号策略、服务管理或回收子进程，因此不能单独构成 PID 1 的 init 实现。

1. **信号处理：**未安装处理函数的信号按默认处置处理，程序不会执行自定义 init 逻辑。
2. **PID 1 边界：**Linux 对 PID 1 的信号默认处置有特殊规则，不能假设任意信号都会使 `pause()` 返回。

**Q4: [done] POSIX mkdir() 如何创建目录并处理权限与已存在路径？**

`mkdir()` 声明在 `<sys/stat.h>`。成功返回 `0`；失败返回 `-1`，并设置 `errno`。路径以 `/` 开头时为绝对路径，否则相对进程当前工作目录。

1. **路径：**绝对路径相对于根目录解析；相对路径相对于进程当前工作目录。当前工作目录可由 `chdir()` 改变，并由 `fork()` 后的子进程继承。
2. **权限模式：**第二个参数是八进制权限位，例如 `0555` 表示所有者、组和其他用户均有读与执行权限。实际权限为请求模式与进程 `umask` 屏蔽位计算后的结果；需严格设置时，创建后调用 `chmod()`。
3. **错误处理：**先检查返回值；仅当返回 `-1` 时读取 `errno`。常见错误包括 `EEXIST`（路径已存在）、`EACCES`（权限不足）、`ENOENT`（父目录不存在）和 `ENOSPC`（空间不足）。
4. **幂等准备：**`mkdir()` 只创建目标目录，不修改已存在路径。若目标是“确保目录存在”，可将 `EEXIST` 视为已就绪；但若该路径是普通文件，`mkdir()` 也会返回 `EEXIST`，需要时用 `stat()` 判断类型。

例如，按上述用法做一次幂等准备：

```cpp
#include <cerrno>
#include <sys/stat.h>

int prepare() {
    if (mkdir("/system", 0555) == -1) {
        if (errno != EEXIST) return errno;

        struct stat info{};
        if (stat("/system", &info) == -1) return errno;
        if (!S_ISDIR(info.st_mode)) return ENOTDIR;
    }
    return 0;
}
```

代码先检查创建结果，仅在失败后读取 `errno`。若路径已存在，再确认它确实是目录。

**Q5: [learning] exec 家族如何选择参数、环境和路径策略？**

`exec` 家族函数用新程序映像替换当前进程映像，保留进程号；成功时不返回，失败时返回 `-1` 并设置 `errno`。POSIX 定义 `execl()`、`execle()`、`execlp()`、`execv()`、`execve()` 和 `execvp()` 六个接口；`execvpe()` 是 glibc 扩展，不属于 POSIX。

1. **参数形式：**`l` 表示逐项传参，以空指针结束可变参数列表；`v` 表示通过指针数组传参，数组末尾也须为空指针。
2. **环境变量：**`execle()` 和 `execve()` 接收调用方提供的环境变量数组。
3. **路径搜索：**`execlp()` 和 `execvp()` 按 `PATH` 搜索不含斜杠的程序名；路径中含斜杠时按给定路径执行。
4. **参数约定：**`argv[0]` 通常填写程序名，数组必须以空指针结束。C++ 字符串字面量不能直接作为 `char*` 参数；应使用生命周期覆盖调用的可写存储。
5. **调用结果：**任一接口成功时都不返回；失败时返回 `-1` 并设置 `errno`。

例如，逐项传参并处理失败：

```cpp
#include <cerrno>
#include <cstdio>
#include <unistd.h>

int run_echo() {
    execl("/bin/echo", "echo", "hello", static_cast<char*>(nullptr));
    // 只有 exec 失败才会执行到这里
    std::printf("exec failed, errno = %d\n", errno);
    return -1;
}
```

数组形式如下。调用成功后进程映像已替换，因此不会继续执行后续语句：

```cpp
char arg0[] = "echo";
char arg1[] = "hello";
char *const argv[] = {arg0, arg1, nullptr};
execv("/bin/echo", argv);  // 成功则后续代码不再执行
```

第一个例子用空指针结束可变参数列表；第二个例子用可写字符数组构造 `argv`，并以空指针结束数组。

**Q6: [learning] 何时应使用 execv()，何时应在 fork() 后调用它？**

是否需要在子进程中调用 `execv()`，取决于调用后父进程是否还需继续执行原程序。若当前进程只需交接给新程序，可直接替换；若父进程需等待或回收子进程，则先 `fork()`。

1. **直接替换：**当前进程完成准备工作后不再需要旧程序逻辑，可直接调用 `execv()`。AAOS 13 first stage init 完成挂载后替换为 `/system/bin/init`，继续使用同一 PID。
2. **子进程执行：**父进程需要等待、回收或继续处理时，先 `fork()`，由子进程调用 `execv()`，父进程使用 `waitpid()` 管理子进程。
3. **失败处理：**`execv()` 仅在失败时返回；成功路径不会执行调用之后的代码，因此应在调用之后处理失败。
4. **参数形式：**参数已存入数组或数量可变时使用 `execv()`；固定参数也可使用 `execl()`。

例如，首阶段交棒给系统 init 的最小形态（AAOS 13 first_stage_init 实测结构）：

```cpp
char path[] = "/system/bin/init";
char mode[] = "selinux_setup";
char* args[] = {path, mode, nullptr};
execv(path, args);
PLOG(FATAL) << "execv(\"" << path << "\") failed";
```

`execv()` 成功后不会返回，因此末行只在失败时执行。标准输出在调用前已重定向到内核日志，且未设置 `FD_CLOEXEC` 的文件描述符会跨映像替换保留。
