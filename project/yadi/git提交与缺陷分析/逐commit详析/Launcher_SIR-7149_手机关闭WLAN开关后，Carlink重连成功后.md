# SIR-7149 · 手机关闭 WLAN 后 Carlink 重连成功但车机未显示融合应用列表
- **提交**：`f568d7ea` | 2026-09-01 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
手机关闭 WLAN 开关再打开后，CarLink 重连成功，但车机端手车互联界面不显示融合应用列表。

## 根因分析
`CarConnectFragment.onDeviceStatusChanged()` 原逻辑只处理了断连分支：`if ((CARLINK || HICAR) && !isConnected)` 时显示顶部卡片、隐藏列表；而重连成功（`isConnected == true`）时没有任何 UI 恢复动作，列表 `recyclerViewCarConn` 维持隐藏、`mAppInfoList` 维持空。应用列表数据本身由 `DeviceConnectManager` 回调/缓存（见 `764f03a5` 引入的 `mCachedCarLinkAppList`）维护，但 Fragment 侧的监听注册时序晚于列表到达事件时，数据事件被错过，界面停留在断连态——即元数据所称"监听时序有误"。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt`
```diff
--- .../function/applist/CarConnectFragment.kt
         setConnStatus(deviceType!!, isConnected)
-            if ((DeviceConnectManager.CARLINK == deviceType
-                        || DeviceConnectManager.HICAR == deviceType)
-                && !isConnected
-            ) {
-                mBinding.layoutTopCard.visibility = View.VISIBLE
-                mBinding.recyclerViewCarConn.visibility = View.GONE
+            if (DeviceConnectManager.CARLINK == deviceType
+                || DeviceConnectManager.HICAR == deviceType
+            ) {
+                if (isConnected) {
+                    mBinding.layoutTopCard.visibility = View.GONE
+                    mBinding.recyclerViewCarConn.visibility = View.VISIBLE
+                    if (mAppInfoList.isEmpty()) {
+                        val cachedAppInfoList =
+                            deviceConnectManager.getCachedCarLinkAppList()
+                        if (cachedAppInfoList.isNotEmpty()) {
+                            mAdapter?.setArrayData(cachedAppInfoList, "cachedCarLinkAppList")
+                        }
+                    }
+                } else {
+                    mBinding.layoutTopCard.visibility = View.VISIBLE
+                    mBinding.recyclerViewCarConn.visibility = View.GONE
+                }
             }
```
另删除了 initObserve 中 4 个调试用 `setOnFastClickListener` 日志按钮。

## 为什么能修复
把状态处理从"只管断连"补成完整对称分支：重连成功时立即切回列表可见态，并在本地列表为空时用 `DeviceConnectManager.getCachedCarLinkAppList()` 缓存兜底渲染，弥补"数据事件先于界面监听"的时序差。即使推送回调再次到来，也会正常覆盖。隐患：缓存数据可能不是最新（如手机端刚卸载应用），但列表变更回调随后会刷新，属可接受的最终一致。

## 复盘与经验
- 状态回调处理要覆盖全部分支：只写 `if (error)` 不写成功分支，重连/恢复类场景必然漏更新。
- "监听时序有误"的通用解法是"事件 + 快照"双通道：错过事件时可从管理器缓存读取当前快照自愈。
- 偶发 UI 状态卡死，优先检查断开→恢复的状态机是否有去程无回程。
