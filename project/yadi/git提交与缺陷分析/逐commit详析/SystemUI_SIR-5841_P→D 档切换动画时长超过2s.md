# SIR-5841 · P→D 档切换动画时长超过 2s
- **提交**：`61a4a085` | 2026-08-14 | ljl | SystemUI | bugfix（参数调优类）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 系统需求

## 问题
P→D 档切换动画总时长超过 2 秒，体验过长。

## 根因分析
`PageStateMachine.kt` 进入 `State.S1_Linux_Dashboard`（Linux 仪表形态）时统一走 `enterS1WithLauncherDelay(ctx, delayMs)`：Launcher 在前台时会 `scheduleS1MeterForm(c.subMode, delayMs, ...)` 延迟 `delayMs` 后才通知 `setMeterForm(1)` 切仪表形态。此前 P→D 分支的两处调用（`SubMode.Pano_B1` 子模式与默认分支）都把延迟写死为 2000ms——原为等待切换动画完成而设的 2s（与缺陷库"延迟时间按照要求设置 2s，但实际体验过长"一致），叠加动画本身的时长后，从挂 D 到仪表切换完成明显超过 2s。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
             ctx.subMode == SubMode.Pano_B1 -> {
                 State.S1_Linux_Dashboard to { _: DataContext ->
-                    enterS1WithLauncherDelay(ctx, 2000) {
+                    enterS1WithLauncherDelay(ctx, 1200) {
                         if (isNeedStartNavi) {
                             notifier.bringNavToForeground()
                         }
             ...
             else -> {
                 State.S1_Linux_Dashboard to { _: DataContext ->
-                    enterS1WithLauncherDelay(ctx, 2000)
+                    enterS1WithLauncherDelay(ctx, 1200)
                 }
             }
```

## 为什么能修复
延迟从 2000ms 调到 1200ms（依据视频测试结果），`setMeterForm(1)` 及其后的导航拉起逻辑整体提前 800ms，端到端切换时长回到 2s 以内。属纯时序参数调整，无逻辑风险；隐患在于该魔法数字依赖 kanzi 侧动画实际时长，若后续动画改版，此值需同步校准，且 Pano_B1 与默认分支两处必须保持一致（本提交已同时改两处）。

## 复盘与经验
- "等动画结束"用固定 delay 而非动画完成回调/信号，是跨进程（SystemUI↔kanzi）联动里常见的妥协；妥协参数必须标注依据与复测方式，否则会以"体验慢"的 bug 形式回流。
- 同一延迟常量出现在多个分支时，应抽成命名常量（如 `S1_METER_FORM_DELAY_MS`），一处修改全局生效，避免分支间漂移——本例就要改两处。
- 性能/时长类缺陷的验证手段（视频逐帧计时）值得沉淀为标准回归步骤。
