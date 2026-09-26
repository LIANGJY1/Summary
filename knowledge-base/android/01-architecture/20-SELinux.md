# Android SELinux

> 学习资料（文章模式沉淀）。主线：SELinux 拒绝（avc denial）的确认与处置——从"功能静默失效"到四元组定位、audit2allow 与 neverallow 的处理边界。语境：全局 enforcing 自 Android 5.0 起；启动期策略合成与装载见 [02-Android系统启动流程.md](./02-Android系统启动流程.md)；沙箱三层（UID/SELinux/seccomp）见 [04-Sanbox.md](./04-Sanbox.md)。命令与流程已于 2026-09-25 与官方资料（source.android.com《Validate SELinux》）核对；Q3–Q8 的策略书写机制按 system/sepolicy 与 car_product/sepolicy 源码核对。2026-09-25 会话沉淀追加 Q9–Q18：service_manager 用户态检查链源码、avc 日志双路径与字段解剖、厂商域漏配 find 的崩溃循环案例与修复流程，按 AAOS13_study（Android 13）源码逐行核对。同日二轮并入《selinux 配置指南》与用户补充问题（Q19–Q26）：概念总览、原理与代码落点、应用/framework 两级开发者场景、ioctl allowxperm 双层授权、neverallow 两大编译场景与最小权限原则、工作模式与 ALLOW_PERMISSIVE_SELINUX、make selinux_policy 与 audit2allow 快速验证，均经 AAOS13_study 源码核对（材料笔误已修正，如 DALLOW_PERMISSIVE_SELINUX 实为 ALLOW_PERMISSIVE_SELINUX）。Q 序列即结构，供 atlas 同源直读。

**Q1: 功能静默失效——读不到 /sys 节点、打不开 /dev 设备、跨进程共享文件失败，却没有任何 Java 异常。怎么确认是 SELinux 拒绝？确认后怎么处理？**

确认手法是抓内核审计日志：SELinux 拒绝不抛异常，只在内核日志里留 avc denial——`adb logcat -b all | grep avc` 或 `dmesg | grep avc`，有 denial 记录即坐实。

1. **读四元组**：`scontext`（来源安全域，如 `u:r:untrusted_app:s0`）、`tcontext`（目标对象及其标签）、`tclass`（对象类别）、`permission`（被拒操作）——合起来就是"哪个域对什么对象做什么被拒"，allow 规则也按同样四元组书写；
2. **认出两类设计性拒绝**：应用数据目录是带 MLS category 的 `app_data_file`，应用互访天然被拒——这是沙箱的强制执行层；应用域对 `/sys`、`/proc` 多数节点默认无权限，取这类信息应走公开 API 而不是碰节点；
3. **处理路径**：`audit2allow` 能把 denial 日志生成候选 allow 规则，但必须人工审读、理解"为什么需要"后放进对应分区的 `.te` 策略文件；撞到编译期 `neverallow` 说明该访问路径被平台禁止，正确动作是改设计（换公开 API 或受控通道），不是删规则；
4. **调试前提**：user build 不能 `setenforce 0`，排障用 userdebug/eng；策略改动要重编对应分区镜像或用可调试构建验证。

**Q2: 开机早期 setprop 总是失败、OTA 后首次开机明显变慢——属性标签（property_contexts）和策略装载有什么关系？**

属性写权限由 SELinux 控制到"哪个域能写哪个前缀"，前缀到标签的映射来自各分区的 property_contexts：init 按序加载 plat/system_ext/vendor/product/odm 的 property_contexts 构建查找树写入共享属性区——属性要么命中某个标签，要么落 `default_prop`，对无标签前缀的早期 setprop 会直接失败。

1. **策略装载路径**：优先信任 vendor 预编译策略——三对 sha256（plat/system_ext/product 与 precompiled 副本）比对一致才直接装载；不一致时 init 现场跑 secilc 编译，开机明显变慢（装载时序细节见 [02-Android系统启动流程.md](./02-Android系统启动流程.md) Q12）；
2. **排查入口**：OTA 后变慢先查日志 "Compiling SELinux policy" 与 `dmesg | grep -i sepolicy`；denied 按 Q1 的 avc 流程走；核对 `/system/etc/selinux/plat_sepolicy_and_mapping.sha256` 与 vendor 侧副本是否成对一致；
3. **边界**：属性区是只读共享内存，"读属性"不走写权限检查；写权限的控制粒度是前缀，不是单个属性名。

