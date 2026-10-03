# Android 权限系统（android.permission.*）

> 学习资料（文章模式沉淀）。主线：框架层权限系统的定位（android.permission.* 是 Java 框架实现的"应用对能力"授权体系，内核不参与判定）、它与内核沙箱（UID/SELinux/seccomp，见 [09-app-sandbox.md](09-app-sandbox.md)；策略层见 [11-selinux.md](11-selinux.md)）的分工与互补、以及它向内核桥接的设计（权限映射补充组，经 Zygote SetGids 生效）。来源：2026-09-26 对话深讲；实践细节（运行时授权弹窗流程、AppOps 调试）属应用实操，后续进 16-app-practice 册。Q 序列即结构，供 atlas 同源直读。

**Q1: android.permission.* 是什么机制？它属于哪一层？**

android.permission.* 是 Android 框架层的权限系统——纯用户态、纯 Java 框架实现：应用在 manifest 里用 uses-permission 声明所需能力，PackageManagerService 在安装时登记，system_server 里的服务在框架 API 入口检查"这个 UID 有没有这个权限"（checkPermission/enforceCallingPermission）。内核从头到尾不参与判定——它执法的是"框架能力的使用资格"，不是内核对象访问。

组成与落点：

1. **声明与登记**：manifest 声明 → PMS 解析登记（packages.xml），安装期授予普通权限；
2. **运行时授权**：Android 6.0 起危险权限（CAMERA、LOCATION 等）由用户在运行时授予/撤销，按应用粒度动态变化；
3. **检查点**：框架服务入口（如 LocationManager 的实现先查 ACCESS_FINE_LOCATION）与组件启动（exported 组件、protected broadcast）。

收束：它是"应用对能力"的授权体系，执法点在 system_server 的 Java 服务里——与内核沙箱（04-Sanbox）分属两个平面。

**Q2: 有了 SELinux，为什么还需要 android.permission.*？**

因为两者管的**对象、粒度、时效**完全不同，互为盲区——SELinux 判"进程对内核对象"（构建期静态、按域统一），permission 判"应用对框架能力"（运行期动态、按应用授权）。

三点展开：

1. **对象不同**：SELinux 管"进程 ↔ 内核对象"（文件、设备节点、socket、属性）；但应用日常要的能力是框架服务——定位、相机、联系人，由 system_server 的 Java 服务提供，SELinux 域看不到"这是哪个应用在调服务"；
2. **粒度不同**：SELinux 策略构建期固化、按域统一（所有三方应用同为 untrusted_app），无法表达"A 应用可以用相机、B 不行"；permission 按应用、按权限名、运行期动态——正是 6.0 起用户"运行时授权/随时撤销"的载体，这种用户交互 SELinux 做不了（策略不能因用户点了"允许"就重编）；
3. **纵深互补**：permission 是准入判断（有没有资格用这个 API），SELinux 是执行兜底（框架被绕过或有漏洞时，进程对内核对象的访问仍被域拦）。反向同理：system_server 代应用执行时运行在自己的域里，SELinux 分不清请求来自哪个应用——区分调用者靠的正是 permission 检查。

收束：SELinux 判"进程对对象"、随镜像走；permission 判"应用对能力"、随用户走——两层互为对方的盲区补全。

**Q3: 为什么设计 android.permission.*？它和内核沙箱是怎么衔接的？**

设计动机是生态层的知情同意：Android 是安装第三方应用的消费级系统，必须让用户知道并决定应用能干什么——"向用户展示能力清单并授权"这个概念任何内核机制都没有；且设计它时（Android 1.0）内核只有 DAC，SELinux 尚未引入，当时没有任何机制能表达"这个应用能用相机 API 但不能读短信"。

它不是孤立的 Java 层摆设，而是一个**向下翻译层**——把 Java 层的用户授权翻译成内核可执行的能力：

1. 部分权限映射为 UID 的补充组：INTERNET → inet 组、CAMERA → camera 组（Android 10 起）；
2. 补充组随 Zygote specialize 的 SetGids 传入内核（见 04-Sanbox 的 specialize 链路），成为设备节点/内核能力的访问资格；
3. 于是"用户授了 CAMERA 权"最终落成"该 UID 的进程对相机相关节点有组权限"——Java 授权与内核执行在 UID 上会师。

收束：manifest 声明 → PMS 计算 gids 与授权表 → Zygote SetGids → 内核生效；框架授权与内核沙箱在 UID 这一点上会师——UID 既是 DAC 的主体，也是权限归属的主键。
