# SIR-6606 · 扫码登录弹窗导致 Dock 栏变深色

- **提交**：`da31aa4c` | 2026-08-27 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心（提交信息标注影响等级 B，以缺陷库为准）

## 问题
弹出扫码登录弹窗时，弹窗下方的 SystemUI Dock 栏也被压暗变深色，与 UI 设计稿不一致。

## 根因分析
`QrCodeLoginDialog` 原先固定使用 `R.style.QrCodeLoginDialogTheme` 主题，该主题启用了 `android:backgroundDimEnabled`（`backgroundDimAmount=0.5`）。Android Dialog 的 backgroundDim 是由 WindowManager 施加在弹窗**所在窗口层级之下整个区域**的系统级遮罩，遮罩范围不以应用页面为边界。车机场景中 SystemUI 的 Dock 是一个独立窗口，与账号中心 Activity 同层，于是系统遮罩把 Dock 也一起盖暗了。修复思路是"去系统遮罩、改用页内遮罩"：弹窗支持无遮罩主题，遮罩改为 Activity 布局内部的 `page_dim_overlay` 视图，只覆盖账号中心自身内容。

## 关键代码修改
改动文件：application/AccountCenter/src/main/java/com/yadea/accountcenter/dialog/QrCodeLoginDialog.kt、application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt、application/AccountCenter/src/main/res/layout/activity_login.xml、application/AccountCenter/src/main/res/values/styles.xml
```diff
--- application/AccountCenter/src/main/res/values/styles.xml
+    <style name="QrCodeLoginDialogNoDimTheme" parent="QrCodeLoginDialogTheme">
+        <item name="android:backgroundDimEnabled">false</item>
+        <item name="android:backgroundDimAmount">0</item>
+    </style>

--- application/AccountCenter/src/main/java/com/yadea/accountcenter/dialog/QrCodeLoginDialog.kt
-class QrCodeLoginDialog(context: Context) : Dialog(context, R.style.QrCodeLoginDialogTheme) {
+class QrCodeLoginDialog(
+    context: Context,
+    dimBehind: Boolean = true
+) : Dialog(
+    context,
+    if (dimBehind) R.style.QrCodeLoginDialogTheme else R.style.QrCodeLoginDialogNoDimTheme
+) {

--- application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
-        qrCodeLoginDialog = QrCodeLoginDialog(this)
+        qrCodeLoginDialog = QrCodeLoginDialog(this, dimBehind = false)
+        qrCodeLoginDialog!!.setOnDismissListener {
+            hidePageDim()
+        }
```
配套改动：`activity_login.xml` 新增 `page_dim_overlay`（`#80000000`、`clickable=true`、默认 gone）的页内遮罩 View；`LoginActivity` 在 `show()` 前 `showPageDim()`，在 dismiss/cancel/`onPause`/销毁路径 `hidePageDim()`。

## 为什么能修复
系统级 backgroundDim 关闭后，WindowManager 不再压暗账号中心窗口以外的区域，Dock 栏恢复原色；页内 `page_dim_overlay` 补上了"弹窗聚焦、背景变暗"的视觉语义，且只覆盖账号中心自身布局。遮罩显隐与弹窗 show/dismiss/cancel/onPause 全部联动，避免出现"弹窗没了遮罩还在"的残留。保留 `dimBehind=true` 默认值，强制登录等原有走系统遮罩的场景不受影响。

## 复盘与经验
- Dialog 的 backgroundDim 是窗口级的，多窗口车机系统中会波及 SystemUI 独立窗口；跨窗口 UI 污染优先考虑"页内自绘遮罩"替代系统遮罩。
- 用默认参数保留旧行为（`dimBehind: Boolean = true`）是低成本兼容方案，调用方逐个迁移。
- 替换遮罩实现时必须枚举所有退出路径（dismiss、cancel、onPause、销毁）同步清理状态，否则会产生新的 UI 残留 bug。
