# Android SELinux

> 学习资料（文章模式沉淀）。主线：SELinux 拒绝（avc denial）的确认与处置——从"功能静默失效"到四元组定位、audit2allow 与 neverallow 的处理边界、service_manager 用户态检查链、ioctl allowxperm 授权与工作模式验证，覆盖应用与 framework 两级开发者场景。语境：全局 enforcing 自 Android 5.0 起；启动期策略合成与装载见 [02-system-boot.md](02-system-boot.md)；沙箱三层（UID/SELinux/seccomp）见 [09-app-sandbox.md](09-app-sandbox.md)。命令、策略书写与案例已按 AAOS13_study（Android 13）`system/sepolicy` 与 `car_product/sepolicy` 源码及官方文档（source.android.com《Validate SELinux》）核对，并遵守 [../../WRITING-GUIDE.md](../../WRITING-GUIDE.md)。Q 序列即结构，供 atlas 同源直读。



**Q1: [done] [tags:SELinux] Android SELinux 是什么？它补上了 Android 哪类访问控制缺口？**

SELinux（Security-Enhanced Linux，安全增强型 Linux）是 Android 基于 Linux LSM（Linux Security Modules）实现的**强制访问控制**机制。它给进程主体标记域、给受保护对象标记类型，再由内核或相应的用户态对象管理器按 `allow 域 类型:类 权限` 规则检查访问；没有匹配授权时默认拒绝。

它要解决的是既有两层隔离的盲区：

1. **传统 DAC**：Linux 文件访问主要依据进程 UID/GID、对象属主/属组和权限位等凭据。Android 通常用每应用 UID 隔离应用数据，但采用同一 UID 的进程或拥有相同 DAC 凭据的主体无法仅凭权限位进一步区分；SELinux 域可增加这一层区分。
2. **Android 框架权限**：`android.permission.*` 等权限通常由框架服务按调用者身份检查，约束特定 API；直接系统调用仍需由内核侧访问控制保护，框架权限本身不替代这层检查。

SELinux 用独立于 UID/GID 的域与类型标签细化访问判定；内核对象由 LSM 执行检查，Binder 服务、属性等 userspace 类由对应对象管理器执行检查。

版本与价值：Android 4.3 引入，Android 5.0 起默认要求设备全局 enforcing。CTS/VTS 会检查设备的 SELinux 状态与策略约束，但 OEM 仍需为设备扩展策略；不能把要求理解成禁止所有原生策略扩展。策略违反 neverallow 会在构建期失败，运行期未获授权的访问则被拒绝并可能留下 AVC 审计记录。

**Q2: [done] SELinux 的判定机制分哪几步？一次访问是怎么被放行或拒绝的？**

判定机制分三步——建立安全上下文、编译并装载规则、逐次判定：策略在构建与启动阶段准备，进程和对象在创建、标记或查询映射时取得上下文；内核或用户态对象管理器再按访问请求执行判定，没有匹配授权即拒绝。

1. **打标签**：进程标"域"，对象标"类型"。普通第三方应用由 `seapp_contexts` 按 uid 与 targetSdkVersion 等匹配条件选定域，应用数据目录由 `file_contexts` 按路径匹配类型；标签的载体是形如 `u:r:untrusted_app:s0` 的安全上下文。
2. **写规则**：策略句式是 `allow 源域 目标类型:类 权限;`。"类"是被访问资源的种类（如 file、property_service、service_manager），决定权限名的取值空间。规则按标签与访问类别授权，不按路径或 uid 授权。
3. **判定**：每次受保护访问都会按"源域 + 目标类型 + 类 + 操作"查策略。存在匹配 allow 才放行，未获授权即拒绝；属主和 root 也不能自行放宽，这是强制访问控制与自主访问控制的区别。

以一次访问走查全流程：普通应用尝试读取另一应用的私有目录时，内核按进程域、目标目录类型、对象类与 read 权限查询策略；没有匹配授权就拒绝，并在允许审计时记录 AVC。读取自己的目录可能因其 MCS 类别不同而获准。路径与 UID 会参与对象标签或进程域的产生，访问检查本身则使用最终安全上下文和策略裁决。

**Q3: Android SELinux 原理？以及代码实现？**

原理可以拆成四层，每层都有明确的代码落点：

1. **LSM 钩子层**：内核在每个敏感操作执行前调用安全钩子；SELinux 作为 LSM 的一个实现，先查内核内存里的访问向量缓存（AVC），未命中再查已装载的策略库（security server / policydb），拒绝时产生 avc 审计记录。内核实现位于 `kernel/security/selinux/`；
2. **策略编译与装载**：`.te` 规则经 m4 宏展开和 checkpolicy/secilc 编译成二进制策略；部分 contexts 输入参与策略编译，file/service/property 等标签映射还会生成供相应对象管理器查询的独立产物。init 在开机早期校验并将策略写入 `/sys/fs/selinux/load`，之后访问判定使用已装载的数据。装载与模式决策代码在 `system/core/init/selinux.cpp`；
3. **两类检查位置**：file、socket 等内核对象走 LSM 钩子；`property_service`、`service_manager`、`hwservice_manager` 是 userspace 类，由 init 属性服务、servicemanager 或 hwservicemanager 等对象管理器在用户态调用 libselinux 检查同一份策略。审计记录由执行检查的进程及其日志回调产生；
4. **neverallow 编译期断言**：违反平台安全约束的 allow 会使正常策略构建失败；它与运行期默认拒绝分别在构建期和访问期发挥作用。忽略 neverallow 的调试选项不能作为发布策略的解决方案。

实现各环节对应的源码与输入如下：

1. **策略源码与标签映射**：`system/sepolicy/` 存放 `.te` 规则以及各类 contexts 输入；public 是 vendor 可见接口层，private 是平台自用层，vendor/device 目录承载设备扩展。

    1. `file_contexts`：按文件系统路径标记普通文件和设备节点。
    2. `genfs_contexts`：标记 proc、sys 等伪文件系统路径，例如为 `/proc/asound` 配置 genfs 标签。
    3. `property_contexts`：把系统属性名映射到属性类型。
    4. `service_contexts` 与 `hwservice_contexts`：分别映射 servicemanager 管理的服务和 hwservicemanager 管理的 HIDL 服务。
    5. `seapp_contexts`：按应用身份匹配进程域与应用数据标签。
    6. `initial_sid_contexts`：声明 SELinux 启动时使用的初始安全标识。
