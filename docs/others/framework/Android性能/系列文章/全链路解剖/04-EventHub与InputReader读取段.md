# 04 · EventHub 与 InputReader 读取段

Android 从输入设备节点读取事件，将其转换后交给系统分发：

```text
/dev/input/eventX 中的 input_event
  → EventHub 读取并转换为 RawEvent
  → InputReader 按设备分派给对应的 mapper
  → mapper 生成 Android 输入事件参数
  → 监听器链 → InputDispatcher
```

mapper 是按设备类型处理原始事件的组件，例如触摸映射器负责处理触点和坐标。

## 0. 本篇范围

1. **起点** ： EventHub 打开输入设备、读取内核事件，记录事件时间和读取时间。evdev 的工作原理见[第 03 篇](03-evdev用户态接口.md)。
2. **终点** ： InputReader 将事件交给监听器链，直至 InputDispatcher 的 `notifyMotion` 入口。触摸映射器的详细转换过程见[第 05 篇](05-触摸烹调段.md)。
3. **源码入口** ： `EventHub.cpp`、`InputReader.cpp`、`InputListener.cpp` 和 `InputManager.cpp`。

本次 trace 中，InputReader 有约 7.951 ms 处于可运行但未执行的 R 状态；这段时间是调度等待，不能计作读取代码的执行时间。完整时间线和依据见第 8 节。

## 1. 阅读前了解的四个概念

1. **事件 ID** ： Android 为输入事件生成的标识，可用于关联不同阶段的记录。例如 trace 中 `notifyMotion(id=0x3b3b7f02)` 的 ID 用于追踪该事件；它不是触点编号。
2. **设备变化标记** ： EventHub 在设备添加、移除和扫描完成时生成对应标记，供 InputReader 更新设备列表。这些标记不是驱动上报的触摸坐标。
3. **设备标识 descriptor** ： EventHub 根据设备信息生成的标识。InputReader 可据此将属于同一设备的多个节点关联起来；标识的生成与复用条件见第 3.3 节。
4. **监听器链** ： 输入事件从 InputReader 依次经过 UnwantedInteractionBlocker、InputClassifier，最终交给 InputDispatcher。各环节的职责和转交方式见第 7 节。

## 2. InputManager：输入组件的组装与启动

### 2.1 InputManager：组装输入处理链

InputManager 是 Android 输入系统的 native 管理组件。它创建并连接 InputReader、输入预处理组件和 InputDispatcher，再负责启动和停止读取、分发线程。它的作用是把各组件组织成一条可运行的输入链，而不是亲自读取每条触摸事件。

这里的 InputManager 指 `frameworks/native/services/inputflinger` 中的 native 类；不要与应用使用的 Java InputManager 或系统服务 InputManagerService 混淆。

监听器链的装配顺序在 `InputManager.cpp` 的构造函数里，四行构造语句的先后即包装关系（谁包谁）：

```cpp
// frameworks/native/services/inputflinger/InputManager.cpp:60-67
InputManager::InputManager(
        const sp<InputReaderPolicyInterface>& readerPolicy,
        const sp<InputDispatcherPolicyInterface>& dispatcherPolicy) {
    mDispatcher = createInputDispatcher(dispatcherPolicy);
    mClassifier = std::make_unique<InputClassifier>(*mDispatcher);
    mBlocker = std::make_unique<UnwantedInteractionBlocker>(*mClassifier);
    mReader = createInputReader(readerPolicy, *mBlocker);
}
```

构造时先创建下游，再把下游交给上游，四个组件的职责分别是：

1. **InputDispatcher** ： 选择输入事件的目标窗口并分发。
2. **InputClassifier** ： 对符合条件的运动事件进行分类，并将事件交给 Dispatcher。
3. **UnwantedInteractionBlocker** ： 根据配置处理需要抑制的误触，再将事件交给分类器。
4. **InputReader** ： 读取并转换设备输入，将事件交给 Blocker。

因此，代码的构造顺序与事件的传递顺序相反：

```text
构造：InputDispatcher → InputClassifier → UnwantedInteractionBlocker → InputReader
传递：InputReader → UnwantedInteractionBlocker → InputClassifier → InputDispatcher
```

具体连接关系可在 `InputClassifier.cpp:370`、`UnwantedInteractionBlocker.cpp:317-322` 和 `InputReaderFactory.cpp:23-26` 中核对。构造由 InputManagerService 的启动路径触发；事件则通过各组件的 `notifyX` 和 `QueuedInputListener::flush` 逐级转交，详见第 7 节。

监听链中的处理可能改变或丢弃事件，取决于具体组件和条件。本样本中，无活动触笔且手势未被取消时，PreferStylusOverTouchBlocker 对该触摸事件原样放行（`PreferStylusOverTouchBlocker.cpp:154-176`）。

源码中的链路注释如下：

```cpp
// frameworks/native/services/inputflinger/InputManager.cpp:56-59
/**
 * The event flow is via the "InputListener" interface, as follows:
 * InputReader -> UnwantedInteractionBlocker -> InputClassifier -> InputDispatcher
 */
```

### 2.2 InputReader 构造与线程创建

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:43-59
InputReader::InputReader(std::shared_ptr<EventHubInterface> eventHub,
                         const sp<InputReaderPolicyInterface>& policy,
                         InputListenerInterface& listener)
      : mContext(this),
        mEventHub(eventHub),
        mPolicy(policy),
        mQueuedListener(listener),
        mGlobalMetaState(AMETA_NONE),
        ...
        mConfigurationChangesToRefresh(0) {
    refreshConfigurationLocked(0);
    updateGlobalMetaStateLocked();
}
```

走读：`mQueuedListener(listener)` 把装配链下游（即 *mBlocker）存为 Reader 自己的 QueuedInputListener 内层——Reader 是监听器链的最上游生产者。构造期 `refreshConfigurationLocked(0)`（`:340-344`）经 policy 拉一次读取配置并把排除设备名交给 EventHub（`:342`）。EventHub 实例在 `createInputReader` 里现做：`std::make_unique<InputReader>(std::make_unique<EventHub>(), policy, listener)`（`InputReaderFactory.cpp:25`）——构造即触发第 3 节的 epoll/inotify/wake 三 fd 初始化。

线程的创建在 `start()`：

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:63-70
status_t InputReader::start() {
    if (mThread) {
        return ALREADY_EXISTS;
    }
    mThread = std::make_unique<InputThread>(
            "InputReader", [this]() { loopOnce(); }, [this]() { mEventHub->wake(); });
    return OK;
}
```

走读：线程名 `"InputReader"`、循环体 `loopOnce()`、退出唤醒函数 `mEventHub->wake()` 三个实参进入 InputThread。InputThread 的实现体（`InputThread.cpp:24-38`）把循环函数包进 libutils 的 Thread 子类，`threadLoop()` 返回 true 使循环永续：

```cpp
// frameworks/native/services/inputflinger/InputThread.cpp:34-46
    bool threadLoop() override {
        mThreadLoop();
        return true;
    }
};

} // namespace

InputThread::InputThread(std::string name, std::function<void()> loop, std::function<void()> wake)
      : mName(name), mThreadWake(wake) {
    mThread = new InputThreadImpl(loop);
    mThread->run(mName.c_str(), ANDROID_PRIORITY_URGENT_DISPLAY);
}
```

走读：`run(name, ANDROID_PRIORITY_URGENT_DISPLAY)` 同时定了线程名与调度优先级——trace 里 tid=1489 的轨名 "InputReader" 的来源就在这一行（已证：命名链 InputReader.cpp:68 → InputThread.cpp:45 → pthread_setname_np）。析构路径（`InputThread.cpp:48-54`）先 `requestExit()` 再调用 wake 函数打断阻塞中的 epoll_wait，最后 join——这是 `EventHub::wake()` 的第一个调用方（第 5.2 节）。

