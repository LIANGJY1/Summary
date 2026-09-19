# SIR-7891 · 耳机蓝牙连接界面残留头盔式样图标（SRS已删除头盔）

- **提交**：`5c4bfb81` | 2026-09-17 | dufan | Setting | bugfix/需求变更落地
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙（耳机/头盔）连接界面中，耳机设备仍会显示头盔式样图标；最新 SRS 已删除头盔图标显示，界面需统一为耳机图标。

## 根因分析
`BluetoothAnwAdapter` 的 item 绑定逻辑还在按设备类别区分头盔/耳机：从 `cod`（Class of Device）取 `Major.BITMASK` 得到 majorCode，经 `BtAnwManager.isBluetoothHeadsetOrHelmet(majorCode)` 判断，再在 `bindBondedDeviceItem` / `bindUnbondedDeviceItem` 里按判断结果分别设置 `ic_headphones*` 或 `ic_helmet*` 系列图标。SRS 变更后头盔分支整体废弃，但代码未同步清理，只要 `isBluetoothHeadsetOrHelmet` 对某设备返回 false，就渲染出头盔图标，与最新需求不符。修复是彻底删除头盔分支：绑定方法去掉 `major` 参数，图标一律 `ic_headphones` / `ic_headphones_connected`。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
@@ item 绑定
-            val majorCode = (cod) and Constant.Major.BITMASK
-            val major = BtAnwManager.isBluetoothHeadsetOrHelmet(majorCode)
             if (isBonded) {
-                bindBondedDeviceItem(holder, major, role, isConnect)
+                bindBondedDeviceItem(holder, role, isConnect)
             } else {
-                bindUnbondedDeviceItem(holder, major)
+                bindUnbondedDeviceItem(holder)
             }
@@ 已连接/未连接图标统一为耳机
-            if (major) {
-                holder.setImageResource(R.id.iv, R.drawable.ic_headphones_connected)
-            } else {
-                holder.setImageResource(R.id.iv, R.drawable.ic_helmet_connected)
-            }
+            holder.setImageResource(R.id.iv, R.drawable.ic_headphones_connected)
@@ 未配对项
-    private fun bindUnbondedDeviceItem(holder: BaseViewHolder, major: Boolean) {
-        if (major) {
-            holder.setImageResource(R.id.iv, R.drawable.ic_headphones)
-        } else {
-            holder.setImageResource(R.id.iv, R.drawable.ic_helmet)
-        }
+    private fun bindUnbondedDeviceItem(holder: BaseViewHolder) {
+        holder.setImageResource(R.id.iv, R.drawable.ic_headphones)
```

## 为什么能修复
按 COD 分类的整条链路（位运算取 major、头盔判断、双图标分支）被连根删除，任何设备都渲染耳机图标，与 SRS 对齐；`mIsConnectTwoHelmet` 的前后排角色逻辑保留未动，双头盔连接时的座位标识不受影响。风险：若未来恢复头盔配件，需重新引入分类逻辑；`BtAnwManager.isBluetoothHeadsetOrHelmet` 可能因此成为死代码，应跟进清理。

## 复盘与经验
- SRS 删除某类设备/样式时，要同步清理代码中的分类分支与判断工具方法，否则残留分支会在真实设备上被触发（本单即"代码滞后于需求"）。
- 图标选择收敛到单一资源后，`ic_helmet*` 系列 drawable 成了孤儿资源，可通过 lint 的 unused resource 检查批量发现此类需求残留。
