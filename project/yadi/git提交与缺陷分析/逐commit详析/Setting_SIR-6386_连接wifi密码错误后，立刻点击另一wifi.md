# SIR-6386 · 密码错误后立刻点另一个 WiFi，新弹窗仍显示密码错误

- **提交**：`227620cd` | 2026-08-27 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
连接某 WiFi 输错密码后，立刻点击另一个 WiFi 打开密码输入弹窗，新弹窗直接显示"密码错误"状态——明明还没输入新密码。

## 根因分析
`WlanCustomEditDialogFragment` 在 `onStart` 注册 WiFi 状态广播，`handleAuthenticationError` 收到认证失败（`DetailedState.DISCONNECTED`）就调 `showPwdError()` 把弹窗置为错误态（停 loading、禁用按钮、清空输入框）。问题在于 WiFi 框架的认证失败/断开广播是**延迟到达且粘性重放**的：上一次连接失败的广播在新弹窗打开（重新 `onStart` 注册）后才送达，新弹窗实例不区分"这是不是自己发起的连接"，直接把上一次的失败结果当成当前连接的结果渲染出来（缺陷库：打开弹窗每次会收到上次连接失败的广播）。弹窗缺少"我是否正在发起连接"的状态门槛，是过时广播能污染新 UI 的根源。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanCustomEditDialogFragment.kt`

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanCustomEditDialogFragment.kt
@@ -43,6 +43,8 @@ class WlanCustomEditDialogFragment(
     private var wifiManager: WifiManager? = null
+    // 用户点击"连接"后才进入连接中状态仅在此时处理认证失败广播
+    private var isConnecting = false
@@ -107,6 +109,7 @@
         mBinding.slbBtn.setOnFastClickListener { _: View? ->
             if (mBinding.slbBtn.isLoading() || !mBinding.slbBtn.isButtonEnabled()) return@setOnFastClickListener
             val input = mBinding.etContent.getText().toString().trim { it <= ' ' }
+            isConnecting = true
             mBinding.slbBtn.setLoading(true)
             mCallback?.confirm(input)
         }
@@ -140,6 +143,7 @@
     fun showPwdError() {
+        isConnecting = false
         mBinding.slbBtn.setLoading(false)
         mBinding.slbBtn.setButtonEnabled(false)
         mBinding.etContent.setText("")
@@ -233,6 +237,9 @@
     private fun handleAuthenticationError(newState: SupplicantState?, detailedState: NetworkInfo.DetailedState) {
+        if (!isConnecting) {
+            return
+        }
         if (detailedState == NetworkInfo.DetailedState.DISCONNECTED) {
             showPwdError()
```

## 为什么能修复
引入 `isConnecting` 门闸：只有用户点了"连接"按钮（`isConnecting=true`）之后的认证失败广播才被处理；`showPwdError()` 消费失败结果时把门闸复位为 false。上一次连接的迟到广播到达时门闸是关的，`handleAuthenticationError` 直接 `return`，新弹窗不会被旧结果污染。副作用：连接成功路径如果只停在 loading 不再复用该弹窗，则门闸保持 true 也无影响；若后续在"连接成功"分支也有状态复位需求，注意同步维护该标志，避免下次连接前门闸状态残留。

## 复盘与经验
- WiFi/蓝牙这类系统服务的广播是异步且可能粘性重放的，UI 处理广播必须带"请求上下文"（是否正在连接、连接的是哪个 SSID），否则过时事件必然污染新会话的 UI。
- 更严格的版本是给每次连接带 requestId/SSID 比对（只处理匹配的结果），本例的 `isConnecting` 布尔门闸是最小修复，能解决"上一个失败"场景。
- 弹窗生命周期内注册/注销广播的写法，要额外考虑"注册瞬间收到历史粘性广播"的可能，`onStart` 注册处就是过时事件的入口，防线要设在处理函数而非注册处。
