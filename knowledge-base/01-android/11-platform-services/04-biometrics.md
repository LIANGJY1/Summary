# 生物识别服务链路

> 学习资料（文章模式沉淀）。边界：本文回答"BiometricPrompt/BiometricService 的请求链路、会话替换、强度等级与回调时序"。源文档：android-internals-wiki §1.24（Android 17 语境），强度能力与会话语义已与官方文档核对。Q 序列即结构，供 atlas 同源直读。

**Q1: BiometricPrompt.authenticate() 很快返回，为什么不能说明传感器已经开始采集？请求依次经过哪些组件？**

快速返回只说明请求已被系统接受：访问控制在 system_server 完成后，后续工作被投递到专用线程异步执行，应用不会在 Binder 调用栈中等待传感器。Android 17 的一次认证依次经过：

1. 应用进程 `BiometricPrompt` 经 `IAuthService`（`Context.AUTH_SERVICE`）进入 system_server——普通应用不直接持有内部 `IBiometricService`；
2. `AuthService` 做访问控制：生物识别权限、AppOps、token 与 `PromptInfo` 检查、调用方前台状态，随后清除来访 Binder 身份；
3. 内部 `BiometricService` 用 `PreAuthInfo` 计算本次有资格参与的传感器（强度、录入、锁定、设备策略、相机隐私等），创建 `AuthSession`；
4. `FingerprintService`/`FaceService` 的 provider 创建认证 client 放入该传感器的 `BiometricScheduler`，经 AIDL `ISession` 驱动 vendor HAL；
5. 采集、模板匹配与 Strong 认证的 HAT 生成在安全隔离环境（TEE）完成——HAL 进程本身不是 TEE。

从调用返回到传感器真正开始采集之间，还隔着预认证、cookie 握手和 SystemUI 显示 Prompt。排查"生物识别慢"时先分段定位时间落在哪一段，而不是把整条链路当成一个黑盒耗时。

**Q2: BiometricPrompt 认证进行中再次调用 authenticate()，旧请求会排队等待还是被替换？**

会被替换，不会排队。Android 17 的 `BiometricService` 只有一个 `mAuthSession`：新请求会强制取消旧会话、关闭旧 UI，旧客户端通过回调收到取消；官方参考文档也明确写出认证进行中再次调用会停止前一个客户端，被中断的客户端收到取消错误。排队只存在于每个传感器自己的 `BiometricScheduler`，那是单个传感器内部对录入、认证等操作的串行调度，不是所有应用请求的全局队列。

应用侧应避免在配置变化、重复点击或状态重组时快速"取消—重建—认证"：这种写法会产生界面重建、HAL 取消确认和 scheduler 队列清理开销，还会让一次用户操作产生多份互相覆盖的回调。页面销毁时是否取消要结合保留策略，`onDestroy()` 里只对真正结束的页面取消。

**Q3: BIOMETRIC_WEAK（Class 2）认证成功后，为什么拿不到可用于 Keystore 的 HardwareAuthToken？**

因为只有 Class 3（`BIOMETRIC_STRONG`）传感器生成的 HAT 才会被 Keystore 采纳：Class 2 可以进入 BiometricPrompt，但不支持 Keystore 的定时授权和单次操作授权，即使回传 token，`AuthSession.onAuthenticationSucceeded()` 也会将其丢弃。按官方能力表：

1. `BIOMETRIC_STRONG` / Class 3：BiometricPrompt、Keystore 时间窗授权、Keystore 单次操作授权都支持；
2. `BIOMETRIC_WEAK` / Class 2：仅 BiometricPrompt；
3. Class 1（convenience）：不进入公开 BiometricPrompt API；
4. `DEVICE_CREDENTIAL`（PIN/图案/密码）：全部支持。

边界：指纹、人脸是认证模态，Class 是安全等级——二维相机不自动等于 Class 2，深度相机也不自动等于 Class 3，强度由设备按 CDD 要求声明并通过测试决定；OEM 声明强度与运行时降级合并后不会比初始声明更强。Class 2 与 Class 3 的采集、录入和匹配都要求在安全隔离环境中完成，framework 只能看到事件、受限标识和 HAT，看不到原始模板。需要 `CryptoObject` 或密钥绑定时使用 `BIOMETRIC_STRONG`；`canAuthenticate()` 只是调用时刻的状态快照，不预留传感器也不保证稍后成功，最终结果以 `AuthenticationCallback` 为准。

**Q4: HAL 已报告生物识别匹配成功，为什么应用的成功回调还要再等一会儿？**

因为 Strong 传感器成功后系统还有收尾流程：`AuthSession` 暂存 HAT 并通知 SystemUI 播放成功或确认界面，等 SystemUI dismiss 后才把 HAT 交给 `KeyStoreAuthorization.addAuthToken()`，之后才调用应用的 `onAuthenticationSucceeded()` 并清理其余传感器。HAL 匹配成功不代表 App callback 已经执行。

三个"完成"时间点不能混用：

1. **HAL success**：secure matcher 已接受样本；
2. **SystemUI success**：Prompt 已进入成功或确认状态；
3. **App callback**：token 已处理、SystemUI 已 dismiss、回调已进入应用 Executor。

用户可能在成功动画开始时就认为认证完成，业务代码必须等 App callback；性能指标要写明采用哪个结束点。另一个边界：应用回调运行在调用方传入的 `Executor` 上，用 `getMainExecutor()` 时在回调里读数据库、访问网络或做复杂解密会占用主线程，重任务应交给后台执行器、仅把 UI 结果切回主线程。

**Q5: 人脸与指纹同时可用时，Android 17 的 BiometricPrompt 会做特征融合吗？两种模态的启动顺序如何协调？**

不做融合。framework 只协调各传感器的启动、UI、成功、失败和取消，不执行厂商级特征融合：任一传感器成功后记录 `mAuthenticatedSensorId` 并取消其余传感器（需要显式确认的部分场景除外），后到的成功或错误回调按当前 session/requestId 过滤。

启动顺序：`AuthSession` 等所有有资格传感器的 cookie 就绪后，先启动非指纹传感器并请求 SystemUI 显示 Prompt；指纹则等 SystemUI 入场动画完成（`onDialogAnimatedIn`）后才启动——源码注释说明这是为了避免指纹交互提示在对话框出现前露出。人脸可以先于 Prompt 界面运行。

推论与边界：双模态设备的总延迟不能写成 `max(人脸, 指纹)`——两个模态启动时刻不同，失败后的 UI 策略也不同；人脸的 `onAuthenticationFailed()` 是终止回调（进入可重试暂停），指纹的同名回调不是终止（operation 可继续等待下一次触摸）。framework 没有"所有认证 30–60 秒强制结束"的统一常量，超时可能来自 HAL error、sensor client 实现、SystemUI 交互状态或 scheduler watchdog，排查时记录实际 error 和 modality，不套用固定秒数。
