# AAOS 添加 Soong 模块

> Android 13/AAOS 中新增 C/C++ 与 Java 可执行模块和库的学习资料，覆盖源码构建、预编译导入、依赖、分区安装及设备侧验证。代码片段说明模块类型与关键属性；模块名、路径和目标分区应按实际产品调整。维护者：session-to-knowledge。

**Q1: [learning] [tags:Android.bp] system/core/init/Android.bp 中的 defaults、soong_config_module_type 和构建变量如何改变模块配置？**

defaults 模块集中保存多个模块共用的编译属性与依赖；`soong_config_module_type` 则把指定类型的属性开放给 Soong 配置变量。`init_defaults` 让 init 相关模块共享安全编译选项、库依赖和按构建变体调整的宏定义。

下面摘出原文件中的配置类型、构建变体和 Soong 配置变量。为突出属性关系，默认编译参数和部分宏列表已省略：

```bp
soong_config_module_type {
    name: "libinit_cc_defaults",
    module_type: "cc_defaults",
    config_namespace: "ANDROID",
    bool_variables: ["PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT"],
    properties: ["cflags"],
}

libinit_cc_defaults {
    name: "init_defaults",
    // sanitize、依赖、visibility 和 uml 分支等属性省略
    cflags: ["-DINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=0"],
    product_variables: {
        debuggable: {
            cppflags: [
                "-UALLOW_FIRST_STAGE_CONSOLE",
                "-DALLOW_FIRST_STAGE_CONSOLE=1",
                // 其他调试宏省略
            ],
        },
        eng: {
            cppflags: ["-USHUTDOWN_ZERO_TIMEOUT", "-DSHUTDOWN_ZERO_TIMEOUT=1"],
        },
    },
    soong_config_variables: {
        PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT: {
            cflags: [
                "-UINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT",
                "-DINSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=1",
            ],
        },
    },
    bootstrap: true,
}
```

1. **定义可配置 defaults 类型**：`libinit_cc_defaults` 包装 `cc_defaults`，将配置命名空间设为 `ANDROID`，允许布尔变量 `PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT` 修改 `cflags`。实例 `init_defaults` 列出警告选项、`-Werror`、线程安全检查、若干安全相关宏及 signed-integer-overflow 检查；它把 `static_libs` 与 `shared_libs` 分开声明，例如静态依赖含 `libavb`、`libxml2` 和 sysprop 库，共享依赖含 `libbase`、`libcutils`、`liblog` 和 `libselinux`。`bootstrap: true` 允许该模块使用非 APEX 版本的库，适用于 APEX 激活前启动的程序；defaults 本身仅对子包可见。
2. **应用产品构建变量**：`debuggable` 变体通过先 `-U` 再 `-D` 的编译参数重新定义宏，启用首阶段控制台、本地属性覆盖、宽松 SELinux、panic 后重启 bootloader、可写 kmsg 等调试行为；`eng` 变体启用零超时关机宏；`uml` 变体定义 `USER_MODE_LINUX`。这些是编译期选择，不是设备运行时动态开关。
3. **应用 Soong 配置变量**：当 `PRODUCT_INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT` 为真时，`cflags` 先取消默认的 `INSTALL_DEBUG_POLICY_TO_SYSTEM_EXT=0`，再定义为 `1`。该属性能否生效还取决于产品配置是否为 `ANDROID` 命名空间提供了对应值。
4. **控制首阶段安装**：`init_first_stage_cc_defaults` 包装另一个 `cc_defaults` 类型，只开放 `installable`；当 `BOARD_BUILD_SYSTEM_ROOT_IMAGE` 或 `BOARD_USES_RECOVERY_AS_BOOT` 为真时，将 `init_first_stage_defaults` 的 `installable` 设为 `false`。源码注释说明这是为了避免 system-as-root 时覆盖已有符号链接。
5. **通过 defaults 复用**：模块在 `defaults` 中引用相应 defaults 实例后，才继承其中允许配置的属性；具体模块仍可在自己的属性或 `target` 分支中添加变体专属配置。阅读时应分别追踪 defaults 定义、实例覆盖条件和消费模块，不能把 defaults 名称误当成可执行模块。

