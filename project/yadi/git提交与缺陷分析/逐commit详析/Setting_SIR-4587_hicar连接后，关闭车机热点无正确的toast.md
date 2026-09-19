# SIR-4587 · HiCar 连接后关闭车机热点无二次确认、无提示

- **提交**：`42b77456` | 2026-07-29 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
HiCar 已通过热点连接车机时，用户直接关闭热点开关会立刻断链，既没有二次确认弹窗，也没有任何 toast 提示，HiCar 通话随之中断。

## 根因分析
`HotspotDialogFragment` 的热点开关回调里，原逻辑对 `mWxApManagerI.isAPOn` 的情况一律直接 `closeAp()`，完全没有感知"当前是否有手机互联（HiCar）正依赖该热点"。`DeviceConnectManager.getCurrentConnectType()` 已能返回连接类型（2 即 HiCar 类互联），但该处从未判断。属功能缺失而非逻辑错误——缺陷库"未添加对应逻辑"即此。另外要处理好 Switch 控件的回弹问题：用户点关后系统态未变，需要把 UI 拨回开启态且不能再次触发监听回调造成循环。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt；application/Setting/src/main/res/values/strings.xml；application/Setting/src/main/res/values-en/strings.xml

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
         mBindingHeader.switchHotspot.setOnCheckedChangeListener {
+            if (mIsCancel) return@setOnCheckedChangeListener
             mBindingHeader.switchHotspot.enableOverlay()
             if (mWxApManagerI.isAPOn) {
-                mWxApManagerI.closeAp()
+                if (DeviceConnectManager.getInstance().getCurrentConnectType() == 2) {
+                    TextDialog("", ResourceUtils.getString(R.string.hot_point_close_hint),
+                        ResourceUtils.getString(R.string.confirm),
+                        ResourceUtils.getString(R.string.cancel))
+                        .setCallback(object : Callback {
+                            override fun confirm(content: Any?) {
+                                mWxApManagerI.closeAp()
+                            }
+                            override fun cancel() {
+                                resetCancel()
+                            }
+                        }).show(childFragmentManager, "CloseHotHintDialog")
+                } else {
+                    mWxApManagerI.closeAp()
+                }
             } else { ... }
@@
+    private fun resetCancel(){
+        lifecycleScope.launch{
+            mIsCancel = true
+            mBindingHeader.switchHotspot.disableOverlay()
+            mBindingHeader.switchHotspot.isChecked = true
+            delay(100.milliseconds)
+            mIsCancel = false
+        }
+    }
```

新增文案 `hot_point_close_hint`："关闭蓝牙连接会影响HUAWEIHiCar通话,是否确定\n关闭蓝牙?"（values 与 values-en 双语）。

## 为什么能修复
关闭动作前先查 `getCurrentConnectType() == 2`（HiCar 连接中），命中则弹 `TextDialog` 二次确认：确认才 `closeAp()`，取消则 `resetCancel()` 把开关拨回 checked=true；`mIsCancel` 标志 + `disableOverlay`/100ms 后复位，吞掉程序性 `setChecked(true)` 触发的监听回调，避免"取消后再次弹窗"的回环。非 HiCar 场景保持原直关行为，零回归。隐患：确认弹窗期间热点真实状态未锁定，理论上存在竞态（极小）；魔法值 `2`（连接类型）建议语义化。

## 复盘与经验
- **破坏性操作的防护要与"依赖方"联动**：热点开关不知道 HiCar 在用它，是典型的资源归属不清；关闭前置检查依赖方状态（connectType）是最低成本的防护栏。
- **程序性 setChecked 必须防回调回环**：`mIsCancel` 标志 + 延时复位是 Switch/CheckBox 回弹 UI 的标准手法，漏掉就会"取消后弹窗又弹出"或死循环。
- **提示文案要说明后果**（"会影响HiCar通话"），而非只问"是否关闭"——用户才能做出正确决策；中英文资源同步新增，避免单语漏翻。
- **魔法值 `getCurrentConnectType() == 2` 应收敛为枚举/常量**，互联类型后续扩展（CarPlay/ICCOA）时这类散落的数字比较会成隐患。
