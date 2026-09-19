# SIR-6295 · 打开蓝牙开关后可配对设备与"无可连接设备"提示重叠

- **提交**：`2bdec030` | 2026-08-24 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 蓝牙电话

## 问题
打开车机设置的蓝牙开关后，"可配对设备"列表里出现了设备号，但"当前无可连接设备"的空态提示仍然显示，两者叠在界面上。

## 根因分析
`BluetoothFragment`（`diologfragment` 包）的列表刷新逻辑只处理了"显示空态"的方向，没有"隐藏空态"的反向分支：`mPhoneAvailableDevices.isEmpty()` 为真且 `mPhonePairedDevices` 也为空时把 `mBinding.tvNoConnect` 设为 `View.VISIBLE`；但当扫描到可配对设备（`mPhoneAvailableDevices` 非空）后，没有任何代码再把 `tvNoConnect` 置回 `GONE`，空态提示就残留在设备列表上，形成重叠。缺陷库根因"未重置界面显示"，修复 how"判断是否隐藏空布局"直指这一点。原代码还有一个顺序瑕疵：先 `handleBluetoothData()` 刷新列表再判断空态，判断依据与刷新结果可能不同步，本次也调整为先判断再刷新。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt（+4/-2）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ 列表刷新
         if (!isStart) {
             if (mBindingHeader.sw.isChecked) {
                 if (mPhoneAvailableDevices.isEmpty()) {
-                    handleBluetoothData()
                     if (mPhonePairedDevices.isEmpty()) {
                         mBinding.tvNoConnect.visibility = View.VISIBLE
                     }
+                    handleBluetoothData()
+                } else {
+                    mBinding.tvNoConnect.visibility = View.GONE
                 }
             } else {
                 if (mPhonePairedDevices.isEmpty()) {
```

## 为什么能修复
补上互斥分支：有可配对设备时强制隐藏 `tvNoConnect`，空态提示与设备列表不再同时可见；先判空再 `handleBluetoothData()` 的顺序调整让空态判断基于刷新前的稳定快照，避免闪烁。隐患：`else` 分支只覆盖了"available 非空"这一种情况，若 available 为空但 paired 非空，`tvNoConnect` 的显隐依赖既有逻辑，嵌套条件增加后建议改为单一函数按"最终列表状态"统一决定空态显隐。

## 复盘与经验
- 空态提示（empty view）的显隐必须与数据列表状态单向绑定：最好收敛为一个 `updateEmptyState()`，由数据源唯一决定 VISIBLE/GONE，散落的 `visibility = VISIBLE` 一定会漏掉反向分支。
- "只显示不隐藏"是空态残留类 bug 的固定模式，review 时看到设置 VISIBLE 的分支就要问 GONE 在哪里。
- 顺带在日志中补充开关状态（`sw.isChecked`）便于排查，值得肯定。
