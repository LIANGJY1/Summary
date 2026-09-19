# SIR-8679 · 反复点击蓝牙开关后显示关闭状态，再也无法点击开启

- **提交**：`877a3863` | 2026-09-18 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 解决方案 · 域 车控车设

## 问题
在设置-蓝牙页反复操作主蓝牙开关后，开关停在关闭状态，此时再点击开关没有任何响应，蓝牙无法再打开。

## 根因分析
`SkinSwitchCardView` 的覆盖层机制是：`enableOverlay()` 显示覆盖 View、把 `switchCompat.setEnabled(false)`，后续点击全部转给 `setOnOverlayClickListener`；只有 `disableOverlay()` 才会恢复可点。旧代码在"关闭蓝牙二次确认"成功后执行 `mBindingHeader.sw.isChecked = false; enableOverlay(true)`——开关已置 OFF，但覆盖层仍常驻、开关仍 disabled、alpha 停在 0.5，且此路径之后无人调用 `disableOverlay()`。此时点击只会进 overlay 监听，而旧守卫 `if (!mBindingHeader.sw.isChecked) return` 对"已关闭"直接吞掉，开关本体又是禁用的——形成"显示关闭、点击无响应"的死锁。这正是缺陷库 `[why]状态更新错误`：UI 状态（覆盖层开/关、透明度）与蓝牙真实状态错位后没有任何自愈路径。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、.../diologfragment/BluetoothAnwFragment.kt、component/CommonTools/src/main/java/com/yadea/common/widgets/SkinSwitchCardView.java（共 +8/-8）
```diff
--- component/CommonTools/src/main/java/com/yadea/common/widgets/SkinSwitchCardView.java
@@ API 语义显式化
-    public void enableOverlay(boolean isSetAlpha) {
+    public void enableOverlay(float alpha) {
         enableOverlay();
-        if (switchCompat != null && isSetAlpha) {
-            switchCompat.setAlpha(0.5f);
+        if (switchCompat != null) {
+            switchCompat.setAlpha(alpha);
         }
     }
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ 覆盖层点击守卫
         mBindingHeader.sw.setOnOverlayClickListener {
-            if (!mBindingHeader.sw.isChecked) return@setOnOverlayClickListener
+            if (!mBindingHeader.sw.isChecked || (mBindingHeader.sw.isChecked && mBindingHeader.sw.switchCompat.alpha != 1.0f)) return@setOnOverlayClickListener
@@ STATE_ON 恢复完整亮度
             ManagerConstants.STATE_ON -> {
                 mAdapter.addData(MultiBluetoothDevice(2, null))
-                mBindingHeader.sw.enableOverlay()
+                mBindingHeader.sw.enableOverlay(1.0f)
```
配套改动：开启/关闭两条路径统一 `enableOverlay(0.5f)`（过渡态置暗）；BluetoothAnwFragment 的关闭确认同步改为 `enableOverlay(0.5f)`。

## 为什么能修复
修复用 alpha 把开关做成三态清晰的状态机：0.5=过渡中（点击被守卫忽略）、1.0=已开启待操作（覆盖层点击弹关闭确认）、`disableOverlay()`=已关闭可点击。开启成功（STATE_ON）显式恢复 `enableOverlay(1.0f)`，过渡态的点击一律 return，直到状态落定；蓝牙 OFF 分支走 `disableOverlay()` 恢复可点，"关闭后锁死"的路径消失。隐患是把透明度当作逻辑状态位，属于约定式状态机——任何新代码路径漏设 alpha 都可能复发，更稳妥是独立布尔态。

## 复盘与经验
- "覆盖层 + 禁用控件"的防抖设计必须保证每条路径最终都回到可交互态，否则一次异常路径就是永久死锁；评审时重点问"这条路径谁来 disableOverlay？"。
- 当 UI 有异步过程（开蓝牙是异步的），控件应显式建模"过渡态"，用统一的视觉标记（本例 alpha 0.5）+ 点击守卫防止半途操作；`enableOverlay(boolean)` 这种把"遮罩"与"置暗"两个正交概念揉进一个布尔参数的 API，是状态错位的温床，重构成显式参数后各调用点语义自明。
- 状态类 bug 的复现口诀是"反复/打断操作"：每次 toggle 留下的残留状态（alpha、enabled、overlay 可见性）逐项对照，残值累积处就是死锁点。
