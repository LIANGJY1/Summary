# AAOS 应用开发要点

> 学习资料（文章模式沉淀）。边界：本文回答"Android Auto 与 AAOS 的执行边界、模板应用与地图/媒体应用的开发约束"。Q 序列即结构，供 atlas 同源直读。

**Q1: Android Auto 和 AAOS 有什么本质区别？性能问题为什么要先确定"谁在哪执行"？**

Android Auto 运行在手机上并把体验投射到兼容车机：应用与 host（手机上的 Android Auto 实现，负责发现应用、管理生命周期、把 Template 数据转换成符合驾驶限制的界面）都在手机，车机是显示、输入与音频端点，业务网络通常走手机；应用不拥有 USB/Wi-Fi/蓝牙投射协议，也不能接管车机接收端的重连算法。AAOS 是直接运行在车载硬件上的 Android：应用进程、存储、网络与 CarService/VHAL/CarWatchdog 都在车端，模板 host 是车机中的系统应用，允许停驻使用的 Activity 走常规 Android 渲染路径。UI 责任分三种：模板模型由客户端构建、布局绘制在 host，地图 Surface 与停驻 Activity 才是应用自己提交图形内容。

版本有四条轴要记录：Android SDK API level、Car App API level（manifest 的 minCarApiLevel 与 host 运行时支持）、androidx.car.app 库版本、host/OEM 软件版本——Android 17 不会自动开启某个模板，Jetpack 升级也不改变旧 host 能力；当前文档还按平台区分发布阶段（如 Car App Library 媒体体验在 Android 17 及以上 AAOS 完整支持、在 Android Auto 受早期访问与测试轨道限制），不能只用 Android API level 推断类别可用性。诊断"点击后卡了"要先拆阶段：Android Auto 是车机输入到手机 host 再到应用再返回模板再生成车机界面的跨设备链路，AAOS 是车机内的单设备链路；没有车端 trace 时用高速摄像与可识别的输入/画面标记测触摸到可见反馈。

**Q2: 模板应用的冷启动与刷新怎么做才不踩 host 的坑？**

onGetTemplate() 是同步取模接口，不能在其中等待网络、数据库迁移、图片解码或路线计算；host 只有拿到首个合法模板才能呈现内容。稳妥结构：进程启动时恢复一份小而完整的本地快照，onGetTemplate() 只读取不可变界面状态并构建模板，网络与磁盘工作放后台，新状态就绪后在主线程调用 Screen.invalidate()，页面销毁或 host 断开时取消无用请求。

刷新与配额是硬边界：invalidate() 只请求 host 再次调用 onGetTemplate()，发出一次刷新请求后、新模板返回前继续调用不产生新请求；host 还限制车屏更新频率，短时间内返回多个模板可能只显示末次结果——不能把 invalidate() 当动画时钟，也不应为每个网络分片刷新界面。host 限制一个任务最多五个模板，配额算 Template 数量而非 Screen 实例数，同类内容刷新、返回上一级与结束任务后重置各有规则，耗尽后继续发送新模板 host 可显示错误并关闭应用。各车允许展示的条目数不同：用 ConstraintManager.getContentLimit() 按运行时上限裁剪数据，客户端不能修改该上限；分页与"更多"操作也要服从对应模板的驾驶限制。指标记 service 绑定到首个模板返回的时间、每次 onGetTemplate() 执行时间分布、invalidate 请求到下一次模板返回的间隔；客户端进程的 FrameTimeline 覆盖不到 host 的完整绘制与车端显示。

**Q3: 车载媒体应用围绕什么组件组织？音频与导航语音要注意什么？**

媒体浏览与播放围绕 MediaBrowserService 或 Media3 的 MediaLibraryService 与 MediaSession 组织：browse tree 供 host 分层浏览，MediaSession 提供播放状态、队列、元数据与控制，Car App Library 媒体体验仍需按 host 能力保留这些组件或兼容路径。性能四块：浏览树根节点与常用分类从本地索引快速返回、远端结果分页加载且错误可恢复；封面按 host 所需尺寸解码并设内存磁盘缓存上限，不为每个条目保存原图；播放器状态先写 MediaSession、UI 作为状态消费者，host 重连后立即拿到队列与位置；音频正确处理焦点、duck、蓝牙或车载输出变化与 ACTION_AUDIO_BECOMING_NOISY，不在 UI 线程准备解码器或等待 DRM 与网络。

导航语音要请求 audio focus 并使用 AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE，官方建议的短时焦点类型是 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK；语音生成、媒体降音量与播放完成事件带会话 ID，避免重算路线后播出旧指令。定位请求频率服务于导航精度与路线匹配，不长期用设备最高采样率；记录定位时间戳、精度、路线匹配耗时与渲染使用的位置版本，才能区分定位波动、路线计算慢与画面更新慢。扬声器端到端时延还包含 Android 音频栈、host 或 CarAudioService、DSP 与车辆放大器，应用 trace 只覆盖其中一段。行驶中视频等停驻体验按平台支持与停驻状态处理，屏幕尺寸足够不等于允许播放。
