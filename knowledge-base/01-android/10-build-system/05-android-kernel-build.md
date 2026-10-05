# Android 内核构建与验证

> Android 内核构建与验证资料，以 AAOS 13 x86_64 模拟器为案例，并补充平台构建与 Android Common Kernel 的边界。重点是让源码版本、配置、工具链、模块和运行目标保持一致。文中的版本号、commit、工具链目录和镜像文件名均为案例值，不是所有设备的固定值。维护者：session-to-knowledge。

**Q1: [learning] 为什么定制 AAOS 模拟器内核时，不能只选相同的 Linux 主版本或随意使用 `gki_defconfig`？**

模拟器内核必须与目标模拟器的设备模型、预编译模块、配置和用户空间预期相容；仅有相同的 `5.15` 主次版本不保证兼容。配置缺失会让模拟器所需驱动未编入，版本/ABI 不一致则可能导致模块无法加载或虚拟设备通信失败。

材料中的 AAOS 13 x86_64 案例目标是 `5.15.41`，对应源码 commit `24d27dff64c4`；它报告较新 `5.15.208` 内核中的 goldfish_pipe 协议与当时 QEMU 设备模型不兼容，表现为黑屏或 adb offline。这是特定模拟器版本的案例，不应泛化为所有版本的固定协议结论。

在动手前，应从实际目标内核镜像、构建配置及源码树确定所需分支、commit、配置和模块集；升级模拟器或设备后重新核验这些值。

**Q2: [learning] 如何确定与 AAOS 13 模拟器预编译内核匹配的源码版本？**

先从目标内核识别版本/构建标识，再在匹配的 Android common kernel 分支中定位对应源码提交，并确认 Makefile 版本号与构建标识一致。不能只按分支名或补丁版本猜测 commit。

材料案例使用 `android13-5.15` 分支，并通过完整 Git 历史定位到 `24d27dff64c4`，源码 `Makefile` 显示 5.15.41。浅克隆可能不包含目标历史；如果日志中找不到目标提交，先确认是否需要获取完整历史，再切换到经过验证的 commit。`file`、内核启动日志和原始构建信息可用于交叉检查。

提交标识只是该案例的观测值；在其他分支、设备或更新后的预编译内核上，必须重新识别目标，不可复用此 commit。

**Q3: [learning] 为什么应从目标预编译内核提取 `.config`，而不是拼凑 defconfig？**

目标镜像的配置包含该设备需要的驱动、内核功能和模块边界；通用 `gki_defconfig` 或 `x86_64_defconfig` 不能保证包含模拟器启动所需配置。基于提取的配置修改可减少意外缺项。

Linux 内核的 `scripts/extract-ikconfig` 可尝试从支持的内核镜像中提取内嵌配置。将提取结果复制为构建目录 `.config` 后，运行 `make olddefconfig` 以处理当前源码 Kconfig 中新增或变化的选项。应保存原始配置副本，并比较 `olddefconfig` 前后的差异；配置工具可能按当前源码依赖调整选项。

若目标镜像没有可提取配置或配置格式不匹配，应使用该产品正式提供的 defconfig/config fragment 与构建脚本，而非把提取失败后的空文件当作有效配置。

**Q4: [learning] AAOS 13 内核案例中的 `ARCH`、`LLVM`、`LLVM_IAS` 和 `PATH` 各控制什么？**

`ARCH` 选择内核目标架构，`LLVM=1` 请求使用 LLVM 工具链，`LLVM_IAS=1` 请求 LLVM 集成汇编器，`PATH` 决定构建脚本能否找到对应 clang 等工具。它们必须与目标架构和内核构建约定相匹配。

材料中的 x86_64 模拟器示例使用 `ARCH=x86_64`、`SUBARCH=x86_64`、`LLVM=1`、`LLVM_IAS=1`，并把 AAOS 源码树中的 clang 目录加入 `PATH`。具体 clang 版本和目录名属于源码树配置；应检查该分支推荐的内核构建命令与 `prebuilts` 实际路径，不要照搬材料中的 `clang-r450784d` 到其他源码版本。

工具链错配会导致编译错误、产物差异或 ABI 不兼容。需要可复现构建时，把源码提交、`.config`、工具链版本和完整构建参数一起记录。

**Q5: [learning] `make olddefconfig` 和 `make -jN` 在内核构建流程中各自解决什么问题？**

`make olddefconfig` 根据当前 Kconfig 依赖更新已有配置并为新选项采用默认值；`make -jN` 并行编译配置好的目标。前者可能改变 `.config`，后者只有在配置与工具链已正确时才有意义。

应先确保位于正确内核源码树、架构和工具链已设置，并为目标 `.config` 留存副本；再更新配置、检查差异并编译。`N` 根据机器资源设定，过高可能造成内存不足或系统失去响应，并不会修复配置错误。预期产物路径依架构而异；材料案例在 x86 下生成 `arch/x86/boot/bzImage`。

