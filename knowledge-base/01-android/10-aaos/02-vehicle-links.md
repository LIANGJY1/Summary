# 车机链路场景地图

> 学习资料（文章模式沉淀）。边界：本文回答"车机九类链路（输入/显示/摄像头/音频/车辆信号/互联/网络/定位/电源）的端到端组成与取证维度"；各链路机制细节归对应领域目录。Q 序列即结构，供 atlas 同源直读。

**Q1: Android 车机中都有哪些链路场景？**

车机的链路按数据流向分九类：输入、显示渲染、摄像头影像、音频、车辆信号、互联投屏、网络与升级、定位导航、系统与电源；每条链路都是"物理源 → 总线/驱动 → 框架服务 → 应用呈现或执行"的端到端通路。

**输入链路**

1. **触摸屏**：触摸 IC → I2C/SPI → 内核触摸驱动 → EventHub/InputReader → InputDispatcher → 应用窗口；
2. **物理按键与旋钮**：GPIO/ADC → 内核按键驱动 → input 事件 → 系统响应（返回、音量、空调旋钮）；
3. **方向盘方控按键**：按键 → 车身 CAN → VHAL → CarService 转成按键事件 → 媒体/语音/仪表响应；
4. **语音**：麦克风阵列 → 音频 codec → 音频 HAL → 唤醒与识别引擎 → 语义执行；反向播报走 TTS → AudioFlinger → 扬声器，多音区要区分主驾/副驾/后排拾音与回声消除。

**显示渲染链路**

5. **应用上屏**：App 绘制 → RenderThread/GPU → BufferQueue → SurfaceFlinger 合成 → Composer HAL → 显示控制器 → 屏幕（LVDS/eDP）；
6. **多屏输出**：主屏、副驾屏、后排娱乐屏各自作为 display，由 DisplayManager/SurfaceControl 分发渲染；
7. **仪表与 HUD**：集群屏或 HUD 的独立渲染链路（AAOS ClusterService 或独立仪表系统）。

**摄像头影像链路**

8. **倒车影像（RVC）**：R 挡信号 CAN → VHAL → CarEvsService → EVS HAL → 相机（SerDes → CSI）→ 视频帧直送上屏，绕过标准相机框架以压低延迟；
9. **360 环视（AVM）**：四路鱼眼相机 → ISP 去畸变与拼接 → 鸟瞰图 → 显示；
10. **行车记录（DVR）**：相机 → 硬件编码器 → 文件循环写入；
11. **DMS/OMS**：红外/RGB 相机 → 疲劳与乘员监测算法 → 提示或整车联动；
12. **拍照与录像应用**：Camera2/CameraX → CameraService → 相机 HAL → ISP → sensor。

**音频链路**

13. **本地媒体播放**：App → AudioTrack → AudioFlinger（焦点与混音）→ 音频 HAL → codec/DSP → 功放 → 扬声器；
14. **U 盘媒体**：U 盘 → USB 存储挂载 → MediaProvider 扫描 → 播放；
15. **收音机**：tuner 芯片 → I2C/SDIO → 广播 radio 服务 → 应用；
16. **蓝牙音乐（A2DP）**：手机 → 蓝牙控制器 → 协议栈 A2DP Sink → AudioFlinger → 扬声器；
17. **蓝牙电话（HFP）**：手机蜂窝通话音频经蓝牙 SCO 通路落到车机麦克风与扬声器；
18. **蜂窝电话**：拨号 → Telecomm → RIL/modem → 通话音频通路；
19. **eCall 紧急呼叫**：碰撞信号或手动触发 → TBOX → 蜂窝呼叫与车辆数据上报；
20. **提示音与多音区混音**：导航播报、雷达提示、chime 与媒体按 CarAudioService 的焦点和多音区策略混音输出。

**车辆信号链路**

21. **车况信号**：CAN 报文 → 收发器/MCU → VHAL → CarPropertyService/CarService → 应用（车速、挡位、胎压、里程）；
22. **空调控制（HVAC）**：UI → CarHVACManager → VHAL → CAN → 空调控制器，状态反向回显；
23. **倒车雷达**：超声波探头 → MCU → CAN → VHAL → 距离显示与提示音。

**互联投屏链路**

24. **手机互联**（CarPlay/CarLife/ICCOA/HiCar）：USB/Wi-Fi 连接 → 互联服务 → 视频流解码合成上屏 + 音频路由 + 触摸事件回传手机；
25. **蓝牙连接**：配对 → BT 协议栈并行起电话簿（PBAP）、音乐、电话多服务。

**网络与升级链路**

26. **蜂窝数据**：SIM → modem（RIL）→ netd/ConnectivityService → 应用；
27. **Wi-Fi 与热点**：wpa_supplicant/HostAPd → Wi-Fi HAL → ConnectivityService；
28. **TBOX 远控**：手机 App → 云平台 → TBOX（4G/5G）→ CAN → 整车执行（远程空调、寻车、解锁）；
29. **OTA 升级**：云端推送 → 下载校验 → A/B 双分区后台安装（updater_engine）→ 重启切槽。

**定位导航链路**