### 2.3 EventHub 构造：一个 epoll 实例＋三类 fd

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:687-736（节选）
    mEpollFd = epoll_create1(EPOLL_CLOEXEC);
    LOG_ALWAYS_FATAL_IF(mEpollFd < 0, "Could not create epoll instance: %s", strerror(errno));

    mINotifyFd = inotify_init1(IN_CLOEXEC);
    ...
    if (std::filesystem::exists(DEVICE_INPUT_PATH, errorCode)) {
        addDeviceInputInotify();                       // 监听 /dev/input
    } else {
        addDeviceInotify();                            // 监听 /dev
        isDeviceInotifyAdded = true;
        ...
    }
    ...
    eventItem.events = EPOLLIN | EPOLLWAKEUP;
    eventItem.data.fd = mINotifyFd;
    int result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mINotifyFd, &eventItem);
    ...
    int wakeFds[2];
    result = pipe2(wakeFds, O_CLOEXEC);
    ...
    mWakeReadPipeFd = wakeFds[0];
    mWakeWritePipeFd = wakeFds[1];
    ...
    eventItem.data.fd = mWakeReadPipeFd;
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeReadPipeFd, &eventItem);
```

走读（// 略 ：688-735 为错误检查与非关键分支）：构造完成三件事。其一，`epoll_create1` 建一个 epoll 实例 `mEpollFd`（`:687`）；其二，`inotify_init1` 建 inotify 实例并以 `inotify_add_watch` 监听 `/dev/input` 的 IN_CREATE/IN_DELETE（`:690`、`:752-756`），把 inotify fd 以 `EPOLLIN|EPOLLWAKEUP` 加入 epoll（`:711-715`）；其三，`pipe2` 建 wake pipe 两端（`mWakeReadPipeFd`/`mWakeWritePipeFd`，`:717-722`），读端置 O_NONBLOCK（`:724-726`）并加入同一 epoll（`:732-735`）。输入设备 fd 是第三类被监听对象，在打开设备时逐个注册（第 3 节）。EPOLLWAKEUP 的语义：任一被监听 fd 有数据未读期间内核持有 wakeup lock，系统不许休眠；为此构造前先校验进程持 CAP_BLOCK_SUSPEND 能力，缺失即 FATAL（`EventHub.cpp:663-669`）。

机制四问（EventHub 阻塞模型）：规则是什么——InputReader 线程只在 getEvents 底部的 `epoll_wait` 一处阻塞（`:1760`），等待对象是上述三类 fd。谁设置——构造函数注册 inotify fd 与 wake pipe 读端（`:711-735`）、`openDeviceLocked` 注册设备 fd（`:2228`），注册统一走 `registerFdForEpoll`（`:1850-1860`）。谁执行——内核 epoll 子系统等待，唤醒方为内核 evdev 的 `wake_up`（内核详解03 B8）与用户态 `EventHub::wake()` 对 `mWakeWritePipeFd` 的写入。执行的后果——无事件时该线程进入 S 态（TASK_INTERRUPTIBLE，#16），零 CPU；事件到达或 wake 写入即转 R（#16）。

### 2.4 InputManager::start：两条子线程的启动序

```cpp
// frameworks/native/services/inputflinger/InputManager.cpp:73-89
status_t InputManager::start() {
    status_t result = mDispatcher->start();
    if (result) {
        ALOGE("Could not start InputDispatcher thread due to error %d.", result);
        return result;
    }

    result = mReader->start();
    if (result) {
        ALOGE("Could not start InputReader due to error %d.", result);

        mDispatcher->stop();
        return result;
    }

    return OK;
}
```

走读：启动顺序为 Dispatcher 线程先、Reader 线程后（`:74`/:80）；Reader 启动失败则回滚 Dispatcher（`:84`）。四问其一（规则是什么）：start 由 InputManagerService 的启动路径调用，本文件内不重入（`mReader->start()` 对重复调用返回 ALREADY_EXISTS，`InputReader.cpp:64-66`）。谁设置：调用序写死在 start 函数体内。谁执行：两级的 start 各自创建线程（dispatcher 侧创建 "InputDispatcher" 线程、Reader 侧创建 "InputReader" 线程）。执行的后果：两线程轨在 trace 中并存（tid=1488/1489，编号相邻与创建顺序相容，编号先后不构成因果证明）；stop 的顺序相反——先停 Reader 再停 Dispatcher（`:91-107`），与数据流向一致（先断源头再断消费）。

## 3. 打开设备与设备档案：openDeviceLocked

### 3.1 open 与 ioctl 能力查询

`scanDevicesLocked`（`:1813-1837`）遍历 `/dev/input` 全部节点逐个调用 `openDeviceLocked`（`:2470-2475`），末尾补上始终存在的虚拟键盘（id=-1，`ReservedInputDeviceId::VIRTUAL_KEYBOARD_ID`，`frameworks/native/include/input/InputDevice.h:327-329`）。打开与身份读取：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1947-1991（节选）
    int fd = open(devicePath.c_str(), O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        ALOGE("could not open %s, %s\n", devicePath.c_str(), strerror(errno));
        return;
    }

    InputDeviceIdentifier identifier;

    // Get device name.
    if (ioctl(fd, EVIOCGNAME(sizeof(buffer) - 1), &buffer) < 1) {
        ...
    } else {
        buffer[sizeof(buffer) - 1] = '\0';
        identifier.name = buffer;
    }
    ...                                              // 略 ：1964-1971 排除名单
    int driverVersion;                               // :1975 EVIOCGVERSION
    if (ioctl(fd, EVIOCGVERSION, &driverVersion)) {
        ...
    }

    struct input_id inputId;                         // :1983 EVIOCGID
    if (ioctl(fd, EVIOCGID, &inputId)) {
        ...
    }
    identifier.bus = inputId.bustype;
    identifier.product = inputId.product;
    identifier.vendor = inputId.vendor;
    identifier.version = inputId.version;
```

走读：`O_RDWR | O_CLOEXEC | O_NONBLOCK`（`:1947`）——非阻塞模式是第 4 节 read 循环不卡死的前提（内核详解03 §9.2 已对照 getevent 的阻塞模式）。身份五件套 name/version/id/phys/uniq 依次经 `EVIOCGNAME(:1956)/EVIOCGVERSION(:1975)/EVIOCGID(:1983)/EVIOCGPHYS(:1994)/EVIOCGUNIQ(:2002)` 读取。这些 ioctl 与内核详解03 §9.1 对照表的 EventHub 列一致；node 名与 event0 序号的分配规则已由内核详解03 §3 闭环（B2），本篇不重复。

能力位图的读取集中在一处：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:2053-2061
    // Figure out the kinds of events the device reports.
    device->readDeviceBitMask(EVIOCGBIT(EV_KEY, 0), device->keyBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_ABS, 0), device->absBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_REL, 0), device->relBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_SW, 0), device->swBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_LED, 0), device->ledBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_FF, 0), device->ffBitmask);
    device->readDeviceBitMask(EVIOCGBIT(EV_MSC, 0), device->mscBitmask);
    device->readDeviceBitMask(EVIOCGPROP(0), device->propBitmask);
```

走读：EventHub 能力位图八种一次读全（key/abs/rel/sw/led/ff/msc/prop；keyState/swState 两个状态位图不在打开时读，在按键/开关状态查询时经 EVIOCGKEY/EVIOCGSW 另行填充，`:855`/`:927`）。`readDeviceBitMask`（`:411-424`）对 `_IOC_SIZE==0` 的 ioctl 码按目标位数组字节数现场补齐长度。轴的上下限不在打开时读，而在 mapper configure 时经 `getAbsoluteAxisInfo` 的 `ioctl(device->fd, EVIOCGABS(axis), &info)`（`:802`）按需查询，`min==max` 判该轴无效（`:808-815`）——批05 走读其消费方。

### 3.2 类别判定：event0 走哪条岔路

类别判定完全依据能力位图，不看设备名：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:2090-2100
    if (device->absBitmask.test(ABS_MT_POSITION_X) && device->absBitmask.test(ABS_MT_POSITION_Y)) {
        // Some joysticks such as the PS3 controller report axes that conflict
        // with the ABS_MT range.  Try to confirm that the device really is
        // a touch screen.
        if (device->keyBitmask.test(BTN_TOUCH) || !haveGamepadButtons) {
            device->classes |= (InputDeviceClass::TOUCH | InputDeviceClass::TOUCH_MT);
        }
        // Is this an old style single-touch driver?
    } else if (device->keyBitmask.test(BTN_TOUCH) && device->absBitmask.test(ABS_X) &&
               device->absBitmask.test(ABS_Y)) {
        device->classes |= InputDeviceClass::TOUCH;
```

走读：event0 报 ABS_MT_POSITION_X/Y（内核详解01 §4.5 的 host 声明；内核详解02 §2.6 的 absbit）且带 BTN_TOUCH（touch.txt 的那一条 EV_KEY）→ 命中第一个分支，判为 `TOUCH | TOUCH_MT`（多点触摸屏）。触笔与摇杆的防止误判分支（`:2102-2123`）对本设备不生效。一类能力都不沾的节点（`classes==0`）直接丢弃不监控（`:2196-2200`）。KEYBOARD 类判定走 `keyBitmask.any(0, BTN_MISC)` 等判据（`:2065-2071`），event0 的 keybit 只有 BTN_TOOL_FINGER 与 BTN_TOUCH、无按键类 KEY_*（内核详解02 §2.6 的 input-devices.txt:44），本设备不判 KEYBOARD——因此第 3.4 节的 EVIOCSREP 分支对 event0 不生效。

判定通过后注册 epoll 并挂入待上报队列：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:2228-2241
    if (registerDeviceForEpollLocked(*device) != OK) {
        return;
    }

    device->configureFd();

    ALOGI("New device: id=%d, fd=%d, path='%s', name='%s', classes=%s, "
          "configuration='%s', keyLayout='%s', keyCharacterMap='%s', builtinKeyboard=%s, ",
          deviceId, fd, devicePath.c_str(), device->identifier.name.c_str(),
          device->classes.string().c_str(), device->configurationFile.c_str(),
          device->keyMap.keyLayoutFile.c_str(), device->keyMap.keyCharacterMapFile.c_str(),
          toString(mBuiltInKeyboardId == deviceId));

    addDeviceLocked(std::move(device));
```

走读：`registerDeviceForEpollLocked`（`:1870-1880`）把设备 fd 以 `EPOLLIN | EPOLLWAKEUP` 加入 `mEpollFd`（`registerFdForEpoll` `:1850-1860`）——从此该 fd 可读会直接唤醒第 4 节的 epoll_wait。`addDeviceLocked`（`:2338-2341`）只做两件事：statslog 上报（`reportDeviceAddedForStatisticsLocked` `:1913-1930`）与把 Device 挂入 `mOpeningDevices`。**此刻设备尚未通知 InputReader**——DEVICE_ADDED 合成事件要等下一次 getEvents 在第 4.2 节的拓扑消费段产出（`:1580-1608`），顺序契约保证 InputReader 先注册设备再看到它的数据。

### 3.3 设备指纹 descriptor

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1310-1318
static std::string generateDescriptor(InputDeviceIdentifier& identifier) {
    std::string rawDescriptor;
    rawDescriptor += StringPrintf(":%04x:%04x:", identifier.vendor, identifier.product);
    if (!identifier.uniqueId.empty()) {
        rawDescriptor += "uniqueId:";
        rawDescriptor += identifier.uniqueId;
    } else if (identifier.nonce != 0) {
        rawDescriptor += StringPrintf("nonce:%04x", identifier.nonce);
    }
    ...
```

走读（// 略 ：1319-1335 vid/pid 全零时退拼 name/location；`:1334` `identifier.descriptor = sha1(rawDescriptor)`）：descriptor 由 vid/pid＋uniqueId 拼串取 sha1（`:1310-1336`）；无 uniqueId 且撞串时以 nonce 自增重算直至全局唯一（`assignDescriptorLocked` `:1346-1355`）。InputReader 侧的消费在 `createDeviceLocked`：

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:273-287
    auto deviceIt = std::find_if(mDevices.begin(), mDevices.end(), [identifier](auto& devicePair) {
        return devicePair.second->getDescriptor().size() && identifier.descriptor.size() &&
                devicePair.second->getDescriptor() == identifier.descriptor;
    });

    std::shared_ptr<InputDevice> device;
    if (deviceIt != mDevices.end()) {
        device = deviceIt->second;
    } else {
        int32_t deviceId = (eventHubId < END_RESERVED_ID) ? eventHubId : nextInputDeviceIdLocked();
        device = std::make_shared<InputDevice>(&mContext, deviceId, bumpGenerationLocked(),
                                               identifier);
    }
    device->addEventHubDevice(eventHubId);
    return device;
