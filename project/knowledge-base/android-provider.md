# Android Provider 域

> Android 跨进程数据共享与监听域——ContentProvider 机制、系统级 Provider（SettingsProvider 等）、ContentResolver/ContentObserver——的稳定架构事实与"用不对"的坑，换一个 Android 项目仍成立。**维护者**：session-to-knowledge

## 边界

- **收**：ContentProvider 访问路径与系统 Provider 的架构事实；ContentObserver 等变化通知机制的行为细节与正确用法范式；跨进程数据监听"拿不到值/漏通知/状态不一致"类坑与解法
- **不收**：编程语言特性（→ [language/](./language/)）；SDK/组件设计模式（→ [sdk-design.md](./sdk-design.md)）；Android UI 域（→ [android-ui.md](./android-ui.md)）；等
- **分工**：本文收"机制层怎么运作、怎么用才对"；权衡与思维方式 → [design-principles.md](./design-principles.md)

## 规则

- 复习者测试准入门槛起效；不设单次条目数量上限
- 踩坑类条目五段：**现象 → 原因 → 误区 → 解决方案 → 启示**；稳定事实类条目（架构事实/源码路径）用变体：**原理 → 优缺点 → 使用场景及用法 → 怎么验证 → 陷阱**，条目首行注明变体
- 涉及代码行为附 ≤ 10 行最小示例并标来源项目与文件路径；验证命令给可直接执行的一行
- 冷可读：专名首现带括注，删掉项目名仍能读懂；条目名是完整命题；正文 timeless，不写日期与元数据行
- 扁平 `## 条目名` 节 + 顶部目录，新条目 = 追加目录行与节

## 目录

