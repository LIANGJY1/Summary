# SIR-3401 · 车机密码多次错误推送 APP 消息（非 bugfix，功能开发误入批次）

- **提交**：`0098ec6e` | 2026-08-27 | ljl | SystemUI/AccountCenter | **feature（提交信息即为 [feature]，mistag=true，非 bugfix 复盘对象）**
- **缺陷库**：未关联缺陷（defs 为空）

## 问题
不是缺陷修复。本提交实现"数字钥匙密码连续错误 5 次后向手机 APP 推送告警"的新功能链路：SystemUI 侧检测 → 广播通知 AccountCenter → 调云端接口推送。

## 改动概要
- 新增 `DigitalKeyCloudReporter.kt`（SystemUI 侧单例）：密码连续错误达 `MAX_PIN_ERROR_COUNT` 时由 `KeyguardActor` 触发 `reportPinLockedIfNeeded()`；内置 **10 分钟 SharedPreferences 冷却**防重复推送；从 `Settings.System("vin_shared_data")` 读 VIN，空值时回退临时 VIN `"test260526a"`；通过定向显式广播 `com.yadea.systemui.action.PIN_LOCKED_REPORT`（package 指向 `com.yadea.accountcenter`）携带 VIN 发出。
- 新增 `CloudReportReceiver.kt`（AccountCenter 侧）接收广播，经 `RequestRepository`/`ApiService` 调 `PinLockedPushRequest`/`PinLockedPushResponse` 云端推送接口；`AndroidManifest.xml` 注册 receiver；`Commons.kt` 增加常量。

## 评注
虽为 feature，实现里有两处值得注意：一是推送冷却用本地 SharedPreferences 时间戳实现，简单有效但重启不清零、跨进程不共享；二是 VIN 兜底使用硬编码测试 VIN `FALLBACK_VIN`，量产前若 `vin_shared_data` 未写入会把告警推到测试车辆 VIN 上，属于需要上线前清理的临时代码（代码注释已自知"量产前期…临时 VIN 兜底"）。

## 复盘与经验
- 批次分析中 [feature] 前缀提交应剔除，避免污染 bugfix 复盘样本（本批 mistag=true 的两条均如此）。
- 新增跨应用广播时显式 `package` 指定目标可避免广播泄漏到其他应用；配套 receiver 要在 Manifest 声明并考虑 exported 权限。
- 临时代码（测试 VIN 兜底）务必带注释与跟踪单，否则极易带量产出厂。
