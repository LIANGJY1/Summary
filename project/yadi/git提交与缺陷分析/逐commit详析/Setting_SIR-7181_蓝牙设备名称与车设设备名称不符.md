# SIR-7181 · 【偶发】蓝牙设备名称与车设设备名称不符
- **提交**：`1832ad04` | 2026-09-04 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
偶发情况下，蓝牙页展示的设备名（读蓝牙协议栈）与车设持久化的本机名称不一致。

## 根因分析
蓝牙协议栈名称与车设名是两份存储：用户改名的写入链路是"车设持久化 → 同步写协议栈"，协议栈写入偶发失败或未持久化成功时（缺陷库推测，提交说明同），两份数据开始漂移，且无任何自愈机制。与 `7ff2e26f`（启动时兜底）同根因、同一 Change-Id 的姊妹提交：本提交在 UI 入口再加一道兜底，覆盖"服务启动同步也失败/时序更晚"的场景。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
         mBindingHeader.sw.setTitle(getString(R.string.bluetooth))
+        correctDeviceNameIfNeeded()
         val btName = mWxBtManager.name
         mBindingHeader.tvDeviceName.text = if (btName.isNullOrEmpty()) DeviceUtils.getDeviceName() else btName
```

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
+    /**
+     * 蓝牙协议栈名称同步失败兜底
+     */
+    private fun correctDeviceNameIfNeeded() {
+        val deviceName = DeviceUtils.getDeviceName()
+        if (deviceName.isEmpty()) return
+        lifecycleScope.launch(ioDispatcher) {
+            try {
+                val btName = mWxBtManager.name
+                if (!btName.isNullOrEmpty() && btName != deviceName) {
+                    mWxBtManager.name = deviceName
+                    withContext(mainDispatcher) {
+                        mBindingHeader.tvDeviceName.text = deviceName
+                    }
+                    log("correctDeviceNameIfNeeded name: $deviceName")
+                }
+            } catch (e: Exception) {
+                log("correctDeviceNameIfNeeded failed: $e")
+            }
+        }
+    }
```

## 为什么能修复
每次进入蓝牙页面时在 IO 线程读取协议栈名并与车设权威名比对，不一致就回写 `mWxBtManager.name` 并刷新标题文本（`withContext(mainDispatcher)` 回主线程更新 UI），实现"进界面即对账"的自愈；异常全量捕获并打日志，兜底路径本身不会造成崩溃。与启动兜底（`7ff2e26f`）形成双保险。隐患：读取协议栈名是异步的，页面先显示的 `btName` 可能短暂为旧值，对账完成后再刷新，存在一次文案跳变；且兜底只在该页面入口触发。

## 复盘与经验
- 对"偶发写失败"类问题，若写入端不可控（协议栈/外部服务），修复模式就是增加读取端对账兜底：服务启动一次 + 关键 UI 入口一次，双保险覆盖不同时序。
- 兜底逻辑放在 `lifecycleScope.launch(ioDispatcher)` 并 try/catch 全包，保证兜底本身零风险——兜底代码崩溃比原 bug 更伤。
- 名称不一致的两个提交（`7ff2e26f`、`1832ad04`）共享 Change-Id、同日同作者，是同一个修复策略在两个层次（服务层/UI 层）的落地，复盘时可合并理解。
