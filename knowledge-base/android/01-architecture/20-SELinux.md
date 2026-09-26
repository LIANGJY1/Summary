# Android SELinux

> 学习资料（文章模式沉淀）。主线：SELinux 拒绝（avc denial）的确认与处置——从"功能静默失效"到四元组定位、audit2allow 与 neverallow 的处理边界。语境：全局 enforcing 自 Android 5.0 起；启动期策略合成与装载见 [02-Android系统启动流程.md](./02-Android系统启动流程.md)；沙箱三层（UID/SELinux/seccomp）见 [04-Sanbox.md](./04-Sanbox.md)。命令与流程已于 2026-09-25 与官方资料（source.android.com《Validate SELinux》）核对；Q3–Q8 的策略书写机制按 system/sepolicy 与 car_product/sepolicy 源码核对。Q 序列即结构，供 atlas 同源直读。

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
5. **ODM**：`BOARD_ODM_SEPOLICY_DIRS` 与 vendor 变量并列（经 Soong config 传入构建），odm 策略在构建代码里独立收集标签；sepolicy README 未写 ODM 章节，权威出处是构建代码（build_files.go / versioned_policy.go）——vendor/odm 策略按 `BOARD_SEPOLICY_VERS` 选定平台版本参与兼容校验。