**Q6: [learning] 在内核源码树内添加自定义驱动时，源码、Kconfig、Makefile 与 `.config` 如何关联？**

驱动源文件需被相应 Kconfig 选项和 Kbuild Makefile 收录，配置项启用后构建系统才会编译该目标。若只在源码目录放入 `.c` 文件，通常不会自动进入内核。

1. 选择合适的驱动子目录并添加源码。
2. 在该目录的 Kconfig 菜单声明配置符号及其类型、依赖和默认值。
3. 在同目录 Makefile 用 `obj-$(CONFIG_...) += ...` 绑定目标文件。
4. 通过配置系统启用符号、更新配置并检查其最终值，再编译目标内核。

`obj-y` 表示纳入内建目标，`obj-m` 表示构建为可加载模块（需该配置路径支持模块）。材料示例用 `bool` 配置却解释了 `obj-m`；这两者不匹配：`bool` 只能取 `y/n`，不能产生 `m`。要生成可加载模块，应使用适当的 `tristate` 配置并检查模块支持、依赖和目标部署流程。

**Q7: [learning] 将自编译内核交给模拟器启动时，如何确认替换的是实际运行的内核？**

应先查明该 lunch 目标和启动脚本实际引用的内核镜像路径，再替换对应运行产物并通过启动标识验证。`kernel-ranchu` 是材料所述 Goldfish/Ranchu x86_64 产品输出中的文件名，不是 AAOS 所有设备的通用内核路径。

材料案例把 `arch/x86/boot/bzImage` 放入 `out/target/product/emulator_car_x86_64/kernel-ranchu`，然后从内核日志或 `file` 输出确认版本标识。AOSP 构建再次运行时可能重建或覆盖输出文件；若构建流程从预编译输入重新复制内核，修改的输入副本也可能需要纳入相应构建配置。应追踪该目标的实际依赖规则，避免盲目改写共享 `kernel/prebuilts` 文件。

保留原始内核备份和校验信息，验证失败时恢复。替换构建输入会影响后续其他产品构建，因此应记录改动范围。

**Q8: [learning] 为什么内核和 `.ko` 模块需要来自匹配的构建配置与源码版本？**

`.ko` 不只是一个通用二进制；它依赖内核导出的符号、配置、版本信息和 ABI。内核与模块不匹配时，可能因 `vermagic`、符号版本、目标架构或 ELF 格式不符而拒绝加载，强制绕过检查也不能保证稳定运行。

为设备构建模块时，应使用目标内核对应的源码树、配置、工具链和构建产物（例如适当的 `Module.symvers`），并确认设备正在运行预期内核。若采用不同提交或配置，就应重建相关模块，而不是混用原有预编译 `.ko`。模块是否能加载还受签名、权限、SELinux 和内核模块策略影响。

**Q9: [learning] 更换内核后出现黑屏、adb offline 或启动循环时，如何区分配置、版本和模拟器状态问题？**

先验证正在运行的内核版本和启动日志，再确认模拟器关键驱动、模块兼容性及磁盘状态；单独看到“编译成功”无法证明它可启动目标模拟器。

1. 用 `emulator -show-kernel` 观察早期内核日志，查找 panic、驱动 probe 失败和模块加载错误。
2. 用 `file` 或可用的启动信息确认实际加载的镜像确实是刚构建的版本。
3. 查看 `adb devices` 状态，并结合 goldfish/virtio 等设备驱动的探测结果判断虚拟硬件链路是否工作。
4. 只有在怀疑旧模拟器数据叠加层或快照干扰时，才考虑以备份后的状态启动；`-wipe-data` 或删除 qcow2 会清除模拟器状态，不是无损的常规编译步骤。

若日志显示虚拟设备驱动缺失，优先核查 `.config` 和内核版本；若内核启动但模块失败，核查模块构建配套；若启动日志和镜像均正确，再检查模拟器快照/缓存及产品启动参数。

**Q10: [learning] 为什么 `emulator -show-kernel` 常用于捕获内核早期日志，设备运行后还可用哪些证据验证？**

`-show-kernel` 将模拟器内核控制台输出到终端，适合观察早期启动阶段的信息；系统启动后的 `adb` 状态和设备侧命令则验证用户空间是否与内核正常协作。

材料给出的基本检查包括查看内核版本、观察 `adb devices` 是否从 `offline` 变为 `device`，以及过滤启动日志中的 panic、fail、goldfish、virtio 等信息。`dmesg` 的访问受 root、SELinux 和系统策略影响；不要把“adb root 会清空日志缓冲区”当作普遍保证，是否丢失早期日志应以设备日志行为为准。

单一信号不足以判定成功：镜像版本证明替换路径正确，内核日志证明启动过程，adb 状态与目标功能测试证明系统交互链可用。

**Q11: [learning] 编译 Linux 内核时，`libelf`、`dwarves/pahole`、`flex`、`bison` 等主机依赖解决什么问题？**