首阶段的安装条件由另一个自定义 defaults 类型表达，源码关键部分如下：

```bp
soong_config_module_type {
    name: "init_first_stage_cc_defaults",
    module_type: "cc_defaults",
    config_namespace: "ANDROID",
    bool_variables: ["BOARD_BUILD_SYSTEM_ROOT_IMAGE", "BOARD_USES_RECOVERY_AS_BOOT"],
    properties: ["installable"],
}

init_first_stage_cc_defaults {
    name: "init_first_stage_defaults",
    soong_config_variables: {
        BOARD_BUILD_SYSTEM_ROOT_IMAGE: { installable: false, },
        BOARD_USES_RECOVERY_AS_BOOT: { installable: false, },
    },
}
```









**Q2: init_first_stage 与 init_second_stage 在 system/core/init/Android.bp 中如何区分源码、链接方式和安装目标？**

两个模块都生成名为 `init` 的不同构建变体产物，但承担不同启动阶段：`init_first_stage` 是放入 ramdisk 根目录的静态可执行文件，`init_second_stage` 则以 `main.cpp` 和 `libinit` 组成，并按 platform 或 recovery 目标选择附属文件与依赖。

下面是首阶段链接与安装属性，以及第二阶段 recovery 分支的源码摘录；源码列表、静态库清单和其他属性为突出差异而省略：

```bp
cc_binary {
    name: "init_first_stage",
    stem: "init",
    defaults: ["init_first_stage_defaults"],
    static_executable: true,
    system_shared_libs: [],
    required: ["adb_debug.prop"],
    ramdisk: true,
    install_in_root: true,
}

cc_binary {
    name: "init_second_stage",
    recovery_available: true,
    stem: "init",
    static_libs: ["libinit"],
    srcs: ["main.cpp"],
    target: {
        recovery: {
            cflags: ["-DRECOVERY"],
            exclude_static_libs: ["libxml2"],
            exclude_shared_libs: ["libbinder", "libutils"],
            required: [
                "init_recovery.rc",
                "ueventd.rc.recovery",
                "e2fsdroid.recovery",
                "make_f2fs.recovery",
                "mke2fs.recovery",
                "sload_f2fs.recovery",
            ],
        },
    },
}
```

1. **首阶段源码与链接**：`init_first_stage` 使用首阶段挂载、设备发现、SELinux 标签和切换根目录等源码；将多项底层库列入 `static_libs`，设置 `static_executable: true`，并将 `system_shared_libs` 设为空列表，以构建不自动链接系统默认共享库集合的静态可执行文件。
2. **首阶段安装位置**：模块设置 `ramdisk: true` 和 `install_in_root: true`，将产物放在 ramdisk 根目录；`stem: "init"` 使文件名为 `init`。它还将 `adb_debug.prop` 设为必需模块，并在调试 ramdisk 注释所述场景中提供 adb root 支持。
3. **首阶段变体配置**：`debuggable` 与 `eng` 分支按前述宏配置改变编译行为；`sanitize` 启用 signed-integer-overflow 检查并关闭 hwaddress 检查，源码注释给出的原因是首阶段运行环境可能没有标准输出、标准错误或 `/proc`。
4. **第二阶段及 recovery**：`init_second_stage` 声明 `recovery_available: true`。platform 目标要求 `init.rc`、`ueventd.rc`、`e2fsdroid`、`extra_free_kbytes.sh`、`make_f2fs`、`mke2fs` 和 `sload_f2fs`；recovery 目标增加 `-DRECOVERY`，排除 `libxml2`、`libbinder` 和 `libutils` 等指定依赖，并改用 `init_recovery.rc`、`ueventd.rc.recovery` 及带 `.recovery` 后缀的文件系统工具。`libinit` 自身也提供 recovery 变体，并在该变体排除 `libxml2`、`apex-info-list`、`libbinder` 和 `libutils`。
5. **文件名与模块名**：Soong 模块名分别是 `init_first_stage` 和 `init_second_stage`，两者的 `stem` 都是 `init`。模块名用于构建图和依赖引用，`stem` 用于产物文件名；不能仅凭产物文件名判断它属于哪个阶段。

