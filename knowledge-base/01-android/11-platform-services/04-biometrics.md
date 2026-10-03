# 生物识别服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"BiometricPrompt/BiometricService 的请求链路、会话替换、强度等级与回调时序"。源文档：android-internals-wiki §1.24（Android 17 语境），强度能力与会话语义已与官方文档核对。Q 序列即结构，供 atlas 同源直读。

**Q1: BiometricPrompt.authenticate() 很快返回，为什么不能说明传感器已经开始采集？请求依次经过哪些组件？**

`authenticate()` 正常返回只说明同步调用已结束，不能证明异步认证最终成功，也不能证明传感器已经开始采集。Android 17 的认证继续在服务端和传感器调度线程推进，应用通过回调获取最终结果。

一次认证请求会依次经过这些边界：

1. **应用到系统服务：**应用进程的 `BiometricPrompt` 经 `IAuthService`（由 `Context.AUTH_SERVICE` 提供）进入 system_server。普通应用不直接持有内部 `IBiometricService`。
2. **入口校验：**`AuthService` 检查生物识别权限、AppOps、token、`PromptInfo` 和调用方前台状态，然后清除来访 Binder 身份。
3. **会话与传感器筛选：**内部 `BiometricService` 用 `PreAuthInfo` 根据强度、模板录入、锁定、设备策略和相机隐私等条件计算可参与传感器，再创建 `AuthSession`。
4. **HAL 调度：**`FingerprintService`、`FaceService` 等 provider 为传感器创建认证 client，放入该传感器自己的 `BiometricScheduler`，再经 AIDL `ISession` 调用 vendor HAL。
5. **安全认证：**传感器采集、模板匹配和 Strong 认证所需的 HardwareAuthToken（HAT）由安全隔离环境处理。HAL 服务进程位于 Android 系统侧，本身不等于 TEE。
6. **采集前等待：**请求还可能经历预认证、cookie 握手和 SystemUI Prompt 展示。因此应分别测量请求提交、传感器启动、首次采集和回调到达时间，不能用 API 返回时间代表采集开始。

**Q2: BiometricPrompt 认证进行中再次调用 authenticate()，旧请求会排队等待还是被替换？**

平台的处理行为是用新请求替换当前认证，不把第二个应用请求排入等待队列。Android 17 的 `BiometricService` 通过一个当前 `mAuthSession` 管理 BiometricPrompt 会话，新请求会取消并关闭旧会话，旧客户端收到取消错误。

平台在不同层级采用不同排队策略：

1. **跨应用请求：**`BiometricService` 只管理当前 `mAuthSession`。新请求会替换正在进行的认证，不排队等待。
2. **单传感器操作：**各自的 `BiometricScheduler` 串行调度该传感器的认证、录入等 client。它不是跨应用请求的全局队列。
3. **应用生命周期：**避免配置变化或重复点击时快速取消再认证，这会引起 Prompt 重建、HAL 取消确认和 scheduler 清理，还可能让一次用户操作产生互相覆盖的回调。Jetpack `BiometricPrompt` 可在配置变化后重建并接管现有会话的 callback。仅在业务确实结束时才取消认证。

**Q3: BIOMETRIC_WEAK（Class 2）认证成功后，为什么拿不到可用于 Keystore 的 HardwareAuthToken？**

Keystore 的生物识别定时授权和单次操作授权要求 Class 3（`BIOMETRIC_STRONG`）。Class 2（`BIOMETRIC_WEAK`）仍可用于普通 `BiometricPrompt`，但不能用于这些 Keystore 授权。即使 HAL 回传 token，Android 17 的 `AuthSession.onAuthenticationSucceeded()` 也会丢弃非 Strong token。

各认证强度的公开能力边界如下：

