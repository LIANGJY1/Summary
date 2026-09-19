# SIR-7003 · 车设与负一屏两个无线网络界面显示不同步

- **提交**：`1c15bcf0` | 2026-09-02 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
在车设的无线网络连接界面停留时，下拉负一屏再次进入无线网络界面，两个界面的列表滚动条位置不一致、页面显示不同步。

## 根因分析
WLAN/蓝牙/热点弹窗存在两套宿主：`ConnectFragment.showConnectDialog` 用 `childFragmentManager` 在车设内直接 show `WlanDialogFragment` 等，并用静态标志 `isWifiDialogShowing / isBluetoothDialogShowing / isHotspotDialogShowing` 防重；而负一屏走 `ConnectChildDialogActivity` 独立 show 一份同名 DialogFragment。两个实例互相不知道对方的滚动状态，各滚各的，出现"同一界面两份显示、滑动条不在同一位置"。更糟的是防重标志只在各自模块内生效，`ConnectChildDialogActivity.showWifiDialog` 里的 `if (ConnectFragment.isWifiDialogShowing) finish()` 只是补丁式互斥（[why] 原话："显示的是2个界面"；[how]："改为一个界面"）。

## 关键代码修改
改动文件：ConnectChildDialogActivity.kt、ConnectFragment.kt（净删约 95 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
     fun showConnectDialog(type: String) {
         log("showConnectDialog：$type")
-        when (type) { ... 在 childFragmentManager 中 show WlanDialogFragment/BluetoothDialogFragment/HotspotDialogFragment，
-                       维护 isWifiDialogShowing 等静态标志 ... }
+        val intent = Intent(requireContext(), ConnectChildDialogActivity::class.java)
+        intent.putExtra("type", type)
+        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
+        startActivity(intent)
     }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/activity/ConnectChildDialogActivity.kt
-        // 车控WiFi弹窗正在显示时，控制中心入口不再弹出，避免两个WiFi弹窗
-        if (ConnectFragment.isWifiDialogShowing) { finish(); return }
         if (mWlanDialogFragment == null || !mWlanDialogFragment!!.isVisible) {
             mWlanDialogFragment = WlanDialogFragment()
+        // 并接收语音关闭事件：VoiceOperationUtil.getCloseConnectDialog().observe(this) { ... safeDismiss(); finish() }
```

## 为什么能修复
车设入口改为 startActivity 委托 `ConnectChildDialogActivity`，全局只剩 Activity 这一个 Dialog 宿主，两入口天然展示同一实例（Activity 复用 onNewIntent），滚动位置、数据状态必然一致；静态 `is*DialogShowing` 标志、`onPause` 里 `is_click_home` 的补丁性 dismiss、语音关闭 observer 全部随宿主收敛而删除（-123 行）。隐患：语音关闭 LiveData 从 Fragment 的 viewLifecycleOwner 改为 Activity 观察，Activity 未启动时语音指令无效属预期行为；`onNewIntent` 路径的类型切换需回归。

## 复盘与经验
- 同一 UI 从两个入口弹出时，"各处自己 show + 静态标志防重"注定腐化；正确姿势是收敛到单一宿主（专用 DialogActivity）统一调度。
- 静态 Boolean 管理弹窗状态跨进程/跨生命周期不可靠（进程回收、视图重建都会失真），能用架构收敛就不要用标志位补丁。
- 这次修复同时删掉 `onPause` 里 `getGSetting("is_click_home")` 的特殊分支，说明宿主唯一化后大量边角补丁自然消失——好的结构性修复会让代码变少。
