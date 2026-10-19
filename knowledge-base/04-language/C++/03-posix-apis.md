# C++ 中的 POSIX 接口

> 本文说明 C++ 程序使用 POSIX 接口的边界，以及 `pause()` 与 `wait()` 等待、`mkdir()`、exec 家族、fork 创建子进程与服务进程、exit 与 _exit 终止进程和僵尸进程回收的行为。

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

`pause()` 挂起调用进程，直到捕获的信号处理函数返回。随后它返回 `-1`，并将 `errno` 设为 `EINTR`。它只提供等待机制，不负责信号策略、服务管理或回收子进程，因此不能单独构成 PID 1 的 init 实现。

1. **信号处理：**未安装处理函数的信号按默认处置处理，程序不会执行自定义 init 逻辑。
2. **PID 1 边界：**Linux 对 PID 1 的信号默认处置有特殊规则，不能假设任意信号都会使 `pause()` 返回。

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

**Q5: [learning] exec 家族如何选择参数、环境和路径策略？**

`exec` 家族函数用新程序映像替换当前进程映像，保留进程号。成功时不返回，失败时返回 `-1` 并设置 `errno`。POSIX 定义 `execl()`、`execle()`、`execlp()`、`execv()`、`execve()` 和 `execvp()` 六个接口。`execvpe()` 是 glibc 扩展，不属于 POSIX。

1. **参数形式：**`l` 表示逐项传参，以空指针结束可变参数列表。`v` 表示通过指针数组传参，数组末尾也须为空指针。
2. **环境变量：**`execle()` 和 `execve()` 接收调用方提供的环境变量数组。
3. **路径搜索：**`execlp()` 和 `execvp()` 按 `PATH` 搜索不含斜杠的程序名。路径中含斜杠时按给定路径执行。
4. **参数约定：**`argv[0]` 通常填写程序名，数组必须以空指针结束。C++ 字符串字面量不能直接作为 `char*` 参数。应使用生命周期覆盖调用的可写存储。
5. **调用结果：**任一接口成功时都不返回。失败时返回 `-1` 并设置 `errno`。

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

第一个例子用空指针结束可变参数列表。第二个例子用可写字符数组构造 `argv`，并以空指针结束数组。

**Q6: [learning] fork 是什么？C++ 程序如何使用它创建子进程？**

fork 是 POSIX 提供的进程创建接口，声明在 `<unistd.h>`，不属于 ISO C++ 标准库。它把调用进程复制出一个新的子进程，调用一次返回两次：父子进程都从返回处继续执行同一段后继代码，再按返回值确定身份。子进程得到 0，父进程得到子进程的 PID，失败时返回 -1 并设置 errno，此时不会产生子进程。

fork 之后父子进程的关系：

1. **内存内容为副本：**子进程拥有与父进程相同的内存内容，物理页不立即复制（写时复制），fork 的开销主要是页表与进程结构的复制，而不是复制整个地址空间。
2. **打开的文件描述符被继承：**双方指向同一打开文件描述，文件偏移是同一个，一方读写推进偏移，另一方立即受影响。
3. **进程身份独立：**子进程有自己的 PID 与 PPID。父进程先退出时，子进程由 init 收养，继续运行。
4. **只复制调用线程：**`fork()` 复制的只是调用它的那个线程。多线程程序里，其他线程持有的锁在子进程中没有释放者，所以子进程在 exec 之前只能调用异步信号安全函数（如 `write()`、`_exit()`），通常应让子进程立即 exec，或改用 `posix_spawn()` 这类接口。

使用 fork 的步骤：

1. **包含头文件：**`fork()` 与 `pid_t` 声明在 `<unistd.h>`，读取错误原因需要 `<cerrno>`。
2. **调用 `fork()` 并检查返回值：**返回值分三种情况：

    1. **返回 -1：**创建失败，没有子进程产生。读取 errno 决定重试或报错，常见原因有 EAGAIN（进程数触达限制，如 RLIMIT_NPROC）与 ENOMEM（内存不足）。
    2. **返回 0：**当前执行流是子进程，执行它应做的工作。
    3. **返回正数：**当前执行流是父进程，返回值是子进程的 PID，保存它用于后续管理。