**Q3: allow 规则到底该写在哪个文件？为什么在 public/*.te 里加规则会被编译拒绝？**

sepolicy 目录按可见性分层：`public` 是 vendor 可见的平台接口层（类型、attribute、宏），`private` 是平台自用规则区——`public/app.te` 文件头明文警告"不要在这里加 allow/neverallow/dontaudit"，具体规则写 `private/*.te`；vendor 侧只能引用 public 暴露的类型加自有类型。

1. **attribute 扩展机制**：给自定义服务授权用 attribute 而非逐域 allow——`attribute app_api_service`（注释原话：对所有应用可用、isolated 除外）；HAL 用 `hal_attribute(hal_x)` 宏一次生成 hal_x/hal_x_client/hal_x_server 三个属性，客户端 `hal_client_domain(域, hal_x)` 挂接；
2. **权威实例**：carservice 的 `carservice_app.te`：`app_domain(carservice_app)` + `add_service(carservice_app, carservice_service)` + 对 vehicle/audiocontrol/evs 等 HAL 逐个 `hal_client_domain`——照这个骨架写最不容易踩坑；
3. **诊断**：neverallow 报错的行号回溯规则所在 .te；不确定宏展开结果时用 m4 预展开确认。

**Q4: 新设备节点打了标签还是 avc denied，tcontext 显示 unlabeled——file_contexts 的求值规则是什么？**

file_contexts 三条求值规则：静态条目先求值、第一条命中即用、**从文件底部向上**评估——越靠底的条目越优先，宽泛规则写前面、具体规则放底部。

1. **条目格式**：`路径正则 [--] u:object_r:类型:s0`（`--` 分隔可选的文件类型标记）；各分区（vendor/odm）的 file_contexts 在构建期合并；
2. **unlabeled 的含义**：规则没命中——先补 file_contexts 条目，而不是给 unlabeled 加 allow；
3. **验证**：`ls -Z` 看实际标签；`restorecon -R <路径>`（adb root）重放规则；改 /data 标签要同步扩展存储（/mnt/expand）区的规则，否则 restorecon 会周期性把标签改回去；
4. **典型坑**：正则被更靠底（优先级更高）的宽规则截胡；跨分区合并后的命中顺序与单文件直觉不一致。

**Q5: 新增一个系统属性，SELinux 侧要改哪几处？**

四处：定义类型、加映射、授权写入方与读取方、过 neverallow。property_contexts 只负责"属性名 → 标签"映射，写权限在 .te 里用宏声明。

1. **映射格式**：`属性名 u:object_r:类型:s0 [exact|prefix]`——以点结尾即前缀匹配，尽量加 `exact`（拼错点号导致意外前缀命中是常见事故）；可附加类型约束（string/int/bool/enum…）；
2. **类型声明**：vendor 侧 `type vendor_x_prop, property_type;`，或用现成宏（system_internal_prop 等，自带配套 neverallow）；官方指引明确 core_property_type 对新属性过宽、不建议使用；
3. **授权宏**：写入方 `set_prop(域, vendor_x_prop)`（展开含 property_socket 连接 + property_service set + 读取）；读取方 `get_prop(域, vendor_x_prop)`——"setprop 被拒"的 audit2allow 输出若落到 property_service set，就应换回 set_prop 宏形态落盘；
4. **完整步骤**：定义类型 → property_contexts 加映射 → 写入方/读取方 .te 授权 → 编译过 neverallow。

**Q6: 新的 Binder/HIDL 服务注册被拒（avc denied { add }）——服务标签在哪些文件里声明？**

三类标签文件各管一类：`service_contexts` 管 Binder 服务（按服务名，AIDL 用"接口全名/实例"），`hwservice_contexts` 管 HIDL/AIDL HAL（按 `包::接口`，无版本号），`seapp_contexts` 管应用进程落域——配合域侧的 add/find 权限才完整。

1. **AAOS 实例**：`car_service u:object_r:carservice_service:s0` 配合 `add_service(carservice_app, carservice_service)`；HAL 侧 `hal_attribute_service(hal_x, hal_x_service)` 宏一次展开出服务端 add 与客户端 find；
2. **seapp 优先级**：isSystemServer → user → seinfo → name → isPrivApp 等多级依次判定——新应用没落自定义域时，逐级检查哪条既有规则先命中了；
3. **诊断口诀**：denied { add } → 服务域缺 add_service/add_hwservice；denied { find } → 客户端缺 find（挂 client 属性）；`ps -Z` 确认进程域；注意 `*` 兜底条目会把未匹配的服务吞进 default 标签。

**Q7: 想让三方应用直读自研驱动节点，neverallow 直接编译失败——正确的解法路径是什么？**

不要放开 app 域：平台侧建服务、把服务类型挂 `app_api_service` 属性，应用经 Binder 调服务；确需硬件访问就 HAL 化（`hal_attribute` + `hal_client_domain`）。app_neverallows 对全部 untrusted 域禁掉了应用数据目录执行、debugfs 读、注册服务、vndbinder 等一整组权限，这些禁令是平台安全模型的编译期保证，不可绕过。

1. **标准三步**：平台服务暴露能力（服务类型挂 app_api_service）→ 应用域与服务域之间 `binder_call` 授权 → 服务域内做受控的节点访问；
2. **例外评估**：确实需要直访硬件的，评估建专有 HAL 域，而不是修改 app 域约束；
3. **思路转换**：SELinux 问题的正解通常是"改访问路径"，不是"改策略放行"（与 Q1 的 neverallow 结论一致）。

**Q8: vendor sepolicy 改动"不生效"、同名 .te 被吞、升级平台后 avc 暴增——板级策略的组织与版本化要点是什么？**

vendor 策略经 `BOARD_VENDOR_SEPOLICY_DIRS` 目录列表拼接，**列表顺序决定同名文件的取舍**；平台与 vendor 的兼容靠 `prebuilts/api/<版本>/` 快照加 mapping 版本化文件支撑。

1. **拼接**：同名 .te 先列出的目录先拼入；产物 vendor_sepolicy.conf 可直接查看验证拼接结果——"改了没生效"先看这个；
2. **细节**：每个策略文件必须以换行符结尾（m4 拼接与报错定位依赖它）；构建宏经 BOARD_SEPOLICY_M4DEFS 传入；
3. **版本化**：新平台 + 旧 vendor 靠 mapping/compat 文件维持旧语义——升级后 avc 暴增，先查 compat 版本文件是否缺失或错版；
4. **归属纪律**：平台类型改动放 system/sepolicy 并经 public 暴露给 vendor，vendor 专属规则放自己的 sepolicy 目录——vendor 引用平台 private 类型会直接编译失败（见 Q3）；
5. **ODM**：`BOARD_ODM_SEPOLICY_DIRS` 与 vendor 变量并列（经 Soong config 传入构建），odm 策略在构建代码里独立收集标签；sepolicy README 未写 ODM 章节，权威出处是构建代码（build_files.go / versioned_policy.go）——vendor/odm 策略按 `BOARD_SEPOLICY_VERS` 选定平台版本参与兼容校验；
6. **快照一致性**：public/private 的接口改动要同步 `system/sepolicy/prebuilts/api/<版本>/` 快照——构建期校验两侧内容一致；快照用于生成 `plat_pub_versioned.cil`，让旧 vendor 按自己锁定的平台版本参与编译。

**Q9: service_manager find 被拒时，SELinux 为什么不抛异常而是把应用打崩？——"静默 null"链路与文件类拒绝的差异**

因为 `service_manager` 类的检查由 servicemanager 在用户态完成，拒绝的表现形式是"返回 null 而不报错"：`ServiceManager.getService` 拿到 null binder，AIDL 生成的 `asInterface(null)` 按约定返回 null 代理，framework 调用方不判空——下一次对该代理调方法就是 NPE，直接击穿 main 线程。文件类拒绝走内核 LSM，`open` 返回 EACCES 错误码，调用方通常有错误处理分支，所以表现为功能失效而非崩溃。

完整因果链（以真机案例"点击音效崩溃"为例）：

1. App 进程调 `getService("audio")`，事务经 binder 到 servicemanager；
2. servicemanager 的 `tryGetService` 先在服务注册表命中（AudioService 开机 20 秒就注册了），再做 `canFind` 检查——策略没有对应 allow，丢弃已到手的 binder，返回 null，事务回包仍是 Status::ok 加空 binder；
3. Java 层 `IAudioService.Stub.asInterface(null)` 返回 null，被写进 `AudioManager` 的静态字段 `sService`，本进程终生缓存；
4. 之后每次点击音效都对 null 调接口方法，NPE → FATAL → AMS 杀进程重启；新进程的 `sService` 与用户态 AVC 缓存都是空的，第一次查询再次被拒——重启即复崩，形成崩溃循环。

对比记忆：内核类拒绝是"系统调用失败 + 错误码"，userspace 类拒绝是"跨进程调用成功 + 返回值变 null"——后者的破坏力在于 Java framework 从设计上假定核心服务任何域都能查到，全部调用点没有判空防线。

**Q10: 从 ServiceManager.getService("audio") 到策略拒绝，binder 两侧完整经过了哪些代码？**

链路是：Java 侧查缓存未命中后经 bootstrap 代理发起 binder 调用，servicemanager 收到事务、恢复调用者身份、查表命中后做 SELinux 检查，拒绝时返回 null 且事务状态仍为 ok。逐跳展开：

1. `ServiceManager.getService` 先查 `sCache`（仅存启动期著名服务，audio 不在其中），未命中走 `rawGetService`；
2. `getIServiceManager` 经 `BinderInternal.getContextObject`（JNI）拿 `ProcessState` 的句柄 0 代理——句柄 0 是 binder 驱动协议里 context manager 的固定地址，唯一不经名字查询的对象，一切名字查询的起点；首次调用会打开 `/dev/binder` 并 mmap；
3. AIDL 生成的 Proxy 把服务名打包进 Parcel，`BinderProxy.transact` 进 JNI，libbinder 写事务后 `ioctl(BINDER_WRITE_READ)` 阻塞等回包；
4. servicemanager 收到 `BR_TRANSACTION_SEC_CTX`：驱动在每个事务附带调用者的 SELinux 上下文（servicemanager 注册时用了 `BINDER_SET_CONTEXT_MGR_EXT` 扩展 ioctl 请求的），服务端把它存进 `IPCThreadState.mCallingSid`；旧内核不支持该特性时回退 `getpidcon(pid)` 读 `/proc`；
5. AIDL 桩分发到 `ServiceManager::getService → tryGetService`：先查 `mNameToService` 注册表（命中即拿到 binder 指针），再做 `mAccess->canFind`，被拒则 `return nullptr`；
6. 回包 Status 恒为 ok，Java 层 `readStrongBinder()` 读出 null，全程无异常。

关键代码事实（AAOS13 `servicemanager/ServiceManager.cpp`）：查表命中发生在权限检查**之前**——"服务明明注册在、还是查不到"不是竞态，是检查顺序使然。

**Q11: tclass=service_manager 的 avc 拒绝为什么在 dmesg 里搜不到？两类 avc 日志各走哪条路径？**

因为 `service_manager` 是 userspace 类：`security_classes` 里它带 userspace 标记，访问检查不在内核 LSM 执行，而由对象管理器（servicemanager 进程）自己做——内核对这次拒绝毫无感知，自然不会产生内核审计记录。要看这条日志得用 `logcat -b events | grep auditd`。

两类 avc 的路径对照：

- **userspace 类**（service_manager、hwservice_manager 等）：对象管理器进程判定；libselinux 的 `avc_audit` 拼好消息后经日志回调以 tag 为 `auditd` 写进 logd events 缓冲。logcat 里打印该行的 pid 是 servicemanager 自己，不是被拒进程——真机上该 pid 是 555，`top` 可证实它就是 servicemanager 进程。
- **内核类**（file、property_service、tcp_socket 等）：内核 LSM 判定，审计记录带 `type=1400 audit(序列号):` 前缀写进 kmsg，logd 捞取后归因到被拒进程（tag 取其 comm）。

排查含义：抓日志只合成了主缓冲时，userspace 类拒绝会整类消失——"崩溃现场没有 avc"先怀疑缓冲不全或 dontaudit 静音，不要直接下"与 SELinux 无关"的结论。

**Q12: 一条 avc 日志里 pid、uid、name、scontext、tcontext、permissive 各字段分别由谁拼出？**

一条 avc 行由三层协作拼装：libselinux 的 `avc_audit` 生成模板与上下文段，对象管理器注册的业务回调填入 pid/uid/name，最终 logcat 行的 tag 和 pid 来自写入进程。以 `auditd : avc:  denied  { find } for pid=7283 uid=10093 name=audio scontext=u:r:nsr_appstore:s0 tcontext=u:object_r:audio_service:s0 tclass=service_manager permissive=0` 为例：

- `avc:  denied  { find }`：`avc_audit` 的模板串（双空格是模板自带）加 `avc_dump_av`——把被拒权限位图按类定义翻译回权限名；
- `pid=7283 uid=10093 name=audio`：servicemanager 在 `Access.cpp` 构造时注册的审计回调填的——pid/uid 取自 binder 事务附带的调用者身份，name 是 `canFind` 的入参。内核类 avc 的同类字段由内核自己拼，这是两类 avc 长得像但字段来源不同的原因；
- `scontext=` / `tcontext=`：`avc_dump_query` 把源和目标的 SID 反查回上下文字符串，tcontext 正是 `service_contexts` 里查出的服务标签；
- `tclass=service_manager`：对象管理器检查时硬编码的类名；
- `permissive=0`：当前 enforce 状态——`setenforce 0` 后同样的拒绝照记日志但放行（permissive=1），是调试利器；
- tag `auditd` 与行首 pid 555：来自 libselinux 的 `AUDITD_LOG_TAG` 常量与写入进程（servicemanager）自身。

修复时的用法：scontext、tcontext、tclass 加权限四段照抄就是一条 allow 规则，`allow nsr_appstore audio_service:service_manager find;`。

**Q13: SELinux 运行时会读 .te 或 service_contexts 文件吗？策略与映射分别何时进入内存？**

不会。运行时判定只查内存中的两份数据：内核内存里的 policydb（唯一权威）和 servicemanager 进程内的 service_contexts 查找句柄；源文件只在两个早期时点被读一次，之后就与判定无关。

1. **开机阶段**：二阶段 init 读取预编译 sepolicy（逐项哈希校验，不符则现场编译），写入 `/sys/fs/selinux/load` 装载进内核——此后所有 allow 与 deny 判定都查这份内存 policydb；所以改策略必须重新编译并重启才生效；
2. **servicemanager 首次 add/find 检查时**：`Access.cpp` 的 `getSehandle()` 惰性打开 service_contexts 编译产物，构建进程内查找句柄并缓存；策略热更新时 `selinux_status_updated` 会检测到并关闭旧句柄，下次查找按新策略重建；
3. **每次检查**：先查进程内 AVC 缓存（按"源域 + 目标标签 + 类"三元组键控），未命中才写 `/sys/fs/selinux/access` 向内核查询。同进程对同一服务的重复检查命中缓存、不再打印 avc，但拒绝判定照样生效；缓存随进程销亡，所以崩溃循环里每个新进程都会再打一条 avc。

"什么时候查看权限文件"的正确答案是：编译期编译进二进制、开机灌进内核、首次检查加载进 servicemanager，此后全部是微秒级内存操作。

**Q14: 普通应用域为什么天然能 find audio 服务？属性批量授权体系如何展开生效？**

因为 `audio_service` 类型声明时挂了 `app_api_service` 属性，而 `untrusted_app_all.te` 有一条按属性批量放行的规则 `allow untrusted_app_all app_api_service:service_manager find;`——编译期 checkpolicy 把属性规则展开成具体的 type×type 规则写进 policydb，运行时只查展开后的位图，"属性"这个概念在运行期不存在。

展开链按依赖顺序是四步：

1. **属性即类型集合**：`public/service.te` 里 `type audio_service, app_api_service, ...`，属性本体在 `public/attributes` 声明；`service_manager_type` 属性标记"可被 add 进 servicemanager 的类型"资格集；
2. **域入集合**：普通 App 域的 te 文件头部调用 `untrusted_app_domain()` 宏，宏体一行 `typeattribute $1 untrusted_app_all` 把域挂进集合；
3. **批量 allow**：`untrusted_app_all.te` 的那条 find 规则于是对集合全体生效，覆盖 audio、clipboard、autofill、window 等几十个挂了 `app_api_service` 的服务；
4. **厂商自建域缺环节**：自定义域不调用宏、也没有等价 allow，批量授权对它整批失效——audio/clipboard 等全部查不到。真机车机平台的厂商 App 域（nsr_appstore 这类自定义域）连环崩溃循环正是这个缺口，修法是补调宏或逐条写 allow。

对照样本：`platform_app`（地图类系统签名 App 的域）不在 untrusted_app_all 集合里，它在自己的 te 里写了同型的批量 allow——同一台设备上"地图查 audio 永远成功、商店一查就崩"的差异全部来自这两份文件的取舍。

**Q15: 作为 SELinux 开发者，修复一条 service_manager find 拒绝的完整流程是什么？**

标准流程六步：定位四元组、写规则进正确分区、过 neverallow 自检、静态验证、重启动态验证、用调试开关收尾。

1. **定位**：`ps -Z` 拿到调用者域；avc 行的 scontext、tcontext、tclass、权限就是格式化好的规则——`scontext=u:r:nsr_appstore... tcontext=u:object_r:audio_service:s0` 直接翻译成 `allow nsr_appstore audio_service:service_manager find;`；
2. **落点**：厂商域的规则放进厂商 sepolicy 目录，并在 `BoardConfig.mk` 的 `BOARD_VENDOR_SEPOLICY_DIRS` 登记路径；只能引用 platform public 类型（`service.te` 声明的 `audio_service` 等可直接用），引用平台 `private/` 类型会编译失败；
3. **neverallow 自检**：checkpolicy 编译期断言，规则与任何 neverallow 相交即整体编译失败——过不去时先怀疑设计而非删除断言；
4. **静态验证**：用 `sesearch -A -s <域> -t <类型> -c service_manager -p find` 确认规则已进编译产物；
5. **动态验证**：刷机后必须重启（内核 policydb 只在开机装载）；userdebug 构建可临时 `setenforce 0` 做对照实验，或给单域写 `permissive <域>;` 缩小放行面；
6. **调试收尾**：`auditallow` 会把"已放行的访问"也打日志，用于确认规则真实生效；验证通过后删除 permissive 与 auditallow 调试语句。

边界：`add` 权限是另一档安全等级——平台策略禁止一切 untrusted App 域对任何 service_manager_type 有 add 权，这不是缺陷而是设计；App 域的合理诉求几乎总是 find。

**Q16: 策略里确实发生了拒绝，avc 日志却一条都没有——dontaudit 的静音机制与排查方法是什么？**

`dontaudit` 在策略编译期把对应访问的 auditdeny 位清零：运行时拒绝照旧生效，但 libselinux 判定"该拒绝无需审计"直接跳过日志生成——dmesg 和 logcat 都不会有记录。遇到"行为明显被拒、日志里却找不到 avc"，第一怀疑对象就是它。

机制细节：内核对每次权限查询返回的裁决结构里，auditdeny 位图从全 1 起步（含义是"拒绝默认要记日志"）；`dontaudit` 规则编译成清位条目，把对应权限位 AND 成 0。用户态拒绝同样遵循：`avc_audit` 计算"被拒位 AND auditdeny"，结果为 0 就不调日志回调、不拼消息。

排查手段：

1. `sesearch --dontaudit -s <域>` 列出对目标域的所有静音规则，确认要找的访问是否在列；
2. 临时删除可疑的 dontaudit 规则重新编译，或改用 permissive 域配合 `auditallow` 观察完整访问流；
3. 排除缓冲因素：userspace 类拒绝只进 logd events 缓冲，普通 `logcat` 抓包不含它，需 `logcat -b events` 或 `dmesg`。

**Q17: neverallow 与运行时拒绝是什么关系？撞上 default_android_service 的 neverallow 该怎么解？**

`neverallow` 是编译期断言：checkpolicy 编译时发现任何 allow 规则与之相交，整个策略编译失败、镜像出不来；编译产物里不存在这条规则，运行时不参与任何判定。它的价值是把安全设计变成"编译不过就上不了机"，让违规策略在构建期而不是渗透测试时暴露。

典型场景是 `default_android_service` 兜底标签：`service_contexts` 末行的 `*` 通配把一切未映射服务名落到这个标签，而平台策略里有 `neverallow * default_android_service:service_manager *`——任何域都不许对兜底标签 add 或 find。这形成"注册即拒绝"的哨兵：厂商 HAL 服务若不建专属类型，注册方 add 与使用方 find 双双被拒，服务永远注册不上（真机案例：车机平台的 SOME/IP HAL 服务因此从未注册成功，依赖它的 RPC 通道从开机起无限重试）。正确解法是按官方口径三步走：新类型进 service.te、名字映射进 service_contexts、然后给服务方 add 权与使用方 find 权配对授权——而不是删除 neverallow。

**Q18: 厂商 App"点哪崩哪、重启即复崩"，如何一步步把根因定位到 SELinux 策略缺失？**

用排除法把时序、服务存活、系统组件逐一排除，再用跨进程对照与 avc 和崩溃的毫秒级对应收口；核心判据是"同一服务、同一时刻、不同域、结果相反"。真机案例（某车机平台，开机后多个厂商 App 连环崩溃）的推演步骤：

1. **排除时序竞态**：设备 uptime 两小时以上，且进程重启后数秒内复崩——不是"服务没起完"也不是进程内坏缓存；
2. **排除服务未注册**：SystemServer 日志显示 AudioService 开机 20 秒即启动，audioserver 进程存活且在混音。注意 native 守护进程活着不等于 Java 层 "audio" 服务对该域可查；
3. **排除系统组件故障**：system_server 与 watchdog 正常，AMS 还能正常重启崩溃应用；
4. **跨域对照**：launcher 和 SystemUI 点击正常、平台签名的地图应用正常、厂商自签名的多个 App 必崩——同一时刻同一服务的唯一变量是调用方的 SELinux 域；
5. **实锤对应**：`logcat -b events` 里每条 FATAL 前毫秒级都有一条 `service_manager find` denied，denial 的 pid 与崩溃 pid 一一对应（真机 10 次崩溃 10 条拒绝全对应）；
6. **闭环验证**：补齐 allow 规则刷机重启，denied 与崩溃同时消失。

迁移判断：framework 层 NPE 消息形如 "Attempt to invoke interface method ... on a null object reference" 且对象是 `IAudioService`、`IClipboardManager`、`IActivityTaskManager` 这类系统服务接口时，先查该进程域的 service_manager find 权限，再怀疑应用代码；clipboard 路径还要注意无障碍节点预取会自发触发剪贴板查询，应用不点击也会崩。

**Q19: Android SELinux 是什么？怎么理解？**

SELinux 是实现在内核里的强制访问控制（MAC）层：给每个进程打"域"标签、给每个文件/服务/属性等对象打"类型"标签，所有访问必须命中一条 `allow 源域 目标类型:类 权限;` 规则，否则一律拒绝——默认拒绝是它与 UID 权限（DAC）最本质的差异。Android 自 4.3 引入、5.0 起全局 enforcing，CTS 强制校验 enforcing 且禁止修改原生策略。

三句话建立理解框架：

1. **一切皆标签**：进程上下文形如 `u:r:untrusted_app:s0`（用户：角色：域：级别，应用域还带 MCS 类别实现互隔离），对象的上下文形如 `u:object_r:audio_service:s0`；
2. **白名单式授权**：策略里只有 allow（放行）、dontaudit（拒绝但静音日志）、neverallow（编译期断言）等少数规则形态，不存在运行时的"deny 规则"——拒绝是"没有规则"的默认态；
3. **与 DAC 叠加而非替代**：root 与 system uid 进程同样受 MAC 约束，它是应用沙箱的第三层（UID 隔离、SELinux、seccomp 各管一层）。

价值取向：把越权访问从"运行时漏洞"变成"编译期失败或 avc 日志可见的失败"；厂商扩展不碰 `system/sepolicy` 原生语义，走自己的 sepolicy 目录叠加。



**Q20: Android SELinux 原理？以及代码实现？**

原理可以拆成四层，每层都有明确的代码落点：

1. **LSM 钩子层**：内核在每个敏感操作执行前调用安全钩子；SELinux 作为 LSM 的一个实现，先查内核内存里的访问向量缓存（AVC），未命中再查已装载的策略库（security server / policydb），拒绝时产生 avc 审计记录。内核实现位于 `kernel/security/selinux/`；
2. **策略编译与装载**：`.te` 规则与各类 contexts 源文件经 m4 宏展开、checkpolicy/secilc 编译成二进制策略；init 在开机早期做哈希校验后写 `/sys/fs/selinux/load` 装载进内核，之后所有判定只查内存。装载与模式决策代码在 `system/core/init/selinux.cpp`；
3. **两类检查位置**：file、property_service 等内核对象走 LSM 钩子；`service_manager`、`hwservice_manager` 是 userspace 类，由对象管理器（servicemanager 等）在用户态用 libselinux 查同一份策略自行判定——这类拒绝的日志也由对象管理器进程自己打；
4. **默认拒绝 + neverallow**：运行时没有 allow 即拒绝；neverallow 是编译期断言，保证平台安全设计不被后续 allow 破坏。

代码落点清单：

- 策略源码：`system/sepolicy/`（public 是 vendor 可见接口层，private 平台自用，vendor/device 板级扩展）；
- 用户态库与工具链：`external/selinux/`（libselinux、checkpolicy、audit2allow）；
- 对象管理器样例：`frameworks/native/cmds/servicemanager/Access.cpp`——`service_manager` 类 add/find/list 的检查点；
- 编译产物装载路径：`/vendor/etc/selinux/precompiled_sepolicy`（哈希校验后经 selinuxfs 进内核）。

**Q21: 作为上层应用开发者，SELinux的实际应用场景都是什么？**

应用开发者通常"被 SELinux 约束"而不是配置它：你的域由签名与 targetSdkVersion 决定，能做的是识别拒绝、改走合规通道，改 sepolicy 不是应用侧的选项。

三个域等级（按签名与权限递增）：

1. **untrusted_app**：无平台签名的第三方应用，最小权限；targetSdkVersion 不超过 25 的应用落 `untrusted_app_25` 兼容域（规则集更宽松以保兼容）；
2. **platform_app**：有 Android 平台签名、无 system uid——内置但非核心的应用；
3. **system_app**：平台签名加 system uid——核心系统应用，能力最强。

实际会遇到的四类场景与动作：

1. **功能静默失效**（读不到 `/sys` 节点、打不开设备、共享文件失败且无异常）——先抓 avc 确认是否 SELinux 拒绝；
2. **跨应用共享数据失败**——应用数据目录 `app_data_file` 带类别隔离是设计，正解是 ContentProvider 或 Binder 通道；
3. **访问被 neverallow 挡死**——平台对 untrusted 域的约束是编译期保证，应用改不了，评估换公开 API 或由系统侧开受控服务；
4. **需要新能力**——由平台团队把能力做成服务并把服务类型挂 `app_api_service` 属性，应用经 Binder 调用，而不是申请直访硬件或节点。

应用侧可用的动作只有三个：`ps -Z` 看自己落在哪个域、`logcat -b all` 抓 avc 定位拒绝、把合理需求提给系统组。



**Q22: 作为framework开发者，SELinux的实际应用场景都是什么？**

framework/系统开发者的日常是"新增进程、服务、属性、节点之后把域和权限配齐"——五个高频情景各有一套固定的文件组合，另外要掌握编译处理与快速验证两门手艺。

1. **init 启动的原生服务**：新建 te 三件套——`type demo, domain;`、`type demo_exec, exec_type, file_type;`、`init_daemon_domain(demo)`（宏体就是 `domain_auto_trans(init, demo_exec, demo)`，init 执行该文件时自动把新进程切到 demo 域）；再在 file_contexts 把执行文件路径绑到 `demo_exec`。漏配域时 init 会打 "Service xxx needs a SELinux domain defined" 警告；
2. **注册 Binder 服务**：service.te 定义 `type xxx_service, service_manager_type;`、service_contexts 加"服务名 → 标签"映射、服务域配 `binder_use/binder_service` 宏与 `service_manager add` 权、客户端域配 `find` 权——漏配 add 拒注册，漏配 find 客户端拿到 null；
3. **新增系统属性**：property.te 定义 `property_type` 类型、property_contexts 加映射（尽量 `exact`）、写入方 `set_prop(域, 类型)`、读取方 `get_prop(域, 类型)`；
4. **访问设备节点**：device.te 定义 `dev_type` 类型、file_contexts 绑定路径、域内对具体类型 allow 读写权限——不要对通用 `device` 标签授权，那会被 neverallow 挡住，正解是建专属类型；
5. **HAL 权限**：HAL 域（`hal_xxx_default`）原生策略已定义，定制部分在厂商 sepolicy 补 allow；file_contexts 在厂商目录绑定定制 HAL 的路径以覆盖原生条目。

配套两门手艺：编译期撞 neverallow 时按最小权限原则换更具体的类型或换执行方式（如 HAL 用 `domain_auto_trans` 替代 `execute_no_trans`）；改完用 `make selinux_policy` 快编、audit2allow 批量生成候选规则、remount 推送重启验证。

**Q23: ioctl 权限加了 allow 还是被拒（avc 里 ioctlcmd=0x...）——SELinux 对 ioctl 的双层授权怎么补？**

因为 ioctl 在 SELinux 里是"类权限位 + 命令白名单"双层控制：`allow` 只放行 ioctl 这个权限位，具体命令号还要 `allowxperm` 白名单放行——只加前者的典型现象是 avc 里 `ioctlcmd=0x127c` 这类条目反复出现、策略看起来"没生效"。

机制：内核对 ioctl 调用做两步检查——先过类的 ioctl 权限位，再核对本次命令号是否在该对（源域，目标类型）的 allowxperm 集合内；被拒时日志带 `ioctlcmd=` 十六进制命令号，这就是要补的白名单项。

修法三步（以 `/dev/block/xvdb` 上 `ioctlcmd=0x127c` 为例）：

1. 拿命令号到 `system/sepolicy/public/ioctl_defines` 找宏——0x127c 对应 `BLKDISCARDZEROES`；
2. `allow resize2fs xvd_block_device:blk_file ioctl;` 放行权限位；
3. `allowxperm resize2fs xvd_block_device:blk_file ioctl { BLKDISCARDZEROES };` 放行具体命令。

边界：每条 ioctl 命令都要单独白名单；"放行全部命令号"的写法在代码评审中通常被拒——白名单本身就是要审的安全边界。

**Q24: 新增设备节点或 HAL 执行外部程序撞 neverallow 编译失败——两大典型场景与最小权限原则的解法是什么？**

编译期 neverallow 报错几乎都出自两类场景：对通用标签授权、HAL 域要 execute_no_trans——两者的正解都不是删断言，而是"换更具体的类型"或"换执行方式"。

场景一，通用设备标签：新增节点 `/dev/vircan` 没建专属类型时落在通用 `device:chr_file` 上，照 avc 补 `allow hal_xxx device:chr_file { open read write };` 会撞上 domain.te 的 `neverallow domain device:chr_file { open read write };`——注释原文就写明"Do not allow raw read/write/open access to generic devices, rather force a relabel to a more specific type"。正解三步：device.te 建 `type vircan_device, dev_type;`、file_contexts 绑定 `/dev/vircan`、allow 改为对 `vircan_device` 授权。

场景二，HAL 的 execute_no_trans：HAL 进程用 execv 系列、fork 执行 `/vendor/bin/sh` 等外部程序时以"不换域"方式执行，需要 `execute_no_trans` 权——但 hal_neverallows.te 明文 `neverallow { halserverdomain ... } {file_type fs_type}:file execute_no_trans;`（仅 hal_dumpstate、hal_telephony_server 豁免），原因是 HAL 域的能力是多个 HAL 的并集，禁止跨安全边界原地执行任意程序。正解：`domain_auto_trans(hal_gnss_unicore, vendor_shell_exec, vendor_shell)`——执行瞬间自动域转换到目标域，既完成执行又保住隔离。

最小权限原则（平台评审口径）：不得直接对 `socket_device`、`device`、`block_device`、`default_service`、`system_data_file`、`tmpfs` 这类通用标签授权——一律为对象建更具体的类型。收束判断：neverallow 报错行号指向的是设计意图，先读懂断言注释再动手，删断言永远不是选项。

**Q25: SELinux 三种工作模式怎么切换？发布版为什么必须 Enforcing？调试用的宽容模式有哪几种开法？**

三种模式是 Enforcing（违规动作被拒绝）、Permissive（只告警不拒绝）、Disabled（内核未使能 SELinux）；发布版本必须 Enforcing——CTS 有 testSELinuxEnforcing 校验项，且禁止对原生策略做修改。

启用与模式决策链（已按 AAOS13 源码核对）：

1. 内核配置 `CONFIG_SECURITY_SELINUX=y` 使能框架；
2. init 决策：读内核 cmdline 或 bootconfig 的 `androidboot.selinux`，值为 permissive 则宽容，否则 enforcing（`system/core/init/selinux.cpp` 里对 `androidboot.selinux` 的显式解析）；
3. 编译开关 `ALLOW_PERMISSIVE_SELINUX`：init 构建时以 `-D` 宏注入（Android.bp 中 user 版本为 0），为 0 时发布形态直接禁用宽容能力——注意资料里常见的 `DALLOW_PERMISSIVE_SELINUX` 是笔误。

调试期的三种宽容开法，按侵入性从小到大：

1. 串口或 adb 临时切换：`setenforce 0`（等价 `setenforce Permissive`），`getenforce` 查看——重启失效，适合单轮复现；
2. 持久宽容：`BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive`——每次开机都宽容，仅限调试构建；
3. 单域放宽：te 里写 `permissive <域>;`——只有目标域不拦截，全局其余域保持 enforcing，暴露面最小。

边界：user build 无法 setenforce 0；宽容模式改变的只是"是否拦截"，avc 日志照记且 `permissive=1` 标注——从宽容日志收敛出的 allow 规则与 enforcing 下等价。

**Q26: 策略改完如何快速编译与验证？audit2allow 的正确用法是什么？**

快速迭代链是：`make selinux_policy` 只编策略、确认产物落在正确分区、adb remount 推送重启验证、audit2allow 批量生成候选规则再人工审读。

1. **编译**：`make selinux_policy -j8`——system/sepolicy 的 Android.mk 里名为 `selinux_policy` 的模块，不必整编镜像；
2. **产物分区**：`out/target/product/<device>/system/etc/selinux/` 是平台策略（改它会动 CTS 基线，厂商改动不要落这里）；`out/target/product/<device>/vendor/etc/selinux/` 是厂商策略——`BOARD_VENDOR_SEPOLICY_DIRS` 各目录拼接的产物（vendor_sepolicy.cil 可直接 grep 确认 allow 已进产物）；
3. **快速验证**：`adb root && adb remount && adb push out/.../vendor/etc/selinux /vendor/etc` 后重启——策略只在开机装载，重启后按原场景复测 avc 是否消失，缺权限就继续补、反复迭代直到无 denied；
4. **audit2allow**：`external/selinux/prebuilts/bin/audit2allow -i avc.log -p out/target/product/<device>/root/sepolicy`——`-i` 喂 denial 日志，`-p` 带上设备现有策略让工具排除已放行的规则；输出按域分组、每组给出候选 allow。它是候选不是终稿：必须理解每条"为什么需要"、剔除设计性拒绝后再落盘。

注意：日志里的 "avc: granted" 不是错误——那是 auditallow 或宽容模式下放行决策的留痕，排查 denied 时可以直接忽略。


