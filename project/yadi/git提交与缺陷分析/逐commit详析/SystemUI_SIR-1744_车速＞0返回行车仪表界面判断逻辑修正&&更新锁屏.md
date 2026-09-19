# SIR-1744 · 车速＞0 时驻车桌面未自动切回行车仪表界面

- **提交**：`d089667a` | 2026-07-06 | ljl | SystemUI | bugfix（含锁屏 UI 更新的捆绑提交）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
车速从 0 升到 >0 时，若前台停在驻车桌面（而非某个应用页面），界面不会自动切换回行车仪表。

## 根因分析
`PageStateMachine.kt` 中"S3_Android_Drive 下车速 0→>0 自动返回仪表"的判断写的是：
```kotlin
if (oldSpeed <= 0 && speed > 0 && curState == State.S3_Android_Drive) {
    when {
        ctx.appForeground -> { handleEvent(Event.AppPageBackWithSpeed) ... }
```
条件是 `ctx.appForeground`——只有"应用页面在前台"才触发返回。而**驻车桌面本身不是"应用页"**（`appForeground` 不涵盖桌面/Launcher 形态），桌面在前台时整个分支不命中，事件不派发。缺陷库定性为"需求理解错误"：需求本意是"除地图全屏外的任何安卓界面（含驻车桌面）在车速起来后都要让位给仪表"，实现却按"非地图应用"狭义理解，恰好漏掉了默认落地页——驻车桌面。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`（逻辑修正，1 行）、`.../keyguard/actor/KeyguardActor.kt` 及 4 个锁屏资源文件（捆绑的锁屏 UI 更新）
```diff
// --- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
         // S3 下车速从 0 升到 >0：应用页/地图全屏自动返回
         if (oldSpeed <= 0 && speed > 0 && curState == State.S3_Android_Drive) {
             when {
-                ctx.appForeground -> {
+                !ctx.navForeground -> {
                     LogUtils.d(TAG, "[SPEED] app page with speed, trigger AppPageBackWithSpeed")
                     handleEvent(Event.AppPageBackWithSpeed)
                 }
```
锁屏部分（与该单无关的捆绑改动）：`KeyguardActor.kt` 新增 `KEY_PIN_SHOW_NUMBER` 配置（PIN 已输入位可切换显示数字或圆点）、`mCursorAnimator`/`startCursorBlink()` 光标闪烁动画，并调整 `actor_keyguard.xml`、`bg_keyguard_pin_box*.xml` 样式。

## 为什么能修复
条件从正向白名单 `appForeground` 反转为排除法 `!ctx.navForeground`：除地图全屏导航外的一切前台形态——包括驻车桌面——车速起来后都会触发 `AppPageBackWithSpeed` 返回仪表，覆盖面与需求一致。风险在于反转后默认范围扩大，若未来出现"车速行驶中也应停留在安卓界面"的新形态（如行车中的视频类合规页面），需要重新收窄条件；建议用显式形态枚举而非布尔组合表达。

## 复盘与经验
- **正向枚举 vs 反向排除**：`appForeground`（只认应用页）与 `!navForeground`（排除地图）语义差异巨大；需求是"除 X 以外都 Y"时，应直接写成排除式，逐形态补白名单极易漏项。
- **"默认页面"最容易漏测**：驻车桌面是系统落地页，反而逃过了"打开应用→车速起来"的主干用例；测试矩阵必须包含"什么都不开就停在桌面"的基线场景。
- 一个提交混入无关的锁屏 UI 改动，追溯 SIR-1744 时需自行剥离，提交拆分仍有改进空间。
