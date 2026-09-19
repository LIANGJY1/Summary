# SIR-7112 · 控制-模式-解除屏幕锁定无二次确认弹窗
- **提交**：`52a3a058` | 2026-09-02 | sgh | Setting | bugfix（需求遗漏补齐）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
"控制 → 模式 → 解除屏幕锁定"开关一拨即生效，缺少需求要求的二次确认弹窗（低速解除屏幕触控锁定存在行车安全隐患）。

## 根因分析
`SceneModeFragment` 中 `swUnlockScreen.switchCompat` 原来只挂了 `setOnCheckedChangeListener`，`isChecked` 一变就直接 `SettingsUtils.setGSetting(SETTING_UNLOCK_SCREEN, value)` 落库，无任何确认环节。缺陷库定性为"需求遗漏"。本次补齐确认流，并新增通用灰色确认弹窗组件 `TipDialog`（`component/CommonTools`）与扩展 `showTipDialog`，同时把该页滚动容器换成 `SmartNestedScrollView`。

## 关键代码修改
改动文件：`application/Setting/.../ui/fragment/SceneModeFragment.kt`、`res/values/strings.xml`、`res/layout/fragment_scene_mode.xml`、`component/CommonTools/.../dialog/TipDialog.kt`（新增）、`.../ext/DialogExt.kt`、`res/layout/dialog_tip.xml`（新增）
```diff
--- .../ui/fragment/SceneModeFragment.kt
-            swUnlockScreen.switchCompat.setOnCheckedChangeListener { _, isChecked ->
-                val value = if (isChecked) SWITCH_ON else SWITCH_OFF
-                SettingsUtils.setGSetting(Constants.SETTING_UNLOCK_SCREEN, value)
-            }
+            swUnlockScreen.setOnOverlayClickListener {
+                showTipDialog(
+                    title = getString(R.string.unlock_screen_dialog_title),
+                    content = getString(R.string.unlock_screen_dialog_content),
+                    confirmText = getString(R.string.unlock_screen_dialog_confirm),
+                    cancelText = getString(R.string.cancel),
+                    onConfirm = {
+                        setUnlockScreenState(true)
+                    }
+                )
+            }
+            swUnlockScreen.switchCompat.setOnClickListener {
+                if (!mBinding.swUnlockScreen.isChecked) {
+                    setUnlockScreenState(false)   // 关闭方向无需确认
+                }
+            }
+    private fun setUnlockScreenState(enable: Boolean) {
+        mBinding.swUnlockScreen.isChecked = enable
+        SettingsUtils.setGSetting(Constants.SETTING_UNLOCK_SCREEN,
+            if (enable) CanSignalConstants.SWITCH_ON else CanSignalConstants.SWITCH_OFF)
+        updateUnlockScreenOverlay()
+    }
```
配套：新增 `TipDialog`（灰色按钮提示框，confirm 触发回调）、弹窗文案"开启后，车速低于20Km/h时仍可进行触屏操作……请确认是否继续开启？"。

## 为什么能修复
开启方向的交互从"直接改开关"改为"点击遮罩层弹 `TipDialog`，确认后才 `setUnlockScreenState(true)`"；关闭方向保持一拨即关（低风险方向不加摩擦），是安全类二次确认的合理不对称设计。`updateUnlockScreenOverlay()` 依据开关状态启用/禁用遮罩，保证弹窗入口只在关闭态出现。副作用：开关视觉状态与实际状态在确认前短暂不同步（点击遮罩不立即拨动），符合确认语义；新增通用组件需回归其他引用 `showWarningDialog` 的页面无冲突。

## 复盘与经验
- 安全相关开关（解除行车锁定）的确认弹窗应只加在"高风险方向"（开启），低风险方向直接执行，避免过度摩擦。
- 用 overlay 点击层拦截原开关手势，是给既有 Switch 补二次确认的低侵入手法，比禁用开关再加独立按钮更自然。
- 通用 TipDialog 落在 CommonTools 并配 DialogExt 扩展，后续同类需求可直接复用。
