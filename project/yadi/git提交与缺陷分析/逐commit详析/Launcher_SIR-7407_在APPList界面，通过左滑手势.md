# SIR-7407 · 在APPList界面，左滑手势误退出APPList界面
- **提交**：`248791c3` | 2026-09-04 | caohongliang | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
在 APPList（应用列表）界面上做左滑手势（本意是翻动/操作列表），整个 APPList 页面被退出。

## 根因分析
该 Launcher 的全局手势体系中，左滑手势会被系统侧转换为 back（返回）事件下发。`AppListActivity` 此前没有重写 `onBackPressed()`，back 事件沿默认行为触发了 Activity 的返回栈出栈，直接退出 APPList。而 APPList 作为Launcher 内的一个功能页，其退出路径本应由自身动画流程接管——代码里已有受控的退出链路：`startExitAnimation` 播放 `AppListTransitionState.forExit` 过渡动画，动画结束后按 `isNeedGoHome` 标志分流为 `finish()` 或 `navigateToLauncher()`（发 ACTION_MAIN/CATEGORY_HOME Intent 回桌面）。左滑触发的 back 绕过了这套动画与状态管理，属于"系统级手势语义"与"应用级页面退出设计"的冲突。同类问题在同仓库反复出现（如 CommonTools 的 SIR-7425 弹窗被左滑关闭），说明左滑=back 的映射是全局约定，各全屏页面必须自行决定是否消费该事件。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
         updateCarConnectStatus(false, mCarTabName)
     }
 
+    override fun onBackPressed() {
+        // 拦截返回键
+    }
+
     companion object {
         private const val TRANSITION_DURATION_MS = 333L
         var SIsCarConnectClick = false
```

## 为什么能修复
空实现把 back 事件在此 Activity 内"吞掉"，左滑手势不再引发默认出栈行为，页面只能通过自身受控路径（退出动画→finish 或 navigateToLauncher）离开，动画状态机不会被 back 打断。副作用需要权衡：用户物理返回键也被一并禁用，若产品后续要求返回键可用，应改为在 `onBackPressed` 里调用自身的 `startExitAnimation` 而非完全置空；另外该写法依赖 `onBackPressed` 未被废弃的 API 版本，若未来升级到 predictive back 体系需迁移到 `OnBackPressedDispatcher`。

## 复盘与经验
- 车机/大屏系统常把手势映射为系统按键事件，全屏页面若不希望被手势退出，必须显式消费 back（重写 `onBackPressed` 或注册 `OnBackPressedCallback`），不能依赖"用户不会滑"。
- 页面退出应收敛到单一受控出口（带动画、带状态清理），把 back 拦截后引导到该出口，比"禁用 back"更完备。
- 同一全局手势约定引发的 bug 会在多个模块重复出现（本仓库 SIR-7407、SIR-7425 同因），值得沉淀为基类 `BaseActivity` 的统一开关而非逐页重写。
