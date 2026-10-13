# Android 可执行文件

> 从产物类型、Soong 构建到设备启动，理解 Android 可执行文件及常见运行问题。`/init` 示例按 AAOS 13 源码说明。维护者：session-to-knowledge。

**Q1: [done] 静态链接与动态链接有什么区别？**

区别在库代码何时并入程序，以及可执行文件运行时依赖什么：

1. **静态链接：**构建时把所需静态库（`.a`）的代码复制进可执行文件。产物自包含，内核装载后即可直接运行，不需要动态链接器，也不依赖设备上的其他库文件。
2. **动态链接：**构建时只在可执行文件中记录依赖的共享库（`.so`）名单和待解析符号，库代码仍留在各 `.so` 文件里。程序启动时，动态链接器先加载这些共享库并完成符号解析，程序才进入入口开始执行。动态链接器和依赖的共享库都是设备上独立于程序的文件，缺一程序就无法启动。

AAOS 13 的 `init_first_stage` 静态链接，`init_second_stage` 动态链接，选择依据是启动时系统分区是否可用：

1. **首阶段静态链接：**动态链接器（Android 为 `/system/bin/linker64`）和共享库都位于系统分区，而首阶段运行时 `/system` 尚未挂载，程序必须自包含。`init_first_stage` 设置 `static_executable: true`；省略该属性时，Soong 默认构建动态可执行文件。静态链接不代表程序不依赖内核，也不妨碍它主动加载共享库。
2. **系统阶段动态链接：**`init_second_stage` 将 `main.cpp` 和 `libinit` 等代码构建为 `/system/bin/init`，未设置 `static_executable: true`，Soong 因而按动态可执行文件构建。系统分区挂载后，动态链接器先加载 Bionic 共享库，再由 C 运行时从 ELF 入口 `_start` 调用 `main()`。它也可链接静态库，`libinit` 就是此例。

源码依据：AAOS 13 的 `system/core/init/Android.bp`、Soong `cc` 模块实现和 Bionic 启动代码。



**Q2: [done] 结合实例验证静态链接与动态链接的区别？**

同一份源文件在宿主 Linux 上用 g++ 编译两次，默认动态链接、加 `-static` 静态链接。两个产物都是可执行文件，按链接方式分别叫动态链接可执行文件和静态链接可执行文件；“静态库、动态库”指 `.a` 和 `.so`，程序不按库的类别命名。实测：动态版约 33K，文件里只记录依赖清单；静态版约 2.4M，把所需库代码复制进产物，自包含。

最小程序只用于熟悉源文件、`main()`、标准输出和编译过程：

```cpp
// init.cpp
#include <iostream>

int main() {
    std::cout << "Mini-Android init" << std::endl;
    return 0;
}
```

1. **编译两个版本：**不加 `-static` 时 g++ 默认动态链接，加上则让链接器把所需运行库代码复制进产物。两条命令其余选项一致，保证链接方式是唯一变量；两个产物运行输出同为 `Mini-Android init`，说明链接方式不改变程序行为。

    ```bash
    g++ -std=c++17 -Wall -Wextra -g init.cpp -o init-dyn
    g++ -std=c++17 -Wall -Wextra -g -static init.cpp -o init-static
    ```

    各选项含义与省略后果：

    1. `-std=c++17`：按 C++17 标准编译；省略时用编译器默认标准（默认值随 g++ 版本变化）。
    2. `-Wall -Wextra`：开启两组常用编译警告；省略不改变产物，只减少编译期提示。
    3. `-g`：写入调试信息，支持源码级调试；省略后产物更小、gdb 无法断点到源码，链接方式不变。
    4. `-o`：指定输出路径；省略时产物默认为当前目录的 `a.out`。

