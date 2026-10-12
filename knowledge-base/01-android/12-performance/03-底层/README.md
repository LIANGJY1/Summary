# Android 底层性能

本目录收录 HAL、Linux 内核、驱动及硬件执行路径。按“调度与频率 → 内存和功耗硬件 → 图形与传感器驱动”学习；Framework、system_server 与系统服务策略见 [系统性能](../02-系统/README.md)。知识内容遵守 [WRITING-GUIDE.md](../../../WRITING-GUIDE.md)。

## 学习顺序

1. **调度与频率**：先理解 CPU 容量、调度、EAS、CPUFreq 与性能域，再看 Android 用户空间策略如何落到内核。
2. **内存与功耗硬件**：学习内核回收、MGLRU 与硬件能量读数边界；PowerStats HAL 的数据类型和公开权限见工具主题。
3. **图形与传感器驱动**：沿 GPU/显示、触控采样与传感器唤醒路径理解硬件行为。

## 文章索引

- [内核调度与 CPU 频率](01-cpu-scheduler.md)（12 题）：Linux 调度、EAS、CPUFreq、DVFS、迁核与厂商内核扩展。
- [内存回收与功耗硬件](02-memory-power-hardware.md)（5 题）：AppFlow 内核边界、低内存回收、ZRAM/MGLRU、硬件能量轨道与缺页计数。
- [图形、显示与传感器驱动](03-graphics-sensor-drivers.md)（1 题）：wake-up 与 non-wake-up 传感器的 HAL/驱动唤醒路径。
- [Binder 驱动调用边界](04-binder-driver.md)（1 题）：BINDER_WRITE_READ 与 Binder RPC 耗时的区别。

共 4 篇、19 题。跨层题按主解释对象保留在一个目录，另一层的策略或调用链通过对应文章互链。
