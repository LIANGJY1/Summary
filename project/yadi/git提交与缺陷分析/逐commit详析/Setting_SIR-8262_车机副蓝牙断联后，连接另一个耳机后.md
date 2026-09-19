# SIR-8262 · 副蓝牙断联后旧耳机仍显示"前排"字样
- **提交**：`5065f59a` | 2026-09-14 | dufan | Setting | bugfix
- **缺陷库**：等级 D · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
车机副蓝牙耳机断联后，再连接另一只耳机时，列表里第一只（已断联的）耳机仍然显示"前排"座位标识，状态与实际不符。

## 根因分析
`application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt` 的列表渲染方法中，原逻辑对 `role == 0 || role == 1`（后排/前排）的设备**无条件**执行 `holder.setGone(R.id.tv_seat, false).setText(...)`，把座位标签设为可见并写死"后排/前排"文案。该逻辑放在 `if (isConnect)` 判断之外，意味着只要设备的角色字段是 0/1，无论当前是否处于连接态，`tv_seat` 都会被置为可见。设备断联后走刷新流程时，adapter 依旧按旧数据把"前排"标签画出来，造成"断联仍显示前排字样"的假象——本质是把"设备具备前排/后排能力"与"设备当前已连接"两个条件混为一谈。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
@@ -111,17 +111,12 @@
         holder.setGone(R.id.iv_arrow, false)
-        if (role == 0 || role == 1) {
-            holder.setGone(R.id.tv_seat, false)
-                .setText(
-                    R.id.tv_seat,
-                    if (role == 0) R.string.back_row else R.string.front_row
-                )
-        }
         if (mIsConnectTwoHelmet && (role == 0 || role == 1)) {
             holder.setGone(R.id.iv_switch, false)
         }
         if (isConnect) {
+            holder.setGone(R.id.tv_seat, false)
+                .setText(R.id.tv_seat, if (role == 0) R.string.back_row else R.string.front_row)
             if (major) {
                 holder.setImageResource(R.id.iv, R.drawable.ic_headphones_connected)
             } else {
```

## 为什么能修复
把 `tv_seat` 的置可见与文案设置整体移入 `if (isConnect)` 分支后，断联设备不再进入该分支，`tv_seat` 保持 XML 默认的隐藏状态，"前排/后排"字样只随连接态出现。改动纯视图层收敛，不影响 `iv_switch`、连接图标等其他元素；隐患是若产品后续要求"断联也保留座位标识"需另加条件，但当前与需求一致。

## 复盘与经验
- 状态型 UI 标签的显示条件要与其语义绑定的状态（这里是"已连接"）对齐，不要仅按静态属性（角色）渲染，否则状态回退时旧文案残留。
- Adapter 中"设置可见 + 设置文案"应尽量放进同一个状态分支，避免跨分支的半更新。
- 复现路径提示：断联→连新设备，这类"旧 item 未刷新"问题优先检查 item 渲染是否依赖了与当前状态无关的旧条件。