1. `BIOMETRIC_STRONG` / Class 3：可进入 `BiometricPrompt`，并可用于 Keystore 时间窗授权和单次操作授权。
2. `BIOMETRIC_WEAK` / Class 2：可进入普通 `BiometricPrompt`，不能用于 Keystore 时间窗授权、单次操作授权或 `CryptoObject` 认证。
3. Class 1（convenience）：不进入公开 `BiometricPrompt` API。
4. `DEVICE_CREDENTIAL`（PIN、图案或密码）：可用于 `BiometricPrompt` 和 Keystore 授权。

认证类别与安全实现还受这些边界约束：

1. 指纹、人脸是认证模态，Class 是安全等级。二维相机不自动等于 Class 2，深度相机也不自动等于 Class 3。设备必须按 CDD 约束声明能力并通过兼容性测试。
2. Class 2 与 Class 3 要求在安全隔离环境中保护生物识别数据和匹配流程。framework 接收认证事件、受限标识和合格 HAT，不读取原始模板。
3. 需要 `CryptoObject` 或密钥绑定时使用 `BIOMETRIC_STRONG`。`canAuthenticate()` 只反映调用时可用性，不预留传感器，也不保证之后认证成功。最终结果以 `AuthenticationCallback` 为准。

**Q4: HAL 已报告生物识别匹配成功，为什么应用的成功回调还要再等一会儿？**

HAL 匹配成功时，framework 会先记录已认证的传感器、暂存 Strong HAT 并通知 SystemUI 更新 Prompt。若还需用户确认，系统会等用户确认。不要求确认时，也要等 SystemUI 报告成功关闭。随后 `AuthSession` 才把 HAT 交给 Keystore、通知应用认证成功并清理传感器。因此，HAL success 不等于 App callback 已执行。

三个"完成"时间点不能混用：

1. **HAL success：**安全 matcher 已接受样本并向 framework 报告成功。
2. **SystemUI success：**Prompt 显示认证成功。配置需要显式确认时，用户还须完成确认操作。
3. **App callback：**SystemUI 回报对应 dismiss reason 后，服务端处理 HAT 并将成功回调投递给应用 Executor。

用户可能在成功动画开始时就认为认证完成，业务代码必须等 App callback。性能指标也要写明采用 HAL success、SystemUI success 还是 App callback 作为结束点。应用回调运行在调用方传入的 `Executor` 上。若使用 `getMainExecutor()`，在回调里读数据库、访问网络或做复杂解密会占用主线程，应把重任务交给后台执行器，再把 UI 更新切回主线程。

**Q5: 人脸与指纹同时可用时，Android 17 的 BiometricPrompt 会做特征融合吗？两种模态的启动顺序如何协调？**

framework 不做厂商级人脸与指纹特征融合，只协调传感器启动、Prompt、成功、失败和取消。任一传感器成功后会记录 `mAuthenticatedSensorId` 并取消其他传感器，除非当前确认策略要求继续等待用户确认。迟到的回调会按当前 session 和 requestId 校验，避免旧请求事件污染新会话。

1. **准备与启动：**`AuthSession` 等所有有资格传感器的 cookie 就绪后，先启动非指纹传感器并请求 SystemUI 显示 Prompt。人脸因此可以先于 Prompt 完成入场动画时开始运行。
2. **指纹时机：**指纹传感器通常等 SystemUI 的入场动画通过 `onDialogAnimatedIn` 通知完成后才启动，避免指纹交互提示早于对话框出现。部分场景可由 SystemUI 发出显式的立即启动信号。
3. **失败状态：**人脸不匹配时，应用仍会收到非终止的 `onAuthenticationFailed()`。framework 会暂停该人脸传感器并让 SystemUI 显示可重试状态。指纹不匹配时通常继续等待下一次触摸，不暂停传感器。
4. **时延与超时：**双模态认证时延不能简单写成 `max(人脸, 指纹)`，因为两个传感器启动时刻和失败后的 UI 策略不同。framework 没有统一的“全部认证 30–60 秒强制结束”常量。超时可能来自 HAL error、sensor client、SystemUI 交互状态或 scheduler watchdog，排查时应记录实际 error 与 modality。
