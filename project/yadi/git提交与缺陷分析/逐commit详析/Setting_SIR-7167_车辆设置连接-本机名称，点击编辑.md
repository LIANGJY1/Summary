# SIR-7167 · 编辑本机名称弹窗输入框显示旧名称

- **提交**：`51cd1ec7` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
连接-本机名称点击"编辑"，二次弹窗输入框里预填的不是当前设备名，而是旧值。

## 根因分析
`ConnectFragment` 在视图初始化时用 `val deviceName = getDeviceName()` 取了一次设备名：既用于刷新 `tvDeviceName` 文本，又被"编辑"点击监听闭包捕获传给 `DeviceNameEditDialogFragment(deviceName)`。此后设备名一旦变化（本机改名、系统侧同步更新），闭包捕获的仍是初始化时刻的局部变量——弹窗每次都拿陈旧快照（缺陷库根因"设备名称没有重新获取"，[how]"弹窗重新取设备名称"）。这是 Java/Kotlin 闭包捕获值语义导致的经典"数据过期"bug。

## 关键代码修改
改动文件：ConnectFragment.kt（仅 1 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
         val deviceName = getDeviceName()
         mBinding.tvDeviceName.text = getString(R.string.connect_device_name, deviceName)
         mBinding.tvEditDeviceName.setOnFastClickListener {
-            val dialog = DeviceNameEditDialogFragment(deviceName)
+            val dialog = DeviceNameEditDialogFragment(getDeviceName())
```

## 为什么能修复
点击时刻现场调用 `getDeviceName()` 重新读取当前设备名，弹窗预填值永远与最新状态一致，消除了对初始化快照的依赖；外层局部 `deviceName` 仍保留用于初始化静态文本，改动无副作用。隐患：`getDeviceName()` 的读取耗时在点击线程上执行，若底层是跨进程查询需注意卡顿；界面其他引用旧名称的位置（如弹窗确认回调里更新 `tvDeviceName`）逻辑未变，行为一致。

## 复盘与经验
- 弹窗预填"当前值"必须在打开时刻现场获取，禁止复用初始化期捕获的局部变量——闭包捕获的是"当时的值"不是"引用"。
- review 快速检查法：看到 `val x = getX()` 与 `setOnClickListener { 用到 x }` 相邻，就要问"x 会中途变吗"。
- 与 `0f84ced1`（同弹窗 hint 文案）、`225c9e76` 等对照可见：设备名/热点名编辑链路是 Setting 模块的高频缺陷聚集地，值得整体走查一次。
