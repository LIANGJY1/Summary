# 平台 AI 服务

> 学习资料（文章模式沉淀）。边界：本文回答 Android AI 能力的交付层次、NNAPI 与 Neural Networks HAL 的应用边界、ML Kit GenAI/AICore 的运行条件，以及 AppFunctions 的调用约束。NNAPI 弃用状态以 Android 官方文档为准，平台 API 行为以 Android 17 AOSP 与 API 37 文档为准。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 设备上的 AI 能力可能由哪些层交付，应用选型时先判断什么？**

AI 能力由芯片、系统服务、运行时、应用模型或云服务中的不同组合提供。选型时应先确认执行位置、接口责任方和不可用时的降级路径。

1. 芯片和厂商软件：CPU、GPU、DSP/NPU、驱动及厂商 delegate 提供底层计算能力。不同设备的算子支持、精度、驱动质量与性能可能不同。
2. Android 平台：系统提供权限、Binder、Power/Thermal 等通用服务和神经网络相关平台接口。AOSP 提供这些接口不代表每台 Android 设备都预装大模型，也不代表平台向普通应用开放统一的 NPU 指令集。
3. Google 设备组件：兼容设备上的 AICore、Gemini Nano 和 ML Kit GenAI 提供共享模型或面向具体任务的高层 API。设备、模型版本、语言和功能支持范围并不统一。
4. 应用推理运行时：LiteRT、MediaPipe、自研运行时或厂商 SDK 负责模型加载和执行。运行时接入 delegate 不保证模型所有算子都落到目标硬件，也不保证性能、质量或功耗达到预期。
5. 云端服务：云端模型可提供设备本地未具备的模型或服务端算力，但需要处理网络依赖、服务可用性和数据传输边界。

delegate 是推理运行时到特定硬件后端的适配软件，不是与 NPU 并列的硬件层。两台手机即使都带 NPU，也可能支持不同算子、精度与模型路径。实际选型按以下顺序进行：

1. 明确模型在哪里运行，以及由系统、应用运行时、设备厂商还是云端交付。
2. 若使用共享端侧模型，评估 ML Kit GenAI/AICore 的设备与功能可用性、配额和版本差异。
3. 若使用自定义模型并要求离线控制，选择 LiteRT 等应用运行时，验证目标设备上的算子覆盖、delegate 性能和回退路径。
4. 若需要更大模型或在线知识，评估云端服务，并明确网络失败时的产品行为与数据处理方式。

**Q2: [learning] Android 17 上 NNAPI 还能用吗？Neural Networks HAL 存在是否代表应用模型运行在 NPU 上？**

NNAPI 仍可在支持它的设备上使用，但它自 Android 15 起已弃用，不应作为新应用的推荐推理接口。HAL 接口存在也不能证明某个应用模型实际在 NPU 上执行。

1. 应用 API 状态：NNAPI 的 `ANeuralNetworks*` API 仍可供既有应用调用，但 Android 官方预期未来多数设备可能只有 CPU 后端。性能敏感的新工作负载应评估官方迁移路径，例如 LiteRT 及其 GPU runtime。
2. HAL 职责：Neural Networks HAL 是平台与设备加速实现之间的接口，主要面向系统和硬件实现方。Android 17 AOSP 中仍可看到 AIDL HAL 定义，但普通应用不应把直接绑定 HAL AIDL 当成跨设备的应用接口。
3. 执行结果：HAL 存在不代表某一模型、设备或驱动具备相应加速能力。模型图中的不支持算子可能回退到 CPU。计算图拆分到 CPU 与加速器时，数据复制和同步也可能抵消加速收益。
4. 验证方法：应用经 LiteRT、ML Kit 或目标设备厂商公开支持的 SDK 接入推理能力。按目标设备和模型检查算子覆盖与实际执行后端，并测量端到端延迟、吞吐和功耗。
5. 架构判断：不能把“AIDL HAL 与 NNAPI 构成 Android 17 应用统一 AI 通路”作为设计前提。API 弃用、HAL 接口仍在源码中、设备实际有无加速器，是三个不同判断。

