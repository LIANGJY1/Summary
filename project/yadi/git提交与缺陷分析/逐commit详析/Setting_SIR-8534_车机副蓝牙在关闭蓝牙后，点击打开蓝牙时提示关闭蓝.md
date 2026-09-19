# SIR-8534 · 副蓝牙关闭后点击打开蓝牙却弹出"关闭蓝牙"确认框

- **提交**：`9d5532a4` | 2026-09-15 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
车机副蓝牙处于关闭状态时，点击开关想打开蓝牙，却弹出"关闭蓝牙"的二次确认提示，方向完全相反。

## 根因分析
`BluetoothAnwFragment` 的总开关使用 `setOnOverlayClickListener` 做二次确认弹窗。问题在于监听器不区分目标状态：`showTipDialog(title=R.string.close_bluetooth_dialog_title, content=close_bluetooth_dialog_content)` 固定弹"关闭蓝牙"文案，且原逻辑对"开→关"与"关→开"都执行弹窗路径。当开关处于未选中（`isChecked == false`，蓝牙已关）状态被点击时，目标动作其实是"打开"，根本不需要确认，更不该弹"关闭"文案。修复只有一行：目标状态为开（当前未勾选）时直接 `return`，跳过关闭确认弹窗。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@         // 弹出二次确认
         mBindingHeader.sw.setOnOverlayClickListener {
+            if (!mBindingHeader.sw.isChecked) return@setOnOverlayClickListener
             showTipDialog(
                 title = getString(R.string.close_bluetooth_dialog_title),
                 content = getString(R.string.close_bluetooth_dialog_content),
```

## 为什么能修复
`sw.isChecked` 在点击时刻仍代表"当前已关"（未切换），`!isChecked` 即"用户想开蓝牙"，直接返回后开关走默认切换流程不再弹"关闭确认"；只有"开→关"路径才弹确认。改动最小且不影响关闭路径的既有确认交互。隐患：若产品后续要求"打开蓝牙也二次确认"（如涉及隐私提示），此逻辑需重新设计为按目标状态区分文案。

## 复盘与经验
- 二次确认弹窗必须绑定"目标状态"而非固定文案；开关类 overlay 点击回调要先问 `isChecked`（当前态）再决定行为。
- 单行 guard 提前返回是修复"方向相反提示"类 bug 的最经济手段，review 时重点看开关监听器是否区分了开/关两个方向。