分析这类属性时要区分“模块可在哪种变体构建”“编译成什么形态”和“安装到哪个镜像位置”：`recovery_available`、`target.recovery`、`static_executable` 与 `ramdisk`/`install_in_root` 分别回答不同问题。









**Q3: system/core/init/Android.bp 如何构建 init 测试、主机校验工具和生成文件？**

该文件把设备测试、基准测试、测试辅助库、主机校验程序与生成规则声明为不同模块。它们复用部分 init 源码或 defaults，但目标平台、依赖和用途不同，不能把它们都当作设备启动程序。

先看测试模块和生成规则的实际声明。以下代码保留关键字段，测试源码清单以及主机 defaults 的其他属性与依赖项有所省略：

```bp
cc_test {
    name: "CtsInitTestCases",
    defaults: ["init_defaults"],
    require_root: true,
    compile_multilib: "both",
    multilib: {
        lib32: { suffix: "32", },
        lib64: { suffix: "64", },
    },
    static_libs: ["libinit"],
    test_suites: ["cts", "device-tests"],
}

genrule {
    name: "generated_stub_builtin_function_map",
    tool_files: ["host_builtin_map.py"],
    out: ["generated_stub_builtin_function_map.h"],
    srcs: ["builtins.cpp", "check_builtins.cpp"],
    cmd: "$(location host_builtin_map.py) --builtins $(location builtins.cpp) --check_builtins $(location check_builtins.cpp) > $(out)",
}
```

主机侧模块共享的配置和消费者如下：

```bp
cc_defaults {
    name: "init_host_defaults",
    host_supported: true,
    generated_headers: [
        "generated_stub_builtin_function_map",
        "generated_android_ids",
    ],
    target: {
        android: { enabled: false, },
        darwin: { enabled: false, },
    },
}

cc_binary {
    name: "host_init_verifier",
    defaults: ["init_host_defaults"],
    srcs: init_common_sources + init_host_sources,
}
```

1. **设备测试**：`CtsInitTestCases` 是 `cc_test`，继承 `init_defaults`，声明 `require_root: true`，并用 `compile_multilib: "both"` 和 `multilib` 为 32 位、64 位变体设置后缀。它编译多项 init 单元测试，静态依赖 `libinit`，并登记到 `cts` 与 `device-tests` 测试套件。
2. **基准测试与辅助库**：`init_benchmarks` 是静态依赖 `libinit` 的 `cc_benchmark`。`libinit_test_utils_libraries_defaults` 集中测试辅助库的共享依赖；`libinit_test_utils` 复用通用源码和测试服务工具源码，整合 `libcap`，导出 `test_utils/include`，供测试代码使用。
3. **生成头文件**：`generated_stub_builtin_function_map` 是 `genrule`，以 `host_builtin_map.py` 为工具、以 `builtins.cpp` 和 `check_builtins.cpp` 为输入，运行命令生成 `generated_stub_builtin_function_map.h`。命令中的 `$(location ...)` 引用已声明输入/工具路径，`$(out)` 指向声明的输出；主机 defaults 再通过 `generated_headers` 消费生成头文件。
4. **主机 defaults 与工具**：`init_host_defaults` 设置 `host_supported: true`，集中指定主机编译选项、依赖、Lite Proto 配置和生成头文件，并在 `target.android`、`target.darwin` 中禁用对应目标。`host_init_verifier` 复用通用源码与主机校验器源码；`libinit_host` 则构建可复用的主机静态库、导出当前目录头文件，并将 Proto 头文件导出给可见消费者。
5. **可见性与脚本模块**：`libinit_host` 的 `visibility` 限定其可被 `system/apex/tools` 子树引用；`init_second_stage` 的可见性另行允许 Virtualization 的 microdroid 子树使用。`extra_free_kbytes.sh` 通过 `sh_binary` 声明为 Shell 脚本模块，其 `src` 指向源码脚本。

