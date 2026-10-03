# 音频延迟与应用实践

> 学习资料（文章模式沉淀）。主线：应用侧的音频延迟口径与实测方法、控制面和数据面分工、FAST 轨接纳条件、AAudio 路径回退、数据回调纪律与 xrun 归因、Offload 取舍与收益验证、AudioTrack/SoundPool 使用语义、AAudio 缓冲调优。Q1–Q5 由 [../03-ui/04-window-system.md](../03-ui/04-window-system.md) 的音频部分迁入，原材料来自 android-internals-wiki §1.20，并已按 Android 13 公开 AOSP 实现复核。Q6–Q7 由 [../14-cpu-power/03-app-power-practice.md](../14-cpu-power/03-app-power-practice.md) 迁入，Android 17 与 AAOS 13 的差异在题内标明。音频子系统机制见 [01-aosp-audio.md](01-aosp-audio.md)。蓝牙音频见 [05-bluetooth-audio.md](05-bluetooth-audio.md)。Q 序列即结构，供 atlas 同源直读。

**Q1: 48 kHz 下 240 帧的缓冲区就是 5 ms 输出延迟吗？设备声明低延迟能证明当前路由够快吗？**

240 帧在 48 kHz 下只代表该缓冲区可承载约 5 ms 音频，不等于端到端输出延迟。设备功能标记描述兼容性承诺，不能代替目标路由上的实测。

1. **缓冲区时长**：按 `帧数 ÷ 采样率` 换算，240 ÷ 48,000 秒约为 5 ms。端到端延迟还叠加应用已排队数据、客户端与服务端队列、混音周期、HAL/驱动队列、DSP 处理、Codec 或无线传输缓冲及调度抖动。各输出档和路由的队列深度不同，因此“双缓冲一律乘 2”不是通用公式。
2. **设备标记**：`FEATURE_AUDIO_LOW_LATENCY` 对应连续输出延迟不高于 45 ms。`FEATURE_AUDIO_PRO` 对应连续往返延迟不高于 20 ms，并以前者为前提。这些标记不保证当前蓝牙耳机、启用音效或系统负载下仍达到同样结果。Android 没有通用运行时 API 可直接查询任意路由的声学延迟。依据 Android NDK《Audio latency》指南和 CDD 音频延迟要求。
3. **实测口径**：分别报告输出延迟、输入延迟、往返延迟和预热延迟。播放写入耗时与 `AudioTimestamp` 不是声学往返测量。应在目标设备和具体路由上做回环测试，并同时报告中位数和尾部延迟。

**Q2: 一条 AudioTrack 从创建到出声，控制面和数据面分别走哪里？为什么每个缓冲区并不会各经过一次 Binder？**

Binder 承载音轨创建、策略协商和控制请求，连续采样数据走共享缓冲或 MMAP 通路。音频样本不会因为应用每次写入一个缓冲就重新经过一次 Binder。

1. **创建与路由控制**：AudioTrack/AAudio 请求建立流时，AudioPolicyService 根据属性、设备与策略选择输出端点和配置，AudioFlinger 或对应服务再创建客户端轨道并打开后续输出路径。路由改变可能触发流重配置、旧端点关闭和新端点启动，因此切换瞬间可能短暂静音。
2. **普通播放数据**：AudioTrack 客户端与服务端通过共享内存环形缓冲交换高频 PCM 数据。AudioFlinger 的输出线程读取轨道数据，完成必要混音后向 Audio HAL 写入，再由驱动和设备端继续处理。
3. **不同输出线程**：MixerThread 承载通用软件混音，FastMixer 为符合条件的快速轨道提供较短周期处理。DirectOutputThread 与 OffloadThread 可绕过通用混音，分别承载直通和卸载路径。MMAP 路径让应用与音频端点访问映射缓冲，数据路径不同于普通 MixerThread。
4. **采集数据**：RecordThread 等输入线程从 HAL 获取采样，再通过客户端与服务端共享缓冲交给应用。低延迟采集也可能使用 FastCapture 或 MMAP 路径，具体取决于设备能力和协商结果。
5. **车机末端**：AAOS 的策略可把流路由到 BUS 端点。Audio HAL 将数据交给驱动、DSP 或功放，最终从对应扬声器发声。Direct、Offload 或 MMAP 会改变中间数据通路，但仍需要系统策略与 HAL 配合。

**Q3: 应用请求低延迟轨却被降级为普通混音，AudioFlinger 按什么条件接纳 FAST？为什么默认只有 7 条应用快速音轨？**