3. **子进程收尾用 `_exit()`：**`exit()` 会刷新继承来的 stdio 缓冲副本，把 fork 前尚未刷出的内容送出，父进程退出时再送一遍就重复输出。`_exit()` 不刷新，代价是子进程自己写入 stdio 的内容也不会自动送出，需要输出时在 `_exit()` 前调用 `std::fflush()`。若子进程要换成新程序，由 exec 家族替换映像，成功时不返回。
4. **父进程回收子进程：**用 `wait()` 或 `waitpid()` 读取子进程的退出状态。示例中的 `waitpid(pid, &status, 0)` 只等 pid 指定的子进程，第三个参数 0 表示默认阻塞等待，退出状态写入 status。不回收的已退出子进程保持僵尸状态。

最小示例：子进程打印一行后退出，父进程回收后打印子进程号。

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

父进程用 `waitpid()` 等到子进程结束后再打印，输出顺序固定为两行：

```text
child
parent: child pid = 12345
```

12345 为实际子进程号。示例在 `_exit()` 前显式刷新 stdout：省略 `std::fflush()` 时，重定向到文件或管道会丢掉子进程那行（本机实测）。

**Q7: [learning] 何时应使用 execv()，何时应在 fork() 后调用它？**

是否需要在子进程中调用 `execv()`，取决于调用后父进程是否还需继续执行原程序。若当前进程只需交接给新程序，可直接替换。若父进程需等待或回收子进程，则先 `fork()`。

1. **直接替换：**当前进程完成准备工作后不再需要旧程序逻辑，可直接调用 `execv()`。AAOS 13 first stage init 完成挂载后替换为 `/system/bin/init`，继续使用同一 PID。
2. **子进程执行：**父进程需要等待、回收或继续处理时，先 `fork()`，由子进程调用 `execv()`，父进程使用 `waitpid()` 管理子进程。
3. **失败处理：**`execv()` 仅在失败时返回。成功路径不会执行调用之后的代码，因此应在调用之后处理失败。
4. **参数形式：**参数已存入数组或数量可变时使用 `execv()`。固定参数也可使用 `execl()`。

例如，首阶段交棒给系统 init 的最小形态（AAOS 13 first_stage_init 实测结构）：

```cpp
char path[] = "/system/bin/init";
char mode[] = "selinux_setup";
char* args[] = {path, mode, nullptr};
execv(path, args);
PLOG(FATAL) << "execv(\"" << path << "\") failed";
```

`execv()` 成功后不会返回，因此末行只在失败时执行。标准输出在调用前已重定向到内核日志，且未设置 `FD_CLOEXEC` 的文件描述符会跨映像替换保留。

**Q8: [learning] 如何用 fork 和 execv 创建服务进程？**

用 fork 复制出子进程，让子进程用 execv 把自身替换成服务程序，父进程打印子进程号后继续处理下一个服务，这是 Unix 创建服务进程的固定两步。分两步的原因是 execv 替换整个当前进程：不先 fork 就连父进程一起换掉，无法继续启动其他服务。典型写法如下：

```cpp
pid_t pid = fork();
if (pid == -1) {
    std::cerr << "fork failed, errno = " << errno << std::endl;
    continue;
}
if (pid == 0) {
    execv(svc.path.c_str(), exec_argv.data());
    std::cerr << "execv " << svc.path << " failed, errno = " << errno << std::endl;
    _exit(127);
}
std::cout << "Started service " << svc.name << " (pid " << pid << ")" << std::endl;
```

以上摘自服务启动循环，外层是逐行解析配置的 while 循环。fork、execv 和 _exit 声明在 <unistd.h>，errno 声明在 <cerrno>。

fork 调用一次、返回两次：子进程是父进程的副本，父子进程从 fork 之后的同一条语句继续执行，靠返回值区分身份，取值有三种：

1. **返回 -1：**fork 失败，没有子进程产生，errno 说明失败原因。代码放弃这个服务，继续处理下一个。
2. **返回 0：**当前执行流是子进程。它得到父进程内存的副本，此后的执行与父进程无关。
3. **返回正数：**当前执行流是父进程，返回值就是子进程的 PID，父进程之后可据此管理和回收这个子进程。

