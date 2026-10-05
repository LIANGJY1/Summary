# AAOS 添加 Soong 模块

> Android 13/AAOS 中新增 C/C++ 与 Java 可执行模块和库的学习资料，覆盖源码构建、预编译导入、依赖、分区安装及设备侧验证。代码片段说明模块类型与关键属性；模块名、路径和目标分区应按实际产品调整。维护者：session-to-knowledge。

**Q1: [done] [tags:Soong] AAOS 构建中的 Soong 和 Soong 模块分别是什么？**

**Soong 是 Android 的构建系统之一；Soong 模块是它构建图中的基本单元。** `Android.bp` 声明模块，Soong 读取声明并解析依赖与构建变体，生成 Ninja 构建规则；Ninja 再执行具体的编译、链接等命令。

读一个模块定义时，先看这三部分：

1. **类型**：决定模块要执行哪类构建规则，例如 `cc_binary` 构建 C/C++ 可执行文件，`cc_library_shared` 构建共享库，`java_library` 构建 Java 库。
2. **属性**：描述模块的输入和构建方式，例如 `name` 是模块名，`srcs` 指定源文件，`shared_libs` 声明共享库依赖。
3. **依赖**：模块通过依赖属性连接成构建图，Soong 据此确定构建顺序，并为适用的架构、产品配置或分区生成变体。

模块不一定对应一个最终文件：`cc_defaults` 用于复用构建属性，`filegroup` 用于组织文件；而能产出文件的模块也不一定自动进入产品镜像，是否打包由产品配置决定。

**Q2: [learning] 在 AAOS 构建中，Soong、Make/Kati、Ninja、Android.bp/Android.mk 和 m 各自承担什么角色，模块声明又怎样流转为构建动作？**

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

构建失败时，可按阶段定位：Soong 报错通常涉及模块名、属性、依赖或可见性；Kati 报错通常来自 Makefile/Android.mk 解析；Ninja 报错会指向具体编译或链接 action。修改构建描述后可先运行 `m nothing` 检查构建图；它不生成目标产物，编译器和链接器错误仍需构建受影响模块才能发现。

**Q3: [learning] AAOS 中预编译模块与可执行模块分别有哪些应用场景？**

“预编译”描述产物来源：Soong 接收已有文件，不编译其源码；“可执行”描述产物用途：系统能把它作为程序启动。两者可以同时成立 ：`cc_binary` 从源码构建可执行文件，`cc_prebuilt_binary` 导入已有可执行文件；预编译的 `.so` 则是库，不能直接启动。

预编译导入常见于以下场景：

1. **本机二进制**：导入供应商或第三方 ELF 程序、共享库 `.so`、静态库 `.a`，对应 `cc_prebuilt_binary`、`cc_prebuilt_library_shared`、`cc_prebuilt_library_static`。ELF 必须匹配设备 ABI、Android 运行时和依赖库。
2. **Java 与配置文件**：已有 JAR 库用 `java_import`；配置文件可用 `prebuilt_etc` 安装。它们是预编译/预置输入，但不一定是程序。

可执行程序常见于以下场景：

1. **设备侧程序**：命令行工具、诊断程序和 `init` 拉起的系统服务，使用 `cc_binary` 或 `cc_prebuilt_binary`；是否进入镜像仍由产品配置决定。
2. **主机工具与测试**：代码生成、校验工具运行在构建主机；`cc_test`、`cc_benchmark` 供测试或基准测试运行，不是普通产品功能程序。
3. **Java 程序与应用**：Java 主类可打入可安装 JAR，再由 `app_process` 启动；`android_app` 生成的 APK 由 Android 应用框架启动，不是本机 ELF 可执行文件。

Soong 对预编译模块仍处理依赖、目标变体和安装；只是不从该模块的源码重新编译二进制内容。

**Q4: [learning] AAOS 项目什么时候需要新增或修改 Android.bp，模块声明与产品打包如何区分？**

当需要把源码或预编译文件纳入构建，或改变已有模块的输入、依赖、编译属性、变体和安装位置时，才新增或修改 `Android.bp`。若模块已经定义，只是要让产品包含它，应修改产品配置中的模块清单，而不是重复定义模块。

按改动目标选择位置：

1. **新增构建目标**：在源码所属目录的 `Android.bp` 声明模块，按产物选择类型；源码编译使用 `cc_*`、`java_*` 等类型，导入预编译产物则使用对应类型，例如 `cc_prebuilt_binary` 或 `java_import`。
2. **调整已有目标**：优先修改该模块现有定义，变更 `srcs`、依赖、编译选项或分区属性；不要只因想换产物文件名就复制一份模块，可检查 `stem` 等属性是否已满足需求。
3. **选择产品内容**：若目标只是让已定义模块进入产品，在产品配置中加入对应模块名（例如 `PRODUCT_PACKAGES`）；`Android.bp` 定义“如何构建”，产品配置决定“本产品包含什么”。

`Android.bp` 是声明式配置，不写 Makefile 式的流程控制；需要按架构或目标变体调整属性时，使用模块类型支持的 `arch`、`target`、defaults 等机制，具体字段以当前 Android 分支的 Soong 定义为准。

下面的最小示例声明一个共享库和依赖它的可执行文件：

```bp
cc_library_shared {
    name: "libvehicle_util",
    srcs: ["VehicleUtil.cpp"],
    export_include_dirs: ["include"],
}

cc_binary {
    name: "vehicle_diag",
    srcs: ["main.cpp"],
    shared_libs: ["libvehicle_util"],
}
```

`shared_libs` 中写的是 Soong 模块名，不是库文件路径；它既声明链接依赖，也让 Soong 将库纳入构建图。Java 模块使用 `java_library`、`android_app` 等类型，具体可用属性取决于模块类型和 Android 分支。写完后可用 `m vehicle_diag` 单独构建目标；需要把它安装进设备镜像时，还要确认产品配置包含该模块，并核对安装分区属性。

**Q5: [learning] 以 NsrVehicleService/Android.bp 为例，如何逐项解读 Soong 属性、依赖与 APK 安装形态？**

这个文件声明一个系统应用和它的支撑模块：1 个 `android_app` 主体、4 个 `android_library` 源码库、3 个预编译导入和 1 个权限白名单。解读主线：第 1～4 项逐组解析应用块的属性，看它如何变成可安装、可启动的 APK；第 5～7 项解析支撑模块如何接入构建图；第 8 项是声明之外必查的事；第 9 项回答什么时候选 `android_app`。每个属性按“提供什么输入、当前值什么效果、省略后回退到什么默认”三问解读；仅凭模块声明不能断言 APK 已被产品打包。

