# SIR-7317 · 触发自定义按键单击效果，导致控制-座椅&手把页面返回顶部
- **提交**：`61e62417` | 2026-09-05 | sgh | Setting | bugfix（与兄弟提交 8c4fb73b 同单号配套）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
在"控制-座椅&手把"长页面滚动到中部后，触发 DVR（行车记录仪）相关状态刷新（怀疑 DVR 服务后台弹 toast 引发界面重绘/重排），页面滚动位置被重置回顶部。

## 根因分析
两层原因。触发层：`SafetyMonitorFragment.updateFormatGrayState()` 无条件调用 `mBinding.btnFormat.setGrayState(enabled)`，即使 enabled 值没变也执行——`setGrayState` 内部改 alpha/enabled 并触发 requestLayout/重绘；DVR 连接状态或 USB 状态的反复回调（如后台 toast 引起的界面重绘窗口）让该刷新被反复执行。重置层：`NestedScrollView` 在重排（relayout）时会将 `mScrollY` 重置为 0（控件注释原话："NestedScrollView 在重排/relayout……可能直接把 mScrollY 重置为 0"），于是每次无谓刷新都可能把整页滚回顶部。修复双管齐下：① `updateFormatGrayState()` 加 `lastFormatBtnEnabled` 幂等门闩，状态未变化直接 `return`，从源头消掉无效刷新；② 把必须执行的刷新包进扩展函数 `runWithScrollRestore`（定义于 `ViewExtension.kt`：先存 `scrollY`，执行 block，再挂 `OnPreDrawListener` 在下一帧绘制前 `scrollToProgrammatically(savedY)` 恢复）；布局根容器由 `androidx.core.widget.NestedScrollView` 换成兄弟提交 8c4fb73b 新增的 `SmartNestedScrollView`（记录 `lastUserScrollY` 并在 `onLayout` 发现被归零时按内容高度安全恢复，同时拦截非用户触发的 `scrollTo/scrollBy`）。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt；application/Setting/src/main/res/layout/fragment_vehicle_control.xml
```diff
--- application/Setting/.../fragment/SafetyMonitorFragment.kt
     private fun updateFormatGrayState() {
         val enabled = dvrConnected && hasUsb
+        if (lastFormatBtnEnabled == enabled) return
+        lastFormatBtnEnabled = enabled
         log("dashcam format button enabled=$enabled (dvrConnected=$dvrConnected, hasUsb=$hasUsb)")
-        mBinding.btnFormat.setGrayState(enabled)
+        (mBinding.root as? SmartNestedScrollView)?.runWithScrollRestore {
+            mBinding.btnFormat.setGrayState(enabled)
+        }
     }
--- application/Setting/src/main/res/layout/fragment_vehicle_control.xml
-    <androidx.core.widget.NestedScrollView
+    <com.yadea.setting.ui.widget.SmartNestedScrollView
         android:id="@+id/vc_nestedScrollView"
```

## 为什么能修复
幂等门闩消灭了"状态没变也重绘"这一根因主路径；即便真有布局变化，`SmartNestedScrollView` 的 onLayout 恢复 + `runWithScrollRestore` 的 PreDraw 前恢复双保险把滚动位置锚在用户停留处，页面不再跳顶。风险：自定义 ScrollView 拦截了程序化 `scrollTo`（需走 `scrollToProgrammatically`），其他代码若直接调 `scrollTo` 会失效；恢复逻辑依赖"内容高度不变"的假设，页面内容动态增减时恢复位置可能不准。

## 复盘与经验
- "页面莫名回顶"在 ScrollView 体系里优先怀疑两件事：频繁的全量 UI 刷新 + relayout 时 `mScrollY` 被重置；先加幂等门闩（状态不变不刷新）往往就能消掉大部分触发源。
- 自定义容器接管"滚动位置保持"（记录用户停留位、onLayout 恢复、区分用户/程序滚动）是长表单页的通用加固手段，一次投入全页受益。
- `runWithScrollRestore` 这类"保存-执行-下一帧恢复"的扩展函数把恢复逻辑从业务代码中剥离，值得作为标准工具沉淀。