30. **卫星定位**：GNSS 模组 → 串口 → GNSS HAL → LocationManager → 导航引擎 → 地图渲染与语音播报；隧道内靠惯导航位推算续接。

**系统与电源链路**

31. **开机启动**：BootROM → bootloader → kernel → init → Zygote → `system_server` → CarService → Launcher；
32. **休眠与唤醒**：下电休眠（suspend 与唤醒源管理）→ ACC ON 快速唤醒恢复现场；
33. **电源状态联动**：ACC/挡位/大灯等信号 → 电源管理服务 → 应用生命周期与资源调度（如倒车时媒体让路）。

**按通信方式归类**

同一条链路的不同段使用不同通信方式；上列 33 条链路的每一段都归属以下六类之一：

1. **进程内调用与 JNI**：不跨进程的段——应用上屏的 App → RenderThread/GPU、语音链路里识别引擎的内部处理、蓝牙协议栈内部的协议处理；
2. **Binder / AIDL**：应用与框架服务、框架服务与 Stable AIDL HAL 之间的段——方控按键与车况信号（VHAL → CarService → 应用）、HVAC、倒车雷达、拍照录像（App → CameraService → 相机 HAL）、蜂窝电话（Telecomm → RIL）、GNSS 上报、Wi-Fi 与蜂窝数据的服务段、OTA 的 updater_engine、多音区混音策略（CarAudioService）；
3. **HIDL / hwbinder**：存量服务化 HAL 的段——旧平台的音频、相机、收音机 radio HAL，迁移完成后由 Stable AIDL 取代；
4. **FMQ / 共享缓冲（大数据面）**：倒车影像与 360 环视的视频帧、拍照录像与 DMS/OMS 的图像流、媒体与语音的 PCM、手机互联的视频流、上屏链路的 graphic buffer；
5. **系统调用 / ioctl / mmap**：触摸与按键的 input 设备节点读取、GNSS 串口读取、U 盘挂载后的文件读取、DVR 的编码与文件写入、OTA 的块设备写入、进程启动的 fork/exec、全部网络 socket、休眠唤醒的 sysfs 节点、显示控制器的 DRM/KMS 调用；
6. **专用总线与外设接口（物理段）**：CAN（方控、车况、HVAC、倒车雷达、R 挡信号、TBOX 下发）、I2C/SPI（触摸 IC、tuner、功放）、GPIO/ADC（物理按键）、UART（GNSS、部分蓝牙控制器）、USB（U 盘、手机互联）、CSI/SerDes（相机）、I2S/DAI（音频 codec）、LVDS/eDP（屏幕）。

**按五层间边界归类**

每条链路都能拆成若干"层间段"，33 条链路用到的层间边界共五类：

1. **应用 ↔ 应用框架**：SDK 调用与系统服务 Binder——CameraService、CarService/CarPropertyService/CarHVACManager、AudioTrack/AudioRecord、LocationManager、Telecomm、ConnectivityService、DisplayManager，几乎每条链路的应用侧都是这一段；
2. **应用框架 ↔ 原生库与 ART**：JNI 与框架服务内部的原生处理——AudioFlinger 混音、SurfaceFlinger 合成、蓝牙协议栈、ISP/环视拼接算法、InputReader 与 InputDispatcher；
3. **原生库/框架 ↔ HAL**：Stable AIDL、存量 HIDL 与 FMQ 数据面——Composer、EVS、相机、音频、radio、GNSS、RIL、VHAL 的调用与大数据传输；
4. **HAL/原生 ↔ 内核**：对内核的设备节点与 ioctl/mmap——VHAL 的 CAN 套接字、音频 ALSA、相机 v4l2/CSI、GNSS 串口、显示 DRM/KMS、触摸与按键的 input 节点、OTA 块设备、网络 socket；
5. **内核 ↔ 硬件**：物理总线段——CAN、I2C/SPI、GPIO/ADC、UART、USB、CSI/SerDes、I2S/DAI、LVDS/eDP。

三个典型链路的分段拆解：

1. **本地媒体播放**：应用↔框架（AudioTrack）→ 框架↔原生（AudioFlinger 混音）→ 原生↔HAL（音频 HAL）→ HAL↔内核（ALSA）→ 内核↔硬件（I2S 到 codec 与功放）；
2. **车况信号**：内核↔硬件（CAN）→ HAL/原生↔内核（VHAL 设备节点）→ 原生↔HAL（VHAL AIDL）→ 应用↔框架（CarPropertyService）；
3. **触摸屏**：内核↔硬件（I2C）→ HAL/原生↔内核（input 设备节点）→ 框架服务内部（InputReader → InputDispatcher，原生实现）→ 应用↔框架（input 通道送达窗口）。

排查时先按现象对号入座找到所属链路，再叠用两个分类维度取证：层间边界指出段发生在哪两层之间、该到哪个进程取证；通信方式指出该用什么手段取证——跨进程段查 Binder/HIDL 的事务与线程状态，大数据段查共享缓冲与同步栅栏，物理总线段抓总线报文与驱动日志。链路随车型配置增减（无 HUD、无 DMS 的车型对应链路不存在），本清单按全配置车型列出。
