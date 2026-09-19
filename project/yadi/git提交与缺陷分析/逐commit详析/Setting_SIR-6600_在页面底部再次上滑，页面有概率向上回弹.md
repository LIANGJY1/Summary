# SIR-6600 · 页面底部再次上滑，页面有概率向上回弹
- **提交**：`b97fdca9` | 2026-09-01 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 原因分析 · 域 主交互
- **注**：缺陷库根因字段为空、解决方案仅"修改属性"，以下以 diff 实际为准剖析；边界行为的框架级机理部分为推断。

## 问题
设置各页面滚动到底部后继续上滑，页面有概率整体向上回弹（滚动位置异常跳变）。

## 根因分析
这些设置页面的滚动容器（`SmartNestedScrollView`/`NestedScrollView`）在 9 个布局里统一配置了 `android:overScrollMode="never"`。这些页面又嵌在 `BaseContainerFragment` 的竖向 ViewPager2 容器中（`setupViewPager()`），底部到达边界后的手势要在"内层滚动容器 overscroll 消费"与"外层分页容器嵌套滚动"之间仲裁。`overScrollMode="never"` 关闭了内层容器对越界的标准 overscroll 处理，边界处的未消费增量被外层容器/`SmartNestedScrollView` 的位置恢复逻辑（`onLayout` 中 `scrollToProgrammatically(lastUserScrollY)` 等）以非用户预期的时机介入，偶发地把页面弹向上方。提交将"属性设置有误"定位在这一个属性上。

## 关键代码修改
改动文件：`application/Setting` 下 9 个布局 XML（fragment_assisted_driving / dashboard / display / safety_monitor / scene_mode / sound_effect / system / vehicle_control / volume）+ `DisplayContainerFragment.kt`
```diff
--- application/Setting/src/main/res/layout/fragment_display.xml（其余 8 个布局同样删除）
     <androidx.core.widget.NestedScrollView
         android:layout_width="match_parent"
         android:layout_height="match_parent"
-        android:overScrollMode="never"
         android:scrollbars="none">

--- .../ui/fragment/DisplayContainerFragment.kt
-        val fragments = mutableListOf<BaseFragment<out ViewDataBinding, out BaseViewModel>>(
+        val fragments = mutableListOf(
             DisplayFragment(),
             DashboardFragment()
         )
```
（`DisplayContainerFragment` 仅泛型显式类型改为类型推断，无行为变化。）

## 为什么能修复
删除 `overScrollMode="never"` 后，内层滚动容器恢复默认 overscroll 行为：到达底部继续上滑时由其自身按标准路径消费越界并呈现系统边界反馈，手势不再外溢给分页容器触发整页回弹。因缺陷库未记录详细根因，若此问题复现，建议沿"SmartNestedScrollView.onLayout 恢复逻辑 × ViewPager2 嵌套滚动"链路补充埋点验证。副作用：页面出现系统默认的边界视觉效果（辉光/拉伸），需设计确认是否可接受。

## 复盘与经验
- 自定义滚动控件（SmartNestedScrollView 带位置恢复、锁定逻辑）与系统 overscroll 属性叠加时，边界行为容易"有概率"异常——这类偶现滚动 bug 先排查 overscroll/nested scroll 配置组合。
- 全局批量设置某个 XML 属性（9 个布局同删）说明该属性当初是被当作无害装饰加入的，属性级"全局约定"在引入自定义容器后需要重新审视。
- 缺陷库根因留空时，提交信息里的 [why] 是唯一线索，工程上应强制 bugfix 提交写清机理，方便复盘。
