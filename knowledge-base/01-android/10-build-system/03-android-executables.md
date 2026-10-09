# Android 可执行文件

> 从产物类型、Soong 构建到设备启动，理解 Android 可执行文件及常见运行问题。`/init` 示例按 AAOS 13 源码说明。维护者：session-to-knowledge。

**Q1: [done] 静态链接与动态链接有什么区别？**

静态链接把所需静态库代码并入可执行文件；动态链接则在启动时依赖动态链接器和共享库。AAOS 13 的 `init_first_stage` 静态链接，`init_second_stage` 动态链接：前者启动时 `/system` 尚未挂载，后者启动时系统分区已可用。

1. **首阶段静态链接：**`init_first_stage` 设置 `static_executable: true`，因此启动时不需要动态链接器加载共享库。省略该属性时，Soong 默认构建动态可执行文件。静态链接不代表程序不依赖内核，也不妨碍它主动加载共享库。
2. **系统阶段动态链接：**`init_second_stage` 将 `main.cpp` 和 `libinit` 等代码构建为 `/system/bin/init`，未设置 `static_executable: true`，Soong 因而按动态可执行文件构建。系统分区挂载后，动态链接器先加载 Bionic 共享库，再由 C 运行时从 ELF 入口 `_start` 调用 `main()`；它也可链接静态库，`libinit` 就是此例。

源码依据：AAOS 13 的 `system/core/init/Android.bp`、Soong `cc` 模块实现和 Bionic 启动代码。



**Q2: [done] ELF 解析？**

ELF（Executable and Linkable Format，可执行与可链接格式）是 Linux 与 Android 描述本机二进制的文件格式：可执行程序、共享库 `.so`、可重定位目标文件和核心转储都用它。ELF 只是格式规范，规定字节如何组织；一个文件能否启动，取决于它的 ELF 类型、CPU 架构和依赖，而不是“是不是 ELF”。

1. **格式承载的角色：**ELF 头部的文件类型字段区分用途：`ET_REL` 是可重定位目标文件（编译中间产物，链接器输入），`ET_EXEC` 是传统可执行文件，`ET_DYN` 是共享库或位置无关可执行文件（PIE），`ET_CORE` 是崩溃转储。同一种格式服务多个角色，不能据扩展名判断用途。
2. **两种视图是理解关键：**ELF 同时描述两套结构。节（section）是链接视图，`.text`、`.data`、`.bss` 等按内容划分，供链接器和调试器使用；段（segment）是运行视图，program header 把节按访问权限分组为可加载段。内核与动态链接器只认段，不认节——`strip` 去掉的是符号和调试节，不影响运行段，因此体积变小而运行不变。
3. **谁消费 ELF：**内核执行 `execve` 时按 program header 把可加载段映射进进程地址空间，再从 ELF 头记录的入口地址开始执行；动态文件的 `PT_INTERP` 指定动态链接器路径（Android 为 `/system/bin/linker64`），由它解析依赖共享库。静态可执行文件不经过这一步。
4. **怎么读一个 ELF：**`file` 给出类型、架构和链接方式；`readelf -h` 读头部（类型、架构、入口地址）；`readelf -l` 读段与解释器；`readelf -d` 读动态依赖。例如 AArch64 与 x86-64 的 ELF 不能互换执行，`file` 输出里的架构就是第一道检查。
5. **Android 语境：**`/init`、`linker` 和各 `.so` 都是 ELF；Soong 产出的本机二进制默认是 ELF，现代版本要求可执行文件为 PIE（同样归类为 `ET_DYN`）。APK 与 DEX 不是 ELF，由 ART 在应用进程中加载执行。

例如，在主机上确认一个构建产物的身份：

```console
$ file out/target/product/<产品名>/system/bin/init
$ readelf -h out/target/product/<产品名>/system/bin/init | grep -E 'Type|Machine|Entry'
```

`file` 输出形如 `ELF 64-bit LSB pie executable, ARM aarch64`；`readelf -h` 的 `Type` 字段给出文件类型，`Machine` 给出目标架构，`Entry` 给出入口地址——三者共同回答“这是什么格式、跑在哪种 CPU、从哪里开始执行”。

**Q3: [done] Android 本机可执行文件、ELF、Soong 模块名和设备路径分别是什么？**

这里的“本机可执行文件”指可由 Linux 内核按 `exec` 机制启动的本机程序，Android 平台二进制常见为 ELF。普通进程通常通过 `execve` 或 `execveat` 请求内核装载；启动时内核也可用内部的 `kernel_execve` 启动 `/init`。APK 是应用包，不是本机可执行映像；系统创建或复用应用进程后，由 ART 加载并执行其中的 DEX。共享库虽常为 ELF，仍需动态链接器加载，不能独立启动。ELF 是文件格式，不等于可执行程序；Soong 模块名用于构建依赖，产物文件名和设备安装路径由构建配置决定。

1. **ELF：**描述二进制文件的格式；可执行程序和 `.so` 共享库都可能是 ELF，能否启动还取决于文件类型、CPU 架构、动态链接器及依赖库。
2. **Soong 模块名：**构建图中的名称，例如 `init_second_stage`；它不一定是最终文件名。
3. **产物名与路径：**`stem` 可将产物命名为 `init`，安装规则再决定它位于 ramdisk 还是 `/system/bin/init`。AAOS 13 的 `init` 就由不同模块生成并安装到不同位置。







**Q4: [learning] Android 启动时执行的 `/init` 是什么，为什么源码里有两个 init？**

`/init` 是 ramdisk 根目录中的 ELF 可执行程序，内核启动用户空间时执行它作为 PID 1。AAOS 13 中，它由 Soong 模块 `init_first_stage` 构建；模块名不是设备上的文件名。

