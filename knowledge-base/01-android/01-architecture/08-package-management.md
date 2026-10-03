# 应用包管理：安装、校验与归档

> 学习资料（文章模式沉淀）。边界：本文回答"应用从分发制品到安装事务、dexopt 编排、增量与分阶段安装、应用归档与恢复"；资源与 Configuration 更新归 [../03-ui/03-resources.md](../03-ui/03-resources.md)；构建期模块声明归 13-build-system。源文档：android-internals-wiki §1.16–§1.17；机制按本地 AAOS13 源码（Android 13）核对，安装 dexopt 迁往 ART Service（Android 14 起）与平台级 App Archiving（Android 15 起）已标注版本差异。Q 序列即结构，供 atlas 同源直读。

**Q1: 商店上传的是 AAB，手机端最终安装的却是 APK：AAB 为什么不能直接安装，设备端 PackageInstaller 对 base/split APK 集合校验什么？**

AAB（Android App Bundle）是发布格式，不是设备直接安装的 APK。商店或 bundletool 按设备 ABI、屏幕密度、语言和功能模块生成适用的 APK 集合，再交给设备端 PackageInstaller。

完整安装会话要满足 APK 集合的一致性条件：

1. 包含一个 base APK，split APK 名称唯一。
2. base 与所有 split 的包名、versionCode 和签名证书一致。
3. 必需 split 不得缺失，也不能混入其他版本或不同签名的 APK；否则会在验证或协调阶段失败。

下载与安装会话的职责不同。商店下载器负责网络下载、断点续传与重试；`openWrite(name, offsetBytes, lengthBytes)` 把已经取得的字节写入会话暂存区，不负责网络请求。`name` 是会话内唯一的 APK 名称，`offsetBytes` 为写入起点（0 表示从头写，可用现有长度续传），`lengthBytes` 是文件总长度并用于预分配空间；长度未知时传 `-1`。弱网失败应先查下载器，写完后 `commit()` 失败则进入 PackageInstaller 与 Package Manager 的诊断范围。

**Q2: 一次普通 APK 安装在 Package Manager 里分哪四个阶段执行？commit() 正常返回与安装完成是什么关系？**

Android 13 的 `InstallPackageHelper.installPackagesTraced()` 将包事务组织为 Prepare、Scan、Reconcile、Commit 四阶段：

1. **Prepare**：检查安装参数、替换关系、ABI 与签名。
2. **Scan**：解析待安装包并生成扫描结果。
3. **Reconcile**：让多个扫描结果与现有包、共享用户和签名规则保持一致。
4. **Commit**：在锁保护下修改包状态。

调用 `commit()` 不会同步完成这四阶段。会话先 seal 并持久化封存状态，系统完成流式校验后发送 `MSG_INSTALL` 进入 `handleInstall`，再执行包事务并通过 `IntentSender` 回调最终结果。因此 `commit()` 正常返回只说明请求已提交处理，不代表安装成功或应用首帧可用。

安装事务之后还有代码路径切换、应用数据准备和 dexopt。路径切换必须先于 dexopt，因为 OAT/VDEX 产物关联最终代码路径。

**Q3: Android 13 上安装 dexopt 由谁调度执行？为什么"安装会话失败"不能自动归因于 dex2oat？**

Android 13 的安装 dexopt 仍在 Package Manager 侧编排：DexOptHelper 发起请求、PackageDexOptimizer 经 `mInstaller.dexopt` 走 Binder 调 installd，真正编译在 dex2oat 进程完成（AAOS13 源码核对）；Android 14 起这项工作迁移到 ART Service（ArtManagerLocal 与 artd 守护进程）统一调度，分析新版本的编译问题要把链路追到 ART Service，不能沿用 PMS→installd 主线描述。dexopt 是尽力而为步骤：编译失败的应用先以解释执行或较低优化级别运行，再由后台任务补齐，因此 dex2oat 失败通常不导致安装回滚；反过来，安装成功也不证明 AOT 已完成。实际编译过滤器还受 `pm.dexopt.<reason>` 配置、Profile 可用性与设备状态影响，请求 speed-profile 不等于必然全量 AOT。

**Q4: Android 13 的 PMS 已用 Computer 快照优化查询，这是否意味着包查询无锁？查询变慢时该怎么归因？**

Package Manager 的 Computer 快照减少只读查询对主状态锁的依赖，但不等于无锁。Android 13 PMS 的锁职责和查询路径如下：

1. **`mLock`**：保护内存中的包状态，持锁时间应尽量短。
2. **`mInstallLock`**：保护对 installd 的访问；按该分支约束，不应持有 `mLock` 时再获取它。
3. **`mSnapshotLock`**：用于构造快照。
4. **查询快照**：`snapshotComputer()` 比较数据版本，版本一致时返回缓存快照，快照落后时才在相应锁保护下重建。写路径持有 `mLock` 时可能返回基于当前可变数据的实时查询视图。
5. **结果构造**：`getPackageInfo()` 还需按调用方可见性、用户状态和查询 flags 过滤并构造结果。

排查查询变慢时，先区分快照重建、可见性过滤、对象构造，再查 monitor 竞争与 Binder 排队。Perfetto 中出现 Computer 不能证明路径无锁；代码中有多把锁也不能证明实际发生锁竞争。

**Q5: APK Signature Scheme v4 与 IncFS 增量安装是什么关系？"边下载边安装"里存在"按需解密"吗？**

