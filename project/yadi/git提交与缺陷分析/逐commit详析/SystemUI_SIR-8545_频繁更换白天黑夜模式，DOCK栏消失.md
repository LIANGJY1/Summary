# SIR-8545 · 频繁切换白天/黑夜模式导致 SystemUI 闪退、DOCK 栏消失

- **提交**：`b996552d` | 2026-09-16 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
频繁更换白天/黑夜模式（uiMode 变更触发 Fragment 视图快速重建）时 SystemUI 闪退，DOCK 栏随之消失不再恢复。

## 根因分析
`QuickSettingFragment` 用成员 `handler` 投递 `WHAT_INIT_DATA` 延迟初始化消息，消息体里直接引用 `viewLifecycleOwner` 去构造 `BrightnessTile` / `HUDBrightnessTile`。昼夜模式切换会销毁并重建 Fragment 视图：旧视图销毁后 `viewLifecycleOwner` 已失效，而 handler 队列里残留的 `WHAT_INIT_DATA` 仍会执行——拿到的是已销毁的 lifecycle owner，注册到其上的观察者/lifecycle 立即抛异常崩溃（元数据所述"旧 Handler 访问了已经销毁的 viewLifecycleOwner"）。此外 `hasInit` 未复位，视图重建后初始化被跳过，即使不崩 DOCK 栏也无法恢复。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
@@ handler 消息处理
                 WHAT_INIT_DATA -> {
+                    val owner = viewLifecycleOwnerLiveData.value ?: run {
+                        LogUtils.w(TAG, "Skip init data: fragment view is unavailable")
+                        return
+                    }
                     brightnessTile =
-                        BrightnessTile(context, viewLifecycleOwner, settingService, brightnessLayout)
+                        BrightnessTile(context, owner, settingService, brightnessLayout)
                     if (mHasHudAdjustment) {
                         hudBrightnessTile = HUDBrightnessTile(
-                            viewLifecycleOwner,
+                            owner,
                             hudBrightnessLayout!!,
                             settingService
                         )
@@ onDestroyView
     override fun onDestroyView() {
         LogUtils.e(TAG, "---onDestroyView---")
-        super.onDestroyView()
+        handler.removeCallbacksAndMessages(null)
+        hasInit = false
         basicServicesTile?.destroy()
+        super.onDestroyView()
     }
```
（同提交把 `clRoot` 的滚动/触摸监听从匿名 object 改写为 SAM lambda，纯等价重构，无行为变化。）

## 为什么能修复
三道防线闭环 Handler 生命周期：`onDestroyView` 里 `removeCallbacksAndMessages(null)` 清空残留消息，从源头防止"死后执行"；消息体改从 `viewLifecycleOwnerLiveData.value` 动态取 owner，视图不存在时告警并跳过，消除对已销毁 owner 的引用；`hasInit = false` 保证视图重建后 `WHAT_INIT_DATA` 能重新走完整初始化，DOCK 栏恢复显示。`super.onDestroyView()` 移到末尾确保清理先于框架销毁。隐患：跳过初始化意味着该次视图周期无亮度条，需等下次消息/重建，属可接受的降级。

## 复盘与经验
- Fragment + 成员 Handler 是昼夜切换/主题重建场景的必炸组合：消息体引用 `viewLifecycleOwner` 前必须经 `viewLifecycleOwnerLiveData.value` 判活，销毁时必须 `removeCallbacksAndMessages(null)`。
- "闪退 + 功能消失"两个症状常是同一根因：崩溃后初始化标志未复位，重建后被 `hasInit` 短路。防御性修复要同时处理崩溃路径和恢复路径。
- 昼夜模式频繁切换是车机特有的高频 uiMode 变更场景，SystemUI/桌面类常驻模块的 Fragment 都应按此模式加固。
