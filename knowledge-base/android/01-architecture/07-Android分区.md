# Android 分区

> 学习资料（文章模式沉淀）。主线：常见分区职责清单、动态分区（super）机制、虚拟 A/B 与 OTA 的分区衔接。AVB 校验链与 fstab/first-stage 挂载的启动语境见 [02-Android系统启动流程.md](./02-Android系统启动流程.md)（Q23/Q27/Q28）。分区名与布局随设备/版本有差异，权威以设备 fstab 与构建配置为准。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 设备上常见分区有哪些？各放什么？**

按"只读系统、引导、可写数据"三类记最不容易乱：

1. **只读系统侧**：`system`/`system_ext`/`product` 是平台与产品定制的镜像主体；`vendor`/`odm` 是芯片与设备厂商的下层实现（HAL、配套 rc 与固件、板级配置）——system 与 vendor 的 Treble 边界见架构册 HAL 篇（09-HAL.md）；
2. **引导侧**：`boot` 装内核；`vendor_boot` 装 vendor 内核模块与 vendor ramdisk（GKI 机制见启动册 Q24）；`init_boot`（GKI 2.0 起）承接通用 ramdisk，boot 只留内核；`dtbo` 是设备树 overlay；`vbmeta` 是 AVB 校验链的元数据根（见启动册 Q28）；
3. **动态容器**：`super` 装着 system/vendor/product/odm 等**逻辑分区**（见 Q2）；
4. **可写侧**：`userdata`（挂载为 /data，FBE 加密的应用与用户数据）、`metadata`（保护加密密钥的启动早期小分区，见 04-storage 册）；
5. **辅助**：`misc` 是 bootloader 通信小区（如 recovery 启动指令 BCB）；A/B 设备的 recovery 功能并入 boot，不再有独立 recovery 分区。

**Q2: 动态分区（dynamic partitions）与 super 分区怎么理解？**

Android 10 起把 system/vendor/product/odm 等只读分区从物理分区改为 `super` 分区内的**逻辑分区**：OTA 时各分区大小可按需伸缩，不再受出厂物理分区表的硬限制。

1. **机制**：逻辑分区的元数据存在 super 内；init 第一阶段按 fstab 的 `logical`/`first_stage_logical` 标志从 super 映射并挂载（fstab 语境见启动册 Q23）；
2. **为什么**：旧世界每个只读分区都要为未来版本预留增长空间，几个大版本后就撞分区表上限；动态分区把"分区大小"变成 OTA 可以调整的数据；
3. **与 OTA 的配合**：升级期间新增内容走虚拟 A/B 快照落在 /data（见 Q3）；
4. **排查入口**：`adb shell lpdump` 查看 super 内的逻辑分区布局。

**Q3: 虚拟 A/B（Virtual A/B）与分区是什么关系？OTA 期间数据写到哪里？**

A/B 无缝升级需要两套可启动的系统；虚拟 A/B 的取舍是：关键启动分区仍走 slot 切换，动态分区的"另一份"不再完整复制成第二个 super，而是在 /data 上建写时复制（CoW）快照——升级期间设备照常可用。

1. **机制**：OTA 把新系统内容以快照形式写入 /data；重启到新 slot 后由 snapuserd 在读取旧分区内容时动态合成快照（启动期"读策略必须先于杀 snapuserd"的时序契约见启动册 Q12）；
2. **失败回退**：快照在元数据中登记，升级失败可放弃合并回滚旧系统，不需要 recovery 手动重刷；
3. **空间权衡**：/data 需为 OTA 预留空间，空间不足时 update_engine 会要求清理或走降级路径；下载、写快照与 slot 切换由 `update_engine` 编排；
4. **版本边界**：虚拟 A/B 自 Android 10 起推荐、Android 11 起为新发布设备强制；存量机型仍有传统 A/B（双份静态分区）与非 A/B（recovery 刷写）形态。
