# C++ 中的 POSIX 接口

> 本文说明 C++ 程序使用 POSIX 接口的边界，以及 mkdir()、fork、exec 家族、exit 与 _exit、wait()/pause() 等待与僵尸进程回收的行为。

**Q1: [done] POSIX 接口是什么？C++ 程序何时使用？**

POSIX（Portable Operating System Interface，可移植操作系统接口）是一套**操作系统接口标准**，约定程序如何访问文件、进程、线程等系统能力。它为应用开发者提供共同的调用方式，也为系统实现者规定接口契约。它不是一份源码库，也不属于 C++ 标准库。

1. **解决的问题：**例如 Android 和 Linux 都提供 `mkdir(path, mode)` 时，开发者可以复用创建目录的 C++ 源码，而不必为每个系统改写接口。不同系统或架构通常仍需分别编译。具体接口是否可用及其行为仍需确认。
2. **由谁实现：**各系统自行实现 POSIX 接口。例如 Android 的开源 C 库 Bionic 提供 `mkdir()`，再请求 Linux 内核创建目录。POSIX 是程序使用系统能力的接口约定，具体实现可以开源或闭源。
3. **何时使用：**Android NDK 的 C/C++ 代码需要操作文件、进程或套接字等系统资源时，可以选用平台支持的 POSIX 接口。Kotlin 或 Java 应用通常可使用 Android SDK 或 Java 提供的高层接口。
4. **如何确认：**并非所有系统或版本都支持全部接口。查目标平台文档、头文件和最低 API 级别，并编译链接最小调用。例如 `mkdir()` 声明在 `<sys/stat.h>`。运行时检查返回值和 `errno`，因为接口可用仍可能因权限等原因失败。

依据：POSIX `<unistd.h>`、`sysconf()`，Android NDK Using newer APIs。

**Q2: [done] C++ 调用 POSIX 函数需要包含什么头文件、链接什么库？**

头文件提供函数声明，链接器解析函数实现。具体头文件和库依函数及目标平台而定，没有供所有 POSIX 函数统一使用的 `libposix`。

1. **头文件：**包含声明所在的头文件，例如 `pause()` 声明在 `<unistd.h>`。
2. **链接库：**Linux 上 `pause()` 由系统 C 库提供，常规 `g++` 链接流程会自动处理，无需额外指定 `-lposix` 或 `-lc`。
3. **平台差异：**其他函数可能需要额外库、特性宏或链接选项，应查目标平台文档。

**Q3: [learning] mode_t 是什么，如何表示套接字文件权限？**

`mode_t` 是 POSIX 定义的整数类型，由 `<sys/types.h>` 提供，用于表示文件模式，包括权限位。在下面的结构体中，`perm` 可以保存希望使用的权限值。

```cpp
#include <string>
#include <sys/types.h>

struct SocketDescriptor {
    std::string name;
    std::string type;
    mode_t perm;
};

SocketDescriptor sock{"demo", "stream", 0660};
```

1. **权限值：**`0660` 是八进制数，依次表示所有者和所属组可读写，其他用户无权限。前面的 `0` 表示八进制。
2. **字段作用：**`sock.perm` 只保存数值。程序需要把它传给相应接口，例如在路径名套接字创建后调用 `chmod(path, sock.perm)`，才会请求修改文件权限，还须检查调用结果。
3. **适用范围：**Linux 的路径名 UNIX 域套接字有文件系统权限，其创建时的初始权限受 `umask` 影响。抽象地址不对应文件路径，文件权限不控制其访问。路径名套接字的权限行为也可能因系统不同而不同。

依据：POSIX `<sys/types.h>`，Linux `unix(7)` 与 `chmod(2)`。

**Q4: [done] POSIX mkdir() 如何创建目录并处理权限与已存在路径？**

`mkdir()` 声明在 `<sys/stat.h>`。成功返回 `0`。失败返回 `-1`，并设置 `errno`。路径以 `/` 开头时为绝对路径，否则相对进程当前工作目录。

