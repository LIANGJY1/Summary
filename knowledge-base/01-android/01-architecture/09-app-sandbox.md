# 应用沙箱

> 学习资料（文章模式沉淀）。主线：UID/SELinux/seccomp 三层沙箱、多用户 UID 架构、seccomp 拒绝（SIGSYS）实战、UID 分配与段位、Zygote specialize 降权链。SELinux 拒绝排查与策略书写见 [11-selinux.md](11-selinux.md)；多进程初始化的启动期视角见 [../15-performance/07-app-startup-optimization.md](../15-performance/07-app-startup-optimization.md)（应用实践册）。2026-09-26 追加 Q5–Q12：沙箱与 UID 的源码深讲（UID 分配与段位、Zygote specialize 降权链、数据目录 DAC、sharedUserId 三层过滤、隔离进程与 SDK 沙箱、getCallingUid 安全语义），相关 UID 机制按 AOSP Application Sandbox、SELinux concepts、Zygote 文档与源码走读核对，常量按 Process.java（AOSP 13–17）核对。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 中的 Sandbox 怎么理解？**

Android 沙箱不是单独的组件，而是系统在应用进程边界上组合多种内核安全机制形成的隔离效果。应用通常以独立 UID 运行；同一应用也可有多个同 UID 进程，因此进程数不等于沙箱数。

整体上，UID/DAC 区分文件属主和进程凭据，SELinux/MAC 按安全域和对象类型限制能力，seccomp-BPF 收窄进程可调用的系统调用集合。各机制的判据和拒绝方式由后续机制题展开。

沙箱的边界可从三个方面判断：

1. **施加者**：沙箱由 Zygote fork 时设置进程凭据、init 配置命名空间和 SELinux 策略等系统机制共同施加。应用不能自行关闭这些系统边界。
2. **安全承诺**：这些机制限制应用间默认越权，但不能保证抵御所有内核漏洞。内核提权漏洞可能造成沙箱逃逸，SELinux 与 seccomp 是纵深防御而不是漏洞免疫保证。
3. **边界配置**：同 UID 的应用多进程共享 DAC 身份，但仍受其他策略限制。`android:sharedUserId` 可让符合条件的应用共享 UID，该机制自 API 29 起废弃。`exported` 组件和权限声明则构成受控的跨边界入口。

**Q2: 应用沙箱依靠哪些机制实现？DAC、MAC、seccomp-BPF 分别是什么？**

应用沙箱把三套内核机制叠在同一条进程边界上：每个应用默认独占一个 Linux UID 和一个进程，任何跨边界访问都必须经过显式 IPC 和权限检查。

三层机制的全称与含义：

1. **UID 隔离（DAC，Discretionary Access Control，自主访问控制）**：传统 Unix 文件权限——数据目录与进程凭据按 UID 判定归属，属主可自主分权，应用之间默认互不可见（Zygote fork 后把子进程降权到目标应用的 UID）；
2. **SELinux（MAC，Mandatory Access Control，强制访问控制）**：进程标域、对象标类型，策略未显式允许即拒绝，属主和 root 都改不了——Android 5.0 起全局 enforcing；
3. **seccomp-BPF（secure computing mode + Berkeley Packet Filter）**：系统调用过滤器——白名单之外的调用直接被内核拒绝、收窄内核攻击面——Android 8.0 起 Zygote 在 fork 应用进程时安装。

每层一个典型实例：

1. **DAC**：应用 A 读应用 B 的 0700 私有目录——UID 不匹配，返回 EACCES，没有崩溃只有失败；
2. **MAC**：应用读 /dev/ttyS0，文件权限位恰好放行，但 untrusted_app 域没有对应 allow——照样拒绝并留下 avc denied，DAC 放行、MAC 拦截；
3. **seccomp**：老 native 库调用被白名单淘汰的系统调用——SIGSYS（信号 31）直接杀进程，tombstone 带 syscall 编号。

称它们为内核机制，是因为拦截点都压在内核边界上，用户态代码绕不过（MAC 的少数用户态对象由对象管理器按同一份策略代为判定）——这也是沙箱与 android.permission.*（Java 层查询、内核不参与）的本质区别。

这套设计的直接推论：应用不能互相读取文件、不能互相 kill，共享数据必须走 ContentProvider 或 Binder 这类受控通道；`android:sharedUserId` 让两个应用合并 UID，等于主动拆掉沙箱，它已从 API 29 起废弃，新应用不应依赖。

