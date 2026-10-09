# Android 权限系统

> 学习资料（文章模式沉淀）。主线：应用权限的声明与授权、Framework 检查、AppOps、Linux UID/GID 和 SELinux 的协作边界。应用沙箱机制见 `09-app-sandbox.md`，SELinux 策略细节见 `11-selinux.md`。应用侧 API 使用与排障按具体服务主题归档。

**Q1: [learning] Android 的 android.permission.* 是什么机制，权限检查发生在哪一层？**

Android 权限是由系统框架管理的访问控制契约：应用在 manifest 请求权限，系统按 protection level 和用户/系统授权状态决定是否授予，受保护的 API 或服务再依据调用者身份检查是否允许操作。它主要由 Android Framework 与系统服务实现，但不能简化为“纯 Java 检查”或“内核完全不参与”。

1. **声明与授予**：`uses-permission` 表达应用的请求，不代表请求已获授权。PackageManager/PermissionManager 根据权限类型、安装状态、用户选择、系统签名或预装策略记录授权状态。普通权限通常自动授予，危险权限等还受运行时授权流程约束。
2. **调用检查**：Framework API 或服务端在执行受保护操作前检查调用 UID、包名、权限状态，并可能进一步检查 AppOps、角色、前台状态或用户限制。具体检查点取决于 API，不能假定所有权限都只在安装时检查。
3. **底层约束**：部分权限状态会映射为进程 supplementary GID 或其他系统状态。Linux DAC、内核网络检查和 SELinux 可在各自边界执行相应限制。大多数相机、位置等 API 则由框架服务做调用者授权与数据访问控制，不能仅凭权限名推断为直接访问设备节点。

因此，定位一次权限拒绝应从具体 API 的检查路径开始，再确认系统记录的授权状态、AppOps 结果和实际进程的 UID/GID、SELinux 域。manifest 中出现权限名只证明应用提出了请求。

**Q2: Android 应用权限与 SELinux、Linux UID/GID 分别控制什么？它们怎样互补？**

三者处于不同的执行边界：应用权限表达应用能否使用某项受保护能力，UID/GID 与文件权限控制进程对内核资源的自主访问，SELinux 按安全域和对象标签实施强制访问控制。任何一层都不能替代另外两层。

1. **Framework 权限与 AppOps**：系统服务可按调用 UID/包名及用户授权决定是否允许访问相机、定位、联系人等 API。AppOps 还能表达某些操作的运行状态或用户限制，实际关系按 API 与版本而变。
2. **Linux DAC 与补充组**：进程 UID/GID 参与文件、设备节点和部分内核接口的访问判断。某些 Android 权限会映射到补充组，例如 `INTERNET` 与 `inet` 组有关联，内核网络路径可以据此限制创建 IP socket 的资格。
3. **SELinux**：内核根据进程安全上下文、对象标签和策略决定访问是否允许。即使 Framework 权限检查通过，SELinux 仍可拒绝服务或进程访问某个文件、设备或 Binder 端点。反过来，SELinux 允许进程访问某对象也不代表应用获得了对应 Framework API 权限。

以相机为例，应用需要通过相机 API 和系统服务完成访问，Framework 检查调用者权限。CameraService 等可信组件再按自身 UID/GID 与 SELinux 域访问设备资源。`camera` 组用于设备侧权限配置，不应推导为第三方应用获授 `CAMERA` 权限后就直接获得该组。

**Q3: Android 为什么需要 Framework 权限？部分权限怎样通过 UID/GID 影响内核访问？**

Framework 权限让系统能够按应用身份和用户授权管理高层能力，并把需要内核参与的少数访问限制映射到进程凭据。它不是把每项用户授权都翻译成一个 GID。每个权限的实现方式必须查对应平台配置与调用链。

1. **用户同意与最小授权**：危险权限等可通过系统界面让用户按应用作出授权决定。内核的文件模式位或 SELinux 策略本身不能代替这套面向应用的声明、解释和授权状态管理。
2. **权限到 GID 的映射**：平台配置可为某些权限定义补充组。AOSP 的 `android.permission.INTERNET` 与 `inet`（GID 3003）存在这种关联，使内核网络访问规则能够约束没有相应资格的进程。应检查目标分支的 `platform.xml` 和 PermissionManager 实现，不能把一项权限的映射推广到所有权限。
3. **进程凭据生效**：系统根据应用 UID 和已授予权限计算进程所需的 GID，进程创建或凭据更新路径再把它们应用到进程。实际生效时机和权限撤销后的处理应以目标 Android 版本源码为准。
4. **相机权限的边界**：`CAMERA` 的普通应用授权由 Framework/相机服务检查。AOSP 历史上曾将该权限映射到 `camera` 组，后来删除了这项映射。当前不能用它作为“应用权限必然映射成设备节点 GID”的例子。设备节点所属的 `camera` 组仍可供可信相机服务使用。

资料来源：AOSP `frameworks/base/data/etc/platform.xml` 的权限到 GID 映射、`PermissionManagerService` 的授权与 GID 计算实现，以及 `system/core/include/private/android_filesystem_config.h` 的 Android UID/GID 定义。具体映射随平台版本和产品配置变化。