2. **对比依赖与内容：**静态版多出的正是动态版留在 `.so` 里的库代码。

    ```console
    $ file init-dyn init-static
    init-dyn:    ELF 64-bit LSB pie executable, x86-64, dynamically linked,
        interpreter /lib64/ld-linux-x86-64.so.2, with debug_info, not stripped
    init-static: ELF 64-bit LSB executable, x86-64, statically linked,
        with debug_info, not stripped
    $ readelf -d init-dyn | grep NEEDED      # libstdc++.so.6、libc.so.6
    $ readelf -d init-static | grep NEEDED   # 无动态段，无输出
    $ nm init-dyn | wc -l                    # 39
    $ nm init-static | wc -l                 # 7152
    ```

    长行折行显示，并省略 BuildID 等字段。按三个面对照：

    1. **依赖声明：**动态版有 INTERP 段（记录解释器路径，即 glibc 动态链接器，Android 上对应 `/system/bin/linker64`）和 NEEDED 清单；静态版没有动态段，`ldd` 输出 `not a dynamic executable`，是预期结果而非错误。
    2. **代码与常量段：**动态版 `.text` 约 402 字节，只有 `main` 和少量胶合代码；静态版约 1.4 MiB，`.rodata` 从 22 字节增至约 140 KiB，增量即 glibc 与 libstdc++ 的实现代码。
    3. **符号表：**动态版里 C 运行时入口 `__libc_start_main`、`std::cout` 的输出实现函数都是 `U`（未定义，等共享库运行时提供）；静态版里同样的函数变为 `T`（定义在本文件内）。

3. **边界：**宿主是 glibc 环境，Android 设备侧使用 Bionic C 库，依赖清单不同但链接方式结论一致，这组命令可在任意 Linux 宿主复现。若要在内核启动时作为 `/init` 工作，动态版还要求 initramfs 中存在配套的动态链接器和共享库，静态版无此要求——这正是 Android 首阶段 init 在 `/system` 未挂载时选择静态链接的依据。

实验依据：宿主 Linux（x86_64、g++、glibc）上同源双编译实测。

**Q3: [done] 魔数是什么，ELF 魔数怎样标识文件格式？**

魔数是文件开头预置的固定字节序列，供读取者按内容而非文件名识别格式。ELF 的魔数是文件前四字节 `0x7f`、`E`、`L`、`F`；内核 execve 和 `file` 等工具都先读它，确认是 ELF 才按 ELF 规则继续解析。

1. **魔数是通用的格式识别机制：**多种格式各有魔数：gzip 开头是 `1f 8b`，PNG 开头是 `89 50 4e 47`，shell 脚本开头是 `#!`。读取者先核对魔数再选择解析方式，对不上就直接拒绝，这正是“认内容不认后缀”的实现方式。
2. **为什么叫“魔数”：**魔数是通用编程术语，这一用法在早期 Unix 时代就已存在，比 ELF 更早。“魔”指取值的含义纯靠约定：数字本身不携带可推导的信息，知道约定的人才能用它完成识别，对局外人如同要知道咒语才生效的魔法，Jargon File（黑客词典）的说法是，只有巫师才知道创造魔数的咒语。命名由来有两层：

    1. **历史层：**早期 Unix 可执行文件的第一个字本身就是 PDP-11 的分支指令，八进制 0407 意为“相对跳转 16 字节”，CPU 执行它就跳过头部数据进入代码，这个字顺带区分了文件类型。头部后来变成纯数据，这个标识字的名字留了下来。
    2. **语义层：**Jargon File 列出魔数的三种义项：二进制文件开头指示文件类型的特殊数据（即本题使用的义项）、哈希和 CRC 等算法中以不透明方式编码关键信息的数字、源码中硬编码且未注释说明来历的数值常量。三种义项的“魔”相同——含义完全来自背景约定，而不是数值本身。ELF 魔数只是第一种义项的一个实例。

3. **ELF 魔数的位置与构成：**魔数占 ELF 头 e_ident 识别数组的前四字节，后续字节继续携带信息：第 5 字节是位数（1 为 32 位、2 为 64 位），第 6 字节是字节序（1 为小端、2 为大端），第 7 字节是 ELF 版本。实测 64 位小端产物的前 7 字节为 `7f 45 4c 46 02 01 01`，读一次文件头就同时得到“是否 ELF、几位、什么字节序”。
4. **谁消费魔数：**

    1. **内核：**execve 按魔数把文件分派给对应装载器，ELF 魔数走 ELF 装载路径，`#!` 走脚本解释路径，都不匹配时报 Exec format error。
    2. **用户态工具：**`file`、链接器、调试器同样先看魔数再决定解析方式。

