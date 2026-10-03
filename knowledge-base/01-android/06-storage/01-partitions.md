# 运行时分区与挂载

> 学习资料（文章模式沉淀）。边界：本文回答"设备上有哪些分区、各放什么、动态分区与虚拟 A/B 在运行时如何挂载与生效"；镜像如何构建、打包与刷写归 [../13-build-system/04-android-system-images.md](../13-build-system/04-android-system-images.md)；启动期 fstab/first-stage 挂载时序归 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)。分区名与布局随设备/版本有差异，权威以设备 fstab 与构建配置为准。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 设备上常见分区有哪些？各放什么？**

按"只读系统、引导、可写数据"三类记最不容易乱：

1. **只读系统侧**：`system`/`system_ext`/`product` 是平台与产品定制的镜像主体；`vendor`/`odm` 是芯片与设备厂商的下层实现（HAL、配套 rc 与固件、板级配置）——system 与 vendor 的 Treble 边界见架构册 HAL 篇（05-hal.md）；
2. **引导侧**：`boot` 装内核；`vendor_boot` 装 vendor 内核模块与 vendor ramdisk（GKI 机制见启动册 Q9）；`init_boot`（GKI 2.0 起）承接通用 ramdisk，boot 只留内核；`dtbo` 是设备树 overlay；`vbmeta` 是 AVB 校验链的元数据根（见启动册 Q3）；
3. **动态容器**：`super` 装着 system/vendor/product/odm 等**逻辑分区**（见 Q2）；
4. **可写侧**：`userdata`（挂载为 /data，FBE 加密的应用与用户数据）、`metadata`（保护加密密钥的启动早期小分区，见 06-storage 册）；
5. **辅助**：`misc` 是 bootloader 通信小区（如 recovery 启动指令 BCB）；A/B 设备的 recovery 功能并入 boot，不再有独立 recovery 分区。

**Q2: 动态分区（dynamic partitions）与 super 分区怎么理解？**

Android 10 起把 system/vendor/product/odm 等只读分区从物理分区改为 `super` 分区内的**逻辑分区**：OTA 时各分区大小可按需伸缩，不再受出厂物理分区表的硬限制。

1. **机制**：逻辑分区的元数据存在 super 内；init 第一阶段按 fstab 的 `logical`/`first_stage_logical` 标志从 super 映射并挂载（fstab 语境见启动册 Q10）；
2. **为什么**：旧世界每个只读分区都要为未来版本预留增长空间，几个大版本后就撞分区表上限；动态分区把"分区大小"变成 OTA 可以调整的数据；
3. **与 OTA 的配合**：升级期间新增内容走虚拟 A/B 快照落在 /data（见 Q3）；
4. **排查入口**：`adb shell lpdump` 查看 super 内的逻辑分区布局。

**Q3: 虚拟 A/B（Virtual A/B）与分区是什么关系？OTA 期间数据写到哪里？**

A/B 无缝升级需要两套可启动的系统；虚拟 A/B 的取舍是：关键启动分区仍走 slot 切换，动态分区的"另一份"不再完整复制成第二个 super，而是在 /data 上建写时复制（CoW）快照——升级期间设备照常可用。

1. **机制**：OTA 把新系统内容以快照形式写入 /data；重启到新 slot 后由 snapuserd 在读取旧分区内容时动态合成快照（启动期"读策略必须先于杀 snapuserd"的时序契约见启动册 Q11）；
2. **失败回退**：快照在元数据中登记，升级失败可放弃合并回滚旧系统，不需要 recovery 手动重刷；
3. **空间权衡**：/data 需为 OTA 预留空间，空间不足时 update_engine 会要求清理或走降级路径；下载、写快照与 slot 切换由 `update_engine` 编排；
4. **版本边界**：虚拟 A/B 自 Android 10 起推荐、Android 11 起为新发布设备强制；存量机型仍有传统 A/B（双份静态分区）与非 A/B（recovery 刷写）形态。

**Q4: Dynamic Partition 下 system/vendor 是怎么挂载出来的，bootloader 负责挂载 /data 吗？**

Android 10 起设备把 system、vendor、product、odm 等适合动态化的只读分区放入 super 物理分区，内核启动后进入 ramdisk 的 first-stage init：它解析 super metadata、创建 dm-linear 逻辑设备，并挂载这些 first_stage_mount 分区；boot、dtbo、vbmeta 等 bootloader 需要直接读取的分区仍是物理分区，不能说所有分区都进入了 super。bootloader 不负责挂载 super 或 `/data`：到 early-fs 阶段系统先启动 vold 做 metadata encryption 准备，late-fs 阶段 init 先执行 `wait_for_keymaster`，再通过 `mount_all` 挂载 `/data`。

动态分区解决了固定分区表的问题——OTA 升级可以动态调整各逻辑分区大小，不再受出厂容量约束。只读动态分区通常叠加 dm-verity 做完整性校验，`/data` 可叠加 dm-default-key 做 metadata encryption；system 分区在 Android 9 起的 system-as-root 模型下由内核直接挂载为根（Android 10 起改为带 ramdisk、由 first-stage init 挂载）。