**Q3: DAC 和 MAC 都在做"放行/拒绝"，本质区别是什么？**

输出同构（都是允许/拒绝），本质区别在三个维度：

1. **规则所有权**：DAC 的规则归资源属主（Discretionary 即"属主自主裁量"——chmod 777 立刻对全体放行）；MAC 的规则归系统策略（Mandatory 即"强制"——属主无权给自己加规则，只能随 sepolicy 更新）；
2. **判别维度**：DAC 只看 UID 一个数字——同 UID 的两个进程判不出差别；MAC 是"域 × 类型 × 类别 + MCS 级别"的多维标签——能区分同 UID 进程的能力差异（渲染器与网络代理的域不同）；
3. **对抗强度**：DAC 对属主和 root 都无效；MAC 默认拒绝、对 root 生效、neverallow 在编译期保证违规规则写不出来。

实证一句话：应用把自己的数据目录 chmod 777——DAC 层立刻对全体放行，MAC 层照样拦（untrusted_app 对其他应用的 app_data_file 无 allow，且 neverallow 封死放行规则的出生）。

收束："负责的业务不同"（文件归属 vs 服务能力）只是结果；本质是**决策权在属主还是在系统、判据是单一数字还是多维标签、可被绕过还是不可绕过**。Android 两层并用：DAC 零成本继承 Linux 的文件隔离，MAC 补上它天生缺失的强制力。

**Q4: 应用的 UID 是怎么分配的？u0_a199 这样的名字怎么换算成真实 UID？**

UID 在应用安装时由系统分配：PackageManagerService 驱动 installd 从应用段取号（Process.FIRST_APPLICATION_UID = 10000 起，LAST_APPLICATION_UID = 19999 封顶），同时创建数据目录并 chown 到该 UID。进程身份名 u0_a199 的读法是"第 0 个物理用户里的第 199 个应用"，换算：真实 UID = 10000 + 199 = 10199。

源码锚点（frameworks/base/core/android/os/Process.java 与 android.os.UserHandle，AOSP 13–17 常量稳定）：

1. UserHandle.getAppId(uid) 取 `uid % 100000` 得应用编号，UserHandle.getUserId(uid) 取 `uid / 100000` 得物理用户编号；
2. 多用户的 UID 公式是 物理用户编号 × 100000 + 应用编号（PER_USER_RANGE = 100000）——第 10 个用户的 u10_a199 是 10010199；user 0 的 SYSTEM 是 1000、user 10 的 SYSTEM 是 1001000，同一身份跨用户天然不同 UID；
3. 分配与数据目录同生同灭：卸载后 UID 由系统回收、近期重装通常复用（removed-app-id 机制），应用不得依赖固定 UID。

**Q5: 同一应用在工作资料里数据完全隔离，ps 里看到 u0_a123 与 u10_a123——多用户下 UID 怎么分配？跨用户访问的合法路径是什么？**

多用户隔离建立在 UID 体系上：`uid = userId × 100000 + appId`（`UserHandle` 的组合规则），同一 appId 在不同用户下是**不同的 Linux UID**——u0_a123 与 u10_a123 互为陌生应用，隔离由 UID 加 SELinux 双重实施。

1. **编号规则**：普通第三方应用 appId 从 10000 起（`AID_APP_START`）。userId 由系统分配，工作资料常见示例可能是 user 10，但不能把 10 当作固定编号。每个用户有独立数据目录 `/data/user/<userId>/`，`/data/data` 对应 user 0 的数据目录；
2. **跨用户访问是特权**：`startActivityAsUser`、`queryIntentActivitiesAsUser`、`createPackageContextAsUser` 都是 hidden/SystemApi，需要 `INTERACT_ACROSS_USERS(_FULL)`（signature|privileged 级）——普通应用拿不到；
3. **普通应用的合法路径**：拥有 launcher 角色可用 `LauncherApps.getActivityList(null, userHandle)` 列出各 profile 的活动，`UserManager.getUserProfiles()` 拿关联用户列表；Android 11+ 还叠加 package visibility 白名单限制；
4. **排查入口**："为什么看不到工作资料里的应用"按序查——是否具备 launcher 角色、是否声明了跨用户查询场景、package visibility 配置；`ps -A | grep u10_` 可快速确认双实例存在。