5. **自查与边界：**`xxd -l 16 <文件>` 直接看头部字节，`readelf -h` 的 Magic 行给出同一内容。魔数正确只证明格式是 ELF，能否执行还要过架构、文件类型和依赖检查；魔数改坏一个字节，内核立即拒绝装载，说明识别完全依赖这几个字节。

依据：ELF 规范的 e_ident 定义、宿主 Linux 上 `xxd`、`readelf -h` 实测输出，命名由来见 Jargon File magic number 条目。

**Q4: [done] 为什么两种链接产物都能被内核直接执行，文件名为什么没有后缀，执行过程有什么区别？**

内核判断能否执行看文件内容与权限位，不看文件名：execve 认出文件头的 ELF 魔数、文件又带执行位，就进入装载，因此两个产物都能直接执行，也都不需要后缀。二者共用的校验相同，区别在内核接手后由谁完成装载。

1. **认内容不认名字：**ELF 文件开头是固定四字节魔数，内核据此和 program header 决定装载方式。后缀只是人类的阅读约定：Windows 用 `.exe` 标记可执行程序，Linux 与 Android 无此要求，平台二进制惯例不带后缀，改名不影响 execve 识别。
2. **共同的执行校验：**两个产物都经过 execve 的同一组检查——魔数有效、CPU 架构与宿主匹配、带执行位，任一不满足就拒绝装载。
3. **执行路径的区别：**

    1. **动态版：**内核先移交 PT_INTERP 记录的解释器（宿主为 `/lib64/ld-linux-x86-64.so.2`），由它加载各 NEEDED 共享库并解析符号后才进入程序入口；宿主缺其中任何文件就启动失败。
    2. **静态版：**内核直接映射它自身的可加载段并进入入口，全程不读外部库文件，因而能在系统分区尚未挂载的早期启动环境运行。

4. **执行成功不等于运行正确：**静态链接的首阶段 init 在宿主上能启动并打印日志，随后连续报 mount /proc、/sys、/dev 失败——宿主不是 initramfs 那种挂载点尚未建立的早期环境，普通进程也无 mount 权限。Mini_Android 的 `build/init` 是最小程序的旧产物，与 `build/init-static` 不同源，输出差异由源码决定，不由链接方式决定。

依据：宿主 Linux 的 execve 装载机制与 Mini_Android 项目实测输出。

**Q5: [done] ELF 解析？**

ELF（Executable and Linkable Format，可执行与可链接格式）是 Linux 与 Android 描述本机二进制的文件格式：可执行程序、共享库 `.so`、可重定位目标文件和核心转储都用它。ELF 只是格式规范，规定字节如何组织。一个文件能否启动，取决于它的 ELF 类型、CPU 架构和依赖，而不是“是不是 ELF”。

1. **格式承载的角色：**ELF 头部的文件类型字段区分用途：`ET_REL` 是可重定位目标文件（编译中间产物，链接器输入），`ET_EXEC` 是传统可执行文件，`ET_DYN` 是共享库或位置无关可执行文件（PIE），`ET_CORE` 是崩溃转储。同一种格式服务多个角色，不能据扩展名判断用途。
2. **两种视图是理解关键：**ELF 同时描述两套结构。节（section）是链接视图，`.text`、`.data`、`.bss` 等按内容划分，供链接器和调试器使用。段（segment）是运行视图，program header 把节按访问权限分组为可加载段。内核与动态链接器只认段，不认节——`strip` 去掉的是符号和调试节，不影响运行段，因此体积变小而运行不变。
3. **谁消费 ELF：**内核执行 `execve` 时按 program header 把可加载段映射进进程地址空间，再从 ELF 头记录的入口地址开始执行。动态文件的 `PT_INTERP` 指定动态链接器路径（Android 为 `/system/bin/linker64`），由它解析依赖共享库。静态可执行文件不经过这一步。
4. **怎么读一个 ELF：**`file` 给出类型、架构和链接方式。`readelf -h` 读头部（类型、架构、入口地址）。`readelf -l` 读段与解释器。`readelf -d` 读动态依赖。例如 AArch64 与 x86-64 的 ELF 不能互换执行，`file` 输出里的架构就是第一道检查。
5. **Android 语境：**`/init`、`linker` 和各 `.so` 都是 ELF。Soong 产出的本机二进制默认是 ELF，现代版本要求可执行文件为 PIE（同样归类为 `ET_DYN`）。APK 与 DEX 不是 ELF，由 ART 在应用进程中加载执行。

