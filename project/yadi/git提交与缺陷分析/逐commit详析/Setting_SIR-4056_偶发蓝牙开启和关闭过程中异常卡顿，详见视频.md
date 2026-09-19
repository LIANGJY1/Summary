# SIR-4056 · 蓝牙开关过程中偶发异常卡顿

- **提交**：`d34954bf` | 2026-07-27 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
设置页开关蓝牙时偶发界面明显卡顿、掉帧。

## 根因分析
`BluetoothFragment.initView()` 在主线程同步执行 `buildInitialDeviceList()`，其内部调用 `BluetoothUtil.handlePairData()` / `handleDeviceList()`，对 `pairedDevices` 做 iterator 遍历、过滤与列表插装，设备多或系统忙时耗时显著，阻塞主线程。关闭蓝牙的回调里又同步调 `handleBluetoothData()` 再次做同样数据处理并刷新 UI，进一步放大卡顿。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt initView()
-        val list = buildInitialDeviceList()
-        mAdapter.setDevicesSize(mPhonePairedDevices.size, 0)
-        mAdapter.addData(list)
-        setupBluetoothSwitchListener()
-        setupTrafficSharingListener()
-        mWxBtManager.addListener(this)
+        if (mWxBtManager.isEnable) {
+            mBindingHeader.sw.isChecked = true
+            mBinding.tvNoConnect.visibility = View.GONE
+        }
+        lifecycleScope.launch(ioDispatcher) {
+            val list = buildInitialDeviceList()
+            withContext(mainDispatcher) {
+                setTrafficSharingView()
+                mAdapter.setRefresh(false)
+                mAdapter.setDevicesSize(mPhonePairedDevices.size, 0)
+                mAdapter.addData(list)
+                setupBluetoothSwitchListener()
+                setupTrafficSharingListener()
+                mWxBtManager.addListener(this@BluetoothFragment)
+            }
+        }
```
```diff
// BluetoothUtil.kt 关闭场景跳过数据处理
-                if (isEnable) {
+                if (isEnable && !isClose) {
                     val iterator = pairedDevices.iterator()
```
配套：`handleBluetoothData()` 增加 `isClose: Boolean = false` 参数，关闭蓝牙时直接 `mBinding.tvNoConnect.visibility = View.VISIBLE` 并把 `isClose` 透传给 `handlePairData`/`handleDeviceList`，跳过配对数据重建；`startScan()` 从嵌套协程改为随 `buildInitialDeviceList` 跑在 io 线程。

## 为什么能修复
把 `handlePairData`/`handleDeviceList` 的遍历与列表构建移到 `ioDispatcher`，仅 UI 触碰（adapter、可见性、监听器注册）回到 `mainDispatcher`，主线程不再被数据处理阻塞；关闭蓝牙时用 `isClose` 短路掉整轮配对数据重建，开关切换更快。隐患：异步加载期间列表短暂空白，且需保证 `buildInitialDeviceList` 内不触碰 View（本次已把 View 操作全部留在主线程块中）。

## 复盘与经验
- "偶发卡顿"多半是主线程做了与设备量/系统负载相关的遍历或 IO，用 StrictMode/trace 定位后应整体下沉到子线程，而不是局部修补。
- 协程切线程要配套"数据在 io、View 回 main"的纪律，否则会埋下 CalledFromWrongThreadException。
- 关闭功能时的清理路径常常照搬开启路径的数据重建逻辑，实际可短路——为清理路径设计轻量分支（isClose 参数）能显著降低耗时。
