# SIR-6206 · 耳机MC1在可配对与已配对列表同时显示

- **提交**：`b4c5e18f` | 2026-08-23 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙连接界面中，处于配对模式的耳机（MC1）同时出现在"可配对设备"和"已配对设备"两个列表中，列表数据异常。

## 根因分析
`BluetoothAnwFragment`（`diologfragment` 包下的蓝牙子界面）在 `onDestroy` 时调用了 `BtAnwManager.getInstance().unregisterReceiver()`，把管理类里负责接收蓝牙扫描/配对/绑定状态更新的广播接收器整体注销。缺陷库根因描述为"界面关闭后，注销了广播监听，后续未处理数据"：`BtAnwManager` 是跨界面共享的单例，其广播监听不只服务这一个 Fragment；界面关闭后广播链路被掐断，后续设备从"可配对"转入"已配对"的状态迁移事件无人处理，残留的中间态数据（同一个设备既留在扫描结果又进入绑定列表）在下次进入界面时原样展示，形成双重显示。修复方式与根因"不注销广播"一致——由于监听器属于单例 Manager 生命周期，注销动作本身越权。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt（1 行）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@ onDestroy()
     override fun onDestroy() {
         BtAnwManager.getInstance().setIsInitStatus(false)
         BtAnwManager.getInstance().unregisterCallback(this)
-        BtAnwManager.getInstance().unregisterReceiver()
+//        BtAnwManager.getInstance().unregisterReceiver()
         mScanJob?.cancel()
         mRoleUpdateTimeoutJob?.cancel()
```

## 为什么能修复
保留 `BtAnwManager` 内的广播接收器常驻，设备配对状态迁移（扫描列表 → 绑定列表）的事件链路不再因界面销毁而中断，单例中的数据能被后续广播正常刷新/去重，两个列表不再同时呈现同一设备。隐患：注销被注释而非删除，若 `BtAnwManager` 自身从未在合适的全局时机注销接收器，可能造成常驻内存泄漏；Fragment 仍注销了自己的 callback（`unregisterCallback(this)`），防止了对已销毁界面的回调，这部分设计是正确的。

## 复盘与经验
- 界面（短生命周期）不要替单例 Manager（长生命周期）注销其自身的广播/监听：注销范围必须与对象生命周期对齐。
- "只注销自己的回调、不注销公共通道"是这类单例+监听者模式的标准做法。
- 列表数据双显类问题，优先排查状态迁移事件链是否在某个生命周期节点被掐断。
- 注释掉的代码应尽快删除并配注释说明原因，否则容易在后续清理中被"恢复"导致回归。
