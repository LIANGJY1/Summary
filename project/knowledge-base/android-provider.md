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
