# 无单号 · 连接 HiCar/CarLink 时弹窗确认断开 CarPlay

- **提交**：`0d826973` | 2026-07-30 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
给互斥逻辑补用户交互：切换对话框确认时，先主动断开当前已连接的 CarPlay，再执行切换回调，避免 HiCar/CarLink 接入时 CarPlay 残留连接。

## 实现结构
- `application/Launcher/.../utils/DialogUtil.kt`（+16/-3）：切换确认弹窗 `confirm()` 中插入"遍历 CarPlay 设备列表找 CONNECTED 设备并 `disconnectCarPlay(btAddr)`"逻辑，然后再触发 `mListener.onConfirm()` 与 `dismissDialog()`
- `application/Launcher/.../control/DeviceConnectManager.kt`：仅删除一个空行（-1）

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
@@ -30,10 +34,19 @@ object DialogUtil {
         mSwitchDialog?.setCallback(object : Callback {
             override fun confirm(content: Any?) {
-                if (mListener != null) {
-                    mListener!!.onConfirm()
+                DeviceConnectManager.getInstance().getCarPlayDeviceManager()?.apply {
+                    carPlayDeviceList.forEach {
+                        LogUtils.d(TAG, "handleCarPlayDeviceList: $it")
+                        if (it.deviceStatus == CarPlayConstants.DeviceStatus.CONNECTED) {
+                            disconnectCarPlay(it.btAddr)
+                            return@forEach
+                        }
+                    }
+                    if (mListener != null) {
+                        mListener!!.onConfirm()
+                    }
+                    dismissDialog()
                 }
-                dismissDialog()
             }
```
实现讲解：这是对 `9012fffa` 互斥逻辑的 UI 层补充——用户点"确认切换"时先走 CarPlay 断开（复用与互斥门卫相同的遍历手法），确认成功后再回调业务切换；注意 `onConfirm`/`dismissDialog` 被挪进了 `apply` 块内，若 `getCarPlayDeviceManager()` 返回 null，回调与关窗都不会执行，属本次引入的边界变化。

## 复盘与要点
- "断开旧连接"的遍历代码在 DeviceConnectManager 互斥门卫与 DialogUtil 弹窗确认里出现两份，应抽成 `disconnectConnectedCarPlay()` 之类的公共方法。
- 弹窗确认后先断开再切换的顺序设计合理（避免两通道短暂并存），但断开是异步的，`onConfirm` 立即执行仍可能与断开完成存在竞态，依赖互斥状态机兜底。
