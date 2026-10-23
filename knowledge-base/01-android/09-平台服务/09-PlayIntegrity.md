# Play Integrity 请求与判定

> 学习资料（文章模式沉淀）。本文解释 Play Integrity 请求选型、请求绑定、服务端判定和端到端延迟的边界。Q 序列即结构。

**Q1: [learning] Play Integrity 的 Standard 与 Classic 请求怎样选，prepare 何时执行？**

多数动作优先选 Standard，在需要 verdict 之前异步 prepare；偶发高价值动作若需要每次重新计算且能承受更高耗时，再评估 Classic。按调用链比较：

1. **Standard：**先以 Cloud project number 调 `prepareIntegrityToken()`，把返回的 StandardIntegrityTokenProvider 保存在进程内；动作发生时计算 `requestHash` 并申请新 token。prepare 会访问 Google 服务端，宜提前进行，不要在每个动作前重复 prepare。
2. **Classic：**无 prepare，每次请求传一次性 nonce 重新计算 verdict。它更耗时，应低频用于高价值动作，不缓存 verdict。
3. **失效处理：**Standard provider 报 `INTEGRITY_TOKEN_PROVIDER_INVALID` 时丢弃旧引用并重新 prepare；不能提前缓存业务 token 或重复用于另一动作。

Google Play Integrity 官方 Standard、Classic 与 Setup 文档（2026-10 核对）给出量级：prepare 通常数秒、多数低于 10 秒，涉及网络，建议容纳约一分钟尾部；Standard token 通常数百毫秒、Classic 通常数秒。这些是典型值，不是 SLA。当前默认每日 token 请求配额 10,000 次，由 Classic 请求与 Standard prepare 共享；服务端解密另有每日 10,000 次默认配额。单应用实例 Standard prepare 为 5 次/分钟，Classic token 为 5 次/分钟，Standard token 无公开的每分钟固定上限，但仍有防滥用限制。配额可申请调整，正式预算须以当前 Play Console/官方文档与项目配额为准。AOSP 不含闭源 Play 组件或 verdict 生成源码。

**Q2: [learning] requestHash 与 Classic nonce 怎样绑定动作，服务端解密后查什么？**

token 解密只证明 Play 返回了载荷，业务服务端仍须验证它对应当前动作并防重放。先建立稳定的动作表示：

1. **规范化输入：**固定字段顺序、空值、编码、金额单位、版本与长度边界，纳入账号 ID、action ID、订单 ID、金额、币种和动作类型等会改变授权决定的字段。
2. **Standard 绑定：**对规范化字节算 SHA-256，再用 URL-safe、no-wrap Base64 编码为 `requestHash`，长度不超过 500 字节；不放敏感明文。服务端按同一规则重算并与 `requestDetails.requestHash` 比较。
3. **Classic 绑定：**由服务端生成至少 128 bit 的不可预测一次性 nonce，与动作关联；解密后核对 nonce 属于当前请求且未被消费。

服务端凭关联 Cloud project 的凭据调用 `decodeIntegrityToken` 后，依次检查：

1. 包名及请求绑定值是否匹配当前动作，`timestampMillis` 是否仍在业务有效窗口。
2. `appRecognitionVerdict`、`appLicensingVerdict` 与所需设备标签是否满足明确策略；`UNEVALUATED` 是未知，不等于明确失败，设备标签为空也不能简单写成“已 root”。Android 13 起设备标签的解释还依赖硬件与系统版本条件。
3. 业务幂等键是否已消费。Standard 对同一 token 的重复解密有自动缓解，但同一订单换 token 重交仍须业务幂等。

最终授权在服务端完成，service account 凭据与 verdict 判定逻辑不能放进 APK。

**Q3: [learning] Play Integrity 的端到端延迟和失败应怎样分段处理？**

客户端 token 请求完成不等于业务获得最终判定；记录五段独立 wall time：

1. `provider_prepare`：prepare 到成功或失败，受网络与 Play 组件影响。
2. `token_request`：provider.request 到 Task 完成。
3. `app_to_backend`：业务上行，包含网络与 TLS。
4. `google_decode`：服务端调用 decode 到收到 payload。
5. `policy_and_business`：解析 verdict、账号/交易查询和策略决策。

错误按语义分开：

1. **API 调用失败：**`NETWORK_ERROR`、`TOO_MANY_REQUESTS` 等可按有上限的指数退避与抖动重试；前台交互不照搬后台长等待。provider invalid 先重新 prepare。
2. **未评估：**`UNEVALUATED` 或标签缺失保留未知语义，由服务端风险策略决定，不伪装为通过或明确失败。
3. **明确风险：**如 `UNRECOGNIZED_VERSION`，按完整性失败处置；高价值动作不能因网络超时自动放行。

修复对话框由服务端解析 verdict 后指示客户端展示，修复后重新申请 token。材料所用库版本中，`GET_LICENSED` 要求 1.3.0+，`GET_INTEGRITY`/`GET_STRONG_INTEGRITY` 要求 1.5.0+；接入时按所用库的官方 remediation 文档复核。无 Play Store/Play 服务的设备需独立账号和风险策略。应用侧没有稳定的 Play 内部 Perfetto slice，可用 async trace 覆盖 token 请求的墙钟等待，并按网络、地区、机型、冷暖状态报告 P50/P90/P99；保留不调用 Integrity 的业务耗时作对照。
