# Android Keystore 操作与认证

> 学习资料（文章模式沉淀）。本文解释 Keystore 操作阶段、按次授权与安全硬件的性能和资源边界；生物识别系统链路见 [04-生物识别.md](./04-生物识别.md)。Q 序列即结构。

**Q1: [learning] 一次 Keystore 运算应怎样分段计时，为什么只包住 sign() 或 doFinal() 会漏掉 init()？**

只包住 `sign()` 或 `doFinal()` 会漏掉密钥读取、operation 建立和可选认证。一次操作按时间分段记录：

1. **密钥查找：**`KeyStore.getKey()` 可能涉及 Provider、Binder 和 key blob 读取。
2. **密钥生成或导入：**仅首次创建或轮换时发生，不能混入每次签名耗时。
3. **operation 初始化：**`Cipher.init()`/`Signature.initSign()` 可触发 KeyMint `begin()`、分配 slot 与生成 challenge。
4. **数据处理与结束：**`update()`、`doFinal()` 或 `sign()` 包含传输和硬件计算。
5. **用户认证：**认证密钥按 per-use 或 time-based 策略另有等待，需单独计时。

按 Android 17 固定源码语境转写，`AndroidKeyStoreCipherSpiBase.engineInit()` 经 `ensureKeystoreOperationInitialized()` 进入 `KeyStoreSecurityLevel.createOperation()`，因此 init 时已占用 operation slot；AAOS13 本地树不含 keystore2 与该 Provider 源码。`finish()`、错误、显式 `abort()`、Binder 对象释放或 slot 紧张时的 prune 均可结束 operation；同一代理并发使用可报 `OPERATION_BUSY`。不要提前数分钟创建并闲置 Cipher/Signature/Mac；AndroidKeyStore 操作放在少量有界工作线程，`engineGenerateKey()` 与若干 init 也被 StrictMode 标为潜在慢调用。

**Q2: [learning] setUserAuthenticationRequired(true) 的 per-use 与 time-based 密钥流程差在哪，CryptoObject 为什么必须用认证回调返回的同一对象？**

两种密钥把授权绑定在不同时间点，不能复用同一初始化流程：

1. **per-use（timeout = 0）：**每次使用都需授权。先在后台初始化 Signature/Cipher，把该 operation 包入 `BiometricPrompt.CryptoObject`，认证成功后在回调返回的同一对象上签名。另建对象会得到另一个 operation，无法沿用刚才的授权。
2. **time-based（timeout > 0）：**有效期内可直接创建 operation。若 init 抛 `UserNotAuthenticatedException`，先认证，再重新创建并初始化对象；不要复用初始化失败的对象。

API 30 起 `setUserAuthenticationParameters()` 明确有效期和认证类型。Android 11+ 的 per-use key 可按策略允许 device credential；Android 10 及以下的 crypto 与 device credential 组合受限，不能把旧限制推广到全部版本。关闭安全锁屏、重置凭据或 biometric enrollment 变化可能令密钥永久失效并抛 `KeyPermanentlyInvalidatedException`；恢复前区分可重建密钥与须服务端解绑的密钥，否则旧数据可能再也无法解密。

**Q3: [learning] 选择 StrongBox 或 TEE 时，安全级别、耗时与 operation slot 怎样权衡？**

StrongBox（Android 9+，`FEATURE_STRONGBOX_KEYSTORE`）提供更强物理隔离，通常更慢、slot 更少，多数应用不应只为性能默认启用。`setIsStrongBoxBacked(true)` 失败会抛 `StrongBoxUnavailableException`，平台不自动降到 TEE；按安全要求预设三种策略：

1. **required：**StrongBox 不可用则终止受保护流程。
2. **preferred：**记录失败，经安全策略允许后重新生成非 StrongBox 密钥，并通知服务端。
3. **不要求：**使用平台默认安全级别。API 31+ 可用 `KeyInfo.getSecurityLevel()` 记录实际级别。

slot 资源也需单独治理。Android 12 起 KeyMint AIDL 合同要求支持 32 个并发 operation（材料按 Android 17 AIDL 转写），旧的 16 或“15+1 给 vold”不是当前合同。超过时 `begin()` 可返回 `TOO_MANY_OPERATIONS`，keystore2 可按 owner 兄弟数量与年龄等因素 prune 旧操作，无候选时可能报 `BACKEND_BUSY`；被回收的旧对象再用会报 invalid handle，没有稳定 FIFO 等待队列。提前为列表项建 Cipher、认证前久置 per-use operation、批量无界并发和超时后保留旧对象再重试都会耗尽 slot。按 TEE/StrongBox 分别压测少量有界线程，记录 busy、pruned 与 invalid-handle；调用方停止等待不保证底层操作立刻取消。