- [ContentObserver 监听设置项：通知不带值、注册不重放初值、注册窗口有竞态](#contentobserver-监听设置项通知不带值注册不重放初值注册窗口有竞态)
- [SettingsProvider 寄宿 system_server，源码 8.0 起在 frameworks/base/packages/SettingsProvider](#settingsprovider-寄宿-system_server源码-80-起在-frameworksbasepackagessettingsprovider)
- [跨应用共享低频开关：用 Settings.Global 当总线，广播/SP/AIDL 各有坑](#跨应用共享低频开关用-settingsglobal-当总线广播spaidl-各有坑)
- [车辆信号 SDK 的回调事件按映射表重贴别名键：按原始键过滤收不到事件，未映射键注册被静默跳过](#车辆信号-sdk-的回调事件按映射表重贴别名键按原始键过滤收不到事件未映射键注册被静默跳过)
- [系统服务一次性就绪信号：握手沿可能先于订阅到达，监听注册要做粘性补发](#系统服务一次性就绪信号握手沿可能先于订阅到达监听注册要做粘性补发)

<!-- 条目模板：

踩坑类五段（现象→原因→误区→解决方案→启示）同 android-ui.md；
稳定事实类五段变体（条目首行注明"（稳定事实变体）"）：

## 条目名（完整命题）

（稳定事实变体）

**原理**：……

**优缺点**：……

**使用场景及用法**：……

**怎么验证**：……

**陷阱**：
- ……

-->

## ContentObserver 监听设置项：通知不带值、注册不重放初值、注册窗口有竞态

**现象**：三个独立症状，任一都会让监听方状态与真实值不一致：① `onChange` 回调里拿不到新值——回调只带一个 selfChange 布尔，没有数据载荷；② 只注册不读初值，进程启动后本地状态与 provider 现值无关——observer（内容观察者）是"变化通知"不是"状态订阅"；③ 先读初值后注册，两步之间值变了，监听方此后一直持有过期初值，且不会有新通知来纠正。

**原因**：变化通知的机制层契约就是"尽力而为地告诉你某 Uri 变过"：写入方 notifyChange 后，ContentService（system_server 中统一登记与分发内容变化通知的系统服务）按 Uri 匹配观察者、经 Binder 回调注册方——整条链路不携带新值；注册只是往登记表插一条记录，不回放当前值；而"读初值"与"注册生效"是两次独立跨进程调用，天然存在空窗。

**误区**：直觉把 onChange 当"数据推送"，发现没值可拿才去重读；以为注册完就完成了状态同步，漏掉主动读初值；把"读初值＋注册"当原子操作——排查状态不一致时只盯回调逻辑，想不到空窗期竞态。

**解决方案**：三步范式（来源：雅迪车机 Launcher `application/Launcher/src/main/java/com/yadea/launcher/pet/platform/PetSources.kt`）：

```kotlin
val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
        readRaw()?.let { onEdge(it == 1) }        // ① 回调内重读：通知不携带新值
    }
}
resolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer)
readRaw()?.takeIf { (it == 1) != initial }        // ② 注册后立刻读现值
    ?.let { onEdge(it == 1) }                     // ③ 与已用初值不同→补对齐沿
```

对齐沿（注册完成后重读现值、与已消费初值不同时补发的一条修正事件）负责把空窗期的变化追补给下游。配套两条：读取失败（键不存在/跨进程异常）返回 null 不发沿，别造默认值污染状态；现值用本地持久缓存兜底，通信不畅时仍有可用状态。

**启示**：
- 一切"变化通知"型 API（ContentObserver、广播、文件 watch）共性：通知只保证"发生过"，不保证"值是什么、你错过了什么"；正确性 = 回调内重读＋注册后快照对齐（适用：任何注册与通知分离的观察 API）
- 通知是尽力而为，别当唯一真相源；真相在数据源里，监听方状态是可被对齐沿修正的缓存（适用：跨进程/跨端状态同步）
- 注册与注销必须**成对**：observer 存成员字段、生命周期对端（onDestroy/release）`unregisterContentObserver`——局部变量注册后拿不到引用，ContentService 登记表持着它：对象泄漏、页面重建后二次注册变重复回调（适用：一切 register 型监听 API）

## SettingsProvider 寄宿 system_server，源码 8.0 起在 frameworks/base/packages/SettingsProvider

（稳定事实变体）

**原理**：系统设置存储是独立 APK `com.android.providers.settings`（priv-app——预置在 /system/priv-app 的特权系统应用），清单声明 sharedUserId=android.uid.system 且 android:process="system"，**不占独立进程，寄宿在 system_server（Android 系统核心服务进程）内**；provider 声明 multiprocess=false，全系统单实例、所有进程同一数据视图。应用侧 `Settings.Global/System/Secure` 的静态方法只是便捷封装，经 ContentResolver 跨进程调用；全表常驻内存，首次经 AMS 拿 Binder 代理后直连 provider。源码：Android 8.0 起在 `frameworks/base/packages/SettingsProvider/`（主体逻辑集中在 SettingsProvider.java 单类）；7.x 及以前在 `packages/providers/SettingsProvider/`。

**优缺点**：优点——最早可用、永不被杀（寄宿 system_server，生命周期与系统同寿）；读廉价（内存缓存＋Binder 直达）；自带一对多变更通知（ContentObserver）；写有签名级权限闸门，三方只读、不可篡改。缺点——写 Secure/Global 需 WRITE_SECURE_SETTINGS（仅系统签名/特权应用拿得到），普通应用写不进；每次写入都落盘＋广播通知，只适合低频小 KV。

**使用场景及用法**：系统设置 UI 的存储后端；跨应用开关总线（一对多实时感知，选型判据见「跨应用共享低频开关」条目）；系统框架自身读配置（飞行模式、ADB 开关等）。用法：读写走 `Settings.XXX.put/getXxx(resolver, key, value)`；监听走 `registerContentObserver(Settings.Global.getUriFor(KEY), false, observer)`，正确性范式见「ContentObserver 监听设置项」条目。

**怎么验证**：
- `adb shell pm path com.android.providers.settings` —— 看 APK 安装位置
- `adb shell settings get/put global <key> [value]` —— 免 UI 读写任意键，联调直接拨开关
- 在线读源码：cs.android.com 搜 SettingsProvider

**陷阱**：
- 以为 ContentProvider 都跑在宿主应用自己的进程——普通 provider 确实如此，但清单 android:process="system" 可把组件指进 system_server，settings 正是靠这种"寄生"获得最早可用与永不被杀
- 按老资料的 packages/providers/SettingsProvider 路径找 8.0+ 源码，找不到或误判仓库不存在
- 以为三张表权限一致——读全开放，写 System 与写 Secure/Global 隔着普通权限与签名级权限两道门

## 跨应用共享低频开关：用 Settings.Global 当总线，广播/SP/AIDL 各有坑

**现象**：跨应用共享一个开关/低频状态，三种常见实现各有症状——广播方案：重启后状态丢失（广播不持久）、接收方进程未起时通知直接丢、时序乱（收到广播时读到的状态与发送意图可能不一致）；SharedPreferences 方案：A 进程写入后 B 进程读不到——SP 是各进程私有的，MODE_MULTI_PROCESS 已废弃且不可靠，多进程下读到旧值；自建 Service+AIDL 方案：能跑，但要保活、维护接口，持久化和变更通知全得手搓。

**原因**：需求本质是"持久化＋一对多实时通知＋权限闸门"三件事。广播天生是事件流不是状态存储；SP 没有跨进程一致性；自建服务三件全手工。而 Settings.Global 一套全带：系统托管持久化（重启不丢）、ContentObserver 一对多即时通知、写权限签名级闸门（三方只读不可篡改）、adb 可直接读写、全表内存缓存使读廉价。

**误区**：直觉按"传个消息"选广播——用事件流承载状态，必然要再补初值同步机制（开机重发、主动拉取接口）；以为 SP 加个 mode 就能跨进程；觉得自建 Service 才"正规"——在系统签名应用场景里，那是把 provider 白送的三件套全部重写一遍。

**解决方案**：选型判据——要"持久化＋一对多＋实时通知"且数据是低频小 KV → Settings.Global 总线。写侧 `Settings.Global.putInt(resolver, KEY, value)`；读侧 getX ＋ ContentObserver 三步范式（见「ContentObserver 监听设置项：通知不带值、注册不重放初值、注册窗口有竞态」条目）；联调 `adb shell settings put global <key> <value>` 免 UI 拨开关。前提两条：写方持 WRITE_SECURE_SETTINGS（系统签名/特权应用，普通三方只能读）；高频写入、大数据、结构化查询不适用，走数据库/文件/服务。

**启示**：
- 选型先数需求件数：持久化/一对多/实时通知/权限闸门，Global 一次给齐；任何一件不要或数据形态不符（高频/大/结构化）才换方案（适用：系统签名应用间共享低频配置态）
- 事件与状态语义别混用：拿事件流（广播）当状态总线，等于在流上重造状态查询；直接选状态型存储更省（适用：任何"开关/配置"类共享的选型）

## 车辆信号 SDK 的回调事件按映射表重贴别名键：按原始键过滤收不到事件，未映射键注册被静默跳过

**现象**：两个症状独立出现：① 订阅了某车辆信号——键在键表里有定义、注释也齐全——真机上回调永远不来；换成同一车端信号的另一个键，事件立刻就到了。② 注册时拼错/漏映射一个键，不报错、不抛异常，只是这个键从此静默失效，排查时极易当作"信号没发"。

**原因**：Carlib（Neusoft 车辆信号封装库）在注册与回调两端各过一次键翻译，翻译表是同一张合并映射表（能量族＋自定义按键＋车设族＋数字族＋3D 车模族按序合并）：

- 注册端 `PropertyManager.registerPropertyCallbacks` 用 `getRecIdByKey(key)` 把逻辑键翻译成车端属性 ID，**映射表查无的键直接被过滤跳过**（不注册、不告警）；
- 回调端 `CarServiceManager.convertPropertyId` 用 `getKeyByRecId(recId)` 按**合并表首个匹配键**把车端 ID 换回逻辑键再分发给订阅者——同一车端信号存在多个逻辑键别名时，事件永远以排最前的族别名下发，其他别名形同虚设。

**误区**：以为"订阅用什么键、回调就带什么键"——两端各翻译一次且查表方向相反，订阅键只决定注册哪个车端信号，回调键由映射表顺序决定；以为映射缺失会显式报错——实际是静默过滤，绿灯测试掩盖一切（纯逻辑层单测碰不到这层）；排查映射表时用数字字面量 grep——表里写的是常量名，搜不到就误下"没有映射"的结论。

**解决方案**：订阅键必须从"回调实际会下发的别名族"取（即合并表里排最前的族）；三步排查口诀——①按常量名查注册/映射两表确认键存在；②确认事件回调侧的首匹配键别名与过滤键一致；③拿捏不准就在回调里先打印 propertyId 实测定键。来源：雅迪车机 Launcher `application/Launcher/src/main/java/com/yadea/launcher/pet/`（PetSources 按原始键过滤收不到事件后改用能量族别名）；Carlib `component/Carlib/src/main/java/com/neusoft/libcar/`（`manager/CarServiceManager.kt` 的 convertPropertyId、`manager/PropertyManager.kt` 的注册过滤、`map/CarPropertyMapping.kt` 的合并表）。

**怎么验证**：`grep -n "getKeyByRecId\|getRecIdByKey" component/Carlib/src/main/java/com/neusoft/libcar/manager/CarServiceManager.kt component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt`——确认两端各查一次映射表；再看 `map/CarPropertyMapping.kt` 的 `carPropertyIdMap` 合并顺序定"首匹配族"。

**启示**：
- 事件键≠订阅键：凡"注册时做键翻译、回调时做反向翻译"的封装，消费方过滤/分发必须用回调侧键（适用：任何带 ID 映射层的封装库，车机信号库、协议栈、配置中心同构）
- 映射缺失＝静默失效：这类 SDK 的错误策略是"查无即跳过"，联调时信号收不到先查映射表再怀疑信号源（适用：一切表驱动翻译层）
- 搜配置表用常量名搜、并打开文件确认条目存在——用字面量 grep 得出"不存在"的结论前必须打开表文件人工复核（适用：一切键表类配置的排查）

## 系统服务一次性就绪信号：握手沿可能先于订阅到达，监听注册要做粘性补发

**现象**：就绪信号是"上游握手成功那一拍发一次"的沿（如车载 IVI 与座舱域控的握手回执，确认后正常不再重复）。订阅者注册晚于这一拍时错过该沿，且没有任何机会再收到——下游把"没收到"当成"没就绪"，依赖它的分支永远不触发。冷启动联调偶然能通过（上游就绪慢、订阅赶在前面），换个时序就复现不了回调，极难定位。

**原因**：一次性沿没有"当前状态"可查：生产者只通知登记表里的在册订阅者，而"注册生效"与"信号发出"之间没有任何先后保证——宿主进程重启后上游早已就绪、组件晚初始化、系统负载拖慢注册，都会让信号落在订阅之前。与常驻状态型信号不同，错过沿＝错过全部信息，消费方无从对账。

**误区**：以为"只要订阅了就一定能等到"——把一次性沿当状态型信号对待；以为冷启动偶然通过证明无竞态——竞态只在就绪快于订阅的时序里出现；为补救而在消费方另起一条轮询/对账链路——绕开了信号源本可一句话解决的补发。

**解决方案**：信号源做粘性标记＋迟到补发（来源：雅迪车机 Launcher `application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`）：

```java
private volatile boolean mIviReady = false;   // 握手确认后置位并保持进程生命周期

public void addOnIviReadyListener(OnIviReadyListener l) {
    mIviReadyListeners.add(l);
    if (mIviReady) l.onIviReady();            // 迟到者立即补发
}
// 握手 ack 处：mIviReady = true; 再遍历通知在册订阅者
```

补发多一次无害的前提是下游自带幂等护栏（一次性动作有"本周期已消耗"标记，重复沿直接忽略）；休眠唤醒等会重新握手的场景也由同一护栏吸收，粘性补发不引入新语义。

**启示**：
- 一次性沿与状态型信号处理方式分叉：状态型"注册后读现值对账"，一次性沿靠源头粘性补发或消费方查询已发生标记（适用：握手/回执/一次性通知型监听）
- 补发放信号源的注册入口（addXxxListener 里）比放消费方好：语义就是"这个事实成立过"，所有迟到者统一受益，消费方零改动（适用：自己维护监听登记表的系统服务/进程内单例）
- 幂等护栏先行：下游对重复沿无副作用，才允许源头做补发/重放（适用：一切带补发语义的事件设计）
- 抽象原则同源：[design-principles「迟加入者补课到当下」](design-principles.md#迟加入者补课到当下)——本文是其单布尔状态的最简重放形态