例如，在主机上确认一个构建产物的身份：

```console
$ file out/target/product/<产品名>/system/bin/init
$ readelf -h out/target/product/<产品名>/system/bin/init | grep -E 'Type|Machine|Entry'
```

`file` 输出形如 `ELF 64-bit LSB pie executable, ARM aarch64`。`readelf -h` 的 `Type` 字段给出文件类型，`Machine` 给出目标架构，`Entry` 给出入口地址——三者共同回答“这是什么格式、跑在哪种 CPU、从哪里开始执行”。

**Q6: [done] ldd 查的是什么，它怎样得到结果，和 readelf -d 有什么区别？**

ldd 是 glibc 随附的用户态脚本，用于打印动态链接 ELF 的共享库依赖及它们在当前系统上被解析到的路径。man-pages 没有给出 ldd 的全称展开，官方定位就是 print shared object dependencies；网上流传的 List Dynamic Dependencies 是按功能反推的助记，不是官方名称。它不是静态解析文件，而是让动态链接器真正做一遍依赖解析，因此输出反映“当前环境实际会加载哪些库”；readelf -d 只读文件里记录的 NEEDED 名字，不做解析。

1. **结果怎样得到：**ldd 设置环境变量 `LD_TRACE_LOADED_OBJECTS=1` 后把文件交给它的解释器执行；动态链接器见到该变量就不进入程序入口，改为打印每个依赖解析到的路径与加载地址后退出。这就是 ldd 输出比 NEEDED 清单多出路径和地址、并能逐层展开整棵依赖树的原因。
2. **逐行读输出：**以宿主上动态版 init 的实测输出为例，行形只有四类。

    1. **`名字 => 路径 (地址)`：**在磁盘上找到的依赖，路径是当前环境的解析结果，括号里是本次 trace 运行的加载地址，随 ASLR 每次不同。其中 libstdc++.so.6、libc.so.6 是文件 NEEDED 记录的直接依赖；libm.so.6、libgcc_s.so.1 是 libstdc++ 带进来的间接依赖。
    2. **`名字 (地址)`，无 `=>`：**磁盘上没有对应文件。linux-vdso.so.1 是内核映射进每个进程的虚拟库；/lib64/ld-linux-x86-64.so.2 是解释器本身，由内核先行加载。
    3. **环境注入的行：**实测输出里的 libeagleaudithook.so 不在程序 NEEDED 中，来自主机 /etc/ld.so.preload；它自己的 NEEDED 又带入 libdl.so.2、libpthread.so.0。preload 与 audit 库同样进入 ldd 输出，再次说明它反映的是当前环境的解析结果。
    4. **异常与类型判定：**某行显示 not found 表示 NEEDED 点名的库在当前环境缺失，程序将启动失败；静态可执行文件和非 ELF 都报 `not a dynamic executable` 并以非零退出码结束，这是类型判定而非错误。

3. **与 readelf -d 的区别：**

    1. **readelf -d：**静态读动态段，只得到依赖名字；不执行任何代码，也不受架构限制，可用于查看异构架构产物。
    2. **ldd：**依赖宿主链接器真实解析，输出含路径与地址，反映“当前环境的查找结果”；只能用于宿主能装载的 ELF。