低延迟请求只是对输出路径的偏好，AudioFlinger 根据轨道参数、输出线程能力和空闲槽位决定是否接纳 FAST。Android 13 AOSP 默认配置最多有 8 个快速槽位，其中一个留给 Normal Mixer 子混音，所以通常最多有 7 条应用快速轨。

1. **轨道与输出条件**：数据格式、采样率和声道布局必须适合目标快速输出，通常要求线性 PCM、匹配输出采样率且无需不支持的转换。目标输出还必须配置并启用快速混音路径。
2. **处理链条件**：轨道及其音效链不能要求与快速路径不兼容的处理。是否能接入要看目标版本的 AudioFlinger 实现和设备输出配置，不能仅凭应用传入低延迟标志判断。
3. **槽位条件**：Android 13 AOSP `FastMixerState.h` 默认 `kDefaultFastTracks=8`，有效上下限为 2 到 32。索引 0 留给普通 Mixer 的子混音。槽位分配完成后，新轨没有空位就会降级。
4. **设备覆盖与线程供数**：厂商可用只读属性 `ro.audio.max_fast_tracks` 在 2 到 32 范围内覆盖总槽位数。属性缺省、格式错误或越界时，AudioFlinger 采用 AOSP 默认值 8。FastMixer 的较高实时调度优先级能减少线程等待，但它仍会等待 HAL 写入。应用供数线程没有按时产出数据，快速轨仍会欠载。打开轨道后应读回实际参数并结合 `dumpsys media.audio_flinger` 判断路径。依据 Android 13 AOSP `FastMixerState.h`、`FastMixerState.cpp` 和 `PlaybackTracks.h`。

**Q4: 用 AAudio 写的应用一定绕过 AudioFlinger 吗？请求 EXCLUSIVE 后实际得到 SHARED 又说明什么？**

AAudio 是面向高性能音频的 API，不等于固定使用 MMAP 或绕过 AudioFlinger。请求的性能模式和共享模式都可能因设备能力、占用情况与系统策略而协商成其他实际路径。

1. **MMAP 条件**：MMAP 需要 Audio HAL、内核驱动和系统配置共同支持。AAudio 的 `AAUDIO_POLICY_AUTO` 会先尝试 MMAP，不可用或打开失败时可走传统框架数据路径。`AAUDIO_POLICY_ALWAYS` 要求使用 MMAP，条件不满足时打开失败。`AAUDIO_POLICY_NEVER` 禁用 MMAP。
2. **共享模式结果**：`AAUDIO_SHARING_MODE_EXCLUSIVE` 表示该流独占相应音频端点，通常延迟最低，也更容易因设备已占用而无法建立或被断开。`AAUDIO_SHARING_MODE_SHARED` 允许多个流由 AAudio 服务混合。应在流打开后读取实际 sharing mode，而不是把 builder 请求当成结果。
3. **核验数据路径**：`AAudioStream_isMMapUsed()` 从 API 36 起才是公开 NDK API。Android 13 应结合设备能力、AAudio 日志和系统音频转储判断，不能把非公开测试接口当成应用契约。即使走 MMAP，创建、路由和错误恢复仍需要系统音频服务与 HAL 协调。依据 AOSP《AAudio and MMAP》文档与 NDK API 声明。
4. **封装选择**：Oboe 统一不同 Android 版本的原生音频 API 选择，在 AAudio 不可用的平台可回退 OpenSL ES。封装不能让不支持 MMAP 的 HAL 或驱动凭空获得 MMAP 能力。

**Q5: 低延迟音频回调应避免什么？xrun 计数增长就一定是缓冲区太小吗？**

高优先级数据回调必须在每个短周期内及时交付固定帧数，避免分配内存、阻塞等待和耗时 I/O。xrun 表示应用与音频端点未能按时交接数据，缓冲过小只是可能原因之一。

1. **回调中避免阻塞工作**：不要做文件、网络或 Binder I/O，不要等待互斥锁或条件变量，不要分配/释放内存，不要执行大量日志或重计算，也不要在回调中再次读写触发该回调的同一条流。
2. **隔离耗时工作**：把解码、网络和其他不可预测工作放到普通 worker 线程。回调只从有界队列取可用数据并完成轻量处理。生产者和消费者应明确环形缓冲容量、索引内存序及欠载时的填零策略。
3. **区分计数含义**：API 26 起的 AAudio `getXRunCount()` 汇总输出欠载与输入溢出。API 24 起的 AudioTrack `getUnderrunCount()` 统计应用级写入缓冲欠载，不等价于整条音频路径所有故障的总数。部分输入设备不支持 xrun 计数，可能始终返回 0。
4. **按证据归因**：记录测试前后的计数变化并采集 Perfetto 线程调度轨迹。若回调长期 runnable 却拿不到 CPU，先查调度和负载。回调按时完成而端点仍欠载时，再查缓冲设置、HAL 写入、路由切换和输入输出时钟漂移。增大缓冲能增加调度容忍度，但会提高延迟。