1. **路径：**绝对路径相对于根目录解析。相对路径相对于进程当前工作目录。当前工作目录可由 `chdir()` 改变，并由 `fork()` 后的子进程继承。
2. **权限模式：**第二个参数是八进制权限位，例如 `0555` 表示所有者、组和其他用户均有读与执行权限。实际权限为请求模式与进程 `umask` 屏蔽位计算后的结果。需严格设置时，创建后调用 `chmod()`。
3. **错误处理：**先检查返回值。仅当返回 `-1` 时读取 `errno`。常见错误包括 `EEXIST`（路径已存在）、`EACCES`（权限不足）、`ENOENT`（父目录不存在）和 `ENOSPC`（空间不足）。
4. **幂等准备：**`mkdir()` 只创建目标目录，不修改已存在路径。若目标是“确保目录存在”，可将 `EEXIST` 视为已就绪。但若该路径是普通文件，`mkdir()` 也会返回 `EEXIST`，需要时用 `stat()` 判断类型。

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

**Q5: [learning] fork 是什么？C++ 程序如何使用它创建子进程？**

fork 是 POSIX 提供的进程创建接口，声明在 `<unistd.h>`，不属于 ISO C++ 标准库。它把调用进程复制出一个新的子进程，调用一次返回两次：父子进程都从返回处继续执行同一段后继代码，再按返回值确定身份。子进程得到 0，父进程得到子进程的 PID，失败时返回 -1 并设置 errno，此时不会产生子进程。

fork 之后父子进程的关系：

1. **内存内容为副本：**子进程拥有与父进程相同的内存内容，Linux 通常采用写时复制，先共享物理页并在写入时复制；这是实现策略，POSIX 不要求采用该策略。
2. **打开的文件描述符被继承：**双方指向同一打开文件描述，文件偏移是同一个，一方读写推进偏移，另一方立即受影响。
3. **进程身份独立：**子进程有自己的 PID 与 PPID。父进程先退出时，Linux 会把子进程交给最近的子收养者，或在没有子收养者时交给 init。
4. **只复制调用线程：**`fork()` 复制的只是调用它的那个线程。多线程程序里，其他线程持有的锁在子进程中没有释放者，所以子进程在 exec 之前只能调用异步信号安全函数（如 `write()`、`_exit()`），通常应让子进程立即 exec，或改用 `posix_spawn()` 这类接口。

使用 fork 的步骤：

1. **包含头文件：**`fork()` 与 `pid_t` 声明在 `<unistd.h>`，读取错误原因需要 `<cerrno>`。
2. **检查返回值，取值分三种：**

    1. **返回 -1：**创建失败，没有子进程产生。读取 errno 决定重试或报错，常见原因有 EAGAIN（进程数触达限制，如 RLIMIT_NPROC）与 ENOMEM（内存不足）。
    2. **返回 0：**当前执行流是子进程，执行它应做的工作。
    3. **返回正数：**当前执行流是父进程，返回值是子进程的 PID，保存它用于后续管理。
3. **子进程收尾用 `_exit()`：**exit() 会刷新继承来的 stdio 缓冲副本，把 fork 前尚未刷出的内容送出，父进程退出时再送一遍就重复输出；_exit() 不刷新，子进程自己的输出也不会自动送出，单线程示例需要输出时可以在 `_exit()` 前刷新，但多线程 `fork()` 后、`exec()` 前不能调用 `std::fflush()`。若子进程要换成新程序，由 exec 家族替换映像，成功时不返回。
4. **父进程回收子进程：**用 `wait()` 或 `waitpid()` 读取子进程的退出状态。示例中的 `waitpid(pid, &status, 0)` 只等 pid 指定的子进程，第三个参数 0 表示默认阻塞等待，退出状态写入 status。不回收的已退出子进程保持僵尸状态。

最小示例是单线程程序：子进程打印一行后退出，父进程回收后打印子进程号。`printf()` 和 `fflush()` 不适用于多线程进程的子进程在 `exec()` 前调用。

```cpp
#include <cerrno>
#include <cstdio>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

int main() {
    pid_t pid = fork();
    if (pid == -1) {
        std::perror("fork");
        return 1;
    }
    if (pid == 0) {
        std::printf("child\n");
        std::fflush(stdout);
        _exit(0);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    std::printf("parent: child pid = %d\n", pid);
    return 0;
}
```

若 `waitpid()` 成功，父进程在子进程结束后打印，输出顺序为两行：

```text
child
parent: child pid = 12345
```

12345 为实际子进程号。示例在 `_exit()` 前显式刷新 stdout：省略 `std::fflush()` 时，重定向到文件或管道会丢掉子进程那行（本机实测）。

**Q6: [learning] exec 家族如何选择参数、环境和路径策略？**

