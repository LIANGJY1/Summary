# SIR-6410 · 副蓝牙首次配对连接耳机，直接显示"后排"

- **提交**：`02cc6393` | 2026-08-28 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙首次配对连接蓝牙耳机时，设备在列表中直接被显示为"后排"，未按前后排分配逻辑处理。

## 根因分析
`BluetoothAnwFragment.handleDeviceRoleUpdate(device, address, isHasFront, isHasBack)` 负责给新连接设备分配角色（前排 role=1 / 后排 role=0）：先遍历 `BtAnwManager.mPairedDevices` 检查该 MAC 是否已在已配对列表，命中就执行**裸 `return`** 整体退出函数，后面"前排空闲给前排、否则给后排"的赋值逻辑（`it.role = 1` / `it.role = 0` + `BtAnwManager.setRole`）永远走不到。首次配对的设备在回调到达时往往已登记进 `mPairedDevices`，于是命中即返回，role 未设置，列表按缺省渲染成"后排"。提交信息概括为"未设置 role → 设置 role"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
         device?.let {
             BtAnwManager.getInstance().mPairedDevices.forEach {
                 if (it.getMacAddress() == address) {
-                    return
+                    if (isHasFront && isHasBack)return
+                    return@forEach
                 }
             }
             if (!isHasFront) {
                 it.role = 1
                 BtAnwManager.getInstance().setRole(it.macAddress, 1)
             } else if (!isHasBack) {
                 it.role = 0
                 BtAnwManager.getInstance().setRole(it.macAddress, 0)
             }
```

## 为什么能修复
原逻辑把"设备已在配对列表"误当成"角色已分配完毕"。修改后：仅当前后排角色**都已占用**（`isHasFront && isHasBack`）才整体返回（无需分配）；否则 `return@forEach` 只跳过本条匹配继续走下方赋值，把唯一空闲的角色（前排优先）通过 `setRole` 写入设备，首次配对的耳机不再因 role 缺省被渲染成"后排"。注意风险：`forEach` 命中相同 MAC 但仅做跳过，若列表中存在重复 MAC 条目会重复赋值，幂等所以无害。

## 复盘经验
- Kotlin 循环里的裸 `return` 退出的是整个函数而非循环，`return`/`return@forEach` 一字之差语义天壤之别；循环内提前返回要显式确认退出范围。
- "已存在于集合"不等于"已完成初始化"，存在性检查与状态完备性检查要分开。
- 设备角色的缺省渲染值（-1 → 显示"后排"）本身就是个坑，缺省态应有显式的"未分配"展示或立即分配兜底。