2. **用户态库与工具链**：`external/selinux/` 包含 libselinux、checkpolicy 和 audit2allow 等组件。
3. **对象管理器检查**：`frameworks/native/cmds/servicemanager/Access.cpp` 展示 `service_manager` 类 add/find/list 检查；其他对象管理器各自执行对应 userspace 类检查。
4. **编译产物装载**：分区中的策略源或预编译产物由 init 选择，最终经 selinuxfs 装入内核；具体产物路径依 Android 分支和设备配置而定。

**Q4: [done] 作为上层应用开发者，SELinux 的实际应用场景都是什么？**

应用开发者通常"被 SELinux 约束"而不是配置它：能做的是识别拒绝、改走合规通道，改 sepolicy 不是应用侧的选项。应用无法自行选择域；平台按 seapp_contexts 中的匹配条件确定域，输入不止签名或 targetSdkVersion。

1. **签名与 seinfo**：签名映射得到的 seinfo 可用于区分平台应用与普通应用。
2. **UID 与用户身份**：system、普通应用、isolated 等身份会影响可匹配规则。
3. **包名与安装属性**：包名、是否 privileged 等字段可用于更具体的规则。
4. **targetSdkVersion**：部分规则按目标 API 版本区分兼容代际；匹配字段和优先级以设备所用 seapp_contexts 为准。

这些条件按设备策略中的匹配优先级经 `seapp_contexts` 选出域，在 Zygote specialize 阶段落定。Android 13 常见规则优先级依次考虑 `isSystemServer`、`user`、`seinfo`、`name`、`isPrivApp` 等字段；后续版本增加或调整过选择条件，排查自定义域时应核对目标设备的完整规则。

常见应用域由平台规则匹配，不能简化成固定的签名等级：

1. **untrusted_app**：普通非平台应用使用的域族；Android 13 策略中可见 `untrusted_app_30`、`untrusted_app_29`、`untrusted_app_27`、`untrusted_app_25` 等代际域，匹配旧 targetSdkVersion 的应用以兼容规则进入相应域。具体名称和回退规则随版本变化。
2. **platform_app**：符合平台签名等 seapp_contexts 条件的应用域，不能仅凭"预装"判断。
3. **system_app**：满足系统应用匹配条件的应用域；实际判定仍以设备规则中的 UID、seinfo、包名及 privilege 条件为准。

实际会遇到的四类场景与动作：

1. **功能静默失效**（读不到 `/sys` 节点、打不开设备、共享文件失败且无异常）——先抓 avc 确认是否 SELinux 拒绝；
2. **跨应用共享数据失败**——应用数据目录 `app_data_file` 带类别隔离是设计，正解是 ContentProvider 或 Binder 通道；
3. **访问被 neverallow 挡死**——平台对 untrusted 域的约束是编译期保证，应用改不了，评估换公开 API 或由系统侧开受控服务；
4. **需要新能力**——由平台团队把能力做成服务并把服务类型挂 `app_api_service` 属性，应用经 Binder 调用，而不是申请直访硬件或节点。

应用侧可采取三类动作：

1. 运行 `ps -Z` 查看进程当前域。
2. 使用 `logcat -b all` 收集 AVC 记录，再按拒绝字段定位访问。
3. 将确有必要的能力需求提交给平台团队，由系统侧评估合规接口。

**Q5: [learning] 作为 framework 开发者，SELinux 的实际应用场景都是什么？**

framework/系统开发者的常见工作是新增进程、服务、属性、设备节点或 HAL 时，把对应域、标签映射和最小权限配置齐。

1. **init 启动的原生服务**：声明 `type demo, domain;` 建立服务域，声明 `type demo_exec, exec_type, file_type;` 标识可执行文件类型，再用 `init_daemon_domain(demo)` 配置 init 执行该文件时的域转换。file_contexts 还需把实际可执行文件路径标为 `demo_exec`。这些名字必须与 init 服务配置和文件路径一致。缺少域配置时，init 会提示该服务没有 SELinux domain。
2. **注册 Binder 服务**：服务注册需要类型、服务名映射、服务端注册授权和客户端查找/调用授权。

    1. 在 service.te 声明 `type xxx_service, service_manager_type;`。`xxx_service` 是服务标签类型，`service_manager_type` 标记它可用于 servicemanager。
    2. 在 service_contexts 把服务名映射到该标签。
    3. 服务端通过 `binder_use(服务域)` 与 servicemanager 通信，并以 `add_service(服务域, 服务类型)` 获得注册权限。
    4. Android 13 的 `binder_service(服务域)` 宏用于标记 `binderservicedomain`。是否采用需核对目标分支宏定义。
    5. 客户端需要 find 权限才能取得句柄。实际 Binder 调用还需 `binder_call(客户端域, 服务端域)`。
    6. 缺少 add 会导致注册失败，缺少 find 会使客户端无法取得句柄。
3. **新增系统属性**：属性新增涉及类型声明、属性名映射和读写授权。

    1. 在 property.te 定义属性类型，例如 `type vendor_x_prop, property_type;`。
    2. 在 property_contexts 建立属性名到该类型的映射。
    3. 写入域用 `set_prop(域, vendor_x_prop)` 授权，读取域用 `get_prop(域, vendor_x_prop)` 授权。参数分别标识授权主体与属性标签，权限范围以宏展开为准。
4. **访问设备节点**：在 device.te 定义专属类型，例如 `type xxx_device, dev_type;`，其中 `dev_type` 将其归入设备节点类型集合。在 file_contexts 把设备路径映射到该类型，再给服务域授予所需的最小读写权限。不要把通用 `device` 类型作为放行目标。
5. **HAL 权限**：复用平台已定义的 HAL 域和属性关系，例如 `hal_xxx_default`；厂商自有节点或服务需补设备侧策略，并在 vendor file_contexts 将自定义 HAL 执行文件标为对应 exec 类型。是否能覆盖平台条目取决于分区边界和合并规则，不能只凭同名路径推断。

**Q6: [done] 一条 avc 日志里 pid、uid、name、scontext、tcontext、permissive 各字段分别由谁拼出？**

每个字段的拼装者分三层：libselinux 的 `avc_audit` 拼模板与上下文段，servicemanager 注册的审计回调拼业务字段，logcat 行首的 tag 与 pid 来自写入进程自身。

