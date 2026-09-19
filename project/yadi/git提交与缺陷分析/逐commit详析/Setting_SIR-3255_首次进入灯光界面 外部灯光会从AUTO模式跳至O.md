# SIR-3255 · 首次进入灯光界面，外部灯光从 AUTO 跳至 OFF

- **提交**：`f21bd402` | 2026-07-29 | sgh | Setting | bugfix（提交头误标为 [feature]，缺陷库已标记 mistag）
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
首次进入灯光设置界面时，外部灯光挡位先显示 AUTO，随后跳变为 OFF，产生肉眼可见的挡位跳变。

## 根因分析
`LightFragment` 中 RadioGroup（`rgExternalLighting`）的初始化与 CAN 信号刷新之间存在时序竞态：
1. 原初始化 `setMixedItems(items, selectedIndex = -1)` 以"无选中"建组，未体现默认 AUTO（`externalLightState = 3`）；
2. 原代码用 `isFirstSignalProcessing` 标志 + `postDelayed(500ms)` 处理第一个信号——用固定延时猜控件初始化完成时机。首次进入时信号先到、500ms 后才刷新，期间界面停在错误的默认观感上；随后真实挡位（如 OFF）落到 UI 上形成"AUTO→OFF"跳变。且固定延时在低端/负载场景并不可靠，缺陷库根因"ui 刷新问题"即此。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
     private var externalLightState = 3
     private var externalLightStateTemp = -1
+    private var pendingExternalLightState = -1  // 暂存待应用的CAN信号值，等待RadioGroup初始化完成
@@
-            rgExternalLighting.setMixedItems(items = mixedItems, selectedIndex = -1)
+            rgExternalLighting.setMixedItems(items = mixedItems, selectedIndex = 2)
+            //先缓存值，待控件初始化完成在去刷新UI
+            rgExternalLighting.post {
+                val targetIndex = when (pendingExternalLightState) {
+                    0 -> 0;
+                    2 -> 1;
+                    3 -> 2;
+                    else -> return@post
+                }
+                rgExternalLighting.setSelectedIndex(targetIndex)
+            }
@@
-    private var isFirstSignalProcessing = true  // 标记是否是第一次处理信号
- 
     fun updateExternalLightingUI(state: Int) {
+        pendingExternalLightState = state
         mBinding.apply {
             rgExternalLighting.cancelRebound()
-            if (isFirstSignalProcessing) {
-                isFirstSignalProcessing = false
-                // 延迟 500ms 再处理第一个信号，确保 RadioGroup 初始化完成
-                rgExternalLighting.postDelayed({
-                    refreshByState(state)
-                }, 500)
-            } else {
-                refreshByState(state)
-            }
+            refreshByState(state)
         }
     }
```

## 为什么能修复
三步消除竞态：初始选中即设为 AUTO（index 2），首帧与真实状态一致，不再出现"默认观感错误"；首信号到达时先写入 `pendingExternalLightState` 暂存，初始化代码用 `post {}`（一帧后、控件就绪）按最新暂存值应用选中——用"帧同步"替代"猜 500ms"，时序可靠且无固定延时；后续信号直接 `refreshByState(state)`，删除了首帧特判。跳变根因（初始化前后的两次错误状态展示）被整体移除。隐患：`post` 回调只执行一次，若 RadioGroup 初始化晚于该帧（极端场景）暂存值可能错过，但相比固定延时已大幅收敛；状态→索引映射（0/2/3）在 init 与 refresh 两处重复维护，新增挡位需同步。

## 复盘与经验
- **不要用 postDelayed(固定毫秒) 等初始化**：延时猜时序在高负载下必然偶发失效（本单"高概率 40%~80%"正是竞态特征），应改用 `post`/回调/状态标志等事件驱动同步。
- **初始 UI 要渲染真实的默认状态**：`selectedIndex = -1` 建组再等信号纠正，天然制造"跳变"；把业务默认值（AUTO）直接落到首帧可从源头消灭闪烁。
- **"先缓存后应用"（pending 值）是处理初始化竞态的通用范式**：信号先到不丢、控件就绪后取最新值一次性应用，天然去抖。
- **首帧特判（isFirstProcessing）是代码坏味道**：为修时序问题引入的特判会累积复杂度，重构为统一路径更健壮。
