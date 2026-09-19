# SIR-5832 · 设置页手机蓝牙界面数据显示错乱
- **提交**：`dfe286b4` | 2026-08-12 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 车控车设

## 问题
手机蓝牙设置界面偶现显示错误/数据错乱（偶现，低于 10%）。

## 根因分析
两处异步刷新问题叠加：
1. `BluetoothUtil.SRefreshData` 是伴生对象里的静态 `MutableLiveData<Boolean>`，`DeviceConnectManager`（数据刷新时 `postValue(true)`）与 `BluetoothUtil`（同步完成时）都会置 true。静态 LiveData 的"粘性"特性使 Fragment 重建、重新 `observe` 时会立即收到上一次遗留的 `true`，旧回调在新页面生命周期内触发 `handleBluetoothData()`，此时异步设备数据尚未就绪或已过期，导致列表数据错乱。
2. `initView()` 里 `mAdapter.setRefresh(false)` 无条件关闭了 Adapter 的刷新能力：蓝牙已开启（`mWxBtManager.isEnable`）时本应允许扫描结果继续刷新列表，却被禁用，后续数据变更无法正常呈现。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt (initView)
                 setTrafficSharingView()
-                mAdapter.setRefresh(false)
+                if (!mWxBtManager.isEnable) {
+                    mAdapter.setRefresh(false)
+                }
                 mAdapter.setDevicesSize(mPhonePairedDevices.size, 0)
```
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt (initObserve)
     override fun initObserve() {
+        BluetoothUtil.SRefreshData.value = false
         DeviceConnectManager.getInstance().setOnDeviceListener(this)
         BluetoothUtil.SRefreshData.observe(viewLifecycleOwner) {
             LogUtils.d(TAG, "initObserve: SRefreshData $it")
-            handleBluetoothData()
+            if (it){
+                handleBluetoothData()
+            }
         }
     }
```

## 为什么能修复
注册观察者前先把静态 LiveData 重置为 `false`，粘性事件变为 no-op；观察回调再叠加 `if (it)` 双保险，只有真实的"数据已刷新"事件（true）才触发 `handleBluetoothData()`，消除了旧事件在新页面错位执行的根因。`setRefresh(false)` 只在蓝牙关闭时执行，开启状态下列表保持可刷新，扫描/配对数据变化能正常上屏。隐患：若页面在 `initObserve` 与真实 postValue 之间有极窄窗口，重置仍可能吞掉一次合法刷新，但设备状态还有 `DeviceConnectManager.setOnDeviceListener` 兜底，风险可控。

## 复盘与经验
- 伴生对象/单例中的 LiveData 是天然的全局粘性事件总线，Fragment 重建时旧值会立刻投递；"先重置再 observe"或改用 SharedFlow/事件包装类（Event wrapper）是标准对策。
- 布尔型刷新信号必须明确"哪个值代表有效事件"，观察端用 `if (it)` 显式过滤比无条件处理更稳。
- `setRefresh(false)` 这类影响列表数据通路的开关要区分场景（蓝牙开/关），一刀切会制造"数据有了但界面不更新"的错乱假象。
