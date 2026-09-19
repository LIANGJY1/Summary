# SIR-6044 · 点击Docker栏设置按钮，车辆设置界面卡在驻车界面

- **提交**：`68a84ff8` | 2026-08-19 | sgh | Setting | bugfix（标题误标[feature]，缺陷库已标 mistag）
- **缺陷库**：等级 A · 频次 偶现-低于10% · 状态 关闭 · 域 主交互

## 问题
偶现：点击 Docker 栏设置按钮打开车辆设置时，界面卡在"驻车"页面的下拉状态上，显示异常且无法正常交互。

## 根因分析
车辆设置页使用公共下拉关闭控件 `LapseTouchLayout`（component/CommonTools），配合 `LapseTouchHelper` 实现"下拉关闭 view"手势：拖动过程中 `onDragging(translationY, progress)` 会持续把整个布局 `setTranslationY` 下移并把 `alpha` 降至 `1 - progress * 0.8f` 做渐隐；若拖动未达到关闭阈值则手势取消，回调 `onExitCancel()`。旧代码的 `onExitCancel()` 是空实现——取消关闭时布局的 `translationY` 与 `alpha` 永远停留在最后一次拖动的中间值，view 视觉上"卡"在下滑/半透明的驻车界面状态，且内部状态 `currentTranslationY` 也未归零，下次进入页面直接呈现残像。缺陷库根因"从顶部下来关闭 view 退出时没有重置 Y 坐标"与此一致（偶现正是因为只有"下拉后取消"这条路径才触发）。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt（+3）
```diff
@@ component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt @@
     override fun onExitCancel() {
+        this.translationY = 0f
+        this.alpha = 1f
+        currentTranslationY = 0f
     }
```

## 为什么能修复
手势取消路径上把视觉属性（`translationY`、`alpha`）和内部状态（`currentTranslationY`）一起复位到初始值，view 立即回到原位、不透明，"卡在驻车界面"的残像消失；`currentTranslationY=0` 也保证下次拖动 `getCurrentTranslation()` 从正确基准开始。无副作用——正常关闭走的 `onExitComplete` 路径不受影响。

## 复盘与经验
- 手势/动画控件必须成对处理 enter 与 cancel：`onDragging` 改了哪些属性，取消回调就要复位哪些，漏一个就会出现"卡在中间态"的偶现 bug。
- 偶现 + A 级的 bug 常藏在"未达阈值的取消分支"这类非主流路径上，排查时要覆盖手势的所有出口（complete/cancel/up/cancel event）。
- 中间态复位要同时覆盖视觉属性与内部状态变量，只复位其一会导致下次手势基准错位。
