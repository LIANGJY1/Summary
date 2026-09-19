# 无单号 · 手车互联开发：添加HiCar流量共享

- **提交**：`5b5134f8` | 2026-06-29 | dufan | Setting | 类型：手车互联开发提交（[bugfix] 标签、影响等级 D、测试范围"无"，实为新功能）
- **缺陷库**：未关联单号

## 问题
无对应缺陷单。为车设页面新增"HiCar 流量共享"开关能力：此前 `DeviceConnectManager` 中 HiCar 共享网络的三个回调（`onShareNetworkFailed`/`onShareNetworkSuccess`/`onShareNetworkStop`）全是空实现，监听器接口没有对应的事件通道，UI 无从展示与操作流量共享。

## 根因分析
以 diff 实际内容为准，本提交打通"共享网络"事件的完整链路。其一，接口通道：`OnDeviceConnectListener` 移除从未实现的 `onDeviceConnected`，新增 `onShareNetwork(state)`（注释明确 0:开启失败 / 1:开启成功 / 2:关闭成功）；三个 HiCar 回调由空方法改为 `mHandler.post` + `synchronized(mListener)` 遍历分发。其二，控制入口：新增 `changeShareNetworkState(enable)`，`mCurrentConnectType == 2`（HiCar）时调 `mHiCarAppManager.startShareNetwork()/stopShareNetwork()`，CarLink 分支留空（API 调用被注释，尚未就绪）。其三，能力探测：CarLink 的 `onSelectWorkMode(supportedWorkModes)` 用位运算 `supportedWorkModes and CarLinkConstants.WorkMode.SHARE_NETWORK` 判定 `mShareNetworkSupported`。其四，UI 侧 `BluetoothUtil.showTrafficSharingDialog()`（配 `SkinSwitchCardView` 开关）与 `BluetoothFragment` 接线。另注意：`CarLinkConstants` 的导入从 `ts.car.service.carlink.data` 换到 `com.ts.car.service.carlink.aidl`，连接态判断由 `SessionState.DEVICE_CONNECTED` 改为 `DeviceState.CONNECTED`（SDK 包/枚举迁移）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/java/com/yadea/setting/MyApplication.kt、res/values/strings.xml、res/values-en/strings.xml、.idea/vcs.xml（删除）

```diff
--- application/Setting/.../init/DeviceConnectManager.kt
@@ HiCar 共享网络回调空实现 → 事件分发
         override fun onShareNetworkSuccess() {
             LogUtils.d(TAG, "onShareNetworkSuccess:")
+            mHandler.post {
+                synchronized(mListener) {
+                    for (callback in mListener) {
+                        callback.onShareNetwork(1)
+                    }
+                }
+            }
         }
@@ 控制入口（HiCar 先行，CarLink 留空）
+    fun changeShareNetworkState(enable: Boolean) {
+        if (mCurrentConnectType == 2) {
+            if (enable) mHiCarAppManager?.startShareNetwork()
+            else mHiCarAppManager?.stopShareNetwork()
+        } else if (mCurrentConnectType == 3) { /* CarLink 待接入 */ }
+    }
@@ 接口变更
-        fun onDeviceConnected(deviceType: String?)
+        // 共享流量状态 0:开启失败  1：开启成功  2：关闭成功
+        fun onShareNetwork(state: Int)
```

```diff
--- application/Setting/.../init/DeviceConnectManager.kt
@@ CarLink 能力位探测
         override fun onSelectWorkMode(supportedWorkModes: Int) {
             super.onSelectWorkMode(supportedWorkModes)
+            mShareNetworkSupported =
+                (supportedWorkModes and CarLinkConstants.WorkMode.SHARE_NETWORK) !== 0
         }
```

## 为什么能修复
不是修复而是补全：事件通道（`onShareNetwork`）+ 控制入口（`changeShareNetworkState`）+ 能力标志（`mShareNetworkSupported`）三者齐备后，UI 开关才有"可操作、有反馈"的闭环。隐患不少：`changeShareNetworkState` 的 CarLink 分支为空、注释掉的 API 调用留在源码；`onShareNetwork` 用 int 魔法数（0/1/2）而非枚举；接口删除 `onDeviceConnected` 属破坏性变更，所有实现类必须同步改（`BluetoothFragment` 已同步，其他实现若有遗漏将编译失败——这也算一种强制同步的保护）。

## 复盘与经验
- **监听器"空实现"等于功能不存在**：SDK 回调注册了但没有分发，上层永远收不到事件；新增能力时先通事件通道再接 UI。
- **接口演进用"替换未实现方法"**：删 `onDeviceConnected` 换 `onShareNetwork`，让编译器找出所有需要适配的实现类，优于加默认方法留死代码。
- **能力探测走位标志**（`supportedWorkModes and SHARE_NETWORK`）：比硬编码"支持/不支持"更能适配不同车型配置。
