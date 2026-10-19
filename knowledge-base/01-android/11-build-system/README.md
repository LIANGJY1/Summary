# Android 构建系统

> 收录 8 册、90 道 Android/AAOS 构建题，按"源码如何变成可刷写、可运行的系统"的构建流水线组织：平台构建主线（产品配置 → Soong 模块 → 可执行文件 → 系统镜像 → OTA 升级交付）之外，内核构建与模块是与平台构建平行的独立支线，aconfig 是贯穿声明、代码生成与版本配置的构建期特性开关机制。通用 Linux 内核构建概念归 `knowledge-base/02-os/06-linux-build/`，驱动运行时设计归 `10-platform-native/02-driver-runtime.md`，aconfig 运行期存储与 aflags 归 `09-platform-services/05-aconfig-runtime.md`。

1. `01-product-config.md`：Android 产品配置与裁剪：lunch 目标、Product/Device 继承、PRODUCT_* 变量、分区安装归属、构建变体与调试能力、新增 AAOS 产品与裁剪（17 题）。
2. `02-soong-modules.md`：Soong 构建体系与 Android.bp：源码与预编译的 C/C++、Java 模块声明，依赖与分区安装，Make/Soong 语法对照，framework 改动验证，AutoFDO 优化接入（24 题）。
3. `03-executables.md`：Android 可执行文件：静态/动态链接与 ELF 格式、动态链接器、/init 的构建与启动、产物形态辨析与无法运行排查（13 题）。链接与 ELF 的通用机制作为前置保留在本册，均落到 Android 语境。
4. `04-system-images.md`：系统镜像：system.img、super.img、ramdisk 的内容与打包关系，单独构建、刷写与改动不生效排查（8 题）。
5. `05-ota-packages.md`：OTA 升级包：整包与差分包的生成、内容与设备端应用，版本链、签名与防回滚（1 题）。
6. `06-kernel-build.md`：内核构建与验证：内核版本、.config 与工具链匹配，模拟器与 Cuttlefish 验证，平台构建与内核构建的边界（14 题）。
7. `07-kernel-modules.md`：内核模块：Kconfig/Kbuild 的内建与模块选择，外部模块构建，.ko 在设备上的加载条件与驱动修改验证（4 题）。
8. `08-aconfig.md`：aconfig 特性开关：声明字段、Soong 接入、构建期代码生成模板、release config 与版本核对（9 题）。

**目录边界：**镜像本身的辨析与刷写在 `04-system-images.md`，升级包交付在 `05-ota-packages.md`；AutoFDO 的构建接入在 `02-soong-modules.md`（用户空间模块）与 `06-kernel-build.md`（内核采样），内核与 GKI 的平台契约归 `10-platform-native/`。各册示例以 Android 13/AAOS 源码为核对依据，模块名、路径和版本号按实际产品调整。