```

走读：同一 descriptor 的多节点设备复用同一 InputDevice 实例（`:273-281`）；deviceId 双空间——eventHubId 落在保留段（`END_RESERVED_ID=1`，`frameworks/native/include/input/InputDevice.h:333`）内直接沿用，其余自增分配（`:282`）。event0 的 eventHubId 是 EventHub 的 `mNextDeviceId++`（`EventHub.cpp:2013`），首台动态设备从 1 起。

### 3.4 configureFd：关内核重复、切单调时钟

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:426-448
void EventHub::Device::configureFd() {
    // Set fd parameters with ioctl, such as key repeat, suspend block, and clock type
    if (classes.test(InputDeviceClass::KEYBOARD)) {
        // Disable kernel key repeat since we handle it ourselves
        unsigned int repeatRate[] = {0, 0};
        if (ioctl(fd, EVIOCSREP, repeatRate)) {
            ALOGW("Unable to disable kernel key repeat for %s: %s", path.c_str(), strerror(errno));
        }
    }
    ...
    int clockId = CLOCK_MONOTONIC;
    if (classes.test(InputDeviceClass::SENSOR)) {
        clockId = CLOCK_BOOTTIME;
    }
    bool usingClockIoctl = !ioctl(fd, EVIOCSCLOCKID, &clockId);
    ALOGI("usingClockIoctl=%s", toString(usingClockIoctl));
}
```

走读：两件事。其一，KEYBOARD 类设备以 `EVIOCSREP {0,0}` 关闭内核按键自动重复（`:428-434`）——批02 已证内核 autorepeat 定时器对无 EV_REP 位设备本来就不武装（B28）；A13 中长按重复改由 InputDispatcher 合成（`InputDispatcher.cpp:755-758` 的 `synthesizeKeyRepeatLocked`），Reader 层没有重复定时器。其二，所有设备把事件时钟切到 `CLOCK_MONOTONIC`（SENSOR 类 BOOTTIME，`:440-446`）——与内核详解03 B18 闭环：getevent 与 EventHub 两个 client 都设 MONO，是 eventTime 与 trace 数值同源的机制前提。event0 非 SENSOR 类，走 MONO。

### 3.5 设备档案的其余部分：配置、键位图、关联设备、统计上报

openDeviceLocked 的中间段建立设备档案的剩余维度（本样本 event0 各项的实际取值以源码机制为准，具体配置文件是否存在属运行时状态）：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:2029-2030、:2153-2161（节选）
    // Load the configuration file for the device.
    device->loadConfigurationLocked();
    ...
    // Load the key map.
    // We need to do this for joysticks too because the key layout may specify axes, and for
    // sensor as well because the key layout may specify the axes to sensor data mapping.
    status_t keyMapStatus = NAME_NOT_FOUND;
    if (device->classes.any(InputDeviceClass::KEYBOARD | InputDeviceClass::JOYSTICK |
                            InputDeviceClass::SENSOR)) {
        // Load the keymap for the device.
        keyMapStatus = device->loadKeyMapLocked();
    }
```

走读：`loadConfigurationLocked`（`:468-486`）按设备标识查找 .idc 配置文件并加载 PropertyMap；键位图（kl/kcm）只对 KEYBOARD/JOYSTICK/SENSOR 类加载（`:2156-2161`）——event0 是 TOUCH 类，两步都不产生键盘映射。虚拟键映射（`:2144-2151`）只在 TOUCH 类上尝试 `/sys/board_properties/virtualkeys.*`（`:488-497`）。event0 无 .idc/无虚拟键文件时对应字段留空，不影响事件通道。

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:2035-2051（节选）
    std::optional<std::filesystem::path> sysfsRootPath = getSysfsRootPath(devicePath.c_str());
    if (sysfsRootPath.has_value()) {
        std::shared_ptr<AssociatedDevice> associatedDevice;
        for (const auto& [id, dev] : mDevices) {
            if (device->identifier.descriptor == dev->identifier.descriptor &&
                !dev->associatedDevice) {
                associatedDevice = dev->associatedDevice;
            }
        }
        if (!associatedDevice) {
            associatedDevice = std::make_shared<AssociatedDevice>(sysfsRootPath.value());
        }
        hasBattery = associatedDevice->configureBatteryLocked();
        hasLights = associatedDevice->configureLightsLocked();

        device->associatedDevice = associatedDevice;
    }
```

