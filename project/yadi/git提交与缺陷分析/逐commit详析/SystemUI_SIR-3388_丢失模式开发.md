# SIR-3388 · 丢失模式开发（feature，误标 bugfix）
- **提交**：`b2508be2` | 2026-08-12 | ljl | SystemUI | **feature（非 bugfix，JSON 标记 mistag=true）**
- **缺陷库**：未关联缺陷（单号为需求开发单，无 defs 记录）

## 类型说明
提交标题为 `[feature][yadea][SystemUI][SIR-3388]丢失模式开发`，属整车丢失模式功能首期开发（提交信息注明"目前还未正式接入信号，待 carlib 对接后添加"），非缺陷修复，不强行剖析。

## 改动概要
20 个文件，+671/-9 行，横跨 SystemUI 与 BTPhone：
- 新增 `systemui/keyguard/LostModeHelper.kt`（+61）：丢失模式核心助手；
- `keyguard/actor/KeyguardActor.kt`（+231）、`KeyguardSettingsActivity.kt`、`actor_keyguard.xml`、`activity_keyguard_settings.xml`：锁屏侧丢失模式开关与展示；
- `digitalkey/PageStateMachine.kt`（+102）、`DigitalKeyManager.kt`（+67）、`DigitalKeyConstants.kt`、`StateEventRouter.kt`、`GestureGuard.kt`：数字钥匙页面状态机对丢失模式的接入；
- `CarHeadsUpNotificationManager.java`、`CarNotificationListener.java`、`VolumeDialogActor.kt`：丢失模式下通知/音量的抑制点；
- BTPhone 侧 `FloatCallWindowPresenter.java`、`UiCallManager.java`、`SteeringWheelKeyManager.java`：来电浮窗/方向盘按键在丢失模式下的联动判断。
