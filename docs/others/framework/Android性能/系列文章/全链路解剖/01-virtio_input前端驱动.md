# 01 · virtio_input：host 输入如何进入 Android

virtio_input 是 guest 内核中的虚拟输入设备驱动，接收 host 提交的输入事件并交给 input core。

> 逐行分析：[内核源码详解 01](内核源码详解/01-virtio_input前端驱动.md)。原分析基准为 kernel 5.15.41（HEAD `24d27dff64c4`），本篇沿用其已验证结论。

## 0. 本层解决什么问题

Android 的 EventHub 从 `/dev/input/event*` 读取事件。在本虚拟化设备上，这些事件先由 host 通过 virtio 输入设备送入 guest，再经过 Linux 输入子系统。它负责接收与转交，不负责识别滑动或产生 Android MotionEvent。

1. 输入：host 提交的 type、code、value 事件。
2. 输出：交给 input core 的同类事件。
3. 上游边界：host 如何采集触摸、安排报点和中断，guest 源码不能证明。
4. 下游边界：事件如何组成包、暴露为设备节点，由 02、03 篇说明。

## 1. 一次触摸如何传递

下面表示事件方向，不表示各阶段耗时或与显示帧一一对应。

```text
host 输入后端
  → events virtqueue
  → guest 收到通知并取出已完成事件
  → virtio_input 解码 type / code / value
  → input core 处理
  → evdev 写入各读者缓冲
  → EventHub 读取
```

1. guest 预先向 virtqueue 提供可供 host 写入的缓冲。virtqueue 是双方约定的缓冲与完成状态传递队列。
2. host 填入事件并标记完成，guest 的接收回调从已完成队列取出事件。
3. 驱动把事件字段交给 input core，再把缓冲重新放回，供后续事件使用。
4. 通知只表示有工作可处理，不能按“一次中断就是一帧”理解。队列容量也只是容量约束，不是触摸采样周期。

调用入口仅供识别：接收回调为 virtinput_recv_events，上交接口为 input_event。无需阅读其初始化或锁实现即可理解主线。

## 2. 谁规定一组触摸数据何时结束

一组触摸状态可能包含坐标、触点编号与按下状态等多个事件，结束标记是 `EV_SYN/SYN_REPORT`。

1. 在本设备的常规触摸通路中，host 发送该标记，virtio_input 连同其他事件转交。
2. input core 根据标记把已经收集的变化交给下游，详见 02 篇。
3. `SYN_REPORT` 表示输入状态更新结束，不等于 RecyclerView 滚动一次、doFrame 一次或屏幕显示一帧。
4. 内核在容量兜底、按键重复等特定路径也可能合成同步标记，不能把本样本结论推广到所有输入设备。

## 3. 时间戳表示哪个时刻

virtio 输入的 wire 事件只有 type、code、value，没有 host 事件时间字段。本路径的 Android eventTime 因此不能解释为物理手指触碰时刻。

1. virtio_input 转交事件时不带入 host 原始时间。
2. 本触摸路径在 evdev 交付阶段通过 input core 的取时接口补齐时间，使用 CLOCK_MONOTONIC，详见 03 篇。
3. EventHub 的读取时间另存为 readTime。eventTime 与 readTime 的差值包含 guest 内核交付到用户态读取的等待，不能包含 guest 未记录的 host 前段耗时。
4. host 产生事件到 guest 取时之间的时延仍是盲区。

## 4. 设备怎样出现在 EventHub 面前

1. 驱动读取 host 提供的设备名称和输入能力，向 input core 注册设备。
2. evdev 为匹配设备创建 `/dev/input/event*` 接口。节点序号分配属于 evdev，而非 Android View 或应用层。
3. EventHub 打开节点并查询能力，识别触摸设备。设备名称和能力帮助确认链路，名称本身不解释 host 的报点策略。
4. 设备释放或挂起恢复时，驱动停止或重建设备队列。正常事件流程与这些生命周期路径应分开理解。

驱动还有 guest 向 host 发送设备状态的反向队列。它不改变本篇触摸输入主线，也不是应用的 finish 回执通道。

## 5. 本样本能说明什么

以下沿用原分析与留痕，不是本次重新采集的结果。

1. 已证：原基准驱动中没有运行期周期定时、最小间隔检查或按显示帧攒点的机制。不能把触摸间隔的分布归为驱动主动按固定周期节流，也不能据此声称驱动或中断执行耗时为零。
2. 已证的观测：1195 个可见报点形成 1194 个间隔，均值 16.617 ms、中位数 16.347 ms、P90 31.416 ms、P95 36.122 ms、P99 40.936 ms，最大 93.711 ms。小于 10 ms 的有 253 个（21.2%），大于 25 ms 的有 209 个（17.5%）。均值接近 60 Hz 不等于均匀 60 Hz 报点。
3. 重点间隔为 37.237 ms，随后是 2.842、9.958、16.588、16.390 ms。这种先长后短的序列不能直接证明 host 攒批后释放。
4. 未证：有限缓冲是否在该窗口产生背压，host 是否合批或合并中断。
5. 盲区：host 输入来源及其内部调度。guest 没有逐事件 host 时间戳，不能定量拆出这一段。

源码证据入口：`drivers/virtio/virtio_input.c:33-54`（接收）、`:218-337`（设备注册），完整证明与原断言编号见详细版 §4、§5、§8、§9、§11。原分析过程见 `过程记录/批01-过程记录.md`。

## 6. 与 Android 主线如何衔接

阅读顺序为 01 → 02 → 03 → 04。前两次交接分别是“驱动交给 input core”和“input core 交给 evdev”。事件到达 notifyMotion 时，已越过本篇，因此 34.738 ms 的 notifyMotion→prepare 空档应在 Android 分发路径查证，不能倒推为驱动中的等待。
