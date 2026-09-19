# SIR-6605 · 里程重置确认弹窗点击内部也会消失

- **提交**：`8c5a85f8` | 2026-08-26 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
能量中心的"重置数据"确认弹窗，点击弹窗内容区（非"重置/取消"按钮）时弹窗意外关闭，用户选择被丢失。

## 根因分析
通用弹窗基类 `BaseDialogFragment`（`component/CommonTools`）的层级是：全屏 wrapper 布局 `wrapperLayout`（遮罩）→ 居中的内容根布局 `view`。遮罩上注册了 `setOnFastClickListener { safeDismiss() }` 实现"点外部关闭"。而内容根布局 `view` 本身不是可点击目标（无 clickable/无监听），`DialogFragment` 原生 `setCanceledOnTouchOutside` 的遮罩拦截在此自定义结构里并不存在——触摸落在内容区时事件一路冒泡到 `wrapperLayout`，被其点击监听当成"点击遮罩"执行 `safeDismiss()`，弹窗即关闭。调用侧 `MileageManagementActivity` 还显式设置了 `resetConfirmationDialog!!.isEnableClickMask = true` 允许遮罩点击关闭，两个因素叠加使"点内部也消失"必现（与 SIR-6455 拨号键穿透同属"事件冒泡被父级语义误接收"一类）。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt`、`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt`

```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
@@ -82,6 +82,10 @@ abstract class BaseDialogFragment : DialogFragment() {
         layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
         wrapperLayout?.addView(view, layoutParams)
+        // Consume taps inside the dialog content so they do not bubble to the
+        // full-screen wrapper, whose click listener dismisses the dialog.
+        view.isClickable = true
+        view.setOnClickListener { }
         parent?.addView(wrapperLayout)
         wrapperLayout?.setOnFastClickListener {
             safeDismiss()
```

```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
@@ -420,7 +420,7 @@ class MileageManagementActivity : BaseActivity(), EnergyLapseTouchLayout.OnLapse
-        resetConfirmationDialog!!.isEnableClickMask = true
+        resetConfirmationDialog!!.isEnableClickMask = false
```

## 为什么能修复
在基类把内容根布局 `view` 设为 `isClickable=true` 并挂空点击监听，内容区成为事件消费终点：点击落在弹窗内部时被子布局吃掉，不再冒泡到 `wrapperLayout` 的 `safeDismiss()`，而"重置/取消"按钮自己的监听不受影响；点击遮罩（wrapper 非 view 区域）仍走关闭逻辑。同时 `MileageManagementActivity` 将 `isEnableClickMask` 改为 `false`，对不可恢复的重置确认场景禁用"点外部关闭"，防误触语义更严谨。副作用：基类改动对所有继承 `BaseDialogFragment` 的弹窗生效——内容区点击一律不再穿透，这对"点内部关闭"型弹窗（若有）是行为变化，需全量回归确认；空 `setOnClickListener { }` 的写法简洁但语义靠注释支撑，建议封装为 `setClickThroughBlocked()` 之类的显式 API。

## 复盘与经验
- 自定义遮罩弹窗必须显式管理事件链：遮罩监听 + 内容区消费缺一不可，否则内容区点击必然被遮罩语义误接收——这类 bug 在多个项目反复出现（本批次就出现两起）。
- 破坏性操作（数据重置、删除）的确认弹窗应默认禁用遮罩点击关闭（`isEnableClickMask=false`），把关闭权收敛到明确按钮，防误触。
- 修复要尽量下沉到公共基类一次治本（本例改 `BaseDialogFragment`），但基类行为变更的影响面是全部子类，合入前需梳理使用方并回归。
