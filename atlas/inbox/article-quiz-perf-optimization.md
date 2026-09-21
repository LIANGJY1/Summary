kind: question
source: article-quiz: 性能优化方法论
q: 读取 /proc/[pid]/io 分析文件读写时，重点字段有哪些？为什么不能只看一次采样的总量？
ref: 重点字段：rchar、wchar、syscr、syscw、read_bytes、write_bytes、cancelled_write_bytes
  单次采样只是累计总量，无法定位问题发生在哪个时间段
  正确做法：固定间隔（如 3 秒）采集两次，用前后差值得到该时段读写增量
  增量才能定位启动或滑动期间高频、过量或不应发生的 I/O
  来源锚点: knowledge-base/android/性能优化.md##7.4 文件读写：关注增量而非总量





---

kind: question
source: article-quiz: 性能优化方法论
q: gfxinfo framestats 的卡顿统计包含哪些关键字段？文中示例数据说明为什么不能只看平均 FPS？
ref: 常见字段：Janky frames、50/90/95/99 分位帧耗时、Number Missed Vsync、Number High input latency、Number Slow UI thread、Number Frame deadline missed
  示例：总帧数 3、Janky frames 2（66.67%）、50 分位 117ms、90 分位 200ms
  平均 FPS 无法反映长尾卡顿；峰值与分位数才可能直接导致功能不可用
  来源锚点: knowledge-base/android/性能优化.md##7.3 帧率、掉帧与帧耗时





---

kind: question
source: article-quiz: 性能优化方法论
q: "优化三板斧"是哪三板？以主线程解析复杂 JSON 为例，说明为什么只把当前调用移到子线程不是完整方案。
ref: 三板斧：① 提供更好的库——针对高风险 API 提供统一、正确、易用的新 API
  ② 治理存量问题——全局搜索或运行时拦截已有问题，按优先级清理相似代码
  ③ 控制增量问题——在编译期、运行期或发布流程检测违规用法，必要时阻断构建并上报
  只移子线程是"头痛医头"：库中其他同步解析点还在，新人还会再犯
  完整方案 = 异步解析库 + 治理存量同步调用 + 打包或运行时阻止新增主线程同步解析
  来源锚点: knowledge-base/android/性能优化.md##4.2 设计方案前先写清楚代价





---

kind: question
source: article-quiz: 性能优化方法论
q: 资源紧张时的"开源"与"节流"各指什么方向？文中为什么强调两者都不能走极端？
ref: 开源：增加可用资源上限，如适配 64 位、合理使用多进程或 largeHeap；无标准 API 时才考虑经过充分验证的系统级方案
  节流：减少资源使用，如增加内存泄漏监控，复用图片缓存、线程池、对象池和 IPC 结果缓存
  开源不是无条件扩大上限，节流也不是简单删除功能
  两者都必须在收益、稳定性和维护成本之间权衡
  来源锚点: knowledge-base/android/性能优化.md##4.3 "开源"与"节流"





---

kind: question
source: article-quiz: 性能优化方法论
q: 为什么不能把 /proc/stat 里的 CPU jiffies 单次计数直接当成 CPU 使用率百分比？正确的 App CPU 使用率怎么算？
ref: jiffies 是开机以来的累计时间片计数，不是瞬时占比
  正确算法：间隔 Δt 前后各采样一次，用计数差除以 Δt 得到时间增量
  App 维度关注 /proc/[pid]/stat 的 utime、stime、cutime、cstime
  还要把 App 增量与整机增量（/proc/stat 各列）比较，才是一段时间内的 CPU 使用率
  来源锚点: knowledge-base/android/性能优化.md##7.1 CPU：区分整机和 App





---

kind: question
source: article-quiz: 性能优化方法论
q: 假如你要为车机 App 发起一次启动优化，按文中"先厘清目标、现状和风险"的方法，应如何确定优化优先级与成功标准？
ref: 先明确业务关键路径和成功标准，而不是先挑一个技术指标（检查清单第 1 条）
  按三类风险排优先级：业务流转风险（关键环节的次数/转化率/失败率）> 技术架构风险（启动、包大小、运行时加载、稳定性）> 技术实现风险
  优先级标准不是"哪个指标最容易优化"，而是"哪个风险对核心业务和用户体验影响最大"
  建立正常基线、异常阈值、报警策略与责任人后再动手（检查清单第 2 条）
  先定位根因再写方案，同时评估收益、代价、风险和回滚
  来源锚点: knowledge-base/android/性能优化.md##3.2 按三类风险确定优先级





---

kind: question
source: article-quiz: 性能优化方法论
q: 用实验而非感觉判断优化收益时，文中对对照组/实验组的设计有哪些要求？"反转实验"起什么作用？
ref: 按用户、设备、版本或业务场景划分对照组和实验组，比较大数据样本
  测试环境须可复现并记录：设备型号、系统版本、App 版本、内存/CPU/GPU 状态、温度、账号与业务数据、压力档位
  版本对比时保持环境和数据一致，否则结果无法归因于代码变更
  反转实验：交换两组开关后再测，若结果仍不符合逻辑，应怀疑其他变量而非把收益归因于优化方案
  目的：不把相关性误当因果性
  来源锚点: knowledge-base/android/性能优化.md##2.4 用实验而不是感觉判断收益





---

kind: question
source: article-quiz: 性能优化方法论
q: 文中反复强调"业务指标指导优化方向"，但车机/嵌入式场景往往没有下单转化率这类业务漏斗指标——这套方法论在此类场景如何落地？文中是否讲透了这个边界？
ref: 文中给的通用锚点是"关键路径"：车机场景可映射为启动到可用时间、倒车影像出图时长、语音唤醒响应等用户体验指标（稳快少框架仍适用）
  但"与业务模式强相关的核心指标"如何在不收费、无转化的车机上定义，文中没有展开，属于未讲透的边界
  批判点：文章的业务指标案例全部来自 C 端 App（购物/直播/聊天室），B 端与系统侧开发者需要自行构造替代指标
  承认边界后，可用"事故率、重复排查次数、体验投诉量"等团队侧指标近似业务价值
  来源锚点: knowledge-base/android/性能优化.md##2.2 业务指标与技术指标





---

kind: card
deck: 文章学习/性能优化
source: article-quiz: 性能优化方法论
front: 性能优化闭环的六个环节依次是？
back: 指标 → 监控 → 分析 → 方案 → 实验 → 沉淀





---

kind: card
deck: 文章学习/性能优化
source: article-quiz: 性能优化方法论
front: "优化三板斧"是哪三板？
back: ① 提供更好的库 ② 治理存量问题 ③ 控制增量问题
  只修一个调用点 = 头痛医头





---

kind: card
deck: 文章学习/性能优化
source: article-quiz: 性能优化方法论
front: /proc/[pid]/io 的读写量怎样计算才正确？
back: 固定间隔两次采样取差值（增量），不做单次总量判读
  重点字段：rchar/wchar/syscr/syscw/read_bytes/write_bytes