走读：经 sysfs 路径反查（`getSysfsRootPath` `:199-244`：stat 设备节点取主次设备号→`/sys/dev/char/{major}:{minor}`→向上找 input 目录）关联 AssociatedDevice，扫电池与灯光节点（`:562-628`）。event0 的 sysfs 树无 power_supply/leds 节点时 `hasBattery/hasLights` 为 false，类别位不加 BATTERY/LIGHT（`:2203-2210`）。

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1913-1929（节选）
void EventHub::reportDeviceAddedForStatisticsLocked(const InputDeviceIdentifier& identifier,
                                                    ftl::Flags<InputDeviceClass> classes) {
    SHA256_CTX ctx;
    SHA256_Init(&ctx);
    SHA256_Update(&ctx, reinterpret_cast<const uint8_t*>(identifier.uniqueId.c_str()),
                  identifier.uniqueId.size());
    std::array<uint8_t, SHA256_DIGEST_LENGTH> digest;
    SHA256_Final(digest.data(), &ctx);
    ...
    android::util::stats_write(android::util::INPUTDEVICE_REGISTERED, identifier.name.c_str(),
                               identifier.vendor, identifier.product, identifier.version,
                               identifier.bus, obfuscatedId.c_str(), classes.get());
```

走读：addDeviceLocked 的第一步（`:2339`）以 SHA256 前 8 字节十六进制（`OBFUSCATED_LENGTH=8`，`:72`）混淆 uniqueId 后写 statsd（INPUTDEVICE_REGISTERED）——统计通道与本链路数据通道无交互，本 trace 中该 statsd 写入不可见（盲区，不判定）。

## 4. getEvents 主循环（EventHub.cpp:1533-1788）

### 4.1 入参与总结构

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1533-1544
size_t EventHub::getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize) {
    ALOG_ASSERT(bufferSize >= 1);

    std::scoped_lock _l(mLock);

    struct input_event readBuffer[bufferSize];

    RawEvent* event = buffer;
    size_t capacity = bufferSize;
    bool awoken = false;
    for (;;) {
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
```

走读：调用方 InputReader 只有一个（`InputReader.cpp:103`），buffer 是其 256 条 RawEvent 的成员数组（`EVENT_BUFFER_SIZE=256`，`InputReader.h:173-174`；buffer 定义 `InputReader.h:174`），timeoutMillis 通常为 -1（无限等）。`std::scoped_lock _l(mLock)`（`:1536`）拿住 EventHub 全局锁——但第 4.6 节会看到，epoll_wait 前主动放锁。整个方法是一个 `for(;;)` 大循环，每圈五段：拓扑消费（4.2）、pending 分派与 read（4.3-4.4）、INotify 处理（4.5）、返回判定与底部 epoll_wait（4.6）、返回后的三分支处置（4.7）。

### 4.2 第一段：拓扑变更以合成事件形式先产出

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1557-1571（节选）
        // Report any devices that had last been added/removed.
        for (auto it = mClosingDevices.begin(); it != mClosingDevices.end();) {
            std::unique_ptr<Device> device = std::move(*it);
            ALOGV("Reporting device closed: id=%d, name=%s\n", device->id, device->path.c_str());
            event->when = now;
            event->deviceId = (device->id == mBuiltInKeyboardId)
                    ? ReservedInputDeviceId::BUILT_IN_KEYBOARD_ID
                    : device->id;
            event->type = DEVICE_REMOVED;
            event += 1;
            it = mClosingDevices.erase(it);
            mNeedToSendFinishedDeviceScan = true;
            if (--capacity == 0) {
                break;
            }
        }
```

走读：`mClosingDevices` 逐个转成 `DEVICE_REMOVED` 合成事件（`:1561-1567`），`when` 取循环顶的 `now`（`:1544`/:1561）——合成事件没有内核时戳，readTime 字段未填（`EventHub.h:57-58` 注释明示"对非输入事件未定义"）。随后 `mNeedToScanDevices` 触发全目录重扫（`:1574-1578`），`mOpeningDevices` 逐个转成 `DEVICE_ADDED`（`:1580-1608`，Device 已在 `:1600` 挂入 `mDevices` 表），`mNeedToSendFinishedDeviceScan` 产出一条 `FINISHED_DEVICE_SCAN`（`:1610-1618`）。四问其一（顺序契约的规则是什么）：拓扑事件在本圈一律先于 pending 数据事件写入返回缓冲；谁设置——上一圈 `readNotifyLocked`/`closeDeviceLocked` 把 Device 放进两个待上报队列（`:2416`/`:2340`）；谁执行——本段的 for 循环；后果——InputReader 按 buffer 顺序处理，先执行 addDeviceLocked/removeDeviceLocked 再消费该设备的数据事件，绝不会把数据派给未注册设备。

### 4.3 第二段：pending 分派的 fd 三岔

上一圈 `epoll_wait` 存下的就绪事件数组 `mPendingEventItems`（`EventHub.h:722`，上限 `EPOLL_MAX_EVENTS=16`，`:719`）在此逐项消费：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1622-1647（节选）
        while (mPendingEventIndex < mPendingEventCount) {
            const struct epoll_event& eventItem = mPendingEventItems[mPendingEventIndex++];
            if (eventItem.data.fd == mINotifyFd) {
                if (eventItem.events & EPOLLIN) {
                    mPendingINotify = true;
                }
                ...
                continue;
            }

            if (eventItem.data.fd == mWakeReadPipeFd) {
                if (eventItem.events & EPOLLIN) {
                    ALOGV("awoken after wake()");
                    awoken = true;
                    char wakeReadBuffer[16];
                    ssize_t nRead;
                    do {
                        nRead = read(mWakeReadPipeFd, wakeReadBuffer, sizeof(wakeReadBuffer));
                    } while ((nRead == -1 && errno == EINTR) || nRead == sizeof(wakeReadBuffer));
                }
                ...
                continue;
            }
```

走读：三岔判定按 fd 身份——inotify fd 置 `mPendingINotify` 标记留到 4.5 段处理（`:1624-1631`）；wake pipe 读端 `mWakeReadPipeFd` 把 16 字节缓冲读空、置 `awoken`（`:1633-1647`）；其余 fd 经 `getDeviceByFdLocked`（`:1462-1476`）反查 Device，查无即未知 fd，断言并丢弃（`:1649-1655`）。视频设备分支（`:1656-1675`）与触摸热力图相关、与本链路无关，略。

### 4.4 第三段：数据事件 read 与 RawEvent 填充

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1676-1714
            // This must be an input event
            if (eventItem.events & EPOLLIN) {
                int32_t readSize =
                        read(device->fd, readBuffer, sizeof(struct input_event) * capacity);
                if (readSize == 0 || (readSize < 0 && errno == ENODEV)) {
                    // Device was removed before INotify noticed.
                    ...
                    deviceChanged = true;
                    closeDeviceLocked(*device);
                } else if (readSize < 0) {
                    if (errno != EAGAIN && errno != EINTR) {
                        ALOGW("could not get event (errno=%d)", errno);
                    }
                } else if ((readSize % sizeof(struct input_event)) != 0) {
                    ALOGE("could not get event (wrong size: %d)", readSize);
                } else {
                    int32_t deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;

                    size_t count = size_t(readSize) / sizeof(struct input_event);
                    for (size_t i = 0; i < count; i++) {
                        struct input_event& iev = readBuffer[i];
                        event->when = processEventTimestamp(iev);
                        event->readTime = systemTime(SYSTEM_TIME_MONOTONIC);
                        event->deviceId = deviceId;
                        event->type = iev.type;
                        event->code = iev.code;
                        event->value = iev.value;
                        event += 1;
                        capacity -= 1;
                    }
                    if (capacity == 0) {
                        // The result buffer is full.  Reset the pending event index
                        // so we will try to read the device again on the next iteration.
                        mPendingEventIndex -= 1;
                        break;
                    }
                }
```

走读：一次 `read(device->fd, readBuffer, sizeof(struct input_event) * capacity)` 尽量读满剩余容量（`:1678-1679`）——fd 是 O_NONBLOCK（`:1947`），EAGAIN 静默（`:1687-1690`）；`readSize==0` 或 `ENODEV` 表示设备在 INotify 察觉前已被移除，就地关闭并置拓扑变化（`:1680-1686`）。每个 input_event 逐条转成 RawEvent（`:1697-1707`）：`when` 经 `processEventTimestamp` 沿用内核 input_event 自带时戳（`:177-193`，MONO 时钟——内核详解03 B16/B17 已证其取时点在 evdev 层每次 flush 一次），`readTime` 就地取 `systemTime(SYSTEM_TIME_MONOTONIC)`（`:1700`）。两个字段的差就是"内核写入 client 缓冲到用户态读走"的排队时延，内核详解03 §9.2 已把它列为延迟分解素材。类型/码/值原样透传（`:1702-1704`）——**EventHub 对事件内容零加工**，这是本篇硬结论"读取段不塑造间隔分布"的第一半证据：不丢（每条都转）、不并（逐条转）、不改写（when 沿用、type/code/value 直拷）。

缓冲读满的处置：

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1708-1713
                    if (capacity == 0) {
                        // The result buffer is full.  Reset the pending event index
                        // so we will try to read the device again on the next iteration.
                        mPendingEventIndex -= 1;
                        break;
                    }
```

走读：capacity 用尽即把 pending 索引回退一格，本圈带着已满的 buffer 返回；剩余事件仍在设备缓冲里，下一圈 loopOnce 的 getEvents 从同一 epoll 项续读。分批只切分"单次返回多少条"，不产生延迟判据以外的加工。

### 4.5 第四段：INotify 最后处理

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1726-1738
        // readNotify() will modify the list of devices so this must be done after
        // processing all other events to ensure that we read all remaining events
        // before closing the devices.
        if (mPendingINotify && mPendingEventIndex >= mPendingEventCount) {
            mPendingINotify = false;
            readNotifyLocked();
            deviceChanged = true;
        }

        // Report added or removed devices immediately.
        if (deviceChanged) {
            continue;
        }
```

走读：`readNotifyLocked`（`:2421-2468`）读 inotify 缓冲：IN_CREATE → `openDeviceLocked`（`:2441-2442`）、IN_DELETE → `closeDeviceByPathLocked`（`:2444-2445`）。它必须在数据事件读完之后执行——否则可能在数据读到一半时把对应设备关闭（`:1726-1728` 英文注释明示该顺序约束）。拓扑变化置 `deviceChanged` 后 `continue` 回循环顶，把新增删的 Device 在下一圈的 4.2 段立刻转成合成事件。

### 4.6 第五段：返回判定与底部阻塞

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1740-1762
        // Return now if we have collected any events or if we were explicitly awoken.
        if (event != buffer || awoken) {
            break;
        }

        // Poll for events.
        // When a device driver has pending (unread) events, it acquires
        // a kernel wake lock.  Once the last pending event has been read, the device
        // driver will release the kernel wake lock, but the epoll will hold the wakelock,
        // since we are using EPOLLWAKEUP. The wakelock is released by the epoll when epoll_wait
        // is called again for the same fd that produced the event.
        // Thus the system can only sleep if there are no events pending or
        // currently being processed.
        ...
        mPendingEventIndex = 0;

        mLock.unlock(); // release lock before poll

        int pollResult = epoll_wait(mEpollFd, mPendingEventItems, EPOLL_MAX_EVENTS, timeoutMillis);

        mLock.lock(); // reacquire lock after poll
```

走读：返回条件两个——本圈已凑到至少一条事件（`event != buffer`）或 wake pipe 被读过（`awoken`，哪怕零事件也返回 0，让 loopOnce 走完收尾）。两条件都不满足才走到底部：先放 `mLock`（`:1758`），再 `epoll_wait(mEpollFd, mPendingEventItems, EPOLL_MAX_EVENTS=16, timeoutMillis)`（`:1760`），醒来重拿锁（`:1762`）。

机制四问（读取段的阻塞点）：规则是什么——`timeoutMillis` 取 -1 时无限等待，取 0 时立即返回，取正值时按时返回；内核在无就绪 fd 时把线程置为 S 态。谁设置——timeoutMillis 由 loopOnce 第一段按配置刷新与最近超时算出（`InputReader.cpp:90-100`，第 6.1 节）；fd 集合由构造与 openDeviceLocked 注册。谁执行——内核 epoll（等待方是 InputReader 线程）；唤醒方两类：内核 evdev `wake_up_interruptible_poll`（内核详解03 B8，`evdev.c:287-289`）与用户态 `EventHub::wake()` 写 `mWakeWritePipeFd`（`:1800-1811`）。执行的后果——trace 中该线程的 S 态段位即"停在这三行"；本样本无 syscall/irq 事件，S 的具体等待条件只能证到"代码中唯一长阻塞点是 epoll_wait"这一层（机制对应已证，是否每段 S 都在 epoll_wait 属未证，批14 收口）。

### 4.7 epoll_wait 返回后的三种处置

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1764-1783
        if (pollResult == 0) {
            // Timed out.
            mPendingEventCount = 0;
            break;
        }

        if (pollResult < 0) {
            // An error occurred.
            mPendingEventCount = 0;

            // Sleep after errors to avoid locking up the system.
            // Hopefully the error is transient.
            if (errno != EINTR) {
                ALOGW("poll failed (errno=%d)\n", errno);
                usleep(100000);
            }
        } else {
            // Some events occurred.
            mPendingEventCount = size_t(pollResult);
        }
    }

    // All done, return the number of events we read.
    return event - buffer;
```

走读：返回值三岔。0＝超时到期（timeoutMillis 为正且无事件就绪），清空 pending 计数并带出本圈已收集的事件数（此时 `event == buffer`，返回 0）；负值＝出错，EINTR 静默重试，其余告警后 `usleep(100000)`（100 ms）防止持续出错时打转 CPU；正值＝就绪 fd 数，存入 `mPendingEventCount` 后 `continue` 回循环顶，在 4.3 段逐项消费。函数唯一出口 `return event - buffer`（`:1787`）——返回值就是本圈写入 buffer 的 RawEvent 条数。注意"超时返回 0"与"awoken 返回 0"在返回值上不可区分，调用方 loopOnce 对 0 的处置一致（跳过加工、走 flush 收尾）。

## 5. RawEvent 与 wake 通道

### 5.1 RawEvent：本段的数据面

```cpp
// frameworks/native/services/inputflinger/reader/include/EventHub.h:54-64
struct RawEvent {
    // Time when the event happened
    nsecs_t when;
    // Time when the event was read by EventHub. Only populated for input events.
    // For other events (device added/removed/etc), this value is undefined and should not be read.
    nsecs_t readTime;
    int32_t deviceId;
    int32_t type;
    int32_t code;
    int32_t value;
};
```

走读：#5 RawEvent 六字段（00 篇术语表锚点即此）。`deviceId` 对内建键盘折算为 0（`:1694` 的反折算，与 `getDeviceLocked` 的正向折算 `:1438-1444` 成对）；合成事件的 deviceId 是设备 id 本身（`:1562-1564`/`:1585`）。`when` 对输入事件＝内核时戳、对合成事件＝getEvents 现取 `now`（`:1561`/`:1584`/`:1612`）；`readTime` 只对输入事件有意义（头文件注释 `:57-58`）。

### 5.2 wake()：打断 epoll_wait 的唯一通道

```cpp
// frameworks/native/services/inputflinger/reader/EventHub.cpp:1800-1811
void EventHub::wake() {
    ALOGV("wake() called");

    ssize_t nWrite;
    do {
        nWrite = write(mWakeWritePipeFd, "W", 1);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite != 1 && errno != EAGAIN) {
        ALOGW("Could not write wake signal: %s", strerror(errno));
    }
}
```

走读：向 `mWakeWritePipeFd` 写一个字节即让读端 `mWakeReadPipeFd` 在 epoll 上就绪。Reader 侧全部调用点穷举（grep `mEventHub->wake`）：`InputThread` 析构唤醒（`InputReader.cpp:68` 的第三个 lambda，stop 路径）、`requestTimeoutAtTimeLocked`（`:480`，登记超时后要求读循环按时醒来）、`requestRefreshConfiguration`（`:613`，配置位从零变非零时）、`monitor()`（`:881`，看门狗探活）。四个场景共同的后果：把停在 `epoll_wait` 的读循环拉回 loopOnce 走一圈。

### 5.3 合成事件枚举：三个保留 type 值

```cpp
// frameworks/native/services/inputflinger/reader/include/EventHub.h:229-240
    // Synthetic raw event type codes produced when devices are added or removed.
    enum {
        // Sent when a device is added.
        DEVICE_ADDED = 0x10000000,
        // Sent when a device is removed.
        DEVICE_REMOVED = 0x20000000,
        // Sent when all added/removed devices from the most recent scan have been reported.
        // This event is always sent at least once.
        FINISHED_DEVICE_SCAN = 0x30000000,

        FIRST_SYNTHETIC_EVENT = DEVICE_ADDED,
    };
```

走读：三个保留 type 值与内核事件 type（EV_SYN=0x00、EV_KEY=0x01、EV_ABS=0x03，#3）在数值空间不重叠——0x10000000 以上为用户态专用段，这正是 processEventsLocked 以 `type < FIRST_SYNTHETIC_EVENT` 分流的判据依据（`InputReader.cpp:149`）。`FINISHED_DEVICE_SCAN` 注释明示"至少发送一次"：构造函数把 `mNeedToScanDevices` 初始化为 true（`EventHub.cpp:681`），首圈 getEvents 必走 `scanDevicesLocked` 并产出一条 FINISHED_DEVICE_SCAN（`:1574-1578`＋`:1610-1618`），InputReader 据此在启动期经 `handleConfigurationChangedLocked`（`InputReader.cpp:331-338`）收到一次配置变化通知并回发 NotifyConfigurationChangedArgs。

## 6. InputReader::loopOnce（InputReader.cpp:81-143）

InputReader 是输入读取与转换组件。它通过 EventHub 获取原始事件，交给对应设备的 mapper 处理，再将生成的通知发送给下游。loopOnce 是它的一轮工作：等待或读取事件、处理设备和配置变化，然后转交通知。

### 6.1 五段结构

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:86-111（节选）
    { // acquire lock
        std::scoped_lock _l(mLock);

        oldGeneration = mGeneration;
        timeoutMillis = -1;

        uint32_t changes = mConfigurationChangesToRefresh;
        if (changes) {
            mConfigurationChangesToRefresh = 0;
            timeoutMillis = 0;
            refreshConfigurationLocked(changes);
        } else if (mNextTimeout != LLONG_MAX) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            timeoutMillis = toMillisecondTimeoutDelay(now, mNextTimeout);
        }
    } // release lock

    size_t count = mEventHub->getEvents(timeoutMillis, mEventBuffer, EVENT_BUFFER_SIZE);

    { // acquire lock
        std::scoped_lock _l(mLock);
        mReaderIsAliveCondition.notify_all();

        if (count) {
            processEventsLocked(mEventBuffer, count);
        }
```

走读（// 略 ：113-142 超时处理、代际同步与 flush）：每圈五段——1. 锁内决定 timeoutMillis（有待刷新配置即 0，有登记超时即剩余毫秒数，否则 -1）（`:87-101`）；2. `getEvents` 阻塞拉取（`:103`）；3. 锁内 `processEventsLocked` 全量加工（`:105-111`），随后超时到期检查（`:113-121`）与代际变化检查（`:124-127`）；4. 代际变化时向 policy 上报设备清单快照（`:131-133`）；5. 锁外 `mQueuedListener.flush()`（`:142`）。第三段开头的 `mReaderIsAliveCondition.notify_all()`（`:107`）应答看门狗探活（`monitor()` `:878-885` 的 wait）。

机制四问（loopOnce 的节奏）：规则是什么——每圈最多消费 256 条 RawEvent，加工与投递交替；无事件时第 2 段无限阻塞。谁设置——EVENT_BUFFER_SIZE 与锁序由 InputReader.h/loopOnce 静态确定；timeoutMillis 由配置请求与 mapper 超时登记动态决定。谁执行——InputReader 线程（`InputThread` 名 "InputReader"，tid 1489 即本线程）。执行的后果——trace 的 R/Running/S 序列与此结构一一对应（第 8 节）：Running 窗口对应第 1/3/4/5 段，S 窗口对应第 2 段底部 epoll_wait。

### 6.2 processEventsLocked：两类去向与按设备分批

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:145-181（节选）
void InputReader::processEventsLocked(const RawEvent* rawEvents, size_t count) {
    for (const RawEvent* rawEvent = rawEvents; count;) {
        int32_t type = rawEvent->type;
        size_t batchSize = 1;
        if (type < EventHubInterface::FIRST_SYNTHETIC_EVENT) {
            int32_t deviceId = rawEvent->deviceId;
            while (batchSize < count) {
                if (rawEvent[batchSize].type >= EventHubInterface::FIRST_SYNTHETIC_EVENT ||
                    rawEvent[batchSize].deviceId != deviceId) {
                    break;
                }
                batchSize += 1;
            }
            ...
            processEventsForDeviceLocked(deviceId, rawEvent, batchSize);
        } else {
            switch (rawEvent->type) {
                case EventHubInterface::DEVICE_ADDED:
                    addDeviceLocked(rawEvent->when, rawEvent->deviceId);
                    break;
                ...
            }
        }
        count -= batchSize;
        rawEvent += batchSize;
    }
}
```

走读：分流判据 `type < FIRST_SYNTHETIC_EVENT`（`EventHub.h:239`，合成事件枚举从 0x10000000 起）：普通事件按 deviceId 凑批（连续同设备事件共用一次查表，`:151-157`）交给 `processEventsForDeviceLocked`（`:290-305`）——查无该 eventHubId 即丢弃并告警（`:293-296`），被 ignore 的设备整批丢弃（`:299-302`），其余 `device->process(rawEvents, count)`（`:304`，批05 从 InputDevice::process 进入 mapper）。合成事件走 switch：DEVICE_ADDED/REMOVED/​FINISHED_DEVICE_SCAN 三分支（`:163-176`）。**分批只减少 map 查找次数，事件顺序不变**——批内逐条进 mapper，这保证了"同一设备的 DOWN/MOVE/SYN 顺序与内核产出顺序一致"。

### 6.3 超时机制：从登记到广播

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:477-482
void InputReader::requestTimeoutAtTimeLocked(nsecs_t when) {
    if (when < mNextTimeout) {
        mNextTimeout = when;
        mEventHub->wake();
    }
}
```

走读：mapper 经 ContextImpl 登记超时（`ContextImpl::requestTimeoutAtTime` `:934-937` 转发至此）。登记取"距今最近的到期时刻"（`:478-479`）并 `wake()` 打断当前 epoll_wait——下一圈 loopOnce 第一段据此算出正的 timeoutMillis，让 `epoll_wait` 按时返回（第 4.6 节）。到期后在 loopOnce 第三段经 `timeoutExpiredLocked`（`:318-325`）把超时时刻广播给每个设备的全部 mapper：TouchInputMapper 的 POINTER 手势模式用它合成手势事件、DIRECT 模式用它判触笔融合超时（`TouchInputMapper.cpp:1787-1795`）。这是 Reader 线程上唯一的时间性唤醒源；虚拟按键抑制（`shouldDropVirtualKeyLocked` `:425-434`）只查时间窗、不注册超时。

### 6.4 SYN_DROPPED 在本段的入口

RawEvent 的 type/code 直拷意味着内核的 SYN_DROPPED（#2 的姊妹标记，内核详解03 B12/B13）会原样到达 InputReader。`InputDevice::process` 的恢复协议（`InputDevice.cpp:394-415`：见 SYN_DROPPED 即 `mDropUntilNextSync = true` 并 reset，丢弃后续事件直至下一个 SYN_REPORT）属于内核详解03 B14 已证结论；本段的执行位置是：恢复动作发生在 InputReader 线程的 processEventsLocked→InputDevice::process 内，不涉及 EventHub。本样本 touch.txt 无 SYN_DROPPED（内核详解03 B23 是 getevent client 口径），EventHub client 口径仍属未证（内核详解03 B24 延续，§11）。

### 6.5 代际机制：设备清单变更的同步通道

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:124-133
        if (oldGeneration != mGeneration) {
            inputDevicesChanged = true;
            inputDevices = getInputDevicesLocked();
        }
    } // release lock

    // Send out a message that the describes the changed input devices.
    if (inputDevicesChanged) {
        mPolicy->notifyInputDevicesChanged(inputDevices);
    }
