# SIR-7464 · 手机蓝牙和耳机蓝牙关闭开关缺少二次弹窗确认
- **提交**：`8a4d8a88` | 2026-09-05 | sgh | Setting | bugfix（需求遗漏补功能）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置页蓝牙开关一键即关，没有二次确认弹窗；而关闭蓝牙会导致胎压监测显示不可用，产品要求关闭前必须弹窗确认。

## 根因分析
需求遗漏导致 `BluetoothFragment.setupBluetoothSwitchListener()` 里开关回调直接执行关断动作：`onCheckedChange { isChecked -> ... mWxBtManager.stopScan(); mWxBtManager.isEnable = false; ... }`，用户单击开关立即生效。项目自定义开关控件 `SkinSwitchCardView` 本身就为"危险操作二次确认"准备了覆盖层机制：`enableOverlay()` 在开关上方盖一层可点击遮罩，点击不改变 checked 状态而是回调 `setOnOverlayClickListener`（见控件注释"设置enableOverlay(true)开启覆盖view，并设置setOnOverlayClickListener(listener)监听覆盖view的点击事件"）。修复重构该监听：开（`isChecked==true`）走原使能流程并 `enableOverlay()` 拦截后续点击；关（`else` 分支）只 `disableOverlay()` 恢复直点（关状态下再开无需确认）；真正的关断逻辑移入覆盖层点击回调弹出的 `showTipDialog` 的 `onConfirm` 中执行；并补充初始化段按当前 `sw.isChecked` 同步覆盖层状态，避免页面重建后拦截失效。新增双语文案 `close_bluetooth_dialog_title`("关闭蓝牙")/`close_bluetooth_dialog_content`("关闭蓝牙后，胎压监测显示将无法使用，是否继续关闭?")。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt；application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/res/values-en/strings.xml
```diff
--- application/Setting/.../diologfragment/BluetoothFragment.kt
         mBindingHeader.sw.setOnCheckedChangeListener { isChecked ->
-            mBindingHeader.sw.enableOverlay()
-            lifecycleScope.launch(ioDispatcher) {
-                if (isChecked) {
-                    mWxBtManager.isEnable = true
-                } else {
-                    mWxBtManager.stopScan()
-                    mWxBtManager.isEnable = false
-                    mAdapter.setDevicesSize(0, 0)
-                    withContext(mainDispatcher) { handleBluetoothData(true) }
-                }
+            if (isChecked) {
+                mBindingHeader.sw.enableOverlay()
+                lifecycleScope.launch(ioDispatcher) {
+                    mWxBtManager.isEnable = true
                 }
+            } else {
+                mBindingHeader.sw.disableOverlay()
             }
-        }
+        }
+        // 关闭二次确认
+        mBindingHeader.sw.setOnOverlayClickListener {
+            showTipDialog(
+                title = getString(R.string.close_bluetooth_dialog_title),
+                content = getString(R.string.close_bluetooth_dialog_content),
+                ...
+                onConfirm = {
+                    lifecycleScope.launch(ioDispatcher) {
+                        mWxBtManager.stopScan()
+                        mWxBtManager.isEnable = false
+                        mAdapter.setDevicesSize(0, 0)
+                        withContext(mainDispatcher) { handleBluetoothData(true) }
+                    }
+                }
+            )
+        }
+        if (mBindingHeader.sw.isChecked) { mBindingHeader.sw.enableOverlay() }
+        else { mBindingHeader.sw.disableOverlay() }
```

## 为什么能修复
蓝牙开启态下覆盖层拦截物理点击，"关"动作必须经确认弹窗的 `onConfirm` 才触达 `mWxBtManager.isEnable = false`，误触关断被消除；关态直点即开，不增加多余步骤。隐患：若弹窗期间页面销毁，协程回调的生命周期安全依赖 `lifecycleScope` 已有机制；另外初始化同步若在其他入口改了开关状态而不刷新覆盖层，可能出现拦截状态与实际状态不同步。

## 复盘与经验
- 项目控件自带的 overlay/confirm 机制（`SkinSwitchCardView.enableOverlay + setOnOverlayClickListener`）是关闭类危险操作的统一解法，新需求应优先套用而不是在 `onCheckedChange` 里塞弹窗。
- 带二次确认的开关要区分两个方向：关需要确认、开保持直达，并且页面初始化时必须同步覆盖层状态，否则"确认"只在部分生命周期内生效。
- 关联影响（关蓝牙→胎压监测不可用）写进确认文案，能让用户在决策点获得完整信息，是车机安全类确认弹窗的规范做法。