阅读生成链路时，应从 `genrule` 的输入、工具、命令和输出追到消费它的 `generated_headers`；阅读主机工具时，则继续检查 host defaults、目标禁用分支和 `visibility`，以判断它构建在哪个平台以及哪些模块能依赖它。




**Q4: 阅读 system/core/init/Android.bp 时，源码变量、libinit、phony 模块和 init_second_stage 如何组成模块依赖图？**

这个文件先定义可复用的源码集合和许可，再由具体 Soong 模块组合成库、可执行文件与别名目标。变量本身不是模块；只有被模块的 `srcs`、`defaults` 等属性引用后，才参与相应模块的构建图。

先看包许可和源码集合怎样进入静态库。下面保留了原文件的关键声明，源码数组中的其他项目用注释标出；模块其余属性也未列出：

```bp
package {
    default_applicable_licenses: ["system_core_init_license"],
}

license {
    name: "system_core_init_license",
    visibility: [":__subpackages__"],
    license_kinds: ["SPDX-license-identifier-Apache-2.0"],
    license_text: ["NOTICE"],
}

init_common_sources = [
    "action.cpp",
    "action_manager.cpp",
    // 其余通用源码项省略
]
init_device_sources = [
    "block_dev_initializer.cpp",
    "bootchart.cpp",
    // 其余设备源码项省略
]

cc_library_static {
    name: "libinit",
    defaults: ["init_defaults", "selinux_policy_version"],
    srcs: init_common_sources + init_device_sources,
    generated_sources: ["apex-info-list"],
    whole_static_libs: [
        "libcap",
        "com.android.sysprop.apex",
        "com.android.sysprop.init",
    ],
}
```

再看入口二进制和 phony 目标的关键字段：

```bp
phony {
    name: "init",
    required: ["init_second_stage"],
}

cc_binary {
    name: "init_second_stage",
    stem: "init",
    static_libs: ["libinit"],
    srcs: ["main.cpp"],
    symlinks: ["ueventd"],
    // recovery_available、defaults、target 和 visibility 等其他属性省略
}
```

1. **包与许可**：`package` 将 `system_core_init_license` 设为默认适用许可；`license` 模块登记 Apache-2.0 类型和 `NOTICE` 文本，并将可见范围限制在子包。
2. **源码集合**：`init_common_sources` 保存解析器、服务、属性、事件和通用工具等共享源码；`init_device_sources` 保存设备启动、挂载、属性服务和设备事件等实现；`init_host_sources` 保存主机侧导入解析与校验器源码。Soong 列表表达式可将这些集合与额外文件拼接。
3. **设备静态库**：`libinit` 是 `cc_library_static`，把通用源码与设备源码编成静态库，并通过 `defaults` 继承 `init_defaults` 和 `selinux_policy_version`。`generated_sources` 加入生成的 `apex-info-list`；`whole_static_libs` 整合 `libcap` 与两个 sysprop 静态库；`header_libs` 引用 `bootimg_headers`；Proto 配置使用 Lite 实现并导出生成头文件。恢复模式有单独的依赖和生成源码排除配置。
4. **可执行模块**：`init_second_stage` 是 `cc_binary`，用 `main.cpp` 作为入口源码并静态依赖 `libinit`。模块名用于 Soong 构建依赖，`stem: "init"` 指定产物文件名；`symlinks: ["ueventd"]` 声明安装时关联的符号链接。
5. **phony 别名**：名为 `init` 的 phony 模块通过 `required` 声明构建目标需关联 `init_second_stage`；`init_system` 也以 `required` 关联它。`required` 是模块构建/安装关联，不是 C++ 链接依赖；phony 自身不编译源码或产生可执行文件。

