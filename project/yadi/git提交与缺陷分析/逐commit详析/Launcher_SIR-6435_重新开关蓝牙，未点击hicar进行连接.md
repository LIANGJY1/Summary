# SIR-6435 · 重新开关蓝牙，未点 HiCar 连接手机就弹连接弹窗且车机无配对码

- **提交**：`58a70866` | 2026-08-28 | dufan | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
在互联（Link）界面重新关闭再打开蓝牙，用户并未点击 HiCar 连接，手机端已弹出连接请求弹窗，且车机侧没有显示配对码，配对体验异常。

## 根因分析
`LinkActivity` 的 `mBroadcastReceiver` 监听蓝牙开关状态广播，只要收到 `STATE_ON` 就执行 `setFusionUiForegroundState(true)` 并联动 `DeviceConnectManager` 的融合连接前台逻辑；`onResume` 里也无条件（仅排除连接失败态）执行 `operationDevice(setFusionUiForegroundState...)`。也就是说，**只要用户停留在互联页面把蓝牙开关重新打开，"蓝牙可用"这一事件就会被当成"用户发起了连接"的信号**，自动触发 HiCar 融合连接/配对流程——手机弹窗出现而车机端因未走正常连接入口没有配对码展示。提交信息概括为"开启蓝牙后调用了配对逻辑 → 界面可见时才可以调用"。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
+    private var mIsResume = false
     private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
         override fun onReceive(context: Context?, intent: Intent?) {
             val state = intent!!.getIntExtra("android.bluetooth.adapter.extra.STATE", Int.MIN_VALUE)
-            if (state == ManagerConstants.STATE_ON) {
+            if (mIsResume && state == ManagerConstants.STATE_ON) {
                 DeviceConnectManager.getInstance().mHiCarFusionManager?.setFusionUiForegroundState(true)
                 ...
     override fun onResume() {
         super.onResume()
-        if (!mIsConnectFail) {
+        mIsResume = true
+        if (!mIsConnectFail && mAdapter.state == BluetoothAdapter.STATE_ON) {
             DeviceConnectManager.getInstance().operationDevice(...)
         }
     }
+
+    override fun onPause() {
+        super.onPause()
+        mIsResume = false
+    }
```

## 为什么能修复
新增 `mIsResume` 页面可见性门闩：广播回调里的连接联动只在页面处于前台时放行，页面在后台收到蓝牙 STATE_ON（例如用户在设置页开蓝牙、互联页只是未销毁）不再触发配对；`onResume` 还叠加 `mAdapter.state == STATE_ON` 判断，避免"页面恢复但蓝牙仍在开启中"时提前联动。这样连接流程只能由用户在可见界面显式触发，手机弹窗与车机配对码恢复一致。隐患：后台触发的融合连接场景（若产品上有"后台自动重连"需求）会被此门闩挡住，需要另行设计。

## 复盘经验
- "蓝牙开启"是系统能力事件，"用户要连接"是用户意图事件，二者不能混用同一触发路径；意图类动作必须有 UI 可见/用户操作前置条件。
- Activity 生命周期标志（如 mIsResume）是广播回调最便宜且可靠的上下文守卫，onReceive 里处理业务前先检查界面状态。
- 配对码显示与手机弹窗是配对流程的两端，触发路径分叉时两端状态就会失配，修复要让触发入口唯一化。
