# SIR-2082 · 副蓝牙可配对列表多次点击设备连接无响应

- **提交**：`910c729b` | 2026-07-08 | daizhecheng | Setting | bugfix（防御性加固，缺陷库标注"多版本未复现，暂关闭"）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设 · 根因"偶现问题，多版本未复现，暂关闭"，方案"/"

## 问题
副蓝牙可配对设备列表中多次主动点击同一设备发起连接时，偶发无响应。缺陷库最终未能复现，按偶现问题关闭；本提交为配套防御性修复。

## 根因分析
无法从 diff 反推确定根因（缺陷库亦承认未复现）。可确定的薄弱点：列表条目点击（`setOnItemClickListener`）与条目内控件点击（`setOnItemChildClickListener`）均**无防抖**，连续快速点击会在连接尚未建立时重复下发连接/配对指令，底层 AIDL 调用（`BtAdapter.executeAidl`）被高频触发后状态紊乱，表现为"点了没反应"。本提交以"点击节流 + 全链路日志"作防御。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/adapter/DebounceOnItemClickListener.kt`（新增 44 行）、`.../diologfragment/BluetoothAnwFragment.kt`（两处监听器包防抖）、`.../activity/PairDialogActivity.kt`、`values/ids.xml`、`component/Hardwarelibs/.../bt/BtAdapter.java`（日志增强）
```kotlin
// --- application/Setting/src/main/java/com/yadea/setting/ui/adapter/DebounceOnItemClickListener.kt（新增）
class DebounceOnItemClickListener(
    private val interval: Long = 500L,
    private val listener: OnItemClickListener?
) : OnItemClickListener {
    override fun onItemClick(adapter: BaseQuickAdapter<*, *>, view: View, position: Int) {
        val now = System.currentTimeMillis()
        val lastTime = view.getTag(R.id.tag_debounce_time) as? Long ?: 0L
        if (now - lastTime < interval) return
        view.setTag(R.id.tag_debounce_time, now)
        listener?.onItemClick(adapter, view, position)
    }
}
// DebounceOnItemChildClickListener 同构，作用于条目子控件
```
接入方式：`BluetoothAnwFragment` 中 `mAdapter.setOnItemClickListener(DebounceOnItemClickListener(1000) { ... })`、`setOnItemChildClickListener(DebounceOnItemChildClickListener(1000) { ... })`，防抖时间戳存于 `view.setTag(R.id.tag_debounce_time)`。

## 为什么能修复
1 秒内重复点击被节流丢弃，连接指令不再被连发轰炸，消除了"多次点击→指令堆积→底层状态紊乱→无响应"的一条可能路径；同时 BtAdapter 各入口补了 DBG 日志、executeAidl 阻塞时记录 powerStatus，若问题复现可凭日志定位。注意缺陷本质未确证，此为"降低触发概率+增强可观测性"的防御闭环，而非根因修复；时间戳存在 view tag 上，视图回收复用时 tag 会带到新条目，理论上节流窗口略有偏移，影响有限。

## 复盘与经验
- **列表点击默认都要防抖**：车机上用户"点了没反应→狂点"是常见行为，无节流的点击监听迟早出事；封装通用 Debounce 监听器一次投入全局复用。
- **未复现问题关闭时最好留下观测钩子**：本提交"关闭单 + 加日志 + 加防抖"的组合是处理偶现问题的务实模板。
- executeAidl 双层嵌套（c94193c4 引入）在本提交仅被格式化而未消除，说明顺带重构缺乏 review 把关，值得专项清理。
