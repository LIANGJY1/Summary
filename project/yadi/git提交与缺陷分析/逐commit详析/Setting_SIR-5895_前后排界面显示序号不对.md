# SIR-5895 · 前后排（耳机）界面显示序号不对
- **提交**：`36890464` | 2026-08-17 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
系统设置的前后排（蓝牙耳机设备）界面里，"我的设备"列表项显示的序号与预期不符。

## 根因分析
`BluetoothAnwFragment` 构建设备列表时，直接按 `BtAnwManager.getInstance().mPairedDevices` 的原始顺序遍历加入"我的设备（TITLE_MINE）"分组。该集合由底层回调顺序填充（配对先后/事件到达顺序），并未按设备角色排序，而 UI 契约要求按 `role`（主耳机/副耳机角色，即前后排序号）排列展示，导致序号显示错乱。缺陷库归因"数据未排序"，与代码一致。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt`
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
             if (BtAnwManager.getInstance().mPairedDevices.isNotEmpty()) {
                 list.add(MultiBluetoothAnwDevice(MultiBluetoothAnwDeviceType.TITLE_MINE.type))
-                for (bean in BtAnwManager.getInstance().mPairedDevices) {
+                val sortedPairedDevices = BtAnwManager.getInstance().mPairedDevices
+                    .sortedByDescending { it.role }
+                for (bean in sortedPairedDevices) {
                     list.add(
                         MultiBluetoothAnwDevice(
                             MultiBluetoothAnwDeviceType.ITEM.type, bean
```
（同提交移除了未使用的 BaseQuickAdapter 相关 import。）

## 为什么能修复
渲染前用 `sortedByDescending { it.role }` 对配对设备排序，列表顺序不再依赖底层回调的到达顺序，序号按角色稳定呈现。属纯展示层排序，无副作用；注意 `role` 语义（升序/降序、主副耳机编号规则）依赖底层枚举定义，若底层调整需同步。

## 复盘与经验
- UI 列表顺序若与业务编号（序号/角色/优先级）相关，必须在渲染入口显式排序，绝不能信任底层集合的填充顺序——回调时序是天然的"低概率随机源"。
- 排序逻辑放在 Adapter 数据组装处（而非底层管理层），能同时保住底层原始顺序语义与 UI 展示契约。
- 偶现（10%~40%）类显示问题优先怀疑无序集合/异步时序，先固定排序再排查其他。
