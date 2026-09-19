# 无单号 · STR唤醒后需重新向linux发送ready状态防止卡开机动画

- **提交**：`4ee1f571` | 2026-08-25 | liujinfeng | Launcher | bugfix（无关联单号，标记 SIR-XXX）
- **缺陷库**：未关联单号（提交 what/why/how 完整：进 STR 不掉电，但唤醒会重走开机动画）

## 问题
车机从 STR（Suspend to RAM）唤醒时，由于内存未掉电、Android 侧不会重走启动流程，但 Linux 侧会重走开机动画；Android 不再发送 ready 握手信号，导致卡在开机动画无法进入桌面。

## 根因分析
`VehicleService` 启动时通过 `mDelayRunnable`（`getLocalHandler().postDelayed(mDelayRunnable, 300)`）向 L2A 发送 `IVI_Ready_Status` 握手，`commStateCallback.onCommState` 收到 `IVI_Ready_Status==1` 回执后置 `hasSendMessageSuccess = true` 并停止重发。这套握手只在进程生命周期开始时执行一次。STR 唤醒场景下 Android 进程从未销毁（进 STR 不掉电），`hasSendMessageSuccess` 保持 true、`mDelayRunnable` 早已移除，而 Linux 侧重启后等待的 ready 信号永远等不到，于是卡在开机动画。缺的是"检测到唤醒并重放握手"的触发点。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java（+62/-18）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java
@@ init() 注册亮灭屏广播
         initL2A();
+        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
+        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
+        mAppContext.registerReceiver(mScreenReceiver, screenFilter);
         getLocalHandler().postDelayed(mDelayRunnable, 300);
@@ 新增亮灭屏接收器
+    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
+        @Override
+        public void onReceive(Context context, Intent intent) {
+            String action = intent.getAction();
+            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
+                mScreenOffReceived = true;
+                LogUtils.d(TAG, "SCREEN_OFF: enter STR");
+            } else if (Intent.ACTION_SCREEN_ON.equals(action) && mScreenOffReceived) {
+                mScreenOffReceived = false;
+                LogUtils.d(TAG, "SCREEN_ON: exit STR, resend IVI ready");
+                hasSendMessageSuccess = false;
+                getLocalHandler().removeCallbacks(mDelayRunnable);
+                getLocalHandler().post(mDelayRunnable);
+            }
+        }
+    };
@@ onDestroy 注销
+        mAppContext.unregisterReceiver(mScreenReceiver);
```
（附带改动：`onVehicleAdasStatus` 增加 `mCachedSmartLightState != adasInfo.param6` 变化判断，值不变不再重复写 Kanzi；精简若干日志。）

## 为什么能修复
用 `ACTION_SCREEN_OFF/ON` 作为 STR 进出的事件代理：灭屏记 `mScreenOffReceived`，亮屏且此前灭屏则判定为 STR 唤醒，重置 `hasSendMessageSuccess` 并重新投递 `mDelayRunnable`，ready 握手重放，Linux 侧拿到 `IVI_Ready_Status` 后开机动画正常退出。`mScreenOffReceived` 门禁避免普通亮灭屏（非 STR）误触发重复握手。隐患：亮灭屏广播并不严格等价于 STR 进出（用户手动熄屏再亮屏也会命中），此时多一次 ready 重放——因协议幂等（回执置位即停），风险可控；广播在 `onDestroy` 已注销，无泄漏。

## 复盘与经验
- STR 这类"Android 不重启、对端重启"的架构，所有一次性握手/注册/初始化都必须有唤醒重放机制，"进程活着"不等于"链路活着"。
- 缺少专门唤醒事件时，`SCREEN_OFF/ON` 广播对是可行的近似代理，但要设计门禁条件（如 `mScreenOffReceived`）与幂等协议兜底误触发。
- 握手状态机（发送→回执→停发）本身写得很健壮，问题只在没有第二触发源——设计初始化逻辑时要问"除了进程启动，还有哪些场景需要重跑这段"。
- 高频回调（ADAS 状态）加变化判断再写 Kanzi，属顺手的性能/日志降噪优化，与主修复无关但同提交，建议拆分。
