# Android 产品配置与裁剪

> AAOS/AOSP 产品配置学习资料。题目覆盖 lunch 目标、产品继承、分区职责、资源与文件配置、自定义产品和系统应用裁剪；示例以本地 Android 13 源码为核对依据。维护者：session-to-knowledge。

**Q1: [learning] 在 AOSP/AAOS 构建中，Product、Device/Board 和 lunch 构建变体分别决定什么？**

Product 决定产品镜像的产品级内容与身份，Device/Board 提供设备及板级构建参数，lunch 选择 Product 与构建变体。三者协作产生目标镜像，但不是同一个配置对象。

1. **Product**：通过 `PRODUCT_PACKAGES`、`PRODUCT_COPY_FILES`、属性和资源覆盖等配置产品包含什么软件、配置和产品标识。
2. **Device/Board**：由 `PRODUCT_DEVICE` 连接设备配置，`BoardConfig.mk` 中的 `BOARD_*`、架构等信息描述硬件目标与构建方式。
3. **构建变体**：`user`、`userdebug`、`eng` 影响可安装模块标签及调试属性等构建行为；它不是 CPU 架构字段。

`sdk_car_x86_64-userdebug` 中，`sdk_car_x86_64` 是产品名，`userdebug` 是变体；`x86_64` 只是产品命名中表达架构的部分。应通过所选产品配置和 `get_build_var` 等构建工具查询真实架构配置，不能把字符串拆分结果当作独立架构字段。

`lunch` 目标格式随 Android 版本变化：Android 13 使用 `product-build_variant` 两段式；Android 17 使用 `product-release_config-build_variant` 三段式。不要跨版本照搬格式，应以当前源码树运行 `lunch` 显示的目标为准。

**Q2: [learning] `source build/envsetup.sh`、`lunch`、`AndroidProducts.mk` 和 `COMMON_LUNCH_CHOICES` 如何共同确定构建目标？**

`envsetup.sh` 将构建辅助函数定义到当前 Shell；`lunch` 选择产品和变体，并设置后续构建使用的目标配置。产品文件注册和菜单展示是两个相关但不同的步骤。

1. 在源码树执行 `source build/envsetup.sh`，使 `lunch`、`m` 等函数在当前 Shell 可用；直接用子进程运行脚本不会把函数导入父 Shell。
2. `AndroidProducts.mk` 中的 `PRODUCT_MAKEFILES` 登记产品入口 makefile；`COMMON_LUNCH_CHOICES` 列出可供菜单选择的产品-变体组合。
3. 选择目标后，构建环境设置 `TARGET_PRODUCT`、`TARGET_BUILD_VARIANT` 等变量，后续产品解析与构建据此进行。

本地 AAOS 13 示例 `device/generic/goldfish/car/AndroidProducts.mk` 同时列出 `sdk_car_x86_64.mk` 和 `sdk_car_x86_64-userdebug`。菜单项应在相应产品登记中有匹配入口；不要把旧式 `add_lunch_combo` 教程当成 Android 13 的通用注册方法。修改产品登记后若当前 Shell 保留旧目标状态，可重新加载环境或清除相关目标变量再执行 `lunch`。

**Q3: [learning] `:=`、`+=` 和产品配置的单值/列表变量应如何理解？**

`:=` 立即展开右侧并赋新值，`+=` 把值追加到变量；产品变量还按构建系统定义分为单值变量和列表变量，因此不能只凭赋值符号推断继承语义。

1. 单值变量（如 `PRODUCT_NAME`）通常由继承链中更靠近最终产品的赋值决定；子产品可重新赋值覆盖父产品的值。
2. 列表变量（如 `PRODUCT_PACKAGES`、`PRODUCT_COPY_FILES`）通常用于累积父子配置，子配置使用 `+=` 增加条目。
3. `=` 是递归延迟展开赋值；判断变量行为应以 AOSP 对该变量的注册类别和实际解析规则为准。

例如父产品设置 `PRODUCT_NAME := base`、`PRODUCT_PACKAGES += Settings`，叶子产品可设置 `PRODUCT_NAME := custom`、`PRODUCT_PACKAGES += MyApp`；最终产品名为 `custom`，包列表包含两项。对列表变量使用 `:=` 可能清掉已继承值，须确认是否确实要重置。