因此，查看 Soong 依赖时要从具体模块的属性向外追踪：例如请求 `init` 会关联到 `init_second_stage`，后者再依赖 `libinit`，而 `libinit` 的 `srcs` 和 defaults 决定参与编译的源码与公共配置。







**Q5: 我现在会写Android.bp，说说写完之后有什么用是怎么生效的？**











**Q6: 在 AAOS 构建中，Soong、Make/Kati、Ninja、Android.bp/Android.mk 和 m 各自承担什么角色，模块声明又怎样流转为构建动作？**

各角色的职责列表如下：

1. **Android.bp 与 Android.mk**：构建描述文件。Android.bp 用于声明 Soong 模块的类型、源码、依赖和属性；Android.mk 及产品 Makefile 使用 Make 语法描述旧式模块、产品配置和构建目标。
2. **Blueprint 与 Soong**：Blueprint 提供通用的构建描述处理基础；Soong 基于 Blueprint 实现 Android 专用的模块类型和依赖逻辑，解析 Android.bp 并生成 Ninja 规则。
3. **Make 与 Kati**：Make 是旧式构建语法和规则体系；Android 13 构建中，Kati 兼容解析相关 Makefile（包括 Android.mk），并将 Make 侧规则转换为 Ninja 规则。Kati 不负责执行 C/C++ 编译器命令。
4. **Ninja**：执行生成的构建规则，按依赖关系调度编译器、链接器等具体命令；它不是 Android 模块声明格式。
5. **m**：在加载 `build/envsetup.sh` 后使用的 Shell 前端。它调用 `soong_ui`，启动并编排构建流程，使构建系统按当前产品、变体和目标协调 Soong、Kati 与 Ninja。

AAOS 的典型构建链路如下：

1. **选择构建配置**：执行 `lunch` 选择产品与变体；产品配置确定要纳入镜像的模块和产品变量。
2. **启动构建编排**：执行 `m <模块名>` 或 `m`，`m` 调用 `build/soong/soong_ui.bash`，由 `soong_ui` 准备并协调各构建阶段。
3. **解析两类声明**：Soong 读取 Android.bp 并生成自己的 Ninja 规则；Kati 解析产品 Makefile 和旧式 Android.mk，并生成 Make 侧的 Ninja 规则。构建系统会提供兼容桥接，使 Make 侧可以使用 Soong 模块；非全局 Soong namespace 通常还需通过 `PRODUCT_SOONG_NAMESPACES` 暴露给 Make 侧。
4. **执行构建图**：构建系统组合 Soong 与 Kati 生成的规则，再调用 Ninja 按依赖关系执行编译、链接和打包命令。

Android 13 处于渐进迁移阶段，同一构建树中可以同时有 Android.bp 和 Android.mk。新增 Soong 模块时通常在 Android.bp 中声明模块，在产品 .mk 中用 `PRODUCT_PACKAGES` 选择要安装的模块，再通过 `m` 构建；遇到模块未找到时，应分别检查声明、命名空间、产品选择和依赖。











**Q7: 在 AAOS 产品中新增 C/C++ 命令行程序时，`cc_binary`、`PRODUCT_PACKAGES` 和 `m <模块名>` 分别做什么？**

`cc_binary` 声明一个由 Soong 编译的本机可执行模块，`PRODUCT_PACKAGES` 请求产品安装它，`m <模块名>` 则构建该模块及其依赖。构建一个模块与把它打进最终镜像是不同动作。

```bp
cc_binary {
    name: "hello",
    srcs: ["hello.cpp"],
    cflags: ["-Werror"],
    product_specific: true,
}
```

