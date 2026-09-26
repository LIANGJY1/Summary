# 应用沙箱

> 学习资料（文章模式沉淀）。主线：UID/SELinux/seccomp 三层沙箱、多用户 UID 架构、seccomp 拒绝（SIGSYS）实战。SELinux 拒绝排查与策略书写见 [20-SELinux.md](./20-SELinux.md)；多进程初始化的启动期视角见 [05-启动优化.md](../09-app-practice/05-启动优化.md)（app-practice 册）。Q 序列即结构，供 atlas 同源直读。

**Q1: 应用沙箱依靠哪些机制实现？为什么说"进程边界就是安全边界"？**

应用沙箱把 Linux 的 UID 隔离、SELinux 强制访问控制和 seccomp-BPF 系统调用过滤叠在同一条边界上：每个应用默认独占一个 Linux UID 和一个进程，任何跨边界访问都必须经过显式 IPC 和权限检查。

1. **UID 隔离**：文件权限与进程凭据按 UID 判定，应用之间的数据目录、进程、内存默认互不可见（Zygote fork 后把子进程降权到目标应用的 UID）；
2. **SELinux**：Android 5.0 起全局 enforcing，每个进程运行在自己的域里，未显式允许即拒绝；
3. **seccomp-BPF**：Android 8.0 起限制应用可用的系统调用集合，收窄内核攻击面。

这套设计的直接推论：应用不能互相读取文件、不能互相 kill，共享数据必须走 ContentProvider 或 Binder 这类受控通道；`android:sharedUserId` 让两个应用合并 UID，等于主动拆掉沙箱，它已从 API 29 起废弃，新应用不应依赖。


**Q2: Android 中的 Sandbox 怎么理解？**

沙箱不是一个独立组件，而是叠在同一条进程边界上的三层内核隔离的组合：每个应用默认独占一个 Linux UID 和一个进程，UID 管住文件与进程凭据、SELinux 管住"未显式允许即拒绝"、seccomp-BPF 管住可用系统调用集合，跨边界只剩显式 IPC 和权限检查这些受控通道。

按三个常见误解来理解它的边界：

1. **沙箱不是应用内部的东西**：它由 Zygote fork 降权、init 挂载命名空间、SELinux 策略等系统机制在进程创建时施加，应用只能在其内运行，不能关闭或修改；
2. **沙箱不是绝对安全屏障**：它防的是应用之间与应用对系统的默认越权，不承诺防住内核漏洞——内核提权漏洞可以让应用逃逸沙箱，SELinux 与 seccomp 是纵深防御而非保证；
3. **边界形态可以被配置改变**：应用自开多进程让同一 UID 内多个进程共享沙箱；`android:sharedUserId` 让两个应用合并 UID 等于合并沙箱，它已从 API 29 起废弃；`exported` 组件与权限声明则是边界上的受控开口。

**Q3: 同一应用在工作资料里数据完全隔离，ps 里看到 u0_a123 与 u10_a123——多用户下 UID 怎么分配？跨用户访问的合法路径是什么？**

多用户隔离建立在 UID 体系上：`uid = userId × 100000 + appId`（`UserHandle` 的组合规则），同一 appId 在不同用户下是**不同的 Linux UID**——u0_a123 与 u10_a123 互为陌生应用，隔离由 UID 加 SELinux 双重实施。

1. **编号规则**：appId 从 10000 起（`AID_APP_START`）才是三方应用；work profile 通常占 userId=10；每用户有独立数据目录 `/data/user/<userId>/`，`/data/data` 只是 user 0 的符号链接；
2. **跨用户访问是特权**：`startActivityAsUser`、`queryIntentActivitiesAsUser`、`createPackageContextAsUser` 都是 hidden/SystemApi，需要 `INTERACT_ACROSS_USERS(_FULL)`（signature|privileged 级）——普通应用拿不到；
3. **普通应用的合法路径**：拥有 launcher 角色可用 `LauncherApps.getActivityList(null, userHandle)` 列出各 profile 的活动，`UserManager.getUserProfiles()` 拿关联用户列表；Android 11+ 还叠加 package visibility 白名单限制；
4. **排查入口**："为什么看不到工作资料里的应用"按序查——是否具备 launcher 角色、是否声明了跨用户查询场景、package visibility 配置；`ps -A | grep u10_` 可快速确认双实例存在。

**Q4: "Fatal signal 31 (SIGSYS)" 把进程直接杀死——seccomp 拦截系统调用时表现成什么样？怎么确认是哪个调用被拒？**

seccomp 拦截不抛 Java 异常：被拒的系统调用直接以 SIGSYS（信号 31）杀死进程，logcat 表现为 `Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)`，tombstone 的信号信息带被拒的 syscall 编号。常见两类来源：应用升级 targetSdk 后，Zygote 按目标 SDK 安装更严格的白名单过滤器，老 native 库里被淘汰的调用被拒；或第三方 ROM/裁剪内核删掉了白名单允许的调用。

1. **机制**：Android 8.0 起 Zygote 在 fork 应用进程时安装 seccomp 过滤器，白名单随 targetSdk 收紧——这是沙箱三层（Q1）中最"沉默"的一层：崩溃即全部信息，没有 avc 那样的审计日志可查；
2. **确认**：signal 31 + `SYS_SECCOMP` code；按 tombstone 里的 syscall 编号对照目标架构的 syscall 表，定位是哪个调用、来自哪个 `.so`（backtrace 给出偏移）；
3. **解决**：升级/重编 native 库，用现行 API 替换被淘汰的调用；若是 ROM 内核裁剪导致，属设备兼容问题——换规避实现或向厂商反馈；
4. **排查提示**：同类崩溃集中在"刚升 targetSdk 的版本 + 特定 native SDK"时优先怀疑 seccomp，而不是先查业务代码。

