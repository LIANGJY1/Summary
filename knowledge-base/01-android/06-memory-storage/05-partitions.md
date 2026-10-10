# 运行时分区与挂载

> 学习资料（文章模式沉淀）。边界：本文回答设备分区的职责、动态分区与 `super` 的容量关系、Virtual A/B 的 OTA 数据路径，system-as-root 根布局，以及启动时逻辑分区和 `/data` 的挂载职责、系统分区内的目录约定（/system/etc）。镜像如何构建、打包与刷写归 [../10-build-system/04-android-system-images.md](../10-build-system/04-android-system-images.md)。具体分区名与布局随设备、启动模式和 Android 版本变化，最终以设备 fstab、分区表与构建配置为准。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 设备常见分区分别保存什么？哪些属于系统、引导、动态容器和用户数据？**

按分区职责可归为六组，但并非每台设备都具有每个分区。Treble 将通用 Android 系统与硬件厂商实现分层，通过稳定接口降低两侧必须同步更新的范围。设备厂商仍会按产品选择具体镜像与引导布局。

1. **系统与厂商实现：**`system` 保存 Android 系统镜像，`system_ext` 放置扩展公共系统镜像的资源和专有模块，`product` 承载产品侧系统内容。`vendor` 与 `odm` 承载芯片和设备实现，包括 HAL、配套 init rc、固件与板级配置。Treble 的边界要求跨层依赖遵守平台接口契约，不能据分区名推断两边代码可以任意互调。
2. **内核和 ramdisk：**`boot` 保存启动内核，在使用 GKI 的设备上该内核为 GKI。Android 12 及更早版本的 GKI 布局中，`boot` 也包含 generic ramdisk。设备若随 Android 13 发布，generic ramdisk 位于 `init_boot`，`boot` 主要保存 GKI 内核。升级设备可能继续把 generic ramdisk 放在 `boot`。`vendor_boot` 保存 vendor ramdisk 与厂商内核模块等内容，实际组成受 GKI 和 recovery 布局影响。
3. **设备树与验证链：**`dtbo` 保存设备树 overlay。`vbmeta` 保存 AVB 验证链的元数据与签名信息，作为验证启动镜像和分区的信任链入口。
4. **逻辑分区容器：**`super` 是物理容器，内部元数据描述 `system`、`vendor`、`product`、`odm` 等逻辑分区。设备采用动态分区时，这些系统镜像不各自占用固定物理分区。
5. **用户和加密数据：**`userdata` 通常挂载为 `/data`，保存应用和用户数据，并可使用文件级加密。`metadata` 是启动早期可访问的小型分区，可保存 metadata encryption 所需的密钥材料或相关元数据，具体用途以设备实现为准。
6. **启动控制与恢复：**`misc` 是 bootloader 与 Android 交换少量启动状态的分区，例如 recovery 启动用的 BCB 指令。A/B 设备常把 recovery 能力放进 boot 相关镜像或 ramdisk，因此可能没有独立 recovery 分区。非 A/B 或特定设备仍可能具有独立 recovery。



**Q2: [learning] android 中的 system-as-root 怎么理解？**

system-as-root（SAR）指根文件系统就是 system 分区本身：挂载后 system 镜像根部的内容直接出现在 `/` 下。init 二进制在镜像里位于 `system/bin/init`，成为根之后它在设备上的路径就是 `/system/bin/init`。构建时由 rootdir 模块（`system/core/rootdir`）在镜像根部创建指向它的符号链接，于是挂载后 `/init` 指向 `/system/bin/init`。内核启动第一个用户态进程的入口就是根下的 `/init`，保留这条路径使内核、既有工具和脚本都无需改动。该布局 Android 9 引入，Android 10 起成为新发布设备的要求。ramdisk 仍然存在，但只负责 first-stage 启动，不再提供根文件系统。

