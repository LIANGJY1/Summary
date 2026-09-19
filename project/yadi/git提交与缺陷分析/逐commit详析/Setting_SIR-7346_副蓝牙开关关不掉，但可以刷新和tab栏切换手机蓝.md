# SIR-7346 · 副蓝牙开关关不掉（可刷新/切 tab）

- **提交**：`447b28a8` | 2026-09-11 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- 说明：同一单号后续还有补充提交 `933f7fb7`（2026-09-14）。

## 问题
设置弹窗内副蓝牙（手机蓝牙）开关点击关闭后开关始终弹回开启状态，表现为"关不掉"，但刷新和 tab 切换等其它功能正常。

## 根因分析
`BluetoothFragment.setupBluetoothSwitchListener` 中关闭流程是"点开关 → `setOnOverlayClickListener` 弹二次确认 → 确认后在 `ioDispatcher` 协程里执行 `mWxBtManager.stopScan()`、`mWxBtManager.isEnable = false` 等异步操作"。开关 UI 状态完全依赖底层状态回传广播来翻转，而确认关闭后并未主动把 `sw.isChecked` 置为 false；一旦底层广播丢失或迟迟不来（缺陷库根因"未收到底层广播"），开关视觉上永远停在开启态，用户看到的就是"关不掉"。另外 `setOnOverlayClickListener` 未判断当前开关状态，关闭态下点击覆盖层也会弹关闭确认框，属于多余交互。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`、`component/CommonTools/src/main/java/com/yadea/common/widgets/SkinSwitchCardView.java`
```diff
// BluetoothFragment.kt
         mBindingHeader.sw.setOnOverlayClickListener {
+            if (!mBindingHeader.sw.isChecked) return@setOnOverlayClickListener
             showTipDialog(
                 ...
                 onConfirm = {
                     lifecycleScope.launch(ioDispatcher) {
+                        withContext(mainDispatcher){
+                            mBindingHeader.sw.isChecked = false
+                            mBindingHeader.sw.enableOverlay(true)
+                        }
                         mWxBtManager.stopScan()
                         mWxBtManager.isEnable = false
```
```diff
// SkinSwitchCardView.java
+    /**
+     * 启用覆盖View（显示并启用点击）
+     */
+    public void enableOverlay(boolean isSetAlpha) {
+        enableOverlay();
+        if (switchCompat != null && isSetAlpha) {
+            switchCompat.setAlpha(0.5f);
+        }
+    }
```

## 为什么能修复
采用"乐观更新 + 置灰等待"模式：确认关闭时先在主线程把 `sw.isChecked = false`，UI 立即反映关闭结果，不再依赖底层广播；同时 `enableOverlay(true)` 弹出覆盖层并把开关 alpha 降为 0.5 置灰，期间拦截点击，待真实状态广播回来再取消置灰恢复可点，状态不一致的窗口期被覆盖层保护。`!isChecked` 早退则消除了关闭态误触确认框的问题。隐患：若底层关闭失败，需要靠后续广播把开关翻回开启态，否则出现 UI 与实际状态短暂不符——这正是"待收到开关后取消置灰"策略的约定。

## 复盘与经验
- 开关类 UI 依赖异步回执时，"先改 UI + 置灰禁点，回执到达再恢复"是标准解法，比让用户反复点击体验好得多。
- 异步协程里改 UI 必须先 `withContext(mainDispatcher)` 切回主线程，本例把 UI 置位放在 IO 协程开头一次性完成。
- 对覆盖层（overlay）点击回调要校验当前开关状态，避免关闭态再弹"关闭确认"这类逻辑漏洞。
- 通用控件 `SkinSwitchCardView` 增加重载而非改原方法，向后兼容性好，值得沿用。
