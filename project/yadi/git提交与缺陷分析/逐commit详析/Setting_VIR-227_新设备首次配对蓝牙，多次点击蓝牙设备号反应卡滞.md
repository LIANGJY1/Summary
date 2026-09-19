# VIR-227 · 新设备首次配对蓝牙时多次点击设备号反应卡滞

- **提交**：`951bcde6` | 2026-07-28 | dufan | Setting | bugfix
- **缺陷库**：未关联缺陷库记录（单号 VIR-227，defs 无条目）

## 问题
新设备首次配对蓝牙弹出 PIN 确认弹窗后，若用户点击弹窗空白处关闭，再多次点击蓝牙设备号时无反应（配对弹窗不再弹出），表现为"卡滞"。

## 根因分析
`PairDialogActivity` 的 `mBinding.root.setOnFastClickListener`（点空白关闭弹窗）原先只调用 `finish()`，**没有取消系统侧的配对流程**：该设备的 bond 状态仍停留在 `BOND_BONDING`。列表页 `BluetoothFragment.onDeviceBondStateChanged()` 只在 `preState==BOND_BONDING && state==BOND_NONE`（配对被取消/失败）时才会重新拉起 `PairDialogActivity`；配对流程挂死在 BONDING 后，后续点击设备号既不产生新的配对请求、也没有状态迁移，弹窗永远不再出现。另外，直接 `cancelBondProcess` 会触发 `BOND_NONE` 回调，若不加标记会在用户已关闭弹窗后又把弹窗重新弹出，形成"关了又弹"的死循环——这正是本次同时引入 `SIsClickCancel` 标志的原因。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt
         mBinding.root.setOnFastClickListener {
+            SIsClickCancel = true
+            mBtManager.removeListener(this)
+            mBtManager.cancelBondProcess(device)
             finish()
         }
+        mBinding.rlOut.setOnFastClickListener {  }
```

配套逻辑（已存在于 `BluetoothFragment`，本次与标志位呼应）：

```java
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
} else if (state == BluetoothDevice.BOND_NONE) {
    if (PairDialogActivity.SIsClickCancel) return   // 用户已主动取消，不再重弹配对窗
    val intent = Intent(requireContext(), PairDialogActivity::class.java)
    ...
```

## 为什么能修复
点击空白关闭弹窗时主动调用 `mBtManager.cancelBondProcess(device)`，把设备从 `BOND_BONDING` 拉回 `BOND_NONE`，系统配对状态机被复位，下次点击设备号可重新发起配对、弹窗正常弹出；`SIsClickCancel=true` 让 `BluetoothFragment` 在收到取消产生的 `BOND_NONE` 回调时直接 return，避免弹窗被重新拉起。同时移除 listener 提前于 finish，防止 finish 过程中还收到回调。副作用：`SIsClickCancel` 是 companion object 的静态变量，依赖 `initView`/`onNewIntent` 重置为 false，若 Activity 复用路径遗漏重置可能误吞弹窗；`rlOut` 上新增的空点击监听用于拦截点击透传，意图未在提交说明中写明。

## 复盘与经验
- **关闭弹窗 ≠ 取消业务流程**：UI dismiss 与底层状态机（配对/事务/请求）要显式解绑，只 finish 不 cancel 会把系统状态挂死在中间态，后续操作全部失灵。
- **主动取消会触发回调链**：调用 cancel 类 API 前先立"用户已取消"标志，防止回调把刚关掉的界面再拉起来（关闭→重弹死循环）。
- **异步状态机要有中间态超时兜底**：BOND_BONDING 若因 App 侧遗漏 cancel 而长期停留，理想上应由服务层超时自动回滚，而不是依赖每个调用方都记得取消。