1. **和旧布局比：**旧布局由 ramdisk 提供初始根文件系统，`/init` 是 ramdisk 内的实体文件，system 作为普通分区挂载到 `/system`。SAR 下 system 同时承担根，init 再把根绑定到 `/system` 路径，按 `/system` 开头的旧访问方式继续可用。
2. **为什么 Android 10 起必须采用：**动态分区下 system 是 `super` 内的逻辑分区，内核只挂载物理块设备，无法把它挂为根。必须由 ramdisk 中的 first-stage init 解析 `super` 元数据、创建设备映射后再挂载。根从 ramdisk 换成 system，与 first-stage init 承担根挂载是同一变化的两面。
3. **怎么确认：**`ls -l /init` 第一列为 `l`，输出形如 `init -> /system/bin/init`——符号链接是一种内容为路径的特殊文件，访问它等于访问目标文件。`findmnt /` 应显示根来自 system 逻辑分区。两者同时成立即可判定设备采用 SAR。
4. **注意：**升级设备保留原有启动布局，非 SAR 设备上 `/init` 是实体文件。recovery 的根布局可能与正常启动不同。判断以设备 fstab 和分区表为准，不按 Android 版本反推。


**Q3: [learning] Android 动态分区怎样把 system、vendor 等逻辑分区放进 super？它解决了什么容量问题？**

Android 10 引入动态分区后，设备可将 `system`、`vendor`、`product`、`odm` 等分区实现为 `super` 内的逻辑分区。OTA 可调整逻辑分区容量，而无需为每个只读分区在出厂物理分区表中永久预留增长空间。

1. **物理与逻辑关系：**`super` 本身是物理分区。逻辑分区由 `super` 中的 extents 和分区组元数据描述，设备映射出 block device 后再挂载文件系统。逻辑分区不是一块独立的物理闪存区域。
2. **容量调整：**旧式固定布局需要给每个分区单独预留空间，某个分区增长时，即使别处有空余也可能受分区边界限制。动态布局在 `super` 的可用空间范围内调整分区大小，设备仍必须满足总容量、分区组和 OTA 峰值空间约束。
3. **OTA 配合：**OTA 可更新分区组元数据并重设逻辑分区大小。采用 Virtual A/B 的设备还会在 `/data` 保存写时复制快照，快照空间需求和 `super` 的逻辑分区容量是两项不同的约束。
4. **查看布局：**设备上可用 `adb shell lpdump` 读取 `super` 元数据，检查逻辑分区、分区组和容量分配。它展示设备当前的分区元数据，不代替构建配置或 OTA 包内容分析。



**Q4: [learning] Virtual A/B 与双份静态 A/B 分区有什么区别？OTA 快照写在哪里，何时回滚或合并？**

Virtual A/B 保留 A/B 更新的 slot 切换能力，但不在 `super` 内完整复制一套动态系统分区。OTA 将新数据写入 `/data` 上的写时复制快照，设备重启后通过快照映射读取新旧数据，确认新系统启动成功后再把快照合并回基础分区。

1. **存储布局：**bootloader 直接读取的关键物理分区仍按 A/B slot 更新。动态系统分区通过 `/data` 上的 COW snapshot 提供新版本块，不为每个动态分区在 `super` 中再保留完整 B 副本，因此比双份静态 A/B 少占常驻空间。
2. **安装与运行：**`update_engine` 编排下载、写入快照和 slot 切换。安装过程中快照写入 `/data`，设备仍可运行旧系统。重启后 device-mapper、`dm-user` 与 `snapuserd` 等组件按 Android 版本提供快照读取与合并路径。
3. **确认与回退：**新 slot 启动成功并标记为成功后，系统把快照合并回基础动态分区。若新系统启动失败，boot control 与更新状态可让设备回退到旧 slot 并放弃未完成的新版本更新。合并过程可跨重启继续，不能把普通启动失败与快照已合并完成混为一谈。
4. **版本和空间：**Virtual A/B 自 Android 11 起是 GMS 新发布设备要求。Android 12 支持压缩快照。Android 13 起，新发布设备的压缩快照与 userspace merge 默认使用 `snapuserd` 流程。存量设备升级时还受原有分区布局和配置约束。`/data` 必须容纳 OTA 的临时快照。空间不足会阻止更新或要求释放空间。



**Q5: [learning] Android 启动时谁把 super 里的逻辑分区映射并挂载？bootloader 会挂载 super 或 /data 吗？**