4. **安全边界：**ldd 的原理是“真执行一遍”，man 页明确警告不要对不可信可执行文件使用 ldd——某些情况下它会直接执行程序本身或其解释器里的代码，导致任意代码执行。更安全的替代是 `objdump -p <文件> | grep NEEDED`，代价是只看直接依赖，看不到整棵依赖树。
5. **Android 语境：**ldd 是 glibc 宿主工具；Android 设备使用 Bionic 与 toybox，toybox 命令表不含 ldd，设备上通常没有这个命令，查看设备产物依赖应在宿主用 readelf -d。

依据：宿主 glibc 的 ldd 脚本源码、man-pages ldd(1) 的 NAME 与 Security 节、ldd 实测输出，/etc/ld.so.preload 与各库 NEEDED 的交叉核对，toybox 命令表不含 ldd。

**Q7: [done] Android 本机可执行文件、ELF、Soong 模块名和设备路径分别是什么？**

这里的“本机可执行文件”指可由 Linux 内核按 `exec` 机制启动的本机程序，Android 平台二进制常见为 ELF。普通进程通常通过 `execve` 或 `execveat` 请求内核装载。启动时内核也可用内部的 `kernel_execve` 启动 `/init`。APK 是应用包，不是本机可执行映像。系统创建或复用应用进程后，由 ART 加载并执行其中的 DEX。共享库虽常为 ELF，仍需动态链接器加载，不能独立启动。ELF 是文件格式，不等于可执行程序。Soong 模块名用于构建依赖，产物文件名和设备安装路径由构建配置决定。

1. **ELF：**描述二进制文件的格式。可执行程序和 `.so` 共享库都可能是 ELF，能否启动还取决于文件类型、CPU 架构、动态链接器及依赖库。
2. **Soong 模块名：**构建图中的名称，例如 `init_second_stage`。它不一定是最终文件名。
3. **产物名与路径：**`stem` 可将产物命名为 `init`，安装规则再决定它位于 ramdisk 还是 `/system/bin/init`。AAOS 13 的 `init` 就由不同模块生成并安装到不同位置。







**Q8: [learning] Android 启动时执行的 /init 是什么，为什么源码里有两个 init？**

`/init` 是 ramdisk 根目录中的 ELF 可执行程序，内核启动用户空间时执行它作为 PID 1。AAOS 13 中，它由 Soong 模块 `init_first_stage` 构建。模块名不是设备上的文件名。

1. **模块变成文件：**`system/core/init/Android.bp` 的 `srcs` 指定首阶段 C++ 源码，Soong 将其编译链接为程序。`stem: "init"` 把产物命名为 `init`。若省略，文件名默认取模块名 `init_first_stage`。Linux 不要求可执行文件带扩展名，内核按 ELF 格式识别程序，因此文件可以直接叫 `init`。构建产物在 `out/soong/.intermediates/system/core/init/init_first_stage/<目标变体>/init`。安装到产品输出树后通常是 `out/target/product/<产品名>/ramdisk/init`。
2. **文件路径变成启动路径：**产品把首阶段产物放进 ramdisk 根目录 `/`。根目录下名为 `init` 的文件，其绝对路径就是 `/init`。所以 `/init` 是文件路径，不是模块名或目录名。
3. **两个程序接力：**首阶段 `init` 静态链接，可在 `/system` 尚未挂载时运行。挂载系统分区后，它以 `selinux_setup` 参数执行 `/system/bin/init`。完成 SELinux 初始化后，后者再以 `second_stage` 参数进入完整服务管理。两次都用 `exec` 替换 PID 1 的程序映像，不创建新进程。
4. **在模拟器中查看：**启动后可用 `adb shell ls -l /init` 检查首阶段文件是否仍在。若存在且权限允许，可用 `adb pull /init` 拉到电脑。部分布局会在启动过程中清理或替换初始 ramdisk，且普通 `user` 镜像可能限制读取。此时从 `ramdisk.img` 或启动镜像提取它。启动完成后 PID 1 运行的是 `/system/bin/init`，可用 `adb pull /system/bin/init` 查看当前阶段的程序。`adb root` 仅适用于支持 root adbd 的 `userdebug` 或 `eng` 镜像。

