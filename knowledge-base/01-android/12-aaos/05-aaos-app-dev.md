# Android Auto 与 AAOS 应用执行、模板生命周期和媒体性能

> 学习资料（文章模式沉淀）。边界：本文说明 Android Auto 与 Android Automotive OS（AAOS）的进程和 UI 责任边界，Car App Library 模板回调、刷新限制，以及媒体和导航应用的性能取证点。具体 Android 图形、音频和系统服务机制归各自主题文档。Car App Library 与 host 能力按 SDK、API 和平台版本分别判断。内容以 Android for Cars 官方文档截至 2026-10-04 的说明为准。Q 序列即结构，供 Atlas 同源直读。

**Q1: [learning] Android Auto 与 AAOS 的应用运行位置和 UI 绘制责任有什么不同？性能问题为什么要先确认代码在哪执行？**

Android Auto 的应用运行在连接的手机上，车机负责显示与输入，并通过 Android Auto host 与手机应用交互。AAOS 应用则直接安装并运行在车辆内的 Android 设备上。排查前先确认处理逻辑、模板 host、图形提交和网络请求分别在哪一端，否则容易把手机端延迟误判为车机 GPU 或 CarService 问题。

1. **Android Auto：**业务应用和 Android Auto 的应用 host 都在手机侧。host 发现兼容应用、管理其生命周期，并把应用提供的 template model 转成适合车载显示且遵守驾驶限制的界面。车机是显示、输入和音频端点，应用本身不拥有 Android Auto 与车机间的投射协议，也不能控制接收端重连算法。业务网络通常使用手机网络。
2. **AAOS：**应用进程、应用存储和车辆网络都在车内运行。车辆系统中的 CarService、VHAL 与 CarWatchdog 提供平台服务，模板应用由车内的系统模板 host 承载。直接安装在车内的应用可使用常规 Android 窗口能力，但具体功能仍受驾驶状态、产品配置和权限约束。
3. **UI 责任：**模板应用提交模板模型，由 host 决定布局并绘制车载 UI。地图应用的 Surface 或符合政策的停驻 Activity 则可由应用提交图形内容。应用进程绘制模板数据，不等于应用绘制模板最终像素。
4. **需要分别记录的版本：**Android SDK API level 描述运行平台 API。Car App API level 描述应用与 host 之间可用的车载模板契约，manifest 中的 `minCarApiLevel` 是应用声明的最低 host 能力。AndroidX Car App Library 版本决定客户端库 API。车机 OEM/host 软件版本决定实际部署能力。升级其中一项不会自动升级其他项。
5. **发布和 host 支持：**截至 2026-10，官方模板媒体应用说明 AAOS 17 及以上车辆会完整支持 Car App Library 媒体能力。旧 AAOS 车辆要核对其 host 能力。Android Auto 模板媒体应用仍处于 beta/early access 发布范围，不能只根据手机 Android 版本判断其可发布范围。
6. **性能定位：**Android Auto 的输入、手机应用处理、模板返回和车机显示构成跨设备链路。AAOS 通常是车内单设备链路。没有车端 trace 时，可用高速摄像和可识别的输入/画面标记测量触摸到可见反馈时间，但这种测量只能给出端到端时延，不能单独归因某一段。

**Q2: [learning] Car App Library 模板应用的冷启动怎样避免阻塞 onGetTemplate()？**

`onGetTemplate()` 是同步回调，host 需要它返回一个合法模板才能显示页面。因此它只应根据已准备好的界面状态快速构造模板，不应在回调中等待网络、数据库迁移、图片解码或路线计算。

1. **启动准备：**在应用服务或会话启动阶段恢复可用的轻量本地状态，并把耗时 I/O 与计算放到后台执行。若缓存不可用，先准备能够解释加载、空数据或错误状态的合法模板。
2. **模板构造：**`onGetTemplate()` 从当前 UI 状态读取数据并构建模板。避免同步网络请求、磁盘扫描、数据库迁移、图片解码和复杂路线计算，否则 host 等待回调期间界面无法及时呈现。
3. **后台结果回写：**网络或磁盘结果就绪后，在主线程更新 Screen 所依赖的界面状态，再调用 `Screen.invalidate()` 请求 host 重新取模板。Screen 对象不是线程安全的，因此其状态和 invalidate 调用应遵循 Car App Library 的线程约束。
4. **任务结束和断连：**Screen 被销毁或 Car App session 断开后，取消不再需要的请求和任务，避免过期结果重新触发模板刷新或继续占用资源。
5. **启动指标：**分别记录应用/CarAppService 启动到首次 `onGetTemplate()` 返回的时间，以及首次合法模板返回时间。这样能分清进程冷启动、应用初始化和模板构造的耗时。

**Q3: [learning] Car App Library 模板刷新、五步配额和内容上限怎样协同工作？**

`Screen.invalidate()` 只请求 host 重新获取模板，不是立即绘制，也不是动画时钟。host 会限制 UI 更新频率，并对模板导航流程应用步数配额。内容列表上限则应从当前 host 查询，三者分别约束刷新时机、任务流深度和单页内容数量。

