# SIR-8631 · WiFi 频繁开关偶现不跟手
- **提交**：`26aae2fe` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 待测试验证 · 域 车控车设

## 问题
频繁打开/关闭 WiFi 时，偶现开关点击无反应（不跟手），此后一直点不动。

## 根因分析
`WlanDialogFragment` 的 WiFi 开关采用"点击即锁、终态解锁"模型：`sw.setOnCheckedChangeListener` 里先 `mBindingHeader.sw.enableOverlay()`（叠加拦截层锁死开关，防止等待期间重复点击）再下发 `mWxWifiManagerI.isEnable = isChecked`；收到 `onWifiStateChanged` 的 `WIFI_STATE_ENABLED/DISABLED` 终态广播后才 `disableOverlay()` 解锁。频繁开关时终态广播偶发丢失（或状态未实际变化导致广播不来），overlay 永远不解锁——开关被永久锁死，表现即"不跟手/点击无效"。提交信息归纳为"wifi状态改变没有重置按钮重置"。

## 关键代码修改
改动文件：WlanDialogFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt
+    // 防状态广播丢失导致开关永久锁死的兜底解锁任务
+    private var mUnlockRunnable: Runnable? = null
...
         mBindingHeader.sw.setOnCheckedChangeListener { isChecked ->
             mBindingHeader.sw.enableOverlay()
             mWxWifiManagerI.isEnable = isChecked
+            scheduleUnlockFallback()
...
             ManagerConstants.WIFI_STATE_ENABLED -> {
+                cancelUnlockFallback()
                 mBindingHeader.sw.disableOverlay()
             }
             ManagerConstants.WIFI_STATE_DISABLED -> {
+                cancelUnlockFallback()
                 mBindingHeader.sw.disableOverlay()
             }
         }
     }
+
+    private fun scheduleUnlockFallback() {
+        cancelUnlockFallback()
+        val runnable = Runnable {
+            LogUtils.e(TAG, "wifi switch unlock fallback triggered")
+            mBindingHeader.sw.disableOverlay()
+        }
+        mUnlockRunnable = runnable
+        mBindingHeader.sw.postDelayed(runnable, 1200L)
+    }
+
+    private fun cancelUnlockFallback() {
+        mUnlockRunnable?.let { mBindingHeader.sw.removeCallbacks(it) }
+        mUnlockRunnable = null
+    }
```

## 为什么能修复
每次下发开关命令即安排一个 1200ms 的兜底解锁任务：正常路径下终态广播到达时 `cancelUnlockFallback()` 先撤销兜底再解锁，行为不变；异常路径（广播丢失/超时）下兜底任务触发强制 `disableOverlay()`，开关恢复可点，"永久锁死"不再发生。重复点击时 `scheduleUnlockFallback` 先 cancel 旧任务再排新任务，避免多个任务叠加提前解锁。隐患：若系统开/关 WiFi 真实耗时超过 1.2s，兜底会提前解锁允许再次点击，可能出现一次冗余下发——相较永久锁死是可接受的取舍。

## 复盘与经验
- "点击锁定→异步终态解锁"模式必须配超时兜底，任何依赖广播/回调解锁的锁都可能因消息丢失变成永久锁。
- 兜底时长应略大于正常终态返回耗时（WiFi 开关通常几百毫秒），并保证同一条锁链上"重入先撤销旧兜底"。
- 与本批次 75065c16（配对弹窗计数互斥）、8f528fb8（调节期间冻结禁用写）同类：异步状态刷新与瞬时 UI 交互状态的同步，是车机设置页 bug 的三大高发区。