产品 makefile 可添加 `PRODUCT_PACKAGES += hello`。随后在已 `lunch` 的构建环境执行 `m hello` 编译；把镜像同步到设备后，再按安装位置运行程序，例如 `adb shell hello`。`product_specific: true` 将模块归入 product 分区，设备路径仍应根据具体模块类型和安装规则核查。











**Q8: 什么时候使用 `cc_prebuilt_binary` 导入 ELF，为什么不能把宿主机 Linux 程序直接塞进 AAOS？**

只有已经针对 Android 目标 ABI 与运行时构建的 ELF 才适合作为 AAOS 预编译可执行文件导入。宿主机 Linux 程序通常依赖 GNU libc，而 Android 使用 Bionic；即使架构相同，也不能据此认为二进制可运行。

Soong 的预编译模块会检查模块类型和目标架构，运行时还要解析 ELF 所需的动态库与 ABI。推荐顺序是：有源码时用 `cc_binary` 在 Android 构建环境重编；只有产物时确认它确实为 Android/Bionic 和目标 ABI 构建，再用 `cc_prebuilt_binary` 声明并按产品配置安装。绕过构建检查不会修复运行时 ABI 不兼容。











**Q9: C/C++ 动态库、静态库和使用者模块之间如何声明依赖与安装？**

`cc_library_shared` 构建 `.so`，`cc_library_static` 构建静态库，消费模块通过 `shared_libs` 或 `static_libs` 声明依赖。显式依赖让构建图先构建依赖；若可执行程序进入产品包集合，所需共享库通常由依赖关系纳入安装闭包。

```bp
cc_library_shared {
    name: "lib-my-math",
    srcs: ["my_math.cpp"],
    export_include_dirs: ["."],
    product_specific: true,
}

cc_binary {
    name: "hello",
    srcs: ["hello.cpp"],
    shared_libs: ["lib-my-math"],
    product_specific: true,
}
```

`export_include_dirs` 使依赖该库的模块能使用其公开头文件目录。共享库是否作为产品安装内容取决于依赖类型、模块属性和分区规则；可执行模块已加入产品后，一般不需要再把它的共享依赖重复列入 `PRODUCT_PACKAGES`。静态库代码会在链接时并入消费者，不能像 `.so` 一样单独加载。











**Q10: 使用 `cc_prebuilt_library_shared` 时，如何保证 `.so` 的架构、文件名和头文件导出相互匹配？**

预编译共享库要按目标 ABI 提供正确二进制，并在 Soong 声明实际源文件、安装名与公开头文件目录。`arch` 分支应对应源码树中真实存在的 ABI 目录和文件，不能让模块名、`stem` 与文件路径互相矛盾。

```bp
cc_prebuilt_library_shared {
    name: "lib-my-math-prebuilt",
    stem: "lib-my-math",
    arch: {
        x86: {
            srcs: ["lib/x86/lib-my-math.so"],
        },
        x86_64: {
            srcs: ["lib/x86_64/lib-my-math.so"],
        },
    },
    export_include_dirs: ["include"],
    product_specific: true,
}
```

模块名供 Soong 依赖引用，`stem` 控制安装文件名，`srcs` 指向仓库内真实文件。构建前检查 ABI、ELF 目标、SONAME/依赖库和头文件 ABI 一致；同名源码库与预编译库同时存在可能造成模块重名或选错实现，应明确只保留一个定义。











**Q11: Java 源码如何声明为可安装的设备侧可执行 JAR，运行时为什么还需要 `app_process`？**

Soong 的 `java_library` 编译 Java 源码；设置 `installable: true` 可生成可安装的设备侧 JAR，产品包配置负责将其放入镜像。Android 设备并不把普通 JAR 名称当作 shell 命令直接执行，示例通过 `app_process` 启动运行时并指定主类。

```bp
java_library {
    name: "java-hello",
    installable: true,
    product_specific: true,
    srcs: ["**/*.java"],
    sdk_version: "current",
}
```