**Q6: "Fatal signal 31 (SIGSYS)" 把进程直接杀死——seccomp 拦截系统调用时表现成什么样？怎么确认是哪个调用被拒？**

seccomp 拦截不抛 Java 异常：被拒的系统调用直接以 SIGSYS（信号 31）杀死进程，logcat 表现为 `Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)`，tombstone 的信号信息带被拒的 syscall 编号。常见两类来源：应用升级 targetSdk 后，Zygote 按目标 SDK 安装更严格的白名单过滤器，老 native 库里被淘汰的调用被拒；或第三方 ROM/裁剪内核删掉了白名单允许的调用。

1. **机制**：Android 8.0 起 Zygote 在 fork 应用进程时安装 seccomp 过滤器，允许的系统调用集合可随 targetSdk 收紧。被拦截的调用可能以 SIGSYS 结束进程；seccomp 不会像 SELinux AVC 那样为每次拒绝提供常规审计记录；
2. **确认**：signal 31 + `SYS_SECCOMP` code；按 tombstone 里的 syscall 编号对照目标架构的 syscall 表，定位是哪个调用、来自哪个 `.so`（backtrace 给出偏移）；
3. **解决**：升级/重编 native 库，用现行 API 替换被淘汰的调用；若是 ROM 内核裁剪导致，属设备兼容问题——换规避实现或向厂商反馈；
4. **排查提示**：同类崩溃集中在"刚升 targetSdk 的版本 + 特定 native SDK"时优先怀疑 seccomp，而不是先查业务代码。

**Q7: 系统的 UID 段位有哪些？隔离进程、App Zygote、SDK 沙箱为什么各占一段？**

UID 段位就是能力档位：0 是 root、1000 是 SYSTEM（system_server 等平台进程）、1001 是 PHONE、2000 是 SHELL、1002 是 BLUETOOTH 等平台固定身份；第三方应用占 10000–19999；再往上三段特殊档——SDK 沙箱 20000–29999、App Zygote 派生进程 90000–98999、隔离进程 99000–99999。

分段的原因是 SELinux 域与能力按 UID 段匹配，段位即收紧等级：

1. **SDK 沙箱**（Android 13 起，SDK Runtime）：第三方 SDK 代码跑在独立进程 com.android.sdk.sandbox 里，与宿主应用同偏移映射；
2. **App Zygote**（Android 9 起）：应用可声明自己的 zygote，派生进程落 90000–98999——Chrome 的渲染进程就是这个用法，配合最小能力收紧；
3. **隔离进程**：99000–99999 每次启动随机取号、不落任何用户组，段位最小即能力最小。

收束：看到 UID 或进程名先对段位——段位直接决定这个进程被 SELinux 匹配到哪个域、被允许活成什么样。

**Q8: Zygote fork 出应用进程时，UID 与 SELinux 域是在哪一步被设置的？**

不是 Zygote 的身份，而是 specialize（改造）阶段设置到子进程上的。链路：AMS 的 ProcessList.startProcess 组装参数（--setuid、--setgid、--gids、--seinfo 等）→ 写入 Zygote socket → ZygoteConnection 解析 → Zygote.forkAndSpecialize → native 层 com_android_internal_os_Zygote.cpp 的 SpecializeCommon 完成改造。

SpecializeCommon 的关键顺序（Android 13 源码）：

1. SetGids：设置补充组——按应用申请的权限映射（如 inet 组给网络能力）；
2. SetSELinuxContext：按 seinfo 经 seapp_contexts 规则选出目标域（untrusted_app 等）并切换安全上下文；
3. SetSeccompFilter：安装 seccomp-BPF 系统调用过滤器；
4. 最后才 SetUidGid：setresgid()/setresuid() 把身份降到目标应用 UID——全部配置在丢权之前完成，丢权后子进程再也无法改回身份。

边界：任何一步失败都直接 kill 子进程，由 AMS 走重试路径；Zygote 本体始终保持 root 身份、没有 Binder 线程——身份交接只发生在 specialize 一瞬间。

**Q9: 应用数据目录的隔离是怎么落到 Linux 文件权限上的？**

完全靠传统 Unix DAC：installd 创建 /data/user/0/<包名> 时把它 chown 到应用 UID 并设 0700——其他应用的 UID 不是目录 owner，权限检查直接拒绝，不需要任何额外的隔离代码。

