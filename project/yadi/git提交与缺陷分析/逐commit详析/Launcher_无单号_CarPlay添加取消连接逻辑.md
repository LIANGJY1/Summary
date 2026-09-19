# 无单号 · CarPlay 添加取消连接逻辑（连接方式选择弹窗下沉 + 超时取消）

- **提交**：`f43f407f` | 2026-07-06 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
为无线 CarPlay 连接补齐"取消"语义：手机发起投射请求而车端弹出"CarPlay 还是蓝牙"选择框时，若用户不选择（弹窗 dismiss）或选择蓝牙，则调用 `cancelWirelessCarPlay` 取消 CarPlay 悬起的连接请求，避免连接残留；同时把弹窗逻辑从 Fragment 下沉到 Manager。

## 实现结构
- 修改 `control/DeviceConnectManager.kt`（+66 行）：新增 `mWxBtManager` 成员与 `showCarPlayConnectDialog(childFragmentManager, address)`——地址已在 CarPlay 设备列表则直连；否则 `checkWirelessCarPlayAvailability` 检查可用性，可用则弹 `TextDialog`（确认→connectCarPlay；取消→`cancelWirelessCarPlay` 后回退蓝牙连接），并挂 `OnDismissListener` 在用户未选择时也执行取消。
- 修改 `function/applist/CarConnectFragment.kt`：广播收到投射地址后的一整段弹窗协程代码替换为一行 `showCarPlayConnectDialog(...)`，Fragment 中的 `mWxBtManager` 成员随之删除（-38 行）。
- 修改 `component/CommonTools/.../dialog/BaseDialogFragment.kt`：`setCallback`/`setOnDismissListener`/`setIsEnableClickMask` 返回类型从 `DialogFragment` 收窄为 `BaseDialogFragment`，修复链式调用时子类方法丢失的继承缺陷。

数据流：手机发起无线 CarPlay → 中间件回调给出 btAddr → Manager 查设备列表：命中直连 / 未命中弹选择框 → 用户取消或关闭 → `cancelWirelessCarPlay(address)` 通知中间件中止 → 选择蓝牙则遍历已配对设备按地址回连。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
@@ -864,6 +874,62 @@
+    fun showCarPlayConnectDialog(childFragmentManager: FragmentManager, address: String) {
+        mCarPlayDeviceManager?.carPlayDeviceList?.firstOrNull {
+            it.btAddr == address
+        }?.apply {
+            connectCarPlay(address)
+        } ?: run {
+            getCarPlayDeviceManager()?.apply {
+                checkWirelessCarPlayAvailability(address) { p0, p1 ->
+                    if (p1) {
+                        ThreadUtils.runOnUiThread {
+                            TextDialog(...连接方式...).setCallback(object : Callback {
+                                    override fun confirm(content: Any?) { connectCarPlay(address) }
+                                    override fun cancel() {
+                                        cancelWirelessCarPlay(address)
+                                        mWxBtManager.pairedDevices.forEach {
+                                            if (it.address == address) { it.connect(true) }
+                                            return@forEach
+                                        }
+                                    }
+                                }).setOnDismissListener(object : DismissListener {
+                                        override fun onDismiss() { cancelWirelessCarPlay(address) }
+                                    }).show(childFragmentManager, "ConnectHintDialog")
+                        }
+                    } else { ...直接走蓝牙回连... }
```

```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
@@ -47,7 +47,7 @@
-    fun setCallback(callback: Callback): DialogFragment {
+    fun setCallback(callback: Callback): BaseDialogFragment {
         this.mCallback = callback
         return this
     }
```

实现讲解：弹窗下沉后，"连接方式选择"在 Launcher 侧有了唯一入口（Setting 模块的 `showPairedDialog` 是另一份平行实现，见 `37bd1320`）。关键新增是 `OnDismissListener`：此前用户按返回/点外部关闭弹窗时，悬起的 CarPlay 连接请求不会被撤销，设备可能延迟自动连上——dismiss 也走 `cancelWirelessCarPlay` 才算把取消语义补全。`BaseDialogFragment` 返回类型收窄是让 `TextDialog.setCallback(...).show(...)` 链式调用不丢类型的标准 builder 修复。

## 复盘与要点
- "弹窗三态"（确认/取消/dismiss）必须全部映射到连接动作，否则留下悬起状态——这是异步连接 + UI 确认组合最容易漏的分支，值得作为检查项。
- 同一"CarPlay vs 蓝牙"选择逻辑在 Launcher 与 Setting 各有一份，弹窗文案与状态记忆方式（Setting 记地址、Launcher 不记）已经出现分叉，应下沉 Common 统一。
- `forEach { ...; return@forEach }` 写在循环体末尾形同虚设（想表达的应是 break），`firstOrNull` + `let` 或 `find` 更能表达意图。