内核构建除目标编译器外，还会运行主机侧工具处理配置、目标文件、调试信息和生成代码；缺少这些依赖会在相应构建阶段报错。安装包名和必需项取决于源码分支、配置与主机发行版。

材料的 Ubuntu 案例列出 `build-essential`、`flex`、`bison`、`libssl-dev`、`libncurses-dev`、`libelf-dev` 和 `dwarves`。其中 `libelf-dev` 提供 ELF 处理开发头文件/库，`dwarves` 提供 `pahole`，用于相关 BTF/DWARF 处理；其他包支持本机编译、配置界面和加密等构建环节。遇到 `gelf.h` 缺失或 BTF 生成失败时，结合当前构建日志确认具体主机依赖。

不要把这份 Ubuntu 安装清单当成所有内核分支与 Linux 发行版的固定清单；优先参考目标内核仓库的构建说明，并以实际构建错误补齐依赖。

**Q12: [learning] 为什么编译 Android 平台不等于编译内核，Android Common Kernel 应怎样单独构建？**

AOSP 平台构建通常使用产品配置指定的内核镜像，内核源码和构建目标可能位于独立 checkout；修改内核后必须按匹配分支单独构建，再确认产品实际使用了新产物。

1. **源码与工具：**Android Common Kernel 使用独立分支、配置和工具链。现代分支使用 Bazel/Kleaf，例如 `tools/bazel run //common:kernel_aarch64_dist`；具体目标以该分支说明为准。
2. **版本边界：**`build.sh` 在 Android 14 及以上不受支持；不要把旧分支脚本套用到新版本，也不要仅凭平台 Android 版本推断内核分支。
3. **集成验证：**GKI 镜像不一定包含 vendor modules、DTBO、`vendor_boot` 或签名产物。按设备构建流程集成匹配产物，并用启动日志和设备状态确认实际运行版本。

**Q13: [learning] 用 Cuttlefish 验证 framework 改动的边界在哪？为什么 Android 内核必须单独构建？**

Cuttlefish 适合验证纯 AOSP framework 行为、系统服务与 CTS；它与真机的差异集中在 HAL 及依赖具体硬件的部分，GPU 合成、热控制、SoC 调度、相机和功耗结论仍需真机。使用 CI 产物时 cvd-host_package.tar.gz 与设备 image 必须来自同一次构建，主机包与镜像混搭不能靠"能启动"证明组合受支持；运行前确认 /dev/kvm 存在且当前用户有权限。把自编译 AOSP 刷入 Pixel 前还要核对四件事：当前 tag 存在目标产品与 lunch 配置、driver binaries 与设备和平台 build 匹配、bootloader/radio 固件满足镜像要求、允许 OEM unlocking 且已备份数据——fastboot flashing unlock 与 flashall -w 都涉及数据清除。

内核源码和构建目标通常独立于 Android 平台树，因此平台编译成功不能证明已生成或集成修改后的内核。性能实验应记录并匹配平台与内核版本；内核构建、集成边界按对应内核分支的配置和产物验证。

**Q14: [learning] Android 17 GKI 的 kernel.afdo 是怎么从设备采样生成的？README 的 Pixel 8 收益数字为什么不能写进产品承诺？**

流程是"采样 → 转换 → 重编 → A/B"。测试机（userdebug/eng、root、CoreSight 能力）上用 simpleperf record -e cs-etm:k 录制分支轨迹——cs-etm 是软件接口名，底层可能是 ETM、ARMv9 的 ETE 加 TRBE 缓冲；simpleperf inject 把原始数据转成分支列表（高负载下轨迹可能溢出丢失，建议多次录制）；主机端与未剥离 vmlinux 聚合生成文本 Profile，最后经 create_llvm_prof 转成 kernel.afdo。三个参数不能省：--binary 必须指向匹配的未剥离 vmlinux，否则 Profile 映射到错误源码位置；--use_fs_discriminator 保留编译器路径区分信息；--prof_sym_list=false 避免把未采样的内核函数都当冷代码降级优化——内核 Profile 不可能覆盖错误处理、中断与低频管理路径。

工作负载比采样时长更重要：Profile 只描述采集期间执行的代码，只跑开机或只启动一个应用都会偏斜；GKI README 的代表性流程包括 App Crawler、单应用 crawler 与冷启动组合。A/B 要保持源码标签、Clang/Kleaf 版本、defconfig 与 LTO、设备固件、温度条件一致，唯一变量是是否应用 Profile，并先用构建日志与反汇编确认两组确实分别未用、已用 Profile。

收益边界：README 的 Pixel 8 数据（boot 1.1%、cold launch 6.6%、Binder 系列 15%–23%）标注为 preliminary，Binder 项还是多轮最佳值，且当时 Pixel 对 6.18 的功耗与调频尚未完全调优；这些数字只说明该 Profile 在该实验中的正向变化，OEM 必须在自己的 SoC、调度配置、vendor modules 与关键用户旅程上重做只改 Profile 的受控 A/B。采样本身也有开销：ETM 数据占用硬件缓冲、带宽与后处理时间。