v4 自 Android 11 引入，生成面向流式安装的 .idsig 签名文件，并与 v2/v3 一起使用：.idsig 让系统在 APK 尚未完整落盘时校验已读取的数据块，而 APK 的最终身份与完整性仍由 v2/v3 保证，所以 v4 不是 v2/v3 的替代品。IncFS（增量文件系统）允许在全部数据块到达前开始安装运行：用户态数据加载器补缺块，内核用 Merkle 树逐块校验，整条链路只有"按需提供并校验数据块"，没有"按需解密"这一步——加密不是 IncFS 的定义。两项能力自 Android 11 起可用，Android 13 已包含。IncFS 安装卡顿要同时观察：数据加载器供数是否及时、存储读取是否阻塞、.idsig 与 APK 签名是否有效。

**Q6: Staged install 与普通安装都是事务，为什么还需要跨重启的分阶段会话？就绪状态为什么要先持久化再通知 apexd？**

普通安装的事务保护 Package Manager 状态提交；分阶段安装则为 APEX 与 APK 等更新提供跨重启激活协议，让新状态在一次重启过程中协调生效。Android 13 的流程如下：

1. PackageSessionVerifier 完成重启前验证。
2. `setSessionReady()` 先把 ready 状态持久化。
3. `markStagedSessionReady()` 再通知 apexd。
4. 重启后 apexd 激活 APEX，StagingManager 恢复会话并安装 APK。

先持久化、后通知可避免“APEX 已激活而框架不知情”：若两步之间重启，apexd 尚未收到 ready 通知，不会激活该会话，系统可以把它判为失败。

`setStaged()` 自 Android 10 提供，是要求 `INSTALL_PACKAGES` 的 SystemApi。普通应用声明 `REQUEST_INSTALL_PACKAGES` 仍不能创建 staged session。ready、applied、failed 状态持久化在 `/data/system/install_sessions.xml`，重启后不依赖调用方重新提交。

**Q7: 应用归档和"卸载但保留数据"差别在哪？Android 13 上能用平台 API 归档应用吗？**

Android 13 不能调用平台级应用归档 API。Android 15 起，持有 `REQUEST_DELETE_PACKAGES` 权限的应用可通过 `PackageInstaller.requestArchive()` 请求归档：系统移除 APK 和缓存、保留用户数据，并通过 LauncherApps 将归档应用作为可展示条目返回。用户请求恢复时由负责安装器重新安装，可用 `ACTION_PACKAGE_ADDED` 观察安装完成。

Android 13 源码中没有 `DELETE_ARCHIVE`、`requestArchive()` 或 PackageArchiver。该版本的 `DELETE_KEEP_DATA`（例如 `adb uninstall -k`）只表示卸载时保留数据，不提供归档入口、合成桌面条目或标准恢复契约；恢复要重新完整安装应用。归档也不是压缩或 lmkd 内存回收，而是由 Package Manager 发起、保留用户数据的可恢复包删除流程，通常会终止目标进程。

**Q8: 多用户设备上归档一个应用，能回收的空间为什么常常只有缓存？归档对恢复后的启动性能有什么影响？**

归档状态（`installed=false` 与归档元数据）按用户保存，APK 和原生库则放在多个用户共用的包级代码目录。空间回收取决于该包是否仍被其他用户安装：

1. 如果其他用户仍安装该包，PMS 必须保留共享 APK 和原生库，归档一个用户通常只能回收其缓存和 code cache。
2. 如果该用户是唯一仍安装该包的用户，并且 PMS 的缓存策略未保留代码目录，删除流程才可能回收共享 APK 与原生库。
3. 因此不能按 APK 文件大小估算单用户归档的确定收益。稳定收益通常来自该用户的 cache 与 code cache。

归档还会清理 ART 应用性能配置文件。用户数据和账号虽保留，恢复后缺少热点代码记录，首次启动的编译状态可能比保持安装状态时更差。应把归档恢复后的首次启动单独测量，不要和普通冷启动数据混比。

**Q9: 用户点击已归档应用的图标后，从"类不存在"到"恢复安装"的系统链路怎么走？UNARCHIVAL_OK 表示恢复完成了吗？**

点击已归档应用图标仍会进入 `startActivity()` 流程。Android 17 的恢复链按以下步骤运行：

1. ActivityStarter 在 `START_CLASS_NOT_FOUND` 分支确认目标包处于归档状态、Intent 含显式 component，且 component 与 ArchiveState 保存的原始入口匹配。满足条件后转入恢复请求，本次启动以 `START_ABORTED` 结束。
2. Framework 根据 InstallSource 选择负责恢复的安装器：优先使用 update owner；没有 update owner 时使用 installer package。
3. 系统为恢复安装器创建草稿安装会话，并向其显式发送 `ACTION_UNARCHIVE_PACKAGE` 广播。
4. 安装器确认账号、网络和空间条件后，经标准 PackageInstaller 会话重新获取并安装 APK。
5. 安装成功后系统清除 ArchiveState 并发送 `ACTION_PACKAGE_ADDED`。`UNARCHIVAL_OK` 只表示安装器接受请求、恢复可以开始，不表示 APK 已装好；应检查安装会话状态或后续广播确认完成。

APK 删除前，系统会把入口 Activity、标题和图标保存在 ArchiveState（该实现使用用户 CE 目录），以便 Launcher 仍能展示归档条目。恢复后的首启是普通冷启动；缓存与 ART profile 已清理时，启动可能比保持安装状态时更慢。
