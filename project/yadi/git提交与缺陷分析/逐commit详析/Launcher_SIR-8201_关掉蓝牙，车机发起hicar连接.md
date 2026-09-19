# SIR-8201 · 关掉蓝牙后车机发起 HiCar 连接无"蓝牙开启中"弹窗

- **提交**：`880a260b` | 2026-09-11 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
蓝牙处于关闭状态时车机发起 HiCar 连接，界面上没有出现"蓝牙开启中"的提示界面，用户看不到连接进度。

## 根因分析
`Launcher` 的 `LinkActivity` 处理互联连接时有两个缺口：一是 `initObserve()` 里注册 `android.bluetooth.adapter.action.STATE_CHANGED` 广播接收器的代码被 `if (!mIsCarLink)` 包住——HiCar 场景（`mIsCarLink == true`）不注册蓝牙状态广播，蓝牙从关闭到开启的状态变化过程完全无从感知；二是发现蓝牙未开启（`mAdapter.state != BluetoothAdapter.STATE_ON`）时虽然调用了 `mAdapter.enable()`（车机场景免授权），却没有把"蓝牙开启中"的视图置为可见，界面停留在无反馈状态。两处叠加导致 HiCar 发起连接时既不显示开启中界面，也等不到状态刷新。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt`
```diff
         if (mAdapter.state != BluetoothAdapter.STATE_ON) {
             @Suppress("DEPRECATION")
             mAdapter.enable() // NOSONAR 车机场景无需用户弹窗授权
+            setViewVisibility(0)
         } else {
             val bondedDevices: Set<BluetoothDevice> = mAdapter.bondedDevices
```
```diff
     override fun initObserve() {
-        if (!mIsCarLink) {
-            val intentFilter = IntentFilter()
-            intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED")
-            registerReceiver(mBroadcastReceiver, intentFilter)
-        }
+        val intentFilter = IntentFilter()
+        intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED")
+        registerReceiver(mBroadcastReceiver, intentFilter)
     }
```

## 为什么能修复
发起 `enable()` 后立即 `setViewVisibility(0)` 显示"蓝牙开启中"界面，用户即时获得反馈；去掉 `!mIsCarLink` 条件后 HiCar 场景同样注册 `STATE_CHANGED` 广播，蓝牙开启完成时 `mBroadcastReceiver` 能收到回调并推进后续连接流程。副作用：非 CarLink 场景原本不监听该广播，现在也会注册，需确认其 `onReceive` 处理对 HiCar 场景无干扰（本例正是要让它生效，风险低）。

## 复盘与经验
- "某场景不需要 X"的条件分支（如 `!mIsCarLink` 不注册广播）在需求演进后常变成漏洞，增删条件前要重新确认每个场景的事件依赖。
- 异步操作（`enable()`）发起后必须立刻给出中间态 UI（开启中/连接中），纯等待回调的界面在回调慢或丢失时就是"无响应"。
- 状态机驱动 UI 的页面，广播注册要覆盖所有会驱动它的入口场景。
