**Q1: HAL 有哪几种存在形态？Treble 之后 system 与 vendor 的边界靠什么维持兼容？**

HAL 有三种存在形态：Stable AIDL HAL（以 Binder 服务进程运行）、服务化（binderized）HIDL HAL（独立服务进程，走 hwbinder）、直通式（passthrough）HIDL HAL（以共享库加载进调用方进程，没有独立 HAL 进程）。Project Treble（Android 8.0 起）用"稳定接口 + VINTF 清单"维持 system/vendor 分区的可组合性。

形态直接决定排查路径：

1. **服务化实现**：调用沿 Binder 进入 HAL 进程，查服务线程、锁、系统调用与同步栅栏；
2. **直通式实现**：代码留在调用方进程，查原生调用栈和共享库内部等待。

兼容机制上，新 HAL 接口已转向 Stable AIDL（用于 system/vendor 边界时需声明 VINTF 稳定性），VINTF 清单与框架端、设备端的兼容矩阵共同决定一个具体的系统镜像与厂商镜像组合是否可安装；稳定接口保证"只更系统框架"成为可能，但不保证任意组合都兼容。

边界：Android 17 设备上仍可能保留存量 HIDL HAL 以兼容旧厂商镜像，不能仅凭系统版本假定全部 HAL 已迁移。
