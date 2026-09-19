# 无单号 · 数字钥匙锁屏信号对接：校验超时与等待状态机完善

- **提交**：`d9ff046a` | 2026-07-23 | ljl | SystemUI | feature
- **关联单**：无（`4c68fa99` 的补充完善）

## 需求/目标
给 MCU 密码校验异步链路补上两个缺口：6120 回复丢失/迟到时的 5 秒超时兜底（按校验失败处理），以及"锁屏是否由 MCU 唤醒信号拉起"的来源标记（非信号拉起的本地/测试锁屏只走本地校验）。

## 实现结构
2 个文件（+55/-2）：仅改 `DigitalKeyConstants.kt`（新增 `PASSWORD_CHECK_TIMEOUT_MS = 5_000`）与 `DigitalKeyManager.kt`：
- 新增 `keyguardRaisedBySignal` 标记：`showKeyguard`（MCU 信号路径）置 true，`hideKeyguardIfShowing`/`onUnlockSuccess` 清除；`requestPasswordCheck` 入口先检查该标记，false 直接返回 false 走本地校验；
- 新增 `isWaitingPasswordCheckResult` + `passwordCheckTimeoutRunnable`：请求发送成功后启动 5s 超时，超时视为 CHECK_FAIL 通知锁屏；
- 新增 `consumePasswordCheckWait()`：成功/失败结果先消费等待状态，过滤超时后迟到、重复的结果；`handlePasswordCheckResult` 两个分支前置该检查；
- `onUnlockSuccess` 统一清理倒计时、等待状态、超时回调。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyManager.kt
+    /** 下发密码校验请求后长时间未反馈结果，按校验失败处理 */
+    private val passwordCheckTimeoutRunnable = Runnable {
+        if (isWaitingPasswordCheckResult) {
+            isWaitingPasswordCheckResult = false
+            LogUtils.e(TAG, "password check result timeout (${DigitalKeyConstants.PASSWORD_CHECK_TIMEOUT_MS}ms), treat as CHECK_FAIL")
+            notifyKeyguardPasswordCheckFail()
+        }
+    }
+
+    private fun consumePasswordCheckWait(): Boolean {
+        if (!isWaitingPasswordCheckResult) {
+            LogUtils.w(TAG, "consumePasswordCheckWait: not waiting for result, ignore")
+            return false
+        }
+        isWaitingPasswordCheckResult = false
+        noOperationHandler.removeCallbacks(passwordCheckTimeoutRunnable)
+        return true
+    }
```
```diff
     fun requestPasswordCheck(rawPassword: String): Boolean {
+        // 仅MCU唤醒信号拉起的锁屏才走信号校验；测试页面等本地拉起的锁屏返回false，走本地校验
+        if (!keyguardRaisedBySignal) {
+            return false
+        }
         ...
         val sent = vehicleService?.sendPasswordCheckRequest(passwordArray) ?: false
+        if (sent) {
+            isWaitingPasswordCheckResult = true
+            noOperationHandler.removeCallbacks(passwordCheckTimeoutRunnable)
+            noOperationHandler.postDelayed(passwordCheckTimeoutRunnable, DigitalKeyConstants.PASSWORD_CHECK_TIMEOUT_MS)
+        }
         return sent
```
实现讲解：把前一日提交遗留的"请求发出后无回复则永远等待"补齐为完整的小型状态机：空闲 → 等待（有超时）→ 消费（成功/失败/超时三出口）。`consumePasswordCheckWait` 的"一次性消费"语义天然过滤重复与迟到结果；`keyguardRaisedBySignal` 则修正了 `c0ad2c83` 删掉 `isDigitalKeyEnabled` 后"本地测试页面也发 MCU 请求"的过宽准入——准入条件从"功能开关"换成"页面来源"，粒度更准。

## 复盘与要点
- 任何"请求-回复"跨 ECU 链路都必须有超时兜底，本提交是教科书式补法：超时即降级为失败路径，复用既有失败 UI，不引入新分支。
- `consumePasswordCheckWait` 这种"check-and-clear 原子消费"封装值得复用，注意它跑在主线程 Handler 上无并发问题；若换到多线程需加锁。
- 标记位生命周期（show 置位 / hide、unlock 清除）分散在三处，后续加锁屏形态时容易漏清，可考虑收敛为状态枚举。