1. **模块类型与源码输入：**`NsrVehicleService` 使用 `android_app` 声明 APK 模块。此组代码集中展示模块身份、源码、AIDL 搜索路径、资源和 Manifest；`...` 表示省略其他属性，片段仅用于说明，不是完整可编译声明。

    ```bp
    android_app {
        name: "NsrVehicleService",
        srcs: [
            "app/src/main/java/**/*.java",
            "vehiclebase/src/main/java/**/*.java",
            "vehiclebase/src/main/aidl/**/*.aidl",
        ],
        aidl: {
            local_include_dirs: ["vehiclebase/src/main/aidl"],
            include_dirs: ["frameworks/base/media/java"],
        },
        resource_dirs: ["app/src/main/res"],
        manifest: "app/src/main/AndroidManifest.xml",
        ...
    }
    ```

    1. **模块类型：**`android_app` 声明可安装 APK 模块；同样源码若声明为 `android_library`，则产出供其他模块依赖的库。
    2. **模块名：**`name` 是 Soong 模块名，供依赖和产品配置引用；省略会导致模块无法注册，也不等于 APK 包名。
    3. **源码输入：**`srcs` 路径相对当前 `Android.bp`。应用 Java 和 `vehiclebase` Java 直接编入 APK；AIDL glob 选中并编译该目录下的 AIDL，`**` 表示递归匹配子目录。
    4. **模块内 AIDL 搜索根：**`aidl.local_include_dirs` 相对当前模块目录，为 `import` 提供搜索根，不负责选择编译文件。例如，`IVehicleSdkService.aidl` 导入 `com.yadea.apf.vehiclesdk.ITirePressureListener`，编译器会在该根下查找 `com/yadea/apf/vehiclesdk/ITirePressureListener.aidl`。文件即使匹配 `srcs`，仍需搜索根才能按包名找到。
    5. **源码树 AIDL 搜索根：**`aidl.include_dirs` 也为 `import` 提供搜索根，但路径相对 Android 源码树根目录；`local_include_dirs` 相对模块目录。这里指向平台媒体 AIDL，不会自动编译这些文件，也不建立模块依赖。当前 AIDL 导入未见平台媒体接口，无法仅凭此配置确认它必需。
    6. **资源目录：**`resource_dirs` 指定应用资源。默认目录是模块下的 `res`，这里使用非默认路径，所以显式配置。
    7. **Manifest：**`manifest` 指定应用清单。默认位置在模块目录下，这里显式选择 `app/src/main/AndroidManifest.xml`。

    简言之：`srcs` 选择编译输入，AIDL include 目录提供 `import` 搜索根；两者不能互相替代。

2. **编译依赖与 native 库：**此组代码展示编译 classpath、静态依赖和 JNI 库的声明：

    ```bp
    android_app {
        ...
        libs: ["android.car"],
        static_libs: [
            "yadea_anwsdkservice",
            "IviCommSdk",
            "TboxSDK",
            "androidx.annotation_annotation",
            "androidx.core_core",
        ],
        jni_libs: ["libYDBleHandshake"],
        use_embedded_native_libs: true,
        ...
    }
    ```

    1. **编译依赖：**`NsrCarManager.java` 使用 `android.car` API；搜索 `name: "android.car"` 可定位其 Soong 模块。`libs` 引用模块名，为编译器提供类型，不把代码打入 APK，运行时由 AAOS 系统提供，近似 Gradle `compileOnly`。
    2. **打包依赖：**应用运行时需要、系统又不提供的库用 `static_libs`；库代码会并入 APK，Android 库的资源和 Manifest 也会合并。APK 会增加多少取决于实际内容和代码裁剪；本例关闭了优化，不能假设未用代码会被移除。它近似 Gradle `implementation`；Gradle 的 `api`/`implementation` 区别主要是依赖是否传递给下游编译。“静态”指并入 APK，不是 C++ 静态库。
    3. **JNI 库：**`jni_libs` 声明需要随 APK 提供的 native 模块，确保 `System.loadLibrary` 能找到对应 `.so`；省略不会自动打包，运行时可能抛出 `UnsatisfiedLinkError`。
    4. **Native 库打包：**`use_embedded_native_libs: true` 可让 `.so` 不压缩地放入 APK，供系统直接加载，并设置 Manifest 的 `android:extractNativeLibs="false"`。常见用途是省去安装时解压、避免额外的 `.so` 文件副本；需确认没有组件依赖文件系统中的 `.so` 路径。AAOS 13 普通应用默认值为 `false`，通常无需启用。

3. **包名、API、签名与分区：**此组代码展示应用身份、编译 API、签名和安装分区：

    ```bp
    android_app {
        ...
        package_name: "com.yadea.apf.vehicleservice",
        platform_apis: true,
        certificate: "platform",
        privileged: true,
        system_ext_specific: true,
        ...
    }
    ```

    1. **包名：**`package_name` 固定 APK 包名；省略时沿用 Manifest 包名，需与权限白名单等按包名匹配的配置一致。
    2. **平台 API：**`platform_apis: true` 允许针对平台内部 API 编译；本块未设 `sdk_version`，所以显式选择平台 API。通常用于源码树内构建且确实依赖 SDK 未公开 API 的系统组件；只用公开或 System API 时配置相应 `sdk_version`。系统/特权应用身份本身不要求开启此项。
    3. **签名证书：**`certificate: "platform"` 中的 `platform` 是 Soong 预置证书名，表示用 Android 平台签名密钥签 APK；不是安装分区，也不等于 `platform_apis` 或 `privileged`。应用需要与平台组件匹配签名身份时使用；否则用产品默认签名。
    4. **特权身份：**`privileged: true` 将应用安装到特权应用目录。应用需要申请特权权限时使用；权限仍须在对应分区的白名单中授权。
    5. **安装分区：**`system_ext_specific: true` 将应用安装到 `system_ext`。应用属于系统扩展时使用，并让特权权限白名单与应用位于同一分区；省略时使用默认分区。

    这些属性各管一项：包名确定应用身份，平台 API 和证书决定编译与签名方式，`privileged` 决定特权应用资格，分区属性决定安装位置；白名单 XML 单独声明权限授权。

