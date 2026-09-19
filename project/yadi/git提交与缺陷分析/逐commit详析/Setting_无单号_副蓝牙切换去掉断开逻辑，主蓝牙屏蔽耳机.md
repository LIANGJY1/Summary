# 无单号 · [SIR-XXX] 副蓝牙切换去掉断开逻辑，主蓝牙屏蔽耳机
- **提交**：`8ebecdbc` | 2026-09-11 | dufan | Setting | feature
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
双蓝牙（前后排两路）场景的策略调整：①前后排设备切换时不再主动断开/回连另一路设备（交给协议栈自行处理，避免切换过程被人为打断）；②主蓝牙设备列表过滤掉名字带 `_Headset` 后缀的耳机设备，只展示手机。

## 实现结构
- `base/BaseContainerFragment.kt`（+29/-10）：Tab 长按 tooltip 压制的重做——原有"attach 后统一清一遍"方案被 Material 内部 `TabView.updateTab()` 在选中时重写 tooltip 击穿，改为注册 `OnTabSelectedListener` 在 selected/unselected/reselected 三个时机反复清除。
- `ui/fragment/diologfragment/BluetoothAnwFragment.kt`（+8/-8，实为注释+改时延）：副蓝牙（ANW 前后座）切换协程中注释掉 `disconnectDevice(otherDevice)` 与切换后对另一路设备的 `connectHfp/connectA2dp` 回连，等待延时从 2000ms 缩到 1000ms。
- `ui/fragment/diologfragment/BluetoothFragment.kt`（+3/-1）：`onDeviceAdded` 里 `CELL_PHONE` 类型判断追加 `!it.name.contains(HEADSET_NAME_SUFFIX)` 条件（常量已在 `BluetoothUtil` 中定义，值为 `"_Headset"`）。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/base/BaseContainerFragment.kt
// Material 在 Tab 被选中时会经 TabView.updateTab() 重新写入 tooltip（取 contentDescription），
// 覆盖 onConfigureTab 里的一次性清除，导致"已选中的 tab 长按仍弹提示"。
// 因此在每次(取消)选中后都再清一次——onTabSelected 在 updateTab 之后触发，能稳稳压住。
mBinding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
    override fun onTabSelected(tab: TabLayout.Tab?) {
        tab?.view?.let { ViewCompat.setTooltipText(it, null) }
    }
    override fun onTabUnselected(tab: TabLayout.Tab?) {
        tab?.view?.let { ViewCompat.setTooltipText(it, null) }
    }
    override fun onTabReselected(tab: TabLayout.Tab?) {
        tab?.view?.let { ViewCompat.setTooltipText(it, null) }
    }
})
```

```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt（切换协程）
-   disconnectDevice(otherDevice)
+//  disconnectDevice(otherDevice)
    executeRoleSwitch(otherDevice, clickedDevice)
-   delay(2000.milliseconds)
+   delay(1000.milliseconds)
    ...
-   BtAnwManager.getInstance().connectHfp(it, true)
-   BtAnwManager.getInstance().connectA2dp(it, true)
+//  BtAnwManager.getInstance().connectHfp(it, true)
+//  BtAnwManager.getInstance().connectA2dp(it, true)
```

实现讲解：切换逻辑从"应用层主导连接编排（断开其他→切换→延时→手动回连）"收缩为"只触发角色切换，连接状态交给蓝牙协议栈"，删除两处主动干预并把固定延时减半，切换路径明显变短。tooltip 问题则是对第三方库内部行为的补丁式对抗：既然 Material 每次选中都重写，就在它重写之后的回调里再清一次，时序上稳压。耳机过滤用名字后缀 `_Headset` 作为识别特征，属于数据侧约定。

## 复盘与要点
- **少即是多的蓝牙编排**：车机双蓝牙切换反复出问题的常见根源是应用层过度干预（手动断开+手动回连+固定延时），本提交反向收敛、信任协议栈，是值得记住的方向性取舍。遗留：注释而非删除代码、magic 延时 1000ms 仍在，回连行为完全依赖栈的自动策略，极端场景（栈不自动回连）无兜底。
- **对抗第三方库的"再清一次"模式**：当库在某事件后必然重写某属性时，把清理挂到该事件之后触发的回调里，比只清一次可靠；代价是依赖 Material 内部实现顺序（updateTab 先于 onTabSelected），库升级需回归验证。
- **按名字后缀过滤设备是脆弱约定**：`_Headset` 依赖手机端/外设命名规范，改名即失效；更稳的是按设备类型/UUID 过滤。注释掉的代码建议尽快删除，靠版本库留历史即可。
