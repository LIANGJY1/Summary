# 无单号 · 修改网络分享回调

- **提交**：`26e3f4ea` | 2026-07-09 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
收敛网络共享（流量分享）的回调分发与开关状态同步：把 6 处重复的"UI 线程 + 遍历监听者"逻辑抽成单一方法，并消除程序化刷写开关被误判为用户点击、以及重复刷写引发的回调风暴。

## 实现结构
改动 2 个文件：
- `init/DeviceConnectManager.kt`：HiCar 与 CarLink 两组 `onShareNetworkFailed/Success/Stop/Received` 回调中的重复分发代码全部替换为新增私有方法 `setShareNetWork(state)`（内部 `runOnUiThread + synchronized + 遍历回调`）；`changeShareNetworkState` 撤掉前提交写入 Settings 的逻辑（注释掉），并在"设备不支持共享"分支主动回发 `setShareNetWork(2)` 让 UI 复位；增加关键路径日志。
- `ui/fragment/diologfragment/BluetoothFragment.kt`：
  - `setTrafficSharingView` 刷写开关前先比对 `isChecked != isCheck`，仅在确实变化时置 `SIsFromUserClick=false` 再赋值；
  - `onShareNetwork(state)` 中 `SIsFromUserClick=false` 从无条件执行改为"仅在实际翻转开关的分支"执行，且 1/2 状态仅在与当前不一致时才刷写；
  - 被吞掉的程序化事件补 `disableOverlay()` 恢复开关可点态。

数据流：车端/互联服务回调 → `DeviceConnectManager.setShareNetWork(state)` → `BluetoothFragment.onShareNetwork` → 仅状态差异时回写 `swSharing.isChecked`（带一次性闸门 `SIsFromUserClick`）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
+    private fun setShareNetWork(state: Int){
+        ThreadUtils.runOnUiThread {
+            synchronized(mListener) {
+                for (callback in mListener) {
+                    callback.onShareNetwork(state)
+                }
+            }
+        }
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -404,7 +406,11 @@
             val currentShareState = SettingsUtils.getGSetting(CURRENT_DEVICE_SHARE_NETWORK_STATE, 2)
             log("currentShareState: $currentShareState")
-            mBindingHeader.swSharing.isChecked = currentShareState == 1
+            val isCheck = currentShareState == 1
+            if (mBindingHeader.swSharing.isChecked != isCheck) {
+                SIsFromUserClick = false
+                mBindingHeader.swSharing.isChecked = isCheck
+            }
```
实现讲解：两步走——生产端把 6 份复制粘贴收敛为一个分发函数（改一个 bug 不再需要改 6 处）；消费端给所有程序化刷写加"变化检测 + 先降闸门再赋值"的固定次序，保证只吞掉真正由代码触发的回调，用户点击不受影响。失败(0)分支回翻开关的语义保持原样。

## 复盘与要点
- "先置标志、再赋值"的次序约束是静态开关防误触的关键：若先赋值再置标志，监听器回调已发出、闸门来不及生效。这套模式可整体复用到任何自定义 Switch 的双向同步。
- 生产端去重（一个分发函数）与消费端去重（变化检测）配合，才能彻底掐断"刷写→回调→再刷写"的循环；单改一端效果有限。
- 遗留风险：`changeShareNetworkState` 里 Settings 写入被注释而非删除，说明状态权威源还在摇摆（Settings vs 内存回调），后续应确定单一事实源并清掉注释代码。