run-as 进一个 debuggable 应用实测（Android 13）可见：

```text
drwxrws--x u0_a199 u0_a199_cache … files
```

读法：owner 是 u0_a199、属组是 u0_a199_cache 且带 setgid 位（组内共享缓存场景）、others 只有 x（可穿过不可列目录）——同 UID 的多进程天然全通，不同 UID 一律按 other 挡住。

边界：DAC 对 root 无效（root 可读任何应用目录；run-as 仅对 debuggable 应用与 shell 放行）；外置存储走 FUSE 路径、权限规则与私有目录不同——"目录属主变了，隔离边界就变了"正是 UID 作为沙箱载体的物证。

**Q10: 两个应用通过 sharedUserId 共享 UID 后，就一定能互相访问吗？**

不一定。sharedUserId 让两个应用（要求同签名）在安装时拿到同一个 UID——这只是打通了三层过滤中的第一层；完整互通要同时满足"同 UID + 同 SELinux 域 + 同物理用户"，任何一层不同都可能被拦。

三层逐层看：

1. **Linux 权限层**：同 UID 则数据目录 owner 相同，DAC 放行——这是 sharedUserId 的原始目的；
2. **SELinux 层**：域由 seinfo 与 targetSdkVersion 决定（untrusted_app、untrusted_app_27 等），签名相同不代表域相同——一个 targetSdk 28 一个 29 会落进不同域，MAC 规则仍可能拒绝互访；
3. **多用户层**：UID 里编码了物理用户号——user 0 的应用是 10xxx，克隆到 user 10 就变成 1001xxx，跨用户即便 appId 相同 UID 也不同，天然不互通。

边界：sharedUserId 自 API 29 起对第三方应用废弃，新代码不应依赖；现代替代是把共享逻辑收进同一应用的多进程，或走显式 IPC/ContentProvider。

**Q11: isolatedProcess 的沙箱为什么更小？UID 在其中是怎么变的？**

声明 `android:isolatedProcess="true"` 的 Service 会由系统分配临时隔离 UID，并运行在 `isolated_app` 等隔离策略域中。该 UID 不继承宿主应用的普通权限和文件属主身份，因此不能像宿主进程一样访问应用私有数据；隔离进程可用的系统服务和资源仍取决于对应 Android 版本的 SELinux 策略及服务授权，不能一概说成完全不可访问。

UID 区间、临时身份和 SELinux 域共同收窄能力面。退出后系统可回收隔离 UID。宿主应通过受控的 Binder 或管道接口提供所需输入，适合把处理不可信数据的组件与应用主体分开。

**Q12: SDK 沙箱（Android 13）是什么？它的 UID 是怎么映射的？**

SDK 沙箱是 Android 13 引入的 SDK Runtime：把第三方广告/分析 SDK 从宿主应用进程挪进独立进程（com.android.sdk.sandbox）运行，SDK 代码只能经定义好的 Binder 接口与宿主通信。UID 落在 20000–29999 段，与宿主应用同偏移映射：沙箱 UID = 20000 + (应用 appId − 10000)——宿主是 u0_a199（10199）时，它的 SDK 沙箱就是 20199。

设计意图与边界：

1. 同偏移映射让系统与用户都能从 UID 直接看出"这个沙箱属于哪个应用"；
2. 沙箱进程的 SELinux 域与权限面独立于宿主，SDK 想要的能力要经宿主代为请求——权限弹窗、身份都归宿主；
3. 该机制要求设备支持 SDK Runtime 且用户未关闭，应用必须保留"SDK 跑在自己进程里"的回退路径。

**Q13: 为什么框架的权限检查都用 getCallingUid 而不是 PID？**

因为 UID 是操作系统分配的安全身份，而 PID 是会随进程退出而变化的进程编号。Binder 驱动在事务中传递内核记录的发送方 uid/pid，正在处理远程 Binder 事务时，`Binder.getCallingUid()` 返回该事务的调用方 UID，调用方不能靠修改参数伪造它。因此跨进程权限检查通常基于 UID，而不是客户端自报的进程号。

细节与边界：

