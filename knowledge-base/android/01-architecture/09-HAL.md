# HAL

> 学习资料（文章模式沉淀）。主线：HAL 三种存在形态与 Treble/VINTF 兼容边界。车机 VHAL 的服务侧机制见 [../06-system/02-OEM与设备差异.md](../06-system/02-OEM与设备差异.md) Q28–Q34；2026-09-25 增补实用调试题（Q2–Q3，按 AOSP 近版源码镜像核对）。Q 序列即结构，供 atlas 同源直读。

**Q1: HAL 有哪几种存在形态？Treble 之后 system 与 vendor 的边界靠什么维持兼容？**

HAL 有三种存在形态：Stable AIDL HAL（以 Binder 服务进程运行）、服务化（binderized）HIDL HAL（独立服务进程，走 hwbinder）、直通式（passthrough）HIDL HAL（以共享库加载进调用方进程，没有独立 HAL 进程）。Project Treble（Android 8.0 起）用"稳定接口 + VINTF 清单"维持 system/vendor 分区的可组合性。

形态直接决定排查路径：

1. **服务化实现**：调用沿 Binder 进入 HAL 进程，查服务线程、锁、系统调用与同步栅栏；
2. **直通式实现**：代码留在调用方进程，查原生调用栈和共享库内部等待。

兼容机制上，新 HAL 接口已转向 Stable AIDL（用于 system/vendor 边界时需声明 VINTF 稳定性），VINTF 清单与框架端、设备端的兼容矩阵共同决定一个具体的系统镜像与厂商镜像组合是否可安装；稳定接口保证"只更系统框架"成为可能，但不保证任意组合都兼容。

边界：Android 17 设备上仍可能保留存量 HIDL HAL 以兼容旧厂商镜像，不能仅凭系统版本假定全部 HAL 已迁移。

**Q2: 怎么确认某台设备上某 HAL"在不在"？HIDL 和 AIDL 的排查入口有什么不同？**

两代 HAL 的注册表不同：HIDL 走 hwbinder + hwservicemanager，用 `lshal`（它只管 HIDL）查；AIDL HAL 走 binder + servicemanager，直接出现在 `dumpsys -l`/`service list` 里（名字形如 `android.hardware.foo.IFoo/default`）。

1. **命令**：HIDL `adb shell lshal | grep <包>@<版本>`；AIDL `adb shell dumpsys -l | grep android.hardware`；
2. **对照声明**：把运行时结果与 VINTF 声明（`/vendor/etc/vintf/manifest.xml` 等）对照——声明只约束"应当存在"，注册成败要看运行时；
3. **调试通道**：`lshal debug <接口>` 可把参数透传给 HAL 的 debug() 方法，相当于 HAL 版 dumpsys（AIDL 侧见 Q3）。

**Q3: HAL 进程反复崩溃怎么排查？客户端如何感知？有官方调试通道吗？**

HAL 是 init 管理的服务：崩溃后由 init 按配置重启（状态机含 restarting 态），标 `critical` 的按"4 分钟 4 次"规则升级为整机动作（判据见 [02-Android系统启动流程.md](./02-Android系统启动流程.md) Q11/Q35）；客户端经 binder/hwbinder 死亡通知感知并重试 getService。

1. **取证**：崩溃前后各执行一次 `lshal`（或 `dumpsys -l`）对比服务在位情况；`logcat -b system` 搜 `Service 'vendor.*hal'` 类 init 重启日志；在 /data/tombstones 找对应 HAL 进程的 tombstone；
2. **调试通道**：AIDL HAL 实现 dump 接口后可直接 `dumpsys <接口名> -h` 查内部状态——官方 VHAL 即此模式（可列出 HAL 自定义 debug 子命令）；HIDL 用 `lshal debug`；
3. **边界**：反复崩溃的 HAL 会拖垮依赖它的服务——如 VHAL 死亡连带 CarService 自杀重建（见 [../06-system/02-OEM与设备差异.md](../06-system/02-OEM与设备差异.md) Q29）。
