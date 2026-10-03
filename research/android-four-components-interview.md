# Android 四大组件面试资料核查

> 调研日期：2026-09-30。用途：为 `knowledge-base/01-android/02-app-framework/01-four-components.md` 的面试级回答提供一手依据。以 Android Developers 文档及 AOSP 为准；掘金文仅作为待核查的二手材料。

## 掘金文章核查

指定文章《Activity 启动流程（一）—— Launcher 阶段》明确以 `android-14.0.0_r9` 为代码基线，范围只是“用户点击 Launcher 图标”到 Launcher 进程把启动请求交给 ATMS。它不是四大组件综述，也没有覆盖 Activity 完整生命周期、任务栈决策或其他三个组件。

| 文章主张 | 一手核对 | 结论 |
|---|---|---|
| 图标点击经 `ItemClickHandler` 分流，应用快捷方式准备 Intent 后走 `startActivitySafely()` | [AOSP Launcher3 ItemClickHandler（Android 14 tag）](https://android.googlesource.com/platform/packages/apps/Launcher3/+/android-14.0.0_r9/src/com/android/launcher3/touch/ItemClickHandler.java) | 与该版本源码一致。点击路径受图标类型、安装状态、快捷方式等分支影响，文章展示的是主路径而非所有情况。 |
| Launcher 调用 `startActivity()`，经 `Activity` / `Instrumentation` 到 `ActivityTaskManager` | [AOSP Activity](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r9/core/java/android/app/Activity.java)、[AOSP Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r9/core/java/android/app/Instrumentation.java)、[AOSP ActivityTaskManager](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r9/core/java/android/app/ActivityTaskManager.java) | 作为 Android 14 源码链路概述正确；具体调用实现会随 Android 版本演进，面试回答应说清版本与边界，不能把 Launcher 的入口链路等同于整个启动流程。 |
| Launcher 给启动 Intent 加 `FLAG_ACTIVITY_NEW_TASK`，目标通常进入新 task | [AOSP Launcher3 ActivityContext（Android 14 tag）](https://android.googlesource.com/platform/packages/apps/Launcher3/+/android-14.0.0_r9/src/com/android/launcher3/views/ActivityContext.java)、[Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack) | 加 flag 的源码事实成立；“新建 task”是过度简化。`NEW_TASK` 参与 task 选择/复用，实际结果还受 task affinity、launchMode、其他 flags 和系统状态影响；不可答成每次必定新建。 |

文章本身没有明显过时错误；需要限定的是它只解释 Android 14 的 Launcher 根 Activity 请求准备阶段。把其局部主链路当作 Activity 全启动/四大组件答案，或把 `NEW_TASK` 解释为无条件创建新 task，才会失准。

## 面试回答的可靠主张

### Activity：入口、生命周期与任务栈

- Activity 是面向用户交互的组件入口；系统依生命周期回调驱动实例，不是应用自行从 `main()` 按页面顺序运行。[Application fundamentals](https://developer.android.com/guide/components/fundamentals)、[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)
- 生命周期常见顺序是 `onCreate → onStart → onResume`；失去前台焦点进入 `onPause`，不再可见进入 `onStop`，返回可见后走 `onRestart → onStart → onResume`。结束/系统销毁时可能有 `onDestroy`，但进程被系统直接杀死时不能依赖它执行。`onSaveInstanceState` 是保存可恢复 UI 状态的机制，不是持久化数据库的替代品。状态保存和不同系统版本回调顺序应结合官方生命周期图回答。[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)、[Processes and threads](https://developer.android.com/guide/components/processes-and-threads)
- 默认情况下新 Activity 入当前 task 的 back stack 顶端；Back 弹出顶层并返回上一实例。Home/切换应用通常令 task 退到后台并保留其栈；系统内存回收时可销毁后台 Activity，因此“后台 Activity 永远驻留”错误。[Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack)
- `standard` 每次请求可创建新实例；`singleTop` 仅当目标实例已在栈顶时复用并回调 `onNewIntent()`；`singleTask` 会按 task/栈规则复用并清理其上方实例等，不应死记成简单的“单例”。Intent flags（如 `CLEAR_TOP`、`NEW_TASK`）和 manifest `launchMode` 共同影响结果。任务栈不等于进程栈。[Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack)、[Activity manifest reference](https://developer.android.com/guide/topics/manifest/activity-element#lmode)
- Activity A 启动同进程 Activity B 时，典型顺序是 A.`onPause` → B.`onCreate/onStart/onResume` → 若 A 不再可见则 A.`onStop`；不要假设 A 一定先 `onStop` 再创建 B。[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)
- Android 12+ 用户从 Launcher 根 Activity 按 Back 时，系统默认把 task 移至后台，而较旧系统会结束 Activity；引用 Back 行为要注明版本。[Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack)

### Service：启动、绑定、线程和限制

- Service 是无 UI 的组件；它默认运行在宿主进程主线程，不会自动获得工作线程，也不因 Service 身份自动成为独立进程。阻塞操作须移到工作线程/合适的异步 API，否则可能 ANR。[Services overview](https://developer.android.com/develop/background-work/services)、[Processes and threads](https://developer.android.com/guide/components/processes-and-threads)
- **Started**：`startService()` 创建/启动，回调 `onCreate()`（首次创建）及 `onStartCommand()`；其启动态独立于调用者生命周期，需 `stopSelf()`/`stopService()` 结束。系统杀进程后可能按 `onStartCommand()` 返回值重建/重投递，但不是可靠持久任务机制。[Services overview](https://developer.android.com/develop/background-work/services)、[Service API](https://developer.android.com/reference/android/app/Service#START_STICKY)
- **Bound**：`bindService()` 建立客户端连接，`onBind()` 返回 `IBinder`，通过 `ServiceConnection` 异步收到 Binder；纯绑定 Service 在最后一个客户端解绑后可销毁。若同时 started + bound，所有客户端解绑不等于 Service 已停止，仍需停止 started 状态。`onUnbind()` 返回 true 可使下次绑定触发 `onRebind()`。[Services overview](https://developer.android.com/develop/background-work/services)、[Bound services](https://developer.android.com/develop/background-work/services/bound-services)
- 绑定同进程 Service 时 Binder 方法通常直接在调用线程运行；跨进程 AIDL 调用通过 Binder 线程池到达服务端，因此接口实现需考虑并发/线程安全。以“Service 回调都在主线程”概括所有 IPC 是错误的。[Bound services](https://developer.android.com/develop/background-work/services/bound-services)、[Processes and threads](https://developer.android.com/guide/components/processes-and-threads)
- “Service 可无限期后台运行”是过时答案：Android 8 起后台 Service 受执行限制；持续且对用户可见的工作用前台 Service 并显示通知，定时/可延迟工作优先 WorkManager/JobScheduler。Android 12+ 后台启动前台 Service 通常受限；Android 14+ 还需声明服务类型及对应权限，且 while-in-use 权限有额外启动条件。[Background execution limits](https://developer.android.com/about/versions/oreo/background)、[Foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[Foreground service changes](https://developer.android.com/develop/background-work/services/fgs/changes)

### BroadcastReceiver：短时入口

- Receiver 是响应广播 Intent 的入口。Manifest 注册可让系统在应用进程未运行时按条件启动进程并派发；Context 动态注册须按生命周期成对注销，进程结束后注册也随进程消失。Android 8 起对大多数隐式广播的 manifest 注册有限制，不能再笼统说“监听系统广播都写 Manifest”。[Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)、[Background execution limits](https://developer.android.com/about/versions/oreo/background)
- 每次派发创建/调用的 Receiver 对象只在 `onReceive()` 调用期间有效；`onReceive()` 默认主线程运行，必须快速返回。长工作不要在回调中直接开普通线程后就返回（进程可能被回收）；应使用 `goAsync()` 做极短的异步收尾（官方建议仍很快完成），或调度 WorkManager/JobScheduler。[Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- 动态注册接收非系统广播时按 API/目标版本明确 `RECEIVER_EXPORTED` 或 `RECEIVER_NOT_EXPORTED`；导出 receiver 可接收其他应用发送的广播，须防伪造并按需加权限。对外服务的 manifest receiver 也要审视 `android:exported` 和权限。Android 13 的动态注册导出标志要求不能拿旧答案套新 API。[Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

### ContentProvider：结构化数据访问与 IPC

- Provider 对外以 `content://authority/path` URI 暴露数据操作接口（`query/insert/update/delete/getType`），由 `ContentResolver` 查找并访问；它是应用组件入口和数据访问抽象，不等于数据库，也不要求数据一定存 SQLite。[Content provider basics](https://developer.android.com/guide/topics/providers/content-provider-basics)、[Create a content provider](https://developer.android.com/guide/topics/providers/content-provider-creating)
- `ContentProvider.onCreate()` 在应用主线程做初始化；API 文档说明已注册 Provider 会在应用启动时初始化，因此耗时打开/扫描数据库会拖慢冷启动，应延迟到首次数据访问。其余数据访问方法可能被多个线程并发调用，需线程安全。[ContentProvider API](https://developer.android.com/reference/android/content/ContentProvider)、[Create a content provider](https://developer.android.com/guide/topics/providers/content-provider-creating)
- 是否可被其他应用访问由 `android:exported`、读写 permission 和临时 URI grant 等共同决定；默认值受 API/target 行为影响，安全回答应显式声明导出属性和权限，不依赖默认值。`FileProvider` 常用于通过临时 URI 授权分享文件。[`<provider>` manifest reference](https://developer.android.com/guide/topics/manifest/provider-element)、[Content provider permissions](https://developer.android.com/guide/topics/providers/content-provider-basics)

## 横向总结（面试收束）

四类组件是系统可调度/应用可进入的不同入口，各有组件生命周期，但共享应用进程、主线程与进程优先级等运行环境；组件被销毁或进程被杀不等价于用户数据自动保存。Activity 管交互界面与导航；Service 承载受平台规则约束的工作/IPC；Receiver 响应短时事件；Provider 以 URI/Resolver 访问和共享数据。组件能否跨应用调用还取决于 Intent、权限、导出属性、用户与系统版本等边界。[Application fundamentals](https://developer.android.com/guide/components/fundamentals)、[Processes and threads](https://developer.android.com/guide/components/processes-and-threads)
