# SIR-7680 · NFC 钥匙信号对接 & 测试页拉起锁屏重启后仅显示 1 秒
- **提交**：`f929df1d` | 2026-09-08 | ljl | SystemUI | bugfix（含 NFC 数字钥匙功能对接的大混合提交）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 数字钥匙

## 问题
通过测试页拉起锁车（锁屏）界面后主动重启车机，锁屏界面仅显示约 1 秒就被隐藏；同提交还包含 NFC 钥匙状态信号（CAN 信号）对接的功能代码。

## 根因分析
`KeyguardActor` 的锁屏显隐由 CAN 信号（6102 锁屏信号）驱动。测试老师用测试页拉起锁屏属于"非 MCU 信号来源"，重启后 SystemUI 重新注册车辆属性时会收到一次 CAN 信号的注册回传（6102 replay），其状态为"无需锁屏"，`hide()` 随即执行，把重启恢复流程刚拉起的锁屏又藏掉——于是锁屏只闪现 1 秒。原逻辑里：`hide()` 无条件透传 `super.hide()`；测试页入口 `showExternalLockoutScreen()` 拉起锁屏但没有任何"来源标记"；重启恢复后 `checkLockoutStatus()` 只认 PIN 锁定的持久化剩余时间。修复引入三件事：1) 持久化标志 `KEY_CAN_LOCK_SIGNAL_IGNORED`（SharedPreferences，重启后延续），测试页拉起锁屏/本地 PIN 锁定触发时置位，之后持续忽略 6102 信号（含注册回传），直到测试页再次操作；2) `hide()` 增加锁定期拦截——`mIsLockedOut || mIsNfcLockout` 时忽略一切 hide 请求，倒计时结束/解锁成功路径先解除锁定再 hide；3) 顺带补全 NFC 锁定（`mIsNfcLockout` + `KEY_NFC_LOCKOUT_REMAINING_MS`）独立于 PIN 锁定的持久化恢复体系（`show()`/`checkLockoutStatus()` 双锁定仲裁）。

## 关键代码修改
改动文件（核心）：application/SystemUI/src/main/java/com/android/systemui/keyguard/actor/KeyguardActor.kt（+238 行；其余 18 个文件为 NFC 对接、manifest、测试页、libcar 常量等混合改动）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/keyguard/actor/KeyguardActor.kt
@@ (常量与字段)
+    /** 测试页拉起锁屏后置位：持续忽略 CAN(6102) 锁屏信号，直到测试页再次操作 */
+    const val KEY_CAN_LOCK_SIGNAL_IGNORED = "key_can_lock_signal_ignored"
+    private var mIgnoreCanLockSignal: Boolean = false
@@ (hide 拦截)
+        // 锁定期（PIN / NFC）内忽略一切 hide 请求：
+        // 防止 CAN 信号注册回传（6102 replay）等把刚恢复的锁定页立刻隐藏
+        if (mIsLockedOut || mIsNfcLockout) {
+            LogUtils.w(TAG, "hide blocked: locked out (pin=$mIsLockedOut, nfc=$mIsNfcLockout)")
+            return
+        }
@@ (测试页入口)
         mIsExternalLockout = true
+        // 测试页入口：拉起锁定页即进入"忽略 CAN 锁屏信号"模式（重启后延续，直到测试页再次操作）
+        setCanLockSignalIgnored(true)
```

## 为什么能修复
锁屏的"隐藏权"被收口：只要是锁定态（PIN/NFC），任何 hide 请求（包括重启后 6102 注册回传触发的隐藏）都被拦截，锁屏不会再被秒藏；测试页拉起场景额外用持久化标志把"忽略 CAN 锁屏信号"跨重启延续，从信号源头也不去响应。副作用：hide 拦截是全局性的，若锁定期内确有合法的全局隐藏需求（如外部关闭锁定的入口），必须走"先解除锁定再 hide"的路径，否则会被拦——代码注释已声明该约定；混合提交里 NFC 管理器（DigitalKeyNfcManager.kt 415 行新文件）等大量功能代码与 bugfix 混在一起，回归范围远大于缺陷本身。

## 复盘经验
- 外部信号驱动的 UI（锁屏/弹窗）必须考虑"注册回传/replay"：重启后第一帧信号回放是车机 UI 的经典闪现问题，显隐逻辑要能识别"来源"并有权拒绝。
- "测试页/调试入口"触发的状态要有持久化标记并与正式信号隔离，否则联调工具会反复制造线上表现无法解释的现象。
- bugfix 与功能对接（NFC 415 行新文件）混在同一提交，严重破坏可回溯性；即便同分支开发，也应拆分提交。
