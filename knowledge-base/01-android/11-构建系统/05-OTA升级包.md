# Android OTA 与升级包

> Android 系统升级交付知识：整包与差分升级包（patch 包）的生成、内容与设备端应用，版本链、签名与防回滚约束，A/B 设备的在线安装契约与 Virtual A/B 快照合并。镜像本身的辨析与刷写归 `04-系统镜像.md`；源码补丁（.patch）归 Git 主题。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] 系统升级语境里的 patch 包（差分升级包）是什么？为什么只装差异也能完成升级？**

Android 系统开发里说的 patch 包，通常指 OTA（Over-the-Air，空中下发）差分升级包（incremental OTA package）：一个只携带“旧版本到新版本之间差异”的升级 zip 包，由构建工具 `ota_from_target_files` 加 `-i` 参数对比新旧两份 target_files（构建产物归档，内含分区文件、镜像与构建元数据）生成，不带 `-i` 生成的就是整包。设备端由 recovery 或 A/B 更新引擎把差异应用到现有分区。它与源码语境的补丁文件（.patch）只是重名，后者是 git 产物，与系统升级无关。

“只装差异就够”依赖三个环节：

1. **差异在构建侧生成：**构建机器同时拥有旧、新两份完整内容，差异不靠设备现算。逐文件对比时，文本与任意二进制用 bsdiff（设备端用 bspatch 以“旧文件+差异描述”还原新文件）。system 这类分区镜像按块生成差异（imgdiff 等算法），输出一串“从旧分区哪些块取数据、按什么算法变换、写到新分区哪些块”的操作序列。A/B 设备把这些操作连同校验信息打包成 payload.bin。包体积因此从几 GB 的整包缩到几十至几百 MB。
2. **应用由设备端完成：**传统 recovery 升级把设备重启进 recovery，按操作序列重建各分区。A/B 设备由 update_engine 在系统运行期把差异写入另一个 slot，校验通过后切换活动 slot 重启进新版本，新 slot 启动失败时 bootloader 依据启动计数回退旧 slot。早期基于 edify 脚本的文件级替换（逐文件打补丁）已成历史，现在都是块级（block-based）差异。
3. **正确性靠版本链保证：**差分包从“确定的旧版本”出发——包内元数据记录旧分区哈希与目标版本指纹，设备当前分区哈希必须与之匹配才继续安装。停在更旧版本、分区被改过或试图降级都会被拒绝。所以差分包必须沿升级链逐级下发（如 1.0→1.1→1.2），断链场景用整包兜底——整包携带全量镜像，任何版本都能直接刷，这就是整包（full OTA）存在的意义。

使用边界与常见混淆：

1. **生成与验证成本高：**每发一个新版本，都要对每个在网历史版本各生成并验证一份差分包。手机与车机厂商通常只对最近若干版本出差分、更早的走整包。差分包升级失败没有中间结果可用，重要推送需要整包保底。
2. **签名与防回滚：**无论整包还是差分，升级包都要用设备信任的 release 密钥签名。Verified Boot 校验签名，并通过回滚保护拒绝把系统降到更旧的版本。
3. **同名异物的源码补丁：**.patch/.diff 文件由 `git format-patch` 生成、`git am` 应用，作用于源码树。车机项目里供应商交付的“patch 包”有时指这类源码补丁集合或修复文件的替换包，按交付说明确认，不要默认当成 OTA 差分包。

**Q2: [learning] A/B 设备的升级包由谁在什么时机应用？update_engine 的调用契约是什么？**

A/B 设备的升级由 update_engine 在系统正常运行期间后台写入未使用的 slot，不需要重启进 recovery。安装完成并通过校验后，新 slot 默认被置为 active，设备下次重启尝试进入新版本。驱动这条链路的是系统更新应用（Google 设备上为 GmsCore）：它通过 `UpdateEngine` SystemApi 绑定服务、下发 payload，并靠回调接收进度与结果。

1. **调用流程：**`UpdateEngine` 的最小流程是创建实例、`bind` 注册回调、`applyPayload` 下发升级。安装期间回调 `onStatusUpdate(status, percent)` 汇报进度，结束时回调 `onPayloadApplicationComplete(errorCode)`。错误码与 update_engine 的 error_code.h 对齐（`SUCCESS` 为 0）。
2. **applyPayload 参数：**`applyPayload(url, offset, size, headerKeyValuePairs)` 的四个参数按职责逐项说明：

    1. `url`：payload 位置。非流式更新可用 `file://` 指向本地文件。Google OTA 的 url 指向一个 zip，payload 以未压缩形式存放在 zip 内。
    2. `offset`：payload 在 url 文件中的起始偏移。
    3. `size`：payload 长度。offset 与 size 配合用于在 zip 里定位 payload 部分。
    4. `headerKeyValuePairs`：向 update_engine 传递元数据键值对。Google 实现中这些键值对存为 zip 内的 payload_properties.txt，javadoc 示例包含 FILE_HASH、FILE_SIZE、METADATA_HASH、METADATA_SIZE。完整键表由 update_engine 的 constants 定义。

3. **slot 切换控制：**javadoc 描述的默认行为是升级成功结束后新 slot 即被置为 active，下次重启尝试进入新 slot。传 `SWITCH_SLOT_ON_REBOOT=0` 可推迟切换。之后以同一 payload 传 `SWITCH_SLOT_ON_REBOOT=1` 重新调用 `applyPayload`，update_engine 校验完整性后做最小工作切换 slot。
4. **中断与撤销：**三个方法分别覆盖不同的中断场景：

    1. `cancel()` 永久取消进行中的升级。
    2. `suspend()` 与 `resume()` 暂停、续跑安装。
    3. `resetStatus()` 撤销已完成但尚未重启进入的升级。

