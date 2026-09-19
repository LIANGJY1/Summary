# 无单号 · [SIR-XXX] 优化应用列表自动退出逻辑
- **提交**：`8d2237f4` | 2026-09-17 | dufan | Launcher | feature（行为优化，兼修复计时漏洞）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
应用列表页（AppListActivity）的"无操作自动退回 Launcher"计时优化：触摸重置计时与真正启动计时的判断条件统一收敛到 `startAutoCloseTimer()` 内部，修复原条件下"计时被启动但永不重置"的漏洞。

## 实现结构
仅改动 `function/applist/AppListActivity.kt`（+4/-4，两处条件互移）：
- 原逻辑：`onDownTouch` 回调里判断 `viewPager.currentItem == 0` 才调 `startAutoCloseTimer()`；而 `startAutoCloseTimer()` 内部无条件 `removeCallbacks + postDelayed`。
- 新逻辑：`onDownTouch` 无条件调用 `startAutoCloseTimer()`；判断移入函数内部——只有 `currentItem == 0` 才 `postDelayed`，但 `removeCallbacks` 无条件执行。

## 关键代码
```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
mBinding.layout.onDownTouch = {
-   if (mBinding.viewPager.currentItem == 0) {
-       startAutoCloseTimer()
-   }
+   startAutoCloseTimer()
}

private fun startAutoCloseTimer() {
    // 先移除之前的倒计时，避免重复执行
    mAutoCloseHandler?.removeCallbacks(mAutoCloseRunnable!!)
-   mAutoCloseHandler?.postDelayed(mAutoCloseRunnable!!, autoCloseDelay)
+   if (mBinding.viewPager.currentItem == 0) {
+       mAutoCloseHandler?.postDelayed(mAutoCloseRunnable!!, autoCloseDelay)
+   }
}
```

实现讲解：条件外置的旧写法存在漏洞——用户从第 1 页滑到第 2 页后，若计时器是在第 1 页时启动的，第 2 页的触摸因条件不满足不会调用 `startAutoCloseTimer()`，旧的倒计时无法被触摸重置，用户仍在操作时页面可能突然自动退出。条件内移后：任何触摸都会先 `removeCallbacks` 清掉旧计时（即使不在第 1 页），在第 1 页则重新计时——"清除"与"重排"解耦，语义完整。

## 复盘与要点
- **"清理副作用"与"新增副作用"应解耦**：把 `removeCallbacks` 与条件 `postDelayed` 拆开，让调用方无条件进入函数、函数内部决定是否重排，是定时器管理的通用正确姿势；条件写在调用方会漏掉"只清不排"的场景。
- **4 行的防御性重构**：无行为规格变更，纯粹堵逻辑洞；车机上"用户操作中被强制退出"是强感知缺陷，这类小提交性价比极高。
- **可复用**：自动退出/自动息屏类计时器（touch 重置型）都适用此模式——入口统一、清除无条件、重排有条件。
