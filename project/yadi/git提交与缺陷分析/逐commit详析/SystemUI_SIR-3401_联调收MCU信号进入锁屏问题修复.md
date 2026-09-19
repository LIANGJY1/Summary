# SIR-3401 · 收 MCU 信号进入锁屏问题修复（联调提交，标题标 feature）
- **提交**：`c65582c0` | 2026-08-18 | ljl | SystemUI | **feature 前缀的联调修复（JSON 标记 mistag=true）**
- **缺陷库**：未关联缺陷（单号为需求/联调单，无 defs 记录）

## 类型说明
提交标题为 `[feature][yadea][SystemUI][SIR-3401]联调收MCU信号进入锁屏问题修复`，what/why/how 均为同一句话，属联调期间的信号联动修复，缺陷库未关联，故按特殊类型简要记录（不按标准 bugfix 模板剖析）。

## 改动概要
仅 `application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyManager.kt`（+53/-2）：
- 数字钥匙信号拉起锁屏（`showKeyguard`）时，原逻辑只在锁屏类型为 `LOCK_TYPE_NONE` 时强制 PIN；现改为只要 `getLockType() != LOCK_TYPE_PIN` 即强制 `LOCK_TYPE_PIN`，并且**同步把 PIN 写入 Settings.Global 的 `keyguard_lock_type`**，使 `KeyguardActor.show()` 内部 `loadLockTypeFromSettings()` 能读到正确类型（否则 show 时从 Settings 读回旧值，密码键盘弹不出）；
- 强制前用 `savedLockTypeBeforeSignal` 保存原始锁屏类型（`readSettingsLockType()`），收到 `WAKE_UP_SHOW_LAUNCHER` 信号切回 Launcher 时 `restoreSettingsLockTypeIfNeeded()` 恢复原值，避免数字钥匙场景把用户锁屏设置永久改成 PIN；
- 新增 `readSettingsLockType()/writeSettingsLockType()/restoreSettingsLockTypeIfNeeded()` 三个辅助方法，读写均有 try/catch 兜底。