子进程分支把自身替换成服务程序。execv 成功后进程映像整体替换，代码和内存都换成新程序的内容，但进程还活着，PID 不变，因此 execv 成功时永不返回，它后面那行报错只在失败时执行。失败时子进程不能继续执行父进程的代码，否则会跟着父进程重复启动后续服务，所以立刻以 _exit(127) 终止。127 是 Unix 惯例，表示程序没能执行起来。

子进程退出用 _exit 而不是 exit，因为 fork 时父进程的 stdio 缓冲区在子进程里有一份副本：

1. **exit()：**终止前刷新缓冲区。父进程 fork 前尚未刷新的输出会随子进程的 exit 提前输出一遍，父进程自己终止时再输出一遍，造成重复。实测父进程先向管道输出一行再 fork，子进程调 exit(0) 时该行输出两次。
2. **_exit()：**立刻终止，不刷新、不清理，继承来的缓冲内容直接丢弃。同一实测改调 _exit(0) 只输出一次（GCC 11 实测）。

能执行到末尾 std::cout 的只有父进程：子进程要么已被 execv 换成服务程序，要么已在失败时 _exit。父进程拿到的 pid 就是子进程编号。

exec_argv.data() 把 vector 的数据交给 execv。execv 的第二个参数要求 C 数组：char* 序列，且以空指针结尾。vector 的元素在内存中连续存放，data() 返回指向首元素的指针，正好满足这个布局要求。使用它有两个前提：exec_argv 的元素类型是 char*，且末尾已经放入空指针。

**Q9: [learning] 什么时候使用 exit()，什么时候使用 _exit()？**

exit() 和 _exit() 都终止进程，区别是死前是否做清理：exit() 会刷新缓冲区并执行清理函数，_exit() 直接进内核终止。正常收尾用 exit()，保证日志落盘、清理逻辑得到执行。fork 之后、exec 之前的子进程必须用 _exit()，因为它的任务已经失败，不能信任任何继承来的状态。execv 与 exit 不是配套关系：execv 成功时进程映像已被整个替换，执行流根本走不到后面的 exit，只有失败才需要它收尾。

两者的清理范围：

1. **exit()：**刷新输出缓冲区，执行 atexit 注册的函数，C++ 还会析构静态存储期对象，最后把退出状态码交回操作系统。
2. **_exit()：**直接进内核终止，不清洗缓冲区、不执行清理函数、不析构静态对象。

fork 出的子进程必须用 _exit，关键在缓冲区会被复制一份。fork 时父进程的 stdio 缓冲区在子进程中有一份副本，父进程尚未刷新的日志也在其中。子进程调 exit() 会尽职尽责地把这份副本刷新出去，父进程稍后自己终止时再刷新一遍，同样的日志就输出两遍。stdout 输出到文件而不是终端时缓冲区不会逐行清空，这种情况尤其常见。实测父进程先向管道输出一行再 fork，子进程调 exit(0) 时该行出现两次，改调 _exit(0) 只输出一次（GCC 11 实测）。

execv 失败是这个规则的典型场景。execv 成功时不返回，它后面的代码只在失败时执行，失败原因包括路径不存在（ENOENT）、没有执行权限（EACCES）、文件不是可执行格式（ENOEXEC），本机实测 errno 分别为 2、13、8。此时子进程仍是父进程的完整副本，若不立刻终止，它会跟着父进程把剩下的启动逻辑再跑一遍，系统里出现两个 init。所以标准收尾是报告错误后用 _exit 退出，并用退出码说明原因：

1. **126：**找到了程序但没有执行权限。
2. **127：**程序不存在或无法执行，execv 失败时用它。
3. **1 至 125：**程序已经跑起来，出错时由它自己返回。

父进程之后用 waitpid 收集退出码，就能区分服务是根本没起来，还是起来后自己出错。

**Q10: [learning] pause() 和 wait() 有什么区别，分别什么时候使用？**

pause() 等信号，wait() 等子进程终止：只是挂起等信号用 pause()，fork 过子进程、要感知退出用 wait()。

1. **pause()：**挂起直到某个被捕获的信号处理函数返回，然后以 -1 加 EINTR 返回，不告诉你是哪个信号。
2. **wait()：**阻塞到某个子进程结束，返回退出者的 PID，退出状态写入 status 并回收该子进程。子进程早已退出时立即返回。

