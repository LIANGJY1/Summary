# Android Keystore 操作与认证

> 学习资料（文章模式沉淀）。本文解释 Keystore 操作阶段、按次认证、安全硬件选择，以及密钥数量配额与 alias 生命周期。Android 17 配额内容依据 android-internals-wiki §20.10、官方文档和 keystore2 源码逐题标注。生物识别系统链路见 [04-生物识别.md](04-生物识别.md)。Q 序列即结构。

**Q1: [learning] 一次 Keystore 运算应怎样分段计时，为什么只包住 sign() 或 doFinal() 会漏掉 init()？**

只包住 `sign()` 或 `doFinal()` 会漏掉密钥读取、operation 建立和可选认证。一次操作按时间分段记录：

1. **密钥查找：**`KeyStore.getKey()` 可能涉及 Provider、Binder 和 key blob 读取。
2. **密钥生成或导入：**仅首次创建或轮换时发生，不能混入每次签名耗时。
3. **operation 初始化：**`Cipher.init()`/`Signature.initSign()` 可触发 KeyMint `begin()`、分配 slot 与生成 challenge。
4. **数据处理与结束：**`update()`、`doFinal()` 或 `sign()` 包含传输和硬件计算。
5. **用户认证：**认证密钥按 per-use 或 time-based 策略另有等待，需单独计时。

按 Android 17 固定源码语境转写，`AndroidKeyStoreCipherSpiBase.engineInit()` 经 `ensureKeystoreOperationInitialized()` 进入 `KeyStoreSecurityLevel.createOperation()`，因此 init 时已占用 operation slot。AAOS13 本地树不含 keystore2 与该 Provider 源码。`finish()`、错误、显式 `abort()`、Binder 对象释放或 slot 紧张时的 prune 均可结束 operation。同一代理并发使用可报 `OPERATION_BUSY`。不要提前数分钟创建并闲置 Cipher/Signature/Mac。AndroidKeyStore 操作放在少量有界工作线程，`engineGenerateKey()` 与若干 init 也被 StrictMode 标为潜在慢调用。

**Q2: [learning] setUserAuthenticationRequired(true) 的 per-use 与 time-based 密钥流程差在哪，CryptoObject 为什么必须用认证回调返回的同一对象？**

两种密钥把授权绑定在不同时间点，不能复用同一初始化流程：

1. **per-use（timeout = 0）：**每次使用都需授权。先在后台初始化 Signature/Cipher，把该 operation 包入 `BiometricPrompt.CryptoObject`，认证成功后在回调返回的同一对象上签名。另建对象会得到另一个 operation，无法沿用刚才的授权。
2. **time-based（timeout > 0）：**有效期内可直接创建 operation。若 init 抛 `UserNotAuthenticatedException`，先认证，再重新创建并初始化对象；不要复用初始化失败的对象。

API 30 起 `setUserAuthenticationParameters()` 明确有效期和认证类型。Android 11+ 的 per-use key 可按策略允许 device credential。Android 10 及以下的 crypto 与 device credential 组合受限，不能把旧限制推广到全部版本。关闭安全锁屏、重置凭据或 biometric enrollment 变化可能令密钥永久失效并抛 `KeyPermanentlyInvalidatedException`。恢复前区分可重建密钥与须服务端解绑的密钥，否则旧数据可能再也无法解密。

**Q3: [learning] 选择 StrongBox 或 TEE 时，安全级别、耗时与 operation slot 怎样权衡？**

StrongBox（Android 9+，`FEATURE_STRONGBOX_KEYSTORE`）提供更强物理隔离，通常更慢、slot 更少，多数应用不应只为性能默认启用。`setIsStrongBoxBacked(true)` 失败会抛 `StrongBoxUnavailableException`，平台不自动降到 TEE；按安全要求预设三种策略：

1. **required：**StrongBox 不可用则终止受保护流程。
2. **preferred：**记录失败，经安全策略允许后重新生成非 StrongBox 密钥，并通知服务端。
3. **不要求：**使用平台默认安全级别。API 31+ 可用 `KeyInfo.getSecurityLevel()` 记录实际级别。