以 `auditd : avc:  denied  { find } for pid=7283 uid=10093 name=audio scontext=u:r:nsr_appstore:s0 tcontext=u:object_r:audio_service:s0 tclass=service_manager permissive=0` 为例，按日志阅读顺序逐段拆：

1. `avc: denied { find }`：`avc_audit` 按固定模板生成拒绝消息，模板中的空格是格式而不是额外字段；`avc_dump_av` 将权限位图转换成权限名。`find` 是 `service_manager` 类的服务查找权限，其他类有各自的权限名。
2. `pid=7283 uid=10093 name=audio`：本例中 servicemanager 的审计回调从 Binder 调用者身份取得 pid/uid，`name` 是待查的服务名。内核类 AVC 的字段由内核审计路径生成，因此字段来源不能一概而论。
3. `scontext` 与 `tcontext`：`avc_dump_query` 将源、目标 SID 转回安全上下文。本例源是 `nsr_appstore` 域，目标类型是 `audio_service`；服务标签来自 service_contexts，文件标签来自 file_contexts。
4. `tclass=service_manager`：表示访问目标是服务注册表中的条目，不能据此推断实际服务进程的域。
5. `permissive=0`：表示本次拒绝处于强制模式；宽容模式下同类访问仍可记录，但会标记 `permissive=1` 并放行。
6. 行首 `auditd` 与日志 pid：userspace AVC 的 tag 和日志行 pid 描述写日志的进程；本例 pid 555 是 servicemanager，而被拒调用方是 `pid=7283`。

修复时的用法：scontext 的域、tcontext 的类型、tclass、被拒权限四段照抄就是一条 allow 规则，`allow nsr_appstore audio_service:service_manager find;`。

**Q7: [learning] 读不到 /sys 节点、打不开 /dev 设备或跨进程访问失败时，怎样确认 SELinux 拒绝并选择正确处理方式？**

先按拒绝发生的位置检查对应日志。以下命令适用于已连接设备；访问内核日志通常需要 root 或可读权限：

```sh
adb logcat -b all | grep -i avc
adb logcat -b events | grep auditd
adb shell dmesg | grep -i avc
```

`adb` 把命令发送到设备。`logcat -b all` 读取全部 logcat 缓冲区，`-b events` 只读取 events 缓冲区。`grep -i avc` 忽略大小写筛选包含 `avc` 的行，`dmesg` 读取内核消息。servicemanager 拒绝优先查 events，内核对象拒绝优先查内核日志，属性服务等其他用户态拒绝还要看执行检查的进程日志。没有记录不能单独证明没有 SELinux 拒绝，因为缓冲区、`dontaudit` 和审计配置都会影响可见性。

1. **读拒绝字段**：`scontext` 是请求进程的安全上下文，`tcontext` 是目标对象的安全上下文，`tclass` 是对象类别，花括号中的权限名是被拒操作。这些字段确定需要调查的主体、客体和操作。上下文的 `user:role:type:level` 中，user 是 SELinux 用户标识（常见值为 `u`），role 是角色字段（进程常见 `r`，对象常见 `object_r`），type 对进程表示域、对客体表示类型，level 可含有用于应用隔离的 MCS 类别。Android 常见策略主要按 type 与 level 等条件授权，不能把 SELinux user 直接等同于 Linux UID。
2. **识别预期隔离**：应用数据目录以 MLS category 隔离不同应用，因此应用互访通常会被拒，这是沙箱的一部分。
3. **识别受限接口**：应用域对 `/sys`、`/proc` 多数节点默认无权限；需要这些信息时优先使用公开 API，而不是直接访问节点。
4. **审查候选规则**：`audit2allow` 只能根据 denial 生成候选 allow。应先理解访问需求，再把必要规则写入对应分区的 `.te` 文件。
5. **处理编译期断言**：撞到 `neverallow` 表示当前设计与平台安全约束冲突；改用公开 API 或受控服务，不要删除断言。
6. **确认调试环境**：user build 不能切换到 permissive，排障通常需要 userdebug 或 eng 构建。策略改动后要重编对应分区镜像，或在可调试构建中验证。

**Q8: OTA 后首次启动因 SELinux 策略编译变慢，怎样判断预编译策略校验为何失配？**

OTA 后首次启动变慢，常见原因是 init 发现预编译策略的分区哈希与当前镜像不一致，转而现场编译策略；应先用日志与哈希文件确认这条路径，再检查相关分区是否来自匹配的构建版本。

1. **正常路径**：Android 16 的 init 将 system、system_ext、product 各自的策略哈希与 vendor 或 odm 预编译策略旁的对应哈希逐项比对。全部匹配才直接装载预编译产物，避免启动时重新编译。其他分支可能增加 APEX 等参与项。
2. **失配路径**：哈希不一致时，init 需要现场合成并编译策略，可能拉长首次启动；这是兼容性保护路径，不应为了缩短启动而跳过校验。
3. **排查顺序**：先查 init 日志中策略编译相关记录，再核对参与分区的哈希文件和 vendor/odm 预编译产物是否来自兼容构建。

    1. Android 16 的平台侧哈希文件包括 `/system/etc/selinux/plat_sepolicy_and_mapping.sha256`、`/system_ext/etc/selinux/system_ext_sepolicy_and_mapping.sha256` 和 `/product/etc/selinux/product_sepolicy_and_mapping.sha256`。预编译策略旁保存对应哈希副本。
    2. `adb logcat -b all | grep -i sepolicy` 从全部 logcat 缓冲筛选策略相关记录；`adb shell dmesg | grep -i sepolicy` 从内核消息筛选。`grep -i` 表示忽略大小写。
    3. 具体参与项和路径以设备所用 Android 分支的 init 源码为准。