4. **构建开关与安装关联：**此组代码展示优化、预优化、产品条件和关联模块：

    ```bp
    android_app {
        ...
        optimize: {
            enabled: false,
        },
        dex_preopt: {
            enabled: false,
        },
        product_variables: {
            pdk: {
                enabled: false,
            },
        },
        required: ["privapp_whitelist_com.yadea.apf.vehicleservice"],
    }
    ```

    1. **代码优化：**`optimize.enabled: false` 关闭此分支的 R8 优化、压缩与混淆；具体流程以产品配置为准，不能从该值推断设置动机。
    2. **DEX 预优化：**`dex_preopt.enabled: false` 关闭镜像构建阶段的 DEX 预优化，与 R8 是两个独立开关。
    3. **PDK 条件：**`product_variables.pdk.enabled: false` 表示 PDK 变量为真时禁用该模块；省略时不应用这条条件覆盖。
    4. **关联白名单：**`required` 将白名单 XML 模块加入应用的构建/安装依赖闭包，名字须与其模块声明一致；它不代表产品已选择本应用。

5. **源码库模块：**`android_library` 把 Java 源码和 Android 资源构建成供其他模块依赖的库，不是可安装 APK。先完整展示第一个库，说明它的属性；后续库只突出新增或不同配置：

    ```bp
    android_library {
        name: "yadea_vehiclesdk",
        manifest: "vehiclesdk/src/main/AndroidManifest.xml",
        srcs: [
            "vehiclesdk/src/main/java/**/*.java",
        ],
        static_libs: [
            "yadea_vehiclebase",
        ],
        optimize: {
            enabled: false,
        },
    }

    android_library {
        name: "yadea_vehiclebase",
        ...
        srcs: [
            "vehiclebase/src/main/aidl/**/*.aidl",
            "vehiclebase/src/main/java/**/*.java",
        ],
        aidl: {
            local_include_dirs: ["vehiclebase/src/main/aidl"],
        },
        platform_apis: true,
        libs: ["android.car"],
        ...
    }

    android_library {
        name: "yadea_nsrspeechsdk",
        ...
        static_libs: [
            "yadea_vehiclesdk",
            "yadea_anwsdkservice",
            "IviCommSdk",
        ],
        ...
    }

    android_library {
        name: "yadea_anwsdkservice",
        ...
        srcs: [
            "anwsdkservice/src/main/java/**/*.java",
            "anwsdkservice/src/main/aidl/**/*.aidl",
        ],
        aidl: {
            local_include_dirs: ["anwsdkservice/src/main/aidl"],
        },
        ...
    }
    ```

    1. **模块类型与名称：**`android_library` 产出供依赖的库，不单独安装；`name` 是 Soong 模块名，必须唯一，供其他模块引用。
    2. **Manifest：**`manifest` 指定库的清单，路径相对当前 `Android.bp`。省略时默认使用模块目录下的 `AndroidManifest.xml`，该文件不存在就无法构建；本例文件在 `vehiclesdk/src/main/`，所以显式指定。
    3. **源码：**`srcs` 选择本库参与编译的文件；路径相对 `Android.bp`，`**/*.java` 递归匹配 Java 源码。它可省略，但省略后不会编译本库 Java 文件。
    4. **静态依赖：**`static_libs` 声明本库依赖的其他模块；这里依赖 `yadea_vehiclebase`。该属性可省略；若源码用到其他库的类型却未通过依赖提供，构建会缺少对应类型或资源。
    5. **优化开关：**`optimize.enabled: false` 显式关闭库优化；它不是必需属性。AAOS 13 的 `android_library` 默认已关闭优化，因此此处显式设置与省略效果相同。
    6. **后续库的新增属性：**`yadea_vehiclebase` 和 `yadea_anwsdkservice` 还声明 AIDL 输入；`yadea_vehiclebase` 另声明平台 API 和 Car API 依赖：
        1. `aidl.local_include_dirs`：可选搜索根；只有 AIDL `import` 需要按本地目录查找时才配置，不负责选择编译文件。
        2. `platform_apis`：本例未设 `sdk_version`，所以设为 `true`；若按 SDK 编译，则配置对应 `sdk_version`。
        3. `libs`：可选编译依赖；源码需要该模块提供的 Car API 类型时才添加 `android.car`。
    7. **依赖链：**`yadea_nsrspeechsdk` 依赖 `yadea_vehiclesdk`、`yadea_anwsdkservice` 和 `IviCommSdk`。这些关系只建立在对应模块声明中，不能推断它们已被 `NsrVehicleService` 依赖或打入 APK。

6. **预编译库：**这三种模块把现成的 AAR、JAR 或 `.so` 接入构建图，不从源码编译：

    ```bp
    android_library_import {
        name: "IviCommSdk",
        aars: ["app/libs/IviCommSdk.aar"],
    }

    java_import {
        name: "TboxSDK",
        jars: ["app/libs/TboxSDK.jar"],
    }

    cc_prebuilt_library_shared {
        name: "libYDBleHandshake",
        target: {
            android_arm64: {
                srcs: ["app/src/main/jniLibs/arm64-v8a/libYDBleHandshake.so"],
            },
        },
        system_ext_specific: true,
        check_elf_files: false,
    }
    ```

    1. **AAR/JAR：**`aars`、`jars` 指向预编译文件；应用通过 `static_libs` 引用对应模块。AAR 的资源和 Manifest 也会参与合并。
    2. **Native 库：**`target.android_arm64` 只为 ARM64 提供该 `.so`；没有其他架构变体时，不能据此认为其他架构可构建。应用通过 `jni_libs` 引用模块。
    3. **安装与校验：**`system_ext_specific: true` 指定模块安装分区；`check_elf_files: false` 关闭 ELF 文件校验，也就失去相应的 ABI、依赖和符号版本检查。

7. **特权权限白名单：**`required` 把白名单 XML 模块关联到应用，两个模块的关键配置如下：

    ```bp
    android_app {
        ...
        required: ["privapp_whitelist_com.yadea.apf.vehicleservice"],
    }

    prebuilt_etc {
        name: "privapp_whitelist_com.yadea.apf.vehicleservice",
        system_ext_specific: true,
        src: "app/privapp-permissions-com.yadea.apf.vehicleservice.xml",
        sub_dir: "permissions",
        filename_from_src: true,
    }
    ```

    1. **安装关联：**`required` 的模块名必须与 `prebuilt_etc.name` 一致；应用被产品选择安装时，白名单模块随依赖安装。
    2. **安装位置：**`system_ext_specific: true` 与 `sub_dir: "permissions"` 将 XML 安装到 `system_ext/etc/permissions/`；`filename_from_src: true` 保留源文件名。
    3. **权限内容：**`src` 指向现成 XML；系统按包名和权限读取其中规则。仅声明该模块不会自动授予权限。