slot 资源也需单独治理。Android 12 起 KeyMint AIDL 合同要求支持 32 个并发 operation（材料按 Android 17 AIDL 转写），旧的 16 或“15+1 给 vold”不是当前合同。超过时 `begin()` 可返回 `TOO_MANY_OPERATIONS`，keystore2 可按 owner 兄弟数量与年龄等因素 prune 旧操作，无候选时可能报 `BACKEND_BUSY`。被回收的旧对象再用会报 invalid handle，没有稳定 FIFO 等待队列。提前为列表项建 Cipher、认证前久置 per-use operation、批量无界并发和超时后保留旧对象再重试都会耗尽 slot。按 TEE/StrongBox 分别压测少量有界线程，记录 busy、pruned 与 invalid-handle。调用方停止等待不保证底层操作立刻取消。

**Q4: [learning] Android 17 的 Keystore 密钥配额规则是什么？Android 13 设备上有这条配额吗？达到上限后旧密钥还能用吗？**

Android 17 起按应用 UID 限制 Keystore 密钥条目数。达到限制后新的密钥生成和导入会失败，但已存在的密钥仍可使用，系统也不会主动删除旧密钥。配额只在密钥创建路径检查。

配额与版本边界如下（Android 17 keystore2 源码 `KeystoreSecurityLevel.check_key_counts()` 与官方文档已核对）：

1. **target API 37 及以上的非系统应用：**上限为 50,000 个条目。
2. **其他应用和系统应用：**上限为 200,000 个条目。
3. **target API 37 及以上达到上限：**返回 API 37 新增的 `ERROR_TOO_MANY_KEYS`，常量值为 18。
4. **较低 target API 达到上限：**返回 `ERROR_INCORRECT_USAGE`，异常消息包含密钥数量限制信息。
5. **检查位置：**`generate_key()`、`import_key()`、`import_wrapped_key()` 在进入 KeyMint 前检查条目数。现有密钥的 `Cipher.init()` 和 `Signature.initSign()` 不经过该配额检查。
6. **旧密钥使用：**升级 target SDK 不会仅因配额使旧密钥失效。不过，同名 alias 的 rebind 也要先创建新密钥，满额时会在替换前被拒绝。
7. **UID 范围：**配额属于 UID，不属于进程或业务模块。同一安装包在不同 Android 用户或工作资料下的 UID 分别计数，TEE 和 StrongBox 密钥计入同一 UID 总数。
8. **Android 13：**Android 16 及更早版本不执行这条配额，因此 Android 13 设备没有此限制。AAOS 13 本地树未包含 `system/security`（keystore2）目录，机制细节按 Android 17 材料及官方文档核对。

**Q5: [learning] 哪些设计会让 Keystore alias 数量持续增长？passkey 是否计入本应用的配额？**

alias 持续增长通常是因为把一次性或不断变化的标识写进 alias，却没有定义回收周期。治理时应让每类用途占用固定槽位和少量版本，并区分应用自己创建的密钥与凭据提供方管理的 passkey。

常见增长来源及控制方法如下：

1. **设备绑定：**在 alias 中加入时间戳，或每次绑定生成新随机 ID。改为按稳定业务域、用途和版本组织固定槽位，例如 `auth.<account-slot>.device-sign.v3`。`account-slot` 使用首次绑定时生成的随机标识，不含敏感信息。
2. **账号和设备生命周期：**账号退出、注销或设备解绑后不回收密钥。完成业务授权后，按生命周期回收不再使用的版本。
3. **一次性数据：**每笔订单或每次挑战都创建密钥。一次性挑战状态不应写入 Keystore。
4. **数据加密：**每个文件或数据库表都创建 Keystore 密钥。可改用少量 Keystore 密钥包装 DEK（数据加密密钥）。
5. **算法轮换与测试：**轮换只新增不删除，或 QA 测试前缀长期累积。确认新版本可用后回收旧版本，并清理测试 alias。
6. **敏感信息：**alias 不应包含账号 ID、手机号、订单号、token 或可猜哈希。除了占用配额，这些内容还会带来数据关联与猜测风险。