`exec` 家族函数用新程序映像替换当前进程映像，保留进程号。成功时不返回，失败时返回 `-1` 并设置 `errno`。POSIX 定义 `execl()`、`execle()`、`execlp()`、`execv()`、`execve()` 和 `execvp()` 六个接口。`execvpe()` 是 glibc 扩展，不属于 POSIX。

1. **参数形式：**`l` 表示逐项传参，以空指针结束可变参数列表。`v` 表示通过指针数组传参，数组末尾也须为空指针。
2. **环境变量：**`execle()` 和 `execve()` 接收调用方提供的环境变量数组。
3. **路径搜索：**`execlp()` 和 `execvp()` 按 `PATH` 搜索不含斜杠的程序名。路径中含斜杠时按给定路径执行。
4. **参数约定：**`argv[0]` 通常填写程序名，数组必须以空指针结束。C++ 字符串字面量不能直接作为 `char*` 参数。应使用生命周期覆盖调用的可写存储。

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

**Q7: [learning] 如何用 fork 和 execv 创建服务进程？何时可以不 fork 直接 execv？**

用 fork 复制出子进程，让子进程用 execv 把自身替换成服务程序，父进程继续处理下一个服务，这是让父进程继续运行并启动服务的一种常见方式。分两步的原因是 execv 替换整个当前进程：不先 fork 就连父进程一起换掉，无法继续启动其他服务。当前进程完成准备工作后不再需要旧程序逻辑时，可以不 fork 直接 execv，例如 AAOS 13 first stage init 完成挂载后替换为 `/system/bin/init`，继续使用同一 PID。

典型写法（摘自服务启动循环，外层是逐行解析配置的 while 循环；fork、execv 和 _exit 声明在 <unistd.h>，errno 声明在 <cerrno>）：

```cpp
pid_t pid = fork();
if (pid == -1) {
    std::cerr << "fork failed, errno = " << errno << std::endl;
    continue;
}
if (pid == 0) {
    execv(svc.path.c_str(), exec_argv.data());
    _exit(127);  // 失败后终止子进程
}
std::cout << "Started service " << svc.name << " (pid " << pid << ")" << std::endl;
```

fork 调用一次、返回两次，父子进程从 fork 之后的同一条语句继续执行，靠返回值区分身份：-1 失败，没有子进程产生，代码放弃这个服务继续处理下一个；0 是子进程，此后的执行与父进程无关；正数是子进程的 PID，父进程据此管理和回收它。

子进程分支把自身替换成服务程序。execv 成功后进程映像整体替换，代码和内存都换成新程序的内容，但进程还活着、PID 不变，因此 execv 成功时永不返回，失败时才会执行后续退出语句。失败时子进程不能继续执行父进程的代码，否则会跟着父进程重复启动后续服务，所以立刻以 _exit(127) 终止，127 是 Unix 惯例，表示程序没能执行起来。子进程收尾用 _exit 而不是 exit：fork 时父进程的 stdio 缓冲区在子进程里有一份副本，exit 会把它也刷新出去，父进程终止时再刷一遍造成重复输出。

首阶段交棒给系统 init 的直接替换形态（AAOS 13 first_stage_init 实测结构）：

```cpp
char path[] = "/system/bin/init";
char mode[] = "selinux_setup";
char* args[] = {path, mode, nullptr};
execv(path, args);
PLOG(FATAL) << "execv(\"" << path << "\") failed";
```

`execv()` 成功后不会返回，末行只在失败时执行。标准输出在调用前已重定向到内核日志，且未设置 `FD_CLOEXEC` 的文件描述符会跨映像替换保留。

exec_argv.data() 把 vector 的数据交给 execv。execv 的第二个参数要求 C 数组：char* 序列，且以空指针结尾。vector 的元素在内存中连续存放，data() 返回指向首元素的指针，正好满足这个布局要求。使用它有两个前提：exec_argv 的元素类型是 char*，且末尾已经放入空指针。

能执行到末尾 std::cout 的只有父进程：子进程要么已被 execv 换成服务程序，要么已在失败时 _exit。父进程拿到的 pid 就是子进程编号。

**Q8: [learning] 什么时候使用 exit()，什么时候使用 _exit()？**

