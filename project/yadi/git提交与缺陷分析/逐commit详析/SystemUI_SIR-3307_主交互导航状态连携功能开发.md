# SIR-3307 · 主交互导航状态连携功能开发（非 bugfix，功能开发误入批次）

- **提交**：`d095afee` | 2026-08-27 | ljl | SystemUI | **feature（提交信息即为 [feature]，mistag=true，非 bugfix 复盘对象）**
- **缺陷库**：未关联缺陷（defs 为空）

## 问题
不是缺陷修复。本提交是"主交互导航状态连携"功能的整段开发：让 `PageStateMachine` 感知地图三态（导航/巡航/自由），并按 SRS 规则表重新定义 D/R/P 档切换时 Android 侧页面与仪表形态的联动。

## 改动概要
- 新增 `libs/naviSdkClient-release-1.7.0.374.aar`（腾讯 wecarnavi 导航 SDK，二进制更新），`proguard-rules.pro` 增加 SDK/Beacon/Gson keep 规则。
- 新增 `NaviSceneManager.kt`（约237行）：负责导航 SDK 连接/断连重连/事件缓存与三态查询（`queryScene()` 同步 AIDL 复核、`sceneListener` 事件回调、`isVoiceLaunchingNavi()`）。
- 新增 `MapScene.kt` 枚举与 `VoiceNaviLaunchProvider.kt`；`DataContext` 增加 `mapScene` 字段。
- `PageStateMachine` 大改：挂 P 档按当前态（S2/S3/其他）分流回驻车-地图/驻车-通用；挂 D/R 档先 `refreshMapScene()` 同步复核三态，仅"导航态/巡航态"才保留地图前台，并按 `DriveTouchLockController` 开关决定进 S2 常态导航还是 S3 暂态导航；行驶触屏锁定回调也按 `isMapInNaviOrCruise()` 分流 `MapFullscreenBackWithSpeed` / `AppPageBackWithSpeed`。
- `DigitalKeyVehicleService` 在 `PageStateMachine.init` 之后初始化 `NaviSceneManager.init(mAppContext)`。

## 评注
本提交虽非 bugfix，但有两处值得学习的防御性设计：决策点用"事件推送 + 同步查询兜底缓存"双通道保证三态一致性（注释明言"事件推送可能遗漏"）；SDK 初始化顺序上把 `NaviSceneManager.init` 放在 `PageStateMachine.init` 之后，保证 `sceneListener` 注册时回调可落。同步 `queryScene()` 是持锁 binder 调用，代码注释已自知存在毫秒级阻塞，属于已记录的技术债。

## 复盘与经验
- 批次归类时按提交信息前缀（[feature]/[bugfix]）先分桶，feature 与 bugfix 混排会污染缺陷复盘样本。
- 状态机依赖外部（跨进程）状态时，"事件回调更新 + 决策点同步复核"是应对事件丢失的标准模式。