**Q4: [learning] `inherit-product` 如何合并产品配置，何时可用 `inherit-product-if-exists`？**

`inherit-product` 将另一个产品 makefile 纳入当前产品配置；单值通常由叶子值覆盖，列表通常沿继承关系累加。目标文件缺失时普通继承会使配置失败，只有可选配置才适合使用 `inherit-product-if-exists`。

Android 13 的产品解析会构建并遍历产品继承关系，而不是把 `inherit-product` 简单等同于文本 `include`。例如 AAOS 产品可以继承车载公共产品和架构基础产品，再在叶子文件中设置自身 `PRODUCT_NAME`、`PRODUCT_DEVICE`、品牌和型号。多个继承分支及赋值顺序会影响最终结果，宜用 `printconfig`、`get_build_var` 或产品解析输出核对实际值，而不是只靠阅读一行声明推测。

`inherit-product-if-exists` 会在文件不存在时跳过；若该文件是必需配置，应使用普通继承，让缺失尽早报错。

**Q5: [learning] `PRODUCT_COPY_FILES` 的源路径和目标路径分别表示什么，适合复制哪些文件？**

`PRODUCT_COPY_FILES` 用 `源路径:目标路径` 声明构建时复制到产品输出中的文件；目标通常是相对分区输出根的路径。它适合配置文件等非模块文件，不应替代 Android 模块构建与安装机制。

1. 本地源码示例 `build/target/product/core_64_bit.mk` 中的 `system/core/rootdir/init.zygote64_32.rc:system/etc/init/hw/init.zygote64_32.rc`，表示把源码文件复制到 system 分区镜像对应路径。
2. 应用配置文件时，可按需要把它放入目标分区的 `etc` 路径，并确保 init、服务或程序按该目标路径读取它。
3. Android 13 的 `build/make/core/Makefile` 对通过该变量复制 APK、VINTF 元数据和 ELF 等内容设有限制或要求使用专用机制；APK 应作为模块配置，ELF 应使用 Soong 的预编译模块类型，VINTF 内容使用相应声明变量。

目标路径同名时还涉及重复目的地处理，且产品分区重映射会影响最终落点。添加后应检查构建输出及生成镜像内容；不能把 `PRODUCT_COPY_FILES` 当作安装任意二进制的通用捷径。

**Q6: [learning] `PRODUCT_PACKAGE_OVERLAYS` 与 `DEVICE_PACKAGE_OVERLAYS` 的职责是什么，为什么 overlay 通常按源码路径组织？**

Overlay 用资源替换机制覆盖目标包中的资源，无需直接修改被覆盖模块的源码；overlay 文件在目录中的相对路径需匹配目标资源所在包的路径结构。

例如 overlay 中的 `frameworks/base/core/res/res/values/config.xml` 可覆盖 framework 的资源值。`PRODUCT_PACKAGE_OVERLAYS` 和 `DEVICE_PACKAGE_OVERLAYS` 的作用范围由构建配置与分区规则决定，不能简单概括为后者无条件覆盖所有分区。Android 新版本还可能把静态覆盖转为运行时资源覆盖（RRO），具体行为受目标和产品配置控制。

选择 overlay 时，应确认目标资源的所属包、产品支持的 overlay 机制及最终生效分区，并检查构建结果。Overlay 改的是资源值，不适合实现需要改代码逻辑的行为。

**Q7: [learning] `PRODUCT_PACKAGES` 如何控制产品安装模块，如何从产品中移除 Contacts 应用？**

`PRODUCT_PACKAGES` 按 Soong/Make 模块名请求把模块安装进产品；移除应用的做法是从最终生效的产品配置链中去掉该模块，或以产品支持的排除机制阻止其安装，再重新构建并检查镜像。

1. 在 AAOS 13 源码中搜索模块名及其产品声明，定位实际引入该包的产品 makefile；本地 `build/make/target/product/handheld_product.mk` 将 `Contacts` 加入 `PRODUCT_PACKAGES`。
2. 若目标产品继承了该配置，在合适的产品层调整包集合。直接编辑共享的通用产品文件会影响所有继承者，应优先在自有产品配置中做产品级裁剪，并遵守该分支的包排除规则。
3. `Contacts` 应用与 `ContactsProvider` 是不同模块。本地 `base_system.mk` 仍单独列出 `ContactsProvider`；移除应用不自动意味着要移除 Provider，后者可能被其他功能依赖。
4. 编译目标镜像或对应模块，启动目标设备后检查包列表与分区文件，确认实际裁剪结果。

