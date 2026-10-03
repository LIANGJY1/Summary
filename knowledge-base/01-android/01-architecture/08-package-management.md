# 应用包管理：安装、校验与归档

> 学习资料（文章模式沉淀）。边界：本文回答"应用从分发制品到安装事务、dexopt 编排、增量与分阶段安装、应用归档与恢复"；资源与 Configuration 更新归 [../03-ui/03-resources.md](../03-ui/03-resources.md)；构建期模块声明归 13-build-system。源文档：android-internals-wiki §1.16–§1.17；机制按本地 AAOS13 源码（Android 13）核对，安装 dexopt 迁往 ART Service（Android 14 起）与平台级 App Archiving（Android 15 起）已标注版本差异。Q 序列即结构，供 atlas 同源直读。

**Q1: 商店上传的是 AAB，手机端最终安装的却是 APK：AAB 为什么不能直接安装，设备端 PackageInstaller 对 base/split APK 集合校验什么？**

AAB（Android App Bundle）是发布格式，由商店或 bundletool 按 ABI、屏幕密度、语言和功能模块生成目标设备需要的 APK 集合；PackageInstaller 不解析 .aab，设备端最终接受的是"一个基础 APK 加零或多个 split APK"。同一安装会话内的一致性是安装前置条件：包名、versionCode 与签名证书一致，split APK 名称唯一，完整安装必须包含一个基础 APK；缺少必需 split、混入不同版本或不同签名的 APK，都会在验证或协调阶段失败。职责边界是：下载、断点续传与重试属于商店下载器，`openWrite(name, offset, length)` 只把字节写入会话暂存区、不发起网络请求，所以"弱网下载失败"在下载层定位，"写完但 commit() 失败"才进入 PackageInstaller 与 Package Manager 的诊断范围。

**Q2: 一次普通 APK 安装在 Package Manager 里分哪四个阶段执行？commit() 正常返回与安装完成是什么关系？**

四阶段是 Prepare、Scan、Reconcile、Commit，由 `InstallPackageHelper.installPackagesTraced()` 组织（AAOS13 按此结构核对）：Prepare 检查安装参数、替换关系、ABI 与签名，Scan 解析待装包生成扫描结果，Reconcile 让多个扫描结果与现有包、共享用户和签名规则一致，Commit 才在锁保护下修改包状态。`commit()` 只是把封存的会话交给系统异步处理：seal 持久化封存状态、流式校验后发 MSG_INSTALL 进入 handleInstall，再走四阶段事务，最终结果经 IntentSender 回调返回——所以 commit() 返回成功不代表安装成功，也不代表首帧可用。Commit 之后还有路径切换、应用数据准备与 dexopt；路径切换在 dexopt 之前，因为 OAT/VDEX 产物关联最终代码路径。

**Q3: Android 13 上安装 dexopt 由谁调度执行？为什么"安装会话失败"不能自动归因于 dex2oat？**

Android 13 的安装 dexopt 仍在 Package Manager 侧编排：DexOptHelper 发起请求、PackageDexOptimizer 经 `mInstaller.dexopt` 走 Binder 调 installd，真正编译在 dex2oat 进程完成（AAOS13 源码核对）；Android 14 起这项工作迁移到 ART Service（ArtManagerLocal 与 artd 守护进程）统一调度，分析新版本的编译问题要把链路追到 ART Service，不能沿用 PMS→installd 主线描述。dexopt 是尽力而为步骤：编译失败的应用先以解释执行或较低优化级别运行，再由后台任务补齐，因此 dex2oat 失败通常不导致安装回滚；反过来，安装成功也不证明 AOT 已完成。实际编译过滤器还受 `pm.dexopt.<reason>` 配置、Profile 可用性与设备状态影响，请求 speed-profile 不等于必然全量 AOT。

**Q4: Android 13 的 PMS 已用 Computer 快照优化查询，这是否意味着包查询无锁？查询变慢时该怎么归因？**

不是无锁。PMS 有三把锁：mLock 保护内存中的包状态（要求持锁时间尽量短）、mInstallLock 保护对 installd 的访问（不在持 mLock 时获取）、mSnapshotLock 只用于构造快照；`snapshotComputer()` 比较数据版本，一致时直接返回缓存快照，落后时才在双锁保护下重建，而持有 mLock 的写路径会返回基于当前可变数据的实时查询视图（AAOS13 源码核对）。快照的意义是大量只读查询不必反复争抢主锁并复制状态；但 `getPackageInfo()` 仍要按调用方可见性、用户状态和查询 flags 构造结果。归因顺序：先看是否快照重建、可见性过滤或对象构造，再看 monitor 竞争与 Binder 排队——Perfetto 里看到 Computer 不能断言无锁，看到多把锁也不能断言必然锁竞争。

**Q5: APK Signature Scheme v4 与 IncFS 增量安装是什么关系？"边下载边安装"里存在"按需解密"吗？**

