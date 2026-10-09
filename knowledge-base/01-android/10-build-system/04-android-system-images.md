# Android 系统镜像

> 面向 Android 13/AAOS 初学者，说明分区镜像、启动镜像中的 ramdisk 布局、产品内容、动态分区及刷写排查。具体镜像布局以目标设备配置为准。维护者：session-to-knowledge。

**Q1: [learning] system.img、整机 ROM、target_files.zip 和 OTA 包分别是什么？**

`system.img` 是 system 分区的镜像，不等于整机 ROM。整机软件由多个分区镜像和设备配置共同组成。`target_files.zip` 与 OTA 包是供后续打包、升级流程使用的归档产物。

1. `system.img`：封装 system 分区文件系统内容的镜像，Android 13 构建规则将其输出到 `$(PRODUCT_OUT)/system.img`。
2. **其他分区镜像：**如 `vendor.img`、`product.img`、`system_ext.img`、`boot.img`，各自承载不同分区或启动内容。是否生成取决于产品配置。
3. `target_files.zip`：包含分区文件、镜像和构建元数据，供 OTA、工厂包等后续工具使用。它不是单个分区镜像。
4. **OTA 包：**由 `target_files.zip` 等输入生成，面向设备升级流程，可能包含完整或增量更新数据。它不是可直接写入某个分区的 `system.img`。[AOSP OTA 打包说明](https://source.android.com/docs/core/ota/tools)

**Q2: [learning] ramdisk 会打包进哪个启动镜像，Android 12 和 Android 13 设备有何区别？**

ramdisk 的归档随启动镜像交付，具体放在哪个镜像取决于设备的启动布局和首发版本；不能只看设备当前运行的 Android 版本判断。

1. **Android 12 及更早首发设备：**通用 ramdisk 通常位于 `boot.img`。
2. **Android 13 首发设备：**通用 ramdisk 移入 `init_boot.img`，`boot.img` 保留 GKI 内核。
3. **厂商 ramdisk：**设备专属启动资源通常放在 `vendor_boot.img`，具体组成由设备配置决定。
4. **升级设备：**从 Android 12 或更早版本升级的设备可能保留原有布局，不一定新增 `init_boot.img`。

分析具体产品时，应检查该产品生成的启动镜像和分区配置。布局依据 [AOSP 通用启动分区说明](https://source.android.com/docs/core/architecture/partitions/generic-boot) 和 [vendor_boot 分区说明](https://source.android.com/docs/core/architecture/partitions/vendor-boot-partitions)。

**Q3: [learning] 哪些文件会进入 system.img，模块编译成功后为什么可能不在里面？**

`system.img` 由构建系统从 system 分区的暂存目录生成。模块必须被产品选中并安装到该分区，才会成为镜像内容。仅有模块声明或单独构建成功都不能保证它进入 `system.img`。

1. **产品选择：**产品配置通过 `PRODUCT_PACKAGES` 等机制请求安装模块。
2. **分区归属：**Soong 模块的安装属性和产品规则决定它进入 system、vendor、product 或其他分区。安装到其他分区的模块不会因为构建成功而出现在 `system.img`。
3. **镜像封装：**Android 13 的构建规则以 `TARGET_OUT` 作为 system 镜像输入目录，并将结果写入 `$(PRODUCT_OUT)/system.img`。[对应规则](</home/liang/Project/MyProject/AAOS13_study/build/make/core/Makefile:3173>)

排查缺文件时，依次检查产品是否选中模块、模块最终安装路径，以及该文件是否出现在 system 暂存目录和安装文件清单中。

**Q4: [learning] super.img 和 system.img 有什么关系，Android 13 何时生成 super.img？**

动态分区把 `system`、`vendor` 等逻辑分区放在物理 `super` 分区中。因此，`system.img` 是单个分区镜像，`super.img` 可按产品布局打包多个逻辑分区镜像；分区机制详见 [Android 分区](../06-memory-storage/05-partitions.md)。

1. **构建条件：**常规构建路径要求 `PRODUCT_BUILD_SUPER_PARTITION=true` 且设置 `BOARD_SUPER_PARTITION_SIZE`；`PRODUCT_RETROFIT_DYNAMIC_PARTITIONS` 不为 `true` 时适用。[Android 13 构建规则](</home/liang/Project/MyProject/AAOS13_study/build/make/core/Makefile:6548>)
2. **默认产出：**`BOARD_BUILD_SUPER_IMAGE_BY_DEFAULT=true` 才会把 `super.img` 纳入默认构建目标。未设为 `true` 时，不要假设执行普通整机编译就会生成它。[目标定义](</home/liang/Project/MyProject/AAOS13_study/build/make/core/Makefile:6608>)

是否交付或刷写 `super.img` 由产品流程决定，不能把它当作通用刷机包。

**Q5: [learning] 如何只构建 system.img，如何构建当前产品的整套镜像？**

先用 `lunch` 选定产品和变体，再选择构建目标。`m systemimage` 生成 system 分区镜像。默认目标 `m` 对应完整产品构建流程，会依产品配置构建所需镜像和产物。

1. **选择目标：**在源码树加载构建环境并执行 `lunch <产品>-<变体>`，例如选择具体 AAOS 产品的 `userdebug` 目标。
2. **只构建 system：**执行 `m systemimage`。该目标依赖 `$(PRODUCT_OUT)/system.img`，不是通用的整机镜像构建命令。[systemimage 目标](</home/liang/Project/MyProject/AAOS13_study/build/make/core/Makefile:3232>)
3. **构建完整产品：**执行 `m` 或 `m droid`，构建当前产品默认目标及所需分区产物。AOSP Android 13 的完整目标依赖 system、boot、recovery、vbmeta 等适用产物。实际集合因设备配置而异。[完整构建依赖](</home/liang/Project/MyProject/AAOS13_study/build/make/core/main.mk:1600>)
4. **查找输出：**用 `get_build_var PRODUCT_OUT` 查询当前产品输出目录。常见目录形如 `out/target/product/<产品名>/`，其中具体有哪些 `.img` 取决于产品配置。

**Q6: [learning] 镜像构建失败、镜像过大或改动没有生效时如何排查？**

先确认构建目标和输入目录，再检查安装清单、镜像大小限制及最终输出时间。镜像构建成功只说明产物生成，不代表设备已经刷入或运行了它。

1. **没有生成目标镜像：**确认 `lunch` 选择正确，并核对该产品是否定义相应镜像目标。动态分区产品也不一定默认生成 `super.img`。
2. **文件未进入镜像：**检查产品模块清单、模块安装分区、`$(PRODUCT_OUT)/system/` 暂存内容和 `installed-files.txt` 等清单。
3. **镜像超过分区限制：**查看 `BOARD_SYSTEMIMAGE_PARTITION_SIZE` 等设备分区大小配置及构建报错。Android 13 的 system 镜像安装规则会校验镜像大小。[大小校验](</home/liang/Project/MyProject/AAOS13_study/build/make/core/Makefile:3227>)
4. **修改后仍是旧内容：**确认构建的是当前 `PRODUCT_OUT` 下的目标文件，检查文件时间和内容。再确认设备实际刷入了这份产物，而非另一产品或旧镜像。

**Q7: [learning] 如何判断 system.img 的文件系统格式和稀疏格式？**

`.img` 后缀只说明它是镜像文件，不能单独判断内部文件系统或是否采用 Android sparse 表示。产品配置可选择 ext4、EROFS 等文件系统，并决定输出是否稀疏。读取、挂载或刷写前应先识别实际格式。[Android 13 镜像构建脚本](</home/liang/Project/MyProject/AAOS13_study/build/make/tools/releasetools/build_image.py:500>)

1. **识别镜像：**在构建主机上用 `file system.img` 查看文件类型。无法确定时，结合目标产品的文件系统配置和构建日志判断。
2. **稀疏转换：**若工具确认它是 Android sparse image，可用 Android 构建工具 `simg2img` 转为普通镜像，再按文件系统类型检查或挂载。不要把稀疏格式与 ext4、EROFS 等文件系统类型混为一谈。

**Q8: [learning] system.img、super.img 和 OTA 包应该怎样刷入设备？**

刷写方式由设备分区布局、A/B 状态、Verified Boot 和厂商升级流程决定。不要把 `fastboot flash system system.img` 当作所有 Android 设备的通用命令。优先使用该产品提供的工厂刷机脚本或 OTA 流程。

1. **独立物理分区：**只有在产品布局明确存在对应物理分区、镜像匹配当前设备与槽位时，才按设备刷机说明写入分区镜像。
2. **动态分区：**逻辑分区通常经 fastbootd 或产品升级流程处理。fastbootd 是设备用户空间中的 fastboot 实现，用于支持可调整分区。是否刷单独逻辑镜像或 `super.img`，以设备配置和厂商脚本为准。[AOSP fastbootd 说明](https://source.android.com/docs/core/architecture/bootloader/fastbootd)
3. **OTA 包：**通过设备支持的恢复、更新引擎或厂商升级工具安装。升级流程还会校验包签名、版本和分区状态。

刷错产品镜像、分区或槽位可能导致设备无法启动。刷写前先核对产品名、镜像来源、目标分区及恢复方案。