不要只根据 `grep` 命中行删除所有同名内容；搜索结果可能包含注释、文档、依赖模块或不同产品配置。材料中的 `make -j16-` 写法不正确，若本意是 16 路并行，应写成 `make -j16`；`Rice14-eng` 也只是示例目标，必须确认它已在当前源码树注册。`make clean` 通常不是验证产品裁剪的必要步骤；增量构建是否重建取决于目标依赖关系。

**Q8: [learning] Soong 模块安装到哪个分区由什么决定，怎样确认它进入了哪个镜像？**

模块类型支持的安装属性和产品配置共同决定安装分区；`PRODUCT_PACKAGES` 负责选择是否构建并安装模块，不负责选择分区。

1. **确认分区：**查看模块类型及其安装属性，按产品规则确定目标分区；省略属性时按该模块类型的默认安装规则处理。
2. **确认产物：**用 `get_build_var PRODUCT_OUT` 获取输出目录，检查对应分区暂存目录和 `installed-files-*.txt` 清单，再确认目标 `.img` 已生成。

**Q9: [learning] `PRODUCT_DEVICE`、`BoardConfig.mk` 和 `PRODUCT_NAME` 的关系是什么？**

`PRODUCT_NAME` 标识产品配置，`PRODUCT_DEVICE` 为产品关联设备配置提供关键名称，`BoardConfig.mk` 提供板级构建参数。它们职责不同，产品 makefile 所在目录本身不决定哪份 `BoardConfig.mk` 被加载。

Android 13 的板级配置发现受构建系统搜索路径和配置变量影响；自定义产品若沿用已有 `PRODUCT_DEVICE`，就会复用该设备对应的板级设置。在一个与已有设备同名的任意目录新放 `BoardConfig.mk`，不能保证它被选择。要改变板级配置，应明确配置新的设备目录/设备标识并核对 `board_config.mk` 的实际选择结果。

用 `get_build_var PRODUCT_DEVICE` 与构建日志确认产品侧值，再检查实际加载的板级配置和 `TARGET_ARCH` 等变量。不要把“产品目录”与“设备配置目录”混为一谈。

**Q10: [learning] 从执行 `lunch` 到 Soong/Kati 开始编译，产品配置大致经过哪些阶段？**

构建先选定产品和变体，再解析产品继承与板级参数，最后由 Make/Kati 与 Soong 生成 Ninja 构建图并执行目标。

1. `lunch` 设置产品/变体相关环境。
2. 产品配置解析发现并导入产品入口及其继承关系，求出 `PRODUCT_*` 等值。
3. 构建系统按设备标识加载板级配置，并确定架构、分区和工具链参数。
4. 构建逻辑按 variant、模块声明及产品包集合选择目标，Kati/Soong 生成 Ninja 文件并运行构建。

理解分阶段流程有助于定位问题：菜单错误看产品注册，变量错误看继承后的生效值，板级错误看实际加载的 `BoardConfig.mk`，模块未打包则检查模块定义、依赖、产品包集合和安装分区。

**Q11: [learning] 新增 AAOS Product 时，`AndroidProducts.mk` 与产品 `.mk` 文件各负责什么？**

`AndroidProducts.mk` 登记可构建产品入口及可选 lunch 菜单项，产品 `.mk` 文件组合继承关系并设置最终产品值。新增目录或文件名本身不会自动构成有效产品。

一个最小产品定义需满足：入口路径存在、产品名在全树唯一、菜单目标对应已登记产品、variant 有效，并且继承的必需配置都能解析。产品文件中可以继承 AAOS 公共层与架构基础层，再设置 `PRODUCT_NAME`、`PRODUCT_DEVICE`、`PRODUCT_BRAND`、`PRODUCT_MODEL` 等值。

完成登记后重新加载构建环境并 `lunch <product>-userdebug`；通过 `printconfig` 与 `get_build_var` 检查生效配置，再编译并启动镜像。报错应按菜单未登记、产品名冲突、无效 variant、继承文件缺失等类别定位。