1. **模块变成文件：**`system/core/init/Android.bp` 的 `srcs` 指定首阶段 C++ 源码，Soong 将其编译链接为程序。`stem: "init"` 把产物命名为 `init`。若省略，文件名默认取模块名 `init_first_stage`。Linux 不要求可执行文件带扩展名，内核按 ELF 格式识别程序，因此文件可以直接叫 `init`。构建产物在 `out/soong/.intermediates/system/core/init/init_first_stage/<目标变体>/init`。安装到产品输出树后通常是 `out/target/product/<产品名>/ramdisk/init`。
2. **文件路径变成启动路径：**产品把首阶段产物放进 ramdisk 根目录 `/`。根目录下名为 `init` 的文件，其绝对路径就是 `/init`。所以 `/init` 是文件路径，不是模块名或目录名。
3. **两个程序接力：**首阶段 `init` 静态链接，可在 `/system` 尚未挂载时运行；挂载系统分区后，它以 `selinux_setup` 参数执行 `/system/bin/init`。完成 SELinux 初始化后，后者再以 `second_stage` 参数进入完整服务管理。两次都用 `exec` 替换 PID 1 的程序映像，不创建新进程。
4. **在模拟器中查看：**启动后可用 `adb shell ls -l /init` 检查首阶段文件是否仍在；若存在且权限允许，可用 `adb pull /init` 拉到电脑。部分布局会在启动过程中清理或替换初始 ramdisk，且普通 `user` 镜像可能限制读取；此时从 `ramdisk.img` 或启动镜像提取它。启动完成后 PID 1 运行的是 `/system/bin/init`，可用 `adb pull /system/bin/init` 查看当前阶段的程序。`adb root` 仅适用于支持 root adbd 的 `userdebug` 或 `eng` 镜像。

这里的文件名和安装位置对应 AAOS 13 的 ramdisk 启动布局；具体产品应以实际 ramdisk 和启动配置为准。源码见 `system/core/init/Android.bp`、`first_stage_main.cpp`、`first_stage_init.cpp` 和 `main.cpp`。







**Q5: [learning] 源码编译的可执行文件和预编译可执行文件有什么区别？**

区别在输入来源：`cc_binary` 用源码为目标 Android 架构编译程序；`cc_prebuilt_binary` 将已有二进制登记进 Soong 构建图。两者都可能产出可安装、可启动的程序。

1. **有源码：**优先使用 `cc_binary`，由当前 Android 工具链生成适配目标 ABI 和 Android 运行时的 ELF。
2. **只有产物：**使用 `cc_prebuilt_binary` 导入，但先确认它面向 Android、匹配设备 ABI，并具备设备所需的动态链接器和依赖库。
3. **不能只看架构：**宿主机 Linux 程序即使同为 ARM，也可能依赖 glibc；Android 通常使用 Bionic，因此不能据此认定它能在 Android 运行。







**Q6: [learning] `.so`、静态库、Java JAR 和 APK 都是可执行文件吗？**

它们用途不同：本机可执行 ELF 可作为独立程序启动；库供程序链接或加载；JAR 由运行时加载；APK 是应用安装包，安装后由 Android 框架在应用进程中启动组件。

1. **可执行 ELF：**例如 `/init` 或命令行工具，由内核装载并进入程序入口。
2. 共享库 `.so`：供程序运行时加载，本身通常没有可直接启动的程序入口；它虽也是 ELF，但不是命令行程序。
3. 静态库 `.a`：链接时把所需代码并入消费者，通常不作为独立文件在设备上启动。
4. **Java JAR：**包含 Java/DEX 类，普通 JAR 不是内核可执行文件；设备侧程序需由 `app_process` 等运行时入口加载。
5. **APK：**是应用安装包，不是本机可执行文件；系统通过 Zygote 创建或复用应用进程，再由 ART 加载 DEX 代码，Android 框架在进程中启动应用组件。







**Q7: [learning] 构建、打进系统镜像和运行可执行文件分别由什么控制？**

三者是不同步骤：Soong 模块定义构建目标，`m <模块名>` 请求构建，产品配置选择是否安装进镜像，设备上的启动命令或服务配置才负责运行它。

1. **构建：**`m <模块名>` 构建该模块及其依赖；构建成功不代表文件已进入设备镜像。
2. **安装进产品：**产品配置通过 `PRODUCT_PACKAGES` 等方式选择模块；模块的安装属性决定能否安装及目标分区。
3. **启动运行：**命令行程序可由 shell 执行；系统服务通常由 init 的 `.rc` 服务项启动；Java 程序由相应 Android 运行时入口启动。







**Q8: [learning] Android 可执行文件存在却无法运行，应该先查什么？**

先根据错误区分文件未安装、格式或 ABI 不匹配、动态依赖缺失、权限或 SELinux 拒绝；“文件存在”不能证明内核具备启动它所需的全部条件。

1. **找不到文件：**用 `adb shell ls -l <路径>` 核对镜像是否包含该文件、路径是否正确；确认模块确实被产品配置选中。
2. **格式或架构错误：**在主机上用 `file`、`readelf -h` 检查文件格式和 ELF 架构，并与设备 ABI 对照。
3. **提示文件不存在但文件可见：**检查 `readelf -l` 中的解释器路径；动态 ELF 所需的 Android 动态链接器不存在时，也可能出现此现象。
4. **动态库加载失败：**根据链接器错误检查 `readelf -d` 的依赖项，以及对应 `.so` 是否安装、架构和分区是否匹配。
5. **权限被拒绝：**检查文件权限、挂载属性和 SELinux 拒绝日志；仅增加执行位不能绕过 SELinux 策略。


