# 无单号 · 手车互联逻辑优化（DeviceConnectManager 状态上报收敛为单一出口）

- **提交**：`dd00ca96` | 2026-07-06 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
重构 `DeviceConnectManager` 的设备状态管理：CarPlay / HiCar / CarLink 三路会话状态回调中重复的"改状态 + post 到主线程 + 遍历监听器通知"代码，收敛为统一的 `setDeviceConnectStatus()` / `updateXxxAppList()` 出口，净减约 100 行。

## 实现结构
- 修改 `control/DeviceConnectManager.kt`（核心，+96/-203）：
  - 新增 `private fun setDeviceConnectStatus(deviceType, isConnected)`：按设备类型维护 `mCurrentConnectType` 与 `productName`（HiCar 连接时固定写 "HUAWEI HiCar"，断开清空并落 SP），断开事件带"当前未连接该类型则忽略"的卫语句，最后统一 `ThreadUtils.runOnUiThread` 遍历回调；
  - 新增 `updateCarLinkAppList()`；HiCar 应用列表刷新复用已有 `updateHiCarAppList()`；
  - 三路 `onSessionStateChanged` / `onServiceConnected` 全部改调统一出口；
  - `Handler(Looper.getMainLooper())` 成员删除，post/postDelayed 全部替换为公共 `ThreadUtils.runOnUiThread(Delayed)`；
  - `connectCarPlay(activity, address)` 签名去掉 Activity 参数，Toast 改用 Application context；
  - 删除无线 CarPlay 自动连接的注释残骸与整段被注释的 `mCarPlayServiceConnectionListener`。
- 删除 `.idea/gradle.xml`（IDE 配置误提交清理）。
- 修改 `Constants.java`：删除 `HAS_HINT_DIALOG_ADDRESS`（Launcher 侧未用）。
- 修改 `CarConnectFragment.kt`：适配 `connectCarPlay` 新签名。

数据流：中间件会话回调 → `setDeviceConnectStatus`（状态机：更新 type/productName → SP 落库）→ 主线程遍历 `mListener.onDeviceStatusChanged` → UI 各处刷新。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
@@ -821,6 +821,60 @@
+    private fun setDeviceConnectStatus(deviceType: String, isConnected: Boolean) {
+        when (deviceType) {
+            CARPLAY -> {
+                if (mCurrentConnectType != 1 && !isConnected) return
+                mProductName = ""
+                SPUtils.setParam("productName", mProductName)
+                mCurrentConnectType = if (isConnected) 1 else 0
+            }
+            HICAR -> {
+                if (mCurrentConnectType != 2 && !isConnected) return
+                if (isConnected) {
+                    mProductName = "HUAWEI HiCar"
+                    SPUtils.setParam("productName", mProductName)
+                    mCurrentConnectType = 2
+                } else { mProductName = ""; mCurrentConnectType = 0 }
+            }
+            CARLINK -> { ... }
+        }
+        ThreadUtils.runOnUiThread {
+            synchronized(mListener) {
+                for (callback in mListener) {
+                    callback.onDeviceStatusChanged(deviceType, isConnected, mProductName)
+                }
+            }
+        }
+    }
```

```diff
@@ -678,21 +598,7 @@
             LogUtils.d(TAG, "onSessionStateChanged=:$i==$mProductName")
-            if (mCurrentConnectType == 2 && i != CarLinkConstants.SessionState.DEVICE_CONNECTED) { return }
-            mCurrentConnectType = if (i == CarLinkConstants.SessionState.DEVICE_CONNECTED) 3 else 0
-            mHandler.post { synchronized(mListener) { ...循环回调... } }
+            setDeviceConnectStatus(CARLINK, i == CarLinkConstants.SessionState.DEVICE_CONNECTED)
```

实现讲解：此前每个回调各自维护一份"改 type/productName → mHandler.post → synchronized 遍历监听器"的样板，且写入值各有出入（HiCar 断开时不清 productName 的版本 vs 清空的版本并存）。收敛后状态机只有一处，"断开去重"卫语句也统一进去（替代此前 CarLink 回调里硬编码 `mCurrentConnectType == 2` 的特判）。`Handler` 换 `ThreadUtils` 则消除了 Manager 持有主线程 Handler 的样板。

## 复盘与要点
- "多处重复的状态流转"收敛为单出口状态机，是互联这类多通道回调架构最有价值的重构；后续加第四种互联协议只需在 when 里加分支。
- 断开事件卫语句 `if (mCurrentConnectType != X && !isConnected) return` 隐含了"同一时刻只连一种"的产品假设，若未来支持多协议并存需重新设计。
- 删掉 Activity 参数让 Manager 与界面解耦（Toast 用 Application context），是回调型 Manager 类去 UI 依赖的可复用改法。