在源码中给出含 `main` 的类，并把 `java-hello` 纳入产品包。设备上需要设置与实际安装位置相符的 `CLASSPATH`，再执行类似 `app_process /system/bin com.custom.hello.HelloJava`。`product_specific` 会影响分区位置；不要只凭示例路径推断所有产品都安装到同一目录。验证时先检查设备文件，再运行主类。











**Q12: `java_library` 与 `java_import` 的区别是什么，源码库和预编译 JAR 怎样供另一个模块使用？**

`java_library` 从源码构建 Java 模块，`java_import` 将已有 JAR 声明为 Soong 模块；消费者通过模块名建立依赖。一个 JAR 依赖是静态编入还是作为运行时依赖，必须结合 Soong 属性和产物检查，不能只看 `.jar` 后缀判断。

源码库可用 `java_library` 声明 `srcs` 和导出 API，再由消费者的 `static_libs` 引用该模块。预编译库可用 `java_import` 的 `jars` 指向仓库中的 JAR，再让消费者引用这个 `java_import` 的准确模块名。生成 JAR 的文件名、Soong `name` 和被引用模块名是三个不同标识，必须逐一核对；原材料中的预编译示例将依赖名写成了未定义名称，应以 `java_import.name` 为准修正。

库若只供另一个 Java 模块编译使用，通常无需单独安装；最终可运行模块必须按设备侧运行方式配置安装和 classpath。运行预编译 JAR 前还要确认其中包含可用类、与设备运行时兼容，且产物有执行所需的 dex/运行时格式。











**Q13: Java 的 `installable`、`product_specific` 和 `PRODUCT_PACKAGES` 分别控制什么？**

`installable` 控制模块是否作为可安装产物生成，`product_specific` 指定模块的产品分区归属，`PRODUCT_PACKAGES` 将模块请求纳入某个产品。三者回答不同问题，不能互相替代。

1. 只供其他模块依赖的 Java 库可不独立安装，由消费者声明依赖。
2. 需要设备侧加载的 JAR 必须按对应模块类型生成可安装产物，并确保产品选择它。
3. 分区属性应与模块依赖和平台接口要求兼容；移动到 `product` 分区可能影响可见性、加载路径和依赖约束。

编译成功只能证明模块可构建；还需确认它进入目标镜像、运行时 classpath 正确且主类/API 可加载。











**Q14: 产品已安装一个可执行文件时，它的共享库依赖是否还需要单独加入 `PRODUCT_PACKAGES`？**

一般通过 Soong 的模块依赖关系，构建系统能构建并安装可执行模块所需的共享库依赖；产品包集合通常只需选择产品入口模块。但这依赖依赖声明正确且安装分区兼容。

若使用者声明 `shared_libs: ["lib-my-math"]`，执行 `m hello` 会先处理其依赖；把 `hello` 加入 `PRODUCT_PACKAGES` 后，安装闭包通常包含运行所需库。若库被标记为非安装、依赖被裁剪、跨分区访问受限或依赖未通过 Soong 声明，则镜像中仍可能缺少运行时库。应检查构建依赖图、安装清单和设备上的文件，而非仅根据“已声明依赖”推测完成。











**Q15: 如何验证新增加的 Soong 模块从源码定义到设备运行的完整链路？**

验证应分别确认模块被发现、可编译、被产品选择、安装到预期分区，并能在设备上加载运行。

1. 在模块目录添加 `Android.bp`，执行 `m <模块名>` 检查模块声明与依赖能否构建。
2. 在产品配置中加入需要安装的入口模块，然后构建相应镜像或安装目标。
3. 用 `adb sync`、刷写镜像或等效部署方式更新设备，检查预期分区内的文件、架构和权限。
4. 本机二进制可直接从 shell 启动；Java JAR 则按实际安装路径设置 `CLASSPATH` 并用 `app_process` 启动主类。

出现问题时区分 Soong 声明错误、目标 ABI 不匹配、模块未进入产品、安装路径错误、动态库缺失与运行时 API/类加载失败，避免用一次成功编译代替端到端验证。









