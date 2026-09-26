# 公共内核与 GKI

> 学习资料（文章模式沉淀）。主线：ACK/GKI 形态、量产使用边界、厂商修改内容与动机。GKI 与 vendor ramdisk 的启动衔接见 [02-Android系统启动流程.md](./02-Android系统启动流程.md) Q24；2026-09-25 增补实用调试题（Q2–Q4，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 公共内核是什么？量产设备都会使用吗？厂商会修改什么、为什么？**

公共内核指 Android Common Kernel（ACK）——Google 基于上游 Linux 内核（通常选 LTS 分支）维护、包含 Android 所需驱动与特性（Binder 驱动、PSI 等）的公共内核分支；GKI（Generic Kernel Image，通用内核镜像）项目进一步把它变成"Google 统一构建的核心内核镜像 + 厂商可加载模块"的形态。量产设备不是原样照搬：核心镜像来自 ACK/GKI，厂商在之上叠加自己的部分。

1. **谁在用**：Android 12 起新发布的设备按 GKI 2.0 形态出货（核心内核 5.10 起）；存量升级设备可能仍运行厂商旧内核，所以"量产设备都会使用"只对新发布设备成立；
2. **厂商改什么**：SoC/板级硬件驱动以厂商模块形式加载（装在 vendor_boot/vendor_dlkm 等分区）、设备树与产品配置、电源/温控/调度策略调优（经 ACK 预留的 vendor hooks 挂回调）；核心内核镜像本身不打厂商补丁；
3. **为什么**：内核碎片化曾让同一版本 Android 背着几十种内核 fork，安全补丁与上游更新无法统一下发；GKI 把硬件代码移出核心镜像、用稳定的内核模块接口（KMI）解耦，使核心内核可以独立更新而厂商模块不动。

排查边界：公共内核源码标签（如 ACK `android17-6.18-2026-06_r6`）只能核对平台通用机制；具体设备的驱动、配置与调度策略要看设备自己的内核提交版本与 fragment，不能拿公共内核源码当设备内核源码用。

**Q2: 怎么确认一台设备的内核版本、KMI 和功能开关？**

1. **版本与 KMI**：`uname -r` 与 `cat /proc/version`——GKI 设备的 KMI 直接体现在版本串里（形如 `5.15.78-android13-8-g…`，即"内核版本-android 平台发布"）；
2. **功能开关**：启用 CONFIG_IKCONFIG_PROC 的内核把完整 config 挂在 `/proc/config.gz`——`su 0 zcat /proc/config.gz | grep CONFIG_PSI=` 即可验证某功能是否编入；无该节点时到对应 GKI release 页下载 config 比对；
3. **排查顺序**：确认"某机制是否存在"先看 config、再看运行时节点（如 `/dev/binderfs`、`/proc/pressure`）、最后看厂商修改（见 Q3）。

**Q3: 怎么确认 GKI 内核里的 vendor hooks（厂商钩子）存在？**

vendor hooks 以 android_vh_/android_rvh 前缀的 tracepoint 形式存在，用 ftrace 的可用事件列表验证：`su 0 cat /sys/kernel/tracing/available_events | grep android_vh`，再向 `events/vendor_hooks/<名>/enable` 写 1 即可观测。

1. **版本纪律**：钩子集合随 KMI 版本变化（不同内核分支的 include/trace/hooks/ 内容不同，如 binder 相关钩子只在部分分支存在）——查钩子必须按设备 KMI 对应的内核分支，不能用主线树想当然；
2. **用途**：OEM 的调度/电源策略经这些钩子挂回调；应用与框架工程师可用它们在 ftrace/perfetto 里观测内核侧事件（厂商调优的可见部分，呼应 Q1 的"厂商改什么"）。

**Q4: PSI 的 /proc/pressure 怎么读？dmesg 过滤有哪些实用姿势？**

每个 `/proc/pressure/{cpu,memory,io}` 文件两行：`some` 与 `full`，各带 avg10/avg60/avg300（窗口内停顿时间占比）与 total（累计微秒）。`some` = 至少部分任务处于停顿的时间占比；`full` = 所有非空闲任务同时停顿的占比（CPU 的 full 在系统级无意义）。

1. **判读**：memory 的 full avg60 持续大于 0 = 全系统级内存停顿明显（内存压力实锤）；some 高而 full 为 0 是局部任务受阻；
2. **dmesg 过滤**：`su 0 dmesg -w | grep -iE 'binder|oom|lowmemorykiller|psi'`；user 版默认限制读 dmesg（dmesg_restrict），要用 userdebug/root；`logcat -b kernel` 依赖 logd 配置、并非所有设备可用；
3. **衔接**：lmkd 消费 PSI 的机制见 [../05-memory/01-内存管理与压力治理.md](../05-memory/01-内存管理与压力治理.md)；调度压力与温控见 [../08-cpu-power/01-调度与功耗框架.md](../08-cpu-power/01-调度与功耗框架.md)。