passkey 是否计入本应用配额，取决于私钥由谁管理：

1. **普通依赖方应用：**通过 Credential Manager 请求 passkey 时，私钥由用户选择的凭据提供方（例如密码管理器）保存，不计入本应用 Keystore alias。
2. **应用自建凭据提供方：**应用自己调用 Keystore 生成密钥，或作为凭据提供方管理私钥时，这些条目才计入该 UID 的盘点。
3. **核对方法：**查看本应用 `AndroidKeyStore` 可见 alias 和密钥创建调用栈，不按用户的 passkey 数量推算配额。

**Q6: [learning] Keystore 故障只看异常消息为什么分类不准？应按什么组合分类？**

异常消息不足以分类 Keystore 故障，因为异常可能多层包装，同一错误码也可能对应多种原因。应同时查看外层异常、cause 链、公开错误码和失败阶段（生成、导入、使用或删除）。

判断时要注意这些 API 和错误类别：

1. **结构化错误信息：**Android 13（API 33）起，`android.security.KeyStoreException` 提供 `getNumericErrorCode()`、`isTransientFailure()`、`getRetryPolicy()`、`isSystemError()` 和 `requiresUserAuthentication()`。
2. **包装异常：**JCA 可能将底层 `KeyStoreException` 包在 `ProviderException` 或 `InvalidKeyException` 的 cause 链中。
3. **独立公开异常：**`UserNotAuthenticatedException`、`KeyPermanentlyInvalidatedException` 和 `StrongBoxUnavailableException` 都需要分别识别。
4. **认证失败与永久失效：**前者应请求设备凭据或生物认证，不应删除密钥。后者应先完成业务身份验证，再替换并回收旧条目。把两者弄反可能导致不必要的删密钥和重新登录。
5. **配额错误：**Android 17 且 target API 37 及以上时，`ERROR_TOO_MANY_KEYS` 可确认配额耗尽。Android 17 上较低 target 的 `ERROR_INCORRECT_USAGE`，只有消息明确提到密钥数量限制时才记为疑似配额，因为该错误码也可能表示算法或参数组合错误。
6. **Keystore 未初始化：**对 `ERROR_KEYSTORE_UNINITIALIZED`，要区分尚未调用 `KeyStore.load()` 与设备未设置锁屏凭据（LSKF）。
7. **瞬态失败：**只有 `isTransientFailure()` 为真时，才按 `getRetryPolicy()` 指定的时机重试，并设置全局次数上限。
8. **权限或参数失败：**`ERROR_PERMISSION_DENIED`，以及没有配额证据的 `ERROR_INCORRECT_USAGE`，应检查调用身份、权限和参数，不能靠重试恢复。

诊断记录保留外层异常类、cause 链中的 `KeyStoreException`、错误码和清理状态。消息文本只能作为辅助证据。

**Q7: [learning] 配额耗尽导致登录失败时，为什么“清空 Keystore 后重试”最危险？正确的恢复顺序是什么？**

清空全部 Keystore 可能同时破坏登录、端到端加密、支付、数据库解锁和第三方 SDK 状态，把配额故障扩大成账号和数据安全故障。恢复必须先停止新建，再验证当前密钥，之后按责任边界小批量回收可确认无用的条目。

建议按以下顺序恢复：

1. 写入持久化的“暂停创建”标记，并让同一应用其他进程读取它，阻止故障期间继续生成密钥。
2. 若当前选中的 alias 仍存在且可用，优先用它完成解密、签名或登录恢复。
3. 按生命周期登记表找候选回收项，包括已退出使用的版本、孤立条目、测试前缀和已注销账号的密钥。
4. 若账号注销或设备解绑需要服务端参与，先完成业务授权。
5. 由对应责任模块分批删除，记录每批成功数、失败数和剩余数量。
6. 重新盘点并确认有余量后，再恢复创建。
7. 大规模盘点放在有执行时限的后台任务。启动路径只读取暂停标记和当前 alias，避免主线程长时间阻塞并触发 ANR。