8. **构建前还需核对：**`Android.bp` 不能单独回答依赖闭包、重复类和产品安装结果：

    1. **依赖闭包：**应用源码导入 `com.yadea.apf.vehiclesdk`，但应用的 `static_libs` 未列 `yadea_vehiclesdk`；确认是否由其他已声明依赖提供。Java `import` 不会建立 Soong 依赖。
    2. **重复类：**应用 `srcs` 和 `yadea_vehiclebase` 都列入 `vehiclebase` 源码；若该库也进入 APK，可能重复定义类，需核对最终依赖图。
    3. **产品安装：**模块声明只定义构建规则；是否进入镜像要查 `PRODUCT_PACKAGES`、Soong 安装清单和目标分区。`required` 不会替代产品对应用的选择。

9. **模块类型怎么选：**按需要的产物和安装方式选择：

    1. **可安装应用 APK：**需要 Manifest、应用资源、权限或分区安装时用 `android_app`；已有 APK 要导入时用 `android_app_import`。
    2. **可复用 Android 库：**供应用合并 Java 代码、资源或 Manifest 时用 `android_library`；已有 AAR 用 `android_library_import`。
    3. **Java 库或程序：**只需 Java 依赖用 `java_library`；已有 JAR 用 `java_import`。是否单独安装和如何启动需另行配置，不能把 JAR 等同于 APK。

**参考：**

