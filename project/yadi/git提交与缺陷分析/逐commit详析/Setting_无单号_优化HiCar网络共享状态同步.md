# 无单号 · 优化 HiCar 网络共享状态同步（ContentObserver 监听全局设置）

- **提交**：`a5d29727` | 2026-07-08 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
让蓝牙设置页的"流量共享"开关实时跟随共享网络状态：注册 `ContentObserver` 监听全局设置键 `current_device_share_network_state`（由互联监听器写入，见 `2e98b2d0`），状态变化即刷新 UI，页面销毁时反注册。

## 实现结构
- 修改 `ui/fragment/diologfragment/BluetoothFragment.kt`（+19/-6）：
  - 新增成员 `mShareNetStateContentObserver: ContentObserver?`；
  - `initData`（蓝牙数据处理处）注册 `Settings.Global.getUriFor(CURRENT_DEVICE_SHARE_NETWORK_STATE)` 的 ContentObserver（主线程 Handler），`onChange` 调 `setTrafficSharingView()`；
  - `setTrafficSharingView()` 读当前状态并加日志；
  - `onDestroyView` 反注册 Observer；
  - 两处日志格式化。

数据流：HiCar/CarLink 网络共享回调 → `SettingsUtils.setGSetting` 写 Settings.Global → `ContentObserver.onChange` → `setTrafficSharingView()` 重读设置 → 开关 UI 更新。原来是"写入方"直接持有 Fragment 回调（onShareNetwork 回调链），现在改为"写入方只写设置、UI 订阅设置"，双侧解耦。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -190,6 +193,16 @@
+        val uri = Settings.Global.getUriFor(CURRENT_DEVICE_SHARE_NETWORK_STATE)
+        mShareNetStateContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
+            override fun onChange(selfChange: Boolean) {
+                setTrafficSharingView()
+            }
+        }
+        requireContext().contentResolver.registerContentObserver(
+            uri, false,
+            mShareNetStateContentObserver!!
+        )
     }
```

```diff
@@ -422,6 +432,9 @@
     override fun onDestroyView() {
         SIsOperationBluetooth = false
         DeviceConnectManager.getInstance().removeListener(this)
+        mShareNetStateContentObserver?.let {
+            requireContext().contentResolver.unregisterContentObserver(it)
+        }
```

实现讲解：把"状态同步"从点对点回调改为通过 `Settings.Global` 作为共享状态源 + `ContentObserver` 订阅，是车机多应用共享状态的惯用机制——任何进程写入都能被感知，且天然支持"页面晚于状态变化打开时读到最新值"（`setTrafficSharingView` 每次显示时重读）。`onChange` 绑主线程 Handler，回调里直接操作 UI 无需再切线程。

## 复盘与要点
- "全局设置 + ContentObserver"可复用为跨应用状态总线（本例中 Launcher 写、Setting 读），比自定义广播少一层协议约定。
- `registerContentObserver(uri, false)` 的 `notifyForDescendants=false` 对叶子 URI 正确；若未来改层级键需注意。
- 反注册放在 `onDestroyView` 且用 `requireContext()`，若 Fragment 已 detach 会抛 IllegalStateException，更稳的是用 `requireContext().applicationContext` 或在 `onDetach` 处理——小的健壮性遗留。
