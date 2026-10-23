# Android 底层性能

本目录收录 HAL、Linux 内核、驱动及硬件执行路径，文章按底层子系统排序。Framework、system_server 与系统服务策略见[系统性能](../02-系统/README.md)，工具的采集和输出口径见[性能工具](../04-工具/README.md)。知识内容遵守 [WRITING-GUIDE.md](../../../WRITING-GUIDE.md)。

## 子系统索引

1. [调度与频率](./01-内核调度与频率.md)（12 题）：Linux 调度、EAS、CPUFreq、DVFS、迁核与厂商内核扩展。
2. [内存与能耗](./02-内存回收与功耗.md)（5 题）：内核内存回收、ZRAM/MGLRU、缺页计数和硬件能量读数。
3. [图形与传感器](./03-图形与传感器驱动.md)（1 题）：传感器 HAL/驱动唤醒路径及屏幕状态影响。
4. [Binder](./04-Binder驱动.md)（1 题）：Binder 驱动调用与 ioctl 耗时的边界。

共 4 篇、19 题。文章按主要解释的底层子系统归档；跨到 Framework 或应用层的调用链通过链接衔接。
