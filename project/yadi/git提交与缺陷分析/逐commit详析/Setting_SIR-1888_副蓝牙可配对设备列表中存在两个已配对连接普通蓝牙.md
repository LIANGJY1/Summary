# SIR-1888 · 副蓝牙设备列表缺"已配对"分组标题（"已可配对设备"字样缺失）

- **提交**：`66a199d8` | 2026-07-03 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙可配对设备列表中存在"两个已连接 + 一个仅配对"的普通蓝牙耳机时，界面上"已可配对设备"的分组字样缺失。

## 根因分析
`BluetoothAnwFragment.buildDeviceAdapterList()` 负责把已配对设备、分组标题、可配对设备拼成一个 `MultiBluetoothAnwDevice` 列表交给 `BluetoothAnwAdapter` 多布局渲染。该列表是"标题行 + 设备行"的分段结构，分组标题本身也是一个 list item（`MultiBluetoothAnwDeviceType.TITLE_MINE(0)` 对应 `item_connect_title` 布局）。原代码在 `mPairedDevices.isNotEmpty()` 分支里直接 for 循环添加设备 ITEM，**漏掉了先插入一条 `TITLE_MINE` 类型的"已配对"标题 item**，导致该分组的标题行不渲染，连带界面分组观感错乱（单据现象描述为"已可配对设备"字样缺失）。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt`（+1）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt buildDeviceAdapterList()
             if (BtAnwManager.getInstance().mPairedDevices.isNotEmpty()) {
+                list.add(MultiBluetoothAnwDevice(MultiBluetoothAnwDeviceType.TITLE_MINE.type))
                 for (bean in BtAnwManager.getInstance().mPairedDevices) {
                     list.add(
                         MultiBluetoothAnwDevice(
```

## 为什么能修复
列表恢复为 `[TITLE_MINE, ITEM...] + TITLE_DIVIDER + TITLE_AVAILABLE + ITEM...]` 的完整分段结构，"已配对"分组头正常渲染，后续"已可配对设备"标题与设备行各归其位。一行修复、无副作用；但说明该列表的组装没有"分组头与分组内容强制配对"的约束，靠人工保证成对插入，仍属易错写法。

## 复盘与经验
- **多类型列表的分组头也是数据**：用单一 RecyclerView/Adapter 拼分段列表时，标题 item 与内容 item 应一起构建（封装成"addSection(title, items)"之类的成对 API），避免某分支只插内容不插标题。
- **组合状态才暴露的 UI 缺陷**：单设备、纯扫描等常规用例测不出"两连接+一配对"这种组合，测试设计需覆盖列表元素的全组合。
- 缺陷现象（"已可配对设备"字样缺失）与根因（缺"已配对"标题 item）表面不一致，实际是缺失标题行导致整个分段渲染错位——复盘 UI 缺陷时要看列表数据结构而非只看最终缺的字。
