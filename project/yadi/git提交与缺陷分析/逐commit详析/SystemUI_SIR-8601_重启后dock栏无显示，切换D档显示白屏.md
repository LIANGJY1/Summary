# SIR-8601 · 重启后锁屏倒计时页卡死，dock 不显示、挂 D 白屏
- **提交**：`11e82d32` | 2026-09-17 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 待测试验证 · 域 在线多媒体

## 问题
测试用测试页验证锁屏、连续输错密码进入倒计时后重启车机；开机停在锁屏倒计时页，倒计时结束后页面状态不正确：dock 栏无显示，切换 D 档白屏、无仪表信息。

## 根因分析
缺陷库 rc：倒计时结束后页面处理状态不正确，未恢复锁屏 PIN 码输入，导致一直拦截不处理档位等信号状态。代码层面：`KeyguardActor` 的倒计时结束路径原来只调用 `showLockoutView(false)` 隐藏倒计时视图，但没有把 `keyguard_main_container`（PIN 输入层）重新置回 `VISIBLE`，形成"锁屏窗口 attach、内容层全 GONE"的全透明拦截页；`isShow()` 沿用 `BaseActor` 的 parent 判断，把这种透明窗仍当作"锁屏展示中"，上层据此持续拦截档位/启动信号——dock 不渲染、D 档白屏。重启恰好在倒计时中发生时，状态持久化恢复走了 NONE 类型分支（`keyguard_main_container` 被手动 GONE），同样缺少内容层修复入口。

## 关键代码修改
改动文件：KeyguardActor.kt（单文件 +71）
```diff
// application/SystemUI/src/main/java/com/android/systemui/keyguard/actor/KeyguardActor.kt
+    override fun isShow(): Boolean {
+        if (!::mKeyguardView.isInitialized) {
+            return false
+        }
+        return getDisplayView().parent != null && hasVisibleLockContent()
+    }
...
+    private fun ensureLockScreenContentVisible() {
+        if (!::mKeyguardView.isInitialized) {
+            return
+        }
+        if (hasVisibleLockContent()) {
+            return
+        }
+        when {
+            mIsLostMode -> applyLostModeUi()
+            mIsLockedOut || mIsNfcLockout -> {
+                mKeyguardView.findViewById<View>(R.id.keyguard_main_container)?.visibility = View.GONE
+                showLockoutView(true)
+            }
+            mLockType == LOCK_TYPE_SLIDE -> setupSlideUnlock()
+            else -> restorePinInputUi()
+        }
+    }
+
+    /** 恢复 PIN 输入页（倒计时结束 / 透明窗兜底）。不关闭锁屏窗口。 */
+    private fun restorePinInputUi() {
+        mKeyguardView.findViewById<View>(R.id.keyguard_main_container)?.visibility = View.VISIBLE
+        mKeyguardView.findViewById<View>(R.id.lockout_container)?.visibility = View.GONE
+        mKeyguardView.findViewById<View>(R.id.lost_mode_container)?.visibility = View.GONE
+    }
...
         // PIN 连续错误倒计时结束：回到密码输入页，窗口不拆（测试忽略 MCU 时仍保持锁屏拦截）
         showLockoutView(false)
+        restorePinInputUi()
+        ensureLockScreenContentVisible()
```

## 为什么能修复
两层修复：其一，倒计时结束路径补上 `restorePinInputUi()`，把 PIN 输入层重新置 `VISIBLE`、倒计时层隐藏，"锁屏窗口在但内容全没"的中间态被消除，锁屏恢复可交互后即放行档位等信号，dock/仪表恢复正常；其二，`isShow()` 重写为"窗口 attach 且 PIN/倒计时/丢失模式/滑动提示至少一层可见"（`hasVisibleLockContent()`，刻意不用 `isShown` 避免首帧未 draw 误判），即使再出现透明窗也不会被当成有效锁屏拦截信号。`ensureLockScreenContentVisible()` 作为兜底挂在 show/refresh/倒计时结束等各入口，按 `mIsLostMode→锁定倒计时→滑动→PIN` 的优先级自愈。副作用：`isShow()` 语义变化影响所有调用方，需保证各判断场景都能接受"内容不可见即未展示"。

## 复盘与经验
- 多层视图切换（PIN/倒计时/丢失模式）构成隐式状态机，"隐藏 A"不等于"显示 B"——状态迁移必须显式双向设置 visibility，或封装成 `restoreXxxUi()` 这类原子方法。
- "窗口存在"与"锁屏生效"是两个概念：拦截类判断要基于可见内容而非窗口 attach 状态，否则透明窗会把整机信号链卡死（本例白屏、dock 消失都是下游症状）。
- 自愈式兜底（`ensureLockScreenContentVisible` 挂满所有入口）适合偶现难复现问题：单次复现路径修不完整时，用不变式"锁屏至少一层可见"收口。
- 该提交 what/why/how 三段相同（复制占位），从缺陷库 rc 才能拿到真正机制——提交信息规范仍是薄弱点。