**Q6: 长音频播放什么时候值得启用 offload？怎样确认实际走了 offload？**

Offload 适合长时间、低交互且受支持的媒体播放，因为它可让解码和播放调度由音频硬件路径承担，使应用线程及部分框架数据管道休眠。请求成功与否取决于音频用途、编码格式、设备能力和当前路由，必须检查实际状态。

1. **适用场景**：锁屏音乐、播客和有声书通常更适合 offload。短音效、实时交互与优先追求最低延迟的场景通常不适合，因为 offload 面向省电并可能使用更深队列。
2. **准入条件**：Android 的 offload 播放要求媒体用途。应用可用 `AudioManager.isOffloadedPlaybackSupported(format, attributes)` 查询具体格式和属性组合，并通过 `AudioTrack.Builder.setOffloadedPlayback(true)` 请求 offload。支持 PCM 与否也由设备对具体组合的查询结果决定，不能只按文件后缀判断。
3. **实际观察**：Java AudioTrack 可用 API 29 起的 `isOffloadedPlayback()` 检查该轨是否以 offload 方式构建。Media3 可监听 `onOffloadedPlayback()` 和 `onSleepingForOffloadChanged()`，这两个接口标注为 `@UnstableApi`，升级 Media3 后应重新核对。AAudio 的 `AAUDIO_PERFORMANCE_MODE_POWER_SAVING_OFFLOADED` 是 API 36 新增模式，较低版本没有这个枚举值，不能无条件引用。
4. **代价与回退**：offload 对 seek、flush、播放速度、gapless、音效和蓝牙路由等组合的支持因平台与设备而异。应针对产品必需的功能组合检查回退或 teardown 行为，不能把某一路径可用推断为所有组合都兼容。

**Q7: 怎样验证 offload 的省电收益？为什么发布开关要按功能组合拆分？**

采用同设备、同内容和同路由的 A/B 对照，并同时测量能耗与播放质量。开关按组合拆分是因为回退和故障往往只发生在特定路由、格式或播放功能组合中。

1. **固定实验条件**：实验组和对照组使用相同设备、媒体、路由、音量、网络及屏幕状态，仅改变 offload 策略。记录构建版本、编码格式、设备和路由，避免比较不同硬件路径。
2. **观察成对指标**：能耗侧区分解码线程与写入线程 CPU 时间、音频线程唤醒间隔、CPU 频率与 idle 状态、batterystats UID 归因及 WakeLock。体验侧记录 underrun、seek 精度、播放恢复和人工重启等指标。省电收益不能以体验退化换取。
3. **分组合发布**：长音频且设备支持的组合可先小流量验证。蓝牙、gapless、章节 seek、广告插入、倍速和跳过静音等组合应分别观察。短音效、实时交互和目标设备异常率高的组合应禁用或回退。
4. **记录回退证据**：至少埋点请求状态、实际 offload 状态、路由和回退/teardown 原因，例如格式不支持、音效启用、倍速或路由变化。若使用 Android API 37 的 `AudioTrack.flushWrittenFramesFromPosition()`，应作为单独能力组合验证其设备支持和时序行为，而不是视作所有 offload 设备都具备的基础能力。

**Q8: AudioTrack.write(WRITE_BLOCKING) 返回后，数据到哪了？为什么可能只写入一部分？**

Streaming 模式的阻塞 `write()` 通常等到请求数据已排入播放路径后返回，但这不表示数据已经从扬声器播放。返回短计数表示只有部分数据成功交给 AudioTrack，调用方必须按当前 overload 的返回单位处理余量。

1. **入队不等于播完**：播放器端的写入位置领先于呈现位置。实际播放时刻由输出路径和端点决定，应用可用时间戳或播放头信息观察进度，但不能把 write 返回时刻当作声学输出时刻。
2. **短写条件**：即使是 `WRITE_BLOCKING`，如果写入开始时轨道已停止或暂停、另一线程在写入期间调用 stop/pause，或发生 I/O 错误，也可能提前返回。`WRITE_NON_BLOCKING` 本来就允许只写入当前可接纳的数据量。
3. **调用方处理**：检查返回值和错误码。部分写入时从已消费的位置继续写，遇到 `ERROR_DEAD_OBJECT` 等不可恢复错误时重建 AudioTrack。字节数组、short 数组和 float 数组 overload 的计量单位不同，不能混用偏移量或余量算法。