**Q3: [learning] 应用通过 ML Kit GenAI 使用 AICore/Gemini Nano 时，必须处理哪些运行条件？**

应用必须在调用前检查设备和模型能力，并为模型未就绪、前后台限制、配额与输出版本差异设计可恢复的处理。

1. 功能范围：ML Kit GenAI 提供摘要、校对、改写、图像描述、语音识别和 Prompt 等高层能力。不同 API 的设备支持、模型版本和语言范围不同，不能据此推断所有 Android 设备都提供所有功能。
2. 可用状态：根据具体 API 查询功能状态。功能可能不可用、可下载、正在下载或已可用。模型尚未就绪时，应提供等待、下载失败处理或其他降级路径，不要让界面无限等待。
3. 前台条件：ML Kit GenAI 推理只允许在应用处于最前台时执行。应用退到后台时，包括通过前台服务继续调用，可能收到 `BACKGROUND_USE_BLOCKED`。
4. 配额处理：AICore 为每个应用设置推理配额。短时间请求过多可能返回 `BUSY`，长期用量可能触发 `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`。对可重试错误使用退避策略，并提供取消和用户可见的失败处理。增加线程池不能绕开服务配额。
5. 模型版本：可读取设备上的基础模型名称。同一提示在不同 Gemini Nano 版本上可能产生不同输出，因此质量评估应覆盖支持的设备与模型版本，而不能只测一台设备。
6. 流式结果：流式接口可以更早返回部分结果，改善首个可见响应时间，但不会因此减少总推理工作或保证总耗时更短。
7. 交付边界：AICore 是 Google 在兼容设备上提供的系统组件，不是 Android 兼容性定义要求所有设备必须实现的通用服务。平台中的 `android.app.ondeviceintelligence` 系统 API 属于 Android 平台/厂商服务边界，不等同于 Google AICore 或 Gemini Nano 模型。

**Q4: [learning] AppFunctions 的调用边界是什么？为什么实现回调时必须正确安排线程？**

AppFunctions 让获准的系统或其他授权调用方发现并执行应用声明的功能，不是任意应用之间不受权限约束的通用 RPC 注册表。执行方式分为 manifest 服务实现与运行时注册实现，两者的线程和生命周期约定不同。

1. 权限边界：提供方声明 `AppFunctionService` 时，服务必须要求 `BIND_APP_FUNCTION_SERVICE`，以限制只有系统服务可以绑定。跨包发现或执行还受 `DISCOVER_APP_FUNCTIONS`、`EXECUTE_APP_FUNCTIONS`、系统级权限及包可见性约束，不能把函数暴露当作无条件公开。
2. 服务回调：服务实现 `AppFunctionService.onExecuteFunction()` 时，Android 会在主线程调用它。该方法收到请求并通过 callback 返回结果。耗时 I/O、数据库访问和模型推理要切到工作线程，否则会阻塞应用主线程。实现还应处理异常并确保回调完成。
3. 运行时注册：Android 17/API 37 的 `AppFunctionManager.registerAppFunction()` 接受函数标识、`Executor` 和实现对象。函数仍须在应用级 `android.app.appfunctions` 元数据中声明。该实现按注册时传入的 `Executor` 执行，不能套用服务回调的“固定主线程”结论。
4. 取消与结果：运行时 `AppFunction.onExecuteAppFunction()` 收到 `CancellationSignal`，应尽可能响应取消，并通过 `OutcomeReceiver` 恰好返回一次成功结果或错误。服务形式使用其 callback 契约。要按所用 API 的契约处理，不要混用两个入口的参数与回调类型。
5. 生命周期：运行时注册受注册进程和 `Context` 生命周期限制。调用方应保留 `AppFunctionRegistration` 并在功能不再有效时调用 `unregister()`。若在 Activity 或服务生命周期结束后仍持有注册，可能造成资源泄漏和意外调用。
6. 职责边界：AppFunctions 负责功能声明、发现与受控调用，不负责动态模型加载、模型版本回滚或推理调度。函数内部可以选择 AICore、自带模型或云端服务，但需由提供方自行实现相应的可用性、取消和错误处理。
