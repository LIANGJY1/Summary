# Android Agent Harness 研究：交互接口与安全信息流

> 学习资料。本文讨论 AOHP（Android Open Harness Project）研究原型及其与标准 Android 平台的差异。论文设计、原型实现和 Android 公共接口是不同证据层级；具体结论须以各自固定版本为准。

**Q1: [learning] AOHP 想把 Android 改造成什么样？评估它时为什么要区分论文设计、原型实现与标准平台三类证据？**

AOHP（Android Open Harness Project）是面向 AI agent 的 OS 级 harness 研究原型：agent 一次任务可能跨多个应用、CLI、文件与服务，需要结构化可验证的观察、事件驱动的生命周期与“数据可流向哪里”的控制面。而标准 Android 的授权对象是应用 UID 与组件，运行时权限与 URI grant 不会理解 LLM prompt 或跨工具派生数据。AOHP 通过修改 frameworks/base、system/core、SELinux policy 等把能力发现、策略检查与审计放进统一系统服务，论文方案分为个性化服务组合、高效 agent 接口与安全信息流三部分。

三类证据不能互替：论文设计（arXiv 2606.23449）说明架构与实验。AOHP 实现（framework fork 与 container daemon 的固定 commit）说明当前原型写出了什么。Android 17 对照（android-17.0.0_r1 与 API 37 文档）说明标准平台提供什么。例如论文的 OS 管理跨服务记忆、全链路信息流控制是设计目标，而实现中 AohpVaultService 是内存 token 表（ConcurrentHashMap 直接保存明文映射，无持久加密、过期与 secure erase），AohpTaintTrackerService 只在选定 UI/tool/file 边界记录元数据——不是 TaintDroid 式的全系统动态污点传播。仓库以 Android 16 QPR2 为构建基线且未进入 Android 17，fork 源码不能当标准平台源码读。项目自身也标注为早期研究、不适合生产环境。

**Q2: [learning] AOHP 用特权虚拟显示、结构化 UI 和事件流替代 GUI 往返。标准平台的 VirtualDisplay 与 AppFunctions 分别给到哪一步？**

AOHP 的虚拟显示用 signature|privileged 权限 MANAGE_AOHP_VIRTUAL_DISPLAY 与 framework 内部接口创建 PUBLIC+TRUSTED+OWN_FOCUS 的 display，把应用放上去后台交互，再挂 ImageReader（maxImages 为 3）持续取帧。成本仍然存在——WindowManager、SurfaceFlinger、图形 buffer、GPU 合成与输入分发都要工作，论文未报告其开销。标准平台上，公共 DisplayManager.createVirtualDisplay() 创建的 display 默认 private、non-secure，创建 VirtualDisplay 不会自动获得后台启动其它应用 Activity、读取 FLAG_SECURE 窗口、注入输入或豁免冻结等能力，这些受系统权限限制。AOHP 的 Structured UI 从 accessibility tree 导出节点类型/文本/层级/bounds，省去像素细节，但 Canvas、游戏与 WebView 的语义质量依赖应用，节点树与像素可能不同帧。事件流由 system_server 内 hook 提供 Toast/通知的 session buffer（默认上限 200 条、TTL 10 分钟），普通应用用 NotificationListenerService 无法等价复制，因为它拿不到其他应用的 Toast。

标准平台最接近的结构化入口是 AppFunctions（API 36 进入平台、Android 17 扩展，官方仍标 experimental preview）：目标应用在 XML asset 声明 function，caller 搜索需 DISCOVER_APP_FUNCTIONS、跨包执行需 EXECUTE_APP_FUNCTIONS 或 EXECUTE_APP_FUNCTIONS_SYSTEM，并满足包可见性。runtime registration 随注册 context 生命周期，注册进程被冻结时系统不会进入该实现。它不提供任意后台 GUI workspace，与 AOHP 的虚拟显示 session 不能互换。system_server 侧服务由 AppFunctionManagerConfiguration.isSupported() 的 flag gate 决定是否启动。两者组合前要先统一身份、consent、审计与撤销模型，避免两个控制面重复授权。

**Q3: [learning] AOHP 的安全信息流设计了六步流程，原型现状与论文的差距在哪？五个 security case 通过能说明什么、不能说明什么？**

论文流程六步：source 被识别为敏感，明文进 agent context 前替换为 typed placeholder，vault 保存明文与 token 映射，agent 只携带 token 提交意图，trusted executor 检查 source/purpose/destination/action/consent，sink 前放行、确认或拒绝并写审计。原型现状的差距要逐个 sink 标注：UI tree 敏感字段替换、token 输入校验、敏感 tap/输入的 consent、file share 检查已实现或部分实现。文件读写的策略检查是 stub（源码返回 DENY 加 file_read_policy_not_implemented），好在选择 fail closed——缺策略时默认拒绝。vault 是进程内存 token 表，重启后映射消失，无硬件密钥绑定、用户隔离与可验证擦除。sanitizer 依赖应用声明加手机号、银行卡号等启发式，字段识别不保证完整。

五个 security case（敏感显示替换、普通动作放行、敏感动作确认、未支持访问拒绝、敏感事件脱敏）在该原型与该测试应用上通过，能证明这五个用例按预期运行。不能证明任意第三方应用的字段识别完整、隐式流/native code/侧信道被跟踪、prompt injection 无法诱导已授权动作、vault 在重启/多用户/设备失窃场景安全，或 framework 服务无提权与拒绝服务问题。实验数字同理：checkpoint 加权完成率从 54.44% 提升到 75.56%、共同成功任务的 token 降低 51.55%，是该任务集上该 agent 的结果，论文没有消融实验，不能归因给 SUI 单一机制，更不能推广到所有应用、设备与模型。