这里的文件名和安装位置对应 AAOS 13 的 ramdisk 启动布局。具体产品应以实际 ramdisk 和启动配置为准。源码见 `system/core/init/Android.bp`、`first_stage_main.cpp`、`first_stage_init.cpp` 和 `main.cpp`。







**Q9: [learning] 源码编译的可执行文件和预编译可执行文件有什么区别？**

区别在输入来源：`cc_binary` 用源码为目标 Android 架构编译程序。`cc_prebuilt_binary` 将已有二进制登记进 Soong 构建图。两者都可能产出可安装、可启动的程序。

1. **有源码：**优先使用 `cc_binary`，由当前 Android 工具链生成适配目标 ABI 和 Android 运行时的 ELF。
2. **只有产物：**使用 `cc_prebuilt_binary` 导入，但先确认它面向 Android、匹配设备 ABI，并具备设备所需的动态链接器和依赖库。
3. **不能只看架构：**宿主机 Linux 程序即使同为 ARM，也可能依赖 glibc。Android 通常使用 Bionic，因此不能据此认定它能在 Android 运行。







**Q10: [learning] .so、静态库、Java JAR 和 APK 都是可执行文件吗？**

它们用途不同：本机可执行 ELF 可作为独立程序启动。库供程序链接或加载。JAR 由运行时加载。APK 是应用安装包，安装后由 Android 框架在应用进程中启动组件。

1. **可执行 ELF：**例如 `/init` 或命令行工具，由内核装载并进入程序入口。
2. 共享库 `.so`：供程序运行时加载，本身通常没有可直接启动的程序入口。它虽也是 ELF，但不是命令行程序。
3. 静态库 `.a`：链接时把所需代码并入消费者，通常不作为独立文件在设备上启动。
4. **Java JAR：**包含 Java/DEX 类，普通 JAR 不是内核可执行文件。设备侧程序需由 `app_process` 等运行时入口加载。
5. **APK：**是应用安装包，不是本机可执行文件。系统通过 Zygote 创建或复用应用进程，再由 ART 加载 DEX 代码，Android 框架在进程中启动应用组件。







**Q11: [learning] 构建、打进系统镜像和运行可执行文件分别由什么控制？**

三者是不同步骤：Soong 模块定义构建目标，`m <模块名>` 请求构建，产品配置选择是否安装进镜像，设备上的启动命令或服务配置才负责运行它。

1. **构建：**`m <模块名>` 构建该模块及其依赖。构建成功不代表文件已进入设备镜像。
2. **安装进产品：**产品配置通过 `PRODUCT_PACKAGES` 等方式选择模块。模块的安装属性决定能否安装及目标分区。
3. **启动运行：**命令行程序可由 shell 执行。系统服务通常由 init 的 `.rc` 服务项启动。Java 程序由相应 Android 运行时入口启动。







**Q12: [learning] Android 可执行文件存在却无法运行，应该先查什么？**

先根据错误区分文件未安装、格式或 ABI 不匹配、动态依赖缺失、权限或 SELinux 拒绝。“文件存在”不能证明内核具备启动它所需的全部条件。

1. **找不到文件：**用 `adb shell ls -l <路径>` 核对镜像是否包含该文件、路径是否正确。确认模块确实被产品配置选中。
2. **格式或架构错误：**在主机上用 `file`、`readelf -h` 检查文件格式和 ELF 架构，并与设备 ABI 对照。
3. **提示文件不存在但文件可见：**检查 `readelf -l` 中的解释器路径。动态 ELF 所需的 Android 动态链接器不存在时，也可能出现此现象。
4. **动态库加载失败：**根据链接器错误检查 `readelf -d` 的依赖项，以及对应 `.so` 是否安装、架构和分区是否匹配。
5. **权限被拒绝：**检查文件权限、挂载属性和 SELinux 拒绝日志。仅增加执行位不能绕过 SELinux 策略。