`exit()` 执行用户态清理后终止进程，`_exit()` 跳过这些清理。在 `fork()` 后执行新程序失败的子进程中，通常用 `_exit()` 退出，避免重复刷新从父进程继承的缓冲区。

两者的区别：

1. **`exit()`：**执行 `atexit()` 注册的函数、析构静态存储期对象并刷新标准 I/O 缓冲区，不会展开当前调用栈中的自动对象。
2. **`_exit()`：**不执行上述用户态清理，直接终止进程并向父进程提供状态。内核仍会关闭文件描述符等进程资源。

`fork()` 复制用户态缓冲区。若父进程此前写入了尚未刷出的数据，子进程调用 `exit()` 会刷新副本，父进程随后又可能输出同一数据。多线程进程的子进程在成功 `exec` 之前还受异步信号安全限制，不能随意调用 `exit()` 或普通日志函数。

`execv()` 成功时不返回，失败时返回 `-1` 并设置 `errno`。例如路径不存在可能得到 `ENOENT`，没有执行权限可能得到 `EACCES`，格式错误可能得到 `ENOEXEC`。失败后子进程应使用约定的状态码调用 `_exit()`，避免继续执行父进程的启动逻辑。`126` 和 `127` 是 shell 的常见约定，并非 `execv()` 自动返回的状态；被执行程序也可能自行返回这些值，父进程不能仅凭状态码无歧义地判断失败阶段。

**Q9: [learning] 什么是僵尸进程，如何消除？**

僵尸进程是已经终止、但退出状态尚未由父进程收取的子进程。内核保留 PID、退出状态等少量记录，直到父进程调用 `wait()` 或 `waitpid()` 回收；它已不再运行，发送 `SIGKILL` 不能将其清除。

处理方式取决于是否需要退出状态：

1. **需要状态：**父进程及时调用 `wait()` 或 `waitpid()`。每次调用回收一个符合条件的子进程；大量僵尸会占用进程表项和 PID，可能妨碍创建新进程。
2. **不需要状态：**POSIX.1-2001 及以后可以显式将 `SIGCHLD` 设为 `SIG_IGN`，或设置 `SA_NOCLDWAIT`，使终止的子进程不成为僵尸，但不能再取得其退出状态。Linux 上信号的默认忽略处置与显式设置 `SIG_IGN` 在此处不同。
3. **父进程已退出：**Linux 会将子进程交给最近的子收养者（subreaper），不存在时交给 init，由收养者负责回收。

孤儿进程是父进程先退出、子进程仍在运行的进程，与已经终止的僵尸进程不同。Linux 的 `/proc` 状态 `Z` 表示僵尸。

