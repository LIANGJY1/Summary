# SRS_ENERGY_001 · [账号中心] 进程退出时调用 unRegisterKeystoreInfoCallback

- **提交**：`ce48a575` | 2026-09-03 | liqingqing | AccountCenter | feature（内容实为缺陷修复：资源/回调泄漏）
- **关联单**：SRS_ENERGY_001（标题引用，无系统单号）

## 问题现象
账号中心进程退出时既不解绑 MessageCenterService，也不反注册 keystore 回调，服务端持有已死亡进程的 `KeystoreCallback.Stub` 引用：进程重启后可能出现僵尸回调、重复注册叠加，绑定泄漏导致消息中心无法正常释放。

## 根因分析
`fc9c99c3` 的 `CertificateAuthManager` 只实现了 init/bind/callback 注册，没有任何 release 路径；且 `bindService`/`scheduleRetryBind` 也不检查 initialized 状态，释放后重试定时器仍会 resurrect 连接。

## 关键代码修改
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/CertificateAuthManager.kt
@@ -158,10 +158,38 @@
+    fun release() {
+        if (!initialized || !::appContext.isInitialized) {
+            return
+        }
+        handler.removeCallbacks(retryBindRunnable)
+        unregisterKeystoreCallback()
+        if (bound) {
+            try {
+                appContext.unbindService(serviceConnection)
+            } catch (e: Exception) {
+                LogUtils.e(TAG, "Unbind MessageCenterService failed: ${e.message}")
+            }
+        }
+        bound = false
+        service = null
+        keyStore = null
+        keyStoreListeners.clear()
+        initialized = false
+    }
+
+    private fun unregisterKeystoreCallback() {
+        val currentService = service ?: return
+        try {
+            currentService.unRegisterKeystoreInfoCallback(keystoreCallback, appContext.packageName)
+        } catch (e: RemoteException) {
+            LogUtils.e(TAG, "Unregister keystore callback failed: ${e.message}")
+        }
+    }
```
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/di/MyApplication.kt
@@ -15,6 +15,10 @@
+    override fun onTerminate() {
+        CertificateAuthManager.release()
+        super.onTerminate()
+    }
```

## 为什么能修复
release 按"取消重试定时器 → 反注册远端回调 → 解绑服务 → 清空本地状态（service/keyStore/listeners/initialized）"的顺序完整回收，`bindService/scheduleRetryBind` 增加 `!initialized` 早退，确保释放后不会有任何路径重新拉起连接；onTerminate 挂钩让进程退出时必然走到。

## 复盘与经验
- 跨进程 callback 注册必须与反注册成对设计——`registerXxx(callback, packageName)` 型 AIDL 接口在对接当天就应把 `unRegisterXxx` 与 release 流程写齐，"注册了再说"是典型的内存/句柄泄漏。
- release 的第一件事是停掉重试调度器（removeCallbacks），否则释放会被异步任务"复活"，这是单例管理器释放的经典陷阱。
- 注意 Android 中 `Application.onTerminate` 在真实设备上通常不会被调用（仅模拟器/特定框架场景），若目标是真正的进程退出清理，还应考虑服务端对 `DeathRecipient`/`onBindingDied` 的兜底——好在本管理器已有 onBindingDied 重连逻辑，服务端侧仍建议加 binder 死亡通知清理。