同名 alias 的 rebind 不能靠原样重试解决，因为配额检查发生在创建替换密钥之前。满额时必须先删除可回收 alias 释放额度。不能临时退回明文 token、共享外部存储或缺少认证约束的软件密钥。是否从 StrongBox 改用 TEE 必须由预先审查的安全策略决定。卸载应用通常会清理对应 UID 的 Keystore 数据，但重装同时会清除业务数据和认证状态，也不会修复 alias 持续增长的代码，不能作为线上恢复方案。

**Q8: [learning] 为什么不能在 containsAlias() 返回 false 后直接 generateKey()？多进程应用如何保证 alias 状态一致？**

不能把 `containsAlias()` 的检查与 `generateKey()` 当成一个原子操作。两个进程可能同时读到不存在，再各自创建密钥，造成重复密钥和配额浪费。创建、轮换和删除应由单一 owner 在持久状态机中串行管理。

alias 生命周期可用以下状态迁移表示：

1. `ABSENT → CREATING`：在生成前先持久化 `CREATING`。
2. `CREATING → ACTIVE`：生成成功后读取 characteristics，确认算法、安全级别和认证授权属性符合策略，再登记为 `ACTIVE`。
3. `ACTIVE → RETIRED`：新版本确认可用后，将旧版本退役。同一用途只保留一个 `ACTIVE` 版本，轮换期最多额外保留一个最近确认可用的版本。
4. `RETIRED → DELETING → DELETED`：先登记 `DELETING`，调用 `KeyStore.deleteEntry()`，复查条目已删除后再登记 `DELETED`。
5. **中断恢复：**进程在生成或删除中被杀后，重启时同时查询登记表和 Keystore，再决定继续提交还是回收。

生命周期登记表是应用自维护的数据，不是 Android API。它不保存密钥材料，也不能把数据库有记录当成密钥存在的证明。每次重要操作都要处理两类不一致：登记表有记录但 Keystore 没有条目，以及 Keystore 有条目但登记表没有归属。

多进程与 SDK 的责任边界如下：

1. 每个 alias 前缀只有一个责任组件能创建、轮换和删除。
2. 业务进程只请求“取得或创建某用途密钥”，不自行拼接随机 alias。
3. 删除前确认相关进程使用的密钥版本。配额失败后写入共享暂停标记。
4. 登记表结构需保留应用版本回滚时的兼容迁移逻辑。
5. 第三方 SDK 自行创建密钥时，宿主应要求其声明 alias 前缀、创建频率和删除 API，否则无法安全回收。

**Q9: [learning] 应用侧如何盘点自己的 alias 数量？target SDK 从 36 升到 37 前要做什么准备？**

应用可用 `KeyStore.getInstance("AndroidKeyStore")` 和 `aliases()` 枚举自己可见的 alias，再按受控前缀盘点。该数量只是应用视角的估算，`KeyStore.aliases()` 和 `size()` 都不是 keystore2 配额查询 API。

盘点和遥测应遵守以下边界：

1. 遥测只保留 alias 总数、已知前缀计数和未匹配数量，不上传完整 alias。
2. `unknownCount` 只表示有 alias 未匹配已知前缀，不能据此批量删除。
3. 接近上限的数万条目全量枚举会产生 Binder 调用、数据库查询和字符串分配开销。
4. 不要在主线程、`Application.onCreate()` 首帧前或每次登录时执行全量枚举。日常用低频后台任务采集趋势，进入预警区间或出现创建失败后再详细盘点，并记录耗时、枚举异常和触发原因。

从 target API 36 升到 37 前要考虑存量设备，而不只是新安装：

1. 系统不会自动清理历史密钥。已积累大量条目的非系统应用 UID 升级到 target 37 后，下一次生成或导入就可能失败。
2. 发布前按历史设备 alias 数量区间评估，不要只测新装设备。
3. 50,000 和 200,000 是拒绝新密钥的硬上限，不适合作为业务预警阈值。预警线应根据每日增长速度、清理能力和一次轮换的最大增量设得更低。
4. 容量测试使用独立设备或可重置测试用户，固定测试前缀并限制生成数量。任务正常结束或中断恢复时都要清理测试密钥。
