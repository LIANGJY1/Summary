# SIR-8648 · 全景模式快速切 R/P，仪表界面残留在 3D 驻车桌面
- **提交**：`79d25e5e` | 2026-09-17 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 待测试验证 · 域 主交互

## 问题
全景模式下快速切换 R/P 档位时，倒车仪表画面没有弹出，仪表界面残留在 3D 驻车（全景）桌面上。

## 根因分析
缺陷库 rc 完整描述了竞态：全景模式下挂 R 后，`MapMiddleware`（导航中间件）会**异步**把仪表形态改成 4（全景），与 SystemUI 挂 P 时下发的形态 0 互相打架；期间 `PageStateMachine` 还会被 `displayState` 静默置成 S4（`S4_Linux_Pano_B2` 全景最终态）。此后用户再挂 R 时，`handleGearR()` 走到原有守卫 `if (curState != State.S0_Android_Park) { return }`（日志 `[IGNORE] Already in DR state pool`），R 档请求被直接丢弃——既不做 `S4→S1` 的转场，也不补发形态 1，倒车画面永远出不来，仪表停在 3D 驻车桌面。快速 R/P 交替放大了"异步形态回调迟到 + 状态被静默改写"的窗口。

## 关键代码修改
改动文件：PageStateMachine.kt
```diff
// application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
     private fun handleGearR() {
+        if (curState == State.S4_Linux_Pano_B2) {
+            LogUtils.d(TAG, "[GEAR_R] S4 panorama B2 -> S1 reverse, setMeterForm(1)")
+            transitionTo(State.S1_Linux_Dashboard) {
+                notifier.showLinuxDashboard(ctx.subMode)
+            }
+            return
+        }
+        if (curState == State.S1_Linux_Dashboard) {
+            LogUtils.d(TAG, "[SUPPLEMENT] S1 Gear_R re-assert meter form 1")
+            notifier.showLinuxDashboard(ctx.subMode)
+            return
+        }
         if (curState != State.S0_Android_Park) {
             LogUtils.d(TAG,"[IGNORE] Already in DR state pool")
             return
```
（缺陷库注明"同时修改导航中间件和主交互"，MapMiddleware 侧改动不在本仓库本批次内；本提交为主交互/SystemUI 侧。）

## 为什么能修复
原来 R 档处理只认 `S0_Android_Park` 这一个合法起点，状态被 displayState 拉到 S4 或已被拉到 S1 时全部 IGNORE。修复后：S4 态收到 R 明确执行 `transitionTo(S1_Linux_Dashboard)` 并 `showLinuxDashboard`（即注释中的 setMeterForm(1)），完成全景→倒车仪表的转场；S1 态收到 R 则补发一次形态 1，把可能被 MapMiddleware 迟到的"形态 4"覆盖回倒车形态，保证最终一致性。原 `S0→S1` 快速路径与 `[IGNORE]` 守卫保持不变，不影响既有 D 档延迟转场逻辑。隐患：`showLinuxDashboard` 的重复补发依赖下游幂等（重复 setMeterForm(1) 无副作用），若下游有动画未做去重可能闪一下。

## 复盘与经验
- 两个模块（SystemUI 与导航中间件）异步写同一形态状态时，必须约定权威方与幂等补发策略，"后到的旧值覆盖新值"要用重断言（re-assert）兜底。
- 状态机守卫 `[IGNORE]` 的粒度要覆盖所有真实可达状态：新增 S4 这类静默迁移态后，每个事件入口都要重新审视"从该态收到该事件该怎么办"，否则就是丢事件。
- 快速交替操作（快速 R/P）是暴露异步竞态的标准压力手法，测试档位/形态类功能时应纳入用例。
- 日志分 TAG（`[GEAR_R]`/`[SUPPLEMENT]`/`[IGNORE]`）记录状态机分支走向，为这类偶现问题提供了直接证据链。
