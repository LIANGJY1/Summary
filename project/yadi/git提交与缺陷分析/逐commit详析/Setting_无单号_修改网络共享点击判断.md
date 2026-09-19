# 无单号 · 修改网络共享点击判断

- **提交**：`67e5edd5` | 2026-07-08 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
调整蓝牙设置页中"网络共享"开关的点击来源判断（`SIsFromUserClick`）的初始/重置值，使页面初始化与视图销毁后的首次开关事件按"用户点击"处理。

## 实现结构
只改动 `application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt`（2 处、各 1 行）：`initView()` 与 `onDestroyView()` 中把静态标志 `SIsFromUserClick` 的赋值由 `false` 改为 `true`。

数据流：`BluetoothUtil.SIsFromUserClick` 是伴随对象里的全局静态位，用于区分 `swSharing.setOnCheckedChangeListener` 收到的回调是"程序化状态同步"还是"真实用户点击"——为 `false` 时首个回调被吞掉一次（仅置回 `true`），为 `true` 时按用户点击走弹窗/关共享流程。此前 `initView`/`onDestroyView` 都把它归零，导致进入页面或页面重建后的第一次回调会被吞掉，真实点击可能被误拦。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -69,7 +69,7 @@ class BluetoothFragment : BaseFragment<DialogBluetoothChildBinding, BaseViewMode
     override fun initView() {
-        SIsFromUserClick = false
+        SIsFromUserClick = true
         SIsOperationCarPlay = false

@@ -428,7 +428,7 @@
     override fun onDestroyView() {
-        SIsFromUserClick = false
+        SIsFromUserClick = true
         SIsOperationCarPlay = false
```
配套逻辑（该版本现状，便于理解标志位作用）：
```kotlin
// BluetoothFragment.kt（swSharing 监听器内）
if (!SIsFromUserClick) {
    SIsFromUserClick = true
    return@setOnCheckedChangeListener
}
```
实现讲解：开关状态会被车端/远端同步程序化刷写，作者用静态布尔做"一次性事件闸门"来吞掉程序化回调。本提交把生命周期边界的闸门初始态从"吞一次"改为"放行"，保证进页/重建后的首次交互不被误吞。

## 复盘与要点
- 用单个静态布尔同时承担"跨页面状态"与"单次事件闸门"两个职责，语义脆弱：任何一端的生命周期赋值都会影响其他页面的回调行为，改动一行就足以反转交互表现——这正是本次 2 行 diff 即可改变行为的根因。
- 可复用手法的反面教材：区分"程序化刷新 vs 用户操作"更稳妥的做法是给 `setChecked` 增加带 `fromUser` 参数的封装，而不是依赖全局静态标志的时序约定。
- 遗留风险：`initView` 与 `onDestroyView` 赋同一值后，静态位在整条链路上几乎恒为 `true`（仅 458 行某路径还会置回 `false`），"吞程序化回调"的保护实际近乎失效，若车端频繁同步开关状态，可能弹出误触的共享确认弹窗。
