# SIR-7254 · 安全监控页循环录像开关缺少车主账号权限限制
- **提交**：`46737dc0` | 2026-09-08 | shengguanghui | Setting | bugfix（cherry-pick 自 7575cc5f）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **注意**：JSON 元数据把 rc 记为"判断网络有无连接逻辑有问题"（与 d555ef72 的 SIR-7254 串号），但提交实际是"循环录像开关加车主权限判断"（新增需求），以 diff 实际为准。

## 问题
车辆设置-控制-安全监控页面，非车主账号（访客等）也能操作"循环录像"开关，缺少车主账号权限限制。

## 根因分析
`SafetyMonitorFragment` 里一整套行车记录仪开关（`ssvLoopRecord` 等）都由 `setDashcamGray(enabled)` 统一按 `dvrConnected`（DVR 是否连接）置灰，判据里根本没有账号维度——无论当前登录者是不是车主，开关都可点。新增需求要求循环录像仅车主可操作，于是需要一个把 `isCarOwner` 与 `dvrManager.isConnect` 组合进灰置判据的独立控制点，并且要在三个时机都刷新：页面恢复（onResume 补刷链路）、DVR 服务 `onConnect`、初始进入（`setDashcamGray` 调用之后的公共路径）。同提交还顺带给 `safeUpdateStorageBar()`、`dvrCallback.onConnect/notifyDeviceValue` 加了 `isAdded` 前置判断，防页面已脱离时的 `requireContext()` 崩溃。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ -365,6 +374,10 @@
+    private fun updateLoopRecordGrayState() {
+        mBinding.ssvLoopRecord.setGrayState(isCarOwner && dvrManager.isConnect)
+    }
+
     private fun setDashcamGray(enabled: Boolean) {
         mBinding.apply {
             ssvLoopRecord.setGrayState(enabled)
@@ -395,9 +409,11 @@
         override fun onConnect() {
+            if (!isAdded) return
             dvrConnected = true
             applyWhenVisible {
                 setDashcamGray(true)
                 refreshDvrStates()
             }
             getVideoData()
+            updateLoopRecordGrayState()
         }
```
（另在 onResume 补刷块尾部与初始化路径各追加一次 `updateLoopRecordGrayState()`；`safeUpdateStorageBar` 头部加 `if (!isAdded) return`。）

## 为什么能修复
循环录像开关的灰置从"只看 DVR 连接"升级为 `isCarOwner && dvrManager.isConnect` 双条件，非车主账号下开关被置灰不可操作；三个刷新时机保证账号态、连接态任一变化后状态都会收敛。副作用：普通 `setDashcamGray(enabled)` 仍会整体刷一遍 `ssvLoopRecord`，若 `enabled=true` 而账号非车主，随后必须再调 `updateLoopRecordGrayState()` 覆盖回来——当前三个时机都补了调用，但后续新增 setDashcamGray 调用点时容易漏掉这层覆盖顺序，存在状态被"洗回"的隐患。

## 复盘经验
- "权限维度"（车主/访客）应该像连接态一样成为开关灰置的常规判据，而不是需求来了再打补丁；设置项控件的 setGrayState 建议统一收口传入多因子。
- 双条件灰置出现后，注意 setXxx 与 setXxxByPermission 两套刷新的调用顺序，单一出口可以避免互相覆盖。
- Fragment 异步回调里 `isAdded`/`view != null` 是保命判断，DVR 这类生命周期长于页面的服务回调尤其需要。
