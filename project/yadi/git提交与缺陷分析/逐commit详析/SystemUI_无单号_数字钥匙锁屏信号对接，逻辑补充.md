# 无单号 · 数字钥匙锁屏密码校验对接 MCU + 无操作关机

- **提交**：`4c68fa99` | 2026-07-22 | ljl | SystemUI | feature
- **关联单**：无

## 需求/目标
把锁屏数字钥匙密码的校验权威从"MPU 本地比对"切换为"MCU 侧校验"：输入 4 位密码后经车控信号（6119/6120）请求 MCU 校验并按回复结果解锁；同时新增"密码界面无操作 60 秒请求关机"（6121）倒计时，并扩展 libcar 信号 ID/常量。

## 实现结构
10 个文件（+364/-16），三层改动：
- 信号层（`com/neusoft/libcar/*`）：`CarPropertyIds` 新增 6119 `MPU_TO_MCU_PASSWORD_CHECK`、6120 `MCU_TO_MPU_REPLY_PASSWORD_CHECK`、6121 `MPU_TO_MCU_NO_PASSWORD_OFF`；`CarConstants` 新增 `ReplyPasswordCheck`（0x00 校验中/0x01 成功/0x02 失败）与 `NoPasswordOff` 常量组；`CarServiceManager/PropertyManager/SendPropertyManager/CarPropertyMapping` 注册属性与新增 `setIntArrayPropertyNoRollback`；
- 服务层：`DigitalKeyVehicleService` 监听列表加入 6120，回调里把 Int 结果转发 `DigitalKeyManager.handlePasswordCheckResult`；新增 `sendPasswordCheckRequest(IntArray)`、`sendNoPasswordOffRequest()` 两个发送接口；顺带把 Standby 判断扩为 0x02/0x03、Toast 改 IC 弹窗；
- 管理层：`DigitalKeyManager` 新增 `requestPasswordCheck`（"1234"→[1,2,3,4]）、`handlePasswordCheckResult`（成功→取消倒计时+onUnlockSuccess+通知锁屏；失败→通知锁屏）、`resetNoOperationTimer/cancelNoOperationTimer`（Handler+Runnable 60s）；
- UI 层：`KeyguardActor.verifyPin` 改为"数字钥匙启用则优先请求 MCU 校验、发送失败回退本地比对"；数字/删除/确认按钮点击都调 `notifyPasswordPageInteraction()` 重置倒计时；新增 `onExternalPasswordCheckSuccess/Fail` 与共用 `handlePinVerifyFail`；`DigitalKeyConstants` 增加密码校验与关机常量、`NO_OPERATION_TIMEOUT_MS=60_000`。

数据流：密码输入 → verifyPin → 6119 请求 → MCU → 6120 回复 → DigitalKeyManager → KeyguardActor.onExternalPasswordCheck{Success,Fail} → unlock/报错锁定；锁屏显示时启动 60s 倒计时，任何按键重置，超时发 6121 请求关机。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/keyguard/actor/KeyguardActor.kt
+        // 数字钥匙场景：优先请求MCU校验密码（MPU_TO_MCU_PASSWORD_CHECK），
+        // 发送失败（车信号服务未就绪等）时回退到本地比对
+        val checkRequested = try {
+            val digitalKeyManager = DigitalKeyManager.getInstance(mContext)
+            digitalKeyManager.isDigitalKeyEnabled() && digitalKeyManager.requestPasswordCheck(mCurrentPin)
+        } catch (e: Exception) {
+            LogUtils.e(TAG, "verifyPin: requestPasswordCheck exception", e)
+            false
+        }
+        if (checkRequested) {
+            LogUtils.i(TAG, "verifyPin: password check request sent to MCU, waiting for result")
+            return
+        }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyManager.kt
+    /** 密码界面无操作60s后发送关机请求（MPU_TO_MCU_NO_PASSWORD_OFF） */
+    private val noOperationTimeoutRunnable = Runnable {
+        val sent = vehicleService?.sendNoPasswordOffRequest() ?: false
+        LogUtils.i(TAG, "sendNoPasswordOffRequest result=$sent")
+    }
+
+    fun handlePasswordCheckResult(result: Int) {
+        when (result) {
+            DigitalKeyConstants.PASSWORD_CHECK_SUCCESS -> {
+                cancelNoOperationTimer()
+                onUnlockSuccess()
+                notifyKeyguardPasswordCheckSuccess()
+            }
+            DigitalKeyConstants.PASSWORD_CHECK_FAIL -> {
+                notifyKeyguardPasswordCheckFail()
+            }
+            else -> { LogUtils.w(TAG, "unknown result=$result") }
+        }
+    }
```
实现讲解：异步校验改造的关键是"请求发出后 verifyPin 直接 return"，把原本同步的本地比对拆成两条路径共用同一批结果处理函数（`handlePinVerifyFail`、外部成功/失败入口），错误次数、锁定逻辑不因路径而异。60s 无操作关机用 Handler+Runnable 实现滑动窗口式重置。

## 复盘与要点
- "MCU 优先、本地兜底、发送失败降级"是车机密码校验的稳妥架构：密码不落 MPU 内存明文比对，安全边界移到 MCU；回退路径保证信号服务未就绪时锁屏仍可用。
- 结果处理收敛到 `handlePasswordCheckResult` 单入口，"校验中(0x00)"状态显式建模，为后续加超时重试留了钩子（本提交未做 6120 无回复超时兜底，是遗留点）。
- 60s 倒计时只在 `showKeyguard` 时启动、`hideKeyguardIfShowing`/解锁时取消，配合每次按键重置；但 `resetNoOperationTimer` 在页面反复 show 时会叠加 postDelayed（先 remove 后 post，逻辑上安全），依赖 Handler 主线程时序。
