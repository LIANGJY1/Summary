# 03 · evdev：Android 如何读取内核输入事件

evdev 是 Linux 内核中向用户态提供输入事件的处理器。它为各打开者维护独立缓冲，EventHub 通过 read/epoll 等待和读取事件。

> 逐行分析：[内核源码详解 03](内核源码详解/03-evdev用户态接口.md)。基准为 kernel 5.15.41、HEAD `24d27dff64c4`，Android 对接沿用原基准源码树分析。

## 0. 本层解决什么问题

evdev 通过 `/dev/input/eventX` 设备节点向 getevent 和 EventHub 等用户态程序提供访问入口。它连接 input core 与读取者，并提供事件格式、缓冲和就绪通知。

1. 输入：input core 分发的一批事件。
2. 输出：可由 read 读取的 input_event，包含时间、类型、代码和值。
3. 通知：有完整输入包可读时，使等待该节点的 poll/epoll 消费者得到就绪通知。
4. 边界：就绪不等于线程已经运行，读取事件也不等于应用已经消费 MotionEvent。

## 1. 为什么 getevent 不会读走 Android 的事件

每次独立打开设备节点都会建立自己的 client 和缓冲，getevent 与 EventHub 各读各的副本。

```text
input core 交付事件
  → evdev 取得事件时间
  ├→ getevent client 缓冲 → getevent 读取与打印
  └→ EventHub client 缓冲 → EventHub read → RawEvent
```

1. 两个消费者可以收到同一次事件的拷贝，时间戳来源相同。
2. 各 client 的读取速度、缓冲积压和溢出状态独立。
3. getevent 能持续读到输入，不能证明 EventHub 同期已及时读取。
4. evdev 还提供独占抓取机制，启用后会影响其他 client 的交付；“独立缓冲”不等于在所有配置下都保证广播。原样本相关前提和边界见详细版 §8、§11。

## 2. 包边界与 read 返回有什么区别

`EV_SYN/SYN_REPORT` 表示一组输入状态更新结束。evdev 使用这一标记确定可读取的事件范围。

1. 包内坐标、按键或触点状态先进入缓冲，完整包到达后唤醒读端。
2. read 返回多少条事件，还取决于调用者给出的缓冲大小和现有可读数据量，可能一次读到多个包，也可能把一个已完成的大包分几次取走。
3. 因此不能把一次 read、一次 epoll 返回或一次线程唤醒直接计为一个触摸报点。
4. EventHub 使用非阻塞读取，无数据时可返回 EAGAIN，再通过 epoll 等待；getevent 的读取形态与它不同，但不改变缓冲中已有事件的时间戳。

## 3. eventTime 与 readTime 分别表示什么

本触摸通路的 eventTime 表示事件批在 guest 内核 evdev 交付阶段取时的结果，不是物理触摸或 host 产生事件的时间。

1. virtio wire 不带 host 时间戳。evdev 调用 input core 的 input_get_timestamp，缺少有效时间时用 ktime_get 取 CLOCK_MONOTONIC 时间。
2. 常规同步包交付后时间槽失效，下一包重新取时。同一批事件交付给各 client 时，共用这一批的时间读数。
3. EventHub 将读到的秒、微秒换算为纳秒，保存在 RawEvent.when，再沿 Android 输入链传递。
4. EventHub 读取时另记录 readTime。两者之差用于观察事件取时到用户态读取的等待，不能把 readTime 当成事件发生时间。
5. 容量兜底拆批等特殊路径的时间槽复用规则保存在详细版 §7.2，不能把常规包的重新取时规则无条件推广。

源码入口：`input.c:2040-2063`、`evdev.c:295-310`、`EventHub.cpp:177-193` 和 RawEvent 填充路径。

## 4. 为什么时钟口径必须单独确认

新建 evdev client 的默认时钟是 CLOCK_REALTIME。getevent 与本触摸设备的 EventHub 按原源码都显式请求 CLOCK_MONOTONIC，因此不能把默认值当成本样本的实际设置。

1. CLOCK_MONOTONIC 不包含系统 suspend 累计时间，CLOCK_BOOTTIME 包含，两者差值不能无条件忽略。
2. 本 trace 的主时间域为 BOOTTIME。将 getevent 时间减去 trace 起点前，应核对两者的时钟映射，而不是只因数值都以秒计就直接相减。
3. 本样本约 `00:00:06.787410` 与同 ID eventTime 的单点吻合是已有证据，全程逐点跨域校验未做。
4. getevent 运行二进制与基准源码的一致性未证，不能只依据源码请求 MONOTONIC 就宣称所有运行期前提都已验证。
5. EventHub 对 SENSOR 类设备有 BOOTTIME 设置分支。本篇讨论的 event0 是触摸设备，不能套用于所有传感器节点。

## 5. SYN_DROPPED 表示什么

SYN_DROPPED 是输入事件流失去连续性的标记，消费者应重新同步设备状态。

1. 主要场景是某个 client 缓冲被写满，未读事件被丢弃。其他 client 是否溢出要独立判断。
2. 状态读取 ioctl 失败补偿、切换时钟并清空非空队列，也可产生该标记。看到标记后应检查具体路径，不能一律判定为应用卡顿导致的溢出。
3. Android InputDevice 收到标记后 reset，忽略后续事件直至下一个 SYN_REPORT，之后恢复正常处理。丢失的历史事件不会被重新构造出来。
4. 相关状态应通过设备当前状态重新同步，不能继续假定丢失区间中的按键、触点仍保持旧值。

## 6. 本样本怎样使用这些原理

1. 已证的机制：getevent 和 EventHub 的相同时间读数来自同一次内核取时后写入各自缓冲，不是两个消费者恰好同时执行。
2. 已有数据：getevent 的 1195 条 EV_SYN 均为 SYN_REPORT，未见 SYN_DROPPED，标签无关检查亦无其他 EV_SYN。原分析据此判定其 client 未见溢出证据。
3. 未证：EventHub client 是否曾溢出，不能用 getevent 的结果代替。
4. 间隔统计来自事件自带时间，不是 getevent 的打印间隔。读取慢会形成积压乃至溢出，不会回写已存事件的时间戳。
5. 约 `6.787410 s` 的事件取时之后，InputReader 在约 `6.787427 s` 开始 R 状态，随后等待约 7.951 ms 才运行。就绪通知与调度执行必须分开，见 04、14 篇。
6. host 侧来源、跨时钟全程对齐、设备 build 一致性继续维持原未证或盲区状态。

证据与原编号见详细版 §3～§7、§9、§11，过程记录为 `过程记录/批03-过程记录.md`。

## 7. 与 Android 源码主线的交接

从 04 篇起，沿 EventHub.getEvents → RawEvent → InputReader 读取 Android 实现。只需带入四个事实：数据按 SYN_REPORT 分组、每个打开者有独立缓冲、when 与 readTime 不同、SYN_DROPPED 需要状态恢复。
