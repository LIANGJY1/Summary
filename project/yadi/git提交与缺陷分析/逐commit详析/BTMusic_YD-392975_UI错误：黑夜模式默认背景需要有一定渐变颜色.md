# YD-392975 · BTMusic 黑夜模式默认背景缺少渐变与 UI 不一致
- **提交**：`5047e366` | 2026-07-30 | hedeyuan | BTMusic | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下蓝牙音乐连接态页面默认背景缺少渐变颜色，与 UI 稿不一致（是 YD-392969 背景替换的补漏）。

## 根因分析
`ab5845d8` 已新增昼夜两套渐变背景 `background_nomal` 并替换了布局与 `switchTheme()` 的引用，但蓝牙连接成功的业务分支（`MainActivity.kt` 中 `if (it.mIsConnected.value == true)` 路径）没有设置背景，该路径下 `clForgetContent` 仍保持旧背景/无渐变背景，黑夜模式下漏出不一致画面。属同类问题的"引用点遗漏"，前一提交没有全量覆盖 `clForgetContent.background` 的所有赋值路径。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`（仅 1 行）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt (连接成功分支)
             if (it.mIsConnected.value == true) {
+                mBinding!!.clForgetContent.background = resources.getDrawable(R.drawable.background_nomal, null)
                 startService()
             } else {
```

## 为什么能修复
在连接成功分支显式把 `clForgetContent` 背景刷成渐变图 `background_nomal`（借助 `-night-mdpi` 限定符自动适配黑夜版本），补齐该路径的背景状态。隐患：到此 `clForgetContent.background` 已散落至少 3 处赋值（布局、switchTheme、连接分支），后续再加状态分支极易再漏，属于用补丁补补丁的修法。

## 复盘与经验
- 换全局性背景资源时，先全局搜索旧资源名与目标控件的所有赋值点再动手；本次 392969 漏掉连接分支、3 天内又发一单（392975），就是没做全量清点。
- 同一控件背景散布多处的，应收敛到单一 `applyBackgroundForState(state)` 之类的状态函数，按状态机集中管理。
- UI 走查要覆盖状态矩阵（昼/夜 × 连接/未连接），只测默认路径会漏掉业务分支里的视图状态。
