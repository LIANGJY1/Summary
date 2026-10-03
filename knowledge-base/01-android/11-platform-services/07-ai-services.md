# 平台 AI 服务

> 学习资料（文章模式沉淀）。边界：本文回答"端侧 AI 能力的交付分层、NNAPI/NN HAL 现状、AICore 与 AppFunctions 的运行条件"。源文档：android-internals-wiki §1.29（Android 17 语境），NNAPI 弃用状态已与官方文档核对。Q 序列即结构，供 atlas 同源直读。

**Q1: 同样叫"AI 手机"，能力可能由哪几层交付？选型时先回答什么？**

先确定每一层由谁交付，因为接口名称相似不代表执行位置、数据边界和可用性相同。五层交付：

1. **芯片与厂商软件**：CPU、GPU、DSP/NPU、驱动、厂商 delegate——不保证同一模型在不同设备上的算子覆盖与性能一致；
2. **AOSP Android 17**：AppFunctions、Binder、权限、Power/Thermal API、Neural Networks HAL——不保证预装大模型或向普通应用开放统一 NPU 指令集；
3. **Google 设备组件**：AICore、Gemini Nano、ML Kit GenAI——不保证所有 Android 17 设备可用或不同 Nano 版本输出一致；
4. **应用推理运行时**：LiteRT、MediaPipe、自研或厂商 SDK——不保证自动得到最优 delegate、质量或功耗；
5. **云端模型**：更大模型与服务端算力——不保证离线可用或数据不出设备。

delegate 是推理运行时连接特定硬件后端的适配器，属于软件，不能与 NPU 等硬件单元并列；即使两台手机都有 NPU，支持的精度、算子与驱动质量也可能不同。

选型时先回答三个问题：能力由谁交付、模型在哪里运行、失败后由谁兜底。用共享端侧模型选 ML Kit GenAI/AICore（应用不打包模型，但要处理可用性与配额）；要自定义模型与离线控制选 LiteRT 自带模型（应用负责模型交付、delegate 覆盖率验证与后端回退）；更大模型与在线知识走云端。同一系统版本下 AI 能力仍可能不同，应用要检测具体 API 与模型可用性并准备回退。

**Q2: Android 17 上 NNAPI 还能用吗？Neural Networks HAL 存在能说明应用模型跑在 NPU 上吗？**

NNAPI NDK API 自 Android 15（API 35）起已弃用，官方迁移说明提醒未来多数设备可能只有 CPU 后端，性能敏感负载应迁移到其他方案（如 LiteRT GPU 运行时），新项目不应再把 `ANeuralNetworks*` 当作 Android 17 的推荐应用接口。Neural Networks HAL 仍受支持（`android-17.0.0_r1` 保留 `hardware/interfaces/neuralnetworks/aidl`），但它面向系统实现者和硬件厂商，普通应用不应直接绑定 HAL AIDL。

HAL 存在也不能证明某个应用模型会完整运行在 NPU 上：不受支持的算子会回退 CPU，计算图被分段到 CPU 与加速器之间时，频繁同步和数据复制甚至可能让混合执行慢于纯 CPU。

做法：应用经 LiteRT、ML Kit 或厂商公开 SDK 使用推理能力；评估 delegate 时按设备验证算子覆盖率（多少算子真正交给目标加速器），并为失败或性能倒退准备后端回退。"AIDL HAL + NNAPI 是 Android 17 应用统一 AI 通路"的说法已经过时。

**Q3: 通过 AICore 使用 ML Kit GenAI（Gemini Nano），应用必须处理哪些运行条件？**

AICore 路线让应用使用设备共享的 Gemini Nano 能力（摘要、校对、改写、图像描述、语音识别、Prompt API），应用无需把基础模型打进 APK，但接入时必须处理：

1. **功能可用性**：不同 API、设备和模型版本支持范围不同，调用前用对应 API 的功能状态检查；
2. **模型准备状态**：新设备或 AICore 重置后模型可能未就绪，应显示可恢复状态而不是卡住等待；
3. **前台限制**：当前只允许最前台、用户正在交互的应用使用 GenAI 推理，前台服务不能绕过；
4. **配额**：短时间请求过多返回 `BUSY`，长期使用还有每应用电量配额错误，重试要退避并允许取消；
5. **模型版本差异**：应用可读取基础模型名称，同一提示在不同版本输出可能不同，质量测试不能只覆盖一台设备；
6. **流式结果**：流式缩短首个可见结果的等待，但不减少总计算量。

边界：AICore 是 Google 在兼容设备上交付的系统组件，不是 CDD 要求的通用服务；Android 17 源码中的 `android.app.ondeviceintelligence`（ODI）系统 API 是平台或厂商的服务边界，不等同于 Google AICore 或 Gemini Nano 模型本身。工程上还应为温度、内存紧张和后台场景设计停止与降级；AICore 自己的每应用请求与电量配额，不会因为应用加大线程池而放宽。

**Q4: AppFunctions 的调用边界是什么？实现 onExecuteFunction 为什么必须把耗时工作切出主线程？**

AppFunctions 是把应用能力暴露给持有相应权限的系统级智能代理的跨应用发现与执行框架，不是任意应用互调的通用 RPC 注册表：提供方用 `AppFunctionService` 或运行时注册实现函数，服务必须声明 `BIND_APP_FUNCTION_SERVICE` 且只有 `system_server` 可以绑定，跨包执行受 `EXECUTE_APP_FUNCTIONS` 或系统级权限约束。

主线程约束是 Android 17 源码明确标注的：`onExecuteFunction` 带 `@MainThread`，回调从主线程进入，耗时 I/O、数据库和模型推理必须切到工作线程，响应 `CancellationSignal`，并通过 `callback` 返回成功或错误——否则智能代理的每次调用都会卡住应用主线程。

边界：Android 17 新增运行时注册，只在相应进程与 `Context` 生命周期内有效，调用者要保存 `AppFunctionRegistration` 并在不再需要时 `unregister()`；另有 Activity 或全局作用域、函数状态观察等能力。AppFunctions 不负责动态模型加载、模型版本回滚或推理调度——函数内部用 AICore、自带模型还是云端，是提供方自己的实现选择。