1. [Android.bp 文件格式（AOSP）](https://source.android.com/docs/setup/reference/androidbp)：模块属性的类型、`srcs` 和 glob 语义。
2. 具体属性的默认值与省略行为：以目标 Android 分支运行 `m soong_docs` 生成的 Soong Modules Reference 和对应分支 `build/soong` 实现为准。

**Q6: [learning] 读 build/make/core/version_defaults.mk 判断项目基于哪个 Android 版本时，TP1A、REL 和末尾 include 的 version_util.mk 分别起什么作用？**

两棵源码树都由 `build/make/core/version_defaults.mk` 声明版本默认值，并在该文件末尾 include `version_util.mk` 完成目标版本校验与平台版本推导。`TP1A` 是 Android 13 的平台版本键，`REL` 是表示正式发布的代号值；按两棵树的默认配置，推导结果均为 Android 13 / API Level 33。源码只提供默认值，最终以设备上的 `ro.build.version.*` 属性为准；以下只摘录相关源码行。

1. **TP1A 与 REL 各是什么：**两个值共同回答"这是哪个平台版本、处于什么发布状态"：

    1. `TP1A` 是平台版本键：`version_defaults.mk` 以 `DEFAULT_PLATFORM_VERSION := TP1A` 声明默认目标版本，`MIN_PLATFORM_VERSION` 和 `MAX_PLATFORM_VERSION` 也同为 `TP1A`，所以本树合法的目标版本只有 `TP1A`。键的意义是把"哪个平台版本"编码进变量名，使一个分支能按键维护多套版本信息。
    2. `REL` 是代号变量 `PLATFORM_VERSION_CODENAME` 的哨兵值，表示正式发布构建；源码注释写明"最终发布构建的代号就是 REL"。开发态则用甜点代号，例如已知代号列表中的 `Tiramisu`。
    3. `PLATFORM_VERSION_CODENAME.TP1A := REL` 是一条按键组织的映射：变量名内嵌目标版本键，读作"目标版本键 `TP1A` 的代号是 `REL`"。`version_util.mk` 稍后以 `$(PLATFORM_VERSION_CODENAME.$(TARGET_PLATFORM_VERSION))` 查这张映射表。
    4. `TP1A` 也是 Android 13 发布构建 ID 的前缀，如 Yadi 树 `build_id.mk` 提供的 `TP1A.220624.014`；但构建 ID 由 `build_id.mk` 单独声明（本学习树为 `TQ2A.230305.008.C1`），与平台版本键不是同一变量。

2. **为什么在末尾 include version_util.mk：**Make 的 include 会读入目标文件并在当前位置就地处理，处理顺序与书写顺序一致，因此必须先定义默认值、再做校验推导：

    1. `version_util.mk` 的顶层逻辑直接读取 `MIN_PLATFORM_VERSION`、`MAX_PLATFORM_VERSION`、`DEFAULT_PLATFORM_VERSION` 和映射变量：缺省时把 `TARGET_PLATFORM_VERSION` 定为 `TP1A`，校验其合法，再查表解析代号。若它被放在默认值之前，这些输入还是空值，目标版本校验会直接报错，后续推导全部失去依据。
    2. 两份文件按"默认输入"与"校验推导"分工：`version_defaults.mk` 只给默认值，`PLATFORM_SDK_VERSION`、`PLATFORM_SECURITY_PATCH` 等包在 `ifndef` 内，此前配置的预设得以保留；`version_util.mk` 负责校验与推导，并用 `.KATI_READONLY` 锁定结果，防止下游配置再改变版本身份。
    3. 把 include 放在 `version_defaults.mk` 末尾，消费者 `build/make/core/envsetup.mk` 只需 include 一次就得到完整链路；若让消费者自己按顺序 include 两份文件，每个使用者都要自行保证顺序不出错。

3. **版本与 API Level 如何推导：**`version_defaults.mk` 提供以下三个默认值：

    ```make
    PLATFORM_VERSION_LAST_STABLE := 13
    PLATFORM_VERSION_CODENAME.TP1A := REL

    ifndef PLATFORM_SDK_VERSION
      PLATFORM_SDK_VERSION := 33
    endif
    ```

    三个赋值的取值来源与省略后果不同：

    1. `PLATFORM_VERSION_LAST_STABLE := 13`：最近一个正式发布的 Android 版本号，供正式发布构建取平台版本。省略后 `REL` 构建推导出的 `PLATFORM_VERSION` 为空。
    2. `PLATFORM_VERSION_CODENAME.TP1A := REL`：把本树唯一的目标版本键映射到正式发布代号。省略后查表为空，代号回退为目标版本键 `TP1A`，而 `TP1A` 不在已知甜点代号列表中，构建会在代号校验处报错。
    3. `PLATFORM_SDK_VERSION := 33`：平台 API Level。包在 `ifndef` 内，此前已有定义时保留原值；省略且无其他定义时 API Level 为空，`ro.build.version.sdk` 等产物属性失去取值来源。

    `version_util.mk` 随后校验并推导（省略了查表为空时回退到键名的兜底赋值和 `.KATI_READONLY` 只读行）：

    ```make
    ifndef TARGET_PLATFORM_VERSION
      TARGET_PLATFORM_VERSION := $(DEFAULT_PLATFORM_VERSION)
    endif

    ifndef PLATFORM_VERSION_CODENAME
      PLATFORM_VERSION_CODENAME := $(PLATFORM_VERSION_CODENAME.$(TARGET_PLATFORM_VERSION))
    endif

    ifndef PLATFORM_VERSION
      ifeq (REL,$(PLATFORM_VERSION_CODENAME))
        PLATFORM_VERSION := $(PLATFORM_VERSION_LAST_STABLE)
      else
        PLATFORM_VERSION := $(PLATFORM_VERSION_CODENAME)
      endif
    endif
    ```

    1. 目标版本缺省取 `DEFAULT_PLATFORM_VERSION`，即 `TP1A`；取其他值会因不在此树允许范围内而以 error 终止构建，所以默认且唯一合法的目标版本是 `TP1A`。
    2. 代号按映射表解析为 `REL`。
    3. 代号为 `REL` 时，平台版本取 `PLATFORM_VERSION_LAST_STABLE`，得到 `13`，默认应用 targetSdk 取 API Level 33，preview SDK 记为 0；代号不是 `REL` 时，代号本身充当平台版本，targetSdk 也用代号，preview SDK 记为 1。`Tiramisu` 是 Android 13 的甜点代号，不是本树解析出的代号。

4. **安全补丁默认值：**两棵树都把默认补丁级别包在 `ifndef PLATFORM_SECURITY_PATCH` 内，变量未定义或为空时才生效。AAOS13 学习树的赋值是：

    ```make
    PLATFORM_SECURITY_PATCH := 2023-03-05
    ```

    Yadi 源码树的对应行是：

    ```make
    PLATFORM_SECURITY_PATCH := 2025-12-05
    ```

    日期只是源码声明的默认补丁级别，不能证明对应补丁已实际合入；`version_util.mk` 会把该变量设为只读，防止后续配置改动。

5. **在设备或产物上核对：**源码默认值仍可能被产品配置覆盖，最终以设备属性为准。核对时各属性的对应关系如下：

    1. `ro.build.version.release`：平台版本，默认配置下为 `13`。
    2. `ro.build.version.sdk`：API Level，默认配置下为 `33`。
    3. `ro.build.version.security_patch`：实际生效的安全补丁级别，可与源码默认值对照，确认产品是否做过覆盖。
    4. `ro.build.fingerprint`：其中的构建 ID（如 `TP1A.220624.014`）可与 `build_id.mk` 对照，确认分支身份。

    源码结论与设备属性不一致时，以设备属性和实际镜像为准。

**Q7: [learning] [tags:Android.bp] system/core/init/Android.bp 中的 defaults、soong_config_module_type 和构建变量如何改变模块配置？**

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

**Q8: [learning] init_first_stage 与 init_second_stage 在 system/core/init/Android.bp 中如何区分源码、链接方式和安装目标？**

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

**Q9: [learning] system/core/init/Android.bp 如何构建 init 测试、主机校验工具和生成文件？**

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

**Q10: [learning] 阅读 system/core/init/Android.bp 时，源码变量、libinit、phony 模块和 init_second_stage 如何组成模块依赖图？**

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

**Q11: [learning] 在 AAOS 产品中新增 C/C++ 命令行程序时，`cc_binary`、`PRODUCT_PACKAGES` 和 `m <模块名>` 分别做什么？**

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

**Q12: [learning] 什么时候使用 `cc_prebuilt_binary` 导入 ELF，为什么不能把宿主机 Linux 程序直接塞进 AAOS？**

只有已经针对 Android 目标 ABI 与运行时构建的 ELF 才适合作为 AAOS 预编译可执行文件导入。宿主机 Linux 程序通常依赖 GNU libc，而 Android 使用 Bionic；即使架构相同，也不能据此认为二进制可运行。

Soong 的预编译模块会检查模块类型和目标架构，运行时还要解析 ELF 所需的动态库与 ABI。推荐顺序是：有源码时用 `cc_binary` 在 Android 构建环境重编；只有产物时确认它确实为 Android/Bionic 和目标 ABI 构建，再用 `cc_prebuilt_binary` 声明并按产品配置安装。绕过构建检查不会修复运行时 ABI 不兼容。

**Q13: [learning] C/C++ 动态库、静态库和使用者模块之间如何声明依赖与安装？**

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

**Q14: [learning] 使用 `cc_prebuilt_library_shared` 时，如何保证 `.so` 的架构、文件名和头文件导出相互匹配？**

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

**Q15: [learning] Java 源码如何声明为可安装的设备侧可执行 JAR，运行时为什么还需要 `app_process`？**

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

**Q16: [learning] `java_library` 与 `java_import` 的区别是什么，源码库和预编译 JAR 怎样供另一个模块使用？**

`java_library` 从源码构建 Java 模块，`java_import` 将已有 JAR 声明为 Soong 模块；消费者通过模块名建立依赖。一个 JAR 依赖是静态编入还是作为运行时依赖，必须结合 Soong 属性和产物检查，不能只看 `.jar` 后缀判断。

源码库可用 `java_library` 声明 `srcs` 和导出 API，再由消费者的 `static_libs` 引用该模块。预编译库可用 `java_import` 的 `jars` 指向仓库中的 JAR，再让消费者引用这个 `java_import` 的准确模块名。生成 JAR 的文件名、Soong `name` 和被引用模块名是三个不同标识，必须逐一核对；原材料中的预编译示例将依赖名写成了未定义名称，应以 `java_import.name` 为准修正。

库若只供另一个 Java 模块编译使用，通常无需单独安装；最终可运行模块必须按设备侧运行方式配置安装和 classpath。运行预编译 JAR 前还要确认其中包含可用类、与设备运行时兼容，且产物有执行所需的 dex/运行时格式。

**Q17: [learning] Java 的 `installable`、`product_specific` 和 `PRODUCT_PACKAGES` 分别控制什么？**

`installable` 控制模块是否作为可安装产物生成，`product_specific` 指定模块的产品分区归属，`PRODUCT_PACKAGES` 将模块请求纳入某个产品。三者回答不同问题，不能互相替代。

1. 只供其他模块依赖的 Java 库可不独立安装，由消费者声明依赖。
2. 需要设备侧加载的 JAR 必须按对应模块类型生成可安装产物，并确保产品选择它。
3. 分区属性应与模块依赖和平台接口要求兼容；移动到 `product` 分区可能影响可见性、加载路径和依赖约束。

编译成功只能证明模块可构建；还需确认它进入目标镜像、运行时 classpath 正确且主类/API 可加载。

**Q18: [learning] 产品已安装一个可执行文件时，它的共享库依赖是否还需要单独加入 `PRODUCT_PACKAGES`？**

一般通过 Soong 的模块依赖关系，构建系统能构建并安装可执行模块所需的共享库依赖；产品包集合通常只需选择产品入口模块。但这依赖依赖声明正确且安装分区兼容。

若使用者声明 `shared_libs: ["lib-my-math"]`，执行 `m hello` 会先处理其依赖；把 `hello` 加入 `PRODUCT_PACKAGES` 后，安装闭包通常包含运行所需库。若库被标记为非安装、依赖被裁剪、跨分区访问受限或依赖未通过 Soong 声明，则镜像中仍可能缺少运行时库。应检查构建依赖图、安装清单和设备上的文件，而非仅根据“已声明依赖”推测完成。

**Q19: [learning] 如何验证新增加的 Soong 模块从源码定义到设备运行的完整链路？**

验证应分别确认模块被发现、可编译、被产品选择、安装到预期分区，并能在设备上加载运行。

1. 在模块目录添加 `Android.bp`，执行 `m <模块名>` 检查模块声明与依赖能否构建。
2. 在产品配置中加入需要安装的入口模块，然后构建相应镜像或安装目标。
3. 用 `adb sync`、刷写镜像或等效部署方式更新设备，检查预期分区内的文件、架构和权限。
4. 本机二进制可直接从 shell 启动；Java JAR 则按实际安装路径设置 `CLASSPATH` 并用 `app_process` 启动主类。

出现问题时区分 Soong 声明错误、目标 ABI 不匹配、模块未进入产品、安装路径错误、动态库缺失与运行时 API/类加载失败，避免用一次成功编译代替端到端验证。

**Q20: [learning] Android 的 `user`、`userdebug` 和 `eng` 构建变体有什么区别，车机调试和验收该怎么选？**

一句话理解：`user` 面向量产，默认收紧调试能力并贴近消费者设备；`userdebug` 保留接近 `user` 的运行特征，同时开放更多调试手段；`eng` 面向开发，构建更快，性能和功耗不作为首要目标。AOSP `lunch` 目标的最后一段指定构建变体；新版目标还可能在产品名与变体之间带 release config。[AOSP 构建说明](https://source.android.com/docs/setup/build/building)

| 属性或行为 | `user` | `userdebug` | `eng` |
|---|---|---|---|
| 用途 | 量产与正式验收 | 开发调试、性能和功耗验证 | 日常系统开发 |
| `ro.build.type` | `user` | `userdebug` | `eng` |
| `ro.debuggable` | `0` | `1` | `1` |
| `ro.secure`（AOSP 默认） | `1` | `1` | `0` |
| ADB 提权 | 通常不支持 `adb root` | 通常支持 `adb root` | 通常默认以 root adbd 运行 |
| SELinux | 量产设备应为 enforcing；普通 ADB 调试不能切为 permissive | 可 root 后执行 `setenforce 0` | 可 root 后执行 `setenforce 0` |
| 调试模块 | 按产品配置安装，通常不选 debug/eng 工具 | 在 user 模块外增加 debug 工具 | 增加 debug/eng 工具 |

构建变体只决定一组默认构建属性和调试策略，产品配置、设备配置和发布签名流程仍可能覆盖部分行为。[AOSP `main.mk`](https://android.googlesource.com/platform/build/%2B/HEAD/core/main.mk) 明确区分 `ro.debuggable`、`ro.secure` 和各变体安装的模块标签；Android 官方说明 `user` 用于 production，`userdebug` 保留调试能力，SELinux 可在 `userdebug`/`eng` 上通过 ADB root 切换 permissive。[SELinux 验证说明](https://source.android.com/docs/security/features/selinux/validate)

1. **调试权限：**`user` 默认 `ro.debuggable=0`，`adbd` 不提供 root 提权；`userdebug` 可用 `adb root`，`eng` 通常默认使用 root adbd。`adb remount`、写系统分区和访问其他应用私有数据还分别受分区只读、Verified Boot、Linux UID 和 SELinux 限制，不能简单等同于“有 root 就都能操作”。
2. **SELinux：**生产设备必须保持 enforcing；AOSP 支持在 `userdebug` 或 `eng` 上通过 `adb root` 后执行 `setenforce 0`。因此排查 SELinux denial 时，可先在调试版本定位策略问题，再在 `user` 版本验证 enforcing 下的真实行为；量产设备不应依赖 permissive。[AOSP SELinux 文档](https://source.android.com/docs/security/features/selinux/validate)
3. **模块与日志：**旧式 `Android.mk` 的 `LOCAL_MODULE_TAGS` 可将模块标记为 `user`、`debug` 或 `eng`；`userdebug` 会在 `user` 模块外增加 debug 工具，`eng` 会安装 debug/eng 工具。但 APK 安装和现代产品打包还受产品配置控制，不能据 `LOCAL_MODULE_TAGS := eng` 推断所有工具或测试 APK 都必然被排除。`ALOGD`、`ALOGV` 是否输出也取决于各模块的编译宏、日志配置和运行时过滤，不是所有 user 构建统一关闭。
4. **签名与属性：**`TARGET_BUILD_VARIANT` 是构建时变体；设备上的 `ro.build.type` 表示 `user`、`userdebug` 或 `eng`，`ro.debuggable` 和 `ro.secure` 反映调试/安全默认值；`ro.build.tags` 表示签名标签，如 `test-keys`、`dev-keys` 或 `release-keys`，不等同于构建变体。AOSP 默认构建会使用公开的 test keys，正式发布必须由厂商发布流程换成私有 release keys；所以 `user` 不会自动等于 `release-keys`，平台签名 APK 也必须使用匹配的厂商平台密钥。[AOSP 发布签名说明](https://source.android.com/docs/core/ota/sign_builds)
5. **车机版本选择：**日常开发和需要 `adb root`、完整调试工具的排障使用 `userdebug`；量产前复现、权限验证和验收应使用与交付配置一致的 `user` 版本。`user` 上日志较少时，可结合 bugreport、系统事件日志、持久化日志和崩溃现场分析；不要把 `userdebug` 上可关闭 SELinux 或可提权的结果直接当作量产行为。

**Q21: [learning] Make 与 Android.bp 的变量赋值、引用和条件语法有何区别？**

读 `.mk` 和 `Android.bp` 时，先分清变量赋值与模块属性。以下示例只演示语法。

1. **Make 赋值与引用：**下面的变量先取 `13`，随后改为 `14`：

    ```make
    NEXT := 13
    DEFERRED = $(NEXT)
    IMMEDIATE := $(NEXT)
    NEXT := 14
    ```

    读取变量时，各符号的作用如下：

    1. `=`：在使用时展开右侧，因此 `DEFERRED` 得到更新后的 `14`。
    2. `:=`：在赋值时展开右侧，因此 `IMMEDIATE` 保留 `13`。
    3. `$(NAME)`：引用变量。示例中的 `$(NEXT)` 读取 `NEXT` 的值。
    4. `+=`：在已有变量后追加值。

2. **Make 条件：**AAOS13 的 `version_defaults.mk` 有如下源码：

    ```make
    ifndef PLATFORM_SDK_VERSION
      PLATFORM_SDK_VERSION := 33
    endif
    ```

    `ifndef` 在变量未定义或值为空时进入分支，将 API Level 设为 `33`。已有非空值时保留原值。

3. **Android.bp 变量与模块：**下例展示变量、列表和模块属性：

    ```bp
    src_files = ["main.cpp"]
    src_files += ["util.cpp"]
    cc_binary {
        name: "demo",
        srcs: src_files,
    }
    ```

    这些语法各承担一项职责：

    1. `src_files = ["main.cpp"]`：用 `=` 定义列表变量，`[]` 包住列表，双引号包住文件名。
    2. `src_files += ["util.cpp"]`：在首次引用前追加第二个文件，此后列表包含两个源码路径。
    3. `cc_binary { ... }`：声明可执行模块。属性采用 `键: 值,` 的格式。
    4. `name: "demo"`：为模块命名。省略会使模块缺少必需名称。
    5. `srcs: src_files`：直接引用变量并编译两个文件。省略后，这两个文件不会因此进入编译。

    Android.bp 的 `=` 不延迟展开，也不使用 Make 的 `:=` 或 `$(NAME)`。

4. **Android.bp 条件：**Android.bp 不支持 Make 式 `if/ifndef`。配置差异写入 Soong 支持的模块属性（如 `soong_config_variables`），复杂逻辑由 Go 构建代码处理。

**Q22: [learning] 如何结合 Android 13 源码判断项目的 `adb root` 权限和实际能力？**

判断 `adb root` 要沿着“构建属性 → root 请求 → `adbd` 降权 → 产品覆盖”检查。两个项目的 ADB 核心实现相同，但具体产品选用的构建变体和属性覆盖决定最终结果；源码树本身不能证明某个已编译镜像当前正在运行哪种变体。

1. **检查变体默认值：**两棵树的 `build/make/core/main.mk` 都根据 `TARGET_BUILD_VARIANT` 设置属性。`user` 默认 `ro.secure=1`、`ro.debuggable=0`；`userdebug` 默认 `ro.secure=1`、`ro.debuggable=1`；`eng` 默认 `ro.secure=0`、`ro.debuggable=1`。`ro.debuggable` 控制是否允许调试提权，`ro.secure` 控制 `adbd` 是否默认降权；实际值仍可能被产品配置覆盖。

2. **检查 root 请求门槛：**两棵树的 `packages/modules/adb/daemon/restart_service.cpp` 相同。`restart_root_service()` 在 `__android_log_is_debuggable()` 为 false 时拒绝请求；该检查对应 `ro.debuggable`。通过后，代码设置 `service.adb.root=1` 并重启 `adbd`，让新进程重新判断是否保留 root。

3. **检查 `adbd` 身份：**两棵树的 `packages/modules/adb/daemon/main.cpp` 中，`should_drop_privileges()` 先以 `ro.secure` 初始化降权状态；只有 `ro.debuggable=1` 且 `service.adb.root=1` 时，才取消降权；`service.adb.root=0` 则要求降权。降权路径通过 minijail 将 `adbd` 的 UID/GID 改为 `shell`。两棵树的 `packages/modules/adb/Android.bp` 都未设置 `ALLOW_ADBD_ROOT`，因此应以实际的请求处理和降权代码为准，不能套用其他分支的宏判断。

4. **区分 ADB 鉴权：**`ro.adb.secure` 控制连接主机是否需要 ADB 授权，不是 `adbd` 是否以 root 运行的开关。两棵树的 `main.cpp` 将它用于主机认证判断；因此“需要电脑端 RSA 授权”和“shell 是否拿到 root”是两个独立问题。

5. **得出默认结果：**按两棵树的 `main.mk` 默认值，`user` 上 `adb root` 会被拒绝，`adbd` 以 `shell` 身份运行；`userdebug` 初始以 `shell` 运行，`adb root` 成功后重启为 root；`eng` 通常默认以 root 运行，`adb unroot` 后降为 `shell`。这只是变体默认行为，必须再检查产品配置。

6. **检查项目覆盖：**Yadi 的 `vendor/yadea` 和相关 `device/sprd` 配置中没有检出 `service.adb.root` 覆盖。`AAOS13` 中 Google GS101/GS201 的 `factory_common.mk` 设置 `service.adb.root=1`，GS101 还设置 `ro.adb.secure=0`；只有继承该配置的具体产品才受影响，而且 root 结果仍需结合 `ro.debuggable` 判断。两棵树都包含 debug ramdisk 的强制调试路径，但它要求特定 debug boot image 和设备已解锁，不能据此推断普通锁定的量产镜像允许 root。

7. **界定 root 的能力：**`adb root` 让 ADB shell 链路中的命令以 root 身份运行，不会自动关闭 SELinux，也不保证系统分区可写。`adb remount` 是否成功还受 Verified Boot、动态分区和 remount 配置影响；分析实际权限时，应分别确认进程 UID、SELinux enforcing 状态和分区挂载状态。

8. **核对最终设备：**有设备或构建产物时，先查看 `ro.build.type`、`ro.debuggable`、`ro.secure`、`ro.adb.secure` 和 `service.adb.root`，再运行 `adb shell id` 确认实际 UID；`getenforce` 用于确认 SELinux 模式，`adb remount` 的结果用于确认分区写入能力。源码结论与设备属性不一致时，以实际产品配置和最终镜像为准。

**Q23: [learning] 应用要在 Android 源码树中编译时，怎样判断 `platform_apis`、签名、特权身份和安装分区该如何配置？**

不要照抄其他应用的属性。先确认应用需要哪类 API、权限由谁授予、是否需要特权权限，以及产品要把应用安装到哪个分区；四项分别控制编译 API、签名身份、特权安装和安装位置。

1. **确认编译 API：**检查代码实际使用的 API，并用目标分支的 SDK 编译验证。
    1. 只用公开 API：配置匹配的 `sdk_version`，例如 `current`。
    2. 只用 System API：分支提供对应 SDK 时，配置 `system_current`。
    3. 确实调用 SDK 未公开的平台 API：源码树内构建时设 `platform_apis: true`，省略 `sdk_version`。它只影响编译可见 API，不授予运行时权限。

2. **确认签名证书：**查看应用申请的权限及权限定义处的 `protectionLevel`。只有需要与权限定义方匹配平台签名身份时才设 `certificate: "platform"`；否则使用产品默认签名。不能只因应用是系统应用或调用了平台 API 就选平台证书。

3. **确认特权权限：**如果确实需要 `privileged` 级权限，才设 `privileged: true`，并在应用所在分区的 `privapp-permissions` 白名单中列出获准权限。没有这类权限需求时不必设置；`signature` 级权限与特权权限要按权限定义分别判断。

4. **确认安装分区：**先查产品配置和相似模块的安装路径，确定应用属于 `system`、`system_ext` 等哪个分区。只有目标是 `system_ext` 时才设 `system_ext_specific: true`；特权权限白名单也要放在对应分区。省略时不要猜默认位置，构建后检查实际安装路径。

5. **按证据核实：**在源码中搜索相似 `android_app`、权限定义、白名单 XML 和产品的 `PRODUCT_PACKAGES`；再用目标分支 Soong 文档确认属性规则，构建模块并检查 APK 签名、Manifest 和安装路径。相似模块只能作线索，最终以本应用的 API、权限和产品配置为准。

**Q24: [learning] 改了 framework 性能代码后，怎样构建、同步到设备并验证改动？**

先构建对应模块，再按安装分区同步产物；随后选择进程重启或整机重启，并用设备端证据确认运行的是新版本。

例如，修改位于 system 分区的服务后，可按以下流程操作：

```bash
m services
adb root && adb remount
adb sync system
adb shell stop && adb shell start
```

`adb sync` 的参数是分区名，不是 `framework` 这类模块名；应按模块实际安装位置选择 `system`、`system_ext`、`product` 或 `vendor`。普通 Java 服务可在同步后重启相关进程；APEX、boot image 或 early-boot 改动需要完整重启。研究启动耗时时也必须整机重启，因为 `stop/start` 不会重新执行 bootloader、init 和 early-boot。

首次 `adb remount` 可能需要在隔离的开发设备上关闭 verity 并重启。同步后行为未变时，核对设备端文件哈希、build fingerprint 和实际安装分区；再用相同测试负载与性能 trace 验证改动是否生效。

**Q25: [learning] AutoFDO 与插桩 PGO、Baseline Profile 分别差在哪？它会在用户手机运行时"自动优化"吗？**

不会。AutoFDO（Automatic Feedback-Directed Optimization）把真实工作负载的执行样本转换成 LLVM 采样 Profile，再用同源代码与相近工具链重新编译，让编译器调整热点内联、基本块布局与分支权重——采集、转换、重编与验证都发生在研发构建流程，用户设备运行的是已用 Profile 编译好的产物。与插桩 PGO 相比，它不插入计数器、不改源码，适合接近生产的负载，代价是样本可能丢失偏斜、地址还原与负载代表性更难保证；与 Baseline Profile 相比，它作用于 C/C++ 原生二进制与内核（Clang/LLVM、按地址与分支样本、系统构建期生效），Baseline Profile 则指导 ART/dex2oat 提前编译应用 DEX 的热点方法，两者可以同时改善启动但不能互换。

Profile 只改变机器码、不改变程序语义，但 Profile 偏差可能造成代码体积膨胀或性能回退，编译器链接器本身也可能有缺陷，所以发布仍要比较代码段大小、基准性能与稳定性。它也不会在 Perfetto 里生成名为 AutoFDO 的 slice——收益要从 A/B 与系统 trace 的执行成本变化里读出来，而不是找一条专用轨道。

**Q26: [learning] Soong 模块怎样接入 AutoFDO？模块声明 afdo: true 能证明本地二进制已经优化了吗？**

不能。afdo: true 只是打开模块的构建接入，是否真正使用还取决于 Profile 配置、目标架构、构建变体与产物日志；验证要看详细构建日志里的 -fprofile-sample-use= 指向的 Profile。按 AAOS13 源码核对：build/soong/cc/afdo.go 已存在，编译参数模板为 -funique-internal-linkage-names -fprofile-sample-accurate -fprofile-sample-use=%s，且 frameworks/base/libs/hwui/Android.bp（libhwui）、art/runtime/Android.bp（libart）、art/libartbase/Android.bp 均已声明 afdo: true——用户空间原生 AFDO 在 Android 13 已进入这些代表性平台模块，Android 17 延续该接入。

OEM 与平台团队还要做的：确认实际构建引用了有效 Profile；针对自研内核差异与产品 CUJ 采集有代表性的 Profile；随代码变化定期刷新，避免长期复用旧 Profile。Profile 与二进制版本必须接近——源码、内联结构与地址布局变化后旧样本可用性下降，应记录代码提交、Clang 版本、build ID、生成时间与完整转换命令；vmlinux、GKI 模块、厂商模块与用户空间库要分别保留未剥离 ELF 并分别生成 Profile，同一份 kernel.afdo 不能优化所有模块，--allow-mismatched-build-id 只是工具容错开关，不能用来忽略二进制来源。