1. **刷新回调：**Screen 处于至少 STARTED 状态时，调用 `invalidate()` 会请求 host 再次调用 `onGetTemplate()`。在新模板返回前，后续 invalidate 调用不会累积成多个独立请求。应在主线程调用，以避免与模板读取并发造成状态竞争。
2. **host 节流：**host 可以限制模板更新显示频率。短时间内提交多个模板时，host 可能只显示最后一个结果。回调被重新调用不保证每个中间模板都已经出现在屏幕上，因此不要用 invalidate 驱动动画，也不要对每个网络分片单独刷新。
3. **模板配额：**常规模板任务每个 task 最多显示五个模板。计数对象是返回给 host 的模板数，不是 Screen 实例数。模板返回相同类型且主要内容符合刷新规则时可能不消耗新步数。返回上一级、模板刷新和某些结束任务的模板会调整或重置配额。耗尽后继续发送新模板，host 可显示错误并关闭应用。具体刷新判定还要满足对应 template 类型规则。
4. **宿主差异：**Android Auto 和 AAOS 的 host 支持能力可能不同。较新 Car App API 提供 app-driven refresh 能力查询。应用若依赖超出传统五步限制的行为，应先查询 host 是否支持相应能力，并准备不支持时的常规刷新路径。
5. **内容上限：**通过 `ConstraintManager.getContentLimit(contentLimitType)` 查询 host 对某类模板的条目上限。参数 `contentLimitType` 用于选择列表、网格、地点列表、路线列表或 pane 等内容类别，返回值是该类别的当前上限，应用用它裁剪展示内容。该上限由 host 执行，客户端不能自行提高。远端调用失败可能抛出 HostException。
6. **驾驶限制：**分页、“更多”操作和模板顺序都必须满足对应类别及 host 的驾驶限制。条目上限不是允许展示任意内容的授权。
7. **取证指标：**记录 host 绑定到首个模板返回的时间、每次 `onGetTemplate()` 执行时间分布，以及 invalidate 到下一次模板返回的间隔。客户端 FrameTimeline 只能观察客户端自身的图形工作，不能覆盖 host 完整绘制或车机显示时延。

**Q4: [learning] 车载媒体应用应由哪些媒体组件提供数据和播放状态？Car App Library 模板与传统媒体服务是什么关系？**

车载媒体应用以 `MediaSession` 表示播放状态和控制能力，并通过 `MediaBrowserService` 或 `MediaLibraryService` 暴露可浏览的媒体内容。Car App Library 模板可以定制车载浏览和播放 UI，但不能取代这些媒体服务契约。

1. **浏览内容：**媒体浏览树为 host 提供根节点、分类和媒体条目。根节点和常用分类应尽量从本地索引快速返回。远端结果应分页获取，并能将网络错误表示成可恢复状态。
2. **播放状态：**播放器及时更新 `MediaSession` 中的播放状态、元数据和控制能力。host 重连后可通过会话恢复当前队列和播放位置。模板 UI 应消费会话状态，不要另维护一份彼此冲突的“正在播放”事实。
3. **模板体验：**Car App Library 媒体模板提供更可定制的浏览和播放 UI，但官方仍要求保留 `MediaSession`，并提供 `MediaBrowserService` 或 `MediaLibraryService`，以支持语音动作和其他媒体体验。传统 media browser UI 仍由 host 绘制。
4. **版本和发布：**模板媒体应用的 Car App API 最低级别和 host 能力必须按所用 API 版本声明与核对。Android Auto 模板媒体功能仍在 beta/early access 范围。AAOS 17 及以上 host 完整支持该能力，较旧车机应提供适当的媒体服务回退。
5. **封面资源：**按 host 实际需要的尺寸解码封面，并为内存和磁盘缓存设置上限。避免为每个媒体条目长期保存原始大图。
6. **播放线程：**不要在 UI 线程准备解码器，也不要等待 DRM 或网络操作完成。媒体播放还应处理音频焦点、duck、蓝牙或车载输出变化，以及 `ACTION_AUDIO_BECOMING_NOISY` 等输出设备变化事件。
7. **Android 15 行为：**目标 SDK 为 Android 15（API 35）及以上的应用，只有在前台顶层应用或运行前台服务时才能请求音频焦点。请求失败时不能继续假设已获得焦点并开始播放。

**Q5: [learning] 导航语音怎样选择车载音频焦点？定位与扬声器时延怎样分别取证？**

导航语音应声明导航引导用途并请求短时可 duck 焦点。导航位置与语音输出是两条相互作用但不同的链路，排查时要分别标记定位采样、路线匹配、音频焦点、播放和扬声器输出的时间点。

1. **焦点属性：**播放导航提示时，AudioAttributes 使用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，AudioFocusRequest 使用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`。前者标明声音用途，后者说明语音只短时占用焦点，并允许原焦点持有者降低音量继续播放。
2. **焦点结果：**只有成功取得焦点后才开始播放。音频策略可能依内容类型与系统场景选择自动 duck 或向原应用回调焦点变化。播客等 speech 内容可能暂停而不是自动降音量。导航应用仍须处理焦点请求失败、焦点变化和提示播放结束。
3. **避免过期提示：**路线计算或播报文本更新后，为语音任务关联会话或路线版本。播放前确认结果仍属于当前路线，避免旧计算结果在新路线建立后才播出。
4. **定位精度与功耗：**定位请求频率应满足导航精度与路线匹配需求，不要长期使用设备可提供的最高采样率。记录位置时间戳、精度和路线匹配耗时，才能区分定位波动与路线计算延迟。
5. **音频端到端时延：**扬声器时延可能经过应用、Android 音频栈、Android Auto host 或 AAOS CarAudioService、DSP 和车辆放大器。应用 trace 只覆盖其中一段，应通过各层时间戳或外部录音补足链路证据。
6. **驾驶中内容边界：**行驶时的视频等停驻体验必须服从平台支持类别和车辆停驻状态。屏幕尺寸足够或应用可以播放，不表示驾驶期间允许显示该内容。