bootloader 负责加载启动所需的物理镜像并启动内核，不负责解析 Android 的 `super` 元数据或挂载 `/data`。启动后由 first-stage init 根据 ramdisk 中的 fstab、动态分区元数据和设备功能创建逻辑 block device 并挂载指定系统分区。`/data` 则在后续 init 阶段完成密钥准备后挂载。

1. **bootloader 阶段：**bootloader 根据设备启动配置读取 boot chain 所需物理分区，例如 boot、vendor_boot、init_boot、dtbo 和 vbmeta，并将内核与 ramdisk 交给内核启动。具体组合取决于 Android 发布版本、GKI、A/B 与 recovery 布局。
2. **first-stage init：**init 从 ramdisk 启动并读取设备 fstab。对标记为 first-stage mount 的条目，它解析 `super` 元数据、创建逻辑分区映射设备，再挂载 system、vendor 等早期所需分区。fstab 中的分区类型和 first-stage 标记决定实际挂载集合，不能假设所有动态分区都会在同一时刻挂载。
3. **Virtual A/B 特例：**启用压缩快照的设备可能需要 first-stage init 在挂载系统分区前启动 ramdisk 中的 `snapuserd`，让逻辑设备读取经过 snapshot 映射的数据。切换到系统分区并加载 SELinux policy 时，init 还需按版本定义的时序重新启动或切换 snapuserd 上下文，避免 snapshot I/O 中断。
4. `/data` 阶段：init 在挂载 `/data` 前要按设备配置完成 metadata encryption 的密钥准备。常见 fstab/init 流程会在 late-fs 阶段等待 KeyMint/Keymaster 等依赖，再通过 `mount_all` 处理 `/data` 条目。阶段名称、等待服务和 fstab 标记随设备实现变化，因此具体顺序要检查产品 init rc 与 fstab。
5. **文件系统安全层：**只读系统分区通常可在 block device 上叠加 dm-verity 校验。`/data` 可配置 dm-default-key 等 metadata encryption 机制。Android 9 的 system-as-root 布局把 root 文件系统并入 `system.img`，由内核将 `system` 挂载为根文件系统。Android 10 起，逻辑 `system` 分区不能再由内核直接挂载，系统分区映射和早期挂载由 ramdisk 中的 first-stage init 处理。升级设备会保留其原有启动布局，不能只按运行的 Android 版本推断分区形态。




**Q6: [learning] Android 的配置文件为什么放在 /system/etc，根目录下为什么没有独立的 /etc？**

两层原因叠加。/etc 是 Unix 世界放配置文件的传统位置，名字来自 et cetera（其他杂项），约定的意义在于任何程序找配置都有一个人人皆知的固定位置。而 Android 的根目录被各分区挂载点占满，没有独立的 /etc，于是把这个角色交给系统分区里的 /system/etc。

1. **/etc 的传统：**早期 Unix 把不方便归类的文件都放进 /etc，久而久之演变成配置文件目录的约定——fstab、hosts、passwd 都在那里。约定省去每个程序自创路径：程序知道去哪儿找，管理员知道东西放哪。
2. **Android 为什么没有独立 /etc：**根目录被 /system、/vendor、/data 等分区挂载点占满，只读与可写内容按分区组织。出厂自带的配置文件随系统分区走，/etc 的角色由 /system/etc 承担——init 的 rc 文件、权限 XML、音频策略都在这里。早期版本还用 /etc 到 /system/etc 的软链接兼容旧习惯。
3. **路径是契约：**现代 Android 的 init.rc 位于 /system/etc/init/hw/init.rc，各服务自己的 rc 文件在 /system/etc/init/ 下。init 的代码写死了这个约定路径，配置文件必须放在那里等它来读——放置位置不是可选建议。
4. **暂存目录到运行时的映射：**制作系统镜像时，暂存目录（如 system_root）里那一层 system/ 对应运行时挂载到 /system 的目录树。因此暂存区中的 system_root/system/etc/init.rc，打包开机后就是 /system/etc/init.rc。bin/init 对应 /system/bin/init。镜像树整体复刻真机布局，路径学到的知识才能直接迁移。
