# SIR-5737 · 首次开启热点开关频繁弹起动效（开关不停开关）

- **提交**：`240349ac` | 2026-08-07 | dufan | Setting | bugfix（元数据标注 [feature]，实为缺陷修复；缺陷库 mistag=true）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：回调修改状态错误 / sol：修改回调逻辑）

## 问题
首次打开热点弹窗中的热点开关时，开关动画不停地在开启/关闭间反复弹起，无法稳定停在开启态。

## 根因分析
`HotspotDialogFragment` 的 `switchHotspot.setOnCheckedChangeListener` 回调里，分支判断用的是 `mWxApManagerI.isAPOn`（管理器缓存的**旧状态**）而不是回调参数 `isChecked`（开关**新状态**）。首次开启时序：用户把开关拨到 ON → 回调触发 → 此时 `isAPOn` 仍为 false（AP 尚未真正打开、缓存未更新）→ 代码走进"关闭"分支去执行 closeAp/弹确认框逻辑 → 状态回调又把开关翻回 OFF → 状态变化再次触发回调，两个状态源互相拉扯，开关在 ON/OFF 间循环动画。本质是在"状态变化回调"里引用了外部可变状态而非回调携带的新值，两个真值源（UI 开关、管理器缓存）不一致时控制流翻转。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
-        mBindingHeader.switchHotspot.setOnCheckedChangeListener {
-            LogUtils.d(TAG, "switchHotspot isChecked: $it  $mIsCancel")
+        mBindingHeader.switchHotspot.setOnCheckedChangeListener { isChecked ->
+            LogUtils.d(TAG, "switchHotspot isChecked: $isChecked  $mIsCancel")
             if (mIsCancel) return@setOnCheckedChangeListener
             mBindingHeader.switchHotspot.enableOverlay()
-            if (mWxApManagerI.isAPOn) {
+            if (isChecked) {
+                mBinding.tvNoConnect.visibility = View.VISIBLE
+                lifecycleScope.launch(ioDispatcher) {
+                    delay(100)
+                    openProjectionHotspot()
+                }
+            } else {
                 val currentConnectType = DeviceConnectManager.getInstance().getCurrentConnectType()
                 if (currentConnectType == 1 || currentConnectType == 2 || currentConnectType == 3) {
                     // ...弹确认框 / closeAp 原关闭分支逻辑
```
（diff 实际为把原 else 的"开启"分支整体移到 isChecked==true 分支，关闭逻辑移入 else；此处按改动语义合并展示。）

## 为什么能修复
分支条件改为回调自带的 `isChecked` 后，控制流只由用户操作产生的新状态决定：拨 ON 必走 openProjectionHotspot，拨 OFF 必走关闭/确认流程，不再依赖可能滞后的 `isAPOn` 缓存，两个状态源不再互相触发翻转，开关动画稳定收敛。开启前 delay(100ms) 略微错开动画与打开指令的时序。隐患：`mIsCancel` 早退与 `enableOverlay()` 的遮罩时序仍耦合，若取消路径漏置 `mIsCancel` 仍可能触发一次误动作；`openProjectionHotspot` 内部的失败回滚需与 `onApState` 纠偏逻辑（见 `dcdcbb9b`）配合。

## 复盘与经验
- 状态变化回调（onCheckedChange/onStateChanged）内必须以回调参数为新状态依据，禁止反查外部缓存状态——缓存永远可能滞后于 UI。
- "开关反复横跳"是双真值源互相触发的典型症状，排查时先画出"UI 状态 → 指令 → 系统回调 → UI 状态"的环，找到环上引用旧状态的那一环。
- 元数据把该 bugfix 标为 [feature]，缺陷库 mistag 已标出；提交类型标注错误会污染缺陷统计，评审时要核对。
