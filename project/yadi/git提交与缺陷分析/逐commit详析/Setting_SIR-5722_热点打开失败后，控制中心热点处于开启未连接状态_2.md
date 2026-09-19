# SIR-5722 · 热点打开失败后开关状态未同步（Setting 侧）

- **提交**：`dcdcbb9b` | 2026-08-06 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rc：状态未同步 / sol：同步状态）

## 问题
热点打开失败后，设置热点弹窗里的开关仍停留在"开启未连接"状态，与真实热点状态不一致（控制中心同样受影响，SystemUI 侧修复见 `50db7e79`）。

## 根因分析
`HotspotDialogFragment.onApState(state)` 是热点状态的回调入口，原实现对 `WIFI_AP_STATE_DISABLED`/`WIFI_AP_STATE_ENABLED` 两个分支**只调用了 `switchHotspot.disableOverlay()` 解除禁用遮罩，从不回写 `isChecked`**；只有 `WIFI_AP_STATE_FAILED` 分支会把开关置回 false。开关的选中态依赖用户点击时的乐观置位，一旦开启流程中途状态机经过 DISABLED/ENABLED 回调（例如失败前先广播了状态），UI 的 `isChecked` 与真实 AP 状态就脱钩，表现为"开启未连接"的悬挂状态。这是典型的"回调只管局部 UI（遮罩）不管核心状态（选中值）"的半截子同步。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
     override fun onApState(state: Int) {
+        LogUtils.d(TAG, "onApState: $state")
         when (state) {
-            WIFI_AP_STATE_DISABLED -> mBindingHeader.switchHotspot.disableOverlay()
-            WIFI_AP_STATE_ENABLED -> mBindingHeader.switchHotspot.disableOverlay()
+            WIFI_AP_STATE_DISABLED -> {
+                mBindingHeader.switchHotspot.isChecked = false
+                mBindingHeader.switchHotspot.disableOverlay()
+            }
+            WIFI_AP_STATE_ENABLED -> {
+                mBindingHeader.switchHotspot.isChecked = true
+                mBindingHeader.switchHotspot.disableOverlay()
+            }
```
```diff
// 同文件：关闭热点前增加 200ms 延迟，避免状态回调竞态
                 } else {
-                    mWxApManagerI.closeAp()
+                    lifecycleScope.launch(ioDispatcher){
+                        delay(200.milliseconds)
+                        mWxApManagerI.closeAp()
+                    }
                 }
```

## 为什么能修复
修复让每个 AP 状态分支都显式回写 `isChecked`，开关选中态始终跟随真实状态机收敛，失败/关闭后不会再残留"开启"假象；新增 `onApState` 日志便于后续状态时序排查。`closeAp` 前延迟 200ms（IO 协程）用于拉开与开关动画/状态广播的时序，降低关闭指令打在中间态上的竞态。副作用：200ms 硬延时属于经验值治标，极端调度下仍可能不够；依赖 `ioDispatcher` 注入，测试可控。

## 复盘与经验
- 开关类 UI 的状态必须以"状态回调"为唯一真值来源，点击时的乐观置位只作过渡效果，任何分支都不能漏回写。
- 回调分支处理要覆盖全枚举并统一收口（每个分支都做"状态回写+解除遮罩"两件事），遗漏半步就会出现"半同步"状态。
- 状态不同步类 bug 先加全量状态日志复现时序，再修；本提交先补日志再补逻辑，顺序值得借鉴。