依据：[Linux wait(2)](https://man7.org/linux/man-pages/man2/waitpid.2.html)。

**Q10: [done] wait() 解析？**

wait 等待调用进程的子进程；在 Linux 上，子进程也可能是通过 clone 等接口创建或被收养的。wait 阻塞于可中断睡眠，有两个返回出口。子进程终止时返回其 PID 并回收。被捕获信号打断时返回 -1，errno 为 EINTR（Interrupted system call，系统调用被信号中断）。

1. **五种调用场景：**

    | 调用瞬间的状态 | wait 的行为 |
    |---|---|
    | 存在已终止未回收的子进程（僵尸进程） | 立即返回该僵尸进程 PID，填充 status 并释放其进程表记录，不阻塞 |
    | 子进程均存活 | 阻塞至任一子进程终止，返回其 PID |
    | 无任何子进程 | 立即返回 -1，errno 为 ECHILD（No child processes，无子进程） |
    | 阻塞中被已捕获信号打断 | 返回 -1，errno 为 EINTR，即被信号中断 |
    | status 指针非法 | 返回 -1，errno 为 EFAULT（Bad address，地址非法） |

2. **信号何时唤醒：**被忽略（SIGCHLD 默认即此类）和被阻塞屏蔽的信号不产生唤醒。安装了处理函数的信号先执行 handler，wait 返回 -1 加 EINTR。默认处置为终止的信号直接终止进程（发给 PID 1 的被内核丢弃）。sigaction 带 SA_RESTART 时内核自动重启 wait，是否返回 EINTR 取决于安装方式。
3. **子进程事件不走信号通道：**子进程终止由内核直接唤醒 wait，SIGCHLD 默认被忽略不影响该机制。
4. **终止与停止的区别：**终止（terminated）指进程结束运行，终止后成为僵尸进程等待回收。停止（stopped）指被 SIGSTOP 暂停执行，进程仍存活、可被 SIGCONT 恢复。通常 wait 报告终止；被跟踪子进程的停止也可能被报告。对于未被跟踪的子进程，可用 waitpid 加 WUNTRACED 获取停止状态。

对监督循环：存在可回收的僵尸时 wait 立即返回，每次回收一个。返回 -1 时应按 errno 区分处理：EINTR 通常重试，ECHILD 表示没有可等待的子进程，其他错误也需处理。不能统一调用 pause 等待，否则可能错过信号或永久阻塞。

**Q11: [done] pause() 解析？**

pause 无条件挂起，唯一返回方式是被信号打断：永远返回 -1 加 EINTR。具体行为取决于到达信号的处置：

| 到达信号的处置 | pause 的行为 |
|---|---|
| 安装了处理函数 | 执行 handler 后返回 -1 加 EINTR，唯一的正常返回路径 |
| 默认处置为终止或核心转储（SIGINT、SIGSEGV 等） | 进程被终止，不存在"返回" |
| 被忽略（SIGCHLD 默认如此，或 SIG_IGN） | 信号被丢弃，继续阻塞 |
| 处于阻塞屏蔽中 | 信号不递送，继续阻塞 |

PID 1 特权：默认处置为终止的信号发给 init 会被内核丢弃，否则任意信号都可能导致系统崩溃。安装了处理函数的信号不受此限。

现阶段 init 未安装任何信号处理函数，监督循环中的 pause 将永久阻塞——该兜底路径阻塞优于忙循环。实现优雅关机等信号处理后，pause 才用于"被信号唤醒后重试 wait"。

**Q12: [learning] pause() 和 wait() 有什么区别，分别什么时候使用？**

`pause()` 等待被捕获的信号处理函数返回，`wait()` 等待子进程状态并可回收已终止的子进程。需要退出状态时应使用 `wait()` 或 `waitpid()`，不能仅靠 `pause()`。

两者的结果不同：

1. **`pause()`：**处理函数返回后，调用返回 `-1` 并将 `errno` 设为 `EINTR`，不返回子进程状态。被忽略或阻塞的信号不会使它正常返回。
2. **`wait(&status)`：**默认等待任意子进程终止，返回其 PID 并写入状态。子进程已经终止且尚未回收时立即返回。无子进程时返回 `-1`、`errno` 为 `ECHILD`，被信号中断时可能返回 `EINTR`。
3. **`waitpid(pid, &status, 0)`：**等待指定 PID 的子进程；PID 为 `-1` 时等待任意子进程。用 `WIFEXITED(status)` 判断正常退出后才能用 `WEXITSTATUS(status)` 取退出码；用 `WIFSIGNALED(status)` 和 `WTERMSIG(status)` 判断信号终止及其信号。

监督循环应在 `EINTR` 时重试，并单独处理 `ECHILD` 和其他错误。不能对所有失败直接调用 `pause()`：信号可能在检查与进入 `pause()` 之间到达，造成错过唤醒；`SIGCHLD` 默认处置也不会唤醒 `pause()`。服务监督还需按返回的 PID 查找服务，并依据其是否为一次性服务决定是否重启。

**Q13: [learning] pause() 配合信号处理函数能替代 wait() 回收子进程吗？**

不能仅靠 `pause()` 和信号处理函数回收子进程：信号可以通知状态变化，但仍需 `wait()` 或 `waitpid()` 收取退出状态。若不需要状态，也可按平台规则配置自动回收。

信号方案有以下限制：

1. **状态读取：**`pause()` 不返回子进程 PID 或退出状态。`SIGCHLD` 可以提示发生变化，仍须通过 `waitpid()` 等接口读取并回收。
2. **事件合并：**多个子进程可能在处理前退出，因此收到通知后应循环调用 `waitpid(-1, &status, WNOHANG)`，直到没有更多可回收的子进程，并处理错误。
3. **等待竞态：**信号可能在检查标志后、调用 `pause()` 前到达，导致随后无限等待。可先屏蔽信号，再用 `sigsuspend()` 原子地替换信号掩码并等待，或使用适合事件循环的其他机制。

只需等待子进程时，阻塞式 `wait()` 或 `waitpid()` 通常更直接；还需协调其他事件时，可让信号处理函数设置适当的标志，由主循环负责回收。
