# SIR-2185 · 已配对列表中未连接设备的手机图标不应高亮
- **提交**：`af0e73ac` | 2026-07-09 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
已可配对设备列表中，已配对但未连接的设备，其手机图标仍显示高亮（蓝色）样式，与已连接设备无法区分。

## 根因分析
`BluetoothAdapter`（继承 `BaseMultiItemQuickAdapter`）绑定设备条目时，在 `isBonded || (phoneCarConnectionType in 1..3)` 分支里，`R.id.iv`（手机图标）被无条件 `setImageResource(R.drawable.ic_blue_phone)` —— 即只要设备是已配对，不管 `isConnected` 与否，图标一律用高亮的 `ic_blue_phone`；连接状态只被用来切换旁边的蓝牙小图标 `iv_bt`（`ic_connect_bt` / `ic_connect_un_bt`）。根因正如缺陷库记录："手机 icon 未做连接状态区分"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAdapter.kt（+14/-6）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAdapter.kt
@@ 已配对设备条目绑定
                     if (isBonded || (phoneCarConnectionType in 1..3)) {
                         holder.setGone(R.id.iv_third, false).setGone(R.id.iv_bt, false)
                             .setGone(R.id.iv_arrow, false)
-                            .setImageResource(R.id.iv, R.drawable.ic_blue_phone)
                         if (isConnected) {
-                            holder.setImageResource(R.id.iv_bt, R.drawable.ic_connect_bt)
+                            holder.setImageResource(R.id.iv, R.drawable.ic_blue_phone)
+                                .setImageResource(R.id.iv_bt, R.drawable.ic_connect_bt)
                         } else {
-                            holder.setImageResource(R.id.iv_bt, R.drawable.ic_connect_un_bt)
+                            holder.setImageResource(R.id.iv, if (isPhoneCarConnect) R.drawable.ic_blue_phone else R.drawable.ic_bluetooth_phone)
+                                .setImageResource(R.id.iv_bt, R.drawable.ic_connect_un_bt)
                         }
```

## 为什么能修复
把 `R.id.iv` 的高亮图标从"分支前置无条件设置"移入 `isConnected` 条件内：已连接才显示高亮 `ic_blue_phone`；未连接时按 `isPhoneCarConnect` 区分，非手机车机互联场景使用普通态 `ic_bluetooth_phone`。修复思路与缺陷库 sol"手机 icon 做连接状态区分"一致。风险：`phoneCarConnectionType 1..3` 的 when 分支仍会在后续覆盖 `iv_bt`，若某类型分支漏设 `iv`，需确认默认态已被上面的 if/else 正确落定（本 diff 内未覆盖类型分支对 `iv` 的改动，行为依赖后续 when 不再触碰 `iv`）。

## 复盘与经验
- **图标状态机要完整覆盖"连接×配对"组合**：只给蓝牙小图标区分状态、主图标写死，是最常见的半截状态机；应把每个图标的每个状态列成矩阵逐一核对。
- **警惕"先无条件设默认值再覆盖"的绑定写法**：`.setGone(...).setImageResource(...)` 链式调用中夹带无条件设置，容易被后续维护者当成"已处理"。
- **ViewHolder 复用放大遗漏**：RecyclerView 复用下，任何"漏复位"的视图都会串行显示别的条目的状态，绑定必须全量覆盖。
