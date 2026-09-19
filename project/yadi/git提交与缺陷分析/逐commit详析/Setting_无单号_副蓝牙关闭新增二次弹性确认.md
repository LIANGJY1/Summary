# 无单号 · 副蓝牙关闭新增二次弹性确认
- **提交**：`7ab15d04` | 2026-09-07 | sgh | Setting | bugfix（UX 变更落地，无关联单号）
- **缺陷库**：未关联单号

## 问题
副蓝牙（耳机蓝牙，`BluetoothAnwFragment`）关闭开关仍是一键直关、无二次确认；同时主蓝牙页面在收到 `STATE_ON` 广播后会把开关覆盖层置为 `disableOverlay()`，导致开机完成态下二次确认拦截失效。

## 根因分析
这是 SIR-7464 系列修复（提交 8a4d8a88）的延伸与纠偏。① `BluetoothAnwFragment.setupBluetoothSwitchListener()` 原先在任意 checked 变化时都无条件 `enableOverlay()`，关闭分支直接执行 `mIsUserClosingBluetooth = true`、`BtAnwManager.saveDeviceList(...)`、`operationBluetooth(OperationType.TYPE_DISABLE)` 等关断动作——没有确认环节。② 覆盖层状态机残留旧语义：`onBTStateChange` 的 `STATE_ON` 分支与 `BluetoothFragment` 的 `STATE_ON` 分支都调用 `disableOverlay()`（不拦截），与"开启态需要拦截以弹确认"的新交互相反。修复把主蓝牙页的模式照搬到副蓝牙页：开→`enableOverlay()`+使能流程；关→只 `disableOverlay()`；关断动作整体迁入 `setOnOverlayClickListener` 的 `showTipDialog`（复用 8a4d8a88 引入的 `close_bluetooth_dialog_title/content` 文案）`onConfirm` 中；并补充初始化按 `sw.isChecked` 同步覆盖层。同时把两处 `STATE_ON` 回调的 `disableOverlay()` 纠正为 `enableOverlay()`，使"蓝牙开启 → 下次点击需确认"的状态闭环真正成立。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
```diff
--- application/Setting/.../diologfragment/BluetoothAnwFragment.kt
         mBindingHeader.sw.setOnCheckedChangeListener { isChecked ->
             logClick("mBindingHeader.sw.setOnCheckedChangeListener")
-            mBindingHeader.sw.enableOverlay()
             if (isChecked) {
+                mBindingHeader.sw.enableOverlay()
                 mIsUserClosingBluetooth = false
                 BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_ENABLE)
                 showRefresh(true)
             } else {
-                mIsUserClosingBluetooth = true
-                BtAnwManager.saveDeviceList(BtAnwManager.getInstance().mPairedDevices)
-                BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_DISABLE)
-                showRefresh(false)
-                mBinding.tvNoConnect.visibility = View.VISIBLE
+                mBindingHeader.sw.disableOverlay()
             }
         }
+        // 弹出二次确认
+        mBindingHeader.sw.setOnOverlayClickListener {
+            showTipDialog(
+                title = getString(R.string.close_bluetooth_dialog_title),
+                ...
+                onConfirm = {
+                    mIsUserClosingBluetooth = true
+                    BtAnwManager.saveDeviceList(BtAnwManager.getInstance().mPairedDevices)
+                    BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_DISABLE)
+                    showRefresh(false)
+                    mBinding.tvNoConnect.visibility = View.VISIBLE
+                }
+            )
+        }
--- application/Setting/.../diologfragment/BluetoothAnwFragment.kt（STATE_ON 回调）
-            mBindingHeader.sw.disableOverlay()
+            mBindingHeader.sw.enableOverlay()
--- application/Setting/.../diologfragment/BluetoothFragment.kt（STATE_ON 分支）
-                mBindingHeader.sw.disableOverlay()
+                mBindingHeader.sw.enableOverlay()
```

## 为什么能修复
副蓝牙页补齐了与主蓝牙一致的"开启态拦截 + 弹窗确认 + onConfirm 才关断"链路；`STATE_ON` 两处 `enableOverlay()` 的翻转修复了确认机制的状态闭环——否则每次蓝牙开完成后覆盖层被解除，下一次点击又会直关。隐患：该机制依赖所有改变开关状态的代码路径都同步刷新覆盖层（本提交正是在补上一版漏改的路径），新增入口时容易再漏；确认弹窗期间若蓝牙状态被外部（如协议栈异常重启）改变，UI 状态与覆盖层可能出现短暂不一致。

## 复盘与经验
- 同一交互模式（二次确认开关）落地到多个页面时，应抽取公共封装（模板方法或自定义控件内建 confirm 回调），逐页复制 `onCheckedChange + overlay` 组合必然出现状态分支漏改——本提交修复的 `STATE_ON` 反向调用就是复制的代价。
- 状态驱动 UI 拦截器（覆盖层）必须梳理"所有会改变底层状态的入口"，逐一对齐拦截状态；提交时用 grep 全量搜 `enableOverlay/disableOverlay` 核对语义是低成本自检手段。
