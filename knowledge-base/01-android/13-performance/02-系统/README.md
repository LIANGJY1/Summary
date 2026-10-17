# Android 系统性能

本目录收录 Framework、system_server、系统服务与系统 Native 的性能行为。按“渲染与输入 → 进程与 ART → 调度与能效 → ANR 与崩溃 → 平台差异诊断”学习；HAL、内核与驱动实现见 [底层性能](../03-底层/README.md)。知识内容遵守 [WRITING-GUIDE.md](../../../WRITING-GUIDE.md)。

## 学习顺序

1. **合成与输入**：从 SurfaceFlinger、FrameTimeline、InputDispatcher 与 Binder 证据理解帧和交互路径。
2. **进程与运行时**：学习启动、ART、内存管理、系统采样权限与进程退出。
3. **调度与能效**：理解 Framework 如何将进程状态、电源策略、温控信号传到系统服务。
4. **ANR 与崩溃**：掌握 ANR 超时、Watchdog、系统 Native 崩溃与退出证据边界。
5. **平台差异诊断**：固定 OS、Mainline、内核与设备变量，区分 Framework、Native 服务、HAL 和应用责任边界。

## 文章索引

- [Android 合成与输入系统](01-合成与输入.md)（10 题）：FrameTimeline、SurfaceFlinger/HWC、BufferQueue、触摸重采样与 SystemUI 帧路径。
- [进程、启动与 ART](02-进程启动与ART.md)（19 题）：ART 与 GC、init 启动、lmkd、低内存启动和 AMS 缓存进程 CPU 终止。
- [系统调度、电源与温控服务](03-调度电源与温控.md)（16 题）：task profile/cgroup、Power HAL、ThermalManagerService、BatteryStats、传感器与唤醒源映射。
- [ANR 与系统故障诊断](04-ANR与故障诊断.md)（27 题）：ANR 超时与预警机制、system_server Watchdog、Binder、Freezer、am_anr/trace/DropBox 和系统 Native 崩溃。
- [平台差异与系统诊断](05-平台差异与诊断.md)（4 题）：跨设备层级归因、公开约定/源码/运行证据，以及 Framework 私有 SQLite 诊断边界。

共 5 篇、76 题。跨 Framework 与 HAL/内核的题按主要解释对象归档，并在文章开头互相链接；工具操作细节仍归工具主题。
