# SIR-7172 · 首次连接 CarPlay 后无法进入融合桌面
- **提交**：`674cd8c3` | 2026-09-02 | dufan | Launcher | bugfix（cherry-pick 自 951ceeaa）
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
手机首次连接 CarPlay 后，车机端未进入融合桌面（手车互联界面不显示）。

## 根因分析
`CarConnectFragment` 中 `BluetoothEventManager` 广播接收器（监听蓝牙配对状态变化）在 `onReceive` 里先 `lifecycleScope.launch { delay(200.milliseconds) }` 等待 200ms，再从全局设置读 `CONNECT_DEVICE_ADDRESS`，地址非空才继续走进入融合桌面的链路。首次连接时底层写入该设置值的完成时间超过 200ms（与 `4cdc4bd8`、`f568d7ea` 同源的"底层响应慢/监听时序"问题），读到的地址为空，流程被 `TextUtils.isEmpty(address)` 判断拦下，融合桌面入口未触发。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt`
```diff
--- .../function/applist/CarConnectFragment.kt
         override fun onReceive(context: Context?, intent: Intent?) {
             lifecycleScope.launch {
-                delay(200.milliseconds)
+                delay(500.milliseconds)
                 var address = SettingsUtils.getGSetting(CONNECT_DEVICE_ADDRESS)
                 log("BluetoothEventManager onReceive: $address")
```

## 为什么能修复
把固定等待从 200ms 放宽到 500ms，覆盖首次连接时底层写入 `CONNECT_DEVICE_ADDRESS` 的典型延迟，使后续读取能拿到有效地址，融合桌面链路得以继续。隐患与 `4cdc4bd8` 相同：仍是以更大固定延时掩盖异步时序，极端情况下（底层更慢）仍会失败；治本方案是监听设置值变化或重试读取。该提交为 cherry-pick，说明同一修复需同步多个分支。

## 复盘与经验
- 本批次（7036/7089/7149/7172）连续四个 B 级缺陷都围绕"跨进程数据未就绪 + 固定延时/一次性读取"，说明该链路需要一次系统性的事件驱动改造，而不是逐单加延时。
- 广播接收器里读全局设置时，"读到空即放弃"应改为有限次重试或值监听，比调大 delay 更稳。
- cherry-pick 修复要注意各分支上的同类代码是否都要同步（版本管理成本）。