**Q9: AudioTrack 要立即停止时该用 stop() 吗？静态模式的短音效怎样重播？**

Streaming 轨道的 `stop()` 会等已经写入的数据播放完再停止。要立刻丢弃未播放数据，应先 `pause()` 再 `flush()`。静态轨道则通过重置播放头重新播放已加载的数据。

1. **停止 streaming 播放**：`stop()` 保留已排队数据直到播放结束。`pause()` 会暂停但不丢弃队列，随后 `play()` 可以继续播放。
2. **立即丢弃队列**：Streaming 模式在 paused 或 stopped 状态下调用 `flush()`，丢弃尚未播放的数据。`flush()` 不用于清除静态模式中已加载的样本。只调用 `pause()` 不会清空队列。
3. **重播静态缓冲**：静态模式先把短音频写入一次。停止或暂停后调用 `reloadStaticData()` 可将播放头回到起点，再调用 `play()`。从 API 23 起，该方法也会把 `getPlaybackHeadPosition()` 重置为 0。更早版本的播放头重置行为未指定。
4. **静态循环和定位**：`setLoopPoints()` 与 `setPlaybackHeadPosition()` 只适用于停止或暂停状态下的 `MODE_STATIC` 轨道。它们不能用来定位 streaming 缓冲。若静态短音效重复后不响或位置错乱，先核对轨道模式、状态和播放头。

**Q10: SoundPool 适合什么声音？为什么 load() 完成前调用 play() 可能无声？**

SoundPool 面向需要快速触发的短音效：`load()` 会异步解码样本，必须等成功完成后再播放。长内容应使用具备流式播放和媒体状态管理能力的播放器。

1. **加载闸门**：`load()` 立即返回 soundID，但解码仍在后台进行。通过 `OnLoadCompleteListener` 检查对应 sampleId 的 status 为 0 后再调用 `play()`。加载失败或尚未完成时，播放请求可能没有有效样本可用。
2. **内存与时长**：SoundPool 将样本预解码为 16 位 PCM，每个已解码样本最多占 1 MiB，超限部分会被截断。1 MiB 对 44.1 kHz 双声道约为 5.6 秒，对低采样率或单声道可容纳更长内容。
3. **并发驱逐**：构造器中的 `maxStreams` 限制同一 SoundPool 同时活动的流数。超过上限时，`play()` 的优先级参与淘汰决策，低优先级先停，同优先级则较早启动的流先停。若新流优先级低于所有在播流，它可能无法启动。
4. **参数边界**：`load(..., priority)` 中的 priority 参数当前没有效果。决定超限时淘汰顺序的是 `play()` 的 priority。密集短音效可选 SoundPool，长内容用 MediaPlayer/ExoPlayer，最低延迟的实时合成用 AAudio。依据 Android `SoundPool` API 文档。

**Q11: AAudio 调缓冲时，framesPerBurst、buffer size 和 capacity 各是什么关系？怎样用 xrun 找到合适缓冲？**

`framesPerBurst` 是应用每次回调或读写的推荐帧数，buffer size 是当前可用缓冲目标，capacity 是该流缓冲区的最大容量。低延迟调优应从小而安全的 burst 整数倍开始，按欠载证据增大，而不是把三者混为一谈。

1. **记录已协商值**：流打开后读取 `AAudioStream_getFramesPerBurst()`、当前 `AAudioStream_getBufferSizeInFrames()` 和最大 `AAudioStream_getBufferCapacityInFrames()`。输入流与输出流、不同路由的 burst 和缓冲行为可能不同，不能把一个设备的数值写死给所有流。
2. **从推荐值起步**：Android 官方低延迟示例建议输出缓冲先设为两个 burst，并保持 burst 的整数倍通常更高效。AAudio 默认实际缓冲可能更大，应用需要根据流打开后的读回值判断真实配置。
3. **按欠载逐步增加**：在稳定回调负载下比较 `AAudioStream_getXRunCount()` 的增量。出现输出欠载时每次增加一个 burst，再留出观测窗口确认计数是否停止增长。更大的缓冲提高调度容忍度，也会增加延迟。
4. **区分容量与自动调节**：`AAudioStreamBuilder_setBufferCapacityInFrames()` 在打开流前请求最大容量，省略时默认为 `AAUDIO_UNSPECIFIED`，由系统选择。打开后的实际 capacity 可能与请求不同。它不等于把当前 buffer size 设为该值。应用应通过 `AAudioStream_setBufferSizeInFrames()` 调整当前大小。若使用 Oboe LatencyTuner 自动调节，不要再由另一段代码同时手工改缓冲大小，以免两套策略互相覆盖。依据 AAudio NDK API 文档。
