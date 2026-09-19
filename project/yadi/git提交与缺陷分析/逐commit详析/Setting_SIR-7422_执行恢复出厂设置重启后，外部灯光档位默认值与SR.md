# SIR-7422 · 恢复出厂设置重启后，外部灯光档位默认值与SRS定义不符
- **提交**：`b184f97f` | 2026-09-07 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
执行恢复出厂设置并重启后，灯光设置页"外部灯光"档位控件显示的默认档位与 SRS 规定不符（SRS 恢复后应为 OFF 档，界面却停在别的档位）。

## 根因分析
`LightFragment` 初始化时 `rgExternalLighting.setMixedItems(items, selectedIndex = 2)` 先硬编码选中第 2 档；随后 `CCU_HEADLIGHTSWITCH` 信号回调 `updateExternalLightingUI(state)` 再校正。问题在于时序：恢复出厂重启后信号链路上线较慢，真实信号（OFF=0）到达时控件可能尚未完成初始化，一次性的 `setSelectedIndex(state)` 被后续控件初始化/重置覆盖，UI 停留在硬编码的默认档 2。旧代码其实已意识到该问题并留有补丁：缓存 `pendingExternalLightState`，控件初始化后 `rgExternalLighting.post { when (pendingExternalLightState) { 0 -> 0; 1 -> 0; 2 -> 1; 3 -> 2; else -> return@post } ... }`——但这个映射把信号值 1→0、2→1、3→2 做了整体左移，本身就是一个可疑的历史残留（疑似旧协议错位的补丁），导致即使补丁执行也会选错档；且 `pendingExternalLightState` 只在特定路径被赋值，覆盖不全。修复改为统一的"最新信号值"模型：新增 `lastExternalLightState` 字段，`updateExternalLightingUI` 每次收到信号都更新它，并在 `setSelectedIndex(state)` 之外再 `post` 一次"若仍是该信号则重设选中项"，保证视图初始化完成后用最新信号值再刷一遍；初始化兜底同样改用 `lastExternalLightState` 直接选中（`if (targetIndex in 0..3)`），删除了错位映射。另加 `onResume -> readAllSignalValues()`，把 `CCU_HEADLIGHTSWITCH`、`CCU_SMARTLOWBEAMSWITCH` 等七个灯光信号读出打日志，便于恢复出厂场景核对。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
```diff
--- application/Setting/.../fragment/LightFragment.kt
             rgExternalLighting.setMixedItems(items = mixedItems, selectedIndex = 2)
             rgExternalLighting.post {
-                val targetIndex = when (pendingExternalLightState) {
-                    0 -> 0;
-                    1 -> 0;
-                    2 -> 1;
-                    3 -> 2;
-                    else -> return@post
+                val targetIndex = lastExternalLightState
+                if (targetIndex in 0..3) {
+                    rgExternalLighting.setSelectedIndex(targetIndex)
                 }
-                rgExternalLighting.setSelectedIndex(targetIndex)
             }
...
     fun updateExternalLightingUI(state: Int) {
         mBinding.apply {
             if(pendingExternalLightState==state){ rgExternalLighting.cancelRebound() }
+            lastExternalLightState = state
             rgExternalLighting.setSelectedIndex(state)
-//            when (state) { ... }
+            rgExternalLighting.post {
+                if (lastExternalLightState == state) {
+                    rgExternalLighting.setSelectedIndex(state)
+                }
+            }
         }
         pendingExternalLightState=-1
     }
```

## 为什么能修复
任何时刻到达的信号都同时更新 `lastExternalLightState` 并做"当帧 + post 下一帧"两次选中，与控件初始化的先后不再敏感——初始化后必有一次以最新信号值的重设，硬编码默认档不再残留；删除 1→0/2→1/3→2 的错位映射后选中值与信号语义一一对应，SRS 默认值得以如实呈现。隐患：`post` 二次刷新可能与用户正在进行的拖动/回弹动画竞争（有 `lastExternalLightState == state` 守卫，风险小）；`readAllSignalValues` 目前仅打日志，若想彻底解决"重启后信号迟到"，还应考虑 onResume 主动 read 后主动触发 UI 刷新。

## 复盘与经验
- "恢复出厂/重启后默认值不对"的通病是 UI 先画硬编码默认、信号后到被吞；正确模式是"控件默认态 + 最新信号值缓存 + 初始化完成后必然回放一次"，不要依赖回调到达顺序。
- 历史补丁中的值映射（1→0、2→1、3→2）这类"错位修正"大概率是协议错位的遮羞布，重构时应对照信号定义表核实后删除，否则错误会被固化。
- 车辆信号类页面在 `onResume` 全量读一遍信号并打日志，是恢复出厂/休眠唤醒类问题的低成本观测手段。
