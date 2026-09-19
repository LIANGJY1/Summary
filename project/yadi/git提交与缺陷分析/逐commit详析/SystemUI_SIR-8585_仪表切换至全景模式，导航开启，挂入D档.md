# SIR-8585 · 全景+导航开启挂 D 档，DOCK 栏被隐藏
- **提交**：`deb17608` | 2026-09-18 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 仪表信息

## 问题
仪表切到全景模式、导航开启后挂入 D 档，DOCK 栏进入了隐藏状态（应显示快捷入口）。

## 根因分析
`PageStateMachine` 的 D/R 档处理（`handleGearDR()` 及同构分支）在"地图保持前台"（`ctx.navForeground && isMapInNaviOrCruise()`）时，用**触屏锁定开关**分流目标态：`DriveTouchLockController.isSwitchOn() && !isLocked()` → S3 暂态导航（DOCK 保留快捷入口），否则 → S2 常态导航（DOCK 隐藏）。问题场景是"开关=关 + 车速 0（驻车未锁）"：按原条件走到 S2 常态导航，DOCK 被送进隐藏——而常态导航的前置语义要求车速不为 0，驻车时进常态本身就是错配。缺陷库 sol 明确：挂 D/R 时"只看是否锁定，不再用开关开/关把车速 0 直接送进常态"。

## 关键代码修改
改动文件：PageStateMachine.kt（handleGearDR 与 R 档同构分支各一处）
```diff
// application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
             mapKeepForeground -> {
-                if (DriveTouchLockController.isSwitchOn() && !DriveTouchLockController.isLocked()) {
-                    // 触屏锁定开关=开且驻车未锁 → S3 暂态导航；达到锁屏条件后由 onDriveTouchLockChanged 转 S2
+                // 未锁定（车速=0 或未达锁屏阈值）→ 暂态导航，DOCK 保持快捷入口（SIR-8585）。
+                // 已锁定 → 常态导航；之后锁定翻转仍由 onDriveTouchLockChanged 在 S2/S3 间切换。
+                if (!DriveTouchLockController.isLocked()) {
                     State.S3_Android_Drive to { _: DataContext ->
                         mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, FOUR_FINGER_SWIPE_INTO_S3_DELAY)
                         notifier.showAndroidDrivePage()
                     }
                 } else {
-                    // 触屏锁定开关=关 → S2 常态导航
                     State.S2_Android_Nav to { _: DataContext ->
                         notifier.showAndroidNavPage()
                     }
```

## 为什么能修复
分流条件从"开关开 && 未锁定"简化为"是否锁定"：驻车（未锁定）挂 D 一律进 S3 暂态导航，DOCK 保持快捷入口不再被隐藏；行驶中达到锁定条件后，仍由既有的 `onDriveTouchLockChanged` 在 S2/S3 间翻转，锁定态下的行为不受影响。这同时修掉了"开关关 + 车速 0 进 S2 违反常态导航前置条件"的语义漏洞。`handleGearDR` 与 R 档分支同步修改，避免两路径行为分叉。副作用：开关关闭的用户驻车时会先看到暂态导航（DOCK 可见），需待锁定逻辑接管后才切常态——与"未锁定=允许触屏交互"的产品语义一致。

## 复盘与经验
- 状态机的分流条件要服务"当前物理状态"（车速/锁定），而不是混入"用户偏好设置"（开关）；偏好只应影响迁移阈值，不应决定目标态语义。
- 同一决策逻辑在多个档位分支中复制时（handleGearDR 与 R 档同构），必须成对修改，更优做法是抽成单一函数。
- S2/S3 两种导航态的边界条件（车速是否为 0、是否锁定）要有明确前置约束表，评审时逐条对照分流代码。