对 init 来说 pause 替代不了 wait：SIGCHLD 的默认处置是忽略，子进程退出不会唤醒 pause（本机实测持续挂起）。装了处理函数能醒，但退出状态只存在内核进程表里，取走它的只有 wait。没人取，退出的子进程就保持僵尸状态。

wait 的三种形式：

1. **wait(&status)：**等任意一个子进程。
2. **waitpid(pid, &status, 0)：**只等指定的子进程。
3. **waitpid(-1, &status, 0)：**等价于 wait()，监督循环用它。

status 用宏拆开：WIFEXITED 判断正常退出还是被信号杀死，WEXITSTATUS 取退出码。Mini-Android init 第二阶段据此写监督循环：

```cpp
// init 永不退出，循环监督全部服务
while (true) {
    int status = 0;  // 内核写入退出状态：退出码或致死信号，用宏解读

    // wait：阻塞等"子进程终止"。醒来时内核返回退出者 PID、写入
    // status、回收子进程（否则留僵尸）。子进程事件只有它能等到。
    pid_t pid = wait(&status);

    // 走到这里是 wait 没等到子进程：无可等子进程，或被信号打断（EINTR）
    if (pid == -1) {
        // pause：阻塞等"信号到达"——与 wait 等的事件不同，这是二者分工线。
        // 它对子进程退出无感（SIGCHLD 默认忽略），也拿不到退出码，
        // 所以子进程事件交给 wait；pause 只在无事可做时兜底挂起，不空转。
        pause();
        continue;  // 被信号唤醒后重试
    }

    Service& svc = FindService(services, pid);  // wait 只给 PID，按它找回服务

    if (WIFEXITED(status)) {
        // 真：正常退出（return 或 exit）
        std::cout << svc.name << " exited, code = "
                  << WEXITSTATUS(status) << std::endl;  // 拆出退出码
    } else {
        // 假：被信号杀死，退出码无意义
        std::cout << svc.name << " terminated abnormally" << std::endl;
    }

    if (svc.oneshot) {
        svc.pid = -1;  // 一次性服务（如开机挂载）：退出即完成，不再管
    } else {
        // 常驻服务退出即异常：重新 fork+execv 拉起，新 PID 记回 svc，
        // 下一轮 wait 靠它匹配
        svc.pid = StartService(svc);
    }
}
```

services 是解析 init.rc 得到的服务列表，每项带 oneshot 标记；StartService 是 fork 加 execv 的启动封装，返回新子进程的 PID；FindService 按 PID 匹配服务，实现摘录时省略。

**Q11: [learning] 什么是僵尸进程，如何消除？**

僵尸进程是已终止但退出状态尚未被父进程读取的子进程：代码和内存都已释放，进程表里只剩一条保存退出状态的记录。实测不 wait 时该条目在 /proc 下的状态为 Z，wait 一次后消失。它只能靠父进程回收：父进程调用 wait 读取该记录，或父进程先退出、僵尸被 init 收养后由 init 回收。

子进程终止时内核必须保留退出状态供父进程读取，而父进程可能远晚于子进程退出才来读，记录不能提前丢弃，于是以僵尸形态存在。wait 能返回退出码，前提就是这条记录还在。

危害有两个：

1. **占用进程号：**每个僵尸占一个 PID 和进程表条目，积累过多会耗尽进程号，无法再创建新进程。
2. **信号无法清除：**僵尸已经终止，向它发信号无效，SIGKILL 也不例外，只能等待被回收。

消除方式：

1. **wait() 或 waitpid()：**父进程及时回收，监督循环里每次处理一个退出的子进程。
2. **SIGCHLD 设为 SIG_IGN：**向内核声明不关心退出状态，子进程终止时自动回收，不产生僵尸，代价是也拿不到退出码（Linux 实测生效）。
3. **父进程退出：**僵尸被 init（PID 1）收养并由 init 回收。父进程活着却不 wait，僵尸会一直存在。

孤儿进程是另一回事：父进程先退出、子进程仍正常运行，被 init 收养后继续执行。僵尸是子进程已死、父进程未回收，两者方向相反。

**Q12: [learning] pause() 配合信号处理函数能替代 wait() 回收子进程吗？**

能实现，但最终仍需调用 waitpid。pause 有三个先天限制：