**Q9: allow 规则到底该写在哪个文件？为什么在 public/*.te 里加规则会被编译拒绝？**

sepolicy 目录按接口可见性分层：`public` 暴露供 vendor 策略引用的平台类型、attribute 与宏，`private` 保存平台内部实现规则。`public` 下并非所有 `.te` 都禁止规则；例如 Android 13 的 `public/app.te` 明确禁止在该文件新增 allow、neverallow 或 dontaudit。厂商规则应写入厂商自己的策略目录，并只引用平台 public 接口与自有类型。

1. **attribute 扩展机制**：自定义服务可用合适的 attribute 统一表达服务范围；Android 13 策略中的 `app_api_service` 用于对普通应用开放的服务类型，isolated app 是否可访问仍取决于具体规则。HAL 用 `hal_attribute(hal_x)` 声明 HAL、客户端和服务端属性，再用 `hal_client_domain(域, hal_x)` 关联客户端域。
2. **AAOS 实例**：carservice 策略组合 `app_domain(carservice_app)`、`add_service(carservice_app, carservice_service)`，并将 carservice 域关联到它调用的 vehicle、audiocontrol、evs 等 HAL 客户端属性。它展示按职责组合宏的方式，实际权限仍以目标分支策略为准。
3. **诊断**：neverallow 报错的行号回溯规则所在 .te；不确定宏展开结果时用 m4 预展开确认。

**Q10: [learning] 新设备节点仍显示 unlabeled 时，file_contexts 如何匹配路径并决定标签？**

file_contexts 根据路径和可选的文件类型标记选择安全上下文；静态路径匹配先于正则项，正则优先级以构建工具生成的排序产物为准。应检查合并后的最终文件，而不能只凭某个源文件中的行号推断命中项。匹配不到时对象会落到未标记类型，针对通用类型添加 allow 并不能修正标签。

1. **条目格式**：`路径正则 [文件类型标记] u:object_r:类型:s0`；文件类型标记可省略，省略时匹配任意文件类型。

    1. `-b`：块设备。
    2. `-c`：字符设备。
    3. `-d`：目录。
    4. `-p`：命名管道。
    5. `-l`：符号链接。
    6. `-s`：socket 文件。
    7. `--`：普通文件，不是"后接类型"的分隔符。

    平台和设备策略条目在构建时合并，并由构建工具处理排序。
2. **unlabeled 的含义**：规则没命中——先补 file_contexts 条目，而不是给 unlabeled 加 allow；
3. **验证**：`ls -Z <路径>` 的 `-Z` 显示安全上下文；在可 root 的调试设备上运行 `restorecon -R <路径>`，其中 `-R` 表示递归按当前规则重标该目录及其内容。修改 `/data` 下应用数据标签时，还要核对 `/mnt/expand` 对应规则，避免后续重标记恢复成旧标签。
4. **典型坑**：只检查设备自有源文件，忽略合并后的平台规则或排序结果；应检查实际构建产物及其匹配顺序。

**Q11: 新增一个系统属性，SELinux 侧要改哪几处？**

新增系统属性时，先确认命名空间与属性类型，再定义标签、映射属性名并分别授权读写；早期 `setprop` 失败也应检查该属性是否命中正确的 `property_contexts` 条目。该文件只负责"属性名 → 标签"映射，实际读写权限由策略规则控制。

1. **标签映射与属性值类型**：`property_contexts` 条目格式为 `属性名 u:object_r:属性类型:s0 exact|prefix [值类型]`。`exact` 只匹配该属性名，`prefix` 匹配以该前缀开头的属性；冲突时 exact 项优先。前缀规则可能覆盖多个属性，只有它们确实共用标签和访问范围时才应使用。

    1. `bool`：接受布尔形式的值，例如 `true`/`false` 或 `1`/`0`。
    2. `int`：有符号 64 位整数。
    3. `uint`：无符号 64 位整数。
    4. `double`：双精度浮点数。
    5. `enum`：在类型声明后列出允许的字符串值，只接受列出的值。
    6. `string`：接受合法 UTF-8 字符串；列表属性也以字符串形式存储。

    这些值类型在支持它们的 Android 分支中会由属性服务校验。省略值类型时，不会获得该类型约束；新增条目应尽可能声明并逐项核实类型。
2. **类型声明**：vendor 属性可定义专属类型，如 `type vendor_x_prop, property_type;`。也可选择符合命名空间和访问范围的现有宏（例如 `system_internal_prop`）。不要为省事使用过宽的 `core_property_type`。
3. **访问授权**：写入方用 `set_prop(域, vendor_x_prop)`，读取方用 `get_prop(域, vendor_x_prop)`。前者覆盖连接属性服务及提交写入所需规则，后者覆盖读取属性所需规则。`property_service set` 与读取属性的 `file read` 是不同访问，不能相互替代。
4. **加载与失败定位**：启动时各分区的 `property_contexts` 被读取为属性名到标签的查找数据；没有专用映射的属性可能落到默认类型，早期属性写入也会因类型或权限不匹配失败。写入时先按属性名找到类型，再按进程域与属性类型检查写权限。`setprop` 失败时同时核对目标属性是否匹配到预期类型、调用者是否拥有 set 权限，以及属性值是否符合声明的类型约束。
5. **属性存储边界**：属性值存放在共享属性区；读取属性与写入属性是不同操作，读权限通常通过属性类型对应的 file 权限检查，写入还要经过属性服务及 property_service 权限检查。
6. **完整步骤**：定义类型、添加映射、给实际读写域授权，再运行策略编译检查；不要把日志生成的 allow 候选未经审查直接写入策略。

Android 13 中，`get_prop(coredomain, system_prop)` 可展开为 `allow coredomain system_prop:file { getattr open read map };`。`getattr` 读取属性对象元数据，`open` 打开属性映射对象，`read` 读取值，`map` 允许映射访问；这些权限的具体宏体可能随版本调整。只有新属性映射到该规则覆盖的类型、且读取域属于 `coredomain` 时才继承该授权，不能仅凭属性名前缀推断。

**Q12: 新 Binder、HIDL 或 AIDL HAL 服务注册被拒时，服务名如何映射到安全类型？**

服务名到安全类型的映射文件取决于服务管理器：`service_contexts` 用于 servicemanager 管理的 Binder 服务及 AIDL HAL，`hwservice_contexts` 用于 hwservicemanager 管理的 HIDL HAL；应用进程域由另一套规则决定，不属于服务名映射。

1. **servicemanager 服务**：普通 Binder 服务和 AIDL HAL 服务按服务名映射；AIDL HAL 常用完整接口描述符与实例名组成的键，例如 `android.hardware.foo.IFoo/default`。
2. **hwservicemanager 服务**：HIDL HAL 映射使用 `包@版本::接口/实例` 形式的服务名，条目写入 `hwservice_contexts`。
3. **授权与诊断**：映射只确定服务类型；服务进程仍需相应 add 权限，客户端仍需 find 权限。AOSP 的 `hal_attribute_service` 等宏可按 HAL attribute 组合服务端注册和客户端查找规则，宏体需按分支核实。用拒绝记录里的 `scontext`、`tcontext`、`tclass` 和权限名定位缺失的一侧，并用 `ps -Z` 核对进程域。

**Q13: [learning] 三方应用直读自研驱动节点撞上 neverallow 时，怎样调整能力暴露路径？**

不要放开 app 域：平台侧建服务、把服务类型挂 `app_api_service` 属性，应用经 Binder 调服务；确需硬件访问就 HAL 化（`hal_attribute` + `hal_client_domain`）。app_neverallows 对全部 untrusted 域禁掉了应用数据目录执行、debugfs 读、注册服务、vndbinder 等一整组权限，这些禁令是平台安全模型的编译期保证，不可绕过。

1. **标准三步**：平台服务暴露能力（服务类型挂 app_api_service）→ 应用域与服务域之间 `binder_call` 授权 → 服务域内做受控的节点访问；
2. **例外评估**：确实需要直访硬件的，评估建专有 HAL 域，而不是修改 app 域约束；
3. **设计判断**：把硬件访问收敛在受控服务中，服务负责参数校验、权限检查和设备操作；仅当平台架构确实要求应用直连时，才由平台安全设计重新评估专用域与最小权限。

**Q14: [learning] vendor sepolicy 改动似乎未生效、同名 .te 顺序难判断或升级平台后 avc 暴增，怎样检查策略组织与版本兼容？**

vendor 策略由 `BOARD_VENDOR_SEPOLICY_DIRS` 指定的目录参与构建；同名策略文件按目录顺序拼接，而不是后一个文件简单覆盖前一个。平台与 vendor 的兼容由 `prebuilts/api/<版本>/` 快照和 mapping 版本化文件支撑。

1. **拼接**：同名 `.te` 都可能进入合并输入，目录顺序影响拼接顺序，不等于覆盖优先级。检查 `vendor_sepolicy.conf` 等中间产物，确认源文件是否进入构建及最终规则内容。
2. **细节**：每个策略文件必须以换行符结尾，因为 m4 拼接与报错定位依赖它。构建宏经 `BOARD_SEPOLICY_M4DEFS` 传入。
3. **版本化**：新平台 + 旧 vendor 靠 mapping/compat 文件维持旧语义——升级后 avc 暴增，先查 compat 版本文件是否缺失或错版；
4. **归属纪律**：平台类型改动放 system/sepolicy 并经 public 暴露给 vendor，vendor 专属规则放自己的 sepolicy 目录；vendor 引用平台 private 类型会直接编译失败。
5. **ODM**：`BOARD_ODM_SEPOLICY_DIRS` 与 vendor 变量并列（经 Soong config 传入构建），odm 策略在构建代码里独立收集标签；sepolicy README 未写 ODM 章节，权威出处是构建代码（build_files.go / versioned_policy.go）——vendor/odm 策略按 `BOARD_SEPOLICY_VERS` 选定平台版本参与兼容校验；
6. **快照一致性**：public/private 的接口改动要同步 `system/sepolicy/prebuilts/api/<版本>/` 快照——构建期校验两侧内容一致；快照用于生成 `plat_pub_versioned.cil`，让旧 vendor 按自己锁定的平台版本参与编译。

**Q15: service_manager find 被拒时，SELinux 为什么不抛异常而是把应用打崩？——"静默 null"链路与文件类拒绝的差异**

`service_manager` 检查由 servicemanager 在用户态完成；find 被拒时，服务查询接口可以返回空 Binder，而不是把内核 `EACCES` 异常直接传给 Java 调用方。AIDL 生成的 `asInterface(null)` 也返回 null；如果 framework 或应用把它缓存后不判空再调用，就会产生 NPE。文件类拒绝则由系统调用返回错误码，最终是否崩溃取决于调用方是否处理该错误。

完整因果链（以真机案例"点击音效崩溃"为例）：

1. App 进程调 `getService("audio")`，事务经 binder 到 servicemanager；
2. servicemanager 的 `tryGetService` 先在服务注册表命中（AudioService 开机 20 秒就注册了），再做 `canFind` 检查——策略没有对应 allow，丢弃已到手的 binder，返回 null，事务回包仍是 Status::ok 加空 binder；
3. Java 层 `IAudioService.Stub.asInterface(null)` 返回 null，被写进 `AudioManager` 的静态字段 `sService`，本进程终生缓存；
4. 之后每次点击音效都对 null 调接口方法，NPE → FATAL → AMS 杀进程重启；新进程的 `sService` 与用户态 AVC 缓存都是空的，第一次查询再次被拒——重启即复崩，形成崩溃循环。

在这个调用场景中，Binder 事务成功只表示查询请求与响应完成，不表示查找得到服务；空句柄是否变成应用崩溃，取决于调用方的空值处理。系统服务客户端不应把某个查找结果必定非空当作 SELinux 的保证。

若 NPE 指向 `IAudioService`、`IClipboardManager` 或 `IActivityTaskManager` 等系统服务接口，应检查服务查找结果及调用进程的 `service_manager find` 权限。剪贴板查询也可能由无障碍节点预取等间接路径触发，因此复现时不一定需要用户显式点击剪贴板。

**Q16: 从 ServiceManager.getService("audio") 到策略拒绝，binder 两侧完整经过了哪些代码？**

链路是：Java 侧查缓存未命中后经 bootstrap 代理发起 binder 调用，servicemanager 收到事务、恢复调用者身份、查表命中后做 SELinux 检查，拒绝时返回 null 且事务状态仍为 ok。逐跳展开：

1. `ServiceManager.getService` 先查当前进程的 `sCache`；若未初始化或没有 `audio` 条目，就继续走 `rawGetService`。缓存是否包含某个服务取决于进程初始化数据，不能把服务名一概视为缓存命中或未命中。
2. `getIServiceManager` 经 `BinderInternal.getContextObject`（JNI）拿 `ProcessState` 的句柄 0 代理——句柄 0 是 binder 驱动协议里 context manager 的固定地址，唯一不经名字查询的对象，一切名字查询的起点；首次调用会打开 `/dev/binder` 并 mmap；
3. AIDL 生成的 Proxy 把服务名打包进 Parcel，`BinderProxy.transact` 进 JNI，libbinder 写事务后 `ioctl(BINDER_WRITE_READ)` 阻塞等回包；
4. servicemanager 收到 `BR_TRANSACTION_SEC_CTX`：驱动在每个事务附带调用者的 SELinux 上下文（servicemanager 注册时用了 `BINDER_SET_CONTEXT_MGR_EXT` 扩展 ioctl 请求的），服务端把它存进 `IPCThreadState.mCallingSid`；旧内核不支持该特性时回退 `getpidcon(pid)` 读 `/proc`；
5. AIDL 桩分发到 `ServiceManager::getService → tryGetService`：先查 `mNameToService` 注册表（命中即拿到 binder 指针），再做 `mAccess->canFind`，被拒则 `return nullptr`；
6. 回包 Status 恒为 ok，Java 层 `readStrongBinder()` 读出 null，全程无异常。

关键代码事实（AAOS13 `servicemanager/ServiceManager.cpp`）：查表命中发生在权限检查**之前**——"服务明明注册在、还是查不到"不是竞态，是检查顺序使然。

**Q17: tclass=service_manager 的 avc 拒绝为什么在 dmesg 里搜不到？两类 avc 日志各走哪条路径？**

因为 `service_manager` 是 userspace 类：`security_classes` 里它带 userspace 标记，访问检查不在内核 LSM 执行，而由对象管理器（servicemanager 进程）自己做——内核对这次拒绝毫无感知，自然不会产生内核审计记录。要看这条日志得用 `logcat -b events | grep auditd`。

两类 avc 的路径对照如下：

1. **userspace 类**（service_manager、hwservice_manager、property_service 等）：对应对象管理器进程在用户态执行检查，因此不是内核 LSM 拒绝记录；servicemanager 的拒绝通过 `auditd` tag 写入 logd events 缓冲，打印行的 pid 是 servicemanager 自己。其他对象管理器的具体日志去向应结合其审计回调确认。
2. **内核类**（file、tcp_socket 等）：内核 LSM 执行判定，审计记录通常带 `type=1400 audit(序列号):` 前缀写入内核日志，再由 logd 转发。

排查含义：抓日志只合成了主缓冲时，userspace 类拒绝会整类消失——"崩溃现场没有 avc"先怀疑缓冲不全或 dontaudit 静音，不要直接下"与 SELinux 无关"的结论。

**Q18: SELinux 运行时会读 .te 或 service_contexts 文件吗？策略与映射分别何时进入内存？**

运行时不会逐次解析 `.te` 源文件。策略构建后由 init 装入内核；servicemanager 等用户态对象管理器也会加载各自的标签映射句柄，并可使用 AVC 缓存访问决策。`.te` 与 `service_contexts` 因此进入不同的数据结构和执行路径。

1. **开机阶段**：二阶段 init 读取预编译 sepolicy（逐项哈希校验，不符则现场编译），写入 `/sys/fs/selinux/load` 装载进内核——此后所有 allow 与 deny 判定都查这份内存 policydb；所以改策略必须重新编译并重启才生效；
2. **servicemanager 首次 add/find 检查时**：`Access.cpp` 的 `getSehandle()` 惰性打开 service_contexts 编译产物，构建进程内查找句柄并缓存；策略热更新时 `selinux_status_updated` 会检测到并关闭旧句柄，下次查找按新策略重建；
3. **每次检查**：用户态 AVC 会缓存访问决策，未命中时再向内核查询；service_contexts 查找句柄与访问决策缓存是两种不同缓存。是否记录每次拒绝还受审计策略和 `dontaudit` 影响，不能仅凭缓存命中推断日志一定出现或消失。

策略规则在构建期编译并于启动期装载；服务标签映射由 servicemanager 在需要时加载；之后检查使用已装载的数据与缓存，不会逐次读取源文件。

**Q19: 厂商 App"点哪崩哪、重启即复崩"，如何一步步把根因定位到 SELinux 策略缺失？**

先排查启动时序、服务存活和系统组件，再用跨进程对照与 AVC/崩溃时间关联收敛根因。若同一时刻对同一服务的不同调用进程结果不同，且失败与 `service_manager find` 拒绝一一对应，就强烈指向域授权差异。下面是真机案例（某车机平台多个厂商 App 连环崩溃）的排查记录：

1. **排除时序竞态**：设备 uptime 两小时以上，且进程重启后数秒内复崩——不是"服务没起完"也不是进程内坏缓存；
2. **排除服务未注册**：SystemServer 日志显示 AudioService 开机 20 秒即启动，audioserver 进程存活且在混音。注意 native 守护进程活着不等于 Java 层 "audio" 服务对该域可查；
3. **排除系统组件故障**：system_server 与 watchdog 正常，AMS 还能正常重启崩溃应用；
4. **跨域对照**：launcher 和 SystemUI 点击正常、平台签名的地图应用正常、厂商自签名的多个 App 必崩；结合调用进程的域和策略匹配结果判断差异，签名、安装属性等也可能通过 seapp_contexts 影响域；
5. **实锤对应**：`logcat -b events` 里每条 FATAL 前毫秒级都有一条 `service_manager find` denied，denial 的 pid 与崩溃 pid 一一对应（真机 10 次崩溃 10 条拒绝全对应）；
6. **闭环验证**：补齐 allow 规则刷机重启，denied 与崩溃同时消失。


**Q20: Android 13 AOSP 的普通应用域为什么能 find audio 服务？属性批量授权怎样起作用？**

在对应平台策略中，`audio_service` 类型带有 `app_api_service` 属性，`untrusted_app_all.te` 又按该属性集合授予 `service_manager find`；普通应用域满足成员条件时就能查找这类服务。构建产物包含编译后的规则和属性关系，运行时查的是已装载策略，不会读取 `.te` 源文件或重新展开宏。

展开链按依赖顺序是四步：

1. **属性即类型集合**：`public/service.te` 里 `type audio_service, app_api_service, ...`，属性本体在 `public/attributes` 声明；`service_manager_type` 属性标记"可被 add 进 servicemanager 的类型"资格集；
2. **域入集合**：普通 App 域的 te 文件头部调用 `untrusted_app_domain()` 宏，宏体将域挂入 `untrusted_app_all` 集合；具体宏展开以 Android 13 的 public/te_macros 为准；
3. **批量 allow**：`untrusted_app_all.te` 的那条 find 规则于是对集合全体生效，覆盖 audio、clipboard、autofill、window 等几十个挂了 `app_api_service` 的服务；
4. **厂商自建域缺环节**：自定义域不属于该属性集合、也没有符合其信任级别的授权时，批量规则不会覆盖它，查服务便可能失败。车机厂商 App 域出现这一现象时，应依据实际应用信任级别配置最小范围的服务查找权限；不要为了得到一条 find 规则就无条件继承整套 `untrusted_app_domain()` 权限。

对照样本：`platform_app`（地图类系统签名 App 的域）不在 untrusted_app_all 集合里，它在自己的 te 里写了同型的批量 allow——同一台设备上"地图查 audio 永远成功、商店一查就崩"的差异全部来自这两份文件的取舍。

**Q21: 作为 SELinux 开发者，修复一条 service_manager find 拒绝的完整流程是什么？**

标准流程六步：定位四元组、写规则进正确分区、过 neverallow 自检、静态验证、重启动态验证、用调试开关收尾。

1. **定位**：用 `ps -Z` 查看调用者域。AVC 行中的 scontext、tcontext、tclass 和权限可以翻译成策略规则；例如 `scontext=u:r:nsr_appstore... tcontext=u:object_r:audio_service:s0` 配合 `tclass=service_manager` 与 `find` 对应 `allow nsr_appstore audio_service:service_manager find;`。
2. **落点**：厂商域的规则放进厂商 sepolicy 目录，并在 `BoardConfig.mk` 的 `BOARD_VENDOR_SEPOLICY_DIRS` 登记路径；只能引用 platform public 类型（`service.te` 声明的 `audio_service` 等可直接用），引用平台 `private/` 类型会编译失败；
3. **neverallow 自检**：checkpolicy 编译期断言，规则与任何 neverallow 相交即整体编译失败——过不去时先怀疑设计而非删除断言；
4. **静态验证**：用 `sesearch -A -s <域> -t <类型> -c service_manager -p find` 查询编译策略；`-A` 显示 allow，`-s` 指定源域，`-t` 指定目标类型，`-c` 指定对象类，`-p` 指定权限。确认规则已进入预期分区的产物。
5. **动态验证**：刷入包含新策略的镜像并重启，因为 init 在启动阶段装载策略。userdebug 构建可用 `setenforce 0` 暂时切换全局宽容模式作对照，或在策略中只将目标域设为 permissive；两种方式都只用于调试，不能替代最终 enforcing 验证。
6. **调试收尾**：`auditallow` 会把"已放行的访问"也打日志，用于确认规则真实生效；验证通过后删除 permissive 与 auditallow 调试语句。

边界：`add` 权限是另一档安全等级——平台策略禁止一切 untrusted App 域对任何 service_manager_type 有 add 权，这不是缺陷而是设计；App 域的合理诉求几乎总是 find。

**Q22: 策略里确实发生了拒绝，avc 日志却一条都没有——dontaudit 的静音机制与排查方法是什么？**

`dontaudit` 在策略编译期把对应访问的 auditdeny 位清零：运行时拒绝照旧生效，但 libselinux 判定"该拒绝无需审计"直接跳过日志生成——dmesg 和 logcat 都不会有记录。遇到"行为明显被拒、日志里却找不到 avc"，第一怀疑对象就是它。

机制细节：内核对每次权限查询返回的裁决结构里，auditdeny 位图从全 1 起步（含义是"拒绝默认要记日志"）；`dontaudit` 规则编译成清位条目，把对应权限位 AND 成 0。用户态拒绝同样遵循：`avc_audit` 计算"被拒位 AND auditdeny"，结果为 0 就不调日志回调、不拼消息。

排查手段：

1. `sesearch --dontaudit -s <域>` 查询匹配该源域的 dontaudit 规则；`--dontaudit` 选择静音规则，`-s` 指定源域。再按目标类型、对象类和权限缩小匹配范围。
2. 临时删除可疑的 dontaudit 规则重新编译，或改用 permissive 域配合 `auditallow` 观察完整访问流。
3. 排除缓冲因素：servicemanager 类拒绝通常需读取 logd events 缓冲，普通主缓冲未必包含它；`dmesg` 用于检查内核审计记录，不是所有 userspace AVC 的日志来源。

**Q23: [learning] neverallow 与运行时拒绝是什么关系？服务落到 default_android_service 类型后注册失败该怎么解？**

`neverallow` 是构建期约束：策略构建检查发现 allow 与断言相交时，策略构建失败；它不是运行时访问规则。运行时仍由已装载策略中的 allow、属性和默认拒绝决定访问结果。

典型场景是 `default_android_service` 兜底标签：servicemanager 的 `service_contexts` 默认映射可让未单独标记的服务名落到该类型。平台策略对该类型设置 `neverallow * default_android_service:service_manager add;`，因此不能通过新增 allow 让服务以兜底类型注册；运行时没有相应 add 授权也会拒绝注册。不能据此推断同一断言禁止 find。正确处理是为服务定义专属类型、在正确的 contexts 文件映射服务名，再分别配置服务端 add 和客户端 find；HIDL HAL 应使用 hwservice 类型及映射。

**Q24: ioctl 权限加了 allow 还是被拒（avc 里 ioctlcmd=0x...）——SELinux 对 ioctl 的双层授权怎么补？**

因为 ioctl 在 SELinux 里是"类权限位 + 命令白名单"双层控制：`allow` 只放行 ioctl 这个权限位，具体命令号还要 `allowxperm` 白名单放行——只加前者的典型现象是 avc 里 `ioctlcmd=0x127c` 这类条目反复出现、策略看起来"没生效"。

机制：内核对 ioctl 调用做两步检查——先过类的 ioctl 权限位，再核对本次命令号是否在该对（源域，目标类型）的 allowxperm 集合内；被拒时日志带 `ioctlcmd=` 十六进制命令号，这就是要补的白名单项。

修法三步（以 `/dev/block/xvdb` 上 `ioctlcmd=0x127c` 为例）：

1. 拿命令号到 `system/sepolicy/public/ioctl_defines` 找宏——0x127c 对应 `BLKDISCARDZEROES`；
2. `allow resize2fs xvd_block_device:blk_file ioctl;` 放行权限位；
3. `allowxperm resize2fs xvd_block_device:blk_file ioctl { BLKDISCARDZEROES };` 放行具体命令。

边界：每条 ioctl 命令都要单独白名单；"放行全部命令号"的写法在代码评审中通常被拒——白名单本身就是要审的安全边界。

**Q25: 新增设备节点或 HAL 执行外部程序撞 neverallow 编译失败——两大典型场景与最小权限原则的解法是什么？**

这类构建失败常见于两种情况：把权限授予过于宽泛的类型，或 HAL 试图在原域中执行外部程序。安全修复分别是缩小目标类型和配置明确的域转换。

1. **设备节点落在通用类型**：对未专门标记的 `/dev/vircan` 添加 `allow hal_xxx device:chr_file { open read write };` 会违反针对通用 `device` 类型的 neverallow。AOSP 注释要求为对象重新标记为更具体的类型。

    1. 在 device.te 定义 `type vircan_device, dev_type;`。
    2. 在 file_contexts 将 `/dev/vircan` 映射到 `vircan_device`。
    3. 将 allow 规则的目标类型改为 `vircan_device`，只授予实际需要的权限。

2. **HAL 请求 execute_no_trans**：HAL 直接以原域执行 `/vendor/bin/sh` 等外部程序需要 `execute_no_trans`，但平台策略通常禁止 HAL 对一般 file_type/fs_type 这样做，仅为特定域保留例外。原因是一个 HAL 域可能聚合多项服务能力，原域执行任意程序会越过隔离边界。应为受控程序定义执行文件标签与目标域，再配置例如 `domain_auto_trans(hal_gnss_unicore, vendor_shell_exec, vendor_shell)` 的域转换，并按设备分支核对转换所需的配套规则。

同一最小权限原则也适用于 `socket_device`、`block_device`、`default_service`、`system_data_file` 和 `tmpfs` 等通用类型。遇到 neverallow 时先确认被拒类型、权限和断言意图，为目标对象建专用类型或调整执行方式，不要删除断言。

**Q26: SELinux 三种工作模式怎么切换？发布版为什么必须 Enforcing？调试用的宽容模式有哪几种开法？**

三种模式的含义是 Enforcing（未授权访问被拒绝）、Permissive（访问不被拒绝但仍可产生审计记录）和 Disabled（SELinux 未启用）。Android 产品发布必须维持 enforcing；兼容性测试会检查 SELinux 状态与策略约束，但这不等于设备不能增加符合约束的厂商策略。

启用与模式决策链（已按 AAOS13 源码核对）：

1. 内核配置 `CONFIG_SECURITY_SELINUX=y` 使能框架；
2. init 决策：读内核 cmdline 或 bootconfig 的 `androidboot.selinux`，值为 permissive 则宽容，否则 enforcing（`system/core/init/selinux.cpp` 里对 `androidboot.selinux` 的显式解析）；
3. 编译开关 `ALLOW_PERMISSIVE_SELINUX`：init 构建时以 `-D` 宏注入（Android.bp 中 user 版本为 0），为 0 时发布形态直接禁用宽容能力——注意资料里常见的 `DALLOW_PERMISSIVE_SELINUX` 是笔误。

调试期的三种宽容开法，按侵入性从小到大：

1. 串口或 adb 临时切换：`setenforce 0`（等价 `setenforce Permissive`），`getenforce` 查看。该切换重启后失效，适合单轮复现。
2. 持久宽容：`BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive`——每次开机都宽容，仅限调试构建；
3. 单域放宽：te 里写 `permissive <域>;`。只有目标域不拦截，全局其余域保持 enforcing，因此影响范围较小。

边界：user build 无法 setenforce 0；宽容模式改变的只是"是否拦截"，avc 日志照记且 `permissive=1` 标注——从宽容日志收敛出的 allow 规则与 enforcing 下等价。

**Q27: 策略改完如何快速编译与验证？audit2allow 的正确用法是什么？**

快速迭代应先确认策略归属，编译后检查目标分区产物，再在可调试设备上部署并重启验证；audit2allow 只用于生成待审候选规则。

1. **编译**：在 AOSP 根目录运行 `make selinux_policy -j8`。`selinux_policy` 是策略构建目标，`-j8` 指最多并行 8 个构建任务；目标名和构建入口按分支确认。
2. **检查产物**：`out/target/product/<device>/system/etc/selinux/` 与 `vendor/etc/selinux/` 分别包含平台侧和厂商侧产物。`<device>` 替换成产品名；用 `grep` 搜索生成的 CIL/合并策略，确认修改进入了预期分区，不要把厂商规则误放进平台策略。
3. **部署验证**：调试设备上依次执行 `adb root`（以 root 重启 adbd）、`adb remount`（尝试将可写分区重新挂载为可写）、`adb push <本地文件> <设备路径>`（复制指定策略文件），然后重启。仅推送变更所需且格式正确的产物；不同 Android 分支的策略文件布局不同，不能把整目录盲目覆盖。重启后按原场景复测并检查对应 AVC。
4. **生成候选规则**：`external/selinux/prebuilts/bin/audit2allow -i avc.log -p out/target/product/<device>/root/sepolicy` 中，`-i` 指定 denial 日志输入，`-p` 指定现有二进制策略供工具参考；`avc.log` 和 `<device>` 都要替换成实际文件名与产品名。输出只是候选 allow，必须审查需求并排除设计性拒绝后才能落盘。

注意：日志里的 "avc: granted" 不是错误——那是 auditallow 或宽容模式下放行决策的留痕，排查 denied 时可以直接忽略。

**Q28: avc 里的 tcontext=audio_service 是指 audioserver 进程吗？**

不是。audio_service 是 servicemanager 注册条目的标签，真正干活的进程是 audioserver、域为 u:r:audioserver:s0——getService 阶段被查的对象是条目而非进程，所以 role 显示 object_r、类是 service_manager。

三个东西各有一个标签：

1. **"audio"**：应用传给 getService 的服务名字符串，本身无标签；
2. **注册条目**：servicemanager 注册表里"名字 → 句柄"的访问点，类型 `audio_service` 来自 `service_contexts`；
3. **audioserver 进程**：混音服务的主体，域 `u:r:audioserver:s0` 由 init 经 audioserver_exec 的 type_transition 落定。

检查分两道门：getService 只到 servicemanager，查的是条目（tcontext=audio_service、tclass=service_manager）；find 通过后发起 binder 调用，对象才变成进程——目标类型用进程域 audioserver、类为 binder，由 binder_call 规则管。

收束：判断 avc 查的是条目还是进程，看 tclass 即可——service_manager/hwservice_manager 对应注册条目，binder/process 对应进程本身；改服务标签去 service_contexts，改进程权限去域的 .te，两套互不相干。
