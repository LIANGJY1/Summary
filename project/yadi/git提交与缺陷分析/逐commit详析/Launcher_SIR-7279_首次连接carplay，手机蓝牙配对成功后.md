# SIR-7279 · 首次连接 Carplay 出现两次"选择连接方式"弹窗

- **提交**：`3042e9c4` | 2026-09-03 | dufan | Launcher | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
首次连接 Carplay、手机蓝牙配对成功后选择连接 Carplay，车机连续弹出两个"选择连接方式（蓝牙/Carplay）"弹窗。

## 根因分析
`DeviceConnectManager.showCarPlayConnectDialog` 内部调用 `checkWirelessCarPlayAvailability` 注册 `onWirelessCarPlayAvailabilityChanged` 回调，回调到达时**无条件** new 一个 `TextDialog` 并 show。该回调是异步的，且可能来自上一次请求（[why]："上次请求回调显示"）——蓝牙配对成功触发一次请求、用户再选连接又触发一次，两次回调都存活时各弹一个，或页面重建后旧回调再弹一个，形成重复弹窗。旧代码没有弹窗引用、没有"当前是否允许弹"的门闩，也没有在界面不可见时撤弹窗的机制。

## 关键代码修改
改动文件：DeviceConnectManager.kt、CarConnectFragment.kt
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
+    private var mIsCanShowCarPlayDialog = true
+    private var mCarPlayConnectDialog: TextDialog? = null
     fun showCarPlayConnectDialog(childFragmentManager: FragmentManager, address: String) {
+        mIsCanShowCarPlayDialog = true                     // 新请求重置门闩
                 ...
                 onWirelessCarPlayAvailabilityChanged: ...
+                    if (!mIsCanShowCarPlayDialog) return@checkWirelessCarPlayAvailability   // 拦截过期回调
-                            TextDialog(...).setCallback(...).setOnDismissListener(...).show(childFragmentManager, "ConnectHintDialog")
+                            mCarPlayConnectDialog = TextDialog(...)
+                            try {
+                                mCarPlayConnectDialog?.show(childFragmentManager, "ConnectHintDialog")
+                            } catch (e: Exception) { LogUtils.d(TAG, "showCarPlayConnectDialog:$e") }
+    fun hindCarPlayConnectDialog() {
+        mIsCanShowCarPlayDialog = false
+        if (mCarPlayConnectDialog != null && mCarPlayConnectDialog?.isVisible == true) {
+            mCarPlayConnectDialog?.dismiss()
+            mCarPlayConnectDialog = null
+        }
+    }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
+    override fun onPause() {
+        super.onPause()
+        DeviceConnectManager.getInstance().hindCarPlayConnectDialog()   // 界面关闭即撤弹窗
+    }
```

## 为什么能修复
三层防护闭环：持弹窗引用使"正在显示的弹窗"可被主动撤回；`onPause` 时 `hindCarPlayConnectDialog()` 置门闩为 false 并 dismiss 可见弹窗（[how]"界面关闭不显示"）；回调入口 `if (!mIsCanShowCarPlayDialog) return` 拦截过期请求的迟到回调。新请求进入时门闩复位为 true，正常流程不受影响——过期回调这条根因被彻底切断。隐患：函数名 `hindCarPlayConnectDialog` 是 `hide` 的拼写错误，会传染检索；try-catch 吞异常仅打 log，可能掩盖 FragmentManager 状态异常；门闩为单 Boolean，多设备并发请求时语义粒度不足。

## 复盘与经验
- 异步回调驱动的弹窗必须带"时效性"防护：请求 ID/门闩标志 + 界面生命周期联动（onPause 撤回），否则迟到回调随时再弹一个幽灵窗。
- DialogFragment/Dialog 用 `show(tag)` 后应保存引用并配 dismiss 途径，"弹完不管"在车载多入口环境必然出重复弹窗。
- 命名拼写错误（hide→hind）会长期留存在公共 API 上，review 与 IDE 拼写检查应把公共方法名当一等公民。