1. **身份转发**：进程代调用方继续调用受保护服务时，Binder 调用身份仍可能代表原始调用方。只有明确要改用服务自身权限执行时，才成对调用 `clearCallingIdentity()` 与 `restoreCallingIdentity()`，并保证恢复发生在异常路径之后。
2. **多用户判定**：按调用者用户隔离时还要使用 `getCallingUserHandle()` 或从 UID 取得 userId；同一 appId 在不同用户下是不同 UID。
3. **PID 的用途**：PID 适合进程管理（如 kill、进程状态）和调试归因，不是稳定的权限身份。对于同进程本地调用，不能把 `getCallingUid()` 误读为一个独立远程应用的凭据。

收束：沙箱的单位是 UID，所以安全判据也是 UID——这条对齐贯穿框架的每一处权限检查。

**Q14: SELinux 完全是 Linux 内核的机制吗？**

判定主体在内核，但不是全部——内核负责钩子判定与策略内存，用户态负责策略的生产、装载与少数对象的执法。

分工：

1. **内核侧（主体）**：SELinux 是内核 LSM 框架下的安全模块；策略装载后存为内存 policydb，文件、socket、属性等对象的访问判定全在内核完成——先查 AVC 缓存，未命中再查策略；
2. **用户态侧（生产与装载）**：.te 与 contexts 源文件由 checkpolicy/secilc 编译成二进制策略，开机由 init 写 /sys/fs/selinux/load 装载进内核；
3. **用户态侧（执法）**：service_manager 这类 userspace 对象的 add/find 检查由 servicemanager 用 libselinux 查同一份策略自行判定——拒绝不产生内核 avc 日志，只出现在 logcat 的 events 缓冲。

收束：说"SELinux 是内核机制"指的是判定主体（LSM 钩子 + policydb）；策略生产与 userspace 类执法在用户态——两类拒绝的日志路径也因此不同。

**Q15: MAC 层的拒绝（如应用读 /dev/ttyS0 被 avc denied）是 Linux 内核行为还是 Android 上层行为？**

拦截动作是纯 Linux 内核行为——DAC 与 MAC 两道检查在内核 VFS 层的同一函数里先后执行；Android 决定的只是策略规则的内容（哪个域对哪个类型有 allow），框架层全程不参与。

链路：应用 open("/dev/ttyS0") → 内核先做 DAC 检查（inode 权限位 vs 进程 UID），假设放行 → 紧接着 LSM 钩子 security_inode_permission 触发 SELinux：拿"进程域 untrusted_app vs 文件标签 tty_device"查内存 policydb，无 allow 规则 → 返回 EACCES 并留 avc denied。全程没有 Java 异常或权限弹窗——Android 框架甚至不知道这次访问发生过。

实锤判据：错误码 EACCES 来自内核、avc 行出现在 dmesg；若拦截发生在 Android 上层，表现会是 Java 异常或权限弹窗。收束：拦截机制是 Linux 内核的，拦截规则是 Android sepolicy 写的——同一套内核装载不同策略，行为就不同。

**Q16: service_manager 类的 avc 拒绝（如 find audio 被拒）也是内核判的吗？**

不是。service_manager 是 userspace 类——判定由 servicemanager 进程在用户态执行，内核只充当策略数据库。以真实日志 `auditd : avc:  denied  { find } for pid=7283 uid=10093 name=audio scontext=u:r:nsr_appstore:s0:c512,c768 tcontext=u:object_r:audio_service:s0 tclass=service_manager permissive=0` 为例：

1. pid 7283 的进程调 getService("audio")，binder 事务携带调用者的 SELinux 上下文到达 servicemanager；
2. servicemanager 先查注册表——audio 服务已注册、binder 指针已到手，再做 canFind 检查：libselinux 先查进程内 AVC 缓存，未命中写 /sys/fs/selinux/access 向内核 policydb 查询；
3. 内核算出"不允许"→ servicemanager 丢弃 binder、返回 null（事务状态仍是 ok），随后 avc_audit 把这行日志以 auditd tag 写进 logd 的 events 缓冲。

日志自证三点：

1. tclass=service_manager 是 userspace 类，这类检查不在内核 LSM 执行；
2. tag 为 auditd、行首 pid 是 servicemanager 自己（内核 avc 带 type=1400 前缀、写进 dmesg）；
3. pid/uid/name 字段由 servicemanager 的审计回调从 binder 调用者身份填充（内核 avc 由内核自己拼）。

边界：这类拒绝在 dmesg 里搜不到——"崩溃现场没有 avc"先换 `logcat -b events` 或怀疑 dontaudit 静音，不要直接下"与 SELinux 无关"的结论。