v4 自 Android 11 引入，生成面向流式安装的 .idsig 签名文件，并与 v2/v3 一起使用：.idsig 让系统在 APK 尚未完整落盘时校验已读取的数据块，而 APK 的最终身份与完整性仍由 v2/v3 保证，所以 v4 不是 v2/v3 的替代品。IncFS（增量文件系统）允许在全部数据块到达前开始安装运行：用户态数据加载器补缺块，内核用 Merkle 树逐块校验，整条链路只有"按需提供并校验数据块"，没有"按需解密"这一步——加密不是 IncFS 的定义。两项能力自 Android 11 起可用，Android 13 已包含。IncFS 安装卡顿要同时观察：数据加载器供数是否及时、存储读取是否阻塞、.idsig 与 APK 签名是否有效。

**Q6: Staged install 与普通安装都是事务，为什么还需要跨重启的分阶段会话？就绪状态为什么要先持久化再通知 apexd？**

普通安装的原子性止步于 Package Manager 状态提交；分阶段安装处理的是必须在新一次启动中以一致状态生效的系统级更新（APEX 与 APK），提供的是跨重启激活协议：PackageSessionVerifier 完成 pre-reboot 验证后把会话标记 ready 并持久化，重启后 apexd 激活 APEX，StagingManager 恢复会话安装 APK。顺序敏感是有理由的：先 `setSessionReady()` 持久化 ready，再 `markStagedSessionReady()` 通知 apexd（AAOS13 源码核对 PackageSessionVerifier 这一顺序）；窗口期内重启时 apexd 未收到通知不会激活，系统可把会话判为失败，避免出现"APEX 已激活而框架不知情"的分裂状态。`setStaged()` 自 Android 10 提供，是要求 INSTALL_PACKAGES 的 SystemApi，普通应用即使声明 REQUEST_INSTALL_PACKAGES 也不能创建分阶段会话；ready/applied/failed 三个状态持久化在 `/data/system/install_sessions.xml`，重启后不依赖调用方重新提交。

**Q7: 应用归档和"卸载但保留数据"差别在哪？Android 13 上能用平台 API 归档应用吗？**

不能。平台级归档是 Android 15 引入的能力（官方文档核对：持有 REQUEST_DELETE_PACKAGES 的应用可调用 `requestArchive()`，移除 APK 与缓存、保留用户数据，归档应用经 LauncherApps 作为可展示条目返回，恢复由负责安装器完成并以 ACTION_PACKAGE_ADDED 监控）；AAOS13 源码核对：DELETE_ARCHIVE、`requestArchive()` 与 PackageArchiver 均不存在。Android 13 的 DELETE_KEEP_DATA（`adb uninstall -k`）只做"卸载并保留数据"，没有归档入口、没有合成的桌面图标、也没有标准恢复契约——要恢复只能重新完整安装。归档也不涉及压缩：它把包转换成可恢复安装的状态，走包删除路径并通常终止目标进程，但触发原因和状态转换属于 Package Manager，不是 lmkd 那样的内存压力回收。

**Q8: 多用户设备上归档一个应用，能回收的空间为什么常常只有缓存？归档对恢复后的启动性能有什么影响？**

归档状态（installed=false 与归档元数据）按用户保存，APK 和原生库却位于包级代码目录、由多个用户共用；只有当被归档的是唯一仍安装该包的用户、且 PMS 不因缓存策略保留未安装包时，删除流程才会移除共享代码目录（归档链路按 Android 17 源码核对，共享代码目录的逻辑在 13 的多用户删除中同样成立）。因此单用户归档的稳定收益是 cache 与 code cache，不能把整个 APK 大小计入。同时归档会清理 ART 应用性能配置文件：用户数据和账号虽在，恢复后缺少热点代码记录，首次启动的编译状态可能比保持安装状态时的冷启动更差，必须单独测量，不能与常规冷启动数据混比。

**Q9: 用户点击已归档应用的图标后，从"类不存在"到"恢复安装"的系统链路怎么走？UNARCHIVAL_OK 表示恢复完成了吗？**

点击仍走 startActivity()：ActivityStarter 在 START_CLASS_NOT_FOUND 分支检查目标包处于归档状态、Intent 含显式 component、且 component 匹配 ArchiveState 中的原始入口，三项满足才转入恢复请求，本次启动以 START_ABORTED 结束；随后框架为恢复责任安装器（InstallSource 的 update owner 或 installer package）创建草稿安装会话，并发送显式指定接收方的 ACTION_UNARCHIVE_PACKAGE 广播，安装器确认账号、网络与空间后经标准 PackageInstaller 会话重新获取并安装 APK，成功后清除 ArchiveState 并广播 ACTION_PACKAGE_ADDED。UNARCHIVAL_OK 只表示安装器接受请求、恢复可以开始，不表示 APK 已装好——判断恢复完成要看安装会话状态或 ACTION_PACKAGE_ADDED。归档前系统必须先把入口 Activity、标题和图标保存进 ArchiveState（存于用户 CE 目录），因为 APK 删除后这些信息无处可读；恢复后的首启是普通冷启动，且因缓存与 profile 被清理可能更慢。
