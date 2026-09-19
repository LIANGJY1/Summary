# 无单号 · 修复蓝牙连接异常（配对取消空指针防护）

- **提交**：`aaf651ca` | 2026-07-29 | dufan | Setting | feature（**实为缺陷修复**）
- **关联单**：无

## 问题
配对确认弹窗 `PairDialogActivity` 中点击取消时偶发蓝牙连接异常/崩溃：取消绑定流程调用 `mBtManager.cancelBondProcess(device)` 时 `device` 可能为 null。

## 根因分析
`PairDialogActivity` 的取消点击回调里，`device` 是可空成员（声明为 `BluetoothDevice?`）。当弹窗展示异常或设备信息尚未回填时用户即可点击取消，`cancelBondProcess(null)` 走到框架层对 null device 解引用，引发 NPE/异常，表现为"蓝牙连接异常"。同类点击 `rlOut` 等路径未做保护。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt
@@ -65,7 +65,9 @@
         mBinding.root.setOnFastClickListener {
             SIsClickCancel = true
             mBtManager.removeListener(this)
-            mBtManager.cancelBondProcess(device)
+            device?.let {
+                mBtManager.cancelBondProcess(it)
+            }
             finish()
         }
```

## 为什么能修复
Kotlin 安全调用 `device?.let{}` 保证仅在设备对象非空时才向蓝牙框架发起取消绑定请求；device 为空时跳过该步直接 `finish()`，既消除了向底层传 null 的崩溃路径，也不影响"移除监听 + 置取消标记 + 关闭弹窗"的正常取消流程。

## 复盘与经验
- 可空平台对象（`BluetoothDevice?`）传给框架 API 前一律先判空/安全调用，弹窗类 UI 的用户操作可能早于数据回填。
- 该提交标题带"修复"字样却打 feature 标签，git 标签与实际性质脱节，复盘统计时应以 diff 内容定性。
- 同文件内 `rlOut` 的空实现点击（`setOnFastClickListener { }`）暗示外围点击吞掉事件的设计，后续可审视是否也需要统一收口取消逻辑。
