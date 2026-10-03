# Android 可执行文件

> 从产物类型、Soong 构建到设备启动，理解 Android 可执行文件及常见运行问题。`/init` 示例按 AAOS 13 源码说明。维护者：session-to-knowledge。

**Q1: Android 中的可执行文件、ELF、Soong 模块名和设备路径分别是什么？**

本机可执行文件是能由内核装载并启动的程序；ELF 是常见的文件格式，不等于“可执行程序”。Soong 模块名用于构建依赖，产物文件名和设备安装路径由构建配置决定。

1. **ELF：**描述二进制文件的格式；可执行程序和 `.so` 共享库都可能是 ELF，能否启动还取决于文件类型、CPU 架构、动态链接器及依赖库。
2. **Soong 模块名：**构建图中的名称，例如 `init_second_stage`；它不一定是最终文件名。
3. **产物名与路径：**`stem` 可将产物命名为 `init`，安装规则再决定它位于 ramdisk 还是 `/system/bin/init`。AAOS 13 的 `init` 就由不同模块生成并安装到不同位置。

**Q2: Android 启动时执行的 `/init` 是什么，为什么源码里有两个 init？**

`/init` 是 ramdisk 根目录中的 ELF 可执行程序，内核启动用户空间时执行它作为 PID 1。AAOS 13 中，它由 Soong 模块 `init_first_stage` 构建；模块名不是设备上的文件名。

1. **模块变成文件：**`system/core/init/Android.bp` 的 `srcs` 指定首阶段 C++ 源码，Soong 将其编译链接为程序。`stem: "init"` 把产物命名为 `init`。若省略，文件名默认取模块名 `init_first_stage`。Linux 不要求可执行文件带扩展名，内核按 ELF 格式识别程序，因此文件可以直接叫 `init`。构建产物在 `out/soong/.intermediates/system/core/init/init_first_stage/<目标变体>/init`。安装到产品输出树后通常是 `out/target/product/<产品名>/ramdisk/init`。
2. **文件路径变成启动路径：**产品把首阶段产物放进 ramdisk 根目录 `/`。根目录下名为 `init` 的文件，其绝对路径就是 `/init`。所以 `/init` 是文件路径，不是模块名或目录名。
3. **两个程序接力：**首阶段 `init` 静态链接，可在 `/system` 尚未挂载时运行；挂载系统分区后，它以 `selinux_setup` 参数执行 `/system/bin/init`。完成 SELinux 初始化后，后者再以 `second_stage` 参数进入完整服务管理。两次都用 `exec` 替换 PID 1 的程序映像，不创建新进程。
4. **在模拟器中查看：**启动后可用 `adb shell ls -l /init` 检查首阶段文件是否仍在；若存在且权限允许，可用 `adb pull /init` 拉到电脑。部分布局会在启动过程中清理或替换初始 ramdisk，且普通 `user` 镜像可能限制读取；此时从 `ramdisk.img` 或启动镜像提取它。启动完成后 PID 1 运行的是 `/system/bin/init`，可用 `adb pull /system/bin/init` 查看当前阶段的程序。`adb root` 仅适用于支持 root adbd 的 `userdebug` 或 `eng` 镜像。

这里的文件名和安装位置对应 AAOS 13 的 ramdisk 启动布局；具体产品应以实际 ramdisk 和启动配置为准。源码见 `system/core/init/Android.bp`、`first_stage_main.cpp`、`first_stage_init.cpp` 和 `main.cpp`。

**Q3: 源码编译的可执行文件和预编译可执行文件有什么区别？**

区别在输入来源：`cc_binary` 用源码为目标 Android 架构编译程序；`cc_prebuilt_binary` 将已有二进制登记进 Soong 构建图。两者都可能产出可安装、可启动的程序。

1. **有源码：**优先使用 `cc_binary`，由当前 Android 工具链生成适配目标 ABI 和 Android 运行时的 ELF。
2. **只有产物：**使用 `cc_prebuilt_binary` 导入，但先确认它面向 Android、匹配设备 ABI，并具备设备所需的动态链接器和依赖库。
3. **不能只看架构：**宿主机 Linux 程序即使同为 ARM，也可能依赖 glibc；Android 通常使用 Bionic，因此不能据此认定它能在 Android 运行。

**Q4: `.so`、静态库、Java JAR 和 APK 都是可执行文件吗？**

它们用途不同：本机可执行 ELF 可直接启动；库用于被程序链接或加载；JAR 需由 Android 运行时启动；APK 则由 Android 应用框架安装和启动。

1. **可执行 ELF：**例如 `/init` 或命令行工具，由内核装载并进入程序入口。
2. **共享库 `.so`：**供程序运行时加载，本身通常没有可直接启动的程序入口；它虽也是 ELF，但不是命令行程序。
3. **静态库 `.a`：**链接时把所需代码并入消费者，通常不作为独立文件在设备上启动。
4. **Java JAR：**包含 Java/DEX 类，普通 JAR 不是内核可执行文件；设备侧程序需由 `app_process` 等运行时入口加载。
5. **APK：**是应用安装包，不是 ELF；应用由 Zygote 和 Android 框架创建进程并启动组件。

**Q5: 构建、打进系统镜像和运行可执行文件分别由什么控制？**

三者是不同步骤：Soong 模块定义构建目标，`m <模块名>` 请求构建，产品配置选择是否安装进镜像，设备上的启动命令或服务配置才负责运行它。

1. **构建：**`m <模块名>` 构建该模块及其依赖；构建成功不代表文件已进入设备镜像。
2. **安装进产品：**产品配置通过 `PRODUCT_PACKAGES` 等方式选择模块；模块的安装属性决定能否安装及目标分区。
3. **启动运行：**命令行程序可由 shell 执行；系统服务通常由 init 的 `.rc` 服务项启动；Java 程序由相应 Android 运行时入口启动。

**Q6: Android 可执行文件存在却无法运行，应该先查什么？**

先根据错误区分文件未安装、格式或 ABI 不匹配、动态依赖缺失、权限或 SELinux 拒绝；“文件存在”不能证明内核具备启动它所需的全部条件。

1. **找不到文件：**用 `adb shell ls -l <路径>` 核对镜像是否包含该文件、路径是否正确；确认模块确实被产品配置选中。
2. **格式或架构错误：**在主机上用 `file`、`readelf -h` 检查文件格式和 ELF 架构，并与设备 ABI 对照。
3. **提示文件不存在但文件可见：**检查 `readelf -l` 中的解释器路径；动态 ELF 所需的 Android 动态链接器不存在时，也可能出现此现象。
4. **动态库加载失败：**根据链接器错误检查 `readelf -d` 的依赖项，以及对应 `.so` 是否安装、架构和分区是否匹配。
5. **权限被拒绝：**检查文件权限、挂载属性和 SELinux 拒绝日志；仅增加执行位不能绕过 SELinux 策略。
