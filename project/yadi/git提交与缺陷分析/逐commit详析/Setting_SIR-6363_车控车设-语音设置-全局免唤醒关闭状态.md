# SIR-6363 · 全局免唤醒关闭后页面仍可滑动，开关被滑出视野

- **提交**：`cb305c68` | 2026-08-25 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车控车设-语音设置-全局免唤醒弹窗中，把"全局免唤醒"开关关闭后页面仍可上下滑动，内容区剩余内容不足以支撑滚动，开关被滑出可视区域找不回来。

## 根因分析
`GlobalWakeUpDialogFragment` 的布局 `dialog_global_wake_up.xml` 中内容区包在 `NestedScrollView` 里。开关打开时内容多、需要滚动；关闭时下方选项被收起/内容变少，`NestedScrollView` 高度为 `wrap_content` 但仍保留可滚动状态（滚动范围由子内容与可视区差值决定，且滑动惯性/嵌套滚动可越界表现），用户滑走后就停在无开关的位置，看不到开关也无法操作。该弹窗此前没有任何"按状态禁用滚动"的联动——布局里的 `NestedScrollView` 甚至没有 id，代码无法引用它，这是状态联动缺失的结构性原因。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/dialog_global_wake_up.xml`、`application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/GlobalWakeUpDialogFragment.kt`

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt
@@ -102,4 +104,15 @@ fun NestedScrollView.setupScrollFade(topFadeView: View, bottomFadeView: View) {
         postDelayed(hideRunnable, 200)
     })
+}
+
+/***
+ * 是否禁用NestedScrollView的滚动
+ * @param enabled false禁用
+ */
+@SuppressLint("ClickableViewAccessibility")
+fun NestedScrollView.setNestedScrollEnabled(enabled: Boolean) {
+    isNestedScrollingEnabled = enabled
+    isVerticalScrollBarEnabled = enabled
+    setOnTouchListener { _, _ -> !enabled }
 }
```

```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/GlobalWakeUpDialogFragment.kt
@@ -58,8 +61,11 @@ class GlobalWakeUpDialogFragment : BaseDialogFragment() {
             SettingsUtils.setGSetting(GLOBAL_WAKE_UP_FIRST, 0)
+
+            mBinding.nestedScrollView.setNestedScrollEnabled(it)
         }
         mBinding.swWakeup.isChecked = SettingsUtils.getGSetting(GLOBAL_WAKE_UP, 0) == 1
+        mBinding.nestedScrollView.setNestedScrollEnabled(mBinding.swWakeup.isChecked)
```

（布局文件中为 `NestedScrollView` 补加 `android:id="@+id/nested_scroll_view"`，使代码可引用。）

## 为什么能修复
新增扩展函数 `setNestedScrollEnabled` 三管齐下：`isNestedScrollingEnabled` 切断嵌套滚动、`isVerticalScrollBarEnabled` 隐藏滚动条、`setOnTouchListener` 返回 `!enabled` 在禁用时直接消费触摸事件阻止手势滚动。调用点做了两处覆盖：开关状态变化回调里实时联动（`setNestedScrollEnabled(it)`），以及弹窗初次创建时按已保存的开关状态初始化，保证"关闭状态进入弹窗"这一路径同样禁用滚动。小隐患：`setOnTouchListener` 覆盖会吞掉禁用态下子 View 的点击（ClickableViewAccessibility 警告已被压制），但该滚动区禁用态本就不应交互，可接受。

## 复盘与经验
- "内容多需要滚动、内容少不该滚动"的弹窗，滚动开关必须与内容状态联动，否则滑动会将关键控件（开关）滚出视野造成死锁感。
- 禁用 `NestedScrollView` 只设 `isNestedScrollingEnabled=false` 往往不够（自身 touch 滚动仍在），需要同时拦截 `onTouch`，这是 Android 常见坑。
- 把"按状态禁滚动"沉淀为 `ViewExtension` 扩展函数而非写在某个 Fragment 里，其他弹窗可直接复用——UI 行为控制类逻辑优先沉淀为扩展函数。
- 布局控件要养成给 id 的习惯：无 id 的控件在需要状态联动时代码拿不到引用，只能回头改布局。
