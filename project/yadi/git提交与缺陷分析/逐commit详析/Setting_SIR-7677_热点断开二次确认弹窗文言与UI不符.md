# SIR-7677 · 热点断开二次确认弹窗文言与UI不符
- **提交**：`56c31239` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车机设置中断开热点连接（断开已接入的客户端设备）时弹出的二次确认弹窗，确认按钮显示通用"确认"文言，与 UI 设计稿要求的"断开"不符。

## 根因分析
热点断开确认弹窗由 `HotspotDialogFragment`（实现 `IWxApListener` 接口）中的 `showWarningDialog` 调用构建。开发时直接复用了通用确认弹窗模板，确认按钮文案参数 `confirmText` 传的是通用字符串资源 `R.string.confirm`，而不是 UI 稿为本场景指定的"断开"资源 `R.string.disconnect`。属于典型的"复用通用弹窗时未替换场景化文案"的资源引用错误，无逻辑缺陷。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
@@ -329,7 +329,7 @@ class HotspotDialogFragment : BaseDialogFragment(), IWxApListener
         showWarningDialog(
             title = getString(R.string.hotspot_block_title),
             content = getString(R.string.hotspot_block_hint),
-            confirmText = getString(R.string.confirm),
+            confirmText = getString(R.string.disconnect),
             cancelText = getString(R.string.cancel),
             onConfirm = { disconnectClient(item) })
```

## 为什么能修复
确认按钮文案从通用"确认"切到语义化资源 `disconnect`，弹窗文案与 UI 稿一致；点击行为仍走原 `onConfirm = { disconnectClient(item) }`，功能零变化，无副作用。隐患仅在于该资源需在多语言 strings 中均有翻译，否则多语环境下会回退默认值。

## 复盘与经验
- 复用通用确认弹窗时，`confirmText/cancelText` 应随场景显式传参并在 code review 时对照 UI 稿逐一核对文案资源。
- 文案类 UI 资源（title/hint/按钮）建议在 UI 走查清单中单列，此类问题（必现、C 级）最容易在开发自测中被忽略。
- 语义化命名资源（`disconnect` vs `confirm`）能让此类错误在代码评审时一眼看出。
