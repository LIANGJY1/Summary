# DoKit

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q18 -->

**Q1: [learning] DoKit 的“断网”“超时”与 Mock 模式为什么都会先发出真实请求？哪些接口绝对不能用它们测试？**

因为它们的实现位置在 OkHttp network interceptor：`DokitWeakNetworkInterceptor` 的断网与超时模式都先执行 `chain.proceed(chain.request())` 让真实请求出网，真实请求成功后才丢弃响应内容、构造 HTTP 400 返回给应用（超时模式先 sleep 再发真实请求）。`DokitMockInterceptor` 同样先执行原请求，命中本地规则后再向 Mock 平台发第二个请求、用平台数据替换应用最终收到的响应。Mock 发生在真实请求之后，不是请求发出前的短路。

由此产生的风险是服务端状态已被改变而界面显示失败：支付、下单、创建、删除、上传、埋点等会改变服务端状态的接口绝对不能用这两个模式测试——用户看到失败后重试，还会再提交一次。另外若底层真实请求本身抛出异常，异常会直接向上返回，DoKit 不会生成 400，所以要验证 `IOException`、`UnknownHostException`、`SocketTimeoutException` 分支，必须用受控测试服务器、MockWebServer 或代理按目标异常构造真实失败。

适用范围因此收窄：断网/超时模式只用于安全、幂等的接口（幂等指同一请求重复执行不产生额外业务结果），观察 UI 收到 400 后的状态与重试入口。Mock 用于无副作用查询接口上的空列表、字段缺失、超大列表与错误码页面。限速模式适合观察大 Body 慢速读写下的 UI 表现。对状态修改接口，改用测试环境服务端 Mock 或依赖注入替换数据层。DoKit 为第三方 SDK，未本地核对。


<!-- 来源：05-observability/03-apm-platforms.md · Q19 -->

**Q2: [learning] DoKit 性能浮窗的 FPS、CPU、内存、卡顿读数各来自什么数据源？为什么浮窗数值不能当版本级性能结论？**

四个读数的来源与缺陷各不相同，共同点是“线索级精度”：FPS 统计的是每秒收到的 `Choreographer.FrameCallback` 回调数，主线程上的 Handler 任务每 1000 ms 读取并清零计数，上限取屏幕刷新率——而统计任务也在主线程，主线程堵塞时“一秒窗口”会延后且代码不按真实经过时间归一化，内置健康体检路径还会把上限压到 60，丢失高刷信息。CPU 每 500 ms 执行一次 `top -n 1` 再把 `%CPU` 除以核数，`top` 输出格式不属于 SDK 兼容契约且算法与其他工具未必一致。内存每 500 ms 读 `Debug.getMemoryInfo()` 的 totalPss，PSS 是采样时刻的驻留值，不指向具体对象。卡顿监控阈值固定 200 ms、堆栈从 300 ms 后开始按 300 ms 间隔采样——200–300 ms 已越阈值但没采到栈的事件会被丢弃，一份堆栈只代表某个采样时刻。

卡顿模块还有一个结构性冲突：它依赖 `Looper.setMessageLogging()`，而一个 Looper 只有一个 `mLogging` Printer 槽位（AAOS13 的 `Looper.java` 同为单字段）。DoKit 的卡顿与 TimeCounter 模块会互相停用、停止时把 logger 置 null，应用内其他使用同一接口的监控器也会被覆盖——现场没有记录不能证明没有卡顿。

所以浮窗的用途是发现“哪个页面、哪个操作值得继续查”。版本级帧结论用 JankStats/FrameMetrics，启动与交互结论用 Macrobenchmark，网络阶段拆分用 OkHttp `EventListener` 或系统 trace。DoKit 为第三方 SDK，未本地核对。


<!-- 来源：05-observability/03-apm-platforms.md · Q20 -->

**Q3: [learning] 在 API 37 工程里接入 DoKit 3.7.11 有哪些硬边界？Release 隔离最终以什么为准？**

3.7.11 公开维护停在 2023 年，接入 API 37 工程前要先接受四条硬边界：其一，`dokitx-plugin` 在 `DoKitPlugin.kt` 里走 AGP 8.0 已移除的 Transform API（`registerTransform()`），AGP 8+ 不能加载，且它只按顶层 task 名判断 release 变体、聚合构建时可能误判。其二，运行时 `HandlerHooker` 反射 `ActivityThread.currentActivityThread`、`mH`、`Handler.mCallback`（AAOS13 源码同样存在这些成员，但无兼容承诺），并在 Android 12+ 跳过 SandHook 全局 hook（源码注明会崩溃）——hook 失败是静默的，只打日志。其三，库 manifest 声明 targetSdk 31 与悬浮窗、存储、电话、相机、前台服务等权限，合并进宿主后要按 target 37 重审（Android 14 起录屏前台服务需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限而库未声明）。其四，运行时默认 `ENABLE_UPLOAD=true` 会出站上传应用信息，必须显式 `disableUpload()` 并抓包核对测试包流量，可选模块 `dokitx-pthread-hook` 还传递 Matrix 2.0.2 的 4 KB 对齐 `.so`，不满足 16 KB。

Release 隔离分两层验收。依赖层：`dokitx-no-op` 只提供空实现 API，能降低误引用成本但不能阻止误加完整运行时，CI 要检查 `releaseRuntimeClasspath` 只允许 no-op。产物层：解开最终签名的 APK/AAB，确认没有 DoKit 类与面板资源、没有多余权限与宽路径 FileProvider、没有统计与健康检查出站请求、没有测试账号入口与调试 deep link——依赖声明只是证据之一，签名产物才是验收对象。DoKit 为第三方 SDK，未本地核对。
