# 音频延迟与应用实践

> 学习资料（文章模式沉淀）。主线：应用侧的音频延迟口径与实测方法、控制面与数据面分工、FAST 轨接纳条件、AAudio 路径回退、数据回调纪律与 xrun 归因、Offload 取舍与收益验证、AudioTrack/SoundPool 使用语义、AAudio 缓冲调优。Q1–Q5 转自 [../03-ui/04-window-system.md](../03-ui/04-window-system.md)（原「显示窗口与音频链路」册的音频部分，2026-09-28 迁入；android-internals-wiki §1.20 来源，机制按本地 AAOS13 源码核对），Q6–Q7 转自 [../16-app-practice/15-power-practice.md](../16-app-practice/15-power-practice.md)（材料 Android 17 口径与 AAOS13 差异已随题标注）。音频子系统机制见 [01-aosp-audio.md](01-aosp-audio.md)；蓝牙音频见 [05-bluetooth-audio.md](05-bluetooth-audio.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 48 kHz 下 240 帧的缓冲区就是 5 ms 输出延迟吗？FEATURE_AUDIO_LOW_LATENCY 声明为 true 能证明当前设备延迟低吗？**

都不能。buffer_duration = frame_count / sample_rate 只描述这一个缓冲区承载的音频时长；端到端输出延迟还要叠加应用已排队数据、client/server 共享队列、mixer 周期、HAL 与驱动队列、DSP 算法、Codec/无线传输缓冲和调度抖动，不同输出配置档与路由的队列深度不同，也不存在"双缓冲所以乘 2"的通用公式。系统特性只是设备声明的能力：FEATURE_AUDIO_LOW_LATENCY 声明连续输出延迟不高于 45 ms，FEATURE_AUDIO_PRO 声明往返不高于 20 ms 且以前者为前提，它们都不是对当前蓝牙耳机、音效配置或系统负载的实测。延迟要分四种口径：输出、输入、往返与预热（首帧后从待机启动）；播放写入耗时与 AudioTimestamp 都不等于声学往返时间。要量化必须在目标设备和路由上做回环测试，并分开报告中位数与尾部。

**Q2: 一条 AudioTrack 从创建到出声，控制面和数据面分别走哪里？"每个音频缓冲区都经过一次 Binder"错在哪？**

控制面走 Binder：AudioTrack/AudioRecord/AAudio 经 AudioPolicyService 选择设备、输出配置档（primary、fast、deep-buffer、direct、offload、MMAP 等）与路由，由 AudioFlinger（运行在 audioserver，自 Android 7 从 mediaserver 拆出）创建对应线程与 track，Audio HAL 打开 stream。数据面是共享内存：高频 PCM 样本经 client/server 共享环形缓冲送 AudioFlinger 混音线程，再写向 HAL 与驱动，Binder 不承载逐缓冲数据。AudioFlinger 按输出类型使用不同线程：MixerThread 通用混音、FastMixer 短周期快速混音、DirectOutput/Offload 绕过通用软件混音、MmapThread 走内存映射，输入侧对应 RecordThread、FastCapture 与 MmapCapture。路由切换伴随流重配置、旧端点关闭与新端点启动，可能出现短暂静音；分析切换问题时应把切换前后当作两条不同路径。

补充（设备面与 AAOS 语境，自原 11 册同题合并）：Audio HAL 将 bus 数据交给驱动、DSP 或功放，最终从指定扬声器发声；若策略选 Direct/Offload/MMAP，数据通路会变化，但仍需策略与 HAL 配合。

**Q3: 应用请求 PERFORMANCE_MODE_LOW_LATENCY 却被降级为普通混音：AudioFlinger 按什么条件接纳 FAST？为什么默认只有 7 条应用快速音轨？**

FAST 标志只是偏好，AudioFlinger 有最终决定权（AAOS13 源码核对 Threads.cpp 注释 "client expresses a preference for FAST, but we get the final say"）：需要同时满足数据是线性 PCM、采样率等于输出硬件采样率、声道布局无需昂贵的 downmix、该混音输出关联了 FastMixer、快速槽位有空位，且音效链不会移除 FAST。槽位由常量定义：kMinFastTracks=2、kMaxFastTracks=32、kDefaultFastTracks=8（AAOS13 源码核对 FastMixerState.h），索引 0 留给 Normal Mixer 的子混音，所以应用默认最多 7 条；厂商可用只读属性 ro.audio.max_fast_tracks 在 2–32 之间覆盖，槽位用完则新请求降级。FastMixer 以提升的实时调度优先级减少调度延迟，但仍会在 HAL write 处等待；应用供数线程也会被请求配置音频优先级，FastMixer 准时而应用没供数照样欠载。打开音轨后应读取实际配置，不能假定构建器请求原样生效。

**Q4: 用 AAudio 写的应用一定绕过了 AudioFlinger 吗？EXCLUSIVE 被降级说明什么？**

不一定。AAudio 是 API（Android 8.0 引入），同一套调用可落在不同数据路径：MMAP 需要 Audio HAL 与驱动声明并实现 MMAP/NOIRQ 能力、并有对应配置档，不可用或自动模式打开失败时回退传统 AudioFlinger 路径，所以"用了 AAudio"推不出"绕过了混音器"；`AAudioStream_isMMapUsed()` 自 API 36 才公开，Android 13 上要结合设备属性、AAudio 日志与 dumpsys 判断。EXCLUSIVE 表示应用直接写入与 ALSA 驱动共享的内存映射缓冲、绕过普通软件混音，延迟最低但端点被占用或不支持时会降级为 SHARED（多流共享端点、由系统侧混合），且独占的只是这个端点，系统声音可经其他端点继续播放。无论哪种模式，建流、权限、路由、时间戳与错误恢复仍走 AAudio 服务、AudioFlinger/AudioPolicy 与 HAL 的控制路径——MMAP 缩短的是数据面，不删除控制面；Oboe 只是把跨版本分支收进一个封装（AAudio 不可用时回退 OpenSL ES），不会让不支持的 HAL 凭空获得 MMAP。

**Q5: 音频数据回调里该避免什么？xrun 计数增长就一定是缓冲区太小吗？**

数据回调运行在高优先级线程，应避免 malloc/new 与不可控对象构造、文件/网络/Binder I/O、互斥锁与条件变量等待、停止或关闭当前流、在同一条流上再调 read/write，以及大量日志；正确分工是普通 worker 完成解码、网络等耗时工作，回调只从无锁环形缓冲取固定帧数做轻量 DSP——且生产者与消费者必须定义清容量、读写索引的内存序与欠载填零策略。xrun（输出欠载与输入溢出的统称）不一定是缓冲太小：回调等锁、线程长期 runnable 拿不到 CPU、HAL 阻塞、路由切换、输入输出时钟漂移都能造成；增大缓冲只是用更高延迟换调度容忍。证据链：getXRunCount() 与 AudioTrack.getUnderrunCount() 的前后差值，加 dumpsys media.audio_flinger 中的欠载计数，再配合 Perfetto 看回调线程是否在截止期前长期 runnable——计数增长且线程等调度先查调度，回调按时完成仍增长再查缓冲、HAL 写入与路由。

**Q6: 长音频播放什么时候值得开 Offload？怎么确认真正走了 offload 而不是"请求了就算"？**

三个特征同时满足才值得：播放时间长、屏幕关闭或界面参与少、媒体格式与设备硬件支持 offload——音乐、播客、有声书锁屏播放是典型；短音效、实时互动、低延迟场景禁用（offload 面向省电不面向低延迟）。收益来自把解码与输出交给专用硬件、一次写入更多数据后让应用线程与框架管道暂停、CPU 得以休眠；代价在 seek/flush 精度、音效（均衡器、空间音频、跳过静音）、倍速与蓝牙路由上的兼容限制。确认实际路径的方法：请求值不作数——AAudio 打开流后读 `AAudioStream_getPerformanceMode()` 是否等于 `AAUDIO_PERFORMANCE_MODE_POWER_SAVING_OFFLOADED`（API 36 起提供；AAOS13 源码核对 `AAudio.h` 只有 `NONE`/`POWER_SAVING`/`LOW_LATENCY` 三种，Android 13 无该模式）；Media3 用 `AudioOffloadPreferences` 表达偏好、用 `ExoPlayer.AudioOffloadListener` 的 `onOffloadedPlayback()`/`onSleepingForOffloadChanged()` 观察（`@UnstableApi`，升级需重新核对）；Android 17 另为 `AudioTrack` 增加 codec provenance 与按写入位置 flush 等能力。注意 PCM 路径也可能 offload，不能用"格式是 PCM"推断未启用。

**Q7: offload 的收益怎么验证？远程开关为什么按"功能组合"设计而不是一个布尔？**

同机 A/B：实验组与对照组同设备、同媒体、同路由、同音量、同网络与屏幕状态，只有 offload 开关不同；指标同时覆盖应用 CPU 时间（解码与写入线程单独看，不与埋点、歌词线程混算）、音频线程唤醒间隔、CPU 频率与 idle 状态、batterystats 的 UID 归因与 WakeLock，以及 underrun、seek 误差、手动重启等体验指标——省电不能以体验变差为代价。发布按功能组合分档：可开启（长音频、格式支持、无倍速/跳过静音、目标设备 A/B 通过）、小流量（蓝牙、gapless、章节 seek、广告插入等复杂组合按路由与格式分组验证）、禁用（短音效、实时互动、倍速刚需、目标设备异常率高）。远程开关按组合设计是因为失败集中在特定组合上：至少包含总开关、蓝牙单独开关、gapless 要求、倍速策略与 API 37 flush 单独实验，并记录 `offload_requested`、实际模式与回退原因（不支持格式、开启音效、倍速、路由变化）——回退原因决定哪组组合回到普通 PCM 路径，而不是全局一键关闭。

**Q8: AudioTrack.write(WRITE_BLOCKING) 返回之后，数据到哪了？返回值比请求量小时发生了什么？**

阻塞 write 只保证"数据已入队到共享缓冲"，不等于已播放——播放位置由 sink 决定，滞后于写入位置（write javadoc 与官方文档口径）。返回值可能小于请求量甚至为负：音轨被本线程或其他线程 `stop()`/`pause()` 打断、流出错时 write 提前返回（官方文档列明的行为）。因此多线程操作同一条流时必须检查返回值并处理部分写入，"write 一次不管"的代码在停止竞态下会静默丢数据。监控侧用 `getUnderrunCount()`（API 24 起）确认消费端是否断供。

**Q9: 想"立刻停"用 stop() 对吗？静态模式重复播放怎么做？**

streaming 模式的 `stop()` 会把已写入缓冲的数据播完才停（官方文档口径）；要立即停止并丢弃，用 `pause()` 后 `flush()`。`flush()` 只在 paused/stopped 状态且 streaming 模式下有效，`pause()` 单独用不丢数据、`play()` 会续播。静态模式不靠 write 供数：重复播放调用 `reloadStaticData()`（它会把 playback head 清零，AudioTrack.java:2330 核对）；`setLoopPoints()` 与 `setPlaybackHeadPosition()` 仅静态模式有效，且须在 stopped/paused 态调用。这三个接口的语义差异是"短音效重播有杂音/不响"类问题的常见根因。

**Q10: SoundPool 的适用边界是什么？"加载完成前播放"为什么无声？**

SoundPool 的设计是预解码短音效池：`load()` 异步解码进内存，未完成就 `play()` 得不到声音——用 `OnLoadCompleteListener`（SoundPool.java 核对）作为播放闸门，并缓存 soundID。并发受构造时的 maxStreams 限制，通道满时按 `play()` 传入的优先级驱逐低优先级流（官方文档口径）；官方建议单样本体量小（1 MB 量级量级 guidance）、时长短，长音频改用播放器组件——SoundPool 播长音频"十几秒就停"是误用不是 bug（社区案例口径）。 适用对照：低延迟密集叠加选 SoundPool，长内容选 MediaPlayer/ExoPlayer，最低延迟实时合成选 AAudio。

**Q11: AAudio 说"buffer 按 burst 对齐、欠载就加一个 burst"——具体怎么调？**

容量与缓冲分开：`setBufferCapacityInFrames()` 定容量（共享缓冲总量），实际缓冲从一帧 burst（`getFramesPerBurst()`）起步，欠载时按一个 burst 步进增加、以 `getUnderrunCount()` 停止增长为收敛信号（官方指南口径）；缓冲大小保持 burst 的整数倍效率最高。Oboe 的 LatencyTuner 自动执行这套调法，与手工 `setBufferSizeInFrames()` 混用会互相覆盖（Oboe issue 口径），二选一。性能模式上限受设备约束：Android 13 的 `AAudio.h` 只有 `NONE`/`POWER_SAVING`/`LOW_LATENCY` 三种（AAOS13 源码核对），`POWER_SAVING_OFFLOADED` 是 API 36 起的新值——低版本上按模式枚举写分支会编译不过或判空。