5. **与 recovery 路径的分界：**传统 recovery 安装由 `RecoverySystem` 写入 /misc 的 bootloader 控制块（setupBcb/clearBcb）并重启执行。A/B 路径由 update_engine 在线完成写入，重启只承担切换 slot。

依据：Android 13 的 frameworks/base/core/java/android/os/UpdateEngine.java 与 UpdateEngineCallback.java（类注释与 applyPayload javadoc）、RecoverySystem.java 的 setupBcb/clearBcb。

**Q3: [learning] Virtual A/B 相比传统 A/B 改变了什么？COW 快照与合并在其中扮演什么角色？**

传统 A/B 为只读分区物理准备两份，升级把完整内容写入未使用的 slot。Virtual A/B 配合动态分区（system 等只读分区改为 super 内的逻辑分区）取消了预置第二份物理分区的要求：升级时对当前分区建立写时复制（COW，Copy-on-Write）快照，把差异写入快照，重启进入新版本并确认升级成功后再合并、释放快照空间。收益是升级仍全程在线且省去一份大分区预留。代价是 COW 数据占用 /data 空间，且合并完成前系统处于快照状态。

1. **创建快照：**`SnapshotManager.CreateUpdateSnapshots` 按 payload manifest 为目标分区建立快照，在 target_metadata 中创建 cow 分组元数据，并把当前分区将被改写的区域标记为写保护。
2. **写入差异：**`MapUpdateSnapshot` 为 OTA 客户端映射出可写的目标分区视图，update_engine 向其写入差异。`ro.virtual_ab.compression.enabled` 开启时使用 userspace snapshot（dm-user），接口不再返回 raw snapshot 路径。重启时快照以 dm 设备的形式把合并前的新版本内容呈现给启动路径。
3. **合并：**`InitiateMerge` 的注释要求只在更新已被标记成功（booting 之后）才发起。`ProcessUpdateState` 应在 /data 挂载后尽早运行，若合并进行中会阻塞直到完成，完成但还需要清理时返回需要重启的状态（MergeNeedsReboot）。合并或更新被取消时，框架会清理对应快照状态。
4. **空间语义：**快照空间经 fiemap 在 /data 上按需分配，管理接口提供预计占用与查询能力，/data 余量因此成为升级可行性的约束之一。

依据：Android 13 的 system/core/fs_mgr/libsnapshot/include/libsnapshot/snapshot.h（ISnapshotManager 注释与 fiemap 命名空间）、frameworks/base 的 UpdateEngine.java。传统 A/B 与 Virtual A/B 的总体差异另见 source.android.com 的 A/B（Seamless）System Updates 与 Virtual A/B 文档。

**Q4: [learning] U 盘升级与 OTA 是互斥的升级方式吗？**

不是。U 盘描述升级包的传输和存放介质，OTA 在 Android 工程中也常指系统更新包和更新框架；本地文件同样可能交给 update_engine 处理。实际写入路径由设备升级应用、包格式和平台实现决定。

1. **确认入口**：查升级应用的日志、调用链和包内容，判断是 update_engine、recovery 还是厂商刷写流程。
2. **确认写入对象**：核对 payload、目标分区及 slot，而不是根据“U 盘升级”推断所有存储都会被覆盖。
3. **确认完成状态**：下载或读取包成功、写入完成、切槽和新系统启动成功是不同阶段，应分别检查。

**Q5: [learning] A/B 升级切换 slot 后，为什么不能断言所有分区都换了一份？**

slot 是引导和更新使用的逻辑选择，不意味着设备上的每个分区都有两份独立物理副本。分区是否参与更新、如何映射和是否共享，取决于设备布局和更新类型。

传统 A/B 可为部分分区准备两个槽位；动态分区由 super 元数据和 device-mapper 映射，Virtual A/B 对动态分区可使用快照。用户数据及厂商分区的处理也取决于实际方案。排查时应结合当前 slot、payload 清单、逻辑分区元数据和块设备映射，而非只看一个槽位后缀。

**Q6: [learning] 系统镜像更新成功为什么不能证明 OverlayFS 上层已清除？**

更新底层分区和清理 OverlayFS 上层是不同操作。升级可能更新目标系统镜像，但 scratch 的后端及上层目录是否保留取决于升级、启动和清理实现；后续若旧 upper 再次挂载，可能遮蔽新镜像中的同路径文件。

应分别记录升级前后 slot、目标分区内容、scratch 后端、upper 文件哈希和目标进程的挂载视图。若没有上层同路径条目，单凭 overlay 存在不能证明旧文件遮蔽；如果后端已重建或上层已清理，同样不能假定旧文件仍在。

**Q7: [learning] 系统升级成功、切槽成功和启动成功各证明什么？**

它们分别描述更新写入、下次启动目标选择和新系统运行状态，不能互相替代。成功写入目标不保证新系统已经启动，也不保证应用业务正常。

1. **更新完成**：更新组件报告目标写入和相关校验结果，具体范围由日志与 payload 决定。
2. **切换目标**：启动控制设置下一次启动的 slot；仍需重启并确认实际启动的 slot。
3. **启动成功**：系统将新槽标记成功有助于后续回退或快照合并决策，但业务界面和应用仍需要各自验证。

**Q8: [learning] 恢复出厂后升级异常消失，为什么不能直接断定哪项数据导致问题？**

恢复出厂会改变多个设备状态，故恢复正常只能证明这次组合操作后现象消失，不能单独证明 scratch、应用数据或包缓存中的某一项是根因。

应在可重置设备上保存操作前后的分区映射、挂载、upper 文件清单及哈希、包状态和日志，再分别控制清理条件。若缺少故障前数据，后续干净状态不能证明故障时上层目录为空；厂商恢复流程是否清理或使 scratch 失效，也需通过实现与实验确认。