**Q12: [learning] 新增产品配置时，如何从已有 AAOS 模拟器产品继承而不误认为它是“车机硬件板”？**

模拟器产品通常组合通用 Android 基础、车载公共产品层和模拟器板级/外设配置；它是软件产品与虚拟硬件目标的组合，不代表真实车载硬件实现。

本地示例的 `sdk_car_x86_64.mk` 继承车载模拟器配置和 x86_64 SDK 基础，再设置产品标识及模拟器属性。Goldfish/Ranchu 与 Cuttlefish 等模拟目标的设备模型、内核及外设链路不同，不能互换假设。真实 OEM 产品通常需要匹配自己的 Device/Board、HAL、内核和分区配置，同时可复用通用 AAOS 产品层。

新产品应从最接近目标硬件的现有产品继承；改用不同模拟器或真机时，重新核对设备配置、内核、HAL 和产品继承树。

**Q13: [learning] AAOS/AOSP 的 `user`、`userdebug` 和 `eng` 构建变体会怎样影响模块标签与调试属性？**

三种变体面向不同用途：`user` 用于发布产品，`userdebug` 兼顾开发调试，`eng` 用于工程开发。Android 13 的 `build/make/core/main.mk` 按 variant 选择模块标签和默认属性；设备/产品配置还可能影响最终属性值。

1. `user` 默认只安装常规 `user` 标签模块，设置 `ro.secure=1`、`ro.adb.secure=1`、`ro.allow.mock.location=0` 和 `ro.debuggable=0`，并关闭目标调试。
2. `userdebug` 在 `user` 基础上增加 `debug` 标签模块，默认仍为 `ro.secure=1`、`ro.allow.mock.location=0`，并设置 `ro.debuggable=1`；该文件在此分支不会显式设置 `ro.adb.secure`。
3. `eng` 选择 `debug`、`eng` 标签模块，并默认设置 `ro.secure=0`、`ro.allow.mock.location=1`、`ro.kernel.android.checkjni=1` 和 `ro.debuggable=1`。

这些是构建源码中的默认行为，不代表所有设备最终属性绝无覆盖。应在目标构建产物和设备上用 `getprop` 检查最终值；发布产品不应仅凭选择了某个 variant 就跳过安全配置审查。

**Q14: [learning] 定制 Product 时，常见 `PRODUCT_*` 变量分别应在哪类决策中使用？**

`PRODUCT_*` 变量按职责描述产品身份、模块集合、文件/资源配置和产品属性；选变量时应先确定要改变的对象，再按目标变量的列表/单值规则修改。

1. **身份与设备连接**：`PRODUCT_NAME` 是产品名，`PRODUCT_DEVICE` 关联设备配置，`PRODUCT_BRAND`/`PRODUCT_MODEL` 提供品牌与型号信息。
2. **系统内容**：`PRODUCT_PACKAGES` 选择安装模块；`PRODUCT_COPY_FILES` 声明受支持的非模块文件复制。
3. **资源与配置**：`PRODUCT_PACKAGE_OVERLAYS` 指定产品级资源覆盖；`PRODUCT_AAPT_CONFIGS`、`PRODUCT_AAPT_PREF_CONFIGS` 参与资源配置/优选设置。
4. **分区属性**：`PRODUCT_SYSTEM_EXT_PROPERTIES`、`PRODUCT_PRODUCT_PROPERTIES` 分别声明 system_ext、product 分区属性。

同名变量在产品继承中的行为可能不同，添加或覆盖前应查看 `build/make/core/product.mk` 对该变量的注册分类及具体消费者。修改后用 `get_build_var`、构建属性文件和设备运行态共同确认结果。

**Q15: [learning] 性能测试应选什么构建变体，怎样避免把调试差异当成优化收益？**

调试和定位可用 `userdebug`；量产性能结论应在匹配产品的 `user` 构建上复测。`userdebug` 的 root、remount 和调试行为会影响测试条件，不能直接当作量产数据。

1. **定位问题：**使用 `userdebug` 复现、采集日志和验证 framework 修改。
2. **测量发布性能：**使用产品对应的 `user` 构建，在相同设备、温度、电源和负载下对比。
3. **记录结果：**记录产品、构建变体、平台与内核版本及测试条件；若先在 `userdebug` 上测量，应单独标注，不能与 `user` 数据混为一组。
