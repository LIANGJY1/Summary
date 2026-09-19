# SIR-8612 · 蓝牙配对失败弹窗连续弹出两次
- **提交**：`75065c16` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 待测试验证 · 域 主交互

## 问题
点击蓝牙配对失败后，"配对失败"弹窗会连续弹出两次。

## 根因分析
按提交信息 `[why] setting和SystemUI调用两次其他配对弹窗`：同一配对失败事件会分别触发 Setting 侧与 SystemUI 侧的弹窗调用。Setting 侧 `BluetoothFragment` 监听蓝牙绑定状态广播，收到 `BluetoothDevice.BOND_NONE`（配对失败/解除绑定）时直接启动 `PairDialogActivity`；由于另一路（SystemUI 调起的弹窗流程）也会弹出同语义的配对提示，两次触发没有互斥，用户看到弹窗叠现。原代码仅用 `SIsClickCancel` 标记"用户点过取消"这一种抑制条件，覆盖不了"弹窗已存活"的场景。

## 关键代码修改
改动文件：PairDialogActivity.kt、BluetoothFragment.kt
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt
     companion object {
         var SIsClickCancel = false
+        var SAliveCount = 0   // 存活实例计数用于抑制一次配对失败时的重复弹窗
     }
     override fun initView() {
         SIsClickCancel = false
+        SAliveCount++
         device = intent.getParcelableExtra("DEVICE")
...
     override fun onDestroy() {
         super.onDestroy()
         mBtManager.removeListener(this)
+        if (SAliveCount > 0) SAliveCount--   // 实例销毁，计数回退
     }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
             } else if (state == BluetoothDevice.BOND_NONE) {
+                if (PairDialogActivity.SAliveCount > 0) return   // 已有配对弹窗存活，避免失败弹窗叠加
                 if (PairDialogActivity.SIsClickCancel) return
                 val intent = Intent(requireContext(), PairDialogActivity::class.java)
```

## 为什么能修复
用静态 `SAliveCount` 记录 `PairDialogActivity` 的存活实例数：创建时 `initView()` 计数 +1，`onDestroy()` 回退，保证与 Activity 生命周期严格同步。`BluetoothFragment` 收到 `BOND_NONE` 时先检查计数，只要已有一个配对弹窗在展示就直接 `return`，第二路触发被拦截，弹窗不再叠现；弹窗关闭后计数归零，后续真正的配对失败仍能正常弹出，不产生"永远弹不出"的副作用。隐患：静态计数在进程被杀重建时归零属安全默认；若 Activity 异常销毁未走 `onDestroy`（极端场景）计数可能虚高，一般生命周期下可接受。

## 复盘与经验
- 同一事件被多个模块（Setting/SystemUI）各自响应是车机 Android 常见双弹窗根因，跨模块语义重复的弹窗必须有一方做互斥或统一收口到单一入口。
- 用"存活计数"抑制重复弹窗时，计数器加减必须与生命周期回调（initView/onDestroy）成对出现，等价于引用计数管理。
- 与其事后打补丁，更优方案是配对失败弹窗只保留一个监听者（如统一由 SystemUI 或 Setting 展示），从架构上消除重复触发源。