1. **不携带信号身份：**pause 返回无法区分信号来源，需在处理函数中设置标志变量记录。
2. **不携带事件数据：**SIGCHLD 不传递退出码与终止原因，这些信息记录在内核进程表中，读取入口只有 wait 家族。
3. **存在竞态窗口：**信号若在检查标志与进入 pause 之间到达，唤醒条件已被消耗，pause 将永久阻塞。wait 将等待与回收合并为一个操作，无此窗口。

结论：wait 一步完成阻塞、感知子进程终止与回收；pause 加处理函数加主动查询是手工组装的替代方案，每个环节都需自行实现。

只有要等待的事件没有专用系统调用时才使用信号方案，例如 Ctrl+C、定时器。子进程终止有专用接口，应直接使用 wait 家族。成熟守护进程的常见形态是信号处理函数仅设置标志，主循环用 waitpid(-1, &status, WNOHANG) 非阻塞轮询回收，WNOHANG 使其在无子进程退出时立即返回：信号负责触发，回收由 waitpid 完成。

**Q13: [learning] wait() 解析？**

wait 只等待本进程 fork 创建的子进程，父子关系记录在内核 task_struct 中。wait 阻塞于可中断睡眠，有两个返回出口：子进程终止时返回其 PID 并回收；被捕获信号打断时返回 -1，errno 为 EINTR（Interrupted system call，系统调用被信号中断）。

1. **五种调用场景：**

    | 调用瞬间的状态 | wait 的行为 |
    |---|---|
    | 存在已终止未回收的子进程（僵尸进程） | 立即返回该僵尸进程 PID，填充 status 并释放其进程表记录，不阻塞 |
    | 子进程均存活 | 阻塞至任一子进程终止，返回其 PID |
    | 无任何子进程 | 立即返回 -1，errno 为 ECHILD（No child processes，无子进程） |
    | 阻塞中被已捕获信号打断 | 返回 -1，errno 为 EINTR，即被信号中断 |
    | status 指针非法 | 返回 -1，errno 为 EFAULT（Bad address，地址非法） |

2. **信号何时唤醒：**被忽略（SIGCHLD 默认即此类）和被阻塞屏蔽的信号不产生唤醒；安装了处理函数的信号先执行 handler，wait 返回 -1 加 EINTR；默认处置为终止的信号直接终止进程（发给 PID 1 的被内核丢弃）。sigaction 带 SA_RESTART 时内核自动重启 wait，是否返回 EINTR 取决于安装方式。
3. **子进程事件不走信号通道：**子进程终止由内核直接唤醒 wait，SIGCHLD 默认被忽略不影响该机制。
4. **终止与停止的区别：**终止（terminated）指进程结束运行，终止后成为僵尸进程等待回收；停止（stopped）指被 SIGSTOP 暂停执行，进程仍存活、可被 SIGCONT 恢复。wait 只报告终止，感知停止需 waitpid 加 WUNTRACED。

对监督循环：存在僵尸时 wait 立即返回，退出信息不丢失；多个僵尸每次回收一个；返回 -1 后直接重试会形成忙循环占满 CPU，故统一 pause 挂起等信号。返回值不小于 0 必定对应子进程终止事件；-1 只对应信号打断或无子进程。

**Q14: [learning] pause() 解析？**

pause 无条件挂起，唯一返回方式是被信号打断：永远返回 -1 加 EINTR。具体行为取决于到达信号的处置：

| 到达信号的处置 | pause 的行为 |
|---|---|
| 安装了处理函数 | 执行 handler 后返回 -1 加 EINTR，唯一的正常返回路径 |
| 默认处置为终止或核心转储（SIGINT、SIGSEGV 等） | 进程被终止，不存在"返回" |
| 被忽略（SIGCHLD 默认如此，或 SIG_IGN） | 信号被丢弃，继续阻塞 |
| 处于阻塞屏蔽中 | 信号不递送，继续阻塞 |

PID 1 特权：默认处置为终止的信号发给 init 会被内核丢弃，否则任意信号都可能导致系统崩溃；安装了处理函数的信号不受此限。

现阶段 init 未安装任何信号处理函数，监督循环中的 pause 将永久阻塞——该兜底路径阻塞优于忙循环；实现优雅关机等信号处理后，pause 才用于"被信号唤醒后重试 wait"。