```

走读：`mGeneration` 在 `addDeviceLocked`/`removeDeviceLocked` 经 `bumpGenerationLocked()` 自增（`:213`/`:245`，定义 `:484-486`），新建 InputDevice 时也以其返回值作构造参数（`:283`）。loopOnce 第一段留存旧代际（`:89`），第三段比对（`:124-127`）；变化即经 policy 上报设备清单快照（`:132`）——该上报走 InputReaderPolicyInterface（系统侧 JNI），不进监听器链，与事件数据通道分离。四问其一（规则是什么）：代际是设备拓扑版本号；谁设置：拓扑变更点自增；谁执行：loopOnce 第三段比对；后果：InputManagerService 持有最新设备列表（供 Java 侧查询与 Blocker 的 notifyInputDevicesChanged，`UnwantedInteractionBlocker.cpp:406-412` 消费）。

### 6.6 ContextImpl：mapper 访问 Reader 能力的收窄句柄

```cpp
// frameworks/native/services/inputflinger/reader/InputReader.cpp:957-967
InputListenerInterface& InputReader::ContextImpl::getListener() {
    return mReader->mQueuedListener;
}

EventHubInterface* InputReader::ContextImpl::getEventHub() {
    return mReader->mEventHub.get();
}

int32_t InputReader::ContextImpl::getNextId() {
    return mIdGenerator.nextId();
}
```

走读：ContextImpl 实现 InputReaderContext（构造 `:889-890`，持 `IdGenerator::Source::INPUT_READER`），把 Reader 的能力暴露为十个受限方法（`:892-951`）——全局元态、LED 元态、虚拟按键抑制窗口、指针控制器、超时登记、代际自增、监听器、EventHub、id 生成。注释统一声明"lock is already held by the input loop"（如 `:893`）：这些方法只在读循环持锁期间被 mapper 调用，接口上排除了 mapper 自行加锁的路径。mapper 经 `getContext()` 拿到它（批05 的入口面）。

## 7. QueuedInputListener 与监听器链

QueuedInputListener 是暂存和转发输入通知的组件。上游先将事件加入队列，flush 时再按顺序交给下游监听器；它帮助输入读取阶段与后续处理衔接。该队列与应用侧将多个 MOVE 样本合成一个 MotionEvent 的批处理是不同机制。

### 7.1 生产口与出队口

```cpp
// frameworks/native/services/inputflinger/InputListener.cpp:361-364
void QueuedInputListener::notifyMotion(const NotifyMotionArgs* args) {
    traceEvent(__func__, args->id);
    mArgsQueue.emplace_back(std::make_unique<NotifyMotionArgs>(*args));
}
```

```cpp
// frameworks/native/services/inputflinger/InputListener.cpp:391-396
void QueuedInputListener::flush() {
    for (const std::unique_ptr<NotifyArgs>& args : mArgsQueue) {
        args->notify(mInnerListener);
    }
    mArgsQueue.clear();
}
```

走读：#32 QueuedInputListener 两个动作——notifyMotion 深拷贝入队（`:363`，args 指向栈上对象，副本生命周期归队列）并先打一次 traceEvent（`:362`）；flush 把队列逐条经 `args->notify(mInnerListener)` 回放给下一级（`:392-394`）——`NotifyMotionArgs::notify` 就是 `listener.notifyMotion(this)`（`:223-225`），下游代码由此继承 flush 调用方线程（Reader 线程），不另起线程。Reader 在 loopOnce 第 5 段锁外 flush（`InputReader.cpp:135-142`），英文注释说明锁外原因：下游会回调回 Reader 方法或被另一线程的 Reader 锁阻塞，锁内 flush 即死锁。

跨段的投递对象 NotifyMotionArgs 携带本篇追踪的两个时戳与事件 id：

```cpp
// frameworks/native/services/inputflinger/InputListener.cpp:98-106、:124-125（节选）
NotifyMotionArgs::NotifyMotionArgs(
        int32_t id, nsecs_t eventTime, nsecs_t readTime, int32_t deviceId, uint32_t source,
        int32_t displayId, uint32_t policyFlags, int32_t action, int32_t actionButton,
        int32_t flags, int32_t metaState, int32_t buttonState, MotionClassification classification,
        int32_t edgeFlags, uint32_t pointerCount, const PointerProperties* pointerProperties,
        const PointerCoords* pointerCoords, float xPrecision, float yPrecision,
        float xCursorPosition, float yCursorPosition, nsecs_t downTime,
        const std::vector<TouchVideoFrame>& videoFrames)
      : NotifyArgs(id, eventTime),
        deviceId(deviceId),
        ...
        readTime(readTime),
        videoFrames(videoFrames) {
```

走读（// 略 ：106-123 其余字段初始化）：`id` 与 `eventTime` 在基类 NotifyArgs（`:106`），`readTime` 独立成字段（`:124`）——三个时刻贯穿全链：eventTime＝内核 evdev 层取时（内核详解03 B17），readTime＝EventHub 读取时刻（4.4 节），二者进入 args 后不再改写（7.3 节的链上拷贝保留全部字段，唯 classification 可能被 Classifier 改写、action/flags 可能被 Blocker 的拒掌拆分改写）。dump() 方法（`:191-221`）以 `NotifyMotionArgs(id=%…, eventTime=%…, …)` 格式化——批06/15 复核事件身份时可用 logtag 输出对照 trace slice 名。

### 7.2 事件 id 的生成点：mapper dispatchMotion

```cpp
// frameworks/native/services/inputflinger/reader/mapper/TouchInputMapper.cpp:3732-3737
    NotifyMotionArgs args(getContext()->getNextId(), when, readTime, deviceId, source, displayId,
                          policyFlags, action, actionButton, flags, metaState, buttonState,
                          MotionClassification::NONE, edgeFlags, pointerCount, pointerProperties,
                          pointerCoords, xPrecision, yPrecision, xCursorPosition, yCursorPosition,
                          downTime, std::move(frames));
    getListener().notifyMotion(&args);
```

走读：事件 id 在此生成（`getContext()->getNextId()` → `ContextImpl::getNextId` → `IdGenerator::nextId`，`InputReader.cpp:965-967`＋`Input.h:337`）——**每次 dispatchMotion 一次新 id**。`getListener()` 即 `mReader->mQueuedListener`（`ContextImpl::getListener`，`InputReader.cpp:957-959`）：mapper 产出的 args 直接进 Reader 的 QueuedInputListener 队列，等 loopOnce 末尾 flush。TouchInputMapper 的调用点穷举（grep `getListener().notifyMotion`）：`:2625/:3552/:3566/:3582/:3593/:3608/:3619/:3641/:3672/:3737` 十处，覆盖 hover 出入、MOVE 拆分（dispatchTouches 的 POINTER_UP 前移与残余 MOVE 两次 dispatch，`:1994/:2009`）、CANCEL（`:1939`）与主 dispatchMotion（`:3737`）——一次 RawEvent 批量可能产生多条 NotifyMotionArgs，但各自持有**不同的新 id**。烹调细节归批05。

MOVE 拆分的两连发在 dispatchTouches 里成对出现（无指针增减时单发，`:1955-1965`；有增减时先 POINTER_UP 再 MOVE）：

```cpp
// frameworks/native/services/inputflinger/reader/mapper/TouchInputMapper.cpp:1987-2009（节选）
        // Dispatch pointer up events.
        while (!upIdBits.isEmpty()) {
            uint32_t upId = upIdBits.clearFirstMarkedBit();
            bool isCanceled = mCurrentCookedState.cookedPointerData.canceledIdBits.hasBit(upId);
            if (isCanceled) {
                ALOGI("Canceling pointer %d for the palm event was detected.", upId);
            }
            dispatchMotion(when, readTime, policyFlags, mSource, AMOTION_EVENT_ACTION_POINTER_UP, 0,
                           isCanceled ? AMOTION_EVENT_FLAG_CANCELED : 0, metaState, buttonState, 0,
                           mLastCookedState.cookedPointerData.pointerProperties,
                           mLastCookedState.cookedPointerData.pointerCoords,
                           mLastCookedState.cookedPointerData.idToIndex, dispatchedIdBits, upId,
                           mOrientedXPrecision, mOrientedYPrecision, mDownTime);
            dispatchedIdBits.clearBit(upId);
            mCurrentCookedState.cookedPointerData.canceledIdBits.clearBit(upId);
        }

        // Dispatch move events if any of the remaining pointers moved from their old locations.
        // Although applications receive new locations as part of individual pointer up
        // events, they do not generally handle them except when presented in a move event.
        if (moveNeeded && !moveIdBits.isEmpty()) {
            ALOG_ASSERT(moveIdBits.value == dispatchedIdBits.value);
            dispatchMotion(when, readTime, policyFlags, mSource, AMOTION_EVENT_ACTION_MOVE, 0, 0,
                           metaState, buttonState, 0,
```

走读：同一个 RawEvent 批次内的一个触摸状态变化最多派生两条 NotifyMotionArgs（POINTER_UP 一条＋MOVE 一条），每条独立取 id（7.2 节）；`when`/`readTime` 在两条里相同（同一处理周期）。本样本接触全程单指 MOVE（批00 §3.1"接触只见一次 DOWN/TRACKING_ID 无 UP"），主 dispatchMotion（`:3737`）即其唯一产出路径——批05 展开 cook 判定。

### 7.3 同 ID 三条 notifyMotion：三次独立打点的机制解释

trace 里同一事件 id 的 notifyMotion slice 有三条（旧样本"三条同层排列"，契约 :78；新样本亦然，8.4 节实证）。批06 需要的机制解释本篇给出（已证，代码＋trace 双面）：

1. **打点函数只有一个**：slice 名 `notifyMotion(id=0x…)` 的格式串在 `traceEvent`（`InputListener.cpp:340-345`，`StringPrintf("%s(id=0x%" PRIx32 ")")`），全仓库 grep `traceEvent` 仅此一处定义、八个 notifyX 各调一次（`:352-387`）。
2. **监听器链上有三个 QueuedInputListener 实例**：Reader 的 `mQueuedListener`（`InputReader.h:168`）、Blocker 的 `mQueuedListener`（`UnwantedInteractionBlocker.h:112`）、Classifier 的 `mQueuedListener`（`InputClassifier.h:264`）。三个实例共用同一个 QueuedInputListener::notifyMotion 实现，各自打一次 traceEvent。
3. **args.id 在链上保持不变**：Blocker 的 `processMotion` 输出沿用输入 id（`UnwantedInteractionBlocker.cpp:151` 的 `NotifyMotionArgs newArgs{args}` 拷贝构造保留 id；`:350-353` enqueueOutboundMotionLocked 原样转发），Classifier 的 `newArgs(*args)` 同样保留（`InputClassifier.cpp:438`）。id 只在 mapper dispatchMotion 生成一次（7.2 节）。

```cpp
// frameworks/native/services/inputflinger/UnwantedInteractionBlocker.cpp:335-348
void UnwantedInteractionBlocker::notifyMotion(const NotifyMotionArgs* args) {
    ALOGD_IF(DEBUG_INBOUND_MOTION, "%s: %s", __func__, args->dump().c_str());
    { // acquire lock
        std::scoped_lock lock(mLock);
        const std::vector<NotifyMotionArgs> processedArgs =
                mPreferStylusOverTouchBlocker.processMotion(*args);
        for (const NotifyMotionArgs& loopArgs : processedArgs) {
            notifyMotionLocked(&loopArgs);
        }
    } // release lock

    // Call out to the next stage without holding the lock
    mQueuedListener.flush();
}
```

```cpp
// frameworks/native/services/inputflinger/InputClassifier.cpp:430-444
void InputClassifier::notifyMotion(const NotifyMotionArgs* args) {
    { // acquire lock
        std::scoped_lock lock(mLock);
        // MotionClassifier is only used for touch events, for now
        const bool sendToMotionClassifier = mMotionClassifier && isTouchEvent(*args);
        if (!sendToMotionClassifier) {
            mQueuedListener.notifyMotion(args);
        } else {
            NotifyMotionArgs newArgs(*args);
            newArgs.classification = mMotionClassifier->classify(newArgs);
            mQueuedListener.notifyMotion(&newArgs);
        }
    } // release lock
    mQueuedListener.flush();
}
```

走读：一条 args 从 Reader flush 出发（第一条 slice），进 Blocker::notifyMotion 处理后经其自身队列 flush（第二条 slice，`mQueuedListener.notifyMotion` 于 enqueueOutboundMotionLocked `:352`），再进 Classifier::notifyMotion 经其队列 flush（第三条 slice，入队点 `:436` 直通或 `:440` 分类后转发，取决于 mMotionClassifier 是否启用；本样本运行时取值无采集证据，两分支都经同一 mQueuedListener.notifyMotion 打点），最后到 `InputDispatcher::notifyMotion`。三条 slice 是**三次独立的函数调用打点**，不是同一 EventEntry 的多次记录——EventEntry 是 dispatcher 侧对象（`dispatcher/Entry.h:32`），此刻尚未创建；三条 slice 的时长（本样本 6,577/5,692/5,231 ns）与 traceEvent 的 StringPrintf＋ATRACE 成本一致。**未证边界**：三条 slice 与三个实例的逐一对应、以及三条之间的间隔差（首条到次条 255,308 ns、次条到三条 16,269 ns，本批新算，过程记录留痕）在 Blocker/Classifier 内部的精确归因（拒掌模型处理时长、cancelSuppressedPointers 拆分 `UnwantedInteractionBlocker.cpp:265-315`）——slice 名无实例区分度，纯 trace 不可判，留批06 结合该窗口事件 action 序列判定。

## 8. trace 配对：读取段的线程状态与 slice 段位

### 8.1 线程身份与时间点链

本篇涉及的 trace 对象（tid 只适用于本样本，契约 :62；批00 §3.3）：

| trace 对象 | 值 | 出处 |
|---|---|---|
| 线程轨 | InputReader tid=1489 | 契约 :62 |
| 事件 id | `0x3b3b7f02` | 契约 :70/:72 |
| event0 报点（SYN_REPORT） | 约 `00:00:06.787410` | 契约 :70 |
| Reader R 态 | `00:00:06.787427` 起，7,951,192 ns | 契约 :71；批0 复验 :17 |
| Reader CPU3 Running | `00:00:06.795378` 起，996,346 ns | 契约 :71；批0 复验 :45 |
| 同 ID notifyMotion 首条 | `00:00:06.795839`（rel 6,795,838,649 ns，dur 6,577 ns） | 契约 :72；批0 复验 :18 |
| Reader S 态（第一段） | `00:00:06.796374` 起，3,852,692 ns | 批0 复验 :45＋原始输出 :113 |
| event0 后续报点 | 约 `00:00:06.800210` | 契约 :70 |
| Reader D 态（write_pmsg） | `00:00:06.801837–00:00:06.805553`，3,716,500 ns | 契约 :74；批0 复验 :19 |
| Reader 完整状态序列 | R 7,951,192 → Running 996,346 → S 3,852,692 → R 848,846 → Running 177,731 → R+ 461,962 → Running 120,884 → D 3,716,500 → R 1,602,116 → … → S 8,639,231 → … | 批0 复验 :44-46 |

### 8.2 R 7,951,192 的段位：可运行未运行，不是处理耗时

R 态（#16）的语义是 TASK_RUNNING——可运行、未获 CPU。链条：evdev 在 SYN_REPORT 写完 client 缓冲后 `wake_up_interruptible_poll` 唤醒 epoll 上的读端（内核详解03 B8，`evdev.c:287-289`）→ 唤醒事件进入调度器，`sched_waking` target_cpu=3、waker 关联 kworker/4:1（#17/#18 的 maybe interrupt 标记不证明代码调用者，契约 :71）→ Reader 于 `00:00:06.787427` 进入 R 态（距 event0 报点约 00:00:06.787410 仅 17 μs 量级）→ **在 CPU3 上等待 7,951,192 ns 未获调度**（窗口内与 getevent 1.717 ms、ADB shell svc 1.096 ms、slogmodem 1.087 ms 等 CPU3 线程重叠，批00 §3.7 已列，因果未证）→ `00:00:06.795378` 转 Running。

机制段位对应（已证）：

- R 窗口内：getEvents 未返回——`:1760` 的 epoll_wait 已醒（内核已把 fd 置就绪并存入 mPendingEventItems），但执行体还没上 CPU。R 起点 `00:00:06.787427` 在 event0 报点 `00:00:06.787410` 之后约 17 μs（两数作差的算术值，过程记录 §2 留痕），与"内核 wake_up → 可运行"的机制链一致。
- Running 996,346 ns（`00:00:06.795378`–`00:00:06.796374`）内：getEvents 返回（含这批 RawEvent 的逐条产出）→ loopOnce 第三段 processEventsLocked → mapper 分派 → dispatchMotion 生成 id → QueuedInputListener 入队 → flush → 三级监听器链 → 三条同 ID notifyMotion slice。首条 `00:00:06.795839`（dur 6,577 ns）落在该窗口内（批0 复验 :46 同判定）。
- **口径结论（已证）**：R 7,951,192 ns 刻画的是调度等待，不是读取段处理耗时；读取段的实际处理窗口是 Running 996,346 ns。契约批15 纪律"R 7.951 ms 不外推为 50.928 ms 的原因"（:186）在本段位上有代码级支撑：R 期间读取段一行代码未执行。

### 8.3 S 3,852,692 的等待点：机制对应到 epoll 层

`00:00:06.796374` 起的 S 3,852,692 ns（至 `00:00:06.800227`）：flush 完成后 loopOnce 本圈结束，下一圈回到 `getEvents`——timeoutMillis=-1（无配置刷新、mNextTimeout 无登记）、底部 `epoll_wait` 无事件可读，线程入 S 态。

- **机制对应（已证）**：Reader 线程代码中唯一的长阻塞点是 `EventHub.cpp:1760` 的 epoll_wait（第 4.6 节穷举：其余代码段全持锁快速执行；`monitor()` 的条件变量 wait 只在探活线程）。S＝等待事件。
- **等待条件（未证）**：本 trace 无 syscall/irq 事件，无法直接证明这段 S 确实停在 epoll_wait 系统调用内（理论上有其他可能性——如被信号打断的短暂返回、mLock 等待——但代码路径上无对应的长等待点）。批00 §3.7 的全局盲区声明在本段位维持，最终确认留批14。
- **配对佐证（机制级）**：S 终点 `00:00:06.800227` 与 event0 下一报点约 `00:00:06.800210` 相邻（差约 17 μs，两数取自契约 :70 与批0 复验 :45 的算术差，过程记录留痕）；其后 R 848,846 → Running 177,731 承接第二批事件的加工。evdev `wake_up` 唤醒 epoll 的机制链（内核详解03 B8）为该配对提供机制解释，trace 侧的逐点因果（内核事件→线程状态）仍属批14 的调度学框架。

### 8.4 同 ID 三条 notifyMotion 的 trace 实证（本批新查询，留痕于过程记录 §2）

对最新样本执行 slice 查询（tid=1489，窗口 rel 6.790–6.810 s）：

| id | rel 起点 | dur |
|---|---|---|
| 0x3b3b7f02 | `00:00:06.795838649` | 6,577 ns |
| 0x2ff164c | `00:00:06.796067688` | 6,577 ns |
| 0x3b3b7f02 | `00:00:06.796093957` | 5,692 ns |
| 0x3b3b7f02 | `00:00:06.796110226` | 5,231 ns |
| 0x2ff164c | `00:00:06.796274572` | 5,731 ns |
| 0x2ff164c | `00:00:06.796287726` | 5,346 ns |
| 0x2f3ae6d0 | `00:00:06.807827342` | 8,423 ns |
| 0x2f3ae6d0 | `00:00:06.807898803` | 6,385 ns |
| 0x2f3ae6d0 | `00:00:06.807918995` | 5,500 ns |

走读（已证，trace 侧）：三个事件 id 各出现**恰好三条** notifyMotion slice，全部在 InputReader tid=1489 线程轨；前六条（0x3b3b7f02 与 0x2ff164c）全部落在批0 复验 :45 记录的 Running 996,346 ns 窗口（`00:00:06.795378`–`00:00:06.796374`）内，0x2f3ae6d0 三条位于其后的第二批加工段（该窗口明细在批0 状态序列的省略段内，未逐窗核对）。单条时长 5-9 μs 与 traceEvent 成本一致——与 7.3 节"链上三个 QueuedInputListener 各打一次"的代码结构吻合。间隔差：0x3b3b7f02 首条→次条 255,308 ns、次条→三条 16,269 ns（本批新算）。**逐条归属与间隔成因未证**（slice 名无实例区分度，留批06）；**可排除项（已证）**：三条不是"同一 EventEntry 的多次记录"——EventEntry 在 dispatcher 侧、此刻未创建；也不是 mapper 三次 dispatch 同 id——每次 dispatchMotion 都取新随机 id（7.2 节，`TouchInputMapper.cpp:3732`）。

### 8.5 D 3,716,500 的边界：不属于读取段

`00:00:06.801837–00:00:06.805553` 的 D 态 3,716,500 ns、blocked_function 含 write_pmsg（#19/#20），时间上发生在首条 notifyMotion（00:00:06.795839）之后、第二批事件加工途中（批0 复验 :45 序列）。写 /dev/pmsg 是 logd 域动作（#19 锚点 `fs/pstore/pmsg.c:14`），不在 EventHub/InputReader 代码路径内——**读取段代码无可中断睡眠点**（第 4.4/4.6 节：read 是 O_NONBLOCK、epoll_wait 是可中断睡眠 S 态语义），D 态成因与同 ID 关键路径的因果均未证（契约 :74），批14 结合 pmsg 写路径展开。本篇只做段位排除：D 不是读取段的机制产物。

### 8.6 旧样本对照（finger.perfetto，契约 :78）

契约记录的旧样本读取段观察与本篇机制一一对应（旧样本 tid=1472，BOOTTIME 口径需换算，契约 :78；批00 §3.3）：

| 旧样本观察（契约 :78 逐字） | 本篇机制解释 |
|---|---|
| Reader 三条同 ID notifyMotion 同层排列（图21/24） | D21＋§8.4：链上三个 QueuedInputListener 各打一次，同层三条为 A13 结构常态（已证机制；旧样本 slice 归属逐条核对未做） |
| filterInputEvent 0.343308/0.346038 ms 两条（图14/15） | 监听器链出口之后 dispatcher 段的 policy 过滤（`InputDispatcher.cpp:3956`/`:4047` 的 `mPolicy->filterInputEvent`，批06 展开；读取段不含此段） |
| Dispatcher S 28.505923 ms（图22） | dispatcher 侧等待语义（批06 的 poll 判据），与读取段 S（§8.3）机制同型、线程不同 |
| deliver id=0x37ee8b11 eventTime→回调差 8.169 ms（图09） | eventTime 计时起点＝本篇 4.4 节 `when` 字段来源（内核详解03 B17），43.597 ms（新样本）与 8.169 ms（旧样本）同口径可对照（批15） |

对照结论：旧样本对事件 0x37ee8b11 记录三条同层 slice（契约 :78）；新样本在 §8.4 查询窗口内三个 id 各恰三条——两样本的"每 id 三条"结构与 D21 的三队列机制一致，支持"结构常态"判定而非异常；两样本数值不混用（口径不同，契约 :78）。

## 9. 机制四问索引表

各机制的四问在正文对应节内作答，此处汇总供批15 引用：

| 机制 | 规则 | 谁设置 | 谁执行 | 后果 | 正文 |
|---|---|---|---|---|---|
| 监听器链装配 | Reader→Blocker→Classifier→Dispatcher 单向 | InputManager.cpp:63-66 | 各级 notifyX＋flush | 事件逐级加工投递 | §2.1 |
| EventHub 阻塞模型 | 三类 fd 单一 epoll_wait | 构造 :711-735＋:2228 | 内核 epoll＋evdev wake_up＋wake() | 无事件 S 态零 CPU；到达即 R | §2.3/§4.6 |
| 顺序契约 | 拓扑事件先于数据事件 | readNotifyLocked/closeDeviceLocked | getEvents 4.2 段 | 数据不派给未注册设备 | §4.2 |
| getEvents 返回条件 | 有事件或 awoken 即返回 | — | getEvents :1741 | 零事件也回一圈应答 wake | §4.6 |
| timeoutMillis | 刷新=0/超时=剩余/否则 -1 | 配置请求＋mapper 登记 | loopOnce :90-100 | epoll_wait 按时返回 | §6.1/§6.3 |
| QueuedInputListener | 深拷贝入队、锁外 flush | 构造期装配 | loopOnce :142 | 下游继承 Reader 线程 | §7.1 |
| 事件 id | dispatchMotion 一次生成、链上保留 | IdGenerator | mapper | 同 id 三 slice 的共同来源 | §7.2/§7.3 |
| EPOLLWAKEUP | 有数据未读即持 wakeup lock | registerFdForEpoll | 内核 epoll | 处理期间系统不休眠 | §2.3/§3.2 |

## 10. 与两个问题的关联

**34.738 ms notifyMotion→prepare（00:00:06.795839→00:00:06.830577）**：首条 notifyMotion 产生于 Reader Running 窗口内（8.2 节），此刻 args 已沿监听器链到达 `InputDispatcher::notifyMotion` 并入 mInboundQueue（#29，批06）。读取段在该空档内的活动只有后续批次的 S/R/D 循环（8.3/8.5 节），其中 D write_pmsg 与空档的因果未证、S 为正常等事件——**读取段不构成该空档的机制段**（已证，机制排除：链路交付完成点早于空档起点）。候选全表在批06。

**50.928 ms 主线程 Sleeping（00:00:06.779837–00:00:06.830765）**：主线程 S 与 Reader 线程 S 是两条线程轨上的独立状态；读取段代码不触碰主线程（无共享锁、无信号），Reader 侧对该问题仅有间接关联——若读取段延迟交付，主线程的醒点会顺延（本样本主线程醒点 00:00:06.830765 的交付事件属批07/08 段位）。批02 已排除 input core 参与；本篇把同一排除推进到读取段：**读取段内无可中断等待、无丢弃、无合并**（已证，§4/§6 穷尽），该排除依据供批15 使用。

**37.237 ms 重点间隔（00:00:06.750173→00:00:06.787410）**：两个端点都在读取段入口之前——间隔在 evdev client 缓冲里已定型（内核详解03 §7.2）；getEvents 对可读数据全量转发、RawEvent 化零加工（4.4 节），InputReader 逐条下发（6.2 节）。**读取段不参与塑造该分布**（已证）。间隔成因归属保持批01-03 的结论：host 报点节奏（未证/盲区）。重点节点间隔序列（契约 :76：37.237/2.842/9.958/16.588/16.390 ms）与读取段的段位对应：批次之间唯一的等待点是 getEvents 底部的 epoll_wait，醒来后的处理节奏受调度影响（R 窗口即等待 CPU，8.2 节），读取段内部不存在额外等待机制（已证，§4/§6 穷尽）。

## 11. 未证边界与盲区清单

1. **未证**：Reader 各段 S 的等待条件是否确为 epoll_wait 系统调用内（trace 无 syscall/irq 记录；机制对应已证到代码层，批14 收口）。
2. **未证**：R 7,951,192 ns 的 CPU3 竞争归因（候选重叠线程清单在批00 §3.7；因果未证）。
3. **未证**：D 3,716,500 ns（write_pmsg）与同 ID 关键路径的因果（契约 :74；批14）。
4. **未证**：三条同 ID notifyMotion slice 与三个 QueuedInputListener 实例的逐一对应，及条间间隔差 255.308 μs/16.269 μs 的内部归因（留批06）。
5. **未证**：EventHub client 缓冲是否溢出过（内核详解03 B24 延续；无日志/trace 证据）。
6. **盲区**：mPendingEventItems 的 EPOLL_MAX_EVENTS=16 上限在本流是否触顶（单次 epoll_wait 返回 >16 个就绪 fd 的情形无 trace 证据，不判定存在或不存在）。
7. **盲区**：Blocker 内拒掌模型（isPalmRejectionEnabled 的 server configurable flag，`UnwantedInteractionBlocker.cpp:91-99`）在本设备的开关状态——代码机制已证，运行时取值无采集证据；flag 为 0 时 mPalmRejectors 为空表（`notifyInputDevicesChanged` 不建实例，`:406-411`），notifyMotion 走直通分支。本样本三条 slice 的条间 255 μs 间隔与模型推理时长的关系依赖该取值（留批06）。
8. **盲区（全局沿用）**：基准源码树与设备 build 的一致性未验证（批00 §3.6）；本文机制结论适用于 A13 源码，套用到设备行为须带此前提。
9. **留待批05**：mapper 侧从 `InputDevice::process` 到 dispatchMotion 的烹调（batching、cookTouches、MotionEvent 组装）；`RawEvent.readTime` 与 `when` 在 mapper 层的传递路径。
10. **留待批06**：`InputDispatcher::notifyMotion` 的入队与裁决；三条同 ID slice 的逐条归属判定。

## 12. 断言与三态汇总

本篇机制性结论的编号与三态（断言-证据对照的完整表在《过程记录/批04-过程记录.md》，编号一致）：

| 编号 | 结论 | 三态 | 关键证据 |
|---|---|---|---|
| D1 | 装配序 Reader→Blocker→Classifier→Dispatcher，各级各持一个 QueuedInputListener | 已证 | InputManager.cpp:63-66/:56-59；InputReader.h:168；UnwantedInteractionBlocker.h:112；InputClassifier.h:264 |
| D2 | InputReader 线程名/优先级/循环体来源 | 已证 | InputReader.cpp:67-69；InputThread.cpp:34-46 |
| D3 | EventHub 构造三 fd（epoll/inotify/wake）＋EPOLLWAKEUP＋CAP_BLOCK_SUSPEND 校验 | 已证 | EventHub.cpp:687-736/:663-669 |
| D4 | 设备 fd 注册统一走 registerFdForEpoll（EPOLLIN\|EPOLLWAKEUP） | 已证 | EventHub.cpp:1850-1860/:2228-2230 |
| D5 | 打开设备＝open(O_NONBLOCK)＋身份五 ioctl＋能力位图八种＋类别判定＋configureFd | 已证 | EventHub.cpp:1947/:1956-2061/:2090-2128/:2232 |
| D6 | event0 判 TOUCH\|TOUCH_MT；无 KEYBOARD 类 | 已证（联合） | EventHub.cpp:2090-2096；内核详解01 §4.5；touch.txt BTN_TOUCH |
| D7 | descriptor 生成与多节点复用 | 已证 | EventHub.cpp:1310-1358；InputReader.cpp:273-287 |
| D8 | configureFd 切 MONO（SENSOR 为 BOOTTIME）；EVIOCSREP 仅 KEYBOARD 类 | 已证 | EventHub.cpp:426-448；内核详解03 B18 |
| D9 | A13 长按重复由 InputDispatcher 合成（修正既有批注） | 已证 | InputDispatcher.cpp:755-758；KeyboardInputMapper.cpp:349-351 |
| D10 | getEvents 五段结构与拓扑先行顺序契约 | 已证 | EventHub.cpp:1543-1762 |
| D11 | 数据事件逐条 RawEvent 化，零加工（不丢不并不改写） | 已证 | EventHub.cpp:1696-1707 |
| D12 | 缓冲满回退续读；分批不丢事件 | 已证 | EventHub.cpp:1708-1713 |
| D13 | INotify 最后处理约束 | 已证 | EventHub.cpp:1726-1733/:2421-2468 |
| D14 | 返回条件：有事件或 awoken；epoll_wait 前放锁 | 已证 | EventHub.cpp:1740-1762/:1758 |
| D15 | RawEvent 双时戳语义（when=内核时戳，readTime=读取时刻） | 已证 | EventHub.h:54-64；EventHub.cpp:1699-1700 |
| D16 | wake() 四调用方穷举 | 已证 | InputReader.cpp:68/:480/:613/:881；EventHub.cpp:1800-1811 |
| D17 | loopOnce 五段；EVENT_BUFFER_SIZE=256 | 已证 | InputReader.cpp:86-142；InputReader.h:173-174 |
| D18 | processEventsLocked 分流与按设备分批（顺序不变） | 已证 | InputReader.cpp:145-181/:290-305 |
| D19 | 超时登记→wake→按时返回→广播 | 已证 | InputReader.cpp:477-482/:318-325；TouchInputMapper.cpp:1787-1795 |
| D20 | 事件 id 于 dispatchMotion 一次生成；链上拷贝保留 | 已证 | TouchInputMapper.cpp:3732；UnwantedInteractionBlocker.cpp:151；InputClassifier.cpp:438 |
| D21 | 同 ID 三条 notifyMotion＝三次独立 traceEvent 打点（链上三队列） | 已证（代码＋trace） | InputListener.cpp:340-345/:361-364；§8.4 查询 |
| D22 | R 7,951,192 为调度等待，处理窗口是 Running 996,346 | 已证（trace＋口径） | 批0 复验 :17/:45；状态序列 :44-46 |
| D23 | Reader S＝等待事件，机制对应 epoll_wait；逐段确认未证 | 已证（机制对应）＋未证（逐段） | EventHub.cpp:1760；批00 §3.7 |
| D24 | 读取段对 1194 间隔分布零塑造 | 已证 | D11/D12/D18 联合；内核详解03 §7.2 |
| D25 | 读取段不构成 34.738 ms 空档的机制段 | 已证（机制排除） | §8.2/§10；契约 :72 |
| D26 | D write_pmsg 不是读取段机制产物 | 已证（段位排除）＋未证（因果） | §8.5；契约 :74 |
| D27 | mapper 十处 notifyMotion 调用点（引用级，批05 展开） | 已证（调用点）；烹调未展开 | TouchInputMapper.cpp grep 清单 |

## 13. 批注审计与补缺摘要

原写作及批注验收记录见[过程性段落存档](过程记录/整体优化-R1-过程性段落存档.md)。
