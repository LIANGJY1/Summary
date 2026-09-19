# [SRS_BT_LinkSetting_012] · 修改蓝牙设备列表空布局显示逻辑

- **提交**：`1abec6ab` | 2026-08-13 | dufan | Setting | feature（**实为空布局误显示的缺陷修复**）
- **关联单**：SRS_BT_LinkSetting_012

## 问题
蓝牙设置弹窗的"暂无连接"空布局（`tvNoConnect`）显示时机不准：蓝牙开启即隐藏空布局（即使无设备），关闭蓝牙时又无条件显示空布局（即使手机仍与车机保持连接），与真实设备状态不符。

## 根因分析
空布局可见性由"蓝牙开关状态 / 列表是否非空"两个独立信号分散决定，缺少"手机-车机连接"这一关键维度：初始化时只看 `isEnable`；`isClose` 分支只看开关不看连接态，导致关蓝牙后已连接手机仍显示"暂无连接"。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -95,6 +94,8 @@
                 setupTrafficSharingListener()
                 mWxBtManager.addListener(this@BluetoothFragment)
+                mBinding.tvNoConnect.visibility =
+                    if (mPhonePairedDevices.isEmpty()) View.VISIBLE else View.GONE
@@ -361,8 +367,15 @@
             if (isClose) {
-                mBinding.tvNoConnect.visibility = View.VISIBLE
+                var isHasCarPhone = false
+                mPhonePairedDevices.forEach {
+                    if (it.bluetoothDevice?.isPhoneCarConnect ?: false) {
+                        isHasCarPhone = true
+                        return@forEach
+                    }
+                }
+                mBinding.tvNoConnect.visibility = if (isHasCarPhone) View.GONE else View.VISIBLE
             } else if (mPhonePairedDevices.isNotEmpty() || mPhoneAvailableDevices.isNotEmpty()) {
```
同时初始化时机调整：删去"开关开启就隐藏空布局"的早期赋值，改为异步设备列表构建完成后再按 `mPhonePairedDevices` 是否为空决定显隐；`setTrafficSharingView()` 移除 `isEnable` 前置条件并先清空 `SCurrentThirdDevice`，按实际手机连接态重算。

## 为什么能修复
空布局判定收敛为单一事实源——"是否存在与车机保持连接的手机（`isPhoneCarConnect`）"：初始化、刷新数据、蓝牙关闭三条路径统一按该事实（或设备列表空）决定显隐，消除了"开关状态覆盖连接事实"的错误分支。

## 复盘与经验
- 状态驱动的 UI 显隐应收敛到单一事实源（本例为手机连接态），而不是每个事件各自 set visibility；多路径写同一 View 的可见性极易漂移。
- 初始化异步加载完成后再定 UI 初态，避免"先按开关猜、列表回来再纠正"的闪烁与错判。
- 该 diff 还夹带了大量纯格式化改动（换行、注释位置），评审时应过滤噪声聚焦可见性逻辑行。
